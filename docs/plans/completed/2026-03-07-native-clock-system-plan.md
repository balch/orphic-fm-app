# Native C++ Clock System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add sample-accurate clock, drum pattern generation (Grids), random sequencer (Marbles), and beat-quantized looper as native C++ graph units.

**Architecture:** Four new ODWG graph units (UNIT_CLOCK, UNIT_GRIDS, UNIT_MARBLES, UNIT_LOOPER) wired via the existing binary graph format. Parameters flow through OrpheusEngine atomics. Clock drives all time-dependent units via graph connections.

**Tech Stack:** C++17 (liborpheus_dsp), Kotlin DSL (WiringGraphDsl.kt / DefaultWiringGraph.kt), MI Grids pattern ROM, MI Marbles random/ramp modules

**Design doc:** `docs/plans/2026-03-07-native-clock-system-design.md`

---

## Phase A: Clock Foundation

### Task 1: Add unit type constants

Add UNIT_CLOCK, UNIT_GRIDS, UNIT_MARBLES, UNIT_LOOPER to both C++ and Kotlin.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_graph.h:7-29`
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/WiringGraphDsl.kt:1-25`

**Step 1: Add C++ unit type enum entries**

In `orpheus_graph.h`, add before `UNIT_TYPE_COUNT`:

```cpp
    UNIT_HYPER_LFO = 19,
    UNIT_CLOCK = 20,
    UNIT_GRIDS = 21,
    UNIT_MARBLES = 22,
    UNIT_LOOPER = 23,
    UNIT_TYPE_COUNT
```

**Step 2: Add Kotlin unit type constants**

In `WiringGraphDsl.kt`, add after `const val UNIT_HYPER_LFO = 19`:

```kotlin
const val UNIT_CLOCK = 20
const val UNIT_GRIDS = 21
const val UNIT_MARBLES = 22
const val UNIT_LOOPER = 23
```

**Step 3: Add DSL factory methods**

In `WiringGraphDsl.kt` class `WiringGraphBuilder`, add after the `hyperLfo()` method (~line 285):

```kotlin
fun clock(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
    addUnit(UNIT_CLOCK, name, init)

fun grids(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
    addUnit(UNIT_GRIDS, name, init)

fun marbles(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
    addUnit(UNIT_MARBLES, name, init)

fun looper(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
    addUnit(UNIT_LOOPER, name, init)
```

**Step 4: Verify Kotlin compiles**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp && ./gradlew :core:foundation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_graph.h core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/WiringGraphDsl.kt
git commit -m "feat(dsp): Add UNIT_CLOCK, UNIT_GRIDS, UNIT_MARBLES, UNIT_LOOPER type constants"
```

---

### Task 2: Add clock state to OrpheusEngine

Add double-precision clock phase accumulator, tick/beat counters, and BPM/run atomics.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1: Add clock state to OrpheusEngine struct**

Add after the reverb section (after line 198, before the closing `};`):

```cpp
    // ── Master Clock ──────────────────────────────────
    double clock_phase{0.0};          // fractional accumulator (double to avoid drift)
    int    clock_tick_count{0};       // 0..23 within each beat (24 PPQN)
    int    clock_beat_count{0};       // beats within bar (0..3 for 4/4)
    std::atomic<float> clock_bpm{120.0f};
    std::atomic<int>   clock_running{1};  // 1 = running, 0 = stopped
```

**Step 2: Verify C++ compiles**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp && cmake --build build`
Expected: Build succeeds

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h
git commit -m "feat(dsp): Add clock state to OrpheusEngine (phase, tick, beat counters)"
```

---

### Task 3: Implement unit_process_clock

The clock unit generates sample-accurate 24 PPQN clock pulses and beat pulses.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.h`
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp:316-358`

**Step 1: Add declaration to orpheus_units.h**

Add after `unit_process_reverb` declaration:

```cpp
void unit_process_clock(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
```

**Step 2: Implement unit_process_clock in orpheus_units.cpp**

Add at end of file:

```cpp
// ── UNIT_CLOCK: Sample-accurate master tempo generator ──────
// IPORT_INPUT_A = BPM (from port map, default 120)
// IPORT_INPUT_B = run/stop (1.0 = running)
// OPORT_OUT     = 24 PPQN clock pulse (1.0 on tick frames, 0.0 otherwise)
// OPORT_OUT_RIGHT = beat pulse (1.0 on quarter-note boundaries)
void unit_process_clock(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* out_tick = u->output_buffers[OPORT_OUT];
    float* out_beat = u->output_buffers[OPORT_OUT_RIGHT];

    float bpm = engine->clock_bpm.load(std::memory_order_relaxed);
    int running = engine->clock_running.load(std::memory_order_relaxed);

    // Also read from graph port (port map can override)
    float port_bpm = u->inputs[IPORT_INPUT_A].constant;
    if (port_bpm > 0.0f) bpm = port_bpm;

    float port_run = u->inputs[IPORT_INPUT_B].constant;
    if (port_run >= 0.0f) running = port_run > 0.5f ? 1 : 0;

    if (!running || bpm <= 0.0f) {
        std::memset(out_tick, 0, num_frames * sizeof(float));
        std::memset(out_beat, 0, num_frames * sizeof(float));
        return;
    }

    // Phase increment per sample: (bpm/60) * 24_ppqn / sample_rate
    double inc = (static_cast<double>(bpm) / 60.0) * 24.0 / static_cast<double>(sample_rate);

    for (int i = 0; i < num_frames; i++) {
        engine->clock_phase += inc;
        bool tick = false;
        bool beat = false;

        if (engine->clock_phase >= 1.0) {
            engine->clock_phase -= 1.0;
            tick = true;
            engine->clock_tick_count++;
            if (engine->clock_tick_count >= 24) {
                engine->clock_tick_count = 0;
                beat = true;
                engine->clock_beat_count = (engine->clock_beat_count + 1) % 4;
            }
        }

        out_tick[i] = tick ? 1.0f : 0.0f;
        out_beat[i] = beat ? 1.0f : 0.0f;
    }
}
```

**Step 3: Add dispatch case to orpheus_graph.cpp**

In the `switch (u->type)` block in `orpheus_graph_process()`, add before `default:`:

```cpp
            case UNIT_CLOCK:
                unit_process_clock(u, engine, num_frames, sr); break;
```

**Step 4: Verify C++ compiles**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp && cmake --build build`
Expected: Build succeeds

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_units.cpp liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Implement UNIT_CLOCK — sample-accurate 24 PPQN master tempo"
```

---

### Task 4: Wire clock into graph and add port map

Add the clock unit to DefaultWiringGraph.kt with BPM port map routing. Add engine port routing for tempo plugin.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Step 1: Add clock unit to DefaultWiringGraph.kt**

After the `hyperLfo` declaration (~line 109), add:

```kotlin
    // Master clock (sample-accurate tempo generator)
    val clock = clock("clock")
```

Add port map entries inside the `portMap { }` block:

```kotlin
        // Tempo clock
        map("org.balch.orpheus.plugins.tempo", "bpm", "clock", IPORT_INPUT_A)
        map("org.balch.orpheus.plugins.tempo", "run", "clock", IPORT_INPUT_B)
```

**Step 2: Add tempo port routing to orpheus_engine.cpp**

In `orpheus_engine_set_port()`, add a new `else if` block for tempo (after the reverb block, ~line 691):

```cpp
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tempo") == 0) {
        if (std::strcmp(symbol, "bpm") == 0)
            engine->clock_bpm.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "run") == 0)
            engine->clock_running.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
