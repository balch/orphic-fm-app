#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "pulsar_rng.h"           // pattern_rand / pattern_rand01
#include "chaos/lorenz.h"         // chaos::process_lorenz + ChaosVoiceState
#include "stmlib/dsp/dsp.h"       // SoftClip
#include "stmlib/dsp/filter.h"

// Weather generators for the pulsar storm voice. Everything here is seeded per
// instance and driven only by pulsar_rng, so a given seed always renders the
// same audio; no allocation, no statics, no logging after Init.
namespace storm {

// Avalanche a seed + salt into a nonzero xorshift state. Adjacent seeds must not
// stay adjacent: xorshift32 needs many steps to decorrelate low-bit-only differences.
inline uint32_t storm_seed(uint32_t seed, uint32_t salt) {
    uint32_t h = seed * 2654435761u + salt * 2246822519u + 0x9E3779B9u;
    h ^= h >> 16; h *= 0x45d9f3bu; h ^= h >> 16;
    return h != 0u ? h : (0xA5A5A5A5u ^ salt);
}

inline float storm_clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

// ── Shared ──────────────────────────────────────────────────────────────────
// Adapted from Mutable Instruments Plaits (dsp/noise/smooth_random_generator.h),
// MIT, (c) Emilie Gillet. Deviations: instance RNG in place of stmlib::Random's static
// state, a randomized start so the slow octaves are never dead on Init, and Restart()
// so a caller can re-aim the ramp instead of inheriting wherever it was parked. Drives
// the rumble's undulation and the rain wash's gust.
struct SmoothRandomLfo {
    void Init(uint32_t seed) {
        rng_ = seed;
        phase_ = pattern_rand01(rng_);
        from_ = pattern_rand01(rng_) * 2.0f - 1.0f;
        interval_ = pattern_rand01(rng_) * 2.0f - 1.0f - from_;
    }
    float Render(float frequency) {
        phase_ += frequency;
        if (phase_ >= 1.0f) {
            phase_ -= 1.0f;
            from_ += interval_;
            interval_ = pattern_rand01(rng_) * 2.0f - 1.0f - from_;
        }
        const float t = phase_ * phase_ * (3.0f - 2.0f * phase_);
        return from_ + interval_ * t;
    }
    // Re-aim the ramp from a known value, drawing its next target off the same
    // instance stream so a restart stays as deterministic as free running.
    void Restart(float value) {
        phase_ = 0.0f;
        from_ = value;
        interval_ = pattern_rand01(rng_) * 2.0f - 1.0f - from_;
    }
    uint32_t rng_ = 1u;
    float phase_ = 0.0f, from_ = 0.0f, interval_ = 0.0f;
};

// ── Rain ────────────────────────────────────────────────────────────────────
// Rainfall is two things at once, and voicing only one of them is what makes it read
// as a leak. NEAR field: Bernoulli dust, each drop a one-sample impulse into a
// bandpass it re-tunes on the way in, so every drop is its own size. FAR field: a
// diffuse wash of the thousands of drops too distant to resolve, dark and gusting,
// rising super-linearly so it is a hint behind a drizzle and the body of a downpour.
// Adapted from Mutable Instruments Plaits (dsp/noise/particle.h), MIT, (c) Emilie Gillet.

static constexpr int   kRainVoices       = 4;         // ring-out slots, taken round-robin
static_assert((kRainVoices & (kRainVoices - 1)) == 0, "slot rotation masks with kRainVoices-1");
static constexpr float kRainSlewSeconds  = 0.08f;

// EAR-TUNE(rain-density): drops per second, per channel. GEOMETRIC, not linear — a
// linear sweep spends its whole bottom half above the rate at which the ear stops
// resolving drops. The floor is SLOW RAIN: separated, countable impacts. It is not a
// dripping tap — 4/s was, but a tap needs sparse AND pitched AND unaccompanied drops,
// and only the sparseness is left (kRainQ took the pitch out, the wash and the
// per-channel pair supply the rest). L and R draw independent dust, so the field
// carries twice this rate. 50/s was a solid patter with no slower setting under it.
static constexpr float kRainMinHitsHz    = 12.0f;
static constexpr float kRainMaxHitsHz    = 2500.0f;

// EAR-TUNE(rain-loudness): master gain on the WHOLE layer, drops and wash together, so
// retuning it never moves their balance. This is the ceiling a section's rainLevel
// scales under. At 1.0 a downpour rendered at twice the band's own RMS and peaked near
// full scale; weather belongs under the music, not over it.
static constexpr float kRainOutGain      = 0.30f;

// EAR-TUNE(rain-drops): the near field. Q is the ring time Q/(pi*f) — 3.7 ms at 600 Hz,
// 0.45 ms at 5 kHz — and at 20 that ring was long enough to give every drop a definite
// PITCH, which is the single most leak-like cue there is. Each drop draws its own centre
// anywhere across the span; quantising to a few fixed bands gave rain a few fixed sizes.
static constexpr float kRainQ            = 7.0f;
static constexpr float kRainLowHz        = 600.0f;    // fattest drops
static constexpr float kRainHighHz       = 5000.0f;   // finest spray
// Per-drop peak BEFORE kRainOutGain, flat across the sweep. It and kRainWashGain are
// the two layers' relative weights; the master above is what sets absolute loudness.
static constexpr float kRainDropGain     = 0.052f;

// EAR-TUNE(rain-wash): the far field, at (rain level)^kRainWashCurve — a hint at a
// drizzle, level with the drops near 0.5, the body of a downpour. Deliberately dark: an
// undarkened 2-5 kHz bed is what read as static the last time rain was continuous.
// kRainWashGain weights a chain whose own RMS is ~0.07 full-scale. More sheet: raise the
// gain, or drop the curve toward 1.5 to bring the sheet in earlier.
static constexpr float kRainWashGain     = 1.75f;     // wash weight at level 1
static constexpr float kRainWashCurve    = 2.0f;      // >1: super-linear, drops lead at the bottom
static constexpr float kRainWashHz       = 850.0f;    // centre of mass, low-mid on purpose
static constexpr float kRainWashQ        = 0.55f;     // broad hump, 6 dB/oct skirts
static constexpr float kRainWashTopHz    = 2000.0f;   // one-pole doubling the top skirt to 12 dB/oct
static constexpr float kRainWashGustHz   = 0.15f;     // ~7 s: a wash that never moves reads as static
static constexpr float kRainWashGustDepth = 0.35f;    // troughs sit 35% under the crests

static_assert(kRainWashCurve > 1.0f, "the wash has to grow faster than the drops it hides behind");
static_assert(kRainWashGustDepth >= 0.0f && kRainWashGustDepth < 1.0f,
              "a gust deeper than 1 would invert the wash");

// The geometric map never reaches zero hits, so something else has to walk a bed down
// to silence. Below this level the drops thin AND fade; above it they only thin.
static constexpr float kRainFadeKnee     = 0.06f;
static constexpr float kRainSilenceEps   = 1e-5f;

static_assert(kRainMinHitsHz > 0.0f && kRainMaxHitsHz > kRainMinHitsHz,
              "the density map is a ratio raised to `level`; it has to span upward");
static_assert(kRainLowHz > 0.0f && kRainHighHz > kRainLowHz, "drop spread must span upward");

struct RainGen {
    void Init(uint32_t seed, float sr) {
        sr_ = sr;
        dust_rng_[0] = storm_seed(seed, 1u);
        dust_rng_[1] = storm_seed(seed, 2u);
        wash_rng_[0] = storm_seed(seed, 3u);
        wash_rng_[1] = storm_seed(seed, 4u);
        gust_lfo_.Init(storm_seed(seed, 5u));
        level_ = 0.0f;
        target_ = 0.0f;
        gain_ = 1.0f;
        gain_target_ = 1.0f;
        gust_ = 1.0f;
        spread_octaves_ = std::log2(kRainHighHz / kRainLowHz);
        wash_top_coef_ = 1.0f - std::exp(-2.0f * 3.14159265f * kRainWashTopHz / sr);
        for (int ch = 0; ch < 2; ++ch) {
            for (int k = 0; k < kRainVoices; ++k) svf_[ch][k].Init();
            next_[ch] = 0;
            wash_svf_[ch].Init();
            wash_svf_[ch].set_f_q<stmlib::FREQUENCY_FAST>(kRainWashHz / sr, kRainWashQ);
            wash_lp_[ch] = 0.0f;
        }
    }

    // Rate: how many drops land. Independent of set_gain below.
    void set_level(float v) { target_ = storm_clamp01(v); }
    // Loudness: one 0-1 gain over drops AND wash, so the balance between them holds.
    void set_gain(float v) { gain_target_ = storm_clamp01(v); }

