#include "orpheus_engine.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

// Hash function matching Kotlin's hash16() and orpheus_graph.cpp's hash16()
static uint16_t engine_hash16(const char* str) {
    uint16_t h = 0;
    while (*str) {
        h = h * 31 + static_cast<uint16_t>(*str);
        str++;
    }
    return h;
}

extern "C" {

OrpheusEngine* orpheus_engine_create(float sample_rate) {
    auto* engine = new OrpheusEngine();
    engine->sample_rate = sample_rate;

    // Initialize all Plaits voices (OrpheusVoice: direct engine render)
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
    engine->warps_modulator.Init(engine->sample_rate);

    // Initialize Marbles random sequencer
    engine->marbles_rng.Init(0xDEADBEEF);
    engine->marbles_random_stream.Init(&engine->marbles_rng);
    engine->marbles_t_generator.Init(&engine->marbles_random_stream, sample_rate);
    engine->marbles_xy_generator.Init(&engine->marbles_random_stream, sample_rate);
    // Load a default major scale for quantization
    {
        marbles::Scale major_scale;
        major_scale.InitMajor();
        engine->marbles_xy_generator.LoadScale(0, major_scale);
        // Also load a chromatic scale (all equal, no quantization) as scale 1
        marbles::Scale chromatic;
        chromatic.Init();
        engine->marbles_xy_generator.LoadScale(1, chromatic);
    }

    // Initialize per-string bender defaults
    engine->string_base_freq[0].store(400.0f, std::memory_order_relaxed);
    engine->string_base_freq[1].store(550.0f, std::memory_order_relaxed);
    engine->string_base_freq[2].store(700.0f, std::memory_order_relaxed);
    engine->string_base_freq[3].store(850.0f, std::memory_order_relaxed);
    for (int i = 0; i < 4; i++) {
        engine->string_mix[i].store(0.5f, std::memory_order_relaxed);
    }
    for (int i = 0; i < kNumMainVoices; i++) {
        engine->voice_mix_cv[i] = 1.0f;
    }

    // Allocate looper buffers
    engine->looper_buffer_l = new float[OrpheusEngine::kMaxLoopSamples]();
    engine->looper_buffer_r = new float[OrpheusEngine::kMaxLoopSamples]();

    // Default per-voice pans (matches Kotlin StereoPlugin defaults)
    engine->voice_pan[0].store(0.0f);
    engine->voice_pan[1].store(0.0f);
    engine->voice_pan[2].store(-0.3f);
    engine->voice_pan[3].store(-0.3f);
    engine->voice_pan[4].store(0.3f);
    engine->voice_pan[5].store(0.3f);
    engine->voice_pan[6].store(-0.7f);
    engine->voice_pan[7].store(0.7f);
    for (int i = 8; i < kNumVoices; i++)
        engine->voice_pan[i].store(0.0f);

    return engine;
}

static void orpheus_graph_free(OrpheusGraph* graph) {
    if (!graph) return;
    // Free heap-allocated delay buffers
    for (int i = 0; i < graph->unit_count; i++) {
        if (graph->units[i].type == UNIT_DELAY_LINE && graph->units[i].state.delay.buffer) {
            delete[] graph->units[i].state.delay.buffer;
            graph->units[i].state.delay.buffer = nullptr;
        }
    }
    delete graph;
}

void orpheus_engine_destroy(OrpheusEngine* engine) {
    if (engine) {
        orpheus_graph_free(engine->graph.load(std::memory_order_relaxed));
        delete[] engine->looper_buffer_l;
        delete[] engine->looper_buffer_r;
        delete engine;
    }
}

int orpheus_engine_load_patch(OrpheusEngine* engine,
                              const uint8_t* descriptor, size_t length) {
    auto* new_graph = new OrpheusGraph();
    int result = orpheus_graph_load(new_graph, descriptor, length,
                                    engine->sample_rate);
    if (result != 0) {
        orpheus_graph_free(new_graph);
        return result;
    }
    // Atomic swap: audio thread sees new graph after release
    auto* old = engine->graph.exchange(new_graph, std::memory_order_acq_rel);
    // Defer free: old graph may still be in use for current audio callback.
    // Store for deferred cleanup on next load or destroy.
    // For now, delete immediately — loadGraph is only called at startup
    // before audio is flowing, so this is safe in practice.
    orpheus_graph_free(old);
    return 0;
}

void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames) {
    if (!engine || !output_buffer || num_frames <= 0) return;

    auto t0 = std::chrono::steady_clock::now();

    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));

    // Constant-power pan helper: pan in -1..+1 → L/R gains
    auto compute_pan = [](float pan, float& gain_l, float& gain_r) {
        float angle = ((pan + 1.0f) * 0.5f) * (3.14159265f * 0.5f);
        gain_l = std::cos(angle);
        gain_r = std::sin(angle);
    };

    OrpheusGraph* graph = engine->graph.load(std::memory_order_acquire);
    if (graph) {
        // Graph-based rendering: voices + effects chain via ODWG descriptor
        orpheus_graph_process(graph, engine, output_buffer, num_frames);

    } else {
        // Fallback: procedural rendering via OrpheusVoice (direct Engine::Render)
        const float volume = engine->master_volume.load(std::memory_order_relaxed);

        // Process each main voice
        for (int v = 0; v < kNumMainVoices; v++) {
            auto& vp = engine->voice_params[v];
            if (!vp.active.load(std::memory_order_relaxed)) continue;
            if (!vp.ever_triggered.load(std::memory_order_relaxed)) continue;
            auto& voice = engine->voices_dsp[v];

            float pan_l, pan_r;
            compute_pan(engine->voice_pan[v].load(std::memory_order_relaxed), pan_l, pan_r);

            int engine_index = vp.engine_index.load(std::memory_order_relaxed);
            float note = vp.tune.load(std::memory_order_relaxed);
            float harmonics = vp.harmonics.load(std::memory_order_relaxed);
            float timbre = vp.timbre.load(std::memory_order_relaxed);
            float morph = vp.morph.load(std::memory_order_relaxed);
            int current_gate = vp.gate.load(std::memory_order_relaxed);

            // Render via OrpheusVoice (direct Engine::Render, no LPG/limiter/int16)
            float mono_buf[kMaxFrames];
            voice.Render(engine_index, current_gate, note, harmonics, timbre, morph, 0.8f,
                         mono_buf, num_frames);

            // Mix into interleaved stereo with pan and volume
            float voice_peak = 0.0f;
            for (int i = 0; i < num_frames; i++) {
                float mono = mono_buf[i] * volume;
                output_buffer[i * 2]     += mono * pan_l;
                output_buffer[i * 2 + 1] += mono * pan_r;

                float abs_out = std::fabs(mono);
                if (abs_out > voice_peak) voice_peak = abs_out;
            }

            engine->voice_levels[v].store(voice_peak, std::memory_order_relaxed);
        }

        // Process drum voices (voices 8-11, using Plaits drum engines)
        for (int v = kNumMainVoices; v < kNumVoices; v++) {
            auto& vp = engine->voice_params[v];
            auto& voice = engine->voices_dsp[v];

            float pan_l, pan_r;
            compute_pan(engine->voice_pan[v].load(std::memory_order_relaxed), pan_l, pan_r);

            int engine_index = vp.engine_index.load(std::memory_order_relaxed);
            float note = vp.tune.load(std::memory_order_relaxed);
            float harmonics = vp.harmonics.load(std::memory_order_relaxed);
            float timbre = vp.timbre.load(std::memory_order_relaxed);
            float morph = vp.morph.load(std::memory_order_relaxed);
            int current_gate = vp.gate.load(std::memory_order_relaxed);

            // Render via OrpheusVoice (direct Engine::Render, no LPG/limiter/int16)
            float mono_buf[kMaxFrames];
            voice.Render(engine_index, current_gate, note, harmonics, timbre, morph, 0.8f,
                         mono_buf, num_frames);

            // Mix into interleaved stereo with pan and volume
            float voice_peak = 0.0f;
            for (int i = 0; i < num_frames; i++) {
                float mono = mono_buf[i] * volume;
                output_buffer[i * 2]     += mono * pan_l;
                output_buffer[i * 2 + 1] += mono * pan_r;

                float abs_out = std::fabs(mono);
                if (abs_out > voice_peak) voice_peak = abs_out;
            }

            // Clear gate after rendering so drums are one-shot triggers
            if (current_gate) {
                vp.gate.store(0, std::memory_order_relaxed);
            }

            engine->voice_levels[v].store(voice_peak, std::memory_order_relaxed);
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

                // Clear strum after first block so it doesn't retrigger
                perf.strum = false;

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

        // ── Drive (tanh saturation) with dry/wet mix ─────
        {
            float drive = engine->drive_amount.load(std::memory_order_relaxed);
            float dmix = engine->drive_mix.load(std::memory_order_relaxed);
            if (dmix > 0.001f) {
                float dry_amt = 1.0f - dmix;
                for (int i = 0; i < num_frames; i++) {
                    int idx = i * 2;
                    float dry_l = output_buffer[idx];
                    float dry_r = output_buffer[idx + 1];
                    float wet_l = std::tanh(dry_l * drive);
                    float wet_r = std::tanh(dry_r * drive);
                    output_buffer[idx]     = dry_l * dry_amt + wet_l * dmix;
                    output_buffer[idx + 1] = dry_r * dry_amt + wet_r * dmix;
                }
            }
        }

        // ── Master pan + soft-clip ─────────────────────────
        {
            float mp_l, mp_r;
            compute_pan(engine->master_pan.load(std::memory_order_relaxed), mp_l, mp_r);
            const float master_gain = 0.5f; // ~6dB headroom
            for (int i = 0; i < num_frames; i++) {
                int idx = i * 2;
                output_buffer[idx]     = std::tanh(output_buffer[idx]     * mp_l * master_gain);
                output_buffer[idx + 1] = std::tanh(output_buffer[idx + 1] * mp_r * master_gain);
            }
        }

        // ── Dual Delay ───────────────────────────────────
        if (!engine->delay_bypass.load(std::memory_order_relaxed)) {
            const float mix = engine->delay_mix.load(std::memory_order_relaxed);
            const float fb = std::min(engine->delay_feedback.load(std::memory_order_relaxed), 0.95f);
            const float sr = engine->sample_rate;

            float target_1 = (0.01f + engine->delay_time_1.load(std::memory_order_relaxed) * 1.99f) * sr;
            float target_2 = (0.01f + engine->delay_time_2.load(std::memory_order_relaxed) * 1.99f) * sr;

            const float smooth_coeff = 1.0f - std::exp(-1.0f / (0.02f * sr));
            engine->delay_time_1_smooth += (target_1 - engine->delay_time_1_smooth) * smooth_coeff;
            engine->delay_time_2_smooth += (target_2 - engine->delay_time_2_smooth) * smooth_coeff;

            int delay_samples_1 = std::max(1, std::min(static_cast<int>(engine->delay_time_1_smooth),
                                                         OrpheusEngine::kMaxDelaySamples - 1));
            int delay_samples_2 = std::max(1, std::min(static_cast<int>(engine->delay_time_2_smooth),
                                                         OrpheusEngine::kMaxDelaySamples - 1));

            const float dry = 1.0f - mix;
            const int max_d = OrpheusEngine::kMaxDelaySamples;

            for (int i = 0; i < num_frames; i++) {
                int idx = i * 2;
                float in_l = output_buffer[idx];
                float in_r = output_buffer[idx + 1];

                int wp = engine->delay_write_pos;
                int rp1 = (wp - delay_samples_1 + max_d) % max_d;
                int rp2 = (wp - delay_samples_2 + max_d) % max_d;

                float d1l = engine->delay_buffer_1l[rp1];
                float d1r = engine->delay_buffer_1r[rp1];
                float d2l = engine->delay_buffer_2l[rp2];
                float d2r = engine->delay_buffer_2r[rp2];

                engine->delay_buffer_1l[wp] = in_l + d1l * fb;
                engine->delay_buffer_1r[wp] = in_r + d1r * fb;
                engine->delay_buffer_2l[wp] = in_l + d2l * fb;
                engine->delay_buffer_2r[wp] = in_r + d2r * fb;

                float wet_l = (d1l + d2l) * 0.5f;
                float wet_r = (d1r + d2r) * 0.5f;
                output_buffer[idx]     = in_l * dry + wet_l * mix;
                output_buffer[idx + 1] = in_r * dry + wet_r * mix;

                engine->delay_write_pos = (wp + 1) % max_d;
            }
        }

        // ── HyperLFO ─────────────────────────────────────
        {
            float freq_a = engine->lfo_freq_a.load(std::memory_order_relaxed);
            float freq_b = engine->lfo_freq_b.load(std::memory_order_relaxed);
            float shape = engine->lfo_shape.load(std::memory_order_relaxed);
            int mode = engine->lfo_mode.load(std::memory_order_relaxed);
            float sr = engine->sample_rate;

            float inc_a = freq_a / sr * static_cast<float>(num_frames);
            float inc_b = freq_b / sr * static_cast<float>(num_frames);
            engine->lfo_phase_a += inc_a;
            engine->lfo_phase_b += inc_b;
            if (engine->lfo_phase_a >= 1.0f) engine->lfo_phase_a -= std::floor(engine->lfo_phase_a);
            if (engine->lfo_phase_b >= 1.0f) engine->lfo_phase_b -= std::floor(engine->lfo_phase_b);

            auto gen_wave = [&](float phase) -> float {
                float sq = phase < 0.5f ? 1.0f : -1.0f;
                float tri = 4.0f * std::fabs(phase - 0.5f) - 1.0f;
                return sq + (tri - sq) * shape;
            };

            float a = gen_wave(engine->lfo_phase_a);
            float b = gen_wave(engine->lfo_phase_b);

            float output;
            if (mode == 0) { // AND
                float ua = a * 0.5f + 0.5f;
                float ub = b * 0.5f + 0.5f;
                output = (ua * ub) * 2.0f - 1.0f;
            } else if (mode == 2) { // OR
                float ua = a * 0.5f + 0.5f;
                float ub = b * 0.5f + 0.5f;
                output = (ua + ub - ua * ub) * 2.0f - 1.0f;
            } else { // OFF (mode=1) — use A only
                output = a;
            }

            engine->lfo_output_value = output;
        }
    }

    // Peak monitoring (always runs, regardless of graph/procedural path)
    float pk_l = 0.0f, pk_r = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float l = std::fabs(output_buffer[i * 2]);
        float r = std::fabs(output_buffer[i * 2 + 1]);
        if (l > pk_l) pk_l = l;
        if (r > pk_r) pk_r = r;
    }
    engine->peak_left.store(pk_l);
    engine->peak_right.store(pk_r);

    // CPU load: elapsed time / audio buffer duration
    auto t1 = std::chrono::steady_clock::now();
    float elapsed_us = std::chrono::duration<float, std::micro>(t1 - t0).count();
    float buffer_us = (static_cast<float>(num_frames) / engine->sample_rate) * 1e6f;
    engine->cpu_load.store(elapsed_us / buffer_us, std::memory_order_relaxed);
}

