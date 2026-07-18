#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_rng.h"   // for pattern_rand / pattern_rand01
#include <algorithm>

// ---------------------------------------------------------------------------
// Algorithmic pattern generation for Pulsar beat machine
// ---------------------------------------------------------------------------

// ── Scale quantization (shared by pattern_gen and bar_strategy) ─────
inline int quantize_to_scale(int note, uint8_t root, const PulsarScale& scale) {
    if (scale.count >= 12) return note;
    int rel = ((note - root) % 12 + 12) % 12;
    int octave_base = note - rel;

    int best = scale.degrees[0];
    int best_dist = 12;
    for (int i = 0; i < scale.count; i++) {
        int dist = rel - scale.degrees[i];
        if (dist < 0) dist = -dist;
        if (dist > 6) dist = 12 - dist;
        if (dist < best_dist) {
            best_dist = dist;
            best = scale.degrees[i];
        }
    }

    int result = octave_base + best;
    while (result < note - 6 && result + 12 <= 96) result += 12;
    while (result > note + 6 && result - 12 >= 24) result -= 12;
    if (result < 24) result += 12;
    if (result > 96) result -= 12;
    return result;
}

// ── Chord degree to semitone offset ───────────────────────────────────
// Maps scale degree (0-6) to semitone offset using the current scale.

inline int chord_degree_to_semitones(int degree, const PulsarScale& scale) {
    degree = ((degree % 7) + 7) % 7;
    if (degree < scale.count) {
        return scale.degrees[degree];
    }
    // Fallback: approximate diatonic mapping
    constexpr int kDiatonic[7] = {0, 2, 4, 5, 7, 9, 11};
    return kDiatonic[degree];
}

// Helper: create a PulsarStep with raw_note = note (non-destructive re-quantization source)
inline PulsarStep make_step(uint8_t note, float velocity, bool gate, float duration) {
    PulsarStep s = {note, note, velocity, gate, duration};
    s.hold = false;
    s.glide_rate = -1.0f;  // sentinel: use track default unless lick overrides
    return s;
}

// pattern_rand / pattern_rand01 live in pulsar_rng.h (included above).

// ---------------------------------------------------------------------------
// Rhythm pattern generators (tracks 0=KICK, 1=PERC, 2=HIHAT)
// ---------------------------------------------------------------------------

