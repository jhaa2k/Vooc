"""Templates: reusable visualizer looks (.viz files = JSON).

A template stores every layer of the ProjectModel (order, visibility and all
property values) but not the playlist, so one look can be reused for any
track. File format::

    {"format": "vooc-viz", "version": 1, "name": "...", "layers": [LayerModel.to_dict(), ...]}

Built-in templates are generated from the default project with overrides, so
they always match the current schema.
"""
import json
import os
import re
import uuid
from pathlib import Path

from .model import DEFAULT_LAYER_ORDER, LayerModel, ProjectModel

FORMAT = "vooc-viz"
VERSION = 1
KEEP_ON_APPLY = ("AUDIOPROVIDER",)       # device audio routing is not part of a look


def _fx_on(layers, module, **values):
    layer = layers[module]
    layer.set("General/Layer/Visible", True)
    for k, v in values.items():
        layer.set(k, v)


def _builtin(name):
    p = ProjectModel()
    L = {l.module: l for l in p.layers}
    if name == "Circle Classic":
        pass
    elif name == "Linear Spectrum":
        b = L["BARS"]
        b.set("Bars Setup/Bars/Shape Path", "HorizontalLine")
        b.set("Bars Setup/Bars/Path Size", 0.24)
        b.set("Bars Setup/Bars/Bar Count", 96)
        b.set("Bars Setup/Bars/Max Height Scale", 0.42)
        b.set("General/Position/Position Y", 0.30)
        b.set("Segment 1/Segment 1/Mirror", True)
        L["IMAGE"].set("General/Layer/Visible", False)
        L["TEXT"].set("General/Position/Position Y", 0.80)
        L["PARTICLES"].set("Overall Settings/Emitter/Spawn Area", "Rectangle")
        _fx_on(L, "BLUREFFECT")
    elif name == "Mirror Wave":
        b = L["BARS"]
        b.set("Bars Setup/Bars/Shape Path", "WaveformPath")
        b.set("Bars Setup/Bars/Path Size", 0.24)
        b.set("Bars Setup/Bars/Bar Count", 128)
        b.set("Bars Setup/Bars/Bar Width", 0.5)
        L["IMAGE"].set("General/Layer/Visible", False)
        _fx_on(L, "MIRROREREFFECT", **{"Appearance/Mirror/Mirror Mode": "Vertical"})
        _fx_on(L, "BLUREFFECT")
    elif name == "Neon Kaleido":
        b = L["BARS"]
        b.set("Bars Setup/Bars/Shape Path", "Star")
        b.set("Bars Setup/Bars/Bar Count", 120)
        L["COMPOSITION"].set("General/Measure Engine/Measure Rot", "TotalTimeWhenPlaying")
        L["COMPOSITION"].set("General/Measure Engine/Rot Amount (A)", 0.05)
        _fx_on(L, "BLUREFFECT")
        _fx_on(L, "RGBSPLITEFFECT")
        _shader(L["CUSTOMSHADER"], "Kaleidoscope")
    elif name == "Zoom Pulse":
        b = L["BARS"]
        b.set("Bars Setup/Bars/Bar Count", 90)
        b.set("Bars Setup/Bars/Max Height Scale", 0.3)
        _fx_on(L, "BLUREFFECT")
        _shader(L["CUSTOMSHADER"], "Zoom Blur")
    elif name == "Retro 3D Box":
        b = L["BARS"]
        b.set("Bars Setup/Bars/Shape Path", "Square")
        b.set("Bars Setup/Bars/Bar Count", 96)
        L["IMAGE"].set("Image Source/Source/Shape", "Square")
        _shader(L["CUSTOMSHADER"], "3D Box")
    elif name == "Twirl Galaxy":
        L["PARTICLES"].set("Overall Settings/Emitter/Count Limit", 900)
        L["PARTICLES"].set("Overall Settings/Emitter/Force Field", "Vortex")
        L["BARS"].set("Bars Setup/Bars/Shape Path", "SidedPolygon")
        _shader(L["CUSTOMSHADER"], "Twirl")
        _fx_on(L, "MOTIONBLUREFFECT")
    else:
        raise KeyError(name)
    return p


