"""Declarative Avee-style property schema.

This module is the single source of truth for every editable property.
The UI (accordion inspector), the data model (defaults / JSON migration) and
the render engine all read the same keys, so adding a property here makes it
appear in the editor, persist to disk and become readable by the renderer.

Hierarchy:  MODULE -> Category (accordion) -> Sub-category (header) -> Property

Property path used everywhere:  "Category/Sub-category/Property"
"""
from collections import OrderedDict as OD

# ---------------------------------------------------------------- option lists
BLEND = ["Alpha", "PreMulAlpha", "Screen", "Add", "AddAlpha", "Multiply",
         "Darken", "Lighten", "ColorDodge", "ColorBurn", "Overlay", "SoftLight",
         "HardLight", "Difference", "Exclusion", "Hue", "Saturation", "Color",
         "Luminosity", "Normal", "Subtract"]

ANCHOR = ["unset", "start", "center", "end", "top", "bottom", "topLeft",
          "topCenter", "topRight", "bottomLeft", "bottomCenter", "bottomRight",
          "centerLeft", "centerRight", "justify", "fill", "stretch"]

MEASURE = ["Nothing", "Beat", "TotalTime", "TotalTimeBackward",
           "TotalTimeWhenPlaying", "TotalTimeAndBeat", "TrackPosition",
           "BeatRandomShake", "BeatCamShakeRotMore", "BeatCamShakeRotLess",
           "ConstantShakeRotMore", "ConstantShakeRotLess", "BeatTriggerAnim",
           "BeatCamShakeOnly", "BeatPulseScale", "BeatFlashColor",
           "BeatGlowIntensity", "BeatHorizontalStretch", "BeatVerticalBounce",
           "ConstantSlowRotate", "ConstantOrbitMove", "ConstantLinearScroll",
           "TrackProgressPercentage", "TrackRemainingTime",
           "TrackIntroOutroTrigger", "VolumeLevelResponsive",
           "PitchFrequencyResponsive", "Frequency", "Amplitude", "Kick", "Snare",
           "HiHat", "Downbeat", "BeatProgress", "BarProgress", "LowBand", "MidBand",
           "HighBand"] + ["CustomBand%d" % i for i in range(1, 9)]

SHAPES = ["None", "Circle", "SidedPolygon", "Line", "HorizontalLine",
          "VerticalLine", "Rectangle", "Square", "Triangle", "Star", "Heart",
          "WaveformPath", "CustomPath", "Oval", "Capsule", "Pentagon", "Hexagon"]

SHADER_PRESETS = ["Custom", "Zoom Blur", "Twirl", "Kaleidoscope", "Reflection", "3D Box",
                  "Color Correction", "Vignette", "Film Grain"]

FFT_SIZES = ["Auto", "512", "1024", "2048", "4096", "8192"]
WINDOWS = ["Hann", "Hamming", "Blackman", "Rectangular", "Blackman-Harris"]
CHANNELS = ["Mono", "Left", "Right", "Stereo", "Mid", "Side"]
REACTIVITY = ["Inherit", "Beat", "Frequency", "Amplitude", "Nothing"]

FONT_WEIGHTS = [str(w) for w in range(100, 1000, 100)]
VARIABLE_TYPES = ["None", "Float", "Vector2", "Vector3", "Color"]

# Order of the main-menu grid (exact names requested).
MODULES = ["COMPOSITION", "TEXT", "BARS", "PARTICLES", "IMAGE", "WAVEFORM", "SHAPE", "AUDIOPROVIDER",
           "BLUREFFECT", "RGBSPLITEFFECT", "MOTIONBLUREFFECT", "MIRROREREFFECT",
           "CUSTOMSHADER"]

EFFECT_MODULES = ["BLUREFFECT", "RGBSPLITEFFECT", "MOTIONBLUREFFECT",
                  "MIRROREREFFECT", "CUSTOMSHADER"]


# ---------------------------------------------------------------- field helpers
def slider(default=0.0, lo=0.0, hi=1.0, step=0.001, **kw):
    """Sliders are normalized 0.000-1.000 unless a wider range is given."""
    return dict(type="slider", default=float(default), min=float(lo),
                max=float(hi), step=step, **kw)


def check(default=False, **kw):
    return dict(type="checkbox", default=bool(default), **kw)


def color(default="#FFFFFFFF", **kw):
    """Colors are RGBA hex strings: #RRGGBBAA (last two digits = alpha)."""
    return dict(type="color", default=default, **kw)


def dropdown(options, default=None, **kw):
    return dict(type="dropdown", options=list(options),
                default=options[0] if default is None else default, **kw)


def text(default="", multiline=False, **kw):
    return dict(type="text", default=default, multiline=multiline, **kw)


def number(default=0, lo=0, hi=99999, **kw):
    return dict(type="number", default=default, min=lo, max=hi, **kw)


def image(**kw):
    return dict(type="image", default="", **kw)


def font(default="Roboto", **kw):
    return dict(type="font", default=default, **kw)


def file(default="", patterns=("*",), **kw):
    """A file path with a Choose button (fonts, lyrics, ...)."""
    return dict(type="file", default=default, patterns=list(patterns), **kw)


def code(default="", **kw):
    return dict(type="code", default=default, **kw)


def action(label, **kw):
    return dict(type="action", default=label, label=label, **kw)


