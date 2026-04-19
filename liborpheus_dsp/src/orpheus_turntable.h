#pragma once
#include <cstdint>
#include <cmath>
#include <atomic>

struct OrpheusEngine;
struct GraphUnit;

static constexpr int kTurntableBufSize = 192000;  // ~4 sec at 48kHz
static constexpr int kTurntableVizSize = 128;      // downsampled waveform snapshot
static constexpr float kTurntableBypassThreshold = 0.001f;
static constexpr float kTurntableVelSmoothCoeff = 0.15f;  // snappy scratch response

// ── Drop effect constants ──────────────────────────────────────────
static constexpr float kDropFilterBuildUpRate = 0.0000035f; // per-sample ramp (~6s to full at 48kHz)
static constexpr float kDropLfoMinHz          = 6.0f;
static constexpr float kDropLfoMaxHz          = 16.0f;
static constexpr float kDropFilterMinHz       = 150.0f;
static constexpr float kDropFilterMaxHz       = 20000.0f;
static constexpr float kDropFilterQMin        = 2.0f;
static constexpr float kDropFilterQMax        = 8.0f;
static constexpr float kDropGainBoost         = 2.5f;

static constexpr float kBrakeDecayPerSample   = 0.9997f;   // brake_speed → 1% in ~320 ms at 48 kHz (fast vinyl stop)
static constexpr int   kFreezeSliceSamples    = 4800;   // 100 ms @ 48 kHz
static constexpr float kFreezeLfoHz           = 0.5f;
static constexpr float kFreezePitchDepth      = 0.06f;  // ±2 semitones
static constexpr float kStutterRampSeconds    = 1.5f;
static constexpr float kFreeRunBeatPhaseHz    = 2.0f;   // idle fallback pulse
static constexpr float kDropOutputGain        = 1.3f;   // ~+2.3 dB, applied after per-kind processor

// OCTAVE: additive subharmonic — reads at half rate and mixes under the playback.
static constexpr float kOctaveSubMix          = 0.7f;   // subharmonic layer gain
static constexpr float kOctaveDryMix          = 0.85f;  // pass-through gain (preserves original)

// PHASER: 4-stage allpass with slow LFO sweep over a cutoff range.
static constexpr int   kPhaserStages          = 4;
static constexpr float kPhaserLfoHz           = 0.4f;
static constexpr float kPhaserMinHz           = 200.0f;
static constexpr float kPhaserMaxHz           = 2000.0f;
static constexpr float kPhaserFeedback        = 0.5f;
static constexpr float kPhaserWetMix          = 0.7f;   // dry(1-wet) + wet(phased)

// ECHO: dub-style delay + regenerative feedback.
static constexpr int   kEchoBufSize           = 24000;  // 500 ms @ 48 kHz
static constexpr int   kEchoDelaySamples      = 18000;  // ~375 ms (dotted-eighth feel at 120 BPM)
static constexpr float kEchoFeedback          = 0.55f;
static constexpr float kEchoWetMix            = 0.6f;

// RING: ring-mod with a slowly-swept carrier frequency.
static constexpr float kRingMinHz             = 80.0f;
static constexpr float kRingMaxHz             = 400.0f;
static constexpr float kRingSweepHz           = 0.25f;  // sweep period = 4 s
static constexpr float kRingDryMix            = 0.35f;
static constexpr float kRingWetMix            = 0.65f;

enum DropKind : int {
    DROP_NONE    = 0,
    DROP_FILTER  = 1,
    DROP_BRAKE   = 2,
    DROP_STUTTER = 3,
    DROP_FREEZE  = 4,
    DROP_OCTAVE  = 5,
    DROP_PHASER  = 6,
    DROP_ECHO    = 7,
    DROP_RING    = 8,
};

enum TurntableSource : int {
    TT_SOURCE_SYNTH  = 0,
    TT_SOURCE_DRUMS  = 1,
    TT_SOURCE_BASS   = 2,
    TT_SOURCE_MASTER = 3,
    TT_SOURCE_SUM    = 4,   // All 8 Pulsar tracks (stereo summed to mono)
};

struct TurntableDeck {
    float buffer[kTurntableBufSize] = {};
    float read_pos = 0.0f;           // fractional sample position
    int   write_pos = 0;             // integer write head
    float smoothed_velocity = 1.0f;  // after one-pole smoothing
    float aa_lpf_state = 0.0f;      // anti-alias filter state (persists across blocks)
    bool  frozen = false;            // manual freeze from UI
    bool  auto_frozen = false;       // auto-freeze when input goes silent
    int   silence_blocks = 0;        // consecutive silent blocks counter
    int   source = TT_SOURCE_SYNTH;

    // Drop effect state — one kind at a time per deck
    struct DropContext {
        DropKind kind = DROP_NONE;
        float    phase = 0.0f;              // 0..1 progress; semantics are kind-specific

        // FILTER: biquad LPF + LFO
        float bq_s1 = 0.0f, bq_s2 = 0.0f;
        float b0 = 0.0f, b1 = 0.0f, b2 = 0.0f;
        float a1 = 0.0f, a2 = 0.0f;
        float lfo_phase = 0.0f;

        // BRAKE: own read cursor + decaying speed
        float brake_read  = 0.0f;
        float brake_speed = 1.0f;

        // STUTTER: division sweep + smoothed gate
        int   stutter_div_idx = 0;          // 0=1/8, 1=1/16, 2=1/32
        float stutter_smoothed_gate = 1.0f;

        // FREEZE: fixed slice + pitch mod
        int   freeze_start = 0;
        int   freeze_len   = 0;
        float freeze_read  = 0.0f;
        float freeze_lfo   = 0.0f;

        // OCTAVE: half-rate read cursor for the subharmonic layer
        float octave_read = 0.0f;

        // PHASER: allpass state per stage + LFO phase + last wet output for feedback
        float phaser_stage[kPhaserStages] = {};
        float phaser_lfo_phase = 0.0f;
        float phaser_feedback = 0.0f;

        // ECHO: own delay line + write head + read phase (for tiny flam variations)
        float echo_buffer[kEchoBufSize] = {};
        int   echo_write = 0;

        // RING: oscillator phase + sweep phase
        float ring_phase = 0.0f;
        float ring_sweep_phase = 0.0f;
    };
    DropContext drop;

    // Double-buffered viz snapshot for thread safety.
    // Audio thread writes to viz_snapshots[viz_write_idx], UI reads from the other.
    float viz_snapshots[2][kTurntableVizSize + 1] = {};  // 128 waveform + 1 playhead
    std::atomic<int> viz_write_idx{0};  // toggles 0/1 after each audio-thread write
};

void unit_process_turntable(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void turntable_get_viz(TurntableDeck* deck, float* out_buffer);

// Exposed for unit testing — not part of the public API
void playback_deck(TurntableDeck* deck, float target_velocity,
                   float* out, int num_frames, float sample_rate);
void drop_process(TurntableDeck* deck, float* buf, int num_frames,
                  float sample_rate, float beat_phase);
