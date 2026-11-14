"""Music-synced spectrum timeline (analysis v2).

The whole track is analysed once in the background (Android: platform codecs via
``com.jhaa2k.vooc.SpectrumDecoder``; desktop: WAV/ffmpeg with numpy). The
result is a timeline of frames that the renderer samples at the *current
playback position*, so the visualizer is always in sync with what you hear and
no microphone / AudioRecord permission is used.

Each frame stores, per spectrum, ``bands`` log-spaced bands (30 Hz .. 16 kHz)
as raw dB (byte = (dB + 100) / 100 * 255, no tilt / normalisation, so every
display option can be changed live), plus linear RMS and peak bytes.

Spectra per frame: 1 (mono) or 4 (stereo modes: mono, left, right, side).
Decimated PCM (int8, ``pcm_rate`` Hz) is kept for the real waveform layer.

Analysis parameters (``AnalysisConfig``): FFT size 512..8192, window Hann /
Hamming / Blackman / Rectangular / Blackman-Harris, overlap 0/25/50/75 % and
the channel mode. Changing them re-analyses the track.
"""
import base64
import math
import os
import threading

BANDS = 64
FPS = 43                 # nominal; real fps = sample_rate / hop
DB_FLOOR = -100.0
WINDOWS = ["Hann", "Hamming", "Blackman", "Rectangular", "Blackman-Harris"]
FFT_SIZES = [512, 1024, 2048, 4096, 8192]
PCM_RATE = 8000

ON_ANDROID = "ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ


def spectrum_index(channel, nspec):
    """Index of the stored spectrum for a channel name (falls back to mono)."""
    if nspec < 4:
        return 0
    return {"Left": 1, "Right": 2, "Side": 3}.get(channel, 0)


