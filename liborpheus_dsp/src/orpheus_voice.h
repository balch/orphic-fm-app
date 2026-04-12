#pragma once

// OrpheusVoice: lightweight Plaits voice wrapper that calls Engine::Render()
// directly, bypassing plaits::Voice's LPG, limiter, and int16 conversion.
// This matches the Kotlin (JSyn) signal path: engine.render() -> outGain -> softLimit().

#include "stmlib/utils/buffer_allocator.h"

#include "plaits/resources.h"

#include "plaits/dsp/engine/additive_engine.h"
#include "plaits/dsp/engine/bass_drum_engine.h"
#include "plaits/dsp/engine/chord_engine.h"
#include "plaits/dsp/engine/engine.h"
#include "plaits/dsp/engine/fm_engine.h"
#include "plaits/dsp/engine/grain_engine.h"
#include "plaits/dsp/engine/hi_hat_engine.h"
#include "plaits/dsp/engine/modal_engine.h"
#include "plaits/dsp/engine/noise_engine.h"
#include "plaits/dsp/engine/particle_engine.h"
#include "plaits/dsp/engine/snare_drum_engine.h"
#include "plaits/dsp/engine/speech_engine.h"
#include "plaits/dsp/engine/string_engine.h"
#include "plaits/dsp/engine/swarm_engine.h"
#include "plaits/dsp/engine/virtual_analog_engine.h"
#include "plaits/dsp/engine/waveshaping_engine.h"
#include "plaits/dsp/engine/wavetable_engine.h"

#include "plaits/dsp/engine2/chiptune_engine.h"
#include "plaits/dsp/engine2/phase_distortion_engine.h"
#include "plaits/dsp/engine2/six_op_engine.h"
#include "plaits/dsp/engine2/string_machine_engine.h"
#include "plaits/dsp/engine2/virtual_analog_vcf_engine.h"
#include "plaits/dsp/engine2/wave_terrain_engine.h"

#include <cmath>
#include <cstring>

// Block size matching Kotlin's PLAITS_BLOCK_SIZE and plaits::kMaxBlockSize.
static constexpr int kOrpheusBlockSize = 24;

// Number of registered Plaits engines.
static constexpr int kOrpheusMaxEngines = 24;

// Engine 0 (triangle/square oscillator) output gain.
// Balances Engine 0 level against Plaits engines so switching engines
// doesn't produce a jarring volume jump.
static constexpr float kEngine0OutGain = 0.65f;

// Per-engine output gain for Orpheus voice rendering.
// Scaled from MI registration out_gain × ~0.56 to match Kotlin levels,
// accounting for Orpheus bypassing the Plaits LPG/limiter pipeline.
static const float kOrpheusOutGain[kOrpheusMaxEngines] = {
    0.55f,  //  0: VirtualAnalogVCF (MI out_gain=1.0, hot analog + filter)
    0.38f,  //  1: PhaseDistortion  (MI out_gain=0.7, moderate output)
    0.55f,  //  2: SixOp FM1        (MI out_gain=1.0, internal envelope)
    0.55f,  //  3: SixOp FM2        (MI out_gain=1.0, internal envelope)
    0.55f,  //  4: SixOp FM3        (MI out_gain=1.0, internal envelope)
    0.38f,  //  5: WaveTerrain      (MI out_gain=0.7, moderate output)
    0.45f,  //  6: StringMachine    (MI out_gain=0.8, ensemble adds energy)
    0.28f,  //  7: Chiptune         (MI out_gain=0.5, loudest raw output)
    0.45f,  //  8: VirtualAnalog
    0.38f,  //  9: Waveshaping
    0.45f,  // 10: FM
    0.55f,  // 11: Grain
    0.45f,  // 12: Additive
    0.70f,  // 13: Wavetable
    0.45f,  // 14: Chord
    0.70f,  // 15: Speech
    0.45f,  // 16: Swarm
    0.65f,  // 17: Noise
    0.45f,  // 18: Particle
    0.75f,  // 19: String
    0.65f,  // 20: Modal
    1.00f,  // 21: BassDrum  (matches Kotlin AnalogBassDrumEngine)
    1.00f,  // 22: SnareDrum (matches Kotlin AnalogSnareDrumEngine)
    1.00f,  // 23: HiHat     (matches Kotlin MetallicHiHatEngine)
};

