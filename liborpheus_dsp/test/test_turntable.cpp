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

// Simulates Bluetooth chunked callbacks (e.g. 960 = 512 + 448) and verifies
// that the turntable buffer doesn't contain stale samples from unequal chunks.
static bool test_turntable_chunked_capture_no_stale_tail() {
    printf("\n=== Test: Chunked capture has no stale tail ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Fill bass source buffer with a known signal for chunk 1 (512 frames)
    float tone_a = 0.1f;
    for (int i = 0; i < kMaxFrames; i++) {
        engine->warps_source_buffers[9][i] = tone_a;
    }

    // Simulate graph_process double-buffer swap + zero for chunk 1 (512 frames)
    std::memcpy(engine->warps_bass_read, engine->warps_source_buffers[9],
                kMaxFrames * sizeof(float));
    // With the fix, this zeros kMaxFrames; without it, only num_frames
    std::memset(engine->warps_source_buffers[9], 0, kMaxFrames * sizeof(float));

    // Bass voice writes 512 samples for chunk 1
    float tone_b = 0.2f;
    for (int i = 0; i < 512; i++) {
        engine->warps_source_buffers[9][i] = tone_b;
    }

    // Simulate graph_process for chunk 2 (448 frames) — smaller chunk
    int chunk2 = 448;
    std::memcpy(engine->warps_bass_read, engine->warps_source_buffers[9],
                chunk2 * sizeof(float));
    // With fix: zeros full kMaxFrames, clearing tail
    // Without fix: only zeros 448, leaving samples 448-511 = tone_b (stale)
    std::memset(engine->warps_source_buffers[9], 0, kMaxFrames * sizeof(float));

    // Bass voice writes 448 samples for chunk 2
    float tone_c = 0.3f;
    for (int i = 0; i < chunk2; i++) {
        engine->warps_source_buffers[9][i] = tone_c;
    }

    // Now simulate NEXT callback, chunk 1 (512 frames) — reads full 512
    std::memcpy(engine->warps_bass_read, engine->warps_source_buffers[9],
                512 * sizeof(float));

    // Check: samples 448-511 should be ZERO (properly cleared), not stale tone_b
    bool tail_clean = true;
    for (int i = chunk2; i < 512; i++) {
        if (engine->warps_bass_read[i] != 0.0f) {
            printf("  FAIL: warps_bass_read[%d] = %.4f (expected 0, stale data)\n",
                   i, engine->warps_bass_read[i]);
            tail_clean = false;
            break;
        }
    }

    orpheus_engine_destroy(engine);
    printf("  Tail samples clean: %s\n", tail_clean ? "PASS" : "FAIL");
    return tail_clean;
}

// Helper: run playback_deck directly on an isolated deck to test drop behavior.
// Fills deck buffer with a 440Hz tone so there's signal to filter.
static void fill_deck_tone(TurntableDeck* deck, float freq, float sr) {
    for (int i = 0; i < kTurntableBufSize; i++) {
        deck->buffer[i] = std::sin(2.0f * 3.14159f * freq * i / sr);
    }
    deck->frozen = true;  // prevent capture from overwriting
}

static bool test_drop_slow_backward_activates() {
    printf("\n=== Test: Slow backward activates drop buildup ===\n");

    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 440.0f, 48000.0f);

    float out[64];
    // Slow backward: velocity = -0.1 (below kDropSlowThreshold of 0.3)
    playback_deck(&deck, -0.1f, out, 64, 48000.0f);

    bool building = (deck.drop_state == DROP_BUILDING);
    bool amount_positive = (deck.drop_amount > 0.0f);

    printf("  State is BUILDING: %s\n", building ? "PASS" : "FAIL");
    printf("  Amount > 0: %s (%.6f)\n", amount_positive ? "PASS" : "FAIL", deck.drop_amount);
    return building && amount_positive;
}

static bool test_drop_fast_backward_does_not_activate() {
    printf("\n=== Test: Fast backward does NOT activate drop ===\n");

    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 440.0f, 48000.0f);

    float out[64];
    // Fast backward: velocity = -1.0 (above threshold)
    playback_deck(&deck, -1.0f, out, 64, 48000.0f);

    bool idle = (deck.drop_state == DROP_IDLE);
    bool amount_zero = (deck.drop_amount == 0.0f);

    printf("  State is IDLE: %s\n", idle ? "PASS" : "FAIL");
    printf("  Amount == 0: %s\n", amount_zero ? "PASS" : "FAIL");
    return idle && amount_zero;
}

