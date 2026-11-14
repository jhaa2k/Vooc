"""Waveform and Shape layers (spec 2.2 / 2.6), drawn with QuadBatch meshes.

Waveform reads the real decoded PCM of the playing track (SpectrumTimeline
.waveform), so what you see is the actual signal, not a simulation.
Shape builds vector outlines (rectangle with rounded corners, ellipse, line,
polygon, star, SVG-style path), fills them with solid / linear / radial
gradients, strokes them with optional dashes and can morph between two
shapes, spin, rotate on the beat or scale with a frequency band.
"""
import math
import re

import numpy as np

from ..model import hex_to_rgba
from . import textures
from .layers import RENDERERS, BaseLayer, QuadBatch

W = "Waveform/"
SH = "Shape/"


# ------------------------------------------------------------------ geometry helpers
def thick_polyline(pts, width, closed=False):
    """Quads (n, 4, 2) for a polyline of constant width."""
    pts = np.asarray(pts, np.float32)
    if closed:
        pts = np.vstack([pts, pts[:1]])
    if len(pts) < 2:
        return np.zeros((0, 4, 2), np.float32)
    a, b = pts[:-1], pts[1:]
    d = b - a
    ln = np.maximum(np.linalg.norm(d, axis=1, keepdims=True), 1e-6)
    n = np.stack([-d[:, 1], d[:, 0]], 1) / ln * (width / 2.0)
    # extend each segment by half the width so joints do not crack
    ext = d / ln * (width / 2.0)
    a, b = a - ext, b + ext
    return np.stack([a - n, b - n, b + n, a + n], 1).astype(np.float32)


def dashed(pts, dash, gap, closed=False):
    """Split a polyline into dash pieces (list of arrays) along its arc length."""
    pts = np.asarray(pts, np.float32)
    if closed:
        pts = np.vstack([pts, pts[:1]])
    if dash <= 0 or gap <= 0 or len(pts) < 2:
        return [pts]
    seg = np.linalg.norm(np.diff(pts, axis=0), axis=1)
    s = np.concatenate([[0.0], np.cumsum(seg)])
    total = s[-1]
    if total <= 0:
        return [pts]
    period = dash + gap
    pieces = []
    start = 0.0
    while start < total:
        end = min(total, start + dash)
        inner = (s > start) & (s < end)
        p0 = np.array([np.interp(start, s, pts[:, 0]), np.interp(start, s, pts[:, 1])])
        p1 = np.array([np.interp(end, s, pts[:, 0]), np.interp(end, s, pts[:, 1])])
        pieces.append(np.vstack([p0[None], pts[inner], p1[None]]))
        start += period
    return pieces


def fan_quads(poly, center=None):
    """Triangle fan as degenerate quads (centroid, p_i, p_i+1, p_i+1)."""
    poly = np.asarray(poly, np.float32)
    c = poly.mean(axis=0) if center is None else np.asarray(center, np.float32)
    nxt = np.roll(poly, -1, axis=0)
    cc = np.repeat(c[None], len(poly), axis=0)
    return np.stack([cc, poly, nxt, nxt], 1)


def resample_closed(poly, n):
    poly = np.asarray(poly, np.float32)
    closed = np.vstack([poly, poly[:1]])
    seg = np.linalg.norm(np.diff(closed, axis=0), axis=1)
    s = np.concatenate([[0.0], np.cumsum(seg)])
    if s[-1] <= 0:
        return np.repeat(poly[:1], n, axis=0)
    t = np.linspace(0, s[-1], n, endpoint=False)
    out = np.stack([np.interp(t, s, closed[:, 0]), np.interp(t, s, closed[:, 1])], 1)
    # start every outline at its top-most point so morphs do not twist
    k = int(np.argmax(out[:, 1] - np.abs(out[:, 0]) * 0.01))
    return np.roll(out, -k, axis=0)


