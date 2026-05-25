#include "orpheus_master_tape_stop.h"

#include <cmath>

namespace orpheus {

void MasterTapeStop::process(float* buf, size_t n) {
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

        float t = 1.0f - (float)left / (float)total;
        float rate = (1.0f - t) * (1.0f - t);

        int idx0 = (int)read_head_;
        float frac = read_head_ - (float)idx0;
        int i0 = ((idx0 % kRingSize) + kRingSize) % kRingSize;
        int i1 = (i0 + 1) % kRingSize;
        buf[i] = ring_[i0] * (1.0f - frac) + ring_[i1] * frac;

        read_head_ += rate;
        if (read_head_ >= (float)kRingSize) read_head_ -= (float)kRingSize;
        if (read_head_ < 0.0f) read_head_ += (float)kRingSize;

        --left;
    }

    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
