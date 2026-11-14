"""Dedicated post-processing effect modules (Effects Engine, spec 2.8).

Each entry is pure data: the property list (shown in the editor, saved in
projects / templates) and the GLSL ``effect_main`` body. The schema builds the
module from ``params``; the render engine (engine/effects.py) compiles the
shader once and pushes every parameter as a uniform each frame.

Param tuple: (label, uniform, kind, default, lo, hi, extra)
    kind  "f" slider, "i" integer slider, "b" switch, "c" color, "d" dropdown
    extra step for sliders, option list for dropdowns
The first param flagged as ``main`` is boosted by Audio Reactivity
(value * (1 + level * amount)).

GLSL ES 1.00 only (constant loop bounds, break allowed) so every phone GPU
compiles it. Inputs available: texture0 (everything below), uv (after the
effect's own General transform), base (untransformed pixel), resolution,
time, beat, rms, bass, mid, treble, and the blend() helper.
"""

REACT_SOURCES = ["Nothing", "Beat", "Bass", "Mid", "Treble", "Volume", "Kick", "Snare", "HiHat", "Downbeat"]
SCOPES = ["All Layers Below", "Layer Directly Below"]

ZOOMBLUR = dict(
    title="Zoom Blur", icon="next", main="strength",
    info="Radial streaks out of a center point. Strength 0-100, Center X/Y, 4-32 samples.",
    params=[("Strength", "strength", "f", 30, 0, 100, 1), ("Center X", "center_x", "f", 0.5, 0, 1, 0.001),
            ("Center Y", "center_y", "f", 0.5, 0, 1, 0.001), ("Samples", "samples", "i", 20, 4, 32, 1)],
    glsl="""
uniform float strength; uniform float center_x; uniform float center_y; uniform float samples;
vec4 effect_main(vec2 uv, vec4 base) {
  vec2 c = vec2(center_x, center_y);
  float amount = strength * 0.006;
  vec4 acc = vec4(0.0); float total = 0.0;
  for (int i = 0; i < 32; i++) {
    if (float(i) >= samples) break;
    float t = float(i) / max(samples - 1.0, 1.0);
    float w = 1.0 - t * 0.6;
    acc += texture2D(texture0, c + (uv - c) * (1.0 - amount * t)) * w;
    total += w;
  }
  return blend(blend_mode, base, acc / max(total, 0.0001));
}""")

TWIRL = dict(
    title="Twirl", icon="reset", main="angle",
    info="Swirls the image around a center. Angle -720..+720 degrees, radius 0-100% of the canvas.",
    params=[("Angle", "angle", "f", 180, -720, 720, 1), ("Radius", "radius", "f", 50, 0, 100, 1),
            ("Center X", "center_x", "f", 0.5, 0, 1, 0.001), ("Center Y", "center_y", "f", 0.5, 0, 1, 0.001),
            ("Spin Speed", "spin", "f", 0, -5, 5, 0.01)],
    glsl="""
uniform float angle; uniform float radius; uniform float center_x; uniform float center_y; uniform float spin;
vec4 effect_main(vec2 uv, vec4 base) {
  float aspect = resolution.x / max(resolution.y, 1.0);
  vec2 c = vec2(center_x, center_y);
  vec2 p = (uv - c) * vec2(aspect, 1.0);
  float r = length(p);
  float rad = max(radius / 100.0, 0.001);
  if (r < rad) {
    float pct = (rad - r) / rad;
    float a = pct * pct * radians(angle) + time * spin;
    float s = sin(a), k = cos(a);
    p = vec2(k * p.x - s * p.y, s * p.x + k * p.y);
  }
  return blend(blend_mode, base, texture2D(texture0, p / vec2(aspect, 1.0) + c));
}""")

