// Integration test: verifies that MasterTapeStop actually affects Pulsar audio
// when reached via the production graph (orpheus_engine_process).
//
// Why this exists:
//   The unit-level master_tape_stop tests prove the primitive freezes its
//   output when handed a buffer. But the audible bug (tape-stop "feeling
//   inert" during SCRATCH/CUT outros) implied Pulsar's audio might reach the
//   soundcard via a path that bypassed unit_process_master_out — making
//   tape-stop functionally inert for the most important use case. This test
//   is the sibling of master_fader_pulsar (ba05f211): same wiring assertion,
//   different primitive on the master bus chain.
//
// What this test asserts:
//   1. With a stationary Pulsar vibe playing, arming masterTapeStop(N) MUST
//      cause the final output samples to settle on a constant value (the
//      tape-stop's "frozen" read head). If Pulsar bypassed master_out, the
//      output would continue varying.
//   2. After the tape-stop completes, subsequent renders MUST return to live
//      Pulsar audio (not stuck on the frozen value).
//   3. The disarmed passthrough path MUST advance the write head so an
//      immediate arm reads recent Pulsar audio (not stale zeros).

#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_engine.h"

#include <cmath>
#include <cstdio>
#include <vector>
#include <algorithm>

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

// Render a number of stereo blocks, returning the absolute peak/RMS over them
// AND the full concatenated stereo buffer (interleaved L,R,L,R,...) for finer
// sample-level inspection at the tail.
struct RenderTrace {
    std::vector<float> stereo;      // interleaved L/R across all rendered samples
    float peak_overall = 0.0f;
    float rms_overall = 0.0f;
};

static RenderTrace render_blocks(OrpheusEngine* engine, int block_size, int num_blocks) {
    RenderTrace r;
    r.stereo.resize(block_size * 2 * num_blocks, 0.0f);
    double sum_sq = 0.0;
    int total_samples = block_size * 2 * num_blocks;
    for (int b = 0; b < num_blocks; b++) {
        float* dst = r.stereo.data() + b * block_size * 2;
        std::fill(dst, dst + block_size * 2, 0.0f);
        orpheus_engine_process(engine, dst, block_size);
        for (int i = 0; i < block_size * 2; i++) {
            float a = std::fabs(dst[i]);
            if (a > r.peak_overall) r.peak_overall = a;
            sum_sq += (double)dst[i] * dst[i];
        }
    }
    r.rms_overall = (float)std::sqrt(sum_sq / total_samples);
    return r;
}

// Build a primed engine: production graph loaded, Pulsar playing a vibe,
// master_volume snapped to 0.7 (default), processed long enough that pulsar
// is producing steady audible audio. Mirrors the master_fader_pulsar helper.
static OrpheusEngine* make_pulsar_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!engine) return nullptr;
    if (!load_production_graph(engine)) {
        orpheus_engine_destroy(engine);
        return nullptr;
    }
    orpheus_engine_set_master_volume(engine, 0.7f);

    // Configure and start Pulsar.
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

    // Render ~2 seconds to let the pattern generator emit notes and voices
    // ring out. 48000 * 2 / 256 = 375 blocks.
    render_blocks(engine, 256, 375);

    return engine;
}

