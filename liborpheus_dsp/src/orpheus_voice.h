#pragma once

// OrpheusVoice: lightweight Plaits voice wrapper that calls Engine::Render()
// directly, bypassing plaits::Voice's LPG, limiter, and int16 conversion.
// This matches the Kotlin (JSyn) signal path: engine.render() -> outGain -> softLimit().

#include "stmlib/utils/buffer_allocator.h"

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

// Per-engine output gain, matching Kotlin DspPlaitsUnit outGain values.
// Engines 0-7 (bank 2): no Kotlin impl, use default 0.3.
// Engines 8-23: match Kotlin per-engine values.
static const float kOutGain[kOrpheusMaxEngines] = {
    0.3f,   //  0: VirtualAnalogVCF (no Kotlin impl, default)
    0.3f,   //  1: PhaseDistortion (no Kotlin impl)
    0.3f,   //  2: SixOp FM1 (no Kotlin impl)
    0.3f,   //  3: SixOp FM2 (no Kotlin impl)
    0.3f,   //  4: SixOp FM3 (no Kotlin impl)
    0.3f,   //  5: WaveTerrain (no Kotlin impl)
    0.3f,   //  6: StringMachine (no Kotlin impl)
    0.3f,   //  7: Chiptune (no Kotlin impl)
    0.30f,  //  8: VirtualAnalog
    0.25f,  //  9: Waveshaping
    0.30f,  // 10: FM
    0.30f,  // 11: Grain
    0.30f,  // 12: Additive
    0.50f,  // 13: Wavetable
    0.30f,  // 14: Chord
    0.50f,  // 15: Speech
    0.30f,  // 16: Swarm
    0.30f,  // 17: Noise
    0.30f,  // 18: Particle
    0.30f,  // 19: String
    0.30f,  // 20: Modal
    0.30f,  // 21: BassDrum
    0.30f,  // 22: SnareDrum
    0.30f,  // 23: HiHat
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

    // State for engine switching and trigger detection.
    int previous_engine_index_;
    bool trigger_state_;

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

        // Handle engine switching: reset on change.
        if (engine_index != previous_engine_index_) {
            e->Reset();
            previous_engine_index_ = engine_index;
        }

        float gain = kOutGain[engine_index];

        // Render in blocks of kOrpheusBlockSize (24), matching Kotlin.
        int frames_rendered = 0;
        while (frames_rendered < num_frames) {
            int block = num_frames - frames_rendered;
            if (block > kOrpheusBlockSize) block = kOrpheusBlockSize;

            // Trigger edge detection (Schmitt trigger).
            bool gate_on = (gate != 0);
            bool rising_edge = gate_on && !trigger_state_;
            if (gate_on) {
                trigger_state_ = true;
            } else {
                trigger_state_ = false;
            }

            // Build engine parameters.
            plaits::EngineParameters p;
            p.trigger = rising_edge
                ? (plaits::TRIGGER_RISING_EDGE | plaits::TRIGGER_HIGH)
                : (gate_on ? plaits::TRIGGER_HIGH : plaits::TRIGGER_LOW);
            p.note = note;
            p.harmonics = harmonics;
            p.timbre = timbre;
            p.morph = morph;
            p.accent = accent;

            bool already_enveloped = false;
            e->Render(p, out_buffer_, aux_buffer_, block, &already_enveloped);

            // Apply per-engine outGain and soft_limit, matching Kotlin path.
            for (int i = 0; i < block; i++) {
                out[frames_rendered + i] = soft_limit(out_buffer_[i] * gain);
            }

            frames_rendered += block;
        }
    }
};