KALEIDOSCOPE = dict(
    title="Kaleidoscope", icon="copy", main="zoom",
    info="Radial mirror segments (2-32) with rotation, optional mirroring and an off-center origin.",
    params=[("Segments", "segments", "i", 8, 2, 32, 1), ("Rotation", "rotation", "f", 0, 0, 360, 1),
            ("Mirror Segments", "mirror", "b", True), ("Center X", "center_x", "f", 0.5, 0, 1, 0.001),
            ("Center Y", "center_y", "f", 0.5, 0, 1, 0.001), ("Spin Speed", "spin", "f", 0.2, -5, 5, 0.01),
            ("Zoom", "zoom", "f", 1.0, 0.2, 4, 0.01)],
    glsl="""
uniform float segments; uniform float rotation; uniform float mirror; uniform float center_x;
uniform float center_y; uniform float spin; uniform float zoom;
vec4 effect_main(vec2 uv, vec4 base) {
  float aspect = resolution.x / max(resolution.y, 1.0);
  vec2 c = vec2(center_x, center_y);
  vec2 p = (uv - c) * vec2(aspect, 1.0);
  float r = length(p) / max(zoom, 0.01);
  float seg = 6.2831853 / max(2.0, floor(segments));
  float a = mod(atan(p.y, p.x) + radians(rotation) + time * spin, seg);
  if (mirror > 0.5) a = abs(a - seg * 0.5);
  vec2 q = vec2(cos(a), sin(a)) * r / vec2(aspect, 1.0) + c;
  return blend(blend_mode, base, texture2D(texture0, clamp(q, 0.0, 1.0)));
}""")

REFLECTION = dict(
    title="Reflection", icon="copy", main="opacity",
    info="Glossy floor mirror below a horizon. Height 10-100%, opacity, gap, fade with curve, ripple.",
    params=[("Horizon", "horizon", "f", 0.35, 0, 1, 0.001), ("Height", "height", "f", 60, 10, 100, 1),
            ("Opacity", "opacity", "f", 50, 0, 100, 1), ("Gap", "gap", "f", 0, 0, 50, 1),
            ("Fade", "fade", "b", True), ("Fade Curve", "curve", "f", 1.5, 0.2, 5, 0.01),
            ("Ripple", "ripple", "f", 0.3, 0, 2, 0.01), ("Tint", "tint", "c", "#FFFFFFFF")],
    glsl="""
uniform float horizon; uniform float height; uniform float opacity; uniform float gap; uniform float fade;
uniform float curve; uniform float ripple; uniform vec4 tint;
vec4 effect_main(vec2 uv, vec4 base) {
  float g = gap / max(resolution.y, 1.0) * 2.0;
  float h = horizon - g;
  float len = max(height / 100.0 * horizon, 0.001);
  if (uv.y < h && uv.y > h - len) {
    float d = (h - uv.y) / len;
    vec2 q = vec2(uv.x + sin(uv.y * 90.0 + time * 4.0) * 0.004 * ripple * (1.0 + rms * 2.0),
                  horizon + (h - uv.y));
    vec4 r = texture2D(texture0, clamp(q, 0.0, 1.0)) * tint;
    float f = opacity / 100.0;
    if (fade > 0.5) f *= pow(max(1.0 - d, 0.0), curve);
    return vec4(mix(base.rgb, r.rgb, clamp(f * r.a, 0.0, 1.0)), max(base.a, r.a * f));
  }
  return base;
}""")

BOX3D = dict(
    title="3D Box", icon="layers", main="depth",
    info="Maps the scene on the faces of a cube. Depth, rotate X/Y/Z (-180..180), perspective, auto spin.",
    params=[("Depth", "depth", "f", 50, 0, 100, 1), ("Rotate X", "rot_x", "f", 15, -180, 180, 1),
            ("Rotate Y", "rot_y", "f", 30, -180, 180, 1), ("Rotate Z", "rot_z", "f", 0, -180, 180, 1),
            ("Perspective", "persp", "f", 800, 100, 2000, 1), ("Spin Speed", "spin", "f", 0.3, -5, 5, 0.01),
            ("Background", "background", "f", 0.15, 0, 1, 0.001), ("Edge Color", "edge", "c", "#C6FF00CC")],
    glsl="""
uniform float depth; uniform float rot_x; uniform float rot_y; uniform float rot_z; uniform float persp;
uniform float spin; uniform float background; uniform vec4 edge;
vec3 rx(vec3 v, float a) { float s = sin(a), c = cos(a); return vec3(v.x, c * v.y - s * v.z, s * v.y + c * v.z); }
vec3 ry(vec3 v, float a) { float s = sin(a), c = cos(a); return vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z); }
vec3 rz(vec3 v, float a) { float s = sin(a), c = cos(a); return vec3(c * v.x - s * v.y, s * v.x + c * v.y, v.z); }
vec4 effect_main(vec2 uv, vec4 base) {
  float aspect = resolution.x / max(resolution.y, 1.0);
  vec2 p = (uv - 0.5) * 2.0 * vec2(aspect, 1.0);
  float ay = radians(rot_y) + time * spin;
  float ax = radians(rot_x);
  float az = radians(rot_z);
  float focal = persp / 360.0;
  vec3 ro = vec3(0.0, 0.0, 1.5 + focal * (1.6 - depth / 100.0 * 1.2));
  vec3 rd = normalize(vec3(p, -focal));
  ro = rz(rx(ry(ro, ay), ax), az);
  rd = rz(rx(ry(rd, ay), ax), az);
  vec3 m = 1.0 / rd; vec3 n = m * ro; vec3 k = abs(m);
  vec3 t1 = -n - k; vec3 t2 = -n + k;
  float tn = max(max(t1.x, t1.y), t1.z);
  float tf = min(min(t2.x, t2.y), t2.z);
  if (tn > tf || tf < 0.0) return vec4(base.rgb * background, base.a * background);
  vec3 pos = ro + rd * tn; vec3 a = abs(pos);
  vec2 f; float shade;
  if (a.x > a.y && a.x > a.z) { f = pos.zy; shade = 0.72; }
  else if (a.y > a.z) { f = pos.xz; shade = 0.86; }
  else { f = pos.xy; shade = 1.0; }
  vec4 c = texture2D(texture0, f * 0.5 + 0.5);
  float e = smoothstep(0.96, 1.0, max(abs(f.x), abs(f.y)));
  return vec4(mix(c.rgb * shade, edge.rgb, e * edge.a), 1.0);
}""")

