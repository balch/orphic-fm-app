#pragma once

#include "pulsar_limits.h"
#include "orpheus_voice.h"
#include "orpheus_unit_chaos.h"
#include "pulsar_void.h"
#include "orpheus_wah_core.h"
#include "tides2/poly_slope_generator.h"
#include "stmlib/dsp/dsp.h"
#include "stmlib/dsp/filter.h"
#include "frames/poly_lfo.h"

static constexpr int kMaxPulsarSteps = 64;
static constexpr int kMarkovIntervals = 15;
static constexpr int kMaxSoloPhrase = 8;
// Track 3 is hard-wired to the bass bus in every scene regardless of engine (see the
// PULSAR_BUS_BASS branch in orpheus_unit_pulsar.cpp). Header-scoped so the wah-anomaly
// eligibility predicate below and the C++ test harness can both see it.
static constexpr int kBassTrack = 3;  // tracks 3=BASS (pulsar_pattern_gen.h:323)

// Per-bar slew rate for solo level/density crossfades (handoff crossfade +
// solo-end fade-out share it). Hoisted here so tests can pin the crossfade
// contract, same as kBassTrack. A full swing must take multiple bars — see
// test_solo_mod_slew_produces_intermediate_values.
static constexpr float kSoloModSlew = 0.15f;

struct PulsarStep {
    uint8_t note;      // MIDI note number (quantized to current scale)
    uint8_t raw_note;  // original unquantized note — re-quantize from this on scale change
    float velocity;    // 0.0-1.0
    bool gate;         // step active
    float duration;    // gate length as fraction of step (0.0-1.0)
    bool hold;         // if true, extend gate into next step (no retrigger)
    float glide_rate;  // -1 = use track default; >= 0 = per-step override (set by lick)
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

enum class TrackRole : uint8_t {
    PERCUSSIVE = 0,
    MELODIC = 1,
    CHORDAL = 2,
};

enum class LickMode : uint8_t {
    NONE = 0,
    SQUASH = 1,
    FILL = 2,
};

enum class NoteFollowMode : uint8_t {
    SLIDE = 0,
    CONTOUR = 1,
    BLEND = 2,
};

enum class PitchEvoMode : uint8_t {
    NONE = 0,
    CONTOUR = 1,   // Markov
    VOICING = 2,   // Chord inversion/substitution
};

enum class CompingStyleId : uint8_t {
    PAD = 0,
    FUNK_STABS = 1,
    ROCK_DOWNBEATS = 2,
    CUSTOM = 3,           // reserved; not yet supported
    SKA_UPSTROKES = 4,
    BLUES_SHUFFLE = 5,
    JAZZ_COMP = 6,
    REGGAE_SKANK = 7,
    GOSPEL_STABS = 8,
};

enum class ArpModeId : uint8_t { AUTO = 0, ALWAYS = 1, NEVER = 2 };
enum class ArpDirectionId : uint8_t { UP = 0, DOWN = 1, UP_DOWN = 2, RANDOM = 3 };

enum class FillTypeId : uint8_t {
    NONE = 0,
    ASCENDING_ARP = 1,
    DESCENDING_ARP = 2,
    TURNAROUND = 3,
    DOUBLE_TIME = 4,
    STAB_FLURRY = 5,
    DROP_OUT = 6,
};

enum class ChordFollowMode : uint8_t {
    FOLLOW = 0,
    ROOT_ONLY = 1,
    FIXED = 2,
};

enum class SectionInversionId : uint8_t {
    FOLLOW_STYLE = 0,
    ROOT_POSITION = 1,
    FIRST_INVERSION = 2,
    SECOND_INVERSION = 3,
    OPEN_VOICING = 4,
};

enum class VoicingType : uint8_t {
    ROOT_ONLY = 0,
    ROOT_FIFTH = 1,
    TRIAD = 2,
    SEVENTH = 3,
    OCTAVE_STACK = 4,
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
    {6, {0,3,5,6,7,10,0,0,0,0,0,0}},               // 13: Blues (minor blues, hexatonic: R b3 4 b5 5 b7)
    {5, {0,3,5,6,10,0,0,0,0,0,0,0}},               // 14: Blues Pentatonic (minor penta + b5: R b3 4 b5 b7)
    {6, {0,2,3,4,7,9,0,0,0,0,0,0}},                // 15: Major Blues (major penta + b3: R 2 b3 3 5 6)
};
static constexpr int kNumPulsarScales = 16;
// Count and table must stay in lockstep — adding a scale row without bumping the
// count (or vice versa) is a silent bug that plays the wrong scale. Catch it here.
static_assert(kNumPulsarScales == sizeof(kPulsarScales) / sizeof(kPulsarScales[0]),
              "kNumPulsarScales must equal the number of kPulsarScales rows");

enum PulsarEnvPhase { ENV_IDLE, ENV_ATTACK, ENV_SUSTAIN, ENV_DECAY };

// How a FILL lick loops while truncated, and how its phase resolves on release.
// Mirrors `HalfLick` in TensionProfile.kt — the values are the wire encoding carried
// by tension slot 7, so do not reorder.
enum class HalfLickMode : int {
    OFF          = 0,  // no truncation; the lick plays its full length
    JAM          = 1,  // loop bar 1; on release the riff re-locks to bar 1
    JAM_INVERTED = 2,  // loop bar 1; on release spill into bar 2 and stay a bar out of
                       // phase until the next section boundary re-locks it
    JAM_LAST_BAR = 3,  // loop the LAST bar (the answer phrase) instead of the first;
                       // on release the riff re-locks to bar 1
};

struct PulsarTrackState {
    OrpheusVoice voice;
    // Per-track Braids macro oscillator. Used when engine_index >= 100.
    // Init'd in PulsarState init alongside `voice`.
    braids::MacroOscillator braids_voice;
    // Resampler residue carried across host blocks for the 96k→host linear-interp
    // path. See orpheus_engine.h::braids_src_phase for rationale.
    float braids_src_phase = 0.0f;
    // Per-track chaos voice trajectory. Used when engine_index is in
    // [kChaosEngineMin, kChaosEngineMax]. Default-initialized to the canonical
    // seed (x=0.1, y=0, z=0) so the first block evolves into the attractor.
    ChaosVoiceState chaos_state;
    PulsarStep steps[kMaxPulsarSteps];
    int step_count;
    int playhead;
    // Tension half-lick: when >0 this FILL lick loops only its first bar (the first
    // `half_loop_len` steps of the full step_count). 0 = not a FILL lick / no truncation.
    // Set at load_vibe for FILL melodic leads; gated at render time on tension.half_lick.
    int half_loop_len = 0;
    // Loop length the playhead is CURRENTLY traversing, latched at each wrap, plus the
    // half-lick mode that owned it. The effective length can change mid-loop (a section
    // flip runs during track 0's boundary, before this track advances), and wrapping
    // against the new length would strand the playhead past its old wrap point. 0 =
    // not yet latched; adopt the effective length on the next advance.
    int wrap_len = 0;
    // First step of that window. Non-zero only under JAM_LAST_BAR, which shifts the
    // window rather than shortening it.
    int wrap_start = 0;
    HalfLickMode wrap_mode = HalfLickMode::OFF;
    // Set when a JAM_INVERTED release deliberately left this track a bar out of phase.
    // Cleared by the section-boundary re-lock, which arms `resync_pending`.
    bool phase_inverted = false;
    // Armed at a section boundary; the track's next advance lands the playhead on 0.
    // Deferred rather than assigned directly because the section handler runs during
    // track 0's boundary, before this track has advanced for the same step.
    bool resync_pending = false;
    int engine_index;
    float volume;
    float pan;
    float harmonics, timbre, morph;
    // Effective pin flags resolved from per-slot atomics + DX engine enforcement.
    // True = bypass all modulation (lerp_macro, evolution, accent, LFO) for that param.
    bool pin_harmonics;
    bool pin_timbre;
    bool pin_morph;
    // When pin_harmonics is true, this is the LFO-swing depth applied around
    // the pinned harmonics value. 0 = fully pinned (no motion).
    float harmonics_modulation;
    // User-knob-driven patch walk on DX-family auto-pinned harmonics.
    //   harmonics_macro_source: which macro drives the walk (NONE/ENERGY/COMPLEXITY/SPACE/MOOD)
    //   harmonics_macro_range:  half-width of the swing around the pinned base; 0 = no walk
    int   harmonics_macro_source;
    float harmonics_macro_range;
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
    int last_chord_index = -1;   // track chord-change edges for per-chord glide
    BarStrategy bar_strategy;

