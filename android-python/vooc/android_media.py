"""Android-safe media selection helpers.

The Android document picker often returns content:// URIs rather than file paths.
Copy those documents into the app cache so existing playback and image loaders
can use a regular local path without changing the original app workflow.
"""
import mimetypes
import os
import re


def _safe_name(value):
    name = os.path.basename(str(value or ""))
    name = re.sub(r"[^A-Za-z0-9._ -]", "_", name).strip(" .")
    return name or "imported_media"


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
            extension = (mimetypes.guess_extension(str(mime)) if mime else None) or ".audio"
            display_name += extension
        target = os.path.join(str(activity.getCacheDir().getAbsolutePath()), display_name)
        base, ext = os.path.splitext(target)
        counter = 1
        while os.path.exists(target):
            target = "%s_%d%s" % (base, counter, ext)
            counter += 1

        input_stream = resolver.openInputStream(uri)
        if input_stream is None:
            raise IOError("Android could not open the selected document")
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
    """Open the audio picker and return a usable local file path."""
    _open_picker(callback, ["*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a", "*.aac"])


def choose_image(callback):
    """Open the image picker and return a usable local file path."""
    _open_picker(callback, ["*.png", "*.jpg", "*.jpeg", "*.webp"])