    // ADDS into l/r.
    void Process(float* l, float* r, int n) {
        if (n <= 0) return;
        const float start = level_, gain_start = gain_;
        const float slew = std::exp(-(float)n / (kRainSlewSeconds * sr_));
        level_ = target_ + (level_ - target_) * slew;
        gain_  = gain_target_ + (gain_ - gain_target_) * slew;
        // Silent either way: no drops, or no level to render them at. Both ends of the
        // block have to be under the epsilon or a fade would be cut off mid-ramp.
        if ((start < kRainSilenceEps && level_ < kRainSilenceEps) ||
            (gain_start < kRainSilenceEps && gain_ < kRainSilenceEps)) {
            // Exact silence, no stuck dust and no wash tail. Snapping and clearing both
            // banks keeps the level and the filter states out of denormal territory.
            if (target_ <= 0.0f) level_ = 0.0f;
            if (gain_target_ <= 0.0f) gain_ = 0.0f;
            for (int ch = 0; ch < 2; ++ch) {
                for (int k = 0; k < kRainVoices; ++k) svf_[ch][k].Reset();
                wash_svf_[ch].Reset();
                wash_lp_[ch] = 0.0f;
            }
            return;
        }

        const float p_hit = kRainMinHitsHz *
            std::pow(kRainMaxHitsHz / kRainMinHitsHz, level_) / sr_;
        // The master and the section's own gain fold together here, once per block, so
        // neither reaches the per-sample loop as a separate multiply.
        const float q0 = gain_start * kRainOutGain, q1 = gain_ * kRainOutGain;
        // Intensity is carried by how many drops land, not by how hard each one lands,
        // so the two ends of the block differ only by the fade toward silence.
        const float a0 = DropAmpAt(start) * q0, a1 = DropAmpAt(level_) * q1;
        // The gust advances once per block and interpolates across it: at 0.15 Hz it has
        // nothing to say at sample resolution, and a per-sample LFO would cost the wash
        // more than the filter it drives.
        const float g0 = gust_;
        gust_ = Gust(n);
        const float w0 = WashAmpAt(start) * g0 * q0, w1 = WashAmpAt(level_) * gust_ * q1;
        const float inv_n = 1.0f / (float)n;
        for (int i = 0; i < n; ++i) {
            const float t = (float)i * inv_n;
            const float amp  = a0 + (a1 - a0) * t;
            const float wash = w0 + (w1 - w0) * t;
            l[i] += RenderChannel(0, p_hit, amp, wash);
            r[i] += RenderChannel(1, p_hit, amp, wash);
        }
    }

 private:
    // Per-drop amplitude: FLAT with density, faded out below the knee so a bed can still
    // be walked all the way down to silence. Level buys drops, never drop size. Both this
    // and WashAmpAt below are pre-gain — Process applies the master and set_gain's value.
    static float DropAmpAt(float level) {
        const float fade = level < kRainFadeKnee ? level / kRainFadeKnee : 1.0f;
        return kRainDropGain * fade;
    }

    // Zero at level 0 exactly, so the wash is gated to true silence and not to a floor.
    static float WashAmpAt(float level) {
        return kRainWashGain * std::pow(level, kRainWashCurve);
    }

    // Shallow, slow amplitude gust in [1-depth, 1]. Advances by a whole block at once.
    float Gust(int n) {
        const float u = 0.5f + 0.5f * gust_lfo_.Render(kRainWashGustHz * (float)n / sr_);
        return 1.0f - kRainWashGustDepth * (1.0f - storm_clamp01(u));
    }

    float RenderChannel(int ch, float p_hit, float amp, float wash_amp) {
        uint32_t& rng = dust_rng_[ch];
        float in[kRainVoices] = {};
        if (pattern_rand01(rng) < p_hit) {
            // One draw, three disjoint bit fields: overlapping them would correlate
            // pitch with sign and leave the bed with a small DC bias.
            const uint32_t v = pattern_rand(rng);
            const float mag   = 0.6f + 0.4f * (float)(v & 0xFFFFu) * (1.0f / 65535.0f);
            const float pitch = (float)((v >> 16) & 0x7FFFu) * (1.0f / 32767.0f);
            // Round-robin, so a drop always lands in the slot that has had the longest
            // to decay before its centre is moved out from under it.
            const int k = next_[ch];
            next_[ch] = (k + 1) & (kRainVoices - 1);
            float f = kRainLowHz * std::exp2(pitch * spread_octaves_) / sr_;
            if (f > 0.45f) f = 0.45f;
            svf_[ch][k].set_f_q<stmlib::FREQUENCY_FAST>(f, kRainQ);
            // A bandpass impulse peaks proportionally to g, so undoing g keeps a drop's
            // loudness independent of the centre it happened to draw.
            in[k] = ((v & 0x80000000u) ? mag : -mag) * amp / svf_[ch][k].g();
        }
        float out = 0.0f;
        for (int k = 0; k < kRainVoices; ++k)
            out += svf_[ch][k].Process<stmlib::FILTER_MODE_BAND_PASS>(in[k]);

        // Far field: broad bandpass plus a one-pole on the top, which is the whole
        // difference between a distant sheet of rain and a frying pan.
        const float white = pattern_rand01(wash_rng_[ch]) * 2.0f - 1.0f;
        const float bp = wash_svf_[ch].Process<stmlib::FILTER_MODE_BAND_PASS>(white);
        wash_lp_[ch] += (bp - wash_lp_[ch]) * wash_top_coef_;
        return out + wash_lp_[ch] * wash_amp;
    }

    stmlib::Svf svf_[2][kRainVoices];
    stmlib::Svf wash_svf_[2];
    SmoothRandomLfo gust_lfo_;
    uint32_t dust_rng_[2] = { 1u, 2u };
    uint32_t wash_rng_[2] = { 3u, 4u };
    int      next_[2] = { 0, 0 };
    float    wash_lp_[2] = { 0.0f, 0.0f };
    float    wash_top_coef_ = 0.0f;
    float    spread_octaves_ = 3.0f;
    float    gust_ = 1.0f;
    float    sr_ = 48000.0f;
    float    level_ = 0.0f;
    float    target_ = 0.0f;
    float    gain_ = 1.0f;
    float    gain_target_ = 1.0f;
};

// ── Rumble ──────────────────────────────────────────────────────────────────

static constexpr int   kRumbleOctaves      = 5;
// EAR-TUNE(thunder-roll): how the front swells and recedes as it travels. The slowest
// octave has to be quick enough to shape a single 2-8 s tail — at the 32 s it started
// on, one strike simply held whatever level the LFO happened to be parked at. Depth 1
// would let the roll recede to true silence; the map stays strictly non-negative.
static constexpr float kRumbleSlowestHz    = 0.12f;   // 8.3 s; octaves double up to 1.9 Hz
static constexpr float kRumbleUndulationDepth = 0.9f; // troughs land 20 dB under the crests
static constexpr float kRumbleUndulationSwing = 1.7f; // widens the fractal sum before clipping
static constexpr float kRumbleBaseHz       = 90.0f;
// Distance darkens the roll, but the pulsar's output high-pass sits at 55 Hz: a deeper
// tilt would push a far strike's whole band under it and simply delete the thunder.
static constexpr float kRumbleDistDarken   = 0.35f;
static constexpr float kRumbleQ            = 1.5f;
static constexpr float kRumbleBedSeconds   = 0.10f;
// Sized so the roll's CRESTS land where the old flat bed sat: the deep undulation is
// meant to add contrast, not loudness, and a strike now arrives on a full crest.
static constexpr float kRumbleGain         = 3.5f;
// 3e-3, up from 1e-3: the peals below re-lift the envelope mid-tail, and at the old floor
// tail_active() ran past the 8 s spec ceiling on a close strike. -50 dB is still well
// under anything audible over the bed.
static constexpr float kRumbleTailFloor    = 3e-3f;   // tail_active() cuts off here
static constexpr float kRumbleSilenceEps   = 1e-5f;
// Per-second decay rates for the tail envelope. Tail length is exactly
// inversely proportional to the intensity rate scale in trigger_tail, so these
// two set the intensity-1 end of the range (~7.6 s) and that mapping sets the rest.
static constexpr float kRumbleShortDecayHz = 0.046f;
static constexpr float kRumbleTailDecayHz  = 0.966f;

// EAR-TUNE(thunder-body): the roll's BODY. The sub path alone (90 Hz low-pass, above a
// 55 Hz output high-pass) is a one-octave hum that consumer speakers barely reproduce,
// which is why a strike read as a tick and then nothing. Thunder's weight sits at
// 100-300 Hz and is what carries on a laptop. The body rides the SAME tail envelope
// raised to a power, so it decays faster than the sub and the roll darkens as it
// travels off, which is the physical cue that it is travelling at all. The bed gets a
// share of it too, or a rumble bed is inaudible on the same speakers.
static constexpr float kRumbleBodyHz       = 165.0f;
static constexpr float kRumbleBodyQ        = 0.9f;    // broad: a boom, not a note
// Against kRumbleGain's sub path. The body band-pass is unity at its centre and about a
// 290 Hz noise bandwidth, the sub low-pass resonates at 90 Hz over ~210 Hz: at 4.5 the
// two arrive at roughly equal RMS on a close strike, and the body's steeper envelope
// takes it from there.
static constexpr float kRumbleBodyGain     = 4.5f;
// A Q under 1 keeps the body a boom rather than a note, but its upper skirt then falls
// only 6 dB/oct and reads as rushing water above 300 Hz. One pole on top doubles the
// slope up there and leaves the 100-250 Hz weight alone.
static constexpr float kRumbleBodyTopHz    = 320.0f;
static constexpr float kRumbleBodyDistTilt = 0.6f;    // a distance-1 roll keeps 40% of its body
static constexpr float kRumbleBedBody      = 0.30f;   // the bed's share of the body path

// EAR-TUNE(thunder-peals): rolling thunder is several arrivals, not one. A bolt is
// kilometres long, so the sound from its far segments lands seconds after the near
// ones: each peal lifts the tail envelope back UP to its level (never down) and re-aims
// the undulation onto a crest, so the roll swells again instead of fading in one line.
// The windows sit where the first arrival has already receded (the envelope is near 0.6
// by a second in, 0.25 by two), or a peal would land on a roll still at full and vanish.
// Delays are drawn per strike inside these windows and stretched by distance, since a far
// bolt's segments are spread across a wider range of ranges.
static constexpr int   kRumblePeals        = 2;
static constexpr float kRumblePealMinS[]   = {0.80f, 1.80f};
static constexpr float kRumblePealMaxS[]   = {1.40f, 2.80f};
static constexpr float kRumblePealLevel[]  = {0.85f, 0.60f};   // envelope level lifted TO
static constexpr float kRumblePealDistStretch = 0.5f;          // delay x (1 + this x distance)

static_assert(kRumblePeals == (int)(sizeof(kRumblePealMinS)  / sizeof(float)), "peal min table");
static_assert(kRumblePeals == (int)(sizeof(kRumblePealMaxS)  / sizeof(float)), "peal max table");
static_assert(kRumblePeals == (int)(sizeof(kRumblePealLevel) / sizeof(float)), "peal level table");
static_assert(kRumbleBodyDistTilt >= 0.0f && kRumbleBodyDistTilt < 1.0f,
              "a tilt of 1 or more would invert the body at distance 1");
static_assert(kRumbleBedBody >= 0.0f && kRumbleBedBody <= 1.0f, "the bed's body share is a fraction");

struct RumbleGen {
    void Init(uint32_t seed, float sr) {
        sr_ = sr;
        noise_rng_[0] = storm_seed(seed, 11u);
        noise_rng_[1] = storm_seed(seed, 12u);
        peal_rng_ = storm_seed(seed, 13u);
        for (int k = 0; k < kRumbleOctaves; ++k) lfo_[k].Init(storm_seed(seed, 20u + (uint32_t)k));
        lp_[0].Init(); lp_[1].Init();
        body_[0].Init(); body_[1].Init();
        body_lp_[0] = 0.0f; body_lp_[1] = 0.0f;
        body_top_coef_ = 1.0f - std::exp(-2.0f * 3.14159265f * kRumbleBodyTopHz / sr_);
        bed_slew_ = 1.0f - std::exp(-1.0f / (kRumbleBedSeconds * sr_));
        bed_ = 0.0f; bed_target_ = 0.0f;
        tail_env_ = 0.0f; tail_amp_ = 0.0f;
        body_amp_ = 0.0f;
        for (int k = 0; k < kRumblePeals; ++k) { peal_samples_[k] = -1; peal_level_[k] = 0.0f; }
        distance_ = 0.5f;
        short_decay_ = kRumbleShortDecayHz / sr_;
        tail_decay_  = kRumbleTailDecayHz  / sr_;
    }