# ---------------------------------------------------------------- shared blocks
def general_category(tag=""):
    """Standard Layer Settings used by every visual layer."""
    return OD([
        ("Layer", OD([
            ("Tag", text(tag)),
            ("Visible", check(True)),
            ("Locked", check(False, help="Locked layers cannot be edited, moved or deleted")),
            ("Opacity", slider(1.0)),
            ("Blend Mode", dropdown(BLEND, "Alpha")),
        ])),
        ("Position", OD([
            ("Position X", slider(0.5)),
            ("Position Y", slider(0.5)),
            ("Anchor X", dropdown(ANCHOR, "center")),
            ("Anchor Y", dropdown(ANCHOR, "center")),
            ("Alignment Position X", slider(0.5)),
            ("Alignment Position Y", slider(0.5)),
        ])),
        ("Scale & Rotation", OD([
            ("Scale Is Uniform", check(True)),
            ("Scale X", slider(1.0, 0, 10)),
            ("Scale Y", slider(1.0, 0, 10)),
            ("Rotation", slider(0.0, help="0..1 = 0..360 degrees")),
            ("Transform Origin", dropdown(["Custom", "Center", "Top Left", "Top Right", "Bottom Left", "Bottom Right",
                                           "Top", "Bottom", "Left", "Right"], "Custom",
                                          help="Rotation / scale origin; Custom uses Pivot X / Y")),
            ("Pivot X", slider(0.5)),
            ("Pivot Y", slider(0.5)),
        ])),
        ("Measure Engine", OD([
            ("Measure Pos", dropdown(MEASURE, "Nothing")),
            ("Pos Amount (A)", slider(0.1, 0, 10)),
            ("Pos Speed (B)", slider(1.0, 0, 10)),
            ("Measure Scale", dropdown(MEASURE, "Nothing")),
            ("Scale Amount (A)", slider(0.2, 0, 10)),
            ("Scale Speed (B)", slider(1.0, 0, 10)),
            ("Measure Rot", dropdown(MEASURE, "Nothing")),
            ("Rot Amount (A)", slider(0.1, 0, 10)),
            ("Rot Speed (B)", slider(1.0, 0, 10)),
        ])),
        ("Reactivity", OD([
            ("Reactivity Mode", dropdown(REACTIVITY, "Inherit",
                                         help="Per-layer override: Beat / Frequency / Amplitude / Nothing replaces every Measure mode of this layer")),
            ("Band Weight Low", slider(1.0, 0, 2, help="Frequency mode: weight of the low band")),
            ("Band Weight Mid", slider(0.5, 0, 2)),
            ("Band Weight High", slider(0.25, 0, 2)),
        ])),
    ])


def segment(index, enabled, c_from, c_to, mult):
    return OD([
        ("Enable Segment", check(enabled)),
        ("Shape Path", dropdown(SHAPES, "None")),
        ("Color From", color(c_from)),
        ("Color To", color(c_to)),
        ("Fixed Height", slider(0.0)),
        ("Bar Height Multiplier", slider(mult, 0, 10)),
        ("Mirror", check(False)),
        ("Flip Every Other", check(False)),
    ])


# ---------------------------------------------------------------- the schema
SCHEMA = OD()

SCHEMA["COMPOSITION"] = OD([
    ("General", general_category("Main")),
    ("Render Settings", OD([
        ("Output", OD([
            ("Resolution Width", number(1920, 16, 7680)),
            ("Resolution Height", number(1080, 16, 7680)),
            ("FPS Limit", dropdown(["30", "60", "120"], "60")),
        ])),
        ("Canvas", OD([
            ("Background Color", color("#070906FF")),
            ("Alpha / Transparency", slider(1.0)),
            ("Clear Canvas on Frame", check(True)),
        ])),
    ])),
])

SCHEMA["IMAGE"] = OD([
    ("General", general_category("Album Art")),
    ("Image Source", OD([
        ("Source", OD([
            ("Pick Image", image()),
            ("Keep Aspect Ratio", check(True)),
            ("Crop to Fit", check(False)),
            ("Shape", dropdown(SHAPES, "Circle")),
            ("Image Index", number(0, 0, 99)),
            ("Size", slider(0.42, help="Base size relative to the canvas")),
            ("Corner Radius", slider(0.0, help="Rounded crop for Rectangle / Square / None shapes")),
            ("Opacity", slider(1.0)),
            ("GIF Speed", slider(1.0, 0, 10, help="Playback speed of animated GIFs")),
        ])),
    ])),
    ("Color Adjustments", OD([
        ("Adjustments", OD([
            ("Tint", color("#FFFFFFFF")),
            ("Brightness", slider(0.5, help="0.5 = unchanged")),
            ("Contrast", slider(0.5, help="0.5 = unchanged")),
            ("Saturation", slider(0.5, help="0.5 = unchanged")),
            ("Hue Shift", slider(0.0, help="0..1 = 0..360 degrees")),
        ])),
    ])),
    ("Color Key (Chroma Key)", OD([
        ("Color Key", OD([
            ("Enable Color Key", check(False)),
            ("Key Color", color("#00FF00FF")),
            ("Threshold / Tolerance", slider(0.25)),
            ("Softness", slider(0.1)),
        ])),
    ])),
    ("Blur", OD([
        ("Blur", OD([
            ("Blur Radius", slider(0.0)),
            ("Blur Type", dropdown(["Gaussian", "Box", "Motion"], "Gaussian")),
        ])),
    ])),
    ("Generated Album Art", OD([
        ("Album Art", OD([
            ("Enable Auto-Fetch", check(True)),
            ("Fallback Image", image()),
        ])),
    ])),
    ("Masking", OD([
        ("Mask", OD([
            ("Mask Image", image()),
            ("Mask Mode", dropdown(["Alpha", "Luminance", "Inverted Alpha"], "Alpha")),
        ])),
    ])),
])
SCHEMA["IMAGE"]["General"]["Position"]["Position Y"] = slider(0.62)