    // Solo/ducking state (applied per-frame)
    float solo_volume_mod = 0.0f;
    float solo_density_mod = 0.0f;
    // Smoothed values that chase the role targets above, advanced once per bar.
    // The audio loop reads these (not the raw targets) so handoffs crossfade.
    float solo_volume_mod_current = 0.0f;
    float solo_density_mod_current = 0.0f;
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

    // Sub-block trigger timing (per-block scratch, audio thread only).
    // trigger_offset: sample index within the current block where this
    // track's step trigger landed (0 = block start / no mid-block trigger).
    // The render is split there so the onset lands on the true step boundary
    // instead of snapping up to a full block early.
    // gate_pre_boundary: voice_active before this block's boundaries were
    // processed — the first trigger_offset samples render with it so the
    // previous note's tail isn't replaced by an early onset.
    // pending_retrig: a step trigger requested an envelope rising edge; the
    // Tides gate is forced low at trigger_offset rather than at block start.
    int trigger_offset = 0;
    bool gate_pre_boundary = false;
    bool pending_retrig = false;

    // Reverb send brightness filter (one-pole LP per channel)
    float reverb_send_filter_state_l = 0.0f;
    float reverb_send_filter_state_r = 0.0f;

    // Track role (persists from load_vibe)
    TrackRole role = TrackRole::PERCUSSIVE;

    // Chord follow mode (persists from load_vibe)
    ChordFollowMode chord_follow = ChordFollowMode::FOLLOW;

