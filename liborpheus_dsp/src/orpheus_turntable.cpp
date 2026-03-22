#include "orpheus_turntable.h"
#include "orpheus_engine.h"
#include "orpheus_graph.h"
#include <cstring>
#include <cmath>

// Cubic Hermite interpolation for smooth variable-speed playback
static inline float cubic_interp(const float* buf, int buf_size, float pos) {
    int i0 = static_cast<int>(pos);
    float frac = pos - static_cast<float>(i0);

    // Wrap indices into circular buffer
    auto wrap = [buf_size](int i) -> int {
        return ((i % buf_size) + buf_size) % buf_size;
    };
    float y_1 = buf[wrap(i0 - 1)];
    float y0  = buf[wrap(i0)];
    float y1  = buf[wrap(i0 + 1)];
    float y2  = buf[wrap(i0 + 2)];

    // Cubic Hermite
    float c0 = y0;
    float c1 = 0.5f * (y1 - y_1);
    float c2 = y_1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2;
    float c3 = 0.5f * (y2 - y_1) + 1.5f * (y0 - y1);
    return ((c3 * frac + c2) * frac + c1) * frac + c0;
}

// Wrap float position into [0, buf_size)
static inline float wrap_pos(float pos, int buf_size) {
    float s = static_cast<float>(buf_size);
    pos = std::fmod(pos, s);
    if (pos < 0.0f) pos += s;
    return pos;
}

static void capture_source(TurntableDeck* deck, const float* source, int num_frames) {
    for (int i = 0; i < num_frames; i++) {
        deck->buffer[deck->write_pos] = source[i];
        deck->write_pos = (deck->write_pos + 1) % kTurntableBufSize;
    }
}

static void playback_deck(TurntableDeck* deck, float target_velocity,
                           float* out, int num_frames) {
    for (int i = 0; i < num_frames; i++) {
        // One-pole velocity smoothing (per-sample for smooth transitions)
        deck->smoothed_velocity += kTurntableVelSmoothCoeff *
            (target_velocity - deck->smoothed_velocity);

        out[i] = cubic_interp(deck->buffer, kTurntableBufSize, deck->read_pos);
        deck->read_pos = wrap_pos(
            deck->read_pos + deck->smoothed_velocity,
            kTurntableBufSize
        );
    }
}

// Source-dependent gain — source buffers are normalized low
static float turntable_source_gain(int src) {
    switch (src) {
        case TT_SOURCE_SYNTH:  return 10.0f;
        case TT_SOURCE_DRUMS:  return 4.0f;
        case TT_SOURCE_BASS:   return 4.0f;
        case TT_SOURCE_MASTER: return 2.0f;
        default:               return 6.0f;
    }
}

static void turntable_update_viz(TurntableDeck* deck) {
    // Write to the non-active snapshot buffer (double-buffered)
    int write_idx = deck->viz_write_idx.load(std::memory_order_relaxed);
    int next_idx = 1 - write_idx;
    float* snap = deck->viz_snapshots[next_idx];

    // Downsample buffer to 128 samples for radial waveform display
    // Scale by source gain so waveform is visible (raw buffer values are very low)
    float gain = turntable_source_gain(deck->source);
    float step = static_cast<float>(kTurntableBufSize) / kTurntableVizSize;
    for (int i = 0; i < kTurntableVizSize; i++) {
        int idx = static_cast<int>(i * step) % kTurntableBufSize;
        snap[i] = deck->buffer[idx] * gain;
    }
    // Append normalized playhead position
    snap[kTurntableVizSize] = deck->read_pos / static_cast<float>(kTurntableBufSize);

    // Publish: flip the write index so UI reads the freshly written buffer
    deck->viz_write_idx.store(next_idx, std::memory_order_release);
}

void turntable_get_viz(TurntableDeck* deck, float* out_buffer) {
    // Read from the currently published snapshot (not being written)
    int read_idx = deck->viz_write_idx.load(std::memory_order_acquire);
    std::memcpy(out_buffer, deck->viz_snapshots[read_idx],
                (kTurntableVizSize + 1) * sizeof(float));
}

void unit_process_turntable(GraphUnit* u, OrpheusEngine* engine,
                            int num_frames, float sample_rate) {
    // Load control atomics
    float target_mix = engine->turntable_mix.load(std::memory_order_relaxed);
    float vel_a = engine->turntable_velocity_a.load(std::memory_order_relaxed);
    float vel_b = engine->turntable_velocity_b.load(std::memory_order_relaxed);
    bool frozen_a = engine->turntable_frozen_a.load(std::memory_order_relaxed) != 0;
    bool frozen_b = engine->turntable_frozen_b.load(std::memory_order_relaxed) != 0;
    int src_a = engine->turntable_source_a.load(std::memory_order_relaxed);
    int src_b = engine->turntable_source_b.load(std::memory_order_relaxed);
    float xfade = engine->turntable_crossfader.load(std::memory_order_relaxed);

    auto& deck_a = engine->turntable_decks[0];
    auto& deck_b = engine->turntable_decks[1];

    // Update frozen state
    deck_a.frozen = frozen_a;
    deck_b.frozen = frozen_b;
    deck_a.source = src_a;
    deck_b.source = src_b;

    // Get source buffers (double-buffered reads from previous frame)
    auto get_source = [&](int source) -> const float* {
        switch (source) {
            case TT_SOURCE_SYNTH:  return engine->warps_synth_read;
            case TT_SOURCE_DRUMS:  return engine->warps_drums_read;
            case TT_SOURCE_BASS:   return engine->warps_bass_read;
            case TT_SOURCE_MASTER: return engine->turntable_prev_master;
            default:               return engine->warps_synth_read;
        }
    };

    // Always capture — decks should be "recording" even when mix is down,
    // so there's material ready to play when the user brings the fader up.
    if (!deck_a.frozen) capture_source(&deck_a, get_source(src_a), num_frames);
    if (!deck_b.frozen) capture_source(&deck_b, get_source(src_b), num_frames);

    // Always update viz so platter waveforms show captured audio
    turntable_update_viz(&deck_a);
    turntable_update_viz(&deck_b);

    // Output bypass — silence output but don't skip capture/viz
    bool bypassed = target_mix <= kTurntableBypassThreshold;
    if (bypassed) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        engine->turntable_smooth_mix = 0.0f;
        engine->viz_rings[VIZ_DJ_OUT].write(0.0f);
        return;
    }

    // Smooth mix to avoid clicks
    float mix = engine->turntable_smooth_mix;
    float mix_inc = (target_mix - mix) / static_cast<float>(num_frames);

    // Playback
    float out_a[kMaxFrames];
    float out_b[kMaxFrames];
    playback_deck(&deck_a, vel_a, out_a, num_frames);
    playback_deck(&deck_b, vel_b, out_b, num_frames);

    float boost_a = turntable_source_gain(src_a);
    float boost_b = turntable_source_gain(src_b);

    // Crossfade (constant power)
    float gain_a = std::cos(xfade * 1.5707963f) * boost_a;  // pi/2
    float gain_b = std::sin(xfade * 1.5707963f) * boost_b;

    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < num_frames; i++) {
        mix += mix_inc;
        out[i] = (out_a[i] * gain_a + out_b[i] * gain_b) * mix;
    }
    engine->turntable_smooth_mix = mix;

    // Write peak to VizRing for Orphoscope time-series trace
    {
        float peak = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            float a = std::fabs(out[i]);
            if (a > peak) peak = a;
        }
        engine->viz_rings[VIZ_DJ_OUT].write(peak);
    }
}
