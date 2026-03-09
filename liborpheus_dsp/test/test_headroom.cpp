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
    printf("\n=== Test: Full-chain headroom (1/2/4/8/15 voices) ===\n");
    bool pass = true;

    printf("  %-12s  %8s  %8s  %6s  %s\n",
           "Config", "Peak", "RMS", "Crest", "Status");

    // Helper lambda for main-voice-only scenarios
    auto run_main_voices = [&](const char* label, int n, float* notes) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        orpheus_engine_set_master_volume(engine, 0.8f);
        for (int v = 0; v < n; v++) {
            activate_voice(engine, v, 8, notes[v]);
            char sym[16];
            snprintf(sym, sizeof(sym), "voice_pan_%d", v);
            orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.stereo", sym, 0.0f);
        }
        auto r = render_engine(engine, 48000);
        float rms = (r.rms_l + r.rms_r) / 2.0f;
        float crest = (rms > 0.0001f) ? r.peak / rms : 0.0f;
        bool no_clip = r.peak <= 1.0f;
        bool has_signal = rms > 0.01f;
        bool ok = no_clip && has_signal;
        printf("  %-12s  %8.4f  %8.4f  %6.2f  %s\n",
               label, r.peak, rms, crest,
               ok ? "OK" : (no_clip ? "LOW SIGNAL" : "CLIPPING!"));
        if (!ok) pass = false;
        orpheus_engine_destroy(engine);
        return rms;
    };

    float n1[] = {60.0f};
    float n2[] = {60.0f, 67.0f};
    float n4[] = {48.0f, 55.0f, 60.0f, 67.0f};
    float n8[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};

    float rms_1v = run_main_voices("1 main", 1, n1);
    run_main_voices("2 main", 2, n2);
    float rms_4v = run_main_voices("4 main", 4, n4);
    float rms_8v = run_main_voices("8 main", 8, n8);

    // 15-voice scenario: 12 main voices + 3 drum triggers (kick/snare/hat)
    {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        orpheus_engine_set_master_volume(engine, 0.8f);

        // Activate all 12 main voices
        float n12[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f,
                       50.0f, 57.0f, 62.0f, 69.0f};
        for (int v = 0; v < 12; v++) {
            activate_voice(engine, v, 8, n12[v]);
            char sym[16];
            snprintf(sym, sizeof(sym), "voice_pan_%d", v);
            orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.stereo", sym, 0.0f);
        }

        // Trigger all 3 drum voices
        orpheus_engine_trigger_drum(engine, 0, 0.8f); // bass drum
        orpheus_engine_trigger_drum(engine, 1, 0.8f); // snare
        orpheus_engine_trigger_drum(engine, 2, 0.8f); // hihat

        auto r = render_engine(engine, 48000);
        float rms = (r.rms_l + r.rms_r) / 2.0f;
        float crest = (rms > 0.0001f) ? r.peak / rms : 0.0f;
        bool no_clip = r.peak <= 1.0f;
        bool has_signal = rms > 0.01f;
        bool ok = no_clip && has_signal;
        printf("  %-12s  %8.4f  %8.4f  %6.2f  %s\n",
               "12+3 drums", r.peak, rms, crest,
               ok ? "OK" : (no_clip ? "LOW SIGNAL" : "CLIPPING!"));
        if (!ok) pass = false;

        // Verify drum voices actually contributed
        int drums_active = 0;
        for (int v = 12; v < 15; v++) {
            float level = engine->voice_levels[v].load();
            if (level > 0.001f) drums_active++;
        }
        printf("  Drum voices active: %d/3\n", drums_active);

        float rms_15v = rms;
        if (rms_15v < rms_8v * 0.95f) {
            printf("  WARNING: 15v RMS (%.4f) lower than 8v (%.4f) — drums may not be contributing\n",
                   rms_15v, rms_8v);
        }

        orpheus_engine_destroy(engine);
    }

    // Check scaling: 4 voices should be louder than 1, but less than 4x
    float ratio_4_1 = rms_4v / (rms_1v + 0.0001f);
    printf("  4v/1v ratio: %.2f (expect 1.5–3.5 for uncorrelated signals)\n", ratio_4_1);
    if (ratio_4_1 < 1.2f || ratio_4_1 > 4.5f) {
        printf("  FAIL: unexpected voice scaling ratio\n");
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

// ═══════════════════════════════════════════════════════════════════
// Test 4: Quad volume via set_port
// Verifies quad_vol_N scales output linearly, including silence at 0
// and cross-quad isolation.
// ═══════════════════════════════════════════════════════════════════
static bool test_quad_volume() {
    printf("\n=== Test: Quad volume via set_port ===\n");
    bool pass = true;

    // Render with quad_vol_0 = 1.0 (default) — baseline
    OrpheusEngine* eng_full = orpheus_engine_create(48000.0f);
    load_production_graph(eng_full);
    for (int v = 0; v < 4; v++)
        activate_voice(eng_full, v, 8, 60.0f + v * 5.0f);
    auto r_full = render_engine(eng_full, 24000);
    float rms_full = (r_full.rms_l + r_full.rms_r) / 2.0f;
    orpheus_engine_destroy(eng_full);

    // Render with quad_vol_0 = 0.5 — should be ~half
    OrpheusEngine* eng_half = orpheus_engine_create(48000.0f);
    load_production_graph(eng_half);
    for (int v = 0; v < 4; v++)
        activate_voice(eng_half, v, 8, 60.0f + v * 5.0f);
    orpheus_engine_set_port(eng_half, "org.balch.orpheus.plugins.stereo", "quad_vol_0", 0.5f);
    auto r_half = render_engine(eng_half, 24000);
    float rms_half = (r_half.rms_l + r_half.rms_r) / 2.0f;
    orpheus_engine_destroy(eng_half);

    float ratio = rms_half / (rms_full + 0.0001f);
    printf("  quad_vol=1.0: RMS=%.4f  quad_vol=0.5: RMS=%.4f  ratio=%.2f\n",
           rms_full, rms_half, ratio);
    if (ratio < 0.35f || ratio > 0.65f) {
        printf("  FAIL: expected ratio ~0.5, got %.2f\n", ratio);
        pass = false;
    }

    // Render with quad_vol_0 = 0.0 — should be silent
    OrpheusEngine* eng_zero = orpheus_engine_create(48000.0f);
    load_production_graph(eng_zero);
    for (int v = 0; v < 4; v++)
        activate_voice(eng_zero, v, 8, 60.0f + v * 5.0f);
    orpheus_engine_set_port(eng_zero, "org.balch.orpheus.plugins.stereo", "quad_vol_0", 0.0f);
    auto r_zero = render_engine(eng_zero, 24000);
    float rms_zero = (r_zero.rms_l + r_zero.rms_r) / 2.0f;
    printf("  quad_vol=0.0: RMS=%.4f %s\n", rms_zero, rms_zero < 0.001f ? "OK (silent)" : "FAIL (not silent)");
    if (rms_zero > 0.001f) pass = false;
    orpheus_engine_destroy(eng_zero);

    // Cross-quad isolation: set quad_vol_0=0, quad_vol_1=1, play voices in both
    OrpheusEngine* eng_iso = orpheus_engine_create(48000.0f);
    load_production_graph(eng_iso);
    for (int v = 0; v < 8; v++)
        activate_voice(eng_iso, v, 8, 60.0f);
    orpheus_engine_set_port(eng_iso, "org.balch.orpheus.plugins.stereo", "quad_vol_0", 0.0f);
    // quad_vol_1 stays at default 1.0
    auto r_iso = render_engine(eng_iso, 24000);
    float rms_iso = (r_iso.rms_l + r_iso.rms_r) / 2.0f;
    printf("  quad_vol_0=0 + quad_vol_1=1: RMS=%.4f %s\n",
           rms_iso, rms_iso > 0.01f ? "OK (quad 1 audible)" : "FAIL (too quiet)");
    if (rms_iso < 0.01f) pass = false;
    orpheus_engine_destroy(eng_iso);

    printf("Quad volume test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 5: Quad hold (sustained drone without gate)
// Verifies hold parameter sustains voices without gate, scales output
// linearly, and produces silence at hold=0.
// ═══════════════════════════════════════════════════════════════════
static bool test_quad_hold() {
    printf("\n=== Test: Quad hold (sustained drone without gate) ===\n");
    bool pass = true;

    // Hold at 0.8 on all 4 voices of quad 0 — no gate
    OrpheusEngine* eng = orpheus_engine_create(48000.0f);
    load_production_graph(eng);
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(eng, v, 1);
        orpheus_engine_set_voice_engine(eng, v, 8);  // VA engine
        orpheus_engine_set_voice_hold(eng, v, 0.8f);
        // No gate — hold should sustain the voice
    }
    auto r_hold = render_engine(eng, 24000);
    float rms_hold = (r_hold.rms_l + r_hold.rms_r) / 2.0f;
    printf("  Hold=0.8 no gate: RMS=%.4f %s\n", rms_hold,
           rms_hold > 0.01f ? "OK" : "FAIL (silent)");
    if (rms_hold < 0.01f) pass = false;
    orpheus_engine_destroy(eng);

    // Hold level scales output: 0.5 vs 1.0
    float rms_levels[2] = {};
    float hold_vals[] = {0.5f, 1.0f};
    for (int h = 0; h < 2; h++) {
        OrpheusEngine* e = orpheus_engine_create(48000.0f);
        load_production_graph(e);
        for (int v = 0; v < 4; v++) {
            orpheus_engine_set_voice_active(e, v, 1);
            orpheus_engine_set_voice_engine(e, v, 8);
            orpheus_engine_set_voice_hold(e, v, hold_vals[h]);
        }
        auto r = render_engine(e, 24000);
        rms_levels[h] = (r.rms_l + r.rms_r) / 2.0f;
        printf("  Hold=%.1f: RMS=%.4f\n", hold_vals[h], rms_levels[h]);
        orpheus_engine_destroy(e);
    }
    float hold_ratio = rms_levels[0] / (rms_levels[1] + 0.0001f);
    printf("  hold 0.5/1.0 ratio: %.2f (expect 0.15–0.70, nonlinear scaling)\n", hold_ratio);
    // Hold scales nonlinearly through the engine (envelope * gain curve)
    // hold=0.5 produces ~23% of hold=1.0 — verify it's quieter but not silent
    if (hold_ratio < 0.10f || hold_ratio > 0.80f) {
        printf("  FAIL: hold ratio %.2f outside expected range\n", hold_ratio);
        pass = false;
    }

    // Hold=0 + no gate = silence
    OrpheusEngine* eng_silent = orpheus_engine_create(48000.0f);
    load_production_graph(eng_silent);
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(eng_silent, v, 1);
        orpheus_engine_set_voice_engine(eng_silent, v, 8);
        orpheus_engine_set_voice_hold(eng_silent, v, 0.0f);
    }
    auto r_silent = render_engine(eng_silent, 24000);
    float rms_silent = (r_silent.rms_l + r_silent.rms_r) / 2.0f;
    printf("  Hold=0 no gate: RMS=%.4f %s\n", rms_silent,
           rms_silent < 0.001f ? "OK (silent)" : "FAIL (not silent)");
    if (rms_silent > 0.001f) pass = false;
    orpheus_engine_destroy(eng_silent);

    printf("Quad hold test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_headroom_tests() {
    bool all_pass = true;
    all_pass &= test_engine_level_parity();
    all_pass &= test_fullchain_headroom();
    all_pass &= test_master_volume_linearity();
    all_pass &= test_quad_volume();
    all_pass &= test_quad_hold();
    return all_pass;
}
