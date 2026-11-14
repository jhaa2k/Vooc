import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from vooc import exporter as ex  # noqa: E402


class ExportPlanTest(unittest.TestCase):
    def test_new_options(self):
        self.assertIn("24", ex.FPS_OPTIONS)
        self.assertIn("Custom", ex.RESOLUTIONS)
        self.assertEqual(ex.CODECS["VP9 (WebM)"][3], "webm")

    def test_custom_resolution_even_and_clamped(self):
        p = ex.plan_export({"resolution": "Custom", "custom_w": 1001, "custom_h": 99999}, 60)
        self.assertEqual((p["width"], p["height"]), (1000, 4096))
        p = ex.plan_export({"resolution": "Custom", "custom_w": 10, "custom_h": 333}, 60)
        self.assertEqual((p["width"], p["height"]), (128, 332))

    def test_codec_fallback_and_container(self):
        p = ex.plan_export({"codec": "VP9 (WebM)"}, 60, available=lambda c: False)
        self.assertEqual(p["codec"], "H.264 (AVC)")
        self.assertEqual(p["ext"], ".mp4")
        self.assertTrue(any("not available" in n for n in p["notes"]))
        p = ex.plan_export({"codec": "VP9 (WebM)"}, 60, available=lambda c: True)
        self.assertEqual((p["ext"], p["container"]), (".webm", "webm"))

    def test_trim_and_fades(self):
        p = ex.plan_export({"trim_start": 10, "trim_end": 40, "fade_in": 2, "fade_out": 100, "fps": 30}, 120)
        self.assertAlmostEqual(p["duration"], 30)
        self.assertEqual(p["frames"], 900)
        self.assertEqual(p["fade_out"], 15)       # clamped to half the clip
        self.assertAlmostEqual(ex.video_fade(p, 1.0), 0.5)
        self.assertAlmostEqual(ex.video_fade(p, 10.0), 1.0)
        self.assertAlmostEqual(ex.video_fade(p, 30.0), 0.0)
        p = ex.plan_export({"trim_end": 500}, 120)   # beyond the song = song end
        self.assertAlmostEqual(p["duration"], 120)

    def test_manual_bitrate_and_advanced(self):
        p = ex.plan_export({"bitrate_mbps": 7.5, "keyframe_s": 2, "bframes": 2, "profile": "High"}, 60)
        self.assertEqual(p["bitrate"], 7500000)
        self.assertEqual((p["keyframe_s"], p["bframes"], p["profile"]), (2, 2, "High"))
        p = ex.plan_export({"codec": "H.265 (HEVC)", "profile": "High"}, 60, available=lambda c: True)
        self.assertEqual(p["profile"], "Auto")

    def test_4gb_limit(self):
        p = ex.plan_export({"resolution": "4K 2160p", "fps": 60, "quality": "High"}, 3 * 3600)
        self.assertLessEqual(p["estimate_bytes"], ex.LIMIT_BYTES)

    def test_i420_matrix(self):
        w, h = 4, 2
        white = bytes([255, 255, 255, 255]) * (w * h)
        for m in ("BT.601", "BT.709"):
            y = np.frombuffer(ex.rgba_to_i420(white, w, h, matrix=m), np.uint8)
            self.assertEqual(int(y[0]), 235)
            self.assertTrue(all(abs(int(c) - 128) <= 1 for c in y[w * h:]))
        red = bytes([255, 0, 0, 255]) * (w * h)
        y601 = ex.rgba_to_i420(red, w, h, matrix="BT.601")[0]
        y709 = ex.rgba_to_i420(red, w, h, matrix="BT.709")[0]
        self.assertEqual((y601, y709), (82, 63))
        half = ex.rgba_to_i420(white, w, h, gain=0.5)[0]
        self.assertTrue(120 <= half <= 130)
        black = ex.rgba_to_i420(white, w, h, gain=0.0)[0]
        self.assertEqual(black, 16)

    def test_output_name(self):
        plan = {"width": 1920, "height": 1080, "fps": 30, "codec": "H.264 (AVC)"}
        name = ex.output_name("{artist}-{title}_{res}_{fps}_{codec}_{n}", {"title": "My/Song?", "artist": "A B"},
                              plan, "Neon", 3)
        self.assertEqual(name, "A B-My_Song_1920x1080_30_H.264_03")
        self.assertNotIn("/", ex.output_name("{title}", {"title": "a/b"}))
        self.assertEqual(ex.output_name("{template}", {}, plan, ""), "Vooc_video")
        self.assertRegex(ex.output_name(None, {"title": "x"}), r"^Vooc_x_\d{8}_\d{6}$")


if __name__ == "__main__":
    unittest.main()
