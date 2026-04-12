#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"
#include <cstring>

// ---------------------------------------------------------------------------
// Beat motif evolution for Pulsar
// ---------------------------------------------------------------------------

inline void init_motif_state(MotifState& state, const MotifSetParam& set, uint32_t& seed) {
    state.current_motif = 0;
    if (set.bars_per_motif_min >= set.bars_per_motif_max) {
        state.bars_remaining = set.bars_per_motif_min;
    } else {
        int range = set.bars_per_motif_max - set.bars_per_motif_min + 1;
        state.bars_remaining = set.bars_per_motif_min + static_cast<int>(pattern_rand01(seed) * range) % range;
    }
    std::memset(state.bars_since_motif, 0, sizeof(state.bars_since_motif));
}

inline int select_next_motif(
    const MotifSetParam& set,
    const MotifState& state,
    int current,
    uint32_t& seed
) {
    if (set.motif_count <= 1) return 0;

    float weights[kMaxMotifs];
    float total = 0.0f;
    for (int i = 0; i < set.motif_count; i++) {
        float base = set.transition_weights[current][i];
        int bars_ago = state.bars_since_motif[i];
        float recency = 1.0f;
        for (int p = 0; p < bars_ago && p < 16; p++) {
            recency *= set.recency_decay;
        }
        weights[i] = base * (0.05f + 0.95f * (1.0f - recency));
        total += weights[i];
    }

    if (total <= 0.0f) return current;

    float roll = pattern_rand01(seed) * total;
    float cumulative = 0.0f;
    for (int i = 0; i < set.motif_count; i++) {
        cumulative += weights[i];
        if (roll <= cumulative) return i;
    }
    return current;
}

inline void apply_motif_to_genre(PulsarGenreProfile& genre, const MotifParam& motif) {
    for (int t = 0; t < kNumPulsarTracks; t++) {
        if (motif.track_density_active[t]) {
            genre.base_density[t] = motif.track_densities[t];
        }
    }
    if (motif.swing_override >= 0.0f) {
        genre.swing_amount = motif.swing_override;
    }
    if (motif.ghost_probability >= 0.0f) {
        genre.ghost_probability = motif.ghost_probability;
    }
    if (motif.rhythm_density >= 0.0f) {
        genre.rhythm_density = motif.rhythm_density;
    }
}

inline bool advance_motif(MotifState& state, const MotifSetParam& set, uint32_t& seed) {
    if (set.motif_count <= 1) return false;

    for (int i = 0; i < set.motif_count; i++) {
        state.bars_since_motif[i]++;
    }
    state.bars_since_motif[state.current_motif] = 0;

    state.bars_remaining--;
    if (state.bars_remaining > 0) return false;

    // Reset timer
    int range = set.bars_per_motif_max - set.bars_per_motif_min + 1;
    int new_bars = set.bars_per_motif_min;
    if (range > 1) new_bars += static_cast<int>(pattern_rand01(seed) * range) % range;

    if (pattern_rand01(seed) > set.switch_probability) {
        state.bars_remaining = new_bars;
        return false;
    }

    int next = select_next_motif(set, state, state.current_motif, seed);
    if (next == state.current_motif) {
        state.bars_remaining = new_bars;
        return false;
    }

    state.current_motif = next;
    state.bars_remaining = new_bars;
    return true;
}
