// Graph hot-swap safety.
//
// orpheus_engine_load_patch swaps the graph pointer atomically, but the
// render thread loads that pointer once per process call and uses it for
// the whole block. Freeing the old graph immediately after the swap is a
// use-after-free against any in-flight block. The fix: wait for one
// blocks_rendered epoch advance (with timeout for the audio-idle case)
// before freeing.
#include "test_harness.h"
#include <atomic>
#include <cmath>
#include <thread>

bool run_graph_swap_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok, const char* name) {
        if (ok) { ++suite_pass; }
        else    { ++suite_fail; printf("  FAIL: %s\n", name); }
    };

    // ── blocks_rendered advances once per process call ──
    {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        float buf[512 * 2];
        uint64_t e0 = engine->blocks_rendered.load();
        orpheus_engine_process(engine, buf, 512);
        orpheus_engine_process(engine, buf, 512);
        tally(engine->blocks_rendered.load() == e0 + 2,
              "blocks_rendered increments per process call");
        orpheus_engine_destroy(engine);
    }

    // ── load_patch with no audio running returns promptly ──
    {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        tally(load_production_graph(engine), "initial load (idle)");
        auto t0 = std::chrono::steady_clock::now();
        tally(load_production_graph(engine), "reload (idle) succeeds");
        float ms = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - t0).count();
        // Idle: epoch never advances, so the retire path must hit its
        // timeout and free anyway — but within ~200ms, not hang forever.
        tally(ms < 500.0f, "idle reload completes within timeout budget");
        orpheus_engine_destroy(engine);
    }

    // ── hot swap while a render thread hammers process() ──
    {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        tally(load_production_graph(engine), "initial load (live)");
        std::atomic<bool> stop_flag{false};
        std::atomic<bool> nonfinite{false};
        std::thread audio([&] {
            float buf[512 * 2];
            while (!stop_flag.load(std::memory_order_relaxed)) {
                orpheus_engine_process(engine, buf, 512);
                for (int i = 0; i < 512 * 2; i++) {
                    if (!std::isfinite(buf[i])) { nonfinite.store(true); break; }
                }
            }
        });
        bool loads_ok = true;
        for (int i = 0; i < 20 && loads_ok; i++) {
            loads_ok = load_production_graph(engine);
        }
        stop_flag.store(true);
        audio.join();
        tally(loads_ok, "20 hot swaps under live render");
        tally(!nonfinite.load(), "output stays finite through swaps");
        orpheus_engine_destroy(engine);
    }

    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
