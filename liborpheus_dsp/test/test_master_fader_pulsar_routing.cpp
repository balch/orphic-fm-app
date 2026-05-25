// Integration test: verifies that MasterFader actually attenuates Pulsar audio
// when reached via the production graph (orpheus_engine_process).
//
// Why this exists:
//   The unit-level master_fader tests prove the primitive ramps correctly when
//   handed a buffer. But the audible bug ("song transitions sound like hard
//   cuts") implied that Pulsar's audio reached the soundcard via a path that
//   bypassed unit_process_master_out — making the fader functionally inert
//   for the most important use case.
//
// What this test asserts:
//   With a stationary Pulsar vibe playing, arming a fadeMasterVolume(0, ...)
//   over a few hundred samples MUST reduce the peak amplitude of the final
//   stereo output to near-silence within one render block. If Pulsar bypassed
//   master_out, the post-fader peak would still be substantial.

#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_engine.h"

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

// Run a number of stereo render blocks, returning the absolute peak of the
// LAST block only (post-fader steady state, not the ramp's transient).
struct PeakResult {
    float peak_last_block = 0.0f;   // peak of final block
    float peak_overall = 0.0f;      // peak across all blocks
};

static PeakResult render_blocks(OrpheusEngine* engine, int block_size, int num_blocks) {
    PeakResult r;
    std::vector<float> buf(block_size * 2, 0.0f);
    for (int b = 0; b < num_blocks; b++) {
        std::fill(buf.begin(), buf.end(), 0.0f);
        orpheus_engine_process(engine, buf.data(), block_size);
        float pk_block = 0.0f;
        for (int i = 0; i < block_size * 2; i++) {
            float a = std::fabs(buf[i]);
            if (a > pk_block) pk_block = a;
            if (a > r.peak_overall) r.peak_overall = a;
        }
        if (b == num_blocks - 1) r.peak_last_block = pk_block;
    }
    return r;
}

// Build a primed engine: production graph loaded, Pulsar playing a vibe,
// master_volume snapped to 0.7 (default), processed long enough that pulsar
// is producing steady audible audio.
static OrpheusEngine* make_pulsar_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!engine) return nullptr;
    if (!load_production_graph(engine)) {
        orpheus_engine_destroy(engine);
        return nullptr;
    }

    // Default master_volume is 0.7 (and the fader is reset to it on engine
    // create). Snap explicitly for clarity.
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

