#pragma once
#include <cmath>

// Smoothing coefficient (~5ms at any sample rate)
// Used across multiple unit files: port_prepare, limiter, delay, master_out,
// plaits, dual_delay, lfo, reverb, marbles, looper, duo_voice
inline float smooth_coeff(float sample_rate) {
    return 1.0f - std::exp(-1.0f / (0.005f * sample_rate));
}
