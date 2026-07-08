#include "orpheus_spectrum.h"
#include <cmath>
#include <algorithm>

namespace {
constexpr float kPi = 3.14159265358979f;
}

void SpectrumAnalyzer::Init(float sample_rate) {
    sample_rate_ = sample_rate;
    fft_.Init();
    for (int i = 0; i < kFftSize; ++i) {
        window_[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * i / (kFftSize - 1)));  // Hann
    }
    initialized_ = true;
}

void SpectrumAnalyzer::Analyze(const SampleRing& ring, float* bands, int n) {
    if (!initialized_ || !bands || n <= 0) return;

    // Copy the newest kFftSize samples and apply the Hann window.
    uint32_t wc = ring.write_count.load(std::memory_order_acquire);
    uint32_t start = wc - static_cast<uint32_t>(kFftSize);
    for (int i = 0; i < kFftSize; ++i) {
        fft_in_[i] = ring.buf[(start + i) % SampleRing::kSize] * window_[i];
    }

    // Forward real FFT. NOTE: Direct() uses fft_in_ as scratch (clobbers it).
    fft_.Direct(fft_in_, fft_out_);

    // ShyFFT (de Soras FFTReal packing): for bin k in [1, N/2-1],
    //   real[k] = fft_out_[k], imag[k] = fft_out_[N/2 + k].
    // (bin 0 = DC at fft_out_[0]; bin N/2 = Nyquist at fft_out_[N/2] — both skipped;
    //  our 30 Hz..16 kHz range lives entirely inside [1, N/2-1].)
    const int   half   = kFftSize / 2;             // 1024
    const float bin_hz = sample_rate_ / kFftSize;  // ~23.4 Hz @ 48 kHz
    const float ratio  = kFMax / kFMin;

    for (int b = 0; b < n; ++b) {
        float f_lo = kFMin * std::pow(ratio, static_cast<float>(b)     / n);
        float f_hi = kFMin * std::pow(ratio, static_cast<float>(b + 1) / n);
        int k_lo = std::max(1, static_cast<int>(std::floor(f_lo / bin_hz)));
        int k_hi = std::min(half - 1, static_cast<int>(std::ceil(f_hi / bin_hz)));
        if (k_hi < k_lo) k_hi = k_lo;  // very low bands cover < 1 bin -> take nearest

        float peak = 0.0f;
        for (int k = k_lo; k <= k_hi; ++k) {
            float re = fft_out_[k];
            float im = fft_out_[half + k];
            float mag = std::sqrt(re * re + im * im);
            if (mag > peak) peak = mag;
        }
        bands[b] = peak * (2.0f / kFftSize);  // normalize to amplitude-ish scale
    }
}

// C API — defined here so all spectrum code lives together. Declared in orpheus_dsp.h.
#include "../include/orpheus_dsp.h"
#include "orpheus_engine.h"
int orpheus_engine_get_spectrum(OrpheusEngine* engine, float* bands, int num_bands) {
    if (!engine || !bands || num_bands <= 0) return 0;
    engine->spectrum_analyzer.Analyze(engine->spectrum_ring, bands, num_bands);
    return num_bands;
}
