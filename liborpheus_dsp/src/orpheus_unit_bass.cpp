#include "orpheus_units.h"
#include "orpheus_engine.h"
#include "orpheus_viz.h"
#include <cstring>
#include <cmath>
#include <algorithm>

// ── Sequencer helpers ───────────────────────────────────────────────

static void init_sequencer(BassSequencerState& seq) {
    seq.rng_state = 0xDEADBEEF;

    // Start with a DRIVING pattern, not random chaos.
    // All gates ON for relentless bass, accents on downbeats (1 & 3),
    // root note on downbeats, random scale notes on off-beats.
    // Mutation evolves FROM this structured seed.
    for (int i = 0; i < kMaxBassSteps; i++) {
        bool is_downbeat = (i % 4 == 0);
        bool is_backbeat = (i % 4 == 2);

        // Pitch: root (0.0) on downbeats, random scale notes on off-beats
        if (is_downbeat) {
            seq.mutation_buffer[i] = 0.0f;  // root note
        } else {
            seq.rng_state = bass_rng_next(seq.rng_state);
            seq.mutation_buffer[i] = bass_rng_float(seq.rng_state);
        }

        // Gates: ALL on for driving rhythm (value > 0.3 = gate on)
        seq.gate_buffer[i] = 0.9f;

        // Accents: on beats 1 and 3 (downbeat + backbeat)
        seq.accent_buffer[i] = (is_downbeat || is_backbeat) ? 0.9f : 0.2f;
    }

    seq.current_step = 0;
    seq.tick_counter = 0;
    seq.env_level = 0.0f;
    seq.env_stage = 0;
    seq.env_gate_prev = false;
    seq.initialized = true;
}

static void mutate_step(BassSequencerState& seq, int step, float mutation) {
    // mutation 0-0.5: pitch only, 0.5-1.0: gates and accents too
    float pitch_prob = mutation * 2.0f;  // 0-1 over first half
    if (pitch_prob > 1.0f) pitch_prob = 1.0f;

    seq.rng_state = bass_rng_next(seq.rng_state);
    float r = bass_rng_float(seq.rng_state);
    if (r < pitch_prob) {
        seq.rng_state = bass_rng_next(seq.rng_state);
        seq.mutation_buffer[step] = bass_rng_float(seq.rng_state);
    }

    if (mutation > 0.5f) {
        float gate_prob = (mutation - 0.5f) * 2.0f;  // 0-1 over second half
        seq.rng_state = bass_rng_next(seq.rng_state);
        r = bass_rng_float(seq.rng_state);
        if (r < gate_prob) {
            seq.rng_state = bass_rng_next(seq.rng_state);
            seq.gate_buffer[step] = bass_rng_float(seq.rng_state);
        }
        seq.rng_state = bass_rng_next(seq.rng_state);
        r = bass_rng_float(seq.rng_state);
        if (r < gate_prob) {
            seq.rng_state = bass_rng_next(seq.rng_state);
            seq.accent_buffer[step] = bass_rng_float(seq.rng_state);
        }
    }
}

// Map random float [0,1) to MIDI note within 2 octaves of root using scale
static float quantize_to_scale(float value, int root_note, int scale_idx) {
    if (scale_idx < 0) scale_idx = 0;
    if (scale_idx >= kBassScaleCount) scale_idx = kBassScaleCount - 1;

    const BassScale& scale = bass_scales[scale_idx];

    // Map to 2 octaves worth of scale degrees
    int total_degrees = scale.count * 2;
    int degree = static_cast<int>(value * total_degrees);
    if (degree >= total_degrees) degree = total_degrees - 1;

    int octave = degree / scale.count;
    int scale_degree = degree % scale.count;

    return static_cast<float>(root_note + octave * 12 + scale.intervals[scale_degree]);
}

// ── Envelope (Peaks-inspired) ───────────────────────────────────────

