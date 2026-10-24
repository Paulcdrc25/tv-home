#!/usr/bin/env bash
# Send your own photos to the TV as home-screen wallpapers. They're resized to 1920 px JPEGs and
# show up in the launcher's "Fond d'écran" picker (and the rotation) after the built-in ones.
# Usage: tools/add-wallpaper.sh photo1.jpg photo2.heic ...
#        tools/add-wallpaper.sh --remove photo1.jpg       (by the name it has on the TV)
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-tools/venv/bin/python}"
DIR=/sdcard/Pictures/TVHome
[ $# -gt 0 ] || { echo "usage: tools/add-wallpaper.sh <images...> | --remove <name>"; exit 1; }
if [ "$1" = "--remove" ]; then
  shift
  for n in "$@"; do "$PY" -I tools/tv.py "rm -f '$DIR/$(basename "$n")'"; done
  exit 0
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
"$PY" -I tools/tv.py "mkdir -p $DIR" >/dev/null
for f in "$@"; do
  name=$(basename "${f%.*}" | tr -c 'A-Za-z0-9_-\n' '_').jpg
  # Convert to sRGB (iPhone photos are Display P3) and keep quality high so gradients stay smooth.
  sips -m "/System/Library/ColorSync/Profiles/sRGB Profile.icc" -s format jpeg -s formatOptions 92 \
    -Z 1920 "$f" --out "$tmp/$name" >/dev/null
  "$PY" -I tools/tv.py --push-b64 "$tmp/$name" "$DIR/$name"
done
echo "Done. On the TV: Fond d'écran (top right) to pick one."
