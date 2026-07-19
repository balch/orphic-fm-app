#include "orpheus_master_crossfade.h"

#include <cmath>

namespace orpheus {

void MasterCrossfade::process(float* buf, size_t n) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;
    int total = samples_total_.load(std::memory_order_relaxed);
    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;
        float t = 1.0f - (float)left / (float)total;      // 0 -> 1
        float dip = 1.0f - std::fabs(2.0f * t - 1.0f);    // 0 at ends, 1 mid
        float gain = 1.0f - (1.0f - depth_) * dip;        // 1 -> depth -> 1
        buf[i] *= gain;
        --left;
    }
    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
