#include "test_harness.h"
#include "../src/pulsar_motif.h"
#include <cstdio>
#include <cmath>

// ── Unit tests for beat motif system (no engine needed) ──

static MotifSetParam make_test_motif_set() {
    MotifSetParam set = {};
    set.motif_count = 3;
    set.bars_per_motif_min = 2;
    set.bars_per_motif_max = 4;
    set.switch_probability = 0.8f;
    set.recency_decay = 0.5f;

    // Equal transition weights (1.0 for all 3x3)
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            set.transition_weights[i][j] = 1.0f;
        }
    }

    // Motif 0: track 0 density=0.5 (active)
    set.motifs[0].track_densities[0] = 0.5f;
    set.motifs[0].track_density_active[0] = true;
    set.motifs[0].swing_override = -1.0f;
    set.motifs[0].ghost_probability = -1.0f;
    set.motifs[0].rhythm_density = -1.0f;

    // Motif 1: track 0 density=0.25 (halftime)
    set.motifs[1].track_densities[0] = 0.25f;
    set.motifs[1].track_density_active[0] = true;
    set.motifs[1].swing_override = -1.0f;
    set.motifs[1].ghost_probability = -1.0f;
    set.motifs[1].rhythm_density = -1.0f;

    // Motif 2: track 0 density=0.8 (double-time)
    set.motifs[2].track_densities[0] = 0.8f;
    set.motifs[2].track_density_active[0] = true;
    set.motifs[2].swing_override = -1.0f;
    set.motifs[2].ghost_probability = -1.0f;
    set.motifs[2].rhythm_density = -1.0f;

    return set;
}

static bool test_motif_init() {
    printf("\n=== Test: Motif init starts at motif 0, bars_remaining in [2,4] ===\n");
    MotifSetParam set = make_test_motif_set();
    MotifState state = {};
    uint32_t seed = 12345;

    init_motif_state(state, set, seed);

    bool ok = state.current_motif == 0
           && state.bars_remaining >= 2
           && state.bars_remaining <= 4;
    printf("  current_motif=%d, bars_remaining=%d -- %s\n",
           state.current_motif, state.bars_remaining,
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_motif_countdown() {
    printf("\n=== Test: Motif advance decrements bars_remaining ===\n");
    MotifSetParam set = make_test_motif_set();
    MotifState state = {};
    uint32_t seed = 12345;

    init_motif_state(state, set, seed);
    int initial_bars = state.bars_remaining;
    int initial_motif = state.current_motif;

    // Advance once: should decrement bars_remaining, no switch
    bool switched = advance_motif(state, set, seed);

    bool ok = !switched
           && state.current_motif == initial_motif
           && state.bars_remaining == initial_bars - 1;
    printf("  initial_bars=%d, after_advance=%d, switched=%s -- %s\n",
           initial_bars, state.bars_remaining, switched ? "yes" : "no",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_motif_eventually_switches() {
    printf("\n=== Test: Motif eventually switches after repeated advances ===\n");
    MotifSetParam set = make_test_motif_set();
    MotifState state = {};
    uint32_t seed = 67890;

    init_motif_state(state, set, seed);
    int initial_motif = state.current_motif;
    bool ever_switched = false;

    // Loop advance 50 times, looking for a switch
    for (int i = 0; i < 50; i++) {
        bool switched = advance_motif(state, set, seed);
        if (switched) {
            ever_switched = true;
            break;
        }
    }

    bool ok = ever_switched;
    printf("  initial_motif=%d, switched_to=%d, ever_switched=%s -- %s\n",
           initial_motif, state.current_motif, ever_switched ? "yes" : "no",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_motif_partial_overlay() {
    printf("\n=== Test: Motif partial overlay (only active tracks apply) ===\n");

    // Create a genre with all densities=0.5, swing=0.1, rhythm=1
    PulsarGenreProfile genre = {};
    for (int t = 0; t < kNumPulsarTracks; t++) {
        genre.base_density[t] = 0.5f;
    }
    genre.swing_amount = 0.1f;
    genre.rhythm_density = 0.33f;

    // Create motif with:
    // - track 0=0.8 active
    // - track 2=0.2 active
    // - swing override=-1 (no override)
    // - ghost override=-1 (no override)
    // - rhythm_density override=-1 (no override)
    MotifParam motif = {};
    motif.track_densities[0] = 0.8f;
    motif.track_density_active[0] = true;
    motif.track_densities[2] = 0.2f;
    motif.track_density_active[2] = true;
    motif.swing_override = -1.0f;
    motif.ghost_probability = -1.0f;
    motif.rhythm_density = -1.0f;

    // Apply motif
    apply_motif_to_genre(genre, motif);

    // Verify:
    // - track 0 should be 0.8
    // - track 1 should be 0.5 (unchanged)
    // - track 2 should be 0.2
    // - swing should be 0.1 (unchanged)
    // - rhythm_density should be 0.33 (unchanged)
    bool ok = std::fabs(genre.base_density[0] - 0.8f) < 0.001f
           && std::fabs(genre.base_density[1] - 0.5f) < 0.001f
           && std::fabs(genre.base_density[2] - 0.2f) < 0.001f
           && std::fabs(genre.swing_amount - 0.1f) < 0.001f
           && std::fabs(genre.rhythm_density - 0.33f) < 0.001f;
    printf("  track[0]=%.2f (expect 0.8), track[1]=%.2f (expect 0.5), track[2]=%.2f (expect 0.2)\n",
           genre.base_density[0], genre.base_density[1], genre.base_density[2]);
    printf("  swing=%.2f (expect 0.1), rhythm_density=%.2f (expect 0.33) -- %s\n",
           genre.swing_amount, genre.rhythm_density,
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_motif_switch_probability_gate() {
    printf("\n=== Test: Motif with switch_probability=0.0 never switches ===\n");
    MotifSetParam set = make_test_motif_set();
    set.switch_probability = 0.0f;  // Never switch
    MotifState state = {};
    uint32_t seed = 11111;

    init_motif_state(state, set, seed);
    int initial_motif = state.current_motif;

    // Loop advance 100 times, should never switch
    bool ever_switched = false;
    for (int i = 0; i < 100; i++) {
        bool switched = advance_motif(state, set, seed);
        if (switched) {
            ever_switched = true;
            break;
        }
    }

    bool ok = !ever_switched && state.current_motif == initial_motif;
    printf("  initial_motif=%d, final_motif=%d, ever_switched=%s -- %s\n",
           initial_motif, state.current_motif, ever_switched ? "yes" : "no",
           ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_motifs_tests() {
    printf("\n========== PULSAR MOTIFS TESTS ==========\n");
    bool all_pass = true;
    all_pass &= test_motif_init();
    all_pass &= test_motif_countdown();
    all_pass &= test_motif_eventually_switches();
    all_pass &= test_motif_partial_overlay();
    all_pass &= test_motif_switch_probability_gate();
    printf("\nPulsar motifs tests: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
