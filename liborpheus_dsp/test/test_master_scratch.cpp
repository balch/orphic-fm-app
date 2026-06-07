#include "test_harness.h"
#include "orpheus_master_scratch.h"

#include <cmath>
#include <cstdint>
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

constexpr float kPi = 3.14159265f;

// Deterministic noise for priming the ring buffer in tests.
struct TestRng {
    uint32_t s;
    explicit TestRng(uint32_t seed) : s(seed) {}
    float next() { s = s * 1664525u + 1013904223u; return ((s >> 8) & 0xFFFFFFu) / 8388608.0f - 1.0f; }
};

// Run a block through the effect while DISARMED to prime the ring buffer
// (mirrors real usage, where audio always flows before arm()).
void prime_ring(orpheus::MasterScratch& s, std::vector<float>& block) {
    s.process(block.data(), block.size(), 0.0f, 0.0f);
}

// ── test_disarmed_passthrough ───────────────────────────────────────
bool test_disarmed_passthrough() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> in(256);
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = std::sin(2.0f * kPi * i / 48.0f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_scratch.disarmed_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_armed_changes_output ───────────────────────────────────────
bool test_armed_changes_output() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> prime(8192);
    TestRng r(0xBEEF);
    for (auto& v : prime) v = 0.5f * r.next();
    prime_ring(scratch, prime);

    const int total = 14400;  // 300ms @ 48k
    scratch.arm(total, 48000.0f);
    std::vector<float> in(total);
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = std::sin(2.0f * kPi * 440.0f * i / 48000.0f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);

    double diff_sum = 0.0;
    for (size_t i = 0; i < in.size(); ++i) {
        float d = work[i] - in[i];
        diff_sum += (double)d * d;
    }
    float diff_rms = (float)std::sqrt(diff_sum / in.size());
    CHK(c, diff_rms > 0.01f);
    printf("  master_scratch.armed_changes_output (diff_rms=%.4f) %s\n",
           diff_rms, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_returns_to_passthrough ─────────────────────────────────────
bool test_returns_to_passthrough() {
    Checker c;
    orpheus::MasterScratch scratch;
    scratch.arm(64, 48000.0f);
    std::vector<float> scratch_buf(64, 0.5f);
    scratch.process(scratch_buf.data(), scratch_buf.size(), 0.0f, 120.0f);
    CHK(c, !scratch.is_active());

    std::vector<float> in(32, 0.7f);
    std::vector<float> work = in;
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);
    for (size_t i = 0; i < in.size(); ++i) {
        CHK(c, std::fabs(work[i] - in[i]) < 1e-6f);
    }
    printf("  master_scratch.returns_to_passthrough %s\n", c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_click_transients_present ───────────────────────────────────
// A click fires on each repeat catch and the squeal catch (plus the drop).
// Prime with a quiet sine so the slice level is low; clicks stand out as
// spikes well above it. Count distinct ONSETS with a refractory window so a
// single decaying click isn't recounted as its noisy tail crosses back.
bool test_click_transients_present() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> prime(8192);
    for (size_t i = 0; i < prime.size(); ++i)
        prime[i] = 0.08f * std::sin(2.0f * kPi * 200.0f * i / 48000.0f);
    prime_ring(scratch, prime);

    const int total = 24000;  // 500ms @ 48k -> N=1: repeat + squeal (+ drop) catches
    scratch.arm(total, 48000.0f);
    std::vector<float> work(total, 0.0f);
    scratch.process(work.data(), work.size(), 0.0f, 0.0f);

    int lo = (int)(0.05f * total), hi = (int)(0.95f * total);
    int onsets = 0, refractory = 0;
    for (int i = lo; i < hi; ++i) {
        if (refractory > 0) { --refractory; continue; }
        if (std::fabs(work[i]) > 0.25f) {  // slice is ~0.08; clicks are ~0.6
            ++onsets;
            refractory = 2000;  // ~42ms: past the ~4ms click tail, under the segment spacing
        }
    }
    CHK(c, onsets >= 2 && onsets <= 6);  // repeat + squeal catches (+ drop)
    printf("  master_scratch.click_transients_present (onsets=%d) %s\n",
           onsets, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_squeal_pitch_sweep ─────────────────────────────────────────
// The repeat replays the captured audio (so its zero-crossing rate matches the
// primed low tone), and the squeal arc pitches it way up (so the squeal-region
// ZCR is much higher). Prime a clean low tone; feed silence so the output is
// the wet skip.
bool test_squeal_pitch_sweep() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> prime(8192);
    for (size_t i = 0; i < prime.size(); ++i)
        prime[i] = 0.4f * std::sin(2.0f * kPi * 120.0f * i / 48000.0f);
    prime_ring(scratch, prime);

    const int total = 24000;  // 500ms @ 48k -> N=1
    scratch.arm(total, 48000.0f);
    std::vector<float> work(total, 0.0f);
    scratch.process(work.data(), work.size(), 0.0f, 0.0f);

    // Debounced ZCR: only count sign changes between samples above a small
    // floor, so the faint hiss bed near the tone's zero-crossings can't inflate
    // the count (floor 0.05 >> hiss 0.004, << tone 0.4).
    auto zcr = [&](int lo, int hi) {
        int ch = 0, last_sign = 0;
        for (int i = lo; i < hi; ++i) {
            if (std::fabs(work[i]) < 0.05f) continue;
            int sign = work[i] < 0.0f ? -1 : 1;
            if (last_sign != 0 && sign != last_sign) ++ch;
            last_sign = sign;
        }
        return (double)ch / (double)(hi - lo);
    };
    double zr = zcr((int)(0.13f * total), (int)(0.30f * total)); // repeat region (~120Hz)
    double zs = zcr((int)(0.50f * total), (int)(0.80f * total)); // squeal region (sped up)
    double expected = 2.0 * 120.0 / 48000.0;                     // ~0.005 for 120Hz

    CHK(c, zr > 0.4 * expected && zr < 2.0 * expected); // repeat replays the captured low tone
    CHK(c, zs > 2.0 * zr);                              // squeal pitches up
    printf("  master_scratch.squeal_pitch_sweep (zr=%.5f zs=%.5f exp=%.5f) %s\n",
           zr, zs, expected, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_drops_to_live ──────────────────────────────────────────────
bool test_drops_to_live() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> prime(8192, 0.3f);
    prime_ring(scratch, prime);

    const int total = 24000;
    scratch.arm(total, 48000.0f);
    std::vector<float> in(total);
    for (int i = 0; i < total; ++i)
        in[i] = -0.5f + (float)i / (float)total;
    std::vector<float> work = in;
    scratch.process(work.data(), work.size(), 0.0f, 120.0f);

    int n_ok = 0, n_tot = 0;
    for (int i = total - 800; i < total; ++i) {
        ++n_tot;
        if (std::fabs(work[i] - in[i]) < 1e-4f) ++n_ok;
    }
    CHK(c, n_ok == n_tot);  // tail is pure live audio
    printf("  master_scratch.drops_to_live (%d/%d live) %s\n",
           n_ok, n_tot, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_no_dc_offset ───────────────────────────────────────────────
// Guard total armed-output DC (the looped slice plus the noise bed stay near
// zero-mean).
bool test_no_dc_offset() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> prime(8192);
    for (size_t i = 0; i < prime.size(); ++i)
        prime[i] = 0.4f * std::sin(2.0f * kPi * 330.0f * i / 48000.0f);
    prime_ring(scratch, prime);

    const int total = 24000;
    scratch.arm(total, 48000.0f);
    std::vector<float> work(total, 0.0f);
    scratch.process(work.data(), work.size(), 0.0f, 0.0f);

    double sum = 0.0;
    for (float v : work) sum += v;
    float mean = (float)(sum / total);
    CHK(c, std::fabs(mean) < 0.02f);
    printf("  master_scratch.no_dc_offset (mean=%.5f) %s\n", mean, c.ok ? "OK" : "FAIL");
    return c.ok;
}

// ── test_output_level_sane ──────────────────────────────────────────
bool test_output_level_sane() {
    Checker c;
    orpheus::MasterScratch scratch;
    std::vector<float> prime(8192);
    for (size_t i = 0; i < prime.size(); ++i)
        prime[i] = 0.5f * std::sin(2.0f * kPi * 440.0f * i / 48000.0f);
    prime_ring(scratch, prime);

    const int total = 24000;
    scratch.arm(total, 48000.0f);
    std::vector<float> work(total, 0.0f);
    scratch.process(work.data(), work.size(), 0.0f, 0.0f);

    float peak = 0.0f; double sq = 0.0;
    for (float v : work) { peak = std::max(peak, std::fabs(v)); sq += (double)v * v; }
    float rms = (float)std::sqrt(sq / total);
    CHK(c, peak < 1.5f);
    CHK(c, rms > 0.01f);
    printf("  master_scratch.output_level_sane (peak=%.3f rms=%.3f) %s\n",
           peak, rms, c.ok ? "OK" : "FAIL");
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
    tally(test_click_transients_present());
    tally(test_squeal_pitch_sweep());
    tally(test_drops_to_live());
    tally(test_no_dc_offset());
    tally(test_output_level_sane());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
