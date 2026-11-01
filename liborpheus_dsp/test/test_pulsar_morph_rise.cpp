// Opt-in per-track morph rise: each note-on adds up to `morph_rise` to the track's morph over
// `morph_rise_time` seconds, then holds, and a note-on glides back over 80 ms (snaps on OSC). Read from the
// per-block morph debug publish, with morph pinned so nothing else moves it.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static constexpr int kBlockFrames = 512;
static constexpr int kRiseTrack = 4;     // melodic, no ducking
static constexpr int kRiseBlocks = 700;  // about 7.5 s at 48 kHz
static constexpr int kReturnBlocks = 8;  // 80 ms of 512-frame blocks, rounded up

struct MRiseSetup {
    float rise_edm = 0.0f;
    float rise_space = 0.0f;
    float time = 1.0f;
    float pinned = 0.2f;
    int engine_edm = 8;
    int engine_space = 8;
    float energy = 0.8f;
};

struct MRiseRun {
    std::vector<float> morph;
    std::vector<float> progress;   // morph_rise_progress after each block
};

static MRiseRun run_mrise(const MRiseSetup& s) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kRiseTrack);
    engine->pulsar_energy.store(s.energy, std::memory_order_relaxed);
    engine->pulsar_track_engine_edm[kRiseTrack].store(s.engine_edm, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kRiseTrack].store(s.engine_space, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kRiseTrack].store(0.5f, std::memory_order_relaxed);
    // Long holds: 8 steps is two seconds at 60 bpm.
    engine->pulsar_track_hold_probability[kRiseTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_probability_space[kRiseTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min_space[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max_space[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_morph[kRiseTrack].store(s.pinned, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[kRiseTrack].store(s.pinned, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph[kRiseTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph_space[kRiseTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_morph_rise[kRiseTrack].store(s.rise_edm, std::memory_order_relaxed);
    engine->pulsar_track_morph_rise_space[kRiseTrack].store(s.rise_space, std::memory_order_relaxed);
    engine->pulsar_track_morph_rise_time[kRiseTrack].store(s.time, std::memory_order_relaxed);
    engine->pulsar_track_morph_rise_time_space[kRiseTrack].store(s.time, std::memory_order_relaxed);
    engine->clock_bpm.store(60.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    MRiseRun r;
    for (int i = 0; i < kRiseBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[kRiseTrack];
        r.morph.push_back(engine->pulsar_track_mod_morph_debug[kRiseTrack].load(std::memory_order_relaxed));
        r.progress.push_back(ts.morph_rise_progress);
    }
    orpheus_engine_destroy(engine);
    return r;
}

// Blocks where the progress ramp was reset, i.e. a note-on landed. The rise is 1 s so it
// never wraps; a reset block is the one where progress dropped.
static std::vector<int> mrise_note_ons(const MRiseRun& r) {
    std::vector<int> on;
    for (size_t i = 1; i < r.progress.size(); i++)
        if (r.progress[i] < r.progress[i - 1]) on.push_back(static_cast<int>(i));
    return on;
}

// Start block and length of the longest gap between note-ons.
static bool longest_hold(const std::vector<int>& ons, int* start, int* length) {
    *start = -1;
    *length = 0;
    for (size_t i = 1; i < ons.size(); i++)
        if (ons[i] - ons[i - 1] > *length) { *length = ons[i] - ons[i - 1]; *start = ons[i - 1]; }
    return ons.size() >= 3 && *length >= 140;
}

static bool test_mrise_unpushed_defaults() {
    printf("\n=== Test: unpushed morph rise atomics are off with a 2 s time ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        ok &= (engine->pulsar_track_morph_rise[t].load() == 0.0f);
        ok &= (engine->pulsar_track_morph_rise_space[t].load() == 0.0f);
        ok &= (engine->pulsar_track_morph_rise_time[t].load() == 2.0f);
        ok &= (engine->pulsar_track_morph_rise_time_space[t].load() == 2.0f);
    }
    printf("Morph rise defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_mrise_zero_leaves_morph_alone() {
    printf("\n=== Test: rise 0 leaves a held note's morph at the pinned value ===\n");
    MRiseSetup a;
    const MRiseRun r = run_mrise(a);
    float max_progress = 0.0f, worst = 0.0f;
    for (float p : r.progress) max_progress = std::max(max_progress, p);
    for (float m : r.morph) worst = std::max(worst, std::fabs(m - 0.2f));
    printf("  max_progress=%.3f worst deviation=%.4f\n", max_progress, worst);
    const bool ok = max_progress == 0.0f && worst < 1e-4f;
    printf("Morph rise off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mrise_climbs_then_holds() {
    printf("\n=== Test: +0.4 over 1 s from a pinned 0.2 is 0.4 at 0.5 s, then holds at 0.6 ===\n");
    MRiseSetup on;
    on.rise_edm = on.rise_space = 0.4f;
    const MRiseRun rise = run_mrise(on);
    int start, length;
    bool ok = longest_hold(mrise_note_ons(rise), &start, &length);
    if (ok) {
        const float mid = rise.morph[start + 47];
        float lo = 9.0f, hi = -9.0f;
        for (int i = start + 95; i < start + length; i++) {
            lo = std::min(lo, rise.morph[i]);
            hi = std::max(hi, rise.morph[i]);
        }
        printf("  mid=%.4f top range %.4f..%.4f\n", mid, lo, hi);
        ok &= std::fabs(mid - 0.4f) < 0.01f && lo > 0.5995f && hi < 0.6005f;
    }
    printf("Morph rise climb: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mrise_negative_moves_down() {
    printf("\n=== Test: -0.3 over 1 s from a pinned 0.5 ends at 0.2 ===\n");
    MRiseSetup on;
    on.pinned = 0.5f;
    on.rise_edm = on.rise_space = -0.3f;
    const MRiseRun rise = run_mrise(on);
    int start, length;
    bool ok = longest_hold(mrise_note_ons(rise), &start, &length);
    if (ok) {
        const float mid = rise.morph[start + 47];
        const float end = rise.morph[start + length - 1];
        printf("  mid=%.4f end=%.4f\n", mid, end);
        ok &= std::fabs(mid - 0.35f) < 0.01f && std::fabs(end - 0.2f) < 0.0005f;
    }
    printf("Morph rise negative: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mrise_note_on_glides_back() {
    printf("\n=== Test: a note-on glides morph back onto the restarted rise instead of jumping ===\n");
    MRiseSetup on;
    on.rise_edm = on.rise_space = 0.4f;
    const MRiseRun rise = run_mrise(on);
    const std::vector<int> ons = mrise_note_ons(rise);
    float step = 0.0f, worst = 0.0f;
    for (size_t i = 1; i < rise.morph.size(); i++)
        step = std::max(step, std::fabs(rise.morph[i] - rise.morph[i - 1]));
    for (size_t k = 0; k < ons.size(); k++) {
        const int at = ons[k] + kReturnBlocks;
        if ((k + 1 < ons.size() && at >= ons[k + 1]) || at >= static_cast<int>(rise.morph.size())) continue;
        const float ramp = 0.2f + 0.4f * std::min(1.0f, (kReturnBlocks + 1) * kBlockFrames / 48000.0f);
        worst = std::max(worst, std::fabs(rise.morph[at] - ramp));
    }
    printf("  note-ons=%zu largest block step=%.4f worst deviation after the glide=%.4f\n",
           ons.size(), step, worst);
    const bool ok = ons.size() >= 3 && step < 0.07f && worst < 0.01f;
    printf("Morph rise return: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mrise_osc_note_on_snaps() {
    printf("\n=== Test: on OSC a note-on snaps a falling rise back to the pinned FM index ===\n");
    MRiseSetup on;
    on.engine_edm = on.engine_space = -1;  // OSC: morph is the FM index
    on.pinned = 0.5f;
    on.rise_edm = on.rise_space = -0.3f;
    on.time = 0.1f;  // done well inside every note, so each later note-on starts from 0.2
    const MRiseRun rise = run_mrise(on);
    const std::vector<int> ons = mrise_note_ons(rise);
    float worst = 0.0f;
    for (size_t k = 1; k < ons.size(); k++)
        worst = std::max(worst, std::fabs(rise.morph[ons[k]] - 0.5f));
    printf("  note-ons=%zu worst note-on deviation from 0.5=%.4f\n", ons.size(), worst);
    const bool ok = ons.size() >= 3 && worst < 1e-4f;
    printf("Morph rise OSC snap: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_morph_rise_tests() {
    printf("\n=== Pulsar Morph Rise Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_mrise_unpushed_defaults()) suite_pass++; else suite_fail++;
    if (test_mrise_zero_leaves_morph_alone()) suite_pass++; else suite_fail++;
    if (test_mrise_climbs_then_holds()) suite_pass++; else suite_fail++;
    if (test_mrise_negative_moves_down()) suite_pass++; else suite_fail++;
    if (test_mrise_note_on_glides_back()) suite_pass++; else suite_fail++;
    if (test_mrise_osc_note_on_snaps()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
