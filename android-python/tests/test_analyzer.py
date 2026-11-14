import math
import unittest

import numpy as np

from vooc.audio import beats
from vooc.audio.timeline_hub import TimelineHub
from vooc.measure_engine import MeasureEngine
from vooc.model import LayerModel
from vooc.spectrum_source import AnalysisConfig, WINDOWS, analyse_pcm


def _music(seconds=12.0, rate=22050, bpm=120.0, stereo=False):
    t = np.arange(int(seconds * rate)) / rate
    period = 60.0 / bpm
    k = t % period
    kick = 0.8 * np.sin(2 * np.pi * 55 * k) * np.exp(-k * 18)
    hat_k = (t + period / 2) % period
    noise = np.random.RandomState(1).randn(len(t) + 2)
    noise = np.diff(noise, 2)                      # high-passed: hi-hats live above ~5 kHz
    hat = 0.05 * noise * np.exp(-hat_k * 60)
    tone = 0.2 * np.sin(2 * np.pi * 880 * t)
    left = kick + hat + tone
    right = kick + hat - tone if stereo else left
    return np.stack([left, right], 1).astype(np.float32) * 0.8, rate


class AnalysisTests(unittest.TestCase):
    def test_fft_sizes_windows_and_overlap(self):
        pcm, rate = _music(3.0)
        for fft in (512, 2048, 8192):
            for win in WINDOWS:
                tl = analyse_pcm(pcm, rate, AnalysisConfig(fft, win, 50, False, 64))
                self.assertGreaterEqual(tl.fps, 19.9)
                self.assertLessEqual(tl.fps, 120.5)
                self.assertAlmostEqual(tl.duration, 3.0, delta=0.3)
                db, rms, peak = tl.sample_db(1.0)
                self.assertEqual(len(db), 64)
                # 880 Hz tone must stand out over 8 kHz
                hz = 30 * (16000 / 30.0) ** ((np.arange(64) + 0.5) / 64)
                self.assertGreater(db[np.argmin(abs(hz - 880))], db[np.argmin(abs(hz - 8000))])
        a = analyse_pcm(pcm, rate, AnalysisConfig(1024, "Hann", 0, False))
        b = analyse_pcm(pcm, rate, AnalysisConfig(1024, "Hann", 75, False))
        self.assertGreater(b.fps, a.fps * 2)

    def test_stereo_mid_side_and_pcm(self):
        pcm, rate = _music(2.0, stereo=True)
        tl = analyse_pcm(pcm, rate, AnalysisConfig(1024, "Hann", 50, True))
        self.assertEqual(tl.nspec, 4)
        side = tl.sample_db(1.0, 3)[0]
        mono = tl.sample_db(1.0, 0)[0]
        hz = 30 * (16000 / 30.0) ** ((np.arange(64) + 0.5) / 64)
        i = np.argmin(abs(hz - 880))
        self.assertGreater(side[i], -40)        # tone is out of phase -> lives in the side channel
        self.assertLess(mono[i], side[i])
        w = tl.waveform(1.0, 0.05, 256, "Left")
        self.assertEqual(len(w), 256)
        self.assertGreater(float(np.abs(w).max()), 0.05)

    def test_beats_bpm_bars(self):
        pcm, rate = _music(16.0, bpm=120)
        tl = analyse_pcm(pcm, rate, AnalysisConfig(1024, "Hann", 50, False))
        for algo in beats.ALGORITHMS:
            g = beats.analyse(tl, {"algorithm": algo, "signature": "4/4"})
            self.assertAlmostEqual(g.bpm_auto, 120, delta=4, msg=algo)
            self.assertGreater(len(g.times), 20, algo)
            gaps = np.diff(g.times)
            self.assertAlmostEqual(float(np.median(gaps)), 0.5, delta=0.06, msg=algo)
        g = beats.analyse(tl, {"bpm_mode": "Manual", "manual_bpm": 60, "signature": "3/4"})
        self.assertAlmostEqual(float(np.median(np.diff(g.times))), 1.0, delta=0.06)
        k, in_bar, bar, phase, down = g.position(8.2)
        self.assertTrue(1 <= in_bar <= 3)
        self.assertGreaterEqual(bar, 1)
        self.assertTrue(g.kick)
        g2 = beats.analyse(tl, {"multiplier": "x2 (Double)"})
        self.assertAlmostEqual(g2.bpm_auto, 240, delta=8)

    def test_tap_tempo_and_decay(self):
        self.assertAlmostEqual(beats.tap_tempo([0, 0.5, 1.0, 1.5, 2.0]), 120, delta=0.1)
        for curve in ("Exponential", "Linear", "Ease-Out", "Ease-In", "Step (Hold)"):
            self.assertAlmostEqual(beats.decay(curve, 0.0, 0.3), 1.0)
            self.assertLess(beats.decay(curve, 0.31, 0.3), 0.1)


