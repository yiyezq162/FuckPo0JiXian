#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb"
SERIAL="${ALLOWMATE_EMULATOR_SERIAL:-emulator-5580}"
QA_ROOT="${ALLOWMATE_QA_DIR:-docs/qa}"
case "$SERIAL" in emulator-*) ;; *) exit 1;; esac
test "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
restore() {
  "$ADB" -s "$SERIAL" shell settings put system font_scale 1.0
  "$ADB" -s "$SERIAL" shell settings put system accelerometer_rotation 1
  "$ADB" -s "$SERIAL" shell settings put system user_rotation 0
  "$ADB" -s "$SERIAL" shell wm user-rotation free
  "$ADB" -s "$SERIAL" shell wm fixed-to-user-rotation default
  "$ADB" -s "$SERIAL" shell wm size reset
  "$ADB" -s "$SERIAL" shell wm density reset
  "$ADB" -s "$SERIAL" shell cmd uimode night no
}
trap restore EXIT
capture() {
  mkdir -p "$QA_ROOT/$1"
  "$ADB" -s "$SERIAL" shell am instrument -w -e class app.allowmate.VisualTest app.allowmate.test/app.allowmate.SafeTestRunner | tee "$QA_ROOT/$1/test.txt"
  rg -q '^OK \(' "$QA_ROOT/$1/test.txt"
  "$ADB" -s "$SERIAL" pull /sdcard/Android/data/app.allowmate/files/qa/. "$QA_ROOT/$1/"
}
"$ADB" -s "$SERIAL" shell cmd uimode night yes
capture dark
"$ADB" -s "$SERIAL" shell cmd uimode night no
"$ADB" -s "$SERIAL" shell wm size 900x1600
"$ADB" -s "$SERIAL" shell wm density 400
"$ADB" -s "$SERIAL" shell settings put system font_scale 2.0
capture small-large-font
"$ADB" -s "$SERIAL" shell settings put system font_scale 1.0
"$ADB" -s "$SERIAL" shell wm size reset
"$ADB" -s "$SERIAL" shell wm density reset
"$ADB" -s "$SERIAL" shell settings put system accelerometer_rotation 0
"$ADB" -s "$SERIAL" shell settings put system user_rotation 1
"$ADB" -s "$SERIAL" shell wm user-rotation lock 1
"$ADB" -s "$SERIAL" shell wm fixed-to-user-rotation enabled
capture landscape
