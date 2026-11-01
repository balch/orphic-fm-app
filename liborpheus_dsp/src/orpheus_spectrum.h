#pragma once
#include <atomic>
#include <cstdint>
#include "stmlib/fft/shy_fft.h"

// Lock-free per-sample ring for spectrum analysis.
// Audio thread writes EVERY sample via write(); UI/poll thread reads the newest
// window in Analyze(). kSize is 2x the longest FFT window (SpectrogramAnalyzer's)
// so the reader's window never collides with the writer's current slot.
struct SampleRing {
    static constexpr int kSize = 16384;
    float buf[kSize] = {};
    std::atomic<uint32_t> write_count{0};

    inline void write(float s) {
        uint32_t wc = write_count.load(std::memory_order_relaxed);
        buf[wc % kSize] = s;
        write_count.store(wc + 1, std::memory_order_release);
    }
};

// Real-FFT spectrum analyzer. Init() once (allocates nothing at Analyze time).
// Analyze() must run OFF the audio thread (it is called from the UI poll bridge).
class SpectrumAnalyzer {
 public:
    static constexpr int   kFftSize = 2048;
    static constexpr float kFMin = 30.0f;      // Hz, lowest band edge
    static constexpr float kFMax = 16000.0f;   // Hz, highest band edge

    void Init(float sample_rate);
    // Reads the newest kFftSize samples from `ring`, windows + FFTs them, folds
    // magnitudes into `n` log-spaced bands over [kFMin, kFMax]. Writes linear
    // magnitude to bands[0..n).
    void Analyze(const SampleRing& ring, float* bands, int n);

 private:
    stmlib::ShyFFT<float, kFftSize, stmlib::RotationPhasor> fft_;
    float window_[kFftSize];
    float fft_in_[kFftSize];
    float fft_out_[kFftSize];
    float sample_rate_ = 48000.0f;
    bool  initialized_ = false;
};

// Log-frequency spectrogram readout: an 8192-point FFT (~5.9 Hz bins at 48 kHz)
// so the low end resolves, read as dB interpolated between bins at each row's
// frequency. Like SpectrumAnalyzer, Analyze() runs off the audio thread.
class SpectrogramAnalyzer {
 public:
    static constexpr int   kFftSize = 8192;
    static constexpr float kFloorDb = -160.0f;  // silence, well under any display floor

    void Init(float sample_rate);
    // Reads the newest kFftSize samples from `ring` and writes dB to rows[0..n),
    // row 0 at f_min and row n-1 at f_max, log-spaced.
    void Analyze(const SampleRing& ring, float* rows, int n, float f_min, float f_max);

 private:
    stmlib::ShyFFT<float, kFftSize, stmlib::RotationPhasor> fft_;
    float window_[kFftSize];
    float fft_in_[kFftSize];
    float fft_out_[kFftSize];
    float bin_db_[kFftSize / 2];
    float sample_rate_ = 48000.0f;
    bool  initialized_ = false;
};
