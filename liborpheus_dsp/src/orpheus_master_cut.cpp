#include "orpheus_master_cut.h"
namespace orpheus {
void MasterCut::process(float* buf, size_t n, float bpm) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;
    if (bpm < 20.0f) bpm = 120.0f;
    float step_samples = sample_rate_ * 60.0f / bpm / 4.0f;    // 4 steps/beat
    float cycle = gate_rate_steps_ * step_samples;
    if (cycle < 1.0f) cycle = 1.0f;
    const float slew = 1.0f / (0.002f * sample_rate_);         // ~2ms click-avoid
    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;
        float pos = phase_ / cycle;                            // 0..1 in cycle
        float target = (pos < duty_) ? 1.0f : depth_;
        float d = target - gate_;
        if (d >  slew) d =  slew;
        if (d < -slew) d = -slew;
        gate_ += d;
        buf[i] *= gate_;
        phase_ += 1.0f; if (phase_ >= cycle) phase_ -= cycle;
        --left;
    }
    samples_left_.store(left, std::memory_order_relaxed);
}
} // namespace orpheus
