# C++ Wiring Graph Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the hardcoded procedural C++ DSP engine with a graph-based scheduler driven by a Kotlin DSL, enabling engine 0, quad holds, envelopes, and full effects chain parity.

**Architecture:** C++ graph runtime with Tarjan SCC scheduler processes units in topological order. Kotlin DSL builds graph description, serializes to binary, sends via single JNI call. Graph topology immutable at runtime; parameters mutable.

**Tech Stack:** C++ (liborpheus_dsp), Kotlin (core/foundation DSL), JNI (existing bridge pattern)

**Design doc:** `docs/plans/2026-03-07-cpp-wiring-graph-design.md`

---

## Task 1: C++ Graph Data Structures and Enums

**Files:**
- Create: `liborpheus_dsp/src/orpheus_graph.h`

**Step 1: Create the header with enums and structs**

```cpp
#pragma once

#include <cstdint>
#include <cstddef>

// ── Unit type enum ──────────────────────────────
enum OrpheusUnitType : uint16_t {
    UNIT_TRIANGLE_OSC = 0,
    UNIT_SQUARE_OSC = 1,
    UNIT_MULTIPLY = 2,
    UNIT_ADD = 3,
    UNIT_MULTIPLY_ADD = 4,
    UNIT_ENVELOPE = 5,
    UNIT_LINEAR_RAMP = 6,
    UNIT_PASS_THROUGH = 7,
    UNIT_PEAK_FOLLOWER = 8,
    UNIT_HARD_CLIP = 9,
    UNIT_LIMITER = 10,
    UNIT_PLAITS = 11,
    UNIT_CLOUDS = 12,
    UNIT_RINGS = 13,
    UNIT_WARPS = 14,
    UNIT_DELAY_LINE = 15,
    UNIT_REVERB = 16,
    UNIT_MASTER_OUT = 17,
    UNIT_TYPE_COUNT
};

// ── Output port enum ────────────────────────────
enum OrpheusOutPort : uint16_t {
    OPORT_OUT = 0,
    OPORT_OUT_RIGHT = 1,
    OPORT_AUX = 2,
    OPORT_COUNT = 3
};

// ── Input port enum ─────────────────────────────
enum OrpheusInPort : uint16_t {
    IPORT_INPUT = 0,
    IPORT_INPUT_A = 1,
    IPORT_INPUT_B = 2,
    IPORT_INPUT_C = 3,
    IPORT_FREQUENCY = 4,
    IPORT_AMPLITUDE = 5,
    IPORT_GATE = 6,
    IPORT_TIME = 7,
    IPORT_DRIVE = 8,
    IPORT_TRIGGER = 9,
    IPORT_COUNT = 10
};

// ── Param key enum (initial values in descriptor) ─
enum OrpheusParamKey : uint16_t {
    PARAM_FREQUENCY = 0,
    PARAM_AMPLITUDE = 1,
    PARAM_ATTACK = 2,
    PARAM_DECAY = 3,
    PARAM_SUSTAIN = 4,
    PARAM_RELEASE = 5,
    PARAM_TIME = 6,
    PARAM_HALF_LIFE = 7,
    PARAM_MAX_DELAY = 8,
    PARAM_DRIVE = 9,
    PARAM_INPUT_A = 10,
    PARAM_INPUT_B = 11,
    PARAM_INPUT_C = 12,
};

// ── Port map entry (URI+symbol hash → unit+port) ─
struct PortMapEntry {
    uint16_t uri_hash;
    uint16_t symbol_hash;
    uint16_t unit_id;
    uint16_t port;
};

// ── Max limits ──────────────────────────────────
static constexpr int kMaxUnits = 600;
static constexpr int kMaxConnections = 1200;
static constexpr int kMaxInputPorts = 10;
static constexpr int kMaxOutputPorts = 3;
static constexpr int kMaxPortMapEntries = 400;
static constexpr int kMaxFrames = 512;

// ── Forward declarations ────────────────────────
struct OrpheusGraph;

// ── Input port ──────────────────────────────────
struct GraphPort {
    float buffer[kMaxFrames];  // pre-allocated
    float constant;            // used when num_sources == 0
    float smoothed;            // current smoothed value
    bool  is_smoothed;         // whether this port applies smoothing
    int   num_sources;         // how many outputs connected
    // Source buffer pointers (up to 4 sources per input)
    float* sources[4];
};

// ── Unit state (union of all possible states) ───
struct OscState {
    float phase;
};

struct EnvelopeState {
    float level;
    int   stage;  // 0=idle, 1=attack, 2=decay, 3=sustain, 4=release
    bool  gate_was_on;
    float attack_rate;
    float decay_coeff;
    float sustain_level;
    float release_coeff;
};

struct RampState {
    float current;
};

struct PeakState {
    float peak;
    float decay_coeff;
};

struct DelayState {
    float* buffer;
    int    write_pos;
    int    buffer_size;
};

// Plaits/Clouds/Rings/Warps use indices into OrpheusEngine arrays
struct ModuleState {
    int index;  // index into OrpheusEngine's MI processor arrays
};

union UnitState {
    OscState osc;
    EnvelopeState env;
    RampState ramp;
    PeakState peak;
    DelayState delay;
    ModuleState module;
};

// ── Graph unit ──────────────────────────────────
struct GraphUnit {
    uint16_t type;
    uint16_t id;
    bool     enabled;
    GraphPort inputs[kMaxInputPorts];
    float     output_buffers[kMaxOutputPorts][kMaxFrames];
    UnitState state;
};

// ── The graph ───────────────────────────────────
struct OrpheusGraph {
    GraphUnit units[kMaxUnits];
    int       unit_count;

    // Topological execution order (indices into units[])
    int       exec_order[kMaxUnits];
    int       exec_count;

    // Port map for nativeSetPort routing
    PortMapEntry port_map[kMaxPortMapEntries];
    int          port_map_count;

    // Master output unit index
    int master_out_index;

    float sample_rate;
};

// ── Graph API ───────────────────────────────────
// Parse binary descriptor into graph. Returns 0 on success.
int  orpheus_graph_load(OrpheusGraph* graph, const uint8_t* data,
                        size_t length, float sample_rate);

// Process all units in topological order, write to interleaved stereo output.
void orpheus_graph_process(OrpheusGraph* graph,
                           float* output_buffer, int num_frames);

// Set a port value by URI+symbol hash lookup.
void orpheus_graph_set_port(OrpheusGraph* graph,
                            uint16_t uri_hash, uint16_t symbol_hash,
                            float value);

// Set a unit's input port constant directly by unit_id + port index.
void orpheus_graph_set_unit_port(OrpheusGraph* graph,
                                 uint16_t unit_id, uint16_t port,
                                 float value);
```

