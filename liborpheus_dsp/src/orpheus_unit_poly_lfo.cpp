#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

// -- PolyLFO graph unit (MI Frames) ----------------------------
// 4-channel poly LFO with shape morphing, spread, and coupling.
// Runs per-sample (Render is per-sample in MI code), converts
// uint8_t level output to float -1..+1 for modulation routing.
//
// Output: 4 channels written to engine poly_lfo_output[0..3] buffers.
// Also writes mixed output (ch0) to the graph unit OPORT_OUT for
// potential graph-level connections.

void unit_process_poly_lfo(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    // Check bypass
    if (engine->poly_lfo_bypass.load(std::memory_order_relaxed)) {
        // Zero all outputs when bypassed
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        for (int ch = 0; ch < 4; ch++) {
            std::memset(engine->poly_lfo_output[ch], 0, num_frames * sizeof(float));
        }
        return;
    }

    // Read parameters from engine atomics (once per block)
    float shape_f   = engine->poly_lfo_shape.load(std::memory_order_relaxed);
    float spread_f  = engine->poly_lfo_shape_spread.load(std::memory_order_relaxed);
    float phase_spread_f = engine->poly_lfo_spread.load(std::memory_order_relaxed);
    float coupling_f = engine->poly_lfo_coupling.load(std::memory_order_relaxed);
    float rate_f     = engine->poly_lfo_rate.load(std::memory_order_relaxed);

    // Convert float 0..1 to uint16_t 0..65535 for MI API
    auto to_u16 = [](float v) -> uint16_t {
        return static_cast<uint16_t>(std::max(0.0f, std::min(1.0f, v)) * 65535.0f);
    };

    engine->poly_lfo.set_shape(to_u16(shape_f));
    engine->poly_lfo.set_shape_spread(to_u16(spread_f));
    engine->poly_lfo.set_spread(to_u16(phase_spread_f));
    engine->poly_lfo.set_coupling(to_u16(coupling_f));

    // Convert rate (0..1) to MI frequency parameter.
    // MI PolyLFO frequency is an int32_t used by FrequencyToPhaseIncrement.
    // The lookup table (lut_increments) has 159 entries accessed as (frequency/5040),
    // with a shift-based octave mechanism. Rate 0 = very slow, rate 1 = fast.
    // Reasonable range: ~0 to ~20000 covers sub-Hz to fast LFO rates.
    // At the original Frames sample rate of ~4kHz (internal), frequency ~5040 = ~1Hz.
    // But we're running at audio rate (48kHz), so phase increments are ~12x too fast.
    // We scale the frequency value to compensate: multiply by (4000/sr) ratio.
    //
    // Practical range mapping:
    //   rate=0.0 -> frequency ~200  (very slow, ~0.04 Hz)
    //   rate=0.5 -> frequency ~5040 (moderate, ~1 Hz at Frames rate)
    //   rate=1.0 -> frequency ~20000 (fast, ~4 Hz)
    //
    // The Frames hardware runs Render() at ~4kHz (1ms timer interrupt).
    // We run at audio rate (48kHz), so we need to scale the frequency down
    // by the ratio 4000/sr to get equivalent LFO rates.
    float frames_rate = 4000.0f;  // Frames internal update rate
    float rate_scale = frames_rate / sr;
    // Map rate 0..1 to a wide frequency range with exponential scaling
    // for musical feel (low end is slow, high end is fast)
    float freq_raw = 200.0f + std::pow(rate_f, 2.0f) * 19800.0f;  // 200..20000
    int32_t frequency = static_cast<int32_t>(freq_raw * rate_scale);
    if (frequency < 0) frequency = 0;

    float* out = u->output_buffers[OPORT_OUT];

    // Render per-sample (MI PolyLFO is a per-sample processor)
    for (int i = 0; i < num_frames; i++) {
        engine->poly_lfo.Render(frequency);

        // Extract 4 channels: level() is uint8_t 0..255 (unsigned waveform)
        // Convert to bipolar float: -1..+1
        for (int ch = 0; ch < 4; ch++) {
            float val = (static_cast<float>(engine->poly_lfo.level(ch)) / 127.5f) - 1.0f;
            engine->poly_lfo_output[ch][i] = val;
        }

        // Primary output is channel 0 (for graph connections)
        out[i] = engine->poly_lfo_output[0][i];
    }

    // Store last values for monitoring
    for (int ch = 0; ch < 4; ch++) {
        engine->poly_lfo_value[ch] = engine->poly_lfo_output[ch][num_frames - 1];
    }
}
