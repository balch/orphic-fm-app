// Outro request: the host asks for an ending, the arrangement takes the next
// boundary to arr.outro_index.
//
// The latch test drives the real pulsar unit. An earlier version re-implemented
// the latch in the test body and asserted on its own copy, so it held even when
// production stopped reading the atomic.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/pulsar_section.h"
#include "../src/orpheus_engine.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"

#include <cstdio>
#include <cstring>

namespace {

// Bare assert() is a no-op under Release's -DNDEBUG, which silently passes
// broken tests. Mirrored from test_master_fader.cpp.
struct Checker {
    bool ok = true;
    void check(bool cond, const char* expr) {
        if (!cond) {
            ok = false;
            std::printf("    CHECK FAILED: %s\n", expr);
        }
    }
};

#define CHK(c, expr) (c).check(expr, #expr)

static GraphUnit make_outro_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

// Two 2-bar sections (bars_min == bars_max draws no RNG), section 0 pinned as
// the opener, section 1 as the outro target.
static OrpheusEngine* make_outro_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    setup_jam_arrangement(engine);

    constexpr int kSectionStride = kSectionDataFields;
    for (int s = 0; s < 2; s++) {
        const int b = s * kSectionStride;
        engine->pulsar_section_data[b + 0].store(2.0f, std::memory_order_relaxed);  // bars_min
        engine->pulsar_section_data[b + 1].store(2.0f, std::memory_order_relaxed);  // bars_max
        engine->pulsar_section_data[b + 2].store(1.0f, std::memory_order_relaxed);  // bar_step
    }
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_request.store(0, std::memory_order_relaxed);
    engine->clock_bpm.store(180.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(2, std::memory_order_release);
    return engine;
}

static constexpr int kMaxBlocks = 600;

// ── The unit latches the request, and only once it is actually set ───────────
bool test_outro_request_latches_through_the_unit() {
    std::printf("\n=== Test: unit_process_pulsar latches pulsar_arrangement_outro_request ===\n");
    Checker c;
    OrpheusEngine* engine = make_outro_engine();
    trigger_vibe_load(engine);
    GraphUnit unit = make_outro_unit();

    // A full section with no request must leave the latch alone.
    for (int i = 0; i < 120; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    CHK(c, !engine->pulsar_state->section_state.outro_triggered);

    engine->pulsar_arrangement_outro_request.store(1, std::memory_order_relaxed);

    bool latched = false;
    for (int i = 0; i < kMaxBlocks && !latched; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        latched = engine->pulsar_state->section_state.outro_triggered;
    }
    CHK(c, latched);

    // Sticky: the latch survives the request being cleared underneath it.
    engine->pulsar_arrangement_outro_request.store(0, std::memory_order_relaxed);
    for (int i = 0; i < 60; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    CHK(c, engine->pulsar_state->section_state.outro_triggered);

    std::printf("  [outro_request] latches through the unit: %s\n", c.ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return c.ok;
}

// ── A latched request re-routes the next boundary to outro_index ─────────────
bool test_outro_request_routes_to_outro_index_at_boundary() {
    Checker c;
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
    CHK(c, state.current_section == 0);

    state.outro_triggered = true;

    for (int i = 0; i < 4; i++) advance_section(state, arr, seed);

    CHK(c, state.current_section == 1);
    std::printf("  [outro_request] routes to outro_index at boundary: %s\n", c.ok ? "PASS" : "FAIL");
    return c.ok;
}

// ── With no outro authored, the request changes nothing ──────────────────────
bool test_outro_request_falls_through_when_no_outro_index() {
    Checker c;
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
    CHK(c, state.current_section == 1);
    std::printf("  [outro_request] falls through with no outro_index: %s\n", c.ok ? "PASS" : "FAIL");
    return c.ok;
}

// Integration: the tests above hand-roll the pull logic inline, never touching the real
// consumption code in unit_process_pulsar(). This drives the actual atomic through a
// render loop and checks retraction self-clears (exchange, not load).
bool test_outro_request_retraction_through_real_engine() {
    std::printf("\n=== Test: outro_request retraction through the real atomic/engine path ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    setup_jam_arrangement(engine);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(300.0f, std::memory_order_relaxed);

    // pulsar_state is allocated lazily on the first unit_process_pulsar() call, so it
    // must be fetched AFTER warming up, not before (a pre-fetched pointer is null here).
    for (int block = 0; block < 20; block++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    bool starts_clear = ps && !ps->section_state.outro_triggered;

    // Arm through the real port.
    engine->pulsar_arrangement_outro_request.store(1, std::memory_order_relaxed);
    bool armed = false;
    for (int block = 0; block < 100 && !armed; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (ps->section_state.outro_triggered) armed = true;
    }
    // Self-clears on consumption: exchange(), not load() — a regression back to load()
    // would leave this atomic stuck at 1 forever.
    bool armed_self_cleared =
        engine->pulsar_arrangement_outro_request.load(std::memory_order_relaxed) == 0;

    // Retract through the real port — the branch under test (orpheus_unit_pulsar.cpp,
    // `else if (outro_req < 0) ... = false;`). Deleting that branch would hang this loop.
    engine->pulsar_arrangement_outro_request.store(-1, std::memory_order_relaxed);
    bool retracted = false;
    for (int block = 0; block < 100 && !retracted; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (!ps->section_state.outro_triggered) retracted = true;
    }
    bool retract_self_cleared =
        engine->pulsar_arrangement_outro_request.load(std::memory_order_relaxed) == 0;

    bool ok = starts_clear && armed && armed_self_cleared && retracted && retract_self_cleared;
    std::printf("  starts_clear=%d armed=%d armed_self_cleared=%d retracted=%d retract_self_cleared=%d -- %s\n",
                starts_clear, armed, armed_self_cleared, retracted, retract_self_cleared,
                ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

}  // namespace

bool run_pulsar_outro_request_tests() {
    std::printf("\n========== PULSAR OUTRO REQUEST TESTS ==========\n");
    // pin_pulsar_rngs re-seeds the process-global stmlib::Random; restore it so
    // later suites keep their own draw sequence.
    const uint32_t saved_random = stmlib::Random::state();
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_outro_request_latches_through_the_unit());
    tally(test_outro_request_routes_to_outro_index_at_boundary());
    tally(test_outro_request_falls_through_when_no_outro_index());
    tally(test_outro_request_retraction_through_real_engine());
    stmlib::Random::Seed(saved_random);
    std::printf("\nPulsar outro_request tests: %s\n",
                suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
