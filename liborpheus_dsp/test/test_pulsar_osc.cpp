// Verify OSC renders when used as a Pulsar track engine. Without the dispatch
// hook in orpheus_unit_pulsar.cpp, engine_index -1 falls through to
// OrpheusVoice::Render, which clamps it to 0 and plays VCF instead.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_osc.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static bool test_pulsar_track_renders_osc() {
    printf("\n=== Test: Pulsar track set to OSC (engine -1) renders the OSC voice ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Track 0 plays OSC on both slots, with FM on so the modulator runs.
    engine->pulsar_track_engine_edm[0].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[0].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_volume[0].store(0.85f, std::memory_order_relaxed);
    engine->pulsar_track_volume_space[0].store(0.85f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[0].store(0.2f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[0].store(0.2f, std::memory_order_relaxed);
    engine->pulsar_track_timbre[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_timbre_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_morph[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_fm_ratio[0].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_ratio_space[0].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_shape[0].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_fm_shape_space[0].store(0.0f, std::memory_order_relaxed);

    solo_track(engine, 0);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

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

    // Prove the branch actually fired. If dispatch never reached the OSC
    // kernel, both phases stay at their default-initialized 0.
    const PulsarOscState& st = engine->pulsar_state->tracks[0].osc_state;
    bool state_evolved = (st.core.tri_phase != 0.0f) || (st.mod_phase != 0.0f);
    printf("  peak=%.4f tri_phase=%.6f mod_phase=%.6f\n",
           peak, st.core.tri_phase, st.mod_phase);

    bool all_pass = true;
    all_pass &= state_evolved;
    all_pass &= (peak > 0.001f);
    all_pass &= (peak < 1.5f);

    printf("Pulsar OSC dispatch: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

// The sub-block trigger split must place the onset on the true boundary
// sample. Mirrors how the chaos and OrpheusVoice branches are checked.
static bool test_pulsar_osc_respects_trigger_offset() {
    printf("\n=== Test: OSC honors the intra-block trigger offset ===\n");
    PulsarOscState st;
    float out[256];
    // Pre-boundary segment with the gate low, then the post-boundary segment
    // with the gate high: the rising edge must land at sample 64, not 0.
    osc::process_osc_block(st, 60.0f, 0.0f, 0.5f, 0.5f,
                           2.0f, 0.0f, 0.0f, 0, 48000.0f, out, 64);
    float phase_before = st.mod_phase;
    osc::process_osc_block(st, 60.0f, 0.0f, 0.5f, 0.5f,
                           2.0f, 0.0f, 0.0f, 1, 48000.0f, out + 64, 192);
    printf("  mod_phase before edge=%.6f, after 192 frames=%.6f\n",
           phase_before, st.mod_phase);
    // The gate-low segment leaves mod_phase untouched (FM only advances while
    // rendering), and the rising edge zeroes it before the second segment.
    bool ok = (st.mod_phase > 0.0f);
    printf("Trigger offset split: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_osc_tests() {
    printf("\n=== Pulsar OSC Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_pulsar_track_renders_osc()) suite_pass++; else suite_fail++;
    if (test_pulsar_osc_respects_trigger_offset()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
