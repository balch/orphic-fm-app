#include "patch_routing.h"
#include "orpheus_engine.h"
#include <cmath>

// Term math is evaluated as written despite -ffast-math, so gains and offsets are the same
// on every build: legacy patches stay bit-exact with the old mod-source formulas.
#if defined(__clang__)
#define PATCH_STRICT_FP _Pragma("clang fp reassociate(off)") _Pragma("clang fp contract(off)")
#else
#define PATCH_STRICT_FP
#endif

float patch_block_rms(const float* buf, int n) {
    float sum_sq = 0.0f;
    for (int i = 0; i < n; i++) sum_sq += buf[i] * buf[i];
    return (n > 0) ? std::sqrt(sum_sq / n) : 0.0f;
}

int patch_fm_partner(const OrpheusEngine& e, int idx) {
    int src = !e.fm_cross_quad.load(std::memory_order_relaxed)
        ? ((idx % 2 == 0) ? idx + 1 : idx - 1)
        : (idx - 2 + 8) % 8;
    return (src >= 0 && src < kNumVoices) ? src : -1;
}

// Block-rate source value: the reads the legacy mod sources made (LFO and Waves at the block
// midpoint, Flux X1/X2 from the cached CV), other Flux outputs at the last sample.
static float block_source(const OrpheusEngine& e, int src, int voice_idx, int num_frames) {
    int mid = num_frames / 2;
    int last = num_frames - 1;
    switch (src) {
        case kPatchSrcLfoAB:  return e.lfo_output_buffer[mid];
        case kPatchSrcLfoA:   return e.lfo_morph_buffer[mid];
        case kPatchSrcLfoB:   return e.lfo_harmonics_buffer[mid];
        case kPatchSrcLfoA90: return e.lfo_pitch_buffer[mid];
        case kPatchSrcWaves1: return e.tides_output_buffer[0][mid];
        case kPatchSrcWaves2: return e.tides_output_buffer[1][mid];
        case kPatchSrcWaves3: return e.tides_output_buffer[2][mid];
        case kPatchSrcWaves4: return e.tides_output_buffer[3][mid];
        case kPatchSrcFluxX1: return e.marbles_cv_output[0];
        case kPatchSrcFluxX2: return e.marbles_cv_output[1];
        case kPatchSrcFluxX3: return e.marbles_x3_buffer[last];
        case kPatchSrcFluxY:  return e.marbles_y_buffer[last];
        case kPatchSrcFluxT1: return e.marbles_t1_buffer[last];
        case kPatchSrcFluxT2: return e.marbles_t2_buffer[last];
        case kPatchSrcFluxT3: return e.marbles_t3_buffer[last];
        // Always the duo partner, never the cross-quad source: unit_process_duo_voice did the same.
        case kPatchSrcVoiceFm: return patch_block_rms(e.voice_fm_buffer[voice_idx ^ 1], num_frames);
        default: return 0.0f;
    }
}

void patch_voice_terms(const OrpheusEngine& e, int duo, int slot, int voice_idx, int num_frames,
                       float depth, PatchVoiceTerms* out) {
    PATCH_STRICT_FP
    for (int d = 0; d < kPatchDests; d++) out->count[d] = 0;
    if (duo < 0 || duo >= kNumPanelDuos || slot < 0 || slot > 1) return;
    for (int s = 0; s < kPatchSources; s++) {
        bool have = false;
        float v = 0.0f;
        for (int d = 0; d < kPatchDests; d++) {
            if (d == kPatchDstFm) continue;
            float amount = e.patch_amount[duo][slot][s][d].load(std::memory_order_relaxed);
            if (amount == 0.0f) continue;
            if (!have) { v = block_source(e, s, voice_idx, num_frames); have = true; }
            out->term[d][out->count[d]++] = {v, depth * (amount * kPatchFullScale[d])};
        }
    }
}

