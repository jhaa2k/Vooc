"""Material-style design tokens: acid green on dark / light surfaces."""
from kivy.metrics import dp, sp


def _hex(value, alpha=1.0):
    h = value.lstrip("#")
    return [int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)] + [alpha]


PALETTES = {
    "dark": {
        "bg": _hex("#0B0D0A"), "surface": _hex("#151812"), "surface_hi": _hex("#1F241A"),
        "surface_top": _hex("#262C20"), "outline": _hex("#2E3527"),
        "on_surface": _hex("#EEF3E6"), "muted": _hex("#8E9886"),
        "primary": _hex("#C6FF00"), "on_primary": _hex("#0E1400"),
        "primary_soft": _hex("#C6FF00", 0.14), "secondary": _hex("#5BFFB0"),
        "danger": _hex("#FF6B5B"), "warning": _hex("#FFC14D"), "scrim": _hex("#000000", 0.6),
    },
    "light": {
        "bg": _hex("#F4F7EC"), "surface": _hex("#FFFFFF"), "surface_hi": _hex("#E9EFDC"),
        "surface_top": _hex("#DDE5CD"), "outline": _hex("#D3DBC4"),
        "on_surface": _hex("#141812"), "muted": _hex("#5E6857"),
        "primary": _hex("#5E8C00"), "on_primary": _hex("#FFFFFF"),
        "primary_soft": _hex("#8BC400", 0.16), "secondary": _hex("#00A86B"),
        "danger": _hex("#C62828"), "warning": _hex("#B26A00"), "scrim": _hex("#000000", 0.45),
    },
}


class Theme:
    dark = True

    def __getattr__(self, name):
        palette = PALETTES["dark" if self.dark else "light"]
        if name in palette:
            return list(palette[name])
        raise AttributeError(name)


T = Theme()

# spacing / type scale
PAD = dp(16)
GAP = dp(10)
RADIUS = dp(18)
ROW_H = dp(52)
FS_TITLE = sp(20)
FS_BODY = sp(14)
FS_LABEL = sp(12)
FS_SMALL = sp(11)
MONO = "data/fonts/RobotoMono-Regular.ttf"
