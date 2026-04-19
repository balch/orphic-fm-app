#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"
#include "orpheus_graph.h"

// OrpheusVoice: direct Engine::Render() without LPG/limiter/int16
#include "orpheus_voice.h"
#include "orpheus_viz.h"

// Include MI Clouds granular processor
#include "clouds/dsp/granular_processor.h"

// Orpheus resonator (modal + Karplus-Strong, ported from Kotlin)
#include "orpheus_resonator.h"

// Include MI Warps modulator
// Undefine Clouds resource macros that collide with Warps
#undef LUT_XFADE_IN
#undef LUT_XFADE_IN_SIZE
#undef LUT_XFADE_OUT
#undef LUT_XFADE_OUT_SIZE
#include "warps/dsp/modulator.h"

// Include MI Frames PolyLFO
#include "frames/poly_lfo.h"

// Include MI Marbles random sequencer
// Undefine Plaits resource macros that collide with Marbles
#undef LUT_SINE
#undef LUT_SINE_SIZE
#include "marbles/random/t_generator.h"
#include "marbles/random/x_y_generator.h"
#include "marbles/random/random_stream.h"
#include "marbles/random/random_generator.h"
#include "stmlib/utils/gate_flags.h"

// Include MI Tides2 poly slope generator
// Undefine resource macros that collide with Tides2
#undef LUT_SINE
#undef LUT_SINE_SIZE
#undef LUT_BIPOLAR_FOLD
#undef LUT_BIPOLAR_FOLD_SIZE
#include "tides2/poly_slope_generator.h"
#include "tides2/ramp/ramp_extractor.h"

#include "orpheus_automation.h"

// Include MI Streams Lorenz generator
#include "streams/lorenz_generator.h"

#include "orpheus_unit_bass.h"
#include "orpheus_unit_pulsar.h"
#include "orpheus_turntable.h"

#include <atomic>
#include <cstring>

static constexpr int kNumMainVoices = 12;
static constexpr int kNumDrumVoices = 3;
static constexpr int kDrumVoiceStart = kNumMainVoices;  // 12
static constexpr int kNumVoices = kNumMainVoices + kNumDrumVoices;  // 15
static constexpr int kVoiceAllocBytes = 32768;  // 32KB per voice (generous)

struct OrpheusEngine {
    float sample_rate;

    // Graph scheduler (null until nativeLoadGraph called)
    // Atomic: written from JNI thread, read from audio thread
    std::atomic<OrpheusGraph*> graph{nullptr};

    // Plaits voices (direct engine render, no LPG/limiter)
    OrpheusVoice voices_dsp[kNumVoices];
    uint8_t voice_alloc_buffers[kNumVoices][kVoiceAllocBytes];

    // Per-voice parameter state (written from UI, read from audio)
    struct VoiceParams {
        std::atomic<float> tune{60.0f};
        std::atomic<int> gate{0};
        std::atomic<float> harmonics{0.5f};
        std::atomic<float> timbre{0.5f};
        std::atomic<float> morph{0.5f};
        std::atomic<float> accent{0.8f};    // velocity/accent for Plaits Engine::Render()
        std::atomic<float> decay{0.5f};     // LPG decay (maps to Plaits patch.decay)
        std::atomic<float> lpg_colour{0.5f}; // LPG colour (filter character)
        std::atomic<int> engine_index{0};
        std::atomic<int> active{0};          // 0 = voice disabled (no Plaits rendering)
        std::atomic<int> ever_triggered{0};  // 0 = never gated on; skip render until first gate
        bool graph_gate_prev{false};         // previous gate state for graph edge detection
        bool graph_trigger_pending{false};   // rising edge detected, not yet consumed by render
        bool ext_retrigger{false};           // external gate re-trigger: force ADSR back to attack
        std::atomic<int> engine_changed{0};  // 1 = engine just changed, force LPG retrigger
    };
    VoiceParams voice_params[kNumVoices];

    // Master controls
    std::atomic<float> master_volume{0.7f};
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
    std::atomic<float> clouds_dry_wet{0.0f};
    std::atomic<float> clouds_feedback{0.0f};
    std::atomic<float> clouds_reverb{0.0f};
    std::atomic<int>   clouds_freeze{0};
    std::atomic<int>   clouds_trigger{0};    // rising-edge flag, cleared by audio thread
    std::atomic<int>   clouds_mode{0};       // PlaybackMode enum
    std::atomic<int>   clouds_bypass{0};     // not bypassed — dry_wet=0 handles passthrough

    // Orpheus resonator (main)
    OrpheusResonator resonator;

    // Rings parameter atomics (written from UI, read from audio thread)
    std::atomic<float> rings_structure{0.5f};
    std::atomic<float> rings_brightness{0.5f};
    std::atomic<float> rings_damping{0.5f};
    std::atomic<float> rings_position{0.5f};
    std::atomic<float> rings_frequency{60.0f};  // MIDI note
    std::atomic<int>   rings_model{0};          // ResonatorModel enum
    std::atomic<int>   rings_strum{0};          // trigger: set to 1, audio thread clears
    std::atomic<int>   rings_bypass{1};         // bypassed by default
    std::atomic<float> resonator_target_mix{0.5f};     // 0=drum, 0.5=both, 1=synth
    std::atomic<float> resonator_mix{0.0f};            // wet/dry (0=bypass, 1=fully wet)

    // Percussive envelope for drum voices (audio-thread-only, not shared with UI)
    // Only applied to engines without built-in envelopes (alreadyEnveloped=false).
    float drum_env_amplitude[kNumDrumVoices] = {};  // 0..1, reset to 1.0 on trigger

    // Drum mix/volume (0..1 from UI, applied as baseGain * mix to drum output)
    std::atomic<float> drum_mix{0.7f};

    // Orpheus resonator (drum direct path, moduleIndex=1)
    OrpheusResonator drum_resonator;
    std::atomic<int>   rings_drum_bypass{0};   // enabled by default (drum MAIN path)

    // Warps modulator
    warps::Modulator warps_modulator;

