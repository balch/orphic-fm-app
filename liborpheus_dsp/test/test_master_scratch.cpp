#include "test_harness.h"
#include "orpheus_master_scratch.h"

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

// ── test_disarmed_passthrough ───────────────────────────────────────
// When not armed, output must be identical to input (pure passthrough).
bool test_disarmed_passthrough() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> in(256);
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = std::sin(2.0f * 3.14159265f * i / 48.0f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_scratch.disarmed_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_armed_changes_output ───────────────────────────────────────
// When armed, the stutter gate must audibly change the output.
bool test_armed_changes_output() {
    Checker c;
    orpheus::MasterScratch scratch;
    const int total = 14400;  // 300ms @ 48k
    scratch.arm(total, 48000.0f);
    std::vector<float> in(total);
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = std::sin(2.0f * 3.14159265f * 440.0f * i / 48000.0f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);

    // Compute difference RMS — should be meaningfully non-zero
    double diff_sum = 0.0;
    for (size_t i = 0; i < in.size(); ++i) {
        float d = work[i] - in[i];
        diff_sum += (double)d * d;
    }
    float diff_rms = (float)std::sqrt(diff_sum / in.size());
    CHK(c, diff_rms > 0.01f);  // stutter must be audible
    printf("  master_scratch.armed_changes_output (diff_rms=%.4f) %s\n",
           diff_rms, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_returns_to_passthrough ─────────────────────────────────────
// After the armed duration expires, the effect returns to passthrough.
bool test_returns_to_passthrough() {
    Checker c;
    orpheus::MasterScratch scratch;
    scratch.arm(64, 48000.0f);
    std::vector<float> scratch_buf(64, 0.5f);
    scratch.process(scratch_buf.data(), scratch_buf.size(), 0.0f, 120.0f);
    // Effect should now be done
    CHK(c, !scratch.is_active());

    // Next block should be pure passthrough
    std::vector<float> in(32, 0.7f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_scratch.returns_to_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_gate_creates_silence ───────────────────────────────────────
// Verify the stutter gate actually creates near-zero samples in the
// gated portions. Feed a constant amplitude signal and verify some
// samples are near zero.
bool test_gate_creates_silence() {
    Checker c;
    orpheus::MasterScratch scratch;
    const int total = 24000;  // 500ms @ 48k
    scratch.arm(total, 48000.0f);

    // Fill buffer with constant amplitude 0.8
    std::vector<float> work(total, 0.8f);
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);

    // Count samples that are near zero (below 0.05).
    // A stutter gate should create silence gaps in the output.
    int near_zero = 0;
    for (size_t i = 0; i < work.size(); ++i) {
        if (std::fabs(work[i]) < 0.05f)
            ++near_zero;
    }
    // At least 10% of samples should be gated to near-silence
    float silence_ratio = (float)near_zero / (float)total;
    CHK(c, silence_ratio > 0.10f);
    printf("  master_scratch.gate_creates_silence (silence_ratio=%.3f) %s\n",
           silence_ratio, c.ok ? "OK" : "FAIL");
    return c.ok;
}

} // namespace

bool run_master_scratch_tests() {
    printf("master_scratch:\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_disarmed_passthrough());
    tally(test_armed_changes_output());
    tally(test_returns_to_passthrough());
    tally(test_gate_creates_silence());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
