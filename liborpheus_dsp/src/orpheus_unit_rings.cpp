#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

void unit_process_rings(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in = u->inputs[IPORT_INPUT].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // moduleIndex distinguishes main resonator (0) from drum resonator (1)
    bool is_drum = u->state.module.index == 1;

    if (is_drum) {
        if (engine->rings_drum_bypass.load(std::memory_order_relaxed)) {
            std::memcpy(out_l, in, num_frames * sizeof(float));
            std::memcpy(out_r, in, num_frames * sizeof(float));
            return;
        }
    } else {
        if (engine->rings_bypass.load(std::memory_order_relaxed)) {
            std::memcpy(out_l, in, num_frames * sizeof(float));
            std::memcpy(out_r, in, num_frames * sizeof(float));
            // Still populate RESONATOR Warps source even when bypassed
            std::memcpy(engine->warps_source_buffers[4], out_r, num_frames * sizeof(float));
            return;
        }
    }

    OrpheusResonator& reso = is_drum ? engine->drum_resonator : engine->resonator;

    // Update resonator parameters from engine atomics
    float structure  = engine->rings_structure.load(std::memory_order_relaxed);
    float brightness = engine->rings_brightness.load(std::memory_order_relaxed);
    float damping    = engine->rings_damping.load(std::memory_order_relaxed);
    float position   = engine->rings_position.load(std::memory_order_relaxed);
    int   mode       = engine->rings_model.load(std::memory_order_relaxed);

    // Clamp mode to 0-2 (Modal, Sympathetic, String)
    if (mode < 0) mode = 0;
    if (mode > 2) mode = 2;

    reso.modal.structure  = structure;
    reso.modal.brightness = brightness;
    reso.modal.damping    = damping;
    reso.modal.position   = position;

    reso.string.brightness = brightness;
    reso.string.damping    = damping;
    reso.string.position   = position;

    // Handle strum trigger (main resonator only)
    bool strum_pending = false;
    if (!is_drum) {
        int strum = engine->rings_strum.load(std::memory_order_relaxed);
        if (strum) {
            engine->rings_strum.store(0, std::memory_order_relaxed);
            // Convert MIDI note to Hz: freq = 440 * 2^((note-69)/12)
            float midi_note = engine->rings_frequency.load(std::memory_order_relaxed);
            float freq_hz = 440.0f * std::pow(2.0f, (midi_note - 69.0f) / 12.0f);
            reso.strum(freq_hz, sr);
            strum_pending = true;
        }
    }

    // Per-sample processing
    for (int i = 0; i < num_frames; i++) {
        float sample = in[i];
        // Inject 1.0f impulse on first sample of strum (matches JSyn behavior)
        if (strum_pending && i == 0) {
            sample = 1.0f;
            strum_pending = false;
        }
        reso.process(sample, mode);
        out_l[i] = reso.out_l;
        out_r[i] = reso.out_r;
    }

    // RESONATOR aux source (4) for warps routing (main resonator only)
    if (!is_drum) {
        std::memcpy(engine->warps_source_buffers[4], out_r, num_frames * sizeof(float));

        // Viz: resonator excitation input and output peaks
        float rin_pk = 0, rout_pk = 0;
        for (int i = 0; i < num_frames; i++) {
            float ai = std::fabs(in[i]);
            float ao = std::fabs(out_l[i]);
            if (ai > rin_pk) rin_pk = ai;
            if (ao > rout_pk) rout_pk = ao;
        }
        engine->viz_rings[VIZ_RESO_IN].write(rin_pk);
        engine->viz_rings[VIZ_RESO_OUT].write(rout_pk);
    }
}
