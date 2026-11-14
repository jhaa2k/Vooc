"""TimelineHub: the audio side of the render engine (in-app player only).

The player (MediaPlayer on Android, SoundLoader on desktop) plays the file
while ``SpectrumDecoder`` analyses it into a ``SpectrumTimeline``. Every frame
the hub reads the timeline at the *current playback position* (audio is the
master clock), so visuals never drift: seeking, pausing and resuming stay in
sync and a seek resets all smoothing state.

AUDIOPROVIDER > Analyzer settings are applied live, per frame:
  dB floor / ceiling, spectral tilt, dB or linear amplitude, peak / RMS
  normalisation, smoothing alpha + attack / release (ms), interpolation,
  3-band split with cut frequencies, gains and AGC, up to 32 custom bands with
  crossover slopes, beat detection (energy / flux / comb), BPM, bars.

Snapshot fields: bands (256, log 30 Hz..16 kHz), bands_left/right/mid/side,
waveform (512, real PCM), rms, peak, bass/mid/treble (= low/mid/high split),
dominant_hz, beat, downbeat, kick/snare/hihat (decayed pulses), bpm, bpm_auto,
beat_index, beat_in_bar, bar, beat_phase, custom_bands, custom_labels,
wave_source (timeline, t) for layers that need their own waveform window.
"""
import math
import time

import numpy as np

from . import beats as beatlib
from .analysis import MAX_BANDS, empty_snapshot
from ..dsp import BeatDetector

P = "General/Routing/"
S = "Sync & Offset/Timing/"
A = "Analyzer/"
B = "Beat Detection/"
TB = "Tempo & Bars/"
FB = "Frequency Bands/"

F_LO, F_HI = 30.0, 16000.0
OCTAVES = math.log2(F_HI / F_LO)


def band_hz(i, bands):
    return F_LO * (F_HI / F_LO) ** ((i + 0.5) / float(bands))


def _tau_coef(ms, dt):
    if ms <= 0:
        return 1.0
    return 1.0 - math.exp(-dt / (ms / 1000.0))


