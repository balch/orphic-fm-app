// Storm weather voice tests (rain/rumble/claps/StormVoice + pulsar integration).
// The first block is direct-call on the generators; the integration block at the
// bottom drives the whole pulsar unit through its engine atomics.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "../src/pulsar_storm.h"
#include "stmlib/utils/random.h"
#include <cstring>

// A real high-pass for the probes below: two Butterworth Svf stages, 24 dB/oct. The
// earlier "two cascaded one-poles subtracted from the input" is only first-order near
// its corner (1 - 1/(1+s)^2 ~ 2s), which let the roll's 165 Hz body through a nominal
// 2 kHz "high-pass" at -19 dB and float every onset detector's floor.
static std::vector<float> highpass4(const std::vector<float>& x, float hz) {
    stmlib::Svf h1, h2; h1.Init(); h2.Init();
    h1.set_f_q<stmlib::FREQUENCY_EXACT>(hz / 48000.0f, 0.707f);
    h2.set_f_q<stmlib::FREQUENCY_EXACT>(hz / 48000.0f, 0.707f);
    std::vector<float> y(x.size());
    for (size_t i = 0; i < x.size(); i++)
        y[i] = h2.Process<stmlib::FILTER_MODE_HIGH_PASS>(h1.Process<stmlib::FILTER_MODE_HIGH_PASS>(x[i]));
    return y;
}

// Bounds and DC only. Level no longer buys per-drop loudness — that is
// test_rain_level_buys_drops_and_a_far_field_wash's job — so the peak assertion here is
// deliberately a wide sanity band rather than a level curve.
static bool test_rain_bounds_and_dc() {
    printf("\n=== Test: rain stays finite, bounded and DC-free at every level ===\n");
    storm::RainGen rain; rain.Init(0xBEA7u, 48000.0f);
    bool ok = true;
    for (float level : {0.25f, 0.5f, 1.0f}) {
        rain.set_level(level);
        float pk = 0.f; double sum = 0.0; int total = 0;
        std::vector<float> l(512), r(512);
        for (int b = 0; b < 400; b++) {                     // ~4.3 s: past the slew
            std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
            rain.Process(l.data(), r.data(), 512);
            for (int i = 0; i < 512; i++) {
                if (!std::isfinite(l[i]) || !std::isfinite(r[i])) ok = false;
                pk = std::max({pk, std::fabs(l[i]), std::fabs(r[i])});
                sum += l[i]; total++;
            }
        }
        float dc = (float)(sum / total);
        printf("  level=%.2f peak=%.3f dc=%.5f\n", level, pk, dc);
        ok &= pk > 0.02f && pk < 1.1f && std::fabs(dc) < 0.02f;
    }
    return ok;
}

static bool test_rain_level_zero_is_silent() {
    printf("\n=== Test: rain at level 0 emits exact silence after slew ===\n");
    storm::RainGen rain; rain.Init(1u, 48000.0f);
    rain.set_level(0.8f);
    std::vector<float> l(512), r(512);
    for (int b = 0; b < 100; b++) { std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f); rain.Process(l.data(), r.data(), 512); }
    rain.set_level(0.0f);
    float tail_pk = 1.f;
    for (int b = 0; b < 200; b++) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        rain.Process(l.data(), r.data(), 512);
        tail_pk = 0.f;
        for (int i = 0; i < 512; i++) tail_pk = std::max({tail_pk, std::fabs(l[i]), std::fabs(r[i])});
    }
    printf("  final-block peak=%.6g\n", tail_pk);
    return tail_pk < 1e-4f;   // decayed to (near-)silence, no stuck dust
}

// Renders `seconds` of one channel at a fixed level and gain, past the slew. Gain 1 is
// RainGen's own Init value, so the default leaves the RNG stream exactly where a
// gain-unaware caller left it.
static std::vector<float> render_rain(float level, float seconds, uint32_t seed = 0xBEA7u,
                                      float gain = 1.0f) {
    storm::RainGen rain; rain.Init(seed, 48000.0f);
    rain.set_level(level);
    rain.set_gain(gain);
    std::vector<float> blk(512), other(512), out;
    for (int b = 0; b < 40; b++) {
        std::fill(blk.begin(), blk.end(), 0.f); std::fill(other.begin(), other.end(), 0.f);
        rain.Process(blk.data(), other.data(), 512);
    }
    const int blocks = (int)(seconds * 48000.0f / 512.0f);
    out.reserve((size_t)blocks * 512);
    for (int b = 0; b < blocks; b++) {
        std::fill(blk.begin(), blk.end(), 0.f); std::fill(other.begin(), other.end(), 0.f);
        rain.Process(blk.data(), other.data(), 512);
        out.insert(out.end(), blk.begin(), blk.end());
    }
    return out;
}

struct RainStats {
    float rms = 0.f, peak = 0.f, onsets_per_sec = 0.f, fill = 0.f, ring_ms = 0.f;
};

// `fill` is the 10th-percentile 10 ms window RMS over the whole render's RMS: near 0 when
// the texture is separated events with silence between them, near 1 when it is a
// continuous sheet. It is the one cheap number that tells sparse drops from rainfall.
static RainStats rain_stats(float level, float seconds, uint32_t seed = 0xBEA7u,
                            float gain = 1.0f) {
    const std::vector<float> x = render_rain(level, seconds, seed, gain);
    RainStats s;
    double sq = 0.0;
    for (float v : x) { sq += (double)v * v; s.peak = std::max(s.peak, std::fabs(v)); }
    s.rms = (float)std::sqrt(sq / x.size());
    // Onsets: a rise past a third of the loudest drop, with a 5 ms refractory. Drops now
    // ring for 0.5-4 ms, so that is long enough to count each one at most once.
    const float thresh = s.peak * 0.33f;
    int onsets = 0, last = -48000;
    std::vector<float> rings;
    for (size_t i = 0; i + 2000 < x.size(); i++) {
        if (std::fabs(x[i]) <= thresh || (int)i - last <= 240) continue;
        onsets++; last = (int)i;
        // How long this drop rings: from its own peak until a 0.5 ms running max falls
        // under a fifth of it. Only meaningful where drops are separated — at a downpour
        // the next one lands inside the window and the measurement runs away.
        float lp = 0.f; int li = (int)i;
        for (int k = (int)i; k < (int)i + 48; k++)
            if (std::fabs(x[k]) > lp) { lp = std::fabs(x[k]); li = k; }
        int end = li;
        for (int k = li; k < li + 1440; k++) {              // give up past 30 ms
            float m = 0.f;
            for (int j = k; j < k + 24; j++) m = std::max(m, std::fabs(x[j]));
            if (m <= lp * 0.2f) break;
            end = k;
        }
        rings.push_back((end - li) * 1000.f / 48000.f);
    }
    s.onsets_per_sec = onsets / seconds;
    std::sort(rings.begin(), rings.end());
    s.ring_ms = rings.empty() ? -1.f : rings[rings.size() / 2];
    constexpr int kWin = 480;                                // 10 ms
    std::vector<float> win;
    for (size_t i = 0; i + kWin <= x.size(); i += kWin) {
        double w = 0.0;
        for (int k = 0; k < kWin; k++) w += (double)x[i + k] * x[i + k];
        win.push_back((float)std::sqrt(w / kWin));
    }
    std::sort(win.begin(), win.end());
    s.fill = s.rms > 0.f ? win[win.size() / 10] / s.rms : 0.f;
    return s;
}

// The complaint this answers: rain read as a LEAK — a few identical drops in a resonant
// space. Rainfall is two layers at once, near drops over a far-field wash, and the
// density floor has to be a light shower rather than a dripping tap. This replaces an
// earlier test that asserted the opposite (that the bottom of the range was a countable
// drip, ~4 drops/s); that premise was the bug. What is pinned instead is what `rain`
// means: it buys DROPS and a super-linear wash, never per-drop size.
//
// 0.12 is not an arbitrary probe level — it is what the shipped storm section authors at
// its start before ramping to 0.95, so it is the setting most of a storm is heard at.
static bool test_rain_level_buys_drops_and_a_far_field_wash() {
    printf("\n=== Test: rain level buys drops and a far-field wash, not drop size ===\n");
    constexpr float kDrizzle = 0.12f, kMid = 0.45f, kDownpour = 0.95f;
    constexpr float kSeconds = 12.0f;
    auto designed = [](float level) {
        return storm::kRainMinHitsHz *
               std::pow(storm::kRainMaxHitsHz / storm::kRainMinHitsHz, level);
    };
    const RainStats a = rain_stats(kDrizzle, kSeconds);
    const RainStats b = rain_stats(kMid, kSeconds);
    const RainStats c = rain_stats(kDownpour, kSeconds);
    const float rate_a = designed(kDrizzle), rate_c = designed(kDownpour);

    bool ok = true;
    // 1. The floor is rain, not a tap. A faucet is a few drips a second; the floor has to
    //    stay well clear of that, and L/R draw independent dust so the field gets twice
    //    it. The other side of this dial — that the floor stays LOW enough for slow rain
    //    to be authorable at all — is pinned by test_low_rain_is_genuinely_sparse.
    if (storm::kRainMinHitsHz < 8.0f) {
        printf("  FAIL: a %.0f/s floor is a dripping tap, not the quietest rain\n",
               storm::kRainMinHitsHz); ok = false;
    }
    if (a.onsets_per_sec < 12.0f) {
        printf("  FAIL: only %.1f drops/s at the authored %.2f -- that is a leak\n",
               a.onsets_per_sec, kDrizzle); ok = false;
    }
    if (a.onsets_per_sec < rate_a * 0.4f || a.onsets_per_sec > rate_a * 1.2f) {
        printf("  FAIL: %.1f onsets/s at level %.2f, designed rate is %.1f/s\n",
               a.onsets_per_sec, kDrizzle, rate_a); ok = false;
    }
    // 2. A drop is a soft tick, not a plink. Ring time is Q/(pi*f), so this is really an
    //    assertion about kRainQ: at 20 the worst-case drop rang for 15 ms, long enough to
    //    carry a definite pitch, and a few identical pitched drops IS a leak. The floor
    //    catches the other end, where drops degenerate into broadband clicks.
    if (a.ring_ms > 4.5f || a.ring_ms < 0.5f) {
        printf("  FAIL: drops ring %.2f ms (kRainQ=%.1f) -- want a tick, not a pitched plink\n",
               a.ring_ms, storm::kRainQ); ok = false;
    }
    // 3. Per-drop level is flat: nearly quadrupling the density has to move the bed a
    //    long way while barely moving the loudest single drop.
    if (b.rms < a.rms * 1.6f) {
        printf("  FAIL: %.2fx the drops bought only %.2fx the level\n",
               designed(kMid) / rate_a, b.rms / a.rms); ok = false;
    }
    if (b.peak > a.peak * 2.0f) {
        printf("  FAIL: peak %.3f -> %.3f -- per-drop loudness is tracking level\n",
               a.peak, b.peak); ok = false;
    }
    // 4. Aggregate climbs a long way from drizzle to downpour...
    if (c.rms < a.rms * 4.0f) {
        printf("  FAIL: downpour rms %.4f is only %.1fx the drizzle's %.4f\n",
               c.rms, c.rms / a.rms, a.rms); ok = false;
    }
    // 5. ...and further than drops alone could take it. Drop count buys only sqrt(rate)
    //    of level, so an aggregate that outruns that is the far-field wash showing up.
    //    Deleting the wash, or making it linear in level, lands here.
    const float sqrt_density = std::sqrt(rate_c / rate_a);
    if (c.rms / a.rms < sqrt_density * 1.25f) {
        printf("  FAIL: rms climbed x%.2f but %.2fx the drops alone give x%.2f -- no wash\n",
               c.rms / a.rms, rate_c / rate_a, sqrt_density); ok = false;
    }
    // 6. And the texture crosses over: separated events at the bottom, a sheet at the top.
    if (a.fill > 0.30f) {
        printf("  FAIL: drizzle fill %.2f -- the bottom is already a continuous bed\n", a.fill);
        ok = false;
    }
    if (c.fill < 0.60f) {
        printf("  FAIL: downpour fill %.2f -- the top never becomes rainfall\n", c.fill);
        ok = false;
    }
    if (ok) printf("  PASS: %.1f drops/s at %.2f (designed %.0f, %.2f ms ring), rms x%.2f to %.2f"
                   " vs x%.2f from density alone, peak x%.2f at %.2f, fill %.2f -> %.2f\n",
                   a.onsets_per_sec, kDrizzle, rate_a, a.ring_ms, c.rms / a.rms, kDownpour,
                   sqrt_density, b.peak / a.peak, kMid, a.fill, c.fill);
    return ok;
}

// rainLevel is the other half of the pair: `rain` buys drops, this buys loudness, and
// neither may do the other's job. The ear complaint it answers is that a bed could only
// be quietened by thinning it — there was no "heavy but distant" and no "sparse but
// close". Onsets are detected against a threshold derived from the render's own peak, so
// the count is scale-invariant by construction and any drift in it is the gain leaking
// into the density map.
static bool test_rain_level_scales_loudness_not_rate() {
    printf("\n=== Test: rainLevel scales loudness without moving the drop rate ===\n");
    constexpr float kLevel = 0.6f, kSeconds = 10.0f;
    const RainStats full = rain_stats(kLevel, kSeconds, 0xBEA7u, 1.0f);
    const RainStats half = rain_stats(kLevel, kSeconds, 0xBEA7u, 0.5f);
    const RainStats low  = rain_stats(kLevel, kSeconds, 0xBEA7u, 0.2f);

    bool ok = true;
    // Rate is untouched: same seed, same level, so the dust stream is the same one.
    for (const RainStats* s : {&half, &low}) {
        if (std::fabs(s->onsets_per_sec - full.onsets_per_sec) > full.onsets_per_sec * 0.02f) {
            printf("  FAIL: %.1f drops/s vs %.1f at full level -- the gain moved the density\n",
                   s->onsets_per_sec, full.onsets_per_sec); ok = false;
        }
    }
    // Loudness follows the gain, and linearly: it scales the near drops and the far
    // wash by one number, so their balance cannot shift with it.
    auto linear = [&](const RainStats& s, float g, const char* what) {
        const float want = full.rms * g;
        if (std::fabs(s.rms - want) > want * 0.06f) {
            printf("  FAIL: gain %.2f gave rms %.5f, expected %.5f (%s)\n", g, s.rms, want, what);
            ok = false;
        }
    };
    linear(half, 0.5f, "half");
    linear(low, 0.2f, "low");
    if (half.peak > full.peak * 0.6f || low.peak > full.peak * 0.3f) {
        printf("  FAIL: peaks %.4f/%.4f/%.4f do not track the gain\n",
               full.peak, half.peak, low.peak); ok = false;
    }

    // Gain 0 is silence, wash included, and exactly zero rather than a floor — the same
    // contract level 0 has. Long enough to clear the slew before the last block is read.
    storm::RainGen mute; mute.Init(0xBEA7u, 48000.0f);
    mute.set_level(1.0f);
    mute.set_gain(0.0f);
    std::vector<float> l(512), r(512);
    float tail = 1.0f;
    for (int b = 0; b < 200; b++) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        mute.Process(l.data(), r.data(), 512);
        tail = 0.f;
        for (int i = 0; i < 512; i++) tail = std::max({tail, std::fabs(l[i]), std::fabs(r[i])});
    }
    if (tail != 0.0f) {
        printf("  FAIL: rainLevel 0 left %.4g of residual at full density\n", tail); ok = false;
    }
    if (ok) printf("  PASS: %.1f drops/s at every gain; rms %.5f -> %.5f -> %.5f (x1/x0.5/x0.2),"
                   " gain 0 exactly silent\n",
                   full.onsets_per_sec, full.rms, half.rms, low.rms);
    return ok;
}

