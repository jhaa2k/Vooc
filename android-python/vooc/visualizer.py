from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle
from kivy.metrics import dp
import math


class Spectrum(Widget):
    """Spectrum renderer. Real PCM-derived bands can be supplied with set_spectrum()."""
    def __init__(self, state, **kw):
        super().__init__(**kw)
        self.state = state
        self.phase = 0.0
        self.bands = None
        self.beat_pulse = 0.0
        self.bind(pos=self.refresh, size=self.refresh)
        Clock.schedule_interval(self.tick, 1 / 30)

    def set_spectrum(self, bands, beat=False):
        """Update the preview from normalized [0..1] frequency-band amplitudes."""
        self.bands = [max(0.0, min(1.0, float(v))) for v in bands] if bands else None
        if beat:
            self.beat_pulse = 1.0
        self.refresh()

    def tick(self, dt):
        self.phase += dt * 3.0
        self.beat_pulse = max(0.0, self.beat_pulse - dt * 3.5)
        self.refresh()

    def refresh(self, *_):
        self.canvas.clear()
        with self.canvas:
            Color(.005, .005, .008, 1)
            Rectangle(pos=self.pos, size=self.size)
            count = len(self.bands) if self.bands else 64
            gap = dp(2)
            bw = max(1, (self.width - gap * (count + 1)) / count)
            for i in range(count):
                if self.bands:
                    amplitude = self.bands[i]
                else:
                    # Visual placeholder until an Android PCM capture source is connected.
                    amplitude = (.08 + .72 * abs(math.sin(i * .19 + self.phase))
                                 * abs(math.sin(i * .073 - self.phase)))
                height = self.height * min(1.0, amplitude)
                Color(.85, .38, .25, 1)
                Rectangle(pos=(self.x + gap + i * (bw + gap), self.y + dp(8)),
                          size=(bw, max(dp(2), height)))
