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

    // Initialize Clouds granular processor
    engine->clouds_processor.Init(
        engine->clouds_large_buffer, sizeof(engine->clouds_large_buffer),
        engine->clouds_small_buffer, sizeof(engine->clouds_small_buffer));
    engine->clouds_processor.set_playback_mode(clouds::PLAYBACK_MODE_GRANULAR);
    engine->clouds_processor.set_quality(0);  // stereo hi-fi
    // Zero-init parameters to avoid undefined fields (stereo_spread, trigger, gate)
    std::memset(engine->clouds_processor.mutable_parameters(), 0, sizeof(clouds::Parameters));

    // Initialize Rings resonator
    engine->rings_part.Init(engine->rings_reverb_buffer);
    engine->rings_part.set_model(rings::RESONATOR_MODEL_MODAL);
    engine->rings_part.set_polyphony(1);

    // Initialize Warps modulator
    engine->warps_modulator.Init(48000.0f);

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

    // Process through Clouds granular effect (if not bypassed)
    if (!engine->clouds_bypass.load(std::memory_order_relaxed)) {
        // Copy atomic parameters into the processor
        auto* p = engine->clouds_processor.mutable_parameters();
        p->position = engine->clouds_position.load(std::memory_order_relaxed);
        p->size = engine->clouds_size.load(std::memory_order_relaxed);
        p->pitch = engine->clouds_pitch.load(std::memory_order_relaxed);
        p->density = engine->clouds_density.load(std::memory_order_relaxed);
        p->texture = engine->clouds_texture.load(std::memory_order_relaxed);
        p->dry_wet = engine->clouds_dry_wet.load(std::memory_order_relaxed);
        p->feedback = engine->clouds_feedback.load(std::memory_order_relaxed);
        p->reverb = engine->clouds_reverb.load(std::memory_order_relaxed);
        p->freeze = engine->clouds_freeze.load(std::memory_order_relaxed) != 0;

        engine->clouds_processor.set_playback_mode(
            static_cast<clouds::PlaybackMode>(
                engine->clouds_mode.load(std::memory_order_relaxed)));

        // Prepare once per callback (not per block) — handles mode transitions
        // and background computation. In firmware, Prepare() runs in idle loop.
        engine->clouds_processor.Prepare();

        // Process in kMaxBlockSize (32) chunks — Clouds uses ShortFrame I/O
        int frames_done = 0;
        while (frames_done < num_frames) {
            int block = std::min(static_cast<int>(clouds::kMaxBlockSize),
                                 num_frames - frames_done);

            clouds::ShortFrame in_frames[clouds::kMaxBlockSize];
            clouds::ShortFrame out_frames[clouds::kMaxBlockSize];

            // Float interleaved → int16 stereo frames
            for (int i = 0; i < block; i++) {
                int idx = (frames_done + i) * 2;
                float l = std::max(-1.0f, std::min(1.0f, output_buffer[idx]));
                float r = std::max(-1.0f, std::min(1.0f, output_buffer[idx + 1]));
                in_frames[i].l = static_cast<short>(l * 32767.0f);
                in_frames[i].r = static_cast<short>(r * 32767.0f);
            }

            engine->clouds_processor.Process(in_frames, out_frames, block);

            // int16 stereo frames → float interleaved
            const float inv = 1.0f / 32768.0f;
            for (int i = 0; i < block; i++) {
                int idx = (frames_done + i) * 2;
                output_buffer[idx]     = out_frames[i].l * inv;
                output_buffer[idx + 1] = out_frames[i].r * inv;
            }

            frames_done += block;
        }
    }

    // Process through Rings resonator (if not bypassed)
    if (!engine->rings_bypass.load(std::memory_order_relaxed)) {
        rings::Patch rings_patch;
        rings_patch.structure = engine->rings_structure.load(std::memory_order_relaxed);
        rings_patch.brightness = engine->rings_brightness.load(std::memory_order_relaxed);
        rings_patch.damping = engine->rings_damping.load(std::memory_order_relaxed);
        rings_patch.position = engine->rings_position.load(std::memory_order_relaxed);

        rings::PerformanceState perf;
        std::memset(&perf, 0, sizeof(perf));
        perf.tonic = engine->rings_frequency.load(std::memory_order_relaxed);
        perf.note = 0.0f;
        perf.internal_exciter = engine->rings_internal_exciter.load(std::memory_order_relaxed) != 0;
        perf.internal_strum = false;
        perf.internal_note = false;

        // Handle strum trigger (set from UI, cleared by audio thread)
        int strum = engine->rings_strum.load(std::memory_order_relaxed);
        perf.strum = strum != 0;
        if (strum) {
            engine->rings_strum.store(0, std::memory_order_relaxed);
        }

        engine->rings_part.set_model(
            static_cast<rings::ResonatorModel>(
                engine->rings_model.load(std::memory_order_relaxed)));
        engine->rings_part.set_polyphony(
            engine->rings_polyphony.load(std::memory_order_relaxed));

        // Rings processes mono float in, stereo float out+aux
        // De-interleave to mono, process in rings::kMaxBlockSize (24) chunks
        int frames_done = 0;
        while (frames_done < num_frames) {
            int block = std::min(static_cast<int>(rings::kMaxBlockSize),
                                 num_frames - frames_done);

            float mono_in[rings::kMaxBlockSize];
            float out_buf[rings::kMaxBlockSize];
            float aux_buf[rings::kMaxBlockSize];

            // Mix stereo to mono for Rings input
            for (int i = 0; i < block; i++) {
                int idx = (frames_done + i) * 2;
                mono_in[i] = (output_buffer[idx] + output_buffer[idx + 1]) * 0.5f;
            }

            engine->rings_part.Process(perf, rings_patch, mono_in, out_buf, aux_buf, block);

            // Write stereo output (out=left, aux=right)
            for (int i = 0; i < block; i++) {
                int idx = (frames_done + i) * 2;
                output_buffer[idx]     = out_buf[i];
                output_buffer[idx + 1] = aux_buf[i];
            }

            frames_done += block;
        }
    }

    // Process through Warps modulator (if not bypassed)
    if (!engine->warps_bypass.load(std::memory_order_relaxed)) {
        auto* wp = engine->warps_modulator.mutable_parameters();
        wp->modulation_algorithm = engine->warps_algorithm.load(std::memory_order_relaxed);
        wp->modulation_parameter = engine->warps_timbre.load(std::memory_order_relaxed);
        wp->channel_drive[0] = engine->warps_level1.load(std::memory_order_relaxed);
        wp->channel_drive[1] = engine->warps_level2.load(std::memory_order_relaxed);
        wp->carrier_shape = 0;  // external carrier

        // Process in warps::kMaxBlockSize (96) chunks — Warps uses ShortFrame I/O
        int frames_done = 0;
        while (frames_done < num_frames) {
            int block = std::min(static_cast<int>(warps::kMaxBlockSize),
                                 num_frames - frames_done);

            warps::ShortFrame in_frames[warps::kMaxBlockSize];
            warps::ShortFrame out_frames[warps::kMaxBlockSize];

            // Float interleaved → int16 stereo frames
            for (int i = 0; i < block; i++) {
                int idx = (frames_done + i) * 2;
                float l = std::max(-1.0f, std::min(1.0f, output_buffer[idx]));
                float r = std::max(-1.0f, std::min(1.0f, output_buffer[idx + 1]));
                in_frames[i].l = static_cast<short>(l * 32767.0f);
                in_frames[i].r = static_cast<short>(r * 32767.0f);
            }

            engine->warps_modulator.Process(in_frames, out_frames, block);

            // int16 stereo frames → float interleaved
            const float inv = 1.0f / 32768.0f;
            for (int i = 0; i < block; i++) {
                int idx = (frames_done + i) * 2;
                output_buffer[idx]     = out_frames[i].l * inv;
                output_buffer[idx + 1] = out_frames[i].r * inv;
            }

            frames_done += block;
        }
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
    if (std::strcmp(plugin_uri, "clouds") == 0) {
        if (std::strcmp(symbol, "position") == 0)
            engine->clouds_position.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "size") == 0)
            engine->clouds_size.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pitch") == 0)
            engine->clouds_pitch.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density") == 0)
            engine->clouds_density.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "texture") == 0)
            engine->clouds_texture.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "dry_wet") == 0)
            engine->clouds_dry_wet.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->clouds_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "reverb") == 0)
            engine->clouds_reverb.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "freeze") == 0)
            engine->clouds_freeze.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mode") == 0)
            engine->clouds_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->clouds_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "rings") == 0) {
        if (std::strcmp(symbol, "structure") == 0)
            engine->rings_structure.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "brightness") == 0)
            engine->rings_brightness.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "damping") == 0)
            engine->rings_damping.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "position") == 0)
            engine->rings_position.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "frequency") == 0)
            engine->rings_frequency.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "model") == 0)
            engine->rings_model.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "polyphony") == 0)
            engine->rings_polyphony.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "strum") == 0)
            engine->rings_strum.store(1, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->rings_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "internal_exciter") == 0)
            engine->rings_internal_exciter.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "warps") == 0) {
        if (std::strcmp(symbol, "algorithm") == 0)
            engine->warps_algorithm.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "timbre") == 0)
            engine->warps_timbre.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "level1") == 0)
            engine->warps_level1.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "level2") == 0)
            engine->warps_level2.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->warps_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
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
