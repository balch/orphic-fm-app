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

// ── Signal-Loss Integration Tests ─────────────────────────────────

// Helper: build a mini voice pipeline graph for N voices
// plaits[0..N-1] → vol_multiply[N..2N-1] → panL[2N..3N-1] → sum[3N] → clip[3N+1] → master[3N+2]
// Also includes panR → sumR → master_R for stereo
static OrpheusGraph* build_voice_pipeline_graph(int num_voices, float sample_rate) {
    auto* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = sample_rate;

    int uid = 0;
    int plaits_start = uid;
    // Plaits units
    for (int v = 0; v < num_voices; v++) {
        auto& u = graph->units[uid];
        u.type = UNIT_PLAITS; u.id = uid; u.enabled = true;
        unit_init(&u, sample_rate);
        u.state.module.index = v;
        uid++;
    }

    // Volume multiply units (inputA = plaits out, inputB = 1.0)
    int vol_start = uid;
    for (int v = 0; v < num_voices; v++) {
        auto& u = graph->units[uid];
        u.type = UNIT_MULTIPLY; u.id = uid; u.enabled = true;
        unit_init(&u, sample_rate);
        u.inputs[IPORT_INPUT_A].sources[0] = graph->units[plaits_start + v].output_buffers[OPORT_OUT];
        u.inputs[IPORT_INPUT_A].num_sources = 1;
        u.inputs[IPORT_INPUT_B].constant = 1.0f;
        uid++;
    }

    // Pan L multiply units (inputA = vol out, inputB = 0.707)
    int panL_start = uid;
    for (int v = 0; v < num_voices; v++) {
        auto& u = graph->units[uid];
        u.type = UNIT_MULTIPLY; u.id = uid; u.enabled = true;
        unit_init(&u, sample_rate);
        u.inputs[IPORT_INPUT_A].sources[0] = graph->units[vol_start + v].output_buffers[OPORT_OUT];
        u.inputs[IPORT_INPUT_A].num_sources = 1;
        u.inputs[IPORT_INPUT_B].constant = 0.7071f;
        uid++;
    }

    // Summing passthrough (up to 4 sources per input — use multiple if needed)
    int sum_id = uid;
    auto& sum_unit = graph->units[uid];
    sum_unit.type = UNIT_PASS_THROUGH; sum_unit.id = uid; sum_unit.enabled = true;
    unit_init(&sum_unit, sample_rate);
    int sources_added = 0;
    for (int v = 0; v < num_voices && sources_added < 4; v++) {
        sum_unit.inputs[IPORT_INPUT].sources[sources_added] = graph->units[panL_start + v].output_buffers[OPORT_OUT];
        sources_added++;
    }
    sum_unit.inputs[IPORT_INPUT].num_sources = sources_added;
    uid++;

    // If more than 4 voices, add a second sum and combine
    int final_sum_id = sum_id;
    if (num_voices > 4) {
        int sum2_id = uid;
        auto& sum2 = graph->units[uid];
        sum2.type = UNIT_PASS_THROUGH; sum2.id = uid; sum2.enabled = true;
        unit_init(&sum2, sample_rate);
        int s2 = 0;
        for (int v = 4; v < num_voices && s2 < 4; v++) {
            sum2.inputs[IPORT_INPUT].sources[s2] = graph->units[panL_start + v].output_buffers[OPORT_OUT];
            s2++;
        }
        sum2.inputs[IPORT_INPUT].num_sources = s2;
        uid++;

        // Top-level sum
        final_sum_id = uid;
        auto& top_sum = graph->units[uid];
        top_sum.type = UNIT_PASS_THROUGH; top_sum.id = uid; top_sum.enabled = true;
        unit_init(&top_sum, sample_rate);
        top_sum.inputs[IPORT_INPUT].sources[0] = graph->units[sum_id].output_buffers[OPORT_OUT];
        top_sum.inputs[IPORT_INPUT].sources[1] = graph->units[sum2_id].output_buffers[OPORT_OUT];
        top_sum.inputs[IPORT_INPUT].num_sources = 2;
        uid++;
    }

    // Hard clip
    int clip_id = uid;
    auto& clip = graph->units[uid];
    clip.type = UNIT_HARD_CLIP; clip.id = uid; clip.enabled = true;
    unit_init(&clip, sample_rate);
    clip.inputs[IPORT_INPUT].sources[0] = graph->units[final_sum_id].output_buffers[OPORT_OUT];
    clip.inputs[IPORT_INPUT].num_sources = 1;
    uid++;

    // Master out (mono duplicated to stereo for simplicity)
    int master_id = uid;
    auto& master = graph->units[uid];
    master.type = UNIT_MASTER_OUT; master.id = uid; master.enabled = true;
    unit_init(&master, sample_rate);
    master.inputs[IPORT_INPUT_A].sources[0] = graph->units[clip_id].output_buffers[OPORT_OUT];
    master.inputs[IPORT_INPUT_A].num_sources = 1;
    master.inputs[IPORT_INPUT_B].sources[0] = graph->units[clip_id].output_buffers[OPORT_OUT];
    master.inputs[IPORT_INPUT_B].num_sources = 1;
    uid++;

    graph->unit_count = uid;
    graph->exec_count = uid;
    graph->master_out_index = master_id;
    for (int i = 0; i < uid; i++) graph->exec_order[i] = i;

    return graph;
}

