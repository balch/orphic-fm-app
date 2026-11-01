// Float classification on the bits, safe under -ffast-math. Clang there assumes floats are
// finite and folds std::isfinite, NaN compares and even a plain bit test, so the bits pass
// through a volatile it can't see through. Standalone: Kotlin-parity headers include it.
#pragma once

#include <cstdint>
#include <cstring>

inline uint32_t orpheus_float_bits(float x) {
    uint32_t raw;
    std::memcpy(&raw, &x, sizeof(raw));
    volatile uint32_t bits = raw;
    return bits;
}

inline bool orpheus_is_finite(float x) { return ((orpheus_float_bits(x) >> 23) & 0xFFu) != 0xFFu; }
inline bool orpheus_is_pos_inf(float x) { return orpheus_float_bits(x) == 0x7F800000u; }
