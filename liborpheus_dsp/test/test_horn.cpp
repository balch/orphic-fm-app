// Horn (Leslie) DSP unit tests
#include "test_harness.h"

// ── Test 1: Self-bypass — mix=0 passes input through unchanged ──────────────
static bool test_horn_self_bypass() {
    printf("\n=== Test: Horn self-bypass (passthrough) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->horn_mix.store(0.0f);

    GraphUnit u = {};
    u.type = UNIT_HORN;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    const int num_frames = 128;
    // Fill input with non-zero sine
    for (int i = 0; i < num_frames; i++) {
        float t = (float)i / 48000.0f;
        float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
        u.inputs[IPORT_INPUT_A].buffer[i] = val;
        u.inputs[IPORT_INPUT_B].buffer[i] = val;
    }

    unit_process_horn(&u, engine, num_frames, 48000.0f);

    // Bypass should copy input to output (passthrough, not silence)
    // because horn is wired inline in the signal chain
    float max_diff = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float diff_l = std::fabs(u.output_buffers[OPORT_OUT][i] - u.inputs[IPORT_INPUT_A].buffer[i]);
        float diff_r = std::fabs(u.output_buffers[OPORT_OUT_RIGHT][i] - u.inputs[IPORT_INPUT_B].buffer[i]);
        if (diff_l > max_diff) max_diff = diff_l;
        if (diff_r > max_diff) max_diff = diff_r;
    }
    bool pass = max_diff < 0.0001f;

    printf("  mix=0 passthrough: max_diff=%.6f %s\n",
           max_diff, pass ? "OK (passthrough)" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 2: Non-zero output — effect modifies the signal ───────────────────
static bool test_horn_active_processing() {
    printf("\n=== Test: Horn active processing ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->horn_mix.store(0.5f);

    engine->horn_speed.store(0.5f);
    engine->horn_ratio.store(0.5f);
    engine->horn_depth.store(0.5f);
    engine->horn_brake.store(0);

    GraphUnit u = {};
    u.type = UNIT_HORN;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    const int test_frames = 4800;  // 100ms at 48kHz — enough to build delay history
    float out_rms = 0.0f;
    float in_rms  = 0.0f;
    float diff_rms = 0.0f;

    for (int offset = 0; offset < test_frames; offset += 128) {
        int chunk = std::min(128, test_frames - offset);
        for (int i = 0; i < chunk; i++) {
            float t = (float)(offset + i) / 48000.0f;
            float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
            u.inputs[IPORT_INPUT_A].buffer[i] = val;
            u.inputs[IPORT_INPUT_B].buffer[i] = val;
            in_rms += val * val;
        }

        unit_process_horn(&u, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            float ol = u.output_buffers[OPORT_OUT][i];
            float or_ = u.output_buffers[OPORT_OUT_RIGHT][i];
            out_rms += ol * ol + or_ * or_;
            // Compare output to input to verify the effect actually modifies the signal
            float dl = ol - u.inputs[IPORT_INPUT_A].buffer[i];
            float dr = or_ - u.inputs[IPORT_INPUT_B].buffer[i];
            diff_rms += dl * dl + dr * dr;
        }
    }

    in_rms   = std::sqrt(in_rms   / test_frames);
    out_rms  = std::sqrt(out_rms  / (test_frames * 2));
    diff_rms = std::sqrt(diff_rms / (test_frames * 2));

    // Effect must produce non-trivial output
    bool has_output = out_rms > 0.001f;
    // Output must differ from input (not just a passthrough)
    bool modifies_signal = diff_rms > 0.001f;
    bool pass = has_output && modifies_signal;

    printf("  mix=0.5 in_rms=%.4f out_rms=%.4f diff_rms=%.4f %s\n",
           in_rms, out_rms, diff_rms, pass ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 3: Phase export — viz rings written with non-zero phases ───────────
static bool test_horn_phase_export() {
    printf("\n=== Test: Horn phase export to viz rings ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->horn_mix.store(1.0f);

    engine->horn_speed.store(0.5f);
    engine->horn_ratio.store(0.5f);
    engine->horn_depth.store(0.5f);
    engine->horn_brake.store(0);

    GraphUnit u = {};
    u.type = UNIT_HORN;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    // Record write counts before processing
    uint32_t horn_wc_before   = engine->viz_rings[VIZ_HORN_PHASE].write_count.load(std::memory_order_relaxed);
    uint32_t woofer_wc_before = engine->viz_rings[VIZ_WOOFER_PHASE].write_count.load(std::memory_order_relaxed);

    // Process multiple blocks — rotor phases need time to ramp up from 0
    const int test_frames = 12000;  // 250ms — enough for speed inertia to build
    for (int offset = 0; offset < test_frames; offset += 128) {
        int chunk = std::min(128, test_frames - offset);
        for (int i = 0; i < chunk; i++) {
            float t = (float)(offset + i) / 48000.0f;
            float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
            u.inputs[IPORT_INPUT_A].buffer[i] = val;
            u.inputs[IPORT_INPUT_B].buffer[i] = val;
        }
        unit_process_horn(&u, engine, chunk, 48000.0f);
    }

    // Check write counts advanced (writes happened)
    uint32_t horn_wc_after   = engine->viz_rings[VIZ_HORN_PHASE].write_count.load(std::memory_order_relaxed);
    uint32_t woofer_wc_after = engine->viz_rings[VIZ_WOOFER_PHASE].write_count.load(std::memory_order_relaxed);

    bool writes_advanced = (horn_wc_after > horn_wc_before) && (woofer_wc_after > woofer_wc_before);

    // Read the most recently written phase values
    uint32_t horn_last_idx   = (horn_wc_after - 1) % VizRing::kVizBufSize;
    uint32_t woofer_last_idx = (woofer_wc_after - 1) % VizRing::kVizBufSize;
    float horn_phase_val   = engine->viz_rings[VIZ_HORN_PHASE].buf[horn_last_idx];
    float woofer_phase_val = engine->viz_rings[VIZ_WOOFER_PHASE].buf[woofer_last_idx];

    // After 250ms at ~0.75 Hz horn speed, horn phase should have advanced from 0
    bool horn_phase_nonzero   = horn_phase_val > 0.0f && horn_phase_val <= 1.0f;
    bool woofer_phase_nonzero = woofer_phase_val > 0.0f && woofer_phase_val <= 1.0f;

    bool pass = writes_advanced && horn_phase_nonzero && woofer_phase_nonzero;

    printf("  writes: horn %u->%u, woofer %u->%u  writes_ok=%s\n",
           horn_wc_before, horn_wc_after,
           woofer_wc_before, woofer_wc_after,
           writes_advanced ? "yes" : "no");
    printf("  horn_phase=%.4f woofer_phase=%.4f %s\n",
           horn_phase_val, woofer_phase_val, pass ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

bool run_horn_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_horn_self_bypass());
    tally(test_horn_active_processing());
    tally(test_horn_phase_export());
    printf("\nHorn tests: %s\n", suite_fail == 0 ? "PASS" : "FAIL");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