    // LPG (low-pass gate) config (persists from load_vibe)
    // mode = LpgMode int from orpheus_voice.h. Default 3 = LPG_ENGINE_DEFAULT.
    // lpg_mode applies on the EDM engine slot; lpg_mode_space on the SPACE slot.
    int lpg_mode = 3;          // LPG_ENGINE_DEFAULT
    int lpg_mode_space = 3;    // LPG_ENGINE_DEFAULT (Kotlin pushes lpgModeSpace ?: lpgMode)
    float lpg_decay = 0.5f;
    float lpg_colour = 0.5f;

    // Comping config (only meaningful when role == CHORDAL)
    CompingStyleId comping_style = CompingStyleId::PAD;

    // Arpeggiator state (used for CHORDAL tracks; set by load_vibe)
    ArpModeId arp_mode = ArpModeId::AUTO;
    float arp_speed = 0.2f;
    ArpDirectionId arp_direction = ArpDirectionId::UP;
    SectionInversionId section_inversion = SectionInversionId::FOLLOW_STYLE;

    // Defaults snapshotted at load_vibe — used to restore when a section's
    // override goes back to "no override" (null → -1 in packed data).
    ChordFollowMode default_chord_follow = ChordFollowMode::FOLLOW;
    CompingStyleId default_comping_style = CompingStyleId::PAD;
    SectionInversionId default_section_inversion = SectionInversionId::FOLLOW_STYLE;
    ArpModeId default_arp_mode = ArpModeId::AUTO;
    // Runtime state (managed during playback — sub-task 2b.4)
    uint8_t arp_notes[4] = {};
    int arp_note_count = 0;
    int arp_index = 0;
    int64_t arp_next_sample = 0;

    // Humanization probabilities (CHORDAL only, loaded from atomics each bar)
    float human_drop_prob = 0.0f;
    float human_ghost_prob = 0.0f;
    float human_octave_prob = 0.0f;
    float human_ext_prob = 0.0f;
    // Fills (CHORDAL only, loaded from atomics on vibe load)
    int fill_every_n = 0;
    FillTypeId fill_type = FillTypeId::ASCENDING_ARP;
    float fill_skip_prob = 0.0f;
    int bars_since_fill = 0;
    // BASE cache for CHORDAL (restored each bar before humanization/fills)
    PulsarStep chordal_base[kMaxPulsarSteps];
    int chordal_base_count = 0;
    bool chordal_base_valid = false;

