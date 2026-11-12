"""SceneView: renders the whole ProjectModel on the GPU.

Pipeline (rebuilt only when layer order / visibility changes):

    [layers pass]  -> [effect pass] -> [layers pass] -> ... -> screen
     Fbo: bg + layers   Fbo: shader      Fbo: previous output + layers above

Layers are drawn bottom -> top in ProjectModel.layers order; an effect
processes everything beneath it, layers above it stay untouched. The final
texture is letterboxed into the widget, so the preview keeps the Render
Settings aspect ratio.
"""
import time

from kivy.clock import Clock
from kivy.graphics import ClearBuffers, ClearColor, Color, Fbo, InstructionGroup, Rectangle
from kivy.uix.widget import Widget

from ..measure_engine import MeasureEngine
from ..model import hex_to_rgba
from ..property_schema import EFFECT_MODULES
from .effects import PASSES
from .layers import RENDERERS, LayerTransform

MAX_RENDER_SIDE = 1280      # preview cap; keeps phones smooth at 60 fps
C = "Render Settings/"


class Frame:
    """Read-only per-frame context handed to every renderer."""
    __slots__ = ("w", "h", "dt", "time", "measure", "snapshot", "metadata",
                 "position", "duration", "playing")


class LayersPass:
    def __init__(self, size, layers, clear_color, first, clear_each_frame):
        self.fbo = Fbo(size=size, with_stencilbuffer=False)
        self.size = size
        self.first = first
        with self.fbo:
            self.clear_color = ClearColor(*clear_color)
            if clear_each_frame or not first:
                ClearBuffers()
            self.bg_color = Color(1, 1, 1, 1)
            self.bg = Rectangle(size=size, pos=(0, 0))
        # Composition General transform wraps every layer of the pass
        self.comp = LayerTransform()
        self.comp.begin(self.fbo)
        for r in layers:
            self.fbo.add(r.group)
        self.comp.end(self.fbo)
        self.layers = layers

    @property
    def texture(self):
        return self.fbo.texture

    def set_input(self, texture):
        self.bg.texture = texture


