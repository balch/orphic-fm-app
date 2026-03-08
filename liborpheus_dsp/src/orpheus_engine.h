#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"
#include "orpheus_graph.h"

// Include MI Plaits voice
#include "plaits/dsp/voice.h"

// Include MI Clouds granular processor
#include "clouds/dsp/granular_processor.h"

// Include MI Rings resonator
#include "rings/dsp/part.h"
#include "rings/dsp/patch.h"
#include "rings/dsp/performance_state.h"

// Include MI Warps modulator
#include "warps/dsp/modulator.h"

// Include MI Marbles random sequencer
#include "marbles/random/t_generator.h"
#include "marbles/random/x_y_generator.h"
#include "marbles/random/random_stream.h"
#include "marbles/random/random_generator.h"
#include "stmlib/utils/gate_flags.h"

#include <atomic>
#include <cstring>

static constexpr int kNumMainVoices = 8;
static constexpr int kNumReplVoices = 4;
static constexpr int kNumVoices = kNumMainVoices + kNumReplVoices;
static constexpr int kVoiceAllocBytes = 32768;  // 32KB per voice (generous)

struct OrpheusEngine {
    float sample_rate;

    // Graph scheduler (null until nativeLoadGraph called)
    // Atomic: written from JNI thread, read from audio thread
    std::atomic<OrpheusGraph*> graph{nullptr};

    // Plaits voices
    plaits::Voice voices_dsp[kNumVoices];
    uint8_t voice_alloc_buffers[kNumVoices][kVoiceAllocBytes];

    // Per-voice parameter state (written from UI, read from audio)
    struct VoiceParams {
        std::atomic<float> tune{60.0f};
        std::atomic<int> gate{0};
        std::atomic<float> harmonics{0.5f};
        std::atomic<float> timbre{0.5f};
        std::atomic<float> morph{0.5f};
        std::atomic<float> decay{0.5f};     // LPG decay (maps to Plaits patch.decay)
        std::atomic<float> lpg_colour{0.5f}; // LPG colour (filter character)
        std::atomic<int> engine_index{0};
        std::atomic<int> active{0};          // 0 = voice disabled (no Plaits rendering)
        std::atomic<int> ever_triggered{0};  // 0 = never gated on; skip render until first gate
        bool graph_gate_prev{false};         // previous gate state for graph edge detection
        bool graph_trigger_pending{false};   // rising edge detected, not yet consumed by render
        std::atomic<int> engine_changed{0};  // 1 = engine just changed, force LPG retrigger
    };
    VoiceParams voice_params[kNumVoices];

    // Master controls
    std::atomic<float> master_volume{0.8f};
    std::atomic<float> master_pan{0.0f};       // -1..+1, 0 = center

    // Per-voice stereo pan (-1..+1, constant-power)
    // Defaults match Kotlin: 0-1 center, 2-3 left(-0.3), 4-5 right(0.3), 6 left(-0.7), 7 right(0.7), 8-11 center
    std::atomic<float> voice_pan[kNumVoices] = {};  // initialized in create()

    // Monitor
    std::atomic<float> peak_left{0.0f};
    std::atomic<float> peak_right{0.0f};
    std::atomic<float> cpu_load{0.0f};
    std::atomic<float> voice_levels[kNumVoices] = {};

    // Clouds granular processor
    clouds::GranularProcessor clouds_processor;
    uint8_t clouds_large_buffer[118784];
    uint8_t clouds_small_buffer[65408];

    // Clouds parameter atomics (written from UI, read from audio thread)
    std::atomic<float> clouds_position{0.5f};
    std::atomic<float> clouds_size{0.5f};
    std::atomic<float> clouds_pitch{0.0f};
    std::atomic<float> clouds_density{0.5f};
    std::atomic<float> clouds_texture{0.5f};
    std::atomic<float> clouds_dry_wet{0.5f};
    std::atomic<float> clouds_feedback{0.0f};
    std::atomic<float> clouds_reverb{0.0f};
    std::atomic<int>   clouds_freeze{0};
    std::atomic<int>   clouds_mode{0};       // PlaybackMode enum
    std::atomic<int>   clouds_bypass{1};     // bypassed by default

    // Rings resonator
    rings::Part rings_part;
    uint16_t rings_reverb_buffer[32768];  // 64KB reverb buffer for Rings

