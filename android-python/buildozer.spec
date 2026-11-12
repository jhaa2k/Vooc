[app]
title = Vooc
package.name = vooc
package.domain = com.jhaa2k
source.dir = .
source.include_exts = py,kv,png,jpg,jpeg,webp,json,md,ttf
version = 0.1.0
requirements = python3,kivy,plyer,pyjnius
orientation = portrait
fullscreen = 0
android.permissions = RECORD_AUDIO,READ_MEDIA_AUDIO,READ_EXTERNAL_STORAGE
android.api = 35
android.minapi = 24
android.ndk_api = 24
android.enable_androidx = True
android.archs = arm64-v8a, armeabi-v7a
android.accept_sdk_license = True

[buildozer]
log_level = 2
warn_on_root = 1