**Step 2: Build to verify header compiles**

Run: `./gradlew :apps:androidApp:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (header is only included, not yet used)

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_graph.h
git commit -m "feat(graph): Add wiring graph data structures and enums"
```

---

## Task 2: C++ Unit Process Functions

**Files:**
- Create: `liborpheus_dsp/src/orpheus_units.h`
- Create: `liborpheus_dsp/src/orpheus_units.cpp`

**Step 1: Create unit process function declarations**

`orpheus_units.h`:
```cpp
#pragma once
#include "orpheus_graph.h"

// Per-type process functions. Each processes num_frames samples.
void unit_process_triangle_osc(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_square_osc(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_multiply(GraphUnit* u, int num_frames);
void unit_process_add(GraphUnit* u, int num_frames);
void unit_process_multiply_add(GraphUnit* u, int num_frames);
void unit_process_envelope(GraphUnit* u, int num_frames);
void unit_process_linear_ramp(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_pass_through(GraphUnit* u, int num_frames);
void unit_process_peak_follower(GraphUnit* u, int num_frames);
void unit_process_hard_clip(GraphUnit* u, int num_frames);
void unit_process_limiter(GraphUnit* u, int num_frames);
void unit_process_delay_line(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_master_out(GraphUnit* u, float* output_buffer, int num_frames);

// Initialize unit state from descriptor params
void unit_init(GraphUnit* u, float sample_rate);
```

**Step 2: Implement all unit process functions**

`orpheus_units.cpp` — the core DSP math ported from Kotlin:

