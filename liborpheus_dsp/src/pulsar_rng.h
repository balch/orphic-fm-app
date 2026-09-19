#pragma once
#include <cstdint>

// Deterministic per-(step,track,loop) hash — shared by the fire gate, duck gate,
// and any other gate that must repeat identically across identical bars.
//
// `salt` is the per-load PulsarState::step_salt. Without it the rolls are one global
// table indexed from loop 0, so every load of every vibe replays the same ghosts,
// drifts and fire gates. salt == 0 keeps the unsalted table for pure-function tests.
inline uint32_t step_hash(int step, int track, int loop, uint32_t salt = 0) {
    uint32_t h = static_cast<uint32_t>(step * 7919 + track * 104729 + loop * 15485863);
    h ^= salt;
    h ^= h >> 16; h *= 0x45d9f3b; h ^= h >> 16;
    return h;
}

// Negative-density duck gate. density_mod in [-1,0]; returns true if the step
// survives. Distinct salt (track+31) keeps it deterministic but decorrelated
// from the main fire gate which uses step_hash(step, track, loop).
inline bool duck_passes(int playhead, int track, int loop, float density_mod,
                        uint32_t salt = 0) {
    float gate = 1.0f + density_mod;            // e.g. -0.4 -> 0.6 survive
    uint32_t h = step_hash(playhead, track + 31, loop, salt);
    float roll = static_cast<float>(h & 0xFFFF) / 65535.0f;
    return roll <= gate;
}

// Share of the old quiet skew a roll-gated hit keeps: 1 = as quiet as it used to be,
// 0 = unbiased (loudest). Tuned by ear.
inline constexpr float kJitterQuietBias = 0.5f;

// Per-hit velocity jitter for a step that fired. `h` is the step's fire-gate hash; the
// gate reads its low 16 bits, so the jitter takes the high 16. It used to reuse the low
// ones, and since a hit only fires on a low roll, every roll-gated hit came out quieter
// by (1 - fire_prob) * 0.2 * variation_amt on average and never louder.
inline float fire_velocity_jitter(uint32_t h, float variation_amt, float fire_prob,
                                  bool roll_gated) {
    const float r = static_cast<float>((h >> 16) & 0xFFFF) / 65535.0f;
    const float bias = roll_gated
        ? -(1.0f - fire_prob) * 0.2f * variation_amt * kJitterQuietBias : 0.0f;
    return (r - 0.5f) * 2.0f * variation_amt * 0.2f + bias;
}

// xorshift32 PRNG — deterministic from seed. Canonical home; pattern_gen and
// pulsar_void both consume these.
inline uint32_t pattern_rand(uint32_t& seed) {
    seed ^= seed << 13;
    seed ^= seed >> 17;
    seed ^= seed << 5;
    return seed;
}
inline float pattern_rand01(uint32_t& seed) {
    return static_cast<float>(pattern_rand(seed) & 0x7FFFFF) / static_cast<float>(0x7FFFFF);
}
