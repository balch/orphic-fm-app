// Tides2 unit processor (four-channel slope generator / LFO / envelope)
//
// Input:  IPORT_INPUT_A = beat clock pulses (used when clock_source == 1)
// Output: OPORT_OUT       = channel 0 (main slope / gate)
//         OPORT_OUT_RIGHT = channel 1
//         OPORT_AUX       = channel 2
//         (channel 3 written only to warps_source_buffers[13])

#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include "orpheus_viz.h"
#include <cmath>
#include <cstring>

void unit_process_tides(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* out0 = u->output_buffers[OPORT_OUT];
    float* out1 = u->output_buffers[OPORT_OUT_RIGHT];
    float* out2 = u->output_buffers[OPORT_AUX];

    // ── Self-bypass when mix is zero ─────────────────────────────────────────
    float mix = engine->tides_mix.load(std::memory_order_relaxed);
    if (mix <= 0.001f) {
        std::memset(out0, 0, num_frames * sizeof(float));
        std::memset(out1, 0, num_frames * sizeof(float));
        std::memset(out2, 0, num_frames * sizeof(float));
        // Zero warps source buffers 10-13
        std::memset(engine->warps_source_buffers[10], 0, num_frames * sizeof(float));
        std::memset(engine->warps_source_buffers[11], 0, num_frames * sizeof(float));
        std::memset(engine->warps_source_buffers[12], 0, num_frames * sizeof(float));
        std::memset(engine->warps_source_buffers[13], 0, num_frames * sizeof(float));
        // Zero tides output buffers
        std::memset(engine->tides_output_buffer[0], 0, num_frames * sizeof(float));
        std::memset(engine->tides_output_buffer[1], 0, num_frames * sizeof(float));
        std::memset(engine->tides_output_buffer[2], 0, num_frames * sizeof(float));
        std::memset(engine->tides_output_buffer[3], 0, num_frames * sizeof(float));
        // Zero viz
        engine->viz_rings[VIZ_TIDES_CH0].write(0.0f);
        engine->viz_rings[VIZ_TIDES_CH1].write(0.0f);
        engine->viz_rings[VIZ_TIDES_CH2].write(0.0f);
        engine->viz_rings[VIZ_TIDES_CH3].write(0.0f);
        return;
    }

    // ── Read all 12 parameter atomics ────────────────────────────────────────
    float frequency    = engine->tides_frequency.load(std::memory_order_relaxed);
    float slope        = engine->tides_slope.load(std::memory_order_relaxed);
    float shape        = engine->tides_shape.load(std::memory_order_relaxed);
    float smoothness   = engine->tides_smoothness.load(std::memory_order_relaxed);
    float shift        = engine->tides_shift.load(std::memory_order_relaxed);
    float clock_offset = engine->tides_clock_offset.load(std::memory_order_relaxed);
    int ramp_mode_i    = engine->tides_ramp_mode.load(std::memory_order_relaxed);
    int output_mode_i  = engine->tides_output_mode.load(std::memory_order_relaxed);
    int range_i        = engine->tides_range.load(std::memory_order_relaxed);
    int gate_source    = engine->tides_gate_source.load(std::memory_order_relaxed);
    int clock_source   = engine->tides_clock_source.load(std::memory_order_relaxed);

    // ── Clamp enum values ────────────────────────────────────────────────────
    if (ramp_mode_i  < 0) ramp_mode_i  = 0;
    if (ramp_mode_i  > 2) ramp_mode_i  = 2;
    if (output_mode_i < 0) output_mode_i = 0;
    if (output_mode_i > 3) output_mode_i = 3;
    if (range_i < 0) range_i = 0;
    if (range_i > 1) range_i = 1;

    auto ramp_mode   = static_cast<tides::RampMode>(ramp_mode_i);
    auto output_mode = static_cast<tides::OutputMode>(output_mode_i);
    auto range       = static_cast<tides::Range>(range_i);

    // ── Frequency mapping ────────────────────────────────────────────────────
    // Control range: 0.001–10 Hz (LFO / envelope rates)
    // Audio range:   20–15000 Hz (oscillator rates)
    // Then normalize to sample_rate units
    float hz;
    if (range_i == 0) {
        // RANGE_CONTROL: 0.001 * pow(10000, knob) → 0.001 Hz .. 10 Hz
        hz = 0.001f * std::pow(10000.0f, frequency);
    } else {
        // RANGE_AUDIO
        hz = 20.0f * std::pow(750.0f, frequency);
    }
    float norm_frequency = hz / sample_rate;

    // ── Gate source selection ─────────────────────────────────────────────────
    // Build gate_flags for PolySlopeGenerator (trigger / gate input)
    stmlib::GateFlags* gate_flags = engine->tides_gate_flags;
    stmlib::GateFlags prev_flag   = engine->tides_prev_gate_flag;

    if (gate_source == 0) {
        // Voice Gate: OR across all main voice gates (block-rate check)
        bool any_gate = false;
        for (int v = 0; v < kNumMainVoices; v++) {
            if (engine->voice_params[v].gate.load(std::memory_order_relaxed)) {
                any_gate = true;
                break;
            }
        }
        for (int i = 0; i < num_frames; i++) {
            gate_flags[i] = stmlib::ExtractGateFlags(prev_flag, any_gate);
            prev_flag = gate_flags[i];
        }
    } else if (gate_source >= 1 && gate_source <= 3) {
        // Marbles T1/T2/T3 buffers
        float* src_buf = nullptr;
        if      (gate_source == 1) src_buf = engine->marbles_t1_buffer;
        else if (gate_source == 2) src_buf = engine->marbles_t2_buffer;
        else                       src_buf = engine->marbles_t3_buffer;
        for (int i = 0; i < num_frames; i++) {
            bool high = src_buf[i] > 0.1f;
            gate_flags[i] = stmlib::ExtractGateFlags(prev_flag, high);
            prev_flag = gate_flags[i];
        }
    } else {
        // Free-run (gate_source == 4): constant high
        for (int i = 0; i < num_frames; i++) {
            gate_flags[i] = stmlib::ExtractGateFlags(prev_flag, true);
            prev_flag = gate_flags[i];
        }
    }
    engine->tides_prev_gate_flag = prev_flag;

    // ── Clock sync via RampExtractor (Task 5) ────────────────────────────────
    // ramp points to a filled buffer when clock_source==1, otherwise nullptr
    float* ramp = nullptr;
    if (clock_source == 1) {
        // Convert IPORT_INPUT_A beat pulse into GateFlags for RampExtractor
        float* clock_in = u->inputs[IPORT_INPUT_A].buffer;
        // Reuse gate_flags storage as temp; we need a separate array for clock flags
        // Use a stack buffer to avoid aliasing with tides_gate_flags
        stmlib::GateFlags clock_gate_flags[kMaxFrames];
        stmlib::GateFlags clock_prev = engine->tides_clock_prev_flag;
        for (int i = 0; i < num_frames; i++) {
            bool high = clock_in[i] > 0.1f;
            clock_gate_flags[i] = stmlib::ExtractGateFlags(clock_prev, high);
            clock_prev = clock_gate_flags[i];
        }
        engine->tides_clock_prev_flag = clock_prev;

        // Allocate ramp buffer on stack for clock-sync path
        float ramp_buffer[kMaxFrames];
        tides::Ratio ratio = {1.0f, 1};
        engine->tides_ramp_extractor.Process(
            false,            // smooth_audio_rate_tracking
            false,            // force_integer_period
            ratio,
            clock_gate_flags,
            ramp_buffer,
            static_cast<size_t>(num_frames));

        // Apply clock offset (positive modulo to keep result in [0, 1))
        for (int i = 0; i < num_frames; i++) {
            float r = std::fmod(ramp_buffer[i] + clock_offset, 1.0f);
            ramp_buffer[i] = r < 0.0f ? r + 1.0f : r;
        }

        ramp = ramp_buffer;

        // Render with clock ramp
        engine->tides_generator.Render(
            ramp_mode, output_mode, range,
            norm_frequency,
            slope,   // pw (attack/decay balance)
            shape,
            smoothness,
            shift,
            gate_flags,
            ramp,
            engine->tides_render_buffer,
            static_cast<size_t>(num_frames));
    } else {
        // Internal clock: pass nullptr for ramp
        engine->tides_generator.Render(
            ramp_mode, output_mode, range,
            norm_frequency,
            slope,
            shape,
            smoothness,
            shift,
            gate_flags,
            nullptr,
            engine->tides_render_buffer,
            static_cast<size_t>(num_frames));
    }

    // ── Deinterleave: tides_render_buffer[i].channel[0..3] ──────────────────
    for (int i = 0; i < num_frames; i++) {
        engine->tides_output_buffer[0][i] = engine->tides_render_buffer[i].channel[0];
        engine->tides_output_buffer[1][i] = engine->tides_render_buffer[i].channel[1];
        engine->tides_output_buffer[2][i] = engine->tides_render_buffer[i].channel[2];
        engine->tides_output_buffer[3][i] = engine->tides_render_buffer[i].channel[3];
    }

    // ── Output normalization and mix smoothing ───────────────────────────────
    // Tides2 outputs up to ±8V in AD/AR modes; normalize with 0.125f (1/8V)
    constexpr float kTidesNorm = 0.125f;
    float mx_coeff = smooth_coeff(sample_rate);
    for (int i = 0; i < num_frames; i++) {
        engine->tides_smooth_mix += mx_coeff * (mix - engine->tides_smooth_mix);
        float sm = engine->tides_smooth_mix;
        for (int ch = 0; ch < 4; ch++) {
            engine->tides_output_buffer[ch][i] *= kTidesNorm * sm;
        }
    }

    // ── Write to unit output ports (channels 0, 1, 2) ───────────────────────
    std::memcpy(out0, engine->tides_output_buffer[0], num_frames * sizeof(float));
    std::memcpy(out1, engine->tides_output_buffer[1], num_frames * sizeof(float));
    std::memcpy(out2, engine->tides_output_buffer[2], num_frames * sizeof(float));

    // ── Copy to Warps sources 10-13 ─────────────────────────────────────────
    for (int ch = 0; ch < 4; ch++) {
        std::memcpy(engine->warps_source_buffers[10 + ch],
                    engine->tides_output_buffer[ch],
                    num_frames * sizeof(float));
    }

    // ── Viz: write peak of each channel ─────────────────────────────────────
    for (int ch = 0; ch < 4; ch++) {
        float pk = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            float a = std::fabs(engine->tides_output_buffer[ch][i]);
            if (a > pk) pk = a;
        }
        engine->viz_rings[VIZ_TIDES_CH0 + ch].write(pk);
    }
}
