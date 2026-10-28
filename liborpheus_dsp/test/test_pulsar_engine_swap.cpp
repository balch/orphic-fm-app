// A changed engine port waits for the track's next note-on before it sounds, or after
// 16 steps adopts on the next bar line with a forced strike, so a live swap never cuts a note.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_pattern_gen.h"
#include "orpheus_dsp.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <vector>

namespace {

constexpr int kVa = 8;
constexpr int kStr = 19;
constexpr int kDx = 2;
constexpr int kVowelFof = 107;

constexpr int kMelodicTrack = 4;
constexpr int kPadTrack = 6;
constexpr int kFrames = 512;
constexpr float kBpm = 120.0f;   // 6000 samples a step
constexpr int kBarBlocks = 16 * 6000 / kFrames + 1;   // the latch's 16-step bound, in blocks
// Energy 1 holds the elastic tempo still, so 16 steps stay 96000 samples for kBarBlocks.
constexpr float kSteadyEnergy = 1.0f;
constexpr int kTailBlocks = 8;

bool is_dx(int id) { return id >= 2 && id <= 4; }

struct Block {
    int active_engine;
    bool pending_retrig;
    bool voice_active;
    int trigger_offset;
    bool pin_harmonics;
    int bar_pos;       // the track's step in its bar after the block
    int boundary_at;   // sample of the block's step boundary; -1 when none
};

OrpheusEngine* make_engine(int track, int engine_id, float energy) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_energy.store(energy, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    engine->clock_bpm.store(kBpm, std::memory_order_relaxed);
    engine->pulsar_track_engine_edm[track].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[track].store(engine_id, std::memory_order_relaxed);
    solo_track(engine, track);
    return engine;
}

// Track 4 playing generated notes. Without a density override a generated track stays empty.
OrpheusEngine* make_melodic_engine() {
    OrpheusEngine* engine = make_engine(kMelodicTrack, kVa, 0.5f);
    engine->pulsar_track_density_override[kMelodicTrack].store(0.5f, std::memory_order_relaxed);
    return engine;
}

// Track 4 on VA with no note-ons: no density override, genre density 0.
OrpheusEngine* make_silent_engine() {
    OrpheusEngine* engine = make_engine(kMelodicTrack, kVa, kSteadyEnergy);
    engine->pulsar_genre_density[kMelodicTrack].store(0.0f, std::memory_order_relaxed);
    return engine;
}

// The held PAD bed of test_flag_off_holds_first_root, solo'd on one engine in both slots.
OrpheusEngine* make_pad_engine(int engine_id) {
    OrpheusEngine* engine = make_engine(kPadTrack, engine_id, kSteadyEnergy);
    engine->pulsar_genre_chords_per_bar.store(1, std::memory_order_relaxed);
    engine->pulsar_track_role[kPadTrack].store(static_cast<int>(TrackRole::CHORDAL), std::memory_order_relaxed);
    engine->pulsar_track_comping_style[kPadTrack].store(static_cast<int>(CompingStyleId::PAD), std::memory_order_relaxed);
    engine->pulsar_track_arp_mode[kPadTrack].store(static_cast<int>(ArpModeId::NEVER), std::memory_order_relaxed);
    engine->pulsar_track_restrike_on_chord_change[kPadTrack].store(0, std::memory_order_relaxed);
    engine->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);
    engine->pulsar_custom_progression_length.store(4, std::memory_order_relaxed);
    const int degrees[4] = {0, 3, 4, 5};
    for (int i = 0; i < 4; i++) {
        engine->pulsar_custom_progression[i].store(degrees[i], std::memory_order_relaxed);
        engine->pulsar_custom_progression_glide[i].store(0.0f, std::memory_order_relaxed);
    }
    return engine;
}

