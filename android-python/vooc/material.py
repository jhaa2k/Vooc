"""Reusable Material 3-inspired widgets for Vooc's Kivy interface."""
from kivy.metrics import dp
from kivy.graphics import Color, RoundedRectangle, Line
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.slider import Slider
from kivy.uix.spinner import Spinner, SpinnerOption
from kivy.uix.textinput import TextInput
from kivy.uix.checkbox import CheckBox
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.popup import Popup

BG = (0.055, 0.063, 0.078, 1)
SURFACE = (0.105, 0.118, 0.145, 1)
SURFACE_HIGH = (0.145, 0.157, 0.188, 1)
PRIMARY = (0.61, 0.36, 0.96, 1)
ON_PRIMARY = (0.06, 0.04, 0.09, 1)
TEXT = (0.91, 0.91, 0.94, 1)
MUTED = (0.66, 0.68, 0.73, 1)
OUTLINE = (0.30, 0.32, 0.38, 1)

class MaterialLabel(Label):
    def __init__(self, **kwargs):
        kwargs.setdefault("color", TEXT)
        kwargs.setdefault("font_size", "12sp")
        super().__init__(**kwargs)

class MaterialButton(Button):
    def __init__(self, variant="tonal", **kwargs):
        self.variant = variant
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", dp(44))
        kwargs.setdefault("font_size", "13sp")
        kwargs.setdefault("background_normal", "")
        kwargs.setdefault("background_down", "")
        kwargs.setdefault("background_color", (0, 0, 0, 0))
        kwargs.setdefault("color", TEXT)
        super().__init__(**kwargs)
        self.background_normal = ""
        self.background_down = ""
        self.border = (0, 0, 0, 0)
        with self.canvas.before:
            self._fill = Color(1, 1, 1, 1)
            self._shape = RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(12)])
            self._outline_color = Color(0, 0, 0, 0)
            self._outline = Line(rounded_rectangle=(self.x, self.y, self.width, self.height, dp(12)), width=1)
        self.bind(pos=self._sync_shape, size=self._sync_shape, state=self._sync_shape)
        self.set_variant(variant)

    def set_variant(self, variant):
        self.variant = variant
        if variant == "filled":
            fill, text, outline = PRIMARY, ON_PRIMARY, (0, 0, 0, 0)
        elif variant == "outlined":
            fill, text, outline = SURFACE, TEXT, OUTLINE
        elif variant == "text":
            fill, text, outline = (0, 0, 0, 0), PRIMARY, (0, 0, 0, 0)
        else:
            fill, text, outline = SURFACE_HIGH, TEXT, (0, 0, 0, 0)
        self._base_fill = fill
        self.color = text
        self._outline_color.rgba = outline
        self._sync_shape()
        return self

    def _sync_shape(self, *_):
        if not hasattr(self, "_shape"):
            return
        fill = self._base_fill if hasattr(self, "_base_fill") else SURFACE_HIGH
        if self.state == "down" and fill[3] > 0:
            fill = (fill[0] * .84, fill[1] * .84, fill[2] * .84, fill[3])
        self._fill.rgba = fill
        self._shape.pos = self.pos
        self._shape.size = self.size
        self._outline.rounded_rectangle = (self.x, self.y, self.width, self.height, dp(12))

class MaterialCard(BoxLayout):
    def __init__(self, **kwargs):
        kwargs.setdefault("padding", dp(8))
        kwargs.setdefault("spacing", dp(6))
        super().__init__(**kwargs)
        with self.canvas.before:
            self._card_color = Color(*SURFACE)
            self._card_shape = RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(16)])
        self.bind(pos=self._sync_card, size=self._sync_card)

    def _sync_card(self, *_):
        self._card_shape.pos = self.pos
        self._card_shape.size = self.size

class MaterialSlider(Slider):
    def __init__(self, **kwargs):
        kwargs.setdefault("cursor_size", (dp(18), dp(18)))
        kwargs.setdefault("background_width", dp(4))
        kwargs.setdefault("value_track", True)
        kwargs.setdefault("value_track_color", PRIMARY)
        super().__init__(**kwargs)

class MaterialSpinnerOption(SpinnerOption):
    def __init__(self, **kwargs):
        kwargs.setdefault("background_normal", "")
        kwargs.setdefault("background_color", SURFACE_HIGH)
        kwargs.setdefault("color", TEXT)
        kwargs.setdefault("font_size", "12sp")
        super().__init__(**kwargs)

class MaterialSpinner(Spinner):
    def __init__(self, **kwargs):
        kwargs.setdefault("background_normal", "")
        kwargs.setdefault("background_color", SURFACE_HIGH)
        kwargs.setdefault("color", TEXT)
        kwargs.setdefault("font_size", "12sp")
        kwargs.setdefault("option_cls", MaterialSpinnerOption)
        super().__init__(**kwargs)

class MaterialTextInput(TextInput):
    def __init__(self, **kwargs):
        kwargs.setdefault("background_normal", "")
        kwargs.setdefault("background_active", "")
        kwargs.setdefault("background_color", SURFACE_HIGH)
        kwargs.setdefault("foreground_color", TEXT)
        kwargs.setdefault("hint_text_color", MUTED)
        kwargs.setdefault("cursor_color", PRIMARY)
        kwargs.setdefault("selection_color", (PRIMARY[0], PRIMARY[1], PRIMARY[2], .35))
        kwargs.setdefault("padding", [dp(12), dp(10)])
        kwargs.setdefault("font_size", "13sp")
        super().__init__(**kwargs)

class MaterialCheckBox(CheckBox):
    def __init__(self, **kwargs):
        kwargs.setdefault("color", PRIMARY)
        super().__init__(**kwargs)

class MaterialPopup(Popup):
    def __init__(self, **kwargs):
        kwargs.setdefault("background_color", SURFACE_HIGH)
        kwargs.setdefault("separator_color", PRIMARY)
        kwargs.setdefault("title_color", TEXT)
        kwargs.setdefault("title_size", "18sp")
        kwargs.setdefault("size_hint", (.88, .42))
        super().__init__(**kwargs)
