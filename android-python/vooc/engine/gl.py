"""GLSL shaders, layer blend modes and small GL helpers."""
from kivy.graphics import Callback

try:
    from kivy.graphics import opengl as gl
except Exception:  # pragma: no cover
    gl = None

GL_MIN, GL_MAX = 0x8007, 0x8008

# ------------------------------------------------------------------ layer blending
# Layers are blended with fixed-function GL. Modes that need the destination
# pixel in a shader (Overlay, Hue, ...) use the closest GL equivalent here;
# effects (Blur/Mirror/RGB/Motion/Shader) implement every mode exactly in GLSL.
_BLEND = {
    "Alpha": ("SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "ADD"),
    "PreMulAlpha": ("ONE", "ONE_MINUS_SRC_ALPHA", "ADD"),
    "Screen": ("ONE", "ONE_MINUS_SRC_COLOR", "ADD"),
    "Add": ("SRC_ALPHA", "ONE", "ADD"),
    "AddAlpha": ("ONE", "ONE", "ADD"),
    "Multiply": ("DST_COLOR", "ONE_MINUS_SRC_ALPHA", "ADD"),
    "Darken": ("ONE", "ONE", "MIN"),
    "Lighten": ("ONE", "ONE", "MAX"),
    "ColorDodge": ("DST_COLOR", "ONE", "ADD"),
    "ColorBurn": ("ZERO", "SRC_COLOR", "ADD"),
    "Overlay": ("DST_COLOR", "SRC_COLOR", "ADD"),
    "SoftLight": ("DST_COLOR", "ONE_MINUS_SRC_ALPHA", "ADD"),
    "HardLight": ("DST_COLOR", "SRC_COLOR", "ADD"),
    "Difference": ("ONE", "ONE", "REVERSE_SUBTRACT"),
    "Exclusion": ("ONE_MINUS_DST_COLOR", "ONE_MINUS_SRC_COLOR", "ADD"),
}


def _const(name):
    return getattr(gl, "GL_" + name)


def set_blend(mode):
    if gl is None:
        return
    src, dst, eq = _BLEND.get(mode, _BLEND["Alpha"])
    try:
        if eq == "ADD":
            gl.glBlendEquation(gl.GL_FUNC_ADD)
        elif eq == "REVERSE_SUBTRACT":
            gl.glBlendEquation(gl.GL_FUNC_REVERSE_SUBTRACT)
        elif eq == "MIN":
            gl.glBlendEquation(GL_MIN)
        elif eq == "MAX":
            gl.glBlendEquation(GL_MAX)
        gl.glBlendFuncSeparate(_const(src), _const(dst), gl.GL_ONE, gl.GL_ONE_MINUS_SRC_ALPHA)
    except Exception:
        reset_blend()


def reset_blend(*_):
    if gl is None:
        return
    try:
        gl.glBlendEquation(gl.GL_FUNC_ADD)
        gl.glBlendFuncSeparate(gl.GL_SRC_ALPHA, gl.GL_ONE_MINUS_SRC_ALPHA,
                               gl.GL_ONE, gl.GL_ONE_MINUS_SRC_ALPHA)
    except Exception:
        pass


class BlendState:
    """Pair of Callback instructions that switch blending around a layer."""

    def __init__(self):
        self.mode = "Alpha"
        self.begin = Callback(lambda instr: set_blend(self.mode))
        self.end = Callback(reset_blend)


# ------------------------------------------------------------------ shaders
HEADER = """
#ifdef GL_ES
    precision highp float;
#endif
varying vec4 frag_color;
varying vec2 tex_coord0;
uniform sampler2D texture0;
"""

DEFAULT_VS = """
#ifdef GL_ES
    precision highp float;
#endif
varying vec4 frag_color;
varying vec2 tex_coord0;
attribute vec2 vPosition;
attribute vec2 vTexCoords0;
uniform mat4 modelview_mat;
uniform mat4 projection_mat;
uniform vec4 color;
uniform float opacity;
void main (void) {
  frag_color = color * vec4(1.0, 1.0, 1.0, opacity);
  tex_coord0 = vTexCoords0;
  gl_Position = projection_mat * modelview_mat * vec4(vPosition.xy, 0.0, 1.0);
}
"""

# Per-vertex color (bars + particles) -------------------------------------------
VCOLOR_FMT = [(b"vPosition", 2, "float"), (b"vTexCoords0", 2, "float"), (b"vColor", 4, "float")]
VCOLOR_VS = """
#ifdef GL_ES
    precision highp float;
#endif
varying vec4 frag_color;
varying vec2 tex_coord0;
attribute vec2 vPosition;
attribute vec2 vTexCoords0;
attribute vec4 vColor;
uniform mat4 modelview_mat;
uniform mat4 projection_mat;
uniform float opacity;
void main (void) {
  frag_color = vColor * vec4(1.0, 1.0, 1.0, opacity);
  tex_coord0 = vTexCoords0;
  gl_Position = projection_mat * modelview_mat * vec4(vPosition.xy, 0.0, 1.0);
}
"""
VCOLOR_FS = HEADER + """
void main (void) {
  gl_FragColor = frag_color * texture2D(texture0, tex_coord0);
}
"""

# Image layer: tint, B/C/S, hue, chroma key, blur, mask ----------------------------
IMAGE_FS = HEADER + """
uniform sampler2D texture1;
uniform vec4 tint;
uniform float brightness;
uniform float contrast;
uniform float saturation;
uniform float hue;
uniform float key_on;
uniform vec4 key_color;
uniform float key_threshold;
uniform float key_soft;
uniform float blur_radius;
uniform float blur_type;
uniform vec2 texel;
uniform float mask_on;
uniform float mask_mode;

vec3 hue_rotate(vec3 c, float a) {
  const vec3 k = vec3(0.57735);
  float ca = cos(a);
  return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

vec4 sample_blur(vec2 uv) {
  if (blur_radius < 0.0005) return texture2D(texture0, uv);
  vec4 acc = vec4(0.0);
  float total = 0.0;
  for (int i = 0; i < 16; i++) {
    float fi = float(i);
    vec2 o;
    if (blur_type < 0.5) {            // Gaussian (golden-angle spiral)
      float r = sqrt((fi + 0.5) / 16.0);
      float a = fi * 2.39996;
      o = vec2(cos(a), sin(a)) * r;
    } else if (blur_type < 1.5) {     // Box
      o = vec2(mod(fi, 4.0) / 1.5 - 1.0, floor(fi / 4.0) / 1.5 - 1.0);
    } else {                          // Motion (horizontal)
      o = vec2(fi / 7.5 - 1.0, 0.0);
    }
    float w = (blur_type < 0.5) ? exp(-dot(o, o) * 2.0) : 1.0;
    acc += texture2D(texture0, uv + o * blur_radius) * w;
    total += w;
  }
  return acc / total;
}

void main (void) {
  vec4 c = sample_blur(tex_coord0);
  if (key_on > 0.5) {
    float d = distance(c.rgb, key_color.rgb);
    float edge = smoothstep(key_threshold, key_threshold + key_soft + 0.0001, d);
    c.a *= edge;
  }
  c.rgb = (c.rgb - 0.5) * contrast + 0.5 + brightness;
  float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
  c.rgb = mix(vec3(l), c.rgb, saturation);
  if (abs(hue) > 0.001) c.rgb = hue_rotate(c.rgb, hue);
  c = clamp(c, 0.0, 1.0) * tint;
  if (mask_on > 0.5) {
    vec4 m = texture2D(texture1, tex_coord0);
    float v = mask_mode < 0.5 ? m.a : (mask_mode < 1.5 ? dot(m.rgb, vec3(0.299, 0.587, 0.114)) : 1.0 - m.a);
    c.a *= v;
  }
  gl_FragColor = c * frag_color;
}
"""

# ------------------------------------------------------------------ effects
EFFECT_HEADER = HEADER + """
uniform sampler2D texture1;
uniform vec2 resolution;
uniform float time;
uniform float beat;
uniform float rms;
uniform float bass;
uniform float use_extra;
uniform float fx_mix;
uniform float blend_mode;
uniform vec2 fx_pos;
uniform vec2 fx_scale;
uniform float fx_rot;
uniform vec2 fx_pivot;

vec2 fx_uv(vec2 uv) {
  vec2 p = uv - fx_pivot - fx_pos;
  float c = cos(-fx_rot), s = sin(-fx_rot);
  p = vec2(c * p.x - s * p.y, s * p.x + c * p.y) / max(fx_scale, vec2(0.0001));
  return p + fx_pivot;
}

vec4 src_sample(vec2 uv) {
  if (use_extra > 0.5) return texture2D(texture1, uv);
  return texture2D(texture0, uv);
}

float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clip_color(vec3 c) {
  float l = lum(c); float n = min(min(c.r, c.g), c.b); float x = max(max(c.r, c.g), c.b);
  if (n < 0.0) c = l + (c - l) * l / max(l - n, 0.0001);
  if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 0.0001);
  return c;
}
vec3 set_lum(vec3 c, float l) { return clip_color(c + (l - lum(c))); }
float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
vec3 set_sat(vec3 c, float s) {
  float mn = min(min(c.r, c.g), c.b); float mx = max(max(c.r, c.g), c.b);
  return mx > mn ? (c - mn) * s / (mx - mn) : vec3(0.0);
}
float overlay_ch(float b, float s) { return b < 0.5 ? 2.0 * b * s : 1.0 - 2.0 * (1.0 - b) * (1.0 - s); }
float soft_ch(float b, float s) {
  return s < 0.5 ? b - (1.0 - 2.0 * s) * b * (1.0 - b)
                 : b + (2.0 * s - 1.0) * ((b < 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b)) - b);
}

// Every blend mode from the BLEND list, in list order (0 = Alpha ... 18 = Luminosity)
vec4 blend(float m, vec4 base, vec4 top) {
  vec3 b = base.rgb, s = top.rgb, r;
  if (m < 0.5)       r = s;                                   // Alpha
  else if (m < 1.5)  r = s / max(top.a, 0.0001);             // PreMulAlpha
  else if (m < 2.5)  r = 1.0 - (1.0 - b) * (1.0 - s);        // Screen
  else if (m < 3.5)  r = b + s;                               // Add
  else if (m < 4.5)  r = b + s * top.a;                       // AddAlpha
  else if (m < 5.5)  r = b * s;                               // Multiply
  else if (m < 6.5)  r = min(b, s);                           // Darken
  else if (m < 7.5)  r = max(b, s);                           // Lighten
  else if (m < 8.5)  r = b / max(1.0 - s, 0.0001);            // ColorDodge
  else if (m < 9.5)  r = 1.0 - (1.0 - b) / max(s, 0.0001);    // ColorBurn
  else if (m < 10.5) r = vec3(overlay_ch(b.r, s.r), overlay_ch(b.g, s.g), overlay_ch(b.b, s.b));
  else if (m < 11.5) r = vec3(soft_ch(b.r, s.r), soft_ch(b.g, s.g), soft_ch(b.b, s.b));
  else if (m < 12.5) r = vec3(overlay_ch(s.r, b.r), overlay_ch(s.g, b.g), overlay_ch(s.b, b.b));
  else if (m < 13.5) r = abs(b - s);                          // Difference
  else if (m < 14.5) r = b + s - 2.0 * b * s;                 // Exclusion
  else if (m < 15.5) r = set_lum(set_sat(s, sat(b)), lum(b)); // Hue
  else if (m < 16.5) r = set_lum(set_sat(b, sat(s)), lum(b)); // Saturation
  else if (m < 17.5) r = set_lum(s, lum(b));                  // Color
  else               r = set_lum(b, lum(s));                  // Luminosity
  r = clamp(r, 0.0, 1.0);
  float a = (m > 2.5 && m < 4.5) ? base.a : top.a;
  return vec4(mix(b, r, a), max(base.a, top.a));
}
"""

EFFECT_FOOTER = """
void main (void) {
  vec4 base = texture2D(texture0, tex_coord0);
  vec4 result = effect_main(fx_uv(tex_coord0), base);
  gl_FragColor = mix(base, result, clamp(fx_mix, 0.0, 1.0));
}
"""

RGBSPLIT_FS = EFFECT_HEADER + """
uniform float split;
uniform float angle;
uniform vec3 ch_off;
uniform float glitch;
float rnd(float x) { return fract(sin(x * 12.9898) * 43758.5453); }
vec4 effect_main(vec2 uv, vec4 base) {
  float amount = split * 0.04 + glitch * 0.05;
  vec2 dir = vec2(cos(angle), sin(angle)) * amount;
  float row = floor(uv.y * 28.0);
  float on = step(0.6, rnd(row + floor(time * 18.0))) * glitch;
  uv.x += (rnd(row * 3.1 + floor(time * 23.0)) - 0.5) * 0.12 * on;
  vec4 r = src_sample(uv + dir * (ch_off.r * 2.0 - 1.0));
  vec4 g = src_sample(uv + dir * (ch_off.g * 2.0 - 1.0));
  vec4 b = src_sample(uv + dir * (ch_off.b * 2.0 - 1.0));
  vec4 top = vec4(r.r, g.g, b.b, max(max(r.a, g.a), b.a));
  return blend(blend_mode, base, top);
}
""" + EFFECT_FOOTER

MIRROR_FS = EFFECT_HEADER + """
uniform float mode;
uniform float flip;
uniform float offset;
uniform vec4 tint;
vec2 fold(float v, float axis, float f) {
  if (f < 0.5) { if (v > axis) v = 2.0 * axis - v; }
  else { if (v < axis) v = 2.0 * axis - v; }
  return vec2(v, 0.0);
}
vec4 effect_main(vec2 uv, vec4 base) {
  if (mode < 2.5) {
    if (mode < 0.5 || mode > 1.5) uv.x = fold(uv.x, offset, flip).x;
    if (mode > 0.5) uv.y = fold(uv.y, offset, flip).x;
  } else {
    float aspect = resolution.x / max(resolution.y, 1.0);
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
    float r = length(p);
    float seg = 6.2831853 / (4.0 + floor(offset * 12.0));
    float a = mod(atan(p.y, p.x) + time * 0.05 * flip, seg);
    a = abs(a - seg * 0.5);
    uv = vec2(cos(a), sin(a)) * r / vec2(aspect, 1.0) + 0.5;
  }
  vec4 top = src_sample(clamp(uv, 0.0, 1.0)) * tint;
  return blend(blend_mode, base, top);
}
""" + EFFECT_FOOTER

BLUR_FS = EFFECT_HEADER + """
uniform float radius;
uniform vec2 sc1;
uniform vec2 sc2;
uniform vec2 sc3;
uniform vec4 col1;
uniform vec4 col2;
uniform vec4 col3;
uniform vec4 tint;
uniform float show_top;
uniform float show_under;
vec4 blur_at(vec2 uv, vec2 r) {
  vec4 acc = vec4(0.0);
  float total = 0.0;
  for (int i = 0; i < 16; i++) {
    float fi = float(i);
    float d = sqrt((fi + 0.5) / 16.0);
    float a = fi * 2.39996;
    vec2 o = vec2(cos(a), sin(a)) * d * r;
    float w = exp(-d * d * 2.0);
    acc += texture2D(texture0, uv + o) * w;
    total += w;
  }
  return acc / total;
}
vec4 effect_main(vec2 uv, vec4 base) {
  float aspect = resolution.y / max(resolution.x, 1.0);
  vec2 r = vec2(radius * 0.06 * aspect, radius * 0.06);
  vec4 g = blur_at(uv, r * sc1) * col1 + blur_at(uv, r * sc2) * col2 + blur_at(uv, r * sc3) * col3;
  g = clamp(g * tint, 0.0, 1.0);
  float strength = 1.0;
  if (use_extra > 0.5) strength = lum(texture2D(texture1, tex_coord0).rgb);
  g *= strength;
  vec4 outc = g;
  if (show_under > 0.5) outc = blend(blend_mode, base, g);
  if (show_top > 0.5) outc = vec4(mix(outc.rgb, base.rgb, base.a), max(outc.a, base.a));
  return outc;
}
""" + EFFECT_FOOTER

MOTION_FS = EFFECT_HEADER + """
uniform vec2 velocity;
uniform float zoom;
uniform float amount;
uniform vec4 tint;
uniform float show_top;
uniform float show_under;
vec4 effect_main(vec2 uv, vec4 base) {
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 16; i++) {
    float t = float(i) / 15.0 - 0.5;
    vec2 o = (velocity + (uv - 0.5) * zoom) * amount * t;
    acc += src_sample(uv + o);
  }
  vec4 g = clamp((acc / 16.0) * tint, 0.0, 1.0);
  vec4 sharp = src_sample(uv);
  vec4 outc = g;
  if (show_under > 0.5 || use_extra > 0.5) outc = blend(blend_mode, base, g);
  else outc = blend(blend_mode, vec4(0.0), g);
  if (show_top > 0.5) outc = vec4(mix(outc.rgb, sharp.rgb, sharp.a * 0.5), max(outc.a, sharp.a));
  return outc;
}
""" + EFFECT_FOOTER


def custom_fs(user_code, variables):
    decl = []
    for var in variables:
        glsl_type = {"Float": "float", "Vector2": "vec2", "Vector3": "vec3", "Color": "vec4"}.get(var["type"])
        if glsl_type:
            decl.append("uniform %s %s;" % (glsl_type, var["name"]))
    return EFFECT_HEADER + "\n".join(decl) + "\n" + user_code + """
vec4 effect_main(vec2 uv, vec4 base) {
  vec4 c;
  if (use_extra > 0.5) c = effect(texture2D(texture1, uv), texture1, uv, gl_FragCoord.xy);
  else c = effect(texture2D(texture0, uv), texture0, uv, gl_FragCoord.xy);
  return blend(blend_mode, base, c);
}
""" + EFFECT_FOOTER
