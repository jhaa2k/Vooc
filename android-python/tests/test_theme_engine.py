import unittest

from vooc import theme_engine as TE

BASE = {"bg": "#0C0E0A", "surface": "#12150F", "surface1": "#181C14", "surface2": "#1E2319", "surface3": "#262C20",
        "outline": "#3A4232", "outline_var": "#2A3024", "on_surface": "#E8EDE0", "on_var": "#A3AD98",
        "primary": "#C6FF00", "on_primary": "#152000", "primary_container": "#2F4400",
        "on_primary_container": "#DDFF8A", "secondary": "#5BFFB0", "secondary_container": "#123B2A",
        "on_secondary_container": "#B6FFD9", "error": "#FFB4AB", "scrim": "#000000"}


class ThemeTest(unittest.TestCase):
    def test_seed_schemes_readable(self):
        for dark in (True, False):
            for seed in ("#FF0066", "#2266FF", "#C6FF00"):
                sc = TE.from_seed(seed, dark)
                self.assertEqual(set(sc), set(BASE))
                self.assertGreater(TE.contrast_ratio(sc["on_surface"], sc["bg"]), 7)

    def test_high_contrast_and_vision(self):
        sc = TE.build_scheme(True, BASE, None, "High", "None")
        self.assertEqual(sc["bg"], "#000000")
        self.assertGreaterEqual(TE.contrast_ratio(sc["primary"], sc["bg"]), 7)
        light = TE.build_scheme(False, TE.from_seed("#C6FF00", False), None, "High")
        self.assertGreaterEqual(TE.contrast_ratio(light["primary"], light["bg"]), 7)
        mono = TE.build_scheme(True, BASE, None, "Standard", "Monochrome")
        r, g, b = mono["primary"][1:3], mono["primary"][3:5], mono["primary"][5:7]
        self.assertTrue(r == g == b)
        self.assertNotEqual(TE.build_scheme(True, BASE, vision="Protanopia")["primary"], BASE["primary"])


if __name__ == "__main__":
    unittest.main()
