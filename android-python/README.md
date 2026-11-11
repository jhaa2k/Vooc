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

## Included in this foundation
Six workspaces (Media, Layers, Presets, Audio, Scene, Export), audio file selection and basic playback, animated spectrum preview, live-bound audio sliders, ten EQ controls, basic layer add/select/delete/duplicate/visibility, presets, scene settings and JSON project save/load.

## Known gaps
This is a first Android implementation foundation, not yet a complete feature-parity port or tested APK. Android-native PCM analysis/FFT, DSP processing, background media notification controls, full OpenGL scene effects and real MP4/WEBM/GIF rendering are not implemented yet. See `docs/PORTING_PLAN.md`.
