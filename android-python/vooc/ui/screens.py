"""Screen 1 (Main Menu) and Screen 2 (Details View)."""
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.screenmanager import Screen
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

from ..property_schema import EFFECT_MODULES, MODULE_INFO, iter_fields
from .inspector import Inspector
from .theme import FS_BODY, FS_LABEL, FS_SMALL, FS_TITLE, GAP, PAD, T
from .widgets import (Icon, IconButton, Pressable, SliderBar, Surface, confirm, info, label)

SUBTITLES = {
    "COMPOSITION": "Canvas, resolution, FPS", "TEXT": "Titles & metadata",
    "BARS": "Spectrum bars & paths", "PARTICLES": "Emitter & physics",
    "IMAGE": "Album art & masks", "AUDIOPROVIDER": "File / Mic / System",
    "BLUREFFECT": "Multi-pass glow", "RGBSPLITEFFECT": "Chromatic glitch",
    "MOTIONBLUREFFECT": "Velocity blur", "MIRROREREFFECT": "Mirror & kaleidoscope",
    "CUSTOMSHADER": "Your GLSL code",
}


def _fmt_time(s):
    s = max(0, int(s))
    return "%d:%02d" % (s // 60, s % 60)


class Background(FloatLayout):
    def __init__(self, **kw):
        super().__init__(**kw)
        with self.canvas.before:
            Color(*T.bg)
            self._r = Rectangle()
        self.bind(pos=lambda *_: setattr(self._r, "pos", self.pos),
                  size=lambda *_: setattr(self._r, "size", self.size))


class PreviewHost(Surface):
    """Holder for the single shared SceneView (moved between screens)."""

    def __init__(self, **kw):
        super().__init__(bg=[0, 0, 0, 1], radius=dp(22), padding=dp(2), **kw)


def adopt(host, scene):
    if scene.parent is not None:
        scene.parent.remove_widget(scene)
    host.add_widget(scene)


class TopBar(BoxLayout):
    def __init__(self, title, left=None, right=(), subtitle="", **kw):
        super().__init__(size_hint_y=None, height=dp(60), padding=(dp(6), 0, dp(6), 0),
                         spacing=dp(2), **kw)
        if left:
            self.add_widget(IconButton(left[0], left[1], pos_hint={"center_y": .5}))
        else:
            self.add_widget(Widget(size_hint_x=None, width=dp(10)))
        col = BoxLayout(orientation="vertical", padding=(dp(4), dp(8)))
        self.title = label(title, FS_TITLE, bold=True)
        col.add_widget(self.title)
        if subtitle:
            col.add_widget(label(subtitle, FS_SMALL, color=T.muted))
        self.add_widget(col)
        for icon, cb in right:
            self.add_widget(IconButton(icon, cb, pos_hint={"center_y": .5}))


class PlayerBar(Surface):
    """Track info, seek bar and transport (file source)."""

    def __init__(self, app, **kw):
        super().__init__(orientation="vertical", bg=T.surface, radius=dp(22),
                         padding=(dp(14), dp(8)), spacing=dp(2), size_hint_y=None, height=dp(132), **kw)
        self.app = app
        info_row = BoxLayout(size_hint_y=None, height=dp(40))
        col = BoxLayout(orientation="vertical")
        self.title = label("No track loaded", FS_BODY, bold=True)
        self.artist = label("Tap the folder to add music", FS_SMALL, color=T.muted)
        col.add_widget(self.title)
        col.add_widget(self.artist)
        info_row.add_widget(col)
        self.source_chip = label("", FS_SMALL, color=T.primary, bold=True, halign="right",
                                 size_hint_x=None, width=dp(100))
        info_row.add_widget(self.source_chip)
        self.add_widget(info_row)
        seek_row = BoxLayout(size_hint_y=None, height=dp(30), spacing=dp(6))
        self.pos_lbl = label("0:00", FS_SMALL, color=T.muted, size_hint_x=None, width=dp(40))
        self.seek = SliderBar(min=0, max=1)
        self.seek.on_commit = lambda v: app.seek_fraction(v)
        self.dur_lbl = label("0:00", FS_SMALL, color=T.muted, size_hint_x=None, width=dp(40),
                             halign="right")
        seek_row.add_widget(self.pos_lbl)
        seek_row.add_widget(self.seek)
        seek_row.add_widget(self.dur_lbl)
        self.add_widget(seek_row)
        ctl = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(8))
        ctl.add_widget(IconButton("folder", app.open_tracks, pos_hint={"center_y": .5}))
        ctl.add_widget(Widget())
        ctl.add_widget(IconButton("prev", app.prev_track, pos_hint={"center_y": .5}))
        self.play_btn = IconButton("play", app.toggle_play, tint=T.on_primary, bg=T.primary,
                                   size=(dp(52), dp(52)), pos_hint={"center_y": .5})
        ctl.add_widget(self.play_btn)
        ctl.add_widget(IconButton("next", app.next_track, pos_hint={"center_y": .5}))
        ctl.add_widget(Widget())
        ctl.add_widget(IconButton("queue", app.show_queue, pos_hint={"center_y": .5}))
        self.add_widget(ctl)

    def update(self, frame):
        hub = self.app.hub
        meta = hub.metadata
        if hub.current_uri:
            self.title.text = meta.get("title") or "Unknown title"
            self.artist.text = meta.get("artist") or meta.get("album") or "Unknown artist"
        kind = hub.kind or "File"
        self.source_chip.text = kind.upper()
        dur = frame.duration
        if dur > 0 and not self.seek._dragging:
            self.seek.value = min(1.0, frame.position / dur)
        self.pos_lbl.text = _fmt_time(frame.position)
        self.dur_lbl.text = _fmt_time(dur)
        icon = "pause" if (frame.playing and hub.provider and hub.provider.has_transport) else "play"
        if self.play_btn.icon.name != icon:
            self.play_btn.icon.name = icon


