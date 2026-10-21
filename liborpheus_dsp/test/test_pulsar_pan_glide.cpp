// Track pan: load_vibe and every track_N_pan write snap; a track_N_pan_glide write (a section
// pan override) is followed at kPanGlidePerSecond (0.15 per second) instead of jumping.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <cmath>
#include <cstdio>
#include <cstring>

static constexpr int kBlockFrames = 480;   // 100 blocks per second at 48 kHz
static constexpr int kTrack = 5;
static constexpr const char* kPulsarUri = "org.balch.orpheus.plugins.pulsar";

static void render_seconds(GraphUnit* unit, OrpheusEngine* engine, float seconds) {
    const int blocks = static_cast<int>(seconds * 100.0f + 0.5f);
    for (int i = 0; i < blocks; i++) unit_process_pulsar(unit, engine, kBlockFrames, 48000.0f);
}

static void set_pan(OrpheusEngine* engine, const char* field, float value) {
    char sym[32];
    std::snprintf(sym, sizeof(sym), "track_%d_%s", kTrack, field);
    orpheus_engine_set_port(engine, kPulsarUri, sym, value);
}

static float pan_of(OrpheusEngine* engine) { return engine->pulsar_state->tracks[kTrack].pan; }

static OrpheusEngine* loaded_engine(GraphUnit& unit, float pan) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    set_pan(engine, "pan", pan);
    trigger_vibe_load(engine);
    render_seconds(&unit, engine, 0.01f);
    return engine;
}

static bool test_glide_port_glides_then_load_snaps() {
    printf("\n=== Test: a pan_glide write glides at 0.15/s; a vibe load snaps ===\n");
    GraphUnit unit;
    OrpheusEngine* engine = loaded_engine(unit, 0.5f);
    const float loaded = pan_of(engine);

    set_pan(engine, "pan_glide", -0.5f);
    render_seconds(&unit, engine, 1.0f);
    const float after_1s = pan_of(engine);
    render_seconds(&unit, engine, 6.0f);
    const float after_7s = pan_of(engine);

    set_pan(engine, "pan", 0.8f);
    trigger_vibe_load(engine);
    render_seconds(&unit, engine, 0.01f);
    const float reloaded = pan_of(engine);

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

// The bench pans through track_N_pan, so a write must be heard at once, mid-glide too, and
// even when the value equals the port's last one while a section glide pulled the track away.
static bool test_pan_write_lands_within_one_block() {
    printf("\n=== Test: a track_N_pan write lands within one block ===\n");
    GraphUnit unit;
    OrpheusEngine* engine = loaded_engine(unit, 0.0f);

    set_pan(engine, "pan", -0.6f);
    render_seconds(&unit, engine, 0.01f);
    const float snapped = pan_of(engine);

    set_pan(engine, "pan_glide", 0.6f);
    render_seconds(&unit, engine, 2.0f);
    const float mid_glide = pan_of(engine);
    set_pan(engine, "pan", -0.6f);         // the port already holds -0.6
    render_seconds(&unit, engine, 0.01f);
    const float same_value = pan_of(engine);
    render_seconds(&unit, engine, 1.0f);
    const float held = pan_of(engine);

    printf("  snapped %.3f, mid glide %.3f, same value %.3f, held %.3f\n",
           snapped, mid_glide, same_value, held);
    const bool ok = std::fabs(snapped + 0.6f) < 1e-6f
                 && mid_glide > -0.35f
                 && std::fabs(same_value + 0.6f) < 1e-6f
                 && std::fabs(held + 0.6f) < 1e-6f;
    printf("Pan snap: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_pan_glide_tests() {
    printf("\n=== Pulsar Pan Glide Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_glide_port_glides_then_load_snaps()) suite_pass++; else suite_fail++;
    if (test_pan_write_lands_within_one_block()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
