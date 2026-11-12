# Vooc Android

Avee-Player-style audio visualizer built with Python + Kivy, packaged as an
Android APK with Buildozer. Acid-green Material UI with dark and light themes.

## Features

* Main Menu with live preview, player (open / previous / play / next / seek /
  queue) and a 2-column grid of the 11 modules: COMPOSITION, TEXT, BARS,
  PARTICLES, IMAGE, AUDIOPROVIDER, BLUREFFECT, RGBSPLITEFFECT,
  MOTIONBLUREFFECT, MIRROREREFFECT, CUSTOMSHADER.
* Details View with Back, title, Reset and Info, a compact live preview and a
  collapsible accordion of categories, sub-categories and controls (sliders,
  switches, RGBA color pickers, full dropdown lists, text, numbers, image / font
  pickers, GLSL code editor, shader variables).
* GPU renderer: layers drawn bottom to top, effects process everything below
  them, 19 blend modes, 17 shape paths, 27 Measure modes.
* Audio from a music file, the microphone or system audio (other apps).
* Everything (every property, playlist, current track, theme) is saved to JSON
  automatically and restored on the next launch.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for Models, ViewModels, UI
binding and the rendering pipeline.

## Run on PC

```bash
cd android-python
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python main.py
```

## Tests

```bash
cd android-python
python -m unittest discover -s tests -v
```

## Build APK

GitHub Actions (`.github/workflows/android-python-build.yml`) builds a debug
APK on every push and uploads it as the `vooc-debug-apk` artifact.

Local build on Linux / WSL:

```bash
cd android-python
buildozer android debug
```

## Permissions

* Microphone (RECORD_AUDIO): required by Android's Visualizer API to read the
  audio that is playing, and for the Microphone source.
* Music / images (READ_MEDIA_AUDIO, READ_MEDIA_IMAGES): files are opened through
  the system picker; access is persisted so the playlist works after restart.
