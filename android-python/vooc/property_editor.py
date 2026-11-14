"""Hierarchical property editor for the render engine (Material 3 styled).

Main menu: two-column grid of the 11 modules (COMPOSITION ... CUSTOMSHADER).
Details view: Back / Title / Reset / Info + collapsible accordion
(Category -> Sub-category -> Property). Every control writes straight into the
``LayerModel`` the SceneView renders, so the preview changes on every
movement. Dropdowns always expose the complete option list from the schema.

Custom shaders: Effect Preset loads a ready-made GLSL effect, Compile / Apply
compiles on the GPU (errors are shown inline, the last good shader keeps
running), Add Variable declares a uniform that immediately gets its own
slider / color picker.
"""
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.widget import Widget

from . import m3
from .m3 import T
from .property_schema import EFFECT_MODULES, MODULE_INFO, MODULES, SCHEMA, defaults

from .fx_library import LIBRARY  # noqa: E402

_ICONS = {"COMPOSITION": "studio", "IMAGE": "image", "TEXT": "text", "BARS": "bars", "PARTICLES": "sparkle",
          "AUDIOPROVIDER": "tune", "BLUREFFECT": "eye", "RGBSPLITEFFECT": "layers", "MOTIONBLUREFFECT": "next",
          "MIRROREREFFECT": "copy", "CUSTOMSHADER": "more", "WAVEFORM": "tune", "SHAPE": "studio", "GROUP": "layers"}
for _name, _fx in LIBRARY.items():
    _ICONS[_name] = _fx["icon"]
FONTS = ["Roboto", "RobotoMono", "DejaVuSans"]
VARS = "Variables/Variable Mappings/Mappings"
BANDS = "Frequency Bands/Custom Bands/Bands"


def _action_icon(path):
    if "Compile" in path:
        return "check"
    if "Re-analyse" in path:
        return "reset"
    if "Tap" in path:
        return "play"
    return "plus"


def pretty(name):
    special = {"AUDIOPROVIDER": "Audio Provider", "BLUREFFECT": "Blur Effect", "RGBSPLITEFFECT": "RGB Split",
               "MOTIONBLUREFFECT": "Motion Blur", "MIRROREREFFECT": "Mirrorer", "CUSTOMSHADER": "Custom Shader"}
    if name in LIBRARY:
        return LIBRARY[name]["title"]
    return special.get(name, name.title())