    void set_bed(float v, float distance) {
        bed_target_ = storm_clamp01(v);
        distance_   = storm_clamp01(distance);
    }

    // Snaps the envelope to full, then decays asymptotically at a rate that
    // accelerates as it falls (the LPG trick). The rate mapping below spans the
    // spec's 2-8 s: a far-off intensity 0 rolls for ~2.2 s, a close 1.0 for ~7.6 s.
    void trigger_tail(float intensity, float distance) {
        intensity = storm_clamp01(intensity);
        distance_ = storm_clamp01(distance);
        tail_env_ = 1.0f;
        tail_amp_ = intensity * (1.0f - 0.5f * distance_);
        // The boom under a close strike; distance takes it away faster than the sub.
        body_amp_ = tail_amp_ * (1.0f - kRumbleBodyDistTilt * distance_);
        const float rate = 1.0f / (0.29f + 0.71f * intensity);   // hard strikes ring longest
        short_decay_ = kRumbleShortDecayHz * rate / sr_;
        tail_decay_  = kRumbleTailDecayHz  * rate / sr_;
        // The wavefront arrives at full strength and the roll travels away from there.
        // Without this a strike landing in an undulation trough would be swallowed for
        // seconds: the octaves are slower than a whole tail, so whatever level they sit
        // at when the strike lands is the level it keeps.
        RestartOnCrest();
        // The later arrivals. A re-trigger mid-tail re-draws them, so a follow-up strike
        // brings its own peals rather than inheriting the previous bolt's.
        const float stretch = 1.0f + kRumblePealDistStretch * distance_;
        for (int k = 0; k < kRumblePeals; ++k) {
            const float s = kRumblePealMinS[k] +
                            (kRumblePealMaxS[k] - kRumblePealMinS[k]) * pattern_rand01(peal_rng_);
            peal_samples_[k] = (int)(s * stretch * sr_ + 0.5f);
            peal_level_[k]   = kRumblePealLevel[k];
        }
    }

    bool tail_active() const { return tail_env_ > 0.0f; }
    // Pre-undulation amplitude the tail is currently contributing. A host gating on
    // "is this strike still audible" has to read this, not tail_active(): the envelope
    // legitimately runs on for seconds below the point anyone can hear it.
    float tail_level() const { return tail_env_ * tail_amp_; }

    // ADDS into l/r.
    void Process(float* l, float* r, int n) {
        if (n <= 0) return;
        if (bed_target_ <= 0.0f && bed_ < kRumbleSilenceEps && tail_env_ <= 0.0f && !peal_pending()) {
            bed_ = 0.0f;
            lp_[0].Reset(); lp_[1].Reset();
            body_[0].Reset(); body_[1].Reset();
            body_lp_[0] = 0.0f; body_lp_[1] = 0.0f;
            return;
        }
        const float darken = 1.0f - kRumbleDistDarken * distance_;
        const float f = kRumbleBaseHz * darken / sr_;
        lp_[0].set_f_q<stmlib::FREQUENCY_DIRTY>(f, kRumbleQ);
        lp_[1].set_f_q<stmlib::FREQUENCY_DIRTY>(f, kRumbleQ);
        const float fb = kRumbleBodyHz * darken / sr_;
        body_[0].set_f_q<stmlib::FREQUENCY_DIRTY>(fb, kRumbleBodyQ);
        body_[1].set_f_q<stmlib::FREQUENCY_DIRTY>(fb, kRumbleBodyQ);

        for (int i = 0; i < n; ++i) {
            bed_ += (bed_target_ - bed_) * bed_slew_;
            // Adapted from Mutable Instruments Plaits (dsp/envelope.h), MIT,
            // (c) Emilie Gillet. The vactrol coefficient, decaying toward 0.
            if (tail_env_ > 0.0f) {
                const float e2 = tail_env_ * tail_env_;
                tail_env_ -= tail_env_ * (short_decay_ + (1.0f - e2 * e2) * tail_decay_);
                if (tail_env_ < kRumbleTailFloor) tail_env_ = 0.0f;
            }
            // A peal arriving lifts the envelope back up (never down) and lands on a crest.
            for (int k = 0; k < kRumblePeals; ++k) {
                if (peal_samples_[k] < 0) continue;
                if (--peal_samples_[k] > 0) continue;
                peal_samples_[k] = -1;
                if (tail_env_ < peal_level_[k]) { tail_env_ = peal_level_[k]; RestartOnCrest(); }
            }
            const float und = Undulation();
            const float env = (bed_ + tail_env_ * tail_amp_) * und;
            // Body: the tail's envelope to the 2.5th power, so the boom is gone long
            // before the sub is and the roll darkens on its way out.
            const float body_tail = tail_env_ * tail_env_ * std::sqrt(tail_env_) * body_amp_;
            const float body_env = (bed_ * kRumbleBedBody + body_tail) * und;
            // Envelope ahead of the filter: the low-pass turns a strike's instant
            // onset into a few-millisecond swell instead of a step click.
            const float wl = pattern_rand01(noise_rng_[0]) * 2.0f - 1.0f;
            const float wr = pattern_rand01(noise_rng_[1]) * 2.0f - 1.0f;
            body_lp_[0] += (body_[0].Process<stmlib::FILTER_MODE_BAND_PASS_NORMALIZED>(wl * body_env) - body_lp_[0]) * body_top_coef_;
            body_lp_[1] += (body_[1].Process<stmlib::FILTER_MODE_BAND_PASS_NORMALIZED>(wr * body_env) - body_lp_[1]) * body_top_coef_;
            l[i] += lp_[0].Process<stmlib::FILTER_MODE_LOW_PASS>(wl * env) * kRumbleGain + body_lp_[0] * kRumbleBodyGain;
            r[i] += lp_[1].Process<stmlib::FILTER_MODE_LOW_PASS>(wr * env) * kRumbleGain + body_lp_[1] * kRumbleBodyGain;
        }
    }

 private:
    bool peal_pending() const {
        for (int k = 0; k < kRumblePeals; ++k) if (peal_samples_[k] >= 0) return true;
        return false;
    }

    // Re-aim the undulation so the next moments sit on a crest: slowest octave at its
    // top, the rest at zero.
    void RestartOnCrest() {
        lfo_[0].Restart(1.0f);
        for (int k = 1; k < kRumbleOctaves; ++k) lfo_[k].Restart(0.0f);
    }

