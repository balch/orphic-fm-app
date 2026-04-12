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
        // Prefer non-always-active members as initial lead
        weights[i] = config.members[i].always_active ? 0.1f : 1.0f;
        total += weights[i];
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
        // Fallback: uniform among non-always-active
        for (int i = 0; i < config.member_count; i++) {
            weights[i] = config.members[i].always_active ? 0.1f : 1.0f;
            total += weights[i];
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
            case MemberSoloRole::LEADING:
                tracks[t].is_soloist = true;
                tracks[t].solo_volume_mod = 0.2f;
                tracks[t].solo_density_mod = 0.3f;
                tracks[t].solo_ghost_mod = 0.0f;
                tracks[t].solo_fill_mod = 0.6f;
                tracks[t].solo_simplify = false;
                tracks[t].solo_reverb_mod = 0.0f;
                break;

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

// ── Start band solo ──────────────────────────────────────────────────

inline void start_band_solo(
    BandSoloState& state,
    const BandSoloConfigParam& config,
    PulsarTrackState* tracks,
    uint32_t& seed,
    int num_tracks = kNumPulsarTracks
) {
    // Probability gate
    if (pattern_rand01(seed) > config.probability) {
        state.active = false;
        clear_solo_modifiers(tracks, num_tracks);
        return;
    }

    state.active = true;
    state.phrase_cursor = 0;
    std::memset(state.last_phrase, -1, sizeof(state.last_phrase));
    state.solo_seed = seed;

    // Select initial lead
    state.lead_member = select_initial_lead(config, seed);

    // Set lead duration
    int range = config.bars_per_lead_max - config.bars_per_lead_min + 1;
    int lead_bars = config.bars_per_lead_min +
                    static_cast<int>(pattern_rand01(seed) * range) % range;

    // Initialize all member roles and state
    for (int m = 0; m < config.member_count; m++) {
        state.bars_since_lead[m] = 0;
        state.member_bars_remaining[m] = 0;
        state.active_technique[m] = config.members[m].default_technique;

        if (m == state.lead_member) {
            state.member_role[m] = MemberSoloRole::LEADING;
            state.member_bars_remaining[m] = lead_bars;
        } else if (config.members[m].always_active) {
            state.member_role[m] = MemberSoloRole::SUPPORT;
        } else {
            state.member_role[m] = MemberSoloRole::SUPPORT;
        }
    }

    apply_band_solo_modifiers(tracks, config, state, num_tracks);
}

// ── Advance band solo (called per bar) ───────────────────────────────

inline void advance_band_solo(
    BandSoloState& state,
    const BandSoloConfigParam& config,
    PulsarTrackState* tracks,
    uint32_t& seed,
    int num_tracks = kNumPulsarTracks
) {
    if (!state.active) return;

    // Update bars_since_lead for all members
    for (int m = 0; m < config.member_count; m++) {
        state.bars_since_lead[m]++;
    }
    if (state.lead_member >= 0) {
        state.bars_since_lead[state.lead_member] = 0;
    }

    // Decrement countdowns for all members
    for (int m = 0; m < config.member_count; m++) {
        if (state.member_bars_remaining[m] > 0) {
            state.member_bars_remaining[m]--;
        }
    }

    // Drop expired pull-ins (ACTIVE members whose bars expired)
    for (int m = 0; m < config.member_count; m++) {
        if (m == state.lead_member) continue;
        if (state.member_role[m] == MemberSoloRole::ACTIVE &&
            state.member_bars_remaining[m] <= 0) {
            state.member_role[m] = MemberSoloRole::SUPPORT;
        }
    }

    // Roll new pull-ins from the lead member's pull-in row
    if (state.lead_member >= 0) {
        for (int m = 0; m < config.member_count; m++) {
            if (m == state.lead_member) continue;
            if (state.member_role[m] != MemberSoloRole::SUPPORT) continue;
            if (config.members[m].always_active) continue;

            float prob = config.pull_in_matrix[state.lead_member * kMaxBandMembers + m];
            if (prob > 0.0f && pattern_rand01(seed) < prob) {
                state.member_role[m] = MemberSoloRole::ACTIVE;
                int pi_range = config.pull_in_bars_max - config.pull_in_bars_min + 1;
                state.member_bars_remaining[m] = config.pull_in_bars_min +
                    static_cast<int>(pattern_rand01(seed) * pi_range) % pi_range;
            }
        }
    }

    // Handle lead expiry and handoff
    if (state.lead_member >= 0 &&
        state.member_bars_remaining[state.lead_member] <= 0) {
        // Demote current lead
        state.member_role[state.lead_member] = MemberSoloRole::SUPPORT;

        // Select next lead
        int next = select_next_lead(config, state, seed);
        state.lead_member = next;
        state.member_role[next] = MemberSoloRole::LEADING;

        int range = config.bars_per_lead_max - config.bars_per_lead_min + 1;
        state.member_bars_remaining[next] = config.bars_per_lead_min +
            static_cast<int>(pattern_rand01(seed) * range) % range;

        // Reset phrase for new lead
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
        state.active_technique[m] = SoloTechniqueId::STANDARD_MARKOV;
    }
    std::memset(state.last_phrase, -1, sizeof(state.last_phrase));

    clear_solo_modifiers(tracks, num_tracks);
}
