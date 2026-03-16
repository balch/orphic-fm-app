#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// ═══════════════════════════════════════════════════════════════════════
// Grids Drum Pattern Generator (MI Grids drum map, AVR-free implementation)
// ═══════════════════════════════════════════════════════════════════════
//
// The pattern ROM is a 5×5 grid of 25 nodes, each 96 bytes (3 instruments × 32 steps).
// x,y select a position in the grid; bilinear interpolation across 4 nearest nodes
// produces an accent level (0-255). If level > density threshold, trigger fires.

namespace grids_data {

static const uint8_t node_0[] = {
    255,0,0,0,0,0,145,0,0,0,0,0,218,0,0,0,72,0,36,0,182,0,0,0,109,0,0,0,72,0,0,0,
    36,0,109,0,0,0,8,0,255,0,0,0,0,0,72,0,0,0,182,0,0,0,36,0,218,0,0,0,145,0,0,0,
    170,0,113,0,255,0,56,0,170,0,141,0,198,0,56,0,170,0,113,0,226,0,28,0,170,0,113,0,198,0,85,0,
};
static const uint8_t node_1[] = {
    229,0,25,0,102,0,25,0,204,0,25,0,76,0,8,0,255,0,8,0,51,0,25,0,178,0,25,0,153,0,127,0,
    28,0,198,0,56,0,56,0,226,0,28,0,141,0,28,0,28,0,170,0,28,0,28,0,255,0,113,0,85,0,85,0,
    159,0,159,0,255,0,63,0,159,0,159,0,191,0,31,0,159,0,127,0,255,0,31,0,159,0,127,0,223,0,95,0,
};
static const uint8_t node_2[] = {
    255,0,0,0,127,0,0,0,0,0,102,0,0,0,229,0,0,0,178,0,204,0,0,0,76,0,51,0,153,0,25,0,
    0,0,127,0,0,0,0,0,255,0,191,0,31,0,63,0,0,0,95,0,0,0,0,0,223,0,0,0,31,0,159,0,
    255,0,85,0,148,0,85,0,127,0,85,0,106,0,63,0,212,0,170,0,191,0,170,0,85,0,42,0,233,0,21,0,
};
static const uint8_t node_3[] = {
    255,0,212,0,63,0,0,0,106,0,148,0,85,0,127,0,191,0,21,0,233,0,0,0,21,0,170,0,0,0,42,0,
    0,0,0,0,141,0,113,0,255,0,198,0,0,0,56,0,0,0,85,0,56,0,28,0,226,0,28,0,170,0,56,0,
    255,0,231,0,255,0,208,0,139,0,92,0,115,0,92,0,185,0,69,0,46,0,46,0,162,0,23,0,208,0,46,0,
};
static const uint8_t node_4[] = {
    255,0,31,0,63,0,63,0,127,0,95,0,191,0,63,0,223,0,31,0,159,0,63,0,31,0,63,0,95,0,31,0,
    8,0,0,0,95,0,63,0,255,0,0,0,127,0,0,0,8,0,0,0,159,0,63,0,255,0,223,0,191,0,31,0,
    76,0,25,0,255,0,127,0,153,0,51,0,204,0,102,0,76,0,51,0,229,0,127,0,153,0,51,0,178,0,102,0,
};
static const uint8_t node_5[] = {
    255,0,51,0,25,0,76,0,0,0,0,0,102,0,0,0,204,0,229,0,0,0,178,0,0,0,153,0,127,0,8,0,
    178,0,127,0,153,0,204,0,255,0,0,0,25,0,76,0,102,0,51,0,0,0,0,0,229,0,25,0,25,0,204,0,
    178,0,102,0,255,0,76,0,127,0,76,0,229,0,76,0,153,0,102,0,255,0,25,0,127,0,51,0,204,0,51,0,
};
static const uint8_t node_6[] = {
    255,0,0,0,223,0,0,0,31,0,8,0,127,0,0,0,95,0,0,0,159,0,0,0,95,0,63,0,191,0,0,0,
    51,0,204,0,0,0,102,0,255,0,127,0,8,0,178,0,25,0,229,0,0,0,76,0,204,0,153,0,51,0,25,0,
    255,0,226,0,255,0,255,0,198,0,28,0,141,0,56,0,170,0,56,0,85,0,28,0,170,0,28,0,113,0,56,0,
};
static const uint8_t node_7[] = {
    223,0,0,0,63,0,0,0,95,0,0,0,223,0,31,0,255,0,0,0,159,0,0,0,127,0,31,0,191,0,31,0,
    0,0,0,0,109,0,0,0,218,0,0,0,182,0,72,0,8,0,36,0,145,0,36,0,255,0,8,0,182,0,72,0,
    255,0,72,0,218,0,36,0,218,0,0,0,145,0,0,0,255,0,36,0,182,0,36,0,182,0,0,0,109,0,0,0,
};
static const uint8_t node_8[] = {
    255,0,0,0,218,0,0,0,36,0,0,0,218,0,0,0,182,0,109,0,255,0,0,0,0,0,0,0,145,0,72,0,
    159,0,0,0,31,0,127,0,255,0,31,0,0,0,95,0,8,0,0,0,191,0,31,0,255,0,31,0,223,0,63,0,
    255,0,31,0,63,0,31,0,95,0,31,0,63,0,127,0,159,0,31,0,63,0,31,0,223,0,223,0,191,0,191,0,
};
static const uint8_t node_9[] = {
    226,0,28,0,28,0,141,0,8,0,8,0,255,0,8,0,113,0,28,0,198,0,85,0,56,0,198,0,170,0,28,0,
    8,0,95,0,8,0,8,0,255,0,63,0,31,0,223,0,8,0,31,0,191,0,8,0,255,0,127,0,127,0,159,0,
    115,0,46,0,255,0,185,0,139,0,23,0,208,0,115,0,231,0,69,0,255,0,162,0,139,0,115,0,231,0,92,0,
};
static const uint8_t node_10[] = {
    145,0,0,0,0,0,109,0,0,0,0,0,255,0,109,0,72,0,218,0,0,0,0,0,36,0,0,0,182,0,0,0,
    0,0,127,0,159,0,127,0,159,0,191,0,223,0,63,0,255,0,95,0,31,0,95,0,31,0,8,0,63,0,8,0,
    255,0,0,0,145,0,0,0,182,0,109,0,109,0,109,0,218,0,0,0,72,0,0,0,182,0,72,0,182,0,36,0,
};
static const uint8_t node_11[] = {
    255,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,255,0,0,0,218,0,72,36,0,0,182,0,0,0,145,109,
    0,0,127,0,0,0,42,0,212,0,0,212,0,0,212,0,0,0,0,0,42,0,0,0,255,0,0,0,170,170,127,85,
    145,0,109,109,218,109,72,0,145,0,72,0,218,0,109,0,182,0,109,0,255,0,72,0,182,109,36,109,255,109,109,0,
};
static const uint8_t node_12[] = {
    255,0,0,0,255,0,191,0,0,0,0,0,95,0,63,0,31,0,0,0,223,0,223,0,0,0,8,0,159,0,127,0,
    0,0,85,0,56,0,28,0,255,0,28,0,0,0,226,0,0,0,170,0,56,0,113,0,198,0,0,0,113,0,141,0,
    255,0,42,0,233,0,63,0,212,0,85,0,191,0,106,0,191,0,21,0,170,0,8,0,170,0,127,0,148,0,148,0,
};
static const uint8_t node_13[] = {
    255,0,0,0,0,0,63,0,191,0,95,0,31,0,223,0,255,0,63,0,95,0,63,0,159,0,0,0,0,0,127,0,
    72,0,0,0,0,0,0,0,255,0,0,0,0,0,0,0,72,0,72,0,36,0,8,0,218,0,182,0,145,0,109,0,
    255,0,162,0,231,0,162,0,231,0,115,0,208,0,139,0,185,0,92,0,185,0,46,0,162,0,69,0,162,0,23,0,
};
static const uint8_t node_14[] = {
    255,0,0,0,51,0,0,0,0,0,0,0,102,0,0,0,204,0,0,0,153,0,0,0,0,0,0,0,51,0,0,0,
    0,0,0,0,8,0,36,0,255,0,0,0,182,0,8,0,0,0,0,0,72,0,109,0,145,0,0,0,255,0,218,0,
    212,0,8,0,170,0,0,0,127,0,0,0,85,0,8,0,255,0,8,0,170,0,0,0,127,0,0,0,42,0,8,0,
};
static const uint8_t node_15[] = {
    255,0,0,0,0,0,0,0,36,0,0,0,182,0,0,0,218,0,0,0,0,0,0,0,72,0,0,0,145,0,109,0,
    36,0,36,0,0,0,0,0,255,0,0,0,182,0,0,0,0,0,0,0,0,0,0,109,218,0,0,0,145,0,72,72,
    255,0,28,0,226,0,56,0,198,0,0,0,0,0,28,28,170,0,0,0,141,0,0,0,113,0,0,0,85,85,85,85,
};
static const uint8_t node_16[] = {
    255,0,0,0,0,0,95,0,0,0,127,0,0,0,0,0,223,0,95,0,63,0,31,0,191,0,0,0,159,0,0,0,
    0,0,31,0,255,0,0,0,0,0,95,0,223,0,0,0,0,0,63,0,191,0,0,0,0,0,0,0,159,0,127,0,
    141,0,28,0,28,0,28,0,113,0,8,0,8,0,8,0,255,0,0,0,226,0,0,0,198,0,56,0,170,0,85,0,
};
static const uint8_t node_17[] = {
    255,0,0,0,8,0,0,0,182,0,0,0,72,0,0,0,218,0,0,0,36,0,0,0,145,0,0,0,109,0,0,0,
    0,0,51,25,76,25,25,0,153,0,0,0,127,102,178,0,204,0,0,0,0,0,255,0,0,0,102,0,229,0,76,0,
    113,0,0,0,141,0,85,0,0,0,0,0,170,0,0,0,56,28,255,0,0,0,0,0,198,0,0,0,226,0,0,0,
};
static const uint8_t node_18[] = {
    255,0,8,0,28,0,28,0,198,0,56,0,56,0,85,0,255,0,85,0,113,0,113,0,226,0,141,0,170,0,141,0,
    0,0,0,0,0,0,0,0,255,0,0,0,127,0,0,0,0,0,0,0,0,0,0,0,63,0,0,0,191,0,0,0,
    255,0,0,0,255,0,127,0,0,0,85,0,0,0,212,0,0,0,212,0,42,0,170,0,0,0,127,0,0,0,0,0,
};
static const uint8_t node_19[] = {
    255,0,0,0,0,0,218,0,182,0,0,0,0,0,145,0,145,0,36,0,0,0,109,0,109,0,0,0,72,0,36,0,
    0,0,0,0,109,0,8,0,72,0,0,0,255,0,182,0,0,0,0,0,145,0,8,0,36,0,8,0,218,0,182,0,
    255,0,0,0,0,0,226,0,85,0,0,0,141,0,0,0,0,0,0,0,170,0,56,0,198,0,0,0,113,0,28,0,
};
static const uint8_t node_20[] = {
    255,0,0,0,113,0,0,0,198,0,56,0,85,0,28,0,255,0,0,0,226,0,0,0,170,0,0,0,141,0,0,0,
    0,0,0,0,0,0,0,0,255,0,145,0,109,0,218,0,36,0,182,0,72,0,72,0,255,0,0,0,0,0,109,0,
    36,0,36,0,145,0,0,0,72,0,72,0,182,0,0,0,72,0,72,0,218,0,0,0,109,0,109,0,255,0,0,0,
};
static const uint8_t node_21[] = {
    255,0,0,0,218,0,0,0,145,0,0,0,36,0,0,0,218,0,0,0,36,0,0,0,182,0,72,0,0,0,109,0,
    0,0,0,0,8,0,0,0,255,0,85,0,212,0,42,0,0,0,0,0,8,0,0,0,85,0,170,0,127,0,42,0,
    109,0,109,0,255,0,0,0,72,0,72,0,218,0,0,0,145,0,182,0,255,0,0,0,36,0,36,0,218,0,8,0,
};
static const uint8_t node_22[] = {
    255,0,0,0,42,0,0,0,212,0,0,0,8,0,212,0,170,0,0,0,85,0,0,0,212,0,8,0,127,0,8,0,
    255,0,85,0,0,0,0,0,226,0,85,0,0,0,198,0,0,0,141,0,56,0,0,0,170,0,28,0,0,0,113,0,
    113,0,56,0,255,0,0,0,85,0,56,0,226,0,0,0,0,0,170,0,0,0,141,0,28,0,28,0,198,0,28,0,
};
static const uint8_t node_23[] = {
    255,0,0,0,229,0,0,0,204,0,204,0,0,0,76,0,178,0,153,0,51,0,178,0,178,0,127,0,102,51,51,25,
    0,0,0,0,0,0,0,31,0,0,0,0,255,0,0,31,0,0,8,0,0,0,191,159,127,95,95,0,223,0,63,0,
    255,0,255,0,204,204,204,204,0,0,51,51,51,51,0,0,204,0,204,0,153,153,153,153,153,0,0,0,102,102,102,102,
};
static const uint8_t node_24[] = {
    170,0,0,0,0,255,0,0,198,0,0,0,0,28,0,0,141,0,0,0,0,226,0,0,56,0,0,113,0,85,0,0,
    255,0,0,0,0,113,0,0,85,0,0,0,0,226,0,0,141,0,0,8,0,170,56,56,198,0,0,56,0,141,28,0,
    255,0,0,0,0,191,0,0,159,0,0,0,0,223,0,0,95,0,0,0,0,63,0,0,127,0,0,0,0,31,0,0,
};

// Drum map: 5×5 grid mapping (row=x>>6, col=y>>6) to node arrays
// Layout matches MI Grids pattern_generator.cc
static const uint8_t* const drum_map[5][5] = {
    { node_10, node_8,  node_0,  node_9,  node_11 },
    { node_15, node_7,  node_13, node_12, node_6  },
    { node_18, node_14, node_4,  node_5,  node_3  },
    { node_23, node_16, node_21, node_1,  node_2  },
    { node_24, node_19, node_17, node_20, node_22 },
};

// U8Mix: unsigned 8-bit linear interpolation (matches avrlib/op.h)
// result = a + (b - a) * balance / 256
static inline uint8_t U8Mix(uint8_t a, uint8_t b, uint8_t balance) {
    return a + static_cast<uint8_t>((static_cast<int16_t>(b - a) * balance) >> 8);
}

// ReadDrumMap: bilinear interpolation across 4 nearest nodes
// step: 0..31, instrument: 0..2, x/y: 0..255
static uint8_t ReadDrumMap(uint8_t step, uint8_t instrument, uint8_t x, uint8_t y) {
    uint8_t i = x >> 6;  // 0..3
    uint8_t j = y >> 6;  // 0..3
    const uint8_t* a_map = drum_map[i][j];
    const uint8_t* b_map = drum_map[i + 1][j];
    const uint8_t* c_map = drum_map[i][j + 1];
    const uint8_t* d_map = drum_map[i + 1][j + 1];
    uint8_t offset = instrument * 32 + step;
    uint8_t a = a_map[offset];
    uint8_t b = b_map[offset];
    uint8_t c = c_map[offset];
    uint8_t d = d_map[offset];
    return U8Mix(U8Mix(a, b, x << 2), U8Mix(c, d, x << 2), y << 2);
}

} // namespace grids_data