// Test 1: With Pulsar producing audio, arming a tape-stop MUST cause the
// final samples to freeze on a constant value (the tape-stop's "frozen"
// signature). If Pulsar bypassed master_out, the tail samples would continue
// varying with the pattern generator's output.
bool test_tape_stop_affects_pulsar_audio() {
    Checker c;
    OrpheusEngine* engine = make_pulsar_engine();
    CHK(c, engine != nullptr);
    if (!engine) {
        printf("  master_tape_stop_pulsar.tape_stop_affects_pulsar_audio FAIL (engine init)\n");
        return false;
    }

    // Baseline: render some blocks, capture peak. Pulsar is rhythmic so we
    // sample across several blocks.
    constexpr int kBlock = 256;
    RenderTrace baseline = render_blocks(engine, kBlock, 24);
    CHK(c, baseline.peak_overall > 0.05f);   // Pulsar IS audible at baseline

    // Arm tape-stop for 300 ms @ 48 kHz = 14400 samples. We must inspect
    // samples WITHIN the ramp window (the last ~100 of the ramp) because
    // once samples_left_ hits 0, the next process() call falls into the
    // passthrough branch — outputting LIVE pulsar audio, not the frozen
    // signature we're trying to verify. So we render exactly kStopSamples
    // (block-aligned) and inspect the last 100 samples of that window.
    constexpr int kStopSamples = 14336;          // 56 * 256, block-aligned
    constexpr int kNumStopBlocks = kStopSamples / kBlock;

    orpheus_engine_master_tape_stop(engine, kStopSamples);
    RenderTrace stopped = render_blocks(engine, kBlock, kNumStopBlocks);

    // At the end of the ramp, t→1 and rate=(1-t)²→0, so the read head has
    // nearly stopped advancing. The master_out chain after tape_stop applies
    // pan/fader/limiter/soft-sat — none of which introduce time variance on
    // a constant input (feed-forward limiter env settles on constant peak).
    // So the FINAL ~100 samples within the ramp must be near-constant
    // (within ~0.01) — same tolerance as the unit test.
    int total_samples_rendered = (int)stopped.stereo.size() / 2;
    int last_n = 100;
    int start = total_samples_rendered - last_n;
    float frozen_l = stopped.stereo[start * 2];
    float frozen_r = stopped.stereo[start * 2 + 1];
    float max_delta_l = 0.0f, max_delta_r = 0.0f;
    for (int i = start + 1; i < total_samples_rendered; i++) {
        max_delta_l = std::max(max_delta_l, std::fabs(stopped.stereo[i * 2] - frozen_l));
        max_delta_r = std::max(max_delta_r, std::fabs(stopped.stereo[i * 2 + 1] - frozen_r));
    }

    // Must be frozen within 0.01 — same tolerance as the unit test. If this
    // fails, either Pulsar audio is bypassing master_out (tape_stop never
    // sees it) OR a downstream stage is reintroducing variation on a
    // constant input.
    CHK(c, max_delta_l < 0.01f);
    CHK(c, max_delta_r < 0.01f);

    // Sanity: tape-stop should have exhausted exactly at the end of the
    // window (samples_total == samples consumed).
    CHK(c, !engine->master_tape_stop_l.is_active());
    CHK(c, !engine->master_tape_stop_r.is_active());

    printf("  master_tape_stop_pulsar.tape_stop_affects_pulsar_audio "
           "baseline_peak=%.4f frozen_l=%.4f frozen_r=%.4f "
           "max_delta_l=%.5f max_delta_r=%.5f %s\n",
           baseline.peak_overall, frozen_l, frozen_r,
           max_delta_l, max_delta_r, c.ok ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return c.ok;
}

// Test 2: After tape-stop completes, additional renders WITHOUT re-arming MUST
// return to live Pulsar audio. Catches a bypass-resume bug where the chain
// stays "stuck" on the frozen value (e.g. if a downstream stage cached the
// last sample).
bool test_tape_stop_then_passthrough_returns_to_pulsar() {
    Checker c;
    OrpheusEngine* engine = make_pulsar_engine();
    CHK(c, engine != nullptr);
    if (!engine) {
        printf("  master_tape_stop_pulsar.tape_stop_then_passthrough_returns_to_pulsar "
               "FAIL (engine init)\n");
        return false;
    }

    // Arm tape-stop and run for EXACTLY the ramp duration so the last
    // sample of `stopped` is still within the frozen-tail. (Going past
    // kStopSamples would drop into passthrough and grab live audio.)
    constexpr int kBlock = 256;
    constexpr int kStopSamples = 14336;  // 56 * 256, block-aligned
    constexpr int kStopBlocks = kStopSamples / kBlock;

    orpheus_engine_master_tape_stop(engine, kStopSamples);
    RenderTrace stopped = render_blocks(engine, kBlock, kStopBlocks);
    CHK(c, !engine->master_tape_stop_l.is_active());

    // Capture the frozen-tail value (last sample of the ramp window) for
    // comparison against post-arm live audio.
    int n_stopped = (int)stopped.stereo.size() / 2;
    float frozen_l = stopped.stereo[(n_stopped - 1) * 2];
    float frozen_r = stopped.stereo[(n_stopped - 1) * 2 + 1];

    // Now render more blocks WITHOUT re-arming. Pulsar's pattern generator
    // is still running, so output should vary again. Use enough blocks for
    // the pattern to emit a fresh hit (~24 blocks = ~128 ms @ kBlock=256).
    RenderTrace resumed = render_blocks(engine, kBlock, 24);

    // The resumed audio should NOT be stuck at the frozen value. We test
    // both that the peak is meaningfully larger AND that the audio is varying
    // (RMS deviation from frozen is non-trivial).
    int n_resumed = (int)resumed.stereo.size() / 2;
    float max_dev_l = 0.0f, max_dev_r = 0.0f;
    double dev_sq_l = 0.0, dev_sq_r = 0.0;
    for (int i = 0; i < n_resumed; i++) {
        float dl = resumed.stereo[i * 2] - frozen_l;
        float dr = resumed.stereo[i * 2 + 1] - frozen_r;
        max_dev_l = std::max(max_dev_l, std::fabs(dl));
        max_dev_r = std::max(max_dev_r, std::fabs(dr));
        dev_sq_l += (double)dl * dl;
        dev_sq_r += (double)dr * dr;
    }
    float dev_rms_l = (float)std::sqrt(dev_sq_l / n_resumed);
    float dev_rms_r = (float)std::sqrt(dev_sq_r / n_resumed);

    // Audio must vary by at least 0.01 peak (vs. frozen tolerance of <0.01).
    CHK(c, max_dev_l > 0.01f);
    CHK(c, max_dev_r > 0.01f);
    // And the resumed peak must be meaningful (Pulsar audio range).
    CHK(c, resumed.peak_overall > 0.02f);

    printf("  master_tape_stop_pulsar.tape_stop_then_passthrough_returns_to_pulsar "
           "frozen_l=%.4f resumed_peak=%.4f max_dev_l=%.4f dev_rms_l=%.4f "
           "dev_rms_r=%.4f %s\n",
           frozen_l, resumed.peak_overall, max_dev_l, dev_rms_l, dev_rms_r,
           c.ok ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return c.ok;
}

// Test 3: Disarmed passthrough must still advance the write head so an
// immediate arm reads recent Pulsar audio, NOT stale zeros from the ring.
// Subtle but important: if write_head never moved during passthrough, the
// first sample after arming would be a stale zero (or the last value from
// before the engine had any signal), creating an audible click that doesn't
// represent the most recent audio.
bool test_tape_stop_writes_pulsar_to_ring() {
    Checker c;
    OrpheusEngine* engine = make_pulsar_engine();
    CHK(c, engine != nullptr);
    if (!engine) {
        printf("  master_tape_stop_pulsar.tape_stop_writes_pulsar_to_ring "
               "FAIL (engine init)\n");
        return false;
    }

    // Render some Pulsar audio (no arm) — this exercises the disarmed
    // passthrough branch of MasterTapeStop::process().
    constexpr int kBlock = 256;
    RenderTrace pre = render_blocks(engine, kBlock, 24);
    CHK(c, pre.peak_overall > 0.05f);  // we have audible Pulsar audio
    CHK(c, !engine->master_tape_stop_l.is_active());

    // Capture the LAST sample of the last pre-arm block — this should
    // (closely) match the FIRST sample produced after arming if the ring
    // buffer was being kept fresh during passthrough. We allow a small
    // tolerance because:
    //   - the soft-sat / limiter is sample-by-sample and stateful;
    //   - the tape-stop's first output sample reads ring_[write_head_]
    //     (the immediately-previous sample written), so it lags the live
    //     input by exactly 1 sample at t=0;
    //   - very high rates of change (transient hits) can produce noticeable
    //     deltas in just 1 sample.
    // The key assertion: the first armed sample is NOT a stale zero from
    // an unfilled ring slot — it should be in the same amplitude ballpark
    // as the recent Pulsar audio.
    int n_pre = (int)pre.stereo.size() / 2;
    float last_pre_l = pre.stereo[(n_pre - 1) * 2];
    float last_pre_r = pre.stereo[(n_pre - 1) * 2 + 1];

    // Now arm and render exactly ONE block. The first sample of this block
    // is what we care about.
    constexpr int kStopSamples = 14400;
    orpheus_engine_master_tape_stop(engine, kStopSamples);
    RenderTrace first = render_blocks(engine, kBlock, 1);
    float first_armed_l = first.stereo[0];
    float first_armed_r = first.stereo[1];

    // PRIMARY ASSERTION: the first armed sample must be in the same
    // amplitude ballpark as the recent audio — NOT a stale zero. This catches
    // the bug where the disarmed-passthrough branch fails to advance the
    // write head: the ring would still contain zeros from initialization,
    // so the first armed sample would be 0 (or wildly off from recent
    // levels).
    //
    // We measure recent RMS as the "expected ballpark" and demand that the
    // first armed sample's magnitude is within a generous band of that RMS.
    // A stale-zero bug would produce |first_armed| ≈ 0 even when RMS ≈ 0.1.
    int recent_n = std::min(n_pre, 1024);
    double recent_sq = 0.0;
    for (int i = n_pre - recent_n; i < n_pre; i++) {
        recent_sq += (double)pre.stereo[i * 2] * pre.stereo[i * 2];
    }
    float recent_rms_l = (float)std::sqrt(recent_sq / recent_n);

    // If recent RMS is meaningfully nonzero (say > 0.02), the first armed
    // sample should be roughly in that range. Stale-zero would be |x|<0.001
    // while recent_rms > 0.02 — caught here.
    bool stale_zero_bug = (recent_rms_l > 0.02f) &&
                          (std::fabs(first_armed_l) < recent_rms_l * 0.05f) &&
                          (std::fabs(first_armed_r) < recent_rms_l * 0.05f);
    CHK(c, !stale_zero_bug);

    // SECONDARY ASSERTION: continuity. The first armed sample should track
    // the last passthrough sample within a generous window. This is weaker
    // than "near-equal" because Pulsar audio at transients can move quickly,
    // but a complete discontinuity (frozen-ring effect) would blow this.
    float delta_l = std::fabs(first_armed_l - last_pre_l);
    float delta_r = std::fabs(first_armed_r - last_pre_r);
    // Allow up to 2x the recent RMS in single-sample jump. Stale-zero from
    // an unfilled ring would produce delta == |last_pre_*|, often dominating
    // this tolerance.
    float tolerance = std::max(0.05f, recent_rms_l * 2.0f);
    CHK(c, delta_l < tolerance);
    CHK(c, delta_r < tolerance);

    printf("  master_tape_stop_pulsar.tape_stop_writes_pulsar_to_ring "
           "recent_rms=%.4f last_pre=(%.4f,%.4f) first_armed=(%.4f,%.4f) "
           "delta=(%.4f,%.4f) tol=%.4f %s\n",
           recent_rms_l, last_pre_l, last_pre_r, first_armed_l, first_armed_r,
           delta_l, delta_r, tolerance, c.ok ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return c.ok;
}

}  // namespace

bool run_master_tape_stop_pulsar_routing_tests() {
    printf("master_tape_stop_pulsar:\n");
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };
    tally(test_tape_stop_affects_pulsar_audio());
    tally(test_tape_stop_then_passthrough_returns_to_pulsar());
    tally(test_tape_stop_writes_pulsar_to_ring());
    TEST_SUITE_RETURN(pass, fail);
}
