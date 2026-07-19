// Per-track lick-wah insert (Task 13) — guards the two load-bearing invariants the
// feature relies on:
//   (a) the per-track opt-in bitmask decodes to exactly the flagged tracks, and
//   (b) a WahVoice whose WahParams.wet == 0 leaves a track buffer BYTE-identical,
//       which is what makes undeclared / unflagged tracks completely untouched.
#include "orpheus_wah_core.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
using namespace orpheus;

// Mirror of the C++ apply-guard idiom (state->lick_wah_mask & (1 << t)) and the
// Kotlin marshal (mask |= 1 << t for each opted-in track).
static bool test_bitmask_decode_round_trips() {
    printf("\n=== lick_wah: track opt-in bitmask decode round-trips ===\n");
    // Tracks 2 and 4 opted in => 0b00010100 = 20.
    const uint8_t mask = 0b00010100;
    bool expected[8] = { false, false, true, false, true, false, false, false };
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        bool set = (mask & (1 << t)) != 0;
        if (set != expected[t]) { ok = false; printf("  track %d: got %d want %d\n", t, set, expected[t]); }
    }
    // Round-trip: rebuild the mask from the expected flags exactly as the Kotlin marshal does.
    uint8_t rebuilt = 0;
    for (int t = 0; t < 8; t++) if (expected[t]) rebuilt = (uint8_t)(rebuilt | (1 << t));
    if (rebuilt != mask) { ok = false; printf("  rebuilt mask %u != %u\n", rebuilt, mask); }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// wet==0 must be a byte-identical pass-through — the guarantee that unflagged tracks
// (and the disabled default) are inert. Uses the same block call the apply-hook uses.
static bool test_wet_zero_is_byte_identical() {
    printf("\n=== lick_wah: WahParams.wet==0 leaves the track buffer byte-identical ===\n");
    const int n = 512;
    float buf[n], orig[n];
    for (int i = 0; i < n; i++) buf[i] = orig[i] = std::sin(i * 0.21f) * 0.7f;

    WahVoice v; v.Init();
    WahParams p;            // non-trivial sweep params...
    p.rate_division = 8.0f;
    p.depth = 1.0f;
    p.resonance_q = 4.0f;
    p.center_hz = 900.0f;
    p.sweep_octaves = 1.5f;
    p.wet = 0.0f;           // ...but fully dry: process() must be a no-op.
    v.process(buf, n, p, /*samples_per_step=*/240.0, /*sample_rate=*/48000.0f);

    bool ok = std::memcmp(buf, orig, sizeof(buf)) == 0;
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_lick_wah_tests() {
    bool ok = true;
    ok &= test_bitmask_decode_round_trips();
    ok &= test_wet_zero_is_byte_identical();
    return ok;
}
