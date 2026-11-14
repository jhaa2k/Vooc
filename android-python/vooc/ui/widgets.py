"""Reusable Material-style widgets drawn with Kivy graphics (no image assets)."""
import math

from kivy.animation import Animation
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Ellipse, Line, Rectangle, RoundedRectangle, Triangle
from kivy.metrics import dp, sp
from kivy.properties import (BooleanProperty, ListProperty, NumericProperty, ObjectProperty,
                             StringProperty)
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.label import Label
from kivy.uix.modalview import ModalView
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.uix.widget import Widget

from ..model import hex_to_rgba, normalize_hex, rgba_to_hex
from .theme import FS_BODY, FS_LABEL, FS_SMALL, FS_TITLE, GAP, MONO, PAD, RADIUS, T


def label(text, size=FS_BODY, color=None, bold=False, halign="left", **kw):
    lb = Label(text=text, font_size=size, color=color or T.on_surface, bold=bold,
               halign=halign, valign="middle", shorten=kw.pop("shorten", True),
               shorten_from="right", **kw)
    lb.bind(size=lambda w, s: setattr(w, "text_size", s))
    return lb


class Surface(BoxLayout):
    """BoxLayout with a square background."""
    bg = ListProperty([0, 0, 0, 0])
    radius = NumericProperty(0)
    border = ListProperty([0, 0, 0, 0])

    def __init__(self, **kw):
        super().__init__(**kw)
        with self.canvas.before:
            self._c = Color(*self.bg)
            self._r = Rectangle()
            self._bc = Color(*self.border)
            self._b = Line(width=dp(1))
        self.bind(pos=self._upd, size=self._upd, bg=self._upd, border=self._upd, radius=self._upd)

    def _upd(self, *_):
        self._c.rgba = self.bg
        self._r.pos, self._r.size = self.pos, self.size
        self._bc.rgba = self.border
        self._b.rectangle = (self.x, self.y, self.width, self.height)


class Pressable(ButtonBehavior, Surface):
    """Rounded button with press feedback."""
    def __init__(self, **kw):
        self.normal_bg = kw.pop("bg", T.surface_hi)
        super().__init__(bg=list(self.normal_bg), **kw)

    def on_press(self):
        self.bg = [min(1, c + 0.08) for c in self.normal_bg[:3]] + [self.normal_bg[3]]

    def on_release(self):
        Animation(bg=list(self.normal_bg), d=0.18).start(self)

    def set_bg(self, color):
        self.normal_bg = color
        self.bg = list(color)


class TextButton(Pressable):
    def __init__(self, text, on_tap=None, primary=False, fs=FS_BODY, **kw):
        bg = T.primary if primary else kw.pop("bg", T.surface_hi)
        kw.setdefault("padding", (dp(14), 0))
        kw.setdefault("radius", 0)
        super().__init__(bg=bg, **kw)
        self.lbl = Label(text=text, font_size=fs, bold=True,
                         color=T.on_primary if primary else T.on_surface,
                         halign="center", valign="middle")
        self.lbl.bind(size=lambda w, s: setattr(w, "text_size", s))
        self.add_widget(self.lbl)
        if on_tap:
            self.bind(on_release=lambda *_: on_tap())

    @property
    def text(self):
        return self.lbl.text

    @text.setter
    def text(self, value):
        self.lbl.text = value


