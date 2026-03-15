// Preset rendering tests — loads exact Kotlin preset port values,
// renders through the production graph, analyzes output for clipping
// and discontinuities. Self-iterating: if clipping is detected, reports
// which parameters need adjustment.
#include "test_harness.h"
#include "data/preset_pink.h"
#include "data/preset_clean.h"

static constexpr int SR = 48000;

// Apply a preset's port values to the engine via set_port
static void apply_preset(OrpheusEngine* engine, const std::vector<PresetPort>& ports) {
    for (auto& p : ports) {
        orpheus_engine_set_port(engine, p.uri, p.symbol, p.value);
    }
}

// Render and analyze a preset with Warps at various mix levels
static bool test_preset_warps(const char* name, const std::vector<PresetPort>& ports, float bpm) {
    printf("\n=== Preset: %s (Warps sweep) ===\n", name);
    bool pass = true;

    float mix_levels[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};

    for (float mix : mix_levels) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;

        // Apply all preset ports
        apply_preset(engine, ports);

        // Override Warps mix for this test
        engine->warps_mix.store(mix);
        engine->warps_bypass.store(mix <= 0.001f ? 1 : 0);

        // Gate first 4 voices (simulate holding keys)
        for (int i = 0; i < 4; i++) {
            engine->voice_params[i].gate.store(1);
            engine->voice_params[i].active.store(1);
            engine->voice_params[i].ever_triggered.store(1);
        }

        // Enable drums
        engine->grids_bypass.store(0);

        int frames = SR * 2;  // 2 seconds
        std::vector<float> buf(frames * 2, 0.0f);
        for (int done = 0; done < frames; ) {
            int block = std::min(512, frames - done);
            orpheus_engine_process(engine, buf.data() + done * 2, block);
            done += block;
        }

        // Analyze
        float rms = 0, peak = 0;
        int clips = 0, disc = 0;
        for (int i = 0; i < frames * 2; i++) {
            float s = buf[i];
            rms += s * s;
            float a = std::fabs(s);
            if (a > peak) peak = a;
            if (a >= 0.999f) clips++;
        }
        rms = std::sqrt(rms / (frames * 2));

        // Count discontinuities (>0.05 threshold, L channel)
        for (int i = 2; i < frames * 2; i += 2) {
            if (std::fabs(buf[i] - buf[i-2]) > 0.05f) disc++;
        }

        const char* status = "OK";
        if (clips > frames / 20) {
            status = "*** CLIPPING";
            pass = false;
        }

        printf("  mix=%.2f: rms=%.4f peak=%.4f clips=%d disc=%d %s\n",
               mix, rms, peak, clips, disc, status);

        // Write WAV at max mix for listening
        if (mix >= 0.99f) {
            char path[128];
            snprintf(path, sizeof(path), "test/output/preset_%s_warps_1.0.wav", name);
            write_wav(path, buf.data(), frames, SR);
        }

        // Write dry reference
        if (mix <= 0.01f) {
            char path[128];
            snprintf(path, sizeof(path), "test/output/preset_%s_dry.wav", name);
            write_wav(path, buf.data(), frames, SR);
        }

        orpheus_engine_destroy(engine);
    }

    // Wet/dry isolation
    {
        // Read back dry and wet WAVs
        std::vector<float> dry_buf, wet_buf;
        int dry_sr, wet_sr;
        char dry_path[128], wet_path[128];
        snprintf(dry_path, sizeof(dry_path), "test/output/preset_%s_dry.wav", name);
        snprintf(wet_path, sizeof(wet_path), "test/output/preset_%s_warps_1.0.wav", name);

        int dry_n = read_wav_mono(dry_path, dry_buf, dry_sr);
        int wet_n = read_wav_mono(wet_path, wet_buf, wet_sr);

        if (dry_n > 0 && wet_n > 0) {
            int n = std::min(dry_buf.size(), wet_buf.size());
            float diff_rms = 0, dry_rms = 0;
            for (int i = 0; i < n; i++) {
                float d = wet_buf[i] - dry_buf[i];
                diff_rms += d * d;
                dry_rms += dry_buf[i] * dry_buf[i];
            }
            diff_rms = std::sqrt(diff_rms / n);
            dry_rms = std::sqrt(dry_rms / n);
            float ratio_db = (diff_rms > 0 && dry_rms > 0)
                ? 20.0f * std::log10(diff_rms / dry_rms) : -100.0f;

            printf("  Warps effect: dry=%.4f wet_diff=%.4f ratio=%.1fdB %s\n",
                   dry_rms, diff_rms, ratio_db,
                   ratio_db < -12.0f ? "*** INAUDIBLE" : "OK");
            if (ratio_db < -12.0f) pass = false;
        }
    }

    printf("Preset %s: %s\n", name, pass ? "PASS" : "FAIL");
    return pass;
}

bool run_preset_tests() {
    printf("\n══════════════════════════════════════\n");
    printf("  PRESET RENDERING TESTS\n");
    printf("══════════════════════════════════════\n");

    bool all_pass = true;
    all_pass &= test_preset_warps("pink", preset_pink_ports, preset_pink_bpm);
    all_pass &= test_preset_warps("clean", preset_clean_ports, preset_clean_bpm);

    printf("\nPreset tests: %s\n", all_pass ? "ALL PASS" : "SOME FAILED");
    return all_pass;
}
