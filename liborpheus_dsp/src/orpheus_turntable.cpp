#include "orpheus_turntable.h"
#include "orpheus_engine.h"
#include "orpheus_graph.h"
#include "orpheus_voice.h"
#include <cstring>
#include <cmath>

static constexpr float kPi    = 3.14159265358979f;
static constexpr float kTwoPi = 2.0f * kPi;

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

// Silence threshold: ~-60dB. Blocks of silence before auto-freeze triggers.
static constexpr float kSilenceThreshold = 0.001f;
static constexpr int kSilenceBlocksToFreeze = 5;  // ~50ms at 512-sample blocks

static void capture_source(TurntableDeck* deck, const float* source, int num_frames) {
    // Check if this block has signal
    float peak = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float a = std::fabs(source[i]);
        if (a > peak) peak = a;
    }

    if (peak < kSilenceThreshold) {
        deck->silence_blocks++;
        if (deck->silence_blocks >= kSilenceBlocksToFreeze && !deck->auto_frozen) {
            deck->auto_frozen = true;  // stop recording, keep buffer
            return;
        }
    } else {
        // Signal returned — unfreeze and resume recording
        deck->silence_blocks = 0;
        deck->auto_frozen = false;
    }

    if (deck->auto_frozen) return;

    for (int i = 0; i < num_frames; i++) {
        deck->buffer[deck->write_pos] = source[i];
        deck->write_pos = (deck->write_pos + 1) % kTurntableBufSize;
    }
}

