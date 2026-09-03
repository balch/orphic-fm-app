#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"  // for pattern_rand01
#include "pulsar_solo_matrices.h"
#include <cstring>
#include <algorithm>

// ---------------------------------------------------------------------------
// Solo state machine for Pulsar arrangement system
//
// Handles:
// - Solo mode selection (ROUND_ROBIN, EXTENDED, IMPROVISERS)
// - Soloist selection (FIXED_ORDER, MARKOV, WEIGHTED_RANDOM)
// - Markov melodic generation with interval weighting
// - IMPROVISERS handoff bias for phrase continuation
// - Volume/density/ghost ducking on non-soloists
// ---------------------------------------------------------------------------

// ── Default solo behavior by envelope profile ──────────────────────────

inline SoloBehaviorParam default_solo_behavior(PulsarEnvelopeProfile profile) {
    SoloBehaviorParam param;

    // Interval weights arrays (15 entries, indices -7..+7)
    const float kMelodicWeights[kMarkovIntervals] = {
        0.02f, 0.03f, 0.05f, 0.08f, 0.10f, 0.15f, 0.25f,
        0.10f, 0.25f, 0.15f, 0.10f, 0.08f, 0.05f, 0.03f, 0.02f
    };

    const float kRhythmicWeights[kMarkovIntervals] = {
        0.01f, 0.01f, 0.02f, 0.03f, 0.05f, 0.08f, 0.10f,
        0.40f, 0.10f, 0.08f, 0.05f, 0.03f, 0.02f, 0.01f, 0.01f
    };

    const float kEffectWeights[kMarkovIntervals] = {
        0.05f, 0.06f, 0.07f, 0.08f, 0.07f, 0.06f, 0.05f,
        0.12f, 0.05f, 0.06f, 0.07f, 0.08f, 0.07f, 0.06f, 0.05f
    };

    const float kWildWeights[kMarkovIntervals] = {
        0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f,
        0.064f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f
    };

    switch (profile) {
        case ENV_PROFILE_RHYTHM: {
            param.volume_boost = 0.2f;
            param.density_boost = 0.4f;
            param.fill_probability = 0.8f;
            param.rest_probability = 0.3f;
            param.density_curve_min = 0.6f;
            param.density_curve_max = 0.9f;
            param.rhythm_variation = 0.4f;
            std::memcpy(param.interval_weights, kRhythmicWeights, sizeof(kRhythmicWeights));
            break;
        }
        case ENV_PROFILE_MELODIC: {
            param.volume_boost = 0.3f;
            param.density_boost = 0.2f;
            param.timbre_min = 0.2f;
            param.timbre_max = 0.8f;
            param.morph_min = 0.1f;
            param.morph_max = 0.7f;
            param.fill_probability = 0.6f;
            param.rest_probability = 0.15f;
            param.hold_probability = 0.25f;
            param.density_curve_min = 0.4f;
            param.density_curve_max = 0.8f;
            param.chromatic_passing = 0.15f;
            param.phrase_length_min = 4;
            param.phrase_length_max = 8;
            std::memcpy(param.interval_weights, kMelodicWeights, sizeof(kMelodicWeights));
            break;
        }
        case ENV_PROFILE_EFFECT: {
            param.volume_boost = 0.25f;
            param.density_boost = 0.15f;
            param.harmonics_min = 0.1f;
            param.harmonics_max = 0.9f;
            param.rest_probability = 0.25f;
            param.chromatic_passing = 0.2f;
            param.density_curve_min = 0.3f;
            param.density_curve_max = 0.6f;
            param.fill_probability = 0.5f;
            std::memcpy(param.interval_weights, kEffectWeights, sizeof(kEffectWeights));
            break;
        }
        case ENV_PROFILE_WILD: {
            param.volume_boost = 0.3f;
            param.density_boost = 0.5f;
            param.evolution_intensity = 1.5f;
            param.rest_probability = 0.1f;
            param.hold_probability = 0.1f;
            param.density_curve_min = 0.7f;
            param.density_curve_max = 1.0f;
            param.fill_probability = 0.9f;
            param.rhythm_variation = 0.8f;
            param.chromatic_passing = 0.3f;
            std::memcpy(param.interval_weights, kWildWeights, sizeof(kWildWeights));
            break;
        }
        case ENV_PROFILE_DRONE: {
            // Drone: slow stepwise drift, lots of holds, wide spacing
            const float kDroneWeights[kMarkovIntervals] = {
                0.01f, 0.01f, 0.02f, 0.04f, 0.08f, 0.20f, 0.30f,
                0.05f,  // unison
                0.30f, 0.20f, 0.08f, 0.04f, 0.02f, 0.01f, 0.01f
            };
            param.volume_boost = 0.15f;
            param.density_boost = 0.1f;
            param.harmonics_min = 0.1f;
            param.harmonics_max = 0.6f;
            param.rest_probability = 0.35f;
            param.hold_probability = 0.4f;
            param.density_curve_min = 0.2f;
            param.density_curve_max = 0.5f;
            param.density_curve_shape = -0.3f;  // front-loaded: statement then fade
            param.chromatic_passing = 0.05f;
            param.phrase_length_min = 3;
            param.phrase_length_max = 6;
            std::memcpy(param.interval_weights, kDroneWeights, sizeof(kDroneWeights));
            break;
        }
    }

    return param;
}

