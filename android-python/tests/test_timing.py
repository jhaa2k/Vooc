import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from vooc.engine.timing import gif_index, synced_time  # noqa: E402


class GifTimingTests(unittest.TestCase):
    D = [0.1, 0.1, 0.1, 0.1]

    def test_modes(self):
        self.assertEqual([gif_index(self.D, t) for t in (0.05, 0.15, 0.35, 0.45)], [0, 1, 3, 0])
        self.assertEqual([gif_index(self.D, t, "Reverse") for t in (0.05, 0.15, 0.35)], [3, 2, 0])
        self.assertEqual([gif_index(self.D, 0.05 + 0.1 * k, "Ping-Pong") for k in range(7)], [0, 1, 2, 3, 2, 1, 0])
        self.assertEqual(gif_index(self.D, 99, "Once"), 3)
        self.assertEqual(gif_index(self.D, 0.05, "Loop", start=2), 2)
        self.assertEqual(gif_index([0.1], 5.0), 0)

    def test_sync(self):
        state = {}
        snap = {"bpm": 120.0, "beats_per_bar": 4, "beat": True, "downbeat": False}
        self.assertEqual(synced_time("Beat", 0, 1, 1, snap, state), 1)
        self.assertEqual(synced_time("Beat", 0, 1, 1, snap, state), 2)
        # BPM: one loop (total) per bar = 2 s at 120 bpm
        self.assertAlmostEqual(synced_time("BPM", 1.0, 1.0, 0.4, snap, {}), 0.2)
        st = {}
        synced_time("Bar", 10.0, 1.0, 0.4, dict(snap, downbeat=True), st)
        self.assertAlmostEqual(synced_time("Bar", 11.0, 1.0, 0.4, snap, st), 0.2)
        self.assertEqual(synced_time("Free", 3.0, 2.0, 1, snap, {}), 6.0)


if __name__ == "__main__":
    unittest.main()
