# Separate Drum Voices Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Separate drum voices from REPL/Quad 2, giving the engine 12 main + 3 drum = 15 voices, matching Kotlin architecture.

**Architecture:** Grow voice arrays to 15 slots. Add 3 dedicated drum plaits units (d0/d1/d2) to the ODWG graph with per-drum volume/pan and full MAIN/FX routing toggle (MAIN path: drums → dedicated Rings resonator → limiter → output; FX path: drums → main resonator → drive → delay). Update all boundary checks from `kNumMainVoices` (8) to `kDrumVoiceStart` (12). Add quad volume, quad hold, and drum isolation tests.

**Tech Stack:** C++ (liborpheus_dsp), Kotlin (DefaultWiringGraph.kt ODWG builder)

**Build/test commands:**
```bash
cd liborpheus_dsp/build_test
cmake --build . && ./orpheus_dsp_test
```

---

### Task 1: Update C++ voice constants

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h:31-33`

**Step 1: Change constants**

Replace:
```cpp
static constexpr int kNumMainVoices = 8;
static constexpr int kNumReplVoices = 4;
static constexpr int kNumVoices = kNumMainVoices + kNumReplVoices;
```

With:
```cpp
static constexpr int kNumMainVoices = 12;
static constexpr int kNumDrumVoices = 3;
static constexpr int kDrumVoiceStart = kNumMainVoices;  // 12
static constexpr int kNumVoices = kNumMainVoices + kNumDrumVoices;  // 15
```

**Step 2: Grow bender arrays**

In `orpheus_engine.h`, the bender arrays are sized to `kNumMainVoices`. Since main voices grow from 8 to 12, these automatically cover voices 0-11. No code change needed — they already use `kNumMainVoices`.

**Step 3: Build to verify compilation**

Run: `cmake --build .`
Expected: Compiles. Some tests may fail (drum trigger indices changed) — that's expected until Task 2.

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h
git commit -m "refactor(dsp): Grow voice layout to 12 main + 3 drum = 15 total"
```

---

### Task 2: Update C++ engine drum trigger and pan defaults

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp:79-89` (pan defaults)
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp:601-619` (trigger_drum)

**Step 1: Update pan defaults in `orpheus_engine_create`**

Replace:
```cpp
engine->voice_pan[0].store(0.0f);
engine->voice_pan[1].store(0.0f);
engine->voice_pan[2].store(-0.3f);
engine->voice_pan[3].store(-0.3f);
engine->voice_pan[4].store(0.3f);
engine->voice_pan[5].store(0.3f);
engine->voice_pan[6].store(-0.7f);
engine->voice_pan[7].store(0.7f);
for (int i = 8; i < kNumVoices; i++)
    engine->voice_pan[i].store(0.0f);
```

With:
```cpp
// Quad 0 (voices 0-3)
engine->voice_pan[0].store(0.0f);
engine->voice_pan[1].store(0.0f);
engine->voice_pan[2].store(-0.3f);
engine->voice_pan[3].store(-0.3f);
// Quad 1 (voices 4-7)
engine->voice_pan[4].store(0.3f);
engine->voice_pan[5].store(0.3f);
engine->voice_pan[6].store(-0.7f);
engine->voice_pan[7].store(0.7f);
// Quad 2 / REPL (voices 8-11) + drum voices (12-14): center
for (int i = 8; i < kNumVoices; i++)
    engine->voice_pan[i].store(0.0f);
```

Note: The code is the same — the loop `8..kNumVoices` now covers 8-14 instead of 8-11. Just update the comments.

**Step 2: Update `orpheus_engine_trigger_drum`**

