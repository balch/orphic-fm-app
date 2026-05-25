#include "orpheus_engine.h"
#include "orpheus_viz.h"
#include "orpheus_turntable.h"
#include <algorithm>
#include <cmath>
#include <cstring>

extern "C" {

void orpheus_engine_set_voice_gate(OrpheusEngine* engine,
                                   int index, int active) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].gate.store(active);
        if (active) {
            engine->voice_params[index].ever_triggered.store(1, std::memory_order_relaxed);
        }
    }
}

void orpheus_engine_set_voice_tune(OrpheusEngine* engine,
                                   int index, float tune) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].tune.store(tune);
    }
}

void orpheus_engine_set_voice_engine(OrpheusEngine* engine,
                                     int index, int engine_index) {
    if (index >= 0 && index < kNumVoices) {
        int old = engine->voice_params[index].engine_index.load(std::memory_order_relaxed);
        engine->voice_params[index].engine_index.store(engine_index, std::memory_order_relaxed);
        // If engine changed while gate is on, force a retrigger so the new
        // engine's LPG gets a fresh attack (Plaits edge-detects the trigger).
        if (old != engine_index) {
            engine->voice_params[index].engine_changed.store(1, std::memory_order_relaxed);
        }
    }
}

void orpheus_engine_set_voice_active(OrpheusEngine* engine,
                                      int index, int active) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].active.store(active, std::memory_order_relaxed);
        // Mark as triggered so the idle-voice guard doesn't block rendering.
        // Voices set active from syncNativeBridgeState need to be ready for
        // gate/hold events without requiring a prior gate pulse.
        if (active) {
            engine->voice_params[index].ever_triggered.store(1, std::memory_order_relaxed);
        }
    }
}

void orpheus_engine_set_voice_hold(OrpheusEngine* engine,
                                    int index, float level) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_hold_level[index].store(level, std::memory_order_relaxed);
        // Ensure voice is activated when hold engages (even if never gated)
        if (level > 0.001f) {
            engine->voice_params[index].ever_triggered.store(1, std::memory_order_relaxed);
        }
    }
}

void orpheus_engine_set_voice_harmonics(OrpheusEngine* engine,
                                        int index, float value) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].harmonics.store(value, std::memory_order_relaxed);
    }
}

void orpheus_engine_set_voice_timbre(OrpheusEngine* engine,
                                     int index, float value) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].timbre.store(value, std::memory_order_relaxed);
    }
}

void orpheus_engine_set_voice_morph(OrpheusEngine* engine,
                                    int index, float value) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].morph.store(value, std::memory_order_relaxed);
    }
}

void orpheus_engine_set_voice_decay(OrpheusEngine* engine,
                                    int index, float value) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].decay.store(value, std::memory_order_relaxed);
    }
}

void orpheus_engine_trigger_drum(OrpheusEngine* engine,
                                 int drum_index, float accent) {
    // Drum voices 12-14: engine_index, tune, timbre, morph, harmonics
    // are all set via set_port (and init defaults). Trigger sets gate + accent.
    // Gate is auto-cleared after each render (one-shot, line ~776 in orpheus_units.cpp).
    // Re-triggering works because the idle exit resets trigger_state_ after decay.
    if (drum_index >= 0 && drum_index < kNumDrumVoices) {
        int voice_index = kDrumVoiceStart + drum_index;
        auto& vp = engine->voice_params[voice_index];
        vp.accent.store(accent, std::memory_order_relaxed);
        vp.active.store(1);
        vp.ever_triggered.store(1);
        vp.gate.store(1);
    }
}

void orpheus_engine_set_master_volume(OrpheusEngine* engine, float v) {
    // Volume is applied via MasterFader inside unit_process_master_out
    // (chain: pan -> tape_stop -> fader -> peak -> limiter).
    engine->master_volume.store(v);
    // Also snap the fader so non-ramped sets take effect immediately
    // (cancels any in-flight master_fade).
    engine->master_fader_l.reset(v);
    engine->master_fader_r.reset(v);
}

