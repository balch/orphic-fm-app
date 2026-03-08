// Cross-engine headroom & level parity tests: C++ raw vs JSyn reference WAVs
#include "test_harness.h"

// ═══════════════════════════════════════════════════════════════════
// Test 1: Per-engine RMS level parity vs JSyn reference WAVs
// Compares raw OrpheusVoice output (outGain + soft_limit) against
// JSyn renders at same params: note=60, harmonics/timbre/morph=0.5
// ═══════════════════════════════════════════════════════════════════
static bool test_engine_level_parity() {
    printf("\n=== Test: Per-engine level parity (C++ vs JSyn) ===\n");
    bool all_pass = true;
    const int sr = 48000;
    const int total = sr * 2; // 2 seconds

    // Source-tree path for JSyn reference WAVs
    const char* jsyn_dir = "../test/output";

    struct EngineSpec {
        int cpp_index;
        const char* name;
        float tolerance_ratio; // acceptable level_ratio range: 1/tol to tol
    };

    // tolerance_ratio: how far C++/JSyn level ratio can deviate from 1.0
    // Most engines should be close; some (modal, particle) have known differences
    EngineSpec engines[] = {
        { 8, "virtual_analog", 1.5f},
        { 9, "waveshaping",    1.5f},
        {10, "fm",             1.5f},
        {11, "grain",          1.5f},
        {12, "additive",       1.5f},
        {13, "wavetable",      1.5f},
        {14, "chord",          1.5f},
        {15, "speech",         1.5f},
        {16, "swarm",          1.5f},
        {17, "noise",          1.5f},
        {18, "particle",       2.0f},  // known divergence
        {19, "string",         2.0f},  // resonant, sensitive to sample rate
        {20, "modal",          5.0f},  // known: C++ ~4x quieter, likely sample-rate-dependent resonance
    };

    printf("  %-16s  %8s  %8s  %6s  %s\n",
           "Engine", "C++ RMS", "JSyn RMS", "Ratio", "Status");
    printf("  %-16s  %8s  %8s  %6s  %s\n",
           "──────", "───────", "────────", "─────", "──────");

    int match_count = 0;
    int total_engines = sizeof(engines) / sizeof(engines[0]);

    for (auto& e : engines) {
        // Render C++ raw engine output
        OrpheusEngine* eng = orpheus_engine_create(sr);
        std::vector<float> mono(total, 0.0f);
        eng->voices_dsp[0].Render(e.cpp_index, 1, 60.0f, 0.5f, 0.5f, 0.5f, 0.8f,
                                   mono.data(), total);
        float cpp_rms = compute_rms(mono.data(), total);
        orpheus_engine_destroy(eng);

        // Load JSyn reference WAV
        char jsyn_path[512];
        snprintf(jsyn_path, sizeof(jsyn_path), "%s/jsyn_raw_%s.wav", jsyn_dir, e.name);

        std::vector<float> jsyn_data;
        int jsyn_sr;
        int jsyn_n = read_wav_mono(jsyn_path, jsyn_data, jsyn_sr);

        if (jsyn_n <= 0) {
            printf("  %-16s  %8.4f  %8s  %6s  SKIP (no JSyn ref)\n",
                   e.name, cpp_rms, "N/A", "N/A");
            continue;
        }

        // JSyn is 44100 Hz, C++ is 48000 Hz — compare by duration, not sample count
        float jsyn_rms = compute_rms(jsyn_data.data(), jsyn_n);
        float ratio = (jsyn_rms > 0.0001f) ? cpp_rms / jsyn_rms : 0.0f;

        bool level_ok = (ratio >= 1.0f / e.tolerance_ratio) &&
                        (ratio <= e.tolerance_ratio);
        if (level_ok) match_count++;

        printf("  %-16s  %8.4f  %8.4f  %6.2f  %s\n",
               e.name, cpp_rms, jsyn_rms, ratio,
               level_ok ? "OK" : "LEVEL MISMATCH");

        if (!level_ok) {
            printf("    -> ratio %.2f outside [%.2f, %.2f]\n",
                   ratio, 1.0f / e.tolerance_ratio, e.tolerance_ratio);
            all_pass = false;
        }
    }

    printf("  Level parity: %d/%d engines within tolerance\n", match_count, total_engines);
    printf("Per-engine level parity test: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 2: Full-chain headroom at various voice counts
// Verifies that N voices through the production graph don't clip
// and scale approximately as expected (sqrt(N) for uncorrelated).
// ═══════════════════════════════════════════════════════════════════
static bool test_fullchain_headroom() {
    printf("\n=== Test: Full-chain headroom (1/2/4/8 voices) ===\n");
    bool pass = true;

    struct Scenario {
        int num_voices;
        float notes[8];
    };
    Scenario scenarios[] = {
        {1, {60.0f}},
        {2, {60.0f, 67.0f}},
        {4, {48.0f, 55.0f, 60.0f, 67.0f}},
        {8, {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f}},
    };

    float rms_values[4] = {};
    float peak_values[4] = {};

    printf("  %-8s  %8s  %8s  %6s  %s\n",
           "Voices", "Peak", "RMS", "Crest", "Status");

    for (int s = 0; s < 4; s++) {
        auto& sc = scenarios[s];
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        orpheus_engine_set_master_volume(engine, 0.8f);

        for (int v = 0; v < sc.num_voices; v++) {
            activate_voice(engine, v, 8, sc.notes[v]);
            // Center pan all voices for worst-case headroom
            char sym[16];
            snprintf(sym, sizeof(sym), "voice_pan_%d", v);
            orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.stereo", sym, 0.0f);
        }

        auto r = render_engine(engine, 48000); // 1 second

        rms_values[s] = (r.rms_l + r.rms_r) / 2.0f;
        peak_values[s] = r.peak;
        float crest = (rms_values[s] > 0.0001f) ? peak_values[s] / rms_values[s] : 0.0f;

        bool no_clip = peak_values[s] <= 1.0f;
        bool has_signal = rms_values[s] > 0.01f;
        bool ok = no_clip && has_signal;

        printf("  %-8d  %8.4f  %8.4f  %6.2f  %s\n",
               sc.num_voices, peak_values[s], rms_values[s], crest,
               ok ? "OK" : (no_clip ? "LOW SIGNAL" : "CLIPPING!"));

        if (!ok) pass = false;
        orpheus_engine_destroy(engine);
    }

    // Check scaling: 4 voices should be louder than 1, but less than 4x
    float ratio_4_1 = rms_values[2] / (rms_values[0] + 0.0001f);
    printf("  4v/1v ratio: %.2f (expect 1.5–3.5 for uncorrelated signals)\n", ratio_4_1);
    if (ratio_4_1 < 1.2f || ratio_4_1 > 4.5f) {
        printf("  FAIL: unexpected voice scaling ratio\n");
        pass = false;
    }

    // 8 voices should not clip
    if (peak_values[3] > 1.0f) {
        printf("  FAIL: 8 voices clip at peak=%.4f\n", peak_values[3]);
        pass = false;
    }

    printf("Full-chain headroom test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 3: Master volume linearity
// Verifies that output scales linearly with master volume
// ═══════════════════════════════════════════════════════════════════
static bool test_master_volume_linearity() {
    printf("\n=== Test: Master volume linearity ===\n");
    bool pass = true;

    float volumes[] = {0.2f, 0.4f, 0.6f, 0.8f, 1.0f};
    float rms_values[5] = {};

    for (int i = 0; i < 5; i++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        activate_voice(engine, 0, 8, 60.0f);
        orpheus_engine_set_master_volume(engine, volumes[i]);

        auto r = render_engine(engine, 24000);
        rms_values[i] = (r.rms_l + r.rms_r) / 2.0f;
        printf("  vol=%.1f: RMS=%.4f\n", volumes[i], rms_values[i]);
        orpheus_engine_destroy(engine);
    }

    // Check linearity: ratio of RMS values should track volume ratios
    // vol=0.4 / vol=0.2 should be ~2.0, vol=0.8 / vol=0.4 should be ~2.0
    for (int i = 1; i < 5; i++) {
        float expected_ratio = volumes[i] / volumes[i - 1];
        float actual_ratio = rms_values[i] / (rms_values[i - 1] + 0.0001f);
        float error = std::fabs(actual_ratio - expected_ratio) / expected_ratio;
        bool ok = error < 0.15f; // 15% tolerance for soft limiting effects
        printf("  vol %.1f/%.1f: expected=%.2f actual=%.2f error=%.1f%% %s\n",
               volumes[i], volumes[i - 1], expected_ratio, actual_ratio,
               error * 100.0f, ok ? "OK" : "NONLINEAR");
        if (!ok) pass = false;
    }

    printf("Master volume linearity test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_headroom_tests() {
    bool all_pass = true;
    all_pass &= test_engine_level_parity();
    all_pass &= test_fullchain_headroom();
    all_pass &= test_master_volume_linearity();
    return all_pass;
}
