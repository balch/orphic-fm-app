// Opt-in detuned double voice. Each case runs twin engines that differ only in the double
// atomics and compares the Pulsar stereo output, track 4 soloed and centred.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

static constexpr int kBlockFrames = 512;
static constexpr int kDblTrack = 4;     // melodic, no ducking
static constexpr int kDblBlocks = 700;  // about 7.5 s at 48 kHz
static constexpr float kSr = 48000.0f;
static constexpr const char* kPulsarUri = "org.balch.orpheus.plugins.pulsar";
static constexpr int kVa = 8;
static constexpr int kSpk = 15;
static constexpr int kBraids = 100;
static constexpr int kChaos = 200;
static constexpr int kOsc = -1;

struct DoubleSetup {
    float detune_edm = 0.0f;
    float detune_space = 0.0f;
    float spread_edm = 0.0f;
    float spread_space = 0.0f;
    int engine_edm = kVa;
    int engine_space = kVa;
    float energy = 0.8f;
    bool grain_fx = false;
    // Writes the EDM detune and spread through orpheus_engine_set_port, not the atomics.
    bool via_port = false;
    float pan = 0.0f;
    float volume = -1.0f;   // the soloed track's volume, both slots, when >= 0
    int lpg_mode = -1;   // both slots when >= 0
    float lpg_decay = 0.0f;
    float morph = -1.0f;   // pinned on both slots when >= 0; on OSC it is the duo detune
    // OSC FM and the per-note morph rise, both slots, through the ports.
    float fm_ratio = 0.0f;
    float fm_depth = 0.0f;
    float morph_rise = 0.0f;
    // Live writes: from each switch's block on, both slots double at its detune and spread.
    struct Switch { int block; float detune; float spread; };
    std::vector<Switch> switches;
};

struct DoubleRun {
    std::vector<float> l, r;
    std::vector<float> note;   // rendered note per block
    std::vector<bool> gate;    // voice_active per block
};

static void set_port(OrpheusEngine* engine, const char* field, float value) {
    char sym[64];
    std::snprintf(sym, sizeof(sym), "track_%d_%s", kDblTrack, field);
    orpheus_engine_set_port(engine, kPulsarUri, sym, value);
}

static void set_port_both(OrpheusEngine* engine, const char* field, float value) {
    set_port(engine, field, value);
    char space[64];
    std::snprintf(space, sizeof(space), "%s_space", field);
    set_port(engine, space, value);
}

