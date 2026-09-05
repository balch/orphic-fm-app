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

    // Zero-initialised: an out-of-range target `continue`s without writing its slot.
    float weights[kMaxSectionTransitions] = {};
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
        // A negative authored weight (or recency_decay > 1) would be summed into total but
        // skipped by the selection loop, leaving the two integrating different distributions.
        if (weights[i] < 0.0f) weights[i] = 0.0f;
        total += weights[i];
    }

    if (total <= 0.0f) return current;

    // Skip zero-weight slots so an out-of-range target can never be returned: roll == 0
    // satisfies `roll <= cumulative` at a skipped slot, and the old fall-through returned the
    // last transition unconditionally. total > 0 guarantees last_weighted gets assigned.
    float roll = pattern_rand01(seed) * total;
    float cumulative = 0.0f;
    int last_weighted = current;
    for (int i = 0; i < sec.transition_count; i++) {
        if (weights[i] <= 0.0f) continue;
        cumulative += weights[i];
        if (roll <= cumulative) {
            return sec.transitions[i].target_index;
        }
        last_weighted = sec.transitions[i].target_index;
    }
    // Reached only when float accumulation leaves roll just past the final cumulative.
    return last_weighted;
}

inline int randomize_section_bars(const SectionParam& sec, uint32_t& seed) {
    if (sec.bars_min >= sec.bars_max) return sec.bars_min;
    int step = (sec.bar_step >= 1) ? sec.bar_step : 1;
    // Number of valid stops in [bars_min, bars_max] at given step:
    //   stop_count = floor((bars_max - bars_min) / step) + 1
    int stop_count = (sec.bars_max - sec.bars_min) / step + 1;
    if (stop_count <= 1) return sec.bars_min;
    // pattern_rand01 returns [0, 1] *inclusive* of 1.0 (it divides by 0x7FFFFF,
    // not 0x800000), so the cast can land on stop_count itself. Clamp the top
    // edge — without this, the rare 1.0 roll overshoots bars_max by one step.
    // idx == 0 still maps to bars_min, so the minimum length stays reachable.
    int idx = static_cast<int>(pattern_rand01(seed) * stop_count);
    if (idx >= stop_count) idx = stop_count - 1;
    return sec.bars_min + idx * step;
}

// Look up the per-edge transition_bars for the edge from src to target_index.
// Returns 0 (hard cut) when no matching outgoing edge exists.
inline int find_edge_transition_bars(const SectionParam& src, int target_index) {
    for (int i = 0; i < src.transition_count; i++) {
        if (src.transitions[i].target_index == target_index) {
            return src.transitions[i].transition_bars;
        }
    }
    return 0;
}

// Pick the next section the moment the current one becomes active, and stash
// the chosen edge's transition_bars. The pre-roll ramp zone occupies the LAST
// `next_section_trans_bars` bars of the current section.
inline void plan_next_section(SectionState& state, const ArrangementParams& arr, uint32_t& seed) {
    int next = select_next_section(arr, state, state.current_section, seed);
    state.next_section_planned = next;
    state.next_section_trans_bars =
        (next >= 0 && next < arr.section_count)
            ? find_edge_transition_bars(arr.sections[state.current_section], next)
            : 0;
}

inline void init_section_state(SectionState& state, const ArrangementParams& arr, uint32_t& seed) {
    std::memset(&state, 0, sizeof(SectionState));
    state.transition_target = -1;
    state.target_energy = -1.0f;
    state.target_complexity = -1.0f;
    state.target_space = -1.0f;
    state.target_mood = -1.0f;
    state.next_energy = -1.0f;
    state.next_complexity = -1.0f;
    state.next_space = -1.0f;
    state.next_mood = -1.0f;
    state.next_section_planned = -1;
    state.next_section_trans_bars = 0;
    state.pending_section_request = -1;

    if (!arr.active || arr.section_count == 0) return;

    if (arr.intro_index >= 0 && arr.intro_index < arr.section_count) {
        state.current_section = arr.intro_index;
        state.intro_done = false;
    } else {
        state.current_section = static_cast<int>(pattern_rand01(seed) * arr.section_count) % arr.section_count;
        state.intro_done = true;
    }

    state.bars_remaining = randomize_section_bars(arr.sections[state.current_section], seed);
    state.bars_total = state.bars_remaining;
    plan_next_section(state, arr, seed);
}

// Returns true if a section change occurred this bar.
//
// Pre-roll model: each bar, decrement bars_remaining. While bars_remaining is
// less than the planned next edge's transition_bars, blend macros toward the
// destination via section_macro_value(). At bars_remaining == 0, hard-flip to
// the planned next section and pre-select the section after that.
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

    state.bars_remaining--;

    // Pre-roll ramp zone: the last N bars of the current section, where
    // N = the planned outgoing edge's transition_bars (0 = no ramp).
    int N = state.next_section_trans_bars;
    int planned = state.next_section_planned;
    if (N > 0 && planned >= 0 && planned < arr.section_count
        && state.bars_remaining < N) {
        // First bar of ramp: stage destination overrides into next_*.
        if (state.transition_target < 0) {
            state.transition_target = planned;
            const MacroOverridesParam& dst = arr.sections[planned].macro_overrides;
            state.next_energy     = dst.energy;
            state.next_complexity = dst.complexity;
            state.next_space      = dst.space;
            state.next_mood       = dst.mood;
        }
        // Linear progress 0 -> 1 across N bars: when bars_remaining == N-1 the
        // first bar of the ramp has just elapsed so progress = 1/N; at
        // bars_remaining == 0 progress = N/N = 1.0 (boundary about to flip).
        int into_ramp = N - state.bars_remaining;
        if (into_ramp > N) into_ramp = N;
        state.transition_progress = static_cast<float>(into_ramp) / static_cast<float>(N);
    }

    if (state.bars_remaining > 0) return false;

    // Section content has fully elapsed -> hard flip to the planned next section.
    int next = (planned >= 0 && planned < arr.section_count)
        ? planned
        : select_next_section(arr, state, state.current_section, seed);

    // outro_triggered may have been set mid-section, after we already planned
    // toward a different target. Re-route at the boundary; the ramp may have
    // morphed toward the wrong destination, but the outro takes precedence.
    // Bounds-checked like intro_index: outro_index is authored and unpacked unclamped, and
    // current_section indexes both arr.sections[] and state.bars_since_visit[kMaxSections].
    if (state.outro_triggered && arr.outro_index >= 0 && arr.outro_index < arr.section_count) {
        next = arr.outro_index;
    }

    // An explicit request outranks the outro: it is the more recent and more specific
    // instruction, and both self-clear once consumed.
    if (state.pending_section_request >= 0
        && state.pending_section_request < arr.section_count) {
        next = state.pending_section_request;
    }
    state.pending_section_request = -1;

    state.current_section     = next;
    state.transition_target   = -1;
    state.transition_progress = 0.0f;
    // Don't propagate next_* here; the audio path's section_changed handler
    // re-reads target_* from arr.sections[next].macro_overrides directly.
    // Just clear the staging slots so future macro reads don't blend stale values.
    state.next_energy       = -1.0f;
    state.next_complexity   = -1.0f;
    state.next_space        = -1.0f;
    state.next_mood         = -1.0f;
    state.bars_remaining = randomize_section_bars(arr.sections[next], seed);
    state.bars_total = state.bars_remaining;

    if (!state.intro_done && next != arr.intro_index) {
        state.intro_done = true;
    }

    // Pre-plan the section that will follow the one we just entered.
    plan_next_section(state, arr, seed);

    return true;
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
