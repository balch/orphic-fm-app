// Warps signal path analysis: WAV + CSV output for visual inspection
#include "test_harness.h"

static constexpr int SR = 48000;
static constexpr int FRAMES = SR * 2;  // 2 seconds per test

// Write stereo interleaved float buffer as CSV (time, left, right, peak)
static void write_csv(const char* path, const float* buf, int num_frames, int sample_rate) {
    FILE* f = fopen(path, "w");
    if (!f) { fprintf(stderr, "Cannot open %s\n", path); return; }
    fprintf(f, "sample,time_ms,left,right,peak\n");
    // Downsample to ~1000 points for readable CSV
    int step = num_frames / 1000;
    if (step < 1) step = 1;
    for (int i = 0; i < num_frames; i += step) {
        float l = buf[i * 2];
        float r = buf[i * 2 + 1];
        float peak = std::max(std::fabs(l), std::fabs(r));
        fprintf(f, "%d,%.2f,%.6f,%.6f,%.6f\n",
                i, (float)i / sample_rate * 1000.0f, l, r, peak);
    }
    fclose(f);
    printf("Wrote %s\n", path);
}

// Measure RMS and peak of a stereo buffer
struct AudioStats {
    float rms_l, rms_r, peak_l, peak_r;
    int clip_count;  // samples at ±1.0
};

static AudioStats measure(const float* buf, int num_frames) {
    AudioStats s = {};
    double sum_l = 0, sum_r = 0;
    for (int i = 0; i < num_frames; i++) {
        float l = buf[i * 2];
        float r = buf[i * 2 + 1];
        sum_l += l * l;
        sum_r += r * r;
        float al = std::fabs(l), ar = std::fabs(r);
        if (al > s.peak_l) s.peak_l = al;
        if (ar > s.peak_r) s.peak_r = ar;
        if (al >= 0.999f) s.clip_count++;
        if (ar >= 0.999f) s.clip_count++;
    }
    s.rms_l = std::sqrt(sum_l / num_frames);
    s.rms_r = std::sqrt(sum_r / num_frames);
    return s;
}

// ═══════════════════════════════════════════════════════════════════
// Test 1: Warps source buffer levels — measure what each source produces
// ═══════════════════════════════════════════════════════════════════
static bool test_warps_source_levels() {
    printf("\n=== Test: Warps source buffer levels ===\n");

    OrpheusEngine* engine = orpheus_engine_create(SR);
    if (!load_production_graph(engine)) return false;

    // Activate voice 0 (SYNTH source) with a sustained note
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].tune.store(60.0f);  // middle C
    engine->voice_params[0].engine_index.store(8); // VA synth

    // Trigger a drum (DRUMS source)
    orpheus_engine_trigger_drum(engine, 0, 0.8f);

    // Disable warps so it doesn't consume the sources
    engine->warps_bypass.store(1);

    // Render
    float buf[FRAMES * 2];
    // Render in blocks to let the engines settle
    for (int done = 0; done < FRAMES; ) {
        int block = std::min(256, FRAMES - done);
        orpheus_engine_process(engine, buf + done * 2, block);
        done += block;
        // Re-trigger drum every 0.5s to keep it alive
        if (done % (SR / 2) < 256) {
            orpheus_engine_trigger_drum(engine, 0, 0.8f);
        }
    }

    // Report source buffer levels
    const char* names[] = {"SYNTH", "DRUMS", "REPL", "LFO", "RESO", "WARPS_FB", "FLUX", "BENDER", "STRINGS"};
    printf("  Source buffer RMS levels (last block):\n");
    for (int s = 0; s < OrpheusEngine::kNumWarpsSources; s++) {
        double sum = 0;
        float peak = 0;
        for (int i = 0; i < 256; i++) {
            float v = engine->warps_source_buffers[s][i];
            sum += v * v;
            float av = std::fabs(v);
            if (av > peak) peak = av;
        }
        float rms = std::sqrt(sum / 256);
        printf("    [%d] %-10s  rms=%.4f  peak=%.4f%s\n",
               s, names[s], rms, peak,
               (s == 0 || s == 1) && rms < 0.001f ? "  *** SILENT!" : "");
    }

    orpheus_engine_destroy(engine);
    return true;
}