```

**Step 3: Verify Kotlin compiles**

Run: `./gradlew :core:dsp-engine:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 4: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`
Expected: Build succeeds

**Step 5: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Wire UNIT_CLOCK into graph with BPM port map routing"
```

---

### Task 5: Test clock output

Write a C++ test that verifies clock pulse timing accuracy.

**Files:**
- Modify: `liborpheus_dsp/test/test_main.cpp`

**Step 1: Add clock test function**

Add before `main()`:

```cpp
bool test_clock() {
    printf("\n=== Test: Clock pulse accuracy ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);

    // Create a minimal graph with just a clock unit
    // We'll test the unit processor directly instead
    GraphUnit clock_unit = {};
    clock_unit.type = 20; // UNIT_CLOCK
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    // Render 1 second = 48000 frames in 128-frame chunks
    int total_ticks = 0;
    int total_beats = 0;
    const int total_frames = 48000;

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            if (clock_unit.output_buffers[0][i] > 0.5f) total_ticks++;
            if (clock_unit.output_buffers[1][i] > 0.5f) total_beats++;
        }
    }

    // At 120 BPM: 2 beats/sec, 24 PPQN = 48 ticks/sec, 2 beats/sec
    printf("Ticks in 1 second: %d (expected 48)\n", total_ticks);
    printf("Beats in 1 second: %d (expected 2)\n", total_beats);

    bool pass = (total_ticks == 48 && total_beats == 2);
    printf("Clock test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}
```

**Step 2: Call from main()**

Add at the start of `main()`, before the existing voice test:

```cpp
    if (!test_clock()) return 1;
```

**Step 3: Build and run test**

Run:
```bash
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp
cmake -B build -DBUILD_TESTS=ON && cmake --build build
./build/orpheus_dsp_test
```
Expected: "Clock test: PASS"

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/test_main.cpp
git commit -m "test(dsp): Add clock pulse accuracy test (120 BPM → 48 ticks, 2 beats/sec)"
```

---

## Phase B: Grids (Drum Pattern Generator)

### Task 6: Add Grids sources to CMake

MI Grids core is `pattern_generator.cc`, `clock.cc`, and `resources.cc`. These need to be compiled into the library.

**Files:**
- Modify: `liborpheus_dsp/CMakeLists.txt`

**Step 1: Add Grids source list**

After the Marbles section (~line 83), add:

```cmake
# ── Grids sources ────────────────────────────────
set(GRIDS_SRC
    "${EURORACK_DIR}/grids/pattern_generator.cc"
    "${EURORACK_DIR}/grids/resources.cc"
)
```

**Step 2: Add to library target**

In the `add_library(orpheus_dsp STATIC ...)` call, add `${GRIDS_SRC}` after `${MARBLES_SRC}`:

```cmake
add_library(orpheus_dsp STATIC
    ${ORPHEUS_SRC}
    ${STMLIB_SRC}
    ${PLAITS_SRC}
    ${CLOUDS_SRC}
    ${RINGS_SRC}
    ${WARPS_SRC}
    ${MARBLES_SRC}
    ${GRIDS_SRC}
)
```

**Step 3: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake -B build -DBUILD_TESTS=ON && cmake --build build`
Expected: Build succeeds (Grids compiles with -DTEST)

Note: `grids/clock.cc` is NOT included — Grids' hardware clock uses AVR-specific timers. We implement our own sample-accurate clock via UNIT_CLOCK. The `PatternGenerator` class in `pattern_generator.cc` is the DSP core we need, and `resources.cc` provides the pattern ROM data.

**Step 4: Commit**

```bash
git add liborpheus_dsp/CMakeLists.txt
git commit -m "build: Add MI Grids pattern_generator + resources to CMake"
```

---

### Task 7: Add Grids state to OrpheusEngine

Add Grids parameter atomics and pattern generator instance.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1: Add Grids include**

Add after the Warps include (~line 19):

```cpp
// Include MI Grids pattern generator
#include "grids/pattern_generator.h"
```

**Step 2: Add Grids state to OrpheusEngine**

Add after the clock section:

```cpp
    // ── Grids Drum Pattern Generator ──────────────────
    // PatternGenerator is a static class — we call its methods directly.
    // State: step position, trigger outputs, pattern map coordinates.
    int grids_step{0};                    // current step (0..31) in the pattern
    int grids_pulse_count{0};             // sub-step counter (0..5 for 24PPQN→4PPQN)
    float grids_trigger_duration{0.001f}; // trigger pulse width in seconds
    int grids_trigger_countdown[3] = {};  // countdown samples for each channel trigger
    std::atomic<float> grids_x{0.5f};           // pattern map X (0..1)
    std::atomic<float> grids_y{0.5f};           // pattern map Y (0..1)
    std::atomic<float> grids_density_kick{0.5f}; // density/threshold (0..1)
    std::atomic<float> grids_density_snare{0.5f};
    std::atomic<float> grids_density_hat{0.5f};
    std::atomic<float> grids_randomness{0.0f};   // 0..1
    std::atomic<int>   grids_bypass{1};           // bypassed by default
```

**Step 3: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h
git commit -m "feat(dsp): Add Grids state to OrpheusEngine (pattern coords, density, triggers)"
```

---

### Task 8: Implement unit_process_grids

Grids reads clock pulses, steps through the pattern ROM, and outputs trigger pulses on 3 channels (kick, snare, hat).

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.h`
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1: Add declaration**

In `orpheus_units.h`, add:

```cpp
void unit_process_grids(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
```

**Step 2: Implement unit_process_grids in orpheus_units.cpp**

Add the Grids include at the top of `orpheus_units.cpp`:

```cpp
#include "grids/pattern_generator.h"
#include "grids/resources.h"
```

Add at end of file:

