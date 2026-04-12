#pragma once

#include "orpheus_voice.h"
#include "tides2/poly_slope_generator.h"
#include "stmlib/dsp/dsp.h"
#include "stmlib/dsp/filter.h"
#include "frames/poly_lfo.h"

static constexpr int kNumPulsarTracks = 8;
static constexpr int kMaxPulsarSteps = 32;
static constexpr int kMaxSections = 8;
static constexpr int kMaxSectionTransitions = 8;
static constexpr int kMaxMotifs = 4;
static constexpr int kMarkovIntervals = 15;
static constexpr int kMaxSoloPhrase = 8;

struct PulsarStep {
    uint8_t note;      // MIDI note number (quantized to current scale)
    uint8_t raw_note;  // original unquantized note — re-quantize from this on scale change
    float velocity;    // 0.0-1.0
    bool gate;         // step active
    float duration;    // gate length as fraction of step (0.0-1.0)
    bool hold;         // if true, extend gate into next step (no retrigger)
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
    PulsarMacroTarget mood_harmonics;
    PulsarMacroTarget mood_timbre;
};

enum PulsarEnvelopeProfile : uint8_t {
    ENV_PROFILE_RHYTHM = 0,
    ENV_PROFILE_MELODIC = 1,
    ENV_PROFILE_EFFECT = 2,
    ENV_PROFILE_WILD = 3,
    ENV_PROFILE_DRONE = 4,
};

enum class BarStrategy : uint8_t {
    REPEAT = 0,
    MUTATE = 1,
    FILL = 2,
    CALL_RESPONSE = 3,
    INDEPENDENT = 4,
};

// Engine-type bus classification for DJ turntable source routing.
enum PulsarBusType : uint8_t {
    PULSAR_BUS_KEYS  = 0,  // melodic engines → warps_source_buffers[0] (SYNTH slot)
    PULSAR_BUS_DRUMS = 1,  // percussive engines → warps_source_buffers[1] (DRUMS slot)
    PULSAR_BUS_BASS  = 2,  // bass engines → warps_source_buffers[9] (BASS slot)
};

// Classification table: Plaits engine ID → bus type.
// 24 engines total (see kOrpheusOutGain in orpheus_voice.h for IDs).
static constexpr PulsarBusType kEngineBusType[24] = {
    PULSAR_BUS_BASS,   //  0: VirtualAnalogVCF — bass-oriented filter sweep
    PULSAR_BUS_KEYS,   //  1: PhaseDistortion — melodic
    PULSAR_BUS_KEYS,   //  2: SixOp FM1 — melodic
    PULSAR_BUS_KEYS,   //  3: SixOp FM2 — melodic
    PULSAR_BUS_KEYS,   //  4: SixOp FM3 — melodic
    PULSAR_BUS_KEYS,   //  5: WaveTerrain — melodic
    PULSAR_BUS_KEYS,   //  6: StringMachine — melodic
    PULSAR_BUS_KEYS,   //  7: Chiptune — melodic
    PULSAR_BUS_KEYS,   //  8: VirtualAnalog — melodic
    PULSAR_BUS_KEYS,   //  9: Waveshaping — melodic
    PULSAR_BUS_KEYS,   // 10: FM — melodic
    PULSAR_BUS_KEYS,   // 11: Grain — melodic/textural
    PULSAR_BUS_KEYS,   // 12: Additive — melodic
    PULSAR_BUS_KEYS,   // 13: Wavetable — melodic
    PULSAR_BUS_KEYS,   // 14: Chord — melodic
    PULSAR_BUS_KEYS,   // 15: Speech — melodic/vocal
    PULSAR_BUS_KEYS,   // 16: Swarm — melodic/textural
    PULSAR_BUS_DRUMS,  // 17: Noise — percussive
    PULSAR_BUS_DRUMS,  // 18: Particle — percussive
    PULSAR_BUS_KEYS,   // 19: String — melodic
    PULSAR_BUS_DRUMS,  // 20: Modal — percussive (tuned percussion)
    PULSAR_BUS_DRUMS,  // 21: BassDrum — percussive
    PULSAR_BUS_DRUMS,  // 22: SnareDrum — percussive
    PULSAR_BUS_DRUMS,  // 23: HiHat — percussive
};

