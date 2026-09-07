#pragma once

#include <cmath>

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
    inline float Next(float freq, float sr, float sharpness, float feedback) {
        freq += prev_output * feedback * 200.0f;
        // Floor: feedback FM at low pitches can drive freq negative, which
        // reverses phase and squeals.
        if (freq < 1.0f) freq = 1.0f;

        float tri = 4.0f * std::fabs(tri_phase - 0.5f) - 1.0f;
        float sq = (sq_phase < 0.5f) ? 1.0f : -1.0f;
        float audio = tri * (1.0f - sharpness) + sq * sharpness;

        // Phases advance unmodulated; feedback only shifts the read position.
        float phase_inc = freq / sr;
        tri_phase += phase_inc;
        tri_phase -= std::floor(tri_phase);
        sq_phase += phase_inc;
        sq_phase -= std::floor(sq_phase);

        prev_output = audio;
        return audio;
    }
};