// The setting the ear test said did not exist: soft, SLOW rain. The floor was raised to
// 50/s to kill a dripping-tap drizzle, which left the sparsest authorable rain a solid
// patter — you could thin a downpour but never reach light rainfall. The tap character
// was sparse AND pitched AND unaccompanied; kRainQ took the pitch out and rainLevel now
// carries the loudness, so the floor can come back down. Pins both halves: the events
// have to be separated (fill), and they have to stay ticks rather than plinks (ring).
static bool test_low_rain_is_genuinely_sparse() {
    printf("\n=== Test: a low rain level renders separated drops, not a sheet ===\n");
    constexpr float kSlow = 0.05f, kSeconds = 14.0f;
    const RainStats s = rain_stats(kSlow, kSeconds);
    const float designed = storm::kRainMinHitsHz *
        std::pow(storm::kRainMaxHitsHz / storm::kRainMinHitsHz, kSlow);

    bool ok = true;
    // The floor's upper bound. Raising it past this makes slow rain unauthorable again,
    // whatever the lower bound in test_rain_level_buys_drops_and_a_far_field_wash allows.
    if (storm::kRainMinHitsHz > 25.0f) {
        printf("  FAIL: a %.0f/s floor has no slow rain under it\n", storm::kRainMinHitsHz);
        ok = false;
    }
    if (s.onsets_per_sec > 30.0f) {
        printf("  FAIL: %.1f drops/s at level %.2f is continuous rainfall, not slow rain\n",
               s.onsets_per_sec, kSlow); ok = false;
    }
    if (s.onsets_per_sec < designed * 0.4f) {
        printf("  FAIL: %.1f onsets/s against a designed %.1f/s -- drops are going missing\n",
               s.onsets_per_sec, designed); ok = false;
    }
    // Separated: a tenth of the 10 ms windows must be near-silent gaps between impacts.
    // A downpour lands above 0.6 here, so this is the texture crossover from the other end.
    if (s.fill > 0.15f) {
        printf("  FAIL: fill %.2f -- the slowest rain is already a continuous bed\n", s.fill);
        ok = false;
    }
    // Sparse must not mean pitched: a few identical ringing drops IS the leak.
    if (s.ring_ms > 4.5f || s.ring_ms < 0.5f) {
        printf("  FAIL: drops ring %.2f ms -- sparse AND pitched is the tap again\n", s.ring_ms);
        ok = false;
    }
    if (ok) printf("  PASS: %.1f drops/s at level %.2f (designed %.1f), fill %.2f, %.2f ms ring\n",
                   s.onsets_per_sec, kSlow, designed, s.fill, s.ring_ms);
    return ok;
}

static bool test_rumble_tail_length_and_onset() {
    printf("\n=== Test: rumble tail — fast onset, seconds-long decay ===\n");
    storm::RumbleGen rum; rum.Init(0xBEA7u, 48000.0f);
    rum.set_bed(0.f, 0.5f);
    rum.trigger_tail(1.0f, 0.2f);
    std::vector<float> l(512), r(512);
    float first_pk = 0.f;
    std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
    rum.Process(l.data(), r.data(), 512);
    for (int i = 0; i < 512; i++) first_pk = std::max(first_pk, std::fabs(l[i]));
    int active_blocks = 0;
    while (rum.tail_active() && active_blocks < 48000 * 12 / 512) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        rum.Process(l.data(), r.data(), 512);
        active_blocks++;
    }
    float seconds = active_blocks * 512.f / 48000.f;
    printf("  onset peak=%.3f, tail=%.2f s\n", first_pk, seconds);
    return first_pk > 0.05f && seconds > 2.0f && seconds < 9.0f;
}

static bool test_rumble_tail_spans_intensity_range() {
    printf("\n=== Test: tail length sweeps the 2-8 s design range with intensity ===\n");
    bool ok = true;
    float prev = 0.f;
    for (float intensity : {0.0f, 0.5f, 1.0f}) {
        storm::RumbleGen rum; rum.Init(0xBEA7u, 48000.0f);
        rum.set_bed(0.f, 0.5f);
        rum.trigger_tail(intensity, 0.2f);
        std::vector<float> l(512), r(512);
        int blocks = 0;
        while (rum.tail_active() && blocks < 48000 * 15 / 512) {
            std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
            rum.Process(l.data(), r.data(), 512);
            blocks++;
        }
        float seconds = blocks * 512.f / 48000.f;
        printf("  intensity=%.2f tail=%.2f s\n", intensity, seconds);
        ok &= seconds > 1.9f && seconds < 8.5f && seconds > prev;   // spec range, and longer with intensity
        prev = seconds;
    }
    return ok;
}

// Both generators are pre-normalization: StormVoice sizes its own gain and soft
// limit against this ceiling, so it has to be pinned, not assumed.
static bool test_storm_worst_case_peak_bounded() {
    printf("\n=== Test: worst-case rain + rumble peak stays finite and bounded ===\n");
    storm::RainGen rain; rain.Init(0xBEA7u, 48000.0f);
    storm::RumbleGen rum; rum.Init(0xBEA7u, 48000.0f);
    rain.set_level(1.0f);
    rum.set_bed(1.0f, 0.0f);        // loudest bed: closest distance
    rum.trigger_tail(1.0f, 0.0f);   // full-intensity close strike stacked on it
    std::vector<float> l(512), r(512);
    float rumble_pk = 0.f, sum_pk = 0.f;
    bool finite = true;
    for (int b = 0; b < 400; b++) {                 // ~4.3 s
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        rum.Process(l.data(), r.data(), 512);
        for (int i = 0; i < 512; i++)
            rumble_pk = std::max({rumble_pk, std::fabs(l[i]), std::fabs(r[i])});
        rain.Process(l.data(), r.data(), 512);      // ADDS on top of the rumble
        for (int i = 0; i < 512; i++) {
            if (!std::isfinite(l[i]) || !std::isfinite(r[i])) finite = false;
            sum_pk = std::max({sum_pk, std::fabs(l[i]), std::fabs(r[i])});
        }
    }
    printf("  rumble-only peak=%.3f, rain+rumble peak=%.3f\n", rumble_pk, sum_pk);
    return finite && rumble_pk < 2.0f && sum_pk < 2.0f;
}

static bool test_rumble_is_low_frequency() {
    printf("\n=== Test: rumble energy is mostly <200 Hz (zero-crossing proxy) ===\n");
    storm::RumbleGen rum; rum.Init(7u, 48000.0f);
    rum.set_bed(0.8f, 0.3f);
    std::vector<float> l(4800), r(4800);
    for (int warm = 0; warm < 40; warm++) { std::fill(l.begin(), l.begin()+512, 0.f); std::fill(r.begin(), r.begin()+512, 0.f); rum.Process(l.data(), r.data(), 512); }
    std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
    rum.Process(l.data(), r.data(), 4800);                  // 100 ms
    int zc = 0;
    for (int i = 1; i < 4800; i++) if ((l[i-1] >= 0.f) != (l[i] >= 0.f)) zc++;
    printf("  zero crossings in 100ms: %d (expect < ~60 for <300 Hz content)\n", zc);
    return zc > 2 && zc < 60;
}

// Thunder has to travel: swell, recede, and arrive at full strength when it strikes.
// The undulation used to be deliberately shallow (a ~1.3x wobble around 0.75), which is
// what left a roll sounding like a second, duller rain bed. Measured over half-second
// windows so the resonant low-pass's own noisiness cannot be mistaken for the swell.
static bool test_rumble_roll_swells_and_recedes() {
    printf("\n=== Test: the rumble bed swells and recedes instead of sitting flat ===\n");
    storm::RumbleGen rum; rum.Init(0xBEA7u, 48000.0f);
    rum.set_bed(0.8f, 0.3f);
    std::vector<float> l(24000), r(24000);
    for (int b = 0; b < 60; b++) {
        std::fill(l.begin(), l.begin() + 512, 0.f); std::fill(r.begin(), r.begin() + 512, 0.f);
        rum.Process(l.data(), r.data(), 512);
    }
    std::vector<float> win;
    for (int b = 0; b < 120; b++) {                       // 60 s of bed
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        rum.Process(l.data(), r.data(), 24000);
        double sq = 0.0;
        for (int i = 0; i < 24000; i++) sq += (double)l[i] * l[i];
        win.push_back((float)std::sqrt(sq / 24000));
    }
    std::sort(win.begin(), win.end());
    const float trough = win[win.size() / 20], crest = win.back();
    const float swing = crest / (trough + 1e-9f);

    // A strike must not be at the mercy of where the roll happens to be: trigger_tail
    // re-aims the undulation so the wavefront always arrives on a crest.
    float weakest_onset = 1e9f;
    for (uint32_t s = 0; s < 24u; s++) {
        storm::RumbleGen t; t.Init(0xBEA7u + 37u * s, 48000.0f);
        t.set_bed(0.f, 0.2f);
        for (int b = 0; b < 200; b++) {                   // let the roll wander first
            std::fill(l.begin(), l.begin() + 512, 0.f); std::fill(r.begin(), r.begin() + 512, 0.f);
            t.Process(l.data(), r.data(), 512);
        }
        t.trigger_tail(1.0f, 0.2f);
        float pk = 0.f;
        for (int b = 0; b < 8; b++) {                     // first ~85 ms
            std::fill(l.begin(), l.begin() + 512, 0.f); std::fill(r.begin(), r.begin() + 512, 0.f);
            t.Process(l.data(), r.data(), 512);
            for (int i = 0; i < 512; i++) pk = std::max(pk, std::fabs(l[i]));
        }
        weakest_onset = std::min(weakest_onset, pk);
    }

    bool ok = true;
    if (swing < 4.0f) {
        printf("  FAIL: crest/trough is only x%.1f -- the roll is back to a flat bed\n", swing);
        ok = false;
    }
    if (trough <= 0.0f) { printf("  FAIL: the roll went to a dead stop, not a trough\n"); ok = false; }
    if (weakest_onset < 0.15f) {
        printf("  FAIL: a strike landed at peak %.3f -- the roll swallowed it\n", weakest_onset);
        ok = false;
    }
    if (ok) printf("  PASS: 0.5s-rms %.4f..%.4f (x%.1f), weakest of 24 strike onsets %.3f\n",
                   trough, crest, swing, weakest_onset);
    return ok;
}

// "Fade to silent" is the ear-test wording, and it has to be literal: a strike over a dry
// bed must render itself all the way to zero AND stop claiming the voice. The second half
// is the one that used to leak — strike_active() was latched off the intensity at trigger
// and then held for the envelope's whole run, so an inaudible tail blocked the next bolt
// for seconds.
static bool test_strike_fades_to_actual_silence() {
    printf("\n=== Test: a strike over a dry bed decays to literal silence ===\n");
    storm::StormVoice v; v.Init(0xBEA7u, 48000.0f);
    v.set_bed(0.f, 0.f, 0.f, 0.f);
    v.trigger_strike(1.0f, 0.2f);
    std::vector<float> l(512), r(512);
    int blocks = 0, release_block = -1;
    float last_audible = 0.0f;
    while (blocks < 48000 * 20 / 512) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        v.Process(l.data(), r.data(), 512);
        float pk = 0.f;
        for (int i = 0; i < 512; i++) pk = std::max({pk, std::fabs(l[i]), std::fabs(r[i])});
        if (pk > 1e-3f) last_audible = blocks * 512.f / 48000.f;
        if (release_block < 0 && !v.strike_active()) release_block = blocks;
        blocks++;
        if (v.settled()) break;
    }
    const float settled_at = blocks * 512.f / 48000.f;
    const float released_at = release_block >= 0 ? release_block * 512.f / 48000.f : -1.f;

    // Everything after settling must be exactly zero, not a floor.
    float residual = 0.f;
    for (int b = 0; b < 200; b++) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        v.Process(l.data(), r.data(), 512);
        for (int i = 0; i < 512; i++) residual = std::max({residual, std::fabs(l[i]), std::fabs(r[i])});
    }

    bool ok = true;
    if (!v.settled() || settled_at > 15.0f) {
        printf("  FAIL: never settled (%.2f s, settled=%d)\n", settled_at, (int)v.settled()); ok = false;
    }
    if (residual != 0.0f) { printf("  FAIL: %.3g of residual after settling\n", residual); ok = false; }
    if (released_at < 0.0f) { printf("  FAIL: the voice never released the strike\n"); ok = false; }
    // The gate has to let go once the roll is inaudible, not once its envelope expires.
    if (released_at > last_audible + 1.5f) {
        printf("  FAIL: last audible at %.2f s but the strike held the voice to %.2f s\n",
               last_audible, released_at); ok = false;
    }
    if (ok) printf("  PASS: audible to %.2f s, released at %.2f s, silent (exactly 0) from %.2f s\n",
                   last_audible, released_at, settled_at);
    return ok;
}

