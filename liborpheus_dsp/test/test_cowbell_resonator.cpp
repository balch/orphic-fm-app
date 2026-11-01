// Cowbell resonator tests: the Rings sources built at a runtime sample rate.
// Deliberately does not include test_harness.h: it pulls in the Plaits headers,
// whose resource macros collide with the Rings ones in a single TU.
#include "rings/dsp/part.h"
#include "stmlib/utils/random.h"

#include "orpheus_cowbell_resonator.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdio>
#include <vector>

extern int g_suite_passes;
extern int g_suite_fails;

namespace {

int g_pass = 0;
int g_fail = 0;

#define CHECK(cond) do { \
    if (cond) { ++g_pass; } \
    else { ++g_fail; printf("  FAIL: %s (line %d)\n", #cond, __LINE__); } \
} while (0)

// In-place radix-2 FFT, n a power of two.
void fft(std::vector<std::complex<float>>& a) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const float ang = -2.0f * 3.14159265f / (float)len;
        const std::complex<float> wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            for (size_t k = 0; k < len / 2; ++k) {
                std::complex<float> u = a[i + k];
                std::complex<float> v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

// Lowest partial in [lo, hi] Hz of an n-point Hann-windowed FFT: the first local peak at least
// 10% of the strongest one, parabolic-interpolated. Picking the strongest peak instead
// reports the 2nd harmonic when it outweighs the fundamental.
float lowest_partial_hz(const float* x, size_t n, float sr, float lo, float hi) {
    std::vector<std::complex<float>> a(n);
    for (size_t i = 0; i < n; ++i) {
        float w = 0.5f - 0.5f * std::cos(2.0f * 3.14159265f * (float)i / (float)(n - 1));
        a[i] = x[i] * w;
    }
    fft(a);
    const float bin_hz = sr / (float)n;
    const size_t k_lo = std::max<size_t>(1, (size_t)(lo / bin_hz));
    const size_t k_hi = (size_t)(hi / bin_hz);
    float strongest = 0.0f;
    for (size_t k = k_lo; k <= k_hi; ++k) strongest = std::max(strongest, std::abs(a[k]));
    size_t best = k_lo;
    for (size_t k = k_lo; k <= k_hi; ++k) {
        float m = std::abs(a[k]);
        if (m >= 0.1f * strongest && m >= std::abs(a[k - 1]) && m >= std::abs(a[k + 1])) {
            best = k;
            break;
        }
    }
    float m0 = std::log(std::abs(a[best - 1]) + 1e-12f);
    float m1 = std::log(std::abs(a[best]) + 1e-12f);
    float m2 = std::log(std::abs(a[best + 1]) + 1e-12f);
    float denom = m0 - 2.0f * m1 + m2;
    float delta = denom != 0.0f ? 0.5f * (m0 - m2) / denom : 0.0f;
    return ((float)best + delta) * bin_hz;
}

// Strum midi `note` on a polyphony-1 string part at `sr`, return the measured fundamental.
float measure_string_f0(float sr, float note) {
    rings::SetSampleRate(sr);
    std::vector<uint16_t> reverb(32768, 0);
    rings::Part part;
    part.Init(reverb.data());
    part.set_model(rings::RESONATOR_MODEL_STRING);
    part.set_polyphony(1);

    // structure 0.25 is the dead zone with zero string dispersion, so the partials stay harmonic.
    rings::Patch patch = {0.25f, 0.5f, 0.8f, 0.5f};  // structure, brightness, damping, position
    rings::PerformanceState ps = {};
    ps.internal_exciter = true;
    ps.tonic = 0.0f;  // note + tonic sounds at its true pitch (note 69 = 440 Hz)
    ps.note = note;

    const size_t block = rings::kMaxBlockSize;
    const size_t total = (size_t)sr;
    std::vector<float> in(block, 0.0f), out(block), aux(block), mono;
    mono.reserve(total + block);
    for (size_t pos = 0; pos < total; pos += block) {
        ps.strum = pos == 0;
        part.Process(ps, patch, in.data(), out.data(), aux.data(), block);
        // `out` alone: at polyphony 1, out + aux cancels the odd harmonics of a string.
        for (size_t i = 0; i < block; ++i) mono.push_back(out[i]);
    }
    // Skip the noisy attack; analyse 8192 samples starting 0.25 s in.
    return lowest_partial_hz(mono.data() + (size_t)(0.25f * sr), 8192, sr, 60.0f, 1000.0f);
}

void test_tuning_at_44k() {
    printf("\n=== Test: Rings string tuning at 44.1 kHz ===\n");
    float f0 = measure_string_f0(44100.0f, 57.0f);  // A3 = 220 Hz
    float cents = 1200.0f * std::log2(f0 / 220.0f);
    printf("  measured f0=%.3f Hz (%.2f cents from 220 Hz)\n", f0, cents);
    CHECK(std::fabs(cents) <= 5.0f);

    // The default 48 kHz path must stay in tune too.
    float f48 = measure_string_f0(48000.0f, 57.0f);
    float cents48 = 1200.0f * std::log2(f48 / 220.0f);
    printf("  measured f0=%.3f Hz at 48 kHz (%.2f cents)\n", f48, cents48);
    CHECK(std::fabs(cents48) <= 5.0f);
}

// ── OrpheusCowbellResonator ─────────────────────────────────────

struct Settings {
    float sr = 48000.0f;
    int model = 0;
    int polyphony = 1;
    float note = 57.0f;
    float structure = 0.5f;
    float brightness = 0.5f;
    float damping = 0.5f;
    float position = 0.5f;
};

// Settings first, then init(): model and polyphony otherwise wait for a strike.
void configure(OrpheusCowbellResonator& r, const Settings& s) {
    r.set_model(s.model);
    r.set_polyphony(s.polyphony);
    r.set_note(s.note);
    r.set_structure(s.structure);
    r.set_brightness(s.brightness);
    r.set_damping(s.damping);
    r.set_position(s.position);
    r.init(s.sr);
}

// Renders `total` frames in `block`-frame calls with a strike at frame 0; returns `out`.
std::vector<float> render_struck(const Settings& s, float velocity, size_t total, int block = 256,
                                 std::vector<float>* aux_out = nullptr) {
    OrpheusCowbellResonator r;
    configure(r, s);
    std::vector<float> out(total), aux(total);
    r.strike(velocity, 0);
    for (size_t pos = 0; pos < total; pos += (size_t)block) {
        int n = (int)std::min<size_t>((size_t)block, total - pos);
        r.process(nullptr, out.data() + pos, aux.data() + pos, n);
    }
    if (aux_out) *aux_out = aux;
    return out;
}

float peak_abs(const float* x, size_t begin, size_t end) {
    float p = 0.0f;
    for (size_t i = begin; i < end; ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

bool all_finite(const std::vector<float>& x) {
    for (float v : x) {
        if (!std::isfinite(v)) return false;
    }
    return true;
}

float max_second_diff(const float* x, size_t begin, size_t end) {
    float m = 0.0f;
    for (size_t i = std::max<size_t>(begin, 2); i < end; ++i) {
        m = std::max(m, std::fabs(x[i] - 2.0f * x[i - 1] + x[i - 2]));
    }
    return m;
}

// Band-limited sawtooth (polyBLEP), amplitude `amp`.
std::vector<float> polyblep_saw(float hz, float sr, size_t n, float amp) {
    std::vector<float> y(n);
    const float dt = hz / sr;
    float phase = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        float v = 2.0f * phase - 1.0f;
        if (phase < dt) {
            float t = phase / dt;
            v -= t + t - t * t - 1.0f;
        } else if (phase > 1.0f - dt) {
            float t = (phase - 1.0f) / dt;
            v -= t * t + t + t + 1.0f;
        }
        y[i] = amp * v;
        phase += dt;
        if (phase >= 1.0f) phase -= 1.0f;
    }
    return y;
}

void test_tuning_matches_at_44k_48k_96k() {
    printf("\n=== Test: tuning_matches_at_44k_48k_96k ===\n");
    for (float sr : {44100.0f, 48000.0f, 96000.0f}) {
        for (int model : {2, 0}) {
            Settings s;
            s.sr = sr;
            s.model = model;
            s.structure = 0.25f;  // the string's zero-dispersion dead zone: harmonic partials
            s.damping = 0.8f;
            std::vector<float> out = render_struck(s, 1.0f, (size_t)sr);
            float f0 = lowest_partial_hz(out.data() + (size_t)(0.25f * sr), 16384, sr, 60.0f, 2000.0f);
            float cents = 1200.0f * std::log2(f0 / 220.0f);
            printf("  sr=%.0f model=%d f0=%.3f Hz (%.2f cents from 220 Hz)\n", sr, model, f0, cents);
            CHECK(std::fabs(cents) <= 5.0f);
        }
    }
}

void test_strike_lands_at_offset() {
    printf("\n=== Test: strike_lands_at_offset ===\n");
    Settings s;
    OrpheusCowbellResonator r;
    configure(r, s);
    std::vector<float> out(256), aux(256);
    r.strike(1.0f, 100);
    r.process(nullptr, out.data(), aux.data(), 256);
    float before = peak_abs(out.data(), 0, 100);
    float after = peak_abs(out.data(), 100, 124);
    printf("  peak before=%g, peak in [100,124)=%g\n", before, after);
    CHECK(before < 1e-6f);
    CHECK(after > 1e-3f);
}

void test_strike_at_block_end_and_two_per_block() {
    printf("\n=== Test: strike_at_block_end_and_two_per_block ===\n");
    Settings s;
    {
        OrpheusCowbellResonator r;
        configure(r, s);
        std::vector<float> out(512), aux(512);
        r.strike(1.0f, 255);
        r.process(nullptr, out.data(), aux.data(), 256);
        r.process(nullptr, out.data() + 256, aux.data() + 256, 256);
        float before = peak_abs(out.data(), 0, 255);
        float onset = peak_abs(out.data(), 255, 255 + 24);
        printf("  strike at 255: peak before=%g, onset=%g\n", before, onset);
        CHECK(before < 1e-6f);
        CHECK(onset > 1e-3f);
    }
    {
        // Two strikes in one block; the reference has only the first, so their difference
        // is the second strike alone.
        std::vector<float> two(256), one(256), aux(256);
        OrpheusCowbellResonator a, b;
        configure(a, s);
        configure(b, s);
        a.strike(0.5f, 10);
        a.strike(1.0f, 200);
        b.strike(0.5f, 10);
        a.process(nullptr, two.data(), aux.data(), 256);
        b.process(nullptr, one.data(), aux.data(), 256);
        float before = peak_abs(two.data(), 0, 10);
        float onset1 = peak_abs(two.data(), 10, 34);
        float diff_before = 0.0f, diff_onset = 0.0f;
        for (size_t i = 0; i < 200; ++i) diff_before = std::max(diff_before, std::fabs(two[i] - one[i]));
        for (size_t i = 200; i < 224; ++i) diff_onset = std::max(diff_onset, std::fabs(two[i] - one[i]));
        printf("  first: before=%g onset=%g; second: diff before=%g onset=%g\n",
               before, onset1, diff_before, diff_onset);
        CHECK(before < 1e-6f);
        CHECK(onset1 > 1e-3f);
        CHECK(diff_before < 1e-6f);
        CHECK(diff_onset > 1e-3f);
    }
}

void test_louder_strike_is_louder() {
    printf("\n=== Test: louder_strike_is_louder ===\n");
    Settings s;
    s.model = 0;
    float peaks[3];
    const float velocities[3] = {0.25f, 0.5f, 1.0f};
    for (int i = 0; i < 3; ++i) {
        std::vector<float> out = render_struck(s, velocities[i], 24000);
        peaks[i] = peak_abs(out.data(), 0, out.size());
        printf("  velocity %.2f -> peak %.4f\n", velocities[i], peaks[i]);
    }
    CHECK(peaks[0] < peaks[1]);
    CHECK(peaks[1] < peaks[2]);
    CHECK(peaks[2] / peaks[0] >= 2.0f);
}

void test_model_changes_at_next_strike() {
    printf("\n=== Test: model_changes_at_next_strike ===\n");
    Settings s;
    s.model = 0;
    OrpheusCowbellResonator r;
    configure(r, s);
    std::vector<float> out(256), aux(256);
    r.strike(1.0f, 0);
    r.process(nullptr, out.data(), aux.data(), 256);
    r.set_model(3);
    for (int i = 0; i < 4800 / 256 + 1; ++i) r.process(nullptr, out.data(), aux.data(), 256);
    printf("  after 100 ms: model()=%d\n", r.model());
    CHECK(r.model() == 0);
    r.strike(1.0f, 0);
    r.process(nullptr, out.data(), aux.data(), 256);
    printf("  after the next strike: model()=%d\n", r.model());
    CHECK(r.model() == 3);
}

enum class Change { kNone, kStep, kRamp };

// A model change re-inits the voices, and their filters ramp in from zero over a block. The
// strike that applies it must still ring at full level, not swallowed by that ramp.
void test_model_change_strike_is_full_level() {
    printf("\n=== Test: model_change_strike_is_full_level ===\n");
    for (int model = 1; model < OrpheusCowbellResonator::kNumModels; ++model) {
        Settings s;
        s.model = 0;
        OrpheusCowbellResonator r;
        configure(r, s);
        const size_t total = 48000, second = 24064, window = 4800;
        std::vector<float> out(total), aux(total);
        stmlib::Random::Seed(0x1234);
        for (size_t pos = 0; pos < total; pos += 256) {
            if (pos == 256) {
                r.set_model(model);
                r.strike(1.0f, 0);
            }
            if (pos == second) r.strike(1.0f, 0);
            int n = (int)std::min<size_t>(256, total - pos);
            r.process(nullptr, out.data() + pos, aux.data() + pos, n);
        }
        float first = peak_abs(out.data(), 256, 256 + window);
        float again = peak_abs(out.data(), second, second + window);
        printf("  0 -> %d: switching strike peak %.4f, next strike %.4f (ratio %.2f)\n", model, first,
               again, again > 0 ? first / again : 0.0f);
        CHECK(first >= 0.7f * again);
    }
}

// Held saw, structure 0 -> 1 at 0.5 s: stepped once (glided by the class), ramped by hand over
// 200 ms, or left alone.
std::vector<float> render_structure_change(Change change_kind, int model) {
    const float sr = 48000.0f;
    const size_t total = (size_t)(0.6f * sr);
    const size_t change = (size_t)(0.5f * sr);
    const size_t ramp_frames = (size_t)(0.2f * sr);
    const int block = 48;
    Settings s;
    s.model = model;
    s.note = 50.0f;
    s.structure = 0.0f;
    OrpheusCowbellResonator r;
    configure(r, s);
    std::vector<float> in = polyblep_saw(440.0f * std::pow(2.0f, (50.0f - 69.0f) / 12.0f), sr, total, 0.25f);
    std::vector<float> out(total), aux(total);
    stmlib::Random::Seed(0x1234);
    for (size_t pos = 0; pos < total; pos += block) {
        if (pos >= change && change_kind != Change::kNone) {
            float t = change_kind == Change::kRamp
                ? std::min(1.0f, (float)(pos - change) / (float)ramp_frames) : 1.0f;
            r.set_structure(t);
        }
        r.process(in.data() + pos, out.data() + pos, aux.data() + pos, block);
    }
    return out;
}

float rms_diff(const std::vector<float>& a, const std::vector<float>& b, size_t begin, size_t end) {
    double sum = 0.0;
    for (size_t i = begin; i < end; ++i) sum += (double)(a[i] - b[i]) * (a[i] - b[i]);
    return (float)std::sqrt(sum / (double)(end - begin));
}

void test_structure_glide_has_no_clicks() {
    printf("\n=== Test: structure_glide_has_no_clicks ===\n");
    const size_t change = 24000, window = 2400;  // 50 ms after the change
    std::vector<float> a = render_structure_change(Change::kStep, 0);
    std::vector<float> b = render_structure_change(Change::kRamp, 0);
    float da = max_second_diff(a.data(), change, change + window);
    float db = max_second_diff(b.data(), change, change + window);
    printf("  maxSecondDiff step=%g ramp=%g (ratio %.3f)\n", da, db, db > 0 ? da / db : 0.0f);
    CHECK(all_finite(a) && all_finite(b));
    CHECK(peak_abs(b.data(), change, change + window) > 1e-3f);
    CHECK(da <= 1.25f * db);

    // The check above passes without any glide (Rings already eases each block), so also
    // check the step glides in: its effect over the first 2 ms is small next to 40-50 ms on.
    for (int model : {0, 2}) {
        std::vector<float> step = render_structure_change(Change::kStep, model);
        std::vector<float> held = render_structure_change(Change::kNone, model);
        float early = rms_diff(step, held, change, change + 96);
        float late = rms_diff(step, held, change + 1920, change + 2400);
        printf("  model=%d: step effect rms 0-2 ms=%g, 40-50 ms=%g (ratio %.3f)\n", model, early,
               late, late > 0 ? early / late : 0.0f);
        CHECK(late > 1e-3f);
        CHECK(early <= 0.25f * late);
    }
}

// Renders 4800 frames with fixed strikes, in calls cycling through `sizes`.
void render_with_blocks(const Settings& s, const std::vector<int>& sizes, bool with_input,
                        std::vector<float>& out, std::vector<float>& aux) {
    const size_t total = 4800;
    const size_t strike_at[] = {0, 333, 1000, 1001, 2205, 3071, 4790};
    const float strike_vel[] = {1.0f, 0.4f, 0.7f, 0.9f, 0.3f, 1.0f, 0.6f};
    std::vector<float> in = polyblep_saw(110.0f, s.sr, total, 0.05f);
    out.assign(total, 0.0f);
    aux.assign(total, 0.0f);
    stmlib::Random::Seed(0x1234);  // the string models draw from the shared generator
    OrpheusCowbellResonator r;
    configure(r, s);
    size_t pos = 0;
    for (size_t call = 0; pos < total; ++call) {
        int n = (int)std::min<size_t>((size_t)sizes[call % sizes.size()], total - pos);
        for (size_t k = 0; k < sizeof(strike_at) / sizeof(strike_at[0]); ++k) {
            if (strike_at[k] >= pos && strike_at[k] < pos + (size_t)n) {
                r.strike(strike_vel[k], (int)(strike_at[k] - pos));
            }
        }
        r.process(with_input ? in.data() + pos : nullptr, out.data() + pos, aux.data() + pos, n);
        pos += (size_t)n;
    }
}

void test_odd_block_sizes_match_one_long_render() {
    printf("\n=== Test: odd_block_sizes_match_one_long_render ===\n");
    struct Case { int model; int polyphony; bool with_input; };
    const Case cases[] = {{0, 1, false}, {0, 4, true}, {2, 2, true}, {5, 1, false}};
    for (const Case& c : cases) {
        Settings s;
        s.model = c.model;
        s.polyphony = c.polyphony;
        std::vector<float> long_out, long_aux, split_out, split_aux;
        render_with_blocks(s, {4800}, c.with_input, long_out, long_aux);
        render_with_blocks(s, {192, 441, 7, 256}, c.with_input, split_out, split_aux);
        float worst = 0.0f;
        for (size_t i = 0; i < long_out.size(); ++i) {
            worst = std::max(worst, std::fabs(long_out[i] - split_out[i]));
            worst = std::max(worst, std::fabs(long_aux[i] - split_aux[i]));
        }
        printf("  model=%d poly=%d input=%d: max diff=%g (peak %g)\n", c.model, c.polyphony,
               (int)c.with_input, worst, peak_abs(long_out.data(), 0, long_out.size()));
        CHECK(peak_abs(long_out.data(), 0, long_out.size()) > 1e-3f);
        CHECK(worst <= 1e-5f);
    }
}

void test_first_strike_uses_settings_from_silence() {
    printf("\n=== Test: first_strike_uses_settings_from_silence ===\n");
    // Silent blocks rendered first: 240 frames keeps the strike on the 24-frame grid, 256 puts
    // it 16 frames in, where the change lands mid sub-block.
    struct Pre { int blocks; int frames; };
    for (Pre pre : {Pre{1, 256}, Pre{10, 240}, Pre{10, 256}})
    for (int model = 0; model < OrpheusCowbellResonator::kNumModels; ++model) {
        Settings s;
        s.model = model;
        s.structure = 0.2f;
        const size_t total = 12000;
        std::vector<float> late(total), early(total), aux(total), silent(256), silent_aux(256);
        // One instance at a time from the same seed: the string models draw dispersion noise
        // from the shared generator, so interleaving them would differ for that reason alone.
        auto render_silence = [&](OrpheusCowbellResonator& r) {
            stmlib::Random::Seed(0x1234);
            for (int i = 0; i < pre.blocks; ++i) {
                r.process(nullptr, silent.data(), silent_aux.data(), pre.frames);
            }
        };
        auto render = [&](OrpheusCowbellResonator& r, std::vector<float>& out) {
            r.strike(1.0f, 0);
            for (size_t pos = 0; pos < total; pos += 256) {
                int n = (int)std::min<size_t>(256, total - pos);
                r.process(nullptr, out.data() + pos, aux.data() + pos, n);
            }
        };

        OrpheusCowbellResonator a;  // structure set to 0.9 just before the strike
        configure(a, s);
        render_silence(a);
        a.set_structure(0.9f);
        render(a, late);

        Settings s9 = s;
        s9.structure = 0.9f;
        OrpheusCowbellResonator b;  // structure 0.9 from init
        configure(b, s9);
        render_silence(b);
        render(b, early);
        float worst = 0.0f;
        for (size_t i = 0; i < total; ++i) worst = std::max(worst, std::fabs(late[i] - early[i]));
        printf("  silence %dx%d, model=%d: max diff=%g (peak %g)\n", pre.blocks, pre.frames, model,
               worst, peak_abs(early.data(), 0, total));
        CHECK(peak_abs(early.data(), 0, total) > 1e-3f);
        CHECK(worst <= 1e-5f);
    }
}

void test_null_input_still_rings() {
    printf("\n=== Test: null_input_still_rings ===\n");
    Settings s;
    s.model = 0;
    std::vector<float> aux;
    std::vector<float> out = render_struck(s, 1.0f, 4800, 256, &aux);
    float p = peak_abs(out.data(), 0, out.size());
    printf("  peak=%g\n", p);
    CHECK(p > 1e-3f);
    CHECK(all_finite(out) && all_finite(aux));
}

void test_settings_grid_stays_sane() {
    printf("\n=== Test: settings_grid_stays_sane ===\n");
    const float sr = 48000.0f;
    const size_t total = (size_t)(0.5f * sr), tail = (size_t)(0.25f * sr), every = (size_t)(0.1f * sr);
    const float grid[3] = {0.0f, 0.5f, 1.0f};
    // The DC blocker and the soft knee would hide a blow-up, so each setting renders twice:
    // raw (output stage off) for the level and DC checks, and conditioned as shipped.
    // FM (model 3) with positive feedback holds an offset of up to ~0.54 by design; the
    // blocker removes it, so its raw DC bound is the measured 0.6 and its conditioned one 0.01.
    const int kFm = 3;
    std::vector<float> out(total), aux(total);
    auto render = [&](const Settings& s, bool raw) {
        OrpheusCowbellResonator r;
        configure(r, s);
        r.set_output_conditioning(!raw);
        for (size_t pos = 0; pos < total; pos += 256) {
            int n = (int)std::min<size_t>(256, total - pos);
            for (size_t t = 0; t < total; t += every) {
                if (t >= pos && t < pos + (size_t)n) r.strike(1.0f, (int)(t - pos));
            }
            r.process(nullptr, out.data() + pos, aux.data() + pos, n);
        }
    };
    auto tail_dc = [&]() {
        double sum_out = 0.0, sum_aux = 0.0;
        for (size_t i = total - tail; i < total; ++i) {
            sum_out += out[i];
            sum_aux += aux[i];
        }
        return (float)std::max(std::fabs(sum_out), std::fabs(sum_aux)) / (float)tail;
    };
    auto peak_both = [&]() {
        return std::max(peak_abs(out.data(), 0, total), peak_abs(aux.data(), 0, total));
    };
    for (int model = 0; model < OrpheusCowbellResonator::kNumModels; ++model) {
        const float raw_dc_max = model == kFm ? 0.6f : 0.01f;
        // Elsewhere the knee (from 0.9) must never engage, so 0.9 bounds the conditioned peak.
        const float shipped_peak_max = model == kFm ? 1.0f : 0.9f;
        int bad_finite = 0, bad_raw_peak = 0, bad_raw_dc = 0, bad_peak = 0, bad_dc = 0;
        float raw_peak_max = 0.0f, raw_dc_worst = 0.0f, peak_max = 0.0f, dc_worst = 0.0f;
        for (int si = 0; si < 3; ++si)
        for (int bi = 0; bi < 3; ++bi)
        for (int di = 0; di < 3; ++di)
        for (int pi = 0; pi < 3; ++pi) {
            Settings s;
            s.sr = sr;
            s.model = model;
            s.polyphony = 4;
            s.structure = grid[si];
            s.brightness = grid[bi];
            s.damping = grid[di];
            s.position = grid[pi];
            for (bool raw : {true, false}) {
                render(s, raw);
                const bool finite = all_finite(out) && all_finite(aux);
                const float peak = peak_both();
                const float dc = tail_dc();
                const char* stage = raw ? "raw" : "shipped";
                if (!finite) {
                    ++bad_finite;
                    printf("  %s non-finite: model=%d s=%.1f b=%.1f d=%.1f p=%.1f\n", stage, model,
                           s.structure, s.brightness, s.damping, s.position);
                }
                const bool peak_bad = raw ? peak > 1.0f : peak > shipped_peak_max;
                const bool dc_bad = raw ? dc > raw_dc_max : dc > 0.01f;
                if (peak_bad) {
                    ++(raw ? bad_raw_peak : bad_peak);
                    printf("  %s peak %.3f: model=%d s=%.1f b=%.1f d=%.1f p=%.1f\n", stage, peak,
                           model, s.structure, s.brightness, s.damping, s.position);
                }
                if (dc_bad) {
                    ++(raw ? bad_raw_dc : bad_dc);
                    printf("  %s dc %.4f: model=%d s=%.1f b=%.1f d=%.1f p=%.1f\n", stage, dc, model,
                           s.structure, s.brightness, s.damping, s.position);
                }
                if (raw) {
                    raw_peak_max = std::max(raw_peak_max, peak);
                    raw_dc_worst = std::max(raw_dc_worst, dc);
                } else {
                    peak_max = std::max(peak_max, peak);
                    dc_worst = std::max(dc_worst, dc);
                }
            }
        }
        printf("  model=%d: raw peak max=%.3f |dc|=%.5f; shipped peak max=%.3f |dc|=%.5f\n", model,
               raw_peak_max, raw_dc_worst, peak_max, dc_worst);
        CHECK(bad_finite == 0);
        CHECK(bad_raw_peak == 0);
        CHECK(bad_raw_dc == 0);
        CHECK(bad_peak == 0);
        CHECK(bad_dc == 0);
        CHECK(peak_max > 1e-3f);
    }
}

// At polyphony 2 and 4, voices that have not been struck yet must sit at the played note, not
// near MIDI 0. A droning FM voice (damping 1) makes a stray one audible as energy under 150 Hz.
void test_unstruck_voices_hold_the_note() {
    printf("\n=== Test: unstruck_voices_hold_the_note ===\n");
    const float sr = 48000.0f;
    for (int poly : {1, 2, 4}) {
        Settings s;
        s.model = 3;
        s.polyphony = poly;
        s.note = 57.0f;
        s.damping = 1.0f;
        std::vector<float> out = render_struck(s, 1.0f, 24000);
        const size_t n = 16384;
        std::vector<std::complex<float>> a(n);
        for (size_t i = 0; i < n; ++i) {
            float w = 0.5f - 0.5f * std::cos(2.0f * 3.14159265f * (float)i / (float)(n - 1));
            a[i] = out[4000 + i] * w;
        }
        fft(a);
        double low = 0.0, all = 0.0;
        for (size_t k = 1; k < n / 2; ++k) {
            double e = std::norm(a[k]);
            all += e;
            if ((float)k * sr / (float)n < 150.0f) low += e;
        }
        const double share = all > 0.0 ? low / all : 1.0;
        printf("  poly=%d: energy under 150 Hz = %.2f%%\n", poly, 100.0 * share);
        CHECK(all > 0.0);
        CHECK(share <= 0.01);
    }
}

}  // namespace

bool run_cowbell_reso_tests() {
    g_pass = g_fail = 0;
    printf("\n########## Cowbell resonator tests ##########\n");
    test_tuning_at_44k();
    test_tuning_matches_at_44k_48k_96k();
    test_strike_lands_at_offset();
    test_strike_at_block_end_and_two_per_block();
    test_louder_strike_is_louder();
    test_model_changes_at_next_strike();
    test_model_change_strike_is_full_level();
    test_structure_glide_has_no_clicks();
    test_odd_block_sizes_match_one_long_render();
    test_first_strike_uses_settings_from_silence();
    test_null_input_still_rings();
    test_settings_grid_stays_sane();
    test_unstruck_voices_hold_the_note();
    printf("\n  cowbell_reso: %d passed, %d failed\n", g_pass, g_fail);
    g_suite_passes = g_pass;
    g_suite_fails = g_fail;
    return g_fail == 0;
}
