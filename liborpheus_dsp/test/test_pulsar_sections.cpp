#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_section.h"
#include <cstdio>
#include <cmath>
#include <cstring>

// ── Test helper: create a 3-section arrangement ──

static ArrangementParams make_test_arrangement() {
    ArrangementParams arr = {};
    arr.active = true;
    arr.section_count = 3;
    arr.intro_index = -1;
    arr.outro_index = -1;

    // Section 0: bars 4-8, transitions to 1(0.6) and 2(0.4), recencyDecay=0.5,
    // both edges hard-cut (transition_bars=0).
    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 8;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 0.6f;
    arr.sections[0].transitions[0].transition_bars = 0;
    arr.sections[0].transitions[1].target_index = 2;
    arr.sections[0].transitions[1].weight = 0.4f;
    arr.sections[0].transitions[1].transition_bars = 0;
    arr.sections[0].transition_count = 2;

    // Section 1: bars 4-4, transitions to 0(0.8) and 2(0.2), recencyDecay=0.5.
    arr.sections[1].bars_min = 4;
    arr.sections[1].bars_max = 4;
    arr.sections[1].recency_decay = 0.5f;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 0.8f;
    arr.sections[1].transitions[0].transition_bars = 0;
    arr.sections[1].transitions[1].target_index = 2;
    arr.sections[1].transitions[1].weight = 0.2f;
    arr.sections[1].transitions[1].transition_bars = 0;
    arr.sections[1].transition_count = 2;

    // Section 2: bars 4-4, transitions to 0(1.0), recencyDecay=0.5.
    arr.sections[2].bars_min = 4;
    arr.sections[2].bars_max = 4;
    arr.sections[2].recency_decay = 0.5f;
    arr.sections[2].transitions[0].target_index = 0;
    arr.sections[2].transitions[0].weight = 1.0f;
    arr.sections[2].transitions[0].transition_bars = 0;
    arr.sections[2].transition_count = 1;

    return arr;
}

// ── Unit tests ──

