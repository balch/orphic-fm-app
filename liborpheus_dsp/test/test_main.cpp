#include "orpheus_dsp.h"
#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>
#include <cmath>
#include <algorithm>
#include <chrono>
#include <sys/stat.h>

// Minimal WAV writer
void write_wav(const char* path, const float* data, int num_frames, int sample_rate) {
    FILE* f = fopen(path, "wb");
    if (!f) { fprintf(stderr, "Cannot open %s\n", path); return; }

    int channels = 2;
    int bytes_per_sample = 2;  // 16-bit
    int data_size = num_frames * channels * bytes_per_sample;
    int file_size = 44 + data_size;

    // WAV header
    fwrite("RIFF", 1, 4, f);
    int32_t chunk_size = file_size - 8; fwrite(&chunk_size, 4, 1, f);
    fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f);
    int32_t fmt_size = 16; fwrite(&fmt_size, 4, 1, f);
    int16_t audio_fmt = 1; fwrite(&audio_fmt, 2, 1, f);
    int16_t nch = channels; fwrite(&nch, 2, 1, f);
    int32_t sr = sample_rate; fwrite(&sr, 4, 1, f);
    int32_t byte_rate = sr * channels * bytes_per_sample; fwrite(&byte_rate, 4, 1, f);
    int16_t block_align = channels * bytes_per_sample; fwrite(&block_align, 2, 1, f);
    int16_t bps = 16; fwrite(&bps, 2, 1, f);
    fwrite("data", 1, 4, f);
    fwrite(&data_size, 4, 1, f);

    // Convert float -> int16
    for (int i = 0; i < num_frames * channels; i++) {
        float s = data[i];
        if (s > 1.0f) s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        int16_t sample = static_cast<int16_t>(s * 32767.0f);
        fwrite(&sample, 2, 1, f);
    }

    fclose(f);
    printf("Wrote %s (%d frames, %d Hz)\n", path, num_frames, sample_rate);
}

// Read a mono WAV file (extracts left channel from stereo). Returns sample count, or -1 on error.
int read_wav_mono(const char* path, std::vector<float>& out, int& sample_rate_out) {
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    char header[44];
    if (fread(header, 1, 44, f) != 44) { fclose(f); return -1; }
    if (header[0] != 'R' || header[1] != 'I') { fclose(f); return -1; }
    int16_t channels;
    std::memcpy(&channels, header + 22, 2);
    int32_t sr;
    std::memcpy(&sr, header + 24, 4);
    sample_rate_out = sr;
    int32_t data_size;
    std::memcpy(&data_size, header + 40, 4);
    int total_samples = data_size / 2; // 16-bit
    int frames = total_samples / channels;
    out.resize(frames);
    for (int i = 0; i < frames; i++) {
        int16_t sample;
        if (fread(&sample, 2, 1, f) != 1) break;
        out[i] = sample / 32767.0f;
        // Skip right channel if stereo
        if (channels > 1) fseek(f, (channels - 1) * 2, SEEK_CUR);
    }
    fclose(f);
    return frames;
}

static float compute_rms(const float* buf, int n) {
    double sum = 0.0;
    for (int i = 0; i < n; i++) sum += (double)buf[i] * buf[i];
    return (float)std::sqrt(sum / n);
}

static float compute_peak(const float* buf, int n) {
    float peak = 0.0f;
    for (int i = 0; i < n; i++) {
        float a = std::fabs(buf[i]);
        if (a > peak) peak = a;
    }
    return peak;
}

// Compare two WAV files: compute RMS difference, correlation, and level ratio.
// Returns true if files exist and comparison succeeds.
struct WavComparison {
    float rms_a, rms_b;
    float rms_diff;
    float correlation;
    float level_ratio;
    bool valid;
};

WavComparison compare_wavs(const char* path_a, const char* path_b) {
    WavComparison c = {};
    std::vector<float> a, b;
    int sr_a, sr_b;
    int n_a = read_wav_mono(path_a, a, sr_a);
    int n_b = read_wav_mono(path_b, b, sr_b);
    if (n_a <= 0 || n_b <= 0) return c;
    int n = std::min(n_a, n_b);
    c.rms_a = compute_rms(a.data(), n);
    c.rms_b = compute_rms(b.data(), n);
    c.level_ratio = (c.rms_b > 0.0001f) ? c.rms_a / c.rms_b : 0.0f;

    // RMS of difference signal
    double diff_sum = 0.0;
    for (int i = 0; i < n; i++) {
        float d = a[i] - b[i];
        diff_sum += (double)d * d;
    }
    c.rms_diff = (float)std::sqrt(diff_sum / n);

    // Pearson correlation
    double mean_a = 0, mean_b = 0;
    for (int i = 0; i < n; i++) { mean_a += a[i]; mean_b += b[i]; }
    mean_a /= n; mean_b /= n;
    double cov = 0, var_a = 0, var_b = 0;
    for (int i = 0; i < n; i++) {
        double da = a[i] - mean_a, db = b[i] - mean_b;
        cov += da * db; var_a += da * da; var_b += db * db;
    }
    c.correlation = (var_a > 0 && var_b > 0) ? (float)(cov / std::sqrt(var_a * var_b)) : 0.0f;
    c.valid = true;
    return c;
}

// Snapshot test helper: render to WAV, optionally compare against reference.
// If reference exists, compare and report. If not, save as new reference.
// Returns true if snapshot matches or is newly created.
bool snapshot_check(const char* name, const float* stereo_data, int num_frames,
                    int sample_rate, const char* output_dir, float tolerance_rms = 0.05f) {
    char snap_path[512], ref_path[512];
    snprintf(snap_path, sizeof(snap_path), "%s/%s.wav", output_dir, name);
    snprintf(ref_path, sizeof(ref_path), "%s/%s.ref.wav", output_dir, name);

    write_wav(snap_path, stereo_data, num_frames, sample_rate);

    // Check if reference exists
    FILE* ref = fopen(ref_path, "rb");
    if (!ref) {
        printf("    [NEW] No reference for '%s' — snapshot saved as baseline\n", name);
        // Copy as reference
        FILE* src = fopen(snap_path, "rb");
        if (src) {
            ref = fopen(ref_path, "wb");
            if (ref) {
                char buf[4096];
                size_t n;
                while ((n = fread(buf, 1, sizeof(buf), src)) > 0)
                    fwrite(buf, 1, n, ref);
                fclose(ref);
            }
            fclose(src);
        }
        return true;
    }
    fclose(ref);

    // Compare against reference
    WavComparison cmp = compare_wavs(snap_path, ref_path);
    if (!cmp.valid) {
        printf("    [ERROR] Could not compare '%s'\n", name);
        return false;
    }
    bool pass = cmp.rms_diff < tolerance_rms;
    printf("    [%s] '%s': rms_diff=%.4f corr=%.4f level_ratio=%.2f\n",
           pass ? "MATCH" : "DRIFT", name, cmp.rms_diff, cmp.correlation, cmp.level_ratio);
    return pass;
}