// Soft saturation matching Kotlin DspPlaitsUnit.softLimit():
// Linear below 0.5, tanh saturation above.
static inline float soft_limit(float x) {
    float ax = std::fabs(x);
    if (ax < 0.5f) return x;
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    return sign * (0.5f + 0.5f * std::tanh((ax - 0.5f) * 2.0f));
}

struct OrpheusVoice {
    // Engine instances — same set as plaits::Voice.
    plaits::VirtualAnalogVCFEngine  virtual_analog_vcf_engine_;
    plaits::PhaseDistortionEngine   phase_distortion_engine_;
    plaits::SixOpEngine             six_op_engine_;
    plaits::WaveTerrainEngine       wave_terrain_engine_;
    plaits::StringMachineEngine     string_machine_engine_;
    plaits::ChiptuneEngine          chiptune_engine_;
    plaits::VirtualAnalogEngine     virtual_analog_engine_;
    plaits::WaveshapingEngine       waveshaping_engine_;
    plaits::FMEngine                fm_engine_;
    plaits::GrainEngine             grain_engine_;
    plaits::AdditiveEngine          additive_engine_;
    plaits::WavetableEngine         wavetable_engine_;
    plaits::ChordEngine             chord_engine_;
    plaits::SpeechEngine            speech_engine_;
    plaits::SwarmEngine             swarm_engine_;
    plaits::NoiseEngine             noise_engine_;
    plaits::ParticleEngine          particle_engine_;
    plaits::StringEngine            string_engine_;
    plaits::ModalEngine             modal_engine_;
    plaits::BassDrumEngine          bass_drum_engine_;
    plaits::SnareDrumEngine         snare_drum_engine_;
    plaits::HiHatEngine             hi_hat_engine_;

    plaits::EngineRegistry<kOrpheusMaxEngines> engines_;

    // Render scratch buffers (sized to kMaxBlockSize = 24).
    float out_buffer_[plaits::kMaxBlockSize];
    float aux_buffer_[plaits::kMaxBlockSize];

    // Remainder buffer: when num_frames isn't divisible by kOrpheusBlockSize,
    // the engine renders a full 24-sample block but only some samples are
    // consumed. The leftovers are stored here and drained on the next call,
    // preventing phase/state discontinuities that cause crackling.
    float remainder_buffer_[kOrpheusBlockSize];
    int   remainder_count_;  // number of valid samples in remainder_buffer_

    // State for engine switching and trigger detection.
    int previous_engine_index_;
    bool trigger_state_;

    // Anti-click: last output sample for retrigger crossfade.
    float last_sample_;