SCHEMA["TEXT"] = OD([
    ("General", general_category("Title")),
    ("Content", OD([
        ("Text", OD([
            ("Text Input", text("%title%\n%artist%", multiline=True)),
            ("Use Metadata Variables", check(True, help="%title% %artist% %album% %time% %duration% %remaining% {ArtistOrTitle}")),
            ("Typewriter Speed", slider(0.5, help="Characters per second x 60")),
            ("Text Transform", dropdown(["None", "UPPERCASE", "lowercase", "Title Case"], "None")),
        ])),
        ("Lyrics", OD([
            ("Source", dropdown(["Text Input", "Synced Lyrics (.lrc)"], "Text Input",
                                help="Synced Lyrics reads the chosen .lrc, or <track name>.lrc next to the audio file")),
            ("Lyrics File", file("", ("*.lrc", "*.txt"), help="Optional; empty = look next to the track")),
            ("Context Lines", slider(0, 0, 3, step=1, help="Lines shown before and after the current one")),
            ("Context Opacity", slider(0.4)),
            ("Lyrics Offset (s)", slider(0.0, -5, 5, step=0.05)),
        ])),
    ])),
    ("Typography", OD([
        ("Font", OD([
            ("Font Family", font("Roboto")),
            ("Custom Font File", file("", ("*.ttf", "*.otf"), help="A .ttf / .otf file overrides Font Family")),
            ("Font Size", slider(0.035, help="Relative to canvas height")),
            ("Font Weight", dropdown(FONT_WEIGHTS, "700")),
            ("Style", dropdown(["Normal", "Italic", "Bold", "Bold-Italic"], "Normal")),
            ("Letter Spacing", slider(0.0)),
            ("Line Height", slider(0.5, help="0.5 = 1.0x, 1 = 2.0x")),
            ("Alignment", dropdown(["Left", "Center", "Right", "Justify"], "Center")),
            ("Auto Fit Width", slider(0.0, help="0 = off; otherwise shrink the text to fit this fraction of the canvas width")),
        ])),
    ])),
    ("Appearance", OD([
        ("Fill", OD([
            ("Text Color", color("#FFFFFFFF")),
            ("Fill Mode", dropdown(["Solid", "Horizontal Gradient", "Vertical Gradient"], "Solid")),
            ("Gradient Color", color("#C6FF00FF")),
        ])),
        ("Shadow", OD([
            ("Shadow Color", color("#00000099")),
            ("Shadow X", slider(0.52)),
            ("Shadow Y", slider(0.48)),
            ("Shadow Blur", slider(0.2)),
        ])),
        ("Outline", OD([
            ("Outline/Stroke Color", color("#000000FF")),
            ("Outline Width", slider(0.0)),
        ])),
        ("Glow", OD([
            ("Glow Color", color("#C6FF00AA")),
            ("Glow Size", slider(0.0)),
            ("Glow Pulse With", dropdown(["Nothing", "Beat", "Bass", "Mid", "Treble", "Volume", "Kick", "Snare", "HiHat"],
                                         "Nothing")),
        ])),
        ("Background", OD([
            ("Show Background", check(False)),
            ("Background Color", color("#00000080")),
            ("Padding", slider(0.35, 0, 2, help="In font heights")),
            ("Corner Radius", slider(0.3)),
        ])),
    ])),
    ("Text Animation", OD([
        ("Animation", OD([
            ("Animation Type", dropdown(["None", "Fade", "Slide Up", "Slide Down", "Zoom", "Typewriter"], "Fade")),
            ("Animation Speed", slider(0.5)),
            ("Stagger", slider(0.0, help="Per-character delay for Fade / Slide / Zoom")),
        ])),
        ("Marquee", OD([
            ("Enable Marquee", check(False, help="Scrolls the text inside a clipped window")),
            ("Marquee Speed", slider(0.1, -1, 1, help="Canvas widths per second; negative scrolls right")),
            ("Marquee Width", slider(0.6, 0.05, 1)),
            ("Marquee Gap", slider(0.15, 0, 1)),
        ])),
    ])),
])
SCHEMA["TEXT"]["General"]["Position"]["Position Y"] = slider(0.2)

