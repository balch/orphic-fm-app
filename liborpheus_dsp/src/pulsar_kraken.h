#pragma once

// Kraken: a performance key shift layered on the vibe's key as notes fire. It never
// writes the vibe's root or scale, so letting go is an exact return home.

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"

constexpr int kKrakenHome = -1;
constexpr int kKrakenTargetCount = 4;
constexpr float kKrakenGlideRate = 0.001f;  // ~20 ms slide for a note still sounding

enum KrakenTarget : int { KRAKEN_IV = 0, KRAKEN_V = 1, KRAKEN_RELATIVE = 2, KRAKEN_PARALLEL = 3 };

// Where a shift lands: tonic offset in semitones and the scale played there.
struct KrakenShift {
    int root_offset;
    int scale_index;
};

// Relative key per home scale: the same pitch set from a new tonic. {0, home} is a no-op.
static const KrakenShift kKrakenRelative[kNumPulsarScales] = {
    {+3, 1},   // 0 Minor -> relative Major
    {-3, 0},   // 1 Major -> relative Minor
    {-3, 10},  // 2 Pentatonic -> relative Minor Pentatonic
    {-4, 1},   // 3 Phrygian -> Major of the same set
    {0, 4},    // 4 Whole Tone: no relative
    {0, 5},    // 5 Chromatic: no relative
    {-2, 1},   // 6 Dorian -> Major of the same set
    {+4, 0},   // 7 Lydian -> Minor of the same set
    {+2, 0},   // 8 Mixolydian -> Minor of the same set
    {+3, 1},   // 9 Harmonic Minor -> relative Major
    {+3, 2},   // 10 Minor Pentatonic -> relative Pentatonic
    {0, 11},   // 11 Hirajoshi: no relative
    {0, 12},   // 12 In Sen: no relative
    {+3, 15},  // 13 Blues -> Major Blues, same set
    {+3, 2},   // 14 Blues Pentatonic -> Pentatonic
    {-3, 13},  // 15 Major Blues -> Blues, same set
};

// Parallel key per home scale: same tonic, the third flipped.
static const KrakenShift kKrakenParallel[kNumPulsarScales] = {
    {0, 1},   // 0 Minor -> Major
    {0, 0},   // 1 Major -> Minor
    {0, 10},  // 2 Pentatonic -> Minor Pentatonic
    {0, 1},   // 3 Phrygian -> Major
    {0, 4},   // 4 Whole Tone: no parallel
    {0, 5},   // 5 Chromatic: no parallel
    {0, 8},   // 6 Dorian -> Mixolydian (only the third differs)
    {0, 0},   // 7 Lydian -> Minor
    {0, 6},   // 8 Mixolydian -> Dorian
    {0, 1},   // 9 Harmonic Minor -> Major
    {0, 2},   // 10 Minor Pentatonic -> Pentatonic
    {0, 2},   // 11 Hirajoshi -> Pentatonic
    {0, 12},  // 12 In Sen: no parallel
    {0, 15},  // 13 Blues -> Major Blues
    {0, 2},   // 14 Blues Pentatonic -> Pentatonic
    {0, 13},  // 15 Major Blues -> Blues
};

inline int kraken_clamp_scale(int scale) {
    return scale < 0 ? 0 : (scale >= kNumPulsarScales ? kNumPulsarScales - 1 : scale);
}

inline KrakenShift kraken_shift_for(int target, int home_scale) {
    switch (target) {
        case KRAKEN_IV: return {5, home_scale};
        case KRAKEN_V: return {7, home_scale};
        case KRAKEN_RELATIVE: return kKrakenRelative[home_scale];
        case KRAKEN_PARALLEL: return kKrakenParallel[home_scale];
        default: return {0, home_scale};
    }
}

inline int kraken_scale_for(int target, int home_scale) {
    home_scale = kraken_clamp_scale(home_scale);
    if (target < 0 || target >= kKrakenTargetCount) return home_scale;
    return kraken_shift_for(target, home_scale).scale_index;
}

// The note `note` plays under `target`, remapped by scale degree so a melody keeps its shape.
inline int kraken_apply(int note, int home_root, int home_scale, int target) {
    if (target < 0 || target >= kKrakenTargetCount) return note;
    home_scale = kraken_clamp_scale(home_scale);
    const KrakenShift shift = kraken_shift_for(target, home_scale);
    if (shift.scale_index == home_scale) return note + shift.root_offset;

    const PulsarScale& from = kPulsarScales[home_scale];
    const PulsarScale& to = kPulsarScales[shift.scale_index];
    const int rel = ((note - home_root) % 12 + 12) % 12;
    for (int i = 0; i < from.count; i++) {
        if (from.degrees[i] != rel) continue;
        if (to.count == from.count) return note - rel + shift.root_offset + to.degrees[i];
        const int to_root = ((home_root + shift.root_offset) % 12 + 12) % 12;
        return quantize_to_scale(note + shift.root_offset, static_cast<uint8_t>(to_root), to);
    }
    return note + shift.root_offset;  // a passing tone keeps its distance from the tonic
}
