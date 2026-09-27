#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "$PWD"/.tools/jdk-17*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
exec ./gradlew "$@"
