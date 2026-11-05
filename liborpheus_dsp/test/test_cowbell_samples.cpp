// Cowbell audition clips: three taps and a tail through a fresh engine, one voicing per clip,
// unnormalised. Manual suite: writes WAV files and a manifest of what can be measured.
#include "test_harness.h"
#include "../include/orpheus_dsp.h"
#include "../src/cowbell_macros.h"
#include "../src/orpheus_engine.h"
#include "../src/orpheus_units.h"
#include "stmlib/utils/random.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr int kSr = 48000;
constexpr int kBlock = 128;
constexpr int kTapGapFrames = 700 * kSr / 1000;
constexpr int kTaps = 3;
constexpr int kTailFrames = 3 * kSr;
constexpr int kLoneTapFrames = 14 * kSr;  // measured for T60, not written
constexpr float kTapVelocity = 0.9f;
constexpr float kCeiling = 0.98f + 1e-5f;  // in orpheus_unit_cowbell.cpp, plus gain x sample rounding
constexpr float kRootHz = 523.2511f;  // root C sounds as C5
constexpr uint32_t kSeed = 0x1234;
constexpr const char* kUri = "org.balch.orpheus.plugins.cowbell";
const char* const kBodyNames[kCowbellBodyCount] = {"string", "sitar", "metal", "synth"};

struct Clip {
    std::string prefix;
    int body;
    float tone, ring, intensity;
};

std::string fmt(const char* f, ...) __attribute__((format(printf, 1, 2)));
std::string fmt(const char* f, ...) {
    char buf[160];
    va_list ap;
    va_start(ap, f);
    vsnprintf(buf, sizeof buf, f, ap);
    va_end(ap);
    return buf;
}

std::string file_of(const Clip& c) {
    const auto pct = [](float v) { return static_cast<int>(std::lround(v * 100.0f)); };
    return fmt("%s%s_t%03d_r%03d_i%03d.wav", c.prefix.c_str(), kBodyNames[c.body], pct(c.tone),
               pct(c.ring), pct(c.intensity));
}

std::vector<Clip> build_clips() {
    std::vector<Clip> clips;
    for (int b = 0; b < kCowbellBodyCount; b++) {
        for (float tone : {0.0f, 0.25f, 0.5f, 0.75f, 1.0f}) clips.push_back({"tone_", b, tone, 0.3f, 0.7f});
    }
    for (int b = 0; b < kCowbellBodyCount; b++) {
        for (float ring : {0.0f, 0.5f, 1.0f}) clips.push_back({"ring_", b, 0.5f, ring, 0.7f});
    }
    for (int b = 0; b < kCowbellBodyCount; b++) {
        for (float intensity : {0.2f, 1.0f}) clips.push_back({"intensity_", b, 0.5f, 0.3f, intensity});
    }
    clips.push_back({"default_", 2, 0.0f, 0.48f, 0.85f});
    clips.push_back({"old_default_", 3, 0.2f, 0.0f, 0.7f});
    return clips;
}

// A fresh engine per render: root C, grid off, level at its default. Returns interleaved stereo.
std::vector<float> render_taps(const Clip& c, int taps, int tail_frames) {
    stmlib::Random::Seed(kSeed);
    OrpheusEngine* e = orpheus_engine_create(static_cast<float>(kSr));
    auto u = std::make_unique<GraphUnit>();
    u->type = UNIT_COWBELL;
    unit_init(u.get(), static_cast<float>(kSr));
    const auto set = [&](const char* symbol, float v) { orpheus_engine_set_port(e, kUri, symbol, v); };
    set("body", static_cast<float>(c.body));
    set("tone", c.tone);
    set("ring", c.ring);
    set("intensity", c.intensity);
    set("grid", 0.0f);
    e->pulsar_root_note.store(0);

    const int total = (taps - 1) * kTapGapFrames + tail_frames;
    std::vector<float> pcm;
    pcm.reserve(static_cast<size_t>(total) * 2);
    int next_tap = 0, taps_left = taps;
    for (int pos = 0; pos < total; pos += kBlock) {
        if (taps_left > 0 && pos >= next_tap) {
            set("strike", static_cast<float>(cowbell_encode_strike(kTapVelocity, 0, 0)));
            next_tap += kTapGapFrames;
            taps_left--;
        }
        const int n = std::min(kBlock, total - pos);
        unit_process_cowbell(u.get(), e, n, static_cast<float>(kSr));
        for (int i = 0; i < n; i++) {
            pcm.push_back(u->output_buffers[OPORT_OUT][i]);
            pcm.push_back(u->output_buffers[OPORT_OUT_RIGHT][i]);
        }
    }
    orpheus_engine_destroy(e);
    return pcm;
}

