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
from kivy.uix.stencilview import StencilView
from kivy.uix.widget import Widget
from pathlib import Path
import json, os, random, time
_STARTUP_IMPORT_ERROR = None
try:
    from vooc import m3
    from vooc.m3 import T
    from vooc.state import ProjectState, THEMES, AUDIO_DEFAULTS, VISUALIZER_DEFAULTS, EQ_PRESETS
    from vooc.spectrum_source import AnalysisConfig, SpectrumLoader
    from vooc.android_audio import AndroidMediaPlayerSource
    from vooc.android_media import choose_audio, choose_file, choose_image, choose_template, publish_file, share_media, delete_media, request_notification_permission
    from vooc.property_editor import AdvancedProperties, pretty as pretty_module, _ICONS as EDITOR_ICONS
    from vooc.property_schema import EFFECT_MODULES, field_spec, iter_fields
    from vooc.model import ProjectModel, LayerModel
    from vooc.history import History
    from vooc.theme_engine import MODES as TE_MODES, CONTRAST as TE_CONTRAST, VISION as TE_VISION
    from vooc.audio.timeline_hub import TimelineHub
    from vooc.engine.scene import SceneView
    from vooc.engine.shader_presets import apply_preset as apply_shader_preset, PRESETS as SHADER_PRESET_TABLE
    from vooc.templates import (TemplateStore, apply_template, builtin as builtin_template,
                                BUILTINS as BUILTIN_TEMPLATES)
    from vooc.palettes import BUILTIN as PALETTE_BUILTIN, METHODS as PALETTE_METHODS, apply_palette, extract_palette, extract_colors, export_palettes, import_palettes, unique_name
    from vooc.media_meta import read_metadata
    from vooc import library as LIB, perf as PERF
    from vooc.exporter import (Exporter, plan_export, frame_size, RESOLUTIONS, FPS_OPTIONS, ASPECTS, CODECS,
                               QUALITY, PROFILES, COLOR_MATRIX, B_FRAMES, KEYFRAMES, NAME_TOKENS, DEFAULT_PATTERN,
                               output_name, encoder_available)
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


class ClipStage(StencilView):
    """Clips the live visualizer to its 16:9 box; children follow pos_hint like a FloatLayout."""

    def __init__(self, **kw):
        super().__init__(**kw)
        self.bind(pos=self._place, size=self._place)

    def add_widget(self, widget, *a, **kw):
        super().add_widget(widget, *a, **kw)
        widget.bind(size=self._place)
        self._place()

    def _place(self, *_):
        for c in self.children:
            ph = c.pos_hint or {}
            if c.size_hint_x is not None:
                c.width = self.width * c.size_hint_x
            if c.size_hint_y is not None:
                c.height = self.height * c.size_hint_y
            x = self.x + self.width * ph["x"] if "x" in ph else (
                self.x + self.width * ph["right"] - c.width if "right" in ph else self.x)
            y = self.y + self.height * ph["y"] if "y" in ph else (
                self.y + self.height * ph["top"] - c.height if "top" in ph else self.y)
            c.pos = (x, y)


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

# Quick effect toggles on the Visual page: (title, module, shader preset, intensity path, lo, hi)
QUICK_EFFECTS = [
    ("Glow", "BLUREFFECT", None, "Blur Settings/Radius/Blur Multiplier", 0.0, 10.0),
    ("Motion Blur", "MOTIONBLUREFFECT", None, "Motion Blur/Motion/Blur Amount Multiplier", 0.0, 10.0),
    ("RGB Split", "RGBSPLITEFFECT", None, "Effect Settings/Split/Split Amount", 0.0, 1.0),
    ("Mirror", "MIRROREREFFECT", None, "Appearance/Mirror/Offset", 0.0, 1.0),
    ("Zoom Blur", "CUSTOMSHADER", "Zoom Blur", "Shader Code/GLSL/Intensity", 0.0, 1.0),
    ("Twirl", "CUSTOMSHADER", "Twirl", "Shader Code/GLSL/Intensity", 0.0, 1.0),
    ("Kaleidoscope", "CUSTOMSHADER", "Kaleidoscope", "Shader Code/GLSL/Intensity", 0.0, 1.0),
    ("Reflection", "CUSTOMSHADER", "Reflection", "Shader Code/GLSL/Intensity", 0.0, 1.0),
    ("3D Box", "CUSTOMSHADER", "3D Box", "Shader Code/GLSL/Intensity", 0.0, 1.0),
    ("Color Correction", "CUSTOMSHADER", "Color Correction", "Shader Code/GLSL/Intensity", 0.0, 1.0),
    ("Vignette", "CUSTOMSHADER", "Vignette", "Shader Code/GLSL/Intensity", 0.0, 1.0),
    ("Film Grain", "CUSTOMSHADER", "Film Grain", "Shader Code/GLSL/Intensity", 0.0, 1.0),
]
ADDABLE = [("Text", "TEXT", "text"), ("Image", "IMAGE", "image"), ("Bars", "BARS", "bars"),
           ("Particles", "PARTICLES", "sparkle"), ("Waveform", "WAVEFORM", "tune"), ("Shape", "SHAPE", "studio"),
           ("Group", "GROUP", "layers"), ("Glow", "BLUREFFECT", "eye"), ("Shader", "CUSTOMSHADER", "more"), ("Effect", "__EFFECT__", "plus")]
VISIBLE_ON_ADD = ("WAVEFORM", "SHAPE", "GROUP")
FIXED_MODULES = ("COMPOSITION", "AUDIOPROVIDER")
B = "Bars Setup/Bars/"
CANVAS = "Render Settings/Canvas/"
OUT = "Render Settings/Output/"


class Progress(m3.Box):
    def __init__(self, **kw):
        super().__init__(bg=T.surface3, radius=dp(4), size_hint_y=None, height=dp(8), **kw)
        self.fill = m3.Box(bg=T.primary, radius=dp(4), size_hint=(None, 1))
        self.add_widget(self.fill)
        self.value = 0.0
        self.bind(size=lambda *_: self.set(self.value))

    def set(self, v):
        self.value = max(0.0, min(1.0, float(v)))
        self.fill.width = max(dp(8) if self.value > 0 else 0, self.width * self.value)


