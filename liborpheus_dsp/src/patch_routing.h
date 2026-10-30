#pragma once

// Duo patch matrix: per panel duo, per voice, a cable amount for each (source, dest) cell.
// Layout mirrors core/audio/.../patch (PatchSource and PatchDest ordinals are the indices).

struct OrpheusEngine;

constexpr int kNumPanelDuos = 6;
constexpr int kPatchSources = 16;
constexpr int kPatchDests = 6;

enum PatchSourceIndex {
    kPatchSrcLfoAB = 0, kPatchSrcLfoA = 1, kPatchSrcLfoB = 2, kPatchSrcLfoA90 = 3,
    kPatchSrcWaves1 = 4, kPatchSrcWaves2 = 5, kPatchSrcWaves3 = 6, kPatchSrcWaves4 = 7,
    kPatchSrcFluxX1 = 8, kPatchSrcFluxX2 = 9, kPatchSrcFluxX3 = 10, kPatchSrcFluxY = 11,
    kPatchSrcFluxT1 = 12, kPatchSrcFluxT2 = 13, kPatchSrcFluxT3 = 14,
    kPatchSrcVoiceFm = 15,
};

enum PatchDestIndex {
    kPatchDstHarmonics = 0, kPatchDstTimbre = 1, kPatchDstMorph = 2,
    kPatchDstFm = 3, kPatchDstPitch = 4, kPatchDstEnv = 5,
};

// Value at amount 1.0: FM in Hz, PITCH in semitones, the rest in knob units.
constexpr float kPatchFullScale[kPatchDests] = {1.0f, 1.0f, 1.0f, 400.0f, 12.0f, 1.0f};

// One plugged cable at block rate: its offset is value * gain, gain = depth * (amount * full scale).
struct PatchTerm { float value, gain; };
struct PatchVoiceTerms { int count[kPatchDests]; PatchTerm term[kPatchDests][kPatchSources]; };

// The block-rate cables of one voice (FM excluded), in source order. slot is 0 for A, 1 for B.
// Pooled duos (duo >= kNumPanelDuos) get none.
void patch_voice_terms(const OrpheusEngine& e, int duo, int slot, int voice_idx, int num_frames,
                       float depth, PatchVoiceTerms* out);

// Block-rate offsets for one voice, each summed from 0 before the caller adds it to its base.
struct PatchVoiceMods { float timbre, morph, harmonics, note, env; };

PatchVoiceMods patch_term_sums(const PatchVoiceTerms& t);

// patch_voice_terms then patch_term_sums. Zeros for pooled duos.
PatchVoiceMods patch_block_mods(const OrpheusEngine& e, int duo, int slot, int voice_idx,
                                int num_frames, float depth);

// The non-zero FM cells of one voice, gathered once per block. k = amount * full scale.
struct PatchFmTaps { int count; int source[kPatchSources]; float k[kPatchSources]; };

void patch_fm_taps(const OrpheusEngine& e, int duo, int slot, PatchFmTaps* out);

// FM offset in Hz at sample i: the per-sample form of the FM plan below, kept as a public helper.
// partner_sample is the VOICE_FM partner's sample, chosen by the caller.
float patch_fm_hz(const PatchFmTaps& t, const OrpheusEngine& e, int i, float partner_sample,
                  float depth);

// The taps resolved for a sample loop: buffer sources (nullptr = the VOICE_FM partner) with
// gain = depth * k, and the block-constant sources (Flux X1/X2) folded into block_hz.
struct PatchFmPlan {
    int count;
    const float* buf[kPatchSources];
    float gain[kPatchSources];
    bool has_block;
    float block_hz;
};

void patch_fm_plan(const OrpheusEngine& e, const PatchFmTaps& t, float depth, PatchFmPlan* out);

// The voice a VOICE_FM cable reads per sample: the duo partner, or the cross-quad ring
// when fm_cross_quad is set. -1 when out of range.
int patch_fm_partner(const OrpheusEngine& e, int voice_idx);

// Block RMS, the VOICE_FM value on block-rate destinations.
float patch_block_rms(const float* buf, int n);