_TOKEN = re.compile(r"[MmLlHhVvCcQqZz]|-?\d*\.?\d+(?:[eE][-+]?\d+)?")


def parse_path(d, steps=12):
    """SVG path subset (M L H V C Q Z, absolute and relative) -> list of polylines in path units."""
    tokens = _TOKEN.findall(d or "")
    polys, cur, pos, start = [], [], np.zeros(2), np.zeros(2)
    cmd, i = None, 0

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v
    while i < len(tokens):
        tok = tokens[i]
        if tok.isalpha():
            cmd = tok
            i += 1
            if cmd in "Zz":
                if cur:
                    cur.append(start.copy())
                    polys.append(np.array(cur))
                    cur = []
                pos = start.copy()
                continue
        if cmd is None:
            i += 1
            continue
        rel = cmd.islower()
        c = cmd.upper()
        try:
            if c == "M":
                p = np.array([num(), num()]) + (pos if rel else 0)
                if cur:
                    polys.append(np.array(cur))
                cur, pos, start = [p.copy()], p, p.copy()
                cmd = "l" if rel else "L"
            elif c == "L":
                pos = np.array([num(), num()]) + (pos if rel else 0)
                cur.append(pos.copy())
            elif c == "H":
                pos = np.array([num() + (pos[0] if rel else 0), pos[1]])
                cur.append(pos.copy())
            elif c == "V":
                pos = np.array([pos[0], num() + (pos[1] if rel else 0)])
                cur.append(pos.copy())
            elif c in "CQ":
                k = 3 if c == "C" else 2
                ctrl = [np.array([num(), num()]) + (pos if rel else 0) for _ in range(k)]
                p0 = pos
                for s in range(1, steps + 1):
                    t = s / float(steps)
                    if k == 3:
                        q = ((1 - t) ** 3) * p0 + 3 * ((1 - t) ** 2) * t * ctrl[0] + 3 * (1 - t) * t * t * ctrl[1] + t ** 3 * ctrl[2]
                    else:
                        q = ((1 - t) ** 2) * p0 + 2 * (1 - t) * t * ctrl[0] + t * t * ctrl[1]
                    cur.append(q)
                pos = ctrl[-1]
            else:
                i += 1
        except (IndexError, ValueError):
            break
    if cur:
        polys.append(np.array(cur))
    polys = [p for p in polys if len(p) >= 2]
    if not polys:
        return []
    allp = np.vstack(polys)
    lo, hi = allp.min(axis=0), allp.max(axis=0)
    span = max(float((hi - lo).max()), 1e-6)
    mid = (lo + hi) / 2.0
    # normalise to [-1, 1]; SVG y grows downwards
    return [((p - mid) / (span / 2.0)) * [1, -1] for p in polys]


def shape_outline(kind, v, rx, ry):
    """Closed outline (n, 2) in px around (0, 0), or (points, closed) for Line / Path."""
    if kind == "Rectangle":
        r = min(rx, ry) * float(v[SH + "Geometry/Corner Radius"])
        if r <= 0.5:
            return np.array([(-rx, -ry), (rx, -ry), (rx, ry), (-rx, ry)], np.float32), True
        pts = []
        for cx, cy, a0 in ((rx - r, ry - r, 0), (-rx + r, ry - r, 90), (-rx + r, -ry + r, 180), (rx - r, -ry + r, 270)):
            for a in np.linspace(math.radians(a0), math.radians(a0 + 90), 10):
                pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        return np.array(pts, np.float32), True
    if kind == "Ellipse":
        a = np.linspace(0, 2 * math.pi, 128, endpoint=False)
        return np.stack([np.cos(a) * rx, np.sin(a) * ry], 1).astype(np.float32), True
    if kind == "Polygon":
        n = int(v[SH + "Geometry/Sides"])
        a = math.pi / 2 + np.arange(n) * 2 * math.pi / n
        return np.stack([np.cos(a) * rx, np.sin(a) * ry], 1).astype(np.float32), True
    if kind == "Star":
        n = int(v[SH + "Geometry/Star Points"])
        inner = float(v[SH + "Geometry/Inner Radius"])
        a = math.pi / 2 + np.arange(n * 2) * math.pi / n
        r = np.where(np.arange(n * 2) % 2 == 0, 1.0, inner)
        return np.stack([np.cos(a) * r * rx, np.sin(a) * r * ry], 1).astype(np.float32), True
    if kind == "Line":
        ang = math.radians(float(v[SH + "Geometry/Line Angle"]))
        d = np.array([math.cos(ang) * rx, math.sin(ang) * rx])
        return np.array([-d, d], np.float32), False
    if kind == "Path":
        polys = parse_path(v[SH + "Geometry/SVG Path"])
        if not polys:
            return np.zeros((0, 2), np.float32), False
        p = polys[0] * [rx, ry]
        closed = np.allclose(polys[0][0], polys[0][-1], atol=1e-3)
        return p.astype(np.float32), closed
    return shape_outline("Ellipse", v, rx, ry)


