// Energy picks a track's EDM or SPACE slot, and the slot picks its params, even when both
// slots hold the same engine. Before the slot flag, edm == spa pinned the EDM slot and
// every _space param of such a track (Orphic Lore's Hammond, its dry bell) never played.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static constexpr int kBlockFrames = 512;
static constexpr int kSlotTrack = 4;  // melodic, no texture notch

struct SlotRun {
    float volume_debug = 0.0f;  // the track's resolved volume after the last block
    int space_slot = -1;        // pulsar_track_active_space_slot after the last block
};

// Plays `track` through each energy stage in turn, `blocks` blocks per stage.
static SlotRun run_slot(int engine_edm, int engine_space, float vol_edm, float vol_space,
                        const std::vector<float>& energies, int blocks = 60) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kSlotTrack);
    engine->pulsar_track_engine_edm[kSlotTrack].store(engine_edm, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kSlotTrack].store(engine_space, std::memory_order_relaxed);
    engine->pulsar_track_volume[kSlotTrack].store(vol_edm, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[kSlotTrack].store(vol_space, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kSlotTrack].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_energy.store(energies.front(), std::memory_order_relaxed);
    trigger_vibe_load(engine);

    for (float e : energies) {
        engine->pulsar_energy.store(e, std::memory_order_relaxed);
        for (int i = 0; i < blocks; i++)
            unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    }
    SlotRun r;
    r.volume_debug = engine->pulsar_track_volume_debug[kSlotTrack].load(std::memory_order_relaxed);
    r.space_slot = engine->pulsar_track_active_space_slot[kSlotTrack].load(std::memory_order_relaxed);
    orpheus_engine_destroy(engine);
    return r;
}

static bool test_slot_same_engine_low_energy_reads_space() {
    printf("\n=== Test: same engine in both slots, low energy plays the SPACE slot's params ===\n");
    // Only the SPACE volume differs between the runs, so the ratio isolates the slot pick.
    const SlotRun quiet = run_slot(8, 8, 0.8f, 0.2f, {0.2f});
    const SlotRun loud = run_slot(8, 8, 0.8f, 0.8f, {0.2f});
    const float ratio = (loud.volume_debug > 0.0f) ? quiet.volume_debug / loud.volume_debug : 0.0f;
    printf("  space_slot=%d volume quiet=%.4f loud=%.4f ratio=%.3f (expect 0.25)\n",
           quiet.space_slot, quiet.volume_debug, loud.volume_debug, ratio);
    const bool ok = quiet.space_slot == 1 && loud.volume_debug > 0.01f
                 && std::fabs(ratio - 0.25f) < 0.01f;
    printf("Same-engine low energy: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_slot_same_engine_high_energy_reads_edm() {
    printf("\n=== Test: same engine in both slots, high energy ignores the SPACE slot ===\n");
    const SlotRun a = run_slot(8, 8, 0.8f, 0.2f, {0.8f});
    const SlotRun b = run_slot(8, 8, 0.8f, 0.8f, {0.8f});
    printf("  space_slot=%d volume a=%.4f b=%.4f\n", a.space_slot, a.volume_debug, b.volume_debug);
    const bool ok = a.space_slot == 0 && a.volume_debug > 0.01f && a.volume_debug == b.volume_debug;
    printf("Same-engine high energy: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_slot_same_engine_keeps_slot_in_crossfade_zone() {
    printf("\n=== Test: same engine in both slots keeps its slot between 0.4 and 0.6 ===\n");
    const SlotRun from_low = run_slot(8, 8, 0.8f, 0.2f, {0.2f, 0.5f}, 200);
    const SlotRun from_high = run_slot(8, 8, 0.8f, 0.2f, {0.8f, 0.5f}, 200);
    printf("  0.2 -> 0.5: space_slot=%d   0.8 -> 0.5: space_slot=%d\n",
           from_low.space_slot, from_high.space_slot);
    const bool ok = from_low.space_slot == 1 && from_high.space_slot == 0;
    printf("Same-engine crossfade hold: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_slot_different_engines_still_switch() {
    printf("\n=== Test: different engines still switch slot with energy ===\n");
    const SlotRun low = run_slot(8, 14, 0.8f, 0.2f, {0.2f});
    const SlotRun high = run_slot(8, 14, 0.8f, 0.2f, {0.8f});
    printf("  low: space_slot=%d volume=%.4f   high: space_slot=%d volume=%.4f\n",
           low.space_slot, low.volume_debug, high.space_slot, high.volume_debug);
    const bool ok = low.space_slot == 1 && high.space_slot == 0
                 && low.volume_debug > 0.0f && high.volume_debug > low.volume_debug;
    printf("Different-engine switch: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_slot_tests() {
    printf("\n=== Pulsar Slot Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_slot_same_engine_low_energy_reads_space()) suite_pass++; else suite_fail++;
    if (test_slot_same_engine_high_energy_reads_edm()) suite_pass++; else suite_fail++;
    if (test_slot_same_engine_keeps_slot_in_crossfade_zone()) suite_pass++; else suite_fail++;
    if (test_slot_different_engines_still_switch()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
