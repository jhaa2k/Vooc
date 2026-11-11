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
- `vooc/dsp.py`: dependency-free radix-2 FFT, Hann windowing, logarithmic spectrum-band mapping, RMS/peak metering and an adaptive energy-onset beat detector.
- `Spectrum.set_spectrum(bands, beat=False)`: accepts normalized band amplitudes from a future PCM capture backend and renders them in the preview.

## Important implementation status

The FFT and analysis module is implemented as a reusable PCM-processing layer, but the app does **not yet capture decoded PCM from Android playback** or feed it into the visualizer. Until that bridge exists, the preview uses its clearly marked animated placeholder. Audio sliders still update project values; they do not yet process the playing audio. Background media controls, full OpenGL effects and actual video encoding are also not implemented.

No APK build or physical-device test is claimed for this update. See `docs/PORTING_PLAN.md` for the remaining integration sequence.