# ---------------------------------------------------------------- vector icons
class Icon(Widget):
    name = StringProperty("")
    tint = ListProperty([1, 1, 1, 1])

    def __init__(self, **kw):
        super().__init__(**kw)
        self.bind(pos=self.redraw, size=self.redraw, name=self.redraw, tint=self.redraw)

    def redraw(self, *_):
        self.canvas.clear()
        s = min(self.width, self.height) * 0.5
        cx, cy = self.center
        w = max(dp(1.6), s * 0.11)
        n = self.name
        with self.canvas:
            Color(*self.tint)
            if n == "back":
                Line(points=[cx + s * .45, cy, cx - s * .45, cy], width=w, cap="round")
                Line(points=[cx - s * .05, cy + s * .42, cx - s * .47, cy, cx - s * .05, cy - s * .42],
                     width=w, cap="round", joint="round")
            elif n == "reset":
                Line(circle=(cx, cy, s * .42, 40, 330), width=w, cap="round")
                a = math.radians(40)
                tx, ty = cx + math.sin(a) * s * .42, cy + math.cos(a) * s * .42
                Triangle(points=[tx - s * .2, ty + s * .14, tx + s * .14, ty + s * .14, tx - s * .02, ty - s * .2])
            elif n == "info":
                Line(circle=(cx, cy, s * .45), width=w)
                Ellipse(pos=(cx - w, cy + s * .2), size=(w * 2, w * 2))
                Line(points=[cx, cy - s * .25, cx, cy + s * .05], width=w, cap="round")
            elif n == "play":
                Triangle(points=[cx - s * .3, cy - s * .4, cx - s * .3, cy + s * .4, cx + s * .42, cy])
            elif n == "pause":
                Rectangle(pos=(cx - s * .32, cy - s * .38), size=(s * .22, s * .76))
                Rectangle(pos=(cx + s * .1, cy - s * .38), size=(s * .22, s * .76))
            elif n in ("next", "prev"):
                k = 1 if n == "next" else -1
                Triangle(points=[cx - k * s * .36, cy - s * .36, cx - k * s * .36, cy + s * .36, cx + k * s * .2, cy])
                x = cx + k * s * .24 - (s * .12 if k > 0 else 0)
                Rectangle(pos=(x, cy - s * .36), size=(s * .12, s * .72))
            elif n == "folder":
                Line(points=[cx - s * .45, cy + s * .3, cx - s * .1, cy + s * .3, cx, cy + s * .18,
                             cx + s * .45, cy + s * .18, cx + s * .45, cy - s * .32,
                             cx - s * .45, cy - s * .32, cx - s * .45, cy + s * .3], width=w, joint="round")
            elif n == "queue":
                for i in range(3):
                    y = cy + s * (.3 - i * .3)
                    Line(points=[cx - s * .42, y, cx + s * .42, y], width=w, cap="round")
            elif n == "theme":
                Line(circle=(cx, cy, s * .4), width=w)
                Ellipse(pos=(cx - s * .4, cy - s * .4), size=(s * .8, s * .8), angle_start=0, angle_end=180)
            elif n == "chevron":
                Line(points=[cx - s * .3, cy + s * .15, cx, cy - s * .15, cx + s * .3, cy + s * .15],
                     width=w, cap="round", joint="round")
            elif n == "chevron_up":
                Line(points=[cx - s * .3, cy - s * .15, cx, cy + s * .15, cx + s * .3, cy - s * .15],
                     width=w, cap="round", joint="round")
            elif n == "close":
                Line(points=[cx - s * .32, cy - s * .32, cx + s * .32, cy + s * .32], width=w, cap="round")
                Line(points=[cx - s * .32, cy + s * .32, cx + s * .32, cy - s * .32], width=w, cap="round")
            elif n == "edit":
                Line(points=[cx - s * .35, cy - s * .35, cx + s * .3, cy + s * .3], width=w * 1.4, cap="round")
                Line(points=[cx - s * .42, cy - s * .42, cx - s * .1, cy - s * .42], width=w, cap="round")
            elif n == "dot":
                Ellipse(pos=(cx - s * .25, cy - s * .25), size=(s * .5, s * .5))
            elif n == "plus":
                Line(points=[cx - s * .35, cy, cx + s * .35, cy], width=w, cap="round")
                Line(points=[cx, cy - s * .35, cx, cy + s * .35], width=w, cap="round")
            elif n == "minus":
                Line(points=[cx - s * .35, cy, cx + s * .35, cy], width=w, cap="round")


