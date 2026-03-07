#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"
#include <atomic>
#include <cstring>
#include <vector>

// Forward-declare MI types we'll use
namespace plaits {
class Voice;
struct Patch;
struct Modulations;
}

struct OrpheusEngine {
    float sample_rate;

    // Interleaved stereo output staging buffer
    std::vector<float> staging_buffer;

    // Monitor data (written by audio thread, read by UI thread)
    std::atomic<float> peak_left{0.0f};
    std::atomic<float> peak_right{0.0f};
    std::atomic<float> cpu_load{0.0f};

    // Master volume
    std::atomic<float> master_volume{0.8f};

    // Voice state
    struct VoiceState {
        std::atomic<float> tune{60.0f};  // MIDI note
        std::atomic<int> gate{0};
    };
    VoiceState voices[12];  // 8 main + 4 REPL

    // Plaits voice instances (allocated in load_patch)
    // Will be added in Task 3
};
