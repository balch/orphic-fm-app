#pragma once

#include <cstdint>

// Per-voice state for unit_process_braids' 96 kHz → host resampler. Zero it when the
// oscillator is re-Init'd.
struct BraidsResampler {
    static constexpr int kCarry = 2;
    // Source position of the next host block's first output, relative to the first 96 kHz
    // sample that block renders. In [-kCarry, 2): a negative position lands in `carry`.
    float src_phase = 0.0f;
    // The last 96 kHz samples rendered, oldest first. Braids renders in pairs, so a block
    // can render up to one sample past what it reads and the next block starts behind.
    int16_t carry[kCarry] = {};
};
