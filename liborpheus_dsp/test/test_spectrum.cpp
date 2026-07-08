#include "test_harness.h"
#include "../src/orpheus_spectrum.h"
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

bool run_spectrum_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_spectrum_sine_peaks_in_correct_band());
    tally(test_spectrum_silence_is_floor());
    tally(test_spectrum_no_nan_on_full_scale());
    TEST_SUITE_RETURN(p, f);
}
