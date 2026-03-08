#include "orpheus_dsp.h"
#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>
#include <cmath>
#include <algorithm>

// Minimal WAV writer
void write_wav(const char* path, const float* data, int num_frames, int sample_rate) {
    FILE* f = fopen(path, "wb");
    if (!f) { fprintf(stderr, "Cannot open %s\n", path); return; }

    int channels = 2;
    int bytes_per_sample = 2;  // 16-bit
    int data_size = num_frames * channels * bytes_per_sample;
    int file_size = 44 + data_size;

    // WAV header
    fwrite("RIFF", 1, 4, f);
    int32_t chunk_size = file_size - 8; fwrite(&chunk_size, 4, 1, f);
    fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f);
    int32_t fmt_size = 16; fwrite(&fmt_size, 4, 1, f);
    int16_t audio_fmt = 1; fwrite(&audio_fmt, 2, 1, f);
    int16_t nch = channels; fwrite(&nch, 2, 1, f);
    int32_t sr = sample_rate; fwrite(&sr, 4, 1, f);
    int32_t byte_rate = sr * channels * bytes_per_sample; fwrite(&byte_rate, 4, 1, f);
    int16_t block_align = channels * bytes_per_sample; fwrite(&block_align, 2, 1, f);
    int16_t bps = 16; fwrite(&bps, 2, 1, f);
    fwrite("data", 1, 4, f);
    fwrite(&data_size, 4, 1, f);

    // Convert float -> int16
    for (int i = 0; i < num_frames * channels; i++) {
        float s = data[i];
        if (s > 1.0f) s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        int16_t sample = static_cast<int16_t>(s * 32767.0f);
        fwrite(&sample, 2, 1, f);
    }

    fclose(f);
    printf("Wrote %s (%d frames, %d Hz)\n", path, num_frames, sample_rate);
}

