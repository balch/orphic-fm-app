// Triggered scope tap on the master mix (orpheus_scope.h, orpheus_engine_get_scope).
//
//   liborpheus_dsp/build-desktop/orpheus_dsp_test scope
#include "test_harness.h"
#include "../src/orpheus_scope.h"
#include <atomic>
#include <limits>
#include <memory>
#include <thread>

namespace {

constexpr float  kSr       = 48000.0f;
constexpr double kTwoPi    = 6.283185307179586;
constexpr int    kPoints   = 256;
constexpr float  kWindowMs = 40.0f;

// Writes a mono signal the way the audio thread does: interleaved stereo with L = R, so the ring
// stores the signal exactly. gen(i) gives the sample at absolute index i.
struct Feeder {
    ScopeRing& ring;
    long long pos = 0;

    template <typename Gen>
    void feed(int frames, Gen&& gen) {
        float lr[2 * 512];
        while (frames > 0) {
            const int n = std::min(frames, 512);
            for (int i = 0; i < n; ++i) {
                const float v = gen(pos + i);
                lr[2 * i] = v;
                lr[2 * i + 1] = v;
            }
            ring.write_stereo(lr, n);
            pos += n;
            frames -= n;
        }
    }
};

auto sine(float hz, float amp) {
    return [=](long long i) {
        return amp * static_cast<float>(std::sin(kTwoPi * hz * static_cast<double>(i) / kSr));
    };
}

// Deterministic white noise in [-amp, amp).
struct Noise {
    uint32_t s = 22222u;
    float amp;
    explicit Noise(float a) : amp(a) {}
    float operator()(long long) {
        s = s * 1664525u + 1013904223u;
        return amp * (static_cast<float>(s >> 8) / 8388608.0f - 1.0f);
    }
};

float max_abs(const float* x, int n) {
    float m = 0.0f;
    for (int i = 0; i < n; ++i) m = std::max(m, std::fabs(x[i]));
    return m;
}

// Largest sinusoid amplitude in out at 0..max_cycles cycles per window: the "low-frequency shape".
float low_freq_amplitude(const float* out, int n, int max_cycles) {
    float worst = 0.0f;
    for (int m = 0; m <= max_cycles; ++m) {
        double re = 0.0, im = 0.0;
        for (int j = 0; j < n; ++j) {
            const double ph = kTwoPi * m * (j + 0.5) / n;
            re += out[j] * std::cos(ph);
            im -= out[j] * std::sin(ph);
        }
        const double scale = (m == 0) ? 1.0 / n : 2.0 / n;
        worst = std::max(worst, static_cast<float>(scale * std::sqrt(re * re + im * im)));
    }
    return worst;
}

// A window starting on a rising zero crossing of amp * sin(2 pi hz t).
float max_error_vs_triggered_sine(const float* out, int n, float hz, float amp, float window_ms) {
    const double step = window_ms * 0.001 * kSr / n;
    float worst = 0.0f;
    for (int j = 0; j < n; ++j) {
        const double t = (j + 0.5) * step / kSr;
        const float expected = amp * static_cast<float>(std::sin(kTwoPi * hz * t));
        worst = std::max(worst, std::fabs(out[j] - expected));
    }
    return worst;
}

}  // namespace

