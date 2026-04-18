#pragma once
#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"  // chord_degree_to_semitones
#include <cstring>
#include <cstdint>

// Shared LCG (Numerical Recipes constants). Advances and returns the new seed.
// Callers derive their output from selected bits of the new state.
inline uint32_t lcg_next(uint32_t& seed) {
    seed = seed * 1103515245u + 12345u;
    return seed;
}

// ── Arpeggiator helpers ──────────────────────────────────────────────────

// Engine 14 (CHD) has native chord synthesis.
inline bool engine_has_native_chord(int engine_id) {
    return engine_id == 14;
}

// Compute chord tones (root, 3rd, 5th, 7th) in MIDI space from root MIDI + scale.
// Applies inversion (rotation + octave shift) before direction ordering.
// Writes up to 4 notes ordered by arp direction; returns count.
inline int compute_chord_tones(
    int root_midi,
    int chord_degree,
    const PulsarScale& scale,
    int voicing_count,             // 1=root only, 2=root+5, 3=triad, 4=7th chord
    ArpDirectionId direction,
    SectionInversionId inversion,
    uint32_t seed,
    uint8_t out_notes[4])
{
    if (voicing_count < 1) voicing_count = 1;
    if (voicing_count > 4) voicing_count = 4;

    // Intervals above root in diatonic terms (root=0, 3rd=2, 5th=4, 7th=6)
    const int intervals[4] = {0, 2, 4, 6};
    int raw[4];
    int base_degree = ((chord_degree % 7) + 7) % 7;
    int base_semis = (base_degree < scale.count) ? scale.degrees[base_degree] : 0;

    for (int i = 0; i < voicing_count; i++) {
        int deg = (base_degree + intervals[i]) % 7;
        int semis = (deg < scale.count) ? scale.degrees[deg] : 0;
        // 7th may wrap down if scale wraps — force it an octave up to stay above root
        int relative = semis - base_semis;
        if (relative < 0) relative += 12;
        raw[i] = root_midi + relative;
    }

    // Apply inversion by rotating the chord tones
    int rotation = 0;
    switch (inversion) {
        case SectionInversionId::FOLLOW_STYLE:    rotation = 0; break;
        case SectionInversionId::ROOT_POSITION:   rotation = 0; break;
        case SectionInversionId::FIRST_INVERSION: rotation = 1; break;
        case SectionInversionId::SECOND_INVERSION: rotation = 2; break;
        case SectionInversionId::OPEN_VOICING: {
            // Open voicing: root stays low, remaining tones shift up an octave
            for (int i = 1; i < voicing_count; i++) raw[i] += 12;
            rotation = 0;  // no additional rotation for open
            break;
        }
    }
    // Rotate: raw[rotation] becomes the lowest note; wrapped tones go +12
    if (rotation > 0) {
        int rotated[4];
        for (int i = 0; i < voicing_count; i++) {
            int src = (i + rotation) % voicing_count;
            rotated[i] = raw[src];
            // Bump wrapped tones up an octave so they stay "above" earlier tones
            if (src < rotation) rotated[i] += 12;
        }
        for (int i = 0; i < voicing_count; i++) raw[i] = rotated[i];
    }

    switch (direction) {
        case ArpDirectionId::UP:
            for (int i = 0; i < voicing_count; i++) out_notes[i] = static_cast<uint8_t>(raw[i]);
            break;
        case ArpDirectionId::DOWN:
            for (int i = 0; i < voicing_count; i++) out_notes[i] = static_cast<uint8_t>(raw[voicing_count - 1 - i]);
            break;
        case ArpDirectionId::UP_DOWN:
            // simple: same as UP for now (up-down needs more slots than we have)
            for (int i = 0; i < voicing_count; i++) out_notes[i] = static_cast<uint8_t>(raw[i]);
            break;
        case ArpDirectionId::RANDOM: {
            for (int i = 0; i < voicing_count; i++) out_notes[i] = static_cast<uint8_t>(raw[i]);
            uint32_t rng = seed ? seed : 1u;
            for (int i = voicing_count - 1; i > 0; i--) {
                int j = static_cast<int>((lcg_next(rng) >> 16) % static_cast<uint32_t>(i + 1));
                uint8_t tmp = out_notes[i];
                out_notes[i] = out_notes[j];
                out_notes[j] = tmp;
            }
            break;
        }
    }
    return voicing_count;
}

