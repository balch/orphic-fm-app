// Lock-free stereo record ring (orpheus_record_ring.h).
//
//   liborpheus_dsp/build-desktop/orpheus_dsp_test record
#include "test_harness.h"
#include "../src/orpheus_record_ring.h"

namespace {

using orpheus::RecordRing;

// Frame i of a ramp: {i, -i}, so order and channel interleave are both checked.
void fill_ramp(float* out, int first, int frames) {
    for (int i = 0; i < frames; ++i) {
        out[2 * i] = static_cast<float>(first + i);
        out[2 * i + 1] = -static_cast<float>(first + i);
    }
}

bool test_ring_reads_back_in_order() {
    printf("\n=== Test: ring reads back in order ===\n");
    RecordRing ring(8);
    ring.arm();
    const float in[6] = {1, -1, 2, -2, 3, -3};
    ring.write(in, 3);
    float out[16] = {};
    const int n = ring.read(out, 8);
    bool pass = n == 3;
    for (int i = 0; i < 6; ++i) pass = pass && out[i] == in[i];
    printf("  read %d frames: %s\n", n, pass ? "PASS" : "FAIL");
    return pass;
}

bool test_ring_disarmed_write_is_noop() {
    printf("\n=== Test: disarmed write is a no-op ===\n");
    RecordRing ring(8);
    float in[8];
    fill_ramp(in, 0, 4);
    ring.write(in, 4);
    float out[16] = {};
    const int n = ring.read(out, 8);
    const bool pass = n == 0 && ring.dropped_frames() == 0 && !ring.armed();
    printf("  read %d, dropped %llu: %s\n", n, (unsigned long long)ring.dropped_frames(),
           pass ? "PASS" : "FAIL");
    return pass;
}

bool test_ring_wraps_around() {
    printf("\n=== Test: ring wraps around ===\n");
    RecordRing ring(8);
    ring.arm();
    bool pass = true;
    for (int round = 0; round < 5; ++round) {
        float in[10], out[10] = {};
        fill_ramp(in, round * 5, 5);
        ring.write(in, 5);
        const int n = ring.read(out, 5);
        pass = pass && n == 5;
        for (int i = 0; i < 10; ++i) pass = pass && out[i] == in[i];
    }
    pass = pass && ring.dropped_frames() == 0;
    printf("  %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool test_ring_overflow_counts_drops() {
    printf("\n=== Test: overflow counts drops, keeps oldest ===\n");
    RecordRing ring(8);
    ring.arm();
    float in[24], out[24] = {};
    fill_ramp(in, 0, 12);
    ring.write(in, 12);
    const uint64_t dropped = ring.dropped_frames();
    const int n = ring.read(out, 16);
    bool pass = dropped == 4 && n == 8;
    for (int i = 0; i < 16; ++i) pass = pass && out[i] == in[i];
    printf("  dropped %llu, read %d: %s\n", (unsigned long long)dropped, n, pass ? "PASS" : "FAIL");
    return pass;
}

bool test_ring_discard_skips_unread() {
    printf("\n=== Test: discard skips unread frames ===\n");
    RecordRing ring(8);
    ring.arm();
    float in[10], out[10] = {};
    fill_ramp(in, 0, 5);
    ring.write(in, 5);
    ring.discard();
    bool pass = ring.read(out, 8) == 0;
    fill_ramp(in, 100, 2);
    const int n = ring.read(out, 8);  // nothing written yet
    pass = pass && n == 0;
    ring.write(in, 2);
    const int m = ring.read(out, 8);
    pass = pass && m == 2;
    for (int i = 0; i < 4; ++i) pass = pass && out[i] == in[i];
    printf("  %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool test_ring_capacity_rounds_up() {
    printf("\n=== Test: capacity rounds up to a power of two ===\n");
    const bool pass = RecordRing(1000).capacity_frames() == 1024 &&
                      RecordRing(8).capacity_frames() == 8;
    printf("  %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

bool run_record_ring_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_ring_reads_back_in_order());
    tally(test_ring_disarmed_write_is_noop());
    tally(test_ring_wraps_around());
    tally(test_ring_overflow_counts_drops());
    tally(test_ring_discard_skips_unread());
    tally(test_ring_capacity_rounds_up());
    TEST_SUITE_RETURN(p, f);
}