    // Adapted from Mutable Instruments Plaits (dsp/noise/fractal_random_generator.h),
    // MIT, (c) Emilie Gillet. Deep and strictly non-negative: the roll travels from
    // near-silence to full and back rather than wobbling around one level, and it never
    // flips the bed's sign. Widening the sum before the clip is what makes the roll HOLD
    // at its crests and troughs instead of hovering in the middle of the range.
    float Undulation() {
        float sum = 0.0f, gain = 0.5f, f = kRumbleSlowestHz / sr_;
        for (int k = 0; k < kRumbleOctaves; ++k) {
            sum += lfo_[k].Render(f) * gain;
            gain *= 0.5f;
            f *= 2.0f;
        }
        const float u = storm_clamp01(0.5f + kRumbleUndulationSwing * sum);
        return 1.0f - kRumbleUndulationDepth * (1.0f - u);
    }

    stmlib::Svf lp_[2];
    stmlib::Svf body_[2];
    SmoothRandomLfo lfo_[kRumbleOctaves];
    uint32_t noise_rng_[2] = { 11u, 12u };
    uint32_t peal_rng_ = 13u;
    float sr_ = 48000.0f;
    float bed_ = 0.0f, bed_target_ = 0.0f, bed_slew_ = 0.0f;
    float tail_env_ = 0.0f, tail_amp_ = 0.0f, body_amp_ = 0.0f;
    float body_lp_[2] = { 0.0f, 0.0f }, body_top_coef_ = 1.0f;
    float short_decay_ = 0.0f, tail_decay_ = 0.0f;
    float distance_ = 0.5f;
    int   peal_samples_[kRumblePeals] = { -1, -1 };
    float peal_level_[kRumblePeals] = { 0.0f, 0.0f };
};

// ── Claps ───────────────────────────────────────────────────────────────────
// A lightning crack is a burst of transients, not one hit — and it falls. The
// cascade starts high and steps down in discrete jumps into the rumble. Each hit
// is half-wave-rectified noise through its own band-pass, shaped by a sharp head
// and a tail that lengthens down the staircase, then panned. The tails overlap the
// next onset on purpose: "clap-clap" is carried by the attacks (the first hit's snap
// and every hit's head), and a strike that went silent between steps was four ticks.
// One high-pass on the mix strips the body.
//
// The band-pass alone was a filter ping: one clean resonance on noise, which is the
// textbook synthetic transient and exactly what the ear test called "synthy". A
// parallel GRIT path gives every hit a broadband torn body beside its pitch, a
// crackle modulator rips its amplitude, and a Lorenz stream wanders both the centre
// and the pitch/grit balance so no two cracks are the same shape.

// EAR-TUNE(user reviews; Claude drafted): the rhythm and the pitch staircase of a
// lightning crack.
static constexpr int   kClapCount       = 4;                       // 2..5
static constexpr float kClapSpacingMs[] = {0.f, 32.f, 74.f, 126.f}; // cumulative; gaps widen 32/42/52 ms
// The staircase: one descending step per hit, ending low enough to hand the
// cascade over to the rumble tail. Per hit and NOT a shared draw — a single
// centre for all four is exactly what smeared a strike into one burst.
// The bottom step sits just above the roll's body (kRumbleBodyHz), so the descent
// runs crack -> tear -> boom -> sub without a hole in it.
static constexpr float kClapPitchHz[]   = {3400.f, 1900.f, 950.f, 420.f};
// Each step is a thunder clap, not a filter ping: its tail LENGTHENS as it steps down
// (a lower band rings longer, physically), and the tails overlap the next onset. The
// "clap-clap" reading comes from the onsets — the snap and the head — not from silence
// between hits. 28 ms flat, the old value, made every step a 30 ms tick.
static constexpr float kClapTailMs[]    = {35.f, 55.f, 85.f, 130.f};
static constexpr float kClapGain[]      = {1.f, 0.90f, 0.78f, 0.66f};
static constexpr float kClapPanOffset[] = {0.f, -0.12f, 0.1f, -0.06f};

static_assert(kClapCount == (int)(sizeof(kClapSpacingMs) / sizeof(float)), "spacing table must cover every hit");
static_assert(kClapCount == (int)(sizeof(kClapPitchHz)   / sizeof(float)), "pitch table must cover every hit");
static_assert(kClapCount == (int)(sizeof(kClapTailMs)    / sizeof(float)), "tail table must cover every hit");
static_assert(kClapCount == (int)(sizeof(kClapGain)      / sizeof(float)), "gain table must cover every hit");
static_assert(kClapCount == (int)(sizeof(kClapPanOffset) / sizeof(float)), "pan table must cover every hit");

// EAR-TUNE(clap-snap): the whip crack on the FIRST hit only — about a millisecond of
// plain broadband noise ahead of everything else. It is the single cue that says
// "close", so distance takes it away faster than it takes the cascade (one more
// proximity factor on top of the burst's own square).
static constexpr int   kClapSnapHit     = 0;
static constexpr float kClapSnapMs      = 1.2f;
// Relative to the hit's own gain, which multiplies a band-pass whose output sits well
// under the unit white the snap is made of: 0.4 lands the snap level with the clap
// peaks, the loudest instant of the strike without dwarfing the cascade (1.4 put it
// 14 dB over everything and the claps read as an afterthought).
static constexpr float kClapSnapGain    = 0.4f;

// EAR-TUNE(clap-crackle): the torn-cloth texture. Every hit's noise is multiplied by a
// random ramp (SmoothRandomLfo, the same generator the rumble undulates on) running at
// a few hundred hertz, so its amplitude tears instead of decaying smoothly; a smooth
// noise burst is a synth clap, a ragged one is thunder. Rate sets how coarse the tearing
// is, depth how deep the rips go: at 0.8 the hit swings between 0.2x and 1.8x.
static constexpr float kClapCrackleHz   = 300.0f;
static constexpr float kClapCrackleDepth = 0.8f;

static_assert(kClapSnapHit >= 0 && kClapSnapHit < kClapCount, "the snap has to sit on a real hit");
static_assert(kClapCrackleDepth >= 0.0f && kClapCrackleDepth <= 1.0f, "crackle depth is a fraction");

// The pan gains are sqrt(1 -/+ offset), so an out-of-range ear-tune would render
// NaN rather than sound wrong. Catch it at compile time instead.
constexpr bool clap_pans_in_range() {
    for (int k = 0; k < kClapCount; ++k)
        if (kClapPanOffset[k] < -1.0f || kClapPanOffset[k] > 1.0f) return false;
    return true;
}
static_assert(clap_pans_in_range(), "clap pan offsets must stay within -1..1");

// The bottom step doubles as the level reference: a band-pass on white noise gets
// louder with its centre, so scaling every hit by sqrt(this / centre) is what makes
// the four perceived levels follow kClapGain instead of the staircase. Derived from
// the table so an ear-tune of kClapPitchHz carries the reference with it.
constexpr float clap_lowest_pitch_hz() {
    float lo = kClapPitchHz[0];
    for (int k = 1; k < kClapCount; ++k)
        if (kClapPitchHz[k] < lo) lo = kClapPitchHz[k];
    return lo;
}
static constexpr float kClapNormRefHz   = clap_lowest_pitch_hz();

static constexpr float kClapDistTilt    = 0.45f;     // a distance-1 cascade sits 45% lower
static constexpr float kClapPitchJitter = 0.04f;     // +/-4%: repeat strikes are not mechanical
static constexpr float kClapCenterMinHz = 120.0f;    // clamp only: the shipped cascade stops at ~446
static constexpr float kClapCenterMaxHz = 8000.0f;   // inside FREQUENCY_FAST's 16 Hz-16 kHz fit
// 4.0, down from 6.0. The band-pass used to carry the whole character, so it had to be
// narrow enough to state a pitch; with the grit path below supplying the broadband body
// it only has to colour, and a band 50% wider reads as a crack rather than a tuned ping.
// Band-passed noise scales as sqrt(Q), so this alone drops the cascade ~18%.
static constexpr float kClapQ           = 4.0f;
static constexpr float kClapHeadSeconds = 0.006f;
// 0.55, down from 0.85: with the head carrying 85% of the onset, a hit was a 5 ms tick
// followed by a tail 15 dB under it, whatever length that tail was given. Near half and
// half, the attack is still sharp and the body it hands over to is only 5 dB down, which
// is what makes the tail table above audible at all.
static constexpr float kClapHeadWeight  = 0.55f;     // head + tail = 1 at onset
// 160, down from 220: the bottom step now sits at 420 Hz and darkens to ~230 under a
// distance-1 tilt, and the corner has to stay under that or the last clap is gutted.
// Still one cutoff for every hit, so the single high-pass on the mix in Process()
// is still the per-hit chain rather than an approximation of it.
static constexpr float kClapHpHz        = 160.0f;
// Loudest lever in the burst though not itself in the EAR-TUNE block above (kClapCount et al.).
// 6.4, up from 5.5: dropping kClapQ to 4 and crossfading in the grit together cost the
// cascade about 1.3 dB of peak, and this hands it back. The crack still peaks lower than
// it used to because a broadband burst reads louder than a resonant one at equal peak.
static constexpr float kClapDropGain    = 6.4f;      // closest strike peaks near the storm ceiling
static constexpr float kClapEnvFloor    = 1e-4f;
// A band-pass rings for about Q/(pi*f) seconds, so no single prime length serves a
// staircase spanning 3.8 kHz down to ~446 Hz (the bottom step under the darkest
// tilt and jitter that still clears kClapMinBurst): at 48 kHz those two ends want
// 48 and 412 samples, and the 64 this started at covered only the top. Sized per hit
// from its own centre rather than pinned to the worst case, so retuning
// kClapPitchHz or kClapQ cannot quietly leave the low claps fading in again.
static constexpr float kClapPrimeRings  = 2.0f;      // ring time constants to pre-run
static constexpr int   kClapPrimeMin    = 32;        // floor; the top step already asks for 48
static constexpr int   kClapPrimeMax    = 512;       // ceiling for a retune below the shipped table
// A burst gain below this is past -40 dB: the crack has stopped reading as a
// crack, and letting it ring on only leaves inaudible high-frequency hash.
static constexpr float kClapMinBurst    = 0.01f;

// EAR-TUNE(clap-static): pitch versus static, the dial the "sounds a little synthy" note
// asks for. 0 is the old sound exactly, one narrow resonance per hit; 1 is pure broadband
// tear with no pitch cue left. The two paths are loudness-matched at kClapGritNorm, so
// this is a straight crossfade: raise it for more thunder-hiss, lower it to bring the
// staircase forward. Modulated per sample by the Lorenz stream, so it is a centre value.
static constexpr float kClapGritMix     = 0.45f;
// The grit is the SAME rectified noise the band-pass sees, shaped only by a one-pole
// whose corner rides this multiple of the hit's own centre. Tracking the staircase is
// what keeps the burst falling instead of laying four identical hisses under it.
static constexpr float kClapGritLpRatio = 2.2f;
static constexpr float kClapGritMinHz   = 700.0f;    // floor: the last clap keeps some air
// Half-wave rectified uniform noise carries a mean of 0.25. Subtracting it analytically
// keeps the grit DC-free instead of leaving that job to the shared high-pass.
static constexpr float kClapRectMean    = 0.25f;
// Equal-loudness match between the band-pass path and the grit path at the reference
// centre, so kClapGritMix is a true crossfade and not a level control. Measured as the
// ratio of the two paths' RMS on the shipped staircase; retune kClapGritMix, not this.
static constexpr float kClapGritNorm    = 1.35f;

// EAR-TUNE(clap-chaos): a Lorenz trajectory, this engine's own chaos/lorenz.h kernel,
// used as a MODULATOR and never as a sound source. It wanders each hit's band-pass centre
// and the pitch/grit balance so a strike is never twice the same and never a pure tone.
// Depth is deliberately small; past ~0.2 the centre wander stops reading as grain and
// starts reading as warble.
static constexpr float kClapChaosPitchDepth = 0.10f;  // +/-10% on the centre
// Relative, not absolute, so the wander scales with however much grit is dialled in and
// a zero mix stays exactly zero. 0.40 is +/-18% of the shipped 0.45 crossfade.
static constexpr float kClapChaosMixDepth   = 0.40f;
// Advancing the attractor once per this many samples sets its speed: the kernel's own
// step is fixed, so the stride is the only rate control. 4 puts one orbit near 90 Hz,
// a couple of turns inside a single 30 ms hit, which is grain rather than vibrato.
static constexpr int   kClapChaosStride     = 4;
static constexpr float kClapChaosRho        = 0.37f;  // -> rho ~28, the classic butterfly
static constexpr float kClapChaosRate       = 0.0f;   // -> the kernel's slowest step

static_assert(kClapGritMix >= 0.0f && kClapGritMix <= 1.0f, "the grit mix is a crossfade");
static_assert(kClapChaosStride >= 1, "the attractor has to advance at least once a block");

struct ClapGen {
    void Init(uint32_t seed, float sr) {
        sr_ = sr;
        rng_ = storm_seed(seed, 31u);
        hp_coef_    = 1.0f - std::exp(-2.0f * 3.14159265f * kClapHpHz / sr);
        head_decay_ = std::exp(-1.0f / (kClapHeadSeconds * sr));
        snap_decay_ = std::exp(-1.0f / (kClapSnapMs * 0.001f * sr));
        crackle_f_  = kClapCrackleHz / sr;
        snap_scale_ = 1.0f;
        hp_[0] = 0.0f; hp_[1] = 0.0f;
        grit_ref_gain_ = OnePoleNoiseGain(OnePoleCoef(GritCornerHz(kClapNormRefHz, sr), sr));
        grit_mix_ = kClapGritMix;
        mix_ = kClapGritMix;
        chaos_v_ = 0.0f;
        chaos_count_ = 0;
        // Own RNG draw, salted apart from rng_, so adding the attractor leaves the clap
        // jitter stream exactly where it was. Started well off the origin saddle: the
        // trajectory is already on the attractor by the first strike.
        uint32_t cs = storm_seed(seed, 32u);
        chaos_ = ChaosVoiceState();
        chaos_.x = 6.0f + pattern_rand01(cs) * 8.0f;
        chaos_.y = 6.0f + pattern_rand01(cs) * 8.0f;
        chaos_.z = 15.0f + pattern_rand01(cs) * 20.0f;
        for (int k = 0; k < kClapCount; ++k) {
            Hit& h = hits_[k];
            h.bp.Init();
            h.delay = -1;
            h.head = 0.0f; h.tail = 0.0f;
            h.gain = 0.0f; h.gl = 1.0f; h.gr = 1.0f; h.norm = 1.0f;
            h.center = kClapPitchHz[k];
            h.grit_lp = 0.0f; h.grit_c = 0.5f; h.grit_g = 1.0f;
            h.tail_decay = std::exp(-1.0f / (kClapTailMs[k] * 0.001f * sr));
            h.snap = 0.0f; h.snap_armed = 0.0f;
            h.crackle.Init(storm_seed(seed, 40u + (uint32_t)k));
            h.prime = kClapPrimeMin;
        }
    }