class ModuleTile(Pressable):
    def __init__(self, module, layer, on_open, **kw):
        super().__init__(bg=T.surface, orientation="vertical", radius=dp(20),
                         padding=(dp(14), dp(12)), size_hint_y=None, height=dp(92), **kw)
        self.module, self.layer = module, layer
        top = BoxLayout(size_hint_y=None, height=dp(22))
        self.dot = Icon(name="dot", size_hint_x=None, width=dp(16))
        top.add_widget(self.dot)
        top.add_widget(Widget())
        kind = "FX" if module in EFFECT_MODULES else ("CORE" if module in ("COMPOSITION", "AUDIOPROVIDER") else "LAYER")
        top.add_widget(label(kind, FS_SMALL, color=T.muted, halign="right", size_hint_x=None, width=dp(50)))
        self.add_widget(top)
        self.add_widget(label(module, FS_LABEL, bold=True))
        self.add_widget(label(SUBTITLES.get(module, ""), FS_SMALL, color=T.muted))
        self.bind(on_release=lambda *_: on_open(module))
        self.refresh()

    def refresh(self):
        visible = self.layer.values.get("General/Layer/Visible",
                                        self.layer.values.get("General/Routing/Visible", True))
        self.dot.tint = T.primary if visible else T.outline


class MainMenu(Screen):
    def __init__(self, app, **kw):
        super().__init__(name="menu", **kw)
        self.app = app
        root = Background()
        col = BoxLayout(orientation="vertical", padding=(dp(12), dp(4), dp(12), 0), spacing=GAP)
        col.add_widget(TopBar("VOOC", right=[("theme", app.toggle_theme), ("info", self._about)],
                              subtitle="Audio visualizer studio"))
        self.preview = PreviewHost(size_hint_y=.42)
        col.add_widget(self.preview)
        self.player = PlayerBar(app)
        col.add_widget(self.player)
        scroll = ScrollView(do_scroll_x=False, size_hint_y=.58)
        grid = GridLayout(cols=2, spacing=GAP, padding=(0, dp(4), 0, dp(24)), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))
        self.tiles = []
        for module, layer in app.pvm.menu_items():
            tile = ModuleTile(module, layer, app.open_module)
            self.tiles.append(tile)
            grid.add_widget(tile)
        scroll.add_widget(grid)
        col.add_widget(scroll)
        root.add_widget(col)
        self.add_widget(root)

    def _about(self):
        n = sum(len(list(iter_fields(m))) for m in MODULE_INFO)
        info("About Vooc", "Avee-style visualizer. 11 modules, %d editable properties.\n\n"
             "Layers render bottom to top. Effects (BLUR, RGB, MOTION, MIRROR, SHADER) process "
             "everything beneath them. Every property is saved automatically and restored on "
             "the next launch.\n\nTap a module to edit it." % n)

    def on_pre_enter(self, *_):
        adopt(self.preview, self.app.scene)
        for t in self.tiles:
            t.refresh()


class DetailsScreen(Screen):
    def __init__(self, app, module, **kw):
        super().__init__(name="details", **kw)
        self.app, self.module = app, module
        self.vm = app.pvm.layer_vm(module)
        root = Background()
        col = BoxLayout(orientation="vertical", padding=(dp(8), dp(4), dp(8), 0), spacing=dp(6))
        col.add_widget(TopBar(module, left=("back", app.go_menu),
                              right=[("reset", self._reset), ("info", self._info)],
                              subtitle=self.vm.layer.values.get("General/Layer/Tag", "") or "Layer settings"))
        self.preview = PreviewHost(size_hint_y=None, height=dp(200))
        col.add_widget(self.preview)
        self.scroll = ScrollView(do_scroll_x=False, bar_color=T.primary, bar_width=dp(3))
        self.inspector = Inspector(self.vm, app)
        self.scroll.add_widget(self.inspector)
        col.add_widget(self.scroll)
        root.add_widget(col)
        self.add_widget(root)

    def _reset(self):
        confirm("Reset %s?" % self.module, "All properties of this module go back to their defaults.",
                lambda: (self.vm.reset(), self.app.toast("%s reset" % self.module)))

    def _info(self):
        lines = [MODULE_INFO.get(self.module, ""), ""]
        for path, spec in iter_fields(self.module):
            if spec.get("help"):
                lines.append("[b]%s[/b]: %s" % (path.split("/")[-1], spec["help"]))
        lines.append("\nSliders are normalized 0.000-1.000 unless marked otherwise. Colors are "
                     "#RRGGBBAA hex. Measure: Nothing = static, Beat = jumps on kicks (A = amount, "
                     "B = speed), TotalTime = follows playback time.")
        info(self.module, "\n".join(lines))

    def on_pre_enter(self, *_):
        adopt(self.preview, self.app.scene)

    def on_leave(self, *_):
        self.inspector.release()
