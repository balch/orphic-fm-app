#pragma once

#include "orpheus_voice.h"
#include "tides2/poly_slope_generator.h"
#include "stmlib/dsp/dsp.h"

static constexpr int kNumPulsarTracks = 8;
static constexpr int kMaxPulsarSteps = 32;

struct PulsarStep {
    uint8_t note;      // MIDI note number (quantized to current scale)
    uint8_t raw_note;  // original unquantized note — re-quantize from this on scale change
    float velocity;    // 0.0-1.0
    bool gate;         // step active
    float duration;    // gate length as fraction of step (0.0-1.0)
};

struct PulsarMacroTarget {
    float min_value;   // parameter value when macro = 0
    float max_value;   // parameter value when macro = 1
};

struct PulsarTrackMacroMap {
    PulsarMacroTarget energy_volume;
    PulsarMacroTarget energy_density;
    PulsarMacroTarget complexity_swing;
    PulsarMacroTarget complexity_variation;
    PulsarMacroTarget space_decay;
    PulsarMacroTarget space_reverb_send;
    PulsarMacroTarget mood_harmonics;
    PulsarMacroTarget mood_timbre;
};

enum PulsarEnvelopeProfile : uint8_t {
    ENV_PROFILE_RHYTHM = 0,
    ENV_PROFILE_MELODIC = 1,
    ENV_PROFILE_EFFECT = 2,
    ENV_PROFILE_WILD = 3,
};

struct PulsarGenreProfile {
    float base_density[8];
    float swing_amount;
    float ghost_probability;
    uint8_t note_range_low;
    uint8_t note_range_high;
    uint8_t rhythm_pattern;  // 0=sparse, 1=four-on-floor, 2=backbeat-heavy, 3=dense-16th
};

struct PulsarTrackPreset {
    int engine_edm;        // engine for high-energy
    int engine_space;      // engine for low-energy
    int step_count;
    float volume;
    float pan;
    float harmonics;
    float timbre;
    float morph;
    PulsarTrackMacroMap macro_map;
    PulsarEnvelopeProfile envelope_profile;
};

struct PulsarScale {
    int count;
    uint8_t degrees[12];
};

static const PulsarScale kPulsarScales[] = {
    {7, {0,2,3,5,7,8,10,0,0,0,0,0}},               // 0: Minor
    {7, {0,2,4,5,7,9,11,0,0,0,0,0}},               // 1: Major
    {5, {0,2,4,7,9,0,0,0,0,0,0,0}},                // 2: Pentatonic
    {7, {0,1,3,5,7,8,10,0,0,0,0,0}},               // 3: Phrygian
    {6, {0,2,4,6,8,10,0,0,0,0,0,0}},               // 4: Whole Tone
    {12, {0,1,2,3,4,5,6,7,8,9,10,11}},             // 5: Chromatic
};
static constexpr int kNumPulsarScales = 6;

struct PulsarScenePreset {
    float default_bpm;
    uint8_t root_note;     // 0=C, 1=C#, ... 11=B
    uint8_t scale_index;   // index into kPulsarScales
    PulsarTrackPreset tracks[kNumPulsarTracks];
    PulsarGenreProfile genre;
};

enum PulsarEnvPhase { ENV_IDLE, ENV_ATTACK, ENV_SUSTAIN, ENV_DECAY };

struct PulsarTrackState {
    OrpheusVoice voice;
    PulsarStep steps[kMaxPulsarSteps];
    int step_count;
    int playhead;
    int engine_index;
    float volume;
    float pan;
    float harmonics, timbre, morph;
    float gate_timer;
    bool voice_active;
    PulsarTrackMacroMap macro_map;

    // Swing: accumulated offset in samples for odd steps
    double swing_offset;

    // Tides envelope
    tides::PolySlopeGenerator tides_env;
    stmlib::GateFlags tides_prev_gate;
    float tides_env_level;
    PulsarEnvelopeProfile envelope_profile;

    // Pitch glide (portamento)
    float current_pitch;     // smoothed pitch for rendering
    float target_pitch;      // target from current step
    float glide_rate;        // per-sample pitch change (MIDI notes/sample)
    bool prev_step_gated;    // was the previous step also gated
};

// ---------------------------------------------------------------------------
// Scene Presets
// ---------------------------------------------------------------------------

// Helper: inactive step
#define _PS0 {0, 0.0f, false, 0.0f}
// Helper: active step (note, velocity, duration)
#define _PS(n,v,d) {n, v, true, d}

#include "pulsar_scene_deep_space.h"
#include "pulsar_scene_chillwave.h"
#include "pulsar_scene_cosmic_techno.h"
#include "pulsar_scene_dog_house.h"
#include "pulsar_scene_artemis2.h"

static constexpr int kNumPulsarScenes = 5;
static const PulsarScenePreset kPulsarScenes[kNumPulsarScenes] = {
    kPulsarSceneDeepSpace,
    kPulsarSceneChillwave,
    kPulsarSceneCosmicTechno,
    kPulsarSceneDogHouse,
    kPulsarSceneArtemis2,
};

#undef _PS0
#undef _PS

// ── Persistent state (heap-allocated on first process call) ──────────────
static constexpr int kVoiceAllocBytes_Pulsar = 32768;

struct PulsarState {
    PulsarTrackState tracks[kNumPulsarTracks];
    double clock_accumulator;   // fractional sample counter for step grid
    int current_scene;
    bool initialized;
    float smooth_energy, smooth_complexity, smooth_space, smooth_mood;

    // Per-voice allocation buffers for OrpheusVoice::Init
    uint8_t voice_alloc_buffers[kNumPulsarTracks][kVoiceAllocBytes_Pulsar];

    // Mutation state — patterns evolve over time
    uint32_t seed_counter;      // incremented each scene load for varied seeds
    uint32_t mutation_seed;     // PRNG state for pattern mutation
    int loop_count;             // how many full loops completed
    int loops_since_reset;      // loops since last déjà vu reset

    // Drunk timing: per-step random offsets (in samples)
    float drunk_offsets[kNumPulsarTracks][kMaxPulsarSteps];
    float drunk_targets[kNumPulsarTracks][kMaxPulsarSteps];

    // Live root/scale tracking — re-quantize melodic notes on change
    int last_root_note;
    int last_scale_index;

    // Elastic tempo: slow random walk
    float tempo_drift;           // current tempo offset (-0.15 to +0.15)
    float tempo_drift_target;    // random walk target
    int tempo_drift_countdown;   // samples until next target change
};

// Forward declaration
struct OrpheusEngine;
struct GraphUnit;

void unit_process_pulsar(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
