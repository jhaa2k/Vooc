"""Material-3-style color schemes from a seed color (pure Python, no kivy).

``build_scheme(dark, base, seed, contrast, vision)`` returns the same keys as the
built-in schemes. ``seed=None`` keeps the hand-tuned acid-green base scheme.
"""
import colorsys

CONTRAST = ["Standard", "Medium", "High"]
VISION = ["None", "Protanopia", "Deuteranopia", "Tritanopia", "Monochrome"]
MODES = ["System", "Dark", "Light"]

# hues that stay distinguishable for each color-vision deficiency (primary, secondary)
_SAFE = {"Protanopia": (0.58, 0.11), "Deuteranopia": (0.60, 0.09), "Tritanopia": (0.97, 0.50)}


def _rgb(h):
    h = h.lstrip("#")
    return [int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)]


def _hex(rgb):
    return "#%02X%02X%02X" % tuple(max(0, min(255, int(round(c * 255)))) for c in rgb)


def _hsl(h, s, l):
    return _hex(colorsys.hls_to_rgb(h % 1.0, max(0, min(1, l)), max(0, min(1, s))))


def luminance(hex_color):
    def ch(c):
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (ch(c) for c in _rgb(hex_color))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(a, b):
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def from_seed(seed, dark):
    h, l, s = colorsys.rgb_to_hls(*_rgb(seed))
    s = max(0.55, s)
    h2 = (h + 0.14) % 1.0
    n = min(0.18, s * 0.2)          # neutral tint
    if dark:
        return {
            "bg": _hsl(h, n, 0.05), "surface": _hsl(h, n, 0.07), "surface1": _hsl(h, n, 0.095),
            "surface2": _hsl(h, n, 0.12), "surface3": _hsl(h, n, 0.15), "outline": _hsl(h, n, 0.24),
            "outline_var": _hsl(h, n, 0.17), "on_surface": _hsl(h, 0.2, 0.91), "on_var": _hsl(h, 0.12, 0.64),
            "primary": _hsl(h, s, 0.62), "on_primary": _hsl(h, s, 0.08),
            "primary_container": _hsl(h, s * 0.8, 0.2), "on_primary_container": _hsl(h, s, 0.85),
            "secondary": _hsl(h2, 0.8, 0.66), "secondary_container": _hsl(h2, 0.5, 0.16),
            "on_secondary_container": _hsl(h2, 0.8, 0.87), "error": "#FFB4AB", "scrim": "#000000",
        }
    return {
        "bg": _hsl(h, n * 2, 0.97), "surface": _hsl(h, n * 2, 0.985), "surface1": _hsl(h, n * 2.5, 0.94),
        "surface2": _hsl(h, n * 2.5, 0.915), "surface3": _hsl(h, n * 2.5, 0.885), "outline": _hsl(h, 0.08, 0.46),
        "outline_var": _hsl(h, 0.12, 0.78), "on_surface": _hsl(h, 0.2, 0.09), "on_var": _hsl(h, 0.12, 0.29),
        "primary": _hsl(h, s, 0.3), "on_primary": "#FFFFFF",
        "primary_container": _hsl(h, s, 0.84), "on_primary_container": _hsl(h, s, 0.08),
        "secondary": _hsl(h2, 0.9, 0.24), "secondary_container": _hsl(h2, 0.7, 0.84),
        "on_secondary_container": _hsl(h2, 0.9, 0.07), "error": "#BA1A1A", "scrim": "#000000",
    }


def _with_hue(hex_color, hue, sat=None):
    _h, l, s = colorsys.rgb_to_hls(*_rgb(hex_color))
    return _hsl(hue, s if sat is None else sat, l)


def _grey(hex_color):
    _h, l, _s = colorsys.rgb_to_hls(*_rgb(hex_color))
    return _hsl(0, 0, l)


def build_scheme(dark, base, seed=None, contrast="Standard", vision="None"):
    sc = dict(base) if not seed else from_seed(seed, dark)
    if vision in _SAFE:
        p, s2 = _SAFE[vision]
        for k in ("primary", "on_primary_container", "primary_container"):
            sc[k] = _with_hue(sc[k], p, 0.85)
        for k in ("secondary", "secondary_container", "on_secondary_container"):
            sc[k] = _with_hue(sc[k], s2, 0.85)
    elif vision == "Monochrome":
        sc = {k: _grey(v) for k, v in sc.items()}
    if contrast in ("Medium", "High"):
        hi = contrast == "High"
        if dark:
            sc["bg"] = "#000000" if hi else sc["bg"]
            sc["on_surface"] = "#FFFFFF"
            sc["on_var"] = "#E6E6E6" if hi else _hsl(0, 0, 0.8)
            sc["outline"] = "#BDBDBD" if hi else _hsl(0, 0, 0.5)
        else:
            sc["bg"] = "#FFFFFF" if hi else sc["bg"]
            sc["on_surface"] = "#000000"
            sc["on_var"] = "#1A1A1A" if hi else _hsl(0, 0, 0.2)
            sc["outline"] = "#303030" if hi else _hsl(0, 0, 0.35)
        # make sure the primary is readable against the background
        h, l, s = colorsys.rgb_to_hls(*_rgb(sc["primary"]))
        target = 7.0 if hi else 4.5
        step = 0.03 if dark else -0.03
        while contrast_ratio(sc["primary"], sc["bg"]) < target and 0.02 < l < 0.98:
            l += step
            sc["primary"] = _hsl(h, s, l)
        sc["on_primary"] = "#000000" if luminance(sc["primary"]) > 0.4 else "#FFFFFF"
    return sc
