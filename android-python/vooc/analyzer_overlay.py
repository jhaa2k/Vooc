"""Analyzer debug overlay drawn over the preview (AUDIOPROVIDER > Analyzer > Debug).

Shows the raw band spectrum, the 3-band split (L/M/H), kick/snare/hat onsets,
the beat flash with downbeat accent, BPM (auto / manual + confidence) and the
bar:beat counter, plus custom band meters. It reads the TimelineHub snapshot
the renderer already produced this frame, so it costs one canvas rebuild.
"""
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle
from kivy.metrics import dp
from kivy.uix.label import Label
from kivy.uix.widget import Widget

ACID = (0.70, 1.0, 0.0)


def _seq(value):
    return [] if value is None else list(value)


class AnalyzerOverlay(Widget):
    def __init__(self, hub, enabled, **kw):
        super().__init__(**kw)
        self.hub, self.enabled = hub, enabled
        self.label = Label(font_size=dp(10.5), halign="left", valign="top", color=(1, 1, 1, 0.95),
                           markup=False, size_hint=(None, None))
        self.add_widget(self.label)
        self._last_text = ""
        self._ev = Clock.schedule_interval(self._tick, 1 / 30.0)

    def stop(self):
        if self._ev is not None:
            self._ev.cancel()
            self._ev = None

    def _tick(self, _dt):
        if self.parent is None:
            return
        if not self.enabled():
            if self._last_text:
                self.canvas.before.clear()
                self.label.text, self._last_text = "", ""
            return
        snap = getattr(self.hub, "current", None) or {}
        self._draw(snap)

    def _draw(self, snap):
        x, y, w, h = self.x, self.y, self.width, self.height
        ph = h * 0.30                       # panel height (bottom of the preview)
        bands = _seq(snap.get("bands"))
        cb = _seq(snap.get("custom_bands"))
        beat = bool(snap.get("beat"))
        down = bool(snap.get("downbeat"))
        cv = self.canvas.before
        cv.clear()
        with cv:
            Color(0, 0, 0, 0.55)
            Rectangle(pos=(x, y), size=(w, ph))
            # spectrum
            n = len(bands)
            if n:
                step = max(1, n // 64)
                pts = bands[::step]
                bw = (w * 0.62) / len(pts)
                Color(*ACID, 0.85)
                for i, v in enumerate(pts):
                    Rectangle(pos=(x + dp(6) + i * bw, y + dp(6)), size=(max(1.0, bw - 1), (ph - dp(28)) * min(1.0, float(v))))
            # 3-band + custom meters
            meters = [("L", snap.get("low", snap.get("bass", 0))), ("M", snap.get("mid", 0)),
                      ("H", snap.get("high", snap.get("treble", 0)))] + [("C%d" % (i + 1), v) for i, v in enumerate(cb[:8])]
            mx = x + w * 0.62 + dp(12)
            mw = max(dp(3), (w - (mx - x) - dp(6)) / max(1, len(meters)) - dp(2))
            for i, (_name, v) in enumerate(meters):
                Color(0.36, 0.82, 1.0, 0.9) if i < 3 else Color(1.0, 0.55, 0.2, 0.9)
                Rectangle(pos=(mx + i * (mw + dp(2)), y + dp(6)), size=(mw, (ph * 0.55) * max(0.0, min(1.0, float(v)))))
            # onsets
            for i, key in enumerate(("kick_hit", "snare_hit", "hihat_hit")):
                on = bool(snap.get(key))
                Color(*((1, 0.3, 0.3) if i == 0 else (1, 0.85, 0.2) if i == 1 else (0.5, 0.8, 1)), 1 if on else 0.18)
                Rectangle(pos=(x + w - dp(16) - i * dp(14), y + ph - dp(16)), size=(dp(10), dp(10)))
            # beat flash
            if beat:
                Color(*((1, 1, 1) if down else ACID), 0.35 if down else 0.22)
                Rectangle(pos=(x, y + h - dp(6)), size=(w, dp(6)))
        bpm = float(snap.get("bpm", 0) or 0)
        auto = float(snap.get("bpm_auto", 0) or 0)
        conf = float(snap.get("bpm_confidence", 0) or 0)
        bpb = int(snap.get("beats_per_bar", 4) or 4)
        text = "BPM %.1f (auto %.1f, %d%%)   bar %d : %d/%d   %d Hz" % (
            bpm, auto, round(conf * 100), int(snap.get("bar", 0)) + 1, int(snap.get("beat_in_bar", 0)) + 1, bpb,
            int(snap.get("dominant_hz", 0) or 0))
        if text != self._last_text:
            self.label.text = self._last_text = text
        self.label.size = (w - dp(12), dp(16))
        self.label.text_size = self.label.size
        self.label.pos = (x + dp(6), y + ph - dp(20))
