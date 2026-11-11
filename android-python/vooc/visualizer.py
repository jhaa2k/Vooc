from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle, Ellipse, Line
from kivy.metrics import dp
from kivy.core.text import Label as CoreLabel
from kivy.core.image import Image as CoreImage
from kivy.graphics import PushMatrix, PopMatrix, Rotate
import math


PALETTES = {
    "Amber": (1.0, .48, .16),
    "Cyan": (.12, .82, 1.0),
    "Purple": (.68, .38, 1.0),
    "Green": (.22, .92, .58),
    "White": (.92, .94, 1.0),
}


class Spectrum(Widget):
    """Configurable spectrum renderer driven by normalized FFT bands."""
    def __init__(self, state, **kw):
        super().__init__(**kw)
        self.state = state
        self.phase = 0.0
        self.bands = None
        self.smoothed = []
        self.beat_pulse = 0.0
        self._image_textures = {}
        self.bind(pos=self.refresh, size=self.refresh)
        Clock.schedule_interval(self.tick, 1 / 30)

    def _prop(self, category, section, group, name, default=None):
        key = ".".join((category, section, group, name))
        return getattr(self.state, "properties", {}).get(key, default)

    def _settings(self):
        settings = dict(getattr(self.state, "visualizer", {}))
        count = self._prop("BARS / SEGMENTS", "Bars Setup", "Geometry", "Bar Count")
        if count is not None:
            settings["bar_count"] = int(max(8, min(128, float(count))))
        return settings

    @staticmethod
    def _rgba(value, fallback=(1, 1, 1, 1)):
        try:
            raw = str(value).strip().lstrip("#")
            if len(raw) == 6: raw += "FF"
            if len(raw) != 8: return fallback
            return tuple(int(raw[i:i+2], 16) / 255.0 for i in (0, 2, 4, 6))
        except (TypeError, ValueError):
            return fallback

    def set_spectrum(self, bands, beat=False):
        incoming = [max(0.0, min(1.0, float(v))) for v in bands] if bands else []
        count = max(8, min(128, int(self._settings().get("bar_count", 64))))
        if incoming:
            sampled = []
            for i in range(count):
                start = int(i * len(incoming) / count)
                end = max(start + 1, int((i + 1) * len(incoming) / count))
                sampled.append(max(incoming[start:min(end, len(incoming))] or [0.0]))
            sensitivity = max(.1, float(self._settings().get("sensitivity", 1.0)))
            sampled = [min(1.0, v * sensitivity) for v in sampled]
            smooth = max(0.0, min(.95, float(self._settings().get("smoothing", .35))))
            if len(self.smoothed) != count:
                self.smoothed = sampled
            else:
                self.smoothed = [old * smooth + new * (1.0 - smooth)
                                 for old, new in zip(self.smoothed, sampled)]
            self.bands = self.smoothed
        if beat:
            self.beat_pulse = 1.0
        self.refresh()

    def tick(self, dt):
        self.phase += dt * 3.0
        self.beat_pulse = max(0.0, self.beat_pulse - dt * 3.5)
        self.refresh()

    def refresh(self, *_):
        self.canvas.clear()
        if self.width <= 1 or self.height <= 1:
            return
        settings = self._settings()
        style = settings.get("style", "Bars")
        rgb = PALETTES.get(settings.get("palette", "Amber"), PALETTES["Amber"])
        bg = self._rgba(self._prop("COMPOSITION", "Render Settings", "Output", "Background Color", "#010102FF"), (.005, .005, .008, 1))
        bar_height = float(self._prop("BARS / SEGMENTS", "Bars Setup", "Geometry", "Height Scale", 1.0))
        bar_height = max(0.0, min(10.0, bar_height))
        bar_spacing = max(0.0, min(1.0, float(self._prop("BARS / SEGMENTS", "Bars Setup", "Geometry", "Bar Spacing", 0.1))))
        flip_input = bool(self._prop("BARS / SEGMENTS", "Bars Setup", "Geometry", "Flip Input", False))
        tint = self._rgba(self._prop("BARS / SEGMENTS", "Segment 1", "Segment 1", "Color From", ""), (*rgb, 1)) if self._prop("BARS / SEGMENTS", "Segment 1", "Segment 1", "Enable Segment", False) else (*rgb, 1)
        with self.canvas:
            Color(*bg)
            Rectangle(pos=self.pos, size=self.size)
            bands = self.bands or [
                .08 + .72 * abs(math.sin(i * .19 + self.phase))
                * abs(math.sin(i * .073 - self.phase)) for i in range(64)
            ]
            count = len(bands)
            gap = max(dp(1), dp(2) + bar_spacing * dp(8))
            Color(tint[0], tint[1], tint[2], tint[3])
            if style in ("Bars", "Mirror Bars"):
                requested_width = float(self._prop("BARS / SEGMENTS", "Bars Setup", "Geometry", "Bar Width", 0.8))
                bw = max(1, min((self.width - gap * (count + 1)) / count, (self.width / count) * max(.05, min(1.0, requested_width))))
                for i, amplitude in enumerate(bands):
                    if flip_input: amplitude = 1.0 - amplitude
                    h = max(dp(2), self.height * min(1.0, amplitude * bar_height))
                    x = self.x + gap + i * (bw + gap)
                    if style == "Mirror Bars":
                        center = self.center_y
                        half = h * .42
                        Rectangle(pos=(x, center - half), size=(bw, half))
                        Rectangle(pos=(x, center), size=(bw, half))
                    else:
                        Rectangle(pos=(x, self.y + dp(8)), size=(bw, h))
            elif style == "Dots":
                bw = max(1, (self.width - gap * (count + 1)) / count)
                dot = max(dp(2), min(dp(7), bw * .72))
                for i, amplitude in enumerate(bands):
                    x = self.x + gap + i * (bw + gap) + (bw-dot)/2
                    y = self.y + dp(8) + amplitude * max(0, self.height-dp(18)-dot)
                    Ellipse(pos=(x, y), size=(dot, dot))
            elif style == "Line":
                pts = []
                for i, amplitude in enumerate(bands):
                    x = self.x + (i / max(1, count-1)) * self.width
                    y = self.y + dp(8) + amplitude * max(0, self.height-dp(16))
                    pts.extend((x, y))
                if len(pts) >= 4:
                    Line(points=pts, width=dp(1.7), cap="round", joint="round")
            elif style == "Radial":
                cx, cy = self.center
                radius = min(self.width, self.height) * .22
                for i, amplitude in enumerate(bands):
                    angle = (i / count) * math.tau
                    inner = radius
                    outer = radius + amplitude * min(self.width, self.height) * .22
                    Line(points=[cx + math.cos(angle)*inner, cy + math.sin(angle)*inner,
                                 cx + math.cos(angle)*outer, cy + math.sin(angle)*outer],
                         width=dp(2), cap="round")
                Color(*rgb, .22 + self.beat_pulse * .35)
                Ellipse(pos=(cx-radius*.12, cy-radius*.12), size=(radius*.24, radius*.24))
            # Image and text layers share normalized scene coordinates and transforms.
            for layer in getattr(self.state, "layers", []):
                if not layer.get("visible", True):
                    continue
                layer_type = layer.get("type")
                if layer_type == "image":
                    path = layer.get("image_path")
                    if not path:
                        continue
                    try:
                        if path not in self._image_textures:
                            self._image_textures[path] = CoreImage(path).texture
                        texture = self._image_textures[path]
                    except Exception:
                        continue
                    if not texture:
                        continue
                    scale_value = self._prop("IMAGE", "General", "Transform", "Scale X", layer.get("scale", 1.0))
                    scale = max(.05, min(8.0, float(scale_value)))
                    max_w, max_h = self.width * .48 * scale, self.height * .72 * scale
                    ratio = texture.width / max(1.0, texture.height)
                    image_w = min(max_w, max_h * ratio)
                    image_h = image_w / max(.001, ratio)
                    if image_h > max_h:
                        image_h = max_h
                        image_w = image_h * ratio
                    x = self.x + self.width * float(layer.get("x", 50)) / 100.0 - image_w / 2
                    y = self.y + self.height * float(layer.get("y", 50)) / 100.0 - image_h / 2
                    opacity_value = self._prop("IMAGE", "General", "Visibility", "Visible", layer.get("visible", True))
                    opacity = max(0.0, min(1.0, float(layer.get("opacity", 1.0)))) if opacity_value else 0.0
                    opacity *= max(0.0, min(1.0, float(self._prop("IMAGE", "General", "Transform", "Scale Y", 1.0))))
                    PushMatrix()
                    Rotate(angle=float(layer.get("rotation", 0)), origin=(x + image_w / 2, y + image_h / 2))
                    Color(1, 1, 1, opacity)
                    Rectangle(texture=texture, pos=(x, y), size=(image_w, image_h))
                    PopMatrix()
                    continue
                if layer_type != "text":
                    continue
                if not layer.get("visible", True) or layer.get("type") != "text":
                    continue
                content = self._prop("TEXT", "Content", "Text", "Text Input", layer.get("name", "Text"))
                font_size = max(1.0, min(300.0, float(self._prop("TEXT", "Typography", "Font", "Font Size", 18))))
                text_color = self._rgba(self._prop("TEXT", "Appearance", "Colors", "Text Color", "#FFFFFFFF"))
                visible = bool(self._prop("TEXT", "General", "Visibility", "Visible", True))
                if not visible: continue
                label = CoreLabel(text=str(content),
                                  font_size=dp(font_size) * max(.25, min(4.0, float(layer.get("scale", 1.0)))),
                                  color=(text_color[0], text_color[1], text_color[2], text_color[3] * max(0.0, min(1.0, float(layer.get("opacity", 1.0))))))
                label.refresh()
                texture = label.texture
                if not texture:
                    continue
                x = self.x + self.width * float(layer.get("x", 50)) / 100.0 - texture.width / 2
                y = self.y + self.height * float(layer.get("y", 50)) / 100.0 - texture.height / 2
                angle = float(layer.get("rotation", 0))
                PushMatrix()
                Rotate(angle=angle, origin=(x + texture.width / 2, y + texture.height / 2))
                Color(1, 1, 1, 1)
                Rectangle(texture=texture, pos=(x, y), size=texture.size)
                PopMatrix()
