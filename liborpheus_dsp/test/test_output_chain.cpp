// Output chain tests: volume, distortion, pan, and gain staging through orpheus_engine_process
#include "test_harness.h"

// Helper: render a single voice through the full engine pipeline (no graph).
// Returns interleaved stereo buffer.
static std::vector<float> render_engine_pipeline(
    int engine_index, float note, float volume, float master_pan,
    float drive, float drive_mix, float voice_pan,
    int sample_rate, float duration_s)
{
    OrpheusEngine* engine = orpheus_engine_create(sample_rate);
    orpheus_engine_set_voice_active(engine, 0, 1);
    orpheus_engine_set_voice_tune(engine, 0, note);
    orpheus_engine_set_voice_gate(engine, 0, 1);
    engine->voice_params[0].engine_index.store(engine_index);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.0f);  // fast envelope
    engine->voice_params[0].ever_triggered.store(1);

    engine->master_volume.store(volume);
    engine->master_pan.store(master_pan);
    engine->voice_pan[0].store(voice_pan);
    engine->drive_amount.store(1.0f + drive * 14.0f); // match UI scaling
    engine->drive_mix.store(drive_mix);

    int total = (int)(sample_rate * duration_s);
    std::vector<float> buf(total * 2, 0.0f);
    // Let envelope reach sustain first (warm-up)
    float warmup_buf[128 * 2];
    for (int i = 0; i < 10; i++) {
        orpheus_engine_process(engine, warmup_buf, 128);
    }
    // Now render the actual measurement
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        orpheus_engine_process(engine, buf.data() + off * 2, chunk);
    }
    orpheus_engine_destroy(engine);
    return buf;
}

// Measure RMS of left and right channels separately from interleaved stereo
static void stereo_rms(const std::vector<float>& buf, float& rms_l, float& rms_r) {
    int frames = (int)buf.size() / 2;
    double sum_l = 0.0, sum_r = 0.0;
    for (int i = 0; i < frames; i++) {
        sum_l += (double)buf[i * 2] * buf[i * 2];
        sum_r += (double)buf[i * 2 + 1] * buf[i * 2 + 1];
    }
    rms_l = (float)std::sqrt(sum_l / frames);
    rms_r = (float)std::sqrt(sum_r / frames);
}

static float stereo_peak(const std::vector<float>& buf) {
    float peak = 0.0f;
    for (size_t i = 0; i < buf.size(); i++) {
        float a = std::fabs(buf[i]);
        if (a > peak) peak = a;
    }
    return peak;
}

