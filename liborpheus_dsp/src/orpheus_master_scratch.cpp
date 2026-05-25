#include "orpheus_master_scratch.h"

#include <cmath>

namespace orpheus {

void MasterScratch::process(float* buf, size_t n, float beat_phase, float bpm) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;

    int total = samples_total_.load(std::memory_order_relaxed);
    float ramp_per_sample = 1.0f / (float)total;
    float phase_per_sample = bpm / (60.0f * sample_rate_);
    float total_seconds = (float)total / sample_rate_;
    float num_bursts = std::round(total_seconds * kStuttersPerSecond);
    if (num_bursts < 1.0f) num_bursts = 1.0f;

    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;

        ramp_ += ramp_per_sample;
        if (ramp_ > 1.0f) ramp_ = 1.0f;

        // Burst envelope: cycles num_bursts times over the duration.
        // First kBurstDuty of each cycle: stutter active. Rest: passthrough.
        float burst_phase = ramp_ * num_bursts;
        burst_phase -= std::floor(burst_phase);
        float target_env = (burst_phase < kBurstDuty) ? 1.0f : 0.0f;
        burst_env_ += 0.3f * (target_env - burst_env_);

        // Division increases with overall ramp (each burst stutters tighter)
        int div_idx = static_cast<int>(ramp_ * 3.0f);
        if (div_idx > 2) div_idx = 2;
        float div_mult = static_cast<float>(8 << div_idx);

        float gated_phase = beat_phase * div_mult;
        gated_phase -= std::floor(gated_phase);
        float target_gate = (gated_phase < 0.5f) ? 1.0f : 0.0f;

        smoothed_gate_ += 0.5f * (target_gate - smoothed_gate_);

        // Mix: when burst_env_ is high, apply the gate; when low, passthrough
        float gain = 1.0f - burst_env_ * (1.0f - smoothed_gate_);
        buf[i] *= gain;

        beat_phase += phase_per_sample;
        if (beat_phase >= 1.0f) beat_phase -= 1.0f;

        --left;
    }

    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
