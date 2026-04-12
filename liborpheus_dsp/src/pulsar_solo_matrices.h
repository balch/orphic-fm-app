#pragma once

#include "orpheus_unit_pulsar.h"  // for PulsarEnvelopeProfile, kMarkovIntervals

static constexpr int kSoloMatrixSize = 15;

// Blend factor for second-order vs first-order weights.
// 0.0 = pure first-order (ignore previous interval),
// 1.0 = pure second-order (fully use transition matrix).
static constexpr float kMatrixBlend = 0.7f;

// ---------------------------------------------------------------------------
// Melodic: Strong gap-fill (classical counterpoint).
// After large leaps, strongly favors stepwise reversal. Scale runs continue.
// Best for bass and keys solos.
// ---------------------------------------------------------------------------
static const float kMelodicTransition[kSoloMatrixSize][kSoloMatrixSize] = {
    // Row 0: prev interval = -7 (large downward leap) → gap-fill: step UP
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.02f, 0.03f, 0.04f, 0.10f, 0.15f, 0.25f, 0.18f, 0.10f, 0.05f, 0.02f, 0.01f },
    // Row 1: prev = -6 → strong step up
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.02f, 0.03f, 0.05f, 0.10f, 0.16f, 0.26f, 0.17f, 0.09f, 0.04f, 0.02f, 0.01f },
    // Row 2: prev = -5 → moderate step up
    { 0.01f, 0.01f, 0.02f, 0.02f, 0.03f, 0.04f, 0.06f, 0.10f, 0.18f, 0.24f, 0.15f, 0.08f, 0.03f, 0.02f, 0.01f },
    // Row 3: prev = -4 → moderate gap-fill up
    { 0.01f, 0.01f, 0.02f, 0.02f, 0.03f, 0.05f, 0.07f, 0.12f, 0.22f, 0.22f, 0.12f, 0.06f, 0.03f, 0.01f, 0.01f },
    // Row 4: prev = -3 → mild gap-fill up
    { 0.01f, 0.01f, 0.02f, 0.03f, 0.04f, 0.06f, 0.08f, 0.15f, 0.24f, 0.18f, 0.09f, 0.05f, 0.02f, 0.01f, 0.01f },
    // Row 5: prev = -2 → slight upward bias
    { 0.01f, 0.02f, 0.02f, 0.03f, 0.05f, 0.07f, 0.10f, 0.15f, 0.22f, 0.16f, 0.09f, 0.04f, 0.02f, 0.01f, 0.01f },
    // Row 6: prev = -1 → step down, tends to continue or reverse
    { 0.01f, 0.02f, 0.03f, 0.04f, 0.08f, 0.14f, 0.30f, 0.15f, 0.10f, 0.06f, 0.04f, 0.02f, 0.01f, 0.00f, 0.00f },
    // Row 7: prev = 0 (unison) → balanced spread, slight step preference
    { 0.01f, 0.01f, 0.02f, 0.03f, 0.05f, 0.10f, 0.18f, 0.20f, 0.18f, 0.10f, 0.05f, 0.03f, 0.02f, 0.01f, 0.01f },
    // Row 8: prev = +1 → step up, tends to continue or reverse
    { 0.00f, 0.00f, 0.01f, 0.02f, 0.04f, 0.06f, 0.10f, 0.15f, 0.30f, 0.14f, 0.08f, 0.04f, 0.03f, 0.02f, 0.01f },
    // Row 9: prev = +2 → slight downward bias
    { 0.01f, 0.01f, 0.02f, 0.04f, 0.09f, 0.16f, 0.22f, 0.15f, 0.10f, 0.07f, 0.05f, 0.03f, 0.02f, 0.02f, 0.01f },
    // Row 10: prev = +3 → mild gap-fill down
    { 0.01f, 0.01f, 0.02f, 0.05f, 0.09f, 0.18f, 0.24f, 0.15f, 0.08f, 0.06f, 0.04f, 0.03f, 0.02f, 0.01f, 0.01f },
    // Row 11: prev = +4 → moderate gap-fill down
    { 0.01f, 0.01f, 0.03f, 0.06f, 0.12f, 0.22f, 0.22f, 0.12f, 0.07f, 0.05f, 0.03f, 0.02f, 0.02f, 0.01f, 0.01f },
    // Row 12: prev = +5 → moderate step down
    { 0.01f, 0.02f, 0.03f, 0.08f, 0.15f, 0.24f, 0.18f, 0.10f, 0.06f, 0.04f, 0.03f, 0.02f, 0.02f, 0.01f, 0.01f },
    // Row 13: prev = +6 → strong step down
    { 0.01f, 0.02f, 0.04f, 0.09f, 0.17f, 0.26f, 0.16f, 0.10f, 0.05f, 0.03f, 0.02f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 14: prev = +7 (large upward leap) → gap-fill: step DOWN
    { 0.01f, 0.02f, 0.05f, 0.10f, 0.18f, 0.25f, 0.15f, 0.10f, 0.04f, 0.03f, 0.02f, 0.02f, 0.01f, 0.01f, 0.01f },
};

