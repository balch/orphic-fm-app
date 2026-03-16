#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

void unit_process_warps(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // warps_smooth_mix is updated in orpheus_graph_process (before any unit runs)
    // so warps_dry_scale() and this function see the same consistent value.
    if (engine->warps_smooth_mix < 0.0001f) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        // Viz: write zeros when bypassed (flat line, not frozen stale data)
        engine->viz_rings[VIZ_WARPS_CARRIER].write(0.0f);
        engine->viz_rings[VIZ_WARPS_MOD].write(0.0f);
        engine->viz_rings[VIZ_WARPS_OUT].write(0.0f);
        return;
    }

    // Select carrier and modulator from source buffers
    int c_src = engine->warps_carrier_source.load(std::memory_order_relaxed);
    int m_src = engine->warps_modulator_source.load(std::memory_order_relaxed);

    float carrier_buf[kMaxFrames];
    float mod_buf[kMaxFrames];

    auto select_source = [&](int src, float* dest) {
        if (src == 5) { // WARPS feedback
            std::memcpy(dest, engine->warps_feedback_l, num_frames * sizeof(float));
        } else if (src == 0) {
            // SYNTH uses double-buffered read (previous frame, avoids execution order)
            std::memcpy(dest, engine->warps_synth_read, num_frames * sizeof(float));
        } else if (src == 1) {
            // DRUMS uses double-buffered read
            std::memcpy(dest, engine->warps_drums_read, num_frames * sizeof(float));
        } else if (src == 2) {
            // REPL uses double-buffered read
            std::memcpy(dest, engine->warps_repl_read, num_frames * sizeof(float));
        } else if (src >= 0 && src < OrpheusEngine::kNumWarpsSources) {
            std::memcpy(dest, engine->warps_source_buffers[src], num_frames * sizeof(float));
        } else {
            std::memset(dest, 0, num_frames * sizeof(float));
        }
    };

    select_source(c_src, carrier_buf);
    select_source(m_src, mod_buf);

    float* in_l = carrier_buf;
    float* in_r = mod_buf;

    auto* wp = engine->warps_modulator.mutable_parameters();
    // UI sends 0-8 (SegmentedAlgoKnob range), MI Warps expects 0-1 (internally * 8)
    wp->modulation_algorithm = engine->warps_algorithm.load(std::memory_order_relaxed) / 8.0f;
    wp->modulation_parameter = engine->warps_timbre.load(std::memory_order_relaxed);
    // Level knobs drive MI Warps' SaturatingAmplifier directly — its nonlinear
    // gain curve handles everything from noise-gating (0) to heavy overdrive (1).
    // Signal enters at full scale (no external pre-gain) to maximize int16 precision.
    wp->channel_drive[0] = engine->warps_level1.load(std::memory_order_relaxed);
    wp->channel_drive[1] = engine->warps_level2.load(std::memory_order_relaxed);
    wp->carrier_shape = 0;

    // Use block size 64 (not kMaxBlockSize=96) to ensure the MI Warps SRC
    // downsampler always takes the same code path. At 96-sample blocks with
    // 512-sample callbacks, the last block is 32 samples which switches the
    // SRC to a circular-buffer path with incompatible state → crackling.
    // At 64 samples: 64*6=384 = 8*48 (filter_size), always using the fast path.
    // 512/64 = 8 blocks, no remainder.
    constexpr int kWarpsBlockSize = 64;

    int frames_done = 0;
    while (frames_done < num_frames) {
        int block = std::min(kWarpsBlockSize, num_frames - frames_done);

        warps::ShortFrame in_frames[warps::kMaxBlockSize];
        warps::ShortFrame out_frames[warps::kMaxBlockSize];

        for (int i = 0; i < block; i++) {
            float l = in_l[frames_done + i];
            float r = in_r[frames_done + i];
            l = std::fmax(-1.0f, std::fmin(1.0f, l));
            r = std::fmax(-1.0f, std::fmin(1.0f, r));
            in_frames[i].l = static_cast<short>(l * 32767.0f);
            in_frames[i].r = static_cast<short>(r * 32767.0f);
        }

        engine->warps_modulator.Process(in_frames, out_frames, block);

        const float inv = 1.0f / 32768.0f;
        for (int i = 0; i < block; i++) {
            out_l[frames_done + i] = out_frames[i].l * inv;
            out_r[frames_done + i] = out_frames[i].r * inv;
        }

        frames_done += block;
    }

    // Scale wet output: boost to match the full-level carrier removed from dry.
    // Source buffers are normalized (SYNTH=1/8, REPL=1/4). The MI SaturatingAmplifier
    // processes this quiet signal and outputs at a similar level. We boost the wet
    // by the inverse normalization so it replaces the carrier at matching volume.
    // Compensate for source normalization but account for MI SaturatingAmplifier
    // internal gain (~0.375x at drive=0.5). Boost 4x gives unity at mid-drive;
    // higher drive values add overdrive (intended).
    float wet_boost = 1.0f;
    if (c_src == 0) wet_boost = 4.0f;       // SYNTH (1/8 norm, hotter)
    else if (c_src == 1) wet_boost = 2.0f;  // DRUMS (1/3 norm)
    else if (c_src == 2) wet_boost = 2.0f;  // REPL (1/4 norm)

    float gain = engine->warps_smooth_mix * wet_boost;
    for (int i = 0; i < num_frames; i++) {
        out_l[i] *= gain;
        out_r[i] *= gain;
    }

    // Viz: write per-block peaks for carrier, modulator, output (post-gain)
    {
        float c_pk = 0, m_pk = 0, o_pk = 0;
        for (int i = 0; i < num_frames; i++) {
            float ac = std::fabs(carrier_buf[i]);
            float am = std::fabs(mod_buf[i]);
            float ao = std::fabs(out_l[i]);
            if (ac > c_pk) c_pk = ac;
            if (am > m_pk) m_pk = am;
            if (ao > o_pk) o_pk = ao;
        }
        engine->viz_rings[VIZ_WARPS_CARRIER].write(c_pk);
        engine->viz_rings[VIZ_WARPS_MOD].write(m_pk);
        engine->viz_rings[VIZ_WARPS_OUT].write(o_pk);
    }

    // Store output as feedback source (source 5)
    std::memcpy(engine->warps_feedback_l, out_l, num_frames * sizeof(float));
    std::memcpy(engine->warps_feedback_r, out_r, num_frames * sizeof(float));
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[5][i] = (out_l[i] + out_r[i]) * 0.5f;
    }
}
