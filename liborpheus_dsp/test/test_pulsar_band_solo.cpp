#include "test_harness.h"
#include "../src/pulsar_band_solo.h"
#include <cstdio>
#include <cmath>

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

bool run_pulsar_band_solo_tests() {
    printf("\n========== PULSAR BAND SOLO TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_band_lead_selection());
    tally(test_always_active_not_ducked());
    tally(test_pull_in_mechanic());
    tally(test_pull_in_duration_and_dropout());
    tally(test_long_fill_no_handoff());
    tally(test_personality_modifiers());
    printf("\nPulsar band solo tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
