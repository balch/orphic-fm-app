#include "test_harness.h"
#include "../src/pulsar_comping.h"
#include <cstdio>

bool run_pulsar_comping_tests() {
    printf("\n=== Pulsar Comping Tests ===\n\n");
    int pass = 0, fail = 0;

    const PulsarScale& minor = kPulsarScales[0];  // Minor

    // ── Template lookup ─────────────────────────────────────────────
    {
        printf("  Test 1: templates — PAD returns kCompingPad\n");
        const float* tpl = comping_rhythm_template(CompingStyleId::PAD);
        bool ok = (tpl != nullptr && tpl[0] == 0.7f && tpl[1] == 0.0f);
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }
    {
        printf("  Test 2: templates — FUNK_STABS hits on step 2, 5, 7, 10, 12, 15\n");
        const float* tpl = comping_rhythm_template(CompingStyleId::FUNK_STABS);
        bool ok = (tpl != nullptr
                   && tpl[2] > 0.0f && tpl[5] > 0.0f && tpl[7] > 0.0f
                   && tpl[10] > 0.0f && tpl[12] > 0.0f && tpl[15] > 0.0f
                   && tpl[0] == 0.0f && tpl[1] == 0.0f);
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }
    {
        printf("  Test 3: templates — ROCK_DOWNBEATS hits on steps 0 and 8\n");
        const float* tpl = comping_rhythm_template(CompingStyleId::ROCK_DOWNBEATS);
        bool ok = (tpl != nullptr
                   && tpl[0] > 0.8f && tpl[8] > 0.5f
                   && tpl[1] == 0.0f && tpl[2] == 0.0f && tpl[9] == 0.0f);
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }
    {
        printf("  Test 4: templates — CUSTOM returns nullptr\n");
        const float* tpl = comping_rhythm_template(CompingStyleId::CUSTOM);
        bool ok = (tpl == nullptr);
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }

    // ── generate_chordal_pattern — basic ─────────────────────────────
    {
        printf("  Test 5: generate — PAD produces one gated step at 0, extends hold to all steps\n");
        PulsarStep steps[16] = {};
        generate_chordal_pattern(steps, 16, CompingStyleId::PAD,
                                 0, 0, minor, 36, 72);
        bool ok = steps[0].gate && steps[0].velocity > 0.5f && steps[0].hold;
        for (int i = 1; i < 16; i++) {
            if (!steps[i].gate || !steps[i].hold) { ok = false; break; }
        }
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }
    {
        printf("  Test 6: generate — ROCK_DOWNBEATS gates step 0 and 8\n");
        PulsarStep steps[16] = {};
        generate_chordal_pattern(steps, 16, CompingStyleId::ROCK_DOWNBEATS,
                                 0, 0, minor, 36, 72);
        bool ok = steps[0].gate && steps[8].gate
                  && !steps[1].gate && !steps[4].gate && !steps[15].gate;
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }
    {
        printf("  Test 7: generate — note follows chord_degree (degree 4 transposes up 7 semitones in minor)\n");
        PulsarStep s0[16] = {};
        PulsarStep s4[16] = {};
        generate_chordal_pattern(s0, 16, CompingStyleId::ROCK_DOWNBEATS, 0, 0, minor, 36, 72);
        generate_chordal_pattern(s4, 16, CompingStyleId::ROCK_DOWNBEATS, 4, 0, minor, 36, 72);
        // In minor scale, degrees[4] = 7 (the 5th → 7 semitones up)
        int diff = static_cast<int>(s4[0].note) - static_cast<int>(s0[0].note);
        bool ok = (diff == 7);
        if (ok) { printf("    PASS: +%d semitones\n", diff); pass++; }
        else { printf("    FAIL: expected +7, got %+d\n", diff); fail++; }
    }
    {
        printf("  Test 8: generate — 32 steps tiles the template\n");
        PulsarStep steps[32] = {};
        generate_chordal_pattern(steps, 32, CompingStyleId::ROCK_DOWNBEATS,
                                 0, 0, minor, 36, 72);
        // Rock downbeats: hits on 0, 8, 16, 24
        bool ok = steps[0].gate && steps[8].gate && steps[16].gate && steps[24].gate
                  && !steps[4].gate && !steps[12].gate && !steps[20].gate;
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }
    {
        printf("  Test 9: generate — note clamped to note_range\n");
        PulsarStep steps[16] = {};
        // octave=4 → base 48. With root=0 and degree=0 → note 48.
        // Force tight range 60-72, should push up an octave to 60.
        generate_chordal_pattern(steps, 16, CompingStyleId::ROCK_DOWNBEATS,
                                 0, 0, minor, 60, 72);
        bool ok = steps[0].note >= 60 && steps[0].note <= 72;
        if (ok) { printf("    PASS: note=%d\n", steps[0].note); pass++; }
        else { printf("    FAIL: note=%d out of [60,72]\n", steps[0].note); fail++; }
    }
    {
        printf("  Test 10: generate — articulation writes step.duration\n");
        PulsarStep pad[16] = {};
        PulsarStep funk[16] = {};
        generate_chordal_pattern(pad, 16, CompingStyleId::PAD, 0, 0, minor, 36, 72);
        generate_chordal_pattern(funk, 16, CompingStyleId::FUNK_STABS, 0, 0, minor, 36, 72);
        bool ok = pad[0].duration > funk[2].duration;
        if (ok) { printf("    PASS: pad=%.2f funk=%.2f\n", pad[0].duration, funk[2].duration); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 11: generate — PAD extends hold across all steps\n");
        PulsarStep steps[16] = {};
        generate_chordal_pattern(steps, 16, CompingStyleId::PAD,
                                 0, 0, minor, 36, 72);
        bool ok = steps[0].gate && steps[0].hold;
        for (int i = 1; i < 16; i++) {
            if (!steps[i].gate || !steps[i].hold) { ok = false; break; }
        }
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 12: generate — ROCK does NOT extend holds\n");
        PulsarStep steps[16] = {};
        generate_chordal_pattern(steps, 16, CompingStyleId::ROCK_DOWNBEATS,
                                 0, 0, minor, 36, 72);
        bool ok = steps[0].gate && !steps[0].hold
                  && !steps[1].gate && !steps[4].gate && !steps[7].gate
                  && steps[8].gate;
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 13: humanization — drop=0 produces no changes\n");
        PulsarStep steps[16] = {};
        generate_chordal_pattern(steps, 16, CompingStyleId::ROCK_DOWNBEATS, 0, 0, minor, 36, 72);
        PulsarStep original[16];
        std::memcpy(original, steps, sizeof(steps));
        apply_humanization(steps, 16, 0.0f, 0.0f, 0.0f, 0.0f, 36, 72, 1.0f, 42u);
        bool ok = std::memcmp(steps, original, sizeof(steps)) == 0;
        if (ok) { printf("    PASS\n"); pass++; } else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 14: humanization — anchor protected from drops\n");
        PulsarStep steps[16] = {};
        generate_chordal_pattern(steps, 16, CompingStyleId::ROCK_DOWNBEATS, 0, 0, minor, 36, 72);
        // Run with max drop probability many times — anchor must survive
        bool anchor_survived = true;
        for (int seed = 1; seed <= 50; seed++) {
            PulsarStep copy[16];
            std::memcpy(copy, steps, sizeof(steps));
            apply_humanization(copy, 16, 1.0f, 0.0f, 0.0f, 0.0f, 36, 72, 1.0f, static_cast<uint32_t>(seed));
            if (!copy[0].gate) { anchor_survived = false; break; }
        }
        if (anchor_survived) { printf("    PASS\n"); pass++; } else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 15: humanization — deterministic from same seed\n");
        PulsarStep a[16] = {};
        PulsarStep b[16] = {};
        generate_chordal_pattern(a, 16, CompingStyleId::ROCK_DOWNBEATS, 0, 0, minor, 36, 72);
        std::memcpy(b, a, sizeof(a));
        apply_humanization(a, 16, 0.3f, 0.3f, 0.3f, 0.3f, 36, 72, 1.0f, 12345u);
        apply_humanization(b, 16, 0.3f, 0.3f, 0.3f, 0.3f, 36, 72, 1.0f, 12345u);
        bool ok = std::memcmp(a, b, sizeof(a)) == 0;
        if (ok) { printf("    PASS\n"); pass++; } else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 16: fill — apply_fill_ascending_arp gates every 2 steps\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_ascending_arp(steps, 16, 0, 0, minor, 36, 72, 4);
        int gated = 0;
        for (int i = 0; i < 16; i++) if (steps[i].gate) gated++;
        bool ok = (gated == 8 && steps[0].gate && steps[2].gate && !steps[1].gate);
        if (ok) { printf("    PASS: %d gated steps\n", gated); pass++; }
        else { printf("    FAIL: %d gated steps\n", gated); fail++; }
    }

    {
        printf("  Test 17: fill — velocity rises toward bar end\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_ascending_arp(steps, 16, 0, 0, minor, 36, 72, 4);
        bool ok = steps[0].velocity < steps[14].velocity;
        if (ok) { printf("    PASS: %.2f -> %.2f\n", steps[0].velocity, steps[14].velocity); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 18: fill — apply_fill_descending_arp gates every 2 steps\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_descending_arp(steps, 16, 0, 0, minor, 36, 72, 4);
        int gated = 0;
        for (int i = 0; i < 16; i++) if (steps[i].gate) gated++;
        bool ok = (gated == 8 && steps[0].gate && !steps[1].gate);
        if (ok) { printf("    PASS: %d gated steps\n", gated); pass++; }
        else { printf("    FAIL: %d gated steps\n", gated); fail++; }
    }

    {
        printf("  Test 19: fill — descending velocity falls toward bar end\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_descending_arp(steps, 16, 0, 0, minor, 36, 72, 4);
        bool ok = steps[0].velocity > steps[14].velocity;
        if (ok) { printf("    PASS: %.2f -> %.2f\n", steps[0].velocity, steps[14].velocity); pass++; }
        else { printf("    FAIL\n"); fail++; }
    }

    {
        printf("  Test 20: fill — turnaround has 4 hits with landing accent on beat 4\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_turnaround(steps, 16, 0, 0, minor, 36, 72, 4);
        int gated = 0;
        for (int i = 0; i < 16; i++) if (steps[i].gate) gated++;
        bool landing_loudest = (steps[12].velocity > steps[0].velocity)
                            && (steps[12].velocity > steps[4].velocity)
                            && (steps[12].velocity > steps[8].velocity);
        bool ok = (gated == 4 && steps[0].gate && steps[4].gate
                   && steps[8].gate && steps[12].gate && landing_loudest);
        if (ok) { printf("    PASS: %d hits, landing v=%.2f\n", gated, steps[12].velocity); pass++; }
        else { printf("    FAIL: %d hits, landing v=%.2f\n", gated, steps[12].velocity); fail++; }
    }

    {
        printf("  Test 21: fill — double_time gates every step with downbeat accents\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_double_time(steps, 16, 0, 0, minor, 36, 72, 4);
        int gated = 0;
        for (int i = 0; i < 16; i++) if (steps[i].gate) gated++;
        bool downbeats_louder = (steps[0].velocity > steps[1].velocity)
                             && (steps[4].velocity > steps[5].velocity);
        bool ok = (gated == 16 && downbeats_louder);
        if (ok) { printf("    PASS: %d gates, beat1 v=%.2f vs beat1+1 v=%.2f\n",
                         gated, steps[0].velocity, steps[1].velocity); pass++; }
        else { printf("    FAIL: %d gates\n", gated); fail++; }
    }

    {
        printf("  Test 22: fill — stab_flurry empty in first half, stabs in second half\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_stab_flurry(steps, 16, 0, 0, minor, 36, 72, 4);
        int first_half = 0, second_half = 0;
        for (int i = 0; i < 8; i++) if (steps[i].gate) first_half++;
        for (int i = 8; i < 16; i++) if (steps[i].gate) second_half++;
        bool rising_velocity = steps[8].velocity < steps[15].velocity;
        bool ok = (first_half == 0 && second_half == 8 && rising_velocity);
        if (ok) { printf("    PASS: 0 in first half, %d in second, v rising %.2f->%.2f\n",
                         second_half, steps[8].velocity, steps[15].velocity); pass++; }
        else { printf("    FAIL: %d/%d\n", first_half, second_half); fail++; }
    }

    {
        printf("  Test 23: fill — drop_out keeps only the downbeat\n");
        PulsarStep steps[16] = {};
        const PulsarScale& minor = kPulsarScales[0];
        apply_fill_drop_out(steps, 16, 0, 0, minor, 36, 72, 4);
        int gated = 0;
        for (int i = 0; i < 16; i++) if (steps[i].gate) gated++;
        bool ok = (gated == 1 && steps[0].gate && steps[0].velocity > 0.8f);
        if (ok) { printf("    PASS: %d gated, step 0 v=%.2f\n", gated, steps[0].velocity); pass++; }
        else { printf("    FAIL: %d gated\n", gated); fail++; }
    }

    {
        printf("  Test 24: fill — notes stay within [low, high] range\n");
        const PulsarScale& minor = kPulsarScales[0];
        PulsarStep a[16] = {}, b[16] = {}, c[16] = {};
        apply_fill_descending_arp(a, 16, 0, 48, minor, 48, 60, 4);
        apply_fill_turnaround(b, 16, 0, 48, minor, 48, 60, 4);
        apply_fill_double_time(c, 16, 0, 48, minor, 48, 60, 4);
        bool ok = true;
        for (int i = 0; i < 16; i++) {
            if (a[i].gate && (a[i].note < 48 || a[i].note > 60)) { ok = false; break; }
            if (b[i].gate && (b[i].note < 48 || b[i].note > 60)) { ok = false; break; }
            if (c[i].gate && (c[i].note < 48 || c[i].note > 60)) { ok = false; break; }
        }
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL — note out of range\n"); fail++; }
    }

    // ── Arp direction resolution ────────────────────────────────────
    {
        printf("  Test 25: arp direction — UP/DOWN/RANDOM pass through unchanged\n");
        bool ok = (resolve_arp_direction(ArpDirectionId::UP, false) == ArpDirectionId::UP)
               && (resolve_arp_direction(ArpDirectionId::UP, true) == ArpDirectionId::UP)
               && (resolve_arp_direction(ArpDirectionId::DOWN, false) == ArpDirectionId::DOWN)
               && (resolve_arp_direction(ArpDirectionId::DOWN, true) == ArpDirectionId::DOWN)
               && (resolve_arp_direction(ArpDirectionId::RANDOM, true) == ArpDirectionId::RANDOM);
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL — phase must only affect UP_DOWN\n"); fail++; }
    }
    {
        printf("  Test 26: arp direction — UP_DOWN alternates with the phase flag\n");
        bool ok = (resolve_arp_direction(ArpDirectionId::UP_DOWN, false) == ArpDirectionId::UP)
               && (resolve_arp_direction(ArpDirectionId::UP_DOWN, true) == ArpDirectionId::DOWN);
        if (ok) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL — UP_DOWN must mirror on alternate stabs\n"); fail++; }
    }
    {
        // The regression this guards: UP_DOWN used to be a synonym for UP, so a
        // two-note root+fifth arp played the identical figure on every stab.
        printf("  Test 27: arp direction — UP_DOWN reverses note order between stabs\n");
        const PulsarScale& minor = kPulsarScales[0];
        uint8_t up[4] = {}, down[4] = {};
        int n_up = compute_chord_tones(
            60, 0, minor, 2, resolve_arp_direction(ArpDirectionId::UP_DOWN, false),
            SectionInversionId::FOLLOW_STYLE, 1u, up);
        int n_down = compute_chord_tones(
            60, 0, minor, 2, resolve_arp_direction(ArpDirectionId::UP_DOWN, true),
            SectionInversionId::FOLLOW_STYLE, 1u, down);
        bool ok = (n_up == 2 && n_down == 2
                   && up[0] == down[1] && up[1] == down[0]
                   && up[0] != up[1]);
        if (ok) {
            printf("    PASS: up=[%d,%d] down=[%d,%d]\n", up[0], up[1], down[0], down[1]);
            pass++;
        } else {
            printf("    FAIL: up=[%d,%d] down=[%d,%d]\n", up[0], up[1], down[0], down[1]);
            fail++;
        }
    }

    // ── Humanization respects the track's note range ────────────────
    // apply_humanization rewrites pitch (octave jump +/-12, extension +2/+5) and used to
    // clamp only to MIDI 0..127, so a jump could drop a pad an octave under its authored
    // register. The fill helpers already clamp; these pin the same guarantee here.
    {
        printf("  Test 28: humanization — octave jump stays inside [lo, hi]\n");
        const uint8_t LO = 48, HI = 72;
        bool in_range = true, moved = false;
        for (uint32_t s = 1; s <= 64 && in_range; s++) {
            PulsarStep steps[16] = {};
            for (int i = 0; i < 6; i++) {
                steps[i].gate = true; steps[i].note = 71; steps[i].raw_note = 71;
                steps[i].velocity = 0.8f; steps[i].duration = 0.5f;
            }
            apply_humanization(steps, 16, 0.0f, 0.0f, 1.0f, 0.0f, LO, HI, 1.0f, s);
            for (int i = 0; i < 16; i++) {
                if (!steps[i].gate) continue;
                if (steps[i].note < LO || steps[i].note > HI) { in_range = false; break; }
                if (steps[i].note != 71) moved = true;
            }
        }
        // `moved` guards the lazy fix: clamping by simply never jumping would also pass.
        bool ok = in_range && moved;
        if (ok) { printf("    PASS: stayed in [%d,%d] and still jumped\n", LO, HI); pass++; }
        else { printf("    FAIL: in_range=%d moved=%d\n", (int)in_range, (int)moved); fail++; }
    }
    {
        printf("  Test 29: humanization — an extension that would overshoot is skipped\n");
        // note 71 in [48,72]: +2 = 73 and +5 = 76 both overshoot, so the note must not move.
        // Skipping is deliberate — folding down an octave would displace the register.
        const uint8_t LO = 48, HI = 72;
        bool held = true;
        for (uint32_t s = 1; s <= 32 && held; s++) {
            PulsarStep steps[16] = {};
            for (int i = 0; i < 6; i++) {
                steps[i].gate = true; steps[i].note = 71; steps[i].raw_note = 71;
                steps[i].velocity = 0.8f; steps[i].duration = 0.5f;
            }
            apply_humanization(steps, 16, 0.0f, 0.0f, 0.0f, 1.0f, LO, HI, 1.0f, s);
            for (int i = 0; i < 16; i++) {
                if (steps[i].gate && steps[i].note != 71) { held = false; break; }
            }
        }
        if (held) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL — extension pushed a note past hi\n"); fail++; }
    }
    {
        printf("  Test 30: humanization — combined sweep never leaves the range\n");
        const uint8_t ranges[3][2] = {{48,72},{36,60},{60,67}};
        bool ok = true;
        for (int r = 0; r < 3 && ok; r++) {
            uint8_t LO = ranges[r][0], HI = ranges[r][1];
            for (uint32_t s = 1; s <= 32 && ok; s++) {
                PulsarStep steps[16] = {};
                for (int i = 0; i < 8; i++) {
                    steps[i].gate = true;
                    steps[i].note = static_cast<uint8_t>(LO + (i * 3) % (HI - LO + 1));
                    steps[i].raw_note = steps[i].note;
                    steps[i].velocity = 0.8f; steps[i].duration = 0.5f;
                }
                apply_humanization(steps, 16, 0.1f, 0.3f, 0.8f, 0.8f, LO, HI, 1.0f, s);
                for (int i = 0; i < 16; i++) {
                    if (steps[i].gate && (steps[i].note < LO || steps[i].note > HI)) {
                        printf("    note %d outside [%d,%d] (seed %u)\n", steps[i].note, LO, HI, s);
                        ok = false; break;
                    }
                }
            }
        }
        if (ok) { printf("    PASS\n"); pass++; }
        else { fail++; }
    }

    printf("\n  Pulsar Comping: %d passed, %d failed\n", pass, fail);
    TEST_SUITE_RETURN(pass, fail);
}
