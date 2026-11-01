// Opt-in CPU and memory bench for the detuned double voice. Informational: name the suite on
// the command line. Release builds only give meaningful numbers.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_double.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace {

constexpr int kBlock = 256;
constexpr int kWarmBlocks = 2000;
constexpr int kTimedBlocks = 20000;
constexpr int kRuns = 3;
constexpr int kSoloTrack = 4;
constexpr float kRate = 48000.0f;
constexpr double kBudgetNs = kBlock / 48000.0 * 1e9;   // 5.33 ms
constexpr float kDetune = 12.0f;
constexpr double kMaxMixRatio = 2.1;   // mix cost budget for the double
volatile double g_sink = 0.0;            // keeps the timed loops from being optimised away

enum class Load { Single, OneTrack, AllTracks };

struct Timing {
    double ns = 0.0;     // per block
    double sum = 0.0;    // sum of |out| sampled once per timed block
};

Timing time_blocks(int engine_id, bool solo, Load load) {
    OrpheusEngine* engine = orpheus_engine_create(kRate);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    if (solo) {
        solo_track(engine, kSoloTrack);
        engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);
        {
            const int t = kSoloTrack;
            engine->pulsar_track_engine_edm[t].store(engine_id, std::memory_order_relaxed);
            engine->pulsar_track_engine_space[t].store(engine_id, std::memory_order_relaxed);
            engine->pulsar_track_density_override[t].store(1.0f, std::memory_order_relaxed);
            engine->pulsar_track_hold_probability[t].store(1.0f, std::memory_order_relaxed);
            engine->pulsar_track_hold_probability_space[t].store(1.0f, std::memory_order_relaxed);
            engine->pulsar_track_hold_length_min[t].store(8, std::memory_order_relaxed);
            engine->pulsar_track_hold_length_min_space[t].store(8, std::memory_order_relaxed);
            engine->pulsar_track_hold_length_max[t].store(8, std::memory_order_relaxed);
            engine->pulsar_track_hold_length_max_space[t].store(8, std::memory_order_relaxed);
        }
    }
    // Generative tracks are empty without a density override, in the fixture too.
    if (!solo) {
        for (int t = 0; t < kNumPulsarTracks; t++)
            engine->pulsar_track_density_override[t].store(1.0f, std::memory_order_relaxed);
    }
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const bool on = load == Load::AllTracks || (load == Load::OneTrack && t == kSoloTrack);
        const float d = on ? kDetune : 0.0f;
        engine->pulsar_track_double_detune[t].store(d, std::memory_order_relaxed);
        engine->pulsar_track_double_detune_space[t].store(d, std::memory_order_relaxed);
        engine->pulsar_track_double_spread[t].store(on ? 1.0f : 0.0f, std::memory_order_relaxed);
        engine->pulsar_track_double_spread_space[t].store(on ? 1.0f : 0.0f, std::memory_order_relaxed);
    }
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    for (int i = 0; i < kWarmBlocks; i++) unit_process_pulsar(&unit, engine, kBlock, kRate);
    const auto t0 = std::chrono::steady_clock::now();
    double sum = 0.0;
    for (int i = 0; i < kTimedBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlock, kRate);
        sum += std::fabs(engine->pulsar_out_l[i % kBlock]) + std::fabs(engine->pulsar_out_r[i % kBlock]);
    }
    const auto t1 = std::chrono::steady_clock::now();
    Timing r;
    r.sum = sum;
    g_sink = g_sink + r.sum;
    orpheus_engine_destroy(engine);
    r.ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / kTimedBlocks;
    return r;
}

Timing best_of(int engine_id, bool solo, Load load) {
    Timing best;
    best.ns = 1e30;
    for (int i = 0; i < kRuns; i++) {
        const Timing t = time_blocks(engine_id, solo, load);
        if (t.ns < best.ns) best = t;
    }
    return best;
}

// Every track renders its voice whatever its volume or 1.0f, so a silent-track baseline
// does not exist: the ratio is the whole mix, delta is the doubled tracks' added cost.
void row(const char* label, const Timing& single, const Timing& dbl, bool& ok) {
    const double ratio = dbl.ns / single.ns;
    const bool differs = dbl.sum != single.sum;
    ok &= ratio <= kMaxMixRatio && differs;
    printf("  %-22s %9.0f %9.0f %7.2f %8.2f %8.2f%% %s\n", label, single.ns, dbl.ns, ratio,
           (dbl.ns - single.ns) / 1000.0, 100.0 * dbl.ns / kBudgetNs,
           differs ? "" : "FAIL: double output identical to single");
}

}  // namespace

bool run_pulsar_double_bench() {
    printf("\n=== Pulsar double voice bench ===\n");
    if (!g_filtered_run) {
        printf("  skipped: name the suite on the command line\n");
        return true;
    }
#ifndef NDEBUG
    printf("  WARNING: not a Release build, numbers are not meaningful\n");
#endif
    printf("  %d-frame blocks at 48 kHz, budget %.0f ns per block, best of %d, detune %.0f\n",
           kBlock, kBudgetNs, kRuns, kDetune);
    printf("  %-22s %9s %9s %7s %8s %9s\n", "case", "single ns", "double ns", "ratio",
           "delta us", "double %");

    bool within = true;
    struct E { const char* name; int id; };
    for (const E& e : {E{"solo track 4, VA 8", 8}, E{"solo track 4, SPK 15", 15},
                       E{"solo track 4, Braids 100", 100}, E{"solo track 4, chaos 200", 200},
                       E{"solo track 4, OSC -1", -1}}) {
        row(e.name, best_of(e.id, true, Load::Single), best_of(e.id, true, Load::OneTrack), within);
    }
    const Timing base = best_of(0, false, Load::Single);
    row("fixture, 1 track", base, best_of(0, false, Load::OneTrack), within);
    row("fixture, 8 tracks", base, best_of(0, false, Load::AllTracks), within);

    const size_t added = (sizeof(PulsarTrackDouble) + kVoiceAllocBytes_Pulsar) * kNumPulsarTracks;
    const size_t before = sizeof(PulsarState) - sizeof(PulsarState::dbl)
                        - sizeof(PulsarState::double_alloc_buffers);
    printf("  sizeof(PulsarTrackDouble) = %zu\n  sizeof(PulsarState) before = %zu, after = %zu\n"
           "  added bytes = (%zu + %d) x %d = %zu\n",
           sizeof(PulsarTrackDouble), before, sizeof(PulsarState), sizeof(PulsarTrackDouble),
           kVoiceAllocBytes_Pulsar, kNumPulsarTracks, added);
    printf("Double bench (informational, mix ratios <= %.1f and outputs differ: %s)\n",
           kMaxMixRatio, within ? "yes" : "NO");
    return true;
}
