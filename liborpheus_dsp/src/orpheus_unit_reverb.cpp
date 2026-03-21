#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

// ═══════════════════════════════════════════════════════════════════════
// Dattorro Plate Reverb (ported from DattorroReverb.kt / MI Rings)
// ═══════════════════════════════════════════════════════════════════════

void unit_process_reverb(GraphUnit* u, OrpheusEngine* engine,
                         int num_frames, float sample_rate) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // Self-bypass
    if (engine->reverb_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        engine->viz_rings[VIZ_REVERB_IN].write(0.0f);
        engine->viz_rings[VIZ_REVERB_OUT].write(0.0f);
        return;
    }

    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;

    // Load parameters
    float amount_target = engine->reverb_amount.load(std::memory_order_relaxed);
    const float krt    = engine->reverb_time.load(std::memory_order_relaxed);
    const float klp    = engine->reverb_damping.load(std::memory_order_relaxed);
    const float kap    = engine->reverb_diffusion.load(std::memory_order_relaxed);
    const float gain   = 0.5f;  // inputGain
    float rv_coeff = smooth_coeff(sample_rate);

    // Reference delay lengths at 48kHz, scaled to runtime sample rate
    const float rr = sample_rate / 48000.0f;

    // Input allpass lengths
    const int ap1_len = static_cast<int>(150 * rr);
    const int ap2_len = static_cast<int>(214 * rr);
    const int ap3_len = static_cast<int>(319 * rr);
    const int ap4_len = static_cast<int>(527 * rr);

    // Loop allpass + delay lengths
    const int dap1a_len = static_cast<int>(2182 * rr);
    const int dap1b_len = static_cast<int>(2690 * rr);
    const int del1_len  = static_cast<int>(4501 * rr);
    const int dap2a_len = static_cast<int>(2525 * rr);
    const int dap2b_len = static_cast<int>(2197 * rr);
    const int del2_len  = static_cast<int>(6312 * rr);

    // Bases (cumulative offsets in ring buffer)
    const int ap1_base   = 0;
    const int ap2_base   = ap1_base   + ap1_len + 1;
    const int ap3_base   = ap2_base   + ap2_len + 1;
    const int ap4_base   = ap3_base   + ap3_len + 1;
    const int dap1a_base = ap4_base   + ap4_len + 1;
    const int dap1b_base = dap1a_base + dap1a_len + 1;
    const int del1_base  = dap1b_base + dap1b_len + 1;
    const int dap2a_base = del1_base  + del1_len + 1;
    const int dap2b_base = dap2a_base + dap2a_len + 1;
    const int del2_base  = dap2b_base + dap2b_len + 1;

    // LFO modulation tap offsets (scaled)
    const float del1_tap     = 4460.0f * rr;
    const float del1_lfo_amp = 40.0f * rr;
    const float del2_tap     = 6261.0f * rr;
    const float del2_lfo_amp = 50.0f * rr;

    // LFO phase increments (updated every 32 samples)
    const float lfo1_inc = 0.5f / sample_rate * 32.0f;
    const float lfo2_inc = 0.3f / sample_rate * 32.0f;

    constexpr float TWO_PI = 6.2831853f;
    float* buf = engine->reverb_buffer;
    const int mask = OrpheusEngine::kReverbMask;
    int wp = engine->reverb_write_pos;
    float lp1 = engine->reverb_lp_decay1;
    float lp2 = engine->reverb_lp_decay2;
    float lfo1_phase = engine->reverb_lfo1_phase;
    float lfo2_phase = engine->reverb_lfo2_phase;
    float lfo_val0 = engine->reverb_lfo1_value;
    float lfo_val1 = engine->reverb_lfo2_value;

    for (int i = 0; i < num_frames; i++) {
        // Advance write pointer (decrement, wrapping)
        wp = (wp - 1 + OrpheusEngine::kReverbBufferSize) & mask;

        // Update LFOs every 32 samples
        if ((wp & 31) == 0) {
            lfo1_phase += lfo1_inc;
            if (lfo1_phase >= 1.0f) lfo1_phase -= 1.0f;
            lfo_val0 = std::cos(lfo1_phase * TWO_PI);

            lfo2_phase += lfo2_inc;
            if (lfo2_phase >= 1.0f) lfo2_phase -= 1.0f;
            lfo_val1 = std::cos(lfo2_phase * TWO_PI);
        }

        // --- Read/write helpers (inline via lambda) ---
        #define RB(offset) buf[(wp + (offset)) & mask]
        #define WB(offset, val) buf[(wp + (offset)) & mask] = (val)

        // Allpass: read tail, feedforward/feedback, write head
        #define ALLPASS(base, len, input, coeff, result) do { \
            float _tail = RB((base) + (len) - 1); \
            float _v = (input) + _tail * (coeff); \
            WB((base), _v); \
            (result) = _v * (-(coeff)) + _tail; \
        } while(0)

        // Mono sum input
        float acc = (in_l[i] + in_r[i]) * gain;

        // 4 input allpass diffusers
        ALLPASS(ap1_base, ap1_len, acc, kap, acc);
        ALLPASS(ap2_base, ap2_len, acc, kap, acc);
        ALLPASS(ap3_base, ap3_len, acc, kap, acc);
        ALLPASS(ap4_base, ap4_len, acc, kap, acc);

        float apout = acc;

        // Interpolated read with LFO modulation
        auto interpolate = [&](int base, float offset, float lfo_val, float amplitude) -> float {
            float mod_off = offset + amplitude * lfo_val;
            int int_part = static_cast<int>(mod_off);
            float frac = mod_off - int_part;
            float a = buf[(wp + int_part + base) & mask];
            float b = buf[(wp + int_part + base + 1) & mask];
            return a + (b - a) * frac;
        };

        // Path 1 (left output)
        acc = apout;
        acc += interpolate(del2_base, del2_tap, lfo_val1, del2_lfo_amp) * krt;
        lp1 += klp * (acc - lp1);
        acc = lp1;
        ALLPASS(dap1a_base, dap1a_len, acc, -kap, acc);
        ALLPASS(dap1b_base, dap1b_len, acc,  kap, acc);
        WB(del1_base, acc);
        engine->smooth_reverb_amount += rv_coeff * (amount_target - engine->smooth_reverb_amount);
        float amount = engine->smooth_reverb_amount;
        out_l[i] = acc * 2.0f * amount;

        // Path 2 (right output)
        acc = apout;
        acc += interpolate(del1_base, del1_tap, lfo_val0, del1_lfo_amp) * krt;
        lp2 += klp * (acc - lp2);
        acc = lp2;
        ALLPASS(dap2a_base, dap2a_len, acc,  kap, acc);
        ALLPASS(dap2b_base, dap2b_len, acc, -kap, acc);
        WB(del2_base, acc);
        out_r[i] = acc * 2.0f * amount;

        #undef RB
        #undef WB
        #undef ALLPASS
    }

    // Save state
    engine->reverb_write_pos = wp;
    engine->reverb_lp_decay1 = lp1;
    engine->reverb_lp_decay2 = lp2;
    engine->reverb_lfo1_phase = lfo1_phase;
    engine->reverb_lfo2_phase = lfo2_phase;
    engine->reverb_lfo1_value = lfo_val0;
    engine->reverb_lfo2_value = lfo_val1;

    // Viz: reverb input and output peaks
    {
        float in_pk = 0, out_pk = 0;
        for (int i = 0; i < num_frames; i++) {
            float ai = std::fabs(in_l[i]);
            float ao = std::fabs(out_l[i]);
            if (ai > in_pk) in_pk = ai;
            if (ao > out_pk) out_pk = ao;
        }
        engine->viz_rings[VIZ_REVERB_IN].write(in_pk);
        engine->viz_rings[VIZ_REVERB_OUT].write(out_pk);
    }
}
