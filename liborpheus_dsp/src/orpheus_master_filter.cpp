#include "orpheus_master_filter.h"

#include <cmath>

namespace orpheus {

static constexpr float kPi = 3.14159265358979f;
static constexpr float kTwoPi = 2.0f * kPi;

void MasterFilter::process(float* buf, size_t n) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;

    int total = samples_total_.load(std::memory_order_relaxed);
    const float pi_over_sr = kPi / sample_rate_;
    const float lfo_inc = 1.0f / (float)total;

    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;

        float t = 1.0f - (float)left / (float)total;
        // Envelope: ramps up to 1.0 at midpoint, back to 0 at end
        float v = 1.0f - std::fabs(2.0f * t - 1.0f);

        // LFO → allpass cutoff sweep (one full cycle per armed duration)
        lfo_phase_ += lfo_inc;
        if (lfo_phase_ >= 1.0f) lfo_phase_ -= 1.0f;
        float lfo = 0.5f * (1.0f + std::sin(kTwoPi * lfo_phase_));
        float cutoff = kMinHz * std::pow(kMaxHz / kMinHz, lfo);
        float tc = std::tan(pi_over_sr * cutoff);
        float a = (tc - 1.0f) / (tc + 1.0f);
        if (a > 0.99f) a = 0.99f;
        if (a < -0.99f) a = -0.99f;

        // 4-stage allpass cascade with feedback
        float x = buf[i] + feedback_ * kFeedback;
        float y = x;
        for (int s = 0; s < kStages; ++s) {
            float w_new = y - a * stage_[s];
            float out = a * w_new + stage_[s];
            stage_[s] = w_new;
            y = out;
        }
        if (std::fabs(y) < 1e-20f) y = 0.0f;
        feedback_ = y;

        // Envelope-scaled wet/dry mix
        float wet = v * kWetMix;
        buf[i] = buf[i] * (1.0f - wet) + y * wet;
        --left;
    }

    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
