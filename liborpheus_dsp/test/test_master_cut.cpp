#include "test_harness.h"
#include "orpheus_master_cut.h"

#include <cmath>
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

constexpr float kPi = 3.14159265f;

// ── test_disarmed_passthrough ───────────────────────────────────────
bool test_disarmed_passthrough() {
    Checker c;
    orpheus::MasterCut cut;
    std::vector<float> in(256);
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = std::sin(2.0f * kPi * i / 48.0f);
    std::vector<float> work = in;
    cut.process(work.data(), work.size(), 120.0f);
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_cut.disarmed_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_armed_gates_by_duty ────────────────────────────────────────
// step_samples = 48000*60/120/4 = 6000; with gate_rate_steps=1, cycle=6000.
// Within the first duty*cycle=3000 samples the gate stays open (~1.0); past
// that it falls toward depth (allow the ~2ms slew before it settles).
bool test_armed_gates_by_duty() {
    Checker c;
    orpheus::MasterCut cut;
    const float sample_rate = 48000.0f;
    const float bpm = 120.0f;
    const float gate_rate_steps = 1.0f;
    const float duty = 0.5f;
    const float depth = 0.0f;
    const int total = 6000;  // exactly one gate cycle at these settings
    cut.arm(total, sample_rate, gate_rate_steps, duty, depth);
    std::vector<float> work(total, 1.0f);
    cut.process(work.data(), work.size(), bpm);

    float max_val = 0.0f, min_val = 1.0f;
    for (float v : work) {
        max_val = std::max(max_val, v);
        min_val = std::min(min_val, v);
    }
    CHK(c, max_val > 0.95f);         // gate opens to ~1.0 during the duty portion
    CHK(c, min_val < depth + 0.1f);  // gate falls toward depth after duty
    CHK(c, work[100] > 0.95f);       // well inside the duty window: stays ~1.0
    CHK(c, work[total - 100] < depth + 0.1f);  // well past duty + slew: settled at depth
    printf("  master_cut.armed_gates_by_duty (max=%.3f min=%.3f) %s\n",
           max_val, min_val, c.ok ? "OK" : "FAIL");
    return c.ok;
}

} // namespace

bool run_master_cut_tests() {
    printf("master_cut:\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_disarmed_passthrough());
    tally(test_armed_gates_by_duty());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
