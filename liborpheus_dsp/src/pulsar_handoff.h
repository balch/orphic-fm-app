#pragma once
#include <cmath>
#include <cstring>
#include <algorithm>
#include "orpheus_unit_pulsar.h"
#include "pulsar_lick_techniques.h"
#include "pulsar_pattern_gen.h"
#include "pulsar_solo_curves.h"

// ── Drum-lead gate and style selection ──────────────────────────────────────
//
// Drum leads are rare and deliberate: at most one in ~8 handoffs, never two
// consecutive, and only in LICK_BUILDER sections where there is a lick to mirror.

enum class DrumLeadStyle { BREAK = 0, LOCK_IN = 1, CONTOUR = 2 };

// Returns true when the drummer should lead the next span.
// Conditions: LICK_BUILDER mode, not consecutive (last_was_drum=false),
// and a random roll below prob.
inline bool should_drum_lead(SoloModeId mode, bool last_was_drum, uint32_t& seed, float prob = 0.12f) {
    if (mode != SoloModeId::LICK_BUILDER) return false;   // only modes with a lick to mirror
    if (last_was_drum) return false;                       // never two in a row
    return pattern_rand01(seed) < prob;
}

// Picks one of three mirror styles uniformly. Falls back to LOCK_IN instead of
// CONTOUR when there are fewer than 3 member tracks (CONTOUR needs peers to mirror).
inline DrumLeadStyle pick_drum_lead_style(int member_track_count, uint32_t& seed) {
    int r = static_cast<int>(pattern_rand01(seed) * 3.0f) % 3;
    DrumLeadStyle s = static_cast<DrumLeadStyle>(r);
    if (s == DrumLeadStyle::CONTOUR && member_track_count < 3) return DrumLeadStyle::LOCK_IN;
    return s;
}

// Lift fire_prob toward 1.0 by the positive solo density/fill mods, scaled by the
// REMAINING headroom so a high base can't slam to 1.0 (which would fire every step
// and remove the rests that articulate a phrase). fill counts half, matching the
// prior weighting. density_mod<=0 is a no-op (the negative-duck path is separate).
inline float solo_fire_boost(float fire_prob, float density_mod, float fill_mod) {
    if (density_mod <= 0.0f) return fire_prob;
    float lift = density_mod;
    if (fill_mod > 0.0f) lift += fill_mod * 0.5f;
    fire_prob += lift * (1.0f - fire_prob);   // headroom-relative
    return fire_prob > 1.0f ? 1.0f : fire_prob;
}

// Handoff punctuation: shallow a ducked track's density for one bar so the kit can
// answer a solo pass. Deliberately NOT folded into solo_fire_boost — that helper
// early-returns on density_mod <= 0, which is exactly the ducked kit this is for.
// Never pushes past 0: the fill lifts the duck, it does not boost above baseline.
inline float handoff_fill_duck(float density_mod, float fill_mod) {
    if (fill_mod <= 0.0f || density_mod >= 0.0f) return density_mod;
    float d = density_mod + fill_mod;
    return (d > 0.0f) ? 0.0f : d;
}

// Pick the MIDI octave (the value render_lick_into_track takes as lick_octave)
// that places the lick's first note closest to the outgoing soloist's last note,
// clamped to the genre note range. outgoing_note < 0 => keep auto (-1).
// Uses lick_degree_to_midi (pulsar_pattern_gen.h) which mirrors the exact
// formula used by generate_lick_pattern and bar_strategy_call_response.
inline int choose_lick_octave(int first_degree, int outgoing_note,
                              int root, const PulsarScale& scale,
                              int nr_low, int nr_high) {
    if (outgoing_note < 0) return -1;   // no prior soloist — caller uses auto
    int best_oct = -1;
    int best_dist = 1 << 30;
    for (int oct = 0; oct <= 8; oct++) {
        int note = lick_degree_to_midi(first_degree, root, scale, oct, nr_low, nr_high);
        if (note < nr_low || note > nr_high) continue;
        int d = std::abs(note - outgoing_note);
        if (d < best_dist) { best_dist = d; best_oct = oct; }
    }
    return best_oct;   // -1 if nothing fit the range (renderer falls back to auto)
}

