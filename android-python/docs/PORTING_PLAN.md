# Vooc Android port — implementation plan

This is a staged port, not yet feature parity. Keep the original browser implementation intact.

## Completed in this Python foundation

- Kivy six-workspace shell and project state persistence.
- Basic playlist/transport controls (Kivy audio-provider dependent).
- Layer and preset state management.
- Pure-Python radix-2 FFT with Hann window, logarithmic band mapping, RMS/peak metering and energy-onset beat detection.
- Visualizer API that can render normalized externally supplied spectrum bands.
- Thread-safe PCM analysis pipeline with scheduler-based UI delivery, lifecycle shutdown, and an app-level `feed_pcm_samples()` integration point.
- Optional PyJNIus Android `MediaPlayer` backend with session-scoped `Visualizer` capture, runtime permission request, and completion/error listener integration. Native mode selection is retained while the user picks a track.

## Newly implemented visualizer foundation

- Dedicated Visualizer workspace with Bars, Mirror Bars, Dots, Line and Radial spectrum render styles.
- Palette selection (Amber, Cyan, Purple, Green, White), adjustable band count, sensitivity and smoothing.
- Visualizer settings persist in project JSON and refresh the preview immediately.

## Newly implemented layer editing foundation

- Selected-layer inspector with name/text, X/Y position, scale, opacity and rotation controls.
- Lock/unlock and move layer up/down actions.
- Text layers render in the spectrum preview using their normalized position and transform settings.
- Existing project layer records remain compatible through default values when a field is absent.

## Remaining work, in dependency order

1. **Android playback hardening:** validate runtime permission, callback delivery, URI/path handling, pause/seek/completion, audio focus and lifecycle on physical Android devices. Legacy AudioManager focus request and focus-loss/duck handling are now wired into native playback. Validate on devices, then add a foreground playback service and notification controls; migrate to Media3 if required.
2. **Storage Access Framework:** resolve selected document URIs into a supported playback source or use a URI-capable native data source; current native adapter only accepts local filesystem paths.
3. **Connect analysis:** tune capture callback delivery and beat threshold/refractory logic against real music. Add waveform, BPM estimation, beat map and LFO analysis.
4. **Actual audio DSP:** implement and validate 10-band EQ, gain, bass shelf, pan, speed, reverb, delay, distortion and normalization in the audio output path. UI state changes alone are not audio processing.
5. **Scene graph and interaction:** Basic image-layer preview is now wired for decodable filesystem paths, with X/Y, scale, opacity and rotation. Next add Android SAF URI decoding and persisted URI grants, then OpenGL ES compositing, particles, mirror, RGB split, blur, motion blur and custom shaders; add drag handles, keyframes, undo/redo, font selection and preset import.
6. **Video export:** MediaCodec + MediaMuxer (H.264/AAC), deterministic frame timestamps, audio/video sync, progress, cancellation/resume, bitrate controls and scoped-storage destination URI.
7. **Validation:** unit/instrumentation tests; Android 8–15, ARM64, scoped storage, backgrounding, orientation, long tracks, memory pressure and export cancellation. Verify every advertised control on device.

## Current limitations

- Android `MediaPlayer` completion/error callbacks and Visualizer waveform capture are integrated but have not been validated on a physical device.
- Native playback currently accepts local filesystem paths, not SAF `content://` URIs.
- Audio focus request and transient/permanent loss handling are implemented at code level but not device-validated. Foreground service, lock-screen/notification media controls and background-resilient playback are not implemented.
- The DSP module analyzes samples but does not modify audio output.
- Kivy SoundLoader seek/pause/URI behavior varies by Android provider.
- Video export and full visual effects are not implemented.
- No successful APK build or physical-device validation is asserted by this plan.

Retain the repository's existing MIT license notice in derivative distributions.

 
## Image layer status

- Image layers expose an import/replace action and render decoded local image paths in the preview with the existing transform controls.
- Android SAF `content://` URI support and persisted read grants remain outstanding; provider compatibility must be validated on-device.
- The current image preview is a Kivy 2D canvas layer, not the planned OpenGL ES compositor.