def gradient_colors(pts, mode, c1, c2, angle_deg, extent):
    """Per-vertex colors for (n, 4, 2) quads."""
    c1, c2 = np.asarray(c1, np.float32), np.asarray(c2, np.float32)
    if mode == "Solid":
        return np.broadcast_to(c1, pts.shape[:2] + (4,)).copy()
    if mode == "Radial Gradient":
        t = np.linalg.norm(pts, axis=-1) / max(extent, 1e-3)
    else:
        a = math.radians(angle_deg)
        t = (pts[..., 0] * math.cos(a) + pts[..., 1] * math.sin(a)) / max(extent, 1e-3) * 0.5 + 0.5
    t = np.clip(t, 0, 1)[..., None]
    return c1 + (c2 - c1) * t


# ------------------------------------------------------------------ WAVEFORM
class WaveformLayer(BaseLayer):
    def build(self):
        super().build()
        self.batch = QuadBatch(self.content)
        self.dots = QuadBatch(self.content, textures.soft_dot())
        self.smooth = None
        self.history = np.zeros(0, np.float32)
        self._hist_t = None

    def _window(self, frame, channel):
        v = self.values
        points = int(v[W + "Parameters/Points"])
        src = frame.snapshot.get("wave_source")
        tl, t = src if src else (None, 0.0)
        scroll = float(v[W + "Parameters/Scroll Speed"])
        window = int(v[W + "Parameters/Window (samples)"])
        if tl is None or getattr(tl, "pcm_count", 0) == 0:
            wave = np.asarray(frame.snapshot.get("waveform"), np.float32)
            if wave.size == 0:
                return np.zeros(points, np.float32), False
            xs = np.linspace(0, len(wave) - 1, points)
            return np.interp(xs, np.arange(len(wave)), wave).astype(np.float32), False
        rate = float(getattr(tl, "pcm_rate", 8000) or 8000)
        if scroll <= 0:
            return tl.waveform(t, window / rate, points, channel), False
        # scrolling envelope: newest on the right, (4 / speed) seconds of history
        secs = 4.0 / scroll
        raw = tl.waveform(t, secs, points * 8, channel)
        env = np.abs(raw).reshape(points, 8).max(axis=1)
        return env, True

    def render(self, frame):
        v = self.values
        w, h = frame.w * float(v[W + "Size/Width"]), frame.h * float(v[W + "Size/Height"])
        channel = v[W + "Style/Channel"]
        style = v[W + "Style/Style"]
        vertical = v[W + "Style/Orientation"] == "Vertical"
        mirror = bool(v[W + "Style/Mirror"])
        amp = float(v[W + "Parameters/Amplitude"])
        thick = float(v[W + "Parameters/Thickness (dp)"]) * frame.h / 720.0
        alpha = float(v[W + "Parameters/Smoothing"])
        chans = ["Left", "Right"] if channel == "Stereo" else [channel]
        quads, colors, dot_quads, dot_cols = [], [], [], []
        c1, c2 = hex_to_rgba(v[W + "Color/Color From"]), hex_to_rgba(v[W + "Color/Color To"])
        cmode = v[W + "Color/Color Mode"]
        for ci, ch in enumerate(chans):
            wave, envelope = self._window(frame, ch)
            wave = np.clip(np.nan_to_num(wave) * amp, -1, 1)
            key = (ci, len(wave))
            if self.smooth is None or self.smooth.get("key") != key:
                self.smooth = {"key": key, "v": wave.copy()}
            sm = self.smooth["v"]
            sm += (wave - sm) * (1.0 - alpha)
            wave = sm
            n = len(wave)
            xs = np.linspace(0, w, n)
            lane_h = h / len(chans)
            mid = lane_h * (ci + 0.5)
            half = lane_h / 2.0
            if envelope:
                ys_top = mid + wave * half if mirror else (mid - half) + wave * lane_h
                ys_bot = mid - wave * half if mirror else np.full(n, mid - half)
            else:
                ys_top = mid + wave * half
                ys_bot = mid - wave * half if mirror else None
            lines = [ys_top] + ([ys_bot] if ys_bot is not None and (mirror or envelope) else [])
            level = np.abs(wave)
            if cmode == "By Amplitude":
                tcol = np.clip(level, 0, 1)
            elif cmode == "Gradient":
                tcol = np.linspace(0, 1, n)
            else:
                tcol = np.zeros(n)
            col = np.asarray(c1, np.float32) + (np.asarray(c2, np.float32) - c1) * tcol[:, None]

            def place(xx, yy):
                return np.stack([xx, yy], 1)
            if style == "Filled":
                base_y = ys_bot if ys_bot is not None else np.full(n, mid if not envelope else mid - half)
                a0, a1 = place(xs[:-1], base_y[:-1]), place(xs[1:], base_y[1:])
                b0, b1 = place(xs[:-1], ys_top[:-1]), place(xs[1:], ys_top[1:])
                quads.append(np.stack([a0, a1, b1, b0], 1))
                cc = col[:-1]
                colors.append(np.stack([cc * [1, 1, 1, 0.55], cc * [1, 1, 1, 0.55], cc, cc], 1))
            elif style == "Bars":
                bw = max(1.0, w / n * 0.6)
                y0 = ys_bot if ys_bot is not None else np.full(n, mid)
                x0, x1 = xs - bw / 2, xs + bw / 2
                q = np.stack([place(x0, y0), place(x1, y0), place(x1, ys_top), place(x0, ys_top)], 1)
                quads.append(q)
                colors.append(np.repeat(col[:, None, :], 4, axis=1))
            elif style == "Dotted":
                r = max(1.5, thick)
                for yy in lines:
                    c = place(xs, yy)
                    dot_quads.append(np.stack([c + [-r, -r], c + [r, -r], c + [r, r], c + [-r, r]], 1))
                    dot_cols.append(np.repeat(col[:, None, :], 4, axis=1))
            else:
                for yy in lines:
                    q = thick_polyline(place(xs, yy), max(1.0, thick))
                    quads.append(q)
                    cc = col[:-1]
                    colors.append(np.repeat(cc[:, None, :], 4, axis=1))
        def finish(qs):
            q = np.concatenate(qs).astype(np.float32)
            return q[..., ::-1].copy() if vertical else q    # vertical: swap x / y
        self.batch.set(finish(quads) if quads else np.zeros((0, 4, 2), np.float32),
                       np.concatenate(colors) if colors else np.zeros((0, 4), np.float32))
        self.dots.set(finish(dot_quads) if dot_quads else np.zeros((0, 4, 2), np.float32),
                      np.concatenate(dot_cols) if dot_cols else np.zeros((0, 4), np.float32))
        return (h, w) if vertical else (w, h)


