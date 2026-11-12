"""Fast numpy spectrum analysis shared by every audio provider."""
import math
import threading
import time
from collections import deque

import numpy as np

from ..dsp import BeatDetector

MAX_BANDS = 256


class SpectrumAnalyzer:
    """Accepts mono (or stereo) float PCM in [-1, 1]; produces a snapshot dict."""

    def __init__(self, sample_rate=44100, fft_size=2048):
        self.sample_rate = int(sample_rate)
        self.fft_size = int(fft_size)
        self.window = np.hanning(self.fft_size).astype(np.float32)
        self.buffer_l = np.zeros(self.fft_size, dtype=np.float32)
        self.buffer_r = np.zeros(self.fft_size, dtype=np.float32)
        self.beat = BeatDetector(sensitivity=1.35, refractory_seconds=0.22)
        self._norm = 1e-3
        self._lock = threading.Lock()
        self.snapshot = empty_snapshot()

    def set_sample_rate(self, rate):
        rate = int(rate)
        if rate > 0 and rate != self.sample_rate:
            self.sample_rate = rate

    def _push(self, buf, data):
        n = len(data)
        if n >= self.fft_size:
            buf[:] = data[-self.fft_size:]
        else:
            buf[:-n] = buf[n:]
            buf[-n:] = data

    def process(self, samples, timestamp=None, gain=1.0, right=None):
        """samples: mono array (or left channel if `right` is given)."""
        now = time.monotonic() if timestamp is None else timestamp
        left = np.asarray(samples, dtype=np.float32) * gain
        if left.size == 0:
            return self.snapshot
        r = left if right is None else np.asarray(right, dtype=np.float32) * gain
        with self._lock:
            self._push(self.buffer_l, left)
            self._push(self.buffer_r, r)
            mono = (self.buffer_l + self.buffer_r) * 0.5
            spec_mono = self._spectrum(mono)
            spec_l = self._spectrum(self.buffer_l) if right is not None else spec_mono
            spec_r = self._spectrum(self.buffer_r) if right is not None else spec_mono
            freqs = np.fft.rfftfreq(self.fft_size, 1.0 / self.sample_rate)
            # adaptive normalization (slow-decaying max) keeps any source in range
            peak_mag = float(spec_mono.max()) if spec_mono.size else 0.0
            self._norm = max(peak_mag, self._norm * 0.995, 1e-4)
            bands = self._bands(spec_mono, freqs)
            rms = float(np.sqrt(np.mean(mono * mono)))
            peak = float(np.max(np.abs(mono)))
            low = self._range(spec_mono, freqs, 20, 250)
            mid = self._range(spec_mono, freqs, 250, 4000)
            high = self._range(spec_mono, freqs, 4000, 16000)
            dom = float(freqs[int(np.argmax(spec_mono[1:])) + 1]) if spec_mono.size > 2 else 0.0
            is_beat = self.beat.process(low, now)
            self.snapshot = {
                "bands": bands,
                "bands_left": self._bands(spec_l, freqs) if right is not None else bands,
                "bands_right": self._bands(spec_r, freqs) if right is not None else bands,
                "waveform": mono[-512:].copy(),
                "rms": min(1.0, rms), "peak": min(1.0, peak),
                "bass": low, "mid": mid, "treble": high,
                "dominant_hz": dom, "beat": is_beat, "time": now,
            }
            return self.snapshot

    def _spectrum(self, data):
        return np.abs(np.fft.rfft(data * self.window)).astype(np.float32)

    def _bands(self, spec, freqs):
        edges = np.geomspace(30.0, min(16000.0, self.sample_rate / 2.0 - 1), MAX_BANDS + 1)
        idx = np.searchsorted(freqs, edges)
        out = np.zeros(MAX_BANDS, dtype=np.float32)
        for i in range(MAX_BANDS):
            lo, hi = idx[i], max(idx[i] + 1, idx[i + 1])
            out[i] = spec[lo:hi].max() if lo < spec.size else 0.0
        out = np.sqrt(np.clip(out / self._norm, 0.0, 1.0))
        return out

    def _range(self, spec, freqs, lo, hi):
        mask = (freqs >= lo) & (freqs < hi)
        if not mask.any():
            return 0.0
        return float(min(1.0, math.sqrt(spec[mask].mean() / self._norm * 2.0)))


def empty_snapshot():
    zeros = np.zeros(MAX_BANDS, dtype=np.float32)
    return {"bands": zeros, "bands_left": zeros, "bands_right": zeros,
            "waveform": np.zeros(512, dtype=np.float32), "rms": 0.0, "peak": 0.0,
            "bass": 0.0, "mid": 0.0, "treble": 0.0, "dominant_hz": 0.0,
            "beat": False, "time": 0.0}


class SnapshotDelay:
    """Ring buffer used by Audio Offset: returns the snapshot from `delay_ms` ago."""

    def __init__(self, seconds=1.5):
        self.items = deque()
        self.seconds = seconds

    def push(self, snap):
        self.items.append(snap)
        cutoff = snap["time"] - self.seconds
        while self.items and self.items[0]["time"] < cutoff:
            self.items.popleft()

    def get(self, delay_ms):
        if not self.items:
            return empty_snapshot()
        if delay_ms <= 0:
            return self.items[-1]
        target = self.items[-1]["time"] - delay_ms / 1000.0
        for snap in reversed(self.items):
            if snap["time"] <= target:
                return snap
        return self.items[0]
