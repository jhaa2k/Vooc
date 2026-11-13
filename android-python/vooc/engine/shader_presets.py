"""Ready-made GLSL effects for the CUSTOMSHADER layer (Shader Code > Effect Preset).

Every preset is a normal custom shader: picking one writes its fragment code
and variable mappings into the layer, so the user can still edit the code,
tweak each variable with a slider, or save it in a template. Built-in uniforms
available to every shader: time, resolution, beat, rms, bass, mid, treble,
intensity (Shader Code > Intensity slider).

GLSL ES 1.00 only (constant loop bounds) so it compiles on every phone GPU.
"""

ZOOM_BLUR = """// Zoom Blur: radial streaks out of a center point, pumped by the beat.
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    vec2 center = vec2(centerX, centerY);
    float amount = intensity * 0.35 * (1.0 + beat * pump);
    vec4 acc = vec4(0.0);
    float total = 0.0;
    for (int i = 0; i < 20; i++) {
        float t = float(i) / 19.0;
        float w = 1.0 - t * 0.6;
        acc += texture2D(tex, center + (uv - center) * (1.0 - amount * t)) * w;
        total += w;
    }
    return acc / total;
}
"""

TWIRL = """// Twirl: swirls the image around the center; spins faster with the bass.
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    float aspect = resolution.x / max(resolution.y, 1.0);
    vec2 c = vec2(0.5, 0.5);
    vec2 p = (uv - c) * vec2(aspect, 1.0);
    float r = length(p);
    float rad = max(radius, 0.01);
    if (r < rad) {
        float pct = (rad - r) / rad;
        float a = pct * pct * (intensity * 12.0) * (0.6 + 0.4 * sin(time * speed) + bass * 0.8);
        float s = sin(a);
        float k = cos(a);
        p = vec2(k * p.x - s * p.y, s * p.x + k * p.y);
    }
    return texture2D(tex, p / vec2(aspect, 1.0) + c);
}
"""

KALEIDOSCOPE = """// Kaleidoscope: radial mirror segments that rotate over time and breathe with the bass.
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    float aspect = resolution.x / max(resolution.y, 1.0);
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
    float r = length(p) * (1.0 - bass * 0.25 * intensity);
    float seg = 6.2831853 / max(2.0, floor(segments));
    float a = mod(atan(p.y, p.x) + time * speed * 0.25, seg);
    a = abs(a - seg * 0.5);
    vec2 q = vec2(cos(a), sin(a)) * r / vec2(aspect, 1.0) + 0.5;
    vec4 k = texture2D(tex, clamp(q, 0.0, 1.0));
    return mix(color, k, clamp(intensity * 2.0, 0.0, 1.0));
}
"""

REFLECTION = """// Reflection: glossy floor mirror below the horizon with a rippling surface.
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    float h = horizon;
    if (uv.y < h) {
        float d = (h - uv.y) / max(h, 0.001);
        vec2 q = vec2(uv.x + sin(uv.y * 90.0 + time * 4.0) * 0.004 * ripple * (1.0 + rms * 2.0),
                      h + (h - uv.y));
        vec4 r = texture2D(tex, clamp(q, 0.0, 1.0));
        float f = intensity * 1.4 * (1.0 - d) * (1.0 - d);
        return vec4(mix(color.rgb, r.rgb, clamp(f * r.a, 0.0, 1.0)), max(color.a, r.a * f));
    }
    return color;
}
"""

BOX_3D = """// 3D Box: the whole scene mapped on the faces of a rotating cube (ray traced).
vec3 rot_y(vec3 v, float a) { float s = sin(a), c = cos(a); return vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z); }
vec3 rot_x(vec3 v, float a) { float s = sin(a), c = cos(a); return vec3(v.x, c * v.y - s * v.z, s * v.y + c * v.z); }
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    float aspect = resolution.x / max(resolution.y, 1.0);
    vec2 p = (uv - 0.5) * 2.0 * vec2(aspect, 1.0);
    float ay = time * speed * 0.6 + beat * 0.15;
    float ax = 0.45 * sin(time * speed * 0.37);
    vec3 ro = vec3(0.0, 0.0, 4.2 - intensity * 1.6 - beat * 0.25);
    vec3 rd = normalize(vec3(p, -2.2));
    ro = rot_x(rot_y(ro, ay), ax);
    rd = rot_x(rot_y(rd, ay), ax);
    vec3 m = 1.0 / rd;
    vec3 n = m * ro;
    vec3 k = abs(m);
    vec3 t1 = -n - k;
    vec3 t2 = -n + k;
    float tn = max(max(t1.x, t1.y), t1.z);
    float tf = min(min(t2.x, t2.y), t2.z);
    if (tn > tf || tf < 0.0) return vec4(color.rgb * background, color.a * background);
    vec3 pos = ro + rd * tn;
    vec3 a = abs(pos);
    vec2 f;
    float shade;
    if (a.x > a.y && a.x > a.z) { f = pos.zy; shade = 0.72; }
    else if (a.y > a.z) { f = pos.xz; shade = 0.86; }
    else { f = pos.xy; shade = 1.0; }
    vec4 c = texture2D(tex, f * 0.5 + 0.5);
    float edge = smoothstep(0.96, 1.0, max(abs(f.x), abs(f.y)));
    return vec4(mix(c.rgb * shade, edgeColor.rgb, edge * edgeColor.a), 1.0);
}
"""

