// Opt-in `texture_line` on a melodic lick line: it takes the texture-curve fade instead of
// the lick-line lift, and on track 7 the FX note gate no longer drops its note-ons.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

static constexpr int kBlockFrames = 512;
static constexpr int kBlocks = 400;   // about 4 s at 120 bpm, a few lick notes

struct TLRun {
    int active_blocks = 0;     // blocks that ended with the voice gated
    float volume = 0.0f;       // final track volume, last block
    bool unpushed_default = false;
};

// One fill lick on `track` at energy 0.5 / complexity 0.4, melodic, not soloing.
static TLRun run_line(int track, bool texture_line, float energy = 0.5f) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_energy.store(energy, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.4f, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    engine->pulsar_track_role[track].store(1, std::memory_order_relaxed);   // MELODIC
    engine->pulsar_track_volume[track].store(0.8f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[track].store(0.8f, std::memory_order_relaxed);

    const int8_t degrees[] = {0, 2, 4, 2};
    for (int i = 0; i < 4; i++) {
        engine->pulsar_lick[i].scale_degree = degrees[i];
        engine->pulsar_lick[i].duration = 1.0f;
        engine->pulsar_lick[i].velocity = 0.8f;
        engine->pulsar_lick[i].glide_rate = -1.0f;
    }
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    engine->pulsar_track_lick_mode[track].store(2, std::memory_order_relaxed);   // FILL
    engine->pulsar_track_texture_line[track].store(texture_line ? 1 : 0, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    TLRun r;
    for (int i = 0; i < kBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        if (engine->pulsar_state->tracks[track].voice_active) r.active_blocks++;
    }
    r.volume = engine->pulsar_track_volume_debug[track].load(std::memory_order_relaxed);
    orpheus_engine_destroy(engine);
    return r;
}

static bool test_tl_unpushed_default_off() {
    printf("\n=== Test: an unpushed texture_line is off on every track ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) ok &= (engine->pulsar_track_texture_line[t].load() == 0);
    printf("texture_line defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_tl_track7_gate() {
    printf("\n=== Test: texture_line keeps a track 7 lick line out of the FX note gate ===\n");
    const TLRun off = run_line(7, false);
    const TLRun on = run_line(7, true);
    printf("  gated blocks: flag off=%d, flag on=%d\n", off.active_blocks, on.active_blocks);
    const bool ok = (off.active_blocks == 0) && (on.active_blocks > 20);
    printf("Track 7 gate: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tl_track7_curve() {
    printf("\n=== Test: texture_line takes the texture curve, a plain lick line is lifted ===\n");
    // Compare volumes at one energy; the gate is open in both runs only when the flag is
    // on, so read the volume the engine computes each block (it does not need a note).
    const TLRun off = run_line(7, false);
    const TLRun on = run_line(7, true);
    const float curve = texture_energy_curve(0.5f);
    const float ratio = (off.volume > 0.0f) ? on.volume / off.volume : -1.0f;
    printf("  volume off=%.5f on=%.5f ratio=%.4f curve(0.5)=%.4f\n", off.volume, on.volume, ratio, curve);
    const bool ok = off.volume > 0.0f && std::fabs(ratio - curve) < 0.005f && curve < 0.1f;
    printf("Track 7 curve: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tl_tracks_5_and_6_take_the_curve() {
    printf("\n=== Test: rule 2 applies to tracks 5 and 6; the gate change only matters on 7 ===\n");
    bool ok = true;
    for (int t = 5; t <= 6; t++) {
        const TLRun off = run_line(t, false);
        const TLRun on = run_line(t, true);
        const float curve = texture_energy_curve(0.5f);
        const float ratio = (off.volume > 0.0f) ? on.volume / off.volume : -1.0f;
        printf("  track %d: ratio=%.4f curve=%.4f gated blocks off=%d on=%d\n",
               t, ratio, curve, off.active_blocks, on.active_blocks);
        ok &= off.volume > 0.0f && std::fabs(ratio - curve) < 0.005f;
        ok &= off.active_blocks > 20 && on.active_blocks > 20;   // no gate below track 7
    }
    printf("Tracks 5 and 6: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tl_flag_is_melodic_only() {
    printf("\n=== Test: texture_line on a chordal track changes nothing ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_track_role[5].store(2, std::memory_order_relaxed);   // CHORDAL
    engine->pulsar_track_texture_line[5].store(1, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    for (int i = 0; i < 4; i++) unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    const bool ok = !engine->pulsar_state->tracks[5].texture_line;
    printf("Melodic only: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_texture_line_tests() {
    printf("\n=== Pulsar Texture Line Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_tl_unpushed_default_off()) suite_pass++; else suite_fail++;
    if (test_tl_track7_gate()) suite_pass++; else suite_fail++;
    if (test_tl_track7_curve()) suite_pass++; else suite_fail++;
    if (test_tl_tracks_5_and_6_take_the_curve()) suite_pass++; else suite_fail++;
    if (test_tl_flag_is_melodic_only()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
