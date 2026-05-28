#!/usr/bin/env bash
# Capture a Perfetto trace from a connected Android device for analysis with
# the perfetto-trace-analysis skill.
#
# Usage:
#   ./scripts/perf/capture-trace.sh                # default: audio profile, 10s
#   ./scripts/perf/capture-trace.sh ui             # UI profile
#   ./scripts/perf/capture-trace.sh audio 20       # audio profile, 20s
#   ./scripts/perf/capture-trace.sh ui 15 orpheus  # UI, 15s, label as orpheus
#
# Output: tmp/traces/<profile>-<label>-<timestamp>.perfetto-trace
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROFILE="${1:-audio}"
DURATION_SEC="${2:-10}"
LABEL="${3:-orpheus}"

CFG="$ROOT/scripts/perf/${PROFILE}.cfg"
if [ ! -f "$CFG" ]; then
    echo "error: unknown profile '$PROFILE' (expected audio | ui)" >&2
    echo "       no config at $CFG" >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "error: adb not found on PATH" >&2
    exit 1
fi

DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "error: no Android device connected via adb" >&2
    exit 1
fi

OUT_DIR="$ROOT/tmp/traces"
mkdir -p "$OUT_DIR"
TS=$(date +%Y%m%d-%H%M%S)
OUT_FILE="$OUT_DIR/${PROFILE}-${LABEL}-${TS}.perfetto-trace"
DEVICE_OUT="/data/misc/perfetto-traces/orpheus-${TS}.perfetto-trace"

# Build a temp config with the requested duration substituted in.
TMP_CFG="$(mktemp -t perfetto-cfg.XXXXXX.cfg)"
trap 'rm -f "$TMP_CFG"' EXIT
awk -v dur_ms=$((DURATION_SEC * 1000)) '
    /^duration_ms:/ { print "duration_ms: " dur_ms; next }
    { print }
' "$CFG" > "$TMP_CFG"

echo "==> Capturing $PROFILE profile for ${DURATION_SEC}s (label: $LABEL)"
adb shell perfetto -c - --txt -o "$DEVICE_OUT" < "$TMP_CFG"

echo "==> Pulling trace to $OUT_FILE"
adb pull "$DEVICE_OUT" "$OUT_FILE" >/dev/null
adb shell rm -f "$DEVICE_OUT" 2>/dev/null || true

SIZE=$(du -h "$OUT_FILE" | cut -f1)
echo "==> Done ($SIZE)"
echo
echo "Open in Claude:"
echo "  \"Analyze $OUT_FILE with the perfetto-trace-analysis skill\""
echo
echo "Or in the Perfetto UI: https://ui.perfetto.dev"
