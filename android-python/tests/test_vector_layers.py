import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("KIVY_NO_ARGS", "1")

from vooc.engine import geometry as vl  # noqa: E402
from vooc.fx_library import LIBRARY  # noqa: E402
from vooc.property_schema import SCHEMA, defaults  # noqa: E402


class GeometryTests(unittest.TestCase):
    def test_svg_path_parse(self):
        polys = vl.parse_path("M 0 0 L 100 0 L 50 86 Z")
        self.assertEqual(len(polys), 1)
        p = polys[0]
        self.assertTrue(np.allclose(p[0], p[-1]))
        self.assertLessEqual(np.abs(p).max(), 1.0001)
        curves = vl.parse_path("M0,0 C 10,20 30,20 40,0 q 10 -10 20 0 h 5 v 5 z")
        self.assertGreater(len(curves[0]), 20)

    def test_shapes_and_morph(self):
        v = defaults("SHAPE")
        for kind in ("Rectangle", "Ellipse", "Polygon", "Star", "Line", "Path"):
            pts, closed = vl.shape_outline(kind, v, 100, 80)
            self.assertGreaterEqual(len(pts), 2, kind)
            self.assertEqual(closed, kind not in ("Line",), kind)
        v["Shape/Geometry/Corner Radius"] = 0.5
        rr, _ = vl.shape_outline("Rectangle", v, 100, 80)
        self.assertGreater(len(rr), 4)
        a = vl.resample_closed(vl.shape_outline("Star", v, 100, 100)[0], 128)
        b = vl.resample_closed(vl.shape_outline("Ellipse", v, 100, 100)[0], 128)
        self.assertEqual(a.shape, b.shape)

    def test_stroke_and_dash(self):
        line = np.array([[0, 0], [100, 0]], np.float32)
        q = vl.thick_polyline(line, 10)
        self.assertEqual(q.shape, (1, 4, 2))
        self.assertAlmostEqual(float(q[0, :, 1].max()), 5.0, places=4)
        pieces = vl.dashed(line, 10, 10)
        self.assertEqual(len(pieces), 5)

    def test_fill_gradient(self):
        poly = np.array([(-1, -1), (1, -1), (1, 1), (-1, 1)], np.float32) * 50
        q = vl.fan_quads(poly, (0, 0))
        cols = vl.gradient_colors(q, "Linear Gradient", (1, 0, 0, 1), (0, 0, 1, 1), 0, 50)
        self.assertEqual(cols.shape, (4, 4, 4))
        self.assertGreater(cols[..., 2].max(), 0.9)


class EffectLibraryTests(unittest.TestCase):
    def test_every_effect_has_schema_and_uniforms(self):
        for name, fx in LIBRARY.items():
            self.assertIn(name, SCHEMA)
            self.assertIn("vec4 effect_main", fx["glsl"])
            unis = [p[1] for p in fx["params"]]
            self.assertIn(fx["main"], unis, name)
            for u in unis:
                self.assertIn("uniform", fx["glsl"])
                self.assertIn(u, fx["glsl"], (name, u))
            d = defaults(name)
            self.assertIn("Effect Stack/Mix/Effect Mix", d)
            self.assertIn("Effect Stack/Audio Reactivity/React To", d)
        self.assertGreaterEqual(len(LIBRARY), 12)


if __name__ == "__main__":
    unittest.main()
