#include "orpheus_master_scratch.h"

#include <cmath>

namespace orpheus {

void MasterScratch::process(float* buf, size_t n) {
    int left = samples_left_.load(std::memory_order_acquire);

    if (left <= 0) {
        for (size_t i = 0; i < n; ++i) {
            ring_[write_head_] = buf[i];
            write_head_ = (write_head_ + 1) % kRingSize;
        }
        return;
    }

    int total = samples_total_.load(std::memory_order_relaxed);

    for (size_t i = 0; i < n; ++i) {
        ring_[write_head_] = buf[i];
        write_head_ = (write_head_ + 1) % kRingSize;

        if (left <= 0) continue;

        float t = 1.0f - (float)left / (float)total;  // 0→1 over duration

        // Peak-and-settle curve: cubic attack to kPeakT, then quadratic
        // ease down to kSettleLevel of peak. Gives a sharp rising scratch
        // that resolves before the crossfade into the next song.
        float curve;
        if (t < kPeakT) {
            float a = t / kPeakT;
            curve = a * a * a;
        } else {
            float r = (t - kPeakT) / (1.0f - kPeakT);
            curve = 1.0f - (1.0f - kSettleLevel) * r * r;
        }
        float rate = 1.0f + kMaxRate * curve;

        // Read from ring buffer with linear interpolation.
        // Read head runs AHEAD of write head at increasing speed.
        int idx0 = (int)read_head_;
        float frac_r = read_head_ - (float)idx0;
        int i0 = ((idx0 % kRingSize) + kRingSize) % kRingSize;
        int i1 = (i0 + 1) % kRingSize;
        float scratched = ring_[i0] * (1.0f - frac_r) + ring_[i1] * frac_r;

        // Groove friction: noise proportional to rate deviation from normal.
        // At rate=1 there's no friction; at rate=9 it's maximum grit.
        // Deviation is sqrt-compressed so friction grows sub-linearly with rate,
        // keeping the noise audible but not dominating at high speeds.
        rng_state_ = rng_state_ * 1103515245u + 12345u;
        float noise = (float)((int32_t)rng_state_) * (1.0f / 2147483648.0f);
        float deviation = rate - 1.0f;
        float friction = noise * std::sqrt(deviation) * kFrictionLevel;

        // Wet/dry crossfade: starts fully dry, reaches full scratch at t=1.
        // Use quadratic ramp so the first ~25% is very subtle.
        float wet = t * t;
        float wet_sample = scratched + friction;
        // Soft-limit the wet signal to prevent amplitude overrun
        if (wet_sample > 1.0f) wet_sample = 1.0f - 1.0f / (1.0f + wet_sample);
        else if (wet_sample < -1.0f) wet_sample = -(1.0f - 1.0f / (1.0f - wet_sample));
        buf[i] = buf[i] * (1.0f - wet) + wet_sample * wet;

        read_head_ += rate;
        if (read_head_ >= (float)kRingSize) read_head_ -= (float)kRingSize;

        --left;
    }

    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