// Helper: render a specific voice through graph and return master peak
static float render_voice_through_graph(OrpheusEngine* engine, OrpheusGraph* graph,
                                        int voice_idx, int total_frames) {
    // Gate on
    engine->voice_params[voice_idx].gate.store(1);

    float stereo[256];
    float max_amp = 0.0f;
    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        std::memset(stereo, 0, sizeof(stereo));
        orpheus_graph_process(graph, engine, stereo, chunk);
        for (int i = 0; i < chunk * 2; i++) {
            float a = std::fabs(stereo[i]);
            if (a > max_amp) max_amp = a;
        }
    }

    // Gate off and render release to silence
    engine->voice_params[voice_idx].gate.store(0);
    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        orpheus_graph_process(graph, engine, stereo, chunk);
    }

    return max_amp;
}

// Test: each voice individually produces similar output through the pipeline
static bool test_per_voice_signal_parity() {
    printf("\n=== Test: Per-Voice Signal Parity (8 voices) ===\n");
    constexpr float sr = 48000.0f;
    constexpr int num_voices = 8;
    constexpr int render_frames = 12000; // 250ms

    OrpheusEngine* engine = orpheus_engine_create(sr);
    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};

    // Activate all voices with Engine 0 (clean OSC)
    for (int v = 0; v < num_voices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(-1); // Engine 0
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.5f);
    }

    OrpheusGraph* graph = build_voice_pipeline_graph(num_voices, sr);

    float peaks[num_voices];
    bool pass = true;

    for (int v = 0; v < num_voices; v++) {
        peaks[v] = render_voice_through_graph(engine, graph, v, render_frames);
        printf("  Voice %d (note %.0f): master_peak=%.4f %s\n",
               v, notes[v], peaks[v], peaks[v] > 0.05f ? "OK" : "*** LOW/SILENT ***");
        if (peaks[v] < 0.05f) pass = false;
    }

    // Check parity: no voice should be more than 3x quieter than the loudest
    float max_peak = *std::max_element(peaks, peaks + num_voices);
    for (int v = 0; v < num_voices; v++) {
        float ratio = peaks[v] / max_peak;
        if (ratio < 0.33f) {
            printf("  FAIL: Voice %d is %.1fx quieter than loudest (ratio=%.3f)\n",
                   v, 1.0f / ratio, ratio);
            pass = false;
        }
    }

    // Check duo parity: each pair (0/1, 2/3, ...) should be within 2x
    printf("  Duo parity:\n");
    for (int d = 0; d < num_voices / 2; d++) {
        int va = d * 2, vb = d * 2 + 1;
        float ratio = (peaks[va] > peaks[vb])
            ? peaks[vb] / peaks[va]
            : peaks[va] / peaks[vb];
        printf("    Duo %d: v%d=%.4f v%d=%.4f ratio=%.3f %s\n",
               d, va, peaks[va], vb, peaks[vb], ratio,
               ratio > 0.5f ? "OK" : "*** MISMATCH ***");
        if (ratio < 0.5f) pass = false;
    }

    printf("Per-voice signal parity test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

// Test: per-string bender doesn't zero odd-voice volumes
// This catches the bug where string_mix=0 caused voice_mix_cv[odd]=0
static bool test_bender_voice_volume_parity() {
    printf("\n=== Test: Bender Voice Volume Parity ===\n");
    constexpr float sr = 48000.0f;
    constexpr int num_voices = 8;
    constexpr int render_frames = 12000;

    OrpheusEngine* engine = orpheus_engine_create(sr);

    // Activate all voices
    for (int v = 0; v < num_voices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(-1);
        engine->voice_params[v].tune.store(60.0f);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.5f);
    }

    // Build graph that includes the per-string bender
    // This exercises voice_mix_cv which the bender writes every frame
    auto* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = sr;

    int uid = 0;

    // 8 plaits voices
    for (int v = 0; v < num_voices; v++) {
        auto& u = graph->units[uid];
        u.type = UNIT_PLAITS; u.id = uid; u.enabled = true;
        unit_init(&u, sr);
        u.state.module.index = v;
        uid++;
    }

    // Per-string bender (processes all 4 strings = 8 voices)
    int psb_id = uid;
    auto& psb = graph->units[uid];
    psb.type = UNIT_PER_STRING_BENDER; psb.id = uid; psb.enabled = true;
    unit_init(&psb, sr);
    uid++;

    // Sum all voices
    int sum_id = uid;
    auto& sum_unit = graph->units[uid];
    sum_unit.type = UNIT_PASS_THROUGH; sum_unit.id = uid; sum_unit.enabled = true;
    unit_init(&sum_unit, sr);
    for (int v = 0; v < std::min(num_voices, 4); v++) {
        sum_unit.inputs[IPORT_INPUT].sources[v] = graph->units[v].output_buffers[OPORT_OUT];
    }
    sum_unit.inputs[IPORT_INPUT].num_sources = std::min(num_voices, 4);
    uid++;

    int sum2_id = uid;
    auto& sum2 = graph->units[uid];
    sum2.type = UNIT_PASS_THROUGH; sum2.id = uid; sum2.enabled = true;
    unit_init(&sum2, sr);
    int s2 = 0;
    for (int v = 4; v < num_voices && s2 < 4; v++) {
        sum2.inputs[IPORT_INPUT].sources[s2] = graph->units[v].output_buffers[OPORT_OUT];
        s2++;
    }
    sum2.inputs[IPORT_INPUT].num_sources = s2;
    uid++;

    int top_sum_id = uid;
    auto& top_sum = graph->units[uid];
    top_sum.type = UNIT_PASS_THROUGH; top_sum.id = uid; top_sum.enabled = true;
    unit_init(&top_sum, sr);
    top_sum.inputs[IPORT_INPUT].sources[0] = graph->units[sum_id].output_buffers[OPORT_OUT];
    top_sum.inputs[IPORT_INPUT].sources[1] = graph->units[sum2_id].output_buffers[OPORT_OUT];
    top_sum.inputs[IPORT_INPUT].num_sources = 2;
    uid++;

    // Master out
    int master_id = uid;
    auto& master = graph->units[uid];
    master.type = UNIT_MASTER_OUT; master.id = uid; master.enabled = true;
    unit_init(&master, sr);
    master.inputs[IPORT_INPUT_A].sources[0] = graph->units[top_sum_id].output_buffers[OPORT_OUT];
    master.inputs[IPORT_INPUT_A].num_sources = 1;
    master.inputs[IPORT_INPUT_B].sources[0] = graph->units[top_sum_id].output_buffers[OPORT_OUT];
    master.inputs[IPORT_INPUT_B].num_sources = 1;
    uid++;

    graph->unit_count = uid;
    graph->master_out_index = master_id;
    // Exec order: bender first (it writes voice_mix_cv), then voices, then sum, then master
    graph->exec_count = uid;
    graph->exec_order[0] = psb_id; // bender runs first
    int ei = 1;
    for (int v = 0; v < num_voices; v++) graph->exec_order[ei++] = v; // plaits units
    graph->exec_order[ei++] = sum_id;
    graph->exec_order[ei++] = sum2_id;
    graph->exec_order[ei++] = top_sum_id;
    graph->exec_order[ei++] = master_id;

    bool pass = true;
    float peaks[num_voices];

    for (int v = 0; v < num_voices; v++) {
        peaks[v] = render_voice_through_graph(engine, graph, v, render_frames);
        bool is_odd = (v % 2 == 1);
        printf("  Voice %d (%s): master_peak=%.4f %s\n",
               v, is_odd ? "odd/B" : "even/A", peaks[v],
               peaks[v] > 0.05f ? "OK" : "*** SILENT (bender zeroed?) ***");
        if (peaks[v] < 0.05f) pass = false;
    }

    // Verify even/odd parity within each duo
    for (int d = 0; d < num_voices / 2; d++) {
        int va = d * 2, vb = d * 2 + 1;
        float ratio = std::min(peaks[va], peaks[vb]) / std::max(peaks[va], peaks[vb]);
        if (ratio < 0.5f) {
            printf("  FAIL: Duo %d: v%d=%.4f v%d=%.4f — %.1fx difference!\n",
                   d, va, peaks[va], vb, peaks[vb],
                   std::max(peaks[va], peaks[vb]) / std::min(peaks[va], peaks[vb]));
            pass = false;
        }
    }

    printf("Bender voice volume parity test: %s\n", pass ? "PASS" : "FAIL");
    delete graph;
    orpheus_engine_destroy(engine);
    return pass;
}