class IconButton(ButtonBehavior, Widget):
    def __init__(self, icon, on_tap=None, tint=None, bg=None, **kw):
        kw.setdefault("size_hint", (None, None))
        kw.setdefault("size", (dp(44), dp(44)))
        super().__init__(**kw)
        self.bg = bg
        with self.canvas.before:
            self._c = Color(*(bg or [0, 0, 0, 0]))
            self._e = Ellipse()
        self.icon = Icon(name=icon, tint=tint or T.on_surface)
        self.add_widget(self.icon)
        self.bind(pos=self._upd, size=self._upd)
        if on_tap:
            self.bind(on_release=lambda *_: on_tap())

    def _upd(self, *_):
        self._e.pos, self._e.size = self.pos, self.size
        pad = self.width * 0.22
        self.icon.pos = (self.x + pad, self.y + pad)
        self.icon.size = (self.width - pad * 2, self.height - pad * 2)

    def on_press(self):
        self._c.rgba = T.primary_soft if self.bg is None else [min(1, c + .1) for c in self.bg[:3]] + [1]

    def on_release(self):
        self._c.rgba = self.bg or [0, 0, 0, 0]


# ---------------------------------------------------------------- inputs
class SliderBar(Widget):
    """Thin Material slider. value in [min, max]; on_change(value) while dragging."""
    value = NumericProperty(0.0)
    min = NumericProperty(0.0)
    max = NumericProperty(1.0)
    step = NumericProperty(0.0)
    on_change = ObjectProperty(None, allownone=True)
    on_commit = ObjectProperty(None, allownone=True)

    def __init__(self, **kw):
        super().__init__(**kw)
        with self.canvas:
            self._tc = Color(*T.outline)
            self._track = Rectangle()
            self._fc = Color(*T.primary)
            self._fill = Rectangle()
            self._hc = Color(*T.primary_soft)
            self._halo = Ellipse()
            self._kc = Color(*T.primary)
            self._knob = Ellipse()
        self._dragging = False
        self.bind(pos=self._upd, size=self._upd, value=self._upd, min=self._upd, max=self._upd)

    def _norm(self):
        span = self.max - self.min
        return 0.0 if span <= 0 else (self.value - self.min) / span

    def _upd(self, *_):
        h = dp(4)
        pad = dp(10)
        x0, w = self.x + pad, max(1, self.width - pad * 2)
        cy = self.center_y
        self._track.pos, self._track.size = (x0, cy - h / 2), (w, h)
        fx = x0 + w * max(0, min(1, self._norm()))
        self._fill.pos, self._fill.size = (x0, cy - h / 2), (fx - x0, h)
        r = dp(8) if not self._dragging else dp(10)
        self._knob.pos, self._knob.size = (fx - r, cy - r), (r * 2, r * 2)
        hr = dp(18) if self._dragging else 0
        self._halo.pos, self._halo.size = (fx - hr, cy - hr), (hr * 2, hr * 2)

    def _set_from_touch(self, touch):
        pad = dp(10)
        t = (touch.x - self.x - pad) / max(1, self.width - pad * 2)
        v = self.min + max(0.0, min(1.0, t)) * (self.max - self.min)
        if self.step:
            v = round(v / self.step) * self.step
        self.value = max(self.min, min(self.max, v))
        if self.on_change:
            self.on_change(self.value)

    def on_touch_down(self, touch):
        if self.collide_point(*touch.pos) and not touch.is_mouse_scrolling:
            touch.grab(self)
            self._dragging = True
            self._start_x = touch.x
            self._set_from_touch(touch)
            return True
        return super().on_touch_down(touch)

    def on_touch_move(self, touch):
        if touch.grab_current is self:
            self._set_from_touch(touch)
            return True
        return super().on_touch_move(touch)

    def on_touch_up(self, touch):
        if touch.grab_current is self:
            touch.ungrab(self)
            self._dragging = False
            self._upd()
            if self.on_commit:
                self.on_commit(self.value)
            return True
        return super().on_touch_up(touch)