```cpp
#include "orpheus_units.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// ── Smoothing coefficient (~5ms at any sample rate) ──
static float smooth_coeff(float sample_rate) {
    return 1.0f - std::exp(-1.0f / (0.005f * sample_rate));
}

// ── Input port prepare: fill buffer from sources or constant ──
static void port_prepare(GraphPort* p, int num_frames, float sr) {
    if (p->num_sources == 0) {
        if (p->is_smoothed) {
            float coeff = smooth_coeff(sr);
            for (int i = 0; i < num_frames; i++) {
                p->smoothed += coeff * (p->constant - p->smoothed);
                p->buffer[i] = p->smoothed;
            }
        } else {
            for (int i = 0; i < num_frames; i++)
                p->buffer[i] = p->constant;
        }
    } else if (p->num_sources == 1) {
        std::memcpy(p->buffer, p->sources[0], num_frames * sizeof(float));
    } else {
        std::memcpy(p->buffer, p->sources[0], num_frames * sizeof(float));
        for (int s = 1; s < p->num_sources; s++) {
            for (int i = 0; i < num_frames; i++)
                p->buffer[i] += p->sources[s][i];
        }
    }
}

// ── Oscillators ─────────────────────────────────

void unit_process_triangle_osc(GraphUnit* u, int n, float sr) {
    float* freq = u->inputs[IPORT_FREQUENCY].buffer;
    float* amp  = u->inputs[IPORT_AMPLITUDE].buffer;
    float* out  = u->output_buffers[OPORT_OUT];
    float phase = u->state.osc.phase;
    for (int i = 0; i < n; i++) {
        out[i] = (4.0f * std::fabs(phase - 0.5f) - 1.0f) * amp[i];
        phase += freq[i] / sr;
        phase -= std::floor(phase);
    }
    u->state.osc.phase = phase;
}

void unit_process_square_osc(GraphUnit* u, int n, float sr) {
    float* freq = u->inputs[IPORT_FREQUENCY].buffer;
    float* amp  = u->inputs[IPORT_AMPLITUDE].buffer;
    float* out  = u->output_buffers[OPORT_OUT];
    float phase = u->state.osc.phase;
    for (int i = 0; i < n; i++) {
        out[i] = (phase < 0.5f ? 1.0f : -1.0f) * amp[i];
        phase += freq[i] / sr;
        phase -= std::floor(phase);
    }
    u->state.osc.phase = phase;
}

// ── Math ────────────────────────────────────────

void unit_process_multiply(GraphUnit* u, int n) {
    float* a   = u->inputs[IPORT_INPUT_A].buffer;
    float* b   = u->inputs[IPORT_INPUT_B].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = a[i] * b[i];
}

void unit_process_add(GraphUnit* u, int n) {
    float* a   = u->inputs[IPORT_INPUT_A].buffer;
    float* b   = u->inputs[IPORT_INPUT_B].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = a[i] + b[i];
}

void unit_process_multiply_add(GraphUnit* u, int n) {
    float* a   = u->inputs[IPORT_INPUT_A].buffer;
    float* b   = u->inputs[IPORT_INPUT_B].buffer;
    float* c   = u->inputs[IPORT_INPUT_C].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = a[i] * b[i] + c[i];
}

void unit_process_pass_through(GraphUnit* u, int n) {
    std::memcpy(u->output_buffers[OPORT_OUT],
                u->inputs[IPORT_INPUT].buffer, n * sizeof(float));
}

// ── Dynamics ────────────────────────────────────

void unit_process_envelope(GraphUnit* u, int n) {
    float* gate = u->inputs[IPORT_GATE].buffer;
    float* out  = u->output_buffers[OPORT_OUT];
    auto& e = u->state.env;

    for (int i = 0; i < n; i++) {
        bool gate_on = gate[i] > 0.0f;

        // Edge detection
        if (gate_on && !e.gate_was_on) e.stage = 1; // ATTACK
        if (!gate_on && e.gate_was_on) e.stage = 4; // RELEASE
        e.gate_was_on = gate_on;

        switch (e.stage) {
            case 1: // ATTACK (linear ramp to 1.0)
                e.level += e.attack_rate;
                if (e.level >= 1.0f) { e.level = 1.0f; e.stage = 2; }
                break;
            case 2: // DECAY (exponential toward sustain)
                e.level = e.sustain_level +
                          (e.level - e.sustain_level) * e.decay_coeff;
                if (e.level - e.sustain_level < 0.0001f) {
                    e.level = e.sustain_level; e.stage = 3;
                }
                break;
            case 3: // SUSTAIN
                e.level = e.sustain_level;
                break;
            case 4: // RELEASE (exponential toward 0)
                e.level *= e.release_coeff;
                if (e.level < 0.0001f) { e.level = 0.0f; e.stage = 0; }
                break;
            default: // IDLE
                e.level = 0.0f;
                break;
        }
        out[i] = e.level;
    }
}

void unit_process_linear_ramp(GraphUnit* u, int n, float sr) {
    float* target = u->inputs[IPORT_INPUT].buffer;
    float* time   = u->inputs[IPORT_TIME].buffer;
    float* out    = u->output_buffers[OPORT_OUT];
    float current = u->state.ramp.current;

    for (int i = 0; i < n; i++) {
        float t = std::max(time[i], 0.001f);
        float rate = 1.0f / (t * sr);
        float diff = target[i] - current;
        current += std::max(-rate, std::min(rate, diff));
        out[i] = current;
    }
    u->state.ramp.current = current;
}

void unit_process_peak_follower(GraphUnit* u, int n) {
    float* in  = u->inputs[IPORT_INPUT].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    float peak = u->state.peak.peak;
    float coeff = u->state.peak.decay_coeff;

    for (int i = 0; i < n; i++) {
        float s = std::fabs(in[i]);
        peak = std::max(s, peak * coeff);
        out[i] = peak;
    }
    u->state.peak.peak = peak;
}

void unit_process_hard_clip(GraphUnit* u, int n) {
    float* in  = u->inputs[IPORT_INPUT].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = std::max(-1.0f, std::min(1.0f, in[i]));
}

void unit_process_limiter(GraphUnit* u, int n) {
    float* in    = u->inputs[IPORT_INPUT].buffer;
    float* drive = u->inputs[IPORT_DRIVE].buffer;
    float* out   = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = std::tanh(in[i] * drive[i]);
}

void unit_process_delay_line(GraphUnit* u, int n, float sr) {
    float* in    = u->inputs[IPORT_INPUT].buffer;
    float* dtime = u->inputs[IPORT_TIME].buffer;
    float* out   = u->output_buffers[OPORT_OUT];
    auto& d = u->state.delay;

    for (int i = 0; i < n; i++) {
        d.buffer[d.write_pos] = in[i];
        int delay_samples = static_cast<int>(dtime[i] * sr + 0.5f);
        delay_samples = std::max(0, std::min(delay_samples, d.buffer_size - 1));
        int read_pos = d.write_pos - delay_samples;
        if (read_pos < 0) read_pos += d.buffer_size;
        out[i] = d.buffer[read_pos];
        d.write_pos = (d.write_pos + 1) % d.buffer_size;
    }
}

void unit_process_master_out(GraphUnit* u, float* output_buffer, int n) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    for (int i = 0; i < n; i++) {
        output_buffer[i * 2]     = in_l[i];
        output_buffer[i * 2 + 1] = in_r[i];
    }
}

// ── Unit initialization from descriptor params ──
void unit_init(GraphUnit* u, float sr) {
    std::memset(&u->state, 0, sizeof(UnitState));

    // Set default smoothing per port based on unit type
    for (int p = 0; p < kMaxInputPorts; p++) {
        u->inputs[p].constant = 0.0f;
        u->inputs[p].smoothed = 0.0f;
        u->inputs[p].num_sources = 0;
        u->inputs[p].is_smoothed = false;
    }
    for (int p = 0; p < kMaxOutputPorts; p++)
        std::memset(u->output_buffers[p], 0, sizeof(u->output_buffers[p]));

    u->enabled = true;

    // Type-specific defaults
    switch (u->type) {
        case UNIT_TRIANGLE_OSC:
        case UNIT_SQUARE_OSC:
            u->inputs[IPORT_FREQUENCY].is_smoothed = true;
            u->inputs[IPORT_FREQUENCY].constant = 440.0f;
            u->inputs[IPORT_AMPLITUDE].is_smoothed = true;
            u->inputs[IPORT_AMPLITUDE].constant = 0.3f;
            break;
        case UNIT_MULTIPLY:
        case UNIT_ADD:
            u->inputs[IPORT_INPUT_A].is_smoothed = true;
            u->inputs[IPORT_INPUT_B].is_smoothed = true;
            break;
        case UNIT_MULTIPLY_ADD:
            u->inputs[IPORT_INPUT_A].is_smoothed = true;
            u->inputs[IPORT_INPUT_B].is_smoothed = true;
            u->inputs[IPORT_INPUT_C].is_smoothed = true;
            break;
        case UNIT_ENVELOPE:
            u->state.env.attack_rate = 1.0f / (0.01f * sr);
            u->state.env.decay_coeff = std::exp(-1.0f / (0.1f * sr));
            u->state.env.sustain_level = 0.7f;
            u->state.env.release_coeff = std::exp(-1.0f / (0.3f * sr));
            break;
        case UNIT_LINEAR_RAMP:
            u->inputs[IPORT_TIME].is_smoothed = true;
            u->inputs[IPORT_TIME].constant = 0.02f;
            break;
        case UNIT_PEAK_FOLLOWER:
            u->state.peak.decay_coeff =
                std::exp(std::log(0.5f) / (0.15f * sr));
            break;
        case UNIT_LIMITER:
            u->inputs[IPORT_INPUT].is_smoothed = false;
            u->inputs[IPORT_DRIVE].is_smoothed = true;
            u->inputs[IPORT_DRIVE].constant = 1.0f;
            break;
        case UNIT_DELAY_LINE:
            u->inputs[IPORT_TIME].is_smoothed = true;
            break;
        default:
            break;
    }
}
```

