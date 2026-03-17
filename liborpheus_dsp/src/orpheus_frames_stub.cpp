// Stub for frames/keyframer.cc functions that poly_lfo.cc links against.
// The full keyframer.cc depends on STM32 flash storage (stmlib/system/storage.h)
// which cannot compile on desktop/WASM targets.
//
// PolyLfo::Render() calls Keyframer::ConvertToDacCode() to fill dac_code_[],
// but we never read that field — we use level() instead. This stub satisfies
// the linker without pulling in STM32 dependencies.

#include "frames/keyframer.h"

namespace frames {

uint16_t Keyframer::ConvertToDacCode(uint16_t gain, uint8_t response) {
    // Stub: return gain unchanged (identity mapping).
    // This is never read by the Orpheus engine.
    return gain;
}

}  // namespace frames
