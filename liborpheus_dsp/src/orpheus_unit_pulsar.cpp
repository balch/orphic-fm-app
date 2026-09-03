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
#include "pulsar_lick_select.h"
#include "pulsar_handoff.h"
#include "pulsar_mod_ranges.h"
#include "pulsar_comping.h"
#include "pulsar_rng.h"
#include "pulsar_anomaly_arm.h"
#include "pulsar_breathe.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <chrono>

// The engine's atomic transfer array and the pulsar lick working buffer must
// agree on capacity, or a full-length lick pushed from Kotlin silently
// truncates (or the engine reads past pulsar_lick[]).
static_assert(OrpheusEngine::kMaxLickSteps == kMaxLickSteps,
              "engine transfer array and pulsar lick buffer must agree");

// Per-track lick-wah bank stride. kLickWahFields is the ONLY thing that keeps the
// Kotlin marshal (PulsarViewModel, stride WahParams.FIELDS) and the load_vibe unpack
// below reading the same offsets, and a mismatch is silent: no crash, no failing
// assertion, just every track after track 0 voiced from its neighbour's floats. Anchor
// it to the struct it marshals so adding a WahParams field without widening the stride
// is a compile error here rather than a mystery in the mix.
// The Kotlin half is pinned by WahAnomalyTest.fieldsConstantMatchesSerializableArity,
// and the two structs are pinned field-for-field by voiceDefaultsMirrorCppWahParams.
static_assert(sizeof(orpheus::WahParams) == OrpheusEngine::kLickWahFields * sizeof(float),
              "kLickWahFields must equal the float count of orpheus::WahParams");
static_assert(sizeof(OrpheusEngine::pulsar_lick_wah_data) /
                  sizeof(OrpheusEngine::pulsar_lick_wah_data[0]) ==
                  1 + kNumPulsarTracks * OrpheusEngine::kLickWahFields,
              "lick-wah bank must hold the mask plus one WahParams per pulsar track");

static constexpr float kTidesNorm = 0.125f;

// Storm send taps. Fixed rather than authored: the weather voice has no per-engine
// send atomics behind it, and a wide reverb wash is what turns a dry rain bed into
// weather — the pulsar reverb is the only diffusion the storm gets. The reverb tap is
// darkened first (storm::kStormSendBrightness); undarkened, this much wide-band bed in
// a reverb reads as static.
static constexpr float kStormReverbSend = 0.45f;
static constexpr float kStormDelaySend  = 0.15f;

// StormAnomaly rumble floor, as a fraction of the authored strike intensity. Half was
// picked to sit under a default 0.7 intensity as a clearly-present roll without
// swamping a section that authored its own bed.
// EAR-TUNE(storm-anomaly-floor)
static constexpr float kStormAnomalyFloorScale = 0.5f;
// Fixed intensity for the per-bar weather strikes. Weather authors a CHANCE, not a
// level: distant grumbling that never competes with an anomaly's headline strike.
// EAR-TUNE(weather-strike-intensity)
static constexpr float kWeatherStrikeIntensity = 0.55f;

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

// step_hash and duck_passes are defined in pulsar_rng.h (shared with tests)

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
    if (state->lick_length > 0 || state->bass_line_length > 0) {
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
                // Bass channel gets the same bounded-drift snap-back.
                if (state->bass_line_length > 0) {
                    int bass_drift_max = std::max(1,
                        static_cast<int>(state->bass_line_mutation * 4.0f + 0.5f));
                    for (int i = 0; i < state->bass_line_length; i++) {
                        int drift = state->bass_line[i].scale_degree
                                  - state->original_bass_line[i].scale_degree;
                        if (std::abs(drift) > bass_drift_max) {
                            state->bass_line[i].scale_degree =
                                state->original_bass_line[i].scale_degree
                                + std::max(-bass_drift_max, std::min(drift, bass_drift_max));
                        }
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

            // Ghost notes: activate inactive steps with low velocity.
            // Percussive tracks are exempt: mutation never removes drum
            // gates, so runtime ghosts accumulated monotonically until the
            // déjà-vu reset (~2 minutes at typical settings) and the kit got
            // progressively busier and sloppier over each window. Drum ghosts
            // come from the pattern generator's authored ghost_probability.
            if (!step.gate) {
                if (ts.role == TrackRole::PERCUSSIVE) continue;
                float ghost_prob = track_var * 0.08f;  // up to 8% chance per step
                if (roll < ghost_prob) {
                    step.gate = true;
                    step.velocity = 0.15f + roll * 0.15f / std::max(ghost_prob, 0.001f);
                    step.duration = 0.2f;
                    // Keep existing note (from preset)
                }
                continue;
            }

            // Accent life comes from the per-hit variation_amt jitter applied
            // at trigger time: it is hash-fresh every loop and zero-mean
            // around the STORED velocity. The in-place walk that used to live
            // here (velocity = clamp01(velocity + offset) once per loop)
            // compounded into an unbounded-within-window random walk, so
            // accents audibly diverged from the authored pattern between
            // déjà-vu resets.

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
    // init_chord_progression clears anchor_bars/drift_range to a neutral baseline.
    // Put the vibe's authored values back: this runs at EVERY section entry, and
    // they are otherwise only set in load_vibe, so without this the first flip
    // zeroed them for the rest of the song — taking chordTransitionMatrix and the
    // progression-style Markov with it, since both are gated on drift_range.
    state->chord_state.anchor_bars =
        engine->pulsar_progression_anchor.load(std::memory_order_relaxed);
    state->chord_state.drift_range =
        engine->pulsar_progression_drift_range.load(std::memory_order_relaxed);

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
    state->tension.half_lick         = static_cast<HalfLickMode>(
        engine->pulsar_tension_half_lick.load(std::memory_order_relaxed));
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

// Which authored channel a lick track renders. BASS tracks read the bass line
// buffers; everything else reads the lead lick (rotation/anomaly capable).
struct LickChannel {
    const PulsarLickStep* lick;
    int length;
    int loop_length;
    float mutation;
    int octave;
};
static LickChannel track_lick_channel(PulsarState* state, OrpheusEngine* engine, int t) {
    if (engine->pulsar_track_lick_source[t].load(std::memory_order_relaxed) == 1) {
        return { state->bass_line, state->bass_line_length, state->bass_line_loop_length,
                 state->bass_line_mutation, state->bass_line_octave };
    }
    return { state->lick, state->lick_length, state->lick_loop_length,
             state->lick_mutation, state->lick_octave };
}

// Eligible-track mask for the wah anomaly, read LIVE from the pushed atomics (same
// convention as the per-block role reads elsewhere in this file, rather than the
// ts.role mirror, so a live role push from Kotlin is honored). The predicate itself
// lives in orpheus_unit_pulsar.h so the harness can pin it without an engine fixture.
static uint8_t pulsar_wah_lead_mask(OrpheusEngine* engine) {
    int roles[kNumPulsarTracks];
    int sources[kNumPulsarTracks];
    for (int i = 0; i < kNumPulsarTracks; i++) {
        roles[i]   = engine->pulsar_track_role[i].load(std::memory_order_relaxed);
        sources[i] = engine->pulsar_track_lick_source[i].load(std::memory_order_relaxed);
    }
    return wah_anomaly_lead_mask(roles, sources);
}

// Open a wah-anomaly window over `mask`. Fresh voices (eligible tracks with no standing
// lick wah) are Init'd so they start from a clean filter at phase 0. A takeover track's
// lick_wah_voice is deliberately NOT touched: preserving its running Svf state and LFO
// phase is exactly what makes the takeover click-free, and the param lerp handles the
// rest. Callers must have already checked that the mask is non-empty.
static void arm_anomaly_wah(PulsarState* state, uint8_t mask, int samples) {
    if (samples <= 0 || mask == 0) return;
    state->anomaly_wah_params        = state->wah_config.voice;
    state->anomaly_wah_samples_total = samples;
    state->anomaly_wah_samples_left  = samples;
    state->anomaly_wah_mask          = mask;
    for (int i = 0; i < kNumPulsarTracks; i++) {
        if (!(mask & (1 << i))) continue;
        if (state->lick_wah_declared && (state->lick_wah_mask & (1 << i))) continue;  // takeover
        state->anomaly_wah_voice[i].Init();
    }
}

// ── Generic per-edge section transition effects ─────────────────────────────
// Staging rules and the row layout live in pulsar_transition_fx.h; only the parts
// that need the engine (arming master effects) and the arrangement are here.

// Which outgoing edge the section is currently planning to leave by. First match on
// target index — the same rule find_edge_transition_bars() uses, so the staged
// effects and the pre-roll ramp can never disagree about which edge is in play.
static int find_planned_edge_index(const ArrangementParams& arr, const SectionState& sec_state) {
    int cur = sec_state.current_section;
    if (cur < 0 || cur >= arr.section_count) return -1;
    const SectionParam& sec = arr.sections[cur];
    for (int e = 0; e < sec.transition_count; e++) {
        if (sec.transitions[e].target_index == sec_state.next_section_planned) return e;
    }
    return -1;
}

// Arm the master effect a staged row asks for. Deliberately unguarded: a transition
// effect always (re)arms, even over a running anomaly. The anomaly dispatch keeps its
// own is_active() guards; transitions win.
static void fire_transition_fx(OrpheusEngine* engine, PulsarState* state,
                               int type, float p0, float p1, float p2,
                               float sample_rate) {
    switch (type) {
        case TRANS_FX_SCRATCH: {
            int samples = static_cast<int>(p0 * sample_rate / 1000.0f);
            engine->master_scratch_l.arm(samples, sample_rate, 0);
            engine->master_scratch_r.arm(samples, sample_rate, 0x55555555u);
            break;
        }
        case TRANS_FX_TAPE_STOP: {
            int samples = static_cast<int>(p0 * sample_rate / 1000.0f);
            engine->master_tape_stop_l.arm(samples);
            engine->master_tape_stop_r.arm(samples);
            break;
        }
        case TRANS_FX_STRIKE:
            // Unguarded like its siblings: a strike landing inside the 30-120 ms window
            // an earlier one is still waiting on truncates that burst and its tail. The
            // anomaly and weather paths guard on strike_active(); transitions do not.
            // p2 is the authored sub-bar delay in milliseconds — the way a pair of strikes
            // is spaced far enough apart to sound as two cracks rather than one.
            state->storm_voice.trigger_strike(p0, p1, p2);
            break;
        default:
            break;
    }
}

// Re-stage the pending list for the section that is now current and the edge it plans
// to leave by. Called at vibe load and at every flip, so a stale edge's rows can never
// survive into the next section.
static void stage_transition_fx_for_planned_edge(PulsarState* state) {
    state->pending_fx_count = 0;
    if (!state->arrangement.active || state->trans_fx_count == 0) return;
    int edge = find_planned_edge_index(state->arrangement, state->section_state);
    if (edge < 0) return;
    state->pending_fx_count = stage_transition_fx(
        state->trans_fx, state->trans_fx_count,
        state->section_state.current_section, edge,
        state->section_state.bars_remaining,
        state->pending_fx, kMaxPendingFx);
}

// Fire and disarm every pending whose bar countdown has run out. after_flip rows are
// skipped: they belong to the section the flip enters, however many bars the outgoing
// one actually runs.
static void fire_due_transition_fx(OrpheusEngine* engine, PulsarState* state, float sample_rate) {
    for (int i = 0; i < state->pending_fx_count; i++) {
        PendingTransFx& p = state->pending_fx[i];
        if (!p.armed || p.after_flip || p.bars_until_fire > 0.0f) continue;
        p.armed = false;
        fire_transition_fx(engine, state, p.type, p.p0, p.p1, p.p2, sample_rate);
    }
}

// The flip fires everything the taken edge still has armed EXCEPT the positive
// offsets, which move to post_flip_fx to count down inside the section just entered.
// Normalized to one bar rather than carried at their staged value: the cap is +1, so
// "the next boundary" is the whole of what a positive offset can mean. Returns the
// carry count, which the entry pass below appends to.
static int fire_and_carry_transition_fx_at_flip(OrpheusEngine* engine, PulsarState* state,
                                                float sample_rate, int carried) {
    for (int i = 0; i < state->pending_fx_count; i++) {
        PendingTransFx& p = state->pending_fx[i];
        if (!p.armed) continue;
        p.armed = false;
        if (p.after_flip && carried < kMaxPendingFx) {
            state->post_flip_fx[carried] = p;
            state->post_flip_fx[carried].armed = true;
            state->post_flip_fx[carried].bars_until_fire = 1.0f;
            carried++;
            continue;
        }
        fire_transition_fx(engine, state, p.type, p.p0, p.p1, p.p2, sample_rate);
    }
    return carried;
}

// The arriving section's own entry rows, sharing the departure's carry list. Runs on
// EVERY arrival, a re-routed one included — an entry row is keyed to its section, not
// to the edge that reached it. Song start is not an arrival, so an opening section's
// entry rows never fire: only a flip reaches here.
static int fire_and_carry_entry_transition_fx(OrpheusEngine* engine, PulsarState* state,
                                              int section, float sample_rate, int carried) {
    PendingTransFx entry[kMaxPendingFx];
    int n = stage_entry_transition_fx(state->trans_fx, state->trans_fx_count, section,
                                      entry, kMaxPendingFx);
    for (int i = 0; i < n; i++) {
        if (entry[i].after_flip) {
            if (carried < kMaxPendingFx) state->post_flip_fx[carried++] = entry[i];
            continue;
        }
        fire_transition_fx(engine, state, entry[i].type, entry[i].p0, entry[i].p1,
                           entry[i].p2, sample_rate);
    }
    return carried;
}

// One elapsed bar for the carried rows, firing whichever have arrived. Runs on EVERY
// bar boundary, flips included — a carry whose new section is one bar long still has
// to land on that section's own flip rather than be dropped by it.
static void tick_post_flip_transition_fx(OrpheusEngine* engine, PulsarState* state,
                                         float sample_rate) {
    for (int i = 0; i < state->post_flip_fx_count; i++) {
        PendingTransFx& p = state->post_flip_fx[i];
        if (!p.armed) continue;
        p.bars_until_fire -= 1.0f;
        if (p.bars_until_fire > 0.0f) continue;
        p.armed = false;
        fire_transition_fx(engine, state, p.type, p.p0, p.p1, p.p2, sample_rate);
    }
}

// StormAnomaly: a strike now, a raised rumble bed under it for the drawn window, and
// a second strike a bar in when the window is long enough to carry one. The window is
// counted in bars and ticked at bar boundaries, like the pending-fx list; the caller
// owns the declared/strike_active guards.
static void arm_storm_anomaly(PulsarState* state) {
    const float bars = anomaly_draw_bars(state->storm_config.dur_min,
                                         state->storm_config.dur_max,
                                         state->master_anomaly_seed);
    const float intensity = clamp01(state->storm_config.intensity);
    state->storm_floor_bars_left = bars;
    state->storm_floor_rumble = clamp01(kStormAnomalyFloorScale * intensity);
    state->storm_second_strike_pending = bars >= 2.0f;
    state->storm_voice.trigger_strike(intensity, state->storm_config.distance);
}

// Copy lick-pool slot `idx` into the active lick buffers (state->lick / original_lick).
// Resets drift so the swapped lick starts from its pristine form.
static void apply_pool_lick(PulsarState* state, int idx) {
    int len = state->lick_pool_len[idx];
    if (len > kMaxLickSteps) len = kMaxLickSteps;
    state->lick_length = len;
    state->lick_loop_length = (state->lick_pool_loop[idx] > 0) ? state->lick_pool_loop[idx] : len;
    for (int i = 0; i < len; i++) state->lick[i] = state->lick_pool[idx][i];
    std::memcpy(state->original_lick, state->lick, sizeof(PulsarLickStep) * len);
    state->in_spurt = false;
    state->spurt_bars_remaining = 0;
}

// Re-render all FILL/Squash melodic tracks from state->lick — used after a rotation or
// anomaly swap. Mirrors the déjà-vu render loop (orpheus_unit_pulsar.cpp:2414-2435):
// derives root/scale/note-range/step-count from the live engine atomics.
static void regenerate_lick_tracks(PulsarState* state, OrpheusEngine* engine, uint32_t seed) {
    if (state->lick_length <= 0 && state->bass_line_length <= 0) return;
    int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
    if (si < 0) si = 0;
    if (si >= kNumPulsarScales) si = kNumPulsarScales - 1;
    const PulsarScale& scale = kPulsarScales[si];
    uint8_t root  = static_cast<uint8_t>(engine->pulsar_root_note.load(std::memory_order_relaxed));
    uint8_t rg_lo = static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed));
    uint8_t rg_hi = static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed));
    int step_count_cfg = engine->pulsar_step_count.load(std::memory_order_relaxed);
    if (step_count_cfg <= 0) step_count_cfg = 16;
    if (step_count_cfg > kMaxPulsarSteps) step_count_cfg = kMaxPulsarSteps;  // guard ts.steps[kMaxPulsarSteps]
    for (int rt = 0; rt < kNumPulsarTracks; rt++) {
        PulsarTrackState& rts = state->tracks[rt];
        TrackRole r_role = static_cast<TrackRole>(engine->pulsar_track_role[rt].load(std::memory_order_relaxed));
        if (r_role == TrackRole::PERCUSSIVE) continue;
        LickMode r_lick_mode = static_cast<LickMode>(
            engine->pulsar_track_lick_mode[rt].load(std::memory_order_relaxed));
        if (r_lick_mode == LickMode::NONE) continue;
        LickChannel ch = track_lick_channel(state, engine, rt);
        if (ch.length <= 0) continue;
        float ch_mut = state->in_spurt
            ? std::min(1.0f, ch.mutation * 3.0f) : ch.mutation;
        render_lick_into_track(rts, rt, ch.lick, ch.length,
                               ch_mut, root, scale, seed,
                               rts.bar_strategy, step_count_cfg, ch.octave,
                               rg_lo, rg_hi, ch.loop_length,
                               engine->pulsar_track_lick_degree_offset[rt].load(std::memory_order_relaxed));
    }
}

