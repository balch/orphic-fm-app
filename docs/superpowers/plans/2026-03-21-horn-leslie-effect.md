# Horn (Leslie Effect) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standalone Leslie speaker simulation effect with dual-rotor animation, ported from MI Ensemble.

**Architecture:** C++ DSP unit (`orpheus_unit_horn.cpp`) ported from MI `plaits/dsp/fx/ensemble.h`, removing MI dependencies. Extended with crossover filter, independent rotor inertia, and 6 parameters (Speed, Ratio, Depth, Amount, Mix, Brake). Kotlin integration follows the standard Symbol → Plugin → ViewModel → Panel pattern. Dual Canvas animations (concentric rings + cabinet cross-section) driven by phase data from the DSP engine.

**Tech Stack:** C++ (DSP), Kotlin Multiplatform (state/UI), Compose (animations), Metro DI

**Spec:** `docs/superpowers/specs/2026-03-21-horn-leslie-effect-design.md`

---

### Task 1: C++ Engine Integration — Headers & Enums

Add Horn support to the C++ engine infrastructure before writing the DSP code.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_graph.h` (add `UNIT_HORN = 32`)
- Modify: `liborpheus_dsp/src/orpheus_viz.h` (add `VIZ_HORN_IN`, `VIZ_HORN_OUT`, `VIZ_HORN_PHASE`, `VIZ_WOOFER_PHASE`)
- Modify: `liborpheus_dsp/src/orpheus_units.h` (add function declaration)
- Modify: `liborpheus_dsp/src/orpheus_engine.h` (add atomic fields + delay buffers + `#include "orpheus_horn.h"`)

- [ ] **Step 1: Add UNIT_HORN to GraphUnitType enum**

In `liborpheus_dsp/src/orpheus_graph.h`, add before `UNIT_TYPE_COUNT`:
```cpp
UNIT_COMPRESSOR = 31,
UNIT_HORN = 32,
UNIT_TYPE_COUNT
```

- [ ] **Step 2: Add VizChannel entries**

In `liborpheus_dsp/src/orpheus_viz.h`, add before `VIZ_CHANNEL_COUNT`:
```cpp
VIZ_MASTER_OUT = 19,
VIZ_HORN_IN = 20,       // audio peak into Leslie effect
VIZ_HORN_OUT = 21,      // audio peak out of Leslie effect
VIZ_HORN_PHASE = 22,    // normalized horn rotor phase 0..1 (for animation)
VIZ_WOOFER_PHASE = 23,  // normalized woofer rotor phase 0..1 (for animation)
VIZ_CHANNEL_COUNT
```

- [ ] **Step 3: Add function declaration to orpheus_units.h**

After `unit_process_compressor`:
```cpp
void unit_process_horn(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
```

- [ ] **Step 4: Add Horn state to OrpheusEngine**

In `liborpheus_dsp/src/orpheus_engine.h`, add a Horn section (near the delay/reverb sections):
```cpp
// ── Horn (Leslie Speaker) ─────────────────────────────────────
static constexpr int kHornBufferSize = 2048;   // delay line for chorus (~42ms @ 48kHz)
static constexpr int kHornMask = kHornBufferSize - 1;
float horn_delay_l[kHornBufferSize] = {};
float horn_delay_r[kHornBufferSize] = {};
int horn_write_pos = 0;
float horn_slow_phase = 0.0f;    // horn rotor phase 0..1
float horn_fast_phase = 0.0f;    // woofer rotor phase 0..1
float smooth_horn_mix = 0.0f;
float smooth_horn_speed = 0.5f;  // smoothed speed for inertia

// Horn parameter atomics
std::atomic<float> horn_speed{0.5f};        // base rotor speed 0..1
std::atomic<float> horn_ratio{0.5f};        // horn:woofer ratio 0..1
std::atomic<float> horn_depth{0.5f};        // Doppler delay depth 0..1
std::atomic<float> horn_amount{0.5f};       // modulation amount 0..1
std::atomic<float> horn_mix{0.0f};          // dry/wet 0..1
std::atomic<int>   horn_brake{0};           // 0=off, 1=braking
std::atomic<int>   horn_bypass{1};          // 1=bypassed (mix<=0.001)
```

