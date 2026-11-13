"""Android document picker and safe local media import helpers.

Android's Storage Access Framework returns content:// URIs, not filesystem paths.
Vooc copies the selected document into its private cache before handing it to the
existing playback/image pipeline. Desktop continues to use Plyer's file chooser.
"""
import mimetypes
import os
import re
import threading

_PENDING = {}
_BOUND = False
_AUDIO_REQUEST = 61421
_IMAGE_REQUEST = 61422


def _safe_name(value):
    name = os.path.basename(str(value or ""))
    name = re.sub(r"[^A-Za-z0-9._ -]", "_", name).strip(" .")
    return name or "imported_media"


def _extension_for_mime(mime):
    mime = str(mime or "").lower().split(";", 1)[0].strip()
    known = {
        "audio/mpeg": ".mp3", "audio/mp3": ".mp3",
        "audio/mp4": ".m4a", "audio/x-m4a": ".m4a",
        "audio/aac": ".aac", "audio/x-aac": ".aac",
        "audio/wav": ".wav", "audio/x-wav": ".wav",
        "audio/ogg": ".ogg", "application/ogg": ".ogg",
        "audio/flac": ".flac", "audio/x-flac": ".flac",
        "audio/opus": ".opus", "audio/webm": ".webm", "audio/x-matroska": ".mka",
        "audio/amr": ".amr", "audio/3gpp": ".3gp", "audio/aiff": ".aif", "audio/x-aiff": ".aif",
        "audio/midi": ".mid", "audio/x-midi": ".mid", "audio/x-ms-wma": ".wma",
        "video/mp4": ".mp4", "video/webm": ".webm", "video/x-matroska": ".mkv", "video/3gpp": ".3gp",
        "image/jpeg": ".jpg", "image/png": ".png",
        "image/webp": ".webp",
    }
    return known.get(mime) or mimetypes.guess_extension(mime) or ""


def _materialize_android_uri(value):
    if not value:
        return None
    value = str(value)
    if value.startswith("file://"):
        try:
            from urllib.parse import unquote, urlparse
            value = unquote(urlparse(value).path)
        except Exception:
            value = value[7:]
    if not value.startswith("content://"):
        return value if os.path.isfile(value) else None

    try:
        from jnius import autoclass
        PythonActivity = autoclass("org.kivy.android.PythonActivity")
        Uri = autoclass("android.net.Uri")
        OpenableColumns = autoclass("android.provider.OpenableColumns")
        Channels = autoclass("java.nio.channels.Channels")
        ByteBuffer = autoclass("java.nio.ByteBuffer")
        FileOutputStream = autoclass("java.io.FileOutputStream")
        activity = PythonActivity.mActivity
        resolver = activity.getContentResolver()
        uri = Uri.parse(value)

        display_name = None
        cursor = None
        try:
            cursor = resolver.query(uri, [OpenableColumns.DISPLAY_NAME], None, None, None)
            if cursor is not None and cursor.moveToFirst():
                display_name = str(cursor.getString(0))
        except Exception:
            display_name = None
        finally:
            if cursor is not None:
                try:
                    cursor.close()
                except Exception:
                    pass

        display_name = _safe_name(display_name or uri.getLastPathSegment())
        mime = resolver.getType(uri)
        if not os.path.splitext(display_name)[1]:
            display_name += _extension_for_mime(mime) or ".media"

        cache_dir = str(activity.getCacheDir().getAbsolutePath())
        target = os.path.join(cache_dir, display_name)
        base, ext = os.path.splitext(target)
        suffix = 1
        while os.path.exists(target):
            target = "%s_%d%s" % (base, suffix, ext)
            suffix += 1

        input_stream = resolver.openInputStream(uri)
        if input_stream is None:
            raise IOError("Android could not open the selected document")
        output_stream = FileOutputStream(target)
        input_channel = output_channel = None
        try:
            input_channel = Channels.newChannel(input_stream)
            output_channel = output_stream.getChannel()
            buffer = ByteBuffer.allocateDirect(65536)
            while True:
                count = input_channel.read(buffer)
                if count == -1:
                    break
                if count == 0:
                    continue
                buffer.flip()
                while buffer.hasRemaining():
                    output_channel.write(buffer)
                buffer.clear()
        finally:
            for stream in (input_channel, input_stream, output_channel, output_stream):
                try:
                    if stream is not None:
                        stream.close()
                except Exception:
                    pass

        if not os.path.isfile(target) or os.path.getsize(target) <= 0:
            raise IOError("The selected document was empty or could not be copied")
        return target
    except Exception as exc:
        print("Vooc media import failed: %s" % exc)
        return None


def _deliver(callback, path):
    try:
        from kivy.clock import Clock
        Clock.schedule_once(lambda _dt: callback(path), 0)
    except Exception:
        callback(path)


