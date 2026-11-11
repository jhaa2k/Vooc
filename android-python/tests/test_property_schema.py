import unittest
from vooc.property_schema import SCHEMA, MEASURE, BLEND, ANCHOR, SHAPES

class AdvancedPropertySchemaTests(unittest.TestCase):
    def test_all_requested_categories_exist(self):
        expected = {"COMPOSITION","IMAGE","TEXT","BARS / SEGMENTS","PARTICLES","AUDIOPROVIDER","BLUR EFFECT","RGBSPLITEFFECT","MOTIONBLUREFFECT","MIRROR EFFECT","CUSTOM SHADER"}
        self.assertEqual(set(SCHEMA), expected)

    def test_full_shared_dropdown_lists(self):
        self.assertIn("PitchFrequencyResponsive", MEASURE)
        self.assertIn("TrackIntroOutroTrigger", MEASURE)
        self.assertIn("Luminosity", BLEND)
        self.assertIn("stretch", ANCHOR)
        self.assertIn("WaveformPath", SHAPES)

    def test_every_property_has_type_and_default(self):
        for category, sections in SCHEMA.items():
            for section, groups in sections.items():
                for group, properties in groups.items():
                    for name, spec in properties.items():
                        with self.subTest(category=category, section=section, group=group, name=name):
                            self.assertIn("type", spec)
                            self.assertIn("default", spec)

    def test_particles_performance_limit_is_explicit(self):
        count = SCHEMA["PARTICLES"]["Overall Settings"]["Emitter"]["Count Limit"]
        self.assertEqual(count["max"], 5000)

if __name__ == "__main__":
    unittest.main()