static bool test_generators_deterministic_under_seed() {
    printf("\n=== Test: same seed → same output (instance RNG only) ===\n");
    auto render = [](uint32_t seed) {
        storm::RainGen g; g.Init(seed, 48000.0f); g.set_level(0.7f);
        std::vector<float> l(2048, 0.f), r(2048, 0.f);
        g.Process(l.data(), r.data(), 2048);
        return l;
    };
    auto a = render(42u), b = render(42u), c = render(43u);
    bool same = a == b, diff = a != c;
    printf("  rain: same-seed identical: %s, different-seed differs: %s\n",
           same ? "OK" : "FAIL", diff ? "OK" : "FAIL");

    // A strike carries two more pieces of state that a pinned seed has to reproduce: the
    // Lorenz trajectory driving the clap grain, and the echo line behind it. Rendered
    // long enough to run well past the longest reflection.
    auto strike = [](uint32_t seed) {
        storm::StormVoice v; v.Init(seed, 48000.0f);
        v.set_bed(0.f, 0.f, 0.f, 0.f);
        v.trigger_strike(0.9f, 0.35f);
        constexpr int kN = 48000 / 2;
        std::vector<float> l(kN, 0.f), r(kN, 0.f);
        for (int off = 0; off < kN; off += 512)
            v.Process(l.data() + off, r.data() + off, 512);
        return l;
    };
    auto sa = strike(42u), sb = strike(42u), sc = strike(43u);
    const bool strike_same = sa == sb, strike_diff = sa != sc;
    bool loud = false;
    for (float s : sa) if (std::fabs(s) > 0.05f) { loud = true; break; }
    printf("  strike (chaos + echo): same-seed identical: %s, different-seed differs: %s\n",
           strike_same ? "OK" : "FAIL", strike_diff ? "OK" : "FAIL");
    if (!loud) printf("  FAIL: the reference strike render is silent\n");
    return same && diff && strike_same && strike_diff && loud;
}

static bool test_strike_burst_spacing_sub_block() {
    printf("\n=== Test: strike claps land 32-52 ms apart (sub-block scheduling) ===\n");
    storm::StormVoice v; v.Init(0xBEA7u, 48000.0f);
    v.set_bed(0.f, 0.f, 0.f, 0.f);
    v.trigger_strike(1.0f, 0.0f);
    constexpr int N = 48000;                                 // 1 s
    std::vector<float> l(N, 0.f), r(N, 0.f);
    for (int off = 0; off < N; off += 512) v.Process(l.data() + off, r.data() + off, 512);
    // Onset detection: envelope jumps of >0.15 within 1 ms, at most one per 20 ms. The
    // refractory window is what keeps the crackle's rips inside a hit (a few ms apart,
    // by design) from counting as hits of their own; the authored gaps are 32 ms and up.
    std::vector<int> onsets;
    float env = 0.f; int last_onset = -48000;
    for (int i = 0; i < N; i++) {
        float a = std::fabs(l[i]);
        if (a > env + 0.15f && i - last_onset > 960) { onsets.push_back(i); last_onset = i; }
        env += (a - env) * (a > env ? 0.3f : 0.002f);
    }
    printf("  onsets detected: %zu at", onsets.size());
    for (int o : onsets) printf(" %.1fms", o * 1000.f / 48000.f);
    printf("\n");
    // Authored gaps run 32..52 ms; the bottom step is a 420 Hz band that takes some
    // 10 ms to ring up past the detector, so its gap reads about that much longer.
    if (onsets.size() < 2 || onsets.size() > 6) return false;
    for (size_t k = 1; k < onsets.size() && k < 4; k++) {
        float gap_ms = (onsets[k] - onsets[k-1]) * 1000.f / 48000.f;
        if (gap_ms < 6.f || gap_ms > 70.f) { printf("  FAIL gap %.1f ms\n", gap_ms); return false; }
    }
    return true;
}

// The envelope-jump detector above is a coarse proxy: a Q=4 band-passed noise hit's
// own envelope fluctuates on roughly the same timescale as a millisecond, so it can
// only pin "roughly 2-6 onsets, 6-60ms apart" — a standalone probe against the real
// render showed a rendered-audio onset detector's noise floor is comparable to a
// +/-1ms tolerance, i.e. not actually exact there. This test pins the schedule
// exactly instead, reading the scheduler's own per-hit delay/gain right after
// trigger() through a debug accessor (ORPHEUS_TESTING-gated, zero cost in production
// builds, same convention as the engine's debug peek atomics) rather than inferring
// it from audio. Expected delays and gains are re-derived here from
// kClapSpacingMs/kClapGain/kClapDropGain directly, so a retune of any of them moves
// this test's expectations with it.
static bool test_burst_schedule_matches_ear_tune_constants_exactly() {
    printf("\n=== Test: clap schedule matches kClapSpacingMs/kClapGain exactly ===\n");
    storm::ClapGen claps;
    claps.Init(0xBEA7u, 48000.0f);
    claps.trigger(/*intensity=*/1.0f, /*distance=*/0.0f);   // burst = 1*1*1 = 1: no scaling to undo

    bool ok = true;
    for (int k = 0; k < storm::kClapCount; k++) {
        const int expected_delay = (int)(storm::kClapSpacingMs[k] * 0.001f * 48000.0f + 0.5f);
        const int actual_delay = claps.debug_hit_delay(k);
        if (actual_delay != expected_delay) {
            printf("  FAIL: hit %d delay=%d, expected %d samples (%.1fms cumulative)\n",
                   k, actual_delay, expected_delay, storm::kClapSpacingMs[k]);
            ok = false;
        }

        const float expected_gain = storm::kClapGain[k] * storm::kClapDropGain;
        const float actual_gain = claps.debug_hit_gain(k);
        if (std::fabs(actual_gain - expected_gain) > 1e-5f * (1.0f + std::fabs(expected_gain))) {
            printf("  FAIL: hit %d gain=%.6f, expected %.6f (kClapGain=%.2f * kClapDropGain=%.2f)\n",
                   k, actual_gain, expected_gain, storm::kClapGain[k], storm::kClapDropGain);
            ok = false;
        }

        // kClapGain is authored monotonically non-increasing (loudest hit first); since
        // kClapDropGain is a fixed positive scale, that shape carries straight through
        // to the scheduled per-hit gain above.
        if (k > 0 && storm::kClapGain[k] > storm::kClapGain[k - 1]) {
            printf("  FAIL: kClapGain[%d]=%.3f > kClapGain[%d]=%.3f -- not monotone\n",
                   k, storm::kClapGain[k], k - 1, storm::kClapGain[k - 1]);
            ok = false;
        }
        printf("  hit %d: delay=%d (%.1fms) gain=%.4f\n", k, actual_delay,
               actual_delay * 1000.0f / 48000.0f, actual_gain);
    }
    if (ok) printf("  PASS: %d hits scheduled exactly per the EAR-TUNE constants\n", storm::kClapCount);
    return ok;
}

// The pitch staircase is the whole "clap-clap" gesture: a strike has to crack high
// and step DOWN into the rumble, not scatter four hits around one shared centre —
// that shared draw is what made a burst read as a single blurred smear. Asserted
// against the band-pass coefficient each hit will actually render through, so a
// mutant that keeps per-hit bookkeeping while configuring one filter still fails,
// and over enough seeds that the +/-4% jitter gets its chance to reorder a step.
static bool test_clap_pitch_staircase_descends() {
    printf("\n=== Test: clap centres step strictly downward (pitch staircase) ===\n");
    constexpr uint32_t kSeeds = 64u;
    constexpr int kDistances = 3;
    const float distances[kDistances] = {0.0f, 0.4f, 0.8f};
    bool ok = true;
    float top[kDistances] = {};
    for (int d = 0; d < kDistances; d++) {
        float lo_top = 1e9f, hi_top = 0.f, weakest_step = 0.f;
        for (uint32_t s = 0; s < kSeeds; s++) {
            storm::ClapGen claps;
            claps.Init(0xBEA7u + 101u * s, 48000.0f);
            claps.trigger(/*intensity=*/1.0f, distances[d]);
            float prev = 1e9f;
            for (int k = 0; k < storm::kClapCount; k++) {
                const float c = claps.debug_hit_center(k);
                if (!(c < prev)) {
                    printf("  FAIL: seed %u distance %.1f -- hit %d at %.0f Hz does not sit below %.0f Hz\n",
                           s, distances[d], k, c, prev);
                    ok = false;
                }
                if (c < storm::kClapCenterMinHz || c > storm::kClapCenterMaxHz) {
                    printf("  FAIL: hit %d centre %.0f Hz is outside the %.0f-%.0f Hz band\n",
                           k, c, storm::kClapCenterMinHz, storm::kClapCenterMaxHz);
                    ok = false;
                }
                if (k == 0) { lo_top = std::min(lo_top, c); hi_top = std::max(hi_top, c); }
                else { weakest_step = std::max(weakest_step, c / prev); }
                prev = c;
            }
        }
        top[d] = hi_top;
        printf("  distance=%.1f  top step %.0f-%.0f Hz over %u seeds, shallowest drop x%.2f\n",
               distances[d], lo_top, hi_top, kSeeds, weakest_step);
    }
    // distance darkens the cascade as a whole: a far strike's staircase sits below a near one's.
    if (!(top[2] < top[1] && top[1] < top[0])) {
        printf("  FAIL: the cascade does not darken with distance (%.0f / %.0f / %.0f Hz)\n",
               top[0], top[1], top[2]);
        ok = false;
    }
    if (ok) printf("  PASS: %d steps descend at every distance, and the cascade darkens with it\n",
                   storm::kClapCount);
    return ok;
}

// The ear complaint: "the strikes sound a little synthy." One resonant band-pass on
// noise IS a filter ping, and the fix is a parallel broadband grit layer under it. The
// A/B is exact rather than approximate: both renders use the same seed AND the grit
// path draws the same noise sample the band-pass does, so the resonant content is
// bit-identical between them and the only difference in the buffer is the grit.
//
// Proxy: the share of the LAST hit's energy sitting above 4 kHz, taken through a
// two-pole high-pass. Band energy rather than zero crossings because the reference here
// has to be defensible: at distance 0 the bottom step's band-pass is centred near 780 Hz,
// so a two-pole resonance is already ~26 dB down at 4 kHz and falling at 6 dB/oct, and
// anything appreciable up there cannot have come from it. (Zero-crossing rate would be a
// poor proxy for the same signal: a 2-pole band-pass has a 1/f skirt, so its second
// spectral moment is set by Nyquist and not by its centre.) Hit 3 is measured because by
// 96 ms the three brighter steps are ~3.4 tail time constants down.
static bool test_clap_grit_broadens_the_spectrum() {
    printf("\n=== Test: the grit layer broadens a clap's spectrum (>4 kHz band energy) ===\n");
    constexpr int kN = 48000 / 4;                       // 250 ms, past the whole cascade
    // The fourth hit starts at kClapSpacingMs[3]; measure the 30 ms it owns outright.
    const int lo = (int)(storm::kClapSpacingMs[storm::kClapCount - 1] * 48.0f);
    const int hi = lo + (int)(0.030f * 48000.0f);
    auto render = [&](float mix) {
        storm::ClapGen g;
        g.Init(0xBEA7u, 48000.0f);
        g.debug_set_grit_mix(mix);
        g.trigger(/*intensity=*/1.0f, /*distance=*/0.0f);
        std::vector<float> l(kN, 0.f), r(kN, 0.f);
        for (int off = 0; off < kN; off += 512)
            g.Process(l.data() + off, r.data() + off, std::min(512, kN - off));
        return l;
    };
    // >4 kHz share of the window's energy, through two cascaded one-poles subtracted
    // from the signal. Also reports the window energy so the layer's weight is visible.
    auto share = [&](const std::vector<float>& x, double* energy, float* peak, bool* finite) {
        const float c = 1.0f - std::exp(-2.0f * 3.14159265f * 4000.0f / 48000.0f);
        float a = 0.f, b = 0.f;
        double hi_e = 0.0;
        *energy = 0.0; *peak = 0.f; *finite = true;
        for (int i = 0; i < kN; i++) {
            a += (x[i] - a) * c;
            b += (a - b) * c;
            const float hp = x[i] - b;
            if (!std::isfinite(x[i]) || !std::isfinite(hp)) *finite = false;
            if (i < lo || i >= hi) continue;
            hi_e     += (double)hp * hp;
            *energy  += (double)x[i] * x[i];
            *peak = std::max(*peak, std::fabs(x[i]));
        }
        return *energy > 0.0 ? (float)(hi_e / *energy) : 0.0f;
    };
    const std::vector<float> pure = render(0.0f);       // resonance only: the old sound
    const std::vector<float> ship = render(storm::kClapGritMix);
    const std::vector<float> full = render(1.0f);
    // What the crossfade EXCHANGES: mix*(grit - resonance), exact to the last bit because
    // the resonant content is identical across the three renders. If the grit were just
    // more of the same band this would be silence; that it is not, and that it is
    // brighter than the band it displaces, is the broadening.
    std::vector<float> swapped(kN);
    for (int i = 0; i < kN; i++) swapped[i] = ship[i] - pure[i];

    double e_pure = 0.0, e_ship = 0.0, e_full = 0.0, e_add = 0.0;
    float pk_pure = 0.f, pk_ship = 0.f, pk_full = 0.f, pk_add = 0.f;
    bool f1 = true, f2 = true, f3 = true, f4 = true;
    const float s_pure = share(pure,  &e_pure, &pk_pure, &f1);
    const float s_ship = share(ship,  &e_ship, &pk_ship, &f2);
    const float s_full = share(full,  &e_full, &pk_full, &f3);
    const float s_add  = share(swapped, &e_add, &pk_add, &f4);

    bool ok = true;
    if (!f1 || !f2 || !f3 || !f4) { printf("  FAIL: non-finite samples\n"); ok = false; }
    if (storm::kClapGritMix <= 0.0f) {
        printf("  FAIL: kClapGritMix is %.2f -- the static layer is dialled out\n",
               storm::kClapGritMix); ok = false;
    }
    if (pk_pure < 1e-3f || pk_ship < 1e-3f) {
        printf("  FAIL: the measured window is silent (%.4g / %.4g)\n", pk_pure, pk_ship);
        ok = false;
    }
    // The layer is actually in the mix, not a rounding error under the resonance.
    if (e_add < e_pure * 0.15) {
        printf("  FAIL: the crossfade exchanges %.1f%% of the resonance's energy\n",
               100.0 * e_add / (e_pure + 1e-30)); ok = false;
    }
    // And what it brings in is BROADER than what it takes out. The resonance's own
    // figure is inflated here by the three brighter steps still decaying through the
    // window, so this comparison is conservative in the direction that matters.
    if (s_add < s_pure * 1.5f) {
        printf("  FAIL: the exchanged layer puts %.4f above 4 kHz against the resonance's"
               " %.4f -- it is not broadening anything\n", s_add, s_pure); ok = false;
    }
    // kClapGritNorm's whole job: keep kClapGritMix a crossfade rather than a level
    // control. A full swap from resonance to grit must not move the window's loudness
    // more than a few dB in either direction.
    if (e_full < e_pure * 0.5 || e_full > e_pure * 2.0) {
        printf("  FAIL: mix 0 -> 1 moved the window energy x%.2f -- kClapGritNorm (%.2f)"
               " is not loudness-matching the two paths\n",
               e_full / (e_pure + 1e-30), storm::kClapGritNorm); ok = false;
    }
    // Monotone in the dial, which is what makes the proxy meaningful rather than a
    // one-point coincidence: more grit must mean more energy off the resonance.
    if (!(s_full > s_ship && s_ship > s_pure)) {
        printf("  FAIL: shares %.4f / %.4f / %.4f are not monotone in the mix\n",
               s_pure, s_ship, s_full); ok = false;
    }
    if (ok) printf("  PASS: >4 kHz share %.4f (mix 0) -> %.4f (shipped %.2f) -> %.4f (mix 1);"
                   " the exchanged layer is %.4f at %.0f%% of the resonance's energy,"
                   " full swap moves loudness x%.2f\n",
                   s_pure, s_ship, storm::kClapGritMix, s_full, s_add,
                   100.0 * e_add / (e_pure + 1e-30), e_full / (e_pure + 1e-30));
    return ok;
}

