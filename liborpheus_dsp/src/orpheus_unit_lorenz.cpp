#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

// =========================================================================
// Lorenz Attractor — chaotic modulation source
//
// Wraps the Streams LorenzGenerator to produce two smooth, chaotic CV
// outputs (X and Z axes of the Lorenz strange attractor).  The outputs
// are normalised to 0..1 for use as modulation sources by other units.
//
// Parameters (from engine atomics):
//   lorenz_rate     0..1  speed of attractor evolution
//   lorenz_balance  0..1  slew amount (0 = raw/jagged, 1 = max smoothing)
//   lorenz_bypass   bool  skip processing when inactive
//
// The MI LorenzGenerator::Process() is a per-sample function that expects
// fixed-point int16 inputs (audio, excite) and produces uint16 outputs
// (gain, frequency).  We call it per-sample with audio=0, excite=0 to
// run it as a free-running chaotic oscillator, then extract the raw x_/z_
// state for higher-fidelity float output rather than the quantised
// gain/frequency uint16 values.
//
// Because x_, y_, z_ are private in LorenzGenerator, we instead use the
// gain (derived from z) and frequency (derived from x) outputs and
// normalise them from uint16 0..65535 to float 0..1.
// =========================================================================

void unit_process_lorenz(GraphUnit* u, OrpheusEngine* engine,
                         int num_frames, float sample_rate) {
    if (engine->lorenz_bypass.load(std::memory_order_relaxed)) {
        // Zero shared buffers so the graph mux doesn't copy stale data
        // when switching back to Lorenz source after being inactive.
        std::memset(engine->lorenz_x_buffer, 0, num_frames * sizeof(float));
        std::memset(engine->lorenz_z_buffer, 0, num_frames * sizeof(float));
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        std::memset(u->output_buffers[OPORT_OUT_RIGHT], 0, num_frames * sizeof(float));
        return;
    }

    float rate_01 = engine->lorenz_rate.load(std::memory_order_relaxed);
    float smooth = engine->lorenz_balance.load(std::memory_order_relaxed);

    // Map 0..1 UI knob to the Configure parameters[] that LorenzGenerator
    // expects.  parameters[0] is rate (0..65535 uint16 mapped through >> 8
    // to 0..255), parameters[1] controls vcf/vca balance.
    //
    // We call Configure() once per block to set rate_ and amounts, then
    // call Process() per sample.
    int32_t params[2];
    params[0] = static_cast<int32_t>(rate_01 * 65535.0f);
    // parameters[1] controls the VCA/VCF crossfade inside Configure().
    // At 0 it's all VCF (frequency output active), at 65535 it's all VCA
    // (gain output active).  We set to midpoint so both outputs are active.
    params[1] = 32768;

    int32_t globals[4] = {0, 0, 0, 0};
    engine->lorenz_generator.Configure(false, params, globals);

    float* out_x = engine->lorenz_x_buffer;
    float* out_z = engine->lorenz_z_buffer;

    // Subsample factor: the MI code was designed for ~31.25kHz on STM32
    // (Streams runs at 31250 Hz).  At 48kHz we'd be 1.536x faster,
    // which changes the attractor character.  We compensate by calling
    // Process() at the original rate and interpolating, but for simplicity
    // (and since this is a mod source, not audio) we just call it every
    // sample.  The rate LUT already provides sufficient range.

    // One-pole slew filter coefficient.
    // smooth=0 → alpha=1.0 (no filtering, raw attractor output)
    // smooth=1 → alpha≈0.0005 (heavy smoothing, glacial drift)
    // Exponential mapping gives musical feel: most of the knob range
    // is useful smoothing, with raw only at the very bottom.
    float alpha = std::exp(-smooth * 7.5f);  // e^0=1.0 … e^-7.5≈0.0006

    float slew_x = engine->lorenz_slew_x;
    float slew_z = engine->lorenz_slew_z;

    for (int i = 0; i < num_frames; i++) {
        uint16_t gain = 0, frequency = 0;
        engine->lorenz_generator.Process(0, 0, &gain, &frequency);

        // Normalise uint16 outputs to 0..1 float
        float x_norm = static_cast<float>(frequency) * (1.0f / 65535.0f);
        float z_norm = static_cast<float>(gain) * (1.0f / 65535.0f);

        // Apply one-pole lowpass slew
        slew_x += alpha * (x_norm - slew_x);
        slew_z += alpha * (z_norm - slew_z);

        out_x[i] = slew_x;
        out_z[i] = slew_z;
    }

    engine->lorenz_slew_x = slew_x;
    engine->lorenz_slew_z = slew_z;

    // Write blended output to the unit's OPORT_OUT buffer.
    // Both outputs carry a 50/50 X/Z mix — the two axes provide
    // decorrelated organic movement even after smoothing.
    float* out = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];
    for (int i = 0; i < num_frames; i++) {
        out[i] = out_x[i] * 0.5f + out_z[i] * 0.5f;
        out_r[i] = out_x[i] * 0.5f + out_z[i] * 0.5f;
    }
}
