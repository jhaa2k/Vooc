"""Audio providers: File / Microphone / System Audio, on Android and desktop.

Each provider pushes PCM to `on_pcm(samples, timestamp, right=None)` from its
own thread. The AudioHub analyses that PCM and hands snapshots to the renderer.
"""
import os
import threading
import time

import numpy as np

from ..platform.android import IS_ANDROID


class BaseProvider:
    kind = "base"
    has_transport = False

    def __init__(self, on_pcm, on_error, on_complete=None):
        self.on_pcm = on_pcm
        self.on_error = on_error
        self.on_complete = on_complete or (lambda: None)
        self.sample_rate = 44100

    # transport (file providers)
    def load(self, uri, autoplay=True):
        return False

    def play(self):
        return False

    def pause(self):
        return False

    def seek(self, seconds):
        return False

    def position(self):
        return 0.0

    def duration(self):
        return 0.0

    def is_playing(self):
        return False

    def set_volume(self, volume, balance):
        pass

    def start(self):
        pass

    def stop(self):
        pass

    def release(self):
        self.stop()


def _balance_gains(volume, balance):
    """balance 0..1 (0.5 = center) -> (left, right) gains."""
    b = (balance - 0.5) * 2.0
    left = volume * (1.0 - max(0.0, b))
    right = volume * (1.0 + min(0.0, b))
    return max(0.0, left), max(0.0, right)


# =========================================================================== Android
def _make_visualizer(session_id, on_pcm, on_error):
    """Attach android.media.audiofx.Visualizer to an audio session (0 = global mix)."""
    from jnius import autoclass, PythonJavaClass, java_method
    from ..android_audio import waveform_to_float
    Visualizer = autoclass("android.media.audiofx.Visualizer")
    vis = Visualizer(int(session_id))
    vis.setEnabled(False)
    size_range = Visualizer.getCaptureSizeRange()
    vis.setCaptureSize(int(min(2048, size_range[1])))
    try:
        vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
    except Exception:
        pass

    class Listener(PythonJavaClass):
        __javainterfaces__ = ["android/media/audiofx/Visualizer$OnDataCaptureListener"]
        __javacontext__ = "app"

        @java_method("(Landroid/media/audiofx/Visualizer;[BI)V")
        def onWaveFormDataCapture(self, visualizer, waveform, sampling_rate):
            try:
                on_pcm(waveform_to_float(waveform), time.monotonic())
            except Exception as exc:  # pragma: no cover
                on_error("PCM callback failed: %s" % exc)

        @java_method("(Landroid/media/audiofx/Visualizer;[BI)V")
        def onFftDataCapture(self, visualizer, fft, sampling_rate):
            return

    listener = Listener()
    vis.setDataCaptureListener(listener, int(Visualizer.getMaxCaptureRate()), True, False)
    vis.setEnabled(True)
    return vis, listener


class AndroidFileProvider(BaseProvider):
    """MediaPlayer playback + Visualizer bound to the player's audio session."""
    kind = "File"
    has_transport = True

    def __init__(self, on_pcm, on_error, on_complete=None):
        super().__init__(on_pcm, on_error, on_complete)
        from ..android_audio import AndroidMediaPlayerSource
        self.source = AndroidMediaPlayerSource(
            lambda s, t: None, on_error=on_error, on_completion=self._completed)
        # we attach our own visualizer (correct Java signature, numpy decode)
        self.source._request_capture_permission = lambda: None
        self.vis = None
        self._listener = None
        self._volume = (0.8, 0.8)

    def _completed(self):
        from kivy.clock import Clock
        Clock.schedule_once(lambda dt: self.on_complete(), 0)

    def _attach(self):
        self._detach()
        player = self.source.player
        if player is None:
            return
        try:
            self.vis, self._listener = _make_visualizer(
                player.getAudioSessionId(), self.on_pcm, self.on_error)
        except Exception as exc:
            self.on_error("Visualizer unavailable (allow microphone permission): %s" % exc)

    def _detach(self):
        if self.vis is not None:
            try:
                self.vis.setEnabled(False)
                self.vis.release()
            except Exception:
                pass
        self.vis = None
        self._listener = None

    def load(self, uri, autoplay=True):
        self._detach()
        ok = self.source.load(uri, autoplay=False)
        if not ok:
            return False
        self.source.set_volume(*self._volume)
        self._attach()
        if autoplay:
            self.play()
        return True

    def play(self):
        ok = self.source.play()
        if ok and self.vis is None:
            self._attach()
        return ok

    def pause(self):
        return self.source.pause()

    def seek(self, seconds):
        return self.source.seek(int(seconds * 1000))

    def position(self):
        return self.source.position_ms() / 1000.0

    def duration(self):
        return self.source.duration_ms() / 1000.0

    def is_playing(self):
        return self.source.is_playing()

    def set_volume(self, volume, balance):
        gains = _balance_gains(volume, balance)
        if gains != self._volume:
            self._volume = gains
            self.source.set_volume(*gains)

    def release(self):
        self._detach()
        self.source.release()


