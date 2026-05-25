#include "orpheus_master_dj_sweep.h"

#include <cmath>

namespace orpheus {

void MasterDjSweep::process(float* buf, size_t n) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;

    int total = samples_total_.load(std::memory_order_relaxed);
    float k = 1.0f / kQ;

    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;

        float t = 1.0f - (float)left / (float)total;
        float v = 1.0f - std::fabs(2.0f * t - 1.0f);

        float cutoff = kOpenHz * std::pow(kClosedHz / kOpenHz, v);
        float g = std::tan(3.14159265f * cutoff / sample_rate_);

        // Trapezoidal SVF (unconditionally stable at any cutoff/Q)
        float a1 = 1.0f / (1.0f + g * (g + k));
        float a2 = g * a1;
        float a3 = g * a2;

        float v3 = buf[i] - ic2eq_;
        float v1 = a1 * ic1eq_ + a2 * v3;
        float v2 = ic2eq_ + a2 * ic1eq_ + a3 * v3;
        ic1eq_ = 2.0f * v1 - ic1eq_;
        ic2eq_ = 2.0f * v2 - ic2eq_;

        float lp = v2;

        // Drive
        float drive = 1.0f + 3.0f * v;
        float driven = std::tanh(lp * drive);
        if (drive > 1.001f) driven /= std::tanh(drive);

        // Comb reverb
        float c1 = comb1_[comb1_pos_];
        comb1_[comb1_pos_] = driven + c1 * kCombFeedback;
        comb1_pos_ = (comb1_pos_ + 1) % kComb1Len;

        float c2 = comb2_[comb2_pos_];
        comb2_[comb2_pos_] = driven + c2 * kCombFeedback;
        comb2_pos_ = (comb2_pos_ + 1) % kComb2Len;

        float comb_wet = (c1 + c2) * 0.5f;

        float wet = driven + comb_wet * v * 0.4f;
        float dry_amount = kMinDry + (1.0f - kMinDry) * (1.0f - v);
        buf[i] = buf[i] * dry_amount + wet * v;
        --left;
    }

    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
