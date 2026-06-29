// Tests for continuous rhythm density pattern generation
// Verifies that generate_rhythm_pattern() with float rhythm_density produces
// correct patterns at boundaries and smoothly blends between levels.

#include "test_harness.h"
#include "../src/pulsar_pattern_gen.h"
#include "../src/orpheus_unit_pulsar.h"  // kPulsarScales, PulsarLickStep

static int count_gates(const PulsarStep* steps, int count) {
    int gates = 0;
    for (int i = 0; i < count; i++) {
        if (steps[i].gate) gates++;
    }
    return gates;
}

// density=0.0 kick should have beat-1 hit and <= 4 total
static bool test_density_zero_matches_sparse() {
    printf("\n  Test: density=0.0 matches sparse pattern\n");

    PulsarGenreProfile genre = {};
    for (int i = 0; i < 8; i++) genre.base_density[i] = 0.3f;
    genre.ghost_probability = 0.2f;
    genre.rhythm_density = 0.0f;

    PulsarStep steps[kMaxPulsarSteps] = {};
    uint32_t seed = 42;

    generate_rhythm_pattern(steps, 16, 0, genre, seed);

    // Beat 1 should always be a kick
    bool beat1 = steps[0].gate && steps[0].note == 36;
    int total = count_gates(steps, 16);

    bool ok = beat1 && total <= 4;
    printf("    beat1=%s, total_gates=%d (expect <= 4) -- %s\n",
           beat1 ? "yes" : "no", total, ok ? "PASS" : "FAIL");
    return ok;
}

// density=1.0 hihat should have >= 10 hits out of 16
static bool test_density_one_is_dense() {
    printf("\n  Test: density=1.0 hihat is dense\n");

    PulsarGenreProfile genre = {};
    for (int i = 0; i < 8; i++) genre.base_density[i] = 0.8f;
    genre.ghost_probability = 0.3f;
    genre.rhythm_density = 1.0f;

    PulsarStep steps[kMaxPulsarSteps] = {};
    uint32_t seed = 42;

    // track_index=2 is hihat
    generate_rhythm_pattern(steps, 16, 2, genre, seed);

    int total = count_gates(steps, 16);

    bool ok = total >= 10;
    printf("    hihat gates=%d (expect >= 10) -- %s\n",
           total, ok ? "PASS" : "FAIL");
    return ok;
}

// Increasing density produces monotonically more hits (averaged over seeds)
static bool test_density_blend_monotonic() {
    printf("\n  Test: increasing density produces monotonically more hits\n");

    const int kNumSeeds = 20;
    const int kNumSteps = 11;  // 0.0, 0.1, 0.2, ..., 1.0
    float avg_hits[kNumSteps] = {};

    for (int s = 0; s < kNumSteps; s++) {
        float density_val = static_cast<float>(s) / 10.0f;
        int total_hits = 0;

        for (int trial = 0; trial < kNumSeeds; trial++) {
            PulsarGenreProfile genre = {};
            for (int i = 0; i < 8; i++) genre.base_density[i] = 0.5f;
            genre.ghost_probability = 0.25f;
            genre.rhythm_density = density_val;

            PulsarStep steps[kMaxPulsarSteps] = {};
            uint32_t seed = static_cast<uint32_t>(trial * 7919 + 1);

            // Sum gates across all 3 percussive tracks for a robust metric
            for (int track = 0; track < 3; track++) {
                uint32_t tseed = seed ^ static_cast<uint32_t>(track * 2654435761u);
                generate_rhythm_pattern(steps, 16, track, genre, tseed);
                total_hits += count_gates(steps, 16);
            }
        }
        avg_hits[s] = static_cast<float>(total_hits) / static_cast<float>(kNumSeeds);
    }

    // Check monotonicity: each step should be >= previous (with small tolerance
    // for stochastic variation — allow 1 hit of slack)
    bool monotonic = true;
    for (int s = 1; s < kNumSteps; s++) {
        if (avg_hits[s] < avg_hits[s - 1] - 1.0f) {
            printf("    non-monotonic at density=%.1f: avg=%.1f < prev=%.1f\n",
                   s * 0.1f, avg_hits[s], avg_hits[s - 1]);
            monotonic = false;
        }
    }

    // Also verify the endpoints diverge meaningfully
    bool spread = avg_hits[kNumSteps - 1] > avg_hits[0] + 3.0f;

    printf("    density 0.0 avg=%.1f, density 1.0 avg=%.1f, monotonic=%s, spread=%s -- %s\n",
           avg_hits[0], avg_hits[kNumSteps - 1],
           monotonic ? "yes" : "no", spread ? "yes" : "no",
           (monotonic && spread) ? "PASS" : "FAIL");
    return monotonic && spread;
}

