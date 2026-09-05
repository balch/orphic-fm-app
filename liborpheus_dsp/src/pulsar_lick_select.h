#pragma once
#include <cstdint>
#include "pulsar_rng.h"   // for pattern_rand01

// Pure, engine-free lick selection for Fire Sky .5f: a per-section rotation pick and
// a per-statement rare "anomaly" roll. Both advance `rng` in place (xorshift32 via
// pattern_rand01), so a play-scoped seed passed by reference yields deterministic-but-
// varied behavior. Modeled on pulsar_void.h (pure logic, unit-testable in isolation).

// Uniformly pick a rotation member in [0, pool_count). pool_count must be >= 1.
// pool_count <= 1 returns 0 WITHOUT consuming RNG (keeps single-member vibes stable).
inline int lick_pick_rotation(uint32_t& rng, int pool_count) {
    if (pool_count <= 1) return 0;
    int idx = static_cast<int>(pattern_rand01(rng) * static_cast<float>(pool_count));
    if (idx >= pool_count) idx = pool_count - 1;  // guard the rand01 == 1.0 edge
    if (idx < 0) idx = 0;
    return idx;
}

// Roll whether the anomaly fires this statement. chance in [0,1]; <= 0 never fires.
inline bool lick_roll_anomaly(uint32_t& rng, float chance) {
    if (chance <= 0.0f) return false;
    return pattern_rand01(rng) < chance;
}

// Decide the desired bank slot for this statement. On section_changed, re-roll the
// rotation member (updates active_rotation). Then decide the anomaly: either a
// one-shot FORCE (force_anomaly, from the Anomaly Engine's manual trigger — consumes
// no RNG) or the per-statement chance roll (only when an anomaly slot exists, index
// >= 0); either way, if it fires it OVERRIDES the rotation for this statement
// (precedence: anomaly wins). force is checked FIRST in the short-circuit so a forced
// call never touches lick_roll_anomaly's rand01() draw. Returns the desired slot, or
// -1 when pool_count <= 0. Advances rng only where it actually rolls.
//
// forced_index >= 0 pins the rotation to that slot instead of rolling, for Scores that
// assign a theme per section. The anomaly still outranks it, so a theme restatement
// keeps working. Pinning consumes no RNG, so a pinned section leaves the stream
// identical to one that never rotated.
inline int lick_resolve_desired(uint32_t& rng, bool section_changed, int pool_count,
                                int anomaly_index, float anomaly_chance, int& active_rotation,
                                bool force_anomaly = false, int forced_index = -1) {
    if (pool_count <= 0) return -1;
    if (forced_index >= 0 && forced_index < pool_count) {
        active_rotation = forced_index;
    } else if (section_changed) {
        active_rotation = lick_pick_rotation(rng, pool_count);
    }
    bool want_anomaly = (anomaly_index >= 0) && (force_anomaly || lick_roll_anomaly(rng, anomaly_chance));
    return want_anomaly ? anomaly_index : active_rotation;
}