class SceneView(Widget):
    def __init__(self, project, hub=None, **kw):
        super().__init__(**kw)
        self.project = project
        self.hub = hub
        self.measure = MeasureEngine()
        self.renderers = {}        # layer.id -> renderer
        self.effects = {}          # layer.id -> EffectPass
        self.passes = []
        self._signature = None
        self._last = time.monotonic()
        self._start = self._last
        self.fps = 0.0
        self.shader_errors = {}
        self._event = None
        self.listeners = []        # callables(frame) after every frame (UI clocks etc.)
        with self.canvas:
            self.pipeline = InstructionGroup()
            self.display_color = Color(1, 1, 1, 1)
            self.display = Rectangle()
        self.bind(pos=self._layout, size=self._layout)
        self._fps_limit = None
        self.start()

    # ------------------------------------------------------------ timing
    def start(self):
        fps = self._comp_fps()
        if self._event is not None and fps == self._fps_limit:
            return
        if self._event is not None:
            self._event.cancel()
        self._fps_limit = fps
        self._event = Clock.schedule_interval(self.tick, 1.0 / fps)

    def stop(self):
        if self._event is not None:
            self._event.cancel()
            self._event = None

    def _comp_fps(self):
        try:
            return int(self.project.layer("COMPOSITION").get(C + "Output/FPS Limit"))
        except (ValueError, TypeError):
            return 60

    # ------------------------------------------------------------ geometry
    def render_size(self):
        comp = self.project.layer("COMPOSITION")
        w = comp.get(C + "Output/Resolution Width")
        h = comp.get(C + "Output/Resolution Height")
        k = min(1.0, MAX_RENDER_SIDE / float(max(w, h)))
        return max(16, int(w * k)), max(16, int(h * k))

    def _layout(self, *_):
        if not self.passes:
            return
        rw, rh = self.passes[-1].size
        k = min(self.width / float(rw), self.height / float(rh))
        dw, dh = rw * k, rh * k
        self.display.pos = (self.x + (self.width - dw) / 2, self.y + (self.height - dh) / 2)
        self.display.size = (dw, dh)

    # ------------------------------------------------------------ pipeline
    def _visible(self, layer):
        if layer.module == "AUDIOPROVIDER":
            return False
        if layer.module == "COMPOSITION":
            return False
        return bool(layer.values.get("General/Layer/Visible", True))

    def _compute_signature(self):
        comp = self.project.layer("COMPOSITION")
        layers = tuple((l.id, l.module) for l in self.project.layers if self._visible(l))
        return (layers, self.render_size(), comp.get(C + "Canvas/Clear Canvas on Frame"),
                comp.get("General/Layer/Visible"))

    def rebuild(self):
        self._signature = self._compute_signature()
        size = self.render_size()
        comp = self.project.layer("COMPOSITION")
        clear_each = comp.get(C + "Canvas/Clear Canvas on Frame")
        self.pipeline.clear()
        self.passes = []
        old_effects = self.effects
        self.effects = {}
        pending = []
        comp_visible = comp.get("General/Layer/Visible")
        visible = [l for l in self.project.layers if self._visible(l)] if comp_visible else []

        def flush():
            first = not self.passes
            p = LayersPass(size, list(pending), (0, 0, 0, 0), first, clear_each)
            if self.passes:
                p.set_input(self.passes[-1].texture)
            self.passes.append(p)
            self.pipeline.add(p.fbo)
            pending.clear()

        for layer in visible:
            if layer.module in EFFECT_MODULES:
                if pending or not self.passes:
                    flush()
                fx = old_effects.get(layer.id)
                if fx is None or fx.size != size:
                    fx = PASSES[layer.module](layer, size)
                    if fx.error:
                        self.shader_errors[layer.id] = fx.error
                self.effects[layer.id] = fx
                fx.set_input(self.passes[-1].texture)
                self.passes.append(fx)
                self.pipeline.add(fx.fbo)
            else:
                r = self.renderers.get(layer.id)
                if r is None:
                    r = self.renderers[layer.id] = RENDERERS[layer.module](layer, self)
                pending.append(r)
        if pending or not self.passes:
            flush()
        self.display.texture = self.passes[-1].texture
        self._layout()

    def compile_shader(self, layer):
        """Compile / Apply button. Returns an error string ("" = success)."""
        fx = self.effects.get(layer.id)
        if fx is None:
            fx = PASSES[layer.module](layer, self.render_size())
            err = fx.error
        else:
            err = fx.compile()
        self.shader_errors[layer.id] = err
        return err

    # ------------------------------------------------------------ frame
    def tick(self, *_):
        now = time.monotonic()
        dt = now - self._last
        self._last = now
        if dt > 0:
            self.fps = self.fps * 0.9 + (1.0 / dt) * 0.1
        if self._comp_fps() != self._fps_limit:
            self.start()
        if self._compute_signature() != self._signature:
            self.rebuild()
        frame = Frame()
        rw, rh = self.passes[-1].size
        frame.w, frame.h, frame.dt, frame.time = float(rw), float(rh), dt, now - self._start
        hub = self.hub
        if hub is not None:
            snap = hub.update()
            frame.snapshot = snap
            frame.metadata = hub.metadata
            frame.position, frame.duration = hub.position(), hub.duration()
            frame.playing = hub.is_playing()
            beat = hub.beat
        else:
            from ..audio.analysis import empty_snapshot
            frame.snapshot, frame.metadata = empty_snapshot(), {}
            frame.position = frame.duration = 0.0
            frame.playing, beat = False, False
        snap = frame.snapshot
        self.measure.update(dt, beat, snap["rms"], snap["peak"], frame.position, frame.duration,
                            snap["dominant_hz"], frame.playing)
        frame.measure = self.measure
        self._update_composition(frame)
        for p in self.passes:
            if isinstance(p, LayersPass):
                for r in p.layers:
                    try:
                        r.update(frame)
                    except Exception as exc:  # keep rendering other layers
                        self._report(r.layer, exc)
                p.fbo.ask_update()
            else:
                # custom shader edits run only after Compile / Apply (last good shader stays)
                p.update(frame)
        self.canvas.ask_update()
        for cb in list(self.listeners):
            cb(frame)

    def _report(self, layer, exc):
        from kivy.logger import Logger
        Logger.warning("Vooc: %s layer failed: %s", layer.module, exc)

    def _update_composition(self, frame):
        comp = self.project.layer("COMPOSITION")
        bg = hex_to_rgba(comp.get(C + "Canvas/Background Color"))
        clear_each = comp.get(C + "Canvas/Clear Canvas on Frame")
        t = LayerTransform.compute(comp.values, self.measure, frame.w, frame.h, frame.w, frame.h)
        for i, p in enumerate(self.passes):
            if not isinstance(p, LayersPass):
                continue
            p.comp.apply(t)
            if i == 0:
                p.clear_color.rgba = bg if clear_each else (0, 0, 0, 0)
                p.bg.texture = None
                # without clearing, a translucent background fades old frames -> trails
                p.bg_color.rgba = bg if clear_each else bg[:3] + [0.12]
            else:
                p.bg_color.rgba = (1, 1, 1, 1)
        self.display_color.a = comp.get(C + "Canvas/Alpha / Transparency")
