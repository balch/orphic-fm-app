#include "test_harness.h"
#include <cmath>
#include <cstring>

static bool test_overdrive_passthrough() {
    // At drive=0, the overdrive bypasses and passes input through unchanged.
    printf("\n=== Test: Overdrive at drive=0 passes through clean ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_OVERDRIVE;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    const int N = 128;
    float input[N];
    for (int i = 0; i < N; i++)
        input[i] = 0.5f * sinf(2.0f * M_PI * 440.0f * i / 48000.0f);
    std::memcpy(u.inputs[IPORT_INPUT].buffer, input, N * sizeof(float));
    u.inputs[IPORT_INPUT].num_sources = 1;

    engine->bass_overdrive.store(0.0f);
    engine->bass_accent_drive_boost = 0.0f;

    unit_process_overdrive(&u, engine, N, 48000.0f);

    float diff = 0.0f;
    for (int i = 0; i < N; i++) {
        float d = u.output_buffers[OPORT_OUT][i] - input[i];
        diff += d * d;
    }
    float rms_diff = sqrtf(diff / N);
    printf("  RMS diff at drive=0: %.6f (expect near 0 = clean passthrough)\n", rms_diff);

    bool pass = rms_diff < 0.001f;
    printf("Overdrive passthrough: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_overdrive_distorts() {
    printf("\n=== Test: Overdrive at drive=1.0 distorts signal ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_OVERDRIVE;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    const int N = 128;
    float input[N];
    for (int i = 0; i < N; i++)
        input[i] = 0.5f * sinf(2.0f * M_PI * 440.0f * i / 48000.0f);
    std::memcpy(u.inputs[IPORT_INPUT].buffer, input, N * sizeof(float));
    u.inputs[IPORT_INPUT].num_sources = 1;

    engine->bass_overdrive.store(1.0f);
    engine->bass_accent_drive_boost = 0.0f;

    unit_process_overdrive(&u, engine, N, 48000.0f);

    float diff = 0.0f;
    float out_peak = 0.0f;
    for (int i = 0; i < N; i++) {
        float d = u.output_buffers[OPORT_OUT][i] - input[i];
        diff += d * d;
        float a = fabsf(u.output_buffers[OPORT_OUT][i]);
        if (a > out_peak) out_peak = a;
    }
    float rms_diff = sqrtf(diff / N);
    printf("  RMS diff at drive=1.0: %.6f, peak: %.4f\n", rms_diff, out_peak);

    bool pass = rms_diff > 0.01f && out_peak > 0.01f;
    printf("Overdrive distortion: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_compressor_passthrough() {
    printf("\n=== Test: Compressor at amount=0 passes through ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_COMPRESSOR;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    const int N = 256;
    float input[N];
    for (int i = 0; i < N; i++)
        input[i] = 0.8f * sinf(2.0f * M_PI * 100.0f * i / 48000.0f);
    std::memcpy(u.inputs[IPORT_INPUT].buffer, input, N * sizeof(float));
    u.inputs[IPORT_INPUT].num_sources = 1;

    engine->bass_compressor.store(0.0f);

    unit_process_compressor(&u, engine, N, 48000.0f);

    float diff = 0.0f;
    for (int i = 0; i < N; i++) {
        float d = u.output_buffers[OPORT_OUT][i] - input[i];
        diff += d * d;
    }
    float rms_diff = sqrtf(diff / N);
    printf("  RMS diff at amount=0: %.6f\n", rms_diff);

    bool pass = rms_diff < 0.01f;
    printf("Compressor passthrough: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_compressor_reduces_dynamics() {
    printf("\n=== Test: Compressor at amount=1.0 reduces dynamic range ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Process in chunks of 512 (kMaxFrames). Loud: chunks 0-3 (512*4=2048 samples),
    // quiet: chunks 4-7 (another 2048 samples). Enough time for the 50ms release to decay.
    const int CHUNK = 512;
    const int LOUD_CHUNKS = 4;
    const int QUIET_CHUNKS = 4;

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_COMPRESSOR;
    u.enabled = true;
    unit_init(&u, 48000.0f);
    u.inputs[IPORT_INPUT].num_sources = 1;

    engine->bass_compressor.store(1.0f);

    // Capture RMS of loud and quiet output sections
    float sum_loud_out = 0.0f, sum_quiet_out = 0.0f;

    // Process loud chunks
    for (int c = 0; c < LOUD_CHUNKS; c++) {
        for (int i = 0; i < CHUNK; i++) {
            int t = c * CHUNK + i;
            u.inputs[IPORT_INPUT].buffer[i] = 0.9f * sinf(2.0f * M_PI * 100.0f * t / 48000.0f);
        }
        unit_process_compressor(&u, engine, CHUNK, 48000.0f);
        float rms = compute_rms(u.output_buffers[OPORT_OUT], CHUNK);
        sum_loud_out += rms * rms;
    }

    // Process quiet chunks
    for (int c = 0; c < QUIET_CHUNKS; c++) {
        for (int i = 0; i < CHUNK; i++) {
            int t = (LOUD_CHUNKS + c) * CHUNK + i;
            u.inputs[IPORT_INPUT].buffer[i] = 0.1f * sinf(2.0f * M_PI * 100.0f * t / 48000.0f);
        }
        unit_process_compressor(&u, engine, CHUNK, 48000.0f);
        float rms = compute_rms(u.output_buffers[OPORT_OUT], CHUNK);
        sum_quiet_out += rms * rms;
    }

    float rms_loud_out = sqrtf(sum_loud_out / LOUD_CHUNKS);
    float rms_quiet_out = sqrtf(sum_quiet_out / QUIET_CHUNKS);
    float ratio_in = 9.0f;  // input loud/quiet = 0.9 / 0.1
    float ratio_out = (rms_quiet_out > 0.0001f) ? rms_loud_out / rms_quiet_out : ratio_in;

    printf("  Input dynamic ratio: %.2f, Output dynamic ratio: %.2f\n", ratio_in, ratio_out);

    bool pass = ratio_out < ratio_in;
    printf("Compressor dynamics: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_bass_voice_produces_audio() {
    printf("\n=== Test: Bass voice produces audio when mix > 0 ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->bass_mix.store(1.0f);
    engine->bass_bypass.store(0);
    engine->bass_root_note.store(36);
    engine->bass_scale.store(1);
    engine->bass_step_count.store(4);
    engine->bass_mutation.store(0.0f);
    engine->bass_envelope.store(0.5f);
    engine->bass_engine.store(0);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_BASS_VOICE;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    float peak = 0.0f;
    for (int i = 0; i < 48000; i += 128) {
        unit_process_bass_voice(&u, engine, 128, 48000.0f);
        for (int j = 0; j < 128; j++) {
            float a = fabsf(u.output_buffers[OPORT_OUT][j]);
            if (a > peak) peak = a;
        }
    }

    printf("  Peak output: %.6f\n", peak);
    bool pass = peak > 0.001f;
    printf("Bass voice audio: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_bass_voice_silent_when_bypassed() {
    printf("\n=== Test: Bass voice silent when mix=0 ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->bass_mix.store(0.0f);
    engine->bass_bypass.store(1);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_BASS_VOICE;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    unit_process_bass_voice(&u, engine, 128, 48000.0f);

    float peak = compute_peak(u.output_buffers[OPORT_OUT], 128);
    printf("  Peak when bypassed: %.6f\n", peak);
    bool pass = peak < 0.0001f;
    printf("Bass voice bypass: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_bass_voice_full_graph() {
    printf("\n=== Test: Bass voice in full production graph ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_production_graph(engine)) {
        printf("SKIP: production graph not available (build app first)\n");
        orpheus_engine_destroy(engine);
        return true; // skip, don't fail
    }

    // Enable bass voice (overdrive > 0 required — at drive=0 the MI algorithm mutes)
    engine->bass_mix.store(1.0f);
    engine->bass_bypass.store(0);
    engine->bass_root_note.store(36);
    engine->bass_engine.store(0); // VCF Acid
    engine->bass_overdrive.store(0.5f);
    engine->bass_params.timbre.store(0.5f);
    engine->bass_params.harmonics.store(0.5f);
    engine->bass_params.gate.store(1);
    engine->bass_params.tune.store(36.0f);
    engine->bass_params.accent.store(0.8f);

    auto r = render_engine(engine, 24000, 5);
    printf("  Peak: %.4f RMS_L: %.4f RMS_R: %.4f\n", r.peak, r.rms_l, r.rms_r);

    float bass_level = engine->bass_voice_level.load();
    printf("  Bass voice level: %.4f\n", bass_level);

    bool pass = r.peak > 0.001f;
    printf("Bass voice full graph: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool run_bass_voice_tests() {
    bool all_pass = true;
    all_pass &= test_overdrive_passthrough();
    all_pass &= test_overdrive_distorts();
    all_pass &= test_compressor_passthrough();
    all_pass &= test_compressor_reduces_dynamics();
    all_pass &= test_bass_voice_produces_audio();
    all_pass &= test_bass_voice_silent_when_bypassed();
    all_pass &= test_bass_voice_full_graph();
    return all_pass;
}