void orpheus_engine_set_drive(OrpheusEngine* engine, float v) {
    // v is 0..1 from UI; map to drive multiplier 1.0..15.0 (matching JSyn)
    engine->drive_amount.store(1.0f + v * 14.0f, std::memory_order_relaxed);
}
void orpheus_engine_set_delay_mix(OrpheusEngine* engine, float v) {
    engine->delay_mix.store(v, std::memory_order_relaxed);
    engine->delay_bypass.store(v < 0.001f ? 1 : 0, std::memory_order_relaxed);
}
void orpheus_engine_set_vibrato(OrpheusEngine* engine, float v) {
    engine->vibrato_depth.store(v, std::memory_order_relaxed);
}
void orpheus_engine_set_vibrato_rate(OrpheusEngine* engine, float hz) {
    engine->vibrato_rate.store(hz, std::memory_order_relaxed);
}
void orpheus_engine_set_bend(OrpheusEngine* engine, float v) {
    engine->bend_amount.store(v, std::memory_order_relaxed);
}

void orpheus_engine_get_monitor(OrpheusEngine* engine,
                                OrpheusMonitorData* out) {
    std::memset(out, 0, sizeof(OrpheusMonitorData));
    out->peak_left = engine->peak_left.load();
    out->peak_right = engine->peak_right.load();
    out->cpu_load = engine->cpu_load.load();
    float voice_sum = 0.0f;
    for (int i = 0; i < kNumVoices && i < 12; i++) {
        float level = engine->voice_levels[i].load(std::memory_order_relaxed);
        out->voice_levels[i] = level;
        voice_sum += level;
    }
    float peak = std::max(out->peak_left, out->peak_right);
    float computed = voice_sum / 12.0f;
    if (computed > 1.0f) computed = 1.0f;
    if (peak > 1.0f) peak = 1.0f;
    out->master_level = std::max(peak, computed);
    out->lfo_output = engine->lfo_output_value;
    out->lfo_output_a = engine->lfo_output_value_a;
    out->lfo_output_b = engine->lfo_output_value_b;
    out->bend_position = engine->bend_amount.load(std::memory_order_relaxed);
}

void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames) {
    std::memset(buffer, 0, max_frames * sizeof(float));
}

int orpheus_engine_get_viz(OrpheusEngine* engine, int channel,
                           float* out_buf, int max_samples, int* last_read_pos) {
    if (!engine || channel < 0 || channel >= VIZ_CHANNEL_COUNT || !out_buf || !last_read_pos)
        return 0;
    auto& ring = engine->viz_rings[channel];
    uint32_t wc = ring.write_count.load(std::memory_order_acquire);
    uint32_t rc = static_cast<uint32_t>(*last_read_pos);
    uint32_t avail = wc - rc;  // unsigned wrapping: correct even after overflow
    if (avail == 0) return 0;
    // Writer lapped reader — snap to most recent kVizBufSize-1 samples
    if (avail > static_cast<uint32_t>(VizRing::kVizBufSize - 1)) {
        rc = wc - (VizRing::kVizBufSize - 1);
        avail = VizRing::kVizBufSize - 1;
    }
    int count = std::min(static_cast<int>(avail), max_samples);
    for (int i = 0; i < count; i++) {
        out_buf[i] = ring.buf[(rc + i) % VizRing::kVizBufSize];
    }
    *last_read_pos = static_cast<int>(rc + count);
    return count;
}

// ── Automation API (called from UI thread) ─────────────────────────────

void orpheus_engine_set_automation(OrpheusEngine* engine,
                                    int target, int voice_index,
                                    const float* times, const float* values,
                                    int count) {
    if (!engine || count <= 0 || count > kMaxAutomationPoints) return;
    if (voice_index < 0 || voice_index >= kNumMainVoices) return;

    // Slot layout: 0-11 = gates, 12-23 = freqs
    int slot_idx = (target == AUTO_TARGET_VOICE_FREQ)
        ? kNumMainVoices + voice_index
        : voice_index;

    auto& slot = engine->automation_slots[slot_idx];

    // Write to the non-active buffer (relaxed: worst case we pick the same buffer, brief glitch)
    int write_buf = (slot.active_path.load(std::memory_order_relaxed) == 0) ? 1 : 0;
    auto& path = slot.paths[write_buf];
    std::memcpy(path.times, times, count * sizeof(float));
    std::memcpy(path.values, values, count * sizeof(float));
    path.count = count;

    // Signal the audio thread (release ensures writes above are visible)
    slot.pending_path.store(write_buf, std::memory_order_release);
}

