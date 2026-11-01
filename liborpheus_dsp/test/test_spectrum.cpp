#include "test_harness.h"
#include "../src/orpheus_spectrum.h"
#include "../include/orpheus_dsp.h"
#include <cmath>
#include <cstdio>

static bool test_spectrum_sine_peaks_in_correct_band() {
    printf("\n=== Test: 1 kHz sine peaks in the band containing 1 kHz ===\n");
    const float sr = 48000.0f;
    const float freq = 1000.0f;
    SampleRing ring;
    for (int i = 0; i < SampleRing::kSize; ++i) {
        ring.write(std::sin(2.0f * 3.14159265f * freq * i / sr));
    }
    SpectrumAnalyzer analyzer;
    analyzer.Init(sr);
    const int N = 40;
    float bands[N];
    analyzer.Analyze(ring, bands, N);

    int argmax = 0;
    for (int b = 1; b < N; ++b) if (bands[b] > bands[argmax]) argmax = b;

    const float ratio = SpectrumAnalyzer::kFMax / SpectrumAnalyzer::kFMin;
    float f_lo = SpectrumAnalyzer::kFMin * std::pow(ratio, (float)argmax / N);
    float f_hi = SpectrumAnalyzer::kFMin * std::pow(ratio, (float)(argmax + 1) / N);
    printf("  argmax band=%d range=[%.0f,%.0f] Hz mag=%.4f\n", argmax, f_lo, f_hi, bands[argmax]);

    bool pass = (freq >= f_lo * 0.85f && freq <= f_hi * 1.15f);
    printf(pass ? "  PASS\n" : "  FAIL: 1 kHz not in peak band (check ShyFFT imag index)\n");
    return pass;
}

static bool test_spectrum_silence_is_floor() {
    printf("\n=== Test: silence -> near-zero bands ===\n");
    SampleRing ring;  // zero-initialized
    SpectrumAnalyzer analyzer;
    analyzer.Init(48000.0f);
    const int N = 40;
    float bands[N];
    analyzer.Analyze(ring, bands, N);
    float maxv = 0.0f;
    for (int b = 0; b < N; ++b) if (bands[b] > maxv) maxv = bands[b];
    bool pass = maxv < 1e-4f;
    printf("  max band on silence = %.6f  %s\n", maxv, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_spectrum_no_nan_on_full_scale() {
    printf("\n=== Test: full-scale broadband -> finite bands ===\n");
    SampleRing ring;
    // deterministic pseudo-noise, full scale
    uint32_t s = 22222u;
    for (int i = 0; i < SampleRing::kSize; ++i) {
        s = s * 1664525u + 1013904223u;
        ring.write(((s >> 8) / 8388608.0f) - 1.0f);  // ~[-1,1)
    }
    SpectrumAnalyzer analyzer;
    analyzer.Init(48000.0f);
    const int N = 64;
    float bands[N];
    analyzer.Analyze(ring, bands, N);
    bool pass = true;
    for (int b = 0; b < N; ++b) {
        if (!std::isfinite(bands[b])) { pass = false; printf("  FAIL: band %d not finite\n", b); }
    }
    if (pass) printf("  PASS (all %d bands finite)\n", N);
    return pass;
}

static float spectrogram_row_hz(int r, int n, float f_min, float f_max) {
    return f_min * std::pow(f_max / f_min, (float)r / (n - 1));
}

static bool test_spectrogram_sine_lands_on_its_row() {
    printf("\n=== Test: spectrogram puts a 1 kHz sine's peak on the 1 kHz row ===\n");
    const float sr = 48000.0f;
    SampleRing ring;
    for (int i = 0; i < SampleRing::kSize; ++i) ring.write(0.5f * std::sin(2.0f * 3.14159265f * 1000.0f * i / sr));
    static SpectrogramAnalyzer analyzer;
    analyzer.Init(sr);
    const int N = 256;
    float rows[N];
    analyzer.Analyze(ring, rows, N, 50.0f, 10000.0f);
    int argmax = 0;
    for (int r = 1; r < N; ++r) if (rows[r] > rows[argmax]) argmax = r;
    float hz = spectrogram_row_hz(argmax, N, 50.0f, 10000.0f);
    // Hann's coherent gain halves a 0.5 sine to ~0.25 (-12 dB); falling between bins costs up to ~3 dB more.
    bool pass = std::fabs(hz - 1000.0f) < 15.0f && rows[argmax] > -16.0f && rows[argmax] < -10.0f;
    printf("  peak row=%d (%.0f Hz) at %.1f dB  %s\n", argmax, hz, rows[argmax], pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_spectrogram_resolves_low_notes() {
    printf("\n=== Test: spectrogram separates 55 Hz from 82 Hz, a fifth a 2048-point FFT merges ===\n");
    const float sr = 48000.0f;
    SampleRing ring;
    for (int i = 0; i < SampleRing::kSize; ++i) {
        float t = i / sr;
        ring.write(0.3f * std::sin(2.0f * 3.14159265f * 55.0f * t) + 0.3f * std::sin(2.0f * 3.14159265f * 82.4f * t));
    }
    static SpectrogramAnalyzer analyzer;
    analyzer.Init(sr);
    const int N = 256;
    float rows[N];
    analyzer.Analyze(ring, rows, N, 50.0f, 10000.0f);
    auto at = [&](float hz) {
        int best = 0;
        for (int r = 1; r < N; ++r) {
            if (std::fabs(spectrogram_row_hz(r, N, 50.0f, 10000.0f) - hz) <
                std::fabs(spectrogram_row_hz(best, N, 50.0f, 10000.0f) - hz)) best = r;
        }
        return rows[best];
    };
    float low = at(55.0f), mid = at(68.0f), high = at(82.4f);
    bool pass = mid < low - 6.0f && mid < high - 6.0f;
    printf("  55 Hz %.1f dB, 68 Hz %.1f dB, 82 Hz %.1f dB  %s\n", low, mid, high, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_spectrogram_silence_and_bad_args() {
    printf("\n=== Test: spectrogram silence sits at the floor, bad args leave rows alone ===\n");
    SampleRing ring;
    static SpectrogramAnalyzer analyzer;
    analyzer.Init(48000.0f);
    const int N = 64;
    float rows[N];
    analyzer.Analyze(ring, rows, N, 50.0f, 10000.0f);
    bool pass = true;
    for (int r = 0; r < N; ++r) {
        if (!(rows[r] <= SpectrogramAnalyzer::kFloorDb + 0.01f)) { pass = false; printf("  FAIL: row %d = %f\n", r, rows[r]); }
    }
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    rows[0] = 123.0f;
    pass &= orpheus_engine_get_spectrogram(engine, rows, N, 0.0f, 10000.0f) == 0;
    pass &= orpheus_engine_get_spectrogram(engine, rows, N, 500.0f, 50.0f) == 0;
    pass &= orpheus_engine_get_spectrogram(nullptr, rows, N, 50.0f, 10000.0f) == 0;
    pass &= rows[0] == 123.0f;
    pass &= orpheus_engine_get_spectrogram(engine, rows, N, 50.0f, 10000.0f) == N;
    orpheus_engine_destroy(engine);
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

bool run_spectrum_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_spectrum_sine_peaks_in_correct_band());
    tally(test_spectrum_silence_is_floor());
    tally(test_spectrum_no_nan_on_full_scale());
    tally(test_spectrogram_sine_lands_on_its_row());
    tally(test_spectrogram_resolves_low_notes());
    tally(test_spectrogram_silence_and_bad_args());
    TEST_SUITE_RETURN(p, f);
}
