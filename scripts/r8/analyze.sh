#!/usr/bin/env bash
# Orchestrates R8 Configuration Analyzer for the Orpheus / DJ Android app.
#
# Flow:
#   1. Clear previous run, archive prior result -> history.txt
#   2. ./gradlew assembleRelease with the R8 dump-keep-radius system property
#   3. Convert generated .pb -> keepruleradius.json
#   4. Compute Optimization / Obfuscation / Shrinking scores -> analysis_result.txt
#   5. Print top-impact + subsumed rules
#
# Requires R8 >= 9.3.7-dev (Path A). If the build runs but no .pb is produced,
# the current AGP/R8 doesn't support this analyzer yet — invoke Claude with the
# r8-analyzer skill to fall back to Path B (heuristic review) instead.
#
# Usage:
#   ./scripts/r8/analyze.sh                          # default: :apps:orpheus:androidApp:assembleRelease
#   ./scripts/r8/analyze.sh <gradle-task>            # e.g. :apps:djapp:androidApp:assembleRelease
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT/tmp/r8analysis"
SCRIPT_DIR="$ROOT/scripts/r8"
GRADLE_TASK="${1:-:apps:orpheus:androidApp:assembleRelease}"

cd "$ROOT"

# Python check (protobuf is the only third-party dep we need)
if ! command -v python3 >/dev/null 2>&1; then
    echo "error: python3 not found on PATH" >&2
    exit 1
fi
if ! python3 -c "import google.protobuf" >/dev/null 2>&1; then
    echo "error: 'protobuf' python package missing. Install with: pip3 install protobuf" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

# Step 3 of the skill: archive previous results & wipe intermediates.
if [ -f "$OUT_DIR/analysis_result.txt" ]; then
    cat "$OUT_DIR/analysis_result.txt" > "$OUT_DIR/history.txt"
    rm -f "$OUT_DIR/analysis_result.txt"
fi
rm -f "$OUT_DIR/keepruleradius.json"
rm -f "$OUT_DIR"/*.pb 2>/dev/null || true

echo "==> Running $GRADLE_TASK with R8 dump-keep-radius"
./gradlew "$GRADLE_TASK" \
    "-Dcom.android.tools.r8.dumpkeepradiustodirectory=$OUT_DIR"

PB_COUNT=$(find "$OUT_DIR" -name "*.pb" -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$PB_COUNT" -eq 0 ]; then
    cat >&2 <<'MSG'

==> No .pb produced.

Your bundled R8 likely predates 9.3.7-dev (the Configuration Analyzer threshold).
The release build still succeeded — you just don't have quantitative data.

Fall back to Path B (heuristic review):
  1. Open the project in Claude Code.
  2. Ask: "review proguard-rules.pro with the r8-analyzer skill"
  3. Claude will compare your rules against REDUNDANT-RULES.md / KEEP-RULES-IMPACT-HIERARCHY.md
     and produce the same report shape from manual analysis.
MSG
    exit 2
fi

echo "==> Converting .pb -> JSON"
(cd "$SCRIPT_DIR" && python3 convert_pb_to_json.py "$OUT_DIR"/*.pb "$OUT_DIR/keepruleradius.json")

echo "==> Computing scores"
(cd "$ROOT" && python3 "$SCRIPT_DIR/analyze_json.py" "$OUT_DIR/keepruleradius.json")

echo
echo "==> Top-impact and subsumed rules"
python3 "$SCRIPT_DIR/report_impactful_rules.py" "$OUT_DIR/keepruleradius.json" > "$OUT_DIR/impactful_rules.json"
python3 -c "
import json, sys
with open('$OUT_DIR/impactful_rules.json') as f:
    d = json.load(f)
print('Top 5 (non-subsumed):')
for r in d['top_5_impact_keep_rules']:
    print(f\"  {r['impact_pct']:>7}  {r['source']}\")
if d['subsumed']:
    print(f'\nSubsumed rules: {len(d[\"subsumed\"])} (see impactful_rules.json)')
"

echo
echo "==> Done. Artifacts in $OUT_DIR/"
echo "    keepruleradius.json    full graph (large)"
echo "    analysis_result.txt    scores"
echo "    impactful_rules.json   top-impact + subsumed lists"
echo "    history.txt            previous run's scores (if any)"
echo
echo "Next: invoke Claude with the r8-analyzer skill to produce the formatted Markdown report."
