from vooc.android_boot import get_window
Window = get_window()  # waits for the Android surface; see vooc/android_boot.py
from kivy.app import App
from kivy.clock import Clock
from kivy.core.audio import SoundLoader
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget
from pathlib import Path
import json, os, random, time
_STARTUP_IMPORT_ERROR = None
try:
    from vooc import m3
    from vooc.m3 import T
    from vooc.state import ProjectState, THEMES, AUDIO_DEFAULTS, VISUALIZER_DEFAULTS, EQ_PRESETS
    from vooc.visualizer import Spectrum, STYLES, PALETTES, palette_stops
    from vooc.spectrum_source import SpectrumLoader
    from vooc.android_audio import AndroidMediaPlayerSource
    from vooc.android_media import choose_audio, choose_image
    from vooc.property_editor import AdvancedProperties
except BaseException:
    import traceback
    _STARTUP_IMPORT_ERROR = traceback.format_exc()


def system_insets():
    """(top, bottom) pixels hidden behind the status / navigation bars.

    Android 15+ draws apps edge-to-edge for targetSdk 35, so the SDL surface
    extends under the system bars. Older versions lay the window out below
    them, so nothing needs to be reserved there."""
    try:
        from jnius import autoclass
        if autoclass("android.os.Build$VERSION").SDK_INT < 35:
            return 0, 0
        act = autoclass("org.kivy.android.PythonActivity").mActivity
        ins = act.getWindow().getDecorView().getRootWindowInsets()
        if ins is None:
            return None
        return int(ins.getSystemWindowInsetTop()), int(ins.getSystemWindowInsetBottom())
    except Exception:
        return 0, 0


