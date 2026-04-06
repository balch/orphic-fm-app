#pragma once

#include "orpheus_unit_pulsar.h"
#include <algorithm>

// ---------------------------------------------------------------------------
// Algorithmic pattern generation for Pulsar beat machine
// ---------------------------------------------------------------------------

// Helper: create a PulsarStep with raw_note = note (non-destructive re-quantization source)
inline PulsarStep make_step(uint8_t note, float velocity, bool gate, float duration) {
    return {note, note, velocity, gate, duration};
}

// xorshift32 PRNG — deterministic from seed
inline uint32_t pattern_rand(uint32_t& seed) {
    seed ^= seed << 13;
    seed ^= seed >> 17;
    seed ^= seed << 5;
    return seed;
}

// Returns 0.0-1.0 float from PRNG
inline float pattern_rand01(uint32_t& seed) {
    return static_cast<float>(pattern_rand(seed) & 0x7FFFFF) / static_cast<float>(0x7FFFFF);
}

// ---------------------------------------------------------------------------
// Rhythm pattern generators (tracks 0=KICK, 1=PERC, 2=HIHAT)
// ---------------------------------------------------------------------------

inline void generate_rhythm_pattern(
    PulsarStep* steps, int step_count, int track_index,
    const PulsarGenreProfile& genre, uint32_t& seed)
{
    const float density = genre.base_density[track_index];
    const float ghost_prob = genre.ghost_probability;

    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    switch (genre.rhythm_pattern) {
        case 0: { // Sparse
            if (track_index == 0) {
                // KICK: beat 1 only
                steps[0] = make_step(36, 0.9f, true, 0.5f);
                // Occasional second hit
                if (step_count >= 12 && pattern_rand01(seed) < density) {
                    steps[8] = make_step(36, 0.7f, true, 0.4f);
                }
            } else if (track_index == 1) {
                // PERC: very occasional
                for (int i = 0; i < step_count; i++) {
                    if (pattern_rand01(seed) < density * 0.5f) {
                        float vel = 0.4f + pattern_rand01(seed) * 0.3f;
                        steps[i] = make_step(40, vel, true, 0.15f + pattern_rand01(seed) * 0.15f);
                    }
                }
            } else {
                // HIHAT: very sparse
                for (int i = 0; i < step_count; i++) {
                    if (pattern_rand01(seed) < density * 0.3f) {
                        float vel = 0.3f + pattern_rand01(seed) * 0.3f;
                        steps[i] = make_step(42, vel, true, 0.1f + pattern_rand01(seed) * 0.1f);
                    }
                }
            }
            break;
        }
        case 1: { // Four-on-floor
            if (track_index == 0) {
                // KICK on 1/5/9/13
                for (int i = 0; i < step_count; i += 4) {
                    float vel = 0.85f + pattern_rand01(seed) * 0.15f;
                    steps[i] = make_step(36, vel, true, 0.5f);
                }
                // Ghost notes
                for (int i = 0; i < step_count; i++) {
                    if (!steps[i].gate && pattern_rand01(seed) < ghost_prob * density) {
                        steps[i] = make_step(36, 0.3f + pattern_rand01(seed) * 0.15f, true, 0.3f);
                    }
                }
            } else if (track_index == 1) {
                // PERC: backbeat on 5/13 (in 16-step = steps 4, 12)
                if (step_count >= 8) {
                    steps[4] = make_step(40, 0.8f + pattern_rand01(seed) * 0.2f, true, 0.3f);
                }
                if (step_count >= 16) {
                    steps[12] = make_step(40, 0.8f + pattern_rand01(seed) * 0.2f, true, 0.3f);
                }
                // Occasional fills
                for (int i = 0; i < step_count; i++) {
                    if (!steps[i].gate && pattern_rand01(seed) < density * 0.3f) {
                        steps[i] = make_step(40, 0.3f + pattern_rand01(seed) * 0.2f, true, 0.15f);
                    }
                }
            } else {
                // HIHAT: offbeat 8ths (steps 2,6,10,14)
                for (int i = 2; i < step_count; i += 4) {
                    float vel = 0.6f + pattern_rand01(seed) * 0.2f;
                    steps[i] = make_step(42, vel, true, 0.15f);
                }
                // Additional ghost hats
                for (int i = 0; i < step_count; i++) {
                    if (!steps[i].gate && pattern_rand01(seed) < ghost_prob * 0.5f) {
                        steps[i] = make_step(42, 0.2f + pattern_rand01(seed) * 0.15f, true, 0.1f);
                    }
                }
            }
            break;
        }
        case 2: { // Backbeat-heavy
            if (track_index == 0) {
                // KICK: 1 and 9 with ghosts
                steps[0] = make_step(36, 0.95f, true, 0.5f);
                if (step_count >= 12) {
                    steps[8] = make_step(36, 0.9f, true, 0.5f);
                }
                // Ghost kicks
                for (int i = 0; i < step_count; i++) {
                    if (!steps[i].gate && pattern_rand01(seed) < ghost_prob * density * 0.6f) {
                        steps[i] = make_step(36, 0.3f + pattern_rand01(seed) * 0.2f, true, 0.3f);
                    }
                }
            } else if (track_index == 1) {
                // PERC: hard 2+4 (steps 4, 12) with triplet fills
                if (step_count >= 8) {
                    steps[4] = make_step(40, 0.95f, true, 0.35f);
                }
                if (step_count >= 16) {
                    steps[12] = make_step(40, 0.95f, true, 0.35f);
                }
                // Triplet-feel fills
                for (int i = 0; i < step_count; i++) {
                    if (!steps[i].gate && pattern_rand01(seed) < density * 0.4f) {
                        float vel = 0.35f + pattern_rand01(seed) * 0.3f;
                        steps[i] = make_step(40, vel, true, 0.2f);
                    }
                }
            } else {
                // HIHAT: driving 8ths
                for (int i = 0; i < step_count; i += 2) {
                    float vel = (i % 4 == 0) ? 0.7f : 0.55f;
                    vel += pattern_rand01(seed) * 0.1f;
                    steps[i] = make_step(42, vel, true, 0.15f);
                }
                // Fill in some 16ths
                for (int i = 1; i < step_count; i += 2) {
                    if (pattern_rand01(seed) < density * 0.5f) {
                        steps[i] = make_step(42, 0.25f + pattern_rand01(seed) * 0.2f, true, 0.1f);
                    }
                }
            }
            break;
        }
        case 3: { // Dense 16th
            if (track_index == 0) {
                // KICK: four-on-floor + ghosts
                for (int i = 0; i < step_count; i += 4) {
                    steps[i] = make_step(36, 0.9f + pattern_rand01(seed) * 0.1f, true, 0.45f);
                }
                for (int i = 0; i < step_count; i++) {
                    if (!steps[i].gate && pattern_rand01(seed) < ghost_prob * density) {
                        steps[i] = make_step(36, 0.25f + pattern_rand01(seed) * 0.2f, true, 0.25f);
                    }
                }
            } else if (track_index == 1) {
                // PERC: dense fills
                for (int i = 0; i < step_count; i++) {
                    if (pattern_rand01(seed) < density) {
                        float vel = 0.4f + pattern_rand01(seed) * 0.5f;
                        // Accent on backbeats
                        if (i % 4 == 2) vel += 0.2f;
                        if (vel > 1.0f) vel = 1.0f;
                        steps[i] = make_step(40, vel, true, 0.15f + pattern_rand01(seed) * 0.15f);
                    }
                }
            } else {
                // HIHAT: 16ths with velocity groove
                for (int i = 0; i < step_count; i++) {
                    float vel;
                    if (i % 4 == 0)      vel = 0.8f;
                    else if (i % 4 == 2) vel = 0.6f;
                    else if (i % 2 == 0) vel = 0.5f;
                    else                  vel = 0.35f;
                    vel += pattern_rand01(seed) * 0.1f;
                    if (vel > 1.0f) vel = 1.0f;
                    // Skip some of the softest hits for variation
                    if (vel < 0.4f && pattern_rand01(seed) > density) continue;
                    steps[i] = make_step(42, vel, true, 0.1f + pattern_rand01(seed) * 0.08f);
                }
            }
            break;
        }
    }
}

