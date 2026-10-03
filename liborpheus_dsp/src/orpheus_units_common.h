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

// Voice-coupling peak follower (150 ms half-life, JSyn PeakFollower) stepped once per
// block on the block peak: the per-sample decay compounds to d^N over the block.
inline float coupling_follower_block(float env, float block_peak, float sample_rate, int num_frames) {
    const float dN = std::pow(1.0f - 0.693f / (sample_rate * 0.15f), static_cast<float>(num_frames));
    return (block_peak > env) ? block_peak + (env - block_peak) * dN : env * dN;
}

// Response time of the duo coupling, FM depth and mod depth knobs.
constexpr float kDuoDepthSmoothSeconds = 0.020f;
