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
from kivy.graphics import (BindTexture, Color, InstructionGroup, MatrixInstruction, Mesh, PopMatrix,
                           PushMatrix, Rectangle, RenderContext, Rotate, RoundedRectangle, Scale,
                           Translate)

from kivy.graphics.transformation import Matrix

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
_ORIGINS = {"Center": (0.5, 0.5), "Top Left": (0.0, 1.0), "Top Right": (1.0, 1.0), "Bottom Left": (0.0, 0.0),
            "Bottom Right": (1.0, 0.0), "Top": (0.5, 1.0), "Bottom": (0.5, 0.0), "Left": (0.0, 0.5),
            "Right": (1.0, 0.5)}


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
        origin = v.get(G + "Scale & Rotation/Transform Origin", "Custom")
        px_f, py_f = _ORIGINS.get(origin, (v[G + "Scale & Rotation/Pivot X"], v[G + "Scale & Rotation/Pivot Y"]))
        pvx, pvy = px_f * box_w, py_f * box_h
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
        self.opacity = float(self.values.get(G + "Layer/Opacity", 1.0)) * self.parent_opacity
        self._apply_opacity()
        box = self.render(frame)
        t = LayerTransform.compute(self.values, frame.measure, frame.w, frame.h, *box)
        self.transform.apply(t)
        self.after_transform(t, frame)

    opacity = 1.0
    parent_opacity = 1.0

    def _apply_opacity(self):
        for obj in vars(self).values():
            ctx = getattr(obj, "ctx", None) if isinstance(obj, QuadBatch) else (obj if isinstance(obj, RenderContext) else None)
            if ctx is not None:
                ctx["opacity"] = self.opacity

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
        # image animation / skew wrap the image and its border
        self.anim_push, self.anim_pop = PushMatrix(), PopMatrix()
        self.anim_t1, self.anim_t2 = Translate(), Translate()
        self.anim_rot = Rotate(angle=0, axis=(0, 0, 1))
        self.anim_scale = Scale(1, 1, 1)
        self.anim_skew = MatrixInstruction()
        for i in (self.anim_push, self.anim_t1, self.anim_rot, self.anim_scale, self.anim_skew, self.anim_t2):
            self.content.add(i)
        self.content.add(self.ctx)
        self.border = QuadBatch(self.content)
        self.content.add(self.anim_pop)
        self._key = None
        self._gif_state = {}
        self._pts = None
        self.box = (1, 1)

    def _pick_texture(self, frame):
        v = self.values
        if v["Generated Album Art/Album Art/Enable Auto-Fetch"]:
            tex = textures.load(frame.metadata.get("art", ""))
            if tex is not None:
                return tex
        G_ = "Image Source/GIF Playback/"
        speed = float(v.get(self.P + "GIF Speed", 1.0))
        mode = v.get(G_ + "Loop Mode", "Loop")
        start = int(v.get(G_ + "Start Frame", 0))
        sync = v.get(G_ + "Sync", "Free")
        for path in (v[self.P + "Pick Image"], v["Generated Album Art/Album Art/Fallback Image"]):
            anim = textures.load_animated(path)
            if anim is not None:
                from .timing import synced_time
                t = synced_time(sync, frame.time, speed, anim.total, frame.snapshot, self._gif_state)
                return anim.step(int(t), mode, start) if sync == "Beat" else anim.at_mode(t, mode, start)
            tex = textures.load(path)
            if tex is not None:
                return tex
        return textures.placeholder_art()

    def _anim_level(self, frame, src):
        if src == "Time":
            return None
        from ..fx_library import react_level
        return react_level(frame.snapshot, frame.measure, src)

    def _animate(self, frame, bw, bh):
        v = self.values
        A = "Image Animation/Animation/"
        kind = v.get(A + "Animation", "None")
        amt = v.get(A + "Animation Amount", 0.3)
        spd = 0.2 + v.get(A + "Animation Speed", 0.5) * 4.0
        lvl = self._anim_level(frame, v.get(A + "Animate With", "Time"))
        t = frame.time * spd
        wave = math.sin(t * 2 * math.pi) if lvl is None else lvl * 2 - 1
        pos01 = (wave + 1) / 2
        rot, sc, dx, dy = 0.0, 1.0, 0.0, 0.0
        if kind == "Pulse":
            sc = 1.0 + amt * 0.4 * pos01
        elif kind == "Spin":
            rot = (t * 360.0 * amt) if lvl is None else lvl * 360.0 * amt
        elif kind == "Float":
            dy = wave * amt * bh * 0.15
        elif kind == "Shake":
            k = (lvl if lvl is not None else 1.0) * amt * min(bw, bh) * 0.05
            dx = math.sin(frame.time * 53.0) * k
            dy = math.cos(frame.time * 41.0) * k
        elif kind == "Swing":
            rot = wave * amt * 30.0
        self.anim_t1.xy = (bw / 2 + dx, bh / 2 + dy)
        self.anim_rot.angle = -rot
        self.anim_scale.x = self.anim_scale.y = sc
        kx = math.tan(math.radians(v.get("Image Source/Layout/Skew X", 0.0) * 45.0))
        ky = math.tan(math.radians(v.get("Image Source/Layout/Skew Y", 0.0) * 45.0))
        m = Matrix()
        m.set(flat=[1, ky, 0, 0, kx, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1])
        self.anim_skew.matrix = m
        self.anim_t2.xy = (-bw / 2, -bh / 2)
        if kind == "Ken Burns":
            z = 1.0 / (1.0 + amt * 0.5 * pos01)
            pan = (1 - z) * (0.5 + 0.5 * math.sin(t * 0.7))
            return (pan, pan * 0.6, pan + z, pan * 0.6 + z)
        return None

    def render(self, frame):
        v = self.values
        tex = self._pick_texture(frame)
        size = v[self.P + "Size"] * min(frame.w, frame.h)
        tw, th = max(1, tex.width), max(1, tex.height)
        aspect = tw / float(th)
        keep, crop = v[self.P + "Keep Aspect Ratio"], v[self.P + "Crop to Fit"]
        fit = v.get("Image Source/Layout/Fit Mode", "Legacy")
        if fit == "Contain":
            keep, crop = True, False
        elif fit == "Cover":
            keep, crop = True, True
        elif fit == "Stretch":
            keep, crop = False, False
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
        kb = self._animate(frame, bw, bh)
        if kb is not None:            # Ken Burns: zoom / pan inside the crop window
            cu, cv = u1 - u0, v1 - v0
            u0, v0, u1, v1 = u0 + cu * kb[0], v0 + cv * kb[1], u0 + cu * kb[2], v0 + cv * kb[3]
        if v.get("Image Source/Layout/Flip Horizontal", False):
            u0, u1 = u1, u0
        if v.get("Image Source/Layout/Flip Vertical", False):
            v0, v1 = v1, v0
        shape = v[self.P + "Shape"]
        radius = float(v.get(self.P + "Corner Radius", 0.0))
        key = (id(tex), shape, round(bw, 1), round(bh, 1), round(u0, 4), round(v0, 4), round(u1, 4), round(v1, 4),
               round(radius, 3))
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
            self._pts = np.asarray(pts, np.float32) * [bw, bh]
        self._apply_uniforms(tex, frame)
        self._border(frame)
        return bw, bh

    def _border(self, frame):
        v = self.values
        B_ = "Image Source/Border/"
        width = v.get(B_ + "Border Width", 0.0) * min(frame.w, frame.h)
        if width < 0.3 or self._pts is None or len(self._pts) < 3:
            self.border.set(np.zeros((0, 4, 2), np.float32), np.zeros((0, 4), np.float32))
            return
        src = v.get(B_ + "Border Pulse With", "Nothing")
        if src != "Nothing":
            from ..fx_library import react_level
            width *= 0.5 + 1.5 * react_level(frame.snapshot, frame.measure, src)
        from .geometry import thick_polyline
        q = thick_polyline(self._pts, width, closed=True)
        col = np.asarray(hex_to_rgba(v.get(B_ + "Border Color", "#C6FF00FF")), np.float32)
        col[3] *= float(v.get(self.P + "Opacity", 1.0))
        self.border.set(q, np.repeat(col[None], len(q), 0))

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


