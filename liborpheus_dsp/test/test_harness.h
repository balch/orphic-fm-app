#pragma once

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

// ── WAV I/O ─────────────────────────────────────────────────────────

inline void write_wav(const char* path, const float* data, int num_frames, int sample_rate) {
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

inline int read_wav_mono(const char* path, std::vector<float>& out, int& sample_rate_out) {
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
        if (channels > 1) fseek(f, (channels - 1) * 2, SEEK_CUR);
    }
    fclose(f);
    return frames;
}

// ── Audio analysis helpers ──────────────────────────────────────────

inline float compute_rms(const float* buf, int n) {
    double sum = 0.0;
    for (int i = 0; i < n; i++) sum += (double)buf[i] * buf[i];
    return (float)std::sqrt(sum / n);
}

inline float compute_peak(const float* buf, int n) {
    float peak = 0.0f;
    for (int i = 0; i < n; i++) {
        float a = std::fabs(buf[i]);
        if (a > peak) peak = a;
    }
    return peak;
}

// ── WAV comparison ──────────────────────────────────────────────────

struct WavComparison {
    float rms_a, rms_b;
    float rms_diff;
    float correlation;
    float level_ratio;
    bool valid;
};

inline WavComparison compare_wavs(const char* path_a, const char* path_b) {
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

    double diff_sum = 0.0;
    for (int i = 0; i < n; i++) {
        float d = a[i] - b[i];
        diff_sum += (double)d * d;
    }
    c.rms_diff = (float)std::sqrt(diff_sum / n);

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

// ── Snapshot test helper ────────────────────────────────────────────

inline bool snapshot_check(const char* name, const float* stereo_data, int num_frames,
                           int sample_rate, const char* output_dir, float tolerance_rms = 0.05f) {
    char snap_path[512], ref_path[512];
    snprintf(snap_path, sizeof(snap_path), "%s/%s.wav", output_dir, name);
    snprintf(ref_path, sizeof(ref_path), "%s/%s.ref.wav", output_dir, name);

    write_wav(snap_path, stereo_data, num_frames, sample_rate);

    FILE* ref = fopen(ref_path, "rb");
    if (!ref) {
        printf("    [NEW] No reference for '%s' — snapshot saved as baseline\n", name);
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

// ── Composable render result ────────────────────────────────────────

struct RenderResult {
    std::vector<float> buffer;  // interleaved stereo
    float rms_l, rms_r;        // per-channel RMS
    float peak;                 // absolute peak across both channels
    int num_frames;
};

// Activate a voice with standard params. Caller can override fields after.
inline void activate_voice(OrpheusEngine* engine, int voice_idx, int engine_idx, float note,
                           float harmonics = 0.5f, float timbre = 0.5f,
                           float morph = 0.5f, float decay = 0.0f) {
    orpheus_engine_set_voice_active(engine, voice_idx, 1);
    orpheus_engine_set_voice_tune(engine, voice_idx, note);
    orpheus_engine_set_voice_gate(engine, voice_idx, 1);
    orpheus_engine_set_voice_engine(engine, voice_idx, engine_idx);
    orpheus_engine_set_voice_harmonics(engine, voice_idx, harmonics);
    orpheus_engine_set_voice_timbre(engine, voice_idx, timbre);
    orpheus_engine_set_voice_morph(engine, voice_idx, morph);
    engine->voice_params[voice_idx].decay.store(decay, std::memory_order_relaxed);
    engine->voice_params[voice_idx].ever_triggered.store(1, std::memory_order_relaxed);
}

// Render through orpheus_engine_process. Engine must have a graph loaded.
// Returns buffer + measurements. Caller sets all engine state beforehand.
inline RenderResult render_engine(OrpheusEngine* engine, int num_frames, int warmup_blocks = 10) {
    // Warmup
    float warmup_buf[128 * 2];
    for (int i = 0; i < warmup_blocks; i++)
        orpheus_engine_process(engine, warmup_buf, 128);

    // Render
    RenderResult r;
    r.num_frames = num_frames;
    r.buffer.resize(num_frames * 2, 0.0f);
    for (int off = 0; off < num_frames; off += 128) {
        int chunk = std::min(128, num_frames - off);
        orpheus_engine_process(engine, r.buffer.data() + off * 2, chunk);
    }

    // Measure
    double sum_l = 0.0, sum_r = 0.0;
    r.peak = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float l = r.buffer[i * 2];
        float rv = r.buffer[i * 2 + 1];
        sum_l += (double)l * l;
        sum_r += (double)rv * rv;
        float al = std::fabs(l), ar = std::fabs(rv);
        if (al > r.peak) r.peak = al;
        if (ar > r.peak) r.peak = ar;
    }
    r.rms_l = (float)std::sqrt(sum_l / num_frames);
    r.rms_r = (float)std::sqrt(sum_r / num_frames);
    return r;
}

// Compute diff RMS between two render results (using shorter of the two)
inline float diff_rms(const RenderResult& a, const RenderResult& b) {
    int n = std::min((int)a.buffer.size(), (int)b.buffer.size());
    double sum = 0.0;
    for (int i = 0; i < n; i++) {
        float d = a.buffer[i] - b.buffer[i];
        sum += (double)d * d;
    }
    return (float)std::sqrt(sum / n);
}

// ── Voice test helpers ──────────────────────────────────────────────

inline float render_voice(GraphUnit* unit, OrpheusEngine* engine, int total_frames, float sr = 48000.0f) {
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

inline void setup_voice_unit(GraphUnit* unit, int voice_idx) {
    std::memset(unit, 0, sizeof(GraphUnit));
    unit->type = UNIT_PLAITS;
    unit->enabled = true;
    unit->state.module.index = voice_idx;
    unit_init(unit, 48000.0f);
}

// ── Production graph loader ─────────────────────────────────────────
// Loads the production ODWG graph descriptor (exported by Gradle task)
// via orpheus_engine_load_patch. Returns true on success.
inline bool load_production_graph(OrpheusEngine* engine) {
    const char* path = TEST_DATA_DIR "/default_graph.odwg";
    FILE* f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "Cannot open production graph: %s\n", path);
        return false;
    }
    fseek(f, 0, SEEK_END);
    size_t len = (size_t)ftell(f);
    fseek(f, 0, SEEK_SET);
    auto* buf = new uint8_t[len];
    size_t read = fread(buf, 1, len, f);
    fclose(f);
    if (read != len) {
        fprintf(stderr, "Short read on production graph: %zu/%zu bytes\n", read, len);
        delete[] buf;
        return false;
    }
    int result = orpheus_engine_load_patch(engine, buf, len);
    delete[] buf;
    if (result != 0) {
        fprintf(stderr, "Failed to load production graph: error %d\n", result);
        return false;
    }
    return true;
}

// ── Default graph helper ────────────────────────────────────────────
// Attaches a default graph (all voices → master_out) to the engine.
// The engine takes ownership; graph is freed on engine destroy.
inline void attach_default_graph(OrpheusEngine* engine) {
    auto* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = engine->sample_rate;

    int unit_idx = 0;

    // Add all voice units (main + drum voices)
    for (int v = 0; v < kNumVoices; v++) {
        graph->units[unit_idx].type = UNIT_PLAITS;
        graph->units[unit_idx].id = unit_idx;
        graph->units[unit_idx].enabled = true;
        unit_init(&graph->units[unit_idx], engine->sample_rate);
        graph->units[unit_idx].state.module.index = v;
        unit_idx++;
    }

    // LFO unit (needed for mod source routing)
    int lfo_idx = unit_idx;
    graph->units[lfo_idx].type = UNIT_HYPER_LFO;
    graph->units[lfo_idx].id = lfo_idx;
    graph->units[lfo_idx].enabled = true;
    unit_init(&graph->units[lfo_idx], engine->sample_rate);
    unit_idx++;

    // Master out — sums all voices
    int master_idx = unit_idx;
    graph->units[master_idx].type = UNIT_MASTER_OUT;
    graph->units[master_idx].id = master_idx;
    graph->units[master_idx].enabled = true;
    unit_init(&graph->units[master_idx], engine->sample_rate);

    // Wire all voice outputs to master_out inputs
    for (int v = 0; v < kNumVoices; v++) {
        graph->units[master_idx].inputs[IPORT_INPUT_A].sources[v] = graph->units[v].output_buffers[OPORT_OUT];
        graph->units[master_idx].inputs[IPORT_INPUT_B].sources[v] = graph->units[v].output_buffers[OPORT_OUT];
    }
    graph->units[master_idx].inputs[IPORT_INPUT_A].num_sources = kNumVoices;
    graph->units[master_idx].inputs[IPORT_INPUT_B].num_sources = kNumVoices;
    unit_idx++;

    // Execution order: all voices → LFO → master_out
    int exec = 0;
    for (int v = 0; v < kNumVoices; v++) graph->exec_order[exec++] = v;
    graph->exec_order[exec++] = lfo_idx;
    graph->exec_order[exec++] = master_idx;

    graph->unit_count = unit_idx;
    graph->exec_count = exec;
    graph->master_out_index = master_idx;

    engine->graph.store(graph, std::memory_order_release);
}

// ── Minimal graph helper ────────────────────────────────────────────
// Creates a heap-allocated graph with a single plaits voice → master_out.
// Caller must delete the graph when done.
inline OrpheusGraph* create_minimal_graph(int voice_idx, float sample_rate) {
    auto* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = sample_rate;

    // Unit 0: plaits voice
    graph->units[0].type = UNIT_PLAITS;
    graph->units[0].id = 0;
    graph->units[0].enabled = true;
    unit_init(&graph->units[0], sample_rate);
    graph->units[0].state.module.index = voice_idx;

    // Unit 1: master out (stereo from mono)
    graph->units[1].type = UNIT_MASTER_OUT;
    graph->units[1].id = 1;
    graph->units[1].enabled = true;
    unit_init(&graph->units[1], sample_rate);
    graph->units[1].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[1].inputs[IPORT_INPUT_B].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT_B].num_sources = 1;

    graph->unit_count = 2;
    graph->exec_count = 2;
    graph->exec_order[0] = 0;
    graph->exec_order[1] = 1;
    graph->master_out_index = 1;

    return graph;
}

