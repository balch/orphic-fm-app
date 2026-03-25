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

enum TurntableSource : int {
    TT_SOURCE_SYNTH  = 0,
    TT_SOURCE_DRUMS  = 1,
    TT_SOURCE_BASS   = 2,
    TT_SOURCE_MASTER = 3,
};

struct TurntableDeck {
    float buffer[kTurntableBufSize] = {};
    float read_pos = 0.0f;           // fractional sample position
    int   write_pos = 0;             // integer write head
    float smoothed_velocity = 1.0f;  // after one-pole smoothing
    float aa_lpf_state = 0.0f;      // anti-alias filter state (persists across blocks)
    bool  frozen = false;
    int   source = TT_SOURCE_SYNTH;

    // Double-buffered viz snapshot for thread safety.
    // Audio thread writes to viz_snapshots[viz_write_idx], UI reads from the other.
    float viz_snapshots[2][kTurntableVizSize + 1] = {};  // 128 waveform + 1 playhead
    std::atomic<int> viz_write_idx{0};  // toggles 0/1 after each audio-thread write
};

void unit_process_turntable(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void turntable_get_viz(TurntableDeck* deck, float* out_buffer);