```cpp
// ── UNIT_GRIDS: Drum pattern generator (MI Grids port) ──────
// IPORT_INPUT_A = 24 PPQN clock pulse (from UNIT_CLOCK)
// IPORT_INPUT_B = beat pulse (for step reset reference)
// OPORT_OUT      = kick triggers
// OPORT_OUT_RIGHT = snare triggers
// OPORT_AUX      = hat triggers
//
// Uses MI Grids' ReadDrumMap() for 5x5 pattern interpolation.
// Converts 24 PPQN input to internal 4 PPQN (every 6th tick = one 16th note).
// 32 steps per pattern = 2 bars of 16th notes at 4/4.
void unit_process_grids(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* in_clock = u->inputs[IPORT_INPUT_A].buffer;
    float* out_kick  = u->output_buffers[OPORT_OUT];
    float* out_snare = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_hat   = u->output_buffers[OPORT_AUX];

    if (engine->grids_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_kick,  0, num_frames * sizeof(float));
        std::memset(out_snare, 0, num_frames * sizeof(float));
        std::memset(out_hat,   0, num_frames * sizeof(float));
        return;
    }

    // Read parameters
    uint8_t x = static_cast<uint8_t>(engine->grids_x.load(std::memory_order_relaxed) * 255.0f);
    uint8_t y = static_cast<uint8_t>(engine->grids_y.load(std::memory_order_relaxed) * 255.0f);
    uint8_t randomness = static_cast<uint8_t>(
        engine->grids_randomness.load(std::memory_order_relaxed) * 255.0f);
    float density[3] = {
        engine->grids_density_kick.load(std::memory_order_relaxed),
        engine->grids_density_snare.load(std::memory_order_relaxed),
        engine->grids_density_hat.load(std::memory_order_relaxed)
    };

    // Trigger pulse width in samples (~1ms)
    int trigger_samples = static_cast<int>(engine->grids_trigger_duration * sample_rate);
    if (trigger_samples < 1) trigger_samples = 1;

    for (int i = 0; i < num_frames; i++) {
        bool clock_tick = in_clock[i] > 0.5f;

        if (clock_tick) {
            engine->grids_pulse_count++;
            // 24 PPQN → 4 PPQN: every 6th tick is a 16th note
            if (engine->grids_pulse_count >= 6) {
                engine->grids_pulse_count = 0;
                int step = engine->grids_step;

                // Read pattern ROM for each instrument via MI Grids' interpolation
                // PatternGenerator::ReadDrumMap reads the 5x5 node grid
                for (int ch = 0; ch < 3; ch++) {
                    uint8_t level = grids::PatternGenerator::ReadDrumMap(step, ch, x, y);

                    // Apply randomness: perturb level
                    if (randomness > 0) {
                        int16_t noise = static_cast<int16_t>((rand() & 0xFF) - 128);
                        int16_t perturbed = static_cast<int16_t>(level) +
                            ((noise * static_cast<int16_t>(randomness)) >> 8);
                        level = static_cast<uint8_t>(
                            std::max(0, std::min(255, static_cast<int>(perturbed))));
                    }

                    // Compare against density threshold (255 = lowest threshold = most triggers)
                    uint8_t threshold = static_cast<uint8_t>((1.0f - density[ch]) * 255.0f);
                    if (level > threshold) {
                        engine->grids_trigger_countdown[ch] = trigger_samples;
                    }
                }

                engine->grids_step = (step + 1) & 31;  // wrap at 32
            }
        }

        // Output trigger pulses (1.0 for trigger_samples, then 0.0)
        out_kick[i]  = engine->grids_trigger_countdown[0] > 0 ? 1.0f : 0.0f;
        out_snare[i] = engine->grids_trigger_countdown[1] > 0 ? 1.0f : 0.0f;
        out_hat[i]   = engine->grids_trigger_countdown[2] > 0 ? 1.0f : 0.0f;

        for (int ch = 0; ch < 3; ch++) {
            if (engine->grids_trigger_countdown[ch] > 0)
                engine->grids_trigger_countdown[ch]--;
        }
    }
}
```

**Step 3: Add dispatch case to orpheus_graph.cpp**

In the switch block, add:

```cpp
            case UNIT_GRIDS:
                unit_process_grids(u, engine, num_frames, sr); break;
```

**Step 4: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`
Expected: Build succeeds

Note: If `PatternGenerator::ReadDrumMap` isn't accessible as a static method, check `grids/pattern_generator.h`. It uses static arrays — you may need to access the node data directly from `grids/resources.h` instead. The pattern ROM nodes are `grids::node_0[]` through `grids::node_24[]`, each 96 bytes (3 instruments × 32 steps). The bilinear interpolation logic is in `PatternGenerator::ReadDrumMap()` — if it's not static, copy the ~20 lines of interpolation code directly into the unit processor.

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_units.cpp liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Implement UNIT_GRIDS — MI Grids drum pattern with 3-channel triggers"
```

---

### Task 9: Add gate input to UNIT_PLAITS for graph-native drum triggering

Currently, Plaits voices get gates via `engine->voice_params[idx].gate` atomics (set from Kotlin). For graph-native drum triggering, Plaits needs to detect gate pulses arriving on a graph input port.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp` (the `unit_process_plaits` function)

**Step 1: Read the existing unit_process_plaits implementation**

Read `orpheus_units.cpp` to find the `unit_process_plaits` function. Look for how it currently reads the gate from `engine->voice_params[idx].gate`.

**Step 2: Add graph gate detection**

After the existing gate read from voice_params, add logic to detect a rising edge on `IPORT_GATE`:

```cpp
    // Graph-native gate input (for Grids → drum voices)
    // If IPORT_GATE has audio connections, use rising edge detection
    GraphPort* gate_port = &u->inputs[IPORT_GATE];
    if (gate_port->num_sources > 0) {
        // Check for rising edge in this block
        for (int i = 0; i < num_frames; i++) {
            float gate_val = gate_port->buffer[i];
            bool gate_on = gate_val > 0.5f;
            bool was_on = engine->voice_params[idx].gate.load(std::memory_order_relaxed) != 0;
            if (gate_on && !was_on) {
                // Rising edge: trigger voice
                engine->voice_params[idx].gate.store(1, std::memory_order_relaxed);
                engine->voice_params[idx].ever_triggered.store(1, std::memory_order_relaxed);
            } else if (!gate_on && was_on) {
                engine->voice_params[idx].gate.store(0, std::memory_order_relaxed);
            }
        }
    }
```

Note: The exact insertion point depends on the current `unit_process_plaits` implementation. Read it first to find the right location — likely near the top where voice parameters are read from atomics, before the Render() call.

For drum voices (index 8-11), the gate should be auto-cleared after rendering (one-shot trigger behavior). Check if the existing code already handles this.

**Step 3: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.cpp
git commit -m "feat(dsp): Add graph gate input to UNIT_PLAITS for native drum triggering"
```