// "can we add echo on that DSP to make it some kind of lasting effect?" Driven on the
// echo alone with a unit impulse, so every number here is the tap table read straight
// back out: arrival times, gains, total return, and tail length. Distance is the dial,
// and the physical way round -- a far strike answers off the terrain, an overhead one
// does not, so distance 1 has to return MORE energy and hold it LONGER than distance 0.
static bool test_clap_echo_grows_and_lengthens_with_distance() {
    printf("\n=== Test: the strike echo scales and lengthens with distance ===\n");
    constexpr int kN = 48000 / 2;                       // 500 ms, past the longest tap
    auto render = [](float distance) {
        storm::ClapEcho e;
        e.Init(48000.0f);
        e.set_distance(distance);
        std::vector<float> l(kN, 0.f), r(kN, 0.f);
        l[0] = 1.0f; r[0] = 1.0f;                       // one unit impulse in
        e.Process(l.data(), r.data(), kN);
        return l;
    };
    auto measure = [](const std::vector<float>& x, double* energy, float* last_ms,
                      float* peak, bool* finite) {
        *energy = 0.0; *last_ms = 0.f; *peak = 0.f; *finite = true;
        for (size_t i = 1; i < x.size(); i++) {         // skip the dry impulse itself
            if (!std::isfinite(x[i])) *finite = false;
            *energy += (double)x[i] * x[i];
            *peak = std::max(*peak, std::fabs(x[i]));
        }
        // Tail measured against each render's OWN first reflection: how far down the tap
        // list the answer still carries, not how loud it started.
        for (size_t i = 1; i < x.size(); i++)
            if (std::fabs(x[i]) > *peak * 0.05f) *last_ms = (float)i * 1000.f / 48000.f;
    };
    const std::vector<float> near_ = render(0.0f), far_ = render(1.0f);
    double e_near = 0.0, e_far = 0.0;
    float last_near = 0.f, last_far = 0.f, pk_near = 0.f, pk_far = 0.f;
    bool fin_near = true, fin_far = true;
    measure(near_, &e_near, &last_near, &pk_near, &fin_near);
    measure(far_, &e_far, &last_far, &pk_far, &fin_far);

    bool ok = true;
    if (!fin_near || !fin_far) { printf("  FAIL: non-finite echo output\n"); ok = false; }
    // Arrivals: an impulse in means tap t comes out at exactly its authored millisecond.
    for (int t = 0; t < storm::kClapEchoTaps; t++) {
        const int at = (int)(storm::kClapEchoTapMs[t] * 0.001f * 48000.0f + 0.5f);
        if (std::fabs(far_[at]) < 1e-4f) {
            printf("  FAIL: no tap %d at its authored %.0f ms\n", t, storm::kClapEchoTapMs[t]);
            ok = false;
        }
    }
    // No feedback anywhere: nothing may come back after the longest tap.
    const float longest = storm::kClapEchoTapMs[storm::kClapEchoTaps - 1];
    if (last_far > longest + 1.0f) {
        printf("  FAIL: energy at %.0f ms, past the %.0f ms last tap -- the line is regenerating\n",
               last_far, longest); ok = false;
    }
    if (pk_far > 1.0f) { printf("  FAIL: a unit impulse came back at %.3f\n", pk_far); ok = false; }
    if (e_far < e_near * 3.0f) {
        printf("  FAIL: distance 1 returned %.4g against %.4g overhead -- distance buys no echo\n",
               e_far, e_near); ok = false;
    }
    if (last_far < last_near * 1.5f) {
        printf("  FAIL: distance 1 rang to %.0f ms, overhead to %.0f -- no longer, only louder\n",
               last_far, last_near); ok = false;
    }
    if (ok) printf("  PASS: energy x%.1f and tail %.0f -> %.0f ms from overhead to distant,"
                   " peak %.3f, nothing past %.0f ms\n",
                   e_far / (e_near + 1e-12), last_near, last_far, pk_far, longest);
    return ok;
}

// The same effect in place, through the whole voice: the terrain has to answer a strike,
// and answer a distant one longer. Exact A/B: the same seed rendered with and without the
// echo, subtracted, is the reflections alone, sample for sample. The claps' tails now
// overlap the reflections by design (the bottom step rings 130 ms), so nothing here
// assumes a window the dry cascade has left; the difference is measured directly. A
// four-pole 1.5 kHz high-pass keeps the roll and its 165 Hz body (~77 dB down) out of it.
static bool test_strike_echo_outlasts_the_dry_cascade() {
    printf("\n=== Test: the terrain answers a strike, and a distant one longer ===\n");
    constexpr int kN = (int)(0.9f * 48000.0f);
    auto render = [](float distance, bool echo) {
        storm::StormVoice v; v.Init(0xBEA7u, 48000.0f);
        v.debug_set_echo_enabled(echo);
        v.set_bed(0.f, 0.f, 0.f, 0.f);
        v.trigger_strike(1.0f, distance);
        std::vector<float> l(kN, 0.f), r(kN, 0.f);
        for (int off = 0; off < kN; off += 512)
            v.Process(l.data() + off, r.data() + off, std::min(512, kN - off));
        return l;
    };
    auto highpass = [](const std::vector<float>& x) { return highpass4(x, 1500.0f); };
    // Last sample above `floor_`, in ms; -1 if never.
    auto last_above = [](const std::vector<float>& x, float floor_) {
        for (int i = (int)x.size() - 1; i >= 0; i--)
            if (std::fabs(x[i]) > floor_) return (float)i * 1000.f / 48000.f;
        return -1.f;
    };
    auto probe = [&](float distance, float* dry_pk, float* dry_end, float* echo_end,
                     double* echo_e, bool* finite) {
        const std::vector<float> with = highpass(render(distance, true));
        const std::vector<float> dry  = highpass(render(distance, false));
        std::vector<float> echo(kN);
        *dry_pk = 0.f; *echo_e = 0.0; *finite = true;
        for (int i = 0; i < kN; i++) {
            echo[i] = with[i] - dry[i];
            if (!std::isfinite(with[i]) || !std::isfinite(dry[i])) *finite = false;
            *dry_pk = std::max(*dry_pk, std::fabs(dry[i]));
            *echo_e += (double)echo[i] * echo[i];
        }
        // -40 dB of the dry cascade's own peak: where each stops being audible.
        *dry_end  = last_above(dry,  *dry_pk * 0.01f);
        *echo_end = last_above(echo, *dry_pk * 0.01f);
    };
    float dry_near = 0.f, dend_near = 0.f, eend_near = 0.f;
    float dry_far = 0.f, dend_far = 0.f, eend_far = 0.f;
    double e_near = 0.0, e_far = 0.0;
    bool f_near = true, f_far = true;
    // 0.6 rather than 1.0: past ~0.9 the cascade itself is gated off (kClapMinBurst),
    // so there would be no crack left to reflect. The echo's own distance response over
    // the full 0..1 range is pinned directly in the test above.
    probe(0.0f, &dry_near, &dend_near, &eend_near, &e_near, &f_near);
    probe(0.6f, &dry_far, &dend_far, &eend_far, &e_far, &f_far);

    bool ok = f_near && f_far;
    if (!ok) printf("  FAIL: non-finite output\n");
    if (dry_near < 0.05f || dry_far < 0.01f) {
        printf("  FAIL: no dry cascade to reflect (%.4f / %.4f)\n", dry_near, dry_far); ok = false;
    }
    if (e_far <= 0.0 || eend_far < 0.0f) {
        printf("  FAIL: the echo contributed nothing audible at distance 0.6\n"); ok = false;
    }
    if (eend_far <= eend_near) {
        printf("  FAIL: reflections last to %.0f ms at distance 0.6 and %.0f ms overhead --"
               " distance is not lengthening the answer\n", eend_far, eend_near); ok = false;
    }
    // Normalised by each render's own cascade energy, so the tilt on the dry claps
    // cannot masquerade as an echo change.
    const double n_near = e_near / ((double)dry_near * dry_near + 1e-30);
    const double n_far  = e_far  / ((double)dry_far  * dry_far  + 1e-30);
    if (n_far < n_near * 2.0) {
        printf("  FAIL: distant strike reflects %.4g of its cascade, overhead %.4g --"
               " distance is not driving the echo\n", n_far, n_near); ok = false;
    }
    if (ok) printf("  PASS: dry cascade audible to %.0f / %.0f ms (overhead / 0.6), reflections"
                   " to %.0f / %.0f ms; echo energy x%.1f with distance\n",
                   dend_near, dend_far, eend_near, eend_far, n_far / (n_near + 1e-30));
    return ok;
}

// Each step of the staircase is a clap, not a tick: the tail table lengthens down the
// cascade and the render actually rings that long. Measured on the whole cascade rather
// than per hit, since the hits now overlap by design: the last sample within 30 dB of
// the peak has to land past the bottom step's onset plus two of its own tails, which a
// flat 28 ms tail (the old tick) cannot reach.
static bool test_clap_tails_lengthen_down_the_staircase() {
    printf("\n=== Test: clap tails lengthen down the staircase and ring that long ===\n");
    bool ok = true;
    for (int k = 1; k < storm::kClapCount; k++) {
        if (!(storm::kClapTailMs[k] > storm::kClapTailMs[k - 1])) {
            printf("  FAIL: kClapTailMs[%d]=%.0f is not longer than kClapTailMs[%d]=%.0f\n",
                   k, storm::kClapTailMs[k], k - 1, storm::kClapTailMs[k - 1]);
            ok = false;
        }
    }
    storm::ClapGen g; g.Init(0xBEA7u, 48000.0f);
    g.trigger(1.0f, 0.0f);
    constexpr int kN = 48000;
    std::vector<float> l(kN, 0.f), r(kN, 0.f);
    for (int off = 0; off < kN; off += 512) g.Process(l.data() + off, r.data() + off, 512);
    float pk = 0.f;
    for (float s : l) pk = std::max(pk, std::fabs(s));
    float last_ms = 0.f;
    for (int i = kN - 1; i >= 0; i--)
        if (std::fabs(l[i]) > pk * 0.0316f) { last_ms = i * 1000.f / 48000.f; break; }
    // 1.5 tails past the bottom onset: that step opens some 12 dB under the cascade's
    // peak, so 30 dB down is about 2.1 of its own time constants from there. A flat
    // 28 ms tail, the old tick, reached 136 ms against a bar of 138.
    const int last = storm::kClapCount - 1;
    const float need_ms = storm::kClapSpacingMs[last] + 1.5f * storm::kClapTailMs[last];
    if (last_ms < need_ms) {
        printf("  FAIL: the cascade is within 30 dB of its peak only to %.0f ms; the bottom step"
               " at %.0f ms with a %.0f ms tail should carry it past %.0f\n",
               last_ms, storm::kClapSpacingMs[last], storm::kClapTailMs[last], need_ms);
        ok = false;
    }
    if (ok) printf("  PASS: tails %.0f..%.0f ms, cascade rings to %.0f ms (needs %.0f)\n",
                   storm::kClapTailMs[0], storm::kClapTailMs[last], last_ms, need_ms);
    return ok;
}

