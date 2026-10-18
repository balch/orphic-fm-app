// Per-track audition gain (the DJ app's debug TRACKS view): scales one track's output,
// leaves the others bit-identical, and 1.0 is bit-identical to an engine that never set it.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

// Track 3 is always the bass bus and the baseline fixture puts no other track there,
// so the bass bus is track 3 alone and the drums + keys buses are everything else.
constexpr int kTrack = 3;
constexpr int kFrames = 256;
constexpr int kBlocks = 600;
constexpr int kSettle = 60;

struct Capture {
    std::vector<float> bass;    // track 3
    std::vector<float> others;  // drums + keys buses, every other track
};

// step_block >= 0 drops track 3's gain to 0 just before that block.
Capture run(bool set_gain, float gain, int step_block = -1) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.6f, std::memory_order_relaxed);
    if (set_gain) engine->pulsar_track_audition_gain[kTrack].store(gain, std::memory_order_relaxed);

    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    trigger_vibe_load(engine);

    Capture c;
    c.bass.reserve(kBlocks * kFrames);
    c.others.reserve(kBlocks * kFrames * 2);
    for (int b = 0; b < kBlocks; b++) {
        if (b == step_block) engine->pulsar_track_audition_gain[kTrack].store(0.0f, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, kFrames, 48000.0f);
        c.bass.insert(c.bass.end(), engine->pulsar_bus_bass_l, engine->pulsar_bus_bass_l + kFrames);
        c.others.insert(c.others.end(), engine->pulsar_bus_drums_l, engine->pulsar_bus_drums_l + kFrames);
        c.others.insert(c.others.end(), engine->pulsar_bus_keys_l, engine->pulsar_bus_keys_l + kFrames);
    }
    orpheus_engine_destroy(engine);
    return c;
}

float peak_after_settle(const std::vector<float>& v) {
    float p = 0.0f;
    for (size_t i = static_cast<size_t>(kSettle) * kFrames; i < v.size(); i++) p = std::max(p, std::fabs(v[i]));
    return p;
}

bool same_bits(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}

}  // namespace