    // Schedules the whole burst. Re-triggering mid-burst restarts every hit.
    void trigger(float intensity, float distance) {
        intensity = storm_clamp01(intensity);
        distance  = storm_clamp01(distance);
        const float proximity = 1.0f - distance;
        const float burst = intensity * proximity * proximity;
        if (burst < kClapMinBurst) return;
        // Distance darkens the whole cascade at once, so the staircase keeps its
        // shape and simply sits lower the further off the strike is.
        const float tilt = 1.0f - kClapDistTilt * distance;
        for (int k = 0; k < kClapCount; ++k) {
            Hit& h = hits_[k];
            h.delay = (int)(kClapSpacingMs[k] * 0.001f * sr_ + 0.5f);
            h.head  = kClapHeadWeight;
            h.tail  = 1.0f - kClapHeadWeight;
            h.gain  = burst * kClapGain[k] * kClapDropGain;
            // Armed here, released at the hit's onset by Prime(): the snap has to start
            // on the same sample the band-pass opens.
            h.snap_armed = k == kClapSnapHit ? h.gain * kClapSnapGain * proximity * snap_scale_ : 0.0f;
            h.snap = 0.0f;
            const float p = kClapPanOffset[k];
            h.gl = std::sqrt(1.0f - p);        // constant power, unity at centre
            h.gr = std::sqrt(1.0f + p);
            // Each step owns its pitch; the jitter only keeps two strikes from
            // landing on identical centres, it never reorders the staircase.
            const float jitter = 1.0f + kClapPitchJitter * (pattern_rand01(rng_) * 2.0f - 1.0f);
            float center = kClapPitchHz[k] * tilt * jitter;
            center = std::min(std::max(center, kClapCenterMinHz), kClapCenterMaxHz);
            h.center = center;                 // base the chaos wander bends around
            h.bp.Reset();
            h.bp.set_f_q<stmlib::FREQUENCY_FAST>(center / sr_, kClapQ);
            // A band-pass on white noise gets louder with its centre; referencing
            // every hit to the bottom step is what leaves kClapGain, and not the
            // staircase, deciding how loud each clap lands.
            h.norm  = std::sqrt(kClapNormRefHz / center);
            // Grit corner rides the same staircase; its level is flattened against the
            // same reference so the crossfade holds at every step.
            const float gc = OnePoleCoef(GritCornerHz(center, sr_), sr_);
            h.grit_c  = gc;
            h.grit_g  = kClapGritNorm * grit_ref_gain_ / OnePoleNoiseGain(gc);
            h.grit_lp = 0.0f;
            h.prime = PrimeCount(center);
            if (h.delay == 0) Prime(h);
        }
    }

    bool active() const {
        for (int k = 0; k < kClapCount; ++k)
            if (hits_[k].delay > 0 || hits_[k].head + hits_[k].tail > kClapEnvFloor) return true;
        return false;
    }