class AndroidSystemAudioProvider(BaseProvider):
    """Visualizes whatever is playing on the phone (global output mix, session 0)."""
    kind = "System Audio"

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.vis = None
        self._listener = None

    def start(self):
        if self.vis is not None:
            return
        try:
            self.vis, self._listener = _make_visualizer(0, self.on_pcm, self.on_error)
        except Exception as exc:
            self.on_error("System audio capture is not allowed on this device: %s" % exc)

    def stop(self):
        if self.vis is not None:
            try:
                self.vis.setEnabled(False)
                self.vis.release()
            except Exception:
                pass
        self.vis = None
        self._listener = None


class AndroidMicProvider(BaseProvider):
    kind = "Microphone"

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._thread = None
        self._running = False

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self):
        recorder = None
        try:
            from jnius import autoclass
            AudioRecord = autoclass("android.media.AudioRecord")
            AudioFormat = autoclass("android.media.AudioFormat")
            Source = autoclass("android.media.MediaRecorder$AudioSource")
            rate = 44100
            min_buf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                                                   AudioFormat.ENCODING_PCM_FLOAT)
            recorder = AudioRecord(Source.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                                   AudioFormat.ENCODING_PCM_FLOAT, max(min_buf, 16384))
            recorder.startRecording()
            chunk = 1024
            buf = [0.0] * chunk
            while self._running:
                # float[] + READ_BLOCKING overload is unambiguous for pyjnius;
                # pass_by_reference copies the samples back into `buf`.
                n = recorder.read(buf, 0, chunk, AudioRecord.READ_BLOCKING)
                if n and n > 0:
                    data = np.asarray(buf[:n], dtype=np.float32)
                    self.on_pcm(data, time.monotonic())
                else:
                    time.sleep(0.01)
        except Exception as exc:
            self.on_error("Microphone failed (allow microphone permission): %s" % exc)
        finally:
            if recorder is not None:
                try:
                    recorder.stop()
                    recorder.release()
                except Exception:
                    pass
            self._running = False

    def stop(self):
        self._running = False


