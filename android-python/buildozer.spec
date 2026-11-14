[app]
title = Vooc
package.name = vooc
package.domain = com.jhaa2k
source.dir = .
source.include_exts = py,png,jpg,jpeg,webp,kv,atlas,json,ttf
source.exclude_dirs = tests,docs,bin,ci,.buildozer,__pycache__
version = 1.0

# Vooc brand assets used by Android's launcher and native presplash.
icon.filename = %(source.dir)s/assets/vooc_icon.png
presplash.filename = %(source.dir)s/assets/vooc_splash.png
android.presplash_color = #050506

# Only libraries that have python-for-android recipes or are pure Python.
# On Android audio is played by MediaPlayer and analysed through the Visualizer
# API (pyjnius); sounddevice/miniaudio are optional desktop-only backends.
requirements = python3,kivy,numpy,plyer,pyjnius

orientation = portrait
fullscreen = 0
android.permissions = MODIFY_AUDIO_SETTINGS,READ_MEDIA_AUDIO,READ_MEDIA_IMAGES,READ_EXTERNAL_STORAGE,POST_NOTIFICATIONS,WAKE_LOCK
android.add_src = java
android.api = 35
android.minapi = 24
android.ndk_api = 24
android.enable_androidx = True
android.archs = arm64-v8a, armeabi-v7a
android.accept_sdk_license = True
# Do NOT set android.sdk_path / android.ndk_path: the runner's preinstalled SDK
# has no legacy tools/bin/sdkmanager, which makes Buildozer abort. Buildozer
# downloads its own SDK + the NDK recommended by p4a instead.

# Pin python-for-android to the upstream fix for the pip self-upgrade/venv corruption.
p4a.branch = develop
p4a.commit = d2ee8c54d9d42375a95f18159e950a119671cf63

[buildozer]
log_level = 2
warn_on_root = 1

# Build trigger: branding assets only; app runtime and audio engine unchanged.
