#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

// ═══════════════════════════════════════════════════════════════════════
// Pulsar Dedicated Dattorro Plate Reverb
// Same topology as shared reverb, but reads from Pulsar send buffers
// and uses independent state/parameters.
// ═══════════════════════════════════════════════════════════════════════

void unit_process_pulsar_reverb(GraphUnit* u, OrpheusEngine* engine,
                                int num_frames, float sample_rate) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // Self-bypass
    if (engine->pulsar_reverb_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        return;
    }

    // Input: read directly from Pulsar per-track reverb send buffers
    float* in_l = engine->pulsar_reverb_send_l;
    float* in_r = engine->pulsar_reverb_send_r;

    // Load and smooth parameters (~5ms ramp to avoid clicks from knob changes
    // propagating through the reverb's feedback loop)
    float amount_target = engine->pulsar_reverb_amount.load(std::memory_order_relaxed);
    float time_target   = engine->pulsar_reverb_time.load(std::memory_order_relaxed);
    float diff_target   = engine->pulsar_reverb_diffusion.load(std::memory_order_relaxed);

    // Remap DAMP knob: UI 0=no damping (bright), 1=heavy damping (dark).
    // The lowpass coefficient klp needs to be inverted: klp=1 is bright (passthrough),
    // klp→0 is dark (frozen). Floor at 0.05 to keep the feedback loop alive.
    float damp_raw = engine->pulsar_reverb_damping.load(std::memory_order_relaxed);
    float damp_target = 1.0f - 0.95f * damp_raw;  // DAMP 0→1.0, DAMP 1→0.05

    float rv_coeff = smooth_coeff(sample_rate);
    engine->smooth_pulsar_reverb_time      += rv_coeff * (time_target - engine->smooth_pulsar_reverb_time);
    engine->smooth_pulsar_reverb_damping   += rv_coeff * (damp_target - engine->smooth_pulsar_reverb_damping);
    engine->smooth_pulsar_reverb_diffusion += rv_coeff * (diff_target - engine->smooth_pulsar_reverb_diffusion);

    const float krt  = engine->smooth_pulsar_reverb_time;
    const float klp  = engine->smooth_pulsar_reverb_damping;
    const float kap  = engine->smooth_pulsar_reverb_diffusion;
    const float gain = 0.5f;  // inputGain

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

    // LFO modulation tap offsets (scaled)
    const float del1_tap     = 4460.0f * rr;
    const float del1_lfo_amp = 40.0f * rr;
    const float del2_tap     = 6261.0f * rr;
    const float del2_lfo_amp = 50.0f * rr;

    // LFO phase increments (updated every 32 samples)
    const float lfo1_inc = 0.5f / sample_rate * 32.0f;
    const float lfo2_inc = 0.3f / sample_rate * 32.0f;

    constexpr float TWO_PI = 6.2831853f;
    constexpr int BUF_SIZE = OrpheusEngine::kPulsarReverbBufSize;
    constexpr int BUF_MASK = BUF_SIZE - 1;

    // Per-stage buffers (separate arrays instead of single ring buffer)
    float* buf_ap1   = engine->pulsar_rv_ap1;
    float* buf_ap2   = engine->pulsar_rv_ap2;
    float* buf_ap3   = engine->pulsar_rv_ap3;
    float* buf_ap4   = engine->pulsar_rv_ap4;
    float* buf_dap1a = engine->pulsar_rv_ap5;
    float* buf_dap1b = engine->pulsar_rv_ap6;
    float* buf_del1  = engine->pulsar_rv_dly1;
    float* buf_dap2a = engine->pulsar_rv_dly2;
    float* buf_dap2b = engine->pulsar_rv_dly3;
    float* buf_del2  = engine->pulsar_rv_dly4;

    int wp = engine->pulsar_rv_write_pos;
    float lp1 = engine->pulsar_rv_lp_decay1;
    float lp2 = engine->pulsar_rv_lp_decay2;
    float lfo1_phase = engine->pulsar_rv_lfo_phase;
    float lfo2_phase = engine->pulsar_rv_lfo2_phase;
    float lfo_val0 = engine->pulsar_rv_lfo_value;
    float lfo_val1 = engine->pulsar_rv_lfo2_value;

    for (int i = 0; i < num_frames; i++) {
        // Advance write pointer (decrement, wrapping)
        wp = (wp - 1 + BUF_SIZE) & BUF_MASK;

        // Update LFOs every 32 samples
        if ((wp & 31) == 0) {
            lfo1_phase += lfo1_inc;
            if (lfo1_phase >= 1.0f) lfo1_phase -= 1.0f;
            lfo_val0 = std::cos(lfo1_phase * TWO_PI);

            lfo2_phase += lfo2_inc;
            if (lfo2_phase >= 1.0f) lfo2_phase -= 1.0f;
            lfo_val1 = std::cos(lfo2_phase * TWO_PI);
        }

        // --- Read/write helpers for separate per-stage buffers ---
        // Each stage has its own buffer, so offset is just the delay tap index
        #define PRB(buf, offset) (buf)[((wp) + (offset)) & BUF_MASK]
        #define PWB(buf, offset, val) (buf)[((wp) + (offset)) & BUF_MASK] = (val)

        // Allpass: read tail, feedforward/feedback, write head
        #define PALLPASS(buf, len, input, coeff, result) do { \
            float _tail = PRB(buf, (len) - 1); \
            float _v = (input) + _tail * (coeff); \
            PWB(buf, 0, _v); \
            (result) = _v * (-(coeff)) + _tail; \
        } while(0)

        // Mono sum input
        float acc = (in_l[i] + in_r[i]) * gain;

        // 4 input allpass diffusers
        PALLPASS(buf_ap1, ap1_len, acc, kap, acc);
        PALLPASS(buf_ap2, ap2_len, acc, kap, acc);
        PALLPASS(buf_ap3, ap3_len, acc, kap, acc);
        PALLPASS(buf_ap4, ap4_len, acc, kap, acc);

        float apout = acc;

        // Interpolated read with LFO modulation (per-stage buffer variant)
        auto interpolate = [&](float* buf, float offset, float lfo_val, float amplitude) -> float {
            float mod_off = offset + amplitude * lfo_val;
            int int_part = static_cast<int>(mod_off);
            float frac = mod_off - int_part;
            float a = buf[(wp + int_part) & BUF_MASK];
            float b = buf[(wp + int_part + 1) & BUF_MASK];
            return a + (b - a) * frac;
        };

        // Path 1 (left output)
        acc = apout;
        acc += interpolate(buf_del2, del2_tap, lfo_val1, del2_lfo_amp) * krt;
        lp1 += klp * (acc - lp1);
        acc = lp1;
        PALLPASS(buf_dap1a, dap1a_len, acc, -kap, acc);
        PALLPASS(buf_dap1b, dap1b_len, acc,  kap, acc);
        PWB(buf_del1, 0, acc);
        engine->smooth_pulsar_reverb_amount += rv_coeff * (amount_target - engine->smooth_pulsar_reverb_amount);
        float amount = engine->smooth_pulsar_reverb_amount;
        out_l[i] = acc * 2.0f * amount;

        // Path 2 (right output)
        acc = apout;
        acc += interpolate(buf_del1, del1_tap, lfo_val0, del1_lfo_amp) * krt;
        lp2 += klp * (acc - lp2);
        acc = lp2;
        PALLPASS(buf_dap2a, dap2a_len, acc,  kap, acc);
        PALLPASS(buf_dap2b, dap2b_len, acc, -kap, acc);
        PWB(buf_del2, 0, acc);
        out_r[i] = acc * 2.0f * amount;

        #undef PRB
        #undef PWB
        #undef PALLPASS
    }

    // Save state
    engine->pulsar_rv_write_pos = wp;
    engine->pulsar_rv_lp_decay1 = lp1;
    engine->pulsar_rv_lp_decay2 = lp2;
    engine->pulsar_rv_lfo_phase = lfo1_phase;
    engine->pulsar_rv_lfo2_phase = lfo2_phase;
    engine->pulsar_rv_lfo_value = lfo_val0;
    engine->pulsar_rv_lfo2_value = lfo_val1;
}
