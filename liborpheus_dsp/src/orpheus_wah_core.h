#pragma once
#include <cmath>
#include "stmlib/dsp/filter.h"
namespace orpheus {
static constexpr int   kWahStepsPerBar   = 16;
static constexpr float kWahMinCutoffHz   = 80.0f;
static constexpr float kWahMaxCutoffFrac = 0.45f;   // * sample_rate

struct WahParams {
    float rate_division = 8.0f;   // note value: 4=quarter, 8=eighth, 16=sixteenth
    float depth         = 1.0f;
    float resonance_q   = 3.0f;   // stmlib::Svf true Q (the wah peak)
    float center_hz     = 800.0f;
    float sweep_octaves = 1.3f;
    float wet           = 1.0f;
};
inline float wah_triangle_bipolar(float phase) {
    float t = phase < 0.5f ? phase * 2.0f : 2.0f - phase * 2.0f;
    return 2.0f * t - 1.0f;
}
inline float wah_cutoff_hz(const WahParams& p, float bipolar, float sample_rate) {
    float hz = p.center_hz * std::exp2(p.depth * p.sweep_octaves * bipolar);
    float hi = kWahMaxCutoffFrac * sample_rate;
    if (hz < kWahMinCutoffHz) hz = kWahMinCutoffHz;
    if (hz > hi) hz = hi;
    return hz;
}
inline double wah_phase_increment(const WahParams& p, double samples_per_step) {
    float period_steps = static_cast<float>(kWahStepsPerBar) / p.rate_division;
    double period_samples = static_cast<double>(period_steps) * samples_per_step;
    // Guard against a degenerate/zero rate_division (or samples_per_step) producing a
    // div-by-zero or sub-sample LFO period below.
    if (period_samples < 1.0) period_samples = 1.0;
    return 1.0 / period_samples;
}
struct WahVoice {
    stmlib::Svf svf;
    double lfo_phase = 0.0;
    void Init() { svf.Init(); lfo_phase = 0.0; }
    void reset_phase(double ph = 0.0) { lfo_phase = ph; }
    inline float process_sample(float in, const WahParams& p, float wet, float sample_rate) {
        float bip = wah_triangle_bipolar(static_cast<float>(lfo_phase));
        float hz  = wah_cutoff_hz(p, bip, sample_rate);
        svf.set_f_q<stmlib::FREQUENCY_FAST>(hz / sample_rate, p.resonance_q);
        float filt = svf.Process<stmlib::FILTER_MODE_BAND_PASS>(in);
        return in + wet * (filt - in);
    }
    inline void advance(const WahParams& p, double samples_per_step) {
        lfo_phase += wah_phase_increment(p, samples_per_step);
        if (lfo_phase >= 1.0) lfo_phase -= std::floor(lfo_phase);
    }
    void process(float* buf, int n, const WahParams& p, double samples_per_step, float sample_rate) {
        for (int i = 0; i < n; i++) { buf[i] = process_sample(buf[i], p, p.wet, sample_rate); advance(p, samples_per_step); }
    }
};
} // namespace orpheus
