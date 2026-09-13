#pragma once
#include <cmath>

// Per-SAMPLE smoothing coefficient (~5ms at any sample rate). Stepped once per block
// instead, the time constant becomes block_frames x 5ms: use block_smooth_coeff.
inline float smooth_coeff(float sample_rate) {
    return 1.0f - std::exp(-1.0f / (0.005f * sample_rate));
}

// One-pole coefficient for a smoother stepped once per block of num_frames.
inline float block_smooth_coeff(float sample_rate, int num_frames, float tau_seconds) {
    return 1.0f - std::exp(-static_cast<float>(num_frames) / (tau_seconds * sample_rate));
}

// Response time of the duo coupling, FM depth and mod depth knobs.
constexpr float kDuoDepthSmoothSeconds = 0.020f;