- [ ] **Step 5: Verify C++ compiles**

Run: `cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop -DEURORACK_DIR=/Users/balch/Source/eurorack -DBUILD_TESTS=ON -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && cmake --build liborpheus_dsp/build-desktop`
Expected: Build succeeds (no references to `unit_process_horn` yet, just declarations)

- [ ] **Step 6: Commit**

```bash
git add liborpheus_dsp/src/orpheus_graph.h liborpheus_dsp/src/orpheus_viz.h \
        liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_engine.h
git commit -m "feat(horn): add C++ engine infrastructure — enums, atomics, viz channels"
```

---

### Task 2: C++ DSP — `orpheus_horn.h` + `orpheus_unit_horn.cpp`

Port MI Ensemble into a standalone Leslie effect processor.

**Files:**
- Create: `liborpheus_dsp/src/orpheus_horn.h` (ported Ensemble class, Orpheus-native)
- Create: `liborpheus_dsp/src/orpheus_unit_horn.cpp` (unit process function)
- Modify: `liborpheus_dsp/src/orpheus_engine.h` (add `#include "orpheus_horn.h"` if the horn class is stored in engine, or just forward-declare)
- Modify: `liborpheus_dsp/CMakeLists.txt` (add source file)
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp` (add dispatch case)

**Reference:** MI source at `/Users/balch/Source/eurorack/plaits/dsp/fx/ensemble.h`

- [ ] **Step 1: Create `orpheus_horn.h`**

Port the MI `Ensemble` class into an Orpheus-native `OrpheusHorn` class. Remove dependencies on `stmlib`, `FxEngine`, `SineRaw()`, and `plaits/resources.h`. Use:
- `std::sin()` instead of `SineRaw()` lookup table
- Simple circular float buffer instead of `FxEngine` delay lines
- Add crossover filter state (2nd-order Linkwitz-Riley LP/HP coefficients)
- Add inertia state (current_speed slewing toward target_speed)
- Class should own its delay buffers and LFO phase state

- [ ] **Step 2: Create `orpheus_unit_horn.cpp`**

Port the MI Ensemble algorithm using the `OrpheusHorn` class from the header. Replace with:
- Inline `sin()` calls instead of `SineRaw()` lookup table
- Simple circular buffer instead of `FxEngine` delay lines
- Add crossover filter (2nd-order Linkwitz-Riley at ~800 Hz)
- Add independent speed targets with inertia (ramp-up ~1s, ramp-down ~3s)
- Add Ratio mapping (center = classic ~1:9 Leslie)
- Self-bypass when `mix <= 0.001f`
- Write peaks to `VIZ_HORN_IN` and `VIZ_HORN_OUT`
- Write normalized rotor phases (0..1) to `VIZ_HORN_PHASE` and `VIZ_WOOFER_PHASE` for animation sync

Key DSP structure:
```cpp
#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

