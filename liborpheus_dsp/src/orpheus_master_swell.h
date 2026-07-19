#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * Crescendo gain envelope on the master bus — the inverse of
 * MasterCrossfade's dip. When armed, gain starts at `start_level`,
 * rises to `peak_level` at the midpoint of the armed duration, then
 * settles to 1.0 at the end.
 *
 * In-place when armed; true passthrough when disarmed.
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterSwell {
public:
    MasterSwell() = default;

    void arm(int samples, float sample_rate, float start_level, float peak_level) {
        if (samples <= 0) return;
        sample_rate_ = sample_rate;
        start_ = start_level;
        peak_ = peak_level;
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float sample_rate_ = 48000.0f;
    float start_ = 1.0f;
    float peak_ = 1.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};
};

} // namespace orpheus
