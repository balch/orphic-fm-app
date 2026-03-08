// Full engine render tests + mod source routing
#include "test_harness.h"

static bool test_full_engine_render() {
    printf("\n=== Test: Full engine render (no graph) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    orpheus_engine_set_voice_active(engine, 0, 1);
    orpheus_engine_set_voice_tune(engine, 0, 60.0f);
    orpheus_engine_set_voice_gate(engine, 0, 1);

    const int sample_rate = 48000;
    const int total_frames = sample_rate;
    std::vector<float> buffer(total_frames * 2, 0.0f);

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    float max_sample = 0.0f;
    for (int i = 0; i < total_frames * 2; i++) {
        float a = std::fabs(buffer[i]);
        if (a > max_sample) max_sample = a;
    }
    printf("  Max amplitude: %.4f %s\n", max_sample, max_sample > 0.001f ? "OK" : "FAIL (silence!)");

    bool pass = max_sample > 0.001f;
    printf("Full engine render test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_polyphonic_engine_render() {
    printf("\n=== Test: Polyphonic full engine render ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    float notes[] = {48.0f, 55.0f, 60.0f, 67.0f};
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(engine, v, 1);
        orpheus_engine_set_voice_tune(engine, v, notes[v]);
        orpheus_engine_set_voice_gate(engine, v, 1);
    }

    const int total_frames = 24000;
    std::vector<float> buffer(total_frames * 2, 0.0f);

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    bool all_pass = true;
    int producing = 0;
    for (int v = 0; v < 4; v++) {
        float level = engine->voice_levels[v].load(std::memory_order_relaxed);
        bool ok = level > 0.001f;
        if (ok) producing++;
        printf("  Voice %d (note %.0f): level=%.4f %s\n", v, notes[v], level, ok ? "OK" : "SILENT!");
        all_pass &= ok;
    }

    float max_sample = 0.0f;
    for (int i = 0; i < total_frames * 2; i++) {
        float a = std::fabs(buffer[i]);
        if (a > max_sample) max_sample = a;
    }
    printf("  Mix amplitude: %.4f, %d/4 voices producing\n", max_sample, producing);
    all_pass &= (max_sample > 0.001f);

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
    ModConfig configs[] = {
        {"OFF",  0, 0.0f, 0.0f},
        {"FM",   1, 0.0f, 0.8f},
        {"LFO",  2, 0.8f, 0.0f},
        {"FLUX", 3, 0.8f, 0.0f},
    };

    static float outputs[4][24000];
    float rms_values[4];

    for (int c = 0; c < 4; c++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        engine->voice_params[0].active.store(1);
        engine->voice_params[0].ever_triggered.store(1);
        engine->voice_params[0].engine_index.store(0);
        engine->voice_params[0].tune.store(60.0f);
        engine->voice_params[0].gate.store(1);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);

        engine->mod_source[0].store(configs[c].mod_source);
        engine->mod_depth[0].store(configs[c].mod_depth);
        engine->fm_depth[0].store(configs[c].fm_depth);

        if (configs[c].mod_source == 1) {
            engine->voice_params[1].active.store(1);
            engine->voice_params[1].ever_triggered.store(1);
            engine->voice_params[1].engine_index.store(0);
            engine->voice_params[1].tune.store(67.0f);
            engine->voice_params[1].gate.store(1);
            engine->voice_params[1].harmonics.store(0.5f);
            engine->voice_params[1].timbre.store(0.5f);
            engine->voice_params[1].morph.store(0.5f);
            engine->voice_params[1].decay.store(0.5f);
            GraphUnit v1;
            setup_voice_unit(&v1, 1);
            render_voice(&v1, engine, 12000);
        } else if (configs[c].mod_source == 2) {
            engine->lfo_output_value = 0.7f;
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
            float diff_rms = std::sqrt(diff_sum / 24000);
            bool different = diff_rms > 0.001f;
            if (different) num_different++;
            printf("  %4s vs %4s: diff_rms=%.6f %s\n",
                   configs[i].name, configs[j].name, diff_rms,
                   different ? "DIFFERENT" : "SAME!");
        }
    }
    bool pass = num_different >= 4;
    printf("Mod source routing: %d/6 pairs different — %s\n", num_different, pass ? "PASS" : "FAIL");
    return pass;
}

bool run_engine_render_tests() {
    bool all_pass = true;
    all_pass &= test_full_engine_render();
    all_pass &= test_polyphonic_engine_render();
    all_pass &= test_mod_source_routing();
    return all_pass;
}
