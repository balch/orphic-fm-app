#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>
#include <stdint.h>

// ── Opaque engine handle ─────────────────────────
typedef struct OrpheusEngine OrpheusEngine;

// ── Lifecycle ────────────────────────────────────
OrpheusEngine* orpheus_engine_create(float sample_rate);
void           orpheus_engine_destroy(OrpheusEngine* engine);

// ── Topology (called once per preset load) ───────
int orpheus_engine_load_patch(OrpheusEngine* engine,
                              const uint8_t* descriptor, size_t length);

// ── Audio render (called from audio thread) ──────
void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames);

// Deinterleaved variant for CoreAudio (separate L/R buffers).
// Renders interleaved internally, then splits into left/right.
void orpheus_engine_process_deinterleaved(OrpheusEngine* engine,
                                          float* left, float* right,
                                          int num_frames);

// ── Diagnostics ────
void  orpheus_engine_dump_state(OrpheusEngine* engine);

// ── Parameter control (called from UI thread) ────
void  orpheus_engine_set_port(OrpheusEngine* engine,
                              const char* plugin_uri,
                              const char* symbol,
                              float value);
float orpheus_engine_get_port(OrpheusEngine* engine,
                              const char* plugin_uri,
                              const char* symbol);

// ── Voice control ────────────────────────────────
void orpheus_engine_set_voice_gate(OrpheusEngine* engine,
                                   int index, int active);
void orpheus_engine_set_voice_tune(OrpheusEngine* engine,
                                   int index, float tune);
void orpheus_engine_set_voice_engine(OrpheusEngine* engine,
                                     int index, int engine_index);
void orpheus_engine_set_voice_harmonics(OrpheusEngine* engine,
                                        int index, float value);
void orpheus_engine_set_voice_timbre(OrpheusEngine* engine,
                                     int index, float value);
void orpheus_engine_set_voice_morph(OrpheusEngine* engine,
                                    int index, float value);
void orpheus_engine_set_voice_decay(OrpheusEngine* engine,
                                    int index, float value);
void orpheus_engine_set_voice_active(OrpheusEngine* engine,
                                      int index, int active);
void orpheus_engine_set_voice_hold(OrpheusEngine* engine,
                                    int index, float level);
void orpheus_engine_trigger_drum(OrpheusEngine* engine,
                                 int drum_index, float accent);

// ── Global controls ──────────────────────────────
void orpheus_engine_set_master_volume(OrpheusEngine* engine, float v);
void orpheus_engine_set_drive(OrpheusEngine* engine, float v);
void orpheus_engine_set_delay_mix(OrpheusEngine* engine, float v);
void orpheus_engine_set_vibrato(OrpheusEngine* engine, float v);
void orpheus_engine_set_vibrato_rate(OrpheusEngine* engine, float hz);
void orpheus_engine_set_bend(OrpheusEngine* engine, float v);

// ── Monitoring (polled at ~60fps from UI thread) ─
typedef struct {
    float peak_left;
    float peak_right;
    float cpu_load;
    float voice_levels[12];
    float lfo_output;
    float master_level;
    float bend_position;
    float lfo_output_a;      // individual LFO oscillator A (-1..1)
    float lfo_output_b;      // individual LFO oscillator B (-1..1)
} OrpheusMonitorData;

void orpheus_engine_get_monitor(OrpheusEngine* engine,
                                OrpheusMonitorData* out);

void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames);

// ── Signal visualization (polled at ~60fps from UI thread) ──
// Reads new samples from a viz ring buffer since last_read_pos.
// Returns count of samples written to out_buf. Updates *last_read_pos.
// Lock-free: safe to call from any thread while audio thread writes.
int  orpheus_engine_get_viz(OrpheusEngine* engine, int channel,
                            float* out_buf, int max_samples, int* last_read_pos);

// ── Automation (called from UI thread) ──────────
// target: 0=VOICE_GATE, 1=VOICE_FREQ
// voice_index: 0-11
// times[]: seconds from "now" (relative to when this call is made)
// values[]: raw values (gate: 0/1, freq: Hz)
// count: number of points (max 128)
void orpheus_engine_set_automation(OrpheusEngine* engine,
                                    int target, int voice_index,
                                    const float* times, const float* values,
                                    int count);

void orpheus_engine_clear_automation(OrpheusEngine* engine,
                                      int target, int voice_index);

// ── TTS sample playback ─────────────────────────
void orpheus_engine_load_tts_audio(OrpheusEngine* engine,
                                    const float* samples, int count,
                                    int sample_rate);
void orpheus_engine_play_tts(OrpheusEngine* engine);
void orpheus_engine_stop_tts(OrpheusEngine* engine);
int  orpheus_engine_is_tts_playing(OrpheusEngine* engine);

#ifdef __cplusplus
}
#endif
