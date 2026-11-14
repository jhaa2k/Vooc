"""Layer renderers: Image, Text, Bars, Particles.

Each renderer owns one persistent InstructionGroup (`self.group`). Every frame
`update(frame)` reads its LayerModel values (read-only) and mutates the GPU
instructions in place - no widgets, no per-frame allocation of instructions
except when the structure changes (e.g. text edited, bar count changed).
"""
import math
import os
import random

import numpy as np
from kivy.graphics import (BindTexture, Color, InstructionGroup, Mesh, PopMatrix,
                           PushMatrix, Rectangle, RenderContext, Rotate, Scale,
                           Translate)

from ..model import hex_to_rgba
from . import shapes, textures
from .gl import IMAGE_FS, VCOLOR_FMT, VCOLOR_FS, VCOLOR_VS, BlendState

G = "General/"
MAX_QUADS_PER_MESH = 16000          # 4 vertices each -> stays below the 65535 index limit

_ANCHOR_X = {"start": 0, "centerLeft": 0, "topLeft": 0, "bottomLeft": 0,
             "center": .5, "topCenter": .5, "bottomCenter": .5, "top": .5, "bottom": .5,
             "end": 1, "topRight": 1, "bottomRight": 1, "centerRight": 1}
_ANCHOR_Y = {"bottom": 0, "bottomLeft": 0, "bottomCenter": 0, "bottomRight": 0, "end": 0,
             "center": .5, "centerLeft": .5, "centerRight": .5,
             "top": 1, "topLeft": 1, "topCenter": 1, "topRight": 1, "start": 1}
_FILL = ("justify", "fill", "stretch")


# ------------------------------------------------------------------ transform
class LayerTransform:
    """General -> Position / Anchor / Scale & Rotation / Measure Engine.

    Box placement:  origin = Position * canvas - anchor * box
    Rotation and scale happen around Pivot (relative to the box).
    """

    def __init__(self):
        self.push = PushMatrix()
        self.t_pivot = Translate()
        self.rotate = Rotate(angle=0, axis=(0, 0, 1))
        self.scale = Scale(1, 1, 1)
        self.t_back = Translate()
        self.pop = PopMatrix()

    def begin(self, group):
        for i in (self.push, self.t_pivot, self.rotate, self.scale, self.t_back):
            group.add(i)

    def end(self, group):
        group.add(self.pop)

    @staticmethod
    def compute(v, measure, canvas_w, canvas_h, box_w, box_h):
        ax_name, ay_name = v[G + "Position/Anchor X"], v[G + "Position/Anchor Y"]
        if ax_name in _FILL:
            box_w, ax = canvas_w, 0.0
        else:
            ax = _ANCHOR_X.get(ax_name, v[G + "Position/Alignment Position X"])
        if ay_name in _FILL:
            box_h, ay = canvas_h, 0.0
        else:
            ay = _ANCHOR_Y.get(ay_name, v[G + "Position/Alignment Position Y"])
        dx, dy = measure.position_offset(v[G + "Measure Engine/Measure Pos"],
                                         v[G + "Measure Engine/Pos Amount (A)"],
                                         v[G + "Measure Engine/Pos Speed (B)"])
        px = (0.0 if ax_name in _FILL else v[G + "Position/Position X"] + dx) * canvas_w
        py = (0.0 if ay_name in _FILL else v[G + "Position/Position Y"] + dy) * canvas_h
        ox, oy = px - ax * box_w, py - ay * box_h
        msx, msy = measure.scale_offset(v[G + "Measure Engine/Measure Scale"],
                                        v[G + "Measure Engine/Scale Amount (A)"],
                                        v[G + "Measure Engine/Scale Speed (B)"])
        sx = v[G + "Scale & Rotation/Scale X"]
        sy = sx if v[G + "Scale & Rotation/Scale Is Uniform"] else v[G + "Scale & Rotation/Scale Y"]
        rot = v[G + "Scale & Rotation/Rotation"] * 360.0 + measure.rotation_offset(
            v[G + "Measure Engine/Measure Rot"], v[G + "Measure Engine/Rot Amount (A)"],
            v[G + "Measure Engine/Rot Speed (B)"])
        pvx, pvy = v[G + "Scale & Rotation/Pivot X"] * box_w, v[G + "Scale & Rotation/Pivot Y"] * box_h
        return dict(origin=(ox, oy), box=(box_w, box_h), pivot=(pvx, pvy),
                    scale=(sx * msx, sy * msy), rotation=rot)

    def apply(self, t):
        ox, oy = t["origin"]
        pvx, pvy = t["pivot"]
        self.t_pivot.xy = (ox + pvx, oy + pvy)
        self.rotate.angle = -t["rotation"]           # clockwise like Avee
        self.scale.x, self.scale.y = t["scale"]
        self.t_back.xy = (-pvx, -pvy)


