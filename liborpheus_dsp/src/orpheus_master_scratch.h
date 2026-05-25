#pragma once

#include <atomic>
#include <cstddef>

namespace orpheus {

/**
 * Beat-synced stutter gate on the master bus. When armed, produces
 * multiple discrete stutter bursts (~4 per second) with beat-synced
 * gate divisions. Each burst chops the audio, then releases to
 * pass-through before the next burst. Division rate increases with
 * each successive burst (1/8 → 1/16 → 1/32).
 *
 * Requires beat_phase (0..1) and bpm from the engine at each process()
 * call for per-sample gate synchronization.
 *
 * In-place when armed; true passthrough when disarmed.
 * Thread-safe: arm() from JNI thread, process() from audio thread.
 * Mono — instantiate once per channel for stereo.
 */
class MasterScratch {
public:
    MasterScratch() = default;

    void arm(int samples, float sample_rate, unsigned int /*seed_offset*/ = 0) {
        if (samples <= 0) return;
        sample_rate_ = sample_rate;
        ramp_ = 0.0f;
        smoothed_gate_ = 1.0f;
        burst_env_ = 0.0f;
        samples_total_.store(samples, std::memory_order_relaxed);
        samples_left_.store(samples, std::memory_order_release);
    }

    void process(float* buf, size_t n, float beat_phase, float bpm);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    float sample_rate_ = 48000.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};

    float ramp_ = 0.0f;
    float smoothed_gate_ = 1.0f;
    float burst_env_ = 0.0f;

    static constexpr float kStuttersPerSecond = 4.0f;
    static constexpr float kBurstDuty = 0.6f;
};

} // namespace orpheus
