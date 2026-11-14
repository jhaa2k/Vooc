"""Templates: reusable visualizer looks (.viz files = JSON, .vzb = bundle with assets).

A template stores every layer of the ProjectModel (order, visibility and all
property values) but not the playlist, so one look can be reused for any
track. File format::

    {"format": "vooc-viz", "version": 2, "name": "...", "meta": {...}, "thumbnail": "<base64 png>",
     "layers": [LayerModel.to_dict(), ...]}

Version 1 files (no meta / thumbnail) are migrated on load. A ``.vzb`` bundle is
``b"VZB1" + zlib(json({"template": ..., "assets": {key: base64}}))``: every image /
font / file referenced by a layer is embedded so the look works on another phone.

Built-in templates are generated from the default project with overrides, so
they always match the current schema.
"""
import base64
import hashlib
import json
import os
import re
import struct
import time
import uuid
import zlib
from pathlib import Path

from .model import DEFAULT_LAYER_ORDER, LayerModel, ProjectModel

FORMAT = "vooc-viz"
VERSION = 2
APP_VERSION = "1.0"
BUNDLE_MAGIC = b"VZB1"
MAX_ASSET_BYTES = 12 * 1024 * 1024        # per asset, keeps bundles shareable
ASSET_TYPES = ("image", "font", "file")
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
def to_template(project, name, meta=None, thumbnail=None):
    now = int(time.time())
    m = {"author": "", "description": "", "tags": [], "created": now, "modified": now, "app_version": APP_VERSION}
    m.update(meta or {})
    m["modified"] = now
    try:
        comp = project.layer("COMPOSITION")
        m["size"] = [int(comp.get("Render Settings/Output/Resolution Width")),
                     int(comp.get("Render Settings/Output/Resolution Height"))]
    except Exception:
        pass
    m["layer_count"] = len(project.layers)
    data = {"format": FORMAT, "version": VERSION, "name": name, "meta": m,
            "layers": [l.to_dict() for l in project.layers]}
    if thumbnail:
        data["thumbnail"] = thumbnail
    return data


def migrate(data):
    """Upgrade older template dicts in place to the current version."""
    if not isinstance(data, dict):
        raise ValueError("Not a Vooc template")
    v = int(data.get("version") or 1)
    if v > VERSION:
        data.setdefault("warnings", []).append("made with a newer Vooc (v%d); unknown parts are skipped" % v)
    if v < 2:
        meta = data.get("meta") if isinstance(data.get("meta"), dict) else {}
        meta.setdefault("author", "")
        meta.setdefault("description", "")
        meta.setdefault("tags", [])
        meta.setdefault("created", 0)
        meta.setdefault("modified", 0)
        meta.setdefault("app_version", "")
        data["meta"] = meta
        data["version"] = VERSION
    data["format"] = FORMAT
    if not isinstance(data.get("meta", {}).get("tags", []), list):
        data["meta"]["tags"] = [str(data["meta"]["tags"])]
    return data


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


def check(data):
    """Validation report for import: (ok, problems[]) without raising."""
    problems = []
    try:
        migrate(data)
        layers = validate(data)
    except Exception as exc:
        return False, [str(exc)]
    skipped = len(data["layers"]) - len(layers)
    if skipped:
        problems.append("%d layer(s) from a newer version were skipped" % skipped)
    for p in asset_paths(data):
        if not os.path.isfile(p):
            problems.append("missing file: %s" % os.path.basename(p))
    return True, problems + list(data.get("warnings", []))


# ---------------------------------------------------------------- assets / bundles
def _asset_fields(module):
    from .property_schema import iter_fields
    return [path for path, spec in iter_fields(module) if spec.get("type") in ASSET_TYPES]


def asset_paths(data):
    out = []
    for d in data.get("layers", []):
        module, values = d.get("module"), d.get("values") or {}
        try:
            fields = _asset_fields(module)
        except Exception:
            continue
        for path in fields:
            v = values.get(path)
            if isinstance(v, str) and v and ("/" in v or os.sep in v) and v not in out:
                out.append(v)
    return out


def pack_bundle(data):
    """Template dict -> .vzb bytes with every referenced local file embedded."""
    assets, mapping = {}, {}
    for p in asset_paths(data):
        try:
            if not os.path.isfile(p) or os.path.getsize(p) > MAX_ASSET_BYTES:
                continue
            raw = Path(p).read_bytes()
        except OSError:
            continue
        key = hashlib.sha1(raw).hexdigest()[:12] + "_" + os.path.basename(p)
        assets[key] = base64.b64encode(raw).decode("ascii")
        mapping[p] = "asset:" + key
    tpl = json.loads(json.dumps(data))
    for d in tpl.get("layers", []):
        vals = d.get("values") or {}
        for k, v in list(vals.items()):
            if isinstance(v, str) and v in mapping:
                vals[k] = mapping[v]
    payload = json.dumps({"template": tpl, "assets": assets}, separators=(",", ":")).encode("utf-8")
    return BUNDLE_MAGIC + zlib.compress(payload, 9)


