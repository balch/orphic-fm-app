// Braids macro-oscillator render unit.
// Called from unit_process_plaits (voice path) and the Pulsar track render
// loop, in both cases with the per-voice/per-track MacroOscillator instance.
//
// Engine ID → Braids shape:
//   100..104  TRIPLE_SAW / TRIPLE_SQUARE / TRIPLE_TRIANGLE / TRIPLE_SINE / TRIPLE_RING_MOD
//   105..108  CSAW / TOY / VOWEL_FOF / QUESTION_MARK
//
// Braids renders int16 audio internally at 96 kHz (its native rate; the
// pitch table and oscillator phase increments assume that). We render
// `ceil(num_frames * 96000 / sr)` Braids samples and resample with linear
// interpolation to host rate. This is correct at 44.1 kHz and 48 kHz alike.
//
// HARMONICS knob → Braids parameter_[0] (chord interval on TRIPLE_* engines).
// MORPH knob    → Braids parameter_[1] (detune on TRIPLE_* engines).
// TIMBRE knob   → no-op. Braids' MacroOscillator only has 2 macro params; the
//                 timbre arg is accepted for signature symmetry with Plaits.

#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include "orpheus_voice.h"
#include "braids/macro_oscillator.h"
#include "braids/settings.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

// id → shape lookup. Aligned with OrpheusEngineId Braids range (Task C1).
constexpr braids::MacroOscillatorShape kBraidsShapeTable[] = {
    braids::MACRO_OSC_SHAPE_TRIPLE_SAW,        // 100
    braids::MACRO_OSC_SHAPE_TRIPLE_SQUARE,     // 101
    braids::MACRO_OSC_SHAPE_TRIPLE_TRIANGLE,   // 102
    braids::MACRO_OSC_SHAPE_TRIPLE_SINE,       // 103
    braids::MACRO_OSC_SHAPE_TRIPLE_RING_MOD,   // 104
    braids::MACRO_OSC_SHAPE_CSAW,              // 105
    braids::MACRO_OSC_SHAPE_TOY,               // 106
    braids::MACRO_OSC_SHAPE_VOWEL_FOF,         // 107
    braids::MACRO_OSC_SHAPE_QUESTION_MARK,     // 108
};
constexpr int kBraidsIdBase = 100;
constexpr int kBraidsIdCount =
    static_cast<int>(sizeof(kBraidsShapeTable) / sizeof(kBraidsShapeTable[0]));

// Output gain for Braids voices.
constexpr float kBraidsOutGain = 0.65f;

// Braids' native render rate.
constexpr float kBraidsNativeRate = 96000.0f;

// MacroOscillator::Render must be called with chunk size <= 24.
constexpr int kBraidsInternalBlock = 24;

// Stack-buffer overhead for oversample. 96k / 32k = 3, +1 for safety.
// Caller must ensure num_frames <= kMaxFrames (512).
constexpr int kBraidsOversampleMax = 4;

}  // anonymous namespace

