// Tests for Pulsar bar strategy algorithms.
// Covers: REPEAT, MUTATE, FILL, CALL_RESPONSE, INDEPENDENT, and 16-step compat.
#include "test_harness.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_pattern_gen.h"
#include "../src/pulsar_bar_strategy.h"
#include <cstdio>
#include <cstring>
#include <cmath>

// ── Helpers ───────────────────────────────────────────────────────────────

static PulsarGenreProfile make_test_genre() {
    PulsarGenreProfile g;
    float density[] = {0.50f, 0.35f, 0.80f, 0.40f, 0.30f, 0.20f, 0.15f, 0.08f};
    for (int i = 0; i < 8; i++) g.base_density[i] = density[i];
    g.swing_amount = 0.02f;
    g.ghost_probability = 0.3f;
    g.note_range_low = 36;
    g.note_range_high = 72;
    g.rhythm_density = 1.0f; // dense-16th
    return g;
}

// ── Test suite ─────────────────────────────────────────────────────────────

bool run_bar_strategy_tests() {
    printf("\n=== Bar Strategy Tests ===\n\n");
    int pass = 0, fail = 0;

    const PulsarScale& scale = kPulsarScales[0];  // Minor
    const uint8_t root = 2;                        // D
    const uint32_t seed = 0xDEAD1234u;
    PulsarGenreProfile genre = make_test_genre();

    // ── Test 1: REPEAT copies bar 1 into bar 2 ───────────────────────────
    {
        printf("  Test 1: REPEAT — bar 2 is byte-for-byte copy of bar 1\n");

        PulsarTrackState ts;
        std::memset((void*)&ts, 0, sizeof(ts));
        generate_track_pattern(ts, 0, true, genre, root, scale, 16, seed);

        PulsarStep bar1_copy[16];
        std::memcpy(bar1_copy, ts.steps, 16 * sizeof(PulsarStep));

        bar_strategy_repeat(ts.steps, 16);

        bool ok = (std::memcmp(bar1_copy, &ts.steps[16], 16 * sizeof(PulsarStep)) == 0);
        if (ok) {
            printf("    PASS: steps[16..31] match steps[0..15]\n");
            pass++;
        } else {
            printf("    FAIL: bar 2 differs from bar 1 after REPEAT\n");
            fail++;
        }
    }

    // ── Test 2: MUTATE at complexity=0 is identical to REPEAT ────────────
    {
        printf("  Test 2: MUTATE at complexity=0.0 is identical to REPEAT\n");

        // Build two identical track states from the same seed
        PulsarTrackState ts_repeat;
        std::memset((void*)&ts_repeat, 0, sizeof(ts_repeat));
        generate_track_pattern(ts_repeat, 0, true, genre, root, scale, 16, seed);

        PulsarTrackState ts_mutate;
        std::memset((void*)&ts_mutate, 0, sizeof(ts_mutate));
        generate_track_pattern(ts_mutate, 0, true, genre, root, scale, 16, seed);

        bar_strategy_repeat(ts_repeat.steps, 16);

        uint32_t mut_seed = seed;
        bar_strategy_mutate(ts_mutate.steps, 16, 0, 0.0f, root, scale, mut_seed);

        // Compare fields, not bytes — struct padding can differ
        bool ok = true;
        for (int i = 0; i < 16 && ok; i++) {
            const PulsarStep& r = ts_repeat.steps[16 + i];
            const PulsarStep& m = ts_mutate.steps[16 + i];
            if (r.note != m.note || r.raw_note != m.raw_note ||
                r.velocity != m.velocity || r.gate != m.gate ||
                r.duration != m.duration) {
                ok = false;
                printf("      step[%d] differs: repeat(n=%d v=%.4f g=%d d=%.4f) "
                       "mutate(n=%d v=%.4f g=%d d=%.4f)\n",
                       i, r.note, r.velocity, r.gate, r.duration,
                       m.note, m.velocity, m.gate, m.duration);
            }
        }
        if (ok) {
            printf("    PASS: MUTATE(complexity=0) equals REPEAT\n");
            pass++;
        } else {
            printf("    FAIL: MUTATE(complexity=0) differs from REPEAT\n");
            fail++;
        }
    }

    // ── Test 3: MUTATE at complexity=1 changes at least one step ─────────
    {
        printf("  Test 3: MUTATE at complexity=1.0 produces at least one changed step\n");

        // Use a well-populated track: dense hihat pattern (track 2)
        PulsarTrackState ts;
        std::memset((void*)&ts, 0, sizeof(ts));
        generate_track_pattern(ts, 2, true, genre, root, scale, 16, seed);

        PulsarStep bar1_copy[16];
        std::memcpy(bar1_copy, ts.steps, 16 * sizeof(PulsarStep));

        uint32_t mut_seed = seed;
        bar_strategy_mutate(ts.steps, 16, 2, 1.0f, root, scale, mut_seed);

        bool any_diff = false;
        for (int i = 0; i < 16; i++) {
            const PulsarStep& b1 = bar1_copy[i];
            const PulsarStep& b2 = ts.steps[16 + i];
            if (b1.gate != b2.gate ||
                b1.note != b2.note ||
                std::fabs(b1.velocity - b2.velocity) > 0.001f) {
                any_diff = true;
                break;
            }
        }

        if (any_diff) {
            printf("    PASS: at least one step differs at complexity=1\n");
            pass++;
        } else {
            printf("    FAIL: no step changed at complexity=1 (unexpected)\n");
            fail++;
        }
    }

    // ── Test 4: MUTATE melodic track — gated notes stay in scale ─────────
    {
        printf("  Test 4: MUTATE melodic track — gated bar 2 notes are in scale\n");

        PulsarTrackState ts;
        std::memset((void*)&ts, 0, sizeof(ts));
        generate_track_pattern(ts, 3, false, genre, root, scale, 16, seed);

        uint32_t mut_seed = seed;
        bar_strategy_mutate(ts.steps, 16, 3, 0.5f, root, scale, mut_seed);

        bool all_in_scale = true;
        int gated_count = 0;
        for (int i = 16; i < 32; i++) {
            const PulsarStep& step = ts.steps[i];
            if (!step.gate) continue;
            gated_count++;

            // Check that step.note is a valid note in the scale (any octave)
            int rel = ((int)step.note - (int)root + 120) % 12;
            bool found = false;
            for (int d = 0; d < scale.count; d++) {
                if (scale.degrees[d] == (uint8_t)rel) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                printf("    note %d (rel %d) not in scale\n", step.note, rel);
                all_in_scale = false;
            }
        }

        if (all_in_scale) {
            printf("    PASS: all %d gated notes in bar 2 are in scale\n", gated_count);
            pass++;
        } else {
            printf("    FAIL: some bar 2 notes are not in scale\n");
            fail++;
        }
    }

    // ── Test 5: FILL — last 4 steps have gate=true ────────────────────────
    {
        printf("  Test 5: FILL — steps[28..31] all have gate=true\n");

        PulsarTrackState ts;
        std::memset((void*)&ts, 0, sizeof(ts));
        generate_track_pattern(ts, 1, true, genre, root, scale, 16, seed);

        uint32_t fill_seed = seed;
        bar_strategy_fill(ts.steps, 16, 1, 1.0f, 0.5f, root, scale, fill_seed);

        bool all_gated = true;
        for (int i = 28; i < 32; i++) {
            if (!ts.steps[i].gate) {
                printf("    step %d gate=false\n", i);
                all_gated = false;
            }
        }

        if (all_gated) {
            printf("    PASS: steps[28..31] all gated\n");
            pass++;
        } else {
            printf("    FAIL: some fill steps not gated\n");
            fail++;
        }
    }

    // ── Test 6: FILL velocity ascending in last 4 steps ──────────────────
    {
        printf("  Test 6: FILL — steps[28..31] have non-decreasing velocity\n");

        PulsarTrackState ts;
        std::memset((void*)&ts, 0, sizeof(ts));
        generate_track_pattern(ts, 1, true, genre, root, scale, 16, seed);

        uint32_t fill_seed = seed;
        bar_strategy_fill(ts.steps, 16, 1, 1.0f, 0.5f, root, scale, fill_seed);

        bool ascending = true;
        for (int i = 29; i < 32; i++) {
            if (ts.steps[i].velocity < ts.steps[i-1].velocity - 0.001f) {
                printf("    step %d vel=%.3f < step %d vel=%.3f\n",
                       i, ts.steps[i].velocity, i-1, ts.steps[i-1].velocity);
                ascending = false;
            }
        }

        if (ascending) {
            printf("    PASS: fill velocities non-decreasing (%.3f %.3f %.3f %.3f)\n",
                   ts.steps[28].velocity, ts.steps[29].velocity,
                   ts.steps[30].velocity, ts.steps[31].velocity);
            pass++;
        } else {
            printf("    FAIL: fill velocities not ascending\n");
            fail++;
        }
    }

    // ── Test 7: CALL_RESPONSE with lick ──────────────────────────────────
    {
        printf("  Test 7: CALL_RESPONSE — call has notes, response exists\n");

        // 4-step lick: degrees 0,2,4,2 at 0.5 duration each
        static const PulsarLickStep lick[4] = {
            {0, 0.5f, 0.8f},
            {2, 0.5f, 0.7f},
            {4, 0.5f, 0.7f},
            {2, 0.5f, 0.6f},
        };

        PulsarStep steps[32];
        std::memset(steps, 0, sizeof(steps));

        uint32_t cr_seed = seed;
        bar_strategy_call_response(steps, 32, lick, 4, 0.0f, root, scale, cr_seed);

        // Check: call portion (steps 0..15) has at least one gated note
        int call_gated = 0;
        for (int i = 0; i < 16; i++) if (steps[i].gate) call_gated++;

        // Check: response portion (steps 16..31) has at least one gated note
        int resp_gated = 0;
        for (int i = 16; i < 32; i++) if (steps[i].gate) resp_gated++;

        if (call_gated > 0 && resp_gated > 0) {
            printf("    PASS: call=%d gated, response=%d gated\n",
                   call_gated, resp_gated);
            pass++;
        } else {
            printf("    FAIL: call_gated=%d, resp_gated=%d (both should be > 0)\n",
                   call_gated, resp_gated);
            fail++;
        }
    }

    // ── Test 8: CALL_RESPONSE determinism ────────────────────────────────
    {
        printf("  Test 8: CALL_RESPONSE — same seed produces identical output\n");

        static const PulsarLickStep lick[4] = {
            {0, 0.5f, 0.8f},
            {2, 0.5f, 0.7f},
            {4, 0.5f, 0.7f},
            {2, 0.5f, 0.6f},
        };

        PulsarStep steps_a[32], steps_b[32];
        std::memset(steps_a, 0, sizeof(steps_a));
        std::memset(steps_b, 0, sizeof(steps_b));

        uint32_t seed_a = seed;
        uint32_t seed_b = seed;

        bar_strategy_call_response(steps_a, 32, lick, 4, 0.3f, root, scale, seed_a);
        bar_strategy_call_response(steps_b, 32, lick, 4, 0.3f, root, scale, seed_b);

        bool identical = (std::memcmp(steps_a, steps_b, 32 * sizeof(PulsarStep)) == 0);
        if (identical) {
            printf("    PASS: two calls with same seed produce identical output\n");
            pass++;
        } else {
            printf("    FAIL: outputs differ despite same seed\n");
            fail++;
        }
    }

    // ── Test 9: INDEPENDENT differs from bar 1 ───────────────────────────
    {
        printf("  Test 9: INDEPENDENT — bar 2 differs from bar 1\n");

        PulsarTrackState ts;
        std::memset((void*)&ts, 0, sizeof(ts));
        generate_track_pattern(ts, 0, true, genre, root, scale, 16, seed);

        PulsarStep bar1_copy[16];
        std::memcpy(bar1_copy, ts.steps, 16 * sizeof(PulsarStep));

        uint32_t ind_seed = seed;
        bar_strategy_independent(ts, 16, 0, true, genre, root, scale, ind_seed);

        bool any_diff = false;
        for (int i = 0; i < 16; i++) {
            const PulsarStep& b1 = bar1_copy[i];
            const PulsarStep& b2 = ts.steps[16 + i];
            if (b1.gate != b2.gate ||
                b1.note != b2.note ||
                std::fabs(b1.velocity - b2.velocity) > 0.001f) {
                any_diff = true;
                break;
            }
        }

        if (any_diff) {
            printf("    PASS: bar 2 differs from bar 1 with INDEPENDENT\n");
            pass++;
        } else {
            printf("    FAIL: bar 2 identical to bar 1 (INDEPENDENT failed to vary)\n");
            fail++;
        }
    }

    // ── Test 10: 16-step backward compat ─────────────────────────────────
    {
        printf("  Test 10: 16-step backward compat — generate 16 steps, no bar strategy\n");

        PulsarTrackState ts;
        std::memset((void*)&ts, 0, sizeof(ts));
        generate_track_pattern(ts, 0, true, genre, root, scale, 16, seed);

        if (ts.step_count == 16) {
            // Verify all gated steps have valid velocities
            bool plausible = true;
            for (int i = 0; i < 16; i++) {
                if (ts.steps[i].gate) {
                    if (ts.steps[i].velocity < 0.0f || ts.steps[i].velocity > 1.0f) {
                        plausible = false;
                    }
                }
            }
            if (plausible) {
                printf("    PASS: step_count=16, all gated steps have valid velocities\n");
                pass++;
            } else {
                printf("    FAIL: some gated steps have out-of-range velocities\n");
                fail++;
            }
        } else {
            printf("    FAIL: expected step_count=16, got %d\n", ts.step_count);
            fail++;
        }
    }

    printf("\n  Bar Strategy: %d passed, %d failed\n", pass, fail);
    TEST_SUITE_RETURN(pass, fail);
}
