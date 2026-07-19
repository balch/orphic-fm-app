#pragma once
#include <cstdint>
#include "pulsar_rng.h"

// Shared Anomaly-Engine arming helpers. Every Master* anomaly (wah first;
// crossfade / cut / swell / … mirror it) arms for a duration expressed in
// musical bars, then draws that duration from a [lo, hi] range. Keeping the
// math here means all anomalies agree on "how many samples is N bars" and on
// how a degenerate range collapses.

// bars → sample count. A bar is 16 sixteenth-note steps, so the armed length is
// bars * 16 * samples_per_step. samples_per_step is the drift-adjusted step
// length the caller already computed for this block.
static inline int anomaly_arm_samples(float bars, double samples_per_step) {
    return static_cast<int>(bars * 16.0 * samples_per_step);
}

// Draw a bar count in [lo, hi]. A degenerate range (hi <= lo) returns lo exactly
// and does NOT consume the RNG, so a fixed-length anomaly stays deterministic.
static inline float anomaly_draw_bars(float lo, float hi, uint32_t& rng) {
    return hi > lo ? lo + pattern_rand01(rng) * (hi - lo) : lo;
}
