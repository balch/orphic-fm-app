#include "test_harness.h"
#include "orpheus_master_fader.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

// Lightweight assert helper that tracks per-test pass/fail without aborting,
// so a single failed sub-check doesn't kill the whole suite.
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

bool test_idle_passthrough() {
    Checker c;
    orpheus::MasterFader f;
    f.reset(1.0f);
    std::vector<float> buf(64);
    for (size_t i = 0; i < buf.size(); ++i) buf[i] = 0.5f;
    f.process(buf.data(), buf.size());
    for (size_t i = 0; i < buf.size(); ++i) {
        CHK(c, buf[i] == 0.5f);
    }
    printf("  master_fader.idle_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_linear_ramp_0_to_1() {
    Checker c;
    orpheus::MasterFader f;
    f.reset(0.0f);
    f.arm(1.0f, 480, orpheus::FadeCurve::LINEAR);
    std::vector<float> buf(480);
    for (auto& v : buf) v = 1.0f;
    f.process(buf.data(), buf.size());
    CHK(c, std::fabs(buf[479] - 1.0f) < 1e-4f);
    CHK(c, std::fabs(buf[239] - 0.5f) < 0.05f);
    CHK(c, std::fabs(f.current() - 1.0f) < 1e-6f);
    printf("  master_fader.linear_ramp_0_to_1 %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_log_fade_midpoint_near_minus_30db() {
    Checker c;
    orpheus::MasterFader f;
    f.reset(1.0f);
    f.arm(0.0f, 4800, orpheus::FadeCurve::LOG);
    std::vector<float> buf(4800);
    for (auto& v : buf) v = 1.0f;
    f.process(buf.data(), buf.size());
    float mid = buf[2399];
    float db = 20.0f * std::log10(std::max(mid, 1e-6f));
    CHK(c, db > -36.0f && db < -24.0f);
    printf("  master_fader.log_midpoint_minus_30db (mid=%.4f, %.1fdB) %s\n",
           mid, db, c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_monotonic_one_direction() {
    Checker c;
    orpheus::MasterFader f;
    f.reset(0.0f);
    f.arm(1.0f, 1000, orpheus::FadeCurve::EASE_OUT);
    std::vector<float> buf(1000, 1.0f);
    f.process(buf.data(), buf.size());
    for (size_t i = 1; i < buf.size(); ++i) {
        CHK(c, buf[i] >= buf[i - 1] - 1e-5f);
    }
    printf("  master_fader.monotonic_one_direction %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

bool test_arms_target_reached_exactly() {
    Checker c;
    orpheus::MasterFader f;
    f.reset(0.7f);
    f.arm(0.0f, 240, orpheus::FadeCurve::LINEAR);
    std::vector<float> buf(240, 1.0f);
    f.process(buf.data(), buf.size());
    CHK(c, std::fabs(buf[239]) < 1e-4f);
    CHK(c, std::fabs(f.current()) < 1e-6f);
    printf("  master_fader.arms_target_reached_exactly %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

} // namespace

bool run_master_fader_tests() {
    printf("master_fader:\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_idle_passthrough());
    tally(test_linear_ramp_0_to_1());
    tally(test_log_fade_midpoint_near_minus_30db());
    tally(test_monotonic_one_direction());
    tally(test_arms_target_reached_exactly());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
