#pragma once
#include <cstdint>

// Deterministic per-(step,track,loop) hash — shared by the fire gate, duck gate,
// and any other gate that must repeat identically across identical bars.
inline uint32_t step_hash(int step, int track, int loop) {
    uint32_t h = static_cast<uint32_t>(step * 7919 + track * 104729 + loop * 15485863);
    h ^= h >> 16; h *= 0x45d9f3b; h ^= h >> 16;
    return h;
}

// Negative-density duck gate. density_mod in [-1,0]; returns true if the step
// survives. Distinct salt (track+31) keeps it deterministic but decorrelated
// from the main fire gate which uses step_hash(step, track, loop).
inline bool duck_passes(int playhead, int track, int loop, float density_mod) {
    float gate = 1.0f + density_mod;            // e.g. -0.4 -> 0.6 survive
    uint32_t h = step_hash(playhead, track + 31, loop);
    float roll = static_cast<float>(h & 0xFFFF) / 65535.0f;
    return roll <= gate;
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
