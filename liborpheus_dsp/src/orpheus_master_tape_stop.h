#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * Single-shot tape-stop varispeed on master bus. Bypassed (bit-exact
 * passthrough) when disarmed. When armed, write head advances at 1
 * sample/sample; read head advances at `rate = (1 - t)²` over
 * samples_total_ samples — quadratic decay matching tape physics.
 *
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * samples_left_ is the release/acquire gate (same pattern as MasterFader).
 *
 * Mono. Run once per channel for stereo (same arm state across channels).
 */
class MasterTapeStop {
public:
    static constexpr int kRingSize = 16384; // ~340ms @ 48k — covers tape-stop up to ~500ms

    MasterTapeStop() {
        for (int i = 0; i < kRingSize; ++i) ring_[i] = 0.0f;
    }

    void arm(int samples) {
        if (samples <= 0) return;
        samples_total_.store(samples, std::memory_order_relaxed);
        read_head_ = (float)write_head_;
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float ring_[kRingSize];
    int write_head_ = 0;
    float read_head_ = 0.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};
};

} // namespace orpheus
