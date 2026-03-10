#pragma once

// OrpheusResonator: Modal resonator and Karplus-Strong string synthesis.
// Ported from Kotlin ModalResonator.kt and ResonatorString.kt.

#include <cmath>
#include <cstring>
#include <algorithm>

static constexpr int kOrpheusMaxModes = 64;
static constexpr int kOrpheusDelayLineSize = 2400;
static constexpr float kOrpheusPiF = 3.1415926535f;

// ── Inline helpers ──────────────────────────────────────────────

static inline float lut_interpolate(const float* table, float index, int size) {
    index *= static_cast<float>(size);
    int idx = static_cast<int>(index);
    if (idx < 0) idx = 0;
    if (idx >= size) idx = size - 1;
    float frac = index - static_cast<float>(idx);
    return table[idx] + frac * (table[idx + 1] - table[idx]);
}

static inline float soft_clip(float x) {
    float ax = std::fabs(x);
    if (ax < 0.5f) return x;
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float offset = ax - 0.5f;
    float saturated = 0.5f + offset / (1.0f + offset * offset);
    return sign * std::min(saturated, 1.0f);
}

static inline float semitones_to_ratio(float s) {
    return std::pow(2.0f, s / 12.0f);
}

// ── SVFilter (ZDF State Variable Filter) ────────────────────────

struct SVFilter {
    float g, r, h;
    float state1, state2;

    void init() {
        set_fq(0.01f, 100.0f);
        reset();
    }

    void reset() {
        state1 = 0.0f;
        state2 = 0.0f;
    }

    void set_fq(float f, float resonance) {
        float frequency = std::max(0.0f, std::min(f, 0.497f));
        g = std::tan(kOrpheusPiF * frequency);
        r = 1.0f / resonance;
        h = 1.0f / (1.0f + r * g + g * g);
    }

    float process_bp(float input) {
        float hp = (input - r * state1 - g * state1 - state2) * h;
        float bp = g * hp + state1;
        state1 = g * hp + bp;
        float lp = g * bp + state2;
        state2 = g * bp + lp;
        return bp;
    }
};

// ── DampingFilter ───────────────────────────────────────────────

struct DampingFilter {
    float x_, x__;
    float brightness, damping;

    void init() {
        x_ = 0.0f;
        x__ = 0.0f;
        brightness = 0.0f;
        damping = 0.0f;
    }

    void reset() {
        x_ = 0.0f;
        x__ = 0.0f;
    }

    void configure(float d, float b) {
        damping = d;
        brightness = b;
    }

    float process(float x) {
        float h0 = (1.0f + brightness) * 0.5f;
        float h1 = (1.0f - brightness) * 0.25f;
        float y = damping * (h0 * x_ + h1 * (x + x__));
        x__ = x_;
        x_ = x;
        return y;
    }
};

// ── Lookup tables (defined in orpheus_resonator.cpp) ────────────

extern const float kOrpheusLutStiffness[257];
extern const float kOrpheusLut4Decades[257];

// ── OrpheusModalResonator ───────────────────────────────────────

struct OrpheusModalResonator {
    SVFilter filters[kOrpheusMaxModes];

    float frequency;   // normalized Hz/sr
    float structure;
    float brightness;
    float damping;
    float position;
    int   resolution;

    float out_odd;
    float out_even;

    void init();
    void reset();
    void process(float input);
};

// ── OrpheusResonatorString ──────────────────────────────────────

struct OrpheusResonatorString {
    float delay_line[kOrpheusDelayLineSize];
    int   write_pos;

    DampingFilter damping_filter;

    float dc_blocker_state;
    float dc_blocker_coeff;

    float frequency;   // normalized Hz/sr
    float brightness;
    float damping;
    float position;

    // Internal smoothed state
    float delay;
    float clamped_position;

    float out_main;
    float out_aux;

    void init(float sample_rate);
    void reset();
    void process(float input, float sample_rate);

private:
    float read_delay(float delay_samples) const;
    void  write_delay(float sample);
    float dc_block(float input);
};

// ── OrpheusResonator (top-level combiner) ───────────────────────

struct OrpheusResonator {
    OrpheusModalResonator modal;
    OrpheusResonatorString string;
    float sample_rate;

    float out_l;
    float out_r;

    void init(float sr);
    void reset();
    void strum(float freq_hz, float sr);
    void process(float input, int mode);
};
