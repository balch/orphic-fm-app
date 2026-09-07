#pragma once

#include "osc_core.h"        // OscCore, kEngine0OutGain
#include "pulsar_mod_ranges.h"
#include <cmath>

// Per-track OSC state for the Pulsar beat machine, used when engine_index < 0.
// A POD struct rather than a second tides::PolySlopeGenerator: far smaller, and
// it does not worsen the copy-assignability constraint on PulsarTrackState.
struct PulsarOscState {
    OscCore core;
    float mod_phase = 0.0f;
    int prev_gate = 0;
};

// Playability bounds for OSC (kEngineModRanges is [24] indexed by Plaits id,
// so engine_index -1 cannot use it). harmonics_max and note_min hold the
// no-clamp condition harmonics*200 < carrier_hz (70Hz vs the 82.4Hz note-40
// carrier) -- past it, self-feedback drives freq negative and freezes the
// oscillator at OscCore::Next's 1Hz floor instead of a tone.
static constexpr EngineModRange kOscModRange =
    { 0.00f,0.35f, 0.00f,1.00f, 0.00f,1.00f, 12.0f, true,true, 0.0f,0.0f,0.0f, 40 };

namespace osc {

// Modulation index at morph = 1, in radians (DX7 indices run to ~10).
// I = morph * kFmIndexMax.
constexpr float kFmIndexMax = 8.0f;

// OscCore's phase is in cycles, so a radian index converts by 1/2pi.
constexpr float kIndexToCycles = 1.0f / (2.0f * 3.14159265358979f);

// Modulator waveform: sine (0) through triangle (0.5) to square (1).
// Crossfading rather than switching keeps fm_shape continuous under macro
// modulation, which would otherwise click on every crossing.
inline float mod_wave(float phase, float shape) {
    float sine = std::sin(phase * 2.0f * 3.14159265358979f);
    float tri = 4.0f * std::fabs(phase - 0.5f) - 1.0f;
    float sq = (phase < 0.5f) ? 1.0f : -1.0f;
    if (shape < 0.5f) {
        float t = shape * 2.0f;
        return sine * (1.0f - t) + tri * t;
    }
    float t = (shape - 0.5f) * 2.0f;
    return tri * (1.0f - t) + sq * t;
}

// Renders num_frames of the Pulsar OSC voice into `out`.
//
// No VCA and no envelope: Pulsar multiplies its own envelope onto the track
// buffer downstream. `gate` is used only to reset modulator phase on a rising
// edge, so a note never starts mid-modulator-cycle. The reset lives HERE, not
// at the call site, because the chaos engines shipped for months without one
// when that was left to callers.
//
// The two modes modulate different things, deliberately.
//
// Ratio mode is PHASE modulation at index I = morph * kFmIndexMax, so the
// carrier rate is untouched and the pitch is the written note at every index.
// Linear FM at that index would drive the carrier negative and OscCore's 1Hz
// floor would half-rectify it, lifting perceived pitch by octaves.
//
// Free-run mode (fm_free_hz > 0) stays on the FREQUENCY path with the Orpheus
// panel's literal +-200Hz, floor clamping included, so a pasted panel preset
// transfers exactly. It is pitch-bending by design and ratio mode is the default.
//
// kEngine0OutGain is the same trim the three main-synth OSC sites apply. Without
// it a Pulsar OSC track runs 1.54x hotter than the panel voice it reproduces and
// ~1.8x hotter than the VCF engine it used to be misrouted to.
inline void process_osc_block(
        PulsarOscState& state,
        float note,
        float harmonics,
        float timbre,
        float morph,
        float fm_ratio,
        float fm_shape,
        float fm_free_hz,
        int gate,
        float sample_rate,
        float* out,
        int num_frames) {

    if (gate != 0 && state.prev_gate == 0) state.mod_phase = 0.0f;
    state.prev_gate = gate;

    const float carrier_hz = 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f);
    const bool free_run = fm_free_hz > 0.0f;
    const float mod_hz = free_run ? fm_free_hz : carrier_hz * fm_ratio;
    const bool fm_on = mod_hz > 0.0f && morph > 0.0f;
    const float mod_inc = mod_hz / sample_rate;
    const float deviation_hz = morph * 200.0f;                      // free-run only
    const float pm_cycles = morph * kFmIndexMax * kIndexToCycles;   // ratio only

    for (int i = 0; i < num_frames; i++) {
        float freq = carrier_hz;
        float phase_offset = 0.0f;
        if (fm_on) {
            const float m = mod_wave(state.mod_phase, fm_shape);
            if (free_run) freq += m * deviation_hz;
            else          phase_offset = m * pm_cycles;
            state.mod_phase += mod_inc;
            state.mod_phase -= std::floor(state.mod_phase);
        }
        out[i] = state.core.Next(freq, sample_rate, timbre, harmonics, phase_offset)
                 * kEngine0OutGain;
    }
}

}  // namespace osc
