# Vooc architecture

Vooc is an Avee-Player-style visualizer written in Python + Kivy and packaged
for Android with Buildozer / python-for-android.

```
vooc/
  property_schema.py   SCHEMA: 11 modules -> categories -> sub-categories -> properties
  model.py             LayerModel / ProjectModel / ProjectStore (JSON persistence)
  viewmodel.py         LayerViewModel / ProjectViewModel (observable, debounced autosave)
  measure_engine.py    Measure dropdown curves (Nothing, Beat, TotalTime, ...)
  audio/
    providers.py       File (MediaPlayer + Visualizer), Microphone (AudioRecord),
                       System Audio (Visualizer on session 0), desktop fallbacks
    analysis.py        numpy FFT -> 256 log bands, stereo bands, waveform, rms, beat
    hub.py             AudioHub: AUDIOPROVIDER settings, offset, BPM grid, metadata
  engine/
    scene.py           SceneView: pipeline of Fbo passes, letterboxed preview
    layers.py          IMAGE / TEXT / BARS / PARTICLES renderers + General transform
    effects.py         BLUR / RGBSPLIT / MOTIONBLUR / MIRROR / CUSTOMSHADER passes
    gl.py              GLSL sources, 19 blend modes, GL blend state for layers
    shapes.py          17 shape paths (bars layout, image masks)
    textures.py        texture cache, generated textures
  platform/android.py  SAF picker, content:// copy, metadata + album art, permissions
  ui/
    theme.py           acid-green dark / light design tokens
    widgets.py         Material-style widgets and dialogs (no image assets)
    inspector.py       collapsible accordion property editor
    screens.py         Main Menu (2-column grid) and Details View
main.py                VoocApp: wires model, view-models, audio hub, scene and UI
```

## Data model and persistence

* Every editable property is declared once in `property_schema.SCHEMA` with a
  type (`slider`, `checkbox`, `color`, `dropdown`, `text`, `number`, `image`,
  `font`, `code`, `action`, `variables`), a default and its limits.
* A property is addressed by its path `"Category/Sub-category/Property"`.
* `LayerModel.values` holds a value for **every** path of its module. Values
  are coerced on write (`model._coerce`): sliders are clamped, dropdowns must
  be one of the listed options, colors are normalised to `#RRGGBBAA`.
* `ProjectModel.layers` is the z-order: index 0 is drawn first (at the back).
* `ProjectStore` writes `project.json` atomically into the app's private data
  directory. Loading migrates old files against the schema: unknown keys are
  dropped, new keys get defaults, missing modules are recreated. Close and
  reopen the app and the visualizer, playlist, current track and theme are
  exactly as they were.

## How the UI binds to the data model

```
 Row widget (slider, switch, swatch, ...)
      |  user edit
      v
 LayerViewModel.set(path, value) --> LayerModel.set()  (validate + store)
      |                         \
      |  emit(path, value)       \--> ProjectViewModel.changed()
      v                                  |-- revision += 1
 Inspector._on_change                    '-- request_save()  (debounced 0.6 s)
      '-- rows[path].refresh(value)
 SceneView.tick (every frame) reads LayerModel.values directly (read-only)
```

* The view-models have no Kivy imports, so they are unit tested
  (`tests/test_viewmodel.py`).
* Rows only call `vm.set`; they never write the model directly.
* Changes that come from elsewhere (Reset, uniform scale keeping Y = X,
  shader variables added/removed) reach the affected rows through the same
  observer channel.
* Accordion categories build their rows lazily on first expand.

## Rendering pipeline

`SceneView` groups layers into passes, in z-order:

```
[layers pass] -> [effect pass] -> [layers pass] -> ... -> letterboxed on screen
```

* A layers pass is an `Fbo` at the composition render resolution (capped to
  1280 px on the longest side for preview performance) that draws the
  previous pass output (or the background) and then its layers.
* An effect pass is a full-screen shader over everything beneath it; layers
  created above an effect are not affected by it (default order keeps TEXT on
  top so it stays sharp).
