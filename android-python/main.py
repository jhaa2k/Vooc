import math
import os
import random
import threading
import time

import numpy as np
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.slider import Slider
from kivy.uix.filechooser import FileChooserListView
from kivy.uix.popup import Popup
from kivy.uix.widget import Widget
from kivy.graphics import Color, Rectangle, RoundedRectangle, Line, Ellipse
from kivy.lang import Builder
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.properties import (
    BooleanProperty,
    ListProperty,
    NumericProperty,
    StringProperty,
)
from kivy.clock import Clock

try:
    from android.permissions import request_permissions, Permission
    ANDROID = True
except ImportError:
    ANDROID = False

try:
    import sounddevice as sd
    AUDIO_BACKEND = "sounddevice"
except ImportError:
    sd = None
    AUDIO_BACKEND = "none"

try:
    import miniaudio
    DECODER = "miniaudio"
except ImportError:
    miniaudio = None
    DECODER = "none"

SR = 44100
BANDS = 48
FFT_SIZE = 2048

class AudioEngine:
    def __init__(self):
        self.samples = None
        self.path = ""
        self.duration = 0.0
        self.position = 0
        self.playing = False
        self.volume = 0.8
        self.lock = threading.Lock()
        self.stream = None
        self.bands = np.zeros(BANDS, dtype=np.float32)
        self.beat = 0.0
        self.last_beat = 0.0
        self.playlist = []
        self.index = -1

    def load(self, path):
        if DECODER != "miniaudio":
            raise RuntimeError("miniaudio is required for audio decoding")

        decoded = miniaudio.decode_file(
            path,
            nchannels=2,
            sample_rate=SR,
            dither=miniaudio.DitherMode.NONE,
        )

        data = np.frombuffer(decoded.samples, dtype=np.int16).astype(np.float32)
        data /= 32768.0
        data = data.reshape(-1, 2)

        with self.lock:
            self.samples = data
            self.path = path
            self.duration = len(data) / SR
            self.position = 0
            self.playing = False

        if path not in self.playlist:
            self.playlist.append(path)
        self.index = self.playlist.index(path)

    def play(self):
        with self.lock:
            if self.samples is None:
                return
            self.playing = True

        if self.stream is None and AUDIO_BACKEND == "sounddevice":
            self.stream = sd.OutputStream(
                samplerate=SR,
                channels=2,
                dtype="float32",
                callback=self.callback,
            )
            self.stream.start()

    def pause(self):
        with self.lock:
            self.playing = False

    def toggle(self):
        if self.playing:
            self.pause()
        else:
            self.play()

    def seek(self, seconds):
        with self.lock:
            if self.samples is None:
                return
            self.position = int(np.clip(seconds, 0, self.duration) * SR)

    def current_time(self):
        with self.lock:
            return self.position / SR if self.samples is not None else 0.0

    def next_track(self):
        if not self.playlist:
            return
        self.index = (self.index + 1) % len(self.playlist)
        self.load(self.playlist[self.index])
        self.play()

    def previous_track(self):
        if not self.playlist:
            return
        self.index = (self.index - 1) % len(self.playlist)
        self.load(self.playlist[self.index])
        self.play()

    def callback(self, out, frames, time_info, status):
        with self.lock:
            if self.samples is None or not self.playing:
                out[:] = 0
                mono = np.zeros(frames, dtype=np.float32)
            else:
                end = self.position + frames
                chunk = self.samples[self.position:end]

                if len(chunk) < frames:
                    out[: len(chunk)] = chunk * self.volume
                    out[len(chunk):] = 0
                    self.position = len(self.samples)
                    self.playing = False
                else:
                    out[:] = chunk * self.volume
                    self.position = end

                mono = out.mean(axis=1)

        self.analyze(mono)

    def analyze(self, mono):
        if len(mono) < FFT_SIZE:
            mono = np.pad(mono, (0, FFT_SIZE - len(mono)))
        else:
            mono = mono[:FFT_SIZE]

        spectrum = np.abs(np.fft.rfft(mono * np.hanning(FFT_SIZE)))
        freqs = np.fft.rfftfreq(FFT_SIZE, 1 / SR)
        edges = np.geomspace(30, 16000, BANDS + 1)
        new_bands = np.zeros(BANDS, dtype=np.float32)

        for i in range(BANDS):
            low = np.searchsorted(freqs, edges[i])
            high = np.searchsorted(freqs, edges[i + 1])
            if high > low:
                new_bands[i] = spectrum[low:high].mean()

        if new_bands.max() > 0:
            new_bands /= new_bands.max()

        self.bands = np.maximum(self.bands * 0.82, new_bands)

        bass = float(self.bands[:8].mean())
        now = time.time()

        if bass > 0.42 and now - self.last_beat > 0.18:
            self.beat = 1.0
            self.last_beat = now
        else:
            self.beat *= 0.90


