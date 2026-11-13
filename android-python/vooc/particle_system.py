"""Lightweight audio-reactive particle simulation for the Vooc preview.

This module intentionally has no Kivy dependency. It returns plain dictionaries
that the Spectrum widget renders, keeping simulation independent of the UI.
"""
import math
import random


class ParticleSystem:
    def __init__(self, seed=None):
        self._rng = random.Random(seed)
        self._particles = []
        self._spawn_accumulator = 0.0

    @staticmethod
    def _number(settings, key, default):
        try:
            return float(settings.get(key, default))
        except (TypeError, ValueError):
            return float(default)

    def update(self, dt, settings, width, height, audio_level=0.0, beat=False):
        dt = max(0.0, min(float(dt), 0.1))
        width = max(1.0, float(width))
        height = max(1.0, float(height))
        settings = settings or {}

        limit = max(0, min(2000, int(self._number(settings, "count", 120))))
        lifetime = max(0.05, min(60.0, self._number(settings, "lifetime", 2.0)))
        spawn_time = max(0.005, min(5.0, self._number(settings, "spawn_time", 0.035)))
        speed = max(0.0, min(2000.0, self._number(settings, "speed", 65.0)))
        speed_random = max(0.0, min(1.0, self._number(settings, "speed_random", 0.35)))
        gravity_x = self._number(settings, "gravity_x", 0.0)
        gravity_y = self._number(settings, "gravity_y", -20.0)
        base_size = max(0.5, min(100.0, self._number(settings, "size", 2.5)))
        angle = math.radians(self._number(settings, "angle", 90.0))
        audio = max(0.0, min(1.0, self._number({"v": audio_level}, "v", 0.0)))
        reactive = bool(settings.get("audio_reactive", True))
        intensity = (0.35 + audio * 1.65) if reactive else 1.0
        if beat:
            intensity *= 1.5

        area = str(settings.get("spawn_area", "Rectangle")).lower()
        if area in ("point", "center"):
            origin_x, origin_y = width * 0.5, height * 0.5
        elif area in ("bottom", "bottom edge"):
            origin_x, origin_y = self._rng.uniform(0, width), 0.0
        elif area in ("top", "top edge"):
            origin_x, origin_y = self._rng.uniform(0, width), height
        elif area in ("left", "left edge"):
            origin_x, origin_y = 0.0, self._rng.uniform(0, height)
        elif area in ("right", "right edge"):
            origin_x, origin_y = width, self._rng.uniform(0, height)
        else:
            origin_x, origin_y = self._rng.uniform(width * .15, width * .85), self._rng.uniform(height * .15, height * .85)

        spawn_rate = 1.0 / spawn_time
        self._spawn_accumulator += dt * spawn_rate * (0.5 + audio if reactive else 1.0)
        spawn_count = min(limit - len(self._particles), int(self._spawn_accumulator))
        if spawn_count > 0:
            self._spawn_accumulator -= spawn_count
            for _ in range(spawn_count):
                spread = self._rng.uniform(-math.pi * 0.45, math.pi * 0.45)
                direction = angle + spread
                velocity = speed * intensity * self._rng.uniform(1.0 - speed_random, 1.0 + speed_random)
                self._particles.append({
                    "x": origin_x,
                    "y": origin_y,
                    "vx": math.cos(direction) * velocity,
                    "vy": math.sin(direction) * velocity,
                    "age": 0.0,
                    "life": lifetime * self._rng.uniform(0.65, 1.35),
                    "size": base_size * self._rng.uniform(0.65, 1.35),
                })

        alive = []
        for particle in self._particles:
            particle["age"] += dt
            if particle["age"] >= particle["life"]:
                continue
            particle["vx"] += gravity_x * dt
            particle["vy"] += gravity_y * dt
            particle["x"] += particle["vx"] * dt
            particle["y"] += particle["vy"] * dt
            if (-100 <= particle["x"] <= width + 100 and
                    -100 <= particle["y"] <= height + 100):
                alive.append(particle)
        self._particles = alive[:limit]
        return [dict(particle) for particle in self._particles]

    def clear(self):
        self._particles.clear()
        self._spawn_accumulator = 0.0
