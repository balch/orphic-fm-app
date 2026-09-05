#include "test_harness.h"
#include "pulsar_score_clock.h"
#include <cstdio>
#include <cmath>

static bool test_ticks_per_sample_matches_formula() {
    printf("\n=== Test: ticks/sample = bpm * 96 / (60 * sr) ===\n");
    // 120 BPM at 48kHz: 2 beats/sec * 96 ticks = 192 ticks/sec / 48000 = 0.004
    double tps = score_ticks_per_sample(120.0f, 48000.0f);
    bool ok = std::fabs(tps - 0.004) < 1e-9;
    printf("  tps=%.9f -- %s\n", tps, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_one_second_at_120_is_192_ticks() {
    printf("\n=== Test: one second at 120 BPM advances exactly 192 ticks ===\n");
    ScoreClock c{};
    score_clock_reset(c);
    // 48000 frames in blocks of 512, plus the 375th partial block.
    for (int i = 0; i < 93; i++) score_clock_advance(c, 512, 120.0f, 48000.0f);
    score_clock_advance(c, 48000 - 93 * 512, 120.0f, 48000.0f);
    bool ok = std::fabs(c.tick_pos - 192.0) < 1e-6;
    printf("  tick_pos=%.6f -- %s\n", c.tick_pos, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tempo_change_rescales_rate_not_position() {
    printf("\n=== Test: a tempo change alters the rate, never the accrued position ===\n");
    ScoreClock c{};
    score_clock_reset(c);
    score_clock_advance(c, 48000, 120.0f, 48000.0f);   // 192 ticks
    double after_first = c.tick_pos;
    score_clock_advance(c, 48000, 60.0f, 48000.0f);    // half rate: +96
    bool ok = std::fabs(after_first - 192.0) < 1e-6 && std::fabs(c.tick_pos - 288.0) < 1e-6;
    printf("  first=%.4f total=%.4f -- %s\n", after_first, c.tick_pos, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_no_drift_over_ten_minutes_at_extremes() {
    printf("\n=== Test: no accumulated drift at 40 and 200 BPM over 10 minutes ===\n");
    bool ok = true;
    const float bpms[2] = {40.0f, 200.0f};
    for (int b = 0; b < 2; b++) {
        ScoreClock c{};
        score_clock_reset(c);
        const int blocks = 10 * 60 * 48000 / 512;
        for (int i = 0; i < blocks; i++) score_clock_advance(c, 512, bpms[b], 48000.0f);
        double expected = static_cast<double>(blocks) * 512.0
                        * score_ticks_per_sample(bpms[b], 48000.0f);
        double err = std::fabs(c.tick_pos - expected);
        printf("  bpm=%.0f pos=%.3f expected=%.3f err=%.6f\n", bpms[b], c.tick_pos, expected, err);
        if (err > 1e-3) ok = false;
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_reset_returns_to_zero() {
    printf("\n=== Test: reset returns the clock to the start of the piece ===\n");
    ScoreClock c{};
    score_clock_advance(c, 48000, 120.0f, 48000.0f);
    score_clock_reset(c);
    bool ok = c.tick_pos == 0.0;
    printf("  tick_pos=%.6f -- %s\n", c.tick_pos, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_zero_and_negative_bpm_do_not_advance() {
    printf("\n=== Test: a non-positive bpm freezes rather than running backwards ===\n");
    ScoreClock c{};
    score_clock_reset(c);
    score_clock_advance(c, 48000, 0.0f, 48000.0f);
    score_clock_advance(c, 48000, -120.0f, 48000.0f);
    bool ok = c.tick_pos == 0.0;
    printf("  tick_pos=%.6f -- %s\n", c.tick_pos, ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_score_clock_tests() {
    printf("\n========== Pulsar Score Clock ==========\n");
    int passed = 0, failed = 0;
    auto run = [&](bool (*fn)()) { if (fn()) passed++; else failed++; };
    run(test_ticks_per_sample_matches_formula);
    run(test_one_second_at_120_is_192_ticks);
    run(test_tempo_change_rescales_rate_not_position);
    run(test_no_drift_over_ten_minutes_at_extremes);
    run(test_reset_returns_to_zero);
    run(test_zero_and_negative_bpm_do_not_advance);
    printf("\n  Pulsar Score Clock: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