// ── lick_loop_length tests ────────────────────────────────────────────

// Helper: build a simple lick with uniform quarter-note durations
static void make_test_lick(PulsarLickStep* lick, int count, float dur = 1.0f) {
    for (int i = 0; i < count; i++) {
        lick[i].scale_degree = i % 5;
        lick[i].duration = dur;
        lick[i].velocity = 0.8f;
    }
}

static PulsarScale test_minor_scale() {
    PulsarScale s = {};
    s.count = 7;
    s.degrees[0] = 0; s.degrees[1] = 2; s.degrees[2] = 3;
    s.degrees[3] = 5; s.degrees[4] = 7; s.degrees[5] = 8; s.degrees[6] = 10;
    return s;
}

// Default: loopLength == lick_length → notes fill entire pattern
static bool test_loop_length_default_fills_pattern() {
    printf("\n  Test: loop_length default — notes fill entire pattern\n");

    const int step_count = 32;
    PulsarStep steps[kMaxPulsarSteps] = {};
    PulsarLickStep lick[8];
    make_test_lick(lick, 8, 1.0f);  // 8 quarter notes = 32 sequencer steps
    PulsarScale scale = test_minor_scale();
    uint32_t seed = 42;

    // lick_loop_length = 0 → defaults to lick_length
    generate_lick_pattern(steps, step_count, lick, 8, 0.0f, 48, scale, seed,
                          0, -1, 36, 72, 0);

    int gates = count_gates(steps, step_count);
    // 8 quarter notes should fill all 32 steps (each note = 4 steps, gate on first + holds)
    bool ok = gates >= 8;
    printf("    gates=%d (expect >= 8, notes fill 32 steps) -- %s\n",
           gates, ok ? "PASS" : "FAIL");
    return ok;
}

// loopLength = 4x lick_length → notes occupy ~25% of pattern
static bool test_loop_length_4x_sparse() {
    printf("\n  Test: loop_length 4x — notes occupy ~25%% of pattern\n");

    const int step_count = 32;
    PulsarStep steps[kMaxPulsarSteps] = {};
    PulsarLickStep lick[4];
    make_test_lick(lick, 4, 0.5f);  // 4 eighth notes = 8 sequencer steps
    PulsarScale scale = test_minor_scale();
    uint32_t seed = 42;

    // lick_loop_length = 16 (4x lick_length of 4) → 25% duty cycle
    generate_lick_pattern(steps, step_count, lick, 4, 0.0f, 48, scale, seed,
                          0, -1, 36, 72, 16);

    // Count which steps have gates
    int gates = count_gates(steps, step_count);
    // Find the last gated step
    int last_gated = -1;
    for (int i = step_count - 1; i >= 0; i--) {
        if (steps[i].gate) { last_gated = i; break; }
    }

    // Notes should be confined to the first ~25% of the pattern
    // play_steps = max(8, 32 * 4/16) = max(8, 8) = 8
    bool notes_early = last_gated < step_count / 2;
    // Second half should be silent
    int second_half_gates = count_gates(steps + step_count / 2, step_count / 2);
    bool second_silent = second_half_gates == 0;

    bool ok = gates >= 4 && notes_early && second_silent;
    printf("    gates=%d, last_gated=%d, second_half_gates=%d -- %s\n",
           gates, last_gated, second_half_gates, ok ? "PASS" : "FAIL");
    return ok;
}