// Helper: generate pattern for a single discrete level (0-3).
// level 0=Sparse, 1=Four-on-floor, 2=Backbeat-heavy, 3=Dense-16th
inline void generate_pattern_level(
    PulsarStep* steps, int step_count, int track_index,
    float density, float ghost_prob, int level, uint32_t& seed)
{
    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    switch (level) {
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

// Continuous rhythm density: maps rhythm_density (0.0-1.0) to a blend
// between two adjacent discrete pattern levels.
inline void generate_rhythm_pattern(
    PulsarStep* steps, int step_count, int track_index,
    const PulsarGenreProfile& genre, uint32_t& seed)
{
    const float density = genre.base_density[track_index];
    const float ghost_prob = genre.ghost_probability;

    // Map 0.0-1.0 to the 0-3 level range
    float d = genre.rhythm_density * 3.0f;
    if (d < 0.0f) d = 0.0f;
    if (d > 3.0f) d = 3.0f;

    int lo = static_cast<int>(d);
    if (lo > 2) lo = 2;  // clamp so hi=lo+1 <= 3
    int hi = lo + 1;
    float blend = d - static_cast<float>(lo);

    // At exact boundaries (or very close), use pure pattern — no blending needed
    if (blend < 0.001f || d >= 3.0f) {
        int level = (d >= 3.0f) ? 3 : lo;
        // Use a copy of seed so the caller's PRNG state advances predictably
        uint32_t level_seed = seed;
        generate_pattern_level(steps, step_count, track_index,
                               density, ghost_prob, level, level_seed);
        seed = level_seed;
        return;
    }

    // Generate both adjacent levels into temp buffers
    PulsarStep buf_lo[kMaxPulsarSteps];
    PulsarStep buf_hi[kMaxPulsarSteps];

    uint32_t seed_lo = seed;
    generate_pattern_level(buf_lo, step_count, track_index,
                           density, ghost_prob, lo, seed_lo);

    // Offset hi seed so it differs from lo (but is still deterministic)
    uint32_t seed_hi = seed ^ 0x9E3779B9u;
    generate_pattern_level(buf_hi, step_count, track_index,
                           density, ghost_prob, hi, seed_hi);

    // Advance caller's seed past both generators
    seed = seed_lo ^ seed_hi;
    pattern_rand(seed);

    // Blend per step using a fresh PRNG stream for coin flips
    uint32_t blend_seed = seed ^ 0x517CC1B7u;
    for (int i = 0; i < step_count; i++) {
        bool lo_gate = buf_lo[i].gate;
        bool hi_gate = buf_hi[i].gate;

        if (lo_gate && hi_gate) {
            // Both have hits — lerp velocity, pick note/duration from whichever
            // side has higher velocity (keeps musically stronger pattern dominant)
            float v = buf_lo[i].velocity * (1.0f - blend) + buf_hi[i].velocity * blend;
            float dur = buf_lo[i].duration * (1.0f - blend) + buf_hi[i].duration * blend;
            uint8_t note = (blend < 0.5f) ? buf_lo[i].note : buf_hi[i].note;
            steps[i] = make_step(note, v, true, dur);
        } else if (lo_gate && !hi_gate) {
            // Only lo has a hit — keep it with probability (1-blend)
            if (pattern_rand01(blend_seed) >= blend) {
                steps[i] = buf_lo[i];
            } else {
                steps[i] = make_step(0, 0.0f, false, 0.0f);
            }
        } else if (!lo_gate && hi_gate) {
            // Only hi has a hit — keep it with probability blend
            if (pattern_rand01(blend_seed) < blend) {
                steps[i] = buf_hi[i];
            } else {
                steps[i] = make_step(0, 0.0f, false, 0.0f);
            }
        } else {
            // Neither has a hit
            steps[i] = make_step(0, 0.0f, false, 0.0f);
        }
    }
}

// ---------------------------------------------------------------------------
// Melodic pattern generators (tracks 3=BASS, 4=KEYS)
// ---------------------------------------------------------------------------

inline void generate_melodic_pattern(
    PulsarStep* steps, int step_count, int track_index,
    const PulsarGenreProfile& genre, uint8_t root, const PulsarScale& scale,
    int chord_degree,
    uint32_t& seed,
    float density_override = -1.0f,
    int note_range_low_override = 0,
    int note_range_high_override = 0,
    int engine_note_min = 0)
{
    const float density = (density_override >= 0.0f) ? density_override : genre.base_density[track_index];
    int eff_range_low = (note_range_low_override > 0) ? note_range_low_override : genre.note_range_low;
    int eff_range_high = (note_range_high_override > 0) ? note_range_high_override : genre.note_range_high;
    if (engine_note_min > 0 && eff_range_low < engine_note_min)
        eff_range_low = engine_note_min;
    const int chord_semitones = chord_degree_to_semitones(chord_degree, scale);

    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    if (track_index == 3) {
        // BASS: chord-root-heavy with fifths and octaves
        // Center bass root in effective range instead of hardcoded octave
        int bass_mid = (eff_range_low + eff_range_high) / 2;
        int bass_root = root + 12 * ((bass_mid - root + 6) / 12) + chord_semitones;
        while (bass_root > eff_range_high) bass_root -= 12;
        while (bass_root < eff_range_low) bass_root += 12;

        // Strong hit on beat 1
        steps[0] = make_step(static_cast<uint8_t>(bass_root), 0.9f, true, 0.5f + pattern_rand01(seed) * 0.2f);

        for (int i = 1; i < step_count; i++) {
            if (pattern_rand01(seed) < density) {
                // Choose root, fifth, or octave
                float r = pattern_rand01(seed);
                int note;
                if (r < 0.5f) {
                    note = bass_root; // chord root
                } else if (r < 0.75f) {
                    // fifth: find degree closest to 7 semitones above chord root
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
                if (note < eff_range_low) note += 12;
                if (note > eff_range_high) note -= 12;

                float vel = 0.6f + pattern_rand01(seed) * 0.3f;
                float dur = 0.4f + pattern_rand01(seed) * 0.3f;
                steps[i] = make_step(static_cast<uint8_t>(note), vel, true, dur);
            }
        }
    } else {
        // KEYS (track 4): chord triad from current chord degree
        // Center key base in effective range instead of hardcoded octave
        int key_mid = (eff_range_low + eff_range_high) / 2;
        int key_base = root + 12 * ((key_mid - root + 6) / 12);

        // Build triad from chord degree within the scale
        int d0 = ((chord_degree % 7) + 7) % 7;
        int d2 = (d0 + 2) % 7;  // third
        int d4 = (d0 + 4) % 7;  // fifth

        int chord_notes[3];
        chord_notes[0] = key_base + (d0 < scale.count ? scale.degrees[d0] : chord_semitones);
        chord_notes[1] = key_base + (d2 < scale.count ? scale.degrees[d2] : chord_semitones + 4);
        chord_notes[2] = key_base + (d4 < scale.count ? scale.degrees[d4] : chord_semitones + 7);

        // Ensure third/fifth wrap correctly if their semitone < root semitone
        if (chord_notes[1] < chord_notes[0]) chord_notes[1] += 12;
        if (chord_notes[2] < chord_notes[0]) chord_notes[2] += 12;

        if (key_base < eff_range_low) {
            for (int n = 0; n < 3; n++) chord_notes[n] += 12;
        }

        for (int i = 0; i < step_count; i++) {
            if (pattern_rand01(seed) < density) {
                int idx = static_cast<int>(pattern_rand01(seed) * 2.99f);
                if (idx > 2) idx = 2;
                int note = chord_notes[idx];

                // Occasional octave shift
                if (pattern_rand01(seed) < 0.2f) note += 12;
                if (pattern_rand01(seed) < 0.1f) note -= 12;

                // Clamp to range
                while (note < eff_range_low) note += 12;
                while (note > eff_range_high) note -= 12;

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
    int chord_degree,
    uint32_t& seed,
    float hold_probability = 0.0f,
    int hold_length_min = 2,
    int hold_length_max = 8,
    float density_override = -1.0f,
    int note_range_low_override = 0,
    int note_range_high_override = 0,
    int engine_note_min = 0)
{
    const float density = (density_override >= 0.0f) ? density_override : genre.base_density[track_index];
    int eff_range_low = (note_range_low_override > 0) ? note_range_low_override : genre.note_range_low;
    int eff_range_high = (note_range_high_override > 0) ? note_range_high_override : genre.note_range_high;
    // Raise floor to engine's minimum playable note
    if (engine_note_min > 0 && eff_range_low < engine_note_min)
        eff_range_low = engine_note_min;

    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    // Base note: center of effective range, snapped to nearest root octave
    int range_mid = (eff_range_low + eff_range_high) / 2;
    int base_note = root + 12 * ((range_mid - root + 6) / 12);  // nearest root octave to midpoint
    while (base_note > eff_range_high) base_note -= 12;
    while (base_note < eff_range_low) base_note += 12;

    // Build chord tones for biasing (root/third/fifth of current chord)
    int d0 = ((chord_degree % 7) + 7) % 7;
    int d2 = (d0 + 2) % 7;
    int d4 = (d0 + 4) % 7;
    int chord_semi[3];
    chord_semi[0] = d0 < scale.count ? scale.degrees[d0] : 0;
    chord_semi[1] = d2 < scale.count ? scale.degrees[d2] : 4;
    chord_semi[2] = d4 < scale.count ? scale.degrees[d4] : 7;

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

    int i = 0;
    while (i < step_count) {
        if (pattern_rand01(seed) < density) {
            int note;
            // Hold steps (pads/drones) use stronger chord bias for harmonic coherence.
            // Non-hold steps (triggered FX) keep more randomness for variety.
            float chord_bias = (hold_probability > 0.5f) ? 0.85f : 0.6f;
            if (pattern_rand01(seed) < chord_bias) {
                // Chord tone: root, third, or fifth
                int ct = static_cast<int>(pattern_rand01(seed) * 2.99f);
                if (ct > 2) ct = 2;
                note = base_note + chord_semi[ct];
            } else {
                // Any scale degree (adds color but can be dissonant)
                int degree_idx = static_cast<int>(pattern_rand01(seed) * (scale.count - 0.01f));
                if (degree_idx >= scale.count) degree_idx = scale.count - 1;
                note = base_note + scale.degrees[degree_idx];
            }

            // Octave shifts: suppress for hold-heavy tracks (pads want stability)
            float octave_prob = (hold_probability > 0.5f) ? 0.03f : 0.15f;
            if (pattern_rand01(seed) < octave_prob) note += 12;
            if (pattern_rand01(seed) < octave_prob) note -= 12;

            // Clamp to range
            while (note < eff_range_low) note += 12;
            while (note > eff_range_high) note -= 12;

            float vel = 0.3f + pattern_rand01(seed) * 0.4f;
            float dur = dur_min + pattern_rand01(seed) * (dur_max - dur_min);

            // Hold chain: when hold_probability > 0, possibly extend across multiple steps
            if (hold_probability > 0.0f && pattern_rand01(seed) < hold_probability) {
                int chain_len = hold_length_min +
                    static_cast<int>(pattern_rand01(seed) * (hold_length_max - hold_length_min + 1));
                if (chain_len < 1) chain_len = 1;
                if (i + chain_len > step_count) chain_len = step_count - i;

                for (int c = 0; c < chain_len; c++) {
                    PulsarStep s = make_step(static_cast<uint8_t>(note),
                                            c == 0 ? vel : vel * 0.8f,
                                            true, 0.95f);
                    s.hold = (c < chain_len - 1);  // last step ends the chain
                    steps[i + c] = s;
                }
                i += chain_len;
            } else {
                steps[i] = make_step(static_cast<uint8_t>(note), vel, true, dur);
                i++;
            }
        } else {
            i++;
        }
    }
}

// ---------------------------------------------------------------------------
// Lick octave base: explicit octave or auto from note range midpoint
// ---------------------------------------------------------------------------

// Returns the MIDI octave offset (multiple of 12) to add to root_note.
// lick_octave: -1 = auto (midpoint of noteRange), 0-8 = explicit MIDI octave.
// note_range_low/high: genre profile note range for auto fallback.
inline int lick_octave_base(int lick_octave, uint8_t root_note,
                            uint8_t note_range_low, uint8_t note_range_high) {
    if (lick_octave >= 0) {
        // Explicit: lick_octave is the MIDI octave (e.g. 3 = C3 = MIDI 36)
        return lick_octave * 12;
    }
    // Auto: midpoint of note range, rounded down to nearest octave
    int mid = (static_cast<int>(note_range_low) + static_cast<int>(note_range_high)) / 2;
    // Clamp to safe range
    if (mid < 24) mid = 24;
    if (mid > 84) mid = 84;
    // Remove root offset so caller adds root_note + base
    int base = mid - static_cast<int>(root_note);
    // Round down to nearest multiple of 12
    if (base < 0) base = 0;
    base = (base / 12) * 12;
    return base;
}

// Canonical degree → MIDI note helper used by both generate_lick_pattern /
// bar_strategy_call_response AND choose_lick_octave in pulsar_handoff.h.
// Wraps scale_degree into [0, scale.count) tracking octave offset, then
// computes: root + lick_octave_base(lick_octave,...) + octave*12 + scale.degrees[d].
// chord_degree is always 0 for the lick renderer (no chord transposition on the
// solo line), so we omit it here to keep the signature minimal.
// note_range_low/high are only used when lick_octave == -1 (auto) — callers that
// already hold an explicit octave may pass 0, 127 as dummies.
inline int lick_degree_to_midi(int scale_degree, int root,
                               const PulsarScale& scale,
                               int lick_octave,
                               int note_range_low = 0,
                               int note_range_high = 127) {
    int d = scale_degree;
    int octave = 0;
    while (d < 0)            { d += scale.count; octave--; }
    while (d >= scale.count) { d -= scale.count; octave++; }
    int base = lick_octave_base(lick_octave,
                                static_cast<uint8_t>(root),
                                static_cast<uint8_t>(note_range_low),
                                static_cast<uint8_t>(note_range_high));
    return std::max(0, std::min(127, root + base + octave * 12 + scale.degrees[d]));
}

// Inverse of lick_degree_to_midi at octave 0: map a MIDI note to a scale degree
// (octave*count + nearest in-scale index). Used to snapshot an existing pattern
// into a lick contour. Out-of-scale notes snap to the nearest scale tone.
inline int midi_to_lick_degree(int midi, int root, const PulsarScale& scale) {
    if (scale.count <= 0) return 0;
    int rel = midi - root;
    int octave = (rel >= 0) ? (rel / 12) : -(((-rel) + 11) / 12);  // floor div
    int pc = ((rel % 12) + 12) % 12;
    int best = 0, bestDist = 128;
    for (int i = 0; i < scale.count; i++) {
        int dd = std::abs(static_cast<int>(scale.degrees[i]) - pc);
        if (dd < bestDist) { bestDist = dd; best = i; }
    }
    return octave * scale.count + best;
}

// Build a lick contour from a track's gated steps (degree + duration + velocity),
// so a Jam/lick-less solo has a shared evolving line to hand around. Returns length.
//
// midi_to_lick_degree returns a NEGATIVE degree for any source note below `root`,
// but the lick renderer (generate_lick_pattern / bar_strategy_call_response) treats
// scale_degree < 0 as a REST. Emitting raw negatives would silence every below-root
// note (the DogHouse lick-less fallback bug). So we octave-shift the ENTIRE contour
// up by whole scale octaves until the lowest degree is >= 0. Shifting all degrees by
// the same multiple of scale.count is musically transparent (preserves pitch class
// and the interval contour; the renderer re-bases the absolute octave via lick_octave).
inline int synthesize_lick_from_steps(const PulsarStep* steps, int step_count,
                                      int root, const PulsarScale& scale,
                                      int8_t* out_degrees, float* out_durations,
                                      float* out_velocities, int max_out) {
    if (scale.count <= 0) return 0;
    // First pass: collect raw degrees (may be negative) and track the minimum so
    // the whole contour can be shifted up together, preserving its shape.
    int raw[kMaxLickSteps];
    int cap = (max_out < kMaxLickSteps) ? max_out : kMaxLickSteps;
    int n = 0;
    int min_deg = 0;
    for (int i = 0; i < step_count && n < cap; i++) {
        if (!steps[i].gate) continue;
        int d = midi_to_lick_degree(steps[i].note, root, scale);
        raw[n] = d;
        if (d < min_deg) min_deg = d;
        out_durations[n] = steps[i].duration;
        out_velocities[n] = steps[i].velocity;
        n++;
    }
    // Octave-shift the whole contour so the lowest degree is non-negative.
    int shift = 0;
    if (min_deg < 0) shift = ((-min_deg + scale.count - 1) / scale.count) * scale.count;
    for (int i = 0; i < n; i++) {
        int d = raw[i] + shift;
        if (d < 0) d = 0; if (d > 127) d = 127;
        out_degrees[i] = static_cast<int8_t>(d);
    }
    return n;
}

// ---------------------------------------------------------------------------
// Lick pattern generator: melodic tracks driven by AI-generated lick data
// ---------------------------------------------------------------------------

inline void generate_lick_pattern(
    PulsarStep* steps, int step_count,
    const PulsarLickStep* lick, int lick_length,
    float mutation, uint8_t root_note, const PulsarScale& scale,
    uint32_t seed,
    int chord_degree = 0,
    int lick_octave = -1,
    uint8_t note_range_low = 36, uint8_t note_range_high = 72,
    int lick_loop_length = 0,
    int lick_degree_offset = 0)
{
    // Clear all steps first (rests by default)
    for (int i = 0; i < step_count; i++) {
        steps[i] = make_step(0, 0.0f, false, 0.0f);
    }

    // Compute how many sequencer steps the lick notes occupy (at 4 steps/beat)
    float note_beats = 0.0f;
    for (int i = 0; i < lick_length; i++) {
        note_beats += lick[i].duration;
    }
    int note_steps = std::max(1, static_cast<int>(note_beats * 4.0f + 0.5f));

    // lick_loop_length is in beats. Convert to sequencer steps for comparison.
    // When loop_steps > note_steps, notes fill (note_beats / loop_beats) of the
    // pattern and the rest is silence. E.g. 4 beats of notes with loopLength=8
    // means 50% notes + 50% rest.
    int loop_steps = (lick_loop_length > 0)
        ? std::max(1, static_cast<int>(static_cast<float>(lick_loop_length) * 4.0f + 0.5f))
        : note_steps;

    int play_steps = step_count;
    if (loop_steps > note_steps) {
        play_steps = std::max(note_steps,
            static_cast<int>(static_cast<float>(step_count) *
                static_cast<float>(note_steps) / static_cast<float>(loop_steps)));
    }

    // Walk through lick steps, converting beat-based durations to sequencer slots.
    // Each lick duration is in beats (0.5 = eighth, 1.0 = quarter, 2.0 = half).
    // Sequencer runs at 4 steps/beat (16th notes), so duration * 4 = steps occupied.
    // Gate fires on the first step; remaining steps within the duration are rests.
    int step_pos = 0;
    int lick_idx = 0;

    while (step_pos < play_steps) {
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

        // Quantize scale degree to MIDI note. The per-track degree offset shifts
        // sounding notes only (rests bailed out above): parallel diatonic harmony
        // that composes with mutation and the octave fold below.
        int octave = 0;
        int d = degree + lick_degree_offset;
        while (d < 0) { d += scale.count; octave--; }
        while (d >= scale.count) { d -= scale.count; octave++; }

        int chord_semitones = chord_degree_to_semitones(chord_degree, scale);
        int base = lick_octave_base(lick_octave, root_note, note_range_low, note_range_high);
        uint8_t midi_note = static_cast<uint8_t>(
            std::max(0, std::min(127,
                static_cast<int>(root_note) + base + chord_semitones + octave * 12 + scale.degrees[d])));

        // Gate fires on the first step with full duration for AR envelope sustain.
        // Remaining slots are hold steps so the gate stays high across the full note.
        // Per-step glide override flows from the lick step onto the gating sequencer
        // step only — hold-tail steps inherit nothing extra (no retrigger anyway).
        if (step_pos < step_count) {
            steps[step_pos] = make_step(midi_note, vel, true, 1.0f);
            steps[step_pos].hold = (slots > 1);
            steps[step_pos].glide_rate = ls.glide_rate;
        }
        for (int h = 1; h < slots && (step_pos + h) < step_count; h++) {
            steps[step_pos + h] = make_step(midi_note, vel, true, 1.0f);
            steps[step_pos + h].hold = (h < slots - 1);
        }
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
    int step_count, uint32_t seed,
    int chord_degree = 0,
    float hold_probability = 0.0f,
    int hold_length_min = 2,
    int hold_length_max = 8,
    float density_override = -1.0f,
    int note_range_low_override = 0,
    int note_range_high_override = 0,
    int engine_note_min = 0)
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
                                 root_note, scale, chord_degree, seed,
                                 density_override, note_range_low_override, note_range_high_override,
                                 engine_note_min);
    } else {
        generate_effect_pattern(ts.steps, step_count, track_index, genre,
                                root_note, scale, chord_degree, seed,
                                hold_probability, hold_length_min, hold_length_max,
                                density_override, note_range_low_override, note_range_high_override,
                                engine_note_min);
    }
}

// ---------------------------------------------------------------------------
// Bass solo articulation pass
// ---------------------------------------------------------------------------

// Slap ghost signature — short duration + high velocity. Kept as named constants so
// the insert and the idempotency-strip below agree on what a prior slap looks like.
static constexpr float kBassSlapVelocity = 0.9f;
static constexpr float kBassSlapDuration = 0.15f;

// Articulate the bass when it leads a solo: accent beat positions and insert sparse
// syncopated percussive slap ghost-notes on empty off-beats. Operates in place.
// slap_density (~0.35 sparse) scales the off-beat slap probability. The slap note is
// the most recent preceding gated bass pitch, with a short duration + high velocity so
// it re-attacks as a percussive ghost (it lands on a rest, so no glide slurs into it).
//
// IDEMPOTENT under repeated application: this runs once per bar, but in JAM mode the
// bass substrate may not be regenerated, so the SAME buffer is articulated bar after
// bar. To avoid piling slaps onto every off-beat and creeping velocities to full scale
// (a sparse bass solo turning into a loud constant 16th-slap line), it (1) strips any
// slap inserted by a prior pass before re-inserting, and (2) uses max-toward-floor
// accents instead of additive boosts so accented beats settle at a fixed level.
inline void articulate_bass_solo(PulsarStep* steps, int step_count,
                                 float slap_density, uint32_t& seed) {
    if (step_count <= 0) return;
    // (1) Strip slaps inserted by a previous pass (gated off-beat steps carrying the
    // slap signature) so re-articulating a non-regenerated buffer can't accumulate.
    for (int i = 1; i < step_count; i += 2) {
        if (steps[i].gate &&
            steps[i].duration == kBassSlapDuration &&
            steps[i].velocity == kBassSlapVelocity) {
            steps[i] = make_step(0, 0.0f, false, 0.0f);
        }
    }
    uint8_t last_note = 36;  // default low bass note if none seen yet
    for (int i = 0; i < step_count; i++) {
        if (steps[i].gate) {
            last_note = steps[i].note;
            // (2) Beat-position accents as idempotent floors (max, not additive): strong
            // on the quarter (i%4==0), medium on the eighth (i%2==0). max() preserves
            // already-louder source notes and never creeps past the floor on repeats.
            if (i % 4 == 0)      steps[i].velocity = std::max(steps[i].velocity, 0.85f);
            else if (i % 2 == 0) steps[i].velocity = std::max(steps[i].velocity, 0.70f);
        } else if ((i % 2) == 1 && slap_density > 0.0f) {
            // Empty off-beat: sparse slap ghost.
            if (pattern_rand01(seed) < slap_density * 0.5f) {
                steps[i] = make_step(last_note, kBassSlapVelocity, true, kBassSlapDuration);
            }
        }
    }
}
