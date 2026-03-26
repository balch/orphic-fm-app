#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// -- Dual Delay graph unit --------------------------------
// Stereo in/out, reads delay params from engine atomics,
// uses engine's delay buffers for state.
void unit_process_dual_delay(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* lfo_in = u->inputs[IPORT_INPUT_C].buffer;  // LFO modulation input
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // Mix bass FX send into delay input (bass is mono → both L and R)
    float bass_send = engine->bass_smooth_fx_send;
    if (bass_send > 0.001f) {
        float* bass = engine->warps_bass_read;  // double-buffered for order-independent read
        for (int i = 0; i < num_frames; i++) {
            in_l[i] += bass[i] * bass_send;
            in_r[i] += bass[i] * bass_send;
        }
    }

    if (engine->delay_bypass.load(std::memory_order_relaxed)) {
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        engine->viz_rings[VIZ_DELAY_IN].write(0.0f);
        engine->viz_rings[VIZ_DELAY_FB].write(0.0f);
        engine->viz_rings[VIZ_DELAY_OUT].write(0.0f);
        return;
    }

    float mix_target = engine->delay_mix.load(std::memory_order_relaxed);
    float fb_target = std::min(engine->delay_feedback.load(std::memory_order_relaxed), 0.95f);
    const float mod_depth_1 = engine->delay_mod_depth_1.load(std::memory_order_relaxed);
    const float mod_depth_2 = engine->delay_mod_depth_2.load(std::memory_order_relaxed);

    // Base delay times in seconds (0..1 knob → 0.01..2.0s)
    float base_time_1 = 0.01f + engine->delay_time_1.load(std::memory_order_relaxed) * 1.99f;
    float base_time_2 = 0.01f + engine->delay_time_2.load(std::memory_order_relaxed) * 1.99f;

    const int max_d = OrpheusEngine::kMaxDelaySamples;

    // Smooth delay times (~20ms ramp)
    const float smooth = 1.0f - std::exp(-1.0f / (0.02f * sr));
    float coeff = smooth_coeff(sr);

    for (int i = 0; i < num_frames; i++) {
        // Per-sample smoothing for mix and feedback
        engine->smooth_delay_mix += coeff * (mix_target - engine->smooth_delay_mix);
        engine->smooth_delay_feedback += coeff * (fb_target - engine->smooth_delay_feedback);
        float mix = engine->smooth_delay_mix;
        float fb = engine->smooth_delay_feedback;
        float dry = 1.0f - mix;

        // LFO modulation: convert -1..1 to 0..1 unipolar, scale by mod depth
        float lfo_uni = (lfo_in[i] + 1.0f) * 0.5f;
        float mod_time_1 = (base_time_1 + lfo_uni * mod_depth_1) * sr;
        float mod_time_2 = (base_time_2 + lfo_uni * mod_depth_2) * sr;

        // Per-sample smoothing for modulated delay time
        engine->delay_time_1_smooth += (mod_time_1 - engine->delay_time_1_smooth) * smooth;
        engine->delay_time_2_smooth += (mod_time_2 - engine->delay_time_2_smooth) * smooth;

        int ds1 = std::max(1, std::min(static_cast<int>(engine->delay_time_1_smooth),
                                        max_d - 1));
        int ds2 = std::max(1, std::min(static_cast<int>(engine->delay_time_2_smooth),
                                        max_d - 1));

        int wp = engine->delay_write_pos;

        int rp1 = (wp - ds1 + max_d) % max_d;
        int rp2 = (wp - ds2 + max_d) % max_d;

        float d1l = engine->delay_buffer_1l[rp1];
        float d1r = engine->delay_buffer_1r[rp1];
        float d2l = engine->delay_buffer_2l[rp2];
        float d2r = engine->delay_buffer_2r[rp2];

        engine->delay_buffer_1l[wp] = in_l[i] + d1l * fb;
        engine->delay_buffer_1r[wp] = in_r[i] + d1r * fb;
        engine->delay_buffer_2l[wp] = in_l[i] + d2l * fb;
        engine->delay_buffer_2r[wp] = in_r[i] + d2r * fb;

        float wet_l = (d1l + d2l) * 0.5f;
        float wet_r = (d1r + d2r) * 0.5f;
        out_l[i] = in_l[i] * dry + wet_l * mix;
        out_r[i] = in_r[i] * dry + wet_r * mix;

        engine->delay_write_pos = (wp + 1) % max_d;
    }

    // Viz: delay input peak, feedback signal peak, output peak
    {
        float in_pk = 0, fb_pk = 0, out_pk = 0;
        float fb = engine->smooth_delay_feedback;
        int wp = engine->delay_write_pos;
        for (int i = 0; i < num_frames; i++) {
            float ai = std::fabs(in_l[i]);
            float ao = std::fabs(out_l[i]);
            // Read the delayed+attenuated signal being fed back (wet * fb)
            float wet = std::fabs(out_l[i] - in_l[i]);  // approximate wet component
            if (ai > in_pk) in_pk = ai;
            if (ao > out_pk) out_pk = ao;
            if (wet > fb_pk) fb_pk = wet;
        }
        // Scale by feedback amount to show actual feedback energy
        fb_pk *= std::fabs(fb);
        engine->viz_rings[VIZ_DELAY_IN].write(in_pk);
        engine->viz_rings[VIZ_DELAY_FB].write(fb_pk);
        engine->viz_rings[VIZ_DELAY_OUT].write(out_pk);
    }
}
