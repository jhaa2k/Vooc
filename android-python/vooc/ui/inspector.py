"""Collapsible accordion inspector.

Binding contract (UI <-> ViewModel <-> Model):

  * Every row is created from the schema spec of one property path.
  * User edits call `vm.set(path, value)`; the view-model validates through the
    model, notifies observers and schedules an autosave.
  * The inspector subscribes to the view-model; when a value changes from
    elsewhere (reset, uniform-scale sync, variable add) only the affected rows
    refresh via `row.refresh(value)`.
  * Category bodies are built lazily the first time they are expanded, so
    opening a module with 50+ properties stays instant.
"""
import os

from kivy.animation import Animation
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout

from ..model import hex_to_rgba
from ..property_schema import VARIABLE_TYPES
from .theme import FS_BODY, FS_LABEL, FS_SMALL, GAP, MONO, PAD, T
from .widgets import (Icon, IconButton, Pressable, SliderBar, Surface, Swatch, ToggleSwitch, TextButton,
                      choose, color_dialog, label, text_dialog, text_field)

BASE_FONTS = ["Roboto", "RobotoMono", "DejaVuSans"]


def system_fonts():
    out = []
    for d in ("/system/fonts", "/usr/share/fonts/truetype"):
        if os.path.isdir(d):
            for root, _, files in os.walk(d):
                for f in sorted(files):
                    if f.lower().endswith((".ttf", ".otf")):
                        out.append(os.path.join(root, f))
                if len(out) > 120:
                    break
    return out


def _fmt(spec, v):
    if spec.get("step") == 1:
        return "%d" % round(v)
    return "%.3f" % v


class Row(BoxLayout):
    def __init__(self, vm, path, name, spec, ctx, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(52))
        kw.setdefault("spacing", GAP)
        super().__init__(**kw)
        self.vm, self.path, self.name, self.spec, self.ctx = vm, path, name, spec, ctx
        self.build()
        self.refresh(vm.get(path))

    def build(self):
        pass

    def refresh(self, value):
        pass

    def commit(self, value):
        self.vm.set(self.path, value)

    def title(self, **kw):
        return label(self.name, FS_BODY, **kw)


class SliderRow(Row):
    def __init__(self, *a, **kw):
        super().__init__(*a, orientation="vertical", height=dp(64), spacing=0, **kw)

    def build(self):
        s = self.spec
        top = BoxLayout(size_hint_y=None, height=dp(26))
        top.add_widget(self.title())
        self.value_lbl = label("", FS_LABEL, color=T.primary, halign="right", bold=True,
                               size_hint_x=None, width=dp(80), font_name=MONO)
        top.add_widget(self.value_lbl)
        self.add_widget(top)
        self.slider = SliderBar(min=s["min"], max=s["max"], step=s.get("step", 0.001) or 0)
        self.slider.on_change = self._changing
        self.add_widget(self.slider)
        self.warn = None
        if s.get("warn_above") is not None:
            self.warn = label("", FS_SMALL, color=T.warning, size_hint_y=None, height=0)
            self.add_widget(self.warn)

    def _changing(self, value):
        self.value_lbl.text = _fmt(self.spec, value)
        self.commit(value)

    def refresh(self, value):
        self.slider.value = value
        self.value_lbl.text = _fmt(self.spec, value)
        if self.warn is not None:
            over = value > self.spec["warn_above"]
            self.warn.text = self.spec.get("warning", "") if over else ""
            self.warn.height = dp(18) if over else 0
            self.height = dp(64) + self.warn.height


class CheckRow(Row):
    def build(self):
        self.add_widget(self.title())
        self.switch = ToggleSwitch(pos_hint={"center_y": .5})
        self.switch.on_change = self.commit
        self.add_widget(self.switch)

    def refresh(self, value):
        self.switch.active = bool(value)


class ColorRow(Row):
    def build(self):
        self.add_widget(self.title())
        self.hex_lbl = label("", FS_LABEL, color=T.muted, halign="right", size_hint_x=None,
                             width=dp(96), font_name=MONO)
        self.add_widget(self.hex_lbl)
        self.swatch = Swatch(pos_hint={"center_y": .5})
        self.swatch.bind(on_release=lambda *_: color_dialog(self.name, self.vm.get(self.path), self.commit))
        self.add_widget(self.swatch)

    def refresh(self, value):
        self.hex_lbl.text = value
        self.swatch.rgba = hex_to_rgba(value)


