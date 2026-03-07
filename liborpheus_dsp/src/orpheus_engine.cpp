#include "orpheus_engine.h"
#include <algorithm>
#include <cmath>
#include <cstring>

extern "C" {

OrpheusEngine* orpheus_engine_create(float sample_rate) {
    auto* engine = new OrpheusEngine();
    engine->sample_rate = sample_rate;

    // Initialize all Plaits voices
    for (int i = 0; i < kNumVoices; i++) {
        stmlib::BufferAllocator allocator(
            engine->voice_alloc_buffers[i], kVoiceAllocBytes);
        engine->voices_dsp[i].Init(&allocator);
    }

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
    // Zero the output
    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));

    const float volume = engine->master_volume.load();
    const float inv_32768 = 1.0f / 32768.0f;

    // Process each main voice
    for (int v = 0; v < kNumMainVoices; v++) {
        auto& vp = engine->voice_params[v];
        auto& voice = engine->voices_dsp[v];

        // Build Plaits Patch from atomic params
        plaits::Patch patch;
        patch.engine = vp.engine_index.load();
        patch.note = vp.tune.load();
        patch.harmonics = vp.harmonics.load();
        patch.timbre = vp.timbre.load();
        patch.morph = vp.morph.load();
        patch.frequency_modulation_amount = 0.0f;
        patch.timbre_modulation_amount = 0.0f;
        patch.morph_modulation_amount = 0.0f;
        patch.decay = 0.5f;
        patch.lpg_colour = 0.5f;

        // Build Modulations — trigger is a FLOAT value, not an enum.
        // Voice does its own Schmitt-trigger edge detection internally.
        plaits::Modulations mod;
        std::memset(&mod, 0, sizeof(mod));
        int current_gate = vp.gate.load();
        mod.trigger = current_gate ? 1.0f : 0.0f;
        mod.trigger_patched = true;
        mod.level_patched = false;

        // Render in kBlockSize (12) chunks
        int frames_done = 0;
        float voice_peak = 0.0f;

        while (frames_done < num_frames) {
            int block = std::min(static_cast<int>(plaits::kBlockSize),
                                 num_frames - frames_done);

            plaits::Voice::Frame frames[plaits::kMaxBlockSize];
            voice.Render(patch, mod, frames, block);

            // Mix into interleaved stereo output with int16->float conversion
            for (int i = 0; i < block; i++) {
                float sample_out = frames[i].out * inv_32768 * volume;
                float sample_aux = frames[i].aux * inv_32768 * volume;

                int idx = (frames_done + i) * 2;
                output_buffer[idx]     += sample_out;  // Left
                output_buffer[idx + 1] += sample_aux;  // Right

                float abs_out = std::fabs(sample_out);
                if (abs_out > voice_peak) voice_peak = abs_out;
            }

            frames_done += block;
        }

        engine->voice_levels[v] = voice_peak;
    }

    // Compute peak for monitoring
    float pk_l = 0.0f, pk_r = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float l = std::fabs(output_buffer[i * 2]);
        float r = std::fabs(output_buffer[i * 2 + 1]);
        if (l > pk_l) pk_l = l;
        if (r > pk_r) pk_r = r;
    }
    engine->peak_left.store(pk_l);
    engine->peak_right.store(pk_r);
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
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].gate.store(active);
    }
}

void orpheus_engine_set_voice_tune(OrpheusEngine* engine,
                                   int index, float tune) {
    if (index >= 0 && index < kNumVoices) {
        engine->voice_params[index].tune.store(tune);
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
    for (int i = 0; i < kNumVoices && i < 12; i++) {
        out->voice_levels[i] = engine->voice_levels[i];
    }
}

void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames) {
    std::memset(buffer, 0, max_frames * sizeof(float));
}

}  // extern "C"
