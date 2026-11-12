"""Shape paths shared by Bars (layout path), Image (mathematical mask) and Particles.

Every shape is defined in unit space, centred on (0, 0) and fitting in [-1, 1].
"""
import math

import numpy as np

OPEN_SHAPES = ("None", "Line", "HorizontalLine", "VerticalLine", "WaveformPath")


def _regular(n, rotation=math.pi / 2):
    a = rotation + np.arange(n) * 2 * math.pi / n
    return np.stack([np.cos(a), np.sin(a)], 1)


def _star(points=5, inner=0.45):
    a = math.pi / 2 + np.arange(points * 2) * math.pi / points
    r = np.where(np.arange(points * 2) % 2 == 0, 1.0, inner)
    return np.stack([np.cos(a) * r, np.sin(a) * r], 1)


def _heart(n=160):
    t = np.linspace(0, 2 * math.pi, n, endpoint=False)
    x = 16 * np.sin(t) ** 3
    y = 13 * np.cos(t) - 5 * np.cos(2 * t) - 2 * np.cos(3 * t) - np.cos(4 * t)
    pts = np.stack([x, y + 2.5], 1) / 17.0
    return pts


def _capsule(n=160, ratio=0.45):
    # stadium: two semicircles joined by straight edges
    r = ratio
    half = 1.0 - r
    right = [(half + r * math.cos(a), r * math.sin(a))
             for a in np.linspace(-math.pi / 2, math.pi / 2, n // 2)]
    left = [(-half + r * math.cos(a), r * math.sin(a))
            for a in np.linspace(math.pi / 2, 3 * math.pi / 2, n // 2)]
    return np.array(right + left)


def _custom(n=200):
    # "CustomPath": a smooth superformula flower (no external path file needed)
    t = np.linspace(0, 2 * math.pi, n, endpoint=False)
    r = 0.75 + 0.25 * np.cos(6 * t)
    return np.stack([np.cos(t + math.pi / 2) * r, np.sin(t + math.pi / 2) * r], 1)


def outline(name):
    """Closed polygon (CCW) for a shape, or None for open/line shapes."""
    if name == "Circle":
        return _regular(160)
    if name == "Oval":
        p = _regular(160)
        return p * [1.0, 0.65]
    if name == "SidedPolygon":
        return _regular(8, math.pi / 8)
    if name == "Pentagon":
        return _regular(5)
    if name == "Hexagon":
        return _regular(6, 0.0)
    if name == "Triangle":
        return _regular(3)
    if name == "Square":
        return np.array([(1, 1), (-1, 1), (-1, -1), (1, -1)], float)
    if name == "Rectangle":
        return np.array([(1, 0.6), (-1, 0.6), (-1, -0.6), (1, -0.6)], float)
    if name == "Star":
        return _star()
    if name == "Heart":
        return _heart()
    if name == "Capsule":
        return _capsule()
    if name == "CustomPath":
        return _custom()
    return None


def mask_polygon(name):
    """Polygon used to clip an Image. Open shapes clip to the full rectangle."""
    poly = outline(name)
    if poly is None:
        poly = np.array([(1, 1), (-1, 1), (-1, -1), (1, -1)], float)
    return poly


def _ensure_ccw(poly):
    x, y = poly[:, 0], poly[:, 1]
    area = np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)
    return poly if area > 0 else poly[::-1]


def sample_path(name, count, waveform=None):
    """Return (points[count, 2], normals[count, 2], closed) in unit space.

    Points are spread evenly by arc length; normals point outward (closed
    shapes) or up/right (open shapes).
    """
    count = max(1, int(count))
    t = (np.arange(count) + 0.5) / count
    if name in ("None", "Line", "HorizontalLine"):
        pts = np.stack([t * 2 - 1, np.zeros(count)], 1)
        nrm = np.tile([0.0, 1.0], (count, 1))
        return pts, nrm, False
    if name == "VerticalLine":
        pts = np.stack([np.zeros(count), t * 2 - 1], 1)
        nrm = np.tile([1.0, 0.0], (count, 1))
        return pts, nrm, False
    if name == "WaveformPath":
        if waveform is None or len(waveform) < 2:
            wave = np.zeros(count)
        else:
            wave = np.interp(t, np.linspace(0, 1, len(waveform)), waveform)
        pts = np.stack([t * 2 - 1, wave * 0.5], 1)
        d = np.gradient(pts, axis=0)
        nrm = np.stack([-d[:, 1], d[:, 0]], 1)
        nrm /= np.maximum(np.linalg.norm(nrm, axis=1, keepdims=True), 1e-6)
        return pts, nrm, False

    poly = _ensure_ccw(outline(name))
    closed = np.vstack([poly, poly[:1]])
    seg = np.diff(closed, axis=0)
    seg_len = np.linalg.norm(seg, axis=1)
    cum = np.concatenate([[0.0], np.cumsum(seg_len)])
    total = cum[-1]
    s = t * total
    idx = np.clip(np.searchsorted(cum, s, side="right") - 1, 0, len(seg) - 1)
    frac = (s - cum[idx]) / np.maximum(seg_len[idx], 1e-9)
    pts = closed[idx] + seg[idx] * frac[:, None]
    tangent = seg[idx] / np.maximum(seg_len[idx], 1e-9)[:, None]
    nrm = np.stack([tangent[:, 1], -tangent[:, 0]], 1)   # outward for CCW
    return pts, nrm, True


def path_extent(name, path_size, width, height):
    """Pixel scale (sx, sy) that maps unit shape space to the canvas."""
    m = min(width, height)
    if name in OPEN_SHAPES:
        if name == "VerticalLine":
            return m, height * min(1.0, path_size * 3.75) * 0.5
        return width * min(1.0, path_size * 3.75) * 0.5, m
    r = m * path_size
    return r, r
