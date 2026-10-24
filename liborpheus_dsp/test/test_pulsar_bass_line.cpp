// Two-channel lick rendering: a BASS-source track renders Vibe.bassLine while a
// LEAD-source track renders Vibe.lick, in the same vibe, with independent
// mutation/octave. Mirrors the marshalling suite's setup idiom.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include <algorithm>
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
    setup_fixture_baseline(engine);
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
    setup_fixture_baseline(engine);
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

    constexpr int kSectionStride = kSectionDataFields;
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
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    // Clear solo/ducking/markov to defaults
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
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
        setup_fixture_baseline(engine);
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

// ── Generative CALL_RESPONSE must answer from the track's OWN channel ──
//
// A track with lickMode NONE takes the "no lick — generative pattern" branch in
// load_vibe (and its déjà-vu twin in mutate_patterns). CALL_RESPONSE is still a
// lick-driven strategy there: apply_bar_strategy renders a lick for bar 2 whenever
// lick_length > 0, falling back to a plain repeat otherwise. Both call sites used to
// hardcode state->lick / state->lick_length / lick_mutation / lick_octave, so a
// lickSource = BASS track in that branch answered with the LEAD riff, at the lead's
// mutation and the lead's octave. The sibling lick branch next to it already resolved
// the per-track LickChannel correctly, which is what made this easy to miss.
//
// Pinned by flipping ONLY lick_source and demanding the render moves. The REPEAT
// control is what makes it precise: REPEAT renders no lick at all, so there the
// channel must make no difference whatsoever. Without that control, "the two runs
// differ" could be satisfied by any other channel-dependent path.
struct GenStepsResult {
    int step_count = 0;
    uint8_t note[kMaxPulsarSteps] = {};
    bool gate[kMaxPulsarSteps] = {};
    int first_note = -1;
};

static GenStepsResult run_generative_case(int bar_strategy, int lick_source) {
    GenStepsResult r;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);
    push_two_channels(engine);   // lead = degree 0 / octave 3, bass = degree 4 / octave 2
    // 32 steps: apply_bar_strategy only runs when step_count > 16, and CALL_RESPONSE
    // needs both bars to lay out its call and its answer.
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    // Track 4 is melodic with NO lick mode, which is exactly what routes it into the
    // generative branch rather than the lick branch.
    engine->pulsar_track_role[4].store(1, std::memory_order_relaxed);          // MELODIC
    engine->pulsar_track_lick_mode[4].store(0, std::memory_order_relaxed);     // NONE
    engine->pulsar_track_bar_strategy[4].store(bar_strategy, std::memory_order_relaxed);
    engine->pulsar_track_lick_source[4].store(lick_source, std::memory_order_relaxed);
    engine->pulsar_track_chord_follow[4].store(2, std::memory_order_relaxed);  // FIXED
    engine->pulsar_track_lick_degree_offset[4].store(0, std::memory_order_relaxed);
    // Force the generative pattern to actually fire notes. The baseline fixture's own track-4
    // density leaves this branch empty across all 32 steps, which would make the REPEAT
    // control below compare two silences and pass without proving anything. CALL_RESPONSE
    // is unaffected either way: bar_strategy_call_response clears every step and rewrites
    // both halves from the lick, so it never reads the generated pattern.
    engine->pulsar_track_density_override[4].store(0.9f, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    if (ps) {
        r.step_count = ps->tracks[4].step_count;
        if (r.step_count > kMaxPulsarSteps) r.step_count = kMaxPulsarSteps;
        for (int i = 0; i < r.step_count; i++) {
            r.note[i] = ps->tracks[4].steps[i].note;
            r.gate[i] = ps->tracks[4].steps[i].gate;
            if (r.first_note < 0 && r.gate[i]) r.first_note = r.note[i];
        }
    }
    orpheus_engine_destroy(engine);
    return r;
}

static bool steps_identical(const GenStepsResult& a, const GenStepsResult& b) {
    if (a.step_count != b.step_count || a.step_count == 0) return false;
    for (int i = 0; i < a.step_count; i++) {
        if (a.gate[i] != b.gate[i]) return false;
        if (a.gate[i] && a.note[i] != b.note[i]) return false;
    }
    return true;
}

