#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"
#include <algorithm>

// ---------------------------------------------------------------------------
// Lick-aware solo techniques for the Pulsar band solo system
//
// Each technique interprets lick data differently per instrument role:
// - LICK_RHYTHM:  converts lick durations into drum hit patterns
// - LICK_MELODY:  embellishes lick notes for pitched instruments
// - LICK_CONTOUR: maps lick shape to parameter sweeps (timbre/morph)
// - FILL_BUILDER: progressive fills for drums
// ---------------------------------------------------------------------------

// ── Living lick management ──────────────────────────────────────────

// Copy the vibe's lick into a mutable working buffer
inline void init_live_lick(
    int8_t* live_degrees, float* live_durations, float* live_velocities,
    int& live_length, bool& live_active,
    const int8_t* src_degrees, const float* src_durations, const float* src_velocities,
    int src_length
) {
    live_length = src_length;
    live_active = src_length > 0;
    for (int i = 0; i < src_length && i < 32; i++) {
        live_degrees[i] = src_degrees[i];
        live_durations[i] = src_durations[i];
        live_velocities[i] = src_velocities[i];
    }
}

// Mutate the live lick in place based on a member's creativity trait.
// Called once per bar when a LickBuilder lead is active.
// creativity: 0 = faithful, 1 = wild reinterpretation
inline void mutate_live_lick(
    int8_t* degrees, float* durations, float* velocities, int length,
    float creativity, uint32_t& seed
) {
    if (length <= 0) return;

    for (int i = 0; i < length; i++) {
        float roll = pattern_rand01(seed);

        // Note substitution: probability scales with creativity
        if (roll < creativity * 0.3f) {
            int shift = static_cast<int>(pattern_rand01(seed) * 5.0f) - 2;
            degrees[i] = static_cast<int8_t>(degrees[i] + shift);
        }

        // Duration variation
        roll = pattern_rand01(seed);
        if (roll < creativity * 0.2f) {
            float mult = 0.5f + pattern_rand01(seed) * 1.0f;  // 0.5x to 1.5x
            durations[i] = std::max(0.125f, std::min(4.0f, durations[i] * mult));
        }

        // Velocity variation
        roll = pattern_rand01(seed);
        if (roll < creativity * 0.25f) {
            float delta = (pattern_rand01(seed) - 0.5f) * 0.3f * creativity;
            velocities[i] = std::max(0.1f, std::min(1.0f, velocities[i] + delta));
        }

        // Octave jump (high creativity only)
        roll = pattern_rand01(seed);
        if (roll < creativity * 0.1f) {
            int octave = (pattern_rand01(seed) > 0.5f) ? 7 : -7;
            degrees[i] = static_cast<int8_t>(degrees[i] + octave);
        }
    }
}

// ── LICK_RHYTHM — lick durations → drum patterns ────────────────────────