// ── Plaits per-engine render helper ────────────────────────────────

// Renders a single Plaits engine via graph path (plaits → master_out).
// Returns interleaved stereo buffer.
// engine_index: C++ Plaits engine index (8=VA, 9=waveshaping, etc.)
// note: MIDI note
// duration_s: total render duration
// gate_s: how long gate stays on (rest is release)
inline std::vector<float> render_plaits_engine(
    int engine_index, float note, float harmonics, float timbre, float morph,
    float decay, int sample_rate, float duration_s, float gate_s)
{
    OrpheusEngine* engine = orpheus_engine_create(sample_rate);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(engine_index);
    engine->voice_params[0].tune.store(note);
    engine->voice_params[0].harmonics.store(harmonics);
    engine->voice_params[0].timbre.store(timbre);
    engine->voice_params[0].morph.store(morph);
    engine->voice_params[0].decay.store(decay);

    // Set up minimal graph: plaits → master_out
    auto* graph = create_minimal_graph(0, (float)sample_rate);

    int total = (int)(sample_rate * duration_s);
    int gate_frames = (int)(sample_rate * gate_s);
    std::vector<float> buf(total * 2, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        if (off == 0) engine->voice_params[0].gate.store(1);
        if (off >= gate_frames) engine->voice_params[0].gate.store(0);
        orpheus_graph_process(graph, engine, buf.data() + off * 2, chunk);
    }
    delete graph;
    orpheus_engine_destroy(engine);
    return buf;
}