// Sample interval between arp retriggers given speed 0-1 and step duration in samples.
// Fast end: 720 samples (~15ms at 48kHz). Slow end: full step.
inline int arp_samples_per_note(float arp_speed, int samples_per_step) {
    const int kFastSamples = 720;
    float s = arp_speed;
    if (s < 0.0f) s = 0.0f;
    if (s > 1.0f) s = 1.0f;
    float slow = static_cast<float>(samples_per_step);
    float val = slow * (1.0f - s) + static_cast<float>(kFastSamples) * s;
    if (val < static_cast<float>(kFastSamples)) val = static_cast<float>(kFastSamples);
    return static_cast<int>(val);
}

// ── Rhythm templates ─────────────────────────────────────────────────────
// Each template is 16 floats: 0 = rest, 0.1-1.0 = velocity.
// Tiles to 32 steps when step_count > 16.

// PAD: sustained, hits only on step 0, full duration covers the pattern.
// Velocity 0.7 (soft sustained pad).
static constexpr float kCompingPad[16] = {
    0.7f, 0.0f, 0.0f, 0.0f,  0.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 0.0f,  0.0f, 0.0f, 0.0f, 0.0f,
};

// FUNK_STABS: syncopated 16th-note comping (after James Brown / Prince comping).
// Accents on the "and" of 2 and "and" of 4, with extra ghost-like stabs.
static constexpr float kCompingFunkStabs[16] = {
    0.0f, 0.0f, 0.9f, 0.0f,  0.0f, 0.6f, 0.0f, 0.8f,
    0.0f, 0.0f, 0.9f, 0.0f,  0.6f, 0.0f, 0.0f, 0.7f,
};

// ROCK_DOWNBEATS: hits on beats 1 and 3 (every 8 steps in 16-step grid).
// Strong accent on 1, slightly softer on 3.
static constexpr float kCompingRockDownbeats[16] = {
    1.0f, 0.0f, 0.0f, 0.0f,  0.0f, 0.0f, 0.0f, 0.0f,
    0.8f, 0.0f, 0.0f, 0.0f,  0.0f, 0.0f, 0.0f, 0.0f,
};

// SKA_UPSTROKES: off-beat accents — hits on every "and" (steps 2, 6, 10, 14 in
// 16-step grid at 8th-note granularity). Classic ska/reggae upstroke pattern.
static constexpr float kCompingSkaUpstrokes[16] = {
    0.0f, 0.0f, 0.9f, 0.0f,  0.0f, 0.0f, 0.8f, 0.0f,
    0.0f, 0.0f, 0.9f, 0.0f,  0.0f, 0.0f, 0.8f, 0.0f,
};

// BLUES_SHUFFLE: triplet-shuffle feel on dotted-8ths. Emulates swing via
// velocity accents on beats 1 and 3 with ghost hits on the "let" of each beat.
static constexpr float kCompingBluesShuffle[16] = {
    0.9f, 0.0f, 0.0f, 0.5f,  0.0f, 0.0f, 0.6f, 0.0f,
    0.8f, 0.0f, 0.0f, 0.5f,  0.0f, 0.0f, 0.6f, 0.0f,
};

// JAZZ_COMP: busy syncopated piano-style comping. Jabs on off-beats with
// velocity variation simulating "comping behind the soloist."
static constexpr float kCompingJazzComp[16] = {
    0.0f, 0.7f, 0.0f, 0.5f,  0.8f, 0.0f, 0.6f, 0.0f,
    0.0f, 0.7f, 0.8f, 0.0f,  0.0f, 0.5f, 0.0f, 0.6f,
};

// REGGAE_SKANK: hits only on beats 2 and 4 (every 8 steps starting at step 4).
// The classic "skank" — pure backbeat comping.
static constexpr float kCompingReggaeSkank[16] = {
    0.0f, 0.0f, 0.0f, 0.0f,  1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 0.0f,  0.9f, 0.0f, 0.0f, 0.0f,
};

// GOSPEL_STABS: dense 8th-note stabs with heavy accents on beats 1 and 3.
// Driving rhythmic comping for uptempo gospel/soul.
static constexpr float kCompingGospelStabs[16] = {
    1.0f, 0.0f, 0.7f, 0.0f,  0.8f, 0.0f, 0.7f, 0.0f,
    0.9f, 0.0f, 0.7f, 0.0f,  0.8f, 0.0f, 0.7f, 0.0f,
};