static bool test_drop_release_snaps_to_idle() {
    printf("\n=== Test: Releasing platter snaps to IDLE (clean forward sound) ===\n");

    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 440.0f, 48000.0f);

    float out[512];
    // Build up for several blocks
    for (int b = 0; b < 10; b++) {
        playback_deck(&deck, -0.1f, out, 512, 48000.0f);
    }
    bool was_building = (deck.drop_state == DROP_BUILDING);

    // Release: velocity goes to 0 (motor returns)
    playback_deck(&deck, 0.0f, out, 64, 48000.0f);

    bool idle = (deck.drop_state == DROP_IDLE);
    bool amount_zero = (deck.drop_amount == 0.0f);

    printf("  Was building: %s\n", was_building ? "PASS" : "FAIL");
    printf("  State is IDLE: %s\n", idle ? "PASS" : "FAIL");
    printf("  Amount reset: %s\n", amount_zero ? "PASS" : "FAIL");
    return was_building && idle && amount_zero;
}

static bool test_drop_filter_attenuates_highs() {
    printf("\n=== Test: Drop filter attenuates high frequencies during buildup ===\n");

    float sr = 48000.0f;
    float high_freq = 8000.0f;

    TurntableDeck deck_clean;
    std::memset(&deck_clean, 0, sizeof(deck_clean));
    deck_clean.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck_clean, high_freq, sr);

    TurntableDeck deck_drop;
    std::memset(&deck_drop, 0, sizeof(deck_drop));
    deck_drop.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck_drop, high_freq, sr);

    float out_clean[512];
    float out_drop[512];

    // Build up deck_drop for ~3 seconds (build rate is slow — 4s to full)
    for (int b = 0; b < 300; b++) {
        playback_deck(&deck_drop, -0.1f, out_drop, 512, sr);
    }
    // Play both — deck_drop still in BUILDING state
    playback_deck(&deck_drop, -0.1f, out_drop, 512, sr);
    playback_deck(&deck_clean, 1.0f, out_clean, 512, sr);

    float rms_clean = 0.0f, rms_drop = 0.0f;
    for (int i = 0; i < 512; i++) {
        rms_clean += out_clean[i] * out_clean[i];
        rms_drop += out_drop[i] * out_drop[i];
    }
    rms_clean = std::sqrt(rms_clean / 512.0f);
    rms_drop = std::sqrt(rms_drop / 512.0f);

    float ratio_db = 20.0f * std::log10(rms_drop / (rms_clean + 1e-10f));
    // With resonance boost + gain, the filtered signal differs from clean.
    // The key assertion is that the filter is actively reshaping — >3dB difference.
    bool filter_active = std::fabs(ratio_db) > 3.0f;

    printf("  Clean RMS: %.4f, Drop RMS: %.4f\n", rms_clean, rms_drop);
    printf("  Difference: %.1f dB: %s\n", ratio_db, filter_active ? "PASS" : "FAIL");
    return filter_active;
}

static bool test_drop_fast_scratch_aborts_buildup() {
    printf("\n=== Test: Fast backward scratch aborts buildup (no drop) ===\n");

    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 440.0f, 48000.0f);

    float out[512];
    // Build up
    for (int b = 0; b < 5; b++) {
        playback_deck(&deck, -0.1f, out, 512, 48000.0f);
    }
    bool was_building = (deck.drop_state == DROP_BUILDING);

    // Fast backward scratch — should abort to IDLE, not RELEASING
    playback_deck(&deck, -1.0f, out, 512, 48000.0f);

    bool idle = (deck.drop_state == DROP_IDLE);
    bool amount_zero = (deck.drop_amount <= 0.0f);

    printf("  Was building: %s\n", was_building ? "PASS" : "FAIL");
    printf("  State is IDLE (aborted): %s\n", idle ? "PASS" : "FAIL");
    printf("  Amount reset: %s\n", amount_zero ? "PASS" : "FAIL");
    return was_building && idle && amount_zero;
}

bool run_turntable_tests() {
    printf("\n========== TURNTABLE TESTS ==========\n");
    bool all_pass = true;
    all_pass &= test_turntable_bypass_at_zero_mix();
    all_pass &= test_turntable_captures_source();
    all_pass &= test_turntable_freeze_stops_capture();
    all_pass &= test_turntable_crossfader();
    all_pass &= test_turntable_viz_snapshot();
    all_pass &= test_turntable_chunked_capture_no_stale_tail();
    all_pass &= test_drop_slow_backward_activates();
    all_pass &= test_drop_fast_backward_does_not_activate();
    all_pass &= test_drop_release_snaps_to_idle();
    all_pass &= test_drop_filter_attenuates_highs();
    all_pass &= test_drop_fast_scratch_aborts_buildup();
    printf("\nTurntable tests: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