struct PulsarGenreProfile {
    float base_density[8];
    float swing_amount;
    float ghost_probability;
    uint8_t note_range_low;
    uint8_t note_range_high;
    float rhythm_density;    // 0.0=sparse .. 1.0=dense 16th (continuous)
    uint8_t progression_style;  // ProgressionStyle enum
    uint8_t chords_per_bar;     // how many chord changes per bar (1-8)
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
    {7, {0,2,3,5,7,9,10,0,0,0,0,0}},               // 6: Dorian
    {7, {0,2,4,6,7,9,11,0,0,0,0,0}},               // 7: Lydian
    {7, {0,2,4,5,7,9,10,0,0,0,0,0}},               // 8: Mixolydian
    {7, {0,2,3,5,7,8,11,0,0,0,0,0}},               // 9: Harmonic Minor
    {5, {0,3,5,7,10,0,0,0,0,0,0,0}},               // 10: Minor Pentatonic
    {5, {0,2,3,7,8,0,0,0,0,0,0,0}},                // 11: Hirajoshi
    {5, {0,1,5,7,10,0,0,0,0,0,0,0}},               // 12: In Sen
};
static constexpr int kNumPulsarScales = 13;

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

    // Drone envelope (PolyLfo-based, bypasses broken Tides in heap)
    frames::PolyLfo drone_lfo;
    bool drone_lfo_initialized;

    // Pitch glide (portamento)
    float current_pitch;     // smoothed pitch for rendering
    float target_pitch;      // target from current step
    float glide_rate;        // per-sample pitch change (MIDI notes/sample)
    bool prev_step_gated;    // was the previous step also gated
    BarStrategy bar_strategy;

    // Solo/ducking state (applied per-frame)
    float solo_volume_mod = 0.0f;
    float solo_density_mod = 0.0f;
    float solo_ghost_mod = 0.0f;
    float solo_fill_mod = 0.0f;
    bool  solo_simplify = false;
    float solo_reverb_mod = 0.0f;
    bool  is_soloist = false;

    // Mod LFO: per-track modulation for TEXTURE/FX (tracks 5-7)
    frames::PolyLfo mod_poly_lfo;
    tides::PolySlopeGenerator mod_slope;
    float mod_lfo_output[4];  // [0]=timbre, [1]=morph, [2]=harmonics, [3]=pitch
    bool mod_lfo_initialized;

    // Hold step state
    bool in_hold;
    int hold_steps_remaining;

    // Reverb send brightness filter (one-pole LP per channel)
    float reverb_send_filter_state_l = 0.0f;
    float reverb_send_filter_state_r = 0.0f;
};

// ── Lick step (mirrors OrpheusEngine::LickStepAtomic layout) ────────────
static constexpr int kMaxLickSteps = 32;
static constexpr int kMaxProgressionLength = 8;

struct PulsarLickStep {
    int8_t scale_degree;
    float duration;
    float velocity;
};

struct PulsarChordState {
    int8_t  progression[kMaxProgressionLength];
    int     progression_length;
    int     chord_index;
    int     chord_step_counter;
    int     steps_per_chord;
    int     matrix_index;
    uint32_t chord_seed;
    // Custom per-vibe transition matrix (overrides built-in when active)
    bool    use_custom_matrix = false;
    float   custom_matrix[7][7] = {};
};

// ── Tension system parameters (loaded from engine atomics on vibe reload) ──
struct TensionParams {
    int inner_bars = 4;
    int outer_bars = 0;
    float outer_depth = 0.5f;
    float volume = 0.3f;
    float timing = 0.2f;
    bool octave_shift = false;
    int key_shift = 0;
    bool half_lick = false;
    float chromatic_passing = 0.0f;
    float evo_timbre_low = 0.25f, evo_timbre_high = 0.55f, evo_timbre_prob = 0.7f;
    float evo_morph_low = -1.0f, evo_morph_high = -1.0f, evo_morph_prob = 0.5f;
    float evo_harm_low = -1.0f, evo_harm_high = -1.0f, evo_harm_prob = 0.3f;
    float evo_attack_point = 0.5f;
    float evo_release_speed = 0.3f;
    float track_evo_weight[8] = {-1,-1,-1,-1,-1,-1,-1,-1};
};

// ── Section system parameters ───────────────────────────────────────

enum class SoloMode : uint8_t { ROUND_ROBIN = 0, EXTENDED = 1, IMPROVISERS = 2 };
enum class SelectionMode : uint8_t { FIXED_ORDER = 0, MARKOV = 1, WEIGHTED_RANDOM = 2 };

struct SectionTransitionParam {
    int target_index;
    float weight;
};

struct MacroOverridesParam {
    float energy = -1.0f;
    float complexity = -1.0f;
    float space = -1.0f;
    float mood = -1.0f;
};

