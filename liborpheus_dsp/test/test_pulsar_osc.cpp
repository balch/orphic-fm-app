// Verify OSC renders when used as a Pulsar track engine. Without the dispatch
// hook in orpheus_unit_pulsar.cpp, engine_index -1 falls through to
// OrpheusVoice::Render, which clamps it to 0 and plays VCF instead.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_osc.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static constexpr int kBlockFrames = 512;

static void make_osc_unit(GraphUnit& unit) {
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
}

// Track 0 on OSC in both slots, soloed, RNGs pinned. solo_track overwrites
// pulsar_track_volume[0] with 1.0f, so it runs BEFORE the per-track settings
// rather than after them.
static void setup_osc_track0(OrpheusEngine* engine) {
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, 0);

    engine->pulsar_track_engine_edm[0].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[0].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_volume[0].store(0.85f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.85f, std::memory_order_relaxed);
}

static bool test_pulsar_track_renders_osc() {
    printf("\n=== Test: Pulsar track set to OSC (engine -1) renders the OSC voice ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);

    // Pinned harmonics at 0.9, well above kOscModRange.harmonics_max: pinning is
    // what makes the authored value the one the OSC arm of the playability-floor
    // block sees, instead of the macro walk's.
    engine->pulsar_track_pin_harmonics[0].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[0].store(1, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[0].store(0.9f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[0].store(0.9f, std::memory_order_relaxed);
    engine->pulsar_track_timbre[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_timbre_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_morph[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_fm_ratio[0].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_ratio_space[0].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_shape[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_shape_space[0].store(0.0f, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

    float peak = 0.0f;
    float max_mod_harm = 0.0f;
    float min_note = 1e9f;
    for (int i = 0; i < 200; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        for (int s = 0; s < kBlockFrames; s++) {
            float al = std::fabs(engine->pulsar_out_l[s]);
            float ar = std::fabs(engine->pulsar_out_r[s]);
            if (al > peak) peak = al;
            if (ar > peak) peak = ar;
        }
        float h = engine->pulsar_track_mod_harmonics_debug[0].load(std::memory_order_relaxed);
        float n = engine->pulsar_track_note_debug[0].load(std::memory_order_relaxed);
        if (h > max_mod_harm) max_mod_harm = h;
        if (n < min_note) min_note = n;
    }

    // The kick pattern writes note 36, below the OSC floor, so the octave fold
    // below is a real transposition rather than a vacuous pass.
    const PulsarTrackState& ts0 = engine->pulsar_state->tracks[0];
    int min_step_note = 128;
    for (int s = 0; s < ts0.step_count; s++) {
        if (ts0.steps[s].gate && ts0.steps[s].note < min_step_note)
            min_step_note = ts0.steps[s].note;
    }

    // Prove the branch actually fired. If dispatch never reached the OSC
    // kernel, both phases stay at their default-initialized 0.
    const PulsarOscState& st = ts0.osc_state;
    bool state_evolved = (st.core.tri_phase != 0.0f) || (st.mod_phase != 0.0f);
    printf("  peak=%.4f tri_phase=%.6f mod_phase=%.6f\n",
           peak, st.core.tri_phase, st.mod_phase);
    printf("  mod_harmonics max=%.6f (ceiling %.2f), note min=%.1f (floor %d), "
           "lowest gated step note=%d\n",
           max_mod_harm, kOscModRange.harmonics_max, min_note,
           kOscModRange.note_min, min_step_note);

    bool all_pass = true;
    all_pass &= state_evolved;
    all_pass &= (peak > 0.001f);
    // No upper bound on the BUS peak: kPulsarOutputGain 3.3 saturates the master
    // soft_limit for any soloed track, so this number pins at ~1.0 whatever the
    // kernel does. The kernel's real ceiling is asserted in the osc suite.
    all_pass &= (max_mod_harm <= kOscModRange.harmonics_max + 1e-6f);
    all_pass &= (max_mod_harm >= kOscModRange.harmonics_max - 1e-6f);
    all_pass &= (min_step_note < kOscModRange.note_min);
    all_pass &= (min_note >= static_cast<float>(kOscModRange.note_min));

    printf("Pulsar OSC dispatch: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

// The sub-block trigger split must hand the pre-boundary segment the OLD gate
// and the post-boundary segment the new one, so a note-on zeroes the modulator
// at the boundary sample instead of at block start. Driven through
// unit_process_pulsar, because process_osc_block on its own is Task 2's kernel
// and says nothing about how this dispatch branch calls it.
//
// free-run FM at 93.75Hz makes mod_inc exactly 1/512, so a full 512-frame block
// advances the phase by exactly 1.0 and every expected value below is an exact
// binary fraction — no tolerance stacking over 400 blocks.
static bool test_pulsar_osc_respects_trigger_offset() {
    printf("\n=== Test: OSC honors the intra-block trigger offset ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);

    const float kFreeHz = 93.75f;
    const float mod_inc = kFreeHz / 48000.0f;   // exactly 1/512
    engine->pulsar_track_harmonics[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_morph[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_free_hz[0].store(kFreeHz, std::memory_order_relaxed);
    engine->pulsar_track_fm_free_hz_space[0].store(kFreeHz, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

    float exp_phase = 0.0f;
    int exp_prev_gate = 0;
    int split_blocks = 0;      // blocks the trigger split actually applied to
    int mismatches = 0;
    for (int i = 0; i < 400; i++) {
        // pulsar_state is allocated by the first process call, so the track
        // reference has to be taken inside the loop.
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts0 = engine->pulsar_state->tracks[0];

        // Same three inputs the dispatch branch feeds the kernel, read back
        // after the block: the split offset, the pre-boundary gate, and the
        // post-boundary gate (voice_active, since OSC is not self-enveloped).
        const int off = (ts0.trigger_offset > 0 && ts0.trigger_offset < kBlockFrames)
                            ? ts0.trigger_offset : 0;
        const int g_pre = ts0.gate_pre_boundary ? 1 : 0;
        const int g_post = ts0.voice_active ? 1 : 0;
        // The kernel only advances the modulator while FM is on.
        const bool fm_on =
            engine->pulsar_track_mod_morph_debug[0].load(std::memory_order_relaxed) > 0.0f;

        if (off > 0) {
            split_blocks++;
            if (g_pre != 0 && exp_prev_gate == 0) exp_phase = 0.0f;
            exp_prev_gate = g_pre;
            if (fm_on) {
                exp_phase += static_cast<float>(off) * mod_inc;
                exp_phase -= std::floor(exp_phase);
            }
        }
        if (g_post != 0 && exp_prev_gate == 0) exp_phase = 0.0f;
        exp_prev_gate = g_post;
        if (fm_on) {
            exp_phase += static_cast<float>(kBlockFrames - off) * mod_inc;
            exp_phase -= std::floor(exp_phase);
        }

        const PulsarOscState& st = ts0.osc_state;
        if (std::fabs(st.mod_phase - exp_phase) > 1e-6f || st.prev_gate != exp_prev_gate) {
            if (mismatches < 5) {
                printf("  MISMATCH block %d off=%d g_pre=%d g_post=%d "
                       "mod_phase=%.6f expected=%.6f prev_gate=%d expected=%d\n",
                       i, off, g_pre, g_post, st.mod_phase, exp_phase,
                       st.prev_gate, exp_prev_gate);
            }
            mismatches++;
            exp_phase = st.mod_phase;          // resync so one slip is not 400
            exp_prev_gate = st.prev_gate;
        }
    }

    printf("  %d blocks carried a sub-block trigger, %d mismatches\n",
           split_blocks, mismatches);
    // Without split blocks the assertion above degenerates to "the kernel ran".
    bool ok = (mismatches == 0) && (split_blocks >= 3);
    printf("Trigger offset split: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The gate check above cannot see a dropped pre-boundary segment: on a rising
// edge both call shapes zero the modulator at the same sample. The carrier phase
// can — it advances once per rendered sample, so a skipped segment leaves the
// block `trigger_offset` samples short.
//
// FM and self-feedback are off here so freq is exactly the carrier for the
// block's note, which makes the advance a single multiply off note_debug
// instead of a re-implementation of the kernel's inner loop.
static bool test_pulsar_osc_renders_the_whole_block() {
    printf("\n=== Test: OSC renders both sides of the trigger split ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);

    // Pin harmonics: unpinned, the LFO block recentres it into VCF's 0.10..0.90
    // range and self-feedback then perturbs the carrier off the note's pitch.
    engine->pulsar_track_pin_harmonics[0].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[0].store(1, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_ratio[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_ratio_space[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_free_hz[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_free_hz_space[0].store(0.0f, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

    // 512 sequential adds inside the kernel round differently from the single
    // multiply here; the gap is ~3e-5, two orders under a dropped segment's.
    const float kPhaseTol = 5e-4f;
    float prev_tri = 0.0f;
    int split_blocks = 0;
    int mismatches = 0;
    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts0 = engine->pulsar_state->tracks[0];
        const float note = engine->pulsar_track_note_debug[0].load(std::memory_order_relaxed);
        const float inc = 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f) / 48000.0f;
        float expected = prev_tri + static_cast<float>(kBlockFrames) * inc;
        expected -= std::floor(expected);
        const float got = ts0.osc_state.core.tri_phase;
        if (ts0.trigger_offset > 0 && ts0.trigger_offset < kBlockFrames) split_blocks++;
        if (i > 0 && std::fabs(got - expected) > kPhaseTol) {
            if (mismatches < 5) {
                printf("  MISMATCH block %d off=%d note=%.1f tri_phase=%.6f expected=%.6f\n",
                       i, ts0.trigger_offset, note, got, expected);
            }
            mismatches++;
        }
        prev_tri = got;   // resync so one block's rounding is not 400 blocks'
    }

    printf("  %d blocks carried a sub-block trigger, %d mismatches\n",
           split_blocks, mismatches);
    bool ok = (mismatches == 0) && (split_blocks >= 3);
    printf("Trigger split coverage: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The pattern generator has to hear OSC's note_min on the vibe's FIRST load, not
// just from a second load onward. ts.engine_index defaults to 0 (VCF, floor 30)
// until load_vibe refreshes it from the atomics; if generation reads the field
// before that refresh, it floors against whatever engine happened to occupy the
// track before (VCF's 30) instead of the vibe actually being loaded (OSC's 40).
//
// Track 3 (BASS) with a 24..56 note range exercises this: the pinned seed drives
// enough gated steps that a wrong (lower) floor lets a note through under it.
static bool test_pulsar_osc_note_floor_reaches_the_generator() {
    printf("\n=== Test: the pattern generator gets OSC's note floor on the first load ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    make_osc_unit(unit);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_track_engine_edm[3].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[3].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_note_range_low[3].store(24, std::memory_order_relaxed);
    engine->pulsar_track_note_range_high[3].store(56, std::memory_order_relaxed);
    // The fixture's bass density writes one gated step per bar; the override
    // fills the bar so the floor is checked against a spread of notes.
    engine->pulsar_track_density_override[3].store(0.8f, std::memory_order_relaxed);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

    // Single load, single block: state doesn't exist yet, so this is the engine's
    // lazy-init path, which calls load_vibe exactly once. No warm-up reload.
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);

    const PulsarTrackState& ts3 = engine->pulsar_state->tracks[3];
    int min_note = 128, gated = 0;
    for (int s = 0; s < ts3.step_count; s++) {
        if (!ts3.steps[s].gate) continue;
        gated++;
        if (ts3.steps[s].note < min_note) min_note = ts3.steps[s].note;
    }
    printf("  engine_index=%d, %d gated steps, lowest generated note=%d (floor %d)\n",
           ts3.engine_index, gated, min_note, kOscModRange.note_min);

    bool ok = (gated > 0) && (min_note >= kOscModRange.note_min);
    printf("Generator note floor on first load: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_osc_tests() {
    printf("\n=== Pulsar OSC Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_pulsar_track_renders_osc()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_respects_trigger_offset()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_renders_the_whole_block()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_note_floor_reaches_the_generator()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
