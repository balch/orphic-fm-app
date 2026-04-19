// Tests for Pulsar chord progression system.
// Covers: init, advance, wrap, mutation, degree-to-semitones, bass chord root.
#include "test_harness.h"
#include "../src/pulsar_chord_progression.h"
#include "../src/pulsar_pattern_gen.h"
#include "../src/orpheus_unit_pulsar.h"
#include <cstdio>
#include <cstring>

bool run_pulsar_chords_tests() {
    printf("\n=== Pulsar Chords Tests ===\n\n");
    int pass = 0, fail = 0;

    // ── Test 1: init_pop_progression ─────────────────────────────────────
    {
        printf("  Test 1: Init PROG_POP — progression is [0,4,5,3], length=4\n");

        PulsarChordState cs;
        std::memset(&cs, 0, sizeof(cs));
        init_chord_progression(cs, PROG_POP, 2, 16, 42);

        bool ok = (cs.progression_length == 4 &&
                   cs.progression[0] == 0 &&
                   cs.progression[1] == 4 &&
                   cs.progression[2] == 5 &&
                   cs.progression[3] == 3 &&
                   cs.steps_per_chord == 8);  // 16 / 2 = 8
        if (ok) {
            printf("    PASS: progression=[%d,%d,%d,%d] length=%d steps_per_chord=%d\n",
                   cs.progression[0], cs.progression[1], cs.progression[2], cs.progression[3],
                   cs.progression_length, cs.steps_per_chord);
            pass++;
        } else {
            printf("    FAIL: progression=[%d,%d,%d,%d] length=%d steps_per_chord=%d\n",
                   cs.progression[0], cs.progression[1], cs.progression[2], cs.progression[3],
                   cs.progression_length, cs.steps_per_chord);
            fail++;
        }
    }

    // ── Test 2: init_blues_progression ───────────────────────────────────
    {
        printf("  Test 2: Init PROG_BLUES — 8-chord progression, correct steps_per_chord\n");

        PulsarChordState cs;
        std::memset(&cs, 0, sizeof(cs));
        init_chord_progression(cs, PROG_BLUES, 4, 32, 42);

        bool ok = (cs.progression_length == 8 &&
                   cs.progression[0] == 0 &&
                   cs.progression[1] == 0 &&
                   cs.progression[2] == 3 &&
                   cs.progression[3] == 3 &&
                   cs.progression[4] == 4 &&
                   cs.progression[5] == 3 &&
                   cs.progression[6] == 0 &&
                   cs.progression[7] == 4 &&
                   cs.steps_per_chord == 8);  // 32 / 4 = 8
        if (ok) {
            printf("    PASS: length=%d steps_per_chord=%d\n",
                   cs.progression_length, cs.steps_per_chord);
            pass++;
        } else {
            printf("    FAIL: length=%d steps_per_chord=%d prog=[%d,%d,%d,%d,%d,%d,%d,%d]\n",
                   cs.progression_length, cs.steps_per_chord,
                   cs.progression[0], cs.progression[1], cs.progression[2], cs.progression[3],
                   cs.progression[4], cs.progression[5], cs.progression[6], cs.progression[7]);
            fail++;
        }
    }

    // ── Test 3: init_clamps_invalid_style ────────────────────────────────
    {
        printf("  Test 3: Init clamps invalid style values\n");

        PulsarChordState cs_high;
        std::memset(&cs_high, 0, sizeof(cs_high));
        init_chord_progression(cs_high, 99, 2, 16, 42);

        PulsarChordState cs_neg;
        std::memset(&cs_neg, 0, sizeof(cs_neg));
        init_chord_progression(cs_neg, -5, 2, 16, 42);

        // style=99 should clamp to last (PROG_ASCENDING = 7): [0,1,2,3]
        // style=-5 should clamp to first (PROG_POP = 0): [0,4,5,3]
        bool ok_high = (cs_high.progression_length == 4 &&
                        cs_high.progression[0] == 0 &&
                        cs_high.progression[1] == 1 &&
                        cs_high.progression[2] == 2 &&
                        cs_high.progression[3] == 3);
        bool ok_neg = (cs_neg.progression_length == 4 &&
                       cs_neg.progression[0] == 0 &&
                       cs_neg.progression[1] == 4 &&
                       cs_neg.progression[2] == 5 &&
                       cs_neg.progression[3] == 3);

        if (ok_high && ok_neg) {
            printf("    PASS: style=99 clamped to ASCENDING, style=-5 clamped to POP\n");
            pass++;
        } else {
            printf("    FAIL: high=[%d,%d,%d,%d] neg=[%d,%d,%d,%d]\n",
                   cs_high.progression[0], cs_high.progression[1],
                   cs_high.progression[2], cs_high.progression[3],
                   cs_neg.progression[0], cs_neg.progression[1],
                   cs_neg.progression[2], cs_neg.progression[3]);
            fail++;
        }
    }

    // ── Test 4: advance_cycles_through_progression ───────────────────────
    {
        printf("  Test 4: Advance cycles through progression correctly\n");

        PulsarChordState cs;
        std::memset(&cs, 0, sizeof(cs));
        init_chord_progression(cs, PROG_POP, 2, 16, 42);
        // steps_per_chord = 8, progression = [0,4,5,3]

        // Initially at chord 0
        bool ok = (cs.progression[cs.chord_index] == 0);

        // Advance 8 times — should move to chord index 1 (degree 4)
        for (int i = 0; i < 8; i++) advance_chord(cs, 0.0f, 0.5f);
        ok = ok && (cs.chord_index == 1) && (cs.progression[cs.chord_index] == 4);

        // Advance 8 more — chord index 2 (degree 5)
        for (int i = 0; i < 8; i++) advance_chord(cs, 0.0f, 0.5f);
        ok = ok && (cs.chord_index == 2) && (cs.progression[cs.chord_index] == 5);

        // Advance 8 more — chord index 3 (degree 3)
        for (int i = 0; i < 8; i++) advance_chord(cs, 0.0f, 0.5f);
        ok = ok && (cs.chord_index == 3) && (cs.progression[cs.chord_index] == 3);

        if (ok) {
            printf("    PASS: chord advances through [0,4,5,3] every 8 steps\n");
            pass++;
        } else {
            printf("    FAIL: chord_index=%d degree=%d\n",
                   cs.chord_index, cs.progression[cs.chord_index]);
            fail++;
        }
    }

    // ── Test 5: advance_wraps_to_start ───────────────────────────────────
    {
        printf("  Test 5: Advance wraps back to chord 0 after full cycle\n");

        PulsarChordState cs;
        std::memset(&cs, 0, sizeof(cs));
        init_chord_progression(cs, PROG_POP, 2, 16, 42);
        // 4 chords * 8 steps = 32 steps for full cycle

        // Advance 32 times (with complexity=0 so no mutation)
        for (int i = 0; i < 32; i++) advance_chord(cs, 0.0f, 0.5f);

        bool ok = (cs.chord_index == 0) && (cs.progression[cs.chord_index] == 0);
        if (ok) {
            printf("    PASS: wrapped back to chord_index=0, degree=0\n");
            pass++;
        } else {
            printf("    FAIL: chord_index=%d degree=%d\n",
                   cs.chord_index, cs.progression[cs.chord_index]);
            fail++;
        }
    }

    // ── Test 6: mutation_preserves_first_chord ───────────────────────────
    {
        printf("  Test 6: Mutation preserves first chord (tonic anchor)\n");

        PulsarChordState cs;
        std::memset(&cs, 0, sizeof(cs));
        init_chord_progression(cs, PROG_POP, 2, 16, 42);
        int steps_per_cycle = cs.progression_length * cs.steps_per_chord;

        bool first_always_zero = true;
        for (int cycle = 0; cycle < 100; cycle++) {
            for (int i = 0; i < steps_per_cycle; i++) {
                advance_chord(cs, 1.0f, 0.5f);  // max complexity -> mutations happen
            }
            if (cs.progression[0] != 0) {
                first_always_zero = false;
                break;
            }
        }

        if (first_always_zero) {
            printf("    PASS: progression[0] remained 0 across 100 cycles\n");
            pass++;
        } else {
            printf("    FAIL: progression[0] changed to %d\n", cs.progression[0]);
            fail++;
        }
    }

    // ── Test 7: mutation_stays_in_range ──────────────────────────────────
    {
        printf("  Test 7: Mutation keeps all degrees in range 0-6\n");

        PulsarChordState cs;
        std::memset(&cs, 0, sizeof(cs));
        init_chord_progression(cs, PROG_POP, 2, 16, 42);
        int steps_per_cycle = cs.progression_length * cs.steps_per_chord;

        bool in_range = true;
        for (int cycle = 0; cycle < 200; cycle++) {
            for (int i = 0; i < steps_per_cycle; i++) {
                advance_chord(cs, 1.0f, 0.5f);
            }
            // Check all progression entries
            for (int p = 0; p < cs.progression_length; p++) {
                if (cs.progression[p] < 0 || cs.progression[p] > 6) {
                    printf("    FAIL: progression[%d]=%d out of range at cycle %d\n",
                           p, cs.progression[p], cycle);
                    in_range = false;
                    break;
                }
            }
            if (!in_range) break;
        }

        if (in_range) {
            printf("    PASS: all degrees in [0,6] across 200 cycles\n");
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 8: chord_degree_semitones_minor ─────────────────────────────
    {
        printf("  Test 8: chord_degree_to_semitones with minor scale\n");

        const PulsarScale& minor = kPulsarScales[0];  // {7, {0,2,3,5,7,8,10,...}}
        int expected[] = {0, 2, 3, 5, 7, 8, 10};
        bool ok = true;
        for (int d = 0; d < 7; d++) {
            int got = chord_degree_to_semitones(d, minor);
            if (got != expected[d]) {
                printf("    FAIL: degree %d -> %d (expected %d)\n", d, got, expected[d]);
                ok = false;
            }
        }
        if (ok) {
            printf("    PASS: minor degrees [0,2,3,5,7,8,10] correct\n");
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 9: chord_degree_semitones_major ─────────────────────────────
    {
        printf("  Test 9: chord_degree_to_semitones with major scale\n");

        const PulsarScale& major = kPulsarScales[1];  // {7, {0,2,4,5,7,9,11,...}}
        int expected[] = {0, 2, 4, 5, 7, 9, 11};
        bool ok = true;
        for (int d = 0; d < 7; d++) {
            int got = chord_degree_to_semitones(d, major);
            if (got != expected[d]) {
                printf("    FAIL: degree %d -> %d (expected %d)\n", d, got, expected[d]);
                ok = false;
            }
        }
        if (ok) {
            printf("    PASS: major degrees [0,2,4,5,7,9,11] correct\n");
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 10: bass_uses_chord_root ────────────────────────────────────
    {
        printf("  Test 10: Bass pattern uses chord root (different first note for different degrees)\n");

        const PulsarScale& minor = kPulsarScales[0];
        uint8_t root = 0;  // C
        uint32_t seed_a = 0xBEEF1234u;
        uint32_t seed_b = 0xBEEF1234u;  // same seed

        PulsarGenreProfile genre;
        std::memset(&genre, 0, sizeof(genre));
        float density[] = {0.50f, 0.35f, 0.80f, 0.40f, 0.30f, 0.20f, 0.15f, 0.08f};
        for (int i = 0; i < 8; i++) genre.base_density[i] = density[i];
        genre.swing_amount = 0.02f;
        genre.ghost_probability = 0.3f;
        genre.note_range_low = 36;
        genre.note_range_high = 72;
        genre.rhythm_density = 1.0f;

        PulsarStep steps_a[16];
        PulsarStep steps_b[16];

        // chord_degree=0 (root=C, bass should start around C3=36)
        generate_melodic_pattern(steps_a, 16, 3, genre, root, minor, 0, seed_a);
        // chord_degree=4 (V in minor: degree 4 -> semitone 7 -> G, bass around G3=43)
        generate_melodic_pattern(steps_b, 16, 3, genre, root, minor, 4, seed_b);

        // First step should be gated (bass always hits beat 1)
        bool ok = steps_a[0].gate && steps_b[0].gate;
        // The notes should differ because chord roots differ
        bool notes_differ = (steps_a[0].note != steps_b[0].note);

        if (ok && notes_differ) {
            printf("    PASS: bass note with chord_degree=0 is %d, chord_degree=4 is %d\n",
                   steps_a[0].note, steps_b[0].note);
            pass++;
        } else {
            printf("    FAIL: gate_a=%d gate_b=%d note_a=%d note_b=%d\n",
                   steps_a[0].gate, steps_b[0].gate,
                   steps_a[0].note, steps_b[0].note);
            fail++;
        }
    }

    // ── Test: Custom matrix overrides built-in ────────────────────────────
    {
        printf("  Test: Custom matrix — forced I->V transition\n");

        PulsarChordState cs{};

        // Create a custom matrix that ALWAYS goes to degree 4 (V)
        float custom[7][7] = {};
        for (int r = 0; r < 7; r++) custom[r][4] = 1.0f;

        cs.use_custom_matrix = true;
        std::memcpy(cs.custom_matrix, custom, sizeof(custom));

        init_chord_progression(cs, PROG_POP, 2, 16, 42);
        // progression = [0,4,5,3], length=4

        // init_chord_progression zeroes drift_range (no drift by default). Set it to 1.0
        // so the drift clamp in maybe_mutate_progression allows the custom matrix to
        // actually change chord degrees. Without this, max_drift=0 and every mutation
        // is immediately clamped back to the original value — custom matrix is a no-op.
        cs.drift_range = 1.0f;

        // Run many full cycles at max complexity. After enough mutations,
        // every mutable slot (1,2,3) should converge to degree 4 (V).
        // Mutation probability per cycle is ~28%, and each mutation picks one
        // random position, so we need many cycles to saturate all positions.
        int steps_per_cycle = cs.progression_length * cs.steps_per_chord;
        for (int cycle = 0; cycle < 500; cycle++) {
            for (int s = 0; s < steps_per_cycle; s++) {
                advance_chord(cs, 1.0f, 0.5f);
            }
        }

        // Position 0 should be unchanged (tonic anchor), positions 1-3 should be V (4)
        int became_V = 0;
        for (int p = 1; p < cs.progression_length; p++) {
            if (cs.progression[p] == 4) became_V++;
        }

        bool ok = became_V == cs.progression_length - 1;  // all mutable slots = V
        printf("    mutable slots at V: %d/%d -- %s\n", became_V,
               cs.progression_length - 1, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    // ── Anchor / drift tests ─────────────────────────────────────────
    {
        printf("  Test 11: anchor=0 (NONE) allows progression to diverge\n");
        PulsarChordState cs{};
        init_chord_progression(cs, 0 /* POP */, 2, 16, 0xC0FFEEu);
        cs.anchor_bars = 0;
        cs.drift_range = 1.0f;
        int8_t initial[kMaxProgressionLength];
        std::memcpy(initial, cs.progression, sizeof(initial));
        // Advance enough bars to trigger many mutations at max complexity
        for (int bar = 0; bar < 100; bar++) {
            for (int step = 0; step < 16; step++) {
                advance_chord(cs, 1.0f, 1.0f);
            }
        }
        bool ok = std::memcmp(cs.progression, initial, cs.progression_length) != 0;
        if (ok) { printf("    PASS: progression mutated\n"); pass++; }
        else { printf("    FAIL: no mutation observed\n"); fail++; }
    }

    {
        printf("  Test 12: anchor>0 resets progression to original\n");
        PulsarChordState cs{};
        init_chord_progression(cs, 0 /* POP */, 2, 16, 0xC0FFEEu);
        cs.anchor_bars = 4;
        cs.drift_range = 1.0f;
        // Manually corrupt working progression
        cs.progression[1] = 6;
        cs.progression[2] = 6;
        cs.bars_since_anchor = 4;  // ready to trigger reset on next wrap
        // Force a progression wrap: set chord_index to last, step counter one from boundary
        cs.chord_index = cs.progression_length - 1;
        cs.chord_step_counter = cs.steps_per_chord - 1;
        // One more step should advance past boundary, wrap chord_index to 0, and reset
        advance_chord(cs, 1.0f, 1.0f);
        bool ok = std::memcmp(cs.progression, cs.original_progression,
                              cs.progression_length) == 0;
        if (ok) { printf("    PASS: progression reset to original after anchor boundary\n"); pass++; }
        else {
            printf("    FAIL: progression=");
            for (int i = 0; i < cs.progression_length; i++) printf("%d ", cs.progression[i]);
            printf("original=");
            for (int i = 0; i < cs.progression_length; i++) printf("%d ", cs.original_progression[i]);
            printf("\n");
            fail++;
        }
    }

    {
        printf("  Test 13: drift_range=0 produces no drift even at mood=1.0\n");
        PulsarChordState cs{};
        init_chord_progression(cs, 0 /* POP */, 2, 16, 0x1234u);
        cs.anchor_bars = 0;
        cs.drift_range = 0.0f;
        int8_t initial[kMaxProgressionLength];
        std::memcpy(initial, cs.progression, sizeof(initial));
        for (int bar = 0; bar < 100; bar++) {
            for (int step = 0; step < 16; step++) {
                advance_chord(cs, 1.0f, 1.0f);
            }
        }
        // max_drift = 1.0 * 0.0 * 6 = 0 → any mutation clamps back to original
        bool ok = std::memcmp(cs.progression, initial, cs.progression_length) == 0;
        if (ok) { printf("    PASS: progression unchanged\n"); pass++; }
        else {
            printf("    FAIL: progression drifted despite drift_range=0\n");
            fail++;
        }
    }

    {
        printf("  Test 14: drift_range bounds stray from original\n");
        PulsarChordState cs{};
        init_chord_progression(cs, 0 /* POP */, 2, 16, 0x9999u);
        cs.anchor_bars = 0;
        cs.drift_range = 0.25f;  // max_drift = 1.0 * 0.25 * 6 = 1.5 → round to 2
        int8_t original[kMaxProgressionLength];
        std::memcpy(original, cs.progression, sizeof(original));
        for (int bar = 0; bar < 200; bar++) {
            for (int step = 0; step < 16; step++) {
                advance_chord(cs, 1.0f, 1.0f);
            }
        }
        bool ok = true;
        for (int i = 0; i < cs.progression_length; i++) {
            int diff = std::abs(cs.progression[i] - original[i]);
            if (diff > 2) { ok = false; break; }
        }
        if (ok) { printf("    PASS: all chords within +/-2 of original\n"); pass++; }
        else {
            printf("    FAIL: out-of-bounds drift\n");
            fail++;
        }
    }

    // ── Summary ──────────────────────────────────────────────────────────
    printf("\n  Pulsar Chords: %d passed, %d failed\n", pass, fail);
    TEST_SUITE_RETURN(pass, fail);
}
