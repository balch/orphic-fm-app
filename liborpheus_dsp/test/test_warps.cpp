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
// Helper: render a synth×drums scenario with given settings, write WAV
static void render_synth_drums(const char* label, const char* filename,
                                int algo_x10, float timbre, float level1, float level2,
                                int carrier_src, int mod_src) {
    OrpheusEngine* engine = orpheus_engine_create(SR);
    if (!load_production_graph(engine)) return;

    // Sustained synth chord (CleanPatch duo 0 = voices 0,1)
    float clean_tunes[] = {37.80f, 41.16f, 52.20f, 55.56f};
    for (int i = 0; i < 4; i++) {
        engine->voice_params[i].gate.store(1);
        engine->voice_params[i].active.store(1);
        engine->voice_params[i].ever_triggered.store(1);
        engine->voice_params[i].tune.store(clean_tunes[i]);
        engine->voice_params[i].engine_index.store(-1);  // Engine 0
        engine->voice_params[i].decay.store(0.0f);
    }

    engine->warps_carrier_source.store(carrier_src);
    engine->warps_modulator_source.store(mod_src);
    engine->warps_algorithm.store(algo_x10 / 10.0f);
    engine->warps_timbre.store(timbre);
    engine->warps_level1.store(level1);
    engine->warps_level2.store(level2);
    engine->warps_mix.store(0.6f);
    engine->warps_bypass.store(0);

    int frames = SR * 3;  // 3 seconds
    std::vector<float> buf(frames * 2, 0.0f);
    for (int done = 0; done < frames; ) {
        int block = std::min(512, frames - done);
        orpheus_engine_process(engine, buf.data() + done * 2, block);
        done += block;
        // Trigger drums every 0.5s (bass + snare alternating)
        if (done % (SR / 2) < 512) {
            orpheus_engine_trigger_drum(engine, (done / (SR/2)) % 3, 0.9f);
        }
    }

    AudioStats stats = measure(buf.data(), frames);
    printf("  %-30s rms=%.4f peak=%.4f clips=%d\n",
           label, stats.rms_l, stats.peak_l, stats.clip_count);

    char path[256];
    snprintf(path, sizeof(path), "test/output/%s.wav", filename);
    write_wav(path, buf.data(), frames, SR);

    orpheus_engine_destroy(engine);
}

