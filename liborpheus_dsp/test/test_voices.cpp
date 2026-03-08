// Voice engine behavior: polyphonic, lifecycle, gate retrigger, hold, idle detection
#include "test_harness.h"

static bool test_single_voice_engine0() {
    printf("\n=== Test: Single voice Engine 0 (VA) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    float amp = render_voice(&v0, engine, 12000);
    printf("  Engine 0 gate=ON: peak=%.4f %s\n", amp, amp > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp > 0.01f);

    engine->voice_params[0].gate.store(0);
    float release_amp = render_voice(&v0, engine, 48000);
    printf("  Engine 0 after 1s release: peak=%.6f\n", release_amp);

    engine->voice_params[0].gate.store(1);
    float retrigger_amp = render_voice(&v0, engine, 12000);
    printf("  Engine 0 re-trigger: peak=%.4f %s\n", retrigger_amp, retrigger_amp > 0.01f ? "OK" : "FAIL");
    all_pass &= (retrigger_amp > 0.01f);

    printf("Single voice Engine 0 test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_single_voice_plaits_engines() {
    printf("\n=== Test: Single voice Plaits engines ===\n");
    bool all_pass = true;

    int engines_to_test[] = {0, 1, 2, 6, 8};
    int num_engines = sizeof(engines_to_test) / sizeof(engines_to_test[0]);

    for (int e = 0; e < num_engines; e++) {
        int eng = engines_to_test[e];
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);

        engine->voice_params[0].active.store(1);
        engine->voice_params[0].ever_triggered.store(1);
        engine->voice_params[0].engine_index.store(eng);
        engine->voice_params[0].tune.store(60.0f);
        engine->voice_params[0].gate.store(1);
        engine->voice_params[0].harmonics.store(0.5f);
        engine->voice_params[0].timbre.store(0.5f);
        engine->voice_params[0].morph.store(0.5f);
        engine->voice_params[0].decay.store(0.5f);

        GraphUnit v0;
        setup_voice_unit(&v0, 0);

        float amp = render_voice(&v0, engine, 24000);
        bool pass = amp > 0.001f;
        printf("  Plaits engine %2d: peak=%.4f %s\n", eng, amp, pass ? "OK" : "FAIL");
        all_pass &= pass;

        orpheus_engine_destroy(engine);
    }

    printf("Plaits engines test: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

static bool test_polyphonic_voices() {
    printf("\n=== Test: Polyphonic 8 voices ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};
    GraphUnit units[kNumMainVoices];

    for (int v = 0; v < kNumMainVoices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(-1);
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        setup_voice_unit(&units[v], v);
    }

    float voice_peaks[kNumMainVoices] = {};
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        for (int v = 0; v < kNumMainVoices; v++) {
            unit_process_plaits(&units[v], engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(units[v].output_buffers[OPORT_OUT][i]);
                if (a > voice_peaks[v]) voice_peaks[v] = a;
            }
        }
    }

    int silent_count = 0;
    for (int v = 0; v < kNumMainVoices; v++) {
        bool ok = voice_peaks[v] > 0.01f;
        if (!ok) silent_count++;
        printf("  Voice %d (note %.0f): peak=%.4f %s\n", v, notes[v], voice_peaks[v], ok ? "OK" : "SILENT!");
        all_pass &= ok;
    }

    printf("Polyphonic test: %d/%d voices producing sound — %s\n",
           kNumMainVoices - silent_count, kNumMainVoices, all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_polyphonic_plaits_voices() {
    printf("\n=== Test: Polyphonic 8 Plaits voices ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};
    GraphUnit units[kNumMainVoices];

    for (int v = 0; v < kNumMainVoices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(0);
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.5f);
        setup_voice_unit(&units[v], v);
    }

    float voice_peaks[kNumMainVoices] = {};
    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        for (int v = 0; v < kNumMainVoices; v++) {
            unit_process_plaits(&units[v], engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(units[v].output_buffers[OPORT_OUT][i]);
                if (a > voice_peaks[v]) voice_peaks[v] = a;
            }
        }
    }

    int silent_count = 0;
    for (int v = 0; v < kNumMainVoices; v++) {
        bool ok = voice_peaks[v] > 0.001f;
        if (!ok) silent_count++;
        printf("  Voice %d (Plaits, note %.0f): peak=%.4f %s\n", v, notes[v], voice_peaks[v], ok ? "OK" : "SILENT!");
        all_pass &= ok;
    }

    printf("Polyphonic Plaits test: %d/%d voices — %s\n",
           kNumMainVoices - silent_count, kNumMainVoices, all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_voice_gate_retrigger() {
    printf("\n=== Test: Voice gate retrigger cycle ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.3f);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    for (int cycle = 0; cycle < 3; cycle++) {
        engine->voice_params[0].gate.store(1);
        float on_amp = render_voice(&v0, engine, 12000);
        engine->voice_params[0].gate.store(0);
        render_voice(&v0, engine, 48000);

        float idle_level = engine->voice_levels[0].load(std::memory_order_relaxed);
        bool on_pass = on_amp > 0.001f;
        printf("  Cycle %d: gate-ON peak=%.4f, idle level=%.6f %s\n",
               cycle, on_amp, idle_level, on_pass ? "OK" : "FAIL");
        all_pass &= on_pass;
    }

    printf("Gate retrigger test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_voice_hold_without_gate() {
    printf("\n=== Test: Voice hold (no gate) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(0);
    engine->voice_hold_level[0].store(0.8f);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    float amp = render_voice(&v0, engine, 24000);
    printf("  Engine 0 hold=0.8 no gate: peak=%.4f %s\n", amp, amp > 0.01f ? "OK" : "FAIL");
    bool pass = amp > 0.01f;

    OrpheusEngine* engine2 = orpheus_engine_create(48000.0f);
    engine2->voice_params[0].active.store(1);
    engine2->voice_params[0].ever_triggered.store(1);
    engine2->voice_params[0].engine_index.store(0);
    engine2->voice_params[0].tune.store(60.0f);
    engine2->voice_params[0].gate.store(0);
    engine2->voice_params[0].harmonics.store(0.5f);
    engine2->voice_params[0].timbre.store(0.5f);
    engine2->voice_params[0].morph.store(0.5f);
    engine2->voice_params[0].decay.store(0.5f);
    engine2->voice_hold_level[0].store(0.8f);

    GraphUnit v0p;
    setup_voice_unit(&v0p, 0);

    float amp2 = render_voice(&v0p, engine2, 24000);
    printf("  Plaits hold=0.8 no gate: peak=%.4f %s\n", amp2, amp2 > 0.001f ? "OK" : "FAIL");
    pass &= (amp2 > 0.001f);

    printf("Hold without gate test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    orpheus_engine_destroy(engine2);
    return pass;
}

static bool test_voice_activation_lifecycle() {
    printf("\n=== Test: Voice activation lifecycle ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    engine->voice_params[0].active.store(0);
    engine->voice_params[0].gate.store(1);
    float amp_inactive = render_voice(&v0, engine, 6000);
    printf("  Not active + gate=ON: peak=%.6f %s\n", amp_inactive,
           amp_inactive < 0.001f ? "OK (silent)" : "FAIL (unexpected sound)");
    all_pass &= (amp_inactive < 0.001f);

    orpheus_engine_set_voice_active(engine, 0, 1);
    engine->voice_params[0].engine_index.store(-1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    float amp_activated = render_voice(&v0, engine, 12000);
    printf("  After set_voice_active + gate=ON: peak=%.4f %s\n", amp_activated,
           amp_activated > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_activated > 0.01f);

    orpheus_engine_set_voice_active(engine, 0, 0);
    float amp_deactivated = render_voice(&v0, engine, 6000);
    printf("  After deactivate: peak=%.6f %s\n", amp_deactivated,
           amp_deactivated < 0.001f ? "OK (silent)" : "FAIL");
    all_pass &= (amp_deactivated < 0.001f);

    orpheus_engine_set_voice_active(engine, 0, 1);
    engine->voice_params[0].gate.store(1);
    float amp_reactivated = render_voice(&v0, engine, 12000);
    printf("  After reactivate + gate=ON: peak=%.4f %s\n", amp_reactivated,
           amp_reactivated > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_reactivated > 0.01f);

    printf("Activation lifecycle test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_engine_switch_while_playing() {
    printf("\n=== Test: Engine switch while voice active ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.5f);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    engine->voice_params[0].engine_index.store(-1);
    float amp_e0 = render_voice(&v0, engine, 12000);
    printf("  Engine 0: peak=%.4f %s\n", amp_e0, amp_e0 > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_e0 > 0.01f);

    orpheus_engine_set_voice_engine(engine, 0, 0);
    float amp_p0 = render_voice(&v0, engine, 24000);
    printf("  Switch to Plaits 0: peak=%.4f %s\n", amp_p0, amp_p0 > 0.001f ? "OK" : "FAIL");
    all_pass &= (amp_p0 > 0.001f);

    orpheus_engine_set_voice_engine(engine, 0, 2);
    float amp_p2 = render_voice(&v0, engine, 24000);
    printf("  Switch to Plaits 2 (FM): peak=%.4f %s\n", amp_p2, amp_p2 > 0.001f ? "OK" : "FAIL");
    all_pass &= (amp_p2 > 0.001f);

    orpheus_engine_set_voice_engine(engine, 0, -1);
    float amp_back = render_voice(&v0, engine, 12000);
    printf("  Back to Engine 0: peak=%.4f %s\n", amp_back, amp_back > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_back > 0.01f);

    printf("Engine switch test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_idle_detection_recovery() {
    printf("\n=== Test: Idle detection recovery ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.2f);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    engine->voice_params[0].gate.store(1);
    render_voice(&v0, engine, 12000);
    engine->voice_params[0].gate.store(0);
    render_voice(&v0, engine, 96000);

    float idle_level = engine->voice_levels[0].load(std::memory_order_relaxed);
    printf("  After 2s decay, voice_level=%.8f (idle=%s)\n",
           idle_level, idle_level < 0.0001f ? "yes" : "no");

    engine->voice_params[0].gate.store(1);
    float recovery_amp = render_voice(&v0, engine, 24000);
    bool recover_pass = recovery_amp > 0.001f;
    printf("  Recovery after idle: peak=%.4f %s\n", recovery_amp, recover_pass ? "OK" : "FAIL");
    all_pass &= recover_pass;

    OrpheusEngine* engine2 = orpheus_engine_create(48000.0f);
    engine2->voice_params[0].active.store(1);
    engine2->voice_params[0].ever_triggered.store(1);
    engine2->voice_params[0].engine_index.store(-1);
    engine2->voice_params[0].tune.store(60.0f);

    GraphUnit v0e;
    setup_voice_unit(&v0e, 0);

    engine2->voice_params[0].gate.store(1);
    render_voice(&v0e, engine2, 12000);
    engine2->voice_params[0].gate.store(0);
    render_voice(&v0e, engine2, 96000);

    float idle2 = engine2->voice_levels[0].load(std::memory_order_relaxed);
    printf("  Engine 0 after 2s decay, voice_level=%.8f\n", idle2);

    engine2->voice_params[0].gate.store(1);
    float recovery2 = render_voice(&v0e, engine2, 12000);
    bool recover2_pass = recovery2 > 0.01f;
    printf("  Engine 0 recovery after idle: peak=%.4f %s\n", recovery2, recover2_pass ? "OK" : "FAIL");
    all_pass &= recover2_pass;

    printf("Idle detection recovery test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    orpheus_engine_destroy(engine2);
    return all_pass;
}

bool run_voice_tests() {
    bool all_pass = true;
    all_pass &= test_single_voice_engine0();
    all_pass &= test_single_voice_plaits_engines();
    all_pass &= test_polyphonic_voices();
    all_pass &= test_polyphonic_plaits_voices();
    all_pass &= test_voice_gate_retrigger();
    all_pass &= test_voice_hold_without_gate();
    all_pass &= test_voice_activation_lifecycle();
    all_pass &= test_engine_switch_while_playing();
    all_pass &= test_idle_detection_recovery();
    return all_pass;
}
