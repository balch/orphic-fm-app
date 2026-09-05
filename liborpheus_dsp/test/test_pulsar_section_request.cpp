#include "test_harness.h"
#include "pulsar_section.h"
#include <cstdio>

#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include <cstring>

// Two sections, each 1 bar, section 0 -> 1 -> 0 deterministically.
static ArrangementParams two_section_arrangement() {
    ArrangementParams arr{};
    arr.active = true;
    arr.section_count = 3;
    arr.intro_index = 0;
    arr.outro_index = 2;
    for (int s = 0; s < 3; s++) {
        arr.sections[s].bars_min = 1;
        arr.sections[s].bars_max = 1;
        arr.sections[s].bar_step = 1;
        arr.sections[s].transition_count = 1;
        arr.sections[s].transitions[0].target_index = (s + 1) % 2;
        arr.sections[s].transitions[0].weight = 1.0f;
        arr.sections[s].transitions[0].transition_bars = 0;
    }
    return arr;
}

static bool test_request_jumps_at_boundary() {
    printf("\n=== Test: pending_section_request re-routes at the bar boundary ===\n");
    ArrangementParams arr = two_section_arrangement();
    SectionState state{};
    uint32_t seed = 12345u;
    init_section_state(state, arr, seed);

    state.pending_section_request = 2;          // ask for the third section
    bool changed = advance_section(state, arr, seed);

    bool ok = changed
        && state.current_section == 2
        && state.pending_section_request == -1;  // consumed
    printf("  section=%d pending=%d -- %s\n",
           state.current_section, state.pending_section_request, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_request_survives_a_non_boundary_bar() {
    printf("\n=== Test: a request set mid-section survives until the boundary ===\n");
    ArrangementParams arr = two_section_arrangement();
    arr.sections[0].bars_min = 3;
    arr.sections[0].bars_max = 3;
    SectionState state{};
    uint32_t seed = 999u;
    init_section_state(state, arr, seed);

    state.pending_section_request = 2;
    advance_section(state, arr, seed);           // bar 1, no boundary
    bool held = state.pending_section_request == 2 && state.current_section == 0;
    advance_section(state, arr, seed);           // bar 2, no boundary
    advance_section(state, arr, seed);           // bar 3, boundary

    bool ok = held && state.current_section == 2 && state.pending_section_request == -1;
    printf("  held=%d section=%d -- %s\n", held, state.current_section, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_request_outranks_outro() {
    printf("\n=== Test: an explicit request wins over outro_triggered ===\n");
    ArrangementParams arr = two_section_arrangement();
    SectionState state{};
    uint32_t seed = 7u;
    init_section_state(state, arr, seed);

    state.outro_triggered = true;                // would route to outro_index 2
    state.pending_section_request = 1;           // but the conductor asked for 1
    advance_section(state, arr, seed);

    bool ok = state.current_section == 1;
    printf("  section=%d -- %s\n", state.current_section, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_out_of_range_request_is_ignored() {
    printf("\n=== Test: an out-of-range request falls back to the planned path ===\n");
    ArrangementParams arr = two_section_arrangement();
    SectionState state{};
    uint32_t seed = 31u;
    init_section_state(state, arr, seed);

    state.pending_section_request = 99;
    advance_section(state, arr, seed);

    bool ok = state.current_section == 1 && state.pending_section_request == -1;
    printf("  section=%d pending=%d -- %s\n",
           state.current_section, state.pending_section_request, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_init_clears_pending_request() {
    printf("\n=== Test: init_section_state clears a stale request ===\n");
    ArrangementParams arr = two_section_arrangement();
    SectionState state{};
    state.pending_section_request = 1;
    uint32_t seed = 5u;
    init_section_state(state, arr, seed);
    bool ok = state.pending_section_request == -1;
    printf("  pending=%d -- %s\n", state.pending_section_request, ok ? "PASS" : "FAIL");
    return ok;
}

// Covers the inactive path, which the test above does not: a default-constructed
// ArrangementParams (active=false, section_count=0) takes the early-return branch,
// so this only passes if the sentinel is assigned BEFORE that return.
static bool test_init_on_inactive_arrangement_clears_pending_request() {
    printf("\n=== Test: init_section_state clears pending_request on the inactive path too ===\n");
    ArrangementParams arr{};
    SectionState state{};
    uint32_t seed = 5u;
    init_section_state(state, arr, seed);
    bool ok = state.pending_section_request == -1;
    printf("  pending=%d -- %s\n", state.pending_section_request, ok ? "PASS" : "FAIL");
    return ok;
}

// 3-section hard-cut arrangement via real atomics: 0 and 1 only transition to each
// other, so section 2 is reachable only by explicit request. Field layout mirrors
// setup_jam_arrangement's section-0 pattern (test_pulsar_helpers.h).
static void setup_three_section_hard_cut_arrangement(OrpheusEngine* engine) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(3, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = kSectionDataFields;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
    }
    for (int s = 0; s < 3; s++) {
        int b = s * kSectionStride;
        section_data[b + 0] = 1;    // bars_min
        section_data[b + 1] = 1;    // bars_max
        section_data[b + 2] = 1;    // bar_step
        section_data[b + 3] = 0.8f; // recency_decay
        section_data[b + 4] = 1;    // transition_count
        section_data[b + 5] = -1; section_data[b + 6] = -1;
        section_data[b + 7] = -1; section_data[b + 8] = -1;
        section_data[b + 9] = 0;    // has_solo = false
    }
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // s0->1, s1->0, s2->1 (mirrors two_section_arrangement's (s+1)%2 formula): nothing
    // transitions INTO section 2 naturally.
    float trans[8 * 8 * 3] = {};
    trans[0 * 24 + 0] = 1; trans[0 * 24 + 1] = 1.0f; trans[0 * 24 + 2] = 0;
    trans[1 * 24 + 0] = 0; trans[1 * 24 + 1] = 1.0f; trans[1 * 24 + 2] = 0;
    trans[2 * 24 + 0] = 1; trans[2 * 24 + 1] = 1.0f; trans[2 * 24 + 2] = 0;
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 6; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// Integration: the 5 tests above hand-set pending_section_request directly, bypassing
// the atomic. This drives the real port -> exchange() -> "- 1" translation pipeline;
// section 2 has no natural inbound edge, so arrival there can only be the request.
static bool test_section_request_integration_through_real_engine() {
    printf("\n=== Test: section_request through the real atomic/engine path reaches an unreachable section ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    setup_three_section_hard_cut_arrangement(engine);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(300.0f, std::memory_order_relaxed);

    // pulsar_state is allocated lazily on the first unit_process_pulsar() call, so it
    // must be fetched AFTER warming up, not before.
    for (int block = 0; block < 20; block++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    bool ps_valid = ps != nullptr;

    // Structural sanity: each section has exactly one outgoing edge (weight 1.0, no
    // competing candidates), so 0 and 1 cycle forever and NEVER visit 2 on their own.
    bool never_naturally_reached_2 = true;
    for (int block = 0; block < 100; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (ps_valid && ps->section_state.current_section == 2) never_naturally_reached_2 = false;
    }

    // Request section 2 through the real port: value is sectionIndex + 1.
    engine->pulsar_arrangement_section_request.store(3, std::memory_order_relaxed);
    bool reached = false;
    for (int block = 0; block < 50 && !reached; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (ps_valid && ps->section_state.current_section == 2) reached = true;
    }
    // Self-clears on consumption: exchange(), not load() — a regression back to load()
    // would leave this atomic stuck at 3 forever.
    bool self_cleared =
        engine->pulsar_arrangement_section_request.load(std::memory_order_relaxed) == 0;

    bool ok = ps_valid && never_naturally_reached_2 && reached && self_cleared;
    printf("  ps_valid=%d never_naturally_reached_2=%d reached=%d self_cleared=%d final_section=%d -- %s\n",
           ps_valid, never_naturally_reached_2, reached, self_cleared,
           ps_valid ? ps->section_state.current_section : -1, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_section_request_tests() {
    printf("\n========== Pulsar Section Request ==========\n");
    int passed = 0, failed = 0;
    auto run = [&](bool (*fn)()) { if (fn()) passed++; else failed++; };
    run(test_request_jumps_at_boundary);
    run(test_request_survives_a_non_boundary_bar);
    run(test_request_outranks_outro);
    run(test_out_of_range_request_is_ignored);
    run(test_init_clears_pending_request);
    run(test_init_on_inactive_arrangement_clears_pending_request);
    run(test_section_request_integration_through_real_engine);
    printf("\n  Pulsar Section Request: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