void unit_process_horn(GraphUnit* u, OrpheusEngine* engine,
                       int num_frames, float sample_rate) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    float mix_target = engine->horn_mix.load(std::memory_order_relaxed);
    if (mix_target <= 0.001f) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        engine->viz_rings[VIZ_HORN_IN].write(0.0f);
        engine->viz_rings[VIZ_HORN_OUT].write(0.0f);
        engine->viz_rings[VIZ_HORN_PHASE].write(0.0f);
        engine->viz_rings[VIZ_WOOFER_PHASE].write(0.0f);
        engine->horn_bypass.store(1, std::memory_order_relaxed);
        return;
    }
    engine->horn_bypass.store(0, std::memory_order_relaxed);

    // Load inputs and parameters...
    // Process: crossover → horn modulator + woofer modulator → recombine → mix
    // Write viz peaks
}
```

The full implementation should follow the MI Ensemble's 3-phase delay modulation with 120° offsets, adapted for dual independent rotors with inertia. This is a creative implementation task — refer to the MI source and the spec's "Extensions" section for the exact algorithm.

- [ ] **Step 2: Add source file to CMakeLists.txt**

In `liborpheus_dsp/CMakeLists.txt`, add to the source list (alphabetically near other unit files):
```cmake
"src/orpheus_unit_horn.cpp"
```

- [ ] **Step 3: Add graph dispatch**

In `liborpheus_dsp/src/orpheus_graph.cpp`, in the switch statement (after `UNIT_COMPRESSOR`):
```cpp
case UNIT_HORN:
    unit_process_horn(u, engine, num_frames, sr); break;
```

- [ ] **Step 4: Build and verify**

Run: `cmake --build liborpheus_dsp/build-desktop`
Expected: Compiles without errors

- [ ] **Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_unit_horn.cpp liborpheus_dsp/CMakeLists.txt \
        liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(horn): implement Leslie DSP unit — dual-rotor ensemble with crossover"
```

---

### Task 3: C++ Routing — `set_port()` Handler

Wire Kotlin port values to C++ engine atomics.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine_routing.cpp`

- [ ] **Step 1: Add Horn routing handler**

After the last plugin block (bass or similar), add:
```cpp
else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.horn") == 0) {
    if (std::strcmp(symbol, "speed") == 0)
        engine->horn_speed.store(value, std::memory_order_relaxed);
    else if (std::strcmp(symbol, "ratio") == 0)
        engine->horn_ratio.store(value, std::memory_order_relaxed);
    else if (std::strcmp(symbol, "depth") == 0)
        engine->horn_depth.store(value, std::memory_order_relaxed);
    else if (std::strcmp(symbol, "amount") == 0)
        engine->horn_amount.store(value, std::memory_order_relaxed);
    else if (std::strcmp(symbol, "mix") == 0) {
        engine->horn_mix.store(value, std::memory_order_relaxed);
        engine->horn_bypass.store(value <= 0.001f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(symbol, "brake") == 0)
        engine->horn_brake.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
}
```

- [ ] **Step 2: Build and verify**

Run: `cmake --build liborpheus_dsp/build-desktop`
Expected: Compiles

- [ ] **Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine_routing.cpp
git commit -m "feat(horn): add set_port routing for horn parameters"
```

---

### Task 4: C++ Tests

Add test cases for the horn unit.

**Files:**
- Modify: `liborpheus_dsp/test/test_effects.cpp` (add horn unit tests)

- [ ] **Step 1: Add horn unit tests**

Test cases:
1. **Self-bypass**: Set `horn_mix = 0.0`, process, verify output is all zeros
2. **Pass-through at low amount**: Set `horn_mix = 1.0`, `horn_amount = 0.0`, verify output ≈ input
3. **Non-zero output**: Set `horn_mix = 0.5`, `horn_amount = 0.5`, `horn_speed = 0.5`, process sine wave input, verify output differs from input
4. **Brake ramps down**: Set `horn_brake = 1`, process multiple blocks, verify speed decreases

- [ ] **Step 2: Run tests**

Run: `cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add liborpheus_dsp/
git commit -m "test(horn): add C++ unit tests for Leslie DSP"
```

---

### Task 5: Kotlin Symbol + Plugin

Create the Kotlin state layer.

**Files:**
- Create: `core/plugin-api/src/commonMain/kotlin/org/balch/orpheus/core/plugin/symbols/HornSymbol.kt`
- Create: `core/plugins/horn/build.gradle.kts`
- Create: `core/plugins/horn/src/commonMain/kotlin/org/balch/orpheus/plugins/horn/HornPlugin.kt`
- Modify: `settings.gradle.kts` (add `:core:plugins:horn`)

