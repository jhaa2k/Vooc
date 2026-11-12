"""Android-safe media selection helpers.

Plyer's Android picker can return content:// URIs instead of filesystem paths.
Kivy's SoundLoader and image decoders need a readable local file, so copy the
selected document into the app cache while the granted URI permission is live.
"""
import mimetypes
import os
import re


def _safe_name(value):
    name = os.path.basename(str(value or ""))
    name = re.sub(r"[^A-Za-z0-9._ -]", "_", name).strip(" .")
    return name or "imported_media"


def _materialize_android_uri(value):
    """Return a local path for a picker result, or the original path on desktop."""
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
            cursor = resolver.query(uri, None, None, None, None)
            if cursor is not None and cursor.moveToFirst():
                index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if index >= 0:
                    display_name = str(cursor.getString(index))
        finally:
            if cursor is not None:
                cursor.close()

        display_name = _safe_name(display_name)
        if "." not in display_name:
            mime = resolver.getType(uri)
            extension = mimetypes.guess_extension(str(mime)) if mime else None
            display_name += extension or ".audio"

        cache_dir = str(activity.getCacheDir().getAbsolutePath())
        target = os.path.join(cache_dir, display_name)
        base, ext = os.path.splitext(target)
        counter = 1
        while os.path.exists(target):
            target = "%s_%d%s" % (base, counter, ext)
            counter += 1

        input_stream = resolver.openInputStream(uri)
        if input_stream is None:
            raise IOError("Android could not open the selected audio document")
        output_stream = FileOutputStream(target)
        input_channel = None
        output_channel = None
        try:
            input_channel = Channels.newChannel(input_stream)
            output_channel = output_stream.getChannel()
            buffer = ByteBuffer.allocateDirect(65536)
            while input_channel.read(buffer) != -1:
                buffer.flip()
                while buffer.hasRemaining():
                    output_channel.write(buffer)
                buffer.clear()
        finally:
            try:
                if input_channel is not None:
                    input_channel.close()
            except Exception:
                pass
            try:
                input_stream.close()
            except Exception:
                pass
            try:
                if output_channel is not None:
                    output_channel.close()
            except Exception:
                pass
            try:
                output_stream.close()
            except Exception:
                pass

        if not os.path.isfile(target) or os.path.getsize(target) <= 0:
            raise IOError("The selected document was empty or could not be copied")
        return target
    except Exception as exc:
        print("Vooc media import failed:", exc)
        return None


def _open_picker(callback, filters=None):
    try:
        from plyer import filechooser

        def selected(files):
            if not files:
                callback(None)
                return
            callback(_materialize_android_uri(files[0]))

        kwargs = {"on_selection": selected}
        if filters:
            kwargs["filters"] = filters
        filechooser.open_file(**kwargs)
    except Exception as exc:
        print("Vooc file picker failed:", exc)
        callback(None)


def choose_audio(callback):
    """Pick an audio file and return a local path usable by the audio backend."""
    _open_picker(callback, ["*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a", "*.aac"])


def choose_image(callback):
    """Pick an image and materialize Android content URIs into the app cache."""
    _open_picker(callback, ["*.png", "*.jpg", "*.jpeg", "*.webp"])
