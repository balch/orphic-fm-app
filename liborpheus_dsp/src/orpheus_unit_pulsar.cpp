#include "orpheus_units.h"
#include "orpheus_engine.h"
#include "orpheus_unit_chaos.h"
#include "pulsar_pattern_gen.h"
#include "pulsar_bar_strategy.h"
#include "pulsar_chord_progression.h"
#include "pulsar_section.h"
#include "pulsar_solo.h"
#include "pulsar_band_solo.h"
#include "pulsar_lick_techniques.h"
#include "pulsar_mod_ranges.h"
#include "pulsar_comping.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <chrono>

static constexpr float kTidesNorm = 0.125f;

// ═══════════════════════════════════════════════════════════════════════
// Pulsar Beat Machine — Clock, Sequencer, Voice Rendering
// ═══════════════════════════════════════════════════════════════════════
//
// 8-track step sequencer with OrpheusVoice per track, Tides envelopes,
// algorithmic pattern generation, constant-power panning, and macro modulation.
// Runs entirely on the audio thread. One heap allocation on first call
// (PulsarState with 8 voices); all subsequent processing is RT-safe.
// State is owned by OrpheusEngine and freed in orpheus_engine_destroy().

// ── Helpers ──────────────────────────────────────────────────────────

static inline float lerp_macro(float macro, const PulsarMacroTarget& t) {
    return t.min_value + macro * (t.max_value - t.min_value);
}

// Console fader law for the per-band MixerPanel gains. Stored value is the
// fader's *travel* (0..1); this maps it to the actual amplitude multiplier
// using a piecewise-linear-in-dB curve modeled on a Penny & Giles broadcast
// fader (gentler than a Yamaha/Mackie law — 50% travel reads as half-loud,
// not heavily-cut):
//   travel  0.00 → -∞ dB   (silent below 0.05)
//   travel  0.05 → -40 dB
//   travel  0.50 → -10 dB
//   travel  0.75 →   0 dB  (unity = 1.0×)
//   travel  1.00 →  +6 dB  (~1.995×)
// Three linear-in-dB segments. Mirrored exactly in MixerPanel.kt::faderToGain
// so the UI multiplier readout matches DSP output.
static inline float pulsar_fader_to_gain(float travel) {
    if (travel <= 0.05f) return 0.0f;
    float db;
    if (travel >= 0.75f) {
        db = (travel - 0.75f) * 24.0f;                        //   0 → +6 dB
    } else if (travel >= 0.50f) {
        db = -10.0f + (travel - 0.50f) * 40.0f;               // -10 →   0 dB
    } else {
        db = -40.0f + (travel - 0.05f) * (30.0f / 0.45f);     // -40 → -10 dB
    }
    return std::pow(10.0f, db / 20.0f);
}

static inline float clamp01(float x) {
    return x < 0.0f ? 0.0f : (x > 1.0f ? 1.0f : x);
}

// Constant-power pan: left = cos(angle), right = sin(angle)
// pan in [-1, 1], 0 = center
static inline void constant_power_pan(float pan, float& gain_l, float& gain_r) {
    float angle = (clamp01(pan * 0.5f + 0.5f)) * 1.5707963f; // pi/2
    gain_l = std::cos(angle);
    gain_r = std::sin(angle);
}

// quantize_to_scale() now lives in pulsar_pattern_gen.h (shared with bar_strategy)

// ── PRNG for mutation ────────────────────────────────────────────────

static inline uint32_t xorshift32(uint32_t& state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}

// Returns 0.0-1.0
static inline float rand01(uint32_t& state) {
    return static_cast<float>(xorshift32(state) & 0xFFFF) / 65535.0f;
}

// Deterministic hash for consistent per-step values (not random each block)
static inline uint32_t step_hash(int step, int track, int loop) {
    uint32_t h = static_cast<uint32_t>(step * 7919 + track * 104729 + loop * 15485863);
    h ^= h >> 16; h *= 0x45d9f3b; h ^= h >> 16;
    return h;
}

// ── Mutation: evolve patterns each loop ─────────────────────────────

static void mutate_patterns(PulsarState* state, float complexity, OrpheusEngine* engine) {
    state->loop_count++;

    // ── Compute tension intensity from inner/outer cycle phases ──
    int inner = state->tension.inner_bars;
    if (inner > 0) {
        float inner_phase = static_cast<float>(state->loop_count % inner) / static_cast<float>(inner);
        float outer_scale = 1.0f;
        int outer = state->tension.outer_bars;
        if (outer > 0) {
            float outer_phase = static_cast<float>(state->loop_count % outer) / static_cast<float>(outer);
            outer_scale = (1.0f - state->tension.outer_depth) + state->tension.outer_depth * outer_phase;
        }
        state->tension_intensity = inner_phase * outer_scale;
    } else {
        state->tension_intensity = 0.0f;
    }

    // ── TENS-2: step the evolution smoother ONCE PER BAR ──
    // The smoother used to advance every audio block (per track, per render
    // loop), so it converged within milliseconds and evo_release_speed had no
    // audible effect. Stepping it here — once per bar — lets releaseSpeed
    // express a multi-bar decay. The render loop now only READS the value.
    // Single shared scalar tracks the section-wide evo intensity (the
    // attack-point-gated tension); each track scales it by its own evo_weight
    // at render time.
    {
        float evo_ap = state->tension.evo_attack_point;
        float evo_intensity = (evo_ap < 0.999f)
            ? std::max(0.0f, (state->tension_intensity - evo_ap) / (1.0f - evo_ap))
            : 0.0f;
        // evo_release_speed is EXPECTED in [0,1] (0 = instant follow, 1 = near
        // frozen), but it is loaded raw from the param atomic / section override
        // with no upstream clamp — so the [0,1] clamp on evo_speed below is
        // load-bearing, NOT redundant: an out-of-range value would otherwise make
        // evo_speed negative (step the wrong way) or >1 (overshoot). The per-bar
        // coefficient is (1 - releaseSpeed); high releaseSpeed = slow multi-bar approach.
        float evo_speed = 1.0f - state->tension.evo_release_speed;
        if (evo_speed < 0.0f) evo_speed = 0.0f;
        if (evo_speed > 1.0f) evo_speed = 1.0f;
        state->tension_evo_smooth += evo_speed * (evo_intensity - state->tension_evo_smooth);
    }

    // ── Lick evolution spurt state machine ──
    if (state->lick_length > 0) {
        if (state->in_spurt) {
            state->spurt_bars_remaining--;
            if (state->spurt_bars_remaining <= 0) {
                // Exit spurt: write back mutated lick with bounded drift
                int max_drift = std::max(1, static_cast<int>(state->lick_mutation * 4.0f + 0.5f));
                for (int i = 0; i < state->lick_length; i++) {
                    int drift = state->lick[i].scale_degree - state->original_lick[i].scale_degree;
                    if (std::abs(drift) > max_drift) {
                        state->lick[i].scale_degree = state->original_lick[i].scale_degree
                            + std::max(-max_drift, std::min(drift, max_drift));
                    }
                    // Velocity drift: clamp to ±0.2 of original
                    float vel_drift = state->lick[i].velocity - state->original_lick[i].velocity;
                    if (std::fabs(vel_drift) > 0.2f) {
                        state->lick[i].velocity = state->original_lick[i].velocity
                            + std::max(-0.2f, std::min(vel_drift, 0.2f));
                    }
                }
                state->in_spurt = false;
            }
        } else {
            bool trigger = false;
            if (state->tension_intensity > 0.85f) {
                trigger = true;
            } else if (state->tension.spurt_chance > 0.001f) {
                uint32_t rng = state->mutation_seed;
                rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5;
                state->mutation_seed = rng;
                float r = (rng & 0xFFFF) / 65535.0f;
                if (r < state->tension.spurt_chance) {
                    trigger = true;
                }
            }
            if (trigger) {
                state->in_spurt = true;
                state->spurt_bars_remaining = std::max(1, state->tension.inner_bars / 2);
            }
        }
    }

    // Clamp all step counts to array bounds (defense against prior overflow)
    for (int t = 0; t < kNumPulsarTracks; t++) {
        if (state->tracks[t].step_count > kMaxPulsarSteps)
            state->tracks[t].step_count = kMaxPulsarSteps;
    }

    // Read per-track Markov contour opt-in from pitch evolution mode
    bool use_markov_contour[kNumPulsarTracks];
    for (int t = 0; t < kNumPulsarTracks; t++) {
        use_markov_contour[t] = (state->tracks[t].evo_pitch_mode == PitchEvoMode::CONTOUR);
    }

    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];
        // Per-track variation budget: scales the global complexity into the
        // track's role-aware {min,max} range (RHYTHM/drums tight, WILD wide).
        float track_var = lerp_macro(complexity, ts.macro_map.complexity_variation);

        for (int s = 0; s < ts.step_count; s++) {
            PulsarStep& step = ts.steps[s];
            uint32_t h = step_hash(s, t, state->loop_count);
            float roll = static_cast<float>(h & 0xFFFF) / 65535.0f;

            // Ghost notes: activate inactive steps with low velocity
            if (!step.gate) {
                float ghost_prob = track_var * 0.08f;  // up to 8% chance per step
                if (roll < ghost_prob) {
                    step.gate = true;
                    step.velocity = 0.15f + roll * 0.15f / std::max(ghost_prob, 0.001f);
                    step.duration = 0.2f;
                    // Keep existing note (from preset)
                }
                continue;
            }

            // Accent variation: slightly vary existing velocities.
            // Driven by RAW complexity (not the per-track variation budget):
            // this is velocity jitter on already-active hits, so it never
            // shifts which steps fire or the phrase length and therefore can't
            // cause rhythmic disjointedness. Letting complexity drive it gives
            // drums their groove/life at high complexity instead of flattening
            // them. Structural mutation (step-count, ghost, drift) stays budgeted.
            float accent_range = complexity * 0.15f;
            float accent_offset = (static_cast<float>((h >> 8) & 0xFFFF) / 65535.0f - 0.5f) * 2.0f * accent_range;
            step.velocity = clamp01(step.velocity + accent_offset);

            // Note drift for melodic and effect tracks (3-7)
            if (t >= 3 && !use_markov_contour[t]) {
                float drift_prob = track_var * 0.1f;
                float drift_roll = static_cast<float>((h >> 16) & 0xFFFF) / 65535.0f;
                if (drift_roll < drift_prob) {
                    // Drift by ±1-2 semitones from raw_note, then quantize
                    int offsets[] = {-2, -1, 1, 2};
                    int idx = static_cast<int>((h >> 24) & 0x3);
                    int new_note = static_cast<int>(step.raw_note) + offsets[idx];
                    if (new_note >= 24 && new_note <= 96) {
                        uint8_t root = static_cast<uint8_t>(
                            engine->pulsar_root_note.load(std::memory_order_relaxed));
                        int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
                        if (si < 0) si = 0;
                        if (si >= kNumPulsarScales) si = kNumPulsarScales - 1;
                        const PulsarScale& scale = kPulsarScales[si];
                        step.raw_note = static_cast<uint8_t>(new_note);
                        step.note = static_cast<uint8_t>(
                            quantize_to_scale(new_note, root, scale));
                    }
                }
            }
        }
    }

    // ── Markov contour mutation (opt-in per track) ──
    // Walks through active steps sequentially using second-order Markov,
    // building melodic contour instead of random drift.
    {
        uint8_t root = static_cast<uint8_t>(
            engine->pulsar_root_note.load(std::memory_order_relaxed));
        int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
        if (si < 0) si = 0;
        if (si >= kNumPulsarScales) si = kNumPulsarScales - 1;
        const PulsarScale& scale = kPulsarScales[si];

        for (int t = 0; t < kNumPulsarTracks; t++) {
            if (!use_markov_contour[t]) continue;
            PulsarTrackState& ts = state->tracks[t];
            SoloBehaviorParam& sb = state->track_solo_behavior[t];
            float track_var = lerp_macro(complexity, ts.macro_map.complexity_variation);

            // Only mutate a fraction of steps per bar, scaling with the
            // per-track variation budget (was raw complexity).
            float mutate_prob = track_var * 0.15f;  // up to 15% of steps mutated

            // Seed current degree from first active step
            int current_degree = 0;
            for (int s = 0; s < ts.step_count; s++) {
                if (ts.steps[s].gate) {
                    // Convert MIDI note to scale degree
                    int note_in_scale = (ts.steps[s].note - root + 120) % 12;
                    for (int d = 0; d < scale.count; d++) {
                        if (scale.degrees[d] == note_in_scale) {
                            current_degree = d;
                            break;
                        }
                    }
                    break;
                }
            }

            for (int s = 0; s < ts.step_count; s++) {
                PulsarStep& step = ts.steps[s];
                if (!step.gate) continue;

                uint32_t h = step_hash(s, t, state->loop_count);
                float roll = static_cast<float>(h & 0xFFFF) / 65535.0f;
                if (roll >= mutate_prob) {
                    // Not mutating this step — but still track the degree for contour
                    int note_in_scale = (step.note - root + 120) % 12;
                    for (int d = 0; d < scale.count; d++) {
                        if (scale.degrees[d] == note_in_scale) {
                            current_degree = d;
                            break;
                        }
                    }
                    continue;
                }

                int degree = markov_next_note(
                    sb, current_degree, scale.count,
                    state->tension_intensity, state->mutation_seed);

                if (degree >= 0) {
                    int d = degree % scale.count;
                    // Compute MIDI note in the same octave region as the original
                    int octave_base = (static_cast<int>(step.note) / 12) * 12;
                    int new_note = octave_base + root + scale.degrees[d];
                    // Keep within ±6 semitones of original to avoid jarring jumps
                    int original = static_cast<int>(step.note);
                    while (new_note - original > 6) new_note -= 12;
                    while (original - new_note > 6) new_note += 12;
                    new_note = std::max(24, std::min(96, new_note));

                    step.note = static_cast<uint8_t>(new_note);
                    step.raw_note = static_cast<uint8_t>(new_note);
                    current_degree = degree;
                } else if (degree == -1) {
                    // Rest — lower velocity instead of killing the gate
                    step.velocity *= 0.3f;
                }
                // degree == -2 (hold): keep step unchanged
            }
        }
    }

    // Step count mutation (very high complexity only; non-percussive tracks
    // t>=2 — drums are skipped by role below so phrase length stays locked).
    if (complexity > 0.85f) {
        float step_mut_prob = (complexity - 0.85f) * 0.1f;
        for (int t = 2; t < kNumPulsarTracks; t++) {
            PulsarTrackState& ts = state->tracks[t];
            if (ts.role == TrackRole::PERCUSSIVE) continue;  // drums must stay phrase-locked
            float roll = rand01(state->mutation_seed);
            if (roll < step_mut_prob) {
                int delta = (rand01(state->mutation_seed) > 0.5f) ? 1 : -1;
                // no +/-2 jump: phrase-length desync is the worst disjointedness source
                int new_count = ts.step_count + delta;
                if (new_count >= 12 && new_count <= kMaxPulsarSteps) {
                    ts.step_count = new_count;
                }
            }
        }
    }

    // ── CHORDAL per-bar evolution pass (humanization + fills) ──
    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];
        if (ts.role != TrackRole::CHORDAL) continue;
        if (!ts.chordal_base_valid) continue;

        bool any_human = (ts.human_drop_prob > 0.001f) || (ts.human_ghost_prob > 0.001f)
                         || (ts.human_octave_prob > 0.001f) || (ts.human_ext_prob > 0.001f);
        bool fills_enabled = (ts.fill_every_n > 0) && (ts.fill_type != FillTypeId::NONE);
        if (!any_human && !fills_enabled) continue;

        // Restore BASE
        std::memcpy(ts.steps, ts.chordal_base, sizeof(PulsarStep) * ts.step_count);

        uint32_t seed = static_cast<uint32_t>(state->loop_count * 0x9E3779B9u)
                      ^ static_cast<uint32_t>(t * 2654435761u);

        // ── Fills first (replaces whole bar) ──
        bool fill_fired = false;
        if (fills_enabled) {
            ts.bars_since_fill++;
            if (ts.bars_since_fill >= ts.fill_every_n) {
                ts.bars_since_fill = 0;
                // Skip-probability roll
                uint32_t sseed = seed;
                float skip_roll = static_cast<float>((lcg_next(sseed) >> 8) & 0xFFFF) / 65535.0f;
                if (skip_roll >= ts.fill_skip_prob) {
                    // Apply the fill
                    int cd = state->chord_state.progression[state->chord_state.chord_index];
                    int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
                    if (si < 0) si = 0;
                    if (si >= kNumPulsarScales) si = kNumPulsarScales - 1;
                    const PulsarScale& sc = kPulsarScales[si];
                    int r = engine->pulsar_root_note.load(std::memory_order_relaxed);
                    int nr_low  = engine->pulsar_track_note_range_low[t].load(std::memory_order_relaxed);
                    int nr_high = engine->pulsar_track_note_range_high[t].load(std::memory_order_relaxed);
                    if (nr_low  <= 0) nr_low  = engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed);
                    if (nr_high <= 0) nr_high = engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed);
                    if (nr_low  <= 0) nr_low  = 48;  // final safety fallback
                    if (nr_high <= 0) nr_high = 72;
                    const uint8_t lo = static_cast<uint8_t>(nr_low);
                    const uint8_t hi = static_cast<uint8_t>(nr_high);
                    const int octv   = comping_default_octave(ts.comping_style);

                    switch (ts.fill_type) {
                        case FillTypeId::ASCENDING_ARP:
                            apply_fill_ascending_arp(ts.steps, ts.step_count, cd,
                                static_cast<uint8_t>(r), sc, lo, hi, octv);
                            fill_fired = true;
                            break;
                        case FillTypeId::DESCENDING_ARP:
                            apply_fill_descending_arp(ts.steps, ts.step_count, cd,
                                static_cast<uint8_t>(r), sc, lo, hi, octv);
                            fill_fired = true;
                            break;
                        case FillTypeId::TURNAROUND:
                            apply_fill_turnaround(ts.steps, ts.step_count, cd,
                                static_cast<uint8_t>(r), sc, lo, hi, octv);
                            fill_fired = true;
                            break;
                        case FillTypeId::DOUBLE_TIME:
                            apply_fill_double_time(ts.steps, ts.step_count, cd,
                                static_cast<uint8_t>(r), sc, lo, hi, octv);
                            fill_fired = true;
                            break;
                        case FillTypeId::STAB_FLURRY:
                            apply_fill_stab_flurry(ts.steps, ts.step_count, cd,
                                static_cast<uint8_t>(r), sc, lo, hi, octv);
                            fill_fired = true;
                            break;
                        case FillTypeId::DROP_OUT:
                            apply_fill_drop_out(ts.steps, ts.step_count, cd,
                                static_cast<uint8_t>(r), sc, lo, hi, octv);
                            fill_fired = true;
                            break;
                        case FillTypeId::NONE:
                        default:
                            break;
                    }
                }
            }
        }

        // ── Humanization (only if no fill this bar) ──
        if (!fill_fired && any_human) {
            // Section-level override wins when active (snap, no crossfade).
            float h_drop  = ts.human_drop_prob;
            float h_ghost = ts.human_ghost_prob;
            float h_oct   = ts.human_octave_prob;
            float h_ext   = ts.human_ext_prob;
            if (state->arrangement.active && state->arrangement.section_count > 0) {
                int cur = state->section_state.current_section;
                if (cur >= 0 && cur < state->arrangement.section_count) {
                    const SectionParam& sec = state->arrangement.sections[cur];
                    if (sec.has_comping_humanization_override) {
                        h_drop  = sec.comping_humanization_drop;
                        h_ghost = sec.comping_humanization_ghost;
                        h_oct   = sec.comping_humanization_octave;
                        h_ext   = sec.comping_humanization_extension;
                    }
                }
            }
            apply_humanization(ts.steps, ts.step_count,
                               h_drop, h_ghost, h_oct, h_ext,
                               complexity, seed);
        }
    }
}

