"""Pure-Python audio analysis primitives for Vooc.

Accepts normalized mono PCM samples from any capture backend. This module deliberately
does not pretend to capture Android audio itself; an Android PCM source can feed
AudioAnalyzer.process() without changing the FFT or band-mapping code.
"""
from __future__ import annotations

import math
from collections import deque
from typing import Iterable, List, Sequence, Tuple


def _next_power_of_two(value: int) -> int:
    if value < 2:
        return 2
    return 1 << (value - 1).bit_length()


def hann_window(size: int) -> List[float]:
    if size <= 1:
        return [1.0] * size
    return [0.5 - 0.5 * math.cos((2.0 * math.pi * i) / (size - 1))
            for i in range(size)]


def fft_magnitudes(samples: Sequence[float]) -> List[float]:
    """Return one-sided magnitudes for a real signal using radix-2 FFT."""
    n = _next_power_of_two(len(samples))
    if not samples:
        return [0.0]
    window = hann_window(len(samples))
    real = [0.0] * n
    imag = [0.0] * n
    for i, sample in enumerate(samples):
        real[i] = float(sample) * window[i]

    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j ^= bit
        if i < j:
            real[i], real[j] = real[j], real[i]
            imag[i], imag[j] = imag[j], imag[i]

    length = 2
    while length <= n:
        angle = -2.0 * math.pi / length
        wlen_r, wlen_i = math.cos(angle), math.sin(angle)
        half = length >> 1
        for start in range(0, n, length):
            wr, wi = 1.0, 0.0
            for k in range(half):
                even = start + k
                odd = even + half
                tr = wr * real[odd] - wi * imag[odd]
                ti = wr * imag[odd] + wi * real[odd]
                real[odd] = real[even] - tr
                imag[odd] = imag[even] - ti
                real[even] += tr
                imag[even] += ti
                wr, wi = wr * wlen_r - wi * wlen_i, wr * wlen_i + wi * wlen_r
        length <<= 1

    scale = 2.0 / max(1, len(samples))
    return [math.hypot(real[i], imag[i]) * scale for i in range(n // 2 + 1)]


def spectrum_bands(magnitudes: Sequence[float], sample_rate: int,
                   band_count: int = 64, min_hz: float = 30.0,
                   max_hz: float = 16000.0) -> List[float]:
    """Map FFT bins into logarithmically spaced, normalized visualizer bands."""
    if not magnitudes or sample_rate <= 0 or band_count <= 0:
        return [0.0] * max(0, band_count)
    nyquist = sample_rate / 2.0
    upper = min(float(max_hz), nyquist)
    lower = max(1.0, float(min_hz))
    if upper <= lower:
        return [0.0] * band_count
    bin_hz = nyquist / max(1, len(magnitudes) - 1)
    output = []
    for index in range(band_count):
        low = lower * ((upper / lower) ** (index / band_count))
        high = lower * ((upper / lower) ** ((index + 1) / band_count))
        first = max(1, int(low / bin_hz))
        last = min(len(magnitudes) - 1, max(first, int(math.ceil(high / bin_hz))))
        values = magnitudes[first:last + 1]
        value = max(values) if values else 0.0
        # Soft compression keeps quiet details visible without clipping peaks.
        output.append(min(1.0, math.sqrt(max(0.0, value))))
    return output


class BeatDetector:
    """Energy-flux beat detector with adaptive local thresholding."""
    def __init__(self, history_size: int = 43, sensitivity: float = 1.45,
                 refractory_seconds: float = 0.20):
        self.history = deque(maxlen=max(8, int(history_size)))
        self.sensitivity = max(1.0, float(sensitivity))
        self.refractory_seconds = max(0.05, float(refractory_seconds))
        self.last_beat_time = -1e9
        self.previous_energy = 0.0
        self.beat_count = 0

    def process(self, energy: float, timestamp: float) -> bool:
        energy = max(0.0, float(energy))
        flux = max(0.0, energy - self.previous_energy)
        self.previous_energy = energy
        baseline = (sum(self.history) / len(self.history)) if self.history else 0.0
        self.history.append(flux)
        threshold = max(0.015, baseline * self.sensitivity)
        detected = flux > threshold and timestamp - self.last_beat_time >= self.refractory_seconds
        if detected:
            self.last_beat_time = float(timestamp)
            self.beat_count += 1
        return detected


class AudioAnalyzer:
    """Streaming-friendly PCM analyzer. Feed mono float samples in [-1, 1]."""
    def __init__(self, sample_rate: int = 44100, fft_size: int = 1024,
                 band_count: int = 64):
        self.sample_rate = int(sample_rate)
        self.fft_size = _next_power_of_two(int(fft_size))
        self.band_count = int(band_count)
        self._buffer = deque(maxlen=self.fft_size)
        self.beat_detector = BeatDetector()
        self.last_bands = [0.0] * self.band_count
        self.last_rms = 0.0
        self.last_peak = 0.0
        self.beat = False

    def process(self, samples: Iterable[float], timestamp: float = 0.0) -> dict:
        for sample in samples:
            self._buffer.append(max(-1.0, min(1.0, float(sample))))
        if not self._buffer:
            return self.snapshot()
        data = list(self._buffer)
        if len(data) < self.fft_size:
            data = [0.0] * (self.fft_size - len(data)) + data
        squares = [sample * sample for sample in data]
        self.last_rms = math.sqrt(sum(squares) / len(squares))
        self.last_peak = max(abs(sample) for sample in data)
        magnitudes = fft_magnitudes(data)
        self.last_bands = spectrum_bands(magnitudes, self.sample_rate, self.band_count)
        self.beat = self.beat_detector.process(self.last_rms, timestamp)
        return self.snapshot()

    def snapshot(self) -> dict:
        return {"bands": list(self.last_bands), "rms": self.last_rms,
                "peak": self.last_peak, "beat": self.beat,
                "beat_count": self.beat_detector.beat_count}