struct SoloistSelectionParam {
    SelectionMode mode = SelectionMode::WEIGHTED_RANDOM;
    int order[kNumPulsarTracks] = {};
    int order_count = 0;
    float weights[kNumPulsarTracks] = {};
    float recency_decay = 0.5f;
    bool allow_effects = true;
};

struct SoloBehaviorParam {
    float volume_boost = 0.2f;
    float density_boost = 0.3f;
    float timbre_min = 0.2f, timbre_max = 0.8f;
    float morph_min = 0.1f, morph_max = 0.7f;
    float harmonics_min = 0.2f, harmonics_max = 0.8f;
    float evolution_intensity = 1.0f;
    float fill_probability = 0.6f;
    float interval_weights[kMarkovIntervals] = {};
    float rest_probability = 0.15f;
    float hold_probability = 0.2f;
    float density_curve_min = 0.4f, density_curve_max = 0.8f;
    float rhythm_variation = 0.3f;
    float chromatic_passing = 0.1f;
    float density_curve_shape = 0.0f;   // -1.0=front-loaded, 0.0=linear, +1.0=back-loaded
    float phrase_length_curve = 0.0f;   // -1.0=shorten over section, +1.0=lengthen
    float lick_gravity = 0.5f;
    int phrase_length_min = 2, phrase_length_max = 4;
    float reentry_probability = 0.4f;
    int override_engine = -1;
    int override_octave_shift = 0;
    int8_t last_interval = 0;         // previous interval for second-order Markov
    PulsarEnvelopeProfile profile = ENV_PROFILE_MELODIC;  // for matrix lookup
};

struct DuckingParam {
    float volume_reduction = 0.3f;
    float density_reduction = 0.4f;
    float ghost_reduction = 0.5f;
    float fill_suppression = 0.8f;
    bool simplify = true;
    float reverb_boost = 0.1f;
};

struct SoloConfigParam {
    SoloMode mode = SoloMode::ROUND_ROBIN;
    float probability = 0.5f;
    SoloistSelectionParam soloist_selection;
    int bars_per_soloist_min = 2, bars_per_soloist_max = 4;
    int transition_bars = 0;
    float improv_carryover = 0.7f;
    // Markov soloist handoff matrix (8x8, row=from, col=to)
    bool has_soloist_matrix = false;
    float soloist_matrix[kNumPulsarTracks][kNumPulsarTracks] = {};
};

struct MotifParam {
    float track_densities[kNumPulsarTracks] = {};
    bool track_density_active[kNumPulsarTracks] = {};
    float swing_override = -1.0f;
    float ghost_probability = -1.0f;
    float rhythm_density = -1.0f;
};

struct MotifSetParam {
    MotifParam motifs[kMaxMotifs];
    int motif_count = 0;
    float transition_weights[kMaxMotifs][kMaxMotifs] = {};
    float recency_decay = 0.5f;
    int bars_per_motif_min = 4, bars_per_motif_max = 8;
    float switch_probability = 0.6f;
};

struct SectionParam {
    int bars_min = 4, bars_max = 8;
    SectionTransitionParam transitions[kMaxSectionTransitions];
    int transition_count = 0;
    int transition_bars = 1;
    float recency_decay = 0.5f;
    MacroOverridesParam macro_overrides;
    bool has_tension_override = false;
    TensionParams tension_override;
    bool has_solo = false;
    SoloConfigParam solo_config;
    bool has_motif_set = false;
    MotifSetParam motif_set;
};

struct ArrangementParams {
    SectionParam sections[kMaxSections];
    int section_count = 0;
    int intro_index = -1;
    int outro_index = -1;
    int default_section_bars = 8;
    bool active = false;
};

// ── Runtime state machines ──────────────────────────────────────────

struct SectionState {
    int current_section = 0;
    int bars_remaining = 0;
    int bars_since_visit[kMaxSections] = {};
    float transition_progress = 0.0f;
    int transition_target = -1;
    bool intro_done = false;
    bool outro_triggered = false;
    float target_energy = -1.0f, target_complexity = -1.0f;
    float target_space = -1.0f, target_mood = -1.0f;
};

struct SoloState {
    bool active = false;
    int current_soloist = -1;
    int bars_remaining = 0;
    SoloMode mode = SoloMode::ROUND_ROBIN;
    int8_t last_phrase[kMaxSoloPhrase] = {};
    int phrase_cursor = 0;
    int markov_state = 0;
    int soloists_visited = 0;
    int bars_since_soloist[kNumPulsarTracks] = {};
    int order_cursor = 0;
};