class ChoiceRow(Row):
    def options(self):
        return self.spec["options"]

    def build(self):
        self.add_widget(self.title(size_hint_x=.45))
        self.btn = Pressable(bg=T.surface_top, padding=(dp(12), 0), radius=dp(12),
                             size_hint_y=None, height=dp(40), pos_hint={"center_y": .5})
        self.val = label("", FS_LABEL, bold=True)
        self.btn.add_widget(self.val)
        self.btn.add_widget(Icon(name="chevron", tint=T.muted, size_hint_x=None, width=dp(18)))
        self.btn.bind(on_release=lambda *_: self.open())
        self.add_widget(self.btn)

    def open(self):
        choose(self.name, self.options(), self.vm.get(self.path), self.commit)

    def refresh(self, value):
        self.val.text = os.path.basename(str(value)) or "-"


class FontRow(ChoiceRow):
    PICK = "Pick a .ttf / .otf file..."

    def options(self):
        return BASE_FONTS + system_fonts() + [self.PICK]

    def commit(self, value):
        if value == self.PICK:
            self.ctx.pick_file("font", lambda path: path and self.vm.set(self.path, path))
        else:
            super().commit(value)


class TextRow(Row):
    def __init__(self, *a, **kw):
        spec = a[3]
        multi = spec.get("multiline")
        super().__init__(*a, orientation="vertical" if not multi else "horizontal",
                         height=dp(84) if not multi else dp(60), **kw)

    def build(self):
        if self.spec.get("multiline"):
            col = BoxLayout(orientation="vertical")
            col.add_widget(self.title())
            self.preview = label("", FS_SMALL, color=T.muted)
            col.add_widget(self.preview)
            self.add_widget(col)
            self.add_widget(IconButton("edit", self._edit, tint=T.primary, pos_hint={"center_y": .5}))
            self.field = None
        else:
            self.add_widget(self.title(size_hint_y=None, height=dp(24)))
            self.field = text_field("", size_hint_y=None, height=dp(44))
            self.field.bind(focus=lambda w, f: (not f) and self.commit(w.text))
            self.field.bind(on_text_validate=lambda w: self.commit(w.text))
            self.add_widget(self.field)

    def _edit(self):
        text_dialog(self.name, self.vm.get(self.path), self.commit,
                    help_text=self.spec.get("help", ""))

    def refresh(self, value):
        if self.field is not None:
            if not self.field.focus:
                self.field.text = value
        else:
            self.preview.text = value.replace("\n", "  /  ") or "(empty)"


class NumberRow(Row):
    def build(self):
        self.add_widget(self.title())
        self.add_widget(IconButton("minus", lambda: self.step(-1), size=(dp(38), dp(38)),
                                   pos_hint={"center_y": .5}))
        self.field = text_field("", size_hint=(None, None), size=(dp(84), dp(40)),
                                input_filter="int", pos_hint={"center_y": .5}, halign="center")
        self.field.bind(focus=lambda w, f: (not f) and self._typed(w.text),
                        on_text_validate=lambda w: self._typed(w.text))
        self.add_widget(self.field)
        self.add_widget(IconButton("plus", lambda: self.step(1), size=(dp(38), dp(38)),
                                   pos_hint={"center_y": .5}))

    def _typed(self, text):
        try:
            self.commit(int(text or 0))
        except ValueError:
            pass
        self.refresh(self.vm.get(self.path))

    def step(self, d):
        self.commit(int(self.vm.get(self.path)) + d)

    def refresh(self, value):
        if not self.field.focus:
            self.field.text = str(value)


class ImageRow(Row):
    def build(self):
        col = BoxLayout(orientation="vertical")
        col.add_widget(self.title())
        self.file_lbl = label("", FS_SMALL, color=T.muted)
        col.add_widget(self.file_lbl)
        self.add_widget(col)
        self.add_widget(TextButton("Pick", self._pick, size_hint=(None, None), size=(dp(70), dp(38)),
                                   pos_hint={"center_y": .5}))
        self.add_widget(IconButton("close", lambda: self.commit(""), tint=T.muted,
                                   size=(dp(38), dp(38)), pos_hint={"center_y": .5}))

    def _pick(self):
        self.ctx.pick_file("image", lambda path: path and self.commit(path))

    def refresh(self, value):
        self.file_lbl.text = os.path.basename(value) if value else "None"


