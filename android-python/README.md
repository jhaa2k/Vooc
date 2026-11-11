# Vooc — Python Android port (parallel implementation)

This folder adds a Python/Kivy Android implementation alongside the original browser app. The existing root-level `index.html`, `styles.css`, and `app.js` are intentionally untouched.

## Build

On Linux with Java 17 and Android SDK/NDK configured:

```bash
python -m pip install buildozer
buildozer android debug
```

Run the desktop UI preview with:

```bash
python -m pip install -r requirements.txt
python main.py
```

Run the pure-Python DSP tests from this directory:

```bash
python -m unittest discover -s tests -v
```

## Included

- Six workspaces: Media, Layers, Presets, Audio, Scene and Export.
- Audio file selection, play/pause, stop, seek, previous/next, playlist selection and repeat modes (subject to the installed Kivy audio provider).
- Project JSON save/load, basic layer management, presets, scene settings and ten EQ control values.
- `vooc/dsp.py`: dependency-free radix-2 FFT, Hann windowing, logarithmic band mapping, RMS/peak metering and an adaptive energy-onset beat detector.
- `Spectrum.set_spectrum(bands, beat=False)`: renders normalized band amplitudes.
- `vooc/pcm_pipeline.py`: thread-safe PCM submission pipeline with scheduler-based UI dispatch and shutdown handling.
- `vooc/android_audio.py`: optional Android `MediaPlayer` backend with session-scoped `Visualizer` waveform capture and completion/error callbacks. The Media workspace exposes an opt-in native-player action.
- `VoocRoot.feed_pcm_samples(samples, timestamp=None)`: integration entry point for normalized mono PCM analysis.

## Native playback lifecycle update

The native backend now registers Android `MediaPlayer.OnCompletionListener` and `OnErrorListener` callbacks. Completion is marshalled onto Kivy's UI thread before playlist/repeat handling, with the existing duration poll retained as a fallback. Selecting the native-player mode before choosing a file is remembered; the next imported/selected track is opened by the native backend rather than silently falling back to Kivy SoundLoader. Loading a new native track releases the previous player/session.

These callbacks and the permission/capture flow are code-level integrations only; they have not been validated on a physical Android device. The native backend currently accepts local filesystem paths, not Storage Access Framework content URIs. Android audio focus, a foreground playback service, lock-screen/notification media controls, and background-resilient playback are not implemented.

## Important implementation status

The FFT and analysis module and its thread-safe UI-dispatch pipeline are implemented. The optional Android backend uses `MediaPlayer` plus `Visualizer` on that player's own audio session and forwards waveform samples into the PCM pipeline when permission and device policy allow it. This is not system-wide audio capture. The existing Kivy SoundLoader path remains the default unless native mode is explicitly selected. Audio sliders still update project values; they do not yet process the playing audio. Full OpenGL effects and actual video encoding are also not implemented.

No APK build or physical-device test is claimed for this update. Playback, listener callbacks, capture permission, and lifecycle behavior still require Android device validation. See `docs/PORTING_PLAN.md` for the remaining integration sequence.
