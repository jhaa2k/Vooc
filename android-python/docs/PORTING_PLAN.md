# Remaining work for complete Vooc parity

1. Replace Kivy SoundLoader with Android MediaPlayer/Media3 via PyJNIus for reliable seeking, playlists, audio focus, background service and media controls.
2. Attach Android audio Visualizer / PCM capture and implement FFT bands, RMS, beat/onset detection, beat map, LFO and waveform analysis.
3. Implement actual audio DSP: 10-band EQ, gain, bass shelf, pan, speed, reverb, delay, distortion and normalization. Current controls update state only.
4. Implement the full scene graph in OpenGL ES: text/image, spectrum bars, particles, transforms, mirror, RGB split, blur, motion blur and custom shaders.
5. Implement MediaCodec + MediaMuxer export (H.264/AAC), timestamps, render progress, cancel/resume, scoped-storage destination URI and audio/video sync.
6. Add full inspector controls, drag handles, keyframes, undo/redo, preset import, export ranges, bitrate controls and project migration.
7. Test Android 8–15, ARM64, scoped storage, backgrounding, orientation, long tracks, memory pressure and export cancellation.

Do not claim these unfinished integrations are complete until tested on device. Retain the repository's existing MIT license notice in derivative distributions.
