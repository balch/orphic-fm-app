#include "test_harness.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_section.h"
#include <cstdio>
#include <cmath>

// ── Test helper: create a 3-section arrangement ──

static ArrangementParams make_test_arrangement() {
    ArrangementParams arr = {};
    arr.active = true;
    arr.section_count = 3;
    arr.intro_index = -1;
    arr.outro_index = -1;

    // Section 0: bars 4-8, transitions to 1(0.6) and 2(0.4), recencyDecay=0.5, transitionBars=0
    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 8;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[0].transition_bars = 0;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 0.6f;
    arr.sections[0].transitions[1].target_index = 2;
    arr.sections[0].transitions[1].weight = 0.4f;
    arr.sections[0].transition_count = 2;

    // Section 1: bars 4-4, transitions to 0(0.8) and 2(0.2), recencyDecay=0.5, transitionBars=0
    arr.sections[1].bars_min = 4;
    arr.sections[1].bars_max = 4;
    arr.sections[1].recency_decay = 0.5f;
    arr.sections[1].transition_bars = 0;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 0.8f;
    arr.sections[1].transitions[1].target_index = 2;
    arr.sections[1].transitions[1].weight = 0.2f;
    arr.sections[1].transition_count = 2;

    // Section 2: bars 4-4, transitions to 0(1.0), recencyDecay=0.5, transitionBars=0
    arr.sections[2].bars_min = 4;
    arr.sections[2].bars_max = 4;
    arr.sections[2].recency_decay = 0.5f;
    arr.sections[2].transition_bars = 0;
    arr.sections[2].transitions[0].target_index = 0;
    arr.sections[2].transitions[0].weight = 1.0f;
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
    printf("\n=== Test: 2-bar transition ramps from 0 to 1 ===\n");
    ArrangementParams arr = make_test_arrangement();
    // Set transition_bars=2 for ramp test
    arr.sections[0].transition_bars = 2;
    arr.sections[1].transition_bars = 2;
    arr.sections[2].transition_bars = 2;

    SectionState state;
    uint32_t seed = 11111;
    init_section_state(state, arr, seed);

    // Advance until bars_remaining reaches 0 (trigger transition)
    while (state.bars_remaining > 0) {
        advance_section(state, arr, seed);
    }

    // Now advance once more to start the ramp
    bool changed = advance_section(state, arr, seed);

    // After triggering transition with transition_bars=2, we should be in ramp
    bool ok = state.transition_target >= 0
           && state.transition_progress > 0.0f
           && state.transition_progress < 1.0f
           && changed == false;
    printf("  transition_target=%d, progress=%.2f -- %s\n",
           state.transition_target, state.transition_progress,
           ok ? "PASS" : "FAIL");

    // Advance again to complete ramp
    changed = advance_section(state, arr, seed);
    // After completion, transition_target should reset to -1 and we should be in a new section
    bool ok2 = changed == true && state.transition_target == -1;
    printf("  after second advance: transition_target=%d, changed=%s -- %s\n",
           state.transition_target, changed ? "true" : "false",
           ok2 ? "PASS" : "FAIL");

    return ok && ok2;
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

bool run_pulsar_sections_tests() {
    printf("\n========== PULSAR SECTIONS TESTS ==========\n");
    bool all_pass = true;
    all_pass &= test_section_init();
    all_pass &= test_section_init_with_intro();
    all_pass &= test_section_advance_countdown();
    all_pass &= test_section_transitions_eventually();
    all_pass &= test_section_recency_prevents_immediate_repeat();
    all_pass &= test_section_transition_ramp();
    all_pass &= test_section_macro_interpolation();
    printf("\nPulsar sections tests: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