_CASE = {"UPPERCASE": str.upper, "lowercase": str.lower, "Title Case": str.title}


class TextLayer(BaseLayer):
    def build(self):
        super().build()
        self.bg_group = InstructionGroup()
        self.glow_group = InstructionGroup()
        self.shadow_group = InstructionGroup()
        self.outline_group = InstructionGroup()
        self.fill_group = InstructionGroup()
        self.bg_color = Color(0, 0, 0, 0)
        self.bg_rect = RoundedRectangle(pos=(0, 0), size=(0, 0), radius=[0])
        self.bg_group.add(self.bg_color)
        self.bg_group.add(self.bg_rect)
        self.glow_color = Color(0, 0, 0, 0)
        self.shadow_color = Color(0, 0, 0, .5)
        self.outline_color = Color(0, 0, 0, 1)
        self.fill_color = Color(1, 1, 1, 1)
        self.anim_scale = Scale(1, 1, 1)
        self.anim_push, self.anim_pop = PushMatrix(), PopMatrix()
        self.anim_t1, self.anim_t2, self.scroll_t = Translate(), Translate(), Translate()
        self.clip_in, self.clip_out = InstructionGroup(), InstructionGroup()
        self.scissor = None
        for i in (self.clip_in, self.anim_push, self.anim_t1, self.anim_scale, self.anim_t2,
                  self.bg_group, self.scroll_t,
                  self.glow_color, self.glow_group, self.shadow_color, self.shadow_group,
                  self.outline_color, self.outline_group, self.fill_color, self.fill_group,
                  self.anim_pop, self.clip_out):
            self.content.add(i)
        self._glyphs = {}
        self._fill_items = []
        self._layout_key = None
        self._text = None
        self._anim_start = 0.0
        self._shadow_copies = 1
        self._glow_copies = 1
        self.box = (1, 1)
        self.text_w = 1
        self.lines_n = 1
        self.current_line = 0

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
                kw["font_name"] = "Roboto"
                label = CoreLabel(**kw)
                label.refresh()
                tex = label.texture
            if len(self._glyphs) > 3000:
                self._glyphs.clear()
            self._glyphs[key] = tex
        return tex

    def _source_text(self, frame):
        v = self.values
        L = "Content/Lyrics/"
        if v.get(L + "Source", "Text Input") != "Text Input":
            from .. import lyrics
            meta = frame.metadata or {}
            path = lyrics.find_lrc(meta.get("path", ""), v.get(L + "Lyrics File", ""))
            lines = lyrics.load(path)
            if not lines:
                self.current_line = 0
                return "No synced lyrics (.lrc) found" if path == "" else "Lyrics file is empty"
            ctx = int(v.get(L + "Context Lines", 0))
            rows, _ = lyrics.window(lines, frame.position + v.get(L + "Lyrics Offset (s)", 0.0), ctx)
            self.current_line = ctx
            return "\n".join(r if r else " " for r in rows)
        self.current_line = -1
        raw = v["Content/Text/Text Input"]
        return substitute(raw, frame) if v["Content/Text/Use Metadata Variables"] else raw

    def render(self, frame):
        v = self.values
        text = self._source_text(frame)
        case = _CASE.get(v.get("Content/Text/Text Transform", "None"))
        if case is not None:
            text = case(text)
        if text != self._text:
            self._text = text
            self._anim_start = frame.time
        A = "Text Animation/"
        anim = v[A + "Animation/Animation Type"]
        duration = 0.15 + (1.0 - v[A + "Animation/Animation Speed"]) * 1.6
        stagger = v.get(A + "Animation/Stagger", 0.0) * 0.08 if anim in ("Fade", "Slide Up", "Slide Down", "Zoom") else 0.0
        elapsed = frame.time - self._anim_start
        total_dur = duration + stagger * max(0, len(text) - 1)
        progress = min(1.0, elapsed / duration) if anim != "None" else 1.0
        visible = len(text)
        if anim == "Typewriter":
            cps = max(0.5, v["Content/Text/Typewriter Speed"] * 60.0)
            visible = int(elapsed * cps)
        font_px = max(4, int(v["Typography/Font/Font Size"] * frame.h))
        weight = int(v["Typography/Font/Font Weight"])
        style = v["Typography/Font/Style"]
        bold = weight >= 600 or "Bold" in style
        italic = "Italic" in style
        outline_px = int(round(v["Appearance/Outline/Outline Width"] * font_px * 0.25))
        M = A + "Marquee/"
        marquee = bool(v.get(M + "Enable Marquee", False))
        layout_key = (text, min(visible, len(text)), font_px, bold, italic, outline_px,
                      v["Typography/Font/Font Family"], v.get("Typography/Font/Custom Font File", ""),
                      v["Typography/Font/Letter Spacing"],
                      v["Typography/Font/Line Height"], v["Typography/Font/Alignment"],
                      v["Appearance/Shadow/Shadow X"], v["Appearance/Shadow/Shadow Y"],
                      v["Appearance/Shadow/Shadow Blur"], v.get("Appearance/Glow/Glow Size", 0.0),
                      marquee, v.get(M + "Marquee Gap", 0.15), frame.w)
        if layout_key != self._layout_key:
            self._layout_key = layout_key
            self._layout(text, min(visible, len(text)), font_px, bold, italic, outline_px,
                         marquee, v.get(M + "Marquee Gap", 0.15) * frame.w)
        # colors + animation
        alpha = (min(1.0, elapsed / total_dur) if stagger else progress) if anim in (
            "Fade", "Slide Up", "Slide Down", "Zoom") else 1.0
        alpha *= self.opacity
        fill = hex_to_rgba(v["Appearance/Fill/Text Color"])
        grad = hex_to_rgba(v.get("Appearance/Fill/Gradient Color", "#C6FF00FF"))
        fmode = v.get("Appearance/Fill/Fill Mode", "Solid")
        ctx_alpha = v.get("Content/Lyrics/Context Opacity", 0.4)
        base_alpha = progress if anim in ("Fade", "Slide Up", "Slide Down", "Zoom") and not stagger else 1.0
        base_alpha *= self.opacity
        self.fill_color.rgba = [1, 1, 1, 1]
        if marquee:
            self.current_line = -1
        for col, tx, ty, idx, line in self._fill_items:
            t = tx if fmode == "Horizontal Gradient" else (ty if fmode == "Vertical Gradient" else 0.0)
            c = [fill[k] + (grad[k] - fill[k]) * t for k in range(4)] if t else fill
            a = base_alpha
            if stagger:
                a *= max(0.0, min(1.0, (elapsed - idx * stagger) / duration))
            if self.current_line >= 0 and line != self.current_line:
                a *= ctx_alpha
            col.rgba = (c[0], c[1], c[2], c[3] * a)
        sc = hex_to_rgba(v["Appearance/Shadow/Shadow Color"])
        self.shadow_color.rgba = sc[:3] + [sc[3] * alpha / max(1, self._shadow_copies) ** 0.7]
        oc = hex_to_rgba(v["Appearance/Outline/Outline/Stroke Color"])
        self.outline_color.rgba = oc[:3] + [oc[3] * alpha]
        gc = hex_to_rgba(v.get("Appearance/Glow/Glow Color", "#C6FF00AA"))
        pulse_src = v.get("Appearance/Glow/Glow Pulse With", "Nothing")
        pulse = 1.0
        if pulse_src != "Nothing":
            from ..fx_library import react_level
            pulse = 0.25 + 0.75 * react_level(frame.snapshot, frame.measure, pulse_src)
        self.glow_color.rgba = gc[:3] + [gc[3] * alpha * pulse / max(1, self._glow_copies) ** 0.6]
        bw, bh = self.box
        tw = self.text_w
        # marquee: clipped window of Marquee Width, text scrolls through it
        x_off = 0.0
        if marquee:
            bw = max(4.0, v.get(M + "Marquee Width", 0.6) * frame.w)
            period = tw + v.get(M + "Marquee Gap", 0.15) * frame.w
            x_off = -((frame.time * v.get(M + "Marquee Speed", 0.1) * frame.w) % max(period, 1.0))
        self.scroll_t.xy = (x_off, 0)
        # auto fit
        fit_w = v.get("Typography/Font/Auto Fit Width", 0.0)
        fit = min(1.0, fit_w * frame.w / max(bw, 1.0)) if fit_w > 0 else 1.0
        # background box (does not scroll)
        if v.get("Appearance/Background/Show Background", False):
            pad = v.get("Appearance/Background/Padding", 0.35) * font_px
            bc = hex_to_rgba(v.get("Appearance/Background/Background Color", "#00000080"))
            self.bg_color.rgba = bc[:3] + [bc[3] * self.opacity * (alpha if anim != "None" else 1.0)]
            self.bg_rect.pos = (-pad, -pad)
            self.bg_rect.size = (bw + pad * 2, bh + pad * 2)
            r = v.get("Appearance/Background/Corner Radius", 0.3) * min(bw + pad * 2, bh + pad * 2) / 2
            self.bg_rect.radius = [r]
        else:
            self.bg_color.a = 0
        ease = 1 - (1 - progress) ** 3
        dy = 0.0
        if anim == "Slide Up":
            dy = -(1 - ease) * frame.h * 0.06
        elif anim == "Slide Down":
            dy = (1 - ease) * frame.h * 0.06
        z = 0.6 + 0.4 * ease if anim == "Zoom" else 1.0
        self.anim_t1.xy = (bw * fit / 2, bh * fit / 2 + dy)
        self.anim_scale.x = self.anim_scale.y = z * fit
        self.anim_t2.xy = (-bw / 2, -bh / 2)
        self._marquee = marquee
        self._mbox = (bw * fit, bh * fit)
        return bw * fit, bh * fit

    def after_transform(self, t, frame):
        if not getattr(self, "_marquee", False):
            if self.scissor is not None:
                self.clip_in.clear()
                self.clip_out.clear()
                self.scissor = None
            return
        from kivy.graphics.scissor_instructions import ScissorPop, ScissorPush
        ox, oy = t["origin"]
        pvx, pvy = t["pivot"]
        sx, sy = t["scale"]
        bw, bh = self._mbox
        x = ox + pvx * (1 - sx)
        y = oy + pvy * (1 - sy)
        rect = (int(x), int(y), max(1, int(bw * sx)), max(1, int(bh * sy)))
        if self.scissor is None:
            self.scissor = ScissorPush(x=rect[0], y=rect[1], width=rect[2], height=rect[3])
            self.clip_in.add(self.scissor)
            self.clip_out.add(ScissorPop())
        else:
            self.scissor.x, self.scissor.y, self.scissor.width, self.scissor.height = rect

    def _layout(self, text, visible, font_px, bold, italic, outline_px, marquee=False, gap=0.0):
        v = self.values
        custom = v.get("Typography/Font/Custom Font File", "")
        font_name = custom if custom and os.path.isfile(custom) else resolve_font(v["Typography/Font/Font Family"])
        spacing = v["Typography/Font/Letter Spacing"] * font_px * 0.5
        line_h = font_px * 1.25 * (v["Typography/Font/Line Height"] * 2.0)
        align = v["Typography/Font/Alignment"]
        lines = text.split("\n")
        if marquee:
            lines = [" ".join(l for l in lines if l.strip()) or " "]
        measured = []
        for line in lines:
            glyphs = [(ch, self._glyph(ch, font_name, font_px, bold, italic, 0)) for ch in line]
            width = sum(t.width for _, t in glyphs) + spacing * max(0, len(glyphs) - 1)
            measured.append((glyphs, width))
        max_w = max([w for _, w in measured] + [1])
        total_h = max(line_h * len(lines), 1)
        self.box = (max_w, total_h)
        self.text_w = max_w
        self.lines_n = len(lines)
        for g in (self.glow_group, self.shadow_group, self.outline_group, self.fill_group):
            g.clear()
        self._fill_items = []
        sx = (v["Appearance/Shadow/Shadow X"] - 0.5) * font_px * 0.6
        sy = (v["Appearance/Shadow/Shadow Y"] - 0.5) * font_px * 0.6
        blur = v["Appearance/Shadow/Shadow Blur"]
        jitter = [(0, 0)] if blur < 0.02 else [
            (math.cos(a) * blur * font_px * 0.15, math.sin(a) * blur * font_px * 0.15)
            for a in np.linspace(0, 2 * math.pi, 6, endpoint=False)]
        self._shadow_copies = len(jitter)
        gsize = v.get("Appearance/Glow/Glow Size", 0.0) * font_px * 0.35
        glow = [] if gsize < 0.5 else [
            (math.cos(a) * r, math.sin(a) * r)
            for r in (gsize * 0.5, gsize) for a in np.linspace(0, 2 * math.pi, 8, endpoint=False)]
        self._glow_copies = max(1, len(glow))
        copies = [0.0, max_w + gap] if marquee else [0.0]
        for base_x in copies:
            drawn = 0
            idx = 0
            for li, (glyphs, width) in enumerate(measured):
                y = total_h - (li + 1) * line_h + (line_h - font_px * 1.2) / 2
                extra = 0.0
                spaces = sum(1 for ch, _ in glyphs if ch == " ")
                if marquee or align == "Left":
                    x = 0.0
                elif align == "Right":
                    x = max_w - width
                elif align == "Justify" and li < len(measured) - 1 and spaces:
                    x, extra = 0.0, (max_w - width) / spaces
                else:
                    x = (max_w - width) / 2
                x += base_x
                for ch, tex in glyphs:
                    if drawn >= visible:
                        break
                    drawn += 1
                    if ch.strip():
                        for gx, gy in glow:
                            self.glow_group.add(Rectangle(texture=tex, size=tex.size, pos=(x + gx, y + gy)))
                        for jx, jy in jitter:
                            self.shadow_group.add(Rectangle(texture=tex, size=tex.size,
                                                            pos=(x + sx + jx, y + sy + jy)))
                        if outline_px:
                            otex = self._glyph(ch, font_name, font_px, bold, italic, outline_px)
                            self.outline_group.add(Rectangle(
                                texture=otex, size=otex.size,
                                pos=(x - (otex.width - tex.width) / 2, y - (otex.height - tex.height) / 2)))
                        col = Color(1, 1, 1, 1)
                        self.fill_group.add(col)
                        self.fill_group.add(Rectangle(texture=tex, size=tex.size, pos=(x, y)))
                        tx = min(1.0, max(0.0, (x - base_x + tex.width / 2) / max(max_w, 1)))
                        ty = 1.0 - min(1.0, max(0.0, (y + tex.height / 2) / max(total_h, 1)))
                        self._fill_items.append((col, tx, ty, idx, li))
                        idx += 1
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
def _hsv_rgba(h, s, v, a):
    h = (np.asarray(h, np.float32) % 1.0) * 6.0
    i = np.floor(h).astype(np.int32) % 6
    f = h - np.floor(h)
    p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    vv = np.full_like(f, v)
    pp, qq, tt = np.broadcast_to(p, f.shape), np.broadcast_to(q, f.shape), np.broadcast_to(t, f.shape)
    r = np.choose(i, [vv, qq, pp, pp, tt, vv])
    g = np.choose(i, [tt, vv, vv, qq, pp, pp])
    b = np.choose(i, [pp, pp, tt, vv, vv, qq])
    return np.stack([r, g, b, np.full_like(f, a)], 1).astype(np.float32)


