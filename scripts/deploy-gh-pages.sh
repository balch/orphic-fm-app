#!/usr/bin/env bash
#
# deploy-gh-pages.sh — Build and deploy the WASM app to GitHub Pages.
#
# Builds the Kotlin/WASM production distribution and pushes it to the
# balch/orphic-fm GitHub Pages repository.
#
# Usage:
#   ./scripts/deploy-gh-pages.sh              # full build + deploy
#   ./scripts/deploy-gh-pages.sh --dry-run    # build + stage, skip push
#   ./scripts/deploy-gh-pages.sh --skip-build # deploy existing build output
#
# Prerequisites (one-time setup):
#   1. ssh-keygen -t ed25519 -C "orphic-fm-deploy" -f orphic-fm-deploy -N ""
#   2. Add orphic-fm-deploy.pub as a deploy key (write access) on balch/orphic-fm
#   3. Add orphic-fm-deploy private key as secret ORPHIC_FM_DEPLOY_KEY on balch/orphic-fm-app
#   4. Configure GitHub Pages on balch/orphic-fm: Source = branch main, folder /
#   5. Delete local key files
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST_DIR="$REPO_ROOT/apps/composeApp/build/dist/wasmJs/productionExecutable"
TARGET_REPO="${DEPLOY_REPO:-git@github.com:balch/orphic-fm.git}"
TARGET_BRANCH="${DEPLOY_BRANCH:-main}"

DRY_RUN=false
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --dry-run)   DRY_RUN=true ;;
        --skip-build) SKIP_BUILD=true ;;
        *) echo "Unknown option: $arg"; exit 1 ;;
    esac
done

# Verify we're in the right repo
if [[ ! -f "$REPO_ROOT/gradlew" ]]; then
    echo "Error: gradlew not found at $REPO_ROOT. Run from the orphic-fm-app repo."
    exit 1
fi

# Strip API keys from local.properties during build, but keep sdk.dir and other
# non-secret entries needed by the Android plugin.
LOCAL_PROPS="$REPO_ROOT/local.properties"
LOCAL_PROPS_BAK="$REPO_ROOT/local.properties.deploy-bak"

restore_local_properties() {
    if [[ -f "$LOCAL_PROPS_BAK" ]]; then
        mv "$LOCAL_PROPS_BAK" "$LOCAL_PROPS"
    fi
}
# Ensure restore runs on any exit (error, Ctrl-C, etc.)
trap 'restore_local_properties' EXIT

if [[ "$SKIP_BUILD" != true ]] && [[ -f "$LOCAL_PROPS" ]]; then
    echo "Stripping API keys from local.properties for deploy build..."
    cp "$LOCAL_PROPS" "$LOCAL_PROPS_BAK"
    # Keep only non-secret lines (sdk.dir, flutter.*, etc.) — drop API keys
    grep -v -i 'API_KEY\|SECRET\|TOKEN' "$LOCAL_PROPS_BAK" > "$LOCAL_PROPS" || true
fi

# Build
if [[ "$SKIP_BUILD" != true ]]; then
    echo "Building WASM production distribution..."
    # Ensure the copyWasmDsp source dir exists (may be absent in a fresh checkout)
    mkdir -p "$REPO_ROOT/liborpheus_dsp/platform/wasm/build"
    "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :apps:composeApp:wasmJsBrowserDistribution
    restore_local_properties
fi

# Verify output
if [[ ! -f "$DIST_DIR/index.html" ]]; then
    echo "Error: Build output missing at $DIST_DIR"
    echo "Run without --skip-build or check the build logs."
    exit 1
fi

# Clone target repo into temp dir
TEMP_DIR=$(mktemp -d)
trap 'restore_local_properties; rm -rf "$TEMP_DIR"' EXIT  # update trap to also clean temp dir

echo "Cloning $TARGET_REPO..."
CLONE_LOG="$TEMP_DIR/clone.log"
if ! git clone --depth 1 --branch "$TARGET_BRANCH" "$TARGET_REPO" "$TEMP_DIR/deploy" 2>"$CLONE_LOG"; then
    if grep -q "not found" "$CLONE_LOG" 2>/dev/null; then
        # Branch doesn't exist yet — clone default and create it
        git clone --depth 1 "$TARGET_REPO" "$TEMP_DIR/deploy"
        cd "$TEMP_DIR/deploy"
        git checkout -b "$TARGET_BRANCH" 2>/dev/null || true
    else
        echo "Error: Failed to clone $TARGET_REPO:"
        cat "$CLONE_LOG"
        exit 1
    fi
fi

cd "$TEMP_DIR/deploy"

# Configure git identity for deploy commits
git config user.name "deploy-script"
git config user.email "deploy@orphic-fm"

# Replace all contents (keep .git)
find . -maxdepth 1 ! -name '.' ! -name '.git' -exec rm -rf {} +
cp -r "$DIST_DIR"/. .

# Prevent Jekyll processing
touch .nojekyll

# Commit
SOURCE_SHA=$(git -C "$REPO_ROOT" rev-parse --short HEAD)
git add -A

if git diff --cached --quiet; then
    echo "No changes to deploy."
    exit 0
fi

git commit -m "Deploy orphic-fm-app@${SOURCE_SHA} ($(date -u +%Y-%m-%d))"

if [[ "$DRY_RUN" == true ]]; then
    echo ""
    echo "Dry run — would push the following to $TARGET_REPO ($TARGET_BRANCH):"
    git log --oneline -1
    echo ""
    echo "Files:"
    ls -la
else
    echo "Pushing to $TARGET_REPO ($TARGET_BRANCH)..."
    git push origin "$TARGET_BRANCH"
    echo "Deployed successfully."
fi
