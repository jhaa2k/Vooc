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
from .geometry import (dashed, fan_quads, gradient_colors, parse_path, resample_closed,  # noqa: F401
                       shape_outline, thick_polyline)
from .layers import RENDERERS, BaseLayer, QuadBatch

W = "Waveform/"
SH = "Shape/"


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