* The pipeline is rebuilt only when layer order / visibility / resolution
  changes; every other property is applied per frame without rebuilding.
* COMPOSITION: General transform wraps every layer, Render Settings define
  resolution (aspect ratio), FPS limit, background color, alpha, and
  "Clear Canvas on Frame" (off = trails).

### General transform (all layers)

`origin = Position * canvas - anchor * box`; rotation (0..1 = 0..360 deg) and
scale happen around Pivot. Measure Pos / Scale / Rot add `MeasureEngine`
offsets with A = amount and B = speed.

### Modules

* IMAGE: keep aspect / crop to fit, shape mask built as a polygon mesh
  (pixels outside the shape are never drawn = alpha 0), tint, brightness,
  contrast, saturation, hue, chroma key, blur, mask image (alpha / luminance /
  inverted). Generated Album Art uses the embedded cover of the current track
  (MediaMetadataRetriever) with the fallback image otherwise.
* TEXT: per-character layout (letter spacing, line height, left / center /
  right / justify), weight >= 600 is bold, italic, outline, shadow with blur,
  metadata variables `%title% %artist% %album% %time% %duration% %remaining%
  {ArtistOrTitle} {Title} {Artist}`, animations Fade / Slide / Zoom /
  Typewriter (re-triggered whenever the resolved text changes).
* BARS: 256 log bands resampled to Bar Count, Flip Input, Mono / Left / Right /
  Stereo, spring physics (Reaction Delay = rise, Softness = fall,
  Accumulated Delay per index), min / max height, shape path, three stackable
  segments with their own path, gradient, fixed height, multiplier, mirror and
  flip-every-other.
* PARTICLES: numpy simulation (count limit, warning above 2000), spawn area,
  direction modes, speed / random, lifetime 0..1 = 0..10 s, gravity (0.5 = none),
  force fields, side sine wave, perspective depth / FOV, size curve, fades,
  colors, velocity angle, trail stretch, mirror X / Y, custom image. Measure
  Overall Speed scales the whole simulation (accelerates on the beat).
* BLUREFFECT: three blurred passes at their own scale and tint summed into a
  glow, optional sharp content on top / under, content blend mode, mask.
* RGBSPLITEFFECT: per-channel offsets along an angle, glitch slices on beat for
  Glitch Duration.
* MOTIONBLUREFFECT: directional + zoom blur from the effect transform (relative =
  per-frame delta), a target image or audio volume.
* MIRROREREFFECT: horizontal / vertical / both / kaleidoscope, flip, axis offset,
  tint, optional target image.
* CUSTOMSHADER: user fragment `vec4 effect(vec4 color, sampler2D texture, vec2
  tex_coords, vec2 coords)` plus optional vertex shader. Compile / Apply
  compiles on the GPU; on error the last good shader keeps running and the
  message is shown. Declared variables become `uniform` declarations and get
  sliders / color pickers; built-ins: `time, resolution, beat, rms, bass`.
* AUDIOPROVIDER: File (MediaPlayer + Visualizer of its session), Microphone
  (AudioRecord PCM), System Audio (Visualizer on the global mix, session 0),
  volume, balance, preamp, measure source, audio offset (ms delay buffer),
  BPM grid with beat offset.

### Known approximations

* Layers use fixed-function GL blending. Alpha, PreMulAlpha, Screen, Add,
  AddAlpha and Multiply are exact; the rest are close equivalents (Darken /
  Lighten need GL_MIN / GL_MAX support). Effects implement all 19 blend modes
  exactly in GLSL.
* There is one composition; "Source Composition Index" and "Image Index" are
  stored but only index 0 is rendered. `LayerModel.children` is in the data
  model for sub-compositions.
* Android's Visualizer delivers 8-bit waveform data at about 20 Hz per capture
  (max capture size 1024), so analysis has lower resolution than on desktop.
* RECORD_AUDIO permission is mandatory for the Visualizer API, even when
  visualizing Vooc's own playback.