void playback_deck(TurntableDeck* deck, float target_velocity,
                   float* out, int num_frames, float sample_rate) {
    float lpf_state = deck->aa_lpf_state;

    for (int i = 0; i < num_frames; i++) {
        // One-pole velocity smoothing
        deck->smoothed_velocity += kTurntableVelSmoothCoeff *
            (target_velocity - deck->smoothed_velocity);

        float raw = cubic_interp(deck->buffer, kTurntableBufSize, deck->read_pos);

        // Anti-alias: one-pole LPF at Nyquist/velocity (BLT-derived coefficient).
        float abs_vel = std::fabs(deck->smoothed_velocity);
        if (abs_vel > 1.0f) {
            float alpha = kPi / (abs_vel + kPi);
            lpf_state += alpha * (raw - lpf_state);
            out[i] = lpf_state;
        } else {
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
        case TT_SOURCE_SYNTH:  return 6.0f;
        case TT_SOURCE_DRUMS:  return 3.0f;
        case TT_SOURCE_BASS:   return 3.0f;
        case TT_SOURCE_MASTER: return 1.5f;
        case TT_SOURCE_SUM:    return 2.0f;
        default:               return 4.0f;
    }
}

// Fader ease-in: strong curve so bottom half is near-silent,
// midpoint is ~15% gain, top half ramps up hot.
// Cubic (w³) gives: 0.1→0.001, 0.3→0.027, 0.5→0.125, 0.7→0.343, 1.0→1.0
static inline float fader_ease(float wet) {
    return wet * wet * wet;
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

// ── Drop: FILTER ────────────────────────────────────────────────────
// Resonant LPF that sweeps from open to closed with a wobble LFO.

static inline float drop_filter_lfo_value(float phase) {
    return (phase < 0.5f) ? (phase * 4.0f - 1.0f) : (3.0f - phase * 4.0f);
}

static void drop_filter_update_coefficients(TurntableDeck::DropContext* d, float sample_rate) {
    float base_cutoff = kDropFilterMaxHz *
        std::pow(kDropFilterMinHz / kDropFilterMaxHz, d->phase);
    float lfo = drop_filter_lfo_value(d->lfo_phase);
    float cutoff_hz = base_cutoff * std::pow(2.0f, lfo * d->phase);
    if (cutoff_hz < kDropFilterMinHz) cutoff_hz = kDropFilterMinHz;
    if (cutoff_hz > kDropFilterMaxHz) cutoff_hz = kDropFilterMaxHz;

    float q = kDropFilterQMin + d->phase * (kDropFilterQMax - kDropFilterQMin);
    float omega = kTwoPi * cutoff_hz / sample_rate;
    float sin_w = std::sin(omega);
    float cos_w = std::cos(omega);
    float alpha = sin_w / (2.0f * q);
    float a0 = 1.0f + alpha;

    d->b0 = ((1.0f - cos_w) / 2.0f) / a0;
    d->b1 = (1.0f - cos_w) / a0;
    d->b2 = d->b0;
    d->a1 = (-2.0f * cos_w) / a0;
    d->a2 = (1.0f - alpha) / a0;
}

static inline float drop_filter_tick(TurntableDeck::DropContext* d, float input) {
    float y = d->b0 * input + d->bq_s1;
    d->bq_s1 = d->b1 * input - d->a1 * y + d->bq_s2;
    d->bq_s2 = d->b2 * input - d->a2 * y;
    float gain = 1.0f + d->phase * (kDropGainBoost - 1.0f);
    return y * gain;
}

// FILTER coefficient update interval. Recomputing pow/sin/cos every sample is
// wasteful — the 6s build-up and 6-16 Hz LFO drift far slower than 48 kHz, so
// updating every 16 samples is imperceptible (phase delta < 6e-5, LFO delta
// < 0.006 cycles) while cutting the trig workload by 16×.
static constexpr int kFilterCoeffUpdateInterval = 16;

static void drop_process_filter(TurntableDeck* deck, float* buf, int n, float sr) {
    auto& d = deck->drop;
    // Seed coefficients if they haven't been computed yet (first block after
    // the NONE→FILTER transition, where maybe_transition zeros them).
    if (d.b0 == 0.0f && d.b1 == 0.0f && d.a1 == 0.0f) {
        drop_filter_update_coefficients(&d, sr);
    }
    const float lfo_inc_base = 1.0f / sr;
    int until_update = 0;  // forces an update on sample 0 of the block
    for (int i = 0; i < n; i++) {
        d.phase += kDropFilterBuildUpRate;
        if (d.phase > 1.0f) d.phase = 1.0f;

        float lfo_hz = kDropLfoMinHz + d.phase * (kDropLfoMaxHz - kDropLfoMinHz);
        d.lfo_phase += lfo_hz * lfo_inc_base;
        if (d.lfo_phase >= 1.0f) d.lfo_phase -= 1.0f;

        if (until_update == 0) {
            drop_filter_update_coefficients(&d, sr);
            until_update = kFilterCoeffUpdateInterval;
        }
        --until_update;
        buf[i] = drop_filter_tick(&d, buf[i]);
    }
}

// ── Drop: STUTTER ────────────────────────────────────────────────────
// Beat-synced gate. Division ramps 1/8 → 1/16 → 1/32 over kStutterRampSeconds.
static void drop_process_stutter(TurntableDeck* deck, float* buf, int n, float sr,
                                 float beat_phase) {
    auto& d = deck->drop;
    float ramp_per_sample = 1.0f / (kStutterRampSeconds * sr);
    for (int i = 0; i < n; i++) {
        d.phase += ramp_per_sample;
        if (d.phase > 1.0f) d.phase = 1.0f;

        int div_idx = static_cast<int>(d.phase * 3.0f);  // 0, 1, or 2
        if (div_idx > 2) div_idx = 2;
        d.stutter_div_idx = div_idx;

        // Division multiplier: 1/8 = 8 pulses per beat, 1/16 = 16, 1/32 = 32
        float div_mult = static_cast<float>(8 << div_idx);
        float gated_phase = beat_phase * div_mult;
        gated_phase -= std::floor(gated_phase);
        float target_gate = (gated_phase < 0.5f) ? 1.0f : 0.0f;

        // Gate smoothing α = 0.5 is intentionally aggressive (reaches 99% in ~4
        // samples). The "chopped" gate attack is part of the stutter aesthetic —
        // a softer smoother blurs the beat-synced feel into a tremolo. Clicks
        // at the gate edges are acceptable for this effect.
        d.stutter_smoothed_gate += 0.5f * (target_gate - d.stutter_smoothed_gate);
        buf[i] *= d.stutter_smoothed_gate;
    }
}

// ── Drop: FREEZE ─────────────────────────────────────────────────────
// Snapshots a fixed slice and loops it with slow pitch modulation.
static void drop_process_freeze(TurntableDeck* deck, float* buf, int n, float sr) {
    auto& d = deck->drop;
    if (d.freeze_len <= 0) {
        for (int i = 0; i < n; i++) buf[i] = 0.0f;
        return;
    }
    for (int i = 0; i < n; i++) {
        float pitch = 1.0f + kFreezePitchDepth *
            std::sin(kTwoPi * d.freeze_lfo);
        d.freeze_lfo += kFreezeLfoHz / sr;
        if (d.freeze_lfo >= 1.0f) d.freeze_lfo -= 1.0f;

        float read = d.freeze_read;
        int idx = static_cast<int>(d.freeze_start + read) % kTurntableBufSize;
        int nxt = (idx + 1) % kTurntableBufSize;
        float frac = read - std::floor(read);
        buf[i] = deck->buffer[idx] * (1.0f - frac) + deck->buffer[nxt] * frac;

        d.freeze_read += pitch;
        while (d.freeze_read >= d.freeze_len) d.freeze_read -= d.freeze_len;
        while (d.freeze_read < 0.0f)          d.freeze_read += d.freeze_len;
    }
}

// ── Drop: BRAKE ──────────────────────────────────────────────────────
// On kind transition NONE→BRAKE, maybe_transition() seeds brake_read with
// the deck's current read_pos and brake_speed = 1.0. Here we just advance
// the cursor at a decaying speed and read from deck->buffer directly,
// overwriting whatever playback_deck produced.
static void drop_process_brake(TurntableDeck* deck, float* buf, int n, float /*sr*/) {
    auto& d = deck->drop;
    for (int i = 0; i < n; i++) {
        d.brake_speed *= kBrakeDecayPerSample;
        if (d.brake_speed < 1e-4f) {
            buf[i] = 0.0f;
            continue;
        }
        // Scale output by brake_speed so amplitude also decays (vinyl motor losing power)
        buf[i] = cubic_interp(deck->buffer, kTurntableBufSize, d.brake_read) * d.brake_speed;
        d.brake_read = wrap_pos(d.brake_read + d.brake_speed, kTurntableBufSize);
    }
}

// ── Drop: OCTAVE ─────────────────────────────────────────────────────
// Blend-first subharmonic: adds a half-rate-read layer UNDER the normal
// playback, giving an instant sub-bass doubling without replacing the source.
// Note: when the deck's smoothed_velocity is 0 (platter stopped), octave_read
// also freezes — the subharmonic layer naturally stops with the dry playback,
// which is the desired behaviour (no phantom octave while the deck is held).
static void drop_process_octave(TurntableDeck* deck, float* buf, int n, float /*sr*/) {
    auto& d = deck->drop;
    for (int i = 0; i < n; i++) {
        float sub = cubic_interp(deck->buffer, kTurntableBufSize, d.octave_read);
        buf[i] = buf[i] * kOctaveDryMix + sub * kOctaveSubMix;
        // Advance at half the deck's smoothed velocity → plays an octave down.
        d.octave_read = wrap_pos(d.octave_read + deck->smoothed_velocity * 0.5f,
                                 kTurntableBufSize);
    }
}

// ── Drop: PHASER ─────────────────────────────────────────────────────
// 4-stage first-order allpass cascade with an LFO-modulated coefficient.
// Canonical Direct-Form II allpass per stage:
//   w[n] = x[n] - a * w[n-1]
//   y[n] = a * w[n] + w[n-1]
// Coefficient `a` is derived from cutoff: a = (tan(ω/2) - 1) / (tan(ω/2) + 1),
// clamped away from ±1 to keep the feedback loop stable during the sweep.
// Dry + wet mix preserves the original signal; small feedback gives depth.
static void drop_process_phaser(TurntableDeck* deck, float* buf, int n, float sr) {
    auto& d = deck->drop;
    const float pi_over_sr = kPi / sr;
    for (int i = 0; i < n; i++) {
        // LFO phase in [0,1)
        d.phaser_lfo_phase += kPhaserLfoHz / sr;
        if (d.phaser_lfo_phase >= 1.0f) d.phaser_lfo_phase -= 1.0f;
        float lfo = 0.5f * (1.0f + std::sin(kTwoPi * d.phaser_lfo_phase));
        float cutoff = kPhaserMinHz * std::pow(kPhaserMaxHz / kPhaserMinHz, lfo);
        float t = std::tan(pi_over_sr * cutoff);
        float a = (t - 1.0f) / (t + 1.0f);
        if (a >  0.99f) a =  0.99f;
        if (a < -0.99f) a = -0.99f;

        // Input + scaled previous feedback
        float x = buf[i] + d.phaser_feedback * kPhaserFeedback;
        float y = x;
        for (int s = 0; s < kPhaserStages; s++) {
            float w_new = y - a * d.phaser_stage[s];
            float out   = a * w_new + d.phaser_stage[s];
            d.phaser_stage[s] = w_new;
            y = out;
        }
        // Flush denormals to zero so the feedback loop can't accumulate tiny
        // drifting values into Inf/NaN during long sweeps.
        if (std::fabs(y) < 1e-20f) y = 0.0f;
        d.phaser_feedback = y;
        buf[i] = buf[i] * (1.0f - kPhaserWetMix) + y * kPhaserWetMix;
    }
}

// ── Drop: ECHO ───────────────────────────────────────────────────────
// Dub-style delay. Reads kEchoDelaySamples behind the write head with feedback,
// mixes wet into the output while the dry passes through.
static void drop_process_echo(TurntableDeck* deck, float* buf, int n, float /*sr*/) {
    auto& d = deck->drop;
    const int delay = kEchoDelaySamples;
    for (int i = 0; i < n; i++) {
        int read_idx = d.echo_write - delay;
        if (read_idx < 0) read_idx += kEchoBufSize;
        float echoed = d.echo_buffer[read_idx];
        float dry = buf[i];
        // Write dry + feedback into the delay line
        d.echo_buffer[d.echo_write] = dry + echoed * kEchoFeedback;
        d.echo_write = (d.echo_write + 1) % kEchoBufSize;
        buf[i] = dry + echoed * kEchoWetMix;
    }
}

// ── Drop: RING ───────────────────────────────────────────────────────
// Ring modulation with a sine carrier that sweeps kRingMinHz↔kRingMaxHz.
// Mix 35% dry + 65% modulated so the source remains recognisable.
static void drop_process_ring(TurntableDeck* deck, float* buf, int n, float sr) {
    auto& d = deck->drop;
    for (int i = 0; i < n; i++) {
        // Sweep carrier across [min, max] via triangle so the sweep reverses.
        d.ring_sweep_phase += kRingSweepHz / sr;
        if (d.ring_sweep_phase >= 1.0f) d.ring_sweep_phase -= 1.0f;
        float tri = (d.ring_sweep_phase < 0.5f)
            ? (d.ring_sweep_phase * 2.0f)
            : (2.0f - d.ring_sweep_phase * 2.0f);
        float carrier_hz = kRingMinHz + (kRingMaxHz - kRingMinHz) * tri;

        d.ring_phase += kTwoPi * carrier_hz / sr;
        if (d.ring_phase >= kTwoPi) d.ring_phase -= kTwoPi;

        float mod = std::sin(d.ring_phase);
        buf[i] = buf[i] * kRingDryMix + buf[i] * mod * kRingWetMix;
    }
}

// Dispatch
void drop_process(TurntableDeck* deck, float* buf, int n, float sr, float beat_phase) {
    switch (deck->drop.kind) {
        case DROP_NONE:    return;
        case DROP_FILTER:  drop_process_filter(deck, buf, n, sr); break;
        case DROP_BRAKE:   drop_process_brake(deck, buf, n, sr); break;
        case DROP_STUTTER: drop_process_stutter(deck, buf, n, sr, beat_phase); break;
        case DROP_FREEZE:  drop_process_freeze(deck, buf, n, sr); break;
        case DROP_OCTAVE:  drop_process_octave(deck, buf, n, sr); break;
        case DROP_PHASER:  drop_process_phaser(deck, buf, n, sr); break;
        case DROP_ECHO:    drop_process_echo(deck, buf, n, sr); break;
        case DROP_RING:    drop_process_ring(deck, buf, n, sr); break;
        default:           return;
    }
    // Global output boost so drops cut through the mix. Applied after the
    // per-kind processor so BRAKE's amplitude taper and STUTTER's gate scale
    // proportionally rather than distorting the kind-specific dynamics.
    for (int i = 0; i < n; i++) buf[i] *= kDropOutputGain;
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

    // Beat phase: advance per block at a tempo sourced from Pulsar (if playing)
    // or the free-run fallback (if idle). Always produces a usable 0..1 value.
    {
        float phase = engine->beat_phase.load(std::memory_order_relaxed);
        float hz;
        if (engine->pulsar_playing.load(std::memory_order_relaxed) != 0) {
            float bpm_override = engine->pulsar_bpm_override.load(std::memory_order_relaxed);
            float bpm = (bpm_override > 0.0f)
                ? bpm_override
                : engine->clock_bpm.load(std::memory_order_relaxed);
            if (bpm < 20.0f) bpm = 120.0f;
            hz = bpm / 60.0f;  // beats per second
        } else {
            hz = kFreeRunBeatPhaseHz;
        }
        phase += static_cast<float>(num_frames) * hz / sample_rate;
        if (phase >= 1.0f) phase -= std::floor(phase);
        engine->beat_phase.store(phase, std::memory_order_relaxed);
        engine->viz_rings[VIZ_BEAT_PHASE].write(phase);
    }

    // Detect NONE→X (and X→NONE and X→Y) drop kind transitions and seed
    // kind-specific state before processing. Callers (port routing) set
    // the target via `turntable_drop_a/b`; on change we reset ONLY the
    // fields the incoming kind will read, keeping the audio-thread cost
    // proportional to the kind (ECHO zeroes its 96 KB buffer, all others
    // touch only a handful of floats). This avoids memsetting the full
    // DropContext (~94 KB) on every transition.
    int target_drop_a = engine->turntable_drop_a.load(std::memory_order_relaxed);
    int target_drop_b = engine->turntable_drop_b.load(std::memory_order_relaxed);
    auto maybe_transition = [](TurntableDeck& dk, int target) {
        DropKind k = static_cast<DropKind>(target);
        if (dk.drop.kind == k) return;
        dk.drop.kind  = k;
        dk.drop.phase = 0.0f;  // used by FILTER build-up and STUTTER ramp
        switch (k) {
            case DROP_NONE:
                break;
            case DROP_FILTER:
                dk.drop.lfo_phase = 0.0f;
                dk.drop.bq_s1 = dk.drop.bq_s2 = 0.0f;
                dk.drop.b0 = dk.drop.b1 = dk.drop.b2 = 0.0f;
                dk.drop.a1 = dk.drop.a2 = 0.0f;
                break;
            case DROP_BRAKE:
                dk.drop.brake_read  = dk.read_pos;
                dk.drop.brake_speed = 1.0f;
                break;
            case DROP_STUTTER:
                dk.drop.stutter_div_idx       = 0;
                dk.drop.stutter_smoothed_gate = 1.0f;
                break;
            case DROP_FREEZE: {
                int start = dk.write_pos - kFreezeSliceSamples;
                if (start < 0) start += kTurntableBufSize;
                dk.drop.freeze_start = start;
                dk.drop.freeze_len   = kFreezeSliceSamples;
                dk.drop.freeze_read  = 0.0f;
                dk.drop.freeze_lfo   = 0.0f;
                break;
            }
            case DROP_OCTAVE:
                // Sub cursor starts alongside the deck's read head.
                dk.drop.octave_read = dk.read_pos;
                break;
            case DROP_PHASER:
                for (int s = 0; s < kPhaserStages; s++) dk.drop.phaser_stage[s] = 0.0f;
                dk.drop.phaser_lfo_phase = 0.0f;
                dk.drop.phaser_feedback  = 0.0f;
                break;
            case DROP_ECHO:
                // Flush the 24000-float delay buffer so a re-entry to ECHO doesn't
                // replay a stale echo tail from a prior session. This is the one
                // transition that incurs a large memset (~96 KB) by design.
                std::memset(dk.drop.echo_buffer, 0, sizeof(dk.drop.echo_buffer));
                dk.drop.echo_write = 0;
                break;
            case DROP_RING:
                dk.drop.ring_phase       = 0.0f;
                dk.drop.ring_sweep_phase = 0.0f;
                break;
            default: break;
        }
    };
    maybe_transition(engine->turntable_decks[0], target_drop_a);
    maybe_transition(engine->turntable_decks[1], target_drop_b);

    // Sum Pulsar stereo to mono for TT_SOURCE_SUM
    float pulsar_sum[kMaxFrames];
    for (int i = 0; i < num_frames; i++) {
        pulsar_sum[i] = (engine->pulsar_out_l[i] + engine->pulsar_out_r[i]) * 0.5f;
    }

    // Get source buffers (double-buffered reads from previous frame)
    auto get_source = [&](int source) -> const float* {
        switch (source) {
            case TT_SOURCE_SYNTH:  return engine->warps_synth_read;
            case TT_SOURCE_DRUMS:  return engine->warps_drums_read;
            case TT_SOURCE_BASS:   return engine->warps_bass_read;
            case TT_SOURCE_MASTER: return engine->turntable_prev_master;
            case TT_SOURCE_SUM:    return pulsar_sum;
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
    playback_deck(&deck_a, vel_a, play_a, num_frames, sample_rate);
    playback_deck(&deck_b, vel_b, play_b, num_frames, sample_rate);

    float beat_phase_now = engine->beat_phase.load(std::memory_order_relaxed);
    drop_process(&deck_a, play_a, num_frames, sample_rate, beat_phase_now);
    drop_process(&deck_b, play_b, num_frames, sample_rate, beat_phase_now);

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
        float fa = fader_ease(wet_a);
        float fb = fader_ease(wet_b);
        out[i] = soft_limit(play_a[i] * boost_a * fa + play_b[i] * boost_b * fb);
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
