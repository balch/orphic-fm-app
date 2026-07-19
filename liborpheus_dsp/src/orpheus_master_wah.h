#pragma once

#include <atomic>
#include <cstddef>

#include "orpheus_wah_core.h"

namespace orpheus {

/**
 * Tempo-synced bandpass wah on the master bus. Wraps the shared
 * WahVoice/WahParams core (orpheus_wah_core.h) with a trapezoidal wet
 * envelope over the armed duration: 15% ramp-in, sustain, 15% ramp-out.
 *
 * In-place when armed; true passthrough when disarmed.
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterWah {
public:
    MasterWah() = default;

    void arm(int samples, float sample_rate, const WahParams& params) {
        if (samples <= 0) return;
        params_ = params;
        sample_rate_ = sample_rate;
        voice_.Init();
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n, float bpm);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    WahVoice voice_;
    WahParams params_;
    float sample_rate_ = 48000.0f;

    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};
};

} // namespace orpheus