// ── Default ducking parameters by profile ──────────────────────────────

inline DuckingParam default_ducking(PulsarEnvelopeProfile profile) {
    DuckingParam param;

    switch (profile) {
        case ENV_PROFILE_RHYTHM:
            param.volume_reduction = 0.2f;
            param.density_reduction = 0.5f;
            param.ghost_reduction = 0.7f;
            param.fill_suppression = 0.9f;
            param.simplify = true;
            param.reverb_boost = 0.05f;
            break;
        case ENV_PROFILE_MELODIC:
            param.volume_reduction = 0.35f;
            param.density_reduction = 0.4f;
            param.ghost_reduction = 0.5f;
            param.fill_suppression = 0.7f;
            param.simplify = true;
            param.reverb_boost = 0.1f;
            break;
        case ENV_PROFILE_EFFECT:
            param.volume_reduction = 0.4f;
            param.density_reduction = 0.6f;
            param.ghost_reduction = 0.6f;
            param.fill_suppression = 0.8f;
            param.simplify = false;
            param.reverb_boost = 0.15f;
            break;
        case ENV_PROFILE_WILD:
            param.volume_reduction = 0.5f;
            param.density_reduction = 0.7f;
            param.ghost_reduction = 0.8f;
            param.fill_suppression = 0.95f;
            param.simplify = true;
            param.reverb_boost = 0.0f;
            break;
        case ENV_PROFILE_DRONE:
            // Same as EFFECT for ducking
            param.volume_reduction = 0.4f;
            param.density_reduction = 0.6f;
            param.ghost_reduction = 0.6f;
            param.fill_suppression = 0.8f;
            param.simplify = false;
            param.reverb_boost = 0.15f;
            break;
    }

    return param;
}

// ── Apply solo modifiers to all tracks ─────────────────────────────────

inline void apply_solo_modifiers(
    PulsarTrackState* tracks,
    int soloist,
    const SoloBehaviorParam& solo_behavior,
    const DuckingParam& track_ducking,
    int num_tracks
) {
    for (int i = 0; i < num_tracks; i++) {
        if (i == soloist) {
            // Soloist gets positive boost
            tracks[i].is_soloist = true;
            tracks[i].solo_volume_mod = solo_behavior.volume_boost;
            tracks[i].solo_density_mod = solo_behavior.density_boost;
            tracks[i].solo_ghost_mod = 0.0f;
            tracks[i].solo_fill_mod = solo_behavior.fill_probability;
            tracks[i].solo_simplify = false;
            tracks[i].solo_reverb_mod = 0.0f;
        } else {
            // Others get ducking
            tracks[i].is_soloist = false;
            tracks[i].solo_volume_mod = -track_ducking.volume_reduction;
            tracks[i].solo_density_mod = -track_ducking.density_reduction;
            tracks[i].solo_ghost_mod = -track_ducking.ghost_reduction;
            tracks[i].solo_fill_mod = -track_ducking.fill_suppression;
            tracks[i].solo_simplify = track_ducking.simplify;
            tracks[i].solo_reverb_mod = track_ducking.reverb_boost;
        }
    }
}

