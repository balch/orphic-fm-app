#pragma once
#include <cstdint>

static constexpr int kMaxBassSteps = 16;

// ── Scale interval tables (semitones from root) ─────────────────────

namespace bass_scale_data {

inline constexpr int8_t chromatic[] = {0,1,2,3,4,5,6,7,8,9,10,11};
inline constexpr int8_t minor_pentatonic[] = {0,3,5,7,10};
inline constexpr int8_t minor[] = {0,2,3,5,7,8,10};
inline constexpr int8_t major[] = {0,2,4,5,7,9,11};
inline constexpr int8_t dorian[] = {0,2,3,5,7,9,10};
inline constexpr int8_t whole_tone[] = {0,2,4,6,8,10};

}  // namespace bass_scale_data

struct BassScale {
    const int8_t* intervals;
    int count;
};

inline constexpr int kBassScaleCount = 6;

inline const BassScale bass_scales[kBassScaleCount] = {
    { bass_scale_data::chromatic,        12 },
    { bass_scale_data::minor_pentatonic,  5 },
    { bass_scale_data::minor,             7 },
    { bass_scale_data::major,             7 },
    { bass_scale_data::dorian,            7 },
    { bass_scale_data::whole_tone,        6 },
};

// ── LCG PRNG helpers (same constants as grids_rng) ──────────────────

inline uint32_t bass_rng_next(uint32_t state) {
    return state * 1664525u + 1013904223u;
}

// Returns float in [0, 1)
inline float bass_rng_float(uint32_t state) {
    return static_cast<float>(state >> 8) / 16777216.0f;  // 2^24
}

// ── Sequencer state ─────────────────────────────────────────────────

struct BassSequencerState {
    float mutation_buffer[kMaxBassSteps];   // random pitch values [0,1)
    float gate_buffer[kMaxBassSteps];       // random gate values [0,1)
    float accent_buffer[kMaxBassSteps];     // random accent values [0,1)
    int   current_step;
    int   tick_counter;
    bool  initialized;
    uint32_t rng_state;
    float env_level;
    int   env_stage;       // 0=idle, 1=attack, 2=decay
    bool  env_gate_prev;
    float smooth_note;     // portamento-smoothed pitch (MIDI note)
    // Jitter state: per-step randomized timing offset and gate hold duration
    int   jitter_offset;          // sample offset for current step (±samples)
    int   jitter_hold_samples;    // how many samples the gate stays on this step (0 = full step)
    int   jitter_hold_counter;    // counts down from jitter_hold_samples
};
