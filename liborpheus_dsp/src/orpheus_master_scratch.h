#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace orpheus {

/**
 * Needle-scratch effect on the master bus. Simulates a DJ dragging the
 * needle forward across vinyl: the read head accelerates to a peak
 * around 65% of the duration, then eases back to a settled rate before
 * the crossfade into the next song. Cubic attack concentrates the push
 * in the middle; quadratic release softens the tail.
 *
 * Friction noise tracks the rate deviation from normal (1.0) and builds
 * with the scratch intensity. The wet/dry crossfade ramps up so the
 * effect starts subtly and ends at full intensity.
 *
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterScratch {
public:
    static constexpr int kRingSize = 16384;

    MasterScratch() {
        for (int i = 0; i < kRingSize; ++i) ring_[i] = 0.0f;
    }

    void arm(int samples, float sample_rate, unsigned int seed_offset = 0) {
        if (samples <= 0) return;
        sample_rate_ = sample_rate;
        read_head_ = (float)write_head_;
        rng_state_ += seed_offset + 0x9E3779B9u;
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float ring_[kRingSize];
    int write_head_ = 0;
    float read_head_ = 0.0f;
    float sample_rate_ = 48000.0f;
    uint32_t rng_state_ = 0x12345678u;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};

    // Maximum playback rate at peak scratch (rate = 1 + kMaxRate = 7x)
    static constexpr float kMaxRate = 6.0f;
    // Normalized time of peak intensity (0–1); after this, rate settles
    static constexpr float kPeakT = 0.65f;
    // Fraction of peak rate retained at t=1 (0.3 = settles to 30% of max)
    static constexpr float kSettleLevel = 0.3f;
    // Friction (vinyl grit) noise level, scaled by rate deviation
    static constexpr float kFrictionLevel = 0.25f;
};

} // namespace orpheus