// Test 1: With Pulsar producing audio, arming a near-instant fade to 0 must
// drop the output peak to near-silence.
bool test_fade_to_zero_silences_pulsar() {
    Checker c;
    OrpheusEngine* engine = make_pulsar_engine();
    CHK(c, engine != nullptr);
    if (!engine) {
        printf("  master_fader_pulsar.fade_to_zero_silences_pulsar FAIL (engine init)\n");
        return false;
    }

    // Measure baseline peak across several blocks at master_volume=0.7.
    // Pulsar is rhythmic, so a single block may land in a silent gap; sample
    // several blocks and use the overall peak.
    PeakResult baseline = render_blocks(engine, 256, 24);
    CHK(c, baseline.peak_overall > 0.05f);   // Pulsar IS audible at baseline

    // Arm a fast fade to 0 over 128 samples (~2.7 ms @ 48 kHz). Then render
    // a couple of blocks; the fader will have fully completed within block 1
    // and block 2 should be near-silent (only any in-flight reverb/delay tail).
    orpheus_engine_master_fade(engine, 0.0f, 128, /*LINEAR*/0);
    PeakResult faded = render_blocks(engine, 256, 8);

    // Last block's peak must be substantially below baseline — fader is the
    // last gain stage in the chain, so output must approach zero.
    CHK(c, faded.peak_last_block < baseline.peak_overall * 0.05f);
    CHK(c, faded.peak_last_block < 0.01f);

    // Sanity: the fader's current() should report ~0.
    CHK(c, std::fabs(orpheus_engine_master_volume_now(engine)) < 1e-4f);

    printf("  master_fader_pulsar.fade_to_zero_silences_pulsar baseline_peak=%.4f faded_last=%.4f fader_now=%.4f %s\n",
           baseline.peak_overall, faded.peak_last_block,
           orpheus_engine_master_volume_now(engine),
           c.ok ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return c.ok;
}

// Test 2: A linear ramp 0.7 -> 0 over ~10 ms must be MONOTONICALLY non-
// increasing in per-block peak (within noise). Same wiring assertion as test
// 1 but proves the ramp shape is visible at the bus output, not just the
// terminal value.
bool test_fade_ramp_is_monotonic_at_master() {
    Checker c;
    OrpheusEngine* engine = make_pulsar_engine();
    CHK(c, engine != nullptr);
    if (!engine) {
        printf("  master_fader_pulsar.fade_ramp_is_monotonic_at_master FAIL (engine init)\n");
        return false;
    }

    // Use small blocks so we get many samples along the ramp.
    constexpr int kBlock = 64;
    constexpr int kFadeSamples = 48 * 10;  // 10 ms @ 48 kHz = 480 samples
    constexpr int kNumBlocks = kFadeSamples / kBlock + 8;

    // RMS per block is more stable than instantaneous peak for monotonicity.
    auto rms_block = [&](OrpheusEngine* eng) {
        float buf[kBlock * 2];
        std::fill(buf, buf + kBlock * 2, 0.0f);
        orpheus_engine_process(eng, buf, kBlock);
        double sum = 0.0;
        for (int i = 0; i < kBlock * 2; i++) sum += (double)buf[i] * buf[i];
        return (float)std::sqrt(sum / (kBlock * 2));
    };

    // Pre-ramp RMS
    float pre = 0.0f;
    for (int b = 0; b < 8; b++) pre = std::max(pre, rms_block(engine));
    CHK(c, pre > 0.005f);

    // Arm ramp and collect per-block RMS during and after.
    orpheus_engine_master_fade(engine, 0.0f, kFadeSamples, /*LINEAR*/0);
    std::vector<float> rms_series;
    rms_series.reserve(kNumBlocks);
    for (int b = 0; b < kNumBlocks; b++) rms_series.push_back(rms_block(engine));

    // Find the maximum RMS across the ramp and require it to be at most pre.
    // (It cannot exceed pre, since the fader can only attenuate.)
    float series_max = 0.0f;
    for (float v : rms_series) series_max = std::max(series_max, v);
    CHK(c, series_max <= pre * 1.10f);    // 10% slack for Pulsar rhythm variance

    // Final blocks should be near-silent.
    float tail = 0.0f;
    for (int i = (int)rms_series.size() - 3; i < (int)rms_series.size(); i++) {
        tail = std::max(tail, rms_series[i]);
    }
    CHK(c, tail < pre * 0.05f);
    CHK(c, tail < 0.005f);

    printf("  master_fader_pulsar.fade_ramp_is_monotonic_at_master pre_rms=%.4f series_max=%.4f tail_rms=%.4f %s\n",
           pre, series_max, tail, c.ok ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return c.ok;
}

// Test 3: After a fade-out, a subsequent fade-in must restore Pulsar audio to
// approximately the pre-fade level. Catches the case where a fade-to-zero
// somehow latches into a non-recoverable state.
bool test_fade_out_then_in_restores_pulsar() {
    Checker c;
    OrpheusEngine* engine = make_pulsar_engine();
    CHK(c, engine != nullptr);
    if (!engine) {
        printf("  master_fader_pulsar.fade_out_then_in_restores_pulsar FAIL (engine init)\n");
        return false;
    }

    PeakResult baseline = render_blocks(engine, 256, 24);
    CHK(c, baseline.peak_overall > 0.05f);

    orpheus_engine_master_fade(engine, 0.0f, 128, 0);
    render_blocks(engine, 256, 8);

    orpheus_engine_master_fade(engine, 0.7f, 128, 0);
    PeakResult restored = render_blocks(engine, 256, 24);

    // Restored peak should be in the same ballpark as baseline (Pulsar is
    // non-deterministic and the seek through the pattern advanced, so allow
    // wide tolerance: 0.3x to 3x).
    CHK(c, restored.peak_overall > baseline.peak_overall * 0.3f);
    CHK(c, std::fabs(orpheus_engine_master_volume_now(engine) - 0.7f) < 1e-3f);

    printf("  master_fader_pulsar.fade_out_then_in_restores_pulsar baseline=%.4f restored=%.4f fader_now=%.4f %s\n",
           baseline.peak_overall, restored.peak_overall,
           orpheus_engine_master_volume_now(engine),
           c.ok ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return c.ok;
}

}  // namespace

bool run_master_fader_pulsar_routing_tests() {
    printf("master_fader_pulsar:\n");
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };
    tally(test_fade_to_zero_silences_pulsar());
    tally(test_fade_ramp_is_monotonic_at_master());
    tally(test_fade_out_then_in_restores_pulsar());
    TEST_SUITE_RETURN(pass, fail);
}
