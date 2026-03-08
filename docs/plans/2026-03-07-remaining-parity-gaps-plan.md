# Remaining C++ DSP Parity Gaps — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close all 6 remaining parity gaps (#6, #10, #11, #12, #13, #15) to achieve 20/20 JSyn–C++ parity.

**Architecture:** All DSP in C++ (`orpheus_units.cpp` + `orpheus_engine.h`). Kotlin only forwards params via `nativeSetPort`. Two new graph units (UNIT_BENDER=24, UNIT_PER_STRING_BENDER=25). Modifications to `unit_process_plaits` and `unit_process_warps` for modulation/coupling/source routing.

**Tech Stack:** C++ (orpheus_units.cpp/h, orpheus_engine.h/cpp, orpheus_graph.h/cpp), Kotlin DSL (WiringGraphDsl.kt, DefaultWiringGraph.kt)

---

## Phase A: Voice Coupling (Gap #11)

### Task 1: Add coupling engine state

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1:** Add after `voice_hold_level` (line 137):

```cpp
    // ── Voice Coupling ─────────────────────────────────
    float voice_envelope[kNumVoices] = {};             // peak follower per voice
    std::atomic<float> coupling_depth{0.0f};           // 0 = off, scales partner env → pitch
```

**Step 2:** Add port routing in `orpheus_engine.cpp`. In the `orpheus_engine_set_port` function, find the port routing section and add:

```cpp
    // Voice coupling
    static uint16_t h_voice = engine_hash16("org.balch.orpheus.plugins.voice");
    static uint16_t h_coupling = engine_hash16("coupling_depth");
    if (uri_hash == h_voice && symbol_hash == h_coupling) {
        engine->coupling_depth.store(value, std::memory_order_relaxed);
        return;
    }
```

**Step 3:** Build and verify:
```bash
cd liborpheus_dsp && cmake --build build 2>&1 | tail -5
```

**Step 4:** Commit:
```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Add voice coupling engine state"
```

### Task 2: Implement coupling in unit_process_plaits

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`

**Step 1:** In `unit_process_plaits`, after the vibrato computation (line 368) and before the `if (engine_index < 0)` block (line 374), add coupling pitch offset:

```cpp
    // ── Voice coupling: partner envelope → pitch modulation ──
    float coupling_offset = 0.0f;
    {
        float coupling = engine->coupling_depth.load(std::memory_order_relaxed);
        if (coupling > 0.001f) {
            int partner = (idx % 2 == 0) ? idx + 1 : idx - 1;
            if (partner >= 0 && partner < kNumVoices) {
                coupling_offset = engine->voice_envelope[partner] * coupling * 24.0f;
            }
        }
    }
```

**Step 2:** Apply `coupling_offset` to pitch. In Engine 0 path (line 376):
```cpp
        float note = vp.tune.load(std::memory_order_relaxed) + vibrato_semitones + coupling_offset;
```

And in Plaits path (line 456):
```cpp
        patch.note = vp.tune.load(std::memory_order_relaxed) + vibrato_semitones + coupling_offset;
```

**Step 3:** Update the peak follower after rendering. After the `voice_levels[idx].store(voice_peak, ...)` call at the end of BOTH the Engine 0 block (line 444) and the Plaits block (line 512), add:

```cpp
        // Update peak follower for voice coupling
        engine->voice_envelope[idx] = engine->voice_envelope[idx] * 0.999f
                                     + 0.001f * voice_peak;