Replace the entire function:
```cpp
void orpheus_engine_trigger_drum(OrpheusEngine* engine,
                                 int drum_index, float accent) {
    // Map drum indices to dedicated drum voices (12-14):
    // 0 = bass drum  (voice 12, engine 21)
    // 1 = snare drum (voice 13, engine 22)
    // 2 = hi-hat     (voice 14, engine 23)
    static const int kDrumEngineIndices[] = {21, 22, 23};

    if (drum_index >= 0 && drum_index < kNumDrumVoices) {
        int voice_index = kDrumVoiceStart + drum_index;
        engine->voice_params[voice_index].engine_index.store(kDrumEngineIndices[drum_index]);
        engine->voice_params[voice_index].tune.store(60.0f);
        engine->voice_params[voice_index].morph.store(accent, std::memory_order_relaxed);
        engine->voice_params[voice_index].active.store(1);
        engine->voice_params[voice_index].ever_triggered.store(1);
        engine->voice_params[voice_index].gate.store(1);
    }
}
```

**Step 3: Build**

Run: `cmake --build .`
Expected: Compiles.

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "refactor(dsp): Update drum trigger to use dedicated voices 12-14"
```

---

### Task 3: Update boundary checks in `orpheus_units.cpp`

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp:452-454` (bender CV)
- Modify: `liborpheus_dsp/src/orpheus_units.cpp:572-577` (ADSR bypass)
- Modify: `liborpheus_dsp/src/orpheus_units.cpp:648` (drum gate clear)
- Modify: `liborpheus_dsp/src/orpheus_units.cpp:654-664` (warps source routing)

**Step 1: Update bender CV boundary**

Line 452: Change `kNumMainVoices` to `kDrumVoiceStart`:

No change needed — `kNumMainVoices` is now 12, and bender only applies to main voices (0-11), which is the same as `kNumMainVoices`. The `voice_bend_cv` array is `kNumMainVoices`-sized, so voices 0-11 have bender slots. Drum voices (12-14) correctly have no bender — the `if (idx < kNumMainVoices)` check still works.

**Step 2: Update ADSR bypass boundary**

Line 577: The check `if (idx < kNumMainVoices)` already correctly means "main voices get ADSR." With `kNumMainVoices=12`, voices 0-11 get ADSR and voices 12-14 (drums) bypass it. This is correct.

**Step 3: Update drum gate clear boundary**

Line 648: Change from:
```cpp
if (idx >= kNumMainVoices && actual_gate) {
```
To:
```cpp
if (idx >= kDrumVoiceStart && actual_gate) {
```

This ensures only drum voices (12-14) auto-clear their gate, not REPL voices (8-11) which should sustain normally.

**Step 4: Update warps source routing**

Lines 654-665: Replace:
```cpp
if (idx < kNumMainVoices) {
    // SYNTH (source 0): accumulate main voices
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[0][i] += out[i] * (1.0f / kNumMainVoices);
    }
}
if (idx >= kNumMainVoices) {
    // REPL (source 2): accumulate REPL voices (8-11)
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[2][i] += out[i] * (1.0f / kNumReplVoices);
    }
}
```

With:
```cpp
if (idx < kDrumVoiceStart) {
    // SYNTH (source 0): accumulate main voices (0-11)
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[0][i] += out[i] * (1.0f / kNumMainVoices);
    }
}
if (idx >= kDrumVoiceStart) {
    // DRUMS (source 2): accumulate drum voices (12-14)
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[2][i] += out[i] * (1.0f / kNumDrumVoices);
    }
}
```

**Step 5: Search for any other `kNumReplVoices` references**

Run: `grep -rn kNumReplVoices liborpheus_dsp/`
All references should now be gone. If any remain, update them.

**Step 6: Build and run tests**

Run: `cmake --build . && ./orpheus_dsp_test`
Expected: Most tests pass. Drum tests may fail because ODWG graph still triggers voices 8-10 — that's fixed in Task 4.

**Step 7: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.cpp
git commit -m "refactor(dsp): Update voice boundary checks for 12+3 layout"
```

---

### Task 4: Update ODWG graph — dedicated drum voices + MAIN/FX routing

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt:18-263`

**Reference:** Kotlin JSyn routing in `DspWiringGraph.kt:wireDrums()` (lines 203-242) and `initDrumDirectResonator()` (lines 290-299). The C++ ODWG must replicate this topology exactly.