// Get rhythm template for a given style. Returns nullptr for unknown styles.
inline const float* comping_rhythm_template(CompingStyleId style) {
    switch (style) {
        case CompingStyleId::PAD: return kCompingPad;
        case CompingStyleId::FUNK_STABS: return kCompingFunkStabs;
        case CompingStyleId::ROCK_DOWNBEATS: return kCompingRockDownbeats;
        case CompingStyleId::SKA_UPSTROKES: return kCompingSkaUpstrokes;
        case CompingStyleId::BLUES_SHUFFLE: return kCompingBluesShuffle;
        case CompingStyleId::JAZZ_COMP: return kCompingJazzComp;
        case CompingStyleId::REGGAE_SKANK: return kCompingReggaeSkank;
        case CompingStyleId::GOSPEL_STABS: return kCompingGospelStabs;
        case CompingStyleId::CUSTOM: return nullptr;  // not supported in Phase 1
    }
    return nullptr;
}

// Articulation (step.duration) per style. PAD = long, stabs = short.
inline float comping_articulation(CompingStyleId style) {
    switch (style) {
        case CompingStyleId::PAD: return 1.0f;
        case CompingStyleId::FUNK_STABS: return 0.3f;
        case CompingStyleId::ROCK_DOWNBEATS: return 0.6f;
        case CompingStyleId::SKA_UPSTROKES: return 0.2f;    // tight upstroke
        case CompingStyleId::BLUES_SHUFFLE: return 0.5f;
        case CompingStyleId::JAZZ_COMP: return 0.35f;       // short jabs
        case CompingStyleId::REGGAE_SKANK: return 0.25f;    // tight skank
        case CompingStyleId::GOSPEL_STABS: return 0.4f;
        case CompingStyleId::CUSTOM: return 0.5f;
    }
    return 0.5f;
}

// Default octave per style. Dropped one octave from intuitive values because
// CHD engine voicings + note_range clamping tend to push higher than expected.
// Styles that historically want brightness (ska/reggae upstrokes) still ride
// a bit higher than piano-style styles.
inline int comping_default_octave(CompingStyleId style) {
    switch (style) {
        case CompingStyleId::PAD: return 4;              // was 5
        case CompingStyleId::FUNK_STABS: return 3;       // was 4 — tighter mid
        case CompingStyleId::ROCK_DOWNBEATS: return 3;   // was 4
        case CompingStyleId::SKA_UPSTROKES: return 4;    // was 5 — still bright
        case CompingStyleId::BLUES_SHUFFLE: return 3;    // was 4 — piano range
        case CompingStyleId::JAZZ_COMP: return 4;        // was 5
        case CompingStyleId::REGGAE_SKANK: return 4;     // was 5
        case CompingStyleId::GOSPEL_STABS: return 3;     // was 4 — piano range
        case CompingStyleId::CUSTOM: return 3;
    }
    return 3;
}

// ── generate_chordal_pattern ─────────────────────────────────────────────
// Pure function: walks the style's rhythm template and writes chord-degree-aware
// notes to steps[]. Uses chord_degree to transpose the stab's root note within
// the scale. Each stab writes one note (the chord root); the CHD engine renders
// the full chord voicing at play time via its harmonics/morph parameters.
//
// Phase 1: step.note = chord_root_midi. No multi-voice; single note per step.
// step.hold = false. step.velocity from template. step.duration from articulation.