static bool test_warps_synth_drums() {
    printf("\n=== Test: Warps synth × drums (all algorithms) ===\n");

    // Dry reference: no Warps
    {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;
        float clean_tunes[] = {37.80f, 41.16f, 52.20f, 55.56f};
        for (int i = 0; i < 4; i++) {
            engine->voice_params[i].gate.store(1);
            engine->voice_params[i].active.store(1);
            engine->voice_params[i].ever_triggered.store(1);
            engine->voice_params[i].tune.store(clean_tunes[i]);
            engine->voice_params[i].engine_index.store(-1);
            engine->voice_params[i].decay.store(0.0f);
        }
        engine->warps_bypass.store(1);
        int frames = SR * 3;
        std::vector<float> buf(frames * 2, 0.0f);
        for (int done = 0; done < frames; ) {
            int block = std::min(512, frames - done);
            orpheus_engine_process(engine, buf.data() + done * 2, block);
            done += block;
            if (done % (SR / 2) < 512)
                orpheus_engine_trigger_drum(engine, (done / (SR/2)) % 3, 0.9f);
        }
        AudioStats stats = measure(buf.data(), frames);
        printf("  %-30s rms=%.4f peak=%.4f clips=%d\n",
               "DRY (no warps)", stats.rms_l, stats.peak_l, stats.clip_count);
        write_wav("test/output/warps_sd_0_dry.wav", buf.data(), frames, SR);
        orpheus_engine_destroy(engine);
    }

    // carrier=SYNTH(0), modulator=DRUMS(1) — each algorithm
    const char* algo_names[] = {"crossfade","fold","analog_ring","digital_ring","xor","comparator"};
    for (int a = 0; a < 6; a++) {
        char label[64], fname[64];
        snprintf(label, sizeof(label), "SYNTH×DRUMS %s", algo_names[a]);
        snprintf(fname, sizeof(fname), "warps_sd_%d_%s", a+1, algo_names[a]);
        render_synth_drums(label, fname, a * 10 + 5, 0.5f, 0.6f, 0.6f, 0, 1);
    }

    // Also: DRUMS as carrier, SYNTH as modulator (reversed)
    render_synth_drums("DRUMS×SYNTH ring (reversed)", "warps_sd_7_reversed_ring",
                       25, 0.5f, 0.6f, 0.6f, 1, 0);

    // User's exact scenario: CROSSFADE, SYNTH+SYNTH, everything 0.5, no drums
    {
        OrpheusEngine* e = orpheus_engine_create(SR);
        if (!load_production_graph(e)) return false;
        float t[] = {37.80f, 41.16f, 52.20f, 55.56f};
        for (int i = 0; i < 4; i++) {
            e->voice_params[i].gate.store(1);
            e->voice_params[i].active.store(1);
            e->voice_params[i].ever_triggered.store(1);
            e->voice_params[i].tune.store(t[i]);
            e->voice_params[i].engine_index.store(-1);
            e->voice_params[i].decay.store(0.0f);
        }
        e->warps_carrier_source.store(0);
        e->warps_modulator_source.store(0);
        e->warps_algorithm.store(0.5f);  // crossfade
        e->warps_timbre.store(0.5f);
        e->warps_level1.store(0.5f);
        e->warps_level2.store(0.5f);
        e->warps_mix.store(1.0f);
        e->warps_bypass.store(0);
        e->drum_mix.store(0.0f);       // silence drums
        e->grids_bypass.store(1);      // disable grids sequencer
        // Kill all drum voices (12-14)
        for (int d = kDrumVoiceStart; d < kNumVoices; d++) {
            e->voice_params[d].gate.store(0);
            e->voice_params[d].active.store(0);
            e->voice_params[d].ever_triggered.store(0);
        }
        // Kill unused synth voices (4-11)
        for (int v = 4; v < kDrumVoiceStart; v++) {
            e->voice_params[v].gate.store(0);
            e->voice_params[v].active.store(0);
            e->voice_params[v].ever_triggered.store(0);
        }
        int frames = SR * 3;
        std::vector<float> buf(frames * 2, 0.0f);
        for (int done = 0; done < frames; ) {
            int block = std::min(512, frames - done);
            orpheus_engine_process(e, buf.data() + done * 2, block);
            done += block;
        }
        AudioStats s = measure(buf.data(), frames);
        printf("  %-30s rms=%.4f peak=%.4f clips=%d\n",
               "SYNTH×SYNTH xfade 0.5 (no drums)", s.rms_l, s.peak_l, s.clip_count);
        write_wav("test/output/warps_crossfade_synth_synth.wav", buf.data(), frames, SR);
        orpheus_engine_destroy(e);
    }

    // User's scenario: DRUMS×DRUMS crossfade, mix=1.0
    // At mix=1, drums should be fully removed from dry and heard only through Warps.
    {
        OrpheusEngine* e = orpheus_engine_create(SR);
        if (!load_production_graph(e)) return false;
        // Synth voices for background (so we can hear if they bleed)
        float t[] = {37.80f, 41.16f, 52.20f, 55.56f};
        for (int i = 0; i < 4; i++) {
            e->voice_params[i].gate.store(1);
            e->voice_params[i].active.store(1);
            e->voice_params[i].ever_triggered.store(1);
            e->voice_params[i].tune.store(t[i]);
            e->voice_params[i].engine_index.store(-1);
            e->voice_params[i].decay.store(0.0f);
        }
        // Warps: DRUMS×DRUMS crossfade, mix=1.0
        e->warps_carrier_source.store(1);   // DRUMS
        e->warps_modulator_source.store(1); // DRUMS
        e->warps_algorithm.store(0.5f);     // crossfade
        e->warps_timbre.store(0.5f);
        e->warps_level1.store(0.5f);
        e->warps_level2.store(0.5f);
        e->warps_mix.store(1.0f);
        e->warps_bypass.store(0);
        // Grids ON to trigger drums
        e->grids_bypass.store(0);
        e->drum_mix.store(0.7f);
        int frames = SR * 3;
        std::vector<float> buf(frames * 2, 0.0f);
        for (int done = 0; done < frames; ) {
            int block = std::min(512, frames - done);
            orpheus_engine_process(e, buf.data() + done * 2, block);
            done += block;
        }
        AudioStats s = measure(buf.data(), frames);
        // Check DRUMS source buffer has signal
        float src_rms = 0;
        for (int i = 0; i < 512; i++) {
            float v = e->warps_source_buffers[1][i];
            src_rms += v * v;
        }
        src_rms = std::sqrt(src_rms / 512);
        printf("  %-30s rms=%.4f peak=%.4f clips=%d\n",
               "DRUMS×DRUMS xfade mix=1", s.rms_l, s.peak_l, s.clip_count);
        printf("           DRUMS src buf: rms=%.4f\n", src_rms);
        if (s.rms_l < 0.01f) printf("    *** SILENT — drums not audible!\n");
        write_wav("test/output/warps_drums_drums_xfade.wav", buf.data(), frames, SR);
        orpheus_engine_destroy(e);
    }

    // Reference: same setup but Warps bypassed (drums should be fully audible)
    {
        OrpheusEngine* e = orpheus_engine_create(SR);
        if (!load_production_graph(e)) return false;
        float t[] = {37.80f, 41.16f, 52.20f, 55.56f};
        for (int i = 0; i < 4; i++) {
            e->voice_params[i].gate.store(1);
            e->voice_params[i].active.store(1);
            e->voice_params[i].ever_triggered.store(1);
            e->voice_params[i].tune.store(t[i]);
            e->voice_params[i].engine_index.store(-1);
            e->voice_params[i].decay.store(0.0f);
        }
        e->warps_bypass.store(1);
        e->grids_bypass.store(0);
        e->drum_mix.store(0.7f);
        int frames = SR * 3;
        std::vector<float> buf(frames * 2, 0.0f);
        for (int done = 0; done < frames; ) {
            int block = std::min(512, frames - done);
            orpheus_engine_process(e, buf.data() + done * 2, block);
            done += block;
        }
        AudioStats s = measure(buf.data(), frames);
        printf("  %-30s rms=%.4f peak=%.4f clips=%d\n",
               "DRY reference (warps off)", s.rms_l, s.peak_l, s.clip_count);
        write_wav("test/output/warps_drums_drums_dry.wav", buf.data(), frames, SR);
        orpheus_engine_destroy(e);
    }

    // ── Wet/Dry isolation analysis ──
    // Read back the dry and each wet WAV, subtract to isolate Warps contribution,
    // verify the effect is audible and algorithms produce distinct results.
    printf("\n  Wet/Dry isolation analysis:\n");
    printf("  %-20s  %8s  %8s  %8s  %s\n", "Algorithm", "Dry RMS", "Wet RMS", "Ratio", "Status");

    // Read dry reference
    std::vector<float> dry_buf;
    int dry_sr;
    int dry_frames = read_wav_mono("test/output/warps_sd_0_dry.wav", dry_buf, dry_sr);
    if (dry_frames <= 0) {
        printf("  ERROR: Could not read dry WAV\n");
        return false;
    }

    bool isolation_pass = true;
    float prev_diff_rms = -1.0f;
    int identical_count = 0;

    for (int a = 0; a < 6; a++) {
        char fname[64];
        snprintf(fname, sizeof(fname), "test/output/warps_sd_%d_%s.wav", a+1, algo_names[a]);

        std::vector<float> wet_buf;
        int wet_sr;
        int wet_frames = read_wav_mono(fname, wet_buf, wet_sr);
        if (wet_frames <= 0) continue;

        int n = std::min((int)dry_buf.size(), (int)wet_buf.size());
        double diff_sum = 0, dry_sum = 0;
        for (int i = 0; i < n; i++) {
            float d = wet_buf[i] - dry_buf[i];
            diff_sum += d * d;
            dry_sum += dry_buf[i] * dry_buf[i];
        }
        float diff_rms = std::sqrt(diff_sum / n);
        float dry_rms = std::sqrt(dry_sum / n);
        float ratio_db = (diff_rms > 0 && dry_rms > 0)
            ? 20.0f * std::log10(diff_rms / dry_rms) : -100.0f;

        const char* status = "OK";
        if (ratio_db < -12.0f) {
            status = "*** INAUDIBLE";
            isolation_pass = false;
        }
        printf("  %-20s  %8.4f  %8.4f  %6.1fdB  %s\n",
               algo_names[a], dry_rms, diff_rms, ratio_db, status);

        // Check if this algorithm sounds different from the previous one
        if (prev_diff_rms >= 0 && std::fabs(diff_rms - prev_diff_rms) < 0.001f) {
            identical_count++;
        }
        prev_diff_rms = diff_rms;
    }

    if (identical_count >= 4) {
        printf("  *** %d algorithms produce nearly identical output — routing may be broken\n",
               identical_count + 1);
        isolation_pass = false;
    }

    if (!isolation_pass) {
        printf("  FAIL: Warps effect is not sufficiently audible\n");
    } else {
        printf("  PASS: Warps effect is audible and algorithms differ\n");
    }

    printf("  WAVs written to test/output/warps_sd_*.wav\n");
    return isolation_pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 5: CleanPatch scenario — 12 Engine 0 voices, Warps level sweep
// Replicates the user's exact setup to find crackling source
// ═══════════════════════════════════════════════════════════════════
static bool test_warps_clean_patch() {
    printf("\n=== Test: CleanPatch + Warps level sweep ===\n");
    bool pass = true;

    // CleanPatch tunes (MIDI note numbers derived from 0-1 normalized values)
    // tune 0.35 → ~42, 0.77 → ~92 etc. The raw float IS the tune value sent to engine.
    // Engine 0 uses tune as a 0-1 knob → mapped to frequency internally.
    // Actually: VoiceParams.tune stores MIDI note number (e.g., 60.0 = C4).
    // CleanPatch uses 0.35-0.77 which are normalized; the plugin maps these.
    // For testing, use MIDI notes spread across a chord:
    float tunes[] = {
        48.0f, 52.0f, 50.0f, 55.0f,   // pair 0-1: lower mid
        60.0f, 64.0f, 62.0f, 67.0f,   // pair 2-3: mid
        72.0f, 76.0f, 74.0f, 79.0f    // pair 4-5: upper
    };

    float level_sweep[] = {0.3f, 0.5f, 0.7f, 0.85f, 1.0f};

    for (float level : level_sweep) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;

        // Activate all 12 voices as Engine 0 (engine_index < 0)
        for (int i = 0; i < 12; i++) {
            engine->voice_params[i].gate.store(1);
            engine->voice_params[i].active.store(1);
            engine->voice_params[i].ever_triggered.store(1);
            engine->voice_params[i].tune.store(tunes[i]);
            engine->voice_params[i].engine_index.store(-1);  // Engine 0
            engine->voice_params[i].decay.store(0.0f);       // fast envelope
        }

        // Warps: carrier=SYNTH, modulator=SYNTH, fold algorithm, full mix
        engine->warps_carrier_source.store(0);
        engine->warps_modulator_source.store(0);
        engine->warps_algorithm.store(1.5f);  // fold
        engine->warps_timbre.store(0.5f);
        engine->warps_level1.store(level);
        engine->warps_level2.store(level);
        engine->warps_mix.store(1.0f);
        engine->warps_bypass.store(0);

        // Reverb + delay like CleanPatch
        engine->reverb_amount.store(0.20f);
        engine->reverb_time.store(0.35f);

        int frames = SR * 2;  // 2 seconds
        std::vector<float> buf(frames * 2, 0.0f);
        for (int done = 0; done < frames; ) {
            int block = std::min(512, frames - done);
            orpheus_engine_process(engine, buf.data() + done * 2, block);
            done += block;
        }

        AudioStats stats = measure(buf.data(), frames);

        // Count discontinuities (sample-to-sample jumps > threshold)
        int disc_count = 0;
        float disc_threshold = 0.05f;
        for (int i = 1; i < frames; i++) {
            float dl = std::fabs(buf[i*2] - buf[(i-1)*2]);
            float dr = std::fabs(buf[i*2+1] - buf[(i-1)*2+1]);
            if (dl > disc_threshold) disc_count++;
            if (dr > disc_threshold) disc_count++;
        }

        printf("  level=%.2f: rms_L=%.4f peak_L=%.4f clips=%d disc=%d\n",
               level, stats.rms_l, stats.peak_l, stats.clip_count, disc_count);

        if (stats.clip_count > frames / 20) {
            printf("    *** EXCESSIVE CLIPPING at level=%.2f\n", level);
            pass = false;
        }

        // Write WAV for the highest level for manual inspection
        if (level >= 0.99f) {
            write_wav("test/output/warps_clean_patch_level_1.0.wav",
                      buf.data(), frames, SR);
        }

        // Also measure just the SYNTH source buffer level
        double src_sum = 0;
        float src_peak = 0;
        for (int i = 0; i < 512; i++) {
            float v = engine->warps_source_buffers[0][i];
            src_sum += v * v;
            float av = std::fabs(v);
            if (av > src_peak) src_peak = av;
        }
        printf("           SYNTH src: rms=%.4f peak=%.4f\n",
               (float)std::sqrt(src_sum / 512), src_peak);

        orpheus_engine_destroy(engine);
    }

    // CPU timing: Warps on vs off with 12 voices
    printf("\n  CPU timing (full production graph, 12 Engine 0 voices):\n");
    for (int warps_on : {0, 1}) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;
        for (int i = 0; i < 12; i++) {
            engine->voice_params[i].gate.store(1);
            engine->voice_params[i].active.store(1);
            engine->voice_params[i].ever_triggered.store(1);
            engine->voice_params[i].tune.store(tunes[i]);
            engine->voice_params[i].engine_index.store(-1);
            engine->voice_params[i].decay.store(0.0f);
        }
        engine->warps_bypass.store(warps_on ? 0 : 1);
        if (warps_on) {
            engine->warps_carrier_source.store(0);
            engine->warps_modulator_source.store(0);
            engine->warps_algorithm.store(1.5f);
            engine->warps_timbre.store(0.5f);
            engine->warps_level1.store(0.7f);
            engine->warps_level2.store(0.7f);
            engine->warps_mix.store(1.0f);
        }

        int render_frames = SR * 2;
        std::vector<float> buf(render_frames * 2, 0.0f);
        auto t0 = std::chrono::high_resolution_clock::now();
        for (int done = 0; done < render_frames; ) {
            int block = std::min(512, render_frames - done);
            orpheus_engine_process(engine, buf.data() + done * 2, block);
            done += block;
        }
        auto t1 = std::chrono::high_resolution_clock::now();
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        double budget_ms = (512.0 / SR) * 1000.0;  // ~10.67ms per callback
        double cost_per_cb = ms / (render_frames / 512.0);
        printf("    Warps %s: %.1fms total, %.2fms/callback (budget=%.1fms, %.0f%% used)\n",
               warps_on ? "ON " : "OFF", ms, cost_per_cb, budget_ms,
               cost_per_cb / budget_ms * 100.0);
        orpheus_engine_destroy(engine);
    }

    printf("CleanPatch Warps test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 6: Callback-boundary discontinuity test
// Reproduces the real app's processing pattern (512-sample callbacks)
// and checks for discontinuities at block boundaries.
// ═══════════════════════════════════════════════════════════════════
static bool test_warps_callback_boundaries() {
    printf("\n=== Test: Warps callback-boundary discontinuity ===\n");

    OrpheusEngine* engine = orpheus_engine_create(SR);
    if (!load_production_graph(engine)) return false;

    // Dump execution order to check if Warps runs before/after voices
    OrpheusGraph* graph = engine->graph.load(std::memory_order_acquire);
    if (graph) orpheus_graph_dump_exec_order(graph);

    // Exact CleanPatch MIDI notes after tuneToMidiNote() conversion:
    //   midiNote = 33 + tune*48 + VOICE_PITCH_MULT_SEMITONES[i] + 24*(0.5-0.5)
    //   pitchMult = [-12,-12, 0,0, 0,0, 12,12, 0,0, 0,0]
    //   tunes     = [0.35,0.42, 0.40,0.47, 0.52,0.57, 0.55,0.62, 0.65,0.72, 0.70,0.77]
    float tunes[] = {
        37.80f, 41.16f,  // pair 0: bass (-12 semitones)
        52.20f, 55.56f,  // pair 1: mid
        57.96f, 60.36f,  // pair 2: mid
        71.40f, 74.76f,  // pair 3: high (+12 semitones)
        64.20f, 67.56f,  // pair 4: repl (0 offset)
        66.60f, 69.96f   // pair 5: repl (0 offset)
    };
    for (int i = 0; i < 12; i++) {
        engine->voice_params[i].gate.store(1);
        engine->voice_params[i].active.store(1);
        engine->voice_params[i].ever_triggered.store(1);
        engine->voice_params[i].tune.store(tunes[i]);
        engine->voice_params[i].engine_index.store(-1);  // Engine 0
        engine->voice_params[i].decay.store(0.0f);       // fast envelope
    }

    // CleanPatch LFO settings
    engine->lfo_freq_a.store(0.02f);
    engine->lfo_freq_b.store(0.035f);
    engine->lfo_shape.store(1.0f);
    engine->lfo_mode.store(0);  // AND

    // CleanPatch reverb
    engine->reverb_amount.store(0.20f);
    engine->reverb_time.store(0.35f);
    engine->reverb_diffusion.store(0.50f);

    // CleanPatch delay
    engine->delay_time_1.store(0.45f);
    engine->delay_time_2.store(0.60f);
    engine->delay_feedback.store(0.20f);
    engine->delay_mix.store(0.15f);

    // Warps: SYNTH+SYNTH, wavefolder, matching user's screenshot exactly
    engine->warps_carrier_source.store(0);
    engine->warps_modulator_source.store(0);
    engine->warps_algorithm.store(1.5f);  // wavefolder
    engine->warps_timbre.store(0.5f);
    engine->warps_level1.store(0.5f);
    engine->warps_level2.store(0.5f);
    engine->warps_mix.store(0.5f);
    engine->warps_bypass.store(0);

    // Process in EXACTLY 512-sample blocks (matching CoreAudio callback)
    constexpr int CB_SIZE = 512;
    constexpr int NUM_CALLBACKS = 200;  // ~2.1 seconds
    constexpr int TOTAL = CB_SIZE * NUM_CALLBACKS;
    std::vector<float> buf(TOTAL * 2, 0.0f);

    for (int cb = 0; cb < NUM_CALLBACKS; cb++) {
        orpheus_engine_process(engine, buf.data() + cb * CB_SIZE * 2, CB_SIZE);
    }

    // Check for discontinuities specifically at callback boundaries
    int boundary_disc = 0;
    int interior_disc = 0;
    float boundary_max_jump = 0;
    float interior_max_jump = 0;

    for (int i = 1; i < TOTAL; i++) {
        float dl = std::fabs(buf[i*2] - buf[(i-1)*2]);
        bool at_boundary = (i % CB_SIZE == 0);
        if (dl > 0.02f) {
            if (at_boundary) {
                boundary_disc++;
                if (dl > boundary_max_jump) boundary_max_jump = dl;
            } else {
                interior_disc++;
                if (dl > interior_max_jump) interior_max_jump = dl;
            }
        }
    }

    // Also check the SYNTH source buffer state right after a callback
    // (to see if it has valid data when Warps would read it)
    float src_rms = 0;
    for (int i = 0; i < CB_SIZE; i++) {
        float v = engine->warps_source_buffers[0][i];
        src_rms += v * v;
    }
    src_rms = std::sqrt(src_rms / CB_SIZE);

    printf("  Callback boundaries (%d total):\n", NUM_CALLBACKS - 1);
    printf("    Boundary discontinuities (>0.02): %d  max_jump=%.4f\n",
           boundary_disc, boundary_max_jump);
    printf("    Interior discontinuities (>0.02): %d  max_jump=%.4f\n",
           interior_disc, interior_max_jump);
    printf("    SYNTH source buffer RMS (last block): %.4f\n", src_rms);

    // The ratio of boundary to interior discontinuities reveals the problem.
    // Interior has (CB_SIZE-1)*NUM_CALLBACKS ≈ 102,000 sample transitions
    // Boundary has NUM_CALLBACKS-1 = 199 sample transitions
    // If the ratio is elevated, boundaries are disproportionately discontinuous.
    float interior_rate = (float)interior_disc / ((CB_SIZE - 1) * NUM_CALLBACKS);
    float boundary_rate = (float)boundary_disc / (NUM_CALLBACKS - 1);
    float ratio = (boundary_rate > 0 && interior_rate > 0)
        ? boundary_rate / interior_rate : 0;

    printf("    Boundary disc rate: %.4f  Interior disc rate: %.4f  Ratio: %.1fx\n",
           boundary_rate, interior_rate, ratio);

    bool pass = true;
    if (ratio > 3.0f) {
        printf("    *** BOUNDARY CRACKLING DETECTED: boundaries are %.1fx more discontinuous\n", ratio);
        pass = false;
    }

    // Write WAV for listening
    write_wav("test/output/warps_callback_boundary.wav", buf.data(), TOTAL, SR);

    orpheus_engine_destroy(engine);
    printf("Callback boundary test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
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
    all_pass &= test_warps_clean_patch();
    all_pass &= test_warps_callback_boundaries();

    printf("\nWarps tests: %s\n", all_pass ? "ALL PASS" : "SOME FAILED");
    return all_pass;
}
