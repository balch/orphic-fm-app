#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

void unit_process_pulsar_delay(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->pulsar_delay_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        return;
    }

    float* in_l = engine->pulsar_delay_send_l;
    float* in_r = engine->pulsar_delay_send_r;

    float fb_target = std::min(engine->pulsar_delay_feedback.load(std::memory_order_relaxed), 0.95f);

    // Blend with per-track feedback overrides
    {
        float pulsar_fb_sum = 0.0f;
        float pulsar_fb_weight = 0.0f;
        for (int t = 0; t < 8; t++) {
            float fb = engine->pulsar_track_delay_feedback[t].load(std::memory_order_relaxed);
            float send = engine->pulsar_track_delay_send[t].load(std::memory_order_relaxed);
            if (fb >= 0.0f && send > 0.001f) {
                pulsar_fb_sum += fb * send;
                pulsar_fb_weight += send;
            }
        }
        if (pulsar_fb_weight > 0.001f) {
            float pulsar_fb = std::min(pulsar_fb_sum / pulsar_fb_weight, 0.95f);
            float blend = std::min(pulsar_fb_weight, 1.0f);
            fb_target = fb_target * (1.0f - blend * 0.5f) + pulsar_fb * blend * 0.5f;
        }
    }

    float base_time_a = 0.01f + engine->pulsar_delay_time_a.load(std::memory_order_relaxed) * 1.99f;
    float base_time_b = 0.01f + engine->pulsar_delay_time_b.load(std::memory_order_relaxed) * 1.99f;

    const int max_d = OrpheusEngine::kPulsarMaxDelaySamples;
    const float smooth = 1.0f - std::exp(-1.0f / (0.02f * sr));
    float coeff = smooth_coeff(sr);

    for (int i = 0; i < num_frames; i++) {
        engine->pulsar_delay_time_a_smooth += smooth * (base_time_a - engine->pulsar_delay_time_a_smooth);
        engine->pulsar_delay_time_b_smooth += smooth * (base_time_b - engine->pulsar_delay_time_b_smooth);
        engine->pulsar_smooth_delay_feedback += coeff * (fb_target - engine->pulsar_smooth_delay_feedback);
        float fb = engine->pulsar_smooth_delay_feedback;

        float delay_samples_a = engine->pulsar_delay_time_a_smooth * sr;
        float delay_samples_b = engine->pulsar_delay_time_b_smooth * sr;
        delay_samples_a = std::min(delay_samples_a, (float)(max_d - 2));
        delay_samples_b = std::min(delay_samples_b, (float)(max_d - 2));

        int wp = engine->pulsar_delay_write_pos;

        // Linear interpolation read
        auto read_delay = [&](float* buf, float delay_s) -> float {
            float rp = (float)wp - delay_s;
            if (rp < 0) rp += max_d;
            int idx = (int)rp;
            float frac = rp - idx;
            int idx1 = (idx + 1) % max_d;
            return buf[idx] * (1.0f - frac) + buf[idx1] * frac;
        };

        float del_1l = read_delay(engine->pulsar_delay_buf_1l, delay_samples_a);
        float del_1r = read_delay(engine->pulsar_delay_buf_1r, delay_samples_a);
        float del_2l = read_delay(engine->pulsar_delay_buf_2l, delay_samples_b);
        float del_2r = read_delay(engine->pulsar_delay_buf_2r, delay_samples_b);

        engine->pulsar_delay_buf_1l[wp] = in_l[i] + del_1l * fb;
        engine->pulsar_delay_buf_1r[wp] = in_r[i] + del_1r * fb;
        engine->pulsar_delay_buf_2l[wp] = in_l[i] + del_2l * fb;
        engine->pulsar_delay_buf_2r[wp] = in_r[i] + del_2r * fb;

        engine->pulsar_delay_write_pos = (wp + 1) % max_d;

        out_l[i] = (del_1l + del_2l) * 0.5f;
        out_r[i] = (del_1r + del_2r) * 0.5f;
    }
}
