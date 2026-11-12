"""Android platform bridge (pyjnius): Storage Access Framework picker,
content-URI copying, track metadata + embedded album art, permissions.

Everything degrades gracefully: on desktop `IS_ANDROID` is False and the app
uses the Kivy file chooser instead.
"""
import os
import zlib
import threading

try:
    from jnius import autoclass, cast  # noqa: F401
    from android import activity as android_activity  # type: ignore
    from android.permissions import request_permissions, check_permission, Permission  # type: ignore
    IS_ANDROID = True
except Exception:  # pragma: no cover - desktop
    IS_ANDROID = False

_REQUEST_BASE = 0x5600
_pending = {}
_bound = False


def _activity():
    return autoclass("org.kivy.android.PythonActivity").mActivity


def _on_activity_result(request_code, result_code, intent):
    callback = _pending.pop(request_code, None)
    if callback is None:
        return
    Activity = autoclass("android.app.Activity")
    uris = []
    if result_code == Activity.RESULT_OK and intent is not None:
        clip = intent.getClipData()
        if clip is not None:
            for i in range(clip.getItemCount()):
                uris.append(clip.getItemAt(i).getUri())
        elif intent.getData() is not None:
            uris.append(intent.getData())
    resolver = _activity().getContentResolver()
    Intent = autoclass("android.content.Intent")
    out = []
    for uri in uris:
        try:
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        except Exception:
            pass
        out.append(uri.toString())
    from kivy.clock import Clock
    Clock.schedule_once(lambda dt: callback(out), 0)


def pick_documents(mime, callback, multiple=False):
    """Open the system picker. callback(list_of_uri_strings)."""
    global _bound
    if not _bound:
        android_activity.bind(on_activity_result=_on_activity_result)
        _bound = True
    Intent = autoclass("android.content.Intent")
    intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)
    intent.setType(mime)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    if multiple:
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, True)
    code = _REQUEST_BASE + len(_pending) + 1
    _pending[code] = callback
    _activity().startActivityForResult(intent, code)


def parse_uri(uri_string):
    Uri = autoclass("android.net.Uri")
    if uri_string.startswith("/"):
        return Uri.fromFile(autoclass("java.io.File")(uri_string))
    return Uri.parse(uri_string)


def display_name(uri_string):
    try:
        resolver = _activity().getContentResolver()
        cursor = resolver.query(parse_uri(uri_string), None, None, None, None)
        if cursor is not None:
            try:
                if cursor.moveToFirst():
                    index = cursor.getColumnIndex("_display_name")
                    if index >= 0:
                        return cursor.getString(index)
            finally:
                cursor.close()
    except Exception:
        pass
    return os.path.basename(uri_string.split("%2F")[-1])


def _java_bytes_to_py(data):
    if data is None:
        return None
    if isinstance(data, (bytes, bytearray)):
        return bytes(data)
    return bytes(bytearray((int(b) + 256) % 256 for b in data))


def copy_to_file(uri_string, directory, name=None):
    """Copy a content:// document into app storage so Kivy can load it."""
    if uri_string.startswith("/"):
        return uri_string
    os.makedirs(directory, exist_ok=True)
    name = name or display_name(uri_string) or "file"
    safe = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
    target = os.path.join(directory, safe)
    resolver = _activity().getContentResolver()
    stream = resolver.openInputStream(parse_uri(uri_string))
    try:
        # Fast path (API 29+): copy entirely inside Java.
        try:
            out = autoclass("java.io.FileOutputStream")(target)
            try:
                autoclass("android.os.FileUtils").copy(stream, out)
                return target
            finally:
                out.close()
        except Exception:
            stream.close()
            stream = resolver.openInputStream(parse_uri(uri_string))
        buf = bytearray(65536)
        with open(target, "wb") as fh:
            while True:
                n = stream.read(buf)
                if n is None or n < 0:
                    break
                fh.write(bytes(buf[:n]))
    finally:
        stream.close()
    return target


def read_metadata(uri_string, art_dir):
    """Returns {"title","artist","album","duration_ms","art"} (art = file path or "")."""
    meta = {"title": "", "artist": "", "album": "", "duration_ms": 0, "art": ""}
    retriever = None
    try:
        MMR = autoclass("android.media.MediaMetadataRetriever")
        retriever = MMR()
        retriever.setDataSource(_activity(), parse_uri(uri_string))
        meta["title"] = retriever.extractMetadata(MMR.METADATA_KEY_TITLE) or ""
        meta["artist"] = retriever.extractMetadata(MMR.METADATA_KEY_ARTIST) or ""
        meta["album"] = retriever.extractMetadata(MMR.METADATA_KEY_ALBUM) or ""
        dur = retriever.extractMetadata(MMR.METADATA_KEY_DURATION)
        meta["duration_ms"] = int(dur) if dur else 0
        art = _java_bytes_to_py(retriever.getEmbeddedPicture())
        if art:
            os.makedirs(art_dir, exist_ok=True)
            ext = ".png" if art[:4] == b"\x89PNG" else ".jpg"
            path = os.path.join(art_dir, "cover_%08x%s" % (zlib.crc32(uri_string.encode("utf-8")), ext))
            with open(path, "wb") as fh:
                fh.write(art)
            meta["art"] = path
    except Exception:
        pass
    finally:
        if retriever is not None:
            try:
                retriever.release()
            except Exception:
                pass
    if not meta["title"]:
        meta["title"] = os.path.splitext(display_name(uri_string))[0]
    return meta


def ensure_permissions(names, callback=None):
    """Request runtime permissions (RECORD_AUDIO is required by Visualizer)."""
    if not IS_ANDROID:
        if callback:
            callback(True)
        return
    perms = [getattr(Permission, n) for n in names if hasattr(Permission, n)]
    missing = [p for p in perms if not check_permission(p)]
    if not missing:
        if callback:
            callback(True)
        return

    def done(permissions, grants):
        if callback:
            from kivy.clock import Clock
            ok = bool(grants) and all(bool(g) for g in grants)
            Clock.schedule_once(lambda dt: callback(ok), 0)

    request_permissions(missing, done)


def run_async(fn, *args):
    threading.Thread(target=fn, args=args, daemon=True).start()
