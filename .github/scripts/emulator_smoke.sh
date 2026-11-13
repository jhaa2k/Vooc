#!/usr/bin/env bash
# Installs the debug APK on a running emulator, starts it like a user would
# (no pre-granted permissions) and collects screenshots + logcat.
set -x
PKG=com.jhaa2k.vooc
APK=$(ls apk/*.apk | head -1)
OUT=smoke-out
mkdir -p "$OUT"
T="timeout 60"
adb wait-for-device
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abi
$T adb install -r "$APK"
adb logcat -c
$T adb shell am start -W -n $PKG/org.kivy.android.PythonActivity
shot() { $T adb exec-out screencap -p > "$OUT/$1.png"; echo "[$1] pid=$($T adb shell pidof $PKG)"; }
allow() {
  # press any runtime-permission "Allow" button that is on screen
  $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $T adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$OUT/ui.xml" <<'PY' | while read x y; do adb shell input tap $x $y; sleep 2; done
import re,sys
try: s=open(sys.argv[1]).read()
except Exception: sys.exit()
for m in re.finditer(r'<node [^>]*>', s):
    n=m.group(0)
    if re.search(r'resource-id="com.android.permissioncontroller:id/permission_allow[^"]*button"', n) or re.search(r'text="(While using the app|Allow|ALLOW)"', n):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
}
sleep 8; shot 01_start
allow; sleep 3; shot 02_after_perm
allow; sleep 3
sleep 20; shot 03_idle_30s
W=$($T adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); WX=${W%x*}; WY=${W#*x}
# tap middle of the screen and a point in the lower half (menu / tabs)
adb shell input tap $((WX/2)) $((WY*55/100)); sleep 4; shot 04_tap_mid
adb shell input tap $((WX/4)) $((WY*80/100)); sleep 4; shot 05_tap_low
adb shell input keyevent KEYCODE_BACK; sleep 3; shot 06_back
$T adb shell am start -n $PKG/org.kivy.android.PythonActivity; sleep 6; shot 07_relaunch
$T adb logcat -d > "$OUT/logcat.txt"
$T adb shell run-as $PKG ls -la files files/app > "$OUT/files.txt" 2>&1
for f in vooc_startup_error.txt; do $T adb shell run-as $PKG cat files/app/$f > "$OUT/$f" 2>/dev/null; done
$T adb shell run-as $PKG sh -c 'cat files/app/.kivy/logs/*' > "$OUT/kivy_log.txt" 2>/dev/null
adb shell getprop ro.product.cpu.abilist; adb shell getprop ro.dalvik.vm.native.bridge
echo "================ APP LOG ================"
grep -E " python |pythonutil|AndroidRuntime|DEBUG   : |libc    : Fatal|F libc|SDL     : |crash_dump|Process com.jhaa2k.vooc|has died|ANR in" "$OUT/logcat.txt" | grep -v extracting | tail -300
echo "================ END ================"
exit 0