    // Evolution state (persists across bars)
    bool evo_rhythmic = false;
    float evo_tension_resp = 1.0f;
    NoteFollowMode evo_note_follow = NoteFollowMode::SLIDE;
    PitchEvoMode evo_pitch_mode = PitchEvoMode::NONE;
    float evo_voicing_tension = 1.0f;
    int anchor_indices[2] = {-1, -1};  // cached anchor positions
};

// The window of steps a track's playhead currently cycles: [start, start + len).
// Only half-lick modes produce a non-zero start.
struct PulsarLoopWindow {
    int start;
    int len;
};

// Effective sequencer loop window for a track, honoring the tension "half-lick" mode.
// When a mode is active AND this track carries a truncatable FILL lick (half_loop_len
// set to its first-bar length), the playhead cycles one bar of it so that figure
// repeats/jams. Otherwise the full pattern plays.
//
// JAM / JAM_INVERTED jam the FIRST bar (the hook). JAM_LAST_BAR jams the remainder —
// the riff's answer phrase — which is a different window, not a different length: it
// starts at half_loop_len rather than 0.
inline PulsarLoopWindow pulsar_effective_loop_window(const PulsarTrackState& ts,
                                                    HalfLickMode mode) {
    const bool truncatable =
        ts.half_loop_len > 0 && ts.half_loop_len < ts.step_count;
    if (!truncatable) return {0, ts.step_count};

    switch (mode) {
        case HalfLickMode::JAM:
        case HalfLickMode::JAM_INVERTED:
            return {0, ts.half_loop_len};
        case HalfLickMode::JAM_LAST_BAR:
            return {ts.half_loop_len, ts.step_count - ts.half_loop_len};
        case HalfLickMode::OFF:
        default:
            return {0, ts.step_count};
    }
}

// Length-only convenience retained for call sites that only care how long the loop is.
inline int pulsar_effective_loop_len(const PulsarTrackState& ts, HalfLickMode mode) {
    return pulsar_effective_loop_window(ts, mode).len;
}

// Advance one step boundary, wrapping against the loop the playhead is ACTUALLY
// traversing rather than whatever length is in effect right now.
//
// The two lengths differ for exactly one boundary whenever a section flip toggles
// half-lick, because the flip runs inside track 0's boundary handling while `t` is the
// outer render loop — track 0 clears the mode before this track advances for the same
// step. Wrapping against the restored length there leaves a truncated lead sitting on
// `half_loop_len` (bar 2) instead of 0, one bar out of phase with the chord grid for
// the rest of the song. Latching the length at each wrap makes the change take effect
// at a musical boundary, and also fixes the mirror case where half-lick ENGAGES
// mid-loop and would otherwise jump the playhead into the middle of bar 1.
//
// JAM_INVERTED opts into the un-wrapped behavior deliberately and marks the track so
// the next section boundary re-locks it.
//
// JAM_LAST_BAR shifts the window rather than shortening it, so entering and leaving it
// both need the playhead moved into range — a playhead sitting in bar 1 is outside a
// [half_loop_len, step_count) window and would otherwise never reach the wrap point.
inline void pulsar_advance_playhead(PulsarTrackState& ts, HalfLickMode mode) {
    const PulsarLoopWindow win = pulsar_effective_loop_window(ts, mode);
    const int wrap_at = win.start + win.len;

    if (ts.resync_pending) {
        ts.resync_pending = false;
        ts.playhead    = win.start;
        ts.wrap_start  = win.start;
        ts.wrap_len    = win.len;
        ts.wrap_mode   = mode;
        return;
    }
    if (ts.wrap_len <= 0) {           // first advance after load: adopt as-is
        ts.wrap_start = win.start;
        ts.wrap_len   = win.len;
        ts.wrap_mode  = mode;
    }

    // A window that no longer contains the playhead can only come from a mode change
    // that MOVED the window (to or from JAM_LAST_BAR). Snap in at its start; there is
    // no phase to preserve because the two windows share no steps.
    if (ts.wrap_start != win.start && ts.wrap_mode != mode) {
        ts.playhead   = win.start;
        ts.wrap_start = win.start;
        ts.wrap_len   = win.len;
        ts.wrap_mode  = mode;
        return;
    }

    ts.playhead++;
    if (ts.playhead < ts.wrap_start + ts.wrap_len) return;

    if (ts.wrap_mode == HalfLickMode::JAM_INVERTED && wrap_at > ts.wrap_start + ts.wrap_len) {
        // Deliberate inversion: the truncated loop just ended and the full lick is
        // back, so let the playhead run on into bar 2 instead of wrapping. The riff
        // states its answer phrase first and stays inverted until the re-lock.
        ts.wrap_start     = win.start;
        ts.wrap_len       = win.len;
        ts.wrap_mode      = mode;
        ts.phase_inverted = true;
        return;
    }
    ts.playhead   = win.start;
    ts.wrap_start = win.start;
    ts.wrap_len   = win.len;
    ts.wrap_mode  = mode;
}

// ── Lick step (mirrors OrpheusEngine::LickStepAtomic layout) ────────────
static constexpr int kMaxLickSteps = 64;
static constexpr int kLickFieldsPerStep = 4;   // degree, duration, velocity, glide
static constexpr int kMaxLickPool = 4;   // MUST equal OrpheusEngine::kMaxLickPool
static_assert(kMaxLickSteps <= kMaxPulsarSteps,
              "a FILL lick is written into a step_count-sized sequencer buffer");
static constexpr int kMaxProgressionLength = 12;  // up to a literal 12-bar blues

struct PulsarLickStep {
    int8_t scale_degree;
    float duration;
    float velocity;
    float glide_rate;  // -1 = use track default; >= 0 = per-step override
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
    int8_t  original_progression[kMaxProgressionLength];  // snapshot at load
    int     anchor_bars = 0;                              // 0 = disabled
    float   drift_range = 0.5f;                           // 0-1
    int     bars_since_anchor = 0;                        // counter
    // Per-chord glide override applied when transitioning *into* each chord.
    // 0 = no glide; >0 maps onto the same 0..1 portamento curve as track glide.
    float   progression_glides[kMaxProgressionLength] = {};
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
    HalfLickMode half_lick = HalfLickMode::OFF;
    float chromatic_passing = 0.0f;
    // Unauthored: timbre sweeps inside the track's own mood_timbre window, morph/harm stay
    // off. Both bounds must be authored together — a lone low sweeps toward the sentinel.
    float evo_timbre_low = kUnauthoredTensionBound, evo_timbre_high = kUnauthoredTensionBound, evo_timbre_prob = 0.7f;
    float evo_morph_low = kUnauthoredTensionBound, evo_morph_high = kUnauthoredTensionBound, evo_morph_prob = 0.5f;
    float evo_harm_low = kUnauthoredTensionBound, evo_harm_high = kUnauthoredTensionBound, evo_harm_prob = 0.3f;
    float evo_attack_point = 0.5f;
    float evo_release_speed = 0.3f;
    float track_evo_weight[8] = {-1,-1,-1,-1,-1,-1,-1,-1};
    float spurt_chance = 0.0f;  // per-bar random spurt probability (0 = tension-only)
};

// ── Section system parameters ───────────────────────────────────────

struct SectionTransitionParam {
    int target_index;
    float weight;
    // Pre-roll ramp into the destination section, in bars. 0 = hard cut at the
    // boundary; >0 = macro overrides crossfade over the LAST N bars of the source
    // section toward the destination's overrides, then snap to destination at boundary.
    int transition_bars;
};

struct MacroOverridesParam {
    float energy = -1.0f;
    float complexity = -1.0f;
    float space = -1.0f;
    float mood = -1.0f;
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
    int markov_current_degree = 0;   // JAM: persists the markov walk's degree across bars
};

struct DuckingParam {
    float volume_reduction = 0.3f;
    float density_reduction = 0.4f;
    float ghost_reduction = 0.5f;
    float fill_suppression = 0.8f;
    bool simplify = true;
    float reverb_boost = 0.1f;
};

enum class SoloModeId : uint8_t {
    NONE         = 0,
    LONG_FILL    = 1,
    LICK_BUILDER = 2,
    JAM          = 3,
};

struct SectionParam {
    int bars_min = 4, bars_max = 8;
    // Step within [bars_min, bars_max] when picking a random length. 1 = any
    // value; 2 = only odd or only even (determined by bars_min's parity); 4 =
    // 4-bar increments; etc. Useful for keeping phrase lengths musical.
    int bar_step = 1;
    SectionTransitionParam transitions[kMaxSectionTransitions];
    int transition_count = 0;
    float recency_decay = 0.5f;
    MacroOverridesParam macro_overrides;
    bool has_tension_override = false;
    TensionParams tension_override;
    SoloModeId solo_mode = SoloModeId::NONE;
    float solo_probability = 0.0f;
    float solo_mutation_rate = 0.5f;    // LickBuilder only
    float solo_lick_influence = 0.5f;   // Jam only
    int solo_bars_min = 2;              // LongFill only
    int solo_bars_max = 4;              // LongFill only
    // Section-level overrides (-1 = no override, keep track defaults)
    int comping_style_override = -1;
    int comping_inversion_override = -1;
    int chord_follow_override = -1;
    // Per-track section overrides (-1 = no override). Per-track wins over the
    // section-level override above. Defaulted to -1 so an un-loaded SectionParam
    // (e.g. constructed in a future test fixture) is safe to read — load_vibe
    // overwrites all 8 slots before any audio block consults them.
    int track_comping_style_override[kNumPulsarTracks] = {-1, -1, -1, -1, -1, -1, -1, -1};
    int track_inversion_override[kNumPulsarTracks]     = {-1, -1, -1, -1, -1, -1, -1, -1};
    int track_arp_mode_override[kNumPulsarTracks]      = {-1, -1, -1, -1, -1, -1, -1, -1};
    int track_chord_follow_override[kNumPulsarTracks]  = {-1, -1, -1, -1, -1, -1, -1, -1};
    // Per-section chord progression override (0 = no override; see pulsar_chord_progression.h for kMaxProgressionLength = 8)
    int custom_progression_length = 0;
    int8_t custom_progression[kMaxProgressionLength] = {};
    // Per-section chord-change rate override (0 = no override, 1..4 = override value)
    int chords_per_bar_override = 0;
    // Per-section CompingHumanization override (applies to ALL CHORDAL tracks).
    bool has_comping_humanization_override = false;
    float comping_humanization_drop = 0.0f;
    float comping_humanization_ghost = 0.0f;
    float comping_humanization_octave = 0.0f;
    float comping_humanization_extension = 0.0f;
    // Master record-scratch fired when this section is LEFT (0 = none). The pulsar
    // unit arms the scratch at the section flip and freezes its own clock while the
    // scratch is active, so the incoming section holds until the scratch drops.
    int exit_scratch_ms = 0;
    // Carry an in-flight band solo across this section's ENTRY (slot 16).
    // Gates only the section-entry solo reset block; see the section_changed
    // handler in orpheus_unit_pulsar.cpp.
    bool jam_carry = false;
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
    // Destination overrides during a transition crossfade. Populated when a
    // transition stages, reset back to -1 when the transition completes.
    float next_energy = -1.0f, next_complexity = -1.0f;
    float next_space = -1.0f, next_mood = -1.0f;
    // Pre-roll model: the next section is selected the moment the current
    // section becomes active, and its incoming edge's transition_bars determines
    // how many of the LAST bars of the current section are the ramp zone.
    int next_section_planned = -1;
    int next_section_trans_bars = 0;
};

// ── Band-based solo system ──────────────────────────────────────────
static constexpr int kMaxBandMembers = 8;

enum class MemberSoloRole : uint8_t {
    SUPPORT  = 0,
    ACTIVE   = 1,
    LEADING  = 2,
};

struct BandMemberParam {
    int tracks[kNumPulsarTracks] = {};
    int track_count = 0;
    bool always_active = false;
    float loudness = 0.5f;
    float creativity = 0.5f;
    float swing_amount = 0.0f;
    float drag_amount = 0.0f;
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
    int8_t last_phrase[kMaxSoloPhrase] = {};
    int phrase_cursor = 0;
    int pending_lead = -1;   // next lead pre-selected one bar early (overlap bridge)
    uint32_t solo_seed = 0;
    // Register reconciliation: track where the outgoing soloist ended so the
    // incoming lick's octave can be chosen to minimise the leap.
    int outgoing_last_note = -1;   // MIDI note of last rendered lick step; -1 = unknown
    bool just_handed_off = false;  // true on the bar a handoff occurred (cleared next bar)
    // Drum-lead state: style chosen at the handoff (-1 = no drum lead this span),
    // and whether the last handoff was a drum lead (prevents back-to-back drum leads).
    int drum_lead_style = -1;
    bool last_handoff_was_drum = false;
    // Octave chosen for the CURRENT soloist's run (chosen once at handoff, held
    // stable for the run). -1 = not yet set (first bar of a new run).
    int solo_lick_octave = -1;
};

// Wah Anomaly config (unpacked from pulsar_wah_data). Mirrors the Kotlin
// WahAnomaly: a probability + duration range plus the WahParams voice armed
// onto the eligible LEAD tracks, one per-track insert each. Never armed onto the
// master bus: filtering the summed mix wahs the drums, which is the whole reason
// this config drives a per-track path instead of a master-bus stage.
struct WahAnomalyConfig {
    float probability = 0.0f;
    float dur_min = 2.0f;
    float dur_max = 4.0f;
    orpheus::WahParams voice;
};

// ── Wah Anomaly: lead-only eligibility ──────────────────────────────────
// The wah anomaly is a LEAD effect, applied per track. A track qualifies only when
// it is melodic, is not reading the bass line channel, and is not the hard-wired
// bass track. Drums, chordal pads, and the bass are all excluded. Pure functions of
// the pushed atomics so the test harness can pin the contract with no engine fixture.
//   role        — pulsar_track_role[t]        (0 = PERC, 1 = MELODIC, 2 = CHORDAL)
//   lick_source — pulsar_track_lick_source[t] (1 = BASS, 0 = LEAD)
inline bool wah_anomaly_track_eligible(int role, int lick_source, int t) {
    return role == static_cast<int>(TrackRole::MELODIC)
        && lick_source != 1
        && t != kBassTrack;
}

// Bitmask of eligible tracks. 0 means the anomaly must NOT fire at all — there is
// deliberately no master-bus fallback, since that is the bug this path replaces.
inline uint8_t wah_anomaly_lead_mask(const int* roles, const int* lick_sources) {
    uint8_t mask = 0;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        if (wah_anomaly_track_eligible(roles[t], lick_sources[t], t))
            mask = static_cast<uint8_t>(mask | (1 << t));
    }
    return mask;
}

