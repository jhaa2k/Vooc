"""Optional Android MediaPlayer + session-scoped Visualizer PCM source.

This captures the waveform for this app's own MediaPlayer session only. Android
does not permit arbitrary system-wide playback capture through this API.
"""
import threading
import time


class AndroidMediaPlayerSource:
    def __init__(self, on_pcm, on_error=None, on_completion=None):
        self.on_pcm = on_pcm
        self.on_error = on_error or (lambda message: None)
        self.on_completion = on_completion or (lambda: None)
        self.player = None
        self.visualizer = None
        self._capture_listener = None
        self._player_listener = None
        self._lock = threading.RLock()
        self._released = False

    @staticmethod
    def is_available():
        try:
            from jnius import autoclass
            autoclass("android.media.MediaPlayer")
            autoclass("android.media.audiofx.Visualizer")
            return True
        except Exception:
            return False

    def load(self, path, autoplay=False):
        """Prepare a local filesystem audio path using Android MediaPlayer."""
        player = None
        try:
            from jnius import autoclass, PythonJavaClass, java_method
            MediaPlayer = autoclass("android.media.MediaPlayer")
            source = self

            class PlayerListener(PythonJavaClass):
                __javainterfaces__ = [
                    "android/media/MediaPlayer$OnCompletionListener",
                    "android/media/MediaPlayer$OnErrorListener",
                ]
                __javacontext__ = "app"

                @java_method("(Landroid/media/MediaPlayer;)V")
                def onCompletion(self, media_player):
                    source.on_completion()

                @java_method("(Landroid/media/MediaPlayer;II)Z")
                def onError(self, media_player, what, extra):
                    source.on_error(
                        "Android MediaPlayer error (what=%s, extra=%s)." % (what, extra)
                    )
                    return True

            player = MediaPlayer()
            listener = PlayerListener()
            player.setOnCompletionListener(listener)
            player.setOnErrorListener(listener)
            player.setDataSource(str(path))
            player.prepare()

            with self._lock:
                self.release()
                self.player = player
                self._player_listener = listener
                self._released = False

            self._request_capture_permission()
            if autoplay:
                return self.play()
            return True
        except Exception as exc:
            if player is not None:
                try:
                    player.release()
                except Exception:
                    pass
            self.on_error("Android MediaPlayer load failed: " + str(exc))
            return False

    def _request_capture_permission(self):
        try:
            from android.permissions import request_permissions, Permission
            request_permissions([Permission.RECORD_AUDIO], self._permission_result)
        except Exception as exc:
            self.on_error("Microphone/audio capture permission unavailable: " + str(exc))

    def _permission_result(self, permissions, grants):
        granted = bool(grants) and all(bool(value) for value in grants)
        if not granted:
            self.on_error("Audio waveform permission was denied. Playback can still work.")
            return
        self._attach_visualizer()

    def _attach_visualizer(self):
        try:
            from jnius import autoclass, PythonJavaClass, java_method
            Visualizer = autoclass("android.media.audiofx.Visualizer")
            with self._lock:
                if self.player is None or self._released:
                    return
                session_id = self.player.getAudioSessionId()
                visualizer = Visualizer(session_id)
                capture_range = Visualizer.getCaptureSizeRange()
                visualizer.setCaptureSize(int(capture_range[1]))
                source = self

                class CaptureListener(PythonJavaClass):
                    __javainterfaces__ = ["android/media/audiofx/Visualizer$OnDataCaptureListener"]
                    __javacontext__ = "app"

                    @java_method("([BII)V")
                    def onWaveFormDataCapture(self, waveform, sampling_rate, capture_size):
                        try:
                            values = []
                            for item in waveform:
                                value = int(item)
                                if value < 0:
                                    value += 256
                                values.append((value - 128) / 128.0)
                            source.on_pcm(values, time.monotonic())
                        except Exception as exc:
                            source.on_error("PCM callback failed: " + str(exc))

                    @java_method("([BII)V")
                    def onFftDataCapture(self, fft, sampling_rate, capture_size):
                        return

                listener = CaptureListener()
                rate = int(Visualizer.getMaxCaptureRate())
                visualizer.setDataCaptureListener(listener, rate, True, False)
                visualizer.setEnabled(True)
                self.visualizer = visualizer
                self._capture_listener = listener
        except Exception as exc:
            self.on_error("Android Visualizer could not start: " + str(exc))

    def play(self):
        try:
            with self._lock:
                if self.player is None or self._released:
                    return False
                self.player.start()
                if self.visualizer is not None:
                    self.visualizer.setEnabled(True)
            return True
        except Exception as exc:
            self.on_error("Playback start failed: " + str(exc))
            return False

    def pause(self):
        try:
            with self._lock:
                if self.player is not None and self.player.isPlaying():
                    self.player.pause()
                    return True
        except Exception as exc:
            self.on_error("Playback pause failed: " + str(exc))
        return False

    def stop(self):
        try:
            with self._lock:
                if self.player is not None:
                    self.player.pause()
                    self.player.seekTo(0)
                    return True
        except Exception as exc:
            self.on_error("Playback stop failed: " + str(exc))
        return False

    def seek(self, position_ms):
        try:
            with self._lock:
                if self.player is not None:
                    duration = max(0, int(self.player.getDuration()))
                    target = max(0, min(int(position_ms), duration))
                    self.player.seekTo(target)
                    return True
        except Exception as exc:
            self.on_error("Playback seek failed: " + str(exc))
        return False

    def position_ms(self):
        try:
            with self._lock:
                return int(self.player.getCurrentPosition()) if self.player is not None else 0
        except Exception:
            return 0

    def duration_ms(self):
        try:
            with self._lock:
                return int(self.player.getDuration()) if self.player is not None else 0
        except Exception:
            return 0

    def is_playing(self):
        try:
            with self._lock:
                return bool(self.player is not None and self.player.isPlaying())
        except Exception:
            return False

    def release(self):
        with self._lock:
            self._released = True
            if self.visualizer is not None:
                try:
                    self.visualizer.setEnabled(False)
                    self.visualizer.release()
                except Exception:
                    pass
                self.visualizer = None
                self._capture_listener = None
            if self.player is not None:
                try:
                    self.player.setOnCompletionListener(None)
                    self.player.setOnErrorListener(None)
                    self.player.release()
                except Exception:
                    pass
                self.player = None
                self._player_listener = None
