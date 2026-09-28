#!/bin/sh
# Regenerates the desktop icons from the shapes in art/icon.svg (see scripts/icons/RenderIcons.java).
# Android draws the same shapes as vector drawables in app/src/main/res/drawable. Needs macOS for iconutil.
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$PWD/.tools/jdk-17.0.20.1+1/Contents/Home}"
ICONSET=$(mktemp -d)/icon.iconset
"$JAVA_HOME/bin/java" -Djava.awt.headless=true scripts/icons/RenderIcons.java desktop "$ICONSET"
iconutil -c icns "$ICONSET" -o desktop/icons/icon.icns
rm -rf "$(dirname "$ICONSET")"
