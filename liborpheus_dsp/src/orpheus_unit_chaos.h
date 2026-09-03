#pragma once

#include "orpheus_graph.h"

struct OrpheusEngine;

// Engine-index range for chaos engines. Matches PickerEntry ordinals in VOICE_PICKER_V4_CONFIG.
constexpr int kChaosEngineLorenz  = 200;
constexpr int kChaosEngineRossler = 201;
constexpr int kChaosEngineDuffing = 202;
constexpr int kChaosEngineHenon   = 203;
constexpr int kChaosEngineChua    = 204;
constexpr int kChaosEngineMin     = 200;
constexpr int kChaosEngineMax     = 204;

// Per-voice chaos trajectory state. Stored as an array indexed by voice id on OrpheusEngine.
struct ChaosVoiceState {
    float x = 0.1f;
    float y = 0.0f;
    float z = 0.0f;
    float drive_phase = 0.0f;     // Duffing only
    float carrier_phase = 0.0f;   // sine carrier
    int   blow_up_count = 0;
    int   prev_gate = 0;          // tracks gate state for rising-edge re-seed
};

namespace chaos {

// Render N samples of a chaos voice into `out`. Iterates the appropriate
// attractor kernel for `engine_index`, blends against a pitched sine carrier
// via `morph`, and resets state on numerical blow-up. A rising edge on `gate`
// re-seeds the trajectory (x/y/z/drive_phase; carrier_phase and blow_up_count
// are left alone) so a new note never starts mid-trajectory. The re-seed
// lives HERE, not in callers — the Pulsar path shipped without it for months
// because the old contract left it to the call site.
//
// Parameters:
//   state         per-voice chaos trajectory state, mutated in place
//   engine_index  must be in [kChaosEngineMin, kChaosEngineMax]; otherwise out is zeroed
//   harmonics     modulated, clamped [0,1] — primary chaos coefficient
//   timbre        modulated, clamped [0,1] — chaos integration rate (or update count for Henon)
//   morph         modulated, clamped [0,1] — carrier<->chaos blend (0=pure carrier, 1=raw chaos)
//   note          MIDI note number; carrier and Duffing drive run at this frequency
//   gate          note gate; a 0->1 edge vs the previous call re-seeds the trajectory
//   sample_rate   Hz
//   out           output buffer of length num_frames
//   num_frames    samples to render
void process_chaos_block(
    ChaosVoiceState& state,
    int engine_index,
    float harmonics,
    float timbre,
    float morph,
    float note,
    int gate,
    float sample_rate,
    float* out,
    int num_frames);

}  // namespace chaos

// Render a chaos voice into u->output_buffers[OPORT_OUT].
// Called by unit_process_plaits when vp.engine_index in [kChaosEngineMin, kChaosEngineMax].
// `harmonics` and `morph` are the FM/LFO-modulated values from the dispatcher
// (already clamped to [0, 1]) — mirrors how the Plaits/Braids paths consume them.
void unit_process_chaos(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr,
                        float harmonics, float morph);
