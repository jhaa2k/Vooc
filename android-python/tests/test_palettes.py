import unittest

import numpy as np

from vooc import palettes as P


def _img():
    rng = np.random.default_rng(1)
    reds = np.tile([0.9, 0.1, 0.1], (500, 1))
    blues = np.tile([0.1, 0.2, 0.9], (300, 1))
    greys = np.tile([0.4, 0.4, 0.4], (200, 1))
    return np.clip(np.vstack([reds, blues, greys]) + rng.normal(0, 0.02, (1000, 3)), 0, 1)


class PaletteTest(unittest.TestCase):
    def test_methods_and_counts(self):
        px = _img()
        for m in P.METHODS:
            for n in (3, 8):
                cols = P.extract_colors(None, m, n, pixels=px)
                self.assertLessEqual(len(cols), n)
                self.assertTrue(all(c.startswith("#") and len(c) == 7 for c in cols))
        top = P.extract_colors(None, "Median Cut", 3, pixels=px)[0]
        self.assertGreater(int(top[1:3], 16), 150)      # red dominates
        self.assertEqual(len(P.extract_palette(None, method="Vibrant", pixels=px)), 4)

    def test_library_roundtrip(self):
        lib = {"Mine": ["#112233", "#445566", "#778899", "#AABBCC"]}
        got = P.import_palettes(P.export_palettes(lib), existing=lib)
        self.assertEqual(list(got.values())[0], lib["Mine"])
        self.assertEqual(list(got), ["Mine 2"])
        self.assertEqual(P.import_palettes('{"X": ["#ff0000"]}')["X"], ["#FF0000"] * 4)
        with self.assertRaises(ValueError):
            P.import_palettes('{"X": ["nothex"]}')
        self.assertEqual(P.unique_name("Acid", {}), "Acid 2")


if __name__ == "__main__":
    unittest.main()
