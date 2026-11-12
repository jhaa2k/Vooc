"""AudioHub: routes the selected Audio Source into analysis, applies the
AUDIOPROVIDER settings (volume, balance, preamp, offset, BPM grid, measure
source) and exposes one snapshot per frame to the render engine."""
import threading
import time

from ..dsp import BeatDetector
from .analysis import SpectrumAnalyzer, SnapshotDelay, empty_snapshot
from .providers import create_provider

P = "General/Routing/"
S = "Sync & Offset/Timing/"


class AudioHub:
    def __init__(self, settings_layer, on_error=None, on_complete=None):
        self.layer = settings_layer           # LayerModel of AUDIOPROVIDER
        self.on_error = on_error or (lambda msg: None)
        self.on_complete = on_complete or (lambda: None)
        self.analyzer = SpectrumAnalyzer()
        self.delay = SnapshotDelay()
        self._lock = threading.Lock()
        self.provider = None
        self.kind = None
        self.beat_detector = BeatDetector(sensitivity=1.3, refractory_seconds=0.22)
        self._last_snap_time = -1.0
        self._grid_index = None
        self.current = empty_snapshot()
        self.beat = False
        self.metadata = {"title": "", "artist": "", "album": "", "art": ""}
        self.current_uri = ""
        self.ensure_source()

    # ------------------------------------------------------------ provider mgmt
    def _on_pcm(self, samples, timestamp, right=None):
        gain = float(self.layer.get(P + "Preamp"))
        snap = self.analyzer.process(samples, timestamp, gain=gain, right=right)
        with self._lock:
            self.delay.push(snap)

    def ensure_source(self):
        kind = self.layer.get(P + "Audio Source")
        if kind == self.kind and self.provider is not None:
            return self.provider
        if self.provider is not None:
            self.provider.release()
        self.kind = kind
        self.provider = create_provider(kind, self._on_pcm, self.on_error, self.on_complete)
        if not self.provider.has_transport:
            self.provider.start()
        elif self.current_uri:
            self.provider.load(self.current_uri, autoplay=False)
        return self.provider

    def restart(self):
        """Re-create the live source / visualizer (e.g. after a permission grant)."""
        provider = self.provider
        if provider is not None and provider.has_transport and hasattr(provider, "_attach"):
            provider._attach()
            return
        if provider is not None:
            provider.release()
        self.provider, self.kind = None, None
        self.ensure_source()

    # ------------------------------------------------------------ transport
    def load(self, uri, metadata=None, autoplay=True):
        self.current_uri = uri
        self.metadata = dict(self.metadata, **(metadata or {}))
        provider = self.ensure_source()
        if provider.has_transport:
            ok = provider.load(uri, autoplay=autoplay)
            self.apply_volume()
            return ok
        return True

    def play(self):
        return self.provider.play() if self.provider.has_transport else False

    def pause(self):
        return self.provider.pause() if self.provider.has_transport else False

    def toggle(self):
        return self.pause() if self.is_playing() else self.play()

    def seek(self, seconds):
        return self.provider.seek(seconds)

    def position(self):
        return self.provider.position()

    def duration(self):
        return self.provider.duration()

    def is_playing(self):
        if not self.provider.has_transport:
            return True   # live sources are always "playing"
        return self.provider.is_playing()

    def apply_volume(self):
        self.provider.set_volume(float(self.layer.get(P + "Volume")),
                                 float(self.layer.get(P + "Balance")))

    # ------------------------------------------------------------ per frame
    def update(self):
        """Call once per frame. Returns the snapshot used for rendering."""
        self.ensure_source()
        self.apply_volume()
        if not self.layer.get(P + "Visible"):
            self.current, self.beat = empty_snapshot(), False
            return self.current
        offset = float(self.layer.get(S + "Audio Offset"))
        with self._lock:
            snap = self.delay.get(offset)
        # Audio stops -> decay to silence instead of freezing
        if time.monotonic() - snap.get("time", 0) > 0.35:
            snap = empty_snapshot()
        beat = False
        if snap["time"] != self._last_snap_time:
            self._last_snap_time = snap["time"]
            source = self.layer.get(P + "Measure Source")
            value = {"Bass": snap["bass"], "Mid": snap["mid"], "Treble": snap["treble"],
                     "Volume": snap["rms"], "Peak": snap["peak"]}[source]
            beat = self.beat_detector.process(value, snap["time"])
        beat = beat or self._bpm_beat()
        self.current, self.beat = snap, beat
        return snap

    def _bpm_beat(self):
        bpm = float(self.layer.get(S + "BPM Sync"))
        if bpm <= 0 or not self.is_playing():
            self._grid_index = None
            return False
        phase = self.position() * bpm / 60.0 + float(self.layer.get(S + "Beat Offset"))
        index = int(phase)
        fired = self._grid_index is not None and index != self._grid_index
        self._grid_index = index
        return fired

    def release(self):
        if self.provider is not None:
            self.provider.release()