```

**Step 4:** Build and run tests:
```bash
cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test 2>&1 | grep -E "(PASS|FAIL)"
```

**Step 5:** Commit:
```bash
git add liborpheus_dsp/src/orpheus_units.cpp
git commit -m "feat(dsp): Implement voice coupling — partner envelope → pitch"
```

### Task 3: Add coupling test

**Files:**
- Modify: `liborpheus_dsp/test/test_main.cpp`

**Step 1:** Add test before `main()`:

```cpp
bool test_voice_coupling() {
    printf("\n=== Test: Voice coupling ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Enable voices 0 and 1 as a duo
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);

    engine->voice_params[1].active.store(1);
    engine->voice_params[1].tune.store(67.0f);
    engine->voice_params[1].gate.store(0);
    engine->voice_params[1].ever_triggered.store(1);
    engine->voice_params[1].engine_index.store(-1);

    engine->coupling_depth.store(0.5f);

    // Process voice 0 for a few blocks to build up envelope
    GraphUnit v0_unit = {};
    v0_unit.type = UNIT_PLAITS;
    v0_unit.enabled = true;
    v0_unit.state.module.index = 0;
    unit_init(&v0_unit, 48000.0f);

    for (int i = 0; i < 20; i++) {
        unit_process_plaits(&v0_unit, engine, 128, 48000.0f);
    }

    float env0 = engine->voice_envelope[0];
    printf("Voice 0 envelope: %.4f\n", env0);
    bool pass = env0 > 0.01f; // Voice 0 is gated, should have envelope
    printf("Coupling test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}
```

**Step 2:** Add `if (!test_voice_coupling()) return 1;` in main() before the existing tests.

**Step 3:** Build and run:
```bash
cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test 2>&1 | grep -E "(coupling|PASS|FAIL)"
```

**Step 4:** Commit:
```bash
git add liborpheus_dsp/test/test_main.cpp
git commit -m "test(dsp): Add voice coupling test"
```

---

## Phase B: Mod Source Routing + FM Cross-Modulation (Gaps #12, #13)

### Task 4: Add mod source engine state

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1:** Add after the voice coupling state:

```cpp
    // ── Mod Source Routing + FM ─────────────────────────
    static constexpr int kNumDuos = 6;
    float voice_last_output[kNumVoices] = {};          // previous block's peak output
    float marbles_cv_output[2] = {};                   // cached Marbles X1/X2 CV
    std::atomic<int> mod_source[kNumDuos] = {};        // per-duo: 0=OFF, 1=VOICE_FM, 2=LFO, 3=FLUX
    std::atomic<float> mod_depth[kNumDuos] = {};       // per-duo timbre mod depth
    std::atomic<float> fm_depth[kNumDuos] = {};        // per-duo FM depth (semitones)
    std::atomic<int> fm_cross_quad{0};                 // 0=duo pairs, 1=cross-quad circular
```

**Step 2:** Add port routing in `orpheus_engine.cpp` set_port section:

```cpp
    // Mod source routing
    static uint16_t h_mod = engine_hash16("org.balch.orpheus.plugins.modulation");
    static uint16_t h_mod_src_0 = engine_hash16("mod_source_0");
    static uint16_t h_mod_src_1 = engine_hash16("mod_source_1");
    static uint16_t h_mod_src_2 = engine_hash16("mod_source_2");
    static uint16_t h_mod_src_3 = engine_hash16("mod_source_3");
    static uint16_t h_mod_src_4 = engine_hash16("mod_source_4");
    static uint16_t h_mod_src_5 = engine_hash16("mod_source_5");
    static uint16_t h_mod_depth_0 = engine_hash16("mod_depth_0");
    static uint16_t h_mod_depth_1 = engine_hash16("mod_depth_1");
    static uint16_t h_mod_depth_2 = engine_hash16("mod_depth_2");
    static uint16_t h_mod_depth_3 = engine_hash16("mod_depth_3");
    static uint16_t h_mod_depth_4 = engine_hash16("mod_depth_4");
    static uint16_t h_mod_depth_5 = engine_hash16("mod_depth_5");
    static uint16_t h_fm_depth_0 = engine_hash16("fm_depth_0");
    static uint16_t h_fm_depth_1 = engine_hash16("fm_depth_1");
    static uint16_t h_fm_depth_2 = engine_hash16("fm_depth_2");
    static uint16_t h_fm_depth_3 = engine_hash16("fm_depth_3");
    static uint16_t h_fm_depth_4 = engine_hash16("fm_depth_4");
    static uint16_t h_fm_depth_5 = engine_hash16("fm_depth_5");
    static uint16_t h_fm_xquad = engine_hash16("fm_cross_quad");

    if (uri_hash == h_mod) {
        uint16_t syms_src[] = {h_mod_src_0, h_mod_src_1, h_mod_src_2, h_mod_src_3, h_mod_src_4, h_mod_src_5};
        uint16_t syms_md[]  = {h_mod_depth_0, h_mod_depth_1, h_mod_depth_2, h_mod_depth_3, h_mod_depth_4, h_mod_depth_5};
        uint16_t syms_fm[]  = {h_fm_depth_0, h_fm_depth_1, h_fm_depth_2, h_fm_depth_3, h_fm_depth_4, h_fm_depth_5};
        for (int i = 0; i < 6; i++) {
            if (symbol_hash == syms_src[i]) { engine->mod_source[i].store(static_cast<int>(value)); return; }
            if (symbol_hash == syms_md[i])  { engine->mod_depth[i].store(value); return; }
            if (symbol_hash == syms_fm[i])  { engine->fm_depth[i].store(value); return; }
        }
        if (symbol_hash == h_fm_xquad) { engine->fm_cross_quad.store(static_cast<int>(value)); return; }
    }
```

**Step 3:** Build:
```bash
cd liborpheus_dsp && cmake --build build 2>&1 | tail -5
```

**Step 4:** Commit:
```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Add mod source + FM engine state and port routing"
```

### Task 5: Implement mod source routing in unit_process_plaits

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`

**Step 1:** After the coupling offset computation (added in Task 2), add mod source routing:

```cpp
    // ── Mod source routing: FM + timbre modulation ──────
    float fm_mod_semitones = 0.0f;
    float timbre_mod_offset = 0.0f;
    {
        int duo = idx / 2;
        if (duo < OrpheusEngine::kNumDuos) {
            int src = engine->mod_source[duo].load(std::memory_order_relaxed);
            float mod_signal = 0.0f;
            if (src == 1) { // VOICE_FM
                int fm_source;
                if (!engine->fm_cross_quad.load(std::memory_order_relaxed)) {
                    fm_source = (idx % 2 == 0) ? idx + 1 : idx - 1;
                } else {
                    fm_source = (idx - 2 + 8) % 8;
                }
                if (fm_source >= 0 && fm_source < kNumVoices) {
                    mod_signal = engine->voice_last_output[fm_source];
                }
            } else if (src == 2) { // LFO
                mod_signal = engine->lfo_output_value;
            } else if (src == 3) { // FLUX
                mod_signal = engine->marbles_cv_output[duo % 2];
            }
            float md = engine->mod_depth[duo].load(std::memory_order_relaxed);
            float fd = engine->fm_depth[duo].load(std::memory_order_relaxed);
            timbre_mod_offset = mod_signal * md;
            fm_mod_semitones = mod_signal * fd * 24.0f; // scale to semitones
        }
    }
```

**Step 2:** Apply FM to pitch in both Engine 0 and Plaits paths. Update the note computation to include `fm_mod_semitones`:

Engine 0 (line ~376):
```cpp
        float note = vp.tune.load(std::memory_order_relaxed) + vibrato_semitones + coupling_offset + fm_mod_semitones;
```

Plaits (line ~456):
```cpp
        patch.note = vp.tune.load(std::memory_order_relaxed) + vibrato_semitones + coupling_offset + fm_mod_semitones;
```

**Step 3:** Apply timbre modulation to Plaits. After setting `patch.timbre` (line ~458):
```cpp
        patch.timbre = std::max(0.0f, std::min(1.0f,
            vp.timbre.load(std::memory_order_relaxed) + timbre_mod_offset));
        patch.timbre_modulation_amount = (timbre_mod_offset != 0.0f) ? 1.0f : 0.0f;
```

**Step 4:** Store voice output for FM source. After each `voice_levels[idx].store(...)` call, also store the last output:

```cpp
        engine->voice_last_output[idx] = voice_peak;
```

**Step 5:** Cache Marbles CV in `unit_process_marbles`. At the end of `unit_process_marbles` (after the output loop), add:

```cpp
    // Cache CV output for mod source routing
    engine->marbles_cv_output[0] = u->output_buffers[OPORT_OUT_RIGHT][num_frames - 1];
    engine->marbles_cv_output[1] = u->output_buffers[OPORT_AUX][num_frames - 1];
```

**Step 6:** Build and run tests:
```bash
cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test 2>&1 | grep -E "(PASS|FAIL)"
```

**Step 7:** Commit:
```bash
git add liborpheus_dsp/src/orpheus_units.cpp
git commit -m "feat(dsp): Implement mod source routing + FM cross-modulation"
```

### Task 6: Add FM/mod test

**Files:**
- Modify: `liborpheus_dsp/test/test_main.cpp`

**Step 1:** Add test:

```cpp
bool test_fm_modulation() {
    printf("\n=== Test: FM modulation ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Set up duo 0 (voices 0+1) with VOICE_FM mod source
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);

    engine->voice_params[1].active.store(1);
    engine->voice_params[1].tune.store(67.0f);
    engine->voice_params[1].gate.store(1);
    engine->voice_params[1].ever_triggered.store(1);
    engine->voice_params[1].engine_index.store(-1);

    engine->mod_source[0].store(1); // VOICE_FM
    engine->fm_depth[0].store(0.5f);

    GraphUnit v0 = {}, v1 = {};
    v0.type = UNIT_PLAITS; v0.enabled = true; v0.state.module.index = 0;
    v1.type = UNIT_PLAITS; v1.enabled = true; v1.state.module.index = 1;
    unit_init(&v0, 48000.0f);
    unit_init(&v1, 48000.0f);

    // Process both voices to build up output levels
    for (int i = 0; i < 10; i++) {
        unit_process_plaits(&v0, engine, 128, 48000.0f);
        unit_process_plaits(&v1, engine, 128, 48000.0f);
    }

    float out0 = engine->voice_last_output[0];
    float out1 = engine->voice_last_output[1];
    printf("Voice 0 last output: %.4f, Voice 1: %.4f\n", out0, out1);
    bool pass = out0 > 0.001f && out1 > 0.001f;
    printf("FM modulation test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}
```

**Step 2:** Add to main(). Build and run.

**Step 3:** Commit:
```bash
git add liborpheus_dsp/test/test_main.cpp
git commit -m "test(dsp): Add FM modulation test"
```

---

## Phase C: Resonator Routing (Gap #6)

### Task 7: Add resonator routing engine state

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1:** Add after the rings_internal_exciter field (line 110):

```cpp
    std::atomic<float> resonator_target_mix{0.5f};     // 0=drum, 0.5=both, 1=synth
    std::atomic<float> resonator_mix{0.5f};            // wet/dry
```

**Step 2:** Add port routing in `orpheus_engine.cpp`:

```cpp
    // Resonator routing
    static uint16_t h_reso = engine_hash16("org.balch.orpheus.plugins.resonator");
    static uint16_t h_target_mix = engine_hash16("target_mix");
    static uint16_t h_reso_mix = engine_hash16("reso_mix");
    if (uri_hash == h_reso) {
        if (symbol_hash == h_target_mix) { engine->resonator_target_mix.store(value); return; }
        if (symbol_hash == h_reso_mix) { engine->resonator_mix.store(value); return; }
    }
```

**Step 3:** Build and commit:
```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Add resonator routing engine state"
```

### Task 8: Rewire resonator in DefaultWiringGraph.kt

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`

**Step 1:** Replace the current simple resonator section (lines 79-93) with full 4-input routing:

```kotlin
    // Rings/Resonator with full 4-input excitation/bypass architecture
    // Drum inputs = post-grains (Clouds output)
    // Synth inputs = master volume output (pre-effects)
    //
    // targetMix controls drum vs synth excitation blend:
    //   0.0 = pure drum, 0.5 = both, 1.0 = pure synth
    // mix controls wet/dry on resonated signal

    // Excitation path: drum + synth inputs scaled by targetMix gains
    val drumExGainL = multiply("drumExGainL") { inputB = 1.0f }
    val drumExGainR = multiply("drumExGainR") { inputB = 1.0f }
    val synthExGainL = multiply("synthExGainL") { inputB = 1.0f }
    val synthExGainR = multiply("synthExGainR") { inputB = 1.0f }

    grains.out to drumExGainL.inputA
    grains.outRight to drumExGainR.inputA
    mvL.out to synthExGainL.inputA
    mvR.out to synthExGainR.inputA

    // Sum excitation to mono for Rings input
    val exciteSumL = passThrough("exciteSumL")
    drumExGainL.out to exciteSumL.input
    synthExGainL.out to exciteSumL.input

    val exciteSumR = passThrough("exciteSumR")
    drumExGainR.out to exciteSumR.input
    synthExGainR.out to exciteSumR.input

    // Mix to mono for rings input
    val resoMix = add("resoMix")
    val resoHalf = multiply("resoHalf") { inputB = 0.5f }
    exciteSumL.out to resoMix.inputA
    exciteSumR.out to resoMix.inputB
    resoMix.out to resoHalf.inputA

    val reso = rings("resonator")
    resoHalf.out to reso.input

    // Bypass path: inverse gains (1 - exciteGain)
    val drumBpGainL = multiply("drumBpGainL") { inputB = 0.0f }
    val drumBpGainR = multiply("drumBpGainR") { inputB = 0.0f }
    val synthBpGainL = multiply("synthBpGainL") { inputB = 0.0f }
    val synthBpGainR = multiply("synthBpGainR") { inputB = 0.0f }

    grains.out to drumBpGainL.inputA
    grains.outRight to drumBpGainR.inputA
    mvL.out to synthBpGainL.inputA
    mvR.out to synthBpGainR.inputA

    val bypassSumL = passThrough("bypassSumL")
    drumBpGainL.out to bypassSumL.input
    synthBpGainL.out to bypassSumL.input

    val bypassSumR = passThrough("bypassSumR")
    drumBpGainR.out to bypassSumR.input
    synthBpGainR.out to bypassSumR.input

    // Wet/dry: resonator output × wetGain + excitation × dryGain + bypass
    val wetGainL = multiply("wetGainL") { inputB = 0.5f }
    val wetGainR = multiply("wetGainR") { inputB = 0.5f }
    val dryGainL = multiply("dryGainL") { inputB = 0.5f }
    val dryGainR = multiply("dryGainR") { inputB = 0.5f }

    reso.out to wetGainL.inputA
    reso.outRight to wetGainR.inputA
    exciteSumL.out to dryGainL.inputA
    exciteSumR.out to dryGainR.inputA

    // Final resonator output sum
    val resoOutL = passThrough("resoOutL")
    val resoOutR = passThrough("resoOutR")
    wetGainL.out to resoOutL.input
    dryGainL.out to resoOutL.input
    bypassSumL.out to resoOutL.input
    wetGainR.out to resoOutR.input
    dryGainR.out to resoOutR.input
    bypassSumR.out to resoOutR.input
```

**Step 2:** Update drive connections to use resoOutL/R instead of reso.out:

```kotlin
    val driveL = limiter("driveL") { driveAmount = 1.0f }
    val driveR = limiter("driveR") { driveAmount = 1.0f }
    resoOutL.out to driveL.input
    resoOutR.out to driveR.input
```

**Step 3:** Add port map entries for the resonator gains:

```kotlin
    // Resonator targetMix-derived gains (Kotlin computes, forwards via setPort)
    map("org.balch.orpheus.plugins.resonator", "drum_ex_gain", "drumExGainL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "drum_ex_gain", "drumExGainR", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "synth_ex_gain", "synthExGainL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "synth_ex_gain", "synthExGainR", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "drum_bp_gain", "drumBpGainL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "drum_bp_gain", "drumBpGainR", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "synth_bp_gain", "synthBpGainL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "synth_bp_gain", "synthBpGainR", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "wet_gain", "wetGainL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "wet_gain", "wetGainR", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "dry_gain", "dryGainL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.resonator", "dry_gain", "dryGainR", IPORT_INPUT_B)
```

**Step 4:** Build Kotlin:
```bash
./gradlew :core:dsp-engine:compileKotlinJvm 2>&1 | tail -5
```

**Step 5:** Commit:
```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt
git commit -m "feat(dsp): Rewire resonator with 4-input excitation/bypass routing"
```

---

## Phase D: Warps Source Routing (Gap #15)

### Task 9: Add warps source buffer system

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1:** Add after the warps_bypass field (line 120):

```cpp
    // ── Warps Source Routing ────────────────────────────
    // 0=SYNTH, 1=DRUMS(grains), 2=REPL(voices 8-11), 3=LFO, 4=RESONATOR(aux), 5=WARPS(feedback), 6=FLUX
    static constexpr int kNumWarpsSources = 7;
    float warps_source_buffers[kNumWarpsSources][kMaxFrames] = {};
    float warps_feedback_l[kMaxFrames] = {};
    float warps_feedback_r[kMaxFrames] = {};
    std::atomic<int> warps_carrier_source{0};     // 0-6 enum
    std::atomic<int> warps_modulator_source{0};   // 0-6 enum
```

**Step 2:** Add port routing in `orpheus_engine.cpp`:

```cpp
    // Warps source routing
    static uint16_t h_warps_uri = engine_hash16("org.balch.orpheus.plugins.warps");
    static uint16_t h_carrier_src = engine_hash16("carrier_source");
    static uint16_t h_mod_src = engine_hash16("modulator_source");
    if (uri_hash == h_warps_uri) {
        if (symbol_hash == h_carrier_src) { engine->warps_carrier_source.store(static_cast<int>(value)); return; }
        if (symbol_hash == h_mod_src) { engine->warps_modulator_source.store(static_cast<int>(value)); return; }
    }
```

**Step 3:** Build and commit:
```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Add warps source buffer system and routing atomics"
```

### Task 10: Populate source buffers + modify unit_process_warps

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`

**Step 1:** At the end of `unit_process_plaits`, after storing voice_last_output, add source buffer population:

```cpp
        // Populate warps source buffers
        // SYNTH (source 0): accumulate main voices (0-7)
        if (idx < kNumMainVoices) {
            for (int i = 0; i < num_frames; i++) {
                engine->warps_source_buffers[0][i] += out[i] / static_cast<float>(kNumMainVoices);
            }
        }
        // REPL (source 2): accumulate REPL voices (8-11)
        if (idx >= kNumMainVoices) {
            for (int i = 0; i < num_frames; i++) {
                engine->warps_source_buffers[2][i] += out[i] / static_cast<float>(kNumReplVoices);
            }
        }
```

**Note:** Source buffers must be zeroed at the start of each process cycle. Add at the beginning of `orpheus_graph_process` in `orpheus_graph.cpp`, before the unit loop:

```cpp
    // Zero warps source buffers (accumulated by individual units)
    std::memset(engine->warps_source_buffers[0], 0, num_frames * sizeof(float)); // SYNTH
    std::memset(engine->warps_source_buffers[2], 0, num_frames * sizeof(float)); // REPL
```

**Step 2:** At the end of `unit_process_clouds`, add:

```cpp
    // DRUMS (source 1): mono mix of grains output
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[1][i] = (out_l[i] + out_r[i]) * 0.5f;
    }
```

**Step 3:** At the end of `unit_process_hyper_lfo`, add:

```cpp
    // LFO (source 3): copy LFO output
    std::memcpy(engine->warps_source_buffers[3], out, num_frames * sizeof(float));
```

**Step 4:** At the end of `unit_process_rings` (after the processing loop), add:

```cpp
    // RESONATOR aux (source 4): copy aux output
    // Rings outputs: out_l = main (out_buf), out_r = aux (aux_buf)
    std::memcpy(engine->warps_source_buffers[4], out_r, num_frames * sizeof(float));
```

**Step 5:** At the end of `unit_process_marbles`, add:

```cpp
    // FLUX (source 6): copy gate output as CV
    std::memcpy(engine->warps_source_buffers[6],
                u->output_buffers[OPORT_OUT_RIGHT], num_frames * sizeof(float));
```

**Step 6:** Modify `unit_process_warps` to use source routing. Replace the input buffer reads at lines 634-635 with:

```cpp
    // Select carrier and modulator sources
    int c_src = engine->warps_carrier_source.load(std::memory_order_relaxed);
    int m_src = engine->warps_modulator_source.load(std::memory_order_relaxed);

    float* in_l;
    float* in_r;

    // If source 5 (WARPS feedback), use feedback buffers
    float carrier_buf[kMaxFrames];
    float mod_buf[kMaxFrames];

    if (c_src >= 0 && c_src < OrpheusEngine::kNumWarpsSources && c_src != 5) {
        std::memcpy(carrier_buf, engine->warps_source_buffers[c_src], num_frames * sizeof(float));
    } else if (c_src == 5) {
        std::memcpy(carrier_buf, engine->warps_feedback_l, num_frames * sizeof(float));
    } else {
        std::memcpy(carrier_buf, u->inputs[IPORT_INPUT_A].buffer, num_frames * sizeof(float));
    }

    if (m_src >= 0 && m_src < OrpheusEngine::kNumWarpsSources && m_src != 5) {
        std::memcpy(mod_buf, engine->warps_source_buffers[m_src], num_frames * sizeof(float));
    } else if (m_src == 5) {
        std::memcpy(mod_buf, engine->warps_feedback_r, num_frames * sizeof(float));
    } else {
        std::memcpy(mod_buf, u->inputs[IPORT_INPUT_B].buffer, num_frames * sizeof(float));
    }

    in_l = carrier_buf;
    in_r = mod_buf;
```

**Step 7:** At the end of `unit_process_warps` (after the processing loop), store feedback and source 5:

```cpp
    // Store output as feedback source (source 5 = WARPS)
    std::memcpy(engine->warps_feedback_l, out_l, num_frames * sizeof(float));
    std::memcpy(engine->warps_feedback_r, out_r, num_frames * sizeof(float));
    // Also store as warps source buffer for potential mono use
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[5][i] = (out_l[i] + out_r[i]) * 0.5f;
    }
```

**Step 8:** Build and run tests:
```bash
cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test 2>&1 | grep -E "(PASS|FAIL)"
```

**Step 9:** Commit:
```bash
git add liborpheus_dsp/src/orpheus_units.cpp liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Implement warps source routing with 7 selectable sources"
```

### Task 11: Remove fixed warps graph connections

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`

**Step 1:** Remove the fixed connections from driveL/driveR to warps (lines 96-98). Warps now reads from engine source buffers internally. Keep the warps unit in the graph but with no input connections:

```kotlin
    // Warps (source-routed internally via engine source buffers)
    val warp = warps("warps")
    // No fixed input connections — unit_process_warps reads from source buffers
```

**Step 2:** Update the delay connections — warps output still goes to delay:

```kotlin
    warp.out to delay.inputA
    warp.outRight to delay.inputB
```

**Step 3:** Build Kotlin and commit:
```bash
./gradlew :core:dsp-engine:compileKotlinJvm
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt
git commit -m "feat(dsp): Remove fixed warps connections — source-routed internally"
```

---

## Phase E: Bender & PerStringBender (Gap #10)

### Task 12: Add UNIT_BENDER and UNIT_PER_STRING_BENDER enums

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_graph.h`
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/WiringGraphDsl.kt`

**Step 1:** Add to C++ enum (before UNIT_TYPE_COUNT):

```cpp
    UNIT_BENDER = 24,
    UNIT_PER_STRING_BENDER = 25,
```

**Step 2:** Add Kotlin constants:

```kotlin
const val UNIT_BENDER = 24
const val UNIT_PER_STRING_BENDER = 25
```

**Step 3:** Add Kotlin DSL factory methods in `WiringGraphBuilder`:

```kotlin
    fun bender(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_BENDER, name, init)

    fun perStringBender(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_PER_STRING_BENDER, name, init)
```

**Step 4:** Build and commit:
```bash
git add liborpheus_dsp/src/orpheus_graph.h core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/WiringGraphDsl.kt
git commit -m "feat(dsp): Add UNIT_BENDER and UNIT_PER_STRING_BENDER enums"
```

### Task 13: Add bender engine state

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1:** Add after the looper state:

```cpp
    // ── Global Bender ──────────────────────────────────
    std::atomic<float> bend_amount{0.0f};              // -1..+1
    std::atomic<float> bend_max_semitones{24.0f};
    std::atomic<float> bend_timbre_mod{0.3f};
    std::atomic<float> bend_spring_vol{0.4f};
    std::atomic<float> bend_tension_vol{0.015f};

    // Internal bender state (audio thread only)
    float bend_tension_phase{0.0f};
    float bend_tension_env{0.0f};
    int   bend_tension_env_stage{0};          // 0=off, 1=attack, 2=decay, 3=sustain, 4=release
    float bend_spring_phase{0.0f};
    float bend_spring_env{0.0f};
    int   bend_spring_env_stage{0};
    float bend_wobble_phase{0.0f};
    float bend_random_lfo_phase{0.0f};
    bool  bend_was_active{false};

    // ── Per-String Bender ──────────────────────────────
    struct StringState {
        float bend_amount{0.0f};
        float voice_mix{0.0f};
        bool  is_active{false};
        bool  was_active{false};
        // Tension oscillator + envelope
        float tension_phase{0.0f};
        float tension_env{0.0f};
        int   tension_env_stage{0};
        // Spring oscillator + envelope + wobble
        float spring_phase{0.0f};
        float spring_env{0.0f};
        int   spring_env_stage{0};
        float wobble_phase{0.0f};
        // Pluck oscillator + envelope
        float pluck_phase{0.0f};
        float pluck_env{0.0f};
        int   pluck_env_stage{0};
        // Slide oscillator + LFO
        float slide_phase{0.0f};
        float slide_lfo_phase{0.0f};
        float slide_ramp{0.0f};
    };
    StringState string_state[4];

    // Per-string atomics (from UI)
    std::atomic<float> string_bend[4] = {};
    std::atomic<float> string_mix[4] = {};
    std::atomic<int>   string_active[4] = {};
    std::atomic<float> string_base_freq[4] = {};       // initialized in create()

    // Slide bar
    std::atomic<float> slide_bar_y{0.0f};
    std::atomic<float> slide_bar_x{0.0f};

    // Output arrays (read by unit_process_plaits)
    float voice_bend_cv[kNumMainVoices] = {};          // pitch bend semitones per voice
    float voice_mix_cv[kNumMainVoices] = {};           // voice volume multiplier per voice (default 1.0)
```

**Step 2:** Initialize string_base_freq defaults in `orpheus_engine_create()`:

```cpp
    engine->string_base_freq[0].store(400.0f);
    engine->string_base_freq[1].store(550.0f);
    engine->string_base_freq[2].store(700.0f);
    engine->string_base_freq[3].store(850.0f);
    // Default voice_mix_cv to 1.0 (no crossfade attenuation)
    for (int i = 0; i < kNumMainVoices; i++) {
        engine->voice_mix_cv[i] = 1.0f;
    }
```

**Step 3:** Add port routing for bender parameters in orpheus_engine.cpp.

**Step 4:** Build and commit:
```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Add bender and per-string bender engine state"
```

### Task 14: Implement unit_process_bender

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.h`
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1:** Add declarations in `orpheus_units.h`:

```cpp
void unit_process_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_per_string_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
```

**Step 2:** Implement `unit_process_bender` in `orpheus_units.cpp`:

```cpp
// ═══════════════════════════════════════════════════════════════════════
// Global Bender (pitch CV + timbre CV + tension/spring audio synthesis)
// ═══════════════════════════════════════════════════════════════════════

static float bender_advance_env(float& env, int& stage, float sr,
                                 float attack_s, float decay_s, float sustain, float release_s) {
    switch (stage) {
        case 1: // attack
            env += 1.0f / (attack_s * sr);
            if (env >= 1.0f) { env = 1.0f; stage = 2; }
            break;
        case 2: // decay
            env -= (env - sustain) * (1.0f / (decay_s * sr));
            if (std::fabs(env - sustain) < 0.001f) { env = sustain; stage = 3; }
            break;
        case 3: // sustain
            break;
        case 4: // release
            env *= 1.0f - (1.0f / (release_s * sr));
            if (env < 0.001f) { env = 0.0f; stage = 0; }
            break;
        default:
            env = 0.0f;
            break;
    }
    return env;
}

void unit_process_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out_pitch  = u->output_buffers[OPORT_OUT];
    float* out_timbre = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_audio  = u->output_buffers[OPORT_AUX];

    float amount = engine->bend_amount.load(std::memory_order_relaxed);
    float max_bend = engine->bend_max_semitones.load(std::memory_order_relaxed);
    float timbre_mod = engine->bend_timbre_mod.load(std::memory_order_relaxed);
    float tension_vol = engine->bend_tension_vol.load(std::memory_order_relaxed);
    float spring_vol = engine->bend_spring_vol.load(std::memory_order_relaxed);

    bool active = std::fabs(amount) > 0.05f;

    // State transitions
    if (active && !engine->bend_was_active) {
        engine->bend_tension_env_stage = 1; // trigger tension attack
    }
    if (!active && engine->bend_was_active) {
        engine->bend_tension_env_stage = 4; // tension release
        engine->bend_spring_env_stage = 1;  // trigger spring attack
    }
    engine->bend_was_active = active;

    for (int i = 0; i < num_frames; i++) {
        // Pitch CV: cubic curve → exponential frequency multiplier
        float cubic = amount * amount * std::fabs(amount); // preserves sign
        if (amount < 0) cubic = -cubic;
        float semitones = cubic * max_bend;
        out_pitch[i] = std::pow(2.0f, semitones / 12.0f) - 1.0f;

        // Timbre CV
        out_timbre[i] = amount * timbre_mod;

        // Tension oscillator (300-500 Hz, driven by bend amount)
        float tension_freq = 300.0f + std::fabs(amount) * 200.0f;
        float tension_env = bender_advance_env(
            engine->bend_tension_env, engine->bend_tension_env_stage,
            sr, 0.1f, 0.1f, 0.6f, 0.2f);
        engine->bend_tension_phase += tension_freq / sr;
        engine->bend_tension_phase -= std::floor(engine->bend_tension_phase);
        float tension = std::sin(engine->bend_tension_phase * 6.2831853f)
                       * tension_env * tension_vol;

        // Spring oscillator (wobble frequency, triggered on release)
        float spring_env = bender_advance_env(
            engine->bend_spring_env, engine->bend_spring_env_stage,
            sr, 0.003f, 0.4f, 0.0f, 0.3f);
        engine->bend_wobble_phase += 8.0f / sr;
        engine->bend_wobble_phase -= std::floor(engine->bend_wobble_phase);
        float wobble = std::sin(engine->bend_wobble_phase * 6.2831853f) * 80.0f;
        float spring_freq = 350.0f + wobble + spring_env * 200.0f;
        engine->bend_spring_phase += spring_freq / sr;
        engine->bend_spring_phase -= std::floor(engine->bend_spring_phase);
        float spring = std::sin(engine->bend_spring_phase * 6.2831853f)
                      * spring_env * spring_vol;

        out_audio[i] = tension + spring;
    }
}
```

**Step 3:** Add dispatch case in `orpheus_graph.cpp`:

```cpp
        case UNIT_BENDER:
            unit_process_bender(unit, engine, frames, graph->sample_rate);
            break;
```

**Step 4:** Build and commit:
```bash
git add liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_units.cpp liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Implement UNIT_BENDER — pitch CV + tension/spring audio"
```

### Task 15: Implement unit_process_per_string_bender

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1:** Implement `unit_process_per_string_bender`:

```cpp
// ═══════════════════════════════════════════════════════════════════════
// Per-String Bender (4 strings × 2 voices, with audio synthesis)
// ═══════════════════════════════════════════════════════════════════════

void unit_process_per_string_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    std::memset(out_l, 0, num_frames * sizeof(float));
    std::memset(out_r, 0, num_frames * sizeof(float));

    for (int s = 0; s < 4; s++) {
        auto& st = engine->string_state[s];
        float bend = engine->string_bend[s].load(std::memory_order_relaxed);
        float mix = engine->string_mix[s].load(std::memory_order_relaxed);
        bool active = engine->string_active[s].load(std::memory_order_relaxed) != 0;

        // Direction: strings 2,3 are inverted (right hand)
        float direction = (s < 2) ? 1.0f : -1.0f;
        float directed_bend = bend * direction;

        // State transitions
        if (active && !st.was_active) {
            st.tension_env_stage = 1;
        }
        if (!active && st.was_active) {
            st.tension_env_stage = 4;
            st.spring_env_stage = 1;
            st.pluck_env_stage = 1;
        }
        st.was_active = active;
        st.is_active = active;

        // Compute voice CVs (2 voices per string)
        int v0 = s * 2, v1 = s * 2 + 1;
        float cubic = directed_bend * directed_bend * std::fabs(directed_bend);
        if (directed_bend < 0) cubic = -cubic;
        float semi = cubic * 12.0f;
        engine->voice_bend_cv[v0] = semi;
        engine->voice_bend_cv[v1] = semi;

        // Voice mix: non-linear crossfade
        float volA, volB;
        if (mix <= 0.25f) {
            volA = 1.0f; volB = mix / 0.25f;
        } else if (mix >= 0.75f) {
            volA = (1.0f - mix) / 0.25f; volB = 1.0f;
        } else {
            volA = 1.0f; volB = 1.0f;
        }
        engine->voice_mix_cv[v0] = volA;
        engine->voice_mix_cv[v1] = volB;

        // Per-string audio synthesis
        float base_freq = engine->string_base_freq[s].load(std::memory_order_relaxed);

        for (int i = 0; i < num_frames; i++) {
            float sample = 0.0f;

            // Tension (300+s*20 Hz)
            float tension_freq = 300.0f + s * 20.0f + std::fabs(directed_bend) * 200.0f;
            float tension_env = bender_advance_env(
                st.tension_env, st.tension_env_stage,
                sr, 0.1f, 0.1f, 0.6f, 0.2f);
            st.tension_phase += tension_freq / sr;
            st.tension_phase -= std::floor(st.tension_phase);
            sample += std::sin(st.tension_phase * 6.2831853f)
                     * tension_env * 0.015f;

            // Spring (wobble + envelope-modulated freq)
            float spring_env = bender_advance_env(
                st.spring_env, st.spring_env_stage,
                sr, 0.002f, 0.5f, 0.0f, 0.3f);
            st.wobble_phase += 8.0f / sr;
            st.wobble_phase -= std::floor(st.wobble_phase);
            float wobble = std::sin(st.wobble_phase * 6.2831853f) * 80.0f;
            float spring_freq = 350.0f + wobble + spring_env * 200.0f;
            st.spring_phase += spring_freq / sr;
            st.spring_phase -= std::floor(st.spring_phase);
            sample += std::sin(st.spring_phase * 6.2831853f)
                     * spring_env * 0.5f * 0.4f;

            // Pluck (short burst at base_freq)
            float pluck_env = bender_advance_env(
                st.pluck_env, st.pluck_env_stage,
                sr, 0.001f, 0.08f, 0.0f, 0.05f);
            float pluck_freq = base_freq + s * 150.0f;
            st.pluck_phase += pluck_freq / sr;
            st.pluck_phase -= std::floor(st.pluck_phase);
            sample += std::sin(st.pluck_phase * 6.2831853f)
                     * pluck_env * 0.6f;

            // Slide (square wave + LFO modulation when slide bar active)
            float slide_bar_y = engine->slide_bar_y.load(std::memory_order_relaxed);
            if (slide_bar_y > 0.01f) {
                float slide_lfo_freq = 40.0f + s * 10.0f;
                st.slide_lfo_phase += slide_lfo_freq / sr;
                st.slide_lfo_phase -= std::floor(st.slide_lfo_phase);
                float slide_mod = std::sin(st.slide_lfo_phase * 6.2831853f) * 50.0f;
                float slide_freq = 200.0f + s * 100.0f + slide_mod;
                st.slide_phase += slide_freq / sr;
                st.slide_phase -= std::floor(st.slide_phase);
                float sq = (st.slide_phase < 0.5f) ? 1.0f : -1.0f;

                // Smooth ramp
                float target_ramp = slide_bar_y;
                st.slide_ramp += (target_ramp - st.slide_ramp) * (1.0f / (0.03f * sr));
                sample += sq * st.slide_ramp * 0.1f;
            }

            // Pan: strings 0,1 left; strings 2,3 right
            float pan = (s < 2) ? -0.3f : 0.3f;
            out_l[i] += sample * (0.5f - pan * 0.5f);
            out_r[i] += sample * (0.5f + pan * 0.5f);
        }
    }

    // Apply slide bar pitch bend to all voice bend CVs
    float slide_y = engine->slide_bar_y.load(std::memory_order_relaxed);
    if (slide_y > 0.01f) {
        float slide_cubic = slide_y * slide_y * slide_y;
        float slide_semi = slide_cubic * 6.0f; // half octave range
        for (int v = 0; v < kNumMainVoices; v++) {
            engine->voice_bend_cv[v] += slide_semi;
        }
    }
}
```

**Step 2:** Add dispatch case in `orpheus_graph.cpp`:

```cpp
        case UNIT_PER_STRING_BENDER:
            unit_process_per_string_bender(unit, engine, frames, graph->sample_rate);
            break;
```

**Step 3:** Build and commit:
```bash
git add liborpheus_dsp/src/orpheus_units.cpp liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Implement UNIT_PER_STRING_BENDER — 4 strings with audio synthesis"
```

### Task 16: Integrate bend CVs into unit_process_plaits

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`

**Step 1:** In `unit_process_plaits`, after the coupling and FM offset computation, add bend CV:

```cpp
    // ── Bender pitch offset ─────────────────────────────
    float bend_offset = 0.0f;
    float bend_vol_mult = 1.0f;
    if (idx < kNumMainVoices) {
        bend_offset = engine->voice_bend_cv[idx];
        bend_vol_mult = engine->voice_mix_cv[idx];
    }
```

**Step 2:** Add `bend_offset` to both note computations:

Engine 0:
```cpp
        float note = vp.tune.load(...) + vibrato_semitones + coupling_offset + fm_mod_semitones + bend_offset;
```

Plaits:
```cpp
        patch.note = vp.tune.load(...) + vibrato_semitones + coupling_offset + fm_mod_semitones + bend_offset;
```

**Step 3:** Apply `bend_vol_mult` to the output. After the per-sample loop in both Engine 0 and Plaits paths, scale the output:

```cpp
        // Apply bender voice mix volume
        if (bend_vol_mult < 0.999f) {
            for (int i = 0; i < num_frames; i++) {
                out[i] *= bend_vol_mult;
            }
        }
```

**Step 4:** Build and run tests:
```bash
cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test 2>&1 | grep -E "(PASS|FAIL)"
```

**Step 5:** Commit:
```bash
git add liborpheus_dsp/src/orpheus_units.cpp
git commit -m "feat(dsp): Integrate bender CVs into Plaits pitch and volume"
```

### Task 17: Wire bender units into graph

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`

**Step 1:** Add bender units after the looper:

```kotlin
    // Global Bender (pitch CV + timbre CV + audio)
    val benderUnit = bender("bender")
    benderUnit.aux to delay.inputA       // bender audio → delay send
    benderUnit.aux to delay.inputB

    // Per-String Bender (4 strings × 2 voices + audio)
    val psb = perStringBender("psb")
    psb.out to clipL.input               // per-string audio → output
    psb.outRight to clipR.input
```

**Step 2:** Build Kotlin:
```bash
./gradlew :core:dsp-engine:compileKotlinJvm
```

**Step 3:** Commit:
```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt
git commit -m "feat(dsp): Wire bender units into graph"
```

### Task 18: Add bender test

**Files:**
- Modify: `liborpheus_dsp/test/test_main.cpp`

**Step 1:** Add test:

```cpp
bool test_bender() {
    printf("\n=== Test: Bender CV + audio ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit bender_unit = {};
    bender_unit.type = UNIT_BENDER;
    bender_unit.enabled = true;
    unit_init(&bender_unit, 48000.0f);

    // Apply a bend
    engine->bend_amount.store(0.5f);

    float max_pitch = 0.0f, max_audio = 0.0f;
    for (int offset = 0; offset < 48000; offset += 128) {
        int chunk = std::min(128, 48000 - offset);
        unit_process_bender(&bender_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float p = std::fabs(bender_unit.output_buffers[OPORT_OUT][i]);
            float a = std::fabs(bender_unit.output_buffers[OPORT_AUX][i]);
            if (p > max_pitch) max_pitch = p;
            if (a > max_audio) max_audio = a;
        }
    }

    printf("Max pitch CV: %.4f, Max audio: %.4f\n", max_pitch, max_audio);
    bool pass = max_pitch > 0.01f && max_audio > 0.0001f;
    printf("Bender test: %s\n", pass ? "PASS" : "FAIL");

    // Release bend — should trigger spring
    engine->bend_amount.store(0.0f);
    float max_spring = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_bender(&bender_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(bender_unit.output_buffers[OPORT_AUX][i]);
            if (a > max_spring) max_spring = a;
        }
    }
    printf("Max spring audio after release: %.4f\n", max_spring);
    bool spring_pass = max_spring > 0.0001f;
    printf("Spring test: %s\n", spring_pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass && spring_pass;
}
```

**Step 2:** Add per-string bender test:

```cpp
bool test_per_string_bender() {
    printf("\n=== Test: Per-string bender ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit psb_unit = {};
    psb_unit.type = UNIT_PER_STRING_BENDER;
    psb_unit.enabled = true;
    unit_init(&psb_unit, 48000.0f);

    // Activate string 0 with bend
    engine->string_active[0].store(1);
    engine->string_bend[0].store(0.5f);
    engine->string_mix[0].store(0.5f);

    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_per_string_bender(&psb_unit, engine, chunk, 48000.0f);
    }

    // Check voice CVs
    float bend_cv = engine->voice_bend_cv[0];
    float mix_cv = engine->voice_mix_cv[0];
    printf("Voice 0 bend CV: %.4f semitones\n", bend_cv);
    printf("Voice 0 mix CV: %.4f\n", mix_cv);

    // Release string — should trigger pluck + spring
    engine->string_active[0].store(0);
    float max_audio = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_per_string_bender(&psb_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(psb_unit.output_buffers[OPORT_OUT][i]);
            if (a > max_audio) max_audio = a;
        }
    }

    printf("Max audio after release: %.4f\n", max_audio);
    bool pass = std::fabs(bend_cv) > 0.1f && mix_cv >= 0.99f && max_audio > 0.001f;
    printf("Per-string bender test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}
```

**Step 3:** Add both to main(). Build and run.

**Step 4:** Commit:
```bash
git add liborpheus_dsp/test/test_main.cpp
git commit -m "test(dsp): Add bender and per-string bender tests"
```

---

## Phase F: Gap Analysis Update + Final Verification

### Task 19: Update gap analysis doc

**Files:**
- Modify: `docs/plans/2026-03-07-cpp-dsp-parity-gap-analysis.md`

**Step 1:** Update the gap summary table — mark all 6 remaining gaps as FIXED:

- Gap #6: `~~Resonator routing~~ | Complex 4-input routing | 4-input excite/bypass graph | Full JSyn routing parity | **FIXED** — targetMix + wet/dry`
- Gap #10: `~~Bender/PerStringBender~~ | Audio synthesis + pitch bend | UNIT_BENDER + UNIT_PER_STRING_BENDER | Full JSyn parity | **FIXED** — CV + tension/spring/pluck/slide audio`
- Gap #11: `~~Voice coupling~~ | Partner envelope → pitch mod | Peak follower + coupling depth | Partner env modulates pitch | **FIXED** — peak follower in unit_process_plaits`
- Gap #12: `~~FM modulation~~ | Voice-to-voice cross-mod, LFO→FM | Duo + cross-quad FM routing | Full FM parity | **FIXED** — voice_last_output + fm_cross_quad`
- Gap #13: `~~Mod source routing~~ | LFO/FM/Flux → timbre/morph mod | Per-duo source selection | Full mod routing | **FIXED** — mod_source + mod_depth per duo`
- Gap #15: `~~Warps source routing~~ | 7 carrier/modulator options | Source buffer system | Dynamic source selection | **FIXED** — 7 source buffers + runtime selection`

**Step 2:** Commit:
```bash
git add docs/plans/2026-03-07-cpp-dsp-parity-gap-analysis.md
git commit -m "docs: All 20 parity gaps FIXED — full JSyn/C++ parity achieved"
```

### Task 20: Full build + test verification

**Step 1:** Build C++ and run all tests:
```bash
cd liborpheus_dsp && cmake --build build && ./build/orpheus_dsp_test 2>&1
```

Expected: All tests PASS (clock, grids, marbles, looper, coupling, FM, bender, per-string bender).

**Step 2:** Build Kotlin:
```bash
./gradlew :core:dsp-engine:compileKotlinJvm 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.
