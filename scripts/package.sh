#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Version comes from app/build.gradle.kts so a release only needs versionName/versionCode bumped there.
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
SERIES=$(echo "$VERSION" | awk -F. '{ printf "%d%d", $1, $2 }')
test -n "$VERSION"
# The module must ship with the same version as the APK it pins.
test "$(sed -n 's/^version=//p' module/module.prop)" = "$VERSION" || { echo "module/module.prop version != $VERSION" >&2; exit 1; }
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
test "$(sed -n 's/^versionCode=//p' module/module.prop)" = "$CODE" || { echo "module/module.prop versionCode != $CODE" >&2; exit 1; }
mkdir -p dist
test -f app/build/outputs/apk/debug/app-debug.apk
test -f app/build/outputs/apk/preview/app-preview.apk
cp app/build/outputs/apk/debug/app-debug.apk "dist/FuckPo0JiXian-$VERSION-debug.apk"
cp app/build/outputs/apk/preview/app-preview.apk "dist/FuckPo0JiXian-$VERSION.apk"
sh scripts/build-helper.sh
# -X strips host extra attributes; only the explicit module files are packaged.
(cd module && zip -X -q "../dist/FuckPo0JiXian-Runtime-$VERSION.zip" module.prop skip_mount customize.sh action.sh service.sh boot-completed.sh uninstall.sh helper.dex.jar certificate.sha256 README.md)
(cd dist && shasum -a 256 "FuckPo0JiXian-$VERSION.apk" "FuckPo0JiXian-$VERSION-debug.apk" "FuckPo0JiXian-Runtime-$VERSION.zip" > "SHA256SUMS-runtime$SERIES")
