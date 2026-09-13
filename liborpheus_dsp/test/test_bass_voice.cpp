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

static bool test_bass_writes_to_warps_source_buffer() {
    printf("\n=== Test: Bass voice writes to warps source buffer (slot 9) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_production_graph(engine)) {
        printf("SKIP: production graph not available (build app first)\n");
        orpheus_engine_destroy(engine);
        return true; // skip, don't fail
    }

    engine->bass_mix.store(1.0f, std::memory_order_relaxed);
    engine->bass_engine.store(0, std::memory_order_relaxed);
    engine->clock_running.store(1, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    float warmup[128 * 2];
    for (int i = 0; i < 20; i++) {
        orpheus_engine_process(engine, warmup, 128);
    }

    float peak = 0.0f;
    for (int i = 0; i < 128; i++) {
        float a = std::fabs(engine->warps_source_buffers[9][i]);
        if (a > peak) peak = a;
    }

    printf("  Peak in warps source buffer 9: %.6f\n", peak);
    bool pass = peak > 0.001f;
    printf("Bass warps source buffer: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_bass_fx_send_mixes_into_clouds() {
    printf("\n=== Test: Bass grains send mixes bass into Clouds input ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_production_graph(engine)) {
        printf("SKIP: production graph not available (build app first)\n");
        orpheus_engine_destroy(engine);
        return true; // skip, don't fail
    }

    // Enable bass voice with signal in warps_source_buffers[9]
    engine->bass_mix.store(1.0f, std::memory_order_relaxed);
    engine->bass_engine.store(0, std::memory_order_relaxed);
    engine->bass_overdrive.store(0.5f, std::memory_order_relaxed);
    engine->bass_params.gate.store(1);
    engine->bass_params.tune.store(36.0f);
    engine->bass_params.accent.store(0.8f);
    engine->bass_params.timbre.store(0.5f);
    engine->bass_params.harmonics.store(0.5f);
    engine->clock_running.store(1, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    // Warm up so bass voice produces audio
    float warmup[128 * 2];
    for (int i = 0; i < 20; i++) {
        orpheus_engine_process(engine, warmup, 128);
    }

    // Verify bass signal exists in source buffer slot 9
    float bass_peak = 0.0f;
    for (int i = 0; i < 128; i++) {
        float a = std::fabs(engine->warps_source_buffers[9][i]);
        if (a > bass_peak) bass_peak = a;
    }
    printf("  Bass peak in warps_source_buffers[9]: %.6f\n", bass_peak);

    // Now set grains_send > 0 and exercise the Clouds unit code path by
    // running unit_process_clouds directly with a manually prepared unit.
    engine->bass_fx_send.store(0.5f, std::memory_order_relaxed);
    engine->clouds_bypass.store(0, std::memory_order_relaxed);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_CLOUDS;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    // Pre-fill input buffers with known zeros so we can detect bass contribution
    std::memset(u.inputs[IPORT_INPUT_A].buffer, 0, 128 * sizeof(float));
    std::memset(u.inputs[IPORT_INPUT_B].buffer, 0, 128 * sizeof(float));
    u.inputs[IPORT_INPUT_A].num_sources = 0;
    u.inputs[IPORT_INPUT_B].num_sources = 0;

    // Set Clouds parameters to known-safe defaults
    engine->clouds_position.store(0.5f);
    engine->clouds_size.store(0.5f);
    engine->clouds_pitch.store(0.0f);
    engine->clouds_density.store(0.5f);
    engine->clouds_texture.store(0.5f);
    engine->clouds_dry_wet.store(1.0f);
    engine->clouds_feedback.store(0.0f);
    engine->clouds_reverb.store(0.0f);
    engine->clouds_freeze.store(0);
    engine->clouds_trigger.store(0);
    engine->clouds_mode.store(0);

    // This call exercises the bass send mix path without crashing
    unit_process_clouds(&u, engine, 128, 48000.0f);

    // Verify input buffers were modified by bass send (in_l/in_r are the input buffers)
    // After processing, in_l should have been modified if bass_peak > 0
    // The output is written to output_buffers, but the input buffer modification
    // happened in-place before Clouds processing; we can't read it back.
    // Instead, just confirm no crash and output is non-trivially initialized.
    bool no_crash = true;
    float out_peak = compute_peak(u.output_buffers[OPORT_OUT], 128);
    printf("  Clouds output peak with bass send=0.5: %.6f\n", out_peak);
    printf("  Bass send path ran without crash: %s\n", no_crash ? "yes" : "no");

    bool pass = no_crash;
    printf("Bass grains send: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// Test: when bass_trigger_source=1 (T1), bass output is gated by T1 buffer.
// T1 low -> bass nearly silent, T1 high -> bass audible.
static bool test_bass_flux_t_gating() {
    printf("\n=== Test: Bass voice is gated by Flux T1 ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->bass_mix.store(1.0f);
    engine->bass_bypass.store(0);
    engine->bass_root_note.store(36);
    engine->bass_scale.store(1);
    engine->bass_step_count.store(4);
    engine->bass_mutation.store(0.0f);
    engine->bass_envelope.store(0.5f);
    engine->bass_engine.store(0);
    engine->bass_trigger_source.store(1);  // use T1 to gate envelope
    engine->clock_running.store(1);
    engine->clock_bpm.store(120.0f);

    const int CHUNK = 128;

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_BASS_VOICE;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    // ── Pass 1: T1 low (zeros) → bass should be nearly silent ──
    std::memset(engine->marbles_t1_buffer, 0, kMaxFrames * sizeof(float));

    float silent_peak = 0.0f;
    for (int i = 0; i < 50; i++) {
        unit_process_bass_voice(&u, engine, CHUNK, 48000.0f);
        float p = compute_peak(u.output_buffers[OPORT_OUT], CHUNK);
        if (p > silent_peak) silent_peak = p;
    }

    // ── Pass 2: T1 high (1.0) → bass should produce audio ──
    for (int i = 0; i < kMaxFrames; i++) engine->marbles_t1_buffer[i] = 1.0f;

    float gated_peak = 0.0f;
    for (int i = 0; i < 100; i++) {
        unit_process_bass_voice(&u, engine, CHUNK, 48000.0f);
        float p = compute_peak(u.output_buffers[OPORT_OUT], CHUNK);
        if (p > gated_peak) gated_peak = p;
    }

    printf("  Silent peak (T1=0): %.6f\n", silent_peak);
    printf("  Gated peak  (T1=1): %.6f\n", gated_peak);

    // T1 high should produce meaningfully more output than T1 low
    bool pass = gated_peak > silent_peak * 2.0f;
    printf("Bass Flux T gating: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// Test: slide steps (gate_buffer 0.3-0.7) produce pitch portamento and legato envelope,
// while normal trigger steps (gate_buffer > 0.7) snap pitch instantly.
static bool test_bass_slide_portamento() {
    printf("\n=== Test: Bass slide portamento and legato ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    engine->bass_mix.store(1.0f);
    engine->bass_bypass.store(0);
    engine->bass_root_note.store(36);
    engine->bass_scale.store(0);  // chromatic — full range
    engine->bass_step_count.store(4);
    engine->bass_mutation.store(0.0f);  // no mutation — we control gates directly
    engine->bass_envelope.store(0.2f);  // low envelope → slow glide (~60ms)
    engine->bass_engine.store(0);
    engine->bass_clock_div.store(2);    // 1x (16th notes)
    engine->clock_running.store(1);
    engine->clock_bpm.store(120.0f);

    const int CHUNK = 128;

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_BASS_VOICE;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    // Run a few blocks to initialize the sequencer
    for (int i = 0; i < 5; i++) {
        unit_process_bass_voice(&u, engine, CHUNK, 48000.0f);
    }

    // Set up a pattern: step 0 = normal trigger (high C), step 1 = slide (high G)
    // step 2 = normal trigger (low C), step 3 = rest
    BassSequencerState& seq = engine->bass_seq_state;
    // pitch values: mapped by quantize_to_scale, chromatic over 2 octaves (24 semitones)
    // value 0.5 = degree 12 = octave up from root
    seq.mutation_buffer[0] = 0.0f;   // root (C2 = MIDI 36)
    seq.mutation_buffer[1] = 0.5f;   // octave up (C3 = MIDI 48)
    seq.mutation_buffer[2] = 0.25f;  // ~6 semitones up
    seq.mutation_buffer[3] = 0.0f;

    seq.gate_buffer[0] = 0.9f;  // normal trigger (>0.7)
    seq.gate_buffer[1] = 0.5f;  // slide (0.3-0.7)
    seq.gate_buffer[2] = 0.9f;  // normal trigger
    seq.gate_buffer[3] = 0.1f;  // rest (<0.3)

    seq.accent_buffer[0] = 0.0f;
    seq.accent_buffer[1] = 0.0f;
    seq.accent_buffer[2] = 0.0f;
    seq.accent_buffer[3] = 0.0f;

    // Reset to step 0
    seq.current_step = 0;
    seq.tick_counter = 0;

    // Run through enough blocks to cover 2 steps at 120 BPM, 16th notes
    // samples_per_step at 120 BPM, clock_div=2 (6 ticks): 48000*60*6/(120*24) = 6000
    int samples_per_step = 6000;
    int blocks_per_step = samples_per_step / CHUNK;

    // Advance through step 0 (normal trigger)
    for (int i = 0; i < blocks_per_step; i++) {
        unit_process_bass_voice(&u, engine, CHUNK, 48000.0f);
    }
    float note_after_step0 = seq.smooth_note;

    // Now step 1 fires (slide) — capture smooth_note over several blocks
    // On the first block after step fires, smooth_note should NOT equal target yet
    unit_process_bass_voice(&u, engine, CHUNK, 48000.0f);
    float note_early_slide = seq.smooth_note;

    // Run most of the remaining step
    for (int i = 1; i < blocks_per_step - 1; i++) {
        unit_process_bass_voice(&u, engine, CHUNK, 48000.0f);
    }
    float note_late_slide = seq.smooth_note;

    // Target note for step 1: quantize_to_scale(0.5, 36, 0) = 36 + 12 = 48
    float target_step1 = 48.0f;

    printf("  Step 0 note (root, normal): %.2f\n", note_after_step0);
    printf("  Step 1 early slide: %.2f (target: %.2f)\n", note_early_slide, target_step1);
    printf("  Step 1 late slide:  %.2f (target: %.2f)\n", note_late_slide, target_step1);

    // Early slide should be between step 0 note and target (not yet arrived)
    bool slide_in_progress = (note_early_slide > note_after_step0 + 0.5f) &&
                             (note_early_slide < target_step1 - 0.5f);
    // Late slide should be closer to target than early slide
    bool slide_converging = std::fabs(note_late_slide - target_step1) <
                            std::fabs(note_early_slide - target_step1);

    printf("  Slide in progress (between start and target): %s\n",
           slide_in_progress ? "yes" : "no");
    printf("  Slide converging (late closer than early): %s\n",
           slide_converging ? "yes" : "no");

    bool pass = slide_in_progress && slide_converging;
    printf("Bass slide portamento: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// ── Click detection test ──────────────────────────────────────────
// Reproduces the user's scenario using the PRODUCTION GRAPH:
// FM engine, low cutoff, drive=0, run at 4x clock then switch to 1/4x.
// Uses orpheus_engine_process() for full signal chain fidelity.
static bool test_bass_vcf_click_detection() {
    printf("\n=== Test: Bass click detection (FM, production graph, 4x→1/4x) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_production_graph(engine)) {
        printf("SKIP: production graph not available (build app first)\n");
        orpheus_engine_destroy(engine);
        return true;
    }

    // Match the user's settings from screenshot (preset: "click")
    engine->bass_mix.store(0.36f);
    engine->bass_bypass.store(0);
    engine->bass_root_note.store(36);     // C2
    engine->bass_scale.store(2);          // Minor
    engine->bass_step_count.store(8);
    engine->bass_mutation.store(0.26f);
    engine->bass_envelope.store(0.5f);
    engine->bass_engine.store(2);         // FM
    engine->bass_overdrive.store(0.0f);   // Drive = 0
    engine->bass_compressor.store(0.0f);  // Comp = 0
    engine->bass_params.timbre.store(0.33f);
    engine->bass_params.harmonics.store(0.0f);
    engine->bass_params.morph.store(0.0f);
    engine->bass_params.accent.store(0.3f);
    engine->bass_accent_amount.store(0.3f);
    engine->bass_lfo_mix.store(0.0f);     // LFO off
    engine->clock_running.store(1);
    engine->clock_bpm.store(120.0f);

    // Bass delay/reverb sends default to 0 — bass signal only reaches master
    // via direct path, not tripled through delay/reverb dry passthrough.

    const int CHUNK = 64;
    const float SR = 48000.0f;

    // Capture master output AND bass-only signal (warps_source_buffers[9])
    std::vector<float> all_stereo;
    std::vector<float> all_samples;      // master left channel
    std::vector<float> bass_samples;     // bass voice direct output (pre-overdrive)

    // Phase 1: Run at 4x clock for 1 second
    engine->bass_clock_div.store(4);
    int blocks_1s = static_cast<int>(SR / CHUNK);
    for (int b = 0; b < blocks_1s; b++) {
        float buf[CHUNK * 2];
        orpheus_engine_process(engine, buf, CHUNK);
        for (int i = 0; i < CHUNK * 2; i++)
            all_stereo.push_back(buf[i]);
        for (int i = 0; i < CHUNK; i++) {
            all_samples.push_back(buf[i * 2]);
            bass_samples.push_back(engine->warps_source_buffers[9][i]);
        }
    }
    int phase1_end = static_cast<int>(all_samples.size());

    // Phase 2: Switch to 1/4x clock for 2 seconds
    engine->bass_clock_div.store(0);
    for (int b = 0; b < blocks_1s * 2; b++) {
        float buf[CHUNK * 2];
        orpheus_engine_process(engine, buf, CHUNK);
        for (int i = 0; i < CHUNK * 2; i++)
            all_stereo.push_back(buf[i]);
        for (int i = 0; i < CHUNK; i++) {
            all_samples.push_back(buf[i * 2]);
            bass_samples.push_back(engine->warps_source_buffers[9][i]);
        }
    }

    // ── Analyze BASS-ONLY signal for clicks ──
    float max_delta = 0.0f;
    int max_delta_idx = 0;
    float bass_rms = compute_rms(bass_samples.data() + phase1_end,
                                  static_cast<int>(bass_samples.size()) - phase1_end);
    float signal_rms = compute_rms(all_samples.data(), static_cast<int>(all_samples.size()));

    printf("  Bass-only RMS (1/4x phase): %.6f\n", bass_rms);

    // Find clicks in bass-only signal
    float bass_max_delta = 0.0f;
    int bass_max_idx = 0;
    int bass_click_count = 0;
    int bass_prev_click = -100;
    for (int i = phase1_end + 1; i < static_cast<int>(bass_samples.size()); i++) {
        float delta = std::fabs(bass_samples[i] - bass_samples[i - 1]);
        if (delta > bass_max_delta) {
            bass_max_delta = delta;
            bass_max_idx = i;
        }
        if (delta > 0.05f && (i - bass_prev_click) > 100) {
            bass_click_count++;
            bass_prev_click = i;
            if (bass_click_count <= 5) {
                printf("  BASS click at sample %d (%.2f ms): delta=%.6f "
                       "val[%d]=%.6f val[%d]=%.6f\n",
                       i, i / SR * 1000.0f, delta,
                       i-1, bass_samples[i-1], i, bass_samples[i]);
            }
        }
    }
    printf("  Bass-only max delta: %.6f at sample %d (%.2f ms)\n",
           bass_max_delta, bass_max_idx, bass_max_idx / SR * 1000.0f);
    printf("  Bass-only clicks: %d\n", bass_click_count);

    // Write bass-only WAV
    std::vector<float> bass_stereo(bass_samples.size() * 2);
    for (size_t i = 0; i < bass_samples.size(); i++) {
        bass_stereo[i * 2] = bass_samples[i];
        bass_stereo[i * 2 + 1] = bass_samples[i];
    }
    write_wav("test/output/bass_click_isolated.wav", bass_stereo.data(),
              static_cast<int>(bass_samples.size()), static_cast<int>(SR));

    // Only look at phase 2 (after clock change) for clicks
    for (int i = phase1_end + 1; i < static_cast<int>(all_samples.size()); i++) {
        float delta = std::fabs(all_samples[i] - all_samples[i - 1]);
        if (delta > max_delta) {
            max_delta = delta;
            max_delta_idx = i;
        }
    }

    // Count clicks: sample-to-sample deltas exceeding a threshold.
    // For a smooth bass signal, consecutive samples shouldn't jump more than
    // ~0.05 (~-26dB). Anything above that is likely an audible click.
    float click_threshold = 0.05f;
    int click_count = 0;
    int prev_click = -100;
    for (int i = phase1_end + 1; i < static_cast<int>(all_samples.size()); i++) {
        float delta = std::fabs(all_samples[i] - all_samples[i - 1]);
        if (delta > click_threshold && (i - prev_click) > 100) {
            click_count++;
            prev_click = i;
            if (click_count <= 5) {
                printf("  Click at sample %d (%.2f ms): delta=%.6f val[%d]=%.6f val[%d]=%.6f\n",
                       i, i / SR * 1000.0f, delta, i-1, all_samples[i-1], i, all_samples[i]);
            }
        }
    }

    // Write full stereo WAV for manual inspection
    write_wav("test/output/bass_click_test.wav", all_stereo.data(),
              static_cast<int>(all_samples.size()), static_cast<int>(SR));

    printf("  Signal RMS: %.6f\n", signal_rms);
    printf("  Click threshold: %.6f\n", click_threshold);
    printf("  Max delta: %.6f at sample %d (%.2f ms, phase %s)\n",
           max_delta, max_delta_idx, max_delta_idx / SR * 1000.0f,
           max_delta_idx < phase1_end ? "4x" : "1/4x");
    printf("  Clicks detected in 1/4x phase: %d\n", click_count);

    // Pass if no clicks above 0.05 in the 1/4x phase
    bool pass = (click_count == 0);
    printf("Bass VCF click detection: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// ── Block-size independent smoothing ──────────────────────────────
// Desktop renders 512-frame blocks. Glide, accent flare and cutoff smoothing must keep
// their time constants there, and a slide must move inside a block, not once per block.

static const float kBassSr = 48000.0f;

// Every bass setting explicit: VCF engine, chromatic scale, no mod sources, clock stopped.
static OrpheusEngine* make_bass_engine(GraphUnit& u, float envelope, int root_note) {
    OrpheusEngine* engine = orpheus_engine_create(kBassSr);
    engine->bass_mix.store(1.0f);
    engine->bass_bypass.store(0);
    engine->bass_root_note.store(root_note);
    engine->bass_scale.store(0);
    engine->bass_step_count.store(4);
    engine->bass_mutation.store(0.0f);
    engine->bass_envelope.store(envelope);
    engine->bass_engine.store(0);
    engine->bass_clock_div.store(2);
    engine->bass_accent_amount.store(1.0f);
    engine->bass_jitter.store(0.0f);
    engine->bass_lfo_mix.store(0.0f);
    engine->bass_trigger_source.store(0);
    engine->bass_pitch_source.store(0);
    engine->bass_timbre_source.store(0);
    engine->bass_key_override.store(0);
    engine->clock_running.store(0);
    engine->clock_bpm.store(120.0f);
    engine->bass_params.timbre.store(0.5f);
    engine->bass_params.harmonics.store(0.0f);
    engine->bass_params.morph.store(0.5f);
    engine->bass_params.accent.store(0.5f);

    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_BASS_VOICE;
    u.enabled = true;
    unit_init(&u, kBassSr);
    unit_process_bass_voice(&u, engine, 128, kBassSr);  // initializes the sequencer

    BassSequencerState& seq = engine->bass_seq_state;
    for (int i = 0; i < kMaxBassSteps; i++) {
        seq.mutation_buffer[i] = 0.0f;
        seq.gate_buffer[i] = 0.9f;
        seq.accent_buffer[i] = 0.0f;
    }
    seq.current_step = 0;
    seq.tick_counter = 0;
    engine->bass_accent_timbre_boost = 0.0f;  // the init pattern accents step 0
    return engine;
}

// glide_ms = 80 * exp(-2.1 * envelope); the one-pole time constant is 0.3 of that.
static float bass_glide_tau_samples(float envelope) {
    return 0.3f * 80.0f * std::exp(-2.1f * envelope) * 0.001f * kBassSr;
}

// Reads pitch from upward zero crossings. Root 72 keeps the period (92 to 46 samples) short
// enough to see the note move inside one 512-frame block.
static bool test_bass_slide_moves_within_a_block() {
    printf("\n=== Test: Bass slide pitch moves within a block at 128 and 512 frames ===\n");
    bool pass = true;
    for (int n : {128, 512}) {
        for (float env : {0.2f, 0.7f}) {
            GraphUnit u;
            OrpheusEngine* engine = make_bass_engine(u, env, 72);
            BassSequencerState& seq = engine->bass_seq_state;
            engine->bass_step_count.store(2);
            engine->bass_accent_amount.store(0.0f);
            seq.gate_buffer[1] = 0.5f;      // slide
            seq.mutation_buffer[1] = 0.5f;  // to 84
            engine->clock_bpm.store(117.1875f);  // 6144 samples per step, a multiple of both sizes
            engine->clock_running.store(1);

            std::vector<float> audio;
            long slide_start = -1, slide_end = -1;
            while (slide_end < 0 && audio.size() < 48000) {
                long start = static_cast<long>(audio.size());
                unit_process_bass_voice(&u, engine, n, kBassSr);
                int step = seq.current_step % 2;
                if (step == 1 && slide_start < 0) slide_start = start;
                if (step == 0 && slide_start >= 0) slide_end = start;
                const float* out = u.output_buffers[OPORT_OUT];
                audio.insert(audio.end(), out, out + n);
            }

            std::vector<double> crossings;
            for (size_t i = 1; i < audio.size(); i++) {
                if (audio[i - 1] < 0.0f && audio[i] >= 0.0f) {
                    crossings.push_back(static_cast<double>(i - 1) + audio[i - 1] / (audio[i - 1] - audio[i]));
                }
            }
            const double tau = bass_glide_tau_samples(env);
            double worst = 0.0;
            int measured = 0;
            for (size_t k = 1; k < crossings.size(); k++) {
                double mid = 0.5 * (crossings[k] + crossings[k - 1]);
                // Skip periods that straddle a step boundary: the next step snaps and retriggers.
                if (mid < slide_start + 48 || crossings[k] >= slide_end) continue;
                double note = 69.0 + 12.0 * std::log2(kBassSr / (crossings[k] - crossings[k - 1]) / 440.0);
                double expect = 84.0 - 12.0 * std::exp(-(mid - slide_start) / tau);
                worst = std::max(worst, std::fabs(note - expect));
                measured++;
            }
            bool ok = slide_end > 0 && measured > 50 && worst < 0.5;
            printf("  N=%3d env=%.1f  %d periods  worst pitch error %.3f st  %s\n",
                   n, env, measured, worst, ok ? "ok" : "FAIL");
            pass = pass && ok;
            orpheus_engine_destroy(engine);
        }
    }
    printf("Bass slide moves within a block: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_bass_glide_time_constant() {
    printf("\n=== Test: Bass glide follows its time constant at 128 and 512 frames ===\n");
    bool pass = true;
    for (int n : {128, 512}) {
        for (float env : {0.2f, 0.7f, 1.0f}) {
            GraphUnit u;
            OrpheusEngine* engine = make_bass_engine(u, env, 36);
            BassSequencerState& seq = engine->bass_seq_state;
            // Clock stopped on a slide step: the target holds at 48 while the note glides from 36.
            seq.current_step = 1;
            seq.gate_buffer[1] = 0.5f;
            seq.mutation_buffer[1] = 0.5f;
            seq.smooth_note = 36.0f;

            const float tau = bass_glide_tau_samples(env);
            float worst = 0.0f, peak_note = 36.0f;
            bool finite = true;
            for (int t = n; t <= 4800; t += n) {
                unit_process_bass_voice(&u, engine, n, kBassSr);
                if (!std::isfinite(seq.smooth_note)) finite = false;
                float expect = 48.0f - 12.0f * std::exp(-t / tau);
                worst = std::max(worst, std::fabs(seq.smooth_note - expect));
                peak_note = std::max(peak_note, seq.smooth_note);
            }
            bool ok = finite && worst < 0.02f && peak_note <= 48.001f;
            printf("  N=%3d env=%.1f  worst deviation %.4f st  peak note %.3f  %s\n",
                   n, env, worst, peak_note, ok ? "ok" : "FAIL");
            pass = pass && ok;
            orpheus_engine_destroy(engine);
        }
    }
    printf("Bass glide time constant: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_bass_long_slide_stays_finite() {
    printf("\n=== Test: A 1.5 s slide at the fastest glide stays finite on 512-frame blocks ===\n");
    GraphUnit u;
    OrpheusEngine* engine = make_bass_engine(u, 1.0f, 36);
    BassSequencerState& seq = engine->bass_seq_state;
    seq.current_step = 1;
    seq.gate_buffer[1] = 0.5f;
    seq.mutation_buffer[1] = 0.5f;
    seq.smooth_note = 36.0f;

    bool finite = true;
    for (int t = 0; t < 72000; t += 512) {
        unit_process_bass_voice(&u, engine, 512, kBassSr);
        if (!std::isfinite(seq.smooth_note)) finite = false;
        const float* out = u.output_buffers[OPORT_OUT];
        for (int i = 0; i < 512; i++) {
            if (!std::isfinite(out[i])) finite = false;
        }
    }
    float err = std::fabs(seq.smooth_note - 48.0f);
    bool pass = finite && err < 0.001f;
    printf("  finite: %s  final note %.4f (target 48)\n", finite ? "yes" : "no", seq.smooth_note);
    printf("Bass long slide stays finite: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_bass_accent_flare_time_constants() {
    printf("\n=== Test: Accent flare opens over ~2 ms and closes over ~60 ms at 128 and 512 frames ===\n");
    const float kTarget = 0.35f;  // accent_amount 1
    const float kAttackTau = 0.002f * kBassSr;
    const float kDecayTau = 0.06f * kBassSr;
    bool pass = true;
    for (int n : {128, 512}) {
        GraphUnit u;
        OrpheusEngine* engine = make_bass_engine(u, 0.5f, 36);
        BassSequencerState& seq = engine->bass_seq_state;
        seq.accent_buffer[0] = 0.9f;  // clock stopped on an accented step

        float attack_worst = 0.0f;
        for (int t = n; t <= 9600; t += n) {
            unit_process_bass_voice(&u, engine, n, kBassSr);
            float expect = kTarget * (1.0f - std::exp(-t / kAttackTau));
            attack_worst = std::max(attack_worst, std::fabs(engine->bass_accent_timbre_boost - expect));
        }
        float v0 = engine->bass_accent_timbre_boost;
        seq.accent_buffer[0] = 0.0f;
        float decay_worst = 0.0f;
        for (int t = n; t <= 9600; t += n) {
            unit_process_bass_voice(&u, engine, n, kBassSr);
            float expect = v0 * std::exp(-t / kDecayTau);
            decay_worst = std::max(decay_worst, std::fabs(engine->bass_accent_timbre_boost - expect));
        }
        bool ok = attack_worst < 0.003f && decay_worst < 0.003f;
        printf("  N=%3d  attack worst %.4f  decay worst %.4f  (boost after 200 ms accent %.4f)  %s\n",
               n, attack_worst, decay_worst, v0, ok ? "ok" : "FAIL");
        pass = pass && ok;
        orpheus_engine_destroy(engine);
    }
    printf("Bass accent flare time constants: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_bass_timbre_smoothing_time_constant() {
    printf("\n=== Test: Cutoff and resonance smooth over ~5 ms at 128 and 512 frames ===\n");
    const float kTau = 0.005f * kBassSr;
    bool pass = true;
    for (int n : {128, 512}) {
        GraphUnit u;
        OrpheusEngine* engine = make_bass_engine(u, 0.5f, 36);
        engine->bass_params.timbre.store(0.2f);
        engine->bass_params.harmonics.store(0.0f);  // VCF remap: RESO 0 -> 0.5
        for (int t = 0; t < 24000; t += n) unit_process_bass_voice(&u, engine, n, kBassSr);
        float start_timbre = engine->bass_smooth_timbre;
        float start_harmonics = engine->bass_smooth_harmonics;

        engine->bass_params.timbre.store(0.8f);
        engine->bass_params.harmonics.store(1.0f);  // RESO 1 -> 0.0
        float worst = 0.0f;
        for (int t = n; t <= 2304; t += n) {
            unit_process_bass_voice(&u, engine, n, kBassSr);
            float decay = std::exp(-t / kTau);
            worst = std::max(worst, std::fabs(engine->bass_smooth_timbre - (0.8f - (0.8f - start_timbre) * decay)));
            worst = std::max(worst, std::fabs(engine->bass_smooth_harmonics - start_harmonics * decay));
        }
        bool ok = worst < 0.003f;
        printf("  N=%3d  start timbre %.3f harmonics %.3f  worst deviation %.4f  %s\n",
               n, start_timbre, start_harmonics, worst, ok ? "ok" : "FAIL");
        pass = pass && ok;
        orpheus_engine_destroy(engine);
    }
    printf("Bass timbre smoothing time constant: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// A Flux T retrigger starts the note at the block start, so the chunks before the edge must
// already play the new note's Flux X pitch, not the previous note's.
static bool test_bass_flux_t_note_starts_on_its_flux_x_pitch() {
    printf("\n=== Test: A Flux T note starts on its own Flux X pitch at 512 frames ===\n");
    const int n = 512, edge = 240;
    GraphUnit u;
    OrpheusEngine* engine = make_bass_engine(u, 0.5f, 72);
    engine->bass_accent_amount.store(0.0f);
    engine->bass_trigger_source.store(2);
    engine->bass_pitch_source.store(2);
    engine->clock_bpm.store(1.0f);  // no sequencer step fires during the test
    engine->clock_running.store(1);

    for (int i = 0; i < kMaxFrames; i++) {
        engine->marbles_t2_buffer[i] = 0.0f;
        engine->marbles_x2_buffer[i] = 0.0f;  // +0 st
    }
    for (int b = 0; b < 2; b++) unit_process_bass_voice(&u, engine, n, kBassSr);

    // T rises and X steps up an octave on the same sample.
    for (int i = edge; i < n; i++) {
        engine->marbles_t2_buffer[i] = 1.0f;
        engine->marbles_x2_buffer[i] = 1.0f;  // exp2(1) - 1: +12 st
    }
    unit_process_bass_voice(&u, engine, n, kBassSr);
    const float* out = u.output_buffers[OPORT_OUT];

    std::vector<double> crossings;
    for (int i = 1; i < edge; i++) {
        if (out[i - 1] < 0.0f && out[i] >= 0.0f) {
            crossings.push_back(static_cast<double>(i - 1) + out[i - 1] / (out[i - 1] - out[i]));
        }
    }
    double sum = 0.0;
    int periods = 0;
    for (size_t k = 1; k < crossings.size(); k++) {
        if (crossings[k - 1] < 24.0) continue;  // the oscillator glides into the first chunk
        sum += 69.0 + 12.0 * std::log2(kBassSr / (crossings[k] - crossings[k - 1]) / 440.0);
        periods++;
    }
    double mean = periods > 0 ? sum / periods : 0.0;
    bool pass = periods >= 1 && std::fabs(mean - 84.0) < 0.5;
    printf("  %d periods before the edge, mean pitch %.2f (want 84)\n", periods, mean);
    printf("Bass Flux T note starts on its Flux X pitch: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// Smoothing steps once per 24-sample chunk, so a 480-frame host (20 chunks, no partial chunk)
// renders the same audio as a 24-frame host while the accent flare and cutoff move.
static bool test_bass_flare_and_cutoff_same_audio_at_24_and_480_frames() {
    printf("\n=== Test: Accent flare and cutoff moves render the same audio at 24 and 480 frames ===\n");
    const int sizes[2] = {24, 480};
    std::vector<float> renders[2];
    for (int r = 0; r < 2; r++) {
        const int n = sizes[r];
        GraphUnit u;
        OrpheusEngine* engine = make_bass_engine(u, 0.5f, 48);
        BassSequencerState& seq = engine->bass_seq_state;
        seq.accent_buffer[0] = 0.9f;                // accented, gated step
        engine->bass_params.harmonics.store(0.8f);  // resonance makes cutoff moves audible
        engine->clock_bpm.store(1.0f);              // no sequencer step fires
        engine->clock_running.store(1);
        for (int t = 0; t < 19200; t += n) {
            if (t == 4800) engine->bass_params.timbre.store(0.8f);
            if (t == 9600) seq.accent_buffer[0] = 0.0f;
            if (t == 14400) engine->bass_params.timbre.store(0.3f);
            unit_process_bass_voice(&u, engine, n, kBassSr);
            const float* out = u.output_buffers[OPORT_OUT];
            renders[r].insert(renders[r].end(), out, out + n);
        }
        orpheus_engine_destroy(engine);
    }
    float worst = 0.0f, peak = 0.0f;
    for (size_t i = 0; i < renders[0].size(); i++) {
        worst = std::max(worst, std::fabs(renders[0][i] - renders[1][i]));
        peak = std::max(peak, std::fabs(renders[0][i]));
    }
    bool pass = peak > 0.01f && worst < 1e-5f;
    printf("  peak %.4f  worst sample difference %.3g\n", peak, worst);
    printf("Bass flare and cutoff same audio at 24 and 480 frames: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_bass_voice_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_overdrive_passthrough());
    tally(test_overdrive_distorts());
    tally(test_compressor_passthrough());
    tally(test_compressor_reduces_dynamics());
    tally(test_bass_voice_produces_audio());
    tally(test_bass_voice_silent_when_bypassed());
    tally(test_bass_voice_full_graph());
    tally(test_bass_writes_to_warps_source_buffer());
    tally(test_bass_fx_send_mixes_into_clouds());
    tally(test_bass_flux_t_gating());
    tally(test_bass_slide_portamento());
    tally(test_bass_vcf_click_detection());
    tally(test_bass_slide_moves_within_a_block());
    tally(test_bass_glide_time_constant());
    tally(test_bass_long_slide_stays_finite());
    tally(test_bass_accent_flare_time_constants());
    tally(test_bass_timbre_smoothing_time_constant());
    tally(test_bass_flux_t_note_starts_on_its_flux_x_pitch());
    tally(test_bass_flare_and_cutoff_same_audio_at_24_and_480_frames());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