class BaseLayer:
    def __init__(self, layer, scene):
        self.layer = layer
        self.scene = scene
        self.group = InstructionGroup()
        self.blend = BlendState()
        self.transform = LayerTransform()
        self.build()

    @property
    def values(self):
        return self.layer.values

    def build(self):
        self.group.clear()
        self.group.add(self.blend.begin)
        self.transform.begin(self.group)
        self.content = InstructionGroup()
        self.group.add(self.content)
        self.transform.end(self.group)
        self.group.add(self.blend.end)

    def update(self, frame):
        frame.measure.use_layer(self.values)
        self.blend.mode = self.values[G + "Layer/Blend Mode"]
        box = self.render(frame)
        t = LayerTransform.compute(self.values, frame.measure, frame.w, frame.h, *box)
        self.transform.apply(t)
        self.after_transform(t, frame)

    def after_transform(self, t, frame):
        pass

    def render(self, frame):
        return frame.w, frame.h


# ------------------------------------------------------------------ IMAGE
class ImageLayer(BaseLayer):
    P = "Image Source/Source/"

    def build(self):
        super().build()
        self.ctx = RenderContext(use_parent_projection=True, use_parent_modelview=True,
                                 use_parent_frag_modelview=True)
        self.ctx.shader.fs = IMAGE_FS
        self.ctx["texture1"] = 1
        self.mask_bind = BindTexture(index=1)
        self.color = Color(1, 1, 1, 1)
        self.mesh = Mesh(mode="triangle_fan", fmt=[(b"vPosition", 2, "float"),
                                                   (b"vTexCoords0", 2, "float")])
        self.ctx.add(self.mask_bind)
        self.ctx.add(self.color)
        self.ctx.add(self.mesh)
        self.content.add(self.ctx)
        self._key = None
        self.box = (1, 1)

    def _pick_texture(self, frame):
        v = self.values
        if v["Generated Album Art/Album Art/Enable Auto-Fetch"]:
            tex = textures.load(frame.metadata.get("art", ""))
            if tex is not None:
                return tex
        t = frame.time * float(v.get(self.P + "GIF Speed", 1.0))
        for path in (v[self.P + "Pick Image"], v["Generated Album Art/Album Art/Fallback Image"]):
            tex = textures.frame(path, t)
            if tex is not None:
                return tex
        return textures.placeholder_art()

    def render(self, frame):
        v = self.values
        tex = self._pick_texture(frame)
        size = v[self.P + "Size"] * min(frame.w, frame.h)
        tw, th = max(1, tex.width), max(1, tex.height)
        aspect = tw / float(th)
        keep, crop = v[self.P + "Keep Aspect Ratio"], v[self.P + "Crop to Fit"]
        # texture region (u0, v0, u1, v1)
        u0, v0, u1, v1 = 0.0, 0.0, 1.0, 1.0
        bw = bh = size
        if crop:
            if aspect > 1:
                cut = (1 - 1 / aspect) / 2
                u0, u1 = cut, 1 - cut
            else:
                cut = (1 - aspect) / 2
                v0, v1 = cut, 1 - cut
        elif keep:
            if aspect > 1:
                bh = size / aspect
            else:
                bw = size * aspect
        shape = v[self.P + "Shape"]
        radius = float(v.get(self.P + "Corner Radius", 0.0))
        key = (id(tex), shape, round(bw, 1), round(bh, 1), u0, v0, u1, v1, round(radius, 3))
        if key != self._key:
            self._key = key
            if radius > 0 and shape in ("None", "Rectangle", "Square"):
                pts = shapes.rounded_rect(radius, bw, bh)
            else:
                poly = shapes.mask_polygon(shape)
                # fit the polygon into the box (keeps circle round inside a square box)
                pts = (poly + 1.0) * 0.5
            tc = tex.tex_coords        # respects flipped textures (album art, fbo)

            def uv(x, y):
                uu = u0 + (u1 - u0) * x
                vv = v0 + (v1 - v0) * y
                return [tc[0] + (tc[2] - tc[0]) * uu, tc[1] + (tc[5] - tc[1]) * vv]

            verts = [bw * 0.5, bh * 0.5] + uv(0.5, 0.5)
            for x, y in list(pts) + [pts[0]]:
                verts += [x * bw, y * bh] + uv(x, y)
            self.mesh.vertices = verts
            self.mesh.indices = list(range(len(verts) // 4))
            self.mesh.texture = tex
        self._apply_uniforms(tex, frame)
        return bw, bh

    def _apply_uniforms(self, tex, frame):
        v = self.values
        c = self.ctx
        self.color.a = float(v.get(self.P + "Opacity", 1.0))
        c["tint"] = hex_to_rgba(v["Color Adjustments/Adjustments/Tint"])
        c["brightness"] = (v["Color Adjustments/Adjustments/Brightness"] - 0.5) * 2.0
        c["contrast"] = v["Color Adjustments/Adjustments/Contrast"] * 2.0
        c["saturation"] = v["Color Adjustments/Adjustments/Saturation"] * 2.0
        c["hue"] = v["Color Adjustments/Adjustments/Hue Shift"] * 2 * math.pi
        c["key_on"] = 1.0 if v["Color Key (Chroma Key)/Color Key/Enable Color Key"] else 0.0
        c["key_color"] = hex_to_rgba(v["Color Key (Chroma Key)/Color Key/Key Color"])
        c["key_threshold"] = v["Color Key (Chroma Key)/Color Key/Threshold / Tolerance"] * 1.2
        c["key_soft"] = v["Color Key (Chroma Key)/Color Key/Softness"]
        c["blur_radius"] = v["Blur/Blur/Blur Radius"] * 0.05
        c["blur_type"] = float(["Gaussian", "Box", "Motion"].index(v["Blur/Blur/Blur Type"]))
        c["texel"] = (1.0 / max(1, tex.width), 1.0 / max(1, tex.height))
        mask = textures.load(v["Masking/Mask/Mask Image"])
        c["mask_on"] = 1.0 if mask is not None else 0.0
        c["mask_mode"] = float(["Alpha", "Luminance", "Inverted Alpha"].index(v["Masking/Mask/Mask Mode"]))
        self.mask_bind.texture = mask or textures.white()


# ------------------------------------------------------------------ TEXT
FONT_ALIASES = {
    "Roboto": "Roboto",
    "RobotoMono": "data/fonts/RobotoMono-Regular.ttf",
    "DejaVuSans": "data/fonts/DejaVuSans.ttf",
}


def resolve_font(name):
    if name and os.path.isfile(name):
        return name
    return FONT_ALIASES.get(name, "Roboto")


def _fmt_time(seconds):
    seconds = max(0, int(seconds))
    return "%d:%02d" % (seconds // 60, seconds % 60)


def substitute(text, frame):
    meta = frame.metadata
    title, artist = meta.get("title", "") or "", meta.get("artist", "") or ""
    repl = {
        "%title%": title, "%artist%": artist, "%album%": meta.get("album", "") or "",
        "%time%": _fmt_time(frame.position), "%duration%": _fmt_time(frame.duration),
        "%remaining%": "-" + _fmt_time(max(0.0, frame.duration - frame.position)),
        "{ArtistOrTitle}": artist or title, "{Title}": title, "{Artist}": artist,
    }
    for k, val in repl.items():
        if k in text:
            text = text.replace(k, val)
    return text


class TextLayer(BaseLayer):
    def build(self):
        super().build()
        self.shadow_group = InstructionGroup()
        self.outline_group = InstructionGroup()
        self.fill_group = InstructionGroup()
        self.shadow_color = Color(0, 0, 0, .5)
        self.outline_color = Color(0, 0, 0, 1)
        self.fill_color = Color(1, 1, 1, 1)
        self.anim_scale = Scale(1, 1, 1)
        self.anim_push, self.anim_pop = PushMatrix(), PopMatrix()
        self.anim_t1, self.anim_t2 = Translate(), Translate()
        for i in (self.anim_push, self.anim_t1, self.anim_scale, self.anim_t2,
                  self.shadow_color, self.shadow_group, self.outline_color,
                  self.outline_group, self.fill_color, self.fill_group, self.anim_pop):
            self.content.add(i)
        self._glyphs = {}
        self._layout_key = None
        self._text = None
        self._anim_start = 0.0
        self.box = (1, 1)

    def _glyph(self, ch, font_name, size, bold, italic, outline):
        key = (ch, font_name, size, bold, italic, outline)
        tex = self._glyphs.get(key)
        if tex is None:
            from kivy.core.text import Label as CoreLabel
            kw = dict(text=ch, font_size=size, font_name=font_name, bold=bold,
                      italic=italic, color=(1, 1, 1, 1), padding=0)
            if outline:
                kw.update(outline_width=outline, outline_color=(1, 1, 1))
            try:
                label = CoreLabel(**kw)
                label.refresh()
                tex = label.texture
            except Exception:
                kw.pop("outline_width", None)
                kw.pop("outline_color", None)
                label = CoreLabel(**kw)
                label.refresh()
                tex = label.texture
            if len(self._glyphs) > 3000:
                self._glyphs.clear()
            self._glyphs[key] = tex
        return tex

    def render(self, frame):
        v = self.values
        raw = v["Content/Text/Text Input"]
        text = substitute(raw, frame) if v["Content/Text/Use Metadata Variables"] else raw
        if text != self._text:
            self._text = text
            self._anim_start = frame.time
        anim = v["Text Animation/Animation/Animation Type"]
        duration = 0.15 + (1.0 - v["Text Animation/Animation/Animation Speed"]) * 1.6
        progress = min(1.0, (frame.time - self._anim_start) / duration) if anim != "None" else 1.0
        visible = len(text)
        if anim == "Typewriter":
            cps = max(0.5, v["Content/Text/Typewriter Speed"] * 60.0)
            visible = int((frame.time - self._anim_start) * cps)
        font_px = max(4, int(v["Typography/Font/Font Size"] * frame.h))
        weight = int(v["Typography/Font/Font Weight"])
        style = v["Typography/Font/Style"]
        bold = weight >= 600 or "Bold" in style
        italic = "Italic" in style
        outline_px = int(round(v["Appearance/Outline/Outline Width"] * font_px * 0.25))
        layout_key = (text, min(visible, len(text)), font_px, bold, italic, outline_px,
                      v["Typography/Font/Font Family"], v["Typography/Font/Letter Spacing"],
                      v["Typography/Font/Line Height"], v["Typography/Font/Alignment"],
                      v["Appearance/Shadow/Shadow X"], v["Appearance/Shadow/Shadow Y"],
                      v["Appearance/Shadow/Shadow Blur"])
        if layout_key != self._layout_key:
            self._layout_key = layout_key
            self._layout(text, min(visible, len(text)), font_px, bold, italic, outline_px)
        # colors + animation
        alpha = progress if anim in ("Fade", "Slide Up", "Slide Down", "Zoom") else 1.0
        fill = hex_to_rgba(v["Appearance/Fill/Text Color"])
        self.fill_color.rgba = fill[:3] + [fill[3] * alpha]
        sc = hex_to_rgba(v["Appearance/Shadow/Shadow Color"])
        blur_n = self._shadow_copies
        self.shadow_color.rgba = sc[:3] + [sc[3] * alpha / max(1, blur_n) ** 0.7]
        oc = hex_to_rgba(v["Appearance/Outline/Outline/Stroke Color"])
        self.outline_color.rgba = oc[:3] + [oc[3] * alpha]
        bw, bh = self.box
        ease = 1 - (1 - progress) ** 3
        dy = 0.0
        if anim == "Slide Up":
            dy = -(1 - ease) * frame.h * 0.06
        elif anim == "Slide Down":
            dy = (1 - ease) * frame.h * 0.06
        z = 0.6 + 0.4 * ease if anim == "Zoom" else 1.0
        self.anim_t1.xy = (bw / 2, bh / 2 + dy)
        self.anim_scale.x = self.anim_scale.y = z
        self.anim_t2.xy = (-bw / 2, -bh / 2)
        return self.box

    def _layout(self, text, visible, font_px, bold, italic, outline_px):
        v = self.values
        font_name = resolve_font(v["Typography/Font/Font Family"])
        spacing = v["Typography/Font/Letter Spacing"] * font_px * 0.5
        line_h = font_px * 1.25 * (v["Typography/Font/Line Height"] * 2.0)
        align = v["Typography/Font/Alignment"]
        lines = text.split("\n")
        # measure
        measured = []
        for line in lines:
            glyphs = [(ch, self._glyph(ch, font_name, font_px, bold, italic, 0)) for ch in line]
            width = sum(t.width for _, t in glyphs) + spacing * max(0, len(glyphs) - 1)
            measured.append((glyphs, width))
        max_w = max([w for _, w in measured] + [1])
        total_h = max(line_h * len(lines), 1)
        self.box = (max_w, total_h)
        for g in (self.shadow_group, self.outline_group, self.fill_group):
            g.clear()
        sx = (v["Appearance/Shadow/Shadow X"] - 0.5) * font_px * 0.6
        sy = (v["Appearance/Shadow/Shadow Y"] - 0.5) * font_px * 0.6
        blur = v["Appearance/Shadow/Shadow Blur"]
        jitter = [(0, 0)] if blur < 0.02 else [
            (math.cos(a) * blur * font_px * 0.15, math.sin(a) * blur * font_px * 0.15)
            for a in np.linspace(0, 2 * math.pi, 6, endpoint=False)]
        self._shadow_copies = len(jitter)
        shadow_on = True
        drawn = 0
        for li, (glyphs, width) in enumerate(measured):
            y = total_h - (li + 1) * line_h + (line_h - font_px * 1.2) / 2
            extra = 0.0
            spaces = sum(1 for ch, _ in glyphs if ch == " ")
            if align == "Left":
                x = 0.0
            elif align == "Right":
                x = max_w - width
            elif align == "Justify" and li < len(measured) - 1 and spaces:
                x, extra = 0.0, (max_w - width) / spaces
            else:
                x = (max_w - width) / 2
            for ch, tex in glyphs:
                if drawn >= visible:
                    break
                drawn += 1
                if ch.strip():
                    if shadow_on:
                        for jx, jy in jitter:
                            self.shadow_group.add(Rectangle(texture=tex, size=tex.size,
                                                            pos=(x + sx + jx, y + sy + jy)))
                    if outline_px:
                        otex = self._glyph(ch, font_name, font_px, bold, italic, outline_px)
                        self.outline_group.add(Rectangle(
                            texture=otex, size=otex.size,
                            pos=(x - (otex.width - tex.width) / 2, y - (otex.height - tex.height) / 2)))
                    self.fill_group.add(Rectangle(texture=tex, size=tex.size, pos=(x, y)))
                x += tex.width + spacing + (extra if ch == " " else 0.0)
            drawn += 1  # newline counts as a typed character


# ------------------------------------------------------------------ mesh helper
class QuadBatch:
    """Many colored, textured quads in as few Mesh instructions as possible."""

    def __init__(self, parent, texture=None):
        self.ctx = RenderContext(use_parent_projection=True, use_parent_modelview=True,
                                 use_parent_frag_modelview=True)
        self.ctx.shader.vs = VCOLOR_VS
        self.ctx.shader.fs = VCOLOR_FS
        parent.add(self.ctx)
        self.meshes = []
        self.texture = texture

    def set(self, corners, colors, uvs=None):
        """corners: (n, 4, 2) px; colors: (n, 4) or (n, 4, 4) rgba; uvs: (4, 2)."""
        n = len(corners)
        if uvs is None:
            uvs = np.array([(0, 0), (1, 0), (1, 1), (0, 1)], np.float32)
        colors = np.asarray(colors, np.float32)
        if colors.ndim == 2:
            colors = np.repeat(colors[:, None, :], 4, axis=1)
        data = np.empty((n, 4, 8), np.float32)
        data[:, :, 0:2] = corners
        data[:, :, 2:4] = uvs
        data[:, :, 4:8] = colors
        chunks = max(1, int(math.ceil(n / float(MAX_QUADS_PER_MESH))))
        while len(self.meshes) < chunks:
            m = Mesh(fmt=VCOLOR_FMT, mode="triangles")
            self.ctx.add(m)
            self.meshes.append(m)
        for ci, mesh in enumerate(self.meshes):
            part = data[ci * MAX_QUADS_PER_MESH:(ci + 1) * MAX_QUADS_PER_MESH]
            k = len(part)
            if k == 0:
                mesh.indices = []
                mesh.vertices = []
                continue
            base = (np.arange(k, dtype=np.int32) * 4)[:, None]
            idx = (base + np.array([0, 1, 2, 2, 3, 0], np.int32)).ravel()
            mesh.vertices = part.ravel().tolist()
            mesh.indices = idx.tolist()
            mesh.texture = self.texture


def _lerp_colors(c_from, c_to, t):
    a, b = np.asarray(c_from, np.float32), np.asarray(c_to, np.float32)
    return a[None, :] + (b - a)[None, :] * np.asarray(t, np.float32)[:, None]


# ------------------------------------------------------------------ BARS
class BarsLayer(BaseLayer):
    def build(self):
        super().build()
        self.batch = QuadBatch(self.content)
        self.values_now = np.zeros(0, np.float32)

    # engine bands are log spaced 30 Hz .. 16 kHz (see audio.analysis / timeline_hub)
    F_LO, F_HI = 30.0, 16000.0

    def _band_positions(self, n_bands, count, f_lo, f_hi, linear=False):
        f_lo = max(self.F_LO, min(f_lo, self.F_HI - 1))
        f_hi = max(f_lo + 1, min(f_hi, self.F_HI))
        hz = np.linspace(f_lo, f_hi, count) if linear else np.geomspace(f_lo, f_hi, count)
        return np.log(hz / self.F_LO) / math.log(self.F_HI / self.F_LO) * n_bands - 0.5

    def _input(self, frame, count):
        v = self.values
        snap = frame.snapshot
        A = "Behavior/Audio/"
        channel = v[A + "Audio Channel"]
        f_lo = float(v.get(A + "Frequency Min (Hz)", 30))
        f_hi = float(v.get(A + "Frequency Max (Hz)", 8000))
        what = v.get(A + "Measure What", "Frequency")
        if what == "Nothing":
            return np.full(count, 0.35, np.float32)
        if what == "Beat":
            level = frame.measure.pulse(3.0) * 0.85 + snap["rms"] * 0.25
            return np.full(count, level, np.float32)
        if what == "Amplitude":
            wave = snap.get("waveform")
            wave = np.abs(np.asarray(wave if wave is not None and len(wave) else [0.0], np.float32))
            pos = np.linspace(0, len(wave) - 1, count)
            return np.clip(np.interp(pos, np.arange(len(wave)), wave) * 1.6, 0, 1).astype(np.float32)
        linear = snap.get("mapping") == "Linear"

        def pick(bands, n):
            src = np.asarray(bands, np.float32)
            pos = self._band_positions(len(src), n, f_lo, f_hi, linear)
            return np.interp(pos, np.arange(len(src)), src)
        if channel == "Stereo":
            half = max(1, count // 2)
            out = np.concatenate([pick(snap["bands_left"], half)[::-1], pick(snap["bands_right"], count - half)])
        else:
            bands = {"Left": snap["bands_left"], "Right": snap["bands_right"],
                     "Mid": snap.get("bands_mid", snap["bands"]),
                     "Side": snap.get("bands_side", snap["bands"])}.get(channel, snap["bands"])
            out = pick(bands, count)
        if v["Bars Setup/Bars/Flip Input"]:
            out = out[::-1]
        return out

    def render(self, frame):
        v = self.values
        B = "Bars Setup/Bars/"
        count = int(v[B + "Bar Count"])
        cap = getattr(self.scene, "perf", {}).get("max_bars")
        if cap:
            count = max(4, min(count, int(cap)))
        target = self._input(frame, count) * v[B + "Height Scale"]
        if self.values_now.shape[0] != count:
            self.values_now = np.zeros(count, np.float32)
        # spring physics: fast rise (Reaction Delay), slow fall (Softness)
        step = min(4.0, frame.dt * 60.0)
        rise = 1.0 - v["Behavior/Physics/Reaction Delay"] * 0.95
        fall = 1.0 - v["Behavior/Physics/Softness"] * 0.97
        acc = v["Behavior/Physics/Reaction Accumulated Delay"]
        ramp = 1.0 - acc * 0.9 * np.arange(count) / max(1, count - 1)
        k = np.where(target > self.values_now, rise, fall) * ramp
        k = 1.0 - (1.0 - np.clip(k, 0.001, 1.0)) ** step
        self.values_now += (target - self.values_now) * k
        m = min(frame.w, frame.h)
        # Max Height Scale is the full-scale bar length (input 1.0); Height Scale amplifies
        # the input, so loud songs only hit the ceiling when Height Scale is pushed up
        max_h = v[B + "Max Height Scale"]
        min_h = v[B + "Min Height Scale"]
        heights = np.clip(self.values_now * max_h, min_h, max(max_h, min_h)) * m

        shape = v[B + "Shape Path"]
        all_corners, all_colors = [], []
        offset_out = np.zeros(count, np.float32)
        offset_in = np.zeros(count, np.float32)
        for si in (1, 2, 3):
            S = "Segment %d/Segment %d/" % (si, si)
            if not v[S + "Enable Segment"]:
                continue
            seg_shape = v[S + "Shape Path"]
            own_path = seg_shape != "None"
            path_name = seg_shape if own_path else shape
            pts, nrm, closed = shapes.sample_path(path_name, count, frame.snapshot["waveform"])
            ex, ey = shapes.path_extent(path_name, v[B + "Path Size"], frame.w, frame.h)
            base = pts * [ex, ey] + [frame.w / 2, frame.h / 2]
            if path_name in shapes.OPEN_SHAPES:
                perim = 2 * (ey if path_name == "VerticalLine" else ex)
            else:
                perim = float(np.sum(np.linalg.norm(np.diff(
                    np.vstack([pts, pts[:1]]) * [ex, ey], axis=0), axis=1)))
            slot = perim / count
            width = slot * v[B + "Bar Width"] * (1.0 - v[B + "Bar Spacing"])
            fixed = v[S + "Fixed Height"]
            h = np.full(count, fixed * m, np.float32) if fixed > 0 else heights * v[S + "Bar Height Multiplier"]
            direction = np.ones(count, np.float32)
            if v[S + "Flip Every Other"]:
                direction[1::2] = -1.0
            tangent = np.stack([-nrm[:, 1], nrm[:, 0]], 1)
            half_w = (tangent * (width / 2.0))
            start = np.zeros(count, np.float32) if own_path else offset_out
            n_dir = nrm * direction[:, None]
            b0 = base + n_dir * start[:, None]
            b1 = base + n_dir * (start + h)[:, None]
            corners = np.stack([b0 - half_w, b0 + half_w, b1 + half_w, b1 - half_w], 1)
            t = (np.arange(count) / max(1, count - 1) + v[B + "Color Blend Offset"]) % 1.0001
            c_from, c_to = hex_to_rgba(v[S + "Color From"]), hex_to_rgba(v[S + "Color To"])
            cols = _lerp_colors(c_from, c_to, t)
            top = _lerp_colors(c_from, c_to, np.clip(t + 0.15, 0, 1))
            col4 = np.stack([cols, cols, top, top], 1)
            all_corners.append(corners)
            all_colors.append(col4)
            if v[S + "Mirror"]:
                istart = np.zeros(count, np.float32) if own_path else offset_in
                m0 = base - n_dir * istart[:, None]
                m1 = base - n_dir * (istart + h)[:, None]
                all_corners.append(np.stack([m0 - half_w, m0 + half_w, m1 + half_w, m1 - half_w], 1))
                all_colors.append(col4)
                if not own_path:
                    offset_in = offset_in + h
            if not own_path:
                offset_out = offset_out + h
        if all_corners:
            self.batch.set(np.concatenate(all_corners), np.concatenate(all_colors))
        else:
            self.batch.set(np.zeros((0, 4, 2), np.float32), np.zeros((0, 4), np.float32))
        return frame.w, frame.h


# ------------------------------------------------------------------ PARTICLES
class ParticlesLayer(BaseLayer):
    FIELDS = ("x", "y", "z", "vx", "vy", "vz", "age", "life", "rand", "wave_f", "wave_m", "phase")

    def build(self):
        super().build()
        self.batch = QuadBatch(self.content, textures.soft_dot())
        self.p = {k: np.zeros(0, np.float32) for k in self.FIELDS}
        self._spawn_acc = 0.0
        self.count_warning = False

    def _spawn(self, n, frame):
        v = self.values
        O, D, M, W = "Overall Settings/Emitter/", "Overall Settings/Direction/", "Behaviour/Motion/", "Wave Behaviour/Side Wave/"
        w, h = frame.w, frame.h
        cx, cy = w / 2, h / 2
        m = min(w, h)
        area = v[O + "Spawn Area"]
        rnd = np.random.random
        if area == "Rectangle":
            x, y = rnd(n) * w, rnd(n) * h
        elif area == "Circle":
            a = rnd(n) * 2 * math.pi
            r = m * 0.25 * np.sqrt(rnd(n) * 0.3 + 0.7)
            x, y = cx + np.cos(a) * r, cy + np.sin(a) * r
        elif area == "Line":
            x, y = rnd(n) * w, np.full(n, cy)
        else:
            x, y = np.full(n, cx), np.full(n, cy)
        z = (rnd(n) * 2 - 1) * v["Overall Settings/Perspective/Perspective Depth"]
        if v[D + "Use Random Vector Instead"]:
            ang = rnd(n) * 2 * math.pi
        elif v[D + "Vector Relative To Center"] and area != "Point":
            ang = np.arctan2(y - cy, x - cx)
            ang = np.where(np.hypot(x - cx, y - cy) < 1, rnd(n) * 2 * math.pi, ang)
        elif v[D + "Vector Relative To Center"]:
            ang = rnd(n) * 2 * math.pi
        else:
            ang = np.full(n, v[D + "Vector Angle"] * 2 * math.pi)
        az = v[D + "Vector Angle Z"] * 2 * math.pi
        speed = v[M + "Speed"] * m * 1.2 * (1.0 + (rnd(n) * 2 - 1) * v[M + "Speed Random"])
        cz = math.cos(az)
        vx, vy = np.cos(ang) * speed * cz, np.sin(ang) * speed * cz
        vz = np.full(n, math.sin(az)) * speed / m
        life = np.maximum(0.05, v[M + "Lifetime"] * 10.0 * (0.75 + rnd(n) * 0.5))
        wf = v[W + "Side Sine Wave Freq"] * 10.0 * (1 + (rnd(n) * 2 - 1) * v[W + "Side Sine Wave Freq Random"])
        wm = v[W + "Side Sine Wave Mag"] * m * 0.3 * (1 + (rnd(n) * 2 - 1) * v[W + "Side Sine Wave Mag Random"])
        new = dict(x=x, y=y, z=z, vx=vx, vy=vy, vz=vz, age=np.zeros(n), life=life,
                   rand=rnd(n), wave_f=wf, wave_m=wm, phase=rnd(n) * 2 * math.pi)
        for k in self.FIELDS:
            self.p[k] = np.concatenate([self.p[k], np.asarray(new[k], np.float32)])

    def render(self, frame):
        v = self.values
        O, M, L, S = "Overall Settings/Emitter/", "Behaviour/Motion/", "Appearance/Look/", "Appearance/Size/"
        dt = min(0.1, frame.dt)
        limit = int(v[O + "Count Limit"])
        self.count_warning = limit > 2000
        cap = getattr(self.scene, "perf", {}).get("max_particles")
        if cap is not None:
            limit = min(limit, int(cap))
        # Measure Overall Speed: the whole simulation accelerates on the beat
        speed_mult = 1.0 + frame.measure.offset(v[M + "Measure Overall Speed"],
                                                v[M + "A X Amount"], v[M + "B Y Amount"])
        sdt = dt * max(0.0, speed_mult)
        p = self.p
        alive = len(p["x"])
        # spawn
        self._spawn_acc += sdt / max(0.001, v[M + "Spawn Time"])
        n_new = int(self._spawn_acc)
        self._spawn_acc -= n_new
        n_new = max(0, min(n_new, limit - alive, 500))
        if n_new:
            self._spawn(n_new, frame)
        # integrate
        m = min(frame.w, frame.h)
        gx = (v[M + "Gravity X"] - 0.5) * 2 * m
        gy = (v[M + "Gravity Y"] - 0.5) * 2 * m
        field = v[O + "Force Field"]
        cx, cy = frame.w / 2, frame.h / 2
        if field == "Gravity":
            gy -= m * 0.8
        elif field == "Wind":
            gx += m * 0.6
        p["vx"] += gx * sdt
        p["vy"] += gy * sdt
        if field in ("Vortex", "Attractor", "Repeller"):
            dx, dy = p["x"] - cx, p["y"] - cy
            d = np.maximum(np.hypot(dx, dy), 1.0)
            if field == "Vortex":
                p["vx"] += -dy / d * m * 1.5 * sdt
                p["vy"] += dx / d * m * 1.5 * sdt
            else:
                sign = -1.0 if field == "Attractor" else 1.0
                p["vx"] += sign * dx / d * m * 1.2 * sdt
                p["vy"] += sign * dy / d * m * 1.2 * sdt
        p["x"] += p["vx"] * sdt
        p["y"] += p["vy"] * sdt
        p["z"] += p["vz"] * sdt
        p["age"] += sdt
        keep = p["age"] < p["life"]
        if not keep.all():
            for k in self.FIELDS:
                p[k] = p[k][keep]
        if len(p["x"]) > limit:
            for k in self.FIELDS:
                p[k] = p[k][-limit:]
        n = len(p["x"])
        if n == 0:
            self.batch.set(np.zeros((0, 4, 2), np.float32), np.zeros((0, 4), np.float32))
            return frame.w, frame.h
        t = np.clip(p["age"] / p["life"], 0, 1)
        # side sine wave perpendicular to velocity
        sp = np.maximum(np.hypot(p["vx"], p["vy"]), 1e-3)
        px_, py_ = -p["vy"] / sp, p["vx"] / sp
        wave = np.sin(p["age"] * p["wave_f"] + p["phase"]) * p["wave_m"]
        x = p["x"] + px_ * wave
        y = p["y"] + py_ * wave
        # perspective
        depth = v["Overall Settings/Perspective/Perspective Depth"]
        fov = math.radians(10 + v["Overall Settings/Perspective/Perspective Fov"] * 160)
        focal = 1.0 / math.tan(fov / 2)
        persp = focal / np.maximum(focal + p["z"] * depth * 2.0, 0.05) if depth > 0 else np.ones(n, np.float32)
        x = cx + (x - cx) * persp
        y = cy + (y - cy) * persp
        # size
        start, end = v[S + "Start Size"], v[S + "End Size"] + p["rand"] * v[S + "End Size Random"]
        size = start + (end - start) * t
        fin = v[S + "Fade In Size"]
        if fin > 0:
            size *= np.clip(t / fin, 0, 1)
        size *= persp ** (v[S + "End Size ZFactor"] * 2.0)
        size *= v[L + "Particle Scale"] * m * 0.12
        # alpha
        fade = v[L + "Fade In And Out Time"] * 0.5
        alpha = np.ones(n, np.float32)
        if fade > 0:
            alpha = np.clip(np.minimum(t, 1 - t) / fade, 0, 1)
        near = v[S + "Near Camera Fade Out Distance"]
        if near > 0 and depth > 0:
            alpha *= np.clip((p["z"] * depth + near) / (near * 2), 0, 1)
        cols = _lerp_colors(hex_to_rgba(v[L + "Color From"]), hex_to_rgba(v[L + "Color To"]), t)
        cols *= np.asarray(hex_to_rgba(v[O + "Color"]), np.float32)[None, :]
        cols[:, 3] *= alpha
        # quad geometry
        ratio = 2.0 ** ((v[L + "Size Aspect Ratio"] - 0.5) * 4.0)
        hw = size * math.sqrt(ratio) / 2
        hh = size / math.sqrt(ratio) / 2
        trail = v[L + "Trail Length"]
        if trail > 0:
            hw = hw * (1 + trail * sp / (m * 0.05))
        if v[L + "Velocity Angle"] or trail > 0:
            ux, uy = p["vx"] / sp, p["vy"] / sp
        else:
            ux, uy = np.ones(n, np.float32), np.zeros(n, np.float32)
        ax, ay = ux * hw, uy * hw          # along
        bx, by = -uy * hh, ux * hh         # across
        corners = np.stack([
            np.stack([x - ax - bx, y - ay - by], 1), np.stack([x + ax - bx, y + ay - by], 1),
            np.stack([x + ax + bx, y + ay + by], 1), np.stack([x - ax + bx, y - ay + by], 1)], 1)
        allc, allcol = [corners], [cols]
        if v[O + "Mirror X"]:
            mc = corners.copy()
            mc[..., 0] = frame.w - mc[..., 0]
            allc.append(mc)
            allcol.append(cols)
        if v[O + "Mirror Y"]:
            for c in list(allc):
                mc = c.copy()
                mc[..., 1] = frame.h - mc[..., 1]
                allc.append(mc)
                allcol.append(cols)
        self.batch.texture = textures.load(v[L + "Custom Image"]) or textures.soft_dot()
        self.batch.set(np.concatenate(allc), np.concatenate(allcol))
        return frame.w, frame.h


RENDERERS = {"IMAGE": ImageLayer, "TEXT": TextLayer, "BARS": BarsLayer, "PARTICLES": ParticlesLayer}
