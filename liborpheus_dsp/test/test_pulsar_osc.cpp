// Verify OSC renders when used as a Pulsar track engine. Without the dispatch
// hook in orpheus_unit_pulsar.cpp, engine_index -1 falls through to
// OrpheusVoice::Render, which clamps it to 0 and plays VCF instead.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_osc.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_pattern_gen.h"
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

// Shared fixture for the opening_note_floor tests below: bass (track 3) on a real
// engine (not OSC), a note range wide enough to expose a floor, and density_override
// so a single bar carries a spread of notes to inspect.
static void setup_opening_floor_fixture(OrpheusEngine* engine, int engine_index) {
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_track_engine_edm[3].store(engine_index, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[3].store(engine_index, std::memory_order_relaxed);
    engine->pulsar_track_note_range_low[3].store(24, std::memory_order_relaxed);
    engine->pulsar_track_note_range_high[3].store(56, std::memory_order_relaxed);
    engine->pulsar_track_density_override[3].store(0.8f, std::memory_order_relaxed);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);
}

static int lowest_gated_note(OrpheusEngine* engine, int track, int* gated_out) {
    const PulsarTrackState& ts = engine->pulsar_state->tracks[track];
    int min_note = 128, gated = 0;
    for (int s = 0; s < ts.step_count; s++) {
        if (!ts.steps[s].gate) continue;
        gated++;
        if (ts.steps[s].note < min_note) min_note = ts.steps[s].note;
    }
    *gated_out = gated;
    return min_note;
}