inline void generate_chordal_pattern(
    PulsarStep* steps, int step_count,
    CompingStyleId style,
    int chord_degree,                // current active chord degree (0-6)
    uint8_t root, const PulsarScale& scale,
    uint8_t note_range_low, uint8_t note_range_high)
{
    // Clear all steps
    for (int i = 0; i < step_count; i++) {
        steps[i] = {};
    }

    const float* rhythm = comping_rhythm_template(style);
    if (rhythm == nullptr) return;  // CUSTOM not supported in Phase 1

    const float articulation = comping_articulation(style);
    const int octave = comping_default_octave(style);

    // Compute MIDI note for the chord root:
    //   base_note = octave * 12
    //   root_note = base_note + root + chord_degree_semitones
    const int chord_semis = chord_degree_to_semitones(chord_degree, scale);
    int root_midi = octave * 12 + static_cast<int>(root) + chord_semis;

    // Clamp to note range
    while (root_midi < note_range_low) root_midi += 12;
    while (root_midi > note_range_high) root_midi -= 12;
    if (root_midi < 0) root_midi = 0;
    if (root_midi > 127) root_midi = 127;

    for (int i = 0; i < step_count; i++) {
        // Tile the 16-step template across 32 steps
        float vel = rhythm[i % 16];
        if (vel <= 0.0f) continue;

        steps[i].note = static_cast<uint8_t>(root_midi);
        steps[i].raw_note = static_cast<uint8_t>(root_midi);
        steps[i].velocity = vel;
        steps[i].gate = true;
        steps[i].duration = articulation;
        steps[i].hold = false;
    }

    // Sustain extension: for sustained styles (articulation >= 0.95),
    // extend each active step's gate through subsequent rest steps with hold=true.
    if (articulation >= 0.95f) {
        for (int i = 0; i < step_count; i++) {
            if (steps[i].gate && !steps[i].hold) {
                // Extend through subsequent rests until next active step
                for (int j = i + 1; j < step_count; j++) {
                    if (steps[j].gate) break;  // stop at next active step
                    steps[j] = steps[i];
                    steps[j].hold = true;
                }
                // Mark the originating step as hold to start the chain
                steps[i].hold = true;
            }
        }
    }
}

// Apply humanization transforms to a CHORDAL track's step array.
// Fill: ASCENDING_ARP — replace bar with chord tones walking up, rising velocity.
// Writes steps with gate on every other step (step_count / 8 density) to leave
// room for arp articulation.
inline void apply_fill_ascending_arp(
    PulsarStep* steps, int step_count,
    int chord_degree, uint8_t root, const PulsarScale& scale,
    uint8_t note_range_low, uint8_t note_range_high,
    int octave)
{
    // Clear bar
    for (int i = 0; i < step_count; i++) steps[i] = {};

    // Ascending arp pattern: root, 3rd, 5th, root+12 — repeat as needed
    // Sparse: hit every 2 steps for clarity
    int base_degree = ((chord_degree % 7) + 7) % 7;
    int intervals[4] = {0, 2, 4, 7};  // root, 3rd, 5th, octave
    int base_semis = (base_degree < scale.count) ? scale.degrees[base_degree] : 0;

    for (int i = 0; i < step_count; i += 2) {
        int idx = (i / 2) % 4;
        int deg = (base_degree + intervals[idx]) % 7;
        int semis = (deg < scale.count) ? scale.degrees[deg] : 0;
        int relative = semis - base_semis;
        if (relative < 0) relative += 12;

        int note = octave * 12 + static_cast<int>(root) + base_semis + relative;
        // Octave shift on the 4th tone (index 3) — already handled by intervals[3]=7
        // But ensure ascending: progressively add octaves on subsequent cycles
        int cycle = (i / 2) / 4;
        note += cycle * 12;

        while (note < note_range_low) note += 12;
        while (note > note_range_high) note -= 12;
        if (note < 0) note = 0;
        if (note > 127) note = 127;

        steps[i].note = static_cast<uint8_t>(note);
        steps[i].raw_note = static_cast<uint8_t>(note);
        steps[i].gate = true;
        // Rising velocity toward the end of the bar
        float t = static_cast<float>(i) / static_cast<float>(step_count);
        steps[i].velocity = 0.5f + 0.45f * t;
        steps[i].duration = 0.5f;
        steps[i].hold = false;
    }
}

// Compute a single chord-tone MIDI note clamped to [note_range_low, note_range_high].
// `interval` is a scale-degree offset from the chord root (0=root, 2=3rd, 4=5th,
// 6=7th). Pass 7 as a shorthand for "root an octave up".
// octave_shift adds/subtracts whole octaves before clamping (e.g., for stacks).
inline int chord_tone_note_clamped(
    int octave, int root, int chord_degree, int interval,
    const PulsarScale& scale,
    int note_range_low, int note_range_high,
    int octave_shift = 0)
{
    int base_degree = ((chord_degree % 7) + 7) % 7;
    int base_semis = (base_degree < scale.count) ? scale.degrees[base_degree] : 0;

    int relative;
    if (interval == 7) {
        relative = 12;  // explicit root-octave-up
    } else {
        int deg = (base_degree + interval) % 7;
        int semis = (deg < scale.count) ? scale.degrees[deg] : 0;
        relative = semis - base_semis;
        if (relative < 0) relative += 12;
    }

    int note = octave * 12 + root + base_semis + relative + octave_shift * 12;
    while (note < note_range_low) note += 12;
    while (note > note_range_high) note -= 12;
    if (note < 0) note = 0;
    if (note > 127) note = 127;
    return note;
}

