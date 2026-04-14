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
