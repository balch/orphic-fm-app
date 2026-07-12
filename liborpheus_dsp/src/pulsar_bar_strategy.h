#pragma once

#include "pulsar_pattern_gen.h"
#include <cstring>
#include <algorithm>

// ---------------------------------------------------------------------------
// Bar strategy algorithms for Pulsar beat machine
//
// Each strategy controls how bar 2 relates to bar 1 in a 2-bar pattern.
// Bar 1 occupies steps[0..bar1_len), bar 2 occupies steps[bar1_len..bar1_len*2).
// ---------------------------------------------------------------------------

// ── REPEAT: bar 2 is an exact copy of bar 1 ────────────────────────────

inline void bar_strategy_repeat(PulsarStep* steps, int bar1_len) {
    std::memcpy(&steps[bar1_len], &steps[0], bar1_len * sizeof(PulsarStep));
}

// ── MUTATE: bar 2 is a varied copy of bar 1 ────────────────────────────

inline void bar_strategy_mutate(
    PulsarStep* steps, int bar1_len,
    int track_index, float complexity,
    uint8_t root, const PulsarScale& scale,
    uint32_t seed)
{
    // Copy bar 1 into bar 2
    std::memcpy(&steps[bar1_len], &steps[0], bar1_len * sizeof(PulsarStep));

    PulsarStep* bar2 = &steps[bar1_len];

    for (int i = 0; i < bar1_len; i++) {
        // Ghost note activation on inactive steps
        if (!bar2[i].gate) {
            if (pattern_rand01(seed) < complexity * 0.15f) {
                bar2[i].gate = true;
                bar2[i].velocity = 0.25f + pattern_rand01(seed) * 0.15f;
                bar2[i].duration = 0.15f + pattern_rand01(seed) * 0.1f;
                // Keep existing note (or 0 for percussion)
            }
            continue;
        }

        // Velocity drift on active steps
        float drift = (pattern_rand01(seed) - 0.5f) * 2.0f * 0.20f * complexity;
        bar2[i].velocity = std::max(0.1f, std::min(1.0f, bar2[i].velocity + drift));

        // Hit displacement for percussion (tracks 0-2)
        if (track_index < 3 && pattern_rand01(seed) < complexity * 0.08f) {
            int dir = (pattern_rand(seed) & 1) ? 1 : -1;
            int target = i + dir;
            if (target >= 0 && target < bar1_len && !bar2[target].gate) {
                bar2[target] = bar2[i];
                bar2[i] = make_step(0, 0.0f, false, 0.0f);
            }
        }

        // Note drift for melodic tracks (index >= 3)
        if (track_index >= 3 && pattern_rand01(seed) < complexity * 0.12f) {
            int offsets[] = {-2, -1, 1, 2};
            int idx = static_cast<int>(pattern_rand(seed) % 4);
            int new_note = static_cast<int>(bar2[i].raw_note) + offsets[idx];
            if (new_note >= 24 && new_note <= 96) {
                bar2[i].raw_note = static_cast<uint8_t>(new_note);
                bar2[i].note = static_cast<uint8_t>(
                    quantize_to_scale(new_note, root, scale));
            }
        }
    }
}

// ── FILL: mutated bar 2 with a drum fill in the last 4 steps ───────────

inline void bar_strategy_fill(
    PulsarStep* steps, int bar1_len,
    int track_index, float energy, float complexity,
    uint8_t root, const PulsarScale& scale,
    uint32_t seed,
    uint8_t note_range_low = 36, uint8_t note_range_high = 72)
{
    // Apply MUTATE as baseline
    bar_strategy_mutate(steps, bar1_len, track_index, complexity,
                        root, scale, seed);

    PulsarStep* bar2 = &steps[bar1_len];

    // Overwrite last 4 steps of bar 2 with fill
    int fill_start = bar1_len - 4;
    if (fill_start < 0) fill_start = 0;
    int fill_len = bar1_len - fill_start;

    for (int i = 0; i < fill_len; i++) {
        int si = fill_start + i;
        float vel_ramp = 0.6f * energy + (static_cast<float>(i) / 3.0f) * 0.3f * energy;
        vel_ramp = std::min(1.0f, vel_ramp);

        if (track_index == 0) {
            // Kick: syncopated — steps 0,2 active
            bool active = (i == 0 || i == 2);
            if (active) {
                bar2[si] = make_step(36, vel_ramp, true, 0.5f);
            } else {
                bar2[si] = make_step(0, 0.0f, false, 0.0f);
            }
        } else if (track_index == 1) {
            // Snare: 16th-note roll — all 4 active, ascending velocity
            bar2[si] = make_step(40, vel_ramp, true, 0.2f);
        } else if (track_index == 2) {
            // Hihat: crescendo — all active, high velocity
            bar2[si] = make_step(42, vel_ramp, true, 0.12f);
        } else {
            // Melodic/effect tracks: ascending scale degree arpeggio
            int degree = i % scale.count;
            int mid = (static_cast<int>(note_range_low) + static_cast<int>(note_range_high)) / 2;
            int base_note = mid + scale.degrees[degree];
            if (base_note > 96) base_note -= 12;
            if (base_note < 24) base_note += 12;
            uint8_t note = static_cast<uint8_t>(
                quantize_to_scale(base_note, root, scale));
            bar2[si] = make_step(note, vel_ramp, true, 0.4f);
        }
    }
}