SCHEMA["BARS"] = OD([
    ("General", general_category("Spectrum")),
    ("Bars Setup", OD([
        ("Bars", OD([
            ("Flip Input", check(False)),
            ("Height Scale", slider(1.0, 0, 10)),
            ("Min Height Scale", slider(0.01)),
            ("Max Height Scale", slider(0.22, 0, 10)),
            ("Shape Path", dropdown(SHAPES, "Circle")),
            ("Path Size", slider(0.24, help="Size of the shape path")),
            ("Color Blend Offset", slider(0.0)),
            ("Bar Count", slider(64, 4, 256, step=1)),
            ("Bar Spacing", slider(0.3)),
            ("Bar Width", slider(0.7)),
        ])),
    ])),
    ("Bar Style", OD([
        ("Color", OD([
            ("Color Mode", dropdown(["Segment Gradient", "Solid", "Rainbow", "By Amplitude"], "Segment Gradient",
                                    help="Solid = Color From only, Rainbow = hue across bars, By Amplitude = From (quiet) to To (loud)")),
            ("Rainbow Speed", slider(0.1, 0, 2, help="Hue rotation, turns per second")),
            ("Rainbow Saturation", slider(0.9)),
        ])),
        ("Shape", OD([
            ("Cap Style", dropdown(["Flat", "Round"], "Flat")),
            ("LED Cell Size", slider(0.0, 0, 0.05, step=0.001, help="0 = solid bars; above 0 splits bars into LED cells")),
            ("LED Gap", slider(0.004, 0, 0.03, step=0.001)),
        ])),
        ("Peaks", OD([
            ("Show Peaks", check(False)),
            ("Peak Hold (s)", slider(0.4, 0, 3)),
            ("Peak Fall Speed", slider(0.5, 0.01, 3, help="Full bar heights per second")),
            ("Peak Thickness", slider(0.006, 0.001, 0.05, step=0.001)),
            ("Peak Color", color("#FFFFFFFF")),
        ])),
        ("Extras", OD([
            ("Glow Amount", slider(0.0)),
            ("Glow Width", slider(2.5, 1, 6)),
            ("Reflection Opacity", slider(0.0, help="Faded mirror copy under the bars")),
            ("Outline Width", slider(0.0, 0, 0.02, step=0.0005)),
            ("Outline Color", color("#FFFFFFCC")),
            ("Baseline Width", slider(0.0, 0, 0.02, step=0.0005)),
            ("Baseline Color", color("#C6FF0099")),
        ])),
    ])),
    ("Segment 1", OD([("Segment 1", segment(1, True, "#C6FF00FF", "#5BFFB0FF", 1.0))])),
    ("Segment 2", OD([("Segment 2", segment(2, False, "#FFFFFF99", "#C6FF0066", 0.6))])),
    ("Segment 3", OD([("Segment 3", segment(3, False, "#5BFFB0FF", "#00E5FFFF", 0.3))])),
    ("Behavior", OD([
        ("Physics", OD([
            ("Softness", slider(0.55, help="How slowly bars fall back")),
            ("Reaction Delay", slider(0.15, help="How slowly bars rise")),
            ("Reaction Accumulated Delay", slider(0.0, help="Extra delay per bar index")),
        ])),
        ("Audio", OD([
            ("Audio Provider Index", number(0, 0, 8)),
            ("Audio Channel", dropdown(CHANNELS, "Mono", help="Left / Right / Side need Analyzer > Channel Mode = Stereo or Mid-Side")),
            ("Frequency Min (Hz)", number(30, 20, 20000)),
            ("Frequency Max (Hz)", number(8000, 40, 20000)),
            ("Measure What", dropdown(["Frequency", "Beat", "Amplitude", "Nothing"], "Frequency",
                                       help="Frequency = live spectrum, Beat = all bars jump on beats, Amplitude = loudness, Nothing = static")),
        ])),
    ])),
])

SCHEMA["BARS"]["General"]["Position"]["Position Y"] = slider(0.62)

SCHEMA["PARTICLES"] = OD([
    ("General", general_category("Particles")),
    ("Overall Settings", OD([
        ("Emitter", OD([
            ("Color", color("#FFFFFFFF")),
            ("Count Limit", slider(300, 0, 5000, step=1, warn_above=2000,
                                   warning="Above 2000 particles may lag on phones")),
            ("Mirror X", check(False)),
            ("Mirror Y", check(False)),
            ("Spawn Area", dropdown(["Rectangle", "Circle", "Line", "Point"], "Circle")),
            ("Force Field", dropdown(["None", "Gravity", "Wind", "Vortex", "Attractor", "Repeller"], "None")),
        ])),
        ("Perspective", OD([
            ("Perspective Depth", slider(0.0)),
            ("Perspective Fov", slider(0.5, help="0..1 = 10..170 degrees")),
        ])),
        ("Direction", OD([
            ("Vector Relative To Center", check(True)),
            ("Use Random Vector Instead", check(False)),
            ("Vector Angle", slider(0.25, help="0..1 = 0..360 degrees")),
            ("Vector Angle Z", slider(0.0, help="0..1 = 0..360 degrees")),
        ])),
    ])),
    ("Appearance", OD([
        ("Look", OD([
            ("Custom Image", image()),
            ("Particle Scale", slider(0.3)),
            ("Trail Length", slider(0.0)),
            ("Color From", color("#C6FF00FF")),
            ("Color To", color("#5BFFB000")),
            ("Fade In And Out Time", slider(0.2)),
            ("Velocity Angle", check(False)),
            ("Size Aspect Ratio", slider(0.5, help="0.5 = square")),
        ])),
        ("Size", OD([
            ("Start Size", slider(0.5)),
            ("Fade In Size", slider(0.1)),
            ("End Size", slider(0.1)),
            ("End Size Random", slider(0.2)),
            ("End Size ZFactor", slider(0.5)),
            ("Near Camera Fade Out Distance", slider(0.0)),
        ])),
    ])),
    ("Behaviour", OD([
        ("Motion", OD([
            ("Spawn Time", slider(0.02, help="Seconds between spawns")),
            ("Measure Overall Speed", dropdown(MEASURE, "Beat")),
            ("A X Amount", slider(0.5, 0, 10)),
            ("B Y Amount", slider(1.0, 0, 10)),
            ("Speed", slider(0.2)),
            ("Speed Random", slider(0.4)),
            ("Lifetime", slider(0.3, help="0..1 = 0..10 seconds")),
            ("Gravity X", slider(0.5, help="0.5 = none")),
            ("Gravity Y", slider(0.5, help="0.5 = none")),
        ])),
    ])),
    ("Wave Behaviour", OD([
        ("Side Wave", OD([
            ("Side Sine Wave Freq", slider(0.2)),
            ("Side Sine Wave Freq Random", slider(0.0)),
            ("Side Sine Wave Mag", slider(0.0)),
            ("Side Sine Wave Mag Random", slider(0.0)),
        ])),
    ])),
    ("Physics & Bursts", OD([
        ("Burst", OD([
            ("Burst On", dropdown(["Nothing", "Beat", "Downbeat", "Kick", "Snare", "HiHat"], "Nothing",
                                  help="Emit a burst of particles on every detected hit")),
            ("Burst Count", slider(40, 1, 500, step=1)),
            ("Burst Speed Boost", slider(1.5, 0, 5, help="Burst particles start this many times faster")),
        ])),
        ("Forces", OD([
            ("Drag", slider(0.0, 0, 5, help="Air resistance, 1/s")),
            ("Wind X", slider(0.0, -1, 1)),
            ("Wind Y", slider(0.0, -1, 1)),
            ("Wind Gust With", dropdown(["Nothing", "Beat", "Bass", "Mid", "Treble", "Volume", "Kick", "Snare", "HiHat"],
                                        "Nothing", help="Audio level multiplies the wind")),
            ("Turbulence", slider(0.0, 0, 2)),
            ("Attractor X", slider(0.5)),
            ("Attractor Y", slider(0.5)),
            ("Attractor Strength", slider(0.0, -2, 2, help="Positive pulls toward the point, negative pushes away")),
        ])),
        ("Rotation", OD([
            ("Rotation Speed (deg/s)", slider(0, -720, 720, step=1)),
            ("Rotation Random", slider(0.0, help="Per-particle spread of the rotation speed")),
        ])),
    ])),
])

