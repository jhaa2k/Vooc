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


class Animated:
    """Frames of an animated GIF: textures + per-frame durations (seconds)."""

    def __init__(self, textures_, delays):
        self.frames = textures_
        self.delays = [max(0.02, float(d)) for d in delays]
        self.total = sum(self.delays)
        self.width, self.height = textures_[0].width, textures_[0].height

    def at_mode(self, t, mode="Loop", start=0):
        from .timing import gif_index
        return self.frames[gif_index(self.delays, t, mode, start)]

    def step(self, k, mode="Loop", start=0):
        """Frame after k discrete steps (beat sync)."""
        from .timing import gif_index
        return self.frames[gif_index([1.0] * len(self.frames), float(k), mode, start)]

    def at(self, t):
        if len(self.frames) == 1 or self.total <= 0:
            return self.frames[0]
        t = t % self.total
        for tex, d in zip(self.frames, self.delays):
            if t < d:
                return tex
            t -= d
        return self.frames[-1]


_anim_cache = {}
MAX_GIF_FRAMES = 60
MAX_GIF_SIDE = 480


def _is_gif(path):
    try:
        with open(path, "rb") as f:
            return f.read(6) in (b"GIF87a", b"GIF89a")
    except OSError:
        return False


def _gif_android(path):
    import base64
    from jnius import autoclass
    dec = autoclass("com.jhaa2k.vooc.GifFrames")()
    if not dec.decode(path, MAX_GIF_FRAMES, MAX_GIF_SIDE):
        return None
    w, h, n = dec.getWidth(), dec.getHeight(), dec.getCount()
    texs = []
    for i in range(n):
        raw = base64.b64decode(str(dec.getFrame(i)))
        arr = np.frombuffer(raw, np.uint8).reshape(h, w, 4)[::-1]
        texs.append(_from_rgba(arr))
    if not texs:
        return None
    return Animated(texs, [dec.getStepMs() / 1000.0] * len(texs))


def _gif_pil(path):
    from PIL import Image, ImageSequence
    im = Image.open(path)
    texs, delays = [], []
    for i, fr in enumerate(ImageSequence.Iterator(im)):
        if i >= MAX_GIF_FRAMES:
            break
        rgba = fr.convert("RGBA")
        k = min(1.0, MAX_GIF_SIDE / float(max(rgba.size)))
        if k < 1:
            rgba = rgba.resize((max(1, int(rgba.width * k)), max(1, int(rgba.height * k))))
        arr = np.asarray(rgba, np.uint8)[::-1]
        texs.append(_from_rgba(arr))
        delays.append(fr.info.get("duration", im.info.get("duration", 100)) / 1000.0)
    return Animated(texs, delays) if texs else None


def load_animated(path):
    """Animated object for GIF files with more than one frame, else None."""
    if not path or not os.path.exists(path) or not _is_gif(path):
        return None
    try:
        key = (path, os.path.getmtime(path))
    except OSError:
        return None
    if key in _anim_cache:
        return _anim_cache[key]
    anim = None
    try:
        if "ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ:
            anim = _gif_android(path)
        else:
            anim = _gif_pil(path)
    except Exception as exc:
        print("Vooc: GIF decode failed (%s), using first frame" % exc)
        anim = None
    if anim is not None and len(anim.frames) < 2:
        anim = None
    _anim_cache[key] = anim
    return anim


def frame(path, t):
    """Texture of an image at time t (animated GIFs advance, others are static)."""
    anim = load_animated(path)
    if anim is not None:
        return anim.at(t)
    return load(path)


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