class VoocRoot(FloatLayout):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.s = ProjectState()
        self._autoload()
        self._apply_ui_theme()
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
        self.timeline = None
        self.meta = {"title": "", "artist": "", "album": "", "art": ""}
        self.selected_layer = None
        self._edit_layer = None
        self.exporter = None
        self.export_state = {"running": False, "progress": 0.0, "text": "", "result": ""}
        self.project = self._load_project()
        self.history = History(20)
        self.history.reset(self.project.to_dict())
        self.templates = TemplateStore(Path(self._data_dir()) / "templates")
        if AndroidMediaPlayerSource.is_available():
            self.native = AndroidMediaPlayerSource(lambda *a: None, self._native_error, self._native_completed,
                                                   self._native_focus_changed)
            self.native.configure_fx(self.s.audio, self.s.eq_freqs)
        self.hub = TimelineHub(self.project.layer("AUDIOPROVIDER"), timeline=lambda: self.timeline,
                               position=lambda: self.clock()[0], playing=lambda: self.clock()[1],
                               duration=lambda: self.duration, on_error=self._native_error)
        self.hub.metadata = self.meta
        self.scene = SceneView(self.project, self.hub, size_hint=(1, 1), pos_hint={"x": 0, "y": 0})
        self.scene.set_perf(**self.s.perf)
        self.analysis = {"state": "idle", "progress": 0.0}
        self.loader = SpectrumLoader(self._spectrum_ready, self._spectrum_progress,
                                     scheduler=lambda fn: Clock.schedule_once(lambda _dt: fn(), 0))
        self._save_trigger = Clock.create_trigger(lambda _dt: self.autosave(), 1.0)
        self._reanalyse_trigger = Clock.create_trigger(lambda _dt: self.reanalyse(), 0.8)
        m3.RECENT_COLORS[:] = [c for c in (self.s.scene.get("recent_colors") or []) if isinstance(c, str)][:20]
        m3.COLOR_HOOKS["recent_changed"] = lambda cols: (self.s.scene.__setitem__("recent_colors", cols),
                                                          self._save_trigger())
        m3.COLOR_HOOKS["eyedropper"] = self.eyedropper
        self.insets = system_insets() or (0, 0)
        self.build_ui()
        if system_insets() is None:
            Clock.schedule_once(self._retry_insets, 0.5)
        Clock.schedule_interval(self.tick, 1 / 15.0)
        self._tuner = PERF.AutoTuner()
        Clock.schedule_interval(self._auto_perf, 1.0)
        if "preset_init" not in self.s.perf:
            self.s.perf["preset_init"] = True
            tier = PERF.ram_tier(PERF.device_ram())
            if tier in ("Low", "Medium"):
                Clock.schedule_once(lambda *_: self.apply_perf_preset(tier), 0)
        Clock.schedule_interval(self._smoke_autoload, 1.0)
        if Window is not None:
            Window.bind(on_keyboard=self._on_key)

    def _data_dir(self):
        cached = getattr(self, "_data_dir_cache", None)
        if cached:
            return cached
        app = App.get_running_app()
        path = app.user_data_dir if app else os.path.expanduser("~/.vooc")
        if app:
            self._data_dir_cache = path
        return path

    def _load_project(self):
        if isinstance(self.s.engine, dict) and self.s.engine.get("layers"):
            try:
                return ProjectModel.from_dict(self.s.engine)
            except Exception as exc:
                print("Vooc engine project invalid, using defaults: %s" % exc)
        project = ProjectModel()
        try:
            apply_template(project, builtin_template(self.s.look.get("template", "Circle Classic")))
        except Exception:
            pass
        return project

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
        self.clear_widgets()
        if Window is not None:
            Window.clearcolor = T.bg
        top, bottom = self.insets
        main = m3.Box(orientation="vertical", bg=T.bg, padding=(dp(8), dp(4) + top, dp(8), 0), spacing=dp(6))
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
        bar = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(3))
        logo = m3.Box(bg=T.primary, radius=dp(12), size_hint=(None, None), size=(dp(32), dp(32)), padding=dp(7))
        logo.add_widget(m3.Icon(name="bars", tint=T.on_primary))
        holder = BoxLayout(size_hint_x=None, width=dp(44), padding=(0, dp(6)))
        holder.add_widget(logo)
        bar.add_widget(holder)
        col = BoxLayout(orientation="vertical", padding=(dp(4), dp(4)))
        col.add_widget(m3.lbl("Vooc", 20, bold=True))
        col.add_widget(m3.lbl("Audio visualizer studio", 11, T.on_var))
        bar.add_widget(col)
        self.undo_btn = m3.IconBtn("undo", self.undo)
        self.redo_btn = m3.IconBtn("redo", self.redo)
        bar.add_widget(self.undo_btn)
        bar.add_widget(self.redo_btn)
        self._sync_history_btns()
        bar.add_widget(m3.IconBtn("theme", self.toggle_theme))
        bar.add_widget(m3.IconBtn("folder", self.load))
        bar.add_widget(m3.IconBtn("save", self.save))
        return bar

    def _preview_ratio(self):
        comp = self.project.layer("COMPOSITION")
        try:
            return max(0.3, min(3.0, float(comp.get(OUT + "Resolution Width")) /
                                float(comp.get(OUT + "Resolution Height"))))
        except Exception:
            return 16 / 9.0

    def _visual_card(self):
        """Live engine preview at the real output aspect ratio (16:9 by default), never stretched."""
        pad = dp(4)
        frame = m3.Box(bg=T.surface1, radius=dp(24), padding=pad, size_hint_y=None)
        holder = FloatLayout()
        stage = ClipStage(size_hint=(None, None))

        def fit(*_):
            ratio = self._preview_ratio()
            inner_w = max(dp(100), (frame.width or self.width or 360) - pad * 2)
            limit = max(dp(112), (self.height or 800) * 0.36)
            h = inner_w / ratio
            w = inner_w
            if h > limit:                 # tall ratios: letterbox inside the card
                h = limit
                w = h * ratio
            frame.height = h + pad * 2
            stage.size = (w, h)
            stage.pos = (holder.x + (holder.width - w) / 2.0, holder.y + (holder.height - h) / 2.0)
            chip_w = min(dp(118), w * 0.46)
            for chip in (getattr(self, "status", None), getattr(self, "style_chip", None)):
                if chip is not None and chip.parent is stage:
                    chip.width = chip_w
        if getattr(self, "_fit_visual", None) is not None:
            self.unbind(height=self._fit_visual)
        self._fit_visual = fit
        self.bind(height=fit)
        frame.bind(width=fit)
        holder.bind(pos=fit, size=fit)
        holder.add_widget(stage)
        self.visual_frame = frame
        if self.scene.parent is not None:
            self.scene.parent.remove_widget(self.scene)
        stage.add_widget(self.scene)
        from vooc.analyzer_overlay import AnalyzerOverlay
        if getattr(self, "analyzer_overlay", None) is not None:
            self.analyzer_overlay.stop()
        self.analyzer_overlay = AnalyzerOverlay(
            self.hub, lambda: bool(self.project.layer("AUDIOPROVIDER").get("Analyzer/Debug/Show Analyzer Overlay")),
            size_hint=(1, 1), pos_hint={"x": 0, "y": 0})
        stage.add_widget(self.analyzer_overlay)
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
        self.style_text = m3.lbl(self.s.look.get("template", "Template"), 11, (1, 1, 1, .92), bold=True)
        self.style_chip.add_widget(self.style_text)
        stage.add_widget(self.style_chip)
        frame.add_widget(holder)
        fit()
        return frame

    def _player_card(self):
        """Compact mini player: art + title + prev/play/next on one row, shuffle / seek / repeat below."""
        c = m3.Box(orientation="vertical", bg=T.surface1, radius=dp(20), padding=(dp(10), dp(6), dp(10), dp(2)),
                   size_hint_y=None, height=dp(76), spacing=0)
        head = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        art = m3.Box(bg=T.primary_container, radius=dp(10), size_hint=(None, None), size=(dp(36), dp(36)), padding=dp(9))
        art.add_widget(m3.Icon(name="note", tint=T.on_primary_container))
        wrap = BoxLayout(size_hint_x=None, width=dp(36), padding=(0, dp(4)))
        wrap.add_widget(art)
        head.add_widget(wrap)
        col = BoxLayout(orientation="vertical", padding=(0, dp(4)))
        self.title_lbl = m3.lbl("No track loaded", 14, bold=True)
        self.sub_lbl = m3.lbl("Import an audio file to start", 10, T.on_var)
        col.add_widget(self.title_lbl)
        col.add_widget(self.sub_lbl)
        head.add_widget(col)
        self.play_btn = m3.IconBtn("play", self.toggle, kind="filled", size=dp(42))
        for w in (m3.IconBtn("prev", self.previous, size=dp(38)), self.play_btn,
                  m3.IconBtn("next", self.next, size=dp(38))):
            holder = BoxLayout(size_hint_x=None, width=w.width, padding=(0, (dp(44) - w.height) / 2))
            holder.add_widget(w)
            head.add_widget(holder)
        c.add_widget(head)
        seek_row = BoxLayout(size_hint_y=None, height=dp(32), spacing=dp(2))
        self.shuffle_btn = m3.IconBtn("shuffle", self.toggle_shuffle, size=dp(32))
        self.repeat_btn = m3.IconBtn("repeat", self.cycle_repeat, size=dp(32))
        self.pos_lbl = m3.lbl("0:00", 10, T.on_var, halign="right", size_hint_x=None, width=dp(32))
        self.seek = m3.Slider3(0, 1, 0, on_change=self._seek_preview, on_commit=self._seek_commit, fmt=fmt_time)
        self.dur_lbl = m3.lbl("0:00", 10, T.on_var, size_hint_x=None, width=dp(32))
        for w in (self.shuffle_btn, self.pos_lbl, self.seek, self.dur_lbl, self.repeat_btn):
            seek_row.add_widget(w)
        c.add_widget(seek_row)
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

    def _smoke_autoload(self, *_):
        """Emulator smoke test hook: play files/smoke_track.* directly (the
        emulator's document picker often shows no files). No-op for users."""
        try:
            base = Path(App.get_running_app().user_data_dir)
            if not (base / "smoke_targets").exists():
                return
            trigger = base / "smoke_load"
            if not trigger.exists():
                return
            trigger.unlink()
            tracks = sorted(base.glob("smoke_track.*"))
            if tracks:
                print("Vooc smoke: loading %s" % tracks[0])
                self.load_track(str(tracks[0]), autoplay=True)
        except Exception as exc:
            print("Vooc smoke autoload: %s" % exc)

    def _dump_targets(self, *_):
        """Emulator smoke test hook: publish key button positions (fractions,
        y from top) when files/smoke_targets exists. No-op for users."""
        try:
            app = App.get_running_app()
            base = Path(app.user_data_dir)
            if not (base / "smoke_targets").exists() or Window is None:
                return
            H = float(Window.height)
            off_top = 0
            try:
                from jnius import autoclass
                if autoclass("android.os.Build$VERSION").SDK_INT < 35:
                    act = autoclass("org.kivy.android.PythonActivity").mActivity
                    ins = act.getWindow().getDecorView().getRootWindowInsets()
                    off_top = int(ins.getSystemWindowInsetTop()) if ins is not None else 0
            except Exception:
                off_top = 0
            out = []

            def add(name, w):
                if w is None or w.get_root_window() is None:
                    return
                x, y = w.to_window(w.x + w.width / 2.0, w.y + w.height / 2.0)
                out.append("%s %d %d px" % (name, int(x), int(off_top + H - y)))
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
        sc = self.s.scene
        col.add_widget(m3.section("Playlist", "%d tracks" % len(self.s.playlist)))
        if not self.s.playlist:
            empty = m3.card(bg=T.surface1)
            empty.add_widget(m3.lbl("Your imported tracks will appear here.", 13, T.on_var, size_hint_y=None, height=dp(24)))
            empty.add_widget(m3.lbl("MP3, M4A, AAC, FLAC, OGG, OPUS and WAV are supported.", 11, T.on_var,
                                    size_hint_y=None, height=dp(20)))
            col.add_widget(empty)
            return
        tools = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        tools.add_widget(m3.text_field(sc.get("lib_query", ""), self._lib_query, hint="Search title, artist, file"))
        tools.add_widget(m3.SelectField("Sort by", LIB.SORTS, sc.get("lib_sort", LIB.SORTS[0]),
                                        lambda v: self._lib_set("lib_sort", v), size_hint_x=.62))
        col.add_widget(tools)
        chips = LIB.chips(self.s.playlist)
        chip = sc.get("lib_chip", "All")
        if chip not in chips:
            chip = "All"
        col.add_widget(m3.ChipGroup(chips, chip, lambda v: self._lib_set("lib_chip", v)))
        self.lib_list = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(10))
        self.lib_list.bind(minimum_height=self.lib_list.setter("height"))
        col.add_widget(self.lib_list)
        self._fill_library()

    def _lib_meta(self):
        return self.s.scene.setdefault("lib_meta", {})

    def _lib_query(self, text):
        self.s.scene["lib_query"] = text
        self._fill_library()

    def _lib_set(self, key, value):
        self.s.scene[key] = value
        self._save_trigger()
        self._fill_library()

    def toggle_track_favorite(self, path):
        favs = self.s.scene.setdefault("lib_favs", [])
        if path in favs:
            favs.remove(path)
        else:
            favs.append(path)
        self._save_trigger()
        self._fill_library()

    def _fill_library(self):
        box = getattr(self, "lib_list", None)
        if box is None:
            return
        box.clear_widgets()
        sc = self.s.scene
        favs = sc.get("lib_favs") or []
        meta = self._lib_meta()
        paths = LIB.view(self.s.playlist, sc.get("lib_query", ""), sc.get("lib_sort", LIB.SORTS[0]),
                         sc.get("lib_chip", "All"), favs, meta)
        if not paths:
            box.add_widget(m3.lbl("No tracks match.", 13, T.on_var, size_hint_y=None, height=dp(40), halign="center"))
        for path in paths:
            on = path == self.track
            tile = m3.Tap(on_tap=lambda p=path: self.load_track(p, autoplay=True), size_hint_y=None, height=dp(60),
                          radius=dp(18), bg=T.secondary_container if on else T.surface1, padding=(dp(12), 0), spacing=dp(12))
            badge = m3.Box(bg=T.primary if on else T.surface3, radius=dp(12), size_hint=(None, None), size=(dp(40), dp(40)), padding=dp(10))
            badge.add_widget(m3.Icon(name=("bars" if on and self.playing else "note"), tint=T.on_primary if on else T.on_var))
            wrap = BoxLayout(size_hint_x=None, width=dp(40), padding=(0, dp(10)))
            wrap.add_widget(badge)
            tile.add_widget(wrap)
            info = BoxLayout(orientation="vertical", padding=(0, dp(10)))
            title = (self.meta.get("title") if on and self.meta.get("title") else LIB.title_of(path, meta))
            info.add_widget(m3.lbl(title, 14, T.on_secondary_container if on else T.on_surface, bold=on))
            sub = LIB.ext_of(path)
            artist = self.meta.get("artist") if on and self.meta.get("artist") else LIB.artist_of(path, meta)
            if artist:
                sub = artist + "  -  " + sub
            info.add_widget(m3.lbl(sub + ("  -  now playing" if on else ""), 11, T.on_var))
            tile.add_widget(info)
            fav = path in favs
            tile.add_widget(m3.IconBtn("star_on" if fav else "star", lambda p=path: self.toggle_track_favorite(p),
                                       size=dp(40), tint=T.primary if fav else None))
            tile.add_widget(m3.IconBtn("close", lambda p=path: self.remove_track(p), size=dp(40)))
            box.add_widget(tile)

    # ---------------------------------------------------------------- visual (quick look editor)
    def _engine_changed(self, layer=None):
        if layer is not None and layer.module == "COMPOSITION" and self._fit_visual:
            self._fit_visual()
        if layer is not None and layer.module == "AUDIOPROVIDER" and self.track:
            if self._analysis_config().key != getattr(self.loader.config, "key", None):
                self._reanalyse_trigger()
        self._save_trigger()

    def _analysis_config(self):
        return AnalysisConfig.from_layer(self.project.layer("AUDIOPROVIDER"))

    def reanalyse(self, force=False):
        """Re-run the decoder/FFT with the current Analyzer settings (FFT size, window, overlap, bands, channels)."""
        if not self.track:
            self.toast("Load a track first")
            return
        if force:
            self.loader.forget(self.track)
        self.analysis = {"state": "analyzing", "progress": 0.0}
        self.loader.load(self.track, self._analysis_config())
        cfg = self.loader.config
        self.toast("Analysing: FFT %d, %s, %d%% overlap%s" % (cfg.fft, cfg.window, cfg.overlap,
                                                             ", stereo" if cfg.stereo else ""))
        self._update_status()

    def _engine_action(self, layer, path):
        """Actions from the property editor that need the app (audio, analyser)."""
        if path.endswith("Re-analyse Track"):
            self.reanalyse(force=True)
            return False
        if path.endswith("Tap Tempo"):
            bpm = self.hub.tap()
            self.toast("Tap tempo: %.1f BPM" % bpm if bpm > 0 else "Keep tapping on the beat")
            self._save_trigger()
            return False
        return False

    def _setv(self, layer, path, value):
        if layer.set(path, value):
            self._engine_changed(layer)

    def _page_visual(self, col):
        bars = self.project.layer("BARS")
        look = self.s.look
        # templates
        col.add_widget(m3.section("Templates", "reuse a look on any track"))
        favs = self.templates.favorites()
        names = BUILTIN_TEMPLATES + [n for n in self.templates.names() if n not in BUILTIN_TEMPLATES]
        names = sorted(names, key=lambda n: n not in favs)
        col.add_widget(m3.ChipGroup(names, look.get("template", ""), self.apply_template_named))
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        row.add_widget(m3.Button3("Browse", self.browse_templates, kind="tonal", icon="layers"))
        row.add_widget(m3.Button3("Save", self.save_template, kind="outlined", icon="save"))
        row.add_widget(m3.Button3("Share", self.export_template, kind="outlined", icon="export"))
        col.add_widget(row)
        # colors
        col.add_widget(m3.section("Colors", "applies to every layer"))
        pals = dict(PALETTE_BUILTIN)
        pals.update(self.s.palettes)
        sw = {k: (m3._hex(v[1]), m3._hex(v[2])) for k, v in pals.items()}
        col.add_widget(m3.ChipGroup(list(pals), look.get("palette", ""), self.apply_palette_named, swatches=sw))
        colors = self._current_palette()
        pc = m3.card(spacing=dp(6))
        grid = GridLayout(cols=2, spacing=dp(8), size_hint_y=None, height=dp(44) * 2 + dp(8))
        for i, name in enumerate(["Deep", "Main", "Accent", "Highlight"]):
            grid.add_widget(m3.ColorField(colors[i] + "FF" if len(colors[i]) == 7 else colors[i],
                                          lambda v, i=i: self._edit_palette_color(i, v), title=name + " color"))
        pc.add_widget(grid)
        pc.add_widget(m3.lbl("Extraction method", 12, T.on_var, size_hint_y=None, height=dp(22)))
        pc.add_widget(m3.SelectField("Extraction method", PALETTE_METHODS, look.get("palette_method", "K-Means"),
                                     lambda v: (look.__setitem__("palette_method", v), self._save_trigger())))
        pc.add_widget(m3.SliderRow("Colors to extract", 3, 8, int(look.get("palette_count", 6)),
                                   lambda x: (look.__setitem__("palette_count", int(round(x))), self._save_trigger()),
                                   step=1, fmt=lambda x: "%d" % x, default=6))
        found = look.get("extracted") or []
        if found:
            pc.add_widget(m3.lbl("Extracted - tap a color to use it", 12, T.on_var, size_hint_y=None, height=dp(22)))
            strip = BoxLayout(size_hint_y=None, height=dp(36), spacing=dp(6))
            for c in found[:8]:
                strip.add_widget(m3.Tap(on_tap=lambda c=c: self._assign_extracted(c), bg=m3._parse_hex(c),
                                        radius=dp(10), line=T.outline))
            pc.add_widget(strip)
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        row.add_widget(m3.Button3("From album art", self.palette_from_art, kind="tonal", icon="image"))
        row.add_widget(m3.Button3("Save", self.save_palette, kind="outlined", icon="save"))
        pc.add_widget(row)
        pc.add_widget(m3.Button3("Manage palettes", self.manage_palettes, kind="text", icon="tune"))
        pc.add_widget(m3.switch_row("Auto colors from album art", bool(look.get("auto_palette", True)),
                                    lambda on: (look.__setitem__("auto_palette", bool(on)), self._save_trigger()),
                                    "Recolor when a track with cover art loads"))
        col.add_widget(pc)
        # spectrum
        col.add_widget(m3.section("Spectrum", "double tap = reset, tap value = type"))
        card = m3.card(spacing=dp(2))
        rows = [("Bar count", B + "Bar Count", 4, 256, 1, lambda x: "%d" % x),
                ("Sensitivity", B + "Height Scale", 0, 10, 0, lambda x: "%.2fx" % x),
                ("Max height", B + "Max Height Scale", 0, 1, 0, lambda x: "%d%%" % round(x * 100)),
                ("Smoothing", "Behavior/Physics/Softness", 0, 1, 0, lambda x: "%d%%" % round(x * 100)),
                ("Rise delay", "Behavior/Physics/Reaction Delay", 0, 1, 0, lambda x: "%d%%" % round(x * 100)),
                ("Bar width", B + "Bar Width", 0, 1, 0, lambda x: "%d%%" % round(x * 100)),
                ("Bar spacing", B + "Bar Spacing", 0, 1, 0, lambda x: "%d%%" % round(x * 100)),
                ("Path size", B + "Path Size", 0, 1, 0, lambda x: "%d%%" % round(x * 100)),
                ("Frequency min", "Behavior/Audio/Frequency Min (Hz)", 20, 2000, 1, lambda x: "%d Hz" % x),
                ("Frequency max", "Behavior/Audio/Frequency Max (Hz)", 1000, 16000, 1,
                 lambda x: ("%.1f kHz" % (x / 1000.0)) if x >= 1000 else "%d Hz" % x),
                ("Rotation", "General/Scale & Rotation/Rotation", 0, 1, 0, lambda x: "%d deg" % round(x * 360))]
        for title, path, lo, hi, step, fmt in rows:
            spec = field_spec("BARS", path)
            card.add_widget(m3.SliderRow(title, lo, hi, max(lo, min(hi, float(bars.get(path)))),
                                         lambda x, p=path, st=step: self._setv(bars, p, int(round(x)) if st else round(x, 4)),
                                         step=step, fmt=fmt, default=spec["default"]))
        seg_count = sum(1 for i in (1, 2, 3) if bars.get("Segment %d/Segment %d/Enable Segment" % (i, i)))
        card.add_widget(m3.SliderRow("Segments", 1, 3, max(1, seg_count), self._set_segments, step=1,
                                     fmt=lambda x: "%d" % x, default=1))
        for title, path in [("Shape / orientation", B + "Shape Path"), ("Measure what", "Behavior/Audio/Measure What"),
                            ("Audio channel", "Behavior/Audio/Audio Channel")]:
            card.add_widget(m3.lbl(title, 12, T.on_var, size_hint_y=None, height=dp(22)))
            card.add_widget(m3.SelectField(title, field_spec("BARS", path)["options"], bars.get(path),
                                           lambda v, p=path: self._setv(bars, p, v)))
        card.add_widget(m3.switch_row("Mirror bars", bool(bars.get("Segment 1/Segment 1/Mirror")),
                                      lambda on: self._setv(bars, "Segment 1/Segment 1/Mirror", bool(on))))
        card.add_widget(m3.switch_row("Flip input", bool(bars.get(B + "Flip Input")),
                                      lambda on: self._setv(bars, B + "Flip Input", bool(on)), "High frequencies first"))
        col.add_widget(card)
        # effects
        col.add_widget(m3.section("Effects", "%d effects" % len(QUICK_EFFECTS)))
        fx = m3.card(spacing=dp(2), padding=(dp(6), dp(6)))
        for title, module, preset, path, lo, hi in QUICK_EFFECTS:
            layer = self._effect_layer(module, preset, create=False)
            on = bool(layer is not None and layer.get("General/Layer/Visible"))
            fx.add_widget(m3.switch_row(title, on, lambda v, t=title: self.toggle_effect(t, bool(v))))
            if on:
                spec = field_spec(module, path)
                fx.add_widget(m3.SliderRow("Intensity", lo, hi, max(lo, min(hi, float(layer.get(path)))),
                                           lambda x, l=layer, p=path: self._setv(l, p, round(x, 4)),
                                           default=spec["default"] if preset is None else _preset_intensity(preset)))
        col.add_widget(fx)
        # other layers
        col.add_widget(m3.section("Layers on canvas"))
        other = m3.card(spacing=0, padding=(dp(4), dp(4)))
        for title, module, sub in [("Album art", "IMAGE", "Cover of the current track"),
                                   ("Particles", "PARTICLES", "Sparks driven by the beat"),
                                   ("Title / artist text", "TEXT", "%title% and %artist%")]:
            layer = self.project.layer(module)
            other.add_widget(m3.switch_row(title, bool(layer.get("General/Layer/Visible")),
                                           lambda v, l=layer: self._setv(l, "General/Layer/Visible", bool(v)), sub))
        part = self.project.layer("PARTICLES")
        other.add_widget(m3.SliderRow("Particle count", 0, 5000, float(part.get("Overall Settings/Emitter/Count Limit")),
                                      lambda x: self._setv(part, "Overall Settings/Emitter/Count Limit", int(x)),
                                      step=10, fmt=lambda x: "%d" % x, default=300))
        comp = self.project.layer("COMPOSITION")
        other.add_widget(m3.lbl("Background", 12, T.on_var, size_hint_y=None, height=dp(22)))
        other.add_widget(m3.ColorField(comp.get(CANVAS + "Background Color"),
                                       lambda v: self._setv(comp, CANVAS + "Background Color", v),
                                       default="#070906FF", swatches=self._palette_swatches, title="Background"))
        col.add_widget(other)
        col.add_widget(m3.Button3("Reset look to template", lambda: self.apply_template_named(look.get("template", "Circle Classic")),
                                  kind="outlined", icon="reset"))

    def _set_segments(self, x):
        bars = self.project.layer("BARS")
        n = int(round(x))
        for i in (1, 2, 3):
            bars.set("Segment %d/Segment %d/Enable Segment" % (i, i), i <= n)
        self._engine_changed(bars)

    def _effect_layer(self, module, preset, create=True):
        for l in self.project.layers:
            if l.module != module:
                continue
            if preset is None or l.get("General/Layer/Tag") == preset or \
                    (preset and l.get("Shader Code/GLSL/Effect Preset") == preset):
                return l
        if not create:
            return None
        layer = LayerModel(module)
        if preset:
            apply_shader_preset(layer, preset)
        self._insert_layer(layer)
        return layer

    def _insert_layer(self, layer):
        """New layers go above everything except the top text layer (text stays sharp)."""
        idx = len(self.project.layers)
        for i in range(len(self.project.layers) - 1, -1, -1):
            if self.project.layers[i].module == "TEXT":
                idx = i
                break
        if layer.module == "TEXT":
            idx = len(self.project.layers)
        self.project.layers.insert(idx, layer)

    def toggle_effect(self, title, on):
        entry = next(e for e in QUICK_EFFECTS if e[0] == title)
        _t, module, preset, _p, _lo, _hi = entry
        layer = self._effect_layer(module, preset, create=on)
        if layer is None:
            return
        layer.set("General/Layer/Visible", on)
        if on and module == "CUSTOMSHADER":
            err = self.scene.compile_shader(layer)
            if err:
                self.toast("Shader error: " + err[:80])
        self._engine_changed(layer)
        if on and module in ("BLUREFFECT", "MOTIONBLUREFFECT") and not self.s.perf.get("heavy_blur", True):
            self.toast("Heavy blur is off in Studio > Scene (preview only)")
        self.show("visual")

    # ---------------------------------------------------------------- palettes
    def _current_palette(self):
        name = self.s.look.get("palette", "Acid")
        pals = dict(PALETTE_BUILTIN)
        pals.update(self.s.palettes)
        return list(self.s.look.get("colors") or pals.get(name) or PALETTE_BUILTIN["Acid"])

    def _palette_swatches(self):
        return [c[:7] + "FF" for c in self._current_palette()] + ["#FFFFFFFF", "#000000FF"]

    def apply_palette_named(self, name):
        pals = dict(PALETTE_BUILTIN)
        pals.update(self.s.palettes)
        if name not in pals:
            return
        self.s.look["palette"] = name
        self.s.look["colors"] = list(pals[name])
        apply_palette(self.project, pals[name])
        self._engine_changed()
        self.show("visual")

    def _edit_palette_color(self, index, value):
        colors = self._current_palette()
        colors[index] = value[:7]
        self.s.look["colors"] = colors
        self.s.look["palette"] = ""
        apply_palette(self.project, colors)
        self._engine_changed()

    def _name_sheet(self, title, value, on_ok):
        sheet = m3.Sheet(title)
        sheet.height = dp(220)
        box = {"v": value}
        sheet.box.add_widget(m3.text_field(value, lambda v: box.__setitem__("v", v), "Name"))
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        row.add_widget(m3.Button3("Cancel", sheet.dismiss, kind="outlined"))
        row.add_widget(m3.Button3("OK", lambda: (sheet.dismiss(), on_ok(box["v"].strip())), kind="filled"))
        sheet.box.add_widget(row)
        sheet.open()

    def save_palette(self):
        def ok(name):
            name = unique_name(name or "My palette", self.s.palettes)
            self.s.palettes[name] = [c[:7] for c in self._current_palette()]
            self.s.look["palette"] = name
            self._save_trigger()
            self.toast("Saved " + name)
            self.show("visual")
        self._name_sheet("Save palette", unique_name("My palette", self.s.palettes), ok)

    def _assign_extracted(self, color):
        roles = ["Deep", "Main", "Accent", "Highlight"]
        m3.select_sheet("Use %s as" % color, roles, "Main",
                        lambda role: (self._edit_palette_color(roles.index(role), color), self.show("visual")))

    def manage_palettes(self):
        from kivy.core.window import Window
        sheet = m3.Sheet("My palettes")
        pals = self.s.palettes
        sheet.height = min(Window.height * 0.8, dp(170) + max(1, len(pals)) * dp(56))
        scroll = ScrollView(do_scroll_x=False)
        lst = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(4))
        lst.bind(minimum_height=lst.setter("height"))

        def refresh():
            sheet.dismiss()
            self._save_trigger()
            self.show("visual")
            Clock.schedule_once(lambda _dt: self.manage_palettes(), 0.25)

        def rename(old):
            def ok(new):
                if not new or new == old:
                    return
                new = unique_name(new, {k for k in pals if k != old})
                pals[new] = pals.pop(old)
                if self.s.look.get("palette") == old:
                    self.s.look["palette"] = new
                refresh()
            self._name_sheet("Rename palette", old, ok)

        def duplicate(name):
            pals[unique_name(name + " copy", pals)] = list(pals[name])
            refresh()

        def delete(name):
            pals.pop(name, None)
            if self.s.look.get("palette") == name:
                self.s.look["palette"] = ""
            self.toast("Deleted " + name)
            refresh()

        if not pals:
            lst.add_widget(m3.lbl("No saved palettes yet. Tap Save in Colors.", 13, T.on_var, size_hint_y=None, height=dp(40)))
        for name, cols in list(pals.items()):
            row = m3.Tap(on_tap=lambda n=name: (sheet.dismiss(), self.apply_palette_named(n)), size_hint_y=None,
                         height=dp(52), radius=dp(12), padding=(dp(8), dp(6)), spacing=dp(6))
            for c in cols[:4]:
                row.add_widget(m3.Box(bg=m3._parse_hex(c), radius=dp(6), size_hint_x=None, width=dp(16)))
            row.add_widget(m3.lbl(name, 14))
            for icon, fn in (("text", rename), ("plus", duplicate), ("close", delete)):
                row.add_widget(m3.IconBtn(icon, lambda n=name, f=fn: f(n), size=dp(40)))
            lst.add_widget(row)
        scroll.add_widget(lst)
        sheet.box.add_widget(scroll)
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        row.add_widget(m3.Button3("Import", lambda: (sheet.dismiss(), self.import_palettes()), kind="tonal", icon="folder"))
        row.add_widget(m3.Button3("Export", self.export_palettes, kind="outlined", icon="export"))
        sheet.box.add_widget(row)
        sheet.open()

    def import_palettes(self):
        def got(path):
            if not path:
                return
            try:
                with open(path, encoding="utf-8") as f:
                    new = import_palettes(f.read(), existing=self.s.palettes)
            except Exception as exc:
                self.toast("Not a palette file: %s" % exc)
                return
            self.s.palettes.update(new)
            self._save_trigger()
            self.toast("Imported %d palette%s" % (len(new), "" if len(new) == 1 else "s"))
            self.show("visual")
        choose_template(got)

    def export_palettes(self):
        if not self.s.palettes:
            self.toast("No saved palettes to export")
            return
        path = Path(self._data_dir()) / "vooc-palettes.json"
        try:
            path.write_text(export_palettes(self.s.palettes), encoding="utf-8")
            where = publish_file(str(path), path.name, "application/json", "Download")
        except Exception as exc:
            self.toast("Export failed: %s" % exc)
            return
        self.toast("Palettes saved to " + where)

    def palette_from_art(self, quiet=False):
        art = self.meta.get("art")
        if not art or not os.path.isfile(art):
            img = self.project.layer("IMAGE").get("Image Source/Source/Pick Image")
            art = img if img and os.path.isfile(img) else ""
        if not art:
            if not quiet:
                self.toast("This track has no album art. Pick an image in the Image layer.")
            return
        try:
            method = self.s.look.get("palette_method", "K-Means")
            count = int(self.s.look.get("palette_count", 6))
            colors = extract_palette(art, k=max(4, count), method=method)
            self.s.look["extracted"] = extract_colors(art, method, count)
        except Exception as exc:
            if not quiet:
                self.toast("Could not read colors: %s" % exc)
            return
        self.s.palettes["Album art"] = colors
        self.s.look["palette"] = "Album art"
        self.s.look["colors"] = colors
        apply_palette(self.project, colors)
        self._engine_changed()
        if not quiet:
            self.toast("Colors taken from the album art")
        if self.page == "visual":
            self.show("visual")

    # ---------------------------------------------------------------- templates
    def apply_template_named(self, name):
        try:
            data = self.templates.load(name)
            apply_template(self.project, data)
        except Exception as exc:
            self.toast("Template failed: %s" % exc)
            return
        self.s.look["template"] = name
        if self.s.look.get("colors") and self.s.look.get("palette") not in ("", None) and name not in BUILTIN_TEMPLATES:
            pass
        self.style_text.text = name
        self.selected_layer = None
        self._edit_layer = None
        self.scene.set_perf(**self.s.perf)
        self._engine_changed(self.project.layer("COMPOSITION"))
        if self.page in ("visual", "layers"):
            self.show(self.page)

    def cycle_style(self):
        names = BUILTIN_TEMPLATES + [n for n in self.templates.names() if n not in BUILTIN_TEMPLATES]
        cur = self.s.look.get("template", names[0])
        nxt = names[(names.index(cur) + 1) % len(names)] if cur in names else names[0]
        self.apply_template_named(nxt)

    def _thumbnail(self):
        try:
            from vooc.templates import make_thumbnail
            rw, rh = self.scene.passes[-1].size
            return make_thumbnail(self.scene.pixels(), rw, rh)
        except Exception as exc:
            print("Vooc thumbnail failed: %s" % exc)
            return None

    def save_template(self):
        sheet = m3.Sheet("Save template")
        sheet.height = dp(330)
        base = self.s.look.get("template") or "My look"
        f = {"name": base if base not in BUILTIN_TEMPLATES else "My " + base, "desc": "", "tags": ""}
        try:
            old = self.templates.load(f["name"]).get("meta", {}) if f["name"] not in BUILTIN_TEMPLATES else {}
            f["desc"], f["tags"] = old.get("description", ""), ", ".join(old.get("tags", []))
        except Exception:
            pass
        sheet.box.add_widget(m3.text_field(f["name"], lambda v: f.__setitem__("name", v), "Template name"))
        sheet.box.add_widget(m3.text_field(f["desc"], lambda v: f.__setitem__("desc", v), "Description (optional)"))
        sheet.box.add_widget(m3.text_field(f["tags"], lambda v: f.__setitem__("tags", v), "Tags, comma separated"))
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))

        def do():
            n = f["name"].strip() or "My look"
            if n in BUILTIN_TEMPLATES:
                n = "My " + n
            meta = {"description": f["desc"].strip(), "tags": [t.strip() for t in f["tags"].split(",") if t.strip()],
                    "aspect": self.s.export.get("aspect", "16:9")}
            try:
                self.templates.save(self.project, n, meta=meta, thumbnail=self._thumbnail())
            except Exception as exc:
                self.toast("Save failed: %s" % exc)
                return
            sheet.dismiss()
            self.s.look["template"] = n
            self.style_text.text = n
            self._save_trigger()
            self.toast("Template saved: " + n)
            self.show("visual")
        row.add_widget(m3.Button3("Cancel", sheet.dismiss, kind="outlined"))
        row.add_widget(m3.Button3("Save", do, kind="filled", icon="save"))
        sheet.box.add_widget(row)
        sheet.open()

    def _thumb_widget(self, b64):
        from kivy.uix.image import Image as KvImage
        holder = m3.Box(bg=T.surface3, radius=dp(10), size_hint=(None, None), size=(dp(96), dp(54)), padding=dp(2))
        if b64:
            try:
                import base64, io
                from kivy.core.image import Image as CoreImage
                tex = CoreImage(io.BytesIO(base64.b64decode(b64)), ext="png").texture
                holder.add_widget(KvImage(texture=tex, fit_mode="contain"))
                return holder
            except Exception:
                pass
        holder.padding = dp(14)
        holder.add_widget(m3.Icon(name="bars", tint=T.on_var))
        return holder

    def browse_templates(self, filt=None):
        from kivy.core.window import Window
        filt = filt or self.s.look.get("template_filter", "All")
        self.s.look["template_filter"] = filt
        sheet = m3.Sheet("Templates")
        sheet.height = Window.height * 0.85
        favs = self.templates.favorites()
        mine = [n for n in self.templates.names() if n not in BUILTIN_TEMPLATES]
        names = {"All": BUILTIN_TEMPLATES + mine, "Favorites": [n for n in BUILTIN_TEMPLATES + mine if n in favs],
                 "Mine": mine}[filt]
        names = sorted(names, key=lambda n: n not in favs)
        sheet.box.add_widget(m3.Segmented(["All", "Favorites", "Mine"], filt,
                                          lambda v: (sheet.dismiss(), self.browse_templates(v))))
        scroll = ScrollView(do_scroll_x=False)
        lst = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(6), padding=(0, dp(6)))
        lst.bind(minimum_height=lst.setter("height"))
        cur = self.s.look.get("template", "")

        def again():
            sheet.dismiss()
            Clock.schedule_once(lambda _dt: self.browse_templates(filt), 0.2)

        def actions(name):
            builtin = name in BUILTIN_TEMPLATES
            opts = ["Apply", "Duplicate", "Share bundle (.vzb)"] + ([] if builtin else ["Rename", "Delete"])

            def do(o):
                try:
                    if o == "Apply":
                        sheet.dismiss()
                        self.apply_template_named(name)
                        return
                    if o == "Duplicate":
                        if builtin:
                            self.apply_template_named(name)
                            self.templates.save(self.project, self.templates.unique("My " + name),
                                                thumbnail=self._thumbnail())
                        else:
                            self.templates.duplicate(name)
                    elif o.startswith("Share"):
                        self.export_template(name)
                        return
                    elif o == "Rename":
                        def ok(new):
                            if new:
                                nn = self.templates.rename(name, new)
                                if self.s.look.get("template") == name:
                                    self.s.look["template"] = nn
                                again()
                        self._name_sheet("Rename template", name, ok)
                        return
                    elif o == "Delete":
                        self.templates.delete(name)
                        self.toast("Deleted " + name)
                except Exception as exc:
                    self.toast("%s failed: %s" % (o, exc))
                again()
            m3.select_sheet(name, opts, "Apply", do)

        if not names:
            lst.add_widget(m3.lbl("Nothing here yet. Tap the star on a template to add it to Favorites.", 13,
                                  T.on_var, shorten=False, size_hint_y=None, height=dp(48)))
        for name in names:
            info = self.templates.info(name)
            meta = info.get("meta") or {}
            row = m3.Tap(on_tap=lambda n=name: (sheet.dismiss(), self.apply_template_named(n)), size_hint_y=None,
                         height=dp(70), radius=dp(16), padding=(dp(8), dp(8)), spacing=dp(10),
                         bg=T.secondary_container if name == cur else T.surface1)
            row.add_widget(self._thumb_widget(info.get("thumbnail")))
            col = BoxLayout(orientation="vertical")
            col.add_widget(m3.lbl(name, 14, bold=True))
            sub = meta.get("description") or ("Built-in" if info.get("builtin") else "My template")
            tags = meta.get("tags") or []
            if tags:
                sub += "  -  " + ", ".join("#" + t for t in tags[:3])
            col.add_widget(m3.lbl(sub, 11, T.on_var))
            row.add_widget(col)
            fav = name in favs
            row.add_widget(m3.IconBtn("star_on" if fav else "star",
                                      lambda n=name: (self.templates.toggle_favorite(n), again()), size=dp(40)))
            row.add_widget(m3.IconBtn("more", lambda n=name: actions(n), size=dp(40)))
            lst.add_widget(row)
        scroll.add_widget(lst)
        sheet.box.add_widget(scroll)
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        row.add_widget(m3.Button3("Save current", lambda: (sheet.dismiss(), self.save_template()), kind="tonal", icon="save"))
        row.add_widget(m3.Button3("Import", lambda: (sheet.dismiss(), self.import_template()), kind="outlined", icon="folder"))
        sheet.box.add_widget(row)
        sheet.open()

    def import_template(self):
        def got(path):
            if not path:
                return
            try:
                name = self.templates.import_file(path)
            except Exception as exc:
                self.toast("Not a valid template: %s" % exc)
                return
            self.apply_template_named(name)
            problems = getattr(self.templates, "last_problems", [])
            self.toast("Imported %s%s" % (name, (" (%s)" % problems[0]) if problems else ""))
        choose_template(got)

    def export_template(self, name=None):
        name = name or self.s.look.get("template") or "My look"
        try:
            if name in BUILTIN_TEMPLATES and self.templates.path(name) is None:
                data_name = name
            elif self.templates.path(name) is None:
                self.templates.save(self.project, name, thumbnail=self._thumbnail())
                data_name = name
            else:
                data_name = name
            path = self.templates.export_bundle(data_name, Path(self._data_dir()) / "share")
        except Exception as exc:
            self.toast("Export failed: %s" % exc)
            return
        where = publish_file(str(path), Path(path).name, "application/octet-stream", "Download")
        self.toast("Template bundle saved to " + where)

    # ---------------------------------------------------------------- sound
    def _page_sound(self, col):
        a = self.s.audio
        hint = "live on Android player" if self.native else "desktop: volume only"
        col.add_widget(m3.section("Output", hint))
        card = m3.card(spacing=dp(2))
        pct = lambda x: "%d%%" % round(x)
        db = lambda x: "%+.1f dB" % x
        for title, key, lo, hi, step, fmt, bip in [
                ("Volume", "volume", 0, 150, 1, pct, False),
                ("Balance", "balance", -100, 100, 1, lambda x: "C" if round(x) == 0 else ("L%d" % -round(x) if x < 0 else "R%d" % round(x)), True),
                ("Pre-amp", "pre", -12, 12, .5, db, True),
                ("Bass boost", "bass", 0, 12, .5, db, False),
                ("3D surround", "virtualizer", 0, 100, 1, pct, False),
                ("Speed", "speed", 50, 200, 1, pct, False),
                ("Pitch", "pitch", 50, 200, 1, pct, False)]:
            val = max(lo, min(hi, float(a.get(key, AUDIO_DEFAULTS[key]))))
            card.add_widget(m3.SliderRow(title, lo, hi, val, lambda x, k=key: self.set_audio(k, x), step=step, fmt=fmt,
                                         bipolar=bip, default=AUDIO_DEFAULTS[key]))
        col.add_widget(card)
        # visual sync (AUDIOPROVIDER)
        ap = self.project.layer("AUDIOPROVIDER")
        col.add_widget(m3.section("Visual sync", "keeps bars on the beat"))
        sc = m3.card(spacing=dp(2))
        sc.add_widget(m3.SliderRow("Visual offset", -500, 500, float(ap.get("Sync & Offset/Timing/Audio Offset")),
                                   lambda x: self._setv(ap, "Sync & Offset/Timing/Audio Offset", round(x)), step=1,
                                   fmt=lambda x: "%+d ms" % x, bipolar=True, default=0))
        sc.add_widget(m3.SliderRow("Analysis gain", 0, 4, float(ap.get("General/Routing/Preamp")),
                                   lambda x: self._setv(ap, "General/Routing/Preamp", round(x, 3)),
                                   fmt=lambda x: "%.2fx" % x, default=field_spec("AUDIOPROVIDER", "General/Routing/Preamp")["default"]))
        sc.add_widget(m3.lbl("Audio source", 12, T.on_var, size_hint_y=None, height=dp(22)))
        sc.add_widget(m3.SelectField("Audio source", field_spec("AUDIOPROVIDER", "General/Routing/Audio Source")["options"],
                                     ap.get("General/Routing/Audio Source"),
                                     lambda v: self._setv(ap, "General/Routing/Audio Source", v)))
        col.add_widget(sc)
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
            sl = m3.Slider3(-12, 12, float(a.get(key, 0)), 0.5, on_change=changed, vertical=True, bipolar=True, default=0.0,
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

    # ---------------------------------------------------------------- layers (engine layer stack)
    def _layer_title(self, layer):
        tag = layer.values.get("General/Layer/Tag") or ""
        base = pretty_module(layer.module)
        return base + ("  -  " + tag if tag and tag.lower() not in (base.lower(), "main", "") else "")

    def _page_layers(self, col):
        col.add_widget(m3.section("Add layer"))
        add = GridLayout(cols=3, spacing=dp(8), size_hint_y=None)
        add.bind(minimum_height=add.setter("height"))
        for title, module, icon in ADDABLE:
            act = self._pick_effect if module == "__EFFECT__" else (lambda m=module: self.add_layer(m))
            add.add_widget(m3.Button3(title, act, kind="tonal", icon=icon, height=dp(44)))
        col.add_widget(add)
        col.add_widget(m3.section("Layer stack", "top draws last"))
        layers = self.project.layers
        if self.selected_layer not in layers:
            self.selected_layer = next((l for l in reversed(layers) if l.module not in FIXED_MODULES), None)
        for layer in reversed(layers):           # top of the stack first
            sel = layer is self.selected_layer
            visible = bool(layer.values.get("General/Layer/Visible", True))
            tile = m3.Tap(on_tap=lambda l=layer: self.select_layer(l), size_hint_y=None, height=dp(54), radius=dp(16),
                          bg=T.secondary_container if sel else T.surface1, padding=(dp(12), 0), spacing=dp(8))
            tile.add_widget(m3.Icon(name=EDITOR_ICONS.get(layer.module, "layers"),
                                    tint=T.on_secondary_container if sel else (T.on_var if visible else T.outline),
                                    size_hint_x=None, width=dp(22)))
            tile.add_widget(m3.lbl(self._layer_title(layer), 14,
                                   T.on_secondary_container if sel else (T.on_surface if visible else T.on_var), bold=sel))
            if layer.module not in FIXED_MODULES:
                eye = m3.IconBtn("eye" if visible else "eye_off", lambda l=layer: self.toggle_visible(l), size=dp(40))
                eye.active = visible
                eye._colors()
                tile.add_widget(eye)
            if layer.module not in FIXED_MODULES:
                locked = bool(layer.values.get("General/Layer/Locked", False))
                lk = m3.IconBtn("lock" if locked else "unlock", lambda l=layer: self.toggle_lock(l), size=dp(40))
                lk.active = locked
                lk._colors()
                tile.add_widget(lk)
            tile.add_widget(m3.IconBtn("tune", lambda l=layer: self.edit_layer(l), size=dp(40)))
            col.add_widget(tile)
        l = self.selected_layer
        if l is None:
            return
        col.add_widget(m3.section("Inspector", pretty_module(l.module)))
        card = m3.card(spacing=dp(2))
        if l.get("General/Layer/Locked"):
            card.add_widget(m3.lbl("This layer is locked. Unlock it to edit, move or delete.", 13, T.on_var,
                                   size_hint_y=None, height=dp(40)))
            card.add_widget(m3.Button3("Unlock layer", lambda: self.toggle_lock(l), kind="tonal", icon="unlock"))
            col.add_widget(card)
            return
        G = "General/"
        rows = [("Position X", G + "Position/Position X", 0, 1), ("Position Y", G + "Position/Position Y", 0, 1),
                ("Scale", G + "Scale & Rotation/Scale X", 0, 4), ("Rotation", G + "Scale & Rotation/Rotation", 0, 1)]
        if l.module == "IMAGE":
            S = "Image Source/Source/"
            rows += [("Size", S + "Size", 0, 1), ("Corner radius", S + "Corner Radius", 0, 1),
                     ("Opacity", S + "Opacity", 0, 1), ("GIF speed", S + "GIF Speed", 0, 4)]
        elif l.module == "TEXT":
            rows += [("Font size", "Typography/Font/Font Size", 0, 0.2)]
        if l.module in FIXED_MODULES and l.module == "AUDIOPROVIDER":
            rows = []
        for title, path, lo, hi in rows:
            spec = field_spec(l.module, path)
            fmt = (lambda x: "%d deg" % round(x * 360)) if "Rotation" in path else (lambda x: "%.3f" % x)
            card.add_widget(m3.SliderRow(title, lo, hi, max(lo, min(hi, float(l.get(path)))),
                                         lambda x, p=path: self._setv(l, p, round(x, 4)), fmt=fmt, default=spec["default"]))
        if l.module == "IMAGE":
            card.add_widget(m3.lbl("Shape (crop)", 12, T.on_var, size_hint_y=None, height=dp(22)))
            card.add_widget(m3.SelectField("Shape", field_spec("IMAGE", "Image Source/Source/Shape")["options"],
                                           l.get("Image Source/Source/Shape"),
                                           lambda v: self._setv(l, "Image Source/Source/Shape", v)))
            card.add_widget(m3.Button3("Choose image / GIF", lambda: self._pick_layer_image(l), kind="tonal", icon="image"))
        if l.module == "TEXT":
            card.add_widget(m3.text_field(l.get("Content/Text/Text Input"),
                                          lambda v: self._setv(l, "Content/Text/Text Input", v),
                                          "Text (%title%, %artist%, %time%)", multiline=True, height=dp(80)))
            card.add_widget(m3.ColorField(l.get("Appearance/Fill/Text Color"),
                                          lambda v: self._setv(l, "Appearance/Fill/Text Color", v),
                                          default="#FFFFFFFF", swatches=self._palette_swatches, title="Text color"))
            card.add_widget(m3.SelectField("Animation", field_spec("TEXT", "Text Animation/Animation/Animation Type")["options"],
                                           l.get("Text Animation/Animation/Animation Type"),
                                           lambda v: self._setv(l, "Text Animation/Animation/Animation Type", v)))
        card.add_widget(m3.Button3("All %d properties" % sum(1 for _ in iter_fields(l.module)), lambda: self.edit_layer(l),
                                   kind="filled", icon="tune"))
        col.add_widget(card)
        tools = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(4))
        tools.add_widget(Widget())
        for icon, fn in [("up", lambda: self.move_layer(1)), ("down", lambda: self.move_layer(-1)),
                         ("copy", self.duplicate), ("delete", self.delete_layer)]:
            tools.add_widget(m3.IconBtn(icon, fn, kind="tonal"))
        tools.add_widget(Widget())
        col.add_widget(tools)

    def _pick_layer_image(self, layer):
        def got(path):
            if path:
                layer.set("Image Source/Source/Pick Image", path)
                layer.set("Generated Album Art/Album Art/Enable Auto-Fetch", False)
                self._engine_changed(layer)
                if self.page == "layers":
                    self.show("layers")
        choose_image(got)

    def _pick_effect(self, *_):
        names = {pretty_module(m): m for m in EFFECT_MODULES}
        m3.select_sheet("Add effect", sorted(names), None, lambda title: self.add_layer(names[title]))

    def add_layer(self, module):
        layer = LayerModel(module)
        if module in EFFECT_MODULES or module in VISIBLE_ON_ADD:
            layer.set("General/Layer/Visible", True)
        if module == "TEXT":
            layer.set("Content/Text/Text Input", "Your text")
            layer.set("Content/Text/Use Metadata Variables", True)
        self._insert_layer(layer)
        self.selected_layer = layer
        if module == "CUSTOMSHADER":
            self.scene.compile_shader(layer)
        self._engine_changed(layer)
        if module == "IMAGE":
            layer.set("Generated Album Art/Album Art/Enable Auto-Fetch", False)
            self._pick_layer_image(layer)
        self.show("layers")

    def select_layer(self, layer):
        self.selected_layer = layer
        self.show("layers")

    def edit_layer(self, layer):
        self._edit_layer = layer
        self.studio_tab = "Properties"
        self.nav.select("studio")

    def toggle_visible(self, layer):
        layer.set("General/Layer/Visible", not layer.get("General/Layer/Visible"))
        if layer.module == "CUSTOMSHADER" and layer.get("General/Layer/Visible"):
            self.scene.compile_shader(layer)
        self._engine_changed(layer)
        self.show("layers")

    def toggle_lock(self, layer):
        layer.set("General/Layer/Locked", not layer.get("General/Layer/Locked"))
        self.toast("Layer locked" if layer.get("General/Layer/Locked") else "Layer unlocked")
        self._save_trigger()
        self.show("layers")

    def _locked(self, layer):
        if layer is not None and layer.get("General/Layer/Locked"):
            self.toast("Layer is locked")
            return True
        return False

    def move_layer(self, direction):
        """direction +1 = up (towards the top / drawn later)."""
        l = self.selected_layer
        layers = self.project.layers
        if l not in layers or self._locked(l):
            return
        i = layers.index(l)
        j = i + direction
        if 0 <= j < len(layers):
            layers[i], layers[j] = layers[j], layers[i]
            self._engine_changed(l)
            self.show("layers")

    def duplicate(self):
        l = self.selected_layer
        if l is None or l.module in FIXED_MODULES:
            self.toast("This layer cannot be duplicated")
            return
        from copy import deepcopy
        copy = LayerModel(l.module, deepcopy(l.values))
        self.project.layers.insert(self.project.layers.index(l) + 1, copy)
        self.selected_layer = copy
        if copy.module == "CUSTOMSHADER":
            self.scene.compile_shader(copy)
        self._engine_changed(copy)
        self.show("layers")

    def delete_layer(self):
        l = self.selected_layer
        if self._locked(l):
            return
        if l is None or l.module in FIXED_MODULES:
            self.toast("Composition and audio provider are always present")
            return
        if sum(1 for x in self.project.layers if x.module == l.module) == 1 and l.module in ("BARS", "TEXT", "IMAGE", "PARTICLES"):
            # the module grid always needs one layer of each type: hide it instead
            l.set("General/Layer/Visible", False)
            self.toast("Last %s layer hidden instead of deleted" % pretty_module(l.module))
        else:
            self.project.layers.remove(l)
            self.selected_layer = None
            self._engine_changed(l)
            self.show("layers")
            self.toast_undo("Deleted %s" % pretty_module(l.module))
            return
        self._engine_changed(l)
        self.show("layers")

    # ---------------------------------------------------------------- studio
    def _page_studio(self):
        box = BoxLayout(orientation="vertical", spacing=dp(10))
        self.studio_seg = m3.Segmented(["Properties", "Scene", "Export"], self.studio_tab, self._studio_tab)
        box.add_widget(self.studio_seg)
        self.studio_body = BoxLayout()
        box.add_widget(self.studio_body)
        self._studio_tab(self.studio_tab)
        return box

    def _studio_tab(self, tab):
        self.studio_tab = tab
        seg = getattr(self, "studio_seg", None)
        if seg is not None:
            seg.select(tab, notify=False)       # keep the segmented control in sync for programmatic switches
        self.studio_body.clear_widgets()
        if tab == "Properties":
            layer, self._edit_layer = self._edit_layer, None
            AdvancedProperties.scroll_cls = Scroll
            self.property_editor = AdvancedProperties(self.project, scene=lambda: self.scene,
                                                      on_change=self._engine_changed, pick_image=choose_image,
                                                      palette=self._palette_swatches, toast=self.toast, layer=layer,
                                                      on_action=self._engine_action,
                                                      pick_file=lambda cb, pat: choose_file(cb, pat, "Choose file"))
            self.studio_body.add_widget(self.property_editor)
            Clock.schedule_once(self._dump_targets, 0.8)
            return
        scroll = Scroll()
        col = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(8), padding=(0, 0, 0, dp(16)))
        col.bind(minimum_height=col.setter("height"))
        if tab == "Scene":
            self._scene_tab(col)
        else:
            self._export_tab(col)
        scroll.add_widget(col)
        self.studio_body.add_widget(scroll)

    def set_aspect(self, aspect):
        self.s.export["aspect"] = aspect
        w, h = frame_size("FHD 1080p", aspect)
        comp = self.project.layer("COMPOSITION")
        comp.set(OUT + "Resolution Width", w)
        comp.set(OUT + "Resolution Height", h)
        self._engine_changed(comp)
        self._save_trigger()

    def _scene_tab(self, col):
        comp = self.project.layer("COMPOSITION")
        col.add_widget(m3.section("Canvas", "preview = export framing"))
        col.add_widget(m3.lbl("Aspect ratio", 12, T.on_var, size_hint_y=None, height=dp(20)))
        col.add_widget(m3.ChipGroup(list(ASPECTS), self.s.export.get("aspect", "16:9"), self.set_aspect))
        c = m3.card(spacing=dp(4))
        c.add_widget(m3.lbl("Background color", 12, T.on_var, size_hint_y=None, height=dp(20)))
        c.add_widget(m3.ColorField(comp.get(CANVAS + "Background Color"),
                                   lambda v: self._setv(comp, CANVAS + "Background Color", v), default="#070906FF",
                                   swatches=self._palette_swatches, title="Background"))
        c.add_widget(m3.switch_row("Clear canvas every frame", bool(comp.get(CANVAS + "Clear Canvas on Frame")),
                                   lambda on: self._setv(comp, CANVAS + "Clear Canvas on Frame", bool(on)),
                                   "Off = motion trails"))
        col.add_widget(c)
        perf = self.s.perf
        col.add_widget(m3.section("Performance", "preview only"))
        p = m3.card(spacing=dp(2))
        self.fps_lbl = m3.lbl("Preview: %d fps" % round(self.scene.fps), 12, T.primary, size_hint_y=None, height=dp(22))
        p.add_widget(self.fps_lbl)
        p.add_widget(m3.lbl("Preset  (suggested for this device: %s)" % PERF.ram_tier(PERF.device_ram()), 12, T.on_var,
                            size_hint_y=None, height=dp(20)))
        p.add_widget(m3.Segmented(PERF.PRESET_NAMES + (["Custom"] if PERF.match(perf) == "Custom" else []),
                                  PERF.match(perf), self.apply_perf_preset))
        p.add_widget(m3.switch_row("Auto lower quality", bool(perf.get("auto", True)),
                                   lambda on: self._set_perf_flag("auto", bool(on)),
                                   "Steps down when frames drop"))
        p.add_widget(m3.lbl("Preview frame rate cap", 12, T.on_var, size_hint_y=None, height=dp(20)))
        p.add_widget(m3.ChipGroup(["24", "30", "45", "60"], str(perf.get("preview_fps", 60)),
                                  lambda v: self._set_perf("preview_fps", int(v))))
        p.add_widget(m3.lbl("Render quality", 12, T.on_var, size_hint_y=None, height=dp(20)))
        qmap = {"Low": 0.5, "Medium": 0.75, "High": 1.0}
        cur = next((k for k, v in qmap.items() if abs(v - float(perf.get("render_scale", 1.0))) < 0.01), "High")
        p.add_widget(m3.ChipGroup(list(qmap), cur, lambda v: self._set_perf("render_scale", qmap[v])))
        p.add_widget(m3.switch_row("Heavy blur effects", bool(perf.get("heavy_blur", True)),
                                   lambda on: self._set_perf("heavy_blur", bool(on)), "Glow and motion blur in the preview"))
        p.add_widget(m3.SliderRow("Max bars", 16, 256, float(perf.get("max_bars", 256)),
                                  lambda x: self._set_perf("max_bars", int(x)), step=1, fmt=lambda x: "%d" % x, default=256))
        p.add_widget(m3.SliderRow("Max particles", 100, 5000, float(perf.get("max_particles", 2000)),
                                  lambda x: self._set_perf("max_particles", int(x)), step=50, fmt=lambda x: "%d" % x,
                                  default=2000))
        col.add_widget(p)
        sc = self.s.scene
        col.add_widget(m3.section("App appearance", "Material 3"))
        a = m3.card(spacing=dp(4))
        a.add_widget(m3.lbl("Theme", 12, T.on_var, size_hint_y=None, height=dp(20)))
        a.add_widget(m3.Segmented(TE_MODES, sc.get("ui_mode") or ("Light" if sc.get("ui_theme") == "light" else "Dark"),
                                  lambda v: self.set_ui_theme(ui_mode=v)))
        a.add_widget(m3.lbl("Seed color (generates the whole scheme)", 12, T.on_var, size_hint_y=None, height=dp(20)))
        seeds = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(6))
        cur_seed = sc.get("ui_seed") or ""
        for seed in ["", "#C6FF00", "#00E5FF", "#7C4DFF", "#FF4081", "#FF9100", "#00C853"]:
            sel = seed == cur_seed
            t = m3.Tap(on_tap=lambda sd=seed: self.set_ui_theme(ui_seed=sd), radius=dp(20),
                       bg=m3._parse_hex(seed or "#C6FF00"), line=T.on_surface if sel else T.outline, padding=dp(10))
            if sel:
                t.add_widget(m3.Icon(name="check", tint=[0, 0, 0, 1]))
            elif not seed:
                t.add_widget(m3.lbl("A", 11, [0, 0, 0, 1], bold=True, halign="center"))
            seeds.add_widget(t)
        a.add_widget(seeds)
        a.add_widget(m3.ColorField((cur_seed or "#C6FF00") + "FF", lambda v: self.set_ui_theme(ui_seed=v[:7]),
                                   default="#C6FF00FF", title="Seed color"))
        a.add_widget(m3.lbl("Contrast", 12, T.on_var, size_hint_y=None, height=dp(20)))
        a.add_widget(m3.Segmented(TE_CONTRAST, sc.get("ui_contrast", "Standard"),
                                  lambda v: self.set_ui_theme(ui_contrast=v)))
        a.add_widget(m3.lbl("Color vision", 12, T.on_var, size_hint_y=None, height=dp(20)))
        a.add_widget(m3.SelectField("Color vision", TE_VISION, sc.get("ui_vision", "None"),
                                    lambda v: self.set_ui_theme(ui_vision=v)))
        col.add_widget(a)
        col.add_widget(m3.section("Background theme (canvas)"))
        col.add_widget(m3.SelectField("Background theme", list(THEMES), self.s.scene.get("theme", "Acid Night"), self._set_bg_theme))

    def apply_perf_preset(self, name, toast=False):
        if name not in PERF.PRESETS:
            return
        values = dict(PERF.PRESETS[name])
        self.s.perf.update(values)
        self.scene.set_perf(**values)
        self._save_trigger()
        if toast:
            self.toast("Preview quality lowered to %s to keep it smooth" % name)
        if self.page == "studio" and getattr(self, "studio_tab", "") == "Scene":
            self._studio_tab("Scene")

    def _set_perf_flag(self, key, value):
        self.s.perf[key] = value
        self._save_trigger()

    def _auto_perf(self, *_):
        perf = self.s.perf
        if not perf.get("auto", True) or not self.playing or self.export_state.get("running"):
            return
        cur = PERF.match({k: perf.get(k) for k in PERF.PRESETS["High"]})
        if cur == "Custom":
            return
        nxt = self._tuner.feed(self.scene.fps, float(perf.get("preview_fps", 60)), cur)
        if nxt:
            self.apply_perf_preset(nxt, toast=True)

    def _set_perf(self, key, value):
        self.s.perf[key] = value
        self.scene.set_perf(**{key: value})
        self._save_trigger()

    def _set_bg_theme(self, name):
        self.s.scene["theme"] = name
        comp = self.project.layer("COMPOSITION")
        comp.set(CANVAS + "Background Color", THEMES.get(name, "#070906") + "FF")
        self._engine_changed(comp)

    def _export_tab(self, col):
        e = self.s.export
        col.add_widget(m3.section("Video export", "MP4 / WebM"))

        def pick(title, key, options, conv=str, after=None):
            col.add_widget(m3.lbl(title, 12, T.on_var, size_hint_y=None, height=dp(20)))

            def change(o, k=key):
                self._set_export(k, conv(o))
                if after:
                    after()
            col.add_widget(m3.ChipGroup(options, str(e.get(key, options[0])), change))
        pick("Resolution", "resolution", RESOLUTIONS, after=lambda: self._studio_tab("Export"))
        if e.get("resolution") == "Custom":
            row = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
            for key, title in (("custom_w", "Width"), ("custom_h", "Height")):
                val = int(e.get(key, 1920 if key == "custom_w" else 1080))
                b = m3.Button3("%s  %d px" % (title, val), kind="outlined",
                               on_tap=lambda k=key, t=title, v=val: m3.number_dialog(
                                   t + " (even, 128-4096)", v, 128, 4096, 2,
                                   lambda x, kk=k: (self._set_export(kk, int(x) // 2 * 2), self._studio_tab("Export")),
                                   default=1920 if k == "custom_w" else 1080))
                row.add_widget(b)
            col.add_widget(row)
        pick("Frame rate", "fps", FPS_OPTIONS)
        col.add_widget(m3.lbl("Aspect ratio", 12, T.on_var, size_hint_y=None, height=dp(20)))
        col.add_widget(m3.ChipGroup(list(ASPECTS), e.get("aspect", "16:9"),
                                    lambda o: (self.set_aspect(o), self._update_plan())))
        codecs = [c for c in CODECS]
        pick("Video codec", "codec", codecs, after=lambda: self._studio_tab("Export"))
        if not encoder_available(e.get("codec", "H.264 (AVC)")):
            col.add_widget(m3.lbl("This device has no %s encoder. H.264 will be used." % e.get("codec"),
                                  11, T.error, shorten=False, size_hint_y=None, height=dp(30)))
        pick("Quality", "quality", list(QUALITY))
        col.add_widget(m3.switch_row("Include audio", bool(e.get("audio", True)),
                                     lambda on: self._set_export("audio", bool(on)),
                                     "AAC in MP4, Opus in WebM"))

        # ---- trim & fades
        col.add_widget(m3.section("Trim & fades", "seconds"))
        dur = float(getattr(self.timeline, "duration", 0.0) or self.duration or 0.0)
        top = max(1.0, dur)
        col.add_widget(m3.SliderRow("Start", 0, top, min(top, float(e.get("trim_start", 0) or 0)),
                                    lambda v: self._set_export("trim_start", round(v, 2)), step=0.1, unit="s",
                                    default=0, fmt=lambda v: fmt_time(v)))
        end = float(e.get("trim_end", 0) or 0)
        col.add_widget(m3.SliderRow("End (0 = song end)", 0, top, min(top, end),
                                    lambda v: self._set_export("trim_end", round(v, 2)), step=0.1, unit="s",
                                    default=0, fmt=lambda v: fmt_time(v) if v > 0 else "End"))
        col.add_widget(m3.SliderRow("Fade in", 0, 10, float(e.get("fade_in", 0) or 0),
                                    lambda v: self._set_export("fade_in", round(v, 2)), step=0.1, unit="s", default=0,
                                    fmt=lambda v: "Off" if v <= 0 else "%.1f s" % v))
        col.add_widget(m3.SliderRow("Fade out", 0, 10, float(e.get("fade_out", 0) or 0),
                                    lambda v: self._set_export("fade_out", round(v, 2)), step=0.1, unit="s", default=0,
                                    fmt=lambda v: "Off" if v <= 0 else "%.1f s" % v))

        # ---- advanced
        adv = bool(self.export_state.get("advanced"))
        col.add_widget(m3.Button3("Advanced settings" + ("  -" if adv else "  +"), kind="text", icon="tune",
                                  on_tap=lambda: (self.export_state.__setitem__("advanced", not adv),
                                                  self._studio_tab("Export"))))
        if adv:
            col.add_widget(m3.SliderRow("Bitrate (0 = auto)", 0, 120, float(e.get("bitrate_mbps", 0) or 0),
                                        lambda v: self._set_export("bitrate_mbps", round(v, 1)), step=0.5,
                                        unit=" Mbps", default=0, fmt=lambda v: "Auto" if v <= 0 else "%.1f Mbps" % v))
            grid = GridLayout(cols=2, size_hint_y=None, spacing=dp(8), row_default_height=dp(66), row_force_default=True)
            grid.bind(minimum_height=grid.setter("height"))

            def field(title, key, options, conv=str):
                box = BoxLayout(orientation="vertical", spacing=dp(2))
                box.add_widget(m3.lbl(title, 12, T.on_var, size_hint_y=None, height=dp(18)))
                box.add_widget(m3.SelectField(title, options, str(e.get(key, options[0])),
                                              lambda o, k=key: self._set_export(k, conv(o))))
                grid.add_widget(box)
            field("Keyframe interval (s)", "keyframe_s", KEYFRAMES, int)
            field("B-frames", "bframes", B_FRAMES, int)
            field("H.264 profile", "profile", list(PROFILES))
            field("Color matrix", "color", list(COLOR_MATRIX))
            col.add_widget(grid)
            col.add_widget(m3.lbl("B-frames need Android 10+. Unsupported profiles fall back automatically.",
                                  11, T.on_var, shorten=False, size_hint_y=None, height=dp(30)))

        # ---- file name
        col.add_widget(m3.section("File name", ""))
        col.add_widget(m3.text_field(e.get("pattern", DEFAULT_PATTERN), lambda v: self._set_export("pattern", v),
                                     hint=DEFAULT_PATTERN))
        col.add_widget(m3.lbl("Tokens: " + " ".join(NAME_TOKENS), 11, T.on_var, shorten=False,
                              size_hint_y=None, height=dp(30)))

        # ---- plan, progress, actions
        info = m3.card(spacing=dp(4))
        self.plan_lbl = m3.lbl("", 12, T.on_var, shorten=False, size_hint_y=None, height=dp(80), valign="top")
        info.add_widget(self.plan_lbl)
        self.export_bar = Progress()
        info.add_widget(self.export_bar)
        self.export_lbl = m3.lbl(self.export_state.get("text") or self.export_state.get("result") or "Ready", 12,
                                 T.primary, size_hint_y=None, height=dp(22))
        info.add_widget(self.export_lbl)
        self.export_bar.set(self.export_state.get("progress", 0.0))
        col.add_widget(info)
        row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        if self.export_state.get("running"):
            paused = self.exporter is not None and self.exporter.paused
            row.add_widget(m3.Button3("Resume" if paused else "Pause", self.pause_export, kind="tonal",
                                      icon="play" if paused else "pause"))
            row.add_widget(m3.Button3("Cancel", self.cancel_export, kind="outlined", icon="close"))
        else:
            row.add_widget(m3.Button3("Render video", self.start_export, kind="filled", icon="export"))
            row.add_widget(m3.Button3("Add to queue", self.queue_export, kind="tonal", icon="plus"))
        col.add_widget(row)

        queue = self.export_state.setdefault("queue", [])
        if queue:
            col.add_widget(m3.section("Queue", "%d waiting" % len(queue)))
            for i, job in enumerate(queue):
                r = m3.Box(size_hint_y=None, height=dp(48), spacing=dp(6), padding=(dp(12), 0),
                           bg=T.surface2, radius=dp(14))
                st = job["settings"]
                r.add_widget(m3.lbl("%d. %s  %s %sfps  %s" % (i + 1, st.get("resolution"), st.get("aspect"),
                                                            st.get("fps"), st.get("codec")), 13))
                r.add_widget(m3.IconBtn("close", lambda j=i: (queue.pop(j), self._studio_tab("Export")),
                                        size=dp(40)))
                col.add_widget(r)
            if not self.export_state.get("running"):
                col.add_widget(m3.Button3("Render queue", self.run_queue, kind="filled", icon="export"))

        history = self.s.export.get("history") or []
        if history:
            col.add_widget(m3.section("Recent exports", "%d" % len(history)))
            for i, h in enumerate(history[:12]):
                r = m3.Box(size_hint_y=None, height=dp(56), spacing=dp(4), padding=(dp(12), 0, dp(4), 0),
                           bg=T.surface2, radius=dp(14))
                txt = BoxLayout(orientation="vertical")
                txt.add_widget(m3.lbl(h.get("name", "video"), 13, T.on_surface if h.get("ok") else T.error))
                txt.add_widget(m3.lbl("%s  -  %s  -  %s" % (h.get("when", ""), _fmt_size(h.get("size", 0)),
                                                            h.get("status", "")), 11, T.on_var))
                r.add_widget(txt)
                if h.get("ok"):
                    r.add_widget(m3.IconBtn("export", lambda hh=h: self.share_export(hh), size=dp(40)))
                r.add_widget(m3.IconBtn("delete", lambda j=i: self.delete_export(j), size=dp(40)))
                col.add_widget(r)
            col.add_widget(m3.Button3("Clear history", self.clear_export_history, kind="text", icon="delete"))
        col.add_widget(m3.lbl("Rendering keeps the screen on, shows a notification, slows down when the phone "
                              "gets hot and avoids files over 4 GB.", 11, T.on_var, shorten=False,
                              size_hint_y=None, height=dp(44)))
        col.add_widget(m3.Button3("Save project JSON", self.save, kind="outlined", icon="save"))
        self._update_plan()

    def _set_export(self, key, value):
        self.s.export[key] = value
        self._save_trigger()
        self._update_plan()

    def _update_plan(self):
        if getattr(self, "plan_lbl", None) is None:
            return
        dur = getattr(self.timeline, "duration", 0.0) or self.duration
        if not dur:
            self.plan_lbl.text = "Load a track to see the export size."
            return
        plan = plan_export(self.s.export, dur, available=encoder_available)
        meta = dict(self.meta, title=self.meta.get("title") or (Path(self.track).stem if self.track else ""))
        name = output_name(self.s.export.get("pattern"), meta, plan, self.s.look.get("template", ""),
                           len(self.s.export.get("history") or []) + 1) + plan["ext"]
        text = "%dx%d  %d fps  %s  %.1f Mbps\nAbout %s for %s\n%s" % (
            plan["width"], plan["height"], plan["fps"], plan["codec"], plan["bitrate"] / 1e6,
            _fmt_size(plan["estimate_bytes"]), fmt_time(plan["duration"]), name)
        if plan["notes"]:
            text += "\n" + "; ".join(plan["notes"])
        self.plan_lbl.text = text

    def queue_export(self):
        from copy import deepcopy
        queue = self.export_state.setdefault("queue", [])
        if len(queue) >= 10:
            self.toast("The queue holds up to 10 jobs")
            return
        queue.append({"settings": deepcopy(self.s.export)})
        queue[-1]["settings"].pop("history", None)
        self.toast("Added to queue (%d)" % len(queue))
        self._studio_tab("Export")

    def run_queue(self):
        queue = self.export_state.get("queue") or []
        if queue and not self.export_state.get("running"):
            job = queue.pop(0)
            self.export_state["queue_left"] = len(queue)
            self.start_export(settings=job["settings"])

    def start_export(self, settings=None):
        if self.exporter is not None and self.exporter.running:
            return
        if not self.track:
            self.toast("Load a track first")
            return
        if self.analysis.get("state") != "ready":
            self.toast("Wait until the track analysis is finished")
            return
        if self.playing:
            self.toggle()
        request_notification_permission()
        settings = dict(settings or self.s.export)
        settings.pop("history", None)
        out_dir = Path(self._data_dir()) / "exports"
        out_dir.mkdir(parents=True, exist_ok=True)
        dur = getattr(self.timeline, "duration", 0.0) or self.duration
        plan = plan_export(settings, dur, available=encoder_available)
        n = len(self.s.export.get("history") or []) + 1
        stem = output_name(settings.get("pattern"), dict(self.meta, title=self.meta.get("title") or Path(self.track).stem),
                           plan, self.s.look.get("template", ""), n)
        out = out_dir / (stem + plan["ext"])
        k = 2
        while out.exists():
            out = out_dir / ("%s_%d%s" % (stem, k, plan["ext"]))
            k += 1
        self.scene.stop()
        queue = self.export_state.get("queue", [])
        self.export_state.update({"running": True, "progress": 0.0, "text": "Starting...", "result": "",
                                  "queue": queue, "mime": "video/webm" if plan["ext"] == ".webm" else "video/mp4"})
        self.exporter = Exporter(self.project, self.timeline, self.track, self.meta, settings, str(out),
                                 on_progress=self._export_progress, on_done=self._export_done)
        if self.exporter.start():
            if self.page == "studio" and self.studio_tab == "Export":
                self._studio_tab("Export")
        else:
            self.scene.start()

    def pause_export(self):
        ex = self.exporter
        if ex is None or not ex.running:
            return
        if ex.paused:
            ex.resume()
            self.scene.stop()
        else:
            ex.pause()
        if self.page == "studio" and self.studio_tab == "Export":
            self._studio_tab("Export")

    def cancel_export(self):
        if self.exporter is not None:
            self.export_state["queue"] = []
            self.exporter.cancel()

    def _export_progress(self, frac, text):
        if text == "Paused":
            self.export_state.update(progress=frac, text="Paused at %d%%" % round(frac * 100))
        else:
            self.export_state.update(progress=frac, text="Rendering %d%%  -  %s" % (round(frac * 100), text))
        if getattr(self, "export_bar", None) is not None and self.export_bar.get_root_window() is not None:
            self.export_bar.set(frac)
            self.export_lbl.text = self.export_state["text"]
        if self.status_text.text != "Export %d%%" % round(frac * 100):
            self.status_text.text = "Export %d%%" % round(frac * 100)

    def _export_done(self, ok, path, message):
        self.scene.start()
        result = message or ""
        mime = self.export_state.get("mime", "video/mp4")
        entry = {"name": Path(self.exporter.out_path).name if self.exporter else "video",
                 "when": time.strftime("%d %b %H:%M"), "ok": bool(ok and path), "size": 0,
                 "status": "Done" if ok else (message or "Failed")[:40], "mime": mime, "path": "", "uri": ""}
        if ok and path:
            try:
                entry["size"] = os.path.getsize(path)
            except OSError:
                pass
            where = publish_file(path, Path(path).name, mime, "Movies")
            if where != path:
                entry["uri"] = getattr(publish_file, "last_uri", "")
                try:
                    os.remove(path)
                except OSError:
                    pass
            else:
                entry["path"] = path
            entry["where"] = where
            result = ("Saved to " + where + (("  (" + message + ")") if message else ""))
        hist = self.s.export.setdefault("history", [])
        hist.insert(0, entry)
        del hist[50:]
        self._save_trigger()
        queue = self.export_state.get("queue", [])
        self.export_state = {"running": False, "progress": 1.0 if ok else 0.0, "text": "", "queue": queue,
                             "advanced": self.export_state.get("advanced"),
                             "result": result or ("Export failed" if not ok else "Done")}
        self.toast(("Saved " + entry["name"]) if entry["ok"] else self.export_state["result"][:60])
        if queue and ok:
            Clock.schedule_once(lambda *_: self.run_queue(), 0.5)
        if self.page == "studio" and self.studio_tab == "Export":
            self._studio_tab("Export")

    def share_export(self, h):
        if share_media(h.get("uri"), h.get("mime", "video/mp4")):
            return
        self.toast("Saved at " + (h.get("where") or h.get("path") or h.get("name", "")))

    def delete_export(self, index):
        hist = self.s.export.get("history") or []
        if not 0 <= index < len(hist):
            return
        h = hist.pop(index)
        if h.get("uri"):
            delete_media(h["uri"])
        elif h.get("path") and os.path.exists(h["path"]):
            try:
                os.remove(h["path"])
            except OSError:
                pass
        self._save_trigger()
        self.toast("Deleted " + h.get("name", "export"))
        self._studio_tab("Export")

    def clear_export_history(self):
        self.s.export["history"] = []
        self._save_trigger()
        self._studio_tab("Export")

    # ================================================================ theme / misc
    def _system_dark(self):
        try:
            from jnius import autoclass
            act = autoclass("org.kivy.android.PythonActivity").mActivity
            mode = act.getResources().getConfiguration().uiMode & 0x30
            return mode != 0x10          # UI_MODE_NIGHT_NO
        except Exception:
            return True

    def _apply_ui_theme(self):
        sc = self.s.scene
        mode = sc.get("ui_mode") or ("Light" if sc.get("ui_theme") == "light" else "Dark")
        dark = self._system_dark() if mode == "System" else mode != "Light"
        T.configure(dark=dark, seed=sc.get("ui_seed") or False, contrast=sc.get("ui_contrast", "Standard"),
                    vision=sc.get("ui_vision", "None"))

    def set_ui_theme(self, **kw):
        self.s.scene.update(kw)
        if "ui_mode" in kw and kw["ui_mode"] != "System":
            self.s.scene["ui_theme"] = kw["ui_mode"].lower()
        self._apply_ui_theme()
        self._save_trigger()
        self.build_ui()

    def toggle_theme(self):
        self.set_ui_theme(ui_mode="Light" if T.dark else "Dark")

    def toast(self, text, action=None, on_action=None):
        m3.Snackbar.show(text, self, action, on_action)

    def toast_undo(self, text):
        """Snackbar with an Undo button that rolls back the last project change."""
        self.autosave()
        self.toast(text, "Undo", self.undo)

    def _on_key(self, _win, key, *_):
        if key == 27:
            pe = getattr(self, "property_editor", None)
            if self.page == "studio" and pe is not None and self.studio_tab == "Properties" and pe.category:
                pe._home()
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
        if not os.path.isfile(path):
            self.toast("File not found: " + Path(path).name)
            if path in self.s.playlist:
                self.s.playlist.remove(path)
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
            self.timeline = None
            self._read_meta(path)
            self.analysis = {"state": "analyzing", "progress": 0.0}
            self.loader.load(path, self._analysis_config())
            if autoplay:
                self.toggle()
            self._update_player_ui()
            if self.page == "library":
                self.show("library")
            self._save_trigger()
        except Exception as e:
            self.toast(str(e))

    def _read_meta(self, path):
        try:
            meta = read_metadata(path, Path(self._data_dir()) / "art")
        except Exception:
            meta = {"title": Path(path).stem, "artist": "", "album": "", "art": ""}
        self.meta.clear()
        self.meta.update(meta)
        self.meta["path"] = path
        if meta.get("title") or meta.get("artist"):
            self._lib_meta()[path] = {"title": meta.get("title") or "", "artist": meta.get("artist") or ""}
        if meta.get("art") and self.s.look.get("auto_palette", True):
            self.palette_from_art(quiet=True)

    def _spectrum_ready(self, path, timeline):
        if path != self.track:
            return
        if timeline is None:
            self.analysis = {"state": "error", "progress": 0.0}
            self.toast("Could not analyse this track: " + (self.loader.error or "unsupported")[:60])
        else:
            if getattr(timeline, "complete", True):
                self.analysis = {"state": "ready", "progress": 1.0}
            else:
                self.analysis = {"state": "streaming", "progress": self.loader.progress}
            self.timeline = timeline
        self._update_status()

    def _spectrum_progress(self, p):
        if self.analysis.get("state") in ("analyzing", "streaming"):
            self.analysis["progress"] = p
            if p >= 1.0 and getattr(self.timeline, "complete", False):
                self.analysis["state"] = "ready"
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
        if path not in self.s.playlist:
            return
        index = self.s.playlist.index(path)
        self.s.playlist.remove(path)
        if path == self.track:
            self.stop()
        self.show("library")
        self._save_trigger()

        def restore():
            if path not in self.s.playlist:
                self.s.playlist.insert(min(index, len(self.s.playlist)), path)
                self.show("library")
                self._save_trigger()
        self.toast("Removed %s" % Path(path).stem, "Undo", restore)

    def clear_playlist(self):
        old = list(self.s.playlist)
        if not old:
            return
        self.stop()
        self.s.playlist = []
        self.show("library")
        self._save_trigger()

        def restore():
            self.s.playlist = old + [p for p in self.s.playlist if p not in old]
            self.show("library")
            self._save_trigger()
        self.toast("Cleared %d tracks" % len(old), "Undo", restore)

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
        lbl = getattr(self, "fps_lbl", None)
        if lbl is not None and lbl.get_root_window() is not None:
            lbl.text = "Preview: %d fps  -  %dx%d render" % ((round(self.scene.fps),) + tuple(self.scene.render_size()))
        if not self.export_state.get("running"):
            self._update_status()

    def _update_status(self):
        st = self.analysis.get("state")
        if st == "streaming" and self.playing:
            text, color = "Live %d%%" % round(self.analysis.get("progress", 0) * 100), (.78, 1, 0, 1)
        elif st in ("analyzing", "streaming"):
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
        pulse = .55 + .45 * abs(((time.monotonic() * 1.6) % 2) - 1) if self.playing or st in ("analyzing", "streaming") else 1
        self._dot_c.rgba = (color[0], color[1], color[2], pulse)

    def _update_player_ui(self):
        if self.track:
            self.title_lbl.text = self.meta.get("title") or Path(self.track).stem
            n = len(self.s.playlist)
            i = self.s.playlist.index(self.track) + 1 if self.track in self.s.playlist else 1
            artist = self.meta.get("artist")
            self.sub_lbl.text = (artist + "  -  " if artist else "") + "Track %d of %d  -  %s" % (
                i, n, Path(self.track).suffix.upper().lstrip("."))
        self.dur_lbl.text = fmt_time(self.duration)
        self.play_btn.set_icon("pause" if self.playing else "play")
        self.shuffle_btn.active = bool(self.s.shuffle)
        self.repeat_btn.set_icon("repeat_one" if self.s.repeat == "one" else "repeat")
        self.repeat_btn.active = self.s.repeat != "off"
        self._update_status()

    # ================================================================ persistence
    def _project_path(self):
        return Path(self._data_dir()) / "vooc_project.json"

    def _autoload(self):
        try:
            p = self._project_path()
            if p.exists():
                self.s = ProjectState.from_data(json.loads(p.read_text()))
                self.s.playlist = [x for x in self.s.playlist if os.path.exists(x)]
        except Exception as exc:
            print("Vooc autoload failed: %s" % exc)

    # ---------------------------------------------------------------- eyedropper
    def pixel_at(self, wx, wy):
        """#RRGGBBAA of the rendered frame under window point (wx, wy), or None outside the preview."""
        d = self.scene.display
        if not self.scene.passes or d.size[0] <= 0:
            return None
        sx, sy = self.scene.to_window(*d.pos)
        u, v = (wx - sx) / d.size[0], (wy - sy) / d.size[1]
        if not (0 <= u <= 1 and 0 <= v <= 1):
            return None
        rw, rh = self.scene.passes[-1].size
        x, y = min(rw - 1, int(u * rw)), min(rh - 1, int(v * rh))
        px = self.scene.pixels()
        i = (y * rw + x) * 4                 # fbo pixels are bottom-up like window coords
        r, g, b = px[i], px[i + 1], px[i + 2]
        return "#%02X%02X%02XFF" % (r, g, b)

    def eyedropper(self, done):
        from kivy.uix.modalview import ModalView
        self.show("visual")
        view = ModalView(background="", background_color=(0, 0, 0, 0), overlay_color=(0, 0, 0, 0.05),
                         auto_dismiss=False)
        hint = m3.Box(bg=T.surface2, radius=dp(16), padding=(dp(14), 0), size_hint=(None, None),
                      height=dp(44), width=dp(300), pos_hint={"center_x": .5, "top": .99})
        hint.add_widget(m3.lbl("Tap the preview to pick a color", 13))
        layer = FloatLayout()
        layer.add_widget(hint)
        view.add_widget(layer)

        def touch(_w, t):
            if hint.collide_point(*t.pos):
                return True
            view.dismiss()
            Clock.schedule_once(lambda _dt: done(self.pixel_at(*t.pos)), 0)
            return True
        view.bind(on_touch_down=touch)
        view.open()

    # ---------------------------------------------------------------- undo / redo
    def _sync_history_btns(self):
        h = self.history
        for btn, ok in ((getattr(self, "undo_btn", None), h.can_undo), (getattr(self, "redo_btn", None), h.can_redo)):
            if btn is not None:
                btn.opacity = 1.0 if ok else 0.38
                btn.disabled = not ok

    def _restore(self, state, label):
        if state is None:
            return
        try:
            project = ProjectModel.from_dict(state)
        except Exception as exc:
            self.toast("%s failed: %s" % (label, exc))
            return
        sel = getattr(self.selected_layer, "id", None)
        self.project = project
        self.scene.set_project(project)
        self.hub.layer = project.layer("AUDIOPROVIDER")
        self.selected_layer = next((l for l in project.layers if l.id == sel), None)
        self._edit_layer = None
        if self._fit_visual:
            self._fit_visual()
        self._restoring = True
        self.autosave()
        self._restoring = False
        self.show(self.page)
        self._sync_history_btns()
        self.toast(label)

    def undo(self):
        self.autosave()                 # flush a pending edit into history first
        self._restore(self.history.undo(), "Undo")

    def redo(self):
        self._restore(self.history.redo(), "Redo")

    def _write_project(self):
        self.s.engine = self.project.to_dict()
        if not getattr(self, "_restoring", False) and self.history.record(self.s.engine):
            self._sync_history_btns()
        p = self._project_path()
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.s.data(), indent=1))
        os.replace(tmp, p)

    def autosave(self):
        try:
            self._write_project()
        except Exception as exc:
            print("Vooc autosave failed: %s" % exc)

    def save(self):
        try:
            self._write_project()
            self.toast("Project saved")
        except Exception as exc:
            self.toast("Save failed: %s" % exc)

    def load(self):
        p = self._project_path()
        if not p.exists():
            self.toast("No saved project found")
            return
        self._autoload()
        self.project = self._load_project()
        self.history.reset(self.project.to_dict())
        self.scene.set_project(self.project)
        self.hub.layer = self.project.layer("AUDIOPROVIDER")
        self.scene.set_perf(**self.s.perf)
        if self.native is not None:
            self.native.configure_fx(self.s.audio, self.s.eq_freqs)
        self._apply_ui_theme()
        self.build_ui()
        self.toast("Project loaded")

    def on_stop(self):
        if self.exporter is not None and self.exporter.running:
            self.exporter.cancel()
        self.autosave()
        if self.native is not None:
            self.native.release()
        self.hub.release()


def _fmt_size(n):
    if n >= 1024 ** 3:
        return "%.2f GB" % (n / 1024.0 ** 3)
    if n >= 10 * 1024 ** 2:
        return "%d MB" % round(n / 1024.0 ** 2)
    if n >= 1024 ** 2:
        return "%.1f MB" % (n / 1024.0 ** 2)
    return "%d KB" % max(1, round(n / 1024.0))


def _preset_intensity(name):
    try:
        return float(SHADER_PRESET_TABLE[name][2])
    except Exception:
        return 0.5


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
