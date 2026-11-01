// Cowbell knob mapping and strike packing. Standalone: no engine includes, so the Kotlin
// parity tests and the unit can share it. Model numbers are the Rings resonator models.
#pragma once

#include "orpheus_float_checks.h"

#include <cmath>
#include <cstdint>

// Makeup after the resonator, whose limiter keeps it quiet: a default tap then sits by the
// 808 hat at the Orpheus master. Applied with LEVEL.
constexpr float kCowbellOutputGain = 4.0f;

struct CowbellParams {
    int model;       // 0 modal, 1 sympathetic, 2 string, 3 FM
    int polyphony;   // 1, 2 or 4
    float structure, brightness, damping, strike_gain;
};

namespace cowbell_detail {
// NaN-safe clamp to 0..1 (NaN maps to 0).
inline float unit(float x) { return x > 0.0f ? (x < 1.0f ? x : 1.0f) : 0.0f; }
}  // namespace cowbell_detail

// METAL picks one of five 0.2-wide segments and interpolates structure inside it.
inline CowbellParams cowbell_macros(float metal, float ring, float intensity) {
    using cowbell_detail::unit;
    static const int kModel[5] = {2, 1, 2, 0, 3};
    static const float kFrom[5] = {0.27f, 0.15f, 0.55f, 0.30f, 0.30f};
    static const float kTo[5] = {0.45f, 0.60f, 0.90f, 0.75f, 0.90f};

    metal = unit(metal);
    ring = unit(ring);
    intensity = unit(intensity);

    const float pos = metal * 5.0f;
    int seg = (int)pos;
    if (seg > 4) seg = 4;
    const float t = pos - (float)seg;

    CowbellParams p;
    p.model = kModel[seg];
    p.structure = kFrom[seg] + (kTo[seg] - kFrom[seg]) * t;
    p.polyphony = ring < 0.4f ? 1 : (ring < 0.8f ? 2 : 4);
    const float damping = 0.35f + 0.6f * ring;
    p.damping = damping < 0.95f ? damping : 0.95f;
    const float brightness = 0.30f + 0.60f * intensity - 0.15f * ring;
    p.brightness = brightness < 0.0f ? 0.0f : (brightness > 0.9995f ? 0.9995f : brightness);
    p.strike_gain = 0.35f + 0.65f * intensity;
    return p;
}

// Strike word: valid(18) | kind(14..17) | color(7..13) | vel7(0..6). Below 2^24, so it
// travels exactly as a float.
constexpr uint32_t kCowbellStrikeValid = 1u << 18;

struct CowbellStrike { float velocity; int kind; int color; };  // kind 0 = engine hit

// Velocity clamps to 0..1 before scaling: +inf is the loudest strike, NaN and -inf the softest.
inline uint32_t cowbell_encode_strike(float velocity, int kind, int color) {
    const float vel = orpheus_is_finite(velocity) ? cowbell_detail::unit(velocity)
                                                  : (orpheus_is_pos_inf(velocity) ? 1.0f : 0.0f);
    long v = std::lroundf(vel * 127.0f);
    if (v < 1) v = 1;
    if (kind < 0) kind = 0;
    if (kind > 15) kind = 15;
    if (color < 0) color = 0;
    if (color > 127) color = 127;
    return kCowbellStrikeValid | ((uint32_t)kind << 14) | ((uint32_t)color << 7) | (uint32_t)v;
}

inline bool cowbell_decode_strike(uint32_t packed, CowbellStrike* out) {
    if (!(packed & kCowbellStrikeValid)) return false;
    out->velocity = (float)(packed & 0x7Fu) / 127.0f;
    out->kind = (int)((packed >> 14) & 0xFu);
    out->color = (int)((packed >> 7) & 0x7Fu);
    return true;
}

// Port value to strike word; NaN, negative and out-of-range values give 0 (ignored).
inline uint32_t cowbell_strike_from_port(float value) {
    if (!orpheus_is_finite(value) || !(value >= 1.0f) || value >= 524288.0f) return 0u;
    return (uint32_t)value;
}

// Fill kinds (kind - 1 is the Kotlin CowbellFill ordinal): MARBLE, BEADS, SEEDS, JINGLES. Each
// hits the resonator softer or harder and, but for MARBLE, adds a band-passed noise burst
// into the resonator's input. `dry` is the burst's share that also goes straight to the output.
// Kind 0 (a body hit) and kinds past JINGLES have no burst.
constexpr int kCowbellFillMarble = 1;
constexpr int kCowbellFillJingles = 4;

struct CowbellFillSpec {
    float strike_scale;   // times the strike's velocity, for the resonator impulse
    float centre_lo_hz;   // band-pass centre at colour 1 (or the random range's floor)
    float centre_hi_hz;   // ... and at colour 127 (or the random range's ceiling)
    bool random_centre;   // a per-hit random centre in the range, not the hit's colour
    float q;
    float seconds;        // burst length, cubic decay; 0 = no burst
    float dry;            // 0..1
};

inline const CowbellFillSpec& cowbell_fill_spec(int kind) {
    static const CowbellFillSpec kSpecs[5] = {
        {1.0f, 0.0f, 0.0f, false, 1.0f, 0.0f, 0.0f},        // body hit, or a kind with no fill
        {1.0f, 0.0f, 0.0f, false, 1.0f, 0.0f, 0.0f},        // MARBLE: rings like a tap
        {0.35f, 1600.0f, 2500.0f, false, 6.0f, 0.040f, 0.3f},   // BEADS
        {0.15f, 5000.0f, 8000.0f, true, 2.5f, 0.030f, 0.5f},    // SEEDS
        {0.3f, 7500.0f, 9000.0f, false, 7.0f, 0.090f, 0.4f},    // JINGLES
    };
    return kSpecs[kind >= kCowbellFillMarble && kind <= kCowbellFillJingles ? kind : 0];
}

// A hit's brightness 0..1 from its colour. Colour 0 is "none": the middle, which offsets nothing.
inline float cowbell_hit_brightness(int color) {
    if (color < 1) return 0.5f;
    return (float)((color > 127 ? 127 : color) - 1) / 126.0f;
}

// Offset to the resonator's BRIGHTNESS for one hit, -0.15..+0.15; 0 for colour 0.
inline float cowbell_hit_brightness_offset(int color) {
    return color < 1 ? 0.0f : (cowbell_hit_brightness(color) - 0.5f) * 0.3f;
}