    // ADDS into l/r.
    void Process(float* l, float* r, int n) {
        if (n <= 0) return;
        if (!active()) { hp_[0] = 0.0f; hp_[1] = 0.0f; return; }
        for (int i = 0; i < n; ++i) {
            if (--chaos_count_ <= 0) AdvanceChaos();
            const float band_mix = 1.0f - mix_;
            float sl = 0.0f, sr_out = 0.0f;
            for (int k = 0; k < kClapCount; ++k) {
                Hit& h = hits_[k];
                if (h.delay > 0) { if (--h.delay == 0) Prime(h); continue; }
                const float env = h.head + h.tail;
                if (env <= kClapEnvFloor) { h.head = 0.0f; h.tail = 0.0f; continue; }
                const float white = pattern_rand01(rng_) * 2.0f - 1.0f;
                const float rect  = white > 0.0f ? white : 0.0f;
                // One noise source, two shapings: the narrow resonance carries the pitch,
                // the gently low-passed copy carries the broadband tear.
                const float band = h.bp.Process<stmlib::FILTER_MODE_BAND_PASS>(rect) * h.norm;
                h.grit_lp += (rect - kClapRectMean - h.grit_lp) * h.grit_c;
                // The crackle: a random ramp in -1..1 mapped to 0..1 multiplies the hit.
                // Its mean is 0.5, so x2 keeps the mean gain at unity and the depth only
                // decides how far the rips swing either side of it.
                const float tear = storm_clamp01(0.5f + 0.5f * h.crackle.Render(crackle_f_));
                const float rip = 1.0f - kClapCrackleDepth + kClapCrackleDepth * 2.0f * tear;
                float v = (band * band_mix + h.grit_lp * h.grit_g * mix_) * rip * env * h.gain;
                // The snap: plain white, un-rectified and un-filtered, for a millisecond.
                if (h.snap > 0.0f) {
                    v += white * h.snap;
                    h.snap *= snap_decay_;
                    if (h.snap < kClapEnvFloor) h.snap = 0.0f;
                }
                h.head *= head_decay_;
                h.tail *= h.tail_decay;
                sl     += v * h.gl;
                sr_out += v * h.gr;
            }
            // One high-pass per channel on the mix: the filter is linear and every
            // hit shares its cutoff, so this is the per-hit chain, four times cheaper.
            hp_[0] += (sl     - hp_[0]) * hp_coef_;
            hp_[1] += (sr_out - hp_[1]) * hp_coef_;
            l[i] += sl     - hp_[0];
            r[i] += sr_out - hp_[1];
        }
    }

#ifdef ORPHEUS_TESTING
    // Test-only: the exact per-hit delay (samples until onset) and gain scheduled by
    // the last trigger(), read directly rather than inferred from rendered audio — a
    // band-passed noise hit's envelope fluctuates on its own on a timescale close to
    // a millisecond, which makes sample-accurate onset detection from the waveform
    // unreliable. Compiled in only when BUILD_TESTS=ON, same as the engine's debug
    // peek atomics; zero cost in production builds.
    int   debug_hit_delay(int k) const { return hits_[k].delay; }
    float debug_hit_gain(int k) const { return hits_[k].gain; }
    // Recovered from the band-pass coefficient the hit will actually render
    // through, not from a bookkeeping copy, so a mutant that records per-hit
    // centres while configuring one shared filter still fails the staircase test.
    // atan un-does the FREQUENCY_FAST tan to within ~0.05%, and is monotone in f
    // whatever the fit error, so an ordering assertion on this is exact.
    float debug_hit_center(int k) const {
        return std::atan(hits_[k].bp.g()) * (1.0f / 3.14159265f) * sr_;
    }
    // Test-only override of the crossfade centre so a probe can render the SAME seed
    // with and without the grit layer. The member exists in every build (an
    // ifdef'd field would change the layout between the library and the harness);
    // only this setter is compiled out.
    void debug_set_grit_mix(float m) { grit_mix_ = storm_clamp01(m); mix_ = grit_mix_; }
    // Test-only: the snap level the last trigger() gave hit k. A hit with no delay has
    // already had its onset inside trigger(), so the live level is read in that case.
    float debug_hit_snap(int k) const {
        return hits_[k].snap_armed > 0.0f ? hits_[k].snap_armed : hits_[k].snap;
    }
    // Test-only scale on the snap, so a probe can render the SAME seed with and without
    // it and subtract. Member in every build, setter compiled out, as with the grit mix.
    void debug_set_snap_scale(float s) { snap_scale_ = s < 0.0f ? 0.0f : s; }
#endif

 private:
    struct Hit {
        stmlib::Svf bp;
        int   delay = -1;                    // samples until onset
        int   prime = kClapPrimeMin;         // pre-run length, sized to this hit's centre
        float head = 0.0f, tail = 0.0f;
        float tail_decay = 0.0f;             // per hit: the tail lengthens down the staircase
        float gain = 0.0f, gl = 1.0f, gr = 1.0f, norm = 1.0f;
        float center = 0.0f;                 // base centre, before the chaos wander
        float grit_lp = 0.0f;                // parallel broadband path's one-pole state
        float grit_c = 0.5f, grit_g = 1.0f;  // its coefficient and level match
        float snap = 0.0f, snap_armed = 0.0f; // whip-crack level: live, and waiting for onset
        SmoothRandomLfo crackle;             // the tearing modulator
    };

    // The grit's corner for a hit centred at `center`. The reference used for the level
    // match runs through the same clamps, so the two cannot drift apart under a retune.
    static float GritCornerHz(float center, float sr) {
        return std::min(std::max(center * kClapGritLpRatio, kClapGritMinHz), 0.45f * sr);
    }

    // One-pole coefficient for a corner, and the RMS a white input comes out at through
    // it: |H|^2 integrates to c/(2-c). Flattens the grit across the staircase.
    static float OnePoleCoef(float hz, float sr) {
        const float c = 1.0f - std::exp(-2.0f * 3.14159265f * hz / sr);
        return c < 1e-4f ? 1e-4f : (c > 1.0f ? 1.0f : c);
    }
    static float OnePoleNoiseGain(float c) { return std::sqrt(c / (2.0f - c)); }

    // Advance the Lorenz trajectory one step and re-aim everything it drives. Runs once
    // per kClapChaosStride samples, only while a burst is sounding, so a quiet storm
    // costs nothing. tanh() inside the kernel bounds the output; the finite check is
    // the only guard a diverging integrator would need, and it re-seeds rather than mutes.
    void AdvanceChaos() {
        chaos_count_ = kClapChaosStride;
        chaos_v_ = chaos::process_lorenz(chaos_, kClapChaosRho, kClapChaosRate, 0.0f, sr_);
        if (!std::isfinite(chaos_v_)) {
            chaos_ = ChaosVoiceState();
            chaos_.x = 8.0f; chaos_.z = 20.0f;
            chaos_v_ = 0.0f;
        }
        const float bend = 1.0f + kClapChaosPitchDepth * chaos_v_;
        for (int k = 0; k < kClapCount; ++k) {
            float c = hits_[k].center * bend;
            c = std::min(std::max(c, kClapCenterMinHz), kClapCenterMaxHz);
            hits_[k].bp.set_f_q<stmlib::FREQUENCY_FAST>(c / sr_, kClapQ);
        }
        mix_ = storm_clamp01(grit_mix_ * (1.0f + kClapChaosMixDepth * chaos_v_));
    }

    // Samples of pre-run needed for a band-pass at `center` to reach steady state:
    // kClapPrimeRings time constants of Q/(pi*f), bounded at both ends.
    int PrimeCount(float center) const {
        const int n = (int)(kClapPrimeRings * kClapQ * sr_ / (3.14159265f * center) + 0.5f);
        return n < kClapPrimeMin ? kClapPrimeMin : (n > kClapPrimeMax ? kClapPrimeMax : n);
    }

    // Ring the band-pass up before the envelope opens. Opening on a silent filter
    // fades the hit in over a quarter cycle, which reads as a smear, not a crack.
    void Prime(Hit& h) {
        for (int i = 0; i < h.prime; ++i) {
            const float white = pattern_rand01(rng_) * 2.0f - 1.0f;
            const float rect = white > 0.0f ? white : 0.0f;
            h.bp.Process<stmlib::FILTER_MODE_BAND_PASS>(rect);
            h.grit_lp += (rect - kClapRectMean - h.grit_lp) * h.grit_c;
        }
        h.crackle.Restart(0.0f);             // the modulator's mean: no rip on the onset sample
        h.snap = h.snap_armed;
        h.snap_armed = 0.0f;
    }