SCHEMA["PARTICLES"]["General"]["Position"]["Position Y"] = slider(0.62)

SHAPE_SOURCES = ["Nothing", "Beat", "Bass", "Mid", "Treble", "Volume", "Kick", "Snare", "HiHat"]
SHAPE_TYPES = ["Rectangle", "Ellipse", "Line", "Polygon", "Star", "Path"]

SCHEMA["WAVEFORM"] = OD([
    ("General", general_category("Waveform")),
    ("Waveform", OD([
        ("Style", OD([
            ("Style", dropdown(["Line", "Filled", "Dotted", "Bars"], "Line")),
            ("Orientation", dropdown(["Horizontal", "Vertical"], "Horizontal")),
            ("Mirror", check(False, help="Symmetric around the center line")),
            ("Channel", dropdown(["Mono", "Left", "Right", "Stereo", "Side"], "Mono",
                                 help="Stereo draws Left and Right in two lanes (needs Channel Mode = Stereo or Mid-Side)")),
        ])),
        ("Parameters", OD([
            ("Thickness (dp)", slider(3, 1, 20, step=0.5)),
            ("Amplitude", slider(1.0, 0.1, 5, step=0.01)),
            ("Smoothing", slider(0.3, 0, 0.99, step=0.01, help="Temporal smoothing between frames")),
            ("Scroll Speed", slider(0.0, 0, 5, step=0.01, help="0 = live oscilloscope; above 0 = scrolling envelope history")),
            ("Window (samples)", dropdown(["256", "512", "1024", "2048", "4096", "8192"], "1024",
                                          help="Oscilloscope window at the 8 kHz analysis rate")),
            ("Points", slider(256, 32, 1024, step=1)),
        ])),
        ("Size", OD([
            ("Width", slider(0.9, 0.05, 1)),
            ("Height", slider(0.3, 0.02, 1)),
        ])),
        ("Color", OD([
            ("Color Mode", dropdown(["Solid", "Gradient", "By Amplitude"], "Gradient")),
            ("Color From", color("#C6FF00FF")),
            ("Color To", color("#00E5FFFF")),
        ])),
    ])),
])
SCHEMA["WAVEFORM"]["General"]["Layer"]["Visible"] = check(False)

SCHEMA["SHAPE"] = OD([
    ("General", general_category("Shape")),
    ("Shape", OD([
        ("Geometry", OD([
            ("Shape Type", dropdown(SHAPE_TYPES, "Ellipse")),
            ("Width", slider(0.5, 0.01, 2)),
            ("Height", slider(0.5, 0.01, 2)),
            ("Corner Radius", slider(0.0, 0, 1, help="Rectangle only, fraction of the short side")),
            ("Sides", slider(6, 3, 32, step=1)),
            ("Star Points", slider(5, 3, 32, step=1)),
            ("Inner Radius", slider(0.45, 0.05, 0.95)),
            ("Line Angle", slider(0, -180, 180, step=1)),
            ("SVG Path", text("M 0 0 L 100 0 L 50 86 Z", help="SVG path data: M L H V C Q Z (absolute or relative)")),
        ])),
        ("Fill", OD([
            ("Fill Mode", dropdown(["Solid", "Linear Gradient", "Radial Gradient", "None"], "Solid")),
            ("Fill Color", color("#C6FF0066")),
            ("Fill Color 2", color("#00E5FF22")),
            ("Gradient Angle", slider(90, 0, 360, step=1)),
        ])),
        ("Stroke", OD([
            ("Stroke Color", color("#C6FF00FF")),
            ("Stroke Width", slider(2, 0, 40, step=0.5)),
            ("Dash Length", slider(0, 0, 60, step=0.5, help="0 = solid stroke")),
            ("Dash Gap", slider(6, 0, 60, step=0.5)),
        ])),
        ("Animation", OD([
            ("Spin (deg/s)", slider(0, -360, 360, step=1)),
            ("Rotate On Beat (deg)", slider(0, -180, 180, step=1)),
            ("Scale With", dropdown(SHAPE_SOURCES, "Bass")),
            ("Scale Amount", slider(0.3, 0, 3)),
            ("Morph To", dropdown(["None"] + SHAPE_TYPES[:-1], "None")),
            ("Morph Amount", slider(0.0)),
            ("Morph With", dropdown(SHAPE_SOURCES, "Nothing", help="Audio pushes the morph further")),
        ])),
    ])),
])
SCHEMA["SHAPE"]["General"]["Layer"]["Visible"] = check(False)

