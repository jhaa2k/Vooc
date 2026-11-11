"""Bridge between a PCM capture backend, DSP analyzer and UI callback.

A platform audio backend should call submit() with normalized mono PCM frames.
The callback is dispatched through the scheduler supplied by the app (Kivy Clock
on Android), so widgets are never touched directly from an audio worker thread.
"""
import threading
import time

from .dsp import AudioAnalyzer


class PCMAnalysisPipeline:
    def __init__(self, on_result, scheduler=None, sample_rate=44100,
                 fft_size=1024, band_count=64, min_interval=1.0 / 30.0):
        self.analyzer = AudioAnalyzer(sample_rate, fft_size, band_count)
        self.on_result = on_result
        self.scheduler = scheduler or (lambda callback: callback())
        self.min_interval = max(0.0, float(min_interval))
        self._lock = threading.Lock()
        self._last_dispatch = 0.0
        self._closed = False
        self.frames_processed = 0

    def submit(self, samples, timestamp=None):
        """Submit one PCM frame. Safe to call from a capture worker thread."""
        if self._closed:
            return None
        now = time.monotonic() if timestamp is None else float(timestamp)
        with self._lock:
            if self._closed:
                return None
            result = self.analyzer.process(samples, now)
            self.frames_processed += 1
            if now - self._last_dispatch < self.min_interval:
                return result
            self._last_dispatch = now
            snapshot = result

        def deliver():
            if not self._closed:
                self.on_result(snapshot)

        self.scheduler(deliver)
        return result

    def close(self):
        with self._lock:
            self._closed = True
