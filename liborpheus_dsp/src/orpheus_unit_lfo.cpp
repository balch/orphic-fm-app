#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// -- HyperLFO graph unit ----------------------------------
// No audio input. Computes dual LFO with logic combination.
// Per-sample computation for correct waveform at all rates.
// Writes to engine->lfo_output_value (monitoring) and output buffer.
void unit_process_hyper_lfo(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out = u->output_buffers[OPORT_OUT];

    float freq_a = engine->lfo_freq_a.load(std::memory_order_relaxed);
    float freq_b = engine->lfo_freq_b.load(std::memory_order_relaxed);
    float shape = engine->lfo_shape.load(std::memory_order_relaxed);
    int mode = engine->lfo_mode.load(std::memory_order_relaxed);

    float phase_a = engine->lfo_phase_a;
    float phase_b = engine->lfo_phase_b;

    // Total feedback: master output peak modulates LFO frequency (FM, not AM).
    // Matches JSyn: peakOutput → totalFbGain(amount*20) → Add → LFO.frequency
    // The feedback signal is added to the LFO base frequency in Hz.
    float fb_target = engine->total_feedback.load(std::memory_order_relaxed);
    engine->smooth_total_feedback += smooth_coeff(sr) * (fb_target - engine->smooth_total_feedback);
    float fb_amount = engine->smooth_total_feedback;
    float master_peak = std::max(
        engine->peak_left.load(std::memory_order_relaxed),
        engine->peak_right.load(std::memory_order_relaxed));
    float fb_hz = master_peak * fb_amount * 20.0f;  // Hz offset added to LFO freq

    // Compute per-sample phase increments with feedback FM
    float inc_a = (freq_a + fb_hz) / sr;
    float inc_b = (freq_b + fb_hz) / sr;

    auto gen_wave = [](float phase, float shp) -> float {
        float sq = phase < 0.5f ? 1.0f : -1.0f;
        float tri = 4.0f * std::fabs(phase - 0.5f) - 1.0f;
        return sq + (tri - sq) * shp;
    };

    // Quadrature phase offset for ch3 (90° of oscillator A's cycle)
    float quad_phase = phase_a + 0.25f;
    if (quad_phase >= 1.0f) quad_phase -= 1.0f;

    float output = 0.0f;
    float last_a = 0.0f, last_b = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float a = gen_wave(phase_a, shape);
        float b = gen_wave(phase_b, shape);
        last_a = a;
        last_b = b;

        if (mode == 0) { // AND
            float ua = a * 0.5f + 0.5f;
            float ub = b * 0.5f + 0.5f;
            output = (ua * ub) * 2.0f - 1.0f;
        } else if (mode == 2) { // OR
            float ua = a * 0.5f + 0.5f;
            float ub = b * 0.5f + 0.5f;
            output = (ua + ub - ua * ub) * 2.0f - 1.0f;
        } else { // OFF (mode=1) — silence
            output = 0.0f;
            a = 0.0f;
            b = 0.0f;
        }

        out[i] = output;

        // 4-channel output: ch0=combined, ch1=osc A, ch2=osc B, ch3=quadrature
        engine->lfo_morph_buffer[i] = a;
        engine->lfo_harmonics_buffer[i] = b;
        engine->lfo_pitch_buffer[i] = gen_wave(quad_phase, shape) * (mode != 1 ? 1.0f : 0.0f);

        phase_a += inc_a;
        if (phase_a >= 1.0f) phase_a -= 1.0f;
        phase_b += inc_b;
        if (phase_b >= 1.0f) phase_b -= 1.0f;
        quad_phase += inc_a;  // tracks osc A frequency
        if (quad_phase >= 1.0f) quad_phase -= 1.0f;
    }

    engine->lfo_phase_a = phase_a;
    engine->lfo_phase_b = phase_b;

    engine->lfo_output_value = output; // last sample for monitoring
    engine->lfo_output_value_a = last_a;
    engine->lfo_output_value_b = last_b;
    std::memcpy(engine->lfo_output_buffer, out, num_frames * sizeof(float));

    // Note: range attenuation, viz write, and warps source copy are handled
    // in the graph mux (orpheus_graph_process) after all LFO sources have run.

    // ── Dedicated vibrato sine oscillator ──────────────────────
    // Separate from HyperLFO (matches JSyn VibratoPlugin architecture).
    // Outputs Hz offset: sine(rate) * depth * 20 Hz
    float vib_depth = engine->vibrato_depth.load(std::memory_order_relaxed);
    float vib_depth_hz = vib_depth * 20.0f;  // 0..1 → 0..20 Hz (matches JSyn: depth * 20)
    float vib_rate = engine->vibrato_rate.load(std::memory_order_relaxed);
    float vib_phase_inc = vib_rate / sr;
    float vib_phase = engine->vibrato_phase;

    for (int i = 0; i < num_frames; i++) {
        float vib_sine = std::sin(vib_phase * 6.283185307f);
        engine->vibrato_output_buffer[i] = vib_sine * vib_depth_hz;
        vib_phase += vib_phase_inc;
        if (vib_phase >= 1.0f) vib_phase -= 1.0f;
    }
    engine->vibrato_phase = vib_phase;
}
