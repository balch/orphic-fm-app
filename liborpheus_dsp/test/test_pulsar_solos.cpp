#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_bar_strategy.h"
#include "../src/pulsar_solo.h"
#include "../src/pulsar_handoff.h"
#include <cstdio>
#include <cmath>
#include <vector>

// ── Unit tests for solo system (modifiers, Markov generation, handoff) ──

static bool test_default_solo_behavior_profiles() {
    printf("\n=== Test: Default solo behavior profiles differ ===\n");

    auto rhythm = default_solo_behavior(ENV_PROFILE_RHYTHM);
    auto melodic = default_solo_behavior(ENV_PROFILE_MELODIC);
    auto effect = default_solo_behavior(ENV_PROFILE_EFFECT);
    auto wild = default_solo_behavior(ENV_PROFILE_WILD);

    // Rhythm: unison-heavy (interval 7 = unison should dominate)
    bool rhythm_ok = rhythm.interval_weights[7] > 0.3f;
    printf("  Rhythm weights[7] (unison) = %.3f -- %s\n",
           rhythm.interval_weights[7], rhythm_ok ? "OK" : "FAIL");

    // Melodic: stepwise (weights[6] and weights[8] significant for +/-1)
    bool melodic_ok = melodic.interval_weights[6] > 0.2f && melodic.interval_weights[8] > 0.2f;
    printf("  Melodic weights[6] (down) = %.3f, weights[8] (up) = %.3f -- %s\n",
           melodic.interval_weights[6], melodic.interval_weights[8],
           melodic_ok ? "OK" : "FAIL");

    // Effect: chromatic_passing should be significant
    bool effect_ok = effect.chromatic_passing > 0.15f;
    printf("  Effect chromatic_passing = %.3f -- %s\n",
           effect.chromatic_passing, effect_ok ? "OK" : "FAIL");

    // Wild: weights should be relatively uniform (spread < 0.02 between min/max)
    float wild_min = 1.0f, wild_max = 0.0f;
    for (int i = 0; i < kMarkovIntervals; i++) {
        if (wild.interval_weights[i] < wild_min) wild_min = wild.interval_weights[i];
        if (wild.interval_weights[i] > wild_max) wild_max = wild.interval_weights[i];
    }
    bool wild_ok = (wild_max - wild_min) < 0.02f;
    printf("  Wild weights range: %.3f to %.3f, spread = %.3f -- %s\n",
           wild_min, wild_max, wild_max - wild_min, wild_ok ? "OK" : "FAIL");

    bool pass = rhythm_ok && melodic_ok && effect_ok && wild_ok;
    printf("  Solo behavior profiles: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_default_ducking_profiles() {
    printf("\n=== Test: Default ducking profiles differ ===\n");

    auto rhythm = default_ducking(ENV_PROFILE_RHYTHM);
    auto wild = default_ducking(ENV_PROFILE_WILD);
    auto effect = default_ducking(ENV_PROFILE_EFFECT);

    // Rhythm ducks less than wild
    bool rhythm_less = rhythm.volume_reduction < wild.volume_reduction;
    printf("  Rhythm volume_reduction = %.3f, Wild = %.3f -- %s\n",
           rhythm.volume_reduction, wild.volume_reduction,
           rhythm_less ? "OK" : "FAIL");

    // Effect has more reverb than rhythm
    bool effect_more_reverb = effect.reverb_boost > rhythm.reverb_boost;
    printf("  Effect reverb_boost = %.3f, Rhythm = %.3f -- %s\n",
           effect.reverb_boost, rhythm.reverb_boost,
           effect_more_reverb ? "OK" : "FAIL");

    bool pass = rhythm_less && effect_more_reverb;
    printf("  Ducking profiles: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_solo_modifier_application() {
    printf("\n=== Test: Solo modifiers application ===\n");

    PulsarTrackState tracks[kNumPulsarTracks] = {};

    auto solo_behavior = default_solo_behavior(ENV_PROFILE_MELODIC);
    auto ducking = default_ducking(ENV_PROFILE_MELODIC);

    // Apply modifiers with soloist=3
    apply_solo_modifiers(tracks, 3, solo_behavior, ducking, kNumPulsarTracks);

    // Track 3: soloist
    bool soloist_ok = tracks[3].is_soloist == true
                   && tracks[3].solo_volume_mod > 0.0f;
    printf("  Track 3 is_soloist=%d, solo_volume_mod=%.3f -- %s\n",
           tracks[3].is_soloist, tracks[3].solo_volume_mod,
           soloist_ok ? "OK" : "FAIL");

    // Track 0: non-soloist (should have negative mods for ducking)
    bool nonsoloist_ok = tracks[0].is_soloist == false
                      && tracks[0].solo_volume_mod < 0.0f;
    printf("  Track 0 is_soloist=%d, solo_volume_mod=%.3f -- %s\n",
           tracks[0].is_soloist, tracks[0].solo_volume_mod,
           nonsoloist_ok ? "OK" : "FAIL");

    bool pass = soloist_ok && nonsoloist_ok;
    printf("  Modifier application: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_clear_solo_modifiers() {
    printf("\n=== Test: Clear solo modifiers ===\n");

    PulsarTrackState tracks[kNumPulsarTracks] = {};

    auto solo_behavior = default_solo_behavior(ENV_PROFILE_MELODIC);
    auto ducking = default_ducking(ENV_PROFILE_MELODIC);

    // Apply modifiers
    apply_solo_modifiers(tracks, 2, solo_behavior, ducking, kNumPulsarTracks);

    // Clear them
    clear_solo_modifiers(tracks, kNumPulsarTracks);

    // Check all zeroed
    bool all_zero = true;
    for (int i = 0; i < kNumPulsarTracks; i++) {
        if (tracks[i].solo_volume_mod != 0.0f
            || tracks[i].solo_density_mod != 0.0f
            || tracks[i].solo_ghost_mod != 0.0f
            || tracks[i].solo_fill_mod != 0.0f
            || tracks[i].solo_reverb_mod != 0.0f
            || tracks[i].solo_simplify == true
            || tracks[i].is_soloist == true) {
            all_zero = false;
            break;
        }
    }

    printf("  All modifiers zeroed: %s\n", all_zero ? "YES" : "NO");
    printf("  Clear solo modifiers: %s\n", all_zero ? "PASS" : "FAIL");
    return all_zero;
}

static bool test_markov_note_generation() {
    printf("\n=== Test: Markov note generation ===\n");

    auto behavior = default_solo_behavior(ENV_PROFILE_MELODIC);
    uint32_t seed = 12345;
    int scale_count = 7;
    int current_degree = 0;

    int valid_notes = 0;
    int rests = 0;

    for (int i = 0; i < 100; i++) {
        int next = markov_next_note(behavior, current_degree, scale_count, 0.5f, seed);
        if (next == -1) {
            rests++;
        } else if (next == -2) {
            // hold: don't change current_degree
        } else {
            current_degree = next;
            valid_notes++;
        }
    }

    bool valid_ok = valid_notes > 30;
    bool rest_low = rests > 5;
    bool rest_high = rests < 50;

    printf("  Generated 100 notes: valid=%d, rests=%d\n", valid_notes, rests);
    printf("  Valid > 30: %s, Rests > 5: %s, Rests < 50: %s\n",
           valid_ok ? "OK" : "FAIL", rest_low ? "OK" : "FAIL", rest_high ? "OK" : "FAIL");

    bool pass = valid_ok && rest_low && rest_high;
    printf("  Markov generation: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_markov_density_curve() {
    printf("\n=== Test: Markov density curve progression ===\n");

    // Behavior with no rests/holds to isolate density gating
    auto behavior = default_solo_behavior(ENV_PROFILE_MELODIC);
    behavior.rest_probability = 0.0f;
    behavior.hold_probability = 0.0f;

    uint32_t seed = 54321;
    int scale_count = 7;
    int current_degree = 0;

    // Early phase (progress=0)
    int early_valid = 0;
    for (int i = 0; i < 200; i++) {
        int next = markov_next_note(behavior, current_degree, scale_count, 0.0f, seed);
        if (next >= 0) {
            current_degree = next;
            early_valid++;
        }
    }

    // Late phase (progress=1)
    seed = 54321;
    current_degree = 0;
    int late_valid = 0;
    for (int i = 0; i < 200; i++) {
        int next = markov_next_note(behavior, current_degree, scale_count, 1.0f, seed);
        if (next >= 0) {
            current_degree = next;
            late_valid++;
        }
    }

    // Late should generate more notes (higher density curve)
    bool late_more = late_valid > early_valid;

    printf("  Early (progress=0): %d valid notes\n", early_valid);
    printf("  Late (progress=1): %d valid notes\n", late_valid);
    printf("  Late > Early: %s\n", late_more ? "YES" : "NO");

    printf("  Density curve: %s\n", late_more ? "PASS" : "FAIL");
    return late_more;
}

static bool test_improvisers_handoff_biases_weights() {
    printf("\n=== Test: IMPROVISERS handoff biases weights ===\n");

    BandSoloState state{};

    // Record an ascending phrase (0, 1, 2, 3, 4)
    state.last_phrase[0] = 0;
    state.last_phrase[1] = 1;
    state.last_phrase[2] = 2;
    state.last_phrase[3] = 3;
    state.last_phrase[4] = 4;
    state.phrase_cursor = 5;

    auto behavior = default_solo_behavior(ENV_PROFILE_MELODIC);
    uint32_t seed = 99999;

    // Original weights[8] (up/+1) and weights[6] (down/-1)
    float orig_up = behavior.interval_weights[8];
    float orig_down = behavior.interval_weights[6];

    // Apply handoff with carryover=0.7
    improvisers_handoff(state, 0.7f, behavior, seed);

    // After handoff, step_up should exceed step_down
    // because the phrase heavily favors +1 intervals (0->1, 1->2, etc)
    bool step_up_biased = behavior.interval_weights[8] > behavior.interval_weights[6];

    printf("  Original: up[8]=%.4f, down[6]=%.4f\n", orig_up, orig_down);
    printf("  After handoff (0.7): up[8]=%.4f, down[6]=%.4f\n",
           behavior.interval_weights[8], behavior.interval_weights[6]);
    printf("  up > down: %s\n", step_up_biased ? "YES" : "NO");

    printf("  Handoff bias: %s\n", step_up_biased ? "PASS" : "FAIL");
    return step_up_biased;
}

static bool test_record_solo_note() {
    printf("\n=== Test: Record solo note ===\n");

    BandSoloState state{};

    // Record 3 notes
    record_solo_note(state, 0);
    record_solo_note(state, 2);
    record_solo_note(state, 4);

    bool cursor_ok = state.phrase_cursor == 3;
    bool values_ok = state.last_phrase[0] == 0
                  && state.last_phrase[1] == 2
                  && state.last_phrase[2] == 4;

    printf("  Recorded 3 notes: [0, 2, 4]\n");
    printf("  phrase_cursor = %d (expect 3): %s\n", state.phrase_cursor,
           cursor_ok ? "OK" : "FAIL");
    printf("  Values match: %s\n", values_ok ? "OK" : "FAIL");

    bool pass = cursor_ok && values_ok;
    printf("  Record solo note: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_density_curve_shape() {
    printf("\n=== Test: Density curve shape affects note generation ===\n");

    SoloBehaviorParam behavior{};
    const float kMelodicWeights[kMarkovIntervals] = {
        0.02f, 0.03f, 0.05f, 0.08f, 0.10f, 0.15f, 0.25f,
        0.10f, 0.25f, 0.15f, 0.10f, 0.08f, 0.05f, 0.03f, 0.02f
    };
    std::memcpy(behavior.interval_weights, kMelodicWeights, sizeof(kMelodicWeights));
    behavior.rest_probability = 0.0f;
    behavior.hold_probability = 0.0f;
    behavior.density_curve_min = 0.2f;
    behavior.density_curve_max = 0.9f;
    behavior.chromatic_passing = 0.0f;

    // Front-loaded: more notes at start (progress=0.1), fewer at end (progress=0.9)
    behavior.density_curve_shape = -0.8f;
    int front_notes_early = 0, front_notes_late = 0;
    for (int trial = 0; trial < 200; trial++) {
        uint32_t seed_e = 1000 + trial;
        uint32_t seed_l = 2000 + trial;
        int r1 = markov_next_note(behavior, 0, 7, 0.1f, seed_e);
        int r2 = markov_next_note(behavior, 0, 7, 0.9f, seed_l);
        if (r1 >= 0) front_notes_early++;
        if (r2 >= 0) front_notes_late++;
    }

    // Back-loaded: fewer notes at start, more at end
    behavior.density_curve_shape = 0.8f;
    int back_notes_early = 0, back_notes_late = 0;
    for (int trial = 0; trial < 200; trial++) {
        uint32_t seed_e = 1000 + trial;
        uint32_t seed_l = 2000 + trial;
        int r1 = markov_next_note(behavior, 0, 7, 0.1f, seed_e);
        int r2 = markov_next_note(behavior, 0, 7, 0.9f, seed_l);
        if (r1 >= 0) back_notes_early++;
        if (r2 >= 0) back_notes_late++;
    }

    bool front_ok = front_notes_early > front_notes_late;
    bool back_ok = back_notes_late > back_notes_early;
    printf("  Front-loaded: early=%d late=%d -- %s\n",
           front_notes_early, front_notes_late, front_ok ? "OK" : "FAIL");
    printf("  Back-loaded: early=%d late=%d -- %s\n",
           back_notes_early, back_notes_late, back_ok ? "OK" : "FAIL");

    bool ok = front_ok && back_ok;
    printf("  Density curve shape: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_drone_interval_preset() {
    printf("\n=== Test: DRONE interval preset ===\n");

    auto drone = default_solo_behavior(ENV_PROFILE_DRONE);

    // DRONE: stepwise dominant (weights[6] and weights[8] for +-1 should be >= 0.2)
    bool stepwise_ok = drone.interval_weights[6] >= 0.2f && drone.interval_weights[8] >= 0.2f;
    printf("  weights[6]=%.3f [8]=%.3f -- %s\n",
           drone.interval_weights[6], drone.interval_weights[8],
           stepwise_ok ? "OK" : "FAIL");

    // High hold probability
    bool hold_ok = drone.hold_probability >= 0.3f;
    printf("  hold_probability=%.3f -- %s\n", drone.hold_probability, hold_ok ? "OK" : "FAIL");

    // Front-loaded density curve
    bool shape_ok = drone.density_curve_shape < 0.0f;
    printf("  density_curve_shape=%.3f -- %s\n", drone.density_curve_shape, shape_ok ? "OK" : "FAIL");

    bool ok = stepwise_ok && hold_ok && shape_ok;
    printf("  DRONE preset: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// push_lickbuilder_band_arrangement lives in test_pulsar_helpers.h (shared
// with test_pulsar_texture.cpp).

// ── JAM-1 integration helper ─────────────────────────────────────────
//
// Same band as push_lickbuilder_band_arrangement but section 1 uses
// solo_mode = JAM instead of LICK_BUILDER.  The vibe has NO authored lick
// (pulsar_lick_length = 0), so the fallback path must synthesize one from
// the lead member's melodic track.
static void push_jam_band_arrangement(OrpheusEngine* engine, int lead_track) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = kSectionDataFields;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
    }
    // Section 0: 1-bar NONE intro, transitions to section 1.
    section_data[0] = 1;     // bars_min
    section_data[1] = 1;     // bars_max
    section_data[2] = 1;     // bar_step
    section_data[3] = 0.8f;  // recency_decay
    section_data[4] = 1;     // transition_count
    section_data[9] = 0;     // solo_mode = NONE

    // Section 1: 32-bar JAM, self-loops.
    int s1 = kSectionStride;
    section_data[s1 + 0] = 32;    // bars_min
    section_data[s1 + 1] = 32;    // bars_max
    section_data[s1 + 2] = 1;     // bar_step
    section_data[s1 + 3] = 0.8f;  // recency_decay
    section_data[s1 + 4] = 1;     // transition_count
    section_data[s1 + 9]  = static_cast<float>(static_cast<int>(SoloModeId::JAM));
    section_data[s1 + 10] = 1.0f; // solo_probability
    section_data[s1 + 11] = 1.0f; // solo_mutation_rate (max so the live lick clearly evolves)
    section_data[s1 + 12] = 0.5f; // solo_lick_influence
    section_data[s1 + 13] = 2;    // solo_bars_min
    section_data[s1 + 14] = 4;    // solo_bars_max
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // Transitions: s0 -> {1:1.0}, s1 -> {1:1.0} (self-loop).
    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;  // s0 edge 0 -> section 1
    trans[24] = 1; trans[25] = 1.0f; trans[26] = 0;  // s1 edge 0 -> section 1
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Band config: member 0 = drums (always_active), member 1 = melodic lead.
    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(2, std::memory_order_relaxed);
    for (int i = 0; i < 96; i++)
        engine->pulsar_band_member_data[i].store(0.0f, std::memory_order_relaxed);
    // member 0: drums tracks 0,1,2 always_active
    engine->pulsar_band_member_data[0 + 0].store(3.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 1].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 2].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 3].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 9].store(1.0f, std::memory_order_relaxed);  // always_active
    engine->pulsar_band_member_data[0 + 10].store(0.7f, std::memory_order_relaxed); // loudness
    engine->pulsar_band_member_data[0 + 11].store(0.2f, std::memory_order_relaxed); // creativity
    // member 1: melodic lead on lead_track
    engine->pulsar_band_member_data[12 + 0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 1].store(static_cast<float>(lead_track), std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 9].store(0.0f, std::memory_order_relaxed);  // not always_active
    engine->pulsar_band_member_data[12 + 10].store(0.8f, std::memory_order_relaxed); // loudness
    engine->pulsar_band_member_data[12 + 11].store(0.9f, std::memory_order_relaxed); // creativity (high)
    // Keep the lead leading (member 1 -> member 1). Stride-N=2.
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    engine->pulsar_band_handoff_matrix[1 * 2 + 1].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_min.store(64, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_max.store(64, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_min.store(2, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_max.store(4, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    // Set non-zero density for the lead_track so generate_jam_solo_line fires notes.
    // Layout: 15 floats/track; [12]=density_curve_min, [13]=density_curve_max.
    {
        int tb = lead_track * 15;
        engine->pulsar_track_solo_behavior[tb + 12].store(0.6f, std::memory_order_relaxed);
        engine->pulsar_track_solo_behavior[tb + 13].store(0.9f, std::memory_order_relaxed);
    }
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// Authored interval distribution for the handoff fixture's two soloists. Shared
// with the lickInfluence test, which asserts influence=0 leaves it untouched.
static const float kJamStepwiseWeights[kMarkovIntervals] = {
    0.01f, 0.01f, 0.02f, 0.04f, 0.07f, 0.11f, 0.18f,
    0.12f,
    0.18f, 0.11f, 0.07f, 0.04f, 0.02f, 0.01f, 0.01f
};

// ── JAM handoff helper ───────────────────────────────────────────────
//
// A JAM band that actually HANDS OFF: drums (always_active) plus TWO melodic
// members that trade the lead every 2 bars. RustBelt and friends cannot exercise
// this — they declare Jam with no band at all — so the carryover and the
// handoff-fill paths need a fixture that owns one.
//
// `lick_influence` lands in section slot 12 (SoloMode.Jam.lickInfluence).
static void push_jam_handoff_arrangement(OrpheusEngine* engine, float lick_influence) {
    const int kLeadA = 4;   // KEYS  (MELODIC in setup_fixture_baseline)
    const int kLeadB = 3;   // BASS  (MELODIC in setup_fixture_baseline)

    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = kSectionDataFields;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
    }
    // Section 0: 1-bar NONE intro -> section 1.
    section_data[0] = 1; section_data[1] = 1; section_data[2] = 1;
    section_data[3] = 0.8f; section_data[4] = 1; section_data[9] = 0;

    // Section 1: 32-bar JAM, self-loops.
    int s1 = kSectionStride;
    section_data[s1 + 0] = 32; section_data[s1 + 1] = 32; section_data[s1 + 2] = 1;
    section_data[s1 + 3] = 0.8f; section_data[s1 + 4] = 1;
    section_data[s1 + 9]  = static_cast<float>(static_cast<int>(SoloModeId::JAM));
    section_data[s1 + 10] = 1.0f;             // solo_probability
    section_data[s1 + 11] = 1.0f;             // solo_mutation_rate
    section_data[s1 + 12] = lick_influence;   // Jam lickInfluence -> carryover
    section_data[s1 + 13] = 2; section_data[s1 + 14] = 4;
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;   // s0 -> s1
    trans[24] = 1; trans[25] = 1.0f; trans[26] = 0;   // s1 -> s1
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Three members: 0 = drums (tracks 0,1,2, always_active), 1 = lead A, 2 = lead B.
    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(3, std::memory_order_relaxed);
    for (int i = 0; i < 96; i++)
        engine->pulsar_band_member_data[i].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0].store(3.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[1].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[2].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[3].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[9].store(1.0f, std::memory_order_relaxed);   // always_active
    engine->pulsar_band_member_data[10].store(0.7f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[11].store(0.2f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 1].store(static_cast<float>(kLeadA), std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 10].store(0.8f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 11].store(0.9f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[24 + 0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[24 + 1].store(static_cast<float>(kLeadB), std::memory_order_relaxed);
    engine->pulsar_band_member_data[24 + 10].store(0.8f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[24 + 11].store(0.9f, std::memory_order_relaxed);

    // Handoff matrix is packed stride-N (N=3) by Kotlin: members 1 and 2 trade.
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    engine->pulsar_band_handoff_matrix[1 * 3 + 2].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_handoff_matrix[2 * 3 + 1].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_min.store(2, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_max.store(2, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_min.store(1, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_max.store(1, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int lt : {kLeadA, kLeadB}) {
        int tb = lt * 15;
        engine->pulsar_track_solo_behavior[tb + 12].store(0.6f, std::memory_order_relaxed);
        engine->pulsar_track_solo_behavior[tb + 13].store(0.9f, std::memory_order_relaxed);
    }
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    // A REAL interval distribution, so lickInfluence=0 leaves something meaningful
    // in place rather than an all-zero row that carryover trivially differs from.
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);
    for (int lt : {kLeadA, kLeadB})
        for (int i = 0; i < kMarkovIntervals; i++)
            engine->pulsar_track_solo_markov[lt * 15 + i].store(
                kJamStepwiseWeights[i], std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// ── JAM-1: Jam-mode solo generates evolving notes (markov, NOT live-lick)
// Updated after Task 2: Jam no longer uses the live-lick path. Instead it uses
// generate_jam_solo_line() directly. Test verifies notes fire and evolve
// across bars (markov walk produces variation).
static bool test_jam_solo_shared_line() {
    printf("\n=== Test: JAM-1 Jam solo generates evolving notes (markov path) ===\n");
    const int kLeadTrack = 4;  // KEYS
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);  // no mutate_patterns drift
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(0, std::memory_order_release);   // NO authored lick
    push_jam_band_arrangement(engine, kLeadTrack);  // a JAM solo section w/ kLeadTrack as a band member
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    std::vector<std::vector<int>> bar_notes; int last_loop = -1;
    for (int i = 0; i < 4000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        int loop = ps->loop_count;
        if (loop != last_loop && ps->band_solo_state.active) {
            last_loop = loop;
            std::vector<int> notes;
            const PulsarTrackState& lt = ps->tracks[kLeadTrack];
            for (int s = 0; s < lt.step_count; s++) if (lt.steps[s].gate) notes.push_back(lt.steps[s].note);
            if (!notes.empty()) bar_notes.push_back(notes);
            if (bar_notes.size() >= 6) break;
        }
    }
    bool evolves = false;
    for (size_t b = 1; b < bar_notes.size(); b++) if (bar_notes[b] != bar_notes[0]) evolves = true;
    bool ok = bar_notes.size() >= 3 && evolves;
    printf("  captured=%zu evolves=%d -- %s\n",
           bar_notes.size(), evolves, ok ? "PASS":"FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// ── SOLO-1: LickBuilder lead renders an evolving, in-scale lick ──────
static bool test_lickbuilder_lead_evolving_in_scale() {
    printf("\n=== Test: SOLO-1 LickBuilder lead renders evolving in-scale notes ===\n");

    const int kLeadTrack = 4;  // KEYS, melodic
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);  // high so notes fire
    // complexity=0 disables mutate_patterns drift, so the ONLY source of per-bar
    // change in the lead track's notes is the SOLO-1 live-lick render. Without
    // SOLO-1 the lead track is frozen (evolves=NO); with it, it evolves.
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    // Harmonic Minor (9), root G# (8) per the plan.
    engine->pulsar_root_note.store(8, std::memory_order_relaxed);
    engine->pulsar_scale_index.store(9, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    // FIXED chord-follow on the lead track so notes aren't chord-transposed
    // out of the simple (root,scale) pitch-class set the test checks.
    engine->pulsar_track_chord_follow[kLeadTrack].store(2, std::memory_order_relaxed);  // FIXED
    // Explicit seed for reproducibility.
    engine->pulsar_seed.store(7777, std::memory_order_relaxed);

    // A lick the lead member will reinterpret.
    engine->pulsar_lick[0] = {0, 0.5f, 0.8f};
    engine->pulsar_lick[1] = {2, 0.5f, 0.8f};
    engine->pulsar_lick[2] = {4, 0.5f, 0.8f};
    engine->pulsar_lick[3] = {1, 0.5f, 0.8f};
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);

    push_lickbuilder_band_arrangement(engine, kLeadTrack);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    const PulsarScale& scale = kPulsarScales[9];
    int root = 8;

    // Capture track-4 gated-note sequence per bar.
    std::vector<std::vector<int>> bar_notes;
    int last_loop = -1;
    bool all_in_scale = true;
    int max_degree_span = 0;  // for MUT-4 bound check
    int base_octave_note = -1;

    for (int i = 0; i < 4000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        // Sample the lead track's pattern once per loop (bar) wrap while soloing.
        int loop = ps->loop_count;
        if (loop != last_loop && ps->band_solo_state.active) {
            last_loop = loop;
            const PulsarTrackState& lt = ps->tracks[kLeadTrack];
            std::vector<int> notes;
            for (int s = 0; s < lt.step_count; s++) {
                if (lt.steps[s].gate) {
                    int n = lt.steps[s].note;
                    notes.push_back(n);
                    // Scale-membership: (n - root) mod 12 must be a scale degree
                    // (ignoring octave base / chord offset which are 0 here).
                    int pc = ((n - root) % 12 + 12) % 12;
                    bool in_scale = false;
                    for (int d = 0; d < scale.count; d++)
                        if (scale.degrees[d] == pc) { in_scale = true; break; }
                    if (!in_scale) all_in_scale = false;
                    if (base_octave_note < 0) base_octave_note = n;
                    int span = std::abs(n - base_octave_note);
                    if (span > max_degree_span) max_degree_span = span;
                }
            }
            if (!notes.empty()) bar_notes.push_back(notes);
            if (bar_notes.size() >= 8) break;
        }
    }

    // 1. The lick must evolve: at least one captured bar differs from the first.
    bool evolves = false;
    for (size_t b = 1; b < bar_notes.size(); b++) {
        if (bar_notes[b] != bar_notes[0]) { evolves = true; break; }
    }
    // 2. Notes must stay in scale.
    // 3. MUT-4: degree drift must stay bounded (within ~2 octaves of the base).
    bool bounded = max_degree_span <= 30;  // ~2.5 octaves of semitone span

    printf("  captured %zu bars, evolves=%s in_scale=%s max_note_span=%d bounded=%s\n",
           bar_notes.size(), evolves ? "YES" : "NO",
           all_in_scale ? "YES" : "NO", max_degree_span, bounded ? "YES" : "NO");

    bool pass = (bar_notes.size() >= 3) && evolves && all_in_scale && bounded;
    printf("  SOLO-1 LickBuilder render: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// Task 7: a LickBuilder lead with an authored per-track range must render
// entirely inside it, not the (wider) genre range.
static bool test_lickbuilder_bass_lead_stays_in_its_register() {
    printf("\n=== Test: a LickBuilder bass lead keeps every note inside its authored range ===\n");
    const int kLead = 3, kLow = 28, kHigh = 45;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(2024, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_track_note_range_low[kLead].store(kLow, std::memory_order_relaxed);
    engine->pulsar_track_note_range_high[kLead].store(kHigh, std::memory_order_relaxed);
    engine->pulsar_lick[0] = {0, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[1] = {2, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[2] = {4, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[3] = {1, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    push_lickbuilder_band_arrangement(engine, kLead);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    int checked = 0, outside = 0, bars = 0, last_loop = -1;
    for (int i = 0; i < 6000 && bars < 24; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->loop_count != last_loop && ps->band_solo_state.active) {
            last_loop = ps->loop_count; bars++;
            const PulsarTrackState& lt = ps->tracks[kLead];
            for (int s = 0; s < lt.step_count; s++) if (lt.steps[s].gate) {
                checked++;
                if (lt.steps[s].note < kLow || lt.steps[s].note > kHigh) outside++;
            }
        }
    }
    orpheus_engine_destroy(engine);
    bool pass = bars >= 24 && checked > 0 && outside == 0;
    printf("  bars=%d notes=%d outside[%d..%d]=%d -- %s\n", bars, checked, kLow, kHigh, outside, pass ? "PASS" : "FAIL");
    return pass;
}

// ── SOLO-3: positive density modifier makes the leading track busier ──
//
// Counts fired step-triggers on a fully-gated melodic track at a fixed seed,
// comparing a LEADING soloist (positive solo_density_mod) against the same
// track with no solo modifier. The fire gate uses fire_prob = energy*0.6+0.4,
// so at moderate energy some gated steps are probabilistically dropped; a
// positive density_mod must RAISE fire_prob so more steps fire.
static int count_fired_on_track(OrpheusEngine* engine, GraphUnit* unit,
                                int track, float density_mod, int bars) {
    // Drive blocks and count rising edges of the track's voice gate by watching
    // gate_timer resets. We sample once per processed block.
    int fired = 0;
    float prev_timer = 0.0f;
    int blocks = bars * 60;  // ~enough blocks per bar at the test BPM
    for (int i = 0; i < blocks; i++) {
        PulsarState* ps = engine->pulsar_state;
        if (ps) {
            // Force the modifier each block (apply_band_solo_modifiers would
            // otherwise overwrite it; here we drive it directly for isolation).
            // Task 4 contract: the audio loop reads the SMOOTHED field
            // (solo_density_mod_current), not the raw field — set both.
            ps->tracks[track].solo_density_mod = density_mod;
            ps->tracks[track].solo_density_mod_current = density_mod;
            ps->tracks[track].is_soloist = (density_mod > 0.0f);
        }
        unit_process_pulsar(unit, engine, 256, 48000.0f);
        if (ps) {
            float t = ps->tracks[track].gate_timer;
            if (t > prev_timer + 1.0f) fired++;  // rising edge (fresh retrigger)
            prev_timer = t;
        }
    }
    return fired;
}

static bool test_positive_density_makes_soloist_busier() {
    printf("\n=== Test: SOLO-3 positive density modifier raises soloist fire density ===\n");

    const int kTrack = 4;  // KEYS melodic

    auto run = [&](float density_mod) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        GraphUnit unit;
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;
        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        // Moderate energy so fire_prob (~0.7) drops a meaningful fraction of
        // gated steps — that head-room is what the density boost fills in.
        engine->pulsar_energy.store(0.5f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
        setup_fixture_baseline(engine);
        engine->pulsar_seed.store(31337, std::memory_order_relaxed);  // fixed
        engine->pulsar_step_count.store(16, std::memory_order_relaxed);
        // Make the track fully gated so the fire gate is the only thing that
        // decides how many steps sound: use a dense density override.
        engine->pulsar_track_density_override[kTrack].store(1.0f, std::memory_order_relaxed);
        engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
        trigger_vibe_load(engine);
        // Warm up one bar.
        for (int i = 0; i < 60; i++) unit_process_pulsar(&unit, engine, 256, 48000.0f);
        int fired = count_fired_on_track(engine, &unit, kTrack, density_mod, 8);
        orpheus_engine_destroy(engine);
        return fired;
    };

    int fired_plain = run(0.0f);
    int fired_solo  = run(0.5f);  // positive density mod

    printf("  fired (no mod) = %d, fired (density +0.5) = %d\n", fired_plain, fired_solo);

    bool busier = fired_solo > fired_plain;
    printf("  SOLO-3 positive density: %s\n", busier ? "PASS" : "FAIL");
    return busier;
}

// ── #3: render_lick_into_track honors CALL_RESPONSE phrasing ─────────
//
// The shared lick->track helper (used by both the déjà-vu reset and the
// SOLO-1 solo render) must route CALL_RESPONSE through bar_strategy_call_response
// (two-bar question/answer), and every other strategy through the plain looping
// render. This locks in the #3 fix: a soloing lead with CALL_RESPONSE keeps its
// trading-fours phrasing instead of looping the same bar.
static bool test_render_lick_into_track_honors_call_response() {
    printf("\n=== Test: render_lick_into_track honors CALL_RESPONSE phrasing (#3) ===\n");

    // 4-note lick with distinct degrees so call vs response is observable.
    PulsarLickStep lick[4] = {
        {0, 0.5f, 0.8f, -1.0f},
        {2, 0.5f, 0.8f, -1.0f},
        {4, 0.5f, 0.8f, -1.0f},
        {1, 0.5f, 0.8f, -1.0f},
    };
    const PulsarScale& scale = kPulsarScales[0];
    const uint8_t root = 60;
    const int step_count = 32;  // 2 bars → CALL_RESPONSE has a question + answer

    // PulsarTrackState owns a non-copyable OrpheusVoice → render in place.
    auto render = [&](PulsarTrackState& ts, BarStrategy bs) {
        ts.role = TrackRole::MELODIC;
        render_lick_into_track(ts, /*track_index*/4, lick, /*lick_length*/4,
                               /*mutation*/0.0f, root, scale, /*seed*/12345u,
                               bs, step_count, /*lick_octave*/0,
                               /*nr_low*/36, /*nr_high*/72, /*lick_loop_length*/0);
    };

    PulsarTrackState cr{};
    PulsarTrackState rep{};
    render(cr,  BarStrategy::CALL_RESPONSE);
    render(rep, BarStrategy::REPEAT);

    // 1. step_count is set to the requested two-bar length.
    bool count_ok = (cr.step_count == step_count) && (rep.step_count == step_count);

    // 2. CALL_RESPONSE must route DIFFERENTLY than the plain loop — that is the
    //    whole point of dispatching CR through the helper (the #3 regression was
    //    that the solo render always used the plain path).
    bool cr_differs_from_plain = false;
    for (int s = 0; s < step_count; s++) {
        if (cr.steps[s].gate != rep.steps[s].gate ||
            cr.steps[s].note != rep.steps[s].note) { cr_differs_from_plain = true; break; }
    }

    // 3. Every gated note stays in scale (no transposition out of the pitch set).
    bool all_in_scale = true;
    int gated = 0;
    for (int s = 0; s < step_count; s++) {
        if (!cr.steps[s].gate) continue;
        gated++;
        int pc = ((static_cast<int>(cr.steps[s].note) - root) % 12 + 12) % 12;
        bool in = false;
        for (int d = 0; d < scale.count; d++)
            if (scale.degrees[d] == pc) { in = true; break; }
        if (!in) all_in_scale = false;
    }

    // Informational: does the answer (bar 2) differ from the call (bar 1)?
    int half = step_count / 2;
    bool answer_differs = false;
    for (int s = 0; s < half; s++) {
        if (cr.steps[s].gate != cr.steps[half + s].gate ||
            cr.steps[s].note != cr.steps[half + s].note) { answer_differs = true; break; }
    }

    bool pass = count_ok && cr_differs_from_plain && all_in_scale && gated > 0;
    printf("  count_ok=%s cr_differs_from_plain=%s in_scale=%s gated=%d (answer_differs=%s)\n",
           count_ok ? "Y" : "N", cr_differs_from_plain ? "Y" : "N",
           all_in_scale ? "Y" : "N", gated, answer_differs ? "Y" : "N");
    printf("  render_lick_into_track CALL_RESPONSE: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── JAM-2: Jam free-improv generation + carryover ──────────────────
// Jam solos now GENERATE a chord-anchored markov line via generate_jam_solo_line
// and record the phrase (phrase_cursor grows), which enables improvisers_handoff
// carryover on the next handoff. The line evolves bar-to-bar (markov walk).
static bool test_jam_improv_generated_and_carryover() {
    printf("\n=== Test: JAM-2 Jam improv generated + carryover ===\n");
    const int kLeadTrack = 4;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(909, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(0, std::memory_order_release);
    push_jam_band_arrangement(engine, kLeadTrack);   // from JAM-1
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    std::vector<std::vector<int>> bar_notes; int last_loop = -1; bool phrase_recorded = false;
    for (int i = 0; i < 6000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->band_solo_state.active && ps->band_solo_state.phrase_cursor > 0) phrase_recorded = true;
        int loop = ps->loop_count;
        if (loop != last_loop && ps->band_solo_state.active) {
            last_loop = loop;
            std::vector<int> notes;
            const PulsarTrackState& lt = ps->tracks[kLeadTrack];
            for (int s = 0; s < lt.step_count; s++) if (lt.steps[s].gate) notes.push_back(lt.steps[s].note);
            if (!notes.empty()) bar_notes.push_back(notes);
            if (bar_notes.size() >= 6) break;
        }
    }
    bool evolves = false;
    for (size_t b = 1; b < bar_notes.size(); b++) if (bar_notes[b] != bar_notes[0]) evolves = true;
    bool ok = bar_notes.size() >= 3 && evolves && phrase_recorded;
    printf("  captured=%zu evolves=%d phrase_recorded=%d -- %s\n",
           bar_notes.size(), evolves, phrase_recorded, ok ? "PASS":"FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// BREAK no longer clears melodic steps itself -- the duck (apply_band_solo_modifiers)
// does that work now, so render_drum_lead must leave every melodic track's steps alone,
// lead member's own tracks included.
static bool test_break_render_leaves_melodic_steps_alone() {
    printf("\n=== Test: BREAK render leaves melodic steps alone ===\n");

    // One band member owning tracks {0,1,2,3}. Tracks 0-2 map to kick/snare/hat
    // (overwritten by the rhythm render); track 3 is a member track the render
    // never touches, so it isolates the BREAK-clear behavior.
    BandSoloConfigParam config{};
    config.member_count = 1;
    config.members[0].track_count = 4;
    config.members[0].tracks[0] = 0;
    config.members[0].tracks[1] = 1;
    config.members[0].tracks[2] = 2;
    config.members[0].tracks[3] = 3;

    PulsarTrackState tracks[kNumPulsarTracks]{};
    for (int t = 0; t < kNumPulsarTracks; t++) {
        tracks[t].role = TrackRole::MELODIC;
        tracks[t].step_count = 16;
        for (int i = 0; i < 16; i++) tracks[t].steps[i].gate = true;
    }

    PulsarLickStep lick[4] = {};   // zero-init is valid regardless of struct fields
    uint32_t seed = 12345;

    render_drum_lead(config, tracks, kNumPulsarTracks, /*lead_member=*/0,
                     DrumLeadStyle::BREAK, lick, /*lick_len=*/4, /*complexity=*/0.5f, seed);

    // Track 3 (member, not kick/snare/hat): its gates survive.
    int member_gates = 0;
    for (int i = 0; i < tracks[3].step_count; i++) if (tracks[3].steps[i].gate) member_gates++;

    // Track 5 (non-member melodic): also survives -- the render no longer touches it.
    int nonmember_gates = 0;
    for (int i = 0; i < tracks[5].step_count; i++) if (tracks[5].steps[i].gate) nonmember_gates++;

    bool member_ok = member_gates > 0;
    bool nonmember_ok = nonmember_gates > 0;
    printf("  member track 3 gates=%d (expected >0) -- %s\n", member_gates, member_ok ? "OK" : "FAIL");
    printf("  non-member track 5 gates=%d (expected >0) -- %s\n", nonmember_gates, nonmember_ok ? "OK" : "FAIL");
    bool ok = member_ok && nonmember_ok;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_solo_mod_slew_produces_intermediate_values() {
    printf("\n=== Test: kSoloModSlew crossfades instead of snapping ===\n");

    // The largest possible solo mod target magnitude is LEADING density at
    // loudness 1.0: 0.15 + 0.3 * 1.0 = 0.45. The crossfade contract: one
    // per-bar slew step must NOT fully cover a typical swing, so handoffs
    // ramp over multiple bars instead of hard-stepping at the boundary.
    float duck = slew_toward(0.0f, -0.18f, kSoloModSlew);   // SUPPORT duck
    bool duck_intermediate = duck > -0.18f && duck < 0.0f;
    printf("  duck after 1 bar = %.3f (target -0.18) -- %s\n",
           duck, duck_intermediate ? "OK" : "FAIL");

    float boost = slew_toward(0.0f, 0.45f, kSoloModSlew);   // max LEADING boost
    bool boost_intermediate = boost > 0.0f && boost < 0.45f;
    printf("  max boost after 1 bar = %.3f (target 0.45) -- %s\n",
           boost, boost_intermediate ? "OK" : "FAIL");

    // Convergence: the biggest swing must still settle within 4 bars.
    float v = 0.0f;
    int bars = 0;
    while (v != 0.45f && bars < 16) { v = slew_toward(v, 0.45f, kSoloModSlew); bars++; }
    bool converges = bars <= 4;
    printf("  max boost converged in %d bars (<= 4) -- %s\n",
           bars, converges ? "OK" : "FAIL");

    bool pass = duck_intermediate && boost_intermediate && converges;
    printf("  Solo mod slew: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Task 1: SoloMode.Jam.lickInfluence drives the handoff carryover ──
//
// lickInfluence was marshalled into section slot 12 and then dropped: the
// carryover was a hardcoded 0.7. 0 must leave the incoming soloist's interval
// weights exactly as authored; 1 must replace them with the outgoing phrase's
// shape, and the resulting line must differ.

struct JamHandoffRun {
    float lead_a_weights[kMarkovIntervals] = {};
    float lead_b_weights[kMarkovIntervals] = {};
    std::vector<std::vector<int>> post_handoff_bars;
    int handoffs = 0;
};

static JamHandoffRun run_jam_handoff(float lick_influence) {
    JamHandoffRun out;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(31337, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(0, std::memory_order_release);
    push_jam_handoff_arrangement(engine, lick_influence);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    int last_loop = -1;
    for (int i = 0; i < 8000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->loop_count == last_loop) continue;
        last_loop = ps->loop_count;
        if (!ps->band_solo_state.active) continue;
        for (int k = 0; k < kMarkovIntervals; k++) {
            out.lead_a_weights[k] = ps->track_solo_behavior[4].interval_weights[k];
            out.lead_b_weights[k] = ps->track_solo_behavior[3].interval_weights[k];
        }
        if (ps->band_solo_state.just_handed_off) {
            out.handoffs++;
            int lt = (ps->band_solo_state.lead_member == 1) ? 4 : 3;
            std::vector<int> notes;
            const PulsarTrackState& tr = ps->tracks[lt];
            for (int s = 0; s < tr.step_count; s++)
                if (tr.steps[s].gate) notes.push_back(tr.steps[s].note);
            out.post_handoff_bars.push_back(notes);
            if (out.handoffs >= 6) break;
        }
    }
    orpheus_engine_destroy(engine);
    return out;
}

static bool test_jam_lick_influence_drives_carryover() {
    printf("\n=== Test: Jam lickInfluence drives the handoff carryover ===\n");

    JamHandoffRun off = run_jam_handoff(0.0f);
    JamHandoffRun on  = run_jam_handoff(1.0f);

    bool handed_off = off.handoffs >= 3 && on.handoffs >= 3;

    // influence 0 => improvisers_handoff must not touch the authored weights.
    float untouched = 0.0f;
    for (int i = 0; i < kMarkovIntervals; i++) {
        untouched = std::max(untouched, std::fabs(off.lead_a_weights[i] - kJamStepwiseWeights[i]));
        untouched = std::max(untouched, std::fabs(off.lead_b_weights[i] - kJamStepwiseWeights[i]));
    }
    bool zero_ignores = untouched < 1e-5f;

    // influence 1 => weights are pulled onto the outgoing phrase's histogram.
    float moved = 0.0f;
    for (int i = 0; i < kMarkovIntervals; i++) {
        moved = std::max(moved, std::fabs(on.lead_a_weights[i] - off.lead_a_weights[i]));
        moved = std::max(moved, std::fabs(on.lead_b_weights[i] - off.lead_b_weights[i]));
    }
    bool one_inherits = moved > 0.05f;

    // The two runs share an identical RNG stream (improvisers_handoff draws no
    // randomness), so a different line is attributable to the weights alone.
    bool lines_differ = false;
    size_t n = std::min(off.post_handoff_bars.size(), on.post_handoff_bars.size());
    for (size_t b = 0; b < n; b++)
        if (off.post_handoff_bars[b] != on.post_handoff_bars[b]) lines_differ = true;

    printf("  handoffs off/on = %d/%d, weights untouched at 0 = %.6f, moved at 1 = %.3f\n",
           off.handoffs, on.handoffs, untouched, moved);
    printf("  post-handoff lines differ = %s\n", lines_differ ? "yes" : "no");

    bool pass = handed_off && zero_ignores && one_inherits && lines_differ;
    printf("  Jam lickInfluence carryover: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Task 2: the handoff bar is punctuated by a kit fill ──────────────
//
// Counts real note-ons (prev_step_gated, set only when a step actually fires)
// on the percussive tracks, bucketed by whether the bar was a handoff bar.
static bool test_handoff_bar_fires_a_kit_fill() {
    printf("\n=== Test: A handoff bar fires a kit fill ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(20260831, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(0, std::memory_order_release);
    push_jam_handoff_arrangement(engine, 0.5f);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    int prev_ph[3] = {-1, -1, -1};
    int cur_bar = -1, cur_hits = 0;
    bool cur_handoff = false, cur_solo = false;
    long ho_hits = 0, ho_bars = 0, nh_hits = 0, nh_bars = 0;

    for (int i = 0; i < 30000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->loop_count != cur_bar) {
            if (cur_bar >= 0 && cur_solo) {
                if (cur_handoff) { ho_hits += cur_hits; ho_bars++; }
                else             { nh_hits += cur_hits; nh_bars++; }
            }
            cur_bar = ps->loop_count;
            cur_hits = 0;
            cur_handoff = ps->band_solo_state.just_handed_off;
            cur_solo = ps->band_solo_state.active;
        }
        for (int t = 0; t < 3; t++) {
            const PulsarTrackState& ts = ps->tracks[t];
            if (ts.playhead != prev_ph[t]) {
                prev_ph[t] = ts.playhead;
                if (ts.prev_step_gated) cur_hits++;
            }
        }
        if (ho_bars >= 20 && nh_bars >= 20) break;
    }
    orpheus_engine_destroy(engine);

    float ho_avg = ho_bars > 0 ? static_cast<float>(ho_hits) / ho_bars : 0.0f;
    float nh_avg = nh_bars > 0 ? static_cast<float>(nh_hits) / nh_bars : 0.0f;
    bool sampled = ho_bars >= 10 && nh_bars >= 10;
    // Not every handoff fires (kHandoffFillChance), so the handoff-bar MEAN is
    // the claim, not every individual bar.
    bool fills_more = ho_avg > nh_avg * 1.10f;

    printf("  handoff bars=%ld avg kit note-ons=%.2f | other bars=%ld avg=%.2f\n",
           ho_bars, ho_avg, nh_bars, nh_avg);
    bool pass = sampled && fills_more;
    printf("  Handoff bar fires a kit fill: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Task 3: a jam builds across its section ──────────────────────────
//
// solo_progress used to be tension_intensity — a per-phrase LFO — so bar 1 and
// bar 32 of a jam were statistically identical. It is now elapsed bars over the
// section, which drives markov_next_note's density gate from min toward max.
static bool test_jam_builds_over_its_section() {
    printf("\n=== Test: A jam builds across its section ===\n");

    const int kLeadTrack = 4;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(5150, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(0, std::memory_order_release);
    push_jam_band_arrangement(engine, kLeadTrack);   // 32-bar JAM, no handoffs
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Gated steps per bar, bucketed by the bar's position within its section.
    long sum[32] = {}; int count[32] = {};
    int last_loop = -1;
    for (int i = 0; i < 40000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->loop_count == last_loop) continue;
        last_loop = ps->loop_count;
        if (!ps->band_solo_state.active) continue;
        int total = ps->section_state.bars_total;
        if (total != 32) continue;
        int pos = total - ps->section_state.bars_remaining;
        if (pos < 0 || pos >= 32) continue;
        int gated = 0;
        const PulsarTrackState& lt = ps->tracks[kLeadTrack];
        for (int s = 0; s < lt.step_count; s++) if (lt.steps[s].gate) gated++;
        sum[pos] += gated; count[pos]++;
        if (count[31] >= 3) break;
    }
    orpheus_engine_destroy(engine);

    auto window_avg = [&](int lo, int hi) {
        long s = 0; int c = 0;
        for (int p = lo; p < hi; p++) { s += sum[p]; c += count[p]; }
        return c > 0 ? static_cast<float>(s) / c : 0.0f;
    };
    float early = window_avg(0, 8);
    float late  = window_avg(24, 32);
    bool sampled = count[0] > 0 && count[31] > 0;
    bool builds = late > early * 1.15f;

    printf("  bars 0-7 avg gated steps=%.2f | bars 24-31 avg=%.2f (passes sampled=%d)\n",
           early, late, count[31]);
    bool pass = sampled && builds;
    printf("  Jam builds over its section: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// First six solo bars of the lick-less JAM lead (track 4, seed 4242), captured from
// the binary built BEFORE the ornament path landed. -1 = rest. Pins the no-lick
// branch byte-for-byte: only a lick-driven lead may take the new route.
#define JAM_NO_LICK_GOLDEN \
    {-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1}, \
    {70,-1,-1,-1,-1,69,-1,72,-1,-1,72,-1,70,70,72,-1}, \
    {-1,70,67,65,65,-1,65,70,69,-1,-1,67,-1,-1,-1,67}, \
    {70,70,-1,-1,-1,-1,72,-1,-1,69,62,64,62,69,-1,-1}, \
    {65,72,-1,-1,69,72,62,67,69,67,65,-1,65,-1,-1,70}, \
    {-1,-1,-1,65,-1,67,70,72,62,62,-1,72,70,-1,62,72},

// ── Jam ornamentation: the authored hook survives its own solo ────────
//
// A deliberately gappy 8-entry hook: sounding pairs on the beat, rests between.
// At 0.5-beat durations it fills exactly one 16-step bar, gating
// {0,1,4,5,8,9,12,13} and leaving {2,3,6,7,10,11,14,15} empty — the gaps a jam
// is supposed to ornament rather than paint over. Shape mirrors an authored
// LickMode.Fill + BarStrategy.REPEAT riff (the RustBelt bass case).
static const int8_t kHookDegrees[8]   = {0, -1, 3, -1, 5, -1, 4, -1};
static const int    kHookGatedSteps[8] = {0, 1, 4, 5, 8, 9, 12, 13};

static bool is_hook_step(int s) {
    for (int k = 0; k < 8; k++) if (kHookGatedSteps[k] == s) return true;
    return false;
}

// Arm `lead_track` with the gappy hook as a REPEAT/FILL lick. Mutation 0 keeps the
// hook bit-stable, so any drift in its gated steps is the jam overwriting it.
static void arm_hook_lick(OrpheusEngine* engine, int lead_track) {
    for (int i = 0; i < 8; i++) {
        engine->pulsar_lick[i].scale_degree = kHookDegrees[i];
        engine->pulsar_lick[i].duration = 0.5f;   // 2 sequencer slots each
        engine->pulsar_lick[i].velocity = 0.8f;
        engine->pulsar_lick[i].glide_rate = -1.0f;
    }
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(8, std::memory_order_release);
    engine->pulsar_track_lick_mode[lead_track].store(2, std::memory_order_relaxed);    // FILL
    engine->pulsar_track_bar_strategy[lead_track].store(0, std::memory_order_relaxed); // REPEAT
}

struct JamBarCapture {
    std::vector<std::vector<PulsarStep>> bars;  // lead track pattern, one entry per solo bar
    std::vector<int> section_pos;               // that bar's position in its 32-bar section
};

// Drive the JAM fixture and snapshot the lead's pattern once per solo bar.
// nr_low/nr_high > 0 push a per-track authored note range before the vibe loads.
static JamBarCapture run_jam_capture(int lead_track, bool with_hook, uint32_t seed,
                                     int want_bars, int nr_low = 0, int nr_high = 0) {
    JamBarCapture out;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);  // no mutate_patterns drift
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(seed, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    if (with_hook) arm_hook_lick(engine, lead_track);
    else           engine->pulsar_lick_length.store(0, std::memory_order_release);
    if (nr_low  > 0) engine->pulsar_track_note_range_low[lead_track].store(
        nr_low, std::memory_order_relaxed);
    if (nr_high > 0) engine->pulsar_track_note_range_high[lead_track].store(
        nr_high, std::memory_order_relaxed);
    push_jam_band_arrangement(engine, lead_track);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    int last_loop = -1;
    for (int i = 0; i < 40000 && static_cast<int>(out.bars.size()) < want_bars; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->loop_count == last_loop) continue;
        last_loop = ps->loop_count;
        if (!ps->band_solo_state.active) continue;
        if (ps->section_state.bars_total != 32) continue;
        const PulsarTrackState& lt = ps->tracks[lead_track];
        out.bars.emplace_back(lt.steps, lt.steps + lt.step_count);
        out.section_pos.push_back(ps->section_state.bars_total - ps->section_state.bars_remaining);
    }
    orpheus_engine_destroy(engine);
    return out;
}

// A soloing lick-driven lead keeps its authored hook and gains motion in the gaps.
// Before the ornament path this failed outright: generate_jam_solo_line overwrote
// every step, so the hook's gated pitches did not survive a single bar.
static bool test_jam_ornaments_authored_hook() {
    printf("\n=== Test: Jam ornaments an authored hook instead of overwriting it ===\n");
    const int kLeadTrack = 4;
    JamBarCapture cap = run_jam_capture(kLeadTrack, /*with_hook=*/true, 4242, 24);

    bool sampled = cap.bars.size() >= 8;
    int hook_notes[8] = {};
    if (!cap.bars.empty())
        for (int k = 0; k < 8; k++) hook_notes[k] = cap.bars[0][kHookGatedSteps[k]].note;

    bool hook_intact = sampled;
    for (const auto& bar : cap.bars)
        for (int k = 0; k < 8; k++) {
            const PulsarStep& st = bar[kHookGatedSteps[k]];
            if (!st.gate || st.note != hook_notes[k]) hook_intact = false;
        }

    // Independent of the run: those pitches must be the hook's AUTHORED degrees.
    // Fixture root is D(2) in minor {0,2,3,5,7,8,10}, so the sounding pairs
    // (degrees 0,3,5,4) carry pitch classes 0,5,8,7 above the root.
    const int kHookPitchClasses[8] = {0, 0, 5, 5, 8, 8, 7, 7};
    bool authored_pitches = sampled;
    for (int k = 0; k < 8; k++)
        if (((hook_notes[k] - 2) % 12 + 12) % 12 != kHookPitchClasses[k]) authored_pitches = false;

    int ornaments = 0;
    for (const auto& bar : cap.bars)
        for (int s = 0; s < static_cast<int>(bar.size()); s++)
            if (!is_hook_step(s) && bar[s].gate) ornaments++;

    bool pass = sampled && hook_intact && authored_pitches && ornaments > 0;
    printf("  bars=%zu hook_intact=%s authored_pitches=%s ornaments_in_rests=%d -- %s\n",
           cap.bars.size(), hook_intact ? "YES" : "NO",
           authored_pitches ? "YES" : "NO", ornaments, pass ? "PASS" : "FAIL");
    return pass;
}

// Ornament density rides jam_solo_progress: a jam opens near the bare hook and
// fills the gaps in as the section runs out.
static bool test_jam_ornament_density_builds() {
    printf("\n=== Test: Jam ornament density builds across the section ===\n");
    const int kLeadTrack = 4;
    JamBarCapture cap = run_jam_capture(kLeadTrack, /*with_hook=*/true, 5150, 96);

    long sum[32] = {}; int count[32] = {};
    for (size_t b = 0; b < cap.bars.size(); b++) {
        int pos = cap.section_pos[b];
        if (pos < 0 || pos >= 32) continue;
        int orn = 0;
        for (int s = 0; s < static_cast<int>(cap.bars[b].size()); s++)
            if (!is_hook_step(s) && cap.bars[b][s].gate) orn++;
        sum[pos] += orn; count[pos]++;
    }
    auto window_avg = [&](int lo, int hi) {
        long s = 0; int c = 0;
        for (int p = lo; p < hi; p++) { s += sum[p]; c += count[p]; }
        return c > 0 ? static_cast<float>(s) / c : 0.0f;
    };
    float early = window_avg(0, 8);
    float late  = window_avg(24, 32);
    bool sampled = count[0] > 0 && count[31] > 0;
    bool builds = late > early * 1.15f;
    // The hook leaves 8 rests per bar. Opening near the BARE hook means barely any
    // of them fill in; the overwrite path used to sit above 4 here, so this bound
    // is what separates ornamenting from regenerating.
    bool opens_sparse = early < 2.5f;

    printf("  bars 0-7 avg ornaments=%.2f (of 8 rests) | bars 24-31 avg=%.2f (late passes=%d)\n",
           early, late, count[31]);
    bool pass = sampled && builds && opens_sparse;
    printf("  Ornamentation builds: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// A soloing track with NO authored lick still takes the full-generation path.
// Golden captured from the pre-ornament binary at this fixture and seed: the
// lick-less jam line must be byte-for-byte what it always was.
static bool test_jam_no_lick_line_unchanged() {
    printf("\n=== Test: Jam with no authored lick is unchanged ===\n");
    const int kLeadTrack = 4;
    JamBarCapture cap = run_jam_capture(kLeadTrack, /*with_hook=*/false, 4242, 6);

    static const int kGolden[6][16] = {
        JAM_NO_LICK_GOLDEN
    };
    bool sampled = cap.bars.size() >= 6;
    bool same = sampled;
    for (int b = 0; b < 6 && b < static_cast<int>(cap.bars.size()); b++)
        for (int s = 0; s < 16; s++) {
            int got = cap.bars[b][s].gate ? cap.bars[b][s].note : -1;
            if (got != kGolden[b][s]) same = false;
        }
    printf("  bars=%zu identical_to_pre_change=%s -- %s\n",
           cap.bars.size(), same ? "YES" : "NO", (sampled && same) ? "PASS" : "FAIL");
    return sampled && same;
}

// Generated jam notes honour the track's authored register. The old path clamped
// only to 24..96, so a narrow authored range was simply ignored.
static bool test_jam_notes_respect_track_note_range() {
    printf("\n=== Test: Jam notes stay inside the track's authored range ===\n");
    const int kLeadTrack = 4;
    const int kLow = 48, kHigh = 60;   // narrower than the fixture genre range (36..72)
    JamBarCapture cap = run_jam_capture(kLeadTrack, /*with_hook=*/false, 4242, 24,
                                        kLow, kHigh);
    int checked = 0, outside = 0, lo_seen = 127, hi_seen = 0;
    for (const auto& bar : cap.bars)
        for (const PulsarStep& st : bar) {
            if (!st.gate) continue;
            checked++;
            if (st.note < kLow || st.note > kHigh) outside++;
            if (st.note < lo_seen) lo_seen = st.note;
            if (st.note > hi_seen) hi_seen = st.note;
        }
    bool pass = checked > 0 && outside == 0;
    printf("  notes=%d outside[%d..%d]=%d observed=%d..%d -- %s\n",
           checked, kLow, kHigh, outside, lo_seen, hi_seen, pass ? "PASS" : "FAIL");
    return pass;
}

bool run_pulsar_solos_tests() {
    printf("\n========== PULSAR SOLOS TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_default_solo_behavior_profiles());
    tally(test_default_ducking_profiles());
    tally(test_solo_modifier_application());
    tally(test_clear_solo_modifiers());
    tally(test_markov_note_generation());
    tally(test_markov_density_curve());
    tally(test_improvisers_handoff_biases_weights());
    tally(test_record_solo_note());
    tally(test_density_curve_shape());
    tally(test_drone_interval_preset());
    tally(test_lickbuilder_lead_evolving_in_scale());
    tally(test_lickbuilder_bass_lead_stays_in_its_register());
    tally(test_positive_density_makes_soloist_busier());
    tally(test_render_lick_into_track_honors_call_response());
    tally(test_jam_solo_shared_line());
    tally(test_jam_improv_generated_and_carryover());
    tally(test_break_render_leaves_melodic_steps_alone());
    tally(test_solo_mod_slew_produces_intermediate_values());
    tally(test_jam_lick_influence_drives_carryover());
    tally(test_handoff_bar_fires_a_kit_fill());
    tally(test_jam_builds_over_its_section());
    tally(test_jam_ornaments_authored_hook());
    tally(test_jam_ornament_density_builds());
    tally(test_jam_no_lick_line_unchanged());
    tally(test_jam_notes_respect_track_note_range());
    printf("\nPulsar solos tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
