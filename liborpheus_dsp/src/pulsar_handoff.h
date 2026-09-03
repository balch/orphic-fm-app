#pragma once
#include <cmath>
#include "orpheus_unit_pulsar.h"
#include "pulsar_lick_techniques.h"
#include "pulsar_pattern_gen.h"

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

// Render the shared lick's rhythm onto the drum member's tracks.
//
// Signature is intentionally flat (no PulsarState*) so unit tests can pass a
// stack-allocated BandSoloConfigParam + PulsarTrackState[] without a full state.
//
// Mapping: member's tracks[0]→kick, [1]→snare, [2]→hat.
// Missing slots (1- or 2-track member) fall back to tracks[0].
//
// Style semantics:
//   CONTOUR  — passes role=LEADING (busier, velocity-routed kit); melodic untouched.
//   LOCK_IN  — passes role=ACTIVE; melodic untouched.
//   BREAK    — passes role=ACTIVE; clears every MELODIC track's steps (melody drops).
inline void render_drum_lead(const BandSoloConfigParam& config,
                             PulsarTrackState* tracks, int num_tracks,
                             int lead_member, DrumLeadStyle style,
                             const PulsarLickStep* lick, int lick_len,
                             float complexity, uint32_t& seed) {
    if (lead_member < 0 || lead_member >= config.member_count) return;
    const BandMemberParam& dm = config.members[lead_member];
    if (dm.track_count < 1 || lick_len <= 0) return;

    // Map first three member tracks to kick/snare/hat (fall back to tracks[0]).
    int kick_idx  = dm.tracks[0];
    int snare_idx = dm.track_count > 1 ? dm.tracks[1] : dm.tracks[0];
    int hat_idx   = dm.track_count > 2 ? dm.tracks[2] : dm.tracks[0];

    if (kick_idx  < 0 || kick_idx  >= num_tracks) return;
    if (snare_idx < 0 || snare_idx >= num_tracks) return;
    if (hat_idx   < 0 || hat_idx   >= num_tracks) return;

    int sc = tracks[kick_idx].step_count;

    MemberSoloRole role = (style == DrumLeadStyle::CONTOUR)
                          ? MemberSoloRole::LEADING : MemberSoloRole::ACTIVE;

    generate_lick_rhythm_pattern(
        tracks[kick_idx].steps, tracks[snare_idx].steps, tracks[hat_idx].steps,
        sc, lick, lick_len, complexity, role, seed);

    if (style == DrumLeadStyle::BREAK) {
        // Duck the OTHER melodic voices — clear every MELODIC track's steps,
        // but exempt the lead member's own tracks. Without this, an all-Melodic
        // vibe (no PERCUSSIVE tracks) wipes the drum member's just-rendered
        // rhythm too, collapsing the whole sequencer to silence.
        for (int t = 0; t < num_tracks; t++) {
            if (tracks[t].role != TrackRole::MELODIC) continue;
            bool is_lead_track = false;
            for (int k = 0; k < dm.track_count; k++) {
                if (dm.tracks[k] == t) { is_lead_track = true; break; }
            }
            if (is_lead_track) continue;
            for (int i = 0; i < tracks[t].step_count; i++)
                tracks[t].steps[i] = make_step(0, 0.0f, false, 0.0f);
        }
    }
}

// Move `current` toward `target` by at most `max_step`; clamps at target.
inline float slew_toward(float current, float target, float max_step) {
    float d = target - current;
    if (std::fabs(d) <= max_step) return target;
    return current + (d > 0.0f ? max_step : -max_step);
}