// Per-sample parameter morph for the wah-anomaly TAKEOVER path. A lead that already
// carries the standing lick-wah insert keeps running its ONE WahVoice, and every
// WahParams field is interpolated from the standing params toward the anomaly params
// by the anomaly's trapezoid envelope. env == 0 reproduces `from` bit-for-bit, so the
// entry AND the return are both continuous: one resonant bandpass per track, never
// two cascaded. Interpolating rate_division moves the LFO increment continuously;
// the phase itself is never reset, so there is nothing to click against.
inline orpheus::WahParams wah_params_lerp(const orpheus::WahParams& from,
                                          const orpheus::WahParams& to, float env) {
    orpheus::WahParams p;
    p.rate_division = from.rate_division + (to.rate_division - from.rate_division) * env;
    p.depth         = from.depth         + (to.depth         - from.depth)         * env;
    p.resonance_q   = from.resonance_q   + (to.resonance_q   - from.resonance_q)   * env;
    p.center_hz     = from.center_hz     + (to.center_hz     - from.center_hz)     * env;
    p.sweep_octaves = from.sweep_octaves + (to.sweep_octaves - from.sweep_octaves) * env;
    p.wet           = from.wet           + (to.wet           - from.wet)           * env;
    return p;
}

// Trapezoid wet/morph envelope for an armed wah-anomaly window: 15% ramp in, flat
// sustain, 15% ramp out. Matches the master-bus wah envelope this path replaces, so
// the sweep shape is unchanged by the reroute. `left` counts DOWN from `total`.
inline float wah_anomaly_env(int left, int total) {
    if (total <= 0) return 0.0f;
    const float ramp = 0.15f;
    const float x = 1.0f - static_cast<float>(left) / static_cast<float>(total);
    return (x < ramp)          ? x / ramp
         : (x > 1.0f - ramp)   ? (1.0f - x) / ramp
                               : 1.0f;
}