def _quads_between(b0, b1, half_w):
    return np.stack([b0 - half_w, b0 + half_w, b1 + half_w, b1 - half_w], 1)


class BarsLayer(BaseLayer):
    def build(self):
        super().build()
        self.batch = QuadBatch(self.content)
        self.values_now = np.zeros(0, np.float32)
        self.peaks = np.zeros(0, np.float32)
        self.peak_hold = np.zeros(0, np.float32)

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
        Y = "Bar Style/"
        mode = v.get(Y + "Color/Color Mode", "Segment Gradient")
        bars = []                         # (b0, b1, half_w, n_dir, col_bottom, col_top, h, mirrored)
        offset_out = np.zeros(count, np.float32)
        offset_in = np.zeros(count, np.float32)
        main_seg = None
        base_line = None
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
            if base_line is None:
                base_line = (base, closed)
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
            t = (np.arange(count) / max(1, count - 1) + v[B + "Color Blend Offset"]) % 1.0001
            c_from, c_to = hex_to_rgba(v[S + "Color From"]), hex_to_rgba(v[S + "Color To"])
            if mode == "Solid":
                cols = np.repeat(np.asarray(c_from, np.float32)[None], count, 0)
                top = cols
            elif mode == "Rainbow":
                hue = t + frame.time * v.get(Y + "Color/Rainbow Speed", 0.1)
                sat = v.get(Y + "Color/Rainbow Saturation", 0.9)
                cols = _hsv_rgba(hue, sat, 1.0, c_from[3])
                top = _hsv_rgba(hue + 0.06, sat * 0.8, 1.0, c_from[3])
            elif mode == "By Amplitude":
                amp = np.clip(self.values_now, 0, 1)
                cols = _lerp_colors(c_from, c_to, amp * 0.6)
                top = _lerp_colors(c_from, c_to, amp)
            else:
                cols = _lerp_colors(c_from, c_to, t)
                top = _lerp_colors(c_from, c_to, np.clip(t + 0.15, 0, 1))
            bars.append((b0, b1, half_w, n_dir, cols, top, h, False))
            if main_seg is None:
                main_seg = (base, n_dir, half_w, start)
            if v[S + "Mirror"]:
                istart = np.zeros(count, np.float32) if own_path else offset_in
                m0 = base - n_dir * istart[:, None]
                m1 = base - n_dir * (istart + h)[:, None]
                bars.append((m0, m1, half_w, -n_dir, cols, top, h, True))
                if not own_path:
                    offset_in = offset_in + h
            if not own_path:
                offset_out = offset_out + h
        corners, colors = self._style(frame, bars, main_seg, base_line, offset_out, m)
        colors = [c if c.ndim == 3 else np.repeat(c[:, None, :], 4, axis=1) for c in colors]
        if corners:
            self.batch.set(np.concatenate(corners), np.concatenate(colors))
        else:
            self.batch.set(np.zeros((0, 4, 2), np.float32), np.zeros((0, 4), np.float32))
        return frame.w, frame.h

    def _style(self, frame, bars, main_seg, base_line, total_h, m):
        """Turns bar records into quads: glow, reflection, LED cells / solid bars, round caps,
        outline, peak markers and baseline (back to front)."""
        v = self.values
        Y = "Bar Style/"
        out_c, out_k = [], []
        glow = v.get(Y + "Extras/Glow Amount", 0.0)
        refl = v.get(Y + "Extras/Reflection Opacity", 0.0)
        led = v.get(Y + "Shape/LED Cell Size", 0.0) * m
        gap = v.get(Y + "Shape/LED Gap", 0.0) * m
        round_cap = v.get(Y + "Shape/Cap Style", "Flat") == "Round"
        outline = v.get(Y + "Extras/Outline Width", 0.0) * m

        def four(bottom, top):
            return np.stack([bottom, bottom, top, top], 1)

        if glow > 0:
            gw = v.get(Y + "Extras/Glow Width", 2.5)
            for b0, b1, hw, nd, cb, ct, h, _ in bars:
                ext = hw * (gw - 1.0)
                g0 = b0 - nd * (np.linalg.norm(hw, axis=1) * (gw - 1))[:, None]
                g1 = b1 + nd * (np.linalg.norm(hw, axis=1) * (gw - 1))[:, None]
                cb2, ct2 = cb.copy(), ct.copy()
                cb2[:, 3] *= glow * 0.35
                ct2[:, 3] *= glow * 0.12
                out_c.append(_quads_between(g0, g1, hw + ext))
                out_k.append(four(cb2, ct2))
        if refl > 0:
            for b0, b1, hw, nd, cb, ct, h, mirrored in bars:
                if mirrored:
                    continue
                r0 = b0
                r1 = b0 - nd * (h * 0.6)[:, None]
                cb2, ct2 = cb.copy(), ct.copy()
                cb2[:, 3] *= refl * 0.5
                ct2[:, 3] = 0.0
                out_c.append(_quads_between(r0, r1, hw))
                out_k.append(four(cb2, ct2))
        for b0, b1, hw, nd, cb, ct, h, _ in bars:
            if led > 0.5:
                pitch = led + gap
                hmax = float(h.max()) if len(h) else 0.0
                cells = int(min(64, math.ceil(hmax / pitch))) if hmax > 0 else 0
                for j in range(cells):
                    s0 = j * pitch
                    live = h > s0
                    if not live.any():
                        break
                    s1 = np.minimum(s0 + led, h)
                    c0 = b0[live] + nd[live] * s0
                    c1 = b0[live] + nd[live] * s1[live][:, None]
                    f = np.clip(s0 / max(hmax, 1e-6), 0, 1)
                    col = cb[live] + (ct[live] - cb[live]) * f
                    out_c.append(_quads_between(c0, c1, hw[live]))
                    out_k.append(col)
            else:
                out_c.append(_quads_between(b0, b1, hw))
                out_k.append(four(cb, ct))
            if round_cap and led <= 0.5:
                r = np.linalg.norm(hw, axis=1)
                tdir = hw / np.maximum(r, 1e-6)[:, None]
                steps = 6
                for k in range(steps):
                    a0, a1 = math.pi * k / steps, math.pi * (k + 1) / steps
                    p0 = b1 + (tdir * math.cos(a0) + nd * math.sin(a0)) * r[:, None]
                    p1 = b1 + (tdir * math.cos(a1) + nd * math.sin(a1)) * r[:, None]
                    out_c.append(np.stack([b1, p0, p1, b1], 1))
                    out_k.append(np.repeat(ct[:, None, :], 4, 1))
            if outline > 0.2:
                oc = np.asarray(hex_to_rgba(v.get(Y + "Extras/Outline Color", "#FFFFFFCC")), np.float32)
                q = _quads_between(b0, b1, hw)
                for e in range(4):
                    a, b = q[:, e], q[:, (e + 1) % 4]
                    d = b - a
                    ln = np.maximum(np.linalg.norm(d, axis=1), 1e-6)[:, None]
                    nrm = np.stack([-d[:, 1], d[:, 0]], 1) / ln * (outline / 2)
                    out_c.append(np.stack([a - nrm, b - nrm, b + nrm, a + nrm], 1))
                    out_k.append(np.repeat(oc[None], len(a), 0))
        if v.get(Y + "Peaks/Show Peaks", False) and main_seg is not None:
            base, nd, hw, start = main_seg
            n = len(total_h)
            if self.peaks.shape[0] != n:
                self.peaks = np.zeros(n, np.float32)
                self.peak_hold = np.zeros(n, np.float32)
            cur = total_h - start
            up = cur >= self.peaks
            self.peaks = np.where(up, cur, self.peaks)
            self.peak_hold = np.where(up, v.get(Y + "Peaks/Peak Hold (s)", 0.4), self.peak_hold - frame.dt)
            falling = (~up) & (self.peak_hold <= 0)
            fall = v.get(Y + "Peaks/Peak Fall Speed", 0.5) * m * v["Bars Setup/Bars/Max Height Scale"] * frame.dt
            self.peaks = np.where(falling, np.maximum(cur, self.peaks - fall), self.peaks)
            th = max(1.0, v.get(Y + "Peaks/Peak Thickness", 0.006) * m)
            p0 = base + nd * (start + self.peaks + th * 0.5)[:, None]
            p1 = p0 + nd * th
            pc = np.asarray(hex_to_rgba(v.get(Y + "Peaks/Peak Color", "#FFFFFFFF")), np.float32)
            out_c.append(_quads_between(p0, p1, hw))
            out_k.append(np.repeat(pc[None], n, 0))
        bw = v.get(Y + "Extras/Baseline Width", 0.0) * m
        if bw > 0.2 and base_line is not None:
            from .geometry import thick_polyline
            pts, closed = base_line
            q = thick_polyline(np.asarray(pts, np.float32), bw, closed=bool(closed))
            if len(q):
                bc = np.asarray(hex_to_rgba(v.get(Y + "Extras/Baseline Color", "#C6FF0099")), np.float32)
                out_c.append(q)
                out_k.append(np.repeat(bc[None], len(q), 0))
        return out_c, out_k


