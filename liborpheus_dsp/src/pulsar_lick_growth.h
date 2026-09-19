#pragma once

// How grown ghosts survive a figure re-render. Where a ghost takes its pitch lives in
// pulsar_pattern_gen.h (ghost_pitch_source), which every ghost path shares.

#include "pulsar_pattern_gen.h"

namespace lick_growth {

// Copy `before`'s ghosts onto steps that are empty in `after`, then pitch them from `after`.
// A second pass assigns pitches so one carried ghost never sources another.
inline int carry_ghosts(const PulsarStep* before, int before_count,
                        PulsarStep* after, int after_count) {
    const int n = before_count < after_count ? before_count : after_count;
    int carried = 0;
    for (int s = 0; s < n; s++) {
        if (!before[s].gate || !before[s].ghost || after[s].gate) continue;
        after[s].gate = true;
        after[s].ghost = true;
        after[s].hold = false;
        after[s].velocity = before[s].velocity;
        after[s].duration = before[s].duration;
        after[s].glide_rate = -1.0f;
        after[s].hit_probability = 1.0f;
        carried++;
    }
    for (int s = 0; s < n; s++) {
        if (!after[s].ghost) continue;
        const int src = ghost_pitch_source(after, after_count, s);
        if (src < 0) continue;
        after[s].note = after[src].note;
        after[s].raw_note = after[src].raw_note;
    }
    return carried;
}

}  // namespace lick_growth