**Step 3: Add new source files to CMakeLists.txt**

Modify: `apps/androidApp/src/main/cpp/CMakeLists.txt` — add `orpheus_units.cpp` to the source list alongside `orpheus_engine.cpp`.

**Step 4: Build to verify compilation**

Run: `./gradlew :apps:androidApp:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_units.cpp
git commit -m "feat(graph): Add unit process functions for all primitive types"
```

---

## Task 3: Binary Descriptor Parser

**Files:**
- Create: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1: Implement the descriptor parser**

```cpp
#include "orpheus_graph.h"
#include "orpheus_units.h"
#include <cstring>

// ── Little-endian read helpers ──────────────────
static uint16_t read_u16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}
static float read_f32(const uint8_t* p) {
    float f;
    std::memcpy(&f, p, 4);
    return f;
}

int orpheus_graph_load(OrpheusGraph* graph, const uint8_t* data,
                       size_t length, float sample_rate) {
    if (length < 12) return -1;

    // Header
    if (data[0] != 'O' || data[1] != 'D' || data[2] != 'W' || data[3] != 'G')
        return -2; // bad magic
    uint16_t version = read_u16(data + 4);
    if (version != 1) return -3;

    uint16_t unit_count = read_u16(data + 6);
    uint16_t conn_count = read_u16(data + 8);
    uint16_t total_params = read_u16(data + 10);
    (void)total_params;

    if (unit_count > kMaxUnits) return -4;
    if (conn_count > kMaxConnections) return -5;

    graph->unit_count = unit_count;
    graph->sample_rate = sample_rate;
    graph->master_out_index = -1;

    // Parse units
    size_t pos = 12;
    for (int i = 0; i < unit_count; i++) {
        if (pos + 6 > length) return -6;
        GraphUnit* u = &graph->units[i];
        u->type = read_u16(data + pos);
        u->id   = read_u16(data + pos + 2);
        uint16_t param_count = read_u16(data + pos + 4);
        pos += 6;

        unit_init(u, sample_rate);

        // Apply descriptor params
        for (int p = 0; p < param_count; p++) {
            if (pos + 6 > length) return -7;
            uint16_t key = read_u16(data + pos);
            float val = read_f32(data + pos + 2);
            pos += 6;

            // Map param keys to port constants or state
            switch (key) {
                case PARAM_FREQUENCY:
                    u->inputs[IPORT_FREQUENCY].constant = val;
                    u->inputs[IPORT_FREQUENCY].smoothed = val;
                    break;
                case PARAM_AMPLITUDE:
                    u->inputs[IPORT_AMPLITUDE].constant = val;
                    u->inputs[IPORT_AMPLITUDE].smoothed = val;
                    break;
                case PARAM_ATTACK:
                    u->state.env.attack_rate = 1.0f / (val * sample_rate);
                    break;
                case PARAM_DECAY:
                    u->state.env.decay_coeff =
                        std::exp(-1.0f / (val * sample_rate));
                    break;
                case PARAM_SUSTAIN:
                    u->state.env.sustain_level = val;
                    break;
                case PARAM_RELEASE:
                    u->state.env.release_coeff =
                        std::exp(-1.0f / (val * sample_rate));
                    break;
                case PARAM_TIME:
                    u->inputs[IPORT_TIME].constant = val;
                    u->inputs[IPORT_TIME].smoothed = val;
                    break;
                case PARAM_HALF_LIFE:
                    u->state.peak.decay_coeff =
                        std::exp(std::log(0.5f) / (val * sample_rate));
                    break;
                case PARAM_MAX_DELAY: {
                    int samples = static_cast<int>(val * sample_rate);
                    u->state.delay.buffer_size = samples;
                    u->state.delay.buffer = new float[samples]();
                    break;
                }
                case PARAM_DRIVE:
                    u->inputs[IPORT_DRIVE].constant = val;
                    u->inputs[IPORT_DRIVE].smoothed = val;
                    break;
                case PARAM_INPUT_A:
                    u->inputs[IPORT_INPUT_A].constant = val;
                    u->inputs[IPORT_INPUT_A].smoothed = val;
                    break;
                case PARAM_INPUT_B:
                    u->inputs[IPORT_INPUT_B].constant = val;
                    u->inputs[IPORT_INPUT_B].smoothed = val;
                    break;
                case PARAM_INPUT_C:
                    u->inputs[IPORT_INPUT_C].constant = val;
                    u->inputs[IPORT_INPUT_C].smoothed = val;
                    break;
            }
        }

        if (u->type == UNIT_MASTER_OUT)
            graph->master_out_index = i;
    }

    // Parse connections
    for (int c = 0; c < conn_count; c++) {
        if (pos + 8 > length) return -8;
        uint16_t src_id   = read_u16(data + pos);
        uint16_t src_port = read_u16(data + pos + 2);
        uint16_t dst_id   = read_u16(data + pos + 4);
        uint16_t dst_port = read_u16(data + pos + 6);
        pos += 8;

        // Find units by id (units are in id order from serializer)
        if (src_id >= unit_count || dst_id >= unit_count) return -9;
        if (src_port >= OPORT_COUNT || dst_port >= IPORT_COUNT) return -10;

        GraphUnit* src = &graph->units[src_id];
        GraphUnit* dst = &graph->units[dst_id];
        GraphPort* inp = &dst->inputs[dst_port];

        if (inp->num_sources < 4) {
            inp->sources[inp->num_sources] = src->output_buffers[src_port];
            inp->num_sources++;
        }
    }

    // Parse port map
    if (pos + 2 <= length) {
        uint16_t map_count = read_u16(data + pos);
        pos += 2;
        graph->port_map_count = std::min(static_cast<int>(map_count),
                                         kMaxPortMapEntries);
        for (int m = 0; m < graph->port_map_count; m++) {
            if (pos + 8 > length) return -11;
            graph->port_map[m].uri_hash    = read_u16(data + pos);
            graph->port_map[m].symbol_hash = read_u16(data + pos + 2);
            graph->port_map[m].unit_id     = read_u16(data + pos + 4);
            graph->port_map[m].port        = read_u16(data + pos + 6);
            pos += 8;
        }
    }

    return 0; // success
}
```

