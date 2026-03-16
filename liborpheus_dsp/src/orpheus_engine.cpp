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

    // Initialize Orpheus resonators (modal + Karplus-Strong)
    engine->resonator.init(engine->sample_rate);
    engine->drum_resonator.init(engine->sample_rate);

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

    // Allocate TTS effects delay buffer (TTS sample buffer is lazily allocated on first load)
    engine->tts_delay_buffer = new float[OrpheusEngine::kTtsDelayMaxSamples]();
    engine->tts_delay_len = static_cast<int>(sample_rate * 0.375f); // 375ms delay
    // Scale reverb comb/AP lengths for actual sample rate
    for (int i = 0; i < 4; i++)
        engine->tts_comb_len[i] = std::min(
            static_cast<int>(OrpheusEngine::kTtsCombRef[i] * sample_rate),
            OrpheusEngine::kTtsCombMaxLen - 1);
    for (int i = 0; i < 2; i++)
        engine->tts_ap_len[i] = std::min(
            static_cast<int>(OrpheusEngine::kTtsApRef[i] * sample_rate),
            OrpheusEngine::kTtsApMaxLen - 1);

    // Default per-voice pans (matches Kotlin StereoPlugin defaults)
    // Voices 0-3: Quad 0
    engine->voice_pan[0].store(0.0f);
    engine->voice_pan[1].store(0.0f);
    engine->voice_pan[2].store(-0.3f);
    engine->voice_pan[3].store(-0.3f);
    // Voices 4-7: Quad 1
    engine->voice_pan[4].store(0.3f);
    engine->voice_pan[5].store(0.3f);
    engine->voice_pan[6].store(-0.7f);
    engine->voice_pan[7].store(0.7f);
    // Voices 8-11: Quad 2 / REPL
    // Voices 12-14: Drum voices (center)
    for (int i = 8; i < kNumVoices; i++)
        engine->voice_pan[i].store(0.0f);

    // Default drum voice params (matching Kotlin DrumPlugin musical defaults)
    // BD (voice 12): MIDI note 28 + 0.3*24 = 35.2 (~62Hz, low kick)
    // SD (voice 13): MIDI note 48 + 0.4*24 = 57.6 (~220Hz, mid snare)
    // HH (voice 14): MIDI note 60 + 0.6*24 = 74.4 (~700Hz, bright hat)
    static const float kDrumDefaultNote[] = {35.2f, 57.6f, 74.4f};
    static const int kDrumEngineIndices[] = {21, 22, 23};
    for (int i = 0; i < kNumDrumVoices; i++) {
        auto& vp = engine->voice_params[kDrumVoiceStart + i];
        vp.tune.store(kDrumDefaultNote[i]);
        vp.timbre.store(0.5f);
        vp.morph.store(0.5f);
        vp.harmonics.store(0.5f);
        vp.engine_index.store(kDrumEngineIndices[i]);
    }

    // Initialize automation slots: 0-11 = voice gates, 12-23 = voice freqs
    for (int i = 0; i < kNumMainVoices; i++) {
        auto& gate_slot = engine->automation_slots[i];
        gate_slot.target = AUTO_TARGET_VOICE_GATE;
        gate_slot.voice_index = static_cast<uint8_t>(i);
        gate_slot.allocated = true;

        auto& freq_slot = engine->automation_slots[kNumMainVoices + i];
        freq_slot.target = AUTO_TARGET_VOICE_FREQ;
        freq_slot.voice_index = static_cast<uint8_t>(i);
        freq_slot.allocated = true;
    }

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
        delete[] engine->tts_buffer;
        delete[] engine->tts_delay_buffer;
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
    // Dump graph topology on load
    orpheus_graph_dump_exec_order(new_graph);

    // Atomic swap: audio thread sees new graph after release.
    // WARNING: immediate free of old graph is only safe when called before
    // audio processing starts (e.g., at startup or during nativeLoadGraph).
    // If hot-swapping while audio is running, defer the free to avoid a
    // use-after-free race with the audio callback.
    auto* old = engine->graph.exchange(new_graph, std::memory_order_acq_rel);
    orpheus_graph_free(old);
    return 0;
}