// FireSky's actual scenario: WSH (engine 9, note_min 0) with an authored
// opening_note_floor of 33 (PD's floor) must lift the FIRST generated pattern to 33+,
// even though WSH's own floor would allow notes all the way down.
static bool test_pulsar_opening_note_floor_lifts_the_first_pattern() {
    printf("\n=== Test: opening_note_floor lifts load_vibe's first pattern above the engine floor ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_opening_floor_fixture(engine, 9);  // WSH, note_min 0
    // Widen the range down past the opening floor -- otherwise the note range alone
    // (24-56) never asks the generator for anything below 33 and the assertion below
    // would pass whether or not the lift actually ran (bass_root centers on the
    // range midpoint, so this also has to clear an octave-bucket boundary, not just
    // lower the nominal minimum).
    engine->pulsar_track_note_range_low[3].store(4, std::memory_order_relaxed);
    engine->pulsar_opening_note_floor.store(33, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);

    int gated = 0;
    int min_note = lowest_gated_note(engine, 3, &gated);
    printf("  engine=WSH(floor 0), opening_note_floor=33, %d gated steps, lowest note=%d\n", gated, min_note);

    bool ok = (gated > 0) && (min_note >= 33);
    printf("Opening note floor lifts the first pattern: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Same fixture generated twice: once with opening_note_floor left at its default
// (0/unset) and once with it explicitly stored as 0. max(floor, 0) == floor whenever
// floor >= 0 (true for every engine table entry), so the two must be byte-identical --
// proving 0 is a true no-op rather than a floor of its own, which is what every other
// vibe relies on (none of them set this field).
static bool test_pulsar_opening_note_floor_unset_matches_explicit_zero() {
    printf("\n=== Test: opening_note_floor unset generates identically to an explicit 0 ===\n");

    OrpheusEngine* engine_default = orpheus_engine_create(48000.0f);
    GraphUnit unit_default;
    make_osc_unit(unit_default);
    setup_opening_floor_fixture(engine_default, 9);  // WSH, note_min 0
    // pulsar_opening_note_floor left at its default-constructed 0 -- no store.
    trigger_vibe_load(engine_default);
    unit_process_pulsar(&unit_default, engine_default, kBlockFrames, 48000.0f);

    OrpheusEngine* engine_explicit = orpheus_engine_create(48000.0f);
    GraphUnit unit_explicit;
    make_osc_unit(unit_explicit);
    setup_opening_floor_fixture(engine_explicit, 9);  // WSH, note_min 0
    engine_explicit->pulsar_opening_note_floor.store(0, std::memory_order_relaxed);
    trigger_vibe_load(engine_explicit);
    unit_process_pulsar(&unit_explicit, engine_explicit, kBlockFrames, 48000.0f);

    const PulsarTrackState& ts_default = engine_default->pulsar_state->tracks[3];
    const PulsarTrackState& ts_explicit = engine_explicit->pulsar_state->tracks[3];
    bool identical = ts_default.step_count == ts_explicit.step_count;
    int gated = 0;
    for (int s = 0; identical && s < ts_default.step_count; s++) {
        if (ts_default.steps[s].gate != ts_explicit.steps[s].gate ||
            ts_default.steps[s].note != ts_explicit.steps[s].note) {
            identical = false;
        }
        if (ts_default.steps[s].gate) gated++;
    }
    printf("  engine=WSH(floor 0), %d gated steps, unset-vs-explicit-0 identical=%s\n",
           gated, identical ? "yes" : "no");

    bool ok = (gated > 0) && identical;
    printf("Unset opening_note_floor matches explicit 0: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine_default);
    orpheus_engine_destroy(engine_explicit);
    return ok;
}

// An authored floor below the engine's own floor must never win -- max(), never a
// replacement. VA (engine 8) floors at 40; an opening_note_floor of 20 must not pull
// generation down below 40.
static bool test_pulsar_opening_note_floor_never_lowers_the_engine_floor() {
    printf("\n=== Test: opening_note_floor below the engine floor never lowers it ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_opening_floor_fixture(engine, 8);  // VA, note_min 40
    engine->pulsar_opening_note_floor.store(20, std::memory_order_relaxed);  // below VA's 40

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);

    int gated = 0;
    int min_note = lowest_gated_note(engine, 3, &gated);
    printf("  engine=VA(floor 40), opening_note_floor=20, %d gated steps, lowest note=%d\n", gated, min_note);

    bool ok = (gated > 0) && (min_note >= 40);
    printf("Opening floor below the engine floor never lowers it: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// LPG_PLUCK must shape the OSC branch the way it shapes a Plaits voice: a
// vactrol bloom on note-on, then an asymmetric decay that keeps falling while
// the gate is still high. Without the LPG the OSC path is a bare oscillator
// under Pulsar's "AD" envelope, which is really attack + 100% sustain — it
// holds full level for the whole gate, so the note reads as an organ rather
// than a plucked string.
//
// Measured on the bus with the track turned well down, because kPulsarOutputGain
// 3.3 saturates the master soft_limit for a soloed track at normal volume and a
// saturated peak would flatten exactly the decay this asserts.
//
// Returns late/early peak ratio within the longest held gate, or -1 on a setup
// that could not be measured.
static float osc_held_gate_decay_ratio(int lpg_mode, float lpg_decay) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);

    // Well under the soft_limit knee: 3.3 * 0.12 = 0.4 peak.
    engine->pulsar_track_volume[0].store(0.12f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.12f, std::memory_order_relaxed);

    // Both slots are OSC, so active_lpg_mode resolves to the EDM slot; set them
    // together so the pick cannot silently choose an unset value.
    engine->pulsar_track_lpg_mode[0].store(lpg_mode, std::memory_order_relaxed);
    engine->pulsar_track_lpg_mode_space[0].store(lpg_mode, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay[0].store(lpg_decay, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay_space[0].store(lpg_decay, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour_space[0].store(0.5f, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(70.0f, std::memory_order_relaxed);  // long gates

    static const int kBlocks = 400;
    static float blk_peak[400];
    static bool  blk_active[400];
    for (int i = 0; i < kBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        float pk = 0.0f;
        for (int s = 0; s < kBlockFrames; s++) {
            float al = std::fabs(engine->pulsar_out_l[s]);
            float ar = std::fabs(engine->pulsar_out_r[s]);
            if (al > pk) pk = al;
            if (ar > pk) pk = ar;
        }
        blk_peak[i] = pk;
        blk_active[i] = engine->pulsar_state->tracks[0].voice_active;
    }
    orpheus_engine_destroy(engine);

    int best_start = -1, best_len = 0, cur_start = -1, cur_len = 0;
    for (int i = 0; i < kBlocks; i++) {
        if (blk_active[i]) {
            if (cur_len == 0) cur_start = i;
            cur_len++;
            if (cur_len > best_len) { best_len = cur_len; best_start = cur_start; }
        } else {
            cur_len = 0;
        }
    }

    const int q = best_len / 4;
    if (best_len < 8 || q < 1) return -1.0f;

    float early = 0.0f, late = 0.0f;
    for (int i = best_start; i < best_start + q; i++)
        if (blk_peak[i] > early) early = blk_peak[i];
    for (int i = best_start + best_len - q; i < best_start + best_len; i++)
        if (blk_peak[i] > late) late = blk_peak[i];
    if (early <= 0.001f) return -1.0f;
    return late / early;
}

static bool test_pulsar_osc_lpg_pluck_decays_under_held_gate() {
    printf("\n=== Test: OSC honors LPG_PLUCK (decays while the gate is held) ===\n");

    const float bypass = osc_held_gate_decay_ratio(LPG_BYPASS, 0.5f);
    const float fast   = osc_held_gate_decay_ratio(LPG_PLUCK,  0.2f);
    const float slow   = osc_held_gate_decay_ratio(LPG_PLUCK,  0.8f);

    printf("  late/early peak within the held gate:\n");
    printf("    BYPASS            = %.3f  (flat sustain, the bug)\n", bypass);
    printf("    PLUCK decay=0.2   = %.3f  (short ring)\n", fast);
    printf("    PLUCK decay=0.8   = %.3f  (long ring)\n", slow);

    bool ok = true;
    if (bypass < 0.0f || fast < 0.0f || slow < 0.0f) {
        printf("  FAIL: no held gate long enough to measure\n");
        return false;
    }
    // Bypass must stay flat — this is the shape the OSC bass had.
    if (bypass < 0.85f) {
        printf("  FAIL: BYPASS should hold level flat, got %.3f\n", bypass);
        ok = false;
    }
    // A fast pluck must fall hard inside the gate.
    if (fast > 0.40f) {
        printf("  FAIL: PLUCK decay=0.2 barely decayed (%.3f) — LPG not applied\n", fast);
        ok = false;
    }
    // And lpg_decay must actually reach the vactrol: a longer decay rings longer.
    if (!(slow > fast)) {
        printf("  FAIL: lpg_decay does not reach the LPG (0.8 ratio %.3f <= 0.2 ratio %.3f)\n",
               slow, fast);
        ok = false;
    }

    printf("OSC LPG_PLUCK decay: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A held note spans several sequencer steps: the first carries the gate, the rest
// are hold continuations. gate_timer is decremented at block rate, so when a step
// boundary falls inside a block the gate briefly drops and the hold path raises it
// again — a rising edge that is NOT a note onset. Under a flat envelope that dip is
// inaudible, but LPG_PLUCK re-blooms the vactrol on it, turning a two-note phrase
// into fourteen plucks.
//
// Drives the real sequencer with a two-note lick whose notes span 8 and 6 steps, and
// counts audible re-articulations. The gate-onset count is reported alongside as the
// contrast: the sequencer still toggles voice_active many times either way.
static bool test_pulsar_osc_lpg_blooms_once_per_note_not_per_hold_step() {
    printf("\n=== Test: LPG_PLUCK blooms per note onset, not per hold step ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);

    engine->pulsar_track_volume[0].store(0.12f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.12f, std::memory_order_relaxed);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    // Pin density near 1 the way an authored gesture does, so both notes always
    // fire. Without this the density roll drops whole notes (by design, since the
    // whole-note fix) and the count below measures the roll, not the envelope.
    engine->pulsar_track_density_override[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_min.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(1.0f, std::memory_order_relaxed);

    engine->pulsar_track_lpg_mode[0].store(2, std::memory_order_relaxed);   // PLUCK
    engine->pulsar_track_lpg_mode_space[0].store(2, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay[0].store(0.62f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay_space[0].store(0.62f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour[0].store(0.35f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour_space[0].store(0.35f, std::memory_order_relaxed);

    // Two notes: 2.0 beats (8 steps) then 1.5 beats (6 steps), 8-beat loop.
    engine->pulsar_lick[0].scale_degree = 2;
    engine->pulsar_lick[0].duration = 2.0f;
    engine->pulsar_lick[0].velocity = 0.95f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick[1].scale_degree = 0;
    engine->pulsar_lick[1].duration = 1.5f;
    engine->pulsar_lick[1].velocity = 0.85f;
    engine->pulsar_lick[1].glide_rate = 0.35f;
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.05f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(2, std::memory_order_relaxed);  // publish last

    trigger_vibe_load(engine);
    engine->clock_bpm.store(80.0f, std::memory_order_relaxed);

    // Count vactrol blooms directly off the LPG envelope rather than guessing at
    // them from the audio peak: a glide sweeping the gate's cutoff also moves the
    // peak, which makes peak-jump counting produce false onsets. A bloom is the
    // envelope gain jumping up; anything else is the decay running.
    const int kBlocks = 1200;
    int note_ons = 0, blooms = 0;
    float prev_gain = 0.0f;
    for (int i = 0; i < kBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (ts.pending_retrig) note_ons++;
        float g = ts.osc_lpg.envelope.gain();
        if (g > prev_gain * 1.5f && g > 0.05f) blooms++;
        prev_gain = g;
    }
    orpheus_engine_destroy(engine);

    printf("  %.1f s / 2 cycles: note onsets=%d, vactrol blooms=%d\n",
           kBlocks * kBlockFrames / 48000.0f, note_ons, blooms);

    bool ok = true;
    if (note_ons < 3) {
        printf("  FAIL: fixture fired almost no notes (%d) - not a real test\n", note_ons);
        ok = false;
    }
    // The property under test: exactly one bloom per note onset. Blooming per hold
    // step gave 14 per cycle against 2 note onsets.
    if (blooms != note_ons) {
        printf("  FAIL: %d blooms for %d note onsets - the vactrol is retriggering off-note\n",
               blooms, note_ons);
        ok = false;
    }
    printf("Bloom per note onset: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Same hold-step re-gating, seen by the TIDES envelope instead of the LPG. The
// spurious voice_active drop reaches ExtractGateFlags as a falling+rising pair,
// so the envelope releases and re-attacks partway through a held note. Nothing
// about this is OSC-specific — it is the shared envelope path — but the OSC
// fixture is the one that can render a two-note phrase with no LPG in the way.
static bool test_pulsar_tides_envelope_holds_through_a_held_note() {
    printf("\n=== Test: TIDES envelope does not re-attack on hold steps ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);

    engine->pulsar_track_volume[0].store(0.12f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.12f, std::memory_order_relaxed);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    // Pin density near 1 the way an authored gesture does, so both notes always
    // fire. Without this the density roll drops whole notes (by design, since the
    // whole-note fix) and the count below measures the roll, not the envelope.
    engine->pulsar_track_density_override[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_min.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(1.0f, std::memory_order_relaxed);

    // TIDES envelope, LPG out of the way: the envelope is the only shaper.
    engine->pulsar_envelope_mode.store(1, std::memory_order_relaxed);
    engine->pulsar_track_lpg_mode[0].store(0, std::memory_order_relaxed);   // BYPASS
    engine->pulsar_track_lpg_mode_space[0].store(0, std::memory_order_relaxed);

    engine->pulsar_lick[0].scale_degree = 2;
    engine->pulsar_lick[0].duration = 2.0f;
    engine->pulsar_lick[0].velocity = 0.95f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick[1].scale_degree = 0;
    engine->pulsar_lick[1].duration = 1.5f;
    engine->pulsar_lick[1].velocity = 0.85f;
    engine->pulsar_lick[1].glide_rate = 0.35f;
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.05f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(2, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(80.0f, std::memory_order_relaxed);

    // Read the envelope level directly rather than inferring it from audio: a
    // 10 ms gate gap under a slow envelope barely moves the peak, but it is
    // plainly visible as the level falling and then climbing back.
    const int kBlocks = 1200;
    int recoveries = 0, gate_low_midnote = 0;
    float prev_env = 0.0f;
    bool prev_active = false;
    for (int i = 0; i < kBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (!ts.voice_active && prev_active && ts.in_hold) gate_low_midnote++;
        prev_active = ts.voice_active;
        float env = ts.tides_env_level;
        // An AR envelope under a continuously held note never climbs back after
        // falling. Any recovery is a release+attack the note did not ask for.
        if (i > 0 && prev_env > 0.05f && env > prev_env * 1.05f) recoveries++;
        prev_env = env;
    }
    orpheus_engine_destroy(engine);

    printf("  gate dropped mid-note %d times; envelope recoveries: %d\n",
           gate_low_midnote, recoveries);

    bool ok = (recoveries == 0);
    if (!ok) printf("  FAIL: envelope re-attacked %d times inside held notes\n", recoveries);
    printf("TIDES holds through a held note: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Density is rolled per gated STEP, but a multi-step note is one musical event:
// its head carries the trigger and the rest are hold continuations. When the head
// loses its roll the rejection clears in_hold, so the tail steps stop looking like
// continuations and fall through to the trigger path — firing the note late, from
// the wrong step, and without its glide (the reject also cleared prev_step_gated).
//
// One note spanning steps 0..7, density set mid so rolls genuinely fail. Every
// trigger must land on step 0; a trigger on any other step is the tail firing.
static bool test_pulsar_density_drops_whole_notes_not_note_heads() {
    printf("\n=== Test: a lost density roll drops the whole note, not just its head ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);       // AD: leaves pending_retrig readable
    // Mid density so some rolls fail and some pass — the whole point of the test.
    engine->pulsar_track_macros[0].energy_density_min.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(0.5f, std::memory_order_relaxed);

    // One note, 2.0 beats = 8 sequencer steps, head at step 0.
    engine->pulsar_lick[0].scale_degree = 0;
    engine->pulsar_lick[0].duration = 2.0f;
    engine->pulsar_lick[0].velocity = 0.9f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(1, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(140.0f, std::memory_order_relaxed);  // more cycles per run

    // A tail step is one whose predecessor is gated with hold=true. Checking the
    // live pattern rather than a fixed step index keeps the assertion honest when
    // the deja-vu reset regenerates the pattern partway through the run.
    int total = 0, tail_fires = 0, head_fires = 0;
    for (int i = 0; i < 4000; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (!ts.pending_retrig) continue;
        int ph = ts.playhead;
        if (ph < 0 || ph >= ts.step_count) continue;
        total++;
        int prev = (ph == 0) ? (ts.step_count - 1) : (ph - 1);
        if (ts.steps[prev].gate && ts.steps[prev].hold) tail_fires++;
        else head_fires++;
    }
    orpheus_engine_destroy(engine);

    printf("  triggers: %d total, %d on note heads, %d on hold-tail steps\n",
           total, head_fires, tail_fires);

    bool ok = true;
    if (total < 5) {
        printf("  FAIL: fixture produced almost no triggers (%d) - not a real test\n", total);
        ok = false;
    }
    if (tail_fires != 0) {
        printf("  FAIL: %d triggers fired from hold-tail steps instead of the note head\n", tail_fires);
        ok = false;
    }
    printf("Density drops whole notes: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_osc_tests() {
    printf("\n=== Pulsar OSC Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_pulsar_track_renders_osc()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_respects_trigger_offset()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_renders_the_whole_block()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_note_floor_reaches_the_generator()) suite_pass++; else suite_fail++;
    if (test_pulsar_opening_note_floor_lifts_the_first_pattern()) suite_pass++; else suite_fail++;
    if (test_pulsar_opening_note_floor_unset_matches_explicit_zero()) suite_pass++; else suite_fail++;
    if (test_pulsar_opening_note_floor_never_lowers_the_engine_floor()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_lpg_pluck_decays_under_held_gate()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_lpg_blooms_once_per_note_not_per_hold_step()) suite_pass++; else suite_fail++;
    if (test_pulsar_tides_envelope_holds_through_a_held_note()) suite_pass++; else suite_fail++;
    if (test_pulsar_density_drops_whole_notes_not_note_heads()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