// ── Vibe loading (reads recipe from engine atomics) ─────────────────

// Start a section's band solo, and for LICK_BUILDER seed the live lick from the
// SOLOIST's channel: a BASS-source lead mutates the bass line, not the lead lick
// ("owned and mutated by the bass player").
//
// Shared by the section-change handler and by load_vibe's opening-section apply.
// The opening section is entered through init_section_state, which fires no
// advance_section, so an intro declaring a soloMode used to get none of this.
static void start_section_solo(PulsarState* state, OrpheusEngine* engine,
                               const SectionParam& sec) {
    start_band_solo(state->band_solo_state, state->band_solo_config,
                    sec, state->tracks, state->mutation_seed,
                    kNumPulsarTracks, state->track_ducking);
    if (sec.solo_mode != SoloModeId::LICK_BUILDER) return;

    int lead = state->band_solo_state.lead_member;
    bool bass_lead = false;
    if (lead >= 0) {
        const BandMemberParam& lm = state->band_solo_config.members[lead];
        for (int ti = 0; ti < lm.track_count; ti++) {
            int rt = lm.tracks[ti];
            if (rt < 0 || rt >= kNumPulsarTracks) continue;
            LickMode m = static_cast<LickMode>(
                engine->pulsar_track_lick_mode[rt].load(std::memory_order_relaxed));
            if (m == LickMode::NONE) continue;
            // First lick track decides (mixed-source members are allowed but
            // take the first; same rule as track_lick_channel).
            bass_lead = engine->pulsar_track_lick_source[rt].load(
                std::memory_order_relaxed) == 1;
            break;
        }
    }
    const PulsarLickStep* src = nullptr;
    int src_len = 0;
    if (bass_lead && state->bass_line_length > 0) {
        src = state->bass_line; src_len = state->bass_line_length;
    } else if (state->lick_length > 0) {
        src = state->lick; src_len = state->lick_length;
        bass_lead = false;  // fell back to the lead channel
    }
    state->live_lick_bass_channel = bass_lead;
    if (src != nullptr) {
        int n = src_len < kMaxLickSteps ? src_len : kMaxLickSteps;
        state->live_lick_length = n;
        state->live_lick_active = true;
        for (int i = 0; i < n; i++) {
            state->live_lick_degrees[i] = src[i].scale_degree;
            state->live_lick_durations[i] = src[i].duration;
            state->live_lick_velocities[i] = src[i].velocity;
            // MUT-4: snapshot section-entry degrees to clamp drift.
            state->live_lick_base_degrees[i] = src[i].scale_degree;
        }
    }
}

// ── Per-section density ─────────────────────────────────────────────────────
//
// density is a pattern-GENERATION input, not a per-block mixer control: the generators
// consume it while BUILDING a track's step array, and nothing re-reads it while that
// array plays. So a section that changes a track's density has to regenerate that
// track's pattern at the boundary, which is what these two functions do.

// Resolve every track's density for `sec`: the section's per-track override when set
// (>= 0 — and 0 legitimately means "track out"), else the vibe's base density. One
// resolver so vibe load, section entry and the déjà-vu reset cannot disagree.
static void resolve_section_densities(const OrpheusEngine* engine, const SectionParam* sec,
                                      float out[kNumPulsarTracks]) {
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const float base = engine->pulsar_genre_density[t].load(std::memory_order_relaxed);
        const float ovr  = sec ? sec->track_density_override[t] : -1.0f;
        out[t] = (ovr >= 0.0f) ? ovr : base;
    }
}

