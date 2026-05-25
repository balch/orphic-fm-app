#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * DJ-style resonant LPF sweep on the master bus. When armed, sweeps a 2-pole
 * trapezoidal SVF (unconditionally stable) from open (20kHz) down to ~200Hz
 * at the midpoint and back. Drive (tanh saturation) and comb reverb ramp
 * proportionally. High Q (4.0) creates the characteristic resonant sweep.
 *
 * Always keeps at least 15% dry signal so the output never fully drops out.
 *
 * In-place when armed; true passthrough when disarmed.
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterDjSweep {
public:
    MasterDjSweep() = default;

    void arm(int samples, float sample_rate, int comb_offset = 0) {
        if (samples <= 0) return;
        sample_rate_ = sample_rate;
        ic1eq_ = 0.0f;
        ic2eq_ = 0.0f;
        for (int i = 0; i < kComb1Len; ++i) comb1_[i] = 0.0f;
        for (int i = 0; i < kComb2Len; ++i) comb2_[i] = 0.0f;
        comb1_pos_ = 0;
        comb2_pos_ = 0;
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float sample_rate_ = 48000.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};

    // Trapezoidal SVF state (unconditionally stable)
    float ic1eq_ = 0.0f;
    float ic2eq_ = 0.0f;

    static constexpr int kComb1Len = 1559;
    static constexpr int kComb2Len = 1613;
    float comb1_[kComb1Len] = {};
    float comb2_[kComb2Len] = {};
    int comb1_pos_ = 0;
    int comb2_pos_ = 0;

    static constexpr float kOpenHz = 20000.0f;
    static constexpr float kClosedHz = 500.0f;
    static constexpr float kQ = 3.0f;
    static constexpr float kCombFeedback = 0.65f;
    static constexpr float kMinDry = 0.15f;
};

} // namespace orpheus