static bool test_scope_sine_is_stable_triggered_and_scaled() {
    printf("\n=== Test: 110 Hz sine -> the same triggered, correctly scaled window every read ===\n");
    auto ring = std::make_unique<ScopeRing>();
    Feeder f{*ring};
    const float amp = 0.5f;
    f.feed(24000, sine(110.0f, amp));

    // Uneven advances between reads, as the 60 Hz poll sees callbacks of any size.
    const int advances[] = {128, 441, 512, 96, 300, 1024, 64, 777, 2048, 1};
    float prev[kPoints], cur[kPoints];
    bool pass = true;
    float worst_err = 0.0f, worst_drift = 0.0f;
    for (int read = 0; read < static_cast<int>(sizeof(advances) / sizeof(advances[0])); ++read) {
        f.feed(advances[read], sine(110.0f, amp));
        const int r = scope_read(*ring, kSr, kWindowMs, cur, kPoints);
        if (r != 1) {
            printf("  FAIL: read %d returned %d, want 1 (triggered)\n", read, r);
            pass = false;
            continue;
        }
        worst_err = std::max(worst_err, max_error_vs_triggered_sine(cur, kPoints, 110.0f, amp, kWindowMs));
        if (read > 0) {
            for (int j = 0; j < kPoints; ++j) worst_drift = std::max(worst_drift, std::fabs(cur[j] - prev[j]));
        }
        const float pk = max_abs(cur, kPoints);
        if (std::fabs(pk / amp - 1.0f) > 0.02f) {
            printf("  FAIL: read %d peak %.4f, want %.2f +/- 2%%\n", read, pk, amp);
            pass = false;
        }
        std::copy(cur, cur + kPoints, prev);
    }
    printf("  max error vs a sine starting on its rising crossing: %.5f (limit 0.01)\n", worst_err);
    printf("  max point drift between successive reads: %.5f (limit 0.005)\n", worst_drift);
    printf("  first points: %.4f %.4f %.4f (rising from zero)\n", cur[0], cur[1], cur[2]);
    if (worst_err > 0.01f) { printf("  FAIL: window is not the triggered sine\n"); pass = false; }
    if (worst_drift > 0.005f) { printf("  FAIL: the shape moves between reads\n"); pass = false; }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

// A chord has several rising crossings per common period. The window must start on the same one
// every read, or the trace flips between shapes.
static bool test_scope_chords_hold_their_phase() {
    printf("\n=== Test: chords -> the same triggered window every read ===\n");
    struct Chord { const char* name; float hz[3]; float amp[3]; };
    const Chord chords[] = {
        {"110 + 165 Hz fifth", {110.0f, 165.0f, 0.0f}, {0.3f, 0.3f, 0.0f}},
        {"110 / 137.5 / 165 Hz triad", {110.0f, 137.5f, 165.0f}, {0.25f, 0.2f, 0.2f}},
    };
    const int advances[] = {128, 441, 512, 96, 300, 1024, 64, 777, 2048, 1, 333, 900, 250, 613};
    bool pass = true;
    for (const Chord& c : chords) {
        auto ring = std::make_unique<ScopeRing>();
        Feeder f{*ring};
        auto chord = [&](long long i) {
            float v = 0.0f;
            for (int k = 0; k < 3; ++k) v += sine(c.hz[k], c.amp[k])(i);
            return v;
        };
        f.feed(24000, chord);
        float prev[kPoints], cur[kPoints];
        bool have_prev = false;
        float worst = 0.0f;
        int untriggered = 0;
        for (int adv : advances) {
            f.feed(adv, chord);
            if (scope_read(*ring, kSr, kWindowMs, cur, kPoints) != 1) { ++untriggered; continue; }
            if (have_prev) {
                for (int j = 0; j < kPoints; ++j) worst = std::max(worst, std::fabs(cur[j] - prev[j]));
            }
            std::copy(cur, cur + kPoints, prev);
            have_prev = true;
        }
        printf("  %s: %d untriggered, max drift between reads %.4f (limit 0.02)\n",
               c.name, untriggered, worst);
        if (untriggered > 0 || worst > 0.02f) pass = false;
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

// Two rising crossings per period whose slopes differ by about 1%, with opposite curvature, so a
// slope measured between the two samples around each crossing swings by more than the gap as the
// crossings drift across the sample grid. x(pi - t) = -x(t) makes the slopes tie exactly; the
// small phase offset on the 110 Hz part opens the gap.
static bool test_scope_near_tie_crossings_hold_their_phase() {
    printf("\n=== Test: two crossings ~1%% apart in slope -> the same one every read ===\n");
    const double w = kTwoPi * 110.0 / kSr;
    const double offset = 0.02;  // radians: opens a ~1% slope gap
    auto tone = [&](long long i) {
        const double t = w * static_cast<double>(i);
        return static_cast<float>(0.4 * std::sin(2.0 * t) +
                                  0.2 * std::sin(t + 0.25 * kTwoPi + offset) +
                                  0.06 * std::sin(8.0 * t));
    };
    auto ring = std::make_unique<ScopeRing>();
    Feeder f{*ring};
    f.feed(24000, tone);
    // 441 is coprime to the 436.36-sample period, so the crossings visit many sub-sample offsets.
    float prev[kPoints], cur[kPoints];
    bool have_prev = false;
    int untriggered = 0, flips = 0;
    float worst = 0.0f;
    for (int read = 0; read < 60; ++read) {
        f.feed(441 + 37 * (read % 5), tone);
        if (scope_read(*ring, kSr, kWindowMs, cur, kPoints) != 1) { ++untriggered; continue; }
        if (have_prev) {
            float d = 0.0f;
            for (int j = 0; j < kPoints; ++j) d = std::max(d, std::fabs(cur[j] - prev[j]));
            worst = std::max(worst, d);
            if (d > 0.02f) ++flips;
        }
        std::copy(cur, cur + kPoints, prev);
        have_prev = true;
    }
    printf("  %d untriggered, %d reads flipped crossing, max drift %.4f (limit 0.02)\n",
           untriggered, flips, worst);
    const bool pass = untriggered == 0 && worst <= 0.02f;
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

// Sub-bass: the scan holds only a few crossings, and the oldest sits where the trigger path's
// history begins. A fifth repeats every two root periods, so it needs three root periods in view.
static bool test_scope_floor_tones_hold_their_phase() {
    printf("\n=== Test: sub-bass tones and fifths -> the same window every read ===\n");
    struct Tone { const char* name; float hz[2]; float amp[2]; };
    const Tone tones[] = {
        {"20 Hz alone", {20.0f, 0.0f}, {0.5f, 0.0f}},
        {"22 Hz alone", {22.0f, 0.0f}, {0.5f, 0.0f}},
        {"20 Hz with a quiet 30 Hz fifth", {20.0f, 30.0f}, {0.5f, 0.12f}},
        {"22 Hz with a quiet 33 Hz fifth", {22.0f, 33.0f}, {0.5f, 0.12f}},
        {"25 Hz with a quiet 37.5 Hz fifth", {25.0f, 37.5f}, {0.5f, 0.12f}},
    };
    bool pass = true;
    for (const Tone& t : tones) {
        auto ring = std::make_unique<ScopeRing>();
        Feeder f{*ring};
        auto gen = [&](long long i) { return sine(t.hz[0], t.amp[0])(i) + sine(t.hz[1], t.amp[1])(i); };
        f.feed(24000, gen);
        float prev[kPoints], cur[kPoints];
        bool have_prev = false;
        int untriggered = 0, flips = 0;
        float worst = 0.0f;
        for (int read = 0; read < 80; ++read) {
            f.feed(441 + 97 * (read % 7), gen);  // ~60 Hz polling over ~1.2 s of audio
            if (scope_read(*ring, kSr, kWindowMs, cur, kPoints) != 1) { ++untriggered; continue; }
            if (have_prev) {
                float d = 0.0f;
                for (int j = 0; j < kPoints; ++j) d = std::max(d, std::fabs(cur[j] - prev[j]));
                worst = std::max(worst, d);
                if (d > 0.02f) ++flips;
            }
            std::copy(cur, cur + kPoints, prev);
            have_prev = true;
        }
        printf("  %s: %d untriggered, %d reads flipped, max drift %.4f (limit 0.02)\n",
               t.name, untriggered, flips, worst);
        if (untriggered > 0 || worst > 0.02f) pass = false;
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_rejects_bad_windows() {
    printf("\n=== Test: a NaN, infinite or non-positive window is a bad argument ===\n");
    auto ring = std::make_unique<ScopeRing>();
    Feeder f{*ring};
    f.feed(24000, sine(110.0f, 0.5f));
    const float windows[] = {std::numeric_limits<float>::quiet_NaN(),
                             std::numeric_limits<float>::infinity(),
                             -std::numeric_limits<float>::infinity(), 0.0f, -5.0f};
    const char* names[] = {"NaN", "+inf", "-inf", "0", "-5"};
    bool pass = true;
    for (int i = 0; i < 5; ++i) {
        float out[kPoints];
        std::fill(out, out + kPoints, 9.0f);
        const int r = scope_read(*ring, kSr, windows[i], out, kPoints);
        bool untouched = true;
        for (float v : out) untouched = untouched && v == 9.0f;
        printf("  window %-4s -> %d, out %s\n", names[i], r, untouched ? "untouched" : "WRITTEN");
        if (r != -1 || !untouched) pass = false;
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_counters_wrap() {
    printf("\n=== Test: reads straddling the 2^32 wrap of the sample counters ===\n");
    bool pass = true;

    // Triggered reads before, across and after the wrap.
    auto ring = std::make_unique<ScopeRing>();
    const uint32_t seed = 0xFFFFFFFFu - 30000u;
    ring->published.store(seed);
    ring->claimed.store(seed);
    Feeder f{*ring};
    f.feed(24000, sine(110.0f, 0.5f));
    // Eleven reads 2500 samples apart: the last reads a span (under 8000 samples) wholly past 0.
    float out[kPoints];
    for (int step = 0; step < 11; ++step) {
        const uint32_t before = ring->published.load();
        const int r = scope_read(*ring, kSr, kWindowMs, out, kPoints);
        const float err = max_error_vs_triggered_sine(out, kPoints, 110.0f, 0.5f, kWindowMs);
        printf("  published=%10u result=%d error %.5f\n", before, r, err);
        if (r != 1 || err > 0.01f) pass = false;
        f.feed(2500, sine(110.0f, 0.5f));
    }
    if (ring->published.load() < 16000u) {
        printf("  FAIL: the last read did not clear the wrap\n");
        pass = false;
    }

    // A raw copy whose span crosses the wrap stays contiguous.
    auto raw = std::make_unique<ScopeRing>();
    raw->published.store(seed);
    raw->claimed.store(seed);
    std::vector<float> lr(2 * 512);
    uint32_t idx = 0;
    for (int block = 0; block < 64; ++block) {  // 32768 samples: ends 2768 past the wrap
        for (int i = 0; i < 512; ++i) lr[2 * i] = lr[2 * i + 1] = static_cast<float>(idx + i);
        raw->write_stereo(lr.data(), 512);
        idx += 512;
    }
    std::vector<float> dst(8000);
    const bool intact = scope_ring_copy_latest(*raw, dst.data(), 8000);
    bool contiguous = true;
    for (int i = 0; i < 8000; ++i) contiguous = contiguous && dst[i] == static_cast<float>(idx - 8000 + i);
    printf("  raw copy across the wrap (published=%u): intact=%d contiguous=%d\n",
           raw->published.load(), intact, contiguous);
    if (!intact || !contiguous) pass = false;

    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_non_finite_samples_read_as_zero() {
    printf("\n=== Test: NaN and infinite master samples never reach a window ===\n");
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    auto ring = std::make_unique<ScopeRing>();
    Feeder f{*ring};
    auto blown = [&](long long i) {
        if (i % 97 == 0) return nan;
        if (i % 211 == 0) return (i % 2) ? inf : -inf;
        return sine(110.0f, 0.5f)(i);
    };
    f.feed(24000, blown);

    float out[kPoints];
    const int r = scope_read(*ring, kSr, kWindowMs, out, kPoints);
    int bad_points = 0;
    for (float v : out) bad_points += std::isfinite(v) ? 0 : 1;
    std::vector<float> raw(8192);
    scope_ring_copy_latest(*ring, raw.data(), 8192);
    int bad_samples = 0;
    for (float v : raw) bad_samples += std::isfinite(v) ? 0 : 1;
    printf("  result=%d, non-finite points %d, non-finite samples in the ring %d (limit 0)\n",
           r, bad_points, bad_samples);
    const bool pass = r >= 0 && bad_points == 0 && bad_samples == 0;
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_write_longer_than_ring() {
    printf("\n=== Test: one callback longer than the ring keeps its newest samples ===\n");
    auto ring = std::make_unique<ScopeRing>();
    const int extra = 1000;
    const int n = static_cast<int>(ScopeRing::kSize) + extra;
    std::vector<float> lr(2 * n);
    for (int i = 0; i < n; ++i) lr[2 * i] = lr[2 * i + 1] = static_cast<float>(i);
    ring->write_stereo(lr.data(), n);

    std::vector<float> dst(ScopeRing::kSize);
    const bool intact = scope_ring_copy_latest(*ring, dst.data(), static_cast<int>(ScopeRing::kSize));
    bool newest = true;
    for (uint32_t i = 0; i < ScopeRing::kSize; ++i) newest = newest && dst[i] == static_cast<float>(extra + i);
    const uint32_t published = ring->published.load(), claimed = ring->claimed.load();
    printf("  published=%u claimed=%u (want %d), intact=%d, holds samples %d..%d: %s\n",
           published, claimed, n, intact, extra, n - 1, newest ? "yes" : "NO");
    const bool pass = intact && newest && published == static_cast<uint32_t>(n) &&
                      claimed == static_cast<uint32_t>(n);
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_silence_reads_flat_without_trigger() {
    printf("\n=== Test: silence -> flat window, no trigger ===\n");
    bool pass = true;
    float out[kPoints];

    auto fresh = std::make_unique<ScopeRing>();
    std::fill(out, out + kPoints, 9.0f);
    int r = scope_read(*fresh, kSr, kWindowMs, out, kPoints);
    printf("  never written: result=%d max|out|=%.2e\n", r, max_abs(out, kPoints));
    if (r != 0 || max_abs(out, kPoints) > 1e-6f) pass = false;

    auto ring = std::make_unique<ScopeRing>();
    Feeder f{*ring};
    f.feed(24000, sine(110.0f, 0.5f));
    f.feed(24000, [](long long) { return 0.0f; });  // music stops: 0.5 s of silence
    r = scope_read(*ring, kSr, kWindowMs, out, kPoints);
    printf("  after music stops: result=%d max|out|=%.2e\n", r, max_abs(out, kPoints));
    if (r != 0 || max_abs(out, kPoints) > 1e-6f) pass = false;

    f.feed(24000, sine(110.0f, 0.0005f));  // -66 dBFS hum: below the silence gate
    r = scope_read(*ring, kSr, kWindowMs, out, kPoints);
    printf("  -66 dBFS hum: result=%d\n", r);
    if (r != 0) pass = false;

    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_decimation_does_not_alias_5k() {
    printf("\n=== Test: 5 kHz tone never folds into a low-frequency shape ===\n");
    // 40 ms window: 1 kHz is 40 cycles per window. Point counts cover point rates below, at and
    // above 5 kHz; 205 points put a naive sampler's alias at 125 Hz (5 cycles).
    const int counts[] = {64, 128, 200, 205, 256, 333, 512};
    const float amp = 0.5f;
    bool pass = true;
    for (int n : counts) {
        auto ring = std::make_unique<ScopeRing>();
        Feeder f{*ring};
        f.feed(24000, sine(5000.0f, amp));
        float out[512];
        const int r = scope_read(*ring, kSr, kWindowMs, out, n);
        const float lf = low_freq_amplitude(out, n, 40);

        // What point sampling at the same positions would draw.
        float naive[512];
        const double step = kWindowMs * 0.001 * kSr / n;
        for (int j = 0; j < n; ++j) {
            const long long k = 1000 + static_cast<long long>((j + 0.5) * step);
            naive[j] = amp * static_cast<float>(std::sin(kTwoPi * 5000.0 * k / kSr));
        }
        const float naive_lf = low_freq_amplitude(naive, n, 40);
        printf("  n=%3d result=%d low-freq amplitude %.4f (limit 0.025), point sampling %.4f\n",
               n, r, lf, naive_lf);
        if (r < 0 || lf > 0.025f) pass = false;
        if (n == 205 && naive_lf < 0.2f) {
            printf("  FAIL: the measure no longer sees the alias a naive sampler makes\n");
            pass = false;
        }
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_noise_does_not_trigger() {
    printf("\n=== Test: noise reads untriggered; a noisy tone still triggers ===\n");
    bool pass = true;
    float out[kPoints];

    auto ring = std::make_unique<ScopeRing>();
    Feeder f{*ring};
    Noise noise(0.5f);
    f.feed(24000, [&](long long i) { return noise(i); });
    int triggered = 0, reads = 0;
    for (int i = 0; i < 60; ++i) {
        f.feed(800, [&](long long k) { return noise(k); });
        if (scope_read(*ring, kSr, kWindowMs, out, kPoints) == 1) ++triggered;
        ++reads;
    }
    printf("  white noise: %d/%d reads triggered (limit 2)\n", triggered, reads);
    if (triggered > 2) pass = false;

    // 110 Hz at 0.5 under noise at 0.15 peak (~16 dB below): still a readable, steady tone.
    auto ring2 = std::make_unique<ScopeRing>();
    Feeder g{*ring2};
    Noise hiss(0.15f);
    auto noisy = [&](long long i) { return sine(110.0f, 0.5f)(i) + hiss(i); };
    g.feed(24000, noisy);
    triggered = 0;
    reads = 0;
    for (int i = 0; i < 60; ++i) {
        g.feed(800, noisy);
        if (scope_read(*ring2, kSr, kWindowMs, out, kPoints) == 1) ++triggered;
        ++reads;
    }
    printf("  110 Hz under hiss: %d/%d reads triggered (want >= 57)\n", triggered, reads);
    if (triggered < 57) pass = false;

    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

// Index-encoded writes make any torn copy visible as a break in the count.
static bool test_scope_raw_copy_never_tears() {
    printf("\n=== Test: raw copies under a racing writer are contiguous or reported lapped ===\n");
    bool pass = true;
    int total_ok = 0;
    // 4096 mostly lands intact; 32000 leaves the writer 768 samples of headroom, so it mostly laps.
    const int lengths[] = {4096, 32000};
    for (int len : lengths) {
        auto ring = std::make_unique<ScopeRing>();
        std::atomic<bool> stop{false};
        std::thread writer([&] {
            uint32_t idx = 0, rng = 1u;
            float lr[2 * 512];
            while (!stop.load(std::memory_order_relaxed)) {
                rng = rng * 1664525u + 1013904223u;
                const int n = 1 + static_cast<int>((rng >> 16) % 512u);
                for (int i = 0; i < n; ++i) {
                    const float v = static_cast<float>((idx + static_cast<uint32_t>(i)) & 0xFFFFFu);
                    lr[2 * i] = v;
                    lr[2 * i + 1] = v;
                }
                ring->write_stereo(lr, n);
                idx += static_cast<uint32_t>(n);
            }
        });
        while (ring->published.load(std::memory_order_acquire) < ScopeRing::kSize) std::this_thread::yield();

        std::vector<float> dst(len);
        int ok = 0, lapped = 0, torn = 0;
        const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(300);
        while (std::chrono::steady_clock::now() < until) {
            if (!scope_ring_copy_latest(*ring, dst.data(), len)) { ++lapped; continue; }
            ++ok;
            for (int i = 1; i < len; ++i) {
                const float want = (dst[i - 1] == 1048575.0f) ? 0.0f : dst[i - 1] + 1.0f;
                if (dst[i] != want) { ++torn; break; }
            }
        }
        stop.store(true);
        writer.join();
        printf("  len=%5d: %d intact, %d reported lapped, %d torn (limit 0)\n", len, ok, lapped, torn);
        if (torn > 0) pass = false;
        total_ok += ok;
    }
    if (total_ok == 0) {
        printf("  FAIL: no copy landed intact; the stress no longer checks contiguity\n");
        pass = false;
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_read_never_tears() {
    printf("\n=== Test: triggered reads under a racing writer: right shape or -1 ===\n");
    bool pass = true;
    // 120 Hz is exactly 400 samples, so the writer reads a table and can outrun the reader.
    constexpr int kPeriod = 400;
    float table[kPeriod];
    for (int i = 0; i < kPeriod; ++i) table[i] = 0.5f * static_cast<float>(std::sin(kTwoPi * i / kPeriod));
    auto tone = [&](long long i) { return table[i % kPeriod]; };

    // Writer pace: samples per burst between yields, 0 = never yields, -1 = sleeps 50 us per block.
    const int paces[] = {0, 64, -1};
    int total_good = 0, total_lapped = 0;
    for (int pace : paces) {
        auto ring = std::make_unique<ScopeRing>();
        Feeder warm{*ring};
        warm.feed(24000, tone);
        std::atomic<bool> stop{false};
        std::thread writer([&, pos = warm.pos]() mutable {
            Feeder f{*ring, pos};
            uint32_t rng = 7u;
            int since_yield = 0;
            while (!stop.load(std::memory_order_relaxed)) {
                rng = rng * 1664525u + 1013904223u;
                const int n = 1 + static_cast<int>((rng >> 16) % 512u);
                f.feed(n, tone);
                since_yield += n;
                if (pace < 0) {
                    std::this_thread::sleep_for(std::chrono::microseconds(50));
                } else if (pace > 0 && since_yield >= pace) {
                    since_yield = 0;
                    std::this_thread::yield();
                }
            }
        });

        float out[kPoints];
        int good = 0, lapped = 0, bad = 0, untriggered = 0;
        const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(300);
        while (std::chrono::steady_clock::now() < until) {
            const int r = scope_read(*ring, kSr, kWindowMs, out, kPoints);
            if (r < 0) { ++lapped; continue; }
            if (r == 0) { ++untriggered; continue; }  // a clean read of a steady sine always triggers
            if (max_error_vs_triggered_sine(out, kPoints, 120.0f, 0.5f, kWindowMs) > 0.02f) ++bad;
            else ++good;
        }
        stop.store(true);
        writer.join();
        printf("  pace=%3d: %d right, %d lapped (-1), %d untriggered, %d wrong shape\n",
               pace, good, lapped, untriggered, bad);
        if (bad > 0 || untriggered > 0) pass = false;
        total_good += good;
        total_lapped += lapped;
    }
    if (total_good < 20) {
        printf("  FAIL: only %d reads got through; the stress no longer exercises the reader\n", total_good);
        pass = false;
    }
    if (total_lapped == 0) printf("  note: the writer never lapped a read on this machine\n");
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_scope_taps_what_the_listener_hears() {
    printf("\n=== Test: the engine taps (L+R)/2 of its final output ===\n");
    OrpheusEngine* engine = orpheus_engine_create(kSr);
    if (!load_production_graph(engine)) {
        orpheus_engine_destroy(engine);
        return false;
    }
    activate_voice(engine, 0, 0, 60.0f);
    const int frames = 4800;
    RenderResult rr = render_engine(engine, frames, 10);

    bool pass = true;
    std::vector<float> tap(frames);
    if (!scope_ring_copy_latest(engine->scope_ring, tap.data(), frames)) {
        printf("  FAIL: copy reported lapped with no writer running\n");
        pass = false;
    }
    float worst = 0.0f;
    double sum_sq = 0.0;
    for (int i = 0; i < frames; ++i) {
        const float heard = (rr.buffer[2 * i] + rr.buffer[2 * i + 1]) * 0.5f;
        worst = std::max(worst, std::fabs(tap[i] - heard));
        sum_sq += static_cast<double>(tap[i]) * tap[i];
    }
    const float rms = static_cast<float>(std::sqrt(sum_sq / frames));
    printf("  tap rms %.4f, max |tap - (L+R)/2| %.2e\n", rms, worst);
    if (rms < 1e-3f) { printf("  FAIL: the render was silent, so the comparison proves nothing\n"); pass = false; }
    if (worst > 1e-7f) { printf("  FAIL: the tap is not the output\n"); pass = false; }

    float out[kPoints];
    const int r = orpheus_engine_get_scope(engine, out, kPoints, kWindowMs);
    printf("  orpheus_engine_get_scope on a playing voice: %d\n", r);
    if (r < 0) pass = false;

    const bool bad_args =
        orpheus_engine_get_scope(nullptr, out, kPoints, kWindowMs) == -1 &&
        orpheus_engine_get_scope(engine, nullptr, kPoints, kWindowMs) == -1 &&
        orpheus_engine_get_scope(engine, out, 0, kWindowMs) == -1 &&
        orpheus_engine_get_scope(engine, out, ORPHEUS_SCOPE_MAX_POINTS + 1, kWindowMs) == -1;
    printf("  bad arguments return -1: %s\n", bad_args ? "yes" : "NO");
    if (!bad_args) pass = false;

    orpheus_engine_destroy(engine);
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

bool run_scope_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_scope_sine_is_stable_triggered_and_scaled());
    tally(test_scope_chords_hold_their_phase());
    tally(test_scope_near_tie_crossings_hold_their_phase());
    tally(test_scope_floor_tones_hold_their_phase());
    tally(test_scope_rejects_bad_windows());
    tally(test_scope_counters_wrap());
    tally(test_scope_non_finite_samples_read_as_zero());
    tally(test_scope_write_longer_than_ring());
    tally(test_scope_silence_reads_flat_without_trigger());
    tally(test_scope_decimation_does_not_alias_5k());
    tally(test_scope_noise_does_not_trigger());
    tally(test_scope_raw_copy_never_tears());
    tally(test_scope_read_never_tears());
    tally(test_scope_taps_what_the_listener_hears());
    TEST_SUITE_RETURN(p, f);
}
