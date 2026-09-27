#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p dist
test -f app/build/outputs/apk/debug/app-debug.apk
test -f app/build/outputs/apk/preview/app-preview.apk
cp app/build/outputs/apk/debug/app-debug.apk dist/AllowMate-0.4.0-preview-debug.apk
cp app/build/outputs/apk/preview/app-preview.apk dist/AllowMate-0.4.0-preview.apk
sh scripts/build-helper.sh
# -X strips host extra attributes; only the explicit module files are packaged.
(cd module && zip -X -q ../dist/AllowMate-Runtime-0.4.0-preview.zip module.prop skip_mount customize.sh action.sh service.sh boot-completed.sh uninstall.sh helper.dex.jar certificate.sha256 README.md)
(cd dist && shasum -a 256 AllowMate-0.4.0-preview.apk AllowMate-0.4.0-preview-debug.apk AllowMate-Runtime-0.4.0-preview.zip > SHA256SUMS-runtime04)
