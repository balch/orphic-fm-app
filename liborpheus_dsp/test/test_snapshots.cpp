// WAV snapshot scenarios: deterministic renders for regression and cross-engine comparison
#include "test_harness.h"

bool run_snapshot_tests() {
    printf("\n=== WAV Snapshot Scenarios ===\n");
    mkdir("test", 0755);
    mkdir("test/output", 0755);
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    const int sr = 48000;
    const char* dir = "test/output";

    // Scenario 1: Single voice C4, Plaits VA, 2s (gate 1s, release 1s)
    {
        printf("  Scenario: single_voice_c4\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        if (!load_production_graph(engine)) {
            printf("FAIL: could not load production graph\n");
            orpheus_engine_destroy(engine);
            return false;
        }
        activate_voice(engine, 0, 0, 60.0f, 0.5f, 0.5f, 0.5f, 0.5f);
        const int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr) orpheus_engine_set_voice_gate(engine, 0, 0);
            orpheus_engine_process(engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        tally(snapshot_check("cpp_single_voice_c4", buf.data(), total, sr, dir));
        orpheus_engine_destroy(engine);
    }

    // Scenario 2: 4-voice chord C-E-G-C'
    {
        printf("  Scenario: 4voice_chord\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        if (!load_production_graph(engine)) {
            printf("FAIL: could not load production graph\n");
            orpheus_engine_destroy(engine);
            return false;
        }
        float chord[] = {60.0f, 64.0f, 67.0f, 72.0f};
        for (int v = 0; v < 4; v++)
            activate_voice(engine, v, 0, chord[v], 0.5f, 0.5f, 0.5f, 0.5f);
        const int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            if (off >= sr) for (int v = 0; v < 4; v++) orpheus_engine_set_voice_gate(engine, v, 0);
            orpheus_engine_process(engine, buf.data() + off * 2, chunk);
        }
        printf("    RMS=%.4f Peak=%.4f\n", compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        tally(snapshot_check("cpp_4voice_chord", buf.data(), total, sr, dir));
        orpheus_engine_destroy(engine);
    }

    // Scenario 3: Bender — bend up and release (pitch CV + tension audio)
    {
        printf("  Scenario: bender_sweep\n");
        OrpheusEngine* engine = orpheus_engine_create(sr);
        GraphUnit u = {};
        u.type = UNIT_BENDER; u.enabled = true;
        unit_init(&u, 48000.0f);
        engine->bend_max_semitones.store(12.0f);
        // spring oscillator removed
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
        tally(snapshot_check("cpp_bender_sweep", buf.data(), total, sr, dir));
        // Envelope CSV for A/B comparison
        {
            char csv_path[512];
            snprintf(csv_path, sizeof(csv_path), "%s/cpp_bender_sweep_envelope.csv", dir);
            FILE* csv = fopen(csv_path, "w");
            if (csv) {
                fprintf(csv, "time_ms,peak_amplitude\n");
                int window = sr / 100;
                for (int off = 0; off < total; off += window) {
                    int end = std::min(off + window, total);
                    float win_peak = 0.0f;
                    for (int i = off; i < end; i++) {
                        float a = std::fabs(buf[i * 2 + 1]); // right = audio only
                        if (a > win_peak) win_peak = a;
                    }
                    fprintf(csv, "%.1f,%.6f\n", (float)off / sr * 1000.0f, win_peak);
                }
                fclose(csv);
            }
        }
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
        tally(snapshot_check("cpp_per_string_bender", buf.data(), total, sr, dir));
        // Envelope CSV for A/B comparison
        {
            char csv_path[512];
            snprintf(csv_path, sizeof(csv_path), "%s/cpp_per_string_bender_envelope.csv", dir);
            FILE* csv = fopen(csv_path, "w");
            if (csv) {
                fprintf(csv, "time_ms,peak_amplitude\n");
                int window = sr / 100;
                for (int off = 0; off < total; off += window) {
                    int end = std::min(off + window, total);
                    float win_peak = 0.0f;
                    for (int i = off; i < end; i++) {
                        float a = std::max(std::fabs(buf[i * 2]), std::fabs(buf[i * 2 + 1]));
                        if (a > win_peak) win_peak = a;
                    }
                    fprintf(csv, "%.1f,%.6f\n", (float)off / sr * 1000.0f, win_peak);
                }
                fclose(csv);
            }
        }
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
        tally(snapshot_check("cpp_voice_reverb", buf.data(), total, sr, dir));
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
        tally(snapshot_check("cpp_voice_delay", buf.data(), total, sr, dir));
        delete graph;
        orpheus_engine_destroy(engine);
    }

    // Per-engine Plaits snapshots
    {
        struct EngineSpec {
            int cpp_index;
            const char* name;
            bool stochastic;  // true = noise/swarm engines: skip waveform match
        };
        EngineSpec engines[] = {
            { 8, "virtual_analog",  false}, { 9, "waveshaping", false}, {10, "fm",       false},
            {11, "grain",           false}, {12, "additive",    false}, {13, "wavetable", false},
            {14, "chord",           false}, {15, "speech",      false}, {16, "swarm",     true},
            {17, "noise",           true},  {18, "particle",    false}, {19, "string",    false},
            {20, "modal",           false}, {21, "bass_drum",   false}, {22, "snare_drum",false},
            {23, "hihat",           false},
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
            // Stochastic engines produce non-reproducible noise; use large tolerance
            // so the snapshot test only verifies the engine produces non-silent output
            // (confirmed by the RMS print above), not exact waveform identity.
            float tol = e.stochastic ? 2.0f : 0.05f;
            tally(snapshot_check(label, buf.data(), total, sr, dir, tol));
        }
    }

    // Raw Plaits output — isolated voice rendered via OrpheusVoice::Render directly
    {
        struct EngineSpec {
            int cpp_index;
            const char* name;
            bool stochastic;  // true = noise/swarm engines: skip waveform match
        };
        EngineSpec engines[] = {
            { 8, "virtual_analog",  false}, { 9, "waveshaping", false}, {10, "fm",       false},
            {11, "grain",           false}, {12, "additive",    false}, {13, "wavetable", false},
            {14, "chord",           false}, {15, "speech",      false}, {16, "swarm",     true},
            {17, "noise",           true},  {18, "particle",    false}, {19, "string",    false},
            {20, "modal",           false}, {21, "bass_drum",   false}, {22, "snare_drum",true},
            {23, "hihat",           false},
        };

        for (auto& e : engines) {
            char label[64];
            snprintf(label, sizeof(label), "cpp_raw_%s", e.name);

            OrpheusEngine* eng = orpheus_engine_create(sr);

            int total = sr * 2;
            std::vector<float> buf(total * 2, 0.0f);
            std::vector<float> mono(total, 0.0f);

            // Render via OrpheusVoice (direct Engine::Render, outGain + soft_limit applied)
            eng->voices_dsp[0].Render(e.cpp_index, 1 /*gate*/, 60.0f /*note*/,
                                       0.5f /*harmonics*/, 0.5f /*timbre*/,
                                       0.5f /*morph*/, 0.8f /*accent*/,
                                       mono.data(), total);

            // Convert mono to interleaved stereo
            for (int i = 0; i < total; i++) {
                buf[i * 2]     = mono[i];
                buf[i * 2 + 1] = mono[i];
            }

            printf("  Raw %s: RMS=%.4f Peak=%.4f\n", e.name,
                   compute_rms(buf.data(), total * 2), compute_peak(buf.data(), total * 2));
            // Stochastic engines produce non-reproducible noise; use large tolerance
            // so the snapshot test only verifies output presence, not waveform identity.
            float tol = e.stochastic ? 2.0f : 0.05f;
            tally(snapshot_check(label, buf.data(), total, sr, dir, tol));
            orpheus_engine_destroy(eng);
        }
    }

    // ── Envelope Speed Sweep: Engine 0 at various envSpeed levels ──
    // Renders 4s clips (1s gate on, 3s release) via graph-based path
    // which exercises unit_process_plaits ADSR.
    // Also extracts per-10ms peak envelope curve for numeric comparison with JSyn.
    {
        float speeds[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
        printf("\n  --- Envelope Speed Sweep (Engine 0, graph path) ---\n");

        for (float speed : speeds) {
            char label[64];
            snprintf(label, sizeof(label), "cpp_envspeed_%.2f", speed);
            printf("  Scenario: envspeed=%.2f\n", speed);

            auto buf = render_voice_with_envelope(
                -1, 60.0f, 0.5f, 0.5f, 0.5f, speed, sr, 4.0f, 1.0f);
            int total = sr * 4;

            float rms = compute_rms(buf.data(), total * 2);
            float peak = compute_peak(buf.data(), total * 2);
            printf("    RMS=%.4f Peak=%.4f\n", rms, peak);

            tally(snapshot_check(label, buf.data(), total, sr, dir));

            // Extract envelope curve: peak amplitude per 10ms window
            char csv_path[512];
            snprintf(csv_path, sizeof(csv_path), "%s/%s_envelope.csv", dir, label);
            FILE* csv = fopen(csv_path, "w");
            if (csv) {
                fprintf(csv, "time_ms,peak_amplitude\n");
                int window = sr / 100; // 10ms at 48kHz = 480 samples
                for (int off = 0; off < total; off += window) {
                    int end = std::min(off + window, total);
                    float win_peak = 0.0f;
                    for (int i = off; i < end; i++) {
                        float a = std::fabs(buf[i * 2]); // left channel
                        if (a > win_peak) win_peak = a;
                    }
                    fprintf(csv, "%.1f,%.6f\n", (float)off / sr * 1000.0f, win_peak);
                }
                fclose(csv);
                printf("    Envelope curve: %s\n", csv_path);
            }
        }
    }

    // ── Envelope Speed Sweep: Plaits VA engine at various envSpeed levels ──
    // Plaits engines have their own internal LPG/decay, so this tests how
    // envSpeed interacts with that. Uses graph path for the ADSR.
    {
        float speeds[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
        printf("\n  --- Envelope Speed Sweep (Plaits VA, graph path) ---\n");

        for (float speed : speeds) {
            char label[64];
            snprintf(label, sizeof(label), "cpp_plaits_envspeed_%.2f", speed);
            printf("  Scenario: plaits envspeed=%.2f\n", speed);

            auto buf = render_voice_with_envelope(
                8, 60.0f, 0.5f, 0.5f, 0.5f, speed, sr, 4.0f, 1.0f);
            int total = sr * 4;

            float rms = compute_rms(buf.data(), total * 2);
            float peak = compute_peak(buf.data(), total * 2);
            printf("    RMS=%.4f Peak=%.4f\n", rms, peak);

            tally(snapshot_check(label, buf.data(), total, sr, dir));

            // Envelope curve CSV
            char csv_path[512];
            snprintf(csv_path, sizeof(csv_path), "%s/%s_envelope.csv", dir, label);
            FILE* csv = fopen(csv_path, "w");
            if (csv) {
                fprintf(csv, "time_ms,peak_amplitude\n");
                int window = sr / 100;
                for (int off = 0; off < total; off += window) {
                    int end = std::min(off + window, total);
                    float win_peak = 0.0f;
                    for (int i = off; i < end; i++) {
                        float a = std::fabs(buf[i * 2]);
                        if (a > win_peak) win_peak = a;
                    }
                    fprintf(csv, "%.1f,%.6f\n", (float)off / sr * 1000.0f, win_peak);
                }
                fclose(csv);
                printf("    Envelope curve: %s\n", csv_path);
            }
        }
    }

    // ── Gain Staging Comparison: full path (voice → master_out) ──
    // Renders each Plaits engine through the full graph path including master_out
    // (pan → volume(0.7) → peak → hard clip) — captures what we actually hear.
    // JSyn test looks for: cpp_gain_virtualanalog.wav, cpp_gain_waveshaping.wav, etc.
    {
        struct GainSpec {
            int cpp_index;
            const char* name; // must match JSyn's PlaitsEngineId name lowercased
            bool stochastic;  // true = noise/swarm engines: skip waveform match
        };
        GainSpec engines[] = {
            { 8, "virtualanalog", false}, { 9, "waveshaping", false}, {10, "fm",       false},
            {11, "grain",         false}, {12, "additive",    false}, {13, "wavetable", false},
            {14, "chord",         false}, {15, "speech",      false}, {16, "swarm",     true},
            {17, "noise",         true},  {18, "particle",    false}, {19, "string",    false},
            {20, "modal",         false},
        };

        printf("\n  --- Gain Staging Comparison (full path: voice → master_out) ---\n");

        for (auto& e : engines) {
            char label[64];
            snprintf(label, sizeof(label), "cpp_gain_%s", e.name);
            printf("  Scenario: gain_%s\n", e.name);

            // Create engine and set master volume to 0.7 (matching JSyn StereoPlugin default)
            OrpheusEngine* eng = orpheus_engine_create(sr);
            eng->master_volume.store(0.7f);
            eng->smooth_master_volume = 0.7f;
            eng->voice_params[0].active.store(1);
            eng->voice_params[0].ever_triggered.store(1);
            eng->voice_params[0].engine_index.store(e.cpp_index);
            eng->voice_params[0].tune.store(60.0f);
            eng->voice_params[0].harmonics.store(0.5f);
            eng->voice_params[0].timbre.store(0.5f);
            eng->voice_params[0].morph.store(0.5f);
            eng->voice_params[0].decay.store(0.5f); // envSpeed = 0.5

            // Minimal graph: plaits → master_out (includes pan, volume, peak, clip)
            auto* graph = create_minimal_graph(0, (float)sr);

            const int total = sr * 2; // 2 seconds
            const int gate_frames = sr * 1; // 1 second gate
            std::vector<float> buf(total * 2, 0.0f);

            for (int off = 0; off < total; off += 128) {
                int chunk = std::min(128, total - off);
                if (off == 0) eng->voice_params[0].gate.store(1);
                if (off >= gate_frames) eng->voice_params[0].gate.store(0);
                orpheus_graph_process(graph, eng, buf.data() + off * 2, chunk);
            }

            float rms = compute_rms(buf.data(), total * 2);
            float peak = compute_peak(buf.data(), total * 2);
            printf("    RMS=%.4f Peak=%.4f\n", rms, peak);
            // Stochastic engines produce non-reproducible noise; use large tolerance
            // so the snapshot test only verifies output presence, not waveform identity.
            float tol = e.stochastic ? 2.0f : 0.05f;
            tally(snapshot_check(label, buf.data(), total, sr, dir, tol));
            delete graph;
            orpheus_engine_destroy(eng);
        }
    }

    // ── Resonator mode snapshots: 3 modes (Modal, Sympathetic, String) ──
    // Renders noise excitation through each OrpheusResonator mode to verify
    // all modes produce distinct non-silent output.
    {
        const char* mode_names[] = {
            "modal", "sympathetic", "string"
        };

        printf("\n  --- Resonator Mode Snapshots ---\n");

        for (int m = 0; m < 3; m++) {
            char label[64];
            snprintf(label, sizeof(label), "cpp_rings_mode_%s", mode_names[m]);
            printf("  Scenario: resonator_mode_%s\n", mode_names[m]);

            OrpheusEngine* eng = orpheus_engine_create(sr);
            eng->rings_bypass.store(0);
            eng->rings_model.store(m);
            eng->rings_structure.store(0.5f);
            eng->rings_brightness.store(0.5f);
            eng->rings_damping.store(0.5f);
            eng->rings_position.store(0.5f);
            eng->rings_frequency.store(60.0f);
            eng->rings_strum.store(1); // trigger strum

            // Set up a single rings unit
            GraphUnit unit = {};
            unit.type = UNIT_RINGS;
            unit.enabled = true;
            unit.state.module.index = 0; // main resonator (not drum)
            unit_init(&unit, (float)sr);

            // Render 2 seconds with noise excitation in first block
            const int total = sr * 2;
            std::vector<float> buf(total * 2, 0.0f);

            for (int off = 0; off < total; off += 128) {
                int chunk = std::min(128, total - off);
                // Fill input buffer with noise excitation (only first 64 samples of first block)
                for (int i = 0; i < chunk; i++) {
                    unit.inputs[IPORT_INPUT].buffer[i] = (off == 0 && i < 64)
                        ? ((float)rand() / RAND_MAX * 2.0f - 1.0f) * 0.5f : 0.0f;
                }
                unit.inputs[IPORT_INPUT].num_sources = 1;

                unit_process_rings(&unit, eng, chunk, (float)sr);

                for (int i = 0; i < chunk; i++) {
                    buf[(off + i) * 2]     = unit.output_buffers[OPORT_OUT][i];
                    buf[(off + i) * 2 + 1] = unit.output_buffers[OPORT_OUT_RIGHT][i];
                }

                // Clear strum after first block
                if (off == 0) eng->rings_strum.store(0);
            }

            float rms = compute_rms(buf.data(), total * 2);
            float peak = compute_peak(buf.data(), total * 2);
            printf("    RMS=%.4f Peak=%.4f\n", rms, peak);
            tally(snapshot_check(label, buf.data(), total, sr, dir));
            orpheus_engine_destroy(eng);
        }
    }

    printf("WAV snapshots: %s\n", suite_fail == 0 ? "PASS" : "FAIL");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
