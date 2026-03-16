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

bool run_viz_tests() {
    printf("\n══════════════════════════════════════\n");
    printf("  SIGNAL VISUALIZATION TESTS\n");
    printf("══════════════════════════════════════\n");
    bool pass = true;
    pass &= test_viz_ring_basic();
    pass &= test_viz_ring_wraparound();
    pass &= test_viz_get_api();
    pass &= test_viz_get_api_lapping();
    pass &= test_viz_lfo_integration();
    printf("\nViz tests: %s\n", pass ? "ALL PASS" : "SOME FAILED");
    return pass;
}