void orpheus_engine_dump_state(OrpheusEngine* e) {
    printf("\n═══ Engine State ═══\n");
    printf("  sample_rate=%.0f\n", e->sample_rate);

    // Voices
    printf("  Voices:\n");
    for (int i = 0; i < kNumVoices; i++) {
        auto& vp = e->voice_params[i];
        if (!vp.ever_triggered.load()) continue;
        printf("    [%2d] engine=%d tune=%.2f gate=%d hold=%.2f decay=%.2f\n",
               i, vp.engine_index.load(), vp.tune.load(),
               vp.gate.load(), e->voice_hold_level[i].load(),
               vp.decay.load());
    }

    // Warps
    printf("  Warps: bypass=%d algo=%.2f timbre=%.2f drv=%.2f/%.2f mix=%.2f carrier=%d mod=%d\n",
           e->warps_bypass.load(), e->warps_algorithm.load(),
           e->warps_timbre.load(), e->warps_level1.load(),
           e->warps_level2.load(), e->warps_mix.load(),
           e->warps_carrier_source.load(), e->warps_modulator_source.load());

    // LFO
    printf("  LFO: freqA=%.3f freqB=%.3f shape=%.2f mode=%d\n",
           e->lfo_freq_a.load(), e->lfo_freq_b.load(),
           e->lfo_shape.load(), e->lfo_mode.load());

    // Delay
    printf("  Delay: t1=%.3f t2=%.3f fb=%.2f mix=%.2f\n",
           e->delay_time_1.load(), e->delay_time_2.load(),
           e->delay_feedback.load(), e->delay_mix.load());

    // Reverb
    printf("  Reverb: amt=%.2f time=%.2f damp=%.2f diff=%.2f\n",
           e->reverb_amount.load(), e->reverb_time.load(),
           e->reverb_damping.load(), e->reverb_diffusion.load());

    // Drums
    printf("  Drums: mix=%.2f grids_bypass=%d\n",
           e->drum_mix.load(), e->grids_bypass.load());

    // Flux
    printf("  Flux: mix=%.2f rate=%.2f spread=%.2f\n",
           e->marbles_mix.load(), e->marbles_t_rate.load(),
           e->marbles_x_spread.load());

    // Master
    printf("  Master: vol=%.2f pan=%.2f\n",
           e->master_volume.load(), e->master_pan.load());
    printf("═══════════════════\n\n");
}

// Step through automation paths at block boundaries (called from audio thread)
static void orpheus_automation_process(OrpheusEngine* engine, int num_frames) {
    float sr = engine->sample_rate;

    for (int s = 0; s < kMaxAutomationSlots; s++) {
        auto& slot = engine->automation_slots[s];
        if (!slot.allocated) continue;

        // Check for pending path swap (lock-free: single atomic load)
        int pending = slot.pending_path.load(std::memory_order_acquire);
        if (pending >= 0) {
            slot.active_path.store(pending, std::memory_order_relaxed);
            slot.current_index = 0;
            slot.start_sample = engine->sample_counter;
            slot.pending_path.store(-1, std::memory_order_release);
        }

        int active = slot.active_path.load(std::memory_order_relaxed);
        if (active < 0) continue;

        auto& path = slot.paths[active];
        if (slot.current_index >= path.count) {
            slot.active_path.store(-1, std::memory_order_relaxed);  // path completed
            continue;
        }

        // Fire all events whose time falls within this block
        // Use double to avoid float precision loss after ~5.5 min at 48kHz
        double block_end_time = static_cast<double>(engine->sample_counter + num_frames - slot.start_sample) / sr;

        while (slot.current_index < path.count &&
               static_cast<double>(path.times[slot.current_index]) <= block_end_time) {
            float value = path.values[slot.current_index];

            switch (slot.target) {
                case AUTO_TARGET_VOICE_GATE: {
                    int idx = slot.voice_index;
                    int gate = value > 0.5f ? 1 : 0;
                    engine->voice_params[idx].gate.store(gate, std::memory_order_relaxed);
                    if (gate) {
                        engine->voice_params[idx].active.store(1, std::memory_order_relaxed);
                        engine->voice_params[idx].ever_triggered.store(1, std::memory_order_relaxed);
                    }
                    break;
                }
                case AUTO_TARGET_VOICE_FREQ: {
                    int idx = slot.voice_index;
                    if (value > 0.0f) {
                        // Hz to MIDI note: note = 69 + 12 * log2(freq / 440)
                        float midi_note = 69.0f + 12.0f * log2f(value / 440.0f);
                        engine->voice_params[idx].tune.store(midi_note, std::memory_order_relaxed);
                    }
                    break;
                }
            }
            slot.current_index++;
        }
    }

    engine->sample_counter += num_frames;
}