class CodeRow(Row):
    def build(self):
        col = BoxLayout(orientation="vertical")
        col.add_widget(self.title())
        self.info_lbl = label("", FS_SMALL, color=T.muted)
        col.add_widget(self.info_lbl)
        self.add_widget(col)
        self.add_widget(TextButton("Edit", self._edit, size_hint=(None, None), size=(dp(70), dp(38)),
                                   pos_hint={"center_y": .5}))

    def _edit(self):
        help_text = self.spec.get("help") or ("Write `vec4 effect(vec4 color, sampler2D texture, vec2 "
                                              "tex_coords, vec2 coords)`. Then tap Compile / Apply.")
        text_dialog(self.name, self.vm.get(self.path), self.commit, mono=True, help_text=help_text)

    def refresh(self, value):
        lines = value.count("\n") + 1 if value.strip() else 0
        self.info_lbl.text = "%d lines of GLSL" % lines if lines else "Default"


class ActionRow(Row):
    def build(self):
        self.add_widget(TextButton(self.spec["label"], lambda: self.ctx.run_action(self.vm, self.path),
                                   primary=True, size_hint_y=None, height=dp(44),
                                   pos_hint={"center_y": .5}))


class VariablesRow(Row):
    """Dynamic sliders / pickers for every declared custom-shader uniform."""

    def __init__(self, *a, **kw):
        super().__init__(*a, orientation="vertical", height=dp(10), spacing=dp(6), **kw)

    def refresh(self, value):
        self.clear_widgets()
        h = 0
        if not value:
            self.add_widget(label("No variables yet. Declare one above.", FS_SMALL, color=T.muted,
                                  size_hint_y=None, height=dp(30)))
            h = dp(30)
        for var in value:
            card = self._card(var)
            self.add_widget(card)
            h += card.height + dp(6)
        self.height = h

    def _card(self, var):
        name, kind = var["name"], var["type"]
        n_sliders = {"Float": 1, "Vector2": 2, "Vector3": 3}.get(kind, 0)
        height = dp(44) + n_sliders * dp(40) + (dp(44) if kind == "Color" else 0) + dp(12)
        card = Surface(orientation="vertical", bg=T.surface_top, radius=dp(14), padding=(dp(12), dp(6)),
                       size_hint_y=None, height=height)
        head = BoxLayout(size_hint_y=None, height=dp(40))
        head.add_widget(label("uniform %s %s" % ({"Float": "float", "Vector2": "vec2", "Vector3": "vec3",
                                                 "Color": "vec4"}[kind], name),
                              FS_LABEL, bold=True, color=T.primary, font_name=MONO))
        head.add_widget(IconButton("close", lambda: self.vm.remove_variable(name), tint=T.muted,
                                   size=(dp(36), dp(36)), pos_hint={"center_y": .5}))
        card.add_widget(head)
        value = var["value"]
        if kind == "Float":
            card.add_widget(self._slider(float(value), lambda v: self.vm.update_variable(name, v)))
        elif kind in ("Vector2", "Vector3"):
            vals = list(value)
            for i, axis in enumerate("xyz"[:n_sliders]):
                def cb(v, i=i):
                    cur = list(self._value_of(name))
                    cur[i] = v
                    self.vm.update_variable(name, cur)
                card.add_widget(self._slider(float(vals[i]), cb, axis))
        elif kind == "Color":
            row = BoxLayout(size_hint_y=None, height=dp(40), spacing=GAP)
            row.add_widget(label(str(value), FS_LABEL, color=T.muted, font_name=MONO))
            sw = Swatch(rgba=hex_to_rgba(value), pos_hint={"center_y": .5})
            sw.bind(on_release=lambda *_: color_dialog(name, self._value_of(name),
                                                       lambda h: self.vm.update_variable(name, h)))
            row.add_widget(sw)
            card.add_widget(row)
        return card

    def _value_of(self, name):
        for v in self.vm.variables():
            if v["name"] == name:
                return v["value"]
        return None

    def _slider(self, value, cb, axis=None):
        row = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(6))
        if axis:
            row.add_widget(label(axis, FS_LABEL, color=T.muted, size_hint_x=None, width=dp(16)))
        val = label("%.3f" % value, FS_LABEL, color=T.primary, size_hint_x=None, width=dp(56),
                    font_name=MONO, halign="right")
        s = SliderBar(min=0, max=1, value=value)

        def changing(v):
            val.text = "%.3f" % v
            self._quiet = True
            cb(v)
            self._quiet = False
        s.on_change = changing
        row.add_widget(s)
        row.add_widget(val)
        return row


