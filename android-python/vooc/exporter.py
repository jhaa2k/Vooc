"""Video export: offline render of the scene + hardware/ffmpeg encoding.

Every frame is rendered by a hidden SceneView at an exact song time
(frame_index / fps) with a fixed time step, so the exported video is always in
sync with the audio, regardless of how fast the phone renders.

    plan_export()  resolution / fps / aspect / codec / bitrate + 4 GB size cap
    Exporter       drives rendering on the GL thread (Kivy Clock) and streams
                   frames to an encoder sink:
                     AndroidSink  - com.jhaa2k.vooc.VideoExporter (MediaCodec + MediaMuxer)
                     FFmpegSink   - desktop, ffmpeg via a pipe

Fallbacks: no HEVC encoder -> H.264 (Java side); size rejected by the encoder or
out of memory -> next lower resolution; projected file over 4 GB -> lower
bitrate, then lower resolution; size guard while writing stops cleanly at 4 GB.
"""
import math
import os
import shutil
import subprocess
import time

import numpy as np

ON_ANDROID = "ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ

RESOLUTIONS = ["SD 480p", "HD 720p", "FHD 1080p", "QHD 1440p", "4K 2160p"]
SHORT_SIDE = {"SD 480p": 480, "HD 720p": 720, "FHD 1080p": 1080, "QHD 1440p": 1440, "4K 2160p": 2160}
BASE_MBPS = {"SD 480p": 2.5, "HD 720p": 5.0, "FHD 1080p": 10.0, "QHD 1440p": 20.0, "4K 2160p": 40.0}
FPS_OPTIONS = ["25", "30", "50", "60"]
ASPECTS = {"16:9": (16, 9), "4:3": (4, 3), "21:10": (21, 10), "9:16": (9, 16), "1:1": (1, 1)}
CODECS = {"H.264 (AVC)": ("video/avc", "libx264", 1.0), "H.265 (HEVC)": ("video/hevc", "libx265", 0.6)}
QUALITY = {"Small": 0.6, "Standard": 1.0, "High": 1.5}
AUDIO_BPS = 192000
LIMIT_BYTES = 4 * 1024 ** 3 - 64 * 1024 ** 2      # FAT32 / MP4 safe cap with headroom


def frame_size(resolution, aspect):
    short = SHORT_SIDE[resolution]
    a, b = ASPECTS.get(aspect, (16, 9))
    if a >= b:
        h, w = short, short * a / float(b)
    else:
        w, h = short, short * b / float(a)
    w = int(round(w / 16.0)) * 16          # encoder friendly
    h = int(round(h / 2.0)) * 2
    return max(16, w), max(16, h)


def video_bitrate(resolution, fps, codec, quality="Standard"):
    k = BASE_MBPS[resolution] * (float(fps) / 30.0) ** 0.6 * CODECS[codec][2] * QUALITY.get(quality, 1.0)
    return int(k * 1e6)


def plan_export(settings, duration):
    """settings: resolution, fps, aspect, codec, audio (bool), quality. Returns a plan dict."""
    res = settings.get("resolution", "FHD 1080p")
    fps = int(settings.get("fps", 30))
    aspect = settings.get("aspect", "16:9")
    codec = settings.get("codec", "H.264 (AVC)")
    quality = settings.get("quality", "Standard")
    audio = bool(settings.get("audio", True))
    notes = []
    duration = max(0.5, float(duration or 0))
    abps = AUDIO_BPS if audio else 0
    order = RESOLUTIONS[:RESOLUTIONS.index(res) + 1][::-1]
    for r in order:
        vb = video_bitrate(r, fps, codec, quality)
        est = (vb + abps) * duration / 8.0 * 1.02
        if est <= LIMIT_BYTES:
            break
        # lower the bitrate (but not below 40% of nominal) before dropping resolution
        fit = int(LIMIT_BYTES * 8.0 / duration / 1.02 - abps)
        if fit >= vb * 0.4:
            notes.append("Bitrate lowered to %.1f Mbps to stay under 4 GB" % (fit / 1e6))
            vb = fit
            est = (vb + abps) * duration / 8.0 * 1.02
            break
        notes.append("%s would exceed 4 GB, trying a lower resolution" % r)
    else:
        vb = max(500000, int(LIMIT_BYTES * 8.0 / duration / 1.02 - abps))
        est = (vb + abps) * duration / 8.0 * 1.02
    if r != res:
        notes.append("Resolution reduced to %s" % r)
    w, h = frame_size(r, aspect)
    return {"resolution": r, "width": w, "height": h, "fps": fps, "aspect": aspect, "codec": codec,
            "mime": CODECS[codec][0], "ffmpeg_codec": CODECS[codec][1], "bitrate": int(vb), "audio": audio,
            "duration": duration, "frames": int(math.ceil(duration * fps)), "estimate_bytes": int(est),
            "notes": notes}