static float process_envelope(BassSequencerState& seq, bool gate, float envelope_param,
                              bool accent, float sample_rate) {
    // Detect rising edge
    bool rising = gate && !seq.env_gate_prev;
    seq.env_gate_prev = gate;

    if (rising) {
        seq.env_stage = 1;  // attack
    }

    // Time parameters controlled by single knob
    // attack_ms: 20ms at 0.0 -> 0.5ms at 1.0
    float attack_ms = 20.0f * std::exp(-3.7f * envelope_param);  // ~20 -> ~0.5
    // decay_ms: 500ms at 0.0 -> 50ms at 1.0
    float decay_ms = 500.0f * std::exp(-2.3f * envelope_param);  // ~500 -> ~50

    // Accent: faster times
    if (accent) {
        attack_ms *= 0.67f;
        decay_ms *= 0.67f;
    }

    float attack_samples = attack_ms * 0.001f * sample_rate;
    float decay_samples = decay_ms * 0.001f * sample_rate;

    // Per-sample coefficient for exponential curves
    float attack_coeff = (attack_samples > 1.0f) ? (1.0f - std::exp(-1.0f / (attack_samples * 0.3f))) : 1.0f;
    float decay_coeff = (decay_samples > 1.0f) ? (1.0f - std::exp(-1.0f / (decay_samples * 0.3f))) : 1.0f;

    switch (seq.env_stage) {
        case 1: // attack
            seq.env_level += attack_coeff * (1.1f - seq.env_level);
            if (seq.env_level >= 1.0f) {
                seq.env_level = 1.0f;
                seq.env_stage = 2;  // decay
            }
            break;
        case 2: // decay
            seq.env_level -= decay_coeff * seq.env_level;
            if (seq.env_level < 0.001f) {
                seq.env_level = 0.0f;
                seq.env_stage = 0;  // idle
            }
            break;
        default: // idle
            seq.env_level *= 0.999f;  // gentle fade to zero
            break;
    }

    float level = seq.env_level;

    // Quartic shaping at high envelope values (snappy)
    if (envelope_param > 0.5f) {
        float blend = (envelope_param - 0.5f) * 2.0f;  // 0-1
        float quartic = level * level * level * level;
        level = level * (1.0f - blend) + quartic * blend;
    }

    // Accent: boost output
    if (accent) {
        level *= 1.5f;
        if (level > 1.0f) level = 1.0f;
    }

    return level;
}

// ── Bass engine index mapping ───────────────────────────────────────
// bass_engine: 0=VCF, 1=PD, 2=FM, 3=BassDrum
static int bass_engine_to_plaits(int bass_engine) {
    switch (bass_engine) {
        case 0: return 0;   // VirtualAnalogVCF
        case 1: return 1;   // PhaseDistortion
        case 2: return 10;  // FM
        case 3: return 21;  // BassDrum
        default: return 0;
    }
}

// ── Ticks per step for each clock_div setting ───────────────────────
// Master clock runs at 24 PPQN. Grids advances every 6 ticks = 16th notes.
// Bass clock_div:
//   0 = 1/4  → 1 step per beat    = 24 ticks (quarter notes)
//   1 = 1/2  → 2 steps per beat   = 12 ticks (8th notes)
//   2 = 1x   → 4 steps per beat   =  6 ticks (16th notes, matches Grids)
//   3 = 2x   → 8 steps per beat   =  3 ticks (32nd notes)
//   4 = 4x   → 16 steps per beat  =  1 tick  (64th notes, fastest)
static const int kTicksPerStep[5] = { 24, 12, 6, 3, 1 };

// ── Step advance helper ──────────────────────────────────────────────
// Called when the sequencer clock signals a step advance.
// Updates seq.current_step, applies per-cycle mutation, returns new step index.
static int advance_step(BassSequencerState& seq, int step_count, float mutation) {
    seq.current_step++;
    bool cycle_wrap = (seq.current_step >= step_count);
    if (cycle_wrap) {
        seq.current_step = 0;
        // Apply mutation to all steps on cycle wrap
        if (mutation > 0.001f) {
            for (int s = 0; s < step_count; s++) {
                mutate_step(seq, s, mutation);
            }
        }
    }
    return seq.current_step;
}