# ------------------------------------------------------------------ PARTICLES
class ParticlesLayer(BaseLayer):
    FIELDS = ("x", "y", "z", "vx", "vy", "vz", "age", "life", "rand", "wave_f", "wave_m", "phase", "rot", "spin")

    def build(self):
        super().build()
        self.batch = QuadBatch(self.content, textures.soft_dot())
        self.p = {k: np.zeros(0, np.float32) for k in self.FIELDS}
        self._spawn_acc = 0.0
        self.count_warning = False

    def _spawn(self, n, frame, boost=1.0):
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
        speed = v[M + "Speed"] * m * 1.2 * (1.0 + (rnd(n) * 2 - 1) * v[M + "Speed Random"]) * boost
        cz = math.cos(az)
        vx, vy = np.cos(ang) * speed * cz, np.sin(ang) * speed * cz
        vz = np.full(n, math.sin(az)) * speed / m
        life = np.maximum(0.05, v[M + "Lifetime"] * 10.0 * (0.75 + rnd(n) * 0.5))
        wf = v[W + "Side Sine Wave Freq"] * 10.0 * (1 + (rnd(n) * 2 - 1) * v[W + "Side Sine Wave Freq Random"])
        wm = v[W + "Side Sine Wave Mag"] * m * 0.3 * (1 + (rnd(n) * 2 - 1) * v[W + "Side Sine Wave Mag Random"])
        R = "Physics & Bursts/Rotation/"
        spin = math.radians(v.get(R + "Rotation Speed (deg/s)", 0.0)) * (
            1.0 + (rnd(n) * 2 - 1) * v.get(R + "Rotation Random", 0.0) * 2)
        new = dict(x=x, y=y, z=z, vx=vx, vy=vy, vz=vz, age=np.zeros(n), life=life,
                   rand=rnd(n), wave_f=wf, wave_m=wm, phase=rnd(n) * 2 * math.pi,
                   rot=rnd(n) * 2 * math.pi, spin=spin)
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
        PB = "Physics & Bursts/"
        snap = frame.snapshot
        burst_on = v.get(PB + "Burst/Burst On", "Nothing")
        if burst_on != "Nothing":
            key = {"Beat": "beat", "Downbeat": "downbeat", "Kick": "kick_hit", "Snare": "snare_hit",
                   "HiHat": "hihat_hit"}[burst_on]
            if snap.get(key):
                nb = int(v.get(PB + "Burst/Burst Count", 40))     # may overshoot the limit briefly; trimmed below
                if nb:
                    self._spawn(nb, frame, boost=1.0 + v.get(PB + "Burst/Burst Speed Boost", 1.5))
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
        F = PB + "Forces/"
        gust_src = v.get(F + "Wind Gust With", "Nothing")
        gust = 1.0
        if gust_src != "Nothing":
            from ..fx_library import react_level
            gust = react_level(snap, frame.measure, gust_src) * 2.0
        p["vx"] += v.get(F + "Wind X", 0.0) * m * gust * sdt
        p["vy"] += v.get(F + "Wind Y", 0.0) * m * gust * sdt
        turb = v.get(F + "Turbulence", 0.0)
        if turb > 0 and len(p["x"]):
            k = 6.0 / m
            tt = frame.time * 0.7
            p["vx"] += np.sin(p["y"] * k + tt + p["phase"]) * turb * m * sdt
            p["vy"] += np.cos(p["x"] * k - tt + p["phase"]) * turb * m * sdt
        att = v.get(F + "Attractor Strength", 0.0)
        if att != 0 and len(p["x"]):
            ax_, ay_ = v.get(F + "Attractor X", 0.5) * frame.w, v.get(F + "Attractor Y", 0.5) * frame.h
            dx, dy = ax_ - p["x"], ay_ - p["y"]
            d = np.maximum(np.hypot(dx, dy), m * 0.02)
            p["vx"] += dx / d * att * m * sdt
            p["vy"] += dy / d * att * m * sdt
        drag = v.get(F + "Drag", 0.0)
        if drag > 0:
            f = math.exp(-drag * sdt)
            p["vx"] *= f
            p["vy"] *= f
        p["rot"] += p["spin"] * sdt
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
            ux, uy = np.cos(p["rot"]), np.sin(p["rot"])
            if not p["spin"].any() and not v.get("Physics & Bursts/Rotation/Rotation Random", 0.0):
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


