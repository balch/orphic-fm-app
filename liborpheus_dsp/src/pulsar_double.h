#pragma once

// Detuned double: a Pulsar track's engine rendered a second time, detuned apart, the pair
// summed to an equal-power mid and side. See the render in orpheus_unit_pulsar.cpp.

#include <cmath>
#include "orpheus_voice.h"
#include "orpheus_unit_chaos.h"
#include "pulsar_osc.h"
#include "braids_resampler.h"
#include "braids/macro_oscillator.h"

// The second voice set a doubled track renders with. Every family but Mist.
struct PulsarTrackDouble {
    OrpheusVoice voice;
    braids::MacroOscillator braids_voice;
    BraidsResampler braids_resampler;
    ChaosVoiceState chaos_state;
    PulsarOscState osc_state;
    OrpheusLpg osc_lpg;
};

constexpr float kDoubleDetuneMaxCents = 50.0f;
// A live change of detune or spread glides over this time constant.
constexpr float kDoubleGlideSeconds = 0.05f;

// Equal-power mid and side gains for a spread in 0..1: mid = mid_gain * (A + B) and
// side = side_gain * (A - B). Spread 1 at centre pan leaves each voice on its own side.
inline void double_gains(float spread, float& mid_gain, float& side_gain) {
    const float n = 1.0f / std::sqrt(1.0f + spread * spread);
    mid_gain = 0.70710678f * n;
    side_gain = 0.5f * n * spread;
}

// The side through a balance control: per-channel weights from the track's pan gains, so
// the pair pans like a stereo source. Unity at centre pan.
inline void double_side_weights(float pan_l, float pan_r, float& w_l, float& w_r) {
    w_l = pan_l / 0.70710678f;
    w_r = pan_r / 0.70710678f;
}
