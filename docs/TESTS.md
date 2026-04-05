# Testing Guide

Orphic-FM uses a multi-layered testing strategy spanning C++ unit tests, Kotlin integration tests, WAV snapshot comparisons, and manual verification via test plans.

## C++ DSP Tests (`liborpheus_dsp/test/`)

The C++ test suite validates the DSP engine in isolation -- voice rendering, effects processing, graph routing, and cross-engine parity with the Kotlin/JSyn implementation.

### Building and Running

```bash
cd liborpheus_dsp
mkdir -p build_test && cd build_test
cmake .. -DCMAKE_BUILD_TYPE=Debug
make -j$(nproc)
./orpheus_dsp_test
```

### Test Suites

| File | What it tests |
|------|--------------|
| `test_voices.cpp` | Voice gate/tune, engine switching, harmonics/timbre/morph |
| `test_engine_render.cpp` | Per-engine WAV snapshot rendering for all 16 Plaits engines |
| `test_graph.cpp` | ODWG binary graph loading, topological sort, unit wiring |
| `test_units.cpp` | Individual DSP units: delay, distortion, master output |
| `test_effects.cpp` | Effects chain: drive, delay mix, reverb, vibrato |
| `test_lfo.cpp` | HyperLFO modes (AND/OR/FM/OFF), frequency, modulation routing |
| `test_drums_graph.cpp` | Drum voice triggers, chain vs direct routing, resonator wet/dry |
| `test_output_chain.cpp` | Full signal path from voices through effects to stereo output |
| `test_control_routing.cpp` | SET_PORT command routing, voice parameter forwarding |
| `test_headroom.cpp` | Headroom and clipping analysis across engine types |
| `test_snapshots.cpp` | WAV snapshot tests comparing C++ vs JSyn engine output |
| `test_benchmark.cpp` | Performance: frames/sec, CPU budget at 48kHz |

### WAV Snapshot Testing

Engine render tests produce WAV files in `liborpheus_dsp/test/output/`:
- `cpp_raw_<engine>.wav` -- C++ engine output (128 frames, mono)
- `jsyn_raw_<engine>.wav` -- JSyn reference output (from `OfflineAudioEngine`)

Compare with:
```bash
python3 tools/compare_engines.py liborpheus_dsp/test/output/
```

This reports RMS delta, peak delta, and spectral similarity per engine.

### Running Specific Tests

```bash
# Run a single test suite
./orpheus_dsp_test --gtest_filter="VoiceTest.*"
./orpheus_dsp_test --gtest_filter="GraphTest.*"
./orpheus_dsp_test --gtest_filter="LFOTest.*"

# Run with verbose output
./orpheus_dsp_test --gtest_print_time=1
```

## Kotlin Tests

### Unit Tests

```bash
# All JVM tests
./gradlew jvmTest

# Single module
./gradlew :core:plugins:flux:jvmTest
./gradlew :features:lfo:jvmTest
```

### WASM Compile Check

```bash
./gradlew :apps:orpheus:compileKotlinWasmJs
```

### Android Instrumented Tests

```bash
./gradlew :apps:androidApp:connectedDebugAndroidTest
```

## Manual Test Plans (`test_plans/`)

Structured test plans for manual verification of each feature. Each plan has numbered steps, expected outcomes, and edge cases.

| Plan | Feature |
|------|---------|
| `808_drums_test_plan.md` | Drum engine triggers, sequencer, accent |
| `distortion_test_plan.md` | Drive amount, clean/distortion mix |
| `global_controls_test_plan.md` | Master volume, vibrato, bend |
| `grains_test_plan.md` | Granular engine parameters |
| `hyper_lfo_test_plan.md` | LFO modes, frequencies, modulation targets |
| `integration_test_plan.md` | Full system: voices + effects + MIDI + presets |
| `midi_test_plan.md` | MIDI learn, CC mapping, note handling |
| `mod_delay_test_plan.md` | Dual delay, feedback, LFO modulation |
| `preset_test_plan.md` | Save/load/rename presets |
| `resonator_test_plan.md` | Resonator filter bank, wet/dry mix |
| `topographic_pattern_test_plan.md` | Grids/Marbles pattern generators |
| `voice_fm_test_plan.md` | FM self-feedback, cross-modulation |

## Cross-Platform Verification

To verify audio parity across platforms:

1. **Desktop JSyn** (reference): `./gradlew :apps:orpheus:run`
2. **Desktop C++**: `./gradlew buildDesktopNative && ./gradlew :apps:orpheus:run -Dorpheus.engine=cpp`
3. **WASM**: `./gradlew :apps:orpheus:wasmJsBrowserDevelopmentRun`

Play the same patch on each platform and compare:
- Voice timbre and tuning
- Effects (delay, reverb, distortion)
- LFO modulation depth and rate
- Drum triggers and accent response

## WASM Debugging

Use Playwright MCP or browser DevTools to debug the running WASM app:

```bash
# Start dev server
./gradlew :apps:orpheus:wasmJsBrowserDevelopmentRun
```

- Open `http://localhost:8080/` in Chrome
- JS bridge files log with tag prefixes: `[DSP-Worker]`, `[MP]`
- KmLogging output appears in the browser console
- Worker errors appear as `TypeError` in the console -- check `orpheus-dsp-worker.js` line numbers
