#include "orpheus_master_leslie.h"

#include <cmath>

namespace orpheus {

void MasterLeslie::process(float* buf, size_t n) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;

    int total = samples_total_.load(std::memory_order_relaxed);
    constexpr float kTwoPi = 2.0f * 3.14159265f;

    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;

        float t = 1.0f - (float)left / (float)total;
        // Symmetric speed curve: 0→1→0, matching the DJ sweep's v-curve
        float v = 1.0f - std::fabs(2.0f * t - 1.0f);
        // Quadratic shape: punchy spin-up, dramatic brake
        float speed = kMaxHz * v * v;

        phase_ += kTwoPi * speed / sample_rate_;
        if (phase_ >= kTwoPi) phase_ -= kTwoPi;

        // AM: half-cosine maps phase to [0,1] modulation, depth tracks v
        float mod = (1.0f - std::cos(phase_)) * 0.5f;
        float gain = 1.0f - kDepth * v * mod;

        buf[i] *= gain;
        --left;
    }

    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
