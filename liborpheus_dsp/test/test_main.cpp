#include "orpheus_dsp.h"
#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>
#include <cmath>
#include <algorithm>

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

int main() {
    if (!test_clock()) return 1;
    if (!test_grids()) return 1;
    if (!test_marbles()) return 1;

    printf("Creating OrpheusEngine at 48kHz...\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Set voice 0 to default engine (VA), note C4 (MIDI 60)
    orpheus_engine_set_voice_tune(engine, 0, 60.0f);

    // Gate on
    orpheus_engine_set_voice_gate(engine, 0, 1);

    // Render 2 seconds total
    const int sample_rate = 48000;
    const int total_frames = sample_rate * 2;
    std::vector<float> buffer(total_frames * 2, 0.0f);

    // Process first second with gate on (in 128-frame chunks like Oboe)
    for (int offset = 0; offset < sample_rate; offset += 128) {
        int chunk = std::min(128, sample_rate - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    // Gate off at 1 second
    orpheus_engine_set_voice_gate(engine, 0, 0);

    // Process second second with gate off (release/decay)
    for (int offset = sample_rate; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    write_wav("test_output.wav", buffer.data(), total_frames, sample_rate);

    OrpheusMonitorData mon;
    orpheus_engine_get_monitor(engine, &mon);
    printf("Peak L=%.4f R=%.4f CPU=%.1f%%\n",
           mon.peak_left, mon.peak_right, mon.cpu_load);

    // Verify non-silence: check if any sample exceeds threshold
    float max_sample = 0.0f;
    for (int i = 0; i < total_frames * 2; i++) {
        float a = std::fabs(buffer[i]);
        if (a > max_sample) max_sample = a;
    }
    printf("Max sample amplitude: %.4f\n", max_sample);

    if (max_sample < 0.001f) {
        fprintf(stderr, "ERROR: Output is silence!\n");
        orpheus_engine_destroy(engine);
        return 1;
    }

    printf("SUCCESS: Audio rendered with amplitude %.4f\n", max_sample);
    orpheus_engine_destroy(engine);
    printf("Done.\n");
    return 0;
}