**Step 2: Build**

Run: `./gradlew :apps:androidApp:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(graph): Add binary descriptor parser"
```

---

## Task 4: Tarjan SCC and Topological Sort

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1: Add Tarjan SCC + Kahn's toposort after the parser**

Append to `orpheus_graph.cpp`:

```cpp
// ── Tarjan SCC + Topological Sort ───────────────

// Build adjacency list from connections, run Tarjan, then Kahn's on SCC DAG.
// Stores execution order in graph->exec_order[].

static int tarjan_disc[kMaxUnits];
static int tarjan_low[kMaxUnits];
static bool tarjan_on_stack[kMaxUnits];
static int tarjan_stack[kMaxUnits];
static int tarjan_stack_top;
static int tarjan_timer;
static int tarjan_scc_id[kMaxUnits];
static int tarjan_scc_count;

// Adjacency: adj[u] stores up to 8 downstream units
static int adj[kMaxUnits][8];
static int adj_count[kMaxUnits];

static void tarjan_dfs(int u) {
    tarjan_disc[u] = tarjan_low[u] = tarjan_timer++;
    tarjan_stack[tarjan_stack_top++] = u;
    tarjan_on_stack[u] = true;

    for (int j = 0; j < adj_count[u]; j++) {
        int v = adj[u][j];
        if (tarjan_disc[v] == -1) {
            tarjan_dfs(v);
            tarjan_low[u] = std::min(tarjan_low[u], tarjan_low[v]);
        } else if (tarjan_on_stack[v]) {
            tarjan_low[u] = std::min(tarjan_low[u], tarjan_disc[v]);
        }
    }

    if (tarjan_low[u] == tarjan_disc[u]) {
        int scc = tarjan_scc_count++;
        while (true) {
            int w = tarjan_stack[--tarjan_stack_top];
            tarjan_on_stack[w] = false;
            tarjan_scc_id[w] = scc;
            if (w == u) break;
        }
    }
}

void orpheus_graph_sort(OrpheusGraph* graph) {
    int n = graph->unit_count;

    // Build adjacency from connections (input sources → downstream)
    std::memset(adj_count, 0, sizeof(adj_count));
    for (int u = 0; u < n; u++) {
        for (int p = 0; p < kMaxInputPorts; p++) {
            GraphPort* inp = &graph->units[u].inputs[p];
            for (int s = 0; s < inp->num_sources; s++) {
                // Find which unit owns this source buffer
                for (int src = 0; src < n; src++) {
                    for (int op = 0; op < kMaxOutputPorts; op++) {
                        if (graph->units[src].output_buffers[op] ==
                            inp->sources[s]) {
                            if (adj_count[src] < 8)
                                adj[src][adj_count[src]++] = u;
                            goto next_source;
                        }
                    }
                }
                next_source:;
            }
        }
    }

    // Tarjan SCC
    std::memset(tarjan_disc, -1, sizeof(tarjan_disc));
    tarjan_stack_top = 0;
    tarjan_timer = 0;
    tarjan_scc_count = 0;
    for (int i = 0; i < n; i++) {
        if (tarjan_disc[i] == -1)
            tarjan_dfs(i);
    }

    // Kahn's toposort on SCC DAG
    int scc_in_degree[kMaxUnits] = {};
    // SCC adjacency (deduplicated)
    int scc_adj[kMaxUnits][8] = {};
    int scc_adj_count[kMaxUnits] = {};

    for (int u = 0; u < n; u++) {
        int su = tarjan_scc_id[u];
        for (int j = 0; j < adj_count[u]; j++) {
            int sv = tarjan_scc_id[adj[u][j]];
            if (su != sv) {
                // Check for duplicate
                bool dup = false;
                for (int k = 0; k < scc_adj_count[su]; k++) {
                    if (scc_adj[su][k] == sv) { dup = true; break; }
                }
                if (!dup && scc_adj_count[su] < 8) {
                    scc_adj[su][scc_adj_count[su]++] = sv;
                    scc_in_degree[sv]++;
                }
            }
        }
    }

    int queue[kMaxUnits];
    int q_head = 0, q_tail = 0;
    for (int s = 0; s < tarjan_scc_count; s++) {
        if (scc_in_degree[s] == 0)
            queue[q_tail++] = s;
    }

    int scc_order[kMaxUnits];
    int scc_order_count = 0;
    while (q_head < q_tail) {
        int s = queue[q_head++];
        scc_order[scc_order_count++] = s;
        for (int j = 0; j < scc_adj_count[s]; j++) {
            int dep = scc_adj[s][j];
            if (--scc_in_degree[dep] == 0)
                queue[q_tail++] = dep;
        }
    }

    // Expand SCCs into unit execution order (insertion order within SCC)
    graph->exec_count = 0;
    for (int si = 0; si < scc_order_count; si++) {
        int scc = scc_order[si];
        for (int u = 0; u < n; u++) {
            if (tarjan_scc_id[u] == scc)
                graph->exec_order[graph->exec_count++] = u;
        }
    }
}
```

