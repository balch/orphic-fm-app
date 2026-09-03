#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/pulsar_band_solo.h"
#include "../src/pulsar_handoff.h"
#include "../src/pulsar_pattern_gen.h"
#include "../src/pulsar_rng.h"
#include <cstdio>
#include <cmath>
#include <vector>

// ── Unit tests for band-based solo system ──────────────────────────────

static bool test_band_lead_selection() {
    printf("\n=== Test: Band lead selection ===\n");

    BandSoloState state{};
    BandSoloConfigParam config{};
    config.probability = 1.0f;  // always start
    config.member_count = 3;
    config.bars_per_lead_min = 2;
    config.bars_per_lead_max = 4;

    // Member 0: drums (always_active, tracks 0,1,2)
    config.members[0].tracks[0] = 0;
    config.members[0].tracks[1] = 1;
    config.members[0].tracks[2] = 2;
    config.members[0].track_count = 3;
    config.members[0].always_active = true;

    // Member 1: bass (track 3)
    config.members[1].tracks[0] = 3;
    config.members[1].track_count = 1;
    config.members[1].always_active = false;

    // Member 2: lead (track 4)
    config.members[2].tracks[0] = 4;
    config.members[2].track_count = 1;
    config.members[2].always_active = false;

    SectionParam section{};
    section.solo_mode = SoloModeId::LICK_BUILDER;
    section.solo_probability = 1.0f;

    PulsarTrackState tracks[kNumPulsarTracks]{};
    uint32_t seed = 12345;

    start_band_solo(state, config, section, tracks, seed);

    bool active_ok = state.active;
    bool lead_ok = state.lead_member >= 0 && state.lead_member < config.member_count;
    bool role_ok = state.member_role[state.lead_member] == MemberSoloRole::LEADING;

    printf("  active=%d (expect 1) -- %s\n", state.active, active_ok ? "OK" : "FAIL");
    printf("  lead_member=%d (expect 0..2) -- %s\n", state.lead_member, lead_ok ? "OK" : "FAIL");
    printf("  lead role=LEADING -- %s\n", role_ok ? "OK" : "FAIL");

    bool pass = active_ok && lead_ok && role_ok;
    printf("  Band lead selection: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_always_active_not_ducked() {
    printf("\n=== Test: Always-active member not ducked ===\n");

    BandSoloState state{};
    BandSoloConfigParam config{};
    config.probability = 1.0f;
    config.member_count = 2;
    config.bars_per_lead_min = 4;
    config.bars_per_lead_max = 4;

    // Member 0: drums (always_active, track 0)
    config.members[0].tracks[0] = 0;
    config.members[0].track_count = 1;
    config.members[0].always_active = true;

    // Member 1: bass (track 1)
    config.members[1].tracks[0] = 1;
    config.members[1].track_count = 1;
    config.members[1].always_active = false;

    PulsarTrackState tracks[kNumPulsarTracks]{};

    // Manually set state: bass (member 1) is leading, drums (member 0) is support
    state.active = true;
    state.lead_member = 1;
    state.member_role[0] = MemberSoloRole::SUPPORT;
    state.member_role[1] = MemberSoloRole::LEADING;
    apply_band_solo_modifiers(tracks, config, state);

    // Drums (track 0, always_active, SUPPORT): volume_mod >= -0.1 (not fully ducked)
    bool drums_ok = tracks[0].solo_volume_mod >= -0.1f;
    // Bass (track 1, LEADING): positive boost
    bool bass_ok = tracks[1].solo_volume_mod > 0.0f;

    printf("  Drums (always_active, SUPPORT) volume_mod=%.3f (expect >= -0.1) -- %s\n",
           tracks[0].solo_volume_mod, drums_ok ? "OK" : "FAIL");
    printf("  Bass (LEADING) volume_mod=%.3f (expect > 0.0) -- %s\n",
           tracks[1].solo_volume_mod, bass_ok ? "OK" : "FAIL");

    bool pass = drums_ok && bass_ok;
    printf("  Always-active not ducked: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_pull_in_mechanic() {
    printf("\n=== Test: Pull-in mechanic ===\n");

    BandSoloState state{};
    BandSoloConfigParam config{};
    config.probability = 1.0f;
    config.member_count = 3;
    config.bars_per_lead_min = 8;  // long lead so no handoff during test
    config.bars_per_lead_max = 8;
    config.pull_in_bars_min = 2;
    config.pull_in_bars_max = 4;

    // Member 0: lead (track 0)
    config.members[0].tracks[0] = 0;
    config.members[0].track_count = 1;
    config.members[0].always_active = false;

    // Member 1: target for pull-in (track 1)
    config.members[1].tracks[0] = 1;
    config.members[1].track_count = 1;
    config.members[1].always_active = false;

    // Member 2: drums (track 2, always_active -- won't be pulled in)
    config.members[2].tracks[0] = 2;
    config.members[2].track_count = 1;
    config.members[2].always_active = true;

    // 100% pull-in probability from member 0 to member 1
    std::memset(config.pull_in_matrix, 0, sizeof(config.pull_in_matrix));
    config.pull_in_matrix[0 * kMaxBandMembers + 1] = 1.0f;

    // Handoff matrix (not relevant but needs something)
    std::memset(config.handoff_matrix, 0, sizeof(config.handoff_matrix));
    config.handoff_matrix[0 * kMaxBandMembers + 1] = 1.0f;

    SectionParam section{};
    section.solo_mode = SoloModeId::JAM;
    section.solo_probability = 1.0f;

    // Manually set up state: member 0 is leading
    state.active = true;
    state.lead_member = 0;
    state.member_role[0] = MemberSoloRole::LEADING;
    state.member_bars_remaining[0] = 8;
    state.member_role[1] = MemberSoloRole::SUPPORT;
    state.member_role[2] = MemberSoloRole::SUPPORT;

    PulsarTrackState tracks[kNumPulsarTracks]{};
    uint32_t seed = 77777;

    // Advance one bar -- should pull in member 1
    advance_band_solo(state, config, section, tracks, seed);

    bool pulled_in = state.member_role[1] == MemberSoloRole::ACTIVE;
    bool has_bars = state.member_bars_remaining[1] >= config.pull_in_bars_min;

    printf("  Member 1 role after advance: %d (expect ACTIVE=1) -- %s\n",
           (int)state.member_role[1], pulled_in ? "OK" : "FAIL");
    printf("  Member 1 bars_remaining=%d (expect >= %d) -- %s\n",
           state.member_bars_remaining[1], config.pull_in_bars_min,
           has_bars ? "OK" : "FAIL");

    bool pass = pulled_in && has_bars;
    printf("  Pull-in mechanic: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_pull_in_duration_and_dropout() {
    printf("\n=== Test: Pull-in duration and dropout ===\n");

    BandSoloState state{};
    BandSoloConfigParam config{};
    config.probability = 1.0f;
    config.member_count = 2;
    config.bars_per_lead_min = 10;
    config.bars_per_lead_max = 10;
    config.pull_in_bars_min = 2;
    config.pull_in_bars_max = 2;  // fixed duration for predictability

    // Member 0: lead (track 0)
    config.members[0].tracks[0] = 0;
    config.members[0].track_count = 1;
    config.members[0].always_active = false;

    // Member 1: pulled-in (track 1)
    config.members[1].tracks[0] = 1;
    config.members[1].track_count = 1;
    config.members[1].always_active = false;

    // No pull-in probability (we'll set it up manually)
    std::memset(config.pull_in_matrix, 0, sizeof(config.pull_in_matrix));
    std::memset(config.handoff_matrix, 0, sizeof(config.handoff_matrix));
    config.handoff_matrix[0 * kMaxBandMembers + 0] = 1.0f;  // self-loop for lead

    SectionParam section{};
    section.solo_mode = SoloModeId::JAM;
    section.solo_probability = 1.0f;

    // Set up: member 0 leading, member 1 just pulled in with 2 bars
    state.active = true;
    state.lead_member = 0;
    state.member_role[0] = MemberSoloRole::LEADING;
    state.member_bars_remaining[0] = 10;
    state.member_role[1] = MemberSoloRole::ACTIVE;
    state.member_bars_remaining[1] = 2;

    PulsarTrackState tracks[kNumPulsarTracks]{};
    uint32_t seed = 88888;

    // Advance 1 bar -- member 1 should still be ACTIVE (1 bar remaining)
    advance_band_solo(state, config, section, tracks, seed);
    bool still_active = state.member_role[1] == MemberSoloRole::ACTIVE;
    int bars_after_1 = state.member_bars_remaining[1];

    printf("  After 1 advance: role=%d (expect ACTIVE=1), bars_remaining=%d -- %s\n",
           (int)state.member_role[1], bars_after_1,
           still_active ? "OK" : "FAIL");

    // Advance 1 more bar -- member 1 should drop to SUPPORT (0 bars remaining)
    advance_band_solo(state, config, section, tracks, seed);
    bool dropped = state.member_role[1] == MemberSoloRole::SUPPORT;

    printf("  After 2 advances: role=%d (expect SUPPORT=0) -- %s\n",
           (int)state.member_role[1], dropped ? "OK" : "FAIL");

    bool pass = still_active && dropped;
    printf("  Pull-in duration and dropout: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_long_fill_no_handoff() {
    printf("\n=== Test: LongFill solo deactivates without handoff ===\n");

    BandSoloState state{};
    BandSoloConfigParam config{};
    config.probability = 1.0f;
    config.member_count = 2;
    config.bars_per_lead_min = 4;
    config.bars_per_lead_max = 4;

    config.members[0].tracks[0] = 0;
    config.members[0].track_count = 1;
    config.members[0].always_active = false;

    config.members[1].tracks[0] = 1;
    config.members[1].track_count = 1;
    config.members[1].always_active = false;

    std::memset(config.handoff_matrix, 0, sizeof(config.handoff_matrix));
    config.handoff_matrix[0 * kMaxBandMembers + 1] = 1.0f;
    config.handoff_matrix[1 * kMaxBandMembers + 0] = 1.0f;

    SectionParam section{};
    section.solo_mode = SoloModeId::LONG_FILL;
    section.solo_probability = 1.0f;
    section.solo_bars_min = 3;
    section.solo_bars_max = 3;

    PulsarTrackState tracks[kNumPulsarTracks]{};
    uint32_t seed = 55555;

    start_band_solo(state, config, section, tracks, seed);
    bool started = state.active;
    int lead = state.lead_member;
    printf("  Started: active=%d, lead=%d -- %s\n", state.active, lead,
           started ? "OK" : "FAIL");

    // Advance through all bars (3 bars for LongFill)
    for (int bar = 0; bar < 4; bar++) {
        advance_band_solo(state, config, section, tracks, seed);
    }

    // After bars expire, LongFill should deactivate (no handoff)
    bool deactivated = !state.active;
    printf("  After bars expire: active=%d (expect 0) -- %s\n",
           state.active, deactivated ? "OK" : "FAIL");

    bool pass = started && deactivated;
    printf("  LongFill no handoff: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Task 1.1: SEED-4 — never let an always-active member be the initial lead ──
static bool test_always_active_never_initial_lead() {
    printf("\n=== Test: Always-active member never the initial lead (SEED-4) ===\n");

    BandSoloConfigParam c{};
    c.member_count = 3;
    c.members[0].always_active = true;  c.members[0].track_count = 1; c.members[0].tracks[0] = 0; // drums
    c.members[1].always_active = false; c.members[1].track_count = 1; c.members[1].tracks[0] = 3; // bass
    c.members[2].always_active = false; c.members[2].track_count = 1; c.members[2].tracks[0] = 4; // lead

    int drum_leads = 0;
    for (uint32_t s = 1; s <= 4000; ++s) {
        uint32_t seed = s;
        if (select_initial_lead(c, seed) == 0) drum_leads++;
    }

    bool pass = (drum_leads == 0);  // drums must never be picked when melodic members exist
    printf("  drum_leads=%d over 4000 rolls (expect 0) -- %s\n", drum_leads, pass ? "OK" : "FAIL");
    printf("  Always-active never initial lead: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Task 1.2: BAND-02 — uniform fallback must exclude the current lead ──
static bool test_empty_handoff_row_does_not_reselect_current_lead() {
    printf("\n=== Test: Empty handoff row does not re-select current lead (BAND-02) ===\n");

    BandSoloConfigParam c{};
    c.member_count = 3;
    for (int m = 0; m < 3; m++) { c.members[m].track_count = 1; c.members[m].tracks[0] = m; }
    // handoff_matrix is all zeros (default), forcing the fallback path for any lead.

    BandSoloState st{};
    st.lead_member = 1;  // current lead = member 1

    int self = 0;
    for (uint32_t s = 1; s <= 4000; ++s) {
        uint32_t seed = s;
        if (select_next_lead(c, st, seed) == 1) self++;
    }

    bool pass = (self == 0);  // fallback must hand OFF, never re-pick the current lead
    printf("  self re-picks=%d over 4000 rolls (expect 0) -- %s\n", self, pass ? "OK" : "FAIL");
    printf("  Empty handoff row does not re-select current lead: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Task 1.3: Bound variance — bias initial lead toward improvisational members ──
static bool test_initial_lead_biased_by_creativity() {
    printf("\n=== Test: Initial lead biased by creativity (bound variance) ===\n");

    BandSoloConfigParam c{};
    c.member_count = 3;
    c.members[0].always_active = true;  c.members[0].track_count = 1; c.members[0].tracks[0] = 0;
    c.members[1].always_active = false; c.members[1].track_count = 1; c.members[1].tracks[0] = 3; c.members[1].creativity = 0.3f; // bass
    c.members[2].always_active = false; c.members[2].track_count = 1; c.members[2].tracks[0] = 4; c.members[2].creativity = 0.6f; // lead

    int lead = 0, bass = 0;
    for (uint32_t s = 1; s <= 6000; ++s) {
        uint32_t seed = s;
        int w = select_initial_lead(c, seed);
        if (w == 2) lead++;
        else if (w == 1) bass++;
    }

    // Require a MEANINGFUL bias, not an RNG-noise coin-flip tie: the creative
    // member's lead share must clearly exceed the bass member's. With equal
    // weights this margin is ~0; with creativity weighting it is ~10%+.
    bool pass = (lead > bass + 200);  // higher-creativity member should lead more often
    printf("  lead(creativity 0.6)=%d  bass(creativity 0.3)=%d (expect lead > bass+200) -- %s\n",
           lead, bass, pass ? "OK" : "FAIL");
    printf("  Initial lead biased by creativity: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── #7: never-led members start as "least-recent" so the first handoff
//        actually prefers them (not collapsed to the recency floor) ──────
static bool test_start_band_solo_seeds_recency_for_never_led() {
    printf("\n=== Test: start_band_solo seeds bars_since_lead so never-led members are due (#7) ===\n");

    BandSoloConfigParam config{};
    config.member_count = 3;
    for (int m = 0; m < 3; m++) { config.members[m].track_count = 1; config.members[m].tracks[0] = m; config.members[m].always_active = false; }

    SectionParam section{};
    section.solo_mode = SoloModeId::LICK_BUILDER;
    section.solo_probability = 1.0f;

    BandSoloState state{};
    PulsarTrackState tracks[kNumPulsarTracks]{};
    uint32_t seed = 24680;
    start_band_solo(state, config, section, tracks, seed);

    // The installed lead must read as just-led (0); every other (never-led)
    // member must read as least-recent (the recency cap), so the first handoff's
    // recency weighting prefers them instead of treating 0 as "just led".
    bool lead_zero = (state.bars_since_lead[state.lead_member] == 0);
    bool others_due = true;
    for (int m = 0; m < config.member_count; m++) {
        if (m == state.lead_member) continue;
        if (state.bars_since_lead[m] < 32) others_due = false;
    }

    printf("  lead=%d bars_since_lead[lead]=%d others>=32=%s\n",
           state.lead_member, state.bars_since_lead[state.lead_member],
           others_due ? "YES" : "NO");

    bool pass = lead_zero && others_due;
    printf("  start_band_solo recency seeding: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_personality_modifiers() {
    printf("\n=== Test: Personality modifiers (loudness affects volume) ===\n");

    BandSoloState state{};
    BandSoloConfigParam config{};
    config.member_count = 2;

    // Member 0: high loudness (track 0)
    config.members[0].tracks[0] = 0;
    config.members[0].track_count = 1;
    config.members[0].loudness = 0.9f;

    // Member 1: low loudness (track 1)
    config.members[1].tracks[0] = 1;
    config.members[1].track_count = 1;
    config.members[1].loudness = 0.1f;

    PulsarTrackState tracks[kNumPulsarTracks]{};

    // Test member 0 as LEADING
    state.active = true;
    state.lead_member = 0;
    state.member_role[0] = MemberSoloRole::LEADING;
    state.member_role[1] = MemberSoloRole::SUPPORT;
    apply_band_solo_modifiers(tracks, config, state);
    float high_loud_vol = tracks[0].solo_volume_mod;

    // Test member 1 as LEADING
    state.lead_member = 1;
    state.member_role[0] = MemberSoloRole::SUPPORT;
    state.member_role[1] = MemberSoloRole::LEADING;
    apply_band_solo_modifiers(tracks, config, state);
    float low_loud_vol = tracks[1].solo_volume_mod;

    bool vol_ok = high_loud_vol > low_loud_vol;
    printf("  High loudness (0.9) volume_mod=%.3f\n", high_loud_vol);
    printf("  Low loudness (0.1) volume_mod=%.3f\n", low_loud_vol);
    printf("  High > Low: %s\n", vol_ok ? "YES" : "NO");

    printf("  Personality modifiers: %s\n", vol_ok ? "PASS" : "FAIL");
    return vol_ok;
}

// ── Handoff punctuation: the pass arms a kit fill, a plain bar never does ──
static bool test_handoff_arms_percussive_fill_mod() {
    printf("\n=== Test: Handoff bar arms the kit's solo_fill_mod ===\n");

    BandSoloConfigParam config{};
    config.probability = 1.0f;
    config.member_count = 3;
    config.bars_per_lead_min = 2;
    config.bars_per_lead_max = 2;   // trade every 2 bars so handoffs are frequent

    // Member 0: drums (always_active, PERCUSSIVE tracks 0-2).
    config.members[0].track_count = 3;
    config.members[0].tracks[0] = 0;
    config.members[0].tracks[1] = 1;
    config.members[0].tracks[2] = 2;
    config.members[0].always_active = true;
    // Members 1 and 2: melodic soloists that trade.
    config.members[1].track_count = 1; config.members[1].tracks[0] = 4;
    config.members[2].track_count = 1; config.members[2].tracks[0] = 3;

    std::memset(config.handoff_matrix, 0, sizeof(config.handoff_matrix));
    config.handoff_matrix[1 * kMaxBandMembers + 2] = 1.0f;
    config.handoff_matrix[2 * kMaxBandMembers + 1] = 1.0f;
    std::memset(config.pull_in_matrix, 0, sizeof(config.pull_in_matrix));

    SectionParam section{};
    section.solo_mode = SoloModeId::JAM;
    section.solo_probability = 1.0f;

    int ho_bars = 0, ho_armed = 0, nh_bars = 0, nh_armed = 0;
    for (uint32_t s = 1; s <= 400; ++s) {
        BandSoloState state{};
        PulsarTrackState tracks[kNumPulsarTracks]{};
        for (int t = 0; t < kNumPulsarTracks; t++)
            tracks[t].role = (t < 3) ? TrackRole::PERCUSSIVE : TrackRole::MELODIC;
        uint32_t seed = s * 2654435761u + 1u;
        start_band_solo(state, config, section, tracks, seed);
        for (int bar = 0; bar < 12; bar++) {
            advance_band_solo(state, config, section, tracks, seed);
            bool armed = tracks[0].solo_fill_mod > 0.0f;
            if (state.just_handed_off) { ho_bars++; if (armed) ho_armed++; }
            else                       { nh_bars++; if (armed) nh_armed++; }
        }
    }

    float ho_rate = ho_bars > 0 ? static_cast<float>(ho_armed) / ho_bars : 0.0f;
    bool sampled = ho_bars > 100 && nh_bars > 100;
    bool never_off_handoff = (nh_armed == 0);
    // Probabilistic by design (kHandoffFillChance), so pin a band, not a value.
    bool fires_often = ho_rate > 0.4f && ho_rate < 0.85f;

    printf("  handoff bars=%d armed=%d (%.0f%%) | other bars=%d armed=%d\n",
           ho_bars, ho_armed, ho_rate * 100.0f, nh_bars, nh_armed);

    bool pass = sampled && never_off_handoff && fires_often;
    printf("  Handoff arms the kit fill: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// The fill has to be able to REACH the kit: solo_fire_boost early-returns on a
// ducked track, so handoff_fill_duck is what lets a support kit's armed fill mod
// through. Pin that it materially widens the duck gate.
static bool test_handoff_fill_duck_reopens_the_gate() {
    printf("\n=== Test: handoff_fill_duck reopens a ducked kit's gate ===\n");

    const float kSupportDuck = -0.15f;   // always-active SUPPORT (pulsar_band_solo.h)
    float filled = handoff_fill_duck(kSupportDuck, kHandoffFillDepth);
    bool clears = (filled == 0.0f);
    bool no_overshoot = handoff_fill_duck(-0.9f, kHandoffFillDepth) < 0.0f;
    bool leaves_boosted_alone = handoff_fill_duck(0.3f, kHandoffFillDepth) == 0.3f;
    bool inert_without_arm = handoff_fill_duck(kSupportDuck, 0.0f) == kSupportDuck;

    int ducked = 0, filled_through = 0;
    for (int loop = 0; loop < 64; loop++) {
        for (int s = 0; s < 16; s++) {
            if (duck_passes(s, 1, loop, kSupportDuck)) ducked++;
            if (filled >= 0.0f || duck_passes(s, 1, loop, filled)) filled_through++;
        }
    }
    bool more_steps = filled_through > ducked;

    printf("  duck %.2f -> %.2f, steps through %d -> %d\n",
           kSupportDuck, filled, ducked, filled_through);
    bool pass = clears && no_overshoot && leaves_boosted_alone && inert_without_arm && more_steps;
    printf("  handoff_fill_duck reopens the gate: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── DuckingProfile wiring ────────────────────────────────────────────
//
// The authored bank reaches the audio only through apply_band_solo_modifiers, and only
// for a track whose vibe actually declared a profile. Everything else has to stay
// exactly where it was, so these pin values bitwise rather than approximately.

struct DuckSnapshot { float volume, density, ghost, fill, reverb; bool simplify, soloist; };

static DuckSnapshot duck_snap(const PulsarTrackState& ts) {
    return { ts.solo_volume_mod, ts.solo_density_mod, ts.solo_ghost_mod,
             ts.solo_fill_mod, ts.solo_reverb_mod, ts.solo_simplify, ts.is_soloist };
}

static bool duck_same(const DuckSnapshot& a, const DuckSnapshot& b) {
    return a.volume == b.volume && a.density == b.density && a.ghost == b.ghost &&
           a.fill == b.fill && a.reverb == b.reverb &&
           a.simplify == b.simplify && a.soloist == b.soloist;
}

// Member 0 = track 0 (always-active kit), member 1 = track 3 (the lead),
// member 2 = track 4 (ducked support). Track 5 deliberately belongs to no member,
// so both standard-duck branches are covered by one call.
static void build_duck_config(BandSoloConfigParam& c, BandSoloState& st) {
    c.member_count = 3;
    c.members[0].track_count = 1; c.members[0].tracks[0] = 0; c.members[0].always_active = true;
    c.members[1].track_count = 1; c.members[1].tracks[0] = 3;
    c.members[2].track_count = 1; c.members[2].tracks[0] = 4;
    st.active = true;
    st.lead_member = 1;
    st.member_role[0] = MemberSoloRole::SUPPORT;
    st.member_role[1] = MemberSoloRole::LEADING;
    st.member_role[2] = MemberSoloRole::SUPPORT;
}

static bool test_unauthored_duck_is_bit_identical() {
    printf("\n=== Test: an unauthored track ducks exactly as it always has ===\n");

    BandSoloConfigParam config{}; BandSoloState state{};
    build_duck_config(config, state);

    PulsarTrackState none[kNumPulsarTracks]{};
    apply_band_solo_modifiers(none, config, state, kNumPulsarTracks, nullptr);

    // A bank the vibe never authored. The values are deliberately nothing like the duck
    // constants — only the clear declared flag may keep them out of the mods, so this
    // fails loudly if the gate is ever inferred from the values instead.
    DuckingParam undeclared[kNumPulsarTracks]{};
    for (int t = 0; t < kNumPulsarTracks; t++) {
        undeclared[t].volume_reduction  = 0.9f;
        undeclared[t].density_reduction = 0.9f;
        undeclared[t].ghost_reduction   = 0.9f;
        undeclared[t].fill_suppression  = 0.9f;
        undeclared[t].reverb_boost      = 0.9f;
        undeclared[t].simplify          = false;
        undeclared[t].declared          = false;
    }
    PulsarTrackState undecl[kNumPulsarTracks]{};
    apply_band_solo_modifiers(undecl, config, state, kNumPulsarTracks, undeclared);

    // Authoring the engine's own numbers must be a no-op. This is what pins the sign
    // convention: REDUCTIONS go in, negative offsets come out.
    DuckingParam echo[kNumPulsarTracks]{};
    for (int t = 0; t < kNumPulsarTracks; t++) {
        echo[t].volume_reduction  = kUnauthoredDuckVolume;
        echo[t].density_reduction = kUnauthoredDuckDensity;
        echo[t].ghost_reduction   = kUnauthoredDuckGhost;
        echo[t].fill_suppression  = kUnauthoredDuckFill;
        echo[t].reverb_boost      = kUnauthoredDuckReverb;
        echo[t].simplify          = true;
        echo[t].declared          = true;
    }
    PulsarTrackState echoed[kNumPulsarTracks]{};
    apply_band_solo_modifiers(echoed, config, state, kNumPulsarTracks, echo);

    bool bank_inert = true, echo_matches = true;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        if (!duck_same(duck_snap(none[t]), duck_snap(undecl[t])))  bank_inert = false;
        if (!duck_same(duck_snap(none[t]), duck_snap(echoed[t])))  echo_matches = false;
    }

    // The literal historical constants, on both ducked branches: track 4 (SUPPORT in a
    // non-always-active member) and track 5 (claimed by no member at all).
    bool literals = true;
    const int ducked[2] = {4, 5};
    for (int i = 0; i < 2; i++) {
        const PulsarTrackState& ts = none[ducked[i]];
        literals = literals &&
            ts.solo_volume_mod == -0.18f && ts.solo_density_mod == -0.2f &&
            ts.solo_ghost_mod  == -0.35f && ts.solo_fill_mod    == -0.35f &&
            ts.solo_simplify   == true   && ts.solo_reverb_mod  == 0.1f &&
            ts.is_soloist      == false;
    }

    printf("  undeclared bank inert -- %s\n", bank_inert ? "OK" : "FAIL");
    printf("  authoring the engine's own numbers is a no-op -- %s\n", echo_matches ? "OK" : "FAIL");
    printf("  t4 vol=%.4f dens=%.4f ghost=%.4f fill=%.4f simp=%d rev=%.4f -- %s\n",
           none[4].solo_volume_mod, none[4].solo_density_mod, none[4].solo_ghost_mod,
           none[4].solo_fill_mod, none[4].solo_simplify, none[4].solo_reverb_mod,
           literals ? "OK" : "FAIL");

    bool pass = bank_inert && echo_matches && literals;
    printf("  Unauthored duck unchanged: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_authored_profile_replaces_the_duck() {
    printf("\n=== Test: an authored DuckingProfile replaces the duck constants ===\n");

    BandSoloConfigParam config{}; BandSoloState state{};
    build_duck_config(config, state);

    // Every track declares the same deep, non-simplifying duck. Only the two standard-duck
    // branches may act on it.
    DuckingParam bank[kNumPulsarTracks]{};
    for (int t = 0; t < kNumPulsarTracks; t++) {
        bank[t].volume_reduction  = 0.6f;
        bank[t].density_reduction = 0.5f;
        bank[t].ghost_reduction   = 0.1f;
        bank[t].fill_suppression  = 0.9f;
        bank[t].simplify          = false;
        bank[t].reverb_boost      = 0.25f;
        bank[t].declared          = true;
    }

    PulsarTrackState tracks[kNumPulsarTracks]{};
    apply_band_solo_modifiers(tracks, config, state, kNumPulsarTracks, bank);

    bool ducked_ok = true;
    const int ducked[2] = {4, 5};
    for (int i = 0; i < 2; i++) {
        const PulsarTrackState& ts = tracks[ducked[i]];
        ducked_ok = ducked_ok &&
            ts.solo_volume_mod == -0.6f && ts.solo_density_mod == -0.5f &&
            ts.solo_ghost_mod  == -0.1f && ts.solo_fill_mod    == -0.9f &&
            ts.solo_simplify   == false && ts.solo_reverb_mod  == 0.25f;
    }

    // "The kit never fully steps back" is a band rule, so an always-active member's
    // tracks ignore the profile entirely.
    bool always_active_untouched =
        tracks[0].solo_volume_mod  == 0.0f  && tracks[0].solo_density_mod == -0.15f &&
        tracks[0].solo_simplify    == true  && tracks[0].solo_reverb_mod  == 0.0f;
    // The lead is boosted, never ducked.
    bool lead_untouched = tracks[3].is_soloist && tracks[3].solo_volume_mod > 0.0f;

    printf("  ducked tracks take the authored profile -- %s\n", ducked_ok ? "OK" : "FAIL");
    printf("  always-active member ignores it (vol=%.3f dens=%.3f) -- %s\n",
           tracks[0].solo_volume_mod, tracks[0].solo_density_mod,
           always_active_untouched ? "OK" : "FAIL");
    printf("  lead still boosted (vol=%.3f) -- %s\n",
           tracks[3].solo_volume_mod, lead_untouched ? "OK" : "FAIL");

    bool pass = ducked_ok && always_active_untouched && lead_untouched;
    printf("  Authored profile replaces the duck: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Engine-level: the authored number reaches the audio ──────────────

static constexpr const char* kDuckPulsarUri = "org.balch.orpheus.plugins.pulsar";
static constexpr int kDuckTrack = 0;   // PERCUSSIVE per setup_fixture_baseline's role table
static constexpr int kDuckLeadTrack = 3;   // MELODIC — so the only JAM-eligible member

// One long JAM section and a two-member band. kDuckTrack's member owns no melodic track,
// so JAM eligibility makes track 3 the only possible lead and kDuckTrack stays SUPPORT
// for the whole run — what we measure is purely its duck.
static void push_duck_render_arrangement(OrpheusEngine* engine) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    // Stride from the header, never a literal: a stale one makes the arrangement
    // silently fail to advance.
    constexpr int kStride = kSectionDataFields;
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_section_data[0].store(64.0f, std::memory_order_relaxed);   // bars_min
    engine->pulsar_section_data[1].store(64.0f, std::memory_order_relaxed);   // bars_max
    engine->pulsar_section_data[2].store(1.0f, std::memory_order_relaxed);    // bar_step
    engine->pulsar_section_data[4].store(1.0f, std::memory_order_relaxed);    // transition_count
    for (int f = 5; f <= 8; f++)                                             // no macro override
        engine->pulsar_section_data[f].store(-1.0f, std::memory_order_relaxed);
    engine->pulsar_section_data[9].store(
        static_cast<float>(static_cast<int>(SoloModeId::JAM)), std::memory_order_relaxed);
    engine->pulsar_section_data[10].store(1.0f, std::memory_order_relaxed);   // solo_probability
    engine->pulsar_section_data[12].store(0.5f, std::memory_order_relaxed);   // lick_influence
    for (int f = 18; f <= 20; f++)                                           // no comping override
        engine->pulsar_section_data[f].store(-1.0f, std::memory_order_relaxed);

    float trans[kMaxSections * kMaxSectionTransitions * 3] = {};
    trans[0] = 0; trans[1] = 1.0f; trans[2] = 0;    // self-loop, hard cut
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(2, std::memory_order_relaxed);
    for (int i = 0; i < 96; i++)
        engine->pulsar_band_member_data[i].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0].store(1.0f, std::memory_order_relaxed);   // track_count
    engine->pulsar_band_member_data[1].store(static_cast<float>(kDuckLeadTrack), std::memory_order_relaxed);
    engine->pulsar_band_member_data[10].store(0.8f, std::memory_order_relaxed);  // loudness
    engine->pulsar_band_member_data[11].store(0.9f, std::memory_order_relaxed);  // creativity
    engine->pulsar_band_member_data[12 + 0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 1].store(static_cast<float>(kDuckTrack), std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 10].store(0.5f, std::memory_order_relaxed);

    // Empty pull-in matrix so the ducked member is never promoted out of SUPPORT, and a
    // lead span far longer than the run so no handoff arms a kit fill mid-measurement.
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    engine->pulsar_band_bars_per_lead_min.store(4096, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_max.store(4096, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
}

// Write one track's ducking row through the routing layer, so the wire indices are
// exercised too rather than assumed.
static void set_duck_row(OrpheusEngine* engine, int track, const DuckingParam& dp) {
    const float vals[kTrackDuckingFields] = {
        dp.volume_reduction, dp.density_reduction, dp.ghost_reduction,
        dp.fill_suppression, dp.simplify ? 1.0f : 0.0f, dp.reverb_boost, 1.0f,
    };
    for (int f = 0; f < kTrackDuckingFields; f++) {
        char sym[32];
        snprintf(sym, sizeof(sym), "track_ducking_%d", track * kTrackDuckingFields + f);
        orpheus_engine_set_port(engine, kDuckPulsarUri, sym, vals[f]);
    }
}

struct DuckRender {
    std::vector<float> samples;
    int fired = 0;
    bool solo_ran = false;
    double rms() const {
        if (samples.empty()) return 0.0;
        double sum = 0.0;
        for (float s : samples) sum += static_cast<double>(s) * s;
        return std::sqrt(sum / samples.size());
    }
};

// Render the ducked track alone. Everything but the authored profile is pinned,
// both RNGs included, so two renders differ only where the profile does.
static DuckRender render_duck(const DuckingParam* authored) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(0x5EED, std::memory_order_relaxed);
    stmlib::Random::Seed(0x5EED);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    solo_track(engine, kDuckTrack);          // only the ducked track is audible
    push_duck_render_arrangement(engine);
    if (authored) set_duck_row(engine, kDuckTrack, *authored);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
    trigger_vibe_load(engine);

    // The duck mods slew per bar, so give them a warm-up before the measured window.
    constexpr int kBlock = 256, kWarmBlocks = 400, kTotalBlocks = 1600;
    DuckRender out;
    out.samples.reserve((kTotalBlocks - kWarmBlocks) * kBlock * 2);
    float prev_timer = 0.0f;
    for (int b = 0; b < kTotalBlocks; b++) {
        unit_process_pulsar(&unit, engine, kBlock, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (ps && ps->band_solo_state.active) out.solo_ran = true;
        if (b < kWarmBlocks) continue;
        for (int i = 0; i < kBlock; i++) {
            out.samples.push_back(engine->pulsar_out_l[i]);
            out.samples.push_back(engine->pulsar_out_r[i]);
        }
        if (ps) {
            float t = ps->tracks[kDuckTrack].gate_timer;
            if (t > prev_timer + 1.0f) out.fired++;   // retrigger: gate_timer jumps up
            prev_timer = t;
        }
    }
    orpheus_engine_destroy(engine);
    return out;
}

static DuckingParam baseline_duck() {
    DuckingParam dp;
    dp.volume_reduction  = kUnauthoredDuckVolume;
    dp.density_reduction = kUnauthoredDuckDensity;
    dp.ghost_reduction   = kUnauthoredDuckGhost;
    dp.fill_suppression  = kUnauthoredDuckFill;
    dp.reverb_boost      = kUnauthoredDuckReverb;
    dp.simplify          = true;
    dp.declared          = true;
    return dp;
}

// Golden: rendering an unauthored track, and rendering one that authors the engine's own
// numbers, must agree sample for sample. That is the whole bit-identity claim — if the
// sign convention or the declared gate were wrong, these two streams would diverge.
static bool test_unauthored_render_is_bit_identical() {
    printf("\n=== Test: unauthored render is bit-identical to authoring the engine's duck ===\n");

    DuckRender plain = render_duck(nullptr);      // no profile: engine constants
    DuckingParam echo = baseline_duck();          // the same numbers, authored
    DuckRender same = render_duck(&echo);

    bool ran = plain.solo_ran && same.solo_ran;
    bool audible = plain.rms() > 1e-5;
    bool identical = (plain.samples == same.samples) && (plain.fired == same.fired);

    printf("  solo ran=%s  rms=%.6f  fired=%d/%d\n",
           ran ? "yes" : "no", plain.rms(), plain.fired, same.fired);
    printf("  %zu samples compared, sample-identical -- %s\n",
           plain.samples.size(), identical ? "OK" : "FAIL");

    bool pass = ran && audible && identical;
    printf("  Unauthored render bit-identical: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// densityReduction is the duck field the render actually consumes (duck_passes' step
// gate). volumeReduction, ghostReduction and reverbBoost are all inert today — see the
// KDoc on DuckingProfile — so this is where "the number reaches the audio" is provable.
static bool test_duck_depth_reaches_the_audio() {
    printf("\n=== Test: authored densityReduction reaches the rendered audio ===\n");

    DuckRender plain = render_duck(nullptr);          // no profile: engine constants (0.2)

    DuckingParam deep = baseline_duck();
    deep.density_reduction = 0.7f;                    // only the density differs
    DuckRender deeper = render_duck(&deep);

    DuckingParam shallow = baseline_duck();
    shallow.density_reduction = 0.0f;
    DuckRender shallower = render_duck(&shallow);

    bool ran = plain.solo_ran && deeper.solo_ran && shallower.solo_ran;

    printf("  fired: deeper(0.7)=%d  baseline(0.2)=%d  shallower(0.0)=%d\n",
           deeper.fired, plain.fired, shallower.fired);
    printf("  rms:   deeper=%.6f  baseline=%.6f  shallower=%.6f\n",
           deeper.rms(), plain.rms(), shallower.rms());

    bool fewer_hits = deeper.fired < plain.fired && plain.fired < shallower.fired;
    bool quieter = deeper.rms() < plain.rms() && plain.rms() < shallower.rms();
    printf("  deeper < baseline < shallower (hits) -- %s  (rms) -- %s\n",
           fewer_hits ? "OK" : "FAIL", quieter ? "OK" : "FAIL");

    bool pass = ran && fewer_hits && quieter;
    printf("  Duck depth reaches the audio: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_simplify_false_keeps_ornament_hits() {
    printf("\n=== Test: simplify=false stops the sub-0.45-velocity drop ===\n");

    // Only `simplify` differs, so the duck depths — and therefore every duck_passes
    // decision — are identical; the extra hits can only be the ornament ones.
    DuckingParam simplifying = baseline_duck();
    DuckingParam keeping = baseline_duck();
    keeping.simplify = false;

    DuckRender simple = render_duck(&simplifying);
    DuckRender full = render_duck(&keeping);

    bool ran = simple.solo_ran && full.solo_ran;
    bool more_hits = full.fired > simple.fired;

    printf("  fired: simplify=true %d  simplify=false %d -- %s\n",
           simple.fired, full.fired, more_hits ? "OK" : "FAIL");

    bool pass = ran && more_hits;
    printf("  simplify=false keeps ornament hits: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_solo_curves_are_seeded_and_deterministic() {
    printf("\n=== Test: solo curves are deterministic per seed and differ across seeds ===\n");
    KitRide a1 = kit_ride(0.5f, 1234u, 3), a2 = kit_ride(0.5f, 1234u, 3);
    bool same = a1.density_mod == a2.density_mod && a1.volume_mod == a2.volume_mod;
    bool differs = false;
    for (int bar = 0; bar < 8 && !differs; bar++)
        differs = kit_ride(0.5f, 1234u, bar).density_mod != kit_ride(0.5f, 99u, bar).density_mod;
    float sh = solo_shape(1234u, kKitSalt);
    bool in_range = sh >= kShapeMin && sh <= kShapeMax;
    bool pass = same && differs && in_range;
    printf("  same=%d differs=%d shape=%.3f -- %s\n", same, differs, sh, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_kit_ride_climbs_from_duck_to_boost() {
    printf("\n=== Test: kit ride climbs from a density cut to a boost ===\n");
    bool pass = true;
    const uint32_t seeds[4] = {1u, 77u, 4242u, 0xBEEFu};
    for (uint32_t s : seeds) {
        KitRide lo = kit_ride(0.0f, s, 0), mid = kit_ride(0.6f, s, 19), hi = kit_ride(1.0f, s, 31);
        bool climbs = (hi.density_mod - lo.density_mod) > 0.15f;
        bool ordered = mid.density_mod > lo.density_mod - 2.0f * kKitJitter &&
                       mid.density_mod < hi.density_mod + 2.0f * kKitJitter;
        bool louder = hi.volume_mod > lo.volume_mod && lo.volume_mod >= 0.0f;
        bool simp = lo.simplify && !hi.simplify;
        bool ok = climbs && ordered && louder && simp;
        printf("  seed %u: density %.3f -> %.3f -> %.3f volume %.3f -> %.3f simplify %d->%d -- %s\n",
               s, lo.density_mod, mid.density_mod, hi.density_mod, lo.volume_mod, hi.volume_mod,
               lo.simplify, hi.simplify, ok ? "OK" : "FAIL");
        pass = pass && ok;
    }
    return pass;
}

static bool test_drum_arc_builds_and_climaxes_on_the_last_bar() {
    printf("\n=== Test: drum arc builds hats and ghosts, climax only on the last bar ===\n");
    DrumArc a0 = drum_arc(0.0f, false, 55u, 0), a1 = drum_arc(1.0f, false, 55u, 3);
    DrumArc last = drum_arc(1.0f, true, 55u, 3);
    bool hats = a1.hat_prob > a0.hat_prob + 0.3f;
    bool ghosts = a0.ghost_prob <= kDrumGhostEnd * kDrumJitter + 1e-6f && a1.ghost_prob > 0.2f;
    bool gain = a0.overlay_gain < a1.overlay_gain && a1.overlay_gain <= 1.0f;
    bool climax = !a1.climax && last.climax;
    bool pass = hats && ghosts && gain && climax;
    printf("  hat %.2f->%.2f ghost %.2f->%.2f gain %.2f->%.2f climax %d/%d -- %s\n",
           a0.hat_prob, a1.hat_prob, a0.ghost_prob, a1.ghost_prob, a0.overlay_gain, a1.overlay_gain,
           a1.climax, last.climax, pass ? "PASS" : "FAIL");
    return pass;
}

// ── The band builds behind the soloist (engine-level) ────────────────

// Per-bar stats of a LickBuilder solo: the kit (0-2), the bass (3, SUPPORT) and the lead (4).
struct SoloBar {
    int kit_hits = 0; float kit_vel = 0.0f;
    float bass_density_target = 0.0f;
    float lead_density_current = 0.0f, lead_density_target = 0.0f;
    bool active = false;
    int bass_slaps = 0;
};

// Runs the engine and samples a SoloBar per bar. Shared by run_lickbuilder_capture
// and any test that needs its own arrangement tweak before capture starts.
// include_inactive also records bars where the solo has ended (default: solo-only,
// matching the original capture behavior every existing caller relies on).
static std::vector<SoloBar> capture_solo_bars(OrpheusEngine* engine, GraphUnit& unit, int want_bars,
                                               int lead_track, bool include_inactive) {
    std::vector<SoloBar> out;
    int last_loop = -1; SoloBar cur; float prev_timer[kNumPulsarTracks] = {};
    for (int b = 0; b < 40000 && (int)out.size() < want_bars; b++) {
        unit_process_pulsar(&unit, engine, 128, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->loop_count != last_loop) {
            if (last_loop >= 0 && (cur.active || include_inactive)) out.push_back(cur);
            last_loop = ps->loop_count; cur = SoloBar{};
            cur.active = ps->band_solo_state.active;
            cur.lead_density_current = ps->tracks[lead_track].solo_density_mod_current;
            cur.lead_density_target  = ps->tracks[lead_track].solo_density_mod;
            cur.bass_density_target  = ps->tracks[3].solo_density_mod;
            for (int s = 1; s < ps->tracks[3].step_count; s += 2) {
                const PulsarStep& st = ps->tracks[3].steps[s];
                if (st.gate && st.velocity == kBassSlapVelocity && st.duration == kBassSlapDuration) cur.bass_slaps++;
            }
        }
        for (int t = 0; t < 4; t++) {
            float g = ps->tracks[t].gate_timer;
            if (g > prev_timer[t] + 1.0f && t < 3) {
                cur.kit_hits++; cur.kit_vel += ps->tracks[t].current_velocity;
            }
            prev_timer[t] = g;
        }
    }
    return out;
}

static std::vector<SoloBar> run_lickbuilder_capture(uint32_t seed, int want_bars, int lead_track = 4,
                                                      bool include_inactive = false) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(seed, std::memory_order_relaxed);
    stmlib::Random::Seed(seed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    engine->pulsar_lick[0] = {0, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[1] = {2, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[2] = {4, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[3] = {1, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    push_lickbuilder_band_arrangement(engine, lead_track);
    trigger_vibe_load(engine);

    std::vector<SoloBar> out = capture_solo_bars(engine, unit, want_bars, lead_track, include_inactive);
    orpheus_engine_destroy(engine);
    return out;
}

// Louder is measured as summed kit velocity over the window (total kit energy), not the
// mean per hit: past the simplify midpoint the quiet ghosts return, which raises the
// total while lowering the mean, so a per-hit average would fight the busier assertion.
// The margin has to clear the pre-feature baseline (kit_ride not wired, old fixed
// -0.15/simplify branch): that baseline still drifts busier/louder by ~1.2x hits /
// ~1.15x vel just from the duck easing off near the section's downbeat, so a bare
// late > early would pass without the feature. With the feature wired the ride reaches
// ~2.1x hits / ~1.9x vel, so 1.5x sits clear of the baseline and under the feature.
static bool test_kit_builds_behind_the_soloist() {
    printf("\n=== Test: the kit builds behind a melodic soloist ===\n");
    std::vector<SoloBar> bars = run_lickbuilder_capture(0x51DE, 30);
    if (bars.size() < 30) { printf("  only %zu solo bars captured -- FAIL\n", bars.size()); return false; }
    int early_hits = 0, late_hits = 0; float early_vel = 0, late_vel = 0;
    for (int i = 1; i <= 4; i++)   { early_hits += bars[i].kit_hits; early_vel += bars[i].kit_vel; }
    for (int i = 25; i <= 28; i++) { late_hits  += bars[i].kit_hits; late_vel  += bars[i].kit_vel; }
    bool busier = late_hits * 2 >= early_hits * 3;
    bool louder = late_vel >= 1.5f * early_vel;
    printf("  kit hits early=%d late=%d  total vel early=%.3f late=%.3f -- %s\n",
           early_hits, late_hits, early_vel, late_vel, (busier && louder) ? "PASS" : "FAIL");
    return busier && louder;
}

// The bass in this fixture is too sparse to prove an ease of up to half depth by
// counting fires, so this pins the mechanism deterministically instead: the target
// apply_band_solo_modifiers wrote that bar, not how many of its gated steps happened
// to pass.
static bool test_support_duck_eases_late_in_the_solo() {
    printf("\n=== Test: a support track's density duck eases as the solo builds ===\n");
    std::vector<SoloBar> bars = run_lickbuilder_capture(0x51DE, 30);
    if (bars.size() < 30) return false;
    float early_sum = 0, late_sum = 0;
    for (int i = 1; i <= 6; i++)   early_sum += bars[i].bass_density_target;
    for (int i = 23; i <= 28; i++) late_sum  += bars[i].bass_density_target;
    float early_mean = early_sum / 6.0f, late_mean = late_sum / 6.0f;
    bool both_ducked = early_mean < 0.0f && late_mean < 0.0f;
    bool eased = (late_mean - early_mean) >= 0.05f;
    bool pass = both_ducked && eased;
    printf("  bass duck target mean early=%.4f late=%.4f (late-early=%.4f) -- %s\n",
           early_mean, late_mean, late_mean - early_mean, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_soloist_entrance_lands_at_half_boost() {
    printf("\n=== Test: the soloist's first bar starts at least half its boost ===\n");
    std::vector<SoloBar> bars = run_lickbuilder_capture(0x51DE, 4);
    if (bars.empty()) return false;
    const SoloBar& first = bars[0];
    bool pass = first.lead_density_target > 0.0f &&
                first.lead_density_current >= kSoloEntranceFraction * first.lead_density_target - 1e-4f;
    printf("  bar 1 density current=%.3f target=%.3f -- %s\n",
           first.lead_density_current, first.lead_density_target, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_ducked_bass_gets_slaps() {
    printf("\n=== Test: a ducked bass keeps slapping under the solo ===\n");
    std::vector<SoloBar> bars = run_lickbuilder_capture(0x51DE, 12);
    int slaps = 0; bool all_ducked = true;
    for (const SoloBar& b : bars) {
        slaps += b.bass_slaps;
        if (b.bass_density_target >= 0.0f) all_ducked = false;
    }
    bool pass = bars.size() == 12 && slaps > 0 && all_ducked;
    printf("  slap steps over %zu bars=%d all_ducked=%d -- %s\n",
           bars.size(), slaps, all_ducked, pass ? "PASS" : "FAIL");
    return pass;
}

// Engine-level: solo_slaps_present must survive clear_solo_modifiers for the strip
// pass to get a bar to run once the solo ends. Repoints the LickBuilder section's
// self-loop at the NONE intro so it actually ends, and shrinks it from 32 bars to
// 3 -- at 32 it lands exactly on the déjà-vu periodic reset (also 32 bars at
// complexity 0), which regenerates the bass pattern on its own and would mask
// whether this pass is what stripped it.
static bool test_slaps_are_stripped_when_the_solo_ends() {
    printf("\n=== Test: slaps are stripped once the solo ends ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    uint32_t seed = 0x51DE;
    engine->pulsar_seed.store(seed, std::memory_order_relaxed);
    stmlib::Random::Seed(seed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    engine->pulsar_lick[0] = {0, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[1] = {2, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[2] = {4, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[3] = {1, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    push_lickbuilder_band_arrangement(engine, 4);
    // Section 1 (LICK_BUILDER, base = 1 * kSectionDataFields): shrink bars_min/max
    // from 32 to 3, well clear of the 32-bar déjà-vu reset (see comment above).
    engine->pulsar_section_data[kSectionDataFields + 0].store(3.0f, std::memory_order_relaxed);
    engine->pulsar_section_data[kSectionDataFields + 1].store(3.0f, std::memory_order_relaxed);
    // s1 edge 0 (base 8*3=24) normally self-loops (target=1); point it at
    // section 0 (the 1-bar NONE intro) so the solo actually ends.
    engine->pulsar_section_transitions[24].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(2, std::memory_order_release);
    trigger_vibe_load(engine);

    // 5 cycles of (1-bar NONE + 3-bar LickBuilder), all inside loop_count < 20 --
    // comfortably short of bar 32, where the déjà-vu reset could otherwise fire.
    std::vector<SoloBar> bars = capture_solo_bars(engine, unit, 20, 4, /*include_inactive=*/true);
    orpheus_engine_destroy(engine);

    bool any_slapped = false;
    for (const SoloBar& b : bars) if (b.active && b.bass_slaps > 0) any_slapped = true;
    bool found_end = false, stripped = false;
    for (size_t i = 1; i < bars.size() && !found_end; i++) {
        if (bars[i - 1].active && !bars[i].active) {
            found_end = true;
            stripped = bars[i].bass_slaps == 0;
        }
    }
    bool pass = any_slapped && found_end && stripped;
    printf("  bars=%zu any_slapped=%d found_end=%d stripped_on_end_bar=%d -- %s\n",
           bars.size(), any_slapped, found_end, stripped, pass ? "PASS" : "FAIL");
    return pass;
}

static bool same_step(const PulsarStep& a, const PulsarStep& b) {
    return a.note == b.note && a.gate == b.gate && a.velocity == b.velocity && a.duration == b.duration;
}

static bool test_drum_lead_hands_the_groove_back() {
    printf("\n=== Test: a drum lead snapshots the groove and restores it on exit ===\n");
    BandSoloConfigParam cfg{}; cfg.member_count = 2;
    cfg.members[0].track_count = 3; cfg.members[0].tracks[0] = 0; cfg.members[0].tracks[1] = 1; cfg.members[0].tracks[2] = 2;
    cfg.members[0].always_active = true;
    cfg.members[1].track_count = 1; cfg.members[1].tracks[0] = 4;
    PulsarTrackState tracks[kNumPulsarTracks]{};
    for (int t = 0; t < 3; t++) { tracks[t].role = TrackRole::PERCUSSIVE; tracks[t].step_count = 16; }
    tracks[4].role = TrackRole::MELODIC; tracks[4].step_count = 16;
    for (int i = 0; i < 16; i += 4) tracks[0].steps[i] = make_step(36, 0.9f, true, 0.5f);
    for (int i = 4; i < 16; i += 8) tracks[1].steps[i] = make_step(40, 0.8f, true, 0.4f);
    for (int i = 0; i < 16; i += 2) tracks[2].steps[i] = make_step(42, 0.4f, true, 0.2f);
    // PulsarTrackState embeds an OrpheusVoice (non-copyable, MI DISALLOW_COPY_AND_ASSIGN),
    // so snapshot just the steps this test actually inspects rather than whole tracks.
    PulsarStep before[3][kMaxPulsarSteps];
    for (int t = 0; t < 3; t++) std::memcpy(before[t], tracks[t].steps, sizeof(before[t]));

    BandSoloState st{}; st.active = true; st.lead_member = 0; st.drum_lead_style = 1;
    st.member_role[0] = MemberSoloRole::LEADING; st.member_bars_remaining[0] = 3;
    begin_drum_lead(st, cfg, tracks, kNumPulsarTracks);
    bool armed = st.drum_groove_valid && st.drum_span_bars == 3;

    PulsarLickStep lick[4] = {{0, 1.0f, 0.9f, -1.0f}, {2, 1.0f, 0.5f, -1.0f}, {4, 1.0f, 0.3f, -1.0f}, {5, 1.0f, 0.8f, -1.0f}};
    uint32_t seed = 7;
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st);
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st);
    bool changed = false;
    for (int i = 0; i < 16 && !changed; i++) changed = !same_step(tracks[0].steps[i], before[0][i]) || !same_step(tracks[2].steps[i], before[2][i]);

    end_drum_lead(st, cfg, tracks, kNumPulsarTracks);
    bool restored = !st.drum_groove_valid;
    for (int t = 0; t < 3 && restored; t++)
        for (int i = 0; i < 16; i++) if (!same_step(tracks[t].steps[i], before[t][i])) { restored = false; break; }
    bool pass = armed && changed && restored;
    printf("  armed=%d changed_during=%d restored=%d -- %s\n", armed, changed, restored, pass ? "PASS" : "FAIL");
    return pass;
}

// A section seam with a carried lead ends the lead, lets the section re-author the kit,
// then re-arms it. The re-arm has to snapshot the kit as it is NOW, or the new section's
// density is buried under the old groove at the next restore.
static bool test_drum_lead_resnapshots_after_a_section_seam() {
    printf("\n=== Test: re-arming a drum lead snapshots the current kit, not the old groove ===\n");
    BandSoloConfigParam cfg{}; cfg.member_count = 1;
    cfg.members[0].track_count = 3; cfg.members[0].tracks[0] = 0; cfg.members[0].tracks[1] = 1; cfg.members[0].tracks[2] = 2;
    cfg.members[0].always_active = true;
    PulsarTrackState tracks[kNumPulsarTracks]{};
    for (int t = 0; t < 3; t++) { tracks[t].role = TrackRole::PERCUSSIVE; tracks[t].step_count = 16; }
    for (int i = 0; i < 16; i += 4) tracks[0].steps[i] = make_step(36, 0.9f, true, 0.5f);

    BandSoloState st{}; st.active = true; st.lead_member = 0;
    st.drum_lead_style = static_cast<int>(DrumLeadStyle::LOCK_IN);
    st.member_role[0] = MemberSoloRole::LEADING; st.member_bars_remaining[0] = 4;
    begin_drum_lead(st, cfg, tracks, kNumPulsarTracks);
    const int carried_span = st.drum_span_bars;

    // The seam: hand the groove back, let the section re-author the kick, re-arm.
    end_drum_lead(st, cfg, tracks, kNumPulsarTracks);
    for (int i = 0; i < 16; i++) tracks[0].steps[i] = make_step(36, 0.6f, (i % 2) == 0, 0.3f);
    st.member_bars_remaining[0] = 1;
    begin_drum_lead(st, cfg, tracks, kNumPulsarTracks);

    bool resnapped = st.drum_groove_valid;
    for (int i = 0; i < 16 && resnapped; i++) resnapped = same_step(st.drum_groove[0][i], tracks[0].steps[i]);
    // begin_drum_lead rewrites the span from member_bars_remaining, which is exactly why
    // the engine puts the carried span back after re-arming a lead mid-span.
    bool span_overwritten = carried_span == 4 && st.drum_span_bars == 1;
    bool pass = resnapped && span_overwritten;
    printf("  resnapped=%d span %d -> %d -- %s\n", resnapped, carried_span, st.drum_span_bars, pass ? "PASS" : "FAIL");
    return pass;
}

// A carried drum lead (jamCarry into a LONG_FILL section) that expires via the LongFill
// bar-count path must still hand its groove back -- that path returns early and does not
// go through the normal handoff expiry block.
static bool test_long_fill_expiry_hands_the_groove_back() {
    printf("\n=== Test: a LongFill expiry hands a carried drum lead's groove back ===\n");
    BandSoloConfigParam cfg{}; cfg.member_count = 1;
    cfg.members[0].track_count = 3; cfg.members[0].tracks[0] = 0; cfg.members[0].tracks[1] = 1; cfg.members[0].tracks[2] = 2;
    cfg.members[0].always_active = true;
    PulsarTrackState tracks[kNumPulsarTracks]{};
    for (int t = 0; t < 3; t++) { tracks[t].role = TrackRole::PERCUSSIVE; tracks[t].step_count = 16; }
    for (int i = 0; i < 16; i += 4) tracks[0].steps[i] = make_step(36, 0.9f, true, 0.5f);
    for (int i = 4; i < 16; i += 8) tracks[1].steps[i] = make_step(40, 0.8f, true, 0.4f);
    for (int i = 0; i < 16; i += 2) tracks[2].steps[i] = make_step(42, 0.4f, true, 0.2f);

    BandSoloState st{}; st.active = true; st.lead_member = 0;
    st.drum_lead_style = static_cast<int>(DrumLeadStyle::LOCK_IN);
    st.member_role[0] = MemberSoloRole::LEADING; st.member_bars_remaining[0] = 1;
    begin_drum_lead(st, cfg, tracks, kNumPulsarTracks);

    // Snapshot what begin_drum_lead captured, then mutate the kit as a lick render
    // would, so "restored" below is checking a real change, not a no-op.
    PulsarStep before[3][kMaxPulsarSteps];
    for (int t = 0; t < 3; t++) std::memcpy(before[t], tracks[t].steps, sizeof(before[t]));
    PulsarLickStep lick[4] = {{0, 1.0f, 0.9f, -1.0f}, {2, 1.0f, 0.5f, -1.0f}, {4, 1.0f, 0.3f, -1.0f}, {5, 1.0f, 0.8f, -1.0f}};
    uint32_t seed = 7;
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st);
    bool changed = false;
    for (int i = 0; i < 16 && !changed; i++)
        for (int t = 0; t < 3 && !changed; t++) changed = !same_step(tracks[t].steps[i], before[t][i]);

    SectionParam section{}; section.solo_mode = SoloModeId::LONG_FILL;
    section.solo_probability = 1.0f; section.solo_bars_min = 3; section.solo_bars_max = 3;
    advance_band_solo(st, cfg, section, tracks, seed);

    bool ended = !st.active;
    bool cleared = !st.drum_groove_valid;
    bool style_reset = st.drum_lead_style == -1;
    bool restored = true;
    for (int t = 0; t < 3 && restored; t++)
        for (int i = 0; i < 16; i++) if (!same_step(tracks[t].steps[i], before[t][i])) { restored = false; break; }
    bool pass = changed && ended && cleared && style_reset && restored;
    printf("  changed_during=%d ended=%d groove_cleared=%d style_reset=%d restored=%d -- %s\n",
           changed, ended, cleared, style_reset, restored, pass ? "PASS" : "FAIL");
    return pass;
}

// Engine-level drum lead. The 12% handoff gate is far too rare to hunt, so the drummer is
// handed a BREAK span by hand at a loop wrap and the whole shape is watched go by: the deep
// melodic duck from the very first bar, the bass still slapping under it, the climax on the
// last bar, and the kit's own groove back on the release.
struct LeadBar {
    int style = -1, lead_fires = 0, lead_gated = 0, bass_fires = 0, bass_slaps = 0;
    int hat_steps = 0, snare_q[4] = {0, 0, 0, 0};
    float lead_dmod = 0.0f, lead_dcur = 0.0f;
    bool kit_restored = false;
};

static bool test_drum_lead_end_to_end() {
    printf("\n=== Test: a forced BREAK drum lead ducks, slaps, climaxes and restores ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(0x51DE, std::memory_order_relaxed);
    stmlib::Random::Seed(0x51DE);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    engine->pulsar_lick[0] = {0, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[1] = {2, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[2] = {4, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick[3] = {1, 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    push_lickbuilder_band_arrangement(engine, 4);
    trigger_vibe_load(engine);

    std::vector<LeadBar> bars;
    PulsarStep snap[3][kMaxPulsarSteps];
    bool forced = false, recording = false;
    int last_loop = -1, solo_bars = 0; LeadBar cur; float prev_timer[kNumPulsarTracks] = {};
    for (int b = 0; b < 40000 && bars.size() < 5; b++) {
        unit_process_pulsar(&unit, engine, 128, 48000.0f);
        PulsarState* ps = engine->pulsar_state; if (!ps) continue;
        if (ps->loop_count != last_loop) {
            last_loop = ps->loop_count;
            if (!forced) {
                // Let the LickBuilder render the lead a few times first, so the track
                // carries real gated steps into the break instead of an empty row.
                if (ps->band_solo_state.active) solo_bars++;
                if (solo_bars < 4) continue;
                BandSoloState& bss = ps->band_solo_state;
                bss.lead_member = 0;                                  // the drummer
                bss.member_role[0] = MemberSoloRole::LEADING;
                bss.member_role[1] = MemberSoloRole::ACTIVE;
                bss.member_bars_remaining[0] = 3;
                bss.member_bars_remaining[1] = 1;
                bss.drum_lead_style = static_cast<int>(DrumLeadStyle::BREAK);
                bss.last_handoff_was_drum = true;                     // no second drum span
                begin_drum_lead(bss, ps->band_solo_config, ps->tracks, kNumPulsarTracks);
                apply_band_solo_modifiers(ps->tracks, ps->band_solo_config, bss,
                                          kNumPulsarTracks, ps->track_ducking);
                for (int t = 0; t < 3; t++) std::memcpy(snap[t], ps->tracks[t].steps, sizeof(snap[t]));
                forced = true;
                continue;   // the poke lands mid-bar; record from the next one
            }
            if (recording) bars.push_back(cur);
            recording = true; cur = LeadBar{};
            cur.style = ps->band_solo_state.drum_lead_style;
            cur.lead_dmod = ps->tracks[4].solo_density_mod;
            cur.lead_dcur = ps->tracks[4].solo_density_mod_current;
            const int sc = ps->tracks[0].step_count;
            for (int i = 0; i < sc; i++) {
                if (ps->tracks[4].steps[i].gate) cur.lead_gated++;
                if (ps->tracks[2].steps[i].gate) cur.hat_steps++;
                if (ps->tracks[1].steps[i].gate) cur.snare_q[(i * 4) / sc]++;
            }
            for (int i = 1; i < ps->tracks[3].step_count; i += 2) {
                const PulsarStep& st = ps->tracks[3].steps[i];
                if (st.gate && st.velocity == kBassSlapVelocity && st.duration == kBassSlapDuration) cur.bass_slaps++;
            }
            cur.kit_restored = true;
            for (int t = 0; t < 3 && cur.kit_restored; t++)
                for (int i = 0; i < sc; i++)
                    if (!same_step(ps->tracks[t].steps[i], snap[t][i])) { cur.kit_restored = false; break; }
        }
        if (recording) {
            for (int t = 3; t <= 4; t++) {
                float g = ps->tracks[t].gate_timer;
                if (g > prev_timer[t] + 1.0f) { if (t == 3) cur.bass_fires++; else cur.lead_fires++; }
                prev_timer[t] = g;
            }
        }
    }
    orpheus_engine_destroy(engine);

    for (size_t i = 0; i < bars.size(); i++)
        printf("  bar %zu style=%2d lead %d/%d fires dmod=%.2f cur=%.2f  bass %d fires %d slaps  hats=%d snare_q=%d/%d/%d/%d restored=%d\n",
               i, bars[i].style, bars[i].lead_fires, bars[i].lead_gated, bars[i].lead_dmod, bars[i].lead_dcur,
               bars[i].bass_fires, bars[i].bass_slaps, bars[i].hat_steps,
               bars[i].snare_q[0], bars[i].snare_q[1], bars[i].snare_q[2], bars[i].snare_q[3], bars[i].kit_restored);

    int first_lead = -1, last_lead = -1;
    for (size_t i = 0; i < bars.size(); i++)
        if (bars[i].style >= 0) { if (first_lead < 0) first_lead = (int)i; last_lead = (int)i; }
    // The span must open on the very first recorded bar and close inside the window.
    bool shape = first_lead == 0 && last_lead + 1 < (int)bars.size() && bars[last_lead + 1].style < 0;
    const int release = shape ? last_lead + 1 : 0;

    // (a) The BREAK-ducked melodic non-lead is all but silent, at full depth from bar one.
    bool ducked = shape;
    int bass_fires = 0, bass_slaps = 0;
    for (int i = first_lead; shape && i <= last_lead; i++) {
        int cap = static_cast<int>((1.0f + kBreakMelodicDuck) * bars[i].lead_gated) + 1;
        if (bars[i].lead_gated <= 0 || bars[i].lead_fires > cap) ducked = false;
        if (bars[i].lead_dcur != kBreakMelodicDuck) ducked = false;   // a cut, not a fade-in
        bass_fires += bars[i].bass_fires; bass_slaps += bars[i].bass_slaps;
    }
    // (b) The bass thins but keeps popping under the break.
    bool bass_ok = bass_fires > 0 && bass_slaps > 0;
    // (c) The kit gets its own groove back on the release, and the lead sings again.
    bool restored = shape && bars[release].kit_restored &&
                    bars[release].lead_dmod > kBreakMelodicDuck &&
                    (bars[release].lead_fires > 0 ||
                     (release + 1 < (int)bars.size() && bars[release + 1].lead_fires > 0));
    // (d) The climax: the last drum-lead bar ends on a full snare ramp the span's first
    // bar does not have.
    bool climax = shape && bars[last_lead].snare_q[3] == 4 &&
                  bars[last_lead].snare_q[3] > bars[first_lead].snare_q[3];

    bool pass = shape && ducked && bass_ok && restored && climax;
    printf("  span=[%d..%d] release=%d ducked=%d bass(fires=%d slaps=%d)=%d restored=%d climax=%d -- %s\n",
           first_lead, last_lead, release, ducked, bass_fires, bass_slaps, bass_ok,
           restored, climax, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_break_is_a_deep_duck_that_lands() {
    printf("\n=== Test: BREAK deep-ducks the melody and bass through the duck, snapping both ways ===\n");
    BandSoloConfigParam cfg{}; cfg.member_count = 3;
    cfg.members[0].track_count = 3; cfg.members[0].tracks[0] = 0; cfg.members[0].tracks[1] = 1; cfg.members[0].tracks[2] = 2; cfg.members[0].always_active = true;
    cfg.members[1].track_count = 1; cfg.members[1].tracks[0] = 3;   // bass
    cfg.members[2].track_count = 1; cfg.members[2].tracks[0] = 4;   // lead
    PulsarTrackState tracks[kNumPulsarTracks]{};
    for (int t = 0; t < 3; t++) tracks[t].role = TrackRole::PERCUSSIVE;
    tracks[3].role = tracks[4].role = tracks[5].role = TrackRole::MELODIC;   // 5 = in no member
    BandSoloState st{}; st.active = true; st.lead_member = 0;
    st.drum_lead_style = static_cast<int>(DrumLeadStyle::BREAK);
    st.member_role[0] = MemberSoloRole::LEADING; st.member_role[1] = st.member_role[2] = MemberSoloRole::SUPPORT;
    apply_band_solo_modifiers(tracks, cfg, st);
    bool melody = tracks[4].solo_density_mod == kBreakMelodicDuck && tracks[4].solo_density_mod_current == kBreakMelodicDuck
               && tracks[5].solo_density_mod == kBreakMelodicDuck && tracks[5].solo_density_mod_current == kBreakMelodicDuck;
    bool bass = tracks[3].solo_density_mod == kBreakBassDensityDuck && tracks[3].solo_density_mod_current == kBreakBassDensityDuck;
    bool kit = tracks[0].solo_density_mod > 0.0f;

    // Release: the lead passes to member 2; the ex-ducked tracks land on their new depth.
    st.drum_lead_style = -1; st.break_released = true; st.lead_member = 2;
    st.member_role[0] = MemberSoloRole::SUPPORT; st.member_role[2] = MemberSoloRole::LEADING;
    apply_band_solo_modifiers(tracks, cfg, st);
    bool landed = tracks[3].solo_density_mod_current == tracks[3].solo_density_mod
               && tracks[5].solo_density_mod_current == tracks[5].solo_density_mod
               && tracks[3].solo_density_mod > kBreakBassDensityDuck;
    bool new_lead = tracks[4].is_soloist && tracks[4].solo_density_mod > 0.0f;
    bool pass = melody && bass && kit && landed && new_lead;
    printf("  melody=%d bass=%d kit=%d landed=%d new_lead=%d -- %s\n", melody, bass, kit, landed, new_lead, pass ? "PASS" : "FAIL");
    return pass;
}

bool run_pulsar_band_solo_tests() {
    printf("\n========== PULSAR BAND SOLO TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_band_lead_selection());
    tally(test_always_active_not_ducked());
    tally(test_pull_in_mechanic());
    tally(test_pull_in_duration_and_dropout());
    tally(test_long_fill_no_handoff());
    tally(test_always_active_never_initial_lead());
    tally(test_empty_handoff_row_does_not_reselect_current_lead());
    tally(test_initial_lead_biased_by_creativity());
    tally(test_start_band_solo_seeds_recency_for_never_led());
    tally(test_personality_modifiers());
    tally(test_handoff_arms_percussive_fill_mod());
    tally(test_handoff_fill_duck_reopens_the_gate());
    tally(test_unauthored_duck_is_bit_identical());
    tally(test_authored_profile_replaces_the_duck());
    tally(test_unauthored_render_is_bit_identical());
    tally(test_duck_depth_reaches_the_audio());
    tally(test_simplify_false_keeps_ornament_hits());
    tally(test_solo_curves_are_seeded_and_deterministic());
    tally(test_kit_ride_climbs_from_duck_to_boost());
    tally(test_drum_arc_builds_and_climaxes_on_the_last_bar());
    tally(test_kit_builds_behind_the_soloist());
    tally(test_support_duck_eases_late_in_the_solo());
    tally(test_soloist_entrance_lands_at_half_boost());
    tally(test_ducked_bass_gets_slaps());
    tally(test_slaps_are_stripped_when_the_solo_ends());
    tally(test_drum_lead_hands_the_groove_back());
    tally(test_drum_lead_resnapshots_after_a_section_seam());
    tally(test_long_fill_expiry_hands_the_groove_back());
    tally(test_drum_lead_end_to_end());
    tally(test_break_is_a_deep_duck_that_lands());
    printf("\nPulsar band solo tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
