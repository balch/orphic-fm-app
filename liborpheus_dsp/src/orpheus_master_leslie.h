#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * Rotary speaker (Leslie) AM modulation on the master bus. When armed,
 * rotation speed follows a symmetric 0→peak→0 curve: spins up over the
 * first half, brakes over the second. AM depth tracks speed so the
 * effect fades cleanly to unity at both ends (true bypass when disarmed).
 *
 * Used by the DJ transition in conjunction with the LPF sweep — both
 * share the same symmetric v-curve so they peak together at the midpoint.
 *
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterLeslie {
public:
    MasterLeslie() = default;

    void arm(int samples, float sample_rate) {
        if (samples <= 0) return;
        sample_rate_ = sample_rate;
        phase_ = 0.0f;
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float phase_ = 0.0f;
    float sample_rate_ = 48000.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};

    // Peak rotation speed (Hz). 7.5 Hz ≈ 450 RPM — classic Leslie "fast" setting.
    static constexpr float kMaxHz = 7.5f;
    // AM depth at peak speed (0.7 = gain swings between 0.3 and 1.0).
    static constexpr float kDepth = 0.7f;
};

} // namespace orpheus