static bool test_generative_call_response_uses_track_channel() {
    printf("\n=== Test: generative CALL_RESPONSE answers from the track's own channel ===\n");

    GenStepsResult cr_lead = run_generative_case(3, 0);  // CALL_RESPONSE + LEAD
    GenStepsResult cr_bass = run_generative_case(3, 1);  // CALL_RESPONSE + BASS
    GenStepsResult rp_lead = run_generative_case(0, 0);  // REPEAT + LEAD  (control)
    GenStepsResult rp_bass = run_generative_case(0, 1);  // REPEAT + BASS  (control)

    // Non-vacuity: the branch has to have actually rendered something on both runs,
    // or "they differ" would be comparing two silences.
    bool rendered = cr_lead.first_note >= 0 && cr_bass.first_note >= 0;
    // The bug, directly: before the fix both runs rendered the LEAD riff and were
    // byte-identical.
    bool cr_differs = !steps_identical(cr_lead, cr_bass);
    // Direction: the bass channel is authored an octave lower (octave 2 vs 3), and the
    // octave dominates the degree difference, so the bass answer must sit below the lead's.
    bool bass_lower = rendered && cr_bass.first_note < cr_lead.first_note;
    // Control: REPEAT renders no lick, so the channel is irrelevant and the two runs
    // must stay identical. This is what proves cr_differs came from the lick render.
    // It only means that if REPEAT actually produced notes — two empty patterns are
    // identical for free, which would make the control worthless.
    bool repeat_rendered = rp_lead.first_note >= 0 && rp_bass.first_note >= 0;
    bool repeat_same = repeat_rendered && steps_identical(rp_lead, rp_bass);

    printf("  CALL_RESPONSE: lead first note=%d, bass first note=%d, steps=%d/%d\n",
           cr_lead.first_note, cr_bass.first_note, cr_lead.step_count, cr_bass.step_count);
    printf("  rendered=%s channel_changes_render=%s bass_lower=%s\n",
           rendered ? "OK" : "FAIL", cr_differs ? "OK" : "FAIL", bass_lower ? "OK" : "FAIL");
    printf("  REPEAT control: lead first note=%d, bass first note=%d, rendered=%s identical=%s\n",
           rp_lead.first_note, rp_bass.first_note,
           repeat_rendered ? "OK" : "FAIL", repeat_same ? "OK" : "FAIL");
    if (!repeat_rendered)
        printf("  FAIL: REPEAT control produced no notes, so 'identical' is vacuous\n");
    if (!cr_differs)
        printf("  FAIL: flipping lick_source changed nothing — the generative branch is "
               "still rendering state->lick regardless of the track's channel\n");
    if (!repeat_same)
        printf("  FAIL: REPEAT is channel-sensitive, so the CALL_RESPONSE difference above "
               "is not attributable to the lick render\n");

    bool ok = rendered && cr_differs && bass_lower && repeat_same;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── Section.bassLineIndex: a section pins a lick-pool slot for the bass channel ──
//
// Pool slot s is a constant-degree line (kPinPoolDegree[s]). Section 0 lasts one bar and
// pins the lead to slot 0 (slot 26) and, optionally, the bass channel to slot 2 (slot 17);
// section 1 pins the lead to slot 1 and the bass to nothing, and self-loops for 32 bars.
static constexpr int kBassPinSlot = 2;
static constexpr float kPinPoolDegree[3] = {1.0f, 3.0f, 6.0f};

static void push_bass_pin_pool(OrpheusEngine* engine) {
    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    for (int s = 0; s < 3; s++) {
        for (int i = 0; i < 4; i++) {
            int b = s * (S * F) + i * F;
            engine->pulsar_lick_pool_data[b + 0] = kPinPoolDegree[s];
            engine->pulsar_lick_pool_data[b + 1] = 1.0f;   // quarter notes, as push_two_channels
            engine->pulsar_lick_pool_data[b + 2] = 0.8f;
            engine->pulsar_lick_pool_data[b + 3] = -1.0f;
            engine->pulsar_lick_pool_hit_prob[s * S + i] = 1.0f;
        }
        engine->pulsar_lick_pool_len[s] = 4;
        engine->pulsar_lick_pool_loop[s] = 4;
    }
    engine->pulsar_lick_anomaly_index = -1;
    engine->pulsar_lick_anomaly_chance = 0.0f;
    engine->pulsar_lick_pool_count.store(3, std::memory_order_release);
}

// s0_bass_pin = -1 writes slot 17 as 0 (no pin).
static void push_bass_pin_arrangement(OrpheusEngine* engine, int s0_bass_pin) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = kSectionDataFields;
    float section_data[2 * kSectionStride] = {};
    for (int s = 0; s < 2; s++) {
        float* d = section_data + s * kSectionStride;
        d[2] = 1; d[3] = 0.8f; d[4] = 1;               // bar_step, recency_decay, one edge
        d[5] = -1; d[6] = -1; d[7] = -1; d[8] = -1;    // no macro overrides
        d[18] = -1; d[19] = -1; d[20] = -1;            // no comping overrides
    }
    section_data[0] = 1;  section_data[1] = 1;         // section 0: one bar
    section_data[26] = 1;                              // lead pinned to slot 0
    section_data[17] = static_cast<float>(s0_bass_pin + 1);
    section_data[kSectionStride + 0] = 32;  section_data[kSectionStride + 1] = 32;
    section_data[kSectionStride + 26] = 2;             // lead pinned to slot 1
    for (int i = 0; i < 2 * kSectionStride; i++) {
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);
    }

    // s0 -> s1 and s1 -> s1, both hard cuts.
    float trans[8 * 8 * 3] = {};
    trans[0] = 1;  trans[1] = 1.0f;
    trans[24] = 1; trans[25] = 1.0f;
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

