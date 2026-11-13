"""TimelineHub: the audio side of the render engine for the in-app player.

The player (native MediaPlayer on Android, Kivy SoundLoader on desktop) plays
the file while ``SpectrumDecoder`` decodes it on its own thread into a
``SpectrumTimeline`` (64 log bands + RMS per ~23 ms). Every frame the hub reads
the timeline at the *current playback position*, so the visuals can never
drift from the audio: seeking, pausing and resuming all stay in sync.

It exposes the same interface as ``AudioHub`` (update / beat / metadata /
position / duration / is_playing) so ``SceneView`` does not care where the
audio comes from. When AUDIOPROVIDER > Audio Source is Microphone or System
Audio, the hub delegates to the live ``AudioHub`` providers instead.

Snapshot fields: bands (256), bands_left/right, waveform (512), rms, peak,
bass, mid, treble, dominant_hz, beat, time.
"""
import math
import time

import numpy as np

from .analysis import MAX_BANDS, empty_snapshot
from ..dsp import BeatDetector

P = "General/Routing/"
S = "Sync & Offset/Timing/"

# timeline band edges (same log spacing as SpectrumDecoder: 30 Hz .. 16 kHz)
F_LO, F_HI = 30.0, 16000.0


def band_hz(i, bands):
    return F_LO * (F_HI / F_LO) ** ((i + 0.5) / float(bands))


