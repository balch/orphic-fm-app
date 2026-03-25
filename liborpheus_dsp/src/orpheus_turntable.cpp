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
    // Anti-alias filter state persists across blocks to avoid clicks at
    // block boundaries during high-speed scratch playback.
    float lpf_state = deck->aa_lpf_state;

    for (int i = 0; i < num_frames; i++) {
        // One-pole velocity smoothing (per-sample for smooth transitions)
        deck->smoothed_velocity += kTurntableVelSmoothCoeff *
            (target_velocity - deck->smoothed_velocity);

        float raw = cubic_interp(deck->buffer, kTurntableBufSize, deck->read_pos);

        // Anti-alias: stronger filtering at higher speeds.
        // At |velocity| > 1, the read head skips samples, causing aliasing.
        // A one-pole LPF with cutoff ∝ 1/|velocity| tames it.
        float abs_vel = std::fabs(deck->smoothed_velocity);
        if (abs_vel > 1.0f) {
            float alpha = 1.0f / abs_vel;
            lpf_state += alpha * (raw - lpf_state);
            out[i] = lpf_state;
        } else {
            // Blend toward raw to avoid a click when crossing the velocity threshold
            lpf_state += 0.5f * (raw - lpf_state);
            out[i] = raw;
        }

        deck->read_pos = wrap_pos(
            deck->read_pos + deck->smoothed_velocity,
            kTurntableBufSize
        );
    }

    deck->aa_lpf_state = lpf_state;
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
    float target_wet_a = engine->turntable_wet_a.load(std::memory_order_relaxed);
    float target_wet_b = engine->turntable_wet_b.load(std::memory_order_relaxed);
    float vel_a = engine->turntable_velocity_a.load(std::memory_order_relaxed);
    float vel_b = engine->turntable_velocity_b.load(std::memory_order_relaxed);
    bool frozen_a = engine->turntable_frozen_a.load(std::memory_order_relaxed) != 0;
    bool frozen_b = engine->turntable_frozen_b.load(std::memory_order_relaxed) != 0;
    int src_a = engine->turntable_source_a.load(std::memory_order_relaxed);
    int src_b = engine->turntable_source_b.load(std::memory_order_relaxed);

    auto& deck_a = engine->turntable_decks[0];
    auto& deck_b = engine->turntable_decks[1];

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

    const float* dry_a = get_source(src_a);
    const float* dry_b = get_source(src_b);

    // Always capture — decks should be "recording" even when wet is down,
    // so there's material ready to play when the user brings the fader up.
    if (!deck_a.frozen) capture_source(&deck_a, dry_a, num_frames);
    if (!deck_b.frozen) capture_source(&deck_b, dry_b, num_frames);

    // Always update viz so platter waveforms show captured audio
    turntable_update_viz(&deck_a);
    turntable_update_viz(&deck_b);

    // Bypass when both decks are fully dry AND smoothed values have settled.
    // Without checking smooth values, the output snaps to zero while the
    // previous block was still producing audio — a click.
    float smooth_a = engine->turntable_smooth_wet_a;
    float smooth_b = engine->turntable_smooth_wet_b;
    bool bypassed = target_wet_a <= kTurntableBypassThreshold
                 && target_wet_b <= kTurntableBypassThreshold
                 && smooth_a <= kTurntableBypassThreshold
                 && smooth_b <= kTurntableBypassThreshold;
    if (bypassed) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        engine->turntable_smooth_wet_a = 0.0f;
        engine->turntable_smooth_wet_b = 0.0f;
        engine->viz_rings[VIZ_DJ_OUT].write(0.0f);
        return;
    }

    // Smooth per-deck wet levels to avoid clicks
    float wet_a = engine->turntable_smooth_wet_a;
    float wet_b = engine->turntable_smooth_wet_b;
    float wet_a_inc = (target_wet_a - wet_a) / static_cast<float>(num_frames);
    float wet_b_inc = (target_wet_b - wet_b) / static_cast<float>(num_frames);

    // Playback
    float play_a[kMaxFrames];
    float play_b[kMaxFrames];
    playback_deck(&deck_a, vel_a, play_a, num_frames);
    playback_deck(&deck_b, vel_b, play_b, num_frames);

    float boost_a = turntable_source_gain(src_a);
    float boost_b = turntable_source_gain(src_b);

    // Each fader controls turntable playback level.  The dry source path is
    // attenuated by duck_source tags on the graph units (bass compressor, drum
    // limiter, PSB) computed at the start of graph_process.  This gives a clean
    // crossfade: as wet goes up, dry goes down — works for both live and frozen.
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < num_frames; i++) {
        wet_a += wet_a_inc;
        wet_b += wet_b_inc;
        out[i] = play_a[i] * boost_a * wet_a + play_b[i] * boost_b * wet_b;
    }
    engine->turntable_smooth_wet_a = wet_a;
    engine->turntable_smooth_wet_b = wet_b;

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
