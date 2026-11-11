import unittest

from vooc.pcm_pipeline import PCMAnalysisPipeline


class PipelineTests(unittest.TestCase):
    def test_submit_delivers_analysis(self):
        delivered = []
        scheduled = []
        pipeline = PCMAnalysisPipeline(
            delivered.append,
            scheduler=lambda callback: scheduled.append(callback),
            sample_rate=8000,
            fft_size=128,
            band_count=8,
            min_interval=0,
        )
        result = pipeline.submit([0.25] * 128, timestamp=1.0)
        self.assertIsNotNone(result)
        self.assertEqual(pipeline.frames_processed, 1)
        self.assertEqual(len(scheduled), 1)
        scheduled[0]()
        self.assertEqual(len(delivered), 1)
        self.assertAlmostEqual(delivered[0]["rms"], 0.25, places=3)
        pipeline.close()

    def test_closed_pipeline_ignores_frames(self):
        delivered = []
        pipeline = PCMAnalysisPipeline(delivered.append, min_interval=0)
        pipeline.close()
        self.assertIsNone(pipeline.submit([0.1] * 128, timestamp=1.0))
        self.assertEqual(delivered, [])


if __name__ == "__main__":
    unittest.main()
