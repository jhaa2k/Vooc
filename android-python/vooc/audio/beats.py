"""Offline beat / tempo / bar tracking on a SpectrumTimeline.

Because the whole track is analysed ahead of the playhead, beats are found on
the timeline (look-ahead, no latency) and simply looked up at the playback
position every frame.

Algorithms
  Energy        - low-band power vs. its local average (history window)
  Spectral Flux - half-wave rectified spectral difference vs. adaptive threshold
  Comb Filter   - tempo from onset autocorrelation, beats placed on a phase
                  locked grid (comb filter), each snapped to the nearest onset

Tempo: autocorrelation of the onset envelope inside the BPM range, octave
corrected (half / double), or a manual BPM (tap tempo writes it).
Bars: beats are grouped by the time signature; the downbeat phase is the one
whose beats carry the most low-band (kick) energy.
Per band: kick (30-150 Hz), snare (150 Hz-4 kHz with noise), hi-hat (6-16 kHz).
"""
import bisect
import math

import numpy as np

F_LO, F_HI = 30.0, 16000.0
BEATS_PER_BAR = {"4/4": 4, "3/4": 3, "6/8": 6, "2/4": 2, "5/4": 5, "7/8": 7, "12/8": 12}
ALGORITHMS = ["Energy", "Spectral Flux", "Comb Filter"]


def band_mask(bands, lo_hz, hi_hz):
    hz = F_LO * (F_HI / F_LO) ** ((np.arange(bands) + 0.5) / float(bands))
    m = (hz >= lo_hz) & (hz < hi_hz)
    if not m.any():
        m[int(np.argmin(np.abs(hz - (lo_hz + hi_hz) / 2)))] = True
    return m


def _moving_mean(x, n):
    n = max(1, int(n))
    if len(x) == 0:
        return x
    k = np.ones(n, np.float32) / n
    return np.convolve(x, k, mode="same")


