#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"  // for pattern_rand01

// ---------------------------------------------------------------------------
// Markov chord progressions for Pulsar beat machine
// ---------------------------------------------------------------------------
//
// Provides chord progression templates, transition matrices for Markov
// mutation, and helpers for advancing/mutating chord state each step.
// PulsarChordState lives in orpheus_unit_pulsar.h (avoids circular include).

// ── Data structures ───────────────────────────────────────────────────

struct PulsarChord {
    int8_t  degree;    // scale degree 0-6
    uint8_t quality;   // 0 = use scale default, 1 = force major, 2 = force minor
};

struct ProgressionTemplate {
    PulsarChord chords[kMaxProgressionLength];
    int         length;
    int         matrix_index;  // which transition matrix to use for mutations
};

enum ProgressionStyle : uint8_t {
    PROG_POP       = 0,
    PROG_SAD       = 1,
    PROG_JAZZ      = 2,
    PROG_BLUES     = 3,
    PROG_DRONE     = 4,
    PROG_MODAL     = 5,
    PROG_DARK      = 6,
    PROG_ASCENDING = 7,
};
static constexpr int kNumProgressionStyles = 8;

// ── Built-in progression templates ────────────────────────────────────

static const ProgressionTemplate kProgressionTemplates[kNumProgressionStyles] = {
    // Pop: I-V-vi-IV
    { {{0,0},{4,0},{5,0},{3,0}}, 4, 0 },
    // Sad: vi-IV-I-V
    { {{5,0},{3,0},{0,0},{4,0}}, 4, 0 },
    // Jazz: ii-V-I-I
    { {{1,0},{4,0},{0,0},{0,0}}, 4, 2 },
    // Blues: I-I-IV-IV-V-IV-I-V
    { {{0,0},{0,0},{3,0},{3,0},{4,0},{3,0},{0,0},{4,0}}, 8, 0 },
    // Drone: I-I-I-I
    { {{0,0},{0,0},{0,0},{0,0}}, 4, 3 },
    // Modal: i-VII-VI-VII
    { {{0,0},{6,0},{5,0},{6,0}}, 4, 1 },
    // Dark: i-iv-V-i (quality overrides: iv=minor, V=major)
    { {{0,0},{3,2},{4,1},{0,0}}, 4, 0 },
    // Ascending: I-ii-iii-IV
    { {{0,0},{1,0},{2,0},{3,0}}, 4, 1 },
};

// ── Transition matrices (7x7, rows sum to ~1.0) ──────────────────────
// Index: [from_degree][to_degree], degrees 0-6 (I through VII)

static constexpr int kNumTransitionMatrices = 4;

// 0 = Tonal: strong V->I, IV->V pulls
static const float kTransitionMatrix0[7][7] = {
    // to: I     ii    iii   IV    V     vi    VII
    { 0.05f, 0.10f, 0.05f, 0.30f, 0.30f, 0.15f, 0.05f }, // from I
    { 0.10f, 0.05f, 0.05f, 0.10f, 0.50f, 0.10f, 0.10f }, // from ii
    { 0.10f, 0.10f, 0.05f, 0.30f, 0.10f, 0.30f, 0.05f }, // from iii
    { 0.15f, 0.10f, 0.05f, 0.05f, 0.45f, 0.10f, 0.10f }, // from IV
    { 0.55f, 0.05f, 0.05f, 0.10f, 0.05f, 0.15f, 0.05f }, // from V
    { 0.10f, 0.15f, 0.10f, 0.30f, 0.20f, 0.05f, 0.10f }, // from vi
    { 0.40f, 0.05f, 0.10f, 0.10f, 0.20f, 0.10f, 0.05f }, // from VII
};

// 1 = Modal: stepwise motion, avoids V->I
static const float kTransitionMatrix1[7][7] = {
    { 0.10f, 0.25f, 0.10f, 0.10f, 0.10f, 0.10f, 0.25f }, // from I
    { 0.20f, 0.10f, 0.25f, 0.10f, 0.10f, 0.10f, 0.15f }, // from ii
    { 0.10f, 0.25f, 0.10f, 0.25f, 0.10f, 0.10f, 0.10f }, // from iii
    { 0.10f, 0.10f, 0.25f, 0.10f, 0.25f, 0.10f, 0.10f }, // from IV
    { 0.10f, 0.10f, 0.10f, 0.25f, 0.10f, 0.25f, 0.10f }, // from V
    { 0.10f, 0.10f, 0.10f, 0.10f, 0.25f, 0.10f, 0.25f }, // from vi
    { 0.25f, 0.10f, 0.10f, 0.10f, 0.10f, 0.25f, 0.10f }, // from VII
};

