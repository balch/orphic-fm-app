#!/bin/bash
set -e

SOURCE_IMAGE="/Users/balch/Downloads/Gemini_Generated_Image_ybx7vgybx7vgybx7.png"

# Ensure source exists
if [ ! -f "$SOURCE_IMAGE" ]; then
    echo "Error: Source image not found at $SOURCE_IMAGE"
    exit 1
fi

echo "Generating icons from $SOURCE_IMAGE..."

# Common Resources (if needed, but mainly platform specific)
# sips -s format webp --resampleHeightWidth 512 512 "$SOURCE_IMAGE" --out "composeApp/src/commonMain/resources/icon.webp"

# Android
echo "Generating Android icons..."
ANDROID_RES="composeApp/src/androidMain/res"
mkdir -p "$ANDROID_RES/mipmap-mdpi"
mkdir -p "$ANDROID_RES/mipmap-hdpi"
mkdir -p "$ANDROID_RES/mipmap-xhdpi"
mkdir -p "$ANDROID_RES/mipmap-xxhdpi"
mkdir -p "$ANDROID_RES/mipmap-xxxhdpi"

sips -s format png --resampleHeightWidth 48 48 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-mdpi/ic_launcher.png"
sips -s format png --resampleHeightWidth 48 48 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-mdpi/ic_launcher_round.png"

sips -s format png --resampleHeightWidth 72 72 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-hdpi/ic_launcher.png"
sips -s format png --resampleHeightWidth 72 72 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-hdpi/ic_launcher_round.png"

sips -s format png --resampleHeightWidth 96 96 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-xhdpi/ic_launcher.png"
sips -s format png --resampleHeightWidth 96 96 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-xhdpi/ic_launcher_round.png"

sips -s format png --resampleHeightWidth 144 144 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-xxhdpi/ic_launcher.png"
sips -s format png --resampleHeightWidth 144 144 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-xxhdpi/ic_launcher_round.png"

sips -s format png --resampleHeightWidth 192 192 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-xxxhdpi/ic_launcher.png"
sips -s format png --resampleHeightWidth 192 192 "$SOURCE_IMAGE" --out "$ANDROID_RES/mipmap-xxxhdpi/ic_launcher_round.png"

# JVM
echo "Generating JVM icons..."
JVM_RES="composeApp/src/jvmMain/resources"
mkdir -p "$JVM_RES"
sips -s format png --resampleHeightWidth 512 512 "$SOURCE_IMAGE" --out "$JVM_RES/icon.png"
sips -s format icns --resampleHeightWidth 512 512 "$SOURCE_IMAGE" --out "$JVM_RES/icon.icns"

# WasmJS
echo "Generating WasmJS icons..."
WASM_RES="composeApp/src/wasmJsMain/resources"
mkdir -p "$WASM_RES"
# Using png for wasm icon as webp support in sips might vary or just to be safe, but user asked for webp.
# Actually sips output said "org.webmproject.webp webp" is supported but not "Writable"? Wait.
# "org.webmproject.webp webp" is in the list.
# Let's check permissions. "Writable" column was empty for webp in the output I got?
# "org.webmproject.webp         webp" -> No "Writable" next to it.
# Ah, I see. public.heic has Writable. webp does NOT.
# I will use PNG for WasmJS if sips cannot write webp.
# Wait, user specifically asked "convert this image to webp".
# If sips can't do it, I might have to rely on proper gradle task or different tool.
# But I will try `sips -s format webp` anyway, maybe compiled version differs.
# If it fails, I'll fallback to png and warn user.

if sips -s format webp --resampleHeightWidth 512 512 "$SOURCE_IMAGE" --out "$WASM_RES/icon.webp" 2>/dev/null; then
    echo "WebP generated successfully."
else
    echo "Sips failed to write WebP (likely not supported for writing on this mac version). Falling back to PNG."
    sips -s format png --resampleHeightWidth 512 512 "$SOURCE_IMAGE" --out "$WASM_RES/icon.png"
    # Rename to webp just to satisfy file path if we change HTML, OR change HTML to use png.
    # User asked for "convert this image to webp".
    # I better be honest. I will output png and update index.html to use icon.png if webp fails.
    # Actually, I'll try to use a python script if sips fails? No, standard python might not have PIL.
    # I will stick to PNG if WebP fails, it's safer for web anyway.
fi

# Favicon
sips -s format ico --resampleHeightWidth 32 32 "$SOURCE_IMAGE" --out "$WASM_RES/favicon.ico" || sips -s format png --resampleHeightWidth 32 32 "$SOURCE_IMAGE" --out "$WASM_RES/favicon.ico"

echo "Done."