- [ ] **Step 1: Create HornSymbol.kt**

```kotlin
package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val HORN_URI = "org.balch.orpheus.plugins.horn"

enum class HornSymbol(
    override val symbol: Symbol,
    override val uri: String = HORN_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    SPEED("speed", displayName = "Speed"),
    RATIO("ratio", displayName = "Ratio"),
    DEPTH("depth", displayName = "Depth"),
    AMOUNT("amount", displayName = "Amount"),
    MIX("mix", displayName = "Mix"),
    BRAKE("brake", displayName = "Brake")
}
```

- [ ] **Step 2: Create plugin build.gradle.kts**

At `core/plugins/horn/build.gradle.kts`:
```kotlin
plugins {
    id("orpheus.kmp.library")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.plugins.horn"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:audio"))
        }
    }
}
```

- [ ] **Step 3: Create HornPlugin.kt**

At `core/plugins/horn/src/commonMain/kotlin/org/balch/orpheus/plugins/horn/HornPlugin.kt`:
```kotlin
package org.balch.orpheus.plugins.horn

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.HORN_URI
import org.balch.orpheus.core.plugin.symbols.HornSymbol

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class HornPlugin : DspPlugin {

    override val info = PluginInfo(uri = URI, name = "Horn", author = "Orpheus")

    companion object { const val URI = HORN_URI }

    private var _speed = 0.5f
    private var _ratio = 0.5f
    private var _depth = 0.5f
    private var _amount = 0.5f
    private var _mix = 0.0f
    private var _brake = false

    private val portDefs = ports(startIndex = 4) {
        controlPort(HornSymbol.SPEED) { floatType { default = 0.5f; get { _speed }; set { _speed = it } } }
        controlPort(HornSymbol.RATIO) { floatType { default = 0.5f; get { _ratio }; set { _ratio = it } } }
        controlPort(HornSymbol.DEPTH) { floatType { default = 0.5f; get { _depth }; set { _depth = it } } }
        controlPort(HornSymbol.AMOUNT) { floatType { default = 0.5f; get { _amount }; set { _amount = it } } }
        controlPort(HornSymbol.MIX) { floatType { default = 0.0f; get { _mix }; set { _mix = it } } }
        controlPort(HornSymbol.BRAKE) { boolType { default = false; get { _brake }; set { _brake = it } } }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts
    override fun onStart() {}
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
```

- [ ] **Step 4: Add to settings.gradle.kts**

Add `include(":core:plugins:horn")` in alphabetical order among plugins.

- [ ] **Step 5: Build plugin**

Run: `./gradlew :core:plugins:horn:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add core/plugin-api/src/commonMain/kotlin/org/balch/orpheus/core/plugin/symbols/HornSymbol.kt \
        core/plugins/horn/ settings.gradle.kts
git commit -m "feat(horn): add HornSymbol and HornPlugin"
```

---

### Task 6: Kotlin ViewModel + Feature

Create the MVI ViewModel following the canonical LfoViewModel pattern.

**Files:**
- Create: `features/horn/build.gradle.kts`
- Create: `features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornViewModel.kt`
- Modify: `settings.gradle.kts` (add `:features:horn`)
- Modify: `core/features/src/commonMain/kotlin/org/balch/orpheus/core/features/FeaturePanel.kt` (add `PanelId.HORN`)

- [ ] **Step 1: Add PanelId.HORN**

In `core/features/.../FeaturePanel.kt`, add to the `PanelId` companion object:
```kotlin
val HORN = PanelId("horn")
```

- [ ] **Step 2: Create feature build.gradle.kts**

At `features/horn/build.gradle.kts`:
```kotlin
plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.features.horn"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.pluginApi)
        }
    }
}
```

- [ ] **Step 3: Create HornViewModel.kt**

At `features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornViewModel.kt`:

