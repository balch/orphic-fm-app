#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * Dip-and-return gain envelope on the master bus. When armed, gain
 * starts at 1.0, dips to `depth` at the midpoint of the armed duration,
 * then returns to 1.0 at the end.
 *
 * In-place when armed; true passthrough when disarmed.
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterCrossfade {
public:
    MasterCrossfade() = default;

    void arm(int samples, float sample_rate, float depth) {
        if (samples <= 0) return;
        sample_rate_ = sample_rate;
        depth_ = depth;
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float sample_rate_ = 48000.0f;
    float depth_ = 0.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};
};

} // namespace orpheus