// Kick/snare/hat of a drum member: tracks[0..2], missing slots fall back to tracks[0].
inline bool drum_member_kit(const BandSoloConfigParam& config, int member, int num_tracks,
                            int& kick, int& snare, int& hat) {
    if (member < 0 || member >= config.member_count) return false;
    const BandMemberParam& dm = config.members[member];
    if (dm.track_count < 1) return false;
    kick  = dm.tracks[0];
    snare = dm.track_count > 1 ? dm.tracks[1] : dm.tracks[0];
    hat   = dm.track_count > 2 ? dm.tracks[2] : dm.tracks[0];
    return kick >= 0 && kick < num_tracks && snare >= 0 && snare < num_tracks && hat >= 0 && hat < num_tracks;
}

// Snapshot the kit's groove at the handoff into a drum lead.
inline void begin_drum_lead(BandSoloState& state, const BandSoloConfigParam& config,
                            PulsarTrackState* tracks, int num_tracks) {
    int k, s, h;
    if (!drum_member_kit(config, state.lead_member, num_tracks, k, s, h)) return;
    const int idx[3] = {k, s, h};
    for (int r = 0; r < 3; r++)
        std::memcpy(state.drum_groove[r], tracks[idx[r]].steps, sizeof(state.drum_groove[r]));
    state.drum_groove_valid = true;
    state.drum_span_bars = state.member_bars_remaining[state.lead_member];
}

// Hand the groove back on the way out. Safe to call when nothing was armed.
inline void end_drum_lead(BandSoloState& state, const BandSoloConfigParam& config,
                          PulsarTrackState* tracks, int num_tracks) {
    if (!state.drum_groove_valid) return;
    int k, s, h;
    if (drum_member_kit(config, state.lead_member, num_tracks, k, s, h)) {
        const int idx[3] = {k, s, h};
        for (int r = 0; r < 3; r++)
            std::memcpy(tracks[idx[r]].steps, state.drum_groove[r], sizeof(state.drum_groove[r]));
    }
    state.drum_groove_valid = false;
    state.drum_span_bars = 0;
}

// 0 on the drummer's first bar, 1 on the last; a one-bar span is its own climax.
inline float drum_span_progress(int span, int remaining) {
    if (span <= 1) return 1.0f;
    float p = static_cast<float>(span - remaining) / static_cast<float>(span - 1);
    return p < 0.0f ? 0.0f : (p > 1.0f ? 1.0f : p);
}

// The arc for the bar about to render: advance_band_solo has already decremented
// member_bars_remaining, so remaining == 1 is the drummer's final bar.
inline DrumArc drum_arc_for_bar(const BandSoloState& state, int lead) {
    int remaining = state.member_bars_remaining[lead];
    return drum_arc(drum_span_progress(state.drum_span_bars, remaining), remaining <= 1,
                    state.solo_seed, state.bars_elapsed);
}