// Fill: DESCENDING_ARP — chord tones walking down, fading velocity.
// Root octave-up → 5th → 3rd → root, repeating. Sparse (every 2 steps).
inline void apply_fill_descending_arp(
    PulsarStep* steps, int step_count,
    int chord_degree, uint8_t root, const PulsarScale& scale,
    uint8_t note_range_low, uint8_t note_range_high,
    int octave)
{
    for (int i = 0; i < step_count; i++) steps[i] = {};

    const int intervals[4] = {7, 4, 2, 0};  // root-up, 5th, 3rd, root

    for (int i = 0; i < step_count; i += 2) {
        int idx = (i / 2) % 4;
        int note = chord_tone_note_clamped(
            octave, static_cast<int>(root), chord_degree, intervals[idx],
            scale, note_range_low, note_range_high);

        steps[i].note = static_cast<uint8_t>(note);
        steps[i].raw_note = static_cast<uint8_t>(note);
        steps[i].gate = true;
        float t = static_cast<float>(i) / static_cast<float>(step_count);
        steps[i].velocity = 0.92f - 0.35f * t;  // starts loud, softens into release
        steps[i].duration = 0.5f;
        steps[i].hold = false;
    }
}

// Fill: TURNAROUND — bluesy 4-beat landing that resolves into the next bar.
// root → 3rd → ♭7 → root with building velocity. Short staccato for a
// "play-me-in" feel.
inline void apply_fill_turnaround(
    PulsarStep* steps, int step_count,
    int chord_degree, uint8_t root, const PulsarScale& scale,
    uint8_t note_range_low, uint8_t note_range_high,
    int octave)
{
    for (int i = 0; i < step_count; i++) steps[i] = {};

    const int beat_positions[4] = {
        0,
        step_count / 4,
        step_count / 2,
        (3 * step_count) / 4,
    };
    const int intervals[4]   = {0, 2, 6, 0};          // root, 3rd, 7th, root
    const float velocities[4] = {0.75f, 0.62f, 0.80f, 0.95f};  // build to landing

    for (int b = 0; b < 4; b++) {
        int step_idx = beat_positions[b];
        if (step_idx >= step_count) continue;
        int note = chord_tone_note_clamped(
            octave, static_cast<int>(root), chord_degree, intervals[b],
            scale, note_range_low, note_range_high);

        steps[step_idx].note = static_cast<uint8_t>(note);
        steps[step_idx].raw_note = static_cast<uint8_t>(note);
        steps[step_idx].gate = true;
        steps[step_idx].velocity = velocities[b];
        steps[step_idx].duration = 0.4f;
        steps[step_idx].hold = false;
    }
}

// Fill: DOUBLE_TIME — every step plays a chord tone, rotating root/3/root/5.
// Downbeats accented, offbeats quieter to avoid flat intensity.
inline void apply_fill_double_time(
    PulsarStep* steps, int step_count,
    int chord_degree, uint8_t root, const PulsarScale& scale,
    uint8_t note_range_low, uint8_t note_range_high,
    int octave)
{
    for (int i = 0; i < step_count; i++) steps[i] = {};

    const int pattern[4] = {0, 2, 0, 4};  // root, 3rd, root, 5th

    for (int i = 0; i < step_count; i++) {
        int interval = pattern[i % 4];
        int note = chord_tone_note_clamped(
            octave, static_cast<int>(root), chord_degree, interval,
            scale, note_range_low, note_range_high);

        steps[i].note = static_cast<uint8_t>(note);
        steps[i].raw_note = static_cast<uint8_t>(note);
        steps[i].gate = true;
        bool downbeat = (i % 4) == 0;
        steps[i].velocity = downbeat ? 0.85f : 0.55f;
        steps[i].duration = 0.3f;  // short, clear at double time
        steps[i].hold = false;
    }
}

