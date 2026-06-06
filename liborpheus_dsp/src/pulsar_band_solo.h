#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"  // for pattern_rand01
#include "pulsar_solo.h"         // for clear_solo_modifiers
#include <cstring>
#include <algorithm>

// ---------------------------------------------------------------------------
// Band-based solo state machine for Pulsar arrangement system
//
// Band members are groups of tracks that solo together. The system uses:
// - NxN Markov handoff matrix for lead selection
// - NxN pull-in matrix for combo formation
// - Always-active members (e.g. drums) simplify but never fully duck
// ---------------------------------------------------------------------------

// ── Select initial lead member (weighted random, prefer non-always-active) ──

inline int select_initial_lead(
    const BandSoloConfigParam& config,
    uint32_t& seed
) {
    float weights[kMaxBandMembers];
    float total = 0.0f;

    for (int i = 0; i < config.member_count; i++) {
        // Bound the roll toward improvisational intent: creative members lead more
        // often. always_active (drums) never lead. Floor keeps every member possible.
        weights[i] = config.members[i].always_active
            ? 0.0f
            : (0.3f + config.members[i].creativity);
        total += weights[i];
    }

    if (total <= 0.0f) {
        // Degenerate: every member is always_active, so no melodic member exists
        // to lead. Someone must be the nominal lead; pick uniformly rather than
        // always member 0 so the choice at least varies. (The "always_active never
        // leads" guarantee is vacuous when there is no non-always-active member.)
        if (config.member_count <= 0) return 0;
        return static_cast<int>(pattern_rand01(seed) * config.member_count) % config.member_count;
    }

    float roll = pattern_rand01(seed) * total;
    float cumulative = 0.0f;
    for (int i = 0; i < config.member_count; i++) {
        cumulative += weights[i];
        if (roll <= cumulative) return i;
    }
    return config.member_count - 1;
}

// ── Select next lead via Markov handoff matrix with recency decay ────

inline int select_next_lead(
    const BandSoloConfigParam& config,
    const BandSoloState& state,
    uint32_t& seed
) {
    int from = state.lead_member;
    if (from < 0 || from >= config.member_count) from = 0;

    float weights[kMaxBandMembers];
    float total = 0.0f;

    for (int i = 0; i < config.member_count; i++) {
        float base = config.handoff_matrix[from * kMaxBandMembers + i];
        if (base <= 0.0f) { weights[i] = 0.0f; continue; }

        // Recency decay: members that led recently get lower weight
        int bars_ago = state.bars_since_lead[i];
        float recency = 1.0f;
        const float kDecay = 0.85f;
        for (int p = 0; p < bars_ago && p < 32; p++)
            recency *= kDecay;
        weights[i] = base * (0.05f + 0.95f * (1.0f - recency));
        total += weights[i];
    }

    if (total <= 0.0f) {
        // Fallback: hand OFF — never re-pick the current lead; prefer least-recent.
        for (int i = 0; i < config.member_count; i++) {
            if (i == from) { weights[i] = 0.0f; continue; }
            float base = config.members[i].always_active ? 0.0f : 1.0f;
            int bars_ago = state.bars_since_lead[i];
            float recency = 1.0f;
            const float kDecay = 0.85f;
            for (int p = 0; p < bars_ago && p < 32; p++) recency *= kDecay;
            weights[i] = base * (0.05f + 0.95f * (1.0f - recency));
            total += weights[i];
        }
        if (total <= 0.0f) {  // degenerate (e.g. all always-active): allow any non-self
            for (int i = 0; i < config.member_count; i++) {
                if (i == from) continue;
                weights[i] = 1.0f;
                total += 1.0f;
            }
        }
    }

    if (total <= 0.0f) return 0;

    float roll = pattern_rand01(seed) * total;
    float cumulative = 0.0f;
    for (int i = 0; i < config.member_count; i++) {
        cumulative += weights[i];
        if (roll <= cumulative) return i;
    }
    return config.member_count - 1;
}

// ── Apply band solo modifiers to per-track state ─────────────────────