Follow the `ReverbViewModel.kt` pattern exactly, with:
- `HornUiState`: speed, ratio, depth, amount, mix (all Float), brake (Boolean)
- `HornPanelActions`: setSpeed, setRatio, setDepth, setAmount, setMix, setBrake
- `HornIntent`: Speed, Ratio, Depth, Amount, Mix, Brake variants
- `HornFeature` interface with `SynthControlDescriptor` (panelId = `PanelId.HORN`, title = "Horn")
- `portControlKeys` mapping all 6 `HornSymbol` control IDs
- `markdown` docs (NO Mutable Instruments trademark names — use "rotating speaker cabinet")
- `HornViewModel` class with all 6 `controlFlow()`/`floatSetter()`/`boolSetter()` patterns
- `previewFeature()` and `@Composable feature()` in companion

- [ ] **Step 4: Add to settings.gradle.kts**

Add `include(":features:horn")` in alphabetical order among features.

- [ ] **Step 5: Build feature**

Run: `./gradlew :features:horn:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add core/features/src/commonMain/kotlin/org/balch/orpheus/core/features/FeaturePanel.kt \
        features/horn/ settings.gradle.kts
git commit -m "feat(horn): add HornViewModel with MVI pattern"
```

---

### Task 7: Kotlin Panel — Basic Controls

Create the panel with knobs and brake toggle (animations come in Task 9).

**Files:**
- Create: `features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornPanel.kt`

- [ ] **Step 1: Create HornPanel.kt**

Layout: Placeholder area for animations on top (will be filled in Task 9), knob row on bottom.
Controls: SPEED, RATIO, DEPTH, AMOUNT knobs + MIX knob + BRAKE toggle.
Color theme: Blackout Crimson (`#080808` bg, `#cc2222` / `#881111` accents).

Follow `ReverbPanel.kt` pattern for the `CollapsibleColumnPanel` wrapper, viz flows, etc.
Use `OrpheusColors` — may need to add a crimson color constant.

- [ ] **Step 2: Build and verify**

Run: `./gradlew :features:horn:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornPanel.kt
git commit -m "feat(horn): add HornPanel with controls (animations TBD)"
```

---

### Task 8: Kotlin Panel Registration + Viz Wiring

Register the panel and wire visualization flows.

**Files:**
- Create: `features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornPanelRegistration.kt`
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/SynthEngine.kt` (add `hornInVizFlow`, `hornOutVizFlow`)
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspSynthEngine.kt` (add viz flow overrides)
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/SynthEngineMonitor.kt` (add polling for `VIZ_HORN_IN`, `VIZ_HORN_OUT`)
- Modify: `apps/composeApp/src/commonMain/kotlin/org/balch/orpheus/ui/FactoryPanelSets.kt` (add `PanelId.HORN`)

- [ ] **Step 1: Create HornPanelRegistration.kt**

Follow `ReverbPanelRegistration.kt` pattern:
```kotlin
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class HornPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.HORN
    override val description = "Rotating speaker cabinet effect"
    override val weight = 0.65f
    override val label = "Horn"
    override val color = /* crimson Color */
    // ... Content composable, preview companion
}
```

- [ ] **Step 2: Add viz flow properties to SynthEngine**

In `core/foundation/.../SynthEngine.kt`, add:
```kotlin
val hornInVizFlow: StateFlow<FloatArray> get() = emptyVizFlow
val hornOutVizFlow: StateFlow<FloatArray> get() = emptyVizFlow
val hornPhaseVizFlow: StateFlow<FloatArray> get() = emptyVizFlow
val wooferPhaseVizFlow: StateFlow<FloatArray> get() = emptyVizFlow
```

- [ ] **Step 3: Add viz flow overrides to DspSynthEngine**

In `core/dsp-engine/.../DspSynthEngine.kt`:
```kotlin
override val hornInVizFlow: StateFlow<FloatArray> get() = monitor.hornInVizFlow
override val hornOutVizFlow: StateFlow<FloatArray> get() = monitor.hornOutVizFlow
override val hornPhaseVizFlow: StateFlow<FloatArray> get() = monitor.hornPhaseVizFlow
override val wooferPhaseVizFlow: StateFlow<FloatArray> get() = monitor.wooferPhaseVizFlow
```

- [ ] **Step 4: Add viz polling to SynthEngineMonitor**

In `core/dsp-engine/.../SynthEngineMonitor.kt`:
- Add Kotlin constants matching C++ enum: `private const val VIZ_HORN_IN = 20`, `VIZ_HORN_OUT = 21`, `VIZ_HORN_PHASE = 22`, `VIZ_WOOFER_PHASE = 23`
- Update `VIZ_CHANNEL_COUNT` to match C++ (24)
- Add `_hornInVizFlow`, `_hornOutVizFlow`, `_hornPhaseVizFlow`, `_wooferPhaseVizFlow` MutableStateFlows
- Add public StateFlow properties for each
- Add `pollVizChannel(VIZ_HORN_IN, ...)`, `pollVizChannel(VIZ_HORN_OUT, ...)`, `pollVizChannel(VIZ_HORN_PHASE, ...)`, `pollVizChannel(VIZ_WOOFER_PHASE, ...)` calls
- Add all flows to the reset list

- [ ] **Step 5: Add Horn to FactoryPanelSets**

In `apps/composeApp/.../FactoryPanelSets.kt`, add `collapse(PanelId.HORN)` to the `All` set and appropriate entries in other sets (e.g., `Effects` set).

- [ ] **Step 6: Build full app**

Run: `./gradlew compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornPanelRegistration.kt \
        core/foundation/ core/dsp-engine/ apps/composeApp/
