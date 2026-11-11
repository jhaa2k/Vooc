"""Optional Android MediaPlayer + session-scoped Visualizer PCM source.

Waveform capture is limited to this app's MediaPlayer session. Audio focus is
requested before playback; transient interruptions pause/resume or duck audio.
"""
import threading
import time


class AndroidMediaPlayerSource:
    def __init__(self, on_pcm, on_error=None, on_completion=None, on_focus_change=None):
        self.on_pcm = on_pcm
        self.on_error = on_error or (lambda message: None)
        self.on_completion = on_completion or (lambda: None)
        self.on_focus_change = on_focus_change or (lambda state: None)
        self.player = None
        self.visualizer = None
        self._capture_listener = None
        self._player_listener = None
        self._focus_listener = None
        self._audio_manager = None
        self._audio_focus_held = False
        self._resume_after_focus = False
        self._user_paused = False
        self._ducked = False
        self._lock = threading.RLock()
        self._released = False

    @staticmethod
    def is_available():
        try:
            from jnius import autoclass
            autoclass("android.media.MediaPlayer")
            autoclass("android.media.audiofx.Visualizer")
            autoclass("android.media.AudioManager")
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
                self._user_paused = False
                self._resume_after_focus = False

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
                visualizer = Visualizer(self.player.getAudioSessionId())
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
                visualizer.setDataCaptureListener(
                    listener, int(Visualizer.getMaxCaptureRate()), True, False
                )
                visualizer.setEnabled(True)
                self.visualizer = visualizer
                self._capture_listener = listener
        except Exception as exc:
            self.on_error("Android Visualizer could not start: " + str(exc))

    def _request_audio_focus(self):
        try:
            from jnius import autoclass, PythonJavaClass, java_method
            PythonActivity = autoclass("org.kivy.android.PythonActivity")
            AudioManager = autoclass("android.media.AudioManager")
            Context = autoclass("android.content.Context")
            source = self

            class FocusListener(PythonJavaClass):
                __javainterfaces__ = ["android/media/AudioManager$OnAudioFocusChangeListener"]
                __javacontext__ = "app"

                @java_method("(I)V")
                def onAudioFocusChange(self, focus_change):
                    source._handle_audio_focus_change(int(focus_change))

            activity = PythonActivity.mActivity
            manager = activity.getSystemService(Context.AUDIO_SERVICE)
            listener = self._focus_listener
            if listener is None:
                listener = FocusListener()
                self._focus_listener = listener
            result = int(manager.requestAudioFocus(
                listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ))
            granted = result == int(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            with self._lock:
                self._audio_manager = manager
                self._audio_focus_held = granted
            if not granted:
                self.on_focus_change("denied")
            return granted
        except Exception as exc:
            self.on_error("Android audio focus request failed: " + str(exc))
            # Keep playback usable on providers/devices where the focus bridge fails.
            return True

    def _handle_audio_focus_change(self, change):
        try:
            from jnius import autoclass
            AudioManager = autoclass("android.media.AudioManager")
            with self._lock:
                player = self.player
                if player is None or self._released:
                    return
                if change == AudioManager.AUDIOFOCUS_GAIN:
                    if self._ducked:
                        player.setVolume(1.0, 1.0)
                        self._ducked = False
                    should_resume = self._resume_after_focus and not self._user_paused
                    self._resume_after_focus = False
                    if should_resume:
                        player.start()
                    state = "resumed" if should_resume else "focus_gained"
                elif change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    player.setVolume(0.2, 0.2)
                    self._ducked = True
                    state = "ducked"
                elif change in (
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                ):
                    was_playing = bool(player.isPlaying())
                    self._resume_after_focus = (
                        was_playing or self._resume_after_focus
                    ) and change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    if was_playing:
                        player.pause()
                    if self._ducked:
                        player.setVolume(1.0, 1.0)
                        self._ducked = False
                    if change == AudioManager.AUDIOFOCUS_LOSS:
                        self._resume_after_focus = False
                    state = "interrupted" if change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT else "focus_lost"
                else:
                    return
            self.on_focus_change(state)
        except Exception as exc:
            self.on_error("Audio focus change handling failed: " + str(exc))

    def _abandon_audio_focus(self):
        with self._lock:
            manager = self._audio_manager
            listener = self._focus_listener
            held = self._audio_focus_held
            self._audio_focus_held = False
            self._resume_after_focus = False
            self._ducked = False
        if manager is not None and listener is not None and held:
            try:
                manager.abandonAudioFocus(listener)
            except Exception as exc:
                self.on_error("Could not abandon Android audio focus: " + str(exc))

    def play(self):
        try:
            with self._lock:
                if self.player is None or self._released:
                    return False
                self._user_paused = False
            if not self._request_audio_focus():
                return False
            with self._lock:
                if self.player is None or self._released:
                    return False
                if self._ducked:
                    self.player.setVolume(1.0, 1.0)
                    self._ducked = False
                self.player.start()
                if self.visualizer is not None:
                    self.visualizer.setEnabled(True)
            self.on_focus_change("playing")
            return True
        except Exception as exc:
            self.on_error("Playback start failed: " + str(exc))
            return False

    def pause(self):
        try:
            with self._lock:
                if self.player is not None and self.player.isPlaying():
                    self.player.pause()
                    self._user_paused = True
                else:
                    self._user_paused = True
            self._abandon_audio_focus()
            self.on_focus_change("paused")
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
                    self._user_paused = True
            self._abandon_audio_focus()
            self.on_focus_change("stopped")
            return True
        except Exception as exc:
            self.on_error("Playback stop failed: " + str(exc))
            return False

    def seek(self, position_ms):
        try:
            with self._lock:
                if self.player is not None:
                    duration = max(0, int(self.player.getDuration()))
                    self.player.seekTo(max(0, min(int(position_ms), duration)))
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
        self._abandon_audio_focus()
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
            self._focus_listener = None
            self._audio_manager = None