static GenStepsResult snap_track(PulsarState* ps, int t) {
    GenStepsResult r;
    r.step_count = std::min(ps->tracks[t].step_count, kMaxPulsarSteps);
    for (int i = 0; i < r.step_count; i++) {
        r.note[i] = ps->tracks[t].steps[i].note;
        r.gate[i] = ps->tracks[t].steps[i].gate;
        if (r.first_note < 0 && r.gate[i]) r.first_note = r.note[i];
    }
    return r;
}

struct BassPinRun {
    bool reached_s1 = false;
    GenStepsResult s0_bass, s0_lead, s1_bass, s1_lead;
    int s0_lick = -1, s1_lick = -1;
    int s0_lead_degree = -99, s1_lead_degree = -99;
};

// bass_degree is the authored bassLine's constant degree; s0_bass_pin = -1 pins nothing.
static BassPinRun run_bass_pin_case(float bass_degree, int s0_bass_pin) {
    BassPinRun r;
    stmlib::Random::Seed(0xBA55u);
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);
    push_two_channels(engine);
    for (int i = 0; i < 4; i++) {
        char sym[36];
        snprintf(sym, sizeof(sym), "bass_line_data_%d", i * OrpheusEngine::kLickFieldsPerStep);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, bass_degree);
    }
    setup_two_lick_tracks(engine);
    // REPEAT on both lick tracks: with mutation 0 the render then ignores its seed.
    engine->pulsar_track_bar_strategy[3].store(0, std::memory_order_relaxed);
    engine->pulsar_track_bar_strategy[4].store(0, std::memory_order_relaxed);
    push_bass_pin_pool(engine);
    push_bass_pin_arrangement(engine, s0_bass_pin);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    if (ps && ps->section_state.current_section == 0) {
        r.s0_bass = snap_track(ps, 3);
        r.s0_lead = snap_track(ps, 4);
        r.s0_lick = ps->current_lick_index;
        r.s0_lead_degree = ps->lick[0].scale_degree;
        for (int i = 0; i < 400 && !r.reached_s1; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            if (ps->section_state.current_section == 1) {
                r.reached_s1 = true;
                r.s1_bass = snap_track(ps, 3);
                r.s1_lead = snap_track(ps, 4);
                r.s1_lick = ps->current_lick_index;
                r.s1_lead_degree = ps->lick[0].scale_degree;
            }
        }
    }
    orpheus_engine_destroy(engine);
    return r;
}

