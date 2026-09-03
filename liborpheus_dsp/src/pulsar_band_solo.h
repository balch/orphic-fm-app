#pragma once

#include "orpheus_unit_pulsar.h"
#include "pulsar_pattern_gen.h"  // for pattern_rand01
#include "pulsar_solo.h"         // for clear_solo_modifiers
#include "pulsar_handoff.h"      // for should_drum_lead, pick_drum_lead_style, DrumLeadStyle
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

// EAR TUNE: how often a soloist handoff is punctuated by a kit fill. The pass is
// an event, so the answer is probabilistic — every time would read as a tic.
inline constexpr float kHandoffFillChance = 0.6f;
// EAR TUNE: how far the kit sheds its duck for that bar, in solo_fill_mod units.
// 0.45 fully clears the -0.15/-0.2 support ducks, so the fill plays at full density.
inline constexpr float kHandoffFillDepth = 0.45f;

// ── Select initial lead member (weighted random, prefer non-always-active) ──

// True if member m owns at least one MELODIC track — i.e. it can host a JAM solo
// LINE. A chordal-only or percussion-only member cannot (generate_jam_solo_line would
// no-op into a dead solo), so it must not be chosen as a JAM lead.
inline bool member_can_lead_solo(const BandSoloConfigParam& config, int m,
                                 const PulsarTrackState* tracks, int num_tracks) {
    if (m < 0 || m >= config.member_count) return false;
    const BandMemberParam& mem = config.members[m];
    for (int ti = 0; ti < mem.track_count; ti++) {
        int rt = mem.tracks[ti];
        if (rt >= 0 && rt < num_tracks && tracks[rt].role == TrackRole::MELODIC) return true;
    }
    return false;
}

// Fill out[member] with JAM solo eligibility (non-drum AND owns a melodic track).
// Returns out, or nullptr when no member qualifies — the caller then passes nullptr
// and keeps the unfiltered selection rather than deadlocking on an empty candidate set.
inline const bool* build_solo_eligibility(const BandSoloConfigParam& config,
                                          const PulsarTrackState* tracks, int num_tracks,
                                          bool* out) {
    int eligible = 0;
    for (int m = 0; m < config.member_count; m++) {
        out[m] = !config.members[m].always_active &&
                 member_can_lead_solo(config, m, tracks, num_tracks);
        if (out[m]) eligible++;
    }
    return (eligible > 0) ? out : nullptr;
}

