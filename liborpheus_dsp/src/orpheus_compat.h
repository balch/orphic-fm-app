#pragma once

// ── STM32 → Standard C++ compatibility shim ─────────────────────
// Allows original eurorack source to compile unmodified on desktop/mobile/WASM.
// This header is included by Orpheus engine sources ONLY (not by MI code).
// MI code uses its own stmlib.h via the eurorack include path.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

// ARM SSAT intrinsics → standard clamp for non-ARM builds.
// These are used by stmlib and MI code when compiled with -DTEST.
#ifndef __arm__
inline int16_t Clip16(int32_t x) {
    if (x < -32768) x = -32768;
    if (x > 32767) x = 32767;
    return static_cast<int16_t>(x);
}
inline uint16_t ClipU16(int32_t x) {
    if (x < 0) x = 0;
    if (x > 65535) x = 65535;
    return static_cast<uint16_t>(x);
}
#endif