**Step 1: Add 3 drum plaits units after the 12 main voice loop (line 46)**

```kotlin
// ── Dedicated drum voices (3 slots) ──
// Per-drum volume defaults from DrumPlugin.SLOT_GAINS, center pan
val drumSlotGains = floatArrayOf(1.2f, 0.6f, 0.5f)
val drumPlaitsUnits = mutableListOf<UnitRef>()
val drumOutsL = mutableListOf<UnitRef>()
val drumOutsR = mutableListOf<UnitRef>()

for (d in 0 until 3) {
    val dp = plaits("d${d}_p") { moduleIndex = (12 + d).toFloat() }
    drumPlaitsUnits.add(dp)
    val dv = multiply("d${d}_vol") { inputB = drumSlotGains[d] }
    val (gl, gr) = panGains(0f)  // center pan
    val dL = multiply("d${d}_pL") { inputB = gl }
    val dR = multiply("d${d}_pR") { inputB = gr }

    dp.out to dv.inputA
    dv.out to dL.inputA
    dv.out to dR.inputA
    drumOutsL.add(dL)
    drumOutsR.add(dR)
}

// Drum sum (mono sum of all 3 drums, L+R channels)
val drumSumL = passThrough("drumSumL")
val drumSumR = passThrough("drumSumR")
for (d in drumOutsL) { d.out to drumSumL.input }
for (d in drumOutsR) { d.out to drumSumR.input }
```

Note: drum outputs do NOT feed into the main `voiceOutsL/R` summing tree. They have their own routing below.

**Step 2: Add drum MAIN/FX routing (after drum sum, before effects chain)**

This implements the two-path bypass toggle matching Kotlin's `DspSynthEngine.setDrumsBypass()`:
- **FX path** (`drumChainGain`): drums → main Rings resonator → drive → delay → output
- **MAIN path** (`drumDirectGain`): drums → dedicated Rings → wet/dry → limiter → master output

```kotlin
// ── Drum FX/MAIN routing toggle ──
// Default: MAIN mode (bypass=true → drumDirectGain=1, drumChainGain=0)
// Controlled at runtime via drumChainGain/drumDirectGain port map entries

// FX path: drum sum → gain gate → existing resonator excitation inputs
val drumChainGainL = multiply("drumChainGainL") { inputB = 0.0f }
val drumChainGainR = multiply("drumChainGainR") { inputB = 0.0f }
drumSumL.out to drumChainGainL.inputA
drumSumR.out to drumChainGainR.inputA

// MAIN path: drum sum → gain gate → dedicated resonator chain
val drumDirectGainL = multiply("drumDirectGainL") { inputB = 1.0f }
val drumDirectGainR = multiply("drumDirectGainR") { inputB = 1.0f }
drumSumL.out to drumDirectGainL.inputA
drumSumR.out to drumDirectGainR.inputA

// Dedicated drum resonator (Rings) — dry/wet mix, defaults: dry=1.0, wet=0.0
val drumDirectResoDryGainL = multiply("drumDirectResoDryGainL") { inputB = 1.0f }
val drumDirectResoDryGainR = multiply("drumDirectResoDryGainR") { inputB = 1.0f }
drumDirectGainL.out to drumDirectResoDryGainL.inputA
drumDirectGainR.out to drumDirectResoDryGainR.inputA

// Mix L+R to mono for drum Rings input
val drumResoMix = add("drumResoMix")
val drumResoHalf = multiply("drumResoHalf") { inputB = 0.5f }
drumDirectGainL.out to drumResoMix.inputA
drumDirectGainR.out to drumResoMix.inputB
drumResoMix.out to drumResoHalf.inputA

val drumReso = rings("drumResonator")
drumResoHalf.out to drumReso.input

val drumDirectResoWetGainL = multiply("drumDirectResoWetGainL") { inputB = 0.0f }
val drumDirectResoWetGainR = multiply("drumDirectResoWetGainR") { inputB = 0.0f }
drumReso.out to drumDirectResoWetGainL.inputA
drumReso.outRight to drumDirectResoWetGainR.inputA

// Sum dry + wet
val drumDirectResoSumL = add("drumDirectResoSumL")
val drumDirectResoSumR = add("drumDirectResoSumR")
drumDirectResoDryGainL.out to drumDirectResoSumL.inputA
drumDirectResoWetGainL.out to drumDirectResoSumL.inputB
drumDirectResoDryGainR.out to drumDirectResoSumR.inputA
drumDirectResoWetGainR.out to drumDirectResoSumR.inputB

// Limiter (drive=1.0 default, matching Kotlin initDrumDirectResonator)
val drumDirectLimiterL = limiter("drumDirectLimiterL") { driveAmount = 1.0f }
val drumDirectLimiterR = limiter("drumDirectLimiterR") { driveAmount = 1.0f }
drumDirectResoSumL.out to drumDirectLimiterL.input
drumDirectResoSumR.out to drumDirectLimiterR.input

// MAIN path output → master clip (direct to output, bypassing delay/reverb)
drumDirectLimiterL.out to clipL.input
drumDirectLimiterR.out to clipR.input
```