// Crossfade Anomaly config (unpacked from pulsar_crossfade_data). Mirrors the
// Kotlin CrossfadeAnomaly: a probability + duration range plus the dip depth
// armed onto MasterCrossfade.
struct CrossfadeConfig {
    float probability = 0.0f;
    float dur_min = 1.0f;
    float dur_max = 2.0f;
    float depth = 0.0f;
};

// Cut Anomaly config (unpacked from pulsar_cut_data). Mirrors the Kotlin
// CutAnomaly: a probability + duration range plus the rhythmic gate armed
// onto MasterCut.
struct CutConfig {
    float probability = 0.0f;
    float dur_min = 1.0f;
    float dur_max = 2.0f;
    float gate_rate = 2.0f;
    float duty = 0.5f;
    float depth = 0.0f;
};

// Swell Anomaly config (unpacked from pulsar_swell_data). Mirrors the Kotlin
// SwellAnomaly: a probability + duration range plus the start/peak levels
// armed onto MasterSwell. peak_level may intentionally exceed 1.0 — not clamped.
struct SwellConfig {
    float probability = 0.0f;
    float dur_min = 2.0f;
    float dur_max = 4.0f;
    float start_level = 1.0f;
    float peak_level = 1.3f;
};

// Tape Anomaly config (unpacked from pulsar_tape_data). Mirrors the Kotlin
// TapeAnomaly: a probability + duration range. Arms the EXISTING
// master_tape_stop_l/r members (already in the master chain) — no voice
// params to carry, unlike Wah.
struct TapeConfig {
    float probability = 0.0f;
    float dur_min = 1.0f;
    float dur_max = 2.0f;
};

