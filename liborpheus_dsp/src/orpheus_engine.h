#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"

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
        std::atomic<int> engine_index{0};
    };
    VoiceParams voice_params[kNumVoices];

    // Master controls
    std::atomic<float> master_volume{0.8f};

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
};
