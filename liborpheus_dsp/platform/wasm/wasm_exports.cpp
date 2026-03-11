// WASM export wrappers for OrpheusEngine C API.
// These are compiled with EMSCRIPTEN_KEEPALIVE so they survive dead-code
// elimination and are callable from JavaScript via ccall/cwrap.

#include <emscripten.h>
#include <cstdlib>
#include <cstring>

#include "orpheus_engine.h"

// Persistent output buffer for JS to read from via HEAPF32.
// Allocated once and reused across process() calls.
static float* g_output_buffer = nullptr;
static int    g_output_frames = 0;

extern "C" {

EMSCRIPTEN_KEEPALIVE
float* wasm_alloc_output(int num_frames) {
    int needed = num_frames * 2;  // stereo interleaved
    if (g_output_buffer && g_output_frames >= num_frames) {
        return g_output_buffer;
    }
    free(g_output_buffer);
    g_output_buffer = static_cast<float*>(malloc(needed * sizeof(float)));
    g_output_frames = num_frames;
    return g_output_buffer;
}

EMSCRIPTEN_KEEPALIVE
OrpheusEngine* wasm_engine_create(float sample_rate) {
    return orpheus_engine_create(sample_rate);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_destroy(OrpheusEngine* engine) {
    orpheus_engine_destroy(engine);
}

EMSCRIPTEN_KEEPALIVE
int wasm_engine_load_patch(OrpheusEngine* engine,
                           const uint8_t* data, int length) {
    return orpheus_engine_load_patch(engine, data, static_cast<size_t>(length));
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_process(OrpheusEngine* engine, int num_frames) {
    float* buf = wasm_alloc_output(num_frames);
    orpheus_engine_process(engine, buf, num_frames);
}

EMSCRIPTEN_KEEPALIVE
float* wasm_get_output_ptr() {
    return g_output_buffer;
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_port(OrpheusEngine* engine,
                          const char* uri, const char* sym, float val) {
    orpheus_engine_set_port(engine, uri, sym, val);
}

EMSCRIPTEN_KEEPALIVE
float wasm_engine_get_port(OrpheusEngine* engine,
                           const char* uri, const char* sym) {
    return orpheus_engine_get_port(engine, uri, sym);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_gate(OrpheusEngine* engine, int idx, int gate) {
    orpheus_engine_set_voice_gate(engine, idx, gate);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_tune(OrpheusEngine* engine, int idx, float note) {
    orpheus_engine_set_voice_tune(engine, idx, note);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_engine(OrpheusEngine* engine, int idx,
                                  int engine_index) {
    orpheus_engine_set_voice_engine(engine, idx, engine_index);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_active(OrpheusEngine* engine, int idx, int active) {
    orpheus_engine_set_voice_active(engine, idx, active);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_hold(OrpheusEngine* engine, int idx, float level) {
    orpheus_engine_set_voice_hold(engine, idx, level);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_harmonics(OrpheusEngine* engine, int idx, float v) {
    orpheus_engine_set_voice_harmonics(engine, idx, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_timbre(OrpheusEngine* engine, int idx, float v) {
    orpheus_engine_set_voice_timbre(engine, idx, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_morph(OrpheusEngine* engine, int idx, float v) {
    orpheus_engine_set_voice_morph(engine, idx, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_decay(OrpheusEngine* engine, int idx, float v) {
    orpheus_engine_set_voice_decay(engine, idx, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_trigger_drum(OrpheusEngine* engine, int drum_index,
                              float accent) {
    orpheus_engine_trigger_drum(engine, drum_index, accent);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_master_volume(OrpheusEngine* engine, float v) {
    orpheus_engine_set_master_volume(engine, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_drive(OrpheusEngine* engine, float v) {
    orpheus_engine_set_drive(engine, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_delay_mix(OrpheusEngine* engine, float v) {
    orpheus_engine_set_delay_mix(engine, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_vibrato(OrpheusEngine* engine, float v) {
    orpheus_engine_set_vibrato(engine, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_vibrato_rate(OrpheusEngine* engine, float hz) {
    orpheus_engine_set_vibrato_rate(engine, hz);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_bend(OrpheusEngine* engine, float v) {
    orpheus_engine_set_bend(engine, v);
}

}  // extern "C"
