// Pulsar engine-parameter pinning tests (TDD anchor — Task 4)
//
// Four cases exercise the expected pinning contract:
//   1. Pin holds harmonics constant while mood sweeps 0..1
//   2. Pin-off: harmonics follows the macro (proves macro range is live)
//   3. DX-style force-pin via atomic: harmonics stays at pinned value
//   4. Pin holds harmonics even when evolution evo_harm range is set
//
// ALL tests that assert pinned behaviour FAIL in this commit because the
// render path does not yet honour the pin atomics.  Task 5 adds the gate.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static constexpr float kSampleRate = 48000.0f;
static constexpr int   kBlockSize  = 64;
// Engine IDs (C++ indices, not Kotlin Pulsar IDs)
// PAR = 18 (Particle/superwave), DX2 = 3 (SixOp variant)
static constexpr int kEngineIdPAR = 18;
static constexpr int kEngineIdDX2 = 3;

// ── Fixture helpers ────────────────────────────────────────────────────

static GraphUnit make_pulsar_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type    = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

static OrpheusEngine* make_playing_engine() {
    OrpheusEngine* engine = orpheus_engine_create(kSampleRate);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    // Neutral energy: above 0.6 → EDM engine selected unconditionally.
    // Use 0.8 to keep engine_index == engine_edm throughout the test
    // so PULSAR_PICK always reads the *_edm atomics.
    engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    return engine;
}