static bool test_defaults_to_unity() {
    printf("\n=== Test: every track's audition gain defaults to 1.0 ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) ok &= engine->pulsar_track_audition_gain[t].load() == 1.0f;
    orpheus_engine_destroy(engine);
    printf("Defaults: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_port_routes() {
    printf("\n=== Test: track_N_audition_gain reaches the atomic ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        char sym[32];
        std::snprintf(sym, sizeof(sym), "track_%d_audition_gain", t);
        const float v = 0.1f * static_cast<float>(t + 1);
        orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.pulsar", sym, v);
        ok &= engine->pulsar_track_audition_gain[t].load() == v;
    }
    orpheus_engine_destroy(engine);
    printf("Routing: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_unity_is_bit_identical(const Capture& ref) {
    printf("\n=== Test: an explicit 1.0 is bit-identical to the default ===\n");
    const Capture got = run(true, 1.0f);
    const bool ok = same_bits(ref.bass, got.bass) && same_bits(ref.others, got.others);
    printf("Bit-identical: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_zero_silences_only_its_track(const Capture& ref) {
    printf("\n=== Test: 0 silences the track and leaves the others untouched ===\n");
    const Capture got = run(true, 0.0f);
    const float ref_peak = peak_after_settle(ref.bass);
    const float got_peak = peak_after_settle(got.bass);
    const bool others = same_bits(ref.others, got.others);
    const bool ok = ref_peak > 0.01f && got_peak == 0.0f && others;
    printf("  ref peak %.4f, gain-0 peak %.6f, others identical %d\n", ref_peak, got_peak, others);
    printf("Silenced: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_half_halves_the_peak(const Capture& ref) {
    printf("\n=== Test: 0.5 halves the track's peak, others untouched ===\n");
    const Capture got = run(true, 0.5f);
    const float ref_peak = peak_after_settle(ref.bass);
    const float got_peak = peak_after_settle(got.bass);
    const float ratio = ref_peak > 0.0f ? got_peak / ref_peak : 0.0f;
    const bool others = same_bits(ref.others, got.others);
    const bool ok = ref_peak > 0.01f && std::fabs(ratio - 0.5f) < 1e-4f && others;
    printf("  ref peak %.4f, half peak %.4f, ratio %.6f, others identical %d\n",
           ref_peak, got_peak, ratio, others);
    printf("Halved: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_step_ramps() {
    printf("\n=== Test: a 1 -> 0 step ramps over blocks instead of landing at once ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    trigger_vibe_load(engine);
    for (int b = 0; b < 10; b++) unit_process_pulsar(&unit, engine, 64, 48000.0f);
    engine->pulsar_track_audition_gain[kTrack].store(0.0f, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);
    const float first = engine->pulsar_state->audition_gain[kTrack];
    for (int b = 0; b < 200; b++) unit_process_pulsar(&unit, engine, 64, 48000.0f);
    const float settled = engine->pulsar_state->audition_gain[kTrack];
    const float other = engine->pulsar_state->audition_gain[kTrack + 1];
    orpheus_engine_destroy(engine);
    const bool ok = first > 0.0f && first < 1.0f && settled == 0.0f && other == 1.0f;
    printf("  after one block %.4f, settled %.4f, neighbour %.4f\n", first, settled, other);
    printf("Ramps: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Largest sample-to-sample change of track 3 over [from, to) blocks, counting the seam into `from`.
float max_jump(const std::vector<float>& v, int from, int to) {
    float m = 0.0f;
    for (size_t i = static_cast<size_t>(from) * kFrames; i < static_cast<size_t>(to) * kFrames; i++)
        if (i > 0) m = std::max(m, std::fabs(v[i] - v[i - 1]));
    return m;
}

static bool test_step_output_is_continuous(const Capture& ref) {
    printf("\n=== Test: a 1 -> 0 step on a sounding track has no jump at a block seam ===\n");
    // Step on the loudest block after the first bar, so the track is mid-note.
    int step = kSettle;
    float loudest = 0.0f;
    for (int b = kSettle; b < kBlocks - 40; b++) {
        const float p = std::fabs(ref.bass[static_cast<size_t>(b) * kFrames - 1]);
        if (p > loudest) { loudest = p; step = b; }
    }
    const Capture got = run(false, 1.0f, step);
    const float ref_jump = max_jump(ref.bass, step, step + 40);
    const float got_jump = max_jump(got.bass, step, step + 40);
    // A ramped gain adds at most level * per-sample gain slope (~0.0004 here) to the
    // signal's own jumps; a per-block step adds tens of percent of the level at one seam.
    const bool ok = loudest > 0.01f && got_jump <= ref_jump + 0.002f;
    printf("  step block %d, level at seam %.4f, ref max jump %.5f, step max jump %.5f\n",
           step, loudest, ref_jump, got_jump);
    printf("Continuous: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_first_block_snaps() {
    printf("\n=== Test: a gain set before the first block applies at once, no fade-out ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_track_audition_gain[kTrack].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_audition_gain[kTrack + 1].store(0.5f, std::memory_order_relaxed);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    unit_process_pulsar(&unit, engine, 64, 48000.0f);
    const float a = engine->pulsar_state->audition_gain[kTrack];
    const float b = engine->pulsar_state->audition_gain[kTrack + 1];
    orpheus_engine_destroy(engine);
    const bool ok = a == 0.0f && b == 0.5f;
    printf("  first block: %.4f, %.4f\n", a, b);
    printf("Snaps: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_audition_gain_tests() {
    printf("\n========== PULSAR AUDITION GAIN TESTS ==========\n");
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };
    tally(test_defaults_to_unity());
    tally(test_port_routes());
    const Capture ref = run(false, 1.0f);
    tally(test_unity_is_bit_identical(ref));
    tally(test_zero_silences_only_its_track(ref));
    tally(test_half_halves_the_peak(ref));
    tally(test_step_ramps());
    tally(test_step_output_is_continuous(ref));
    tally(test_first_block_snaps());
    printf("\nPulsar audition gain tests: %s\n", fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(pass, fail);
}
