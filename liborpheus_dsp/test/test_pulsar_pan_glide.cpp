// Track pan glide: load_vibe snaps each track to its pan port, then a later write (a section
// pan override) is followed at kPanGlidePerSecond (0.15 per second) instead of jumping.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <cmath>
#include <cstdio>
#include <cstring>

static constexpr int kBlockFrames = 480;   // 100 blocks per second at 48 kHz
static constexpr int kTrack = 5;

static void render_seconds(GraphUnit* unit, OrpheusEngine* engine, float seconds) {
    const int blocks = static_cast<int>(seconds * 100.0f + 0.5f);
    for (int i = 0; i < blocks; i++) unit_process_pulsar(unit, engine, kBlockFrames, 48000.0f);
}

static bool test_pan_glides_then_snaps_on_load() {
    printf("\n=== Test: a pan write glides at 0.15/s; a vibe load snaps ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_track_pan[kTrack].store(0.5f, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    render_seconds(&unit, engine, 0.01f);
    const float loaded = engine->pulsar_state->tracks[kTrack].pan;

    engine->pulsar_track_pan[kTrack].store(-0.5f, std::memory_order_relaxed);
    render_seconds(&unit, engine, 1.0f);
    const float after_1s = engine->pulsar_state->tracks[kTrack].pan;
    render_seconds(&unit, engine, 6.0f);
    const float after_7s = engine->pulsar_state->tracks[kTrack].pan;

    engine->pulsar_track_pan[kTrack].store(0.8f, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    render_seconds(&unit, engine, 0.01f);
    const float reloaded = engine->pulsar_state->tracks[kTrack].pan;

    printf("  loaded %.3f, after 1 s %.3f, after 7 s %.3f, reloaded %.3f\n",
           loaded, after_1s, after_7s, reloaded);
    const bool ok = std::fabs(loaded - 0.5f) < 1e-6f
                 && std::fabs(after_1s - 0.35f) < 0.005f
                 && std::fabs(after_7s + 0.5f) < 1e-6f
                 && std::fabs(reloaded - 0.8f) < 1e-6f;
    printf("Pan glide: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_pan_glide_tests() {
    printf("\n=== Pulsar Pan Glide Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_pan_glides_then_snaps_on_load()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
