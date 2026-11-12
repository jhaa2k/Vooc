[app]
title = Vooc
package.name = vooc
package.domain = com.jhaa2k
source.dir = .
source.include_exts = py,png,jpg,kv,atlas
version = 1.0
requirements = python3,kivy,numpy,sounddevice,miniaudio
orientation = portrait
fullscreen = 0
android.permissions = READ_EXTERNAL_STORAGE,READ_MEDIA_AUDIO
android.api = 31
android.minapi = 24
android.archs = arm64-v8a,armeabi-v7a
android.sdk_path = /usr/local/lib/android/sdk
android.ndk_path = /usr/local/lib/android/sdk/ndk/27.3.13750724
android.build_tools_version = 35.0.0

[buildozer]
log_level = 2
warn_on_root = 1
