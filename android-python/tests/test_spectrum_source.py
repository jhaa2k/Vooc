import math
import os
import struct
import tempfile
import unittest
import wave

from vooc.android_fx import eq_gain_at
from vooc.spectrum_source import BANDS, FPS, _from_bytes, analyse_wav


def _write_tone(path, freq, seconds=1.5, rate=22050, kick_every=0.5):
    frames = []
    for i in range(int(seconds * rate)):
        t = i / rate
        v = 0.3 * math.sin(2 * math.pi * freq * t)
        k = t % kick_every
        if k < 0.12:
            v += 0.7 * math.sin(2 * math.pi * 55 * k) * math.exp(-k * 25)
        frames.append(struct.pack("<h", int(max(-1, min(1, v)) * 32000)))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(frames))


class SpectrumSourceTests(unittest.TestCase):
    def test_wav_analysis_follows_the_music(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "tone.wav")
            _write_tone(path, 4000)
            tl = analyse_wav(path)
        self.assertAlmostEqual(tl.duration, 1.5, delta=0.1)
        bands, rms = tl.sample(0.3)
        self.assertEqual(len(bands), BANDS)
        self.assertGreater(rms, 0)
        # 4 kHz tone: high bands must be louder than the band halfway in between
        peak = max(range(BANDS), key=lambda i: bands[i])
        self.assertGreater(peak, BANDS // 2)
        self.assertTrue(tl.beat_between(0.9, 1.15) or tl.beat_between(0.4, 0.65))

    def test_decoder_byte_format(self):
        n = 5
        raw = (bytes([153] * BANDS) + bytes([255, 128])) * n     # 153 -> -40 dB
        tl = _from_bytes(raw)
        self.assertEqual(tl.count, n)
        self.assertAlmostEqual(tl.duration, n / FPS)
        db, rms, peak = tl.sample_db(0.01)
        self.assertAlmostEqual(float(db[0]), -40.0, delta=0.3)
        self.assertAlmostEqual(rms, 1.0, places=3)
        self.assertAlmostEqual(peak, 128 / 255.0, places=3)

    def test_eq_interpolation(self):
        freqs = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
        gains = [0, 0, 0, 0, 0, 6, 0, 0, 0, 0]
        self.assertAlmostEqual(eq_gain_at(1000, freqs, gains), 6)
        self.assertAlmostEqual(eq_gain_at(10, freqs, gains), 0)
        mid = eq_gain_at(math.sqrt(1000 * 2000), freqs, gains)
        self.assertAlmostEqual(mid, 3, places=3)


if __name__ == "__main__":
    unittest.main()


class StreamingTimelineTest(unittest.TestCase):
    def test_append_and_finish(self):
        from vooc.spectrum_source import SpectrumTimeline, BANDS
        tl = SpectrumTimeline.empty()
        self.assertEqual(tl.sample(0.0), (None, 0.0))
        frame = bytes([153] * BANDS + [255, 0])
        tl.append_bytes(frame * 300)
        self.assertEqual(tl.count, 300)
        db, rms, _ = tl.sample_db(1.0)
        self.assertAlmostEqual(float(db[0]), -40.0, delta=0.3)
        self.assertAlmostEqual(rms, 1.0, places=3)
        # beyond decoded range while streaming -> no data yet
        self.assertEqual(tl.sample(100.0), (None, 0.0))
        tl.append_bytes(frame * 1000)
        self.assertEqual(tl.count, 1300)
        tl.finish()
        self.assertTrue(tl.complete)
        self.assertEqual(len(tl.rms), 1300)
        bands, _ = tl.sample(100.0)
        self.assertEqual(len(bands), BANDS)