// Loads the vibe and runs `blocks` blocks, recording track `track` and pulsar_out_l.
// `poke` runs after each block, so a test can write a port mid-run.
std::vector<Block> run(OrpheusEngine* engine, int track, int blocks, std::vector<float>& out_l,
                       const std::function<void(int, const Block&)>& poke = nullptr) {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    trigger_vibe_load(engine);
    std::vector<Block> rec;
    rec.reserve(blocks);
    out_l.reserve(static_cast<size_t>(blocks) * kFrames);
    int prev_playhead = -1;
    for (int i = 0; i < blocks; i++) {
        unit_process_pulsar(&unit, engine, kFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[track];
        // A step is longer than a block, so a moved playhead means one boundary, and the
        // accumulator holds the samples since it: boundary = frames - 1 - floor(accumulator).
        const int boundary_at = ts.playhead != prev_playhead
            ? kFrames - 1 - static_cast<int>(std::floor(engine->pulsar_state->clock_accumulator)) : -1;
        prev_playhead = ts.playhead;
        rec.push_back({engine->pulsar_track_active_engine[track].load(), ts.pending_retrig,
                       ts.voice_active, ts.trigger_offset, ts.pin_harmonics,
                       ((ts.playhead - ts.wrap_start) % 16 + 16) % 16, boundary_at});
        out_l.insert(out_l.end(), engine->pulsar_out_l, engine->pulsar_out_l + kFrames);
        if (poke) poke(i, rec.back());
    }
    return rec;
}

// First block at or after `from` whose active engine is `id`; -1 when none.
int first_block_on(const std::vector<Block>& rec, int from, int id) {
    for (int i = from; i < static_cast<int>(rec.size()); i++)
        if (rec[i].active_engine == id) return i;
    return -1;
}

float peak(const std::vector<float>& x, size_t from, size_t to) {
    float p = 0.0f;
    for (size_t n = from; n < std::min(to, x.size()); n++) p = std::max(p, std::fabs(x[n]));
    return p;
}

// Largest sample-to-sample step over [from, to).
float max_step(const std::vector<float>& x, size_t from, size_t to) {
    float s = 0.0f;
    for (size_t n = std::max<size_t>(from, 1); n < std::min(to, x.size()); n++)
        s = std::max(s, std::fabs(x[n] - x[n - 1]));
    return s;
}

}  // namespace