// ── Band-based solo system ──────────────────────────────────────────
static constexpr int kMaxBandMembers = 8;

enum class SoloTechniqueId : uint8_t {
    STANDARD_MARKOV = 0,
    LICK_MELODY     = 1,
    LICK_RHYTHM     = 2,
    LICK_CONTOUR    = 3,
    FILL_BUILDER    = 4,
    MARKOV_CONTOUR  = 5,  // opt-in: regenerate patterns via second-order Markov
};

enum class MemberSoloRole : uint8_t {
    SUPPORT  = 0,
    ACTIVE   = 1,
    LEADING  = 2,
};

struct BandMemberParam {
    int tracks[kNumPulsarTracks] = {};
    int track_count = 0;
    bool always_active = false;
    SoloTechniqueId default_technique = SoloTechniqueId::STANDARD_MARKOV;
};

struct BandSoloConfigParam {
    int member_count = 0;
    BandMemberParam members[kMaxBandMembers];
    float handoff_matrix[kMaxBandMembers * kMaxBandMembers] = {};
    float pull_in_matrix[kMaxBandMembers * kMaxBandMembers] = {};
    int pull_in_bars_min = 2, pull_in_bars_max = 4;
    float improv_carryover = 0.7f;
    float probability = 0.7f;
    int bars_per_lead_min = 2, bars_per_lead_max = 4;
};

struct BandSoloState {
    bool active = false;
    int lead_member = -1;
    MemberSoloRole member_role[kMaxBandMembers] = {};
    int member_bars_remaining[kMaxBandMembers] = {};
    int bars_since_lead[kMaxBandMembers] = {};
    SoloTechniqueId active_technique[kMaxBandMembers] = {};
    int8_t last_phrase[kMaxSoloPhrase] = {};
    int phrase_cursor = 0;
    uint32_t solo_seed = 0;
};

struct MotifState {
    int current_motif = 0;
    int bars_remaining = 0;
    int bars_since_motif[kMaxMotifs] = {};
};

// ── Persistent state (heap-allocated on first process call) ──────────────
static constexpr int kVoiceAllocBytes_Pulsar = 32768;

struct PulsarState {
    PulsarTrackState tracks[kNumPulsarTracks];
    double clock_accumulator;   // fractional sample counter for step grid
    int current_vibe_generation;
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

    // Lick state (copied from engine atomics on vibe load)
    int lick_length;      // actual step count (array bounds)
    int lick_loop_length; // beats; when > note duration sum, rest fills the gap
    PulsarLickStep lick[kMaxLickSteps];
    float lick_mutation;
    int lick_octave;  // -1 = auto (midpoint of noteRange), 0-8 = explicit MIDI octave

    // Chord progression state
    PulsarChordState chord_state;

    // Elastic tempo: slow random walk
    float tempo_drift;           // current tempo offset (-0.15 to +0.15)
    float tempo_drift_target;    // random walk target
    int tempo_drift_countdown;   // samples until next target change

    // Tension system
    TensionParams tension;
    float tension_intensity = 0.0f;
    float tension_evo_smooth = 0.0f;

    // Section / Solo / Motif system
    ArrangementParams arrangement;
    SectionState section_state;
    SoloState solo_state;
    MotifState motif_state;
    SoloBehaviorParam track_solo_behavior[kNumPulsarTracks];
    DuckingParam track_ducking[kNumPulsarTracks];

    // Band-based solo system (parallel to track-based solos)
    BandSoloState band_solo_state;
    BandSoloConfigParam band_solo_config;
    bool has_band_solo = false;

    // Arrangement read-back (written by audio thread, read by viz polling)
    // relaxed atomics: zero overhead on ARM/x86 for aligned ints, standards-compliant
    std::atomic<int> arr_viz_section_index{-1};    // -1 = inactive
    std::atomic<int> arr_viz_bars_elapsed{0};
    std::atomic<int> arr_viz_bars_total{0};
    std::atomic<bool> arr_viz_solo_active{false};
    std::atomic<int> arr_viz_solo_track{-1};
    std::atomic<int> arr_viz_solo_mode{0};

    // Output stage: sub-bass high-pass filter (55Hz, 24dB/oct Linkwitz-Riley)
    // Two cascaded SVF stages for steep rolloff
    stmlib::Svf output_hpf_l;
    stmlib::Svf output_hpf_r;
    stmlib::Svf output_hpf2_l;
    stmlib::Svf output_hpf2_r;
    bool output_hpf_initialized = false;
};

struct GraphUnit;

void unit_process_pulsar(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