// ── Main process function ───────────────────────────────────────────

void unit_process_bass_voice(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    auto& bp = engine->bass_params;
    auto& seq = engine->bass_seq_state;

    // ── Mix / bypass ──
    float target_mix = engine->bass_mix.load(std::memory_order_relaxed);
    float smooth_mix = engine->bass_smooth_mix;

    // Smooth mix transitions (~10ms)
    float mix_coeff = 1.0f - std::exp(-1.0f / (0.01f * sample_rate));

    // Check bypass: both target and smoothed mix are near zero
    bool bypassed = (target_mix <= 0.001f && smooth_mix <= 0.001f);
    if (bypassed) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        engine->bass_smooth_mix = 0.0f;
        return;
    }

    // ── Initialize sequencer on first call ──
    if (!seq.initialized) {
        init_sequencer(seq);
    }

    // ── Read parameters ──
    int root_note  = engine->bass_root_note.load(std::memory_order_relaxed);
    int scale_idx  = engine->bass_scale.load(std::memory_order_relaxed);
    int step_count = engine->bass_step_count.load(std::memory_order_relaxed);
    float mutation = engine->bass_mutation.load(std::memory_order_relaxed);
    float envelope_param = engine->bass_envelope.load(std::memory_order_relaxed);
    int bass_engine   = engine->bass_engine.load(std::memory_order_relaxed);
    int key_override  = engine->bass_key_override.load(std::memory_order_relaxed);
    float key_pitch   = engine->bass_key_pitch.load(std::memory_order_relaxed);
    int clock_div     = engine->bass_clock_div.load(std::memory_order_relaxed);
    bool clock_running = engine->clock_running.load(std::memory_order_relaxed) != 0;
    float bpm = engine->clock_bpm.load(std::memory_order_relaxed);

    if (step_count < 1) step_count = 1;
    if (step_count > kMaxBassSteps) step_count = kMaxBassSteps;
    if (clock_div < 0) clock_div = 0;
    if (clock_div > 4) clock_div = 4;
    if (bpm <= 0.0f) bpm = 120.0f;

    // ── Map bass engine to Plaits engine index ──
    int plaits_engine_index = bass_engine_to_plaits(bass_engine);

    // ── Bass drum special case: skip external envelope ──
    bool use_external_envelope = (bass_engine != 3);

    // ── Advance sequencer clock ──────────────────────────────────────
    // We use a sample-based counter (seq.tick_counter) rather than wiring the
    // clock output buffer here. This gives the same timing as the master clock
    // since both use BPM + sample_rate to compute period lengths.
    //
    // samples_per_step: how many samples elapse before advancing one sequencer step.
    //   At 24 PPQN, one 16th-note step = 6 ticks = sample_rate * 60 / (bpm * 4) samples.
    //   General formula: samples_per_step = sample_rate * 60 * ticks_per_step / (bpm * 24)
    //
    // seq.tick_counter is repurposed as a sample sub-counter (0..samples_per_step-1).
    // On each process() call we advance it by num_frames, firing step advances as needed.

    int ticks_per_step = kTicksPerStep[clock_div];
    // Use integer rounding to avoid drift accumulation
    int samples_per_step = static_cast<int>(
        (static_cast<double>(sample_rate) * 60.0 * ticks_per_step) / (static_cast<double>(bpm) * 24.0) + 0.5);
    if (samples_per_step < 1) samples_per_step = 1;

    // Current step state — read from seq, updated when a step fires
    int step = seq.current_step % step_count;
    float pitch_value = seq.mutation_buffer[step];
    bool  step_gate   = seq.gate_buffer[step]   > 0.3f;  // ~70% chance of gate
    bool  step_accent = seq.accent_buffer[step] > 0.7f;  // ~30% chance of accent

    // Detect whether a new step fired this block (for rising-edge trigger)
    bool new_step_fired = false;

    if (clock_running && !key_override) {
        int remaining = num_frames;
        while (remaining > 0) {
            int until_next = samples_per_step - seq.tick_counter;
            if (until_next <= remaining) {
                // Step fires within this block
                seq.tick_counter = 0;
                remaining -= until_next;

                step = advance_step(seq, step_count, mutation);

                pitch_value = seq.mutation_buffer[step];
                step_gate   = seq.gate_buffer[step]   > 0.3f;
                step_accent = seq.accent_buffer[step] > 0.7f;
                new_step_fired = true;
            } else {
                seq.tick_counter += remaining;
                remaining = 0;
            }
        }
    }

    // ── Compute note for active step ──
    float note;
    if (key_override) {
        note = key_pitch;
        step_gate = true;   // keyboard always gates
        step_accent = false;
    } else {
        note = quantize_to_scale(pitch_value, root_note, scale_idx);
    }

    // ── Set accent drive boost for downstream overdrive ──
    engine->bass_accent_drive_boost = step_accent ? 0.3f : 0.0f;

    // ── Determine gate to pass to voice ──────────────────────────────
    // OrpheusVoice::Render() performs internal rising-edge detection.
    // We pass gate=1 on a new-step-with-gate or keyboard press, gate=0 otherwise.
    // This means Render() will see a rising edge on the block where a step fires,
    // triggering the voice even if the previous block also had gate=1 (legato).
    //
    // For accurate triggering: pass gate=1 only when step_gate is true AND a new
    // step just fired (or key_override is active). When clock is stopped or gate
    // is false, pass gate=0 so the voice releases.
    int render_gate;
    if (key_override) {
        render_gate = 1;
    } else if (!clock_running) {
        render_gate = 0;
    } else if (new_step_fired && step_gate) {
        render_gate = 1;
    } else if (step_gate) {
        // Same step continuing — hold gate (sustain until next step)
        render_gate = 1;
    } else {
        render_gate = 0;
    }

    // ── Render voice ──
    // Force retrigger on new step: reset both OrpheusVoice's Schmitt trigger
    // AND the envelope's gate state so both see a fresh rising edge.
    // Without this, all-gates-on patterns never retrigger after the first note.
    if (new_step_fired && step_gate) {
        engine->bass_voice.trigger_state_ = false;
        seq.env_gate_prev = false;  // force envelope retrigger too
    }

    float raw_out[kMaxFrames];
    engine->bass_voice.Render(
        plaits_engine_index,
        render_gate,
        note,
        bp.harmonics.load(std::memory_order_relaxed),
        bp.timbre.load(std::memory_order_relaxed),
        bp.morph.load(std::memory_order_relaxed),
        bp.accent.load(std::memory_order_relaxed),
        raw_out,
        num_frames
    );

    // ── Apply envelope, output gain, and mix ──
    // Bass voice needs headroom boost: Plaits output is soft-limited to ~±1,
    // envelope scales 0-1, so raw output is quiet relative to the 12 main voices
    // which get summed in the duo mixer. 4x (~12dB) brings bass to parity.
    static constexpr float kBassOutputGain = 2.5f;

    float* out = u->output_buffers[OPORT_OUT];

    for (int i = 0; i < num_frames; i++) {
        smooth_mix += mix_coeff * (target_mix - smooth_mix);

        float sample = raw_out[i];

        if (use_external_envelope) {
            bool gate_for_env = (render_gate != 0);
            float env = process_envelope(seq, gate_for_env, envelope_param, step_accent, sample_rate);
            sample *= env;
        }

        out[i] = sample * kBassOutputGain * smooth_mix;
    }

    engine->bass_smooth_mix = smooth_mix;

    // Write visualization data (one peak per block)
    float viz_peak = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float a = std::fabs(out[i]);
        if (a > viz_peak) viz_peak = a;
    }
    engine->viz_rings[VIZ_BASS_OUT].write(viz_peak);
}
