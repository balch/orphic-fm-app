#pragma once

#include <atomic>
#include <cmath>
#include <cstring>

// Layout (see orpheus_engine.cpp's init loop and orpheus_engine_voice.cpp's slot-index
// formula): slots 0..kNumMainVoices-1 = gates, kNumMainVoices..2*kNumMainVoices-1 = freqs,
// plus 8 spare. Derived so a bigger voice bank can't silently overflow this array again --
// it used to be a bare literal (32 = 2*12+8) that stayed put while kNumMainVoices moved.
static constexpr int kMaxAutomationSlots = 2 * kNumMainVoices + 8;
static constexpr int kMaxAutomationPoints = 128;  // max events per path

// Identifies the target of an automation path
enum AutomationTarget : uint8_t {
    AUTO_TARGET_VOICE_GATE = 0,  // value: 0.0 or 1.0
    AUTO_TARGET_VOICE_FREQ = 1,  // value: Hz (converted to MIDI note internally)
};

// A single automation path: list of (time, value) pairs played step-wise
struct AutomationPath {
    float times[kMaxAutomationPoints];   // seconds from path start
    float values[kMaxAutomationPoints];  // raw values
    int count{0};                        // number of points
};

// Per-slot runtime state
struct AutomationSlot {
    // Double-buffer: UI writes to pending, audio thread swaps to active
    AutomationPath paths[2];
    std::atomic<int> pending_path{-1};   // -1 = none, 0 or 1 = which path is pending

    // Audio-thread-only state (active_path is atomic for safe reads from UI thread)
    std::atomic<int> active_path{-1};    // -1 = inactive
    int current_index{0};                // next point to fire
    int64_t start_sample{0};             // sample_counter when path was activated

    // Slot identity (set once at init)
    AutomationTarget target{AUTO_TARGET_VOICE_GATE};
    uint8_t voice_index{0};
    bool allocated{false};
};