void fft(std::vector<std::complex<double>>& a) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * M_PI / static_cast<double>(len);
        const std::complex<double> wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<double> w(1.0, 0.0);
            for (size_t k = 0; k < len / 2; ++k) {
                const std::complex<double> u = a[i + k];
                const std::complex<double> v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

// Hann-windowed spectral centroid of the mono sum over the first `frames`.
float centroid_hz(const std::vector<float>& pcm, int frames) {
    size_t fft_n = 1;
    while (fft_n < static_cast<size_t>(frames)) fft_n <<= 1;
    std::vector<std::complex<double>> a(fft_n);
    for (int i = 0; i < frames; i++) {
        const double w = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / (frames - 1));
        a[static_cast<size_t>(i)] = 0.5 * (pcm[2 * i] + pcm[2 * i + 1]) * w;
    }
    fft(a);
    double num = 0.0, den = 0.0;
    for (size_t k = 1; k < fft_n / 2; k++) {
        const double m = std::abs(a[k]);
        num += m * static_cast<double>(k) * kSr / static_cast<double>(fft_n);
        den += m;
    }
    return den > 0.0 ? static_cast<float>(num / den) : 0.0f;
}

float rms_of(const std::vector<float>& pcm, int frames) {
    double sum = 0.0;
    for (int i = 0; i < 2 * frames; i++) sum += static_cast<double>(pcm[i]) * pcm[i];
    return static_cast<float>(std::sqrt(sum / (2.0 * frames)));
}

// T60 estimate of a lone tap: 1.5 x the time its backward-integrated energy takes to fall 40 dB.
// The integral rides over the beating of SITAR's strings; the window caps a very long tail.
float t60_ms(const std::vector<float>& pcm) {
    const size_t bin = 5 * kSr / 1000;
    std::vector<double> energy;
    for (size_t f = 0; (f + bin) * 2 <= pcm.size(); f += bin) {
        double e = 0.0;
        for (size_t i = 2 * f; i < 2 * (f + bin); i++) e += static_cast<double>(pcm[i]) * pcm[i];
        energy.push_back(e);
    }
    double total = 0.0;
    for (double e : energy) total += e;
    double remaining = total;
    for (size_t i = 0; i < energy.size(); i++) {
        remaining -= energy[i];
        if (remaining < 1e-4 * total) return 1.5f * 5.0f * static_cast<float>(i + 1);
    }
    return 1.5f * 5.0f * static_cast<float>(energy.size());
}

// The strongest partial of a lone tap's first 200 ms, between 0.6 and 3 times the root (C5).
float pitch_hz(const std::vector<float>& lone) {
    const int frames = 200 * kSr / 1000;
    std::vector<std::complex<double>> a(65536);  // padded, for the interpolation below
    for (int i = 0; i < frames; i++) {
        const double w = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / (frames - 1));
        a[static_cast<size_t>(i)] = 0.5 * (lone[2 * i] + lone[2 * i + 1]) * w;
    }
    fft(a);
    const double bin_hz = static_cast<double>(kSr) / static_cast<double>(a.size());
    size_t best = static_cast<size_t>(0.6 * kRootHz / bin_hz);
    const size_t top = static_cast<size_t>(3.0 * kRootHz / bin_hz);
    for (size_t k = best; k <= top; k++) {
        if (std::abs(a[k]) > std::abs(a[best])) best = k;
    }
    const double m0 = std::log(std::abs(a[best - 1]) + 1e-12);
    const double m1 = std::log(std::abs(a[best]) + 1e-12);
    const double m2 = std::log(std::abs(a[best + 1]) + 1e-12);
    const double denom = m0 - 2.0 * m1 + m2;
    const double delta = denom != 0.0 ? 0.5 * (m0 - m2) / denom : 0.0;
    return static_cast<float>((static_cast<double>(best) + delta) * bin_hz);
}