// ── Clear solo modifiers ───────────────────────────────────────────────

inline void clear_solo_modifiers(
    PulsarTrackState* tracks,
    int num_tracks
) {
    for (int i = 0; i < num_tracks; i++) {
        tracks[i].solo_volume_mod = 0.0f;
        tracks[i].solo_density_mod = 0.0f;
        tracks[i].solo_ghost_mod = 0.0f;
        tracks[i].solo_fill_mod = 0.0f;
        tracks[i].solo_simplify = false;
        tracks[i].solo_reverb_mod = 0.0f;
        tracks[i].is_soloist = false;
    }
}

// ── Markov melodic generation ──────────────────────────────────────────

inline int markov_next_note(
    SoloBehaviorParam& behavior,  // non-const: updates last_interval
    int current_degree,
    int scale_count,
    float solo_progress,
    uint32_t& seed
) {
    // Rest probability
    if (pattern_rand01(seed) < behavior.rest_probability) {
        return -1;
    }

    // Hold probability
    if (pattern_rand01(seed) < behavior.hold_probability) {
        return -2;
    }

    // Density gating
    float shaped_progress = solo_progress;
    if (behavior.density_curve_shape > 0.01f) {
        shaped_progress = solo_progress * solo_progress * behavior.density_curve_shape
                        + solo_progress * (1.0f - behavior.density_curve_shape);
    } else if (behavior.density_curve_shape < -0.01f) {
        float inv = 1.0f - solo_progress;
        float abs_shape = -behavior.density_curve_shape;
        shaped_progress = inv * inv * abs_shape + inv * (1.0f - abs_shape);
    }
    float density_gate = behavior.density_curve_min +
                         shaped_progress * (behavior.density_curve_max - behavior.density_curve_min);
    if (pattern_rand01(seed) > density_gate) {
        return -1;
    }

    // ── Second-order Markov interval selection ──
    int row_idx = behavior.last_interval + 7;
    if (row_idx < 0) row_idx = 0;
    if (row_idx >= kSoloMatrixSize) row_idx = kSoloMatrixSize - 1;

    const float (*matrix)[kSoloMatrixSize] = solo_transition_matrix(behavior.profile);
    float blended[kSoloMatrixSize];
    float sum = 0.0f;
    for (int i = 0; i < kSoloMatrixSize; i++) {
        blended[i] = matrix[row_idx][i] * kMatrixBlend
                   + behavior.interval_weights[i] * (1.0f - kMatrixBlend);
        sum += blended[i];
    }
    // Normalize
    if (sum > 0.0f) {
        float inv_sum = 1.0f / sum;
        for (int i = 0; i < kSoloMatrixSize; i++) blended[i] *= inv_sum;
    }

    // Weighted random selection
    float roll = pattern_rand01(seed);
    float cumulative = 0.0f;
    int next_interval = 0;
    for (int i = 0; i < kSoloMatrixSize; i++) {
        cumulative += blended[i];
        if (roll <= cumulative) {
            next_interval = i - 7;
            break;
        }
    }

    // Chromatic passing tone
    if (pattern_rand01(seed) < behavior.chromatic_passing) {
        next_interval += (pattern_rand01(seed) < 0.5f) ? 1 : -1;
    }

    // Update last_interval for next call
    behavior.last_interval = static_cast<int8_t>(
        std::max(-7, std::min(7, next_interval)));

    // Compute next degree, wrapping within scale
    int next_degree = current_degree + next_interval;
    while (next_degree < 0) next_degree += scale_count;
    while (next_degree >= scale_count) next_degree -= scale_count;

    return next_degree;
}

// ── IMPROVISERS handoff: bias next behavior toward continuing phrase ──