---

### Task 10: Wire Grids into graph and connect to drum voices

Wire UNIT_GRIDS to receive clock and send triggers to drum voice Plaits units.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Step 1: Add Grids unit and wiring to DefaultWiringGraph.kt**

After the clock declaration, add:

```kotlin
    // Grids drum pattern generator
    val grids = grids("grids")
    clock.out to grids.inputA          // 24 PPQN clock
    clock.outRight to grids.inputB     // beat pulse

    // Wire Grids triggers to drum voices (voices 8-11)
    // Kick → voice 8, Snare → voice 9, Hat → voice 10
    // Voice 11 is spare (bass drum alt, could duplicate kick)
    grids.out to plaitsRefs[8].gate        // kick → voice 8
    grids.outRight to plaitsRefs[9].gate   // snare → voice 9
    grids.aux to plaitsRefs[10].gate       // hat → voice 10
```

Note: `plaitsRefs` doesn't exist yet — the Plaits units are created in the loop as `val p = plaits("v${v}_p")`. You'll need to collect them into a list first. Modify the voice creation loop:

```kotlin
    val plaitsUnits = mutableListOf<UnitRef>()
    for (v in 0 until 12) {
        val p = plaits("v${v}_p") { moduleIndex = v.toFloat() }
        plaitsUnits.add(p)
        // ... rest of voice wiring unchanged
    }
```

Then wire grids:

```kotlin
    grids.out to plaitsUnits[8].gate
    grids.outRight to plaitsUnits[9].gate
    grids.aux to plaitsUnits[10].gate
```

**Step 2: Add Grids port map entries**

In the `portMap { }` block:

```kotlin
        // Grids drum patterns
        map("org.balch.orpheus.plugins.drums", "x", "grids", IPORT_INPUT_A)
        map("org.balch.orpheus.plugins.drums", "y", "grids", IPORT_INPUT_B)
```

Wait — `IPORT_INPUT_A` and `IPORT_INPUT_B` are already used for clock connections on Grids. The density/x/y parameters should flow through engine atomics instead (like clouds/rings), not graph ports. The port map entries here are **NOT needed** — Grids parameters are read from `OrpheusEngine` atomics in the processor, not from graph ports.

Remove the Grids port map entries above. Parameters will be set via `orpheus_engine_set_port()` → engine atomics.

**Step 3: Add Grids engine port routing**

In `orpheus_engine.cpp` `orpheus_engine_set_port()`, add:

```cpp
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.drums") == 0) {
        if (std::strcmp(symbol, "x") == 0)
            engine->grids_x.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "y") == 0)
            engine->grids_y.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_kick") == 0)
            engine->grids_density_kick.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_snare") == 0)
            engine->grids_density_snare.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_hat") == 0)
            engine->grids_density_hat.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "randomness") == 0)
            engine->grids_randomness.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->grids_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
```

**Step 4: Verify both compile**

Run:
```bash
./gradlew :core:dsp-engine:compileKotlinJvm
cd liborpheus_dsp && cmake --build build
```
Expected: Both succeed

