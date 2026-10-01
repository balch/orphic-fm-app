#pragma once

// How hard the Complexity knob varies the licks, in three zones: steady at 0.3 and below,
// close to the original behavior (a little tamer) up to 0.9, and past the old maximum above it.

#include <algorithm>
#include <cmath>

namespace lick_calm {

constexpr float kSteadyCeiling = 0.3f;   // at or below: steady
constexpr float kSteadyMax     = 0.2f;   // variation at the steady edge
constexpr float kCrazyFloor    = 0.9f;   // at or above: the crazy zone
constexpr float kCrazyMax      = 2.0f;   // variation at Complexity 1 (1 = the old maximum)

enum class Zone : int { STEADY = 0, NORMAL = 1, CRAZY = 2 };

inline Zone zone_for(float complexity) {
    if (complexity <= kSteadyCeiling) return Zone::STEADY;
    return complexity < kCrazyFloor ? Zone::NORMAL : Zone::CRAZY;
}

// Scales ghost/drift growth and lickMutation. `complexity` is the effective value: the knob
// times any section multiplier. 0 = the lick exactly as written, 1 = the original behavior.
inline float variation_for(float complexity) {
    const float c = std::max(0.0f, std::min(1.0f, complexity));
    if (c <= kSteadyCeiling) {
        const float t = c / kSteadyCeiling;
        return kSteadyMax * t * t;
    }
    if (c < kCrazyFloor) {
        // sqrt eases out: quick off the steady edge, gentlest below 0.5, reaching 1 at 0.9.
        const float t = (c - kSteadyCeiling) / (kCrazyFloor - kSteadyCeiling);
        return kSteadyMax + (1.0f - kSteadyMax) * std::sqrt(t);
    }
    return 1.0f + (kCrazyMax - 1.0f) * (c - kCrazyFloor) / (1.0f - kCrazyFloor);
}

// A vibe's lickMutation scaled by the zone's variation. Spurts still triple it, except in
// the steady zone.
inline float effective_mutation(float mutation, bool in_spurt, float variation, Zone zone) {
    float m = mutation * variation;
    if (in_spurt && zone != Zone::STEADY) m *= 3.0f;
    return std::min(1.0f, m);
}

// True on the bar Complexity drops into a lower zone: that bar rebuilds the licks.
inline bool dropped_zone(Zone prev, Zone now) {
    return static_cast<int>(now) < static_cast<int>(prev);
}

}  // namespace lick_calm