inline int select_initial_lead(
    const BandSoloConfigParam& config,
    uint32_t& seed,
    const bool* eligible = nullptr   // JAM: only members that can host a solo line
) {
    float weights[kMaxBandMembers];
    float total = 0.0f;

    for (int i = 0; i < config.member_count; i++) {
        // Bound the roll toward improvisational intent: creative members lead more
        // often. always_active (drums) never lead. Floor keeps every member possible.
        weights[i] = (config.members[i].always_active || (eligible && !eligible[i]))
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
    uint32_t& seed,
    const bool* eligible = nullptr   // JAM: only members that can host a solo line
) {
    int from = state.lead_member;
    if (from < 0 || from >= config.member_count) from = 0;

    float weights[kMaxBandMembers];
    float total = 0.0f;

    for (int i = 0; i < config.member_count; i++) {
        if (i == from) { weights[i] = 0.0f; continue; }                 // never self-handoff
        if (config.members[i].always_active) { weights[i] = 0.0f; continue; } // drums don't lead via the normal path
        if (eligible && !eligible[i]) { weights[i] = 0.0f; continue; }  // JAM: must host a solo line
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
            if (eligible && !eligible[i]) { weights[i] = 0.0f; continue; }  // keep the JAM filter
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

// The duck a fully-ducked track takes when its vibe authored no DuckingProfile.
// These are the values every band vibe has always used, expressed as REDUCTIONS to
// match the Kotlin sign convention. Kotlin mirrors them as DuckingProfile's defaults.
inline constexpr float kUnauthoredDuckVolume  = 0.18f;
inline constexpr float kUnauthoredDuckDensity = 0.2f;
inline constexpr float kUnauthoredDuckGhost   = 0.35f;
inline constexpr float kUnauthoredDuckFill    = 0.35f;
inline constexpr float kUnauthoredDuckReverb  = 0.1f;

// Standard ducking for one track: authored profile if the vibe declared one, else the
// constants above. Kotlin authors REDUCTIONS, the mods are signed offsets, so the
// whole set flips sign. `dp == nullptr` means the caller has no bank at all.
// Only solo_density_mod, solo_simplify and solo_fill_mod reach the render today;
// volume/ghost/reverb are set here and read by nothing (DuckingProfile's KDoc says so).
inline void apply_duck_modifiers(PulsarTrackState& ts, const DuckingParam* dp) {
    const bool authored = (dp != nullptr && dp->declared);
    ts.is_soloist       = false;
    ts.solo_volume_mod  = -(authored ? dp->volume_reduction  : kUnauthoredDuckVolume);
    ts.solo_density_mod = -(authored ? dp->density_reduction : kUnauthoredDuckDensity);
    ts.solo_ghost_mod   = -(authored ? dp->ghost_reduction   : kUnauthoredDuckGhost);
    ts.solo_fill_mod    = -(authored ? dp->fill_suppression  : kUnauthoredDuckFill);
    ts.solo_simplify    =  (authored ? dp->simplify          : true);
    ts.solo_reverb_mod  =  (authored ? dp->reverb_boost      : kUnauthoredDuckReverb);
}

inline void apply_band_solo_modifiers(
    PulsarTrackState* tracks,
    const BandSoloConfigParam& config,
    const BandSoloState& state,
    int num_tracks = kNumPulsarTracks,
    const DuckingParam* track_ducking = nullptr   // kNumPulsarTracks entries, or nullptr
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
        const DuckingParam* dp = track_ducking ? &track_ducking[t] : nullptr;

        if (m < 0) {
            // Track not in any member: standard ducking
            apply_duck_modifiers(tracks[t], dp);
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
                    // Always-active support: simplify but no volume duck. Deliberately
                    // NOT profile-driven — "the kit never fully steps back" is a band
                    // rule, and an authored per-track duck must not undo it.
                    tracks[t].is_soloist = false;
                    tracks[t].solo_volume_mod = 0.0f;
                    tracks[t].solo_density_mod = -0.15f;
                    tracks[t].solo_ghost_mod = 0.0f;
                    tracks[t].solo_fill_mod = 0.0f;
                    tracks[t].solo_simplify = true;
                    tracks[t].solo_reverb_mod = 0.0f;
                } else {
                    // Standard ducking for non-always-active support
                    apply_duck_modifiers(tracks[t], dp);
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
    int num_tracks = kNumPulsarTracks,
    const DuckingParam* track_ducking = nullptr   // per-track authored duck, or nullptr
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
    state.pending_lead = -1;
    std::memset(state.last_phrase, -1, sizeof(state.last_phrase));
    state.solo_seed = seed;
    state.drum_lead_style = -1;
    state.last_handoff_was_drum = false;
    state.solo_lick_octave = -1;
    state.outgoing_last_note = -1;
    state.just_handed_off = false;
    state.bars_elapsed = 0;

    // JAM solos render an improvised melodic LINE, so the lead must own a melodic
    // track; a chordal-only member would no-op into a dead solo. Filter it out.
    bool jam_elig[kMaxBandMembers];
    const bool* elig = (section.solo_mode == SoloModeId::JAM)
        ? build_solo_eligibility(config, tracks, num_tracks, jam_elig) : nullptr;
    state.lead_member = select_initial_lead(config, seed, elig);

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

    apply_band_solo_modifiers(tracks, config, state, num_tracks, track_ducking);
}

// ── Advance band solo with SoloMode-aware dispatch ──────────────────

inline void advance_band_solo(
    BandSoloState& state,
    const BandSoloConfigParam& config,
    const SectionParam& section,
    PulsarTrackState* tracks,
    uint32_t& seed,
    int num_tracks = kNumPulsarTracks,
    const DuckingParam* track_ducking = nullptr   // per-track authored duck, or nullptr
) {
    if (!state.active) return;

    // Clear the per-bar handoff flag; the expiry block below sets it if a
    // handoff happens THIS bar. The cpp render reads it AFTER this call.
    state.just_handed_off = false;

    // Bars this solo has run, section-wide. Feeds jam_solo_progress() so a jam
    // builds across its section instead of re-arcing at every handoff.
    state.bars_elapsed++;

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

    // JAM leads must own a melodic track (a chordal-only member would no-op into a
    // dead solo). Compute eligibility once and apply it to both lead selections below.
    bool jam_elig[kMaxBandMembers];
    const bool* elig = (section.solo_mode == SoloModeId::JAM)
        ? build_solo_eligibility(config, tracks, num_tracks, jam_elig) : nullptr;

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

    // Pre-select next lead one bar before expiry and pull it toward ACTIVE so it
    // rises into the handoff (overlap bridge bar).
    if (state.lead_member >= 0 && state.pending_lead < 0 &&
        state.member_bars_remaining[state.lead_member] == 1) {
        int next = select_next_lead(config, state, seed, elig);
        if (next != state.lead_member) {
            state.pending_lead = next;
            if (state.member_role[next] == MemberSoloRole::SUPPORT &&
                !config.members[next].always_active) {
                state.member_role[next] = MemberSoloRole::ACTIVE;
            }
        }
    }

    // Handle lead expiry and handoff
    if (state.lead_member >= 0 &&
        state.member_bars_remaining[state.lead_member] <= 0) {
        int outgoing = state.lead_member;
        int next = (state.pending_lead >= 0) ? state.pending_lead
                                             : select_next_lead(config, state, seed, elig);

        // Drum-lead gate: override `next` with the always_active drummer
        // when conditions are met. Inserted AFTER next is computed, BEFORE roles.
        bool drum_lead = should_drum_lead(section.solo_mode, state.last_handoff_was_drum, seed);
        if (drum_lead) {
            // pick the always_active drummer as the lead for this span
            for (int m = 0; m < config.member_count; m++) {
                if (config.members[m].always_active) { next = m; break; }
            }
            state.drum_lead_style =
                static_cast<int>(pick_drum_lead_style(config.members[next].track_count, seed));
            state.last_handoff_was_drum = true;
        } else {
            state.drum_lead_style = -1;
            state.last_handoff_was_drum = false;
        }

        // Demote outgoing lead to ACTIVE for a trailing overlap bar;
        // the "Drop expired pull-ins" sweep above will return it to SUPPORT
        // once its member_bars_remaining reaches 0.
        state.member_role[outgoing] = MemberSoloRole::ACTIVE;
        state.member_bars_remaining[outgoing] = 1;
        state.lead_member = next;
        state.member_role[next] = MemberSoloRole::LEADING;
        state.pending_lead = -1;

        int range = config.bars_per_lead_max - config.bars_per_lead_min + 1;
        if (range < 1) range = 1;
        state.member_bars_remaining[next] = config.bars_per_lead_min +
            static_cast<int>(pattern_rand01(seed) * range) % range;

        // NOTE: phrase_cursor + last_phrase intentionally NOT reset here.
        // The outgoing phrase survives to the render block so improvisers_handoff()
        // can bias the incoming soloist's interval weights toward the outgoing phrase.
        // The render block resets phrase_cursor + last_phrase AFTER calling handoff.
        // Signal to the render block that this bar is the first for the new lead;
        // the render block will choose the lick octave and suppress the octave-jump
        // mutation idiom so the incoming lick doesn't leap away from the outgoing note.
        state.just_handed_off = true;
    }

    apply_band_solo_modifiers(tracks, config, state, num_tracks, track_ducking);

    // Handoff punctuation: on the bar the solo passes, the kit answers with a fill.
    // Rolled ONCE per handoff and written into solo_fill_mod, which the call above
    // rewrites every bar — so the arming is inherently one bar long. PERCUSSIVE
    // non-soloists only: the fill is the band answering the pass, not the incoming
    // soloist filling over its own entrance.
    if (state.just_handed_off && pattern_rand01(seed) < kHandoffFillChance) {
        for (int t = 0; t < num_tracks; t++) {
            if (tracks[t].role != TrackRole::PERCUSSIVE || tracks[t].is_soloist) continue;
            if (tracks[t].solo_fill_mod < kHandoffFillDepth)
                tracks[t].solo_fill_mod = kHandoffFillDepth;
        }
    }
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
    state.pending_lead = -1;
    for (int m = 0; m < kMaxBandMembers; m++) {
        state.member_role[m] = MemberSoloRole::SUPPORT;
        state.member_bars_remaining[m] = 0;
        state.bars_since_lead[m] = 0;
    }
    std::memset(state.last_phrase, -1, sizeof(state.last_phrase));
    state.drum_lead_style = -1;
    state.last_handoff_was_drum = false;
    state.solo_lick_octave = -1;
    state.outgoing_last_note = -1;
    state.just_handed_off = false;
    state.bars_elapsed = 0;

    clear_solo_modifiers(tracks, num_tracks);
}