COLORCORRECTION = dict(
    title="Color Correction", icon="sun", main="saturation",
    info="Brightness / contrast / saturation (-100..100), hue 0-360, gamma 0.1-5, vignette, temperature and tint.",
    params=[("Brightness", "brightness", "f", 0, -100, 100, 1), ("Contrast", "contrast", "f", 0, -100, 100, 1),
            ("Saturation", "saturation", "f", 0, -100, 100, 1), ("Hue Shift", "hue", "f", 0, 0, 360, 1),
            ("Gamma", "gamma", "f", 1.0, 0.1, 5, 0.01), ("Vignette", "vignette", "f", 0, 0, 100, 1),
            ("Vignette Softness", "vsoft", "f", 50, 0, 100, 1), ("Temperature", "temperature", "f", 0, -100, 100, 1),
            ("Tint (Green-Magenta)", "tint_gm", "f", 0, -100, 100, 1)],
    glsl="""
uniform float brightness; uniform float contrast; uniform float saturation; uniform float hue; uniform float gamma;
uniform float vignette; uniform float vsoft; uniform float temperature; uniform float tint_gm;
vec3 hue_rot(vec3 c, float a) {
  const vec3 k = vec3(0.57735); float ca = cos(a);
  return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}
vec4 effect_main(vec2 uv, vec4 base) {
  vec4 s = texture2D(texture0, uv);
  vec3 c = s.rgb + brightness / 100.0;
  c = (c - 0.5) * (1.0 + contrast / 100.0) + 0.5;
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(l), c, 1.0 + saturation / 100.0);
  if (hue > 0.5) c = hue_rot(c, radians(hue));
  c += vec3(temperature, 0.0, -temperature) / 400.0;
  c += vec3(tint_gm, -tint_gm, tint_gm) / 500.0;
  c = pow(max(c, 0.0), vec3(1.0 / max(gamma, 0.05)));
  if (vignette > 0.0) {
    vec2 p = (uv - 0.5) * vec2(resolution.x / max(resolution.y, 1.0), 1.0);
    float d = length(p);
    float soft = 0.05 + vsoft / 100.0 * 0.6;
    float v = smoothstep(0.75, 0.75 - soft, d);
    c *= mix(1.0, v, vignette / 100.0);
  }
  return vec4(clamp(c, 0.0, 1.0), s.a);
}""")

