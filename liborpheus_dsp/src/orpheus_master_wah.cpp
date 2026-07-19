#include "orpheus_master_wah.h"
namespace orpheus {
void MasterWah::process(float* buf, size_t n, float bpm) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;
    if (bpm < 20.0f) bpm = 120.0f;
    double step_samples = (double)sample_rate_ * 60.0 / bpm / 4.0;  // 4 steps/beat
    int total = samples_total_.load(std::memory_order_relaxed);
    const float ramp = 0.15f;   // trapezoid: 15% in, 15% out
    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;
        float t = 1.0f - (float)left / (float)total;               // 0 -> 1
        float env = (t < ramp) ? t / ramp
                  : (t > 1.0f - ramp) ? (1.0f - t) / ramp : 1.0f;
        float wet = env * params_.wet;
        buf[i] = voice_.process_sample(buf[i], params_, wet, sample_rate_);
        voice_.advance(params_, step_samples);
        --left;
    }
    samples_left_.store(left, std::memory_order_relaxed);
}
} // namespace orpheus