// ── Tides envelope parameter computation ────────────────────────────

static void compute_tides_params(
    PulsarEnvelopeProfile profile,
    float energy, float complexity, float space, float mood,
    uint32_t& seed,
    float& out_shape, float& out_pw, float& out_smoothness, float& out_freq_mult
) {
    // Each profile responds to knobs differently for musical variety:
    // - SHAPE: morphs envelope curve (low=soft, high=snappy)
    // - PW: attack/decay balance (low=fast attack, high=slow attack)
    // - SMOOTHNESS: <0.5 adds LP filter (rounded), >0.5 adds wavefold (complex)
    // - FREQ_MULT: scales the base envelope frequency (>1 = shorter, <1 = longer)
    switch (profile) {
        case ENV_PROFILE_RHYTHM:
            // Energy → punchier (shorter, snappier). Mood → tonal character.
            out_shape = 0.7f + energy * 0.2f;           // snappier at high energy
            out_pw = 0.15f + (1.0f - energy) * 0.15f;   // faster attack at high energy
            out_smoothness = 0.3f + mood * 0.2f;         // mood rounds the transient
            out_freq_mult = 0.5f + energy * 1.5f;        // shorter at high energy
            break;
        case ENV_PROFILE_MELODIC:
            // Space → longer, dreamier. Mood → warmer shape. Energy → sustain.
            out_shape = 0.3f + mood * 0.4f;              // mood morphs curve
            out_pw = 0.2f + space * 0.5f;                // space extends decay
            out_smoothness = 0.4f - space * 0.2f;        // space adds LP smoothing
            out_freq_mult = 0.3f + (1.0f - space) * 0.7f; // space extends envelope
            break;
        case ENV_PROFILE_EFFECT:
            // Space → ambient swells. Mood → shape character. Complexity → variation.
            out_shape = 0.2f + mood * 0.6f;              // mood: soft → bright
            out_pw = 0.3f + space * 0.5f;                // space: slow attack swell
            out_smoothness = 0.5f - space * 0.35f;       // high space → very smooth
            out_freq_mult = 0.2f + (1.0f - space) * 0.5f; // long at high space
            break;
        case ENV_PROFILE_DRONE:
            // Swelling sustained tone for hold steps.
            // High PW = slow attack swell, the signature "breathing" character.
            // Low freq_mult = envelope takes 1-3s to fully open, creating gradual swells.
            // Smoothness softens any transient at the swell onset.
            out_shape = 0.15f + mood * 0.25f;       // gentle curve
            out_pw = 0.75f + space * 0.15f;          // 75-90%: slow swell attack
            out_smoothness = 0.3f + space * 0.2f;    // smooth onset, more at high space
            out_freq_mult = 0.15f + (1.0f - space) * 0.25f;  // 0.15-0.40: 1-3s swell
            break;
        case ENV_PROFILE_WILD:
        default:
            // Everything cross-modulates. Complexity adds chaos.
            out_shape = mood * 0.6f + energy * 0.3f + rand01(seed) * 0.1f;
            out_pw = complexity * 0.7f + space * 0.2f;
            out_smoothness = 0.5f - space * 0.3f + complexity * 0.3f + rand01(seed) * 0.15f;
            out_freq_mult = 0.3f + energy * 0.5f + complexity * 0.3f;
            break;
    }
}

// ── FX track probability ────────────────────────────────────────────

static float compute_fx_probability(float energy, float complexity) {
    float low_prob = std::max(0.0f, (1.0f - energy) - 0.6f) * 2.5f;
    float high_prob = std::max(0.0f, (complexity - 0.7f) * 3.3f)
                    * std::max(0.0f, (energy - 0.6f) * 2.5f);
    return std::max(low_prob, high_prob);
}

// ── Section entry: restart chord progression ────────────────────────
//
// Re-initializes state->chord_state so it starts at the beginning of the
// section's effective progression. Used both on initial vibe load (for the
// first section) and whenever advance_section() reports a section change.
// Uses the section's custom progression / chords-per-bar when set, else the
// vibe's own custom progression, else the genre default. init_chord_progression
// resets chord_index to 0.
static void restart_progression_for_section(PulsarState* state,
                                            const SectionParam& sec,
                                            OrpheusEngine* engine) {
    int style = static_cast<int>(
        engine->pulsar_genre_progression_style.load(std::memory_order_relaxed));
    int step_count = static_cast<int>(
        engine->pulsar_step_count.load(std::memory_order_relaxed));
    int cpb = (sec.chords_per_bar_override > 0)
        ? sec.chords_per_bar_override
        : static_cast<int>(
            engine->pulsar_genre_chords_per_bar.load(std::memory_order_relaxed));

    int section_idx = state->section_state.current_section;  // for section-progression glide lookup
    if (sec.custom_progression_length > 0) {
        int degrees[kMaxProgressionLength];
        for (int i = 0; i < sec.custom_progression_length; i++) {
            degrees[i] = sec.custom_progression[i];
        }
        init_chord_progression(state->chord_state, style, cpb, step_count,
                               state->mutation_seed,
                               degrees, sec.custom_progression_length);
        // Per-chord glides for the section progression. Section-indexed.
        if (section_idx >= 0 && section_idx < 8) {
            for (int i = 0; i < sec.custom_progression_length; i++) {
                state->chord_state.progression_glides[i] =
                    engine->pulsar_section_progression_glides[section_idx * kMaxProgressionLength + i].load(
                        std::memory_order_relaxed);
            }
        } else {
            for (int i = 0; i < kMaxProgressionLength; i++)
                state->chord_state.progression_glides[i] = 0.0f;
        }
    } else {
        int vibe_len = 0;
        int vibe_degrees[kMaxProgressionLength];
        if (engine->pulsar_custom_progression_active.load(std::memory_order_relaxed) > 0) {
            vibe_len = engine->pulsar_custom_progression_length.load(std::memory_order_relaxed);
            for (int i = 0; i < vibe_len && i < kMaxProgressionLength; i++) {
                vibe_degrees[i] = engine->pulsar_custom_progression[i].load(
                    std::memory_order_relaxed);
            }
        }
        init_chord_progression(state->chord_state, style, cpb, step_count,
                               state->mutation_seed,
                               vibe_len > 0 ? vibe_degrees : nullptr,
                               vibe_len);
        // Per-chord glides for the vibe-level progression.
        for (int i = 0; i < kMaxProgressionLength; i++) {
            state->chord_state.progression_glides[i] = (vibe_len > 0)
                ? engine->pulsar_custom_progression_glide[i].load(std::memory_order_relaxed)
                : 0.0f;
        }
    }
    // Reset per-track chord-edge tracker. Re-entering chord 0 in a new section
    // should be treated as the first edge (no glide), same as the initial vibe
    // load — we have no previous chord to slide from across the section boundary.
    for (int t = 0; t < kNumPulsarTracks; t++) {
        state->tracks[t].last_chord_index = -1;
    }
}

// Copy the vibe-level tension atomics into state->tension. Used on initial
// vibe load and on section entry when the current section has no tension
// override (so prior overrides do not leak forward).
static void reload_vibe_tension(OrpheusEngine* engine, PulsarState* state) {
    state->tension.inner_bars        = engine->pulsar_tension_inner_bars.load(std::memory_order_relaxed);
    state->tension.outer_bars        = engine->pulsar_tension_outer_bars.load(std::memory_order_relaxed);
    state->tension.outer_depth       = engine->pulsar_tension_outer_depth.load(std::memory_order_relaxed);
    state->tension.volume            = engine->pulsar_tension_volume.load(std::memory_order_relaxed);
    state->tension.timing            = engine->pulsar_tension_timing.load(std::memory_order_relaxed);
    state->tension.octave_shift      = engine->pulsar_tension_octave_shift.load(std::memory_order_relaxed) != 0;
    state->tension.key_shift         = engine->pulsar_tension_key_shift.load(std::memory_order_relaxed);
    state->tension.half_lick         = engine->pulsar_tension_half_lick.load(std::memory_order_relaxed) != 0;
    state->tension.chromatic_passing = engine->pulsar_tension_chromatic_passing.load(std::memory_order_relaxed);
    state->tension.evo_timbre_low    = engine->pulsar_tension_evo_timbre_low.load(std::memory_order_relaxed);
    state->tension.evo_timbre_high   = engine->pulsar_tension_evo_timbre_high.load(std::memory_order_relaxed);
    state->tension.evo_timbre_prob   = engine->pulsar_tension_evo_timbre_prob.load(std::memory_order_relaxed);
    state->tension.evo_morph_low     = engine->pulsar_tension_evo_morph_low.load(std::memory_order_relaxed);
    state->tension.evo_morph_high    = engine->pulsar_tension_evo_morph_high.load(std::memory_order_relaxed);
    state->tension.evo_morph_prob    = engine->pulsar_tension_evo_morph_prob.load(std::memory_order_relaxed);
    state->tension.evo_harm_low      = engine->pulsar_tension_evo_harm_low.load(std::memory_order_relaxed);
    state->tension.evo_harm_high     = engine->pulsar_tension_evo_harm_high.load(std::memory_order_relaxed);
    state->tension.evo_harm_prob     = engine->pulsar_tension_evo_harm_prob.load(std::memory_order_relaxed);
    state->tension.evo_attack_point  = engine->pulsar_tension_evo_attack_point.load(std::memory_order_relaxed);
    state->tension.evo_release_speed = engine->pulsar_tension_evo_release_speed.load(std::memory_order_relaxed);
    state->tension.spurt_chance      = engine->pulsar_tension_spurt_chance.load(std::memory_order_relaxed);
    for (int i = 0; i < 8; i++)
        state->tension.track_evo_weight[i] = engine->pulsar_track_evo_weight[i].load(std::memory_order_relaxed);
}

// ── Vibe loading (reads recipe from engine atomics) ─────────────────

