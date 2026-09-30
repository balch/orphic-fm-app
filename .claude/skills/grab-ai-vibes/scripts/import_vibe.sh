#!/usr/bin/env bash
# import_vibe.sh — wire a grabbed AI-vibe JSON into the repo as a real, compiling VibeProvider.
#
# Given a Vibe JSON file (e.g. one grabbed by pull_ai_vibes.sh), this:
#   1. Calls the tools:vibe-codegen Gradle task, which decodes the JSON through the app's own
#      lenient decoder and reflectively generates features/pulsar/.../vibes/<Class>Vibe.kt as a
#      real Vibe(...) Kotlin literal — no runtime JSON decode, no raw-JSON shim. The provider's
#      name is VibeNames.<CONST>: the constant VibeNames.kt already holds for that name, else
#      one derived from it.
#   2. Adds that constant to VibeNames.kt in alphabetical position. An entry with the same name
#      is kept; one with a different name stops the import before anything is written.
#   3. Adds a VibeCatalog entry (WIP by default, so it stays out of the picker until you
#      ear-test it — an uncataloged provider is auto-hidden anyway).
# AlbumCatalog.kt is left alone: the vibe lands on STEALTH until another album lists it.
#
# Usage: import_vibe.sh <vibe.json> [--status WIP|LIVE|SHELF] [--tags "a,b"] [--force]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# scripts/ -> grab-ai-vibes/ -> skills/ -> .claude/ -> repo root
REPO="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
VIBES_DIR="$REPO/features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes"
CATALOG="$VIBES_DIR/VibeCatalog.kt"
NAMES="$VIBES_DIR/VibeNames.kt"

JSON=""
STATUS="WIP"
TAGS="ai"
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --status) STATUS="${2:?}"; shift ;;
    --tags) TAGS="${2:?}"; shift ;;
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    -*) echo "unknown arg: $1" >&2; exit 2 ;;
    *) JSON="$1" ;;
  esac
  shift
done

[ -n "$JSON" ] || { echo "usage: import_vibe.sh <vibe.json> [--status WIP|LIVE|SHELF] [--tags a,b] [--force]" >&2; exit 2; }
[ -f "$JSON" ] || { echo "no such file: $JSON" >&2; exit 2; }
case "$STATUS" in WIP|LIVE|SHELF) ;; *) echo "--status must be WIP, LIVE, or SHELF" >&2; exit 2 ;; esac
[ -f "$CATALOG" ] || { echo "VibeCatalog.kt not found at $CATALOG — wrong repo layout?" >&2; exit 2; }
[ -f "$NAMES" ] || { echo "VibeNames.kt not found at $NAMES, wrong repo layout?" >&2; exit 2; }
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

# VibeNames constant: uppercase, & -> a word AND, apostrophes dropped, any other run of
# non-alphanumerics -> one _, trimmed, V_ before a leading digit ("Space & Drums" -> SPACE_AND_DRUMS).
APOS="'"
CONST="${NAME//&/ AND }"
CONST="${CONST//"$APOS"/}"; CONST="${CONST//’/}"; CONST="${CONST//‘/}"
CONST="$(printf '%s' "$CONST" | LC_ALL=C tr '[:lower:]' '[:upper:]' | LC_ALL=C tr -cs '[:alnum:]' '_')"
CONST="${CONST#_}"; CONST="${CONST%_}"
[ -n "$CONST" ] || { echo "could not derive a VibeNames constant from \"$NAME\"" >&2; exit 2; }
case "$CONST" in [0-9]*) CONST="V_$CONST" ;; esac

# NAME as a Kotlin string literal body (escape \ " and $), the form VibeNames.kt holds it in.
# jq's split/join is literal, so there are no regex or replacement-escape surprises.
NAME_KT="$(jq -r '.name | split("\\") | join("\\\\") | split("\"") | join("\\\"") | split("$") | join("\\$")' "$JSON")"

# A name VibeNames already holds keeps its constant, even a hand-picked one like JUPITER.
EXISTING="$(grep -F "= VibeName(\"$NAME_KT\")" "$NAMES" | head -1 || true)"
if [[ $EXISTING =~ val[[:space:]]+([A-Z0-9_]+) ]]; then CONST="${BASH_REMATCH[1]}"; fi

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
# -F: the constant and name are literal strings here, not regexes. An AI-generated name
# containing a `.` or `*` must not false-match an unrelated existing line.
CATALOG_HAS=0
grep -qF "VibeNames.$CONST to CatalogEntry" "$CATALOG" && CATALOG_HAS=1