// The whip crack: the first hit opens with a millisecond of plain broadband noise ahead
// of the staircase, and it is the first thing distance takes away. Exact A/B on one seed
// with the snap scaled to zero: the difference IS the snap, sample for sample, so its
// timing and its spectrum are read off directly rather than guessed from a window.
static bool test_clap_snap_leads_the_crack() {
    printf("\n=== Test: the first hit opens on a broadband snap that distance removes first ===\n");
    constexpr int kN = 4800;                            // 100 ms: the snap and the first two hits
    auto render = [](float distance, float snap_scale) {
        storm::ClapGen g; g.Init(0xBEA7u, 48000.0f);
        g.debug_set_snap_scale(snap_scale);
        g.trigger(1.0f, distance);
        std::vector<float> l(kN, 0.f), r(kN, 0.f);
        for (int off = 0; off < kN; off += 512) g.Process(l.data() + off, r.data() + off, 512);
        return l;
    };
    const std::vector<float> with = render(0.0f, 1.0f), without = render(0.0f, 0.0f);
    std::vector<float> snap(kN);
    for (int i = 0; i < kN; i++) snap[i] = with[i] - without[i];
    // Where the snap's energy lands: nearly all of it inside a few snap time constants.
    const int burst = (int)(storm::kClapSnapMs * 4.0f * 48.0f);
    double e_all = 0.0, e_burst = 0.0;
    for (int i = 0; i < kN; i++) { e_all += (double)snap[i] * snap[i]; if (i < burst) e_burst += (double)snap[i] * snap[i]; }
    // Its spectrum against the clap it sits on: >8 kHz share through a four-pole
    // high-pass, over the same burst window, snap alone vs staircase alone.
    auto hi_share = [&](const std::vector<float>& x) {
        const std::vector<float> hp = highpass4(x, 8000.0f);
        double hi_e = 0.0, e = 0.0;
        for (int i = 0; i < burst; i++) { hi_e += (double)hp[i] * hp[i]; e += (double)x[i] * x[i]; }
        return e > 0.0 ? (float)(hi_e / e) : 0.0f;
    };
    const float s_snap = hi_share(snap), s_clap = hi_share(without);
    float pk_snap = 0.f, pk_clap = 0.f;
    for (int i = 0; i < kN; i++) { pk_snap = std::max(pk_snap, std::fabs(snap[i])); pk_clap = std::max(pk_clap, std::fabs(without[i])); }

    bool ok = true;
    if (e_all <= 0.0) { printf("  FAIL: the snap contributes nothing\n"); ok = false; }
    else if (e_burst < e_all * 0.9) {
        printf("  FAIL: only %.0f%% of the snap's energy sits in its first %.1f ms\n",
               100.0 * e_burst / e_all, storm::kClapSnapMs * 4.0f);
        ok = false;
    }
    if (s_snap < s_clap * 1.5f) {
        printf("  FAIL: >8 kHz share %.3f on the snap against %.3f on the clap under it --"
               " not broadband\n", s_snap, s_clap);
        ok = false;
    }
    // Loudest instant of the strike, but not by a mile: within +6 dB of the cascade.
    if (pk_snap < pk_clap * 0.7f || pk_snap > pk_clap * 2.0f) {
        printf("  FAIL: snap peaks at %.3f against the cascade's %.3f -- it should sit a"
               " decibel or two over the claps, not under them or far above\n", pk_snap, pk_clap);
        ok = false;
    }
    storm::ClapGen a; a.Init(0xBEA7u, 48000.0f); a.trigger(1.0f, 0.0f);
    storm::ClapGen b; b.Init(0xBEA7u, 48000.0f); b.trigger(1.0f, 0.6f);
    // The snap has to fall faster than the hit it rides on -- one extra proximity factor
    // beyond the burst's square.
    const float snap_near = a.debug_hit_snap(storm::kClapSnapHit);
    const float snap_far  = b.debug_hit_snap(storm::kClapSnapHit);
    const float gain_near = a.debug_hit_gain(storm::kClapSnapHit);
    const float gain_far  = b.debug_hit_gain(storm::kClapSnapHit);
    if (snap_near <= 0.0f) { printf("  FAIL: no snap on hit %d\n", storm::kClapSnapHit); ok = false; }
    else if (!(snap_far / snap_near < gain_far / gain_near)) {
        printf("  FAIL: distance 0.6 keeps %.3f of the snap but %.3f of the hit -- the snap"
               " is not the first thing to go\n", snap_far / snap_near, gain_far / gain_near);
        ok = false;
    }
    for (int k = 0; k < storm::kClapCount; k++) {
        if (k != storm::kClapSnapHit && a.debug_hit_snap(k) != 0.0f) {
            printf("  FAIL: hit %d carries a snap; only hit %d should\n", k, storm::kClapSnapHit);
            ok = false;
        }
    }
    if (ok) printf("  PASS: %.0f%% of the snap inside %.1f ms, >8 kHz share %.3f vs %.3f, peak"
                   " %.2f vs %.2f; distance 0.6 keeps %.2f of the snap against %.2f of the hit\n",
                   100.0 * e_burst / e_all, storm::kClapSnapMs * 4.0f, s_snap, s_clap,
                   pk_snap, pk_clap, snap_far / snap_near, gain_far / gain_near);
    return ok;
}

// The tearing texture: a hit's amplitude has to rip, not glide. Proxy is the roughness of
// its 1 ms RMS envelope with the decay trend divided out. Band-passed noise on its own
// fluctuates by roughly 1/sqrt(2N) per window (~0.1 at 48 samples); the crackle drives it
// well past that. Measured on the bottom step, where nothing else is still sounding.
static bool test_clap_crackle_tears_the_envelope() {
    printf("\n=== Test: the crackle makes a clap's envelope ragged, not smooth ===\n");
    storm::ClapGen g; g.Init(0xBEA7u, 48000.0f);
    g.trigger(1.0f, 0.0f);
    constexpr int kN = 48000 / 2;
    std::vector<float> l(kN, 0.f), r(kN, 0.f);
    for (int off = 0; off < kN; off += 512) g.Process(l.data() + off, r.data() + off, 512);
    const int last = storm::kClapCount - 1;
    const int win = 48;                                                 // 1 ms
    const int lo = (int)((storm::kClapSpacingMs[last] + 10.0f) * 48.0f);
    const int hi = lo + (int)(storm::kClapTailMs[last] * 48.0f);
    std::vector<float> rms;
    for (int i = lo; i + win <= hi; i += win) {
        double sq = 0.0;
        for (int j = 0; j < win; j++) sq += (double)l[i + j] * l[i + j];
        rms.push_back((float)std::sqrt(sq / win));
    }
    // Divide each window by the mean of its 9-window neighbourhood: what is left is the
    // fast texture, with the exponential decay and the slow wander taken out.
    double sum = 0.0, sq = 0.0; int n = 0;
    for (int i = 4; i + 4 < (int)rms.size(); i++) {
        float local = 0.f;
        for (int j = -4; j <= 4; j++) local += rms[i + j];
        local /= 9.0f;
        if (local <= 1e-6f) continue;
        const double v = rms[i] / local;
        sum += v; sq += v * v; n++;
    }
    const double mean = n ? sum / n : 0.0;
    const double cv = n ? std::sqrt(std::max(0.0, sq / n - mean * mean)) / (mean + 1e-30) : 0.0;
    bool ok = n > 20;
    if (!ok) printf("  FAIL: only %d usable windows in the bottom step\n", n);
    if (cv < 0.25) {
        printf("  FAIL: envelope roughness %.3f -- the bottom clap decays smoothly, which is"
               " a synth clap, not a torn one\n", cv);
        ok = false;
    }
    if (ok) printf("  PASS: 1 ms envelope roughness %.3f over %d windows (smooth noise ~0.1)\n", cv, n);
    return ok;
}

// The roll has to carry on ordinary speakers, and it has to darken as it travels. The
// old sub-only roll put under 1% of its energy above 100 Hz, which a laptop cannot
// reproduce at all. The body path fixes the first; running it on the tail envelope to
// a higher power fixes the second, and both are measured on the render.
static bool test_rumble_body_carries_and_darkens() {
    printf("\n=== Test: the roll carries a 100-400 Hz body that fades before the sub ===\n");
    storm::RumbleGen rum; rum.Init(0xBEA7u, 48000.0f);
    rum.set_bed(0.f, 0.2f);
    rum.trigger_tail(1.0f, 0.2f);
    constexpr int kN = 48000 * 4;
    std::vector<float> l(kN, 0.f), r(kN, 0.f);
    for (int off = 0; off < kN; off += 512) rum.Process(l.data() + off, r.data() + off, 512);
    // Share of the roll's energy above 130 Hz over [lo, hi) s, through a four-pole
    // Butterworth high-pass (two Svf stages). The sub path's ~90 Hz resonance leaves a
    // fixed fraction up there whatever the body does, so a share that FALLS over the
    // tail can only be the body fading; a share that never rises above it is no body.
    auto share_above = [&](float lo_s, float hi_s) {
        stmlib::Svf h1, h2; h1.Init(); h2.Init();
        h1.set_f_q<stmlib::FREQUENCY_EXACT>(130.0f / 48000.0f, 0.707f);
        h2.set_f_q<stmlib::FREQUENCY_EXACT>(130.0f / 48000.0f, 0.707f);
        double band = 0.0, all = 0.0;
        const int lo = (int)(lo_s * 48000.f), hi = (int)(hi_s * 48000.f);
        for (int i = 0; i < hi; i++) {
            const float v = h2.Process<stmlib::FILTER_MODE_HIGH_PASS>(
                                h1.Process<stmlib::FILTER_MODE_HIGH_PASS>(l[i]));
            if (i < lo) continue;
            band += (double)v * v; all += (double)l[i] * l[i];
        }
        return all > 0.0 ? (float)(band / all) : 0.0f;
    };
    const float early = share_above(0.10f, 0.50f);
    const float late  = share_above(2.50f, 3.50f);
    bool ok = true;
    if (early < 0.30f) {
        printf("  FAIL: only %.1f%% of the roll's first half-second sits above 130 Hz --"
               " inaudible on small speakers\n", early * 100.f);
        ok = false;
    }
    if (!(late < early * 0.7f)) {
        printf("  FAIL: share above 130 Hz %.3f early, %.3f late -- the roll does not darken"
               " as it travels\n", early, late);
        ok = false;
    }
    if (ok) printf("  PASS: above-130 Hz share %.1f%% at 0.1-0.5 s, %.1f%% at 2.5-3.5 s\n",
                   early * 100.f, late * 100.f);
    return ok;
}

// Rolling thunder re-swells. The tail's envelope is read directly: it must fall
// monotonically except at exactly kRumblePeals moments where it lifts, each inside its
// authored window stretched by distance, and the render has to swell audibly at the
// second one, after the first arrival has clearly receded.
static bool test_rumble_peals_reswell_the_roll() {
    printf("\n=== Test: the tail re-swells on delayed peals ===\n");
    const float distance = 0.3f;
    storm::RumbleGen rum; rum.Init(0xBEA7u, 48000.0f);
    rum.set_bed(0.f, distance);
    rum.trigger_tail(1.0f, distance);
    constexpr int kBlock = 480;                                          // 10 ms
    constexpr int kBlocks = 600;                                         // 6 s
    std::vector<float> l(kBlock), r(kBlock), rms(kBlocks);
    std::vector<float> lifts_ms;
    float prev = rum.tail_level();
    for (int b = 0; b < kBlocks; b++) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        rum.Process(l.data(), r.data(), kBlock);
        double sq = 0.0;
        for (int i = 0; i < kBlock; i++) sq += (double)l[i] * l[i];
        rms[b] = (float)std::sqrt(sq / kBlock);
        const float now = rum.tail_level();
        if (now > prev * 1.05f) lifts_ms.push_back((b + 1) * 10.0f);
        prev = now;
    }
    bool ok = true;
    if ((int)lifts_ms.size() != storm::kRumblePeals) {
        printf("  FAIL: %zu lifts in the tail envelope, expected %d peals\n",
               lifts_ms.size(), storm::kRumblePeals);
        ok = false;
    }
    const float stretch = 1.0f + storm::kRumblePealDistStretch * distance;
    for (size_t k = 0; k < lifts_ms.size() && (int)k < storm::kRumblePeals; k++) {
        const float lo = storm::kRumblePealMinS[k] * stretch * 1000.f;
        const float hi = storm::kRumblePealMaxS[k] * stretch * 1000.f + 10.f;
        if (lifts_ms[k] < lo || lifts_ms[k] > hi) {
            printf("  FAIL: peal %zu at %.0f ms, outside its %.0f-%.0f ms window\n",
                   k, lifts_ms[k], lo, hi);
            ok = false;
        }
    }
    // Audible: 100 ms RMS at the last peal's arrival against the 100 ms just before it.
    if (lifts_ms.size() >= 2) {
        const int at = (int)(lifts_ms.back() / 10.f);
        auto mean = [&](int a, int b) {
            double s = 0.0; int n = 0;
            for (int i = std::max(0, a); i < std::min(kBlocks, b); i++) { s += rms[i]; n++; }
            return n ? (float)(s / n) : 0.f;
        };
        const float before = mean(at - 12, at - 2), after = mean(at + 1, at + 11);
        if (after < before * 1.41f) {
            printf("  FAIL: the last peal lifts the roll only x%.2f (%.4f -> %.4f)\n",
                   after / (before + 1e-9f), before, after);
            ok = false;
        }
        if (ok) printf("  PASS: peals at %.0f and %.0f ms; the last swells the roll x%.2f\n",
                       lifts_ms[0], lifts_ms[1], after / (before + 1e-9f));
    }
    return ok;
}

static bool test_strike_far_distance_drops_claps() {
    printf("\n=== Test: distance ~1 → rumble-only strike (no bright claps) ===\n");
    storm::StormVoice v; v.Init(3u, 48000.0f);
    v.trigger_strike(1.0f, 0.95f);
    std::vector<float> l(9600, 0.f), r(9600, 0.f);          // first 200 ms
    for (int off = 0; off < 9600; off += 512) v.Process(l.data() + off, r.data() + off, 512);
    int zc = 0; float pk = 0.f;
    for (int i = 1; i < 9600; i++) {
        if ((l[i-1] >= 0.f) != (l[i] >= 0.f)) zc++;
        pk = std::max(pk, std::fabs(l[i]));
    }
    printf("  peak=%.3f zero-crossings(200ms)=%d (bright claps would exceed ~400)\n", pk, zc);
    return pk < 0.6f && zc < 400;
}

static bool test_storm_voice_bounds_all_modes() {
    printf("\n=== Test: full storm (bed max + strike) stays within soft-limit bounds ===\n");
    storm::StormVoice v; v.Init(0xBEA7u, 48000.0f);
    v.set_bed(1.0f, 1.0f, 1.0f, 0.2f);
    bool ok = true; float pk = 0.f;
    std::vector<float> l(512), r(512);
    for (int b = 0; b < 800; b++) {                          // ~8.5 s, strike mid-way
        if (b == 200) v.trigger_strike(1.0f, 0.1f);
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        v.Process(l.data(), r.data(), 512);
        for (int i = 0; i < 512; i++) {
            if (!std::isfinite(l[i]) || !std::isfinite(r[i])) ok = false;
            pk = std::max({pk, std::fabs(l[i]), std::fabs(r[i])});
        }
    }
    printf("  peak=%.3f\n", pk);
    return ok && pk > 0.2f && pk < 1.2f;
}

