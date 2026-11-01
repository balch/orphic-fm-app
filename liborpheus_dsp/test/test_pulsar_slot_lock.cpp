// A track's slot lock (port track_N_slot_lock: 0 Auto, 1 EDM, 2 SPACE) holds it on one slot
// whatever energy, the loop-boundary roll or the soloist rule picks. The roll still draws.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "orpheus_dsp.h"
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

namespace {

constexpr int kFrames = 512;
constexpr float kBpm = 240.0f;   // 3000 samples a step, a 16-step loop in ~94 blocks
constexpr int kLoopBlocks = 16 * 3000 / kFrames + 1;
constexpr int kLockTrack = 3;    // fixture engines 9 / 19
constexpr int kOtherTrack = 4;   // fixture engines 14 / 6
// The roll runs inside track 0's turn, after its per-block pick, so only track 0's state
// after a block shows the boundary's pick (fixture engines 21 / 20).
constexpr int kWrapTrack = 0;
constexpr int kVa = 8;

struct Block {
    bool space_slot;
    int active_space_slot;
    float harmonics;
    bool is_soloist;
};

void set_lock(OrpheusEngine* engine, int track, int lock) {
    const std::string port = "track_" + std::to_string(track) + "_slot_lock";
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.pulsar", port.c_str(),
                            static_cast<float>(lock));
}

OrpheusEngine* make_engine(float energy) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_energy.store(energy, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    engine->clock_bpm.store(kBpm, std::memory_order_relaxed);
    return engine;
}

// Loads the vibe and runs `blocks` blocks, recording `track` after each.
std::vector<Block> run(OrpheusEngine* engine, int track, int blocks,
                       const std::function<void(int)>& after = nullptr) {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    trigger_vibe_load(engine);
    std::vector<Block> rec;
    rec.reserve(blocks);
    for (int i = 0; i < blocks; i++) {
        unit_process_pulsar(&unit, engine, kFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[track];
        rec.push_back({ts.space_slot, engine->pulsar_track_active_space_slot[track].load(),
                       ts.harmonics, ts.is_soloist});
        if (after) after(i);
    }
    return rec;
}

// Blocks whose slot is not `space`, counting both the state and the published mirror.
int off_slot(const std::vector<Block>& rec, bool space) {
    int bad = 0;
    for (const Block& b : rec)
        if (b.space_slot != space || b.active_space_slot != (space ? 1 : 0)) bad++;
    return bad;
}

int space_blocks(const std::vector<Block>& rec) {
    int n = 0;
    for (const Block& b : rec) n += b.space_slot ? 1 : 0;
    return n;
}

}  // namespace