// loopLength = 2x lick_length → notes occupy ~50% of pattern
static bool test_loop_length_2x_half() {
    printf("\n  Test: loop_length 2x — notes occupy ~50%% of pattern\n");

    const int step_count = 32;
    PulsarStep steps[kMaxPulsarSteps] = {};
    PulsarLickStep lick[4];
    make_test_lick(lick, 4, 1.0f);  // 4 quarter notes = 16 sequencer steps
    PulsarScale scale = test_minor_scale();
    uint32_t seed = 42;

    // lick_loop_length = 8 (2x lick_length of 4) → 50% duty cycle
    generate_lick_pattern(steps, step_count, lick, 4, 0.0f, 48, scale, seed,
                          0, -1, 36, 72, 8);

    // play_steps = max(16, 32 * 4/8) = max(16, 16) = 16
    int first_half_gates = count_gates(steps, 16);
    int second_half_gates = count_gates(steps + 16, 16);

    bool ok = first_half_gates >= 4 && second_half_gates == 0;
    printf("    first_half_gates=%d, second_half_gates=%d -- %s\n",
           first_half_gates, second_half_gates, ok ? "PASS" : "FAIL");
    return ok;
}

// loopLength = step_count / 2 → notes occupy (lick_length / (step_count/2)) fraction
static bool test_loop_length_half_step_count() {
    printf("\n  Test: loop_length = step_count/2 — proportional duty cycle\n");

    const int step_count = 32;
    PulsarStep steps[kMaxPulsarSteps] = {};
    PulsarLickStep lick[8];
    make_test_lick(lick, 8, 0.5f);  // 8 eighth notes = 16 sequencer steps of note data
    PulsarScale scale = test_minor_scale();
    uint32_t seed = 42;

    // lick_loop_length = 16 = step_count/2, lick_length = 8
    // Duty cycle = 8/16 = 50% → play_steps = max(16, 32*8/16) = 16
    generate_lick_pattern(steps, step_count, lick, 8, 0.0f, 48, scale, seed,
                          0, -1, 36, 72, 16);

    int first_half_gates = count_gates(steps, 16);
    int second_half_gates = count_gates(steps + 16, 16);

    bool ok = first_half_gates >= 8 && second_half_gates == 0;
    printf("    first_half_gates=%d (expect >= 8), second_half_gates=%d (expect 0) -- %s\n",
           first_half_gates, second_half_gates, ok ? "PASS" : "FAIL");
    return ok;
}

// loopLength == lick_length should produce same result as loopLength = 0
static bool test_loop_length_equals_lick_length() {
    printf("\n  Test: loop_length == lick_length — same as default\n");

    const int step_count = 32;
    PulsarStep steps_default[kMaxPulsarSteps] = {};
    PulsarStep steps_explicit[kMaxPulsarSteps] = {};
    PulsarLickStep lick[6];
    make_test_lick(lick, 6, 1.0f);
    PulsarScale scale = test_minor_scale();
    uint32_t seed = 42;

    generate_lick_pattern(steps_default, step_count, lick, 6, 0.0f, 48, scale, seed,
                          0, -1, 36, 72, 0);
    generate_lick_pattern(steps_explicit, step_count, lick, 6, 0.0f, 48, scale, seed,
                          0, -1, 36, 72, 6);

    bool identical = true;
    for (int i = 0; i < step_count; i++) {
        if (steps_default[i].gate != steps_explicit[i].gate ||
            steps_default[i].note != steps_explicit[i].note) {
            identical = false;
            break;
        }
    }

    printf("    identical=%s -- %s\n",
           identical ? "yes" : "no", identical ? "PASS" : "FAIL");
    return identical;
}

