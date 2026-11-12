import unittest

from vooc.measure_engine import MeasureEngine


class MeasureEngineTests(unittest.TestCase):
    def test_nothing_static(self):
        self.assertEqual(MeasureEngine().evaluate("Nothing", 0.4, 10, 10), 0.4)

    def test_beat_pulse(self):
        m = MeasureEngine()
        m.update(0.016, beat=True)
        self.assertAlmostEqual(m.evaluate("Beat", 0.0, 2.0, 1.0), 2.0, places=3)
        m.update(0.5)
        self.assertLess(m.evaluate("Beat", 0.0, 2.0, 1.0), 1.0)

    def test_volume_is_db_mapped(self):
        m = MeasureEngine()
        m.update(0.016, rms=0.5)
        # -6 dB -> 0.9 on a 60 dB scale
        self.assertAlmostEqual(m.evaluate("VolumeLevelResponsive", 0.0, 1.0, 1.0), 0.9, places=1)

    def test_track_progress(self):
        m = MeasureEngine()
        m.update(0.016, track_position=30, track_duration=60)
        self.assertAlmostEqual(m.evaluate("TrackProgressPercentage", 0.0, 1.0, 1.0), 0.5)

    def test_total_time_uses_time(self):
        m = MeasureEngine()
        m.update(0.25)
        self.assertAlmostEqual(m.evaluate("TotalTime", 0.0, 1.0, 1.0), 0.25, places=3)


if __name__ == "__main__":
    unittest.main()
