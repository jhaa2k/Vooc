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
from . import vector_layers  # noqa: F401  (registers WAVEFORM / SHAPE renderers)

MAX_RENDER_SIDE = 1280      # preview cap; keeps phones smooth at 60 fps
C = "Render Settings/"


class Frame:
    """Read-only per-frame context handed to every renderer."""
    __slots__ = ("w", "h", "dt", "time", "measure", "snapshot", "metadata",
                 "position", "duration", "playing")


class CompositePass:
    """Puts an effect's output (scope = Layer Directly Below) back over the layers beneath it."""

    def __init__(self, size, below, top):
        self.size = size
        self.fbo = Fbo(size=size, with_stencilbuffer=False)
        with self.fbo:
            ClearColor(0, 0, 0, 0)
            ClearBuffers()
            Color(1, 1, 1, 1)
            self.below = Rectangle(size=size, pos=(0, 0), texture=below)
            Color(1, 1, 1, 1)
            self.top = Rectangle(size=size, pos=(0, 0), texture=top)

    @property
    def texture(self):
        return self.fbo.texture

    def update(self, frame):
        self.fbo.ask_update()


class LayersPass:
    def __init__(self, size, layers, clear_color, first, clear_each_frame, isolated=False):
        self.fbo = Fbo(size=size, with_stencilbuffer=False)
        self.size = size
        self.first = first
        self.isolated = isolated        # renders one layer alone on transparent (per-layer effect scope)
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
    HEAVY = ("BLUREFFECT", "MOTIONBLUREFFECT")

    def __init__(self, project, hub=None, offline=False, size_override=None, **kw):
        super().__init__(**kw)
        self.project = project
        self.offline = offline                 # export renderer: no clock, fixed dt, full quality
        self.size_override = size_override     # exact output size for export
        # performance controls (preview only; export always renders everything)
        self.perf = {"preview_fps": 60, "render_scale": 1.0, "heavy_blur": True,
                     "max_bars": 256, "max_particles": 2000}
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
        if not offline:
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
            fps = int(self.project.layer("COMPOSITION").get(C + "Output/FPS Limit"))
        except (ValueError, TypeError):
            fps = 60
        if not self.offline:
            fps = min(fps, int(self.perf.get("preview_fps", 60) or 60))
        return max(10, fps)

    def set_perf(self, **values):
        self.perf.update(values)
        self._signature = None           # force rebuild (size / heavy effects may change)
        if not self.offline:
            self.start()

    # ------------------------------------------------------------ geometry
    def render_size(self):
        comp = self.project.layer("COMPOSITION")
        if self.size_override:
            return tuple(int(v) for v in self.size_override)
        w = comp.get(C + "Output/Resolution Width")
        h = comp.get(C + "Output/Resolution Height")
        scale = max(0.25, min(1.0, float(self.perf.get("render_scale", 1.0))))
        k = min(1.0, MAX_RENDER_SIDE * scale / float(max(w, h)))
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
        if layer.module in self.HEAVY and not self.offline and not self.perf.get("heavy_blur", True):
            return False
        if not layer.values.get("General/Layer/Visible", True):
            return False
        parent = self._parent_group(layer)
        if parent is not None and not parent.values.get("General/Layer/Visible", True):
            return False
        return True

    def _parent_group(self, layer):
        name = (layer.values.get("General/Layer/Parent Group") or "").strip()
        if not name or layer.module == "GROUP" or layer.module in EFFECT_MODULES:
            return None
        for g in self.project.layers:
            if g.module == "GROUP" and (g.values.get("General/Layer/Tag") or "").strip() == name:
                return g
        return None

    def _compute_signature(self):
        comp = self.project.layer("COMPOSITION")
        layers = tuple((l.id, l.module, l.values.get("Effect Stack/Mix/Scope"),
                        l.values.get("General/Layer/Parent Group"), l.values.get("General/Layer/Tag"))
                       for l in self.project.layers if self._visible(l))
        return (layers, self.render_size(), self.perf.get("heavy_blur", True), comp.get(C + "Canvas/Clear Canvas on Frame"),
                comp.get("General/Layer/Visible"))

    def set_project(self, project):
        self.project = project
        self._signature = None

    def rebuild(self):
        self._signature = self._compute_signature()
        alive = {l.id for l in self.project.layers}
        for lid in [k for k in self.renderers if k not in alive]:
            self.renderers.pop(lid, None)
            self.shader_errors.pop(lid, None)
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
        members = {}            # group layer id -> member layers (project order)
        for l in visible:
            g = self._parent_group(l)
            if g is not None and g in visible:
                members.setdefault(g.id, []).append(l)
        in_group = {l.id for ls in members.values() for l in ls}
        visible = [l for l in visible if l.id not in in_group]

        def renderer(layer):
            r = self.renderers.get(layer.id)
            if r is None:
                r = self.renderers[layer.id] = RENDERERS[layer.module](layer, self)
            return r

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
                single = layer.values.get("Effect Stack/Mix/Scope") == "Layer Directly Below" and pending
                target = pending.pop() if single else None
                if pending or not self.passes:
                    flush()
                fx = old_effects.get(layer.id)
                if fx is None or fx.size != size:
                    fx = PASSES[layer.module](layer, size)
                    if fx.error:
                        self.shader_errors[layer.id] = fx.error
                self.effects[layer.id] = fx
                if target is not None:
                    below = self.passes[-1].texture
                    iso = LayersPass(size, [target], (0, 0, 0, 0), False, True, isolated=True)
                    self.passes.append(iso)
                    self.pipeline.add(iso.fbo)
                    fx.set_input(iso.texture)
                    self.passes.append(fx)
                    self.pipeline.add(fx.fbo)
                    comp_pass = CompositePass(size, below, fx.texture)
                    self.passes.append(comp_pass)
                    self.pipeline.add(comp_pass.fbo)
                    continue
                fx.set_input(self.passes[-1].texture)
                self.passes.append(fx)
                self.pipeline.add(fx.fbo)
            else:
                r = renderer(layer)
                if layer.module == "GROUP":
                    r.set_members([renderer(m) for m in members.get(layer.id, [])])
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
    def render_frame(self, t, dt):
        """Offline (export) frame at song time ``t``: updates and draws every pass."""
        self._offline_time = t
        self.tick(dt=dt)
        for p in self.passes:
            p.fbo.draw()
        return self.passes[-1].fbo

    def pixels(self):
        """RGBA bytes of the final frame (bottom-up rows, as OpenGL returns them)."""
        return self.passes[-1].fbo.pixels

    def tick(self, *_, dt=None):
        now = time.monotonic()
        if dt is None:
            dt = now - self._last
            self._last = now
        else:
            now = self._start + getattr(self, "_offline_time", 0.0)
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
                            snap["dominant_hz"], frame.playing, snapshot=snap)
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
        self.measure.use_layer(comp.values)
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
            elif p.isolated:
                p.bg_color.rgba = (0, 0, 0, 0)
            else:
                p.bg_color.rgba = (1, 1, 1, 1)
        self.display_color.a = comp.get(C + "Canvas/Alpha / Transparency")
