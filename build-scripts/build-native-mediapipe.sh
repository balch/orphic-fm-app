#!/usr/bin/env bash
#
# build-native-mediapipe.sh — Build the MediaPipe JNI dylib for desktop
# (macOS ARM64).
#
# This is the single entry point for both initial setup and rebuilds.
#
# ── What it does ──────────────────────────────────────────────────────
#
#   1. (--setup) Patches a clean MediaPipe checkout with Orpheus-specific
#      changes: OpenCV 4 static linking, Apple Silicon Homebrew paths,
#      the combined JNI bridge Bazel target, and symbol-hiding linker flags.
#
#   2. Builds libmediapipe_jni.dylib via Bazel — a self-contained shared
#      library that statically links MediaPipe HandLandmarker + OpenCV + TBB.
#      All internal symbols (including OpenCV) are hidden via
#      -exported_symbols_list to prevent collisions with JavaCV.
#      Only 7 JNI entry points are exported.
#
#   3. Copies the dylib into the Orpheus resource directory and ad-hoc
#      signs it for Apple Silicon (macOS requires code-signed dylibs).
#
# ── GestureRecognizer fixes ──────────────────────────────────────────
#
#   The dylib statically links both HandLandmarker AND GestureRecognizer
#   C APIs, and the JNI bridge (mediapipe_jni.cc) wraps both.
#
#   Two fixes are required for the GestureRecognizer:
#
#   1. MakePacket fix (mediapipe.patch):
#      LandmarksToMatrixCalculator and HandednessToMatrixCalculator
#      crash with POINTER_BEING_FREED_WAS_NOT_ALLOCATED in
#      Holder<Eigen::Matrix>::~Holder() during ClearCurrentInputs.
#      This is caused by an allocator mismatch: Eigen's custom
#      operator new (aligned allocation) creates the Matrix, but
#      Holder's delete_helper calls plain delete, which may resolve
#      to the wrong deallocator when symbols are hidden via
#      -exported_symbols_list.
#      Fix: change Send(unique_ptr<Matrix>) → Send(Matrix&&) so
#      packets use MakePacket (value copy) instead of Adopt (pointer).
#
#   2. VIDEO mode (mediapipe_jni.cc):
#      The JNI bridge creates the GestureRecognizer in VIDEO mode
#      (synchronous) instead of LIVE_STREAM (async callback) as an
#      additional safety measure. The capture loop is sequential
#      anyway, so there's no throughput difference.
#
#   HandLandmarker is unaffected (no Matrix calculators in its graph)
#   and uses LIVE_STREAM mode normally.
#
#   DesktopHandTracker tries GestureRecognizer first and falls back to
#   HandLandmarker if the gesture model is unavailable. A pure-Kotlin
#   rule-based classifier (AslSignClassifier.kt) provides supplemental
#   ASL sign recognition from HandLandmarker landmarks.
#
# ── Dependent files ───────────────────────────────────────────────────
#
#   build-scripts/mediapipe-patches/mediapipe.patch
#       Diffs applied to the MediaPipe repo:
#       - .bazelrc: zlib fdopen conflict fix for macOS
#       - WORKSPACE: Homebrew path for Apple Silicon (/opt/homebrew/Cellar)
#       - third_party/opencv_macos.BUILD: OpenCV 4.13 static libs + TBB
#         + macOS framework linkopts (was OpenCV 3 dynamic)
#       - mediapipe/tasks/c/vision/hand_landmarker/BUILD: adds the
#         combined libmediapipe_jni.dylib cc_binary target with
#         exported_symbols_list for symbol hiding
#       - mediapipe/tasks/cc/vision/gesture_recognizer/calculators/:
#         LandmarksToMatrixCalculator and HandednessToMatrixCalculator
#         changed from Send(unique_ptr<Matrix>) to Send(Matrix&&) to
#         use MakePacket instead of Adopt (fixes Holder crash)
#
#   build-scripts/mediapipe-patches/mediapipe_jni.cc
#       Combined JNI bridge wrapping HandLandmarker and GestureRecognizer
#       C APIs for the JVM.
#       Kotlin side: org.balch.orpheus.core.mediapipe.MediaPipeJni
#
#   build-scripts/mediapipe-patches/exported_symbols.txt
#       Linker symbol export list — only 7 JNI entry points are visible.
#       All other symbols (OpenCV, protobuf, Eigen, etc.) are hidden to
#       prevent collisions with JavaCV loaded in the same JVM process.
#
# ── Output ────────────────────────────────────────────────────────────
#
#   core/mediapipe/src/jvmMain/resources/native/darwin-aarch64/
#       libmediapipe_jni.dylib  (~14MB, 7 exported symbols)
#
# ── Prerequisites ─────────────────────────────────────────────────────
#
#   brew install opencv tbb bazelisk
#   JDK 17+ with JAVA_HOME set (for JNI headers)
#   Python 3.12 (for Bazel hermetic python)
#   MediaPipe source: git clone https://github.com/google-ai-edge/mediapipe.git
#
# ── Usage ─────────────────────────────────────────────────────────────
#
#   First time (patch + build):
#     ./build-scripts/build-native-mediapipe.sh --setup
#
#   Subsequent rebuilds:
#     ./build-scripts/build-native-mediapipe.sh
#
#   Custom MediaPipe location:
#     MEDIAPIPE_DIR=/path/to/mediapipe ./build-scripts/build-native-mediapipe.sh
#
# ── MediaPipe base commit ─────────────────────────────────────────────
#
#   Patch tested against: ee89477cd (2025-02)
#   Repo: https://github.com/google-ai-edge/mediapipe.git
#
# ──────────────────────────────────────────────────────────────────────