// 2 = Chromatic: more uniform distribution
static const float kTransitionMatrix2[7][7] = {
    { 0.10f, 0.15f, 0.15f, 0.15f, 0.15f, 0.15f, 0.15f }, // from I
    { 0.15f, 0.10f, 0.15f, 0.15f, 0.15f, 0.15f, 0.15f }, // from ii
    { 0.15f, 0.15f, 0.10f, 0.15f, 0.15f, 0.15f, 0.15f }, // from iii
    { 0.15f, 0.15f, 0.15f, 0.10f, 0.15f, 0.15f, 0.15f }, // from IV
    { 0.15f, 0.15f, 0.15f, 0.15f, 0.10f, 0.15f, 0.15f }, // from V
    { 0.15f, 0.15f, 0.15f, 0.15f, 0.15f, 0.10f, 0.15f }, // from vi
    { 0.15f, 0.15f, 0.15f, 0.15f, 0.15f, 0.15f, 0.10f }, // from VII
};

// 3 = Static: 90% self-transition (for drones)
static const float kTransitionMatrix3[7][7] = {
    { 0.90f, 0.02f, 0.02f, 0.02f, 0.02f, 0.01f, 0.01f }, // from I
    { 0.02f, 0.90f, 0.02f, 0.02f, 0.02f, 0.01f, 0.01f }, // from ii
    { 0.02f, 0.02f, 0.90f, 0.02f, 0.02f, 0.01f, 0.01f }, // from iii
    { 0.02f, 0.02f, 0.02f, 0.90f, 0.02f, 0.01f, 0.01f }, // from IV
    { 0.02f, 0.02f, 0.02f, 0.02f, 0.90f, 0.01f, 0.01f }, // from V
    { 0.02f, 0.02f, 0.02f, 0.02f, 0.02f, 0.89f, 0.01f }, // from vi
    { 0.02f, 0.02f, 0.02f, 0.02f, 0.02f, 0.01f, 0.89f }, // from VII
};

// Pointers for indexing by matrix_index
static const float (*kTransitionMatrices[kNumTransitionMatrices])[7] = {
    kTransitionMatrix0,
    kTransitionMatrix1,
    kTransitionMatrix2,
    kTransitionMatrix3,
};

// chord_degree_to_semitones() lives in pulsar_pattern_gen.h (included transitively)

// ── Initialize chord progression from template ────────────────────────

// @param custom_degrees  Optional caller-supplied chord degrees. When non-null
//                        and custom_length > 0, overrides the template's chord
//                        sequence. Matrix selection still comes from the style
//                        (unless cs.use_custom_matrix is set). Pass nullptr to
//                        use the style's template unchanged.
inline void init_chord_progression(PulsarChordState& cs, int style,
                                   int chords_per_bar, int step_count,
                                   uint32_t seed,
                                   const int* custom_degrees = nullptr,
                                   int custom_length = 0) {
    if (style < 0) style = 0;
    if (style >= kNumProgressionStyles) style = kNumProgressionStyles - 1;

    const ProgressionTemplate& tmpl = kProgressionTemplates[style];
    if (custom_degrees != nullptr && custom_length > 0) {
        if (custom_length > kMaxProgressionLength) custom_length = kMaxProgressionLength;
        cs.progression_length = custom_length;
        for (int i = 0; i < custom_length; i++) {
            int d = custom_degrees[i];
            if (d < 0) d = 0;
            if (d > 6) d = 6;
            cs.progression[i] = static_cast<int8_t>(d);
        }
    } else {
        cs.progression_length = tmpl.length;
        for (int i = 0; i < tmpl.length; i++) {
            cs.progression[i] = tmpl.chords[i].degree;
        }
    }
    if (!cs.use_custom_matrix) {
        cs.matrix_index = tmpl.matrix_index;
        if (cs.matrix_index < 0) cs.matrix_index = 0;
        if (cs.matrix_index >= kNumTransitionMatrices) cs.matrix_index = 0;
    }

    cs.chord_index = 0;
    cs.chord_step_counter = 0;
    cs.chord_seed = seed ^ 0xDEADBEEFu;

    // Steps per chord: divide total steps by chords per bar
    if (chords_per_bar <= 0) chords_per_bar = 2;
    if (chords_per_bar > 8) chords_per_bar = 8;
    cs.steps_per_chord = step_count / chords_per_bar;
    if (cs.steps_per_chord < 1) cs.steps_per_chord = 1;

    std::memcpy(cs.original_progression, cs.progression, sizeof(cs.progression));
    // Seed at 1 so the first anchor reset lands on cycle N (not cycle N+1).
    // bars_since_anchor only increments when the progression wraps back to
    // chord_index 0, which happens at the START of cycle 2; cycle 1 plays
    // without ever entering that branch, so we lose one increment up front
    // and need to compensate. Mirrors the fix in orpheus_unit_pulsar.cpp for
    // ts.bars_since_fill.
    cs.bars_since_anchor = 1;
    // Clear anchor/drift to sane defaults so init is a true reset. The host
    // load_vibe() path immediately overwrites these from the vibe config;
    // other callers (tests, hot-swapped progressions) get a stable baseline.
    cs.anchor_bars = 0;      // 0 = never auto-anchor
    cs.drift_range = 0.0f;   // 0 = no Markov drift
}