def unpack_bundle(raw, asset_dir):
    """.vzb bytes -> template dict; assets are written to ``asset_dir`` and paths rewritten."""
    if not raw.startswith(BUNDLE_MAGIC):
        raise ValueError("Not a Vooc bundle")
    try:
        doc = json.loads(zlib.decompress(raw[len(BUNDLE_MAGIC):]).decode("utf-8"))
    except Exception:
        raise ValueError("Bundle is damaged")
    tpl, assets = doc.get("template"), doc.get("assets") or {}
    if not isinstance(tpl, dict):
        raise ValueError("Bundle has no template")
    asset_dir = Path(asset_dir)
    paths = {}
    for key, b64 in assets.items():
        safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", key)[:120]
        asset_dir.mkdir(parents=True, exist_ok=True)
        f = asset_dir / safe
        if not f.exists():
            f.write_bytes(base64.b64decode(b64))
        paths["asset:" + key] = str(f)
    for d in tpl.get("layers", []):
        vals = d.get("values") or {}
        for k, v in list(vals.items()):
            if isinstance(v, str) and v.startswith("asset:"):
                vals[k] = paths.get(v, "")
    return tpl


def read_any(path, asset_dir):
    raw = Path(path).read_bytes()
    if raw.startswith(BUNDLE_MAGIC):
        return unpack_bundle(raw, asset_dir)
    return json.loads(raw.decode("utf-8"))


# ---------------------------------------------------------------- thumbnails
def png_bytes(rgba, w, h):
    """Minimal PNG encoder (RGBA8, top-down rows)."""
    def chunk(tag, body):
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
    stride = w * 4
    rows = b"".join(b"\x00" + bytes(rgba[y * stride:(y + 1) * stride]) for y in range(h))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def make_thumbnail(pixels, w, h, max_w=192, bottom_up=True):
    """Base64 PNG thumbnail from raw RGBA frame bytes."""
    import numpy as np
    arr = np.frombuffer(bytes(pixels), np.uint8).reshape(h, w, 4)
    if bottom_up:
        arr = arr[::-1]
    step = max(1, int(np.ceil(w / float(max_w))))
    small = np.ascontiguousarray(arr[::step, ::step])
    small[..., 3] = 255
    th, tw = small.shape[:2]
    return base64.b64encode(png_bytes(small.tobytes(), tw, th)).decode("ascii")


def apply_template(project, data):
    """Replace the project's look with a template. Returns the template name."""
    layers = validate(migrate(data))
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

    def _read(self, f):
        return migrate(json.loads(f.read_text(encoding="utf-8")))

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
        return self._read(f)

    def info(self, name):
        """Metadata + thumbnail without layers (cheap for lists)."""
        try:
            d = self.load(name)
        except Exception:
            return {"name": name, "meta": {}, "thumbnail": None, "builtin": name in BUILTINS}
        return {"name": name, "meta": d.get("meta", {}), "thumbnail": d.get("thumbnail"),
                "builtin": name in BUILTINS}

    def _write(self, data):
        self.dir.mkdir(parents=True, exist_ok=True)
        f = self._file(data["name"])
        tmp = f.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=1), encoding="utf-8")
        os.replace(tmp, f)
        return f

    def save(self, project, name, meta=None, thumbnail=None):
        old = None
        try:
            old = self.load(name) if name not in BUILTINS and self.path(name) else None
        except Exception:
            pass
        m = dict((old or {}).get("meta", {}))
        m.update(meta or {})
        if old and old.get("meta", {}).get("created"):
            m["created"] = old["meta"]["created"]
        return self._write(to_template(project, name, m, thumbnail or (old or {}).get("thumbnail")))

    def unique(self, name):
        base, i, n = name, 2, name
        while n in BUILTINS or self.path(n) is not None:
            n = "%s %d" % (base, i)
            i += 1
        return n

    def rename(self, old, new):
        if old in BUILTINS:
            raise ValueError("Built-in templates cannot be renamed")
        data = self.load(old)
        new = self.unique(new.strip() or old)
        f = self.path(old)
        data["name"] = new
        self._write(data)
        if f is not None and f != self._file(new):
            f.unlink()
        favs = self.favorites()
        if old in favs:
            favs[favs.index(old)] = new
            self._save_favs(favs)
        return new

    def duplicate(self, name):
        data = json.loads(json.dumps(self.load(name)))
        data["name"] = self.unique(name + " copy")
        data.setdefault("meta", {})["created"] = int(time.time())
        self._write(data)
        return data["name"]

    def delete(self, name):
        f = self.path(name)
        if f is not None:
            f.unlink()
        favs = self.favorites()
        if name in favs:
            favs.remove(name)
            self._save_favs(favs)

    # favorites
    def favorites(self):
        try:
            return list(json.loads((self.dir / "favorites.json").read_text(encoding="utf-8")))
        except Exception:
            return []

    def _save_favs(self, favs):
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / "favorites.json").write_text(json.dumps(favs), encoding="utf-8")

    def toggle_favorite(self, name):
        favs = self.favorites()
        if name in favs:
            favs.remove(name)
        else:
            favs.append(name)
        self._save_favs(favs)
        return name in favs

    # import / export
    def import_file(self, path):
        data = read_any(path, self.dir / "assets")
        ok, problems = check(data)
        if not ok:
            raise ValueError(problems[0])
        data["name"] = self.unique(data.get("name") or Path(path).stem)
        data.pop("warnings", None)
        self._write(data)
        self.last_problems = problems
        return data["name"]

    def export_bundle(self, name, out_dir):
        data = self.load(name)
        out = Path(out_dir) / (re.sub(r"[^A-Za-z0-9 _.-]+", "_", name).strip() + ".vzb")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(pack_bundle(data))
        return out