class AdvancedProperties(BoxLayout):
    """``project``: ProjectModel. ``scene``: callable returning the SceneView (for shader compiles).
    ``on_change(layer)`` after every edit, ``pick_image(callback)`` opens the image picker."""

    scroll_cls = None

    def __init__(self, project, scene=None, on_change=None, pick_image=None, palette=None, toast=None,
                 layer=None, on_back=None, on_action=None, pick_file=None, **kwargs):
        super().__init__(orientation="vertical", spacing=dp(8), **kwargs)
        self.project, self.scene = project, scene or (lambda: None)
        self.on_change = on_change or (lambda layer: None)
        self.pick_image = pick_image
        self.pick_file = pick_file
        self.palette = palette or (lambda: [])
        self.toast = toast or (lambda text: None)
        self.on_back = on_back
        self.on_action = on_action or (lambda layer, path: False)
        self.category = None
        self.first_tile = None
        if layer is not None:
            self.open_layer(layer)
        else:
            self._home()

    def _new_scroll(self):
        from kivy.uix.scrollview import ScrollView
        cls = self.scroll_cls or ScrollView
        return cls(do_scroll_x=False, bar_width=dp(3))

    # ------------------------------------------------------------ helpers
    def _changed(self, layer, path, value):
        if layer.set(path, value):
            self.on_change(layer)
            if path == "General/Layer/Locked":
                from kivy.clock import Clock
                Clock.schedule_once(lambda _dt: self.open_layer(layer, {"General"}), 0)

    @staticmethod
    def _count(module):
        return sum(len(f) for groups in SCHEMA[module].values() for f in groups.values())

    @staticmethod
    def _edited(layer):
        fresh = defaults(layer.module)
        return sum(1 for k, v in layer.values.items() if fresh.get(k) != v)

    # ------------------------------------------------------------ home
    def _home(self):
        self.clear_widgets()
        self.category = None
        head = BoxLayout(size_hint_y=None, height=dp(40), padding=(dp(4), 0))
        head.add_widget(m3.lbl("Modules", 16, bold=True))
        head.add_widget(m3.lbl("%d modules - tap to edit" % len(MODULES), 12, T.on_var, halign="right"))
        self.add_widget(head)
        scroll = self._new_scroll()
        page = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(6), padding=(0, 0, 0, dp(12)))
        page.bind(minimum_height=page.setter("height"))
        self.first_tile = None
        core = [m for m in MODULES if m in ("COMPOSITION", "AUDIOPROVIDER")]
        fx = [m for m in MODULES if m in EFFECT_MODULES]
        layers = [m for m in MODULES if m not in core and m not in fx]
        for group, names in (("Core", core), ("Layers", layers), ("Effects", fx)):
            page.add_widget(m3.section(group, "%d" % len(names)))
            grid = GridLayout(cols=2, spacing=dp(10), padding=(0, dp(2), 0, dp(6)), size_hint_y=None)
            grid.bind(minimum_height=grid.setter("height"))
            self._tiles(grid, names)
            page.add_widget(grid)
        scroll.add_widget(page)
        self.add_widget(scroll)

    def _tiles(self, grid, names):
        for name in names:
            layer = self.project.layer(name)
            on = bool(layer.values.get("General/Layer/Visible", True))
            tile = m3.Tap(on_tap=lambda l=layer: self.open_layer(l), orientation="vertical", bg=T.surface2,
                          radius=dp(18), padding=dp(12), spacing=dp(6), size_hint_y=None, height=dp(96))
            top = BoxLayout(size_hint_y=None, height=dp(34))
            ic = m3.Box(bg=T.primary_container if on else T.surface3, radius=dp(12), size_hint=(None, None),
                        size=(dp(34), dp(34)), padding=dp(8))
            ic.add_widget(m3.Icon(name=_ICONS.get(name, "tune"), tint=T.on_primary_container if on else T.on_var))
            top.add_widget(ic)
            top.add_widget(Widget())
            edited = self._edited(layer)
            tag = ("%d edited" % edited) if edited else ("" if on else "off")
            if tag:
                top.add_widget(m3.lbl(tag, 11, T.primary if edited else T.on_var, halign="right",
                                      size_hint_x=None, width=dp(70)))
            tile.add_widget(top)
            tile.add_widget(m3.lbl(pretty(name), 14, bold=True))
            tile.add_widget(m3.lbl("%d settings" % self._count(name), 11, T.on_var))
            grid.add_widget(tile)
            if self.first_tile is None:
                self.first_tile = tile

    def back(self):
        if self.on_back is not None:
            self.on_back()
        else:
            self._home()

    # ------------------------------------------------------------ details
    def open_layer(self, layer, open_sections=None):
        self.layer = layer
        self.category = layer.module
        module = layer.module
        self.clear_widgets()
        top = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(4))
        top.add_widget(m3.IconBtn("back", self.back))
        title = layer.values.get("General/Layer/Tag") or ""
        col_t = BoxLayout(orientation="vertical")
        col_t.add_widget(m3.lbl(pretty(module), 17, bold=True))
        if title:
            col_t.add_widget(m3.lbl(title, 11, T.on_var))
        top.add_widget(col_t)
        top.add_widget(m3.IconBtn("reset", lambda: self._reset(layer)))
        top.add_widget(m3.IconBtn("info", lambda: self._info(module)))
        self.add_widget(top)
        scroll = self._new_scroll()
        col = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(8), padding=(0, 0, 0, dp(16)))
        col.bind(minimum_height=col.setter("height"))
        self._open_sections = set(open_sections or [])
        first = not self._open_sections
        locked = bool(layer.values.get("General/Layer/Locked", False))
        if locked:
            banner = m3.card(padding=(dp(12), dp(8)), spacing=dp(4), bg=T.secondary_container, radius=dp(16))
            banner.add_widget(m3.lbl("Locked - only General is editable. Turn off General > Layer > Locked to edit.",
                                     12, T.on_secondary_container, size_hint_y=None, height=dp(34)))
            col.add_widget(banner)
        for section, groups in SCHEMA[module].items():
            if locked and section != "General":
                continue
            count = sum(len(f) for f in groups.values())
            sec_card = m3.card(padding=(dp(4), dp(4)), spacing=dp(2), bg=T.surface1, radius=dp(18))
            header = m3.Tap(size_hint_y=None, height=dp(48), radius=dp(16), padding=(dp(12), 0), spacing=dp(8))
            header.add_widget(m3.lbl(section, 14, bold=True))
            header.add_widget(m3.lbl(str(count), 12, T.on_var, halign="right", size_hint_x=None, width=dp(36)))
            arrow = m3.Icon(name="down", tint=T.on_var, size_hint_x=None, width=dp(18))
            header.add_widget(arrow)
            holder = BoxLayout(orientation="vertical", size_hint_y=None, height=0, spacing=dp(4), padding=(dp(8), 0))
            holder.bind(minimum_height=holder.setter("height"))

            def toggle(sec=section, grp=groups, box=holder, arr=arrow):
                if box.children:
                    box.clear_widgets()
                    box.height = 0
                    arr.name = "down"
                    self._open_sections.discard(sec)
                    return
                arr.name = "up"
                self._open_sections.add(sec)
                for group, fields in grp.items():
                    box.add_widget(m3.lbl(group, 12, T.primary, bold=True, size_hint_y=None, height=dp(28)))
                    for name, spec in fields.items():
                        try:
                            box.add_widget(self._control(layer, sec + "/" + group + "/" + name, name, spec))
                        except Exception as exc:
                            box.add_widget(m3.lbl(name + ": " + str(exc), 11, T.error, size_hint_y=None, height=dp(30)))
                box.add_widget(Widget(size_hint_y=None, height=dp(6)))

            header.bind(on_release=lambda *_, t=toggle: t())
            sec_card.add_widget(header)
            sec_card.add_widget(holder)
            col.add_widget(sec_card)
            if first or section in self._open_sections:
                self._open_sections.discard(section)
                toggle()
                first = False
        scroll.add_widget(col)
        self.add_widget(scroll)

    def _refresh(self):
        self.open_layer(self.layer, set(self._open_sections))

    # ------------------------------------------------------------ controls
    def _slider(self, layer, path, name, spec, value):
        lo, hi = float(spec.get("min", 0)), float(spec.get("max", 1))
        st = float(spec.get("step", 0) or 0)
        step = st if st >= 1 else 0.0
        span = hi - lo
        fmt = (lambda v: "%d" % round(v)) if step else (lambda v: "%.3f" % v if span <= 2 else "%.2f" % v)
        return m3.SliderRow(name, lo, hi, max(lo, min(hi, float(value or 0))),
                            lambda v, p=path: self._changed(layer, p, round(float(v), 4)), step=step, fmt=fmt,
                            bipolar=(lo < 0 < hi), default=spec.get("default", lo))

    def _control(self, layer, path, name, spec):
        value = layer.get(path)
        kind = spec["type"]
        if kind == "slider":
            return self._slider(layer, path, name, spec, value)
        if kind == "number":
            lo, hi = int(spec.get("min", 0)), int(spec.get("max", 99999))
            hi_ui = min(hi, 8192)
            return m3.SliderRow(name, lo, hi_ui, max(lo, min(hi_ui, int(value))),
                                lambda v, p=path: self._changed(layer, p, int(round(v))), step=1,
                                fmt=lambda v: "%d" % round(v), default=spec.get("default", lo))
        if kind == "checkbox":
            return m3.switch_row(name, bool(value), lambda v, p=path: self._changed(layer, p, bool(v)))
        if kind == "variables":
            return self._variables(layer, path, value)
        if kind == "bands":
            return self._bands(layer, path, value)
        row = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(4))
        row.bind(minimum_height=row.setter("height"))
        if kind != "action":
            row.add_widget(m3.lbl(name, 12, T.on_var, size_hint_y=None, height=dp(20)))
        if kind == "dropdown":
            options = [str(v) for v in spec.get("options", [])]
            row.add_widget(m3.SelectField(name, options, str(value),
                                          lambda v, p=path: self._dropdown(layer, p, v)))
        elif kind == "font":
            options = FONTS + ([value] if value and value not in FONTS else [])
            row.add_widget(m3.SelectField(name, options, str(value), lambda v, p=path: self._changed(layer, p, v)))
        elif kind == "action":
            row.add_widget(m3.Button3(str(spec.get("label", value)), lambda p=path: self._action(layer, p),
                                      kind="tonal", icon=_action_icon(path)))
            if "Compile" in path:
                err = (getattr(self.scene(), "shader_errors", {}) or {}).get(layer.id, "")
                status = ("Shader error: " + err[:400]) if err else "Shader compiled OK"
                row.add_widget(m3.lbl(status, 11, T.error if err else T.primary, shorten=False,
                                      size_hint_y=None, height=dp(60) if err else dp(20), valign="top"))
        elif kind == "color":
            row.add_widget(m3.ColorField(value, lambda v, p=path: self._changed(layer, p, v),
                                         default=spec.get("default"), swatches=self.palette, title=name))
        elif kind == "image":
            line = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
            line.add_widget(m3.lbl(value.split("/")[-1] if value else "No image", 13,
                                   T.on_surface if value else T.on_var))
            line.add_widget(m3.Button3("Choose", lambda p=path: self._choose(layer, p), kind="tonal",
                                       icon="image", size_hint_x=None, width=dp(110)))
            if value:
                line.add_widget(m3.IconBtn("close", lambda p=path: (self._changed(layer, p, ""), self._refresh()),
                                           size=dp(40)))
            row.add_widget(line)
        elif kind == "file":
            line = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
            line.add_widget(m3.lbl(value.split("/")[-1] if value else "None", 13, T.on_surface if value else T.on_var))
            if value:
                line.add_widget(m3.IconBtn("close", lambda p=path: (self._changed(layer, p, ""), self._refresh()),
                                           size=dp(40)))
            line.add_widget(m3.Button3("Choose", lambda p=path, pat=spec.get("patterns"): self._choose_file(layer, p, pat),
                                       kind="tonal", icon="folder", size_hint_x=None, width=dp(110)))
            row.add_widget(line)
        elif kind == "code":
            row.add_widget(m3.text_field(value or "", lambda v, p=path: layer.set(p, v), "GLSL",
                                         multiline=True, height=dp(240)))
            row.add_widget(m3.lbl("Edits run after Compile / Apply", 11, T.on_var, size_hint_y=None, height=dp(18)))
        else:
            row.add_widget(m3.text_field(value if value is not None else "",
                                         lambda v, p=path: self._changed(layer, p, v), name,
                                         multiline=bool(spec.get("multiline"))))
        return row

    def _dropdown(self, layer, path, value):
        self._changed(layer, path, value)
        if path == "Shader Code/GLSL/Effect Preset" and value != "Custom":
            from .engine.shader_presets import apply_preset
            if apply_preset(layer, value):
                if not layer.get("General/Layer/Visible"):
                    layer.set("General/Layer/Visible", True)
                self._compile(layer)
                self.on_change(layer)
                self._refresh()

    def _choose(self, layer, path):
        if self.pick_image is None:
            self.toast("Image picker not available")
            return

        def done(p):
            if p:
                self._changed(layer, path, p)
                self._refresh()
        self.pick_image(done)

    def _choose_file(self, layer, path, patterns):
        if self.pick_file is None:
            self.toast("File picker not available")
            return

        def done(p):
            if p:
                self._changed(layer, path, p)
                self._refresh()
        self.pick_file(done, patterns)

    def _compile(self, layer):
        scene = self.scene()
        if scene is None:
            return ""
        err = scene.compile_shader(layer)
        self.toast("Shader error - see details" if err else "Shader compiled")
        return err

    def _action(self, layer, path):
        if path.endswith("Compile / Apply"):
            self._compile(layer)
            self.on_change(layer)
            self._refresh()
        elif path.endswith("Add Variable"):
            name = str(layer.get("Variables/Declare/New Variable Name") or "").strip()
            kind = layer.get("Variables/Declare/New Variable")
            if not name or not (name[0].isalpha() or name[0] == "_") or not name.replace("_", "").isalnum():
                self.toast("Variable name must be a valid GLSL identifier")
                return
            if kind == "None":
                self.toast("Pick a variable type first")
                return
            mappings = [dict(v) for v in layer.get(VARS)]
            if any(v["name"] == name for v in mappings):
                self.toast("Variable %s already exists" % name)
                return
            default = {"Float": 0.5, "Vector2": [0.5, 0.5], "Vector3": [0.5, 0.5, 0.5],
                       "Color": "#FFFFFFFF"}[kind]
            mappings.append({"name": name, "type": kind, "value": default})
            layer.set(VARS, mappings)
            self._compile(layer)
            self.on_change(layer)
            self._refresh()
        elif path.endswith("Custom Bands/Add Band"):
            bands = [dict(b) for b in (layer.get(BANDS) or [])]
            if len(bands) >= 8:
                self.toast("Up to 8 custom bands (CustomBand1-8 measure modes)")
                return
            presets = [(20, 60, "Sub"), (60, 250, "Bass"), (250, 500, "Low Mid"), (500, 2000, "Mid"),
                       (2000, 4000, "High Mid"), (4000, 6000, "Presence"), (6000, 12000, "Brilliance"),
                       (12000, 16000, "Air")]
            lo, hi, label = presets[len(bands)]
            bands.append({"label": label, "lo": lo, "hi": hi, "gain": 1.0, "smoothing": 0.0,
                          "attack": 0.0, "release": 0.0, "agc": False})
            layer.set(BANDS, bands)
            self.on_change(layer)
            self.toast("Added %s (%d-%d Hz) = CustomBand%d" % (label, lo, hi, len(bands)))
            self._refresh()
        elif self.on_action(layer, path):
            self._refresh()

    # ------------------------------------------------------------ custom analyzer bands
    def _bands(self, layer, path, bands):
        box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(4))
        box.bind(minimum_height=box.setter("height"))
        if not bands:
            box.add_widget(m3.lbl("No custom bands. Tap Add Band; each one becomes a CustomBand measure mode.",
                                  12, T.on_var, shorten=False, size_hint_y=None, height=dp(36)))
        for index, band in enumerate(bands):
            head = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(6))
            head.add_widget(m3.text_field(str(band.get("label", "")), lambda v, i=index: self._set_band(layer, i, "label", v, False),
                                          "Band name"))
            head.add_widget(m3.lbl("CustomBand%d" % (index + 1), 11, T.primary, size_hint_x=None, width=dp(92)))
            head.add_widget(m3.IconBtn("delete", lambda i=index: self._delete_band(layer, i), size=dp(38)))
            box.add_widget(head)
            rows = [("Low (Hz)", "lo", 10, 20000, 1), ("High (Hz)", "hi", 20, 22000, 1), ("Gain", "gain", 0, 8, 0),
                    ("Smoothing", "smoothing", 0, 0.95, 0), ("Attack (ms)", "attack", 0, 1000, 1),
                    ("Release (ms)", "release", 0, 3000, 1)]
            for name, key, lo, hi, step in rows:
                val = float(band.get(key, 0) or 0)
                fmt = (lambda v: "%d" % round(v)) if step else (lambda v: "%.2f" % v)
                box.add_widget(m3.SliderRow(name, lo, hi, max(lo, min(hi, val)),
                                            lambda v, i=index, k=key, st=step: self._set_band(
                                                layer, i, k, int(round(v)) if st else round(float(v), 3)),
                                            step=step, fmt=fmt, default=val))
            box.add_widget(m3.switch_row("Auto gain (AGC)", bool(band.get("agc")),
                                         lambda v, i=index: self._set_band(layer, i, "agc", bool(v))))
        return box

    def _set_band(self, layer, index, key, value, live=True):
        bands = [dict(b) for b in (layer.get(BANDS) or [])]
        if 0 <= index < len(bands):
            bands[index][key] = value
            if key == "lo" and bands[index]["hi"] <= value:
                bands[index]["hi"] = value + 1
            layer.set(BANDS, bands)
            self.on_change(layer)

    def _delete_band(self, layer, index):
        bands = [dict(b) for b in (layer.get(BANDS) or [])]
        if 0 <= index < len(bands):
            bands.pop(index)
            layer.set(BANDS, bands)
            self.on_change(layer)
            self._refresh()

    # ------------------------------------------------------------ shader variables
    def _variables(self, layer, path, mappings):
        from .engine.shader_presets import RANGES
        box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(4))
        box.bind(minimum_height=box.setter("height"))
        if not mappings:
            box.add_widget(m3.lbl("No variables. Declare one above.", 12, T.on_var, size_hint_y=None, height=dp(24)))
        for index, var in enumerate(mappings):
            head = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(6))
            head.add_widget(m3.lbl("%s  (%s)" % (var["name"], var["type"]), 13, T.on_surface, bold=True))
            head.add_widget(m3.IconBtn("delete", lambda i=index: self._delete_var(layer, i), size=dp(38)))
            box.add_widget(head)

            def setv(v, i=index, comp=None):
                maps = [dict(x) for x in layer.get(VARS)]
                if comp is None:
                    maps[i]["value"] = v
                else:
                    cur = list(maps[i]["value"]) if isinstance(maps[i]["value"], (list, tuple)) else [0.0] * 3
                    cur[comp] = round(float(v), 4)
                    maps[i]["value"] = cur
                layer.set(VARS, maps)
                self.on_change(layer)
            lo, hi = RANGES.get(var["name"], (0.0, 1.0))
            if var["type"] == "Float":
                val = float(var.get("value", 0) or 0)
                hi = max(hi, val)
                box.add_widget(m3.SliderRow("Value", lo, hi, val, lambda v, s=setv: s(round(float(v), 4)),
                                            default=val))
            elif var["type"] in ("Vector2", "Vector3"):
                n = 2 if var["type"] == "Vector2" else 3
                vals = list(var.get("value") or [0.0] * n) + [0.0] * n
                for c in range(n):
                    box.add_widget(m3.SliderRow("xyz"[c].upper(), -1.0, 2.0, float(vals[c]),
                                                lambda v, s=setv, c=c: s(v, comp=c), default=float(vals[c])))
            elif var["type"] == "Color":
                box.add_widget(m3.ColorField(var.get("value", "#FFFFFFFF"), lambda v, s=setv: s(v),
                                             default="#FFFFFFFF", swatches=self.palette, title=var["name"]))
        return box

    def _delete_var(self, layer, index):
        maps = [dict(x) for x in layer.get(VARS)]
        if 0 <= index < len(maps):
            maps.pop(index)
            layer.set(VARS, maps)
            self._compile(layer)
            self.on_change(layer)
            self._refresh()

    # ------------------------------------------------------------ reset / info
    def _reset(self, layer):
        tag = layer.values.get("General/Layer/Tag")
        visible = layer.values.get("General/Layer/Visible")
        layer.reset()
        if layer.module in ("BLUREFFECT", "MOTIONBLUREFFECT", "MIRROREREFFECT", "RGBSPLITEFFECT", "CUSTOMSHADER"):
            layer.set("General/Layer/Visible", visible)      # reset values, keep the effect on/off as it was
        if tag and layer.module == "CUSTOMSHADER":
            from .engine.shader_presets import PRESETS, apply_preset
            if tag in PRESETS:
                apply_preset(layer, tag)
        if layer.module == "CUSTOMSHADER":
            self._compile(layer)
        self.on_change(layer)
        self._refresh()

    def _info(self, module):
        sheet = m3.Sheet(pretty(module))
        sheet.height = dp(300)
        sheet.box.add_widget(m3.lbl(MODULE_INFO.get(module, "") + "\n\n%d settings. Changes apply live. Double tap a "
                                    "slider to reset it, tap its value to type an exact number." % self._count(module),
                                    13, T.on_var, shorten=False, valign="top"))
        sheet.open()