**Step 5: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Wire UNIT_GRIDS → drum voice gates + engine atomic routing"
```

---

### Task 11: Test Grids trigger output

Write a test that feeds a 120 BPM clock into Grids and verifies triggers are produced.

**Files:**
- Modify: `liborpheus_dsp/test/test_main.cpp`

**Step 1: Add Grids test function**

```cpp
bool test_grids() {
    printf("\n=== Test: Grids drum triggers ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);
    engine->grids_bypass.store(0);
    engine->grids_x.store(0.5f);
    engine->grids_y.store(0.5f);
    engine->grids_density_kick.store(0.8f);   // high density
    engine->grids_density_snare.store(0.8f);
    engine->grids_density_hat.store(0.8f);

    GraphUnit clock_unit = {};
    clock_unit.type = 20; // UNIT_CLOCK
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    GraphUnit grids_unit = {};
    grids_unit.type = 21; // UNIT_GRIDS
    grids_unit.enabled = true;
    unit_init(&grids_unit, 48000.0f);

    // Wire clock output to grids input
    grids_unit.inputs[IPORT_INPUT_A].sources[0] = clock_unit.output_buffers[OPORT_OUT];
    grids_unit.inputs[IPORT_INPUT_A].num_sources = 1;
    grids_unit.inputs[IPORT_INPUT_B].sources[0] = clock_unit.output_buffers[OPORT_OUT_RIGHT];
    grids_unit.inputs[IPORT_INPUT_B].num_sources = 1;

    int kick_triggers = 0, snare_triggers = 0, hat_triggers = 0;
    const int total_frames = 48000 * 2; // 2 seconds

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);

        // Process clock first, then grids (topological order)
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);
        port_prepare(&grids_unit.inputs[IPORT_INPUT_A], chunk, 48000.0f);
        port_prepare(&grids_unit.inputs[IPORT_INPUT_B], chunk, 48000.0f);
        unit_process_grids(&grids_unit, engine, chunk, 48000.0f);

        // Count triggers (rising edges)
        for (int i = 0; i < chunk; i++) {
            if (grids_unit.output_buffers[OPORT_OUT][i] > 0.5f &&
                (i == 0 || grids_unit.output_buffers[OPORT_OUT][i-1] <= 0.5f))
                kick_triggers++;
            if (grids_unit.output_buffers[OPORT_OUT_RIGHT][i] > 0.5f &&
                (i == 0 || grids_unit.output_buffers[OPORT_OUT_RIGHT][i-1] <= 0.5f))
                snare_triggers++;
            if (grids_unit.output_buffers[OPORT_AUX][i] > 0.5f &&
                (i == 0 || grids_unit.output_buffers[OPORT_AUX][i-1] <= 0.5f))
                hat_triggers++;
        }
    }

    // At 120 BPM, 2 bars in 2 seconds, 32 steps total.
    // With density 0.8, expect some subset of 32 triggers per channel.
    printf("Kick triggers:  %d\n", kick_triggers);
    printf("Snare triggers: %d\n", snare_triggers);
    printf("Hat triggers:   %d\n", hat_triggers);

    bool pass = kick_triggers > 0 && snare_triggers > 0 && hat_triggers > 0;
    printf("Grids test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}
```

**Step 2: Call from main()**

Add after the clock test call:

```cpp
    if (!test_grids()) return 1;
```

**Step 3: Build and run**

Run:
```bash
cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test
```
Expected: "Grids test: PASS" with non-zero trigger counts for all 3 channels.

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/test_main.cpp
git commit -m "test(dsp): Add Grids drum trigger test (clock → pattern ROM → triggers)"
```

---

## Phase C: Marbles (Random Sequencer)

### Task 12: Verify Marbles compiles and understand the API

Marbles sources are already in `CMakeLists.txt`. This task verifies compilation and reads the key headers to understand the integration API.

**Files:**
- Read only: `${EURORACK_DIR}/marbles/random/t_generator.h`
- Read only: `${EURORACK_DIR}/marbles/random/x_y_generator.h`
- Read only: `${EURORACK_DIR}/marbles/ramp/ramp_extractor.h`
- Read only: `${EURORACK_DIR}/marbles/random/random_stream.h`

**Step 1: Verify Marbles already compiles**

Run: `cd liborpheus_dsp && cmake --build build 2>&1 | grep -i error`
Expected: No errors related to marbles

**Step 2: Read the key Marbles headers**

Read these files to understand the exact Init() and Process() signatures and what stmlib types are used:
- `t_generator.h` — `TGenerator::Init(RandomStream*, float sr)` and `TGenerator::Process()`
- `x_y_generator.h` — `XYGenerator::Init()` and `XYGenerator::Process()`
- `ramp_extractor.h` — `RampExtractor::Init(float max_freq)` and `RampExtractor::Process()`
- `random_stream.h` — `RandomStream` class (provides hardware RNG abstraction)

Note the key types:
- `stmlib::GateFlags` — enum for edge detection
- `Ramps` struct — holds ramp buffer pointers
- `GroupSettings` struct — X/Y generator settings
- `Ratio` struct — clock division ratio

No code changes, just verification and reading.

**Step 3: Commit (no changes expected — skip if nothing changed)**

---

### Task 13: Add Marbles wrapper state to OrpheusEngine

Allocate the MI Marbles objects (TGenerator, XYGenerator, RampExtractor, RandomStream) and working buffers.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Step 1: Add Marbles includes to orpheus_engine.h**

Add after the Warps include:

```cpp
// Include MI Marbles random sequencer
#include "marbles/random/t_generator.h"
#include "marbles/random/x_y_generator.h"
#include "marbles/ramp/ramp_extractor.h"
#include "marbles/random/random_stream.h"
```

**Step 2: Add Marbles state to OrpheusEngine struct**

Add after the Grids section:

```cpp
    // ── Marbles Random Sequencer ──────────────────────
    marbles::TGenerator marbles_t_gen;
    marbles::XYGenerator marbles_xy_gen;
    marbles::RampExtractor marbles_ramp_extractor;
    marbles::RandomStream marbles_random_stream;

    // Working buffers for Marbles processing (block-based, max 512 frames)
    float marbles_ramp_master[kMaxFrames] = {};
    float marbles_ramp_external[kMaxFrames] = {};
    float marbles_ramp_slave[2][kMaxFrames] = {};
    bool  marbles_gate_out[kMaxFrames] = {};
    float marbles_cv_out[kMaxFrames * 4] = {};  // interleaved X1,X2,X3,Y
    stmlib::GateFlags marbles_gate_flags[kMaxFrames] = {};

    // Additional Marbles parameters (beyond the existing stubs)
    std::atomic<float> marbles_t_rate{0.5f};
    std::atomic<float> marbles_t_bias{0.5f};
    std::atomic<float> marbles_t_jitter{0.0f};
    std::atomic<float> marbles_x_spread{0.5f};
    std::atomic<float> marbles_x_bias{0.5f};
    std::atomic<float> marbles_x_steps{0.5f};
    std::atomic<int>   marbles_deja_vu_length{8};
    std::atomic<int>   marbles_t_model{0};     // TGeneratorModel
    std::atomic<int>   marbles_x_control_mode{0}; // ControlMode
    std::atomic<int>   marbles_x_register_mode{0};
    std::atomic<int>   marbles_x_range{2};      // VoltageRange (0=narrow, 1=medium, 2=wide)
```

Note: Some of these duplicate the existing stub atomics (marbles_rate, marbles_spread, etc.). Rename or remove the old stubs to avoid confusion. Replace the existing stubs (lines ~130-138 of orpheus_engine.h) with the new fields above.

**Step 3: Initialize Marbles in orpheus_engine_create()**

In `orpheus_engine.cpp`, after the Warps init, add:

```cpp
    // Initialize Marbles random sequencer
    engine->marbles_random_stream.Init(42);  // seed
    engine->marbles_t_gen.Init(&engine->marbles_random_stream, sample_rate);
    engine->marbles_xy_gen.Init(&engine->marbles_random_stream, sample_rate);
    engine->marbles_ramp_extractor.Init(sample_rate / 4.0f);  // max freq
```

Note: Check the exact `Init()` signatures by reading the headers. `RandomStream::Init(uint32_t seed)` may need a different signature — `RandomStream` might wrap hardware RNG. If it needs a `RandomGenerator*`, create a local `RandomGenerator` and pass that. Adjust based on what you find in the header.

**Step 4: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`

Note: You may encounter compilation issues if `RandomStream` expects hardware RNG or if `stmlib::GateFlags` is defined differently under `-DTEST`. Debug any issues by:
1. Reading the relevant stmlib headers for the `-DTEST` code path
2. Adding stubs in `orpheus_compat.h` if needed

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Add Marbles TGenerator + XYGenerator state to OrpheusEngine"
```

---

### Task 14: Implement unit_process_marbles

Wraps MI Marbles TGenerator and XYGenerator as a graph unit.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.h`
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1: Add declaration**

In `orpheus_units.h`:

```cpp
void unit_process_marbles(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
```

**Step 2: Implement unit_process_marbles**

This is the most complex unit. The adapter needs to:
1. Convert graph clock pulses to `stmlib::GateFlags`
2. Set TGenerator parameters from engine atomics
3. Call `TGenerator::Process()` to get ramps and gates
4. Set XYGenerator parameters
5. Call `XYGenerator::Process()` to get CV outputs
6. Write to output buffers

```cpp
// ── UNIT_MARBLES: MI Marbles random sequence generator ──────
// IPORT_INPUT_A = 24 PPQN clock pulse (from UNIT_CLOCK)
// OPORT_OUT       = t1 gate output (rhythmic triggers)
// OPORT_OUT_RIGHT = x1 CV output (random pitch voltage)
// OPORT_AUX       = x2 CV output (second random channel)
void unit_process_marbles(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* in_clock = u->inputs[IPORT_INPUT_A].buffer;
    float* out_gate = u->output_buffers[OPORT_OUT];
    float* out_x1   = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_x2   = u->output_buffers[OPORT_AUX];

    if (engine->marbles_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_gate, 0, num_frames * sizeof(float));
        std::memset(out_x1,   0, num_frames * sizeof(float));
        std::memset(out_x2,   0, num_frames * sizeof(float));
        return;
    }

    // Convert float clock pulses to stmlib::GateFlags
    bool prev_clock = false;
    for (int i = 0; i < num_frames; i++) {
        bool current = in_clock[i] > 0.5f;
        stmlib::GateFlags flags = stmlib::GATE_FLAG_LOW;
        if (current && !prev_clock) flags = stmlib::GATE_FLAG_RISING;
        else if (current && prev_clock) flags = stmlib::GATE_FLAG_HIGH;
        else if (!current && prev_clock) flags = stmlib::GATE_FLAG_FALLING;
        engine->marbles_gate_flags[i] = flags;
        prev_clock = current;
    }

    // Configure TGenerator
    engine->marbles_t_gen.set_model(
        static_cast<marbles::TGeneratorModel>(
            engine->marbles_t_model.load(std::memory_order_relaxed)));
    engine->marbles_t_gen.set_rate(
        engine->marbles_t_rate.load(std::memory_order_relaxed));
    engine->marbles_t_gen.set_bias(
        engine->marbles_t_bias.load(std::memory_order_relaxed));
    engine->marbles_t_gen.set_jitter(
        engine->marbles_t_jitter.load(std::memory_order_relaxed));
    engine->marbles_t_gen.set_deja_vu(
        engine->marbles_deja_vu.load(std::memory_order_relaxed));
    engine->marbles_t_gen.set_length(
        engine->marbles_deja_vu_length.load(std::memory_order_relaxed));

    // Build ramps struct
    marbles::Ramps ramps;
    ramps.external = engine->marbles_ramp_external;
    ramps.master = engine->marbles_ramp_master;
    ramps.slave[0] = engine->marbles_ramp_slave[0];
    ramps.slave[1] = engine->marbles_ramp_slave[1];

    // Process TGenerator (rhythmic gates + ramps)
    bool reset = false;
    engine->marbles_t_gen.Process(
        true,  // use_external_clock (from graph clock)
        &reset,
        engine->marbles_gate_flags,
        ramps,
        engine->marbles_gate_out,
        static_cast<size_t>(num_frames));

    // Configure XYGenerator
    marbles::GroupSettings x_settings;
    x_settings.control_mode = static_cast<marbles::ControlMode>(
        engine->marbles_x_control_mode.load(std::memory_order_relaxed));
    x_settings.voltage_range = static_cast<marbles::VoltageRange>(
        engine->marbles_x_range.load(std::memory_order_relaxed));
    x_settings.register_mode = engine->marbles_x_register_mode.load(std::memory_order_relaxed) != 0;
    x_settings.register_value = engine->marbles_deja_vu.load(std::memory_order_relaxed);
    x_settings.spread = engine->marbles_x_spread.load(std::memory_order_relaxed);
    x_settings.bias = engine->marbles_x_bias.load(std::memory_order_relaxed);
    x_settings.steps = engine->marbles_x_steps.load(std::memory_order_relaxed);
    x_settings.deja_vu = engine->marbles_deja_vu.load(std::memory_order_relaxed);
    x_settings.length = engine->marbles_deja_vu_length.load(std::memory_order_relaxed);
    x_settings.ratio.p = 1;
    x_settings.ratio.q = 1;

    // Y settings = same as X for now
    marbles::GroupSettings y_settings = x_settings;

    // Process XYGenerator (random CV outputs)
    engine->marbles_xy_gen.Process(
        marbles::CLOCK_SOURCE_EXTERNAL,
        x_settings,
        y_settings,
        &reset,
        engine->marbles_gate_flags,
        ramps,
        engine->marbles_cv_out,
        static_cast<size_t>(num_frames));

    // Write outputs
    for (int i = 0; i < num_frames; i++) {
        out_gate[i] = engine->marbles_gate_out[i] ? 1.0f : 0.0f;
        out_x1[i] = engine->marbles_cv_out[i * 4];      // X1
        out_x2[i] = engine->marbles_cv_out[i * 4 + 1];  // X2
    }
}
```

Note: The exact struct field names and enum values MUST be verified by reading the MI headers. The code above uses the expected API based on the agent research, but `GroupSettings`, `ClockSource`, `Ramps`, etc. may have slightly different field names. Read the headers carefully during implementation.

**Step 3: Add dispatch case to orpheus_graph.cpp**

```cpp
            case UNIT_MARBLES:
                unit_process_marbles(u, engine, num_frames, sr); break;
```

**Step 4: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_units.cpp liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Implement UNIT_MARBLES — MI Marbles TGenerator + XYGenerator wrapper"
```

---

### Task 15: Wire Marbles into graph and add parameter routing

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Step 1: Add Marbles unit and wiring to DefaultWiringGraph.kt**

After the Grids wiring:

```kotlin
    // Marbles random sequencer (Flux)
    val marbles = marbles("marbles")
    clock.out to marbles.inputA    // 24 PPQN clock input
```

Note: Marbles CV outputs are used for voice pitch modulation. For now, just add the unit to the graph. Pitch modulation routing (Marbles CV → voice tune) is a separate integration concern handled by DspSynthEngine reading the engine's marbles CV output values.

**Step 2: Update engine port routing for Marbles**

In `orpheus_engine.cpp`, update the existing `"marbles"` strcmp block (~line 655) to use the proper plugin URI and new field names:

```cpp
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.flux") == 0) {
        if (std::strcmp(symbol, "rate") == 0)
            engine->marbles_t_rate.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "spread") == 0)
            engine->marbles_x_spread.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bias") == 0)
            engine->marbles_t_bias.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "steps") == 0)
            engine->marbles_x_steps.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "jitter") == 0)
            engine->marbles_t_jitter.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu") == 0)
            engine->marbles_deja_vu.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu_length") == 0)
            engine->marbles_deja_vu_length.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "t_model") == 0)
            engine->marbles_t_model.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_range") == 0)
            engine->marbles_x_range.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->marbles_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
```

Also remove (or keep as alias) the old `"marbles"` URI block to avoid dead code.

**Step 3: Verify both compile**

Run:
```bash
./gradlew :core:dsp-engine:compileKotlinJvm
cd liborpheus_dsp && cmake --build build
```

**Step 4: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Wire UNIT_MARBLES into graph with Flux plugin parameter routing"
```

---

### Task 16: Test Marbles output

**Files:**
- Modify: `liborpheus_dsp/test/test_main.cpp`

**Step 1: Add Marbles test function**

```cpp
bool test_marbles() {
    printf("\n=== Test: Marbles random sequencer ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);
    engine->marbles_bypass.store(0);
    engine->marbles_t_bias.store(0.5f);
    engine->marbles_x_spread.store(0.5f);

    GraphUnit clock_unit = {};
    clock_unit.type = 20;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    GraphUnit marbles_unit = {};
    marbles_unit.type = 22;
    marbles_unit.enabled = true;
    unit_init(&marbles_unit, 48000.0f);

    // Wire clock → marbles
    marbles_unit.inputs[IPORT_INPUT_A].sources[0] = clock_unit.output_buffers[OPORT_OUT];
    marbles_unit.inputs[IPORT_INPUT_A].num_sources = 1;

    int gate_transitions = 0;
    float cv_min = 1e9f, cv_max = -1e9f;
    bool prev_gate = false;
    const int total_frames = 48000 * 2;

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);

        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);
        port_prepare(&marbles_unit.inputs[IPORT_INPUT_A], chunk, 48000.0f);
        unit_process_marbles(&marbles_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            bool gate = marbles_unit.output_buffers[OPORT_OUT][i] > 0.5f;
            if (gate && !prev_gate) gate_transitions++;
            prev_gate = gate;

            float cv = marbles_unit.output_buffers[OPORT_OUT_RIGHT][i];
            if (cv < cv_min) cv_min = cv;
            if (cv > cv_max) cv_max = cv;
        }
    }

    printf("Gate transitions: %d\n", gate_transitions);
    printf("CV range: [%.3f, %.3f]\n", cv_min, cv_max);

    bool pass = gate_transitions > 0;
    printf("Marbles test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}
```

**Step 2: Call from main()**

```cpp
    if (!test_marbles()) return 1;
```

**Step 3: Build and run**

Run: `cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test`
Expected: "Marbles test: PASS" with non-zero gate transitions.

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/test_main.cpp
git commit -m "test(dsp): Add Marbles random sequencer test (clock → gates + CV)"
```

---

## Phase D: Looper

### Task 17: Add looper state to OrpheusEngine

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1: Add looper state**

After the Marbles section:

```cpp
    // ── Beat-Quantized Looper ─────────────────────────
    static constexpr int kMaxLoopSamples = 48000 * 30;  // 30 seconds at 48kHz
    float* looper_buffer_l{nullptr};  // heap allocated in create()
    float* looper_buffer_r{nullptr};
    int    looper_length{0};           // recorded loop length in samples
    int    looper_position{0};         // current read/write position
    int    looper_current_state{0};    // actual state: 0=stop, 1=record, 2=play, 3=overdub
    bool   looper_pending_transition{false};
    int    looper_pending_state{0};    // requested state (waits for beat boundary)
    std::atomic<int>   looper_requested_state{0}; // from UI: 0=stop, 1=record, 2=play, 3=overdub
    std::atomic<float> looper_level{1.0f};        // playback level
    std::atomic<float> looper_feedback{0.8f};     // overdub feedback
    std::atomic<int>   looper_quantize{1};        // 1 = quantize to beat, 0 = immediate
```

**Step 2: Allocate/free looper buffers in create/destroy**

In `orpheus_engine_create()`:

```cpp
    // Allocate looper buffers
    engine->looper_buffer_l = new float[OrpheusEngine::kMaxLoopSamples]();
    engine->looper_buffer_r = new float[OrpheusEngine::kMaxLoopSamples]();
```

In `orpheus_engine_destroy()`, before `delete engine`:

```cpp
    delete[] engine->looper_buffer_l;
    delete[] engine->looper_buffer_r;
```

**Step 3: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Add looper state to OrpheusEngine (30s stereo buffer, quantize support)"
```

---

### Task 18: Implement unit_process_looper

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.h`
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1: Add declaration**

```cpp
void unit_process_looper(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
```

**Step 2: Implement unit_process_looper**

```cpp
// ── UNIT_LOOPER: Beat-quantized audio looper ────────
// IPORT_INPUT_A = audio in L (from effect chain)
// IPORT_INPUT_B = audio in R
// IPORT_INPUT_C = beat pulse (from UNIT_CLOCK, for quantization)
// OPORT_OUT      = audio out L (loop playback + passthrough)
// OPORT_OUT_RIGHT = audio out R
void unit_process_looper(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* in_beat = u->inputs[IPORT_INPUT_C].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    float level = engine->looper_level.load(std::memory_order_relaxed);
    float feedback = engine->looper_feedback.load(std::memory_order_relaxed);
    bool quantize = engine->looper_quantize.load(std::memory_order_relaxed) != 0;
    int requested = engine->looper_requested_state.load(std::memory_order_relaxed);

    // Check for state change request
    if (requested != engine->looper_current_state) {
        if (quantize) {
            engine->looper_pending_transition = true;
            engine->looper_pending_state = requested;
        } else {
            engine->looper_current_state = requested;
            if (requested == 1) { // start recording
                engine->looper_position = 0;
                engine->looper_length = 0;
            }
        }
    }

    int state = engine->looper_current_state;
    int max_samples = OrpheusEngine::kMaxLoopSamples;

    for (int i = 0; i < num_frames; i++) {
        // Check for beat boundary (quantized state transitions)
        if (engine->looper_pending_transition && in_beat[i] > 0.5f) {
            engine->looper_pending_transition = false;
            state = engine->looper_pending_state;
            engine->looper_current_state = state;
            if (state == 1) { // start recording
                engine->looper_position = 0;
                engine->looper_length = 0;
            }
        }

        float loop_l = 0.0f, loop_r = 0.0f;
        int pos = engine->looper_position;

        switch (state) {
            case 0: // Stop — passthrough only
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
                break;

            case 1: // Record
                if (pos < max_samples) {
                    engine->looper_buffer_l[pos] = in_l[i];
                    engine->looper_buffer_r[pos] = in_r[i];
                    engine->looper_length = pos + 1;
                    engine->looper_position = pos + 1;
                }
                out_l[i] = in_l[i]; // monitor input while recording
                out_r[i] = in_r[i];
                break;

            case 2: // Play
                if (engine->looper_length > 0) {
                    loop_l = engine->looper_buffer_l[pos] * level;
                    loop_r = engine->looper_buffer_r[pos] * level;
                    engine->looper_position = (pos + 1) % engine->looper_length;
                }
                out_l[i] = in_l[i] + loop_l;
                out_r[i] = in_r[i] + loop_r;
                break;

            case 3: // Overdub
                if (engine->looper_length > 0) {
                    loop_l = engine->looper_buffer_l[pos];
                    loop_r = engine->looper_buffer_r[pos];
                    engine->looper_buffer_l[pos] = in_l[i] + loop_l * feedback;
                    engine->looper_buffer_r[pos] = in_r[i] + loop_r * feedback;
                    engine->looper_position = (pos + 1) % engine->looper_length;
                }
                out_l[i] = in_l[i] + loop_l * level;
                out_r[i] = in_r[i] + loop_r * level;
                break;
        }
    }
}
```

**Step 3: Add dispatch case**

```cpp
            case UNIT_LOOPER:
                unit_process_looper(u, engine, num_frames, sr); break;
```

**Step 4: Verify C++ compiles**

Run: `cd liborpheus_dsp && cmake --build build`

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_units.cpp liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Implement UNIT_LOOPER — beat-quantized record/play/overdub"
```

---

### Task 19: Wire looper into graph and add parameter routing

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Step 1: Add looper unit and wiring to DefaultWiringGraph.kt**

After the Marbles wiring:

```kotlin
    // Beat-quantized looper
    val looper = looper("looper")
    driveL.out to looper.inputA         // audio in L (post-drive)
    driveR.out to looper.inputB         // audio in R
    clock.outRight to looper.inputC     // beat pulse for quantization

    // Looper output feeds into delay alongside other sources
    looper.out to delay.inputA
    looper.outRight to delay.inputB
```

**Step 2: Add looper port routing to orpheus_engine.cpp**

```cpp
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.looper") == 0) {
        if (std::strcmp(symbol, "state") == 0)
            engine->looper_requested_state.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "level") == 0)
            engine->looper_level.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->looper_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quantize") == 0)
            engine->looper_quantize.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