# ---------------------------------------------------------------------------
# Material-style design tokens: acid green accent on dark / light surfaces
# ---------------------------------------------------------------------------

def hex_rgba(value, alpha=1.0):
    value = value.lstrip("#")
    return [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)] + [alpha]


PALETTES = {
    "dark": {
        "bg": hex_rgba("#0B0D0A"),
        "surface": hex_rgba("#151812"),
        "surface_hi": hex_rgba("#1F241A"),
        "outline": hex_rgba("#2E3527"),
        "on_surface": hex_rgba("#EEF3E6"),
        "muted": hex_rgba("#8E9886"),
        "primary": hex_rgba("#C6FF00"),
        "on_primary": hex_rgba("#0E1400"),
        "primary_soft": hex_rgba("#C6FF00", 0.14),
        "secondary": hex_rgba("#5BFFB0"),
        "vis_bg": hex_rgba("#070906"),
    },
    "light": {
        "bg": hex_rgba("#F4F7EC"),
        "surface": hex_rgba("#FFFFFF"),
        "surface_hi": hex_rgba("#E9EFDC"),
        "outline": hex_rgba("#D3DBC4"),
        "on_surface": hex_rgba("#141812"),
        "muted": hex_rgba("#5E6857"),
        "primary": hex_rgba("#5E8C00"),
        "on_primary": hex_rgba("#FFFFFF"),
        "primary_soft": hex_rgba("#8BC400", 0.16),
        "secondary": hex_rgba("#00A86B"),
        "vis_bg": hex_rgba("#10140C"),
    },
}

VIS_MODES = ["BARS", "MIRROR", "RADIAL", "WAVE", "PARTICLES"]


