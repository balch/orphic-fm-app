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

    // Warps modulator
    warps::Modulator warps_modulator;

    // Warps parameter atomics (written from UI, read from audio thread)
    std::atomic<float> warps_algorithm{0.0f};
    std::atomic<float> warps_timbre{0.5f};
    std::atomic<float> warps_level1{0.5f};
    std::atomic<float> warps_level2{0.5f};
    std::atomic<int>   warps_bypass{1};         // bypassed by default

    // Marbles random sequence generator (minimal stub for future integration)
    // Full integration requires clock system and modulation routing (Phase 4+)
    std::atomic<float> marbles_rate{0.5f};
    std::atomic<float> marbles_spread{0.5f};
    std::atomic<float> marbles_bias{0.5f};
    std::atomic<float> marbles_steps{0.5f};
    std::atomic<float> marbles_jitter{0.0f};
    std::atomic<float> marbles_deja_vu{0.0f};
    std::atomic<int>   marbles_bypass{1};       // bypassed by default

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
    std::atomic<int>   delay_bypass{1};         // bypassed by default

    // Smoothed delay times (to prevent zipper noise)
    float delay_time_1_smooth{0.0f};
    float delay_time_2_smooth{0.0f};

    // ── HyperLFO (dual oscillator with logic combination) ─
    float lfo_phase_a{0.0f};
    float lfo_phase_b{0.0f};
    float lfo_output_value{0.0f};              // latest output for monitoring

    std::atomic<float> lfo_freq_a{1.0f};       // Hz
    std::atomic<float> lfo_freq_b{1.0f};       // Hz
    std::atomic<float> lfo_shape{0.5f};        // 0=square, 1=triangle
    std::atomic<int>   lfo_mode{1};            // 0=AND, 1=OFF (independent), 2=OR
};
