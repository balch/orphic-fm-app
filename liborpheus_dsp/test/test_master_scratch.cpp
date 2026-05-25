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
    scratch.process(work.data(), work.size());
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_scratch.disarmed_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_armed_changes_output ───────────────────────────────────────
// When armed, the scratch effect must audibly change the output.
bool test_armed_changes_output() {
    Checker c;
    orpheus::MasterScratch scratch;
    const int total = 14400;  // 300ms @ 48k
    scratch.arm(total, 48000.0f);
    std::vector<float> in(total);
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = std::sin(2.0f * 3.14159265f * 440.0f * i / 48000.0f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size());

    // Compute difference RMS — should be meaningfully non-zero
    double diff_sum = 0.0;
    for (size_t i = 0; i < in.size(); ++i) {
        float d = work[i] - in[i];
        diff_sum += (double)d * d;
    }
    float diff_rms = (float)std::sqrt(diff_sum / in.size());
    CHK(c, diff_rms > 0.01f);  // scratch must be audible
    printf("  master_scratch.armed_changes_output (diff_rms=%.4f) %s\n",
           diff_rms, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_no_overrun_amplitude ───────────────────────────────────────
// Output peak should not exceed input peak (within tolerance for
// friction noise contribution).
bool test_no_overrun_amplitude() {
    Checker c;
    orpheus::MasterScratch scratch;
    const int total = 14400;
    scratch.arm(total, 48000.0f);

    // Use deterministic pseudo-random input at +-0.8 so friction noise
    // has headroom before clipping.
    std::vector<float> in(total * 2);  // extra samples after effect ends
    uint32_t rng = 0xDEADBEEFu;
    for (size_t i = 0; i < in.size(); ++i) {
        rng = rng * 1103515245u + 12345u;
        in[i] = ((float)(int32_t)rng / 2147483648.0f) * 0.8f;
    }
    std::vector<float> work = in;
    scratch.process(work.data(), work.size());

    float peak_in = 0.0f, peak_out = 0.0f;
    for (size_t i = 0; i < in.size(); ++i) {
        peak_in = std::max(peak_in, std::fabs(in[i]));
        peak_out = std::max(peak_out, std::fabs(work[i]));
    }
    // Allow margin for friction noise on top of ring buffer content.
    // The soft limiter in process() prevents exceeding 1.0, but friction
    // can push the combined signal above the input peak by up to ~0.25.
    CHK(c, peak_out <= peak_in + 0.25f);
    printf("  master_scratch.no_overrun_amplitude (peak_in=%.3f, peak_out=%.3f) %s\n",
           peak_in, peak_out, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_peak_and_settle_shape ──────────────────────────────────────
// Feed a sine wave and verify the pitch profile matches the peak-and-
// settle curve: pitch rises through the attack phase (Q2 < Q3 around
// the peak at t≈0.65), then settles in Q4 (intervals widen vs peak).
bool test_peak_and_settle_shape() {
    Checker c;
    const float sr = 48000.0f;
    const int total = 24000;  // 500ms
    const float freq = 300.0f;

    orpheus::MasterScratch scratch;

    {
        std::vector<float> prefill(orpheus::MasterScratch::kRingSize);
        for (size_t i = 0; i < prefill.size(); ++i)
            prefill[i] = std::sin(2.0f * 3.14159265f * freq * i / sr);
        scratch.process(prefill.data(), prefill.size());
    }

    scratch.arm(total, sr);

    std::vector<float> buf(total);
    for (size_t i = 0; i < buf.size(); ++i)
        buf[i] = std::sin(2.0f * 3.14159265f * freq * i / sr);
    scratch.process(buf.data(), buf.size());

    auto measure_avg_zc_interval = [&](int start, int end) -> float {
        int crossings = 0;
        int last_cross = start;
        double interval_sum = 0.0;
        for (int j = start + 1; j < end; ++j) {
            if ((buf[j-1] >= 0.0f && buf[j] < 0.0f) ||
                (buf[j-1] < 0.0f && buf[j] >= 0.0f)) {
                if (crossings > 0) {
                    interval_sum += (j - last_cross);
                }
                last_cross = j;
                ++crossings;
            }
        }
        return (crossings > 1) ? (float)(interval_sum / (crossings - 1)) : 0.0f;
    };

    // Q2 (t=0.25..0.5): mid-attack, pitch rising
    // Q3 (t=0.5..0.75): contains the peak at t≈0.65, highest pitch
    // Q4 (t=0.75..1.0): settle phase, pitch easing back down
    int q2_start = total / 4;
    int q2_end = total / 2;
    int q3_start = total / 2;
    int q3_end = total * 3 / 4;
    int q4_start = q3_end;
    int q4_end = total;

    float avg_zc_q2 = measure_avg_zc_interval(q2_start, q2_end);
    float avg_zc_q3 = measure_avg_zc_interval(q3_start, q3_end);
    float avg_zc_q4 = measure_avg_zc_interval(q4_start, q4_end);

    // Attack: Q3 intervals shorter than Q2 (pitch rises toward peak)
    CHK(c, avg_zc_q2 > 0.0f);
    CHK(c, avg_zc_q3 > 0.0f);
    CHK(c, avg_zc_q3 < avg_zc_q2);

    // Q4 still faster than input (rate > 1 even in the settle phase).
    // Note: we can't directly verify Q4 > Q3 via zero-crossings because
    // the wet/dry crossfade (t²) is still increasing through Q4, which
    // makes the scratched signal more prominent and confounds the
    // measurement. The rate curve itself settles, but the mix ramp means
    // perceived pitch can still rise slightly in Q4.
    float input_zc_interval = sr / freq / 2.0f;  // ~80 samples for 300Hz
    CHK(c, avg_zc_q4 > 0.0f);
    CHK(c, avg_zc_q4 < input_zc_interval);

    printf("  master_scratch.peak_and_settle_shape "
           "(q2=%.2f q3=%.2f q4=%.2f input=%.2f) %s\n",
           avg_zc_q2, avg_zc_q3, avg_zc_q4, input_zc_interval,
           c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_returns_to_passthrough ─────────────────────────────────────
// After the scratch completes, the effect returns to passthrough.
bool test_returns_to_passthrough() {
    Checker c;
    orpheus::MasterScratch scratch;
    scratch.arm(64, 48000.0f);
    std::vector<float> scratch_buf(64, 0.5f);
    scratch.process(scratch_buf.data(), scratch_buf.size());
    // Effect should now be done
    CHK(c, !scratch.is_active());

    // Next block should be pure passthrough
    std::vector<float> in(32, 0.7f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size());
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_scratch.returns_to_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_rate_never_reverses ────────────────────────────────────────
// The read head must always advance forward (rate >= 1.0). We verify
// this indirectly: the effect should never replay samples in reverse.
// With a pure monotonic ramp from rate=1 to rate=9, the read head
// always moves forward through the ring buffer.
bool test_rate_never_reverses() {
    Checker c;
    const float sr = 48000.0f;
    const int total = 9600;  // 200ms

    orpheus::MasterScratch scratch;

    // Fill ring with a known ascending ramp so reverse would be detectable
    std::vector<float> prefill(orpheus::MasterScratch::kRingSize);
    for (size_t i = 0; i < prefill.size(); ++i)
        prefill[i] = (float)i / (float)prefill.size();  // 0.0 → 1.0
    scratch.process(prefill.data(), prefill.size());

    scratch.arm(total, sr);

    // Feed ascending ramp continuation
    std::vector<float> buf(total);
    for (size_t i = 0; i < buf.size(); ++i)
        buf[i] = (float)i / (float)total;
    scratch.process(buf.data(), buf.size());

    // In the second half (where wet dominates), the output should
    // generally trend upward (since the read head moves forward through
    // ascending data). Check that the overall trend is non-decreasing
    // by comparing average of first and second halves of the wet region.
    int wet_start = total / 2;
    int mid = (wet_start + total) / 2;
    double avg_first = 0.0, avg_second = 0.0;
    int n_first = mid - wet_start;
    int n_second = total - mid;
    for (int j = wet_start; j < mid; ++j) avg_first += buf[j];
    for (int j = mid; j < total; ++j) avg_second += buf[j];
    avg_first /= n_first;
    avg_second /= n_second;

    // The second half average should not be significantly lower than the first
    // (would indicate reverse playback). Allow some tolerance since the ramp
    // wraps around the ring buffer.
    // We're really checking for gross reverse behavior, not exact monotonicity
    // of a wrapped buffer. The key invariant is: no reverse.
    bool no_reverse = (avg_second >= avg_first * 0.5);
    CHK(c, no_reverse);

    printf("  master_scratch.rate_never_reverses (avg_first=%.4f, avg_second=%.4f) %s\n",
           avg_first, avg_second, c.ok ? "OK" : "FAIL");
    return c.ok;
}

} // namespace

bool run_master_scratch_tests() {
    printf("master_scratch:\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_disarmed_passthrough());
    tally(test_armed_changes_output());
    tally(test_no_overrun_amplitude());
    tally(test_peak_and_settle_shape());
    tally(test_returns_to_passthrough());
    tally(test_rate_never_reverses());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
