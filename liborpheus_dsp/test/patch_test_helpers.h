#pragma once
// Test helpers for the duo patch matrix. set_legacy_patch mirrors Kotlin legacyDuoPatch.
#include "orpheus_engine.h"
#include "patch_routing.h"

// Old per-duo ModSource ordinals (Kotlin LegacyModSource).
enum LegacySource { kLegacyVoiceFm = 0, kLegacyOff = 1, kLegacyLfo = 2, kLegacyFlux = 3, kLegacyTides = 4 };

inline void clear_patch(OrpheusEngine* e, int duo) {
    for (int v = 0; v < 2; v++)
        for (int s = 0; s < kPatchSources; s++)
            for (int d = 0; d < kPatchDests; d++)
                e->patch_amount[duo][v][s][d].store(0.0f, std::memory_order_relaxed);
}

// voice -1 sets both voices (slot BOTH).
inline void set_patch_cell(OrpheusEngine* e, int duo, int voice, int src, int dst, float amount) {
    for (int v = 0; v < 2; v++)
        if (voice < 0 || voice == v)
            e->patch_amount[duo][v][src][dst].store(amount, std::memory_order_relaxed);
}

// Replaces the duo's patch with the legacy conversion. Parked cables (HARMONICS on DX) stay 0.
inline void set_legacy_patch(OrpheusEngine* e, int duo, int legacy_source, bool on_dx) {
    clear_patch(e, duo);
    const float pitch = 0.5f / 12.0f;
    switch (legacy_source) {
        case kLegacyLfo:
            set_patch_cell(e, duo, -1, kPatchSrcLfoAB, kPatchDstTimbre, 1.0f);
            set_patch_cell(e, duo, -1, kPatchSrcLfoA, kPatchDstMorph, 0.5f);
            if (!on_dx) set_patch_cell(e, duo, -1, kPatchSrcLfoB, kPatchDstHarmonics, 0.5f);
            set_patch_cell(e, duo, -1, kPatchSrcLfoA90, kPatchDstPitch, pitch);
            set_patch_cell(e, duo, -1, kPatchSrcLfoAB, kPatchDstFm, 0.5f);
            break;
        case kLegacyTides:
            set_patch_cell(e, duo, -1, kPatchSrcWaves1, kPatchDstTimbre, 1.0f);
            set_patch_cell(e, duo, -1, kPatchSrcWaves2, kPatchDstMorph, 0.5f);
            if (!on_dx) set_patch_cell(e, duo, -1, kPatchSrcWaves3, kPatchDstHarmonics, 0.5f);
            set_patch_cell(e, duo, -1, kPatchSrcWaves4, kPatchDstPitch, pitch);
            set_patch_cell(e, duo, -1, kPatchSrcWaves1, kPatchDstFm, 0.5f);
            break;
        case kLegacyFlux: {
            int x = (duo % 2 == 0) ? kPatchSrcFluxX1 : kPatchSrcFluxX2;
            set_patch_cell(e, duo, -1, x, kPatchDstTimbre, 1.0f);
            set_patch_cell(e, duo, -1, x, kPatchDstFm, 0.5f);
            break;
        }
        case kLegacyVoiceFm:
            set_patch_cell(e, duo, -1, kPatchSrcVoiceFm, kPatchDstTimbre, 1.0f);
            set_patch_cell(e, duo, -1, kPatchSrcVoiceFm, kPatchDstFm, 0.5f);
            break;
        default:  // OFF
            break;
    }
}