    // Warps parameter atomics (written from UI, read from audio thread)
    std::atomic<float> warps_algorithm{0.0f};
    std::atomic<float> warps_timbre{0.5f};
    std::atomic<float> warps_level1{0.5f};
    std::atomic<float> warps_level2{0.5f};
    std::atomic<float> warps_mix{0.0f};          // dry/wet mix (0=dry, 1=wet)
    float warps_smooth_mix{0.0f};               // smoothed mix (audio thread only, no clicks)
    std::atomic<int>   warps_bypass{1};         // bypassed by default

    // ── Warps Source Routing ────────────────────────────
    static constexpr int kNumWarpsSources = 16;
    // 0=SYNTH, 1=DRUMS, 2=REPL, 3=LFO, 4=RESONATOR, 5=WARPS(feedback), 6=FLUX, 7=BENDER, 8=STRINGS, 9=BASS, 10=TIDES1, 11=TIDES2, 12=TIDES3, 13=TIDES4, 14=PULSAR_DELAY_SEND, 15=PULSAR_REVERB_SEND
    float warps_source_buffers[kNumWarpsSources][kMaxFrames] = {};
    float warps_feedback_l[kMaxFrames] = {};
    float warps_feedback_r[kMaxFrames] = {};
    std::atomic<int> warps_carrier_source{0};     // 0-9 WarpsSource enum
    std::atomic<int> warps_modulator_source{0};   // 0-9 WarpsSource enum

    // ── Bass Voice (standalone, not in voices_dsp[]) ─────────
    OrpheusVoice bass_voice;
    uint8_t bass_voice_alloc_buffer[kVoiceAllocBytes];

    struct BassVoiceParams {
        std::atomic<float> tune{36.0f};       // MIDI note (C2 default)
        std::atomic<int> gate{0};
        std::atomic<float> harmonics{0.0f};   // resonance
        std::atomic<float> timbre{0.5f};      // cutoff
        std::atomic<float> morph{0.5f};
        std::atomic<float> accent{0.8f};
        std::atomic<int> engine_index{0};     // 0=VCF, 1=PD, 10=FM, 21=BassDrum
    };
    // Note: no prev_gate needed — OrpheusVoice::Render() handles trigger edge detection internally
    BassVoiceParams bass_params;

    // Bass sequencer atomics (written from UI, read from audio thread)
    std::atomic<float> bass_mutation{0.0f};      // 0..1
    std::atomic<float> bass_envelope{0.7f};      // 0..1 (snap/decay shape)
    std::atomic<float> bass_overdrive{0.0f};     // 0..1
    std::atomic<float> bass_compressor{0.0f};    // 0..1
    std::atomic<float> bass_mix{0.0f};           // 0..1 (0=bypass)
    std::atomic<float> bass_lfo_mix{0.0f};       // 0..1 LFO mod depth

    // Bass Connect routing (0=off, 1=T1/X1, 2=T2/X2, 3=T3/X3)
    std::atomic<int>   bass_trigger_source{0};    // which Flux T gates the envelope
    std::atomic<int>   bass_pitch_source{0};      // which Flux X modulates pitch
    std::atomic<int>   bass_timbre_source{0};     // 0=off, 1=Y on (boolean, only one Y channel)

    // Bass accent amount (0-1, scales envelope boost + drive boost + cutoff flare)
    std::atomic<float> bass_accent_amount{0.5f};
    // Bass jitter amount (0-1, time jitter + hold/gate-length jitter)
    std::atomic<float> bass_jitter{0.0f};

    // Bass → effects send (0-1): feeds delay, reverb, and grains at same level
    std::atomic<float> bass_fx_send{0.0f};

    float bass_smooth_mix{0.0f};                 // audio thread only
    float bass_smooth_fx_send{0.0f};             // audio thread only
    float bass_smooth_timbre{0.5f};              // audio thread only
    float bass_smooth_harmonics{0.5f};           // audio thread only
    float bass_lpf_state{0.0f};                  // one-pole LPF to catch retrigger clicks
    std::atomic<int>   bass_bypass{1};           // bypass when mix=0
    std::atomic<int>   bass_root_note{36};       // MIDI note (C2)
    std::atomic<int>   bass_scale{1};            // 0=chrom, 1=min_pent, 2=minor, 3=major, 4=dorian, 5=whole
    std::atomic<int>   bass_step_count{16};      // 4, 8, 12, or 16
    std::atomic<int>   bass_clock_div{0};        // 0=1x(quarter), 1=2x(8th), 2=4x(16th), 3=8x(32nd), 4=16x(64th)
    std::atomic<int>   bass_engine{0};           // BassEngine enum

    // Bass keyboard override (set from MIDI/keyboard, cleared on release)
    std::atomic<int>   bass_key_override{0};     // 0=off, 1=active
    std::atomic<float> bass_key_pitch{36.0f};    // override pitch (MIDI note)

    // Bass step sequencer state (audio thread only — too large for UnitState union)
    BassSequencerState bass_seq_state;

    // Bass accent state (audio thread only)
    float bass_accent_drive_boost{0.0f};  // +0.3 when accent active, 0 otherwise
    float bass_accent_timbre_boost{0.0f}; // smoothed cutoff boost for accent flare
    // Anti-click crossfade: last output sample from previous block
    float bass_prev_output{0.0f};

