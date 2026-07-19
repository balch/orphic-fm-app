#include "orpheus_engine.h"
#include "orpheus_graph.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#if defined(__SSE__)
#include <xmmintrin.h>
#endif
#ifndef __EMSCRIPTEN__
#include <thread>
#endif
extern "C" {

// Reset static DSP state for units that use singletons.
// Defined in orpheus_unit_horn.cpp.
void horn_reset_static();

OrpheusEngine* orpheus_engine_create(float sample_rate) {
    auto* engine = new OrpheusEngine();
    engine->sample_rate = sample_rate;
    engine->spectrum_analyzer.Init(sample_rate);

    // Initialize all Plaits voices (OrpheusVoice: direct engine render)
    for (int i = 0; i < kNumVoices; i++) {
        stmlib::BufferAllocator allocator(
            engine->voice_alloc_buffers[i], kVoiceAllocBytes);
        engine->voices_dsp[i].Init(&allocator);
    }

    // Initialize all Braids macro oscillators (used when engine_index >= 100)
    for (int i = 0; i < kNumVoices; i++) {
        engine->braids_voices[i].Init();
    }

    // Initialize bass voice
    {
        stmlib::BufferAllocator bass_allocator(engine->bass_voice_alloc_buffer, kVoiceAllocBytes);
        engine->bass_voice.Init(&bass_allocator);
    }
    engine->bass_seq_state.initialized = false;

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

    // Initialize Marbles random sequencer with a placeholder state; the real
    // seed is applied by unit_process_marbles on the first audio block (driven
    // by the marbles_seed atomic). 0xDEADBEEF is just a safe non-zero state.
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

    // Initialize Frames PolyLFO
    engine->poly_lfo.Init();

    // Initialize Lorenz attractor (chaotic modulation)
    engine->lorenz_generator.Init();
    engine->lorenz_generator.set_index(0);

    // Initialize Tides2 poly slope generator
    engine->tides_generator.Init();
    engine->tides_ramp_extractor.Init(sample_rate, sample_rate * 0.25f);

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

    // Seed the master fader from the default master_volume (0.7) so the very
    // first audio frame plays at the expected gain. MasterFader defaults its
    // internal current_ to 1.0; without this snap, the first block would be
    // ~3 dB hotter than intended.
    float v0 = engine->master_volume.load(std::memory_order_relaxed);
    engine->master_fader_l.reset(v0);
    engine->master_fader_r.reset(v0);

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
        horn_reset_static();
        orpheus_graph_free(engine->graph.load(std::memory_order_relaxed));
        delete[] engine->looper_buffer_l;
        delete[] engine->looper_buffer_r;
        delete[] engine->tts_buffer;
        delete[] engine->tts_delay_buffer;
        delete engine->pulsar_state;
        delete engine;
    }
}

// Free a graph that was just swapped out, waiting for any in-flight audio
// block to finish with it first. The render thread loads the graph pointer
// once per process call, so one blocks_rendered advance after the swap
// proves no block that started before the swap is still running. If audio
// is idle the counter never advances and the timeout expires — freeing
// immediately is then safe because nothing is reading the old graph.
// The one-advance proof holds only because a single thread calls
// orpheus_engine_process per engine; a second concurrent render caller
// (offline export, a second host) would require revisiting this.
static void orpheus_graph_retire(OrpheusEngine* engine, OrpheusGraph* old_graph) {
#ifndef __EMSCRIPTEN__
    uint64_t epoch = engine->blocks_rendered.load(std::memory_order_acquire);
    for (int i = 0; i < 100; i++) {  // up to ~100ms grace
        if (engine->blocks_rendered.load(std::memory_order_acquire) != epoch) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
#endif
    // WASM is single-threaded: loader and renderer share one thread, so no
    // in-flight block can exist and the wait (which would never advance) is
    // skipped entirely.
    orpheus_graph_free(old_graph);
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

    // Atomic swap: audio thread sees the new graph at its next block.
    auto* old = engine->graph.exchange(new_graph, std::memory_order_acq_rel);
    if (old) orpheus_graph_retire(engine, old);
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

    // PolyLFO
    printf("  PolyLFO: bypass=%d rate=%.2f shape=%.2f spread=%.2f coupling=%.2f\n",
           e->poly_lfo_bypass.load(), e->poly_lfo_rate.load(),
           e->poly_lfo_shape.load(), e->poly_lfo_spread.load(),
           e->poly_lfo_coupling.load());

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

// Flush denormals to zero on the audio thread.
// Without this, tiny float values in filter feedback paths (SVF state,
// compressor envelope, etc.) trigger microcode traps on many ARM cores,
// causing 10-100x slowdown per operation → buffer underruns → clicking.
static inline void enable_flush_to_zero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);  // FZ bit
    __asm__ __volatile__("msr fpcr, %0" :: "r"(fpcr));
#elif defined(__arm__)
    uint32_t fpscr;
    __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1 << 24);  // FZ bit
    __asm__ __volatile__("vmsr fpscr, %0" :: "r"(fpscr));
