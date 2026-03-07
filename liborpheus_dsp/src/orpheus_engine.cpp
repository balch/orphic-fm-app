#include "orpheus_engine.h"
#include <cstring>
#include <chrono>

extern "C" {

OrpheusEngine* orpheus_engine_create(float sample_rate) {
    auto* engine = new OrpheusEngine();
    engine->sample_rate = sample_rate;
    return engine;
}

void orpheus_engine_destroy(OrpheusEngine* engine) {
    delete engine;
}

int orpheus_engine_load_patch(OrpheusEngine* engine,
                              const uint8_t* descriptor, size_t length) {
    // TODO: parse descriptor, build graph
    return 0;
}

void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames) {
    // Silence for now — will be replaced with real DSP in Task 3
    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));
}

void orpheus_engine_set_port(OrpheusEngine* engine,
                             const char* plugin_uri,
                             const char* symbol,
                             float value) {
    // TODO: route to plugin
}

float orpheus_engine_get_port(OrpheusEngine* engine,
                              const char* plugin_uri,
                              const char* symbol) {
    return 0.0f;
}

void orpheus_engine_set_voice_gate(OrpheusEngine* engine,
                                   int index, int active) {
    if (index >= 0 && index < 12) {
        engine->voices[index].gate.store(active);
    }
}

void orpheus_engine_set_voice_tune(OrpheusEngine* engine,
                                   int index, float tune) {
    if (index >= 0 && index < 12) {
        engine->voices[index].tune.store(tune);
    }
}

void orpheus_engine_trigger_drum(OrpheusEngine* engine,
                                 int drum_index, float accent) {
    // TODO
}

void orpheus_engine_set_master_volume(OrpheusEngine* engine, float v) {
    engine->master_volume.store(v);
}

void orpheus_engine_set_drive(OrpheusEngine* engine, float v) { }
void orpheus_engine_set_delay_mix(OrpheusEngine* engine, float v) { }
void orpheus_engine_set_vibrato(OrpheusEngine* engine, float v) { }
void orpheus_engine_set_bend(OrpheusEngine* engine, float v) { }

void orpheus_engine_get_monitor(OrpheusEngine* engine,
                                OrpheusMonitorData* out) {
    std::memset(out, 0, sizeof(OrpheusMonitorData));
    out->peak_left = engine->peak_left.load();
    out->peak_right = engine->peak_right.load();
    out->cpu_load = engine->cpu_load.load();
}

void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames) {
    std::memset(buffer, 0, max_frames * sizeof(float));
}

}  // extern "C"
