import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from vooc import library, perf  # noqa: E402


class LibraryTest(unittest.TestCase):
    PL = ["/m/b song.mp3", "/m/a tune.flac", "/m/c beat.mp3", "/m/zz.wav"]
    META = {"/m/zz.wav": {"title": "Alpha", "artist": "Zed"}, "/m/c beat.mp3": {"artist": "Amy"}}

    def test_chips(self):
        self.assertEqual(library.chips(self.PL), ["All", "Favorites", "FLAC", "MP3", "WAV"])

    def test_search_and_filter(self):
        self.assertEqual(library.view(self.PL, "amy", meta=self.META), ["/m/c beat.mp3"])
        self.assertEqual(library.view(self.PL, "alpha zed", meta=self.META), ["/m/zz.wav"])
        self.assertEqual(library.view(self.PL, chip="MP3"), ["/m/c beat.mp3", "/m/b song.mp3"])
        self.assertEqual(library.view(self.PL, chip="Favorites", favorites=["/m/zz.wav"]), ["/m/zz.wav"])

    def test_sorts(self):
        self.assertEqual(library.view(self.PL, sort="Title A-Z", meta=self.META)[0], "/m/a tune.flac")
        self.assertEqual(library.view(self.PL, sort="Title Z-A", meta=self.META)[0], "/m/c beat.mp3")
        self.assertEqual(library.view(self.PL, sort="Artist", meta=self.META)[:2], ["/m/c beat.mp3", "/m/zz.wav"])
        self.assertEqual(library.view(self.PL)[0], "/m/zz.wav")


class PerfTest(unittest.TestCase):
    def test_tiers(self):
        self.assertEqual(perf.ram_tier(2 * perf.GB), "Low")
        self.assertEqual(perf.ram_tier(4 * perf.GB), "Medium")
        self.assertEqual(perf.ram_tier(8 * perf.GB), "High")
        self.assertEqual(perf.ram_tier(12 * perf.GB), "Ultra")
        self.assertEqual(perf.ram_tier(0), "High")

    def test_match(self):
        self.assertEqual(perf.match(dict(perf.PRESETS["Medium"])), "Medium")
        self.assertEqual(perf.match({"preview_fps": 45}), "Custom")

    def test_autotuner(self):
        t = perf.AutoTuner(window=3, cooldown=5)
        out = [t.feed(20, 60, "Ultra") for _ in range(3)]
        self.assertEqual(out[-1], "High")
        self.assertIsNone(t.feed(20, 60, "High"))      # cooldown
        t2 = perf.AutoTuner(window=3, cooldown=0)
        self.assertEqual([t2.feed(f, 60, "High") for f in (20, 59, 20, 20)], [None, None, None, None])
        self.assertIsNone(t2.feed(5, 60, "Low"))


if __name__ == "__main__":
    unittest.main()
