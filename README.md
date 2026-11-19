# Vooc — Audio Visualizer Studio

Android music visualizer/editor built with Kotlin, Jetpack Compose and Media3.

## Active rebuild

Branch: `redesign/full-studio`

### Working implementation in the branch
- Media3 ExoPlayer playback service with a MediaSession for notification and lock-screen controls.
- Decoded PCM tap from this app's own player; no microphone or system-wide audio capture.
- FFT analysis with stereo left/right and mid/side modes, waveform, RMS/peak, bass/mid/treble bands, beat algorithms and manual BPM.
- Live Compose preview with distinct render paths for bars, mirrored bars, circle spectrum, multi-ring, concentric, ring, pulse, wave, waveform, scope, dots, lines, blocks, grid, particles, shape, text, image and background layers.
- Per-layer measure source, channel, sample count, transforms, rotation, opacity, sensitivity, smoothing, thickness, spacing, radius, particle count and RGB/secondary colors.
- Image import, text content editing, layer rename, duplicate, reorder, enable/disable and delete.
- Twelve starter compositions.
- Local auto-save plus JSON project import/export.
- Multi-track audio import, queue management, seeking, previous/next, shuffle, repeat and playback speed.
- Export workspace stores resolution and frame-rate settings.

### Not complete / not yet device-verified
- Actual MP4 encoding and audio/video muxing are not implemented. Export settings are not a video exporter.
- Full GPU effects pipeline (blur, glow, RGB split, motion blur, mirror and custom shaders) is not complete.
- Verify notification/background playback and imported image persistence on physical devices.
- Add automated UI/instrumentation tests, profile frame time and memory, and validate long audio sessions and large exports.
- Verify sustained 60 FPS on target devices. A CI build only verifies compilation and packaging.

Do not treat this branch as a release build until the outstanding export/effects work and physical-device validation are complete.