class TimelineHub:
    def __init__(self, settings_layer, timeline=None, position=None, playing=None, duration=None,
                 on_error=None):
        self.layer = settings_layer
        self._timeline = timeline or (lambda: None)
        self._position = position or (lambda: 0.0)
        self._playing = playing or (lambda: False)
        self._duration = duration or (lambda: 0.0)
        self.on_error = on_error or (lambda msg: None)
        self.metadata = {"title": "", "artist": "", "album": "", "art": ""}
        self.current = empty_snapshot()
        self.beat = False
        self._last_pos = 0.0
        self._grid_index = None
        self._smooth = np.zeros(MAX_BANDS, np.float32)
        self._phase = 0.0
        self._detector = BeatDetector(sensitivity=1.3, refractory_seconds=0.22)
        self._live = None
        self._live_kind = None
        self._x256 = (np.arange(MAX_BANDS, dtype=np.float32) + 0.5) / MAX_BANDS
        self.fallback = True       # animate gently when playing without data yet

    # ------------------------------------------------------------ transport proxies
    def position(self):
        if self._live is not None:
            return self._live.position()
        try:
            return float(self._position() or 0.0)
        except Exception:
            return 0.0

    def duration(self):
        if self._live is not None:
            return self._live.duration()
        try:
            return float(self._duration() or 0.0)
        except Exception:
            return 0.0

    def is_playing(self):
        if self._live is not None:
            return True
        try:
            return bool(self._playing())
        except Exception:
            return False

    # ------------------------------------------------------------ live sources
    def _ensure_live(self):
        kind = self.layer.get(P + "Audio Source")
        if kind == "File":
            if self._live is not None:
                try:
                    self._live.release()
                except Exception:
                    pass
                self._live, self._live_kind = None, None
            return None
        if self._live is None or self._live_kind != kind:
            if self._live is not None:
                self._live.release()
            try:
                from .hub import AudioHub
                self._live = AudioHub(self.layer, on_error=self.on_error)
                self._live_kind = kind
            except Exception as exc:     # missing permission / unsupported device
                self.on_error("%s is not available: %s" % (kind, exc))
                self.layer.set(P + "Audio Source", "File")
                self._live, self._live_kind = None, None
        return self._live

    def release(self):
        if self._live is not None:
            self._live.release()
            self._live = None

    # ------------------------------------------------------------ per frame
    def update(self):
        live = self._ensure_live()
        if live is not None:
            snap = live.update()
            self.current, self.beat = snap, live.beat
            return snap
        if not self.layer.get(P + "Visible"):
            self.current, self.beat = empty_snapshot(), False
            return self.current
        pos = self.position()
        playing = self.is_playing()
        offset = float(self.layer.get(S + "Audio Offset")) / 1000.0
        t = pos - offset
        gain = float(self.layer.get(P + "Preamp"))
        tl = self._timeline()
        row, rms = (None, 0.0)
        if tl is not None:
            row, rms = tl.sample(t)
        if row is None:
            if playing and self.fallback:
                row, rms = self._synthetic(t), 0.35
            else:
                row = np.zeros(64, np.float32)
        src = np.asarray(row, np.float32)
        bands_src = len(src)
        # timeline bands -> 256 engine bands; both are log-spaced 30 Hz .. 16 kHz
        xs = (np.arange(bands_src, dtype=np.float32) + 0.5) / bands_src
        bands = np.interp(self._x256, xs, src).astype(np.float32) * gain
        np.clip(bands, 0.0, 1.0, out=bands)
        if not playing:
            # paused: decay softly instead of freezing
            self._smooth *= 0.86
            bands = np.minimum(bands, self._smooth) if tl is not None else self._smooth.copy()
        self._smooth = bands
        # bass / mid / treble from real frequencies of the timeline bands
        hz = np.array([band_hz(i, bands_src) for i in range(bands_src)], np.float32)
        def avg(lo, hi):
            m = (hz >= lo) & (hz < hi)
            return float(min(1.0, src[m].mean() * gain)) if m.any() else 0.0
        bass, mid, treble = avg(20, 250), avg(250, 4000), avg(4000, 20000)
        dominant = float(hz[int(np.argmax(src))]) if src.any() else 0.0
        rms = float(min(1.0, rms * gain)) if playing else 0.0
        peak = float(bands.max()) if bands.size else 0.0
        # waveform: a smooth wave whose amplitude/shape follows the music
        self._phase += 0.12 + mid * 0.4
        k = np.linspace(0, 2 * math.pi, 512, dtype=np.float32)
        wave = (np.sin(k * 3 + self._phase) * (0.5 * bass + 0.2)
                + np.sin(k * 11 + self._phase * 1.7) * (0.35 * mid)
                + np.sin(k * 29 + self._phase * 2.3) * (0.2 * treble)) * rms * 1.6
        snap = {"bands": bands, "bands_left": bands, "bands_right": bands,
                "waveform": wave.astype(np.float32), "rms": rms, "peak": peak,
                "bass": bass, "mid": mid, "treble": treble, "dominant_hz": dominant,
                "beat": False, "time": time.monotonic()}
        beat = False
        if playing:
            if tl is not None and getattr(tl, "_beats", None) and t >= self._last_pos:
                beat = tl.beat_between(self._last_pos, t)
            elif tl is None or not getattr(tl, "_beats", None):
                source = self.layer.get(P + "Measure Source")
                value = {"Bass": bass, "Mid": mid, "Treble": treble, "Volume": rms, "Peak": peak}.get(source, bass)
                beat = self._detector.process(value, snap["time"])
        self._last_pos = t
        beat = beat or self._bpm_beat(pos, playing)
        snap["beat"] = beat
        self.current, self.beat = snap, beat
        return snap

    def _synthetic(self, t):
        beat = (t * 2.0) % 1.0
        kick = math.exp(-beat * 6.0)
        i = np.arange(64, dtype=np.float32)
        return (0.75 * kick * np.exp(-i / 9.0) + 0.25 * (0.5 + 0.5 * np.sin(i * 0.45 + t * 3.0)) * np.exp(-i / 40.0))

    def _bpm_beat(self, pos, playing):
        bpm = float(self.layer.get(S + "BPM Sync"))
        if bpm <= 0 or not playing:
            self._grid_index = None
            return False
        phase = pos * bpm / 60.0 + float(self.layer.get(S + "Beat Offset"))
        index = int(phase)
        fired = self._grid_index is not None and index != self._grid_index
        self._grid_index = index
        return fired
