// Graph integration tests: manually-built mini-graphs through orpheus_graph_process
#include "test_harness.h"

static bool test_graph_single_voice() {
    printf("\n=== Test: Graph — single voice ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.5f);

    OrpheusGraph* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = 48000.0f;
    graph->unit_count = 3;
    graph->exec_count = 3;
    graph->exec_order[0] = 0; graph->exec_order[1] = 1; graph->exec_order[2] = 2;
    graph->master_out_index = 2;

    graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
    graph->units[0].enabled = true;
    unit_init(&graph->units[0], 48000.0f);
    graph->units[0].state.module.index = 0; // AFTER init (memset zeroes state)

    graph->units[1].type = UNIT_HARD_CLIP; graph->units[1].id = 1;
    graph->units[1].enabled = true; unit_init(&graph->units[1], 48000.0f);
    graph->units[1].inputs[IPORT_INPUT].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT].num_sources = 1;

    graph->units[2].type = UNIT_MASTER_OUT; graph->units[2].id = 2;
    graph->units[2].enabled = true; unit_init(&graph->units[2], 48000.0f);
    graph->units[2].inputs[IPORT_INPUT_A].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[2].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

    float stereo[256];
    float max_amp = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        std::memset(stereo, 0, sizeof(stereo));
        orpheus_graph_process(graph, engine, stereo, chunk);
        for (int i = 0; i < chunk * 2; i++) {
            float a = std::fabs(stereo[i]);
            if (a > max_amp) max_amp = a;
        }
    }
    printf("  Graph single voice: peak=%.4f %s\n", max_amp, max_amp > 0.001f ? "OK" : "FAIL");
    bool pass = max_amp > 0.001f;
    printf("Graph single voice test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_graph_polyphonic() {
    printf("\n=== Test: Graph — 4-voice polyphonic ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f};
    for (int v = 0; v < 4; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(0);
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.5f);
    }

    OrpheusGraph* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = 48000.0f;
    graph->unit_count = 6;
    graph->exec_count = 6;
    for (int i = 0; i < 6; i++) graph->exec_order[i] = i;
    graph->master_out_index = 5;

    for (int v = 0; v < 4; v++) {
        graph->units[v].type = UNIT_PLAITS; graph->units[v].id = v;
        graph->units[v].enabled = true;
        unit_init(&graph->units[v], 48000.0f);
        graph->units[v].state.module.index = v; // AFTER init
    }

    graph->units[4].type = UNIT_HARD_CLIP; graph->units[4].id = 4;
    graph->units[4].enabled = true; unit_init(&graph->units[4], 48000.0f);
    for (int v = 0; v < 4; v++)
        graph->units[4].inputs[IPORT_INPUT].sources[v] = graph->units[v].output_buffers[OPORT_OUT];
    graph->units[4].inputs[IPORT_INPUT].num_sources = 4;

    graph->units[5].type = UNIT_MASTER_OUT; graph->units[5].id = 5;
    graph->units[5].enabled = true; unit_init(&graph->units[5], 48000.0f);
    graph->units[5].inputs[IPORT_INPUT_A].sources[0] = graph->units[4].output_buffers[OPORT_OUT];
    graph->units[5].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[5].inputs[IPORT_INPUT_B].sources[0] = graph->units[4].output_buffers[OPORT_OUT];
    graph->units[5].inputs[IPORT_INPUT_B].num_sources = 1;

    float stereo[256];
    float max_amp = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        std::memset(stereo, 0, sizeof(stereo));
        orpheus_graph_process(graph, engine, stereo, chunk);
        for (int i = 0; i < chunk * 2; i++) {
            float a = std::fabs(stereo[i]);
            if (a > max_amp) max_amp = a;
        }
    }
    int producing = 0;
    for (int v = 0; v < 4; v++) {
        float level = engine->voice_levels[v].load(std::memory_order_relaxed);
        if (level > 0.001f) producing++;
        printf("  Voice %d (note %.0f): level=%.4f %s\n", v, notes[v], level, level > 0.001f ? "OK" : "SILENT!");
    }
    printf("  Mix peak: %.4f, %d/4 voices\n", max_amp, producing);
    bool pass = max_amp > 0.001f && producing >= 4;
    printf("Graph polyphonic test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_graph_effects_chain() {
    printf("\n=== Test: Graph — effects chain (bypass) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.5f);
    engine->clouds_bypass.store(1);
    engine->delay_bypass.store(1);

    OrpheusGraph* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = 48000.0f;
    graph->unit_count = 5;
    graph->exec_count = 5;
    for (int i = 0; i < 5; i++) graph->exec_order[i] = i;
    graph->master_out_index = 4;

    // voice → clouds(bypass) → delay(bypass) → clip → master
    graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
    graph->units[0].enabled = true;
    unit_init(&graph->units[0], 48000.0f);
    graph->units[0].state.module.index = 0;

    graph->units[1].type = UNIT_CLOUDS; graph->units[1].id = 1;
    graph->units[1].enabled = true; unit_init(&graph->units[1], 48000.0f);
    graph->units[1].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[1].inputs[IPORT_INPUT_B].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
    graph->units[1].inputs[IPORT_INPUT_B].num_sources = 1;

    graph->units[2].type = UNIT_DUAL_DELAY; graph->units[2].id = 2;
    graph->units[2].enabled = true; unit_init(&graph->units[2], 48000.0f);
    graph->units[2].inputs[IPORT_INPUT_A].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[2].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT_RIGHT];
    graph->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

    graph->units[3].type = UNIT_HARD_CLIP; graph->units[3].id = 3;
    graph->units[3].enabled = true; unit_init(&graph->units[3], 48000.0f);
    graph->units[3].inputs[IPORT_INPUT].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
    graph->units[3].inputs[IPORT_INPUT].num_sources = 1;

    graph->units[4].type = UNIT_MASTER_OUT; graph->units[4].id = 4;
    graph->units[4].enabled = true; unit_init(&graph->units[4], 48000.0f);
    graph->units[4].inputs[IPORT_INPUT_A].sources[0] = graph->units[3].output_buffers[OPORT_OUT];
    graph->units[4].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[4].inputs[IPORT_INPUT_B].sources[0] = graph->units[2].output_buffers[OPORT_OUT_RIGHT];
    graph->units[4].inputs[IPORT_INPUT_B].num_sources = 1;

    float stereo[256];
    float max_l = 0.0f, max_r = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        std::memset(stereo, 0, sizeof(stereo));
        orpheus_graph_process(graph, engine, stereo, chunk);
        for (int i = 0; i < chunk; i++) {
            float l = std::fabs(stereo[i * 2]);
            float r = std::fabs(stereo[i * 2 + 1]);
            if (l > max_l) max_l = l;
            if (r > max_r) max_r = r;
        }
    }
    printf("  Effects chain: L=%.4f R=%.4f\n", max_l, max_r);
    bool pass = max_l > 0.001f && max_r > 0.001f;
    printf("Graph effects chain test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

bool run_graph_tests() {
    bool all_pass = true;
    all_pass &= test_graph_single_voice();
    all_pass &= test_graph_polyphonic();
    all_pass &= test_graph_effects_chain();
    return all_pass;
}
