#pragma once

// ── STM32 → Standard C++ compatibility shim ─────────────────────
// Allows original eurorack source to compile unmodified on desktop/mobile/WASM.
// This header is included by Orpheus engine sources ONLY (not by MI code).
// MI code uses its own stmlib.h via the eurorack include path.
//
// Clip16/ClipU16 are provided by stmlib/dsp/dsp.h under -DTEST (set in CMake).
// Do NOT redefine them here — that causes an ODR violation (different return types).
//
// Currently no additional shims needed — MI code compiles cleanly with -DTEST.