inline void generate_lick_rhythm_pattern(
    PulsarStep* kick_steps,
    PulsarStep* snare_steps,
    PulsarStep* hat_steps,
    int step_count,
    const PulsarLickStep* lick, int lick_length,
    float complexity,
    MemberSoloRole role,
    uint32_t& seed
) {
    // Clear all tracks
    for (int i = 0; i < step_count; i++) {
        kick_steps[i] = make_step(0, 0.0f, false, 0.0f);
        snare_steps[i] = make_step(0, 0.0f, false, 0.0f);
        hat_steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    if (lick_length <= 0) return;

    // 1. Compute total lick duration
    float total_dur = 0.0f;
    for (int i = 0; i < lick_length; i++) {
        total_dur += lick[i].duration;
    }
    if (total_dur <= 0.0f) total_dur = 1.0f;

    bool is_leading = (role == MemberSoloRole::LEADING);

    // 2. Map each lick step to a beat position and assign to drum tracks
    float cumulative = 0.0f;
    for (int li = 0; li < lick_length; li++) {
        int pos = static_cast<int>((cumulative / total_dur) * step_count);
        if (pos >= step_count) pos = step_count - 1;
        if (pos < 0) pos = 0;

        float vel = lick[li].velocity;
        float dur = 0.3f + vel * 0.4f;

        if (vel >= 0.7f) {
            // Kick
            kick_steps[pos] = make_step(36, vel, true, dur);
            // LEADING: vel >= 0.85 also triggers snare
            if (is_leading && vel >= 0.85f) {
                snare_steps[pos] = make_step(40, vel * 0.7f, true, dur * 0.8f);
            }
        } else if (vel >= 0.4f) {
            // Snare
            snare_steps[pos] = make_step(40, vel, true, dur);
        } else {
            // Hihat
            hat_steps[pos] = make_step(42, vel + 0.2f, true, 0.15f);
        }

        cumulative += lick[li].duration;
    }

    // 3. Fill hihat gaps with probability based on complexity
    float hat_prob = 0.3f + complexity * 0.4f;
    if (is_leading) hat_prob += 0.2f;
    hat_prob = std::min(1.0f, hat_prob);

    for (int i = 0; i < step_count; i++) {
        if (!hat_steps[i].gate && pattern_rand01(seed) < hat_prob) {
            float vel = 0.25f + pattern_rand01(seed) * 0.2f;
            hat_steps[i] = make_step(42, vel, true, 0.1f);
        }
    }

    // 4. Mutation: shift hits +/-1 step with probability complexity * 0.15
    float shift_prob = complexity * 0.15f;
    for (int i = 0; i < step_count; i++) {
        if (kick_steps[i].gate && pattern_rand01(seed) < shift_prob) {
            int dir = (pattern_rand01(seed) < 0.5f) ? -1 : 1;
            int dst = i + dir;
            if (dst >= 0 && dst < step_count && !kick_steps[dst].gate) {
                kick_steps[dst] = kick_steps[i];
                kick_steps[i] = make_step(0, 0.0f, false, 0.0f);
            }
        }
        if (snare_steps[i].gate && pattern_rand01(seed) < shift_prob) {
            int dir = (pattern_rand01(seed) < 0.5f) ? -1 : 1;
            int dst = i + dir;
            if (dst >= 0 && dst < step_count && !snare_steps[dst].gate) {
                snare_steps[dst] = snare_steps[i];
                snare_steps[i] = make_step(0, 0.0f, false, 0.0f);
            }
        }
    }

    // 5. LEADING mode: add ghost snare notes
    if (is_leading) {
        float ghost_prob = complexity * 0.3f;
        for (int i = 0; i < step_count; i++) {
            if (!snare_steps[i].gate && pattern_rand01(seed) < ghost_prob) {
                float vel = 0.2f + pattern_rand01(seed) * 0.15f;
                snare_steps[i] = make_step(40, vel, true, 0.15f);
            }
        }
    }
}

// ── LICK_MELODY — embellish lick notes for pitched instruments ──────────

inline void generate_lick_melody_variation(
    PulsarStep* steps, int step_count,
    const PulsarLickStep* lick, int lick_length,
    float complexity,
    MemberSoloRole role,
    uint8_t root, const PulsarScale& scale,
    uint32_t& seed
) {
    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    if (lick_length <= 0 || scale.count <= 0) return;

    // 1. Compute total lick duration
    float total_dur = 0.0f;
    for (int i = 0; i < lick_length; i++) {
        total_dur += lick[i].duration;
    }
    if (total_dur <= 0.0f) total_dur = 1.0f;

    bool is_active = (role == MemberSoloRole::ACTIVE);

    // 2. Map lick steps to positions and place notes
    float cumulative = 0.0f;
    for (int li = 0; li < lick_length; li++) {
        int pos = static_cast<int>((cumulative / total_dur) * step_count);
        if (pos >= step_count) pos = step_count - 1;
        if (pos < 0) pos = 0;

        int degree = lick[li].scale_degree;
        float vel = lick[li].velocity;

        // 3a. Octave shift mutation
        if (pattern_rand01(seed) < complexity * 0.15f) {
            int shift = (pattern_rand01(seed) < 0.5f) ? 7 : -7;
            degree += shift;
        }

        // 3b. Rhythmic displacement mutation
        if (pattern_rand01(seed) < complexity * 0.2f) {
            int shift = (pattern_rand01(seed) < 0.5f) ? -1 : 1;
            int new_pos = pos + shift;
            if (new_pos >= 0 && new_pos < step_count) {
                pos = new_pos;
            }
        }

        // 5. ACTIVE role: softer velocity
        if (is_active) {
            vel *= 0.8f;
        }

        // 4. Convert degree to MIDI note via root + scale
        int octave = 0;
        int d = degree;
        while (d < 0) { d += scale.count; octave--; }
        while (d >= scale.count) { d -= scale.count; octave++; }

        int raw_note = static_cast<int>(root) + 48 + octave * 12 + scale.degrees[d];
        int midi_note = quantize_to_scale(raw_note, root, scale);
        midi_note = std::max(24, std::min(96, midi_note));

        float dur = 0.4f + vel * 0.3f;
        if (pos < step_count && !steps[pos].gate) {
            steps[pos] = make_step(static_cast<uint8_t>(midi_note), vel, true, dur);
        }

        // 3c. Passing tone between this note and next
        if (li + 1 < lick_length && pattern_rand01(seed) < complexity * 0.3f) {
            int next_degree = lick[li + 1].scale_degree;
            int mid_pos = pos + static_cast<int>(((lick[li].duration / total_dur) * step_count) / 2.0f);
            if (mid_pos > pos && mid_pos < step_count && !steps[mid_pos].gate) {
                int pass_degree = degree + ((next_degree > degree) ? 1 : -1);
                int pd = pass_degree;
                int p_oct = 0;
                while (pd < 0) { pd += scale.count; p_oct--; }
                while (pd >= scale.count) { pd -= scale.count; p_oct++; }

                int pass_note = static_cast<int>(root) + 48 + p_oct * 12 + scale.degrees[pd];
                pass_note = quantize_to_scale(pass_note, root, scale);
                pass_note = std::max(24, std::min(96, pass_note));

                float pass_vel = vel * 0.6f;
                if (is_active) pass_vel *= 0.8f;
                steps[mid_pos] = make_step(static_cast<uint8_t>(pass_note), pass_vel, true, 0.3f);
            }
        }

        cumulative += lick[li].duration;
    }
}

// ── LICK_CONTOUR — lick shape → parameter sweeps ───────────────────────

inline void generate_lick_contour_sweep(
    float* timbre_sweep, float* morph_sweep, int step_count,
    const PulsarLickStep* lick, int lick_length,
    float complexity,
    uint32_t& seed
) {
    if (lick_length <= 0 || step_count <= 0) {
        for (int i = 0; i < step_count; i++) {
            timbre_sweep[i] = 0.5f;
            morph_sweep[i] = 0.5f;
        }
        return;
    }

    // Compute total duration for position mapping
    float total_dur = 0.0f;
    for (int i = 0; i < lick_length; i++) {
        total_dur += lick[i].duration;
    }
    if (total_dur <= 0.0f) total_dur = 1.0f;

    // Build segment boundaries (cumulative positions in step space)
    float timbre_val = 0.5f;
    float morph_val = 0.5f;

    float cumulative = 0.0f;
    int seg_idx = 0;
    float seg_start = 0.0f;
    float seg_end = (lick[0].duration / total_dur) * step_count;

    for (int i = 0; i < step_count; i++) {
        float fi = static_cast<float>(i);

        // Advance segment if needed
        while (seg_idx < lick_length - 1 && fi >= seg_end) {
            cumulative += lick[seg_idx].duration;
            seg_idx++;
            seg_start = seg_end;
            seg_end = ((cumulative + lick[seg_idx].duration) / total_dur) * step_count;
        }

        // 2. Compute direction from current to next degree
        float direction = 0.0f;
        if (seg_idx < lick_length - 1) {
            direction = static_cast<float>(lick[seg_idx + 1].scale_degree - lick[seg_idx].scale_degree) / 7.0f;
            direction = std::max(-1.0f, std::min(1.0f, direction));
        }

        // 3. Map: rising → brighter timbre, falling → darker
        float timbre_target = 0.5f + direction * 0.3f;
        // 4. Morph goes inverse
        float morph_target = 0.5f - direction * 0.3f;

        // 5. Smooth toward target
        timbre_val += (timbre_target - timbre_val) * 0.3f;
        morph_val += (morph_target - morph_val) * 0.3f;

        // 6. Add noise scaled by complexity
        timbre_val += (pattern_rand01(seed) - 0.5f) * complexity * 0.1f;
        morph_val += (pattern_rand01(seed) - 0.5f) * complexity * 0.1f;

        timbre_sweep[i] = std::max(0.0f, std::min(1.0f, timbre_val));
        morph_sweep[i] = std::max(0.0f, std::min(1.0f, morph_val));
    }
}

// ── FILL_BUILDER — progressive fills for drums ─────────────────────────

inline void generate_fill_pattern(
    PulsarStep* steps, int step_count,
    float complexity, float solo_progress,
    uint32_t& seed
) {
    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    // 1. Density = 0.2 + solo_progress * 0.6, scaled by complexity
    float density = (0.2f + solo_progress * 0.6f) * (0.5f + complexity * 0.5f);
    density = std::min(1.0f, density);

    for (int i = 0; i < step_count; i++) {
        // 2. Probabilistic hit based on density
        if (pattern_rand01(seed) >= density) continue;

        // 3. Accent on beat positions
        float vel;
        uint8_t note;

        if (i % 4 == 0) {
            // Beat position → kick
            vel = 0.8f + pattern_rand01(seed) * 0.2f;
            note = 36;
        } else if (i % 2 == 0) {
            // Half-beat → snare
            vel = 0.6f + pattern_rand01(seed) * 0.2f;
            note = 40;
        } else {
            // Offbeat → hihat
            vel = 0.3f + pattern_rand01(seed) * 0.3f;
            note = 42;
        }

        float dur = 0.15f + pattern_rand01(seed) * 0.2f;
        steps[i] = make_step(note, vel, true, dur);
    }
}