def fmt_time(sec):
    sec = max(0, int(sec or 0))
    return "%d:%02d" % (sec // 60, sec % 60)


class Scroll(ScrollView):
    """Vertical ScrollView that hands touches to vertical sliders immediately
    (otherwise the page would scroll instead of moving an EQ band)."""

    def __init__(self, **kw):
        kw.setdefault("do_scroll_x", False)
        kw.setdefault("bar_width", dp(3))
        kw.setdefault("scroll_type", ["bars", "content"])
        super().__init__(**kw)

    def on_touch_down(self, touch):
        if self.collide_point(*touch.pos):
            wx, wy = self.to_window(*touch.pos)
            for w in self.walk(restrict=True):
                if getattr(w, "vertical", False) and isinstance(w, m3.Slider3) and \
                        w.collide_point(*w.to_widget(wx, wy)):
                    return Widget.on_touch_down(self, touch)
        return super().on_touch_down(touch)


NAV = [("library", "note", "Library"), ("visual", "bars", "Visual"), ("sound", "tune", "Sound"),
       ("layers", "layers", "Layers"), ("studio", "studio", "Studio")]


class VoocRoot(FloatLayout):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.s = ProjectState()
        self._autoload()
        T.dark = self.s.scene.get("ui_theme", "dark") != "light"
        self.page = "library"
        self.studio_tab = "Properties"
        self.track = ""
        self.playing = False
        self.duration = 0.0
        self._pos = 0.0
        self._pos_stamp = time.monotonic()
        self._end_handled = False
        self.sound = None
        self.native = None
        if AndroidMediaPlayerSource.is_available():
            self.native = AndroidMediaPlayerSource(lambda *a: None, self._native_error, self._native_completed,
                                                   self._native_focus_changed)
            self.native.configure_fx(self.s.audio, self.s.eq_freqs)
        self.analysis = {"state": "idle", "progress": 0.0}
        self.loader = SpectrumLoader(self._spectrum_ready, self._spectrum_progress,
                                     scheduler=lambda fn: Clock.schedule_once(lambda _dt: fn(), 0))
        self._save_trigger = Clock.create_trigger(lambda _dt: self.autosave(), 1.0)
        self.insets = system_insets() or (0, 0)
        self.build_ui()
        if system_insets() is None:
            Clock.schedule_once(self._retry_insets, 0.5)
        Clock.schedule_interval(self.tick, 1 / 15.0)
        if Window is not None:
            Window.bind(on_keyboard=self._on_key)

    def _retry_insets(self, _dt, tries=[0]):
        ins = system_insets()
        if ins is None:
            tries[0] += 1
            if tries[0] < 10:
                Clock.schedule_once(self._retry_insets, 0.5)
            return
        if tuple(ins) != tuple(self.insets):
            self.insets = ins
            self.build_ui()

    # ================================================================ UI
    def build_ui(self):
        if getattr(self, "preview", None) is not None:
            try:
                self.preview._ev.cancel()
            except Exception:
                pass
        self.clear_widgets()
        if Window is not None:
            Window.clearcolor = T.bg
        top, bottom = self.insets
        main = m3.Box(orientation="vertical", bg=T.bg, padding=(dp(12), dp(6) + top, dp(12), 0), spacing=dp(10))
        main.add_widget(self._top_bar())
        main.add_widget(self._visual_card())
        main.add_widget(self._player_card())
        self.body = BoxLayout()
        main.add_widget(self.body)
        outer = BoxLayout(orientation="vertical")
        outer.add_widget(main)
        self.nav = m3.NavBar(NAV, self.show, self.page)
        self.nav.height += bottom
        self.nav.padding = (dp(4), dp(6), dp(4), dp(6) + bottom)
        outer.add_widget(self.nav)
        self.add_widget(outer)
        self.show(self.page)
        self._update_player_ui()

    def _top_bar(self):
        bar = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(4))
        logo = m3.Box(bg=T.primary, radius=dp(12), size_hint=(None, None), size=(dp(36), dp(36)), padding=dp(8))
        logo.add_widget(m3.Icon(name="bars", tint=T.on_primary))
        holder = BoxLayout(size_hint_x=None, width=dp(44), padding=(0, dp(6)))
        holder.add_widget(logo)
        bar.add_widget(holder)
        col = BoxLayout(orientation="vertical", padding=(dp(4), dp(4)))
        col.add_widget(m3.lbl("Vooc", 20, bold=True))
        col.add_widget(m3.lbl("Audio visualizer studio", 11, T.on_var))
        bar.add_widget(col)
        bar.add_widget(m3.IconBtn("theme", self.toggle_theme))
        bar.add_widget(m3.IconBtn("folder", self.load))
        bar.add_widget(m3.IconBtn("save", self.save))
        return bar

    def _visual_card(self):
        frame = m3.Box(bg=T.surface1, radius=dp(24), padding=dp(4), size_hint_y=None)
        def fit(*_):
            frame.height = max(dp(130), min(dp(340), (self.height or 800) * 0.28))
        if getattr(self, "_fit_visual", None) is not None:
            self.unbind(height=self._fit_visual)
        self._fit_visual = fit
        self.bind(height=fit)
        fit()
        self.visual_frame = frame
        stage = FloatLayout()
        self.preview = Spectrum(self.s, clock_source=self.clock, size_hint=(1, 1), pos_hint={"x": 0, "y": 0})
        if self.loader is not None and self.track and self.track in self.loader._cache:
            self.preview.set_timeline(self.loader._cache[self.track])
        stage.add_widget(self.preview)
        # mask the corners of the live canvas so it matches the card radius
        self.status = m3.Box(bg=T.a("scrim", .55), radius=dp(12), size_hint=(None, None), height=dp(26), width=dp(96),
                             pos_hint={"x": .02, "top": .96}, padding=(dp(10), 0), spacing=dp(6))
        self.status_dot = Widget(size_hint=(None, 1), width=dp(8))
        from kivy.graphics import Color, Ellipse
        with self.status_dot.canvas:
            self._dot_c = Color(*T.primary)
            self._dot = Ellipse(size=(dp(8), dp(8)))
        self.status_dot.bind(pos=lambda w, *_: setattr(self._dot, "pos", (w.x, w.y + w.height / 2 - dp(4))))
        self.status.add_widget(self.status_dot)
        self.status_text = m3.lbl("Idle", 11, (1, 1, 1, .92), bold=True)
        self.status.add_widget(self.status_text)
        stage.add_widget(self.status)
        self.style_chip = m3.Tap(on_tap=self.cycle_style, bg=T.a("scrim", .55), radius=dp(13), size_hint=(None, None),
                                 height=dp(26), width=dp(96), pos_hint={"right": .98, "top": .96}, padding=(dp(10), 0), spacing=dp(4))
        self.style_chip.add_widget(m3.Icon(name="sparkle", tint=(.78, 1, .2, 1), size_hint_x=None, width=dp(14)))
        self.style_text = m3.lbl(self.preview.style(), 11, (1, 1, 1, .92), bold=True)
        self.style_chip.add_widget(self.style_text)
        stage.add_widget(self.style_chip)
        frame.add_widget(stage)
        return frame

    def _player_card(self):
        c = m3.Box(orientation="vertical", bg=T.surface1, radius=dp(24), padding=(dp(14), dp(10), dp(14), dp(6)),
                   size_hint_y=None, height=dp(156), spacing=0)
        head = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(10))
        art = m3.Box(bg=T.primary_container, radius=dp(12), size_hint=(None, None), size=(dp(40), dp(40)), padding=dp(10))
        art.add_widget(m3.Icon(name="note", tint=T.on_primary_container))
        head.add_widget(art)
        col = BoxLayout(orientation="vertical")
        self.title_lbl = m3.lbl("No track loaded", 15, bold=True)
        self.sub_lbl = m3.lbl("Import an audio file to start", 11, T.on_var)
        col.add_widget(self.title_lbl)
        col.add_widget(self.sub_lbl)
        head.add_widget(col)
        c.add_widget(head)
        seek_row = BoxLayout(size_hint_y=None, height=dp(34), spacing=dp(4))
        self.pos_lbl = m3.lbl("0:00", 11, T.on_var, size_hint_x=None, width=dp(38))
        self.seek = m3.Slider3(0, 1, 0, on_change=self._seek_preview, on_commit=self._seek_commit, fmt=fmt_time)
        self.dur_lbl = m3.lbl("0:00", 11, T.on_var, halign="right", size_hint_x=None, width=dp(38))
        seek_row.add_widget(self.pos_lbl)
        seek_row.add_widget(self.seek)
        seek_row.add_widget(self.dur_lbl)
        c.add_widget(seek_row)
        ctl = BoxLayout(size_hint_y=None, height=dp(64), spacing=dp(6))
        self.shuffle_btn = m3.IconBtn("shuffle", self.toggle_shuffle)
        self.repeat_btn = m3.IconBtn("repeat", self.cycle_repeat)
        self.play_btn = m3.IconBtn("play", self.toggle, kind="filled", size=dp(60))
        ctl.add_widget(Widget())
        for w in (self.shuffle_btn, m3.IconBtn("prev", self.previous, size=dp(48)), self.play_btn,
                  m3.IconBtn("next", self.next, size=dp(48)), self.repeat_btn):
            holder = BoxLayout(size_hint_x=None, width=w.width + dp(4), padding=(dp(2), (dp(64) - w.height) / 2))
            holder.add_widget(w)
            ctl.add_widget(holder)
        ctl.add_widget(Widget())
        c.add_widget(ctl)
        return c

    def show(self, page):
        self.page = page
        self.body.clear_widgets()
        Clock.schedule_once(self._dump_targets, 0.8)
        builder = getattr(self, "_page_" + page)
        if page == "studio":
            self.body.add_widget(builder())
            return
        scroll = Scroll()
        col = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(10), padding=(0, 0, 0, dp(16)))
        col.bind(minimum_height=col.setter("height"))
        builder(col)
        scroll.add_widget(col)
        self.body.add_widget(scroll)

    def _dump_targets(self, *_):
        """Emulator smoke test hook: publish key button positions (fractions,
        y from top) when files/smoke_targets exists. No-op for users."""
        try:
            app = App.get_running_app()
            base = Path(app.user_data_dir)
            if not (base / "smoke_targets").exists() or Window is None:
                return
            W, H = float(Window.width), float(Window.height)
            out = []

            def add(name, w):
                if w is None or w.get_root_window() is None:
                    return
                x, y = w.to_window(w.x + w.width / 2.0, w.y + w.height / 2.0)
                out.append("%s %.4f %.4f" % (name, x / W, 1 - y / H))
            add("play", self.play_btn)
            add("style", self.style_chip)
            for k, it in self.nav.items.items():
                add("nav_" + k, it)
            add("import", getattr(self, "import_btn", None))
            pe = getattr(self, "property_editor", None)
            if pe is not None and pe.get_root_window() is not None and getattr(pe, "first_tile", None) is not None:
                add("prop_tile", pe.first_tile)
            (base / "ui_targets.txt").write_text("\n".join(out) + "\n")
        except Exception as exc:
            print("Vooc targets: %s" % exc)

    # ---------------------------------------------------------------- library
    def _page_library(self, col):
        actions = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(10))
        self.import_btn = m3.Button3("Import audio", self.import_audio, kind="filled", icon="plus", height=dp(48))
        actions.add_widget(self.import_btn)
        actions.add_widget(m3.Button3("Clear", self.clear_playlist, kind="outlined", icon="delete", height=dp(48),
                                      size_hint_x=.6))
        col.add_widget(actions)
        col.add_widget(m3.section("Playlist", "%d tracks" % len(self.s.playlist)))
        if not self.s.playlist:
            empty = m3.card(bg=T.surface1)
            empty.add_widget(m3.lbl("Your imported tracks will appear here.", 13, T.on_var, size_hint_y=None, height=dp(24)))
            empty.add_widget(m3.lbl("MP3, M4A, AAC, FLAC, OGG and WAV are supported.", 11, T.on_var, size_hint_y=None, height=dp(20)))
            col.add_widget(empty)
        for i, path in enumerate(self.s.playlist):
            on = path == self.track
            tile = m3.Tap(on_tap=lambda p=path: self.load_track(p, autoplay=True), size_hint_y=None, height=dp(60),
                          radius=dp(18), bg=T.secondary_container if on else T.surface1, padding=(dp(12), 0), spacing=dp(12))
            badge = m3.Box(bg=T.primary if on else T.surface3, radius=dp(12), size_hint=(None, None), size=(dp(40), dp(40)), padding=dp(10))
            badge.add_widget(m3.Icon(name=("bars" if on and self.playing else "note"), tint=T.on_primary if on else T.on_var))
            wrap = BoxLayout(size_hint_x=None, width=dp(40), padding=(0, dp(10)))
            wrap.add_widget(badge)
            tile.add_widget(wrap)
            info = BoxLayout(orientation="vertical", padding=(0, dp(10)))
            info.add_widget(m3.lbl(Path(path).stem, 14, T.on_secondary_container if on else T.on_surface, bold=on))
            info.add_widget(m3.lbl(Path(path).suffix.upper().lstrip(".") + ("  -  now playing" if on else ""), 11, T.on_var))
            tile.add_widget(info)
            tile.add_widget(m3.IconBtn("close", lambda p=path: self.remove_track(p), size=dp(40)))
            col.add_widget(tile)

    # ---------------------------------------------------------------- visual
    def _page_visual(self, col):
        v = self.s.visualizer
        col.add_widget(m3.section("Style"))
        col.add_widget(m3.ChipGroup(STYLES, self.preview.style(), lambda o: self.set_vis("style", o)))
        col.add_widget(m3.section("Palette"))
        sw = {}
        for name in PALETTES:
            stops = palette_stops(name)
            sw[name] = (stops[0], stops[-1])
        col.add_widget(m3.ChipGroup(list(PALETTES), self._palette_name(), lambda o: self.set_vis("palette", o), swatches=sw))
        card = m3.card(spacing=dp(2))
        rows = [("Bars", "bar_count", 16, 128, 4, lambda x: "%d" % x),
                ("Sensitivity", "sensitivity", .2, 3.0, 0, lambda x: "%.2fx" % x),
                ("Smoothing", "smoothing", 0, .95, 0, lambda x: "%d%%" % round(x * 100)),
                ("Height", "height", .3, 2.0, 0, lambda x: "%.2fx" % x),
                ("Bar gap", "gap", 0, 1.0, 0, lambda x: "%d%%" % round(x * 100)),
                ("Glow", "glow", 0, 1.0, 0, lambda x: "%d%%" % round(x * 100)),
                ("Peak fall speed", "peak_fall", .2, 3.0, 0, lambda x: "%.2fx" % x)]
        for title, key, lo, hi, step, fmt in rows:
            card.add_widget(m3.SliderRow(title, lo, hi, float(v.get(key, VISUALIZER_DEFAULTS[key])),
                                         lambda x, k=key, st=step: self.set_vis(k, int(round(x)) if st else round(x, 3)),
                                         step=step, fmt=fmt, default=VISUALIZER_DEFAULTS[key]))
        col.add_widget(m3.section("Motion", "tap a value to reset"))
        col.add_widget(card)
        sw_card = m3.card(spacing=0, padding=(dp(4), dp(4)))
        for title, key, sub in [("Peak caps", "peaks", "Falling peak markers"), ("Beat pulse", "beat_pulse", "Flash and bounce on kicks"),
                                ("Particles", "particles", "Sparks fly on every beat"), ("Reflection", "reflection", "Mirror glow under bars"),
                                ("Auto spin", "spin", "Rotate radial and orbit styles")]:
            sw_card.add_widget(m3.switch_row(title, bool(v.get(key, VISUALIZER_DEFAULTS[key])),
                                             lambda on, k=key: self.set_vis(k, bool(on)), sub))
        col.add_widget(m3.section("Effects"))
        col.add_widget(sw_card)
        col.add_widget(m3.section("Background"))
        col.add_widget(m3.ChipGroup(list(THEMES), self.s.scene.get("theme", "Acid Night"), self._set_bg_theme,
                                    swatches={k: (m3._hex(c), m3._hex(c)) for k, c in THEMES.items()}))
        col.add_widget(m3.Button3("Reset visualizer", self.reset_visualizer, kind="outlined", icon="reset"))

    def _palette_name(self):
        p = self.s.visualizer.get("palette", "Acid")
        from vooc.visualizer import _PALETTE_ALIASES
        return _PALETTE_ALIASES.get(p, p)

    def set_vis(self, key, value):
        self.s.visualizer[key] = value
        self.preview.refresh()
        if key == "style":
            self.style_text.text = self.preview.style()
        self._save_trigger()

    def _set_bg_theme(self, name):
        self.s.scene["theme"] = name
        self.preview.refresh()
        self._save_trigger()

    def cycle_style(self):
        cur = self.preview.style()
        nxt = STYLES[(STYLES.index(cur) + 1) % len(STYLES)]
        self.set_vis("style", nxt)
        if self.page == "visual":
            self.show("visual")

    def reset_visualizer(self):
        self.s.visualizer = dict(VISUALIZER_DEFAULTS)
        self.set_vis("style", "Bars")
        self.show("visual")

    # ---------------------------------------------------------------- sound
    def _page_sound(self, col):
        a = self.s.audio
        hint = "live on Android player" if self.native else "desktop: volume only"
        col.add_widget(m3.section("Output", hint))
        card = m3.card(spacing=dp(2))
        pct = lambda x: "%d%%" % round(x)
        db = lambda x: "%+.1f dB" % x
        for title, key, lo, hi, step, fmt, bip, icon in [
                ("Volume", "volume", 0, 150, 1, pct, False, None),
                ("Balance", "balance", -100, 100, 1, lambda x: "C" if round(x) == 0 else ("L%d" % -round(x) if x < 0 else "R%d" % round(x)), True, None),
                ("Pre-amp", "pre", -12, 12, .5, db, True, None),
                ("Bass boost", "bass", 0, 12, .5, db, False, None),
                ("3D surround", "virtualizer", 0, 100, 1, pct, False, None),
                ("Speed", "speed", 50, 200, 1, pct, False, None),
                ("Pitch", "pitch", 50, 200, 1, pct, False, None)]:
            val = max(lo, min(hi, float(a.get(key, AUDIO_DEFAULTS[key]))))
            card.add_widget(m3.SliderRow(title, lo, hi, val, lambda x, k=key: self.set_audio(k, x), step=step, fmt=fmt,
                                         bipolar=bip, default=AUDIO_DEFAULTS[key]))
        col.add_widget(card)
        col.add_widget(m3.section("Equalizer", "10 bands, -12..+12 dB"))
        presets = list(EQ_PRESETS) + [n for n in self.s.presets if n not in EQ_PRESETS]
        col.add_widget(m3.ChipGroup(presets, self._eq_preset_name(), self.apply_eq_preset))
        eq = m3.card(padding=(dp(6), dp(10)), spacing=dp(4))
        grid = BoxLayout(size_hint_y=None, height=dp(210), spacing=0)
        self.eq_sliders = []
        for i, f in enumerate(self.s.eq_freqs):
            c = BoxLayout(orientation="vertical", spacing=dp(2))
            key = "eq_" + str(i)
            value_lbl = m3.lbl("%+.0f" % a.get(key, 0), 11, T.primary, bold=True, halign="center", size_hint_y=None, height=dp(18))

            def changed(x, k=key, l=value_lbl):
                l.text = "%+.0f" % x
                self.set_audio(k, x)
            sl = m3.Slider3(-12, 12, float(a.get(key, 0)), 0.5, on_change=changed, vertical=True, bipolar=True,
                            fmt=lambda x: "%+.1f dB" % x, size_hint_x=1, width=dp(30))
            sl.value_lbl = value_lbl
            self.eq_sliders.append(sl)
            c.add_widget(value_lbl)
            c.add_widget(sl)
            c.add_widget(m3.lbl(("%dk" % (f // 1000)) if f >= 1000 else str(f), 10, T.on_var, halign="center", size_hint_y=None, height=dp(16)))
            grid.add_widget(c)
        eq.add_widget(grid)
        col.add_widget(eq)
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(10))
        row.add_widget(m3.Button3("Save preset", self.save_preset, kind="tonal", icon="save"))
        row.add_widget(m3.Button3("Reset sound", self.reset_audio, kind="outlined", icon="reset"))
        col.add_widget(row)

    def _eq_preset_name(self):
        cur = [round(float(self.s.audio.get("eq_%d" % i, 0)), 1) for i in range(10)]
        for name, gains in EQ_PRESETS.items():
            if [float(g) for g in gains] == cur:
                return name
        return ""

    def set_audio(self, key, value):
        value = round(float(value), 2)
        self.s.audio[key] = value
        if self.native is not None:
            self.native.set_fx(key, value)
        elif self.sound is not None and key == "volume":
            try:
                self.sound.volume = max(0.0, min(1.0, value / 100.0))
            except Exception:
                pass
        self._save_trigger()

    def apply_eq_preset(self, name):
        if name in EQ_PRESETS:
            for i, g in enumerate(EQ_PRESETS[name]):
                self.set_audio("eq_%d" % i, g)
        elif name in self.s.presets:
            for k, v in self.s.presets[name].items():
                if k in AUDIO_DEFAULTS or k.startswith("eq_"):
                    self.set_audio(k, v)
            self.show("sound")
            return
        for i, sl in enumerate(getattr(self, "eq_sliders", [])):
            sl.value = float(self.s.audio.get("eq_%d" % i, 0))
            sl.value_lbl.text = "%+.0f" % sl.value

    def save_preset(self):
        name = "My preset %d" % (len([n for n in self.s.presets if n.startswith("My preset")]) + 1)
        self.s.presets[name] = dict(self.s.audio)
        self.toast("Saved " + name)
        self.show("sound")

    def reset_audio(self):
        for k, v in AUDIO_DEFAULTS.items():
            self.set_audio(k, v)
        for i in range(10):
            self.set_audio("eq_%d" % i, 0)
        self.show("sound")

    # ---------------------------------------------------------------- layers
    def _page_layers(self, col):
        add = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        add.add_widget(m3.Button3("Text", lambda: self.add_layer("Text"), kind="tonal", icon="text"))
        add.add_widget(m3.Button3("Image", self.add_image_layer, kind="tonal", icon="image"))
        add.add_widget(m3.Button3("Spectrum", lambda: self.add_layer("Spectrum"), kind="tonal", icon="bars"))
        col.add_widget(add)
        col.add_widget(m3.section("Layer stack", "top draws last"))
        if not self.s.layers:
            col.add_widget(m3.lbl("No layers", 13, T.on_var, size_hint_y=None, height=dp(30)))
            return
        self.s.selected = max(0, min(self.s.selected, len(self.s.layers) - 1))
        for i, l in enumerate(self.s.layers):
            sel = i == self.s.selected
            tile = m3.Tap(on_tap=lambda ix=i: self.select_layer(ix), size_hint_y=None, height=dp(54), radius=dp(16),
                          bg=T.secondary_container if sel else T.surface1, padding=(dp(12), 0), spacing=dp(8))
            icon = {"spectrum": "bars", "text": "text", "image": "image"}.get(l.get("type"), "layers")
            tile.add_widget(m3.Icon(name=icon, tint=T.on_secondary_container if sel else T.on_var, size_hint_x=None, width=dp(22)))
            tile.add_widget(m3.lbl(l.get("name", "Layer"), 14, T.on_secondary_container if sel else T.on_surface, bold=sel))
            eye = m3.IconBtn("eye" if l.get("visible", True) else "eye_off", lambda ix=i: self.toggle_visible(ix), size=dp(40))
            eye.active = l.get("visible", True)
            eye._colors()
            lock = m3.IconBtn("lock" if l.get("locked") else "unlock", lambda ix=i: self.toggle_lock(ix), size=dp(40))
            tile.add_widget(eye)
            tile.add_widget(lock)
            col.add_widget(tile)
        l = self.s.layers[self.s.selected]
        col.add_widget(m3.section("Inspector", "locked" if l.get("locked") else "live"))
        card = m3.card(spacing=dp(4))
        card.add_widget(m3.text_field(l.get("name", ""), lambda v: self.set_layer_value("name", v, refresh=True), "Layer name / text"))
        if l.get("type") == "image":
            card.add_widget(m3.Button3("Replace image", self.import_layer_image, kind="tonal", icon="image"))
        for title, key, lo, hi, step, d, fmt in [("Position X", "x", 0, 100, 1, 50, lambda x: "%d%%" % x),
                                                 ("Position Y", "y", 0, 100, 1, 50, lambda x: "%d%%" % x),
                                                 ("Scale", "scale", .25, 4.0, 0, 1.0, lambda x: "%.2fx" % x),
                                                 ("Opacity", "opacity", 0, 1.0, 0, 1.0, lambda x: "%d%%" % round(x * 100)),
                                                 ("Rotation", "rotation", -180, 180, 1, 0, lambda x: "%d deg" % x)]:
            card.add_widget(m3.SliderRow(title, lo, hi, float(l.get(key, d)), lambda x, k=key: self.set_layer_value(k, round(x, 3)),
                                         step=step, fmt=fmt, default=d, bipolar=(key == "rotation")))
        col.add_widget(card)
        tools = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(4))
        tools.add_widget(Widget())
        for icon, fn in [("up", lambda: self.move_layer(-1)), ("down", lambda: self.move_layer(1)),
                         ("copy", self.duplicate), ("delete", self.delete_layer)]:
            tools.add_widget(m3.IconBtn(icon, fn, kind="tonal"))
        tools.add_widget(Widget())
        col.add_widget(tools)

    # ---------------------------------------------------------------- studio
    def _page_studio(self):
        box = BoxLayout(orientation="vertical", spacing=dp(10))
        box.add_widget(m3.Segmented(["Properties", "Scene", "Export"], self.studio_tab, self._studio_tab))
        self.studio_body = BoxLayout()
        box.add_widget(self.studio_body)
        self._studio_tab(self.studio_tab)
        return box

    def _studio_tab(self, tab):
        self.studio_tab = tab
        self.studio_body.clear_widgets()
        if tab == "Properties":
            self.property_editor = AdvancedProperties(self.s, on_change=self.preview.refresh, on_save=self._save_trigger)
            self.studio_body.add_widget(self.property_editor)
            Clock.schedule_once(self._dump_targets, 0.8)
            return
        scroll = Scroll()
        col = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(8), padding=(0, 0, 0, dp(16)))
        col.bind(minimum_height=col.setter("height"))

        def pick(title, key, options):
            col.add_widget(m3.lbl(title, 12, T.on_var, size_hint_y=None, height=dp(20)))
            col.add_widget(m3.ChipGroup(options, self.s.scene.get(key, options[0]),
                                        lambda o, k=key: (self.s.scene.__setitem__(k, o), self.preview.refresh(), self._save_trigger())))
        if tab == "Scene":
            col.add_widget(m3.section("Canvas"))
            pick("Aspect ratio", "ratio", ["16:9", "9:16", "1:1", "4:5", "4:3"])
            pick("Resolution", "resolution", ["720p", "1080p", "1440p", "2160p"])
            pick("Frame rate", "fps", ["24", "30", "60"])
            col.add_widget(m3.section("Background theme"))
            col.add_widget(m3.SelectField("Background theme", list(THEMES), self.s.scene.get("theme", "Acid Night"), self._set_bg_theme))
        else:
            col.add_widget(m3.section("Video export"))
            pick("Format", "format", ["MP4", "WEBM", "GIF"])
            pick("Resolution", "resolution", ["720p", "1080p", "1440p", "2160p"])
            pick("FPS", "fps", ["24", "30", "60"])
            pick("Quality", "quality", ["Draft", "Standard", "High", "Maximum"])
            col.add_widget(Widget(size_hint_y=None, height=dp(6)))
            col.add_widget(m3.Button3("Render video", lambda: self.toast("Video encoder is not connected yet"), kind="filled", icon="export"))
            col.add_widget(m3.Button3("Save project JSON", self.save, kind="outlined", icon="save"))
        scroll.add_widget(col)
        self.studio_body.add_widget(scroll)

    # ================================================================ theme / misc
    def toggle_theme(self):
        T.dark = not T.dark
        self.s.scene["ui_theme"] = "dark" if T.dark else "light"
        self._save_trigger()
        self.build_ui()

    def toast(self, text):
        m3.Snackbar.show(text, self)

    def _on_key(self, _win, key, *_):
        if key == 27:
            if self.page == "studio" and getattr(self, "property_editor", None) is not None and \
                    self.studio_tab == "Properties" and self.property_editor.category:
                self.property_editor._home()
                return True
            if self.page != "library":
                self.nav.select("library")
                return True
        return False

    # ================================================================ audio
    def clock(self):
        """(position seconds, playing) for the visualizer, interpolated per frame."""
        if not self.playing:
            return self._pos, False
        speed = float(self.s.audio.get("speed", 100)) / 100.0 if self.native else 1.0
        return self._pos + (time.monotonic() - self._pos_stamp) * speed, True

    def _read_pos(self):
        try:
            if self.native is not None and self.track:
                return self.native.position_ms() / 1000.0
            if self.sound is not None:
                return float(self.sound.get_pos() or 0)
        except Exception:
            pass
        return self._pos

    def import_audio(self):
        choose_audio(lambda p: self.load_track(p, autoplay=True))

    def load_track(self, path, autoplay=False):
        if not path:
            return
        try:
            self.playing = False
            if self.native is not None:
                if not self.native.load(path, autoplay=False):
                    return
                self.native.configure_fx(self.s.audio, self.s.eq_freqs)
                self.duration = self.native.duration_ms() / 1000.0
            else:
                if self.sound:
                    self.sound.stop()
                self.sound = SoundLoader.load(path)
                if not self.sound:
                    self.toast("This audio format is not supported")
                    return
                self.sound.volume = max(0.0, min(1.0, float(self.s.audio.get("volume", 100)) / 100.0))
                self.duration = float(self.sound.length or 0)
            self.track = path
            self.s.current_track = path
            if path not in self.s.playlist:
                self.s.playlist.append(path)
            self._pos, self._pos_stamp, self._end_handled = 0.0, time.monotonic(), False
            self.preview.set_timeline(None)
            self.analysis = {"state": "analyzing", "progress": 0.0}
            self.loader.load(path)
            if autoplay:
                self.toggle()
            self._update_player_ui()
            if self.page == "library":
                self.show("library")
            self._save_trigger()
        except Exception as e:
            self.toast(str(e))

    def _spectrum_ready(self, path, timeline):
        if path != self.track:
            return
        if timeline is None:
            self.analysis = {"state": "error", "progress": 0.0}
            self.toast("Could not analyse this track: " + (self.loader.error or "unsupported")[:60])
        else:
            self.analysis = {"state": "ready", "progress": 1.0}
            self.preview.set_timeline(timeline)
        self._update_status()

    def _spectrum_progress(self, p):
        if self.analysis.get("state") == "analyzing":
            self.analysis["progress"] = p
            self._update_status()

    def toggle(self):
        if not self.track:
            self.import_audio()
            return
        if self.playing:
            self._pos = self._read_pos()
            if self.native is not None:
                self.native.pause()
            elif self.sound:
                self.sound.stop()
            self.playing = False
        else:
            if self.native is not None:
                self.playing = bool(self.native.play())
            elif self.sound:
                self.sound.play()
                if self._pos > 0.5:
                    try:
                        self.sound.seek(self._pos)
                    except Exception:
                        pass
                self.playing = True
            self._pos_stamp = time.monotonic()
            self._end_handled = False
        self._update_player_ui()

    def stop(self):
        if self.native is not None and self.track:
            self.native.stop()
        if self.sound:
            self.sound.stop()
        self.playing = False
        self._pos = 0.0
        self._update_player_ui()

    def _seek_preview(self, v):
        self.pos_lbl.text = fmt_time(v)

    def _seek_commit(self, v):
        if not self.track:
            return
        if self.native is not None:
            self.native.seek(float(v) * 1000.0)
        elif self.sound:
            try:
                self.sound.seek(float(v))
            except Exception:
                pass
        self._pos, self._pos_stamp, self._end_handled = float(v), time.monotonic(), False

    def _order(self):
        return list(self.s.playlist)

    def previous(self):
        if not self.s.playlist:
            return
        if self._read_pos() > 3:
            self._seek_commit(0)
            return
        try:
            i = self.s.playlist.index(self.track)
        except ValueError:
            i = 0
        self.load_track(self.s.playlist[(i - 1) % len(self.s.playlist)], autoplay=True)

    def next(self, auto=False):
        pl = self.s.playlist
        if not pl:
            return
        if auto and self.s.repeat == "one":
            self.load_track(self.track, autoplay=True)
            return
        try:
            i = pl.index(self.track)
        except ValueError:
            i = -1
        if self.s.shuffle and len(pl) > 1:
            choices = [p for p in pl if p != self.track]
            self.load_track(random.choice(choices), autoplay=True)
        elif i + 1 < len(pl):
            self.load_track(pl[i + 1], autoplay=True)
        elif self.s.repeat == "all" or not auto:
            self.load_track(pl[0], autoplay=True)
        else:
            self.stop()

    def toggle_shuffle(self):
        self.s.shuffle = not self.s.shuffle
        self._update_player_ui()
        self.toast("Shuffle " + ("on" if self.s.shuffle else "off"))

    def cycle_repeat(self):
        self.s.repeat = {"off": "all", "all": "one", "one": "off"}.get(self.s.repeat, "off")
        self._update_player_ui()
        self.toast({"off": "Repeat off", "all": "Repeat all", "one": "Repeat one"}[self.s.repeat])

    def remove_track(self, path):
        if path in self.s.playlist:
            self.s.playlist.remove(path)
        if path == self.track:
            self.stop()
        self.show("library")
        self._save_trigger()

    def clear_playlist(self):
        self.stop()
        self.s.playlist = []
        self.show("library")
        self._save_trigger()

    def _native_error(self, message):
        Clock.schedule_once(lambda _dt: self.toast(message), 0)

    def _native_completed(self):
        Clock.schedule_once(lambda _dt: self._track_finished(), 0)

    def _native_focus_changed(self, state):
        def apply(_dt):
            if state in ("interrupted", "focus_lost", "paused", "stopped", "denied"):
                self._pos = self._read_pos()
                self.playing = False
            elif state in ("playing", "resumed"):
                self.playing = True
                self._pos_stamp = time.monotonic()
            self._update_player_ui()
        Clock.schedule_once(apply, 0)

    def _track_finished(self):
        if self._end_handled:
            return
        self._end_handled = True
        self.playing = False
        self.next(auto=True)

    def tick(self, dt):
        if self.playing:
            self._pos = self._read_pos()
            self._pos_stamp = time.monotonic()
            if self.duration <= 0:
                try:
                    self.duration = (self.native.duration_ms() / 1000.0) if self.native else float(self.sound.length or 0)
                except Exception:
                    pass
            if self.native is None and self.sound is not None and self.duration > 0 and \
                    (self._pos >= self.duration - .25 or self.sound.state == "stop"):
                self._track_finished()
        if not self.seek.dragging:
            self.seek.hi = max(1.0, self.duration)
            self.seek.set_value(self._pos)
            self.pos_lbl.text = fmt_time(self._pos)
            self.dur_lbl.text = fmt_time(self.duration)
        self._update_status()

    def _update_status(self):
        st = self.analysis.get("state")
        if st == "analyzing":
            text, color = "Analyzing %d%%" % round(self.analysis.get("progress", 0) * 100), (1, .8, .2, 1)
        elif st == "error":
            text, color = "No spectrum", (1, .45, .4, 1)
        elif self.playing:
            text, color = "Live", (.78, 1, 0, 1)
        elif self.track:
            text, color = "Paused", (.7, .75, .7, 1)
        else:
            text, color = "Idle", (.6, .65, .6, 1)
        if self.status_text.text != text:
            self.status_text.text = text
        pulse = .55 + .45 * abs(((time.monotonic() * 1.6) % 2) - 1) if self.playing or st == "analyzing" else 1
        self._dot_c.rgba = (color[0], color[1], color[2], pulse)

    def _update_player_ui(self):
        if self.track:
            self.title_lbl.text = Path(self.track).stem
            n = len(self.s.playlist)
            i = self.s.playlist.index(self.track) + 1 if self.track in self.s.playlist else 1
            self.sub_lbl.text = "Track %d of %d  -  %s" % (i, n, Path(self.track).suffix.upper().lstrip("."))
        self.dur_lbl.text = fmt_time(self.duration)
        self.play_btn.set_icon("pause" if self.playing else "play")
        self.shuffle_btn.active = bool(self.s.shuffle)
        self.repeat_btn.set_icon("repeat_one" if self.s.repeat == "one" else "repeat")
        self.repeat_btn.active = self.s.repeat != "off"
        self._update_status()

    # ================================================================ layers
    def add_layer(self, k):
        self.s.layers.append({"type": k.lower(), "name": k + " " + str(len(self.s.layers) + 1), "visible": True, "locked": False,
                              "x": 50, "y": 50, "scale": 1.0, "opacity": 1.0, "rotation": 0})
        self.s.selected = len(self.s.layers) - 1
        self.preview.refresh()
        self.show("layers")

    def add_image_layer(self):
        self.add_layer("Image")
        self.import_layer_image()

    def import_layer_image(self):
        if not self.s.layers or self.s.layers[self.s.selected].get("type") != "image":
            self.toast("Select an Image layer first")
            return
        choose_image(self._on_image_selected)

    def _on_image_selected(self, path):
        if not path:
            return
        layer = self.s.layers[self.s.selected]
        layer["image_path"] = path
        layer["name"] = Path(path).name
        self.preview.refresh()
        self.show("layers")

    def select_layer(self, i):
        self.s.selected = i
        self.show("layers")

    def set_layer_value(self, key, value, refresh=False):
        if not self.s.layers:
            return
        layer = self.s.layers[self.s.selected]
        if layer.get("locked", False) and key != "name":
            self.toast("Layer is locked")
            return
        layer[key] = value
        self.preview.refresh()
        self._save_trigger()

    def toggle_lock(self, i=None):
        i = self.s.selected if i is None else i
        if 0 <= i < len(self.s.layers):
            self.s.layers[i]["locked"] = not self.s.layers[i].get("locked", False)
            self.show("layers")

    def toggle_visible(self, i=None):
        i = self.s.selected if i is None else i
        if 0 <= i < len(self.s.layers):
            self.s.layers[i]["visible"] = not self.s.layers[i].get("visible", True)
            self.preview.refresh()
            self.show("layers")

    def move_layer(self, direction):
        i = self.s.selected
        j = i + direction
        if 0 <= i < len(self.s.layers) and 0 <= j < len(self.s.layers):
            self.s.layers[i], self.s.layers[j] = self.s.layers[j], self.s.layers[i]
            self.s.selected = j
            self.preview.refresh()
            self.show("layers")

    def duplicate(self):
        if self.s.layers:
            x = dict(self.s.layers[self.s.selected])
            x["name"] = x.get("name", "Layer") + " copy"
            self.s.layers.insert(self.s.selected + 1, x)
            self.s.selected += 1
            self.preview.refresh()
            self.show("layers")

    def delete_layer(self):
        if self.s.layers:
            self.s.layers.pop(self.s.selected)
            self.s.selected = max(0, min(self.s.selected, len(self.s.layers) - 1))
            self.preview.refresh()
            self.show("layers")

    # ================================================================ persistence
    def _project_path(self):
        app = App.get_running_app()
        base = app.user_data_dir if app else os.path.expanduser("~")
        return Path(base) / "vooc_project.json"

    def _autoload(self):
        try:
            p = self._project_path()
            if p.exists():
                self.s = ProjectState.from_data(json.loads(p.read_text()))
                self.s.playlist = [x for x in self.s.playlist if os.path.exists(x)]
        except Exception as exc:
            print("Vooc autoload failed: %s" % exc)

    def autosave(self):
        try:
            self._project_path().write_text(json.dumps(self.s.data(), indent=2))
        except Exception:
            pass

    def save(self):
        try:
            p = self._project_path()
            p.write_text(json.dumps(self.s.data(), indent=2))
            self.toast("Project saved")
        except Exception as exc:
            self.toast("Save failed: %s" % exc)

    def load(self):
        p = self._project_path()
        if not p.exists():
            self.toast("No saved project found")
            return
        self._autoload()
        if self.native is not None:
            self.native.configure_fx(self.s.audio, self.s.eq_freqs)
        T.dark = self.s.scene.get("ui_theme", "dark") != "light"
        self.build_ui()
        self.toast("Project loaded")

    def on_stop(self):
        self.autosave()
        if self.native is not None:
            self.native.release()


class VoocApp(App):
    def build(self):
        self.title = "Vooc"
        if Window is not None:
            Window.clearcolor = (.047, .055, .039, 1)
        details = _STARTUP_IMPORT_ERROR
        if not details:
            try:
                self.root_widget = VoocRoot()
                return self.root_widget
            except BaseException:
                import traceback
                details = traceback.format_exc()
        try:
            log_path = Path(self.user_data_dir) / "vooc_startup_error.txt"
            log_path.write_text(details, encoding="utf-8")
        except Exception:
            log_path = "app-private/vooc_startup_error.txt"
        from kivy.uix.label import Label
        from kivy.uix.button import Button
        fallback = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(8))
        fallback.add_widget(Label(text="VOOC STARTUP DIAGNOSTIC", bold=True, font_size="17sp",
                                  color=(1, .45, .4, 1), size_hint_y=None, height=dp(38)))
        info = Label(text="Startup failed. Full traceback is saved at:\n" + str(log_path),
                     halign="left", valign="middle", color=(.9, .9, .92, 1),
                     size_hint_y=None, height=dp(58))
        fallback.add_widget(info)
        scroll = ScrollView()
        error_text = Label(text=details[-7000:], size_hint_y=None, halign="left", valign="top",
                           color=(1, .78, .72, 1), font_size="10sp")
        error_text.bind(width=lambda obj, width: setattr(obj, "text_size", (width, None)))
        error_text.bind(texture_size=lambda obj, size: setattr(obj, "height", size[1] + dp(12)))
        scroll.add_widget(error_text)
        fallback.add_widget(scroll)
        close = Button(text="CLOSE APP", size_hint_y=None, height=dp(44))
        close.bind(on_release=lambda *_: App.get_running_app().stop())
        fallback.add_widget(close)
        return fallback

    def on_stop(self):
        root = getattr(self, "root_widget", None)
        if root is not None:
            root.on_stop()

    def on_pause(self):
        root = getattr(self, "root_widget", None)
        if root is not None:
            root.autosave()
        return True


if __name__ == "__main__":
    VoocApp().run()