static bool test_lock_edm_holds_at_low_energy() {
    printf("\n=== Test: slot lock edm holds at low energy ===\n");
    OrpheusEngine* engine = make_engine(0.2f);
    set_lock(engine, kLockTrack, 1);
    const auto rec = run(engine, kLockTrack, kLoopBlocks * 4);
    const int bad = off_slot(rec, false);
    printf("  %zu blocks at energy 0.2, %d off the EDM slot\n", rec.size(), bad);
    orpheus_engine_destroy(engine);
    const bool ok = bad == 0;
    printf("EDM lock at low energy: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_lock_space_holds_at_high_energy() {
    printf("\n=== Test: slot lock space holds at high energy ===\n");
    OrpheusEngine* engine = make_engine(0.9f);
    set_lock(engine, kLockTrack, 2);
    const auto rec = run(engine, kLockTrack, kLoopBlocks * 4);
    const int bad = off_slot(rec, true);
    printf("  %zu blocks at energy 0.9, %d off the SPACE slot\n", rec.size(), bad);
    orpheus_engine_destroy(engine);
    const bool ok = bad == 0;
    printf("SPACE lock at high energy: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_lock_holds_across_the_loop_roll() {
    printf("\n=== Test: slot lock holds across the loop roll ===\n");
    const int blocks = kLoopBlocks * 10;
    // The unlocked twin shows the roll reaching SPACE, so the locked run is a real check.
    OrpheusEngine* free_engine = make_engine(0.5f);
    const int free_space = space_blocks(run(free_engine, kWrapTrack, blocks));
    orpheus_engine_destroy(free_engine);

    OrpheusEngine* engine = make_engine(0.5f);
    set_lock(engine, kWrapTrack, 1);
    const auto rec = run(engine, kWrapTrack, blocks);
    const int loops = engine->pulsar_state->loop_count;
    const int bad = off_slot(rec, false);
    printf("  %d loops, unlocked SPACE blocks %d, locked off EDM %d\n", loops, free_space, bad);
    orpheus_engine_destroy(engine);
    const bool ok = loops >= 8 && free_space > 0 && bad == 0;
    printf("Lock across the roll: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_lock_leaves_the_rolls_draws_alone() {
    printf("\n=== Test: slot lock leaves the roll's draws alone ===\n");
    const int blocks = kLoopBlocks * 10;
    std::vector<uint32_t> seeds_free, seeds_locked;

    OrpheusEngine* free_engine = make_engine(0.5f);
    const auto free_rec = run(free_engine, kOtherTrack, blocks, [&](int) {
        seeds_free.push_back(free_engine->pulsar_state->mutation_seed);
    });
    const int free_loops = free_engine->pulsar_state->loop_count;
    orpheus_engine_destroy(free_engine);

    OrpheusEngine* locked = make_engine(0.5f);
    set_lock(locked, kLockTrack, 2);
    const auto locked_rec = run(locked, kOtherTrack, blocks, [&](int) {
        seeds_locked.push_back(locked->pulsar_state->mutation_seed);
    });
    orpheus_engine_destroy(locked);

    int seed_diff = 0, slot_diff = 0, first = -1;
    for (int i = 0; i < blocks; i++) {
        const bool s = seeds_free[i] != seeds_locked[i];
        const bool d = free_rec[i].space_slot != locked_rec[i].space_slot;
        if ((s || d) && first < 0) first = i;
        seed_diff += s ? 1 : 0;
        slot_diff += d ? 1 : 0;
    }
    int flips = 0;
    for (int i = 1; i < blocks; i++) flips += free_rec[i].space_slot != free_rec[i - 1].space_slot;
    printf("  %d loops, track %d flips %d, seed differs on %d blocks, slot on %d (first %d)\n",
           free_loops, kOtherTrack, flips, seed_diff, slot_diff, first);
    const bool ok = free_loops >= 8 && flips > 0 && seed_diff == 0 && slot_diff == 0;
    printf("Roll draws alone: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_lock_picks_the_slot_on_an_equal_pair() {
    printf("\n=== Test: slot lock picks the slot on an equal pair ===\n");
    OrpheusEngine* engine = make_engine(0.9f);
    engine->pulsar_track_engine_edm[kOtherTrack].store(kVa, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kOtherTrack].store(kVa, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[kOtherTrack].store(0.2f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[kOtherTrack].store(0.8f, std::memory_order_relaxed);
    set_lock(engine, kOtherTrack, 2);
    const auto rec = run(engine, kOtherTrack, kLoopBlocks * 4);
    int bad = 0;
    for (const Block& b : rec)
        if (!b.space_slot || b.harmonics != 0.8f) bad++;
    printf("  %zu blocks, last harmonics %.3f (want 0.800), %d off the SPACE params\n",
           rec.size(), rec.back().harmonics, bad);
    orpheus_engine_destroy(engine);
    const bool ok = bad == 0;
    printf("Equal pair lock: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_soloist_keeps_its_locked_slot() {
    printf("\n=== Test: soloist keeps its locked slot ===\n");
    OrpheusEngine* engine = make_engine(0.9f);
    push_lickbuilder_band_arrangement(engine, kOtherTrack);
    set_lock(engine, kOtherTrack, 2);
    const auto rec = run(engine, kOtherTrack, kLoopBlocks * 12);
    int solo_blocks = 0;
    for (const Block& b : rec) solo_blocks += b.is_soloist ? 1 : 0;
    const int bad = off_slot(rec, true);
    printf("  %zu blocks, %d as soloist, %d off the SPACE slot\n", rec.size(), solo_blocks, bad);
    orpheus_engine_destroy(engine);
    const bool ok = solo_blocks > kLoopBlocks && bad == 0;
    printf("Soloist lock: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_lock_zero_changes_nothing() {
    printf("\n=== Test: slot lock zero changes nothing ===\n");
    const int blocks = kLoopBlocks * 10;
    OrpheusEngine* plain = make_engine(0.5f);
    const auto plain_rec = run(plain, kLockTrack, blocks);
    const uint32_t plain_seed = plain->pulsar_state->mutation_seed;
    orpheus_engine_destroy(plain);

    OrpheusEngine* zero = make_engine(0.5f);
    set_lock(zero, kLockTrack, 0);
    const auto zero_rec = run(zero, kLockTrack, blocks);
    const uint32_t zero_seed = zero->pulsar_state->mutation_seed;
    orpheus_engine_destroy(zero);

    int diff = 0;
    for (int i = 0; i < blocks; i++)
        diff += plain_rec[i].space_slot != zero_rec[i].space_slot
            || plain_rec[i].active_space_slot != zero_rec[i].active_space_slot;
    printf("  %d blocks, %d differ, SPACE blocks %d, seeds %s\n", blocks, diff,
           space_blocks(plain_rec), plain_seed == zero_seed ? "equal" : "differ");
    const bool ok = diff == 0 && plain_seed == zero_seed;
    printf("Lock zero: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_slot_lock_tests() {
    printf("\n=== Pulsar Slot Lock Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_lock_edm_holds_at_low_energy()) suite_pass++; else suite_fail++;
    if (test_lock_space_holds_at_high_energy()) suite_pass++; else suite_fail++;
    if (test_lock_holds_across_the_loop_roll()) suite_pass++; else suite_fail++;
    if (test_lock_leaves_the_rolls_draws_alone()) suite_pass++; else suite_fail++;
    if (test_lock_picks_the_slot_on_an_equal_pair()) suite_pass++; else suite_fail++;
    if (test_soloist_keeps_its_locked_slot()) suite_pass++; else suite_fail++;
    if (test_lock_zero_changes_nothing()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
