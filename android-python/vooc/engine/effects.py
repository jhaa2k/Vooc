"""Post-processing effects. Each effect is one full-screen Fbo pass whose input
is everything rendered beneath it (z-order) and whose output feeds the layers
above it."""
import math

from kivy.graphics import BindTexture, Color, Fbo, Rectangle

from ..model import hex_to_rgba
from ..property_schema import BLEND
from . import gl, textures

G = "General/"


def _blend_index(name):
    return float(BLEND.index(name)) if name in BLEND else 0.0


class EffectPass:
    FS = None

    def __init__(self, layer, size):
        self.layer = layer
        self.size = size
        self.fbo = Fbo(size=size, with_stencilbuffer=False)
        self.error = ""
        self.compile()
        with self.fbo:
            self.extra = BindTexture(texture=textures.white(), index=1)
            Color(1, 1, 1, 1)
            self.rect = Rectangle(size=size, pos=(0, 0))
        self.fbo["texture1"] = 1
        self._last_transform = None

    @property
    def texture(self):
        return self.fbo.texture

    @property
    def values(self):
        return self.layer.values

    def fragment(self):
        return self.FS

    def vertex(self):
        return gl.DEFAULT_VS

    def compile(self):
        """Returns "" on success or an error message. Keeps the last good shader."""
        shader = self.fbo.shader
        old_vs, old_fs = shader.vs, shader.fs
        try:
            shader.vs = self.vertex()
            shader.fs = self.fragment()
            ok = shader.success
        except Exception as exc:  # pragma: no cover - driver specific
            ok, self.error = False, str(exc)
        if not ok:
            self.error = self.error or "GLSL compile/link failed - check the code (see log for line numbers)."
            try:
                shader.vs, shader.fs = old_vs, old_fs
            except Exception:
                pass
            return self.error
        self.error = ""
        return ""

    def set_input(self, texture):
        self.rect.texture = texture

    def transform_uniforms(self, frame):
        v, m = self.values, frame.measure
        dx, dy = m.position_offset(v[G + "Measure Engine/Measure Pos"], v[G + "Measure Engine/Pos Amount (A)"],
                                   v[G + "Measure Engine/Pos Speed (B)"])
        msx, msy = m.scale_offset(v[G + "Measure Engine/Measure Scale"], v[G + "Measure Engine/Scale Amount (A)"],
                                  v[G + "Measure Engine/Scale Speed (B)"])
        sx = v[G + "Scale & Rotation/Scale X"]
        sy = sx if v[G + "Scale & Rotation/Scale Is Uniform"] else v[G + "Scale & Rotation/Scale Y"]
        rot = v[G + "Scale & Rotation/Rotation"] * 360 + m.rotation_offset(
            v[G + "Measure Engine/Measure Rot"], v[G + "Measure Engine/Rot Amount (A)"],
            v[G + "Measure Engine/Rot Speed (B)"])
        return dict(pos=(v[G + "Position/Position X"] - 0.5 + dx, v[G + "Position/Position Y"] - 0.5 + dy),
                    scale=(sx * msx, sy * msy), rot=math.radians(rot),
                    pivot=(v[G + "Scale & Rotation/Pivot X"], v[G + "Scale & Rotation/Pivot Y"]))

    def update(self, frame):
        f = self.fbo
        t = self.transform_uniforms(frame)
        f["resolution"] = tuple(float(s) for s in self.size)
        f["time"] = float(frame.time)
        f["beat"] = float(frame.measure.pulse(1.0))
        f["rms"] = float(frame.snapshot["rms"])
        f["bass"] = float(frame.snapshot["bass"])
        f["fx_mix"] = 1.0
        f["blend_mode"] = _blend_index(self.values[G + "Layer/Blend Mode"])
        f["fx_pos"], f["fx_scale"] = t["pos"], t["scale"]
        f["fx_rot"], f["fx_pivot"] = t["rot"], t["pivot"]
        self.set_extra(None)
        self.uniforms(frame, t)
        f.ask_update()

    def set_extra(self, path):
        tex = textures.load(path) if path else None
        self.extra.texture = tex or textures.white()
        self.fbo["use_extra"] = 1.0 if tex is not None else 0.0

    def uniforms(self, frame, t):
        pass


class RGBSplitPass(EffectPass):
    FS = gl.RGBSPLIT_FS

    def uniforms(self, frame, t):
        v, f = self.values, self.fbo
        E = "Effect Settings/"
        f["split"] = v[E + "Split/Split Amount"]
        f["angle"] = v[E + "Split/Angle / Direction"] * 2 * math.pi
        f["ch_off"] = (v[E + "Split/Red Channel Offset"], v[E + "Split/Green Channel Offset"],
                       v[E + "Split/Blue Channel Offset"])
        glitch = 0.0
        if v[E + "Glitch/Enable Glitch on Beat"]:
            dur = max(0.02, v[E + "Glitch/Glitch Duration"])
            since = frame.measure.time - frame.measure.last_beat_time
            glitch = max(0.0, 1.0 - since / dur)
        f["glitch"] = glitch