def lower_resolution(plan):
    i = RESOLUTIONS.index(plan["resolution"])
    if i == 0:
        return None
    r = RESOLUTIONS[i - 1]
    p = dict(plan)
    p["resolution"] = r
    p["width"], p["height"] = frame_size(r, plan["aspect"])
    p["bitrate"] = min(plan["bitrate"], video_bitrate(r, plan["fps"], plan["codec"]))
    p["notes"] = list(plan["notes"]) + ["Fell back to %s" % r]
    return p


def rgba_to_i420(pixels, w, h, flip=True):
    """RGBA bytes (OpenGL bottom-up) -> I420 bytes (BT.601 limited range)."""
    a = np.frombuffer(pixels, np.uint8).reshape(h, w, 4)
    if flip:
        a = a[::-1]
    r = a[:, :, 0].astype(np.int32)
    g = a[:, :, 1].astype(np.int32)
    b = a[:, :, 2].astype(np.int32)
    y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16
    # 2x2 average for chroma
    r2 = (r[0::2, 0::2] + r[1::2, 0::2] + r[0::2, 1::2] + r[1::2, 1::2]) >> 2
    g2 = (g[0::2, 0::2] + g[1::2, 0::2] + g[0::2, 1::2] + g[1::2, 1::2]) >> 2
    b2 = (b[0::2, 0::2] + b[1::2, 0::2] + b[0::2, 1::2] + b[1::2, 1::2]) >> 2
    u = ((-38 * r2 - 74 * g2 + 112 * b2 + 128) >> 8) + 128
    v = ((112 * r2 - 94 * g2 - 18 * b2 + 128) >> 8) + 128
    out = np.empty(w * h * 3 // 2, np.uint8)
    out[:w * h] = np.clip(y, 0, 255).reshape(-1)
    q = (w // 2) * (h // 2)
    out[w * h:w * h + q] = np.clip(u, 0, 255).reshape(-1)
    out[w * h + q:] = np.clip(v, 0, 255).reshape(-1)
    return out.tobytes()


# ---------------------------------------------------------------- sinks
class FFmpegSink:
    """Desktop encoder (also used by the tests)."""

    def __init__(self, plan, out_path, audio_path):
        self.plan, self.out_path = plan, out_path
        ff = shutil.which("ffmpeg")
        if not ff:
            raise RuntimeError("ffmpeg is not installed")
        w, h = plan["width"], plan["height"]
        cmd = [ff, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "%dx%d" % (w, h),
               "-r", str(plan["fps"]), "-i", "-"]
        if plan["audio"] and audio_path:
            cmd += ["-i", audio_path, "-map", "0:v", "-map", "1:a", "-c:a", "aac", "-b:a", "192k", "-shortest"]
        cmd += ["-vf", "vflip", "-c:v", plan["ffmpeg_codec"], "-b:v", str(plan["bitrate"]), "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", out_path]
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
        self.error = ""
        self.limit_reached = False
        self.mime = plan["mime"]

    def ready(self):
        return True

    def write(self, rgba, w, h, pts_us):
        try:
            self.proc.stdin.write(rgba)
            return True
        except Exception as exc:
            self.error = "ffmpeg: %s" % exc
            return False

    def bytes_written(self):
        try:
            return os.path.getsize(self.out_path)
        except OSError:
            return 0

    def finish(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        err = self.proc.stderr.read().decode("utf-8", "ignore")
        code = self.proc.wait()
        if code != 0:
            self.error = err.strip()[-300:] or ("ffmpeg exit %d" % code)
        return code == 0

    def cancel(self):
        try:
            self.proc.kill()
        except Exception:
            pass


class AndroidSink:
    def __init__(self, plan, out_path, audio_path):
        from jnius import autoclass
        VE = autoclass("com.jhaa2k.vooc.VideoExporter")
        self.plan = plan
        self.java = VE(out_path, plan["width"], plan["height"], plan["fps"], plan["bitrate"], plan["mime"],
                       audio_path or "", bool(plan["audio"] and audio_path), LIMIT_BYTES)
        if not self.java.start():
            raise RuntimeError(self.java.getError() or "encoder failed to start")
        self.mime = self.java.getMime()
        self.error = ""

    @property
    def limit_reached(self):
        return bool(self.java.isLimitReached())

    def ready(self):
        return bool(self.java.isAudioReady())

    def write(self, rgba, w, h, pts_us):
        ok = self.java.writeFrame(rgba_to_i420(rgba, w, h), int(pts_us))
        if not ok:
            self.error = self.java.getError()
        return ok

    def bytes_written(self):
        return int(self.java.getBytesWritten())

    def finish(self):
        ok = self.java.finish()
        if not ok:
            self.error = self.java.getError()
        audio_err = self.java.getAudioError()
        if audio_err:
            self.error = (self.error + " " + audio_err).strip()
        return ok

    def cancel(self):
        try:
            self.java.cancel()
            self.java.finish()
        except Exception:
            pass


def keep_screen_on(on):
    if not ON_ANDROID:
        return
    try:
        from android.runnable import run_on_ui_thread
        from jnius import autoclass

        @run_on_ui_thread
        def apply():
            act = autoclass("org.kivy.android.PythonActivity").mActivity
            win = act.getWindow()
            flag = 128                       # WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if on:
                win.addFlags(flag)
            else:
                win.clearFlags(flag)
        apply()
    except Exception as exc:
        print("Vooc keep screen on: %s" % exc)


# ---------------------------------------------------------------- driver
class Exporter:
    """Runs on the Kivy main thread. Callbacks: on_progress(frac, text), on_done(ok, path, message)."""

    FRAME_BUDGET = 0.030      # seconds of rendering per Clock tick (keeps the UI responsive)

    def __init__(self, project, timeline, audio_path, metadata, settings, out_path,
                 on_progress=None, on_done=None, sink_factory=None):
        self.project, self.timeline, self.audio_path = project, timeline, audio_path
        self.metadata = dict(metadata or {})
        self.settings, self.out_path = dict(settings), out_path
        self.on_progress = on_progress or (lambda f, t: None)
        self.on_done = on_done or (lambda ok, p, m: None)
        self.sink_factory = sink_factory or (AndroidSink if ON_ANDROID else FFmpegSink)
        self.plan = None
        self.sink = None
        self.scene = None
        self.index = 0
        self.cancelled = False
        self.running = False
        self._event = None
        self._t0 = 0.0

    # ---- setup with resolution fallback
    def start(self):
        from kivy.clock import Clock
        duration = getattr(self.timeline, "duration", 0.0) or 0.0
        if self.timeline is None or duration <= 0:
            self.on_done(False, None, "Load a track and wait for the analysis before exporting")
            return False
        if not getattr(self.timeline, "complete", True):
            self.on_done(False, None, "The track is still being analysed, try again in a moment")
            return False
        self.plan = plan_export(self.settings, duration)
        while True:
            try:
                self._build(self.plan)
                break
            except MemoryError:
                msg = "Out of memory at %s" % self.plan["resolution"]
            except Exception as exc:
                msg = str(exc)
            self._teardown()
            nxt = lower_resolution(self.plan)
            if nxt is None:
                self.on_done(False, None, "Export failed: " + msg)
                return False
            print("Vooc export: %s -> retry at %s" % (msg, nxt["resolution"]))
            self.plan = nxt
        keep_screen_on(True)
        self.running = True
        self._t0 = time.monotonic()
        self._event = Clock.schedule_interval(self._step, 0)
        return True

    def _build(self, plan):
        from copy import deepcopy
        from .audio.timeline_hub import TimelineHub
        from .engine.scene import SceneView
        from .model import ProjectModel
        project = ProjectModel.from_dict(deepcopy(self.project.to_dict()))
        audio = project.layer("AUDIOPROVIDER")
        audio.set("General/Routing/Audio Source", "File")
        comp = project.layer("COMPOSITION")
        comp.set("Render Settings/Output/Resolution Width", plan["width"])
        comp.set("Render Settings/Output/Resolution Height", plan["height"])
        self.t = 0.0
        hub = TimelineHub(audio, timeline=lambda: self.timeline, position=lambda: self.t,
                          playing=lambda: True, duration=lambda: plan["duration"])
        hub.metadata.update(self.metadata)
        hub.fallback = False
        self.scene = SceneView(project, hub, offline=True, size_override=(plan["width"], plan["height"]))
        self.scene.size = (plan["width"], plan["height"])
        self.scene.rebuild()
        self.scene.render_frame(0.0, 1.0 / plan["fps"])
        px = self.scene.pixels()
        if len(px) != plan["width"] * plan["height"] * 4:
            raise RuntimeError("GPU could not render %dx%d" % (plan["width"], plan["height"]))
        self.sink = self.sink_factory(plan, self.out_path, self.audio_path if plan["audio"] else None)

    def _teardown(self):
        if self.scene is not None:
            self.scene.stop()
            self.scene.passes = []
            self.scene.renderers = {}
            self.scene.effects = {}
            self.scene = None

    def cancel(self):
        self.cancelled = True

    def _step(self, _dt):
        plan = self.plan
        if self.cancelled:
            return self._finish(False, "Export cancelled")
        if not self.sink.ready():
            self.on_progress(0.0, "Preparing audio...")
            return
        deadline = time.monotonic() + self.FRAME_BUDGET
        dt = 1.0 / plan["fps"]
        while self.index < plan["frames"] and time.monotonic() < deadline:
            self.t = self.index * dt
            self.scene.render_frame(self.t, dt)
            if not self.sink.write(self.scene.pixels(), plan["width"], plan["height"], int(self.t * 1e6)):
                return self._finish(False, "Encoding failed: " + (self.sink.error or "unknown error"))
            self.index += 1
            if self.sink.limit_reached:
                return self._finish(True, "Stopped at the 4 GB file size limit")
        frac = self.index / float(max(1, plan["frames"]))
        el = time.monotonic() - self._t0
        eta = el / frac - el if frac > 0.01 else 0
        self.on_progress(frac, "%d / %d frames  -  %s left" % (self.index, plan["frames"], _fmt(eta)))
        if self.index >= plan["frames"]:
            return self._finish(True, "")

    def _finish(self, ok, message):
        if self._event is not None:
            self._event.cancel()
            self._event = None
        self.running = False
        keep_screen_on(False)
        if self.sink is not None:
            if self.cancelled:
                self.sink.cancel()
                ok = False
            else:
                done = self.sink.finish()
                if not done:
                    ok = False
                    message = (message + " " + (self.sink.error or "")).strip()
                elif self.sink.error:      # e.g. audio could not be added
                    message = (message + " " + self.sink.error).strip()
        self._teardown()
        if not ok and os.path.exists(self.out_path) and self.cancelled:
            try:
                os.remove(self.out_path)
            except OSError:
                pass
        notes = "; ".join(self.plan.get("notes", [])) if self.plan else ""
        self.on_done(ok, self.out_path if ok else None, "; ".join(x for x in (message, notes) if x))
        return False


def _fmt(sec):
    sec = int(max(0, sec))
    return "%d:%02d" % (sec // 60, sec % 60)