// Render the shared lick's rhythm onto the drum member's tracks.
//
// Signature is intentionally flat (no PulsarState*) so unit tests can pass a
// stack-allocated BandSoloConfigParam + PulsarTrackState[] without a full state.
//
// Mapping: member's tracks[0] to kick, [1] to snare, [2] to hat.
// Missing slots (1- or 2-track member) fall back to tracks[0].
//
// Style semantics:
//   CONTOUR: passes role=LEADING (busier, velocity-routed kit); melodic untouched.
//   LOCK_IN: passes role=ACTIVE; melodic untouched.
//   BREAK: passes role=ACTIVE; melodic ducking is handled by apply_band_solo_modifiers.
//
// solo_state, when given and holding a valid snapshot, is the base: the lick's accents
// lay OVER the restored groove instead of clearing it. arc, when given, rides the
// drummer's build (overlay gain, hat/ghost climb, the last-bar climax fill).
//
// seed is expected to be a LOCAL hashed seed, not the shared mutation stream: the hat
// and ghost draws below run every drum-lead bar and would reorder every later mutation.
inline void render_drum_lead(const BandSoloConfigParam& config,
                             PulsarTrackState* tracks, int num_tracks,
                             int lead_member, DrumLeadStyle style,
                             const PulsarLickStep* lick, int lick_len,
                             float complexity, uint32_t& seed,
                             const BandSoloState* solo_state = nullptr,
                             const DrumArc* arc = nullptr) {
    int kick_idx, snare_idx, hat_idx;
    if (!drum_member_kit(config, lead_member, num_tracks, kick_idx, snare_idx, hat_idx)) return;
    PulsarStep* kick  = tracks[kick_idx].steps;
    PulsarStep* snare = tracks[snare_idx].steps;
    PulsarStep* hat   = tracks[hat_idx].steps;
    const int sc = tracks[kick_idx].step_count;
    if (sc <= 0) return;
    const MemberSoloRole role = (style == DrumLeadStyle::CONTOUR)
                                ? MemberSoloRole::LEADING : MemberSoloRole::ACTIVE;
    const bool has_groove = solo_state && solo_state->drum_groove_valid;

    if (has_groove) {
        // Base = the snapshot, so bars never compound and the groove is always underneath.
        std::memcpy(kick,  solo_state->drum_groove[0], sizeof(PulsarStep) * sc);
        std::memcpy(snare, solo_state->drum_groove[1], sizeof(PulsarStep) * sc);
        std::memcpy(hat,   solo_state->drum_groove[2], sizeof(PulsarStep) * sc);
        float gain = arc ? arc->overlay_gain : 1.0f;
        overlay_lick_rhythm_pattern(kick, snare, hat, sc, lick, lick_len, gain, role);
    } else {
        // No snapshot (unit tests, or a lead that started before one was armed): the
        // historic mirror, which clears and rebuilds the three tracks.
        if (lick_len <= 0) return;
        generate_lick_rhythm_pattern(kick, snare, hat, sc, lick, lick_len, complexity, role, seed);
    }

    // Hat gaps and snare ghosts: by the arc when there is one, else the historic rates.
    // Only for the has-groove path -- the no-groove path already did both inside
    // generate_lick_rhythm_pattern, so an arc with no snapshot skips just these two
    // rates. Its climax block below still runs.
    if (has_groove) {
        const bool is_leading = (role == MemberSoloRole::LEADING);
        float hat_prob = arc ? arc->hat_prob
                             : std::min(1.0f, 0.3f + complexity * 0.4f + (is_leading ? 0.2f : 0.0f));
        float ghost_prob = arc ? arc->ghost_prob : (is_leading ? complexity * 0.3f : 0.0f);
        for (int i = 0; i < sc; i++) {
            if (!hat[i].gate && pattern_rand01(seed) < hat_prob)
                hat[i] = make_step(42, 0.25f + pattern_rand01(seed) * 0.2f, true, 0.1f);
            if (!snare[i].gate && pattern_rand01(seed) < ghost_prob)
                snare[i] = make_step(40, 0.2f + pattern_rand01(seed) * 0.15f, true, 0.15f);
        }
    }

    // The last quarter of the final bar is a snare ramp capped by a kick on the last
    // step, the pickup into the band's downbeat.
    if (arc && arc->climax) {
        int q = sc / 4; if (q < 1) q = 1;
        for (int i = sc - q; i < sc; i++) {
            float f = (q > 1) ? static_cast<float>(i - (sc - q)) / static_cast<float>(q - 1) : 1.0f;
            snare[i] = make_step(40, kClimaxVelStart + (1.0f - kClimaxVelStart) * f, true, 0.15f);
        }
        kick[sc - 1] = make_step(36, 1.0f, true, 0.4f);
    }
}

// Move `current` toward `target` by at most `max_step`; clamps at target.
inline float slew_toward(float current, float target, float max_step) {
    float d = target - current;
    if (std::fabs(d) <= max_step) return target;
    return current + (d > 0.0f ? max_step : -max_step);
}
