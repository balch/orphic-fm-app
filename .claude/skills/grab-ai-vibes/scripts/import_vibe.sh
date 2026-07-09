#!/usr/bin/env bash
# import_vibe.sh — wire a grabbed AI-vibe JSON into the repo as a real, compiling VibeProvider.
#
# Given a Vibe JSON file (e.g. one grabbed by pull_ai_vibes.sh), this:
#   1. Calls the tools:vibe-codegen Gradle task, which decodes the JSON through the app's own
#      lenient decoder and reflectively generates features/pulsar/.../vibes/<Class>Vibe.kt as a
#      real Vibe(...) Kotlin literal — no runtime JSON decode, no raw-JSON shim.
#   2. Adds a VibeCatalog entry (WIP by default, so it stays out of the picker until you
#      ear-test it — an uncataloged provider is auto-hidden anyway).
#
# Usage: import_vibe.sh <vibe.json> [--status WIP|LIVE|SHELF] [--tags "a,b"] [--force]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# scripts/ -> grab-ai-vibes/ -> skills/ -> .claude/ -> repo root
REPO="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
VIBES_DIR="$REPO/features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes"
CATALOG="$VIBES_DIR/VibeCatalog.kt"

JSON=""
STATUS="WIP"
TAGS="ai"
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --status) STATUS="${2:?}"; shift ;;
    --tags) TAGS="${2:?}"; shift ;;
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    -*) echo "unknown arg: $1" >&2; exit 2 ;;
    *) JSON="$1" ;;
  esac
  shift
done

[ -n "$JSON" ] || { echo "usage: import_vibe.sh <vibe.json> [--status WIP|LIVE|SHELF] [--tags a,b] [--force]" >&2; exit 2; }
[ -f "$JSON" ] || { echo "no such file: $JSON" >&2; exit 2; }
case "$STATUS" in WIP|LIVE|SHELF) ;; *) echo "--status must be WIP, LIVE, or SHELF" >&2; exit 2 ;; esac
[ -f "$CATALOG" ] || { echo "VibeCatalog.kt not found at $CATALOG — wrong repo layout?" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "jq is required (brew install jq)" >&2; exit 2; }

# Display name = the vibe's "name" field. Must match provider.name for the catalog key to line up.
# Uses jq (not grep/sed) so JSON escaping is handled correctly — a shell regex can't tell an
# escaped quote (\") inside the name apart from the string's closing quote, and would truncate.
NAME="$(jq -r '.name // empty' "$JSON" 2>/dev/null || true)"
[ -n "$NAME" ] || { echo "could not read a \"name\" field from $JSON" >&2; exit 2; }

# Class name = CamelCase of the display name + "Vibe" (e.g. "Saffron Mirage" -> SaffronMirageVibe).
CLASS=""
for w in $(printf '%s' "$NAME" | sed -E 's/[^[:alnum:]]+/ /g'); do
  CLASS="$CLASS$(printf '%s' "${w:0:1}" | tr '[:lower:]' '[:upper:]')${w:1}"
done
[ -n "$CLASS" ] || CLASS="Imported"
case "$CLASS" in [0-9]*) CLASS="V$CLASS" ;; esac
CLASS="${CLASS}Vibe"
TARGET="$VIBES_DIR/$CLASS.kt"

# Kotlin listOf("a", "b") from a comma list.
TAGK=""
IFS=',' read -ra _tags <<< "$TAGS"
for t in "${_tags[@]}"; do
  t="$(printf '%s' "$t" | xargs)"; [ -z "$t" ] && continue
  TAGK="$TAGK\"$t\", "
done
TAGK="listOf(${TAGK%, })"

echo "Importing \"$NAME\" -> $CLASS ($STATUS)"

# --- guards -------------------------------------------------------------------
# -F: NAME is a literal string here, not a regex — an AI-generated name containing a `.` or `*`
# must not false-match an unrelated existing catalog line.
CATALOG_HAS=0
grep -qF "\"$NAME\" to CatalogEntry" "$CATALOG" && CATALOG_HAS=1

# --- 1. generate the provider (real Kotlin, via tools:vibe-codegen) -----------
if [ -f "$TARGET" ] && [ "$FORCE" -ne 1 ]; then
  echo "  provider already exists: $TARGET (pass --force to overwrite) — skipping codegen"
else
  JSON_ABS="$(cd "$(dirname "$JSON")" && pwd)/$(basename "$JSON")"
  # Each dynamic value is individually quoted inside --args so Gradle's own whitespace
  # tokenizer keeps a path containing a space as one argument instead of splitting it.
  ( cd "$REPO" && ./gradlew -q :tools:vibe-codegen:run --args="\"$JSON_ABS\" --class-name \"$CLASS\" --out-dir \"$VIBES_DIR\"" )
  echo "  wrote $TARGET"
fi

# --- 2. catalog entry (inserted after the LAST existing entry, so a LIVE import isn't the
#        default vibe and the map order stays stable) ------------------------------------------
if [ "$CATALOG_HAS" -eq 1 ]; then
  echo "  catalog already lists \"$NAME\" — left as-is"
else
  ENTRY="        \"$NAME\" to CatalogEntry(VibeStatus.$STATUS, tags = $TAGK),"
  LAST="$(grep -n 'to CatalogEntry(' "$CATALOG" | tail -1 | cut -d: -f1)"
  if [ -z "$LAST" ]; then
    echo "  could not find an anchor entry in $CATALOG — add the catalog line by hand:" >&2
    echo "    $ENTRY" >&2
  else
    awk -v n="$LAST" -v ins="$ENTRY" 'NR==n{print; print ins; next} {print}' "$CATALOG" > "$CATALOG.tmp" && mv "$CATALOG.tmp" "$CATALOG"
    echo "  added catalog entry ($STATUS) after line $LAST"
  fi
fi

echo "Done. Verify: ./gradlew :features:pulsar:compileKotlinJvm"
[ "$STATUS" = "WIP" ] && echo "Hidden until ear-tested; see it on desktop with -Pcatalog=wip, or flip to LIVE in VibeCatalog.kt."
exit 0
