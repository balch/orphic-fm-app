#!/usr/bin/env bash
# Render one framed store image. Needs: python3 -m http.server 8765 --directory apps/djapp
# Usage: render_frame.sh <html> <hash> <W> <H> <out.png>
set -euo pipefail
html=$1 hash=$2 w=$3 h=$4 out=$5
raw="${out%.png}-raw.png"
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --window-size="$w,$h" --screenshot="$raw" \
  "http://127.0.0.1:8765/store-listings/capture/framed/$html?v=$(date +%s)#$hash" 2>/dev/null
magick "$raw" -background '#0A0814' -alpha remove -alpha off "PNG24:$out"
rm "$raw"
