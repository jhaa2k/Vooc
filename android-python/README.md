# Vooc Android

Vooc Android is a Kivy-based audio visualizer app.

## Features

- Audio file picker
- MP3, WAV, FLAC and OGG support
- Play, pause, next and previous controls
- Volume slider
- Real-time FFT spectrum visualizer
- Bars, mirror, radial, wave and particle visual modes
- Five color themes
- Beat-reactive visuals
- Android storage permission support

## Run on PC

```bash
cd android-python
python -m venv venv
source venv/bin/activate  # macOS/Linux
venv\Scripts\activate    # Windows

pip install -r requirements.txt
python main.py
```

## Build APK

Install Buildozer dependencies on Linux or WSL:

```bash
sudo apt update
sudo apt install -y git zip unzip openjdk-17-jdk python3-pip autoconf libtool pkg-config zlib1g-dev libncurses5-dev libncursesw5-dev libtinfo6 cmake libffi-dev libssl-dev
pip install buildozer cython==0.29.33
```

Build the APK:

```bash
cd android-python
buildozer android debug
```

The APK will be created in:

```text
bin/vooc-1.0-arm64-v8a-debug.apk
```

Install it on Android:

```bash
adb install bin/vooc-1.0-arm64-v8a-debug.apk
```

## Notes

- First Buildozer build can take 20-60 minutes.
- Android 13+ requires `READ_MEDIA_AUDIO` permission.
- For production release, use `buildozer android release`.
