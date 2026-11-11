# Vooc Android port — implementation plan

This is a staged port, not yet feature parity. Keep the original browser implementation intact.

## Completed in this Python foundation

- Kivy six-workspace shell and project state persistence.
- Basic playlist/transport controls (Kivy audio-provider dependent).
- Layer and preset state management.
- Pure-Python radix-2 FFT with Hann window, logarithmic band mapping, RMS/peak metering and energy-onset beat detection.
- Visualizer API that can render normalized externally supplied spectrum bands.
- Thread-safe PCM analysis pipeline with scheduler-based UI delivery, lifecycle shutdown, and an app-level `feed_pcm_samples()` integration point.

## Remaining work, in dependency order

1. **Android playback and PCM bridge:** an opt-in PyJNIus `MediaPlayer` + session-scoped `Visualizer` adapter is now present and connected to the PCM pipeline. Validate runtime permission, callback delivery, URI/path handling, pause/seek/completion, audio focus and lifecycle on physical Android devices. Then add foreground playback service and notification controls; migrate to Media3 if required.
2. **Connect analysis:** implement the platform capture backend and call `VoocRoot.feed_pcm_samples()` with normalized mono PCM frames. The pipeline already runs `AudioAnalyzer`, throttles UI dispatch and routes results to `Spectrum.set_spectrum()` on the Kivy UI thread. Use monotonic timestamps and tune beat threshold/refractory logic against real music. Add waveform, BPM estimation, beat map and LFO analysis.
3. **Actual audio DSP:** implement and validate 10-band EQ, gain, bass shelf, pan, speed, reverb, delay, distortion and normalization in the audio output path. UI state changes alone are not audio processing.
4. **Scene graph and interaction:** OpenGL ES rendering for text/image, spectrum bars, particles, transforms, mirror, RGB split, blur, motion blur and custom shaders; add inspector controls, drag handles, keyframes, undo/redo and preset import.
5. **Video export:** MediaCodec + MediaMuxer (H.264/AAC), deterministic frame timestamps, audio/video sync, progress, cancellation/resume, bitrate controls and scoped-storage destination URI.
6. **Validation:** unit/instrumentation tests; Android 8–15, ARM64, scoped storage, backgrounding, orientation, long tracks, memory pressure and export cancellation. Verify every advertised control on device.

## Current limitations

- The optional Android MediaPlayer/Visualizer backend is wired to the analyzer, but its waveform callback has not been validated on a physical device. The default Kivy path still shows the synthetic placeholder.
- The DSP module analyzes samples but does not modify audio output.
- Kivy SoundLoader seek/pause/URI behavior varies by Android provider.
- Video export, native notification controls and full visual effects are not implemented.
- No successful APK build or physical-device validation is asserted by this plan.

Retain the repository's existing MIT license notice in derivative distributions.