set -euo pipefail

ORPHIC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MEDIAPIPE_DIR="${MEDIAPIPE_DIR:-$HOME/Source/mediapipe}"
PATCHES_DIR="$(cd "$(dirname "$0")/mediapipe-patches" && pwd)"
TARGET_DIR="$ORPHIC_DIR/core/mediapipe/src/jvmMain/resources/native/darwin-aarch64"

# ── Setup (patch + copy sources) ─────────────────────────────────────

do_setup() {
    echo "==> Setting up MediaPipe at $MEDIAPIPE_DIR"

    if [[ ! -f "$MEDIAPIPE_DIR/WORKSPACE" ]]; then
        echo "Error: $MEDIAPIPE_DIR does not look like a MediaPipe checkout (no WORKSPACE)"
        exit 1
    fi

    # Verify JAVA_HOME for JNI headers
    if [[ -z "${JAVA_HOME:-}" ]]; then
        echo "Error: JAVA_HOME is not set"
        exit 1
    fi
    if [[ ! -f "$JAVA_HOME/include/jni.h" ]]; then
        echo "Error: $JAVA_HOME/include/jni.h not found"
        exit 1
    fi

    # Apply patch (OpenCV 4 static, Homebrew paths, BUILD target, .bazelrc zlib fix)
    echo "    Applying mediapipe.patch..."
    cd "$MEDIAPIPE_DIR"
    git apply "$PATCHES_DIR/mediapipe.patch"

    # Copy JNI bridge source and symbol export list
    echo "    Copying mediapipe_jni.cc and exported_symbols.txt..."
    cp "$PATCHES_DIR/mediapipe_jni.cc" \
       mediapipe/tasks/c/vision/hand_landmarker/mediapipe_jni.cc
    cp "$PATCHES_DIR/exported_symbols.txt" \
       mediapipe/tasks/c/vision/hand_landmarker/exported_symbols.txt

    # Symlink JNI headers from JAVA_HOME (avoids vendoring Oracle headers)
    echo "    Symlinking JNI headers from $JAVA_HOME..."
    local jni_dir="mediapipe/tasks/c/vision/hand_landmarker/jni"
    mkdir -p "$jni_dir"
    ln -sf "$JAVA_HOME/include/jni.h" "$jni_dir/jni.h"

    if [[ -f "$JAVA_HOME/include/darwin/jni_md.h" ]]; then
        ln -sf "$JAVA_HOME/include/darwin/jni_md.h" "$jni_dir/jni_md.h"
    elif [[ -f "$JAVA_HOME/include/linux/jni_md.h" ]]; then
        ln -sf "$JAVA_HOME/include/linux/jni_md.h" "$jni_dir/jni_md.h"
    else
        echo "Warning: jni_md.h not found in $JAVA_HOME/include/{darwin,linux}/"
    fi

    echo "==> Setup complete"
}

# ── Build ─────────────────────────────────────────────────────────────

do_build() {
    echo "==> Building combined MediaPipe JNI dylib in $MEDIAPIPE_DIR"

    cd "$MEDIAPIPE_DIR"
    bazelisk build --config darwin_arm64 -c opt --strip always \
        --define MEDIAPIPE_DISABLE_GPU=1 \
        --repo_env=HERMETIC_PYTHON_VERSION=3.12 \
        --copt=-DEIGEN_MAX_ALIGN_BYTES=16 \
        //mediapipe/tasks/c/vision/hand_landmarker:libmediapipe_jni.dylib

    mkdir -p "$TARGET_DIR"
    cp bazel-bin/mediapipe/tasks/c/vision/hand_landmarker/libmediapipe_jni.dylib \
       "$TARGET_DIR/"

    # Ad-hoc sign for Apple Silicon (macOS kills unsigned dylibs with SIGKILL).
    # Note: MediaPipeJni.kt also re-signs after extracting from the JAR at runtime,
    # since the JAR extraction loses the signature.
    codesign -s - "$TARGET_DIR/libmediapipe_jni.dylib"

    echo "==> Copied and signed $TARGET_DIR/libmediapipe_jni.dylib"
}

# ── Main ──────────────────────────────────────────────────────────────

if [[ "${1:-}" == "--setup" ]]; then
    do_setup
    shift
fi

do_build
