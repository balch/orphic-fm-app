#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

enum class FadeCurve : int {
    LINEAR = 0,
    EASE_IN = 1,
    EASE_OUT = 2,
    LOG = 3,
};

/**
 * Per-sample interpolating gain. When idle (samples_left_ == 0), hot path
 * is `out *= current_` per sample. When armed, lerps current_ toward target_
 * across samples_total_ samples, shaped by the curve.
 *
 * Thread-safe: arm() is called from the JNI/control thread, process() from
 * the audio thread. samples_left_ acts as the release/acquire gate — stores
 * to target_/samples_total_/curve_ happen-before the release store to
 * samples_left_ in arm(), and are visible after the acquire load in process().
 */
class MasterFader {
public:
    MasterFader() = default;

    void reset(float value) {
        current_.store(value, std::memory_order_relaxed);
        target_.store(value, std::memory_order_relaxed);
        samples_total_.store(0, std::memory_order_relaxed);
        curve_.store(static_cast<int>(FadeCurve::LINEAR), std::memory_order_relaxed);
        samples_left_.store(0, std::memory_order_release);
    }

    void arm(float target, int samples, FadeCurve curve) {
        if (samples <= 0) {
            current_.store(target, std::memory_order_relaxed);
            target_.store(target, std::memory_order_relaxed);
            samples_left_.store(0, std::memory_order_release);
            return;
        }
        target_.store(target, std::memory_order_relaxed);
        samples_total_.store(samples, std::memory_order_relaxed);
        curve_.store(static_cast<int>(curve), std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n);

    float current() const { return current_.load(std::memory_order_relaxed); }
    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    std::atomic<float> current_{1.0f};
    std::atomic<float> target_{1.0f};
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};
    std::atomic<int> curve_{0};
};

} // namespace orpheus