    // Rings parameter atomics (written from UI, read from audio thread)
    std::atomic<float> rings_structure{0.5f};
    std::atomic<float> rings_brightness{0.5f};
    std::atomic<float> rings_damping{0.5f};
    std::atomic<float> rings_position{0.5f};
    std::atomic<float> rings_frequency{60.0f};  // MIDI note
    std::atomic<int>   rings_model{0};          // ResonatorModel enum
    std::atomic<int>   rings_polyphony{1};
    std::atomic<int>   rings_strum{0};          // trigger: set to 1, audio thread clears
    std::atomic<int>   rings_bypass{1};         // bypassed by default
    std::atomic<int>   rings_internal_exciter{1}; // use internal noise exciter
    std::atomic<float> resonator_target_mix{0.5f};     // 0=drum, 0.5=both, 1=synth
    std::atomic<float> resonator_mix{0.5f};            // wet/dry

    // Warps modulator
    warps::Modulator warps_modulator;

    // Warps parameter atomics (written from UI, read from audio thread)
    std::atomic<float> warps_algorithm{0.0f};
    std::atomic<float> warps_timbre{0.5f};
    std::atomic<float> warps_level1{0.5f};
    std::atomic<float> warps_level2{0.5f};
    std::atomic<int>   warps_bypass{1};         // bypassed by default

    // ── Warps Source Routing ────────────────────────────
    static constexpr int kNumWarpsSources = 7;
    // 0=SYNTH, 1=DRUMS(grains), 2=REPL(v8-11), 3=LFO, 4=RESONATOR(aux), 5=WARPS(feedback), 6=FLUX
    float warps_source_buffers[kNumWarpsSources][kMaxFrames] = {};
    float warps_feedback_l[kMaxFrames] = {};
    float warps_feedback_r[kMaxFrames] = {};
    std::atomic<int> warps_carrier_source{0};     // 0-6 enum
    std::atomic<int> warps_modulator_source{0};   // 0-6 enum

    // ── Per-voice Engine 0 (OSC mode) state ─────────
    // Used when engine_index == -1 (OSC mode): triangle+square with ADSR + hold
    struct VoiceOscState {
        float tri_phase = 0.0f;
        float sq_phase = 0.0f;
        // ADSR envelope
        float env_level = 0.0f;
        int   env_stage = 0;  // 0=idle, 1=attack, 2=decay, 3=sustain, 4=release
        bool  env_gate_was_on = false;
        // Smoothed hold ramp (20ms)
        float hold_smoothed = 0.0f;
    };
    VoiceOscState voice_osc_state[kNumVoices];

    // Per-voice hold level (0.0-1.0, raw from UI before scaling)
    std::atomic<float> voice_hold_level[kNumVoices] = {};

    // ── Voice Coupling ─────────────────────────────────
    float voice_envelope[kNumVoices] = {};             // peak follower per voice
    std::atomic<float> coupling_depth{0.0f};           // 0 = off, scales partner env → pitch

    // ── Mod Source Routing + FM ─────────────────────────
    static constexpr int kNumDuos = 6;
    float voice_last_output[kNumVoices] = {};          // previous block's peak output
    float marbles_cv_output[2] = {};                   // cached Marbles X1/X2 CV
    std::atomic<int> mod_source[kNumDuos] = {};        // per-duo: 0=OFF, 1=VOICE_FM, 2=LFO, 3=FLUX
    std::atomic<float> mod_depth[kNumDuos] = {};       // per-duo timbre mod depth
    std::atomic<float> fm_depth[kNumDuos] = {};        // per-duo FM depth (semitones)
    std::atomic<int> fm_cross_quad{0};                 // 0=duo pairs, 1=cross-quad circular

    // ── Marbles Random Sequencer ─────────────────────
    // MI Marbles: TGenerator (rhythmic gates) + XYGenerator (random CV)
    marbles::RandomGenerator marbles_rng;
    marbles::RandomStream    marbles_random_stream;
    marbles::TGenerator      marbles_t_generator;
    marbles::XYGenerator     marbles_xy_generator;

    // Working buffers for Marbles processing (sized to kMaxFrames=512)
    stmlib::GateFlags marbles_gate_flags[kMaxFrames] = {};
    float marbles_ramp_external[kMaxFrames] = {};
    float marbles_ramp_master[kMaxFrames] = {};
    float marbles_ramp_slave0[kMaxFrames] = {};
    float marbles_ramp_slave1[kMaxFrames] = {};
    bool  marbles_gate_out[kMaxFrames * 2] = {};   // 2 channels interleaved (t1, t2)
    float marbles_xy_output[kMaxFrames * 4] = {};  // 4 channels interleaved (x1, x2, x3, y)
    stmlib::GateFlags marbles_prev_gate_flag{stmlib::GATE_FLAG_LOW};  // for edge detection

