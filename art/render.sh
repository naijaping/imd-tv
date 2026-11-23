#!/bin/sh
# Renders the site images from the SVGs in art/ (needs rsvg-convert). The app icons, TV banner and in-app logo come
# from art/logo-a.png through art/brand.py (needs Pillow), run at the end.
# Uses the app's copy of Inter (app/src/main/res/font/inter.ttf) through a private fontconfig file.
set -e
cd "$(dirname "$0")/.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
printf '<?xml version="1.0"?><fontconfig><dir>%s</dir><cachedir>%s</cachedir></fontconfig>\n' "$PWD/app/src/main/res/font" "$T" > "$T/fonts.conf"
r() { FONTCONFIG_FILE="$T/fonts.conf" rsvg-convert -w "$2" -h "$3" "art/$1.svg" -o "$4"; }
mkdir -p site/img
r mark 320 272 site/img/logo.png
r launcher 64 64 site/favicon.png
r launcher 512 512 site/img/icon.png
python3 art/brand.py