inline void improvisers_handoff(
    const BandSoloState& state,
    float carryover,  // 0.0-1.0: how much to bias toward last phrase
    SoloBehaviorParam& next_behavior,
    uint32_t& seed
) {
    if (carryover <= 0.0f || state.phrase_cursor == 0) {
        return;  // No phrase history to carry over
    }

    // Bias interval_weights toward the intervals used in last_phrase[]
    // This creates musical continuity between soloists.

    // Track which intervals appeared in last phrase
    float phrase_interval_freq[kMarkovIntervals] = {};
    float total_freq = 0.0f;

    for (int i = 1; i < state.phrase_cursor; i++) {
        int prev_degree = state.last_phrase[i - 1];
        int curr_degree = state.last_phrase[i];

        if (prev_degree >= 0 && curr_degree >= 0) {
            int interval = curr_degree - prev_degree;
            // Clamp interval to -7..+7
            if (interval < -7) interval = -7;
            if (interval > 7) interval = 7;
            int idx = interval + 7;  // maps -7..+7 to 0..14
            phrase_interval_freq[idx] += 1.0f;
            total_freq += 1.0f;
        }
    }

    // Blend: carryover fraction of biased weights, (1-carryover) of original
    if (total_freq > 0.0f) {
        for (int i = 0; i < kMarkovIntervals; i++) {
            float biased = phrase_interval_freq[i] / total_freq;
            next_behavior.interval_weights[i] =
                next_behavior.interval_weights[i] * (1.0f - carryover) +
                biased * carryover;
        }
    }
}

// ── Record note in solo phrase history ─────────────────────────────────

inline void record_solo_note(
    BandSoloState& state,
    int scale_degree
) {
    if (state.phrase_cursor < kMaxSoloPhrase) {
        state.last_phrase[state.phrase_cursor] = static_cast<int8_t>(scale_degree);
        state.phrase_cursor++;
    }
}

// Nearest chord tone (1-3-5 of the chord on chord_degree) to `degree`, octave-shifted
// into degree's neighborhood so anchoring doesn't introduce a leap.
inline int nearest_chord_tone_degree(int degree, int chord_degree, int scale_count) {
    if (scale_count <= 0) return degree;
    int cand[3] = { chord_degree, chord_degree + 2, chord_degree + 4 };
    int best = degree, bestDist = 1 << 30;
    for (int k = 0; k < 3; k++) {
        int c = cand[k];
        while (c - degree >  scale_count / 2) c -= scale_count;
        while (degree - c >  scale_count / 2) c += scale_count;
        int dist = std::abs(c - degree);
        if (dist < bestDist) { bestDist = dist; best = c; }
    }
    return best;
}

// Nominal jam span used when the section clock is not running (a single-section
// arrangement never decrements bars_remaining), so a jam still builds instead of
// freezing at progress 0 for its whole life.
inline constexpr int kJamNominalBars = 32;

// How far a jam has travelled through its span, 0..1 — the `solo_progress` that
// shapes markov_next_note's density gate. Section-wide, NOT per-soloist-span: a
// per-span reset restarts every 2-4 bars, which leaves bar 32 sounding like bar 1.
inline float jam_solo_progress(int bars_elapsed, int section_bars_total) {
    int span = (section_bars_total > 0) ? section_bars_total : kJamNominalBars;
    if (span <= 1) return 1.0f;
    float p = static_cast<float>(bars_elapsed) / static_cast<float>(span - 1);
    return (p < 0.0f) ? 0.0f : (p > 1.0f ? 1.0f : p);
}

// Fit a generated jam note to the soloing track's register. An authored range folds
// by octave so the pitch class survives instead of piling onto the boundary; the
// 24..96 backstop stays the hard clamp the un-ranged path has always applied.
inline int fit_jam_note_to_range(int note, int note_low, int note_high) {
    if (note_low > 0 && note_high > note_low) {
        while (note > note_high) note -= 12;
        while (note < note_low)  note += 12;
        if (note > note_high) note = note_high;   // range narrower than an octave
    }
    if (note < 24) note = 24; if (note > 96) note = 96;
    return note;
}

