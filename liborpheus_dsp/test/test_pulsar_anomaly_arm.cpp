#include "pulsar_anomaly_arm.h"
#include <cstdio>

// Unit tests for the shared Anomaly-Engine arming helpers. These two functions
// are the canonical duration math reused by every Master* anomaly dispatch
// (wah first; crossfade/cut/swell/… mirror it), so pin their contract here.

static bool test_arm_samples_math() {
    printf("\n=== pulsar_anomaly_arm: arm-samples math ===\n");
    // 2 bars * 16 steps/bar * 240 samples/step = 7680 samples.
    int s = anomaly_arm_samples(2.0f, 240.0);
    bool ok = (s == 7680);
    printf("  anomaly_arm_samples(2, 240)=%d expect 7680 %s\n", s, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_draw_bars_degenerate_is_deterministic() {
    printf("\n=== pulsar_anomaly_arm: degenerate range is deterministic ===\n");
    uint32_t rng = 12345u;
    uint32_t before = rng;
    float bars = anomaly_draw_bars(3.0f, 3.0f, rng);
    // lo == hi returns lo exactly and must NOT consume the RNG.
    bool ok = (bars == 3.0f) && (rng == before);
    printf("  draw(3,3)=%.3f rng_untouched=%d %s\n", bars, rng == before, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_draw_bars_in_range_and_repeatable() {
    printf("\n=== pulsar_anomaly_arm: real range stays in bounds and repeats per seed ===\n");
    bool ok = true;
    uint32_t a = 0xABCDEF01u;
    uint32_t b = 0xABCDEF01u;   // identical seed -> identical draw
    float da = anomaly_draw_bars(2.0f, 4.0f, a);
    float db = anomaly_draw_bars(2.0f, 4.0f, b);
    ok &= (da >= 2.0f && da <= 4.0f);
    ok &= (da == db);
    printf("  draw(2,4)=%.4f in[2,4] repeatable=%d %s\n", da, da == db, ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_anomaly_arm_tests() {
    bool ok = true;
    ok &= test_arm_samples_math();
    ok &= test_draw_bars_degenerate_is_deterministic();
    ok &= test_draw_bars_in_range_and_repeatable();
    return ok;
}
