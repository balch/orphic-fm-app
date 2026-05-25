#include "orpheus_master_fader.h"

#include <algorithm>
#include <cmath>

namespace orpheus {

namespace {

inline float apply_curve(FadeCurve c, float t) {
    switch (c) {
        case FadeCurve::LINEAR:   return t;
        case FadeCurve::EASE_IN:  return t * t;
        case FadeCurve::EASE_OUT: { float u = 1.0f - t; return 1.0f - u * u; }
        case FadeCurve::LOG: {
            constexpr float floor_v = 1e-3f;
            float amp = std::pow(10.0f, -60.0f * t / 20.0f);
            float norm_amp = (amp - floor_v) / (1.0f - floor_v);
            if (norm_amp < 0.0f) norm_amp = 0.0f;
            if (norm_amp > 1.0f) norm_amp = 1.0f;
            return 1.0f - norm_amp;
        }
    }
    return t;
}

} // namespace

void MasterFader::process(float* buf, size_t n) {
    int left = samples_left_.load(std::memory_order_acquire);
    float cur = current_.load(std::memory_order_relaxed);

    if (left <= 0) {
        if (cur == 1.0f) return; // unity gain
        for (size_t i = 0; i < n; ++i) buf[i] *= cur;
        return;
    }

    float tgt = target_.load(std::memory_order_relaxed);
    int total = samples_total_.load(std::memory_order_relaxed);
    auto crv = static_cast<FadeCurve>(curve_.load(std::memory_order_relaxed));
    float start = cur;

    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) {
            buf[i] *= cur;
            continue;
        }
        float t = (float)(total - left + 1) / (float)total;
        float shaped = apply_curve(crv, t);
        cur = start + (tgt - start) * shaped;
        buf[i] *= cur;
        --left;
        if (left == 0) {
            cur = tgt;
            start = tgt;
        }
    }

    current_.store(cur, std::memory_order_relaxed);
    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
