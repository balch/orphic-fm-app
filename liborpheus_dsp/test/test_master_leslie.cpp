#include "test_harness.h"
#include "orpheus_master_leslie.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

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
    orpheus::MasterLeslie les;
    std::vector<float> in(256);
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = std::sin(2.0f * 3.14159265f * i / 48.0f);
    std::vector<float> work = in;
    les.process(work.data(), work.size());
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_leslie.disarmed_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_armed_modulates_output() {
    Checker c;
    orpheus::MasterLeslie les;
    const int total = 24000;  // 500ms @ 48k
    les.arm(total, 48000.0f);
    std::vector<float> in(total, 0.8f);  // constant input
    std::vector<float> work = in;
    les.process(work.data(), work.size());

    // With constant input and AM, output should vary from the input.
    float min_out = 1.0f, max_out = 0.0f;
    for (size_t i = 0; i < work.size(); ++i) {
        if (work[i] < min_out) min_out = work[i];
        if (work[i] > max_out) max_out = work[i];
    }
    // Must have visible modulation range
    float range = max_out - min_out;
    CHK(c, range > 0.1f);
    // Must never exceed input (AM only attenuates, gain <= 1)
    CHK(c, max_out <= 0.8f + 1e-5f);
    // Must not go negative (gain >= 0)
    CHK(c, min_out >= -1e-5f);

    printf("  master_leslie.armed_modulates_output (min=%.3f max=%.3f range=%.3f) %s\n",
           min_out, max_out, range, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// The edges of the effect (t≈0 and t≈1) should be near-unity gain since
// v≈0 at both ends. This ensures clean bypass at start and end.
bool test_edges_near_unity() {
    Checker c;
    orpheus::MasterLeslie les;
    const int total = 48000;  // 1 second
    les.arm(total, 48000.0f);
    std::vector<float> buf(total, 1.0f);
    les.process(buf.data(), buf.size());

    // First 2% of samples: v is small, gain should be near 1.0
    float max_deviation_start = 0.0f;
    int edge_n = total / 50;
    for (int i = 0; i < edge_n; ++i) {
        max_deviation_start = std::max(max_deviation_start, std::fabs(buf[i] - 1.0f));
    }
    // Last 2% of samples: same
    float max_deviation_end = 0.0f;
    for (int i = total - edge_n; i < total; ++i) {
        max_deviation_end = std::max(max_deviation_end, std::fabs(buf[i] - 1.0f));
    }

    CHK(c, max_deviation_start < 0.05f);
    CHK(c, max_deviation_end < 0.05f);

    printf("  master_leslie.edges_near_unity (start_dev=%.4f end_dev=%.4f) %s\n",
           max_deviation_start, max_deviation_end, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// Peak modulation should occur around the midpoint (t≈0.5) where v=1.
bool test_peak_at_midpoint() {
    Checker c;
    orpheus::MasterLeslie les;
    const int total = 48000;
    les.arm(total, 48000.0f);
    std::vector<float> buf(total, 1.0f);
    les.process(buf.data(), buf.size());

    // Measure modulation depth in three regions
    auto measure_depth = [&](int start, int end) -> float {
        float lo = 1.0f, hi = 0.0f;
        for (int i = start; i < end; ++i) {
            if (buf[i] < lo) lo = buf[i];
            if (buf[i] > hi) hi = buf[i];
        }
        return hi - lo;
    };

    float depth_q1 = measure_depth(0, total / 4);
    float depth_mid = measure_depth(total * 3 / 8, total * 5 / 8);
    float depth_q4 = measure_depth(total * 3 / 4, total);

    CHK(c, depth_mid > depth_q1);
    CHK(c, depth_mid > depth_q4);

    printf("  master_leslie.peak_at_midpoint (q1=%.3f mid=%.3f q4=%.3f) %s\n",
           depth_q1, depth_mid, depth_q4, c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_returns_to_passthrough() {
    Checker c;
    orpheus::MasterLeslie les;
    les.arm(64, 48000.0f);
    std::vector<float> scratch_buf(64, 0.5f);
    les.process(scratch_buf.data(), scratch_buf.size());
    CHK(c, !les.is_active());

    std::vector<float> in(32, 0.7f);
    std::vector<float> work = in;
    les.process(work.data(), work.size());
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_leslie.returns_to_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// Gain must never exceed 1.0 (AM only attenuates, never boosts).
bool test_gain_never_exceeds_unity() {
    Checker c;
    orpheus::MasterLeslie les;
    const int total = 48000;
    les.arm(total, 48000.0f);
    std::vector<float> buf(total);
    for (size_t i = 0; i < buf.size(); ++i)
        buf[i] = std::sin(2.0f * 3.14159265f * 440.0f * i / 48000.0f);
    float peak_in = 0.0f;
    for (size_t i = 0; i < buf.size(); ++i)
        peak_in = std::max(peak_in, std::fabs(buf[i]));
    les.process(buf.data(), buf.size());
    float peak_out = 0.0f;
    for (size_t i = 0; i < buf.size(); ++i)
        peak_out = std::max(peak_out, std::fabs(buf[i]));
    CHK(c, peak_out <= peak_in + 1e-5f);
    printf("  master_leslie.gain_never_exceeds_unity (peak_in=%.3f peak_out=%.3f) %s\n",
           peak_in, peak_out, c.ok ? "OK" : "FAIL");
    return c.ok;
}

} // namespace

bool run_master_leslie_tests() {
    printf("master_leslie:\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_disarmed_passthrough());
    tally(test_armed_modulates_output());
    tally(test_edges_near_unity());
    tally(test_peak_at_midpoint());
    tally(test_returns_to_passthrough());
    tally(test_gain_never_exceeds_unity());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
