#include "orpheus_master_crossfade.h"
#include <cstdio>
#include <cmath>
using namespace orpheus;

static bool test_disarmed_passthrough() {
    printf("\n=== crossfade: disarmed passthrough ===\n");
    MasterCrossfade x;
    float b[64];
    for (int i = 0; i < 64; i++) b[i] = 0.5f;
    x.process(b, 64);
    bool ok = true;
    for (int i = 0; i < 64; i++) if (b[i] != 0.5f) { ok = false; break; }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_dips_and_returns() {
    printf("\n=== crossfade: dips to depth mid, returns ===\n");
    MasterCrossfade x;
    x.arm(400, 48000.0f, 0.0f);
    float b[400];
    for (int i = 0; i < 400; i++) b[i] = 1.0f;
    x.process(b, 400);
    // start near 1, mid dips to depth (0), end returns near 1
    bool ok = b[0] > 0.9f && b[399] > 0.9f && b[200] < 0.1f;
    printf("  start=%.3f mid=%.3f end=%.3f %s\n", b[0], b[200], b[399], ok ? "PASS" : "FAIL");
    return ok;
}

bool run_master_crossfade_tests() {
    bool ok = true;
    ok &= test_disarmed_passthrough();
    ok &= test_dips_and_returns();
    return ok;
}
