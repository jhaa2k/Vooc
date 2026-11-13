import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

from vooc.model import ProjectModel
from vooc import templates, palettes, exporter


class TemplateTests(unittest.TestCase):
    def test_every_builtin_applies(self):
        for name in templates.BUILTINS:
            p = ProjectModel()
            audio = p.layer("AUDIOPROVIDER")
            templates.apply_template(p, templates.builtin(name))
            self.assertIs(p.layer("AUDIOPROVIDER"), audio, name)

    def test_store_roundtrip_and_import(self):
        with tempfile.TemporaryDirectory() as d:
            store = templates.TemplateStore(d)
            p = ProjectModel()
            p.layer("BARS").set("Bars Setup/Bars/Bar Count", 77)
            store.save(p, "Mine")
            self.assertIn("Mine", store.names())
            q = ProjectModel()
            templates.apply_template(q, store.load("Mine"))
            self.assertEqual(q.layer("BARS").get("Bars Setup/Bars/Bar Count"), 77)
            ext = Path(d) / "other.viz"
            ext.write_text(json.dumps(templates.to_template(p, "Mine")))
            self.assertEqual(store.import_file(ext), "Mine 2")

    def test_invalid_template_rejected(self):
        with self.assertRaises(ValueError):
            templates.validate({"nope": 1})


class PaletteTests(unittest.TestCase):
    def test_extract_from_image(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "a.png"
            im = Image.new("RGB", (64, 64), (255, 0, 0))
            im.paste((0, 0, 255), (32, 0, 64, 64))
            im.save(f)
            cols = palettes.extract_palette(str(f))
            self.assertEqual(len(cols), 4)
            for c in cols:
                self.assertRegex(c, r"^#[0-9A-F]{6}$")

    def test_apply_palette(self):
        p = ProjectModel()
        palettes.apply_palette(p, ["#FF0000FF", "#00FF00FF", "#0000FFFF"])
        self.assertEqual(p.layer("COMPOSITION").to_dict()["module"], "COMPOSITION")


class ExportPlanTests(unittest.TestCase):
    def test_frame_sizes(self):
        self.assertEqual(exporter.frame_size("FHD 1080p", "16:9"), (1920, 1080))
        w, h = exporter.frame_size("HD 720p", "4:3")
        self.assertEqual(h, 720)
        self.assertEqual(w % 16, 0)

    def test_4gb_cap(self):
        plan = exporter.plan_export({"resolution": "4K 2160p", "fps": 60}, 3 * 3600)
        self.assertLessEqual(plan["estimate_bytes"], exporter.LIMIT_BYTES)
        self.assertTrue(plan["notes"])

    def test_i420_size_and_values(self):
        w, h = 32, 16
        rgba = np.zeros((h, w, 4), np.uint8)
        rgba[..., :3] = 255
        out = exporter.rgba_to_i420(rgba.tobytes(), w, h)
        self.assertEqual(len(out), w * h * 3 // 2)
        self.assertEqual(out[0], 235)


if __name__ == "__main__":
    unittest.main()