KV = """
#:import dp kivy.metrics.dp

<Card@BoxLayout>:
    padding: dp(16)
    spacing: dp(10)
    canvas.before:
        Color:
            rgba: app.c_surface
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(24)]

<Chip>:
    size_hint: None, None
    height: dp(34)
    width: self.texture_size[0] + dp(28)
    font_size: "13sp"
    bold: True
    color: app.c_primary if self.selected else app.c_on_surface
    background_normal: ""
    background_down: ""
    background_color: 0, 0, 0, 0
    canvas.before:
        Color:
            rgba: app.c_primary_soft if self.selected else app.c_surface_hi
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [self.height / 2]

<IconButton>:
    size_hint: None, None
    size: dp(52), dp(52)
    background_normal: ""
    background_down: ""
    background_color: 0, 0, 0, 0
    canvas.before:
        Color:
            rgba: (app.c_primary if self.filled else (app.c_surface_hi if self.state == "normal" else app.c_outline))
        Ellipse:
            pos: self.pos
            size: self.size
    canvas.after:
        Color:
            rgba: app.c_on_primary if self.filled else app.c_on_surface
        # play
        Triangle:
            points: ([self.center_x - self.width * .12, self.center_y - self.height * .17, self.center_x - self.width * .12, self.center_y + self.height * .17, self.center_x + self.width * .18, self.center_y] if self.icon == "play" else [0, 0, 0, 0, 0, 0])
        # pause
        Rectangle:
            pos: (self.center_x - self.width * .15, self.center_y - self.height * .16) if self.icon == "pause" else (0, 0)
            size: (self.width * .1, self.height * .32) if self.icon == "pause" else (0, 0)
        Rectangle:
            pos: (self.center_x + self.width * .05, self.center_y - self.height * .16) if self.icon == "pause" else (0, 0)
            size: (self.width * .1, self.height * .32) if self.icon == "pause" else (0, 0)
        # next
        Triangle:
            points: ([self.center_x - self.width * .16, self.center_y - self.height * .14, self.center_x - self.width * .16, self.center_y + self.height * .14, self.center_x + self.width * .08, self.center_y] if self.icon == "next" else [0, 0, 0, 0, 0, 0])
        Rectangle:
            pos: (self.center_x + self.width * .08, self.center_y - self.height * .14) if self.icon == "next" else (0, 0)
            size: (self.width * .07, self.height * .28) if self.icon == "next" else (0, 0)
        # prev
        Triangle:
            points: ([self.center_x + self.width * .16, self.center_y - self.height * .14, self.center_x + self.width * .16, self.center_y + self.height * .14, self.center_x - self.width * .08, self.center_y] if self.icon == "prev" else [0, 0, 0, 0, 0, 0])
        Rectangle:
            pos: (self.center_x - self.width * .15, self.center_y - self.height * .14) if self.icon == "prev" else (0, 0)
            size: (self.width * .07, self.height * .28) if self.icon == "prev" else (0, 0)
        # folder / open (plus)
        Rectangle:
            pos: (self.center_x - self.width * .17, self.center_y - self.height * .035) if self.icon == "add" else (0, 0)
            size: (self.width * .34, self.height * .07) if self.icon == "add" else (0, 0)
        Rectangle:
            pos: (self.center_x - self.width * .035, self.center_y - self.height * .17) if self.icon == "add" else (0, 0)
            size: (self.width * .07, self.height * .34) if self.icon == "add" else (0, 0)
        # theme (half moon)
        Line:
            circle: (self.center_x, self.center_y, self.width * .17) if self.icon == "theme" else (0, 0, 0)
            width: dp(1.6)
        Ellipse:
            pos: (self.center_x - self.width * .17, self.center_y - self.height * .17) if self.icon == "theme" else (0, 0)
            size: (self.width * .34, self.height * .34) if self.icon == "theme" else (0, 0)
            angle_start: 180
            angle_end: 360

<MSlider>:
    cursor_size: dp(18), dp(18)
    canvas:
        Clear
        Color:
            rgba: app.c_outline
        RoundedRectangle:
            pos: self.x + self.padding, self.center_y - dp(3)
            size: self.width - self.padding * 2, dp(6)
            radius: [dp(3)]
        Color:
            rgba: app.c_primary
        RoundedRectangle:
            pos: self.x + self.padding, self.center_y - dp(3)
            size: max(0, self.value_pos[0] - self.x - self.padding), dp(6)
            radius: [dp(3)]
        Color:
            rgba: app.c_primary_soft if self.dragging else (0, 0, 0, 0)
        Ellipse:
            pos: self.value_pos[0] - dp(18), self.center_y - dp(18)
            size: dp(36), dp(36)
        Color:
            rgba: app.c_primary
        Ellipse:
            pos: self.value_pos[0] - dp(9), self.center_y - dp(9)
            size: dp(18), dp(18)

<Root>:
    orientation: "vertical"
    padding: dp(16), dp(12)
    spacing: dp(12)
    canvas.before:
        Color:
            rgba: app.c_bg
        Rectangle:
            pos: self.pos
            size: self.size

    # ---- Top app bar ----
    BoxLayout:
        size_hint_y: None
        height: dp(56)
        spacing: dp(10)
        Widget:
            size_hint: None, None
            size: dp(36), dp(36)
            pos_hint: {"center_y": .5}
            canvas:
                Color:
                    rgba: app.c_primary
                RoundedRectangle:
                    pos: self.pos
                    size: self.size
                    radius: [dp(12)]
                Color:
                    rgba: app.c_on_primary
                Triangle:
                    points: [self.x + dp(9), self.top - dp(10), self.right - dp(9), self.top - dp(10), self.center_x, self.y + dp(9)]
        BoxLayout:
            orientation: "vertical"
            Label:
                text: "Vooc"
                font_size: "22sp"
                bold: True
                color: app.c_on_surface
                halign: "left"
                valign: "bottom"
                text_size: self.size
            Label:
                text: "Audio visualizer"
                font_size: "12sp"
                color: app.c_muted
                halign: "left"
                valign: "top"
                text_size: self.size
        IconButton:
            icon: "theme"
            size: dp(44), dp(44)
            pos_hint: {"center_y": .5}
            on_release: app.toggle_theme()

    # ---- Visualizer card ----
    RelativeLayout:
        canvas.before:
            Color:
                rgba: app.c_vis_bg
            RoundedRectangle:
                pos: 0, 0
                size: self.size
                radius: [dp(28)]
            Color:
                rgba: app.c_outline
            Line:
                rounded_rectangle: (0, 0, self.width, self.height, dp(28))
                width: dp(1)
        Visualizer:
            id: vis
            pos: 0, 0
            size: self.parent.size
        Label:
            text: app.mode_name
            font_size: "11sp"
            bold: True
            color: app.c_primary
            size_hint: None, None
            size: self.texture_size[0] + dp(20), dp(26)
            pos: dp(14), self.parent.height - self.height - dp(14)
            canvas.before:
                Color:
                    rgba: app.c_primary_soft
                RoundedRectangle:
                    pos: self.pos
                    size: self.size
                    radius: [dp(13)]
        Label:
            text: "Tap to switch"
            font_size: "11sp"
            color: 1, 1, 1, .45
            size_hint: None, None
            size: self.texture_size
            pos: self.parent.width - self.width - dp(16), self.parent.height - self.height - dp(20)

    # ---- Mode chips ----
    ScrollView:
        size_hint_y: None
        height: dp(38)
        do_scroll_y: False
        bar_width: 0
        BoxLayout:
            id: chips
            size_hint_x: None
            width: self.minimum_width
            spacing: dp(8)

    # ---- Now playing card ----
    Card:
        orientation: "vertical"
        size_hint_y: None
        height: dp(250)
        BoxLayout:
            size_hint_y: None
            height: dp(48)
            spacing: dp(12)
            Widget:
                size_hint: None, None
                size: dp(48), dp(48)
                canvas:
                    Color:
                        rgba: app.c_primary_soft
                    RoundedRectangle:
                        pos: self.pos
                        size: self.size
                        radius: [dp(14)]
                    Color:
                        rgba: app.c_primary
                    Rectangle:
                        pos: self.x + dp(13), self.y + dp(14)
                        size: dp(4), dp(20 * (0.4 + app.beat * 0.6))
                    Rectangle:
                        pos: self.x + dp(22), self.y + dp(14)
                        size: dp(4), dp(20)
                    Rectangle:
                        pos: self.x + dp(31), self.y + dp(14)
                        size: dp(4), dp(20 * (0.6 + app.beat * 0.4))
            BoxLayout:
                orientation: "vertical"
                Label:
                    text: app.track_title
                    font_size: "16sp"
                    bold: True
                    color: app.c_on_surface
                    halign: "left"
                    valign: "middle"
                    text_size: self.size
                    shorten: True
                    shorten_from: "right"
                Label:
                    text: app.track_sub
                    font_size: "12sp"
                    color: app.c_muted
                    halign: "left"
                    valign: "middle"
                    text_size: self.size
                    shorten: True
        MSlider:
            id: seek
            min: 0
            max: 100
            size_hint_y: None
            height: dp(30)
            on_touch_down: if self.collide_point(*args[1].pos): self.dragging = True
            on_touch_up: if self.dragging: self.dragging = False; app.seek_to(self.value)
        BoxLayout:
            size_hint_y: None
            height: dp(16)
            padding: dp(16), 0
            Label:
                text: app.time_now
                font_size: "12sp"
                color: app.c_muted
                halign: "left"
                text_size: self.size
            Label:
                text: app.time_total
                font_size: "12sp"
                color: app.c_muted
                halign: "right"
                text_size: self.size
        BoxLayout:
            size_hint_y: None
            height: dp(72)
            spacing: dp(14)
            Widget:
            IconButton:
                icon: "add"
                pos_hint: {"center_y": .5}
                on_release: app.open_file()
            IconButton:
                icon: "prev"
                pos_hint: {"center_y": .5}
                on_release: app.prev_track()
            IconButton:
                icon: "pause" if app.is_playing else "play"
                filled: True
                size: dp(68), dp(68)
                pos_hint: {"center_y": .5}
                on_release: app.toggle_play()
            IconButton:
                icon: "next"
                pos_hint: {"center_y": .5}
                on_release: app.next_track()
            Widget:
                size_hint_x: None
                width: dp(52)
            Widget:
        BoxLayout:
            size_hint_y: None
            height: dp(30)
            spacing: dp(8)
            Label:
                text: "VOL"
                font_size: "11sp"
                bold: True
                color: app.c_muted
                size_hint_x: None
                width: dp(34)
            MSlider:
                min: 0
                max: 1
                value: 0.8
                on_value: app.set_volume(self.value)
"""


