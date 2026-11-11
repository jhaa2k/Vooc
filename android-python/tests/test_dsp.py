import math
import unittest

from vooc.dsp import AudioAnalyzer, fft_magnitudes, spectrum_bands


class DSPTests(unittest.TestCase):
    def test_fft_finds_sine_peak(self):
        size = 1024
        rate = 1024
        frequency = 64
        samples = [math.sin(2 * math.pi * frequency * i / rate) for i in range(size)]
        magnitudes = fft_magnitudes(samples)
        peak = max(range(1, len(magnitudes)), key=lambda i: magnitudes[i])
        self.assertEqual(peak, frequency)

    def test_band_mapping_returns_bounded_values(self):
        values = spectrum_bands([0.0, 0.2, 0.5, 1.0, 0.3], 8000, 16)
        self.assertEqual(len(values), 16)
        self.assertTrue(all(0.0 <= value <= 1.0 for value in values))

    def test_analyzer_reports_rms_and_peak(self):
        analyzer = AudioAnalyzer(sample_rate=8000, fft_size=256, band_count=16)
        result = analyzer.process([0.5] * 256, timestamp=1.0)
        self.assertAlmostEqual(result["rms"], 0.5, places=3)
        self.assertAlmostEqual(result["peak"], 0.5, places=3)
        self.assertEqual(len(result["bands"]), 16)


if __name__ == "__main__":
    unittest.main()
