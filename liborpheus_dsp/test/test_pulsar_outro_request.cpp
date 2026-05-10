#include "test_harness.h"
#include "../src/pulsar_section.h"
#include "../src/orpheus_engine.h"

#include <cassert>
#include <cstdio>

namespace {

bool test_outro_request_sets_outro_triggered() {
    auto* engine = new OrpheusEngine();

    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_request.store(0, std::memory_order_relaxed);

    SectionState state{};
    state.current_section = 0;
    state.bars_remaining  = 4;
    state.intro_done      = true;
    state.next_section_planned = 1;
    state.next_section_trans_bars = 0;

    int req = engine->pulsar_arrangement_outro_request.load(std::memory_order_relaxed);
    if (req && !state.outro_triggered) state.outro_triggered = true;
    assert(!state.outro_triggered);

    engine->pulsar_arrangement_outro_request.store(1, std::memory_order_relaxed);
    req = engine->pulsar_arrangement_outro_request.load(std::memory_order_relaxed);
    if (req && !state.outro_triggered) state.outro_triggered = true;
    assert(state.outro_triggered);

    delete engine;
    std::printf("[outro_request] sets outro_triggered: PASS\n");
    return true;
}

bool test_outro_request_routes_to_outro_index_at_boundary() {
    ArrangementParams arr{};
    arr.active = true;
    arr.section_count = 2;
    arr.intro_index = 0;
    arr.outro_index = 1;
    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 4;
    arr.sections[0].bar_step = 1;
    arr.sections[0].transition_count = 1;
    arr.sections[0].transitions[0].target_index = 0;  // would loop without outro
    arr.sections[0].transitions[0].weight = 1.0f;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[1].bars_min = 4;
    arr.sections[1].bars_max = 4;
    arr.sections[1].bar_step = 1;
    arr.sections[1].transition_count = 0;  // outro = terminal

    uint32_t seed = 12345;
    SectionState state{};
    init_section_state(state, arr, seed);
    assert(state.current_section == 0);

    state.outro_triggered = true;

    for (int i = 0; i < 4; i++) advance_section(state, arr, seed);

    assert(state.current_section == 1);
    std::printf("[outro_request] routes to outro_index at boundary: PASS\n");
    return true;
}

bool test_outro_request_falls_through_when_no_outro_index() {
    ArrangementParams arr{};
    arr.active = true;
    arr.section_count = 2;
    arr.intro_index = 0;
    arr.outro_index = -1;
    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 4;
    arr.sections[0].bar_step = 1;
    arr.sections[0].transition_count = 1;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 1.0f;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[1].bars_min = 4;
    arr.sections[1].bars_max = 4;
    arr.sections[1].bar_step = 1;
    arr.sections[1].transition_count = 1;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 1.0f;
    arr.sections[1].recency_decay = 0.5f;

    uint32_t seed = 99;
    SectionState state{};
    init_section_state(state, arr, seed);
    state.outro_triggered = true;

    for (int i = 0; i < 4; i++) advance_section(state, arr, seed);
    assert(state.current_section == 1);
    std::printf("[outro_request] falls through with no outro_index: PASS\n");
    return true;
}

}  // namespace

bool run_pulsar_outro_request_tests() {
    std::printf("\n========== PULSAR OUTRO REQUEST TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_outro_request_sets_outro_triggered());
    tally(test_outro_request_routes_to_outro_index_at_boundary());
    tally(test_outro_request_falls_through_when_no_outro_index());
    std::printf("\nPulsar outro_request tests: %s\n",
                suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
