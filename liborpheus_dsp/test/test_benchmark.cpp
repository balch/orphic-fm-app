// CPU performance benchmark: engine vs graph path at various voice counts
#include "test_harness.h"

bool run_benchmark_tests() {
    printf("\n=== CPU Benchmark ===\n");
    const int sr = 48000;
    const int render_seconds = 10;
    const int total_frames = sr * render_seconds;

    auto benchmark = [&](const char* label, int num_voices, bool use_graph) -> double {
        OrpheusEngine* engine = orpheus_engine_create(sr);
        for (int v = 0; v < num_voices; v++) {
            orpheus_engine_set_voice_active(engine, v, 1);
            orpheus_engine_set_voice_tune(engine, v, 48.0f + v * 4);
            orpheus_engine_set_voice_gate(engine, v, 1);
            engine->voice_params[v].engine_index.store(v % 4);
            engine->voice_params[v].harmonics.store(0.5f);
            engine->voice_params[v].timbre.store(0.5f);
            engine->voice_params[v].morph.store(0.5f);
            engine->voice_params[v].decay.store(0.5f);
        }

        OrpheusGraph* graph = nullptr;
        if (use_graph) {
            graph = new OrpheusGraph();
            std::memset(graph, 0, sizeof(OrpheusGraph));
            graph->sample_rate = (float)sr;
            int n = std::min(num_voices, (int)kNumMainVoices);
            graph->unit_count = n + 2;
            graph->exec_count = n + 2;
            for (int i = 0; i < graph->unit_count; i++) graph->exec_order[i] = i;
            graph->master_out_index = n + 1;
            for (int v = 0; v < n; v++) {
                graph->units[v].type = UNIT_PLAITS; graph->units[v].id = v;
                graph->units[v].enabled = true;
                unit_init(&graph->units[v], (float)sr);
                graph->units[v].state.module.index = v;
            }
            int clip = n;
            graph->units[clip].type = UNIT_HARD_CLIP; graph->units[clip].id = clip;
            graph->units[clip].enabled = true; unit_init(&graph->units[clip], (float)sr);
            for (int v = 0; v < std::min(n, 4); v++)
                graph->units[clip].inputs[IPORT_INPUT].sources[v] = graph->units[v].output_buffers[OPORT_OUT];
            graph->units[clip].inputs[IPORT_INPUT].num_sources = std::min(n, 4);
            int mo = n + 1;
            graph->units[mo].type = UNIT_MASTER_OUT; graph->units[mo].id = mo;
            graph->units[mo].enabled = true; unit_init(&graph->units[mo], (float)sr);
            graph->units[mo].inputs[IPORT_INPUT_A].sources[0] = graph->units[clip].output_buffers[OPORT_OUT];
            graph->units[mo].inputs[IPORT_INPUT_A].num_sources = 1;
            graph->units[mo].inputs[IPORT_INPUT_B].sources[0] = graph->units[clip].output_buffers[OPORT_OUT];
            graph->units[mo].inputs[IPORT_INPUT_B].num_sources = 1;
        }

        float buf[256];
        auto start = std::chrono::high_resolution_clock::now();
        for (int off = 0; off < total_frames; off += 128) {
            int chunk = std::min(128, total_frames - off);
            if (use_graph)
                orpheus_graph_process(graph, engine, buf, chunk);
            else
                orpheus_engine_process(engine, buf, chunk);
        }
        auto end = std::chrono::high_resolution_clock::now();
        double ms = std::chrono::duration<double, std::milli>(end - start).count();
        double rt = (render_seconds * 1000.0) / ms;
        printf("  %-30s %7.1fms for %ds = %.1fx realtime\n", label, ms, render_seconds, rt);
        if (graph) delete graph;
        orpheus_engine_destroy(engine);
        return rt;
    };

    double r1 = benchmark("1 voice (engine)", 1, false);
    double r2 = benchmark("4 voices (engine)", 4, false);
    double r3 = benchmark("8 voices (engine)", 8, false);
    double r4 = benchmark("1 voice (graph)", 1, true);
    double r5 = benchmark("4 voices (graph)", 4, true);
    double r6 = benchmark("8 voices (graph)", 8, true);

    double lowest = std::min({r1, r2, r3, r4, r5, r6});
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(lowest > 2.0);
    printf("CPU benchmark: lowest=%.1fx — %s\n", lowest, suite_fail == 0 ? "PASS" : "FAIL");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
