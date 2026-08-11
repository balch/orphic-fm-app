#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>

// ═══════════════════════════════════════════════════════════════════════
// Master Clock (sample-accurate 24 PPQN tempo generator)
// ═══════════════════════════════════════════════════════════════════════

void unit_process_clock(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* out_tick = u->output_buffers[OPORT_OUT];
    float* out_beat = u->output_buffers[OPORT_OUT_RIGHT];

    float bpm = engine->clock_bpm.load(std::memory_order_relaxed);
    int running = engine->clock_running.load(std::memory_order_relaxed);

    float port_bpm = u->inputs[IPORT_INPUT_A].constant;
    if (port_bpm > 0.0f) bpm = port_bpm;
    float port_run = u->inputs[IPORT_INPUT_B].constant;
    if (port_run >= 0.0f) running = port_run > 0.5f ? 1 : 0;

    if (!running || bpm <= 0.0f) {
        std::memset(out_tick, 0, num_frames * sizeof(float));
        std::memset(out_beat, 0, num_frames * sizeof(float));
        return;
    }

    double inc = (static_cast<double>(bpm) / 60.0) * 24.0 / static_cast<double>(sample_rate);

    for (int i = 0; i < num_frames; i++) {
        engine->clock_phase += inc;
        bool tick = false;
        bool beat = false;

        if (engine->clock_phase >= 1.0) {
            engine->clock_phase -= 1.0;
            tick = true;
            engine->clock_tick_count++;
            if (engine->clock_tick_count >= 24) {
                engine->clock_tick_count = 0;
                beat = true;
                engine->clock_beat_count = (engine->clock_beat_count + 1) % 4;
            }
        }

        out_tick[i] = tick ? 1.0f : 0.0f;
        out_beat[i] = beat ? 1.0f : 0.0f;
    }
}
