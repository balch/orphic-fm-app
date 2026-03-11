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
    UNIT_DUAL_DELAY = 18,
    UNIT_HYPER_LFO = 19,
    UNIT_CLOCK = 20,
    UNIT_GRIDS = 21,
    UNIT_MARBLES = 22,
    UNIT_LOOPER = 23,
    UNIT_BENDER = 24,
    UNIT_PER_STRING_BENDER = 25,
    UNIT_DUO_VOICE = 26,
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
    PARAM_MODULE_INDEX = 13,
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
void orpheus_graph_process(OrpheusGraph* graph, struct OrpheusEngine* engine,
                           float* output_buffer, int num_frames);

// Set a port value by URI+symbol hash lookup.
void orpheus_graph_set_port(OrpheusGraph* graph,
                            uint16_t uri_hash, uint16_t symbol_hash,
                            float value);

// Set a unit's input port constant directly by unit_id + port index.
void orpheus_graph_set_unit_port(OrpheusGraph* graph,
                                 uint16_t unit_id, uint16_t port,
                                 float value);
