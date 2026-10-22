#!/usr/bin/env bash
# Headless mix measurement for Pulsar vibes: dumps each vibe's port writes from Kotlin, replays
# them through the C++ DJ graph with the vibe_measure suite, and writes one JSON per vibe plus
# summary.md under liborpheus_dsp/test/output/vibe_measure/.
#
#   scripts/mix/measure.sh live                        # every LIVE vibe
#   scripts/mix/measure.sh "Orphic Lore,Fire Sky"
#   VIBE_TRIM=2:-4.4,3:-2.6 VIBE_TAG=trim scripts/mix/measure.sh "Orphic Lore"
#   VIBE_SET=energy=0.7 VIBE_TAG=e70 NO_DUMP=1 scripts/mix/measure.sh "Orphic Lore"
#
# Passed through to the suite: VIBE_SECONDS (240), VIBE_DIST (0.30), VIBE_SET, VIBE_TRIM, VIBE_TAG,
# VIBE_WAV, VIBE_OUTRO_AT, VIBE_OVERVIEW (also writes <name>.overview.json for the Mix Bench). VIBE_SET reaches Pulsar ports only: energy and complexity work, but
# reverb/delay sends are scaled by space in Kotlin, so a space override leaves the sends alone.
# NO_DUMP=1 reuses the last dump (skip it only when no vibe file changed).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
vibes="${1:-live}"
dumps="features/pulsar/build/vibe-recordings"
out="${VIBE_MEASURE_DIR:-liborpheus_dsp/test/output/vibe_measure}"
build="liborpheus_dsp/build-desktop"

if [[ -z "${NO_DUMP:-}" ]]; then
    RECORD_VIBE="$vibes" ./gradlew -q :features:pulsar:jvmTest \
        --tests '*VibePortDumpRenderHarness*' -PrenderHarness --rerun
fi

if [[ ! -d "$build" ]]; then
    CMAKE_GENERATOR=Ninja cmake -S liborpheus_dsp -B "$build" -DEURORACK_DIR="$EURORACK_DIR" \
        -DBUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON >/dev/null
fi
cmake --build "$build" --target orpheus_dsp_test -j"$(sysctl -n hw.ncpu)" >/dev/null

# The slug rule matches VibePortDumpRenderHarness.
if [[ "$vibes" == "live" ]]; then
    files=$(ls "$dumps"/*.ports)
else
    files=$(echo "$vibes" | tr ',' '\n' | while read -r name; do
        slug=$(echo "$name" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/_/g; s/^_+|_+$//g')
        echo "$dumps/$slug.ports"
    done)
fi

mkdir -p "$out"
# One process per vibe: stmlib::Random's seed is global, so vibes cannot share a process.
for f in $files; do
    log="$out/$(basename "$f" .ports)${VIBE_TAG:+_$VIBE_TAG}.log"
    VIBE_PORTS="$f" VIBE_MEASURE_DIR="$out" "$build/orpheus_dsp_test" vibe_measure >"$log" 2>&1 &
done
wait

python3 -I scripts/mix/summarize.py "$out" > "$out/summary.md"
echo "Wrote $out/summary.md"