git commit -m "feat(horn): wire panel registration, viz flows, and factory panel sets"
```

---

### Task 9: Kotlin Wiring Graph

Add the Horn unit to the DSP signal chain.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`

- [ ] **Step 1: Add Horn unit to the wiring graph**

**IMPORTANT: Horn is an inline effect, not a parallel send.** It must be inserted into the signal chain so that audio passes through it (with internal dry/wet mix handling bypass). The spec says: `"... → warps → horn → delay → reverb → ..."`.

The horn receives from drive output (same point as delay/reverb currently do) and its output replaces those existing connections to delay and reverb. This avoids signal doubling.

Add a `horn` DSL function if needed (check existing patterns like `reverb()`, `dualDelay()`), then:

1. **Remove** existing `driveL.out to delay.inputA` and `driveR.out to delay.inputB` connections
2. **Remove** existing `driveL.out to reverb.inputA` and `driveR.out to reverb.inputB` connections
3. **Insert horn inline**:
```kotlin
val horn = horn("horn")
// Drive → Horn
driveL.out to horn.inputA
driveR.out to horn.inputB
// Horn → Delay (replaces drive → delay)
horn.out to delay.inputA
horn.outRight to delay.inputB
// Horn → Reverb (replaces drive → reverb)
horn.out to reverb.inputA
horn.outRight to reverb.inputB
// Horn → Master (direct monitoring)
horn.out to master.inputA
horn.outRight to master.inputB
```

When horn mix=0 (bypass), the unit outputs silence, but the existing other routes (bass, warps, looper, bender → delay/reverb) still work because they have their own connections. Only the drive→delay and drive→reverb routes go through horn now.

**Note:** Other sources feeding delay/reverb (bass, looper, warps, bender) keep their existing direct connections — only the main voice drive path is routed through horn.

- [ ] **Step 2: Build and verify**

Run: `./gradlew compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/dsp-engine/
git commit -m "feat(horn): wire Leslie unit into DSP graph after drive, before master"
```

---

### Task 10: Dual Rotor Animation — Concentric Rings + Cabinet

Add the Blackout Crimson dual animations to the HornPanel.

**Files:**
- Modify: `features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornPanel.kt`
- Possibly modify: `features/horn/src/commonMain/kotlin/org/balch/orpheus/features/horn/HornViewModel.kt` (add phase state if using viz-driven animation)