    // Initialize all engines. Must be called once before Render().
    // The allocator is freed between each engine Init() so all engines
    // share the same RAM space (same pattern as plaits::Voice::Init).
    void Init(stmlib::BufferAllocator* allocator) {
        engines_.Init();

        // Registration order must match plaits::Voice::Init() exactly.
        // SixOpEngine is registered three times (indices 2, 3, 4) — same instance.
        engines_.RegisterInstance(&virtual_analog_vcf_engine_, false, 1.0f, 1.0f);
        engines_.RegisterInstance(&phase_distortion_engine_,   false, 0.7f, 0.7f);
        engines_.RegisterInstance(&six_op_engine_,             true,  1.0f, 1.0f);
        engines_.RegisterInstance(&six_op_engine_,             true,  1.0f, 1.0f);
        engines_.RegisterInstance(&six_op_engine_,             true,  1.0f, 1.0f);
        engines_.RegisterInstance(&wave_terrain_engine_,       false, 0.7f, 0.7f);
        engines_.RegisterInstance(&string_machine_engine_,     false, 0.8f, 0.8f);
        engines_.RegisterInstance(&chiptune_engine_,           false, 0.5f, 0.5f);

        engines_.RegisterInstance(&virtual_analog_engine_,     false, 0.8f, 0.8f);
        engines_.RegisterInstance(&waveshaping_engine_,        false, 0.7f, 0.6f);
        engines_.RegisterInstance(&fm_engine_,                 false, 0.6f, 0.6f);
        engines_.RegisterInstance(&grain_engine_,              false, 0.7f, 0.6f);
        engines_.RegisterInstance(&additive_engine_,           false, 0.8f, 0.8f);
        engines_.RegisterInstance(&wavetable_engine_,          false, 0.6f, 0.6f);
        engines_.RegisterInstance(&chord_engine_,              false, 0.8f, 0.8f);
        engines_.RegisterInstance(&speech_engine_,             false, -0.7f, 0.8f);

        engines_.RegisterInstance(&swarm_engine_,              false, -3.0f, 1.0f);
        engines_.RegisterInstance(&noise_engine_,              false, -1.0f, -1.0f);
        engines_.RegisterInstance(&particle_engine_,           false, -2.0f, 1.0f);
        engines_.RegisterInstance(&string_engine_,             true,  -1.0f, 0.8f);
        engines_.RegisterInstance(&modal_engine_,              true,  -1.0f, 0.8f);
        engines_.RegisterInstance(&bass_drum_engine_,          true,  0.8f, 0.8f);
        engines_.RegisterInstance(&snare_drum_engine_,         true,  0.8f, 0.8f);
        engines_.RegisterInstance(&hi_hat_engine_,             true,  0.8f, 0.8f);

        for (int i = 0; i < engines_.size(); ++i) {
            // All engines share the same RAM space.
            allocator->Free();
            engines_.get(i)->Init(allocator);
        }

        previous_engine_index_ = -1;
        trigger_state_ = false;
        remainder_count_ = 0;
        last_sample_ = 0.0f;
    }

