#include "test_harness.h"
#include "orpheus_engine.h"
#include "orpheus_units.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

// ═══════════════════════════════════════════════════════════════════
// Test 1: All 9 Braids engines produce non-silent, finite, ~zero-DC audio
//         at A4 with default params.
// Engine IDs 100..108:
//   100=TRIPLE_SAW, 101=TRIPLE_SQUARE, 102=TRIPLE_TRIANGLE,
//   103=TRIPLE_SINE, 104=TRIPLE_RING_MOD (chord engines)
//   105=CSAW, 106=TOY, 107=VOWEL_FOF, 108=QUESTION_MARK (character engines)
// ═══════════════════════════════════════════════════════════════════
static bool test_braids_all_engines_produce_sound() {
    printf("\n=== Test: Braids — all 9 engines produce sound ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000);

    constexpr int FRAMES = 48000;  // 1 second
    std::vector<float> out(FRAMES);

    bool all_ok = true;
    for (int id = 100; id <= 108; id++) {
        std::fill(out.begin(), out.end(), 0.0f);
        auto& vp = engine->voice_params[0];
        vp.engine_index.store(id, std::memory_order_relaxed);
        vp.tune.store(69.0f, std::memory_order_relaxed);  // A4
        vp.harmonics.store(0.5f, std::memory_order_relaxed);
        vp.morph.store(0.5f, std::memory_order_relaxed);
        vp.active.store(1, std::memory_order_relaxed);
        vp.gate.store(1, std::memory_order_relaxed);

        // Render in 64-frame blocks (typical host block size)
        constexpr int BLOCK = 64;
        for (int i = 0; i < FRAMES; i += BLOCK) {
            int n = std::min(BLOCK, FRAMES - i);
            auto& vp = engine->voice_params[0];
            unit_process_braids(engine->braids_voices[0],
                                engine->braids_src_phase[0],
                                vp.engine_index.load(),
                                vp.tune.load(),
                                vp.harmonics.load(),
                                vp.timbre.load(),
                                vp.morph.load(),
                                48000.0f, out.data() + i, n);
        }

        // RMS and DC check
        double sum_sq = 0;
        double sum = 0;
        bool has_inf = false;
        for (int i = 0; i < FRAMES; i++) {
            if (!std::isfinite(out[i])) {
                printf("  [FAIL] engine %d: NaN/Inf at sample %d\n", id, i);
                all_ok = false;
                has_inf = true;
                break;
            }
            sum_sq += out[i] * out[i];
            sum += out[i];
        }
        if (has_inf) continue;
        float rms = std::sqrt(sum_sq / FRAMES);
        float dc  = sum / FRAMES;
        if (rms < 1e-4f) {
            printf("  [FAIL] engine %d: silent (rms=%.6f)\n", id, rms);
            all_ok = false;
        } else if (std::fabs(dc) > 0.05f) {
            printf("  [FAIL] engine %d: DC offset %.4f > 0.05\n", id, dc);
            all_ok = false;
        } else {
            printf("  [OK]   engine %d: rms=%.4f dc=%+.4f\n", id, rms, dc);
        }
    }
    orpheus_engine_destroy(engine);

    int pass = all_ok ? 1 : 0;
    int fail = all_ok ? 0 : 1;
    g_suite_passes += pass;
    g_suite_fails  += fail;
    return all_ok;
}

// ═══════════════════════════════════════════════════════════════════
// Test 2: TRIPLE_SAW chord changes spectrum when HARMONICS sweeps.
// Renders TRIPLE_SAW (id 100) at A2 with harmonics=0.0 vs harmonics=1.0
// and asserts at least one candidate frequency differs by >10%.
// ═══════════════════════════════════════════════════════════════════
static bool test_braids_chord_responds_to_harmonics() {
    printf("\n=== Test: Braids TRIPLE_SAW chord responds to HARMONICS ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000);
    constexpr int FRAMES = 48000 / 4;  // 250 ms

    auto render = [&](float harm) {
        std::vector<float> buf(FRAMES);
        auto& vp = engine->voice_params[0];
        vp.engine_index.store(100, std::memory_order_relaxed);  // TRIPLE_SAW
        vp.tune.store(45.0f, std::memory_order_relaxed);  // A2
        vp.harmonics.store(harm, std::memory_order_relaxed);
        vp.morph.store(0.5f, std::memory_order_relaxed);
        vp.active.store(1, std::memory_order_relaxed);
        vp.gate.store(1, std::memory_order_relaxed);
        constexpr int BLOCK = 64;
        for (int i = 0; i < FRAMES; i += BLOCK) {
            int n = std::min(BLOCK, FRAMES - i);
            auto& vp = engine->voice_params[0];
            unit_process_braids(engine->braids_voices[0],
                                engine->braids_src_phase[0],
                                vp.engine_index.load(),
                                vp.tune.load(),
                                vp.harmonics.load(),
                                vp.timbre.load(),
                                vp.morph.load(),
                                48000.0f, buf.data() + i, n);
        }
        return buf;
    };

    auto dft_mag = [&](const std::vector<float>& buf, float f_hz) {
        double re = 0, im = 0;
        const double w = 2.0 * M_PI * f_hz / 48000.0;
        for (int i = 0; i < (int)buf.size(); i++) {
            re += buf[i] * std::cos(w * i);
            im += buf[i] * std::sin(w * i);
        }
        return std::sqrt(re * re + im * im) / buf.size();
    };

    auto buf_lo = render(0.0f);
    auto buf_hi = render(1.0f);

    // Sample candidate chord-interval frequencies above A2 (110 Hz):
    // major third = 138.6 Hz, perfect fifth = 164.8 Hz, octave = 220 Hz.
    const float test_freqs[] = {138.59f, 164.81f, 220.0f};
    bool any_changed = false;
    for (float f : test_freqs) {
        double mag_lo = dft_mag(buf_lo, f);
        double mag_hi = dft_mag(buf_hi, f);
        double diff_ratio = std::fabs(mag_hi - mag_lo) /
                            std::max(std::max(mag_lo, mag_hi), 1e-9);
        printf("  %.2f Hz: lo=%.6f hi=%.6f diff=%.3f\n",
               f, mag_lo, mag_hi, diff_ratio);
        if (diff_ratio > 0.10) any_changed = true;
    }

    orpheus_engine_destroy(engine);

    bool ok = any_changed;
    if (!ok) {
        printf("  [FAIL] no candidate frequency moved by >10%% between harmonics extremes\n");
    } else {
        printf("  [OK]   at least one frequency changed by >10%% with HARMONICS sweep\n");
    }
    g_suite_passes += ok ? 1 : 0;
    g_suite_fails  += ok ? 0 : 1;
    return ok;
}

// ═══════════════════════════════════════════════════════════════════
// Test 3: TRIPLE_SAW at A4 has a spectral peak near 440 Hz (pitch sanity).
// ═══════════════════════════════════════════════════════════════════
static bool test_braids_pitch_sanity() {
    printf("\n=== Test: Braids TRIPLE_SAW pitch sanity (A4 = 440 Hz) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000);
    constexpr int FRAMES = 48000;  // 1 second
    std::vector<float> buf(FRAMES);

    auto& vp = engine->voice_params[0];
    vp.engine_index.store(100, std::memory_order_relaxed);  // TRIPLE_SAW
    vp.tune.store(69.0f, std::memory_order_relaxed);  // A4
    vp.harmonics.store(0.0f, std::memory_order_relaxed);  // unison-ish (smallest interval)
    vp.morph.store(0.0f, std::memory_order_relaxed);  // minimal detune
    vp.active.store(1, std::memory_order_relaxed);
    vp.gate.store(1, std::memory_order_relaxed);

    constexpr int BLOCK = 64;
    for (int i = 0; i < FRAMES; i += BLOCK) {
        int n = std::min(BLOCK, FRAMES - i);
        auto& vp2 = engine->voice_params[0];
        unit_process_braids(engine->braids_voices[0],
                            engine->braids_src_phase[0],
                            vp2.engine_index.load(),
                            vp2.tune.load(),
                            vp2.harmonics.load(),
                            vp2.timbre.load(),
                            vp2.morph.load(),
                            48000.0f, buf.data() + i, n);
    }

    auto dft_mag = [&](float f_hz) {
        double re = 0, im = 0;
        const double w = 2.0 * M_PI * f_hz / 48000.0;
        for (int i = 0; i < FRAMES; i++) {
            re += buf[i] * std::cos(w * i);
            im += buf[i] * std::sin(w * i);
        }
        return std::sqrt(re * re + im * im) / FRAMES;
    };

    // Search 430..450 Hz at 0.5 Hz resolution for the peak.
    float best_freq = 0.0f;
    double best_mag = 0.0;
    for (float f = 430.0f; f <= 450.0f; f += 0.5f) {
        double m = dft_mag(f);
        if (m > best_mag) { best_mag = m; best_freq = f; }
    }
    printf("  peak frequency: %.2f Hz (mag=%.6f)\n", best_freq, best_mag);
    orpheus_engine_destroy(engine);

    bool ok = std::fabs(best_freq - 440.0f) <= 2.0f;
    if (!ok) {
        printf("  [FAIL] peak %.2f Hz is more than 2 Hz off A4 (440 Hz)\n", best_freq);
    } else {
        printf("  [OK]   peak %.2f Hz is within 2 Hz of A4 (440 Hz)\n", best_freq);
    }
    g_suite_passes += ok ? 1 : 0;
    g_suite_fails  += ok ? 0 : 1;
    return ok;
}

// ═══════════════════════════════════════════════════════════════════
// Test 4: At 44.1 kHz host rate, the resampler residue (`src_phase`)
//         keeps phase continuous across host block boundaries.
//         Renders TRIPLE_SINE at A4 in 64-frame blocks and asserts that
//         no two adjacent block boundaries produce a sudden jump that
//         a phase-continuous oscillator wouldn't produce.
// ═══════════════════════════════════════════════════════════════════
static bool test_braids_44k_block_continuity() {
    printf("\n=== Test: Braids 44.1 kHz block-boundary continuity ===\n");
    OrpheusEngine* engine = orpheus_engine_create(44100);

    constexpr int BLOCK = 64;
    constexpr int N_BLOCKS = 200;
    constexpr int FRAMES = BLOCK * N_BLOCKS;
    std::vector<float> buf(FRAMES);

    auto& vp = engine->voice_params[0];
    vp.engine_index.store(103, std::memory_order_relaxed);  // TRIPLE_SINE — smooth
    vp.tune.store(69.0f, std::memory_order_relaxed);  // A4
    vp.harmonics.store(0.0f, std::memory_order_relaxed);
    vp.morph.store(0.0f, std::memory_order_relaxed);  // minimal detune
    vp.active.store(1, std::memory_order_relaxed);
    vp.gate.store(1, std::memory_order_relaxed);

    for (int b = 0; b < N_BLOCKS; b++) {
        unit_process_braids(engine->braids_voices[0],
                            engine->braids_src_phase[0],
                            vp.engine_index.load(),
                            vp.tune.load(),
                            vp.harmonics.load(),
                            vp.timbre.load(),
                            vp.morph.load(),
                            44100.0f, buf.data() + b * BLOCK, BLOCK);
    }

    // Compute the maximum sample-to-sample step inside blocks vs at block boundaries.
    // For a phase-continuous resampler the two should be in the same ballpark; a
    // discontinuity at every boundary would push the boundary-step well above the
    // intra-block max.
    float max_step_intra = 0.0f;
    float max_step_boundary = 0.0f;
    for (int b = 0; b < N_BLOCKS; b++) {
        for (int i = 1; i < BLOCK; i++) {
            float step = std::fabs(buf[b * BLOCK + i] - buf[b * BLOCK + i - 1]);
            if (step > max_step_intra) max_step_intra = step;
        }
        if (b > 0) {
            float step = std::fabs(buf[b * BLOCK] - buf[b * BLOCK - 1]);
            if (step > max_step_boundary) max_step_boundary = step;
        }
    }
    printf("  max intra-block step:    %.5f\n", max_step_intra);
    printf("  max boundary step:       %.5f\n", max_step_boundary);

    // Allow 2x tolerance — boundary step should not be wildly larger than intra-block.
    bool ok = max_step_boundary <= max_step_intra * 2.0f;
    if (!ok) {
        printf("  [FAIL] boundary step %.5f is more than 2x intra-block max %.5f\n",
               max_step_boundary, max_step_intra);
    } else {
        printf("  [OK]   boundary step is within 2x intra-block max\n");
    }

    orpheus_engine_destroy(engine);
    g_suite_passes += ok ? 1 : 0;
    g_suite_fails  += ok ? 0 : 1;
    return ok;
}

bool run_braids_tests() {
    bool ok = true;
    ok &= test_braids_all_engines_produce_sound();
    ok &= test_braids_chord_responds_to_harmonics();
    ok &= test_braids_pitch_sanity();
    ok &= test_braids_44k_block_continuity();
    return ok;
}