**Step 2: Call `orpheus_graph_sort` at the end of `orpheus_graph_load`**

Add before `return 0;` in `orpheus_graph_load`:

```cpp
    orpheus_graph_sort(graph);
    return 0;
```

**Step 3: Build and verify**

Run: `./gradlew :apps:androidApp:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(graph): Add Tarjan SCC and topological sort"
```

---

## Task 5: Graph Process Loop

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1: Implement the graph process function and port routing**

Append to `orpheus_graph.cpp`:

```cpp
// ── Graph process: run all units in topological order ─

void orpheus_graph_process(OrpheusGraph* graph,
                           float* output_buffer, int num_frames) {
    if (num_frames > kMaxFrames) num_frames = kMaxFrames;
    float sr = graph->sample_rate;

    for (int ei = 0; ei < graph->exec_count; ei++) {
        int idx = graph->exec_order[ei];
        GraphUnit* u = &graph->units[idx];
        if (!u->enabled) continue;

        // Prepare all input ports
        for (int p = 0; p < kMaxInputPorts; p++) {
            if (u->inputs[p].num_sources > 0 ||
                u->inputs[p].is_smoothed ||
                u->inputs[p].constant != 0.0f) {
                port_prepare(&u->inputs[p], num_frames, sr);
            }
        }

        // Dispatch to type-specific process
        switch (u->type) {
            case UNIT_TRIANGLE_OSC:
                unit_process_triangle_osc(u, num_frames, sr); break;
            case UNIT_SQUARE_OSC:
                unit_process_square_osc(u, num_frames, sr); break;
            case UNIT_MULTIPLY:
                unit_process_multiply(u, num_frames); break;
            case UNIT_ADD:
                unit_process_add(u, num_frames); break;
            case UNIT_MULTIPLY_ADD:
                unit_process_multiply_add(u, num_frames); break;
            case UNIT_ENVELOPE:
                unit_process_envelope(u, num_frames); break;
            case UNIT_LINEAR_RAMP:
                unit_process_linear_ramp(u, num_frames, sr); break;
            case UNIT_PASS_THROUGH:
                unit_process_pass_through(u, num_frames); break;
            case UNIT_PEAK_FOLLOWER:
                unit_process_peak_follower(u, num_frames); break;
            case UNIT_HARD_CLIP:
                unit_process_hard_clip(u, num_frames); break;
            case UNIT_LIMITER:
                unit_process_limiter(u, num_frames); break;
            case UNIT_DELAY_LINE:
                unit_process_delay_line(u, num_frames, sr); break;
            case UNIT_MASTER_OUT:
                unit_process_master_out(u, output_buffer, num_frames); break;
            // Plaits, Clouds, Rings, Warps, Reverb handled in Task 7
            default: break;
        }
    }
}

// ── Port routing via hash table ─────────────────

static uint16_t hash16(const char* str) {
    uint16_t h = 0;
    while (*str) {
        h = h * 31 + static_cast<uint16_t>(*str);
        str++;
    }
    return h;
}

void orpheus_graph_set_port(OrpheusGraph* graph,
                            uint16_t uri_hash, uint16_t symbol_hash,
                            float value) {
    for (int i = 0; i < graph->port_map_count; i++) {
        auto& e = graph->port_map[i];
        if (e.uri_hash == uri_hash && e.symbol_hash == symbol_hash) {
            if (e.unit_id < graph->unit_count && e.port < kMaxInputPorts) {
                graph->units[e.unit_id].inputs[e.port].constant = value;
            }
            return;
        }
    }
}

void orpheus_graph_set_unit_port(OrpheusGraph* graph,
                                 uint16_t unit_id, uint16_t port,
                                 float value) {
    if (unit_id < graph->unit_count && port < kMaxInputPorts) {
        graph->units[unit_id].inputs[port].constant = value;
    }
}
```

**Step 2: Build**

Run: `./gradlew :apps:androidApp:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(graph): Add graph process loop and port routing"
```

---