// Cents from the nearest whole number of octaves above or below the root: -600..600.
float cents_from_root(float hz) {
    const float cents = 1200.0f * std::log2(hz / kRootHz);
    return cents - 1200.0f * std::round(cents / 1200.0f);
}

// Manual only: the suite writes files, so a no-argument run skips it.
bool should_render() { return g_filtered_run || std::getenv("COWBELL_SAMPLES_DIR") != nullptr; }

}  // namespace

bool run_cowbell_samples() {
    printf("\n=== Cowbell audition clips ===\n");
    if (!should_render()) {
        printf("  skipped: name the suite on the command line or set COWBELL_SAMPLES_DIR\n");
        return true;
    }
    const char* env_dir = std::getenv("COWBELL_SAMPLES_DIR");
    const std::string dir = env_dir ? env_dir : "liborpheus_dsp/test/output/cowbell_samples";
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    if (ec) {
        fprintf(stderr, "Cannot create %s: %s\n", dir.c_str(), ec.message().c_str());
        return false;
    }
    FILE* manifest = fopen((dir + "/manifest.tsv").c_str(), "w");
    if (!manifest) {
        fprintf(stderr, "Cannot open manifest in %s\n", dir.c_str());
        return false;
    }
    fprintf(manifest, "file\tbody\ttone\tring\tintensity\tpeak\trms_700ms\tt60_ms\tcentroid_hz\tpitch_hz\tcents_from_root\n");

    bool ok = true;
    int written = 0;
    for (const Clip& c : build_clips()) {
        const std::string file = file_of(c);
        const std::vector<float> pcm = render_taps(c, kTaps, kTailFrames);
        float peak = 0.0f;
        bool finite = true;
        for (float x : pcm) {
            peak = std::max(peak, std::fabs(x));
            finite = finite && orpheus_is_finite(x);
        }
        const float rms = rms_of(pcm, kTapGapFrames);
        const std::vector<float> lone = render_taps(c, 1, kLoneTapFrames);
        const float t60 = t60_ms(lone);
        const float centroid = centroid_hz(pcm, 200 * kSr / 1000);
        const float pitch = pitch_hz(lone);
        const float off_root = cents_from_root(pitch);
        write_wav((dir + "/" + file).c_str(), pcm.data(), static_cast<int>(pcm.size() / 2), kSr);
        written++;
        fprintf(manifest, "%s\t%s\t%.2f\t%.2f\t%.2f\t%.4f\t%.4f\t%.0f\t%.0f\t%.1f\t%.0f\n", file.c_str(),
                kBodyNames[c.body], c.tone, c.ring, c.intensity, peak, rms, t60, centroid, pitch, off_root);
        printf("  %-40s peak %.4f rms %.4f t60 %6.0f ms centroid %6.0f Hz pitch %7.1f Hz %+4.0f cents\n", file.c_str(),
               peak, rms, t60, centroid, pitch, off_root);
        if (!finite) { printf("  FLAGGED %s: non-finite sample\n", file.c_str()); ok = false; }
        else if (peak < 1e-4f) { printf("  FLAGGED %s: silent\n", file.c_str()); ok = false; }
        else if (peak > kCeiling) { printf("  FLAGGED %s: peak above the ceiling\n", file.c_str()); ok = false; }
    }
    fclose(manifest);
    printf("Wrote %d clips and manifest.tsv to %s\n", written, dir.c_str());
    printf("Cowbell audition clips: %s\n", ok ? "OK" : "FLAGGED");
    return ok;
}