static bool test_strike_active_lifecycle() {
    printf("\n=== Test: strike_active true through claps+tail, then self-clears ===\n");
    storm::StormVoice v; v.Init(9u, 48000.0f);
    if (v.strike_active()) return false;
    v.trigger_strike(0.8f, 0.3f);
    if (!v.strike_active()) return false;
    std::vector<float> l(512), r(512);
    int blocks = 0;
    while (v.strike_active() && blocks < 48000 * 12 / 512) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        v.Process(l.data(), r.data(), 512); blocks++;
    }
    float seconds = blocks * 512.f / 48000.f;
    printf("  strike lifetime %.2f s\n", seconds);
    return !v.strike_active() && seconds > 1.0f && seconds < 10.0f;
}

// ── Delayed strikes ─────────────────────────────────────────────────────────
// StrikeEffect.delayMs crosses the wire as the strike row's p2 and parks a strike on a
// sample countdown. Every probe below renders over a DRY bed, so silence-to-crack is
// unambiguous and no bed energy can be mistaken for an onset.

// Renders `seconds` of the left channel in 512-sample blocks, the block size the pulsar
// unit itself uses, so the delay is measured against realistic chunking.
static std::vector<float> render_storm(storm::StormVoice& v, float seconds) {
    const int n = (int)(seconds * 48000.0f);
    std::vector<float> l(n, 0.f), r(n, 0.f);
    for (int off = 0; off < n; off += 512) {
        const int m = std::min(512, n - off);
        v.Process(l.data() + off, r.data() + off, m);
    }
    return l;
}

// First sample above `floor_`, in milliseconds. Over a dry bed the buffer is exactly zero
// until the claps fire, so this is the strike's true onset and not an envelope estimate.
static float first_onset_ms(const std::vector<float>& x, float floor_) {
    for (size_t i = 0; i < x.size(); i++)
        if (std::fabs(x[i]) > floor_) return (float)i * 1000.f / 48000.f;
    return -1.f;
}

// Cascade onsets, in milliseconds. The top two steps of the staircase live near 2-3.5 kHz
// with the shortest tails, the lower steps ring on for 100 ms or more and the roll's body
// sits at 165 Hz, so a four-pole 2 kHz high-pass leaves essentially the bright cracks.
// Runs of loud 5 ms windows separated by >= 100 ms of quiet count as ONE cascade each —
// the whole four-hit burst is one crack, not four.
static std::vector<float> cascade_onsets_ms(const std::vector<float>& x) {
    const std::vector<float> bright = highpass4(x, 2000.0f);
    const int win = (int)(0.005f * 48000.0f);
    const int gap_windows = (int)(0.100f * 48000.0f / win);
    std::vector<float> onsets;
    float peak = 0.f;
    int w = 0, quiet = gap_windows;
    for (size_t i = 0; i < bright.size(); i++) {
        peak = std::max(peak, std::fabs(bright[i]));
        if (++w < win) continue;
        // 0.12: the long-tailed bottom steps leave a broadband floor near 0.05 for some
        // 400 ms after a cascade (that is the tearing fading out), while a cascade's own
        // onset lands above 0.3 even for the quieter second strike of an authored pair.
        if (peak > 0.12f) {
            if (quiet >= gap_windows) onsets.push_back((float)(i + 1 - win) * 1000.f / 48000.f);
            quiet = 0;
        } else {
            quiet++;
        }
        peak = 0.f; w = 0;
    }
    return onsets;
}

static bool test_delayed_strike_sounds_at_the_authored_offset() {
    printf("\n=== Test: a delayed strike sounds at its authored millisecond ===\n");
    bool ok = true;
    for (float delay_ms : {40.0f, 250.0f, 700.0f}) {
        storm::StormVoice v; v.Init(0xBEA7u, 48000.0f);
        v.set_bed(0.f, 0.f, 0.f, 0.f);
        v.trigger_strike(0.9f, 0.1f, delay_ms);
        const std::vector<float> l = render_storm(v, 2.0f);
        const float at = first_onset_ms(l, 0.02f);
        const float err = at < 0.f ? 1e9f : std::fabs(at - delay_ms);
        printf("  authored %.0f ms -> onset %.2f ms (err %.2f ms)\n", delay_ms, at, err);
        // Process() cuts its chunk at the fire point, so the only error left is the
        // half-sample rounding of the delay itself. A whole millisecond is 48x that.
        if (err > 1.0f) { printf("  FAIL: onset missed its authored offset\n"); ok = false; }
    }
    return ok;
}

static bool test_authored_pair_needs_a_gap_to_be_two_cracks() {
    printf("\n=== Test: two strikes read as two cracks only with a gap ===\n");
    // The same authored pair rendered twice. Both strikes in one block re-trigger the clap
    // generator, so the second truncates the first and only one cascade sounds; that
    // collision is exactly what the delay exists to avoid.
    auto render_pair = [](float gap_ms) {
        storm::StormVoice v; v.Init(0xBEA7u, 48000.0f);
        v.set_bed(0.f, 0.f, 0.f, 0.f);
        v.trigger_strike(0.9f, 0.1f);
        v.trigger_strike(0.75f, 0.40f, gap_ms);
        return render_storm(v, 2.0f);
    };
    const std::vector<float> together = cascade_onsets_ms(render_pair(0.0f));
    const std::vector<float> spaced   = cascade_onsets_ms(render_pair(420.0f));   // RustBelt's gap
    printf("  no gap: %zu cascade(s)", together.size());
    for (float o : together) printf(" %.0fms", o);
    printf("\n  420 ms gap: %zu cascade(s)", spaced.size());
    for (float o : spaced) printf(" %.0fms", o);
    printf("\n");

    bool ok = true;
    if (together.size() != 1) {
        printf("  FAIL: an ungapped pair should collide into one cascade\n"); ok = false;
    }
    if (spaced.size() != 2) {
        printf("  FAIL: a gapped pair should sound as two cascades\n"); ok = false;
    }
    if (spaced.size() == 2 && std::fabs(spaced[1] - 420.f) > 10.f) {
        printf("  FAIL: second cascade at %.0f ms, expected ~420\n", spaced[1]); ok = false;
    }
    return ok;
}

static bool test_zero_delay_is_bit_identical_to_the_immediate_path() {
    printf("\n=== Test: delay 0 renders bit-identically to the two-argument trigger ===\n");
    // The new parameter must not perturb a single sample of the existing path. Rendered
    // over a live bed so the chunk-splitting in Process() is exercised, and from one lambda
    // so both buffers come out of the same call sites (no per-site FMA differences).
    auto render = [](bool pass_explicit_zero) {
        storm::StormVoice v; v.Init(0xBEA7u, 48000.0f);
        v.set_bed(0.6f, 0.8f, 0.4f, 0.2f);
        if (pass_explicit_zero) v.trigger_strike(0.9f, 0.1f, 0.0f);
        else                    v.trigger_strike(0.9f, 0.1f);
        return render_storm(v, 1.5f);
    };
    const std::vector<float> implicit_ = render(false), explicit_ = render(true);
    const bool same = implicit_ == explicit_;
    printf("  %s\n", same ? "identical, sample for sample" : "FAIL: buffers differ");
    return same;
}

static bool test_a_queued_strike_holds_the_voice() {
    printf("\n=== Test: a queued strike keeps strike_active() true for its whole wait ===\n");
    storm::StormVoice v; v.Init(9u, 48000.0f);
    v.set_bed(0.f, 0.f, 0.f, 0.f);
    bool ok = true;
    if (v.strike_active()) { printf("  FAIL: active before any trigger\n"); return false; }

    v.trigger_strike(0.8f, 0.3f, 800.0f);
    if (!v.strike_active() || !v.strike_queued()) {
        printf("  FAIL: a queued strike is not reported as active\n"); ok = false;
    }
    // The guards on the anomaly and per-bar weather paths read strike_active(); a single
    // false block during the wait is a dropped strike.
    std::vector<float> l(512), r(512);
    float pre_peak = 0.f;
    for (int b = 0; b < (int)(0.7f * 48000 / 512); b++) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        v.Process(l.data(), r.data(), 512);
        for (int i = 0; i < 512; i++) pre_peak = std::max(pre_peak, std::fabs(l[i]));
        if (!v.strike_active()) {
            printf("  FAIL: released the voice %.2f s into an 0.8 s wait\n", b * 512.f / 48000.f);
            ok = false; break;
        }
    }
    if (pre_peak > 1e-6f) { printf("  FAIL: %.3g of sound before the strike fired\n", pre_peak); ok = false; }

    // It fires, drains its slot, and then releases the voice the same way an immediate
    // strike does — a queue that never empties would block every later strike.
    (void)render_storm(v, 0.5f);
    if (v.strike_queued()) { printf("  FAIL: the slot never drained\n"); ok = false; }
    int blocks = 0;
    while (v.strike_active() && blocks < 48000 * 12 / 512) {
        std::fill(l.begin(), l.end(), 0.f); std::fill(r.begin(), r.end(), 0.f);
        v.Process(l.data(), r.data(), 512); blocks++;
    }
    if (v.strike_active()) { printf("  FAIL: never released after firing\n"); ok = false; }
    if (ok) printf("  PASS: held silent through the wait, fired, drained, released\n");
    return ok;
}

// ── Pulsar-unit integration ────────────────────────────────────────────────
// Weather reaches C++ only as pulsar_section_data slots 21-25, so these write the
// wire, not the struct. Every track is muted, which makes the pulsar output the
// storm's output: any energy measured below came from the weather bed.

// rain_level defaults to 0 to mirror the wire's all-zero "no weather" encoding, NOT
// to SectionWeather's 1f authoring default: a default-constructed instance here has
// to be the dry row test_zero_weather_leaves_the_mix_bit_quiet asserts on. Every wet
// fixture below sets it explicitly.
struct StormTestWeather {
    float rain = 0.0f, rumble = 0.0f, strike_chance = 0.0f, distance = 0.0f,
          rain_level = 0.0f;
};

// Two-section arrangement whose only variables are the two weather beds and the
// length of the 0 -> 1 pre-roll. Section 0 is the intro so the opening section is
// deterministic, and both sections have a fixed bar count.
static void push_storm_weather_arrangement(OrpheusEngine* engine,
                                           const StormTestWeather& w0,
                                           const StormTestWeather& w1,
                                           int trans_bars, int bars_per_section) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kStride = kSectionDataFields;   // 26 — slots 21-25 are the weather
    std::vector<float> sd(kMaxSections * kStride, 0.0f);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kStride;
        sd[b + 5] = sd[b + 6] = sd[b + 7] = sd[b + 8] = -1.0f;    // no macro overrides
        sd[b + 18] = sd[b + 19] = sd[b + 20] = -1.0f;             // no comping overrides
    }
    const StormTestWeather* w[2] = { &w0, &w1 };
    for (int s = 0; s < 2; s++) {
        const int b = s * kStride;
        sd[b + 0] = static_cast<float>(bars_per_section);   // bars_min == bars_max
        sd[b + 1] = static_cast<float>(bars_per_section);
        sd[b + 2] = 1.0f;                                   // bar_step
        sd[b + 3] = 0.8f;                                   // recency_decay
        sd[b + 4] = 1.0f;                                   // one outgoing edge
        sd[b + 21] = w[s]->rain;
        sd[b + 22] = w[s]->rumble;
        sd[b + 23] = w[s]->strike_chance;
        sd[b + 24] = w[s]->distance;
        sd[b + 25] = w[s]->rain_level;
    }
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);

    // 0 -> 1 carries the pre-roll under test; 1 -> 0 is a hard cut.
    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[0] = 1.0f; tr[1] = 1.0f; tr[2] = static_cast<float>(trans_bars);
    const int s1 = kMaxSectionTransitions * 3;
    tr[s1 + 0] = 0.0f; tr[s1 + 1] = 1.0f; tr[s1 + 2] = 0.0f;
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// Playing, unity mix, every track silent, both RNGs pinned.
static OrpheusEngine* make_muted_storm_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    solo_track(engine, -1);                 // no track index matches: all volumes 0
    pin_pulsar_rngs(engine);                // after the fixture, which stores seed 0
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    return engine;
}

static GraphUnit make_storm_pulsar_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