// ── Graph-based voice render (exercises unit_process_plaits ADSR) ────
//
// Renders a single voice through: plaits → master_out
// Uses the same graph path as production (no fallback).
// Returns interleaved stereo buffer.
inline std::vector<float> render_voice_with_envelope(
    int engine_index, float note, float harmonics, float timbre, float morph,
    float env_speed, int sample_rate, float duration_s, float gate_s)
{
    OrpheusEngine* engine = orpheus_engine_create(sample_rate);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(engine_index);
    engine->voice_params[0].tune.store(note);
    engine->voice_params[0].harmonics.store(harmonics);
    engine->voice_params[0].timbre.store(timbre);
    engine->voice_params[0].morph.store(morph);
    engine->voice_params[0].decay.store(env_speed); // envSpeed stored in decay

    auto* graph = create_minimal_graph(0, (float)sample_rate);

    int total = (int)(sample_rate * duration_s);
    int gate_frames = (int)(sample_rate * gate_s);
    std::vector<float> buf(total * 2, 0.0f);

    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        if (off == 0) engine->voice_params[0].gate.store(1);
        if (off >= gate_frames) engine->voice_params[0].gate.store(0);
        orpheus_graph_process(graph, engine, buf.data() + off * 2, chunk);
    }

    delete graph;
    orpheus_engine_destroy(engine);
    return buf;
}

// ── Test suite declarations ─────────────────────────────────────────

bool run_unit_tests();
bool run_voice_tests();
bool run_engine_render_tests();
bool run_effects_tests();
bool run_graph_tests();
bool run_output_chain_tests();
bool run_snapshot_tests();
bool run_drums_graph_tests();
bool run_lfo_tests();
bool run_control_routing_tests();
bool run_headroom_tests();
bool run_warps_tests();
bool run_bridge_audit();
bool run_preset_tests();
bool run_chain_compare_tests();
bool run_fm_compare_tests();
bool run_benchmark_tests();