    // Parameter atomics (written from UI, read from audio thread)
    std::atomic<float> marbles_t_rate{0.0f};        // -48..+48 semitone rate offset
    std::atomic<float> marbles_t_bias{0.5f};        // 0..1 gate probability bias
    std::atomic<float> marbles_t_jitter{0.0f};      // 0..1 jitter amount
    std::atomic<int>   marbles_t_model{0};           // TGeneratorModel enum (0-6)
    std::atomic<int>   marbles_t_range{1};           // TGeneratorRange enum (0-2, default 1x)
    std::atomic<float> marbles_x_spread{0.5f};       // 0..1 CV spread
    std::atomic<float> marbles_x_bias{0.5f};         // 0..1 CV bias
    std::atomic<float> marbles_x_steps{0.5f};        // 0..1 quantization steps
    std::atomic<int>   marbles_x_control_mode{0};    // ControlMode enum (0-2)
    std::atomic<int>   marbles_x_range{1};           // VoltageRange enum (0-2)
    std::atomic<int>   marbles_x_scale{0};           // scale index
    std::atomic<float> marbles_deja_vu{0.0f};        // 0..1 deja vu amount
    std::atomic<int>   marbles_deja_vu_length{8};    // loop length (1-16)
    std::atomic<int>   marbles_bypass{1};            // bypassed by default

    // ── Drive (tanh saturation) ──────────────────────
    std::atomic<float> drive_amount{1.0f};       // 1.0 = clean, higher = more saturation
    std::atomic<float> drive_mix{0.0f};          // 0 = fully dry, 1 = fully wet (distorted)

    // ── Dual Delay ───────────────────────────────────
    static constexpr int kMaxDelaySamples = 110250; // ~2.3s at 48kHz
    float delay_buffer_1l[kMaxDelaySamples] = {};
    float delay_buffer_1r[kMaxDelaySamples] = {};
    float delay_buffer_2l[kMaxDelaySamples] = {};
    float delay_buffer_2r[kMaxDelaySamples] = {};
    int delay_write_pos{0};

    std::atomic<float> delay_time_1{0.25f};     // 0..1 → 0.01..2.0s
    std::atomic<float> delay_time_2{0.375f};
    std::atomic<float> delay_feedback{0.3f};    // 0..0.95
    std::atomic<float> delay_mix{0.0f};         // 0..1 dry/wet
    std::atomic<float> delay_mod_depth_1{0.0f}; // LFO mod depth for delay 1
    std::atomic<float> delay_mod_depth_2{0.0f}; // LFO mod depth for delay 2
    std::atomic<int>   delay_bypass{1};         // bypassed by default

    // Smoothed delay times (to prevent zipper noise)
    float delay_time_1_smooth{0.0f};
    float delay_time_2_smooth{0.0f};

    // ── Vibrato (LFO → pitch modulation) ─────────────────────
    std::atomic<float> vibrato_depth{0.0f};    // 0..1 → 0..2 semitones pitch mod

    // ── HyperLFO (dual oscillator with logic combination) ─
    float lfo_phase_a{0.0f};
    float lfo_phase_b{0.0f};
    float lfo_output_value{0.0f};              // latest output for monitoring

    std::atomic<float> lfo_freq_a{1.0f};       // Hz
    std::atomic<float> lfo_freq_b{1.0f};       // Hz
    std::atomic<float> lfo_shape{0.5f};        // 0=square, 1=triangle
    std::atomic<int>   lfo_mode{1};            // 0=AND, 1=OFF (independent), 2=OR

    // ── Dattorro Plate Reverb ─────────────────────────
    static constexpr int kReverbBufferSize = 32768;
    static constexpr int kReverbMask = kReverbBufferSize - 1;
    float reverb_buffer[kReverbBufferSize] = {};
    int   reverb_write_pos{0};

    // LFO state (two cosine oscillators for modulated delay reads)
    float reverb_lfo1_phase{0.0f};
    float reverb_lfo2_phase{0.0f};
    float reverb_lfo1_value{0.0f};
    float reverb_lfo2_value{0.0f};

    // LP filter state
    float reverb_lp_decay1{0.0f};
    float reverb_lp_decay2{0.0f};

    // Parameters (atomics, written from UI)
    std::atomic<float> reverb_amount{0.0f};     // 0-1, wet level
    std::atomic<float> reverb_time{0.5f};       // 0-1, feedback/decay
    std::atomic<float> reverb_damping{0.7f};    // 0-1, LP coefficient
    std::atomic<float> reverb_diffusion{0.625f};// 0-1, allpass coefficient
    std::atomic<int>   reverb_bypass{1};        // self-bypass when amount<=0.001

