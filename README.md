# Vooc — Audio Visualizer Studio

Vooc is an Android music visualizer/editor built with Kotlin, Jetpack Compose and Media3.

## Current redesign branch

Branch: `redesign/full-studio`

### Implemented in this branch
- Media3 playback service and MediaSession integration for notification / lock-screen transport controls.
- In-app decoded PCM tap feeding the audio analyzer; no microphone or system-wide audio capture.
- FFT spectrum, waveform, RMS/peak, frequency bands and beat tracking through the analyzer module.
- Studio preview driven by live analyzer frames.
- Layer creation, enable/disable, delete and selection for bars, mirrored bars, rings, circles, waves, scope, particles, dots, lines, blocks, grid, shapes, text, image and background.
- Layer transform, opacity, sensitivity, smoothing and color controls.
- Starter presets and local JSON project save/restore.
- Multi-track import, queue, seek, previous/next, shuffle and repeat controls.

### Still required before calling this a full release
- Verify the media-session notification and background playback on physical Android devices.
- Complete the export renderer and MP4 muxing pipeline (audio/video synchronization, H.264/AAC, progress, cancellation and error recovery).
- Finish the full effect stack (blur, RGB split, motion blur, mirror and custom shader controls), image/text asset management and full preset library.
- Add project import/export, robust library metadata, automated UI/instrumentation tests and device performance profiling.
- Validate sustained 60 FPS rendering on supported devices and test large audio files / long exports.

The debug APK is built by GitHub Actions. A successful CI build proves compilation and packaging only; it does not replace device playback, rendering or export validation.
