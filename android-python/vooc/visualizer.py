"""Vooc spectrum renderer.

Draws at display rate (60 fps) with persistent canvas instructions: the
instruction pool is rebuilt only when the style / bar count / size changes and
every frame just updates positions, sizes and texture coordinates.

Data comes from a music-synced :class:`SpectrumTimeline` sampled at the
current playback position (see ``spectrum_source``). Live PCM analysis
(``set_spectrum``) is still accepted for backends that stream bands.
"""
import math

from kivy.clock import Clock
from kivy.core.image import Image as CoreImage
from kivy.core.text import Label as CoreLabel
from kivy.graphics import (Color, Ellipse, InstructionGroup, Line, Mesh, PopMatrix, PushMatrix,
                           Rectangle, Rotate, Scale, Translate)
from kivy.graphics.texture import Texture
from kivy.metrics import dp
from kivy.uix.widget import Widget

from .particle_system import ParticleSystem

STYLES = ["Bars", "Mirror", "Wave", "Radial", "LED", "Orbit"]
_STYLE_ALIASES = {"Mirror Bars": "Mirror", "Line": "Wave", "Dots": "LED"}

# gradient stops bottom -> top
PALETTES = {
    "Acid":   ["#2E5E00", "#7CC400", "#C6FF00", "#F4FFB0"],
    "Neon":   ["#3A0CA3", "#7209B7", "#F72585", "#FFB3E6"],
    "Sunset": ["#5F0F40", "#E36414", "#FB8B24", "#FFE066"],
    "Ocean":  ["#03045E", "#0077B6", "#00B4D8", "#CAF0F8"],
    "Fire":   ["#370617", "#D00000", "#FF7B00", "#FFF3B0"],
    "Aurora": ["#0B3D2E", "#00C896", "#5BFFB0", "#B8F2FF"],
    "Mono":   ["#3A3A3A", "#8A8A8A", "#D9D9D9", "#FFFFFF"],
}
_PALETTE_ALIASES = {"Amber": "Sunset", "Cyan": "Ocean", "Purple": "Neon", "Green": "Acid", "White": "Mono"}

DEFAULTS = {
    "style": "Bars", "palette": "Acid", "bar_count": 48, "sensitivity": 1.0,
    "smoothing": 0.55, "height": 1.0, "gap": 0.35, "glow": 0.5, "peak_fall": 1.0,
    "peaks": True, "beat_pulse": True, "particles": False, "reflection": True, "spin": True,
}


def hex_rgba(value, fallback=(1, 1, 1, 1)):
    try:
        raw = str(value).strip().lstrip("#")
        if len(raw) == 6:
            raw += "FF"
        if len(raw) != 8:
            return fallback
        return tuple(int(raw[i:i + 2], 16) / 255.0 for i in (0, 2, 4, 6))
    except (TypeError, ValueError):
        return fallback


def palette_stops(name):
    name = _PALETTE_ALIASES.get(name, name)
    return [hex_rgba(c) for c in PALETTES.get(name, PALETTES["Acid"])]


def lerp_stops(stops, t):
    t = max(0.0, min(1.0, t)) * (len(stops) - 1)
    i = min(len(stops) - 2, int(t))
    f = t - i
    a, b = stops[i], stops[i + 1]
    return tuple(a[k] * (1 - f) + b[k] * f for k in range(4))


def gradient_texture(stops, size=128):
    tex = Texture.create(size=(1, size), colorfmt="rgba")
    buf = bytearray()
    for i in range(size):
        c = lerp_stops(stops, i / (size - 1))
        buf += bytes(int(max(0, min(1, v)) * 255) for v in c)
    tex.blit_buffer(bytes(buf), colorfmt="rgba", bufferfmt="ubyte")
    tex.mag_filter = "linear"
    tex.min_filter = "linear"
    return tex


def fade_texture(color, size=64):
    """Vertical alpha fade of one colour (bottom opaque -> top transparent)."""
    tex = Texture.create(size=(1, size), colorfmt="rgba")
    buf = bytearray()
    for i in range(size):
        a = (1 - i / (size - 1)) ** 1.6
        buf += bytes([int(color[0] * 255), int(color[1] * 255), int(color[2] * 255), int(a * 255)])
    tex.blit_buffer(bytes(buf), colorfmt="rgba", bufferfmt="ubyte")
    tex.mag_filter = "linear"
    return tex