// Scratch Anomaly config (unpacked from pulsar_scratch_data). Mirrors the Kotlin
// ScratchAnomaly: a probability + duration range. Arms the EXISTING
// master_scratch_l/r members (already in the master chain for the section-exit
// scratch feature) — no voice params to carry, unlike Wah.
struct ScratchConfig {
    float probability = 0.0f;
    float dur_min = 1.0f;
    float dur_max = 2.0f;
};

// Filter Anomaly config (unpacked from pulsar_filter_data). Mirrors the Kotlin
// FilterAnomaly: a probability + duration range. Arms the EXISTING
// master_filter_l/r members (already in the master chain) — no voice params
// to carry, unlike Wah.
struct FilterConfig {
    float probability = 0.0f;
    float dur_min = 2.0f;
    float dur_max = 4.0f;
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
    PulsarLickStep lick[kMaxLickSteps];           // working copy (drifts over time)
    PulsarLickStep original_lick[kMaxLickSteps];  // immutable copy from Kotlin
    float lick_mutation;
    int lick_octave;  // -1 = auto (midpoint of noteRange), 0-8 = explicit MIDI octave

    // Lick pool (Fire Sky .5f); copied from engine atomics on vibe load.
    int lick_pool_count = 0;                 // 0 = disabled (single-lick path)
    PulsarLickStep lick_pool[kMaxLickPool][kMaxLickSteps];
    int lick_pool_len[kMaxLickPool] = {};
    int lick_pool_loop[kMaxLickPool] = {};
    int lick_anomaly_index = -1;
    float lick_anomaly_chance = 0.0f;
    int active_rotation_index = 0;           // current section's rotation choice
    int current_lick_index = -1;             // bank slot currently rendered (-1 = single-lick)
    uint32_t lick_select_seed = 0;           // play-scoped RNG, independent of mutation/void

    // Bass line channel (copied from engine atomics on vibe load). Sibling of
    // state->lick; rotation and anomaly swaps never touch these buffers.
    int bass_line_length = 0;
    int bass_line_loop_length = 0;
    PulsarLickStep bass_line[kMaxLickSteps];
    PulsarLickStep original_bass_line[kMaxLickSteps];
    float bass_line_mutation = 0.5f;
    int bass_line_octave = -1;

    // Lick evolution spurt state
    bool in_spurt = false;
    int spurt_bars_remaining = 0;

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

    // Section / Solo system
    ArrangementParams arrangement;
    SectionState section_state;
    SoloBehaviorParam track_solo_behavior[kNumPulsarTracks];
    DuckingParam track_ducking[kNumPulsarTracks];

    // Band-based solo system (parallel to track-based solos)
    BandSoloState band_solo_state;
    BandSoloConfigParam band_solo_config;
    bool has_band_solo = false;

    // Void Anomaly (dispatched by the Anomaly Engine)
    VoidConfig void_config;
    VoidAnomaly void_state;
    uint32_t void_seed = 0;             // play-scoped RNG, independent of mutation_seed
    int prev_anomaly_request = 0;       // edge-detect mirror of pulsar_anomaly_request
    bool void_declared = false;         // true when this vibe opts into the void (void_data[7])

    // Wah Anomaly (dispatched by the Anomaly Engine, arms a per-track insert on the
    // eligible LEAD tracks — see wah_anomaly_lead_mask above. Unlike every sibling
    // anomaly below, it does NOT touch the master bus.)
    WahAnomalyConfig wah_config;
    bool wah_declared = false;          // true when this vibe opts into the wah (wah_data[9])
    // Armed-window state for the per-track wah anomaly. anomaly_wah_mask is resolved
    // ONCE at arm time: roles can be pushed live, but the window must not change which
    // tracks it filters mid-sweep. samples_left > 0 is the single "armed" predicate, and
    // it is decremented exactly once per BLOCK (at t == 0 in the per-track loop), never
    // once per track — a per-track decrement would burn the window 8x too fast and
    // desync the per-track LFO phases. In-class initializers matter here: PulsarState is
    // heap-allocated once per engine and only selected POD fields are explicitly zeroed.
    orpheus::WahParams anomaly_wah_params;   // snapshot of wah_config.voice at arm time
    int      anomaly_wah_samples_total = 0;
    int      anomaly_wah_samples_left  = 0;
    uint8_t  anomaly_wah_mask = 0;           // bit t set => track t is in the armed window
    // Fresh voices for eligible tracks with NO standing lick wah. A takeover track
    // reuses lick_wah_voice[t] instead, so its Svf state and LFO phase stay continuous.
    orpheus::WahVoice anomaly_wah_voice[kNumPulsarTracks];