// TTS speech effects: phaser → feedback delay → Schroeder reverb
// Tuned for dramatic "One of These Days" character:
//   - Slow deep phaser sweep (0.1–1Hz, wide 100–5000Hz range)
//   - Longer feedback delay (375ms, higher feedback ceiling)
//   - Richer reverb (higher comb feedback for ~2s decay)
// All buffer lengths are sample-rate-scaled (initialized in create()).
// Per-block cached TTS effect parameters (loaded once from atomics, used per-sample)
struct TtsEffectParams {
    float phaser_amt;
    float fb_amt;
    float reverb_amt;
    float phaser_g;  // precomputed all-pass coefficient
};

static TtsEffectParams tts_load_effect_params(OrpheusEngine* e) {
    TtsEffectParams p;
    p.phaser_amt = e->tts_phaser.load(std::memory_order_relaxed);
    p.fb_amt = e->tts_feedback.load(std::memory_order_relaxed);
    p.reverb_amt = e->tts_reverb.load(std::memory_order_relaxed);

    // Precompute phaser coefficient once per block (avoids per-sample tan())
    p.phaser_g = 0.0f;
    if (p.phaser_amt > 0.001f) {
        float sr = e->sample_rate;
        double lfo_rate = 0.1 + p.phaser_amt * 0.9;
        e->tts_phaser_lfo_phase += lfo_rate / sr;
        if (e->tts_phaser_lfo_phase >= 1.0) e->tts_phaser_lfo_phase -= 1.0;

        float tri = (e->tts_phaser_lfo_phase < 0.5)
            ? static_cast<float>(e->tts_phaser_lfo_phase * 2.0)
            : static_cast<float>(2.0 - e->tts_phaser_lfo_phase * 2.0);

        float fc = 100.0f + tri * p.phaser_amt * 4900.0f;
        float w = std::tan(3.14159265f * fc / sr);
        p.phaser_g = (1.0f - w) / (1.0f + w);
    }
    return p;
}

static float tts_process_effects(OrpheusEngine* e, float sample,
                                  const TtsEffectParams& p) {
    // 1. 6-stage all-pass phaser — slow deep sweep
    if (p.phaser_amt > 0.001f) {
        float g = p.phaser_g;
        float x = sample;
        for (int s = 0; s < OrpheusEngine::kTtsPhaserStages; s++) {
            float y = -g * x + e->tts_phaser_buf[s];
            e->tts_phaser_buf[s] = g * y + x;
            x = y;
        }
        sample = sample + x * p.phaser_amt;
    }

    // 2. Feedback delay (375ms, higher feedback for more repeats)
    if (p.fb_amt > 0.001f) {
        int delay_len = e->tts_delay_len;
        float fb_gain = p.fb_amt * 0.75f;
        if (fb_gain > 0.92f) fb_gain = 0.92f;
        float wet = e->tts_delay_buffer[e->tts_delay_write_pos];
        e->tts_delay_buffer[e->tts_delay_write_pos] = sample + e->tts_delay_fb_sample * fb_gain;
        e->tts_delay_fb_sample = wet;
        e->tts_delay_write_pos++;
        if (e->tts_delay_write_pos >= delay_len) e->tts_delay_write_pos = 0;

        sample = sample * (1.0f - p.fb_amt * 0.4f) + wet * p.fb_amt;
    }

    // 3. Schroeder reverb — richer decay (~2s)
    if (p.reverb_amt > 0.001f) {
        constexpr float COMB_FB = 0.88f;
        constexpr float AP_GAIN = 0.5f;
        constexpr float LP_COEFF = 0.6f;

        e->tts_reverb_lp += LP_COEFF * (sample - e->tts_reverb_lp);
        float damped = e->tts_reverb_lp;

        float comb_sum = 0.0f;
        for (int c = 0; c < 4; c++) {
            int len = e->tts_comb_len[c];
            int pos = e->tts_comb_pos[c];
            float delayed = e->tts_comb_bufs[c][pos];
            e->tts_comb_bufs[c][pos] = damped + delayed * COMB_FB;
            e->tts_comb_pos[c] = (pos + 1) % len;
            comb_sum += delayed;
        }
        comb_sum *= 0.25f;

        float ap_out = comb_sum;
        for (int a = 0; a < 2; a++) {
            int len = e->tts_ap_len[a];
            int pos = e->tts_ap_pos[a];
            float delayed = e->tts_ap_bufs[a][pos];
            float y = -AP_GAIN * ap_out + delayed;
            e->tts_ap_bufs[a][pos] = ap_out + AP_GAIN * y;
            e->tts_ap_pos[a] = (pos + 1) % len;
            ap_out = y;
        }

        sample = sample * (1.0f - p.reverb_amt * 0.5f) + ap_out * p.reverb_amt;
    }

    return sample;
}