class Chip(Button):
    selected = BooleanProperty(False)


class IconButton(Button):
    icon = StringProperty("play")
    filled = BooleanProperty(False)


class MSlider(Slider):
    dragging = BooleanProperty(False)


class Root(BoxLayout):
    pass


class Visualizer(Widget):
    """Spectrum renderer. Draws relative to its own pos so it works inside cards."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.engine = None
        self.mode_index = 0
        self.primary = PALETTES["dark"]["primary"]
        self.secondary = PALETTES["dark"]["secondary"]
        self.particles = []
        self.idle_t = 0.0
        Clock.schedule_interval(self.update, 1 / 60)

    def on_touch_down(self, touch):
        if self.collide_point(*touch.pos):
            App.get_running_app().set_mode((self.mode_index + 1) % len(VIS_MODES))
            return True
        return super().on_touch_down(touch)

    def bands(self):
        if self.engine is not None and self.engine.playing:
            return self.engine.bands
        # gentle idle animation so the screen never looks empty
        self.idle_t += 0.02
        idx = np.arange(BANDS)
        return (0.08 + 0.06 * np.sin(idx * 0.35 + self.idle_t * 3)).astype(np.float32)

    def beat(self):
        return self.engine.beat if self.engine is not None else 0.0

    def update(self, dt):
        if self.width < 10 or self.height < 10:
            return
        self.canvas.clear()
        bands = self.bands()
        mode = VIS_MODES[self.mode_index]
        with self.canvas:
            if mode == "BARS":
                self.draw_bars(bands)
            elif mode == "MIRROR":
                self.draw_mirror(bands)
            elif mode == "RADIAL":
                self.draw_radial(bands)
            elif mode == "WAVE":
                self.draw_wave(bands)
            else:
                self.draw_particles()

    def _mix(self, t, alpha=1.0):
        a, b = self.primary, self.secondary
        return [a[i] + (b[i] - a[i]) * t for i in range(3)] + [alpha]

    def draw_bars(self, bands):
        pad = dp(18)
        w = self.width - pad * 2
        step = w / BANDS
        bw = step * 0.62
        base_y = self.y + pad
        for i, value in enumerate(bands):
            h = max(dp(3), float(value) * (self.height - pad * 3) * 0.85)
            x = self.x + pad + i * step
            Color(*self._mix(i / BANDS))
            RoundedRectangle(pos=(x, base_y), size=(bw, h), radius=[bw / 2])

    def draw_mirror(self, bands):
        pad = dp(18)
        step = (self.width - pad * 2) / BANDS
        bw = step * 0.62
        cy = self.center_y
        for i, value in enumerate(bands):
            h = max(dp(2), float(value) * self.height * 0.36)
            x = self.x + pad + i * step
            Color(*self._mix(i / BANDS))
            RoundedRectangle(pos=(x, cy), size=(bw, h), radius=[bw / 2])
            Color(*self._mix(i / BANDS, 0.45))
            RoundedRectangle(pos=(x, cy - h), size=(bw, h), radius=[bw / 2])

    def draw_radial(self, bands):
        cx, cy = self.center
        size = min(self.width, self.height)
        radius = size * 0.2 * (1 + self.beat() * 0.15)
        Color(*self._mix(0, 0.12))
        Ellipse(pos=(cx - radius, cy - radius), size=(radius * 2, radius * 2))
        for i, value in enumerate(bands):
            angle = (i / BANDS) * math.tau - math.pi / 2
            length = dp(8) + float(value) * size * 0.24
            x1 = cx + math.cos(angle) * radius
            y1 = cy + math.sin(angle) * radius
            x2 = cx + math.cos(angle) * (radius + length)
            y2 = cy + math.sin(angle) * (radius + length)
            Color(*self._mix(i / BANDS))
            Line(points=[x1, y1, x2, y2], width=dp(2), cap="round")

    def draw_wave(self, bands):
        points = []
        t = time.time()
        for px in range(0, int(self.width), 6):
            i = int((px / max(1, self.width)) * (BANDS - 1))
            y = self.center_y + math.sin(px * 0.03 + t * 3) * (0.15 + float(bands[i])) * self.height * 0.3
            points.extend([self.x + px, y])
        Color(*self._mix(1, 0.35))
        Line(points=points, width=dp(5))
        Color(*self._mix(0))
        Line(points=points, width=dp(2))

    def draw_particles(self):
        cx, cy = self.center
        while len(self.particles) < 110:
            self.particles.append({
                "angle": random.uniform(0, math.tau),
                "distance": random.uniform(10, 200),
                "speed": random.uniform(0.4, 2.0),
                "size": random.uniform(2, 6),
                "tint": random.random(),
            })
        limit = max(self.width, self.height) * 0.6
        for p in self.particles:
            p["angle"] += p["speed"] * 0.01
            p["distance"] += self.beat() * 8 + 0.5
            if p["distance"] > limit:
                p["distance"] = random.uniform(10, 60)
            x = cx + math.cos(p["angle"]) * p["distance"]
            y = cy + math.sin(p["angle"]) * p["distance"]
            if self.x < x < self.right and self.y < y < self.top:
                Color(*self._mix(p["tint"]))
                Ellipse(pos=(x, y), size=(dp(p["size"]), dp(p["size"])))


class VoocApp(App):
    dark = BooleanProperty(True)
    is_playing = BooleanProperty(False)
    beat = NumericProperty(0.0)
    mode_name = StringProperty(VIS_MODES[0])
    track_title = StringProperty("No track loaded")
    track_sub = StringProperty("Tap + to open music")
    time_now = StringProperty("00:00")
    time_total = StringProperty("00:00")

    c_bg = ListProperty(PALETTES["dark"]["bg"])
    c_surface = ListProperty(PALETTES["dark"]["surface"])
    c_surface_hi = ListProperty(PALETTES["dark"]["surface_hi"])
    c_outline = ListProperty(PALETTES["dark"]["outline"])
    c_on_surface = ListProperty(PALETTES["dark"]["on_surface"])
    c_muted = ListProperty(PALETTES["dark"]["muted"])
    c_primary = ListProperty(PALETTES["dark"]["primary"])
    c_on_primary = ListProperty(PALETTES["dark"]["on_primary"])
    c_primary_soft = ListProperty(PALETTES["dark"]["primary_soft"])
    c_vis_bg = ListProperty(PALETTES["dark"]["vis_bg"])

    def build(self):
        self.title = "Vooc"
        if ANDROID:
            request_permissions(
                [Permission.READ_EXTERNAL_STORAGE, Permission.READ_MEDIA_AUDIO]
            )
        self.engine = AudioEngine()
        Builder.load_string(KV)
        self.root_widget = Root()
        self.vis = self.root_widget.ids.vis
        self.vis.engine = self.engine
        self.chips = []
        for index, name in enumerate(VIS_MODES):
            chip = Chip(text=name.title(), selected=(index == 0))
            chip.bind(on_release=lambda _w, i=index: self.set_mode(i))
            self.root_widget.ids.chips.add_widget(chip)
            self.chips.append(chip)
        self.apply_palette()
        Clock.schedule_interval(self.update_status, 0.1)
        return self.root_widget

    # ---- theme ----
    def apply_palette(self):
        pal = PALETTES["dark" if self.dark else "light"]
        for key, value in pal.items():
            attr = "c_" + key
            if hasattr(self, attr):
                setattr(self, attr, value)
        self.vis.primary = pal["primary"] if self.dark else PALETTES["dark"]["primary"]
        self.vis.secondary = pal["secondary"]
        Window.clearcolor = pal["bg"]

    def toggle_theme(self):
        self.dark = not self.dark
        self.apply_palette()

    def set_mode(self, index):
        self.vis.mode_index = index
        self.mode_name = VIS_MODES[index]
        for i, chip in enumerate(self.chips):
            chip.selected = i == index

    # ---- transport ----
    def toggle_play(self):
        if self.engine.samples is None:
            self.open_file()
            return
        self.engine.toggle()

    def next_track(self):
        self._safe(self.engine.next_track)

    def prev_track(self):
        self._safe(self.engine.previous_track)

    def set_volume(self, value):
        self.engine.volume = value

    def seek_to(self, value):
        if self.engine.duration:
            self.engine.seek(value / 100 * self.engine.duration)

    def _safe(self, fn):
        try:
            fn()
        except Exception as error:
            self.track_sub = f"Error: {error}"

    @staticmethod
    def _fmt(seconds):
        seconds = max(0, int(seconds))
        return f"{seconds // 60:02d}:{seconds % 60:02d}"

    def update_status(self, dt):
        self.is_playing = bool(self.engine.playing)
        self.beat = float(self.engine.beat)
        if not self.engine.path:
            return
        current = self.engine.current_time()
        total = self.engine.duration
        self.track_title = os.path.splitext(os.path.basename(self.engine.path))[0]
        count = len(self.engine.playlist)
        self.track_sub = f"Track {self.engine.index + 1} of {count}  \u2022  {DECODER}"
        self.time_now = self._fmt(current)
        self.time_total = self._fmt(total)
        seek = self.root_widget.ids.seek
        if total and not seek.dragging:
            seek.value = current / total * 100

    # ---- file picker ----
    def open_file(self):
        pal = PALETTES["dark" if self.dark else "light"]
        start = "/storage/emulated/0/Music" if ANDROID else os.path.expanduser("~")
        if not os.path.isdir(start):
            start = "/storage/emulated/0" if ANDROID else os.getcwd()
        content = FileChooserListView(
            path=start, filters=["*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a"]
        )

        def select(_instance, selection):
            if selection:
                try:
                    self.engine.load(selection[0])
                    self.engine.play()
                except Exception as error:
                    self.track_sub = f"Load failed: {error}"
            popup.dismiss()

        content.bind(selection=select)
        popup = Popup(
            title="Select audio",
            content=content,
            size_hint=(0.94, 0.86),
            background="",
            background_color=pal["surface"],
            title_color=pal["primary"],
            separator_color=pal["primary"],
        )
        popup.open()


if __name__ == "__main__":
    VoocApp().run()
