#include "test_harness.h"
#include "orpheus_master_wah.h"

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

std::vector<float> make_sine(size_t n, float freq_hz = 440.0f, float sample_rate = 48000.0f) {
    std::vector<float> out(n);
    for (size_t i = 0; i < n; ++i)
        out[i] = std::sin(2.0f * kPi * freq_hz * (float)i / sample_rate);
    return out;
}

// ── test_disarmed_passthrough ───────────────────────────────────────
bool test_disarmed_passthrough() {
    Checker c;
    orpheus::MasterWah wah;
    std::vector<float> in = make_sine(256);
    std::vector<float> work = in;
    wah.process(work.data(), work.size(), 120.0f);
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, work[i] == in[i]);
    }
    printf("  master_wah.disarmed_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_armed_wet_zero_is_identity ─────────────────────────────────
// Even armed and running, wet==0 means env * wet == 0 everywhere, so the
// wet path never contributes and output must stay bit-identical to input.
bool test_armed_wet_zero_is_identity() {
    Checker c;
    orpheus::MasterWah wah;
    orpheus::WahParams params;
    params.wet = 0.0f;
    const int total = 4800;
    wah.arm(total, 48000.0f, params);
    std::vector<float> in = make_sine((size_t)total);
    std::vector<float> work = in;
    wah.process(work.data(), work.size(), 120.0f);
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, work[i] == in[i]);
    }
    printf("  master_wah.armed_wet_zero_is_identity %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_armed_default_params_is_audible_and_bounded ────────────────
bool test_armed_default_params_is_audible_and_bounded() {
    Checker c;
    orpheus::MasterWah wah;
    orpheus::WahParams params;  // defaults: wet=1.0
    const int total = 9600;     // 200ms @ 48kHz
    wah.arm(total, 48000.0f, params);
    std::vector<float> in = make_sine((size_t)total);
    std::vector<float> work = in;
    wah.process(work.data(), work.size(), 120.0f);

    bool differs = false;
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::isfinite(work[i]));
        CHK(c, std::fabs(work[i]) < 20.0f);
        // "somewhere in the middle" — only check the sustain region, since
        // the ramp edges intentionally approach zero wet (near-identity).
        if (i > in.size() / 4 && i < 3 * in.size() / 4) {
            if (std::fabs(work[i] - in[i]) > 1e-4f) differs = true;
        }
    }
    CHK(c, differs);
    printf("  master_wah.armed_default_params_is_audible_and_bounded (differs=%d) %s\n",
           differs, c.ok ? "OK" : "FAIL");
    return c.ok;
}

} // namespace

bool run_master_wah_tests() {
    printf("master_wah:\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_disarmed_passthrough());
    tally(test_armed_wet_zero_is_identity());
    tally(test_armed_default_params_is_audible_and_bounded());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
