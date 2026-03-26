// Effects bypass passthrough + active processing tests
#include "test_harness.h"

static bool test_effects_bypass_passthrough() {
    printf("\n=== Test: Effects bypass passthrough ===\n");
    bool all_pass = true;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const int test_frames = 4800;
    const float freq = 440.0f;

    auto gen_sine = [&](GraphPort& port, int offset, int chunk) {
        for (int i = 0; i < chunk; i++) {
            float t = (float)(offset + i) / 48000.0f;
            port.buffer[i] = std::sin(t * freq * 2.0f * 3.14159265f) * 0.3f;
        }
    };

    auto check_bypass = [&](const char* name, auto process_fn, auto setup_fn) {
        GraphUnit u = {};
        setup_fn(u);
        float in_rms = 0.0f, out_rms = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            for (int i = 0; i < chunk; i++) {
                float t = (float)(offset + i) / 48000.0f;
                float val = std::sin(t * freq * 2.0f * 3.14159265f) * 0.3f;
                in_rms += val * val;
            }
            process_fn(u, engine, offset, chunk);
            for (int i = 0; i < chunk; i++)
                out_rms += u.output_buffers[OPORT_OUT][i] * u.output_buffers[OPORT_OUT][i];
        }
        in_rms = std::sqrt(in_rms / test_frames);
        out_rms = std::sqrt(out_rms / test_frames);
        float ratio = (in_rms > 0.001f) ? out_rms / in_rms : 0.0f;
        bool pass = ratio > 0.9f && ratio < 1.1f;
        printf("  %s bypass: in_rms=%.4f out_rms=%.4f ratio=%.3f %s\n",
               name, in_rms, out_rms, ratio, pass ? "OK" : "FAIL");
        return pass;
    };

    engine->clouds_bypass.store(1);
    all_pass &= check_bypass("Clouds",
        [&](GraphUnit& u, OrpheusEngine* e, int offset, int chunk) {
            gen_sine(u.inputs[IPORT_INPUT_A], offset, chunk);
            gen_sine(u.inputs[IPORT_INPUT_B], offset, chunk);
            unit_process_clouds(&u, e, chunk, 48000.0f);
        },
        [](GraphUnit& u) { u.type = UNIT_CLOUDS; u.enabled = true; unit_init(&u, 48000.0f); }
    );

    engine->rings_bypass.store(1);
    all_pass &= check_bypass("Rings",
        [&](GraphUnit& u, OrpheusEngine* e, int offset, int chunk) {
            gen_sine(u.inputs[IPORT_INPUT], offset, chunk);
            unit_process_rings(&u, e, chunk, 48000.0f);
        },
        [](GraphUnit& u) { u.type = UNIT_RINGS; u.enabled = true; unit_init(&u, 48000.0f); }
    );

    // Warps is a parallel mix effect (not inline) — bypass outputs silence, not passthrough.
    // Verify it produces silence when warps_smooth_mix == 0 (default).
    {
        GraphUnit u = {};
        u.type = UNIT_WARPS; u.enabled = true; unit_init(&u, 48000.0f);
        gen_sine(u.inputs[IPORT_INPUT_A], 0, 128);
        gen_sine(u.inputs[IPORT_INPUT_B], 0, 128);
        unit_process_warps(&u, engine, 128, 48000.0f);
        float out_peak = compute_peak(u.output_buffers[OPORT_OUT], 128);
        bool pass = out_peak < 0.0001f;
        printf("  Warps bypass (parallel mix → silence): peak=%.6f %s\n", out_peak, pass ? "OK" : "FAIL");
        all_pass &= pass;
    }

    engine->delay_bypass.store(1);
    all_pass &= check_bypass("Delay",
        [&](GraphUnit& u, OrpheusEngine* e, int offset, int chunk) {
            gen_sine(u.inputs[IPORT_INPUT_A], offset, chunk);
            gen_sine(u.inputs[IPORT_INPUT_B], offset, chunk);
            std::memset(u.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));
            unit_process_dual_delay(&u, e, chunk, 48000.0f);
        },
        [](GraphUnit& u) { u.type = UNIT_DUAL_DELAY; u.enabled = true; unit_init(&u, 48000.0f); }
    );

    engine->reverb_bypass.store(1);
    {
        GraphUnit u = {};
        u.type = UNIT_REVERB; u.enabled = true;
        unit_init(&u, 48000.0f);
        float out_peak = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            gen_sine(u.inputs[IPORT_INPUT_A], offset, chunk);
            gen_sine(u.inputs[IPORT_INPUT_B], offset, chunk);
            unit_process_reverb(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > out_peak) out_peak = a;
            }
        }
        bool pass = out_peak < 0.001f;
        printf("  Reverb bypass: out_peak=%.6f %s\n", out_peak, pass ? "OK (silent)" : "FAIL");
        all_pass &= pass;
    }

    printf("Effects bypass test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

static bool test_effects_active() {
    printf("\n=== Test: Effects active processing ===\n");
    bool all_pass = true;
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const int test_frames = 12000;

    // Clouds active
    {
        engine->clouds_bypass.store(0);
        engine->clouds_dry_wet.store(1.0f);
        engine->clouds_position.store(0.5f);
        engine->clouds_size.store(0.5f);
        engine->clouds_density.store(0.5f);
        engine->clouds_texture.store(0.5f);
        GraphUnit u = {};
        u.type = UNIT_CLOUDS; u.enabled = true;
        unit_init(&u, 48000.0f);
        float out_rms = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            for (int i = 0; i < chunk; i++) {
                float t = (float)(offset + i) / 48000.0f;
                float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
                u.inputs[IPORT_INPUT_A].buffer[i] = val;
                u.inputs[IPORT_INPUT_B].buffer[i] = val;
            }
            unit_process_clouds(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++)
                out_rms += u.output_buffers[OPORT_OUT][i] * u.output_buffers[OPORT_OUT][i];
        }
        out_rms = std::sqrt(out_rms / test_frames);
        bool pass = out_rms > 0.001f;
        printf("  Clouds active: rms=%.4f %s\n", out_rms, pass ? "OK" : "FAIL");
        all_pass &= pass;
    }

    // Reverb active
    {
        engine->reverb_bypass.store(0);
        engine->reverb_amount.store(0.5f);
        engine->reverb_time.store(0.7f);
        GraphUnit u = {};
        u.type = UNIT_REVERB; u.enabled = true;
        unit_init(&u, 48000.0f);
        float peak_during = 0.0f, peak_tail = 0.0f;
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            for (int i = 0; i < chunk; i++) {
                float t = (float)(offset + i) / 48000.0f;
                u.inputs[IPORT_INPUT_A].buffer[i] = std::sin(t * 440.0f * 6.283185f) * 0.3f;
                u.inputs[IPORT_INPUT_B].buffer[i] = u.inputs[IPORT_INPUT_A].buffer[i];
            }
            unit_process_reverb(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > peak_during) peak_during = a;
            }
        }
        for (int offset = 0; offset < test_frames; offset += 128) {
            int chunk = std::min(128, test_frames - offset);
            std::memset(u.inputs[IPORT_INPUT_A].buffer, 0, chunk * sizeof(float));
            std::memset(u.inputs[IPORT_INPUT_B].buffer, 0, chunk * sizeof(float));
            unit_process_reverb(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > peak_tail) peak_tail = a;
            }
        }
        bool pass = peak_during > 0.01f && peak_tail > 0.001f;
        printf("  Reverb active: peak=%.4f tail=%.4f %s\n", peak_during, peak_tail, pass ? "OK" : "FAIL");
        all_pass &= pass;
    }

    // Delay active
    {
        engine->delay_bypass.store(0);
        engine->delay_mix.store(0.5f);
        engine->delay_feedback.store(0.3f);
        engine->delay_time_1.store(0.1f);
        engine->delay_time_2.store(0.15f);
        GraphUnit u = {};
        u.type = UNIT_DUAL_DELAY; u.enabled = true;
        unit_init(&u, 48000.0f);
        for (int i = 0; i < 128; i++) {
            u.inputs[IPORT_INPUT_A].buffer[i] = (i == 0) ? 0.5f : 0.0f;
            u.inputs[IPORT_INPUT_B].buffer[i] = (i == 0) ? 0.5f : 0.0f;
            u.inputs[IPORT_INPUT_C].buffer[i] = 0.0f;
        }
        unit_process_dual_delay(&u, engine, 128, 48000.0f);
        float echo_peak = 0.0f;
        for (int offset = 0; offset < 24000; offset += 128) {
            int chunk = std::min(128, 24000 - offset);
            std::memset(u.inputs[IPORT_INPUT_A].buffer, 0, chunk * sizeof(float));
            std::memset(u.inputs[IPORT_INPUT_B].buffer, 0, chunk * sizeof(float));
            std::memset(u.inputs[IPORT_INPUT_C].buffer, 0, chunk * sizeof(float));
            unit_process_dual_delay(&u, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) {
                float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
                if (a > echo_peak) echo_peak = a;
            }
        }
        bool pass = echo_peak > 0.01f;
        printf("  Delay echo: peak=%.4f %s\n", echo_peak, pass ? "OK" : "FAIL");
        all_pass &= pass;
    }

    printf("Effects active test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool run_effects_tests() {
    bool all_pass = true;
    all_pass &= test_effects_bypass_passthrough();
    all_pass &= test_effects_active();
    return all_pass;
}
