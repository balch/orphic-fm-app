#pragma once

#include <cmath>

// Engine 0 (triangle/square oscillator) output gain.
// Balances Engine 0 level against Plaits engines so switching engines
// doesn't produce a jarring volume jump. Lives beside the oscillator it trims
// so callers do not have to pull in the whole Plaits chain to reach it.
static constexpr float kEngine0OutGain = 0.65f;

// Triangle/square oscillator with self-feedback FM, shared by the main-synth
// OSC voice and the Pulsar OSC kernel so the two cannot drift apart.
// Extracted from three identical copies in orpheus_unit_plaits.cpp.
struct OscCore {
    float tri_phase = 0.0f;
    float sq_phase = 0.0f;
    float prev_output = 0.0f;

    // One sample. `freq` is the already-modulated carrier in Hz: callers add
    // vibrato, bend, coupling and external FM before calling. `feedback` adds
    // self-FM from the previous sample at +-200Hz, matching JSyn.
    // `sharpness` crossfades triangle (0) to square (1).
    //
    // `phase_offset` is phase modulation in CYCLES: it shifts where this sample
    // reads the waveforms without touching the accumulators, so the long-run
    // rate stays exactly `freq`. Defaulted to 0, where the read phase is
    // bit-identical to the stored phase and the panel path is unchanged.
    inline float Next(float freq, float sr, float sharpness, float feedback,
                      float phase_offset = 0.0f) {
        freq += prev_output * feedback * 200.0f;
        // Floor: feedback FM at low pitches can drive freq negative, which
        // reverses phase and squeals.
        if (freq < 1.0f) freq = 1.0f;

        float tri_read = tri_phase + phase_offset;
        tri_read -= std::floor(tri_read);
        float sq_read = sq_phase + phase_offset;
        sq_read -= std::floor(sq_read);

        float tri = 4.0f * std::fabs(tri_read - 0.5f) - 1.0f;
        float sq = (sq_read < 0.5f) ? 1.0f : -1.0f;
        float audio = tri * (1.0f - sharpness) + sq * sharpness;

        // Feedback modulates the rate, so it advances the accumulators too;
        // only phase_offset shifts the read position without them.
        float phase_inc = freq / sr;
        tri_phase += phase_inc;
        tri_phase -= std::floor(tri_phase);
        sq_phase += phase_inc;
        sq_phase -= std::floor(sq_phase);

        prev_output = audio;
        return audio;
    }
};