# =========================================================================== Desktop
class DesktopFileProvider(BaseProvider):
    """miniaudio decode + sounddevice output when available; Kivy SoundLoader otherwise."""
    kind = "File"
    has_transport = True

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.samples = None
        self.pos = 0
        self.playing = False
        self.stream = None
        self.sound = None
        self.gains = (0.8, 0.8)
        self.lock = threading.Lock()
        try:
            import miniaudio  # noqa: F401
            import sounddevice  # noqa: F401
            self.native = True
        except Exception:
            self.native = False

    def load(self, uri, autoplay=True):
        self.release()
        path = uri[7:] if uri.startswith("file://") else uri
        if not os.path.exists(path):
            self.on_error("File not found: " + path)
            return False
        if self.native:
            try:
                import miniaudio
                decoded = miniaudio.decode_file(path, nchannels=2, sample_rate=self.sample_rate)
                data = np.frombuffer(decoded.samples, dtype=np.int16).astype(np.float32) / 32768.0
                with self.lock:
                    self.samples = data.reshape(-1, 2)
                    self.pos = 0
            except Exception as exc:
                self.on_error("Decode failed: %s" % exc)
                return False
        else:
            from kivy.core.audio import SoundLoader
            self.sound = SoundLoader.load(path)
            if self.sound is None:
                self.on_error("This audio format is not supported on this system.")
                return False
            self.sound.bind(on_stop=self._sound_stopped)
        if autoplay:
            self.play()
        return True

    def _sound_stopped(self, *_):
        if self.sound is not None and self.playing and self.sound.get_pos() <= 0.05:
            self.playing = False
            self.on_complete()

    def _callback(self, out, frames, time_info, status):
        with self.lock:
            if self.samples is None or not self.playing:
                out[:] = 0
                return
            chunk = self.samples[self.pos:self.pos + frames]
            n = len(chunk)
            out[:n, 0] = chunk[:, 0] * self.gains[0]
            out[:n, 1] = chunk[:, 1] * self.gains[1]
            out[n:] = 0
            self.pos += n
            finished = n < frames
            if finished:
                self.playing = False
        self.on_pcm(chunk[:, 0], time.monotonic(), right=chunk[:, 1])
        if finished:
            from kivy.clock import Clock
            Clock.schedule_once(lambda dt: self.on_complete(), 0)

    def play(self):
        if self.native:
            if self.samples is None:
                return False
            if self.stream is None:
                import sounddevice as sd
                self.stream = sd.OutputStream(samplerate=self.sample_rate, channels=2,
                                              dtype="float32", blocksize=1024,
                                              callback=self._callback)
                self.stream.start()
            self.playing = True
            return True
        if self.sound is not None:
            self.sound.play()
            self.playing = True
            return True
        return False

    def pause(self):
        self.playing = False
        if self.sound is not None:
            self._paused_at = self.sound.get_pos()
            self.sound.stop()
        return True

    def seek(self, seconds):
        if self.samples is not None:
            with self.lock:
                self.pos = int(max(0, min(seconds, self.duration())) * self.sample_rate)
        elif self.sound is not None:
            self.sound.seek(seconds)
        return True

    def position(self):
        if self.samples is not None:
            return self.pos / float(self.sample_rate)
        return self.sound.get_pos() if self.sound is not None else 0.0

    def duration(self):
        if self.samples is not None:
            return len(self.samples) / float(self.sample_rate)
        return self.sound.length if self.sound is not None else 0.0

    def is_playing(self):
        return self.playing

    def set_volume(self, volume, balance):
        self.gains = _balance_gains(volume, balance)
        if self.sound is not None:
            self.sound.volume = volume

    def release(self):
        self.playing = False
        if self.stream is not None:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
            self.stream = None
        if self.sound is not None:
            self.sound.unbind(on_stop=self._sound_stopped)
            self.sound.stop()
            self.sound.unload()
            self.sound = None
        self.samples = None


class DesktopMicProvider(BaseProvider):
    kind = "Microphone"

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.stream = None

    def start(self):
        try:
            import sounddevice as sd
            self.stream = sd.InputStream(samplerate=self.sample_rate, channels=1, blocksize=1024,
                                         callback=lambda d, f, t, s: self.on_pcm(d[:, 0].copy(), time.monotonic()))
            self.stream.start()
        except Exception as exc:
            self.on_error("Microphone unavailable: %s" % exc)

    def stop(self):
        if self.stream is not None:
            self.stream.stop()
            self.stream.close()
            self.stream = None


def create_provider(kind, on_pcm, on_error, on_complete):
    if IS_ANDROID:
        cls = {"File": AndroidFileProvider, "Microphone": AndroidMicProvider,
               "System Audio": AndroidSystemAudioProvider}[kind]
    else:
        cls = {"File": DesktopFileProvider, "Microphone": DesktopMicProvider,
               "System Audio": DesktopMicProvider}[kind]
    return cls(on_pcm, on_error, on_complete)
