"""Pure numpy geometry for vector layers (no Kivy import, unit-testable)."""
import math
import re

import numpy as np

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