class ToggleSwitch(ButtonBehavior, Widget):
    active = BooleanProperty(False)
    on_change = ObjectProperty(None, allownone=True)

    def __init__(self, **kw):
        kw.setdefault("size_hint", (None, None))
        kw.setdefault("size", (dp(46), dp(28)))
        super().__init__(**kw)
        with self.canvas:
            self._tc = Color()
            self._track = Rectangle()
            self._kc = Color()
            self._knob = Ellipse()
        self.bind(pos=self._upd, size=self._upd, active=self._upd)
        self._upd()

    def _upd(self, *_):
        self._tc.rgba = T.primary if self.active else T.outline
        self._track.pos, self._track.size = self.pos, self.size
        r = self.height * 0.36
        x = self.right - self.height / 2 if self.active else self.x + self.height / 2
        self._kc.rgba = T.on_primary if self.active else T.muted
        self._knob.pos, self._knob.size = (x - r, self.center_y - r), (r * 2, r * 2)

    def on_release(self):
        self.active = not self.active
        if self.on_change:
            self.on_change(self.active)


class Swatch(ButtonBehavior, Widget):
    """Color chip with a checkerboard so alpha is visible."""
    rgba = ListProperty([1, 1, 1, 1])

    def __init__(self, **kw):
        kw.setdefault("size_hint", (None, None))
        kw.setdefault("size", (dp(40), dp(28)))
        super().__init__(**kw)
        self.bind(pos=self._draw, size=self._draw, rgba=self._draw)

    def _draw(self, *_):
        self.canvas.clear()
        with self.canvas:
            n = 4
            cw, ch = self.width / n, self.height / 2
            for i in range(n):
                for j in range(2):
                    g = .85 if (i + j) % 2 else .55
                    Color(g, g, g, 1)
                    Rectangle(pos=(self.x + i * cw, self.y + j * ch), size=(cw, ch))
            Color(*self.rgba)
            Rectangle(pos=self.pos, size=self.size)
            Color(*T.outline)
            Line(rectangle=(self.x, self.y, self.width, self.height), width=dp(1))


def text_field(text="", multiline=False, mono=False, **kw):
    ti = TextInput(text=text, multiline=multiline, font_size=FS_BODY,
                   background_normal="", background_active="",
                   background_color=T.surface_top, foreground_color=T.on_surface,
                   cursor_color=T.primary, hint_text_color=T.muted,
                   selection_color=T.primary_soft,
                   padding=(dp(10), dp(10)), write_tab=False, **kw)
    if mono:
        ti.font_name = MONO
    return ti


# ---------------------------------------------------------------- dialogs
class Dialog(ModalView):
    def __init__(self, title, body, actions=(), size_hint=(.92, None), height=None, **kw):
        super().__init__(size_hint=size_hint, auto_dismiss=True, background="",
                         background_color=[0, 0, 0, 0], overlay_color=T.scrim, **kw)
        card = Surface(orientation="vertical", bg=T.surface, padding=PAD, spacing=GAP,
                       radius=0)
        card.add_widget(label(title, FS_TITLE, bold=True, size_hint_y=None, height=dp(36)))
        card.add_widget(body)
        if actions:
            row = BoxLayout(size_hint_y=None, height=dp(46), spacing=GAP)
            row.add_widget(Widget())
            for text, cb, primary in actions:
                def tap(cb=cb):
                    if cb is None or cb() is not False:
                        self.dismiss()
                row.add_widget(TextButton(text, tap, primary=primary, size_hint_x=None,
                                          width=dp(max(96, len(text) * 10 + 32))))
            card.add_widget(row)
        self.add_widget(card)
        if height:
            self.height = height
        elif size_hint[1] is None:
            self.height = min(Window.height * .8, dp(420))


def confirm(title, message, on_yes, yes="Reset"):
    body = label(message, FS_BODY, color=T.muted, shorten=False)
    Dialog(title, body, [("Cancel", None, False), (yes, on_yes, True)], height=dp(220)).open()


def info(title, message):
    scroll = ScrollView(do_scroll_x=False)
    lb = Label(text=message, font_size=FS_BODY, color=T.on_surface, size_hint_y=None,
               halign="left", valign="top", markup=True)
    lb.bind(width=lambda w, v: setattr(w, "text_size", (v, None)),
            texture_size=lambda w, v: setattr(w, "height", v[1]))
    scroll.add_widget(lb)
    Dialog(title, scroll, [("Close", None, True)], size_hint=(.92, .7)).open()


