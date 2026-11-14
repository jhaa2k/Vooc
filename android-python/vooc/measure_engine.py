"""Measure Engine: turns audio/time into animated property offsets.

    value = base + curve(mode, A, B)

A (Amount) = how much the property changes.  B (Speed) = how fast it changes.
"Nothing" keeps the property static. "Beat*" modes jump on a kick and decay
back with speed B. "Constant*" modes run continuously from playback time.
"VolumeLevelResponsive" follows loudness (dB mapped to 0..1),
"PitchFrequencyResponsive" follows the dominant frequency (log-mapped 0..1).
"""
import math

from .audio.beats import decay as _decay


def _hash_noise(x):
    """Deterministic pseudo-random value in [-1, 1] for shake modes."""
    s = math.sin(x * 12.9898 + 78.233) * 43758.5453
    return (s - math.floor(s)) * 2.0 - 1.0


class MeasureEngine:
    def __init__(self):
        self.time = 0.0
        self.play_time = 0.0
        self.beat_pulse = 0.0
        self.last_beat_time = -100.0
        self.beat_count = 0
        self.track_position = 0.0
        self.track_duration = 0.0
        self.rms = 0.0
        self.peak = 0.0
        self.dominant_hz = 0.0
        self.playing = False
        self.snap = {}
        self.decay_curve = "Exponential"
        self.decay_s = 1.0
        self.last_downbeat_time = -100.0
        self.override = None            # per-layer Reactivity Mode (Beat / Frequency / Amplitude / Nothing)
        self.weights = (1.0, 0.5, 0.25)

    def use_layer(self, values):
        """Set the per-layer reactivity context before a layer evaluates its modes."""
        mode = values.get("General/Reactivity/Reactivity Mode", "Inherit")
        self.override = None if mode in (None, "Inherit") else mode
        self.weights = (values.get("General/Reactivity/Band Weight Low", 1.0),
                        values.get("General/Reactivity/Band Weight Mid", 0.5),
                        values.get("General/Reactivity/Band Weight High", 0.25))

    def frequency_level(self):
        s = self.snap
        wl, wm, wh = self.weights
        total = max(1e-6, wl + wm + wh)
        return max(0.0, min(1.0, (wl * s.get("bass", 0.0) + wm * s.get("mid", 0.0)
                                  + wh * s.get("treble", 0.0)) / total))

    def update(self, dt, beat=False, rms=0.0, peak=0.0, track_position=None,
               track_duration=None, dominant_hz=0.0, playing=None, snapshot=None):
        dt = max(0.0, min(0.25, float(dt)))
        self.time += dt
        if playing is not None:
            self.playing = bool(playing)
        if self.playing:
            self.play_time += dt
        self.beat_pulse = max(0.0, self.beat_pulse - dt * 3.5)
        if beat:
            self.beat_pulse = 1.0
            self.last_beat_time = self.time
            self.beat_count += 1
        if snapshot is not None:
            self.snap = snapshot
            self.decay_curve = snapshot.get("decay_curve", self.decay_curve)
            self.decay_s = float(snapshot.get("decay_s", self.decay_s))
            if snapshot.get("downbeat"):
                self.last_downbeat_time = self.time
        self.rms = max(0.0, min(1.0, float(rms)))
        self.peak = max(0.0, min(1.0, float(peak)))
        self.dominant_hz = max(0.0, float(dominant_hz))
        if track_position is not None:
            self.track_position = max(0.0, float(track_position))
        if track_duration is not None:
            self.track_duration = max(0.0, float(track_duration))

    # ------------------------------------------------------------------ curves
    def pulse(self, speed=1.0):
        """1.0 on the beat, exponential decay; B (speed) controls decay rate."""
        since = self.time - self.last_beat_time
        if since < 0:
            return 0.0
        return _decay(self.decay_curve, since * max(0.05, speed), self.decay_s)

    def volume_level(self):
        if self.rms <= 1e-5:
            return 0.0
        db = 20.0 * math.log10(self.rms)          # -100..0 dB
        return max(0.0, min(1.0, (db + 60.0) / 60.0))

    def pitch_level(self):
        return min(1.0, math.log1p(self.dominant_hz) / math.log1p(20000.0))

    def evaluate(self, mode, base=0.0, amount=1.0, speed=1.0, now=None):
        """Scalar measure value: base + offset."""
        t = self.time if now is None else float(now)
        a = max(-1000.0, min(1000.0, float(amount)))
        b = max(-100.0, min(100.0, float(speed)))
        if self.override is not None and mode not in (None, "", "Nothing"):
            mode = {"Beat": "Beat", "Frequency": "Frequency", "Amplitude": "Amplitude"}.get(self.override, "Nothing")
        p = self.pulse(abs(b) if b else 1.0)
        s = self.snap
        progress = self.track_position / self.track_duration if self.track_duration > 0 else 0.0
        if mode in (None, "", "Nothing"):
            d = 0.0
        elif mode in ("Beat", "BeatTriggerAnim", "BeatPulseScale", "BeatFlashColor",
                      "BeatGlowIntensity", "BeatHorizontalStretch", "BeatVerticalBounce"):
            d = a * p
        elif mode == "TotalTime":
            d = a * ((t * b) % 1.0)
        elif mode == "TotalTimeBackward":
            d = a * (1.0 - ((t * b) % 1.0))
        elif mode == "TotalTimeWhenPlaying":
            d = a * ((self.play_time * b) % 1.0)
        elif mode == "TotalTimeAndBeat":
            d = a * (((t * b) % 1.0) * 0.5 + p * 0.5)
        elif mode == "TrackPosition":
            d = a * self.track_position
        elif mode in ("BeatRandomShake", "BeatCamShakeOnly", "BeatCamShakeRotMore",
                      "BeatCamShakeRotLess", "ConstantShakeRotMore", "ConstantShakeRotLess"):
            gate = p if mode.startswith("Beat") else 1.0
            strength = 0.4 if mode.endswith("Less") else 1.0
            d = a * gate * strength * _hash_noise(math.floor(t * (8.0 + abs(b) * 20.0)))
        elif mode == "ConstantSlowRotate":
            d = a * ((t * b * 0.1) % 1.0)
        elif mode == "ConstantOrbitMove":
            d = a * math.sin(t * b)
        elif mode == "ConstantLinearScroll":
            d = a * ((t * b * 0.25) % 1.0)
        elif mode == "TrackProgressPercentage":
            d = a * progress
        elif mode == "TrackRemainingTime":
            d = a * (1.0 - progress)
        elif mode == "TrackIntroOutroTrigger":
            d = a if (progress < 0.08 or progress > 0.92) and self.track_duration > 0 else 0.0
        elif mode == "VolumeLevelResponsive":
            d = a * self.volume_level()
        elif mode == "PitchFrequencyResponsive":
            d = a * self.pitch_level()
        elif mode == "Frequency":
            d = a * self.frequency_level()
        elif mode == "Amplitude":
            d = a * self.volume_level()
        elif mode in ("Kick", "Snare", "HiHat"):
            d = a * float(s.get({"HiHat": "hihat"}.get(mode, mode.lower()), 0.0))
        elif mode == "Downbeat":
            since = self.time - self.last_downbeat_time
            d = a * (_decay(self.decay_curve, since * max(0.05, abs(b) or 1.0), self.decay_s) if since >= 0 else 0.0)
        elif mode == "BeatProgress":
            d = a * float(s.get("beat_phase", 0.0))
        elif mode == "BarProgress":
            n = max(1, int(s.get("beats_per_bar", 4)))
            d = a * ((max(1, int(s.get("beat_in_bar", 1))) - 1 + float(s.get("beat_phase", 0.0))) / n)
        elif mode in ("LowBand", "MidBand", "HighBand"):
            d = a * float(s.get({"LowBand": "bass", "MidBand": "mid", "HighBand": "treble"}[mode], 0.0))
        elif mode.startswith("CustomBand"):
            vals = s.get("custom_bands") or []
            k = int(mode[10:] or 1) - 1
            d = a * (float(vals[k]) if 0 <= k < len(vals) else 0.0)
        else:
            d = 0.0
        result = float(base) + d
        return result if math.isfinite(result) else float(base)

    def offset(self, mode, amount, speed):
        return self.evaluate(mode, 0.0, amount, speed)

    # ------------------------------------------------------------------ vectors
    def position_offset(self, mode, amount, speed):
        """(dx, dy) in canvas-relative units for the Measure Pos dropdown."""
        if mode in (None, "", "Nothing"):
            return 0.0, 0.0
        if self.override == "Nothing":
            return 0.0, 0.0
        t = self.time
        a, b = float(amount), float(speed)
        if mode == "ConstantOrbitMove":
            return a * 0.2 * math.cos(t * b), a * 0.2 * math.sin(t * b)
        if "Shake" in mode:
            gate = self.pulse(b or 1.0) if mode.startswith("Beat") else 1.0
            k = math.floor(t * (8.0 + abs(b) * 20.0))
            return a * 0.05 * gate * _hash_noise(k), a * 0.05 * gate * _hash_noise(k + 17.3)
        if mode == "BeatHorizontalStretch":
            return a * 0.1 * self.pulse(b or 1.0), 0.0
        if mode == "ConstantLinearScroll":
            return self.offset(mode, a, b) - a * 0.5, 0.0
        return 0.0, self.offset(mode, a, b) * 0.2

    def scale_offset(self, mode, amount, speed):
        """(sx, sy) multipliers for Measure Scale."""
        if mode in (None, "", "Nothing"):
            return 1.0, 1.0
        d = self.offset(mode, amount, speed)
        if mode == "BeatHorizontalStretch":
            return 1.0 + d, 1.0
        if mode == "BeatVerticalBounce":
            return 1.0, 1.0 + d
        return 1.0 + d, 1.0 + d

    def rotation_offset(self, mode, amount, speed):
        """Degrees for Measure Rot."""
        if mode in (None, "", "Nothing"):
            return 0.0
        d = self.offset(mode, amount, speed)
        if mode in ("ConstantSlowRotate", "TotalTime", "TotalTimeBackward",
                    "TotalTimeWhenPlaying", "ConstantLinearScroll"):
            return d * 360.0
        return d * 45.0
