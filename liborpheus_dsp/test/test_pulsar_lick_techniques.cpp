#include "test_harness.h"
#include "../src/pulsar_lick_techniques.h"
#include <cstdio>
#include <cmath>

// ── Unit tests for lick-aware solo techniques ─────────────────────────

static bool test_lick_rhythm_produces_hits() {
    printf("\n=== Test: Lick rhythm produces hits ===\n");

    // 4-note lick with varying velocities
    PulsarLickStep lick[4] = {
        {0, 1.0f, 0.9f},   // high vel → kick
        {2, 0.5f, 0.5f},   // mid vel → snare
        {4, 0.5f, 0.3f},   // low vel → hihat
        {5, 1.0f, 0.8f},   // high vel → kick
    };

    PulsarStep kick[kMaxPulsarSteps], snare[kMaxPulsarSteps], hat[kMaxPulsarSteps];
    uint32_t seed = 42;

    generate_lick_rhythm_pattern(
        kick, snare, hat, 16,
        lick, 4, 0.5f,
        MemberSoloRole::SUPPORT,
        seed
    );

    int kick_hits = 0, total_hits = 0;
    for (int i = 0; i < 16; i++) {
        if (kick[i].gate) { kick_hits++; total_hits++; }
        if (snare[i].gate) total_hits++;
        if (hat[i].gate) total_hits++;
    }

    bool kick_ok = kick_hits >= 2;
    bool total_ok = total_hits >= 4;

    printf("  kick_hits=%d (expect >=2) -- %s\n", kick_hits, kick_ok ? "OK" : "FAIL");
    printf("  total_hits=%d (expect >=4) -- %s\n", total_hits, total_ok ? "OK" : "FAIL");

    bool pass = kick_ok && total_ok;
    printf("  Lick rhythm produces hits: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_lick_melody_preserves_backbone() {
    printf("\n=== Test: Lick melody preserves backbone ===\n");

    // 4-note lick, evenly spaced
    PulsarLickStep lick[4] = {
        {0, 1.0f, 0.8f},
        {2, 1.0f, 0.7f},
        {4, 1.0f, 0.6f},
        {5, 1.0f, 0.9f},
    };

    PulsarStep steps[kMaxPulsarSteps];
    uint32_t seed = 12345;
    PulsarScale minor = {7, {0,2,3,5,7,8,10,0,0,0,0,0}};

    // At complexity=0.0, mutations should not fire
    generate_lick_melody_variation(
        steps, 16,
        lick, 4,
        0.0f,
        MemberSoloRole::LEADING,
        48, minor,
        seed
    );

    int gated = 0;
    for (int i = 0; i < 16; i++) {
        if (steps[i].gate) gated++;
    }

    bool pass = gated >= 3;
    printf("  gated steps=%d (expect >=3 of 4 lick notes) -- %s\n", gated, pass ? "OK" : "FAIL");
    printf("  Lick melody preserves backbone: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_lick_contour_produces_sweeps() {
    printf("\n=== Test: Lick contour produces sweeps ===\n");

    // Ascending lick to create rising contour
    PulsarLickStep lick[4] = {
        {0, 1.0f, 0.5f},
        {2, 1.0f, 0.5f},
        {4, 1.0f, 0.5f},
        {6, 1.0f, 0.5f},
    };

    float timbre[16], morph[16];
    uint32_t seed = 777;

    generate_lick_contour_sweep(
        timbre, morph, 16,
        lick, 4,
        0.3f,
        seed
    );

    // Verify timbre has variation > 0.05 across steps
    float t_min = timbre[0], t_max = timbre[0];
    for (int i = 1; i < 16; i++) {
        if (timbre[i] < t_min) t_min = timbre[i];
        if (timbre[i] > t_max) t_max = timbre[i];
    }

    float variation = t_max - t_min;
    bool pass = variation > 0.05f;

    printf("  timbre range: %.3f - %.3f (variation=%.3f, expect >0.05) -- %s\n",
           t_min, t_max, variation, pass ? "OK" : "FAIL");
    printf("  Lick contour produces sweeps: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_fill_builder_progressive() {
    printf("\n=== Test: Fill builder progressive ===\n");

    PulsarStep early_steps[kMaxPulsarSteps], late_steps[kMaxPulsarSteps];

    // Run multiple trials to average out randomness
    int early_total = 0, late_total = 0;
    const int trials = 10;

    for (int t = 0; t < trials; t++) {
        uint32_t seed_early = 1000 + t * 37;
        uint32_t seed_late  = 1000 + t * 37;

        generate_fill_pattern(early_steps, 16, 0.5f, 0.1f, seed_early);
        generate_fill_pattern(late_steps, 16, 0.5f, 0.9f, seed_late);

        for (int i = 0; i < 16; i++) {
            if (early_steps[i].gate) early_total++;
            if (late_steps[i].gate) late_total++;
        }
    }

    bool pass = late_total > early_total;

    printf("  early hits (total over %d trials)=%d\n", trials, early_total);
    printf("  late hits (total over %d trials)=%d\n", trials, late_total);
    printf("  late > early: %s\n", pass ? "OK" : "FAIL");
    printf("  Fill builder progressive: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Suite runner ────────────────────────────────────────────────────────

bool run_pulsar_lick_techniques_tests() {
    printf("\n╔══════════════════════════════════════════════╗\n");
    printf("║   Pulsar Lick Techniques Tests               ║\n");
    printf("╚══════════════════════════════════════════════╝\n");

    bool all_pass = true;
    all_pass &= test_lick_rhythm_produces_hits();
    all_pass &= test_lick_melody_preserves_backbone();
    all_pass &= test_lick_contour_produces_sweeps();
    all_pass &= test_fill_builder_progressive();

    printf("\n  Lick techniques suite: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
