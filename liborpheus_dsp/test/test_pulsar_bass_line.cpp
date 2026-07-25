// Two-channel lick rendering: a BASS-source track renders Vibe.bassLine while a
// LEAD-source track renders Vibe.lick, in the same vibe, with independent
// mutation/octave. Mirrors the marshalling suite's setup idiom.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include <cstdio>
#include <cstring>

static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";

// Author maximally-distinct channels: lead = constant degree 0, bass = constant
// degree 4, both mutation 0 so renders are deterministic.
static void push_two_channels(OrpheusEngine* engine) {
    for (int i = 0; i < 4; i++) {
        int base = i * OrpheusEngine::kLickFieldsPerStep;
        char sym[36];
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 0);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 0.0f);       // degree 0
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 1);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 1.0f);       // quarter notes
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 2);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 0.8f);
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 3);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, -1.0f);
        snprintf(sym, sizeof(sym), "bass_line_data_%d", base + 0);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 4.0f);       // degree 4
        snprintf(sym, sizeof(sym), "bass_line_data_%d", base + 1);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 1.0f);
        snprintf(sym, sizeof(sym), "bass_line_data_%d", base + 2);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 0.8f);
        snprintf(sym, sizeof(sym), "bass_line_data_%d", base + 3);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, -1.0f);
    }
    orpheus_engine_set_port(engine, PULSAR_URI, "lick_mutation", 0.0f);
    orpheus_engine_set_port(engine, PULSAR_URI, "lick_octave", 3.0f);
    orpheus_engine_set_port(engine, PULSAR_URI, "lick_length", 4.0f);
    orpheus_engine_set_port(engine, PULSAR_URI, "bass_line_mutation", 0.0f);
    orpheus_engine_set_port(engine, PULSAR_URI, "bass_line_octave", 2.0f);
    orpheus_engine_set_port(engine, PULSAR_URI, "bass_line_length", 4.0f);
}

// Configure track 3 as the BASS-source FILL melodic and track 4 as LEAD FILL.
static void setup_two_lick_tracks(OrpheusEngine* engine) {
    engine->pulsar_track_role[3].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_role[4].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[3].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_track_lick_mode[4].store(2, std::memory_order_relaxed);  // FILL
    engine->pulsar_track_lick_source[3].store(1, std::memory_order_relaxed); // BASS
    engine->pulsar_track_lick_source[4].store(0, std::memory_order_relaxed); // LEAD
    engine->pulsar_track_chord_follow[3].store(2, std::memory_order_relaxed); // FIXED
    engine->pulsar_track_chord_follow[4].store(2, std::memory_order_relaxed); // FIXED
}

// First gated note of a track, or -1.
static int first_gated_note(PulsarState* ps, int t) {
    for (int i = 0; i < ps->tracks[t].step_count; i++)
        if (ps->tracks[t].steps[i].gate) return ps->tracks[t].steps[i].note;
    return -1;
}

