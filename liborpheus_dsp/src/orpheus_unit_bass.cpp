#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include "orpheus_viz.h"
#include <cstring>
#include <cmath>
#include <algorithm>

static inline float* resolve_flux_t(OrpheusEngine* engine, int src) {
    switch (src) {
        case 1: return engine->marbles_t1_buffer;
        case 2: return engine->marbles_t2_buffer;
        case 3: return engine->marbles_t3_buffer;
        default: return nullptr;
    }
}

static inline float* resolve_flux_x(OrpheusEngine* engine, int src) {
    switch (src) {
        case 1: return engine->marbles_x1_buffer;
        case 2: return engine->marbles_x2_buffer;
        case 3: return engine->marbles_x3_buffer;
        default: return nullptr;
    }
}

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
    seq.jitter_offset = 0;
    seq.jitter_hold_samples = 0;
    seq.jitter_hold_counter = 0;
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
                              bool accent, float accent_amount, float sample_rate) {
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

    // Accent: snappier attack + extended decay for punchy "flare" transient
    if (accent) {
        float speed_factor = 1.0f - 0.6f * accent_amount;  // up to 60% faster attack
        attack_ms *= speed_factor;
        // Extend decay slightly so the accent ring is audible, not just a click
        decay_ms *= 1.0f + 0.4f * accent_amount;
    }

    float attack_samples = attack_ms * 0.001f * sample_rate;
    float decay_samples = decay_ms * 0.001f * sample_rate;

    // Per-sample coefficient for exponential curves
    float attack_coeff = (attack_samples > 1.0f) ? (1.0f - std::exp(-1.0f / (attack_samples * 0.3f))) : 1.0f;
    float decay_coeff = (decay_samples > 1.0f) ? (1.0f - std::exp(-1.0f / (decay_samples * 0.3f))) : 1.0f;

    // Sustain floor while gate is held: low envelope = high sustain (full bass),
    // high envelope = no sustain (percussive AD). This keeps the note alive
    // through slide transitions so portamento has signal to glide.
    float sustain_level = gate ? 0.6f * (1.0f - envelope_param) : 0.0f;

    switch (seq.env_stage) {
        case 1: // attack
            seq.env_level += attack_coeff * (1.1f - seq.env_level);
            if (seq.env_level >= 1.0f) {
                seq.env_level = 1.0f;
                seq.env_stage = 2;  // decay
            }
            break;
        case 2: // decay
            // Decay toward sustain floor (0 when gate off, up to 0.6 when gate on)
            seq.env_level += decay_coeff * (sustain_level - seq.env_level);
            if (!gate && seq.env_level < 0.001f) {
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

    // Accent: boost output with soft saturation (no hard clamp = no click)
    if (accent) {
        level *= 1.0f + 0.6f * accent_amount;
        // Soft saturate instead of hard clamp — preserves envelope shape continuity
        if (level > 1.0f) {
            level = 1.0f + std::tanh(level - 1.0f) * 0.15f;  // gentle overshoot
        }
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
// Master clock runs at 24 PPQN. 1x = quarter notes (1 step per beat).
// Bass clock_div:
//   0 = 1x   → 1 step per beat    = 24 ticks (quarter notes)
//   1 = 2x   → 2 steps per beat   = 12 ticks (8th notes)
//   2 = 4x   → 4 steps per beat   =  6 ticks (16th notes, matches Grids)
//   3 = 8x   → 8 steps per beat   =  3 ticks (32nd notes)
//   4 = 16x  → 16 steps per beat  =  1 tick  (64th notes, fastest)
static const int kTicksPerStep[5] = { 24, 12, 6, 3, 1 };

// ── Step advance helper ──────────────────────────────────────────────
// Called when the sequencer clock signals a step advance.
// Updates seq.current_step, applies per-cycle mutation, returns new step index.
// When jitter > 0, computes a per-step timing offset and gate hold duration.
static int advance_step(BassSequencerState& seq, int step_count, float mutation,
                        float jitter, int samples_per_step) {
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

    // ── Jitter: randomize timing offset and gate hold duration ──
    if (jitter > 0.001f) {
        // Time jitter: ±15% of step period at max jitter
        seq.rng_state = bass_rng_next(seq.rng_state);
        float r = bass_rng_float(seq.rng_state) * 2.0f - 1.0f;  // [-1, +1)
        seq.jitter_offset = static_cast<int>(r * jitter * 0.15f * samples_per_step);

        // Hold jitter: gate duration varies from 50% to 100% of step period.
        // At jitter=0, hold = full step (no early gate-off).
        // At jitter=1, hold ranges randomly from 50% to 100% of step.
        seq.rng_state = bass_rng_next(seq.rng_state);
        float hold_rand = bass_rng_float(seq.rng_state);  // [0, 1)
        float min_hold = 1.0f - 0.5f * jitter;  // 1.0 at jitter=0, 0.5 at jitter=1
        float hold_frac = min_hold + hold_rand * (1.0f - min_hold);
        seq.jitter_hold_samples = static_cast<int>(hold_frac * samples_per_step);
        seq.jitter_hold_counter = 0;
    } else {
        seq.jitter_offset = 0;
        seq.jitter_hold_samples = 0;  // 0 = full step (no early cutoff)
        seq.jitter_hold_counter = 0;
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
        engine->viz_rings[VIZ_BASS_OUT].write(0.0f);
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
    int trigger_src = engine->bass_trigger_source.load(std::memory_order_relaxed);
    int pitch_src   = engine->bass_pitch_source.load(std::memory_order_relaxed);
    int timbre_src  = engine->bass_timbre_source.load(std::memory_order_relaxed);
    float accent_amount = engine->bass_accent_amount.load(std::memory_order_relaxed);
    float jitter = engine->bass_jitter.load(std::memory_order_relaxed);
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
    // Three-tier gate: <=0.3 rest, 0.3-0.7 slide (legato+portamento), >0.7 normal trigger
    int step = seq.current_step % step_count;
    float pitch_value = seq.mutation_buffer[step];
    float gate_val    = seq.gate_buffer[step];
    bool  step_gate   = gate_val > 0.3f;
    bool  step_slide  = gate_val > 0.3f && gate_val <= 0.7f;
    bool  step_accent = seq.accent_buffer[step] > 0.7f;  // ~30% chance of accent

    // Detect whether a new step fired this block (for rising-edge trigger)
    bool new_step_fired = false;

    if (clock_running && !key_override) {
        int remaining = num_frames;
        while (remaining > 0) {
            // Apply time jitter: shift the step boundary by jitter_offset samples
            int effective_period = samples_per_step + seq.jitter_offset;
            if (effective_period < 4) effective_period = 4;  // safety floor

            int until_next = effective_period - seq.tick_counter;
            if (until_next <= remaining) {
                // Step fires within this block
                seq.tick_counter = 0;
                remaining -= until_next;

                step = advance_step(seq, step_count, mutation, jitter, samples_per_step);

                pitch_value = seq.mutation_buffer[step];
                gate_val    = seq.gate_buffer[step];
                step_gate   = gate_val > 0.3f;
                step_slide  = gate_val > 0.3f && gate_val <= 0.7f;
                step_accent = seq.accent_buffer[step] > 0.7f;
                new_step_fired = true;
            } else {
                seq.tick_counter += remaining;
                remaining = 0;
            }
        }

        // Hold jitter: count elapsed samples and turn gate off early if hold expired
        if (seq.jitter_hold_samples > 0) {
            seq.jitter_hold_counter += num_frames;
            if (seq.jitter_hold_counter >= seq.jitter_hold_samples && !new_step_fired) {
                step_gate = false;  // gate off early — note cuts short
            }
        }
    }

    // ── Compute note for active step (with portamento on slide steps) ──
    float target_note;
    if (key_override) {
        target_note = key_pitch;
        step_gate = true;   // keyboard always gates
        step_slide = false; // keyboard always snaps
        step_accent = false;
    } else {
        target_note = quantize_to_scale(pitch_value, root_note, scale_idx);
    }

    // Portamento: slide steps glide toward target, normal steps snap
    // Glide time shaped by envelope knob: low=80ms (rubbery), high=10ms (zippy)
    if (seq.smooth_note == 0.0f) {
        seq.smooth_note = target_note;  // init on first render
    }
    if (step_slide && step_gate) {
        float glide_ms = 80.0f * std::exp(-2.1f * envelope_param);
        float glide_samples = glide_ms * 0.001f * sample_rate;
        float glide_coeff = (glide_samples > 1.0f) ? (1.0f - std::exp(-1.0f / (glide_samples * 0.3f))) : 1.0f;
        // Apply per-block smoothing (block-rate is sufficient for pitch glide)
        seq.smooth_note += glide_coeff * num_frames * (target_note - seq.smooth_note);
    } else {
        seq.smooth_note = target_note;
    }
    float note = seq.smooth_note;

    // ── Set accent boosts ──
    engine->bass_accent_drive_boost = step_accent ? (0.3f * accent_amount) : 0.0f;

    // Accent cutoff flare: target is +0.35 timbre boost on accented steps.
    // Smoothed with fast attack (~2ms) / slow decay (~60ms) for the classic
    // accent sweep — filter snaps open then slowly closes back down.
    float accent_timbre_target = step_accent ? (0.35f * accent_amount) : 0.0f;
    float flare_attack = 1.0f - std::exp(-1.0f / (0.002f * sample_rate));  // ~2ms
    float flare_decay  = 1.0f - std::exp(-1.0f / (0.06f * sample_rate));   // ~60ms
    float flare_coeff = (accent_timbre_target > engine->bass_accent_timbre_boost)
                        ? flare_attack : flare_decay;
    engine->bass_accent_timbre_boost += flare_coeff * (accent_timbre_target - engine->bass_accent_timbre_boost);

    // ── Determine gate to pass to voice ──
    int render_gate;
    float* ext_t = resolve_flux_t(engine, trigger_src);

    if (key_override) {
        render_gate = 1;
    } else if (!clock_running) {
        render_gate = 0;
    } else if (ext_t != nullptr) {
        // External Flux T gates the envelope — sequencer still advances for pitch
        int mid = num_frames / 2;
        render_gate = (ext_t[mid] > 0.5f) ? 1 : 0;
    } else if (new_step_fired && step_gate) {
        render_gate = 1;
    } else if (step_gate) {
        render_gate = 1;
    } else {
        render_gate = 0;
    }

    // ── LFO modulation ──
    // Bass repurposes the global LFO buffers for its own parameter mapping:
    //   lfo_output_buffer    → cutoff (timbre)
    //   lfo_morph_buffer     → resonance (harmonics)
    //   lfo_pitch_buffer     → pitch
    //   lfo_harmonics_buffer → envelope (reduced depth to avoid clicks)
    float lfo_depth = engine->bass_lfo_mix.load(std::memory_order_relaxed);
    float mod_timbre = 0.0f, mod_harmonics = 0.0f, mod_pitch = 0.0f, mod_envelope = 0.0f;
    if (lfo_depth > 0.001f) {
        // Sample LFO at block midpoint (block-rate modulation, matching Plaits pattern)
        int mid = num_frames / 2;
        mod_timbre    = engine->lfo_output_buffer[mid]     * lfo_depth * 0.5f;
        mod_harmonics = engine->lfo_morph_buffer[mid]      * lfo_depth * 0.5f;
        mod_pitch     = engine->lfo_pitch_buffer[mid]      * lfo_depth * 0.5f;  // ±0.5 semitone
        mod_envelope  = engine->lfo_harmonics_buffer[mid]  * lfo_depth * 0.3f;  // reduced to avoid envelope pop
    }

    // NOTE: Tides modulation of bass was removed — it applied unconditionally
    // whenever tides_mix > 0, modifying bass pitch/timbre/harmonics without
    // user consent. If Tides→Bass modulation is desired in the future, it
    // should be gated by an explicit mod source selector (like Plaits has).

    // Apply pitch modulation
    note += mod_pitch;

    // ── Flux X pitch modulation ──
    float* ext_x = resolve_flux_x(engine, pitch_src);
    if (ext_x != nullptr) {
        // X buffer contains exp2(v)-1 values (frequency ratio offset).
        // Convert back to semitones: 12 * log2(1 + x_val)
        int mid = num_frames / 2;
        float x_val = ext_x[mid];
        if (x_val > -0.99f) {
            float semitones = 12.0f * std::log2f(1.0f + x_val);
            note += semitones;
        }
    }

    // Apply envelope modulation
    float modulated_envelope = std::max(0.0f, std::min(1.0f, envelope_param + mod_envelope));

    // ── Render voice ──
    // Force retrigger on new step (or Flux T rising edge): reset both OrpheusVoice's
    // Schmitt trigger AND the envelope's gate state so both see a fresh rising edge.
    // Without this, all-gates-on patterns never retrigger after the first note.
    bool retrigger = false;
    if (ext_t != nullptr) {
        // Detect rising edge in T buffer for retrigger
        bool t_rising = (ext_t[num_frames / 2] > 0.5f) && (ext_t[0] <= 0.5f);
        if (t_rising) {
            engine->bass_voice.trigger_state_ = false;
            engine->bass_voice.remainder_count_ = 0;  // discard stale pre-trigger samples
            seq.env_gate_prev = false;
            retrigger = true;
        }
    } else if (new_step_fired && step_gate && !step_slide) {
        // Normal trigger: retrigger voice + envelope for fresh attack.
        // Clear the remainder buffer to avoid a phase discontinuity:
        // the remainder contains samples rendered at the OLD note's oscillator
        // phase; draining them before the trigger fires creates a click.
        engine->bass_voice.trigger_state_ = false;
        engine->bass_voice.remainder_count_ = 0;
        seq.env_gate_prev = false;
        retrigger = true;
    }
    // Slide steps: no retrigger — envelope continues (legato), pitch glides

    // Apply cutoff/resonance modulation to voice params
    float timbre_val = std::max(0.0f, std::min(1.0f,
        bp.timbre.load(std::memory_order_relaxed) + mod_timbre));

    // Remap RESO knob for the VCF engine.
    // The VCF engine's resonance is V-shaped: |harmonics - 0.5| controls Q,
    // so harmonics=0.0 and 1.0 both give MAXIMUM resonance, while 0.5 gives
    // zero. This is confusing for a bass RESO knob where 0 should mean "off".
    // Remap: RESO 0→0.5 (no resonance), RESO 1→0.0 (max resonance).
    float raw_reso = bp.harmonics.load(std::memory_order_relaxed);
    float harmonics_val;
    if (bass_engine == 0) {
        // VCF Acid: remap so RESO 0=off, RESO 1=max
        harmonics_val = 0.5f * (1.0f - raw_reso);
    } else {
        harmonics_val = raw_reso;
    }
    harmonics_val = std::max(0.0f, std::min(1.0f, harmonics_val + mod_harmonics));

    // ── Flux Y timbre modulation ──
    if (timbre_src > 0) {
        // Y buffer is clamped [-1, +1] by unit_process_marbles.
        // Scale to ±0.3 for subtle tonal variation without extreme jumps.
        int mid = num_frames / 2;
        float y_val = engine->marbles_y_buffer[mid];
        timbre_val = std::max(0.0f, std::min(1.0f, timbre_val + y_val * 0.3f));
    }

    // Smooth timbre/harmonics to prevent filter coefficient clicks (~5ms).
    // smooth_coeff() is per-sample; scale by num_frames for block-rate application
    // (same approximation used by portamento's glide_coeff * num_frames).
    float tc = smooth_coeff(sample_rate) * num_frames;
    if (tc > 1.0f) tc = 1.0f;  // clamp for very large blocks
    engine->bass_smooth_timbre += tc * (timbre_val - engine->bass_smooth_timbre);
    engine->bass_smooth_harmonics += tc * (harmonics_val - engine->bass_smooth_harmonics);

    // Apply accent cutoff flare on top of smoothed timbre (post-smooth so the
    // flare envelope shape isn't smeared by the parameter smoother)
    float render_timbre = std::min(1.0f, engine->bass_smooth_timbre + engine->bass_accent_timbre_boost);

    float raw_out[kMaxFrames];
    engine->bass_voice.Render(
        plaits_engine_index,
        render_gate,
        note,
        engine->bass_smooth_harmonics,
        render_timbre,
        bp.morph.load(std::memory_order_relaxed),
        bp.accent.load(std::memory_order_relaxed),
        raw_out,
        num_frames
    );

    // ── Apply envelope, output gain, and mix ──
    // Bass voice needs headroom boost: Plaits output is soft-limited to ~±1,
    // envelope scales 0-1, so raw output is quiet relative to the 12 main voices
    // which get summed in the duo mixer. The master limiter handles peaks, so
    // this gain just sets the bass-to-voice balance.
    static constexpr float kBassOutputGain = 1.0f;

    float* out = u->output_buffers[OPORT_OUT];

    // One-pole LPF to catch retrigger transients.
    // OrpheusVoice bypasses Plaits' LPG, which normally filters the raw engine
    // output on retrigger. Without it, oscillator phase resets produce ultrasonic
    // click energy. A gentle lowpass (~12kHz) absorbs this without affecting
    // the bass tone. Coefficient: 1 - e^(-2π·fc/fs) ≈ 0.79 at 12kHz/48kHz.
    float lpf_coeff = 1.0f - std::exp(-2.0f * 3.14159265f * 12000.0f / sample_rate);
    float lpf = engine->bass_lpf_state;

    for (int i = 0; i < num_frames; i++) {
        smooth_mix += mix_coeff * (target_mix - smooth_mix);

        float sample = raw_out[i];

        if (use_external_envelope) {
            bool gate_for_env = (render_gate != 0);
            float env = process_envelope(seq, gate_for_env, modulated_envelope, step_accent, accent_amount, sample_rate);
            sample *= env;
        }

        // Apply one-pole LPF to raw sample (before gain/mix) to catch retrigger clicks
        // at the source. Filtering after mix would cause the LPF state to lag during
        // mix ramps (bypass transitions), producing brief volume swells.
        lpf += lpf_coeff * (sample - lpf);
        out[i] = lpf * kBassOutputGain * smooth_mix;
    }

    engine->bass_lpf_state = lpf;
    engine->bass_prev_output = out[num_frames - 1];
    engine->bass_smooth_mix = smooth_mix;

    // Write to Warps source buffer (slot 9 = BASS)
    // Uses post-envelope output (before overdrive/compressor graph units)
    std::memcpy(engine->warps_source_buffers[9], out, num_frames * sizeof(float));

    // Write visualization data (one peak per block)
    float viz_peak = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float a = std::fabs(out[i]);
        if (a > viz_peak) viz_peak = a;
    }
    engine->viz_rings[VIZ_BASS_OUT].write(viz_peak);
}