class GroupLayer(BaseLayer):
    """Wraps member layers: they render inside this layer's transform / blend / opacity."""

    def build(self):
        super().build()
        self.audio_scale = Scale(1, 1, 1)
        self.spin = Rotate(angle=0, axis=(0, 0, 1))
        self.t1, self.t2 = Translate(), Translate()
        self.members_group = InstructionGroup()
        for i in (self.t1, self.spin, self.audio_scale, self.t2, self.members_group):
            self.content.add(i)
        self.members = []
        self._angle = 0.0

    def set_members(self, renderers):
        self.members_group.clear()
        self.members = list(renderers)
        for r in self.members:
            self.members_group.add(r.group)

    def render(self, frame):
        v = self.values
        A = "Group/Audio/"
        src = v.get(A + "Scale With", "Nothing")
        k = 1.0
        if src != "Nothing":
            from ..fx_library import react_level
            k = 1.0 + react_level(frame.snapshot, frame.measure, src) * v.get(A + "Scale Amount", 0.2)
        self._angle = (self._angle + v.get(A + "Spin (deg/s)", 0.0) * frame.dt) % 360.0
        self.t1.xy = (frame.w / 2, frame.h / 2)
        self.audio_scale.x = self.audio_scale.y = k
        self.spin.angle = -self._angle
        self.t2.xy = (-frame.w / 2, -frame.h / 2)
        return frame.w, frame.h

    def update(self, frame):
        super().update(frame)
        for r in self.members:
            r.parent_opacity = self.opacity
            try:
                r.update(frame)
            except Exception as exc:
                from kivy.logger import Logger
                Logger.warning("Vooc: %s layer (in group) failed: %s", r.layer.module, exc)


RENDERERS = {"GROUP": GroupLayer, "IMAGE": ImageLayer, "TEXT": TextLayer, "BARS": BarsLayer, "PARTICLES": ParticlesLayer}