void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames) {
    if (!engine || !output_buffer || num_frames <= 0) return;

    auto t0 = std::chrono::steady_clock::now();

    // Step automation paths before rendering
    orpheus_automation_process(engine, num_frames);

    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));

    OrpheusGraph* graph = engine->graph.load(std::memory_order_acquire);
    if (graph) {
        // Graph-based rendering: voices + effects chain via ODWG descriptor
        // master_out unit handles drive, pan, and soft-clip
        orpheus_graph_process(graph, engine, output_buffer, num_frames);
    } else {
        // No graph loaded — silent output (graph required for audio rendering)
    }

    // Peak monitoring is handled inside unit_process_master_out (pre-clip, matching JSyn).
    // If no graph is loaded, peaks remain at 0.

    // TTS sample playback — mix into output after graph
    {
        // Handle play trigger (atomic flag from UI thread)
        if (engine->tts_trigger.exchange(0, std::memory_order_relaxed)) {
            engine->tts_position = 0.0;
            engine->tts_playing.store(1, std::memory_order_relaxed);
        }

        int tts_len = engine->tts_buffer_length.load(std::memory_order_acquire);
        if (engine->tts_playing.load(std::memory_order_relaxed) &&
            tts_len > 0 && engine->tts_buffer) {
            float rate = engine->tts_rate.load(std::memory_order_relaxed);
            float vol = engine->tts_volume.load(std::memory_order_relaxed);
            // Adjust rate for sample rate difference (source vs engine)
            int src_rate = engine->tts_source_rate.load(std::memory_order_relaxed);
            double rate_ratio = static_cast<double>(src_rate) / engine->sample_rate;
            double step = rate * rate_ratio;
            int len = tts_len;

            // Load effect params once per block (avoids per-sample atomic loads + tan())
            TtsEffectParams fx = tts_load_effect_params(engine);

            for (int i = 0; i < num_frames; i++) {
                int intPos = static_cast<int>(engine->tts_position);
                if (intPos >= len - 1) {
                    engine->tts_playing.store(0, std::memory_order_relaxed);
                    break;
                }
                // Linear interpolation
                float frac = static_cast<float>(engine->tts_position - intPos);
                float sample = engine->tts_buffer[intPos] * (1.0f - frac)
                             + engine->tts_buffer[intPos + 1] * frac;
                float scaled = sample * vol;
                // Apply speech effects (phaser → feedback delay → reverb)
                scaled = tts_process_effects(engine, scaled, fx);
                // Mix into stereo interleaved output
                output_buffer[i * 2]     += scaled;
                output_buffer[i * 2 + 1] += scaled;
                engine->tts_position += step;
            }
        }
    }

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

    // Voice plugin parameters (coupling, mod source/depth, vibrato)
    {
        static uint16_t h_voice = engine_hash16("org.balch.orpheus.plugins.voice");
        static uint16_t h_coupling = engine_hash16("coupling_depth");
        static uint16_t h_vibrato = engine_hash16("vibrato");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_voice) {
            if (symbol_hash == h_coupling) {
                engine->coupling_depth.store(value, std::memory_order_relaxed);
                return;
            }
            static uint16_t h_total_fb = engine_hash16("total_feedback");
            if (symbol_hash == h_total_fb) {
                engine->total_feedback.store(value, std::memory_order_relaxed);
                return;
            }
            if (symbol_hash == h_vibrato) {
                engine->vibrato_depth.store(value, std::memory_order_relaxed);
                return;
            }
            // Per-duo mod source/depth: duo_mod_source_N, duo_mod_source_level_N
            static uint16_t src_hashes[] = {
                engine_hash16("duo_mod_source_0"), engine_hash16("duo_mod_source_1"),
                engine_hash16("duo_mod_source_2"), engine_hash16("duo_mod_source_3"),
                engine_hash16("duo_mod_source_4"), engine_hash16("duo_mod_source_5")};
            static uint16_t lvl_hashes[] = {
                engine_hash16("duo_mod_source_level_0"), engine_hash16("duo_mod_source_level_1"),
                engine_hash16("duo_mod_source_level_2"), engine_hash16("duo_mod_source_level_3"),
                engine_hash16("duo_mod_source_level_4"), engine_hash16("duo_mod_source_level_5")};
            for (int i = 0; i < 6; i++) {
                if (symbol_hash == src_hashes[i]) { engine->mod_source[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
                if (symbol_hash == lvl_hashes[i]) { engine->mod_depth[i].store(value, std::memory_order_relaxed); engine->fm_depth[i].store(value, std::memory_order_relaxed); return; }
            }
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
            static uint16_t mod_src_hashes[] = {
                engine_hash16("mod_source_0"), engine_hash16("mod_source_1"),
                engine_hash16("mod_source_2"), engine_hash16("mod_source_3"),
                engine_hash16("mod_source_4"), engine_hash16("mod_source_5")};
            static uint16_t mod_depth_hashes[] = {
                engine_hash16("mod_depth_0"), engine_hash16("mod_depth_1"),
                engine_hash16("mod_depth_2"), engine_hash16("mod_depth_3"),
                engine_hash16("mod_depth_4"), engine_hash16("mod_depth_5")};
            static uint16_t fm_depth_hashes[] = {
                engine_hash16("fm_depth_0"), engine_hash16("fm_depth_1"),
                engine_hash16("fm_depth_2"), engine_hash16("fm_depth_3"),
                engine_hash16("fm_depth_4"), engine_hash16("fm_depth_5")};
            for (int i = 0; i < 6; i++) {
                if (symbol_hash == mod_src_hashes[i]) { engine->mod_source[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
                if (symbol_hash == mod_depth_hashes[i])  { engine->mod_depth[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == fm_depth_hashes[i])  { engine->fm_depth[i].store(value, std::memory_order_relaxed); return; }
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
            static uint16_t h_bend = engine_hash16("bend");
            static uint16_t h_max_semi = engine_hash16("max_bend");
            static uint16_t h_random = engine_hash16("random_depth");
            static uint16_t h_timbre = engine_hash16("timbre_mod");
            static uint16_t h_spring = engine_hash16("spring_vol");
            static uint16_t h_tension = engine_hash16("tension_vol");
            if (symbol_hash == h_bend) { engine->bend_amount.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_max_semi) { engine->bend_max_semitones.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_random) { engine->bend_random_depth.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_timbre) { engine->bend_timbre_mod.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_spring) { engine->bend_spring_vol.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_tension) { engine->bend_tension_vol.store(value, std::memory_order_relaxed); return; }
            // Per-string parameters
            static uint16_t s_bend_hashes[] = {
                engine_hash16("string_bend_0"), engine_hash16("string_bend_1"),
                engine_hash16("string_bend_2"), engine_hash16("string_bend_3")};
            static uint16_t s_mix_hashes[] = {
                engine_hash16("string_mix_0"), engine_hash16("string_mix_1"),
                engine_hash16("string_mix_2"), engine_hash16("string_mix_3")};
            static uint16_t s_active_hashes[] = {
                engine_hash16("string_active_0"), engine_hash16("string_active_1"),
                engine_hash16("string_active_2"), engine_hash16("string_active_3")};
            for (int i = 0; i < 4; i++) {
                if (symbol_hash == s_bend_hashes[i]) { engine->string_bend[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == s_mix_hashes[i]) { engine->string_mix[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == s_active_hashes[i]) { engine->string_active[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
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
        else if (std::strcmp(symbol, "trigger") == 0) {
            if (value > 0.5f)
                engine->clouds_trigger.store(1, std::memory_order_relaxed);
        }
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
        else if (std::strcmp(symbol, "mode") == 0)
            engine->rings_model.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "strum") == 0)
            engine->rings_strum.store(1, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->resonator_mix.store(value, std::memory_order_relaxed);
            engine->rings_bypass.store(value < 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->rings_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
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
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->warps_mix.store(value, std::memory_order_relaxed);
            engine->warps_bypass.store(value <= 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
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
            // Map [0,1] UI knob → [-48,+48] semitone rate offset (matches Kotlin FluxProcessor)
            engine->marbles_t_rate.store((value - 0.5f) * 96.0f, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "spread") == 0)
            engine->marbles_x_spread.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_bias") == 0)
            engine->marbles_x_bias.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "probability") == 0)
            engine->marbles_t_bias.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "steps") == 0)
            engine->marbles_x_steps.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "jitter") == 0)
            engine->marbles_t_jitter.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu") == 0)
            engine->marbles_deja_vu.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu_length") == 0)
            engine->marbles_deja_vu_length.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu_mode") == 0)
            engine->marbles_deja_vu_mode.store(static_cast<int>(value), std::memory_order_relaxed);
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
        else if (std::strcmp(symbol, "mix") == 0)
            engine->marbles_mix.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulse_width") == 0)
            engine->marbles_pulse_width.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulse_width_std") == 0)
            engine->marbles_pulse_width_std.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "clock_source") == 0)
            engine->marbles_clock_source.store(static_cast<int>(value), std::memory_order_relaxed);
        // Trigger router: drum source selectors
        else if (std::strcmp(symbol, "drum_trigger_source_0") == 0)
            engine->drum_trigger_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_trigger_source_1") == 0)
            engine->drum_trigger_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_trigger_source_2") == 0)
            engine->drum_trigger_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_pitch_source_0") == 0)
            engine->drum_pitch_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_pitch_source_1") == 0)
            engine->drum_pitch_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_pitch_source_2") == 0)
            engine->drum_pitch_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        // Trigger router: quad source selectors
        else if (std::strcmp(symbol, "quad_trigger_source_0") == 0) {
            engine->quad_trigger_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "quad_trigger_source_1") == 0) {
            engine->quad_trigger_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "quad_trigger_source_2") == 0) {
            engine->quad_trigger_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "quad_pitch_source_0") == 0)
            engine->quad_pitch_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_pitch_source_1") == 0)
            engine->quad_pitch_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_pitch_source_2") == 0)
            engine->quad_pitch_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_trigger_mode_0") == 0)
            engine->quad_trigger_mode[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_trigger_mode_1") == 0)
            engine->quad_trigger_mode[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_trigger_mode_2") == 0)
            engine->quad_trigger_mode[2].store(static_cast<int>(value), std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.drum") == 0) {
        // Drum synthesis parameters: map 0-1 knob values to voice_params for drum voices 12-14
        // Frequency is converted from 0-1 to MIDI note range (matching Kotlin DrumPlugin.frequencyToNote)
        auto set_drum_param = [&](int drum_index, const char* sym) {
            auto& vp = engine->voice_params[kDrumVoiceStart + drum_index];
            if (std::strcmp(sym, "freq") == 0) {
                // BD: MIDI 28-52, SD: MIDI 48-72, HH: MIDI 60-84
                static const float kBaseNote[] = {28.0f, 48.0f, 60.0f};
                float note = kBaseNote[drum_index] + value * 24.0f;
                vp.tune.store(note, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "tone") == 0) {
                vp.timbre.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "decay") == 0) {
                vp.morph.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "p4") == 0) {
                vp.harmonics.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "p5") == 0) {
                vp.lpg_colour.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "engine") == 0) {
                // Map Kotlin PlaitsEngineId ordinal to C++ Plaits engine index
                // Kotlin: 0=BD, 1=SD, 2=HH, 3=FMDrum, 4=FM, 5=Noise, 6=Waveshaping,
                //         7=VA, 8=Additive, 9=Grain, 10=String, 11=Modal, 12=Particle,
                //         13=Swarm, 14=Chord, 15=Wavetable, 16=Speech,
                //         17=VA_VCF, 18=PhaseDistortion, 19=SixOpFM, 20=WaveTerrain,
                //         21=StringMachine, 22=Chiptune
                // C++: see kOrpheusOutGain[] in orpheus_voice.h for index meanings
                static const int kKotlinToEngine[] = {
                    21, 22, 23, 10, // BD, SD, HH, FMDrum→FM(10) (no C++ FmDrum; Kotlin has custom impl)
                    10, 17,  9,  8, // FM, Noise, Waveshaping, VA
                    12, 11, 19, 20, // Additive, Grain, String, Modal
                    18, 16, 14, 13, // Particle, Swarm, Chord, Wavetable
                    15,             // Speech
                    // V1.2 engines (ordinals 17-22)
                     0,  1,  2,  5, // VA_VCF, PhaseDistortion, SixOpFM, WaveTerrain
                     6,  7           // StringMachine, Chiptune
                };
                int ordinal = static_cast<int>(value);
                if (ordinal >= 0 && ordinal < static_cast<int>(sizeof(kKotlinToEngine)/sizeof(kKotlinToEngine[0]))) {
                    vp.engine_index.store(kKotlinToEngine[ordinal], std::memory_order_relaxed);
                    vp.engine_changed.store(1, std::memory_order_relaxed);
                }
            }
        };
        if (std::strncmp(symbol, "bd_", 3) == 0)
            set_drum_param(0, symbol + 3);
        else if (std::strncmp(symbol, "sd_", 3) == 0)
            set_drum_param(1, symbol + 3);
        else if (std::strncmp(symbol, "hh_", 3) == 0)
            set_drum_param(2, symbol + 3);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->drum_mix.store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->rings_drum_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
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
        else if (std::strcmp(symbol, "master_vol") == 0)
            engine->master_volume.store(value, std::memory_order_relaxed);
        else if (std::strncmp(symbol, "voice_pan_", 10) == 0) {
            int idx = std::atoi(symbol + 10);
            if (idx >= 0 && idx < kNumVoices) {
                engine->voice_pan[idx].store(value, std::memory_order_relaxed);
                // Compute constant-power gains and update graph pan multiply units
                if (g) {
                    float angle = ((value + 1.0f) * 0.5f) * (3.14159265f * 0.5f);
                    float gl = std::cos(angle);
                    float gr = std::sin(angle);
                    static uint16_t uh = engine_hash16("org.balch.orpheus.plugins.stereo");
                    char sym_l[32], sym_r[32];
                    snprintf(sym_l, sizeof(sym_l), "voice_pan_L_%d", idx);
                    snprintf(sym_r, sizeof(sym_r), "voice_pan_R_%d", idx);
                    orpheus_graph_set_port(g, uh, engine_hash16(sym_l), gl);
                    orpheus_graph_set_port(g, uh, engine_hash16(sym_r), gr);
                }
            }
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
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tts") == 0) {
        if (std::strcmp(symbol, "rate") == 0)
            engine->tts_rate.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "volume") == 0)
            engine->tts_volume.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "phaser") == 0)
            engine->tts_phaser.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->tts_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "reverb") == 0)
            engine->tts_reverb.store(value, std::memory_order_relaxed);
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
    if (!engine) return 0.0f;

    if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.looper") == 0) {
        if (std::strcmp(symbol, "position") == 0) {
            // Normalized position: write position / max during record, read / length during play
            int state = engine->looper_current_state;
            int pos = engine->looper_position;
            if (state == 1) { // recording
                return static_cast<float>(pos) / OrpheusEngine::kMaxLoopSamples;
            } else if ((state == 2 || state == 3) && engine->looper_length > 0) {
                return static_cast<float>(pos) / engine->looper_length;
            }
            return 0.0f;
        }
        if (std::strcmp(symbol, "duration") == 0) {
            return static_cast<float>(engine->looper_length) / engine->sample_rate;
        }
        if (std::strcmp(symbol, "state") == 0) {
            return static_cast<float>(engine->looper_current_state);
        }
    }
    if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tts") == 0) {
        if (std::strcmp(symbol, "playing") == 0) {
            return static_cast<float>(engine->tts_playing.load(std::memory_order_relaxed));
        }
    }
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
    // Volume is applied in unit_process_master_out (after pan, before clip)
    // matching JSyn's StereoPlugin chain: pan → volume → peak → clip
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

}  // extern "C"