    Hit hits_[kClapCount];
    ChaosVoiceState chaos_;
    uint32_t rng_ = 31u;
    float sr_ = 48000.0f;
    float hp_[2] = { 0.0f, 0.0f };
    float hp_coef_ = 0.0f, head_decay_ = 0.0f, snap_decay_ = 0.0f, crackle_f_ = 0.0f;
    float snap_scale_ = 1.0f;            // only a test probe moves it
    float grit_ref_gain_ = 1.0f;
    float grit_mix_ = kClapGritMix;      // crossfade centre; only a test probe moves it
    float mix_ = kClapGritMix;           // that centre plus the current chaos wander
    float chaos_v_ = 0.0f;
    int   chaos_count_ = 0;
};

// ── Clap echo ───────────────────────────────────────────────────────────────
// A crack that stops dead has no landscape around it. This is terrain reflection, not a
// tempo effect: five irregularly spaced taps off one mono line, NO feedback, so the tail
// is bounded by the longest tap by construction and cannot run away whatever the taps
// are retuned to. Distance is the whole dial, and the right way round: a bolt directly
// overhead barely echoes, a distant one answers off the hills for a quarter second.

static constexpr int kClapEchoTaps = 5;
// EAR-TUNE(strike-echo): reflection times in ms. Deliberately non-harmonic. Evenly
// spaced taps comb, and a doubling chain reads as a tempo-synced delay rather than as
// ground. The pans are small and alternating: a reflection arrives off to one side.
static constexpr float kClapEchoTapMs[]   = {37.f, 71.f, 118.f, 179.f, 247.f};
static constexpr float kClapEchoTapGain[] = {0.60f, 0.42f, 0.28f, 0.17f, 0.09f};
static constexpr float kClapEchoTapPan[]  = {-0.34f, 0.29f, -0.19f, 0.41f, -0.12f};
// One MONO line, summed to both channels: a stereo pair would double the memory to buy
// width the per-tap pans already supply. 12288 floats is 48 KB and 256 ms at 48 kHz,
// which clears the longest tap with room to spare. Sized for 48 kHz on purpose: a host
// running faster gets a proportionally shorter echo (the tap delays clamp) rather than a
// bigger buffer, which is the trade this length was picked for.
static constexpr int kClapEchoLine = 12288;
// EAR-TUNE(strike-echo-distance): how much of the cascade comes back, and how far down
// the tap list it survives. Overhead is a whisper that dies on the first reflection; far
// off is most of the cascade, all five taps, and the longest tail.
static constexpr float kClapEchoNearMix   = 0.05f;
static constexpr float kClapEchoFarMix    = 0.85f;
static constexpr float kClapEchoNearDecay = 0.40f;   // gain step per tap at distance 0
static constexpr float kClapEchoFarDecay  = 1.00f;   // gain step per tap at distance 1

static_assert(kClapEchoTaps == (int)(sizeof(kClapEchoTapMs)   / sizeof(float)), "tap ms table");
static_assert(kClapEchoTaps == (int)(sizeof(kClapEchoTapGain) / sizeof(float)), "tap gain table");
static_assert(kClapEchoTaps == (int)(sizeof(kClapEchoTapPan)  / sizeof(float)), "tap pan table");
static_assert(kClapEchoNearDecay > 0.0f && kClapEchoFarDecay <= 1.0f,
              "a per-tap step above 1 would grow the tail instead of decaying it");
static_assert(kClapEchoFarMix > kClapEchoNearMix && kClapEchoFarDecay > kClapEchoNearDecay,
              "distance has to buy MORE echo and a LONGER one, not less");

// Same square-root pan form the claps use, so an out-of-range ear-tune is a compile
// error rather than a NaN, and the longest tap has to fit the line at 48 kHz.
constexpr bool clap_echo_taps_in_range() {
    for (int t = 0; t < kClapEchoTaps; ++t) {
        if (kClapEchoTapPan[t] < -1.0f || kClapEchoTapPan[t] > 1.0f) return false;
        if (kClapEchoTapMs[t] * 48.0f >= (float)(kClapEchoLine - 1)) return false;
        if (t > 0 && kClapEchoTapMs[t] <= kClapEchoTapMs[t - 1]) return false;
    }
    return true;
}
static_assert(clap_echo_taps_in_range(), "echo taps must ascend, pan in -1..1, and fit the line");

struct ClapEcho {
    void Init(float sr) {
        write_ = 0;
        idle_ = 0;
        for (int i = 0; i < kClapEchoLine; ++i) line_[i] = 0.0f;
        for (int t = 0; t < kClapEchoTaps; ++t) {
            int d = (int)(kClapEchoTapMs[t] * 0.001f * sr + 0.5f);
            delay_[t] = d < 1 ? 1 : (d > kClapEchoLine - 1 ? kClapEchoLine - 1 : d);
        }
        set_distance(0.5f);
    }

    // Latched once per strike: the echo belongs to the bolt that fed it, and the only
    // moment the tap gains move is under the next crack's own transient.
    void set_distance(float distance) {
        distance = storm_clamp01(distance);
        const float amount = kClapEchoNearMix +
                             (kClapEchoFarMix - kClapEchoNearMix) * distance;
        const float decay  = kClapEchoNearDecay +
                             (kClapEchoFarDecay - kClapEchoNearDecay) * distance;
        float step = 1.0f;
        for (int t = 0; t < kClapEchoTaps; ++t) {
            const float g = kClapEchoTapGain[t] * step * amount;
            const float p = kClapEchoTapPan[t];
            gl_[t] = g * std::sqrt(1.0f - p);
            gr_[t] = g * std::sqrt(1.0f + p);
            step *= decay;
        }
    }

    // True while the line can still emit. The countdown is reset by any non-zero input
    // and runs a full line length, so at zero every tap is reading an exact zero.
    bool active() const { return idle_ > 0; }

    // Reads the dry claps already sitting in l/r and ADDS its reflections back in.
    // Must run before anything else mixes into the buffer or it would echo the weather.
    void Process(float* l, float* r, int n) {
        for (int i = 0; i < n; ++i) {
            const float in = 0.5f * (l[i] + r[i]);
            if (in != 0.0f) idle_ = kClapEchoLine;
            else if (idle_ > 0) --idle_;
            else continue;                       // the whole line is exact zeros
            line_[write_] = in;
            float ol = 0.0f, orr = 0.0f;
            for (int t = 0; t < kClapEchoTaps; ++t) {
                int rd = write_ - delay_[t];
                if (rd < 0) rd += kClapEchoLine;
                const float s = line_[rd];
                ol  += s * gl_[t];
                orr += s * gr_[t];
            }
            if (++write_ >= kClapEchoLine) write_ = 0;
            l[i] += ol;
            r[i] += orr;
        }
    }

 private:
    float line_[kClapEchoLine] = {};
    float gl_[kClapEchoTaps] = {}, gr_[kClapEchoTaps] = {};
    int   delay_[kClapEchoTaps] = {};
    int   write_ = 0;
    int   idle_ = 0;
};

// ── StormVoice ──────────────────────────────────────────────────────────────

static constexpr int   kStormChunk       = 256;    // scratch size; Process() splits n
static constexpr float kStormOutGain     = 0.62f;  // worst-case rain+rumble peaks ~1.37 pre-limit
static constexpr float kStormSilenceEps  = 1e-5f;
// EAR-TUNE(storm-send-brightness): how bright the storm arrives at the reverb. Every
// per-track send is darkened by a one-pole before it reaches the bus; the storm alone
// went in flat, and a wide-band bed pushed into a reverb undarkened is what reads as
// static rather than as weather. Same 0.1 + 0.9b mapping the tracks use, but deliberately
// below their 0.5 default (~6.1 kHz): the drops now live at 600-4500 Hz, so a corner up
// there removes almost nothing. 0.25 is ~3 kHz — the wash goes distant and dull while the
// dry drops stay crisp in front of it. Dry path is untouched. Raise for a brighter wash.
static constexpr float kStormSendBrightness = 0.25f;
static constexpr float kStormSendLpCoeff    = 0.1f + kStormSendBrightness * 0.9f;
static_assert(kStormSendLpCoeff > 0.0f && kStormSendLpCoeff <= 1.0f,
              "a one-pole coefficient outside 0..1 rings or runs away");
static constexpr float kStormDistSeconds = 0.06f;  // glide between bed and strike distance
// Below this the rumble tail is past -40 dB and strike_active() must not hold the storm
// open for it: RumbleGen's envelope legitimately runs on for seconds after the roll has
// faded out, and a strike that is still "active" is a strike that blocks the next one.
// Read against the tail's LIVE level, not latched at trigger, so a roll that has decayed
// past audibility releases the voice at that moment rather than at the envelope's end.
static constexpr float kStormTailAudible = 0.01f;
// Delayed strikes in flight at once. One flip can author kMaxPendingFx effects
// (pulsar_transition_fx.h), so that is the whole reachable demand; an undelayed strike
// never takes a slot.
static constexpr int   kMaxQueuedStrikes = 4;
// Delay ceiling, mirrored as StrikeEffect.MAX_DELAY_MS in Kotlin (which rejects longer);
// clamped rather than trusted here because the wire is a raw float.
static constexpr float kStrikeMaxDelayMs = 2000.0f;

struct StormVoice {
    void Init(uint32_t seed, float sample_rate) {
        sr_ = sample_rate;
        rain_.Init(storm_seed(seed, 101u), sample_rate);
        rumble_.Init(storm_seed(seed, 102u), sample_rate);
        claps_.Init(storm_seed(seed, 103u), sample_rate);
        echo_.Init(sample_rate);            // clears the line: a reload cannot ring old audio
        bed_rain_ = 0.0f; bed_rain_level_ = 0.0f; bed_rumble_ = 0.0f; bed_distance_ = 0.5f;
        strike_distance_ = 0.5f;
        distance_ = 0.5f;
        dist_slew_ = 1.0f - std::exp(-(float)kStormChunk / (kStormDistSeconds * sample_rate));
        pending_tail_ = -1;
        pending_intensity_ = 0.0f;
        for (int k = 0; k < kMaxQueuedStrikes; ++k) queued_[k] = QueuedStrike{};
        send_lp_[0] = 0.0f; send_lp_[1] = 0.0f;
        settled_ = true;
        rain_.set_level(0.0f);
        rain_.set_gain(0.0f);
        rumble_.set_bed(0.0f, distance_);
    }

