"""Track metadata + embedded album art (title, artist, album, art file).

Android: android.media.MediaMetadataRetriever (works for mp3, m4a, flac, ogg ...).
Desktop: ffprobe / ffmpeg when installed. Always falls back to the file name,
so TEXT layers (%title% / %artist%) and IMAGE album art never break.
"""
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

ON_ANDROID = "ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ


def _jbytes(arr):
    if arr is None:
        return None
    if isinstance(arr, (bytes, bytearray)):
        return bytes(arr)
    for attr in ("tostring", "tobytes"):
        if hasattr(arr, attr):
            try:
                return bytes(getattr(arr, attr)())
            except Exception:
                pass
    return bytes((int(x) & 0xFF) for x in arr)


def _art_path(cache_dir, path, data):
    ext = ".png" if data[:4] == b"\x89PNG" else ".jpg"
    name = "art_" + hashlib.md5(str(path).encode("utf-8", "ignore")).hexdigest()[:12] + ext
    out = Path(cache_dir) / name
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
    return str(out)


def read_metadata(path, cache_dir):
    meta = {"title": Path(path).stem, "artist": "", "album": "", "art": ""}
    try:
        if ON_ANDROID:
            meta.update({k: v for k, v in _android(path, cache_dir).items() if v})
        else:
            meta.update({k: v for k, v in _desktop(path, cache_dir).items() if v})
    except Exception as exc:
        print("Vooc metadata: %s" % exc)
    return meta


def _android(path, cache_dir):
    from jnius import autoclass
    MMR = autoclass("android.media.MediaMetadataRetriever")
    r = MMR()
    out = {}
    try:
        r.setDataSource(str(path))
        out["title"] = r.extractMetadata(7) or ""      # METADATA_KEY_TITLE
        out["artist"] = r.extractMetadata(2) or ""     # METADATA_KEY_ARTIST
        out["album"] = r.extractMetadata(1) or ""      # METADATA_KEY_ALBUM
        data = _jbytes(r.getEmbeddedPicture())
        if data:
            out["art"] = _art_path(cache_dir, path, data)
    finally:
        try:
            r.release()
        except Exception:
            pass
    return out


def _desktop(path, cache_dir):
    out = {}
    if shutil.which("ffprobe"):
        try:
            res = subprocess.run(["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", str(path)],
                                 capture_output=True, timeout=10)
            tags = {k.lower(): v for k, v in json.loads(res.stdout or b"{}").get("format", {}).get("tags", {}).items()}
            out["title"], out["artist"], out["album"] = tags.get("title", ""), tags.get("artist", ""), tags.get("album", "")
        except Exception:
            pass
    if shutil.which("ffmpeg"):
        try:
            res = subprocess.run(["ffmpeg", "-v", "quiet", "-i", str(path), "-an", "-frames:v", "1",
                                  "-c:v", "mjpeg", "-f", "image2pipe", "-"], capture_output=True, timeout=15)
            if res.stdout:
                out["art"] = _art_path(cache_dir, path, res.stdout)
        except Exception:
            pass
    return out