// Regenerate every track whose section-effective density differs from the density its
// current pattern was built at, recording the new value. Returns the count regenerated.
//
// Only GENERATIVE tracks are rebuilt. A CHORDAL track walks a comping template and a
// FILL/SQUASH lick owns its own steps — neither generator takes a density parameter, so
// there a density override means nothing and the pattern is left alone. Tracks whose
// density did not change are skipped entirely, so entering a section that says nothing
// about density can never perturb the groove.
static int apply_section_densities(PulsarState* state, OrpheusEngine* engine,
                                   const SectionParam* sec) {
    float density[kNumPulsarTracks];
    resolve_section_densities(engine, sec, density);

    // Genre profile for regeneration, with the section-effective densities substituted.
    PulsarGenreProfile genre;
    for (int t = 0; t < kNumPulsarTracks; t++) genre.base_density[t] = density[t];
    genre.swing_amount      = engine->pulsar_genre_swing.load(std::memory_order_relaxed);
    genre.ghost_probability = engine->pulsar_genre_ghost_prob.load(std::memory_order_relaxed);
    genre.note_range_low    = static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed));
    genre.note_range_high   = static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed));
    genre.rhythm_density    = engine->pulsar_genre_rhythm_density.load(std::memory_order_relaxed);

    int scale_idx = engine->pulsar_scale_index.load(std::memory_order_relaxed);
    if (scale_idx < 0) scale_idx = 0;
    if (scale_idx >= kNumPulsarScales) scale_idx = kNumPulsarScales - 1;
    const PulsarScale& scale = kPulsarScales[scale_idx];
    const int root = engine->pulsar_root_note.load(std::memory_order_relaxed);

    int step_count_cfg = engine->pulsar_step_count.load(std::memory_order_relaxed);
    if (step_count_cfg <= 0) step_count_cfg = 16;
    const int bar1_len = (step_count_cfg > 16) ? 16 : step_count_cfg;

    // Same seed basis as the déjà-vu reset, so a track returning to a density it played
    // before gets that pattern back instead of drifting one step further every section.
    const uint32_t section_seed = state->seed_counter * 2654435761u;
    const float energy     = engine->pulsar_energy.load(std::memory_order_relaxed);
    const float complexity = engine->pulsar_complexity.load(std::memory_order_relaxed);

    int regenerated = 0;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];

        // density 0 = "track out" for this section, enforced as a render mute so it works
        // for every role and reverses on exit without touching the pattern. Deliberately
        // does NOT update generated_density: the steps are untouched, so leaving the
        // section brings the original groove back exactly, with no regeneration at all.
        ts.section_density_out = (density[t] == 0.0f);
        if (ts.section_density_out) continue;

        if (ts.generated_density == density[t]) continue;
        ts.generated_density = density[t];

        if (ts.role == TrackRole::CHORDAL) continue;
        const LickMode lick_mode = static_cast<LickMode>(
            engine->pulsar_track_lick_mode[t].load(std::memory_order_relaxed));
        LickChannel ch = track_lick_channel(state, engine, t);
        if (ch.length > 0 && lick_mode != LickMode::NONE && ts.role == TrackRole::MELODIC) continue;

        const bool percussive = (ts.role == TrackRole::PERCUSSIVE);
        const float hold_prob = engine->pulsar_track_hold_probability[t].load(std::memory_order_relaxed);
        int hold_min = engine->pulsar_track_hold_length_min[t].load(std::memory_order_relaxed);
        int hold_max = engine->pulsar_track_hold_length_max[t].load(std::memory_order_relaxed);
        if (hold_min < 1) hold_min = 2;
        if (hold_max < hold_min) hold_max = hold_min;
        const float density_ovr = engine->pulsar_track_density_override[t].load(std::memory_order_relaxed);
        const int nr_low  = engine->pulsar_track_note_range_low[t].load(std::memory_order_relaxed);
        const int nr_high = engine->pulsar_track_note_range_high[t].load(std::memory_order_relaxed);
        const int eng_note_min = (ts.engine_index >= 0 && ts.engine_index < 24)
            ? kEngineModRanges[ts.engine_index].note_min : 0;

        generate_track_pattern(ts, t, percussive, genre,
                               static_cast<uint8_t>(root), scale, bar1_len, section_seed,
                               0, hold_prob, hold_min, hold_max,
                               density_ovr, nr_low, nr_high, eng_note_min);

        // generate_track_pattern only fills bar 1; without this the regenerated track
        // would sit at step_count 16 while every other track runs 32 and drift out of
        // phase within a bar. Same contract as the load and déjà-vu paths.
        if (step_count_cfg > 16) {
            const float ch_mut = state->in_spurt
                ? std::min(1.0f, ch.mutation * 3.0f) : ch.mutation;
            apply_bar_strategy(ts, t, ts.bar_strategy, percussive, genre,
                               static_cast<uint8_t>(root), scale,
                               energy, complexity,
                               ch.lick, ch.length, ch_mut,
                               section_seed ^ (t * 13331u),
                               ch.octave,
                               ch.loop_length);
        }
        if (ts.playhead >= ts.step_count) ts.playhead = 0;
        regenerated++;
    }
    return regenerated;
}

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
    state->live_lick_bass_channel = false;
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
        // Pin seed_counter too: déjà-vu resets regenerate patterns from
        // seed_counter (not mutation_seed), and PulsarState construction
        // seeds it from the wall clock. Without this, a locked-seed vibe
        // is only reproducible until its first déjà-vu reset (~1-2 min),
        // after which patterns diverge run-to-run.
        state->seed_counter = static_cast<uint32_t>(seed_val);
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

    // Void RNG: always stir from the wall clock so the anomaly's occurrence
    // varies per play, independent of the (possibly pinned) pattern seed.
    {
        uint64_t vstir = static_cast<uint64_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
        state->void_seed = base_seed ^ static_cast<uint32_t>(vstir * 0x9E3779B9u) ^ 0xA5A5A5A5u;
    }

    // Lick-select RNG: play-scoped, independent of the pattern seed AND void_seed, so
    // Fire Sky .5f rotation + anomaly vary per play. Distinct salt from void_seed.
    {
        uint64_t lstir = static_cast<uint64_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
        state->lick_select_seed = base_seed ^ static_cast<uint32_t>(lstir * 0x2545F491u) ^ 0x5A5A5A5Au;
        if (state->lick_select_seed == 0) state->lick_select_seed = 0x1234567u;  // xorshift needs nonzero
    }

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

    // Fire Sky .5f lick pool: rotation members + optional anomaly lick. When present
    // (pool_count > 0), pick an initial rotation member and override the single lick.
    int pool_count = engine->pulsar_lick_pool_count.load(std::memory_order_acquire);
    if (pool_count > kMaxLickPool) pool_count = kMaxLickPool;
    state->lick_pool_count = pool_count;
    if (pool_count > 0) {
        state->lick_anomaly_index  = engine->pulsar_lick_anomaly_index;
        state->lick_anomaly_chance = engine->pulsar_lick_anomaly_chance;
        for (int s = 0; s < kMaxLickPool; s++) {
            int plen = engine->pulsar_lick_pool_len[s];
            if (plen > kMaxLickSteps) plen = kMaxLickSteps;
            state->lick_pool_len[s]  = plen;
            state->lick_pool_loop[s] = engine->pulsar_lick_pool_loop[s];
            for (int i = 0; i < plen; i++) {
                int b = s * (kMaxLickSteps * kLickFieldsPerStep) + i * kLickFieldsPerStep;
                state->lick_pool[s][i].scale_degree = static_cast<int8_t>(engine->pulsar_lick_pool_data[b + 0]);
                state->lick_pool[s][i].duration     = engine->pulsar_lick_pool_data[b + 1];
                state->lick_pool[s][i].velocity     = engine->pulsar_lick_pool_data[b + 2];
                state->lick_pool[s][i].glide_rate   = engine->pulsar_lick_pool_data[b + 3];
            }
        }
        state->active_rotation_index = lick_pick_rotation(state->lick_select_seed, pool_count);
        state->current_lick_index = state->active_rotation_index;
        apply_pool_lick(state, state->active_rotation_index);  // overrides state->lick
        // No load-scoped re-sync needed after this override: every render call in the
        // loop below resolves its buffer through track_lick_channel(), which reads
        // state->lick / state->lick_length live.
    } else {
        state->current_lick_index = -1;
    }

    // Read bass line channel (length acts as acquire fence, same contract as lick)
    int bass_len = engine->pulsar_bass_line_length.load(std::memory_order_acquire);
    if (bass_len > kMaxLickSteps) bass_len = kMaxLickSteps;
    state->bass_line_length = bass_len;
    int bass_loop = engine->pulsar_bass_line_loop.load(std::memory_order_relaxed);
    state->bass_line_loop_length = (bass_loop > 0) ? bass_loop : bass_len;
    for (int i = 0; i < bass_len; i++) {
        state->bass_line[i].scale_degree = engine->pulsar_bass_line[i].scale_degree;
        state->bass_line[i].duration     = engine->pulsar_bass_line[i].duration;
        state->bass_line[i].velocity     = engine->pulsar_bass_line[i].velocity;
        state->bass_line[i].glide_rate   = engine->pulsar_bass_line[i].glide_rate;
    }
    state->bass_line_mutation = engine->pulsar_bass_line_mutation.load(std::memory_order_relaxed);
    state->bass_line_octave   = engine->pulsar_bass_line_octave.load(std::memory_order_relaxed);
    std::memcpy(state->original_bass_line, state->bass_line, sizeof(PulsarLickStep) * bass_len);

    int root = engine->pulsar_root_note.load(std::memory_order_relaxed);
    int scale_idx = engine->pulsar_scale_index.load(std::memory_order_relaxed);
    if (scale_idx < 0) scale_idx = 0;
    if (scale_idx >= static_cast<int>(sizeof(kPulsarScales) / sizeof(kPulsarScales[0])))
        scale_idx = static_cast<int>(sizeof(kPulsarScales) / sizeof(kPulsarScales[0])) - 1;
    const PulsarScale& scale = kPulsarScales[scale_idx];

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

        // Default: no half-lick truncation. Only FILL leads set this (below) so the
        // tension half_lick flag can loop just their first bar; all other tracks stay full.
        ts.half_loop_len = 0;

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

        // This track's authored channel (LEAD lick or BASS line) and its effective
        // mutation — the tension spurt amplifies 3x, capped at 1.0. Every render call
        // below reads these, including the generative branch: mutation is per CHANNEL,
        // so a BASS-source track must never be rendered at the lead's mutation.
        LickChannel ch = track_lick_channel(state, engine, t);
        float ch_mut = state->in_spurt
            ? std::min(1.0f, ch.mutation * 3.0f) : ch.mutation;

        // Density this pattern is about to be built at, so a section entry can tell whether
        // its per-track override actually changes anything. See apply_section_densities().
        ts.generated_density = genre.base_density[t];

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
        } else if (ch.length > 0 && lick_mode != LickMode::NONE && role == TrackRole::MELODIC) {
            const int lick_deg_off =
                engine->pulsar_track_lick_degree_offset[t].load(std::memory_order_relaxed);
            if (lick_mode == LickMode::FILL) {
                // FILL: lick spans full step count, bypass bar strategy
                ts.step_count = step_count_config;
                // Tension half_lick can later loop just bar 1 of this FILL riff (the
                // "jam on the first part" mode) — record its length. The full pattern
                // is still built; the render-time playhead wrap does the truncation so
                // it can toggle per-section without regenerating the pattern.
                ts.half_loop_len = bar1_len;
                if (bar_strategy == BarStrategy::CALL_RESPONSE) {
                    bar_strategy_call_response(ts.steps, step_count_config,
                                               ch.lick, ch.length,
                                               ch_mut,
                                               static_cast<uint8_t>(root), scale,
                                               base_seed ^ (t * 7919u),
                                               ch.octave,
                                               genre.note_range_low, genre.note_range_high,
                                               ch.loop_length,
                                               lick_deg_off);
                } else {
                    generate_lick_pattern(ts.steps, ts.step_count, ch.lick, ch.length,
                                          ch_mut, static_cast<uint8_t>(root), scale,
                                          base_seed ^ (t * 7919u), 0,
                                          ch.octave,
                                          genre.note_range_low, genre.note_range_high,
                                          ch.loop_length,
                                          lick_deg_off);
                }
                // No bar strategy — FILL owns the full pattern
            } else {
                // SQUASH: lick compressed to bar1_len, bar strategy handles bar 2
                ts.step_count = bar1_len;
                generate_lick_pattern(ts.steps, bar1_len, ch.lick, ch.length,
                                      ch_mut, static_cast<uint8_t>(root), scale,
                                      base_seed ^ (t * 7919u), 0,
                                      ch.octave,
                                      genre.note_range_low, genre.note_range_high,
                                      ch.loop_length,
                                      lick_deg_off);
                // Apply bar strategy for bar 2
                if (step_count_config > 16) {
                    apply_bar_strategy(ts, t, bar_strategy, (role == TrackRole::PERCUSSIVE), genre,
                                       static_cast<uint8_t>(root), scale,
                                       engine->pulsar_energy.load(std::memory_order_relaxed),
                                       engine->pulsar_complexity.load(std::memory_order_relaxed),
                                       ch.lick, ch.length, ch_mut,
                                       base_seed ^ (t * 13331u),
                                       ch.octave,
                                       ch.loop_length,
                                       lick_deg_off);
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
                // CALL_RESPONSE is the one bar strategy that renders a lick, and a track
                // landing in this branch (lickMode NONE, or an empty channel) still gets
                // one for bar 2. Feed it THIS track's channel: a lickSource = BASS track
                // has to answer with the bass line, at bass mutation and bass octave. The
                // sibling lick branch above already resolves `ch`; this call used to
                // hardcode state->lick, so a BASS-source track here rendered the LEAD riff.
                apply_bar_strategy(ts, t, bar_strategy, (role == TrackRole::PERCUSSIVE), genre,
                                   static_cast<uint8_t>(root), scale,
                                   engine->pulsar_energy.load(std::memory_order_relaxed),
                                   engine->pulsar_complexity.load(std::memory_order_relaxed),
                                   ch.lick, ch.length, ch_mut,
                                   base_seed ^ (t * 13331u),
                                   ch.octave,
                                   ch.loop_length);
            }
        }

        ts.engine_index = engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed);

        // Reset state
        ts.playhead = 0;
        // Resync on the first advance: adopt the NEW window and seat the playhead
        // at its start without incrementing. Otherwise the opening pass
        // advance-skips the downbeat and wraps at the PREVIOUS vibe's stale
        // wrap_len. (Window start is 0 for every mode except JAM_LAST_BAR, whose
        // window IS bar 2 — a FILL lead under it opens there by design.)
        ts.resync_pending = true;
        // Clear the window alongside the playhead that traverses it. Correctness
        // does not rest on resync_pending being consumed first: any reader that
        // runs before this track's first advance would otherwise see the previous
        // vibe's window. wrap_len = 0 alone is NOT enough — the adopt-as-is branch
        // falls through to playhead++ and reads steps[1].
        ts.wrap_len = 0;
        ts.wrap_start = 0;
        ts.wrap_mode = HalfLickMode::OFF;
        // A carried-over inversion would spuriously re-lock at the new vibe's
        // first section flip.
        ts.phase_inverted = false;
        // The render path reads the SMOOTHED twins (duck gate, velocity shift), and
        // their only drain is the per-bar slew inside the wrap handler — a bar away,
        // then ~6 bars at kSoloModSlew to resolve a deep duck. clear_solo_modifiers
        // zeroes the targets but not these, so a vibe loaded during a band solo
        // opened ducked with no solo left to explain it. Snapping is safe here and
        // only here: a vibe load is already a discontinuity, whereas the mid-song
        // solo-end paths need the slew to avoid a click.
        ts.solo_volume_mod_current = 0.0f;
        ts.solo_density_mod_current = 0.0f;
        ts.gate_timer = 0.0f;
        ts.voice_active = false;
        ts.swing_offset = 0.0;
        ts.tides_env.Init();
        ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
        ts.tides_env_level = 0.0f;
        ts.current_pitch = 60.0f;
        ts.target_pitch = 60.0f;
        ts.current_velocity = 0.8f;
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
        state->track_solo_behavior[t].markov_current_degree = 0;
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
            int base = s * kSectionDataFields;
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

            // Slot 15 retired (was the legacy per-section exit-scratch length); the
            // generic transition-fx bank (trans_fx_data_$i, see below) is the only
            // scratch-at-flip mechanism now. Left unread so it stays inert.

            // Slot 16: jamCarry — continue an in-flight band solo across this
            // section's entry seam (see SectionParam::jam_carry).
            sec.jam_carry =
                engine->pulsar_section_data[base + 16].load(std::memory_order_relaxed) > 0.5f;

            // Slots 21-25: SectionWeather. All five are zero when the section declares
            // no weather, so nothing here distinguishes "dry" from "absent" — an
            // all-zero bed renders nothing either way. Clamped because StormVoice
            // treats every one of these as a 0-1 control.
            {
                auto W = [&](int f) {
                    return clamp01(engine->pulsar_section_data[base + f].load(
                        std::memory_order_relaxed));
                };
                sec.weather.rain          = W(21);
                sec.weather.rumble        = W(22);
                sec.weather.strike_chance = W(23);
                sec.weather.distance      = W(24);
                sec.weather.rain_level    = W(25);
            }

            // Per-track section overrides; -1 = no override (per-track wins over section-level)
            {
                int tbase = s * kNumPulsarTracks;
                for (int t = 0; t < kNumPulsarTracks; t++) {
                    sec.track_comping_style_override[t] = engine->pulsar_section_track_comping_style[tbase + t].load(std::memory_order_relaxed);
                    sec.track_inversion_override[t]     = engine->pulsar_section_track_inversion[tbase + t].load(std::memory_order_relaxed);
                    sec.track_arp_mode_override[t]      = engine->pulsar_section_track_arp_mode[tbase + t].load(std::memory_order_relaxed);
                    sec.track_chord_follow_override[t]  = engine->pulsar_section_track_chord_follow[tbase + t].load(std::memory_order_relaxed);
                    sec.track_density_override[t]       = engine->pulsar_section_track_density[tbase + t].load(std::memory_order_relaxed);
                    // Breathe: 0 bars = off. Negative bars would invert the modulo in
                    // breathe_phase, and floor/span are 0-1 controls, so both are pinned
                    // here rather than trusted from the wire.
                    const float bb = engine->pulsar_section_track_breathe_bars[tbase + t].load(std::memory_order_relaxed);
                    sec.track_breathe_bars[t] = (bb > 0.0f) ? static_cast<int>(bb) : 0;
                    sec.track_breathe_floor[t] = clamp01(
                        engine->pulsar_section_track_breathe_floor[tbase + t].load(std::memory_order_relaxed));
                    sec.track_breathe_timbre_span[t] = clamp01(
                        engine->pulsar_section_track_breathe_timbre_span[tbase + t].load(std::memory_order_relaxed));
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
                    // Tension's OWN fixed stride -- NOT kSectionDataFields (25). It never
                    // grew the 4 weather slots pulsar_section_data added.
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
                    sec.tension_override.half_lick          = static_cast<HalfLickMode>(
                        static_cast<int>(L(7)));
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
            // Stride is edges-per-section, NOT sections. This read used kMaxSections for
            // years and was only correct because both constants happened to be 8; the
            // Kotlin writer (PulsarViewModel) has always used the edge count.
            int trans_base = (s * kMaxSectionTransitions) * 3;
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

        // Unpack per-track ducking (kTrackDuckingFields floats per track). Slot 6 is the
        // declared flag; with it clear the band solo path ignores slots 0-5 entirely.
        for (int t = 0; t < kNumPulsarTracks; t++) {
            int db = t * kTrackDuckingFields;
            DuckingParam& dp = state->track_ducking[t];
            dp.volume_reduction  = engine->pulsar_track_ducking[db + 0].load(std::memory_order_relaxed);
            dp.density_reduction = engine->pulsar_track_ducking[db + 1].load(std::memory_order_relaxed);
            dp.ghost_reduction   = engine->pulsar_track_ducking[db + 2].load(std::memory_order_relaxed);
            dp.fill_suppression  = engine->pulsar_track_ducking[db + 3].load(std::memory_order_relaxed);
            dp.simplify          = engine->pulsar_track_ducking[db + 4].load(std::memory_order_relaxed) > 0.5f;
            dp.reverb_boost      = engine->pulsar_track_ducking[db + 5].load(std::memory_order_relaxed);
            dp.declared          = engine->pulsar_track_ducking[db + 6].load(std::memory_order_relaxed) > 0.5f;
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

                // Apply the initial section's TENSION override. The section-change
                // handler does this on every later transition, but the first section
                // is entered via init_section_state (no advance_section fires), so
                // without this the intro would play the vibe-base tension (loaded by
                // reload_vibe_tension above) for its whole duration — a halfLick or
                // custom-evolution intro would silently not take effect. Base tension
                // is already loaded, so only replace it when this section overrides it.
                if (sec.has_tension_override) {
                    state->tension = sec.tension_override;
                }

                // Apply the opening section's per-track DENSITY overrides. The patterns
                // above were generated at the vibe's base densities; this rebuilds only
                // the tracks this section actually changes. Same reason as the tension
                // block: no advance_section fires for the section we start in.
                apply_section_densities(state, engine, &sec);

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

    // Void Anomaly config bank [prob, floor, rampDown, floorMin, floorMax, rampUp, ghost].
    // Loaded unconditionally (independent of arr_active), but today the void only
    // ARMS (auto and manual) while an arrangement is active — see the section-
    // advancement block below. Arming a locked-length pattern off its own loop as
    // the "section" is a possible future extension, not current behavior.
    state->void_config.probability     = engine->pulsar_void_data[0].load(std::memory_order_relaxed);
    state->void_config.floor_level     = engine->pulsar_void_data[1].load(std::memory_order_relaxed);
    state->void_config.ramp_down_bars  = engine->pulsar_void_data[2].load(std::memory_order_relaxed);
    state->void_config.floor_bars_min  = engine->pulsar_void_data[3].load(std::memory_order_relaxed);
    state->void_config.floor_bars_max  = engine->pulsar_void_data[4].load(std::memory_order_relaxed);
    state->void_config.ramp_up_bars    = engine->pulsar_void_data[5].load(std::memory_order_relaxed);
    state->void_config.ghost_intensity = engine->pulsar_void_data[6].load(std::memory_order_relaxed);
    // [7] is the explicit "this vibe declares the void anomaly" flag (Kotlin pushes
    // 1.0 when Vibe.anomalies contains a VoidAnomaly, 0.0 otherwise). The manual trigger
    // only arms the void when this is set — no default-shape fallback for undeclared vibes.
    state->void_declared = engine->pulsar_void_data[7].load(std::memory_order_relaxed) > 0.5f;
    void_reset(state->void_state);
    // PulsarState (and VoidAnomaly's default member initializer) is only constructed
    // once for the life of the engine, not per vibe load -- so a void that was
    // mid-duck when the vibe changed would otherwise leave gain_smoothed stuck below
    // 1.0 and the new vibe's first blocks would start artificially quiet. A vibe
    // switch already hard-cuts/reinitializes the rest of the audio state, so snap
    // this back to full too rather than gliding it (nothing to click against).
    state->void_state.gain_smoothed = 1.0f;

    // Storm weather voice. Seeded off base_seed like the pattern RNG, so a pinned
    // pulsar_seed renders the same rain twice. Init is also the full reset: it drops
    // any bed, pending tail or ringing clap the previous vibe left in flight, which a
    // vibe switch hard-cuts anyway. PulsarState is built once per engine, so without
    // this the storm would be the one voice that survives a vibe change.
    state->storm_voice.Init(base_seed,
                            engine->sample_rate > 0.0f ? engine->sample_rate : 48000.0f);

    // Wah Anomaly config bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=prob [1]=durMin [2]=durMax [3]=rateDivision [4]=depth [5]=resonanceQ
    // [6]=centerHz [7]=sweepOctaves [8]=wet [9]=declared flag. Loaded unconditionally,
    // but (like the void) only ARMS while an arrangement is active.
    state->wah_config.probability        = engine->pulsar_wah_data[0].load(std::memory_order_relaxed);
    state->wah_config.dur_min            = engine->pulsar_wah_data[1].load(std::memory_order_relaxed);
    state->wah_config.dur_max            = engine->pulsar_wah_data[2].load(std::memory_order_relaxed);
    state->wah_config.voice.rate_division = engine->pulsar_wah_data[3].load(std::memory_order_relaxed);
    state->wah_config.voice.depth        = engine->pulsar_wah_data[4].load(std::memory_order_relaxed);
    state->wah_config.voice.resonance_q  = engine->pulsar_wah_data[5].load(std::memory_order_relaxed);
    state->wah_config.voice.center_hz    = engine->pulsar_wah_data[6].load(std::memory_order_relaxed);
    state->wah_config.voice.sweep_octaves = engine->pulsar_wah_data[7].load(std::memory_order_relaxed);
    state->wah_config.voice.wet          = engine->pulsar_wah_data[8].load(std::memory_order_relaxed);
    // [9] is the explicit "this vibe declares the wah anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a WahAnomaly). The manual trigger only arms the wah
    // when this is set — no default-shape fallback for undeclared vibes.
    state->wah_declared = engine->pulsar_wah_data[9].load(std::memory_order_relaxed) > 0.5f;
    // Cancel any in-flight wah window across a vibe switch. PulsarState is constructed
    // once for the life of the engine, so a window left mid-sweep would keep filtering
    // the NEW vibe's tracks through the OLD mask — and a track that was a takeover under
    // the old vibe may have no lick wah under the new one, which would leave the lerp's
    // "from" params unrelated to the running filter. A vibe switch already hard-cuts
    // audio, so drop the window rather than gliding it. Cancelling here is also what lets
    // the insert recompute takeover-vs-fresh per block instead of snapshotting it:
    // lick_wah_mask / lick_wah_declared only ever change in this function.
    state->anomaly_wah_samples_left  = 0;
    state->anomaly_wah_samples_total = 0;
    state->anomaly_wah_mask          = 0;
    for (int t = 0; t < kNumPulsarTracks; t++) state->anomaly_wah_voice[t].Init();

    // Crossfade Anomaly config bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=prob [1]=durMin [2]=durMax [3]=depth [4]=declared flag. Loaded unconditionally,
    // but (like the void/wah) only ARMS while an arrangement is active.
    state->crossfade_config.probability = engine->pulsar_crossfade_data[0].load(std::memory_order_relaxed);
    state->crossfade_config.dur_min     = engine->pulsar_crossfade_data[1].load(std::memory_order_relaxed);
    state->crossfade_config.dur_max     = engine->pulsar_crossfade_data[2].load(std::memory_order_relaxed);
    state->crossfade_config.depth       = engine->pulsar_crossfade_data[3].load(std::memory_order_relaxed);
    // [4] is the explicit "this vibe declares the crossfade anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a CrossfadeAnomaly). The manual trigger only arms the
    // crossfade when this is set — no default-shape fallback for undeclared vibes.
    state->crossfade_declared = engine->pulsar_crossfade_data[4].load(std::memory_order_relaxed) > 0.5f;

    // Cut Anomaly config bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=prob [1]=durMin [2]=durMax [3]=gateRate [4]=duty [5]=depth [6]=declared flag.
    // Loaded unconditionally, but (like the void/wah/crossfade) only ARMS while an
    // arrangement is active.
    state->cut_config.probability = engine->pulsar_cut_data[0].load(std::memory_order_relaxed);
    state->cut_config.dur_min     = engine->pulsar_cut_data[1].load(std::memory_order_relaxed);
    state->cut_config.dur_max     = engine->pulsar_cut_data[2].load(std::memory_order_relaxed);
    state->cut_config.gate_rate   = engine->pulsar_cut_data[3].load(std::memory_order_relaxed);
    state->cut_config.duty        = engine->pulsar_cut_data[4].load(std::memory_order_relaxed);
    state->cut_config.depth       = engine->pulsar_cut_data[5].load(std::memory_order_relaxed);
    // [6] is the explicit "this vibe declares the cut anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a CutAnomaly). The manual trigger only arms the
    // cut when this is set — no default-shape fallback for undeclared vibes.
    state->cut_declared = engine->pulsar_cut_data[6].load(std::memory_order_relaxed) > 0.5f;

    // Swell Anomaly config bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=prob [1]=durMin [2]=durMax [3]=startLevel [4]=peakLevel [5]=declared flag.
    // Loaded unconditionally, but (like the void/wah/crossfade/cut) only ARMS while an
    // arrangement is active. peakLevel may intentionally exceed 1.0 — not clamped.
    state->swell_config.probability  = engine->pulsar_swell_data[0].load(std::memory_order_relaxed);
    state->swell_config.dur_min      = engine->pulsar_swell_data[1].load(std::memory_order_relaxed);
    state->swell_config.dur_max      = engine->pulsar_swell_data[2].load(std::memory_order_relaxed);
    state->swell_config.start_level  = engine->pulsar_swell_data[3].load(std::memory_order_relaxed);
    state->swell_config.peak_level   = engine->pulsar_swell_data[4].load(std::memory_order_relaxed);
    // [5] is the explicit "this vibe declares the swell anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a SwellAnomaly). The manual trigger only arms the
    // swell when this is set — no default-shape fallback for undeclared vibes.
    state->swell_declared = engine->pulsar_swell_data[5].load(std::memory_order_relaxed) > 0.5f;

    // Tape Anomaly config bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=prob [1]=durMin [2]=durMax [3]=declared flag. Loaded unconditionally, but
    // (like the void/wah/crossfade/cut/swell) only ARMS while an arrangement is active.
    state->tape_config.probability = engine->pulsar_tape_data[0].load(std::memory_order_relaxed);
    state->tape_config.dur_min     = engine->pulsar_tape_data[1].load(std::memory_order_relaxed);
    state->tape_config.dur_max     = engine->pulsar_tape_data[2].load(std::memory_order_relaxed);
    // [3] is the explicit "this vibe declares the tape anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a TapeAnomaly). The manual trigger only arms the
    // tape stop when this is set — no default-shape fallback for undeclared vibes.
    state->tape_declared = engine->pulsar_tape_data[3].load(std::memory_order_relaxed) > 0.5f;

    // Scratch Anomaly config bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=prob [1]=durMin [2]=durMax [3]=declared flag. Loaded unconditionally, but
    // (like the void/wah/crossfade/cut/swell/tape) only ARMS while an arrangement is active.
    state->scratch_config.probability = engine->pulsar_scratch_data[0].load(std::memory_order_relaxed);
    state->scratch_config.dur_min     = engine->pulsar_scratch_data[1].load(std::memory_order_relaxed);
    state->scratch_config.dur_max     = engine->pulsar_scratch_data[2].load(std::memory_order_relaxed);
    // [3] is the explicit "this vibe declares the scratch anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a ScratchAnomaly). The manual trigger only arms the
    // scratch when this is set — no default-shape fallback for undeclared vibes.
    state->scratch_declared = engine->pulsar_scratch_data[3].load(std::memory_order_relaxed) > 0.5f;

    // Filter Anomaly config bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=prob [1]=durMin [2]=durMax [3]=declared flag. Loaded unconditionally, but
    // (like the void/wah/crossfade/cut/swell/tape/scratch) only ARMS while an arrangement
    // is active.
    state->filter_config.probability = engine->pulsar_filter_data[0].load(std::memory_order_relaxed);
    state->filter_config.dur_min     = engine->pulsar_filter_data[1].load(std::memory_order_relaxed);
    state->filter_config.dur_max     = engine->pulsar_filter_data[2].load(std::memory_order_relaxed);
    // [3] is the explicit "this vibe declares the filter anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a FilterAnomaly). The manual trigger only arms the
    // filter when this is set — no default-shape fallback for undeclared vibes.
    state->filter_declared = engine->pulsar_filter_data[3].load(std::memory_order_relaxed) > 0.5f;

    // Storm Anomaly config bank. Order mirrors the Kotlin marshal in PulsarFeature:
    // [0]=prob [1]=durMin [2]=durMax [3]=intensity [4]=distance [5]=declared flag.
    // The only anomaly with no master effect behind it — it strikes storm_voice, which
    // the block above just re-Init'd, so the armed window has to be cleared with it.
    state->storm_config.probability = engine->pulsar_storm_data[0].load(std::memory_order_relaxed);
    state->storm_config.dur_min     = engine->pulsar_storm_data[1].load(std::memory_order_relaxed);
    state->storm_config.dur_max     = engine->pulsar_storm_data[2].load(std::memory_order_relaxed);
    state->storm_config.intensity   = engine->pulsar_storm_data[3].load(std::memory_order_relaxed);
    state->storm_config.distance    = engine->pulsar_storm_data[4].load(std::memory_order_relaxed);
    // [5] is the explicit "this vibe declares the storm anomaly" flag (Kotlin pushes 1.0
    // when Vibe.anomalies contains a StormAnomaly). The manual trigger only strikes when
    // this is set — no default-shape fallback for undeclared vibes.
    state->storm_declared = engine->pulsar_storm_data[5].load(std::memory_order_relaxed) > 0.5f;
    state->storm_floor_bars_left = 0.0f;
    state->storm_floor_rumble = 0.0f;
    state->storm_second_strike_pending = false;
    // Per-bar strikeChance stream. Derived from base_seed rather than the wall clock so a
    // pinned pulsar_seed replays the same weather, and kept apart from master_anomaly_seed
    // so authoring weather cannot shift the anomaly rolls.
    state->storm_weather_seed = base_seed ^ 0x57EA7E12u;
    if (state->storm_weather_seed == 0) state->storm_weather_seed = 0x57EA7E12u;  // xorshift needs nonzero

    // Generic per-edge transition effects. The wire sends no row count, so every slot
    // is scanned and type 0 (unauthored) rows are dropped — stopping at the first empty
    // row would silently discard everything past a gap. Staged here rather than in the
    // arrangement block above because init_section_state must already have picked the
    // opening section's outgoing edge.
    state->trans_fx_count = 0;
    for (int r = 0; r < kMaxTransFxRows; r++) {
        float fields[kTransFxRowFields];
        const int base = r * kTransFxRowFields;
        for (int f = 0; f < kTransFxRowFields; f++) {
            fields[f] = engine->pulsar_trans_fx_data[base + f].load(std::memory_order_relaxed);
        }
        TransFxRow row = trans_fx_row_from_wire(fields);
        if (row.type == TRANS_FX_NONE) continue;
        state->trans_fx[state->trans_fx_count++] = row;
    }
    state->post_flip_fx_count = 0;
    stage_transition_fx_for_planned_edge(state);

    // Per-track lick-wah insert bank. Order mirrors the Kotlin marshal in PulsarViewModel:
    // [0]=track opt-in bitmask [1]=rateDivision [2]=depth [3]=resonanceQ [4]=centerHz
    // [5]=sweepOctaves [6]=wet [7]=declared flag. NOT an anomaly — a standing per-track filter
    // applied inside the per-track accumulation loop. Reset each voice's filter + LFO phase on
    // load so a new vibe starts clean.
    // Bank layout: [0] = mask, then kLickWahFields floats per track at 1 + t * kLickWahFields
    // in orpheus::WahParams declaration order. A track only appears in the mask when Kotlin
    // resolved params for it (track override, else the vibe-wide default), so the mask alone
    // says whether the insert runs — no separate declared flag in the bank.
    state->lick_wah_mask = (uint8_t)(engine->pulsar_lick_wah_data[0].load(std::memory_order_relaxed));
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const int b = 1 + t * OrpheusEngine::kLickWahFields;
        orpheus::WahParams& p = state->lick_wah_params[t];
        p.rate_division = engine->pulsar_lick_wah_data[b + 0].load(std::memory_order_relaxed);
        p.depth         = engine->pulsar_lick_wah_data[b + 1].load(std::memory_order_relaxed);
        p.resonance_q   = engine->pulsar_lick_wah_data[b + 2].load(std::memory_order_relaxed);
        p.center_hz     = engine->pulsar_lick_wah_data[b + 3].load(std::memory_order_relaxed);
        p.sweep_octaves = engine->pulsar_lick_wah_data[b + 4].load(std::memory_order_relaxed);
        p.wet           = engine->pulsar_lick_wah_data[b + 5].load(std::memory_order_relaxed);
    }
    state->lick_wah_declared = state->lick_wah_mask != 0;
    for (int t = 0; t < kNumPulsarTracks; t++) state->lick_wah_voice[t].Init();

    // Play-scoped RNG for Master* anomaly rolls/durations. Stirred like void_seed via
    // base_seed (which already folds in the wall clock), with a distinct salt.
    state->master_anomaly_seed = base_seed ^ 0x5A17F00Du;

    // Clear any stale outro request so a request from a prior vibe does not
    // bleed into the new arrangement. Placed after arrangement loading so that
    // it always runs regardless of whether arr_active is set.
    engine->pulsar_arrangement_outro_request.store(0, std::memory_order_relaxed);
    engine->pulsar_anomaly_request.store(0, std::memory_order_relaxed);
    state->prev_anomaly_request = 0;
    state->force_lick_anomaly = false;  // per-vibe hygiene: no forced anomaly carries over

    state->current_vibe_generation = generation;
    state->last_root_note = root;
    state->last_scale_index = scale_idx;
    state->clock_accumulator = 0.0;
    state->boundary_on_load = true;  // downbeat fires at sample 0, not a step late
    state->start_hold_samples = 0;   // fresh audibility-hold budget per load
    // mutation_seed is reset earlier (before init_section_state consumes it).
    state->loop_count = 0;
    state->loops_since_reset = 0;
    // The opening section is entered without a flip, so its breathe has to be seated
    // here: bar 0 of the cycle, every envelope at the top.
    state->breathe_bar = 0;
    state->breathe_mask = 0;
    for (int t = 0; t < kNumPulsarTracks; t++) state->breathe_env[t] = 1.0f;
    std::memset(state->drunk_offsets, 0, sizeof(state->drunk_offsets));
    std::memset(state->drunk_targets, 0, sizeof(state->drunk_targets));
    state->tempo_drift = 0.0f;
    state->tempo_drift_target = 0.0f;
    state->tempo_drift_countdown = 0;

    // ── Apply the OPENING section's overrides ─────────────────────────────
    // The first section is entered through init_section_state, which fires no
    // advance_section — so everything the section-change handler does was
    // skipped for the whole intro. A section declaring macroOverrides played at
    // vibe-base macros; a declared soloMode produced no solo until some LATER
    // flip; section_total_steps still held the PREVIOUS vibe's value; and a
    // declared void anomaly could not fire during the opening section at all.
    //
    // Same gap the tension override above closes, and this closes the rest. It
    // lives down here rather than beside that one because band_solo_config and
    // void_config are not loaded until well after init_section_state runs.
    if (state->arrangement.active && state->arrangement.section_count > 0) {
        int init_sec = state->section_state.current_section;
        if (init_sec >= 0 && init_sec < state->arrangement.section_count) {
            const SectionParam& sec = state->arrangement.sections[init_sec];

            state->section_state.target_energy     = sec.macro_overrides.energy;
            state->section_state.target_complexity = sec.macro_overrides.complexity;
            state->section_state.target_space      = sec.macro_overrides.space;
            state->section_state.target_mood       = sec.macro_overrides.mood;

            // No jam_carry check: load_vibe already memset band_solo_state, so
            // there is never an in-flight jam to walk across this seam.
            if (sec.solo_mode != SoloModeId::NONE && state->has_band_solo) {
                start_section_solo(state, engine, sec);
            }

            state->section_total_steps =
                static_cast<float>(state->section_state.bars_remaining) *
                static_cast<float>(state->tracks[0].step_count);
            arm_void_auto(state->void_state, state->void_config,
                          state->section_total_steps, state->void_seed);
        }
    }

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

// Resolve this block's breathe targets for all 8 tracks (see pulsar_breathe.h).
// Committed ONCE per block: the eight tracks must agree on one envelope, and running
// it per track would resolve eight different phases.
static void resolve_breathe_block(PulsarState* state, float sample_rate) {
    state->breathe_coeff = orpheus::breathe_smoothing_coeff(sample_rate);
    const ArrangementParams& arr = state->arrangement;
    const int cur = state->section_state.current_section;
    // Same guard as the rest of the track-override family: the bank keeps a previous
    // vibe's values on the null-arrangement path, so it must not be read there.
    const bool have_section = arr.active && cur >= 0 && cur < arr.section_count;
    uint8_t mask = 0;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const int bars = have_section ? arr.sections[cur].track_breathe_bars[t] : 0;
        if (bars <= 0) {
            // Pinned to the top so the render path can skip this track outright —
            // "off" must be exactly unity, not a computed 1.0.
            state->breathe_env[t] = 1.0f;
            state->breathe_env_target[t] = 1.0f;
            state->breathe_floor[t] = 0.0f;
            state->breathe_span[t] = 0.0f;
            continue;
        }
        const SectionParam& sec = arr.sections[cur];
        mask |= static_cast<uint8_t>(1u << t);
        state->breathe_env_target[t] =
            orpheus::breathe_envelope(orpheus::breathe_phase(state->breathe_bar, bars));
        state->breathe_floor[t] = sec.track_breathe_floor[t];
        state->breathe_span[t]  = sec.track_breathe_timbre_span[t];
    }
    state->breathe_mask = mask;
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
        // Report the void glow as idle while paused/muted: this return sits above the
        // void-gain pre-pass and its viz write, so without these two lines a pause
        // mid-duck freezes the Kotlin VIBE-dropdown tint at the ring's last (ducked)
        // sample until resume. A pause is an audio gap, so snapping gain_smoothed to
        // 1.0 is inaudible -- and on resume the pre-pass slew re-glides it down to
        // the still-armed arc's target, so the snap can't click on either edge.
        // (`state` is always non-null here: the lazy-init block above allocated it.)
        state->void_state.gain_smoothed = 1.0f;
        engine->viz_rings[VIZ_PULSAR_VOID_GAIN].write(1.0f);
        // An armed wah-anomaly window deliberately FREEZES here rather than cancelling.
        // This return sits above the per-track loop that owns the once-per-block countdown,
        // so anomaly_wah_samples_left stops advancing along with everything else on the
        // timeline (playhead, section bars_remaining, the void arc). Cancelling instead
        // would single the wah out as the one anomaly whose armed duration shortens when
        // you tap stop. Resume is click-free by construction: the countdown, the LFO phase
        // and the Svf state all pick up exactly where they left off, and a pause is an
        // audio gap regardless. The old master-bus wah counted down through a pause because
        // it lived downstream of this return, not because anything wanted it to.
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

    // ── Read and smooth macros (~10ms time constant) ──
    // This runs once per BLOCK, so the exponent scales with the frames advanced
    // this call — same fix, and the same bug, as the tempo_drift slew below. The
    // old per-SAMPLE constant applied per block gave a real time constant of
    // block_period / coeff = 5.1s at 512/48kHz, ~512x the documented 10ms, and
    // one that changed with the host block size (desktop 512 vs Android vs WASM).
    // Everything downstream inherited that lag: the energy volume curves, the
    // energy > 0.6f EDM/Space engine swap, swing amount and the fire gate.
    float smooth_coeff = 1.0f - std::exp(-static_cast<float>(num_frames)
                                         / (0.01f * sample_rate));

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
    //
    // `progress` is hoisted out of the block because the storm's weather bed
    // crossfades on this same ramp, and it renders after the track loop.
    float progress = 0.0f;
    if (state->arrangement.active) {
        const SectionState& ss = state->section_state;
        bool in_transition = ss.transition_target >= 0;
        progress = ss.transition_progress;
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
    // Cap the wander at ±5% at energy=0. (The old ±15% ceiling was masked by
    // a slew bug — see below — but once the slew advances at the intended
    // rate, 15% turns low-energy outros into an audible ±wobble of many BPM.
    // 5% reads as gentle rubato instead of sloppy timekeeping.)
    float max_drift = (1.0f - energy) * 0.05f;

    state->tempo_drift_countdown -= num_frames;
    if (state->tempo_drift_countdown <= 0) {
        state->tempo_drift_target = (rand01(state->mutation_seed) - 0.5f) * 2.0f * max_drift;
        int bars = 4 + static_cast<int>(rand01(state->mutation_seed) * 4.0f);
        state->tempo_drift_countdown = static_cast<int>(samples_per_step * 16.0 * bars);
    }

    // Slew toward the target over ~32 steps. This runs once per BLOCK, so the
    // exponent must scale with the frames advanced this call — the previous
    // per-sample constant applied per block slewed ~512x slower than intended.
    float drift_coeff = 1.0f - std::exp(-static_cast<float>(num_frames)
        / std::max(static_cast<float>(samples_per_step * 32.0), 1.0f));
    state->tempo_drift += drift_coeff * (state->tempo_drift_target - state->tempo_drift);
    state->tempo_drift = std::max(-max_drift, std::min(max_drift, state->tempo_drift));

    samples_per_step *= (1.0 + static_cast<double>(state->tempo_drift));

    // Void cursor: fractional steps elapsed in the current section. Advances at the
    // sequencer rate so the end-aligned arc stays locked to the section boundary.
    double void_cursor_start = state->void_state.cursor;
    state->void_state.cursor += static_cast<double>(num_frames) / samples_per_step;

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
    // Swing: the off-beat (odd) onset is delayed by swing * 0.5 * step by
    // lengthening the on-beat (even) step; the odd step is shortened by the
    // same amount so a swung pair still totals exactly 2 * samples_per_step.
    // We track a global step parity to alternate the two thresholds.
    static constexpr int kMaxStepBoundaries = 32;
    int step_boundary_samples[kMaxStepBoundaries];
    int num_boundaries = 0;

    // Use track 0's playhead parity to determine global swing phase
    // (all tracks advance together on the same clock)
    bool step_is_odd = (state->tracks[0].playhead % 2) != 0;

    // Task B — section-exit scratch hold: while a master record-scratch is active,
    // FREEZE the pulsar clock. A frozen accumulator produces no step boundaries, so
    // every track's playhead stays put, advance_section can't run, and the incoming
    // section (e.g. the drop after the build's scratch) is held until the scratch
    // drops back to live — its first steps aren't eaten. The scratch runs downstream
    // on the master bus off its own past-audio ring buffer, so freezing pulsar is safe
    // (it doesn't starve the scratch). Inert when no scratch is ever armed.
    const bool scratch_hold = engine->master_scratch_l.is_active();

    // ── Hold the start until the downbeat can actually be heard ────────────
    // mix is a straight linear gain (output_gain = mix * kPulsarOutputGain), so
    // the mute point above is about -60dB. In MIX_GATED (Orpheus) the ViewModel
    // forces mix to 0 on every vibe load and the user dials it back up, and the
    // `mix <= 0.001f` return sits ABOVE the generation check -- so load_vibe
    // first ran, and the downbeat was spent, on the block where the knob crossed
    // 0.001. The whole point of the injected boundary was lost there.
    //
    // Holding the TIMELINE (not just the boundary) is what keeps the downbeat on
    // step 0: gating the boundary alone would let natural boundaries advance the
    // playhead underneath it, and the vibe would open mid-pattern instead.
    //
    // The hold is bounded. A mix parked between 0.001 and the floor is a visible
    // knob position, and it must not produce permanent silence -- so after
    // kStartHoldSeconds the downbeat fires anyway, still on step 0, just quietly.
    //
    // Inert in EXPLICIT mode (the DJ app), which pins mix at 1.0: the floor is
    // already cleared on the first block, so transitions keep their exact timing.
    static constexpr float kStartAudibleMix = 0.05f;   // ~-26dB
    static constexpr float kStartHoldSeconds = 0.5f;
    bool start_hold = false;
    if (state->boundary_on_load) {
        const int max_hold = static_cast<int>(kStartHoldSeconds * sample_rate);
        if (mix < kStartAudibleMix && state->start_hold_samples < max_hold) {
            state->start_hold_samples += num_frames;
            start_hold = true;
        }
    } else {
        state->start_hold_samples = 0;
    }

    // Either hold freezes the timeline: no accumulator advance, no boundaries.
    const bool timeline_hold = scratch_hold || start_hold;

    // Vibe-load boundary: fire step 0 at sample 0 of the first playing block
    // so every track resyncs onto the fresh window immediately. Parity stays
    // even (playhead is 0), so the next natural threshold is the on-beat one.
    //
    // Unlike a natural boundary this one is INJECTED: no musical time elapsed at
    // it. Everything keyed to elapsed 16ths must skip it — the chord clock and the
    // wrap detector below both do, via its index. Keying off resync_pending instead
    // would also catch the JAM_INVERTED section re-lock, where a real step DID pass.
    int load_boundary_index = -1;
    if (state->boundary_on_load && !timeline_hold &&
        num_boundaries < kMaxStepBoundaries) {
        state->boundary_on_load = false;
        load_boundary_index = num_boundaries;
        step_boundary_samples[num_boundaries++] = 0;
    }

    // Swing amount is constant across the block: the complexity macro of
    // track 0 provides the live groove amount, with the vibe's authored
    // genre swing as a floor (pulsar_genre_swing was previously loaded but
    // never consumed anywhere in the clock).
    float swing_amount = std::max(
        engine->pulsar_genre_swing.load(std::memory_order_relaxed),
        lerp_macro(complexity, state->tracks[0].macro_map.complexity_swing));
    swing_amount = std::max(0.0f, std::min(swing_amount, 0.9f));
    const double swing_shift =
        static_cast<double>(swing_amount) * 0.5 * samples_per_step;

    // The scratch freeze is hoisted out of the loop rather than gating only the
    // increment: a held accumulator still SATISFIES a threshold that shrinks
    // under it (tempo drift or a swing change both move it), so leaving the
    // comparison live let boundaries fire during the freeze — and keep firing
    // until the accumulator drained. Freezing means no advance AND no boundary.
    for (int i = 0; i < num_frames && !timeline_hold; i++) {
        state->clock_accumulator += 1.0;

        // A swung pair must total exactly 2 * samples_per_step: lengthening
        // the even (on-beat) step delays the odd (off-beat) onset, and the
        // odd step is shortened by the same amount so the next on-beat lands
        // back on the nominal grid. (The old code only lengthened — every
        // pair took 2S + swing*0.5*S, so the real tempo ran swing/4 below
        // the nominal BPM that fixed-ms delays, beat-synced LFOs, and the
        // bass/grids/stutter units follow. They all drifted off the drum
        // grid over the course of a song.)
        double threshold = step_is_odd ? (samples_per_step - swing_shift)
                                       : (samples_per_step + swing_shift);

        if (state->clock_accumulator >= threshold) {
            state->clock_accumulator -= threshold;
            step_is_odd = !step_is_odd;
            if (num_boundaries < kMaxStepBoundaries) {
                step_boundary_samples[num_boundaries++] = i;
            }
        }
    }

    // Precompute the per-sample void gain for this block (all tracks share it),
    // and the block-level note-on suppression flag. The raw target (1.0f when idle /
    // not armed, void_arc_gain(...) when armed) is slewed with a max-delta clamp
    // (gain_smoothed on VoidAnomaly) so ghost-bar steps and section-boundary resets
    // glide instead of stepping -- see pulsar_void.h. Cheap fast-path when idle AND
    // already settled at 1.0 keeps the steady-state (void never used) cost a plain
    // fill; the idle-but-not-settled case (gliding back after a reset) still slews.
    float void_gain_buf[kMaxFrames];
    {
        const VoidAnomaly& vz = state->void_state;
        bool active = vz.armed;
        const float max_step = void_gain_max_step_per_sample(sample_rate);
        float g = state->void_state.gain_smoothed;
        if (!active && std::fabs(g - 1.0f) < 1e-4f) {
            for (int i = 0; i < num_frames; i++) void_gain_buf[i] = 1.0f;
            state->void_state.gain_smoothed = 1.0f;
            state->void_state.suppress_note_ons = false;
        } else if (!active) {
            for (int i = 0; i < num_frames; i++)
                void_gain_buf[i] = g = slew_toward(g, 1.0f, max_step);
            state->void_state.gain_smoothed = g;
            state->void_state.suppress_note_ons = false;
        } else {
            double inc = 1.0 / samples_per_step;
            double cur = void_cursor_start;
            bool susp_mid = false;
            for (int i = 0; i < num_frames; i++) {
                bool s = false;
                float target = void_arc_gain(vz, static_cast<float>(cur - vz.start_step), &s);
                void_gain_buf[i] = g = slew_toward(g, target, max_step);
                if (i == num_frames / 2) susp_mid = s;
                cur += inc;
            }
            state->void_state.gain_smoothed = g;
            state->void_state.suppress_note_ons = susp_mid;
            // Disarm once the arc has fully played out (gain back to 1.0).
            if (void_cursor_start - vz.start_step >= vz.ramp_up_end)
                state->void_state.armed = false;
        }
    }

    // Publish this block's void gain to the UI viz ring (1.0 when idle/not armed).
    // Rides the same lock-free per-channel VizRing transport as the per-track
    // levels below (VIZ_PULSAR_TRACK_0+t) — the Kotlin VIBE-dropdown glow polls
    // this at the same ~60fps cadence instead of approximating off a fixed timer.
    engine->viz_rings[VIZ_PULSAR_VOID_GAIN].write(void_gain_buf[num_frames - 1]);

    // ── Per-track: advance sequencer + render voice ──
    float track_buffer[kMaxFrames];

    // Wah-anomaly envelope trajectory for this block, shared by every eligible track.
    // Unlike void_gain_buf above this CANNOT be filled before the loop: the anomaly arms
    // inside the loop at t == 0, so the trajectory is built there (right after the step
    // boundaries are consumed) and every track then reads the same samples, which is what
    // keeps their LFO phases in lockstep with the window. Left uninitialized on purpose:
    // every read is guarded by anom_wah_block_armed, so zero-filling it unconditionally
    // would be kMaxFrames of pointless stores per block on the audio thread.
    float anom_wah_env[kMaxFrames];
    bool  anom_wah_block_armed = false;

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

        // Reset per-block trigger-timing scratch and capture the pre-boundary
        // gate so the split render can keep the old note's tail intact for
        // the samples before this block's trigger.
        ts.trigger_offset = 0;
        ts.pending_retrig = false;
        ts.gate_pre_boundary = ts.voice_active;

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

            // Timbre sweep (only when not pinned). Unauthored bounds sweep inside the track's
            // OWN mood_timbre range instead of a fixed one the author never chose; morph and
            // harmonics read unauthored as "off", timbre keeps its movement.
            //
            // The default used to be a hardcoded 0.25-0.55, so a vibe that authored no window
            // was dragged there on ~70% of steps whatever its mood_timbre said. That also broke
            // the documented lock idiom: a degenerate MacroTarget(x, x) now survives.
            //
            // Both bounds must be authored — with only a low, hi stays at the sentinel and the
            // sweep runs toward -1, going dull at peak tension.
            if (!ts.pin_timbre && state->tension.evo_timbre_prob > 0.001f) {
                uint32_t rng = step_hash(ts.playhead, t + 13, state->loop_count);
                if ((rng & 0xFFFF) / 65535.0f < state->tension.evo_timbre_prob) {
                    const bool authored = state->tension.evo_timbre_low >= 0.0f
                                       && state->tension.evo_timbre_high >= 0.0f;
                    float lo = authored ? state->tension.evo_timbre_low : mm.mood_timbre.min_value;
                    float hi = authored ? state->tension.evo_timbre_high : mm.mood_timbre.max_value;
                    mod_timbre = lo + (hi - lo) * evo;
                }
            }

            // Morph sweep (only when not pinned, only if BOTH bounds are authored)
            if (!ts.pin_morph && state->tension.evo_morph_low >= 0.0f
                && state->tension.evo_morph_high >= 0.0f && state->tension.evo_morph_prob > 0.001f) {
                uint32_t rng = step_hash(ts.playhead, t + 17, state->loop_count);
                if ((rng & 0xFFFF) / 65535.0f < state->tension.evo_morph_prob) {
                    float lo = state->tension.evo_morph_low;
                    float hi = state->tension.evo_morph_high;
                    mod_morph = lo + (hi - lo) * evo;
                }
            }

            // Harmonics nudge (only when not pinned, only if BOTH bounds are authored)
            if (!ts.pin_harmonics && state->tension.evo_harm_low >= 0.0f
                && state->tension.evo_harm_high >= 0.0f && state->tension.evo_harm_prob > 0.001f) {
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

        // Track mute: zero volume but keep sequencer running. section_density_out is the
        // active section's `density = 0` override — same treatment, cleared on exit.
        bool track_muted = engine->pulsar_track_mute[t].load(std::memory_order_relaxed) != 0
                           || ts.section_density_out;
        if (track_muted) track_volume = 0.0f;

        // ── Pan gains ──
        float pan_l, pan_r;
        constant_power_pan(ts.pan, pan_l, pan_r);

        // ── Process step boundaries for this track ──
        // Advance playhead at each step boundary.
        // Determine gate state for voice rendering.
        for (int b = 0; b < num_boundaries; b++) {
            int prev_playhead = ts.playhead;
            // Tension half-lick: a FILL lead loops only its first bar while active,
            // so the opening figure repeats/jams (its tone still breathes via evolution).
            // The helper latches the loop length at each wrap so a mid-loop mode change
            // cannot strand the playhead past its old wrap point.
            // The injected vibe-load boundary consumes no musical time: it exists
            // only to seat the playhead on the new window and sound steps[0].
            const bool is_load_boundary = (b == load_boundary_index);
            pulsar_advance_playhead(ts, state->tension.half_lick);

            // Advance chord progression on track 0 step boundaries. The load
            // boundary is not an elapsed 16th, so ticking it here would put the
            // chord grid permanently one step ahead of the drum grid.
            if (t == 0 && !is_load_boundary) {
                advance_chord(state->chord_state, complexity, mood);
            }

            // Detect loop wrap (playhead wrapped to 0) — trigger mutation.
            // Same exclusion as the chord clock: this drives loop_count, section
            // advancement and every master-anomaly roll, so counting the load
            // boundary as a bar would drift them against the chord clock.
            if (ts.playhead == 0 && prev_playhead > 0 && t == 0 && !is_load_boundary) {
                mutate_patterns(state, complexity, engine);

                // ── StormAnomaly window: one bar of the drawn length has elapsed ──
                // Outside the arrangement guard below so a window can never outlive an
                // arrangement that goes inactive mid-flight, leaving the floor stuck up.
                if (state->storm_floor_bars_left > 0.0f) {
                    state->storm_floor_bars_left -= 1.0f;
                    if (state->storm_second_strike_pending) {
                        state->storm_second_strike_pending = false;
                        // Deliberately unguarded: a bar is orders of magnitude longer than
                        // the 30-120 ms window a re-trigger truncates, and the first strike's
                        // seconds-long tail still holds strike_active() true here — guarding
                        // would simply delete the second strike.
                        state->storm_voice.trigger_strike(clamp01(state->storm_config.intensity),
                                                          state->storm_config.distance);
                    }
                }

                // ── Breathe: one loop-unit of the cycle has elapsed ──
                // Before the section advance below, which zeroes it again on a flip so
                // the incoming section always starts at the top of its own cycle.
                state->breathe_bar++;

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

                    // Anomaly Engine: edge-detect the manual trigger counter and fire every
                    // anomaly this vibe DECLARES. A vibe with no declared anomaly ignores the
                    // gesture entirely (no default fallback). Registry point: future anomaly
                    // types (scratch/sweep) add their declared-check + fire here.
                    int anomaly_req = engine->pulsar_anomaly_request.load(std::memory_order_acquire);
                    if (anomaly_req != state->prev_anomaly_request) {
                        state->prev_anomaly_request = anomaly_req;
                        if (state->void_declared && !state->void_state.armed) {
                            arm_void_manual(state->void_state, state->void_config, state->void_seed);
                        }
                        // Wah Anomaly: LEAD-ONLY, armed as a per-track insert. Unlike the
                        // siblings below it never touches the master bus — filtering the
                        // summed mix wahs the drums. The eligible-track mask is resolved
                        // BEFORE the duration is drawn so an empty mask neither arms
                        // anything nor consumes the shared anomaly RNG. There is no
                        // fallback: a vibe with no eligible lead simply never fires it.
                        if (state->wah_declared && state->anomaly_wah_samples_left <= 0) {
                            uint8_t lead_mask = pulsar_wah_lead_mask(engine);
                            if (lead_mask != 0) {
                                float bars = anomaly_draw_bars(state->wah_config.dur_min,
                                                               state->wah_config.dur_max,
                                                               state->master_anomaly_seed);
                                arm_anomaly_wah(state, lead_mask,
                                                anomaly_arm_samples(bars, samples_per_step));
                            }
                        }
                        if (state->crossfade_declared && !engine->master_crossfade_l.is_active()) {
                            float bars = anomaly_draw_bars(state->crossfade_config.dur_min,
                                                           state->crossfade_config.dur_max,
                                                           state->master_anomaly_seed);
                            int samples = anomaly_arm_samples(bars, samples_per_step);
                            float depth = std::clamp(state->crossfade_config.depth, 0.0f, 1.0f);
                            engine->master_crossfade_l.arm(samples, sample_rate, depth);
                            engine->master_crossfade_r.arm(samples, sample_rate, depth);
                        }
                        if (state->cut_declared && !engine->master_cut_l.is_active()) {
                            float bars = anomaly_draw_bars(state->cut_config.dur_min,
                                                           state->cut_config.dur_max,
                                                           state->master_anomaly_seed);
                            int samples = anomaly_arm_samples(bars, samples_per_step);
                            engine->master_cut_l.arm(samples, sample_rate, state->cut_config.gate_rate,
                                                     state->cut_config.duty, state->cut_config.depth);
                            engine->master_cut_r.arm(samples, sample_rate, state->cut_config.gate_rate,
                                                     state->cut_config.duty, state->cut_config.depth);
                        }
                        if (state->swell_declared && !engine->master_swell_l.is_active()) {
                            float bars = anomaly_draw_bars(state->swell_config.dur_min,
                                                           state->swell_config.dur_max,
                                                           state->master_anomaly_seed);
                            int samples = anomaly_arm_samples(bars, samples_per_step);
                            engine->master_swell_l.arm(samples, sample_rate, state->swell_config.start_level,
                                                       state->swell_config.peak_level);
                            engine->master_swell_r.arm(samples, sample_rate, state->swell_config.start_level,
                                                       state->swell_config.peak_level);
                        }
                        if (state->tape_declared && !engine->master_tape_stop_l.is_active()) {
                            float bars = anomaly_draw_bars(state->tape_config.dur_min,
                                                           state->tape_config.dur_max,
                                                           state->master_anomaly_seed);
                            int samples = anomaly_arm_samples(bars, samples_per_step);
                            engine->master_tape_stop_l.arm(samples);
                            engine->master_tape_stop_r.arm(samples);
                        }
                        if (state->scratch_declared && !engine->master_scratch_l.is_active()) {
                            float bars = anomaly_draw_bars(state->scratch_config.dur_min,
                                                           state->scratch_config.dur_max,
                                                           state->master_anomaly_seed);
                            int samples = anomaly_arm_samples(bars, samples_per_step);
                            engine->master_scratch_l.arm(samples, sample_rate, 0);
                            engine->master_scratch_r.arm(samples, sample_rate, 0x55555555u);
                        }
                        if (state->filter_declared && !engine->master_filter_l.is_active()) {
                            float bars = anomaly_draw_bars(state->filter_config.dur_min,
                                                           state->filter_config.dur_max,
                                                           state->master_anomaly_seed);
                            int samples = anomaly_arm_samples(bars, samples_per_step);
                            engine->master_filter_l.arm(samples, sample_rate, 0);
                            engine->master_filter_r.arm(samples, sample_rate, 7);
                        }
                        // Storm Anomaly: strikes the pulsar's OWN storm voice, not a master
                        // effect. Guarded on the voice so a gesture inside a ringing strike
                        // cannot truncate the burst that is already sounding.
                        if (state->storm_declared && !state->storm_voice.strike_active()) {
                            arm_storm_anomaly(state);
                        }
                        if (state->lick_pool_count > 0 && state->lick_anomaly_index >= 0) {
                            state->force_lick_anomaly = true;   // one-shot; consumed at the next lick resolve
                        }
                    }

                    // Per-bar weather strikes: the ACTIVE section's strikeChance, rolled once
                    // a bar on its own stream so authoring weather never shifts the anomaly
                    // rolls. The roll is consumed before the guard is consulted, keeping the
                    // stream's position independent of whatever is still ringing.
                    {
                        const int wsec = state->section_state.current_section;
                        if (wsec >= 0 && wsec < state->arrangement.section_count) {
                            const SectionWeatherParam& w = state->arrangement.sections[wsec].weather;
                            if (w.strike_chance > 0.0f &&
                                pattern_rand01(state->storm_weather_seed) < w.strike_chance &&
                                !state->storm_voice.strike_active()) {
                                state->storm_voice.trigger_strike(kWeatherStrikeIntensity, w.distance);
                            }
                        }
                    }

                    // The edge the transition effects were staged for. advance_section
                    // re-plans, so it has to be read before the flip.
                    int staged_target = state->section_state.next_section_planned;
                    bool section_changed = advance_section(
                        state->section_state, state->arrangement, state->mutation_seed);

                    // Rows a previous flip carried forward (positive offsets) count down
                    // here, before the block below can hand this flip's own carries over.
                    if (state->post_flip_fx_count > 0) {
                        tick_post_flip_transition_fx(engine, state, sample_rate);
                    }

                    // Transition effects on a bar that is NOT the flip: count the staged
                    // rows down, and fire the ones whose negative offset_bars puts them
                    // ahead of their edge. Offset 0 lands on the flip and is fired inside
                    // the section-changed block below.
                    if (!section_changed && state->pending_fx_count > 0) {
                        tick_transition_fx_bar(state->pending_fx, state->pending_fx_count);
                        fire_due_transition_fx(engine, state, sample_rate);
                    }

                    if (section_changed) {
                        int cur_sec = state->section_state.current_section;
                        const SectionParam& sec = state->arrangement.sections[cur_sec];

                        // Generic transition effects: fire whatever the taken edge still has
                        // armed (offset 0 lands here; negative offsets already fired at an
                        // earlier bar), then the section just entered fires its own entry
                        // rows, and both hand their positive offsets to post_flip_fx, which
                        // the re-stage below cannot reach. This is the ONLY scratch/tape-stop/
                        // strike-at-flip mechanism now (the legacy per-section exit-scratch arm
                        // is retired — see the slot-15 comment in the unpack above). A boundary
                        // re-route (outro_triggered) means the staged edge was NOT the one
                        // taken, so its rows are dropped rather than fired on the wrong seam,
                        // carries included — the carry list is rebuilt from zero either way.
                        // Entry rows survive a re-route: the arrival is real whichever edge
                        // made it.
                        int carried_fx = 0;
                        if (cur_sec == staged_target) {
                            carried_fx = fire_and_carry_transition_fx_at_flip(
                                engine, state, sample_rate, carried_fx);
                        }
                        carried_fx = fire_and_carry_entry_transition_fx(
                            engine, state, cur_sec, sample_rate, carried_fx);
                        for (int i = carried_fx; i < kMaxPendingFx; i++) {
                            state->post_flip_fx[i] = PendingTransFx{};
                        }
                        state->post_flip_fx_count = carried_fx;
                        stage_transition_fx_for_planned_edge(state);

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

                        // --- Per-track DENSITY overrides: rebuild only what changed ---
                        // Density is consumed at pattern-generation time, so unlike volume
                        // it cannot simply be re-read per block — the section boundary is
                        // where it has to take effect. Tracks the section leaves alone
                        // (and every chordal/lick track) keep their existing pattern.
                        apply_section_densities(state, engine, &sec);

                        // --- Re-lock any track a JAM_INVERTED release left out of phase ---
                        // The inversion is scoped to the section that follows the jam, so
                        // this boundary ends it. Deferred via resync_pending because tracks
                        // 1..7 have not advanced for this step yet — assigning playhead here
                        // would land them on 1, not 0.
                        for (int rt = 0; rt < kNumPulsarTracks; rt++) {
                            PulsarTrackState& rts = state->tracks[rt];
                            if (rts.phase_inverted) {
                                rts.phase_inverted = false;
                                rts.resync_pending = true;
                            }
                        }

                        // --- Always reset the tension phase at section entry ---
                        // Cosmetic: tension_intensity is recomputed every bar from loop_count
                        // in mutate_patterns(), so this zero is observable only on the
                        // entry bar. tension_evo_smooth is actually smoothed across bars,
                        // so zeroing it here DOES reset the timbre-evolution arc.
                        state->tension_intensity  = 0.0f;
                        state->tension_evo_smooth = 0.0f;

                        // --- Breathe: restart the cycle at the top on section entry ---
                        // The envelope is reset, not just the bar, and that matters on the
                        // HANDOFF: when the incoming section breathes too the render path
                        // keeps running, so without this the new cycle would glide up from
                        // the outgoing section's sunk value instead of snapping to its own
                        // top. (A section that drops its breathe clears the mask and skips
                        // the path entirely, so it is clean either way.) The one-pole
                        // smooths bar steps INSIDE a cycle; it must never cross the seam.
                        state->breathe_bar = 0;
                        for (int bt = 0; bt < kNumPulsarTracks; bt++)
                            state->breathe_env[bt] = 1.0f;

                        // Start solo: new SoloMode system > band system > legacy.
                        // Section.jamCarry: when a band solo is already in flight
                        // and the incoming section keeps soloing, skip the reset —
                        // same lead, same roles, same live lick and phrase memory,
                        // continuing under the incoming section's solo params
                        // (every per-bar consumer reads the CURRENT SectionParam,
                        // so mode/mutation switch over automatically).
                        // Exception: a carry INTO a JAM section additionally requires
                        // the carried lead to pass member_can_lead_solo. should_drum_lead
                        // (LICK_BUILDER-only) can leave the always-active Drummer as
                        // lead_member when a vamp's bars expire; JAM's render block needs
                        // the lead's first MELODIC track, gets -1 for a kit-only Drummer,
                        // and generates nothing while the band stays SUPPORT-ducked. A
                        // carried LICK_BUILDER lead is unaffected — drum leads are
                        // legitimate there.
                        bool jam_carried = sec.jam_carry &&
                                           state->band_solo_state.active &&
                                           sec.solo_mode != SoloModeId::NONE &&
                                           state->has_band_solo &&
                                           (sec.solo_mode != SoloModeId::JAM ||
                                            member_can_lead_solo(state->band_solo_config,
                                                                 state->band_solo_state.lead_member,
                                                                 state->tracks, kNumPulsarTracks));
                        if (jam_carried) {
                            // Intentionally nothing: the jam walks across the seam.
                        } else if (sec.solo_mode != SoloModeId::NONE && state->has_band_solo) {
                            start_section_solo(state, engine, sec);
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

                        // Void Anomaly: snapshot section length (in steps) and roll
                        // the auto trigger, end-aligned to the boundary.
                        void_reset(state->void_state);
                        state->section_total_steps =
                            static_cast<float>(state->section_state.bars_remaining) *
                            static_cast<float>(state->tracks[0].step_count);
                        arm_void_auto(state->void_state, state->void_config,
                                      state->section_total_steps, state->void_seed);

                        // Wah Anomaly: roll the auto trigger at the section boundary.
                        // LEAD-ONLY per track, exactly as the manual dispatch above. The
                        // eligible-track mask is resolved and checked BEFORE the roll so an
                        // empty mask does not consume the probability draw either.
                        if (state->wah_declared && state->wah_config.probability > 0.0f &&
                            state->anomaly_wah_samples_left <= 0) {
                            uint8_t lead_mask = pulsar_wah_lead_mask(engine);
                            if (lead_mask != 0 &&
                                pattern_rand01(state->master_anomaly_seed) < state->wah_config.probability) {
                                float bars = anomaly_draw_bars(state->wah_config.dur_min,
                                                               state->wah_config.dur_max,
                                                               state->master_anomaly_seed);
                                arm_anomaly_wah(state, lead_mask,
                                                anomaly_arm_samples(bars, samples_per_step));
                            }
                        }

                        // Crossfade Anomaly: roll the auto trigger at the section boundary.
                        if (state->crossfade_declared && state->crossfade_config.probability > 0.0f &&
                            !engine->master_crossfade_l.is_active()) {
                            if (pattern_rand01(state->master_anomaly_seed) < state->crossfade_config.probability) {
                                float bars = anomaly_draw_bars(state->crossfade_config.dur_min,
                                                               state->crossfade_config.dur_max,
                                                               state->master_anomaly_seed);
                                int samples = anomaly_arm_samples(bars, samples_per_step);
                                float depth = std::clamp(state->crossfade_config.depth, 0.0f, 1.0f);
                                engine->master_crossfade_l.arm(samples, sample_rate, depth);
                                engine->master_crossfade_r.arm(samples, sample_rate, depth);
                            }
                        }

                        // Cut Anomaly: roll the auto trigger at the section boundary.
                        if (state->cut_declared && state->cut_config.probability > 0.0f &&
                            !engine->master_cut_l.is_active()) {
                            if (pattern_rand01(state->master_anomaly_seed) < state->cut_config.probability) {
                                float bars = anomaly_draw_bars(state->cut_config.dur_min,
                                                               state->cut_config.dur_max,
                                                               state->master_anomaly_seed);
                                int samples = anomaly_arm_samples(bars, samples_per_step);
                                engine->master_cut_l.arm(samples, sample_rate, state->cut_config.gate_rate,
                                                         state->cut_config.duty, state->cut_config.depth);
                                engine->master_cut_r.arm(samples, sample_rate, state->cut_config.gate_rate,
                                                         state->cut_config.duty, state->cut_config.depth);
                            }
                        }

                        // Swell Anomaly: roll the auto trigger at the section boundary.
                        if (state->swell_declared && state->swell_config.probability > 0.0f &&
                            !engine->master_swell_l.is_active()) {
                            if (pattern_rand01(state->master_anomaly_seed) < state->swell_config.probability) {
                                float bars = anomaly_draw_bars(state->swell_config.dur_min,
                                                               state->swell_config.dur_max,
                                                               state->master_anomaly_seed);
                                int samples = anomaly_arm_samples(bars, samples_per_step);
                                engine->master_swell_l.arm(samples, sample_rate, state->swell_config.start_level,
                                                           state->swell_config.peak_level);
                                engine->master_swell_r.arm(samples, sample_rate, state->swell_config.start_level,
                                                           state->swell_config.peak_level);
                            }
                        }

                        // Tape Anomaly: roll the auto trigger at the section boundary.
                        if (state->tape_declared && state->tape_config.probability > 0.0f &&
                            !engine->master_tape_stop_l.is_active()) {
                            if (pattern_rand01(state->master_anomaly_seed) < state->tape_config.probability) {
                                float bars = anomaly_draw_bars(state->tape_config.dur_min,
                                                               state->tape_config.dur_max,
                                                               state->master_anomaly_seed);
                                int samples = anomaly_arm_samples(bars, samples_per_step);
                                engine->master_tape_stop_l.arm(samples);
                                engine->master_tape_stop_r.arm(samples);
                            }
                        }

                        // Scratch Anomaly: roll the auto trigger at the section boundary.
                        if (state->scratch_declared && state->scratch_config.probability > 0.0f &&
                            !engine->master_scratch_l.is_active()) {
                            if (pattern_rand01(state->master_anomaly_seed) < state->scratch_config.probability) {
                                float bars = anomaly_draw_bars(state->scratch_config.dur_min,
                                                               state->scratch_config.dur_max,
                                                               state->master_anomaly_seed);
                                int samples = anomaly_arm_samples(bars, samples_per_step);
                                engine->master_scratch_l.arm(samples, sample_rate, 0);
                                engine->master_scratch_r.arm(samples, sample_rate, 0x55555555u);
                            }
                        }

                        // Filter Anomaly: roll the auto trigger at the section boundary.
                        if (state->filter_declared && state->filter_config.probability > 0.0f &&
                            !engine->master_filter_l.is_active()) {
                            if (pattern_rand01(state->master_anomaly_seed) < state->filter_config.probability) {
                                float bars = anomaly_draw_bars(state->filter_config.dur_min,
                                                               state->filter_config.dur_max,
                                                               state->master_anomaly_seed);
                                int samples = anomaly_arm_samples(bars, samples_per_step);
                                engine->master_filter_l.arm(samples, sample_rate, 0);
                                engine->master_filter_r.arm(samples, sample_rate, 7);
                            }
                        }

                        // Storm Anomaly: roll the auto trigger at the section boundary.
                        // Guarded on the storm voice rather than a master effect — it is
                        // the pulsar's own voice, and a strike is still ringing for seconds.
                        if (state->storm_declared && state->storm_config.probability > 0.0f &&
                            !state->storm_voice.strike_active()) {
                            if (pattern_rand01(state->master_anomaly_seed) < state->storm_config.probability) {
                                arm_storm_anomaly(state);
                            }
                        }
                    }

                    // ── Fire Sky .5f: per-section rotation + rare anomaly override ──
                    // lick_resolve_desired re-rolls rotation on section change, rolls the
                    // anomaly every statement (inert until a vibe sets anomaly_index >= 0),
                    // and returns the winning bank slot. force_lick_anomaly (Anomaly Engine
                    // manual trigger) is a one-shot override consumed here regardless of
                    // whether it actually changed the slot. Swap + re-render only on change.
                    if (state->lick_pool_count > 0) {
                        int desired = lick_resolve_desired(
                            state->lick_select_seed, section_changed, state->lick_pool_count,
                            state->lick_anomaly_index, state->lick_anomaly_chance,
                            state->active_rotation_index, state->force_lick_anomaly);
                        state->force_lick_anomaly = false;
                        if (desired != state->current_lick_index) {
                            apply_pool_lick(state, desired);
                            state->current_lick_index = desired;
                            regenerate_lick_tracks(state, engine, state->seed_counter * 2654435761u);
                        }
                    }

                    // Advance solo within current section
                    if (state->band_solo_state.active) {
                        int cur_sec = state->section_state.current_section;
                        const SectionParam& sec_adv = state->arrangement.sections[cur_sec];
                        advance_band_solo(state->band_solo_state, state->band_solo_config,
                                          sec_adv, state->tracks, state->mutation_seed,
                                          kNumPulsarTracks, state->track_ducking);
                        // Slew smoothed volume/density mods toward freshly-written targets
                        // so solo handoffs resolve over a few bars (2-3 at typical mod
                        // magnitudes, via kSoloModSlew) instead of snapping.
                        for (int st = 0; st < kNumPulsarTracks; st++) {
                            state->tracks[st].solo_volume_mod_current =
                                slew_toward(state->tracks[st].solo_volume_mod_current,
                                            state->tracks[st].solo_volume_mod, kSoloModSlew);
                            state->tracks[st].solo_density_mod_current =
                                slew_toward(state->tracks[st].solo_density_mod_current,
                                            state->tracks[st].solo_density_mod, kSoloModSlew);
                        }
                        // Set when the bass leads a JAM off its own authored hook, so the
                        // articulation pass below leaves the written velocities alone.
                        bool bass_solo_authored = false;
                        // JAM free improv: generate the lead's chord-anchored line + carryover.
                        // Mutually exclusive with the LickBuilder live-lick block below:
                        // only one of JAM or LICK_BUILDER writes the lead's steps per bar.
                        if (sec_adv.solo_mode == SoloModeId::JAM &&
                            state->band_solo_state.lead_member >= 0) {
                            int lead = state->band_solo_state.lead_member;
                            // Carryover: on a handoff, bias the NEW lead's interval weights
                            // toward the outgoing phrase, then start a fresh phrase.
                            const BandMemberParam& lm = state->band_solo_config.members[lead];
                            int mt = -1;
                            for (int ti = 0; ti < lm.track_count; ti++) {
                                int rt = lm.tracks[ti];
                                if (rt >= 0 && rt < kNumPulsarTracks &&
                                    state->tracks[rt].role == TrackRole::MELODIC) { mt = rt; break; }
                            }
                            if (mt >= 0) {
                                if (state->band_solo_state.just_handed_off) {
                                    // The section's authored Jam lickInfluence IS the carryover:
                                    // 0 = the incoming soloist ignores the outgoing phrase,
                                    // 1 = inherits it as strongly as the blend allows.
                                    improvisers_handoff(state->band_solo_state,
                                                        clamp01(sec_adv.solo_lick_influence),
                                                        state->track_solo_behavior[mt],
                                                        state->mutation_seed);
                                    state->band_solo_state.phrase_cursor = 0;
                                    std::memset(state->band_solo_state.last_phrase, -1,
                                                sizeof(state->band_solo_state.last_phrase));
                                }
                                const PulsarScale& sc = kPulsarScales[live_scale];
                                int cd = state->chord_state.progression[state->chord_state.chord_index];
                                PulsarTrackState& lts = state->tracks[mt];
                                // Register for the GENERATED notes: the track's authored
                                // range wins, then the genre's; fit_jam_note_to_range's
                                // 24..96 backstop covers a vibe that authors neither.
                                int jam_nr_low  = engine->pulsar_track_note_range_low[mt].load(
                                    std::memory_order_relaxed);
                                int jam_nr_high = engine->pulsar_track_note_range_high[mt].load(
                                    std::memory_order_relaxed);
                                if (jam_nr_low <= 0) jam_nr_low = engine->pulsar_genre_note_range_low.load(
                                    std::memory_order_relaxed);
                                if (jam_nr_high <= 0) jam_nr_high = engine->pulsar_genre_note_range_high.load(
                                    std::memory_order_relaxed);
                                // A lick-driven lead's hook IS the vibe, so ornament it rather
                                // than overwrite it. Same (channel length + lick mode) pair that
                                // drives the LickMode::FILL render at load; mt is already MELODIC.
                                LickMode mt_lick_mode = static_cast<LickMode>(
                                    engine->pulsar_track_lick_mode[mt].load(std::memory_order_relaxed));
                                LickChannel mt_ch = track_lick_channel(state, engine, mt);
                                bool hook_driven = (mt_ch.length > 0 && mt_lick_mode != LickMode::NONE);
                                bass_solo_authored = hook_driven && (mt == kBassTrack);
                                if (hook_driven) {
                                    // Re-render the bare hook (load/déjà-vu recipe + seed) so last
                                    // bar's ornaments can't compound into the riff. GENRE range, as
                                    // the load path passed — a different one moves lick_octave_base.
                                    float mt_mut = state->in_spurt
                                        ? std::min(1.0f, mt_ch.mutation * 3.0f) : mt_ch.mutation;
                                    render_lick_into_track(
                                        lts, mt, mt_ch.lick, mt_ch.length, mt_mut,
                                        static_cast<uint8_t>(live_root), sc,
                                        state->seed_counter * 2654435761u,
                                        lts.bar_strategy, lts.step_count, mt_ch.octave,
                                        static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(
                                            std::memory_order_relaxed)),
                                        static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(
                                            std::memory_order_relaxed)),
                                        mt_ch.loop_length,
                                        engine->pulsar_track_lick_degree_offset[mt].load(
                                            std::memory_order_relaxed));
                                }
                                // Render around the lead track's current register.
                                int octave = 5;
                                for (int s = 0; s < lts.step_count; s++)
                                    if (lts.steps[s].gate) {
                                        octave = (lts.steps[s].note - static_cast<int>(live_root)) / 12;
                                        break;
                                    }
                                // Build: bars elapsed in this solo over the section's span.
                                // tension_intensity used to sit here, but that is the
                                // per-phrase LFO — it made bar 1 and bar 32 identical.
                                float jam_progress = jam_solo_progress(
                                    state->band_solo_state.bars_elapsed,
                                    state->section_state.bars_total);
                                if (hook_driven) {
                                    ornament_jam_solo_line(
                                        state->track_solo_behavior[mt], state->band_solo_state,
                                        lts.steps, lts.step_count,
                                        static_cast<int>(live_root), sc, cd, octave,
                                        state->track_solo_behavior[mt].markov_current_degree,
                                        jam_progress, jam_nr_low, jam_nr_high,
                                        state->mutation_seed);
                                } else {
                                    generate_jam_solo_line(
                                        state->track_solo_behavior[mt], state->band_solo_state,
                                        lts.steps, lts.step_count,
                                        static_cast<int>(live_root), sc, cd, octave,
                                        state->track_solo_behavior[mt].markov_current_degree,
                                        jam_progress, jam_nr_low, jam_nr_high,
                                        state->mutation_seed);
                                }
                            }
                        }
                        // Mutate live lick per bar during LickBuilder only (JAM uses
                        // generate_jam_solo_line above; these two blocks are mutually exclusive).
                        if (sec_adv.solo_mode == SoloModeId::LICK_BUILDER &&
                            state->band_solo_state.lead_member >= 0) {
                            int lead = state->band_solo_state.lead_member;
                            // Fallback: seed the live lick by snapshotting the leading
                            // member's first MELODIC track's gated steps into a starting line
                            // for LickBuilder to mutate. This guard catches two cases:
                            // (a) nothing authored in either channel (e.g. DogHouseVibe);
                            // (b) a LEAD-channel soloist in a bass-only vibe (channel is empty
                            // but solo still needs a line to build on). WARNING: do NOT add a
                            // bass_line_length guard here; that widening was reverted in 73d94bbb
                            // because it silently muted lead soloists in bass-only vibes.
                            if (!state->live_lick_active && state->lick_length == 0) {
                                // Search for a MELODIC source: prefer the lead member's
                                // tracks, then fall back to any melodic track in the band.
                                int src = -1;
                                const BandMemberParam& lm = state->band_solo_config.members[lead];
                                for (int ti = 0; ti < lm.track_count && src < 0; ti++) {
                                    int rt = lm.tracks[ti];
                                    if (rt >= 0 && rt < kNumPulsarTracks &&
                                        state->tracks[rt].role == TrackRole::MELODIC &&
                                        state->tracks[rt].step_count > 0) {
                                        // Only accept if the track has at least one gated step
                                        for (int si2 = 0; si2 < state->tracks[rt].step_count; si2++) {
                                            if (state->tracks[rt].steps[si2].gate) { src = rt; break; }
                                        }
                                    }
                                }
                                // Lead member had no gated melodic steps: scan all tracks
                                if (src < 0) {
                                    for (int rt = 0; rt < kNumPulsarTracks && src < 0; rt++) {
                                        if (state->tracks[rt].role != TrackRole::MELODIC) continue;
                                        for (int si2 = 0; si2 < state->tracks[rt].step_count; si2++) {
                                            if (state->tracks[rt].steps[si2].gate) { src = rt; break; }
                                        }
                                    }
                                }
                                if (src >= 0) {
                                    const PulsarScale& sc = kPulsarScales[live_scale];
                                    int n = synthesize_lick_from_steps(
                                        state->tracks[src].steps, state->tracks[src].step_count,
                                        static_cast<int>(live_root), sc,
                                        state->live_lick_degrees, state->live_lick_durations,
                                        state->live_lick_velocities, kMaxLickSteps);
                                    if (n > 0) {
                                        state->live_lick_length = n;
                                        state->live_lick_active = true;
                                        state->live_lick_bass_channel = false;
                                        for (int i = 0; i < n; i++)
                                            state->live_lick_base_degrees[i] = state->live_lick_degrees[i];
                                    }
                                }
                            }
                            if (state->live_lick_active) {
                            float creativity = state->band_solo_config.members[lead].creativity;
                            // MUT-4: pass the section-entry snapshot so degree drift
                            // is clamped (octave-jump idiom can't run away).
                            // Task 6: suppress octave-jump mutation on the first
                            // post-handoff bar so the incoming lick can't leap away.
                            mutate_live_lick(
                                state->live_lick_degrees, state->live_lick_durations,
                                state->live_lick_velocities, state->live_lick_length,
                                creativity * sec_adv.solo_mutation_rate,
                                state->mutation_seed,
                                state->live_lick_base_degrees,
                                /*max_degree_drift=*/14,
                                /*allow_octave_jump=*/!state->band_solo_state.just_handed_off
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
                            // Task 8: when a drum lead is active, mirror the lick's
                            // rhythm onto the drum member's tracks instead of running
                            // the melodic render loop. The live[] buffer is shared by
                            // both paths — it was already mutated above.
                            if (state->band_solo_state.drum_lead_style >= 0) {
                                render_drum_lead(
                                    state->band_solo_config,
                                    state->tracks, kNumPulsarTracks,
                                    lead,
                                    static_cast<DrumLeadStyle>(state->band_solo_state.drum_lead_style),
                                    live, nlive, creativity, state->mutation_seed);
                            } else {
                            // Reuse the already-clamped live_root/live_scale (#4) so the
                            // solo can't land in a different key than the rest of the bar
                            // via a second relaxed root/scale read.
                            uint8_t rroot = static_cast<uint8_t>(live_root);
                            const PulsarScale& rscale = kPulsarScales[live_scale];
                            uint8_t nr_low = static_cast<uint8_t>(
                                engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed));
                            uint8_t nr_high = static_cast<uint8_t>(
                                engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed));
                            // Task 6: choose the octave ONCE per soloist run (at handoff
                            // or on the first bar of a new run) and hold it stable.
                            // Re-choosing every bar caused octave drift as outgoing_last_note
                            // chased each bar's last note, accumulating register drift.
                            if (state->band_solo_state.just_handed_off ||
                                state->band_solo_state.solo_lick_octave < 0) {
                                int chosen = choose_lick_octave(
                                    nlive > 0 ? state->live_lick_degrees[0] : 0,
                                    state->band_solo_state.outgoing_last_note,
                                    static_cast<int>(rroot), rscale,
                                    static_cast<int>(nr_low), static_cast<int>(nr_high));
                                if (chosen < 0) chosen = state->live_lick_bass_channel
                                    ? state->bass_line_octave : state->lick_octave;
                                state->band_solo_state.solo_lick_octave = chosen;
                            }
                            int oct = state->band_solo_state.solo_lick_octave;
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
                                                       oct,
                                                       nr_low, nr_high,
                                                       state->live_lick_bass_channel
                                                           ? state->bass_line_loop_length
                                                           : state->lick_loop_length,
                                                       engine->pulsar_track_lick_degree_offset[rt].load(
                                                           std::memory_order_relaxed));
                            }
                            // Task 6: stash the last rendered lick note so the next
                            // handoff can reconcile the incoming lead's register.
                            if (nlive > 0) {
                                state->band_solo_state.outgoing_last_note =
                                    lick_degree_to_midi(
                                        state->live_lick_degrees[nlive - 1],
                                        static_cast<int>(rroot), rscale, oct,
                                        static_cast<int>(nr_low),
                                        static_cast<int>(nr_high));
                            }
                            } // end else (non-drum-lead melodic render)
                            } // end if (live_lick_active)
                        }
                        // Articulate the bass when it leads the solo: accents + sparse
                        // syncopated slaps. Placed AFTER the LickBuilder render block so
                        // articulation runs on the freshly-rendered lick steps (LICK_BUILDER
                        // mode) or on the Jam pattern — covering both solo modes.
                        // Scoped to track kBassTrack as soloist so no other instrument or
                        // non-solo bass is touched. A hook-driven jam lead keeps the
                        // velocities its author wrote; it gets the slaps, not the accents.
                        {
                            PulsarTrackState& bts = state->tracks[kBassTrack];
                            if (bts.is_soloist) {
                                constexpr float kBassSlapDensity = 0.35f;  // sparse-syncopated
                                articulate_bass_solo(bts.steps, bts.step_count,
                                                     kBassSlapDensity, state->mutation_seed,
                                                     bass_solo_authored);
                            }
                        }
                    }

                    // Solo-end fade-out: when solo clears, targets are zeroed by
                    // clear_solo_modifiers but the per-bar slew above no longer runs
                    // (guarded by band_solo_state.active). Slew _current toward 0
                    // here so the snap-back resolves over a few bars (2-3 at typical
                    // mod magnitudes, via kSoloModSlew) instead of snapping.
                    if (!state->band_solo_state.active) {
                        for (int st = 0; st < kNumPulsarTracks; st++) {
                            PulsarTrackState& sts = state->tracks[st];
                            if (sts.solo_volume_mod_current != 0.0f) {
                                sts.solo_volume_mod_current =
                                    slew_toward(sts.solo_volume_mod_current, 0.0f, kSoloModSlew);
                            }
                            if (sts.solo_density_mod_current != 0.0f) {
                                sts.solo_density_mod_current =
                                    slew_toward(sts.solo_density_mod_current, 0.0f, kSoloModSlew);
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
                    {
                        // Resolve through the ACTIVE section: a déjà-vu reset that read the
                        // raw base densities would silently undo the section's arrangement
                        // partway through it.
                        const int cs = state->section_state.current_section;
                        const SectionParam* cur_sec =
                            (state->arrangement.active && cs >= 0 && cs < state->arrangement.section_count)
                                ? &state->arrangement.sections[cs] : nullptr;
                        float rd[kNumPulsarTracks];
                        resolve_section_densities(engine, cur_sec, rd);
                        for (int gi = 0; gi < kNumPulsarTracks; gi++) rg.base_density[gi] = rd[gi];
                    }
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

                    for (int rt = 0; rt < kNumPulsarTracks; rt++) {
                        PulsarTrackState& rts = state->tracks[rt];
                        TrackRole r_role = static_cast<TrackRole>(engine->pulsar_track_role[rt].load(std::memory_order_relaxed));
                        bool perc = (r_role == TrackRole::PERCUSSIVE);
                        BarStrategy bs = rts.bar_strategy;

                        LickMode r_lick_mode = static_cast<LickMode>(
                            engine->pulsar_track_lick_mode[rt].load(std::memory_order_relaxed));
                        bool r_use_lick = (r_lick_mode != LickMode::NONE);
                        // Per-channel buffer + effective mutation (spurt amplifies 3x,
                        // capped at 1.0). Resolved before the branch because BOTH arms
                        // need it: the generative arm's CALL_RESPONSE renders a lick too.
                        LickChannel ch = track_lick_channel(state, engine, rt);
                        float ch_mut = state->in_spurt
                            ? std::min(1.0f, ch.mutation * 3.0f) : ch.mutation;
                        if (ch.length > 0 && r_use_lick && !perc) {
                            // Shared lick->track render (#5): honors CALL_RESPONSE,
                            // else loops the lick. Genre note range (rg) matches the
                            // load path — the old hardcoded 36/72 here shifted the
                            // octave on every déjà-vu reset (#4 note-range fix).
                            render_lick_into_track(rts, rt, ch.lick, ch.length,
                                                   ch_mut, static_cast<uint8_t>(rr), rscale,
                                                   reset_seed, bs, step_count_cfg,
                                                   ch.octave,
                                                   rg.note_range_low, rg.note_range_high,
                                                   ch.loop_length,
                                                   engine->pulsar_track_lick_degree_offset[rt].load(
                                                       std::memory_order_relaxed));
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
                                // Same channel contract as the load path: CALL_RESPONSE
                                // renders bar 2 from a lick even here, so it must be THIS
                                // track's channel, not state->lick unconditionally.
                                apply_bar_strategy(rts, rt, bs, perc, rg,
                                                   static_cast<uint8_t>(rr), rscale,
                                                   energy, complexity,
                                                   ch.lick, ch.length, ch_mut,
                                                   reset_seed ^ (rt * 13331u),
                                                   ch.octave,
                                                   ch.loop_length);
                            }
                        }
                    }
                }

            }

            if (ts.playhead >= kMaxPulsarSteps) ts.playhead = 0;
            const PulsarStep& step = ts.steps[ts.playhead];

            if (step.gate) {
                // Void Anomaly floor: mute new note-ons (sounding voices ring on).
                if (state->void_state.suppress_note_ons) {
                    ts.prev_step_gated = false;
                    ts.in_hold = false;
                    continue;
                }
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
                        // Handoff punctuation: on the bar a solo passed, a percussive
                        // track armed by advance_band_solo answers with a fill. It sheds
                        // its duck and gets its ornaments back for this bar only.
                        const bool fill_bar =
                            state->band_solo_state.just_handed_off &&
                            ts.role == TrackRole::PERCUSSIVE && ts.solo_fill_mod > 0.0f;

                        // Solo density gating (deterministic per step/track/bar)
                        // Read the smoothed _current value so duck depth crossfades at handoff.
                        float duck_mod = fill_bar
                            ? handoff_fill_duck(ts.solo_density_mod_current, ts.solo_fill_mod)
                            : ts.solo_density_mod_current;
                        if (duck_mod < 0.0f &&
                            !duck_passes(ts.playhead, t, state->loop_count, duck_mod)) {
                            ts.prev_step_gated = false;
                            ts.in_hold = false;
                            continue;
                        }

                        // Simplify during a bandmate's solo: SUPPORT members
                        // drop their ornament (low-velocity) hits and keep only
                        // the backbone. Deterministic, so the same hits vanish
                        // every bar rather than flickering.
                        if (ts.solo_simplify && !fill_bar && step.velocity < 0.45f) {
                            ts.prev_step_gated = false;
                            ts.in_hold = false;
                            continue;
                        }

                        // Probability gating: energy controls base fire probability.
                        // TEXTURE/FX tracks (5-7) at low energy bypass gating so hold
                        // chains reliably start — the pattern generator already controls
                        // density, and the energy volume curve handles presence.
                        float base_prob = energy * 0.6f + 0.4f;  // 40% at energy=0, 100% at energy=1
                        // Percussive tracks keep their backbone at any energy:
                        // randomly dropping one-drop kicks and backbeats reads
                        // as a drummer flubbing hits, not as dynamics — and
                        // low-energy outro/breakdown sections made it happen
                        // to a third of the kit's already-sparse pattern. The
                        // energy volume curve handles presence instead.
                        if (ts.role == TrackRole::PERCUSSIVE) {
                            base_prob = std::max(base_prob, 0.9f);
                        }
                        float vel_boost = step.velocity * (1.0f - base_prob) * 0.5f;
                        float fire_prob = base_prob + vel_boost;

                        // SOLO-3: a positive solo density modifier raises fire
                        // probability so a soloist fires more of its gated steps —
                        // but headroom-relative (see solo_fire_boost) so a high base
                        // energy can't saturate to 1.0 and turn the solo into a run.
                        fire_prob = solo_fire_boost(fire_prob,
                                                    ts.solo_density_mod_current,
                                                    ts.solo_fill_mod);

                        uint32_t prob_hash = step_hash(ts.playhead, t, state->loop_count);
                        float prob_roll = static_cast<float>(prob_hash & 0xFFFF) / 65535.0f;
                        // The vibe-load downbeat always fires. load_vibe zeroes both
                        // playhead and loop_count, so step_hash(0, t, 0) collapses to
                        // t * 104729 — a per-track CONSTANT, independent of seed, vibe
                        // and pattern. Its rolls (t2=0.988, t3=0.997, t4=0.977) sit above
                        // the reachable fire_prob ceiling (0.95 percussive, 0.985 at
                        // energy 0.95), so without this the bass (t3) and t2/t4 lost the
                        // downbeat on EVERY vibe load below energy 0.99 — deterministically.
                        bool fires = prob_roll < fire_prob || energy >= 0.99f || is_load_boundary;

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

                            // Apply solo volume modifier (smoothed to crossfade at handoff)
                            if (ts.solo_volume_mod_current != 0.0f) {
                                vel = clamp01(vel + ts.solo_volume_mod_current);
                            }

                            // Volume tension: scale velocity based on phrase intensity
                            float vol_tension = state->tension.volume;
                            if (vol_tension > 0.001f) {
                                float vel_scale = 1.0f - vol_tension * 0.3f * (1.0f - state->tension_intensity);
                                vel = clamp01(vel * vel_scale);
                            }
                            // Carry it to the render. Without this the three modifiers
                            // above are computed and discarded, and the voice hears the
                            // raw authored velocity.
                            ts.current_velocity = vel;

                            // Sample-accurate trigger: remember where in this
                            // block the step boundary landed. The render is
                            // split there, and the envelope rising edge
                            // (pending_retrig) is forced at that offset
                            // instead of at block start.
                            ts.trigger_offset = step_boundary_samples[b];
                            ts.pending_retrig = true;
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

        // ── Wah-anomaly envelope: per-block bookkeeping (t == 0 only) ──
        // The arm above runs inside this per-track loop at t == 0, so the trajectory is
        // built here rather than above the loop. Two things are load-bearing:
        //  1. The countdown is committed exactly ONCE per block. The loop body runs 8
        //     times, so a per-track decrement would end the window 8x early and leave the
        //     eight tracks reading eight different envelopes.
        //  2. This sits OUTSIDE the step-boundary loop, so it still runs on blocks that
        //     contain no boundary at all (the common case at 512 frames). Inside that loop
        //     the window would simply never advance.
        // The trapezoid (15% in, sustain, 15% out) matches the master-bus wah this path
        // replaces, so the sweep shape is unchanged by the reroute.
        if (t == 0) {
            int left = state->anomaly_wah_samples_left;
            const int total = state->anomaly_wah_samples_total;
            if (left > 0 && total > 0) {
                anom_wah_block_armed = true;
                int i = 0;
                for (; i < num_frames && left > 0; i++, left--)
                    anom_wah_env[i] = wah_anomaly_env(left, total);
                // Tail of a closing window. env 0 returns a takeover track to its standing
                // lick-wah params EXACTLY (from + (to - from) * 0 == from) and is a plain
                // dry pass for a fresh voice, so the exit is as click-free as the entry.
                for (; i < num_frames; i++) anom_wah_env[i] = 0.0f;
                state->anomaly_wah_samples_left = left;
                // anomaly_wah_mask is deliberately NOT cleared when left hits 0: tracks
                // 1..7 still have to render THIS block through it. samples_left <= 0 is
                // the sole armed predicate; the mask goes stale until the next arm.
            }
        }

        // ── Breathe cycle: per-block resolve (t == 0 only) ──
        // Same two constraints as the wah bookkeeping above: committed once per block,
        // and OUTSIDE the step-boundary loop so it still runs on the common 512-frame
        // block that contains no boundary at all. It sits after that loop so a bar
        // boundary (and any section flip it carried) lands in THIS block's envelope,
        // which is what makes the flip a snap rather than a block-late glide.
        if (t == 0) {
            resolve_breathe_block(state, sample_rate);
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
                accent_for_render = ts.current_velocity;
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
            // Bias follows lfo_depth, not depth_scale: depth_scale folds in the energy curve,
            // and a live energy sweep must not drift an authored static tone. The swing is
            // already depth-scaled in mod_lfo_output[], so apply_mod must not rescale it.
            if (!ts.pin_harmonics) {
                mod_harmonics = apply_mod(mod_harmonics, ts.mod_lfo_output[2], lfo_depth,
                                          mr.harmonics_min, mr.harmonics_max, mr.harmonics_safe);
            } else if (ts.harmonics_modulation > 0.001f) {
                // Pinned, but the vibe opts in to a bounded LFO swing around the
                // pinned base. Only computed here because mod_lfo_output[2] is
                // populated inside this LFO block.
                mod_harmonics = ts.harmonics + ts.mod_lfo_output[2] * ts.harmonics_modulation;
            }
            if (!ts.pin_timbre) {
                mod_timbre = apply_mod(mod_timbre, ts.mod_lfo_output[0], lfo_depth,
                                       mr.timbre_min, mr.timbre_max, true);
            }
            if (!ts.pin_morph) {
                mod_morph = apply_mod(mod_morph, ts.mod_lfo_output[1], lfo_depth,
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

        // ── Breathe: close the tone as the swell sinks ──
        // ADDED to whatever this block's writers left in mod_timbre, so the macro walk,
        // the LFO and the evolution sweep all still shape it. Deliberately above the
        // playability floor below: a deep breathe must not sink an engine into its
        // artifact zone. Deliberately NOT gated on pin_timbre either — this is an
        // authored per-section gesture, not the automatic macro modulation the pin
        // suppresses, and span defaults to 0 so it is opt-in per section.
        if ((state->breathe_mask & (1u << t)) && state->breathe_span[t] > 0.0f) {
            mod_timbre += orpheus::breathe_timbre_bias(state->breathe_env[t],
                                                       state->breathe_span[t]);
        }

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
            // its last engine (HiHat). Split at the intra-block step boundary
            // like the OrpheusVoice path below, so the kernel sees the gate
            // edge (note-on re-seed) on the true boundary sample.
            const int trig_off =
                (ts.trigger_offset > 0 && ts.trigger_offset < num_frames)
                    ? ts.trigger_offset : 0;
            if (trig_off > 0) {
                chaos::process_chaos_block(
                    ts.chaos_state,
                    ts.engine_index,
                    clamp01(mod_harmonics),
                    clamp01(mod_timbre),
                    clamp01(mod_morph),
                    note_for_render,
                    ts.gate_pre_boundary ? 1 : 0,
                    sample_rate,
                    track_buffer,
                    trig_off);
            }
            chaos::process_chaos_block(
                ts.chaos_state,
                ts.engine_index,
                clamp01(mod_harmonics),
                clamp01(mod_timbre),
                clamp01(mod_morph),
                note_for_render,
                gate_for_render,
                sample_rate,
                track_buffer + trig_off,
                num_frames - trig_off);
        } else {
            // Sub-block trigger accuracy: split the render at the intra-block
            // step-boundary offset so the voice's gate edge — and with it the
            // drum onset — lands on the true boundary sample instead of
            // snapping up to a full block (10.7ms at 512/48k) early. The
            // pre-boundary segment renders the previous note's tail with its
            // old gate state. OrpheusVoice buffers sub-24-sample remainders
            // internally, so arbitrary segment lengths are safe.
            const int trig_off =
                (ts.trigger_offset > 0 && ts.trigger_offset < num_frames)
                    ? ts.trigger_offset : 0;
            if (trig_off > 0) {
                int gate_pre = ts.gate_pre_boundary ? 1 : 0;
                if (is_self_env && ts.in_hold) gate_pre = gate_for_render;
                ts.voice.Render(
                    ts.engine_index,
                    gate_pre,
                    note_for_render,
                    clamp01(mod_harmonics),
                    clamp01(mod_timbre),
                    clamp01(mod_morph),
                    accent_for_render,
                    track_buffer,
                    trig_off,
                    static_cast<LpgMode>(active_lpg_mode),
                    ts.lpg_decay,
                    ts.lpg_colour
                );
            }
            ts.voice.Render(
                ts.engine_index,
                gate_for_render,
                note_for_render,
                clamp01(mod_harmonics),
                clamp01(mod_timbre),
                clamp01(mod_morph),
                accent_for_render,
                track_buffer + trig_off,
                num_frames - trig_off,
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

                const int drone_trig_off = ts.trigger_offset;
                for (int i = 0; i < num_frames; i++) {
                    // Before the intra-block trigger offset, the previous
                    // note's gate state applies (sub-block trigger accuracy).
                    const bool active_i = (i < drone_trig_off)
                        ? ts.gate_pre_boundary : ts.voice_active;
                    if (active_i && ts.tides_env_level < 1.0f) {
                        ts.tides_env_level += 1.0f / attack_samples;
                        if (ts.tides_env_level > 1.0f) ts.tides_env_level = 1.0f;
                    } else if (!active_i) {
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
                    const int env_trig_off = ts.trigger_offset;
                    for (int i = 0; i < num_frames; i++) {
                        // Force the retrigger's rising edge at the true step
                        // boundary sample; before it, the previous note's
                        // gate state holds.
                        if (ts.pending_retrig && i == env_trig_off) {
                            ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
                            ts.pending_retrig = false;
                        }
                        const bool gate_i = (i < env_trig_off)
                            ? ts.gate_pre_boundary : ts.voice_active;
                        env_flags[i] = stmlib::ExtractGateFlags(
                            ts.tides_prev_gate, gate_i);
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

                    const int ad_trig_off = ts.trigger_offset;
                    for (int i = 0; i < num_frames; i++) {
                        // Before the intra-block trigger offset, the previous
                        // note's gate state applies (sub-block trigger accuracy).
                        const bool active_i = (i < ad_trig_off)
                            ? ts.gate_pre_boundary : ts.voice_active;
                        if (active_i && ts.tides_env_level < 1.0f) {
                            ts.tides_env_level += 1.0f / attack_samples;
                            if (ts.tides_env_level > 1.0f) ts.tides_env_level = 1.0f;
                        } else if (!active_i) {
                            ts.tides_env_level *= decay_coeff;
                            if (ts.tides_env_level < 0.001f) ts.tides_env_level = 0.0f;
                        }
                        track_buffer[i] *= ts.tides_env_level;
                    }
                }
            }
        }

        // ── Per-track wah insert: standing lick wah, or the wah anomaly ──
        // Filters this track's fully-rendered, enveloped buffer in place ONCE per block,
        // before it accumulates into out_l/out_r and before the sends below read it (both
        // read track_buffer), so a wah'd lead feeds the sends wah'd. Tracks in neither set
        // are completely untouched: zero cost, byte-identical output.
        //
        // The branches are mutually exclusive on purpose. EXACTLY ONE resonant bandpass
        // runs per track at any instant: an anomaly landing on a track that already carries
        // the standing lick wah TAKES OVER that voice by morphing its params, it never
        // cascades a second filter (two bandpasses in series would each reject the other's
        // passband and roughly square the wet blend).
        {
            const bool has_lick_wah =
                state->lick_wah_declared && (state->lick_wah_mask & (1 << t)) != 0;
            const bool has_anomaly =
                anom_wah_block_armed && (state->anomaly_wah_mask & (1 << t)) != 0;

            if (has_anomaly && has_lick_wah) {
                // Takeover: ONE voice, one continuous LFO phase, no phase or filter reset.
                // Params lerp from the standing wah toward the anomaly by the trapezoid, so
                // env 0 is the standing wah bit-for-bit at BOTH ends of the window.
                orpheus::WahVoice& v = state->lick_wah_voice[t];
                for (int i = 0; i < num_frames; i++) {
                    const orpheus::WahParams p = wah_params_lerp(
                        state->lick_wah_params[t], state->anomaly_wah_params, anom_wah_env[i]);
                    track_buffer[i] = v.process_sample(track_buffer[i], p, p.wet, sample_rate);
                    v.advance(p, samples_per_step);
                }
            } else if (has_anomaly) {
                // Fresh anomaly voice on an otherwise dry lead: the "from" state is dry, so
                // the morph collapses to wet = env * anomaly wet, which is exactly the
                // envelope the master-bus wah applied before this moved per track.
                orpheus::WahVoice& v = state->anomaly_wah_voice[t];
                const orpheus::WahParams& p = state->anomaly_wah_params;
                for (int i = 0; i < num_frames; i++) {
                    track_buffer[i] = v.process_sample(track_buffer[i], p,
                                                       anom_wah_env[i] * p.wet, sample_rate);
                    v.advance(p, samples_per_step);
                }
            } else if (has_lick_wah) {
                // Standing bandpass wah (NOT an anomaly), unchanged.
                state->lick_wah_voice[t].process(track_buffer, num_frames,
                                                 state->lick_wah_params[t], samples_per_step,
                                                 sample_rate);
            }
        }

        // ── Breathe swell ──
        // Scales the rendered buffer rather than the mix gain below, so the dry mix,
        // the bus stem, the fx sends and the level meter cannot disagree about how far
        // the track has sunk. One-poled per SAMPLE: the phase only moves at bar
        // boundaries, and a per-block step of the full swing would click. Skipped
        // outright when this track has no breathe override — that skip, not a computed
        // 1.0, is what keeps a no-breathe vibe bit-identical.
        if (state->breathe_mask & (1u << t)) {
            float env = state->breathe_env[t];
            const float target = state->breathe_env_target[t];
            const float coeff  = state->breathe_coeff;
            const float floor_gain = state->breathe_floor[t];
            for (int i = 0; i < num_frames; i++) {
                env += coeff * (target - env);
                track_buffer[i] *= orpheus::breathe_gain(env, floor_gain);
            }
            state->breathe_env[t] = env;
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
            float s = track_buffer[i] * vol * void_gain_buf[i];
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
                float s = track_buffer[i] * vol * void_gain_buf[i];
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

    // ── Storm weather bed (the 9th voice) ────────────────────────────────
    // Not sequenced and not a bus stem: the storm renders once per block, straight
    // into the stereo mix ahead of the output HPF, so the master chain (tape stop,
    // fader, filter) warps the weather along with the band. Its bed is the active
    // section's weather crossfaded toward the staged destination's on the same
    // pre-roll ramp the macros use, so weather swells in over an entry and drains
    // over a walk-back with no extra machinery.
    //
    // set_bed runs every block even when the values did not change: it is also what
    // re-asserts the bed's distance after a strike temporarily darkened it.
    {
        storm::StormVoice& storm = state->storm_voice;
        float bed_rain = 0.0f, bed_rain_level = 0.0f, bed_rumble = 0.0f, bed_distance = 0.0f;
        if (state->arrangement.active) {
            const ArrangementParams& arr = state->arrangement;
            const SectionState& ss = state->section_state;
            const int cur = ss.current_section;
            if (cur >= 0 && cur < arr.section_count) {
                const SectionWeatherParam& w = arr.sections[cur].weather;
                bed_rain = w.rain; bed_rumble = w.rumble; bed_distance = w.distance;
                bed_rain_level = w.rain_level;
                // transition_target is cleared at the flip, so a mid-block flip lands
                // here as "no blend" and the bed snaps to the section that just began.
                const int dst = ss.transition_target;
                if (dst >= 0 && dst < arr.section_count) {
                    const SectionWeatherParam& n = arr.sections[dst].weather;
                    bed_rain       += (n.rain       - bed_rain)       * progress;
                    bed_rain_level += (n.rain_level - bed_rain_level) * progress;
                    bed_rumble     += (n.rumble     - bed_rumble)     * progress;
                    bed_distance   += (n.distance   - bed_distance)   * progress;
                }
            }
        }
        // StormAnomaly window: hold a rumble floor under whatever the section authored.
        // max(), so a heavier authored bed is never ducked by the anomaly; the anomaly's
        // own distance comes along only when its floor is the one that wins.
        if (state->storm_floor_bars_left > 0.0f && state->storm_floor_rumble > bed_rumble) {
            bed_rumble = state->storm_floor_rumble;
            bed_distance = state->storm_config.distance;
        }
        storm.set_bed(bed_rain, bed_rain_level, bed_rumble, bed_distance);

        // Exactly the condition Process() early-outs on. Checking it here skips the
        // scratch clear and the mix loop too, so a vibe with no weather pays three
        // compares per block rather than 2 KB of stores it would only add zeros from.
        // audible_rain() is rate * level: either at zero is a silent rain layer.
        const bool storm_idle = storm.settled() && storm.audible_rain() <= 0.0f
                                && bed_rumble <= 0.0f && !storm.strike_active();
        if (!storm_idle) {
            float storm_l[kMaxFrames];
            float storm_r[kMaxFrames];
            std::memset(storm_l, 0, num_frames * sizeof(float));
            std::memset(storm_r, 0, num_frames * sizeof(float));
            storm.Process(storm_l, storm_r, num_frames);
            for (int i = 0; i < num_frames; i++) {
                // The same per-sample void gain the tracks got: the storm sinks into
                // a Void with everything else. Unconditional — a branch on "no void
                // armed" costs more than the multiply it would save.
                const float sl = storm_l[i] * void_gain_buf[i];
                const float sr = storm_r[i] * void_gain_buf[i];
                out_l[i] += sl;
                out_r[i] += sr;
                engine->pulsar_delay_send_l[i]  += sl * kStormDelaySend;
                engine->pulsar_delay_send_r[i]  += sr * kStormDelaySend;
                // The same one-pole darkening every per-track send gets, set lower for a
                // wide-band bed: the dry storm above stays bright, the wash does not sizzle.
                float dark_l, dark_r;
                storm.DarkenSend(sl, sr, &dark_l, &dark_r);
                engine->pulsar_reverb_send_l[i] += dark_l * kStormReverbSend;
                engine->pulsar_reverb_send_r[i] += dark_r * kStormReverbSend;
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
