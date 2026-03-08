// WAV snapshot scenarios: deterministic renders for regression and cross-engine comparison
#include "test_harness.h"

bool run_snapshot_tests() {
    printf("\n=== WAV Snapshot Scenarios ===\n");
    mkdir("test", 0755);
    mkdir("test/output", 0755);
    bool all_pass = true;
    const int sr = 48000;
    const char* dir = "test/output";

    // Scenario 1: Single voice C4, Plaits VA, 2s (gate 1s, release 1s)
    {
        printf("  Scenario: single_voice_c4\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        orpheus_engine_set_voice_active(engine, 0, 1);
        orpheus_engine_set_voice_tune(engine, 0, 60.0f);
        orpheus_engine_set_voice_gate(engine, 0, 1);
        engine->voice_params[0].engine_index.store(0);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);
        const int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr) orpheus_engine_set_voice_gate(engine, 0, 0);
            orpheus_engine_process(engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_single_voice_c4", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 2: 4-voice chord C-E-G-C'
    {
        printf("  Scenario: 4voice_chord\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        float chord[] = {60.0f, 64.0f, 67.0f, 72.0f};
        for (int v = 0; v < 4; v++) {
            orpheus_engine_set_voice_active(engine, v, 1);
            orpheus_engine_set_voice_tune(engine, v, chord[v]);
            orpheus_engine_set_voice_gate(engine, v, 1);
            engine->voice_params[v].engine_index.store(0);
            engine->voice_params[v].harmonics.store(0.5f);
            engine->voice_params[v].timbre.store(0.5f);
            engine->voice_params[v].morph.store(0.5f);
            engine->voice_params[v].decay.store(0.5f);
        }
        const int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr) for (int v = 0; v < 4; v++) orpheus_engine_set_voice_gate(engine, v, 0);
            orpheus_engine_process(engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_4voice_chord", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 3: Bender — bend up and release (pitch CV + spring audio)
    {
        printf("  Scenario: bender_sweep\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        GraphUnit u = {};
        u.type = UNIT_BENDER; u.enabled = true;
        unit_init(&u, 48000.0f);
        engine->bend_max_semitones.store(12.0f);
        engine->bend_spring_vol.store(0.4f);
        engine->bend_tension_vol.store(0.02f);
        const int total = sr * 3;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            float t = (float)off / sr;
            if (t < 1.0f) engine->bend_amount.store(t);
            else if (t < 1.5f) engine->bend_amount.store(1.0f);
            else engine->bend_amount.store(0.0f);
            unit_process_bender(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float pitch = u.output_buffers[OPORT_OUT][i];
                float audio = u.output_buffers[OPORT_AUX][i];
                buf[(off + i) * 2]     = pitch * 0.1f + audio;
                buf[(off + i) * 2 + 1] = audio;
            }
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_bender_sweep", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 4: Per-string bender — 4 strings, pluck + slide
    {
        printf("  Scenario: per_string_bender\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        GraphUnit u = {};
        u.type = UNIT_PER_STRING_BENDER; u.enabled = true;
        unit_init(&u, 48000.0f);
        engine->string_base_freq[0].store(400.0f);
        engine->string_base_freq[1].store(500.0f);
        engine->string_base_freq[2].store(600.0f);
        engine->string_base_freq[3].store(700.0f);
        const int total = sr * 4;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            float t = (float)off / sr;
            for (int s = 0; s < 4; s++) {
                float start = s * 0.5f;
                bool active = t >= start && t < start + 0.5f;
                engine->string_active[s].store(active ? 1 : 0);
                engine->string_bend[s].store(active ? 0.7f : 0.0f);
                engine->string_mix[s].store(active ? 0.8f : 0.0f);
            }
            unit_process_per_string_bender(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                buf[(off + i) * 2]     = u.output_buffers[OPORT_OUT][i];
                buf[(off + i) * 2 + 1] = u.output_buffers[OPORT_OUT_RIGHT][i];
            }
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_per_string_bender", buf.data(), total, sr, dir);
        orpheus_engine_destroy(engine);
    }

    // Scenario 5: Voice through reverb (graph)
    {
        printf("  Scenario: voice_reverb\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        orpheus_engine_set_voice_active(engine, 0, 1);
        orpheus_engine_set_voice_tune(engine, 0, 60.0f);
        orpheus_engine_set_voice_gate(engine, 0, 1);
        engine->voice_params[0].engine_index.store(0);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);
        engine->reverb_bypass.store(0);
        engine->reverb_amount.store(0.5f);
        engine->reverb_time.store(0.7f);

        OrpheusGraph* graph = new OrpheusGraph();
        std::memset(graph, 0, sizeof(OrpheusGraph));
        graph->sample_rate = (float)sr;
        graph->unit_count = 4;
        graph->exec_count = 4;
        for (int i = 0; i < 4; i++) graph->exec_order[i] = i;
        graph->master_out_index = 3;

        graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
        graph->units[0].enabled = true;
        unit_init(&graph->units[0], (float)sr);
        graph->units[0].state.module.index = 0;

        graph->units[1].type = UNIT_REVERB; graph->units[1].id = 1;
        graph->units[1].enabled = true; unit_init(&graph->units[1], (float)sr);
        graph->units[1].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[1].inputs[IPORT_INPUT_B].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_B].num_sources = 1;

        graph->units[2].type = UNIT_ADD; graph->units[2].id = 2;
        graph->units[2].enabled = true; unit_init(&graph->units[2], (float)sr);
        graph->units[2].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[2].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
        graph->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

        graph->units[3].type = UNIT_MASTER_OUT; graph->units[3].id = 3;
        graph->units[3].enabled = true; unit_init(&graph->units[3], (float)sr);
        graph->units[3].inputs[IPORT_INPUT_A].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
        graph->units[3].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[3].inputs[IPORT_INPUT_B].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
        graph->units[3].inputs[IPORT_INPUT_B].num_sources = 1;

        const int total = sr * 4;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr / 2) orpheus_engine_set_voice_gate(engine, 0, 0);
            orpheus_graph_process(graph, engine, buf.data() + off * 2, chunk);
        }
        float tail_rms = compute_rms(buf.data() + (sr * 3) * 2, sr * 2);
        printf("    RMS=%.4f Peak=%.4f Tail=%.6f\n",
               compute_rms(buf.data(), total * 2), compute_peak(buf.data(), total * 2), tail_rms);
        all_pass &= snapshot_check("cpp_voice_reverb", buf.data(), total, sr, dir);
        delete graph;
        orpheus_engine_destroy(engine);
    }

    // Scenario 6: Voice through delay (graph)
    {
        printf("  Scenario: voice_delay\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        orpheus_engine_set_voice_active(engine, 0, 1);
        orpheus_engine_set_voice_tune(engine, 0, 60.0f);
        orpheus_engine_set_voice_gate(engine, 0, 1);
        engine->voice_params[0].engine_index.store(0);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);
        engine->delay_bypass.store(0);
        engine->delay_mix.store(0.5f);
        engine->delay_feedback.store(0.4f);
        engine->delay_time_1.store(0.2f);
        engine->delay_time_2.store(0.3f);

        OrpheusGraph* graph = new OrpheusGraph();
        std::memset(graph, 0, sizeof(OrpheusGraph));
        graph->sample_rate = (float)sr;
        graph->unit_count = 4;
        graph->exec_count = 4;
        for (int i = 0; i < 4; i++) graph->exec_order[i] = i;
        graph->master_out_index = 3;

        graph->units[0].type = UNIT_PLAITS; graph->units[0].id = 0;
        graph->units[0].enabled = true;
        unit_init(&graph->units[0], (float)sr);
        graph->units[0].state.module.index = 0;

        graph->units[1].type = UNIT_DUAL_DELAY; graph->units[1].id = 1;
        graph->units[1].enabled = true; unit_init(&graph->units[1], (float)sr);
        graph->units[1].inputs[IPORT_INPUT_A].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[1].inputs[IPORT_INPUT_B].sources[0] = graph->units[0].output_buffers[OPORT_OUT];
        graph->units[1].inputs[IPORT_INPUT_B].num_sources = 1;

        graph->units[2].type = UNIT_HARD_CLIP; graph->units[2].id = 2;
        graph->units[2].enabled = true; unit_init(&graph->units[2], (float)sr);
        graph->units[2].inputs[IPORT_INPUT].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
        graph->units[2].inputs[IPORT_INPUT].num_sources = 1;

        graph->units[3].type = UNIT_MASTER_OUT; graph->units[3].id = 3;
        graph->units[3].enabled = true; unit_init(&graph->units[3], (float)sr);
        graph->units[3].inputs[IPORT_INPUT_A].sources[0] = graph->units[2].output_buffers[OPORT_OUT];
        graph->units[3].inputs[IPORT_INPUT_A].num_sources = 1;
        graph->units[3].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT_RIGHT];
        graph->units[3].inputs[IPORT_INPUT_B].num_sources = 1;

        const int total = sr * 3;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr / 4) orpheus_engine_set_voice_gate(engine, 0, 0);
            orpheus_graph_process(graph, engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n",
               compute_rms(buf.data(), total * 2), compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check("cpp_voice_delay", buf.data(), total, sr, dir);
        delete graph;
        orpheus_engine_destroy(engine);
    }

    // Per-engine Plaits snapshots
    {
        struct EngineSpec {
            int cpp_index;
            const char* name;
        };
        EngineSpec engines[] = {
            { 8, "virtual_analog"}, { 9, "waveshaping"}, {10, "fm"},
            {11, "grain"}, {12, "additive"}, {13, "wavetable"},
            {14, "chord"}, {15, "speech"}, {16, "swarm"},
            {17, "noise"}, {18, "particle"}, {19, "string"},
            {20, "modal"}, {21, "bass_drum"}, {22, "snare_drum"},
            {23, "hihat"},
        };

        for (auto& e : engines) {
            char label[64];
            snprintf(label, sizeof(label), "cpp_engine_%s", e.name);
            printf("  Scenario: engine_%s\n", e.name);
            auto buf = render_plaits_engine(
                e.cpp_index, 60.0f, 0.5f, 0.5f, 0.5f, 0.5f,
                sr, 2.0f, 1.0f);
            int total = sr * 2;
            printf("    RMS=%.4f Peak=%.4f\n",
                   compute_rms(buf.data(), total * 2),
                   compute_peak(buf.data(), total * 2));
            all_pass &= snapshot_check(label, buf.data(), total, sr, dir);
        }
    }

    // Raw Plaits output — isolated voice rendered via plaits::Voice::Render directly
    {
        struct EngineSpec {
            int cpp_index;
            const char* name;
        };
        EngineSpec engines[] = {
            { 8, "virtual_analog"}, { 9, "waveshaping"}, {10, "fm"},
            {11, "grain"}, {12, "additive"}, {13, "wavetable"},
            {14, "chord"}, {15, "speech"}, {16, "swarm"},
            {17, "noise"}, {18, "particle"}, {19, "string"},
            {20, "modal"}, {21, "bass_drum"}, {22, "snare_drum"},
            {23, "hihat"},
        };

        for (auto& e : engines) {
            char label[64];
            snprintf(label, sizeof(label), "cpp_raw_%s", e.name);

            OrpheusEngine* eng = orpheus_engine_create(sr);

            plaits::Patch patch;
            patch.engine = e.cpp_index;
            patch.note = 60.0f;
            patch.harmonics = 0.5f;
            patch.timbre = 0.5f;
            patch.morph = 0.5f;
            patch.decay = 0.5f;
            patch.lpg_colour = 0.5f;
            patch.frequency_modulation_amount = 0.0f;
            patch.timbre_modulation_amount = 0.0f;
            patch.morph_modulation_amount = 0.0f;

            plaits::Modulations mod = {};
            mod.trigger = 1.0f;
            mod.trigger_patched = true;

            int total = sr * 2;
            std::vector<float> buf(total * 2, 0.0f);
            const float inv_32768 = 1.0f / 32768.0f;

            for (int off = 0; off < total; off += 12) {
                int block = std::min(12, total - off);
                plaits::Voice::Frame frames[plaits::kMaxBlockSize];
                eng->voices_dsp[0].Render(patch, mod, frames, block);
                for (int i = 0; i < block; i++) {
                    float sample = (frames[i].out + frames[i].aux) * 0.5f * inv_32768;
                    buf[(off + i) * 2]     = sample;
                    buf[(off + i) * 2 + 1] = sample;
                }
                // Keep gate high (sustain)
                mod.trigger = 1.0f;
            }
            printf("  Raw %s: RMS=%.4f Peak=%.4f\n", e.name,
                   compute_rms(buf.data(), total * 2), compute_peak(buf.data(), total * 2));
            all_pass &= snapshot_check(label, buf.data(), total, sr, dir);
            orpheus_engine_destroy(eng);
        }
    }

    printf("WAV snapshots: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}
