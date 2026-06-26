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