// Test: verify voice_mix_cv is 1.0 for all voices after engine creation
static bool test_voice_mix_cv_defaults() {
    printf("\n=== Test: voice_mix_cv defaults ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool pass = true;

    for (int v = 0; v < kNumMainVoices; v++) {
        float mix_cv = engine->voice_mix_cv[v];
        if (std::fabs(mix_cv - 1.0f) > 0.01f) {
            printf("  FAIL: voice_mix_cv[%d] = %.4f (expected 1.0)\n", v, mix_cv);
            pass = false;
        }
    }

    // Also verify string_mix defaults put both voices at full volume
    for (int s = 0; s < 4; s++) {
        float mix = engine->string_mix[s].load(std::memory_order_relaxed);
        // mix should be in [0.25, 0.75] range for both voices to get 1.0
        if (mix < 0.25f || mix > 0.75f) {
            printf("  FAIL: string_mix[%d] = %.4f — outside [0.25, 0.75], will attenuate one voice\n",
                   s, mix);
            pass = false;
        }
    }

    printf("voice_mix_cv defaults test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// ── Issue 1: Plaits engines must respond to envSpeed ──────────────
// In JSyn, DspVoice wraps ALL sources (OSC + Plaits) with an ADSR VCA,
// so envSpeed affects attack/release for Plaits engines too.
// The C++ path currently has NO ADSR on Plaits — this test should FAIL
// until we add ADSR wrapping to the Plaits render path.
static bool test_plaits_envspeed_varies_output() {
    printf("\n=== Test: Plaits engines respond to envSpeed ===\n");
    constexpr float sr = 48000.0f;
    constexpr int plaits_va = 8; // Virtual Analog engine

    // Render at fast (0.0) and slow (1.0) envSpeed
    // With ADSR: fast should have quick attack/release, slow should have long attack
    // → RMS over 4s should differ significantly
    auto buf_fast = render_voice_with_envelope(plaits_va, 60.0f, 0.5f, 0.5f, 0.5f,
                                                0.0f, (int)sr, 4.0f, 1.0f);
    auto buf_slow = render_voice_with_envelope(plaits_va, 60.0f, 0.5f, 0.5f, 0.5f,
                                                1.0f, (int)sr, 4.0f, 1.0f);

    int total = (int)(sr * 4);
    float rms_fast = compute_rms(buf_fast.data(), total * 2);
    float rms_slow = compute_rms(buf_slow.data(), total * 2);
    float peak_fast = compute_peak(buf_fast.data(), total * 2);
    float peak_slow = compute_peak(buf_slow.data(), total * 2);

    printf("  envspeed=0.0 (fast): RMS=%.4f Peak=%.4f\n", rms_fast, peak_fast);
    printf("  envspeed=1.0 (slow): RMS=%.4f Peak=%.4f\n", rms_slow, peak_slow);

    // Check release: measure amplitude 500ms after gate-off (at 1500ms = frame 72000)
    int release_check = (int)(sr * 1.5f); // 500ms after gate-off
    float release_fast = 0.0f, release_slow = 0.0f;
    int window = (int)(sr / 100); // 10ms window
    for (int i = release_check; i < release_check + window && i < total; i++) {
        float a = std::fabs(buf_fast[i * 2]);
        if (a > release_fast) release_fast = a;
        a = std::fabs(buf_slow[i * 2]);
        if (a > release_slow) release_slow = a;
    }
    printf("  500ms after gate-off: fast=%.4f slow=%.4f\n", release_fast, release_slow);

    bool pass = true;

    // The fast and slow renders MUST produce different RMS (ADSR shapes the output)
    float rms_ratio = std::min(rms_fast, rms_slow) / std::max(rms_fast, rms_slow);
    if (rms_ratio > 0.95f) {
        printf("  FAIL: envSpeed has no effect on Plaits — RMS ratio=%.3f (expected < 0.95)\n", rms_ratio);
        pass = false;
    } else {
        printf("  RMS ratio=%.3f — envSpeed affects Plaits output OK\n", rms_ratio);
    }

    // Fast release should be mostly silent 500ms after gate-off
    if (release_fast > 0.05f) {
        printf("  FAIL: fast envSpeed still loud 500ms after gate-off (%.4f > 0.05)\n", release_fast);
        pass = false;
    }

    printf("Plaits envSpeed test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Issue 2: Engine 0 ADSR release behavior ──────────────────────
// Tests the behavioral contract of the ADSR, not JSyn-specific thresholds:
// 1. Faster envSpeed → faster release (monotonic ordering)
// 2. envspeed=0.0 (fastest) must reach silence within 3s of release
// 3. Release must actually reduce output (not stuck at sustain)
static bool test_engine0_release_timing() {
    printf("\n=== Test: Engine 0 ADSR release behavior ===\n");
    constexpr float sr = 48000.0f;
    bool pass = true;

    float speeds[] = {0.0f, 0.5f, 1.0f};
    float silence_times[3]; // ms to reach < 0.01 after gate-off

    for (int s = 0; s < 3; s++) {
        auto buf = render_voice_with_envelope(-1, 60.0f, 0.5f, 0.5f, 0.5f,
                                               speeds[s], (int)sr, 4.0f, 1.0f);
        int total = (int)(sr * 4);

        // Sustain level (peak in last 100ms before gate-off)
        float sustain_peak = 0.0f;
        int sustain_start = (int)(sr * 0.9f);
        int sustain_end = (int)sr;
        for (int i = sustain_start; i < sustain_end; i++) {
            float a = std::fabs(buf[i * 2]);
            if (a > sustain_peak) sustain_peak = a;
        }

        // Measure time to reach silence after gate-off
        int gate_off_frame = (int)sr;
        float threshold_silent = 0.01f;
        silence_times[s] = -1.0f;
        int window = (int)(sr / 100); // 10ms
        float level_at_500ms = 0.0f;

        for (int off = gate_off_frame; off < total; off += window) {
            int end = std::min(off + window, total);
            float win_peak = 0.0f;
            for (int i = off; i < end; i++) {
                float a = std::fabs(buf[i * 2]);
                if (a > win_peak) win_peak = a;
            }
            float ms_after_gate = (float)(off - gate_off_frame) / sr * 1000.0f;
            if (ms_after_gate >= 490.0f && ms_after_gate <= 510.0f)
                level_at_500ms = win_peak;
            if (silence_times[s] < 0 && win_peak < threshold_silent)
                silence_times[s] = ms_after_gate;
        }

        printf("  envspeed=%.1f: sustain=%.3f, 500ms_after=%.4f, silence=%.0fms\n",
               speeds[s], sustain_peak, level_at_500ms, silence_times[s]);

        // Check 1: release must actually reduce level (not stuck)
        if (level_at_500ms > sustain_peak * 0.9f) {
            printf("  FAIL: level barely dropped 500ms after gate-off (%.3f vs sustain %.3f)\n",
                   level_at_500ms, sustain_peak);
            pass = false;
        }

        // Check 2: ALL speeds must reach silence within 3s (a note must always fully release)
        if (silence_times[s] < 0) {
            printf("  FAIL: envspeed=%.1f never reached silence in 3s\n", speeds[s]);
            pass = false;
        }
    }

    // Check 3: faster envSpeed → faster release (monotonic ordering)
    // silence_times[0] should be < silence_times[1] < silence_times[2]
    // (or both slow ones may not reach silence, which is fine)
    for (int s = 0; s < 2; s++) {
        if (silence_times[s] > 0 && silence_times[s + 1] > 0) {
            if (silence_times[s] >= silence_times[s + 1]) {
                printf("  FAIL: faster speed (%.1f=%.0fms) should release before slower (%.1f=%.0fms)\n",
                       speeds[s], silence_times[s], speeds[s + 1], silence_times[s + 1]);
                pass = false;
            }
        }
    }

    printf("Engine 0 release timing test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_graph_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_graph_single_voice());
    tally(test_graph_polyphonic());
    tally(test_graph_effects_chain());
    tally(test_voice_mix_cv_defaults());
    tally(test_per_voice_signal_parity());
    tally(test_bender_voice_volume_parity());
    tally(test_plaits_envspeed_varies_output());
    tally(test_engine0_release_timing());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