inline void apply_band_solo_modifiers(
    PulsarTrackState* tracks,
    const BandSoloConfigParam& config,
    const BandSoloState& state,
    int num_tracks = kNumPulsarTracks
) {
    // Build track-to-member lookup (-1 = not in any member)
    int track_member[kNumPulsarTracks];
    for (int t = 0; t < num_tracks; t++) track_member[t] = -1;

    for (int m = 0; m < config.member_count; m++) {
        for (int ti = 0; ti < config.members[m].track_count; ti++) {
            int t = config.members[m].tracks[ti];
            if (t >= 0 && t < num_tracks) {
                track_member[t] = m;
            }
        }
    }

    for (int t = 0; t < num_tracks; t++) {
        int m = track_member[t];

        if (m < 0) {
            // Track not in any member: standard ducking
            tracks[t].is_soloist = false;
            tracks[t].solo_volume_mod = -0.3f;
            tracks[t].solo_density_mod = -0.4f;
            tracks[t].solo_ghost_mod = -0.5f;
            tracks[t].solo_fill_mod = -0.7f;
            tracks[t].solo_simplify = true;
            tracks[t].solo_reverb_mod = 0.1f;
            continue;
        }

        MemberSoloRole role = state.member_role[m];
        bool always_active = config.members[m].always_active;

        switch (role) {
            case MemberSoloRole::LEADING: {
                float loud = config.members[m].loudness;
                tracks[t].is_soloist = true;
                tracks[t].solo_volume_mod = 0.1f + 0.2f * loud;
                tracks[t].solo_density_mod = 0.15f + 0.3f * loud;
                tracks[t].solo_ghost_mod = 0.0f;
                tracks[t].solo_fill_mod = 0.3f + 0.5f * loud;
                tracks[t].solo_simplify = false;
                tracks[t].solo_reverb_mod = 0.0f;
                break;
            }

            case MemberSoloRole::ACTIVE:
                tracks[t].is_soloist = false;
                tracks[t].solo_volume_mod = 0.1f;
                tracks[t].solo_density_mod = 0.15f;
                tracks[t].solo_ghost_mod = 0.0f;
                tracks[t].solo_fill_mod = 0.0f;
                tracks[t].solo_simplify = false;
                tracks[t].solo_reverb_mod = 0.0f;
                break;

            case MemberSoloRole::SUPPORT:
                if (always_active) {
                    // Always-active support: simplify but no volume duck
                    tracks[t].is_soloist = false;
                    tracks[t].solo_volume_mod = 0.0f;
                    tracks[t].solo_density_mod = -0.15f;
                    tracks[t].solo_ghost_mod = 0.0f;
                    tracks[t].solo_fill_mod = 0.0f;
                    tracks[t].solo_simplify = true;
                    tracks[t].solo_reverb_mod = 0.0f;
                } else {
                    // Standard ducking for non-always-active support
                    tracks[t].is_soloist = false;
                    tracks[t].solo_volume_mod = -0.3f;
                    tracks[t].solo_density_mod = -0.4f;
                    tracks[t].solo_ghost_mod = -0.5f;
                    tracks[t].solo_fill_mod = -0.7f;
                    tracks[t].solo_simplify = true;
                    tracks[t].solo_reverb_mod = 0.1f;
                }
                break;
        }
    }
}

// ── Start band solo with SoloMode-aware dispatch ────────────────────

inline void start_band_solo(
    BandSoloState& state,
    const BandSoloConfigParam& config,
    const SectionParam& section,
    PulsarTrackState* tracks,
    uint32_t& seed,
    int num_tracks = kNumPulsarTracks
) {
    if (section.solo_mode == SoloModeId::NONE) {
        state.active = false;
        clear_solo_modifiers(tracks, num_tracks);
        return;
    }

    // Probability gate uses section's solo probability
    if (pattern_rand01(seed) > section.solo_probability) {
        state.active = false;
        clear_solo_modifiers(tracks, num_tracks);
        return;
    }

    state.active = true;
    state.phrase_cursor = 0;
    std::memset(state.last_phrase, -1, sizeof(state.last_phrase));
    state.solo_seed = seed;

    state.lead_member = select_initial_lead(config, seed);

    // LongFill uses section bars, LickBuilder/Jam uses band's barsPerLead
    int bars_min, bars_max;
    if (section.solo_mode == SoloModeId::LONG_FILL) {
        bars_min = section.solo_bars_min;
        bars_max = section.solo_bars_max;
    } else {
        bars_min = config.bars_per_lead_min;
        bars_max = config.bars_per_lead_max;
    }
    int range = bars_max - bars_min + 1;
    if (range < 1) range = 1;
    int lead_bars = bars_min + static_cast<int>(pattern_rand01(seed) * range) % range;

    for (int m = 0; m < config.member_count; m++) {
        state.member_bars_remaining[m] = 0;

        if (m == state.lead_member) {
            state.member_role[m] = MemberSoloRole::LEADING;
            state.member_bars_remaining[m] = lead_bars;
            state.bars_since_lead[m] = 0;   // just became lead
        } else {
            state.member_role[m] = MemberSoloRole::SUPPORT;
            // Never-led members are "due": seed bars_since_lead at the recency
            // decay cap (32) so the first handoff's recency weighting — and the
            // empty-row fallback — actually prefer them, instead of treating
            // bars_since_lead==0 as "just led" and collapsing everyone to the floor.
            state.bars_since_lead[m] = 32;
        }
    }

    apply_band_solo_modifiers(tracks, config, state, num_tracks);
}