static DoubleRun run_double(const DoubleSetup& s) {
    OrpheusEngine* engine = orpheus_engine_create(kSr);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kDblTrack);
    engine->pulsar_energy.store(s.energy, std::memory_order_relaxed);
    engine->pulsar_track_pan[kDblTrack].store(s.pan, std::memory_order_relaxed);
    engine->pulsar_track_pan_glide[kDblTrack].store(s.pan, std::memory_order_relaxed);
    if (s.volume >= 0.0f) {
        engine->pulsar_track_volume[kDblTrack].store(s.volume, std::memory_order_relaxed);
        engine->pulsar_track_volume_space[kDblTrack].store(s.volume, std::memory_order_relaxed);
    }
    engine->pulsar_track_engine_edm[kDblTrack].store(s.engine_edm, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kDblTrack].store(s.engine_space, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kDblTrack].store(0.5f, std::memory_order_relaxed);
    // Long holds: 8 steps is a full second at 120 bpm.
    engine->pulsar_track_hold_probability[kDblTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_probability_space[kDblTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min[kDblTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min_space[kDblTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max[kDblTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max_space[kDblTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_depth[kDblTrack].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_depth_space[kDblTrack].store(0.0f, std::memory_order_relaxed);
    if (s.via_port) {
        set_port(engine, "double_detune", s.detune_edm);
        set_port(engine, "double_spread", s.spread_edm);
    } else {
        engine->pulsar_track_double_detune[kDblTrack].store(s.detune_edm, std::memory_order_relaxed);
        engine->pulsar_track_double_detune_space[kDblTrack].store(s.detune_space, std::memory_order_relaxed);
        engine->pulsar_track_double_spread[kDblTrack].store(s.spread_edm, std::memory_order_relaxed);
        engine->pulsar_track_double_spread_space[kDblTrack].store(s.spread_space, std::memory_order_relaxed);
    }
    if (s.grain_fx) {
        // The Sirens' choir fields: BURST, CHORD, CENTERED.
        set_port_both(engine, "grain_mode", 0.0f);
        set_port_both(engine, "grain_scatter_mode", 0.0f);
        set_port_both(engine, "grain_voicing", 1.0f);
        set_port_both(engine, "grain_scatter", 0.20f);
        set_port_both(engine, "grain_rate", 0.6f);
        set_port_both(engine, "grain_random_timing", 1.0f);
        set_port_both(engine, "grain_shape", 1.0f);
        set_port_both(engine, "grain_size", 0.75f);
        set_port_both(engine, "grain_blend", 0.45f);
        set_port_both(engine, "grain_fx", 1.0f);
    }
    if (s.morph >= 0.0f) {
        set_port_both(engine, "pin_morph", 1.0f);
        set_port_both(engine, "morph", s.morph);
    }
    set_port_both(engine, "fm_ratio", s.fm_ratio);
    set_port_both(engine, "fm_depth", s.fm_depth);
    if (s.morph_rise != 0.0f) {
        set_port_both(engine, "morph_rise", s.morph_rise);
        set_port_both(engine, "morph_rise_time", 0.5f);
    }
    if (s.lpg_mode >= 0) {
        engine->pulsar_track_lpg_mode[kDblTrack].store(s.lpg_mode, std::memory_order_relaxed);
        engine->pulsar_track_lpg_mode_space[kDblTrack].store(s.lpg_mode, std::memory_order_relaxed);
        engine->pulsar_track_lpg_decay[kDblTrack].store(s.lpg_decay, std::memory_order_relaxed);
        engine->pulsar_track_lpg_decay_space[kDblTrack].store(s.lpg_decay, std::memory_order_relaxed);
    }
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    DoubleRun r;
    for (int i = 0; i < kDblBlocks; i++) {
        for (const DoubleSetup::Switch& sw : s.switches) {
            if (i != sw.block) continue;
            engine->pulsar_track_double_detune[kDblTrack].store(sw.detune, std::memory_order_relaxed);
            engine->pulsar_track_double_detune_space[kDblTrack].store(sw.detune, std::memory_order_relaxed);
            engine->pulsar_track_double_spread[kDblTrack].store(sw.spread, std::memory_order_relaxed);
            engine->pulsar_track_double_spread_space[kDblTrack].store(sw.spread, std::memory_order_relaxed);
        }
        unit_process_pulsar(&unit, engine, kBlockFrames, kSr);
        r.l.insert(r.l.end(), engine->pulsar_out_l, engine->pulsar_out_l + kBlockFrames);
        r.r.insert(r.r.end(), engine->pulsar_out_r, engine->pulsar_out_r + kBlockFrames);
        r.note.push_back(engine->pulsar_track_note_debug[kDblTrack].load(std::memory_order_relaxed));
        r.gate.push_back(engine->pulsar_state->tracks[kDblTrack].voice_active);
    }
    orpheus_engine_destroy(engine);
    return r;
}

static DoubleSetup single_twin(DoubleSetup s) {
    s.detune_edm = s.detune_space = 0.0f;
    s.spread_edm = s.spread_space = 0.0f;
    s.via_port = false;
    return s;
}

static bool bit_identical(const DoubleRun& a, const DoubleRun& b) {
    return a.l.size() == b.l.size()
        && std::memcmp(a.l.data(), b.l.data(), a.l.size() * sizeof(float)) == 0
        && std::memcmp(a.r.data(), b.r.data(), a.r.size() * sizeof(float)) == 0;
}

// Bit test, since -ffast-math may fold std::isfinite to true.
static bool all_finite(const DoubleRun& r) {
    for (size_t i = 0; i < r.l.size(); i++)
        if (!scope_is_finite(r.l[i]) || !scope_is_finite(r.r[i])) return false;
    return true;
}

static float peak_of(const DoubleRun& r) {
    float p = 0.0f;
    for (size_t i = 0; i < r.l.size(); i++) p = std::max({p, std::fabs(r.l[i]), std::fabs(r.r[i])});
    return p;
}

static double power_of(const DoubleRun& r) {
    double p = 0.0;
    for (size_t i = 0; i < r.l.size(); i++)
        p += static_cast<double>(r.l[i]) * r.l[i] + static_cast<double>(r.r[i]) * r.r[i];
    return p;
}

// rms(L - R) over rms(L + R): the side's share. A centred mono track sits near 1e-7,
// the cos/sin mismatch of the centre pan gains.
static double side_ratio(const DoubleRun& r) {
    double d = 0.0, s = 0.0;
    for (size_t i = 0; i < r.l.size(); i++) {
        const double l = r.l[i], rr = r.r[i];
        d += (l - rr) * (l - rr);
        s += (l + rr) * (l + rr);
    }
    return s > 0.0 ? std::sqrt(d / s) : 0.0;
}

struct HoldWindow { int start = -1; int length = 0; };

// The longest run of blocks with one rendered note: a held note.
static HoldWindow longest_hold(const DoubleRun& r) {
    HoldWindow best;
    int start = 0;
    for (int i = 1; i <= static_cast<int>(r.note.size()); i++) {
        if (i == static_cast<int>(r.note.size()) || r.note[i] != r.note[start]) {
            if (i - start > best.length) { best.start = start; best.length = i - start; }
            start = i;
        }
    }
    return best;
}

// Fundamental of x[start, start + 4096) by normalised autocorrelation: past the zero-lag
// lobe, the first peak within 90% of the best, parabolically interpolated.
static double estimate_hz(const std::vector<float>& x, size_t start, int kWin = 4096) {
    constexpr int kMaxLag = 1200;   // 40 Hz
    if (start + kWin + kMaxLag + 2 > x.size()) return 0.0;
    std::vector<double> r(kMaxLag + 2, 0.0);
    double e0 = 0.0;
    for (int i = 0; i < kWin; i++) e0 += static_cast<double>(x[start + i]) * x[start + i];
    for (int lag = 0; lag <= kMaxLag + 1; lag++) {
        double c = 0.0, el = 0.0;
        for (int i = 0; i < kWin; i++) {
            c += static_cast<double>(x[start + i]) * x[start + i + lag];
            el += static_cast<double>(x[start + i + lag]) * x[start + i + lag];
        }
        r[lag] = (e0 > 0.0 && el > 0.0) ? c / std::sqrt(e0 * el) : 0.0;
    }
    int first = 1;
    while (first <= kMaxLag && r[first] > 0.0) first++;
    double best = 0.0;
    for (int lag = first; lag <= kMaxLag; lag++) best = std::max(best, r[lag]);
    int lag = first;
    while (lag <= kMaxLag && r[lag] < 0.9 * best) lag++;
    while (lag < kMaxLag && r[lag + 1] > r[lag]) lag++;
    if (lag >= kMaxLag) return 0.0;
    const double a = r[lag - 1], b = r[lag], c = r[lag + 1];
    const double denom = a - 2.0 * b + c;
    const double shift = denom != 0.0 ? 0.5 * (a - c) / denom : 0.0;
    return kSr / (lag + shift);
}

static double correlation(const DoubleRun& r, size_t from, size_t to) {
    double lr = 0.0, ll = 0.0, rr = 0.0;
    for (size_t i = from; i < to && i < r.l.size(); i++) {
        lr += static_cast<double>(r.l[i]) * r.r[i];
        ll += static_cast<double>(r.l[i]) * r.l[i];
        rr += static_cast<double>(r.r[i]) * r.r[i];
    }
    return (ll > 0.0 && rr > 0.0) ? lr / std::sqrt(ll * rr) : 1.0;
}

static bool test_double_detune_zero_is_bit_identical() {
    printf("\n=== Test: detune 0 with spread 1 is bit-identical on every family ===\n");
    bool ok = true;
    for (int eng : {kVa, kSpk, kBraids, kChaos, kOsc}) {
        DoubleSetup s;
        s.engine_edm = s.engine_space = eng;
        if (eng == kOsc) s.morph = 0.0f;   // OSC morph doubles the voice too
        s.spread_edm = s.spread_space = 1.0f;
        const DoubleRun a = run_double(s);
        const DoubleRun b = run_double(single_twin(s));
        const bool same = bit_identical(a, b);
        printf("  engine %d: %s\n", eng, same ? "identical" : "DIFFERS");
        ok &= same;
    }
    printf("Double off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_double_detune_splits_the_pitch() {
    printf("\n=== Test: detune 50, spread 1 puts one voice 25 cents down left, one up right ===\n");
    DoubleSetup s;
    s.detune_edm = s.detune_space = 50.0f;
    s.spread_edm = s.spread_space = 1.0f;
    const DoubleRun dbl = run_double(s);
    const DoubleRun one = run_double(single_twin(s));
    const HoldWindow hold = longest_hold(one);
    printf("  longest hold starts at block %d, %d blocks\n", hold.start, hold.length);
    bool ok = hold.length >= 60;
    if (ok) {
        const size_t at = static_cast<size_t>(hold.start) * kBlockFrames + static_cast<size_t>(kSr / 2);
        const double f0 = estimate_hz(one.l, at);
        const double fl = estimate_hz(dbl.l, at);
        const double fr = estimate_hz(dbl.r, at);
        const double split = 1200.0 * std::log2(fr / fl);
        const double centre = 1200.0 * std::log2(std::sqrt(fl * fr) / f0);
        printf("  single %.3f Hz, L %.3f Hz, R %.3f Hz: split %.2f cents, centre off by %.2f cents\n",
               f0, fl, fr, split, centre);
        ok &= f0 > 0.0 && fl > 0.0 && fr > 0.0;
        ok &= std::fabs(split - 50.0) <= 3.0 && std::fabs(centre) <= 3.0;
    }
    printf("Double pitch split: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_double_spread_zero_is_mono() {
    printf("\n=== Test: spread 0 adds no side; spread 1 decorrelates the channels ===\n");
    DoubleSetup s;
    s.detune_edm = s.detune_space = 20.0f;
    const DoubleRun mono = run_double(s);
    s.spread_edm = s.spread_space = 1.0f;
    const DoubleRun wide = run_double(s);
    // The centre pan gains are cos and sin of pi/4 in float, one ulp apart, so the channels
    // of a mono track differ by that ratio and nothing more.
    const double mono_side = side_ratio(mono);
    const HoldWindow hold = longest_hold(wide);
    bool ok = hold.length >= 60;
    double corr = 1.0;
    if (ok) {
        const size_t from = static_cast<size_t>(hold.start) * kBlockFrames + static_cast<size_t>(kSr / 2);
        const size_t to = static_cast<size_t>(hold.start + hold.length) * kBlockFrames;
        corr = correlation(wide, from, to);
    }
    printf("  spread 0 side ratio %.3g; spread 1 L/R correlation over the hold %.3f (%d blocks)\n",
           mono_side, corr, hold.length);
    ok &= mono_side < 1e-6 && corr < 0.95 && peak_of(mono) > 0.01f;
    printf("Double spread: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_double_keeps_the_level() {
    printf("\n=== Test: detune 12 keeps the track's power within 1.5 dB ===\n");
    bool ok = true;
    for (int eng : {kVa, kSpk}) {
        DoubleSetup s;
        s.engine_edm = s.engine_space = eng;
        s.detune_edm = s.detune_space = 12.0f;
        const double p0 = power_of(run_double(single_twin(s)));
        for (float spread : {0.0f, 1.0f}) {
            s.spread_edm = s.spread_space = spread;
            const double db = 10.0 * std::log10(power_of(run_double(s)) / p0);
            printf("  engine %d spread %.0f: %+.2f dB\n", eng, spread, db);
            ok &= p0 > 0.0 && std::fabs(db) <= 1.5;
        }
    }
    printf("Double level: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_double_renders_every_family() {
    printf("\n=== Test: detune 15, spread 0.5 sounds, bounded, on every family ===\n");
    bool ok = true;
    for (int eng : {kVa, kSpk, kBraids, kChaos, kOsc}) {
        DoubleSetup s;
        s.engine_edm = s.engine_space = eng;
        if (eng == kOsc) s.morph = 0.0f;
        s.detune_edm = s.detune_space = 15.0f;
        s.spread_edm = s.spread_space = 0.5f;
        const DoubleRun dbl = run_double(s);
        const DoubleRun one = run_double(single_twin(s));
        const bool differs = !bit_identical(dbl, one);
        const bool finite = all_finite(dbl);
        const float peak = peak_of(dbl);
        printf("  engine %d: differs=%d finite=%d peak=%.3f (single %.3f)\n",
               eng, differs ? 1 : 0, finite ? 1 : 0, peak, peak_of(one));
        ok &= differs && finite && peak < 1.5f;
    }
    printf("Double families: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_double_ignored_on_mist() {
    printf("\n=== Test: MST ignores the double ===\n");
    DoubleSetup s;
    s.engine_edm = s.engine_space = kMistEngineId;
    s.detune_edm = s.detune_space = 30.0f;
    s.spread_edm = s.spread_space = 1.0f;
    const DoubleRun dbl = run_double(s);
    const bool same = bit_identical(dbl, run_double(single_twin(s)));
    const float peak = peak_of(dbl);
    printf("  identical=%d peak=%.3f\n", same ? 1 : 0, peak);
    const bool ok = same && peak > 0.01f;
    printf("Double on Mist: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// PULSAR_PICK's slot rule: low energy hands the track to the SPACE engine and its atomics.
static bool test_double_follows_the_active_slot() {
    printf("\n=== Test: the active slot's detune applies ===\n");
    DoubleSetup s;
    s.engine_edm = kVa;
    s.engine_space = 14;
    s.detune_edm = 30.0f;
    s.energy = 0.2f;
    const DoubleRun space_run = run_double(s);
    const bool space_same = bit_identical(space_run, run_double(single_twin(s)));
    s.energy = 0.9f;
    const bool edm_same = bit_identical(run_double(s), run_double(single_twin(s)));
    const float space_peak = peak_of(space_run);
    printf("  SPACE active identical=%d (peak %.3f), EDM active identical=%d\n",
           space_same ? 1 : 0, space_peak, edm_same ? 1 : 0);
    const bool ok = space_same && !edm_same && space_peak > 0.01f;
    printf("Double slots: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_double_clamps_wild_port_values() {
    printf("\n=== Test: port detune 500 and spread -3 clamp to 50 and 0; NaN turns off ===\n");
    DoubleSetup wild;
    wild.via_port = true;
    wild.detune_edm = 500.0f;
    wild.spread_edm = -3.0f;
    DoubleSetup tame;
    tame.detune_edm = 50.0f;
    tame.spread_edm = 0.0f;
    const DoubleRun w = run_double(wild);
    const DoubleRun t = run_double(tame);
    const bool same = bit_identical(w, t);
    const bool finite = all_finite(w);
    const float peak = peak_of(w);
    printf("  identical to 50/0=%d finite=%d peak=%.3f\n", same ? 1 : 0, finite ? 1 : 0, peak);
    bool ok = same && finite && peak < 1.5f;

    // A NaN spread flattens to 0; a NaN detune turns the double off.
    const float nan = std::numeric_limits<float>::quiet_NaN();
    DoubleSetup nan_spread;
    nan_spread.via_port = true;
    nan_spread.detune_edm = 12.0f;
    nan_spread.spread_edm = nan;
    DoubleSetup zero_spread;
    zero_spread.detune_edm = 12.0f;
    const DoubleRun ns = run_double(nan_spread);
    const bool ns_same = bit_identical(ns, run_double(zero_spread));
    const bool ns_finite = all_finite(ns);
    DoubleSetup nan_detune;
    nan_detune.via_port = true;
    nan_detune.detune_edm = nan;
    nan_detune.spread_edm = 1.0f;
    const DoubleRun nd = run_double(nan_detune);
    const bool nd_same = bit_identical(nd, run_double(single_twin(nan_detune)));
    printf("  NaN spread: identical to 12/0=%d finite=%d; NaN detune: identical to off=%d peak=%.3f\n",
           ns_same ? 1 : 0, ns_finite ? 1 : 0, nd_same ? 1 : 0, peak_of(nd));
    ok &= ns_same && ns_finite && nd_same && peak_of(nd) > 0.01f;
    printf("Double clamp: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_double_with_grain_fx_keeps_both_sides() {
    printf("\n=== Test: SPK with grain FX and the double keeps both sides, bounded ===\n");
    DoubleSetup s;
    s.engine_edm = s.engine_space = kSpk;
    s.grain_fx = true;
    s.detune_edm = s.detune_space = 12.0f;
    s.spread_edm = s.spread_space = 0.6f;
    const DoubleRun wide = run_double(s);
    s.spread_edm = s.spread_space = 0.0f;
    const DoubleRun narrow = run_double(s);
    const double wide_side = side_ratio(wide), narrow_side = side_ratio(narrow);
    printf("  spread 0.6: side %.3f finite=%d peak=%.3f; spread 0: side %.3f finite=%d peak=%.3f\n",
           wide_side, all_finite(wide) ? 1 : 0, peak_of(wide),
           narrow_side, all_finite(narrow) ? 1 : 0, peak_of(narrow));
    bool ok = wide_side > 0.01 && all_finite(wide) && peak_of(wide) < 1.5f;
    ok &= narrow_side > 0.01 && all_finite(narrow) && peak_of(narrow) < 1.5f;
    // The double's side rides on top of the grains'.
    ok &= wide_side > narrow_side * 1.5;
    printf("Double with grain FX: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static double rms_over(const std::vector<float>& x, size_t from, size_t to) {
    double e = 0.0;
    for (size_t i = from; i < to && i < x.size(); i++) e += static_cast<double>(x[i]) * x[i];
    return to > from ? std::sqrt(e / static_cast<double>(to - from)) : 0.0;
}

// A double that turns on mid-note (slot flip, live port write) strikes voice B, so a PLUCK
// vactrol opens rather than waiting for the next note-on. At spread 1 and centre pan R is B.
// B goes idle on the last note's decayed ring with its gate up, so it sees no edge of its own.
static bool test_double_activation_mid_note_strikes_voice_b() {
    printf("\n=== Test: turning the double back on just after a note-on strikes voice B (PLUCK) ===\n");
    DoubleSetup s;
    s.engine_edm = s.engine_space = 9;   // one oscillator: VA's two at harmonics 0.5 phase-cancel
    s.lpg_mode = 2;                      // LPG_PLUCK
    s.lpg_decay = 0.4f;                  // the last note's ring dies before the hold
    const DoubleRun base = run_double(s);
    const HoldWindow hold = longest_hold(base);
    // The off switch: inside the note before the hold, its ring decayed, the gate up.
    int off_block = -1;
    for (int b = hold.start - 1; b > 0 && hold.start - b < 60 && off_block < 0; b--) {
        if (base.note[b] != base.note[hold.start - 1]) break;
        if (base.gate[b - 1] && base.gate[b] && hold.start - b >= 4) off_block = b;
    }
    bool ok = hold.length >= 60 && off_block > 0;
    double rms_l = 0.0, rms_r = 0.0;
    if (ok) {
        const int on_block = hold.start + 1;
        s.detune_edm = s.detune_space = 12.0f;
        s.spread_edm = s.spread_space = 1.0f;
        s.switches = {{off_block, 0.0f, 1.0f}, {on_block, 12.0f, 1.0f}};
        const DoubleRun r = run_double(s);
        const size_t from = static_cast<size_t>(on_block) * kBlockFrames;
        const size_t to = from + 20 * kBlockFrames;
        rms_l = rms_over(r.l, from, to);
        rms_r = rms_over(r.r, from, to);
    }
    printf("  hold %d blocks at %d, double off at %d, on at %d; next 20 blocks rms L=%.4f R=%.4f\n",
           hold.length, hold.start, off_block, hold.start + 1, rms_l, rms_r);
    ok &= rms_l > 0.005 && rms_r > 0.5 * rms_l;
    printf("Double mid-note strike: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static double block_side_ratio(const DoubleRun& r, int block) {
    double d = 0.0, s = 0.0;
    for (int i = block * kBlockFrames; i < (block + 1) * kBlockFrames; i++) {
        const double l = r.l[i], rr = r.r[i];
        d += (l - rr) * (l - rr);
        s += (l + rr) * (l + rr);
    }
    return s > 0.0 ? std::sqrt(d / s) : 0.0;
}

static double balance_db(const DoubleRun& r, size_t from, size_t to) {
    return 20.0 * std::log10(rms_over(r.r, from, to) / rms_over(r.l, from, to));
}

// Off centre the pair pans like a stereo source through a balance control: L is voice A
// scaled by the left pan gain, R voice B by the right, with no inverted leak into either.
// Two detuned voices beat only a few times over a hold, so their own correlation is not
// zero; an inverted leak shows as panning moving it away from the centre-pan value.
static bool test_double_side_follows_pan() {
    printf("\n=== Test: the double's side follows the track's pan ===\n");
    DoubleSetup s;
    s.engine_edm = s.engine_space = 9;
    s.detune_edm = s.detune_space = 12.0f;
    s.spread_edm = s.spread_space = 1.0f;
    s.pan = 0.6f;
    const DoubleRun dbl = run_double(s);
    const DoubleRun one = run_double(single_twin(s));
    const HoldWindow hold = longest_hold(one);
    bool ok = hold.length >= 60;
    double bal_dbl = 0.0, bal_one = 0.0, corr = 1.0, corr_centre = 0.0, hard_ratio = 1.0;
    if (ok) {
        const size_t from = static_cast<size_t>(hold.start) * kBlockFrames + static_cast<size_t>(kSr / 2);
        const size_t to = static_cast<size_t>(hold.start + hold.length) * kBlockFrames;
        bal_dbl = balance_db(dbl, from, to);
        bal_one = balance_db(one, from, to);
        corr = correlation(dbl, from, to);
        s.pan = 0.0f;
        corr_centre = correlation(run_double(s), from, to);
        s.pan = 1.0f;
        const DoubleRun hard = run_double(s);
        hard_ratio = rms_over(hard.l, from, to) / rms_over(hard.r, from, to);
    }
    printf("  pan 0.6: R/L %.2f dB doubled, %.2f dB single; corr(L, R) %.3f, at centre %.3f; pan 1: L/R %.4f\n",
           bal_dbl, bal_one, corr, corr_centre, hard_ratio);
    ok &= std::fabs(bal_dbl - bal_one) <= 1.0 && std::fabs(corr - corr_centre) < 0.05 && hard_ratio < 0.01;
    printf("Double pan: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Centre pan is the balance law's unity point: the side ratio and level recorded from the
// engine before the side followed the pan.
static bool test_double_centre_pan_unchanged() {
    printf("\n=== Test: at centre pan the double sounds as before ===\n");
    constexpr double kSideRatioBefore = 0.536903;
    constexpr double kPowerDbBefore = 44.8227;
    DoubleSetup s;
    s.detune_edm = s.detune_space = 12.0f;
    s.spread_edm = s.spread_space = 0.6f;
    const DoubleRun r = run_double(s);
    const double side = side_ratio(r);
    const double power_db = 10.0 * std::log10(power_of(r));
    printf("  side ratio %.6f (before %.6f), power %.4f dB (before %.4f)\n",
           side, kSideRatioBefore, power_db, kPowerDbBefore);
    const bool ok = std::fabs(20.0 * std::log10(side / kSideRatioBefore)) <= 0.1
                 && std::fabs(power_db - kPowerDbBefore) <= 0.1;
    printf("Double centre: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A live change of spread or detune glides over about 50 ms rather than stepping. The
// voices do not depend on the spread, so against a twin held at the new spread the
// per-block side ratio scales by the spread in force.
static bool test_double_glides_to_new_settings() {
    printf("\n=== Test: spread and detune changes glide ===\n");
    DoubleSetup s;
    s.volume = 0.5f;   // clear of the output limiter, so L and R stay linear in the side
    s.detune_edm = s.detune_space = 12.0f;
    s.spread_edm = s.spread_space = 0.2f;
    const HoldWindow hold = longest_hold(run_double(single_twin(s)));
    bool ok = hold.length >= 60;
    if (!ok) {
        printf("Double glide: FAIL (no hold)\n");
        return false;
    }
    const int sw = hold.start + 30;
    s.switches = {{sw, 12.0f, 0.8f}};
    const DoubleRun glide = run_double(s);
    DoubleSetup held = s;
    held.switches.clear();
    held.spread_edm = held.spread_space = 0.8f;
    const DoubleRun steady = run_double(held);
    std::vector<double> spread;
    for (int b = sw; b <= sw + 20; b++)
        spread.push_back(0.8 * block_side_ratio(glide, b) / block_side_ratio(steady, b));
    bool rising = true;
    for (int i = 1; i <= 10; i++) rising &= spread[i] >= spread[i - 1] - 1e-3;
    printf("  spread in force: block 0 %.3f, 1 %.3f, 5 %.3f, 10 %.3f, 20 %.3f (rising=%d)\n",
           spread[0], spread[1], spread[5], spread[10], spread[20], rising ? 1 : 0);
    ok &= spread[0] < 0.5 && rising && spread[20] > 0.75;

    // Detune 10 -> 20 cents at spread 1: L carries voice A, R voice B.
    DoubleSetup d;
    d.volume = 0.5f;
    d.detune_edm = d.detune_space = 10.0f;
    d.spread_edm = d.spread_space = 1.0f;
    d.switches = {{sw, 20.0f, 1.0f}};
    const DoubleRun dr = run_double(d);
    auto split_at = [&](int block) {
        const size_t at = static_cast<size_t>(block) * kBlockFrames;
        const double fl = estimate_hz(dr.l, at, 1024), fr = estimate_hz(dr.r, at, 1024);
        return (fl > 0.0 && fr > 0.0) ? 1200.0 * std::log2(fr / fl) : 0.0;
    };
    const double before = split_at(sw - 4), early = split_at(sw), mid = split_at(sw + 5),
                 late = split_at(sw + 20);
    printf("  split: before %.2f, blocks 0-1 %.2f, 5-6 %.2f, 20-21 %.2f cents\n", before, early, mid, late);
    ok &= std::fabs(before - 10.0) < 2.0 && early < 15.0 && early < mid && mid < late
       && std::fabs(late - 20.0) < 2.0;
    printf("Double glide: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// OSC morph is the Orpheus panel's duo detune, +-morph*25 cents, rendered as the double: morph m
// sounds exactly like doubleDetune 50m, adds to an authored doubleDetune, and caps at 50 cents.
// FM on, so this also shows morph no longer reaches the FM index.
static bool test_osc_morph_is_the_double_detune() {
    printf("\n=== Test: OSC morph m renders as doubleDetune 50m, added to the authored one ===\n");
    struct Case { float morph, detune, same_as; };
    const Case cases[] = {
        {0.25f, 0.0f, 12.5f},
        {1.0f, 0.0f, 50.0f},
        {0.5f, 10.0f, 35.0f},   // additive
        {1.0f, 20.0f, 50.0f},   // capped at kDoubleDetuneMaxCents
    };
    bool ok = true;
    for (const Case& c : cases) {
        DoubleSetup s;
        s.engine_edm = s.engine_space = kOsc;
        s.spread_edm = s.spread_space = 0.5f;
        s.fm_ratio = 2.0f;
        s.fm_depth = 0.3f;
        s.morph = c.morph;
        s.detune_edm = s.detune_space = c.detune;
        DoubleSetup ref = s;
        ref.morph = 0.0f;
        ref.detune_edm = ref.detune_space = c.same_as;
        const DoubleRun a = run_double(s);
        const bool same = bit_identical(a, run_double(ref));
        const bool differs = !bit_identical(a, run_double(single_twin(ref)));
        printf("  morph %.2f + detune %.1f vs detune %.1f: identical=%d, differs from single=%d\n",
               c.morph, c.detune, c.same_as, same ? 1 : 0, differs ? 1 : 0);
        ok &= same && differs;
    }
    printf("OSC morph detune: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The FM index is its own field now: depth 0 is FM off whatever the ratio, depth > 0 is FM.
// The per-note morph rise on OSC moves that depth, not the detune.
static bool test_osc_fm_depth_and_rise() {
    printf("\n=== Test: OSC FM rides fm_depth, and morph rise moves it instead of the detune ===\n");
    DoubleSetup off;
    off.engine_edm = off.engine_space = kOsc;
    off.morph = 0.0f;
    DoubleSetup zero_depth = off;
    zero_depth.fm_ratio = 2.0f;
    DoubleSetup fm = zero_depth;
    fm.fm_depth = 0.5f;
    const DoubleRun base = run_double(off);
    const bool depth0_off = bit_identical(base, run_double(zero_depth));
    const bool depth_fm = !bit_identical(base, run_double(fm));

    // FM off (ratio 0): a rise that moved the detune would double the voice. It moves the
    // inert depth instead, so the render is unchanged.
    DoubleSetup rise = off;
    rise.morph_rise = 0.5f;
    const bool rise_no_detune = bit_identical(base, run_double(rise));
    // With FM on the same rise is heard.
    DoubleSetup fm_rise = fm;
    fm_rise.morph_rise = -0.4f;
    const bool rise_moves_fm = !bit_identical(run_double(fm), run_double(fm_rise));
    printf("  depth 0 = off: %d, depth 0.5 = FM: %d, rise without FM unchanged: %d, rise with FM heard: %d\n",
           depth0_off ? 1 : 0, depth_fm ? 1 : 0, rise_no_detune ? 1 : 0, rise_moves_fm ? 1 : 0);
    const bool ok = depth0_off && depth_fm && rise_no_detune && rise_moves_fm && peak_of(base) > 0.01f;
    printf("OSC FM depth: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_double_tests() {
    printf("\n=== Pulsar Double Voice Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_double_detune_zero_is_bit_identical()) suite_pass++; else suite_fail++;
    if (test_double_detune_splits_the_pitch()) suite_pass++; else suite_fail++;
    if (test_double_spread_zero_is_mono()) suite_pass++; else suite_fail++;
    if (test_double_keeps_the_level()) suite_pass++; else suite_fail++;
    if (test_double_renders_every_family()) suite_pass++; else suite_fail++;
    if (test_double_ignored_on_mist()) suite_pass++; else suite_fail++;
    if (test_double_follows_the_active_slot()) suite_pass++; else suite_fail++;
    if (test_double_clamps_wild_port_values()) suite_pass++; else suite_fail++;
    if (test_double_with_grain_fx_keeps_both_sides()) suite_pass++; else suite_fail++;
    if (test_double_activation_mid_note_strikes_voice_b()) suite_pass++; else suite_fail++;
    if (test_double_side_follows_pan()) suite_pass++; else suite_fail++;
    if (test_double_centre_pan_unchanged()) suite_pass++; else suite_fail++;
    if (test_double_glides_to_new_settings()) suite_pass++; else suite_fail++;
    if (test_osc_morph_is_the_double_detune()) suite_pass++; else suite_fail++;
    if (test_osc_fm_depth_and_rise()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