static bool test_weather_bed_is_audible_on_a_muted_vibe() {
    printf("\n=== Test: a weather section sounds on an all-muted vibe ===\n");
    OrpheusEngine* engine = make_muted_storm_engine();
    GraphUnit unit = make_storm_pulsar_unit();
    StormTestWeather wet; wet.rain = 0.8f; wet.rumble = 0.55f; wet.distance = 0.4f;
    wet.rain_level = 1.0f;
    push_storm_weather_arrangement(engine, wet, wet, /*trans_bars=*/0, /*bars=*/8);
    trigger_vibe_load(engine);

    double sum_sq = 0.0; int n = 0;
    float min_rms = 1e9f, max_rms = 0.0f;
    for (int b = 0; b < 400; b++) {                 // ~4.3 s
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (b < 60) continue;                       // past the bed slew
        float rms = compute_rms(engine->pulsar_out_l, 512);
        sum_sq += static_cast<double>(rms) * rms; n++;
        min_rms = std::fmin(min_rms, rms);
        max_rms = std::fmax(max_rms, rms);
    }
    float mean_rms = static_cast<float>(std::sqrt(sum_sq / n));
    // Sustained, not a one-shot: the quietest block in the window must still carry
    // the bed, and the whole window must sit well above the -60 dB mute point.
    bool ok = mean_rms > 0.01f && min_rms > 0.002f && max_rms < 1.0f;
    printf("  mean=%.4f min=%.4f max=%.4f -- %s\n", mean_rms, min_rms, max_rms,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The storm is a mix citizen: it taps the same send buses the tracks accumulate into,
// at a fixed reverb 0.45 / delay 0.15 — and the reverb tap is darkened by a one-pole on
// the way, the same treatment every per-track send already got and the storm alone did
// not. This used to assert reverb == delay * 3 per sample; that ratio is still exactly
// what the two constants say, but it now holds through the filter rather than sample for
// sample, so the check reconstructs it instead: recover the shared post-void signal from
// the (undarkened) delay tap, re-run the one-pole, and the result must be the reverb tap.
// One assertion pinning both send constants AND the darkening coefficient — and the delay
// tap staying undarkened is what shows the dry path was left alone, since it is the same
// sample the mix got.
//
// The filter's state at the top of the measured block is not observable from here, but a
// one-pole forgets it as (1-coeff)^n, so the comparison starts once that is past 1e-6 —
// derived from the shipped coefficient rather than hard-coded, since a darker ear-tune
// forgets more slowly and would otherwise start the comparison mid-transient.
static bool test_storm_taps_the_send_buses() {
    printf("\n=== Test: storm sends at 0.45/0.15, reverb tap darkened on the way ===\n");
    OrpheusEngine* engine = make_muted_storm_engine();
    GraphUnit unit = make_storm_pulsar_unit();
    StormTestWeather wet; wet.rain = 0.8f; wet.rumble = 0.55f; wet.distance = 0.4f;
    wet.rain_level = 1.0f;
    push_storm_weather_arrangement(engine, wet, wet, 0, 8);
    trigger_vibe_load(engine);

    for (int b = 0; b < 120; b++) unit_process_pulsar(&unit, engine, 512, 48000.0f);

    constexpr float kReverbSend = 0.45f, kDelaySend = 0.15f;
    const int kSettle = std::min(256, 1 + (int)(std::log(1e-6f) /
                                               std::log(1.0f - storm::kStormSendLpCoeff)));
    const float rv = compute_rms(engine->pulsar_reverb_send_l, 512);
    const float dl = compute_rms(engine->pulsar_delay_send_l, 512);

    float lp = engine->pulsar_delay_send_l[0] / kDelaySend;
    float worst = 0.0f;
    double hf_bright = 0.0, hf_dark = 0.0, e_bright = 0.0, e_dark = 0.0;
    for (int i = 0; i < 512; i++) {
        const float bright = engine->pulsar_delay_send_l[i];
        const float dark   = engine->pulsar_reverb_send_l[i];
        lp += storm::kStormSendLpCoeff * (bright / kDelaySend - lp);
        const float expect = lp * kReverbSend;
        if (i >= kSettle)
            worst = std::fmax(worst, std::fabs(dark - expect) / (1.0f + std::fabs(expect)));
        if (i > 0) {
            const float db = bright - engine->pulsar_delay_send_l[i - 1];
            const float dd = dark - engine->pulsar_reverb_send_l[i - 1];
            hf_bright += (double)db * db;
            hf_dark   += (double)dd * dd;
        }
        e_bright += (double)bright * bright;
        e_dark   += (double)dark * dark;
    }
    // Normalised first-difference energy: a coarse "how much top is left". The darkened
    // tap has to come out duller than the undarkened one it was derived from, or the
    // filter is in the code but doing nothing. Loose on purpose — the coefficient is
    // pinned exactly above, and kStormSendBrightness is an ear-tune dial: only turning it
    // all the way up (coefficient 1, a bypass) should land here.
    const float top_bright = e_bright > 0.0 ? (float)(hf_bright / e_bright) : 0.0f;
    const float top_dark   = e_dark   > 0.0 ? (float)(hf_dark   / e_dark)   : 0.0f;

    bool ok = rv > 1e-4f && dl > 1e-4f && worst < 1e-4f && top_dark < top_bright * 0.95f;
    printf("  reverb_rms=%.5f delay_rms=%.5f  worst per-sample err=%.3g  top bright=%.4f dark=%.4f -- %s\n",
           rv, dl, worst, top_bright, top_dark, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The early-out contract: an all-zero bed with no strike must contribute EXACTLY
// nothing. With every track muted the mix is the storm alone, so "no contribution"
// is checkable as literal zero — a leaked denormal or a stuck filter fails here.
static bool test_zero_weather_leaves_the_mix_bit_quiet() {
    printf("\n=== Test: a vibe with no weather renders bit-exact silence ===\n");
    OrpheusEngine* engine = make_muted_storm_engine();
    GraphUnit unit = make_storm_pulsar_unit();
    StormTestWeather dry;                             // all zeros: no weather declared
    push_storm_weather_arrangement(engine, dry, dry, 0, 8);
    trigger_vibe_load(engine);

    int nonzero = 0, send_nonzero = 0;
    float worst = 0.0f;
    for (int b = 0; b < 400; b++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        for (int i = 0; i < 512; i++) {
            if (engine->pulsar_out_l[i] != 0.0f || engine->pulsar_out_r[i] != 0.0f) {
                nonzero++;
                worst = std::fmax(worst, std::fmax(std::fabs(engine->pulsar_out_l[i]),
                                                   std::fabs(engine->pulsar_out_r[i])));
            }
            // The send taps have to stay out of it too, or a dry vibe would still
            // print reverb tails from a bed nobody authored.
            if (engine->pulsar_reverb_send_l[i] != 0.0f ||
                engine->pulsar_delay_send_l[i] != 0.0f) send_nonzero++;
        }
    }
    bool ok = nonzero == 0 && send_nonzero == 0;
    printf("  nonzero mix=%d sends=%d worst=%.3g -- %s\n", nonzero, send_nonzero, worst,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Weather crossfades on the same pre-roll the macros use: a wet section walking
// into a dry one drains its bed across the ramp instead of cutting at the flip.
// Rain only — the rumble's roll swings its own level by more than 10x on purpose
// (test_rumble_roll_swells_and_recedes), which would swamp a monotonicity check.
static bool test_bed_drains_across_a_transition_ramp() {
    printf("\n=== Test: the bed follows the section transition ramp down ===\n");
    OrpheusEngine* engine = make_muted_storm_engine();
    GraphUnit unit = make_storm_pulsar_unit();
    StormTestWeather wet; wet.rain = 0.9f; wet.distance = 0.3f; wet.rain_level = 1.0f;
    StormTestWeather dry;
    push_storm_weather_arrangement(engine, wet, dry, /*trans_bars=*/4, /*bars=*/8);
    trigger_vibe_load(engine);

    // Bucket 0 is the pre-ramp bed (transition not staged yet); buckets 1-3 are the
    // ramp bars, keyed off the coarse per-bar progress the section machine publishes.
    // Progress 1.0 is never observed: the bar that sets it also performs the flip.
    double sq[4] = {}; long cnt[4] = {};
    for (int b = 0; b < 2400; b++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        const SectionState& ss = ps->section_state;
        if (ss.current_section != 0) break;                  // flipped: ramp is over
        int k = (ss.transition_target < 0)
                    ? 0
                    : static_cast<int>(ss.transition_progress * 4.0f + 0.5f);
        if (k < 0) k = 0;
        if (k > 3) k = 3;
        float rms = compute_rms(engine->pulsar_out_l, 512);
        sq[k] += static_cast<double>(rms) * rms; cnt[k]++;
    }
    float lvl[4] = {};
    for (int k = 0; k < 4; k++)
        lvl[k] = cnt[k] > 0 ? static_cast<float>(std::sqrt(sq[k] / cnt[k])) : -1.0f;
    bool measured = cnt[0] > 0 && cnt[1] > 0 && cnt[2] > 0 && cnt[3] > 0;
    bool monotone = measured && lvl[1] < lvl[0] && lvl[2] < lvl[1] && lvl[3] < lvl[2];
    bool drained = measured && lvl[3] < lvl[0] * 0.5f;
    bool ok = measured && monotone && drained && lvl[0] > 0.005f;
    printf("  bed=%.4f ramp=%.4f/%.4f/%.4f (blocks %ld/%ld/%ld/%ld) -- %s\n",
           lvl[0], lvl[1], lvl[2], lvl[3], cnt[0], cnt[1], cnt[2], cnt[3],
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The storm sinks into a Void with everything else: its contribution is scaled by
// the same per-sample void gain the tracks get, so the floor must duck it too.
static bool test_void_ducks_the_storm() {
    printf("\n=== Test: an armed void ducks the storm bed to its floor ===\n");
    OrpheusEngine* engine = make_muted_storm_engine();
    GraphUnit unit = make_storm_pulsar_unit();
    StormTestWeather wet; wet.rain = 0.8f; wet.rumble = 0.5f; wet.distance = 0.4f;
    wet.rain_level = 1.0f;
    push_storm_weather_arrangement(engine, wet, wet, /*trans_bars=*/0, /*bars=*/6);

    // Deterministic 1-bar floor with 1-bar ramps: a 3-bar arc inside a 6-bar section.
    engine->pulsar_void_data[0].store(1.0f, std::memory_order_relaxed);   // probability
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed);  // floor level
    engine->pulsar_void_data[2].store(1.0f, std::memory_order_relaxed);   // ramp down
    engine->pulsar_void_data[3].store(1.0f, std::memory_order_relaxed);   // floor min
    engine->pulsar_void_data[4].store(1.0f, std::memory_order_relaxed);   // floor max
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);   // ramp up
    engine->pulsar_void_data[6].store(0.0f, std::memory_order_relaxed);   // no ghost
    trigger_vibe_load(engine);

    float lead_in = 0.0f, floor_rms = 1e9f;
    bool saw_floor = false;
    for (int b = 0; b < 3000; b++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (b < 60) continue;                        // past the bed slew
        float rms = compute_rms(engine->pulsar_out_l, 512);
        const VoidAnomaly& vz = ps->void_state;
        if (!vz.armed) continue;
        if (vz.cursor <= vz.start_step) lead_in = std::fmax(lead_in, rms);
        if (vz.suppress_note_ons) { floor_rms = std::fmin(floor_rms, rms); saw_floor = true; }
    }
    bool ok = saw_floor && lead_in > 0.005f && floor_rms < lead_in * 0.2f;
    printf("  lead_in=%.4f floor=%.4f ratio=%.3f -- %s\n", lead_in, floor_rms,
           lead_in > 0.0f ? floor_rms / lead_in : -1.0f, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// ── StormAnomaly + per-bar weather strikes ─────────────────────────────────
// All of these run on the muted fixture at 120 BPM, where one bar is 16 steps of
// 6000 samples — 187.5 blocks of 512.
static constexpr int kBlocksPerBar = 188;

static void push_storm_anomaly_bank(OrpheusEngine* engine, float probability,
                                    float dur_min, float dur_max, float intensity,
                                    float distance, bool declared) {
    const float bank[OrpheusEngine::kStormDataFields] = {
        probability, dur_min, dur_max, intensity, distance, declared ? 1.0f : 0.0f
    };
    for (int i = 0; i < OrpheusEngine::kStormDataFields; i++)
        engine->pulsar_storm_data[i].store(bank[i], std::memory_order_relaxed);
}

// Rising edges of strike_active(): the anomaly and weather paths only fire while
// the voice is quiet, so one edge is exactly one accepted strike.
static int count_storm_strikes(OrpheusEngine* engine, GraphUnit* unit, int blocks,
                               float* peak_out = nullptr) {
    int strikes = 0;
    bool prev = false;
    float peak = 0.0f;
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(unit, engine, 512, 48000.0f);
        for (int i = 0; i < 512; i++)
            peak = std::fmax(peak, std::fabs(engine->pulsar_out_l[i]));
        const bool now = engine->pulsar_state->storm_voice.strike_active();
        if (now && !prev) strikes++;
        prev = now;
    }
    if (peak_out) *peak_out = peak;
    return strikes;
}

// Manual gesture on a declaring vibe: a strike inside a bar, a second one a bar
// later (the drawn window is >= 2 bars), and a rumble floor that outlives both
// tails and then releases. Intensity 0.5 keeps each tail near 2.8 s (~1.4 bars),
// so bar 4 of a 6-bar window carries the floor and nothing else.
//
// The second strike is measured as a TRANSIENT, not as a strike_active() edge: it
// lands while the first is still ringing (that is the whole point of the follow-up),
// so there is no falling edge in between to count.
static bool test_storm_anomaly_manual_trigger_strikes() {
    printf("\n=== Test: a declared StormAnomaly strikes on the manual trigger ===\n");
    OrpheusEngine* engine = make_muted_storm_engine();
    GraphUnit unit = make_storm_pulsar_unit();
    StormTestWeather dry;                      // no bed: the anomaly is the whole signal
    push_storm_weather_arrangement(engine, dry, dry, /*trans_bars=*/0, /*bars=*/8);
    push_storm_anomaly_bank(engine, /*probability=*/0.0f, /*dur_min=*/6.0f, /*dur_max=*/6.0f,
                            /*intensity=*/0.5f, /*distance=*/0.2f, /*declared=*/true);
    trigger_vibe_load(engine);

    float pre_peak = 0.0f;
    for (int b = 0; b < kBlocksPerBar; b++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        for (int i = 0; i < 512; i++)
            pre_peak = std::fmax(pre_peak, std::fabs(engine->pulsar_out_l[i]));
    }
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    constexpr int kRunBlocks = 10 * kBlocksPerBar;
    std::vector<float> block_peak(kRunBlocks, 0.0f);
    int blocks_to_strike = -1;
    float drawn_bars = -1.0f;
    double floor_sq = 0.0; long floor_n = 0;      // bar 4: both tails gone, window open
    double after_sq = 0.0; long after_n = 0;      // bars 8-9: window released
    for (int b = 0; b < kRunBlocks; b++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        const PulsarState* ps = engine->pulsar_state;
        if (blocks_to_strike < 0 && ps->storm_voice.strike_active()) {
            blocks_to_strike = b;
            drawn_bars = ps->storm_floor_bars_left;
        }
        float pk = 0.0f;
        for (int i = 0; i < 512; i++) pk = std::fmax(pk, std::fabs(engine->pulsar_out_l[i]));
        block_peak[b] = pk;
        const float rms = compute_rms(engine->pulsar_out_l, 512);
        const int bar = b / kBlocksPerBar;
        if (bar == 4) { floor_sq += (double)rms * rms; floor_n++; }
        if (bar >= 8) { after_sq += (double)rms * rms; after_n++; }
    }
    const float floor_rms = floor_n > 0 ? (float)std::sqrt(floor_sq / floor_n) : -1.0f;
    const float after_rms = after_n > 0 ? (float)std::sqrt(after_sq / after_n) : -1.0f;
    const float left_after = engine->pulsar_state->storm_floor_bars_left;

    // A bar is 187.5 blocks, so the follow-up burst lands in [+180, +200] of the first.
    // The 55 blocks before that are the first strike's decaying tail: no transient.
    auto window_peak = [&](int lo, int hi) {
        float pk = 0.0f;
        for (int b = std::max(lo, 0); b < std::min(hi, kRunBlocks); b++)
            pk = std::fmax(pk, block_peak[b]);
        return pk;
    };
    const float first_burst  = blocks_to_strike >= 0 ? window_peak(blocks_to_strike, blocks_to_strike + 20) : 0.0f;
    const float lull         = blocks_to_strike >= 0 ? window_peak(blocks_to_strike + 120, blocks_to_strike + 175) : 0.0f;
    const float second_burst = blocks_to_strike >= 0 ? window_peak(blocks_to_strike + 180, blocks_to_strike + 200) : 0.0f;

    bool ok = true;
    if (pre_peak != 0.0f) { printf("  FAIL: the vibe was not silent before the gesture (%.4g)\n", pre_peak); ok = false; }
    if (blocks_to_strike < 0 || blocks_to_strike >= kBlocksPerBar) {
        printf("  FAIL: no strike within a bar of the gesture (block %d)\n", blocks_to_strike); ok = false;
    }
    if (drawn_bars < 6.0f) { printf("  FAIL: drawn window %.1f bars (expected 6)\n", drawn_bars); ok = false; }
    if (first_burst < 0.02f) { printf("  FAIL: strike peak %.4f is inaudible\n", first_burst); ok = false; }
    if (second_burst < lull * 2.0f) {
        printf("  FAIL: no follow-up burst a bar in (%.4f vs %.4f in the lull before it)\n",
               second_burst, lull); ok = false;
    }
    if (floor_rms < 1e-3f) { printf("  FAIL: bar-4 rumble floor %.5f (expected the window to hold it)\n", floor_rms); ok = false; }
    if (after_rms > floor_rms * 0.1f) { printf("  FAIL: still ringing after the window (%.5f vs floor %.5f)\n", after_rms, floor_rms); ok = false; }
    if (left_after > 0.0f) { printf("  FAIL: floor countdown still %.1f bars after the window\n", left_after); ok = false; }
    if (ok) printf("  PASS: bursts %.3f then %.3f (lull %.3f), floor=%.5f -> %.5f after release\n",
                   first_burst, second_burst, lull, floor_rms, after_rms);

    orpheus_engine_destroy(engine);
    return ok;
}

// The gesture must be a no-op on a vibe that never declared the anomaly. Both runs
// render the same wet bed from the same pinned seeds, so "inert" is checkable as
// bit-equality against the un-bumped baseline rather than as a level threshold.
static bool test_undeclared_storm_anomaly_is_inert() {
    printf("\n=== Test: an undeclared StormAnomaly ignores the manual gesture ===\n");
    bool struck = false;
    auto run = [&](bool bump) {
        OrpheusEngine* engine = make_muted_storm_engine();
        GraphUnit unit = make_storm_pulsar_unit();
        StormTestWeather wet; wet.rain = 0.5f; wet.rumble = 0.3f; wet.distance = 0.5f;
        wet.rain_level = 1.0f;
        push_storm_weather_arrangement(engine, wet, wet, /*trans_bars=*/0, /*bars=*/8);
        push_storm_anomaly_bank(engine, /*probability=*/1.0f, /*dur_min=*/2.0f, /*dur_max=*/2.0f,
                                /*intensity=*/0.9f, /*distance=*/0.2f, /*declared=*/false);
        trigger_vibe_load(engine);
        std::vector<float> out;
        out.reserve(600 * 512);
        for (int b = 0; b < 600; b++) {
            if (bump && b == 200) engine->pulsar_anomaly_request.store(1, std::memory_order_release);
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            out.insert(out.end(), engine->pulsar_out_l, engine->pulsar_out_l + 512);
            if (bump && engine->pulsar_state->storm_voice.strike_active()) struck = true;
        }
        orpheus_engine_destroy(engine);
        return out;
    };
    const std::vector<float> baseline = run(false);
    const std::vector<float> bumped = run(true);

    float loudest = 0.0f;
    for (float s : baseline) loudest = std::fmax(loudest, std::fabs(s));
    size_t first_diff = baseline.size();
    for (size_t i = 0; i < baseline.size(); i++)
        if (baseline[i] != bumped[i]) { first_diff = i; break; }

    bool ok = true;
    if (loudest < 1e-3f) { printf("  FAIL: the baseline bed is silent (%.4g) — nothing to compare\n", loudest); ok = false; }
    if (first_diff != baseline.size()) {
        printf("  FAIL: outputs diverge at sample %zu (%.6g vs %.6g)\n",
               first_diff, baseline[first_diff], bumped[first_diff]);
        ok = false;
    }
    if (struck) { printf("  FAIL: the storm struck on an undeclared vibe\n"); ok = false; }
    if (ok) printf("  PASS: %zu samples bit-identical, bed peak %.3f\n", baseline.size(), loudest);
    return ok;
}

// strikeChance is a per-bar roll on the ACTIVE section's weather. The guard means
// "every bar" is really "every bar the previous strike has finished ringing", so
// the assertion is repetition, not a fixed count. Zero must never roll at all.
static bool test_weather_strike_chance_rolls_per_bar() {
    printf("\n=== Test: strikeChance strikes repeatedly; zero never strikes ===\n");
    auto run = [](float chance, int* strikes, float* peak) {
        OrpheusEngine* engine = make_muted_storm_engine();
        GraphUnit unit = make_storm_pulsar_unit();
        StormTestWeather w; w.strike_chance = chance; w.distance = 0.9f;   // dry bed, strikes only
        push_storm_weather_arrangement(engine, w, w, /*trans_bars=*/0, /*bars=*/8);
        trigger_vibe_load(engine);
        *strikes = count_storm_strikes(engine, &unit, 12 * kBlocksPerBar, peak);
        orpheus_engine_destroy(engine);
    };
    int hot_strikes = 0, cold_strikes = 0;
    float hot_peak = 0.0f, cold_peak = 0.0f;
    run(1.0f, &hot_strikes, &hot_peak);
    run(0.0f, &cold_strikes, &cold_peak);

    bool ok = true;
    if (hot_strikes < 3) { printf("  FAIL: strikeChance 1 struck %d times in 12 bars\n", hot_strikes); ok = false; }
    if (hot_peak < 0.02f) { printf("  FAIL: strikeChance 1 peak %.4f is inaudible\n", hot_peak); ok = false; }
    if (cold_strikes != 0) { printf("  FAIL: strikeChance 0 struck %d times\n", cold_strikes); ok = false; }
    if (cold_peak != 0.0f) { printf("  FAIL: strikeChance 0 made sound (%.4g)\n", cold_peak); ok = false; }
    if (ok) printf("  PASS: chance 1 -> %d strikes (peak %.3f), chance 0 -> silence\n", hot_strikes, hot_peak);
    return ok;
}

// Endpoint-only coverage (chance 0 and 1, above) can't catch a mutant that swaps the
// compared chance for a literal 1.0: that mutant still behaves correctly at the two
// endpoints and only misbehaves in between. A naive "chance 0.5 hits both a striking
// bar and a quiet bar" probe doesn't discriminate that mutant either here: the roll
// is drawn every bar regardless of outcome (comment above test_weather_strike_chance_
// rolls_per_bar), a strike's own rumble tail then holds strike_active() for ~2.6 bars
// (measured at the fixed weather-strike intensity), and that guard alone already
// forces quiet bars into ANY chance>0 run — chance=1 included. What the mutant
// actually collapses is chance 0.5 into chance 1's cadence: since both runs draw the
// same pinned RNG stream bar for bar, the real comparison must strike strictly less
// often than chance=1 over the same window, while the 1.0-literal mutant makes the
// two runs bar-for-bar identical (same strike count).
static bool test_weather_strike_chance_midrange_strikes_less_than_certain() {
    printf("\n=== Test: strikeChance 0.5 strikes strictly less than chance 1 over the same bars ===\n");
    auto run = [](float chance, int* strikes) {
        OrpheusEngine* engine = make_muted_storm_engine();
        GraphUnit unit = make_storm_pulsar_unit();
        StormTestWeather w; w.strike_chance = chance; w.distance = 0.9f;   // dry bed, strikes only
        push_storm_weather_arrangement(engine, w, w, /*trans_bars=*/0, /*bars=*/8);
        trigger_vibe_load(engine);
        *strikes = count_storm_strikes(engine, &unit, 16 * kBlocksPerBar, nullptr);
        orpheus_engine_destroy(engine);
    };
    int half_strikes = 0, full_strikes = 0;
    run(0.5f, &half_strikes);
    run(1.0f, &full_strikes);

    bool ok = true;
    if (half_strikes < 1) { printf("  FAIL: chance 0.5 never struck across 16 bars\n"); ok = false; }
    if (half_strikes >= full_strikes) {
        printf("  FAIL: chance 0.5 struck %d times, chance 1 struck %d in the same window -- "
               "the mid-range chance isn't gating anything\n", half_strikes, full_strikes);
        ok = false;
    }
    if (ok) printf("  PASS: chance 0.5 -> %d strikes, chance 1 -> %d (same 16-bar window, same seed)\n",
                    half_strikes, full_strikes);
    return ok;
}

// The auto path rolls at section entry, beside the other anomalies. Two-bar
// sections give four flips inside the render budget.
static bool test_storm_anomaly_auto_roll_at_section_entry() {
    printf("\n=== Test: StormAnomaly auto-roll fires at probability 1, never at 0 ===\n");
    auto run = [](float probability, bool declared, int* strikes) {
        OrpheusEngine* engine = make_muted_storm_engine();
        GraphUnit unit = make_storm_pulsar_unit();
        StormTestWeather dry;
        push_storm_weather_arrangement(engine, dry, dry, /*trans_bars=*/0, /*bars=*/2);
        push_storm_anomaly_bank(engine, probability, /*dur_min=*/1.0f, /*dur_max=*/1.0f,
                                /*intensity=*/0.8f, /*distance=*/0.3f, declared);
        trigger_vibe_load(engine);
        *strikes = count_storm_strikes(engine, &unit, 8 * kBlocksPerBar, nullptr);
        orpheus_engine_destroy(engine);
    };
    int certain = 0, never = 0, undeclared = 0;
    run(1.0f, /*declared=*/true, &certain);
    run(0.0f, /*declared=*/true, &never);
    run(1.0f, /*declared=*/false, &undeclared);

    bool ok = true;
    if (certain < 1) { printf("  FAIL: probability 1 never armed across 4 section entries\n"); ok = false; }
    if (never != 0) { printf("  FAIL: probability 0 armed %d time(s)\n", never); ok = false; }
    if (undeclared != 0) { printf("  FAIL: an undeclared vibe auto-armed %d time(s)\n", undeclared); ok = false; }
    if (ok) printf("  PASS: p=1 -> %d strikes, p=0 -> 0, undeclared -> 0\n", certain);
    return ok;
}

static bool test_storm_bank_routing() {
    printf("\n=== Test: storm_data_$i routes to the engine bank and bounds-checks ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const char* uri = "org.balch.orpheus.plugins.pulsar";
    orpheus_engine_set_port(engine, uri, "storm_data_0", 0.25f);
    orpheus_engine_set_port(engine, uri, "storm_data_5", 1.0f);
    orpheus_engine_set_port(engine, uri, "storm_data_6", 9.0f);   // out of range, dropped

    bool ok = true;
    if (engine->pulsar_storm_data[0].load(std::memory_order_relaxed) != 0.25f) {
        printf("  FAIL: slot 0 did not route\n"); ok = false;
    }
    if (engine->pulsar_storm_data[5].load(std::memory_order_relaxed) != 1.0f) {
        printf("  FAIL: slot 5 (declared) did not route\n"); ok = false;
    }
    if (OrpheusEngine::kStormDataFields != 6) {
        printf("  FAIL: bank size %d (expected 6)\n", OrpheusEngine::kStormDataFields); ok = false;
    }
    if (ok) printf("  PASS: 6-slot bank routed, out-of-range write dropped\n");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_storm_tests() {
    printf("\n=== Pulsar Storm Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_rain_bounds_and_dc());
    tally(test_rain_level_zero_is_silent());
    tally(test_rain_level_buys_drops_and_a_far_field_wash());
    tally(test_rain_level_scales_loudness_not_rate());
    tally(test_low_rain_is_genuinely_sparse());
    tally(test_rumble_tail_length_and_onset());
    tally(test_rumble_tail_spans_intensity_range());
    tally(test_storm_worst_case_peak_bounded());
    tally(test_rumble_is_low_frequency());
    tally(test_rumble_roll_swells_and_recedes());
    tally(test_strike_fades_to_actual_silence());
    tally(test_generators_deterministic_under_seed());
    tally(test_strike_burst_spacing_sub_block());
    tally(test_burst_schedule_matches_ear_tune_constants_exactly());
    tally(test_clap_pitch_staircase_descends());
    tally(test_clap_grit_broadens_the_spectrum());
    tally(test_clap_echo_grows_and_lengthens_with_distance());
    tally(test_strike_echo_outlasts_the_dry_cascade());
    tally(test_clap_tails_lengthen_down_the_staircase());
    tally(test_clap_snap_leads_the_crack());
    tally(test_clap_crackle_tears_the_envelope());
    tally(test_rumble_body_carries_and_darkens());
    tally(test_rumble_peals_reswell_the_roll());
    tally(test_strike_far_distance_drops_claps());
    tally(test_storm_voice_bounds_all_modes());
    tally(test_strike_active_lifecycle());
    tally(test_delayed_strike_sounds_at_the_authored_offset());
    tally(test_authored_pair_needs_a_gap_to_be_two_cracks());
    tally(test_zero_delay_is_bit_identical_to_the_immediate_path());
    tally(test_a_queued_strike_holds_the_voice());
    tally(test_storm_bank_routing());
    // The integration tests pin the global metallic-noise RNG; later suites read
    // whatever state they inherit, so hand it back exactly as it was found.
    {
        uint32_t saved = stmlib::Random::state();
        tally(test_weather_bed_is_audible_on_a_muted_vibe());
        tally(test_storm_taps_the_send_buses());
        tally(test_zero_weather_leaves_the_mix_bit_quiet());
        tally(test_bed_drains_across_a_transition_ramp());
        tally(test_void_ducks_the_storm());
        tally(test_storm_anomaly_manual_trigger_strikes());
        tally(test_undeclared_storm_anomaly_is_inert());
        tally(test_weather_strike_chance_rolls_per_bar());
        tally(test_weather_strike_chance_midrange_strikes_less_than_certain());
        tally(test_storm_anomaly_auto_roll_at_section_entry());
        stmlib::Random::Seed(saved);
    }
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