// ── Advance band solo with SoloMode-aware dispatch ──────────────────

inline void advance_band_solo(
    BandSoloState& state,
    const BandSoloConfigParam& config,
    const SectionParam& section,
    PulsarTrackState* tracks,
    uint32_t& seed,
    int num_tracks = kNumPulsarTracks
) {
    if (!state.active) return;

    for (int m = 0; m < config.member_count; m++) {
        state.bars_since_lead[m]++;
    }
    if (state.lead_member >= 0) {
        state.bars_since_lead[state.lead_member] = 0;
    }

    for (int m = 0; m < config.member_count; m++) {
        if (state.member_bars_remaining[m] > 0) {
            state.member_bars_remaining[m]--;
        }
    }

    // LongFill: no handoff, end when bars expire
    if (section.solo_mode == SoloModeId::LONG_FILL) {
        if (state.lead_member >= 0 &&
            state.member_bars_remaining[state.lead_member] <= 0) {
            state.active = false;
            clear_solo_modifiers(tracks, num_tracks);
        }
        return;
    }

    // LickBuilder and Jam: pull-ins and handoffs (same as existing advance logic)

    // Drop expired pull-ins
    for (int m = 0; m < config.member_count; m++) {
        if (m == state.lead_member) continue;
        if (state.member_role[m] == MemberSoloRole::ACTIVE &&
            state.member_bars_remaining[m] <= 0) {
            state.member_role[m] = MemberSoloRole::SUPPORT;
        }
    }

    // Roll new pull-ins
    if (state.lead_member >= 0) {
        for (int m = 0; m < config.member_count; m++) {
            if (m == state.lead_member) continue;
            if (state.member_role[m] != MemberSoloRole::SUPPORT) continue;
            if (config.members[m].always_active) continue;

            float prob = config.pull_in_matrix[state.lead_member * kMaxBandMembers + m];
            if (prob > 0.0f && pattern_rand01(seed) < prob) {
                state.member_role[m] = MemberSoloRole::ACTIVE;
                int pi_range = config.pull_in_bars_max - config.pull_in_bars_min + 1;
                if (pi_range < 1) pi_range = 1;
                state.member_bars_remaining[m] = config.pull_in_bars_min +
                    static_cast<int>(pattern_rand01(seed) * pi_range) % pi_range;
            }
        }
    }

    // Handle lead expiry and handoff
    if (state.lead_member >= 0 &&
        state.member_bars_remaining[state.lead_member] <= 0) {
        state.member_role[state.lead_member] = MemberSoloRole::SUPPORT;

        int next = select_next_lead(config, state, seed);
        state.lead_member = next;
        state.member_role[next] = MemberSoloRole::LEADING;

        int range = config.bars_per_lead_max - config.bars_per_lead_min + 1;
        if (range < 1) range = 1;
        state.member_bars_remaining[next] = config.bars_per_lead_min +
            static_cast<int>(pattern_rand01(seed) * range) % range;

        state.phrase_cursor = 0;
        std::memset(state.last_phrase, -1, sizeof(state.last_phrase));
    }

    apply_band_solo_modifiers(tracks, config, state, num_tracks);
}

// ── Clear band solo state and modifiers ──────────────────────────────

inline void clear_band_solo(
    BandSoloState& state,
    PulsarTrackState* tracks,
    int num_tracks = kNumPulsarTracks
) {
    state.active = false;
    state.lead_member = -1;
    state.phrase_cursor = 0;
    for (int m = 0; m < kMaxBandMembers; m++) {
        state.member_role[m] = MemberSoloRole::SUPPORT;
        state.member_bars_remaining[m] = 0;
        state.bars_since_lead[m] = 0;
    }
    std::memset(state.last_phrase, -1, sizeof(state.last_phrase));

    clear_solo_modifiers(tracks, num_tracks);
}