**Step 3: Wire FX path drums into existing resonator excitation**

The FX path feeds drums into the main Rings resonator (already built above). Add after the drumChainGain definitions:

```kotlin
// FX path: drumChainGain → main resonator drum excitation inputs
drumChainGainL.out to drumExGainL.inputA  // joins existing excitation sum
drumChainGainR.out to drumExGainR.inputA
```

Note: In the current graph, `grains.out` feeds `drumExGainL.inputA`. The FX path drums should REPLACE that connection or be summed. Check whether `drumExGainL.inputA` can accept multiple sources (passThrough supports multi-input, but multiply's inputA may not). If needed, add a passThrough to merge grains + drumChainGain before drumExGainL.

**Step 4: Rewire GRIDS triggers to drum units**

Replace lines 192-194:
```kotlin
gridsUnit.out to plaitsUnits[8].gate        // kick → voice 8
gridsUnit.outRight to plaitsUnits[9].gate   // snare → voice 9
gridsUnit.aux to plaitsUnits[10].gate       // hat → voice 10
```

With:
```kotlin
gridsUnit.out to drumPlaitsUnits[0].gate        // kick → drum voice 12
gridsUnit.outRight to drumPlaitsUnits[1].gate   // snare → drum voice 13
gridsUnit.aux to drumPlaitsUnits[2].gate        // hat → drum voice 14
```

**Step 5: Add port map entries**

In the `portMap { }` block, add:

```kotlin
// Per-drum volume
map("org.balch.orpheus.plugins.drum", "bd_vol", "d0_vol", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "sd_vol", "d1_vol", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "hh_vol", "d2_vol", IPORT_INPUT_B)
// Per-drum pan
for (d in 0 until 3) {
    map("org.balch.orpheus.plugins.drum", "drum_pan_L_$d", "d${d}_pL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.drum", "drum_pan_R_$d", "d${d}_pR", IPORT_INPUT_B)
}
// Drum FX/MAIN bypass toggle
map("org.balch.orpheus.plugins.drum", "drum_chain_gain_l", "drumChainGainL", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "drum_chain_gain_r", "drumChainGainR", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "drum_direct_gain_l", "drumDirectGainL", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "drum_direct_gain_r", "drumDirectGainR", IPORT_INPUT_B)
// Drum direct resonator wet/dry
map("org.balch.orpheus.plugins.drum", "drum_direct_reso_dry_l", "drumDirectResoDryGainL", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "drum_direct_reso_dry_r", "drumDirectResoDryGainR", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "drum_direct_reso_wet_l", "drumDirectResoWetGainL", IPORT_INPUT_B)
map("org.balch.orpheus.plugins.drum", "drum_direct_reso_wet_r", "drumDirectResoWetGainR", IPORT_INPUT_B)
// Drum direct limiter drive
map("org.balch.orpheus.plugins.distortion", "drum_drive", "drumDirectLimiterL", IPORT_DRIVE)
map("org.balch.orpheus.plugins.distortion", "drum_drive", "drumDirectLimiterR", IPORT_DRIVE)
```

**Step 6: Build the Kotlin module and regenerate ODWG**

Run: `./gradlew :core:dsp-engine:build`
Then rebuild the desktop native lib and C++ tests:
```bash
cd liborpheus_dsp/build_test
cmake --build . && ./orpheus_dsp_test
```

Expected: All existing tests pass with the new graph. Drum tests now trigger voices 12-14.

**Step 7: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt
git commit -m "feat(graph): Add dedicated drum voices with MAIN/FX routing and resonator"
```

---

### Task 5: Update test files for new voice layout

**Files:**
- Modify: `liborpheus_dsp/test/test_drums_graph.cpp`
- Modify: `liborpheus_dsp/test/test_headroom.cpp`
- Modify: `liborpheus_dsp/test/test_engine_render.cpp` (if mod source uses voice 8+)

**Step 1: Update drum test voice indices**

In `test_drums_graph.cpp`, update all references from voices 8-11 to 12-14:
- `test_drum_trigger`: drum voices are now 12-14, only 3 drums (remove drum 3 / bass_drum_alt)
- `test_drum_isolation`: voice 0 is still main; drum level checks now at `voice_levels[12..14]`
- `test_graph_drum_voice`: update voice indices to 12-14

**Step 2: Update headroom test**

In `test_headroom.cpp`, update `test_fullchain_headroom`:
- "8+4 drums" scenario becomes "12+3 drums"
- Trigger only 3 drums (not 4)
- Check `voice_levels[12..14]` for drum activity

**Step 3: Check mod source test**

In `test_engine_render.cpp`, `test_mod_source_routing` uses `activate_voice(engine, 1, 0, 67.0f)` for FM — voice 1 is still a main voice, no change needed.

**Step 4: Build and run**

Run: `cmake --build . && ./orpheus_dsp_test`
Expected: All tests pass.

**Step 5: Commit**

```bash
git add liborpheus_dsp/test/test_drums_graph.cpp liborpheus_dsp/test/test_headroom.cpp
git commit -m "test(dsp): Update drum tests for 12+3 voice layout"
```

---

### Task 6: Add quad volume test

**Files:**
- Modify: `liborpheus_dsp/test/test_headroom.cpp`

**Step 1: Write the test**

Add `test_quad_volume()`:

```cpp
static bool test_quad_volume() {
    printf("\n=== Test: Quad volume via set_port ===\n");
    bool pass = true;

    // Render with quad_vol_0 = 1.0 (default) — baseline
    OrpheusEngine* eng_full = orpheus_engine_create(48000.0f);
    load_production_graph(eng_full);
    for (int v = 0; v < 4; v++)
        activate_voice(eng_full, v, 8, 60.0f + v * 5.0f);
    auto r_full = render_engine(eng_full, 24000);
    float rms_full = (r_full.rms_l + r_full.rms_r) / 2.0f;
    orpheus_engine_destroy(eng_full);

    // Render with quad_vol_0 = 0.5 — should be ~half
    OrpheusEngine* eng_half = orpheus_engine_create(48000.0f);
    load_production_graph(eng_half);
    for (int v = 0; v < 4; v++)
        activate_voice(eng_half, v, 8, 60.0f + v * 5.0f);
    orpheus_engine_set_port(eng_half, "org.balch.orpheus.plugins.stereo", "quad_vol_0", 0.5f);
    auto r_half = render_engine(eng_half, 24000);
    float rms_half = (r_half.rms_l + r_half.rms_r) / 2.0f;
    orpheus_engine_destroy(eng_half);

    float ratio = rms_half / (rms_full + 0.0001f);
    printf("  quad_vol=1.0: RMS=%.4f  quad_vol=0.5: RMS=%.4f  ratio=%.2f\n",
           rms_full, rms_half, ratio);
    if (ratio < 0.35f || ratio > 0.65f) {
        printf("  FAIL: expected ratio ~0.5, got %.2f\n", ratio);
        pass = false;
    }

    // Render with quad_vol_0 = 0.0 — should be silent
    OrpheusEngine* eng_zero = orpheus_engine_create(48000.0f);
    load_production_graph(eng_zero);
    for (int v = 0; v < 4; v++)
        activate_voice(eng_zero, v, 8, 60.0f + v * 5.0f);
    orpheus_engine_set_port(eng_zero, "org.balch.orpheus.plugins.stereo", "quad_vol_0", 0.0f);
    auto r_zero = render_engine(eng_zero, 24000);
    float rms_zero = (r_zero.rms_l + r_zero.rms_r) / 2.0f;
    printf("  quad_vol=0.0: RMS=%.4f %s\n", rms_zero, rms_zero < 0.001f ? "OK (silent)" : "FAIL (not silent)");
    if (rms_zero > 0.001f) pass = false;
    orpheus_engine_destroy(eng_zero);

    // Cross-quad isolation: set quad_vol_0=0, quad_vol_1=1, play voices in both
    OrpheusEngine* eng_iso = orpheus_engine_create(48000.0f);
    load_production_graph(eng_iso);
    for (int v = 0; v < 8; v++)
        activate_voice(eng_iso, v, 8, 60.0f);
    orpheus_engine_set_port(eng_iso, "org.balch.orpheus.plugins.stereo", "quad_vol_0", 0.0f);
    // quad_vol_1 stays at default 1.0
    auto r_iso = render_engine(eng_iso, 24000);
    float rms_iso = (r_iso.rms_l + r_iso.rms_r) / 2.0f;
    printf("  quad_vol_0=0 + quad_vol_1=1: RMS=%.4f %s\n",
           rms_iso, rms_iso > 0.01f ? "OK (quad 1 audible)" : "FAIL (too quiet)");
    if (rms_iso < 0.01f) pass = false;
    orpheus_engine_destroy(eng_iso);

    printf("Quad volume test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}
```

**Step 2: Build and run**

Run: `cmake --build . && ./orpheus_dsp_test`
Expected: PASS

**Step 3: Commit**

```bash
git add liborpheus_dsp/test/test_headroom.cpp
git commit -m "test(dsp): Add quad volume scaling and cross-quad isolation tests"
```

---

### Task 7: Add quad hold test

**Files:**
- Modify: `liborpheus_dsp/test/test_headroom.cpp`

**Step 1: Write the test**

Add `test_quad_hold()`:

```cpp
static bool test_quad_hold() {
    printf("\n=== Test: Quad hold (sustained drone without gate) ===\n");
    bool pass = true;

    // Hold at 0.8 on all 4 voices of quad 0 — no gate
    OrpheusEngine* eng = orpheus_engine_create(48000.0f);
    load_production_graph(eng);
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(eng, v, 1);
        orpheus_engine_set_voice_engine(eng, v, 8);  // VA engine
        orpheus_engine_set_voice_hold(eng, v, 0.8f);
        // No gate — hold should sustain the voice
    }
    auto r_hold = render_engine(eng, 24000);
    float rms_hold = (r_hold.rms_l + r_hold.rms_r) / 2.0f;
    printf("  Hold=0.8 no gate: RMS=%.4f %s\n", rms_hold,
           rms_hold > 0.01f ? "OK" : "FAIL (silent)");
    if (rms_hold < 0.01f) pass = false;
    orpheus_engine_destroy(eng);

    // Hold level scales output: 0.5 vs 1.0
    float rms_levels[2] = {};
    float hold_vals[] = {0.5f, 1.0f};
    for (int h = 0; h < 2; h++) {
        OrpheusEngine* e = orpheus_engine_create(48000.0f);
        load_production_graph(e);
        for (int v = 0; v < 4; v++) {
            orpheus_engine_set_voice_active(e, v, 1);
            orpheus_engine_set_voice_engine(e, v, 8);
            orpheus_engine_set_voice_hold(e, v, hold_vals[h]);
        }
        auto r = render_engine(e, 24000);
        rms_levels[h] = (r.rms_l + r.rms_r) / 2.0f;
        printf("  Hold=%.1f: RMS=%.4f\n", hold_vals[h], rms_levels[h]);
        orpheus_engine_destroy(e);
    }
    float hold_ratio = rms_levels[0] / (rms_levels[1] + 0.0001f);
    printf("  hold 0.5/1.0 ratio: %.2f (expect ~0.5)\n", hold_ratio);
    if (hold_ratio < 0.3f || hold_ratio > 0.7f) {
        printf("  FAIL: hold ratio %.2f outside expected range\n", hold_ratio);
        pass = false;
    }

    // Hold=0 + no gate = silence
    OrpheusEngine* eng_silent = orpheus_engine_create(48000.0f);
    load_production_graph(eng_silent);
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(eng_silent, v, 1);
        orpheus_engine_set_voice_engine(eng_silent, v, 8);
        orpheus_engine_set_voice_hold(eng_silent, v, 0.0f);
    }
    auto r_silent = render_engine(eng_silent, 24000);
    float rms_silent = (r_silent.rms_l + r_silent.rms_r) / 2.0f;
    printf("  Hold=0 no gate: RMS=%.4f %s\n", rms_silent,
           rms_silent < 0.001f ? "OK (silent)" : "FAIL (not silent)");
    if (rms_silent > 0.001f) pass = false;
    orpheus_engine_destroy(eng_silent);

    printf("Quad hold test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}
```

**Step 2: Wire both new tests into `run_headroom_tests`**

```cpp
bool run_headroom_tests() {
    bool all_pass = true;
    all_pass &= test_engine_level_parity();
    all_pass &= test_fullchain_headroom();
    all_pass &= test_master_volume_linearity();
    all_pass &= test_quad_volume();
    all_pass &= test_quad_hold();
    return all_pass;
}
```

**Step 3: Build and run**

Run: `cmake --build . && ./orpheus_dsp_test`
Expected: PASS

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/test_headroom.cpp
git commit -m "test(dsp): Add quad hold drone and hold-level scaling tests"
```

---

### Task 8: Add drum voice isolation test

**Files:**
- Modify: `liborpheus_dsp/test/test_drums_graph.cpp`

**Step 1: Write the test**

Update or add `test_drum_slot_gains()`:

```cpp
static bool test_drum_slot_gains() {
    printf("\n=== Test: Drum slot gain balance (kick > snare > hat) ===\n");
    bool pass = true;

    // Trigger each drum individually and measure peak
    float peaks[3] = {};
    const char* names[] = {"kick", "snare", "hat"};

    for (int d = 0; d < 3; d++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        orpheus_engine_trigger_drum(engine, d, 0.8f);
        auto r = render_engine(engine, 24000);
        peaks[d] = r.peak;

        // Verify drum voice level at correct index
        float level = engine->voice_levels[12 + d].load();
        printf("  %s (v%d): peak=%.4f level=%.4f %s\n",
               names[d], 12 + d, peaks[d], level,
               level > 0.001f ? "OK" : "SILENT!");
        if (level < 0.001f) pass = false;

        // Verify main voices 8-11 are NOT affected
        for (int v = 8; v < 12; v++) {
            float main_level = engine->voice_levels[v].load();
            if (main_level > 0.001f) {
                printf("  FAIL: main voice %d has level=%.4f during drum trigger\n", v, main_level);
                pass = false;
            }
        }

        orpheus_engine_destroy(engine);
    }

    // Verify relative levels match SLOT_GAINS ordering: kick (1.2) > snare (0.6) > hat (0.5)
    if (peaks[0] < peaks[1]) {
        printf("  FAIL: kick (%.4f) should be louder than snare (%.4f)\n", peaks[0], peaks[1]);
        pass = false;
    }
    if (peaks[1] < peaks[2]) {
        printf("  WARNING: snare (%.4f) quieter than hat (%.4f) — engine-dependent\n", peaks[1], peaks[2]);
        // Not a hard fail — different drum engines have different inherent levels
    }

    printf("Drum slot gains test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}
```

**Step 2: Wire into `run_drums_graph_tests`**

Add `all_pass &= test_drum_slot_gains();` to the test runner.

**Step 3: Build and run**

Run: `cmake --build . && ./orpheus_dsp_test`
Expected: PASS

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/test_drums_graph.cpp
git commit -m "test(dsp): Add drum slot gain balance and voice isolation test"
```

---

### Task 9: Update full-chain headroom for 15 voices

**Files:**
- Modify: `liborpheus_dsp/test/test_headroom.cpp`

**Step 1: Update the 12+3 scenario**

In `test_fullchain_headroom`, change the "8+4 drums" block to "12+3 drums":

```cpp
// 15-voice scenario: 12 main voices + 3 drum triggers
{
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    load_production_graph(engine);
    orpheus_engine_set_master_volume(engine, 0.8f);

    // Activate all 12 main voices
    float notes12[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f,
                       50.0f, 57.0f, 62.0f, 69.0f};
    for (int v = 0; v < 12; v++) {
        activate_voice(engine, v, 8, notes12[v]);
        char sym[16];
        snprintf(sym, sizeof(sym), "voice_pan_%d", v);
        orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.stereo", sym, 0.0f);
    }

    // Trigger all 3 drum voices
    orpheus_engine_trigger_drum(engine, 0, 0.8f);
    orpheus_engine_trigger_drum(engine, 1, 0.8f);
    orpheus_engine_trigger_drum(engine, 2, 0.8f);

    auto r = render_engine(engine, 48000);
    float rms = (r.rms_l + r.rms_r) / 2.0f;
    float crest = (rms > 0.0001f) ? r.peak / rms : 0.0f;
    bool no_clip = r.peak <= 1.0f;
    bool has_signal = rms > 0.01f;
    bool ok = no_clip && has_signal;
    printf("  %-12s  %8.4f  %8.4f  %6.2f  %s\n",
           "12+3 drums", r.peak, rms, crest,
           ok ? "OK" : (no_clip ? "LOW SIGNAL" : "CLIPPING!"));
    if (!ok) pass = false;

    // Verify drum voices contributed
    int drums_active = 0;
    for (int v = 12; v < 15; v++) {
        float level = engine->voice_levels[v].load();
        if (level > 0.001f) drums_active++;
    }
    printf("  Drum voices active: %d/3\n", drums_active);

    orpheus_engine_destroy(engine);
}
```

**Step 2: Build and run**

Run: `cmake --build . && ./orpheus_dsp_test`
Expected: All tests pass, no clipping at 15 voices.

**Step 3: Commit**

```bash
git add liborpheus_dsp/test/test_headroom.cpp
git commit -m "test(dsp): Update headroom test for 15-voice (12+3) scenario"
```

---

### Task 10: Delete WAV reference snapshots and regenerate

After all structural changes, the WAV snapshots will have drifted due to the new summing tree (15 outputs instead of 12).

**Step 1: Delete old refs**

```bash
rm liborpheus_dsp/build_test/test/output/*.ref.wav
```

**Step 2: Run tests to regenerate baselines**

```bash
cd liborpheus_dsp/build_test
./orpheus_dsp_test
```

Expected: All `[NEW]` snapshots created, all tests PASS.

**Step 3: Final verification**

Run tests again:
```bash
./orpheus_dsp_test
```

Expected: All snapshots now `[MATCH]`, `SUCCESS: All tests passed.`

**Step 4: Commit**

```bash
git add liborpheus_dsp/
git commit -m "test(dsp): Regenerate WAV snapshot baselines for 15-voice layout"
```