class HubTests(unittest.TestCase):
    def _hub(self, tl, layer):
        clock = {"t": 0.0}
        hub = TimelineHub(layer, timeline=lambda: tl, position=lambda: clock["t"], playing=lambda: True)
        hub.fixed_dt = 1 / 60.0
        return hub, clock

    def test_snapshot_fields_bands_and_beats(self):
        pcm, rate = _music(8.0, bpm=120)
        tl = analyse_pcm(pcm, rate, AnalysisConfig(1024, "Hann", 50, False))
        layer = LayerModel("AUDIOPROVIDER")
        layer.set("Frequency Bands/Custom Bands/Bands", [{"label": "Kick", "lo": 40, "hi": 120},
                                                        {"label": "Air", "lo": 9000, "hi": 16000}])
        hub, clock = self._hub(tl, layer)
        n_beats, kick_max = 0, 0.0
        for i in range(60 * 6):
            clock["t"] = i / 60.0
            snap = hub.update()
            n_beats += snap["beat"]
            kick_max = max(kick_max, snap["custom_bands"][0])
        self.assertEqual(len(snap["bands"]), 256)
        self.assertEqual(snap["custom_labels"], ["Kick", "Air"])
        self.assertGreater(kick_max, snap["custom_bands"][1])
        self.assertTrue(9 <= n_beats <= 14, n_beats)
        self.assertAlmostEqual(snap["bpm"], 120, delta=4)
        self.assertIn(snap["beat_in_bar"], (1, 2, 3, 4))
        # seeking resyncs without firing a beat
        clock["t"] = 1.0
        self.assertTrue(hub.update()["seeked"])

    def test_scale_options(self):
        pcm, rate = _music(3.0)
        tl = analyse_pcm(pcm, rate, AnalysisConfig())
        layer = LayerModel("AUDIOPROVIDER")
        hub, clock = self._hub(tl, layer)
        clock["t"] = 1.0
        base = hub.update()["bands"].mean()
        layer.set("Analyzer/Scale/dB Floor", -100)
        lower_floor = hub.update()["bands"].mean()
        self.assertGreater(lower_floor, base)
        layer.set("Analyzer/Scale/Amplitude Scale", "Linear")
        self.assertLess(hub.update()["bands"].mean(), lower_floor)
        layer.set("Analyzer/Smoothing/Release (ms)", 800)
        for name in ("Cubic", "Nearest"):
            layer.set("Analyzer/Scale/Interpolation", name)
            self.assertEqual(len(hub.update()["bands"]), 256)


class MeasureTests(unittest.TestCase):
    def test_new_modes_and_override(self):
        m = MeasureEngine()
        snap = {"bass": 1.0, "mid": 0.0, "treble": 0.0, "kick": 0.7, "beat_phase": 0.5,
                "beat_in_bar": 3, "beats_per_bar": 4, "custom_bands": [0.25], "decay_curve": "Linear",
                "decay_s": 0.5}
        m.update(1 / 60, beat=True, rms=0.5, snapshot=snap)
        self.assertAlmostEqual(m.evaluate("Kick", 0, 1, 1), 0.7)
        self.assertAlmostEqual(m.evaluate("BarProgress", 0, 1, 1), 2.5 / 4)
        self.assertAlmostEqual(m.evaluate("CustomBand1", 0, 2, 1), 0.5)
        self.assertGreater(m.evaluate("Frequency", 0, 1, 1), 0.5)
        layer = LayerModel("BARS")
        layer.set("General/Reactivity/Reactivity Mode", "Nothing")
        m.use_layer(layer.values)
        self.assertEqual(m.evaluate("Beat", 0, 1, 1), 0.0)
        layer.set("General/Reactivity/Reactivity Mode", "Amplitude")
        m.use_layer(layer.values)
        self.assertAlmostEqual(m.evaluate("TotalTime", 0, 1, 1), m.volume_level())
        self.assertEqual(m.evaluate("Nothing", 0, 1, 1), 0.0)


if __name__ == "__main__":
    unittest.main()