# ------------------------------------------------------------------ SHAPE
class ShapeLayer(BaseLayer):
    def build(self):
        super().build()
        self.batch = QuadBatch(self.content)
        self.spin = 0.0
        self.kick = 0.0

    def _level(self, frame, source):
        snap = frame.snapshot
        if source == "Nothing":
            return 0.0
        if source == "Beat":
            return float(frame.measure.pulse(2.0))
        if source == "Volume":
            return min(1.0, float(snap.get("rms", 0.0)) * 2.0)
        return float(snap.get(source.lower(), 0.0))

    def render(self, frame):
        v = self.values
        m = min(frame.w, frame.h)
        rx = m * float(v[SH + "Geometry/Width"]) / 2.0
        ry = m * float(v[SH + "Geometry/Height"]) / 2.0
        A = SH + "Animation/"
        level = self._level(frame, v[A + "Scale With"])
        k = 1.0 + level * float(v[A + "Scale Amount"])
        rx, ry = rx * k, ry * k
        kind = v[SH + "Geometry/Shape Type"]
        pts, closed = shape_outline(kind, v, rx, ry)
        morph_to = v[A + "Morph To"]
        morph = float(v[A + "Morph Amount"])
        if v[A + "Morph With"] != "Nothing":
            morph = min(1.0, morph + self._level(frame, v[A + "Morph With"]))
        if morph_to != "None" and morph > 0 and closed and len(pts) >= 3:
            other, oc = shape_outline(morph_to, v, rx, ry)
            if oc and len(other) >= 3:
                a, b = resample_closed(pts, 128), resample_closed(other, 128)
                pts = a + (b - a) * morph
        # spin + rotate on beat
        self.spin += float(v[A + "Spin (deg/s)"]) * frame.dt
        if frame.snapshot.get("beat"):
            self.kick = 1.0
        self.kick = max(0.0, self.kick - frame.dt * 4.0)
        ang = math.radians(self.spin + float(v[A + "Rotate On Beat (deg)"]) * self.kick)
        if ang and len(pts):
            c, s = math.cos(ang), math.sin(ang)
            pts = pts @ np.array([[c, s], [-s, c]], np.float32)
        quads, colors = [], []
        F = SH + "Fill/"
        fill = v[F + "Fill Mode"]
        extent = max(rx, ry)
        if fill != "None" and closed and len(pts) >= 3:
            q = fan_quads(pts, (0.0, 0.0) if kind != "Path" else None)
            quads.append(q)
            colors.append(gradient_colors(q, fill, hex_to_rgba(v[F + "Fill Color"]), hex_to_rgba(v[F + "Fill Color 2"]),
                                          float(v[F + "Gradient Angle"]), extent))
        S = SH + "Stroke/"
        sw = float(v[S + "Stroke Width"]) * m / 360.0
        if sw > 0 and len(pts) >= 2:
            dash, gap = float(v[S + "Dash Length"]) * m / 360.0, float(v[S + "Dash Gap"]) * m / 360.0
            col = hex_to_rgba(v[S + "Stroke Color"])
            for piece in dashed(pts, dash, gap, closed):
                q = thick_polyline(piece, sw, closed=closed and dash <= 0)
                if len(q):
                    quads.append(q)
                    colors.append(np.broadcast_to(np.asarray(col, np.float32), q.shape[:2] + (4,)).copy())
        if quads:
            allq = np.concatenate(quads) + [frame.w / 2.0, frame.h / 2.0]
            self.batch.set(allq.astype(np.float32), np.concatenate(colors))
        else:
            self.batch.set(np.zeros((0, 4, 2), np.float32), np.zeros((0, 4), np.float32))
        return frame.w, frame.h


RENDERERS.update({"WAVEFORM": WaveformLayer, "SHAPE": ShapeLayer})
