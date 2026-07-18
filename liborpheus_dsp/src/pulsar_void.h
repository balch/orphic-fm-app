#pragma once
#include <cmath>
#include <cstdint>
#include "pulsar_rng.h"   // for pattern_rand01

// A musical bar is a fixed 16 steps (4 steps/beat * 4 beats), independent of a
// track's loop length. The ghost bar is exactly one musical bar.
static constexpr int kVoidStepsPerBar = 16;
static constexpr int kVoidGhostSteps  = kVoidStepsPerBar;

// Full-scale slew time for the applied gain (VoidAnomaly::gain_smoothed). Bounds the
// per-sample delta so ghost-bar steps and section-boundary resets glide instead of
// stepping, while staying orders of magnitude faster than the cosine ramps (which
// span whole bars) so the ramps themselves pass through unaffected.
static constexpr float kVoidGainSlewSeconds = 0.005f;

// Unpacked from the pulsar_void_data[] atomic bank (see load_vibe). probability
// <= 0 disables AUTO firing; the shape fields still drive a MANUAL trigger.
struct VoidConfig {
    float probability    = 0.0f;
    float floor_level     = 0.05f;
    float ramp_down_bars  = 1.0f;
    float floor_bars_min  = 1.0f;
    float floor_bars_max  = 2.0f;
    float ramp_up_bars    = 1.5f;
    float ghost_intensity = 0.5f;   // 0 = no ghost bar
};

// Runtime state machine for one void arc. All positions are in STEPS relative to
// section entry; the schedule fields are relative to start_step.
struct VoidAnomaly {
    bool   armed = false;
    double start_step = 0.0;         // cursor value at which the arc begins
    double cursor = 0.0;             // fractional steps since section entry
    float  ramp_down_end = 0.0f;
    float  floor1_end = 0.0f;
    float  ghost_end = 0.0f;
    float  floor2_end = 0.0f;
    float  ramp_up_end = 0.0f;       // == total arc length
    float  floor_level = 0.05f;
    float  ghost_gain = 0.05f;       // max(floor_level, ghost_intensity)
    bool   has_ghost = false;
    bool   suppress_note_ons = false; // recomputed each block from the current phase
    // Smoothed (slewed) copy of the applied gain, persisted across blocks -- this is
    // what actually reaches the mix (see the void_gain_buf pre-pass in
    // orpheus_unit_pulsar.cpp). Intentionally NOT touched by void_reset(): a reset
    // (e.g. a section boundary crossing a still-ducking manual arc) leaves this alone
    // so the post-reset idle/re-armed path glides it toward the new target instead of
    // snapping. PulsarState (and this struct's default member initializer) is
    // constructed once for the life of the engine, not per vibe load, so a vibe
    // switch re-defaults this to 1.0 explicitly in load_vibe() rather than relying on
    // construction.
    float  gain_smoothed = 1.0f;
};

// Fill the schedule boundaries (relative to start_step) for a drawn floor length.
inline void compute_void_schedule(VoidAnomaly& v, const VoidConfig& cfg, float floor_bars) {
    float rd = cfg.ramp_down_bars * kVoidStepsPerBar;
    float f  = floor_bars         * kVoidStepsPerBar;
    float ru = cfg.ramp_up_bars   * kVoidStepsPerBar;
    v.has_ghost = cfg.ghost_intensity > 0.0f;
    float g = v.has_ghost ? static_cast<float>(kVoidGhostSteps) : 0.0f;
    float half_f = f * 0.5f;
    v.ramp_down_end = rd;
    v.floor1_end    = rd + half_f;
    v.ghost_end     = rd + half_f + g;
    v.floor2_end    = rd + half_f + g + half_f;
    v.ramp_up_end   = v.floor2_end + ru;
    v.floor_level   = cfg.floor_level;
    v.ghost_gain    = v.has_ghost ? std::fmax(cfg.floor_level, cfg.ghost_intensity)
                                  : cfg.floor_level;
}

inline float void_arc_total_steps(const VoidAnomaly& v) { return v.ramp_up_end; }

// Cosine ease from a to b as t goes 0->1.
inline float void_ease(float a, float b, float t) {
    if (t <= 0.0f) return a;
    if (t >= 1.0f) return b;
    float c = 0.5f - 0.5f * std::cos(t * 3.14159265f);
    return a + (b - a) * c;
}

// Gain in [floor_level, 1] for a position `pos` (steps into the arc, == cursor -
// start_step). Sets *suppress true during the quiet floor halves only (note-ons
// are muted there); ramps and the ghost bar leave *suppress false so notes play.
inline float void_arc_gain(const VoidAnomaly& v, float pos, bool* suppress) {
    *suppress = false;
    if (pos <= 0.0f || pos >= v.ramp_up_end) return 1.0f;
    if (pos < v.ramp_down_end)  return void_ease(1.0f, v.floor_level, pos / v.ramp_down_end);
    if (pos < v.floor1_end)   { *suppress = true; return v.floor_level; }
    if (v.has_ghost && pos < v.ghost_end) return v.ghost_gain;   // ghost: notes fire, gain lifts
    if (pos < v.floor2_end)   { *suppress = true; return v.floor_level; }
    return void_ease(v.floor_level, 1.0f,
                     (pos - v.floor2_end) / (v.ramp_up_end - v.floor2_end));
}

// Max per-sample delta for the gain_smoothed slew at a given sample rate. Shared by
// the pre-pass in orpheus_unit_pulsar.cpp and by tests that verify the slew's bound.
inline float void_gain_max_step_per_sample(float sample_rate) {
    return 1.0f / (kVoidGainSlewSeconds * sample_rate);
}

// Draw the floor length; deterministic when floor_bars_min == floor_bars_max
// (no RNG consumed in that case, so tests stay reproducible under a stirred seed).
inline float void_draw_floor_bars(const VoidConfig& cfg, uint32_t& rng) {
    if (cfg.floor_bars_max > cfg.floor_bars_min)
        return cfg.floor_bars_min + pattern_rand01(rng) * (cfg.floor_bars_max - cfg.floor_bars_min);
    return cfg.floor_bars_min;
}

inline void void_reset(VoidAnomaly& v) {
    v.armed = false;
    v.cursor = 0.0;
    v.suppress_note_ons = false;
    // gain_smoothed is intentionally NOT reset here -- see its field comment above.
}

// AUTO: end-align the arc to the section boundary. Rolls probability last so that
// probability==1 fires regardless of prior draws. Returns true when armed.
inline bool arm_void_auto(VoidAnomaly& v, const VoidConfig& cfg,
                          float section_total_steps, uint32_t& rng) {
    v.armed = false;
    if (cfg.probability <= 0.0f) return false;
    float floor_bars = void_draw_floor_bars(cfg, rng);
    compute_void_schedule(v, cfg, floor_bars);
    float arc = void_arc_total_steps(v);
    float margin = kVoidStepsPerBar * 0.5f;         // land clear of the boundary
    if (arc + margin > section_total_steps) return false;   // room gate
    if (pattern_rand01(rng) >= cfg.probability) return false;
    v.start_step = static_cast<double>(section_total_steps - arc);
    v.armed = true;
    return true;
}

// MANUAL: fire at the next musical-bar boundary from the current cursor.
inline void arm_void_manual(VoidAnomaly& v, const VoidConfig& cfg, uint32_t& rng) {
    float floor_bars = void_draw_floor_bars(cfg, rng);
    compute_void_schedule(v, cfg, floor_bars);
    double next_bar = std::ceil((v.cursor + 0.001) / kVoidStepsPerBar) * kVoidStepsPerBar;
    v.start_step = next_bar;
    v.armed = true;
}