SCHEMA["AUDIOPROVIDER"] = OD([
    ("General", OD([
        ("Routing", OD([
            ("Visible", check(True)),
            ("Audio Source", dropdown(["File"], "File",
                                      help="In-app playback only: the file you play is analysed. No microphone, no AudioRecord.")),
            ("Volume", slider(0.8)),
            ("Balance", slider(0.5, help="0 = left, 0.5 = center, 1 = right")),
            ("Preamp", slider(1.0, 0, 10, help="Gain applied to the analysis signal")),
            ("Measure Source", dropdown(["Bass", "Mid", "Treble", "Volume", "Peak"], "Bass",
                                        help="Used for beats while the track is still being analysed")),
        ])),
    ])),
    ("Analyzer", OD([
        ("FFT", OD([
            ("FFT Size", dropdown(FFT_SIZES, "1024", help="Bigger = finer bass detail, smaller = faster reaction. Auto picks 2048. Re-analyses the track.")),
            ("Window", dropdown(WINDOWS, "Hann", help="Window function applied before the FFT. Re-analyses the track.")),
            ("Overlap", dropdown(["0%", "25%", "50%", "75%"], "50%", help="Hop = FFT x (1 - overlap), kept between 20 and 120 frames per second")),
            ("Analysis Bands", dropdown(["32", "64", "96", "128"], "64", help="Log bands stored per frame (30 Hz - 16 kHz)")),
            ("Re-analyse Track", action("Re-analyse Track")),
        ])),
        ("Channels", OD([
            ("Channel Mode", dropdown(["Mono", "Stereo", "Mid-Side"], "Mono",
                                      help="Mono = (L+R)/2. Stereo / Mid-Side also analyse Left, Right and Side so every layer can pick its channel.")),
        ])),
        ("Smoothing", OD([
            ("Smoothing", slider(0.0, 0, 0.95, help="Exponential smoothing alpha (0 = raw)")),
            ("Attack (ms)", slider(0, 0, 500, step=1, help="Rise time constant")),
            ("Release (ms)", slider(0, 0, 2000, step=1, help="Fall time constant")),
        ])),
        ("Scale", OD([
            ("Frequency Mapping", dropdown(["Logarithmic", "Linear"], "Logarithmic",
                                           help="Default spacing of bars across frequencies")),
            ("Interpolation", dropdown(["Linear", "Cubic", "Nearest"], "Linear")),
            ("Amplitude Scale", dropdown(["dB", "Linear"], "dB")),
            ("dB Floor", slider(-66, -120, -20, step=1, help="Level shown as 0")),
            ("dB Ceiling", slider(-6, -40, 0, step=1, help="Level shown as full height")),
            ("Spectral Tilt (dB/oct)", slider(2.65, 0, 6, step=0.05, help="Lifts treble (music falls ~3 dB/oct)")),
            ("Normalization", dropdown(["None", "Peak", "RMS"], "None",
                                       help="Peak = loudest part of the track reaches the ceiling; RMS = average loudness")),
        ])),
        ("Debug", OD([
            ("Show Analyzer Overlay", check(False, help="Draws bands, beats, BPM and bar counter over the preview")),
        ])),
    ])),
    ("Beat Detection", OD([
        ("Detector", OD([
            ("Algorithm", dropdown(["Energy", "Spectral Flux", "Comb Filter"], "Spectral Flux")),
            ("Beat Trigger", dropdown(["All Beats", "Downbeats Only", "Kick", "Snare", "Hi-Hat"], "All Beats",
                                      help="Which onsets fire Beat measure modes")),
            ("Sensitivity", slider(0.5)),
            ("Cooldown (ms)", slider(180, 50, 500, step=1, help="Minimum time between beats")),
            ("Threshold (x)", slider(1.5, 1, 5, step=0.05, help="Onset must exceed the local average by this factor")),
            ("History (s)", slider(1.0, 0.5, 5, step=0.05, help="Window of the local average")),
        ])),
        ("Pulse", OD([
            ("Decay Curve", dropdown(["Exponential", "Linear", "Ease-Out", "Ease-In", "Step (Hold)"], "Exponential")),
            ("Pulse Duration (ms)", slider(1000, 50, 3000, step=1, help="How long a beat pulse lasts (scaled by each layer's Speed B)")),
        ])),
    ])),
    ("Tempo & Bars", OD([
        ("Tempo", OD([
            ("BPM Mode", dropdown(["Auto", "Manual"], "Auto")),
            ("Manual BPM", slider(120, 40, 240, step=0.1)),
            ("BPM Range Min", slider(60, 40, 200, step=1)),
            ("BPM Range Max", slider(200, 80, 240, step=1)),
            ("Tempo Multiplier", dropdown(["x1", "x0.5 (Half)", "x2 (Double)"], "x1")),
            ("Tap Tempo", action("Tap Tempo")),
        ])),
        ("Bars", OD([
            ("Time Signature", dropdown(["4/4", "3/4", "6/8", "2/4", "5/4", "7/8", "12/8"], "4/4")),
            ("Downbeat Offset", number(0, 0, 11, help="Shift which beat counts as 1")),
        ])),
    ])),
    ("Frequency Bands", OD([
        ("3-Band Split", OD([
            ("Low-Mid Cut (Hz)", slider(250, 40, 1000, step=1)),
            ("Mid-High Cut (Hz)", slider(4000, 1000, 12000, step=10)),
            ("Low Gain", slider(1.0, 0, 4)),
            ("Mid Gain", slider(1.0, 0, 4)),
            ("High Gain", slider(1.0, 0, 4)),
            ("Band Attack (ms)", slider(0, 0, 500, step=1)),
            ("Band Release (ms)", slider(0, 0, 2000, step=1)),
            ("Band AGC", check(False, help="Automatic gain: each band adapts to its own recent maximum")),
        ])),
        ("Custom Bands", OD([
            ("Crossover Slope", dropdown(["6 dB/oct", "12 dB/oct", "24 dB/oct", "48 dB/oct"], "24 dB/oct")),
            ("Add Band", action("Add Band")),
            ("Bands", dict(type="bands", default=[])),
        ])),
    ])),
    ("Sync & Offset", OD([
        ("Timing", OD([
            ("Audio Offset", slider(0.0, -1000, 1000, step=1, help="Milliseconds")),
            ("BPM Sync", number(0, 0, 400, help="0 = off; otherwise adds a beat grid")),
            ("Beat Offset", slider(0.0)),
        ])),
    ])),
])