## Task 6: MI Module Wrappers (Plaits, Clouds, Rings, Warps)

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Modify: `liborpheus_dsp/src/orpheus_units.h`
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp` (process dispatch)

These wrappers call the existing MI processor code already in `OrpheusEngine`. The graph units hold an index into the engine's processor arrays.

**Step 1: Add wrapper process functions to `orpheus_units.h`**

```cpp
// MI module wrappers — need OrpheusEngine pointer for processor access
struct OrpheusEngine; // forward declaration
void unit_process_plaits(GraphUnit* u, OrpheusEngine* engine,
                         int num_frames, float sample_rate);
void unit_process_clouds(GraphUnit* u, OrpheusEngine* engine,
                         int num_frames, float sample_rate);
void unit_process_rings(GraphUnit* u, OrpheusEngine* engine,
                        int num_frames, float sample_rate);
void unit_process_warps(GraphUnit* u, OrpheusEngine* engine,
                        int num_frames, float sample_rate);
```

**Step 2: Implement wrappers in `orpheus_units.cpp`**

The Plaits wrapper reads from VoiceParams atomics (same as current hardcoded code) and writes to the unit's output buffer. Clouds/Rings/Warps read from their input ports and write to output ports, delegating to the MI processors.

This is the largest single step — port the existing render code from `orpheus_engine.cpp` into per-unit process functions. The key difference: instead of writing directly to the interleaved output buffer, each wrapper writes to its `output_buffers[OPORT_OUT]` (and `OPORT_OUT_RIGHT` for stereo units).

**Step 3: Add MI wrapper dispatch cases to `orpheus_graph_process`**

The graph process function needs access to `OrpheusEngine*` for MI processors. Change the signature to pass it:

```cpp
void orpheus_graph_process(OrpheusGraph* graph, OrpheusEngine* engine,
                           float* output_buffer, int num_frames);
```

Add cases:
```cpp
case UNIT_PLAITS:
    unit_process_plaits(u, engine, num_frames, sr); break;
case UNIT_CLOUDS:
    unit_process_clouds(u, engine, num_frames, sr); break;
case UNIT_RINGS:
    unit_process_rings(u, engine, num_frames, sr); break;
case UNIT_WARPS:
    unit_process_warps(u, engine, num_frames, sr); break;
```

**Step 4: Build and commit**

```bash
git commit -m "feat(graph): Add MI module wrappers (Plaits, Clouds, Rings, Warps)"
```

---

## Task 7: Wire Graph into OrpheusEngine

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h` — add `OrpheusGraph*` member
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp` — replace `orpheus_engine_process` body with graph dispatch, update `orpheus_engine_set_port` to use graph hash routing
- Modify: `liborpheus_dsp/include/orpheus_dsp.h` — update `orpheus_engine_load_patch` declaration if needed

**Step 1: Add graph to engine**

In `orpheus_engine.h`, add:
```cpp
#include "orpheus_graph.h"