void unit_process_braids(braids::MacroOscillator& osc,
                         float& src_phase,
                         int engine_id,
                         float pitch_midi,
                         float harmonics, float timbre, float morph,
                         float sample_rate,
                         float* out_buffer, int num_frames) {
    int shape_idx = engine_id - kBraidsIdBase;
    if (shape_idx < 0 || shape_idx >= kBraidsIdCount ||
        num_frames <= 0 || sample_rate < 1.0f) {
        std::memset(out_buffer, 0, num_frames * sizeof(float));
        return;
    }

    osc.set_shape(kBraidsShapeTable[shape_idx]);

    // Pitch: Braids set_pitch takes a 16-bit value in 1/128-semitone units.
    // The pitch table assumes 96 kHz output, so set_pitch is rate-independent —
    // we just pass the MIDI note × 128.
    int16_t pitch_q15 = static_cast<int16_t>(std::lround(pitch_midi * 128.0f));
    osc.set_pitch(pitch_q15);

    // Macro params: HARMONICS → p0, MORPH → p1. TIMBRE is unused.
    (void)timbre;
    float h = std::clamp(harmonics, 0.0f, 1.0f);
    float m = std::clamp(morph, 0.0f, 1.0f);
    int16_t p0 = static_cast<int16_t>(h * 32767.0f);
    int16_t p1 = static_cast<int16_t>(m * 32767.0f);
    osc.set_parameters(p0, p1);

    // Resample 96 kHz → host rate.
    //
    // Fast path (sample_rate ≈ 48 kHz, ratio = 2.0): render num_frames * 2
    // samples and decimate by 2 with a 2-tap moving average. Phase-perfect
    // across blocks (and the moving average gives a touch of anti-aliasing).
    //
    // General path (other rates): linear interpolation, with a per-voice
    // fractional source-sample residue (`src_phase`) carried across host
    // blocks so the oscillator phase stays continuous at block boundaries.
    // Each block renders enough source samples to cover all output positions
    // src_phase + i*ratio (i ∈ [0, num_frames)), then advances src_phase by
    // num_frames*ratio − rendered_count, which keeps it bounded near [0, 2).
    //
    // Sample-rate floor: at host rates < ~24 kHz, oversample_frames clamps
    // to kStagingMax and the resampler degrades (audio is bounded but glitchy).
    // Orpheus runs at 44.1k or 48k in practice.
    constexpr int kStagingMax = kMaxFrames * kBraidsOversampleMax;
    int16_t staging[kStagingMax];
    uint8_t sync_buffer[kStagingMax];

    constexpr float kInt16Scale = 1.0f / 32767.0f;
    const float ratio = kBraidsNativeRate / sample_rate;  // 2.0 at 48k, ~2.18 at 44.1k

    if (std::fabs(sample_rate - 48000.0f) < 0.5f) {
        // ── Fast path: 48 kHz host, ratio = 2.0 exactly ──
        int oversample_frames = num_frames * 2;
        if (oversample_frames > kStagingMax) oversample_frames = kStagingMax;
        std::memset(sync_buffer, 0, oversample_frames * sizeof(uint8_t));

        int rendered = 0;
        while (rendered < oversample_frames) {
            int chunk = std::min(kBraidsInternalBlock, oversample_frames - rendered);
            osc.Render(sync_buffer + rendered, staging + rendered,
                       static_cast<size_t>(chunk));
            rendered += chunk;
        }

        for (int i = 0; i < num_frames; i++) {
            float s0 = staging[i * 2]     * kInt16Scale;
            float s1 = staging[i * 2 + 1] * kInt16Scale;
            float sample = (s0 + s1) * 0.5f * kBraidsOutGain;
            out_buffer[i] = soft_limit(sample);
        }
        // Fast path is phase-locked at ratio=2.0; src_phase stays 0.
        src_phase = 0.0f;
    } else {
        // ── General path: arbitrary host sample rate, linear interpolation ──
        // Last output sample's source position: src_phase + (num_frames-1)*ratio.
        // Need staging[idx] and staging[idx+1] for that, so render
        // floor(src_phase + (num_frames-1)*ratio) + 2 source samples.
        const float last_src_pos = src_phase + (num_frames - 1) * ratio;
        int oversample_frames = static_cast<int>(std::floor(last_src_pos)) + 2;
        if (oversample_frames > kStagingMax) {
            oversample_frames = kStagingMax;
        }
        std::memset(sync_buffer, 0, oversample_frames * sizeof(uint8_t));

        int rendered = 0;
        while (rendered < oversample_frames) {
            int chunk = std::min(kBraidsInternalBlock, oversample_frames - rendered);
            osc.Render(sync_buffer + rendered, staging + rendered,
                       static_cast<size_t>(chunk));
            rendered += chunk;
        }

        const int last_valid = rendered - 1;
        for (int i = 0; i < num_frames; i++) {
            float src = src_phase + i * ratio;
            int idx = static_cast<int>(src);
            float frac = src - idx;
            // Defensive clamp — only triggers if oversample_frames was clamped at
            // very low host rates (< ~24 kHz). Bounded but produces glitchy audio.
            if (idx >= last_valid) { idx = last_valid - 1; frac = 1.0f; }
            if (idx < 0) { idx = 0; frac = 0.0f; }
            float s0 = staging[idx]     * kInt16Scale;
            float s1 = staging[idx + 1] * kInt16Scale;
            float sample = (s0 + (s1 - s0) * frac) * kBraidsOutGain;
            out_buffer[i] = soft_limit(sample);
        }

        // Carry residue: oscillator advanced by `rendered` source samples this call.
        // Next block's i=0 logical source position = src_phase + num_frames*ratio,
        // expressed relative to the new staging[0] (which is `rendered` samples ahead).
        src_phase = src_phase + num_frames * ratio - rendered;
        // Defensive: if src_phase drifted negative (shouldn't, since rendered is the
        // floor + 2 and ratio > 1), clamp to 0 to avoid negative idx on the next block.
        if (src_phase < 0.0f) src_phase = 0.0f;
    }
}