PatchVoiceMods patch_term_sums(const PatchVoiceTerms& t) {
    PATCH_STRICT_FP
    float sum[kPatchDests] = {};
    for (int d = 0; d < kPatchDests; d++)
        for (int j = 0; j < t.count[d]; j++) sum[d] += t.term[d][j].value * t.term[d][j].gain;
    return {sum[kPatchDstTimbre], sum[kPatchDstMorph], sum[kPatchDstHarmonics],
            sum[kPatchDstPitch], sum[kPatchDstEnv]};
}

PatchVoiceMods patch_block_mods(const OrpheusEngine& e, int duo, int slot, int voice_idx,
                                int num_frames, float depth) {
    PatchVoiceTerms t;
    patch_voice_terms(e, duo, slot, voice_idx, num_frames, depth, &t);
    return patch_term_sums(t);
}

void patch_fm_taps(const OrpheusEngine& e, int duo, int slot, PatchFmTaps* out) {
    PATCH_STRICT_FP
    out->count = 0;
    if (duo < 0 || duo >= kNumPanelDuos || slot < 0 || slot > 1) return;
    for (int s = 0; s < kPatchSources; s++) {
        float amount = e.patch_amount[duo][slot][s][kPatchDstFm].load(std::memory_order_relaxed);
        if (amount == 0.0f) continue;
        out->source[out->count] = s;
        out->k[out->count] = amount * kPatchFullScale[kPatchDstFm];
        out->count++;
    }
}

// Per-sample FM buffer of a source; nullptr for the VOICE_FM partner.
static const float* fm_buffer(const OrpheusEngine& e, int src) {
    switch (src) {
        case kPatchSrcLfoAB:  return e.lfo_output_buffer;
        case kPatchSrcLfoA:   return e.lfo_morph_buffer;
        case kPatchSrcLfoB:   return e.lfo_harmonics_buffer;
        case kPatchSrcLfoA90: return e.lfo_pitch_buffer;
        case kPatchSrcWaves1: return e.tides_output_buffer[0];
        case kPatchSrcWaves2: return e.tides_output_buffer[1];
        case kPatchSrcWaves3: return e.tides_output_buffer[2];
        case kPatchSrcWaves4: return e.tides_output_buffer[3];
        case kPatchSrcFluxX3: return e.marbles_x3_buffer;
        case kPatchSrcFluxY:  return e.marbles_y_buffer;
        case kPatchSrcFluxT1: return e.marbles_t1_buffer;
        case kPatchSrcFluxT2: return e.marbles_t2_buffer;
        case kPatchSrcFluxT3: return e.marbles_t3_buffer;
        default: return nullptr;
    }
}

// Per-sample sources take gain = depth * k, which the sample loop fuses as freq + v * gain.
// Flux X1/X2 are block constants, (v * depth) * k, added as Hz.
void patch_fm_plan(const OrpheusEngine& e, const PatchFmTaps& t, float depth, PatchFmPlan* out) {
    PATCH_STRICT_FP
    out->count = 0;
    out->has_block = false;
    out->block_hz = 0.0f;
    for (int j = 0; j < t.count; j++) {
        int src = t.source[j];
        if (src == kPatchSrcFluxX1 || src == kPatchSrcFluxX2) {
            float v = e.marbles_cv_output[src == kPatchSrcFluxX1 ? 0 : 1];
            out->block_hz += (v * depth) * t.k[j];
            out->has_block = true;
        } else {
            out->buf[out->count] = fm_buffer(e, src);
            out->gain[out->count] = depth * t.k[j];
            out->count++;
        }
    }
}

// Per-sample reference for the FM plan, in the sample loops' order; the loops use patch_fm_plan directly.
float patch_fm_hz(const PatchFmTaps& t, const OrpheusEngine& e, int i, float partner_sample,
                  float depth) {
    PATCH_STRICT_FP
    PatchFmPlan p;
    patch_fm_plan(e, t, depth, &p);
    float hz = 0.0f;
    for (int j = 0; j < p.count; j++) hz += (p.buf[j] ? p.buf[j][i] : partner_sample) * p.gain[j];
    if (p.has_block) hz += p.block_hz;
    return hz;
}