    // Bass output buffer (written by bass voice unit, read by overdrive)
    float bass_output_buffer[kMaxFrames] = {};
    // Bass voice level monitor
    std::atomic<float> bass_voice_level{0.0f};

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
        // Self-feedback (previous sample output for FM-like feedback)
        float prev_output = 0.0f;
    };
    VoiceOscState voice_osc_state[kNumVoices];

    // Per-voice hold level (0.0-1.0, raw from UI before scaling)
    std::atomic<float> voice_hold_level[kNumVoices] = {};

    // ── Voice Coupling ─────────────────────────────────
    float voice_envelope[kNumVoices] = {};             // peak follower per voice
    std::atomic<float> coupling_depth{0.0f};           // 0 = off, scales partner env → pitch
    std::atomic<float> total_feedback{0.0f};           // master output → LFO feedback (0-1)

    // ── Mod Source Routing + FM ─────────────────────────
    static constexpr int kNumDuos = 6;
    // Previous block's full output per voice (for audio-rate VOICE_FM cross-mod).
    // Stores post-VCA, post-gain signal (includes envelope shaping and kEngine0OutGain
    // or kOrpheusOutGain), matching JSyn where voiceA.output connects post-VCA.
    float voice_fm_buffer[kNumVoices][kMaxFrames] = {};
    float marbles_cv_output[2] = {};                   // cached Marbles X1/X2 CV
    std::atomic<int> mod_source[kNumDuos] = {};        // per-duo: 0=VOICE_FM, 1=OFF, 2=LFO, 3=FLUX (Kotlin ModSource ordinals)
    std::atomic<float> mod_depth[kNumDuos] = {};       // per-duo timbre mod depth
    std::atomic<float> fm_depth[kNumDuos] = {};        // per-duo FM depth (0-1, scaled to ±200Hz matching JSyn)
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
    std::atomic<float> marbles_mix{0.0f};            // 0..1 output scaling (self-bypass at 0)
    std::atomic<float> marbles_range_min{0.0f};     // CV output floor (0..1 UI, maps to voltage attenuation)
    std::atomic<float> marbles_range_max{1.0f};     // CV output ceiling (0..1 UI)
    std::atomic<int>   marbles_deja_vu_mode{0};     // 0=T+X, 1=T only, 2=X only
    std::atomic<float> marbles_pulse_width{0.5f};    // 0..1 pulse width mean
    std::atomic<float> marbles_pulse_width_std{0.0f};// 0..1 pulse width randomization
    std::atomic<int>   marbles_clock_source{0};      // 0=global clock, 1=LFO

    // ── Marbles Deinterleaved Output Buffers ─────────
    // Written by unit_process_marbles, read by unit_process_plaits for trigger routing.
    // All 6 streams extracted from interleaved TGenerator/XYGenerator output.
    float marbles_t1_buffer[kMaxFrames] = {};  // T1 gate (TGenerator ch0)
    float marbles_t2_buffer[kMaxFrames] = {};  // T2 gate (master ramp < pulseWidth)
    float marbles_t3_buffer[kMaxFrames] = {};  // T3 gate (TGenerator ch1)
    float marbles_x1_buffer[kMaxFrames] = {};  // X1 CV (post mix+exp)
    float marbles_x2_buffer[kMaxFrames] = {};  // X2 CV (post mix+exp)
    float marbles_x3_buffer[kMaxFrames] = {};  // X3 CV (post mix+exp)
    float marbles_y_buffer[kMaxFrames] = {};   // Y output (smooth random CV)

    // ── Tides2 Poly Slope Generator ("Waves") ───────
    tides::PolySlopeGenerator tides_generator;
    tides::RampExtractor tides_ramp_extractor;

    // Parameter atomics (written from UI, read from audio thread)
    // NOTE: tides_slope maps to the "pw" (pulse width) parameter in Render()
    std::atomic<float> tides_frequency{0.5f};    // 0–1 knob position, mapped to Hz by range
    std::atomic<float> tides_slope{0.5f};        // maps to Render(pw=...) — attack/decay balance
    std::atomic<float> tides_shape{0.5f};
    std::atomic<float> tides_smoothness{0.5f};
    std::atomic<float> tides_shift{0.5f};  // center position (matches MI hardware knob default)
    std::atomic<float> tides_mix{0.0f};          // default off (mix knob pattern)
    std::atomic<float> tides_clock_offset{0.0f};
    std::atomic<int>   tides_ramp_mode{1};       // RAMP_MODE_LOOPING (AD=0, LOOPING=1, AR=2)
    std::atomic<int>   tides_output_mode{0};     // OUTPUT_MODE_GATES
    std::atomic<int>   tides_range{0};           // RANGE_CONTROL
    std::atomic<int>   tides_gate_source{0};     // 0=Voice Gate, 1=T1, 2=T2, 3=T3, 4=Free-run
    std::atomic<int>   tides_clock_source{0};    // 0=Internal, 1=Master Clock

    // Working buffers
    stmlib::GateFlags tides_gate_flags[kMaxFrames] = {};
    stmlib::GateFlags tides_prev_gate_flag{stmlib::GATE_FLAG_LOW};
    stmlib::GateFlags tides_clock_prev_flag{stmlib::GATE_FLAG_LOW};
    tides::PolySlopeGenerator::OutputSample tides_render_buffer[kMaxFrames];

    // Deinterleaved output buffers (4 channels, for mod routing + Warps sources)
    float tides_output_buffer[4][kMaxFrames] = {};

    // Smoothed mix (audio thread only — matches warps_smooth_mix pattern)
    float tides_smooth_mix{0.0f};

    // ── Trigger Router: source selection atomics ─────
    // Written from Kotlin UI, read by unit_process_plaits.
    // Drums: 0=Grids (default graph wiring), 1=T1, 2=T2, 3=T3
    // Quads: 0=Internal/MIDI, 1=T1, 2=T2, 3=T3
    // Pitch: 0=None, 1=X1, 2=X2, 3=X3
    std::atomic<int> drum_trigger_source[kNumDrumVoices] = {};
    std::atomic<int> drum_pitch_source[kNumDrumVoices] = {};
    std::atomic<int> quad_trigger_source[3] = {};  // 3 quads of 4 voices each
    std::atomic<int> quad_pitch_source[3] = {};
    std::atomic<int> quad_trigger_mode[3] = {};   // 0=sustain, 1=trigger (percussive)

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

    // ── Parameter smoothing (prevents clicks on gain/level changes) ──
    // Smoothed shadows for atomic parameters that multiply audio signals.
    // Updated per-sample with one-pole filter (~5ms ramp).
    float smooth_drive_mix{0.0f};
    float smooth_delay_mix{0.0f};
    float smooth_delay_feedback{0.3f};
    float smooth_master_pan{0.0f};
    float smooth_drum_mix{0.7f};
    float smooth_marbles_mix{0.0f};
    float smooth_looper_level{0.0f};
    float smooth_looper_feedback{0.0f};
    float smooth_master_volume{0.7f};
    float master_limiter_env{0.0f};   // peak envelope for master bus limiter
    float smooth_vibrato_depth{0.0f};
    float smooth_coupling_depth{0.0f};
    float smooth_total_feedback{0.0f};
    float smooth_reverb_amount{0.0f};
    float smooth_reverb_time{0.5f};
    float smooth_reverb_damping{0.5f};
    float smooth_reverb_diffusion{0.5f};
    float smooth_mod_depth[kNumDuos] = {};
    float smooth_fm_depth[kNumDuos] = {};

    // ── Vibrato (dedicated sine oscillator → pitch modulation) ──
    std::atomic<float> vibrato_depth{0.0f};    // 0..1 → depth * 20 Hz pitch mod
    std::atomic<float> vibrato_rate{5.0f};     // Hz (JSyn VibratoPlugin default = 5 Hz)
    float vibrato_phase{0.0f};                 // dedicated vibrato oscillator phase [0..1)
    float vibrato_output_buffer[kMaxFrames]{}; // per-sample vibrato in Hz

    // ── HyperLFO (dual oscillator with logic combination) ─
    float lfo_phase_a{0.0f};
    float lfo_phase_b{0.0f};
    float lfo_output_value{0.0f};              // latest combined output for monitoring
    float lfo_output_value_a{0.0f};            // latest oscillator A output (-1..1)
    float lfo_output_value_b{0.0f};            // latest oscillator B output (-1..1)
    float lfo_output_buffer[kMaxFrames]{};     // ch0: per-sample LFO output for timbre modulation
    float lfo_morph_buffer[kMaxFrames]{};      // ch1: morph modulation (PolyLFO only, zero otherwise)
    float lfo_harmonics_buffer[kMaxFrames]{};  // ch2: harmonics modulation (PolyLFO only)
    float lfo_pitch_buffer[kMaxFrames]{};      // ch3: subtle pitch modulation (PolyLFO only)

    std::atomic<float> lfo_freq_a{1.0f};       // Hz
    std::atomic<float> lfo_freq_b{1.0f};       // Hz
    std::atomic<float> lfo_shape{1.0f};        // 0=square, 1=triangle (default: triangle, matches Kotlin)
    std::atomic<int>   lfo_mode{1};            // 0=AND, 1=OFF (independent), 2=OR
    std::atomic<float> lfo_range_min{-1.0f};   // output floor (-1..+1)
    std::atomic<float> lfo_range_max{1.0f};    // output ceiling (-1..+1)
    std::atomic<int>   lfo_source{0};          // 0=DuoLFO, 1=PolyLFO, 2=Lorenz

    // ── PolyLFO (MI Frames, 4-channel morphing LFO) ──
    frames::PolyLfo poly_lfo;
    float poly_lfo_output[4][kMaxFrames] = {};     // 4-channel output buffers (-1..+1)
    float poly_lfo_value[4] = {};                   // last sample per channel (monitoring)

    std::atomic<float> poly_lfo_shape{0.0f};        // 0..1 waveform shape morph
    std::atomic<float> poly_lfo_shape_spread{0.5f}; // 0..1 shape spread across channels (0.5=center/none)
    std::atomic<float> poly_lfo_spread{0.5f};       // 0..1 phase spread (0.5=center/none)
    std::atomic<float> poly_lfo_coupling{0.5f};     // 0..1 inter-channel coupling (0.5=center/none)
    std::atomic<float> poly_lfo_rate{0.5f};         // 0..1 LFO rate
    std::atomic<int>   poly_lfo_bypass{1};          // bypassed by default

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
    std::atomic<float> bend_random_depth{0.1f};        // random LFO modulation depth (matches JSyn default)
    std::atomic<float> bend_timbre_mod{0.3f};
    std::atomic<float> bend_tension_vol{0.015f};

    // Internal bender state (audio thread only)
    float bend_tension_phase{0.0f};
    float bend_tension_env{0.0f};
    int   bend_tension_env_stage{0};          // 0=off, 1=attack, 2=decay, 3=sustain, 4=release
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

    // Pre-allocated scratch unit for Plaits fallback in duo voice rendering.
    // Avoids ~27KB stack allocation + zero-init on every audio block.
    GraphUnit plaits_fallback_unit;

    // Output arrays (read by unit_process_plaits)
    float voice_bend_cv[kNumMainVoices] = {};          // pitch bend Hz offset per voice (matches JSyn benderDepth=100Hz)
    float voice_mix_cv[kNumMainVoices] = {};            // voice volume multiplier per voice (default 1.0)

    // ── TTS Sample Player ────────────────────────────
    // Kotlin loads TTS-generated audio, C++ plays it back with variable rate.
    static constexpr int kMaxTtsSamples = 48000 * 60;  // 60 seconds at 48kHz
    float* tts_buffer{nullptr};                    // heap allocated on first load
    std::atomic<int> tts_buffer_length{0};         // loaded sample count (release/acquire gate)
    std::atomic<int> tts_source_rate{48000};       // source sample rate
    double tts_position{0.0};           // fractional read position (audio thread)
    std::atomic<float> tts_rate{1.0f};  // playback rate multiplier
    std::atomic<float> tts_volume{3.5f}; // playback volume (Kotlin 0.5 * 7)
    std::atomic<int>   tts_playing{0};  // 0=stopped, 1=playing
    std::atomic<int>   tts_trigger{0};  // 1=start from beginning

    // TTS speech effects (phaser → feedback delay → Schroeder reverb)
    std::atomic<float> tts_phaser{0.0f};
    std::atomic<float> tts_feedback{0.0f};
    std::atomic<float> tts_reverb{0.0f};

    // Phaser state (6-stage all-pass)
    static constexpr int kTtsPhaserStages = 6;
    float tts_phaser_buf[kTtsPhaserStages] = {};
    double tts_phaser_lfo_phase{0.0};

    // Feedback delay (~375ms circular buffer — longer for dramatic effect)
    static constexpr int kTtsDelayMaxSamples = 48000 / 2; // 500ms max at 48kHz
    float* tts_delay_buffer{nullptr};  // heap allocated in create()
    int   tts_delay_len{0};            // actual delay length (sample-rate adjusted)
    int   tts_delay_write_pos{0};
    float tts_delay_fb_sample{0.0f};

    // Schroeder reverb (4 comb + 2 all-pass, sample-rate scaled)
    // Reference lengths at 44100Hz — scaled to actual sample rate in create()
    static constexpr float kTtsCombRef[4] = {1116.0f/44100, 1188.0f/44100, 1277.0f/44100, 1356.0f/44100};
    static constexpr float kTtsApRef[2] = {225.0f/44100, 556.0f/44100};
    static constexpr int kTtsCombMaxLen = 3000;  // safe up to 96kHz
    static constexpr int kTtsApMaxLen = 1300;   // safe up to 96kHz
    float tts_comb_bufs[4][kTtsCombMaxLen] = {};
    int   tts_comb_len[4] = {};
    int   tts_comb_pos[4] = {};
    float tts_ap_bufs[2][kTtsApMaxLen] = {};
    int   tts_ap_len[2] = {};
    int   tts_ap_pos[2] = {};
    float tts_reverb_lp{0.0f};

    // ── Automation Player ────────────────────────────
    // Sample-accurate automation: Kotlin sends time/value paths via JNI,
    // audio thread steps through them per-block.
    int64_t sample_counter{0};  // monotonic, incremented by num_frames each process()
    AutomationSlot automation_slots[kMaxAutomationSlots];

    // ── DJ Turntable ──
    TurntableDeck turntable_decks[2];
    std::atomic<float> turntable_wet_a{0.0f};    // per-deck dry/wet (0=dry source, 1=turntable)
    std::atomic<float> turntable_wet_b{0.0f};
    std::atomic<float> turntable_velocity_a{1.0f};
    std::atomic<float> turntable_velocity_b{1.0f};
    std::atomic<int>   turntable_frozen_a{0};
    std::atomic<int>   turntable_frozen_b{0};
    std::atomic<int>   turntable_source_a{0};   // TurntableSource enum
    std::atomic<int>   turntable_source_b{1};   // default drums
    std::atomic<float> turntable_crossfader{0.5f};
    std::atomic<float> turntable_delay_send{0.0f};
    std::atomic<float> turntable_reverb_send{0.0f};
    std::atomic<int>   turntable_drop_a{DROP_NONE};
    std::atomic<int>   turntable_drop_b{DROP_NONE};
    std::atomic<float> beat_phase{0.0f};  // 0..1 from Pulsar or free-run fallback
    float turntable_smooth_wet_a = 0.0f;
    float turntable_smooth_wet_b = 0.0f;

    // Per-source duck gains — computed at start of graph_process from turntable
    // wet levels. Applied in compressor (bass), limiter (drums), PSB (synth).
    float turntable_duck_synth = 1.0f;
    float turntable_duck_drums = 1.0f;
    float turntable_duck_bass  = 1.0f;

    // ── Global mute (play/pause) ──
    std::atomic<int> global_muted{0};       // 0 = unmuted; DJ app sets 1 on init via AppPlugin
    float smooth_global_mute{1.0f};         // smoothing state (0=silent, 1=audible)

    // Bass double-buffer read (new, for turntable + future use)
    float warps_bass_read[kMaxFrames] = {};

    // Previous frame's master output for turntable "master" source
    float turntable_prev_master[kMaxFrames] = {};

    // ── Warps double-buffer (at end to avoid shifting field offsets) ──
    // Warps reads previous frame's completed SYNTH/REPL data while
    // voices fill the current frame's buffers.
    float warps_synth_read[kMaxFrames] = {};
    float warps_drums_read[kMaxFrames] = {};
    float warps_repl_read[kMaxFrames] = {};

    // ── Lorenz Attractor (chaotic modulation source) ──
    streams::LorenzGenerator lorenz_generator;
    std::atomic<float> lorenz_rate{0.5f};      // 0..1, speed of attractor evolution
    std::atomic<float> lorenz_balance{0.5f};   // 0..1, slew amount (0=raw, 1=max smooth)
    std::atomic<int>   lorenz_bypass{1};       // bypassed by default
    float lorenz_x_buffer[kMaxFrames] = {};    // normalised X attractor output (0..1)
    float lorenz_z_buffer[kMaxFrames] = {};    // normalised Z attractor output (0..1)
    float lorenz_slew_x = 0.5f;               // one-pole filter state for X
    float lorenz_slew_z = 0.5f;               // one-pole filter state for Z

    // ── Horn (Leslie Speaker) ─────────────────────────────────────
    static constexpr int kHornBufferSize = 2048;   // delay line for chorus (~42ms @ 48kHz)
    static constexpr int kHornMask = kHornBufferSize - 1;
    float horn_delay_l[kHornBufferSize] = {};
    float horn_delay_r[kHornBufferSize] = {};
    int horn_write_pos = 0;
    float horn_slow_phase = 0.0f;    // horn rotor phase 0..1
    float horn_fast_phase = 0.0f;    // woofer rotor phase 0..1
    float smooth_horn_mix = 0.0f;
    float smooth_horn_speed = 0.5f;  // smoothed speed for inertia

    // Horn parameter atomics
    std::atomic<float> horn_speed{0.5f};        // base rotor speed 0..1
    std::atomic<float> horn_ratio{0.5f};        // horn:woofer ratio 0..1
    std::atomic<float> horn_depth{0.5f};        // Doppler delay depth 0..1
    std::atomic<float> horn_mix{0.0f};          // dry/wet 0..1
    std::atomic<int>   horn_brake{0};           // 0=off, 1=braking
    std::atomic<int>   horn_bypass{1};          // 1=bypassed (mix<=0.001)

    // ── Pulsar beat machine ──────────────────────────
    struct PulsarViz {
        bool step_gates[kNumPulsarTracks][kMaxPulsarSteps];
        float step_velocities[kNumPulsarTracks][kMaxPulsarSteps];
        int playheads[kNumPulsarTracks];
        int step_counts[kNumPulsarTracks];
    };

    // Pulsar state (heap-allocated on first process call, freed in destroy)
    ::PulsarState* pulsar_state = nullptr;

    // Pulsar output buffers (stereo, summed into master)
    float pulsar_out_l[kMaxFrames] = {};
    float pulsar_out_r[kMaxFrames] = {};

    // Per-bus output buffers for Pulsar engine-type classification.
    float pulsar_bus_keys_l[kMaxFrames] = {};
    float pulsar_bus_keys_r[kMaxFrames] = {};
    float pulsar_bus_drums_l[kMaxFrames] = {};
    float pulsar_bus_drums_r[kMaxFrames] = {};
    float pulsar_bus_bass_l[kMaxFrames] = {};
    float pulsar_bus_bass_r[kMaxFrames] = {};

    // Per-track effect send accumulation buses
    float pulsar_delay_send_l[kMaxFrames] = {};
    float pulsar_delay_send_r[kMaxFrames] = {};
    float pulsar_reverb_send_l[kMaxFrames] = {};
    float pulsar_reverb_send_r[kMaxFrames] = {};

    // Pulsar visualization (written by audio thread, read by UI)
    PulsarViz pulsar_viz = {};
    std::atomic<int> pulsar_viz_version{0};

    // Pulsar parameters (set by Kotlin via setPort)
    std::atomic<int> pulsar_playing{0};
    std::atomic<int> pulsar_vibe_generation{0};  // incremented by Kotlin on vibe load
    std::atomic<float> pulsar_energy{0.5f};
    std::atomic<float> pulsar_complexity{0.3f};
    std::atomic<float> pulsar_space{0.4f};
    std::atomic<float> pulsar_mood{0.5f};
    std::atomic<float> pulsar_bpm_override{0.0f};  // 0 = follow global tempo
    std::atomic<int> pulsar_root_note{0};      // 0=C, 11=B
    std::atomic<int> pulsar_scale_index{0};    // 0-5 into kPulsarScales
    std::atomic<float> pulsar_mix{0.0f};       // output level 0-1 (Kotlin sets per-app default)
    std::atomic<float> pulsar_perc_mix{0.7f};  // percussion group volume 0-1
    std::atomic<int> pulsar_envelope_mode{0};  // 0=AD, 1=Tides, 2=Blend
    std::atomic<int> pulsar_track_engine_edm[8]{};
    std::atomic<int> pulsar_track_engine_space[8]{};

    // Per-track voice params (set by Kotlin on vibe load)
    std::atomic<float> pulsar_track_volume[8]{};
    std::atomic<float> pulsar_track_pan[8]{};
    std::atomic<float> pulsar_track_harmonics[8]{};
    std::atomic<float> pulsar_track_timbre[8]{};
    std::atomic<float> pulsar_track_morph[8]{};
    std::atomic<int>   pulsar_track_envelope[8]{};
    std::atomic<int>   pulsar_track_role[8]{};  // TrackRole: 0=PERC, 1=MELODIC, 2=CHORDAL
    std::atomic<int>   pulsar_track_bar_strategy[8]{};  // BarStrategy enum (0-4)
    std::atomic<int>   pulsar_track_evo_rhythmic[8]{};
    std::atomic<int>   pulsar_step_count{16};           // 16 or 32

    // Per-track macro maps (set by Kotlin on vibe load)
    // 8 targets × 2 (min/max) = 16 floats per track
    struct PulsarTrackMacroAtomic {
        std::atomic<float> energy_vol_min{0.5f}, energy_vol_max{1.0f};
        std::atomic<float> energy_density_min{0.3f}, energy_density_max{0.8f};
        std::atomic<float> complexity_swing_min{0.0f}, complexity_swing_max{0.2f};
        std::atomic<float> complexity_var_min{0.0f}, complexity_var_max{0.3f};
        std::atomic<float> space_decay_min{0.2f}, space_decay_max{0.5f};
        std::atomic<float> mood_harm_min{0.3f}, mood_harm_max{0.7f};
        std::atomic<float> mood_timbre_min{0.3f}, mood_timbre_max{0.7f};
    };
    PulsarTrackMacroAtomic pulsar_track_macros[8];

    // Genre profile (set by Kotlin on vibe load)
    std::atomic<float> pulsar_genre_density[8]{};
    std::atomic<float> pulsar_genre_swing{0.0f};
    std::atomic<float> pulsar_genre_ghost_prob{0.0f};
    std::atomic<int>   pulsar_genre_note_range_low{36};
    std::atomic<int>   pulsar_genre_note_range_high{72};
    std::atomic<float> pulsar_genre_rhythm_density{0.0f};
    std::atomic<int>   pulsar_genre_progression_style{0};
    std::atomic<int>   pulsar_genre_chords_per_bar{2};
    std::atomic<int>   pulsar_chord_matrix_active{0};   // 1 = use custom matrix
    std::atomic<float> pulsar_chord_matrix[49]{};        // 7x7 row-major
    std::atomic<int>   pulsar_progression_anchor{0};
    std::atomic<float> pulsar_progression_drift_range{0.5f};

    // Custom chord progression (overrides template.progression when active).
    // Matrix selection still comes from genre_progression_style unless
    // pulsar_chord_matrix_active is also set. Max 8 slots (kMaxProgressionLength).
    std::atomic<int>   pulsar_custom_progression_active{0};
    std::atomic<int>   pulsar_custom_progression_length{0};
    std::atomic<int>   pulsar_custom_progression[8]{};   // chord degrees 0-6

    // Lick transfer buffer (written by Kotlin before setting lick_length)
    static constexpr int kMaxLickSteps = 32;
    struct LickStepAtomic {
        int8_t scale_degree;
        float duration;
        float velocity;
    };
    std::atomic<int> pulsar_lick_length{0};        // 0 = no lick (pure generative)
    std::atomic<int> pulsar_lick_loop_length{0};   // beats; 0 = no rest padding
    LickStepAtomic pulsar_lick[kMaxLickSteps];     // not atomic; guarded by length write order
    std::atomic<float> pulsar_lick_mutation{0.5f};
    std::atomic<int> pulsar_lick_octave{-1};       // -1 = auto (midpoint of noteRange)
    std::atomic<int64_t> pulsar_seed{0};           // 0 = random seed each load

    // ── Pulsar Tension ──
    std::atomic<int>   pulsar_tension_inner_bars{4};
    std::atomic<int>   pulsar_tension_outer_bars{0};
    std::atomic<float> pulsar_tension_outer_depth{0.5f};
    std::atomic<float> pulsar_tension_volume{0.3f};
    std::atomic<float> pulsar_tension_timing{0.2f};
    std::atomic<int>   pulsar_tension_octave_shift{0};
    std::atomic<int>   pulsar_tension_key_shift{0};
    std::atomic<int>   pulsar_tension_half_lick{0};
    std::atomic<float> pulsar_tension_chromatic_passing{0.0f};
    std::atomic<float> pulsar_tension_evo_timbre_low{0.25f};
    std::atomic<float> pulsar_tension_evo_timbre_high{0.55f};
    std::atomic<float> pulsar_tension_evo_timbre_prob{0.7f};
    std::atomic<float> pulsar_tension_evo_morph_low{-1.0f};
    std::atomic<float> pulsar_tension_evo_morph_high{-1.0f};
    std::atomic<float> pulsar_tension_evo_morph_prob{0.5f};
    std::atomic<float> pulsar_tension_evo_harm_low{-1.0f};
    std::atomic<float> pulsar_tension_evo_harm_high{-1.0f};
    std::atomic<float> pulsar_tension_evo_harm_prob{0.3f};
    std::atomic<float> pulsar_tension_evo_attack_point{0.5f};
    std::atomic<float> pulsar_tension_evo_release_speed{0.3f};
    std::atomic<float> pulsar_tension_spurt_chance{0.0f};
    std::atomic<float> pulsar_track_evo_weight[8] = {
        {-1.0f}, {-1.0f}, {-1.0f}, {-1.0f}, {-1.0f}, {-1.0f}, {-1.0f}, {-1.0f}
    };

    // ── Arrangement / Section system ──
    std::atomic<int>   pulsar_arrangement_active{0};
    std::atomic<int>   pulsar_arrangement_section_count{0};
    std::atomic<int>   pulsar_arrangement_intro_index{-1};
    std::atomic<int>   pulsar_arrangement_outro_index{-1};
    std::atomic<float> pulsar_section_data[8 * 21] = {};
    std::atomic<float> pulsar_section_transitions[8 * 8 * 2] = {};
    std::atomic<float> pulsar_track_solo_behavior[8 * 15] = {};
    std::atomic<float> pulsar_track_ducking[8 * 6] = {};
    std::atomic<float> pulsar_track_solo_markov[8 * 15] = {};
    std::atomic<int>   pulsar_soloist_matrix_active{0};
    std::atomic<float> pulsar_soloist_matrix[64] = {};  // 8x8 row-major
    std::atomic<int>   pulsar_arrangement_generation{0};

    // ── Band solo system ──
    std::atomic<int>   pulsar_band_active{0};
    std::atomic<int>   pulsar_band_member_count{0};
    std::atomic<float> pulsar_band_member_data[8 * 12] = {};  // 8 members × 12 floats each
    std::atomic<float> pulsar_band_handoff_matrix[64] = {};    // 8x8 max
    std::atomic<float> pulsar_band_pull_in_matrix[64] = {};    // 8x8 max
    std::atomic<int>   pulsar_band_pull_in_bars_min{2};
    std::atomic<int>   pulsar_band_pull_in_bars_max{4};
    std::atomic<float> pulsar_band_improv_carryover{0.7f};
    std::atomic<float> pulsar_band_probability{0.7f};
    std::atomic<int>   pulsar_band_bars_per_lead_min{2};
    std::atomic<int>   pulsar_band_bars_per_lead_max{4};

    // Per-track hold parameters (TEXTURE/FX tracks 5-7)
    std::atomic<float> pulsar_track_hold_probability[8] = {};
    std::atomic<int>   pulsar_track_hold_length_min[8] = {};
    std::atomic<int>   pulsar_track_hold_length_max[8] = {};

    // Per-track mod LFO parameters (TEXTURE/FX modulation)
    std::atomic<float> pulsar_track_mod_lfo_rate[8] = {};      // Hz (0.05-2.0)
    std::atomic<float> pulsar_track_mod_lfo_depth[8] = {};     // 0.0-1.0
    std::atomic<float> pulsar_track_mod_lfo_shape[8] = {};     // 0.0-1.0 (PolyLfo shape morph)
    std::atomic<float> pulsar_track_mod_lfo_coupling[8] = {};  // 0.0-1.0

    // Track mute (0=enabled, 1=muted) — UI toggle, not persisted
    std::atomic<int>   pulsar_track_mute[8] = {};

    // Per-track delay/reverb sends
    std::atomic<float> pulsar_track_delay_send[8] = {};
    std::atomic<float> pulsar_track_reverb_send[8] = {};

    // Per-track note range overrides (0 = use genre default)
    std::atomic<int>   pulsar_track_note_range_low[8] = {};
    std::atomic<int>   pulsar_track_note_range_high[8] = {};
    // Per-track reverb send brightness (0-1, default 0.5)
    std::atomic<float> pulsar_track_reverb_brightness[8] = {};
    // Per-track density override (-1 = use genre, 0-1 = override)
    std::atomic<float> pulsar_track_density_override[8] = {};
    // Per-track delay feedback (-1 = use global, 0-0.95 = override)
    std::atomic<float> pulsar_track_delay_feedback[8] = {};
    // Per-track glide rate (0 = instant, 1 = very slow ~2s)
    std::atomic<float> pulsar_track_glide_rate[8] = {};
    // Per-track lick usage
    std::atomic<int>   pulsar_track_lick_mode[8] = {};  // LickMode: 0=NONE, 1=SQUASH, 2=FILL
    std::atomic<int>   pulsar_track_comping_style[8] = {};  // CompingStyleId: 0=PAD, 1=FUNK, 2=ROCK, 3=CUSTOM
    std::atomic<int>   pulsar_track_arp_mode[8] = {};       // ArpModeId: 0=AUTO, 1=ALWAYS, 2=NEVER
    std::atomic<float> pulsar_track_arp_speed[8] = {};      // 0.0-1.0
    std::atomic<int>   pulsar_track_arp_direction[8] = {};  // ArpDirectionId: 0=UP, 1=DOWN, 2=UP_DOWN, 3=RANDOM
    std::atomic<int>   pulsar_track_inversion[8] = {};      // SectionInversionId: 0=FOLLOW, 1=ROOT, 2=FIRST, 3=SECOND, 4=OPEN
    // Per-track humanization probabilities (CHORDAL only)
    std::atomic<float> pulsar_track_human_drop_prob[8] = {};
    std::atomic<float> pulsar_track_human_ghost_prob[8] = {};
    std::atomic<float> pulsar_track_human_octave_prob[8] = {};
    std::atomic<float> pulsar_track_human_ext_prob[8] = {};
    // Per-track fill parameters (CHORDAL only)
    std::atomic<int>   pulsar_track_fill_every_n[8] = {};
    std::atomic<int>   pulsar_track_fill_type[8] = {};
    std::atomic<float> pulsar_track_fill_skip_prob[8] = {};

    // Per-track chord follow mode (0=FOLLOW, 1=ROOT_ONLY, 2=FIXED)
    std::atomic<int> pulsar_track_chord_follow[8] = {};

    // Per-track evolution parameters
    // Note: tension_resp and voicing_tension zero-init here; Kotlin defaults are 1.0f.
    // Safe because load_vibe() always pushes values before they're read in mutate_patterns().
    std::atomic<float> pulsar_track_evo_tension_resp[8] = {};
    std::atomic<int>   pulsar_track_evo_note_follow[8] = {};
    std::atomic<int>   pulsar_track_evo_pitch_mode[8] = {};
    std::atomic<float> pulsar_track_evo_voicing_tension[8] = {};

    // ── Pulsar Dedicated Delay ────────────────────────
    // Separate delay instance for Pulsar sends, independent from the shared voice delay.
    static constexpr int kPulsarMaxDelaySamples = 96000 * 2;  // 2s at 96kHz
    float pulsar_delay_buf_1l[kPulsarMaxDelaySamples] = {};
    float pulsar_delay_buf_1r[kPulsarMaxDelaySamples] = {};
    float pulsar_delay_buf_2l[kPulsarMaxDelaySamples] = {};
    float pulsar_delay_buf_2r[kPulsarMaxDelaySamples] = {};
    int   pulsar_delay_write_pos{0};

    std::atomic<float> pulsar_delay_time_a{0.3f};
    std::atomic<float> pulsar_delay_time_b{0.35f};
    std::atomic<float> pulsar_delay_feedback{0.4f};
    std::atomic<float> pulsar_delay_damping{0.5f};
    std::atomic<int>   pulsar_delay_bypass{0};  // active by default (DEEP controls wet)

    float pulsar_delay_time_a_smooth{0.0f};
    float pulsar_delay_time_b_smooth{0.0f};
    float pulsar_smooth_delay_feedback{0.4f};

    // ── Pulsar Dedicated Reverb ──────────────────────
    // Separate reverb for Pulsar sends, independent from the shared voice reverb.
    // Dattorro plate — same topology as shared reverb, separate state.
    static constexpr int kPulsarReverbBufSize = 16384;  // must cover 96kHz (del2_len=12624)
    float pulsar_rv_ap1[kPulsarReverbBufSize] = {};
    float pulsar_rv_ap2[kPulsarReverbBufSize] = {};
    float pulsar_rv_ap3[kPulsarReverbBufSize] = {};
    float pulsar_rv_ap4[kPulsarReverbBufSize] = {};
    float pulsar_rv_dly1[kPulsarReverbBufSize] = {};
    float pulsar_rv_dly2[kPulsarReverbBufSize] = {};
    float pulsar_rv_dly3[kPulsarReverbBufSize] = {};
    float pulsar_rv_dly4[kPulsarReverbBufSize] = {};
    float pulsar_rv_ap5[kPulsarReverbBufSize] = {};
    float pulsar_rv_ap6[kPulsarReverbBufSize] = {};
    int   pulsar_rv_write_pos{0};

    float pulsar_rv_lfo_phase{0.0f};
    float pulsar_rv_lfo2_phase{0.37f};
    float pulsar_rv_lfo_value{0.0f};
    float pulsar_rv_lfo2_value{0.0f};
    float pulsar_rv_lp_decay1{0.0f};
    float pulsar_rv_lp_decay2{0.0f};

    std::atomic<float> pulsar_reverb_amount{0.6f};
    std::atomic<float> pulsar_reverb_time{0.6f};
    std::atomic<float> pulsar_reverb_damping{0.5f};
    std::atomic<float> pulsar_reverb_diffusion{0.625f};
    std::atomic<int>   pulsar_reverb_bypass{0};  // active by default

    float smooth_pulsar_reverb_amount{0.0f};
    float smooth_pulsar_reverb_time{0.6f};
    float smooth_pulsar_reverb_damping{0.5f};
    float smooth_pulsar_reverb_diffusion{0.625f};

    // ── Signal visualization ring buffers ──
    // Written by audio thread (one sample per block), read by UI at ~60fps.
    VizRing viz_rings[VIZ_CHANNEL_COUNT];
};