- [ ] **Step 1: Add phase interpolation to HornUiState (if needed)**

If driving animations from viz data, add `hornPhase` and `wooferPhase` floats to `HornUiState`, with corresponding `HornIntent` variants and `controlFlow()` sources from the viz ring buffers. Alternatively, the animation can be driven purely client-side based on the current speed value — simpler and smoother at 60fps.

- [ ] **Step 2: Implement Concentric Rings animation (left side)**

Canvas composable drawing:
- Inner ring (horn rotor): brighter crimson `Color(0xFFCC2222)`, fast rotation
- Outer ring (woofer rotor): darker crimson `Color(0xFF881111)`, slow rotation
- Gradient arc trails using `drawArc()` with decreasing alpha
- Ember glow via radial gradient `drawCircle()` at rotor head positions
- Phase driven by `animateFloatAsState` or `rememberInfiniteTransition` keyed to speed
- Inertia: use `animateFloatAsState` with `tween(durationMillis = ...)` for ramp-up (1s) / ramp-down (3s)
- Brake: animate speed target to near-zero

- [ ] **Step 3: Implement Cabinet Cross-Section animation (right side)**

Canvas composable drawing:
- Louvered cabinet outline with subtle `#1a0808` border
- Horn rotor on top: curved paddle shape, 3D foreshortening via `scaleX` oscillation (cos of phase)
- Woofer drum on bottom: wider paddle, same foreshortening at its own speed
- Shelf divider between rotors
- Ember glow via `drawCircle()` with radial gradient behind rotor elements
- Motion blur: draw semi-transparent copies at previous phase positions

- [ ] **Step 4: Compose the dual animation layout**

Top of panel: `Row` containing both animation Canvases side by side.
Color theme constants:
```kotlin
val CrimsonBg = Color(0xFF080808)
val CrimsonHorn = Color(0xFFCC2222)
val CrimsonWoofer = Color(0xFF881111)
val CrimsonBorder = Color(0xFF1A0808)
val CrimsonKnob = Color(0xFFAA2222)
```

- [ ] **Step 5: Build and verify**

Run: `./gradlew :features:horn:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add features/horn/
git commit -m "feat(horn): add dual rotor animation — concentric rings + cabinet cross-section"
```

---

### Task 11: Integration Test — Full App Build

Verify everything compiles and links together.

**Files:** None (verification only)

- [ ] **Step 1: Full Kotlin build**

Run: `./gradlew compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Full app build**

Run: `./gradlew :apps:composeApp:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: C++ tests pass**

Run: `cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test`
Expected: All tests pass

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
git commit -m "fix(horn): resolve integration issues from full build"
```

---

## Reference Files

| Pattern | File |
|---------|------|
| Symbol | `core/plugin-api/.../symbols/ReverbSymbol.kt` |
| Plugin | `core/plugins/reverb/.../ReverbPlugin.kt` |
| ViewModel | `features/reverb/.../ReverbViewModel.kt` |
| Panel | `features/reverb/.../ReverbPanel.kt` |
| Registration | `features/reverb/.../ReverbPanelRegistration.kt` |
| PanelId | `core/features/.../FeaturePanel.kt` |
| C++ unit | `liborpheus_dsp/src/orpheus_unit_reverb.cpp` |
| C++ routing | `liborpheus_dsp/src/orpheus_engine_routing.cpp` |
| C++ dispatch | `liborpheus_dsp/src/orpheus_graph.cpp` |
| Graph wiring | `core/dsp-engine/.../DefaultWiringGraph.kt` |
| Viz monitor | `core/dsp-engine/.../SynthEngineMonitor.kt` |
| Factory panels | `apps/composeApp/.../FactoryPanelSets.kt` |
| MI Ensemble | `/Users/balch/Source/eurorack/plaits/dsp/fx/ensemble.h` |