static void load_vibe(PulsarState* state, int generation, OrpheusEngine* engine) {
    // ── Clear generative runtime state from the previous vibe ───────────
    // Solo modifiers, live-lick caches, and anchor indices all carry musical
    // state that must not bleed across vibe boundaries. (Pattern data, effect
    // buffers, tempo drift, etc. are reset further down in this function.)
    clear_solo_modifiers(state->tracks, kNumPulsarTracks);
    std::memset(state->live_lick_degrees,    0, sizeof(state->live_lick_degrees));
    std::memset(state->live_lick_durations,  0, sizeof(state->live_lick_durations));
    std::memset(state->live_lick_velocities, 0, sizeof(state->live_lick_velocities));
    // MUT-4: the section-entry drift snapshot is a live-lick cache too — clear it
    // here with its siblings so a prior vibe's degrees can't bleed into the clamp.
    std::memset(state->live_lick_base_degrees, 0, sizeof(state->live_lick_base_degrees));
    state->live_lick_length = 0;
    state->live_lick_active = false;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        state->tracks[t].anchor_indices[0] = -1;
        state->tracks[t].anchor_indices[1] = -1;
    }

    // Read seed — 0 means random
    int64_t seed_val = engine->pulsar_seed.load(std::memory_order_relaxed);
    uint32_t base_seed;
    if (seed_val == 0) {
        auto now = std::chrono::steady_clock::now().time_since_epoch();
        uint32_t time_bits = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(now).count());
        state->seed_counter += time_bits;
        base_seed = state->seed_counter * 2654435761u;
    } else {
        base_seed = static_cast<uint32_t>(seed_val) * 2654435761u;
    }
    // SEED-1: Defense-in-depth ONLY for the random path. An explicit (non-zero)
    // seed must be byte-reproducible so a curated/locked roll can be recalled
    // exactly. For the random path (seed == 0) we still stir a fresh microsecond
    // reading into base_seed so even re-loads of the same vibe at the same
    // seed_counter value diverge slightly.
    if (seed_val == 0) {
        auto stir_us = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
        base_seed ^= stir_us * 0x85EBCA77u;
    }

    // Reset the mutation RNG immediately, BEFORE any consumer in this function
    // (e.g. init_section_state passes mutation_seed by reference to randomize
    // the initial section / bars-remaining). Without this, the new vibe's
    // arrangement begins biased by whatever mid-render mutation state the
    // previous vibe left behind.
    state->mutation_seed = base_seed;

    // Snap macro smoothers to the current engine atomics so the new vibe's
    // very first render block uses new-vibe macros (no ~10ms cross-fade from
    // the old vibe's smoothed values). Pattern decisions made on bar 1 read
    // these directly.
    state->smooth_energy     = engine->pulsar_energy.load(std::memory_order_relaxed);
    state->smooth_complexity = engine->pulsar_complexity.load(std::memory_order_relaxed);
    state->smooth_space      = engine->pulsar_space.load(std::memory_order_relaxed);
    state->smooth_mood       = engine->pulsar_mood.load(std::memory_order_relaxed);


    // Read genre profile from atomics
    PulsarGenreProfile genre;
    for (int i = 0; i < 8; i++)
        genre.base_density[i] = engine->pulsar_genre_density[i].load(std::memory_order_relaxed);
    genre.swing_amount = engine->pulsar_genre_swing.load(std::memory_order_relaxed);
    genre.ghost_probability = engine->pulsar_genre_ghost_prob.load(std::memory_order_relaxed);
    genre.note_range_low = static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed));
    genre.note_range_high = static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed));
    genre.rhythm_density = engine->pulsar_genre_rhythm_density.load(std::memory_order_relaxed);
    genre.progression_style = static_cast<uint8_t>(
        engine->pulsar_genre_progression_style.load(std::memory_order_relaxed));
    genre.chords_per_bar = static_cast<uint8_t>(
        engine->pulsar_genre_chords_per_bar.load(std::memory_order_relaxed));

    // Read lick (length acts as acquire fence)
    int lick_len = engine->pulsar_lick_length.load(std::memory_order_acquire);
    if (lick_len > kMaxLickSteps) lick_len = kMaxLickSteps;
    state->lick_length = lick_len;
    int loop_len = engine->pulsar_lick_loop_length.load(std::memory_order_relaxed);
    state->lick_loop_length = (loop_len > 0) ? loop_len : lick_len;
    if (lick_len > 0) {
        for (int i = 0; i < lick_len; i++) {
            state->lick[i].scale_degree = engine->pulsar_lick[i].scale_degree;
            state->lick[i].duration = engine->pulsar_lick[i].duration;
            state->lick[i].velocity = engine->pulsar_lick[i].velocity;
            state->lick[i].glide_rate = engine->pulsar_lick[i].glide_rate;
        }
    }
    state->lick_mutation = engine->pulsar_lick_mutation.load(std::memory_order_relaxed);
    // Keep immutable copy for bounded drift during evolution spurts
    std::memcpy(state->original_lick, state->lick, sizeof(PulsarLickStep) * lick_len);
    state->in_spurt = false;
    state->spurt_bars_remaining = 0;
    state->lick_octave = engine->pulsar_lick_octave.load(std::memory_order_relaxed);

    int root = engine->pulsar_root_note.load(std::memory_order_relaxed);
    int scale_idx = engine->pulsar_scale_index.load(std::memory_order_relaxed);
    if (scale_idx < 0) scale_idx = 0;
    if (scale_idx >= static_cast<int>(sizeof(kPulsarScales) / sizeof(kPulsarScales[0])))
        scale_idx = static_cast<int>(sizeof(kPulsarScales) / sizeof(kPulsarScales[0])) - 1;
    const PulsarScale& scale = kPulsarScales[scale_idx];

    // Effective mutation: spurt amplifies 3x, capped at 1.0
    float eff_mutation = state->in_spurt
        ? std::min(1.0f, state->lick_mutation * 3.0f)
        : state->lick_mutation;

    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];

        // Read per-track voice config from atomics
        ts.volume = engine->pulsar_track_volume[t].load(std::memory_order_relaxed);
        ts.pan = engine->pulsar_track_pan[t].load(std::memory_order_relaxed);
        ts.harmonics = engine->pulsar_track_harmonics[t].load(std::memory_order_relaxed);
        ts.timbre = engine->pulsar_track_timbre[t].load(std::memory_order_relaxed);
        ts.morph = engine->pulsar_track_morph[t].load(std::memory_order_relaxed);
        ts.envelope_profile = static_cast<PulsarEnvelopeProfile>(
            engine->pulsar_track_envelope[t].load(std::memory_order_relaxed));
        TrackRole role = static_cast<TrackRole>(
            engine->pulsar_track_role[t].load(std::memory_order_relaxed));
        ts.role = role;
        ts.comping_style = static_cast<CompingStyleId>(
            engine->pulsar_track_comping_style[t].load(std::memory_order_relaxed));
        ts.arp_mode = static_cast<ArpModeId>(
            engine->pulsar_track_arp_mode[t].load(std::memory_order_relaxed));
        ts.arp_speed =
            engine->pulsar_track_arp_speed[t].load(std::memory_order_relaxed);
        ts.arp_direction = static_cast<ArpDirectionId>(
            engine->pulsar_track_arp_direction[t].load(std::memory_order_relaxed));
        ts.section_inversion = static_cast<SectionInversionId>(
            engine->pulsar_track_inversion[t].load(std::memory_order_relaxed));
        // Hard-reset per-track runtime state so the new vibe doesn't inherit
        // mid-arpeggiation, mid-fill-cycle, or stale chordal-comping caches
        // from the previous vibe. Subsequent role-specific branches below
        // override bars_since_fill (chordal -> 1) and chordal_base_valid as
        // needed; this is the safe baseline.
        ts.arp_note_count = 0;
        ts.arp_index = 0;
        ts.arp_next_sample = 0;
        std::memset(ts.arp_notes, 0, sizeof(ts.arp_notes));
        ts.bars_since_fill = 0;
        ts.chordal_base_valid = false;
        bool percussive = (role == TrackRole::PERCUSSIVE);

        // Read macro map from atomics
        auto& m = engine->pulsar_track_macros[t];
        ts.macro_map = {
            {m.energy_vol_min.load(std::memory_order_relaxed), m.energy_vol_max.load(std::memory_order_relaxed)},
            {m.energy_density_min.load(std::memory_order_relaxed), m.energy_density_max.load(std::memory_order_relaxed)},
            {m.complexity_swing_min.load(std::memory_order_relaxed), m.complexity_swing_max.load(std::memory_order_relaxed)},
            {m.complexity_var_min.load(std::memory_order_relaxed), m.complexity_var_max.load(std::memory_order_relaxed)},
            {m.space_decay_min.load(std::memory_order_relaxed), m.space_decay_max.load(std::memory_order_relaxed)},
            {m.mood_harm_min.load(std::memory_order_relaxed), m.mood_harm_max.load(std::memory_order_relaxed)},
            {m.mood_timbre_min.load(std::memory_order_relaxed), m.mood_timbre_max.load(std::memory_order_relaxed)},
        };

        // Generate pattern
        // Lick drives the lead melody (keys=4) only.
        // Bass (3) uses its root-heavy generative pattern for complementary foundation.
        // Tracks 5-7 (pad, texture, FX) use generative patterns for variety.
        // Read bar strategy for this track
        BarStrategy bar_strategy = static_cast<BarStrategy>(
            engine->pulsar_track_bar_strategy[t].load(std::memory_order_relaxed));
        ts.bar_strategy = bar_strategy;

        int step_count_config = engine->pulsar_step_count.load(std::memory_order_relaxed);
        if (step_count_config <= 0) step_count_config = 16;
        if (step_count_config > kMaxPulsarSteps) step_count_config = kMaxPulsarSteps;

        // For 32-step vibes, generate bar 1 (16 steps) then apply bar strategy
        int bar1_len = (step_count_config > 16) ? 16 : step_count_config;

        LickMode lick_mode = static_cast<LickMode>(
            engine->pulsar_track_lick_mode[t].load(std::memory_order_relaxed));

        // Chord follow mode
        ts.chord_follow = static_cast<ChordFollowMode>(
            engine->pulsar_track_chord_follow[t].load(std::memory_order_relaxed));

        // LPG (low-pass gate) config
        ts.lpg_mode = engine->pulsar_track_lpg_mode[t].load(std::memory_order_relaxed);
        ts.lpg_mode_space = engine->pulsar_track_lpg_mode_space[t].load(std::memory_order_relaxed);
        ts.lpg_decay = engine->pulsar_track_lpg_decay[t].load(std::memory_order_relaxed);
        ts.lpg_colour = engine->pulsar_track_lpg_colour[t].load(std::memory_order_relaxed);

        // Snapshot defaults so section overrides can revert to them on -1 (no override)
        ts.default_chord_follow = ts.chord_follow;
        ts.default_comping_style = ts.comping_style;
        ts.default_section_inversion = ts.section_inversion;
        ts.default_arp_mode = ts.arp_mode;

        // Evolution parameters
        ts.evo_rhythmic = engine->pulsar_track_evo_rhythmic[t].load(std::memory_order_relaxed) != 0;
        ts.evo_tension_resp = engine->pulsar_track_evo_tension_resp[t].load(std::memory_order_relaxed);
        ts.evo_note_follow = static_cast<NoteFollowMode>(
            engine->pulsar_track_evo_note_follow[t].load(std::memory_order_relaxed));
        ts.evo_pitch_mode = static_cast<PitchEvoMode>(
            engine->pulsar_track_evo_pitch_mode[t].load(std::memory_order_relaxed));
        ts.evo_voicing_tension = engine->pulsar_track_evo_voicing_tension[t].load(std::memory_order_relaxed);

        if (role == TrackRole::CHORDAL) {
            // CHORDAL: walk rhythm template, stab chord root (CHD renders voicing)
            ts.step_count = step_count_config;
            int initial_chord_degree = (state->chord_state.progression_length > 0)
                ? state->chord_state.progression[0] : 0;
            generate_chordal_pattern(
                ts.steps, ts.step_count,
                ts.comping_style,
                initial_chord_degree,
                static_cast<uint8_t>(root), scale,
                genre.note_range_low, genre.note_range_high);
            // Snapshot BASE for humanization restore
            std::memcpy(ts.chordal_base, ts.steps, sizeof(ts.steps));
            ts.chordal_base_count = ts.step_count;
            ts.chordal_base_valid = true;
            // Load humanization probabilities
            ts.human_drop_prob = engine->pulsar_track_human_drop_prob[t].load(std::memory_order_relaxed);
            ts.human_ghost_prob = engine->pulsar_track_human_ghost_prob[t].load(std::memory_order_relaxed);
            ts.human_octave_prob = engine->pulsar_track_human_octave_prob[t].load(std::memory_order_relaxed);
            ts.human_ext_prob = engine->pulsar_track_human_ext_prob[t].load(std::memory_order_relaxed);
            ts.fill_every_n = engine->pulsar_track_fill_every_n[t].load(std::memory_order_relaxed);
            ts.fill_type = static_cast<FillTypeId>(
                engine->pulsar_track_fill_type[t].load(std::memory_order_relaxed));
            ts.fill_skip_prob = engine->pulsar_track_fill_skip_prob[t].load(std::memory_order_relaxed);
            // Seed at 1 so the first fill lands on bar N (not bar N+1). Bar 1
            // plays BASE without going through mutate_patterns(), so we lose
            // one increment up front and need to compensate.
            ts.bars_since_fill = 1;
        } else if (lick_len > 0 && lick_mode != LickMode::NONE && role == TrackRole::MELODIC) {
            if (lick_mode == LickMode::FILL) {
                // FILL: lick spans full step count, bypass bar strategy
                ts.step_count = step_count_config;
                if (bar_strategy == BarStrategy::CALL_RESPONSE) {
                    bar_strategy_call_response(ts.steps, step_count_config,
                                               state->lick, lick_len,
                                               eff_mutation,
                                               static_cast<uint8_t>(root), scale,
                                               base_seed ^ (t * 7919u),
                                               state->lick_octave,
                                               genre.note_range_low, genre.note_range_high,
                                               state->lick_loop_length);
                } else {
                    generate_lick_pattern(ts.steps, ts.step_count, state->lick, lick_len,
                                          eff_mutation, static_cast<uint8_t>(root), scale,
                                          base_seed ^ (t * 7919u), 0,
                                          state->lick_octave,
                                          genre.note_range_low, genre.note_range_high,
                                          state->lick_loop_length);
                }
                // No bar strategy — FILL owns the full pattern
            } else {
                // SQUASH: lick compressed to bar1_len, bar strategy handles bar 2
                ts.step_count = bar1_len;
                generate_lick_pattern(ts.steps, bar1_len, state->lick, lick_len,
                                      eff_mutation, static_cast<uint8_t>(root), scale,
                                      base_seed ^ (t * 7919u), 0,
                                      state->lick_octave,
                                      genre.note_range_low, genre.note_range_high,
                                      state->lick_loop_length);
                // Apply bar strategy for bar 2
                if (step_count_config > 16) {
                    apply_bar_strategy(ts, t, bar_strategy, (role == TrackRole::PERCUSSIVE), genre,
                                       static_cast<uint8_t>(root), scale,
                                       engine->pulsar_energy.load(std::memory_order_relaxed),
                                       engine->pulsar_complexity.load(std::memory_order_relaxed),
                                       state->lick, lick_len, eff_mutation,
                                       base_seed ^ (t * 13331u),
                                       state->lick_octave,
                                       state->lick_loop_length);
                }
            }
        } else {
            // No lick — generative pattern
            float hold_prob = engine->pulsar_track_hold_probability[t].load(std::memory_order_relaxed);
            int hold_min = engine->pulsar_track_hold_length_min[t].load(std::memory_order_relaxed);
            int hold_max = engine->pulsar_track_hold_length_max[t].load(std::memory_order_relaxed);
            if (hold_min < 1) hold_min = 2;
            if (hold_max < hold_min) hold_max = hold_min;
            float density_ovr = engine->pulsar_track_density_override[t].load(std::memory_order_relaxed);
            int nr_low = engine->pulsar_track_note_range_low[t].load(std::memory_order_relaxed);
            int nr_high = engine->pulsar_track_note_range_high[t].load(std::memory_order_relaxed);
            int eng_note_min = (ts.engine_index >= 0 && ts.engine_index < 24)
                ? kEngineModRanges[ts.engine_index].note_min : 0;
            generate_track_pattern(ts, t, (role == TrackRole::PERCUSSIVE), genre,
                                   static_cast<uint8_t>(root), scale, bar1_len, base_seed,
                                   0, hold_prob, hold_min, hold_max,
                                   density_ovr, nr_low, nr_high, eng_note_min);

            if (step_count_config > 16) {
                apply_bar_strategy(ts, t, bar_strategy, (role == TrackRole::PERCUSSIVE), genre,
                                   static_cast<uint8_t>(root), scale,
                                   engine->pulsar_energy.load(std::memory_order_relaxed),
                                   engine->pulsar_complexity.load(std::memory_order_relaxed),
                                   state->lick, lick_len, eff_mutation,
                                   base_seed ^ (t * 13331u),
                                   state->lick_octave,
                                   state->lick_loop_length);
            }
        }

        ts.engine_index = engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed);

        // Reset state
        ts.playhead = 0;
        ts.gate_timer = 0.0f;
        ts.voice_active = false;
        ts.swing_offset = 0.0;
        ts.tides_env.Init();
        ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
        ts.tides_env_level = 0.0f;
        ts.current_pitch = 60.0f;
        ts.target_pitch = 60.0f;
        ts.glide_rate = 0.0f;
        ts.prev_step_gated = false;
        ts.last_chord_index = -1;
        ts.mod_poly_lfo.Init();
        ts.mod_slope.Init();
        ts.drone_lfo.Init();
        ts.drone_lfo_initialized = true;
        std::memset(ts.mod_lfo_output, 0, sizeof(ts.mod_lfo_output));
        ts.mod_lfo_initialized = true;
        ts.in_hold = false;
        ts.hold_steps_remaining = 0;
        state->track_solo_behavior[t].last_interval = 0;
    }

    // Initialize chord progression
    int step_count_for_chords = engine->pulsar_step_count.load(std::memory_order_relaxed);
    if (step_count_for_chords <= 0) step_count_for_chords = 16;
    if (step_count_for_chords > kMaxPulsarSteps) step_count_for_chords = kMaxPulsarSteps;
    bool custom_active = engine->pulsar_chord_matrix_active.load(std::memory_order_relaxed) > 0;
    state->chord_state.use_custom_matrix = custom_active;
    if (custom_active) {
        for (int i = 0; i < 49; i++) {
            int r = i / 7, c = i % 7;
            state->chord_state.custom_matrix[r][c] =
                engine->pulsar_chord_matrix[i].load(std::memory_order_relaxed);
        }
    }

    // Optional caller-supplied progression (overrides the template's chord sequence)
    int custom_prog[kMaxProgressionLength];
    int custom_prog_len = 0;
    if (engine->pulsar_custom_progression_active.load(std::memory_order_relaxed) > 0) {
        custom_prog_len = engine->pulsar_custom_progression_length.load(std::memory_order_relaxed);
        if (custom_prog_len > kMaxProgressionLength) custom_prog_len = kMaxProgressionLength;
        for (int i = 0; i < custom_prog_len; i++) {
            custom_prog[i] = engine->pulsar_custom_progression[i].load(std::memory_order_relaxed);
        }
    }
    init_chord_progression(state->chord_state, genre.progression_style,
                           genre.chords_per_bar, step_count_for_chords, base_seed,
                           custom_prog_len > 0 ? custom_prog : nullptr,
                           custom_prog_len);
    // Per-chord glides for the vibe-level progression. Only meaningful when
    // a custom progression is active; otherwise zero (no glide).
    for (int i = 0; i < kMaxProgressionLength; i++) {
        state->chord_state.progression_glides[i] = (custom_prog_len > 0)
            ? engine->pulsar_custom_progression_glide[i].load(std::memory_order_relaxed)
            : 0.0f;
    }
    state->chord_state.anchor_bars =
        engine->pulsar_progression_anchor.load(std::memory_order_relaxed);
    state->chord_state.drift_range =
        engine->pulsar_progression_drift_range.load(std::memory_order_relaxed);
    state->chord_state.bars_since_anchor = 0;

    // ── Load tension params from engine atomics ──
    reload_vibe_tension(engine, state);
    state->tension_intensity = 0.0f;
    state->tension_evo_smooth = 0.0f;

    // ── Load arrangement from engine atomics ──
    // pulsar_arrangement_generation uses acquire ordering as the fence.
    engine->pulsar_arrangement_generation.load(std::memory_order_acquire);
    int arr_active = engine->pulsar_arrangement_active.load(std::memory_order_relaxed);

    if (arr_active) {
        ArrangementParams& arr = state->arrangement;
        arr.active = true;
        arr.section_count = engine->pulsar_arrangement_section_count.load(std::memory_order_relaxed);
        if (arr.section_count > kMaxSections) arr.section_count = kMaxSections;
        arr.intro_index = engine->pulsar_arrangement_intro_index.load(std::memory_order_relaxed);
        arr.outro_index = engine->pulsar_arrangement_outro_index.load(std::memory_order_relaxed);

        for (int s = 0; s < arr.section_count; s++) {
            SectionParam& sec = arr.sections[s];
            int base = s * 21;
            sec.bars_min              = static_cast<int>(engine->pulsar_section_data[base + 0].load(std::memory_order_relaxed));
            sec.bars_max              = static_cast<int>(engine->pulsar_section_data[base + 1].load(std::memory_order_relaxed));
            int loaded_bar_step       = static_cast<int>(engine->pulsar_section_data[base + 2].load(std::memory_order_relaxed));
            sec.bar_step              = (loaded_bar_step >= 1) ? loaded_bar_step : 1;
            sec.recency_decay         = engine->pulsar_section_data[base + 3].load(std::memory_order_relaxed);
            sec.transition_count      = static_cast<int>(engine->pulsar_section_data[base + 4].load(std::memory_order_relaxed));
            if (sec.transition_count > kMaxSectionTransitions) sec.transition_count = kMaxSectionTransitions;
            sec.macro_overrides.energy     = engine->pulsar_section_data[base + 5].load(std::memory_order_relaxed);
            sec.macro_overrides.complexity = engine->pulsar_section_data[base + 6].load(std::memory_order_relaxed);
            sec.macro_overrides.space      = engine->pulsar_section_data[base + 7].load(std::memory_order_relaxed);
            sec.macro_overrides.mood       = engine->pulsar_section_data[base + 8].load(std::memory_order_relaxed);
            // Solo mode system
            sec.solo_mode        = static_cast<SoloModeId>(static_cast<int>(engine->pulsar_section_data[base + 9].load(std::memory_order_relaxed)));
            sec.solo_probability = engine->pulsar_section_data[base + 10].load(std::memory_order_relaxed);
            sec.solo_mutation_rate = engine->pulsar_section_data[base + 11].load(std::memory_order_relaxed);
            sec.solo_lick_influence = engine->pulsar_section_data[base + 12].load(std::memory_order_relaxed);
            sec.solo_bars_min    = static_cast<int>(engine->pulsar_section_data[base + 13].load(std::memory_order_relaxed));
            sec.solo_bars_max    = static_cast<int>(engine->pulsar_section_data[base + 14].load(std::memory_order_relaxed));

            // Section-level comping overrides (slots 18-20); -1 = no override
            {
                float cs = engine->pulsar_section_data[base + 18].load(std::memory_order_relaxed);
                sec.comping_style_override = (cs < 0.0f) ? -1 : static_cast<int>(cs);
                float ci = engine->pulsar_section_data[base + 19].load(std::memory_order_relaxed);
                sec.comping_inversion_override = (ci < 0.0f) ? -1 : static_cast<int>(ci);
                float cf = engine->pulsar_section_data[base + 20].load(std::memory_order_relaxed);
                sec.chord_follow_override = (cf < 0.0f) ? -1 : static_cast<int>(cf);
            }

            // Per-track section overrides; -1 = no override (per-track wins over section-level)
            {
                int tbase = s * kNumPulsarTracks;
                for (int t = 0; t < kNumPulsarTracks; t++) {
                    sec.track_comping_style_override[t] = engine->pulsar_section_track_comping_style[tbase + t].load(std::memory_order_relaxed);
                    sec.track_inversion_override[t]     = engine->pulsar_section_track_inversion[tbase + t].load(std::memory_order_relaxed);
                    sec.track_arp_mode_override[t]      = engine->pulsar_section_track_arp_mode[tbase + t].load(std::memory_order_relaxed);
                    sec.track_chord_follow_override[t]  = engine->pulsar_section_track_chord_follow[tbase + t].load(std::memory_order_relaxed);
                }
            }

            // --- Per-section progression override ---
            sec.custom_progression_length = std::clamp(
                engine->pulsar_section_progression_length[s].load(std::memory_order_relaxed),
                0, kMaxProgressionLength);
            for (int i = 0; i < kMaxProgressionLength; i++) {
                int d = engine->pulsar_section_progression_degrees[s * kMaxProgressionLength + i].load(
                    std::memory_order_relaxed);
                if (d < 0) d = 0;
                if (d > 6) d = 6;
                sec.custom_progression[i] = static_cast<int8_t>(d);
            }
            sec.chords_per_bar_override = engine->pulsar_section_chords_per_bar[s].load(
                std::memory_order_relaxed);
            if (sec.chords_per_bar_override < 0) sec.chords_per_bar_override = 0;
            if (sec.chords_per_bar_override > 4) sec.chords_per_bar_override = 4;

            // --- Per-section tension override ---
            // SectionParam already has has_tension_override / tension_override fields;
            // the section_changed block at orpheus_unit_pulsar.cpp:1351 already reads them.
            // This path wires the atomics that were previously never populated.
            {
                int tension_active = engine->pulsar_section_tension_active[s].load(
                    std::memory_order_relaxed);
                sec.has_tension_override = (tension_active == 1);
                if (sec.has_tension_override) {
                    int tb = s * 21;
                    auto L = [&](int f) {
                        return engine->pulsar_section_tension_data[tb + f].load(
                            std::memory_order_relaxed);
                    };
                    sec.tension_override.inner_bars         = static_cast<int>(L(0));
                    sec.tension_override.outer_bars         = static_cast<int>(L(1));
                    sec.tension_override.outer_depth        = L(2);
                    sec.tension_override.volume             = L(3);
                    sec.tension_override.timing             = L(4);
                    sec.tension_override.octave_shift       = (L(5) != 0.0f);
                    sec.tension_override.key_shift          = static_cast<int>(L(6));
                    sec.tension_override.half_lick          = (L(7) != 0.0f);
                    sec.tension_override.chromatic_passing  = L(8);
                    sec.tension_override.evo_timbre_low     = L(9);
                    sec.tension_override.evo_timbre_high    = L(10);
                    sec.tension_override.evo_timbre_prob    = L(11);
                    sec.tension_override.evo_morph_low      = L(12);
                    sec.tension_override.evo_morph_high     = L(13);
                    sec.tension_override.evo_morph_prob     = L(14);
                    sec.tension_override.evo_harm_low       = L(15);
                    sec.tension_override.evo_harm_high      = L(16);
                    sec.tension_override.evo_harm_prob      = L(17);
                    sec.tension_override.evo_attack_point   = L(18);
                    sec.tension_override.evo_release_speed  = L(19);
                    sec.tension_override.spurt_chance       = L(20);
                }
            }

            // --- Per-section CompingHumanization override ---
            // When active, replaces the per-track humanization probabilities for
            // ALL CHORDAL tracks during this section. See apply_humanization call
            // site in mutate_patterns().
            {
                int ch_active = engine->pulsar_section_comping_humanization_active[s].load(
                    std::memory_order_relaxed);
                sec.has_comping_humanization_override = (ch_active != 0);
                if (sec.has_comping_humanization_override) {
                    int chBase = s * 4;
                    sec.comping_humanization_drop      = engine->pulsar_section_comping_humanization_data[chBase + 0].load(std::memory_order_relaxed);
                    sec.comping_humanization_ghost     = engine->pulsar_section_comping_humanization_data[chBase + 1].load(std::memory_order_relaxed);
                    sec.comping_humanization_octave    = engine->pulsar_section_comping_humanization_data[chBase + 2].load(std::memory_order_relaxed);
                    sec.comping_humanization_extension = engine->pulsar_section_comping_humanization_data[chBase + 3].load(std::memory_order_relaxed);
                }
            }

            // Unpack transitions for this section (3 floats per edge: target, weight, transition_bars)
            int trans_base = (s * kMaxSections) * 3;
            for (int e = 0; e < sec.transition_count; e++) {
                sec.transitions[e].target_index = static_cast<int>(engine->pulsar_section_transitions[trans_base + e * 3 + 0].load(std::memory_order_relaxed));
                sec.transitions[e].weight        = engine->pulsar_section_transitions[trans_base + e * 3 + 1].load(std::memory_order_relaxed);
                int tb = static_cast<int>(engine->pulsar_section_transitions[trans_base + e * 3 + 2].load(std::memory_order_relaxed));
                sec.transitions[e].transition_bars = (tb < 0) ? 0 : tb;
            }
        }

        // Unpack per-track solo behavior (15 floats per track)
        for (int t = 0; t < kNumPulsarTracks; t++) {
            int tb = t * 15;
            SoloBehaviorParam& sb = state->track_solo_behavior[t];
            sb.volume_boost        = engine->pulsar_track_solo_behavior[tb + 0].load(std::memory_order_relaxed);
            sb.density_boost       = engine->pulsar_track_solo_behavior[tb + 1].load(std::memory_order_relaxed);
            sb.timbre_min          = engine->pulsar_track_solo_behavior[tb + 2].load(std::memory_order_relaxed);
            sb.timbre_max          = engine->pulsar_track_solo_behavior[tb + 3].load(std::memory_order_relaxed);
            sb.morph_min           = engine->pulsar_track_solo_behavior[tb + 4].load(std::memory_order_relaxed);
            sb.morph_max           = engine->pulsar_track_solo_behavior[tb + 5].load(std::memory_order_relaxed);
            sb.harmonics_min       = engine->pulsar_track_solo_behavior[tb + 6].load(std::memory_order_relaxed);
            sb.harmonics_max       = engine->pulsar_track_solo_behavior[tb + 7].load(std::memory_order_relaxed);
            sb.evolution_intensity = engine->pulsar_track_solo_behavior[tb + 8].load(std::memory_order_relaxed);
            sb.fill_probability    = engine->pulsar_track_solo_behavior[tb + 9].load(std::memory_order_relaxed);
            sb.rest_probability    = engine->pulsar_track_solo_behavior[tb + 10].load(std::memory_order_relaxed);
            sb.hold_probability    = engine->pulsar_track_solo_behavior[tb + 11].load(std::memory_order_relaxed);
            sb.density_curve_min   = engine->pulsar_track_solo_behavior[tb + 12].load(std::memory_order_relaxed);
            sb.density_curve_max   = engine->pulsar_track_solo_behavior[tb + 13].load(std::memory_order_relaxed);
            sb.chromatic_passing   = engine->pulsar_track_solo_behavior[tb + 14].load(std::memory_order_relaxed);
        }

        // Unpack per-track Markov interval weights (15 floats per track)
        for (int t = 0; t < kNumPulsarTracks; t++) {
            int mb = t * kMarkovIntervals;
            for (int i = 0; i < kMarkovIntervals; i++) {
                state->track_solo_behavior[t].interval_weights[i] =
                    engine->pulsar_track_solo_markov[mb + i].load(std::memory_order_relaxed);
            }
        }

        // Set envelope profile for second-order Markov matrix lookup
        for (int t = 0; t < kNumPulsarTracks; t++) {
            state->track_solo_behavior[t].profile =
                static_cast<PulsarEnvelopeProfile>(
                    engine->pulsar_track_envelope[t].load(std::memory_order_relaxed));
        }

        // Unpack per-track ducking (6 floats per track)
        for (int t = 0; t < kNumPulsarTracks; t++) {
            int db = t * 6;
            DuckingParam& dp = state->track_ducking[t];
            dp.volume_reduction  = engine->pulsar_track_ducking[db + 0].load(std::memory_order_relaxed);
            dp.density_reduction = engine->pulsar_track_ducking[db + 1].load(std::memory_order_relaxed);
            dp.ghost_reduction   = engine->pulsar_track_ducking[db + 2].load(std::memory_order_relaxed);
            dp.fill_suppression  = engine->pulsar_track_ducking[db + 3].load(std::memory_order_relaxed);
            dp.simplify          = engine->pulsar_track_ducking[db + 4].load(std::memory_order_relaxed) > 0.5f;
            dp.reverb_boost      = engine->pulsar_track_ducking[db + 5].load(std::memory_order_relaxed);
        }

        // Initialize section state machine
        init_section_state(state->section_state, arr, state->mutation_seed);

        // If the initial section has a progression or chords_per_bar override,
        // re-init chord_state so it lands in the section's sequence before playback.
        // init_chord_progression resets chord_index to 0.
        if (arr.active && arr.section_count > 0) {
            int init_sec = state->section_state.current_section;
            if (init_sec >= 0 && init_sec < arr.section_count) {
                const SectionParam& sec = arr.sections[init_sec];
                if (sec.custom_progression_length > 0 || sec.chords_per_bar_override > 0) {
                    restart_progression_for_section(state, sec, engine);
                }

                // Apply per-track section overrides for the initial section so
                // chordal patterns generated above match the active section's
                // overrides on first play.
                int cd0 = state->chord_state.progression[state->chord_state.chord_index];
                for (int t = 0; t < kNumPulsarTracks; t++) {
                    PulsarTrackState& ts = state->tracks[t];
                    if (ts.role == TrackRole::CHORDAL) {
                        int cs_ovr = sec.track_comping_style_override[t];
                        if (cs_ovr >= 0) {
                            CompingStyleId target = static_cast<CompingStyleId>(cs_ovr);
                            if (target != ts.comping_style) {
                                ts.comping_style = target;
                                if (ts.chordal_base_valid) {
                                    generate_chordal_pattern(
                                        ts.chordal_base, ts.chordal_base_count,
                                        ts.comping_style, cd0,
                                        static_cast<uint8_t>(root), scale,
                                        static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed)),
                                        static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed)));
                                    std::memcpy(ts.steps, ts.chordal_base,
                                                sizeof(PulsarStep) * ts.step_count);
                                }
                            }
                        }
                        int inv_ovr = sec.track_inversion_override[t];
                        if (inv_ovr >= 0) ts.section_inversion = static_cast<SectionInversionId>(inv_ovr);
                        int arp_ovr = sec.track_arp_mode_override[t];
                        if (arp_ovr >= 0) ts.arp_mode = static_cast<ArpModeId>(arp_ovr);
                    }
                    if (ts.role == TrackRole::MELODIC || ts.role == TrackRole::CHORDAL) {
                        int cf_ovr = sec.track_chord_follow_override[t];
                        if (cf_ovr >= 0) ts.chord_follow = static_cast<ChordFollowMode>(cf_ovr);
                    }
                }
            }
        }

        // Zero out solo state
        std::memset(&state->band_solo_state, 0, sizeof(BandSoloState));

        // Load band solo config
        bool band_active = engine->pulsar_band_active.load(std::memory_order_relaxed) > 0;
        state->has_band_solo = band_active;
        if (band_active) {
            BandSoloConfigParam& bc = state->band_solo_config;
            bc.member_count = engine->pulsar_band_member_count.load(std::memory_order_relaxed);
            if (bc.member_count > kMaxBandMembers) bc.member_count = kMaxBandMembers;

            for (int m = 0; m < bc.member_count; m++) {
                int base = m * 12;
                BandMemberParam& bm = bc.members[m];
                bm.track_count = static_cast<int>(engine->pulsar_band_member_data[base + 0].load(std::memory_order_relaxed));
                if (bm.track_count > kNumPulsarTracks) bm.track_count = kNumPulsarTracks;
                for (int t = 0; t < bm.track_count; t++) {
                    bm.tracks[t] = static_cast<int>(engine->pulsar_band_member_data[base + 1 + t].load(std::memory_order_relaxed));
                }
                bm.always_active = engine->pulsar_band_member_data[base + 9].load(std::memory_order_relaxed) > 0.5f;
                bm.loudness      = engine->pulsar_band_member_data[base + 10].load(std::memory_order_relaxed);
                bm.creativity    = engine->pulsar_band_member_data[base + 11].load(std::memory_order_relaxed);
            }

            // BAND-01: Kotlin packs the handoff/pull-in matrices row-major with
            // stride = member_count (N), but the consumers (select_next_lead,
            // pull-in roll) index stride-kMaxBandMembers. Read the raw stride-N
            // atomics into a temp, then re-pack into the stride-8 layout — and
            // zero unused rows so a previous vibe with more members can't leak
            // (band_solo_config is persistent across loads).
            int N = bc.member_count;
            float ho_src[kMaxBandMembers * kMaxBandMembers] = {};
            float pi_src[kMaxBandMembers * kMaxBandMembers] = {};
            for (int i = 0; i < N * N && i < 64; i++) {
                ho_src[i] = engine->pulsar_band_handoff_matrix[i].load(std::memory_order_relaxed);
                pi_src[i] = engine->pulsar_band_pull_in_matrix[i].load(std::memory_order_relaxed);
            }
            pack_band_matrix(bc.handoff_matrix, ho_src, N);
            pack_band_matrix(bc.pull_in_matrix, pi_src, N);
            bc.pull_in_bars_min = engine->pulsar_band_pull_in_bars_min.load(std::memory_order_relaxed);
            bc.pull_in_bars_max = engine->pulsar_band_pull_in_bars_max.load(std::memory_order_relaxed);
            bc.improv_carryover = engine->pulsar_band_improv_carryover.load(std::memory_order_relaxed);
            bc.probability = engine->pulsar_band_probability.load(std::memory_order_relaxed);
            bc.bars_per_lead_min = engine->pulsar_band_bars_per_lead_min.load(std::memory_order_relaxed);
            bc.bars_per_lead_max = engine->pulsar_band_bars_per_lead_max.load(std::memory_order_relaxed);
        }

        // Set initial arrangement viz read-back. Pre-roll model: section total
        // is just bars_remaining — the ramp zone (if any) lives INSIDE that count.
        int init_sec = state->section_state.current_section;
        state->arr_viz_section_index.store(init_sec, std::memory_order_relaxed);
        state->arr_viz_bars_elapsed.store(0, std::memory_order_relaxed);
        state->arr_viz_bars_total.store(state->section_state.bars_remaining, std::memory_order_relaxed);
        state->arr_viz_solo_active.store(false, std::memory_order_relaxed);
        state->arr_viz_solo_track.store(-1, std::memory_order_relaxed);
        state->arr_viz_solo_mode.store(0, std::memory_order_relaxed);
    } else {
        // No arrangement active: clear all state machines
        // (solo modifiers were already cleared at the top of load_vibe)
        state->arrangement.active = false;
        state->arrangement.section_count = 0;
        std::memset(&state->section_state, 0, sizeof(SectionState));
        std::memset(&state->band_solo_state, 0, sizeof(BandSoloState));
        state->has_band_solo = false;
        state->arr_viz_section_index.store(-1, std::memory_order_relaxed);
    }

    // Clear any stale outro request so a request from a prior vibe does not
    // bleed into the new arrangement. Placed after arrangement loading so that
    // it always runs regardless of whether arr_active is set.
    engine->pulsar_arrangement_outro_request.store(0, std::memory_order_relaxed);

    state->current_vibe_generation = generation;
    state->last_root_note = root;
    state->last_scale_index = scale_idx;
    state->clock_accumulator = 0.0;
    // mutation_seed is reset earlier (before init_section_state consumes it).
    state->loop_count = 0;
    state->loops_since_reset = 0;
    std::memset(state->drunk_offsets, 0, sizeof(state->drunk_offsets));
    std::memset(state->drunk_targets, 0, sizeof(state->drunk_targets));
    state->tempo_drift = 0.0f;
    state->tempo_drift_target = 0.0f;
    state->tempo_drift_countdown = 0;

    // ── Clear effect buffers so old vibe tails don't bleed through ──
    std::memset(engine->pulsar_delay_buf_1l, 0, sizeof(engine->pulsar_delay_buf_1l));
    std::memset(engine->pulsar_delay_buf_1r, 0, sizeof(engine->pulsar_delay_buf_1r));
    std::memset(engine->pulsar_delay_buf_2l, 0, sizeof(engine->pulsar_delay_buf_2l));
    std::memset(engine->pulsar_delay_buf_2r, 0, sizeof(engine->pulsar_delay_buf_2r));
    engine->pulsar_delay_write_pos = 0;

    std::memset(engine->pulsar_rv_ap1, 0, sizeof(engine->pulsar_rv_ap1));
    std::memset(engine->pulsar_rv_ap2, 0, sizeof(engine->pulsar_rv_ap2));
    std::memset(engine->pulsar_rv_ap3, 0, sizeof(engine->pulsar_rv_ap3));
    std::memset(engine->pulsar_rv_ap4, 0, sizeof(engine->pulsar_rv_ap4));
    std::memset(engine->pulsar_rv_ap5, 0, sizeof(engine->pulsar_rv_ap5));
    std::memset(engine->pulsar_rv_ap6, 0, sizeof(engine->pulsar_rv_ap6));
    std::memset(engine->pulsar_rv_dly1, 0, sizeof(engine->pulsar_rv_dly1));
    std::memset(engine->pulsar_rv_dly2, 0, sizeof(engine->pulsar_rv_dly2));
    std::memset(engine->pulsar_rv_dly3, 0, sizeof(engine->pulsar_rv_dly3));
    std::memset(engine->pulsar_rv_dly4, 0, sizeof(engine->pulsar_rv_dly4));
    engine->pulsar_rv_write_pos = 0;
    engine->pulsar_rv_lp_decay1 = 0.0f;
    engine->pulsar_rv_lp_decay2 = 0.0f;
    engine->pulsar_rv_lfo_phase = 0.0f;
    engine->pulsar_rv_lfo2_phase = 0.37f;
    engine->pulsar_rv_lfo_value = 0.0f;
    engine->pulsar_rv_lfo2_value = 0.0f;

    // Reset per-track reverb send filter state
    for (int t = 0; t < kNumPulsarTracks; t++) {
        state->tracks[t].reverb_send_filter_state_l = 0.0f;
        state->tracks[t].reverb_send_filter_state_r = 0.0f;
    }
}

