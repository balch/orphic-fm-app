// Verify chaos engines render correctly when used as a Pulsar track engine.
// Without the dispatch hook in orpheus_unit_pulsar.cpp, engine_index 200..204
// would silently fall through to plaits::Voice::Render which clamps to HiHat.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_unit_chaos.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static bool test_pulsar_chaos_renders_lorenz() {
    printf("\n=== Test: Pulsar track set to Lorenz (engine 200) produces non-silent, non-HiHat output ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    // Start from a working pulsar setup (cosmic techno) so all the upstream
    // sequencer state (densities, scale, root, etc.) is sane.
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);

    // Configure track 0 to play Lorenz (engine 200) on both EDM and Space slots.
    // Override what cosmic_techno set for track 0.
    constexpr int kChaosLorenz = 200;
    engine->pulsar_track_engine_edm[0].store(kChaosLorenz, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[0].store(kChaosLorenz, std::memory_order_relaxed);
    engine->pulsar_track_volume[0].store(0.85f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.85f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_timbre[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_timbre_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_morph[0].store(1.0f, std::memory_order_relaxed);   // pure chaos
    engine->pulsar_track_morph_space[0].store(1.0f, std::memory_order_relaxed);

    // Solo track 0 so we measure chaos output specifically (not muddied by
    // the seven other cosmic-techno tracks).
    solo_track(engine, 0);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

    // Process ~2 seconds of audio so the sequencer has time to fire gates on
    // track 0 and the chaos kernel actually runs.
    float peak = 0.0f;
    for (int i = 0; i < 200; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        for (int s = 0; s < 512; s++) {
            float al = std::fabs(engine->pulsar_out_l[s]);
            float ar = std::fabs(engine->pulsar_out_r[s]);
            if (al > peak) peak = al;
            if (ar > peak) peak = ar;
        }
    }

    // Inspect the chaos trajectory on track 0 — if our dispatch hook fired
    // the state should have evolved away from the canonical seed (x=0.1).
    // If the chaos branch never ran, the state stays exactly at the
    // default-initialized values.
    float chaos_x = engine->pulsar_state->tracks[0].chaos_state.x;
    float chaos_y = engine->pulsar_state->tracks[0].chaos_state.y;
    float chaos_z = engine->pulsar_state->tracks[0].chaos_state.z;
    bool state_evolved = std::fabs(chaos_x - 0.1f) > 1e-4f
                      || std::fabs(chaos_y) > 1e-4f
                      || std::fabs(chaos_z) > 1e-4f;

    bool ok = true;
    if (peak <= 0.001f) {
        printf("  FAIL: peak amplitude = %.6f (expected > 0.001)\n", peak);
        ok = false;
    }
    if (peak >= 1.5f) {
        printf("  FAIL: peak amplitude = %.6f (expected < 1.5 — soft_limit bound)\n", peak);
        ok = false;
    }
    if (!state_evolved) {
        printf("  FAIL: chaos_state did not evolve (x=%.6f y=%.6f z=%.6f — still at seed)\n",
               chaos_x, chaos_y, chaos_z);
        ok = false;
    }
    if (ok) {
        printf("  PASS: peak=%.4f, chaos_state=(x=%.4f, y=%.4f, z=%.4f)\n",
               peak, chaos_x, chaos_y, chaos_z);
    }

    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_chaos_tests() {
    printf("\n=== Pulsar Chaos Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_pulsar_chaos_renders_lorenz()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