class Spectrum(Widget):
    def __init__(self, state, clock_source=None, **kw):
        super().__init__(**kw)
        self.state = state
        self.clock_source = clock_source or (lambda: (0.0, False))
        self.timeline = None
        self.live_bands = None
        self.values = []
        self.peaks = []
        self.beat_pulse = 0.0
        self.level = 0.0
        self.phase = 0.0
        self._last_t = None
        self._sig = None
        self._tex = None
        self._tex_name = None
        self._layer_sig = None
        self._text_cache = {}
        self._image_cache = {}
        self.particle_system = ParticleSystem()
        self.smoothed = []  # kept for older callers
        with self.canvas.before:
            self._bg_color = Color(0, 0, 0, 1)
            self._bg = Rectangle()
            self._vig_color = Color(1, 1, 1, 1)
            self._vig = Rectangle()
            self._flash_color = Color(1, 1, 1, 0)
            self._flash = Rectangle()
        self._group = InstructionGroup()
        self._parts = InstructionGroup()
        self._layers = InstructionGroup()
        self.canvas.add(PushMatrix())
        self._translate = Translate(0, 0)
        self._rot = Rotate(angle=0, origin=(0, 0))
        self._scale = Scale(1, 1, 1, origin=(0, 0))
        self.canvas.add(self._translate)
        self.canvas.add(self._rot)
        self.canvas.add(self._scale)
        self._opacity_color = None
        self.canvas.add(self._group)
        self.canvas.add(PopMatrix())
        self.canvas.add(self._parts)
        self.canvas.add(self._layers)
        self.bind(pos=self._invalidate, size=self._invalidate)
        self._ev = Clock.schedule_interval(self.frame, 0)

    # ------------------------------------------------------------- settings
    def cfg(self, key):
        v = getattr(self.state, "visualizer", {}).get(key)
        return DEFAULTS[key] if v is None else v

    def style(self):
        s = self.cfg("style")
        s = _STYLE_ALIASES.get(s, s)
        return s if s in STYLES else "Bars"

    def _prop(self, path, default=None):
        return getattr(self.state, "properties", {}).get(path, default)

    def set_timeline(self, timeline):
        self.timeline = timeline

    def set_spectrum(self, bands, beat=False):
        """Live band input (0..1) from a streaming analyser."""
        self.live_bands = list(bands) if bands else None
        if beat:
            self.beat_pulse = 1.0

    def refresh(self, *_):
        """Settings changed: rebuild on next frame."""
        self._sig = None
        self._layer_sig = None

    def _invalidate(self, *_):
        self._sig = None
        self._layer_sig = None

    # ------------------------------------------------------------- frame
    def _targets(self, count, dt):
        pos, playing = self.clock_source()
        bands = None
        rms = 0.0
        if playing and self.timeline is not None:
            bands, rms = self.timeline.sample(pos)
            if self._last_t is not None and self.timeline.beat_between(self._last_t, pos):
                self.beat_pulse = 1.0
            self._last_t = pos
        elif playing and self.live_bands:
            bands = self.live_bands
            rms = sum(bands) / len(bands)
        else:
            self._last_t = pos if playing else None
        if bands is None and playing:
            # music is playing but the spectrum for this point is not decoded
            # (yet): animated groove at ~120 bpm so it never looks frozen
            t = self.phase
            kick = math.exp(-((t * 2.0) % 1.0) * 6.0)
            bands = [min(1.0, (0.62 - 0.38 * i / 63.0) * (0.55 + 0.45 * math.sin(i * 0.37 + t * 3.1)) *
                         (0.65 + 0.35 * math.sin(i * 0.13 - t * 1.7)) + kick * max(0.0, 0.55 - i / 40.0))
                     for i in range(64)]
            rms = 0.25 + 0.3 * kick
            if kick > 0.97:
                self.beat_pulse = max(self.beat_pulse, 0.6)
        elif bands is None:
            # idle: gentle breathing wave so the screen never looks dead
            t = self.phase
            amp = 0.10
            bands = [amp * (0.55 + 0.45 * math.sin(i * 0.33 + t * 1.7)) *
                     (0.6 + 0.4 * math.sin(i * 0.11 - t * 0.9)) for i in range(64)]
        n = len(bands)
        out = []
        sens = float(self.cfg("sensitivity"))
        for i in range(count):
            a = i * n / count
            b = (i + 1) * n / count
            lo_i, hi_i = int(a), max(int(a) + 1, int(math.ceil(b)))
            seg = bands[lo_i:min(n, hi_i)] or [0.0]
            v = max(seg)
            # contrast curve: quiet parts low, loud parts pop
            v = max(0.0, (v - 0.12) / 0.88) ** 1.35 * sens
            out.append(min(1.0, v))
        self.level = self.level * 0.85 + (rms if bands else 0) * 0.15
        return out

    def frame(self, dt):
        dt = max(0.001, min(0.1, dt))
        self.phase += dt
        if self.width < 4 or self.height < 4:
            return
        style = self.style()
        count = int(max(8, min(128, float(self.cfg("bar_count")))))
        if style in ("LED",):
            count = min(count, 40)
        palette = self.cfg("palette")
        sig = (style, count, palette, round(self.width), round(self.height), round(self.x), round(self.y),
               bool(self.cfg("reflection")), round(float(self.cfg("glow")), 2), bool(self.cfg("peaks")))
        if sig != self._sig:
            self._build(style, count, palette)
            self._sig = sig
        targets = self._targets(count, dt)
        if len(self.values) != count:
            self.values = [0.0] * count
            self.peaks = [0.0] * count
        smooth = max(0.0, min(0.95, float(self.cfg("smoothing"))))
        release = (1.0 - smooth) * 14.0 + 1.5
        fall = float(self.cfg("peak_fall")) * 0.9
        for i, tv in enumerate(targets):
            v = self.values[i]
            if tv > v:
                v += (tv - v) * min(1.0, dt * 38.0)
            else:
                v += (tv - v) * min(1.0, dt * release)
            self.values[i] = v
            p = self.peaks[i] - fall * dt
            self.peaks[i] = v if v > p else max(0.0, p)
        self.smoothed = self.values
        self.beat_pulse = max(0.0, self.beat_pulse - dt * 3.2)
        self._update_background()
        self._update_transform()
        getattr(self, "_update_" + style.lower())()
        self._update_particles(dt)
        self._update_layers()

    # ------------------------------------------------------------- background / layer transform
    def _update_background(self):
        theme_bg = None
        try:
            from .state import THEMES
            theme_bg = hex_rgba(THEMES.get(self.state.scene.get("theme", ""), "#070906"))
        except Exception:
            pass
        custom = self._prop("COMPOSITION.Render Settings.Canvas.Background Color")
        if custom and str(custom).upper() not in ("#070906FF",):
            theme_bg = hex_rgba(custom, theme_bg or (0, 0, 0, 1))
        bg = theme_bg or (0.03, 0.035, 0.03, 1)
        self._bg_color.rgba = bg
        self._bg.pos, self._bg.size = self.pos, self.size
        accent = lerp_stops(palette_stops(self.cfg("palette")), 0.6)
        if self._tex_name != ("vig", self.cfg("palette")):
            self._vig.texture = fade_texture(accent)
            self._tex_name = ("vig", self.cfg("palette"))
        self._vig_color.rgba = (1, 1, 1, 0.16 + 0.22 * self.level)
        self._vig.pos, self._vig.size = self.pos, (self.width, self.height * 0.75)
        pulse = self.beat_pulse if self.cfg("beat_pulse") else 0.0
        self._flash_color.rgba = (accent[0], accent[1], accent[2], 0.10 * pulse)
        self._flash.pos, self._flash.size = self.pos, self.size

    def _spectrum_layer(self):
        for layer in getattr(self.state, "layers", []):
            if layer.get("type") == "spectrum":
                return layer
        return None

    def _update_transform(self):
        layer = self._spectrum_layer() or {}
        visible = layer.get("visible", True)
        dx = (float(layer.get("x", 50)) - 50) / 100.0 * self.width
        dy = (float(layer.get("y", 50)) - 50) / 100.0 * self.height if "y" in layer else 0
        if layer.get("y") == 72:   # legacy default position of the spectrum layer
            dy = 0
        pulse = 1.0 + (0.035 * self.beat_pulse if self.cfg("beat_pulse") else 0.0)
        s = float(layer.get("scale", 1.0)) * pulse if visible else 0.0
        cx, cy = self.center
        self._translate.xy = (dx, dy)
        self._rot.origin = (cx, cy)
        self._rot.angle = float(layer.get("rotation", 0))
        self._scale.origin = (cx, cy)
        self._scale.x = self._scale.y = s
        if self._opacity_color is not None:
            self._opacity_color.a = max(0.0, min(1.0, float(layer.get("opacity", 1.0))))

    # ------------------------------------------------------------- pool building
    def _build(self, style, count, palette):
        g = self._group
        g.clear()
        stops = palette_stops(palette)
        self._stops = stops
        self._tex = gradient_texture(stops)
        self._opacity_color = None
        self._items = []
        self._glows = []
        self._refl = []
        self._caps = []
        glow = float(self.cfg("glow"))
        if style in ("Bars", "Mirror"):
            self._glow_color = Color(1, 1, 1, 0.22 * glow)
            g.add(self._glow_color)
            if glow > 0.01:
                for _ in range(count):
                    r = Rectangle(texture=self._tex)
                    self._glows.append(r)
                    g.add(r)
            if self.cfg("reflection") and style == "Bars":
                g.add(Color(1, 1, 1, 0.18))
                for _ in range(count):
                    r = Rectangle(texture=self._tex)
                    self._refl.append(r)
                    g.add(r)
            self._opacity_color = Color(1, 1, 1, 1)
            g.add(self._opacity_color)
            for _ in range(count * (2 if style == "Mirror" else 1)):
                r = Rectangle(texture=self._tex)
                self._items.append(r)
                g.add(r)
            if self.cfg("peaks"):
                top = stops[-1]
                g.add(Color(top[0], top[1], top[2], 0.95))
                for _ in range(count * (2 if style == "Mirror" else 1)):
                    r = Rectangle()
                    self._caps.append(r)
                    g.add(r)
        elif style == "Wave":
            self._mesh = Mesh(mode="triangle_strip", texture=self._tex)
            self._mesh_b = Mesh(mode="triangle_strip", texture=self._tex)
            g.add(Color(1, 1, 1, 0.55))
            g.add(self._mesh)
            g.add(Color(1, 1, 1, 0.30))
            g.add(self._mesh_b)
            top = stops[-1]
            self._glow_color = Color(top[0], top[1], top[2], 0.25 * glow)
            g.add(self._glow_color)
            self._line_glow = Line(width=dp(5))
            g.add(self._line_glow)
            g.add(Color(top[0], top[1], top[2], 1))
            self._line = Line(width=dp(1.8))
            g.add(self._line)
            self._line_b = Line(width=dp(1.2))
            g.add(Color(top[0], top[1], top[2], 0.6))
            g.add(self._line_b)
        elif style == "Radial":
            self._ring_color = Color(*stops[2])
            self._colors = []
            for _ in range(count):
                c = Color(1, 1, 1, 1)
                ln = Line(width=dp(2), cap="round")
                self._colors.append(c)
                self._items.append(ln)
                g.add(c)
                g.add(ln)
            g.add(self._ring_color)
            self._ring = Line(width=dp(1.6))
            g.add(self._ring)
            self._core_color = Color(*stops[2])
            g.add(self._core_color)
            self._core = Ellipse()
            g.add(self._core)
        elif style == "LED":
            dim = stops[1]
            self._cols_bg = []
            g.add(Color(dim[0], dim[1], dim[2], 0.10))
            for _ in range(count):
                r = Rectangle()
                self._cols_bg.append(r)
                g.add(r)
            self._opacity_color = Color(1, 1, 1, 1)
            g.add(self._opacity_color)
            self._rows = 16
            for _ in range(count * self._rows):
                r = Rectangle(texture=self._tex)
                self._items.append(r)
                g.add(r)
        elif style == "Orbit":
            self._mesh = Mesh(mode="triangle_fan", texture=self._tex)
            g.add(Color(1, 1, 1, 0.75))
            g.add(self._mesh)
            top = stops[-1]
            self._glow_color = Color(top[0], top[1], top[2], 0.3 * glow)
            g.add(self._glow_color)
            self._line_glow = Line(width=dp(6))
            g.add(self._line_glow)
            g.add(Color(top[0], top[1], top[2], 1))
            self._line = Line(width=dp(2))
            g.add(self._line)
            self._core_color = Color(*stops[1])
            g.add(self._core_color)
            self._core = Ellipse()
            g.add(self._core)

    # ------------------------------------------------------------- styles
    def _geometry(self, count):
        pad = dp(10)
        width = self.width - pad * 2
        gap_ratio = max(0.0, min(0.9, float(self.cfg("gap"))))
        slot = width / count
        bw = max(1.0, slot * (1.0 - gap_ratio * 0.8))
        return pad, slot, bw

    def _update_bars(self):
        count = len(self.values)
        pad, slot, bw = self._geometry(count)
        hs = float(self.cfg("height"))
        base = self.y + (self.height * 0.30 if self.cfg("reflection") else dp(10))
        avail = self.top - base - dp(12)
        cap_h = max(dp(2), bw * 0.18)
        for i, v in enumerate(self.values):
            x = self.x + pad + i * slot + (slot - bw) / 2
            h = max(dp(2), avail * min(1.0, v * hs))
            frac = min(1.0, h / max(1, avail))
            r = self._items[i]
            r.pos, r.size = (x, base), (bw, h)
            r.tex_coords = (0, 0, 1, 0, 1, frac, 0, frac)
            if self._glows:
                gw = bw * 2.4
                g = self._glows[i]
                g.pos, g.size = (x - (gw - bw) / 2, base), (gw, h * 1.04)
                g.tex_coords = r.tex_coords
            if self._refl:
                rh = min(h * 0.45, base - self.y - dp(2))
                rr = self._refl[i]
                rr.pos, rr.size = (x, base - rh - dp(2)), (bw, rh)
                rr.tex_coords = (0, frac, 1, frac, 1, 0, 0, 0)
            if self._caps:
                ph = avail * min(1.0, self.peaks[i] * hs)
                self._caps[i].pos = (x, base + max(h, ph) + dp(2))
                self._caps[i].size = (bw, cap_h)

    def _update_mirror(self):
        count = len(self.values)
        pad, slot, bw = self._geometry(count)
        hs = float(self.cfg("height"))
        cy = self.center_y
        avail = self.height / 2 - dp(10)
        cap_h = max(dp(2), bw * 0.18)
        for i, v in enumerate(self.values):
            # symmetric: bass in the middle, treble at the edges
            k = abs(i - (count - 1) / 2.0) / ((count - 1) / 2.0)
            src = int(k * (count - 1))
            v = self.values[src]
            x = self.x + pad + i * slot + (slot - bw) / 2
            h = max(dp(1.5), avail * min(1.0, v * hs))
            frac = min(1.0, h / max(1, avail))
            up, down = self._items[2 * i], self._items[2 * i + 1]
            up.pos, up.size = (x, cy), (bw, h)
            up.tex_coords = (0, 0, 1, 0, 1, frac, 0, frac)
            down.pos, down.size = (x, cy - h), (bw, h)
            down.tex_coords = (0, frac, 1, frac, 1, 0, 0, 0)
            if self._glows:
                gw = bw * 2.4
                g = self._glows[i]
                g.pos, g.size = (x - (gw - bw) / 2, cy - h * 1.04), (gw, h * 2.08)
                g.tex_coords = (0, 0, 1, 0, 1, frac, 0, frac)
            if self._caps:
                ph = avail * min(1.0, self.peaks[src] * hs)
                top = max(h, ph)
                self._caps[2 * i].pos, self._caps[2 * i].size = (x, cy + top + dp(2)), (bw, cap_h)
                self._caps[2 * i + 1].pos, self._caps[2 * i + 1].size = (x, cy - top - dp(2) - cap_h), (bw, cap_h)

    @staticmethod
    def _catmull(values, steps=3):
        n = len(values)
        out = []
        for i in range(n - 1):
            p0 = values[i - 1] if i > 0 else values[i]
            p1, p2 = values[i], values[i + 1]
            p3 = values[i + 2] if i + 2 < n else values[i + 1]
            for s in range(steps):
                t = s / steps
                t2, t3 = t * t, t * t * t
                out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
                                  (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
        out.append(values[-1])
        return out

    def _update_wave(self):
        vals = self._catmull(self.values, 3)
        n = len(vals)
        hs = float(self.cfg("height"))
        cy = self.y + self.height * 0.5
        avail = self.height * 0.46
        pts, pts_b, verts, verts_b = [], [], [], []
        for i, v in enumerate(vals):
            x = self.x + dp(6) + i * (self.width - dp(12)) / max(1, n - 1)
            h = max(0.0, min(1.0, v * hs))
            y = cy + avail * h
            yb = cy - avail * h * 0.8
            pts += [x, y]
            pts_b += [x, yb]
            verts += [x, cy, 0, 0, x, y, 0, h]
            verts_b += [x, cy, 0, 0, x, yb, 0, h * 0.8]
        idx = list(range(n * 2))
        self._mesh.vertices, self._mesh.indices = verts, idx
        self._mesh_b.vertices, self._mesh_b.indices = verts_b, idx
        self._line.points = pts
        self._line_glow.points = pts
        self._line_b.points = pts_b

    def _update_radial(self):
        count = len(self.values)
        cx, cy = self.center
        size = min(self.width, self.height)
        pulse = self.beat_pulse if self.cfg("beat_pulse") else 0.0
        radius = size * (0.20 + 0.03 * pulse)
        hs = float(self.cfg("height"))
        spin = self.phase * 0.25 if self.cfg("spin") else 0.0
        bw = max(dp(1.5), (math.tau * radius / count) * (1.0 - float(self.cfg("gap")) * 0.7))
        for i, v in enumerate(self.values):
            k = abs(i - (count - 1) / 2.0) / ((count - 1) / 2.0)
            v = self.values[int(k * (count - 1))]
            ang = spin + i / count * math.tau + math.pi / 2
            ln = min(1.0, v * hs) * size * 0.27 + dp(2)
            c, s = math.cos(ang), math.sin(ang)
            self._items[i].points = [cx + c * radius, cy + s * radius, cx + c * (radius + ln), cy + s * (radius + ln)]
            self._items[i].width = bw / 2
            col = lerp_stops(self._stops, 0.25 + 0.75 * min(1.0, v * hs))
            self._colors[i].rgba = col
        self._ring.circle = (cx, cy, radius - dp(4))
        core = radius * (0.55 + 0.35 * self.level + 0.25 * pulse)
        self._core_color.a = 0.25 + 0.55 * pulse
        self._core.pos, self._core.size = (cx - core, cy - core), (core * 2, core * 2)

    def _update_led(self):
        count = len(self.values)
        rows = self._rows
        pad, slot, bw = self._geometry(count)
        hs = float(self.cfg("height"))
        base = self.y + dp(10)
        avail = self.height - dp(20)
        cell = avail / rows
        bh = cell * 0.72
        for i, v in enumerate(self.values):
            x = self.x + pad + i * slot + (slot - bw) / 2
            self._cols_bg[i].pos, self._cols_bg[i].size = (x, base), (bw, avail)
            lit = int(round(min(1.0, v * hs) * rows))
            peak_row = int(min(rows - 1, self.peaks[i] * hs * rows)) if self.cfg("peaks") else -1
            for r in range(rows):
                rect = self._items[i * rows + r]
                if r < lit or r == peak_row:
                    f = (r + 0.5) / rows
                    rect.pos, rect.size = (x, base + r * cell), (bw, bh)
                    rect.tex_coords = (0, f, 1, f, 1, f, 0, f)
                else:
                    rect.size = (0, 0)

    def _update_orbit(self):
        count = len(self.values)
        cx, cy = self.center
        size = min(self.width, self.height)
        pulse = self.beat_pulse if self.cfg("beat_pulse") else 0.0
        radius = size * (0.18 + 0.03 * pulse)
        hs = float(self.cfg("height"))
        spin = -self.phase * 0.2 if self.cfg("spin") else 0.0
        vals = []
        for i in range(count):
            k = abs(i - (count - 1) / 2.0) / ((count - 1) / 2.0)
            vals.append(self.values[int(k * (count - 1))])
        vals = self._catmull(vals + vals[:3], 3)[: count * 3]
        n = len(vals)
        verts = [cx, cy, 0, 0]
        pts = []
        for i in range(n + 1):
            v = vals[i % n]
            ang = spin + i / n * math.tau
            r = radius + min(1.0, v * hs) * size * 0.26
            x, y = cx + math.cos(ang) * r, cy + math.sin(ang) * r
            verts += [x, y, 0, min(1.0, 0.35 + v * hs)]
            pts += [x, y]
        self._mesh.vertices = verts
        self._mesh.indices = list(range(n + 2))
        self._line.points = pts
        self._line_glow.points = pts
        core = radius * (0.5 + 0.3 * self.level + 0.3 * pulse)
        self._core_color.a = 0.35 + 0.5 * pulse
        self._core.pos, self._core.size = (cx - core, cy - core), (core * 2, core * 2)

    # ------------------------------------------------------------- particles
    def _update_particles(self, dt):
        self._parts.clear()
        if not self.cfg("particles"):
            return
        settings = {"count": 160, "lifetime": 2.2, "spawn_time": 0.03, "speed": 55.0 + 140 * self.level,
                    "speed_random": 0.5, "gravity_x": 0.0, "gravity_y": 14.0, "spawn_area": "Rectangle",
                    "angle": 90.0, "force_field": "None", "size": 2.4, "audio_reactive": True}
        try:
            parts = self.particle_system.update(dt, settings, self.width, self.height,
                                                audio_level=self.level, beat=self.beat_pulse > 0.8)
        except Exception:
            parts = []
        top = self._stops[-1] if getattr(self, "_stops", None) else (1, 1, 1, 1)
        for p in parts[:220]:
            life = max(0.0, min(1.0, 1.0 - p["age"] / max(0.001, p["life"])))
            self._parts.add(Color(top[0], top[1], top[2], 0.8 * life))
            s = dp(p.get("size", 2.4)) * (0.6 + life)
            self._parts.add(Ellipse(pos=(self.x + p["x"] - s / 2, self.y + p["y"] - s / 2), size=(s, s)))

    # ------------------------------------------------------------- text & image layers
    def _title(self):
        track = str(getattr(self.state, "current_track", "") or "")
        if not track:
            return "Vooc"
        return track.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0]

    def _update_layers(self):
        layers = getattr(self.state, "layers", [])
        sig = (round(self.width), round(self.height), round(self.x), round(self.y), self._title(),
               repr([(l.get("type"), l.get("name"), l.get("visible"), l.get("x"), l.get("y"), l.get("scale"),
                      l.get("opacity"), l.get("rotation"), l.get("image_path")) for l in layers]))
        if sig == self._layer_sig:
            return
        self._layer_sig = sig
        g = self._layers
        g.clear()
        for layer in layers:
            if not layer.get("visible", True) or layer.get("type") not in ("text", "image"):
                continue
            opacity = max(0.0, min(1.0, float(layer.get("opacity", 1.0))))
            scale = max(0.1, min(6.0, float(layer.get("scale", 1.0))))
            if layer["type"] == "text":
                text = layer.get("name", "Text")
                if text.strip().lower() in ("track title", "%title%"):
                    text = self._title()
                key = (text, round(scale, 2))
                tex = self._text_cache.get(key)
                if tex is None:
                    lb = CoreLabel(text=text, font_size=dp(15) * scale, bold=True, color=(1, 1, 1, 1),
                                   text_size=(self.width * 0.9, None), halign="center", shorten=True)
                    lb.refresh()
                    tex = lb.texture
                    if len(self._text_cache) > 40:
                        self._text_cache.clear()
                    self._text_cache[key] = tex
                w, h = tex.size
            else:
                path = layer.get("image_path")
                if not path:
                    continue
                tex = self._image_cache.get(path)
                if tex is None:
                    try:
                        tex = CoreImage(path).texture
                    except Exception:
                        continue
                    self._image_cache[path] = tex
                ratio = tex.width / max(1.0, tex.height)
                h = self.height * 0.4 * scale
                w = h * ratio
            x = self.x + self.width * float(layer.get("x", 50)) / 100.0 - w / 2
            y = self.y + self.height * float(layer.get("y", 50)) / 100.0 - h / 2
            g.add(PushMatrix())
            g.add(Rotate(angle=float(layer.get("rotation", 0)), origin=(x + w / 2, y + h / 2)))
            if layer["type"] == "text":
                g.add(Color(0, 0, 0, 0.45 * opacity))
                g.add(Rectangle(texture=tex, pos=(x + dp(1), y - dp(1)), size=(w, h)))
            g.add(Color(1, 1, 1, opacity))
            g.add(Rectangle(texture=tex, pos=(x, y), size=(w, h)))
            g.add(PopMatrix())