SCHEMA["BLUREFFECT"] = OD([
    ("General", general_category("Glow")),
    ("Appearance", OD([
        ("Content", OD([
            ("Blend Mode Content", dropdown(BLEND, "Screen")),
            ("Color", color("#FFFFFFFF")),
            ("Source Composition Index", number(0, 0, 16)),
            ("Mask Image", image()),
            ("Show Unblurred Content", check(True)),
            ("Show Unblurred Content Under", check(False)),
        ])),
    ])),
    ("Blur Settings", OD([
        ("Radius", OD([
            ("Blur Radius", slider(0.3)),
            ("Blur Multiplier", slider(1.0, 0, 10)),
        ])),
        ("Layer 1", OD([
            ("1Layer Scale X", slider(1.0, 0, 10)),
            ("1Layer Scale Y", slider(1.0, 0, 10)),
            ("1Layer Color", color("#FFFFFF99")),
        ])),
        ("Layer 2", OD([
            ("2Layer Scale X", slider(2.0, 0, 10)),
            ("2Layer Scale Y", slider(2.0, 0, 10)),
            ("2Layer Color", color("#C6FF0066")),
        ])),
        ("Layer 3", OD([
            ("3Layer Scale X", slider(4.0, 0, 10)),
            ("3Layer Scale Y", slider(4.0, 0, 10)),
            ("3Layer Color", color("#5BFFB044")),
        ])),
    ])),
])
SCHEMA["BLUREFFECT"]["General"]["Layer"]["Visible"] = check(False)

SCHEMA["RGBSPLITEFFECT"] = OD([
    ("General", general_category("Glitch")),
    ("Effect Settings", OD([
        ("Split", OD([
            ("Split Amount", slider(0.2)),
            ("Angle / Direction", slider(0.0, help="0..1 = 0..360 degrees")),
            ("Red Channel Offset", slider(1.0)),
            ("Green Channel Offset", slider(0.5)),
            ("Blue Channel Offset", slider(0.0)),
        ])),
        ("Glitch", OD([
            ("Enable Glitch on Beat", check(True)),
            ("Glitch Duration", slider(0.2, help="0..1 = 0..1 second")),
        ])),
    ])),
])
SCHEMA["RGBSPLITEFFECT"]["General"]["Layer"]["Visible"] = check(False)

SCHEMA["MOTIONBLUREFFECT"] = OD([
    ("General", general_category("Motion Blur")),
    ("Appearance", OD([
        ("Content", OD([
            ("Blend Mode Content", dropdown(BLEND, "Alpha")),
            ("Color", color("#FFFFFFFF")),
            ("Target Image", image()),
            ("Show Unblurred Content", check(True)),
            ("Show Unblurred Content Under", check(False)),
        ])),
    ])),
    ("Motion Blur", OD([
        ("Motion", OD([
            ("Blur Amount Multiplier", slider(1.0, 0, 10)),
            ("Motion Source", dropdown(["EffectTransform", "TargetImage", "AudioVolume"], "AudioVolume")),
            ("Relative Motion Mode", check(True)),
        ])),
    ])),
])
SCHEMA["MOTIONBLUREFFECT"]["General"]["Layer"]["Visible"] = check(False)

SCHEMA["MIRROREREFFECT"] = OD([
    ("General", general_category("Mirror")),
    ("Appearance", OD([
        ("Mirror", OD([
            ("Target Image", image()),
            ("Mirror Mode", dropdown(["Horizontal", "Vertical", "Both", "Kaleidoscope"], "Horizontal")),
            ("Flip Mirror", check(False)),
            ("Color", color("#FFFFFFFF")),
            ("Offset", slider(0.5, help="Mirror axis position")),
        ])),
    ])),
])
SCHEMA["MIRROREREFFECT"]["General"]["Layer"]["Visible"] = check(False)

DEFAULT_FRAGMENT = """// Kivy effect function. Inputs: color, texture, tex_coords, coords.
// Built-ins: time, resolution, beat, rms, bass + your variables below.
vec4 effect(vec4 color, sampler2D texture, vec2 tex_coords, vec2 coords)
{
    vec2 uv = tex_coords;
    float wave = sin(uv.y * 30.0 + time * 4.0) * 0.004 * (1.0 + beat * 4.0);
    vec4 c = texture2D(texture, vec2(uv.x + wave, uv.y));
    return vec4(c.rgb * (0.9 + mySpeed * 0.2), c.a);
}
"""

SCHEMA["CUSTOMSHADER"] = OD([
    ("General", general_category("Shader")),
    ("Appearance", OD([
        ("Target", OD([
            ("Target Image", image()),
        ])),
    ])),
    ("Shader Code", OD([
        ("GLSL", OD([
            ("Shader Vertex", code("", help="Optional. Leave empty to use the default vertex shader.")),
            ("Effect Preset", dropdown(SHADER_PRESETS, "Custom",
                                        help="Loads a ready-made shader (code + variables) into this layer")),
            ("Intensity", slider(0.5, help="Strength of the preset effect (uniform 'intensity')")),
            ("Fragment Shader", code(DEFAULT_FRAGMENT)),
            ("Compile / Apply", action("Compile / Apply")),
        ])),
    ])),
    ("Variables", OD([
        ("Declare", OD([
            ("New Variable Name", text("myVar")),
            ("New Variable", dropdown(VARIABLE_TYPES, "Float")),
            ("Add Variable", action("Add Variable")),
        ])),
        ("Variable Mappings", OD([
            ("Mappings", dict(type="variables", default=[
                {"name": "mySpeed", "type": "Float", "value": 0.5}])),
        ])),
    ])),
])
SCHEMA["CUSTOMSHADER"]["General"]["Layer"]["Visible"] = check(False)