```

**Step 3: Verify both compile**

Run:
```bash
./gradlew :core:dsp-engine:compileKotlinJvm
cd liborpheus_dsp && cmake --build build
```

**Step 4: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Wire UNIT_LOOPER into graph with looper plugin parameter routing"
```

---

### Task 20: Test looper

**Files:**
- Modify: `liborpheus_dsp/test/test_main.cpp`

**Step 1: Add looper test function**

```cpp
bool test_looper() {
    printf("\n=== Test: Looper record/play ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);

    GraphUnit looper_unit = {};
    looper_unit.type = 23; // UNIT_LOOPER
    looper_unit.enabled = true;
    unit_init(&looper_unit, 48000.0f);

    // Generate a test tone into the looper input
    const int record_frames = 12000; // 0.25 seconds
    float test_input_l[128], test_input_r[128];

    // Phase 1: Record 0.25 seconds of sine tone (no quantize for simplicity)
    engine->looper_quantize.store(0);
    engine->looper_requested_state.store(1); // record

    for (int offset = 0; offset < record_frames; offset += 128) {
        int chunk = std::min(128, record_frames - offset);
        for (int i = 0; i < chunk; i++) {
            float phase = static_cast<float>(offset + i) / 48000.0f;
            test_input_l[i] = std::sin(phase * 440.0f * 2.0f * 3.14159f) * 0.5f;
            test_input_r[i] = test_input_l[i];
        }

        // Set input buffers
        std::memcpy(looper_unit.inputs[IPORT_INPUT_A].buffer, test_input_l, chunk * sizeof(float));
        looper_unit.inputs[IPORT_INPUT_A].num_sources = 0;
        looper_unit.inputs[IPORT_INPUT_A].constant = 0;
        std::memcpy(looper_unit.inputs[IPORT_INPUT_B].buffer, test_input_r, chunk * sizeof(float));
        std::memset(looper_unit.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));

        unit_process_looper(&looper_unit, engine, chunk, 48000.0f);
    }

    printf("Recorded %d samples (loop length: %d)\n", record_frames, engine->looper_length);

    // Phase 2: Switch to play, feed silence, verify loop plays back
    engine->looper_requested_state.store(2); // play
    float max_playback = 0.0f;

    for (int offset = 0; offset < record_frames; offset += 128) {
        int chunk = std::min(128, record_frames - offset);
        std::memset(looper_unit.inputs[IPORT_INPUT_A].buffer, 0, chunk * sizeof(float));
        std::memset(looper_unit.inputs[IPORT_INPUT_B].buffer, 0, chunk * sizeof(float));
        std::memset(looper_unit.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));

        unit_process_looper(&looper_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            float v = std::fabs(looper_unit.output_buffers[OPORT_OUT][i]);
            if (v > max_playback) max_playback = v;
        }
    }

    printf("Max playback amplitude: %.4f\n", max_playback);

    bool pass = engine->looper_length == record_frames && max_playback > 0.1f;
    printf("Looper test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}
```

