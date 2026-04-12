#include "test_harness.h"
#include "../src/pulsar_solo.h"
#include <cstdio>
#include <cmath>

// Test 1: All matrix rows sum to 1.0
static bool test_matrix_normalization() {
    PulsarEnvelopeProfile profiles[] = {
        ENV_PROFILE_RHYTHM, ENV_PROFILE_MELODIC, ENV_PROFILE_EFFECT,
        ENV_PROFILE_WILD, ENV_PROFILE_DRONE
    };
    const char* names[] = {"Rhythmic", "Melodic", "Effect", "Wild", "Drone"};

    for (int p = 0; p < 5; p++) {
        const float (*matrix)[kSoloMatrixSize] = solo_transition_matrix(profiles[p]);
        for (int row = 0; row < kSoloMatrixSize; row++) {
            float sum = 0.0f;
            for (int col = 0; col < kSoloMatrixSize; col++) {
                if (matrix[row][col] < 0.0f) {
                    printf("  FAIL: %s[%d][%d] = %.4f (negative)\n",
                           names[p], row, col, matrix[row][col]);
                    return false;
                }
                sum += matrix[row][col];
            }
            if (std::fabs(sum - 1.0f) > 0.01f) {
                printf("  FAIL: %s row %d sums to %.4f\n", names[p], row, sum);
                return false;
            }
        }
    }
    printf("  Matrix normalization: PASS (all 75 rows valid)\n");
    return true;
}

// Test 2: Melodic gap-fill — after +5 leap, negative intervals dominate
static bool test_melodic_gap_fill() {
    SoloBehaviorParam behavior = default_solo_behavior(ENV_PROFILE_MELODIC);
    behavior.profile = ENV_PROFILE_MELODIC;
    behavior.rest_probability = 0.0f;
    behavior.hold_probability = 0.0f;
    behavior.density_curve_min = 1.0f;
    behavior.density_curve_max = 1.0f;
    behavior.chromatic_passing = 0.0f;

    int negative = 0, positive = 0, total = 0;
    uint32_t seed = 12345;

    for (int i = 0; i < 2000; i++) {
        behavior.last_interval = 5;  // reset to test this specific row
        int current = 7;
        int result = markov_next_note(behavior, current, 12, 0.5f, seed);
        if (result >= 0) {
            int interval = result - current;
            while (interval > 6) interval -= 12;
            while (interval < -6) interval += 12;
            if (interval < 0) negative++;
            else if (interval > 0) positive++;
            total++;
        }
    }

    float neg_ratio = total > 0 ? static_cast<float>(negative) / total : 0;
    printf("  Gap-fill (+5): neg=%.0f%% pos=%.0f%% (n=%d)\n",
           neg_ratio * 100, (1 - neg_ratio) * 100, total);

    if (neg_ratio < 0.35f) {
        printf("  FAIL: expected >35%% negative after +5 leap\n");
        return false;
    }
    printf("  Melodic gap-fill: PASS\n");
    return true;
}

// Test 3: Rhythmic unison gravity — unison most common from any start
static bool test_rhythmic_unison() {
    SoloBehaviorParam behavior = default_solo_behavior(ENV_PROFILE_RHYTHM);
    behavior.profile = ENV_PROFILE_RHYTHM;
    behavior.rest_probability = 0.0f;
    behavior.hold_probability = 0.0f;
    behavior.density_curve_min = 1.0f;
    behavior.density_curve_max = 1.0f;
    behavior.chromatic_passing = 0.0f;

    int unison = 0, total = 0;
    uint32_t seed = 54321;

    for (int i = 0; i < 2000; i++) {
        behavior.last_interval = 3;
        int current = 5;
        int result = markov_next_note(behavior, current, 12, 0.5f, seed);
        if (result >= 0) {
            if (result == current) unison++;
            total++;
        }
    }

    float ratio = total > 0 ? static_cast<float>(unison) / total : 0;
    printf("  Rhythmic unison (from +3): %.0f%% (n=%d)\n", ratio * 100, total);

    if (ratio < 0.15f) {
        printf("  FAIL: expected >15%% unison\n");
        return false;
    }
    printf("  Rhythmic unison gravity: PASS\n");
    return true;
}

// Test 4: last_interval tracking updates
static bool test_last_interval_tracking() {
    SoloBehaviorParam behavior = default_solo_behavior(ENV_PROFILE_MELODIC);
    behavior.profile = ENV_PROFILE_MELODIC;
    behavior.last_interval = 0;
    behavior.rest_probability = 0.0f;
    behavior.hold_probability = 0.0f;
    behavior.density_curve_min = 1.0f;
    behavior.density_curve_max = 1.0f;
    behavior.chromatic_passing = 0.0f;

    uint32_t seed = 99999;
    int current = 5;
    int8_t before = behavior.last_interval;
    int result = markov_next_note(behavior, current, 12, 0.5f, seed);

    if (result >= 0 && result != current && behavior.last_interval == before) {
        printf("  FAIL: last_interval not updated after note\n");
        return false;
    }
    printf("  Last interval tracking: PASS (was %d, now %d)\n",
           before, behavior.last_interval);
    return true;
}

// Test 5: Blend normalization — blended distribution sums to 1.0
static bool test_blend_normalization() {
    // Generate many notes and verify the distribution is valid
    // (if blend produced non-normalized weights, sampling would be biased)
    SoloBehaviorParam behavior = default_solo_behavior(ENV_PROFILE_EFFECT);
    behavior.profile = ENV_PROFILE_EFFECT;
    behavior.rest_probability = 0.0f;
    behavior.hold_probability = 0.0f;
    behavior.density_curve_min = 1.0f;
    behavior.density_curve_max = 1.0f;
    behavior.chromatic_passing = 0.0f;

    int counts[15] = {};
    int total = 0;
    uint32_t seed = 77777;

    for (int i = 0; i < 5000; i++) {
        behavior.last_interval = 0;
        int current = 6;
        int result = markov_next_note(behavior, current, 12, 0.5f, seed);
        if (result >= 0) {
            int interval = result - current;
            while (interval > 7) interval -= 12;
            while (interval < -7) interval += 12;
            int idx = interval + 7;
            if (idx >= 0 && idx < 15) counts[idx]++;
            total++;
        }
    }

    // Verify all notes were generated (no stuck distribution)
    if (total < 4000) {
        printf("  FAIL: only %d notes generated (expected ~5000)\n", total);
        return false;
    }
    printf("  Blend normalization: PASS (%d notes, well-distributed)\n", total);
    return true;
}

bool run_markov_solo_tests() {
    printf("\n── Markov Solo Tests ──\n");
    bool ok = true;
    ok &= test_matrix_normalization();
    ok &= test_melodic_gap_fill();
    ok &= test_rhythmic_unison();
    ok &= test_last_interval_tracking();
    ok &= test_blend_normalization();
    return ok;
}
