"""Color palettes: built-ins, user palettes, album-art extraction, apply to layers.

A palette is 4 colors, dark -> light: [deep, main, accent, highlight] (#RRGGBB).
``apply_palette`` writes them into every colorable property of every layer of a
ProjectModel, so one tap recolors bars, particles, text, glow and background.
"""
import colorsys

import numpy as np

BUILTIN = {
    "Acid":   ["#2E5E00", "#7CC400", "#C6FF00", "#F4FFB0"],
    "Neon":   ["#3A0CA3", "#7209B7", "#F72585", "#FFB3E6"],
    "Sunset": ["#5F0F40", "#E36414", "#FB8B24", "#FFE066"],
    "Ocean":  ["#03045E", "#0077B6", "#00B4D8", "#CAF0F8"],
    "Fire":   ["#370617", "#D00000", "#FF7B00", "#FFF3B0"],
    "Aurora": ["#0B3D2E", "#00C896", "#5BFFB0", "#B8F2FF"],
    "Mono":   ["#3A3A3A", "#8A8A8A", "#D9D9D9", "#FFFFFF"],
}


def _hex(rgb, alpha="FF"):
    r, g, b = [max(0, min(255, int(round(c * 255)))) for c in rgb[:3]]
    return "#%02X%02X%02X%s" % (r, g, b, alpha)


def _rgb(hex_color):
    h = hex_color.lstrip("#")
    return [int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)]


def _with_alpha(hex_color, alpha):
    return hex_color[:7].upper() + alpha


# ---------------------------------------------------------------- extraction
def _pixels(path):
    """RGB float array (n, 3) from an image file, downsampled to <= ~4k pixels."""
    try:
        from PIL import Image
        im = Image.open(path).convert("RGB")
        im.thumbnail((64, 64))
        return np.asarray(im, np.float32).reshape(-1, 3) / 255.0
    except Exception:
        pass
    from kivy.core.image import Image as CoreImage
    tex = CoreImage(path, mipmap=False).texture
    arr = np.frombuffer(tex.pixels, np.uint8).reshape(tex.height, tex.width, 4)
    step = max(1, int(max(tex.width, tex.height) / 64))
    arr = arr[::step, ::step, :3].reshape(-1, 3)
    return arr.astype(np.float32) / 255.0


def extract_palette(path, k=6, iterations=10):
    """4-color palette (dark -> light) from an image using k-means on its pixels."""
    px = _pixels(path)
    if len(px) < k:
        raise ValueError("image too small")
    rng = np.random.default_rng(7)
    centers = px[rng.choice(len(px), k, replace=False)]
    for _ in range(iterations):
        d = ((px[:, None, :] - centers[None, :, :]) ** 2).sum(-1)
        lab = d.argmin(1)
        for i in range(k):
            m = lab == i
            if m.any():
                centers[i] = px[m].mean(0)
    counts = np.bincount(lab, minlength=k).astype(np.float32)
    hsv = [colorsys.rgb_to_hsv(*c) for c in centers]
    # vivid colors that cover a real part of the image make the best accents
    vivid = [(hsv[i][1] * (0.4 + hsv[i][2]) * np.sqrt(counts[i] + 1), i) for i in range(k)]
    vivid.sort(reverse=True)
    main = centers[vivid[0][1]]
    accent = centers[vivid[1][1]] if len(vivid) > 1 else main
    h, s, v = colorsys.rgb_to_hsv(*main)
    # keep colors bright enough to read on a dark canvas
    main = colorsys.hsv_to_rgb(h, max(s, 0.45), max(v, 0.75))
    ha, sa, va = colorsys.rgb_to_hsv(*accent)
    if abs(ha - h) < 0.04 and abs(sa - s) < 0.1:
        ha = (h + 0.12) % 1.0
    accent = colorsys.hsv_to_rgb(ha, max(sa, 0.5), max(va, 0.85))
    deep = colorsys.hsv_to_rgb(h, min(1.0, max(s, 0.5)), 0.32)
    light = colorsys.hsv_to_rgb(ha, min(s, 0.25), 1.0)
    return [_hex(deep)[:7], _hex(main)[:7], _hex(accent)[:7], _hex(light)[:7]]


# ---------------------------------------------------------------- apply
def apply_palette(project, colors, background=True):
    """Recolor every layer of a ProjectModel with a 4-color palette."""
    deep, main, accent, light = [c[:7].upper() for c in (list(colors) + list(colors))[:4]]
    for layer in project.layers:
        m = layer.module
        if m == "BARS":
            layer.set("Segment 1/Segment 1/Color From", main + "FF")
            layer.set("Segment 1/Segment 1/Color To", accent + "FF")
            layer.set("Segment 2/Segment 2/Color From", light + "99")
            layer.set("Segment 2/Segment 2/Color To", main + "66")
            layer.set("Segment 3/Segment 3/Color From", accent + "FF")
            layer.set("Segment 3/Segment 3/Color To", light + "FF")
        elif m == "PARTICLES":
            layer.set("Appearance/Look/Color From", accent + "FF")
            layer.set("Appearance/Look/Color To", main + "00")
        elif m == "TEXT":
            layer.set("Appearance/Fill/Text Color", light + "FF")
        elif m == "BLUREFFECT":
            layer.set("Blur Settings/Layer 1/1Layer Color", main + "99")
            layer.set("Blur Settings/Layer 2/2Layer Color", accent + "66")
            layer.set("Blur Settings/Layer 3/3Layer Color", deep + "55")
        elif m == "IMAGE":
            pass
        elif m == "CUSTOMSHADER":
            for var in layer.get("Variables/Variable Mappings/Mappings") or []:
                if var.get("type") == "Color" and var.get("name") == "edgeColor":
                    var["value"] = main + "CC"
        elif m == "COMPOSITION" and background:
            r, g, b = _rgb(deep)
            layer.set("Render Settings/Canvas/Background Color", _hex((r * 0.18, g * 0.18, b * 0.18)))
    return [deep, main, accent, light]
