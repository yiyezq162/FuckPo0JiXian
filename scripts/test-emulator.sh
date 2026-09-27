#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="${FUCKPO0JIXIAN_EMULATOR_SERIAL:-emulator-5580}"
case "$SERIAL" in emulator-*) ;; *) echo 'Refusing physical-device target' >&2; exit 1;; esac
test "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" = 36
test "$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')" = arm64-v8a
sh scripts/build.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p .tools
"$ADB" -s "$SERIAL" shell am instrument -w -e class app.fuckpo0jixian.UiTest,app.fuckpo0jixian.SlotUiTest,app.fuckpo0jixian.RuntimeEvidenceTest,app.fuckpo0jixian.WakeTest,app.fuckpo0jixian.VisualTest app.fuckpo0jixian.test/app.fuckpo0jixian.SafeTestRunner | tee .tools/emulator-tests.log
grep -Eq '^OK \([0-9]+ tests?\)' .tools/emulator-tests.log
mkdir -p docs/qa/portrait
"$ADB" -s "$SERIAL" pull /sdcard/Android/data/app.fuckpo0jixian/files/qa/. docs/qa/portrait/