bool test_clock() {
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

bool test_grids() {
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

bool test_marbles() {
    printf("\n=== Test: Marbles random sequencer ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->clock_bpm.store(120.0f);
    engine->clock_running.store(1);
    engine->marbles_bypass.store(0);

    // Configure Marbles parameters
    engine->marbles_t_rate.store(0.0f);       // default rate
    engine->marbles_t_bias.store(0.5f);       // balanced probability
    engine->marbles_t_jitter.store(0.0f);     // no jitter
    engine->marbles_t_model.store(0);         // complementary Bernoulli
    engine->marbles_t_range.store(1);         // 1x range
    engine->marbles_x_spread.store(0.5f);     // medium spread
    engine->marbles_x_bias.store(0.5f);       // centered
    engine->marbles_x_steps.store(0.5f);      // medium quantization
    engine->marbles_deja_vu.store(0.0f);      // no looping
    engine->marbles_deja_vu_length.store(8);

    GraphUnit clock_unit = {};
    clock_unit.type = UNIT_CLOCK;
    clock_unit.enabled = true;
    unit_init(&clock_unit, 48000.0f);

    GraphUnit marbles_unit = {};
    marbles_unit.type = UNIT_MARBLES;
    marbles_unit.enabled = true;
    unit_init(&marbles_unit, 48000.0f);

    // Wire clock→marbles: clock OPORT_OUT (tick) → marbles IPORT_INPUT_A
    marbles_unit.inputs[IPORT_INPUT_A].sources[0] = clock_unit.output_buffers[OPORT_OUT];
    marbles_unit.inputs[IPORT_INPUT_A].num_sources = 1;

    int gate_transitions = 0;
    float cv1_min = 1e9f, cv1_max = -1e9f;
    float cv2_min = 1e9f, cv2_max = -1e9f;
    bool prev_gate = false;
    const int total_frames = 48000 * 2; // 2 seconds

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);

        // Process clock first
        unit_process_clock(&clock_unit, engine, chunk, 48000.0f);

        // Prepare marbles input from clock output
        port_prepare(&marbles_unit.inputs[IPORT_INPUT_A], chunk, 48000.0f);

        // Process marbles
        unit_process_marbles(&marbles_unit, engine, chunk, 48000.0f);

        // Count gate rising edges and track CV ranges
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

    // Verify: should have gate transitions from TGenerator
    bool gate_pass = gate_transitions > 0;
    // Verify: CV output should have some range (not stuck at zero)
    float cv1_range = cv1_max - cv1_min;
    bool cv_pass = cv1_range > 0.01f;

    printf("Gate test: %s (%d transitions)\n", gate_pass ? "PASS" : "FAIL", gate_transitions);
    printf("CV range test: %s (range=%.4f)\n", cv_pass ? "PASS" : "FAIL", cv1_range);

    bool pass = gate_pass && cv_pass;
    printf("Marbles test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool test_looper() {
    printf("\n=== Test: Looper record/play ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit looper_unit = {};
    looper_unit.type = UNIT_LOOPER;
    looper_unit.enabled = true;
    unit_init(&looper_unit, 48000.0f);

    const int record_frames = 12000; // 0.25 seconds
    engine->looper_quantize.store(0); // immediate (no beat sync for test simplicity)
    engine->looper_requested_state.store(1); // record

    // Phase 1: Record a sine tone
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

    // Phase 2: Switch to play, feed silence, verify loop plays back
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

bool test_voice_coupling() {
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

bool test_fm_modulation() {
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

bool test_bender() {
    printf("\n=== Test: Bender CV + audio ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit bender_unit = {};
    bender_unit.type = UNIT_BENDER;
    bender_unit.enabled = true;
    unit_init(&bender_unit, 48000.0f);

    // Apply a bend
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

    // Release bend — should trigger spring
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

bool test_per_string_bender() {
    printf("\n=== Test: Per-string bender ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit psb_unit = {};
    psb_unit.type = UNIT_PER_STRING_BENDER;
    psb_unit.enabled = true;
    unit_init(&psb_unit, 48000.0f);

    // Activate string 0 with bend
    engine->string_active[0].store(1);
    engine->string_bend[0].store(0.5f);
    engine->string_mix[0].store(0.5f);

    for (int offset = 0; offset < 24000; offset += 128) {
        int chunk = std::min(128, 24000 - offset);
        unit_process_per_string_bender(&psb_unit, engine, chunk, 48000.0f);
    }

    // Check voice CVs
    float bend_cv = engine->voice_bend_cv[0];
    float mix_cv = engine->voice_mix_cv[0];
    printf("Voice 0 bend CV: %.4f semitones\n", bend_cv);
    printf("Voice 0 mix CV: %.4f\n", mix_cv);

    // Release string — should trigger pluck + spring
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

// ═══════════════════════════════════════════════════════════════════════
// Voice Module Tests — polyphonic, lifecycle, idle detection
// ═══════════════════════════════════════════════════════════════════════

// Helper: process a voice unit for N frames, return max absolute output
static float render_voice(GraphUnit* unit, OrpheusEngine* engine, int total_frames, float sr = 48000.0f) {
    float max_amp = 0.0f;
    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        unit_process_plaits(unit, engine, chunk, sr);
        for (int i = 0; i < chunk; i++) {
            float a = std::fabs(unit->output_buffers[OPORT_OUT][i]);
            if (a > max_amp) max_amp = a;
        }
    }
    return max_amp;
}

// Helper: set up a voice unit for a given voice index
static void setup_voice_unit(GraphUnit* unit, int voice_idx) {
    std::memset(unit, 0, sizeof(GraphUnit));
    unit->type = UNIT_PLAITS;
    unit->enabled = true;
    unit->state.module.index = voice_idx;
    unit_init(unit, 48000.0f);
}

bool test_single_voice_engine0() {
    printf("\n=== Test: Single voice Engine 0 (VA) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Voice 0: Engine 0 (triangle/square), MIDI note 60 (C4)
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1);  // Engine 0
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    float amp = render_voice(&v0, engine, 12000); // 0.25s
    printf("  Engine 0 gate=ON: peak=%.4f %s\n", amp, amp > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp > 0.01f);

    // Gate off — envelope should release, eventually go silent
    engine->voice_params[0].gate.store(0);
    float release_amp = render_voice(&v0, engine, 48000); // 1s of release
    printf("  Engine 0 after 1s release: peak=%.6f\n", release_amp);

    // Gate back on — must produce sound again (no stuck silence)
    engine->voice_params[0].gate.store(1);
    float retrigger_amp = render_voice(&v0, engine, 12000);
    printf("  Engine 0 re-trigger: peak=%.4f %s\n", retrigger_amp, retrigger_amp > 0.01f ? "OK" : "FAIL");
    all_pass &= (retrigger_amp > 0.01f);

    printf("Single voice Engine 0 test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_single_voice_plaits_engines() {
    printf("\n=== Test: Single voice Plaits engines ===\n");
    bool all_pass = true;

    // Test engines 0 (VA analog), 1 (waveshaper), 2 (FM), 6 (chord), 8 (wavetable)
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

        float amp = render_voice(&v0, engine, 24000); // 0.5s
        bool pass = amp > 0.001f;
        printf("  Plaits engine %2d: peak=%.4f %s\n", eng, amp, pass ? "OK" : "FAIL");
        all_pass &= pass;

        orpheus_engine_destroy(engine);
    }

    printf("Plaits engines test: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

bool test_polyphonic_voices() {
    printf("\n=== Test: Polyphonic 8 voices ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Activate all 8 main voices with different notes, all Engine 0
    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};
    GraphUnit units[kNumMainVoices];

    for (int v = 0; v < kNumMainVoices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(-1); // Engine 0
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        setup_voice_unit(&units[v], v);
    }

    // Render all voices together for 0.5s
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

bool test_polyphonic_plaits_voices() {
    printf("\n=== Test: Polyphonic 8 Plaits voices ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Activate all 8 main voices with Plaits engine 0 (virtual analog)
    float notes[] = {48.0f, 52.0f, 55.0f, 60.0f, 64.0f, 67.0f, 72.0f, 76.0f};
    GraphUnit units[kNumMainVoices];

    for (int v = 0; v < kNumMainVoices; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(0); // Plaits VA
        engine->voice_params[v].tune.store(notes[v]);
        engine->voice_params[v].gate.store(1);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.5f);
        setup_voice_unit(&units[v], v);
    }

    // Render for 0.5s
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

bool test_voice_gate_retrigger() {
    printf("\n=== Test: Voice gate retrigger cycle ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0); // Plaits VA
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.3f); // faster decay

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    // 3 gate cycles: on-off-on-off-on-off
    for (int cycle = 0; cycle < 3; cycle++) {
        // Gate ON
        engine->voice_params[0].gate.store(1);
        float on_amp = render_voice(&v0, engine, 12000); // 0.25s

        // Gate OFF — let decay
        engine->voice_params[0].gate.store(0);
        render_voice(&v0, engine, 48000); // 1s full decay

        // Check idle state
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

bool test_voice_hold_without_gate() {
    printf("\n=== Test: Voice hold (no gate) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Engine 0: hold should produce sound even without gate
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(-1); // Engine 0
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(0); // no gate
    engine->voice_hold_level[0].store(0.8f); // high hold

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    float amp = render_voice(&v0, engine, 24000);
    printf("  Engine 0 hold=0.8 no gate: peak=%.4f %s\n", amp, amp > 0.01f ? "OK" : "FAIL");
    bool pass = amp > 0.01f;

    // Also test with Plaits engine
    OrpheusEngine* engine2 = orpheus_engine_create(48000.0f);
    engine2->voice_params[0].active.store(1);
    engine2->voice_params[0].ever_triggered.store(1);
    engine2->voice_params[0].engine_index.store(0); // Plaits VA
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

bool test_voice_activation_lifecycle() {
    printf("\n=== Test: Voice activation lifecycle ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    // Step 1: Voice not active — should produce silence
    engine->voice_params[0].active.store(0);
    engine->voice_params[0].gate.store(1);
    float amp_inactive = render_voice(&v0, engine, 6000);
    printf("  Not active + gate=ON: peak=%.6f %s\n", amp_inactive,
           amp_inactive < 0.001f ? "OK (silent)" : "FAIL (unexpected sound)");
    all_pass &= (amp_inactive < 0.001f);

    // Step 2: Activate via set_voice_active API (sets ever_triggered=1)
    orpheus_engine_set_voice_active(engine, 0, 1);
    engine->voice_params[0].engine_index.store(-1);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    float amp_activated = render_voice(&v0, engine, 12000);
    printf("  After set_voice_active + gate=ON: peak=%.4f %s\n", amp_activated,
           amp_activated > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_activated > 0.01f);

    // Step 3: Deactivate — should go silent
    orpheus_engine_set_voice_active(engine, 0, 0);
    float amp_deactivated = render_voice(&v0, engine, 6000);
    printf("  After deactivate: peak=%.6f %s\n", amp_deactivated,
           amp_deactivated < 0.001f ? "OK (silent)" : "FAIL");
    all_pass &= (amp_deactivated < 0.001f);

    // Step 4: Reactivate — must produce sound again
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

bool test_engine_switch_while_playing() {
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

    // Start with Engine 0
    engine->voice_params[0].engine_index.store(-1);
    float amp_e0 = render_voice(&v0, engine, 12000);
    printf("  Engine 0: peak=%.4f %s\n", amp_e0, amp_e0 > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_e0 > 0.01f);

    // Switch to Plaits engine 0 (VA analog)
    orpheus_engine_set_voice_engine(engine, 0, 0);
    float amp_p0 = render_voice(&v0, engine, 24000);
    printf("  Switch to Plaits 0: peak=%.4f %s\n", amp_p0, amp_p0 > 0.001f ? "OK" : "FAIL");
    all_pass &= (amp_p0 > 0.001f);

    // Switch to Plaits engine 2 (FM)
    orpheus_engine_set_voice_engine(engine, 0, 2);
    float amp_p2 = render_voice(&v0, engine, 24000);
    printf("  Switch to Plaits 2 (FM): peak=%.4f %s\n", amp_p2, amp_p2 > 0.001f ? "OK" : "FAIL");
    all_pass &= (amp_p2 > 0.001f);

    // Switch back to Engine 0
    orpheus_engine_set_voice_engine(engine, 0, -1);
    float amp_back = render_voice(&v0, engine, 12000);
    printf("  Back to Engine 0: peak=%.4f %s\n", amp_back, amp_back > 0.01f ? "OK" : "FAIL");
    all_pass &= (amp_back > 0.01f);

    printf("Engine switch test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

bool test_idle_detection_recovery() {
    printf("\n=== Test: Idle detection recovery ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    // Test with Plaits — the tricky idle path checks voice_levels
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(0); // Plaits VA
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.2f); // fast decay

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    // Gate on, render, gate off, let fully decay to idle
    engine->voice_params[0].gate.store(1);
    render_voice(&v0, engine, 12000);
    engine->voice_params[0].gate.store(0);

    // Long silence to trigger idle detection (voice_levels → 0)
    render_voice(&v0, engine, 96000); // 2 seconds

    float idle_level = engine->voice_levels[0].load(std::memory_order_relaxed);
    printf("  After 2s decay, voice_level=%.8f (idle=%s)\n",
           idle_level, idle_level < 0.0001f ? "yes" : "no");

    // Now gate ON again — the voice MUST recover from idle
    engine->voice_params[0].gate.store(1);
    float recovery_amp = render_voice(&v0, engine, 24000);
    bool recover_pass = recovery_amp > 0.001f;
    printf("  Recovery after idle: peak=%.4f %s\n", recovery_amp, recover_pass ? "OK" : "FAIL");
    all_pass &= recover_pass;

    // Test Engine 0 idle recovery too
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
    render_voice(&v0e, engine2, 96000); // full decay

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

bool test_full_engine_render() {
    printf("\n=== Test: Full engine render (no graph) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Activate voice 0 and set gate — the OLD test was missing set_voice_active!
    orpheus_engine_set_voice_active(engine, 0, 1);
    orpheus_engine_set_voice_tune(engine, 0, 60.0f);
    orpheus_engine_set_voice_gate(engine, 0, 1);

    const int sample_rate = 48000;
    const int total_frames = sample_rate; // 1 second
    std::vector<float> buffer(total_frames * 2, 0.0f);

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    float max_sample = 0.0f;
    for (int i = 0; i < total_frames * 2; i++) {
        float a = std::fabs(buffer[i]);
        if (a > max_sample) max_sample = a;
    }
    printf("  Max amplitude: %.4f %s\n", max_sample, max_sample > 0.001f ? "OK" : "FAIL (silence!)");

    bool pass = max_sample > 0.001f;
    printf("Full engine render test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool test_polyphonic_engine_render() {
    printf("\n=== Test: Polyphonic full engine render ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Activate 4 voices with different notes
    float notes[] = {48.0f, 55.0f, 60.0f, 67.0f};
    for (int v = 0; v < 4; v++) {
        orpheus_engine_set_voice_active(engine, v, 1);
        orpheus_engine_set_voice_tune(engine, v, notes[v]);
        orpheus_engine_set_voice_gate(engine, v, 1);
    }

    const int total_frames = 24000; // 0.5s
    std::vector<float> buffer(total_frames * 2, 0.0f);

    for (int offset = 0; offset < total_frames; offset += 128) {
        int chunk = std::min(128, total_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    // Check per-voice levels
    bool all_pass = true;
    int producing = 0;
    for (int v = 0; v < 4; v++) {
        float level = engine->voice_levels[v].load(std::memory_order_relaxed);
        bool ok = level > 0.001f;
        if (ok) producing++;
        printf("  Voice %d (note %.0f): level=%.4f %s\n", v, notes[v], level, ok ? "OK" : "SILENT!");
        all_pass &= ok;
    }

    float max_sample = 0.0f;
    for (int i = 0; i < total_frames * 2; i++) {
        float a = std::fabs(buffer[i]);
        if (a > max_sample) max_sample = a;
    }
    printf("  Mix amplitude: %.4f, %d/4 voices producing\n", max_sample, producing);
    all_pass &= (max_sample > 0.001f);

    printf("Polyphonic engine render test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

int main() {
    bool all_pass = true;

    // Existing unit tests
    all_pass &= test_voice_coupling();
    all_pass &= test_fm_modulation();
    all_pass &= test_clock();
    all_pass &= test_grids();
    all_pass &= test_marbles();
    all_pass &= test_looper();
    all_pass &= test_bender();
    all_pass &= test_per_string_bender();

    // New voice module tests
    all_pass &= test_single_voice_engine0();
    all_pass &= test_single_voice_plaits_engines();
    all_pass &= test_polyphonic_voices();
    all_pass &= test_polyphonic_plaits_voices();
    all_pass &= test_voice_gate_retrigger();
    all_pass &= test_voice_hold_without_gate();
    all_pass &= test_voice_activation_lifecycle();
    all_pass &= test_engine_switch_while_playing();
    all_pass &= test_idle_detection_recovery();
    all_pass &= test_full_engine_render();
    all_pass &= test_polyphonic_engine_render();

    if (!all_pass) {
        fprintf(stderr, "\nFAILURE: One or more tests failed!\n");
        return 1;
    }

    printf("\nSUCCESS: All tests passed.\n");
    return 0;
}
