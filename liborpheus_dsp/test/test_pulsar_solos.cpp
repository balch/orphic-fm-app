#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_bar_strategy.h"
#include "../src/pulsar_solo.h"
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

// ── SOLO-1 integration helper ───────────────────────────────────────
//
// Pushes a 2-section arrangement with a 2-member band:
//   member 0 = drums (always_active, tracks 0,1,2)
//   member 1 = melodic lead (track `lead_track`)
// Section 0 = 1-bar NONE intro that transitions to section 1.
// Section 1 = long (32-bar) LickBuilder section that self-loops; the solo
// starts on ENTRY to section 1 and then advance_band_solo + mutate_live_lick
// run once per bar for the section's duration, evolving the live lick.
static void push_lickbuilder_band_arrangement(OrpheusEngine* engine, int lead_track) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = 21;
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

    // Section 1: 32-bar LickBuilder, self-loops.
    int s1 = kSectionStride;
    section_data[s1 + 0] = 32;     // bars_min
    section_data[s1 + 1] = 32;     // bars_max
    section_data[s1 + 2] = 1;      // bar_step
    section_data[s1 + 3] = 0.8f;   // recency_decay
    section_data[s1 + 4] = 1;      // transition_count
    section_data[s1 + 9]  = static_cast<float>(static_cast<int>(SoloModeId::LICK_BUILDER));
    section_data[s1 + 10] = 1.0f;  // solo_probability
    section_data[s1 + 11] = 1.0f;  // solo_mutation_rate (max so the live lick clearly evolves)
    section_data[s1 + 12] = 0.5f;  // solo_lick_influence
    section_data[s1 + 13] = 2;     // solo_bars_min
    section_data[s1 + 14] = 4;     // solo_bars_max
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // Transitions: s0 -> {1:1.0}, s1 -> {1:1.0} (self-loop).
    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;  // s0 edge 0 -> section 1
    trans[24] = 1; trans[25] = 1.0f; trans[26] = 0;  // s1 edge 0 -> section 1 (s1 base = 8*3 = 24)
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Band config: member 0 = drums (always_active), member 1 = melodic lead.
    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(2, std::memory_order_relaxed);
    // band_member_data layout per member: base = m*12
    //   [0]=track_count, [1..8]=tracks, [9]=always_active, [10]=loudness, [11]=creativity
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
    // Handoff/pull-in: keep the lead leading (member 1 -> member 1). Stride-N=2.
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    // row 1 (lead) -> col 1 (itself) = 1.0 (stride-N row-major, N=2 → idx 1*2+1=3)
    engine->pulsar_band_handoff_matrix[1 * 2 + 1].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_min.store(64, std::memory_order_relaxed);  // keep leading
    engine->pulsar_band_bars_per_lead_max.store(64, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_min.store(2, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_max.store(4, std::memory_order_relaxed);
    engine->pulsar_band_improv_carryover.store(0.7f, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    // Clear solo/ducking/markov to defaults
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 6; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
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
    setup_cosmic_techno(engine);
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
            ps->tracks[track].solo_density_mod = density_mod;
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
        setup_cosmic_techno(engine);
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
    tally(test_positive_density_makes_soloist_busier());
    tally(test_render_lick_into_track_honors_call_response());
    printf("\nPulsar solos tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
