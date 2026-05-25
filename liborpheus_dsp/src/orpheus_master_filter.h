#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * 4-stage allpass filter sweep on the master bus. When armed, sweeps a
 * first-order allpass cascade (200–2000 Hz at 0.4 Hz LFO) over the
 * armed duration. The wet/dry envelope peaks at the midpoint — effect
 * starts dry, peaks mid-transition, returns to dry.
 *
 * In-place when armed; true passthrough when disarmed.
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterFilter {
public:
    MasterFilter() = default;

    void arm(int samples, float sample_rate, int /*comb_offset*/ = 0) {
        if (samples <= 0) return;
        sample_rate_ = sample_rate;
        for (int s = 0; s < kStages; ++s) stage_[s] = 0.0f;
        lfo_phase_ = 0.0f;
        feedback_ = 0.0f;
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float sample_rate_ = 48000.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};

    static constexpr int kStages = 4;
    float stage_[kStages] = {};
    float lfo_phase_ = 0.0f;
    float feedback_ = 0.0f;

    static constexpr float kMinHz = 200.0f;
    static constexpr float kMaxHz = 2000.0f;
    static constexpr float kFeedback = 0.7f;
    static constexpr float kWetMix = 0.85f;
};

} // namespace orpheus