static bool test_bass_line_index_sings_the_pool_slot() {
    printf("\n=== Test: a section's bass_line_index renders that pool slot on the BASS track ===\n");
    BassPinRun pinned = run_bass_pin_case(0.0f, kBassPinSlot);
    // Reference: the same vibe whose authored bassLine IS slot 2's line, nothing pinned.
    BassPinRun reference = run_bass_pin_case(kPinPoolDegree[kBassPinSlot], -1);
    BassPinRun authored = run_bass_pin_case(0.0f, -1);
    bool rendered = pinned.s0_bass.first_note >= 0 && reference.s0_bass.first_note >= 0;
    bool sings_slot = steps_identical(pinned.s0_bass, reference.s0_bass);
    bool moved = !steps_identical(pinned.s0_bass, authored.s0_bass);
    printf("  section 0 bass first note: pinned=%d slot-2 reference=%d authored=%d\n",
           pinned.s0_bass.first_note, reference.s0_bass.first_note, authored.s0_bass.first_note);
    printf("  rendered=%s sings_slot_2=%s differs_from_authored=%s\n",
           rendered ? "OK" : "FAIL", sings_slot ? "OK" : "FAIL", moved ? "OK" : "FAIL");
    bool ok = rendered && sings_slot && moved;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_bass_line_index_restores_authored_line() {
    printf("\n=== Test: a section without bass_line_index restores the authored bassLine ===\n");
    BassPinRun pinned = run_bass_pin_case(0.0f, kBassPinSlot);
    BassPinRun authored = run_bass_pin_case(0.0f, -1);
    bool reached = pinned.reached_s1 && authored.reached_s1;
    bool rendered = pinned.s1_bass.first_note >= 0;
    // Non-vacuity: section 0 really sang the pin, so section 1 had something to undo.
    bool was_pinned = !steps_identical(pinned.s0_bass, authored.s0_bass);
    bool restored = reached && steps_identical(pinned.s1_bass, authored.s1_bass);
    printf("  section 1 bass first note: pinned run=%d authored run=%d (section 0 pinned=%d)\n",
           pinned.s1_bass.first_note, authored.s1_bass.first_note, pinned.s0_bass.first_note);
    printf("  reached=%s rendered=%s section_0_pinned=%s restored=%s\n",
           reached ? "OK" : "FAIL", rendered ? "OK" : "FAIL", was_pinned ? "OK" : "FAIL",
           restored ? "OK" : "FAIL");
    bool ok = reached && rendered && was_pinned && restored;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_bass_line_index_leaves_lead_alone() {
    printf("\n=== Test: bass_line_index never moves the LEAD track off its lick_index ===\n");
    BassPinRun pinned = run_bass_pin_case(0.0f, kBassPinSlot);
    BassPinRun authored = run_bass_pin_case(0.0f, -1);
    bool reached = pinned.reached_s1 && authored.reached_s1;
    bool slots = pinned.s0_lick == 0 && pinned.s1_lick == 1
              && pinned.s0_lead_degree == static_cast<int>(kPinPoolDegree[0])
              && pinned.s1_lead_degree == static_cast<int>(kPinPoolDegree[1]);
    bool rendered = pinned.s0_lead.first_note >= 0 && pinned.s1_lead.first_note >= 0;
    // Non-vacuity: the bass pin did take effect in section 0 of this same run.
    bool bass_pinned = !steps_identical(pinned.s0_bass, authored.s0_bass);
    bool same_lead = reached && steps_identical(pinned.s0_lead, authored.s0_lead)
                  && steps_identical(pinned.s1_lead, authored.s1_lead);
    printf("  lead slot s0=%d s1=%d, lead degree s0=%d s1=%d\n",
           pinned.s0_lick, pinned.s1_lick, pinned.s0_lead_degree, pinned.s1_lead_degree);
    printf("  reached=%s slots=%s rendered=%s bass_pinned=%s lead_unchanged_by_bass_pin=%s\n",
           reached ? "OK" : "FAIL", slots ? "OK" : "FAIL", rendered ? "OK" : "FAIL",
           bass_pinned ? "OK" : "FAIL", same_lead ? "OK" : "FAIL");
    bool ok = reached && slots && rendered && bass_pinned && same_lead;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

struct BassSeamRun {
    bool reached_s1 = false;
    int slot_after = -2;
    GenStepsResult before, after;   // track 3 on the last block of section 0, the first of section 1
};

// The bass channel at mutation 0.8, so a re-render on a fresh seed moves the steps. No lead
// pins and no pool, so nothing else re-renders a lick track at the seam. s1_pins_authored
// gives section 1 a one-slot pool holding the authored bass line and pins it there.
static BassSeamRun run_bass_seam_case(bool s1_pins_authored) {
    BassSeamRun r;
    stmlib::Random::Seed(0xBA55u);
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);
    push_two_channels(engine);
    orpheus_engine_set_port(engine, PULSAR_URI, "bass_line_mutation", 0.8f);
    engine->pulsar_complexity.store(0.6f, std::memory_order_relaxed);  // past the steady zone
    setup_two_lick_tracks(engine);
    engine->pulsar_track_bar_strategy[3].store(0, std::memory_order_relaxed);
    engine->pulsar_track_bar_strategy[4].store(0, std::memory_order_relaxed);
    if (s1_pins_authored) {
        const int F = OrpheusEngine::kLickFieldsPerStep;
        for (int i = 0; i < 4; i++) {
            engine->pulsar_lick_pool_data[i * F + 0] = 4.0f;   // push_two_channels' bass line
            engine->pulsar_lick_pool_data[i * F + 1] = 1.0f;
            engine->pulsar_lick_pool_data[i * F + 2] = 0.8f;
            engine->pulsar_lick_pool_data[i * F + 3] = -1.0f;
            engine->pulsar_lick_pool_hit_prob[i] = 1.0f;
        }
        engine->pulsar_lick_pool_len[0] = 4;
        engine->pulsar_lick_pool_loop[0] = 4;
        engine->pulsar_lick_anomaly_index = -1;
        engine->pulsar_lick_anomaly_chance = 0.0f;
        engine->pulsar_lick_pool_count.store(1, std::memory_order_release);
    }
    push_bass_pin_arrangement(engine, -1);
    engine->pulsar_section_data[26].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_section_data[kSectionDataFields + 26].store(0.0f, std::memory_order_relaxed);
    if (s1_pins_authored)
        engine->pulsar_section_data[kSectionDataFields + 17].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    if (ps && ps->section_state.current_section == 0) {
        // A re-render seeds from seed_counter, which only a load sets, so it would repeat the
        // load's render exactly. A new value makes any seam re-render show in the steps.
        ps->seed_counter += 1;
        for (int i = 0; i < 400 && !r.reached_s1; i++) {
            r.before = snap_track(ps, 3);
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            if (ps->section_state.current_section == 1) {
                r.reached_s1 = true;
                r.after = snap_track(ps, 3);
                r.slot_after = ps->bass_line_slot;
            }
        }
    }
    orpheus_engine_destroy(engine);
    return r;
}

// A vibe with an arrangement and no bass pin does no bass-channel work at a section seam:
// the BASS track keeps the exact steps it had. The control re-renders the same line at the
// seam (a pin to a slot holding it), proving the comparison sees a seam re-render.
static bool test_bass_line_index_absent_is_inert() {
    printf("\n=== Test: no bass_line_index leaves the BASS track untouched at a seam ===\n");
    BassSeamRun unpinned = run_bass_seam_case(false);
    BassSeamRun control = run_bass_seam_case(true);
    bool reached = unpinned.reached_s1 && control.reached_s1;
    bool rendered = unpinned.before.first_note >= 0 && control.before.first_note >= 0;
    bool slot_kept = unpinned.slot_after == -1;
    bool untouched = steps_identical(unpinned.before, unpinned.after);
    bool control_moved = control.slot_after == 0 && !steps_identical(control.before, control.after);
    printf("  bass slot after seam: unpinned=%d control=%d\n", unpinned.slot_after, control.slot_after);
    printf("  reached=%s rendered=%s slot_kept=%s untouched=%s control_rerender_moves=%s\n",
           reached ? "OK" : "FAIL", rendered ? "OK" : "FAIL", slot_kept ? "OK" : "FAIL",
           untouched ? "OK" : "FAIL", control_moved ? "OK" : "FAIL");
    bool ok = reached && rendered && slot_kept && untouched && control_moved;
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
    tally(test_generative_call_response_uses_track_channel());
    tally(test_bass_line_index_sings_the_pool_slot());
    tally(test_bass_line_index_restores_authored_line());
    tally(test_bass_line_index_leaves_lead_alone());
    tally(test_bass_line_index_absent_is_inert());
    printf("\nPulsar bass line tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
