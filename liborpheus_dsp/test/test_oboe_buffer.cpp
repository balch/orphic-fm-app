// Oboe Buffer Simulation Tests
//
// Simulates Oboe audio callback patterns to catch issues with:
//   - Large buffer sizes (Bluetooth A2DP: 1024-2048 frames)
//   - Small buffer sizes (low-latency: 48-96 frames)
//   - Varying buffer sizes (jitter between callbacks)
//   - Sample rate differences (44100 vs 48000)
//   - CPU budget at different buffer sizes
//
// These tests call orpheus_engine_process() directly (same entry point
// as the Oboe onAudioReady callback), NOT unit_process_pulsar().

#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include <cstdio>
#include <cmath>
#include <cstring>
#include <chrono>

static OrpheusEngine* make_oboe_engine(float sample_rate) {
    OrpheusEngine* engine = orpheus_engine_create(sample_rate);
    if (!load_production_graph(engine)) {
        fprintf(stderr, "WARNING: no production graph, Oboe tests will skip\n");
    }
    activate_voice(engine, 0, 8, 60.0f);  // VA engine, C4
    orpheus_engine_set_master_volume(engine, 0.8f);
    return engine;
}

// ═══════════════════════════════════════════════════════════════════════

bool run_oboe_buffer_tests() {
    printf("\n=== Oboe Buffer Simulation Tests ===\n\n");
    int pass = 0, fail = 0;

    // ── Test 1: Standard 512-frame callback produces audio ──
    {
        printf("  Test 1: Standard 512-frame callback produces audio\n");
        OrpheusEngine* engine = make_oboe_engine(48000.0f);

        float buf[512 * 2];
        // Warm up
        for (int i = 0; i < 50; i++) orpheus_engine_process(engine, buf, 512);

        float peak = 0.0f;
        bool has_nan = false;
        orpheus_engine_process(engine, buf, 512);
        for (int i = 0; i < 512 * 2; i++) {
            if (std::isnan(buf[i]) || std::isinf(buf[i])) has_nan = true;
            float a = std::fabs(buf[i]);
            if (a > peak) peak = a;
        }

        printf("    peak=%.4f nan=%s\n", peak, has_nan ? "YES" : "no");
        bool ok = !has_nan && peak > 0.01f;
        if (ok) { printf("    PASS\n"); pass++; }
        else    { printf("    FAIL\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 2: Large buffer (1024) — check for silence gap at sample 512 ──
    // This is the critical Bluetooth test. If orpheus_graph_process clamps
    // to kMaxFrames=512, the second half will be silence → periodic clicks.
    {
        printf("  Test 2: Large buffer (1024) — check for silence gap at sample 512\n");
        OrpheusEngine* engine = make_oboe_engine(48000.0f);

        float warmup[512 * 2];
        for (int i = 0; i < 50; i++) orpheus_engine_process(engine, warmup, 512);

        float buf[1024 * 2];
        std::memset(buf, 0, sizeof(buf));
        orpheus_engine_process(engine, buf, 1024);

        float peak_first = 0.0f, peak_second = 0.0f;
        for (int i = 0; i < 512; i++) {
            float a = std::fabs(buf[i * 2]) + std::fabs(buf[i * 2 + 1]);
            if (a > peak_first) peak_first = a;
        }
        for (int i = 512; i < 1024; i++) {
            float a = std::fabs(buf[i * 2]) + std::fabs(buf[i * 2 + 1]);
            if (a > peak_second) peak_second = a;
        }

        printf("    First half (0-511): peak=%.4f\n", peak_first);
        printf("    Second half (512-1023): peak=%.4f\n", peak_second);

        bool first_has_audio = peak_first > 0.01f;
        bool second_has_audio = peak_second > 0.01f;

        if (first_has_audio && second_has_audio) {
            printf("    PASS: both halves have audio\n");
            pass++;
        } else if (first_has_audio && !second_has_audio) {
            printf("    FAIL: SILENCE GAP at sample 512 (kMaxFrames clamp)\n");
            printf("    This causes crackling on Bluetooth with >512 frame buffers\n");
            fail++;
        } else {
            printf("    FAIL: unexpected (first=%.4f second=%.4f)\n", peak_first, peak_second);
            fail++;
        }
        orpheus_engine_destroy(engine);
    }

    // ── Test 3: 2048-frame buffer (worst-case Bluetooth) ──
    {
        printf("  Test 3: 2048-frame buffer (worst-case Bluetooth)\n");
        OrpheusEngine* engine = make_oboe_engine(48000.0f);

        float warmup[512 * 2];
        for (int i = 0; i < 50; i++) orpheus_engine_process(engine, warmup, 512);

        float buf[2048 * 2];
        std::memset(buf, 0, sizeof(buf));
        orpheus_engine_process(engine, buf, 2048);

        float peaks[4] = {};
        for (int q = 0; q < 4; q++) {
            int start = q * 512;
            for (int i = start; i < start + 512; i++) {
                float a = std::fabs(buf[i * 2]) + std::fabs(buf[i * 2 + 1]);
                if (a > peaks[q]) peaks[q] = a;
            }
            printf("    Quarter %d (%d-%d): peak=%.4f\n", q, start, start + 511, peaks[q]);
        }

        bool all_have_audio = true;
        for (int q = 0; q < 4; q++) {
            if (peaks[q] < 0.01f) all_have_audio = false;
        }

        if (all_have_audio) { printf("    PASS\n"); pass++; }
        else { printf("    FAIL: some quarters are silent\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 4: Very small buffer (48 frames) ──
    {
        printf("  Test 4: Very small buffer (48 frames, 2s)\n");
        OrpheusEngine* engine = make_oboe_engine(48000.0f);

        float buf[48 * 2];
        bool any_nan = false;
        float peak = 0.0f;

        for (int b = 0; b < 2000; b++) {
            orpheus_engine_process(engine, buf, 48);
            for (int i = 0; i < 48 * 2; i++) {
                if (std::isnan(buf[i]) || std::isinf(buf[i])) any_nan = true;
                float a = std::fabs(buf[i]);
                if (a > peak) peak = a;
            }
        }

        printf("    peak=%.4f nan=%s\n", peak, any_nan ? "YES" : "no");
        bool ok = !any_nan && peak <= 1.1f;
        if (ok) { printf("    PASS\n"); pass++; }
        else    { printf("    FAIL\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 5: 44100 Hz sample rate (common Bluetooth) ──
    {
        printf("  Test 5: 44100 Hz sample rate (Bluetooth)\n");
        OrpheusEngine* engine = make_oboe_engine(44100.0f);

        float buf[512 * 2];
        bool any_nan = false;
        float peak = 0.0f;

        for (int b = 0; b < 100; b++) {
            orpheus_engine_process(engine, buf, 512);
            for (int i = 0; i < 512 * 2; i++) {
                if (std::isnan(buf[i]) || std::isinf(buf[i])) any_nan = true;
                float a = std::fabs(buf[i]);
                if (a > peak) peak = a;
            }
        }

        printf("    peak=%.4f nan=%s\n", peak, any_nan ? "YES" : "no");
        bool ok = !any_nan && peak > 0.01f && peak <= 1.1f;
        if (ok) { printf("    PASS\n"); pass++; }
        else    { printf("    FAIL\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 6: Alternating buffer sizes (jitter) ──
    {
        printf("  Test 6: Alternating buffer sizes (Bluetooth jitter)\n");
        OrpheusEngine* engine = make_oboe_engine(48000.0f);

        int sizes[] = {512, 256, 512, 128, 512, 384, 512, 192};
        int num_sizes = 8;
        float buf[512 * 2];
        bool any_nan = false;

        for (int round = 0; round < 50; round++) {
            for (int si = 0; si < num_sizes; si++) {
                orpheus_engine_process(engine, buf, sizes[si]);
                for (int i = 0; i < sizes[si] * 2; i++) {
                    if (std::isnan(buf[i]) || std::isinf(buf[i])) any_nan = true;
                }
            }
        }

        if (!any_nan) { printf("    PASS\n"); pass++; }
        else          { printf("    FAIL: NaN/Inf\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 7: CPU budget measurement ──
    {
        printf("  Test 7: CPU budget measurement\n");
        OrpheusEngine* engine = make_oboe_engine(48000.0f);

        float warmup[512 * 2];
        for (int i = 0; i < 20; i++) orpheus_engine_process(engine, warmup, 512);

        int test_sizes[] = {48, 128, 256, 512};

        for (int si = 0; si < 4; si++) {
            int frames = test_sizes[si];
            float buf[512 * 2];
            double total_us = 0;

            for (int it = 0; it < 200; it++) {
                auto start = std::chrono::steady_clock::now();
                orpheus_engine_process(engine, buf, frames);
                auto end = std::chrono::steady_clock::now();
                total_us += std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
            }

            double avg_us = total_us / 200;
            double budget_us = static_cast<double>(frames) / 48000.0 * 1e6;
            printf("    %3d frames: avg=%.0f us, budget=%.0f us, load=%.1f%%\n",
                   frames, avg_us, budget_us, avg_us / budget_us * 100.0);
        }

        printf("    (informational)\n");
        pass++;
        orpheus_engine_destroy(engine);
    }

    printf("\n  Oboe Buffer: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