    // Crossfade Anomaly (dispatched by the Anomaly Engine, arms MasterCrossfade on the master bus)
    CrossfadeConfig crossfade_config;
    bool crossfade_declared = false;    // true when this vibe opts into the crossfade (crossfade_data[4])

    // Cut Anomaly (dispatched by the Anomaly Engine, arms MasterCut on the master bus)
    CutConfig cut_config;
    bool cut_declared = false;          // true when this vibe opts into the cut (cut_data[6])

    // Swell Anomaly (dispatched by the Anomaly Engine, arms MasterSwell on the master bus)
    SwellConfig swell_config;
    bool swell_declared = false;        // true when this vibe opts into the swell (swell_data[5])

    // Tape Anomaly (dispatched by the Anomaly Engine, arms the EXISTING
    // master_tape_stop_l/r on the master bus)
    TapeConfig tape_config;
    bool tape_declared = false;         // true when this vibe opts into the tape stop (tape_data[3])

    // Scratch Anomaly (dispatched by the Anomaly Engine, arms the EXISTING
    // master_scratch_l/r on the master bus)
    ScratchConfig scratch_config;
    bool scratch_declared = false;      // true when this vibe opts into the scratch (scratch_data[3])

    // Filter Anomaly (dispatched by the Anomaly Engine, arms the EXISTING
    // master_filter_l/r on the master bus)
    FilterConfig filter_config;
    bool filter_declared = false;       // true when this vibe opts into the filter (filter_data[3])

    // Per-track lick-wah insert (NOT an anomaly): a standing bandpass wah applied to each
    // opted-in track's rendered buffer, in place, before it accumulates into the mix. One
    // WahVoice per track so each keeps its own filter + LFO phase. Inert unless lick_wah_declared.
    // While the wah anomaly is armed on the same track, the anomaly TAKES OVER this voice by
    // morphing lick_wah_params toward anomaly_wah_params rather than adding a second filter.
    // "One voice per track, own phase" is exactly what makes that takeover click-free.
    // Params are per track too, so a bass can rock a bar-long sweep down low while a lead
    // works a quarter-note pedal up in the vowel range. Costs nothing at render time: the
    // voice loop already ran per track, this only changes which params it reads.
    orpheus::WahParams lick_wah_params[kNumPulsarTracks];
    orpheus::WahVoice lick_wah_voice[kNumPulsarTracks];
    uint8_t lick_wah_mask = 0;          // bit t set => track t filters through lick_wah_voice[t]
    bool lick_wah_declared = false;     // true when at least one track resolved wah params

    uint32_t master_anomaly_seed = 0;   // play-scoped RNG for Master* anomaly rolls/durations
    bool force_lick_anomaly = false;    // one-shot: force the OG lick anomaly at the next resolve
    float section_total_steps = 0.0f;   // drawn section length in steps, snapshot at entry

    // Living lick state for LickBuilder/Jam modes
    int8_t live_lick_degrees[kMaxLickSteps] = {};
    float live_lick_durations[kMaxLickSteps] = {};
    float live_lick_velocities[kMaxLickSteps] = {};
    int live_lick_length = 0;
    bool live_lick_active = false;
    // MUT-4: section-entry snapshot of the lick degrees. mutate_live_lick clamps
    // each evolving degree against this so the octave-jump idiom can't run away
    // now that the live lick is audibly rendered (SOLO-1).
    int8_t live_lick_base_degrees[kMaxLickSteps] = {};
    // True while the live lick was seeded from the bass channel (the soloing
    // member's lick tracks are BASS-source). Render-back then uses the bass
    // line's loop length and octave instead of the lead lick's.
    bool live_lick_bass_channel = false;

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

// Re-pack a Kotlin-packed (stride-N, row-major) NxN matrix into the engine's
// fixed stride-kMaxBandMembers layout the consumers read. Zeros unused rows so
// a previous vibe with more members can't leak (band_solo_config is persistent).
// Declared inline in the header so both the audio thread (load_vibe) and the
// C++ test harness can call it directly.
inline void pack_band_matrix(float* dst /*[kMaxBandMembers*kMaxBandMembers]*/,
                             const float* src_packed /*[N*N]*/, int n) {
    for (int i = 0; i < kMaxBandMembers * kMaxBandMembers; i++) dst[i] = 0.0f;
    if (n < 1 || n > kMaxBandMembers) return;
    for (int from = 0; from < n; from++)
        for (int to = 0; to < n; to++)
            dst[from * kMaxBandMembers + to] = src_packed[from * n + to];
}
