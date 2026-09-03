#pragma once
#include <cmath>

// Per-track breathe cycle: a bar-clocked swell that sinks a track's gain toward a
// floor and closes its tone with it, then rises back. Authored per section as a
// track override (breathe_bars / breathe_floor / breathe_timbre_span) and consumed
// in the pulsar unit's per-track render path.
//
// bars == 0 is OFF, and off must be EXACTLY unity: the caller skips the audio path
// rather than multiplying by a computed 1.0, so a vibe with no breathe renders
// bit-identically to a build without the feature.
//
// The phase is bar-clocked (one step per elapsed bar, restarting at 0 on section
// entry), so the envelope below is a staircase; the caller one-poles the applied
// gain per sample to keep the bar steps from clicking.

namespace orpheus {

// Full-scale time constant for the applied-gain one-pole. Long enough that a bar
// step reads as a swell rather than a gate, short enough to settle well inside one
// bar at any playable tempo.
static constexpr float kBreatheSmoothingSeconds = 0.20f;

// One-pole coefficient for kBreatheSmoothingSeconds at this rate. The exact form is
// 1 - exp(-1/(tau*sr)); tau*sr is in the thousands here, where the two agree to five
// decimal places, so this stays off the audio thread's transcendental path.
inline float breathe_smoothing_coeff(float sample_rate) {
    const float n = kBreatheSmoothingSeconds * sample_rate;
    return n > 1.0f ? 1.0f / n : 1.0f;
}

// Cycle position for a bar counted from section entry. 0 is the TOP of the swell.
// The clock is bars, so a cycle only ever visits `bars` positions: bars 1 samples
// phase 0 forever and is armed-but-static. A within-bar swell would need a phase
// that advances per block, not per bar.
inline float breathe_phase(int bars_since_entry, int bars) {
    if (bars <= 0) return 0.0f;
    int m = bars_since_entry % bars;
    if (m < 0) m += bars;
    return static_cast<float>(m) / static_cast<float>(bars);
}

// EAR-TUNE(user reviews; Claude drafted): the breathe curve. Pure raised cosine,
// starting at the TOP on section entry (the hook sinks first). Swap the shape here.
// Returns the normalized envelope: 1 at the top of the swell, 0 at the bottom.
inline float breathe_envelope(float phase) {
    constexpr float kTwoPi = 6.28318530718f;
    return 0.5f * (1.0f + std::cos(kTwoPi * phase));
}

// Applied gain. envelope 1 => exactly 1.0 whatever the floor, envelope 0 => the floor.
inline float breathe_gain(float envelope, float floor_gain) {
    return floor_gain + (1.0f - floor_gain) * envelope;
}

// Timbre offset, ADDED to the block's mod_timbre. Zero at the top (the tone is open)
// and -span at the bottom (closed as the breath sinks), so it tracks the gain curve
// inverted. Callers pass the SMOOTHED envelope so the tone closes with the swell
// instead of snapping a bar ahead of it.
inline float breathe_timbre_bias(float envelope, float span) {
    return -span * (1.0f - envelope);
}

}  // namespace orpheus
