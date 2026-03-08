// MI module unit tests: Clock, Grids, Marbles, Looper, Bender, Per-String Bender,
// Voice Coupling, FM Modulation
#include "test_harness.h"

static bool test_clock() {
    printf("\n=== Test: Clock pulse accuracy ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);

    GraphUnit clock_unit = {};
    clock_unit.type = UNIT_CLOCK;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    int total_ticks = 0, total_beats = 0;
    const int total_frames = 48000; // 1 second

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            if (clock_unit.output_buffers[0][i] > 0.5f) total_ticks++;
            if (clock_unit.output_buffers[1][i] > 0.5f) total_beats++;
        }
    }

    printf("Ticks in 1 second: %d (expected 48)\n", total_ticks);
    printf("Beats in 1 second: %d (expected 2)\n", total_beats);
    bool pass = (total_ticks == 48 && total_beats == 2);
    printf("Clock test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_grids() {
    printf("\n=== Test: Grids drum triggers ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);
    engine->grids_bypass.store(0);
    engine->grids_x.store(0.5f);
    engine->grids_y.store(0.5f);
    engine->grids_density_kick.store(0.8f);
    engine->grids_density_snare.store(0.8f);
    engine->grids_density_hat.store(0.8f);

    GraphUnit clock_unit = {};
    clock_unit.type = UNIT_CLOCK;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    GraphUnit grids_unit = {};
    grids_unit.type = UNIT_GRIDS;
    grids_unit.enabled = true;
    unit_init(&grids_unit, 48000.0f);

    // Wire clock→grids: clock OPORT_OUT (tick) → grids IPORT_INPUT_A
    grids_unit.inputs[IPORT_INPUT_A].sources[0] = clock_unit.output_buffers[OPORT_OUT];
    grids_unit.inputs[IPORT_INPUT_A].num_sources = 1;
    grids_unit.inputs[IPORT_INPUT_B].sources[0] = clock_unit.output_buffers[OPORT_OUT_RIGHT];
    grids_unit.inputs[IPORT_INPUT_B].num_sources = 1;

    int kick = 0, snare = 0, hat = 0;
    const int total_frames = 48000 * 2; // 2 seconds
    bool prev_k = false, prev_s = false, prev_h = false;

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);
        port_prepare(&grids_unit.inputs[IPORT_INPUT_A], chunk, 48000.0f);
        port_prepare(&grids_unit.inputs[IPORT_INPUT_B], chunk, 48000.0f);
        unit_process_grids(&grids_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            bool k = grids_unit.output_buffers[OPORT_OUT][i] > 0.5f;
            bool s = grids_unit.output_buffers[OPORT_OUT_RIGHT][i] > 0.5f;
            bool h = grids_unit.output_buffers[OPORT_AUX][i] > 0.5f;
            if (k && !prev_k) kick++;
            if (s && !prev_s) snare++;
            if (h && !prev_h) hat++;
            prev_k = k; prev_s = s; prev_h = h;
        }
    }

    printf("Kick: %d  Snare: %d  Hat: %d\n", kick, snare, hat);
    bool pass = kick > 0 && snare > 0 && hat > 0;
    printf("Grids test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_marbles() {
    printf("\n=== Test: Marbles random sequencer ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);
    engine->marbles_bypass.store(0);

    engine->marbles_t_rate.store(0.0f);
    engine->marbles_t_bias.store(0.5f);
    engine->marbles_t_jitter.store(0.0f);
    engine->marbles_t_model.store(0);
    engine->marbles_t_range.store(1);
    engine->marbles_x_spread.store(0.5f);
    engine->marbles_x_bias.store(0.5f);
    engine->marbles_x_steps.store(0.5f);
    engine->marbles_deja_vu.store(0.0f);
    engine->marbles_deja_vu_length.store(8);

    GraphUnit clock_unit = {};
    clock_unit.type = UNIT_CLOCK;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    GraphUnit marbles_unit = {};
    marbles_unit.type = UNIT_MARBLES;
    marbles_unit.enabled = true;
    unit_init(&marbles_unit, 48000.0f);

    marbles_unit.inputs[IPORT_INPUT_A].sources[0] = clock_unit.output_buffers[OPORT_OUT];
    marbles_unit.inputs[IPORT_INPUT_A].num_sources = 1;

    int gate_transitions = 0;
    float cv1_min = 1e9f, cv1_max = -1e9f;
    float cv2_min = 1e9f, cv2_max = -1e9f;
    bool prev_gate = false;
    const int total_frames = 48000 * 2;

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);
        port_prepare(&marbles_unit.inputs[IPORT_INPUT_A], chunk, 48000.0f);
        unit_process_marbles(&marbles_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            bool gate = marbles_unit.output_buffers[OPORT_OUT][i] > 0.5f;
            if (gate && !prev_gate) gate_transitions++;
            prev_gate = gate;

            float cv1 = marbles_unit.output_buffers[OPORT_OUT_RIGHT][i];
            float cv2 = marbles_unit.output_buffers[OPORT_AUX][i];
            if (cv1 < cv1_min) cv1_min = cv1;
            if (cv1 > cv1_max) cv1_max = cv1;
            if (cv2 < cv2_min) cv2_min = cv2;
            if (cv2 > cv2_max) cv2_max = cv2;
        }
    }

    printf("Gate transitions: %d\n", gate_transitions);
    printf("CV1 range: [%.4f, %.4f]\n", cv1_min, cv1_max);
    printf("CV2 range: [%.4f, %.4f]\n", cv2_min, cv2_max);

    bool gate_pass = gate_transitions > 0;
    float cv1_range = cv1_max - cv1_min;
    bool cv_pass = cv1_range > 0.01f;

    printf("Gate test: %s (%d transitions)\n", gate_pass ? "PASS" : "FAIL", gate_transitions);
    printf("CV range test: %s (range=%.4f)\n", cv_pass ? "PASS" : "FAIL", cv1_range);

    bool pass = gate_pass && cv_pass;
    printf("Marbles test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_looper() {
    printf("\n=== Test: Looper record/play ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit looper_unit = {};
    looper_unit.type = UNIT_LOOPER;
    looper_unit.enabled = true;
    unit_init(&looper_unit, 48000.0f);

    const int record_frames = 12000;
    engine->looper_quantize.store(0);
    engine->looper_requested_state.store(1); // record

    for (int offset = 0; offset < record_frames; offset += 128) {
        int chunk = std::min(128, record_frames - offset);
        for (int i = 0; i < chunk; i++) {
            float phase = static_cast<float>(offset + i) / 48000.0f;
            float val = std::sin(phase * 440.0f * 2.0f * 3.14159f) * 0.5f;
            looper_unit.inputs[IPORT_INPUT_A].buffer[i] = val;
            looper_unit.inputs[IPORT_INPUT_B].buffer[i] = val;
            looper_unit.inputs[IPORT_INPUT_C].buffer[i] = 0.0f;
        }
        unit_process_looper(&looper_unit, engine, chunk, 48000.0f);
    }

    printf("Recorded %d samples (loop length: %d)\n", record_frames, engine->looper_length);

    engine->looper_requested_state.store(2); // play
    float max_playback = 0.0f;

    for (int offset = 0; offset < record_frames; offset += 128) {
        int chunk = std::min(128, record_frames - offset);
        std::memset(looper_unit.inputs[IPORT_INPUT_A].buffer, 0, chunk * sizeof(float));
        std::memset(looper_unit.inputs[IPORT_INPUT_B].buffer, 0, chunk * sizeof(float));
        std::memset(looper_unit.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));

        unit_process_looper(&looper_unit, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            float v = std::fabs(looper_unit.output_buffers[OPORT_OUT][i]);
            if (v > max_playback) max_playback = v;
        }
    }

    printf("Max playback amplitude: %.4f\n", max_playback);
    bool pass = engine->looper_length == record_frames && max_playback > 0.1f;
    printf("Looper test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_voice_coupling() {
    printf("\n=== Test: Voice coupling ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);

    engine->voice_params[1].active.store(1);
    engine->voice_params[1].tune.store(67.0f);
    engine->voice_params[1].gate.store(0);
    engine->voice_params[1].ever_triggered.store(1);
    engine->voice_params[1].engine_index.store(-1);

    engine->coupling_depth.store(0.5f);

    GraphUnit v0_unit = {};
    v0_unit.type = UNIT_PLAITS;
    v0_unit.enabled = true;
    v0_unit.state.module.index = 0;
    unit_init(&v0_unit, 48000.0f);

    for (int i = 0; i < 200; i++) {
        unit_process_plaits(&v0_unit, engine, 128, 48000.0f);
    }

    float env0 = engine->voice_envelope[0];
    float level0 = engine->voice_levels[0].load(std::memory_order_relaxed);
    printf("Voice 0 envelope: %.4f (voice_level: %.4f)\n", env0, level0);
    bool pass = env0 > 0.001f;
    printf("Coupling test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_fm_modulation() {
    printf("\n=== Test: FM modulation ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);

    engine->voice_params[1].active.store(1);
    engine->voice_params[1].tune.store(67.0f);
    engine->voice_params[1].gate.store(1);
    engine->voice_params[1].ever_triggered.store(1);
    engine->voice_params[1].engine_index.store(-1);

    engine->mod_source[0].store(1); // VOICE_FM
    engine->fm_depth[0].store(0.5f);

    GraphUnit v0 = {}, v1 = {};
    v0.type = UNIT_PLAITS; v0.enabled = true;
    v1.type = UNIT_PLAITS; v1.enabled = true;
    unit_init(&v0, 48000.0f);
    unit_init(&v1, 48000.0f);
    v0.state.module.index = 0;
    v1.state.module.index = 1;

    for (int i = 0; i < 10; i++) {
        unit_process_plaits(&v0, engine, 128, 48000.0f);
        unit_process_plaits(&v1, engine, 128, 48000.0f);
    }

    float out0 = engine->voice_last_output[0];
    float out1 = engine->voice_last_output[1];
    printf("Voice 0 last output: %.4f, Voice 1: %.4f\n", out0, out1);
    bool pass = out0 > 0.001f && out1 > 0.001f;
    printf("FM modulation test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_bender() {
    printf("\n=== Test: Bender CV + audio ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit bender_unit = {};
    bender_unit.type = UNIT_BENDER;
    bender_unit.enabled = true;
    unit_init(&bender_unit, 48000.0f);

    engine->bend_amount.store(0.5f);

    float max_pitch = 0.0f, max_audio = 0.0f;
    for (int offset = 0; offset < 48000; offset += 128) {
        int chunk = std::min(128, 48000 - offset);
        unit_process_bender(&bender_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float p = std::fabs(bender_unit.output_buffers[OPORT_OUT][i]);
            float a = std::fabs(bender_unit.output_buffers[OPORT_AUX][i]);
            if (p > max_pitch) max_pitch = p;
            if (a > max_audio) max_audio = a;
        }
    }

    printf("Max pitch CV: %.4f, Max audio: %.4f\n", max_pitch, max_audio);
    bool pass = max_pitch > 0.01f && max_audio > 0.0001f;
    printf("Bender test: %s\n", pass ? "PASS" : "FAIL");

    engine->bend_amount.store(0.0f);
    float max_spring = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_bender(&bender_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(bender_unit.output_buffers[OPORT_AUX][i]);
            if (a > max_spring) max_spring = a;
        }
    }
    printf("Max spring audio after release: %.4f\n", max_spring);
    bool spring_pass = max_spring > 0.0001f;
    printf("Spring test: %s\n", spring_pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass && spring_pass;
}

static bool test_per_string_bender() {
    printf("\n=== Test: Per-string bender ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit psb_unit = {};
    psb_unit.type = UNIT_PER_STRING_BENDER;
    psb_unit.enabled = true;
    unit_init(&psb_unit, 48000.0f);

    engine->string_active[0].store(1);
    engine->string_bend[0].store(0.5f);
    engine->string_mix[0].store(0.5f);

    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_per_string_bender(&psb_unit, engine, chunk, 48000.0f);
    }

    float bend_cv = engine->voice_bend_cv[0];
    float mix_cv = engine->voice_mix_cv[0];
    printf("Voice 0 bend CV: %.4f semitones\n", bend_cv);
    printf("Voice 0 mix CV: %.4f\n", mix_cv);

    engine->string_active[0].store(0);
    float max_audio = 0.0f;
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_per_string_bender(&psb_unit, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(psb_unit.output_buffers[OPORT_OUT][i]);
            if (a > max_audio) max_audio = a;
        }
    }

    printf("Max audio after release: %.4f\n", max_audio);
    bool pass = std::fabs(bend_cv) > 0.1f && mix_cv >= 0.99f && max_audio > 0.001f;
    printf("Per-string bender test: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

bool run_unit_tests() {
    bool all_pass = true;
    all_pass &= test_voice_coupling();
    all_pass &= test_fm_modulation();
    all_pass &= test_clock();
    all_pass &= test_grids();
    all_pass &= test_marbles();
    all_pass &= test_looper();
    all_pass &= test_bender();
    all_pass &= test_per_string_bender();
    return all_pass;
}