**Step 2: Call from main()**

```cpp
    if (!test_looper()) return 1;
```

**Step 3: Build and run**

Run: `cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test`
Expected: "Looper test: PASS"

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/test_main.cpp
git commit -m "test(dsp): Add looper record/play test (record sine, verify playback)"
```

---

### Task 21: Update gap analysis

**Files:**
- Modify: `docs/plans/2026-03-07-cpp-dsp-parity-gap-analysis.md`

**Step 1: Update gap statuses**

- Gap #9 (Looper): **FIXED** — UNIT_LOOPER beat-quantized record/play/overdub
- Gap #14 (Flux CV): **FIXED** — UNIT_MARBLES full MI Marbles port
- Gap #16 (Drum routing): **FIXED** — UNIT_GRIDS → UNIT_PLAITS graph-native triggers

Update the summary table accordingly. Parity score moves from 12/20 to 15/20.

**Step 2: Commit**

```bash
git add docs/plans/2026-03-07-cpp-dsp-parity-gap-analysis.md
git commit -m "docs: Update gap analysis — Clock/Grids/Marbles/Looper implemented (15/20)"
```

---

## Dependency Graph

```
Task 1 (constants) → Task 2 (clock state) → Task 3 (clock processor) → Task 4 (clock wiring) → Task 5 (clock test)
                                                      ↓
Task 6 (grids cmake) → Task 7 (grids state) → Task 8 (grids processor) → Task 9 (plaits gate) → Task 10 (grids wiring) → Task 11 (grids test)
                                                      ↓
Task 12 (marbles verify) → Task 13 (marbles state) → Task 14 (marbles processor) → Task 15 (marbles wiring) → Task 16 (marbles test)
                                                      ↓
Task 17 (looper state) → Task 18 (looper processor) → Task 19 (looper wiring) → Task 20 (looper test)
                                                      ↓
                                                Task 21 (gap analysis update)
```

Phases B, C, and D can run in parallel after Phase A is complete (Tasks 1-5). Within each phase, tasks are sequential.

## Build & Test Commands

```bash
# C++ build
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp
cmake -B build -DBUILD_TESTS=ON && cmake --build build

# C++ test
./build/orpheus_dsp_test

# Kotlin compile check
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp
./gradlew :core:foundation:compileKotlinJvm
./gradlew :core:dsp-engine:compileKotlinJvm

# Full app build (includes native lib rebuild)
./gradlew :apps:composeApp:build

# Run desktop app for A/B testing
./gradlew :apps:composeApp:run
```
