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

// ── Drop buildup constants ──────────────────────────────────────────
static constexpr float kDropSlowThreshold = 0.3f;    // |vel| below this = "slow backward"
static constexpr float kDropBuildUpRate   = 0.0000035f; // per-sample initial ramp up (~6s to full at 48kHz)
static constexpr float kDropCycleRate     = 0.0000069f; // per-sample ping-pong rate (~3s per leg after first)
static constexpr float kDropReleaseRate   = 0.00015f;  // per-sample ramp (~139ms release)
static constexpr float kDropLfoMinHz      = 6.0f;      // immediate thumping wobble
static constexpr float kDropLfoMaxHz      = 16.0f;     // aggressive at full buildup
static constexpr float kDropFilterMinHz   = 150.0f;    // deeper sweep floor
static constexpr float kDropFilterMaxHz   = 20000.0f;
static constexpr float kDropFilterQMin    = 2.0f;     // Q at start of buildup
static constexpr float kDropFilterQMax    = 8.0f;     // Q at full buildup (resonant scream)
static constexpr float kDropGainBoost     = 2.5f;     // max output gain at full buildup
static constexpr float kDropPlaySpeedMin  = -1.0f;    // backward playback speed at start
static constexpr float kDropPlaySpeedMax  = -12.0f;   // accelerates to 12x backward at full
static constexpr float kDropPingPongFloor = 0.3f;     // ping-pong never fully opens (0.3 = still filtered)
static constexpr float kDropPingPongCeil  = 0.75f;    // ping-pong ceiling (lower = doesn't cut out)

enum DropState : int { DROP_IDLE = 0, DROP_BUILDING, DROP_RELEASING };

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

    // Drop buildup state (slow-backward filter sweep)
    DropState drop_state    = DROP_IDLE;
    float drop_amount       = 0.0f;    // 0-1 buildup intensity
    bool  drop_rising       = true;    // ping-pong direction (true=rising, false=falling)
    bool  drop_first_sweep  = true;    // true until first peak reached
    float drop_lfo_phase    = 0.0f;    // 0-1 triangle LFO phase
    float drop_bq_s1        = 0.0f;    // biquad state (direct form II transposed)
    float drop_bq_s2        = 0.0f;
    // Cached biquad coefficients — recomputed per sample for smooth sweeps
    float drop_b0 = 0.0f, drop_b1 = 0.0f, drop_b2 = 0.0f;
    float drop_a1 = 0.0f, drop_a2 = 0.0f;

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
