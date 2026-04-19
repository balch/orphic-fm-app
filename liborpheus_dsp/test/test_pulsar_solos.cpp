#include "test_harness.h"
#include "../src/pulsar_solo.h"
#include <cstdio>
#include <cmath>

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
    printf("\nPulsar solos tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
