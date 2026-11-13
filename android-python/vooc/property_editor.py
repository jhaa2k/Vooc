"""Hierarchical property editor (Material 3 styled) for advanced visualizer settings.

Two-column module grid -> details view (Back / title / Reset / Info) with
collapsible sections that are built lazily on first open. Every control writes
straight into ``state.properties`` and calls ``on_change`` on each movement so
the preview updates live. Dropdowns always expose the complete option list.
"""
from copy import deepcopy

from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

from . import m3
from .m3 import T
from .property_schema import SCHEMA

_ICONS = {"COMPOSITION": "studio", "IMAGE": "image", "TEXT": "text", "BARS": "bars", "PARTICLES": "sparkle",
          "AUDIOPROVIDER": "tune", "BLUREFFECT": "eye", "RGBSPLITEFFECT": "layers", "MOTIONBLUREFFECT": "next",
          "MIRROREREFFECT": "copy", "CUSTOMSHADER": "more"}


def _pretty(name):
    special = {"AUDIOPROVIDER": "Audio Provider", "BLUREFFECT": "Blur Effect", "RGBSPLITEFFECT": "RGB Split",
               "MOTIONBLUREFFECT": "Motion Blur", "MIRROREREFFECT": "Mirrorer", "CUSTOMSHADER": "Custom Shader"}
    return special.get(name, name.title())


