import unittest

from vooc.android_audio import AndroidMediaPlayerSource


class AndroidAudioSourceTests(unittest.TestCase):
    def test_backend_availability_is_safe_on_desktop(self):
        # The method must return a boolean and never make desktop imports fail.
        self.assertIsInstance(AndroidMediaPlayerSource.is_available(), bool)

    def test_source_starts_unloaded(self):
        source = AndroidMediaPlayerSource(lambda samples, timestamp: None)
        self.assertIsNone(source.player)
        self.assertFalse(source.is_playing())
        self.assertEqual(source.position_ms(), 0)
        self.assertEqual(source.duration_ms(), 0)
        source.release()


if __name__ == "__main__":
    unittest.main()