// Configure track 0 with a specific engine and pinning flags.
// Calls setup_cosmic_techno first (initialises all 8 tracks + genre),
// then overrides track 0.
static void setup_pin_test_track0(OrpheusEngine* engine,
                                   int engine_id,
                                   float harmonics_val,
                                   float timbre_val,
                                   float morph_val,
                                   bool pin_h, bool pin_t, bool pin_m) {
    setup_cosmic_techno(engine);
    // Override track 0 engine (both slots identical so energy>0.6 picks EDM).
    engine->pulsar_track_engine_edm[0].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[0].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);  // Melodic
    engine->pulsar_track_volume[0].store(0.8f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.8f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[0].store(harmonics_val, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[0].store(harmonics_val, std::memory_order_relaxed);
    engine->pulsar_track_timbre[0].store(timbre_val, std::memory_order_relaxed);
    engine->pulsar_track_timbre_space[0].store(timbre_val, std::memory_order_relaxed);
    engine->pulsar_track_morph[0].store(morph_val, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[0].store(morph_val, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics[0].store(pin_h ? 1 : 0, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[0].store(pin_h ? 1 : 0, std::memory_order_relaxed);
    engine->pulsar_track_pin_timbre[0].store(pin_t ? 1 : 0, std::memory_order_relaxed);
    engine->pulsar_track_pin_timbre_space[0].store(pin_t ? 1 : 0, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph[0].store(pin_m ? 1 : 0, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph_space[0].store(pin_m ? 1 : 0, std::memory_order_relaxed);
    // Start mood at 0.0 so the smoother has maximum room to walk during
    // test_pin_off_follows_macro; harmless for tests 1/3/4 which set mood explicitly.
    engine->pulsar_mood.store(0.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);
}

// Render a few blocks and return the last mod_harmonics_debug value for track 0.
static float render_and_peek(GraphUnit* unit, OrpheusEngine* engine, int num_blocks = 2) {
    for (int b = 0; b < num_blocks; b++)
        unit_process_pulsar(unit, engine, kBlockSize, kSampleRate);
    return engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
}

// ── Test 1: Pin holds harmonics across a mood sweep ───────────────────
//
// Expected (Task 5+): mod_harmonics stays at 0.85 for every mood step.
// Currently FAILS because lerp_macro(mood, ...) ignores the pin.
static bool test_pin_holds_harmonics_across_mood() {
    printf("\n  Test 1: Pin holds harmonics=0.85 across mood 0..1\n");
    bool pass = true;
    constexpr float kPinned = 0.85f;
    constexpr float kTol    = 1e-4f;
    constexpr int   kSteps  = 11;

    OrpheusEngine* engine = make_playing_engine();
    setup_pin_test_track0(engine, kEngineIdPAR, kPinned, 0.5f, 0.3f,
                          /*pin_h=*/true, /*pin_t=*/false, /*pin_m=*/false);
    GraphUnit unit = make_pulsar_unit();

    for (int step = 0; step < kSteps; step++) {
        float mood = step / (float)(kSteps - 1);  // 0.0 .. 1.0
        engine->pulsar_mood.store(mood, std::memory_order_relaxed);

        float actual = render_and_peek(&unit, engine, /*num_blocks=*/2);
        bool ok = std::fabs(actual - kPinned) <= kTol;
        if (!ok) {
            printf("    FAIL at mood=%.2f: mod_harmonics=%.5f, expected=%.5f\n",
                   mood, actual, kPinned);
            pass = false;
        }
    }

    if (pass) printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 2: Pin-off — harmonics follows the macro ────────────────────
//
// Default mood_harm range is [0.3, 0.7] → delta = 0.4 across full sweep.
// We require |delta| > 0.05 to confirm macro is live.
// This test is EXPECTED TO PASS even before Task 5 because pin=0 means
// the current lerp_macro path is the correct behaviour.
static bool test_pin_off_follows_macro() {
    printf("\n  Test 2: Pin-off: mood sweeps harmonics across macro range\n");
    // Note: smooth_coeff is applied once per process-block (not per sample),
    // so the effective settling time is much longer than 10ms.  After 50 blocks
    // the smooth has advanced ~10% of the way from mood=0 to mood=1.
    // Even at 10% travel the macro delta is clearly nonzero (0.04 > 0.02).
    constexpr float kMinDelta = 0.02f;
    constexpr int kSettleBlocks = 50;

    OrpheusEngine* engine = make_playing_engine();
    // pin_h = false → current behaviour: lerp_macro drives mod_harmonics.
    setup_pin_test_track0(engine, kEngineIdPAR, 0.5f, 0.5f, 0.3f,
                          /*pin_h=*/false, /*pin_t=*/false, /*pin_m=*/false);
    GraphUnit unit = make_pulsar_unit();

    // Capture at mood=0 (settle first)
    engine->pulsar_mood.store(0.0f, std::memory_order_relaxed);
    float harm_at_0 = render_and_peek(&unit, engine, kSettleBlocks);

    // Capture at mood=1 (settle again)
    engine->pulsar_mood.store(1.0f, std::memory_order_relaxed);
    float harm_at_1 = render_and_peek(&unit, engine, kSettleBlocks);

    float delta = std::fabs(harm_at_1 - harm_at_0);
    bool pass = delta > kMinDelta;

    if (!pass)
        printf("    FAIL: |delta|=%.4f <= %.3f (mod_harm@mood=0=%.4f, @mood=1=%.4f)\n",
               delta, kMinDelta, harm_at_0, harm_at_1);
    else
        printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 3: DX-style engine force-pin via pin atomic ────────────────
//
// DX2 engine (id 3) should have harmonics pinned by the loader
// (via engineId.forcePinHarmonics).  We simulate that by setting
// pin_harmonics=1 directly (same thing the loader will do in Task 5+).
//
// Expected (Task 5+): mod_harmonics == 0.72 for every mood step.
// Currently FAILS because the render path ignores the pin atomic.
static bool test_dx_force_pin_harmonics() {
    printf("\n  Test 3: DX2 engine (id=%d) pin_harmonics: mod_harmonics stays at 0.72\n",
           kEngineIdDX2);
    bool pass = true;
    constexpr float kPinned = 0.72f;
    constexpr float kTol    = 1e-4f;
    const float moods[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    constexpr int kSteps = 5;

    OrpheusEngine* engine = make_playing_engine();
    setup_pin_test_track0(engine, kEngineIdDX2, kPinned, 0.5f, 0.3f,
                          /*pin_h=*/true, /*pin_t=*/false, /*pin_m=*/false);
    GraphUnit unit = make_pulsar_unit();

    for (int s = 0; s < kSteps; s++) {
        engine->pulsar_mood.store(moods[s], std::memory_order_relaxed);
        float actual = render_and_peek(&unit, engine, 2);
        bool ok = std::fabs(actual - kPinned) <= kTol;
        if (!ok) {
            printf("    FAIL at mood=%.2f: mod_harmonics=%.5f, expected=%.5f\n",
                   moods[s], actual, kPinned);
            pass = false;
        }
    }

    if (pass) printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 4: Pin prevents evolution drift ───────────────────────────
//
// Configure evo_harm range [0.1, 0.9] with prob=1.0 so the evolution
// block would normally steer mod_harmonics away from the pinned value.
// With pin active, mod_harmonics should remain at 0.85.
//
// Expected (Task 5+): mod_harmonics stays at 0.85 for all 64 blocks.
// Currently FAILS because the render path doesn't honour the pin.
//
// Note: tension_intensity is internal state that ramps up over bars.
// Even at low tension_intensity the lerp_macro override already causes
// mod_harmonics != 0.85, so the test fails for the right reason.
static bool test_pin_escapes_evolution() {
    printf("\n  Test 4: Pin prevents evolution from overriding harmonics=0.85\n");
    bool pass = true;
    constexpr float kPinned  = 0.85f;
    constexpr float kTol     = 1e-4f;
    constexpr int   kBlocks  = 64;

    OrpheusEngine* engine = make_playing_engine();
    setup_pin_test_track0(engine, kEngineIdPAR, kPinned, 0.5f, 0.3f,
                          /*pin_h=*/true, /*pin_t=*/false, /*pin_m=*/false);
    GraphUnit unit = make_pulsar_unit();

    // Enable harmonics evolution range so it would modify mod_harmonics
    // if the render path didn't respect the pin flag.
    engine->pulsar_tension_evo_harm_low.store(0.1f, std::memory_order_relaxed);
    engine->pulsar_tension_evo_harm_high.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_tension_evo_harm_prob.store(1.0f, std::memory_order_relaxed);
    // Use a mid-range mood so the lerp_macro output is clearly != 0.85
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);

    int fail_count = 0;
    float last_actual = 0.0f;
    for (int b = 0; b < kBlocks; b++) {
        unit_process_pulsar(&unit, engine, kBlockSize, kSampleRate);
        float actual = engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
        last_actual = actual;
        if (std::fabs(actual - kPinned) > kTol) {
            if (fail_count == 0)
                printf("    First fail at block %d: mod_harmonics=%.5f, expected=%.5f\n",
                       b, actual, kPinned);
            fail_count++;
            pass = false;
        }
    }

    if (fail_count > 0)
        printf("    FAIL: %d/%d blocks had mod_harmonics != %.5f (last=%.5f)\n",
               fail_count, kBlocks, kPinned, last_actual);
    else
        printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// Test 5: harmonicsModulation > 0 walks the pinned harmonics within its swing
// window, while still staying close to the base value (not free-running).
static bool test_pinned_harmonics_walks_when_modulation_nonzero() {
    printf("\n  Test 5: pin + harmonicsModulation walks within bounded window\n");
    bool pass = true;
    constexpr float kBase = 0.582f;          // legacy DX3 anchor (bucket edge: idx 18/19)
    constexpr float kModDepth = 0.05f;       // ±0.05 swing requested
    constexpr int   kBlocks = 256;           // long enough for slow LFO to complete cycles

    OrpheusEngine* engine = make_playing_engine();
    setup_pin_test_track0(engine, kEngineIdPAR, kBase, 0.5f, 0.3f,
                          /*pin_h=*/true, /*pin_t=*/false, /*pin_m=*/false);
    // The LFO modulation path in unit_process_pulsar only runs for texture/FX
    // tracks (idx >= 5) OR tracks with DRONE envelope profile. Override track 0's
    // envelope to DRONE (=4) so the LFO runs on it.
    engine->pulsar_track_envelope[0].store(4, std::memory_order_relaxed);
    // Engage the LFO at a known depth so mod_lfo_output[2] is non-zero.
    engine->pulsar_track_mod_lfo_rate[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_mod_lfo_depth[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_mod_lfo_coupling[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_modulation[0].store(kModDepth, std::memory_order_relaxed);
    // Force texture-energy curve to its peak (energy at extreme) so LFO output isn't ducked.
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);

    GraphUnit unit = make_pulsar_unit();
    float min_seen = 1.0f, max_seen = 0.0f;
    bool ever_diff = false;
    for (int b = 0; b < kBlocks; b++) {
        unit_process_pulsar(&unit, engine, kBlockSize, kSampleRate);
        float actual = engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
        if (actual < min_seen) min_seen = actual;
        if (actual > max_seen) max_seen = actual;
        if (std::fabs(actual - kBase) > 1e-4f) ever_diff = true;
    }

    // Two assertions:
    //  (a) harmonics actually moved (not pinned-static)
    //  (b) it stayed inside the requested ±kModDepth window
    if (!ever_diff) {
        printf("    FAIL: harmonics never deviated from base — modulation didn't apply "
               "(saw [%.5f..%.5f] around %.5f)\n", min_seen, max_seen, kBase);
        pass = false;
    }
    if (min_seen < kBase - kModDepth - 1e-3f || max_seen > kBase + kModDepth + 1e-3f) {
        printf("    FAIL: swing exceeded ±%.3f window (saw [%.5f .. %.5f] around %.5f)\n",
               kModDepth, min_seen, max_seen, kBase);
        pass = false;
    }

    if (pass) printf("    PASS\n");
    orpheus_engine_destroy(engine);
    return pass;
}

// Test 6: harmonicsMacroRange — mood macro walks pinned harmonics on DX
// At mood = 0.5 the walk is zero (back at the pinned base); at mood = 0 or 1
// the walk is ±range. Verifies the user-knob-driven feel: turn the knob,
// patch index changes.
static bool test_macro_walk_on_dx_pinned_harmonics() {
    printf("\n  Test 6: pin + harmonicsMacroRange walks DX harmonics with mood\n");
    bool pass = true;
    constexpr float kBase = 0.551f;        // legacy DX anchor (loads idx 17 "Insert 1")
    constexpr float kRange = 0.10f;        // ±0.10 across a full mood sweep
    constexpr float kTol = 0.005f;

    OrpheusEngine* engine = make_playing_engine();
    // Use DX2 (engine id 3) so the is_dx_family gate fires.
    setup_pin_test_track0(engine, kEngineIdDX2, kBase, 0.5f, 0.3f,
                          /*pin_h=*/true, /*pin_t=*/false, /*pin_m=*/false);
    engine->pulsar_track_harmonics_macro_source[0].store(4, std::memory_order_relaxed); // MOOD
    engine->pulsar_track_harmonics_macro_range[0].store(kRange, std::memory_order_relaxed);
    GraphUnit unit = make_pulsar_unit();

    // The mood smoother lags target by ~0.21%/block (smooth_coeff at 48kHz),
    // so 4 blocks only travel ~0.83% of the way — not enough for the kBase
    // check to pass deterministically. trigger_vibe_load() snaps the smoother
    // to the current atomic on the next render, so we can sample directly.
    auto render_with_mood = [&](float mood_val) -> float {
        engine->pulsar_mood.store(mood_val, std::memory_order_relaxed);
        trigger_vibe_load(engine);
        for (int b = 0; b < 4; b++)
            unit_process_pulsar(&unit, engine, kBlockSize, kSampleRate);
        return engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
    };

    // Mood at 0.5 → walk contribution is 0 → mod_harmonics ≈ kBase
    float at_mid = render_with_mood(0.5f);
    if (std::fabs(at_mid - kBase) > kTol) {
        printf("    FAIL: at mood=0.5, mod_harmonics=%.5f (expected ~%.5f)\n", at_mid, kBase);
        pass = false;
    }

    // Mood at 0.0 → walk = (0 - 0.5) * 2 * range = -range → mod_harmonics ≈ kBase - range.
    // (Macro is smoothed; 4 blocks may not fully reach 0, but should be clearly below kBase.)
    float at_low = render_with_mood(0.0f);
    if (at_low >= kBase) {
        printf("    FAIL: at mood=0.0, mod_harmonics=%.5f (expected < %.5f)\n", at_low, kBase);
        pass = false;
    }

    // Mood at 1.0 → walk = +range → mod_harmonics ≈ kBase + range. Clearly above.
    float at_high = render_with_mood(1.0f);
    if (at_high <= kBase) {
        printf("    FAIL: at mood=1.0, mod_harmonics=%.5f (expected > %.5f)\n", at_high, kBase);
        pass = false;
    }

    // Direction check: at_low < at_mid < at_high.
    if (!(at_low < at_mid && at_mid < at_high)) {
        printf("    FAIL: mood sweep didn't produce monotonic walk (low=%.5f, mid=%.5f, high=%.5f)\n",
               at_low, at_mid, at_high);
        pass = false;
    }

    if (pass) printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// Test 7: harmonicsMacroRange is DX-only — on non-DX engines, the walk is
// ignored even when pinned. Verifies the engine_index gate.
static bool test_macro_walk_skipped_on_non_dx() {
    printf("\n  Test 7: pin + harmonicsMacroRange has no effect on non-DX engine\n");
    bool pass = true;
    constexpr float kBase = 0.85f;
    constexpr float kRange = 0.10f;
    constexpr float kTol = 1e-4f;

    OrpheusEngine* engine = make_playing_engine();
    setup_pin_test_track0(engine, kEngineIdPAR, kBase, 0.5f, 0.3f,
                          /*pin_h=*/true, /*pin_t=*/false, /*pin_m=*/false);
    engine->pulsar_track_harmonics_macro_source[0].store(4, std::memory_order_relaxed); // MOOD
    engine->pulsar_track_harmonics_macro_range[0].store(kRange, std::memory_order_relaxed);
    GraphUnit unit = make_pulsar_unit();

    // Sweep mood across 5 values; mod_harmonics must remain pinned at kBase.
    int fail_count = 0;
    for (int i = 0; i <= 4; i++) {
        float mood_val = static_cast<float>(i) / 4.0f;
        engine->pulsar_mood.store(mood_val, std::memory_order_relaxed);
        for (int b = 0; b < 4; b++)
            unit_process_pulsar(&unit, engine, kBlockSize, kSampleRate);
        float actual = engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
        if (std::fabs(actual - kBase) > kTol) {
            printf("    FAIL: mood=%.2f → mod_harmonics=%.5f (expected pinned at %.5f)\n",
                   mood_val, actual, kBase);
            fail_count++;
            pass = false;
        }
    }

    if (fail_count == 0) printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// Test 8: modulation × macro_range compose — LFO swing oscillates around a
// macro-walked center, not around the static base. Guards the composition
// order in orpheus_unit_pulsar.cpp (mod_harmonics = base + lfo*mod, then
// mod_harmonics += macro_walk). A future refactor that flips '=' / '+='
// between the two branches would silently break this.
//
// At mood=1.0 the macro walk contributes +kRange; the LFO branch keeps the
// signal bounded within ±kModDepth of that walked center. Test passes when:
//   1. Every sampled value sits inside [center − mod − tol, center + mod + tol]
//   2. The mean tracks the walked center within smoother-lag tolerance
// LFO amplitude itself is covered by test 5 — we don't re-assert it here.
static bool test_pin_modulation_plus_macro_walk_compose() {
    printf("\n  Test 8: pin + harmonicsModulation + harmonicsMacroRange compose\n");
    bool pass = true;
    constexpr float kBase     = 0.551f;        // legacy DX anchor (loads idx 17 "Insert 1")
    constexpr float kModDepth = 0.03f;         // small LFO swing
    constexpr float kRange    = 0.10f;         // larger macro walk
    constexpr int   kBlocks   = 256;

    OrpheusEngine* engine = make_playing_engine();
    setup_pin_test_track0(engine, kEngineIdDX2, kBase, 0.5f, 0.3f,
                          /*pin_h=*/true, /*pin_t=*/false, /*pin_m=*/false);
    // Same LFO-arming as test 5.
    engine->pulsar_track_envelope[0].store(4, std::memory_order_relaxed);
    engine->pulsar_track_mod_lfo_rate[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_mod_lfo_depth[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_mod_lfo_coupling[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_modulation[0].store(kModDepth, std::memory_order_relaxed);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    // Engage the macro walk too — mood=1.0 → walk = +kRange.
    engine->pulsar_track_harmonics_macro_source[0].store(4, std::memory_order_relaxed); // MOOD
    engine->pulsar_track_harmonics_macro_range[0].store(kRange, std::memory_order_relaxed);
    engine->pulsar_mood.store(1.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);  // snap smoother so the walk lands at full range fast

    GraphUnit unit = make_pulsar_unit();
    float min_seen = 1.0f, max_seen = 0.0f, sum = 0.0f;
    int samples = 0;
    // Warm up so the macro smoother has settled.
    for (int b = 0; b < 32; b++) unit_process_pulsar(&unit, engine, kBlockSize, kSampleRate);
    for (int b = 0; b < kBlocks; b++) {
        unit_process_pulsar(&unit, engine, kBlockSize, kSampleRate);
        float actual = engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
        if (actual < min_seen) min_seen = actual;
        if (actual > max_seen) max_seen = actual;
        sum += actual;
        samples++;
    }
    float mean = sum / static_cast<float>(samples);
    float expected_center = kBase + kRange;       // mood=1.0 walks center up by +range
    constexpr float kCenterTol = 0.02f;           // smoother lag tolerance
    constexpr float kSwingTol  = 0.005f;          // overshoot allowance

    if (std::fabs(mean - expected_center) > kCenterTol) {
        printf("    FAIL: mean=%.5f deviates from expected center %.5f "
               "(saw [%.5f..%.5f], tol %.3f) — composition order broken?\n",
               mean, expected_center, min_seen, max_seen, kCenterTol);
        pass = false;
    }
    if (min_seen < expected_center - kModDepth - kSwingTol ||
        max_seen > expected_center + kModDepth + kSwingTol) {
        printf("    FAIL: swing [%.5f .. %.5f] exceeded ±%.3f window around walked center %.5f\n",
               min_seen, max_seen, kModDepth, expected_center);
        pass = false;
    }

    if (pass) printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// Test 9: Space-slot pinning — at energy < 0.6, PULSAR_PICK reads the
// `*_space` atomics. PULSAR_PICK keys on (ts.engine_index == engine_edm),
// so the two slots must point at different engine IDs to actually exercise
// the `_space` branch (else use_edm stays true and the EDM atomics are read
// regardless of energy). This forces that divergence and verifies the Space
// pin flag is honoured independently from EDM.
static bool test_space_slot_pin_holds_harmonics() {
    printf("\n  Test 9: Space-slot pin holds harmonics_space=0.30 (energy=0.2)\n");
    bool pass = true;
    constexpr float kEdmHarm   = 0.85f;   // wildly different from Space
    constexpr float kSpaceHarm = 0.30f;
    constexpr float kTol = 1e-4f;

    OrpheusEngine* engine = make_playing_engine();
    setup_cosmic_techno(engine);
    // Different engines per slot is what makes PULSAR_PICK actually read the
    // _space side when energy is low.
    engine->pulsar_track_engine_edm[0].store(kEngineIdDX2, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[0].store(kEngineIdPAR, std::memory_order_relaxed);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);
    engine->pulsar_track_volume[0].store(0.8f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.8f, std::memory_order_relaxed);
    // Divergent slot values — mod_harmonics must follow the active (Space) slot.
    engine->pulsar_track_harmonics[0].store(kEdmHarm, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[0].store(kSpaceHarm, std::memory_order_relaxed);
    engine->pulsar_track_timbre[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_timbre_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_morph[0].store(0.3f, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[0].store(0.3f, std::memory_order_relaxed);
    // EDM unpinned, Space pinned — proves the slot-correct pin flag is honoured.
    engine->pulsar_track_pin_harmonics[0].store(0, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[0].store(1, std::memory_order_relaxed);
    // Lock onto the Space slot: energy well below 0.4 selects engine_space.
    engine->pulsar_energy.store(0.2f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    GraphUnit unit = make_pulsar_unit();
    for (int step = 0; step <= 10; step++) {
        float mood = step / 10.0f;
        engine->pulsar_mood.store(mood, std::memory_order_relaxed);
        for (int b = 0; b < 2; b++) unit_process_pulsar(&unit, engine, kBlockSize, kSampleRate);
        float actual = engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
        if (std::fabs(actual - kSpaceHarm) > kTol) {
            printf("    FAIL at mood=%.2f: mod_harmonics=%.5f (expected pinned at Space %.5f, EDM was %.5f)\n",
                   mood, actual, kSpaceHarm, kEdmHarm);
            pass = false;
        }
    }

    if (pass) printf("    PASS\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Suite entry point ─────────────────────────────────────────────────

bool run_pulsar_pinning_tests() {
    printf("\n═══ Pulsar Engine-Parameter Pinning ═══\n");
    int passed = 0, failed = 0;

    auto run = [&](bool (*fn)()) {
        if (fn()) passed++; else failed++;
    };

    run(test_pin_holds_harmonics_across_mood);
    run(test_pin_off_follows_macro);
    run(test_dx_force_pin_harmonics);
    run(test_pin_escapes_evolution);
    run(test_pinned_harmonics_walks_when_modulation_nonzero);
    run(test_macro_walk_on_dx_pinned_harmonics);
    run(test_macro_walk_skipped_on_non_dx);
    run(test_pin_modulation_plus_macro_walk_compose);
    run(test_space_slot_pin_holds_harmonics);

    printf("\n  Pulsar Pinning: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
