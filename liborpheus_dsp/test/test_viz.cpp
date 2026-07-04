// Signal visualization ring buffer tests
#include "test_harness.h"
#include "orpheus_viz.h"

static constexpr int SR = 48000;

static bool test_viz_ring_basic() {
    printf("\n=== Test: VizRing basic write/read ===\n");
    VizRing ring;
    for (int i = 0; i < 10; i++) ring.write(i * 0.1f);

    float buf[480];
    int read_pos = 0;
    int count = 0;
    // Manual read (simulating get_viz logic)
    int wc = ring.write_count.load();
    int avail = wc - read_pos;
    bool pass = (avail == 10);
    for (int i = 0; i < avail; i++) {
        buf[i] = ring.buf[(read_pos + i) % VizRing::kVizBufSize];
    }
    // Verify values
    for (int i = 0; i < 10; i++) {
        if (std::fabs(buf[i] - i * 0.1f) > 0.001f) {
            printf("  FAIL at %d: expected %.3f got %.3f\n", i, i * 0.1f, buf[i]);
            pass = false;
        }
    }
    printf("VizRing basic: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_viz_ring_wraparound() {
    printf("\n=== Test: VizRing wraparound ===\n");
    VizRing ring;
    for (int i = 0; i < VizRing::kVizBufSize + 100; i++) ring.write(i * 0.01f);
    int wc = ring.write_count.load();
    bool pass = (wc == VizRing::kVizBufSize + 100);
    // Last written value should be at buf index (kVizBufSize+99) % kVizBufSize = 99
    float last = ring.buf[(VizRing::kVizBufSize + 99) % VizRing::kVizBufSize];
    float expected = (VizRing::kVizBufSize + 99) * 0.01f;
    if (std::fabs(last - expected) > 0.01f) {
        printf("  FAIL: last value expected %.2f got %.2f\n", expected, last);
        pass = false;
    }
    printf("VizRing wraparound: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_viz_get_api() {
    printf("\n=== Test: get_viz API ===\n");
    OrpheusEngine* engine = orpheus_engine_create(SR);
    for (int i = 0; i < 50; i++) engine->viz_rings[VIZ_LFO_OUTPUT].write(i * 0.02f);

    float buf[480];
    int read_pos = 0;
    int count = orpheus_engine_get_viz(engine, VIZ_LFO_OUTPUT, buf, 480, &read_pos);
    bool pass = (count == 50 && read_pos == 50);
    printf("  first read: count=%d read_pos=%d %s\n", count, read_pos, pass ? "OK" : "FAIL");

    // Second read — no new data
    int count2 = orpheus_engine_get_viz(engine, VIZ_LFO_OUTPUT, buf, 480, &read_pos);
    pass &= (count2 == 0);
    printf("  second read: count=%d (expect 0) %s\n", count2, count2 == 0 ? "OK" : "FAIL");

    // Write more, read again
    for (int i = 0; i < 20; i++) engine->viz_rings[VIZ_LFO_OUTPUT].write(1.0f);
    int count3 = orpheus_engine_get_viz(engine, VIZ_LFO_OUTPUT, buf, 480, &read_pos);
    pass &= (count3 == 20);
    printf("  third read: count=%d (expect 20) %s\n", count3, count3 == 20 ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    printf("get_viz API: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_viz_get_api_lapping() {
    printf("\n=== Test: get_viz lapping (writer outruns reader) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(SR);
    int read_pos = 0;

    // Write entire buffer + 100 more without reading
    for (int i = 0; i < VizRing::kVizBufSize + 100; i++)
        engine->viz_rings[VIZ_LFO_OUTPUT].write(i * 0.001f);

    float buf[480];
    int count = orpheus_engine_get_viz(engine, VIZ_LFO_OUTPUT, buf, 480, &read_pos);
    // Should return kVizBufSize - 1 (most recent samples, capped)
    bool pass = (count == VizRing::kVizBufSize - 1);
    printf("  lapped read: count=%d (expect %d) %s\n", count, VizRing::kVizBufSize - 1,
           pass ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    printf("get_viz lapping: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_viz_lfo_integration() {
    printf("\n=== Test: LFO viz integration ===\n");
    OrpheusEngine* engine = orpheus_engine_create(SR);
    if (!load_production_graph(engine)) return false;

    engine->lfo_freq_a.store(2.0f);
    engine->lfo_freq_b.store(3.0f);
    engine->lfo_mode.store(0); // AND
    engine->lfo_shape.store(1.0f); // triangle

    // Render 2 seconds
    float audio[512 * 2];
    for (int done = 0; done < 96000; ) {
        int block = std::min(512, 96000 - done);
        orpheus_engine_process(engine, audio, block);
        done += block;
    }

    float buf[480];
    int read_pos = 0;
    int count = orpheus_engine_get_viz(engine, VIZ_LFO_OUTPUT, buf, 480, &read_pos);
    printf("  LFO viz samples: %d (expect ~188 for 2sec at 94/sec)\n", count);

    bool pass = (count > 100);
    float peak = 0;
    for (int i = 0; i < count; i++) {
        float a = std::fabs(buf[i]);
        if (a > peak) peak = a;
        if (a > 1.01f) { pass = false; printf("  OUT OF RANGE at %d: %.3f\n", i, buf[i]); }
    }
    printf("  peak=%.3f (expect near 1.0 for AND triangle)\n", peak);
    if (peak < 0.3f) { pass = false; printf("  FAIL: peak too low\n"); }

    orpheus_engine_destroy(engine);
    printf("LFO viz integration: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// Regression: a 64-step vibe drives the pulsar playhead/step_count past the viz window
// (kPulsarVizSteps=32), but the consumer viz arrays — gates_out/velocities_out here, and the
// Kotlin stepGates/stepVelocities that mirror them — are only kPulsarVizSteps wide.
// orpheus_engine_get_pulsar_viz must clamp the exported playhead/step_count to that window,
// or PulsarStepGrid's `if (ph < stepCounts[t]) stepGates[t][ph]` indexes a 32-wide array at
// >=32 and crashes (ArrayIndexOutOfBoundsException on the viz frame callback).
static bool test_pulsar_viz_clamps_to_window() {
    printf("\n=== Test: pulsar viz clamps 64-step playhead/step_count to window ===\n");
    OrpheusEngine* engine = orpheus_engine_create(SR);
    bool pass = true;

    // Simulate the audio thread's written viz state for a mix of pattern lengths:
    //   t0: 16-step (normal), t1: 32-step at the edge, t2/t3: 64-step with the playhead
    //   already past the 32-step window (exactly the state a Corner Office loop reaches).
    auto& viz = engine->pulsar_viz;
    for (int t = 0; t < kNumPulsarTracks; t++) { viz.playheads[t] = 0; viz.step_counts[t] = 0; }
    viz.playheads[0] = 0;  viz.step_counts[0] = 16;
    viz.playheads[1] = 31; viz.step_counts[1] = 32;
    viz.playheads[2] = 40; viz.step_counts[2] = 64;
    viz.playheads[3] = 63; viz.step_counts[3] = 64;

    // Output buffers sized exactly as the real consumers (JNI/iOS/monitor) allocate them.
    int   gates_out[kNumPulsarTracks * kPulsarVizSteps];
    float vels_out[kNumPulsarTracks * kPulsarVizSteps];
    int   playheads_out[kNumPulsarTracks];
    int   step_counts_out[kNumPulsarTracks];
    orpheus_engine_get_pulsar_viz(engine, gates_out, vels_out, playheads_out, step_counts_out);

    for (int t = 0; t < kNumPulsarTracks; t++) {
        // Contract: nothing exported may exceed the viz window.
        if (step_counts_out[t] > kPulsarVizSteps) {
            printf("  FAIL t%d: step_count %d > window %d\n", t, step_counts_out[t], kPulsarVizSteps);
            pass = false;
        }
        if (playheads_out[t] >= kPulsarVizSteps) {
            printf("  FAIL t%d: playhead %d >= window %d\n", t, playheads_out[t], kPulsarVizSteps);
            pass = false;
        }
        // Crash-repro: mirror PulsarStepGrid's exact access. When the guard passes, the index
        // into a kPulsarVizSteps-wide array must be in bounds.
        int ph = playheads_out[t];
        if (ph < step_counts_out[t] && (ph < 0 || ph >= kPulsarVizSteps)) {
            printf("  FAIL t%d: consumer would index stepGates[%d] into a %d-wide array\n",
                   t, ph, kPulsarVizSteps);
            pass = false;
        }
    }

    // <=32 tracks must pass through unchanged (no over-clamping of the visible grid).
    if (playheads_out[0] != 0  || step_counts_out[0] != 16) { pass = false; printf("  FAIL: 16-step passthrough\n"); }
    if (playheads_out[1] != 31 || step_counts_out[1] != 32) { pass = false; printf("  FAIL: 32-step edge passthrough\n"); }
    printf("  t2 (in 40/64) -> playhead=%d step_count=%d\n", playheads_out[2], step_counts_out[2]);
    printf("  t3 (in 63/64) -> playhead=%d step_count=%d\n", playheads_out[3], step_counts_out[3]);

    orpheus_engine_destroy(engine);
    printf("pulsar viz clamp: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_viz_tests() {
    printf("\n══════════════════════════════════════\n");
    printf("  SIGNAL VISUALIZATION TESTS\n");
    printf("══════════════════════════════════════\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_viz_ring_basic());
    tally(test_viz_ring_wraparound());
    tally(test_viz_get_api());
    tally(test_viz_get_api_lapping());
    tally(test_viz_lfo_integration());
    tally(test_pulsar_viz_clamps_to_window());
    printf("\nViz tests: %s\n", suite_fail == 0 ? "ALL PASS" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