    // `rain` is the drop RATE, `rain_level` the loudness of the whole rain layer; either
    // at zero is a silent bed, which is why the guards below read their product.
    void set_bed(float rain, float rain_level, float rumble, float distance) {
        bed_rain_       = storm_clamp01(rain);
        bed_rain_level_ = storm_clamp01(rain_level);
        bed_rumble_     = storm_clamp01(rumble);
        bed_distance_   = storm_clamp01(distance);
        rain_.set_level(bed_rain_);
        rain_.set_gain(bed_rain_level_);
        if (audible_rain() > 0.0f || bed_rumble_ > 0.0f) settled_ = false;
    }

    // Claps fire now; the rumble tail follows 30-120 ms later, further out the
    // more distant the strike. The distance glide starts here so the tail's
    // darkening is already in place by the time it sounds.
    //
    // `delay_ms` parks the whole strike that far into the future instead — the sub-bar
    // spacing that lets a vibe author two strikes as a sequence rather than a collision.
    // Zero takes the immediate path below unchanged.
    void trigger_strike(float intensity, float distance, float delay_ms = 0.0f) {
        if (delay_ms > 0.0f) { QueueStrike(intensity, distance, delay_ms); return; }
        FireStrike(intensity, distance);
    }

    bool strike_active() const {
        return strike_queued() || pending_tail_ >= 0 || claps_.active() ||
               rumble_.tail_level() > kStormTailAudible;
    }

    // A queued strike has not sounded yet, so strike_active() has to report it: the
    // !strike_active() guards on the anomaly and per-bar weather paths would otherwise
    // fire straight over one still counting down and drop it.
    bool strike_queued() const {
        for (int k = 0; k < kMaxQueuedStrikes; ++k)
            if (queued_[k].samples >= 0) return true;
        return false;
    }

    // True once the generators have rendered themselves down to silence. Paired with a
    // zero bed and no strike this is exactly Process()'s own early-out condition, which
    // lets a host skip clearing and mixing buffers it would only add zeros from.
    bool settled() const { return settled_; }

    // Darken one already-rendered output sample for an effect send. The state lives here
    // because the host's send tap is per-sample and post-void; the dry mix never sees it.
    void DarkenSend(float in_l, float in_r, float* out_l, float* out_r) {
        send_lp_[0] += kStormSendLpCoeff * (in_l - send_lp_[0]);
        send_lp_[1] += kStormSendLpCoeff * (in_r - send_lp_[1]);
        *out_l = send_lp_[0];
        *out_r = send_lp_[1];
    }

    // The rain layer's effective drive: rate times loudness. Zero in either is a silent
    // layer, so a host's idle check has to read this rather than the rate alone.
    float audible_rain() const { return bed_rain_ * bed_rain_level_; }

#ifdef ORPHEUS_TESTING
    // Test-only: render the same seed with and without the terrain echo, so a probe can
    // subtract the two and hold the reflections alone. The flag lives in every build
    // (same layout rule as debug_set_grit_mix); only the setter is compiled out.
    void debug_set_echo_enabled(bool on) { echo_enabled_ = on; }
#endif

    // ADDS into l/r, soft-limited over its own contribution only.
    void Process(float* l, float* r, int n) {
        if (n <= 0) return;
        if (settled_ && audible_rain() <= 0.0f && bed_rumble_ <= 0.0f && !strike_active()) return;
        for (int off = 0; off < n; ) {
            int m = (n - off < kStormChunk) ? (n - off) : kStormChunk;
            // Cut the chunk short at the next queued strike so its claps start on the
            // authored sample rather than the next kStormChunk boundary. With an empty
            // queue this is the plain kStormChunk split, unchanged. A short chunk still
            // takes one dist_slew_ step, so the 60 ms distance glide runs marginally
            // quicker across the one block a strike lands in — well under audibility.
            const int until = samples_until_strike();
            if (until > 0 && until < m) m = until;
            RenderChunk(l + off, r + off, m);
            off += m;
        }
    }

 private:
    void RenderChunk(float* l, float* r, int n) {
        FireDueStrikes(n);
        if (pending_tail_ >= 0) {
            pending_tail_ -= n;
            if (pending_tail_ < 0) rumble_.trigger_tail(pending_intensity_, strike_distance_);
        }
        // trigger_tail() overwrites the distance the bed's filter shares, so the
        // bed's own value is re-asserted here every chunk once the tail is done.
        const float target = pending_tail_ >= 0 || rumble_.tail_level() > kStormTailAudible
                                 ? strike_distance_ : bed_distance_;
        distance_ += (target - distance_) * dist_slew_;
        rumble_.set_bed(bed_rumble_, distance_);

        // Claps first and alone, so the echo's input is the cascade and nothing else:
        // reflecting the rain and the roll as well would just be a reverb on the bed.
        for (int i = 0; i < n; ++i) { scratch_l_[i] = 0.0f; scratch_r_[i] = 0.0f; }
        claps_.Process(scratch_l_, scratch_r_, n);
        if (echo_enabled_) echo_.Process(scratch_l_, scratch_r_, n);
        rain_.Process(scratch_l_, scratch_r_, n);
        rumble_.Process(scratch_l_, scratch_r_, n);

        float peak = 0.0f;
        for (int i = 0; i < n; ++i) {
            const float a = scratch_l_[i] * kStormOutGain;
            const float b = scratch_r_[i] * kStormOutGain;
            peak = std::max(peak, std::max(std::fabs(a), std::fabs(b)));
            l[i] += stmlib::SoftClip(a);
            r[i] += stmlib::SoftClip(b);
        }
        // echo_.active() is in here and NOT in strike_active(): a ringing reflection must
        // not let the voice early-out from under it, but it is also far shorter than the
        // rumble tail and has no business blocking the next bolt.
        if (peak < kStormSilenceEps && audible_rain() <= 0.0f && bed_rumble_ <= 0.0f &&
            !strike_active() && !echo_.active())
            settled_ = true;
    }

    // The immediate path, unchanged: claps now, one rumble tail 30-120 ms behind them.
    void FireStrike(float intensity, float distance) {
        intensity = storm_clamp01(intensity);
        strike_distance_ = storm_clamp01(distance);
        claps_.trigger(intensity, strike_distance_);
        echo_.set_distance(strike_distance_);
        pending_intensity_ = intensity;
        pending_tail_ = (int)((30.0f + 90.0f * strike_distance_) * 0.001f * sr_ + 0.5f);
        settled_ = false;
    }

    // Park a strike on a sample countdown, the same shape as pending_tail_. Fixed slots,
    // no allocation; a full queue drops the newest, which authoring makes unreachable
    // (kMaxPendingFx caps a flip at exactly kMaxQueuedStrikes effects).
    void QueueStrike(float intensity, float distance, float delay_ms) {
        if (delay_ms > kStrikeMaxDelayMs) delay_ms = kStrikeMaxDelayMs;
        const int samples = (int)(delay_ms * 0.001f * sr_ + 0.5f);
        if (samples <= 0) { FireStrike(intensity, distance); return; }
        for (int k = 0; k < kMaxQueuedStrikes; ++k) {
            if (queued_[k].samples >= 0) continue;
            queued_[k].samples   = samples;
            queued_[k].intensity = storm_clamp01(intensity);
            queued_[k].distance  = storm_clamp01(distance);
            settled_ = false;
            return;
        }
    }

    // Fire every strike whose countdown has run out, then advance the rest by this chunk.
    // A later strike RE-ARMS the one rumble tail rather than stacking a second: an authored
    // pair is meant to read as clap, clap, then a single roll. That is the decision, not a
    // limitation of the queue. Countdowns floor at 0 so a chunk longer than the remaining
    // wait (Process shortens them, but nothing here relies on it) still fires, one chunk late.
    void FireDueStrikes(int n) {
        for (int k = 0; k < kMaxQueuedStrikes; ++k) {
            QueuedStrike& q = queued_[k];
            if (q.samples < 0) continue;
            if (q.samples == 0) {
                q.samples = -1;
                FireStrike(q.intensity, q.distance);
            } else {
                q.samples = q.samples > n ? q.samples - n : 0;
            }
        }
    }

    // Samples until the next queued strike is due, or -1 when none is waiting.
    int samples_until_strike() const {
        int next = -1;
        for (int k = 0; k < kMaxQueuedStrikes; ++k) {
            const int s = queued_[k].samples;
            if (s <= 0) continue;
            if (next < 0 || s < next) next = s;
        }
        return next;
    }

    struct QueuedStrike {
        int   samples = -1;                  // countdown to fire; negative = free slot
        float intensity = 0.0f, distance = 0.5f;
    };

    RainGen   rain_;
    RumbleGen rumble_;
    ClapGen   claps_;
    ClapEcho  echo_;
    QueuedStrike queued_[kMaxQueuedStrikes];
    float scratch_l_[kStormChunk] = {};
    float scratch_r_[kStormChunk] = {};
    float sr_ = 48000.0f;
    float bed_rain_ = 0.0f, bed_rain_level_ = 0.0f, bed_rumble_ = 0.0f, bed_distance_ = 0.5f;
    float strike_distance_ = 0.5f, distance_ = 0.5f, dist_slew_ = 0.0f;
    float pending_intensity_ = 0.0f;
    float send_lp_[2] = { 0.0f, 0.0f };
    int   pending_tail_ = -1;
    bool  settled_ = true;
    bool  echo_enabled_ = true;          // only a test probe clears it
};

}  // namespace storm
