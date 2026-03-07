#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"

// Include MI Plaits voice
#include "plaits/dsp/voice.h"

// Include MI Clouds granular processor
#include "clouds/dsp/granular_processor.h"

#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

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
        int prev_gate{0};  // audio-thread only, for edge detection
    };
    VoiceParams voice_params[kNumVoices];

    // Master controls
    std::atomic<float> master_volume{0.8f};

    // Monitor
    std::atomic<float> peak_left{0.0f};
    std::atomic<float> peak_right{0.0f};
    std::atomic<float> cpu_load{0.0f};
    float voice_levels[kNumVoices] = {};

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
};