bool test_clock() {
    printf("\n=== Test: Clock pulse accuracy ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);

    GraphUnit clock_unit = {};
    clock_unit.type = UNIT_CLOCK;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    int total_ticks = 0, total_beats = 0;
    const int total_frames = 48000; // 1 second

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            if (clock_unit.output_buffers[0][i] > 0.5f) total_ticks++;
            if (clock_unit.output_buffers[1][i] > 0.5f) total_beats++;
        }
    }

    printf("Ticks in 1 second: %d (expected 48)\n", total_ticks);
    printf("Beats in 1 second: %d (expected 2)\n", total_beats);
    bool pass = (total_ticks == 48 && total_beats == 2);
    printf("Clock test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool test_grids() {
    printf("\n=== Test: Grids drum triggers ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);
    engine->grids_bypass.store(0);
    engine->grids_x.store(0.5f);
    engine->grids_y.store(0.5f);
    engine->grids_density_kick.store(0.8f);
    engine->grids_density_snare.store(0.8f);
    engine->grids_density_hat.store(0.8f);

    GraphUnit clock_unit = {};
    clock_unit.type = UNIT_CLOCK;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    GraphUnit grids_unit = {};
    grids_unit.type = UNIT_GRIDS;
    grids_unit.enabled = true;
    unit_init(&grids_unit, 48000.0f);

    // Wire clock→grids: clock OPORT_OUT (tick) → grids IPORT_INPUT_A
    grids_unit.inputs[IPORT_INPUT_A].sources[0] = clock_unit.output_buffers[OPORT_OUT];
    grids_unit.inputs[IPORT_INPUT_A].num_sources = 1;
    grids_unit.inputs[IPORT_INPUT_B].sources[0] = clock_unit.output_buffers[OPORT_OUT_RIGHT];
    grids_unit.inputs[IPORT_INPUT_B].num_sources = 1;

    int kick = 0, snare = 0, hat = 0;
    const int total_frames = 48000 * 2; // 2 seconds
    bool prev_k = false, prev_s = false, prev_h = false;

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);
        port_prepare(&grids_unit.inputs[IPORT_INPUT_A], chunk, 48000.0f);
        port_prepare(&grids_unit.inputs[IPORT_INPUT_B], chunk, 48000.0f);
        unit_process_grids(&grids_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            bool k = grids_unit.output_buffers[OPORT_OUT][i] > 0.5f;
            bool s = grids_unit.output_buffers[OPORT_OUT_RIGHT][i] > 0.5f;
            bool h = grids_unit.output_buffers[OPORT_AUX][i] > 0.5f;
            if (k && !prev_k) kick++;
            if (s && !prev_s) snare++;
            if (h && !prev_h) hat++;
            prev_k = k; prev_s = s; prev_h = h;
        }
    }

    printf("Kick: %d  Snare: %d  Hat: %d\n", kick, snare, hat);
    bool pass = kick > 0 && snare > 0 && hat > 0;
    printf("Grids test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool test_marbles() {
    printf("\n=== Test: Marbles random sequencer ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);
    engine->marbles_bypass.store(0);

    // Configure Marbles parameters
    engine->marbles_t_rate.store(0.0f);       // default rate
    engine->marbles_t_bias.store(0.5f);       // balanced probability
    engine->marbles_t_jitter.store(0.0f);     // no jitter
    engine->marbles_t_model.store(0);         // complementary Bernoulli
    engine->marbles_t_range.store(1);         // 1x range
    engine->marbles_x_spread.store(0.5f);     // medium spread
    engine->marbles_x_bias.store(0.5f);       // centered
    engine->marbles_x_steps.store(0.5f);      // medium quantization
    engine->marbles_deja_vu.store(0.0f);      // no looping
    engine->marbles_deja_vu_length.store(8);

    GraphUnit clock_unit = {};
    clock_unit.type = UNIT_CLOCK;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    GraphUnit marbles_unit = {};
    marbles_unit.type = UNIT_MARBLES;
    marbles_unit.enabled = true;
    unit_init(&marbles_unit, 48000.0f);

    // Wire clock→marbles: clock OPORT_OUT (tick) → marbles IPORT_INPUT_A
    marbles_unit.inputs[IPORT_INPUT_A].sources[0] = clock_unit.output_buffers[OPORT_OUT];
    marbles_unit.inputs[IPORT_INPUT_A].num_sources = 1;

    int gate_transitions = 0;
    float cv1_min = 1e9f, cv1_max = -1e9f;
    float cv2_min = 1e9f, cv2_max = -1e9f;
    bool prev_gate = false;
    const int total_frames = 48000 * 2; // 2 seconds

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);

        // Process clock first
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);

        // Prepare marbles input from clock output
        port_prepare(&marbles_unit.inputs[IPORT_INPUT_A], chunk, 48000.0f);

        // Process marbles
        unit_process_marbles(&marbles_unit, engine, chunk, 48000.0f);

        // Count gate rising edges and track CV ranges
        for (int i = 0; i < chunk; i++) {
            bool gate = marbles_unit.output_buffers[OPORT_OUT][i] > 0.5f;
            if (gate && !prev_gate) gate_transitions++;
            prev_gate = gate;

            float cv1 = marbles_unit.output_buffers[OPORT_OUT_RIGHT][i];
            float cv2 = marbles_unit.output_buffers[OPORT_AUX][i];
            if (cv1 < cv1_min) cv1_min = cv1;
            if (cv1 > cv1_max) cv1_max = cv1;
            if (cv2 < cv2_min) cv2_min = cv2;
            if (cv2 > cv2_max) cv2_max = cv2;
        }
    }

    printf("Gate transitions: %d\n", gate_transitions);
    printf("CV1 range: [%.4f, %.4f]\n", cv1_min, cv1_max);
    printf("CV2 range: [%.4f, %.4f]\n", cv2_min, cv2_max);

    // Verify: should have gate transitions from TGenerator
    bool gate_pass = gate_transitions > 0;
    // Verify: CV output should have some range (not stuck at zero)
    float cv1_range = cv1_max - cv1_min;
    bool cv_pass = cv1_range > 0.01f;

    printf("Gate test: %s (%d transitions)\n", gate_pass ? "PASS" : "FAIL", gate_transitions);
    printf("CV range test: %s (range=%.4f)\n", cv_pass ? "PASS" : "FAIL", cv1_range);

    bool pass = gate_pass && cv_pass;
    printf("Marbles test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool test_looper() {
    printf("\n=== Test: Looper record/play ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit looper_unit = {};
    looper_unit.type = UNIT_LOOPER;
    looper_unit.enabled = true;
    unit_init(&looper_unit, 48000.0f);

    const int record_frames = 12000; // 0.25 seconds
    engine->looper_quantize.store(0); // immediate (no beat sync for test simplicity)
    engine->looper_requested_state.store(1); // record

    // Phase 1: Record a sine tone
    for (int offset = 0; offset < record_frames; offset += 128) {
        int chunk = std::min(128, record_frames - offset);
        for (int i = 0; i < chunk; i++) {
            float phase = static_cast<float>(offset + i) / 48000.0f;
            float val = std::sin(phase * 440.0f * 2.0f * 3.14159f) * 0.5f;
            looper_unit.inputs[IPORT_INPUT_A].buffer[i] = val;
            looper_unit.inputs[IPORT_INPUT_B].buffer[i] = val;
            looper_unit.inputs[IPORT_INPUT_C].buffer[i] = 0.0f;
        }
        unit_process_looper(&looper_unit, engine, chunk, 48000.0f);
    }

    printf("Recorded %d samples (loop length: %d)\n", record_frames, engine->looper_length);

    // Phase 2: Switch to play, feed silence, verify loop plays back
    engine->looper_requested_state.store(2); // play
    float max_playback = 0.0f;

    for (int offset = 0; offset < record_frames; offset += 128) {
        int chunk = std::min(128, record_frames - offset);
        std::memset(looper_unit.inputs[IPORT_INPUT_A].buffer, 0, chunk * sizeof(float));
        std::memset(looper_unit.inputs[IPORT_INPUT_B].buffer, 0, chunk * sizeof(float));
        std::memset(looper_unit.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));

        unit_process_looper(&looper_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            float v = std::fabs(looper_unit.output_buffers[OPORT_OUT][i]);
            if (v > max_playback) max_playback = v;
        }
    }

    printf("Max playback amplitude: %.4f\n", max_playback);
    bool pass = engine->looper_length == record_frames && max_playback > 0.1f;
    printf("Looper test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

bool test_voice_coupling() {
    printf("\n=== Test: Voice coupling ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);

    engine->voice_params[1].active.store(1);
    engine->voice_params[1].tune.store(67.0f);
    engine->voice_params[1].gate.store(0);
    engine->voice_params[1].ever_triggered.store(1);
    engine->voice_params[1].engine_index.store(-1);

    engine->coupling_depth.store(0.5f);

    GraphUnit v0_unit = {};
    v0_unit.type = UNIT_PLAITS;
    v0_unit.enabled = true;
    v0_unit.state.module.index = 0;
    unit_init(&v0_unit, 48000.0f);

    for (int i = 0; i < 200; i++) {
        unit_process_plaits(&v0_unit, engine, 128, 48000.0f);
    }

    float env0 = engine->voice_envelope[0];
    float level0 = engine->voice_levels[0].load(std::memory_order_relaxed);
    printf("Voice 0 envelope: %.4f (voice_level: %.4f)\n", env0, level0);
    bool pass = env0 > 0.001f;
    printf("Coupling test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

bool test_fm_modulation() {
    printf("\n=== Test: FM modulation ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);

    engine->voice_params[1].active.store(1);
    engine->voice_params[1].tune.store(67.0f);
    engine->voice_params[1].gate.store(1);
    engine->voice_params[1].ever_triggered.store(1);
    engine->voice_params[1].engine_index.store(-1);

    engine->mod_source[0].store(1); // VOICE_FM
    engine->fm_depth[0].store(0.5f);

    GraphUnit v0 = {}, v1 = {};
    v0.type = UNIT_PLAITS; v0.enabled = true;
    v1.type = UNIT_PLAITS; v1.enabled = true;
    unit_init(&v0, 48000.0f);
    unit_init(&v1, 48000.0f);
    v0.state.module.index = 0;
    v1.state.module.index = 1;

    for (int i = 0; i < 10; i++) {
        unit_process_plaits(&v0, engine, 128, 48000.0f);
        unit_process_plaits(&v1, engine, 128, 48000.0f);
    }

    float out0 = engine->voice_last_output[0];
    float out1 = engine->voice_last_output[1];
    printf("Voice 0 last output: %.4f, Voice 1: %.4f\n", out0, out1);
    bool pass = out0 > 0.001f && out1 > 0.001f;
    printf("FM modulation test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

bool test_bender() {
    printf("\n=== Test: Bender CV + audio ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit bender_unit = {};
    bender_unit.type = UNIT_BENDER;
    bender_unit.enabled = true;
    unit_init(&bender_unit, 48000.0f);

    // Apply a bend
    engine->bend_amount.store(0.5f);

    float max_pitch = 0.0f, max_audio = 0.0f;
    for (int offset = 0; offset < 48000; offset += 128) {
        int chunk = std::min(128, 48000 - offset);
        unit_process_bender(&bender_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float p = std::fabs(bender_unit.output_buffers[OPORT_OUT][i]);
            float a = std::fabs(bender_unit.output_buffers[OPORT_AUX][i]);
            if (p > max_pitch) max_pitch = p;
            if (a > max_audio) max_audio = a;
        }
    }

    printf("Max pitch CV: %.4f, Max audio: %.4f\n", max_pitch, max_audio);
    bool pass = max_pitch > 0.01f && max_audio > 0.0001f;
    printf("Bender test: %s\n", pass ? "PASS" : "FAIL");

    // Release bend — should trigger spring
    engine->bend_amount.store(0.0f);
    float max_spring = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_bender(&bender_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(bender_unit.output_buffers[OPORT_AUX][i]);
            if (a > max_spring) max_spring = a;
        }
    }
    printf("Max spring audio after release: %.4f\n", max_spring);
    bool spring_pass = max_spring > 0.0001f;
    printf("Spring test: %s\n", spring_pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass && spring_pass;
}

bool test_per_string_bender() {
    printf("\n=== Test: Per-string bender ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit psb_unit = {};
    psb_unit.type = UNIT_PER_STRING_BENDER;
    psb_unit.enabled = true;
    unit_init(&psb_unit, 48000.0f);

    // Activate string 0 with bend
    engine->string_active[0].store(1);
    engine->string_bend[0].store(0.5f);
    engine->string_mix[0].store(0.5f);

    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_per_string_bender(&psb_unit, engine, chunk, 48000.0f);
    }

    // Check voice CVs
    float bend_cv = engine->voice_bend_cv[0];
    float mix_cv = engine->voice_mix_cv[0];
    printf("Voice 0 bend CV: %.4f semitones\n", bend_cv);
    printf("Voice 0 mix CV: %.4f\n", mix_cv);

    // Release string — should trigger pluck + spring
    engine->string_active[0].store(0);
    float max_audio = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_per_string_bender(&psb_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(psb_unit.output_buffers[OPORT_OUT][i]);
            if (a > max_audio) max_audio = a;
        }
    }

    printf("Max audio after release: %.4f\n", max_audio);
    bool pass = std::fabs(bend_cv) > 0.1f && mix_cv >= 0.99f && max_audio > 0.001f;
    printf("Per-string bender test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ═══════════════════════════════════════════════════════════════════════
// Voice Module Tests — polyphonic, lifecycle, idle detection
// ═══════════════════════════════════════════════════════════════════════

// Helper: process a voice unit for N frames, return max absolute output
static float render_voice(GraphUnit* unit, OrpheusEngine* engine, int total_frames, float sr = 48000.0f) {
    float max_amp = 0.0f;
    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_plaits(unit, engine, chunk, sr);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(unit->output_buffers[OPORT_OUT][i]);
            if (a > max_amp) max_amp = a;
        }
    }
    return max_amp;
}

// Helper: set up a voice unit for a given voice index
static void setup_voice_unit(GraphUnit* unit, int voice_idx) {
    std::memset(unit, 0, sizeof(GraphUnit));
    unit->type = UNIT_PLAITS;
    unit->enabled = true;
    unit->state.module.index = voice_idx;
    unit_init(unit, 48000.0f);
}

bool test_single_voice_engine0() {
    printf("\n=== Test: Single voice Engine 0 (VA) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Voice 0: Engine 0 (triangle/square), MIDI note 60 (C4)
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);  // Engine 0
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    float amp = render_voice(&v0, engine, 12000); // 0.25s
    printf("  Engine 0 gate=ON: peak=%.4f %s\n", amp, amp > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp > 0.01f);

    // Gate off — envelope should release, eventually go silent
    engine->voice_params[0].gate.store(0);
    float release_amp = render_voice(&v0, engine, 48000); // 1s of release
    printf("  Engine 0 after 1s release: peak=%.6f\n", release_amp);

    // Gate back on — must produce sound again (no stuck silence)
    engine->voice_params[0].gate.store(1);
    float retrigger_amp = render_voice(&v0, engine, 12000);
    printf("  Engine 0 re-trigger: peak=%.4f %s\n", retrigger_amp, retrigger_amp > 0.01f ? "OK" : "FAIL");
    all_pass &= (retrigger_amp > 0.01f);

    printf("Single voice Engine 0 test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_single_voice_plaits_engines() {
    printf("\n=== Test: Single voice Plaits engines ===\n");
    bool all_pass = true;

    // Test engines 0 (VA analog), 1 (waveshaper), 2 (FM), 6 (chord), 8 (wavetable)
    int engines_to_test[] = {0, 1, 2, 6, 8};
    int num_engines = sizeof(engines_to_test) / sizeof(engines_to_test[0]);

    for (int e = 0; e < num_engines; e++) {
        int eng = engines_to_test[e];
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);

        engine->voice_params[0].active.store(1);
        engine->voice_params[0].ever_triggered.store(1);
        engine->voice_params[0].engine_index.store(eng);
        engine->voice_params[0].tune.store(60.0f);
        engine->voice_params[0].gate.store(1);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);

        GraphUnit v0;
        setup_voice_unit(&v0, 0);

        float amp = render_voice(&v0, engine, 24000); // 0.5s
        bool pass = amp > 0.001f;
        printf("  Plaits engine %2d: peak=%.4f %s\n", eng, amp, pass ? "OK" : "FAIL");
        all_pass &= pass;

        orpheus_engine_destroy(engine);
    }

    printf("Plaits engines test: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

bool test_polyphonic_voices() {
    printf("\n=== Test: Polyphonic 8 voices ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Activate all 8 main voices with different notes, all Engine 0
    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};
    GraphUnit units[kNumMainVoices];

    for (int v = 0; v < kNumMainVoices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(-1); // Engine 0
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        setup_voice_unit(&units[v], v);
    }

    // Render all voices together for 0.5s
    float voice_peaks[kNumMainVoices] = {};
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        for (int v = 0; v < kNumMainVoices; v++) {
            unit_process_plaits(&units[v], engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(units[v].output_buffers[OPORT_OUT][i]);
                if (a > voice_peaks[v]) voice_peaks[v] = a;
            }
        }
    }

    int silent_count = 0;
    for (int v = 0; v < kNumMainVoices; v++) {
        bool ok = voice_peaks[v] > 0.01f;
        if (!ok) silent_count++;
        printf("  Voice %d (note %.0f): peak=%.4f %s\n", v, notes[v], voice_peaks[v], ok ? "OK" : "SILENT!");
        all_pass &= ok;
    }

    printf("Polyphonic test: %d/%d voices producing sound — %s\n",
           kNumMainVoices - silent_count, kNumMainVoices, all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_polyphonic_plaits_voices() {
    printf("\n=== Test: Polyphonic 8 Plaits voices ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Activate all 8 main voices with Plaits engine 0 (virtual analog)
    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};
    GraphUnit units[kNumMainVoices];

    for (int v = 0; v < kNumMainVoices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(0); // Plaits VA
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.5f);
        setup_voice_unit(&units[v], v);
    }

    // Render for 0.5s
    float voice_peaks[kNumMainVoices] = {};
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        for (int v = 0; v < kNumMainVoices; v++) {
            unit_process_plaits(&units[v], engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(units[v].output_buffers[OPORT_OUT][i]);
                if (a > voice_peaks[v]) voice_peaks[v] = a;
            }
        }
    }

    int silent_count = 0;
    for (int v = 0; v < kNumMainVoices; v++) {
        bool ok = voice_peaks[v] > 0.001f;
        if (!ok) silent_count++;
        printf("  Voice %d (Plaits, note %.0f): peak=%.4f %s\n", v, notes[v], voice_peaks[v], ok ? "OK" : "SILENT!");
        all_pass &= ok;
    }

    printf("Polyphonic Plaits test: %d/%d voices — %s\n",
           kNumMainVoices - silent_count, kNumMainVoices, all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_voice_gate_retrigger() {
    printf("\n=== Test: Voice gate retrigger cycle ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0); // Plaits VA
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.3f); // faster decay

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    // 3 gate cycles: on-off-on-off-on-off
    for (int cycle = 0; cycle < 3; cycle++) {
        // Gate ON
        engine->voice_params[0].gate.store(1);
        float on_amp = render_voice(&v0, engine, 12000); // 0.25s

        // Gate OFF — let decay
        engine->voice_params[0].gate.store(0);
        render_voice(&v0, engine, 48000); // 1s full decay

        // Check idle state
        float idle_level = engine->voice_levels[0].load(std::memory_order_relaxed);

        bool on_pass = on_amp > 0.001f;
        printf("  Cycle %d: gate-ON peak=%.4f, idle level=%.6f %s\n",
               cycle, on_amp, idle_level, on_pass ? "OK" : "FAIL");
        all_pass &= on_pass;
    }

    printf("Gate retrigger test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_voice_hold_without_gate() {
    printf("\n=== Test: Voice hold (no gate) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Engine 0: hold should produce sound even without gate
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1); // Engine 0
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(0); // no gate
    engine->voice_hold_level[0].store(0.8f); // high hold

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    float amp = render_voice(&v0, engine, 24000);
    printf("  Engine 0 hold=0.8 no gate: peak=%.4f %s\n", amp, amp > 0.01f ? "OK" : "FAIL");
    bool pass = amp > 0.01f;

    // Also test with Plaits engine
    OrpheusEngine* engine2 = orpheus_engine_create(48000.0f);
    engine2->voice_params[0].active.store(1);
    engine2->voice_params[0].ever_triggered.store(1);
    engine2->voice_params[0].engine_index.store(0); // Plaits VA
    engine2->voice_params[0].tune.store(60.0f);
    engine2->voice_params[0].gate.store(0);
    engine2->voice_params[0].harmonics.store(0.5f);
    engine2->voice_params[0].timbre.store(0.5f);
    engine2->voice_params[0].morph.store(0.5f);
    engine2->voice_params[0].decay.store(0.5f);
    engine2->voice_hold_level[0].store(0.8f);

    GraphUnit v0p;
    setup_voice_unit(&v0p, 0);

    float amp2 = render_voice(&v0p, engine2, 24000);
    printf("  Plaits hold=0.8 no gate: peak=%.4f %s\n", amp2, amp2 > 0.001f ? "OK" : "FAIL");
    pass &= (amp2 > 0.001f);

    printf("Hold without gate test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    orpheus_engine_destroy(engine2);
    return pass;
}

bool test_voice_activation_lifecycle() {
    printf("\n=== Test: Voice activation lifecycle ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    // Step 1: Voice not active — should produce silence
    engine->voice_params[0].active.store(0);
    engine->voice_params[0].gate.store(1);
    float amp_inactive = render_voice(&v0, engine, 6000);
    printf("  Not active + gate=ON: peak=%.6f %s\n", amp_inactive,
           amp_inactive < 0.001f ? "OK (silent)" : "FAIL (unexpected sound)");
    all_pass &= (amp_inactive < 0.001f);

    // Step 2: Activate via set_voice_active API (sets ever_triggered=1)
    orpheus_engine_set_voice_active(engine, 0, 1);
    engine->voice_params[0].engine_index.store(-1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    float amp_activated = render_voice(&v0, engine, 12000);
    printf("  After set_voice_active + gate=ON: peak=%.4f %s\n", amp_activated,
           amp_activated > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_activated > 0.01f);

    // Step 3: Deactivate — should go silent
    orpheus_engine_set_voice_active(engine, 0, 0);
    float amp_deactivated = render_voice(&v0, engine, 6000);
    printf("  After deactivate: peak=%.6f %s\n", amp_deactivated,
           amp_deactivated < 0.001f ? "OK (silent)" : "FAIL");
    all_pass &= (amp_deactivated < 0.001f);

    // Step 4: Reactivate — must produce sound again
    orpheus_engine_set_voice_active(engine, 0, 1);
    engine->voice_params[0].gate.store(1);
    float amp_reactivated = render_voice(&v0, engine, 12000);
    printf("  After reactivate + gate=ON: peak=%.4f %s\n", amp_reactivated,
           amp_reactivated > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_reactivated > 0.01f);

    printf("Activation lifecycle test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_engine_switch_while_playing() {
    printf("\n=== Test: Engine switch while voice active ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.5f);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    // Start with Engine 0
    engine->voice_params[0].engine_index.store(-1);
    float amp_e0 = render_voice(&v0, engine, 12000);
    printf("  Engine 0: peak=%.4f %s\n", amp_e0, amp_e0 > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_e0 > 0.01f);

    // Switch to Plaits engine 0 (VA analog)
    orpheus_engine_set_voice_engine(engine, 0, 0);
    float amp_p0 = render_voice(&v0, engine, 24000);
    printf("  Switch to Plaits 0: peak=%.4f %s\n", amp_p0, amp_p0 > 0.001f ? "OK" : "FAIL");
    all_pass &= (amp_p0 > 0.001f);

    // Switch to Plaits engine 2 (FM)
    orpheus_engine_set_voice_engine(engine, 0, 2);
    float amp_p2 = render_voice(&v0, engine, 24000);
    printf("  Switch to Plaits 2 (FM): peak=%.4f %s\n", amp_p2, amp_p2 > 0.001f ? "OK" : "FAIL");
    all_pass &= (amp_p2 > 0.001f);

    // Switch back to Engine 0
    orpheus_engine_set_voice_engine(engine, 0, -1);
    float amp_back = render_voice(&v0, engine, 12000);
    printf("  Back to Engine 0: peak=%.4f %s\n", amp_back, amp_back > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_back > 0.01f);

    printf("Engine switch test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_idle_detection_recovery() {
    printf("\n=== Test: Idle detection recovery ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Test with Plaits — the tricky idle path checks voice_levels
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0); // Plaits VA
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.2f); // fast decay

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    // Gate on, render, gate off, let fully decay to idle
    engine->voice_params[0].gate.store(1);
    render_voice(&v0, engine, 12000);
    engine->voice_params[0].gate.store(0);

    // Long silence to trigger idle detection (voice_levels → 0)
    render_voice(&v0, engine, 96000); // 2 seconds

    float idle_level = engine->voice_levels[0].load(std::memory_order_relaxed);
    printf("  After 2s decay, voice_level=%.8f (idle=%s)\n",
           idle_level, idle_level < 0.0001f ? "yes" : "no");

    // Now gate ON again — the voice MUST recover from idle
    engine->voice_params[0].gate.store(1);
    float recovery_amp = render_voice(&v0, engine, 24000);
    bool recover_pass = recovery_amp > 0.001f;
    printf("  Recovery after idle: peak=%.4f %s\n", recovery_amp, recover_pass ? "OK" : "FAIL");
    all_pass &= recover_pass;

    // Test Engine 0 idle recovery too
    OrpheusEngine* engine2 = orpheus_engine_create(48000.0f);
    engine2->voice_params[0].active.store(1);
    engine2->voice_params[0].ever_triggered.store(1);
    engine2->voice_params[0].engine_index.store(-1);
    engine2->voice_params[0].tune.store(60.0f);

    GraphUnit v0e;
    setup_voice_unit(&v0e, 0);

    engine2->voice_params[0].gate.store(1);
    render_voice(&v0e, engine2, 12000);
    engine2->voice_params[0].gate.store(0);
    render_voice(&v0e, engine2, 96000); // full decay

    float idle2 = engine2->voice_levels[0].load(std::memory_order_relaxed);
    printf("  Engine 0 after 2s decay, voice_level=%.8f\n", idle2);

    engine2->voice_params[0].gate.store(1);
    float recovery2 = render_voice(&v0e, engine2, 12000);
    bool recover2_pass = recovery2 > 0.01f;
    printf("  Engine 0 recovery after idle: peak=%.4f %s\n", recovery2, recover2_pass ? "OK" : "FAIL");
    all_pass &= recover2_pass;

    printf("Idle detection recovery test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    orpheus_engine_destroy(engine2);
    return all_pass;
}

bool test_full_engine_render() {
    printf("\n=== Test: Full engine render (no graph) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Activate voice 0 and set gate — the OLD test was missing set_voice_active!
    orpheus_engine_set_voice_active(engine, 0, 1);
    orpheus_engine_set_voice_tune(engine, 0, 60.0f);
    orpheus_engine_set_voice_gate(engine, 0, 1);

    const int sample_rate = 48000;
    const int total_frames = sample_rate; // 1 second
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

bool test_polyphonic_engine_render() {
    printf("\n=== Test: Polyphonic full engine render ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Activate 4 voices with different notes
    float notes[] = {48.0f, 55.0f, 60.0f, 67.0f};
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(engine, v, 1);
        orpheus_engine_set_voice_tune(engine, v, notes[v]);
        orpheus_engine_set_voice_gate(engine, v, 1);
    }

    const int total_frames = 24000; // 0.5s
    std::vector<float> buffer(total_frames * 2, 0.0f);

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    // Check per-voice levels
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

// ═══════════════════════════════════════════════════════════════════════
// Mod Source Routing Tests
// ═══════════════════════════════════════════════════════════════════════

bool test_mod_source_routing() {
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

        if (configs[c].mod_source == 1) { // FM: need partner voice output
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
        } else if (configs[c].mod_source == 2) { // LFO
            engine->lfo_output_value = 0.7f;
        } else if (configs[c].mod_source == 3) { // FLUX
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
    // At least 4 of 6 pairs should differ
    bool pass = num_different >= 4;
    printf("Mod source routing: %d/6 pairs different — %s\n", num_different, pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════════
// Effects Bypass + Active Tests
// ═══════════════════════════════════════════════════════════════════════

bool test_effects_bypass_passthrough() {
    printf("\n=== Test: Effects bypass passthrough ===\n");
    bool all_pass = true;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const int test_frames = 4800; // 0.1s
    const float freq = 440.0f;

    auto gen_sine = [&](GraphPort& port, int offset, int chunk) {
        for (int i = 0; i < chunk; i++) {
            float t = (float)(offset + i) / 48000.0f;
            port.buffer[i] = std::sin(t * freq * 2.0f * 3.14159265f) * 0.3f;
        }
    };

    auto check_bypass = [&](const char* name, auto process_fn, auto setup_fn) {
        GraphUnit u = {};
        setup_fn(u);
        float in_rms = 0.0f, out_rms = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            for (int i = 0; i < chunk; i++) {
                float t = (float)(offset + i) / 48000.0f;
                float val = std::sin(t * freq * 2.0f * 3.14159265f) * 0.3f;
                in_rms += val * val;
            }
            process_fn(u, engine, offset, chunk);
            for (int i = 0; i < chunk; i++)
                out_rms += u.output_buffers[OPORT_OUT][i] * u.output_buffers[OPORT_OUT][i];
        }
        in_rms = std::sqrt(in_rms / test_frames);
        out_rms = std::sqrt(out_rms / test_frames);
        float ratio = (in_rms > 0.001f) ? out_rms / in_rms : 0.0f;
        bool pass = ratio > 0.9f && ratio < 1.1f;
        printf("  %s bypass: in_rms=%.4f out_rms=%.4f ratio=%.3f %s\n",
               name, in_rms, out_rms, ratio, pass ? "OK" : "FAIL");
        return pass;
    };

    // Clouds bypass
    engine->clouds_bypass.store(1);
    all_pass &= check_bypass("Clouds",
        [&](GraphUnit& u, OrpheusEngine* e, int offset, int chunk) {
            gen_sine(u.inputs[IPORT_INPUT_A], offset, chunk);
            gen_sine(u.inputs[IPORT_INPUT_B], offset, chunk);
            unit_process_clouds(&u, e, chunk, 48000.0f);
        },
        [](GraphUnit& u) { u.type = UNIT_CLOUDS; u.enabled = true; unit_init(&u, 48000.0f); }
    );

    // Rings bypass
    engine->rings_bypass.store(1);
    all_pass &= check_bypass("Rings",
        [&](GraphUnit& u, OrpheusEngine* e, int offset, int chunk) {
            gen_sine(u.inputs[IPORT_INPUT], offset, chunk);
            unit_process_rings(&u, e, chunk, 48000.0f);
        },
        [](GraphUnit& u) { u.type = UNIT_RINGS; u.enabled = true; unit_init(&u, 48000.0f); }
    );

    // Warps bypass
    engine->warps_bypass.store(1);
    all_pass &= check_bypass("Warps",
        [&](GraphUnit& u, OrpheusEngine* e, int offset, int chunk) {
            gen_sine(u.inputs[IPORT_INPUT_A], offset, chunk);
            gen_sine(u.inputs[IPORT_INPUT_B], offset, chunk);
            unit_process_warps(&u, e, chunk, 48000.0f);
        },
        [](GraphUnit& u) { u.type = UNIT_WARPS; u.enabled = true; unit_init(&u, 48000.0f); }
    );

    // Delay bypass
    engine->delay_bypass.store(1);
    all_pass &= check_bypass("Delay",
        [&](GraphUnit& u, OrpheusEngine* e, int offset, int chunk) {
            gen_sine(u.inputs[IPORT_INPUT_A], offset, chunk);
            gen_sine(u.inputs[IPORT_INPUT_B], offset, chunk);
            std::memset(u.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));
            unit_process_dual_delay(&u, e, chunk, 48000.0f);
        },
        [](GraphUnit& u) { u.type = UNIT_DUAL_DELAY; u.enabled = true; unit_init(&u, 48000.0f); }
    );

    // Reverb bypass — zeroes output (additive effect)
    engine->reverb_bypass.store(1);
    {
        GraphUnit u = {};
        u.type = UNIT_REVERB; u.enabled = true;
        unit_init(&u, 48000.0f);
        float out_peak = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            gen_sine(u.inputs[IPORT_INPUT_A], offset, chunk);
            gen_sine(u.inputs[IPORT_INPUT_B], offset, chunk);
            unit_process_reverb(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > out_peak) out_peak = a;
            }
        }
        bool pass = out_peak < 0.001f;
        printf("  Reverb bypass: out_peak=%.6f %s\n", out_peak, pass ? "OK (silent)" : "FAIL");
        all_pass &= pass;
    }

    printf("Effects bypass test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_effects_active() {
    printf("\n=== Test: Effects active processing ===\n");
    bool all_pass = true;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const int test_frames = 12000;

    // Clouds active
    {
        engine->clouds_bypass.store(0);
        engine->clouds_dry_wet.store(1.0f);
        engine->clouds_position.store(0.5f);
        engine->clouds_size.store(0.5f);
        engine->clouds_density.store(0.5f);
        engine->clouds_texture.store(0.5f);
        GraphUnit u = {};
        u.type = UNIT_CLOUDS; u.enabled = true;
        unit_init(&u, 48000.0f);
        float out_rms = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            for (int i = 0; i < chunk; i++) {
                float t = (float)(offset + i) / 48000.0f;
                float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
                u.inputs[IPORT_INPUT_A].buffer[i] = val;
                u.inputs[IPORT_INPUT_B].buffer[i] = val;
            }
            unit_process_clouds(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++)
                out_rms += u.output_buffers[OPORT_OUT][i] * u.output_buffers[OPORT_OUT][i];
        }
        out_rms = std::sqrt(out_rms / test_frames);
        bool pass = out_rms > 0.001f;
        printf("  Clouds active: rms=%.4f %s\n", out_rms, pass ? "OK" : "FAIL");
        all_pass &= pass;
    }

    // Reverb active — check for tail after input stops
    {
        engine->reverb_bypass.store(0);
        engine->reverb_amount.store(0.5f);
        engine->reverb_time.store(0.7f);
        GraphUnit u = {};
        u.type = UNIT_REVERB; u.enabled = true;
        unit_init(&u, 48000.0f);
        float peak_during = 0.0f, peak_tail = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            for (int i = 0; i < chunk; i++) {
                float t = (float)(offset + i) / 48000.0f;
                u.inputs[IPORT_INPUT_A].buffer[i] = std::sin(t * 440.0f * 6.283185f) * 0.3f;
                u.inputs[IPORT_INPUT_B].buffer[i] = u.inputs[IPORT_INPUT_A].buffer[i];
            }
            unit_process_reverb(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > peak_during) peak_during = a;
            }
        }
        // Feed silence, check for reverb tail
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            std::memset(u.inputs[IPORT_INPUT_A].buffer, 0, chunk * sizeof(float));
            std::memset(u.inputs[IPORT_INPUT_B].buffer, 0, chunk * sizeof(float));
            unit_process_reverb(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > peak_tail) peak_tail = a;
            }
        }
        bool pass = peak_during > 0.01f && peak_tail > 0.001f;
        printf("  Reverb active: peak=%.4f tail=%.4f %s\n", peak_during, peak_tail, pass ? "OK" : "FAIL");
        all_pass &= pass;
    }

    // Delay active — impulse then check echo
    {
        engine->delay_bypass.store(0);
        engine->delay_mix.store(0.5f);
        engine->delay_feedback.store(0.3f);
        engine->delay_time_1.store(0.1f);
        engine->delay_time_2.store(0.15f);
        GraphUnit u = {};
        u.type = UNIT_DUAL_DELAY; u.enabled = true;
        unit_init(&u, 48000.0f);
        // Impulse
        for (int i = 0; i < 128; i++) {
            u.inputs[IPORT_INPUT_A].buffer[i] = (i == 0) ? 0.5f : 0.0f;
            u.inputs[IPORT_INPUT_B].buffer[i] = (i == 0) ? 0.5f : 0.0f;
            u.inputs[IPORT_INPUT_C].buffer[i] = 0.0f;
        }
        unit_process_dual_delay(&u, engine, 128, 48000.0f);
        // Silence — look for echo
        float echo_peak = 0.0f;
        for (int offset = 0; offset < 24000; offset += 128) {
            int chunk = std::min(128, 24000 - offset);
            std::memset(u.inputs[IPORT_INPUT_A].buffer, 0, chunk * sizeof(float));
            std::memset(u.inputs[IPORT_INPUT_B].buffer, 0, chunk * sizeof(float));
            std::memset(u.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));
            unit_process_dual_delay(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > echo_peak) echo_peak = a;
            }
        }
        bool pass = echo_peak > 0.01f;
        printf("  Delay echo: peak=%.4f %s\n", echo_peak, pass ? "OK" : "FAIL");
        all_pass &= pass;
    }

    printf("Effects active test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

// ═══════════════════════════════════════════════════════════════════════
// Graph Integration Tests (manually-built mini-graphs)
// ═══════════════════════════════════════════════════════════════════════

bool test_graph_single_voice() {
    printf("\n=== Test: Graph — single voice ===\n");
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

    OrpheusGraph* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = 48000.0f;
    graph->unit_count = 3;
    graph->exec_count = 3;
    graph->exec_order[0] = 0; graph->exec_order[1] = 1; graph->exec_order[2] = 2;
    graph->master_out_index = 2;

    graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
    graph->units[0].enabled = true;
    unit_init(&graph->units[0], 48000.0f);
    graph->units[0].state.module.index = 0; // AFTER init (memset zeroes state)

    graph->units[1].type = UNIT_HARD_CLIP; graph->units[1].id = 1;
    graph->units[1].enabled = true; unit_init(&graph->units[1], 48000.0f);
    graph->units[1].inputs[IPORT_INPUT].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT].num_sources = 1;

    graph->units[2].type = UNIT_MASTER_OUT; graph->units[2].id = 2;
    graph->units[2].enabled = true; unit_init(&graph->units[2], 48000.0f);
    graph->units[2].inputs[IPORT_INPUT_A].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[2].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

    float stereo[256];
    float max_amp = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        std::memset(stereo, 0, sizeof(stereo));
        orpheus_graph_process(graph, engine, stereo, chunk);
        for (int i = 0; i < chunk * 2; i++) {
            float a = std::fabs(stereo[i]);
            if (a > max_amp) max_amp = a;
        }
    }
    printf("  Graph single voice: peak=%.4f %s\n", max_amp, max_amp > 0.001f ? "OK" : "FAIL");
    bool pass = max_amp > 0.001f;
    printf("Graph single voice test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

bool test_graph_polyphonic() {
    printf("\n=== Test: Graph — 4-voice polyphonic ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f};
    for (int v = 0; v < 4; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(0);
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.5f);
    }

    OrpheusGraph* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = 48000.0f;
    graph->unit_count = 6;
    graph->exec_count = 6;
    for (int i = 0; i < 6; i++) graph->exec_order[i] = i;
    graph->master_out_index = 5;

    for (int v = 0; v < 4; v++) {
        graph->units[v].type = UNIT_PLAITS; graph->units[v].id = v;
        graph->units[v].enabled = true;
        unit_init(&graph->units[v], 48000.0f);
        graph->units[v].state.module.index = v; // AFTER init
    }

    // Clip: sum all 4 voices
    graph->units[4].type = UNIT_HARD_CLIP; graph->units[4].id = 4;
    graph->units[4].enabled = true; unit_init(&graph->units[4], 48000.0f);
    for (int v = 0; v < 4; v++)
        graph->units[4].inputs[IPORT_INPUT].sources[v] = graph->units[v].output_buffers[OPORT_OUT];
    graph->units[4].inputs[IPORT_INPUT].num_sources = 4;

    graph->units[5].type = UNIT_MASTER_OUT; graph->units[5].id = 5;
    graph->units[5].enabled = true; unit_init(&graph->units[5], 48000.0f);
    graph->units[5].inputs[IPORT_INPUT_A].sources[0] = graph->units[4].output_buffers[OPORT_OUT];
    graph->units[5].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[5].inputs[IPORT_INPUT_B].sources[0] = graph->units[4].output_buffers[OPORT_OUT];
    graph->units[5].inputs[IPORT_INPUT_B].num_sources = 1;

    float stereo[256];
    float max_amp = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        std::memset(stereo, 0, sizeof(stereo));
        orpheus_graph_process(graph, engine, stereo, chunk);
        for (int i = 0; i < chunk * 2; i++) {
            float a = std::fabs(stereo[i]);
            if (a > max_amp) max_amp = a;
        }
    }
    int producing = 0;
    for (int v = 0; v < 4; v++) {
        float level = engine->voice_levels[v].load(std::memory_order_relaxed);
        if (level > 0.001f) producing++;
        printf("  Voice %d (note %.0f): level=%.4f %s\n", v, notes[v], level, level > 0.001f ? "OK" : "SILENT!");
    }
    printf("  Mix peak: %.4f, %d/4 voices\n", max_amp, producing);
    bool pass = max_amp > 0.001f && producing >= 4;
    printf("Graph polyphonic test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

bool test_graph_effects_chain() {
    printf("\n=== Test: Graph — effects chain (bypass) ===\n");
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
    engine->clouds_bypass.store(1);
    engine->delay_bypass.store(1);

    OrpheusGraph* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = 48000.0f;
    graph->unit_count = 5;
    graph->exec_count = 5;
    for (int i = 0; i < 5; i++) graph->exec_order[i] = i;
    graph->master_out_index = 4;

    // voice → clouds(bypass) → delay(bypass) → clip → master
    graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
    graph->units[0].enabled = true;
    unit_init(&graph->units[0], 48000.0f);
    graph->units[0].state.module.index = 0;

    graph->units[1].type = UNIT_CLOUDS; graph->units[1].id = 1;
    graph->units[1].enabled = true; unit_init(&graph->units[1], 48000.0f);
    graph->units[1].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[1].inputs[IPORT_INPUT_B].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT_B].num_sources = 1;

    graph->units[2].type = UNIT_DUAL_DELAY; graph->units[2].id = 2;
    graph->units[2].enabled = true; unit_init(&graph->units[2], 48000.0f);
    graph->units[2].inputs[IPORT_INPUT_A].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[2].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT_RIGHT];
    graph->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

    graph->units[3].type = UNIT_HARD_CLIP; graph->units[3].id = 3;
    graph->units[3].enabled = true; unit_init(&graph->units[3], 48000.0f);
    graph->units[3].inputs[IPORT_INPUT].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
    graph->units[3].inputs[IPORT_INPUT].num_sources = 1;

    graph->units[4].type = UNIT_MASTER_OUT; graph->units[4].id = 4;
    graph->units[4].enabled = true; unit_init(&graph->units[4], 48000.0f);
    graph->units[4].inputs[IPORT_INPUT_A].sources[0] = graph->units[3].output_buffers[OPORT_OUT];
    graph->units[4].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[4].inputs[IPORT_INPUT_B].sources[0] = graph->units[2].output_buffers[OPORT_OUT_RIGHT];
    graph->units[4].inputs[IPORT_INPUT_B].num_sources = 1;

    float stereo[256];
    float max_l = 0.0f, max_r = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        std::memset(stereo, 0, sizeof(stereo));
        orpheus_graph_process(graph, engine, stereo, chunk);
        for (int i = 0; i < chunk; i++) {
            float l = std::fabs(stereo[i * 2]);
            float r = std::fabs(stereo[i * 2 + 1]);
            if (l > max_l) max_l = l;
            if (r > max_r) max_r = r;
        }
    }
    printf("  Effects chain: L=%.4f R=%.4f\n", max_l, max_r);
    bool pass = max_l > 0.001f && max_r > 0.001f;
    printf("Graph effects chain test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

// ═══════════════════════════════════════════════════════════════════════
// WAV Snapshot Scenarios (sound fingerprints for A/B comparison)
// ═══════════════════════════════════════════════════════════════════════

bool test_wav_snapshots() {
    printf("\n=== WAV Snapshot Scenarios ===\n");
    mkdir("test", 0755);
    mkdir("test/output", 0755);
    bool all_pass = true;
    const int sr = 48000;
    const char* dir = "test/output";

    // Scenario 1: Single voice C4, Plaits VA, 2s (gate 1s, release 1s)
    {
        printf("  Scenario: single_voice_c4\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        orpheus_engine_set_voice_active(engine, 0, 1);
        orpheus_engine_set_voice_tune(engine, 0, 60.0f);
        orpheus_engine_set_voice_gate(engine, 0, 1);
        engine->voice_params[0].engine_index.store(0);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);
        const int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr) orpheus_engine_set_voice_gate(engine, 0, 0);
            orpheus_engine_process(engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_single_voice_c4", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 2: 4-voice chord C-E-G-C'
    {
        printf("  Scenario: 4voice_chord\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        float chord[] = {60.0f, 64.0f, 67.0f, 72.0f};
        for (int v = 0; v < 4; v++) {
            orpheus_engine_set_voice_active(engine, v, 1);
            orpheus_engine_set_voice_tune(engine, v, chord[v]);
            orpheus_engine_set_voice_gate(engine, v, 1);
            engine->voice_params[v].engine_index.store(0);
            engine->voice_params[v].harmonics.store(0.5f);
            engine->voice_params[v].timbre.store(0.5f);
            engine->voice_params[v].morph.store(0.5f);
            engine->voice_params[v].decay.store(0.5f);
        }
        const int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr) for (int v = 0; v < 4; v++) orpheus_engine_set_voice_gate(engine, v, 0);
            orpheus_engine_process(engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_4voice_chord", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 3: Bender — bend up and release (pitch CV + spring audio)
    {
        printf("  Scenario: bender_sweep\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        GraphUnit u = {};
        u.type = UNIT_BENDER; u.enabled = true;
        unit_init(&u, 48000.0f);
        engine->bend_max_semitones.store(12.0f);
        engine->bend_spring_vol.store(0.4f);
        engine->bend_tension_vol.store(0.02f);
        const int total = sr * 3; // 3 seconds
        // Use aux output (audio) for the snapshot. Build stereo from pitch+audio.
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            // Bend up for 1s, hold 0.5s, release for 1.5s
            float t = (float)off / sr;
            if (t < 1.0f) engine->bend_amount.store(t); // ramp 0→1
            else if (t < 1.5f) engine->bend_amount.store(1.0f); // hold
            else engine->bend_amount.store(0.0f); // release
            unit_process_bender(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float pitch = u.output_buffers[OPORT_OUT][i];
                float audio = u.output_buffers[OPORT_AUX][i];
                buf[(off + i) * 2]     = pitch * 0.1f + audio; // L: audio + pitch trace
                buf[(off + i) * 2 + 1] = audio;                 // R: audio only
            }
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_bender_sweep", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 4: Per-string bender — 4 strings, pluck + slide
    {
        printf("  Scenario: per_string_bender\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        GraphUnit u = {};
        u.type = UNIT_PER_STRING_BENDER; u.enabled = true;
        unit_init(&u, 48000.0f);
        engine->string_base_freq[0].store(400.0f);
        engine->string_base_freq[1].store(500.0f);
        engine->string_base_freq[2].store(600.0f);
        engine->string_base_freq[3].store(700.0f);
        const int total = sr * 4; // 4 seconds
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            float t = (float)off / sr;
            // Stagger strings: activate at 0s, 0.5s, 1s, 1.5s, release after 0.5s each
            for (int s = 0; s < 4; s++) {
                float start = s * 0.5f;
                bool active = t >= start && t < start + 0.5f;
                engine->string_active[s].store(active ? 1 : 0);
                engine->string_bend[s].store(active ? 0.7f : 0.0f);
                engine->string_mix[s].store(active ? 0.8f : 0.0f);
            }
            unit_process_per_string_bender(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                buf[(off + i) * 2]     = u.output_buffers[OPORT_OUT][i];
                buf[(off + i) * 2 + 1] = u.output_buffers[OPORT_OUT_RIGHT][i];
            }
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_per_string_bender", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 5: Voice through reverb (graph)
    {
        printf("  Scenario: voice_reverb\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        orpheus_engine_set_voice_active(engine, 0, 1);
        orpheus_engine_set_voice_tune(engine, 0, 60.0f);
        orpheus_engine_set_voice_gate(engine, 0, 1);
        engine->voice_params[0].engine_index.store(0);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);
        engine->reverb_bypass.store(0);
        engine->reverb_amount.store(0.5f);
        engine->reverb_time.store(0.7f);

        OrpheusGraph* graph = new OrpheusGraph();
        std::memset(graph, 0, sizeof(OrpheusGraph));
        graph->sample_rate = (float)sr;
        graph->unit_count = 4;
        graph->exec_count = 4;
        for (int i = 0; i < 4; i++) graph->exec_order[i] = i;
        graph->master_out_index = 3;

        graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
        graph->units[0].enabled = true;
        unit_init(&graph->units[0], (float)sr);
        graph->units[0].state.module.index = 0;

        graph->units[1].type = UNIT_REVERB; graph->units[1].id = 1;
        graph->units[1].enabled = true; unit_init(&graph->units[1], (float)sr);
        graph->units[1].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[1].inputs[IPORT_INPUT_B].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_B].num_sources = 1;

        // Sum voice dry + reverb wet
        graph->units[2].type = UNIT_ADD; graph->units[2].id = 2;
        graph->units[2].enabled = true; unit_init(&graph->units[2], (float)sr);
        graph->units[2].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[2].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
        graph->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

        graph->units[3].type = UNIT_MASTER_OUT; graph->units[3].id = 3;
        graph->units[3].enabled = true; unit_init(&graph->units[3], (float)sr);
        graph->units[3].inputs[IPORT_INPUT_A].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
        graph->units[3].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[3].inputs[IPORT_INPUT_B].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
        graph->units[3].inputs[IPORT_INPUT_B].num_sources = 1;

        const int total = sr * 4;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr / 2) orpheus_engine_set_voice_gate(engine, 0, 0);
            orpheus_graph_process(graph, engine, buf.data() + off * 2, chunk);
        }
        float tail_rms = compute_rms(buf.data() + (sr * 3) * 2, sr * 2);
        printf("    RMS=%.4f Peak=%.4f Tail=%.6f\n",
               compute_rms(buf.data(), total * 2), compute_peak(buf.data(), total * 2), tail_rms);
        all_pass &= snapshot_check("cpp_voice_reverb", buf.data(), total, sr, dir);
        delete graph;
        orpheus_engine_destroy(engine);
    }

    // Scenario 6: Voice through delay (graph)
    {
        printf("  Scenario: voice_delay\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        orpheus_engine_set_voice_active(engine, 0, 1);
        orpheus_engine_set_voice_tune(engine, 0, 60.0f);
        orpheus_engine_set_voice_gate(engine, 0, 1);
        engine->voice_params[0].engine_index.store(0);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);
        engine->delay_bypass.store(0);
        engine->delay_mix.store(0.5f);
        engine->delay_feedback.store(0.4f);
        engine->delay_time_1.store(0.2f);
        engine->delay_time_2.store(0.3f);

        OrpheusGraph* graph = new OrpheusGraph();
        std::memset(graph, 0, sizeof(OrpheusGraph));
        graph->sample_rate = (float)sr;
        graph->unit_count = 4;
        graph->exec_count = 4;
        for (int i = 0; i < 4; i++) graph->exec_order[i] = i;
        graph->master_out_index = 3;

        graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
        graph->units[0].enabled = true;
        unit_init(&graph->units[0], (float)sr);
        graph->units[0].state.module.index = 0;

        graph->units[1].type = UNIT_DUAL_DELAY; graph->units[1].id = 1;
        graph->units[1].enabled = true; unit_init(&graph->units[1], (float)sr);
        graph->units[1].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[1].inputs[IPORT_INPUT_B].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_B].num_sources = 1;

        graph->units[2].type = UNIT_HARD_CLIP; graph->units[2].id = 2;
        graph->units[2].enabled = true; unit_init(&graph->units[2], (float)sr);
        graph->units[2].inputs[IPORT_INPUT].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
        graph->units[2].inputs[IPORT_INPUT].num_sources = 1;

        graph->units[3].type = UNIT_MASTER_OUT; graph->units[3].id = 3;
        graph->units[3].enabled = true; unit_init(&graph->units[3], (float)sr);
        graph->units[3].inputs[IPORT_INPUT_A].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
        graph->units[3].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[3].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT_RIGHT];
        graph->units[3].inputs[IPORT_INPUT_B].num_sources = 1;

        const int total = sr * 3;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr / 4) orpheus_engine_set_voice_gate(engine, 0, 0);
            orpheus_graph_process(graph, engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n",
               compute_rms(buf.data(), total * 2), compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_voice_delay", buf.data(), total, sr, dir);
        delete graph;
        orpheus_engine_destroy(engine);
    }

    printf("WAV snapshots: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// ═══════════════════════════════════════════════════════════════════════
// CPU Benchmark
// ═══════════════════════════════════════════════════════════════════════

bool test_cpu_benchmark() {
    printf("\n=== CPU Benchmark ===\n");
    const int sr = 48000;
    const int render_seconds = 10;
    const int total_frames = sr * render_seconds;

    auto benchmark = [&](const char* label, int num_voices, bool use_graph) -> double {
        OrpheusEngine* engine = orpheus_engine_create(sr);
        for (int v = 0; v < num_voices; v++) {
            orpheus_engine_set_voice_active(engine, v, 1);
            orpheus_engine_set_voice_tune(engine, v, 48.0f + v * 4);
            orpheus_engine_set_voice_gate(engine, v, 1);
            engine->voice_params[v].engine_index.store(v % 4);
            engine->voice_params[v].harmonics.store(0.5f);
            engine->voice_params[v].timbre.store(0.5f);
            engine->voice_params[v].morph.store(0.5f);
            engine->voice_params[v].decay.store(0.5f);
        }

        OrpheusGraph* graph = nullptr;
        if (use_graph) {
            graph = new OrpheusGraph();
            std::memset(graph, 0, sizeof(OrpheusGraph));
            graph->sample_rate = (float)sr;
            int n = std::min(num_voices, (int)kNumMainVoices);
            graph->unit_count = n + 2;
            graph->exec_count = n + 2;
            for (int i = 0; i < graph->unit_count; i++) graph->exec_order[i] = i;
            graph->master_out_index = n + 1;
            for (int v = 0; v < n; v++) {
                graph->units[v].type = UNIT_PLAITS; graph->units[v].id = v;
                graph->units[v].enabled = true;
                unit_init(&graph->units[v], (float)sr);
                graph->units[v].state.module.index = v;
            }
            int clip = n;
            graph->units[clip].type = UNIT_HARD_CLIP; graph->units[clip].id = clip;
            graph->units[clip].enabled = true; unit_init(&graph->units[clip], (float)sr);
            for (int v = 0; v < std::min(n, 4); v++)
                graph->units[clip].inputs[IPORT_INPUT].sources[v] = graph->units[v].output_buffers[OPORT_OUT];
            graph->units[clip].inputs[IPORT_INPUT].num_sources = std::min(n, 4);
            int mo = n + 1;
            graph->units[mo].type = UNIT_MASTER_OUT; graph->units[mo].id = mo;
            graph->units[mo].enabled = true; unit_init(&graph->units[mo], (float)sr);
            graph->units[mo].inputs[IPORT_INPUT_A].sources[0] = graph->units[clip].output_buffers[OPORT_OUT];
            graph->units[mo].inputs[IPORT_INPUT_A].num_sources = 1;
            graph->units[mo].inputs[IPORT_INPUT_B].sources[0] = graph->units[clip].output_buffers[OPORT_OUT];
            graph->units[mo].inputs[IPORT_INPUT_B].num_sources = 1;
        }

        float buf[256];
        auto start = std::chrono::high_resolution_clock::now();
        for (int off = 0; off < total_frames; off += 128) {
            int chunk = std::min(128, total_frames - off);
            if (use_graph)
                orpheus_graph_process(graph, engine, buf, chunk);
            else
                orpheus_engine_process(engine, buf, chunk);
        }
        auto end = std::chrono::high_resolution_clock::now();
        double ms = std::chrono::duration<double, std::milli>(end - start).count();
        double rt = (render_seconds * 1000.0) / ms;
        printf("  %-30s %7.1fms for %ds = %.1fx realtime\n", label, ms, render_seconds, rt);
        if (graph) delete graph;
        orpheus_engine_destroy(engine);
        return rt;
    };

    double r1 = benchmark("1 voice (engine)", 1, false);
    double r2 = benchmark("4 voices (engine)", 4, false);
    double r3 = benchmark("8 voices (engine)", 8, false);
    double r4 = benchmark("1 voice (graph)", 1, true);
    double r5 = benchmark("4 voices (graph)", 4, true);
    double r6 = benchmark("8 voices (graph)", 8, true);

    double lowest = std::min({r1, r2, r3, r4, r5, r6});
    bool pass = lowest > 2.0; // must be at least 2x realtime
    printf("CPU benchmark: lowest=%.1fx — %s\n", lowest, pass ? "PASS" : "FAIL");
    return pass;
}

int main() {
    bool all_pass = true;

    // Existing unit tests
    all_pass &= test_voice_coupling();
    all_pass &= test_fm_modulation();
    all_pass &= test_clock();
    all_pass &= test_grids();
    all_pass &= test_marbles();
    all_pass &= test_looper();
    all_pass &= test_bender();
    all_pass &= test_per_string_bender();

    // New voice module tests
    all_pass &= test_single_voice_engine0();
    all_pass &= test_single_voice_plaits_engines();
    all_pass &= test_polyphonic_voices();
    all_pass &= test_polyphonic_plaits_voices();
    all_pass &= test_voice_gate_retrigger();
    all_pass &= test_voice_hold_without_gate();
    all_pass &= test_voice_activation_lifecycle();
    all_pass &= test_engine_switch_while_playing();
    all_pass &= test_idle_detection_recovery();
    all_pass &= test_full_engine_render();
    all_pass &= test_polyphonic_engine_render();

    // Mod source routing
    all_pass &= test_mod_source_routing();

    // Effects bypass + active
    all_pass &= test_effects_bypass_passthrough();
    all_pass &= test_effects_active();

    // Graph integration
    all_pass &= test_graph_single_voice();
    all_pass &= test_graph_polyphonic();
    all_pass &= test_graph_effects_chain();

    // WAV snapshot scenarios (writes to test/output/)
    all_pass &= test_wav_snapshots();

    // CPU benchmark (always last — timing)
    all_pass &= test_cpu_benchmark();

    if (!all_pass) {
        fprintf(stderr, "\nFAILURE: One or more tests failed!\n");
        return 1;
    }

    printf("\nSUCCESS: All tests passed.\n");
    return 0;
}
