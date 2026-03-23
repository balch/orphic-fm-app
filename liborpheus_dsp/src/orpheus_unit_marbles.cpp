#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

// ═══════════════════════════════════════════════════════════════════════
// Marbles Random Sequencer (MI Marbles TGenerator + XYGenerator)
// ═══════════════════════════════════════════════════════════════════════
//
// Input: IPORT_INPUT_A = 24 PPQN clock pulses (from UNIT_CLOCK)
// Output: OPORT_OUT       = t1 gate (rhythmic trigger from TGenerator)
//         OPORT_OUT_RIGHT = x1 CV (random pitch voltage from XYGenerator)
//         OPORT_AUX       = x2 CV (second random voltage)

void unit_process_marbles(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* out_gate = u->output_buffers[OPORT_OUT];
    float* out_cv1  = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_cv2  = u->output_buffers[OPORT_AUX];

    // Self-bypass: output silence when mix is zero
    float mix = engine->marbles_mix.load(std::memory_order_relaxed);
    if (mix <= 0.001f) {
        std::memset(out_gate, 0, num_frames * sizeof(float));
        std::memset(out_cv1,  0, num_frames * sizeof(float));
        std::memset(out_cv2,  0, num_frames * sizeof(float));
        // Zero Warps FLUX source and cached CV to avoid stale data
        std::memset(engine->warps_source_buffers[6], 0, num_frames * sizeof(float));
        engine->marbles_cv_output[0] = 0.0f;
        engine->marbles_cv_output[1] = 0.0f;
        engine->viz_rings[VIZ_FLUX_CV].write(0.0f);
        return;
    }

    // Clock source: 0=global 24 PPQN clock, 1=LFO output (-1..+1 continuous waveform)
    float* clock_in = (engine->marbles_clock_source.load(std::memory_order_relaxed) == 1)
        ? engine->lfo_output_buffer
        : u->inputs[IPORT_INPUT_A].buffer;

    // ── Step 1: Convert float clock pulses to stmlib::GateFlags ──
    // Threshold 0.1 (not 0.5): LFO waveforms (-1..+1) need a low threshold for reliable
    // edge detection. Harmless for digital 0/1 clock pulses (well above 0.1).
    stmlib::GateFlags* gate_flags = engine->marbles_gate_flags;
    stmlib::GateFlags prev_flag = engine->marbles_prev_gate_flag;
    for (int i = 0; i < num_frames; i++) {
        bool high = clock_in[i] > 0.1f;
        gate_flags[i] = stmlib::ExtractGateFlags(prev_flag, high);
        prev_flag = gate_flags[i];
    }
    engine->marbles_prev_gate_flag = prev_flag;

    // ── Step 2: Read TGenerator parameters from engine atomics ──
    float t_rate = engine->marbles_t_rate.load(std::memory_order_relaxed);
    float t_bias = engine->marbles_t_bias.load(std::memory_order_relaxed);
    float t_jitter = engine->marbles_t_jitter.load(std::memory_order_relaxed);
    int t_model_i = engine->marbles_t_model.load(std::memory_order_relaxed);
    int t_range_i = engine->marbles_t_range.load(std::memory_order_relaxed);
    float deja_vu = engine->marbles_deja_vu.load(std::memory_order_relaxed);
    int deja_vu_length = engine->marbles_deja_vu_length.load(std::memory_order_relaxed);
    int deja_vu_mode = engine->marbles_deja_vu_mode.load(std::memory_order_relaxed);
    if (deja_vu_mode < 0) deja_vu_mode = 0;
    if (deja_vu_mode > 2) deja_vu_mode = 2;

    // Clamp enums to valid ranges
    if (t_model_i < 0) t_model_i = 0;
    if (t_model_i > 6) t_model_i = 6;
    if (t_range_i < 0) t_range_i = 0;
    if (t_range_i > 2) t_range_i = 2;
    if (deja_vu_length < 1) deja_vu_length = 1;
    if (deja_vu_length > 16) deja_vu_length = 16;

    engine->marbles_t_generator.set_model(
        static_cast<marbles::TGeneratorModel>(t_model_i));
    engine->marbles_t_generator.set_range(
        static_cast<marbles::TGeneratorRange>(t_range_i));
    engine->marbles_t_generator.set_rate(t_rate);
    engine->marbles_t_generator.set_bias(t_bias);
    engine->marbles_t_generator.set_jitter(t_jitter);
    // Déjà vu mode: 0=T+X, 1=T only, 2=X only
    float t_deja_vu = (deja_vu_mode == 2) ? 0.0f : deja_vu;  // X-only → disable T looping
    engine->marbles_t_generator.set_deja_vu(t_deja_vu);
    engine->marbles_t_generator.set_length(deja_vu_length);
    engine->marbles_t_generator.set_pulse_width_mean(
        engine->marbles_pulse_width.load(std::memory_order_relaxed));
    engine->marbles_t_generator.set_pulse_width_std(
        engine->marbles_pulse_width_std.load(std::memory_order_relaxed));

    // ── Step 3: Set up Ramps struct with working buffer pointers ──
    marbles::Ramps ramps;
    ramps.external = engine->marbles_ramp_external;
    ramps.master   = engine->marbles_ramp_master;
    ramps.slave[0] = engine->marbles_ramp_slave0;
    ramps.slave[1] = engine->marbles_ramp_slave1;

    // ── Step 4: Process TGenerator (rhythmic gate generation) ──
    // use_external_clock=true: we feed the 24 PPQN clock from UNIT_CLOCK
    bool* gate_out = engine->marbles_gate_out;
    engine->marbles_t_generator.Process(
        true,                   // use_external_clock
        gate_flags,             // external clock gate flags
        ramps,                  // ramp working buffers (filled by TGenerator)
        gate_out,               // output: bool gate[size * kNumTChannels]
        static_cast<size_t>(num_frames));

    // ── Step 5: Read XYGenerator parameters ──
    float x_spread = engine->marbles_x_spread.load(std::memory_order_relaxed);
    float x_bias = engine->marbles_x_bias.load(std::memory_order_relaxed);
    float x_steps = engine->marbles_x_steps.load(std::memory_order_relaxed);
    int x_control_mode_i = engine->marbles_x_control_mode.load(std::memory_order_relaxed);
    int x_range_i = engine->marbles_x_range.load(std::memory_order_relaxed);
    int x_scale_i = engine->marbles_x_scale.load(std::memory_order_relaxed);

    if (x_control_mode_i < 0) x_control_mode_i = 0;
    if (x_control_mode_i > 2) x_control_mode_i = 2;
    if (x_range_i < 0) x_range_i = 0;
    if (x_range_i > 2) x_range_i = 2;
    if (x_scale_i < 0) x_scale_i = 0;
    if (x_scale_i > 5) x_scale_i = 5;

    // Build GroupSettings for X and Y outputs
    marbles::GroupSettings x_settings;
    x_settings.control_mode = static_cast<marbles::ControlMode>(x_control_mode_i);
    x_settings.voltage_range = static_cast<marbles::VoltageRange>(x_range_i);
    x_settings.register_mode = false;
    x_settings.register_value = 0.0f;
    x_settings.spread = x_spread;
    x_settings.bias = x_bias;
    x_settings.steps = x_steps;
    float x_deja_vu = (deja_vu_mode == 1) ? 0.0f : deja_vu;  // T-only → disable X looping
    x_settings.deja_vu = x_deja_vu;
    x_settings.scale_index = x_scale_i;
    x_settings.length = deja_vu_length;
    x_settings.ratio = { 1, 1 };

    marbles::GroupSettings y_settings = x_settings;
    // Y channel (index 3) must use IDENTICAL mode — the TILT amount formula
    // (2*i/(n-1) - 1) only covers X channels 0-2. Index 3 produces amount=2.0,
    // pushing spread/bias out of [0,1] and causing an OOB table access crash.
    y_settings.control_mode = marbles::CONTROL_MODE_IDENTICAL;
    y_settings.ratio = { 1, 1 };

    // ── Step 6: Process XYGenerator (random CV generation) ──
    // CLOCK_SOURCE_INTERNAL_T1_T2_T3: X channels use the T ramps as clock
    float* xy_output = engine->marbles_xy_output;
    engine->marbles_xy_generator.Process(
        marbles::CLOCK_SOURCE_INTERNAL_T1_T2_T3,
        x_settings,
        y_settings,
        gate_flags,
        ramps,
        xy_output,
        static_cast<size_t>(num_frames));

    // ── Step 7: Deinterleave all 6 outputs ──
    // T1 gate (TGenerator channel 0)
    for (int i = 0; i < num_frames; i++) {
        out_gate[i] = gate_out[i * 2] ? 1.0f : 0.0f;
    }
    // T2 gate (master ramp < pulseWidth, matching Kotlin FluxProcessor)
    float pw = engine->marbles_pulse_width.load(std::memory_order_relaxed);
    for (int i = 0; i < num_frames; i++) {
        engine->marbles_t2_buffer[i] = (ramps.master[i] < pw) ? 1.0f : 0.0f;
    }
    // T3 gate (TGenerator channel 1)
    for (int i = 0; i < num_frames; i++) {
        engine->marbles_t3_buffer[i] = gate_out[i * 2 + 1] ? 1.0f : 0.0f;
    }

    // CV outputs: scale voltage by mix before exp conversion for perceptually linear control.
    // Raw XY voltage is scaled by 0.4 to keep pitch shifts musically useful:
    //   POSITIVE (0-5V): 0-2 octaves up at full mix  (was 0-4 octaves)
    //   NARROW   (0-2V): 0-0.8 octaves              (subtle)
    //   FULL   (-5-+5V): ±2 octaves                  (symmetric)
    // Also prevents Warps saturation — Flux signal stays in a usable drive range.
    constexpr float kFluxVoltageScale = 0.4f;
    float rng_min = engine->marbles_range_min.load(std::memory_order_relaxed);
    float rng_max = engine->marbles_range_max.load(std::memory_order_relaxed);
    float rng_scale = (rng_max - rng_min);
    float rng_offset = rng_min;
    float mx_coeff = smooth_coeff(sample_rate);
    for (int i = 0; i < num_frames; i++) {
        engine->smooth_marbles_mix += mx_coeff * (mix - engine->smooth_marbles_mix);
        float sm = engine->smooth_marbles_mix;
        float v1 = xy_output[i * 4 + 0] * sm * kFluxVoltageScale;
        float v2 = xy_output[i * 4 + 1] * sm * kFluxVoltageScale;
        float v3 = xy_output[i * 4 + 2] * sm * kFluxVoltageScale;
        // Attenuator: remap voltage range (acts like a hardware attenuator knob)
        v1 = v1 * rng_scale + rng_offset;
        v2 = v2 * rng_scale + rng_offset;
        v3 = v3 * rng_scale + rng_offset;
        // Clamp to [-2, 2] octaves before exp to prevent blowout
        v1 = std::fmin(std::fmax(v1, -2.0f), 2.0f);
        v2 = std::fmin(std::fmax(v2, -2.0f), 2.0f);
        v3 = std::fmin(std::fmax(v3, -2.0f), 2.0f);
        out_cv1[i] = std::exp2f(v1) - 1.0f;
        out_cv2[i] = std::exp2f(v2) - 1.0f;
        engine->marbles_x3_buffer[i] = std::exp2f(v3) - 1.0f;
        // Y channel: smooth random CV, clamped to [-1, +1] for modulation use
        float y_raw = xy_output[i * 4 + 3] * sm;  // scale by mix like X channels
        engine->marbles_y_buffer[i] = std::fmax(-1.0f, std::fmin(1.0f, y_raw));
    }

    // Copy to shared engine buffers for trigger router consumers
    std::memcpy(engine->marbles_t1_buffer, out_gate, num_frames * sizeof(float));
    std::memcpy(engine->marbles_x1_buffer, out_cv1, num_frames * sizeof(float));
    std::memcpy(engine->marbles_x2_buffer, out_cv2, num_frames * sizeof(float));

    // Cache CV output for mod source routing (post-mix/exp)
    engine->marbles_cv_output[0] = out_cv1[num_frames - 1];
    engine->marbles_cv_output[1] = out_cv2[num_frames - 1];

    // FLUX source (6) for warps routing (post-mix/exp)
    std::memcpy(engine->warps_source_buffers[6],
                out_cv1, num_frames * sizeof(float));

    // Viz: Flux CV output peak
    {
        float cv_pk = 0;
        for (int i = 0; i < num_frames; i++) {
            float a = std::fabs(out_cv1[i]);
            if (a > cv_pk) cv_pk = a;
        }
        engine->viz_rings[VIZ_FLUX_CV].write(cv_pk);
    }
}