class MirrorPass(EffectPass):
    FS = gl.MIRROR_FS
    MODES = ["Horizontal", "Vertical", "Both", "Kaleidoscope"]

    def uniforms(self, frame, t):
        v, f = self.values, self.fbo
        A = "Appearance/Mirror/"
        f["mode"] = float(self.MODES.index(v[A + "Mirror Mode"]))
        f["flip"] = 1.0 if v[A + "Flip Mirror"] else 0.0
        f["offset"] = v[A + "Offset"]
        f["tint"] = hex_to_rgba(v[A + "Color"])
        self.set_extra(v[A + "Target Image"])


def _premul(hex_color):
    r, g, b, a = hex_to_rgba(hex_color)
    return (r * a, g * a, b * a, a)


class BlurPass(EffectPass):
    FS = gl.BLUR_FS

    def uniforms(self, frame, t):
        v, f = self.values, self.fbo
        R, C = "Blur Settings/", "Appearance/Content/"
        f["radius"] = v[R + "Radius/Blur Radius"] * v[R + "Radius/Blur Multiplier"]
        for i in (1, 2, 3):
            L = R + "Layer %d/%dLayer " % (i, i)
            f["sc%d" % i] = (v[L + "Scale X"], v[L + "Scale Y"])
            f["col%d" % i] = _premul(v[L + "Color"])
        f["tint"] = hex_to_rgba(v[C + "Color"])
        f["show_top"] = 1.0 if v[C + "Show Unblurred Content"] else 0.0
        f["show_under"] = 1.0 if v[C + "Show Unblurred Content Under"] else 0.0
        f["blend_mode"] = _blend_index(v[C + "Blend Mode Content"])
        self.set_extra(v[C + "Mask Image"])     # mask: luminance = blur strength


class MotionBlurPass(EffectPass):
    FS = gl.MOTION_FS

    def update(self, frame):
        super().update(frame)
        # the effect transform defines motion, not displacement
        f = self.fbo
        f["fx_pos"], f["fx_scale"], f["fx_rot"] = (0.0, 0.0), (1.0, 1.0), 0.0

    def uniforms(self, frame, t):
        v, f = self.values, self.fbo
        C, Mo = "Appearance/Content/", "Motion Blur/Motion/"
        source = v[Mo + "Motion Source"]
        relative = v[Mo + "Relative Motion Mode"]
        pos, scale = t["pos"], t["scale"][0]
        last = self._last_transform or (pos, scale)
        self._last_transform = (pos, scale)
        if relative:
            k = 1.0 / max(frame.dt, 1e-3) / 60.0
            vel = ((pos[0] - last[0][0]) * k * 4, (pos[1] - last[0][1]) * k * 4)
            zoom = (scale - last[1]) * k * 4
        else:
            vel, zoom = (pos[0] * 0.2, pos[1] * 0.2), (scale - 1.0) * 0.2
        if source == "AudioVolume":
            level = frame.measure.volume_level()
            zoom += level * 0.12 + frame.measure.pulse(2.0) * 0.08
        f["velocity"] = vel
        f["zoom"] = zoom
        f["amount"] = v[Mo + "Blur Amount Multiplier"]
        f["tint"] = hex_to_rgba(v[C + "Color"])
        f["show_top"] = 1.0 if v[C + "Show Unblurred Content"] else 0.0
        f["show_under"] = 1.0 if v[C + "Show Unblurred Content Under"] else 0.0
        f["blend_mode"] = _blend_index(v[C + "Blend Mode Content"])
        self.set_extra(v[C + "Target Image"] if source == "TargetImage" else None)


class CustomShaderPass(EffectPass):
    VARS = "Variables/Variable Mappings/Mappings"

    def __init__(self, layer, size):
        self._compiled_key = None
        super().__init__(layer, size)

    def _key(self):
        v = self.values
        return (v["Shader Code/GLSL/Fragment Shader"], v["Shader Code/GLSL/Shader Vertex"],
                tuple((x["name"], x["type"]) for x in v[self.VARS]))

    def fragment(self):
        return gl.custom_fs(self.values["Shader Code/GLSL/Fragment Shader"], self.values[self.VARS])

    def vertex(self):
        code = self.values["Shader Code/GLSL/Shader Vertex"].strip()
        return code or gl.DEFAULT_VS

    def compile(self):
        self._compiled_key = self._key()
        return super().compile()

    def uniforms(self, frame, t):
        f = self.fbo
        for var in self.values[self.VARS]:
            val = var.get("value")
            try:
                if var["type"] == "Float":
                    f[var["name"]] = float(val)
                elif var["type"] in ("Vector2", "Vector3"):
                    f[var["name"]] = tuple(float(x) for x in val)
                elif var["type"] == "Color":
                    f[var["name"]] = tuple(hex_to_rgba(val))
            except (TypeError, ValueError):
                pass
        self.set_extra(self.values["Appearance/Target/Target Image"])


PASSES = {"RGBSPLITEFFECT": RGBSplitPass, "MIRROREREFFECT": MirrorPass,
          "BLUREFFECT": BlurPass, "MOTIONBLUREFFECT": MotionBlurPass,
          "CUSTOMSHADER": CustomShaderPass}