ROWS = {"slider": SliderRow, "checkbox": CheckRow, "color": ColorRow, "dropdown": ChoiceRow,
        "text": TextRow, "number": NumberRow, "image": ImageRow, "font": FontRow,
        "code": CodeRow, "action": ActionRow, "variables": VariablesRow}


class AccordionSection(BoxLayout):
    """One Category. Header toggles; body (sub-category headers + rows) is lazy."""

    def __init__(self, inspector, category, subs, expanded=False, **kw):
        super().__init__(orientation="vertical", size_hint_y=None, **kw)
        self.inspector, self.category, self.subs = inspector, category, subs
        self.header = Pressable(bg=T.surface_hi, size_hint_y=None, height=dp(56),
                                padding=(dp(16), 0), radius=dp(16), spacing=GAP)
        self.header.add_widget(Icon(name="dot", tint=T.primary, size_hint_x=None, width=dp(14)))
        count = sum(len(rows) for _, rows in subs)
        self.header.add_widget(label(category, FS_BODY, bold=True))
        self.header.add_widget(label("%d" % count, FS_SMALL, color=T.muted, halign="right",
                                     size_hint_x=None, width=dp(30)))
        self.chev = Icon(name="chevron", tint=T.on_surface, size_hint_x=None, width=dp(22))
        self.header.add_widget(self.chev)
        self.header.bind(on_release=lambda *_: self.toggle())
        self.add_widget(self.header)
        self.body = None
        self.expanded = False
        self.bind(minimum_height=self.setter("height"))
        if expanded:
            self.toggle()

    def _build_body(self):
        body = Surface(orientation="vertical", bg=T.surface, size_hint_y=None,
                       padding=(dp(16), dp(6), dp(16), dp(12)), spacing=dp(4), radius=dp(16))
        body.bind(minimum_height=body.setter("height"))
        for sub, rows in self.subs:
            show_sub = len(self.subs) > 1 or sub != self.category
            if show_sub:
                body.add_widget(label(sub.upper(), FS_SMALL, color=T.primary, bold=True,
                                      size_hint_y=None, height=dp(30)))
            for path, name, spec in rows:
                row_cls = ROWS.get(spec["type"])
                if row_cls is None:
                    continue
                row = row_cls(self.inspector.vm, path, name, spec, self.inspector.ctx)
                self.inspector.rows[path] = row
                body.add_widget(row)
        return body

    def toggle(self):
        self.expanded = not self.expanded
        self.chev.name = "chevron_up" if self.expanded else "chevron"
        if self.expanded:
            if self.body is None:
                self.body = self._build_body()
            self.body.opacity = 0
            self.add_widget(self.body)
            Animation(opacity=1, d=.18).start(self.body)
        elif self.body is not None:
            self.remove_widget(self.body)


class Inspector(BoxLayout):
    def __init__(self, vm, ctx, **kw):
        super().__init__(orientation="vertical", size_hint_y=None, spacing=dp(8),
                         padding=(PAD, dp(8), PAD, dp(96)), **kw)
        self.vm, self.ctx = vm, ctx
        self.rows = {}
        self.bind(minimum_height=self.setter("height"))
        cats = vm.categories()
        # open the module's own settings first (not the shared General block)
        preferred = {"CUSTOMSHADER": "Shader Code", "AUDIOPROVIDER": "General"}.get(vm.module)
        first_specific = next((j for j, (c, _) in enumerate(cats)
                               if (c == preferred if preferred else c != "General")), 0)
        for i, (category, subs) in enumerate(cats):
            self.add_widget(AccordionSection(self, category, subs, expanded=(i == first_specific)))
        self._unsub = vm.subscribe(self._on_change)

    def _on_change(self, path, value):
        if path is None:
            for p, row in self.rows.items():
                row.refresh(self.vm.get(p))
            return
        row = self.rows.get(path)
        if row is not None and not getattr(row, "_quiet", False):
            row.refresh(value)

    def release(self):
        if self._unsub:
            self._unsub()
            self._unsub = None
