"""Texture cache + generated textures (soft particle dot, album-art placeholder)."""
import os

import numpy as np
from kivy.graphics.texture import Texture

_cache = {}
_generated = {}


def load(path):
    """Texture for an image file, cached by path + mtime. None when unavailable."""
    if not path or not os.path.exists(path):
        return None
    try:
        key = (path, os.path.getmtime(path))
    except OSError:
        return None
    tex = _cache.get(key)
    if tex is None:
        try:
            from kivy.core.image import Image as CoreImage
            tex = CoreImage(path, mipmap=False).texture
        except Exception:
            tex = None
        _cache[key] = tex
    return tex


def _from_rgba(arr):
    h, w = arr.shape[:2]
    tex = Texture.create(size=(w, h), colorfmt="rgba")
    tex.blit_buffer(np.ascontiguousarray(arr, dtype=np.uint8).tobytes(),
                    colorfmt="rgba", bufferfmt="ubyte")
    tex.mag_filter = tex.min_filter = "linear"
    return tex


def soft_dot(size=64):
    tex = _generated.get(("dot", size))
    if tex is None:
        y, x = np.mgrid[0:size, 0:size]
        c = (size - 1) / 2.0
        d = np.sqrt((x - c) ** 2 + (y - c) ** 2) / c
        a = np.clip(1.0 - d, 0, 1) ** 1.6
        arr = np.zeros((size, size, 4), np.float32)
        arr[..., :3] = 255
        arr[..., 3] = a * 255
        tex = _generated[("dot", size)] = _from_rgba(arr)
    return tex


def placeholder_art(size=256):
    """Acid-green gradient disc used when no image / album art exists."""
    tex = _generated.get(("art", size))
    if tex is None:
        y, x = np.mgrid[0:size, 0:size] / float(size - 1)
        arr = np.zeros((size, size, 4), np.float32)
        arr[..., 0] = (0.78 * (1 - y) + 0.10 * y) * 255
        arr[..., 1] = (1.00 * (1 - y) + 0.35 * y) * 255
        arr[..., 2] = (0.00 * (1 - x) + 0.45 * x) * 255
        ring = np.sqrt((x - 0.5) ** 2 + (y - 0.5) ** 2)
        groove = 0.85 + 0.15 * np.sin(ring * 90)
        arr[..., :3] *= groove[..., None]
        arr[..., :3] *= np.where(ring < 0.07, 0.15, 1.0)[..., None]
        arr[..., 3] = 255
        tex = _generated[("art", size)] = _from_rgba(arr)
    return tex


def white():
    tex = _generated.get("white")
    if tex is None:
        tex = _generated["white"] = _from_rgba(np.full((2, 2, 4), 255, np.float32))
    return tex