// ---------------------------------------------------------------------------
// Rhythmic: Strong unison gravity.
// Most intervals collapse to unison (±0) or small steps. Large leaps very rare.
// Best for percussion/drum tracks where "intervals" are velocity variations.
// ---------------------------------------------------------------------------
static const float kRhythmicTransition[kSoloMatrixSize][kSoloMatrixSize] = {
    // Row 0: prev = -7 → strong pull to unison after big move
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.03f, 0.06f, 0.12f, 0.45f, 0.13f, 0.07f, 0.04f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 1: prev = -6
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.03f, 0.06f, 0.13f, 0.43f, 0.13f, 0.07f, 0.04f, 0.02f, 0.02f, 0.01f, 0.01f },
    // Row 2: prev = -5
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.03f, 0.07f, 0.13f, 0.42f, 0.13f, 0.07f, 0.04f, 0.03f, 0.01f, 0.01f, 0.01f },
    // Row 3: prev = -4
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.04f, 0.07f, 0.14f, 0.40f, 0.14f, 0.07f, 0.04f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 4: prev = -3
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.04f, 0.07f, 0.14f, 0.38f, 0.14f, 0.08f, 0.05f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 5: prev = -2
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.04f, 0.07f, 0.15f, 0.35f, 0.15f, 0.08f, 0.05f, 0.03f, 0.01f, 0.01f, 0.01f },
    // Row 6: prev = -1 → step down, likely unison or continue
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.04f, 0.08f, 0.15f, 0.33f, 0.15f, 0.08f, 0.05f, 0.03f, 0.02f, 0.01f, 0.01f },
    // Row 7: prev = 0 (unison) → stay on unison or small steps
    { 0.01f, 0.01f, 0.02f, 0.03f, 0.05f, 0.08f, 0.14f, 0.32f, 0.14f, 0.08f, 0.05f, 0.03f, 0.02f, 0.01f, 0.01f },
    // Row 8: prev = +1 → step up, likely unison or continue
    { 0.01f, 0.01f, 0.02f, 0.03f, 0.05f, 0.08f, 0.15f, 0.33f, 0.15f, 0.08f, 0.04f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 9: prev = +2
    { 0.01f, 0.01f, 0.01f, 0.03f, 0.05f, 0.08f, 0.15f, 0.35f, 0.15f, 0.07f, 0.04f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 10: prev = +3
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.05f, 0.08f, 0.14f, 0.38f, 0.14f, 0.07f, 0.04f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 11: prev = +4
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.04f, 0.07f, 0.14f, 0.40f, 0.14f, 0.07f, 0.04f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 12: prev = +5
    { 0.01f, 0.01f, 0.01f, 0.03f, 0.04f, 0.07f, 0.13f, 0.42f, 0.13f, 0.07f, 0.03f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 13: prev = +6
    { 0.01f, 0.01f, 0.02f, 0.02f, 0.04f, 0.06f, 0.13f, 0.43f, 0.13f, 0.06f, 0.03f, 0.02f, 0.01f, 0.01f, 0.02f },
    // Row 14: prev = +7 → strong pull to unison
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.04f, 0.07f, 0.13f, 0.45f, 0.12f, 0.06f, 0.03f, 0.02f, 0.01f, 0.01f, 0.01f },
};

// ---------------------------------------------------------------------------
// Effect: Moderate gap-fill with dramatic gestures.
// Looser counterpoint — allows consecutive same-direction leaps for tension.
// Best for effect/texture tracks.
// ---------------------------------------------------------------------------
static const float kEffectTransition[kSoloMatrixSize][kSoloMatrixSize] = {
    // Row 0: prev = -7 → moderate gap-fill up, some drama allowed
    { 0.02f, 0.02f, 0.02f, 0.03f, 0.04f, 0.05f, 0.06f, 0.10f, 0.12f, 0.18f, 0.15f, 0.10f, 0.05f, 0.03f, 0.03f },
    // Row 1: prev = -6
    { 0.02f, 0.02f, 0.02f, 0.03f, 0.04f, 0.05f, 0.07f, 0.10f, 0.13f, 0.18f, 0.14f, 0.09f, 0.05f, 0.03f, 0.03f },
    // Row 2: prev = -5
    { 0.02f, 0.02f, 0.02f, 0.03f, 0.04f, 0.06f, 0.08f, 0.11f, 0.15f, 0.17f, 0.13f, 0.08f, 0.04f, 0.03f, 0.02f },
    // Row 3: prev = -4
    { 0.02f, 0.02f, 0.03f, 0.03f, 0.05f, 0.06f, 0.08f, 0.12f, 0.17f, 0.16f, 0.12f, 0.06f, 0.04f, 0.02f, 0.02f },
    // Row 4: prev = -3
    { 0.02f, 0.02f, 0.03f, 0.04f, 0.05f, 0.07f, 0.09f, 0.13f, 0.18f, 0.14f, 0.10f, 0.06f, 0.03f, 0.02f, 0.02f },
    // Row 5: prev = -2
    { 0.02f, 0.02f, 0.03f, 0.04f, 0.05f, 0.08f, 0.11f, 0.13f, 0.17f, 0.14f, 0.09f, 0.05f, 0.03f, 0.02f, 0.02f },
    // Row 6: prev = -1 → continue or reverse
    { 0.02f, 0.02f, 0.03f, 0.04f, 0.07f, 0.11f, 0.25f, 0.13f, 0.12f, 0.08f, 0.05f, 0.04f, 0.02f, 0.01f, 0.01f },
    // Row 7: prev = 0 → balanced with slight step preference
    { 0.02f, 0.02f, 0.03f, 0.04f, 0.06f, 0.10f, 0.14f, 0.18f, 0.14f, 0.10f, 0.06f, 0.04f, 0.03f, 0.02f, 0.02f },
    // Row 8: prev = +1 → continue or reverse
    { 0.01f, 0.01f, 0.02f, 0.04f, 0.05f, 0.08f, 0.12f, 0.13f, 0.25f, 0.11f, 0.07f, 0.04f, 0.03f, 0.02f, 0.02f },
    // Row 9: prev = +2
    { 0.02f, 0.02f, 0.03f, 0.05f, 0.09f, 0.14f, 0.17f, 0.13f, 0.11f, 0.08f, 0.05f, 0.04f, 0.03f, 0.02f, 0.02f },
    // Row 10: prev = +3
    { 0.02f, 0.02f, 0.03f, 0.06f, 0.10f, 0.14f, 0.18f, 0.13f, 0.09f, 0.07f, 0.05f, 0.04f, 0.03f, 0.02f, 0.02f },
    // Row 11: prev = +4
    { 0.02f, 0.02f, 0.04f, 0.06f, 0.12f, 0.16f, 0.17f, 0.12f, 0.08f, 0.06f, 0.05f, 0.03f, 0.03f, 0.02f, 0.02f },
    // Row 12: prev = +5
    { 0.02f, 0.03f, 0.04f, 0.08f, 0.13f, 0.17f, 0.15f, 0.11f, 0.08f, 0.06f, 0.04f, 0.03f, 0.02f, 0.02f, 0.02f },
    // Row 13: prev = +6
    { 0.03f, 0.03f, 0.05f, 0.09f, 0.14f, 0.18f, 0.13f, 0.10f, 0.07f, 0.05f, 0.04f, 0.03f, 0.02f, 0.02f, 0.02f },
    // Row 14: prev = +7 → moderate gap-fill down
    { 0.03f, 0.03f, 0.05f, 0.10f, 0.15f, 0.18f, 0.12f, 0.10f, 0.06f, 0.05f, 0.04f, 0.03f, 0.02f, 0.02f, 0.02f },
};

// ---------------------------------------------------------------------------
// Wild: Near-uniform distribution with slight contour awareness.
// Maximum unpredictability — a "barely musical random walk."
// Best for chaotic/experimental tracks.
// ---------------------------------------------------------------------------
static const float kWildTransition[kSoloMatrixSize][kSoloMatrixSize] = {
    // Row 0: prev = -7 → slight bias toward upward steps
    { 0.05f, 0.05f, 0.05f, 0.05f, 0.06f, 0.06f, 0.07f, 0.07f, 0.08f, 0.10f, 0.10f, 0.08f, 0.07f, 0.06f, 0.05f },
    // Row 1: prev = -6
    { 0.05f, 0.05f, 0.05f, 0.06f, 0.06f, 0.06f, 0.07f, 0.07f, 0.08f, 0.10f, 0.10f, 0.08f, 0.06f, 0.06f, 0.05f },
    // Row 2: prev = -5
    { 0.05f, 0.05f, 0.05f, 0.06f, 0.06f, 0.06f, 0.07f, 0.07f, 0.08f, 0.10f, 0.10f, 0.08f, 0.06f, 0.06f, 0.05f },
    // Row 3: prev = -4
    { 0.05f, 0.05f, 0.06f, 0.06f, 0.06f, 0.06f, 0.07f, 0.07f, 0.08f, 0.09f, 0.09f, 0.08f, 0.07f, 0.06f, 0.05f },
    // Row 4: prev = -3
    { 0.05f, 0.05f, 0.06f, 0.06f, 0.06f, 0.07f, 0.07f, 0.07f, 0.08f, 0.09f, 0.09f, 0.08f, 0.06f, 0.06f, 0.05f },
    // Row 5: prev = -2
    { 0.05f, 0.06f, 0.06f, 0.06f, 0.06f, 0.07f, 0.07f, 0.07f, 0.08f, 0.08f, 0.08f, 0.08f, 0.07f, 0.06f, 0.05f },
    // Row 6: prev = -1
    { 0.05f, 0.06f, 0.06f, 0.06f, 0.06f, 0.07f, 0.08f, 0.07f, 0.07f, 0.08f, 0.08f, 0.08f, 0.07f, 0.06f, 0.05f },
    // Row 7: prev = 0 → symmetric
    { 0.05f, 0.06f, 0.06f, 0.06f, 0.07f, 0.07f, 0.07f, 0.08f, 0.07f, 0.07f, 0.07f, 0.06f, 0.06f, 0.06f, 0.09f },
    // Row 8: prev = +1
    { 0.05f, 0.06f, 0.07f, 0.08f, 0.08f, 0.08f, 0.07f, 0.07f, 0.08f, 0.07f, 0.06f, 0.06f, 0.06f, 0.06f, 0.05f },
    // Row 9: prev = +2
    { 0.05f, 0.06f, 0.07f, 0.08f, 0.08f, 0.08f, 0.08f, 0.07f, 0.07f, 0.07f, 0.06f, 0.06f, 0.06f, 0.06f, 0.05f },
    // Row 10: prev = +3
    { 0.05f, 0.06f, 0.06f, 0.08f, 0.09f, 0.09f, 0.08f, 0.07f, 0.07f, 0.07f, 0.06f, 0.06f, 0.06f, 0.05f, 0.05f },
    // Row 11: prev = +4
    { 0.05f, 0.06f, 0.07f, 0.08f, 0.09f, 0.09f, 0.08f, 0.07f, 0.07f, 0.06f, 0.06f, 0.06f, 0.06f, 0.05f, 0.05f },
    // Row 12: prev = +5
    { 0.05f, 0.06f, 0.06f, 0.08f, 0.10f, 0.10f, 0.08f, 0.07f, 0.07f, 0.06f, 0.06f, 0.06f, 0.05f, 0.05f, 0.05f },
    // Row 13: prev = +6
    { 0.05f, 0.06f, 0.06f, 0.08f, 0.10f, 0.10f, 0.08f, 0.07f, 0.07f, 0.06f, 0.06f, 0.06f, 0.05f, 0.05f, 0.05f },
    // Row 14: prev = +7 → slight bias toward downward steps
    { 0.05f, 0.06f, 0.07f, 0.08f, 0.10f, 0.10f, 0.08f, 0.07f, 0.07f, 0.06f, 0.06f, 0.05f, 0.05f, 0.05f, 0.05f },
};

// ---------------------------------------------------------------------------
// Drone: Strong unison and step gravity.
// Minimal melodic movement — meditative, sustain-oriented.
// Best for pad/drone tracks.
// ---------------------------------------------------------------------------
static const float kDroneTransition[kSoloMatrixSize][kSoloMatrixSize] = {
    // Row 0: prev = -7 → strong unison pull + step back up
    { 0.00f, 0.01f, 0.01f, 0.01f, 0.02f, 0.03f, 0.06f, 0.45f, 0.20f, 0.12f, 0.05f, 0.02f, 0.01f, 0.01f, 0.00f },
    // Row 1: prev = -6
    { 0.00f, 0.01f, 0.01f, 0.01f, 0.02f, 0.03f, 0.06f, 0.44f, 0.21f, 0.12f, 0.05f, 0.02f, 0.01f, 0.01f, 0.00f },
    // Row 2: prev = -5
    { 0.00f, 0.01f, 0.01f, 0.01f, 0.02f, 0.03f, 0.07f, 0.43f, 0.21f, 0.12f, 0.05f, 0.02f, 0.01f, 0.01f, 0.00f },
    // Row 3: prev = -4
    { 0.00f, 0.01f, 0.01f, 0.01f, 0.02f, 0.04f, 0.08f, 0.42f, 0.21f, 0.11f, 0.05f, 0.02f, 0.01f, 0.01f, 0.00f },
    // Row 4: prev = -3
    { 0.00f, 0.01f, 0.01f, 0.01f, 0.02f, 0.05f, 0.09f, 0.40f, 0.20f, 0.11f, 0.05f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 5: prev = -2
    { 0.00f, 0.01f, 0.01f, 0.01f, 0.03f, 0.06f, 0.10f, 0.38f, 0.20f, 0.10f, 0.05f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 6: prev = -1 → continue down, unison, or reverse
    { 0.00f, 0.01f, 0.01f, 0.02f, 0.03f, 0.06f, 0.20f, 0.40f, 0.10f, 0.07f, 0.05f, 0.02f, 0.01f, 0.01f, 0.01f },
    // Row 7: prev = 0 → stay on unison, small steps only
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.03f, 0.07f, 0.20f, 0.32f, 0.20f, 0.07f, 0.03f, 0.02f, 0.01f, 0.00f, 0.00f },
    // Row 8: prev = +1 → continue up, unison, or reverse
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.05f, 0.07f, 0.10f, 0.40f, 0.20f, 0.06f, 0.03f, 0.02f, 0.01f, 0.01f, 0.00f },
    // Row 9: prev = +2
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.05f, 0.10f, 0.20f, 0.38f, 0.10f, 0.06f, 0.03f, 0.01f, 0.01f, 0.01f, 0.00f },
    // Row 10: prev = +3
    { 0.01f, 0.01f, 0.01f, 0.02f, 0.05f, 0.11f, 0.20f, 0.40f, 0.09f, 0.05f, 0.02f, 0.01f, 0.01f, 0.01f, 0.00f },
    // Row 11: prev = +4
    { 0.00f, 0.01f, 0.01f, 0.02f, 0.05f, 0.11f, 0.21f, 0.42f, 0.08f, 0.04f, 0.02f, 0.01f, 0.01f, 0.01f, 0.00f },
    // Row 12: prev = +5
    { 0.00f, 0.01f, 0.01f, 0.02f, 0.05f, 0.12f, 0.21f, 0.43f, 0.07f, 0.03f, 0.02f, 0.01f, 0.01f, 0.01f, 0.00f },
    // Row 13: prev = +6
    { 0.00f, 0.01f, 0.01f, 0.02f, 0.05f, 0.12f, 0.21f, 0.44f, 0.06f, 0.03f, 0.02f, 0.01f, 0.01f, 0.01f, 0.00f },
    // Row 14: prev = +7 → strong unison pull + step back down
    { 0.00f, 0.01f, 0.01f, 0.02f, 0.05f, 0.12f, 0.20f, 0.45f, 0.06f, 0.03f, 0.02f, 0.01f, 0.01f, 0.01f, 0.00f },
};

// Returns the second-order transition matrix for a given envelope profile.
inline const float (*solo_transition_matrix(PulsarEnvelopeProfile profile))[kSoloMatrixSize] {
    switch (profile) {
        case ENV_PROFILE_RHYTHM:  return kRhythmicTransition;
        case ENV_PROFILE_MELODIC: return kMelodicTransition;
        case ENV_PROFILE_EFFECT:  return kEffectTransition;
        case ENV_PROFILE_WILD:    return kWildTransition;
        case ENV_PROFILE_DRONE:   return kDroneTransition;
        default:                  return kMelodicTransition;
    }
}
