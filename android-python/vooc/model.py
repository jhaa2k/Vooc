"""Data model + JSON persistence.

ProjectModel
  └─ layers: [LayerModel, ...]   rendered bottom -> top (index 0 is at the back)
       └─ values: {"Category/Sub/Property": value}

Every property of every layer is stored, so closing and reopening the app
restores the visualizer exactly. Unknown/missing keys are migrated against the
schema on load, so adding properties later never breaks an old save file.
"""
import json
import os
import tempfile
import uuid
from copy import deepcopy

from .property_schema import MODULES, defaults, field_spec

SAVE_VERSION = 2

# Creation order == z-order: the first layer is at the back.
# Effects process everything beneath them, so TEXT sits on top and stays sharp.
DEFAULT_LAYER_ORDER = ["COMPOSITION", "AUDIOPROVIDER", "IMAGE", "PARTICLES",
                       "BARS", "BLUREFFECT", "MOTIONBLUREFFECT", "MIRROREREFFECT",
                       "RGBSPLITEFFECT", "CUSTOMSHADER", "TEXT"]


def _coerce(spec, value):
    """Clamp/convert a stored value so the renderer can trust it."""
    kind = spec["type"]
    try:
        if kind == "slider":
            v = float(value)
            return max(spec["min"], min(spec["max"], v))
        if kind == "number":
            v = int(float(value))
            return max(int(spec["min"]), min(int(spec["max"]), v))
        if kind == "checkbox":
            return bool(value)
        if kind == "dropdown":
            value = str(value)
            return value if value in spec["options"] else spec["default"]
        if kind == "color":
            return normalize_hex(value, spec["default"])
        if kind == "bands":
            out = []
            for b in value or []:
                if not isinstance(b, dict):
                    continue
                lo = max(10.0, min(20000.0, float(b.get("lo", 100))))
                hi = max(lo + 1.0, min(22000.0, float(b.get("hi", 1000))))
                out.append({"label": str(b.get("label", "Band %d" % (len(out) + 1)))[:24],
                            "lo": lo, "hi": hi,
                            "gain": max(0.0, min(8.0, float(b.get("gain", 1.0)))),
                            "smoothing": max(0.0, min(0.95, float(b.get("smoothing", 0.0)))),
                            "attack": max(0.0, min(1000.0, float(b.get("attack", 0.0)))),
                            "release": max(0.0, min(3000.0, float(b.get("release", 0.0)))),
                            "agc": bool(b.get("agc", False))})
            return out[:32]
        if kind == "variables":
            return [dict(v) for v in value if isinstance(v, dict) and v.get("name")]
        if kind in ("text", "image", "font", "code", "action"):
            return "" if value is None else str(value)
    except (TypeError, ValueError):
        pass
    return deepcopy(spec["default"])


def normalize_hex(value, fallback="#FFFFFFFF"):
    """Return an uppercase #RRGGBBAA string (accepts #RGB, #RRGGBB, #RRGGBBAA)."""
    if not isinstance(value, str):
        return fallback
    h = value.strip().lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h) + "FF"
    elif len(h) == 6:
        h += "FF"
    if len(h) != 8:
        return fallback
    try:
        int(h, 16)
    except ValueError:
        return fallback
    return "#" + h.upper()


def hex_to_rgba(value):
    h = normalize_hex(value).lstrip("#")
    return [int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4, 6)]


def rgba_to_hex(rgba):
    return "#" + "".join("%02X" % int(round(max(0, min(1, c)) * 255)) for c in rgba)


class LayerModel:
    def __init__(self, module, values=None, layer_id=None, children=None):
        if module not in MODULES:
            raise ValueError("Unknown module " + module)
        self.module = module
        self.id = layer_id or uuid.uuid4().hex[:10]
        self.values = defaults(module)
        # Sub-compositions may own child layers (grouped transform).
        self.children = children or []
        if values:
            for path, value in values.items():
                if path in self.values:
                    self.values[path] = _coerce(field_spec(module, path), value)

    def get(self, path):
        return self.values[path]

    def set(self, path, value):
        value = _coerce(field_spec(self.module, path), value)
        changed = self.values.get(path) != value
        self.values[path] = value
        return changed

    def reset(self, category=None):
        fresh = defaults(self.module)
        for path in list(self.values):
            if category is None or path.startswith(category + "/"):
                self.values[path] = fresh[path]

    def to_dict(self):
        return {"id": self.id, "module": self.module, "values": deepcopy(self.values),
                "children": [c.to_dict() for c in self.children]}

    @classmethod
    def from_dict(cls, data):
        return cls(data["module"], data.get("values"), data.get("id"),
                   [cls.from_dict(c) for c in data.get("children", [])
                    if c.get("module") in MODULES])


class ProjectModel:
    def __init__(self):
        self.layers = [LayerModel(m) for m in DEFAULT_LAYER_ORDER]
        self.playlist = []          # list of {"uri", "title", "artist"}
        self.track_index = -1
        self.ui = {"dark": True}

    # ---- layer access
    def layer(self, module):
        """Primary layer of a module (created on demand, appended on top)."""
        for layer in self.layers:
            if layer.module == module:
                return layer
        layer = LayerModel(module)
        self.layers.append(layer)
        return layer

    def visual_layers(self):
        return [l for l in self.layers if l.module not in ("COMPOSITION", "AUDIOPROVIDER")]

    # ---- serialization
    def to_dict(self):
        return {"version": SAVE_VERSION, "layers": [l.to_dict() for l in self.layers],
                "playlist": self.playlist, "track_index": self.track_index, "ui": self.ui}

    @classmethod
    def from_dict(cls, data):
        project = cls()
        layers = [LayerModel.from_dict(d) for d in data.get("layers", [])
                  if d.get("module") in MODULES]
        if layers:
            project.layers = layers
            # make sure every module exists at least once
            for module in DEFAULT_LAYER_ORDER:
                project.layer(module)
        project.playlist = [p for p in data.get("playlist", []) if isinstance(p, dict) and p.get("uri")]
        project.track_index = int(data.get("track_index", -1))
        project.ui.update(data.get("ui", {}))
        return project


class ProjectStore:
    """Atomic JSON save/load in the app's private data directory."""

    def __init__(self, directory, filename="project.json"):
        self.directory = directory
        self.path = os.path.join(directory, filename)

    def load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as fh:
                return ProjectModel.from_dict(json.load(fh))
        except (OSError, ValueError, KeyError, TypeError):
            return ProjectModel()

    def save(self, project):
        os.makedirs(self.directory, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self.directory, suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(project.to_dict(), fh, indent=1)
            os.replace(tmp, self.path)
        except OSError:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise
