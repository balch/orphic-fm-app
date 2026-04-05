# PocketDJ Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a standalone PocketDJ app with Pulsar beat machine + DJ turntable + timer, powered by a minimal DSP graph where Pulsar tracks auto-route to Keys/Drums/Bass buses for DJ capture.

**Architecture:** Extract a reusable Pulsar subgraph from the monolithic DefaultWiringGraph. Add C++ engine-type bus classification so per-track output accumulates into Keys/Drums/Bass source buffers. Create a new `apps/pocketdj` module with its own DI graph, wiring graph, and portrait/landscape UI. First step: rename `apps/composeApp` → `apps/orpheus`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Metro DI, C++ DSP (liborpheus_dsp), Plaits engines

**Spec:** `docs/superpowers/specs/2026-04-04-pocketdj-design.md`

---

## File Map

### Renamed (Task 1)
- `apps/composeApp/` → `apps/orpheus/` (entire directory)
- Update references in: `settings.gradle.kts`, `apps/androidApp/build.gradle.kts`, `.github/workflows/deploy-wasm.yml`, `scripts/deploy-gh-pages.sh`, `scripts/dev-site.sh`, `build-scripts/generate_icons.sh`, `.gitignore`, `apps/iosApp/project.yml`, `CLAUDE.md`, `README.md`, `docs/BUILD.md`, `docs/TESTS.md`, `.claude/skills/wasm-dev/SKILL.md`, `.claude/skills/panel-viewmodel-feature/SKILL.md`