struct OrpheusEngine {
    // ... existing fields ...
    OrpheusGraph* graph;  // null until loadGraph called
};
```

**Step 2: Implement `orpheus_engine_load_patch` to parse graph**

```cpp
int orpheus_engine_load_patch(OrpheusEngine* engine,
                              const uint8_t* descriptor, size_t length) {
    auto* new_graph = new OrpheusGraph();
    int result = orpheus_graph_load(new_graph, descriptor, length,
                                    engine->sample_rate);
    if (result != 0) {
        delete new_graph;
        return result;
    }
    auto* old = engine->graph;
    engine->graph = new_graph;
    delete old;
    return 0;
}
```

**Step 3: Replace `orpheus_engine_process` body**

```cpp
void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames) {
    if (!engine || !output_buffer || num_frames <= 0) return;
    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));

    if (engine->graph) {
        orpheus_graph_process(engine->graph, engine,
                              output_buffer, num_frames);
    }
    // else: silence (no graph loaded yet)

    // Peak monitoring (unchanged)
    float pk_l = 0.0f, pk_r = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float l = std::fabs(output_buffer[i * 2]);
        float r = std::fabs(output_buffer[i * 2 + 1]);
        if (l > pk_l) pk_l = l;
        if (r > pk_r) pk_r = r;
    }
    engine->peak_left.store(pk_l);
    engine->peak_right.store(pk_r);
}
```

**Step 4: Update `orpheus_engine_set_port` to use graph hash routing**

```cpp
void orpheus_engine_set_port(OrpheusEngine* engine,
                             const char* plugin_uri,
                             const char* symbol,
                             float value) {
    if (engine->graph) {
        uint16_t uh = hash16(plugin_uri);
        uint16_t sh = hash16(symbol);
        orpheus_graph_set_port(engine->graph, uh, sh, value);
    }
}
```

**Step 5: Build, install, verify silence (no graph loaded yet)**

Run: `./gradlew :apps:androidApp:installDebug`
Expected: App runs, no sound (graph is null). No crashes.

**Step 6: Commit**

```bash
git commit -m "feat(graph): Wire graph runtime into OrpheusEngine process loop"
```

---

## Task 8: JNI Bridge for nativeLoadGraph

**Files:**
- Modify: `core/audio/src/androidMain/kotlin/org/balch/orpheus/core/audio/dsp/OboeAudioBridge.kt`
- Modify: `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/NativeDspBridge.kt`
- Modify: `core/audio/src/androidMain/kotlin/org/balch/orpheus/core/audio/dsp/OboeAudioEngine.kt`
- Modify: `apps/androidApp/src/main/cpp/jni_bridge.cpp`
- Modify: `apps/androidApp/src/main/cpp/OboeEngine.h`
- Modify: `apps/androidApp/src/main/cpp/OboeEngine.cpp`

**Step 1: Add Kotlin external declaration**

`OboeAudioBridge.kt`: Add `external fun nativeLoadGraph(data: ByteArray): Int`

**Step 2: Add to NativeDspBridge interface**

`NativeDspBridge.kt`: Add `fun nativeLoadGraph(data: ByteArray): Int`

**Step 3: Add to OboeAudioEngine**

`OboeAudioEngine.kt`: Add `override fun nativeLoadGraph(data: ByteArray): Int = bridge.nativeLoadGraph(data)`

**Step 4: Add JNI function**

`jni_bridge.cpp`:
```cpp
JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeLoadGraph(
        JNIEnv *env, jobject thiz, jbyteArray data) {
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    jsize length = env->GetArrayLength(data);
    int result = sEngine.loadGraph(
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return result;
}
```

**Step 5: Add OboeEngine methods**

`OboeEngine.h`: `int loadGraph(const uint8_t* data, size_t length);`
`OboeEngine.cpp`: Delegate to `orpheus_engine_load_patch(dsp_engine_, data, length);`

**Step 6: Build and commit**

```bash
git commit -m "feat(graph): Add nativeLoadGraph JNI bridge"
```

---

## Task 9: Kotlin WiringGraph DSL

**Files:**
- Create: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/WiringGraphDsl.kt`

**Step 1: Implement the DSL builder and binary serializer**

This is the Kotlin-side graph builder. It provides a type-safe DSL for defining units, connections, and the port map. On `serialize()`, it emits the binary descriptor format.

Key classes:
- `WiringGraphBuilder` — top-level builder with `triangleOsc()`, `multiply()`, etc.
- `UnitRef` — returned by each builder method, holds id and port accessors (`.out`, `.inputA`, etc.)
- `PortRef` — wraps (unit_id, port_enum) for connection wiring
- `infix fun PortRef.to(other: PortRef)` — records a connection

The `serialize()` method writes the header, units, connections, and port map in the binary format specified in the design doc.

The `portMap { }` block registers `(uri, symbol) → (unit_id, port)` entries. The DSL computes the 16-bit hashes using the same `hash16` function as C++.

**Step 2: Build**

Run: `./gradlew :core:foundation:build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git commit -m "feat(graph): Add Kotlin WiringGraph DSL and binary serializer"
```

---

## Task 10: Default Wiring Graph (12-voice + effects)

**Files:**
- Create: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`

**Step 1: Build the default graph using the DSL**

This file defines a function `buildDefaultWiringGraph(): ByteArray` that constructs the full 12-voice graph with effects chain matching the Kotlin DspVoice + DspWiringGraph topology.

Per voice (×12):
- Triangle + square oscillators with sharpness crossfade
- Plaits unit (source switching via oscGain/plaitsGain/sourceSelector)
- ADSR envelope + hold ramp → VCA control mixer → VCA
- Wobble gain + volume gain
- FM path: feedback scaler + FM depth + FM freq mixer
- Frequency path: pitch scaler → vibrato → bender → coupling → FM freq mixer
- Per-voice pan (multiply units for L/R gains)

Effects chain:
- Drive (limiter with dry/wet)
- Clouds (grains), Rings (resonator), Warps
- Delay (circular buffer with feedback)
- Master pan + master volume + hard clip
- Master out

Port map entries for all existing nativeSetPort routes.

**Step 2: Wire into DspSynthEngine**

Modify `DspSynthEngine` to call `nativeLoadGraph(buildDefaultWiringGraph())` after the native engine starts.

**Step 3: Build, install, test**

Run: `./gradlew :apps:androidApp:installDebug`
Expected: Sound plays through the graph. Voices respond to gate/tune/engine changes. Drive, delay, pan knobs work.

**Step 4: Commit**

```bash
git commit -m "feat(graph): Add default 12-voice wiring graph with effects chain"
```

---

## Task 11: Verify Engine 0 and Quad Holds

**Files:**
- Possibly modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspSynthEngine.kt`

**Step 1: Test engine 0 (oscillator mode)**

1. Launch app, select engine 0 from the engine picker
2. Tap pulse buttons — should hear triangle/square oscillator blend
3. Turn sharpness knob — should morph between triangle and square
4. Turn morph knob — should detune duo voices

**Step 2: Test quad holds**

1. Turn quad hold knob up from 0
2. Voices should sustain/drone without gate
3. Turn hold back to 0 — voices should silence

**Step 3: Test Plaits engines**

1. Switch to any Plaits engine (FM, String, etc.)
2. Verify sound matches previous behavior
3. Test all existing knobs (harmonics, timbre, morph, drive, delay, pan)

**Step 4: Fix any issues found, commit**

```bash
git commit -m "fix(graph): Address engine 0 and quad hold issues from testing"
```

---

## Summary

| Task | What | Files |
|------|------|-------|
| 1 | Data structures + enums | `orpheus_graph.h` |
| 2 | Unit process functions | `orpheus_units.h`, `orpheus_units.cpp` |
| 3 | Binary descriptor parser | `orpheus_graph.cpp` |
| 4 | Tarjan SCC + toposort | `orpheus_graph.cpp` |
| 5 | Graph process loop | `orpheus_graph.cpp` |
| 6 | MI module wrappers | `orpheus_units.cpp` |
| 7 | Wire into OrpheusEngine | `orpheus_engine.h`, `orpheus_engine.cpp` |
| 8 | JNI bridge | 6 files across JNI + Kotlin |
| 9 | Kotlin DSL | `WiringGraphDsl.kt` |
| 10 | Default graph | `DefaultWiringGraph.kt`, `DspSynthEngine.kt` |
| 11 | Verify engine 0 + quad holds | Testing + fixes |
