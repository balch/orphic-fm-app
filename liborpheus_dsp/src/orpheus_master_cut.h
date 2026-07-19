#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * Rhythmic hard gate on the master bus. When armed, gates the master bus
 * to `depth` for the `(1 - duty)` portion of each `gate_rate_steps`-step
 * cycle (synced to bpm, 4 steps/beat), full level (1.0) for the rest, with
 * a short click-avoid slew between levels.
 *
 * In-place when armed; true passthrough when disarmed.
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterCut {
public:
    MasterCut() = default;

    void arm(int samples, float sample_rate, float gate_rate_steps, float duty, float depth) {
        if (samples <= 0) return;
        phase_ = 0.0f;
        gate_ = 1.0f;
        sample_rate_ = sample_rate;
        gate_rate_steps_ = gate_rate_steps;
        duty_ = duty;
        depth_ = depth;
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n, float bpm);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float gate_rate_steps_ = 1.0f;
    float duty_ = 0.5f;
    float depth_ = 0.0f;
    float sample_rate_ = 48000.0f;

    // Run state.
    float phase_ = 0.0f;
    float gate_ = 1.0f;

    std::atomic<int> samples_left_{0};
};

} // namespace orpheus
