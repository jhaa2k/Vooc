import os
import math
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
from kivy.graphics import Color, Rectangle, Line, Ellipse
from kivy.core.window import Window
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

THEMES = [
    ((8, 10, 18), (0, 229, 255), (255, 43, 214)),
    ((38, 38, 36), (217, 119, 87), (250, 249, 245)),
    ((6, 11, 31), (109, 140, 255), (0, 230, 168)),
    ((18, 4, 6), (255, 45, 74), (255, 176, 0)),
    ((3, 18, 11), (24, 224, 138), (0, 229, 255)),
]

VIS_MODES = ["BARS", "MIRROR", "RADIAL", "WAVE", "PARTICLES"]


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


class Visualizer(Widget):
    def __init__(self, engine, **kwargs):
        super().__init__(**kwargs)
        self.engine = engine
        self.theme_index = 0
        self.mode_index = 0
        self.particles = []

        with self.canvas:
            self.bg_color = Color(*THEMES[0][0], 1)
            self.bg = Rectangle(pos=self.pos, size=self.size)

        self.bind(pos=self.update_canvas, size=self.update_canvas)
        Clock.schedule_interval(self.update, 1 / 60)

    def update_canvas(self, *args):
        self.bg.pos = self.pos
        self.bg.size = self.size

    def theme(self):
        return THEMES[self.theme_index]

    def on_touch_down(self, touch):
        if self.collide_point(*touch.pos):
            self.mode_index = (self.mode_index + 1) % len(VIS_MODES)
            return True
        return super().on_touch_down(touch)

    def update(self, dt):
        self.canvas.clear()
        bg, accent, accent2 = self.theme()

        with self.canvas:
            Color(*bg, 1)
            Rectangle(pos=self.pos, size=self.size)

            mode = VIS_MODES[self.mode_index]

            if mode == "BARS":
                self.draw_bars(accent, accent2)
            elif mode == "MIRROR":
                self.draw_mirror(accent, accent2)
            elif mode == "RADIAL":
                self.draw_radial(accent, accent2)
            elif mode == "WAVE":
                self.draw_wave(accent, accent2)
            else:
                self.draw_particles(accent, accent2)

    def draw_bars(self, accent, accent2):
        width, height = self.size
        base_y = height * 0.18
        bar_width = width / BANDS * 0.7
        gap = width / BANDS * 0.3

        for i, value in enumerate(self.engine.bands):
            h = max(2, value * height * 0.55)
            x = i * (bar_width + gap)

            Color(
                accent[0] / 255,
                accent[1] / 255,
                accent[2] / 255,
                1,
            )
            Rectangle(pos=(x, base_y), size=(bar_width, h))

    def draw_mirror(self, accent, accent2):
        width, height = self.size
        center_y = height / 2
        bar_width = width / BANDS * 0.7
        gap = width / BANDS * 0.3

        for i, value in enumerate(self.engine.bands):
            h = max(2, value * height * 0.28)
            x = i * (bar_width + gap)

            Color(
                accent[0] / 255,
                accent[1] / 255,
                accent[2] / 255,
                1,
            )
            Rectangle(pos=(x, center_y), size=(bar_width, h))
            Rectangle(pos=(x, center_y - h), size=(bar_width, h))

    def draw_radial(self, accent, accent2):
        width, height = self.size
        center_x = width / 2
        center_y = height / 2
        radius = min(width, height) * 0.18 * (1 + self.engine.beat * 0.2)

        for i, value in enumerate(self.engine.bands):
            angle = (i / BANDS) * math.tau - math.pi / 2
            length = 20 + value * min(width, height) * 0.22

            x1 = center_x + math.cos(angle) * radius
            y1 = center_y + math.sin(angle) * radius
            x2 = center_x + math.cos(angle) * (radius + length)
            y2 = center_y + math.sin(angle) * (radius + length)

            Color(
                accent[0] / 255,
                accent[1] / 255,
                accent[2] / 255,
                1,
            )
            Line(points=[x1, y1, x2, y2], width=max(1, value * 6))

    def draw_wave(self, accent, accent2):
        width, height = self.size
        points = []
        base_y = height / 2

        for x in range(0, int(width), 8):
            i = int((x / width) * (BANDS - 1))
            value = self.engine.bands[i]
            y = base_y + math.sin(x * 0.03 + time.time() * 3) * value * height * 0.2
            points.extend([x, y])

        Color(
            accent[0] / 255,
            accent[1] / 255,
            accent[2] / 255,
            1,
        )
        Line(points=points, width=4)

    def draw_particles(self, accent, accent2):
        width, height = self.size
        center_x = width / 2
        center_y = height / 2

        while len(self.particles) < 100:
            self.particles.append(
                {
                    "angle": random.uniform(0, math.tau),
                    "distance": random.uniform(30, 250),
                    "speed": random.uniform(0.4, 2.0),
                    "size": random.uniform(2, 5),
                }
            )

        for particle in self.particles:
            particle["angle"] += particle["speed"] * 0.01
            particle["distance"] += self.engine.beat * 8 + 0.4

            if particle["distance"] > max(width, height):
                particle["distance"] = random.uniform(20, 100)

            x = center_x + math.cos(particle["angle"]) * particle["distance"]
            y = center_y + math.sin(particle["angle"]) * particle["distance"]

            Color(
                accent2[0] / 255,
                accent2[1] / 255,
                accent2[2] / 255,
                1,
            )
            Ellipse(pos=(x, y), size=(particle["size"], particle["size"]))


class VoocApp(App):
    def build(self):
        if ANDROID:
            request_permissions(
                [Permission.READ_EXTERNAL_STORAGE, Permission.READ_MEDIA_AUDIO]
            )

        self.engine = AudioEngine()
        self.visualizer = Visualizer(self.engine)

        controls = BoxLayout(
            orientation="horizontal",
            size_hint=(1, None),
            height=70,
            spacing=8,
            padding=8,
        )

        open_button = Button(text="Open")
        open_button.bind(on_release=self.open_file)

        play_button = Button(text="Play / Pause")
        play_button.bind(on_release=lambda x: self.engine.toggle())

        next_button = Button(text="Next")
        next_button.bind(on_release=lambda x: self.engine.next_track())

        prev_button = Button(text="Prev")
        prev_button.bind(on_release=lambda x: self.engine.previous_track())

        theme_button = Button(text="Theme")
        theme_button.bind(on_release=self.change_theme)

        controls.add_widget(prev_button)
        controls.add_widget(play_button)
        controls.add_widget(next_button)
        controls.add_widget(open_button)
        controls.add_widget(theme_button)

        volume = Slider(min=0, max=1, value=0.8)
        volume.bind(value=lambda instance, value: setattr(self.engine, "volume", value))

        layout = BoxLayout(orientation="vertical")
        layout.add_widget(self.visualizer)
        layout.add_widget(volume)
        layout.add_widget(controls)

        return layout

    def change_theme(self, instance):
        self.visualizer.theme_index = (self.visualizer.theme_index + 1) % len(THEMES)

    def open_file(self, instance):
        content = FileChooserListView(
            filters=["*.mp3", "*.wav", "*.flac", "*.ogg"]
        )

        def select(instance, selection):
            if selection:
                try:
                    self.engine.load(selection[0])
                    self.engine.play()
                except Exception as error:
                    print("Audio load failed:", error)
            popup.dismiss()

        content.bind(selection=select)
        popup = Popup(title="Select audio", content=content, size_hint=(0.9, 0.9))
        popup.open()


if __name__ == "__main__":
    VoocApp().run()