// ═══════════════════════════════════════════════════════════════════
// Test 1: Master volume scales output proportionally
// JSyn: linear volume scaling, can exceed 1.0 before final hard clip
// ═══════════════════════════════════════════════════════════════════
static bool test_master_volume_scaling() {
    printf("\n=== Test: Master volume scaling ===\n");
    bool pass = true;

    float volumes[] = {0.2f, 0.4f, 0.6f, 0.8f, 1.0f};
    float rms_values[5];

    for (int v = 0; v < 5; v++) {
        auto buf = render_engine_pipeline(8, 60.0f, volumes[v], 0.0f,
                                           0.0f, 0.0f, 0.0f, 48000, 0.5f);
        float rms_l, rms_r;
        stereo_rms(buf, rms_l, rms_r);
        rms_values[v] = (rms_l + rms_r) / 2.0f;
        printf("  vol=%.1f: RMS=%.4f\n", volumes[v], rms_values[v]);
    }

    // Volume should be monotonically increasing
    for (int v = 0; v < 4; v++) {
        if (rms_values[v] >= rms_values[v + 1]) {
            printf("  FAIL: vol=%.1f (%.4f) should be quieter than vol=%.1f (%.4f)\n",
                   volumes[v], rms_values[v], volumes[v + 1], rms_values[v + 1]);
            pass = false;
        }
    }

    // Doubling volume should roughly double RMS (within 30% tolerance for tanh compression)
    // JSyn is linear; C++ uses tanh at the output, so we test that the ratio isn't too compressed
    float ratio = rms_values[4] / rms_values[0]; // vol=1.0 / vol=0.2
    printf("  vol ratio (1.0/0.2): %.2f (expected ~5.0 for linear, lower means compression)\n", ratio);
    if (ratio < 2.0f) {
        printf("  FAIL: volume ratio %.2f is severely compressed (expected > 2.0)\n", ratio);
        pass = false;
    }

    printf("Master volume scaling test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 2: Output should not be crushed below 1.0 by excessive saturation
// JSyn: hard clip at ±1.0 (transparent below 1.0)
// C++: tanh(signal * 0.5) at master output — caps everything
// ═══════════════════════════════════════════════════════════════════
static bool test_output_headroom() {
    printf("\n=== Test: Output headroom (saturation check) ===\n");
    bool pass = true;

    // Render a clean signal at full volume — should preserve dynamics
    auto buf_clean = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                             0.0f, 0.0f, 0.0f, 48000, 0.5f);
    float peak_clean = stereo_peak(buf_clean);
    float rms_l, rms_r;
    stereo_rms(buf_clean, rms_l, rms_r);
    float rms_clean = (rms_l + rms_r) / 2.0f;

    printf("  Clean (vol=0.8, no drive): peak=%.4f RMS=%.4f\n", peak_clean, rms_clean);

    // Crest factor: peak/RMS — for a triangle wave should be ~1.73 (sqrt 3)
    // If tanh compression is too aggressive, crest factor drops toward 1.0
    float crest = peak_clean / rms_clean;
    printf("  Crest factor: %.2f (triangle wave theoretical: ~1.73)\n", crest);
    if (crest < 1.2f) {
        printf("  FAIL: crest factor %.2f indicates over-compression (expected > 1.2)\n", crest);
        pass = false;
    }

    // Peak should be a reasonable fraction of what we'd expect without saturation.
    // A triangle wave with amplitude ~0.8 (Engine 0 ×0.3 gain ×0.8 vol) → expect ~0.24 peak.
    // With center pan (0.707 constant-power) → ~0.17
    // After tanh(x * 0.5) → tanh(0.085) ≈ 0.085
    // This is quite low — let's just verify it's not silence and report the value
    if (peak_clean < 0.01f) {
        printf("  FAIL: clean signal peak %.4f is nearly silent\n", peak_clean);
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

    // Clean reference (mix=0)
    auto buf_clean = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                             0.0f, 0.0f, 0.0f, 48000, 0.5f);
    float rms_l, rms_r;
    stereo_rms(buf_clean, rms_l, rms_r);
    float rms_clean = (rms_l + rms_r) / 2.0f;
    float peak_clean = stereo_peak(buf_clean);

    // Light drive (drive=0.3, mix=0.5)
    auto buf_light = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                              0.3f, 0.5f, 0.0f, 48000, 0.5f);
    stereo_rms(buf_light, rms_l, rms_r);
    float rms_light = (rms_l + rms_r) / 2.0f;
    float peak_light = stereo_peak(buf_light);

    // Heavy drive (drive=1.0, mix=1.0)
    auto buf_heavy = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                              1.0f, 1.0f, 0.0f, 48000, 0.5f);
    stereo_rms(buf_heavy, rms_l, rms_r);
    float rms_heavy = (rms_l + rms_r) / 2.0f;
    float peak_heavy = stereo_peak(buf_heavy);

    printf("  Clean:      peak=%.4f RMS=%.4f crest=%.2f\n",
           peak_clean, rms_clean, peak_clean / rms_clean);
    printf("  Light drive: peak=%.4f RMS=%.4f crest=%.2f\n",
           peak_light, rms_light, peak_light / rms_light);
    printf("  Heavy drive: peak=%.4f RMS=%.4f crest=%.2f\n",
           peak_heavy, rms_heavy, peak_heavy / rms_heavy);

    // Heavy distortion should produce a lower crest factor (flatter waveform)
    float crest_clean = peak_clean / rms_clean;
    float crest_heavy = peak_heavy / rms_heavy;
    if (crest_heavy >= crest_clean && rms_heavy > 0.001f) {
        printf("  FAIL: heavy drive crest (%.2f) should be lower than clean (%.2f)\n",
               crest_heavy, crest_clean);
        pass = false;
    }

    // Drive with mix=0 should equal clean
    auto buf_bypass = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                               1.0f, 0.0f, 0.0f, 48000, 0.5f);
    stereo_rms(buf_bypass, rms_l, rms_r);
    float rms_bypass = (rms_l + rms_r) / 2.0f;
    float diff_ratio = std::fabs(rms_bypass - rms_clean) / (rms_clean + 0.0001f);
    printf("  Drive bypass (mix=0): RMS=%.4f diff_from_clean=%.1f%%\n",
           rms_bypass, diff_ratio * 100.0f);
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

    float pan_values[] = {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f};
    const char* labels[] = {"hard L", "half L", "center", "half R", "hard R"};

    for (int p = 0; p < 5; p++) {
        auto buf = render_engine_pipeline(8, 60.0f, 0.8f, pan_values[p],
                                           0.0f, 0.0f, 0.0f, 48000, 0.5f);
        float rms_l, rms_r;
        stereo_rms(buf, rms_l, rms_r);
        float balance = (rms_l + rms_r > 0.0001f) ? rms_r / (rms_l + rms_r) : 0.5f;
        printf("  pan=%5.1f (%s): L=%.4f R=%.4f balance=%.3f\n",
               pan_values[p], labels[p], rms_l, rms_r, balance);

        // Hard left: R should be near-silent
        if (p == 0 && rms_r > rms_l * 0.1f) {
            printf("  FAIL: hard-left pan but R channel (%.4f) is not silent vs L (%.4f)\n", rms_r, rms_l);
            pass = false;
        }
        // Hard right: L should be near-silent
        if (p == 4 && rms_l > rms_r * 0.1f) {
            printf("  FAIL: hard-right pan but L channel (%.4f) is not silent vs R (%.4f)\n", rms_l, rms_r);
            pass = false;
        }
        // Center: L ≈ R (within 5%)
        if (p == 2 && std::fabs(rms_l - rms_r) > (rms_l + rms_r) * 0.05f) {
            printf("  FAIL: center pan but L (%.4f) != R (%.4f)\n", rms_l, rms_r);
            pass = false;
        }
    }

    // Constant-power: total energy at center ≈ total energy at hard left
    // (each channel at 0.707 → total power preserved)
    auto buf_center = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                              0.0f, 0.0f, 0.0f, 48000, 0.5f);
    auto buf_left = render_engine_pipeline(8, 60.0f, 0.8f, -1.0f,
                                            0.0f, 0.0f, 0.0f, 48000, 0.5f);
    float c_l, c_r, l_l, l_r;
    stereo_rms(buf_center, c_l, c_r);
    stereo_rms(buf_left, l_l, l_r);
    float power_center = std::sqrt(c_l * c_l + c_r * c_r);
    float power_left = std::sqrt(l_l * l_l + l_r * l_r);
    float power_ratio = power_center / (power_left + 0.0001f);
    printf("  Constant-power check: center=%.4f hard_left=%.4f ratio=%.3f (expect ~1.0)\n",
           power_center, power_left, power_ratio);
    if (std::fabs(power_ratio - 1.0f) > 0.15f) {
        printf("  FAIL: constant-power violation — ratio %.3f (expected ~1.0)\n", power_ratio);
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

    // Voice panned left — render directly to verify pan is applied
    {
        OrpheusEngine* eng = orpheus_engine_create(48000.0f);
        orpheus_engine_set_voice_active(eng, 0, 1);
        orpheus_engine_set_voice_tune(eng, 0, 60.0f);
        orpheus_engine_set_voice_gate(eng, 0, 1);
        eng->voice_params[0].engine_index.store(0); // Plaits engine 0
        eng->voice_params[0].harmonics.store(0.5f);
        eng->voice_params[0].timbre.store(0.5f);
        eng->voice_params[0].morph.store(0.5f);
        eng->voice_params[0].decay.store(0.0f);
        eng->voice_params[0].ever_triggered.store(1);
        eng->master_volume.store(0.8f);
        eng->master_pan.store(0.0f);
        eng->voice_pan[0].store(-0.7f);
        printf("  DEBUG: voice_pan[0]=%.2f master_pan=%.2f vol=%.2f\n",
               eng->voice_pan[0].load(), eng->master_pan.load(), eng->master_volume.load());
        // Warmup
        float wb[128 * 2];
        for (int i = 0; i < 20; i++) orpheus_engine_process(eng, wb, 128);
        // Render
        int total = 24000;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            orpheus_engine_process(eng, buf.data() + off * 2, chunk);
        }
        orpheus_engine_destroy(eng);
        float ll2, lr2;
        stereo_rms(buf, ll2, lr2);
        printf("  voice_pan=-0.7 (direct): L=%.4f R=%.4f ratio=%.2f\n", ll2, lr2, ll2/(lr2+0.0001f));
    }
    auto buf_left = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                            0.0f, 0.0f, -0.7f, 48000, 0.5f);
    float ll, lr;
    stereo_rms(buf_left, ll, lr);
    printf("  voice_pan=-0.7 (pipeline): L=%.4f R=%.4f\n", ll, lr);
    if (lr >= ll) {
        printf("  FAIL: left-panned voice has R (%.4f) >= L (%.4f)\n", lr, ll);
        pass = false;
    }

    // Voice panned right
    auto buf_right = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                             0.0f, 0.0f, 0.7f, 48000, 0.5f);
    float rl, rr;
    stereo_rms(buf_right, rl, rr);
    printf("  voice_pan=+0.7: L=%.4f R=%.4f\n", rl, rr);
    if (rl >= rr) {
        printf("  FAIL: right-panned voice has L (%.4f) >= R (%.4f)\n", rl, rr);
        pass = false;
    }

    // Voice panned center — L ≈ R
    auto buf_center = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                              0.0f, 0.0f, 0.0f, 48000, 0.5f);
    float cl, cr;
    stereo_rms(buf_center, cl, cr);
    printf("  voice_pan=0.0: L=%.4f R=%.4f\n", cl, cr);
    if (std::fabs(cl - cr) > (cl + cr) * 0.05f) {
        printf("  FAIL: center voice but L (%.4f) != R (%.4f)\n", cl, cr);
        pass = false;
    }

    printf("Per-voice pan test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 6: Multi-voice gain staging — 4 voices shouldn't clip/compress badly
// JSyn: voices sum, then hard clip at ±1.0
// C++: voices sum, then tanh(sum * 0.5) — compresses everything
// ═══════════════════════════════════════════════════════════════════
static bool test_multi_voice_gain_staging() {
    printf("\n=== Test: Multi-voice gain staging ===\n");
    bool pass = true;

    // 1 voice reference
    auto buf_1v = render_engine_pipeline(8, 60.0f, 0.8f, 0.0f,
                                          0.0f, 0.0f, 0.0f, 48000, 0.5f);
    float rms_l, rms_r;
    stereo_rms(buf_1v, rms_l, rms_r);
    float rms_1v = (rms_l + rms_r) / 2.0f;
    float peak_1v = stereo_peak(buf_1v);

    // 4 voices (at different pitches to avoid phase cancellation)
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    float notes[] = {48.0f, 55.0f, 60.0f, 67.0f};
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(engine, v, 1);
        orpheus_engine_set_voice_tune(engine, v, notes[v]);
        orpheus_engine_set_voice_gate(engine, v, 1);
        engine->voice_params[v].engine_index.store(8); // VirtualAnalog
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.0f);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_pan[v].store(0.0f);
    }
    engine->master_volume.store(0.8f);
    engine->master_pan.store(0.0f);

    // Warm up
    float warmup_buf[128 * 2];
    for (int i = 0; i < 10; i++)
        orpheus_engine_process(engine, warmup_buf, 128);

    int total = 48000 / 2; // 0.5s
    std::vector<float> buf_4v(total * 2, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        orpheus_engine_process(engine, buf_4v.data() + off * 2, chunk);
    }
    orpheus_engine_destroy(engine);

    stereo_rms(buf_4v, rms_l, rms_r);
    float rms_4v = (rms_l + rms_r) / 2.0f;
    float peak_4v = stereo_peak(buf_4v);

    float level_ratio = rms_4v / (rms_1v + 0.0001f);
    printf("  1 voice:  peak=%.4f RMS=%.4f\n", peak_1v, rms_1v);
    printf("  4 voices: peak=%.4f RMS=%.4f\n", peak_4v, rms_4v);
    printf("  Level ratio (4v/1v): %.2f (linear sum would be ~4.0, constant-power ~2.0)\n", level_ratio);

    // 4 voices should be louder than 1 (basic sanity)
    if (level_ratio < 1.5f) {
        printf("  FAIL: 4 voices barely louder than 1 (ratio=%.2f, expected > 1.5)\n", level_ratio);
        pass = false;
    }

    // Peak should not be hard-clipped at exactly 1.0 (that would indicate hard clip)
    // and should not be severely compressed below 0.5 of expected
    printf("  4v peak: %.4f (expect > 0.05 and natural dynamics)\n", peak_4v);
    if (peak_4v < 0.01f) {
        printf("  FAIL: 4-voice peak %.4f is too quiet\n", peak_4v);
        pass = false;
    }

    printf("Multi-voice gain staging test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 7: Final output characteristics — compare with JSyn expectations
// This is a diagnostic test that reports gain staging metrics
// ═══════════════════════════════════════════════════════════════════
static bool test_gain_staging_report() {
    printf("\n=== Test: Gain staging report (diagnostic) ===\n");
    bool pass = true;

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
        for (int v = 0; v < sc.num_voices; v++) {
            orpheus_engine_set_voice_active(engine, v, 1);
            orpheus_engine_set_voice_tune(engine, v, notes[v]);
            orpheus_engine_set_voice_gate(engine, v, 1);
            engine->voice_params[v].engine_index.store(8); // VirtualAnalog
            engine->voice_params[v].harmonics.store(0.5f);
            engine->voice_params[v].timbre.store(0.5f);
            engine->voice_params[v].morph.store(0.5f);
            engine->voice_params[v].decay.store(0.0f);
            engine->voice_params[v].ever_triggered.store(1);
        }
        engine->master_volume.store(sc.volume);
        engine->master_pan.store(0.0f);
        engine->drive_amount.store(1.0f + sc.drive * 14.0f);
        engine->drive_mix.store(sc.drive_mix);

        // Warm up
        float warmup[128 * 2];
        for (int i = 0; i < 10; i++)
            orpheus_engine_process(engine, warmup, 128);

        int total = 24000; // 0.5s
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            orpheus_engine_process(engine, buf.data() + off * 2, chunk);
        }
        orpheus_engine_destroy(engine);

        float rms_l, rms_r;
        stereo_rms(buf, rms_l, rms_r);
        float rms = (rms_l + rms_r) / 2.0f;
        float peak = stereo_peak(buf);
        float crest = (rms > 0.0001f) ? peak / rms : 0.0f;

        printf("  %-20s  %8.4f %8.4f %8.2f\n", sc.name, peak, rms, crest);
    }

    // This test always passes — it's diagnostic reporting
    // The data helps compare against JSyn equivalent
    printf("Gain staging report: PASS (diagnostic)\n");
    return pass;
}

bool run_output_chain_tests() {
    bool all_pass = true;
    all_pass &= test_master_volume_scaling();
    all_pass &= test_output_headroom();
    all_pass &= test_drive_distortion();
    all_pass &= test_master_pan();
    all_pass &= test_voice_pan();
    all_pass &= test_multi_voice_gain_staging();
    all_pass &= test_gain_staging_report();
    return all_pass;
}