class AnalysisConfig:
    __slots__ = ("fft", "window", "overlap", "stereo", "bands")

    def __init__(self, fft=1024, window="Hann", overlap=50, stereo=False, bands=BANDS):
        self.fft = int(fft) if int(fft) in FFT_SIZES else 1024
        self.window = window if window in WINDOWS else "Hann"
        self.overlap = max(0, min(75, int(overlap)))
        self.stereo = bool(stereo)
        self.bands = max(16, min(256, int(bands)))

    @property
    def key(self):
        return (self.fft, self.window, self.overlap, self.stereo, self.bands)

    @property
    def nspec(self):
        return 4 if self.stereo else 1

    def hop(self, rate):
        h = int(round(self.fft * (1.0 - self.overlap / 100.0)))
        lo = max(32, rate // 120)
        hi = max(lo, rate // 20)
        return max(lo, min(hi, h))

    @classmethod
    def from_layer(cls, layer, auto_fft=None):
        """Read the AUDIOPROVIDER > Analyzer fields (tolerates older saves)."""
        g = getattr(layer, "values", {}) if layer is not None else {}
        A = "Analyzer/FFT/"
        size = g.get(A + "FFT Size", "1024")
        if size == "Auto":
            size = auto_fft or 2048
        channel = g.get("Analyzer/Channels/Channel Mode", "Mono")
        overlap = str(g.get(A + "Overlap", "50%")).rstrip("%")
        return cls(int(size), g.get(A + "Window", "Hann"), int(overlap or 0),
                   channel in ("Stereo", "Mid-Side"), int(g.get(A + "Analysis Bands", BANDS)))


class SpectrumTimeline:
    def __init__(self, data=None, fps=FPS, bands=BANDS, nspec=1, complete=True,
                 pcm=None, pcm_rate=PCM_RATE, pcm_channels=1, config=None):
        import numpy as np
        self.bands = int(bands)
        self.nspec = int(nspec)
        self.record = self.nspec * self.bands + 2
        self.fps = float(fps)
        self.config = config
        if data is None:
            data = np.zeros((0, self.record), np.uint8)
        self._data = np.ascontiguousarray(data, np.uint8).reshape(-1, self.record)
        self.count = len(self._data)
        self._cap = self.count
        self.pcm_rate = int(pcm_rate)
        self.pcm_channels = int(pcm_channels)
        self._pcm = np.zeros((0, self.pcm_channels), np.int8) if pcm is None else \
            np.asarray(pcm, np.int8).reshape(-1, self.pcm_channels)
        self.pcm_count = len(self._pcm)
        self._pcm_cap = self.pcm_count
        self.complete = complete
        self.duration = self.count / self.fps if self.fps else 0.0
        self.version = 0              # bumps when data grows (beat analysis cache key)
        self.stats = {"p99_db": -12.0, "mean_rms": 0.2}
        self._beats = []
        if complete:
            self._update_stats()
            self._beats = self._detect_beats()

    # ------------------------------------------------------------ building
    @classmethod
    def empty(cls, fps=FPS, bands=BANDS, nspec=1, pcm_rate=PCM_RATE, pcm_channels=1, config=None):
        return cls(None, fps, bands, nspec, False, None, pcm_rate, pcm_channels, config)

    def append_bytes(self, raw):
        import numpy as np
        data = np.frombuffer(raw, dtype=np.uint8)
        n = len(data) // self.record
        if n <= 0:
            return 0
        need = self.count + n
        if need > self._cap:
            cap = max(need, self._cap * 2, 256)
            grown = np.zeros((cap, self.record), np.uint8)
            grown[: self.count] = self._data[: self.count]
            self._data, self._cap = grown, cap
        self._data[self.count:need] = data[: n * self.record].reshape(n, self.record)
        self.count = need
        self.duration = self.count / self.fps
        self.version += 1
        return n

    def append_pcm(self, raw):
        import numpy as np
        data = np.frombuffer(raw, dtype=np.int8)
        n = len(data) // self.pcm_channels
        if n <= 0:
            return 0
        need = self.pcm_count + n
        if need > self._pcm_cap:
            cap = max(need, self._pcm_cap * 2, 8192)
            grown = np.zeros((cap, self.pcm_channels), np.int8)
            grown[: self.pcm_count] = self._pcm[: self.pcm_count]
            self._pcm, self._pcm_cap = grown, cap
        self._pcm[self.pcm_count:need] = data[: n * self.pcm_channels].reshape(n, self.pcm_channels)
        self.pcm_count = need
        return n

    def finish(self):
        self._data = self._data[: self.count]
        self._cap = self.count
        self._pcm = self._pcm[: self.pcm_count]
        self._pcm_cap = self.pcm_count
        self.complete = True
        self.version += 1
        self._update_stats()
        self._beats = self._detect_beats()

    def refresh_beats(self):
        self._update_stats()
        self._beats = self._detect_beats()

    # ------------------------------------------------------------ views
    def db(self, spectrum=0):
        """(count, bands) float32 dB of one stored spectrum."""
        import numpy as np
        s = max(0, min(self.nspec - 1, int(spectrum)))
        raw = self._data[: self.count, s * self.bands:(s + 1) * self.bands]
        return raw.astype(np.float32) * (-DB_FLOOR / 255.0) + DB_FLOOR

    @property
    def rms(self):
        return self._data[: self.count, self.nspec * self.bands].astype("float32") / 255.0

    @property
    def peak(self):
        return self._data[: self.count, self.nspec * self.bands + 1].astype("float32") / 255.0

    @property
    def frames(self):
        """Legacy 0..1 view (-66..-6 dB + 3 dB/oct tilt), used by beat detection."""
        import numpy as np
        tilt = np.arange(self.bands, dtype=np.float32) * (24.0 / self.bands)
        return np.clip((self.db(0) + tilt + 66.0) / 60.0, 0.0, 1.0)

    def _update_stats(self):
        import numpy as np
        if self.count < 2:
            return
        step = max(1, self.count // 4000)
        d = self.db(0)[::step]
        self.stats = {"p99_db": float(np.percentile(d, 99.0)),
                      "mean_rms": float(max(1e-4, self.rms[::step].mean()))}

    # ------------------------------------------------------------ sampling
    def _index(self, t):
        if self.count == 0:
            return None
        x = max(0.0, t) * self.fps
        i = int(x)
        if not self.complete and i >= self.count - 1:
            return None
        if i >= self.count - 1:
            return self.count - 1, 0.0
        return i, x - i

    def sample_db(self, t, spectrum=0):
        """dB row (float32, bands) at time t, linear interpolation; None if not decoded yet."""
        import numpy as np
        hit = self._index(t)
        if hit is None:
            return None, 0.0, 0.0
        i, f = hit
        s = max(0, min(self.nspec - 1, int(spectrum)))
        sl = slice(s * self.bands, (s + 1) * self.bands)
        a = self._data[i, sl].astype(np.float32)
        r0, p0 = self._data[i, -2], self._data[i, -1]
        if f > 0 and i + 1 < self.count:
            b = self._data[i + 1, sl].astype(np.float32)
            a = a * (1.0 - f) + b * f
            r0 = r0 * (1.0 - f) + self._data[i + 1, -2] * f
            p0 = p0 * (1.0 - f) + self._data[i + 1, -1] * f
        return a * (-DB_FLOOR / 255.0) + DB_FLOOR, float(r0) / 255.0, float(p0) / 255.0

    def sample(self, t):
        """Legacy: (bands 0..1 list, rms)."""
        row, rms, _ = self.sample_db(t)
        if row is None:
            return None, 0.0
        import numpy as np
        tilt = np.arange(self.bands, dtype=np.float32) * (24.0 / self.bands)
        return np.clip((row + tilt + 66.0) / 60.0, 0, 1).tolist(), rms

    def waveform(self, t, seconds, points, channel="Mono"):
        """Real waveform window ending at ``t`` (float32 -1..1, ``points`` samples)."""
        import numpy as np
        if self.pcm_count == 0:
            return None
        n = max(8, int(seconds * self.pcm_rate))
        end = int(max(0.0, t) * self.pcm_rate)
        start = end - n
        lo, hi = max(0, start), min(self.pcm_count, end)
        if hi <= lo:
            return np.zeros(points, np.float32)
        pcm = self._pcm[lo:hi].astype(np.float32) / 127.0
        if self.pcm_channels == 2:
            pcm = {"Left": pcm[:, 0], "Right": pcm[:, 1], "Side": (pcm[:, 0] - pcm[:, 1]) * 0.5}.get(
                channel, pcm.mean(axis=1))
        else:
            pcm = pcm[:, 0]
        if lo > start:
            pcm = np.concatenate([np.zeros(lo - start, np.float32), pcm])
        if len(pcm) < n:
            pcm = np.concatenate([pcm, np.zeros(n - len(pcm), np.float32)])
        xs = np.linspace(0, len(pcm) - 1, points)
        return np.interp(xs, np.arange(len(pcm)), pcm).astype(np.float32)

    # ------------------------------------------------------------ legacy beats
    def _detect_beats(self):
        beats = []
        if self.count < 4:
            return beats
        try:
            import numpy as np
            low = self.frames[:, : max(2, self.bands // 8)].mean(axis=1)
            flux = np.maximum(0.0, np.diff(low, prepend=low[0]))
            win = max(4, int(self.fps * 0.5))
            local = np.convolve(flux, np.ones(win) / win, mode="same")
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

    def beat_between(self, t0, t1):
        if t1 < t0 or not self._beats:
            return False
        import bisect
        k = bisect.bisect_right(self._beats, t0)
        return k < len(self._beats) and self._beats[k] <= t1


# ------------------------------------------------------------- numpy analysis
def _window(n, name):
    import numpy as np
    x = 2 * np.pi * np.arange(n) / (n - 1)
    if name == "Hamming":
        w = 0.54 - 0.46 * np.cos(x)
    elif name == "Blackman":
        w = 0.42 - 0.5 * np.cos(x) + 0.08 * np.cos(2 * x)
    elif name == "Rectangular":
        w = np.ones(n)
    elif name == "Blackman-Harris":
        w = 0.35875 - 0.48829 * np.cos(x) + 0.14128 * np.cos(2 * x) - 0.01168 * np.cos(3 * x)
    else:
        w = 0.5 - 0.5 * np.cos(x)
    return w.astype(np.float32)


def band_layout(bands, fft, rate):
    """(lo, hi, center_bin) per band, same as SpectrumDecoder.Analyzer."""
    fmin, fmax = 30.0, 16000.0          # fixed so every rate shares the same band axis
    bin_hz = rate / float(fft)
    out = []
    for b in range(bands):
        a = fmin * (fmax / fmin) ** (b / float(bands))
        z = fmin * (fmax / fmin) ** ((b + 1) / float(bands))
        out.append((max(1, int(math.ceil(a / bin_hz))), min(fft // 2, int(math.floor(z / bin_hz))),
                    math.sqrt(a * z) / bin_hz))
    return out


def analyse_pcm(pcm, rate, config=None, progress=None):
    """pcm: float32 (n, channels) -1..1. Returns a complete SpectrumTimeline."""
    import numpy as np
    cfg = config or AnalysisConfig()
    pcm = np.asarray(pcm, np.float32)
    if pcm.ndim == 1:
        pcm = pcm[:, None]
    left = pcm[:, 0]
    right = pcm[:, 1] if pcm.shape[1] > 1 else left
    mono = (left + right) * 0.5
    sources = [mono] + ([left, right, (left - right) * 0.5] if cfg.stereo else [])
    fft, bands, nspec = cfg.fft, cfg.bands, cfg.nspec
    hop = cfg.hop(rate)
    n = max(0, len(mono) // hop)
    window = _window(fft, cfg.window)
    scale = 2.0 / float(window.sum())
    layout = band_layout(bands, fft, rate)
    wide = [(b, lo, hi) for b, (lo, hi, _) in enumerate(layout) if hi >= lo]
    narrow = [(b, c) for b, (lo, hi, c) in enumerate(layout) if hi < lo]
    record = nspec * bands + 2
    data = np.zeros((n, record), np.uint8)
    padded = [np.concatenate([np.zeros(fft, np.float32), s]) for s in sources]
    chunk = 256
    for start in range(0, n, chunk):
        idx = np.arange(start, min(n, start + chunk))
        ends = (idx + 1) * hop + fft
        for si, src in enumerate(padded):
            seg = np.stack([src[e - fft:e] for e in ends])
            mags = np.abs(np.fft.rfft(seg * window, axis=1)) * scale
            out = np.zeros((len(idx), bands), np.float32)
            for b, lo, hi in wide:
                out[:, b] = mags[:, lo:hi + 1].max(axis=1)
            for b, c in narrow:
                if c >= fft // 2:
                    continue                    # above Nyquist: silent
                k0 = max(0, min(fft // 2 - 1, int(math.floor(c))))
                f = max(0.0, min(1.0, c - k0))
                out[:, b] = mags[:, k0] * (1 - f) + mags[:, k0 + 1] * f
            db = 20 * np.log10(out + 1e-10)
            data[idx, si * bands:(si + 1) * bands] = np.round(
                np.clip((db + 100.0) / 100.0, 0, 1) * 255).astype(np.uint8)
            if si == 0:
                data[idx, nspec * bands] = np.round(np.clip(np.sqrt((seg ** 2).mean(axis=1)) * 2.2, 0, 1) * 255)
                data[idx, nspec * bands + 1] = np.round(np.clip(np.abs(seg).max(axis=1), 0, 1) * 255)
        if progress:
            progress(min(0.99, (start + chunk) / max(1, n)))
    # decimated PCM
    step = rate / float(PCM_RATE)
    m = int(len(mono) / step)
    edges = (np.arange(m + 1) * step).astype(np.int64)
    chans = [left, right] if cfg.stereo else [mono]
    pcm8 = np.zeros((m, len(chans)), np.int8)
    if m > 0:
        for ci, ch in enumerate(chans):
            csum = np.concatenate([[0.0], np.cumsum(ch, dtype=np.float64)])
            avg = (csum[edges[1:]] - csum[edges[:-1]]) / np.maximum(1, edges[1:] - edges[:-1])
            pcm8[:, ci] = np.round(np.clip(avg, -1, 1) * 127).astype(np.int8)
    return SpectrumTimeline(data, rate / float(hop), bands, nspec, True, pcm8, PCM_RATE, len(chans), cfg)


def read_wav(path):
    import wave
    import numpy as np
    with wave.open(path, "rb") as w:
        rate, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width == 2:
        pcm = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 1:
        pcm = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif width == 4:
        pcm = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise ValueError("unsupported WAV sample width")
    pcm = pcm[: len(pcm) // ch * ch].reshape(-1, ch)
    if ch > 2:
        pcm = pcm[:, :2]
    return pcm, rate


def analyse_wav(path, config=None, progress=None, **_legacy):
    pcm, rate = read_wav(path)
    return analyse_pcm(pcm, rate, config, progress)


def analyse_ffmpeg(path, config=None, progress=None):
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
        subprocess.run([exe, "-v", "error", "-y", "-i", str(path), "-vn", "-ac", "2", "-ar", "44100",
                        "-acodec", "pcm_s16le", tmp], check=True, timeout=300)
        return analyse_wav(tmp, config, progress)
    except Exception as exc:
        print("Vooc ffmpeg decode failed: %s" % exc)
        return None
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass


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
        self.config = AnalysisConfig()

    def forget(self, path):
        for key in [k for k in self._cache if k[0] == path]:
            self._cache.pop(key, None)

    def load(self, path, config=None):
        self._job += 1
        job = self._job
        self.config = config or self.config
        self._stop_android()
        key = (path, self.config.key)
        if key in self._cache:
            self.busy = False
            timeline = self._cache[key]
            self.scheduler(lambda: self.on_ready(path, timeline))
            return
        self.busy = True
        self.progress = 0.0
        self.error = ""
        if ON_ANDROID and self._start_android(path, job):
            return
        threading.Thread(target=self._run, args=(path, job, self.config), daemon=True).start()

    def _set_progress(self, job, p):
        if job == self._job:
            self.progress = p
            self.scheduler(lambda: self.on_progress(p))

    def _store(self, path, config, timeline):
        self._cache[(path, config.key)] = timeline
        if len(self._cache) > 6:
            self._cache.pop(next(iter(self._cache)))

    def _run(self, path, job, config):
        timeline = None
        try:
            if str(path).lower().endswith(".wav"):
                try:
                    timeline = analyse_wav(path, config, progress=lambda p: self._set_progress(job, p))
                except Exception as exc:
                    print("Vooc WAV analysis failed, trying ffmpeg: %s" % exc)
            if timeline is None:
                timeline = analyse_ffmpeg(path, config, progress=lambda p: self._set_progress(job, p))
            if timeline is None and not self.error:
                self.error = "install ffmpeg for non-WAV spectrum on desktop"
        except Exception as exc:
            self.error = str(exc)
            print("Vooc spectrum analysis failed: %s" % exc)
        if job != self._job:
            return
        self.busy = False
        if timeline is not None:
            self._store(path, config, timeline)
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

        All jnius calls run on the Kivy main thread (app classes are not
        visible from Python worker threads).
        """
        cfg = self.config
        try:
            from jnius import autoclass
            from kivy.clock import Clock
            if SpectrumLoader._decoder_cls is None:
                SpectrumLoader._decoder_cls = autoclass("com.jhaa2k.vooc.SpectrumDecoder")
            decoder = SpectrumLoader._decoder_cls()
            decoder.start(str(path), cfg.bands, cfg.fft, WINDOWS.index(cfg.window), cfg.overlap,
                          cfg.stereo, PCM_RATE)
        except Exception as exc:
            self.error = "decoder unavailable: %s" % exc
            print("Vooc SpectrumDecoder start failed: %s" % exc)
            return False
        self._decoder = decoder
        state = {"announced": False, "beats_at": 0, "tl": None}

        def poll(_dt):
            if job != self._job:
                return False
            try:
                done = bool(decoder.isDone())
                tl = state["tl"]
                if tl is None and decoder.isFormatKnown():
                    tl = state["tl"] = SpectrumTimeline.empty(
                        float(decoder.getFps()), cfg.bands, int(decoder.getSpectra()),
                        int(decoder.getPcmRate()), int(decoder.getPcmChannels()), cfg)
                if tl is not None:
                    avail = int(decoder.getAvailableFrames())
                    if avail > tl.count:
                        b64 = decoder.readFrames(tl.count, avail, 0)
                        if b64:
                            tl.append_bytes(base64.b64decode(str(b64)))
                    pavail = int(decoder.getAvailablePcm())
                    if pavail > tl.pcm_count:
                        b64 = decoder.readPcm(tl.pcm_count, pavail)
                        if b64:
                            tl.append_pcm(base64.b64decode(str(b64)))
                    if tl.count - state["beats_at"] > tl.fps * 4:
                        state["beats_at"] = tl.count
                        tl.refresh_beats()
                    if not state["announced"] and tl.count > 0:
                        state["announced"] = True
                        self.on_ready(path, tl)
                self.progress = float(decoder.getProgress())
                self.on_progress(self.progress)
                if not done or (tl is not None and int(decoder.getAvailableFrames()) > tl.count):
                    return True
                err = str(decoder.getError() or "")
            except Exception as exc:
                err = str(exc)
                print("Vooc spectrum poll failed: %s" % exc)
            tl = state["tl"]
            self._poll = None
            self._decoder = None
            self.busy = False
            if tl is not None and tl.count > 0:
                tl.finish()
                self._store(path, cfg, tl)
                self.progress = 1.0
                self.on_progress(1.0)
                self.on_ready(path, tl)
            else:
                self.error = err or "could not decode audio"
                print("Vooc SpectrumDecoder: %s" % self.error)
                self.on_ready(path, None)
            return False

        self._poll = Clock.schedule_interval(poll, 0.12)
        return True


def _from_bytes(raw, bands=BANDS, fps=FPS):
    """Build a complete mono timeline from v2 records (tests / tools)."""
    import numpy as np
    data = np.frombuffer(raw, dtype=np.uint8)
    rec = bands + 2
    n = len(data) // rec
    return SpectrumTimeline(data[: n * rec].reshape(n, rec), fps, bands, 1, True)