def _moving_max(x, n):
    n = max(1, int(n)) | 1
    if len(x) == 0:
        return x
    pad = np.pad(x, (n // 2, n // 2), mode="edge")
    try:
        view = np.lib.stride_tricks.sliding_window_view(pad, n)
        return view.max(axis=1)
    except AttributeError:          # very old numpy
        return np.array([pad[i:i + n].max() for i in range(len(x))], np.float32)


def adaptive_threshold(env, hist, thr_mult, sens):
    """Mean x multiplier, plus a fraction of the local maximum so weak onsets are ignored."""
    return _moving_mean(env, hist) * thr_mult + _moving_max(env, hist) * (0.3 - 0.25 * sens) + 1e-6


def _pick(signal, threshold, fps, cooldown_s):
    """Local maxima above threshold with a refractory period. Returns frame indices."""
    out = []
    if len(signal) < 3:
        return out
    above = (signal > threshold)
    above[1:-1] &= (signal[1:-1] >= signal[:-2]) & (signal[1:-1] >= signal[2:])
    gap = max(1, int(round(cooldown_s * fps)))
    last = -10 ** 9
    for i in np.nonzero(above)[0]:
        if i - last >= gap:
            out.append(int(i))
            last = i
    return out


def onset_envelope(lin, mask=None):
    """Spectral flux of a (frames, bands) 0..1 matrix."""
    x = lin if mask is None else lin[:, mask]
    if len(x) < 2:
        return np.zeros(len(x), np.float32)
    d = np.maximum(0.0, np.diff(x, axis=0, prepend=x[:1]))
    return d.mean(axis=1).astype(np.float32)


def _autocorr(env):
    e = env - env.mean()
    n = len(e)
    size = 1 << int(math.ceil(math.log2(2 * n)))
    spec = np.fft.rfft(e, size)
    ac = np.fft.irfft(spec * np.conj(spec), size)[:n]
    return ac / ac[0] if ac[0] > 0 else None


def estimate_bpm(env, fps, lo=60.0, hi=200.0):
    """Tempo from the onset autocorrelation.

    Every candidate BPM (0.25 BPM steps) is scored by the autocorrelation at
    its beat period and the first harmonics (fractional lags, interpolated),
    times a log-gaussian prior around 120 BPM that resolves octave errors.
    """
    if len(env) < fps * 4:
        return 0.0, 0.0
    ac = _autocorr(env)
    if ac is None:
        return 0.0, 0.0
    n = len(ac)
    lo, hi = max(20.0, float(lo)), max(float(lo) + 1, float(hi))
    bpm = np.arange(lo, hi + 0.001, 0.25)
    lag = 60.0 * fps / bpm
    idx = np.arange(n)
    score = np.zeros_like(bpm)
    for h, wgt in ((1, 1.0), (2, 0.5), (3, 0.33), (4, 0.25)):
        lh = lag * h
        ok = lh < n - 1
        score[ok] += wgt * np.interp(lh[ok], idx, ac)
    prior = np.exp(-0.5 * (np.log2(bpm / 120.0) / 0.9) ** 2)
    total = score * prior
    k = int(np.argmax(total))
    conf = float(np.interp(lag[k], idx, ac))
    return float(bpm[k]), max(0.0, min(1.0, conf))


def comb_grid(env, fps, bpm, offset_beats=0.0):
    """Beat frame positions on a tempo grid with the best phase, snapped to onsets."""
    if bpm <= 0 or len(env) < 2:
        return []
    period = 60.0 * fps / bpm
    steps = max(1, int(period))
    best, best_phase = -1.0, 0.0
    idx_cache = np.arange(0, len(env) - 1, period)
    for p in range(steps):
        pos = (idx_cache + p).astype(np.int64)
        pos = pos[pos < len(env)]
        s = float(env[pos].sum())
        if s > best:
            best, best_phase = s, p
    phase = best_phase + offset_beats * period
    beats = []
    win = max(1, int(period * 0.12))
    x = phase % period
    while x < len(env):
        c = int(round(x))
        lo, hi = max(0, c - win), min(len(env), c + win + 1)
        if hi > lo:
            j = lo + int(np.argmax(env[lo:hi]))
            # only snap when there is a clear onset, otherwise keep the grid
            beats.append(j if env[j] > env[lo:hi].mean() * 1.5 else c)
        x += period
    return [min(int(b), len(env) - 1) for b in beats]


class BeatGrid:
    """Result of an analysis pass: times + tempo + bar structure."""

    def __init__(self):
        self.times = []              # beat times (s)
        self.strength = []
        self.bpm = 0.0
        self.bpm_auto = 0.0
        self.confidence = 0.0
        self.beats_per_bar = 4
        self.downbeat = 0            # index of the first downbeat in times
        self.kick, self.snare, self.hihat = [], [], []

    # --------------------------------------------------------- queries
    def index_at(self, t):
        return bisect.bisect_right(self.times, t) - 1

    def last(self, t, seq=None):
        seq = self.times if seq is None else seq
        k = bisect.bisect_right(seq, t) - 1
        return seq[k] if k >= 0 else None

    def between(self, t0, t1, seq=None):
        seq = self.times if seq is None else seq
        if t1 < t0 or not seq:
            return False
        k = bisect.bisect_right(seq, t0)
        return k < len(seq) and seq[k] <= t1

    def position(self, t):
        """(beat_index, beat_in_bar 1..N, bar 1.., beat_phase 0..1, is_downbeat_beat)."""
        k = self.index_at(t)
        if k < 0:
            return -1, 0, 0, 0.0, False
        n = max(1, self.beats_per_bar)
        rel = k - self.downbeat
        in_bar = rel % n
        bar = rel // n + 1
        t0 = self.times[k]
        t1 = self.times[k + 1] if k + 1 < len(self.times) else t0 + (60.0 / self.bpm if self.bpm else 0.5)
        phase = max(0.0, min(1.0, (t - t0) / max(1e-3, t1 - t0)))
        return k, in_bar + 1, bar, phase, in_bar == 0


def _bpm_multiplier(name):
    return {"x0.5 (Half)": 0.5, "x2 (Double)": 2.0, "x1": 1.0}.get(name, 1.0)


def analyse(timeline, cfg):
    """cfg: dict with algorithm, sensitivity, cooldown_ms, threshold, history_s,
    bpm_mode, manual_bpm, bpm_min, bpm_max, multiplier, signature, downbeat_offset, beat_offset."""
    grid = BeatGrid()
    if timeline is None or timeline.count < 8:
        return grid
    fps = timeline.fps
    db = timeline.db(0)
    tilt = np.arange(timeline.bands, dtype=np.float32) * (24.0 / timeline.bands)
    lin = np.clip((db + tilt + 66.0) / 60.0, 0.0, 1.0)
    power = np.power(10.0, db / 10.0)
    bands = timeline.bands
    low = band_mask(bands, 30, 150)
    sens = float(cfg.get("sensitivity", 0.5))
    thr_mult = float(cfg.get("threshold", 1.5)) * (1.25 - 0.5 * sens)
    hist = max(1, int(float(cfg.get("history_s", 1.0)) * fps))
    cool = float(cfg.get("cooldown_ms", 180)) / 1000.0
    algo = cfg.get("algorithm", "Spectral Flux")

    beat_mask = band_mask(bands, 30, 2500)          # kick + snare body drive the beat, not hi-hats
    flux = onset_envelope(lin, beat_mask)
    bpm_auto, conf = estimate_bpm(flux, fps, float(cfg.get("bpm_min", 60)), float(cfg.get("bpm_max", 200)))
    bpm_auto *= _bpm_multiplier(cfg.get("multiplier", "x1"))
    grid.bpm_auto, grid.confidence = bpm_auto, conf
    manual = cfg.get("bpm_mode") == "Manual" and float(cfg.get("manual_bpm", 0)) > 0
    grid.bpm = float(cfg.get("manual_bpm")) if manual else bpm_auto

    if manual or algo == "Comb Filter":
        frames = comb_grid(flux, fps, grid.bpm, float(cfg.get("beat_offset", 0.0)))
    elif algo == "Energy":
        e = power[:, low].mean(axis=1)
        frames = _pick(e, adaptive_threshold(e, hist, thr_mult, sens), fps, cool)
    else:
        frames = _pick(flux, adaptive_threshold(flux, hist, thr_mult, sens), fps, cool)
    grid.times = [f / fps for f in frames]
    grid.strength = [float(flux[f]) for f in frames]

    # bars + downbeat
    n = BEATS_PER_BAR.get(cfg.get("signature", "4/4"), 4)
    grid.beats_per_bar = n
    if frames and n > 1:
        kick_e = power[:, low].mean(axis=1)
        scores = []
        for k in range(n):
            sel = frames[k::n]
            scores.append(float(kick_e[sel].mean()) if sel else 0.0)
        grid.downbeat = int(np.argmax(scores))
    grid.downbeat = (grid.downbeat + int(cfg.get("downbeat_offset", 0))) % max(1, n)

    # per band onsets
    def band_onsets(lo, hi, gap):
        env = onset_envelope(lin, band_mask(bands, lo, hi))
        idx = _pick(env, adaptive_threshold(env, hist, thr_mult, sens), fps, gap)
        return [i / fps for i in idx]
    grid.kick = band_onsets(30, 150, max(0.1, cool))
    grid.snare = band_onsets(150, 4000, max(0.1, cool))
    grid.hihat = band_onsets(6000, 16000, 0.06)
    return grid


def tap_tempo(taps, max_gap=2.0):
    """BPM from tap timestamps (median interval of the last taps)."""
    taps = [t for t in taps]
    if len(taps) < 2:
        return 0.0
    gaps = [b - a for a, b in zip(taps[:-1], taps[1:]) if 0.15 < b - a < max_gap]
    if not gaps:
        return 0.0
    gaps = sorted(gaps[-8:])
    return 60.0 / gaps[len(gaps) // 2]


def decay(curve, since, duration):
    """Beat pulse shape: 1 at the beat, 0 after ``duration`` seconds."""
    if since < 0:
        return 0.0
    d = max(1e-3, duration)
    x = since / d
    if curve == "Linear":
        return max(0.0, 1.0 - x)
    if curve == "Ease-Out":
        return max(0.0, 1.0 - x) ** 2
    if curve == "Ease-In":
        return max(0.0, 1.0 - x * x)
    if curve == "Step (Hold)":
        return 1.0 if x < 1.0 else 0.0
    return math.exp(-x * 4.0)           # Exponential (~2 % left at the end)