# Two names deriving one constant would share a VibeNames entry: stop before writing anything.
NAMES_HAS=0
NAMES_LINE="$(grep -E "^[[:space:]]*val $CONST[[:space:]]*=" "$NAMES" | head -1 || true)"
if [ -n "$NAMES_LINE" ]; then
  case "$NAMES_LINE" in
    *"= VibeName(\"$NAME_KT\")"*) NAMES_HAS=1 ;;
    *)
      echo "VibeNames.$CONST already exists for a different name, and \"$NAME\" derives the same constant:" >&2
      echo "  $NAMES_LINE" >&2
      echo "Rename one of the two vibes. Nothing was changed." >&2
      exit 1 ;;
  esac
fi

# --- 1. generate the provider (real Kotlin, via tools:vibe-codegen) -----------
if [ -f "$TARGET" ] && [ "$FORCE" -ne 1 ]; then
  echo "  provider already exists: $TARGET (pass --force to overwrite) — skipping codegen"
else
  JSON_ABS="$(cd "$(dirname "$JSON")" && pwd)/$(basename "$JSON")"
  # Each dynamic value is individually quoted inside --args so Gradle's own whitespace
  # tokenizer keeps a path containing a space as one argument instead of splitting it.
  ( cd "$REPO" && ./gradlew -q :tools:vibe-codegen:run --args="\"$JSON_ABS\" --class-name \"$CLASS\" --name-const \"$CONST\" --out-dir \"$VIBES_DIR\"" )
  echo "  wrote $TARGET"
fi

# --- 2. VibeNames entry (alphabetical: before the first constant that sorts after it, else before
#        the closing brace). Sorts with _ below letters, the order the file is already in. ----------
if [ "$NAMES_HAS" -eq 1 ]; then
  echo "  VibeNames already has $CONST, left as-is"
else
  # ENVIRON, not awk -v: -v would turn a \\ in the name into an escape sequence.
  if VN_CONST="$CONST" VN_LINE="    val $CONST = VibeName(\"$NAME_KT\")" LC_ALL=C awk '
    BEGIN { key = "" ENVIRON["VN_CONST"]; gsub(/_/, " ", key) }
    !done && /^[[:space:]]*val [A-Z0-9_]+[[:space:]]*=/ {
      cur = $0; sub(/^[[:space:]]*val /, "", cur); sub(/[[:space:]]*=.*/, "", cur); gsub(/_/, " ", cur)
      if (cur > key) { print ENVIRON["VN_LINE"]; done = 1 }
    }
    !done && /^}/ { print ENVIRON["VN_LINE"]; done = 1 }
    { print }
    END { if (!done) exit 3 }
  ' "$NAMES" > "$NAMES.tmp"; then
    mv "$NAMES.tmp" "$NAMES"
    echo "  added VibeNames.$CONST"
  else
    rm -f "$NAMES.tmp"
    echo "  could not place $CONST in $NAMES, add it by hand:" >&2
    echo "    val $CONST = VibeName(\"$NAME_KT\")" >&2
  fi
fi

# --- 3. catalog entry (inserted after the LAST existing entry, so a LIVE import isn't the
#        default vibe and the map order stays stable) ------------------------------------------
if [ "$CATALOG_HAS" -eq 1 ]; then
  echo "  catalog already lists VibeNames.$CONST, left as-is"
else
  ENTRY="        VibeNames.$CONST to CatalogEntry(VibeStatus.$STATUS, tags = $TAGK),"
  LAST="$(grep -n 'to CatalogEntry(' "$CATALOG" | tail -1 | cut -d: -f1)"
  if [ -z "$LAST" ]; then
    echo "  could not find an anchor entry in $CATALOG — add the catalog line by hand:" >&2
    echo "    $ENTRY" >&2
  else
    awk -v n="$LAST" -v ins="$ENTRY" 'NR==n{print; print ins; next} {print}' "$CATALOG" > "$CATALOG.tmp" && mv "$CATALOG.tmp" "$CATALOG"
    echo "  added catalog entry ($STATUS) after line $LAST"
    echo "  AlbumCatalog.kt untouched: the vibe lands on STEALTH until it is listed on another album there"
  fi
fi

echo "Done. Verify: ./gradlew :features:pulsar:compileKotlinJvm"
[ "$STATUS" = "WIP" ] && echo "Hidden until ear-tested; see it on desktop with -Pcatalog=wip, or flip to LIVE in VibeCatalog.kt."
exit 0