// ═══════════════════════════════════════════════════════════════════
// Test 2: Warps algorithm sweep — render each algorithm and measure levels
// ═══════════════════════════════════════════════════════════════════
static bool test_warps_algorithms() {
    printf("\n=== Test: Warps algorithm sweep ===\n");
    bool pass = true;

    const char* algo_names[] = {
        "crossfade", "fold", "analog_ring", "digital_ring", "xor", "comparator"
    };

    for (int algo = 0; algo < 6; algo++) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;

        // Voice 0: sustained sine
        engine->voice_params[0].gate.store(1);
        engine->voice_params[0].active.store(1);
        engine->voice_params[0].ever_triggered.store(1);
        engine->voice_params[0].tune.store(60.0f);
        engine->voice_params[0].engine_index.store(8); // VA

        // Voice 2: different pitch for modulator
        engine->voice_params[2].gate.store(1);
        engine->voice_params[2].active.store(1);
        engine->voice_params[2].ever_triggered.store(1);
        engine->voice_params[2].tune.store(67.0f);  // G above middle C
        engine->voice_params[2].engine_index.store(10); // FM

        // Warps: carrier=SYNTH, modulator=SYNTH, full mix
        engine->warps_carrier_source.store(0);
        engine->warps_modulator_source.store(0);
        // UI sends 0-8 range; C++ divides by 8 internally
        engine->warps_algorithm.store(algo + 0.5f);  // center of each algo zone (0.5, 1.5, ...)
        engine->warps_timbre.store(0.5f);
        engine->warps_level1.store(0.5f);
        engine->warps_level2.store(0.5f);
        engine->warps_mix.store(1.0f);
        engine->warps_bypass.store(0);

        float buf[FRAMES * 2] = {};
        for (int done = 0; done < FRAMES; ) {
            int block = std::min(256, FRAMES - done);
            orpheus_engine_process(engine, buf + done * 2, block);
            done += block;
        }

        AudioStats stats = measure(buf, FRAMES);
        printf("  algo %d (%-14s): rms_L=%.4f rms_R=%.4f peak_L=%.4f peak_R=%.4f clips=%d\n",
               algo, algo_names[algo],
               stats.rms_l, stats.rms_r, stats.peak_l, stats.peak_r, stats.clip_count);

        if (stats.rms_l < 0.001f && stats.rms_r < 0.001f) {
            printf("    *** SILENT — algorithm not producing output!\n");
            pass = false;
        }
        if (stats.clip_count > FRAMES / 10) {
            printf("    *** EXCESSIVE CLIPPING — %d clips in %d frames\n", stats.clip_count, FRAMES);
        }

        // Write WAV + CSV
        char wav_path[128], csv_path[128];
        snprintf(wav_path, sizeof(wav_path), "test/output/warps_algo_%d_%s.wav", algo, algo_names[algo]);
        snprintf(csv_path, sizeof(csv_path), "test/output/warps_algo_%d_%s.csv", algo, algo_names[algo]);
        write_wav(wav_path, buf, FRAMES, SR);
        write_csv(csv_path, buf, FRAMES, SR);

        orpheus_engine_destroy(engine);
    }

    printf("Warps algorithm sweep: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 3: Warps mix sweep — verify dry/wet crossfade
// ═══════════════════════════════════════════════════════════════════
static bool test_warps_mix() {
    printf("\n=== Test: Warps mix crossfade ===\n");
    bool pass = true;

    float mix_levels[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};

    for (float mix : mix_levels) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;

        // Voice 0: sustained
        engine->voice_params[0].gate.store(1);
        engine->voice_params[0].active.store(1);
        engine->voice_params[0].ever_triggered.store(1);
        engine->voice_params[0].tune.store(60.0f);
        engine->voice_params[0].engine_index.store(8);

        // Warps: carrier=SYNTH, modulator=SYNTH, analog ring mod
        engine->warps_carrier_source.store(0);
        engine->warps_modulator_source.store(0);
        engine->warps_algorithm.store(2.5f);  // analog ring mod
        engine->warps_timbre.store(0.5f);
        engine->warps_level1.store(0.5f);
        engine->warps_level2.store(0.5f);
        engine->warps_mix.store(mix);
        engine->warps_bypass.store(mix <= 0.001f ? 1 : 0);

        int frames = SR;  // 1 second
        float buf[SR * 2] = {};
        for (int done = 0; done < frames; ) {
            int block = std::min(256, frames - done);
            orpheus_engine_process(engine, buf + done * 2, block);
            done += block;
        }

        AudioStats stats = measure(buf, frames);
        printf("  mix=%.2f: rms_L=%.4f peak_L=%.4f clips=%d\n",
               mix, stats.rms_l, stats.peak_l, stats.clip_count);

        // At mix=0 with bypass, should still have synth audio (from other paths)
        // At mix=1, should have warps output + synth
        if (mix > 0.1f && stats.rms_l < 0.001f) {
            printf("    *** SILENT at mix=%.2f!\n", mix);
            pass = false;
        }

        orpheus_engine_destroy(engine);
    }

    printf("Warps mix test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 4: Warps synth×drums — the actual use case
// ═══════════════════════════════════════════════════════════════════
static bool test_warps_synth_drums() {
    printf("\n=== Test: Warps synth × drums ===\n");

    OrpheusEngine* engine = orpheus_engine_create(SR);
    if (!load_production_graph(engine)) return false;

    // Sustained synth
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].tune.store(48.0f);  // low bass
    engine->voice_params[0].engine_index.store(19); // STRING

    // Warps: synth × drums, analog ring mod, mix=0.6
    engine->warps_carrier_source.store(0);  // SYNTH
    engine->warps_modulator_source.store(1); // DRUMS
    engine->warps_algorithm.store(2.5f);  // analog ring mod
    engine->warps_timbre.store(0.5f);
    engine->warps_level1.store(0.5f);
    engine->warps_level2.store(0.5f);
    engine->warps_mix.store(0.6f);
    engine->warps_bypass.store(0);

    float buf[FRAMES * 2] = {};
    for (int done = 0; done < FRAMES; ) {
        int block = std::min(256, FRAMES - done);
        orpheus_engine_process(engine, buf + done * 2, block);
        done += block;
        // Trigger drum every 0.25s
        if (done % (SR / 4) < 256) {
            orpheus_engine_trigger_drum(engine, 0, 0.8f);
        }
    }

    AudioStats stats = measure(buf, FRAMES);
    printf("  synth×drums: rms_L=%.4f rms_R=%.4f peak_L=%.4f peak_R=%.4f clips=%d\n",
           stats.rms_l, stats.rms_r, stats.peak_l, stats.peak_r, stats.clip_count);

    write_wav("test/output/warps_synth_drums.wav", buf, FRAMES, SR);
    write_csv("test/output/warps_synth_drums.csv", buf, FRAMES, SR);

    orpheus_engine_destroy(engine);
    return true;
}

// Entry point
bool run_warps_tests() {
    printf("\n══════════════════════════════════════\n");
    printf("  WARPS SIGNAL PATH ANALYSIS\n");
    printf("══════════════════════════════════════\n");

    bool all_pass = true;
    all_pass &= test_warps_source_levels();
    all_pass &= test_warps_algorithms();
    all_pass &= test_warps_mix();
    all_pass &= test_warps_synth_drums();

    printf("\nWarps tests: %s\n", all_pass ? "ALL PASS" : "SOME FAILED");
    return all_pass;
}
