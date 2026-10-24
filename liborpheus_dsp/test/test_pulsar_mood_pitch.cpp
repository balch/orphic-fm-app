// Per-track mood pitch: the mood macro bends the note handed to the engine by a lerped
// number of semitones. Twin engines differing only in the mood_pitch atomics make the
// per-block difference of pulsar_track_note_debug the bend.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static constexpr int kBlockFrames = 512;
static constexpr int kPitchTrack = 4;    // melodic, no ducking
static constexpr int kPitchBlocks = 300;

static std::vector<float> run_notes(float mood, float pitch_min, float pitch_max) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kPitchTrack);
    engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);
    engine->pulsar_mood.store(mood, std::memory_order_relaxed);
    engine->pulsar_track_engine_edm[kPitchTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kPitchTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kPitchTrack].store(0.5f, std::memory_order_relaxed);
    auto& m = engine->pulsar_track_macros[kPitchTrack];
    m.mood_pitch_min.store(pitch_min, std::memory_order_relaxed);
    m.mood_pitch_max.store(pitch_max, std::memory_order_relaxed);
    engine->clock_bpm.store(60.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    std::vector<float> notes;
    for (int i = 0; i < kPitchBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        notes.push_back(engine->pulsar_track_note_debug[kPitchTrack].load(std::memory_order_relaxed));
    }
    orpheus_engine_destroy(engine);
    return notes;
}

static float max_dev_from(const std::vector<float>& a, const std::vector<float>& b, float expect) {
    float worst = 0.0f;
    for (size_t i = 0; i < a.size(); i++) worst = std::max(worst, std::fabs((a[i] - b[i]) - expect));
    return worst;
}

static bool test_mood_pitch_defaults_off() {
    printf("\n=== Test: unpushed mood pitch atomics are 0..0 ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        ok &= (engine->pulsar_track_macros[t].mood_pitch_min.load() == 0.0f);
        ok &= (engine->pulsar_track_macros[t].mood_pitch_max.load() == 0.0f);
    }
    printf("Mood pitch defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_mood_pitch_routes() {
    printf("\n=== Test: mood pitch ports reach the atomics ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        char sym[64];
        snprintf(sym, sizeof(sym), "track_%d_macro_mood_pitch_min", t);
        orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.pulsar", sym, -3.0f - t);
        snprintf(sym, sizeof(sym), "track_%d_macro_mood_pitch_max", t);
        orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.pulsar", sym, 5.0f + t);
        ok &= (engine->pulsar_track_macros[t].mood_pitch_min.load() == -3.0f - t);
        ok &= (engine->pulsar_track_macros[t].mood_pitch_max.load() == 5.0f + t);
    }
    printf("Mood pitch routing: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_mood_pitch_zero_range_is_inert() {
    printf("\n=== Test: a 0..0 range leaves the note alone at any mood ===\n");
    const auto lo = run_notes(0.0f, 0.0f, 0.0f);
    const auto hi = run_notes(1.0f, 0.0f, 0.0f);
    const auto base = run_notes(1.0f, 0.0f, 0.0f);
    const float dev = max_dev_from(hi, base, 0.0f);
    printf("  deviation=%.6f (mood-0 run has %zu blocks)\n", dev, lo.size());
    bool ok = dev == 0.0f;
    printf("Mood pitch off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mood_pitch_ends_and_midpoint() {
    printf("\n=== Test: mood 0 gives the min bend, mood 1 the max, 0.5 the midpoint ===\n");
    const auto base0 = run_notes(0.0f, 0.0f, 0.0f);
    const auto base5 = run_notes(0.5f, 0.0f, 0.0f);
    const auto base1 = run_notes(1.0f, 0.0f, 0.0f);
    const float d0 = max_dev_from(run_notes(0.0f, -7.0f, 5.0f), base0, -7.0f);
    const float d5 = max_dev_from(run_notes(0.5f, -7.0f, 5.0f), base5, -1.0f);
    const float d1 = max_dev_from(run_notes(1.0f, -7.0f, 5.0f), base1, 5.0f);
    printf("  deviations from expected bend: mood0=%.5f mood.5=%.5f mood1=%.5f\n", d0, d5, d1);
    bool ok = d0 < 1e-3f && d5 < 1e-3f && d1 < 1e-3f;
    printf("Mood pitch lerp: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mood_pitch_is_clamped() {
    printf("\n=== Test: a bend past +-24 semitones is clamped ===\n");
    const auto base = run_notes(1.0f, 0.0f, 0.0f);
    const float dev = max_dev_from(run_notes(1.0f, 0.0f, 60.0f), base, 24.0f);
    printf("  deviation from +24=%.5f\n", dev);
    bool ok = dev < 1e-3f;
    printf("Mood pitch clamp: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_mood_pitch_tests() {
    printf("\n=== Pulsar Mood Pitch Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_mood_pitch_defaults_off()) suite_pass++; else suite_fail++;
    if (test_mood_pitch_routes()) suite_pass++; else suite_fail++;
    if (test_mood_pitch_zero_range_is_inert()) suite_pass++; else suite_fail++;
    if (test_mood_pitch_ends_and_midpoint()) suite_pass++; else suite_fail++;
    if (test_mood_pitch_is_clamped()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
