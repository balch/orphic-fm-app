#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"  // for pattern_rand01

// ---------------------------------------------------------------------------
// Section state machine for Pulsar arrangement system
// ---------------------------------------------------------------------------

inline int select_next_section(
    const ArrangementParams& arr,
    const SectionState& state,
    int current,
    uint32_t& seed
) {
    const SectionParam& sec = arr.sections[current];
    if (sec.transition_count == 0) return current;

    float weights[kMaxSectionTransitions];
    float total = 0.0f;
    for (int i = 0; i < sec.transition_count; i++) {
        int target = sec.transitions[i].target_index;
        if (target < 0 || target >= arr.section_count) continue;
        float base_weight = sec.transitions[i].weight;
        int bars_ago = state.bars_since_visit[target];
        float recency = 1.0f;
        for (int p = 0; p < bars_ago && p < 32; p++) {
            recency *= sec.recency_decay;
        }
        weights[i] = base_weight * (0.05f + 0.95f * (1.0f - recency));
        total += weights[i];
    }

    if (total <= 0.0f) return current;

    float roll = pattern_rand01(seed) * total;
    float cumulative = 0.0f;
    for (int i = 0; i < sec.transition_count; i++) {
        cumulative += weights[i];
        if (roll <= cumulative) {
            return sec.transitions[i].target_index;
        }
    }
    return sec.transitions[sec.transition_count - 1].target_index;
}

inline int randomize_section_bars(const SectionParam& sec, uint32_t& seed) {
    if (sec.bars_min >= sec.bars_max) return sec.bars_min;
    int range = sec.bars_max - sec.bars_min + 1;
    return sec.bars_min + static_cast<int>(pattern_rand01(seed) * range) % range;
}

inline void init_section_state(SectionState& state, const ArrangementParams& arr, uint32_t& seed) {
    std::memset(&state, 0, sizeof(SectionState));
    state.transition_target = -1;
    state.target_energy = -1.0f;
    state.target_complexity = -1.0f;
    state.target_space = -1.0f;
    state.target_mood = -1.0f;

    if (!arr.active || arr.section_count == 0) return;

    if (arr.intro_index >= 0 && arr.intro_index < arr.section_count) {
        state.current_section = arr.intro_index;
        state.intro_done = false;
    } else {
        state.current_section = static_cast<int>(pattern_rand01(seed) * arr.section_count) % arr.section_count;
        state.intro_done = true;
    }

    state.bars_remaining = randomize_section_bars(arr.sections[state.current_section], seed);
}

// Returns true if a section change occurred
inline bool advance_section(
    SectionState& state,
    const ArrangementParams& arr,
    uint32_t& seed
) {
    if (!arr.active || arr.section_count <= 1) return false;

    for (int i = 0; i < arr.section_count; i++) {
        state.bars_since_visit[i]++;
    }
    state.bars_since_visit[state.current_section] = 0;

    // Handle transition ramp in progress
    if (state.transition_target >= 0) {
        int trans_bars = arr.sections[state.current_section].transition_bars;
        if (trans_bars > 0) {
            state.transition_progress += 1.0f / static_cast<float>(trans_bars);
        } else {
            state.transition_progress = 1.0f;
        }

        if (state.transition_progress >= 1.0f) {
            state.current_section = state.transition_target;
            state.transition_target = -1;
            state.transition_progress = 0.0f;
            state.bars_remaining = randomize_section_bars(arr.sections[state.current_section], seed);
            if (!state.intro_done && state.current_section != arr.intro_index) {
                state.intro_done = true;
            }
            return true;
        }
        return false;
    }

    state.bars_remaining--;
    if (state.bars_remaining > 0) return false;

    int next = select_next_section(arr, state, state.current_section, seed);

    if (state.outro_triggered && arr.outro_index >= 0) {
        next = arr.outro_index;
    }

    int trans_bars = arr.sections[state.current_section].transition_bars;
    if (trans_bars > 0) {
        state.transition_target = next;
        state.transition_progress = 0.0f;
        return false;
    } else {
        state.current_section = next;
        state.bars_remaining = randomize_section_bars(arr.sections[next], seed);
        if (!state.intro_done && next != arr.intro_index) {
            state.intro_done = true;
        }
        return true;
    }
}

inline float section_macro_value(
    float base_value,
    float current_override,
    float target_override,
    float transition_progress
) {
    float current = (current_override >= 0.0f) ? current_override : base_value;
    float target = (target_override >= 0.0f) ? target_override : base_value;
    if (transition_progress <= 0.0f) return current;
    return current + (target - current) * transition_progress;
}