### C++ Changes (Tasks 2-3)
- Modify: `liborpheus_dsp/src/orpheus_unit_pulsar.cpp` — add bus classification + per-track bus accumulation
- Modify: `liborpheus_dsp/src/orpheus_unit_pulsar.h` — add bus enum, bus buffer fields to PulsarState
- Modify: `liborpheus_dsp/src/orpheus_engine.h` — add pulsar bus output buffers
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp` — add pulsar bus buffer double-buffering
- Create: `liborpheus_dsp/test/test_pulsar_bus.cpp` — bus classification tests

### Kotlin Graph Changes (Task 4)
- Create: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/PocketDjWiringGraph.kt`
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt` — extract shared Pulsar+DJ wiring

### PocketDJ App Module (Tasks 5-7)
- Create: `apps/pocketdj/build.gradle.kts`
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/PocketDjApp.kt`
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/PocketDjScreen.kt`
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjGraph.kt`
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjModule.kt`
- Create: `apps/pocketdj/src/jvmMain/kotlin/org/balch/orpheus/pocketdj/main.kt`
- Create: `apps/pocketdj/src/jvmMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjGraph.jvm.kt`
- Create: `apps/pocketdj/src/androidMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjGraph.android.kt`
- Modify: `settings.gradle.kts` — add `:apps:pocketdj`

---

## Task 1: Rename `apps/composeApp` → `apps/orpheus`

**Files:**
- Rename: `apps/composeApp/` → `apps/orpheus/`
- Modify: `settings.gradle.kts:38`
- Modify: `apps/androidApp/build.gradle.kts:83`
- Modify: `.github/workflows/deploy-wasm.yml:9,43,50`
- Modify: `scripts/deploy-gh-pages.sh:24,70`
- Modify: `scripts/dev-site.sh:19,64`
- Modify: `build-scripts/generate_icons.sh:19,43,50`
- Modify: `.gitignore:35-37`
- Modify: `apps/iosApp/project.yml:45,48`
- Modify: `CLAUDE.md:25,29,30`
- Modify: `README.md:66,116,122,127,136`
- Modify: `docs/BUILD.md` (multiple lines)
- Modify: `docs/TESTS.md` (multiple lines)
- Modify: `.claude/skills/wasm-dev/SKILL.md`
- Modify: `.claude/skills/panel-viewmodel-feature/SKILL.md`

- [ ] **Step 1: Move the directory**

```bash
git mv apps/composeApp apps/orpheus
```

- [ ] **Step 2: Update `settings.gradle.kts`**

Change line 38:
```kotlin
// OLD:
include(":apps:composeApp")
// NEW:
include(":apps:orpheus")
```

- [ ] **Step 3: Update `apps/androidApp/build.gradle.kts`**

Change line 83:
```kotlin
// OLD:
implementation(projects.apps.composeApp)
// NEW:
implementation(projects.apps.orpheus)
```

- [ ] **Step 4: Update CI workflow `.github/workflows/deploy-wasm.yml`**

Replace all `composeApp` with `orpheus` in paths and gradle tasks:
```yaml
# Line 9: path trigger
- 'apps/orpheus/**'
# Line 43: gradle task
run: ./gradlew :apps:orpheus:wasmJsBrowserDistribution
# Line 50: dist dir
DIST_DIR="apps/orpheus/build/dist/wasmJs/productionExecutable"
```

- [ ] **Step 5: Update deploy and dev scripts**

`scripts/deploy-gh-pages.sh` — replace `composeApp` with `orpheus` on lines 24 and 70.
`scripts/dev-site.sh` — replace `composeApp` with `orpheus` on lines 19 and 64.
`build-scripts/generate_icons.sh` — replace `composeApp` with `orpheus` on lines 15, 19, 43, 50.

- [ ] **Step 6: Update `.gitignore`**

Lines 35-37:
```gitignore
apps/orpheus/src/jvmMain/resources/native/*/liborpheus_desktop.*
apps/orpheus/src/wasmJsMain/resources/orpheus_dsp.js
apps/orpheus/src/wasmJsMain/resources/orpheus_dsp.wasm
```

- [ ] **Step 7: Update `apps/iosApp/project.yml`**

Lines 45 and 48:
```yaml
- "$(SRCROOT)/../orpheus/build/bin/iosArm64/debugFramework"
- "$(SRCROOT)/../orpheus/build/bin/iosSimulatorArm64/debugFramework"
```

- [ ] **Step 8: Update documentation**

`CLAUDE.md` — replace all `:apps:composeApp:` with `:apps:orpheus:` (3 occurrences on lines 25, 29, 30).

`README.md` — replace all `composeApp` references (lines 66, 116, 122, 127, 136).

`docs/BUILD.md` — replace all `composeApp` references.

`docs/TESTS.md` — replace all `composeApp` references.

- [ ] **Step 9: Update Claude skills**

`.claude/skills/wasm-dev/SKILL.md` — replace `composeApp` with `orpheus`.
`.claude/skills/panel-viewmodel-feature/SKILL.md` — replace `composeApp` with `orpheus`.

- [ ] **Step 10: Verify build compiles**

```bash
./gradlew :apps:orpheus:compileKotlinJvm
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Commit and squash-merge to main**

```bash
git add -A
git commit -m "Rename apps/composeApp to apps/orpheus

Prepares for multi-app architecture (PocketDJ standalone app).
All Gradle task paths, CI workflows, scripts, docs, and .gitignore
updated to reference apps/orpheus."
```

Then squash-merge the pocketdj branch onto main:
```bash
git checkout main
git merge --squash pocketdj
git commit -m "Rename apps/composeApp to apps/orpheus for multi-app support"
git checkout pocketdj
git rebase main
```

---

## Task 2: C++ Engine-Type Bus Classification

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_unit_pulsar.h`
- Modify: `liborpheus_dsp/src/orpheus_engine.h`
- Create: `liborpheus_dsp/test/test_pulsar_bus.cpp`

- [ ] **Step 1: Add bus enum and classification table to `orpheus_unit_pulsar.h`**

After the `PulsarEnvelopeProfile` enum (line 39), add:

```cpp
// Engine-type bus classification for DJ turntable source routing.
// Maps each Plaits engine ID to a mix bus so the turntable can capture
// Keys (melodic), Drums (percussive), or Bass separately.
enum PulsarBusType : uint8_t {
    PULSAR_BUS_KEYS  = 0,  // melodic engines → warps_source_buffers[0] (SYNTH slot)
    PULSAR_BUS_DRUMS = 1,  // percussive engines → warps_source_buffers[1] (DRUMS slot)
    PULSAR_BUS_BASS  = 2,  // bass engines → warps_source_buffers[9] (BASS slot)
};

// Classification table: Plaits engine ID → bus type.
// 24 engines total (see kOrpheusOutGain in orpheus_voice.h for IDs).
static constexpr PulsarBusType kEngineBusType[24] = {
    PULSAR_BUS_BASS,   //  0: VirtualAnalogVCF — bass-oriented filter sweep
    PULSAR_BUS_KEYS,   //  1: PhaseDistortion — melodic
    PULSAR_BUS_KEYS,   //  2: SixOp FM1 — melodic
    PULSAR_BUS_KEYS,   //  3: SixOp FM2 — melodic
    PULSAR_BUS_KEYS,   //  4: SixOp FM3 — melodic
    PULSAR_BUS_KEYS,   //  5: WaveTerrain — melodic
    PULSAR_BUS_KEYS,   //  6: StringMachine — melodic
    PULSAR_BUS_KEYS,   //  7: Chiptune — melodic
    PULSAR_BUS_KEYS,   //  8: VirtualAnalog — melodic
    PULSAR_BUS_KEYS,   //  9: Waveshaping — melodic
    PULSAR_BUS_KEYS,   // 10: FM — melodic
    PULSAR_BUS_KEYS,   // 11: Grain — melodic/textural
    PULSAR_BUS_KEYS,   // 12: Additive — melodic
    PULSAR_BUS_KEYS,   // 13: Wavetable — melodic
    PULSAR_BUS_KEYS,   // 14: Chord — melodic
    PULSAR_BUS_KEYS,   // 15: Speech — melodic/vocal
    PULSAR_BUS_KEYS,   // 16: Swarm — melodic/textural
    PULSAR_BUS_DRUMS,  // 17: Noise — percussive
    PULSAR_BUS_DRUMS,  // 18: Particle — percussive
    PULSAR_BUS_KEYS,   // 19: String — melodic
    PULSAR_BUS_DRUMS,  // 20: Modal — percussive (tuned percussion)
    PULSAR_BUS_DRUMS,  // 21: BassDrum — percussive
    PULSAR_BUS_DRUMS,  // 22: SnareDrum — percussive
    PULSAR_BUS_DRUMS,  // 23: HiHat — percussive
};
```

- [ ] **Step 2: Add bus output buffers to `orpheus_engine.h`**

After `pulsar_out_r` (line 689), add:

```cpp
// Per-bus output buffers for Pulsar engine-type classification.
// Accumulated per-track before soft_limit, then copied to warps_source_buffers
// so the turntable can capture Keys/Drums/Bass independently.
float pulsar_bus_keys_l[kMaxFrames] = {};
float pulsar_bus_keys_r[kMaxFrames] = {};
float pulsar_bus_drums_l[kMaxFrames] = {};
float pulsar_bus_drums_r[kMaxFrames] = {};
float pulsar_bus_bass_l[kMaxFrames] = {};
float pulsar_bus_bass_r[kMaxFrames] = {};
```

- [ ] **Step 3: Write failing test for bus classification**

Create `liborpheus_dsp/test/test_pulsar_bus.cpp`:

```cpp
#include "test_framework.h"
#include "orpheus_unit_pulsar.h"

static void test_drum_engines_classified_as_drums() {
    // BassDrum(21), SnareDrum(22), HiHat(23), Noise(17), Particle(18), Modal(20)
    ASSERT_EQ(kEngineBusType[21], PULSAR_BUS_DRUMS);
    ASSERT_EQ(kEngineBusType[22], PULSAR_BUS_DRUMS);
    ASSERT_EQ(kEngineBusType[23], PULSAR_BUS_DRUMS);
    ASSERT_EQ(kEngineBusType[17], PULSAR_BUS_DRUMS);
    ASSERT_EQ(kEngineBusType[18], PULSAR_BUS_DRUMS);
    ASSERT_EQ(kEngineBusType[20], PULSAR_BUS_DRUMS);
}

static void test_bass_engines_classified_as_bass() {
    // VirtualAnalogVCF(0) is bass-oriented
    ASSERT_EQ(kEngineBusType[0], PULSAR_BUS_BASS);
}

static void test_melodic_engines_classified_as_keys() {
    // FM(10), Wavetable(13), Chord(14), String(19)
    ASSERT_EQ(kEngineBusType[10], PULSAR_BUS_KEYS);
    ASSERT_EQ(kEngineBusType[13], PULSAR_BUS_KEYS);
    ASSERT_EQ(kEngineBusType[14], PULSAR_BUS_KEYS);
    ASSERT_EQ(kEngineBusType[19], PULSAR_BUS_KEYS);
}

static void test_all_engines_have_valid_bus() {
    for (int i = 0; i < 24; i++) {
        PulsarBusType bus = kEngineBusType[i];
        ASSERT_TRUE(bus == PULSAR_BUS_KEYS || bus == PULSAR_BUS_DRUMS || bus == PULSAR_BUS_BASS);
    }
}

REGISTER_SUITE("pulsar_bus", {
    RUN_TEST(test_drum_engines_classified_as_drums);
    RUN_TEST(test_bass_engines_classified_as_bass);
    RUN_TEST(test_melodic_engines_classified_as_keys);
    RUN_TEST(test_all_engines_have_valid_bus);
});
```

- [ ] **Step 4: Run test to verify it compiles and passes**

```bash
cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop \
  -DEURORACK_DIR=$HOME/Source/eurorack \
  -DBUILD_TESTS=ON -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && \
cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && \
liborpheus_dsp/build-desktop/orpheus_dsp_test pulsar_bus
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_unit_pulsar.h liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/test/test_pulsar_bus.cpp
git commit -m "Add Pulsar engine-type bus classification table and buffers"
```

---

## Task 3: C++ Per-Track Bus Accumulation

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_unit_pulsar.cpp:764-795`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp:333-358`
- Modify: `liborpheus_dsp/test/test_pulsar_bus.cpp`

- [ ] **Step 1: Add bus accumulation integration test**

Append to `test_pulsar_bus.cpp`:

```cpp
#include "orpheus_engine.h"
#include "orpheus_graph.h"

static void test_pulsar_bus_accumulation() {
    // Verify that after processing Pulsar, bus buffers contain non-zero audio
    // when tracks are assigned to different engine types.
    auto engine = std::make_unique<OrpheusEngine>();
    engine->sample_rate = 48000.0f;

    // Enable Pulsar playback
    engine->pulsar_playing.store(1);
    engine->pulsar_scene.store(0); // Cosmic Techno — has drums + melodic
    engine->pulsar_energy.store(0.7f);
    engine->pulsar_bpm.store(120.0f);

    // Load and process the default graph for a few frames to let Pulsar produce audio
    auto graph_data = build_default_wiring_graph();
    OrpheusGraph graph;
    orpheus_graph_load(&graph, graph_data.data(), graph_data.size());
    orpheus_graph_sort(&graph);

    // Run several frames so sequencer advances and voices render
    for (int f = 0; f < 20; f++) {
        orpheus_graph_process(&graph, engine.get(), 64);
    }

    // At least one bus should have non-zero audio
    bool has_keys = false, has_drums = false, has_bass = false;
    for (int i = 0; i < 64; i++) {
        if (std::fabs(engine->pulsar_bus_keys_l[i]) > 1e-6f) has_keys = true;
        if (std::fabs(engine->pulsar_bus_drums_l[i]) > 1e-6f) has_drums = true;
        if (std::fabs(engine->pulsar_bus_bass_l[i]) > 1e-6f) has_bass = true;
    }
    // Cosmic Techno scene has drum engines (kick, snare, hat) so drums bus must have audio
    ASSERT_TRUE(has_drums);
    // At least keys or bass should also have audio (pad, lead, bass tracks)
    ASSERT_TRUE(has_keys || has_bass);
}

// Add to suite registration:
// RUN_TEST(test_pulsar_bus_accumulation);
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && \
liborpheus_dsp/build-desktop/orpheus_dsp_test pulsar_bus
```

Expected: `test_pulsar_bus_accumulation` FAILS (bus buffers are all zero).

- [ ] **Step 3: Add bus accumulation to `orpheus_unit_pulsar.cpp`**

In the per-track accumulation loop (line 764), add bus routing after the existing stereo mix. Replace lines 764-773 with:

```cpp
        // ── Mix to stereo with constant-power pan ──
        float vol = track_volume;
        float track_peak = 0.0f;

        // Look up bus for this track's current engine
        int engine_id = ts.engine_index;
        if (engine_id < 0) engine_id = 0;
        if (engine_id >= 24) engine_id = 0;
        PulsarBusType bus = kEngineBusType[engine_id];

        // Select bus output pointers
        float* bus_l;
        float* bus_r;
        switch (bus) {
            case PULSAR_BUS_DRUMS:
                bus_l = engine->pulsar_bus_drums_l;
                bus_r = engine->pulsar_bus_drums_r;
                break;
            case PULSAR_BUS_BASS:
                bus_l = engine->pulsar_bus_bass_l;
                bus_r = engine->pulsar_bus_bass_r;
                break;
            default: // PULSAR_BUS_KEYS
                bus_l = engine->pulsar_bus_keys_l;
                bus_r = engine->pulsar_bus_keys_r;
                break;
        }

        for (int i = 0; i < num_frames; i++) {
            float s = track_buffer[i] * vol;
            out_l[i] += s * pan_l;
            out_r[i] += s * pan_r;
            // Also accumulate into the per-bus buffer
            bus_l[i] += s * pan_l;
            bus_r[i] += s * pan_r;
            float a = std::fabs(s);
            if (a > track_peak) track_peak = a;
        }
```

- [ ] **Step 4: Zero bus buffers at start of `unit_process_pulsar`**

At the top of `unit_process_pulsar()`, after `out_l`/`out_r` are zeroed (around line 290), add:

```cpp
    // Zero per-bus accumulation buffers
    std::memset(engine->pulsar_bus_keys_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_keys_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_drums_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_drums_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_bass_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_bass_r, 0, num_frames * sizeof(float));
```

- [ ] **Step 5: Copy bus buffers to `warps_source_buffers` in `orpheus_graph.cpp`**

In `orpheus_graph_process()`, after the Pulsar bus buffers are filled (the unit has been processed), copy them to source buffers. Add after the existing warps buffer double-buffering section (after line 358):

```cpp
    // Pulsar bus → warps_source_buffers for turntable capture.
    // Accumulate (+=) so Pulsar buses add to any existing voice/drum sources.
    for (int i = 0; i < num_frames; i++) {
        // Keys → SYNTH slot (index 0), mono sum
        engine->warps_source_buffers[0][i] +=
            (engine->pulsar_bus_keys_l[i] + engine->pulsar_bus_keys_r[i]) * 0.5f;
        // Drums → DRUMS slot (index 1), mono sum
        engine->warps_source_buffers[1][i] +=
            (engine->pulsar_bus_drums_l[i] + engine->pulsar_bus_drums_r[i]) * 0.5f;
        // Bass → BASS slot (index 9), mono sum
        engine->warps_source_buffers[9][i] +=
            (engine->pulsar_bus_bass_l[i] + engine->pulsar_bus_bass_r[i]) * 0.5f;
    }
```

- [ ] **Step 6: Run tests to verify bus accumulation works**

```bash
cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && \
liborpheus_dsp/build-desktop/orpheus_dsp_test pulsar_bus
```

Expected: All tests PASS.

- [ ] **Step 7: Run full C++ test suite to verify no regressions**

```bash
liborpheus_dsp/build-desktop/orpheus_dsp_test
```

Expected: All existing suites PASS.

- [ ] **Step 8: Commit**

```bash
git add liborpheus_dsp/src/orpheus_unit_pulsar.cpp liborpheus_dsp/src/orpheus_graph.cpp liborpheus_dsp/test/test_pulsar_bus.cpp
git commit -m "Route Pulsar per-track output to Keys/Drums/Bass source buffers

Each track's engine ID is classified via kEngineBusType[] and its audio
is accumulated into the corresponding warps_source_buffers slot. The DJ
turntable can now capture Keys (melodic), Drums (percussive), or Bass
from Pulsar independently."
```

---

## Task 4: PocketDJ Wiring Graph (Kotlin)

**Files:**
- Create: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/PocketDjWiringGraph.kt`
- Modify: `core/dsp-engine/build.gradle.kts` (if needed — check it already has the right dependencies)

- [ ] **Step 1: Create `PocketDjWiringGraph.kt`**

```kotlin
package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.audio.dsp.WiringGraphDsl.Companion.IPORT_INPUT_A
import org.balch.orpheus.core.audio.dsp.WiringGraphDsl.Companion.IPORT_INPUT_B
import org.balch.orpheus.core.audio.dsp.WiringGraphDsl.Companion.wiringGraph

/**
 * Minimal DSP wiring graph for PocketDJ.
 *
 * Topology:
 *   Pulsar (8-track beat machine)
 *     → warps_source_buffers (Keys/Drums/Bass via engine-type classification)
 *     → Master Out
 *   Turntable (captures from source buffers)
 *     → Delay send → Delay
 *     → Reverb send → Reverb
 *     → Master Out
 *   Delay → Master Out
 *   Reverb → Master Out
 */
