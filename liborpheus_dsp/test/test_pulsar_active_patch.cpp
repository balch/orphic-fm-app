// Each Pulsar track reports the DX patch it is sounding, read from the final harmonics
// (macro walk included), and -1 off the DX engines.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cstring>

static constexpr int kBlockFrames = 512;
static constexpr int kTrack = 4;      // melodic, no ducking
static constexpr int kDx2 = 3;
static constexpr int kVa = 8;

// Patch 5 sits at the middle of its 1/32.64 band.
static constexpr float kPatch5 = (5.0f + 0.5f) / 32.64f;

// Runs a pinned-harmonics track for a while and returns the reported patch.
static int run_patch(int engine_id, float walk_range, float energy, bool pause_after = false) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kTrack);
    engine->pulsar_energy.store(energy, std::memory_order_relaxed);
    engine->pulsar_track_engine_edm[kTrack].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kTrack].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kTrack].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[kTrack].store(kPatch5, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[kTrack].store(kPatch5, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics[kTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[kTrack].store(1, std::memory_order_relaxed);
    // Macro source 1 is ENERGY.
    engine->pulsar_track_harmonics_macro_source[kTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_macro_source_space[kTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_macro_range[kTrack].store(walk_range, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_macro_range_space[kTrack].store(walk_range, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    // The macro is smoothed, so give it time to settle.
    for (int i = 0; i < 400; i++)
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    if (pause_after) {
        engine->pulsar_playing.store(0, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    }
    const int patch = engine->pulsar_track_active_patch[kTrack].load(std::memory_order_relaxed);
    orpheus_engine_destroy(engine);
    return patch;
}

static bool test_static_dx_track() {
    printf("\n=== Test: a DX2 track at a fixed harmonics reports that patch ===\n");
    const int patch = run_patch(kDx2, 0.0f, 0.5f);
    printf("  patch: %d\n", patch);
    bool ok = (patch == 5);
    printf("Static DX patch: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_walk_moves_patch() {
    printf("\n=== Test: the energy macro walk moves the reported patch ===\n");
    const int patch = run_patch(kDx2, 0.05f, 1.0f);
    printf("  patch: %d\n", patch);
    bool ok = (patch == 6 || patch == 7);
    printf("Walk moves patch: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_non_dx_reports_minus_one() {
    printf("\n=== Test: a non-DX track reports -1 ===\n");
    const int patch = run_patch(kVa, 0.0f, 0.5f);
    printf("  patch: %d\n", patch);
    bool ok = (patch == -1);
    printf("Non-DX patch: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_paused_reports_minus_one() {
    printf("\n=== Test: pausing clears the reported patch ===\n");
    const int patch = run_patch(kDx2, 0.0f, 0.5f, true);
    printf("  patch: %d\n", patch);
    bool ok = (patch == -1);
    printf("Paused patch: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_active_patch_tests() {
    printf("\n=== Pulsar Active Patch Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_static_dx_track()) suite_pass++; else suite_fail++;
    if (test_walk_moves_patch()) suite_pass++; else suite_fail++;
    if (test_non_dx_reports_minus_one()) suite_pass++; else suite_fail++;
    if (test_paused_reports_minus_one()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