// ── Maybe mutate one chord in the progression via Markov transition ───

inline void maybe_mutate_progression(PulsarChordState& cs, float complexity, float mood) {
    // Only mutate when complexity > 0.3, probability scales with complexity
    float roll = pattern_rand01(cs.chord_seed);
    float mutation_prob = (complexity - 0.3f) * 0.25f;  // 0 at 0.3, 0.175 at 1.0
    if (mutation_prob <= 0.0f || roll > mutation_prob) return;

    // Pick a random position to mutate (not the first chord — keep the tonic anchor)
    int pos = 1 + static_cast<int>(pattern_rand01(cs.chord_seed) * (cs.progression_length - 1));
    if (pos >= cs.progression_length) pos = cs.progression_length - 1;

    // Current degree at the previous position determines the row
    int prev_pos = (pos - 1 + cs.progression_length) % cs.progression_length;
    int from_degree = cs.progression[prev_pos];
    if (from_degree < 0) from_degree = 0;
    if (from_degree > 6) from_degree = 6;

    // Sample from transition matrix row
    const float (*matrix)[7] = cs.use_custom_matrix
        ? cs.custom_matrix
        : kTransitionMatrices[cs.matrix_index];
    float r = pattern_rand01(cs.chord_seed);
    float cumulative = 0.0f;
    int new_degree = 0;
    for (int d = 0; d < 7; d++) {
        cumulative += matrix[from_degree][d];
        if (r <= cumulative) {
            new_degree = d;
            break;
        }
    }
    cs.progression[pos] = static_cast<int8_t>(new_degree);

    // Bound drift: clamp new_degree to ±max_drift of original at this position
    int max_drift = static_cast<int>(mood * cs.drift_range * 6.0f + 0.5f);
    if (max_drift < 0) max_drift = 0;
    if (max_drift > 6) max_drift = 6;
    int original = cs.original_progression[pos];
    int clamped = cs.progression[pos];
    if (clamped < original - max_drift) clamped = original - max_drift;
    if (clamped > original + max_drift) clamped = original + max_drift;
    if (clamped < 0) clamped = 0;
    if (clamped > 6) clamped = 6;
    cs.progression[pos] = static_cast<int8_t>(clamped);
}

// ── Advance chord state by one step ───────────────────────────────────

inline void advance_chord(PulsarChordState& cs, float complexity, float mood) {
    cs.chord_step_counter++;
    if (cs.chord_step_counter >= cs.steps_per_chord) {
        cs.chord_step_counter = 0;
        cs.chord_index = (cs.chord_index + 1) % cs.progression_length;

        // At progression wrap, maybe mutate or reset to anchor
        if (cs.chord_index == 0) {
            cs.bars_since_anchor++;
            if (cs.anchor_bars > 0 && cs.bars_since_anchor >= cs.anchor_bars) {
                // Reset progression to original snapshot
                std::memcpy(cs.progression, cs.original_progression, sizeof(cs.progression));
                cs.bars_since_anchor = 0;
            } else {
                maybe_mutate_progression(cs, complexity, mood);
            }
        }
    }
}
