#include "orpheus_unit_chaos.h"
#include "orpheus_engine.h"
#include "chaos/lorenz.h"
#include "chaos/rossler.h"
#include "chaos/duffing.h"
#include "chaos/henon.h"
#include "chaos/chua.h"
#include <cmath>

namespace {
constexpr float kTwoPi = 6.28318530717958647692f;
}

namespace chaos {

// Per-sample chaos render loop, shared by the main voice path
// (unit_process_chaos) and the Pulsar render path. Owns the rising-edge
// re-seed so every caller gets note-on behavior by construction.
void process_chaos_block(ChaosVoiceState& s, int engine_index,
                         float harmonics, float timbre, float morph,
                         float note, int gate, float sample_rate,
                         float* out, int num_frames) {
    // Rising-edge gate detection: re-seed the attractor on note trigger so a
    // new note doesn't start mid-trajectory (potentially near a blow-up
    // region for Henon). carrier_phase and blow_up_count stay untouched.
    if (gate && !s.prev_gate) {
        s.x = 0.1f;
        s.y = 0.0f;
        s.z = 0.0f;
        s.drive_phase = 0.0f;
    }
    s.prev_gate = gate;

    const float note_freq = 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f);
    const float phase_inc = note_freq / sample_rate;

    for (int i = 0; i < num_frames; i++) {
        float chaos_sample = 0.0f;
        switch (engine_index) {
            case kChaosEngineLorenz:
                chaos_sample = chaos::process_lorenz(s, harmonics, timbre, note_freq, sample_rate); break;
            case kChaosEngineRossler:
                chaos_sample = chaos::process_rossler(s, harmonics, timbre, note_freq, sample_rate); break;
            case kChaosEngineDuffing:
                chaos_sample = chaos::process_duffing(s, harmonics, timbre, note_freq, sample_rate); break;
            case kChaosEngineHenon:
                chaos_sample = chaos::process_henon(s, harmonics, timbre, note_freq, sample_rate); break;
            case kChaosEngineChua:
                chaos_sample = chaos::process_chua(s, harmonics, timbre, note_freq, sample_rate); break;
            default:
                break;
        }

        // Blow-up safety: detect runaway trajectories and reset to seed.
        // fabs(NaN) > X is false per IEEE 754, so the magnitude check alone
        // wouldn't catch NaN-poisoned state — explicit isfinite check
        // ensures both NaN and Inf trigger the reset.
        if (!std::isfinite(s.x) || !std::isfinite(s.y) || !std::isfinite(s.z)
            || std::fabs(s.x) + std::fabs(s.y) + std::fabs(s.z) > 1e3f) {
            s.x = 0.1f;
            s.y = 0.0f;
            s.z = 0.0f;
            s.blow_up_count++;
            chaos_sample = 0.0f;
        }

        // Pitched sine carrier.
        const float carrier = std::sin(kTwoPi * s.carrier_phase);
        s.carrier_phase += phase_inc;
        if (s.carrier_phase >= 1.0f) s.carrier_phase -= std::floor(s.carrier_phase);

        // MORPH blend (0 = pure carrier, 1 = pure chaos).
        out[i] = (1.0f - morph) * carrier + morph * chaos_sample;
    }
}

}  // namespace chaos

// Render chaos voice into u->output_buffers[OPORT_OUT].
// Output = (1 - morph) * sine_carrier + morph * chaos_kernel
//   - MORPH=0 → pure pitched carrier (sine at note frequency)
//   - MORPH=1 → raw chaos signal
// Caller (unit_process_plaits) applies the shared envelope/peak/voice_levels tail.
void unit_process_chaos(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr,
                        float harmonics, float morph) {
    const int voice_idx = u->state.module.index;
    auto& vp = engine->voice_params[voice_idx];
    ChaosVoiceState& s = engine->chaos_state[voice_idx];

    const int gate = vp.gate.load(std::memory_order_relaxed);
    const int engine_index = vp.engine_index.load(std::memory_order_relaxed);
    const float timbre = vp.timbre.load(std::memory_order_relaxed);
    const float note = vp.tune.load(std::memory_order_relaxed);

    chaos::process_chaos_block(s, engine_index, harmonics, timbre, morph,
                               note, gate, sr, u->output_buffers[OPORT_OUT], num_frames);
}