// Scale degree -> MIDI for one jam step: chord-anchor the downbeats, then fit to the
// track's register. Shared by the create and ornament paths so they cannot drift.
inline int jam_step_note(int degree, int step_index, int root, const PulsarScale& scale,
                         int chord_degree, int octave, int note_low, int note_high) {
    int eff = degree;
    if ((step_index % 4) == 0) eff = nearest_chord_tone_degree(degree, chord_degree, scale.count);
    int d = ((eff % scale.count) + scale.count) % scale.count;
    int oct_off = (eff - d) / scale.count;         // exact since eff-d is a multiple of count
    return fit_jam_note_to_range(root + octave * 12 + oct_off * 12 + scale.degrees[d],
                                 note_low, note_high);
}

// Create-mode: generate a fresh chord-anchored improvised line into steps[] for the
// Jam lead, recording the phrase for cross-soloist carryover.
inline void generate_jam_solo_line(SoloBehaviorParam& behavior, BandSoloState& solo_state,
                                   PulsarStep* steps, int step_count,
                                   int root, const PulsarScale& scale, int chord_degree,
                                   int octave, int& current_degree,
                                   float solo_progress, int note_low, int note_high,
                                   uint32_t& seed) {
    if (step_count <= 0 || scale.count <= 0) return;
    for (int s = 0; s < step_count; s++) {
        int degree = markov_next_note(behavior, current_degree, scale.count, solo_progress, seed);
        if (degree == -1 || degree == -2) {        // rest / hold -> no new attack
            steps[s] = make_step(0, 0.0f, false, 0.0f);
            continue;
        }
        current_degree = degree;
        record_solo_note(solo_state, degree);
        int note = jam_step_note(degree, s, root, scale, chord_degree, octave,
                                 note_low, note_high);
        float vel = 0.6f + 0.3f * ((s % 4) == 0 ? 1.0f : 0.4f);  // accent downbeats
        steps[s] = make_step(static_cast<uint8_t>(note), vel, true, 0.5f);
    }
}

// Ornament density floor/ceiling across a jam's build. The floor is deliberately low
// so a jam OPENS on the bare hook; the ceiling leaves the riff audible under the fills.
inline constexpr float kJamOrnamentMin = 0.10f;
inline constexpr float kJamOrnamentMax = 0.85f;

// Ornament-mode: the lead carries an authored hook, so the jam plays in its GAPS —
// gated steps keep note, velocity and articulation; only rests are candidates.
// The caller MUST re-render the bare hook first, or last bar's ornaments read as hook.
inline void ornament_jam_solo_line(SoloBehaviorParam& behavior, BandSoloState& solo_state,
                                   PulsarStep* steps, int step_count,
                                   int root, const PulsarScale& scale, int chord_degree,
                                   int octave, int& current_degree,
                                   float solo_progress, int note_low, int note_high,
                                   uint32_t& seed) {
    if (step_count <= 0 || scale.count <= 0) return;
    float fill_gate = kJamOrnamentMin + solo_progress * (kJamOrnamentMax - kJamOrnamentMin);
    for (int s = 0; s < step_count; s++) {
        if (steps[s].gate) continue;                     // the hook owns this step
        if (pattern_rand01(seed) > fill_gate) continue;  // leave the gap open
        int degree = markov_next_note(behavior, current_degree, scale.count, solo_progress, seed);
        if (degree < 0) continue;                        // markov rest/hold -> gap stays
        current_degree = degree;
        record_solo_note(solo_state, degree);
        int note = jam_step_note(degree, s, root, scale, chord_degree, octave,
                                 note_low, note_high);
        // Ornaments answer the hook rather than compete with it: shorter and quieter
        // than a lead attack, with the downbeat still carrying more weight.
        float vel = 0.45f + 0.15f * ((s % 4) == 0 ? 1.0f : 0.0f);
        steps[s] = make_step(static_cast<uint8_t>(note), vel, true, 0.35f);
    }
}
