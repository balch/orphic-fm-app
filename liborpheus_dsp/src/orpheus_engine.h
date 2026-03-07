#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"

// Include MI Plaits voice
#include "plaits/dsp/voice.h"

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
};
