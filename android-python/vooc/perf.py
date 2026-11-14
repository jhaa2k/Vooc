"""Performance presets, device RAM tiers and automatic preview downgrade (pure Python)."""

PRESET_NAMES = ["Low", "Medium", "High", "Ultra"]
PRESETS = {
    "Low": {"preview_fps": 30, "render_scale": 0.5, "heavy_blur": False, "max_bars": 64, "max_particles": 300},
    "Medium": {"preview_fps": 30, "render_scale": 0.75, "heavy_blur": False, "max_bars": 128, "max_particles": 1000},
    "High": {"preview_fps": 60, "render_scale": 1.0, "heavy_blur": True, "max_bars": 256, "max_particles": 2000},
    "Ultra": {"preview_fps": 60, "render_scale": 1.0, "heavy_blur": True, "max_bars": 256, "max_particles": 5000},
}
GB = 1024 ** 3


def ram_tier(total_bytes):
    """Suggested preset for the device memory (ActivityManager.MemoryInfo.totalMem)."""
    if not total_bytes or total_bytes <= 0:
        return "High"
    if total_bytes < 3 * GB:
        return "Low"
    if total_bytes < 5 * GB:
        return "Medium"
    if total_bytes < 9 * GB:
        return "High"
    return "Ultra"


def device_ram():
    try:
        from jnius import autoclass
        act = autoclass("org.kivy.android.PythonActivity").mActivity
        Context = autoclass("android.content.Context")
        am = act.getSystemService(Context.ACTIVITY_SERVICE)
        info = autoclass("android.app.ActivityManager$MemoryInfo")()
        am.getMemoryInfo(info)
        return int(info.totalMem)
    except Exception:
        return 0


def match(perf):
    """Name of the preset equal to ``perf`` or "Custom"."""
    for name in PRESET_NAMES:
        if all(perf.get(k) == v for k, v in PRESETS[name].items()):
            return name
    return "Custom"


def lower(name):
    i = PRESET_NAMES.index(name) if name in PRESET_NAMES else len(PRESET_NAMES) - 1
    return PRESET_NAMES[max(0, i - 1)]


class AutoTuner:
    """Feed measured preview fps once per second. When fps stays below ``ratio`` x target for
    ``window`` seconds the next lower preset is returned (once per ``cooldown`` seconds)."""

    def __init__(self, ratio=0.7, window=6, cooldown=20):
        self.ratio, self.window, self.cooldown = ratio, window, cooldown
        self.low = 0
        self.since = cooldown

    def feed(self, fps, target, current):
        self.since += 1
        if current == "Low" or target <= 0 or fps <= 0:
            self.low = 0
            return None
        self.low = self.low + 1 if fps < target * self.ratio else 0
        if self.low >= self.window and self.since >= self.cooldown:
            self.low, self.since = 0, 0
            return lower(current)
        return None
