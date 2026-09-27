#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="${ALLOWMATE_EMULATOR_SERIAL:-emulator-5580}"
case "$SERIAL" in emulator-*) ;; *) echo 'Refusing physical-device target' >&2; exit 1;; esac
test "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" = 36
test "$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')" = arm64-v8a
sh scripts/build.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p .tools
"$ADB" -s "$SERIAL" shell am instrument -w -e class app.allowmate.UiTest,app.allowmate.VisualTest app.allowmate.test/app.allowmate.SafeTestRunner | tee .tools/emulator-tests.log
rg -q '^OK \([0-9]+ tests?\)' .tools/emulator-tests.log
mkdir -p docs/qa/portrait
"$ADB" -s "$SERIAL" pull /sdcard/Android/data/app.allowmate/files/qa/. docs/qa/portrait/