BLOOM = dict(
    title="Glow / Bloom", icon="sparkle", main="intensity",
    info="Only pixels brighter than the threshold glow. Radius 0-100, intensity 0-100, threshold 0-100, tint.",
    params=[("Radius", "radius", "f", 30, 0, 100, 1), ("Intensity", "intensity", "f", 60, 0, 100, 1),
            ("Threshold", "threshold", "f", 55, 0, 100, 1), ("Color Tint", "tint", "c", "#FFFFFFFF")],
    glsl="""
uniform float radius; uniform float intensity; uniform float threshold; uniform vec4 tint;
vec3 bright(vec2 q) {
  vec3 c = texture2D(texture0, q).rgb;
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  return c * smoothstep(threshold / 100.0, threshold / 100.0 + 0.15, l);
}
vec4 effect_main(vec2 uv, vec4 base) {
  float aspect = resolution.y / max(resolution.x, 1.0);
  vec2 r = vec2(aspect, 1.0) * radius * 0.0012;
  vec3 acc = vec3(0.0); float total = 0.0;
  for (int i = 0; i < 24; i++) {
    float fi = float(i);
    float d = sqrt((fi + 0.5) / 24.0);
    float a = fi * 2.39996;
    vec2 o = vec2(cos(a), sin(a)) * d * r * 10.0;
    float w = exp(-d * d * 2.5);
    acc += bright(uv + o) * w; total += w;
  }
  vec3 glow = acc / total * tint.rgb * (intensity / 100.0) * 2.0;
  vec4 s = texture2D(texture0, uv);
  return vec4(clamp(s.rgb + glow, 0.0, 1.0), max(s.a, clamp(dot(glow, vec3(0.333)), 0.0, 1.0)));
}""")

CHROMATIC = dict(
    title="Chromatic Aberration", icon="layers", main="offset",
    info="Lens color fringing. Offset 0-50 px, angle 0-360 or radial (stronger at the edges).",
    params=[("Offset (px)", "offset", "f", 6, 0, 50, 0.5), ("Angle", "angle", "f", 0, 0, 360, 1),
            ("Radial", "radial", "b", False)],
    glsl="""
uniform float offset; uniform float angle; uniform float radial;
vec4 effect_main(vec2 uv, vec4 base) {
  vec2 dir = vec2(cos(radians(angle)), sin(radians(angle))) * offset / max(resolution, vec2(1.0));
  if (radial > 0.5) dir = (uv - 0.5) * offset / max(resolution.x, 1.0) * 4.0;
  vec4 r = texture2D(texture0, uv + dir);
  vec4 g = texture2D(texture0, uv);
  vec4 b = texture2D(texture0, uv - dir);
  return blend(blend_mode, base, vec4(r.r, g.g, b.b, max(max(r.a, g.a), b.a)));
}""")

PIXELATE = dict(
    title="Pixelate", icon="bars", main="size",
    info="Mosaic blocks 1-100 px, square or circular dots.",
    params=[("Pixel Size", "size", "f", 12, 1, 100, 1), ("Shape", "shape", "d", "Square", None, None, ["Square", "Circle"]),
            ("Dot Background", "dot_bg", "c", "#000000FF")],
    glsl="""
uniform float size; uniform float shape; uniform vec4 dot_bg;
vec4 effect_main(vec2 uv, vec4 base) {
  vec2 cell = vec2(max(size, 1.0)) / max(resolution, vec2(1.0));
  vec2 center = (floor(uv / cell) + 0.5) * cell;
  vec4 c = texture2D(texture0, center);
  if (shape > 0.5) {
    vec2 d = (uv - center) / cell;
    float m = smoothstep(0.5, 0.45, length(d));
    c = mix(dot_bg, c, m);
  }
  return blend(blend_mode, base, c);
}""")

SCANLINES = dict(
    title="Scanlines", icon="bars", main="opacity",
    info="CRT lines. Density 1-50 px, opacity, thickness and scroll speed -5..+5.",
    params=[("Line Density (px)", "density", "f", 4, 1, 50, 1), ("Opacity", "opacity", "f", 35, 0, 100, 1),
            ("Thickness", "thickness", "f", 0.5, 0.05, 0.95, 0.01), ("Scroll Speed", "speed", "f", 0.5, -5, 5, 0.01),
            ("Line Color", "line", "c", "#000000FF")],
    glsl="""
uniform float density; uniform float opacity; uniform float thickness; uniform float speed; uniform vec4 line;
vec4 effect_main(vec2 uv, vec4 base) {
  vec4 s = texture2D(texture0, uv);
  float y = uv.y * resolution.y / max(density, 1.0) + time * speed * 4.0;
  float m = step(1.0 - thickness, fract(y));
  return vec4(mix(s.rgb, line.rgb, m * opacity / 100.0 * line.a), s.a);
}""")

