#pragma once

#include "osc_core.h"
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

// Modulation index at morph = 1. I = morph * kFmIndexMax.
constexpr float kFmIndexMax = 8.0f;

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
// Ratio mode scales deviation by mod_hz, which holds the modulation index
// I = morph * kFmIndexMax constant at every note. Free-run mode (fm_free_hz > 0)
// keeps the Orpheus panel's literal +-200Hz so a pasted preset transfers.
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
    const float deviation = free_run ? (morph * 200.0f)
                                     : (morph * kFmIndexMax * mod_hz);

    for (int i = 0; i < num_frames; i++) {
        float freq = carrier_hz;
        if (fm_on) {
            freq += mod_wave(state.mod_phase, fm_shape) * deviation;
            state.mod_phase += mod_inc;
            state.mod_phase -= std::floor(state.mod_phase);
        }
        out[i] = state.core.Next(freq, sample_rate, timbre, harmonics);
    }
}

}  // namespace osc
