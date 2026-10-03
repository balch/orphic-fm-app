#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include "orpheus_horn.h"
#include <cmath>
#include <cstring>

// ─── Speed mapping ────────────────────────────────────────────────────────────
// horn_speed param 0..1, exponential:
//   0    = 0.2 Hz treble (barely moving "chorale")
//   0.5  = 1.26 Hz treble
//   1.0  = 8.0 Hz treble (fast tremolo)
//
// Ratio param 0..1 sets woofer_hz = horn_hz * ratio_scale / 9:
//   ratio=0   → horn / 81 (much slower)
//   ratio=0.5 → horn / 9  (classic Leslie ratio)
//   ratio=1   → horn      (drum as fast as the horn)

static inline float speed_to_hz(float speed) {
    return 0.2f * std::pow(40.0f, speed);
}

static inline float ratio_to_woofer_hz(float horn_hz, float ratio) {
    float log_scale = (ratio - 0.5f) * 2.0f * std::log(9.0f);
    float ratio_scale = std::exp(log_scale);   // 1/9 .. 9
    return horn_hz * ratio_scale / 9.0f;
}

// ─── unit_process_horn ────────────────────────────────────────────────────────
void unit_process_horn(GraphUnit* u, OrpheusEngine* engine,
                       int num_frames, float sample_rate) {
    float* in_l  = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r  = u->inputs[IPORT_INPUT_B].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];
    OrpheusHorn& horn = engine->horn;

    // ── Self-bypass: passthrough (not silence), since horn is wired inline ──
    // Waits for the smoothed mix to fade out, so snapping mix to 0 doesn't click,
    // and clears the delay lines on the way in so a re-enable can't replay old audio.
    float mix_target = engine->horn_mix.load(std::memory_order_relaxed);
    if (mix_target <= 0.001f && horn.smooth_mix <= 0.001f) {
        if (!horn.bypassed) {
            horn.ClearAudio();
            horn.bypassed = true;
        }
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        engine->viz_rings[VIZ_HORN_IN].write(0.0f);
        engine->viz_rings[VIZ_HORN_OUT].write(0.0f);
        engine->viz_rings[VIZ_HORN_PHASE].write(0.0f);
        engine->viz_rings[VIZ_WOOFER_PHASE].write(0.0f);
        engine->horn_bypass.store(1, std::memory_order_relaxed);
        return;
    }
    horn.bypassed = false;
    engine->horn_bypass.store(0, std::memory_order_relaxed);

    // ── Load parameters ────────────────────────────────────────────────────
    float speed_param  = engine->horn_speed.load(std::memory_order_relaxed);  // 0..1
    float ratio_param  = engine->horn_ratio.load(std::memory_order_relaxed);  // 0..1
    float depth_param  = engine->horn_depth.load(std::memory_order_relaxed);  // 0..1
    int   brake        = engine->horn_brake.load(std::memory_order_relaxed);  // 0 or 1

    float horn_hz_target   = 0.0f;
    float woofer_hz_target = 0.0f;
    if (!brake) {
        horn_hz_target   = speed_to_hz(speed_param);
        woofer_hz_target = ratio_to_woofer_hz(horn_hz_target, ratio_param);
    }

    // One-pole LP at ~800 Hz: coeff = 1 - exp(-2*pi*fc / sr)
    const float crossover_coeff = 1.0f - std::exp(-6.28318530718f * 800.0f / sample_rate);

    float in_peak = 0.0f;
    for (int i = 0; i < num_frames; ++i) {
        float s = std::fabs(in_l[i]) + std::fabs(in_r[i]);
        if (s > in_peak) in_peak = s;
    }
    in_peak *= 0.5f;  // average of L+R energy

    horn.Process(in_l, in_r, out_l, out_r, num_frames,
                 mix_target, horn_hz_target, woofer_hz_target, depth_param,
                 crossover_coeff, sample_rate, smooth_coeff(sample_rate));

    float out_peak = 0.0f;
    for (int i = 0; i < num_frames; ++i) {
        float s = std::fabs(out_l[i]) + std::fabs(out_r[i]);
        if (s > out_peak) out_peak = s;
    }
    out_peak *= 0.5f;

    engine->viz_rings[VIZ_HORN_IN].write(in_peak);
    engine->viz_rings[VIZ_HORN_OUT].write(out_peak);
    engine->viz_rings[VIZ_HORN_PHASE].write(horn.horn_phase);
    engine->viz_rings[VIZ_WOOFER_PHASE].write(horn.woofer_phase);
}
