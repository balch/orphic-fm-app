#!/usr/bin/env bash
#
# dev-site.sh — Build WASM synth and serve it inside the orphic.fm Jekyll site.
#
# Copies the WASM build output into ~/Source/orphic-fm/synth/ and starts
# Jekyll on port 4001 so you can test at http://localhost:4001/synth/
#
# Usage:
#   ./scripts/dev-site.sh              # build + copy + serve
#   ./scripts/dev-site.sh --skip-build # copy existing build + serve
#   ./scripts/dev-site.sh --copy-only  # build + copy, don't start server
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SITE_DIR="${ORPHIC_FM_SITE:-$HOME/Source/orphic-fm}"
SYNTH_DIR="$SITE_DIR/synth"
DIST_DIR="$REPO_ROOT/apps/orpheus/webApp/build/dist/wasmJs/productionExecutable"
PORT="${PORT:-4001}"

SKIP_BUILD=false
COPY_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --copy-only)  COPY_ONLY=true ;;
        *) echo "Unknown option: $arg"; exit 1 ;;
    esac
done

# Verify repos exist
if [[ ! -f "$REPO_ROOT/gradlew" ]]; then
    echo "Error: Run from the orphic-fm-app repo."
    exit 1
fi
if [[ ! -f "$SITE_DIR/_config.yaml" ]]; then
    echo "Error: orphic-fm site not found at $SITE_DIR"
    echo "Set ORPHIC_FM_SITE env var to the correct path."
    exit 1
fi

# Strip API keys from local.properties during build
LOCAL_PROPS="$REPO_ROOT/local.properties"
LOCAL_PROPS_BAK="$REPO_ROOT/local.properties.deploy-bak"

restore_local_properties() {
    if [[ -f "$LOCAL_PROPS_BAK" ]]; then
        mv "$LOCAL_PROPS_BAK" "$LOCAL_PROPS"
    fi
}
trap 'restore_local_properties' EXIT

# Build WASM
if [[ "$SKIP_BUILD" != true ]]; then
    if [[ -f "$LOCAL_PROPS" ]]; then
        echo "Stripping API keys from local.properties for build..."
        cp "$LOCAL_PROPS" "$LOCAL_PROPS_BAK"
        grep -v -i 'API_KEY\|SECRET\|TOKEN' "$LOCAL_PROPS_BAK" > "$LOCAL_PROPS" || true
    fi
    echo "Building WASM production distribution..."
    mkdir -p "$REPO_ROOT/liborpheus_dsp/platform/wasm/build"
    "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :apps:orpheus:webApp:wasmJsBrowserDistribution
    restore_local_properties
fi

# Verify build output
if [[ ! -f "$DIST_DIR/index.html" ]]; then
    echo "Error: Build output missing at $DIST_DIR"
    echo "Run without --skip-build first."
    exit 1
fi

# Copy to site
echo "Copying WASM build to $SYNTH_DIR..."
rm -rf "$SYNTH_DIR"
cp -r "$DIST_DIR" "$SYNTH_DIR"
echo "Synth files copied to $SYNTH_DIR"

if [[ "$COPY_ONLY" == true ]]; then
    echo "Done. Run 'bundle exec jekyll serve' in $SITE_DIR to test."
    exit 0
fi

# Serve
echo ""
echo "Starting Jekyll on port $PORT..."
echo "  Site:  http://localhost:$PORT/"
echo "  Synth: http://localhost:$PORT/synth/"
echo ""
cd "$SITE_DIR"
bundle exec jekyll serve --port "$PORT" --livereload