void unit_process_grids(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* clock_in  = u->inputs[IPORT_INPUT_A].buffer;  // 24 PPQN tick pulses
    float* out_kick  = u->output_buffers[OPORT_OUT];
    float* out_snare = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_hat   = u->output_buffers[OPORT_AUX];

    if (engine->grids_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_kick,  0, num_frames * sizeof(float));
        std::memset(out_snare, 0, num_frames * sizeof(float));
        std::memset(out_hat,   0, num_frames * sizeof(float));
        return;
    }

    // Read parameters
    float fx = engine->grids_x.load(std::memory_order_relaxed);
    float fy = engine->grids_y.load(std::memory_order_relaxed);
    uint8_t gx = static_cast<uint8_t>(std::max(0.0f, std::min(1.0f, fx)) * 255.0f);
    uint8_t gy = static_cast<uint8_t>(std::max(0.0f, std::min(1.0f, fy)) * 255.0f);

    // Density: 0.0 = sparse (high threshold), 1.0 = dense (low threshold)
    // MI Grids uses ~density (bitwise NOT), so threshold = 255 - density*255
    float dk = engine->grids_density_kick.load(std::memory_order_relaxed);
    float ds = engine->grids_density_snare.load(std::memory_order_relaxed);
    float dh = engine->grids_density_hat.load(std::memory_order_relaxed);
    uint8_t threshold[3] = {
        static_cast<uint8_t>((1.0f - std::max(0.0f, std::min(1.0f, dk))) * 255.0f),
        static_cast<uint8_t>((1.0f - std::max(0.0f, std::min(1.0f, ds))) * 255.0f),
        static_cast<uint8_t>((1.0f - std::max(0.0f, std::min(1.0f, dh))) * 255.0f),
    };

    float randomness = engine->grids_randomness.load(std::memory_order_relaxed);
    int trigger_samples = static_cast<int>(engine->grids_trigger_duration * sample_rate);
    if (trigger_samples < 1) trigger_samples = 1;

    for (int i = 0; i < num_frames; i++) {
        bool tick = clock_in[i] > 0.5f;

        if (tick) {
            engine->grids_pulse_count++;
            // Every 6th clock tick = one 16th-note step (24 PPQN / 6 = 4 PPQN)
            if (engine->grids_pulse_count >= 6) {
                engine->grids_pulse_count = 0;

                // Evaluate pattern for current step
                int step = engine->grids_step;
                for (int ch = 0; ch < 3; ch++) {
                    uint8_t level = grids_data::ReadDrumMap(
                        static_cast<uint8_t>(step),
                        static_cast<uint8_t>(ch),
                        gx, gy);

                    // Add randomness perturbation (LCG PRNG, audio-safe)
                    if (randomness > 0.0f) {
                        engine->grids_rng_state = engine->grids_rng_state * 1664525u + 1013904223u;
                        uint8_t noise = static_cast<uint8_t>(engine->grids_rng_state >> 24);
                        uint8_t perturb = static_cast<uint8_t>(randomness * static_cast<float>(noise) * 0.5f);
                        if (level < 255 - perturb) {
                            level += perturb;
                        } else {
                            level = 255;
                        }
                    }

                    if (level > threshold[ch]) {
                        engine->grids_trigger_countdown[ch] = trigger_samples;
                    }
                }

                engine->grids_step = (step + 1) % 32;
            }
        }

        // Output trigger pulses with countdown
        out_kick[i]  = engine->grids_trigger_countdown[0] > 0 ? 1.0f : 0.0f;
        out_snare[i] = engine->grids_trigger_countdown[1] > 0 ? 1.0f : 0.0f;
        out_hat[i]   = engine->grids_trigger_countdown[2] > 0 ? 1.0f : 0.0f;

        for (int ch = 0; ch < 3; ch++) {
            if (engine->grids_trigger_countdown[ch] > 0)
                engine->grids_trigger_countdown[ch]--;
        }
    }
}

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
