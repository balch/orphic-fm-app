#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include "orpheus_horn.h"
#include <cmath>
#include <cstring>

// ─── OrpheusHorn singleton (holds all DSP state except delay buffers) ──────
// The delay buffers are in OrpheusEngine (horn_delay_l / horn_delay_r).
// We keep the rest of the DSP state here in a static struct so it persists
// across calls but doesn't bloat OrpheusEngine's header.
static OrpheusHorn s_horn;
static bool s_horn_initialized = false;

// Called from orpheus_engine_destroy() to reset singleton state
// so that Init() re-runs with fresh delay buffer pointers on engine recreation.
extern "C" void horn_reset_static() {
    s_horn = OrpheusHorn{};
    s_horn_initialized = false;
}

// ─── Speed mapping ────────────────────────────────────────────────────────────
// horn_speed param 0..1:
//   0    = slowest     (0.2 Hz treble / 0.5 Hz bass — barely moving "chorale")
//   0.5  = classic Leslie slow (0.75 Hz treble / 0.5 Hz bass)
//              → maps to MI Ensemble's original 0.75 Hz / ~6.57 Hz split
//   1.0  = fastest     (8.0 Hz treble / 6.5 Hz bass)
//
// Ratio param 0..1 sets bass_hz = horn_hz * ratio_scale.
//   ratio=0.5 (center) → bass_hz = horn_hz / 9.0 (classic Leslie ratio)
//   ratio<0.5 → bass slower relative to horn
//   ratio>0.5 → bass faster relative to horn

static inline float speed_to_hz(float speed) {
    // Exponential curve from 0.2 Hz to 8.0 Hz
    return 0.2f * std::pow(40.0f, speed);
}

static inline float ratio_to_woofer_hz(float horn_hz, float ratio) {
    // ratio=0.5 → woofer = horn / 9.0
    // ratio=0   → woofer = horn / 50.0 (much slower)
    // ratio=1   → woofer = horn * 2.0  (faster than horn)
    // We use exponential interpolation: scale = exp(ratio * log(18) - log(9))
    //   at ratio=0.5: scale = exp(0) = 1.0 → woofer = horn / 9.0
    //   at ratio=0:   scale = exp(-log(9)) = 1/9 → woofer = horn / 81
    //   at ratio=1:   scale = exp(log(9)) = 9   → woofer = horn
    // Simplified: ratio_scale in [1/9 .. 9], pivot at 1.0 when ratio=0.5
    float log_scale = (ratio - 0.5f) * 2.0f * std::log(9.0f);
    float ratio_scale = std::exp(log_scale);   // 1/9 .. 9
    return horn_hz * ratio_scale / 9.0f;       // pivot: horn_hz/9 at ratio=0.5
}

// ─── unit_process_horn ────────────────────────────────────────────────────────
void unit_process_horn(GraphUnit* u, OrpheusEngine* engine,
                       int num_frames, float sample_rate) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // ── Self-bypass when mix <= 0.001: passthrough (not silence) ──────────
    // Horn is wired inline (drive → horn → delay/reverb), so bypass must
    // copy input to output to keep the voice signal flowing downstream.
    float mix_target = engine->horn_mix.load(std::memory_order_relaxed);
    if (mix_target <= 0.001f) {
        float* in_l = u->inputs[IPORT_INPUT_A].buffer;
        float* in_r = u->inputs[IPORT_INPUT_B].buffer;
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        engine->viz_rings[VIZ_HORN_IN].write(0.0f);
        engine->viz_rings[VIZ_HORN_OUT].write(0.0f);
        engine->viz_rings[VIZ_HORN_PHASE].write(0.0f);
        engine->viz_rings[VIZ_WOOFER_PHASE].write(0.0f);
        engine->horn_bypass.store(1, std::memory_order_relaxed);
        return;
    }
    engine->horn_bypass.store(0, std::memory_order_relaxed);

    // ── Initialize horn DSP (once) ─────────────────────────────────────────
    if (!s_horn_initialized) {
        s_horn.Init(engine->horn_delay_l, engine->horn_delay_r);
        s_horn_initialized = true;
    }

    // ── Load input buffers ─────────────────────────────────────────────────
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;

    // ── Load parameters ────────────────────────────────────────────────────
    float speed_param  = engine->horn_speed.load(std::memory_order_relaxed);  // 0..1
    float ratio_param  = engine->horn_ratio.load(std::memory_order_relaxed);  // 0..1
    float depth_param  = engine->horn_depth.load(std::memory_order_relaxed);  // 0..1
    int   brake        = engine->horn_brake.load(std::memory_order_relaxed);  // 0 or 1

    // ── Derive rotor speed targets ─────────────────────────────────────────
    float horn_hz_target;
    float woofer_hz_target;
    if (brake) {
        horn_hz_target   = 0.0f;
        woofer_hz_target = 0.0f;
    } else {
        horn_hz_target   = speed_to_hz(speed_param);
        woofer_hz_target = ratio_to_woofer_hz(horn_hz_target, ratio_param);
    }

    // ── Crossover filter coefficient (one-pole LP at ~800 Hz) ─────────────
    // coeff = 1 - exp(-2*pi*fc / sr)
    const float crossover_hz    = 800.0f;
    const float crossover_coeff = 1.0f - std::exp(-6.28318530718f * crossover_hz / sample_rate);

    // ── Smoothing coefficient for mix ──────────────────────────────────────
    const float sc = smooth_coeff(sample_rate);

    // ── Viz: peak of input signal ──────────────────────────────────────────
    float in_peak = 0.0f;
    for (int i = 0; i < num_frames; ++i) {
        float s = std::fabs(in_l[i]) + std::fabs(in_r[i]);
        if (s > in_peak) in_peak = s;
    }
    in_peak *= 0.5f;  // average of L+R energy

    // ── Process block ──────────────────────────────────────────────────────
    float exported_horn_phase   = 0.0f;
    float exported_woofer_phase = 0.0f;

    s_horn.Process(
        in_l, in_r,
        out_l, out_r,
        num_frames,
        mix_target,
        horn_hz_target,
        woofer_hz_target,
        depth_param,
        crossover_coeff,
        sample_rate,
        exported_horn_phase,
        exported_woofer_phase,
        sc
    );

    // ── Sync engine state from horn DSP ───────────────────────────────────
    engine->horn_write_pos   = s_horn.write_pos;
    engine->horn_slow_phase  = exported_horn_phase;
    engine->horn_fast_phase  = exported_woofer_phase;
    engine->smooth_horn_mix  = s_horn.smooth_mix;

    // ── Viz: peak of output signal ─────────────────────────────────────────
    float out_peak = 0.0f;
    for (int i = 0; i < num_frames; ++i) {
        float s = std::fabs(out_l[i]) + std::fabs(out_r[i]);
        if (s > out_peak) out_peak = s;
    }
    out_peak *= 0.5f;

    engine->viz_rings[VIZ_HORN_IN].write(in_peak);
    engine->viz_rings[VIZ_HORN_OUT].write(out_peak);
    engine->viz_rings[VIZ_HORN_PHASE].write(exported_horn_phase);
    engine->viz_rings[VIZ_WOOFER_PHASE].write(exported_woofer_phase);
}
