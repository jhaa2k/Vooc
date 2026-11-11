def choose_audio(callback):
    try:
        from plyer import filechooser
        filechooser.open_file(on_selection=lambda files:callback(files[0] if files else None))
    except Exception:
        callback(None)