static bool test_swap_waits_for_note_on() {
    printf("\n=== Test: a melodic track keeps its engine until its next note-on ===\n");
    OrpheusEngine* engine = make_melodic_engine();
    int write_block = -1;
    std::vector<float> out_l;
    const auto rec = run(engine, kMelodicTrack, kBarBlocks * 4, out_l, [&](int i, const Block& b) {
        if (write_block < 0 && b.pending_retrig) {
            engine->pulsar_track_engine_edm[kMelodicTrack].store(kVowelFof, std::memory_order_relaxed);
            write_block = i;
        }
    });
    bool ok = write_block >= 0;
    int adopt = -1;
    for (int i = write_block + 1; ok && i < static_cast<int>(rec.size()); i++) {
        if (rec[i].pending_retrig) {
            adopt = i;
            ok &= rec[i].active_engine == kVowelFof;
            break;
        }
        if (rec[i].active_engine != kVa) {
            printf("  block %d: engine %d before the next note-on\n", i, rec[i].active_engine);
            ok = false;
        }
    }
    ok &= adopt > write_block && adopt - write_block < kBarBlocks;
    printf("  write at block %d, next note-on at block %d (engine %d)\n", write_block, adopt,
           adopt >= 0 ? rec[adopt].active_engine : -1);
    orpheus_engine_destroy(engine);
    printf("Waits for note-on: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool held_pad_swaps(int to_engine) {
    OrpheusEngine* engine = make_pad_engine(kVa);
    const int write_block = kBarBlocks * 2 + 50;
    std::vector<float> out_l;
    const auto rec = run(engine, kPadTrack, write_block + kBarBlocks * 4, out_l, [&](int i, const Block&) {
        if (i == write_block) {
            engine->pulsar_track_engine_edm[kPadTrack].store(to_engine, std::memory_order_relaxed);
            engine->pulsar_track_engine_space[kPadTrack].store(to_engine, std::memory_order_relaxed);
        }
    });
    const int adopt = first_block_on(rec, write_block + 1, to_engine);
    bool ok = adopt > write_block && adopt - write_block <= 2 * kBarBlocks;
    // The forced strike lands on the bar line's boundary sample.
    const bool on_bar = adopt >= 0 && rec[adopt].bar_pos == 0 && rec[adopt].boundary_at >= 0
        && rec[adopt].trigger_offset == rec[adopt].boundary_at;
    ok &= on_bar;
    if (adopt >= 0)
        printf("  adopt block: bar pos %d, boundary %d, trigger offset %d\n", rec[adopt].bar_pos,
               rec[adopt].boundary_at, rec[adopt].trigger_offset);
    float p = 0.0f;
    if (adopt >= 0) {
        // Past the cut VA note's tail through the output filters (gone within ~6 blocks),
        // so only a strike on the new engine counts.
        const size_t from = static_cast<size_t>(adopt + kTailBlocks) * kFrames;
        p = peak(out_l, from, static_cast<size_t>(adopt + kBarBlocks) * kFrames);
    }
    ok &= p > 1e-3f;
    printf("  write at block %d (voice_active %d), engine %d at block %d, peak after %.5f\n",
           write_block, rec[write_block].voice_active ? 1 : 0, to_engine, adopt, p);
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_held_pad_swaps_on_a_bar_line() {
    printf("\n=== Test: a held PAD swaps to STR on a bar line and strikes it ===\n");
    const bool ok = held_pad_swaps(kStr);
    printf("Held pad to STR: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_held_pad_swaps_into_dx() {
    printf("\n=== Test: a held PAD swaps to DX on a bar line and strikes it ===\n");
    const bool ok = held_pad_swaps(kDx);
    printf("Held pad to DX: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_silent_track_adopts_on_a_bar_line() {
    printf("\n=== Test: a track with no note-ons adopts a new engine on a bar line ===\n");
    OrpheusEngine* engine = make_silent_engine();
    const int write_block = 40;
    std::vector<float> out_l;
    int note_ons = 0;
    const auto rec = run(engine, kMelodicTrack, write_block + kBarBlocks * 3, out_l, [&](int i, const Block& b) {
        if (b.pending_retrig) note_ons++;
        if (i == write_block)
            engine->pulsar_track_engine_edm[kMelodicTrack].store(kVowelFof, std::memory_order_relaxed);
    });
    const int adopt = first_block_on(rec, write_block + 1, kVowelFof);
    const bool on_bar = adopt >= 0 && rec[adopt].bar_pos == 0 && rec[adopt].boundary_at >= 0;
    const bool ok = note_ons == 0 && adopt > write_block && adopt - write_block <= 2 * kBarBlocks && on_bar;
    printf("  note-ons %d (want 0), write at block %d, engine %d at block %d (bar pos %d, boundary %d)\n",
           note_ons, write_block, kVowelFof, adopt, adopt >= 0 ? rec[adopt].bar_pos : -1,
           adopt >= 0 ? rec[adopt].boundary_at : -1);
    orpheus_engine_destroy(engine);
    printf("Silent track adopts: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The retune writes the raw pin (0 here); the DX force must follow the engine that sounds.
static bool pin_follows_engine(int from, int to) {
    OrpheusEngine* engine = make_pad_engine(from);
    engine->pulsar_track_pin_harmonics[kPadTrack].store(0, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[kPadTrack].store(0, std::memory_order_relaxed);
    const int write_block = kBarBlocks * 2 + 50;
    std::vector<float> out_l;
    const auto rec = run(engine, kPadTrack, write_block + kBarBlocks * 4, out_l, [&](int i, const Block&) {
        if (i == write_block) {
            engine->pulsar_track_engine_edm[kPadTrack].store(to, std::memory_order_relaxed);
            engine->pulsar_track_engine_space[kPadTrack].store(to, std::memory_order_relaxed);
        }
    });
    const int adopt = first_block_on(rec, write_block + 1, to);
    bool ok = adopt > write_block;
    int bad = 0, first_bad = -1;
    for (int i = 1; i < static_cast<int>(rec.size()); i++) {
        if (rec[i].pin_harmonics == is_dx(rec[i].active_engine)) continue;
        if (first_bad < 0) first_bad = i;
        bad++;
    }
    ok &= bad == 0;
    printf("  %d -> %d: write at block %d, adopted at block %d, pin off the engine on %d blocks"
           " (first %d)\n", from, to, write_block, adopt, bad, first_bad);
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_pin_follows_adopted_engine() {
    printf("\n=== Test: the DX harmonics pin flips with the adopted engine, not the port ===\n");
    bool ok = pin_follows_engine(kDx, kVa);
    ok &= pin_follows_engine(kVa, kDx);
    printf("Pin follows engine: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_vibe_load_snaps_latch() {
    printf("\n=== Test: a vibe load adopts the engine ports at once ===\n");
    // No note-ons, so only the load's snap can land the id on the first block.
    OrpheusEngine* engine = make_silent_engine();
    std::vector<float> out_l;
    run(engine, kMelodicTrack, 20, out_l);
    engine->pulsar_track_engine_edm[kMelodicTrack].store(kVowelFof, std::memory_order_relaxed);
    const auto rec = run(engine, kMelodicTrack, 1, out_l);
    const bool ok = rec[0].active_engine == kVowelFof && !rec[0].pending_retrig;
    printf("  engine after load %d (want %d), note-on %d (want 0)\n", rec[0].active_engine,
           kVowelFof, rec[0].pending_retrig ? 1 : 0);
    orpheus_engine_destroy(engine);
    printf("Load snaps: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mid_play_write_keeps_mirror() {
    printf("\n=== Test: a mid-play engine_edm write leaves the active-engine mirror alone ===\n");
    // Space slot (energy 0.2) on VOWEL_FOF; the pending write goes to the EDM slot.
    OrpheusEngine* engine = make_engine(kMelodicTrack, kVa, 0.2f);
    engine->pulsar_track_engine_space[kMelodicTrack].store(kVowelFof, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kMelodicTrack].store(0.5f, std::memory_order_relaxed);
    int write_block = -1, adopt = -1, bad = 0;
    auto mirror_ok = [&]() {
        return engine->pulsar_track_active_engine[kMelodicTrack].load() == kVowelFof
            && engine->pulsar_track_active_space_slot[kMelodicTrack].load() == 1;
    };
    std::vector<float> out_l;
    run(engine, kMelodicTrack, kBarBlocks * 3, out_l, [&](int i, const Block& b) {
        if (write_block < 0) {
            if (!b.pending_retrig) return;
            write_block = i;
            orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.pulsar",
                                    "track_4_engine_edm", static_cast<float>(kStr));
            if (!mirror_ok()) bad++;
            return;
        }
        if (adopt >= 0) return;
        if (!mirror_ok()) bad++;
        if (engine->pulsar_state->tracks[kMelodicTrack].live_engine_edm == kStr) adopt = i;
    });
    const bool ok = write_block >= 0 && adopt > write_block && bad == 0;
    printf("  write at block %d, adopted at block %d, mirror off the live engine on %d checks\n",
           write_block, adopt, bad);
    orpheus_engine_destroy(engine);
    printf("Mirror kept: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_swap_step_no_bigger_than_note_on() {
    printf("\n=== Test: the swap's largest sample step is no bigger than a note-on's ===\n");
    OrpheusEngine* engine = make_melodic_engine();
    std::vector<size_t> note_ons;
    int write_block = -1;
    std::vector<float> out_l;
    const auto rec = run(engine, kMelodicTrack, kBarBlocks * 8, out_l, [&](int i, const Block& b) {
        if (write_block >= 0 || !b.pending_retrig) return;
        note_ons.push_back(static_cast<size_t>(i) * kFrames + b.trigger_offset);
        if (note_ons.size() == 4) {
            engine->pulsar_track_engine_edm[kMelodicTrack].store(kVowelFof, std::memory_order_relaxed);
            write_block = i;
        }
    });
    bool ok = write_block >= 0;
    float baseline = 0.0f;
    for (size_t on : note_ons) {
        const float s = max_step(out_l, on, on + 256);
        printf("  note-on at sample %zu: max step %.5f\n", on, s);
        baseline = std::max(baseline, s);
    }
    const int adopt = ok ? first_block_on(rec, write_block + 1, kVowelFof) : -1;
    ok &= adopt >= 0;
    float swap = 0.0f;
    if (adopt >= 0) {
        // From the adoption block's start: the whole block renders on the new engine.
        const size_t start = static_cast<size_t>(adopt) * kFrames;
        const size_t on = start + rec[adopt].trigger_offset;
        swap = max_step(out_l, start, on + 256);
        printf("  swap adopted at block %d (note-on %d, offset %d): max step %.5f "
               "(block start to offset %.5f)\n",
               adopt, rec[adopt].pending_retrig ? 1 : 0, rec[adopt].trigger_offset, swap,
               max_step(out_l, start, on + 1));
    }
    ok &= swap <= 1.5f * baseline;
    printf("  baseline max %.5f, swap %.5f, ratio %.2f (want <= 1.5)\n", baseline, swap,
           baseline > 0.0f ? swap / baseline : 0.0f);
    orpheus_engine_destroy(engine);
    printf("Swap step: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_engine_swap_tests() {
    printf("\n=== Pulsar Engine Swap Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_swap_waits_for_note_on()) suite_pass++; else suite_fail++;
    if (test_held_pad_swaps_on_a_bar_line()) suite_pass++; else suite_fail++;
    if (test_held_pad_swaps_into_dx()) suite_pass++; else suite_fail++;
    if (test_silent_track_adopts_on_a_bar_line()) suite_pass++; else suite_fail++;
    if (test_pin_follows_adopted_engine()) suite_pass++; else suite_fail++;
    if (test_vibe_load_snaps_latch()) suite_pass++; else suite_fail++;
    if (test_mid_play_write_keeps_mirror()) suite_pass++; else suite_fail++;
    if (test_swap_step_no_bigger_than_note_on()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