def _get(layer, path, default):
    try:
        return layer.get(path)
    except (KeyError, AttributeError):
        return default


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
        self._last_pos = None
        self._last_wall = time.monotonic()
        self._grid_index = None
        self._state = {}               # smoothing state per channel / band
        self._agc = {}
        self._detector = BeatDetector(sensitivity=1.3, refractory_seconds=0.22)
        self._x256 = (np.arange(MAX_BANDS, dtype=np.float32) + 0.5) / MAX_BANDS
        self._grid = beatlib.BeatGrid()
        self._grid_key = None
        self._weights_key = None
        self._weights = []
        self.taps = []
        self.fallback = True
        self.dt = 1 / 60.0
        self.seeked = False
        self.fixed_dt = None           # export: exact frame step instead of wall clock

    # ------------------------------------------------------------ transport
    def position(self):
        try:
            return float(self._position() or 0.0)
        except Exception:
            return 0.0

    def duration(self):
        try:
            return float(self._duration() or 0.0)
        except Exception:
            return 0.0

    def is_playing(self):
        try:
            return bool(self._playing())
        except Exception:
            return False

    def release(self):
        pass

    @property
    def grid(self):
        return self._grid

    # ------------------------------------------------------------ settings
    def beat_config(self):
        L = self.layer
        return {
            "algorithm": _get(L, B + "Detector/Algorithm", "Spectral Flux"),
            "sensitivity": _get(L, B + "Detector/Sensitivity", 0.5),
            "cooldown_ms": _get(L, B + "Detector/Cooldown (ms)", 180),
            "threshold": _get(L, B + "Detector/Threshold (x)", 1.5),
            "history_s": _get(L, B + "Detector/History (s)", 1.0),
            "bpm_mode": _get(L, TB + "Tempo/BPM Mode", "Auto"),
            "manual_bpm": _get(L, TB + "Tempo/Manual BPM", 120.0),
            "bpm_min": _get(L, TB + "Tempo/BPM Range Min", 60),
            "bpm_max": _get(L, TB + "Tempo/BPM Range Max", 200),
            "multiplier": _get(L, TB + "Tempo/Tempo Multiplier", "x1"),
            "signature": _get(L, TB + "Bars/Time Signature", "4/4"),
            "downbeat_offset": _get(L, TB + "Bars/Downbeat Offset", 0),
            "beat_offset": _get(L, S + "Beat Offset", 0.0),
        }

    def tap(self, now=None):
        """Tap tempo: returns the BPM (also written to Manual BPM, mode = Manual)."""
        now = time.monotonic() if now is None else now
        if self.taps and now - self.taps[-1] > 2.5:
            self.taps = []
        self.taps.append(now)
        self.taps = self.taps[-9:]
        bpm = beatlib.tap_tempo(self.taps)
        if bpm > 0:
            bpm = max(40.0, min(240.0, bpm))
            self.layer.set(TB + "Tempo/Manual BPM", round(bpm, 1))
            self.layer.set(TB + "Tempo/BPM Mode", "Manual")
            self._grid_key = None
        return bpm

    def _update_grid(self, tl):
        if tl is None:
            return
        cfg = self.beat_config()
        bucket = tl.count if tl.complete else tl.count // max(1, int(tl.fps * 4))
        key = (id(tl), bucket, tl.complete, tuple(sorted(cfg.items())))
        if key != self._grid_key:
            self._grid_key = key
            try:
                self._grid = beatlib.analyse(tl, cfg)
            except Exception as exc:          # never break rendering
                print("Vooc beat analysis failed: %s" % exc)

    # ------------------------------------------------------------ processing
    def _shape(self, db_row, bands_src, tl):
        L = self.layer
        floor = float(_get(L, A + "Scale/dB Floor", -66.0))
        ceil = float(_get(L, A + "Scale/dB Ceiling", -6.0))
        if ceil <= floor + 1:
            ceil = floor + 1
        tilt = float(_get(L, A + "Scale/Spectral Tilt (dB/oct)", 2.65))
        octs = (np.arange(bands_src, dtype=np.float32) + 0.5) / bands_src * OCTAVES
        db = db_row + tilt * octs
        norm = _get(L, A + "Scale/Normalization", "None")
        if tl is not None and norm == "Peak":
            db = db + (ceil - (tl.stats["p99_db"] + tilt * OCTAVES * 0.5))
        elif tl is not None and norm == "RMS":
            db = db + (-20.0 - 20.0 * math.log10(max(1e-4, tl.stats["mean_rms"] / 2.2)))
        if _get(L, A + "Scale/Amplitude Scale", "dB") == "Linear":
            v = np.power(10.0, (db - ceil) / 20.0)
        else:
            v = (db - floor) / (ceil - floor)
        return np.clip(v, 0.0, 1.0).astype(np.float32)

    def _smooth(self, key, v, dt):
        L = self.layer
        prev = self._state.get(key)
        if prev is None or prev.shape != v.shape or self.seeked:
            self._state[key] = v
            return v
        ka = _tau_coef(float(_get(L, A + "Smoothing/Attack (ms)", 0)), dt)
        kr = _tau_coef(float(_get(L, A + "Smoothing/Release (ms)", 0)), dt)
        out = prev + (v - prev) * np.where(v > prev, ka, kr)
        alpha = float(_get(L, A + "Smoothing/Smoothing", 0.0))
        if alpha > 0:
            k = alpha ** (dt * 60.0)
            out = prev * k + out * (1.0 - k)
        self._state[key] = out
        return out

    def _to256(self, v):
        n = len(v)
        xs = (np.arange(n, dtype=np.float32) + 0.5) / n
        mode = _get(self.layer, A + "Scale/Interpolation", "Linear")
        if mode == "Nearest":
            idx = np.clip((self._x256 * n).astype(np.int32), 0, n - 1)
            return v[idx]
        out = np.interp(self._x256, xs, v)
        if mode == "Cubic" and n >= 4:
            # Catmull-Rom on the band axis
            x = np.clip(self._x256 * n - 0.5, 0, n - 1.0001)
            i = x.astype(np.int32)
            f = x - i
            p0 = v[np.clip(i - 1, 0, n - 1)]
            p1 = v[i]
            p2 = v[np.clip(i + 1, 0, n - 1)]
            p3 = v[np.clip(i + 2, 0, n - 1)]
            out = 0.5 * ((2 * p1) + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f
                         + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f)
        return np.clip(out, 0.0, 1.0).astype(np.float32)

    def _band_weights(self, bands_src):
        L = self.layer
        lo_cut = float(_get(L, FB + "3-Band Split/Low-Mid Cut (Hz)", 250))
        hi_cut = float(_get(L, FB + "3-Band Split/Mid-High Cut (Hz)", 4000))
        slope = _get(L, FB + "Custom Bands/Crossover Slope", "24 dB/oct")
        custom = _get(L, FB + "Custom Bands/Bands", []) or []
        key = (bands_src, lo_cut, hi_cut, slope, tuple((b.get("lo"), b.get("hi")) for b in custom))
        if key == self._weights_key:
            return self._weights
        hz = np.array([band_hz(i, bands_src) for i in range(bands_src)], np.float32)
        try:
            db_oct = float(str(slope).split()[0])
        except ValueError:
            db_oct = 24.0

        def weights(lo, hi):
            lo, hi = max(F_LO * 0.5, float(lo)), max(float(lo) + 1, float(hi))
            below = np.maximum(0.0, np.log2(lo / hz))
            above = np.maximum(0.0, np.log2(hz / hi))
            w = np.power(10.0, -(below + above) * db_oct / 20.0)
            return (w / max(1e-6, w.sum())).astype(np.float32)
        lo_cut, hi_cut = min(lo_cut, hi_cut - 10), max(hi_cut, lo_cut + 10)
        self._weights = [weights(20, lo_cut), weights(lo_cut, hi_cut), weights(hi_cut, 20000)] + \
            [weights(b.get("lo", 100), b.get("hi", 1000)) for b in custom[:32]]
        self._weights_key = key
        return self._weights

    def _agc_value(self, key, v, dt, on):
        if not on:
            return v
        m = self._agc.get(key, 0.3)
        m = max(v, m * math.exp(-dt / 3.0), 0.05)
        self._agc[key] = m
        return min(1.0, v / m)

    # ------------------------------------------------------------ per frame
    def update(self):
        L = self.layer
        now = time.monotonic()
        dt = self.fixed_dt or max(1e-3, min(0.25, now - self._last_wall))
        self._last_wall = now
        self.dt = dt
        if not _get(L, P + "Visible", True):
            self.current, self.beat = empty_snapshot(), False
            return self.current
        pos = self.position()
        playing = self.is_playing()
        offset = float(_get(L, S + "Audio Offset", 0.0)) / 1000.0
        t = pos - offset
        self.seeked = self._last_pos is not None and (t < self._last_pos - 0.05 or t > self._last_pos + 0.6)
        gain = float(_get(L, P + "Preamp", 1.0))
        tl = self._timeline()
        self._update_grid(tl)
        rows = {}
        rms = peak_lin = 0.0
        bands_src = 64
        if tl is not None:
            bands_src = tl.bands
            row, rms, peak_lin = tl.sample_db(t, 0)
            if row is not None:
                rows["mono"] = row
                if tl.nspec >= 4:
                    for name, idx in (("left", 1), ("right", 2), ("side", 3)):
                        rows[name] = tl.sample_db(t, idx)[0]
        synthetic = False
        if "mono" not in rows:
            if playing and self.fallback:
                rows["mono"] = self._synthetic_db(t)
                rms, synthetic = 0.35, True
            else:
                rows["mono"] = np.full(bands_src, -100.0, np.float32)
        out = {}
        mono_src = None
        for name, row in rows.items():
            v = self._shape(np.asarray(row, np.float32), len(row), tl)
            v = self._smooth(name, v * gain, dt)
            np.clip(v, 0.0, 1.0, out=v)
            if name == "mono":
                mono_src = v
            out[name] = self._to256(v)
        bands = out["mono"]
        if not playing:
            prev = self._state.get("_paused")
            prev = bands if prev is None or prev.shape != bands.shape else prev * 0.86
            bands = np.minimum(bands, prev)
            self._state["_paused"] = bands
            for k in list(out):
                out[k] = np.minimum(out[k], bands) if k != "mono" else bands
        else:
            self._state.pop("_paused", None)
        left = out.get("left", bands)
        right = out.get("right", bands)
        side = out.get("side", np.zeros_like(bands))
        # 3-band split + custom bands
        w = self._band_weights(len(mono_src))
        AGC = bool(_get(L, FB + "3-Band Split/Band AGC", False))
        gains = [float(_get(L, FB + "3-Band Split/%s Gain" % n, 1.0)) for n in ("Low", "Mid", "High")]
        ba = float(_get(L, FB + "3-Band Split/Band Attack (ms)", 0))
        br = float(_get(L, FB + "3-Band Split/Band Release (ms)", 0))
        three = []
        for i in range(3):
            raw = float(np.dot(w[i], mono_src)) * gains[i]
            raw = self._agc_value("b%d" % i, raw, dt, AGC)
            three.append(self._smooth_scalar("b%d" % i, raw, dt, ba, br, 0.0))
        bass, mid, treble = (float(min(1.0, x)) for x in three)
        custom = _get(L, FB + "Custom Bands/Bands", []) or []
        cvals, clabels = [], []
        for j, cb in enumerate(custom[:32]):
            raw = float(np.dot(w[3 + j], mono_src)) * float(cb.get("gain", 1.0))
            raw = self._agc_value("c%d" % j, raw, dt, bool(cb.get("agc", False)))
            val = self._smooth_scalar("c%d" % j, raw, dt, float(cb.get("attack", 0)),
                                      float(cb.get("release", 0)), float(cb.get("smoothing", 0)))
            cvals.append(float(min(1.0, val)))
            clabels.append(str(cb.get("label") or "Band %d" % (j + 1)))
        hz = np.array([band_hz(i, len(mono_src)) for i in range(len(mono_src))], np.float32)
        dominant = float(hz[int(np.argmax(mono_src))]) if mono_src.any() else 0.0
        rms = float(min(1.0, rms * gain)) if playing else 0.0
        peak = float(bands.max()) if bands.size else 0.0
        # real waveform (falls back to a synthetic one while PCM is missing)
        wave = None
        if tl is not None and not synthetic and getattr(tl, "pcm_count", 0):
            wave = tl.waveform(t, 0.064, 512)
        if wave is None:
            wave = self._synthetic_wave(bass, mid, treble, rms)
        if not playing:
            wave = wave * 0.0
        snap = {"bands": bands, "bands_left": left, "bands_right": right,
                "bands_mid": bands, "bands_side": side,
                "waveform": wave.astype(np.float32), "rms": rms, "peak": peak, "peak_level": peak_lin,
                "bass": bass, "mid": mid, "treble": treble, "low": bass, "high": treble,
                "dominant_hz": dominant, "beat": False, "downbeat": False, "time": now,
                "custom_bands": cvals, "custom_labels": clabels,
                "mapping": _get(L, A + "Scale/Frequency Mapping", "Logarithmic"),
                "wave_source": (tl, t), "seeked": self.seeked}
        self._beats(snap, tl, t, pos, playing)
        self._last_pos = t
        self.current, self.beat = snap, snap["beat"]
        return snap

    def _smooth_scalar(self, key, v, dt, attack, release, alpha):
        prev = self._state.get("s_" + key)
        if prev is None or self.seeked:
            self._state["s_" + key] = v
            return v
        k = _tau_coef(attack if v > prev else release, dt)
        out = prev + (v - prev) * k
        if alpha > 0:
            a = alpha ** (dt * 60.0)
            out = prev * a + out * (1 - a)
        self._state["s_" + key] = out
        return out

    def _beats(self, snap, tl, t, pos, playing):
        L = self.layer
        g = self._grid
        decay_curve = _get(L, B + "Pulse/Decay Curve", "Exponential")
        decay_s = float(_get(L, B + "Pulse/Pulse Duration (ms)", 1000)) / 1000.0
        last = self._last_pos
        fire = lambda seq: bool(playing and last is not None and not self.seeked and seq
                                and g.between(last, t, seq))

        def pulse(seq):
            lt = g.last(t, seq) if seq else None
            return beatlib.decay(decay_curve, t - lt, max(0.05, decay_s * 0.35)) if lt is not None else 0.0
        trigger = _get(L, B + "Detector/Beat Trigger", "All Beats")
        k, in_bar, bar, phase, is_down = g.position(t)
        beat = False
        if g.times:
            beat = fire(g.times)
            if trigger == "Downbeats Only":
                beat = beat and is_down
        if trigger in ("Kick", "Snare", "Hi-Hat"):
            beat = fire({"Kick": g.kick, "Snare": g.snare, "Hi-Hat": g.hihat}[trigger])
        elif not g.times and playing and (tl is None or tl.count < 8):
            source = _get(L, P + "Measure Source", "Bass")
            value = {"Bass": snap["bass"], "Mid": snap["mid"], "Treble": snap["treble"],
                     "Volume": snap["rms"], "Peak": snap["peak"]}.get(source, snap["bass"])
            beat = self._detector.process(value, snap["time"])
        beat = beat or self._bpm_beat(pos, playing)
        snap.update(beat=bool(beat), downbeat=bool(beat and is_down),
                    kick=pulse(g.kick), snare=pulse(g.snare), hihat=pulse(g.hihat),
                    kick_hit=fire(g.kick), snare_hit=fire(g.snare), hihat_hit=fire(g.hihat),
                    bpm=float(g.bpm), bpm_auto=float(g.bpm_auto), bpm_confidence=float(g.confidence),
                    beat_index=int(k), beat_in_bar=int(in_bar), bar=int(bar), beat_phase=float(phase),
                    beats_per_bar=int(g.beats_per_bar), decay_curve=decay_curve, decay_s=decay_s)

    def _synthetic_db(self, t):
        beat = (t * 2.0) % 1.0
        kick = math.exp(-beat * 6.0)
        i = np.arange(64, dtype=np.float32)
        v = (0.75 * kick * np.exp(-i / 9.0) + 0.25 * (0.5 + 0.5 * np.sin(i * 0.45 + t * 3.0)) * np.exp(-i / 40.0))
        return (v * 60.0 - 66.0 - 2.65 * (i + 0.5) / 64 * OCTAVES).astype(np.float32)

    def _synthetic_wave(self, bass, mid, treble, rms):
        self._phase = getattr(self, "_phase", 0.0) + 0.12 + mid * 0.4
        k = np.linspace(0, 2 * math.pi, 512, dtype=np.float32)
        return ((np.sin(k * 3 + self._phase) * (0.5 * bass + 0.2)
                 + np.sin(k * 11 + self._phase * 1.7) * (0.35 * mid)
                 + np.sin(k * 29 + self._phase * 2.3) * (0.2 * treble)) * rms * 1.6).astype(np.float32)

    def _bpm_beat(self, pos, playing):
        bpm = float(_get(self.layer, S + "BPM Sync", 0))
        if bpm <= 0 or not playing:
            self._grid_index = None
            return False
        phase = pos * bpm / 60.0 + float(_get(self.layer, S + "Beat Offset", 0.0))
        index = int(phase)
        fired = self._grid_index is not None and index != self._grid_index
        self._grid_index = index
        return fired
