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

int main() {
    if (!test_clock()) return 1;

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