COLOR_CORRECTION = """// Color Correction: brightness, contrast, saturation, hue, gamma and tint.
vec3 hue_rotate(vec3 c, float a) {
    const vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    vec3 c = color.rgb;
    c += (brightness - 0.5) * 2.0;
    c = (c - 0.5) * (contrast * 2.0) + 0.5;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, saturation * 2.0);
    c = hue_rotate(c, hue * 6.2831853);
    c = pow(max(c, 0.0), vec3(1.0 / max(gamma * 2.0, 0.05)));
    c *= tintColor.rgb;
    return vec4(mix(color.rgb, clamp(c, 0.0, 1.0), clamp(intensity * 2.0, 0.0, 1.0)), color.a);
}
"""

VIGNETTE = """// Vignette: darkens the edges; opens up on the beat.
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    vec2 p = uv - 0.5;
    float d = length(p * vec2(resolution.x / max(resolution.y, 1.0), 1.0));
    float v = smoothstep(0.85 - softness, 0.25, d * (1.0 + intensity) - beat * 0.06);
    return vec4(color.rgb * mix(1.0, v, clamp(intensity * 2.0, 0.0, 1.0)), color.a);
}
"""

FILM_GRAIN = """// Film Grain: animated noise plus subtle flicker.
float noise(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
vec4 effect(vec4 color, sampler2D tex, vec2 uv, vec2 coords)
{
    float n = noise(uv * resolution + fract(time * 13.0) * 100.0) - 0.5;
    float flicker = 1.0 + (noise(vec2(floor(time * 24.0), 1.0)) - 0.5) * 0.06 * intensity;
    return vec4((color.rgb + n * intensity * 0.35 * grain) * flicker, color.a);
}
"""


def _f(name, value):
    return {"name": name, "type": "Float", "value": value}


PRESETS = {
    "Zoom Blur": (ZOOM_BLUR, [_f("centerX", 0.5), _f("centerY", 0.5), _f("pump", 1.5)], 0.5),
    "Twirl": (TWIRL, [_f("radius", 0.6), _f("speed", 1.0)], 0.4),
    "Kaleidoscope": (KALEIDOSCOPE, [_f("segments", 8.0), _f("speed", 1.0)], 0.5),
    "Reflection": (REFLECTION, [_f("horizon", 0.3), _f("ripple", 1.0)], 0.5),
    "3D Box": (BOX_3D, [_f("speed", 0.6), _f("background", 0.15),
                        {"name": "edgeColor", "type": "Color", "value": "#C6FF00CC"}], 0.5),
    "Color Correction": (COLOR_CORRECTION, [_f("brightness", 0.5), _f("contrast", 0.55), _f("saturation", 0.6),
                                            _f("hue", 0.0), _f("gamma", 0.5),
                                            {"name": "tintColor", "type": "Color", "value": "#FFFFFFFF"}], 0.5),
    "Vignette": (VIGNETTE, [_f("softness", 0.3)], 0.5),
    "Film Grain": (FILM_GRAIN, [_f("grain", 0.6)], 0.4),
}

# Float variable slider ranges (default 0..1, wider where it makes sense)
RANGES = {"segments": (2.0, 24.0), "speed": (0.0, 10.0), "pump": (0.0, 10.0)}


def apply_preset(layer, name):
    """Write a preset into a CUSTOMSHADER LayerModel. Returns True if changed."""
    if name not in PRESETS:
        return False
    code, variables, intensity = PRESETS[name]
    layer.set("Shader Code/GLSL/Effect Preset", name)
    layer.set("Shader Code/GLSL/Fragment Shader", code)
    layer.set("Shader Code/GLSL/Intensity", intensity)
    layer.set("Variables/Variable Mappings/Mappings", [dict(v) for v in variables])
    layer.set("General/Layer/Tag", name)
    return True
