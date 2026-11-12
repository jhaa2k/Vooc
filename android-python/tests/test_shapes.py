import unittest

import numpy as np

from vooc.engine import shapes
from vooc.property_schema import SHAPES


class ShapeTests(unittest.TestCase):
    def test_every_shape_samples(self):
        for name in SHAPES:
            pts, nrm, closed = shapes.sample_path(name, 37, np.zeros(64))
            self.assertEqual(pts.shape, (37, 2), name)
            self.assertTrue(np.allclose(np.linalg.norm(nrm, axis=1), 1.0, atol=1e-5), name)
            self.assertLessEqual(np.abs(pts).max(), 1.0001, name)

    def test_circle_normals_point_outward(self):
        pts, nrm, closed = shapes.sample_path("Circle", 64)
        self.assertTrue(closed)
        self.assertTrue((np.sum(pts * nrm, axis=1) > 0.9).all())

    def test_mask_polygon_for_open_shapes_is_rectangle(self):
        self.assertEqual(len(shapes.mask_polygon("Line")), 4)


if __name__ == "__main__":
    unittest.main()
