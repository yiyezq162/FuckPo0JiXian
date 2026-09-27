#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$PWD/.tools/jdk-17.0.20.1+1/Contents/Home}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
mkdir -p module/build
HELPER_BUILD=$(mktemp -d "$PWD/module/build/compile.XXXXXX")
mkdir -p "$HELPER_BUILD/classes" "$HELPER_BUILD/dex"
"$JAVA_HOME/bin/javac" -source 17 -target 17 -classpath "$SDK/platforms/android-36/android.jar" -d "$HELPER_BUILD/classes" module/src/app/fuckpo0jixian/helper/Main.java module/src/app/fuckpo0jixian/helper/Diagnostics.java
"$JAVA_HOME/bin/jar" --create --file "$HELPER_BUILD/helper.jar" -C "$HELPER_BUILD/classes" .
"$SDK/build-tools/36.0.0/d8" --min-api 28 --lib "$SDK/platforms/android-36/android.jar" --output "$HELPER_BUILD/dex" "$HELPER_BUILD/helper.jar"
MODULE_OUTPUT="$PWD/module/helper.dex.jar"
(cd "$HELPER_BUILD/dex" && zip -X -q "$MODULE_OUTPUT" classes.dex)
"$SDK/build-tools/36.0.0/apksigner" verify --print-certs app/build/outputs/apk/preview/app-preview.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' > module/certificate.sha256
test "$(wc -c < module/certificate.sha256 | tr -d ' ')" = 65
