// Full engine render tests + mod source routing
#include "test_harness.h"

static bool test_full_engine_render() {
    printf("\n=== Test: Full engine render (production graph) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_production_graph(engine)) {
        printf("FAIL: could not load production graph\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    activate_voice(engine, 0, 0, 60.0f);
    auto r = render_engine(engine, 48000, 0);

    printf("  Peak: %.4f RMS_L: %.4f RMS_R: %.4f %s\n",
           r.peak, r.rms_l, r.rms_r, r.peak > 0.001f ? "OK" : "FAIL (silence!)");

    bool pass = r.peak > 0.001f;
    printf("Full engine render test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_polyphonic_engine_render() {
    printf("\n=== Test: Polyphonic full engine render (production graph) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_production_graph(engine)) {
        printf("FAIL: could not load production graph\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    float notes[] = {48.0f, 55.0f, 60.0f, 67.0f};
    for (int v = 0; v < 4; v++)
        activate_voice(engine, v, 0, notes[v]);

    auto r = render_engine(engine, 24000, 0);

    bool all_pass = true;
    int producing = 0;
    for (int v = 0; v < 4; v++) {
        float level = engine->voice_levels[v].load(std::memory_order_relaxed);
        bool ok = level > 0.001f;
        if (ok) producing++;
        printf("  Voice %d (note %.0f): level=%.4f %s\n", v, notes[v], level, ok ? "OK" : "SILENT!");
        all_pass &= ok;
    }

    printf("  Mix peak: %.4f, %d/4 voices producing\n", r.peak, producing);
    all_pass &= (r.peak > 0.001f);

    printf("Polyphonic engine render test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_mod_source_routing() {
    printf("\n=== Test: Mod source routing (OFF/FM/LFO/FLUX) ===\n");

    struct ModConfig {
        const char* name;
        int mod_source;
        float mod_depth;
        float fm_depth;
    };
    // Kotlin ModSource ordinals: VOICE_FM=0, OFF=1, LFO=2, FLUX=3
    ModConfig configs[] = {
        {"OFF",  1, 0.0f, 0.0f},
        {"FM",   0, 0.0f, 0.8f},
        {"LFO",  2, 0.8f, 0.0f},
        {"FLUX", 3, 0.8f, 0.0f},
    };

    static float outputs[4][24000];
    float rms_values[4];

    for (int c = 0; c < 4; c++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        activate_voice(engine, 0, 0, 60.0f, 0.5f, 0.5f, 0.5f, 0.5f);

        engine->mod_source[0].store(configs[c].mod_source);
        engine->mod_depth[0].store(configs[c].mod_depth);
        engine->fm_depth[0].store(configs[c].fm_depth);

        if (configs[c].mod_source == 0) { // VOICE_FM
            activate_voice(engine, 1, 0, 67.0f, 0.5f, 0.5f, 0.5f, 0.5f);
            GraphUnit v1;
            setup_voice_unit(&v1, 1);
            render_voice(&v1, engine, 12000);
        } else if (configs[c].mod_source == 2) {
            engine->lfo_output_value = 0.7f;
            for (int i = 0; i < kMaxFrames; i++) engine->lfo_output_buffer[i] = 0.7f;
        } else if (configs[c].mod_source == 3) {
            engine->marbles_cv_output[0] = 2.5f;
        }

        GraphUnit v0;
        setup_voice_unit(&v0, 0);
        int total_samples = 0;
        float sum_sq = 0.0f;
        for (int offset = 0; offset < 24000; offset += 128) {
            int chunk = std::min(128, 24000 - offset);
            unit_process_plaits(&v0, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float s = v0.output_buffers[OPORT_OUT][i];
                outputs[c][total_samples++] = s;
                sum_sq += s * s;
            }
        }
        rms_values[c] = std::sqrt(sum_sq / total_samples);
        printf("  %4s: RMS=%.6f\n", configs[c].name, rms_values[c]);
        orpheus_engine_destroy(engine);
    }

    int num_different = 0;
    for (int i = 0; i < 4; i++) {
        for (int j = i + 1; j < 4; j++) {
            float diff_sum = 0.0f;
            for (int s = 0; s < 24000; s++) {
                float d = outputs[i][s] - outputs[j][s];
                diff_sum += d * d;
            }
            float drms = std::sqrt(diff_sum / 24000);
            bool different = drms > 0.001f;
            if (different) num_different++;
            printf("  %4s vs %4s: diff_rms=%.6f %s\n",
                   configs[i].name, configs[j].name, drms,
                   different ? "DIFFERENT" : "SAME!");
        }
    }
    bool pass = num_different >= 4;
    printf("Mod source routing: %d/6 pairs different — %s\n", num_different, pass ? "PASS" : "FAIL");
    return pass;
}

bool run_engine_render_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_full_engine_render());
    tally(test_polyphonic_engine_render());
    tally(test_mod_source_routing());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