GRAIN = dict(
    title="Grain / Noise", icon="sparkle", main="intensity",
    info="Film grain. Intensity 0-100, refresh 0-60 fps, monochrome or color noise.",
    params=[("Intensity", "intensity", "f", 25, 0, 100, 1), ("Speed (fps)", "speed", "f", 24, 0, 60, 1),
            ("Monochrome", "mono", "b", True), ("Grain Size", "gsize", "f", 1, 1, 8, 0.1)],
    glsl="""
uniform float intensity; uniform float speed; uniform float mono; uniform float gsize;
float rnd(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
vec4 effect_main(vec2 uv, vec4 base) {
  vec4 s = texture2D(texture0, uv);
  vec2 px = floor(uv * resolution / max(gsize, 1.0));
  float t = speed > 0.0 ? floor(time * speed) : 0.0;
  vec3 n = vec3(rnd(px + t * 1.7));
  if (mono < 0.5) n = vec3(rnd(px + t * 1.7), rnd(px + t * 2.3 + 5.0), rnd(px + t * 3.1 + 9.0));
  return vec4(clamp(s.rgb + (n - 0.5) * intensity / 100.0 * 0.7, 0.0, 1.0), s.a);
}""")

WAVE = dict(
    title="Wave / Ripple", icon="tune", main="amplitude",
    info="Sine displacement. Amplitude 0-100 px, frequency 0.1-10, speed 0-5, horizontal, vertical or radial.",
    params=[("Amplitude (px)", "amplitude", "f", 12, 0, 100, 0.5), ("Frequency", "frequency", "f", 3, 0.1, 10, 0.01),
            ("Speed", "speed", "f", 1, 0, 5, 0.01),
            ("Direction", "direction", "d", "Horizontal", None, None, ["Horizontal", "Vertical", "Radial"]),
            ("Center X", "center_x", "f", 0.5, 0, 1, 0.001), ("Center Y", "center_y", "f", 0.5, 0, 1, 0.001)],
    glsl="""
uniform float amplitude; uniform float frequency; uniform float speed; uniform float direction;
uniform float center_x; uniform float center_y;
vec4 effect_main(vec2 uv, vec4 base) {
  vec2 a = amplitude / max(resolution, vec2(1.0));
  float ph = time * speed * 6.2831853;
  vec2 q = uv;
  if (direction < 0.5) q.x += sin(uv.y * frequency * 6.2831853 + ph) * a.x;
  else if (direction < 1.5) q.y += sin(uv.x * frequency * 6.2831853 + ph) * a.y;
  else {
    vec2 c = vec2(center_x, center_y);
    vec2 d = uv - c;
    float r = length(d * vec2(resolution.x / max(resolution.y, 1.0), 1.0));
    q += normalize(d + 1e-5) * sin(r * frequency * 20.0 - ph) * a.y;
  }
  return blend(blend_mode, base, texture2D(texture0, clamp(q, 0.0, 1.0)));
}""")

LIBRARY = {
    "ZOOMBLUREFFECT": ZOOMBLUR, "TWIRLEFFECT": TWIRL, "KALEIDOSCOPEEFFECT": KALEIDOSCOPE,
    "REFLECTIONEFFECT": REFLECTION, "BOX3DEFFECT": BOX3D, "COLORCORRECTIONEFFECT": COLORCORRECTION,
    "BLOOMEFFECT": BLOOM, "CHROMATICEFFECT": CHROMATIC, "PIXELATEEFFECT": PIXELATE,
    "SCANLINESEFFECT": SCANLINES, "GRAINEFFECT": GRAIN, "WAVEEFFECT": WAVE,
}
ORDER = list(LIBRARY)


def param_path(label):
    return "Effect Settings/Parameters/" + label


def react_level(snap, measure, source):
    """0..1 audio level that drives an effect's main parameter."""
    if source == "Nothing" or not snap:
        return 0.0
    if source == "Beat":
        return float(measure.pulse(2.0)) if measure is not None else (1.0 if snap.get("beat") else 0.0)
    if source == "Volume":
        return min(1.0, float(snap.get("rms", 0.0)) * 2.0)
    if source in ("Kick", "Snare", "HiHat"):
        return float(snap.get(source.lower(), 0.0))
    if source == "Downbeat":
        return 1.0 if snap.get("downbeat") else 0.0
    return float(snap.get(source.lower(), 0.0))
