def choose_audio(callback):
    try:
        from plyer import filechooser
        filechooser.open_file(on_selection=lambda files:callback(files[0] if files else None))
    except Exception:
        callback(None)

 
def choose_image(callback):
    """Open an image picker; returned Android content URIs may need SAF support."""
    try:
        from plyer import filechooser
        filechooser.open_file(
            on_selection=lambda files: callback(files[0] if files else None),
            filters=["*.png", "*.jpg", "*.jpeg", "*.webp"]
        )
    except Exception:
        callback(None)