static bool test_two_channel_render_distinct() {
    printf("\n=== Test: BASS track renders bassLine, LEAD track renders lick ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);
    push_two_channels(engine);
    setup_two_lick_tracks(engine);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr);
    if (ok) {
        int lead_note = first_gated_note(ps, 4);
        int bass_note = first_gated_note(ps, 3);
        // With mutation 0 and constant degrees, notes are constant per track.
        // Distinctness is the contract; exact pitch depends on root/scale/octave.
        bool distinct = (lead_note >= 0) && (bass_note >= 0) && (lead_note != bass_note);
        // Bass octave 2 vs lead octave 3 with a lower degree: bass must sit lower.
        bool bass_lower = bass_note < lead_note;
        printf("  lead first note=%d, bass first note=%d, distinct=%s, bass-lower=%s\n",
               lead_note, bass_note, distinct ? "OK" : "FAIL", bass_lower ? "OK" : "FAIL");
        // Every gated bass step must share one pitch (constant-degree lick, mutation 0)
        bool bass_constant = true;
        for (int i = 0; i < ps->tracks[3].step_count; i++)
            if (ps->tracks[3].steps[i].gate && ps->tracks[3].steps[i].note != bass_note)
                bass_constant = false;
        printf("  bass constant-pitch=%s\n", bass_constant ? "OK" : "FAIL");
        ok = distinct && bass_lower && bass_constant;
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_bass_only_vibe_renders() {
    printf("\n=== Test: bass line renders when no lead lick exists ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);
    push_two_channels(engine);
    orpheus_engine_set_port(engine, PULSAR_URI, "lick_length", 0.0f);  // kill the lead channel
    setup_two_lick_tracks(engine);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr) && (first_gated_note(ps, 3) >= 0);
    printf("  bass track has gated notes with lead lick absent -- %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// ── Channel-aware LickBuilder seeding (Task 5) ────────────────────────
//
// Adapted from push_lickbuilder_band_arrangement in test_pulsar_solos.cpp
// (SOLO-1): the same 2-member band (member 0 = always-active drums on
// tracks 0-2, member 1 = the sole non-drum member, owning ONLY `lead_track`)
// and the same 1-bar NONE intro -> 32-bar LickBuilder section shape, pushed
// through pulsar_band_*/pulsar_section_data atomics so start_band_solo runs
// for real inside unit_process_pulsar (not a direct struct-level call).
//
// One deliberate deviation from the original: solo_mutation_rate is 0 here
// (not 1). This test asserts an EXACT live_lick_degrees[0] at section entry;
// per-bar mutation is already covered by SOLO-1's
// test_lickbuilder_lead_evolving_in_scale in test_pulsar_solos.cpp and would
// make the exact-degree assertions here flaky for no benefit.
static void push_bass_channel_lickbuilder_arrangement(OrpheusEngine* engine, int lead_track) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = 21;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
    }
    // Section 0: 1-bar NONE intro, transitions to section 1.
    section_data[0] = 1;     // bars_min
    section_data[1] = 1;     // bars_max
    section_data[2] = 1;     // bar_step
    section_data[3] = 0.8f;  // recency_decay
    section_data[4] = 1;     // transition_count
    section_data[9] = 0;     // solo_mode = NONE

    // Section 1: 32-bar LickBuilder, self-loops.
    int s1 = kSectionStride;
    section_data[s1 + 0] = 32;     // bars_min
    section_data[s1 + 1] = 32;     // bars_max
    section_data[s1 + 2] = 1;      // bar_step
    section_data[s1 + 3] = 0.8f;   // recency_decay
    section_data[s1 + 4] = 1;      // transition_count
    section_data[s1 + 9]  = static_cast<float>(static_cast<int>(SoloModeId::LICK_BUILDER));
    section_data[s1 + 10] = 1.0f;  // solo_probability
    section_data[s1 + 11] = 0.0f;  // solo_mutation_rate -- see file-level comment above
    section_data[s1 + 12] = 0.5f;  // solo_lick_influence
    section_data[s1 + 13] = 2;     // solo_bars_min
    section_data[s1 + 14] = 4;     // solo_bars_max
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // Transitions: s0 -> {1:1.0}, s1 -> {1:1.0} (self-loop).
    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;  // s0 edge 0 -> section 1
    trans[24] = 1; trans[25] = 1.0f; trans[26] = 0;  // s1 edge 0 -> section 1 (s1 base = 8*3 = 24)
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Band config: member 0 = drums (always_active), member 1 = the sole
    // other member, owning ONLY lead_track ("member 1 owns ONLY track 3/4").
    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(2, std::memory_order_relaxed);
    // band_member_data layout per member: base = m*12
    //   [0]=track_count, [1..8]=tracks, [9]=always_active, [10]=loudness, [11]=creativity
    for (int i = 0; i < 96; i++)
        engine->pulsar_band_member_data[i].store(0.0f, std::memory_order_relaxed);
    // member 0: drums tracks 0,1,2 always_active
    engine->pulsar_band_member_data[0 + 0].store(3.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 1].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 2].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 3].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 9].store(1.0f, std::memory_order_relaxed);  // always_active
    engine->pulsar_band_member_data[0 + 10].store(0.7f, std::memory_order_relaxed); // loudness
    engine->pulsar_band_member_data[0 + 11].store(0.2f, std::memory_order_relaxed); // creativity
    // member 1: the sole non-drum member, on lead_track
    engine->pulsar_band_member_data[12 + 0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 1].store(static_cast<float>(lead_track), std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 9].store(0.0f, std::memory_order_relaxed);  // not always_active
    engine->pulsar_band_member_data[12 + 10].store(0.8f, std::memory_order_relaxed); // loudness
    engine->pulsar_band_member_data[12 + 11].store(0.9f, std::memory_order_relaxed); // creativity
    // Handoff/pull-in: keep the lead leading (member 1 -> member 1). Stride-N=2.
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    engine->pulsar_band_handoff_matrix[1 * 2 + 1].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_min.store(64, std::memory_order_relaxed);  // keep leading
    engine->pulsar_band_bars_per_lead_max.store(64, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_min.store(2, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_max.store(4, std::memory_order_relaxed);
    engine->pulsar_band_improv_carryover.store(0.7f, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    // Clear solo/ducking/markov to defaults
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 6; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// LickBuilder must seed its live buffer from the SOLOIST's channel: when the
// lead member's lick track is BASS-source, live_lick_degrees mirror the bass
// line (degree 4), not the lead lick (degree 0). Task 4's push_two_channels
// gives lead=degree 0/octave 3 and bass=degree 4/octave 2; setup_two_lick_tracks
// makes track 3 BASS-source FILL and track 4 LEAD-source FILL.
static bool test_lick_builder_seeds_from_bass_channel() {
    printf("\n=== Test: LickBuilder seeds live lick from bassist's channel ===\n");

    struct SeedResult {
        bool reached = false;       // did we observe section 1 entry within budget?
        bool active = false;        // live_lick_active
        bool bass_channel = false;  // live_lick_bass_channel
        int degree0 = -99;          // live_lick_degrees[0]
    };
    auto run_case = [](int lead_track) -> SeedResult {
        SeedResult r;
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        GraphUnit unit;
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;
        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_cosmic_techno(engine);
        engine->pulsar_seed.store(7777, std::memory_order_relaxed);
        push_two_channels(engine);
        setup_two_lick_tracks(engine);
        push_bass_channel_lickbuilder_arrangement(engine, lead_track);
        // Pin the intro so section 0 (1-bar NONE) is always the deterministic
        // start; the 2-section helper otherwise picks the start randomly, which
        // would occasionally skip straight to section 1 and delay entry by a
        // full 32-bar self-loop before start_band_solo ever runs.
        engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
        trigger_vibe_load(engine);
        engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

        for (int i = 0; i < 800 && !r.reached; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            PulsarState* ps = engine->pulsar_state;
            if (ps && ps->section_state.current_section == 1 && ps->band_solo_state.active) {
                r.reached = true;
                r.active = ps->live_lick_active;
                r.bass_channel = ps->live_lick_bass_channel;
                r.degree0 = ps->live_lick_length > 0 ? ps->live_lick_degrees[0] : -99;
            }
        }
        orpheus_engine_destroy(engine);
        return r;
    };

    // Case A: member 1 owns ONLY track 3 (the BASS-source FILL track) ->
    // live lick must mirror the bass line (degree 4), flag must be true.
    SeedResult bass_case = run_case(3);
    bool bass_ok = bass_case.reached && bass_case.active
                && bass_case.bass_channel
                && bass_case.degree0 == 4;
    printf("  bass-led (member 1 owns track 3): reached=%d active=%d bass_channel=%d degree0=%d -- %s\n",
           bass_case.reached, bass_case.active, bass_case.bass_channel, bass_case.degree0,
           bass_ok ? "OK" : "FAIL");

    // Case B: member 1 owns ONLY track 4 (the LEAD-source FILL track) ->
    // live lick must fall back to the lead lick (degree 0), flag must be false.
    SeedResult lead_case = run_case(4);
    bool lead_ok = lead_case.reached && lead_case.active
                && !lead_case.bass_channel
                && lead_case.degree0 == 0;
    printf("  lead-led (member 1 owns track 4): reached=%d active=%d bass_channel=%d degree0=%d -- %s\n",
           lead_case.reached, lead_case.active, lead_case.bass_channel, lead_case.degree0,
           lead_ok ? "OK" : "FAIL");

    bool ok = bass_ok && lead_ok;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_bass_line_tests() {
    printf("\n========== PULSAR BASS LINE TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_two_channel_render_distinct());
    tally(test_bass_only_vibe_renders());
    tally(test_lick_builder_seeds_from_bass_channel());
    printf("\nPulsar bass line tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
