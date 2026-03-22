#include "../src/orpheus_engine.h"
#include "../src/orpheus_turntable.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static bool test_turntable_bypass_at_zero_mix() {
    printf("\n=== Test: Turntable bypass at zero mix ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(0.0f);
    engine->turntable_wet_b.store(0.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    for (int i = 0; i < 64; i++) u.output_buffers[OPORT_OUT][i] = 1.0f;

    unit_process_turntable(&u, engine, 64, 48000.0f);

    bool silent = true;
    for (int i = 0; i < 64; i++) {
        if (u.output_buffers[OPORT_OUT][i] != 0.0f) { silent = false; break; }
    }
    orpheus_engine_destroy(engine);
    printf("  Output silent: %s\n", silent ? "PASS" : "FAIL");
    return silent;
}

static bool test_turntable_captures_source() {
    printf("\n=== Test: Turntable captures source into buffer ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_source_a.store(TT_SOURCE_SYNTH);
    engine->turntable_crossfader.store(0.0f);

    for (int i = 0; i < 64; i++) {
        engine->warps_synth_read[i] = std::sin(2.0f * 3.14159f * 440.0f * i / 48000.0f);
    }

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    for (int b = 0; b < 10; b++) {
        unit_process_turntable(&u, engine, 64, 48000.0f);
    }

    bool has_data = false;
    for (int i = 0; i < 640; i++) {
        if (engine->turntable_decks[0].buffer[i] != 0.0f) { has_data = true; break; }
    }
    orpheus_engine_destroy(engine);
    printf("  Buffer has data: %s\n", has_data ? "PASS" : "FAIL");
    return has_data;
}

static bool test_turntable_freeze_stops_capture() {
    printf("\n=== Test: Freeze stops buffer capture ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_source_a.store(TT_SOURCE_SYNTH);
    engine->turntable_frozen_a.store(0);
    engine->turntable_crossfader.store(0.0f);

    for (int i = 0; i < 64; i++) engine->warps_synth_read[i] = 0.5f;

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    unit_process_turntable(&u, engine, 64, 48000.0f);
    int write_pos_before = engine->turntable_decks[0].write_pos;

    engine->turntable_frozen_a.store(1);

    for (int i = 0; i < 64; i++) engine->warps_synth_read[i] = -0.5f;
    unit_process_turntable(&u, engine, 64, 48000.0f);

    int write_pos_after = engine->turntable_decks[0].write_pos;
    bool frozen_ok = (write_pos_before == write_pos_after);

    orpheus_engine_destroy(engine);
    printf("  Write pos unchanged: %s\n", frozen_ok ? "PASS" : "FAIL");
    return frozen_ok;
}

static bool test_turntable_crossfader() {
    printf("\n=== Test: Crossfader blends decks ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    // Explicitly set both decks to the same source so the equal-gain
    // assumption (both use TT_SOURCE_SYNTH gain) is visible in the test.
    engine->turntable_source_a.store(TT_SOURCE_SYNTH);
    engine->turntable_source_b.store(TT_SOURCE_SYNTH);

    for (int i = 0; i < kTurntableBufSize; i++) {
        engine->turntable_decks[0].buffer[i] = 1.0f;
        engine->turntable_decks[1].buffer[i] = -1.0f;
    }
    engine->turntable_frozen_a.store(1);
    engine->turntable_frozen_b.store(1);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    engine->turntable_crossfader.store(0.5f);
    unit_process_turntable(&u, engine, 64, 48000.0f);

    float sum = 0.0f;
    for (int i = 0; i < 64; i++) sum += std::fabs(u.output_buffers[OPORT_OUT][i]);
    bool center_ok = (sum / 64.0f) < 0.1f;

    orpheus_engine_destroy(engine);
    printf("  Center crossfade cancels: %s (avg=%.4f)\n", center_ok ? "PASS" : "FAIL", sum / 64.0f);
    return center_ok;
}

static bool test_turntable_viz_snapshot() {
    printf("\n=== Test: Viz snapshot produces data ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    for (int i = 0; i < kTurntableBufSize; i++) {
        engine->turntable_decks[0].buffer[i] = static_cast<float>(i) / kTurntableBufSize;
    }
    engine->turntable_decks[0].read_pos = kTurntableBufSize / 4.0f;

    // Freeze deck A so buffer data is preserved, then process one block
    // to trigger turntable_update_viz which populates the snapshot.
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_frozen_a.store(1);
    engine->turntable_frozen_b.store(1);
    engine->turntable_crossfader.store(0.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    unit_process_turntable(&u, engine, 64, 48000.0f);

    float viz[kTurntableVizSize + 1];
    turntable_get_viz(&engine->turntable_decks[0], viz);

    bool has_waveform = false;
    for (int i = 0; i < kTurntableVizSize; i++) {
        if (viz[i] != 0.0f) { has_waveform = true; break; }
    }
    bool playhead_ok = std::fabs(viz[kTurntableVizSize] - 0.25f) < 0.01f;

    orpheus_engine_destroy(engine);
    printf("  Has waveform: %s\n", has_waveform ? "PASS" : "FAIL");
    printf("  Playhead pos: %s (%.3f)\n", playhead_ok ? "PASS" : "FAIL", viz[kTurntableVizSize]);
    return has_waveform && playhead_ok;
}

bool run_turntable_tests() {
    printf("\n========== TURNTABLE TESTS ==========\n");
    bool all_pass = true;
    all_pass &= test_turntable_bypass_at_zero_mix();
    all_pass &= test_turntable_captures_source();
    all_pass &= test_turntable_freeze_stops_capture();
    all_pass &= test_turntable_crossfader();
    all_pass &= test_turntable_viz_snapshot();
    printf("\nTurntable tests: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