// ── CALL & RESPONSE: lick-based melodic pattern across both bars ───────

inline void bar_strategy_call_response(
    PulsarStep* steps, int total_step_count,
    const PulsarLickStep* lick, int lick_length, float lick_mutation,
    uint8_t root, const PulsarScale& scale,
    uint32_t seed,
    int lick_octave = -1,
    uint8_t note_range_low = 36, uint8_t note_range_high = 72,
    int lick_loop_length = 0,
    int lick_degree_offset = 0)
{
    if (lick_length <= 0) return;

    // Clear all steps
    for (int i = 0; i < total_step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    int half = total_step_count / 2;

    // Compute duty cycle: how many steps of each half the lick fills.
    // lick_loop_length is in beats — convert to sequencer steps for comparison.
    float note_beats = 0.0f;
    for (int i = 0; i < lick_length; i++) note_beats += lick[i].duration;
    int note_steps = std::max(1, static_cast<int>(note_beats * 4.0f + 0.5f));
    int loop_steps = (lick_loop_length > 0)
        ? std::max(1, static_cast<int>(static_cast<float>(lick_loop_length) * 4.0f + 0.5f))
        : note_steps;

    int play_half = half;
    if (loop_steps > note_steps) {
        play_half = std::max(note_steps,
            static_cast<int>(static_cast<float>(half) *
                static_cast<float>(note_steps) / static_cast<float>(loop_steps)));
    }

    // ── Phase 1: Call ────────────────────────────────────────────────
    // Walk through lick steps converting beat durations to step slots
    // (same algorithm as generate_lick_pattern)
    {
        uint32_t call_seed = seed;
        int step_pos = 0;
        int lick_idx = 0;

        while (step_pos < play_half) {
            int li = lick_idx % lick_length;
            lick_idx++;

            const auto& ls = lick[li];

            float mutate_chance = pattern_rand01(call_seed);

            int degree = ls.scale_degree;
            float dur = ls.duration;
            float vel = ls.velocity;

            int slots = std::max(1, static_cast<int>(dur * 4.0f + 0.5f));

            if (degree < 0) {
                // Rest — at high mutation, might become a note
                if (mutate_chance < lick_mutation * 0.3f) {
                    degree = static_cast<int>(pattern_rand(call_seed) % 5);
                    vel = 0.5f + pattern_rand01(call_seed) * 0.3f;
                } else {
                    step_pos += slots;
                    continue;
                }
            } else if (mutate_chance < lick_mutation) {
                float intensity = lick_mutation * pattern_rand01(call_seed);
                if (intensity > 0.7f) {
                    degree = static_cast<int>(pattern_rand(call_seed) % 7);
                } else if (intensity > 0.3f) {
                    int shift = static_cast<int>(pattern_rand(call_seed) % 3) - 1;
                    degree += shift;
                }
                vel += (pattern_rand01(call_seed) - 0.5f) * 0.2f * lick_mutation;
                vel = std::max(0.2f, std::min(1.0f, vel));
            }

            int octave = 0;
            int d = degree + lick_degree_offset;  // per-track parallel harmony (sounding notes only)
            while (d < 0) { d += scale.count; octave--; }
            while (d >= scale.count) { d -= scale.count; octave++; }

            uint8_t midi_note = static_cast<uint8_t>(
                std::max(0, std::min(127,
                    static_cast<int>(root) + lick_octave_base(lick_octave, root, note_range_low, note_range_high) + octave * 12 + scale.degrees[d])));

            float gate_frac = std::min(1.0f, 0.8f / static_cast<float>(slots));

            if (step_pos < half) {
                steps[step_pos] = make_step(midi_note, vel, true, gate_frac);
                steps[step_pos].glide_rate = ls.glide_rate;
            }
            step_pos += slots;
        }
    }

    // ── Phase 2: Response ────────────────────────────────────────────
    // Replay lick with a transformation chosen deterministically from seed
    {
        int transform = static_cast<int>(seed % 4);
        float resp_mutation = std::min(1.0f, lick_mutation * 1.5f);
        uint32_t resp_seed = seed ^ 0xDEADBEEF;
        int step_pos = half;
        int lick_idx = 0;

        while (step_pos < half + play_half) {
            int li = lick_idx % lick_length;
            lick_idx++;

            const auto& ls = lick[li];

            float mutate_chance = pattern_rand01(resp_seed);

            int degree = ls.scale_degree;
            float dur = ls.duration;
            float vel = ls.velocity;

            int slots = std::max(1, static_cast<int>(dur * 4.0f + 0.5f));

            // Transform 3: truncate at half the lick then hold
            if (transform == 3 && lick_idx > lick_length / 2) {
                // Hold: fill remaining with last note sustained
                break;
            }

            if (degree < 0) {
                if (mutate_chance < resp_mutation * 0.3f) {
                    degree = static_cast<int>(pattern_rand(resp_seed) % 5);
                    vel = 0.5f + pattern_rand01(resp_seed) * 0.3f;
                } else {
                    step_pos += slots;
                    continue;
                }
            } else if (mutate_chance < resp_mutation) {
                float intensity = resp_mutation * pattern_rand01(resp_seed);
                if (intensity > 0.7f) {
                    degree = static_cast<int>(pattern_rand(resp_seed) % 7);
                } else if (intensity > 0.3f) {
                    int shift = static_cast<int>(pattern_rand(resp_seed) % 3) - 1;
                    degree += shift;
                }
                vel += (pattern_rand01(resp_seed) - 0.5f) * 0.2f * resp_mutation;
                vel = std::max(0.2f, std::min(1.0f, vel));
            }

            // Apply transformation
            switch (transform) {
                case 0: degree += 2; break;                    // transpose up a third
                case 1: degree = -degree; break;               // invert contour
                case 2: degree += scale.count; break;          // octave up
                case 3: break;                                 // truncate (handled above)
            }

            int octave = 0;
            int d = degree + lick_degree_offset;  // compose offset with the response transform
            while (d < 0) { d += scale.count; octave--; }
            while (d >= scale.count) { d -= scale.count; octave++; }

            uint8_t midi_note = static_cast<uint8_t>(
                std::max(0, std::min(127,
                    static_cast<int>(root) + lick_octave_base(lick_octave, root, note_range_low, note_range_high) + octave * 12 + scale.degrees[d])));

            float gate_frac = std::min(1.0f, 0.8f / static_cast<float>(slots));

            if (step_pos < total_step_count) {
                steps[step_pos] = make_step(midi_note, vel, true, gate_frac);
                steps[step_pos].glide_rate = ls.glide_rate;
            }
            step_pos += slots;
        }
    }
}

// ── INDEPENDENT: bar 2 generated from a different seed ─────────────────

inline void bar_strategy_independent(
    PulsarTrackState& ts, int bar1_len,
    int track_index, bool percussive,
    const PulsarGenreProfile& genre,
    uint8_t root, const PulsarScale& scale,
    uint32_t seed, int chord_degree = 0)
{
    // Generate bar 2 into a temp buffer using the same generator type but
    // a different seed, then copy into the bar 2 slot.
    PulsarStep temp[kMaxPulsarSteps];
    uint32_t bar2_seed = seed ^ 0x8000;

    // Scramble with track index like generate_track_pattern does
    bar2_seed ^= static_cast<uint32_t>(track_index * 2654435761u);
    pattern_rand(bar2_seed);

    if (percussive) {
        generate_rhythm_pattern(temp, bar1_len, track_index, genre, bar2_seed);
    } else if (track_index <= 4) {
        generate_melodic_pattern(temp, bar1_len, track_index, genre,
                                 root, scale, chord_degree, bar2_seed);
    } else {
        generate_effect_pattern(temp, bar1_len, track_index, genre,
                                root, scale, chord_degree, bar2_seed);
    }

    std::memcpy(&ts.steps[bar1_len], temp, bar1_len * sizeof(PulsarStep));
}

// ── Main dispatcher ────────────────────────────────────────────────────

inline void apply_bar_strategy(
    PulsarTrackState& ts, int track_index,
    BarStrategy strategy, bool percussive,
    const PulsarGenreProfile& genre,
    uint8_t root, const PulsarScale& scale,
    float energy, float complexity,
    const PulsarLickStep* lick, int lick_length, float lick_mutation,
    uint32_t seed,
    int lick_octave = -1,
    int lick_loop_length = 0,
    int lick_degree_offset = 0)
{
    int bar1_len = ts.step_count;  // bar 1 length (already generated)

    switch (strategy) {
        case BarStrategy::REPEAT:
            bar_strategy_repeat(ts.steps, bar1_len);
            break;

        case BarStrategy::MUTATE:
            bar_strategy_mutate(ts.steps, bar1_len, track_index, complexity,
                                root, scale, seed);
            break;

        case BarStrategy::FILL:
            bar_strategy_fill(ts.steps, bar1_len, track_index, energy, complexity,
                              root, scale, seed,
                              genre.note_range_low, genre.note_range_high);
            break;

        case BarStrategy::CALL_RESPONSE:
            // Replaces the ENTIRE pattern (both bars) since the lick plays
            // across the full 32 steps.
            if (lick_length > 0) {
                bar_strategy_call_response(ts.steps, bar1_len * 2,
                                           lick, lick_length, lick_mutation,
                                           root, scale, seed,
                                           lick_octave,
                                           genre.note_range_low, genre.note_range_high,
                                           lick_loop_length,
                                           lick_degree_offset);
            } else {
                // Fallback: just repeat if no lick loaded
                bar_strategy_repeat(ts.steps, bar1_len);
            }
            break;

        case BarStrategy::INDEPENDENT:
            bar_strategy_independent(ts, bar1_len, track_index, percussive,
                                     genre, root, scale, seed);
            break;
    }

    // Double the step count to cover both bars
    ts.step_count = bar1_len * 2;
}

// ── Render a lick into a melodic track, honoring CALL_RESPONSE phrasing ──
//
// Single source of truth for "lick -> track steps", shared by the déjà-vu
// pattern reset and the LickBuilder solo render (SOLO-1). CALL_RESPONSE builds a
// two-bar question/answer across the full step_count; every other bar strategy
// just loops the lick — the non-CR strategies shape *generated* patterns, not
// single-line lick renders, which is why the load and déjà-vu paths treat lick
// tracks the same way. The caller owns the lick buffer, the mutation amount, the
// seed, and the note range, so the same recipe renders either the static vibe
// lick (déjà-vu, mutation = eff_mutation) or the live, already-mutated solo lick
// (SOLO-1, mutation = 0). The per-track seed salt (^ track*7919u) lives here so
// every call site stays consistent.
inline void render_lick_into_track(
    PulsarTrackState& ts, int track_index,
    const PulsarLickStep* lick, int lick_length, float lick_mutation,
    uint8_t root, const PulsarScale& scale, uint32_t seed,
    BarStrategy bar_strategy, int step_count,
    int lick_octave,
    uint8_t note_range_low, uint8_t note_range_high,
    int lick_loop_length,
    int lick_degree_offset = 0)
{
    ts.step_count = step_count;
    if (bar_strategy == BarStrategy::CALL_RESPONSE) {
        bar_strategy_call_response(ts.steps, step_count,
                                   lick, lick_length, lick_mutation,
                                   root, scale, seed ^ (static_cast<uint32_t>(track_index) * 7919u),
                                   lick_octave, note_range_low, note_range_high,
                                   lick_loop_length,
                                   lick_degree_offset);
    } else {
        generate_lick_pattern(ts.steps, ts.step_count,
                              lick, lick_length, lick_mutation,
                              root, scale, seed ^ (static_cast<uint32_t>(track_index) * 7919u),
                              0, lick_octave, note_range_low, note_range_high,
                              lick_loop_length,
                              lick_degree_offset);
    }
}
