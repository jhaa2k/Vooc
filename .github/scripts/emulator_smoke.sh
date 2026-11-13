#!/usr/bin/env bash
# Installs the debug APK on a running emulator and drives it like a user:
# first launch (no pre-granted permissions), import a song through the system
# picker, play it, open every tab, switch to the Android native player.
# Collects screenshots + logcat in smoke-out/.
set -x
PKG=com.jhaa2k.vooc
APK=$(ls apk/*.apk | head -1)
OUT=smoke-out
mkdir -p "$OUT"
T="timeout 60"
adb wait-for-device
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
python3 - <<'PY'
import wave, struct, math
w = wave.open("test_song.wav", "wb"); w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100)
frames = bytearray()
for i in range(44100 * 20):
    t = i / 44100.0
    beat = 1.0 if (t % 0.5) < 0.08 else 0.25
    v = int(9000 * beat * math.sin(2 * math.pi * (110 + 220 * (int(t) % 4)) * t))
    frames += struct.pack("<hh", v, v)
w.writeframes(bytes(frames)); w.close()
PY
adb push test_song.wav /sdcard/Download/test_song.wav
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/test_song.wav >/dev/null 2>&1
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
$T adb install -r "$APK"
adb logcat -c
W=$($T adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); WX=${W%x*}; WY=${W#*x}
tap() { adb shell input tap $(python3 -c "print(int($WX*$1), int($WY*$2))"); }
n=0
shot() { n=$((n+1)); f=$(printf "%02d_%s" $n "$1"); $T adb exec-out screencap -p > "$OUT/$f.png"; echo "[$f] pid=$($T adb shell pidof $PKG)"; }
tap_text() {
  $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $T adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$OUT/ui.xml" "$1" <<'PY' | while read x y; do if [ -n "$LONG" ]; then adb shell input swipe $x $y $x $y 1200; else adb shell input tap $x $y; fi; done
import re, sys
try: s = open(sys.argv[1]).read()
except Exception: sys.exit()
pat = re.compile(sys.argv[2])
for m in re.finditer(r'<node [^>]*>', s):
    node = m.group(0)
    vals = [m2.group(1) for m2 in re.finditer(r' (?:text|resource-id|content-desc)="([^"]*)"', node)]
    if any(v and pat.search(v) for v in vals):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}
allow() { tap_text '^(While using the app|Allow|ALLOW|Only this time)$|permission_allow_foreground_only_button|permission_allow_button'; sleep 2; }

# First launch, but leave the app during the "Loading..." screen (Home key),
# then come back. This used to kill the app ("Could not fetch native window").
$T adb shell am start -W -n $PKG/org.kivy.android.PythonActivity
sleep 2; adb shell input keyevent KEYCODE_HOME; sleep 6
$T adb shell am start -n $PKG/org.kivy.android.PythonActivity
sleep 12; shot start
allow; sleep 2; shot after_permission
# IMPORT (top transport row)
adb shell cp /sdcard/Download/test_song.wav /sdcard/Music/test_song.wav
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1; sleep 3
tap 0.31 0.374; sleep 5; shot picker
picker_up() { [ -n "$($T adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | grep -i documentsui)" ]; }
for attempt in 1 2 3 4; do
  top=$($T adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity:' | head -1)
  echo "picker attempt $attempt: $top"
  case "$top" in
    *documentsui*) ;;
    *$PKG*) break ;;
    *) adb shell input keyevent KEYCODE_BACK; sleep 3; continue ;;
  esac
  $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  if ! grep -q test_song "$OUT/ui.xml"; then
    tap_text '^Show roots$'; sleep 3; tap_text '^(Downloads|Music|Audio)$'; sleep 4; shot picker_downloads; continue
  fi
  if [ $attempt -le 2 ]; then tap_text 'test_song'; sleep 5
  else LONG=1 tap_text 'test_song'; sleep 2; tap_text '^(Select|SELECT|Open|OPEN|Done)$|action_menu_select|menu_select'; sleep 5; fi
done
shot imported
tap 0.51 0.374; sleep 6; shot playing
for tab in "0.20 Layers" "0.33 Presets" "0.45 Audio" "0.57 Visualizer" "0.69 Properties" "0.81 Scene" "0.93 Export" "0.08 Media"; do
  set -- $tab; tap $1 0.943; sleep 3; shot "tab_$2"
done
# Properties tab, open first module
tap 0.69 0.943; sleep 3; tap 0.5 0.62; sleep 3; shot properties_open
tap 0.08 0.943; sleep 3
# USE ANDROID NATIVE PLAYER
tap 0.5 0.626; sleep 4; allow; sleep 4; shot native_player
tap 0.51 0.374; sleep 6; shot native_playing
adb shell input keyevent KEYCODE_HOME; sleep 3
$T adb shell am start -n $PKG/org.kivy.android.PythonActivity; sleep 6; shot resume

$T adb logcat -d > "$OUT/logcat.txt"
$T adb shell run-as $PKG ls -la files/app > "$OUT/files.txt" 2>&1
$T adb shell run-as $PKG cat files/app/vooc_startup_error.txt > "$OUT/vooc_startup_error.txt" 2>/dev/null
echo "================ APP LOG ================"
grep -E " python |AndroidRuntime: (FATAL|Process)|F DEBUG|Fatal signal|has died|ANR in|ndk_translation: Undefined" "$OUT/logcat.txt" | grep -v extracting | tail -300
echo "================ END ================"
exit 0