    // Render audio using the specified engine.
    //
    // Parameters:
    //   engine_index  - Plaits engine index (0-23)
    //   gate          - gate signal (0 or 1)
    //   note          - MIDI note number
    //   harmonics     - 0..1
    //   timbre        - 0..1
    //   morph         - 0..1
    //   accent        - 0..1
    //   out           - output buffer (num_frames floats)
    //   num_frames    - number of frames to render
    //
    // Output has per-engine outGain and soft_limit() applied.
    void Render(
            int engine_index,
            int gate,
            float note,
            float harmonics,
            float timbre,
            float morph,
            float accent,
            float* out,
            int num_frames) {

        // Clamp engine index to valid range.
        if (engine_index < 0) engine_index = 0;
        if (engine_index >= engines_.size()) engine_index = engines_.size() - 1;

        plaits::Engine* e = engines_.get(engine_index);

        // Handle engine switching: reset and load user data on change.
        // SixOp engines (indices 2-4) require FM patch data from resources.
        if (engine_index != previous_engine_index_) {
            const uint8_t* user_data = nullptr;
            if (engine_index >= 2 && engine_index <= 4) {
                user_data = plaits::fm_patches_table[engine_index - 2];
            }
            e->LoadUserData(user_data);
            e->Reset();
            previous_engine_index_ = engine_index;
            remainder_count_ = 0;  // discard stale remainder on engine switch
        }

        float gain = kOrpheusOutGain[engine_index];

        // Render in fixed blocks of kOrpheusBlockSize (24), matching Kotlin.
        // Always render full blocks — SixOpEngine's staggered rendering uses
        // acc_buffer_ across calls and breaks if block sizes vary.
        // Leftover samples from the last block are buffered in remainder_buffer_
        // and drained at the start of the next call to prevent discontinuities.
        int frames_rendered = 0;

        // ── Drain remainder from previous call ──
        if (remainder_count_ > 0) {
            int drain = (remainder_count_ < num_frames)
                ? remainder_count_ : num_frames;
            std::memcpy(out, remainder_buffer_, drain * sizeof(float));
            if (drain > 0) last_sample_ = out[drain - 1];
            // Shift any undrained remainder forward
            remainder_count_ -= drain;
            if (remainder_count_ > 0) {
                std::memmove(remainder_buffer_, remainder_buffer_ + drain,
                             remainder_count_ * sizeof(float));
            }
            frames_rendered = drain;
        }

        // ── Render fresh blocks ──
        while (frames_rendered < num_frames) {
            // Trigger edge detection (Schmitt trigger).
            bool gate_on = (gate != 0);
            bool rising_edge = gate_on && !trigger_state_;
            trigger_state_ = gate_on;

            // Build engine parameters.
            //
            // Do NOT set TRIGGER_UNPATCHED — it means "no trigger jack
            // connected" and puts engines into drone/sustain mode (String
            // bows forever, Speech free-runs, Swarm loses gate response).
            plaits::EngineParameters p;
            p.trigger = rising_edge
                ? (plaits::TRIGGER_RISING_EDGE | plaits::TRIGGER_HIGH)
                : (gate_on ? plaits::TRIGGER_HIGH : plaits::TRIGGER_LOW);
            p.note = note;
            p.timbre = timbre;
            p.harmonics = harmonics;
            p.morph = morph;
            p.accent = accent;

            // Remap Swarm (16) and Particle (18) knob curves.
            // MI's internal scaling is very aggressive (exponential/cubic/squared),
            // pushing toward noise quickly. Quadratic pre-curve compresses the
            // upper range so ~70% of knob travel stays in the pitched zone.
            if (engine_index == 16 || engine_index == 18) {
                p.timbre = timbre * timbre;
                p.harmonics = harmonics * harmonics;
            }

            bool already_enveloped = false;
            e->Render(p, out_buffer_, aux_buffer_, kOrpheusBlockSize, &already_enveloped);

            // Anti-click: on rising edge, crossfade the first 12 samples
            // from the last output level to the new engine output.
            // This prevents phase discontinuities when oscillators snap to
            // new frequencies (especially audible on Chord engine retrigger).
            // Works in post-gain domain (last_sample_ stores post-gain value).
            if (rising_edge) {
                static constexpr int kXfadeLen = 12;  // 0.25ms at 48kHz
                int xf = (kOrpheusBlockSize < kXfadeLen) ? kOrpheusBlockSize : kXfadeLen;
                for (int i = 0; i < xf; i++) {
                    float alpha = static_cast<float>(i + 1) / static_cast<float>(xf + 1);
                    float new_sample = soft_limit(out_buffer_[i] * gain);
                    out_buffer_[i] = (last_sample_ * (1.0f - alpha) + new_sample * alpha);
                    // Store back in out_buffer_ as post-gain (bypass gain/limit below)
                }
                // Copy the crossfaded samples directly, apply gain/limit to rest
                int frames_needed = num_frames - frames_rendered;
                int copy_count = (frames_needed < kOrpheusBlockSize)
                    ? frames_needed : kOrpheusBlockSize;
                for (int i = 0; i < copy_count; i++) {
                    if (i < xf) {
                        out[frames_rendered + i] = out_buffer_[i];  // already post-gain
                    } else {
                        out[frames_rendered + i] = soft_limit(out_buffer_[i] * gain);
                    }
                }
                if (copy_count > 0) {
                    last_sample_ = out[frames_rendered + copy_count - 1];
                }
                if (copy_count < kOrpheusBlockSize) {
                    remainder_count_ = kOrpheusBlockSize - copy_count;
                    for (int i = 0; i < remainder_count_; i++) {
                        remainder_buffer_[i] = soft_limit(out_buffer_[copy_count + i] * gain);
                    }
                }
                frames_rendered += copy_count;
            } else {
                int frames_needed = num_frames - frames_rendered;
                if (frames_needed >= kOrpheusBlockSize) {
                    for (int i = 0; i < kOrpheusBlockSize; i++) {
                        out[frames_rendered + i] = soft_limit(out_buffer_[i] * gain);
                    }
                    last_sample_ = out[frames_rendered + kOrpheusBlockSize - 1];
                    frames_rendered += kOrpheusBlockSize;
                } else {
                    for (int i = 0; i < frames_needed; i++) {
                        out[frames_rendered + i] = soft_limit(out_buffer_[i] * gain);
                    }
                    if (frames_needed > 0) {
                        last_sample_ = out[frames_rendered + frames_needed - 1];
                    }
                    remainder_count_ = kOrpheusBlockSize - frames_needed;
                    for (int i = 0; i < remainder_count_; i++) {
                        remainder_buffer_[i] = soft_limit(out_buffer_[frames_needed + i] * gain);
                    }
                    frames_rendered += frames_needed;
                }
            }
        }
    }
};
