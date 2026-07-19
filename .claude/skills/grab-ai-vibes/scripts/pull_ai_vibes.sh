#!/usr/bin/env bash
# pull_ai_vibes.sh — collect the AI-created Vibe JSON archives from a running Orphic DJ app.
#
# Default: dry listing of what's archived on each platform (nothing copied).
# With --dest DIR: also copies (JVM) / pulls (Android) the files into DIR/{jvm,android}/.
#
# Usage:
#   pull_ai_vibes.sh [--all|--jvm|--android] [--dest DIR] [--import] [--package PKG] [--serial SERIAL]
#
# --import wires each collected vibe into the repo as a WIP JSON-backed VibeProvider (needs --dest;
# delegates to import_vibe.sh). Android storage is private to the app, so retrieval uses
# `adb exec-out run-as <pkg>`
# (works only on a DEBUGGABLE build; a release/Play build refuses run-as). `exec-out` is used
# instead of `shell` because `shell` rewrites newlines and corrupts JSON.
set -euo pipefail
shopt -s nullglob

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SOURCES="all"          # all | jvm | android
IMPORT=0               # --import: wire each collected vibe into the repo (WIP)
DEST=""
PKG=""                 # explicit android package override
SERIAL="${ANDROID_SERIAL:-}"

JVM_DIR="$HOME/.config/orpheus-dj/ai-vibes"
ANDROID_SUBDIR="files/ai-vibes"

usage() { sed -n '2,14p' "$0"; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --all) SOURCES="all" ;;
    --jvm) SOURCES="jvm" ;;
    --android) SOURCES="android" ;;
    --dest) DEST="${2:?--dest needs a directory}"; shift ;;
    --package) PKG="${2:?--package needs a value}"; shift ;;
    --serial) SERIAL="${2:?--serial needs a value}"; shift ;;
    --import) IMPORT=1 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
  shift
done

# Display name = the "name" field in the JSON, via jq so escaped quotes in the name don't
# truncate it (a shell regex can't tell \" apart from the string's closing quote). Best-effort;
# falls back to "?" if jq is missing or the field can't be read.
name_of() {
  if command -v jq >/dev/null 2>&1; then
    local n; n="$(jq -r '.name // empty' "$1" 2>/dev/null || true)"
    [ -n "$n" ] && printf '%s\n' "$n" || printf '?\n'
  else
    printf '?\n'
  fi
}

adb_cmd() { if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }

collect_jvm() {
  echo "== JVM desktop =="
  if [ ! -d "$JVM_DIR" ]; then
    echo "  (no archive dir at $JVM_DIR — nothing applied on this machine yet)"
    return
  fi
  local files=("$JVM_DIR"/*.json) copied=0
  if [ ${#files[@]} -eq 0 ]; then echo "  (dir exists but is empty)"; return; fi
  local out=""
  [ -n "$DEST" ] && { out="$DEST/jvm"; mkdir -p "$out"; }
  for f in "${files[@]}"; do
    printf '  %s → "%s"\n' "$(basename "$f")" "$(name_of "$f")"
    if [ -n "$out" ]; then cp "$f" "$out/"; copied=$((copied+1)); fi
  done
  echo "  ${#files[@]} vibe(s)$([ -n "$out" ] && echo ", copied $copied to $out")"
}

discover_packages() {
  adb_cmd shell pm list packages 2>/dev/null | sed 's/package://' | grep 'org\.balch\.djapp' || true
}

collect_android() {
  echo "== Android =="
  if ! command -v adb >/dev/null 2>&1; then echo "  (adb not on PATH)"; return; fi
  if ! adb_cmd get-state >/dev/null 2>&1; then
    echo "  (no authorized device — check 'adb devices'; pass --serial for multiple)"
    return
  fi

  local candidates
  if [ -n "$PKG" ]; then candidates="$PKG"; else candidates="$(discover_packages)"; fi
  if [ -z "$candidates" ]; then
    echo "  (no org.balch.djapp* package installed — 'adb shell pm list packages | grep djapp')"
    return
  fi

  local found_any=0 out=""
  [ -n "$DEST" ] && { out="$DEST/android"; mkdir -p "$out"; }
  local pkg
  for pkg in $candidates; do
    # adb exec-out doesn't reliably separate the device's stderr or propagate its exit code, so
    # don't trust either: grab the raw listing and keep only real *.json names. That drops the
    # "run-as: not debuggable" / "ls: No such file" noise no matter which stream it lands on, and
    # naturally skips non-debuggable builds and devices where no vibe has been applied yet.
    local listing files f
    # -1 forces one entry per line: toybox's `ls` on-device defaults to multi-column output
    # even when piped through exec-out (it doesn't detect the non-tty the way GNU ls does),
    # which previously merged multiple filenames onto one "line" and corrupted the pull.
    listing="$(adb_cmd exec-out run-as "$pkg" sh -c "ls -1 $ANDROID_SUBDIR 2>/dev/null" 2>/dev/null || true)"
    files="$(printf '%s\n' "$listing" | grep -E '\.json$' || true)"
    [ -z "$files" ] && continue
    found_any=1
    echo "  package $pkg:"
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      if [ -n "$out" ]; then
        adb_cmd exec-out run-as "$pkg" cat "$ANDROID_SUBDIR/$f" > "$out/$f"
        printf '    %s → "%s"\n' "$f" "$(name_of "$out/$f")"
      else
        printf '    %s\n' "$f"
      fi
    done <<< "$files"
  done

  if [ "$found_any" -eq 0 ]; then
    echo "  (found package(s) but no readable ai-vibes/ — is this a debuggable build with vibes applied?)"
  elif [ -n "$out" ]; then
    echo "  pulled to $out"
  fi
}

if [ "$SOURCES" = "all" ] || [ "$SOURCES" = "jvm" ]; then collect_jvm; fi
if [ "$SOURCES" = "all" ] || [ "$SOURCES" = "android" ]; then collect_android; fi

if [ "$IMPORT" -eq 1 ]; then
  echo; echo "== import =="
  if [ -z "$DEST" ]; then
    echo "  --import needs --dest (it imports the files collected there)"; exit 2
  fi
  imported=0
  for f in "$DEST"/jvm/*.json "$DEST"/android/*.json; do
    [ -e "$f" ] || continue
    "$SELF_DIR/import_vibe.sh" "$f" || echo "  import failed: $f"
    imported=$((imported+1))
  done
  [ "$imported" -eq 0 ] && echo "  (nothing collected under $DEST to import)"
fi
exit 0
