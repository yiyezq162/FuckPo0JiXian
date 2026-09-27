#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb"
SERIAL="${FUCKPO0JIXIAN_EMULATOR_SERIAL:-emulator-5580}"
case "$SERIAL" in emulator-*) ;; *) exit 1;; esac
test "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
# Do not change real or active app state. Run after synthetic visual tests only.
"$ADB" -s "$SERIAL" shell run-as app.fuckpo0jixian cat no_backup/state-v1.json | jq -e '.demo == true and .paused == true' >/dev/null
before=$("$ADB" -s "$SERIAL" shell run-as app.fuckpo0jixian cat no_backup/state-v1.json | shasum -a 256 | cut -d ' ' -f 1)
"$ADB" -s "$SERIAL" shell am force-stop app.fuckpo0jixian
if "$ADB" -s "$SERIAL" shell pidof app.fuckpo0jixian; then echo 'FAIL: process still alive' >&2; exit 1; fi
echo 'PASS: process absent after force-stop checkpoint'
"$ADB" -s "$SERIAL" shell am start -W -n app.fuckpo0jixian/.MainActivity
after=$("$ADB" -s "$SERIAL" shell run-as app.fuckpo0jixian cat no_backup/state-v1.json | shasum -a 256 | cut -d ' ' -f 1)
test "$before" = "$after"
echo "PASS: persistent synthetic state unchanged after fresh process start: $after"
