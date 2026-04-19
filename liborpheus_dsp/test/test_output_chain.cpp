// Output chain tests: volume, distortion, pan, and gain staging through orpheus_engine_process
#include "test_harness.h"

// ═══════════════════════════════════════════════════════════════════
// Test 1: Master volume scales output proportionally
// ═══════════════════════════════════════════════════════════════════
static bool test_master_volume_scaling() {
    printf("\n=== Test: Master volume scaling ===\n");
    bool pass = true;

    float volumes[] = {0.2f, 0.4f, 0.6f, 0.8f, 1.0f};
    float rms_values[5];

    for (int v = 0; v < 5; v++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        activate_voice(engine, 0, 8, 60.0f);
        orpheus_engine_set_master_volume(engine, volumes[v]);
        auto r = render_engine(engine, 24000);
        rms_values[v] = (r.rms_l + r.rms_r) / 2.0f;
        printf("  vol=%.1f: RMS=%.4f\n", volumes[v], rms_values[v]);
        orpheus_engine_destroy(engine);
    }

    for (int v = 0; v < 4; v++) {
        if (rms_values[v] >= rms_values[v + 1]) {
            printf("  FAIL: vol=%.1f (%.4f) should be quieter than vol=%.1f (%.4f)\n",
                   volumes[v], rms_values[v], volumes[v + 1], rms_values[v + 1]);
            pass = false;
        }
    }

    float ratio = rms_values[4] / rms_values[0];
    printf("  vol ratio (1.0/0.2): %.2f (expected ~5.0 for linear)\n", ratio);
    if (ratio < 2.0f) {
        printf("  FAIL: volume ratio %.2f is severely compressed (expected > 2.0)\n", ratio);
        pass = false;
    }

    printf("Master volume scaling test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 2: Output should not be crushed below 1.0 by excessive saturation
// ═══════════════════════════════════════════════════════════════════
static bool test_output_headroom() {
    printf("\n=== Test: Output headroom (saturation check) ===\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    load_production_graph(engine);
    activate_voice(engine, 0, 8, 60.0f);
    orpheus_engine_set_master_volume(engine, 0.8f);
    auto r = render_engine(engine, 24000);
    orpheus_engine_destroy(engine);

    float rms = (r.rms_l + r.rms_r) / 2.0f;
    float crest = r.peak / rms;
    printf("  Clean (vol=0.8): peak=%.4f RMS=%.4f crest=%.2f\n", r.peak, rms, crest);

    if (crest < 1.2f) {
        printf("  FAIL: crest factor %.2f indicates over-compression (expected > 1.2)\n", crest);
        pass = false;
    }
    if (r.peak < 0.01f) {
        printf("  FAIL: clean signal peak %.4f is nearly silent\n", r.peak);
        pass = false;
    }

    printf("Output headroom test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 3: Drive distortion adds harmonics and saturation proportionally
// ═══════════════════════════════════════════════════════════════════
static bool test_drive_distortion() {
    printf("\n=== Test: Drive distortion ===\n");
    bool pass = true;

    auto render_with_drive = [](float drive, float drive_mix) -> RenderResult {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        activate_voice(engine, 0, 8, 60.0f);
        orpheus_engine_set_master_volume(engine, 0.8f);
        engine->drive_amount.store(1.0f + drive * 14.0f);
        engine->drive_mix.store(drive_mix);
        auto r = render_engine(engine, 24000);
        orpheus_engine_destroy(engine);
        return r;
    };

    auto r_clean = render_with_drive(0.0f, 0.0f);
    auto r_light = render_with_drive(0.3f, 0.5f);
    auto r_heavy = render_with_drive(1.0f, 1.0f);
    auto r_bypass = render_with_drive(1.0f, 0.0f);

    float rms_clean = (r_clean.rms_l + r_clean.rms_r) / 2.0f;
    float rms_light = (r_light.rms_l + r_light.rms_r) / 2.0f;
    float rms_heavy = (r_heavy.rms_l + r_heavy.rms_r) / 2.0f;
    float rms_bypass = (r_bypass.rms_l + r_bypass.rms_r) / 2.0f;

    float crest_clean = r_clean.peak / rms_clean;
    float crest_heavy = r_heavy.peak / rms_heavy;

    printf("  Clean:       peak=%.4f RMS=%.4f crest=%.2f\n", r_clean.peak, rms_clean, crest_clean);
    printf("  Light drive: peak=%.4f RMS=%.4f crest=%.2f\n", r_light.peak, rms_light, r_light.peak / rms_light);
    printf("  Heavy drive: peak=%.4f RMS=%.4f crest=%.2f\n", r_heavy.peak, rms_heavy, crest_heavy);

    if (crest_heavy >= crest_clean && rms_heavy > 0.001f) {
        printf("  FAIL: heavy drive crest (%.2f) should be lower than clean (%.2f)\n", crest_heavy, crest_clean);
        pass = false;
    }

    float diff_ratio = std::fabs(rms_bypass - rms_clean) / (rms_clean + 0.0001f);
    printf("  Drive bypass (mix=0): RMS=%.4f diff_from_clean=%.1f%%\n", rms_bypass, diff_ratio * 100.0f);
    if (diff_ratio > 0.05f) {
        printf("  FAIL: drive with mix=0 should match clean (diff=%.1f%%)\n", diff_ratio * 100.0f);
        pass = false;
    }

    printf("Drive distortion test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 4: Master pan moves signal between L and R channels
// ═══════════════════════════════════════════════════════════════════
static bool test_master_pan() {
    printf("\n=== Test: Master pan (constant-power) ===\n");
    bool pass = true;

    auto render_with_pan = [](float pan) -> RenderResult {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        activate_voice(engine, 0, 8, 60.0f);
        orpheus_engine_set_master_volume(engine, 0.8f);
        engine->master_pan.store(pan);
        auto r = render_engine(engine, 24000);
        orpheus_engine_destroy(engine);
        return r;
    };

    float pan_values[] = {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f};
    const char* labels[] = {"hard L", "half L", "center", "half R", "hard R"};

    for (int p = 0; p < 5; p++) {
        auto r = render_with_pan(pan_values[p]);
        float balance = (r.rms_l + r.rms_r > 0.0001f) ? r.rms_r / (r.rms_l + r.rms_r) : 0.5f;
        printf("  pan=%5.1f (%s): L=%.4f R=%.4f balance=%.3f\n",
               pan_values[p], labels[p], r.rms_l, r.rms_r, balance);

        if (p == 0 && r.rms_r > r.rms_l * 0.1f) {
            printf("  FAIL: hard-left pan but R (%.4f) is not silent vs L (%.4f)\n", r.rms_r, r.rms_l);
            pass = false;
        }
        if (p == 4 && r.rms_l > r.rms_r * 0.1f) {
            printf("  FAIL: hard-right pan but L (%.4f) is not silent vs R (%.4f)\n", r.rms_l, r.rms_r);
            pass = false;
        }
        if (p == 2 && std::fabs(r.rms_l - r.rms_r) > (r.rms_l + r.rms_r) * 0.05f) {
            printf("  FAIL: center pan but L (%.4f) != R (%.4f)\n", r.rms_l, r.rms_r);
            pass = false;
        }
    }

    // Constant-power check
    auto r_center = render_with_pan(0.0f);
    auto r_left = render_with_pan(-1.0f);
    float power_center = std::sqrt(r_center.rms_l * r_center.rms_l + r_center.rms_r * r_center.rms_r);
    float power_left = std::sqrt(r_left.rms_l * r_left.rms_l + r_left.rms_r * r_left.rms_r);
    float power_ratio = power_center / (power_left + 0.0001f);
    printf("  Constant-power: center=%.4f hard_left=%.4f ratio=%.3f (expect ~1.0)\n",
           power_center, power_left, power_ratio);
    if (std::fabs(power_ratio - 1.0f) > 0.15f) {
        printf("  FAIL: constant-power violation — ratio %.3f\n", power_ratio);
        pass = false;
    }

    printf("Master pan test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 5: Per-voice pan positions voices in stereo field
// ═══════════════════════════════════════════════════════════════════
static bool test_voice_pan() {
    printf("\n=== Test: Per-voice pan ===\n");
    bool pass = true;

    auto render_with_voice_pan = [](float voice_pan) -> RenderResult {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        activate_voice(engine, 0, 8, 60.0f);
        orpheus_engine_set_master_volume(engine, 0.8f);
        char sym[16];
        snprintf(sym, sizeof(sym), "voice_pan_%d", 0);
        orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.stereo", sym, voice_pan);
        auto r = render_engine(engine, 24000, 20);
        orpheus_engine_destroy(engine);
        return r;
    };

    auto r_left = render_with_voice_pan(-0.7f);
    printf("  voice_pan=-0.7: L=%.4f R=%.4f\n", r_left.rms_l, r_left.rms_r);
    if (r_left.rms_r >= r_left.rms_l) {
        printf("  FAIL: left-panned voice has R (%.4f) >= L (%.4f)\n", r_left.rms_r, r_left.rms_l);
        pass = false;
    }

    auto r_right = render_with_voice_pan(0.7f);
    printf("  voice_pan=+0.7: L=%.4f R=%.4f\n", r_right.rms_l, r_right.rms_r);
    if (r_right.rms_l >= r_right.rms_r) {
        printf("  FAIL: right-panned voice has L (%.4f) >= R (%.4f)\n", r_right.rms_l, r_right.rms_r);
        pass = false;
    }

    auto r_center = render_with_voice_pan(0.0f);
    printf("  voice_pan=0.0: L=%.4f R=%.4f\n", r_center.rms_l, r_center.rms_r);
    if (std::fabs(r_center.rms_l - r_center.rms_r) > (r_center.rms_l + r_center.rms_r) * 0.05f) {
        printf("  FAIL: center voice but L (%.4f) != R (%.4f)\n", r_center.rms_l, r_center.rms_r);
        pass = false;
    }

    printf("Per-voice pan test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 6: Multi-voice gain staging — 4 voices shouldn't clip/compress badly
// ═══════════════════════════════════════════════════════════════════
static bool test_multi_voice_gain_staging() {
    printf("\n=== Test: Multi-voice gain staging ===\n");
    bool pass = true;

    // 1 voice reference
    OrpheusEngine* eng1 = orpheus_engine_create(48000.0f);
    load_production_graph(eng1);
    activate_voice(eng1, 0, 8, 60.0f);
    orpheus_engine_set_master_volume(eng1, 0.8f);
    auto r_1v = render_engine(eng1, 24000);
    orpheus_engine_destroy(eng1);

    // 4 voices
    OrpheusEngine* eng4 = orpheus_engine_create(48000.0f);
    load_production_graph(eng4);
    float notes[] = {48.0f, 55.0f, 60.0f, 67.0f};
    for (int v = 0; v < 4; v++) {
        activate_voice(eng4, v, 8, notes[v]);
        char sym[16];
        snprintf(sym, sizeof(sym), "voice_pan_%d", v);
        orpheus_engine_set_port(eng4, "org.balch.orpheus.plugins.stereo", sym, 0.0f);
    }
    orpheus_engine_set_master_volume(eng4, 0.8f);
    auto r_4v = render_engine(eng4, 24000);
    orpheus_engine_destroy(eng4);

    float rms_1v = (r_1v.rms_l + r_1v.rms_r) / 2.0f;
    float rms_4v = (r_4v.rms_l + r_4v.rms_r) / 2.0f;
    float level_ratio = rms_4v / (rms_1v + 0.0001f);

    printf("  1 voice:  peak=%.4f RMS=%.4f\n", r_1v.peak, rms_1v);
    printf("  4 voices: peak=%.4f RMS=%.4f\n", r_4v.peak, rms_4v);
    printf("  Level ratio (4v/1v): %.2f\n", level_ratio);

    if (level_ratio < 1.5f) {
        printf("  FAIL: 4 voices barely louder than 1 (ratio=%.2f, expected > 1.5)\n", level_ratio);
        pass = false;
    }
    if (r_4v.peak < 0.01f) {
        printf("  FAIL: 4-voice peak %.4f is too quiet\n", r_4v.peak);
        pass = false;
    }

    printf("Multi-voice gain staging test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 7: Gain staging report (diagnostic)
// ═══════════════════════════════════════════════════════════════════
static bool test_gain_staging_report() {
    printf("\n=== Test: Gain staging report (diagnostic) ===\n");

    struct Scenario {
        const char* name;
        float volume;
        float drive;
        float drive_mix;
        int num_voices;
    };

    Scenario scenarios[] = {
        {"1v clean quiet",    0.4f, 0.0f, 0.0f, 1},
        {"1v clean default",  0.7f, 0.0f, 0.0f, 1},
        {"1v clean full",     1.0f, 0.0f, 0.0f, 1},
        {"1v light drive",    0.7f, 0.3f, 0.5f, 1},
        {"1v heavy drive",    0.7f, 1.0f, 1.0f, 1},
        {"4v clean default",  0.7f, 0.0f, 0.0f, 4},
        {"4v light drive",    0.7f, 0.3f, 0.5f, 4},
        {"8v clean default",  0.7f, 0.0f, 0.0f, 8},
    };

    printf("  %-20s  %8s %8s %8s\n", "Scenario", "Peak", "RMS", "Crest");
    printf("  %-20s  %8s %8s %8s\n", "--------", "----", "---", "-----");

    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};

    for (auto& sc : scenarios) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        load_production_graph(engine);
        for (int v = 0; v < sc.num_voices; v++)
            activate_voice(engine, v, 8, notes[v]);
        orpheus_engine_set_master_volume(engine, sc.volume);
        engine->drive_amount.store(1.0f + sc.drive * 14.0f);
        engine->drive_mix.store(sc.drive_mix);

        auto r = render_engine(engine, 24000);
        orpheus_engine_destroy(engine);

        float rms = (r.rms_l + r.rms_r) / 2.0f;
        float crest = (rms > 0.0001f) ? r.peak / rms : 0.0f;
        printf("  %-20s  %8.4f %8.4f %8.2f\n", sc.name, r.peak, rms, crest);
    }

    printf("Gain staging report: PASS (diagnostic)\n");
    return true;
}

bool run_output_chain_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_master_volume_scaling());
    tally(test_output_headroom());
    tally(test_drive_distortion());
    tally(test_master_pan());
    tally(test_voice_pan());
    tally(test_multi_voice_gain_staging());
    tally(test_gain_staging_report());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
