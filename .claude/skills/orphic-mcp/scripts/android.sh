#!/usr/bin/env bash
# android.sh - drive the Orphic MCP server on an Android ai build over adb.
#
# Usage:
#   android.sh --serial <id> [--package <pkg>] <command> [args]
#
# Commands:
#   connect [--host-port 47821]  forward a host port to the app's MCP port, print URL and token
#   token                        print files/mcp.json ({url, token, port})
#   list                         list mix-captures/ and stem-captures/ on the device
#   pull <dest>                  copy both capture folders to <dest>/mix-captures and <dest>/stem-captures
#   clean-stems                  delete everything under files/stem-captures/
#
# --serial is required (a phone may sit next to an emulator). run-as needs a DEBUGGABLE build
# (aiDebug). exec-out is used instead of shell because shell rewrites bytes in WAVs and JSON.
set -euo pipefail

SERIAL=""
PKG=""
HOST_PORT=47821

usage() { sed -n '2,15p' "$0"; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    --package) PKG="${2:?--package needs a value}"; shift 2 ;;
    -h|--help) usage 0 ;;
    -*) echo "unknown arg: $1" >&2; usage 1 ;;
    *) break ;;
  esac
done

CMD="${1:-}"
[ -n "$CMD" ] || usage 1
shift

[ -n "$SERIAL" ] || { echo "--serial is required (see 'adb devices')" >&2; exit 2; }

adb_cmd() { adb -s "$SERIAL" "$@"; }

discover_package() {
  local pkgs
  if ! pkgs="$(adb_cmd shell pm list packages 2>&1)"; then
    echo "adb failed for $SERIAL: $pkgs" >&2
    return 1
  fi
  pkgs="$(printf '%s\n' "$pkgs" | tr -d '\r')"
  if printf '%s\n' "$pkgs" | grep -qx 'package:org.balch.djapp.ai.debug'; then
    echo "org.balch.djapp.ai.debug"
  elif printf '%s\n' "$pkgs" | grep -qx 'package:org.balch.djapp.ai'; then
    echo "org.balch.djapp.ai"
  else
    echo "no org.balch.djapp.ai[.debug] package on $SERIAL (install the aiDebug apk)" >&2
    return 1
  fi
}

# Reads a file from the app's private dir. A non-debuggable build prints a run-as error.
read_app_file() { adb_cmd exec-out run-as "$PKG" cat "$1" 2>/dev/null | tr -d '\r'; }

# Lists a directory, one name per line. Empty when it does not exist.
ls_app_dir() { adb_cmd exec-out "run-as $PKG sh -c 'ls -1 $1 2>/dev/null'" 2>/dev/null | tr -d '\r' || true; }

read_mcp_json() {
  local json
  json="$(read_app_file files/mcp.json || true)"
  case "$json" in
    '{'*) printf '%s\n' "$json" ;;
    *) echo "cannot read files/mcp.json from $PKG (not running yet, or not a debuggable build: use the AI tab MCP chip)" >&2
       return 1 ;;
  esac
}

# Pulls a JSON string or number field out of mcp.json without needing jq.
json_field() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r ".$2 // empty" || true
  else
    { printf '%s' "$1" | grep -oE "\"$2\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|[0-9]+)" | head -n1 \
      | grep -oE '(\"[^\"]*\"|[0-9]+)$' | tr -d '"'; } || true
  fi
}

# run-as errors arrive on stdout through exec-out, so prove it works before trusting any output.
require_run_as() {
  local out
  out="$(adb_cmd exec-out "run-as $PKG sh -c 'echo ok'" 2>&1 | tr -d '\r' || true)"
  if [ "$out" != "ok" ]; then
    echo "run-as failed for $PKG: $out" >&2
    echo "(needs a debuggable build, i.e. aiDebug; otherwise use the AI tab MCP chip)" >&2
    exit 1
  fi
}

case "$CMD" in
  connect|token|list|pull|clean-stems) ;;
  *) echo "unknown command: $CMD" >&2; usage 1 ;;
esac

if [ -z "$PKG" ]; then PKG="$(discover_package)" || exit 1; fi

case "$CMD" in
  connect)
    while [ $# -gt 0 ]; do
      case "$1" in
        --host-port) HOST_PORT="${2:?--host-port needs a value}"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
      esac
    done
    if [ "$HOST_PORT" = "47801" ]; then
      echo "host port 47801 belongs to the desktop app; pick another" >&2; exit 2
    fi
    JSON="$(read_mcp_json)"
    DEVICE_PORT="$(json_field "$JSON" port)"
    TOKEN="$(json_field "$JSON" token)"
    [ -n "$DEVICE_PORT" ] && [ -n "$TOKEN" ] || { echo "mcp.json has no port or token" >&2; exit 1; }
    adb_cmd forward "tcp:$HOST_PORT" "tcp:$DEVICE_PORT" >/dev/null
    echo "package: $PKG"
    echo "forward: host tcp:$HOST_PORT -> device tcp:$DEVICE_PORT"
    echo "url:     http://127.0.0.1:$HOST_PORT/mcp"
    echo "token:   $TOKEN"
    ;;
  token)
    read_mcp_json
    ;;
  list)
    require_run_as
    for d in mix-captures stem-captures; do
      echo "== $d =="
      ls_app_dir "files/$d"
    done
    ;;
  pull)
    DEST="${1:?pull needs a destination directory}"
    require_run_as
    mkdir -p "$DEST"
    DIRS=()
    for d in mix-captures stem-captures; do
      # exec-out drops the device exit code, so decide from printed output. adb joins args with
      # spaces for the device shell, so each device command goes in as ONE string.
      if [ "$(adb_cmd exec-out "run-as $PKG sh -c 'test -d files/$d && echo yes'" 2>/dev/null | tr -d '\r')" = "yes" ]; then
        DIRS+=("$d")
      else
        echo "skipping $d (not on the device yet)" >&2
      fi
    done
    if [ ${#DIRS[@]} -eq 0 ]; then echo "nothing to pull"; exit 0; fi
    adb_cmd exec-out run-as "$PKG" tar cf - -C files "${DIRS[@]}" | tar xf - -C "$DEST"
    echo "pulled ${DIRS[*]} to $DEST"
    ;;
  clean-stems)
    require_run_as
    echo "deleting from files/stem-captures/ on $PKG:"
    ls_app_dir files/stem-captures
    adb_cmd exec-out "run-as $PKG sh -c 'rm -rf files/stem-captures/*'"
    echo "done"
    ;;
  *)
    echo "unknown command: $CMD" >&2; usage 1 ;;
esac