// ── Main process function ────────────────────────────────────────────

void unit_process_pulsar(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    if (num_frames > kMaxFrames) num_frames = kMaxFrames;

    float* out_l = engine->pulsar_out_l;
    float* out_r = engine->pulsar_out_r;

    // ── Lazy-init persistent state (owned by engine, freed in destroy) ──
    PulsarState* state = engine->pulsar_state;
    if (!state) {
        state = new PulsarState();
        // Zero POD fields only — don't memset the whole struct, which
        // contains OrpheusVoice instances with vtable pointers.
        state->clock_accumulator = 0.0;
        state->current_vibe_generation = -1;  // force vibe load
        state->initialized = false;
        state->smooth_energy = 0.5f;
        state->smooth_complexity = 0.3f;
        state->smooth_space = 0.4f;
        state->smooth_mood = 0.5f;
        // Seed from high-resolution clock so patterns differ across launches
        auto now = std::chrono::steady_clock::now().time_since_epoch();
        state->seed_counter = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(now).count());
        state->mutation_seed = state->seed_counter ^ 0xDEADBEEF;
        state->loop_count = 0;
        state->loops_since_reset = 0;
        std::memset(state->voice_alloc_buffers, 0, sizeof(state->voice_alloc_buffers));
        std::memset(state->drunk_offsets, 0, sizeof(state->drunk_offsets));
        std::memset(state->drunk_targets, 0, sizeof(state->drunk_targets));
        state->tempo_drift = 0.0f;
        state->tempo_drift_target = 0.0f;
        state->tempo_drift_countdown = 0;
        state->last_root_note = -1;
        state->last_scale_index = -1;
        for (int t = 0; t < kNumPulsarTracks; t++) {
            PulsarTrackState& ts = state->tracks[t];
            ts.step_count = 0;
            ts.playhead = 0;
            ts.engine_index = 0;
            ts.volume = 0.0f;
            ts.pan = 0.0f;
            ts.harmonics = 0.0f;
            ts.timbre = 0.0f;
            ts.morph = 0.0f;
            ts.gate_timer = 0.0f;
            ts.voice_active = false;
            ts.swing_offset = 0.0;
            ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
            ts.tides_env_level = 0.0f;
            ts.envelope_profile = ENV_PROFILE_RHYTHM;
            ts.current_pitch = 60.0f;
            ts.target_pitch = 60.0f;
            ts.glide_rate = 0.0f;
            ts.prev_step_gated = false;
        }
        engine->pulsar_state = state;
    }

    if (!state->initialized) {
        for (int t = 0; t < kNumPulsarTracks; t++) {
            stmlib::BufferAllocator allocator(
                state->voice_alloc_buffers[t], kVoiceAllocBytes_Pulsar);
            state->tracks[t].voice.Init(&allocator);
            state->tracks[t].braids_voice.Init();
            state->tracks[t].tides_env.Init();
            state->tracks[t].mod_poly_lfo.Init();
            state->tracks[t].mod_slope.Init();
            state->tracks[t].drone_lfo.Init();
            state->tracks[t].drone_lfo_initialized = true;
            std::memset(state->tracks[t].mod_lfo_output, 0, sizeof(state->tracks[t].mod_lfo_output));
            state->tracks[t].mod_lfo_initialized = true;
            state->tracks[t].in_hold = false;
            state->tracks[t].hold_steps_remaining = 0;
        }
        // PT-1: acquire load (like the steady-state path below) so the param
        // snapshot Kotlin published before its release store of vibe_generation
        // is coherent on this first-ever load too. A relaxed load here would
        // bypass the fence — and because load_vibe sets current_vibe_generation,
        // the acquire load at the steady-state check below becomes a no-op, so
        // this init path is the ONLY synchronization point on first load.
        load_vibe(state, engine->pulsar_vibe_generation.load(std::memory_order_acquire), engine);
        state->initialized = true;
    }

    // ── Check playing state ──
    bool playing = engine->pulsar_playing.load(std::memory_order_relaxed) != 0;
    float mix = engine->pulsar_mix.load(std::memory_order_relaxed);
    if (!playing || mix <= 0.001f) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        std::memset(u->output_buffers[OPORT_OUT_RIGHT], 0, num_frames * sizeof(float));
        // Zero effect send buffers to prevent delay/reverb tails from bleeding through
        std::memset(engine->pulsar_delay_send_l, 0, num_frames * sizeof(float));
        std::memset(engine->pulsar_delay_send_r, 0, num_frames * sizeof(float));
        std::memset(engine->pulsar_reverb_send_l, 0, num_frames * sizeof(float));
        std::memset(engine->pulsar_reverb_send_r, 0, num_frames * sizeof(float));
        return;
    }

    // ── Handle vibe change ──
    // PT-1: acquire load pairs with the release store in engine_routing.cpp so
    // the full param snapshot (per-track/tension/genre/band atomics written
    // before the Kotlin generation bump) is coherent before load_vibe reads it.
    int vibe_gen = engine->pulsar_vibe_generation.load(std::memory_order_acquire);
    if (vibe_gen != state->current_vibe_generation) {
        load_vibe(state, vibe_gen, engine);
    }

    // ── Re-quantize melodic notes when root/scale changes live ──
    int live_root = engine->pulsar_root_note.load(std::memory_order_relaxed);
    int live_scale = engine->pulsar_scale_index.load(std::memory_order_relaxed);
    if (live_scale < 0) live_scale = 0;
    if (live_scale >= kNumPulsarScales) live_scale = kNumPulsarScales - 1;

    if (live_root != state->last_root_note || live_scale != state->last_scale_index) {
        uint8_t root = static_cast<uint8_t>(live_root);
        const PulsarScale& scale = kPulsarScales[live_scale];
        for (int t = 0; t < kNumPulsarTracks; t++) {
            TrackRole role = static_cast<TrackRole>(engine->pulsar_track_role[t].load(std::memory_order_relaxed));
            if (role == TrackRole::PERCUSSIVE) continue;
            PulsarTrackState& ts = state->tracks[t];
            for (int s = 0; s < ts.step_count; s++) {
                if (ts.steps[s].gate) {
                    ts.steps[s].note = static_cast<uint8_t>(
                        quantize_to_scale(ts.steps[s].raw_note, root, scale));
                }
            }
        }
        state->last_root_note = live_root;
        state->last_scale_index = live_scale;
    }

    // ── Read and smooth macros (~10ms coefficient) ──
    float smooth_coeff = 1.0f - std::exp(-1.0f / (0.01f * sample_rate));

    float target_energy     = engine->pulsar_energy.load(std::memory_order_relaxed);
    float target_complexity = engine->pulsar_complexity.load(std::memory_order_relaxed);
    float target_space      = engine->pulsar_space.load(std::memory_order_relaxed);
    float target_mood       = engine->pulsar_mood.load(std::memory_order_relaxed);

    state->smooth_energy     += smooth_coeff * (target_energy     - state->smooth_energy);
    state->smooth_complexity += smooth_coeff * (target_complexity - state->smooth_complexity);
    state->smooth_space      += smooth_coeff * (target_space      - state->smooth_space);
    state->smooth_mood       += smooth_coeff * (target_mood       - state->smooth_mood);

    float energy     = clamp01(state->smooth_energy);
    float complexity = clamp01(state->smooth_complexity);
    float space      = clamp01(state->smooth_space);
    float mood       = clamp01(state->smooth_mood);

    // Section macro overrides (multipliers: 1.0=no change, >1=boost, <1=cut; -1=inactive).
    // When a transition is in flight, blend the multiplier from the current
    // section's override toward the staged destination's override via
    // section_macro_value(). Passing base=1.0 to the helper means an absent
    // override (-1) on either side collapses to "no change" — so e.g. blending
    // from an unset override toward energy=2.0 ramps the multiplier 1.0 → 2.0.
    //
    // Sub-bar smoothing: ss.transition_progress only updates on bar boundaries
    // (in advance_section), which makes an N-bar ramp jump in N coarse steps
    // and sounds abrupt. Compute a continuous progress here using track 0's
    // playhead within the current bar — gives one update per step (typically
    // 1/16 of a bar) which is effectively continuous.
    if (state->arrangement.active) {
        const SectionState& ss = state->section_state;
        bool in_transition = ss.transition_target >= 0;
        float progress = ss.transition_progress;
        if (in_transition && ss.next_section_trans_bars > 0) {
            int N = ss.next_section_trans_bars;
            const PulsarTrackState& t0 = state->tracks[0];
            float bar_phase = (t0.step_count > 0)
                ? static_cast<float>(t0.playhead) / static_cast<float>(t0.step_count)
                : 0.0f;
            // bars_remaining counts whole bars LEFT in the source section. As
            // we move through the current bar, the continuous remainder shrinks
            // from bars_remaining toward bars_remaining - 1.
            float bars_remaining_continuous =
                static_cast<float>(ss.bars_remaining) - bar_phase;
            if (bars_remaining_continuous < 0.0f) bars_remaining_continuous = 0.0f;
            progress = (static_cast<float>(N) - bars_remaining_continuous)
                       / static_cast<float>(N);
            if (progress < 0.0f) progress = 0.0f;
            if (progress > 1.0f) progress = 1.0f;
        }

        auto apply = [&](float base, float cur_ov, float nxt_ov) -> float {
            if (in_transition) {
                if (cur_ov < 0.0f && nxt_ov < 0.0f) return base;
                float mult = section_macro_value(1.0f, cur_ov, nxt_ov, progress);
                return clamp01(base * mult);
            }
            return (cur_ov >= 0.0f) ? clamp01(base * cur_ov) : base;
        };

        energy     = apply(energy,     ss.target_energy,     ss.next_energy);
        complexity = apply(complexity, ss.target_complexity, ss.next_complexity);
        space      = apply(space,      ss.target_space,      ss.next_space);
        mood       = apply(mood,       ss.target_mood,       ss.next_mood);
    }

    // Complexity and space are used below for swing, variation, morph, etc.

    // ── Determine BPM ──
    float bpm_override = engine->pulsar_bpm_override.load(std::memory_order_relaxed);
    float bpm = (bpm_override > 0.0f)
        ? bpm_override
        : engine->clock_bpm.load(std::memory_order_relaxed);
    if (bpm <= 0.0f) bpm = 120.0f;

    // 16th-note grid: 4 steps per beat
    double steps_per_second = (static_cast<double>(bpm) / 60.0) * 4.0;
    double samples_per_step = static_cast<double>(sample_rate) / steps_per_second;

    // ── Elastic tempo: slow random walk scaled by (1 - energy) ──
    float max_drift = (1.0f - energy) * 0.15f;

    state->tempo_drift_countdown -= num_frames;
    if (state->tempo_drift_countdown <= 0) {
        state->tempo_drift_target = (rand01(state->mutation_seed) - 0.5f) * 2.0f * max_drift;
        int bars = 4 + static_cast<int>(rand01(state->mutation_seed) * 4.0f);
        state->tempo_drift_countdown = static_cast<int>(samples_per_step * 16.0 * bars);
    }

    float drift_coeff = 1.0f - std::exp(-1.0f / std::max(static_cast<float>(samples_per_step * 32.0f), 1.0f));
    state->tempo_drift += drift_coeff * (state->tempo_drift_target - state->tempo_drift);
    state->tempo_drift = std::max(-max_drift, std::min(max_drift, state->tempo_drift));

    samples_per_step *= (1.0 + static_cast<double>(state->tempo_drift));

    // ── Zero output buffers ──
    std::memset(out_l, 0, num_frames * sizeof(float));
    std::memset(out_r, 0, num_frames * sizeof(float));

    // Zero per-bus accumulation buffers
    std::memset(engine->pulsar_bus_keys_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_keys_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_drums_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_drums_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_bass_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_bass_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_delay_send_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_delay_send_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_reverb_send_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_reverb_send_r, 0, num_frames * sizeof(float));

    // ── Clock: find step boundaries within this block ──
    // Swing: odd steps are delayed by swing_amount * 0.5 * samples_per_step.
    // We track a global step parity to alternate even/odd thresholds.
    static constexpr int kMaxStepBoundaries = 32;
    int step_boundary_samples[kMaxStepBoundaries];
    int num_boundaries = 0;

    // Use track 0's playhead parity to determine global swing phase
    // (all tracks advance together on the same clock)
    bool step_is_odd = (state->tracks[0].playhead % 2) != 0;

    for (int i = 0; i < num_frames; i++) {
        state->clock_accumulator += 1.0;

        // Swing: alternate threshold between straight and delayed
        // swing_amount from complexity macro (use track 0's macro as global ref)
        float swing_amount = lerp_macro(complexity, state->tracks[0].macro_map.complexity_swing);
        double threshold = samples_per_step;
        if (step_is_odd) {
            threshold += static_cast<double>(swing_amount) * 0.5 * samples_per_step;
        }

        if (state->clock_accumulator >= threshold) {
            state->clock_accumulator -= threshold;
            step_is_odd = !step_is_odd;
            if (num_boundaries < kMaxStepBoundaries) {
                step_boundary_samples[num_boundaries++] = i;
            }
        }
    }

    // ── Per-track: advance sequencer + render voice ──
    float track_buffer[kMaxFrames];

    // Pick the EDM-slot atomic when use_edm is true, else the *_space twin.
    // Used by all per-engine field reads inside the per-track loop. Each call
    // site computes its own `use_edm` from the current ts.engine_index — the
    // mid-loop section-advance block (gated by t==0 + bar boundary) can flip
    // engine_index, so we re-derive use_edm at each consumer rather than cache
    // it once at the top of the loop.
    #define PULSAR_PICK(field) (use_edm \
        ? engine->pulsar_track_##field[t].load(std::memory_order_relaxed) \
        : engine->pulsar_track_##field##_space[t].load(std::memory_order_relaxed))

    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];
        const PulsarTrackMacroMap& mm = ts.macro_map;

        // Per-block refresh of fields that section transitions can override.
        // Atomic-cheap; lets Kotlin push per-section overrides without a vibe reload.
        ts.envelope_profile = static_cast<PulsarEnvelopeProfile>(
            engine->pulsar_track_envelope[t].load(std::memory_order_relaxed));

        // ── Apply engine selection from atomics (immediate UI response) ──
        {
            int edm = engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed);
            int spa = engine->pulsar_track_engine_space[t].load(std::memory_order_relaxed);
            if (edm == spa || energy > 0.6f) {
                ts.engine_index = edm;
            } else if (energy < 0.4f) {
                ts.engine_index = spa;
            }
            // In crossfade zone (0.4-0.6): keep current — probabilistic pick at loop boundary.
            // Publish active slot unconditionally (even when edm == spa or the
            // crossfade zone left ts.engine_index untouched) so downstream units
            // never see a stale value across vibe transitions.
            engine->pulsar_track_active_engine[t].store(ts.engine_index, std::memory_order_relaxed);
        }

        // ── Resolve per-engine character knobs based on the active slot ──
        // Each TrackVoice has two OrpheusEngine instances (engineEdm/engineSpace).
        // When ts.engine_index matches the EDM slot, we use the *_edm atomics;
        // otherwise we fall through to *_space. This mirrors the lpg_mode/lpg_mode_space
        // selection already done at render time below.
        {
            const bool use_edm = (ts.engine_index ==
                engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed));
            ts.volume     = PULSAR_PICK(volume);
            ts.harmonics  = PULSAR_PICK(harmonics);
            ts.timbre     = PULSAR_PICK(timbre);
            ts.morph      = PULSAR_PICK(morph);
            ts.lpg_decay  = PULSAR_PICK(lpg_decay);
            ts.lpg_colour = PULSAR_PICK(lpg_colour);
            ts.pin_harmonics = PULSAR_PICK(pin_harmonics) != 0;
            ts.pin_timbre    = PULSAR_PICK(pin_timbre) != 0;
            ts.pin_morph     = PULSAR_PICK(pin_morph) != 0;
            ts.harmonics_modulation   = PULSAR_PICK(harmonics_modulation);
            ts.harmonics_macro_source = PULSAR_PICK(harmonics_macro_source);
            ts.harmonics_macro_range  = PULSAR_PICK(harmonics_macro_range);
        }

        // ── Apply macro modulation ──
        float mod_volume    = lerp_macro(energy, mm.energy_volume);
        float mod_harmonics = ts.pin_harmonics
            ? ts.harmonics
            : lerp_macro(mood, mm.mood_harmonics);
        float mod_timbre = ts.pin_timbre
            ? ts.timbre
            : lerp_macro(mood, mm.mood_timbre);
        float mod_morph = ts.pin_morph
            ? ts.morph
            : lerp_macro(space, mm.space_decay);
        float variation_amt = lerp_macro(complexity, mm.complexity_variation);

        // ── Evolution tension: modulate timbre/morph/harmonics based on phrase intensity ──
        float evo_weight = state->tension.track_evo_weight[t];
        if (evo_weight < 0.0f) {
            // Auto: 1.0 for tracks with mood_timbre macro range, 0.0 for others
            evo_weight = (mm.mood_timbre.max_value > 0.001f) ? 1.0f : 0.0f;
        }

        if (evo_weight > 0.001f) {
            // TENS-2: tension_evo_smooth is advanced once per bar in
            // mutate_patterns (so evo_release_speed expresses a multi-bar decay).
            // Here we only READ the section-wide smoothed value and scale it by
            // this track's evolution weight — no per-block stepping.
            float evo = state->tension_evo_smooth * evo_weight;

            // Timbre sweep (only when not pinned)
            if (!ts.pin_timbre && state->tension.evo_timbre_prob > 0.001f) {
                uint32_t rng = step_hash(ts.playhead, t + 13, state->loop_count);
                if ((rng & 0xFFFF) / 65535.0f < state->tension.evo_timbre_prob) {
                    float lo = state->tension.evo_timbre_low;
                    float hi = state->tension.evo_timbre_high;
                    mod_timbre = lo + (hi - lo) * evo;
                }
            }

            // Morph sweep (only when not pinned, only if specified)
            if (!ts.pin_morph && state->tension.evo_morph_low >= 0.0f && state->tension.evo_morph_prob > 0.001f) {
                uint32_t rng = step_hash(ts.playhead, t + 17, state->loop_count);
                if ((rng & 0xFFFF) / 65535.0f < state->tension.evo_morph_prob) {
                    float lo = state->tension.evo_morph_low;
                    float hi = state->tension.evo_morph_high;
                    mod_morph = lo + (hi - lo) * evo;
                }
            }

            // Harmonics nudge (only when not pinned, only if specified)
            if (!ts.pin_harmonics && state->tension.evo_harm_low >= 0.0f && state->tension.evo_harm_prob > 0.001f) {
                uint32_t rng = step_hash(ts.playhead, t + 23, state->loop_count);
                if ((rng & 0xFFFF) / 65535.0f < state->tension.evo_harm_prob) {
                    float lo = state->tension.evo_harm_low;
                    float hi = state->tension.evo_harm_high;
                    mod_harmonics = lo + (hi - lo) * evo;
                }
            }
        }

        float track_volume = ts.volume * clamp01(mod_volume);
        // Energy-aware volume shaping
        if (t >= 5) {
            // TEXTURE/FX tracks: duck in mid-energy zone
            track_volume *= texture_energy_curve(energy);
        } else if (t == 3 || t == 4) {
            // Lick/bass tracks: boost in mid-energy zone
            track_volume *= lick_bass_energy_boost(energy);
        }
        // Per-band user gains from the MixerPanel — multiplied AFTER section
        // volumes so the user can scale a band without sections clobbering
        // them. Stored ports are 0..1 fader *travel*; pulsar_fader_to_gain()
        // applies the Penny & Giles-style console law (unity at 0.75 travel,
        // +6 dB at full, log-tapered cuts below). Atomic defaults all init
        // to 0.75 so a fresh engine sounds identical to the legacy unity
        // behavior.
        float perc_mix  = engine->pulsar_perc_mix.load(std::memory_order_relaxed);
        float bass_gain = engine->pulsar_bass_gain.load(std::memory_order_relaxed);
        float keys_gain = engine->pulsar_keys_gain.load(std::memory_order_relaxed);
        float fx_gain   = engine->pulsar_fx_gain.load(std::memory_order_relaxed);
        if (t <= 2)            track_volume *= pulsar_fader_to_gain(perc_mix);
        else if (t == 3)       track_volume *= pulsar_fader_to_gain(bass_gain);
        else if (t == 4)       track_volume *= pulsar_fader_to_gain(keys_gain);
        else /* t == 5,6,7 */  track_volume *= pulsar_fader_to_gain(fx_gain);

        // Track mute: zero volume but keep sequencer running
        bool track_muted = engine->pulsar_track_mute[t].load(std::memory_order_relaxed) != 0;
        if (track_muted) track_volume = 0.0f;

        // ── Pan gains ──
        float pan_l, pan_r;
        constant_power_pan(ts.pan, pan_l, pan_r);

        // ── Process step boundaries for this track ──
        // Advance playhead at each step boundary.
        // Determine gate state for voice rendering.
        for (int b = 0; b < num_boundaries; b++) {
            int prev_playhead = ts.playhead;
            ts.playhead = (ts.playhead + 1) % ts.step_count;

            // Advance chord progression on track 0 step boundaries
            if (t == 0) {
                advance_chord(state->chord_state, complexity, mood);
            }

            // Detect loop wrap (playhead wrapped to 0) — trigger mutation
            if (ts.playhead == 0 && prev_playhead > 0 && t == 0) {
                mutate_patterns(state, complexity, engine);

                // ── Section / Solo advancement ──
                if (state->arrangement.active) {
                    // Pull any pending outro request before advancing so the
                    // boundary handler in advance_section() can re-route to
                    // arr.outro_index this bar. Sticky once set; cleared by
                    // load_vibe() on arrangement reload.
                    const int outro_req = engine->pulsar_arrangement_outro_request.load(
                        std::memory_order_relaxed);
                    if (outro_req != 0 && !state->section_state.outro_triggered) {
                        state->section_state.outro_triggered = true;
                    }

                    bool section_changed = advance_section(
                        state->section_state, state->arrangement, state->mutation_seed);

                    if (section_changed) {
                        int cur_sec = state->section_state.current_section;
                        const SectionParam& sec = state->arrangement.sections[cur_sec];

                        // Apply section macro overrides
                        state->section_state.target_energy = sec.macro_overrides.energy;
                        state->section_state.target_complexity = sec.macro_overrides.complexity;
                        state->section_state.target_space = sec.macro_overrides.space;
                        state->section_state.target_mood = sec.macro_overrides.mood;

                        // Always restart the chord progression at section entry.
                        // Uses the section's override when set, else the vibe's
                        // progression. init_chord_progression resets chord_index to 0.
                        restart_progression_for_section(state, sec, engine);

                        // --- Apply section tension override, else revert to vibe-level ---
                        if (sec.has_tension_override) {
                            state->tension = sec.tension_override;
                        } else {
                            // Reload vibe tension so a prior section's override does not
                            // leak forward into a section with no override of its own.
                            reload_vibe_tension(engine, state);
                        }

                        // --- Always reset the tension phase at section entry ---
                        // Cosmetic: tension_intensity is recomputed every bar from loop_count
                        // in mutate_patterns(), so this zero is observable only on the
                        // entry bar. tension_evo_smooth is actually smoothed across bars,
                        // so zeroing it here DOES reset the timbre-evolution arc.
                        state->tension_intensity  = 0.0f;
                        state->tension_evo_smooth = 0.0f;

                        // Start solo: new SoloMode system > band system > legacy
                        if (sec.solo_mode != SoloModeId::NONE && state->has_band_solo) {
                            // Initialize live lick for LickBuilder mode
                            if (sec.solo_mode == SoloModeId::LICK_BUILDER && state->lick_length > 0) {
                                // Copy from struct-of-arrays lick into separate live buffers
                                int n = state->lick_length < 32 ? state->lick_length : 32;
                                state->live_lick_length = n;
                                state->live_lick_active = true;
                                for (int i = 0; i < n; i++) {
                                    state->live_lick_degrees[i] = state->lick[i].scale_degree;
                                    state->live_lick_durations[i] = state->lick[i].duration;
                                    state->live_lick_velocities[i] = state->lick[i].velocity;
                                    // MUT-4: snapshot the section-entry degrees to clamp drift against.
                                    state->live_lick_base_degrees[i] = state->lick[i].scale_degree;
                                }
                            }
                            start_band_solo(state->band_solo_state, state->band_solo_config,
                                            sec, state->tracks, state->mutation_seed);
                        } else {
                            if (state->band_solo_state.active) {
                                clear_band_solo(state->band_solo_state, state->tracks);
                            }
                        }

                        // Apply section-level comping overrides to all CHORDAL tracks
                        // (-1 = restore default snapshotted at load_vibe)
                        {
                            int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
                            if (si < 0) si = 0;
                            if (si >= kNumPulsarScales) si = kNumPulsarScales - 1;
                            const PulsarScale& sc_now = kPulsarScales[si];
                            int root_now = engine->pulsar_root_note.load(std::memory_order_relaxed);
                            int cd = state->chord_state.progression[state->chord_state.chord_index];
                            for (int t = 0; t < kNumPulsarTracks; t++) {
                                if (state->tracks[t].role != TrackRole::CHORDAL) continue;
                                // Per-track override wins over section-level override.
                                int per_track = sec.track_comping_style_override[t];
                                CompingStyleId target = (per_track >= 0)
                                    ? static_cast<CompingStyleId>(per_track)
                                    : (sec.comping_style_override >= 0)
                                        ? static_cast<CompingStyleId>(sec.comping_style_override)
                                        : state->tracks[t].default_comping_style;
                                if (target != state->tracks[t].comping_style) {
                                    state->tracks[t].comping_style = target;
                                    if (state->tracks[t].chordal_base_valid) {
                                        generate_chordal_pattern(
                                            state->tracks[t].chordal_base, state->tracks[t].chordal_base_count,
                                            state->tracks[t].comping_style, cd,
                                            static_cast<uint8_t>(root_now), sc_now,
                                            static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed)),
                                            static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed)));
                                        std::memcpy(state->tracks[t].steps, state->tracks[t].chordal_base,
                                                    sizeof(PulsarStep) * state->tracks[t].step_count);
                                    }
                                }
                            }
                        }

                        // Inversion override (per-track wins over section-level; -1 = restore default)
                        for (int t = 0; t < kNumPulsarTracks; t++) {
                            if (state->tracks[t].role != TrackRole::CHORDAL) continue;
                            int per_track = sec.track_inversion_override[t];
                            SectionInversionId target = (per_track >= 0)
                                ? static_cast<SectionInversionId>(per_track)
                                : (sec.comping_inversion_override >= 0)
                                    ? static_cast<SectionInversionId>(sec.comping_inversion_override)
                                    : state->tracks[t].default_section_inversion;
                            state->tracks[t].section_inversion = target;
                        }

                        // Chord-follow override (per-track wins over section-level; -1 = restore default)
                        for (int t = 0; t < kNumPulsarTracks; t++) {
                            TrackRole r = state->tracks[t].role;
                            if (r != TrackRole::MELODIC && r != TrackRole::CHORDAL) continue;
                            int per_track = sec.track_chord_follow_override[t];
                            ChordFollowMode target = (per_track >= 0)
                                ? static_cast<ChordFollowMode>(per_track)
                                : (sec.chord_follow_override >= 0)
                                    ? static_cast<ChordFollowMode>(sec.chord_follow_override)
                                    : state->tracks[t].default_chord_follow;
                            state->tracks[t].chord_follow = target;
                        }

                        // Arp-mode override (per-track only — no section-level equivalent; -1 = restore default)
                        for (int t = 0; t < kNumPulsarTracks; t++) {
                            if (state->tracks[t].role != TrackRole::CHORDAL) continue;
                            int per_track = sec.track_arp_mode_override[t];
                            ArpModeId target = (per_track >= 0)
                                ? static_cast<ArpModeId>(per_track)
                                : state->tracks[t].default_arp_mode;
                            state->tracks[t].arp_mode = target;
                        }
                    }

                    // Advance solo within current section
                    if (state->band_solo_state.active) {
                        int cur_sec = state->section_state.current_section;
                        const SectionParam& sec_adv = state->arrangement.sections[cur_sec];
                        advance_band_solo(state->band_solo_state, state->band_solo_config,
                                          sec_adv, state->tracks, state->mutation_seed);
                        // Mutate live lick per bar during LickBuilder
                        if (sec_adv.solo_mode == SoloModeId::LICK_BUILDER &&
                            state->live_lick_active && state->band_solo_state.lead_member >= 0) {
                            int lead = state->band_solo_state.lead_member;
                            float creativity = state->band_solo_config.members[lead].creativity;
                            // MUT-4: pass the section-entry snapshot so degree drift
                            // is clamped (octave-jump idiom can't run away).
                            mutate_live_lick(
                                state->live_lick_degrees, state->live_lick_durations,
                                state->live_lick_velocities, state->live_lick_length,
                                creativity * sec_adv.solo_mutation_rate,
                                state->mutation_seed,
                                state->live_lick_base_degrees
                            );

                            // SOLO-1: render the mutated live lick into the leading
                            // member's MELODIC tracks so the LickBuilder is audible.
                            // Pack the live SoA buffers into a temp PulsarLickStep[]
                            // then render through the shared lick->track helper so the
                            // solo honors the track's CALL_RESPONSE phrasing (#3) and
                            // shares one render recipe with the déjà-vu reset (#5).
                            PulsarLickStep live[kMaxLickSteps];
                            int nlive = state->live_lick_length;
                            if (nlive > kMaxLickSteps) nlive = kMaxLickSteps;
                            for (int i = 0; i < nlive; i++) {
                                live[i].scale_degree = state->live_lick_degrees[i];
                                live[i].duration     = state->live_lick_durations[i];
                                live[i].velocity     = state->live_lick_velocities[i];
                                live[i].glide_rate   = -1.0f;  // use track default
                            }
                            // Reuse the already-clamped live_root/live_scale (#4) so the
                            // solo can't land in a different key than the rest of the bar
                            // via a second relaxed root/scale read.
                            uint8_t rroot = static_cast<uint8_t>(live_root);
                            const PulsarScale& rscale = kPulsarScales[live_scale];
                            uint8_t nr_low = static_cast<uint8_t>(
                                engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed));
                            uint8_t nr_high = static_cast<uint8_t>(
                                engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed));
                            const BandMemberParam& lm = state->band_solo_config.members[lead];
                            for (int ti = 0; ti < lm.track_count; ti++) {
                                int rt = lm.tracks[ti];
                                if (rt < 0 || rt >= kNumPulsarTracks) continue;
                                PulsarTrackState& rtrk = state->tracks[rt];
                                // Only MELODIC leads — CHORDAL voicing/PERCUSSIVE are
                                // not driven by a single-line lick. (Scoped to MELODIC
                                // per the plan's note.)
                                if (rtrk.role != TrackRole::MELODIC) continue;
                                // Live buffer is already mutated → render with mutation 0
                                // so a CALL_RESPONSE answer tracks the evolving call
                                // deterministically (no double-scramble).
                                render_lick_into_track(rtrk, rt, live, nlive, 0.0f,
                                                       rroot, rscale, state->mutation_seed,
                                                       rtrk.bar_strategy, rtrk.step_count,
                                                       state->lick_octave,
                                                       nr_low, nr_high,
                                                       state->lick_loop_length);
                            }
                        }
                    }

                    // Update arrangement viz read-back. Pre-roll model: section
                    // total is just bars_remaining — the ramp zone (if any) is
                    // inside that count, not appended after.
                    state->arr_viz_section_index.store(state->section_state.current_section, std::memory_order_relaxed);
                    if (section_changed) {
                        int content_bars = state->section_state.bars_remaining;
                        state->arr_viz_bars_total.store(content_bars, std::memory_order_relaxed);
                        state->arr_viz_bars_elapsed.store(0, std::memory_order_relaxed);
                    } else {
                        state->arr_viz_bars_elapsed.store(
                            state->arr_viz_bars_elapsed.load(std::memory_order_relaxed) + 1,
                            std::memory_order_relaxed);
                    }
                    state->arr_viz_solo_active.store(
                        state->band_solo_state.active, std::memory_order_relaxed);
                    state->arr_viz_solo_track.store(
                        state->band_solo_state.active
                            ? state->band_solo_state.lead_member : -1,
                        std::memory_order_relaxed);
                    {
                        int cur_sec = state->section_state.current_section;
                        const SectionParam& sec_viz = state->arrangement.sections[cur_sec];
                        state->arr_viz_solo_mode.store(static_cast<int>(sec_viz.solo_mode), std::memory_order_relaxed);
                    }
                }

                // Voice crossfade: select EDM or space engine per track based on energy
                // Soloists lock to one engine (prefer EDM) to avoid timbre flipping mid-solo
                for (int vt = 0; vt < kNumPulsarTracks; vt++) {
                    int edm = engine->pulsar_track_engine_edm[vt].load(std::memory_order_relaxed);
                    int spa = engine->pulsar_track_engine_space[vt].load(std::memory_order_relaxed);
                    if (edm == spa) continue;

                    bool soloing = state->tracks[vt].is_soloist;
                    if (soloing) {
                        // Soloists: lock to EDM unless energy is clearly low
                        state->tracks[vt].engine_index = (energy < 0.25f) ? spa : edm;
                    } else if (energy > 0.6f) {
                        state->tracks[vt].engine_index = edm;
                    } else if (energy < 0.4f) {
                        state->tracks[vt].engine_index = spa;
                    } else {
                        float p_edm = (energy - 0.4f) / 0.2f;
                        float roll = rand01(state->mutation_seed);
                        state->tracks[vt].engine_index = (roll < p_edm) ? edm : spa;
                    }
                }

                // Update drunk timing targets
                float max_drunk = (1.0f - energy) * complexity * 0.3f;

                // Timing tension: scale drunk offset magnitude by tension intensity
                float timing_scale = 1.0f;
                float timing_tension = state->tension.timing;
                if (timing_tension > 0.001f) {
                    timing_scale = (1.0f - timing_tension) + timing_tension * state->tension_intensity;
                }

                for (int dt = 0; dt < kNumPulsarTracks; dt++) {
                    int sc = std::min(state->tracks[dt].step_count, kMaxPulsarSteps);
                    for (int ds = 0; ds < sc; ds++) {
                        float target = (rand01(state->mutation_seed) - 0.5f) * 2.0f * max_drunk
                                       * static_cast<float>(samples_per_step);
                        target *= timing_scale;
                        state->drunk_targets[dt][ds] = target;
                        state->drunk_offsets[dt][ds] += 0.5f * (target - state->drunk_offsets[dt][ds]);
                    }
                }

                // Déjà vu reset: regenerate patterns from original seed periodically
                state->loops_since_reset++;
                int reset_interval = std::max(8, static_cast<int>(32.0f * (1.0f - complexity)));
                if (state->loops_since_reset >= reset_interval) {
                    state->loops_since_reset = 0;
                    // Re-read genre profile for regeneration
                    PulsarGenreProfile rg;
                    for (int gi = 0; gi < 8; gi++)
                        rg.base_density[gi] = engine->pulsar_genre_density[gi].load(std::memory_order_relaxed);
                    rg.swing_amount = engine->pulsar_genre_swing.load(std::memory_order_relaxed);
                    rg.ghost_probability = engine->pulsar_genre_ghost_prob.load(std::memory_order_relaxed);
                    rg.note_range_low = static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed));
                    rg.note_range_high = static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed));
                    rg.rhythm_density = engine->pulsar_genre_rhythm_density.load(std::memory_order_relaxed);

                    int rr = engine->pulsar_root_note.load(std::memory_order_relaxed);
                    int rsi = engine->pulsar_scale_index.load(std::memory_order_relaxed);
                    if (rsi < 0) rsi = 0;
                    if (rsi >= static_cast<int>(sizeof(kPulsarScales) / sizeof(kPulsarScales[0])))
                        rsi = static_cast<int>(sizeof(kPulsarScales) / sizeof(kPulsarScales[0])) - 1;
                    const PulsarScale& rscale = kPulsarScales[rsi];

                    uint32_t reset_seed = state->seed_counter * 2654435761u;
                    int step_count_cfg = engine->pulsar_step_count.load(std::memory_order_relaxed);
                    if (step_count_cfg <= 0) step_count_cfg = 16;
                    if (step_count_cfg > kMaxPulsarSteps) step_count_cfg = kMaxPulsarSteps;
                    int bar1_reset = (step_count_cfg > 16) ? 16 : step_count_cfg;

                    // Effective mutation: spurt amplifies 3x, capped at 1.0
                    float eff_mutation = state->in_spurt
                        ? std::min(1.0f, state->lick_mutation * 3.0f)
                        : state->lick_mutation;

                    for (int rt = 0; rt < kNumPulsarTracks; rt++) {
                        PulsarTrackState& rts = state->tracks[rt];
                        TrackRole r_role = static_cast<TrackRole>(engine->pulsar_track_role[rt].load(std::memory_order_relaxed));
                        bool perc = (r_role == TrackRole::PERCUSSIVE);
                        BarStrategy bs = rts.bar_strategy;

                        LickMode r_lick_mode = static_cast<LickMode>(
                            engine->pulsar_track_lick_mode[rt].load(std::memory_order_relaxed));
                        bool r_use_lick = (r_lick_mode != LickMode::NONE);
                        if (state->lick_length > 0 && r_use_lick && !perc) {
                            // Shared lick->track render (#5): honors CALL_RESPONSE,
                            // else loops the lick. Genre note range (rg) matches the
                            // load path — the old hardcoded 36/72 here shifted the
                            // octave on every déjà-vu reset (#4 note-range fix).
                            render_lick_into_track(rts, rt, state->lick, state->lick_length,
                                                   eff_mutation, static_cast<uint8_t>(rr), rscale,
                                                   reset_seed, bs, step_count_cfg,
                                                   state->lick_octave,
                                                   rg.note_range_low, rg.note_range_high,
                                                   state->lick_loop_length);
                        } else {
                            float hold_prob = engine->pulsar_track_hold_probability[rt].load(std::memory_order_relaxed);
                            int hold_min = engine->pulsar_track_hold_length_min[rt].load(std::memory_order_relaxed);
                            int hold_max = engine->pulsar_track_hold_length_max[rt].load(std::memory_order_relaxed);
                            if (hold_min < 1) hold_min = 2;
                            if (hold_max < hold_min) hold_max = hold_min;
                            float density_ovr = engine->pulsar_track_density_override[rt].load(std::memory_order_relaxed);
                            int nr_low = engine->pulsar_track_note_range_low[rt].load(std::memory_order_relaxed);
                            int nr_high = engine->pulsar_track_note_range_high[rt].load(std::memory_order_relaxed);
                            int eng_nm = (rts.engine_index >= 0 && rts.engine_index < 24)
                                ? kEngineModRanges[rts.engine_index].note_min : 0;
                            generate_track_pattern(rts, rt, perc, rg,
                                                   static_cast<uint8_t>(rr), rscale, bar1_reset, reset_seed,
                                                   0, hold_prob, hold_min, hold_max,
                                                   density_ovr, nr_low, nr_high, eng_nm);
                            if (step_count_cfg > 16) {
                                apply_bar_strategy(rts, rt, bs, perc, rg,
                                                   static_cast<uint8_t>(rr), rscale,
                                                   energy, complexity,
                                                   state->lick, state->lick_length, eff_mutation,
                                                   reset_seed ^ (rt * 13331u),
                                                   state->lick_octave,
                                                   state->lick_loop_length);
                            }
                        }
                    }
                }

            }

            if (ts.playhead >= kMaxPulsarSteps) ts.playhead = 0;
            const PulsarStep& step = ts.steps[ts.playhead];

            if (step.gate) {
                // Hold continuation: previous step had hold=true, extend gate without retrigger
                if (ts.in_hold) {
                    float drunk = state->drunk_offsets[t][ts.playhead];
                    float base_gate = static_cast<float>(step.duration * samples_per_step);
                    ts.gate_timer = std::max(base_gate + drunk, base_gate * 0.25f);
                    ts.voice_active = true;
                    // Continue or end hold chain based on this step's hold flag
                    ts.in_hold = step.hold;
                } else {
                    // Normal trigger path

                    // FX track (7): only fire at energy extremes
                    bool fx_skip = false;
                    if (t == 7) {
                        float fx_prob = compute_fx_probability(energy, complexity);
                        if (fx_prob <= 0.001f) {
                            ts.prev_step_gated = false;
                            ts.in_hold = false;
                            fx_skip = true;
                        }
                    }

                    if (!fx_skip) {
                        // Solo density gating
                        if (ts.solo_density_mod < 0.0f) {
                            float density_gate = 1.0f + ts.solo_density_mod;
                            if (rand01(state->mutation_seed) > density_gate) {
                                ts.prev_step_gated = false;
                                ts.in_hold = false;
                                continue;
                            }
                        }

                        // Probability gating: energy controls base fire probability.
                        // TEXTURE/FX tracks (5-7) at low energy bypass gating so hold
                        // chains reliably start — the pattern generator already controls
                        // density, and the energy volume curve handles presence.
                        float base_prob = energy * 0.6f + 0.4f;  // 40% at energy=0, 100% at energy=1
                        float vel_boost = step.velocity * (1.0f - base_prob) * 0.5f;
                        float fire_prob = base_prob + vel_boost;

                        // SOLO-3: a positive solo density modifier raises the fire
                        // probability so a soloist fires more of its gated steps
                        // (mirrors the negative branch above, which skips them).
                        // solo_fill_mod (>0) adds extra lift so busy soloists fill
                        // the gaps; both are bounded so fire_prob stays a probability.
                        if (ts.solo_density_mod > 0.0f) {
                            fire_prob += ts.solo_density_mod;
                            if (ts.solo_fill_mod > 0.0f) fire_prob += ts.solo_fill_mod * 0.5f;
                            if (fire_prob > 1.0f) fire_prob = 1.0f;
                        }

                        uint32_t prob_hash = step_hash(ts.playhead, t, state->loop_count);
                        float prob_roll = static_cast<float>(prob_hash & 0xFFFF) / 65535.0f;
                        bool fires = prob_roll < fire_prob || energy >= 0.99f;

                        // TEXTURE/FX at low energy: always fire so hold chains work
                        if (t >= 5 && energy < 0.4f) fires = true;

                        if (fires) {
                            // Apply velocity variation from complexity
                            float vel = step.velocity;
                            if (variation_amt > 0.001f) {
                                uint32_t vh = step_hash(ts.playhead, t, state->loop_count);
                                float var_offset = (static_cast<float>(vh & 0xFFFF) / 65535.0f - 0.5f)
                                                  * 2.0f * variation_amt * 0.2f;
                                vel = clamp01(vel + var_offset);
                            }

                            // Apply solo volume modifier
                            if (ts.solo_volume_mod != 0.0f) {
                                vel = clamp01(vel + ts.solo_volume_mod);
                            }

                            // Volume tension: scale velocity based on phrase intensity
                            float vol_tension = state->tension.volume;
                            if (vol_tension > 0.001f) {
                                float vel_scale = 1.0f - vol_tension * 0.3f * (1.0f - state->tension_intensity);
                                vel = clamp01(vel * vel_scale);
                            }

                            // Force retrigger: reset gate so Tides sees a rising edge
                            ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
                            ts.voice_active = true;
                            float drunk = state->drunk_offsets[t][ts.playhead];
                            float base_gate = static_cast<float>(step.duration * samples_per_step);
                            ts.gate_timer = std::max(base_gate + drunk, base_gate * 0.25f);

                            // ── Tonal tension: modify MIDI note before pitch glide ──
                            int midi_note = step.note;

                            // Chord progression transposition (melodic tracks, mode-dependent)
                            if (ts.role != TrackRole::PERCUSSIVE && ts.chord_follow != ChordFollowMode::FIXED) {
                                int chord_deg = state->chord_state.progression[state->chord_state.chord_index];
                                int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
                                if (si < 0) si = 0;
                                if (si >= kNumPulsarScales) si = kNumPulsarScales - 1;
                                const PulsarScale& sc = kPulsarScales[si];

                                if (ts.chord_follow == ChordFollowMode::ROOT_ONLY) {
                                    // Play the chord root, pinned to the LOWEST valid octave in the
                                    // track's note range. Bass stays on the floor; V-chord offsets
                                    // push up from there instead of wandering relative to pattern octave.
                                    int root = engine->pulsar_root_note.load(std::memory_order_relaxed);
                                    int chord_semis = chord_degree_to_semitones(chord_deg, sc);
                                    int nr_low_root = engine->pulsar_track_note_range_low[t].load(std::memory_order_relaxed);
                                    // Find lowest octave where (octave*12 + root + chord_semis) >= nr_low_root
                                    int base = root + chord_semis;
                                    int octave = 0;
                                    while (octave * 12 + base < nr_low_root) octave++;
                                    midi_note = octave * 12 + base;
                                } else if (chord_deg != 0) {
                                    // FOLLOW: transpose by degree-to-degree offset
                                    int semi_offset = chord_degree_to_semitones(chord_deg, sc)
                                                    - chord_degree_to_semitones(0, sc);
                                    midi_note += semi_offset;
                                }

                                // Octave pin: bias toward the lowest valid octave of the track's
                                // note range. For bass (narrow range), this keeps the bass in the
                                // bass register on every chord. For wider-range tracks, this keeps
                                // chord transposition from drifting upward over time — pattern shape
                                // preserved within the floor octave.
                                int nr_low = engine->pulsar_track_note_range_low[t].load(std::memory_order_relaxed);
                                int nr_high = engine->pulsar_track_note_range_high[t].load(std::memory_order_relaxed);
                                if (nr_low > 0 && nr_high > nr_low) {
                                    // Drop down to within one octave of the floor
                                    while (midi_note >= nr_low + 12) midi_note -= 12;
                                    // Bump up if below floor
                                    while (midi_note < nr_low) midi_note += 12;
                                    // Safety cap (shouldn't trigger given above logic)
                                    if (midi_note > nr_high) midi_note = nr_high;
                                }
                            }

                            // Octave shift at extreme intensities
                            if (state->tension.octave_shift) {
                                float intensity = state->tension_intensity;
                                uint32_t rng = step_hash(ts.playhead, t, state->loop_count + 997);
                                float r = (rng & 0xFFFF) / 65535.0f;
                                if (intensity > 0.8f && r < 0.3f) {
                                    midi_note += 12;
                                } else if (intensity < 0.2f && r < 0.2f) {
                                    midi_note -= 12;
                                }
                            }

                            // Key shift at climax
                            if (state->tension.key_shift != 0 && state->tension_intensity > 0.7f) {
                                midi_note += state->tension.key_shift;
                            }

                            // Chromatic passing tones
                            float chrom_prob = state->tension.chromatic_passing * state->tension_intensity;
                            if (chrom_prob > 0.001f) {
                                uint32_t rng = step_hash(ts.playhead, t + 7, state->loop_count);
                                float r = (rng & 0xFFFF) / 65535.0f;
                                if (r < chrom_prob) {
                                    midi_note += (rng & 0x10000) ? 1 : -1;
                                }
                            }

                            // Clamp to valid MIDI range
                            if (midi_note < 0) midi_note = 0;
                            if (midi_note > 127) midi_note = 127;

                            float new_note = static_cast<float>(midi_note);
                            ts.target_pitch = new_note;

                            // ── Arpeggiator initialization (CHORDAL tracks) ──
                            if (ts.role == TrackRole::CHORDAL && ts.arp_mode != ArpModeId::NEVER) {
                                bool use_arp = (ts.arp_mode == ArpModeId::ALWAYS)
                                               || !engine_has_native_chord(ts.engine_index);
                                if (use_arp) {
                                    int cd = state->chord_state.progression[state->chord_state.chord_index];
                                    int arp_si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
                                    if (arp_si < 0) arp_si = 0;
                                    if (arp_si >= kNumPulsarScales) arp_si = kNumPulsarScales - 1;
                                    const PulsarScale& arp_sc = kPulsarScales[arp_si];

                                    uint32_t seed = static_cast<uint32_t>(
                                        state->loop_count * 0x9E3779B9u) ^ static_cast<uint32_t>(t);
                                    // 2 notes (root + 5th) by default — less blippy than triad arps.
                                    // With arpDirection = DOWN, plays 5th then root — like a grace
                                    // note landing on the root. Chord color comes from humanization.
                                    ts.arp_note_count = compute_chord_tones(
                                        midi_note, cd, arp_sc,
                                        2 /* root + 5th */, ts.arp_direction, ts.section_inversion, seed, ts.arp_notes);

                                    // First note plays immediately via target_pitch already set above.
                                    // Override with computed arp_notes[0] for consistent ordering.
                                    ts.target_pitch = static_cast<float>(ts.arp_notes[0]);
                                    ts.current_pitch = ts.target_pitch;  // no glide on arp retriggers
                                    ts.arp_index = 1;  // next retrigger = arp_notes[1]

                                    // Schedule next retrigger (countdown in samples)
                                    int spn = arp_samples_per_note(ts.arp_speed,
                                                                    static_cast<int>(samples_per_step));
                                    ts.arp_next_sample = static_cast<int64_t>(spn);
                                } else {
                                    ts.arp_note_count = 0;  // arp inactive (CHD engine, AUTO mode)
                                }
                            } else {
                                ts.arp_note_count = 0;
                            }

                            // Arp suppresses glide — each note fires at exact pitch.
                            // Glide priority (most-specific wins):
                            //   1) Per-lick-step glide (step.glide_rate >= 0)
                            //   2) Per-chord glide on chord-transition edge (one-shot)
                            //   3) Per-track default (per-engine: EDM vs Space slot)
                            const bool use_edm = (ts.engine_index ==
                                engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed));
                            float track_glide = PULSAR_PICK(glide_rate);
                            float step_glide = step.glide_rate;
                            float chord_glide = -1.0f;
                            const PulsarChordState& cs = state->chord_state;
                            int new_ci = cs.chord_index;
                            if (new_ci != ts.last_chord_index
                                && new_ci >= 0 && new_ci < cs.progression_length) {
                                // last_chord_index == -1 means "no previous chord" (initial
                                // load or section transition). Skip the glide on that first
                                // edge — there's nothing to slide *from*. Only fire when
                                // moving between two known chord positions.
                                if (ts.last_chord_index >= 0) {
                                    float g = cs.progression_glides[new_ci];
                                    if (g > 0.0f) chord_glide = g;
                                }
                                ts.last_chord_index = new_ci;
                            }
                            float glide_param = (ts.arp_note_count > 0) ? 0.0f
                                : (step_glide >= 0.0f ? step_glide
                                    : (chord_glide >= 0.0f ? chord_glide : track_glide));
                            if (glide_param > 0.001f && ts.prev_step_gated) {
                                // Map 0-1 to glide rate: 0.001 (fast ~20ms) to 0.00002 (very slow ~2s)
                                ts.glide_rate = 0.001f * std::pow(0.02f, glide_param);
                            } else {
                                ts.glide_rate = 0.0f;
                                ts.current_pitch = ts.target_pitch;
                            }
                            ts.prev_step_gated = true;
                            // Start or continue a hold chain if this step has hold=true
                            ts.in_hold = step.hold;
                        } else {
                            ts.prev_step_gated = false;
                            ts.in_hold = false;
                        }
                    }
                }
            } else {
                // Rest step
                if (ts.in_hold) {
                    // Previous step had hold=true: bridge gate through this rest
                    float base_gate = static_cast<float>(samples_per_step);
                    ts.gate_timer = base_gate;
                    ts.voice_active = true;
                    ts.in_hold = false;  // Hold ends after bridging a rest
                }
                // else: normal rest — gate_timer continues decaying (no action needed)
            }
        }

        // Decrement gate timer by num_frames (block-rate approximation).
        if (ts.gate_timer > 0.0f) {
            ts.gate_timer -= static_cast<float>(num_frames);
            if (ts.gate_timer <= 0.0f) {
                ts.gate_timer = 0.0f;
                ts.voice_active = false;
            }
        }

        // ── Arpeggiator per-block countdown ──
        // Fires next chord tone when countdown expires. Uses block-rate decrement
        // (same convention as gate_timer). Option A sustain: hold last note.
        if (ts.arp_note_count > 0 && ts.arp_index < ts.arp_note_count) {
            ts.arp_next_sample -= static_cast<int64_t>(num_frames);
            if (ts.arp_next_sample <= 0) {
                // Retrigger voice with next arp note
                ts.target_pitch = static_cast<float>(ts.arp_notes[ts.arp_index]);
                ts.current_pitch = ts.target_pitch;  // no glide between arp notes
                ts.arp_index++;

                // Force rising edge so Tides/AD envelope restarts
                ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
                ts.voice_active = true;
                // Reset gate timer so the voice fires a full envelope for this note.
                // Use current playhead step duration; fall back to one arp interval.
                float arp_note_duration = 1.0f;
                if (ts.playhead >= 0 && ts.playhead < ts.step_count) {
                    arp_note_duration = ts.steps[ts.playhead].duration;
                }
                int spn_for_gate = arp_samples_per_note(ts.arp_speed,
                                                        static_cast<int>(samples_per_step));
                float base_gate = static_cast<float>(arp_note_duration) * static_cast<float>(spn_for_gate);
                ts.gate_timer = std::max(base_gate, static_cast<float>(720));

                if (ts.arp_index < ts.arp_note_count) {
                    // Schedule next retrigger
                    int spn = arp_samples_per_note(ts.arp_speed,
                                                    static_cast<int>(samples_per_step));
                    ts.arp_next_sample = static_cast<int64_t>(spn);
                } else {
                    // Option A: sequence complete — hold last note, no more retriggers
                    ts.arp_note_count = 0;
                }
            }
        }

        // Apply pitch glide (exponential approach toward target_pitch)
        if (ts.glide_rate != 0.0f) {
            float alpha = ts.glide_rate * static_cast<float>(num_frames);
            alpha = alpha > 1.0f ? 1.0f : alpha;
            ts.current_pitch += (ts.target_pitch - ts.current_pitch) * alpha;
            if (std::abs(ts.target_pitch - ts.current_pitch) < 0.01f) {
                ts.current_pitch = ts.target_pitch;
                ts.glide_rate = 0.0f;
            }
        }

        // Determine note and accent from current step
        float note_for_render = ts.current_pitch;
        float accent_for_render = 0.8f;
        if (ts.playhead >= 0 && ts.playhead < ts.step_count) {
            const PulsarStep& step = ts.steps[ts.playhead];
            if (step.gate) {
                accent_for_render = step.velocity;
            }
        }

        // Accent boost: high-velocity steps push harmonics/timbre
        if (accent_for_render > 0.7f) {
            float accent_boost = (accent_for_render - 0.7f) * 0.3f;
            if (!ts.pin_harmonics) mod_harmonics = clamp01(mod_harmonics + accent_boost);
            if (!ts.pin_timbre)    mod_timbre    = clamp01(mod_timbre + accent_boost);
        }

        // ── Dual LFO modulation for TEXTURE/FX tracks (5-7) and DRONE profile ──
        if ((t >= 5 || ts.envelope_profile == ENV_PROFILE_DRONE) && ts.mod_lfo_initialized) {
            // Mod LFO is per-engine: pick EDM vs Space slot.
            const bool use_edm = (ts.engine_index ==
                engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed));
            float lfo_rate     = PULSAR_PICK(mod_lfo_rate);
            float lfo_depth    = PULSAR_PICK(mod_lfo_depth);
            float lfo_shape    = PULSAR_PICK(mod_lfo_shape);
            float lfo_coupling = PULSAR_PICK(mod_lfo_coupling);

            // Energy-based source mixing:
            // Low energy (<0.4): PolyLfo (smooth, evolving)
            // High energy (>0.6): PolySlopeGenerator (rhythmic, beat-synced)
            // Crossfade at boundaries
            float poly_lfo_mix = 1.0f;
            float slope_mix = 0.0f;
            if (energy > 0.6f) {
                poly_lfo_mix = 0.0f;
                slope_mix = 1.0f;
            } else if (energy > 0.4f) {
                float t_fade = (energy - 0.4f) / 0.2f;  // 0..1 across crossfade
                poly_lfo_mix = 1.0f - t_fade;
                slope_mix = t_fade;
            }

            float mod_lfo_timbre = 0.0f;
            float mod_lfo_morph = 0.0f;
            float mod_lfo_harmonics = 0.0f;
            float mod_lfo_pitch = 0.0f;

            // ── PolyLfo source (smooth, low-energy) ──
            if (poly_lfo_mix > 0.001f) {
                auto to_u16 = [](float v) -> uint16_t {
                    return static_cast<uint16_t>(std::max(0.0f, std::min(1.0f, v)) * 65535.0f);
                };

                // Rate: slower at lower energy. Cubic mapping to PolyLfo frequency.
                float effective_rate = lfo_rate * (0.3f + 0.7f * energy);
                float freq_raw = effective_rate * effective_rate * effective_rate * 55000.0f;
                int32_t frequency = static_cast<int32_t>(freq_raw);
                if (frequency < 0) frequency = 0;

                // Shape from LFO param, spread from mood macro
                // DRONE profile: wider spread and shape_spread for 4 distinct modulation lines
                bool is_drone = (ts.envelope_profile == ENV_PROFILE_DRONE);
                float eff_shape_spread = is_drone
                    ? std::max(0.6f, mood * 0.5f + 0.4f)   // 0.6-0.9 (wide shape variety)
                    : mood * 0.5f;
                float eff_spread = is_drone
                    ? std::max(0.5f, space * 0.5f + 0.3f)  // 0.5-0.8 (phase offset)
                    : space * 0.7f + 0.15f;
                ts.mod_poly_lfo.set_shape(to_u16(lfo_shape));
                ts.mod_poly_lfo.set_shape_spread(to_u16(eff_shape_spread));
                ts.mod_poly_lfo.set_spread(to_u16(eff_spread));
                ts.mod_poly_lfo.set_coupling(to_u16(lfo_coupling));

                // Render per-sample, accumulate for block average
                float ch_accum[4] = {0.0f, 0.0f, 0.0f, 0.0f};
                for (int i = 0; i < num_frames; i++) {
                    ts.mod_poly_lfo.Render(frequency);
                    for (int ch = 0; ch < 4; ch++) {
                        float val = (static_cast<float>(ts.mod_poly_lfo.level(ch)) / 127.5f) - 1.0f;
                        ch_accum[ch] += val;
                    }
                }

                float inv_frames = 1.0f / static_cast<float>(num_frames);
                float poly_timbre    = ch_accum[0] * inv_frames;
                float poly_morph     = ch_accum[1] * inv_frames;
                float poly_harmonics = ch_accum[2] * inv_frames;
                float poly_pitch     = ch_accum[3] * inv_frames;

                mod_lfo_timbre    += poly_lfo_mix * poly_timbre;
                mod_lfo_morph     += poly_lfo_mix * poly_morph;
                mod_lfo_harmonics += poly_lfo_mix * poly_harmonics;
                mod_lfo_pitch     += poly_lfo_mix * poly_pitch;
            }

            // ── PolySlopeGenerator source (rhythmic, high-energy) ──
            if (slope_mix > 0.001f) {
                // Beat-synced rate from BPM and complexity
                float beats_per_sec = bpm / 60.0f;
                float slope_freq = beats_per_sec * (0.5f + complexity * 2.0f) / sample_rate;

                stmlib::GateFlags slope_flags[kMaxFrames];
                for (int i = 0; i < num_frames; i++) {
                    slope_flags[i] = stmlib::GATE_FLAG_HIGH;  // free-running looping
                }
                float slope_ramp[kMaxFrames];
                std::memset(slope_ramp, 0, num_frames * sizeof(float));

                tides::PolySlopeGenerator::OutputSample slope_out[kMaxFrames];
                ts.mod_slope.Render(
                    tides::RAMP_MODE_LOOPING,
                    tides::OUTPUT_MODE_SLOPE_PHASE,
                    tides::RANGE_CONTROL,
                    slope_freq,
                    0.5f,          // pw
                    lfo_shape,     // shape
                    0.5f,          // smoothness
                    space * 0.5f,  // shift (phase offset between channels)
                    slope_flags,
                    slope_ramp,
                    slope_out,
                    num_frames
                );

                // Average across block, normalize from +-8V to +-1
                float s_accum[4] = {0.0f, 0.0f, 0.0f, 0.0f};
                for (int i = 0; i < num_frames; i++) {
                    for (int ch = 0; ch < 4; ch++) {
                        s_accum[ch] += slope_out[i].channel[ch] * kTidesNorm;
                    }
                }

                float inv_frames = 1.0f / static_cast<float>(num_frames);
                mod_lfo_timbre    += slope_mix * s_accum[0] * inv_frames;
                mod_lfo_morph     += slope_mix * s_accum[1] * inv_frames;
                mod_lfo_harmonics += slope_mix * s_accum[2] * inv_frames;
                mod_lfo_pitch     += slope_mix * s_accum[3] * inv_frames;
            }

            // Scale by depth — DRONE tracks bypass energy curve (drone should evolve at all energy levels)
            float depth_scale = (ts.envelope_profile == ENV_PROFILE_DRONE)
                ? lfo_depth
                : lfo_depth * texture_energy_curve(energy);
            ts.mod_lfo_output[0] = mod_lfo_timbre * depth_scale;
            ts.mod_lfo_output[1] = mod_lfo_morph * depth_scale;
            ts.mod_lfo_output[2] = mod_lfo_harmonics * depth_scale;
            ts.mod_lfo_output[3] = mod_lfo_pitch * depth_scale;

            // Apply LFO modulation with per-engine range clamping
            int ei = ts.engine_index;
            if (ei < 0) ei = 0;
            if (ei > 23) ei = 23;
            const EngineModRange& mr = kEngineModRanges[ei];
            if (!ts.pin_harmonics) {
                mod_harmonics = apply_mod(mod_harmonics, ts.mod_lfo_output[2], 1.0f,
                                          mr.harmonics_min, mr.harmonics_max, mr.harmonics_safe);
            } else if (ts.harmonics_modulation > 0.001f) {
                // Pinned, but the vibe opts in to a bounded LFO swing around the
                // pinned base. Only computed here because mod_lfo_output[2] is
                // populated inside this LFO block.
                mod_harmonics = ts.harmonics + ts.mod_lfo_output[2] * ts.harmonics_modulation;
            }
            if (!ts.pin_timbre) {
                mod_timbre = apply_mod(mod_timbre, ts.mod_lfo_output[0], 1.0f,
                                       mr.timbre_min, mr.timbre_max, true);
            }
            if (!ts.pin_morph) {
                mod_morph = apply_mod(mod_morph, ts.mod_lfo_output[1], 1.0f,
                                      mr.morph_min, mr.morph_max, mr.morph_safe);
            }
        }

        // ── User-knob-driven DX patch walk ──
        // Independent of the LFO block above (which is gated by track index /
        // envelope profile). This lets a vibe author bring back the pre-pin
        // feel where moving mood (or another macro) shifts DX patches without
        // requiring the track to be a DRONE/texture slot. DX-family only —
        // the quantizer is what makes small knob tweaks land on discrete
        // "voices" instead of smoothly interpolating.
        if (ts.pin_harmonics
            && ts.engine_index >= 2 && ts.engine_index <= 4
            && ts.harmonics_macro_range > 0.001f) {
            // 0=NONE, 1=ENERGY, 2=COMPLEXITY, 3=SPACE, 4=MOOD (matches MacroSource.kt ordinals)
            float macro_val = 0.5f;
            switch (ts.harmonics_macro_source) {
                case 1: macro_val = energy;     break;
                case 2: macro_val = complexity; break;
                case 3: macro_val = space;      break;
                case 4: macro_val = mood;       break;
                default: break;
            }
            // Centered on 0.5 → no walk at knob midpoint, ±range at extremes.
            mod_harmonics += (macro_val - 0.5f) * 2.0f * ts.harmonics_macro_range;
        }

        // ── CHD engine inversion: override morph to select voicing registration ──
        // CHD (engine 14) uses morph to select chord voicing type.
        // Map SectionInversion → morph: 0.0=root, 0.3=1st, 0.6=2nd, 0.9=open.
        if (ts.role == TrackRole::CHORDAL
            && engine_has_native_chord(ts.engine_index)
            && ts.section_inversion != SectionInversionId::FOLLOW_STYLE)
        {
            float morph_val = mod_morph;  // default: preserve existing mod
            switch (ts.section_inversion) {
                case SectionInversionId::ROOT_POSITION:    morph_val = 0.0f;  break;
                case SectionInversionId::FIRST_INVERSION:  morph_val = 0.3f;  break;
                case SectionInversionId::SECOND_INVERSION: morph_val = 0.6f;  break;
                case SectionInversionId::OPEN_VOICING:     morph_val = 0.9f;  break;
                default: break;
            }
            mod_morph = morph_val;
        }

        // Truly self-enveloped engines: BD(21), SD(22), HH(23), DX(2-4).
        // These have internal VCAs — keep gate high during holds.
        // STR(19) and MOD(20) are NOT self-sustaining: they fire a brief excitation
        // on trigger then decay. They need the external Tides envelope.
        bool is_self_env = (ts.engine_index >= 21 && ts.engine_index <= 23)
                        || (ts.engine_index >= 2 && ts.engine_index <= 4);
        int gate_for_render = ts.voice_active ? 1 : 0;
        if (is_self_env && ts.in_hold) gate_for_render = 1;

        // ── Apply per-engine playability floors ──
        // Prevents artifacts (aliasing, crackling) from bad parameter combos.
        if (ts.engine_index >= 0 && ts.engine_index < 24) {
            const EngineModRange& mr = kEngineModRanges[ts.engine_index];
            if (mod_harmonics < mr.harmonics_floor) mod_harmonics = mr.harmonics_floor;
            if (mod_timbre < mr.timbre_floor) mod_timbre = mr.timbre_floor;
            if (mod_morph < mr.morph_floor) mod_morph = mr.morph_floor;
            // Clamp note above engine minimum (shift up an octave if needed)
            if (mr.note_min > 0 && note_for_render < mr.note_min) {
                while (note_for_render < mr.note_min) note_for_render += 12.0f;
            }
        }

        // ── Render voice ──
        // Pick lpg_mode based on which engine slot is currently active.
        // ts.engine_index is set per-block by the energy threshold above —
        // EDM slot above 0.6, SPACE slot below 0.4. The compare-against-edm
        // here means SPACE slot's mode applies whenever engine_index isn't
        // the EDM engine (covers both SPACE-active and the "edm == spa"
        // single-engine case, which still uses lpg_mode since they match).
        const int active_edm_engine =
            engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed);
        const int active_lpg_mode = (ts.engine_index == active_edm_engine)
            ? ts.lpg_mode
            : ts.lpg_mode_space;
        // Braids range (100..199) → MacroOscillator wrapper.
        // Otherwise → OrpheusVoice (Plaits engines + LPG).
        // Braids' MacroOscillator silently clamps unknown indices to its last
        // shape; OrpheusVoice clamps engine_index to engines_.size()-1 = 23
        // (HiHat). Without this branch, Pulsar tracks set to a Braids id
        // would render HiHat instead of the chord/character engine.