// Fill: STAB_FLURRY — empty first half, dense stabs in second half.
// Rising velocity across the flurry for a "build-up" feel.
inline void apply_fill_stab_flurry(
    PulsarStep* steps, int step_count,
    int chord_degree, uint8_t root, const PulsarScale& scale,
    uint8_t note_range_low, uint8_t note_range_high,
    int octave)
{
    for (int i = 0; i < step_count; i++) steps[i] = {};

    int start = step_count / 2;
    const int tones[3] = {0, 2, 4};  // root, 3rd, 5th

    for (int i = start; i < step_count; i++) {
        int interval = tones[(i - start) % 3];
        int note = chord_tone_note_clamped(
            octave, static_cast<int>(root), chord_degree, interval,
            scale, note_range_low, note_range_high);

        steps[i].note = static_cast<uint8_t>(note);
        steps[i].raw_note = static_cast<uint8_t>(note);
        steps[i].gate = true;
        float t = static_cast<float>(i - start) / static_cast<float>(step_count - start);
        steps[i].velocity = 0.70f + 0.28f * t;
        steps[i].duration = 0.20f;  // very short stabs
        steps[i].hold = false;
    }
}

// Fill: DROP_OUT — keep only the downbeat, silence everything else.
// Creates dramatic space, letting the rest of the band breathe.
inline void apply_fill_drop_out(
    PulsarStep* steps, int step_count,
    int chord_degree, uint8_t root, const PulsarScale& scale,
    uint8_t note_range_low, uint8_t note_range_high,
    int octave)
{
    for (int i = 0; i < step_count; i++) steps[i] = {};

    int note = chord_tone_note_clamped(
        octave, static_cast<int>(root), chord_degree, 0,
        scale, note_range_low, note_range_high);

    steps[0].note = static_cast<uint8_t>(note);
    steps[0].raw_note = static_cast<uint8_t>(note);
    steps[0].gate = true;
    steps[0].velocity = 0.90f;
    steps[0].duration = 0.6f;
    steps[0].hold = false;
}

// All probabilities scaled by complexity (0-1).
// First active step is anchor-protected from drops and octave jumps.
// Deterministic from seed.
inline void apply_humanization(
    PulsarStep* steps, int step_count,
    float drop_prob, float ghost_prob,
    float oct_jump_prob, float ext_prob,
    float complexity, uint32_t seed)
{
    if (complexity <= 0.0f) return;

    // Find anchor (first non-hold active step)
    int anchor = -1;
    for (int i = 0; i < step_count; i++) {
        if (steps[i].gate && !steps[i].hold) { anchor = i; break; }
    }

    auto next_rand = [&seed]() -> float {
        return static_cast<float>((lcg_next(seed) >> 8) & 0xFFFF) / 65535.0f;
    };

    float eff_drop = drop_prob * complexity;
    float eff_ghost = ghost_prob * complexity;
    float eff_oct = oct_jump_prob * complexity;
    float eff_ext = ext_prob * complexity;

    for (int i = 0; i < step_count; i++) {
        if (!steps[i].gate) {
            // Ghost insertion on empty step
            if (eff_ghost > 0.0f && next_rand() < eff_ghost) {
                // Clone anchor note at low velocity
                if (anchor >= 0) {
                    steps[i] = steps[anchor];
                    steps[i].velocity *= 0.3f;
                    steps[i].duration = 0.2f;
                    steps[i].hold = false;
                }
            }
        } else {
            if (i == anchor) continue;    // protect anchor from drops/octave
            if (steps[i].hold) continue;  // don't modify hold-chain members

            // Drop
            if (eff_drop > 0.0f && next_rand() < eff_drop) {
                steps[i] = {};
                continue;
            }

            // Octave jump
            if (eff_oct > 0.0f && next_rand() < eff_oct) {
                int shift = (next_rand() < 0.5f) ? 12 : -12;
                int nn = static_cast<int>(steps[i].note) + shift;
                if (nn >= 0 && nn <= 127) {
                    steps[i].note = static_cast<uint8_t>(nn);
                }
            }

            // Extension (add 9th ~2 semis or 11th ~5 semis flavor)
            if (eff_ext > 0.0f && next_rand() < eff_ext) {
                int shift = (next_rand() < 0.5f) ? 2 : 5;
                int nn = static_cast<int>(steps[i].note) + shift;
                if (nn >= 0 && nn <= 127) {
                    steps[i].note = static_cast<uint8_t>(nn);
                }
            }
        }
    }
}