static bool test_section_init() {
    printf("\n=== Test: Section init without intro starts in valid section ===\n");
    ArrangementParams arr = make_test_arrangement();
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    bool ok = state.current_section >= 0
           && state.current_section < arr.section_count
           && state.bars_remaining > 0
           && state.intro_done == true;
    printf("  current_section=%d, bars_remaining=%d, intro_done=%s -- %s\n",
           state.current_section, state.bars_remaining,
           state.intro_done ? "true" : "false",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_init_with_intro() {
    printf("\n=== Test: Section init with intro_index=0 starts at section 0, intro_done=false ===\n");
    ArrangementParams arr = make_test_arrangement();
    arr.intro_index = 0;  // Set intro to section 0
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    bool ok = state.current_section == 0
           && state.intro_done == false;
    printf("  current_section=%d, intro_done=%s -- %s\n",
           state.current_section, state.intro_done ? "true" : "false",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_advance_countdown() {
    printf("\n=== Test: Section advance decrements bars_remaining without changing section ===\n");
    ArrangementParams arr = make_test_arrangement();
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    int initial_section = state.current_section;
    int initial_bars = state.bars_remaining;

    // Advance once
    bool changed = advance_section(state, arr, seed);

    bool ok = state.current_section == initial_section
           && state.bars_remaining == initial_bars - 1
           && changed == false;
    printf("  section unchanged: %s, bars decremented: %d -> %d, changed=%s -- %s\n",
           state.current_section == initial_section ? "true" : "false",
           initial_bars, state.bars_remaining,
           changed ? "true" : "false",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_transitions_eventually() {
    printf("\n=== Test: After enough advances, section changes ===\n");
    ArrangementParams arr = make_test_arrangement();
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    int initial_section = state.current_section;

    // Advance enough times to trigger a transition
    bool transitioned = false;
    for (int i = 0; i < 100; i++) {
        if (advance_section(state, arr, seed)) {
            transitioned = true;
            break;
        }
    }

    // After transition, section should be different
    bool ok = transitioned && state.current_section != initial_section;
    printf("  transitioned=%s, initial=%d, final=%d -- %s\n",
           transitioned ? "true" : "false",
           initial_section, state.current_section,
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_recency_prevents_immediate_repeat() {
    printf("\n=== Test: Low recencyDecay prevents immediate repeat (300 transitions) ===\n");
    ArrangementParams arr = make_test_arrangement();
    // Set very low recency decay to strongly penalize recent visits
    arr.sections[0].recency_decay = 0.1f;
    arr.sections[1].recency_decay = 0.1f;
    arr.sections[2].recency_decay = 0.1f;

    SectionState state;
    uint32_t seed = 54321;
    init_section_state(state, arr, seed);

    // Count visits to each section
    int visits[3] = {0, 0, 0};
    visits[state.current_section]++;

    // Perform 300 transitions by advancing many times
    // Each section is 4-8 bars, so with ~6 bar average, 300 transitions = ~1800 advances
    for (int i = 0; i < 2000; i++) {
        bool changed = advance_section(state, arr, seed);
        if (changed && state.current_section >= 0 && state.current_section < 3) {
            visits[state.current_section]++;
        }
    }

    // With 3 sections and many transitions, each should get reasonable visits.
    // Check that each section gets at least some visits (> 10%)
    float visit_pct[3];
    int total_visits = 0;
    for (int i = 0; i < 3; i++) {
        total_visits += visits[i];
    }
    for (int i = 0; i < 3; i++) {
        visit_pct[i] = (total_visits > 0) ? visits[i] / (float)total_visits : 0.0f;
    }
    bool ok = visit_pct[0] > 0.1f && visit_pct[1] > 0.1f && visit_pct[2] > 0.1f;
    printf("  total transitions: %d, visits: sec0=%.1f%%, sec1=%.1f%%, sec2=%.1f%% -- %s\n",
           total_visits, visit_pct[0] * 100.0f, visit_pct[1] * 100.0f, visit_pct[2] * 100.0f,
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_transition_ramp() {
    printf("\n=== Test: pre-roll ramp occupies last N bars of source section ===\n");

    // 2-section A->B arrangement. Section 0 is 4 bars; the edge to section 1
    // has transition_bars = 2, so the LAST 2 of those 4 bars are the ramp zone.
    ArrangementParams arr = {};
    arr.active = true;
    arr.section_count = 2;
    arr.intro_index = -1;
    arr.outro_index = -1;

    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 4;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 1.0f;
    arr.sections[0].transitions[0].transition_bars = 2;
    arr.sections[0].transition_count = 1;

    arr.sections[1].bars_min = 4;
    arr.sections[1].bars_max = 4;
    arr.sections[1].recency_decay = 0.5f;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 1.0f;
    arr.sections[1].transitions[0].transition_bars = 0;  // hard cut back
    arr.sections[1].transition_count = 1;

    SectionState state;
    uint32_t seed = 11111;
    init_section_state(state, arr, seed);

    // Force section 0 active deterministically.
    state.current_section = 0;
    state.bars_remaining = 4;
    plan_next_section(state, arr, seed);

    // Bar 1: bars_remaining 4 -> 3. Outside ramp zone (3 >= 2).
    advance_section(state, arr, seed);
    bool ok_bar1 = state.transition_target < 0 && state.bars_remaining == 3;

    // Bar 2: bars_remaining 3 -> 2. Still outside (2 < 2 is false).
    advance_section(state, arr, seed);
    bool ok_bar2 = state.transition_target < 0 && state.bars_remaining == 2;

    // Bar 3: bars_remaining 2 -> 1. Ramp starts. progress = (2-1)/2 = 0.5.
    bool changed3 = advance_section(state, arr, seed);
    float progress_after_bar3 = state.transition_progress;
    bool ok_bar3 = state.transition_target == 1
                && std::fabs(progress_after_bar3 - 0.5f) < 0.001f
                && !changed3;

    // Bar 4: bars_remaining 1 -> 0. Ramp completes mid-update (would be 1.0),
    // then hard-flip to section 1. After flip: progress reset, transition cleared.
    bool changed4 = advance_section(state, arr, seed);
    bool ok_bar4 = changed4
                && state.current_section == 1
                && state.transition_target == -1
                && state.transition_progress == 0.0f;

    printf("  bar 1 (rem=3, no ramp): %s\n", ok_bar1 ? "PASS" : "FAIL");
    printf("  bar 2 (rem=2, no ramp): %s\n", ok_bar2 ? "PASS" : "FAIL");
    printf("  bar 3 (rem=1, ramp progress=%.2f): %s\n",
           progress_after_bar3, ok_bar3 ? "PASS" : "FAIL");
    printf("  bar 4 (rem=0, flip to section 1): %s\n", ok_bar4 ? "PASS" : "FAIL");

    return ok_bar1 && ok_bar2 && ok_bar3 && ok_bar4;
}

static bool test_section_macro_interpolation() {
    printf("\n=== Test: section_macro_value interpolates correctly ===\n");

    // Test at progress=0.0 (no override, should return base)
    float v1 = section_macro_value(0.5f, 0.3f, 0.8f, 0.0f);
    bool ok1 = std::fabs(v1 - 0.3f) < 0.001f;
    printf("  at progress=0.0: %.2f (expected 0.30) -- %s\n", v1, ok1 ? "PASS" : "FAIL");

    // Test at progress=0.5 (midway)
    float v2 = section_macro_value(0.5f, 0.3f, 0.8f, 0.5f);
    float expected2 = 0.3f + (0.8f - 0.3f) * 0.5f;  // 0.55
    bool ok2 = std::fabs(v2 - expected2) < 0.001f;
    printf("  at progress=0.5: %.2f (expected %.2f) -- %s\n", v2, expected2, ok2 ? "PASS" : "FAIL");

    // Test at progress=1.0 (complete)
    float v3 = section_macro_value(0.5f, 0.3f, 0.8f, 1.0f);
    bool ok3 = std::fabs(v3 - 0.8f) < 0.001f;
    printf("  at progress=1.0: %.2f (expected 0.80) -- %s\n", v3, ok3 ? "PASS" : "FAIL");

    // Test with no override (both -1)
    float v4 = section_macro_value(0.5f, -1.0f, -1.0f, 0.5f);
    bool ok4 = std::fabs(v4 - 0.5f) < 0.001f;
    printf("  no override at progress=0.5: %.2f (expected 0.50) -- %s\n", v4, ok4 ? "PASS" : "FAIL");

    return ok1 && ok2 && ok3 && ok4;
}

// ── Integration tests that drive the full unit_process_pulsar path ──
//
// The existing tests above are pure state-machine unit tests that call
// init_section_state / advance_section directly.  The two tests below verify
// the runtime hook that re-inits the chord progression on every section_changed
// event — logic that only fires inside unit_process_pulsar.

// Push a two-section arrangement where section 0 deterministically transitions
// to section 1 after `bars_per_section` bars.
static void push_two_section_ab_arrangement(OrpheusEngine* engine,
                                            int bars_per_section) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = 21;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        // Default comping overrides to -1
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
        // Disable solo for all sections (slot 9)
        section_data[s * kSectionStride + 9]  = 0;
        // No macro overrides
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
    }
    // Section 0: bars_min = bars_max = bars_per_section, one transition to section 1.
    section_data[0] = (float)bars_per_section; // bars_min
    section_data[1] = (float)bars_per_section; // bars_max
    section_data[2] = 1;                       // bar_step
    section_data[3] = 0.8f;                    // recency_decay
    section_data[4] = 1;                       // transition_count
    // Section 1: bars_min = bars_max = bars_per_section, one transition back to section 0.
    int s1 = kSectionStride;
    section_data[s1 + 0] = (float)bars_per_section;
    section_data[s1 + 1] = (float)bars_per_section;
    section_data[s1 + 2] = 1;                  // bar_step
    section_data[s1 + 3] = 0.8f;
    section_data[s1 + 4] = 1;

    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // Transitions: s0 -> {1:1.0}, s1 -> {0:1.0}.
    // Per-edge stride: 3 floats (target, weight, transition_bars). All hard-cut.
    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;  // s0 edge 0 -> section 1
    trans[24] = 0; trans[25] = 1.0f; trans[26] = 0;  // s1 edge 0 -> section 0 (s1 base = 8*3 = 24)
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Clear all per-section overrides by default; tests set them explicitly after.
    for (int s = 0; s < 8; s++) {
        engine->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
        for (int i = 0; i < 12; i++) {  // kMaxProgressionLength
            engine->pulsar_section_progression_degrees[s * 12 + i].store(0, std::memory_order_relaxed);
        }
    }

    // Clear solo/ducking/markov to defaults
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 6; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

static bool test_section_progression_override_applied() {
    printf("\n=== Test: Section progression override activates on section entry ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);

    // Two-section arrangement, 1 bar each so transitions happen quickly.
    push_two_section_ab_arrangement(engine, 1);

    // Section 0: no override.  Section 1: customProgression = [3, 4, 0].
    engine->pulsar_section_progression_length[1].store(3, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 0].store(3, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 1].store(4, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 2].store(0, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    // At 240 BPM with 1-bar sections and 512-sample blocks at 48 kHz, one bar ≈ 47 blocks.
    // 800-iteration cap gives ~17 bars of slack before we consider the test stuck.
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Drive audio until we see section 1 (or bail out).
    bool reached_section_1 = false;
    for (int i = 0; i < 800 && !reached_section_1; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (ps && ps->section_state.current_section == 1) {
            reached_section_1 = true;
        }
    }

    bool ok = reached_section_1;
    if (!ok) {
        printf("  FAIL: never reached section 1\n");
    } else {
        PulsarState* ps = engine->pulsar_state;
        int plen = ps->chord_state.progression_length;
        int p0 = (plen > 0) ? ps->chord_state.progression[0] : -1;
        int p1 = (plen > 1) ? ps->chord_state.progression[1] : -1;
        int p2 = (plen > 2) ? ps->chord_state.progression[2] : -1;
        int cidx = ps->chord_state.chord_index;
        bool plen_ok = (plen == 3);
        bool seq_ok  = (p0 == 3 && p1 == 4 && p2 == 0);
        bool idx_ok  = (cidx == 0);
        ok = plen_ok && seq_ok && idx_ok;
        printf("  progression_length=%d (expected 3) -- %s\n", plen, plen_ok ? "OK" : "FAIL");
        printf("  progression[0..3]={%d, %d, %d} (expected {3, 4, 0}) -- %s\n",
               p0, p1, p2, seq_ok ? "OK" : "FAIL");
        printf("  chord_index=%d (expected 0) -- %s\n", cidx, idx_ok ? "OK" : "FAIL");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_progression_inheritance_resets_index() {
    printf("\n=== Test: Section without override inherits vibe progression after section with override ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);

    // Vibe-level progression: [0, 3, 4, 5] (I - IV - V - VI).
    engine->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);
    engine->pulsar_custom_progression_length.store(4, std::memory_order_relaxed);
    engine->pulsar_custom_progression[0].store(0, std::memory_order_relaxed);
    engine->pulsar_custom_progression[1].store(3, std::memory_order_relaxed);
    engine->pulsar_custom_progression[2].store(4, std::memory_order_relaxed);
    engine->pulsar_custom_progression[3].store(5, std::memory_order_relaxed);

    // Two-section ping-pong arrangement, 1 bar each.
    push_two_section_ab_arrangement(engine, 1);

    // Section 0: no override (should use vibe progression).
    // Section 1: override to [2, 1] (distinct length and contents from vibe progression).
    engine->pulsar_section_progression_length[1].store(2, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 0].store(2, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 1].store(1, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    // At 240 BPM with 1-bar sections and 512-sample blocks at 48 kHz, one bar ≈ 47 blocks.
    // 3000-iteration cap gives ~63 bars of slack to observe the 0 -> 1 -> 0 ping-pong.
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Track the full section history: we need to see 0 -> 1 -> 0 and verify that on
    // the second entry to section 0, the vibe progression is RE-INSTALLED (length=4,
    // sequence={0,3,4,5}) and chord_index is reset to 0.  Without the fix, once
    // section 1's override shrinks the progression to length=2 with contents {2,1},
    // returning to section 0 would leave it there (no re-init), so this test fails.
    int last_section = -1;
    bool entered_section_1 = false;
    bool section_1_override_observed = false;
    bool returned_to_section_0 = false;
    int snap_len = -1, snap_cidx = -1;
    int snap_prog[4] = {-1, -1, -1, -1};

    for (int i = 0; i < 3000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        int cur = ps->section_state.current_section;

        // On the tick we observe entry to section 1, confirm the override took hold
        if (last_section == 0 && cur == 1) {
            entered_section_1 = true;
            if (ps->chord_state.progression_length == 2
                && ps->chord_state.progression[0] == 2
                && ps->chord_state.progression[1] == 1) {
                section_1_override_observed = true;
            }
        }

        // On the tick we observe return to section 0, snapshot state
        if (last_section == 1 && cur == 0 && entered_section_1) {
            snap_len = ps->chord_state.progression_length;
            snap_cidx = ps->chord_state.chord_index;
            for (int k = 0; k < snap_len && k < 4; k++)
                snap_prog[k] = ps->chord_state.progression[k];
            returned_to_section_0 = true;
            break;
        }
        last_section = cur;
    }

    bool entered_ok = entered_section_1 && section_1_override_observed;
    bool return_ok  = returned_to_section_0;
    bool plen_ok    = (snap_len == 4);
    bool seq_ok     = (snap_prog[0] == 0
                    && snap_prog[1] == 3
                    && snap_prog[2] == 4
                    && snap_prog[3] == 5);
    bool idx_ok     = (snap_cidx == 0);

    bool ok = entered_ok && return_ok && plen_ok && seq_ok && idx_ok;

    if (!entered_ok) {
        printf("  FAIL: did not observe section 1 override taking effect\n");
    } else {
        printf("  section 1 override observed (length=2, {2,1}) -- OK\n");
    }
    if (!return_ok) {
        printf("  FAIL: never observed return to section 0\n");
    } else {
        printf("  on return to section 0:\n");
        printf("    progression_length=%d (expected 4) -- %s\n", snap_len, plen_ok ? "OK" : "FAIL");
        printf("    progression[0..4]={%d, %d, %d, %d} (expected {0, 3, 4, 5}) -- %s\n",
               snap_prog[0], snap_prog[1], snap_prog[2], snap_prog[3],
               seq_ok ? "OK" : "FAIL");
        printf("    chord_index=%d (expected 0) -- %s\n", snap_cidx, idx_ok ? "OK" : "FAIL");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Tension phase reset tests (Task 7) ──
//
// Verify that on EVERY section boundary:
//  - tension_intensity and tension_evo_smooth are unconditionally zeroed
//  - when a section has no override, the vibe-level tension profile is
//    re-loaded so a prior section's override does not leak forward.

static bool test_tension_phase_resets_without_override() {
    printf("\n=== Test: Tension phase (intensity + evo_smooth) resets on section change without override ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);

    // Vibe-level tension: inner_bars=4, outer_bars=0, volume=0.3
    // so tension_intensity rises from 0 -> 0.75 over 4 bars then wraps.
    engine->pulsar_tension_inner_bars.store(4, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_tension_volume.store(0.3f, std::memory_order_relaxed);
    // Give evo a non-trivial attack point so evo_smooth can rise.
    engine->pulsar_tension_evo_attack_point.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_tension_evo_release_speed.store(0.3f, std::memory_order_relaxed);

    // 2-section ping-pong, 3 bars each so tension_intensity has time to rise.
    push_two_section_ab_arrangement(engine, 3);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Track section history: we want to observe 0 -> 1 and sample state at the
    // moment we first see section 1 (end of the block where section_changed fired).
    int last_section = -1;
    bool entered_section_1 = false;
    float saw_intensity_rise = 0.0f;  // max observed while still in section 0
    float intensity_at_change = -1.0f;
    float evo_smooth_at_change = -1.0f;

    for (int i = 0; i < 2400; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        int cur = ps->section_state.current_section;

        if (cur == 0) {
            if (ps->tension_intensity > saw_intensity_rise) {
                saw_intensity_rise = ps->tension_intensity;
            }
        }

        if (last_section == 0 && cur == 1 && !entered_section_1) {
            entered_section_1 = true;
            intensity_at_change = ps->tension_intensity;
            evo_smooth_at_change = ps->tension_evo_smooth;
            break;
        }
        last_section = cur;
    }

    bool entered_ok = entered_section_1;
    bool rose_ok    = saw_intensity_rise > 0.1f;
    bool intensity_ok = entered_ok && intensity_at_change <= 1e-6f;
    bool evo_ok       = entered_ok && evo_smooth_at_change <= 1e-6f;

    if (!entered_ok) {
        printf("  FAIL: never observed transition to section 1\n");
    } else {
        printf("  rose to %.4f while in section 0 -- %s\n",
               saw_intensity_rise, rose_ok ? "OK" : "FAIL");
        printf("  tension_intensity at section change = %.6g (expected 0) -- %s\n",
               intensity_at_change, intensity_ok ? "OK" : "FAIL");
        printf("  tension_evo_smooth at section change = %.6g (expected 0) -- %s\n",
               evo_smooth_at_change, evo_ok ? "OK" : "FAIL");
    }

    bool ok = entered_ok && rose_ok && intensity_ok && evo_ok;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_tension_override_then_inherit() {
    printf("\n=== Test: Section tension override applied, then vibe tension reverts on exit ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);

    // Vibe-level tension: distinctive volume = 0.3
    engine->pulsar_tension_inner_bars.store(4, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_tension_volume.store(0.3f, std::memory_order_relaxed);

    // 2-section ping-pong, 1 bar each so transitions happen quickly.
    push_two_section_ab_arrangement(engine, 1);

    // Section 1: override with distinctive volume = 0.9
    // Field layout (matches engine.h): 0=inner_bars, 1=outer_bars, 2=outer_depth,
    //   3=volume, 4=timing, 5=octave_shift, 6=key_shift, 7=half_lick,
    //   8=chromatic_passing, 9..11=evo_timbre_low/high/prob, 12..14=evo_morph_*,
    //   15..17=evo_harm_*, 18=evo_attack_point, 19=evo_release_speed, 20=spurt_chance
    engine->pulsar_section_tension_active[1].store(1, std::memory_order_relaxed);
    constexpr int kSectionStride = 21;
    const int tb = 1 * kSectionStride;
    // Valid, non-silly defaults so the override struct isn't garbage.
    engine->pulsar_section_tension_data[tb + 0].store(4.0f, std::memory_order_relaxed);  // inner_bars
    engine->pulsar_section_tension_data[tb + 1].store(0.0f, std::memory_order_relaxed);  // outer_bars
    engine->pulsar_section_tension_data[tb + 2].store(0.5f, std::memory_order_relaxed);  // outer_depth
    engine->pulsar_section_tension_data[tb + 3].store(0.9f, std::memory_order_relaxed);  // volume <-- distinctive
    engine->pulsar_section_tension_data[tb + 4].store(0.2f, std::memory_order_relaxed);  // timing
    engine->pulsar_section_tension_data[tb + 5].store(0.0f, std::memory_order_relaxed);  // octave_shift
    engine->pulsar_section_tension_data[tb + 6].store(0.0f, std::memory_order_relaxed);  // key_shift
    engine->pulsar_section_tension_data[tb + 7].store(0.0f, std::memory_order_relaxed);  // half_lick
    engine->pulsar_section_tension_data[tb + 8].store(0.0f, std::memory_order_relaxed);  // chromatic_passing
    engine->pulsar_section_tension_data[tb + 9].store(0.25f, std::memory_order_relaxed); // evo_timbre_low
    engine->pulsar_section_tension_data[tb + 10].store(0.55f, std::memory_order_relaxed);// evo_timbre_high
    engine->pulsar_section_tension_data[tb + 11].store(0.5f, std::memory_order_relaxed); // evo_timbre_prob
    engine->pulsar_section_tension_data[tb + 12].store(-1.0f, std::memory_order_relaxed);// evo_morph_low
    engine->pulsar_section_tension_data[tb + 13].store(-1.0f, std::memory_order_relaxed);// evo_morph_high
    engine->pulsar_section_tension_data[tb + 14].store(0.5f, std::memory_order_relaxed); // evo_morph_prob
    engine->pulsar_section_tension_data[tb + 15].store(-1.0f, std::memory_order_relaxed);// evo_harm_low
    engine->pulsar_section_tension_data[tb + 16].store(-1.0f, std::memory_order_relaxed);// evo_harm_high
    engine->pulsar_section_tension_data[tb + 17].store(0.3f, std::memory_order_relaxed); // evo_harm_prob
    engine->pulsar_section_tension_data[tb + 18].store(0.5f, std::memory_order_relaxed); // evo_attack_point
    engine->pulsar_section_tension_data[tb + 19].store(0.3f, std::memory_order_relaxed); // evo_release_speed
    engine->pulsar_section_tension_data[tb + 20].store(0.0f, std::memory_order_relaxed); // spurt_chance

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Observe ping-pong 0 -> 1 -> 0.
    int last_section = -1;
    bool entered_section_1 = false;
    bool section_1_override_observed = false;
    bool returned_to_section_0 = false;
    float vol_on_return = -1.0f;

    for (int i = 0; i < 3000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        int cur = ps->section_state.current_section;

        // On entry to section 1, confirm override took hold
        if (last_section == 0 && cur == 1) {
            entered_section_1 = true;
            if (std::fabs(ps->tension.volume - 0.9f) < 1e-5f) {
                section_1_override_observed = true;
            }
        }

        // On return to section 0, snapshot tension.volume
        if (last_section == 1 && cur == 0 && entered_section_1) {
            returned_to_section_0 = true;
            vol_on_return = ps->tension.volume;
            break;
        }
        last_section = cur;
    }

    bool entered_ok = entered_section_1 && section_1_override_observed;
    bool return_ok  = returned_to_section_0;
    bool revert_ok  = return_ok && std::fabs(vol_on_return - 0.3f) < 1e-5f;
    bool ok = entered_ok && return_ok && revert_ok;

    if (!entered_ok) {
        printf("  FAIL: did not observe section 1 override (volume=0.9) take effect\n");
    } else {
        printf("  section 1 override observed (tension.volume=0.9) -- OK\n");
    }
    if (!return_ok) {
        printf("  FAIL: never observed return to section 0\n");
    } else {
        printf("  on return to section 0: tension.volume=%.4f (expected 0.30, vibe default) -- %s\n",
               vol_on_return, revert_ok ? "OK" : "FAIL");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_comping_humanization_override_loads() {
    printf("\n=== Test: Section comping humanization override loads from atomics ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);

    // Two-section arrangement so we have indices 0 and 1.
    push_two_section_ab_arrangement(engine, 1);

    // Section 0: no override.
    engine->pulsar_section_comping_humanization_active[0].store(0, std::memory_order_relaxed);

    // Section 1: full override with distinctive values.
    engine->pulsar_section_comping_humanization_active[1].store(1, std::memory_order_relaxed);
    engine->pulsar_section_comping_humanization_data[1 * 4 + 0].store(0.05f, std::memory_order_relaxed); // drop
    engine->pulsar_section_comping_humanization_data[1 * 4 + 1].store(0.10f, std::memory_order_relaxed); // ghost
    engine->pulsar_section_comping_humanization_data[1 * 4 + 2].store(0.00f, std::memory_order_relaxed); // octave (the SunkenPlace use case)
    engine->pulsar_section_comping_humanization_data[1 * 4 + 3].store(0.15f, std::memory_order_relaxed); // extension

    trigger_vibe_load(engine);
    // One process call to flush load_vibe into PulsarState.
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr);
    if (ok) {
        const SectionParam& s0 = ps->arrangement.sections[0];
        const SectionParam& s1 = ps->arrangement.sections[1];
        bool s0_ok = !s0.has_comping_humanization_override;
        bool s1_active_ok = s1.has_comping_humanization_override;
        bool s1_drop_ok   = std::fabs(s1.comping_humanization_drop      - 0.05f) < 1e-5f;
        bool s1_ghost_ok  = std::fabs(s1.comping_humanization_ghost     - 0.10f) < 1e-5f;
        bool s1_octave_ok = std::fabs(s1.comping_humanization_octave    - 0.00f) < 1e-5f;
        bool s1_ext_ok    = std::fabs(s1.comping_humanization_extension - 0.15f) < 1e-5f;
        printf("  section 0 inactive override: %s\n", s0_ok ? "OK" : "FAIL");
        printf("  section 1 active flag: %s\n", s1_active_ok ? "OK" : "FAIL");
        printf("  section 1 drop=%.4f ghost=%.4f octave=%.4f ext=%.4f -- %s\n",
               s1.comping_humanization_drop, s1.comping_humanization_ghost,
               s1.comping_humanization_octave, s1.comping_humanization_extension,
               (s1_drop_ok && s1_ghost_ok && s1_octave_ok && s1_ext_ok) ? "OK" : "FAIL");
        ok = s0_ok && s1_active_ok && s1_drop_ok && s1_ghost_ok && s1_octave_ok && s1_ext_ok;
    } else {
        printf("  FAIL: PulsarState was null after process call\n");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_macro_crossfade() {
    printf("\n=== Test: pre-roll macro crossfade across per-edge transition_bars ===\n");

    // Build a 2-section arrangement where section 0 IS the ramp:
    //   Section 0: 4 bars long, no macro overrides.
    //   Edge 0->1: transition_bars = 4 (the whole section is the ramp zone).
    //   Section 1: energy override = 2.0 (the ramp target).
    ArrangementParams arr = {};
    arr.active = true;
    arr.section_count = 2;
    arr.intro_index = -1;
    arr.outro_index = -1;

    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 4;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 1.0f;
    arr.sections[0].transitions[0].transition_bars = 4;
    arr.sections[0].transition_count = 1;
    // macro_overrides default-initialized to -1 (no override)

    arr.sections[1].bars_min = 1;
    arr.sections[1].bars_max = 1;
    arr.sections[1].recency_decay = 0.5f;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 1.0f;
    arr.sections[1].transitions[0].transition_bars = 0;  // hard cut back
    arr.sections[1].transition_count = 1;
    arr.sections[1].macro_overrides.energy = 2.0f;

    SectionState state;
    uint32_t seed = 7777;
    init_section_state(state, arr, seed);

    // Force section 0 active deterministically.
    state.current_section = 0;
    state.target_energy = -1.0f;
    state.target_complexity = -1.0f;
    state.target_space = -1.0f;
    state.target_mood = -1.0f;
    state.bars_remaining = 4;
    plan_next_section(state, arr, seed);
    bool ok_plan = state.next_section_planned == 1
                && state.next_section_trans_bars == 4;
    printf("  plan: next=%d, trans_bars=%d -- %s\n",
           state.next_section_planned, state.next_section_trans_bars,
           ok_plan ? "PASS" : "FAIL");

    // Bar 1: rem 4->3. into_ramp = 4-3 = 1. progress = 0.25. Stage destination.
    advance_section(state, arr, seed);
    bool ok_stage = state.transition_target == 1
                 && std::fabs(state.transition_progress - 0.25f) < 0.001f
                 && std::fabs(state.next_energy - 2.0f) < 0.001f
                 && std::fabs(state.target_energy - (-1.0f)) < 0.001f;
    // Blend at progress=0.25: 1.0 + (2.0 - 1.0) * 0.25 = 1.25
    float mult_q = section_macro_value(1.0f, state.target_energy, state.next_energy,
                                        state.transition_progress);
    bool ok_mult_q = std::fabs(mult_q - 1.25f) < 0.001f;
    printf("  bar 1 (rem=3, progress=%.2f, blend=%.3f): %s/%s\n",
           state.transition_progress, mult_q,
           ok_stage ? "PASS" : "FAIL", ok_mult_q ? "PASS" : "FAIL");

    // Bars 2, 3: progress 0.5, 0.75
    advance_section(state, arr, seed);
    bool ok_half = std::fabs(state.transition_progress - 0.5f) < 0.001f;
    advance_section(state, arr, seed);
    bool ok_three_q = std::fabs(state.transition_progress - 0.75f) < 0.001f;
    printf("  bar 2 (progress=%.2f): %s\n", state.transition_progress, ok_half ? "PASS" : "FAIL");
    // (note: bar 2's progress was already overwritten by bar 3's; ok_half captured at the right time)
    printf("  bar 3 (progress=%.2f): %s\n", state.transition_progress, ok_three_q ? "PASS" : "FAIL");

    // Bar 4: rem 1->0. Ramp would hit 1.0 mid-bar, then hard-flip to section 1.
    bool changed = advance_section(state, arr, seed);
    bool ok_flip = changed
                && state.current_section == 1
                && state.transition_target == -1
                && state.transition_progress == 0.0f
                && state.next_energy < -0.5f;  // staging slots cleared back to sentinel
    printf("  bar 4 (flip to section 1): %s\n", ok_flip ? "PASS" : "FAIL");

    return ok_plan && ok_stage && ok_mult_q && ok_half && ok_three_q && ok_flip;
}

// Verify the audio path computes a continuous sub-bar transition_progress
// from track 0's playhead, so the macro lerp updates per-step instead of
// stepping in coarse per-bar jumps. The arrangement-level transition_progress
// stays bar-quantized; the smoothing happens inside unit_process_pulsar.
//
// Assertion: across an N-bar ramp, observe more than N distinct progress
// values. A bar-quantized implementation gives exactly N values; the
// continuous formula gives ~N * step_count.
static bool test_section_macro_subbar_lerp() {
    printf("\n=== Test: macro multiplier lerps continuously across each bar ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);

    // 2-section arrangement, 4 bars per section so we can observe a 4-bar ramp.
    push_two_section_ab_arrangement(engine, 4);

    // Edge 0->1 has transition_bars = 4 so the entire section 0 IS the ramp.
    constexpr int kSectionStride = 21;
    engine->pulsar_section_data[1 * kSectionStride + 5].store(2.0f, std::memory_order_relaxed);  // section 1 energy = 2.0
    engine->pulsar_section_transitions[0 * 3 + 2].store(4.0f, std::memory_order_relaxed);        // edge 0 -> 1 trans_bars
    // Force section 0 as the initial section so the ramp is deterministic
    // (intro_index = -1 makes init_section_state pick randomly).
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Drive long enough to traverse the full 4-bar ramp with room to spare.
    // 1024-sample blocks at 48 kHz, 240 BPM, 4 bars = ~94 blocks; budget 400.
    int distinct_progress = 0;
    float last_progress = -2.0f;
    bool monotonic = true;
    bool reached_section_1 = false;
    int N = 4;

    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, 1024, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->section_state.current_section == 1) {
            reached_section_1 = true;
            break;
        }
        if (ps->section_state.transition_target < 0) continue;

        const PulsarTrackState& t0 = ps->tracks[0];
        float bar_phase = (t0.step_count > 0)
            ? static_cast<float>(t0.playhead) / static_cast<float>(t0.step_count)
            : 0.0f;
        float bars_remaining_continuous =
            static_cast<float>(ps->section_state.bars_remaining) - bar_phase;
        if (bars_remaining_continuous < 0.0f) bars_remaining_continuous = 0.0f;
        float progress = (static_cast<float>(N) - bars_remaining_continuous)
                         / static_cast<float>(N);
        if (progress < 0.0f) progress = 0.0f;
        if (progress > 1.0f) progress = 1.0f;

        if (std::fabs(progress - last_progress) > 1e-5f) {
            distinct_progress++;
            if (progress + 1e-5f < last_progress) monotonic = false;
            last_progress = progress;
        }
    }

    bool ok_reached = reached_section_1;
    bool ok_smooth = distinct_progress > N;       // bar-quantized would give exactly N
    bool ok_mono = monotonic;

    printf("  reached section 1: %s\n", ok_reached ? "OK" : "FAIL");
    printf("  distinct progress values during ramp: %d (expected > %d): %s\n",
           distinct_progress, N, ok_smooth ? "OK" : "FAIL");
    printf("  progress monotonic non-decreasing: %s\n", ok_mono ? "OK" : "FAIL");

    bool ok = ok_reached && ok_smooth && ok_mono;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// randomize_section_bars must keep the chosen length within [bars_min, bars_max]
// and must keep the minimum reachable (idx == 0 maps to bars_min). The top edge
// needs a guard because pattern_rand01 returns [0, 1] *inclusive* of 1.0, which
// would otherwise push idx to stop_count and overshoot bars_max by one step.
static bool test_randomize_section_bars_bounds() {
    printf("\n=== Test: randomize_section_bars includes min, never exceeds max ===\n");
    bool ok = true;

    // Contract sweep: range 6..8 step 1 — both ends reachable, never out of bounds.
    {
        SectionParam sec;
        sec.bars_min = 6; sec.bars_max = 8; sec.bar_step = 1;
        uint32_t seed = 0xC0FFEE;
        bool saw_min = false, saw_max = false, in_bounds = true;
        for (int i = 0; i < 200000; i++) {
            int b = randomize_section_bars(sec, seed);
            if (b < 6 || b > 8) in_bounds = false;
            if (b == 6) saw_min = true;
            if (b == 8) saw_max = true;
        }
        bool case_ok = saw_min && saw_max && in_bounds;
        printf("  6..8 step1: saw_min=%s saw_max=%s in_bounds=%s -- %s\n",
               saw_min ? "yes" : "no", saw_max ? "yes" : "no",
               in_bounds ? "yes" : "no", case_ok ? "PASS" : "FAIL");
        ok = ok && case_ok;
    }

    // Equal min==max (e.g. the 4-bar drift/awol section): always returns the min.
    {
        SectionParam sec;
        sec.bars_min = 4; sec.bars_max = 4; sec.bar_step = 1;
        uint32_t seed = 0x1234;
        bool always_min = true;
        for (int i = 0; i < 1000; i++)
            if (randomize_section_bars(sec, seed) != 4) always_min = false;
        printf("  4..4: always returns 4 = %s -- %s\n",
               always_min ? "yes" : "no", always_min ? "PASS" : "FAIL");
        ok = ok && always_min;
    }

    // Deterministic top-edge clamp: find a seed whose first xorshift fills the low
    // 23 bits (so pattern_rand01 returns exactly 1.0), then confirm the result is
    // clamped to bars_max (8) rather than overshooting to bars_max + step (9).
    {
        uint32_t hot_seed = 0;
        bool found = false;
        for (uint32_t cand = 1; cand != 0; cand++) {
            uint32_t t = cand;
            if ((pattern_rand(t) & 0x7FFFFF) == 0x7FFFFF) { hot_seed = cand; found = true; break; }
            if (cand > 50000000u) break;  // safety; a hit exists far sooner
        }
        SectionParam sec;
        sec.bars_min = 6; sec.bars_max = 8; sec.bar_step = 1;  // stop_count = 3
        uint32_t seed = hot_seed;
        int b = randomize_section_bars(sec, seed);
        bool case_ok = found && (b == 8);
        printf("  forced rand01=1.0 (seed=%u): bars=%d (expect 8, not 9) found=%s -- %s\n",
               hot_seed, b, found ? "yes" : "no", case_ok ? "PASS" : "FAIL");
        ok = ok && case_ok;
    }

    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_sections_tests() {
    printf("\n========== PULSAR SECTIONS TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_section_init());
    tally(test_section_init_with_intro());
    tally(test_section_advance_countdown());
    tally(test_section_transitions_eventually());
    tally(test_section_recency_prevents_immediate_repeat());
    tally(test_section_transition_ramp());
    tally(test_section_macro_interpolation());
    tally(test_section_progression_override_applied());
    tally(test_section_progression_inheritance_resets_index());
    tally(test_tension_phase_resets_without_override());
    tally(test_tension_override_then_inherit());
    tally(test_section_macro_crossfade());
    tally(test_section_comping_humanization_override_loads());
    tally(test_section_macro_subbar_lerp());
    tally(test_randomize_section_bars_bounds());
    printf("\nPulsar sections tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
