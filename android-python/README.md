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

These callbacks and the permission/capture flow are code-level integrations only; they have not been validated on a physical Android device. The native backend currently accepts local filesystem paths, not Storage Access Framework content URIs. Android legacy AudioManager focus is now requested before native playback. Transient loss pauses and resumes on focus gain; duck requests lower the player volume; permanent loss does not auto-resume. A foreground playback service, lock-screen/notification media controls, and background-resilient playback are not implemented.

## Avee-inspired visualizer controls

The Visualizer workspace adds five render styles (Bars, Mirror Bars, Dots, Line and Radial), five color palettes, adjustable band count, sensitivity and smoothing. Settings are stored in project JSON and update the preview immediately. The renderer consumes FFT-derived band amplitudes when the PCM pipeline supplies them; its animated fallback is only a preview placeholder when no audio analysis data is available. This is a foundational 2D Kivy renderer, not yet the planned OpenGL ES effect/layer engine.

## Layer inspector update

The Layers workspace now includes a selected-layer inspector with editable name/text, normalized X/Y position, scale, opacity and rotation, plus lock/unlock and layer ordering controls. Text layers are drawn over the spectrum preview using these transform values. This is a basic 2D text-layer implementation; image decoding/placement, drag handles, font selection and full compositing remain future work.

## Important implementation status

The FFT and analysis module and its thread-safe UI-dispatch pipeline are implemented. The optional Android backend uses `MediaPlayer` plus `Visualizer` on that player's own audio session and forwards waveform samples into the PCM pipeline when permission and device policy allow it. This is not system-wide audio capture. The existing Kivy SoundLoader path remains the default unless native mode is explicitly selected. Audio sliders still update project values; they do not yet process the playing audio. Full OpenGL effects and actual video encoding are also not implemented.

No APK build or physical-device test is claimed for this update. Audio focus callbacks, interruption behavior, playback, listener callbacks, capture permission, and lifecycle behavior still require Android device validation. See `docs/PORTING_PLAN.md` for the remaining integration sequence.

 
## Image layer preview update

Image layers can open the platform file chooser and, when the picker returns a filesystem path that Kivy can decode, render the selected image in the preview. Image layers use the same normalized X/Y, scale, opacity and rotation inspector values as text layers. Android document providers may return `content://` URIs rather than filesystem paths; URI decoding and persisted URI permissions are not implemented yet, so image import is not guaranteed for every Android picker/provider. No APK build or device validation is claimed.

## Advanced hierarchical property editor

The **Properties** workspace adds a two-column category menu and accordion-based inspector for COMPOSITION, IMAGE, TEXT, BARS / SEGMENTS, PARTICLES, AUDIOPROVIDER, BLUR EFFECT, RGBSPLITEFFECT, MOTIONBLUREFFECT, MIRROR EFFECT and CUSTOM SHADER. The declarative definitions live in `vooc/property_schema.py`; the reusable Kivy controls and property-path binding live in `vooc/property_editor.py`.

- Dropdown lists are centralized and shared (including the complete Measure Engine and blend-mode lists).
- Each property is stored under a stable dotted key in `ProjectState.properties` and serialized with the project JSON. Property edits autosave the project file.
- Reset clears one category's overrides; the next view rebuild restores schema defaults.
- Supported generic controls currently include sliders, checkboxes, dropdowns, text/number fields and editable code/text fields. Color, font and image values currently use editable text/path fields; dedicated native pickers are not yet connected.
- Shader compile/apply and dynamic uniform creation currently emit persisted runtime request flags only. There is no GLSL compiler, GPU uniform bridge, or live custom-shader renderer connected yet.
- The full property hierarchy is now configurable and persisted, but not every property is consumed by the renderer. Measure Engine evaluation, particle simulation, multi-pass blur, chroma key, RGB split, motion blur, mirror compositing, album-art metadata, audio routing and final video rendering still require their respective engine implementations. The existing preview remains the current 2D Kivy renderer.
- The particle count control exposes a 5,000 maximum; use caution with high counts on mobile hardware. The UI does not silently spawn particles.

Run the schema checks with `python -m unittest discover -s tests -v`.
