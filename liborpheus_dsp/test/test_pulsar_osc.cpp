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
#include <cstdlib>
#include <cstring>
#include <vector>

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

// A note's head is rolled (density for generated steps, hitProbability for lick
// steps), but a multi-step note is one musical event: its head carries the trigger
// and the rest are hold continuations. When the head loses its roll the rejection
// clears in_hold, so the tail steps stop looking like
// continuations and fall through to the trigger path — firing the note late, from
// the wrong step, and without its glide (the reject also cleared prev_step_gated).
//
// One note spanning steps 0..7, density set mid so rolls genuinely fail. Every
// trigger must land on step 0; a trigger on any other step is the tail firing.
static bool test_pulsar_density_drops_whole_notes_not_note_heads() {
    printf("\n=== Test: a lost head roll drops the whole note, not just its head ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);       // AD: leaves pending_retrig readable
    engine->pulsar_tension_inner_bars.store(1, std::memory_order_relaxed);  // tension 0: no lift
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);

    // One note, 2.0 beats = 8 sequencer steps, head at step 0. Lick steps skip the
    // energy_density roll, so the rejection under test is the hitProbability roll:
    // mid probability so some heads fail and some pass — the whole point of the test.
    engine->pulsar_lick[0].scale_degree = 0;
    engine->pulsar_lick[0].duration = 2.0f;
    engine->pulsar_lick[0].velocity = 0.9f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick[0].hit_probability = 0.5f;
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

// A lick step can carry a probability that its note fires at all, and tension
// lifts that probability toward certainty: effective_p = p + (1-p) * tension.
// The gesture starts uncertain and arrives as the section climbs.
//
// tension_intensity comes from (loop_count % inner_bars) / inner_bars, so
// inner_bars = 1 pins it at 0; pushing pulsar_tension_drive every block holds it
// near 1 (the drive is a decaying hold, exchanged to 0 each bar). Density is
// pinned so the density roll cannot be mistaken for this one.
static float lick_head_fire_rate(float hit_prob, bool high_tension) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);
    engine->pulsar_track_density_override[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_min.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_tension_inner_bars.store(1, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);

    // One note, 2.0 beats = 8 steps, head at step 0.
    engine->pulsar_lick[0].scale_degree = 0;
    engine->pulsar_lick[0].duration = 2.0f;
    engine->pulsar_lick[0].velocity = 0.9f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick[0].hit_probability = hit_prob;
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(1, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(150.0f, std::memory_order_relaxed);

    int fires = 0;
    for (int i = 0; i < 6000; i++) {
        if (high_tension) engine->pulsar_tension_drive.store(1.0f, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (ts.pending_retrig && ts.playhead == 0) fires++;
    }
    const int cycles = engine->pulsar_state->loop_count;
    orpheus_engine_destroy(engine);
    return (cycles > 0) ? static_cast<float>(fires) / static_cast<float>(cycles) : -1.0f;
}

static bool test_pulsar_lick_hit_probability_is_lifted_by_tension() {
    printf("\n=== Test: lick hitProbability gates the note, tension lifts it ===\n");
    const float always_lo = lick_head_fire_rate(1.0f, false);
    const float never_lo  = lick_head_fire_rate(0.0f, false);
    const float never_hi  = lick_head_fire_rate(0.0f, true);
    const float half_lo   = lick_head_fire_rate(0.5f, false);

    printf("  p=1.0 low tension  -> %.2f  (must be ~1: every existing lick relies on this)\n", always_lo);
    printf("  p=0.0 low tension  -> %.2f  (must be 0)\n", never_lo);
    printf("  p=0.0 high tension -> %.2f  (tension lifts it to near certain)\n", never_hi);
    printf("  p=0.5 low tension  -> %.2f  (roughly half)\n", half_lo);

    bool ok = true;
    if (always_lo < 0.99f) { printf("  FAIL: default probability must always fire\n"); ok = false; }
    if (never_lo > 0.01f)  { printf("  FAIL: p=0 at zero tension must never fire\n"); ok = false; }
    if (never_hi < 0.80f)  { printf("  FAIL: tension must lift p=0 toward certain\n"); ok = false; }
    if (half_lo < 0.30f || half_lo > 0.70f) { printf("  FAIL: p=0.5 should land near half\n"); ok = false; }
    printf("Lick hitProbability: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A Plaits-engine track (the OrpheusVoice path, not OSC) blooms its PLUCK vactrol on
// the voice's gate rising edge. A lick of back-to-back notes never drops the gate:
// each note's timer runs to the next head, which re-arms it before the underrun can
// clear voice_active. Every note after the first therefore arrived with no bloom and
// decayed into silence — the Fire Sky lead going quiet the moment its verse put it on
// the WSH/PLUCK slot. 2.0.5 only sounded right because its hold steps dropped the gate
// every step. The note-on (pending_retrig) is the onset; the gate edge is not.
static bool test_pulsar_voice_lpg_blooms_on_every_note_on_under_a_held_gate() {
    printf("\n=== Test: a Plaits voice's PLUCK blooms on every note-on, gate never falling ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);
    engine->pulsar_track_engine_edm[0].store(9, std::memory_order_relaxed);    // WSH
    engine->pulsar_track_engine_space[0].store(9, std::memory_order_relaxed);
    engine->pulsar_track_volume[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);       // AD
    engine->pulsar_track_density_override[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_min.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_mode[0].store(2, std::memory_order_relaxed);   // PLUCK
    engine->pulsar_track_lpg_mode_space[0].store(2, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour_space[0].store(0.5f, std::memory_order_relaxed);

    // Two 4-beat notes fill the 8-beat loop exactly: no rest, so the gate never falls.
    engine->pulsar_lick[0].scale_degree = 0;
    engine->pulsar_lick[0].duration = 4.0f;
    engine->pulsar_lick[0].velocity = 0.9f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick[1].scale_degree = 2;
    engine->pulsar_lick[1].duration = 4.0f;
    engine->pulsar_lick[1].velocity = 0.9f;
    engine->pulsar_lick[1].glide_rate = -1.0f;
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(2, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);  // a note = 2 s, longer than the pluck

    // An audible onset is the block peak jumping well above the previous block's:
    // the vactrol opening. A note that arrives without a bloom just keeps decaying.
    const int kBlocks = 1200;
    int note_ons = 0, audible = 0, gate_drops = 0;
    float prev_peak = 0.0f;
    bool prev_gate = false;
    for (int i = 0; i < kBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (ts.pending_retrig) note_ons++;
        if (prev_gate && !ts.voice_active) gate_drops++;
        prev_gate = ts.voice_active;
        float peak = 0.0f;
        for (int k = 0; k < kBlockFrames; k++) {
            float a = std::fabs(engine->pulsar_out_l[k]);
            if (a > peak) peak = a;
        }
        if (peak > 0.01f && peak > prev_peak * 4.0f) audible++;
        prev_peak = peak;
    }
    orpheus_engine_destroy(engine);

    printf("  %.1f s: note onsets=%d, audible blooms=%d, gate drops=%d\n",
           kBlocks * kBlockFrames / 48000.0f, note_ons, audible, gate_drops);

    bool ok = true;
    if (note_ons < 4) {
        printf("  FAIL: fixture fired almost no notes (%d) - not a real test\n", note_ons);
        ok = false;
    }
    // One drop is the load boundary; a drop per note would hand the voice its edge back.
    if (gate_drops > 1) {
        printf("  FAIL: the gate fell %d times - the fixture no longer holds it, so the test is vacuous\n", gate_drops);
        ok = false;
    }
    if (audible < note_ons) {
        printf("  FAIL: %d of %d note-ons arrived without a bloom (the voice only plucks on a gate edge)\n",
               note_ons - audible, note_ons);
        ok = false;
    }
    printf("Plaits voice PLUCK per note-on: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

struct RepickCounts {
    int retrigs = 0;       // blocks where the sequencer raised a note-on
    int hold_repicks = 0;  // ...of which landed on a hold continuation
    int blooms = 0;        // vactrol gain jumping up, read off the LPG envelope
    int by_beat_pos[4] = {};  // hold re-picks by playhead % 4: beat, e, &, a
    std::vector<long long> retrig_at;  // sample time of every note-on and re-pick
};

struct RepickNote {
    int8_t degree;  // negative = rest
    float beats;
    float velocity;
};

// A 16th on the beat, then 2.0- and 1.5-beat notes that both start on the "e"
// (steps 1 and 9; 12 hold steps per 8-beat cycle).
static constexpr RepickNote kHeadsOnTheE[] = {{4, 0.25f, 0.9f}, {2, 2.0f, 0.95f}, {0, 1.5f, 0.85f}};
static constexpr int kHeadsOnTheECount = static_cast<int>(sizeof(kHeadsOnTheE) / sizeof(kHeadsOnTheE[0]));
// Four 1.5-beat notes, each starting on the "&" after an 8th rest.
static constexpr RepickNote kHeadsOnTheAnd[] = {
    {-1, 0.5f, 0.0f}, {2, 1.5f, 0.9f}, {-1, 0.5f, 0.0f}, {4, 1.5f, 0.9f},
    {-1, 0.5f, 0.0f}, {2, 1.5f, 0.9f}, {-1, 0.5f, 0.0f}, {4, 1.5f, 0.9f},
};
static constexpr int kHeadsOnTheAndCount = static_cast<int>(sizeof(kHeadsOnTheAnd) / sizeof(kHeadsOnTheAnd[0]));

// An 8-beat lick loop on track 0. engine_id -1 is the OSC branch (ts.osc_lpg), 9 is WSH
// through OrpheusVoice, the Fire Sky lead's path. A retrig on a block whose previous step
// said "the next step continues me" is a hold re-pick. A swing >= 0 makes the clock rigid:
// that swing exactly, and energy 1 so the elastic tempo cannot drift.
static RepickCounts run_repick_fixture(int engine_id, int lpg_mode, float swing = -1.0f,
                                       float bpm = 80.0f,
                                       const RepickNote* notes = kHeadsOnTheE,
                                       int note_count = kHeadsOnTheECount, int blocks = 1200) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);
    engine->pulsar_track_engine_edm[0].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[0].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_volume[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);       // AD
    engine->pulsar_track_density_override[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_min.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_mode[0].store(lpg_mode, std::memory_order_relaxed);
    engine->pulsar_track_lpg_mode_space[0].store(lpg_mode, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_decay_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_lpg_colour_space[0].store(0.5f, std::memory_order_relaxed);
    if (swing >= 0.0f) {
        engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);  // drift range is (1 - energy) * 5%
        engine->pulsar_genre_swing.store(swing, std::memory_order_relaxed);
        engine->pulsar_track_macros[0].complexity_swing_min.store(0.0f, std::memory_order_relaxed);
        engine->pulsar_track_macros[0].complexity_swing_max.store(0.0f, std::memory_order_relaxed);
    }

    for (int n = 0; n < note_count; n++) {
        engine->pulsar_lick[n].scale_degree = notes[n].degree;
        engine->pulsar_lick[n].duration = notes[n].beats;
        engine->pulsar_lick[n].velocity = notes[n].velocity;
        engine->pulsar_lick[n].glide_rate = -1.0f;
    }
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(note_count, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(bpm, std::memory_order_relaxed);

    RepickCounts c;
    float prev_gain = 0.0f;
    bool was_in_hold = false;
    for (int i = 0; i < blocks; i++) {  // 1200 blocks at 80 BPM is a little over two cycles
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (ts.pending_retrig) {
            c.retrigs++;
            c.retrig_at.push_back(static_cast<long long>(i) * kBlockFrames + ts.trigger_offset);
            if (was_in_hold) {
                c.hold_repicks++;
                c.by_beat_pos[ts.playhead % 4]++;
            }
        }
        was_in_hold = ts.in_hold;
        const float g = (engine_id < 0) ? ts.osc_lpg.envelope.gain()
                                        : ts.voice.lpg_envelope_.gain();
        if (g > prev_gain * 1.5f && g > 0.05f) c.blooms++;
        prev_gain = g;
    }
    orpheus_engine_destroy(engine);
    return c;
}

// 2.0.5 re-picked every held lick note on each hold step by accident (the gate
// timer underran between steps, 7819cbd05), and Fire Sky's double-picked riff
// was that accident. LPG_PLUCK_REPEAT is the deliberate version: the hold path
// raises the same note-on the trigger path does, so the vactrol blooms per step,
// on both voice paths. Plain PLUCK keeps one bloom per note.
static bool test_pulsar_lpg_pluck_repeat_repicks_every_hold_step() {
    printf("\n=== Test: LPG_PLUCK_REPEAT re-picks a held note on every hold step ===\n");
    const RepickCounts plaits = run_repick_fixture(9, LPG_PLUCK_REPEAT);
    const RepickCounts osc = run_repick_fixture(-1, LPG_PLUCK_REPEAT);
    const RepickCounts control = run_repick_fixture(9, LPG_PLUCK);
    printf("  WSH  PLUCK_REPEAT: note-ons=%d hold re-picks=%d blooms=%d\n",
           plaits.retrigs, plaits.hold_repicks, plaits.blooms);
    printf("  OSC  PLUCK_REPEAT: note-ons=%d hold re-picks=%d blooms=%d\n",
           osc.retrigs, osc.hold_repicks, osc.blooms);
    printf("  WSH  PLUCK       : note-ons=%d hold re-picks=%d blooms=%d\n",
           control.retrigs, control.hold_repicks, control.blooms);

    bool ok = true;
    if (control.retrigs < 3) {
        printf("  FAIL: fixture fired almost no notes (%d) - not a real test\n", control.retrigs);
        ok = false;
    }
    if (control.hold_repicks != 0) {
        printf("  FAIL: plain PLUCK re-picked %d hold steps\n", control.hold_repicks);
        ok = false;
    }
    // 12 hold steps per cycle, two cycles rendered: a handful would be block
    // alignment, not the mode.
    const RepickCounts* runs[2] = { &plaits, &osc };
    const char* names[2] = { "WSH", "OSC" };
    for (int r = 0; r < 2; r++) {
        if (runs[r]->hold_repicks < 16) {
            printf("  FAIL: %s re-picked only %d hold steps\n", names[r], runs[r]->hold_repicks);
            ok = false;
        }
        if (runs[r]->blooms < runs[r]->retrigs) {
            printf("  FAIL: %s: %d of %d note-ons arrived without a bloom\n",
                   names[r], runs[r]->retrigs - runs[r]->blooms, runs[r]->retrigs);
            ok = false;
        }
    }
    printf("PLUCK_REPEAT re-picks holds: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The grids follow the beat, not the note. Both long notes start on the "e", so an
// 8th counted from the note-on would land on the "e" and "a" instead.
static bool test_pulsar_lpg_pluck_repeat_grids_follow_the_beat() {
    printf("\n=== Test: PLUCK_REPEAT grids re-pick held notes on their beat positions ===\n");
    struct Grid { int mode; const char* name; bool picks[4]; };  // beat, e, &, a
    const Grid grids[] = {
        { LPG_PLUCK_REPEAT,          "16th",     { true,  true,  true,  true  } },
        { LPG_PLUCK_REPEAT_8TH,      "8th",      { true,  false, true,  false } },
        { LPG_PLUCK_REPEAT_8TH_OFF,  "8th-off",  { false, false, true,  false } },
        { LPG_PLUCK_REPEAT_16TH_OFF, "16th-off", { false, true,  false, true  } },
    };
    const int engine_ids[2] = { 9, -1 };
    bool ok = true;
    for (const Grid& g : grids) {
        for (int engine_id : engine_ids) {
            const RepickCounts c = run_repick_fixture(engine_id, g.mode);
            const char* voice = (engine_id < 0) ? "OSC" : "WSH";
            printf("  %-8s %s: hold re-picks [beat e & a] = [%d %d %d %d], blooms=%d of %d note-ons\n",
                   g.name, voice, c.by_beat_pos[0], c.by_beat_pos[1], c.by_beat_pos[2],
                   c.by_beat_pos[3], c.blooms, c.retrigs);
            for (int p = 0; p < 4; p++) {
                // Two cycles put at least 4 re-picks on every position a grid selects.
                const bool wrong = g.picks[p] ? (c.by_beat_pos[p] < 4) : (c.by_beat_pos[p] != 0);
                if (wrong) {
                    printf("  FAIL: %s %s re-picked %d times at beat position %d\n",
                           g.name, voice, c.by_beat_pos[p], p);
                    ok = false;
                }
            }
            if (c.blooms < c.retrigs) {
                printf("  FAIL: %s %s: %d of %d note-ons arrived without a bloom\n",
                       g.name, voice, c.retrigs - c.blooms, c.retrigs);
                ok = false;
            }
        }
    }
    printf("PLUCK_REPEAT grids follow the beat: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// PLUCK_REPEAT_TRIPLET re-picks a held note on the beat and a third and two thirds into
// it. At 80 BPM a 16th is 9000 samples and a triplet 12000. Swing moves the heads on the
// "e" but none of the thirds, and the thirds right after those heads (12000, 84000) are
// too close to pick.
static bool test_pulsar_lpg_pluck_repeat_triplet_lands_on_beat_thirds() {
    printf("\n=== Test: PLUCK_REPEAT_TRIPLET re-picks on beat thirds, straight or swung ===\n");
    static constexpr long long kCycle = 32 * 9000;
    const float swings[2] = { 0.0f, 0.5f };
    bool ok = true;
    for (float swing : swings) {
        const RepickCounts c = run_repick_fixture(9, LPG_PLUCK_REPEAT_TRIPLET, swing);
        const long long e_shift = static_cast<long long>(swing * 0.5f * 9000.0f);
        const long long expected[] = {
            0, 9000 + e_shift, 81000 + e_shift,  // heads
            24000, 36000, 48000, 60000, 72000,    // the 2-beat note
            96000, 108000, 120000, 132000,        // the 1.5-beat note
        };
        const int n_expected = static_cast<int>(sizeof(expected) / sizeof(expected[0]));
        bool seen[16] = {};
        bool swing_ok = c.retrigs > 0;
        const long long t0 = c.retrig_at.empty() ? 0 : c.retrig_at[0];
        for (long long at : c.retrig_at) {
            const long long pos = (at - t0) % kCycle;
            int match = -1;
            for (int k = 0; k < n_expected; k++) {
                // Clock boundaries land a sample before the load downbeat's grid.
                if (std::llabs(pos - expected[k]) <= 2 || std::llabs(pos - expected[k] - kCycle) <= 2)
                    match = k;
            }
            if (match < 0) {
                printf("  FAIL: swing %.1f: a pick at cycle sample %lld is neither a head nor a beat third\n",
                       swing, pos);
                swing_ok = false;
            } else {
                seen[match] = true;
            }
        }
        for (int k = 0; k < n_expected; k++) {
            if (!seen[k]) {
                printf("  FAIL: swing %.1f: nothing picked at cycle sample %lld\n", swing, expected[k]);
                swing_ok = false;
            }
        }
        if (c.blooms < c.retrigs) {
            printf("  FAIL: swing %.1f: %d of %d picks arrived without a bloom\n",
                   swing, c.retrigs - c.blooms, c.retrigs);
            swing_ok = false;
        }
        printf("  swing %.1f: %d picks, %d blooms\n", swing, c.retrigs, c.blooms);
        ok &= swing_ok;
    }
    printf("PLUCK_REPEAT_TRIPLET lands on beat thirds: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A note starting on the "&" is exactly 2/3 of a 16th ahead of the beat's second third,
// so whether that third picks must not come down to sample rounding at a fractional step
// length. A skipped third shows as a 2-step gap from the note-on to the downbeat re-pick.
static bool test_pulsar_lpg_pluck_repeat_triplet_picks_the_third_after_an_and_note() {
    printf("\n=== Test: PLUCK_REPEAT_TRIPLET picks the third after a note on the \"&\" ===\n");
    const float bpms[] = {97.0f, 101.0f, 103.0f, 107.0f, 113.0f, 131.0f};
    bool ok = true;
    for (float bpm : bpms) {
        const RepickCounts c = run_repick_fixture(-1, LPG_PLUCK_REPEAT_TRIPLET, 0.0f, bpm,
                                                  kHeadsOnTheAnd, kHeadsOnTheAndCount, 900);
        const double samples_per_step = 720000.0 / bpm;  // 48 kHz, 4 steps per beat
        int skipped = 0;
        for (size_t i = 1; i < c.retrig_at.size(); i++) {
            const double steps = (c.retrig_at[i] - c.retrig_at[i - 1]) / samples_per_step;
            if (steps > 1.9 && steps < 2.1) skipped++;
        }
        printf("  %5.1f BPM: %zu picks, %d thirds skipped\n", bpm, c.retrig_at.size(), skipped);
        if (c.retrig_at.size() < 20) {
            printf("  FAIL: %.1f BPM fired almost nothing - not a real test\n", bpm);
            ok = false;
        }
        if (skipped != 0) {
            printf("  FAIL: %.1f BPM skipped %d thirds after a note on the \"&\"\n", bpm, skipped);
            ok = false;
        }
    }
    printf("PLUCK_REPEAT_TRIPLET third after an \"&\" note: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The playback energy_density roll is the generator's second gate: the first is the
// TrackVoice density that decides which steps get written. An authored lick is not a
// generated pattern — its steps ARE the part, and hitProbability is the channel a vibe
// uses when it wants a note to be a maybe. Rolling energy_density on lick heads on top of
// that double-gated every hook, and since the whole-note fix a lost head is a hole the
// length of the note rather than a one-step delay. Lick heads therefore skip the roll.
//
// Density pinned to 0 makes fire_prob = vel * 0.5 = 0.45: a generated head would fire
// well under half the time, so a lick head firing every cycle is unambiguous.
static bool test_pulsar_lick_heads_skip_the_energy_density_roll() {
    printf("\n=== Test: lick note heads skip the energy_density roll ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_min.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_tension_inner_bars.store(1, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);

    // One note, 2.0 beats = 8 steps, head at step 0, default hitProbability (1.0).
    engine->pulsar_lick[0].scale_degree = 0;
    engine->pulsar_lick[0].duration = 2.0f;
    engine->pulsar_lick[0].velocity = 0.9f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick[0].hit_probability = 1.0f;
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(1, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(150.0f, std::memory_order_relaxed);

    int fires = 0;
    for (int i = 0; i < 6000; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (ts.pending_retrig && ts.playhead == 0) fires++;
    }
    const int cycles = engine->pulsar_state->loop_count;
    orpheus_engine_destroy(engine);

    printf("  head fired on %d of %d cycles at energy_density 0\n", fires, cycles);
    bool ok = true;
    if (cycles < 5) {
        printf("  FAIL: fixture ran almost no cycles (%d) - not a real test\n", cycles);
        ok = false;
    }
    if (fires < cycles) {
        printf("  FAIL: %d lick heads lost the energy_density roll\n", cycles - fires);
        ok = false;
    }
    printf("Lick heads skip density roll: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Which authored channel the probability under test travels. The single lick reaches
// C++ through its own per-step port block; the rotation pool and the bass line ride
// their own transports, and each needed a parallel block of its own to carry this.
enum class ProbChannel { Pool, BassLine };

// Same measurement as lick_head_fire_rate, but the probability is authored on a
// channel OTHER than the single lick. The single lick is always set up as an
// always-firing decoy, so a pass here cannot come from the channel silently falling
// back to it — if the transport drops the probability, the rate returns 1.0.
static float authored_channel_fire_rate(float hit_prob, ProbChannel channel) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_osc_unit(unit);
    setup_osc_track0(engine);
    engine->pulsar_track_role[0].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[0].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);
    engine->pulsar_track_density_override[0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_min.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[0].energy_density_max.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_tension_inner_bars.store(1, std::memory_order_relaxed);  // pins tension at 0
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);

    // Decoy: one note, 2.0 beats = 8 steps, head at step 0, always fires.
    engine->pulsar_lick[0].scale_degree = 0;
    engine->pulsar_lick[0].duration = 2.0f;
    engine->pulsar_lick[0].velocity = 0.9f;
    engine->pulsar_lick[0].glide_rate = -1.0f;
    engine->pulsar_lick[0].hit_probability = 1.0f;
    engine->pulsar_lick_loop_length.store(8, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(1, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);

    if (channel == ProbChannel::Pool) {
        // Slot 0, step 0: the same figure, carrying the probability under test.
        engine->pulsar_lick_pool_data[0] = 0.0f;   // degree
        engine->pulsar_lick_pool_data[1] = 2.0f;   // duration
        engine->pulsar_lick_pool_data[2] = 0.9f;   // velocity
        engine->pulsar_lick_pool_data[3] = -1.0f;  // glide
        engine->pulsar_lick_pool_hit_prob[0] = hit_prob;
        engine->pulsar_lick_pool_len[0]  = 1;
        engine->pulsar_lick_pool_loop[0] = 8;
        engine->pulsar_lick_pool_count.store(1, std::memory_order_release);
    } else {
        engine->pulsar_track_lick_source[0].store(1, std::memory_order_relaxed);  // BASS
        engine->pulsar_bass_line[0].scale_degree = 0;
        engine->pulsar_bass_line[0].duration = 2.0f;
        engine->pulsar_bass_line[0].velocity = 0.9f;
        engine->pulsar_bass_line[0].glide_rate = -1.0f;
        engine->pulsar_bass_line[0].hit_probability = hit_prob;
        engine->pulsar_bass_line_mutation.store(0.0f, std::memory_order_relaxed);
        engine->pulsar_bass_line_octave.store(-1, std::memory_order_relaxed);
        engine->pulsar_bass_line_loop.store(8, std::memory_order_relaxed);
        engine->pulsar_bass_line_length.store(1, std::memory_order_release);
    }

    trigger_vibe_load(engine);
    engine->clock_bpm.store(150.0f, std::memory_order_relaxed);

    int fires = 0;
    for (int i = 0; i < 6000; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[0];
        if (ts.pending_retrig && ts.playhead == 0) fires++;
    }
    const int cycles = engine->pulsar_state->loop_count;
    orpheus_engine_destroy(engine);
    return (cycles > 0) ? static_cast<float>(fires) / static_cast<float>(cycles) : -1.0f;
}

static bool check_channel_carries_probability(const char* label, ProbChannel channel) {
    const float always = authored_channel_fire_rate(1.0f, channel);
    const float never  = authored_channel_fire_rate(0.0f, channel);
    const float half   = authored_channel_fire_rate(0.5f, channel);
    printf("  %s: p=1.0 -> %.2f   p=0.0 -> %.2f   p=0.5 -> %.2f\n",
           label, always, never, half);

    bool ok = true;
    if (always < 0.99f) {
        printf("  FAIL: %s p=1 must always fire\n", label); ok = false;
    }
    // The decoy single lick fires every cycle, so a dropped probability reads as 1.0
    // here. This is the assert that the parallel transport actually arrived.
    if (never > 0.01f) {
        printf("  FAIL: %s p=0 must never fire (transport dropped the probability?)\n", label);
        ok = false;
    }
    if (half < 0.30f || half > 0.70f) {
        printf("  FAIL: %s p=0.5 should land near half\n", label); ok = false;
    }
    return ok;
}

static bool test_pulsar_authored_channels_carry_hit_probability() {
    printf("\n=== Test: the pool and bass-line channels carry hitProbability ===\n");
    bool ok = check_channel_carries_probability("rotation pool", ProbChannel::Pool);
    ok &= check_channel_carries_probability("bass line", ProbChannel::BassLine);
    printf("Authored-channel hitProbability: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// An unpushed pool slot must fall open to "always fires". The bank is plain memory
// reused across vibe loads, and 0 is a MEANINGFUL probability here, so a zero-init
// slot would silence a lick that never authored one.
static bool test_pulsar_unpushed_pool_probability_defaults_to_firing() {
    printf("\n=== Test: an unpushed pool hit probability defaults to firing ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int i = 0; i < OrpheusEngine::kMaxLickPool * OrpheusEngine::kMaxLickSteps; i++) {
        if (engine->pulsar_lick_pool_hit_prob[i] != 1.0f) {
            printf("  FAIL: slot %d seeded %.2f, expected 1.0\n",
                   i, engine->pulsar_lick_pool_hit_prob[i]);
            ok = false;
            break;
        }
    }
    orpheus_engine_destroy(engine);
    printf("Unpushed pool probability: %s\n", ok ? "PASS" : "FAIL");
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
    if (test_pulsar_lick_hit_probability_is_lifted_by_tension()) suite_pass++; else suite_fail++;
    if (test_pulsar_lick_heads_skip_the_energy_density_roll()) suite_pass++; else suite_fail++;
    if (test_pulsar_voice_lpg_blooms_on_every_note_on_under_a_held_gate()) suite_pass++; else suite_fail++;
    if (test_pulsar_lpg_pluck_repeat_repicks_every_hold_step()) suite_pass++; else suite_fail++;
    if (test_pulsar_lpg_pluck_repeat_grids_follow_the_beat()) suite_pass++; else suite_fail++;
    if (test_pulsar_lpg_pluck_repeat_triplet_lands_on_beat_thirds()) suite_pass++; else suite_fail++;
    if (test_pulsar_lpg_pluck_repeat_triplet_picks_the_third_after_an_and_note()) suite_pass++; else suite_fail++;
    if (test_pulsar_authored_channels_carry_hit_probability()) suite_pass++; else suite_fail++;
    if (test_pulsar_unpushed_pool_probability_defaults_to_firing()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
