#pragma once

// A section's own Pulsar delay: tap times and feedback (SectionDelay, slots kSectionDelaySlot..+2).
// Each field rides the wire as value + 1, so an unwritten 0 reads as unset and that field falls
// back to the vibe's VibeEffects value. The host lerps toward the staged destination's values on
// the macro pre-roll, so a time change glides (the tail bends in pitch) instead of jumping.

namespace section_delay {

static constexpr float kUnset = -1.0f;
static constexpr float kMaxFeedback = 0.95f;

struct Param {
    float time_a = kUnset;     // 0..1 knob units, the same scale as pulsar_delay_time_a
    float time_b = kUnset;
    float feedback = kUnset;   // 0..0.95
};

inline float decode(float wire, float max_value) {
    if (!(wire > 0.0f)) return kUnset;
    const float v = wire - 1.0f;
    return v < 0.0f ? 0.0f : (v > max_value ? max_value : v);
}

// The value one field should take this block. `cur` is the active section's, `dst` the staged
// destination's (kUnset when none or unauthored), `vibe` the vibe-level value. Returns kUnset
// when neither section authors the field, so the delay keeps reading the vibe value untouched.
inline float blend(float cur, float dst, bool in_transition, float vibe, float progress) {
    const bool has_cur = cur >= 0.0f;
    const bool has_dst = in_transition && dst >= 0.0f;
    if (!has_cur && !has_dst) return kUnset;
    const float from = has_cur ? cur : vibe;
    if (!in_transition) return from;
    const float to = has_dst ? dst : vibe;
    return from + (to - from) * progress;
}

}  // namespace section_delay