#elif defined(__SSE__)
    _mm_setcsr(_mm_getcsr() | 0x8040);  // FTZ + DAZ
#endif
}

void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames) {
    if (!engine || !output_buffer || num_frames <= 0) return;

    enable_flush_to_zero();

    auto t0 = std::chrono::steady_clock::now();

    // Step automation paths before rendering
    orpheus_automation_process(engine, num_frames);

    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));

    OrpheusGraph* graph = engine->graph.load(std::memory_order_acquire);
    if (graph) {
        // Process in kMaxFrames (512) chunks for large Bluetooth callbacks.
        // orpheus_graph_process clamps to kMaxFrames internally — without
        // chunking, frames beyond 512 would be silence (causing BT crackling).
        int frames_done = 0;
        while (frames_done < num_frames) {
            int chunk = num_frames - frames_done;
            if (chunk > kMaxFrames) chunk = kMaxFrames;
            orpheus_graph_process(graph, engine, output_buffer + frames_done * 2, chunk);
            frames_done += chunk;
        }
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

    // Save the LAST chunk's master output (mono downmix) for turntable "master" source.
    // turntable_prev_master is float[kMaxFrames]; the turntable unit reads up to kMaxFrames
    // per chunk. For BT callbacks > kMaxFrames, offset into the final chunk so the turntable
    // reads the most recent audio, not stale data from the first chunk.
    int master_frames = num_frames < kMaxFrames ? num_frames : kMaxFrames;
    int last_chunk_offset = num_frames - master_frames;
    for (int i = 0; i < master_frames; i++) {
        int src = (last_chunk_offset + i) * 2;
        engine->turntable_prev_master[i] = (output_buffer[src] + output_buffer[src + 1]) * 0.5f;
    }

    // CPU load: elapsed time / audio buffer duration
    auto t1 = std::chrono::steady_clock::now();
    float elapsed_us = std::chrono::duration<float, std::micro>(t1 - t0).count();
    float buffer_us = (static_cast<float>(num_frames) / engine->sample_rate) * 1e6f;
    engine->cpu_load.store(elapsed_us / buffer_us, std::memory_order_relaxed);

    engine->blocks_rendered.fetch_add(1, std::memory_order_release);
}

// Max frames per callback — CoreAudio typically uses 512 or 1024.
// 4096 covers all reasonable buffer sizes with no dynamic allocation.
static constexpr int kMaxDeinterleavedFrames = 4096;

void orpheus_engine_process_deinterleaved(OrpheusEngine* engine,
                                          float* left, float* right,
                                          int num_frames) {
    if (!engine || !left || !right || num_frames <= 0) return;
    if (num_frames > kMaxDeinterleavedFrames) num_frames = kMaxDeinterleavedFrames;

    // Thread-local static avoids 32KB stack pressure on CoreAudio's
    // real-time thread (which may have only 64KB stack on iOS).
    static thread_local float scratch[kMaxDeinterleavedFrames * 2];

    orpheus_engine_process(engine, scratch, num_frames);

    // Deinterleave: L0,R0,L1,R1,... → separate L[],R[]
    for (int i = 0; i < num_frames; i++) {
        left[i]  = scratch[i * 2];
        right[i] = scratch[i * 2 + 1];
    }
}

// ── Master-bus fade and tape-stop (song transitions) ──────────
void orpheus_engine_master_fade(OrpheusEngine* engine, float target, int samples, int curve) {
    if (!engine) return;
    auto c = static_cast<orpheus::FadeCurve>(curve);
    engine->master_fader_l.arm(target, samples, c);
    engine->master_fader_r.arm(target, samples, c);
}

void orpheus_engine_master_tape_stop(OrpheusEngine* engine, int samples) {
    if (!engine) return;
    engine->master_tape_stop_l.arm(samples);
    engine->master_tape_stop_r.arm(samples);
}

void orpheus_engine_master_scratch(OrpheusEngine* engine, int samples) {
    if (!engine) return;
    engine->master_scratch_l.arm(samples, engine->sample_rate, 0);
    engine->master_scratch_r.arm(samples, engine->sample_rate, 0x55555555u);
}

void orpheus_engine_master_filter(OrpheusEngine* engine, int samples) {
    if (!engine) return;
    engine->master_filter_l.arm(samples, engine->sample_rate, 0);
    engine->master_filter_r.arm(samples, engine->sample_rate, 7);
    engine->master_leslie_l.arm(samples, engine->sample_rate);
    engine->master_leslie_r.arm(samples, engine->sample_rate);
}

float orpheus_engine_master_volume_now(OrpheusEngine* engine) {
    if (!engine) return 0.0f;
    return engine->master_fader_l.current();
}

}  // extern "C"