def _shader(layer, preset):
    from .engine.shader_presets import apply_preset
    apply_preset(layer, preset)
    layer.set("General/Layer/Visible", True)


BUILTINS = ["Circle Classic", "Linear Spectrum", "Mirror Wave", "Neon Kaleido", "Zoom Pulse",
            "Retro 3D Box", "Twirl Galaxy"]


# ---------------------------------------------------------------- serialize
def to_template(project, name):
    return {"format": FORMAT, "version": VERSION, "name": name,
            "layers": [l.to_dict() for l in project.layers]}


def validate(data):
    if not isinstance(data, dict) or not isinstance(data.get("layers"), list):
        raise ValueError("Not a Vooc template (missing layers)")
    if data.get("format") not in (FORMAT, None):
        raise ValueError("Unknown template format %r" % data.get("format"))
    layers = []
    for d in data["layers"]:
        try:
            layers.append(LayerModel.from_dict(d))
        except Exception:
            continue          # unknown module from a newer version: skip it
    if not layers:
        raise ValueError("Template has no usable layers")
    return layers


def apply_template(project, data):
    """Replace the project's look with a template. Returns the template name."""
    layers = validate(data)
    keep = {l.module: l for l in project.layers if l.module in KEEP_ON_APPLY}
    fresh = []
    for l in layers:
        if l.module in keep:
            fresh.append(keep.pop(l.module))
        else:
            l.id = uuid.uuid4().hex[:10]          # new renderers / shader passes
            fresh.append(l)
    fresh.extend(keep.values())
    project.layers = fresh
    present = {l.module for l in fresh}
    for module in DEFAULT_LAYER_ORDER:
        if module not in present:
            project.layer(module)
    return data.get("name") or "Template"


def builtin(name):
    return to_template(_builtin(name), name)


# ---------------------------------------------------------------- storage
class TemplateStore:
    def __init__(self, directory):
        self.dir = Path(directory)

    def _file(self, name):
        safe = re.sub(r"[^A-Za-z0-9 _.-]+", "_", name).strip() or "Template"
        return self.dir / (safe + ".viz")

    def names(self):
        if not self.dir.exists():
            return []
        out = []
        for f in sorted(self.dir.glob("*.viz"), key=lambda x: x.stat().st_mtime):
            try:
                out.append(json.loads(f.read_text(encoding="utf-8")).get("name") or f.stem)
            except Exception:
                continue
        return out

    def path(self, name):
        for f in self.dir.glob("*.viz"):
            try:
                if (json.loads(f.read_text(encoding="utf-8")).get("name") or f.stem) == name:
                    return f
            except Exception:
                continue
        return None

    def load(self, name):
        if name in BUILTINS:
            return builtin(name)
        f = self.path(name)
        if f is None:
            raise KeyError(name)
        return json.loads(f.read_text(encoding="utf-8"))

    def save(self, project, name):
        self.dir.mkdir(parents=True, exist_ok=True)
        f = self._file(name)
        tmp = f.with_suffix(".tmp")
        tmp.write_text(json.dumps(to_template(project, name), indent=1), encoding="utf-8")
        os.replace(tmp, f)
        return f

    def delete(self, name):
        f = self.path(name)
        if f is not None:
            f.unlink()

    def import_file(self, path):
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        validate(data)
        name = data.get("name") or Path(path).stem
        base, i = name, 2
        while name in BUILTINS or self.path(name) is not None:
            name = "%s %d" % (base, i)
            i += 1
        data["name"] = name
        self.dir.mkdir(parents=True, exist_ok=True)
        self._file(name).write_text(json.dumps(data, indent=1), encoding="utf-8")
        return name
