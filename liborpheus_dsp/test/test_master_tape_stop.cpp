#include "test_harness.h"
#include "orpheus_master_tape_stop.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

// Lightweight assert helper that tracks per-test pass/fail without aborting.
// Bare assert() compiles to a no-op in Release, silently passing broken tests —
// the Checker pattern (mirrored from test_master_fader.cpp) avoids that trap.
struct Checker {
    bool ok = true;
    void check(bool cond, const char* expr) {
        if (!cond) {
            ok = false;
            printf("    CHECK FAILED: %s\n", expr);
        }
    }
};

#define CHK(c, expr) (c).check(expr, #expr)

bool test_disarmed_passthrough() {
    Checker c;
    orpheus::MasterTapeStop ts;
    std::vector<float> in(256);
    for (size_t i = 0; i < in.size(); ++i) in[i] = std::sin(2.0f * 3.14159265f * i / 48.0f);
    std::vector<float> work = in;
    ts.process(work.data(), work.size());
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_tape_stop.disarmed_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_armed_freezes_output() {
    // MasterTapeStop only does the pitch/rate ramp — read head decays toward 0.
    // The output therefore freezes on whatever sample the slowed read head lands
    // on (a constant value), NOT silence. Silence in SCRATCH comes from the
    // separate MasterFader being clamped to 0 after this unit's ramp completes.
    Checker c;
    orpheus::MasterTapeStop ts;
    ts.arm(14400); // 300ms @ 48k
    std::vector<float> buf(14400);
    for (size_t i = 0; i < buf.size(); ++i) {
        buf[i] = std::sin(2.0f * 3.14159265f * 1000.0f * i / 48000.0f);
    }
    ts.process(buf.data(), buf.size());

    // Verify the final samples are frozen on a single value (within tolerance).
    float frozen = buf[buf.size() - 100];
    float max_delta = 0.0f;
    for (size_t i = buf.size() - 99; i < buf.size(); ++i) {
        max_delta = std::max(max_delta, std::fabs(buf[i] - frozen));
    }
    CHK(c, max_delta < 0.01f);
    printf("  master_tape_stop.armed_freezes_output (frozen=%.3f, max_delta=%.4f) %s\n",
           frozen, max_delta, c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_no_overrun_amplitude() {
    Checker c;
    orpheus::MasterTapeStop ts;
    ts.arm(14400);
    std::vector<float> buf(28800);
    for (size_t i = 0; i < buf.size(); ++i) {
        buf[i] = ((float)(i * 1103515245u + 12345u) / (float)0xFFFFFFFFu) * 2.0f - 1.0f;
    }
    std::vector<float> work = buf;
    ts.process(work.data(), work.size());
    float peak_in = 0.0f, peak_out = 0.0f;
    for (size_t i = 0; i < buf.size(); ++i) {
        peak_in = std::max(peak_in, std::fabs(buf[i]));
        peak_out = std::max(peak_out, std::fabs(work[i]));
    }
    CHK(c, peak_out <= peak_in + 1e-3f);
    printf("  master_tape_stop.no_overrun_amplitude (peak_in=%.3f, peak_out=%.3f) %s\n",
           peak_in, peak_out, c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_after_arm_completes_returns_to_passthrough() {
    Checker c;
    orpheus::MasterTapeStop ts;
    ts.arm(64);
    std::vector<float> stop_buf(64, 1.0f);
    ts.process(stop_buf.data(), stop_buf.size());
    std::vector<float> in(32, 0.7f);
    std::vector<float> work = in;
    ts.process(work.data(), work.size());
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_tape_stop.returns_to_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

} // namespace

bool run_master_tape_stop_tests() {
    printf("master_tape_stop:\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_disarmed_passthrough());
    tally(test_armed_freezes_output());
    tally(test_no_overrun_amplitude());
    tally(test_after_arm_completes_returns_to_passthrough());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