class AdvancedProperties(BoxLayout):
    def __init__(self, state, on_change=None, on_save=None, **kwargs):
        super().__init__(orientation="vertical", spacing=dp(8), **kwargs)
        self.state, self.on_change, self.on_save = state, on_change, on_save
        if not hasattr(state, "properties"):
            state.properties = {}
        self.category = None
        self._home()

    # ------------------------------------------------------------ values
    def _value(self, category, section, group, name, spec):
        path = category + "." + section + "." + group + "." + name
        if path not in self.state.properties:
            self.state.properties[path] = deepcopy(spec.get("default"))
        return path, self.state.properties[path]

    def _changed(self, path, value):
        self.state.properties[path] = value
        if self.on_change:
            self.on_change()
        if self.on_save:
            self.on_save()

    def _count(self, category):
        return sum(len(f) for groups in SCHEMA[category].values() for f in groups.values())

    def _edited(self, category):
        """Number of properties that differ from their schema default."""
        props = self.state.properties
        n = 0
        for section, groups in SCHEMA[category].items():
            for group, fields in groups.items():
                for name, spec in fields.items():
                    path = category + "." + section + "." + group + "." + name
                    if path in props and props[path] != spec.get("default"):
                        n += 1
        return n

    # ------------------------------------------------------------ home
    def _home(self):
        self.clear_widgets()
        self.category = None
        head = BoxLayout(size_hint_y=None, height=dp(40), padding=(dp(4), 0))
        head.add_widget(m3.lbl("Advanced properties", 16, bold=True))
        head.add_widget(m3.lbl("%d modules" % len(SCHEMA), 12, T.on_var, halign="right"))
        self.add_widget(head)
        scroll = ScrollView(do_scroll_x=False, bar_width=dp(3))
        grid = GridLayout(cols=2, spacing=dp(10), padding=(0, dp(2), 0, dp(12)), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))
        for name in SCHEMA:
            tile = m3.Tap(on_tap=lambda n=name: self._details(n), orientation="vertical", bg=T.surface2,
                          radius=dp(18), padding=dp(12), spacing=dp(6), size_hint_y=None, height=dp(96))
            top = BoxLayout(size_hint_y=None, height=dp(34))
            ic = m3.Box(bg=T.primary_container, radius=dp(12), size_hint=(None, None), size=(dp(34), dp(34)))
            ic.add_widget(m3.Icon(name=_ICONS.get(name, "tune"), tint=T.on_primary_container))
            ic.padding = dp(8)
            top.add_widget(ic)
            top.add_widget(Widget())
            edited = self._edited(name)
            if edited:
                top.add_widget(m3.lbl("%d edited" % edited, 11, T.primary, halign="right", size_hint_x=None, width=dp(70)))
            tile.add_widget(top)
            tile.add_widget(m3.lbl(_pretty(name), 14, bold=True))
            tile.add_widget(m3.lbl("%d settings" % self._count(name), 11, T.on_var))
            grid.add_widget(tile)
        scroll.add_widget(grid)
        self.add_widget(scroll)

    # ------------------------------------------------------------ details
    def _details(self, category):
        self.category = category
        self.clear_widgets()
        top = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(4))
        top.add_widget(m3.IconBtn("back", self._home))
        top.add_widget(m3.lbl(_pretty(category), 17, bold=True))
        top.add_widget(m3.IconBtn("reset", lambda: self._reset(category)))
        top.add_widget(m3.IconBtn("info", lambda: self._info(category)))
        self.add_widget(top)
        scroll = ScrollView(do_scroll_x=False, bar_width=dp(3))
        col = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(8), padding=(0, 0, 0, dp(16)))
        col.bind(minimum_height=col.setter("height"))
        first = True
        for section, groups in SCHEMA[category].items():
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
                    return
                arr.name = "up"
                for group, fields in grp.items():
                    box.add_widget(m3.lbl(group, 12, T.primary, bold=True, size_hint_y=None, height=dp(28)))
                    for name, spec in fields.items():
                        try:
                            box.add_widget(self._control(category, sec, group, name, spec))
                        except Exception as exc:
                            box.add_widget(m3.lbl(name + ": " + str(exc), 11, T.error, size_hint_y=None, height=dp(30)))
                box.add_widget(Widget(size_hint_y=None, height=dp(6)))

            header.bind(on_release=lambda *_, t=toggle: t())
            sec_card.add_widget(header)
            sec_card.add_widget(holder)
            col.add_widget(sec_card)
            if first:
                toggle()
                first = False
        scroll.add_widget(col)
        self.add_widget(scroll)

    def _control(self, category, section, group, name, spec):
        path, value = self._value(category, section, group, name, spec)
        kind = spec["type"]
        if kind == "slider":
            lo, hi = float(spec.get("min", 0)), float(spec.get("max", 1))
            span = hi - lo
            step = 1.0 if span >= 50 and float(lo).is_integer() and float(hi).is_integer() else 0.0
            fmt = (lambda v: "%d" % round(v)) if step else (lambda v: "%.3f" % v if span <= 2 else "%.1f" % v)
            return m3.SliderRow(name, lo, hi, max(lo, min(hi, float(value or 0))),
                                lambda v, p=path: self._changed(p, round(float(v), 3)), step=step, fmt=fmt,
                                bipolar=(lo < 0 < hi), default=spec.get("default", lo))
        if kind == "checkbox":
            return m3.switch_row(name, bool(value), lambda v, p=path: self._changed(p, bool(v)))
        row = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(4))
        row.add_widget(m3.lbl(name, 12, T.on_var, size_hint_y=None, height=dp(20)))
        if kind == "dropdown":
            options = [str(v) for v in spec.get("options", [])]
            row.add_widget(m3.SelectField(name, options, str(value), lambda v, p=path: self._changed(p, v)))
        elif kind == "action":
            row.add_widget(m3.Button3(str(value), lambda p=path: self._action(p, category), kind="tonal"))
        elif kind == "color":
            line = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
            sw = m3.Box(bg=self._rgba(value), radius=dp(12), line=T.outline, size_hint_x=None, width=dp(46))

            def col_changed(v, p=path, s=sw):
                self._changed(p, v)
                s.bg = self._rgba(v)
            line.add_widget(sw)
            line.add_widget(m3.text_field(value if value is not None else "", col_changed, "#RRGGBB"))
            row.add_widget(line)
        else:
            def text_changed(v, p=path, k=kind):
                if k == "number":
                    v = int(v) if v.lstrip("-").isdigit() else self._number(v)
                self._changed(p, v)
            row.add_widget(m3.text_field(value if value is not None else "", text_changed, kind.upper(),
                                         multiline=(kind == "code")))
        row.bind(minimum_height=row.setter("height"))
        return row

    @staticmethod
    def _rgba(value):
        try:
            return m3._hex(str(value)[:7])
        except Exception:
            return [0, 0, 0, 0]

    def _number(self, value):
        try:
            return float(value)
        except (TypeError, ValueError):
            return 0

    def _action(self, path, category):
        if path.endswith(".Compile / Apply"):
            self._changed("runtime.shader_compile_requested", True)
        elif path.endswith(".Add Variable"):
            self._changed("runtime.shader_variable_add_requested", True)

    def _reset(self, category):
        prefix = category + "."
        for path in list(self.state.properties):
            if path.startswith(prefix):
                del self.state.properties[path]
        if self.on_change:
            self.on_change()
        self._details(category)

    def _info(self, category):
        sheet = m3.Sheet(_pretty(category))
        sheet.height = dp(220)
        sheet.box.add_widget(m3.lbl("%d settings. Values are saved per property in the project JSON and "
                                    "applied live to the preview. Tap a value chip to reset one slider."
                                    % self._count(category), 13, T.on_var, shorten=False, valign="top"))
        sheet.open()