# ---------------------------------------------------------------- dedicated effects (spec 2.8)
from .fx_library import LIBRARY as FX_LIBRARY, ORDER as FX_ORDER, REACT_SOURCES, SCOPES  # noqa: E402


def effect_stack_category():
    """Shared by every effect module: mix, scope and audio reactivity."""
    return OD([
        ("Mix", OD([
            ("Effect Mix", slider(1.0, help="0 = effect off, 1 = full effect (dry/wet)")),
            ("Scope", dropdown(SCOPES, "All Layers Below",
                               help="All Layers Below = global effect on everything under it; "
                                    "Layer Directly Below = applied to that one layer before compositing")),
        ])),
        ("Audio Reactivity", OD([
            ("React To", dropdown(REACT_SOURCES, "Nothing", help="Boosts the effect's main parameter")),
            ("React Amount", slider(1.0, 0, 4)),
        ])),
    ])


def _fx_field(param):
    label, _uni, kind, default = param[:4]
    if kind == "f":
        return slider(default, param[4], param[5], step=param[6])
    if kind == "i":
        return slider(default, param[4], param[5], step=1)
    if kind == "b":
        return check(default)
    if kind == "c":
        return color(default)
    return dropdown(param[6], default)


for _name in FX_ORDER:
    _fx = FX_LIBRARY[_name]
    SCHEMA[_name] = OD([
        ("General", general_category(_fx["title"])),
        ("Effect Settings", OD([("Parameters", OD((p[0], _fx_field(p)) for p in _fx["params"]))])),
        ("Effect Stack", effect_stack_category()),
    ])
    SCHEMA[_name]["General"]["Layer"]["Visible"] = check(False)
    MODULES.append(_name)
    EFFECT_MODULES.append(_name)

for _name in ("BLUREFFECT", "RGBSPLITEFFECT", "MOTIONBLUREFFECT", "MIRROREREFFECT", "CUSTOMSHADER"):
    SCHEMA[_name]["Effect Stack"] = effect_stack_category()


# ---------------------------------------------------------------- module info
MODULE_INFO = {
    "COMPOSITION": "Master canvas. Its General transform moves every layer together. Render Settings set the internal render resolution (and aspect ratio), FPS limit, background color and whether the canvas is cleared every frame (turn off for trails).",
    "IMAGE": "Draws a picture. Keep Aspect Ratio prevents stretching, Crop to Fit fills the box. Shape builds a mathematical mask: pixels outside the shape become transparent. Color Key removes one color. Generated Album Art loads the embedded cover of the current track.",
    "TEXT": "Draws text. With metadata variables on, %title% %artist% %album% %time% %duration% %remaining% and {ArtistOrTitle} are replaced with live data. Typewriter reveals characters one by one.",
    "BARS": "Splits the spectrum into Bar Count chunks. Height Scale amplifies, Softness and Reaction Delay act like a spring (fast rise, slow fall). Shape Path lays bars along a circle, polygon, line or waveform. Segments stack up to three styles.",
    "PARTICLES": "Particle system. Count Limit caps particles (over 2000 can lag). Spawn Area is the birth location, Gravity pulls, Lifetime sets seconds before respawn. Measure Overall Speed accelerates particles on the beat.",
    "AUDIOPROVIDER": "Routes audio: a music file, the microphone or system audio (other apps). Volume/Balance control playback, Preamp boosts analysis, Audio Offset delays visuals so they sync with the beat, BPM Sync adds a beat grid.",
    "BLUREFFECT": "Gaussian glow. Three passes blur at different scales and tint them to build an atmospheric glow. Show Unblurred Content keeps the sharp image on top.",
    "RGBSPLITEFFECT": "Separates the red, green and blue channels for a glitch look. Can spike on every beat.",
    "MOTIONBLUREFFECT": "Stretches the image in the direction of movement. Faster movement means more blur. Relative mode uses per-frame movement.",
    "MIRROREREFFECT": "Horizontal/Vertical takes half of the image and flips it onto the other half. Flip Mirror swaps the copied side. Kaleidoscope creates radial symmetry.",
    **{name: FX_LIBRARY[name]["info"] + " Effect Stack: dry/wet mix, global or single-layer scope, audio reactivity."
       for name in FX_ORDER},
    "WAVEFORM": "Draws the real decoded waveform of the playing track: line, filled area, dots or bars, horizontal or vertical, mirrored, per channel, as a live oscilloscope or a scrolling envelope.",
    "SHAPE": "Vector shape: rectangle (rounded), ellipse, line, polygon, star or SVG path. Solid / gradient fill, dashed stroke, spin, rotate on beat, scale with a band, morph into another shape.",
    "CUSTOMSHADER": "Your own GLSL fragment shader, compiled on the GPU and applied to the scene. Declared variables get sliders/color pickers and are sent to the shader as uniforms every frame.",
}


# ---------------------------------------------------------------- helpers
def iter_fields(module):
    """Yield (path, spec) for every property of a module, in display order."""
    for category, subs in SCHEMA[module].items():
        for sub, props in subs.items():
            for name, spec in props.items():
                yield "/".join((category, sub, name)), spec


def defaults(module):
    from copy import deepcopy
    return {path: deepcopy(spec["default"]) for path, spec in iter_fields(module)}


def field_spec(module, path):
    category, sub, name = path.split("/", 2)
    return SCHEMA[module][category][sub][name]