def _on_android_activity_result(request_code, result_code, data):
    try:
        request_code = int(request_code)
    except Exception:
        return
    callback = _PENDING.pop(request_code, None)
    if callback is None:
        return
    try:
        from jnius import autoclass
        Activity = autoclass("android.app.Activity")
        if data is None or int(result_code) != int(Activity.RESULT_OK):
            _deliver(callback, None)
            return
        uri = data.getData()
        if uri is None:
            _deliver(callback, None)
            return
        uri_text = str(uri.toString())
        try:
            flags = int(data.getFlags())
            read_flag = int(autoclass("android.content.Intent").FLAG_GRANT_READ_URI_PERMISSION)
            persist_flag = int(autoclass("android.content.Intent").FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if flags & persist_flag:
                autoclass("org.kivy.android.PythonActivity").mActivity.getContentResolver().takePersistableUriPermission(
                    uri, read_flag
                )
        except Exception:
            pass

        def copy_and_deliver():
            path = _materialize_android_uri(uri_text)
            _deliver(callback, path)
        threading.Thread(target=copy_and_deliver, daemon=True).start()
    except Exception as exc:
        print("Vooc document selection failed: %s" % exc)
        _deliver(callback, None)


def _open_android_picker(callback, mime_type, request_code, title):
    global _BOUND
    try:
        from android import activity, mActivity
        from jnius import autoclass
        Intent = autoclass("android.content.Intent")
        if not _BOUND:
            activity.bind(on_activity_result=_on_android_activity_result)
            _BOUND = True
        intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType(mime_type)
        if mime_type == "audio/*":
            # some file managers label .opus/.mka/.webm/.mp4 music as video or ogg
            try:
                intent.setType("*/*")
                intent.putExtra(Intent.EXTRA_MIME_TYPES, ["audio/*", "application/ogg", "video/mp4", "video/webm",
                                                          "video/x-matroska", "video/3gpp"])
            except Exception as exc:
                print("Vooc picker mime list unavailable: %s" % exc)
                intent.setType(mime_type)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        _PENDING[request_code] = callback
        mActivity.startActivityForResult(intent, request_code)
        return True
    except Exception as exc:
        _PENDING.pop(request_code, None)
        print("Vooc Android picker unavailable, using Plyer fallback: %s" % exc)
        return False


def _open_picker(callback, mime_type, filters, request_code, title):
    if _open_android_picker(callback, mime_type, request_code, title):
        return
    try:
        from plyer import filechooser
        def selected(files):
            if not files:
                callback(None)
                return
            path = _materialize_android_uri(files[0])
            callback(path)
        filechooser.open_file(on_selection=selected, filters=filters, title=title)
    except Exception as exc:
        print("Vooc file picker failed: %s" % exc)
        callback(None)


def choose_audio(callback):
    """Open Android's audio document picker and return a readable local path."""
    _open_picker(
        callback, "audio/*",
        ["*.mp3", "*.wav", "*.flac", "*.ogg", "*.oga", "*.m4a", "*.aac", "*.opus", "*.amr",
         "*.3gp", "*.mka", "*.webm", "*.mp4", "*.mid", "*.aif", "*.aiff", "*.wma"],
        _AUDIO_REQUEST, "Choose music"
    )


def choose_image(callback):
    """Open Android's image document picker and return a readable local path."""
    _open_picker(
        callback, "image/*",
        ["*.png", "*.jpg", "*.jpeg", "*.webp"],
        _IMAGE_REQUEST, "Choose image"
    )


_FILE_REQUEST = 61423

def choose_template(callback):
    """Pick a Vooc template (.viz / .json) to import."""
    _open_picker(callback, "*/*", ["*.viz", "*.json"], _FILE_REQUEST, "Import template")


def publish_file(path, display_name, mime, collection="Download"):
    """Copy a finished file into shared storage so Gallery / Files can see it.

    Android 10+: MediaStore (Movies/Vooc or Download/Vooc), no permission needed.
    Older Android / desktop: the file stays where it is. Returns a user-facing location."""

    try:
        from jnius import autoclass
    except Exception:
        return str(path)
    try:
        Build = autoclass("android.os.Build$VERSION")
        if Build.SDK_INT < 29:
            return str(path)
        act = autoclass("org.kivy.android.PythonActivity").mActivity
        resolver = act.getContentResolver()
        ContentValues = autoclass("android.content.ContentValues")

        values = ContentValues()
        values.put("_display_name", display_name)
        values.put("mime_type", mime)
        folder = ("Movies/Vooc" if collection == "Movies" else "Download/Vooc")
        values.put("relative_path", folder)
        if collection == "Movies":
            target = autoclass("android.provider.MediaStore$Video$Media").EXTERNAL_CONTENT_URI
        else:
            target = autoclass("android.provider.MediaStore$Downloads").EXTERNAL_CONTENT_URI
        uri = resolver.insert(target, values)
        if uri is None:
            return str(path)
        out = resolver.openOutputStream(uri)
        try:
            with open(path, "rb") as fh:
                while True:
                    chunk = fh.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk, 0, len(chunk))
        finally:
            out.close()
        return folder + "/" + display_name
    except Exception as exc:
        print("Vooc publish failed: %s" % exc)
        return str(path)