void orpheus_engine_clear_automation(OrpheusEngine* engine,
                                      int target, int voice_index) {
    if (!engine) return;
    if (voice_index < 0 || voice_index >= kNumMainVoices) return;

    int slot_idx = (target == AUTO_TARGET_VOICE_FREQ)
        ? kNumMainVoices + voice_index
        : voice_index;

    auto& slot = engine->automation_slots[slot_idx];
    // Write an empty path to stop playback
    int write_buf = (slot.active_path.load(std::memory_order_relaxed) == 0) ? 1 : 0;
    slot.paths[write_buf].count = 0;
    slot.pending_path.store(write_buf, std::memory_order_release);
}

// ── TTS sample playback API ──────────────────────
void orpheus_engine_load_tts_audio(OrpheusEngine* engine,
                                    const float* samples, int count,
                                    int sample_rate) {
    if (!engine || !samples || count <= 0) return;

    // Stop playback first so audio thread won't read during copy
    engine->tts_playing.store(0, std::memory_order_relaxed);
    // Set length to 0 so audio thread skips even if it reads between these stores
    engine->tts_buffer_length.store(0, std::memory_order_release);

    // Lazy-allocate buffer on first load (~11.5MB)
    if (!engine->tts_buffer) {
        engine->tts_buffer = new float[OrpheusEngine::kMaxTtsSamples]();
    }

    int clamped = (count > OrpheusEngine::kMaxTtsSamples)
                ? OrpheusEngine::kMaxTtsSamples : count;
    std::memcpy(engine->tts_buffer, samples, clamped * sizeof(float));
    engine->tts_source_rate.store(sample_rate > 0 ? sample_rate : 48000,
                                   std::memory_order_relaxed);
    // Store length last with release — audio thread acquires to see completed buffer
    engine->tts_buffer_length.store(clamped, std::memory_order_release);
}

void orpheus_engine_play_tts(OrpheusEngine* engine) {
    if (!engine) return;
    engine->tts_trigger.store(1, std::memory_order_relaxed);
}

void orpheus_engine_stop_tts(OrpheusEngine* engine) {
    if (!engine) return;
    engine->tts_playing.store(0, std::memory_order_relaxed);
}

int orpheus_engine_is_tts_playing(OrpheusEngine* engine) {
    if (!engine) return 0;
    return engine->tts_playing.load(std::memory_order_relaxed);
}

void orpheus_engine_get_turntable_viz(OrpheusEngine* engine, int deck, float* out_buffer) {
    if (!engine || deck < 0 || deck > 1 || !out_buffer) return;
    turntable_get_viz(&engine->turntable_decks[deck], out_buffer);
}

void orpheus_engine_get_pulsar_viz(OrpheusEngine* engine,
                                   int* gates_out,
                                   float* velocities_out,
                                   int* playheads_out,
                                   int* step_counts_out) {
    if (!engine) return;
    const auto& viz = engine->pulsar_viz;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        for (int s = 0; s < kMaxPulsarSteps; s++) {
            gates_out[t * kMaxPulsarSteps + s] = viz.step_gates[t][s] ? 1 : 0;
            velocities_out[t * kMaxPulsarSteps + s] = viz.step_velocities[t][s];
        }
        playheads_out[t] = viz.playheads[t];
        step_counts_out[t] = viz.step_counts[t];
    }
}

void orpheus_engine_get_pulsar_arrangement(OrpheusEngine* engine, int* out) {
    if (!engine || !out) { if (out) out[0] = -1; return; }
    const PulsarState* ps = engine->pulsar_state;
    // Use arr_viz_section_index as the gate (not arrangement.active which is a
    // non-atomic bool and may not be visible across threads).
    // arr_viz_section_index >= 0 means arrangement is active and has been written.
    if (!ps) {
        out[0] = -1;
        out[1] = 0;
        out[2] = 0;
        out[3] = 0;
        out[4] = -1;
        out[5] = 0;
        return;
    }
    int sec = ps->arr_viz_section_index.load(std::memory_order_relaxed);
    if (sec < 0) {
        out[0] = -1;
        out[1] = 0;
        out[2] = 0;
        out[3] = 0;
        out[4] = -1;
        out[5] = 0;
        return;
    }
    out[0] = sec;
    out[1] = ps->arr_viz_bars_elapsed.load(std::memory_order_relaxed);
    out[2] = ps->arr_viz_bars_total.load(std::memory_order_relaxed);
    out[3] = ps->arr_viz_solo_active.load(std::memory_order_relaxed) ? 1 : 0;
    out[4] = ps->arr_viz_solo_track.load(std::memory_order_relaxed);
    out[5] = ps->arr_viz_solo_mode.load(std::memory_order_relaxed);
}

}  // extern "C"