void orpheus_engine_set_port(OrpheusEngine* engine,
                             const char* plugin_uri,
                             const char* symbol,
                             float value) {
    // Route through graph if available (raw value — scaled overrides below)
    OrpheusGraph* g = engine->graph.load(std::memory_order_relaxed);
    if (g) {
        uint16_t uh = engine_hash16(plugin_uri);
        uint16_t sh = engine_hash16(symbol);
        orpheus_graph_set_port(g, uh, sh, value);
    }

    // Voice coupling
    {
        static uint16_t h_voice = engine_hash16("org.balch.orpheus.plugins.voice");
        static uint16_t h_coupling = engine_hash16("coupling_depth");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_voice && symbol_hash == h_coupling) {
            engine->coupling_depth.store(value, std::memory_order_relaxed);
            return;
        }
    }

    // Mod source routing
    {
        static uint16_t h_mod = engine_hash16("org.balch.orpheus.plugins.modulation");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_mod) {
            static uint16_t h_fm_xquad = engine_hash16("fm_cross_quad");
            if (symbol_hash == h_fm_xquad) {
                engine->fm_cross_quad.store(static_cast<int>(value), std::memory_order_relaxed);
                return;
            }
            // Per-duo parameters: mod_source_N, mod_depth_N, fm_depth_N
            const char* src_names[] = {"mod_source_0","mod_source_1","mod_source_2","mod_source_3","mod_source_4","mod_source_5"};
            const char* md_names[]  = {"mod_depth_0","mod_depth_1","mod_depth_2","mod_depth_3","mod_depth_4","mod_depth_5"};
            const char* fm_names[]  = {"fm_depth_0","fm_depth_1","fm_depth_2","fm_depth_3","fm_depth_4","fm_depth_5"};
            for (int i = 0; i < 6; i++) {
                if (symbol_hash == engine_hash16(src_names[i])) { engine->mod_source[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
                if (symbol_hash == engine_hash16(md_names[i]))  { engine->mod_depth[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == engine_hash16(fm_names[i]))  { engine->fm_depth[i].store(value, std::memory_order_relaxed); return; }
            }
        }
    }

    // Resonator routing
    {
        static uint16_t h_reso = engine_hash16("org.balch.orpheus.plugins.resonator");
        static uint16_t h_target_mix = engine_hash16("target_mix");
        static uint16_t h_reso_mix = engine_hash16("reso_mix");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_reso) {
            if (symbol_hash == h_target_mix) { engine->resonator_target_mix.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_reso_mix) { engine->resonator_mix.store(value, std::memory_order_relaxed); return; }
        }
    }

    // Warps source routing
    {
        static uint16_t h_warps_uri = engine_hash16("org.balch.orpheus.plugins.warps");
        static uint16_t h_carrier_src = engine_hash16("carrier_source");
        static uint16_t h_mod_src_w = engine_hash16("modulator_source");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_warps_uri) {
            if (symbol_hash == h_carrier_src) { engine->warps_carrier_source.store(static_cast<int>(value), std::memory_order_relaxed); return; }
            if (symbol_hash == h_mod_src_w) { engine->warps_modulator_source.store(static_cast<int>(value), std::memory_order_relaxed); return; }
        }
    }

    // Bender parameters
    {
        static uint16_t h_bender = engine_hash16("org.balch.orpheus.plugins.bender");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_bender) {
            static uint16_t h_bend = engine_hash16("bend_amount");
            static uint16_t h_max_semi = engine_hash16("max_semitones");
            static uint16_t h_timbre = engine_hash16("timbre_mod");
            static uint16_t h_spring = engine_hash16("spring_vol");
            static uint16_t h_tension = engine_hash16("tension_vol");
            if (symbol_hash == h_bend) { engine->bend_amount.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_max_semi) { engine->bend_max_semitones.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_timbre) { engine->bend_timbre_mod.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_spring) { engine->bend_spring_vol.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_tension) { engine->bend_tension_vol.store(value, std::memory_order_relaxed); return; }
            // Per-string parameters
            const char* s_bend[] = {"string_bend_0","string_bend_1","string_bend_2","string_bend_3"};
            const char* s_mix[] = {"string_mix_0","string_mix_1","string_mix_2","string_mix_3"};
            const char* s_active[] = {"string_active_0","string_active_1","string_active_2","string_active_3"};
            for (int i = 0; i < 4; i++) {
                if (symbol_hash == engine_hash16(s_bend[i])) { engine->string_bend[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == engine_hash16(s_mix[i])) { engine->string_mix[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == engine_hash16(s_active[i])) { engine->string_active[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
            }
            static uint16_t h_slide_y = engine_hash16("slide_bar_y");
            static uint16_t h_slide_x = engine_hash16("slide_bar_x");
            if (symbol_hash == h_slide_y) { engine->slide_bar_y.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_slide_x) { engine->slide_bar_x.store(value, std::memory_order_relaxed); return; }
        }
    }

    // Also set engine atomics (for MI wrappers that read from atomics)
    // Keep ALL existing strcmp chains below
    if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.grains") == 0) {
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
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.resonator") == 0) {
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
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.warps") == 0) {
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
        else if (std::strcmp(symbol, "carrier_source") == 0)
            engine->warps_carrier_source.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "modulator_source") == 0)
            engine->warps_modulator_source.store(static_cast<int>(value), std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.delay") == 0) {
        if (std::strcmp(symbol, "time_1") == 0)
            engine->delay_time_1.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "time_2") == 0)
            engine->delay_time_2.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->delay_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->delay_mix.store(value, std::memory_order_relaxed);
            engine->delay_bypass.store(value < 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "mod_depth_1") == 0)
            engine->delay_mod_depth_1.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mod_depth_2") == 0)
            engine->delay_mod_depth_2.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.duolfo") == 0) {
        if (std::strcmp(symbol, "freq_a") == 0)
            engine->lfo_freq_a.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "freq_b") == 0)
            engine->lfo_freq_b.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "shape") == 0)
            engine->lfo_shape.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mode") == 0)
            engine->lfo_mode.store(static_cast<int>(value), std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.flux") == 0
          || std::strcmp(plugin_uri, "marbles") == 0) {
        if (std::strcmp(symbol, "rate") == 0)
            engine->marbles_t_rate.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "spread") == 0)
            engine->marbles_x_spread.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bias") == 0)
            engine->marbles_t_bias.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_bias") == 0)
            engine->marbles_x_bias.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "steps") == 0)
            engine->marbles_x_steps.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "jitter") == 0)
            engine->marbles_t_jitter.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu") == 0)
            engine->marbles_deja_vu.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu_length") == 0)
            engine->marbles_deja_vu_length.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "t_model") == 0)
            engine->marbles_t_model.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "t_range") == 0)
            engine->marbles_t_range.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_control_mode") == 0)
            engine->marbles_x_control_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_range") == 0)
            engine->marbles_x_range.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_scale") == 0)
            engine->marbles_x_scale.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->marbles_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.drums") == 0) {
        if (std::strcmp(symbol, "x") == 0)
            engine->grids_x.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "y") == 0)
            engine->grids_y.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_kick") == 0)
            engine->grids_density_kick.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_snare") == 0)
            engine->grids_density_snare.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_hat") == 0)
            engine->grids_density_hat.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "randomness") == 0)
            engine->grids_randomness.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->grids_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.stereo") == 0) {
        if (std::strcmp(symbol, "master_pan") == 0)
            engine->master_pan.store(value, std::memory_order_relaxed);
        else if (std::strncmp(symbol, "voice_pan_", 10) == 0) {
            int idx = std::atoi(symbol + 10);
            if (idx >= 0 && idx < kNumVoices)
                engine->voice_pan[idx].store(value, std::memory_order_relaxed);
        }
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.reverb") == 0) {
        if (std::strcmp(symbol, "amount") == 0) {
            engine->reverb_amount.store(value, std::memory_order_relaxed);
            engine->reverb_bypass.store(value <= 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "time") == 0)
            engine->reverb_time.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "damping") == 0)
            engine->reverb_damping.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "diffusion") == 0)
            engine->reverb_diffusion.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.looper") == 0) {
        if (std::strcmp(symbol, "state") == 0)
            engine->looper_requested_state.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "level") == 0)
            engine->looper_level.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->looper_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quantize") == 0)
            engine->looper_quantize.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tempo") == 0) {
        if (std::strcmp(symbol, "bpm") == 0)
            engine->clock_bpm.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "run") == 0)
            engine->clock_running.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.distortion") == 0) {
        if (std::strcmp(symbol, "drive") == 0) {
            float scaled = 1.0f + value * 14.0f;
            engine->drive_amount.store(scaled, std::memory_order_relaxed);
            // Override graph port with scaled drive (raw value was set above)
            if (g) {
                orpheus_graph_set_port(g,
                    engine_hash16(plugin_uri), engine_hash16(symbol), scaled);
            }
        }
        else if (std::strcmp(symbol, "mix") == 0)
            engine->drive_mix.store(value, std::memory_order_relaxed);
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
    // Map drum indices to repl voices (8-11) with drum engine indices:
    // 0 = bass drum  (voice 8,  engine 21)
    // 1 = snare drum (voice 9,  engine 22)
    // 2 = hi-hat     (voice 10, engine 23)
    // 3 = bass drum alt (voice 11, engine 21)
    static const int kDrumEngineIndices[] = {21, 22, 23, 21};

    if (drum_index >= 0 && drum_index < kNumReplVoices) {
        int voice_index = kNumMainVoices + drum_index;
        engine->voice_params[voice_index].engine_index.store(kDrumEngineIndices[drum_index]);
        engine->voice_params[voice_index].tune.store(60.0f);  // default pitch
        engine->voice_params[voice_index].morph.store(accent, std::memory_order_relaxed);
        engine->voice_params[voice_index].gate.store(1);       // trigger on
    }
}

void orpheus_engine_set_master_volume(OrpheusEngine* engine, float v) {
    engine->master_volume.store(v);
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
void orpheus_engine_set_bend(OrpheusEngine* engine, float v) { }

void orpheus_engine_get_monitor(OrpheusEngine* engine,
                                OrpheusMonitorData* out) {
    std::memset(out, 0, sizeof(OrpheusMonitorData));
    out->peak_left = engine->peak_left.load();
    out->peak_right = engine->peak_right.load();
    out->cpu_load = engine->cpu_load.load();
    for (int i = 0; i < kNumVoices && i < 12; i++) {
        out->voice_levels[i] = engine->voice_levels[i].load(std::memory_order_relaxed);
    }
    out->lfo_output = engine->lfo_output_value;
}

void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames) {
    std::memset(buffer, 0, max_frames * sizeof(float));
}

}  // extern "C"
