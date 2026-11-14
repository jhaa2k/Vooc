import json
import os
import tempfile
import unittest

from vooc.model import DEFAULT_LAYER_ORDER, LayerModel, ProjectModel, ProjectStore, hex_to_rgba, normalize_hex, rgba_to_hex
from vooc.property_schema import (ANCHOR, BLEND, MEASURE, MODULES, SCHEMA, SHAPES, defaults,
                                  field_spec, iter_fields)


class SchemaTests(unittest.TestCase):
    def test_modules_match_main_menu(self):
        original = ["COMPOSITION", "TEXT", "BARS", "PARTICLES", "IMAGE", "AUDIOPROVIDER", "BLUREFFECT",
                    "RGBSPLITEFFECT", "MOTIONBLUREFFECT", "MIRROREREFFECT", "CUSTOMSHADER"]
        self.assertEqual([m for m in MODULES if m in original], original)   # the 11 Avee modules, in order
        for extra in ("WAVEFORM", "SHAPE", "ZOOMBLUREFFECT", "BLOOMEFFECT", "WAVEEFFECT"):
            self.assertIn(extra, MODULES)
        self.assertEqual(set(SCHEMA), set(MODULES))

    def test_full_shared_dropdown_lists(self):
        self.assertEqual(len(BLEND), 19)
        self.assertEqual(len(ANCHOR), 17)
        self.assertGreaterEqual(len(MEASURE), 27)      # 27 Avee modes + analyzer extras
        for m in ("Kick", "Snare", "HiHat", "Downbeat", "CustomBand8", "Amplitude"):
            self.assertIn(m, MEASURE)
        self.assertEqual(len(SHAPES), 17)
        spec = field_spec("BARS", "General/Measure Engine/Measure Pos")
        self.assertEqual(spec["options"], MEASURE)

    def test_every_property_has_type_and_default(self):
        for module in MODULES:
            for path, spec in iter_fields(module):
                self.assertIn("type", spec, path)
                self.assertIn("default", spec, path)
                if spec["type"] == "slider":
                    self.assertLessEqual(spec["min"], spec["default"], path)
                    self.assertLessEqual(spec["default"], spec["max"], path)
                if spec["type"] == "color":
                    self.assertEqual(normalize_hex(spec["default"]), spec["default"], path)

    def test_particles_performance_limit_is_explicit(self):
        spec = field_spec("PARTICLES", "Overall Settings/Emitter/Count Limit")
        self.assertEqual(spec["warn_above"], 2000)


class ModelTests(unittest.TestCase):
    def test_hex_roundtrip(self):
        self.assertEqual(normalize_hex("#abc"), "#AABBCCFF")
        self.assertEqual(normalize_hex("#12345678"), "#12345678")
        self.assertEqual(rgba_to_hex(hex_to_rgba("#C6FF0080")), "#C6FF0080")
        self.assertEqual(normalize_hex("nope", "#000000FF"), "#000000FF")

    def test_coercion_clamps_and_validates(self):
        layer = LayerModel("BARS")
        layer.set("Bars Setup/Bars/Bar Spacing", 5)
        self.assertEqual(layer.get("Bars Setup/Bars/Bar Spacing"), 1.0)
        layer.set("Bars Setup/Bars/Shape Path", "NotAShape")
        self.assertEqual(layer.get("Bars Setup/Bars/Shape Path"), "Circle")

    def test_persistence_restores_every_property(self):
        project = ProjectModel()
        project.layer("TEXT").set("Content/Text/Text Input", "Hello %artist%")
        project.layer("BARS").set("Segment 2/Segment 2/Color From", "#11223344")
        project.playlist.append({"uri": "content://x", "title": "x"})
        project.track_index = 0
        with tempfile.TemporaryDirectory() as d:
            store = ProjectStore(d)
            store.save(project)
            restored = store.load()
        for a, b in zip(project.layers, restored.layers):
            self.assertEqual(a.module, b.module)
            self.assertEqual(a.values, b.values)
        self.assertEqual(restored.playlist, project.playlist)
        self.assertEqual(restored.track_index, 0)

    def test_old_save_is_migrated(self):
        data = {"layers": [{"module": "BARS", "values": {"Bars Setup/Bars/Bar Count": 32,
                                                         "Removed/Old/Key": 1}}]}
        project = ProjectModel.from_dict(data)
        self.assertEqual(project.layer("BARS").get("Bars Setup/Bars/Bar Count"), 32)
        self.assertEqual(set(l.module for l in project.layers), set(DEFAULT_LAYER_ORDER))
        self.assertEqual(len(project.layer("BARS").values), len(defaults("BARS")))

    def test_layer_order_is_back_to_front(self):
        order = [l.module for l in ProjectModel().layers]
        self.assertLess(order.index("IMAGE"), order.index("BARS"))
        self.assertLess(order.index("BLUREFFECT"), order.index("TEXT"))


if __name__ == "__main__":
    unittest.main()