    // ── Master Clock ──────────────────────────────────
    double clock_phase{0.0};          // fractional accumulator (double to avoid drift)
    int    clock_tick_count{0};       // 0..23 within each beat (24 PPQN)
    int    clock_beat_count{0};       // beats within bar (0..3 for 4/4)
    std::atomic<float> clock_bpm{120.0f};
    std::atomic<int>   clock_running{1};  // 1 = running, 0 = stopped

    // ── Grids Drum Pattern Generator ──────────────────
    int grids_step{0};                    // current step (0..31) in the pattern
    int grids_pulse_count{0};             // sub-step counter (0..5 for 24PPQN→4PPQN)
    float grids_trigger_duration{0.001f}; // trigger pulse width in seconds
    int grids_trigger_countdown[3] = {};  // countdown samples for each channel trigger
    uint32_t grids_rng_state{12345};      // LCG PRNG for randomness perturbation
    std::atomic<float> grids_x{0.5f};
    std::atomic<float> grids_y{0.5f};
    std::atomic<float> grids_density_kick{0.5f};
    std::atomic<float> grids_density_snare{0.5f};
    std::atomic<float> grids_density_hat{0.5f};
    std::atomic<float> grids_randomness{0.0f};
    std::atomic<int>   grids_bypass{1};

    // ── Beat-Quantized Looper ─────────────────────────
    static constexpr int kMaxLoopSamples = 48000 * 30;  // 30 seconds at 48kHz
    float* looper_buffer_l{nullptr};  // heap allocated in create()
    float* looper_buffer_r{nullptr};
    int    looper_length{0};           // recorded loop length in samples
    int    looper_position{0};         // current read/write position
    int    looper_current_state{0};    // actual state: 0=stop, 1=record, 2=play, 3=overdub
    bool   looper_pending_transition{false};
    int    looper_pending_state{0};    // requested state (waits for beat boundary)
    std::atomic<int>   looper_requested_state{0}; // from UI: 0=stop, 1=record, 2=play, 3=overdub
    std::atomic<float> looper_level{1.0f};        // playback level
    std::atomic<float> looper_feedback{0.8f};     // overdub feedback
    std::atomic<int>   looper_quantize{1};        // 1 = quantize to beat, 0 = immediate

    // ── Global Bender ──────────────────────────────────
    std::atomic<float> bend_amount{0.0f};              // -1..+1
    std::atomic<float> bend_max_semitones{24.0f};
    std::atomic<float> bend_timbre_mod{0.3f};
    std::atomic<float> bend_spring_vol{0.4f};
    std::atomic<float> bend_tension_vol{0.015f};

    // Internal bender state (audio thread only)
    float bend_tension_phase{0.0f};
    float bend_tension_env{0.0f};
    int   bend_tension_env_stage{0};          // 0=off, 1=attack, 2=decay, 3=sustain, 4=release
    float bend_spring_phase{0.0f};
    float bend_spring_env{0.0f};
    int   bend_spring_env_stage{0};
    float bend_wobble_phase{0.0f};
    float bend_random_lfo_phase{0.0f};
    bool  bend_was_active{false};

    // ── Per-String Bender ──────────────────────────────
    struct StringState {
        float bend_amount{0.0f};
        float voice_mix{0.0f};
        bool  is_active{false};
        bool  was_active{false};
        float tension_phase{0.0f};
        float tension_env{0.0f};
        int   tension_env_stage{0};
        float spring_phase{0.0f};
        float spring_env{0.0f};
        int   spring_env_stage{0};
        float wobble_phase{0.0f};
        float pluck_phase{0.0f};
        float pluck_env{0.0f};
        int   pluck_env_stage{0};
        float slide_phase{0.0f};
        float slide_lfo_phase{0.0f};
        float slide_ramp{0.0f};
    };
    StringState string_state[4];

    // Per-string atomics (from UI)
    std::atomic<float> string_bend[4] = {};
    std::atomic<float> string_mix[4] = {};
    std::atomic<int>   string_active[4] = {};
    std::atomic<float> string_base_freq[4] = {};       // initialized in create()

    // Slide bar
    std::atomic<float> slide_bar_y{0.0f};
    std::atomic<float> slide_bar_x{0.0f};

    // Output arrays (read by unit_process_plaits)
    float voice_bend_cv[kNumMainVoices] = {};          // pitch bend semitones per voice
    float voice_mix_cv[kNumMainVoices] = {};            // voice volume multiplier per voice (default 1.0)
};