#ifdef ORPHEUS_TESTING
        // Debug peek for tests — publish final mod values before render.
        // Compiled in only when BUILD_TESTS=ON; production audio path pays zero cost.
        engine->pulsar_track_mod_harmonics_debug[t].store(clamp01(mod_harmonics), std::memory_order_relaxed);
        engine->pulsar_track_mod_timbre_debug[t].store(clamp01(mod_timbre), std::memory_order_relaxed);
        engine->pulsar_track_mod_morph_debug[t].store(clamp01(mod_morph), std::memory_order_relaxed);
#endif

        if (ts.engine_index >= 100 && ts.engine_index < 200) {
            unit_process_braids(ts.braids_voice, ts.braids_src_phase,
                                ts.engine_index,
                                note_for_render,
                                clamp01(mod_harmonics),
                                clamp01(mod_timbre),
                                clamp01(mod_morph),
                                sample_rate, track_buffer, num_frames);
        } else if (ts.engine_index >= kChaosEngineMin && ts.engine_index <= kChaosEngineMax) {
            // Chaos engines (200..204): per-track ChaosVoiceState driven by the
            // shared chaos kernel. Without this branch the engine_index would
            // fall through to plaits::Voice::Render which clamps unknown ids to
            // its last engine (HiHat).
            chaos::process_chaos_block(
                ts.chaos_state,
                ts.engine_index,
                clamp01(mod_harmonics),
                clamp01(mod_timbre),
                clamp01(mod_morph),
                note_for_render,
                sample_rate,
                track_buffer,
                num_frames);
        } else {
            ts.voice.Render(
                ts.engine_index,
                gate_for_render,
                note_for_render,
                clamp01(mod_harmonics),
                clamp01(mod_timbre),
                clamp01(mod_morph),
                accent_for_render,
                track_buffer,
                num_frames,
                static_cast<LpgMode>(active_lpg_mode),
                ts.lpg_decay,
                ts.lpg_colour
            );
        }

        // ── Apply Tides envelope ──
        // Self-enveloped engines bypass: 19-23 (String, Modal, BD, SD, HH), 2-4 (SixOp)
        // Only BD(21), SD(22), HH(23), DX(2-4) are truly self-enveloped.
        // STR(19) and MOD(20) need external envelope for sustain.
        bool self_enveloped = (ts.engine_index >= 21 && ts.engine_index <= 23)
                           || (ts.engine_index >= 2 && ts.engine_index <= 4);

        if (!self_enveloped) {
            // DRONE profile: slow attack/sustain/release envelope (bypasses broken Tides)
            // Uses the same tides_env_level state variable as the AD path.
            PulsarEnvelopeProfile active_profile = ts.envelope_profile;
            if (t >= 5 && ts.in_hold && energy < 0.4f) {
                active_profile = ENV_PROFILE_DRONE;
            }

            if (active_profile == ENV_PROFILE_DRONE) {
                // Slow swell in (~1-3s attack), full sustain while gate held, slow fade out (~2-5s)
                float attack_time = 1.0f + space * 2.0f;  // 1-3s based on space
                float attack_samples = attack_time * sample_rate;
                float release_time = 2.0f + space * 3.0f;  // 2-5s based on space
                float decay_coeff = 1.0f - (1.0f / (release_time * sample_rate));
                if (decay_coeff < 0.9999f) decay_coeff = 0.9999f;
                if (decay_coeff > 0.999999f) decay_coeff = 0.999999f;

                for (int i = 0; i < num_frames; i++) {
                    if (ts.voice_active && ts.tides_env_level < 1.0f) {
                        ts.tides_env_level += 1.0f / attack_samples;
                        if (ts.tides_env_level > 1.0f) ts.tides_env_level = 1.0f;
                    } else if (!ts.voice_active) {
                        ts.tides_env_level *= decay_coeff;
                        if (ts.tides_env_level < 0.001f) ts.tides_env_level = 0.0f;
                    }
                    track_buffer[i] *= ts.tides_env_level;
                }
            } else {
                int envelope_mode = engine->pulsar_envelope_mode.load(std::memory_order_relaxed);

                // Blend mode (2): AD at high energy (EDM), Tides at low energy (Space)
                if (envelope_mode == 2) {
                    envelope_mode = (energy > 0.5f) ? 0 : 1;
                }

                if (envelope_mode == 1) {
                    // === TIDES ENVELOPE ===
                    float env_shape, env_pw, env_smoothness, env_freq_mult;
                    compute_tides_params(active_profile, energy, complexity, space, mood,
                                         state->mutation_seed, env_shape, env_pw, env_smoothness, env_freq_mult);

                    stmlib::GateFlags env_flags[kMaxFrames];
                    for (int i = 0; i < num_frames; i++) {
                        env_flags[i] = stmlib::ExtractGateFlags(
                            ts.tides_prev_gate, ts.voice_active);
                        ts.tides_prev_gate = env_flags[i];
                    }

                    float base_freq;
                    switch (active_profile) {
                        case ENV_PROFILE_RHYTHM:  base_freq = 0.0005f; break;
                        case ENV_PROFILE_MELODIC: base_freq = 0.00008f; break;
                        case ENV_PROFILE_EFFECT:  base_freq = 0.00003f; break;
                        case ENV_PROFILE_WILD:
                        default:                  base_freq = 0.00006f; break;
                    }
                    float env_freq = base_freq * env_freq_mult;

                    tides::PolySlopeGenerator::OutputSample env_out[kMaxFrames];
                    ts.tides_env.Render(
                        tides::RAMP_MODE_AR,
                        tides::OUTPUT_MODE_AMPLITUDE,
                        tides::RANGE_CONTROL,
                        env_freq,
                        env_pw,
                        env_shape,
                        env_smoothness,
                        0.6f,
                        env_flags, nullptr, env_out, static_cast<size_t>(num_frames)
                    );

                    for (int i = 0; i < num_frames; i++) {
                        float env = env_out[i].channel[0] * kTidesNorm;
                        if (env < 0.0f) env = 0.0f;
                        if (env > 1.0f) env = 1.0f;
                        ts.tides_env_level = env;
                        track_buffer[i] *= env;
                    }
                } else {
                    // === SIMPLE AD ENVELOPE ===
                    float attack_samples = 200.0f;
                    float decay_coeff = 1.0f - (4.0f / (0.01f * sample_rate + space * sample_rate * 0.5f));
                    if (decay_coeff < 0.99f) decay_coeff = 0.99f;
                    if (decay_coeff > 0.99999f) decay_coeff = 0.99999f;

                    for (int i = 0; i < num_frames; i++) {
                        if (ts.voice_active && ts.tides_env_level < 1.0f) {
                            ts.tides_env_level += 1.0f / attack_samples;
                            if (ts.tides_env_level > 1.0f) ts.tides_env_level = 1.0f;
                        } else if (!ts.voice_active) {
                            ts.tides_env_level *= decay_coeff;
                            if (ts.tides_env_level < 0.001f) ts.tides_env_level = 0.0f;
                        }
                        track_buffer[i] *= ts.tides_env_level;
                    }
                }
            }
        }

        // ── Mix to stereo with constant-power pan ──
        float vol = track_volume;
        float track_peak = 0.0f;

        // Classify track into bus: engine-type for drums, track role for bass.
        // Track 3 is always the bass track across all scenes, regardless of engine.
        PulsarBusType bus;
        if (t == 3) {
            bus = PULSAR_BUS_BASS;
        } else {
            int engine_id = ts.engine_index;
            if (engine_id < 0) engine_id = 0;
            if (engine_id >= 24) engine_id = 0;
            bus = kEngineBusType[engine_id];
        }

        // Select bus output pointers
        float* bus_l;
        float* bus_r;
        switch (bus) {
            case PULSAR_BUS_DRUMS:
                bus_l = engine->pulsar_bus_drums_l;
                bus_r = engine->pulsar_bus_drums_r;
                break;
            case PULSAR_BUS_BASS:
                bus_l = engine->pulsar_bus_bass_l;
                bus_r = engine->pulsar_bus_bass_r;
                break;
            default: // PULSAR_BUS_KEYS
                bus_l = engine->pulsar_bus_keys_l;
                bus_r = engine->pulsar_bus_keys_r;
                break;
        }

        for (int i = 0; i < num_frames; i++) {
            float s = track_buffer[i] * vol;
            out_l[i] += s * pan_l;
            out_r[i] += s * pan_r;
            // Also accumulate into the per-bus buffer
            bus_l[i] += s * pan_l;
            bus_r[i] += s * pan_r;
            float a = std::fabs(s);
            if (a > track_peak) track_peak = a;
        }
        // Write per-track peak to viz ring
        engine->viz_rings[VIZ_PULSAR_TRACK_0 + t].write(track_peak);

        // Sends + reverb brightness are per-engine: pick EDM vs Space slot.
        const bool use_edm = (ts.engine_index ==
            engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed));
        float delay_send_amt    = PULSAR_PICK(delay_send);
        float reverb_send_amt   = PULSAR_PICK(reverb_send);
        float reverb_brightness = PULSAR_PICK(reverb_brightness);
        if (reverb_brightness <= 0.001f) reverb_brightness = 0.5f;  // default if unset
        float rb_lp_coeff = 0.1f + reverb_brightness * 0.9f;  // 0=dark(0.1), 1=bright/bypass(1.0)

        if (delay_send_amt > 0.001f || reverb_send_amt > 0.001f) {
            for (int i = 0; i < num_frames; i++) {
                float s = track_buffer[i] * vol;
                if (delay_send_amt > 0.001f) {
                    engine->pulsar_delay_send_l[i] += s * pan_l * delay_send_amt;
                    engine->pulsar_delay_send_r[i] += s * pan_r * delay_send_amt;
                }
                if (reverb_send_amt > 0.001f) {
                    float dry_l = s * pan_l * reverb_send_amt;
                    float dry_r = s * pan_r * reverb_send_amt;
                    ts.reverb_send_filter_state_l += rb_lp_coeff * (dry_l - ts.reverb_send_filter_state_l);
                    ts.reverb_send_filter_state_r += rb_lp_coeff * (dry_r - ts.reverb_send_filter_state_r);
                    engine->pulsar_reverb_send_l[i] += ts.reverb_send_filter_state_l;
                    engine->pulsar_reverb_send_r[i] += ts.reverb_send_filter_state_r;
                }
            }
        }
    }

    // Copy bus buffers to warps_source_buffers for turntable capture.
    // warps_source_buffers were zeroed at frame start; += accumulates with other sources.
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[0][i] +=
            (engine->pulsar_bus_keys_l[i] + engine->pulsar_bus_keys_r[i]) * 0.5f;
        engine->warps_source_buffers[1][i] +=
            (engine->pulsar_bus_drums_l[i] + engine->pulsar_bus_drums_r[i]) * 0.5f;
        engine->warps_source_buffers[9][i] +=
            (engine->pulsar_bus_bass_l[i] + engine->pulsar_bus_bass_r[i]) * 0.5f;
        // Per-track effect send buses → slots 14 (delay) and 15 (reverb)
        engine->warps_source_buffers[14][i] +=
            (engine->pulsar_delay_send_l[i] + engine->pulsar_delay_send_r[i]) * 0.5f;
        engine->warps_source_buffers[15][i] +=
            (engine->pulsar_reverb_send_l[i] + engine->pulsar_reverb_send_r[i]) * 0.5f;
    }

    // ── Sub-bass cleanup: 55Hz high-pass (24dB/oct Linkwitz-Riley) ──
    // Two cascaded 12dB/oct Butterworth stages = 24dB/oct rolloff.
    // 55Hz cutoff keeps fundamentals above E2 (82Hz) clean while
    // aggressively cutting sub-bass mud from PAR/PD sub-harmonics.
    if (!state->output_hpf_initialized) {
        state->output_hpf_l.Init();
        state->output_hpf_r.Init();
        state->output_hpf2_l.Init();
        state->output_hpf2_r.Init();
        float hpf_f = 55.0f / sample_rate;
        state->output_hpf_l.set_f_q<stmlib::FREQUENCY_EXACT>(hpf_f, 0.707f);
        state->output_hpf_r.set_f_q<stmlib::FREQUENCY_EXACT>(hpf_f, 0.707f);
        state->output_hpf2_l.set_f_q<stmlib::FREQUENCY_EXACT>(hpf_f, 0.707f);
        state->output_hpf2_r.set_f_q<stmlib::FREQUENCY_EXACT>(hpf_f, 0.707f);
        state->output_hpf_initialized = true;
    }
    for (int i = 0; i < num_frames; i++) {
        out_l[i] = state->output_hpf_l.Process<stmlib::FILTER_MODE_HIGH_PASS>(out_l[i]);
        out_l[i] = state->output_hpf2_l.Process<stmlib::FILTER_MODE_HIGH_PASS>(out_l[i]);
        out_r[i] = state->output_hpf_r.Process<stmlib::FILTER_MODE_HIGH_PASS>(out_r[i]);
        out_r[i] = state->output_hpf2_r.Process<stmlib::FILTER_MODE_HIGH_PASS>(out_r[i]);
    }

    // Apply mix gain BEFORE bus limiter so soft_limit catches the boosted peaks.
    // Slight overdrive (3.3x vs old 3.0x) pushes more signal into tanh saturation
    // zone, generating harmonics in the 80-250Hz "feel" range.
    static constexpr float kPulsarOutputGain = 3.3f;
    float output_gain = mix * kPulsarOutputGain;
    for (int i = 0; i < num_frames; i++) {
        out_l[i] *= output_gain;
        out_r[i] *= output_gain;
    }

    // Bus limiter: soft-clip the boosted 8-track sum to prevent digital overs.
    // soft_limit is linear below 0.5, tanh saturation above — natural bus compression.
    // The slight overdrive above means more content enters the tanh zone, adding
    // subtle warmth and harmonic density.
    for (int i = 0; i < num_frames; i++) {
        out_l[i] = soft_limit(out_l[i]);
        out_r[i] = soft_limit(out_r[i]);
    }

    // ── Publish beat_phase for master-bus stutter sync ──
    {
        int ph = state->tracks[0].playhead;
        float step_frac = static_cast<float>(state->clock_accumulator / samples_per_step);
        float bp = (static_cast<float>(ph % 4) + step_frac) / 4.0f;
        if (bp >= 1.0f) bp -= 1.0f;
        engine->beat_phase.store(bp, std::memory_order_relaxed);
        engine->clock_bpm.store(bpm, std::memory_order_relaxed);
    }

    // ── Copy to graph output buffers for effects routing ──
    std::memcpy(u->output_buffers[OPORT_OUT], out_l, num_frames * sizeof(float));
    std::memcpy(u->output_buffers[OPORT_OUT_RIGHT], out_r, num_frames * sizeof(float));

    // ── Write visualization data ──
    auto& viz = engine->pulsar_viz;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const PulsarTrackState& ts = state->tracks[t];
        viz.playheads[t] = ts.playhead;
        int sc = std::min(ts.step_count, kMaxPulsarSteps);
        viz.step_counts[t] = sc;
        for (int s = 0; s < sc; s++) {
            viz.step_gates[t][s] = ts.steps[s].gate;
            viz.step_velocities[t][s] = ts.steps[s].velocity;
        }
    }
    engine->pulsar_viz_version.fetch_add(1, std::memory_order_release);

    #undef PULSAR_PICK
}