def choose(title, options, current, on_pick):
    """Scrollable option list (dropdowns have up to 27 entries)."""
    scroll = ScrollView(do_scroll_x=False)
    box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(4))
    box.bind(minimum_height=box.setter("height"))
    dlg = Dialog(title, scroll, [("Cancel", None, False)], size_hint=(.92, .78))
    for opt in options:
        selected = opt == current
        row = Pressable(bg=T.primary_soft if selected else T.surface_hi, size_hint_y=None,
                        height=dp(42), padding=(dp(12), 0), radius=0)
        row.add_widget(label(opt, FS_BODY, color=T.primary if selected else T.on_surface,
                             bold=selected))

        def tap(*_, opt=opt):
            dlg.dismiss()
            on_pick(opt)
        row.bind(on_release=tap)
        box.add_widget(row)
    scroll.add_widget(box)
    dlg.open()


def color_dialog(title, hex_value, on_pick):
    from kivy.uix.colorpicker import ColorPicker
    body = BoxLayout(orientation="vertical", spacing=GAP)
    picker = ColorPicker(color=hex_to_rgba(hex_value))
    field = text_field(normalize_hex(hex_value), size_hint_y=None, height=dp(44), mono=True,
                       hint_text="#RRGGBBAA")
    lock = {"busy": False}

    def from_picker(_, color):
        if not lock["busy"]:
            lock["busy"] = True
            field.text = rgba_to_hex(color)
            lock["busy"] = False

    def from_field(_, text):
        if lock["busy"]:
            return
        h = normalize_hex(text, None)
        if h:
            lock["busy"] = True
            picker.color = hex_to_rgba(h)
            lock["busy"] = False
    picker.bind(color=from_picker)
    field.bind(text=from_field)
    body.add_widget(picker)
    hint = label("Hex RGBA  (#RRGGBBAA, last two digits = alpha)", FS_SMALL, color=T.muted,
                 size_hint_y=None, height=dp(18))
    body.add_widget(hint)
    body.add_widget(field)

    def ok():
        on_pick(normalize_hex(field.text, rgba_to_hex(picker.color)))
    Dialog(title, body, [("Cancel", None, False), ("Apply", ok, True)], size_hint=(.96, .9)).open()


def text_dialog(title, value, on_ok, multiline=True, mono=False, help_text=""):
    body = BoxLayout(orientation="vertical", spacing=GAP)
    if help_text:
        body.add_widget(label(help_text, FS_SMALL, color=T.muted, size_hint_y=None, height=dp(30),
                              shorten=False))
    field = text_field(value, multiline=multiline, mono=mono)
    body.add_widget(field)

    def ok():
        return on_ok(field.text)
    Dialog(title, body, [("Cancel", None, False), ("Save", ok, True)],
           size_hint=(.96, .9 if multiline else None), height=None if multiline else dp(230)).open()


class Snackbar(FloatLayout):
    """Bottom toast; attach once to the root window."""

    def __init__(self, **kw):
        super().__init__(**kw)
        self.card = Surface(bg=T.surface_top, size_hint=(None, None), padding=(dp(12), dp(8)),
                            radius=0, opacity=0)
        self.lbl = Label(font_size=FS_LABEL, color=T.on_surface, halign="left", valign="middle")
        self.card.add_widget(self.lbl)
        self.add_widget(self.card)
        self._ev = None

    def show(self, text, seconds=3.0):
        self.lbl.text = text
        w = min(Window.width - dp(32), dp(520))
        self.lbl.text_size = (w - dp(32), None)
        self.lbl.texture_update()
        self.card.size = (w, max(dp(44), self.lbl.texture_size[1] + dp(20)))
        self.card.pos = ((Window.width - w) / 2, dp(24))
        Animation.cancel_all(self.card)
        Animation(opacity=1, d=.15).start(self.card)
        if self._ev:
            self._ev.cancel()
        self._ev = Clock.schedule_once(lambda dt: Animation(opacity=0, d=.3).start(self.card), seconds)

    def on_touch_down(self, touch):
        return False