// Dust Groove scenario: 8 steps with varying durations, loopLength=32, stepCount=32
static bool test_dust_groove_scenario() {
    printf("\n  Test: Dust Groove scenario — 8 mixed-duration steps, loopLength=32\n");

    const int step_count = 32;
    PulsarStep steps[kMaxPulsarSteps] = {};
    PulsarLickStep lick[8];
    // Dust Groove durations: 0.5, 0.25, 0.25, 0.5, 0.5, 0.5, 0.5, 1.0 = 4.0 beats
    lick[0] = {0, 0.5f, 0.90f};
    lick[1] = {0, 0.25f, 0.80f};
    lick[2] = {0, 0.25f, 0.85f};
    lick[3] = {0, 0.5f, 0.80f};
    lick[4] = {2, 0.5f, 0.85f};
    lick[5] = {0, 0.5f, 0.90f};
    lick[6] = {-1, 0.5f, 0.75f};  // rest
    lick[7] = {-2, 1.0f, 0.80f};  // rest

    PulsarScale scale = test_minor_scale();
    uint32_t seed = 42;

    // loopLength=32, lick_length=8 → duty cycle = 8/32 = 25%
    // Note beats = 4.0 → note_steps = 16
    // play_steps = max(16, 32*8/32) = max(16, 8) = 16
    generate_lick_pattern(steps, step_count, lick, 8, 0.0f, 40, scale, seed,
                          0, 3, 33, 52, 32);

    int total_gates = count_gates(steps, step_count);
    int last_gated = -1;
    for (int i = step_count - 1; i >= 0; i--) {
        if (steps[i].gate) { last_gated = i; break; }
    }
    int final_quarter_gates = count_gates(steps + 24, 8);

    // Notes should be confined to first half, last quarter should be silent
    bool ok = total_gates >= 4 && total_gates <= 12 && final_quarter_gates == 0;
    printf("    gates=%d, last_gated=%d, final_quarter_gates=%d -- %s\n",
           total_gates, last_gated, final_quarter_gates, ok ? "PASS" : "FAIL");
    return ok;
}

// The Blues scale (kPulsarScales index 13, ScaleType.BLUES) must expose the b5
// "blue note" — the whole reason it exists. Render a lick that walks R, b3, 4, b5
// through the real wired table and assert the flat-fifth lands at root + 6 semis.
static bool test_blues_scale_renders_flat_five() {
    printf("\n  Test: Blues scale renders the b5 blue note\n");

    const PulsarScale& blues = kPulsarScales[13];  // 13 = Blues (minor blues, hexatonic)
    bool degrees_ok = blues.count == 6 &&
        blues.degrees[0] == 0 && blues.degrees[1] == 3 && blues.degrees[2] == 5 &&
        blues.degrees[3] == 6 && blues.degrees[4] == 7 && blues.degrees[5] == 10;

    // R, b3, 4, b5 — degree 3 is the b5. 0.25-beat steps = 1 slot each.
    PulsarLickStep lick[4] = {
        {0, 0.25f, 0.9f, -1.0f},
        {1, 0.25f, 0.9f, -1.0f},
        {2, 0.25f, 0.9f, -1.0f},
        {3, 0.25f, 0.9f, -1.0f},
    };
    PulsarStep steps[kMaxPulsarSteps] = {};
    const int root = 36;  // C2
    // lick_octave 0 → base offset 0; chord_degree 0; no rest padding.
    generate_lick_pattern(steps, 16, lick, 4, 0.0f, (uint8_t)root, blues, 7u,
                          0, 0, 36, 72, 0);

    // Onsets at steps 0..3 (1 slot each): R, b3, 4, b5.
    bool notes_ok = steps[0].note == root + 0 &&   // R
                    steps[1].note == root + 3 &&   // b3
                    steps[2].note == root + 5 &&   // 4
                    steps[3].note == root + 6;     // b5 (the blue note)

    bool ok = degrees_ok && notes_ok;
    printf("    degrees_ok=%s, b5_note=%d (expect %d) -- %s\n",
           degrees_ok ? "yes" : "no", steps[3].note, root + 6, ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_pattern_gen_tests() {
    printf("\n=== Pulsar Pattern Gen Tests ===\n");
    int pass = 0, fail = 0;

    test_density_zero_matches_sparse()      ? pass++ : fail++;
    test_density_one_is_dense()             ? pass++ : fail++;
    test_density_blend_monotonic()          ? pass++ : fail++;
    test_loop_length_default_fills_pattern() ? pass++ : fail++;
    test_loop_length_4x_sparse()            ? pass++ : fail++;
    test_loop_length_2x_half()              ? pass++ : fail++;
    test_loop_length_half_step_count()      ? pass++ : fail++;
    test_loop_length_equals_lick_length()   ? pass++ : fail++;
    test_dust_groove_scenario()             ? pass++ : fail++;
    test_blues_scale_renders_flat_five()    ? pass++ : fail++;

    printf("\n  Results: %d passed, %d failed\n", pass, fail);
    TEST_SUITE_RETURN(pass, fail);
}