// ---------------------------------------------------------------------------
// Melodic pattern generators (tracks 3=BASS, 4=KEYS)
// ---------------------------------------------------------------------------

inline void generate_melodic_pattern(
    PulsarStep* steps, int step_count, int track_index,
    const PulsarGenreProfile& genre, uint8_t root, const PulsarScale& scale,
    uint32_t& seed)
{
    const float density = genre.base_density[track_index];

    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    if (track_index == 3) {
        // BASS: root-heavy with fifths and octaves
        int bass_root = root + 36; // Low octave
        if (bass_root < genre.note_range_low) bass_root += 12;

        // Strong hit on beat 1
        steps[0] = make_step(static_cast<uint8_t>(bass_root), 0.9f, true, 0.5f + pattern_rand01(seed) * 0.2f);

        for (int i = 1; i < step_count; i++) {
            if (pattern_rand01(seed) < density) {
                // Choose root, fifth, or octave
                float r = pattern_rand01(seed);
                int note;
                if (r < 0.5f) {
                    note = bass_root; // root
                } else if (r < 0.75f) {
                    // fifth: find degree closest to 7 semitones
                    int fifth = bass_root + 7;
                    // Quantize: find nearest scale degree
                    int best = bass_root;
                    int best_dist = 99;
                    for (int d = 0; d < scale.count; d++) {
                        int candidate = bass_root + scale.degrees[d];
                        int dist = (candidate - fifth);
                        if (dist < 0) dist = -dist;
                        if (dist < best_dist) { best_dist = dist; best = candidate; }
                        // Also check octave above
                        candidate += 12;
                        dist = (candidate - fifth);
                        if (dist < 0) dist = -dist;
                        if (dist < best_dist) { best_dist = dist; best = candidate; }
                    }
                    note = best;
                } else {
                    note = bass_root + 12; // octave
                }

                // Clamp to range
                if (note < genre.note_range_low) note += 12;
                if (note > genre.note_range_high) note -= 12;

                float vel = 0.6f + pattern_rand01(seed) * 0.3f;
                float dur = 0.4f + pattern_rand01(seed) * 0.3f;
                steps[i] = make_step(static_cast<uint8_t>(note), vel, true, dur);
            }
        }
    } else {
        // KEYS (track 4): chord tones (degrees 1, 3, 5)
        int key_base = root + 48; // Mid octave
        if (key_base < genre.note_range_low) key_base += 12;

        // Pick chord tones from scale
        int chord_notes[3];
        chord_notes[0] = key_base + (scale.count > 0 ? scale.degrees[0] : 0);
        chord_notes[1] = key_base + (scale.count > 2 ? scale.degrees[2] : 4);
        chord_notes[2] = key_base + (scale.count > 4 ? scale.degrees[4] : 7);

        for (int i = 0; i < step_count; i++) {
            if (pattern_rand01(seed) < density) {
                int idx = static_cast<int>(pattern_rand01(seed) * 2.99f);
                if (idx > 2) idx = 2;
                int note = chord_notes[idx];

                // Occasional octave shift
                if (pattern_rand01(seed) < 0.2f) note += 12;
                if (pattern_rand01(seed) < 0.1f) note -= 12;

                // Clamp to range
                while (note < genre.note_range_low) note += 12;
                while (note > genre.note_range_high) note -= 12;

                float vel = 0.5f + pattern_rand01(seed) * 0.3f;
                float dur = 0.3f + pattern_rand01(seed) * 0.5f;
                steps[i] = make_step(static_cast<uint8_t>(note), vel, true, dur);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Effect pattern generators (tracks 5=PAD, 6=TEXTURE, 7=FX)
// ---------------------------------------------------------------------------

inline void generate_effect_pattern(
    PulsarStep* steps, int step_count, int track_index,
    const PulsarGenreProfile& genre, uint8_t root, const PulsarScale& scale,
    uint32_t& seed)
{
    const float density = genre.base_density[track_index];

    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    // Upper note range for effects
    int base_note = root + 60;
    if (base_note > genre.note_range_high) base_note -= 12;
    if (base_note < genre.note_range_low) base_note += 12;

    // Duration ranges by track type
    float dur_min, dur_max;
    if (track_index == 5) {
        // PAD: long sustained
        dur_min = 0.7f; dur_max = 0.95f;
    } else if (track_index == 6) {
        // TEXTURE: varied
        dur_min = 0.3f; dur_max = 0.8f;
    } else {
        // FX: medium-long
        dur_min = 0.5f; dur_max = 0.9f;
    }

    for (int i = 0; i < step_count; i++) {
        if (pattern_rand01(seed) < density) {
            // Pick a scale degree
            int degree_idx = static_cast<int>(pattern_rand01(seed) * (scale.count - 0.01f));
            if (degree_idx >= scale.count) degree_idx = scale.count - 1;
            int note = base_note + scale.degrees[degree_idx];

            // Occasional octave shift
            if (pattern_rand01(seed) < 0.15f) note += 12;
            if (pattern_rand01(seed) < 0.15f) note -= 12;

            // Clamp to range
            while (note < genre.note_range_low) note += 12;
            while (note > genre.note_range_high) note -= 12;

            float vel = 0.3f + pattern_rand01(seed) * 0.4f;
            float dur = dur_min + pattern_rand01(seed) * (dur_max - dur_min);
            steps[i] = make_step(static_cast<uint8_t>(note), vel, true, dur);
        }
    }
}

// ---------------------------------------------------------------------------
// Lick pattern generator: melodic tracks driven by AI-generated lick data
// ---------------------------------------------------------------------------

inline void generate_lick_pattern(
    PulsarStep* steps, int step_count,
    const PulsarLickStep* lick, int lick_length,
    float mutation, uint8_t root_note, const PulsarScale& scale,
    uint32_t seed)
{
    // Clear all steps first (rests by default)
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    // Walk through lick steps, converting beat-based durations to sequencer slots.
    // Each lick duration is in beats (0.5 = eighth, 1.0 = quarter, 2.0 = half).
    // Sequencer runs at 4 steps/beat (16th notes), so duration * 4 = steps occupied.
    // Gate fires on the first step; remaining steps within the duration are rests.
    int step_pos = 0;
    int lick_idx = 0;

    while (step_pos < step_count) {
        int li = lick_idx % lick_length;
        lick_idx++;
        const auto& ls = lick[li];

        float mutate_chance = pattern_rand01(seed);

        int degree = ls.scale_degree;
        float dur = ls.duration;
        float vel = ls.velocity;

        // How many sequencer steps this lick note occupies
        int slots = std::max(1, static_cast<int>(dur * 4.0f + 0.5f));

        if (degree < 0) {
            // Rest — at high mutation, might become a note
            if (mutate_chance < mutation * 0.3f) {
                degree = static_cast<int>(pattern_rand(seed) % 5);
                vel = 0.5f + pattern_rand01(seed) * 0.3f;
            } else {
                // Skip these slots (already cleared to rests)
                step_pos += slots;
                continue;
            }
        } else if (mutate_chance < mutation) {
            float intensity = mutation * pattern_rand01(seed);
            if (intensity > 0.7f) {
                degree = static_cast<int>(pattern_rand(seed) % 7);
            } else if (intensity > 0.3f) {
                int shift = static_cast<int>(pattern_rand(seed) % 3) - 1;
                degree += shift;
            }
            vel += (pattern_rand01(seed) - 0.5f) * 0.2f * mutation;
            vel = std::max(0.2f, std::min(1.0f, vel));
            // Mutate duration slightly but keep slot count stable
            dur += (pattern_rand01(seed) - 0.5f) * 0.3f * mutation;
            dur = std::max(0.1f, std::min(2.0f, dur));
        }

        // Quantize scale degree to MIDI note
        int octave = 0;
        int d = degree;
        while (d < 0) { d += scale.count; octave--; }
        while (d >= scale.count) { d -= scale.count; octave++; }

        uint8_t midi_note = static_cast<uint8_t>(
            std::max(0, std::min(127,
                static_cast<int>(root_note) + 48 + octave * 12 + scale.degrees[d])));

        // Gate duration as fraction of the note's total step span
        float gate_frac = std::min(1.0f, 0.8f / static_cast<float>(slots));

        if (step_pos < step_count) {
            steps[step_pos] = make_step(midi_note, vel, true, gate_frac);
        }
        // Remaining slots within this note's duration stay as rests
        step_pos += slots;
    }
}

// ---------------------------------------------------------------------------
// Main dispatcher: generates pattern for a track based on its index
// ---------------------------------------------------------------------------

inline void generate_track_pattern(
    PulsarTrackState& ts, int track_index,
    bool percussive,
    const PulsarGenreProfile& genre,
    uint8_t root_note, const PulsarScale& scale,
    int step_count, uint32_t seed)
{
    seed ^= static_cast<uint32_t>(track_index * 2654435761u);
    pattern_rand(seed);

    if (step_count <= 0) step_count = 16;
    if (step_count > kMaxPulsarSteps) step_count = kMaxPulsarSteps;
    ts.step_count = step_count;

    if (percussive) {
        generate_rhythm_pattern(ts.steps, step_count, track_index, genre, seed);
    } else if (track_index <= 4) {
        generate_melodic_pattern(ts.steps, step_count, track_index, genre,
                                 root_note, scale, seed);
    } else {
        generate_effect_pattern(ts.steps, step_count, track_index, genre,
                                root_note, scale, seed);
    }
}
