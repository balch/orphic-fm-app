#include "orpheus_master_swell.h"
#include <cstdio>
#include <cmath>
using namespace orpheus;

static bool test_disarmed_passthrough() {
    printf("\n=== swell: disarmed passthrough ===\n");
    MasterSwell x;
    float b[64];
    for (int i = 0; i < 64; i++) b[i] = 0.5f;
    x.process(b, 64);
    bool ok = true;
    for (int i = 0; i < 64; i++) if (b[i] != 0.5f) { ok = false; break; }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_rises_to_peak_then_settles() {
    printf("\n=== swell: rises start->peak->1.0 ===\n");
    MasterSwell x;
    x.arm(400, 48000.0f, 0.3f, 1.4f);
    float b[400];
    for (int i = 0; i < 400; i++) b[i] = 1.0f;
    x.process(b, 400);
    // start near start_level, mid peaks at peak_level, end settles near 1.0
    bool ok = std::fabs(b[0] - 0.3f) < 0.05f &&
              std::fabs(b[200] - 1.4f) < 0.05f &&
              std::fabs(b[399] - 1.0f) < 0.05f;
    printf("  start=%.3f mid=%.3f end=%.3f %s\n", b[0], b[200], b[399], ok ? "PASS" : "FAIL");
    return ok;
}

bool run_master_swell_tests() {
    bool ok = true;
    ok &= test_disarmed_passthrough();
    ok &= test_rises_to_peak_then_settles();
    return ok;
}
