"""Music-synced spectrum timeline.

The whole track is analysed once in the background (Android: platform codecs via
``com.jhaa2k.vooc.SpectrumDecoder``; desktop: WAV files with numpy). The result
is a list of frames (``FPS`` per second, ``BANDS`` log bands each, 0..1) that
the renderer samples at the current playback position, so the visualizer moves
exactly with the music and needs no microphone permission.
"""
import base64
import math
import os
import threading
import time

BANDS = 64
FPS = 43  # ~23 ms per frame

ON_ANDROID = "ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ


class SpectrumTimeline:
    def __init__(self, frames, rms, fps=FPS, bands=BANDS, complete=True):
        self.frames = frames          # numpy float32 (n, bands) or list of lists
        self.rms = rms                # numpy float32 (n,) or list
        self.fps = float(fps)
        self.bands = int(bands)
        self.count = len(rms)
        self.complete = complete      # False while still streaming from the decoder
        self.duration = self.count / self.fps if self.fps else 0.0
        self._beats = self._detect_beats()

    @classmethod
    def empty(cls, fps=FPS, bands=BANDS):
        import numpy as np
        tl = cls(np.zeros((0, bands), np.float32), np.zeros(0, np.float32), fps, bands, complete=False)
        tl._cap = 0
        return tl

    def append_bytes(self, raw):
        """Append interleaved frames ([bands bytes][rms byte] per frame)."""
        import numpy as np
        data = np.frombuffer(raw, dtype=np.uint8)
        n = len(data) // (self.bands + 1)
        if n <= 0:
            return 0
        data = data[: n * (self.bands + 1)].reshape(n, self.bands + 1).astype(np.float32) / 255.0
        need = self.count + n
        cap = getattr(self, "_cap", len(self.rms))
        if need > cap:
            cap = max(need, cap * 2, 256)
            frames = np.zeros((cap, self.bands), np.float32)
            rms = np.zeros(cap, np.float32)
            frames[: self.count] = self.frames[: self.count]
            rms[: self.count] = self.rms[: self.count]
            self.frames, self.rms, self._cap = frames, rms, cap
        self.frames[self.count:need] = data[:, : self.bands]
        self.rms[self.count:need] = data[:, self.bands]
        self.count = need
        self.duration = self.count / self.fps
        return n

    def finish(self):
        self.frames = self.frames[: self.count]
        self.rms = self.rms[: self.count]
        self._cap = self.count
        self.complete = True
        self._beats = self._detect_beats()

    def refresh_beats(self):
        self._beats = self._detect_beats()

    def _detect_beats(self):
        """Onset times from low-band energy flux (kick drums / bass hits)."""
        beats = []
        if self.count < 4:
            return beats
        try:
            import numpy as np
            low = np.asarray(self.frames)[: self.count, : max(2, self.bands // 8)].mean(axis=1)
            flux = np.maximum(0.0, np.diff(low, prepend=low[0]))
            win = max(4, int(self.fps * 0.5))
            kernel = np.ones(win) / win
            local = np.convolve(flux, kernel, mode="same")
            threshold = local * 1.6 + 0.02
            last = -1e9
            for i in np.nonzero(flux > threshold)[0]:
                t = i / self.fps
                if t - last >= 0.18:
                    beats.append(t)
                    last = t
        except Exception:
            pass
        return beats

    def sample(self, t):
        """Return (bands list, rms) at time t (seconds), linearly interpolated."""
        if self.count == 0:
            return None, 0.0
        x = max(0.0, t) * self.fps
        i = int(x)
        if not self.complete and i >= self.count - 1:
            return None, 0.0          # decoder has not reached this point yet
        if i >= self.count - 1:
            i = self.count - 1
            return list(self.frames[i]), float(self.rms[i])
        f = x - i
        a = self.frames[i]
        b = self.frames[i + 1]
        try:
            row = a * (1.0 - f) + b * f
            return row.tolist(), float(self.rms[i] * (1 - f) + self.rms[i + 1] * f)
        except TypeError:
            return [p * (1 - f) + q * f for p, q in zip(a, b)], float(self.rms[i])

    def beat_between(self, t0, t1):
        if t1 < t0 or not self._beats:
            return False
        import bisect
        k = bisect.bisect_right(self._beats, t0)
        return k < len(self._beats) and self._beats[k] <= t1


def _from_bytes(raw, bands=BANDS, fps=FPS):
    import numpy as np
    data = np.frombuffer(raw, dtype=np.uint8)
    n = len(data) // (bands + 1)
    frames = data[: n * bands].reshape(n, bands).astype(np.float32) / 255.0
    rms = data[n * bands: n * bands + n].astype(np.float32) / 255.0
    return SpectrumTimeline(frames, rms, fps, bands)


# ------------------------------------------------------------- desktop / WAV
def analyse_wav(path, bands=BANDS, fps=FPS, progress=None):
    """Same algorithm as SpectrumDecoder.java, for WAV files, using numpy."""
    import wave
    import numpy as np
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        ch = w.getnchannels()
        width = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width == 2:
        pcm = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 1:
        pcm = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif width == 4:
        pcm = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise ValueError("unsupported WAV sample width")
    if ch > 1:
        pcm = pcm[: len(pcm) // ch * ch].reshape(-1, ch).mean(axis=1)
    fft = 1024
    hop = max(64, rate // fps)
    n = max(0, (len(pcm) - 1) // hop)
    padded = np.concatenate([np.zeros(fft, np.float32), pcm])
    window = np.hanning(fft).astype(np.float32)
    fmin, fmax = 30.0, min(16000.0, rate / 2.0 - 1)
    bin_hz = rate / fft
    lo = [max(1, int(math.floor(fmin * (fmax / fmin) ** (b / bands) / bin_hz))) for b in range(bands)]
    hi = [min(fft // 2, max(lo[b] + 1, int(math.ceil(fmin * (fmax / fmin) ** ((b + 1) / bands) / bin_hz))))
          for b in range(bands)]
    frames = np.zeros((n, bands), np.float32)
    rms = np.zeros(n, np.float32)
    tilt = np.array([3.0 * (b * 8.0 / bands) for b in range(bands)], np.float32)
    chunk = 512
    for start in range(0, n, chunk):
        idx = np.arange(start, min(n, start + chunk))
        ends = (idx + 1) * hop + fft
        seg = np.stack([padded[e - fft:e] for e in ends])
        mags = np.abs(np.fft.rfft(seg * window, axis=1)) * (4.0 / fft)
        peaks = np.stack([mags[:, lo[b]:hi[b]].max(axis=1) for b in range(bands)], axis=1)
        db = 20 * np.log10(peaks + 1e-9) + tilt
        frames[idx] = np.clip((db + 66.0) / 60.0, 0, 1)
        rms[idx] = np.clip(np.sqrt((seg ** 2).mean(axis=1)) * 2.2, 0, 1)
        if progress:
            progress(min(0.99, (start + chunk) / max(1, n)))
    frames = np.round(frames * 255) / 255.0
    return SpectrumTimeline(frames.astype(np.float32), rms, fps, bands)


# ------------------------------------------------------------- loader
class SpectrumLoader:
    """Analyse tracks in the background; results delivered via callback."""

    _decoder_cls = None

    def __init__(self, on_ready, on_progress=None, scheduler=None):
        self.on_ready = on_ready
        self.on_progress = on_progress or (lambda p: None)
        self.scheduler = scheduler or (lambda fn: fn())
        self._cache = {}
        self._job = 0
        self._decoder = None
        self._poll = None
        self.progress = 0.0
        self.busy = False
        self.error = ""

    def load(self, path):
        self._job += 1
        job = self._job
        self._stop_android()
        if path in self._cache:
            self.busy = False
            timeline = self._cache[path]
            self.scheduler(lambda: self.on_ready(path, timeline))
            return
        self.busy = True
        self.progress = 0.0
        self.error = ""
        if ON_ANDROID and self._start_android(path, job):
            return
        threading.Thread(target=self._run, args=(path, job), daemon=True).start()

    def _set_progress(self, job, p):
        if job == self._job:
            self.progress = p
            self.scheduler(lambda: self.on_progress(p))

    def _store(self, path, timeline):
        self._cache[path] = timeline
        if len(self._cache) > 6:
            self._cache.pop(next(iter(self._cache)))

    def _run(self, path, job):
        """Desktop: numpy WAV analysis, or ffmpeg for any other format."""
        timeline = None
        try:
            if str(path).lower().endswith(".wav"):
                try:
                    timeline = analyse_wav(path, progress=lambda p: self._set_progress(job, p))
                except Exception as exc:
                    print("Vooc WAV analysis failed, trying ffmpeg: %s" % exc)
            if timeline is None:
                timeline = analyse_ffmpeg(path, progress=lambda p: self._set_progress(job, p))
            if timeline is None and not self.error:
                self.error = "install ffmpeg for non-WAV spectrum on desktop"
        except Exception as exc:
            self.error = str(exc)
            print("Vooc spectrum analysis failed: %s" % exc)
        if job != self._job:
            return
        self.busy = False
        if timeline is not None:
            self._store(path, timeline)
        self.scheduler(lambda: self.on_ready(path, timeline))

    # ---------------------------------------------------------- Android
    def _stop_android(self):
        if self._poll is not None:
            try:
                self._poll.cancel()
            except Exception:
                pass
            self._poll = None
        if self._decoder is not None:
            try:
                self._decoder.cancel()
            except Exception:
                pass
            self._decoder = None

    def _start_android(self, path, job):
        """Decode with the platform codecs on a Java thread and stream frames.

        Everything jnius touches here runs on the Kivy main thread: app classes
        cannot be found from Python worker threads (they get the system class
        loader), which is why the visualizer used to stay idle on phones.
        """
        try:
            from jnius import autoclass
            from kivy.clock import Clock
            if SpectrumLoader._decoder_cls is None:
                SpectrumLoader._decoder_cls = autoclass("com.jhaa2k.vooc.SpectrumDecoder")
            decoder = SpectrumLoader._decoder_cls()
            decoder.start(str(path), BANDS, FPS)
        except Exception as exc:
            self.error = "decoder unavailable: %s" % exc
            print("Vooc SpectrumDecoder start failed: %s" % exc)
            return False
        self._decoder = decoder
        timeline = SpectrumTimeline.empty()
        state = {"announced": False, "beats_at": 0}

        def poll(_dt):
            if job != self._job:
                return False
            try:
                done = bool(decoder.isDone())
                avail = int(decoder.getAvailableFrames())
                if avail > timeline.count:
                    b64 = decoder.readFrames(timeline.count, avail, BANDS)
                    if b64:
                        timeline.append_bytes(base64.b64decode(str(b64)))
                    if timeline.count - state["beats_at"] > FPS * 2:
                        state["beats_at"] = timeline.count
                        timeline.refresh_beats()
                    if not state["announced"] and timeline.count > 0:
                        state["announced"] = True
                        self.on_ready(path, timeline)      # start drawing right away
                self.progress = float(decoder.getProgress())
                self.on_progress(self.progress)
                if not done or int(decoder.getAvailableFrames()) > timeline.count:
                    return True
                err = str(decoder.getError() or "")
            except Exception as exc:
                err = str(exc)
                print("Vooc spectrum poll failed: %s" % exc)
            self._poll = None
            self._decoder = None
            self.busy = False
            if timeline.count > 0:
                timeline.finish()
                self._store(path, timeline)
                self.progress = 1.0
                self.on_progress(1.0)
                self.on_ready(path, timeline)
            else:
                self.error = err or "could not decode audio"
                print("Vooc SpectrumDecoder: %s" % self.error)
                self.on_ready(path, None)
            return False

        self._poll = Clock.schedule_interval(poll, 0.12)
        return True


# ------------------------------------------------------------- desktop / ffmpeg
def analyse_ffmpeg(path, bands=BANDS, fps=FPS, progress=None):
    """Decode any format with an ffmpeg binary (desktop only), then analyse."""
    import shutil
    import subprocess
    import tempfile
    exe = shutil.which("ffmpeg")
    if not exe:
        return None
    fd, tmp = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        subprocess.run([exe, "-v", "error", "-y", "-i", str(path), "-vn", "-ac", "1", "-ar", "44100",
                        "-acodec", "pcm_s16le", tmp], check=True, timeout=300)
        return analyse_wav(tmp, bands, fps, progress)
    except Exception as exc:
        print("Vooc ffmpeg decode failed: %s" % exc)
        return None
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass
