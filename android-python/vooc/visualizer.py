from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle, Ellipse, Line
from kivy.metrics import dp
from kivy.core.text import Label as CoreLabel
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
        self.bind(pos=self.refresh, size=self.refresh)
        Clock.schedule_interval(self.tick, 1 / 30)

    def _settings(self):
        return getattr(self.state, "visualizer", {})

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
        with self.canvas:
            Color(.005, .005, .008, 1)
            Rectangle(pos=self.pos, size=self.size)
            bands = self.bands or [
                .08 + .72 * abs(math.sin(i * .19 + self.phase))
                * abs(math.sin(i * .073 - self.phase)) for i in range(64)
            ]
            count = len(bands)
            gap = dp(2)
            Color(*rgb, 1)
            if style in ("Bars", "Mirror Bars"):
                bw = max(1, (self.width - gap * (count + 1)) / count)
                for i, amplitude in enumerate(bands):
                    h = max(dp(2), self.height * min(1.0, amplitude))
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
            # Text layers are rendered in normalized scene coordinates.
            for layer in getattr(self.state, "layers", []):
                if not layer.get("visible", True) or layer.get("type") != "text":
                    continue
                label = CoreLabel(text=str(layer.get("name", "Text")),
                                  font_size=dp(18) * max(.25, min(4.0, float(layer.get("scale", 1.0)))),
                                  color=(1, 1, 1, max(0.0, min(1.0, float(layer.get("opacity", 1.0))))))
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
