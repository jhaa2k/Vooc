#!/usr/bin/env bash
# Installs the x86_64 debug APK on the emulator, starts it and collects logs.
set -x
APK=$(ls android-python/bin/*.apk | head -1)
OUT=smoke-out
mkdir -p "$OUT"
adb wait-for-device
adb shell getprop ro.build.version.release
adb install -r -g "$APK" || adb install -r "$APK"
adb shell pm grant com.jhaa2k.vooc android.permission.RECORD_AUDIO || true
adb logcat -c
adb shell am start -W -n com.jhaa2k.vooc/org.kivy.android.PythonActivity
for t in 15 30 60; do
  sleep 15
  adb exec-out screencap -p > "$OUT/shot_${t}s.png" || true
  echo "pid after ${t}s: $(adb shell pidof com.jhaa2k.vooc)"
done
# simple interaction: tap centre-bottom area, then back
adb shell input tap 540 1700 || true
sleep 5
adb exec-out screencap -p > "$OUT/shot_tap.png" || true
echo "pid after tap: $(adb shell pidof com.jhaa2k.vooc)"
adb logcat -d > "$OUT/logcat.txt"
adb shell run-as com.jhaa2k.vooc cat files/app/.kivy/logs/*.txt > "$OUT/kivy_logs.txt" 2>/dev/null || true
adb shell run-as com.jhaa2k.vooc sh -c 'cat files/*.txt files/app/*.txt 2>/dev/null' > "$OUT/app_files.txt" || true
echo "================ PYTHON / CRASH LOG ================"
grep -E "python|Python|Traceback|Error|FATAL|AndroidRuntime|SDL|libc|DEBUG  :|crash|Vooc|vooc" "$OUT/logcat.txt" | grep -v "chatty" | tail -400
echo "================ END ================"
exit 0