fun buildPocketDjWiringGraph(): ByteArray = wiringGraph {
    // ── Units ──
    val pulsarUnit = pulsar("pulsar")
    val turntableUnit = turntable("turntable")
    val delay = delay("delay")
    val reverb = reverb("reverb")
    val master = masterOut("master")

    // Turntable send gains (controlled via DJ plugin ports)
    val ttDelaySend = multiply("ttDelaySend") { inputB = 0.0f }
    val ttReverbSend = multiply("ttReverbSend") { inputB = 0.0f }

    // ── Connections ──

    // Pulsar direct → master (stereo)
    pulsarUnit.out to master.inputA
    pulsarUnit.outRight to master.inputB

    // Turntable → send gains → effects
    turntableUnit.out to ttDelaySend.inputA
    turntableUnit.out to ttReverbSend.inputA

    // Turntable delay send → delay (mono → both channels)
    ttDelaySend.out to delay.inputA
    ttDelaySend.out to delay.inputB

    // Turntable reverb send → reverb
    ttReverbSend.out to reverb.inputA
    ttReverbSend.out to reverb.inputB

    // Turntable direct → master
    turntableUnit.out to master.inputA
    turntableUnit.out to master.inputB

    // Effects → master
    delay.out to master.inputA
    delay.outRight to master.inputB
    reverb.out to master.inputA
    reverb.outRight to master.inputB

    // ── Port Map ──
    portMap {
        // DJ Turntable send levels
        map("org.balch.orpheus.plugins.dj", "delay_send", "ttDelaySend", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.dj", "reverb_send", "ttReverbSend", IPORT_INPUT_B)
    }
}
```

- [ ] **Step 2: Verify the dsp-engine module builds**

```bash
./gradlew :core:dsp-engine:compileKotlinJvm
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/PocketDjWiringGraph.kt
git commit -m "Add PocketDJ minimal wiring graph

Pulsar → Master, Turntable → Delay/Reverb → Master.
Only 7 units vs ~60 in the full Orpheus graph."
```

---

## Task 5: PocketDJ App Module Setup

**Files:**
- Create: `apps/pocketdj/build.gradle.kts`
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjGraph.kt`
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjModule.kt`
- Create: `apps/pocketdj/src/jvmMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjGraph.jvm.kt`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Add module to `settings.gradle.kts`**

After the `include(":apps:orpheus")` line, add:

```kotlin
include(":apps:pocketdj")
```

- [ ] **Step 2: Create `apps/pocketdj/build.gradle.kts`**

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.pocketdj"
    }

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "PocketDJ"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:dsp-engine"))
            api(project(":core:features"))
            api(project(":core:foundation"))
            api(project(":core:plugins:pulsar"))
            api(project(":core:plugins:dj"))
            api(project(":ui:panels"))
            api(project(":ui:theme"))
            api(project(":ui:widgets"))
            api(project(":features:pulsar"))
            api(project(":features:dj"))
            api(project(":features:timer"))
            implementation(libs.compose.material.icons)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.liquid)
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.metrox.viewmodel.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.slf4j.api)
            implementation(libs.logback.classic)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.desktop {
    application {
        mainClass = "org.balch.orpheus.pocketdj.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PocketDJ"
            packageVersion = "1.0.0"

            macOS {
                dockName = "PocketDJ"
            }
        }

        jvmArgs += listOf(
            "-Dorpheus.engine=cpp"
        )
        val nativePath = System.getProperty("orpheus.native.path", "")
        if (nativePath.isNotEmpty()) {
            jvmArgs += "-Djava.library.path=$nativePath"
        }
    }
}

// ── Native C++ DSP builds ───────────────────────────────────────────
val eurorackDir = File(System.getProperty("user.home"), "Source/eurorack").absolutePath

val buildDesktopNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build liborpheus_desktop native library for PocketDJ JVM"

    val desktopDir = rootProject.file("liborpheus_dsp/desktop")
    val arch = System.getProperty("os.arch").let {
        if (it == "aarch64" || it == "arm64") "aarch64" else "x86_64"
    }
    val osName = System.getProperty("os.name").lowercase().let {
        when {
            "mac" in it -> "darwin"
            "linux" in it -> "linux"
            else -> "windows"
        }
    }
    val libName = System.mapLibraryName("orpheus_desktop")
    val targetDir = layout.projectDirectory.dir("src/jvmMain/resources/native/$osName-$arch")

    workingDir = desktopDir
    commandLine("bash", "-c",
        "cmake -B build -DCMAKE_BUILD_TYPE=Release -DEURORACK_DIR=$eurorackDir && cmake --build build --config Release && " +
        "mkdir -p ${targetDir.asFile.absolutePath} && cp build/$libName ${targetDir.asFile.absolutePath}/$libName"
    )
}

tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn(buildDesktopNative)
}
```

- [ ] **Step 3: Create `PocketDjGraph.kt` (expect)**

Create `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjGraph.kt`:

```kotlin
package org.balch.orpheus.pocketdj.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.tempo.GlobalTempo

expect interface PocketDjGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator
    val synthEngine: SynthEngine
    val globalTempo: GlobalTempo
}
```

- [ ] **Step 4: Create `PocketDjModule.kt`**

Create `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjModule.kt`:

```kotlin
package org.balch.orpheus.pocketdj.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.buildPocketDjWiringGraph
import org.balch.orpheus.di.AppScope
import org.balch.orpheus.di.WiringGraphData

@ContributesTo(AppScope::class)
interface PocketDjModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideWiringGraphData(): WiringGraphData =
            WiringGraphData(buildPocketDjWiringGraph())
    }
}
```

Note: Check if `WiringGraphData` wrapper exists or if the graph bytes are provided differently. If the main app injects graph data via a different mechanism, match that pattern.

- [ ] **Step 5: Create `PocketDjGraph.jvm.kt` (actual)**

Create `apps/pocketdj/src/jvmMain/kotlin/org/balch/orpheus/pocketdj/di/PocketDjGraph.jvm.kt`:

```kotlin
package org.balch.orpheus.pocketdj.di

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.di.AppScope

@DependencyGraph(AppScope::class)
actual interface PocketDjGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val globalTempo: GlobalTempo

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): PocketDjGraph
    }
}
```

- [ ] **Step 6: Verify module compiles**

```bash
./gradlew :apps:pocketdj:compileKotlinJvm
```

Expected: BUILD SUCCESSFUL (may need DI adjustments — fix any Metro wiring errors).

- [ ] **Step 7: Commit**

```bash
git add apps/pocketdj/build.gradle.kts apps/pocketdj/src/ settings.gradle.kts
git commit -m "Add PocketDJ app module with DI graph and minimal dependencies"
```

---

## Task 6: PocketDJ App Composables

**Files:**
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/PocketDjApp.kt`
- Create: `apps/pocketdj/src/commonMain/kotlin/org/balch/orpheus/pocketdj/PocketDjScreen.kt`
- Create: `apps/pocketdj/src/jvmMain/kotlin/org/balch/orpheus/pocketdj/main.kt`

- [ ] **Step 1: Create `PocketDjApp.kt`**

```kotlin
package org.balch.orpheus.pocketdj

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.core.features.LocalSynthFeatures
import org.balch.orpheus.core.features.SynthFeatureRegistry
import org.balch.orpheus.core.features.feature
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerOverlay
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.pocketdj.di.PocketDjGraph
import org.balch.orpheus.ui.infrastructure.LocalDialogLiquidState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusTheme

@Composable
fun PocketDjApp(graph: PocketDjGraph) {
    CompositionLocalProvider(
        LocalMetroViewModelFactory provides graph.metroViewModelFactory,
    ) {
        val registry: SynthFeatureRegistry = metroViewModel()

        CompositionLocalProvider(LocalSynthFeatures provides registry) {
            val liquidState = rememberLiquidState()
            val dialogLiquidState = rememberLiquidState()
            val timerFeature: TimerFeature = registry.feature<TimerViewModel, TimerFeature>()

            OrpheusTheme {
                CompositionLocalProvider(
                    LocalLiquidState provides liquidState,
                    LocalDialogLiquidState provides dialogLiquidState,
                    LocalLiquidEffects provides emptyList(),
                ) {
                    Box(modifier = Modifier.fillMaxSize().liquefiable(dialogLiquidState)) {
                        PocketDjScreen(
                            modifier = Modifier.fillMaxSize()
                        )

                        TimerOverlay(
                            feature = timerFeature,
                            modifier = Modifier.fillMaxSize(),
                            liquidState = dialogLiquidState,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create `PocketDjScreen.kt`**

```kotlin
package org.balch.orpheus.pocketdj

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.features.LocalSynthFeatures
import org.balch.orpheus.core.features.feature
import org.balch.orpheus.features.dj.DjFeature
import org.balch.orpheus.features.dj.DjPanel
import org.balch.orpheus.features.dj.DjViewModel
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarViewModel

@Composable
fun PocketDjScreen(modifier: Modifier = Modifier) {
    val registry = LocalSynthFeatures.current
    val djFeature: DjFeature = registry.feature<DjViewModel, DjFeature>()
    val pulsarFeature = registry.feature<PulsarViewModel, org.balch.orpheus.features.pulsar.PulsarFeature>()

    BoxWithConstraints(modifier = modifier) {
        val isLandscape = maxWidth > maxHeight

        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                DjPanel(
                    feature = djFeature,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                PulsarPanel(
                    feature = pulsarFeature,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                DjPanel(
                    feature = djFeature,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                PulsarPanel(
                    feature = pulsarFeature,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}
```

- [ ] **Step 3: Create `main.kt` (JVM entry point)**

Create `apps/pocketdj/src/jvmMain/kotlin/org/balch/orpheus/pocketdj/main.kt`:

```kotlin
package org.balch.orpheus.pocketdj

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.pocketdj.di.PocketDjGraph

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")

    application {
        val graph = remember { createGraphFactory<PocketDjGraph.Factory>().create() }

        Window(
            onCloseRequest = ::exitApplication,
            title = "PocketDJ",
            state = rememberWindowState(width = 400.dp, height = 700.dp),
        ) {
            PocketDjApp(graph)
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
./gradlew :apps:pocketdj:compileKotlinJvm
```

Expected: BUILD SUCCESSFUL (may need adjustments to DjPanel/PulsarPanel signatures — fix as needed).

- [ ] **Step 5: Commit**

```bash
git add apps/pocketdj/src/
git commit -m "Add PocketDJ app composables with portrait/landscape layout

DJ panel on top (or left), Pulsar on bottom (or right).
Timer overlay on top. Minimal liquid glass setup."
```

---

## Task 7: Integration Test — Run PocketDJ

**Files:** None new — this is a verification task.

- [ ] **Step 1: Build and run PocketDJ JVM**

```bash
./gradlew :apps:pocketdj:run
```

Expected: Window opens with DJ panel (top) and Pulsar panel (bottom). Audio should play when Pulsar is started.

- [ ] **Step 2: Verify DJ captures Pulsar buses**

In the running app:
1. Start Pulsar playback (tap play)
2. Set DJ Deck A source to Drums
3. Bring up Deck A wet fader
4. Verify turntable captures drum sounds

- [ ] **Step 3: Verify landscape layout**

Resize the window to be wider than tall. Panels should switch to side-by-side layout.

- [ ] **Step 4: Verify timer overlay**

Open the sleep timer. It should overlay correctly on the PocketDJ layout.

- [ ] **Step 5: Verify Orpheus main app still works**

```bash
./gradlew :apps:orpheus:run
```

Expected: Full synth app works exactly as before.

- [ ] **Step 6: Final commit**

```bash
git add -A
git commit -m "PocketDJ: standalone DJ + Pulsar + Timer app

Minimal DSP graph with engine-type bus classification routing
Pulsar tracks to Keys/Drums/Bass buses for DJ turntable capture."
```

---

## Notes for Implementation

1. **DI wiring**: The `PocketDjModule` needs to provide `WiringGraphData` (or however the graph bytes reach `SynthEngine`). Check how `DefaultWiringGraph` data is currently injected in the main app — match that mechanism.

2. **Panel signatures**: `DjPanel` and `PulsarPanel` may expect specific feature types or additional parameters. Check their `@Composable` signatures and adapt `PocketDjScreen.kt` accordingly.

3. **Native library**: PocketDJ reuses the same `liborpheus_desktop` native library as Orpheus. The build task in `build.gradle.kts` handles copying it. Alternatively, the native lib could be shared from a common location.

4. **Timer feature**: Timer is a feature module with its own ViewModel. It should be auto-discovered by Metro's `@ContributesIntoMap` without explicit registration, as long as the feature module is on the classpath.

5. **Engine-type classification**: The table in Task 2 assigns VirtualAnalogVCF (engine 0) to BASS. This may need tuning — engine 0 is used as both a bass and a lead depending on context. If the user prefers a different default, the table is easy to update.
