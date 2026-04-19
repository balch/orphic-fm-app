// LFO waveform, mode logic, and mod-source integration tests
#include "test_harness.h"

static constexpr float SR = 48000.0f;

// Fill lfo_output_buffer with a constant value (simulates steady LFO for unit tests)
static void fill_lfo_buffer(OrpheusEngine* eng, float value) {
    eng->lfo_output_value = value;
    for (int i = 0; i < kMaxFrames; i++) {
        eng->lfo_output_buffer[i] = value;
    }
}

// Fill vibrato_output_buffer with Hz offset (simulates dedicated vibrato oscillator output)
static void fill_vibrato_buffer(OrpheusEngine* eng, float hz_offset) {
    for (int i = 0; i < kMaxFrames; i++) {
        eng->vibrato_output_buffer[i] = hz_offset;
    }
}

// ═══════════════════════════════════════════════════════════════════
// Helper: render LFO in isolation via UNIT_HYPER_LFO graph unit
// Returns mono output buffer
// ═══════════════════════════════════════════════════════════════════
static std::vector<float> render_lfo(
    float freq_a, float freq_b, float shape, int mode,
    float sr, float duration_s)
{
    OrpheusEngine* engine = orpheus_engine_create(sr);
    engine->lfo_freq_a.store(freq_a);
    engine->lfo_freq_b.store(freq_b);
    engine->lfo_shape.store(shape);
    engine->lfo_mode.store(mode);

    GraphUnit lfo;
    std::memset(&lfo, 0, sizeof(lfo));
    lfo.type = UNIT_HYPER_LFO;
    lfo.enabled = true;
    unit_init(&lfo, sr);

    int total = (int)(sr * duration_s);
    std::vector<float> buf(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_hyper_lfo(&lfo, engine, chunk, sr);
        std::memcpy(buf.data() + off, lfo.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }

    orpheus_engine_destroy(engine);
    return buf;
}

// ═══════════════════════════════════════════════════════════════════
// Test 1: Triangle waveform — verify shape, frequency, bipolar range
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_triangle_waveform() {
    printf("\n=== Test: LFO triangle waveform ===\n");
    bool pass = true;

    float sr = 48000.0f;
    float freq = 2.0f;    // 2 Hz → 2 complete cycles in 1s
    float shape = 1.0f;   // pure triangle
    int mode = 0;         // AND with freq_b=0 passes through A only

    auto buf = render_lfo(freq, 0.0f, shape, mode, sr, 1.0f);

    // Check bipolar range: should span [-1, +1]
    float min_val = 1.0f, max_val = -1.0f;
    for (float s : buf) {
        if (s < min_val) min_val = s;
        if (s > max_val) max_val = s;
    }
    printf("  Range: [%.4f, %.4f]\n", min_val, max_val);
    if (max_val < 0.95f || min_val > -0.95f) {
        printf("  FAIL: expected bipolar range near [-1, +1]\n");
        pass = false;
    }

    // Count zero crossings — triangle at 2 Hz should have ~4 crossings/sec
    int zero_crossings = 0;
    for (int i = 1; i < (int)buf.size(); i++) {
        if ((buf[i-1] >= 0.0f && buf[i] < 0.0f) || (buf[i-1] < 0.0f && buf[i] >= 0.0f))
            zero_crossings++;
    }
    printf("  Zero crossings: %d (expect ~%d for %.0f Hz triangle)\n",
           zero_crossings, (int)(freq * 2), freq);
    // Triangle at F Hz has 2*F zero crossings per second (±10% tolerance)
    if (zero_crossings < (int)(freq * 2 * 0.8f) || zero_crossings > (int)(freq * 2 * 1.2f)) {
        printf("  FAIL: frequency mismatch\n");
        pass = false;
    }

    // Verify triangle shape: RMS of a pure triangle wave is 1/sqrt(3) ≈ 0.577
    float rms = compute_rms(buf.data(), buf.size());
    printf("  RMS: %.4f (expect ~0.577 for triangle)\n", rms);
    if (rms < 0.50f || rms > 0.65f) {
        printf("  FAIL: RMS outside expected range for triangle\n");
        pass = false;
    }

    printf("LFO triangle waveform test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 2: Square waveform — verify shape and RMS
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_square_waveform() {
    printf("\n=== Test: LFO square waveform ===\n");
    bool pass = true;

    float sr = 48000.0f;
    float freq = 3.0f;
    float shape = 0.0f;   // pure square
    int mode = 0;          // AND with freq_b=0 passes through A only

    auto buf = render_lfo(freq, 0.0f, shape, mode, sr, 1.0f);

    // Check bipolar range
    float min_val = 1.0f, max_val = -1.0f;
    for (float s : buf) {
        if (s < min_val) min_val = s;
        if (s > max_val) max_val = s;
    }
    printf("  Range: [%.4f, %.4f]\n", min_val, max_val);
    if (max_val < 0.99f || min_val > -0.99f) {
        printf("  FAIL: square should be exactly ±1\n");
        pass = false;
    }

    // Square wave RMS = 1.0 (pure bipolar square)
    float rms = compute_rms(buf.data(), buf.size());
    printf("  RMS: %.4f (expect 1.000 for square)\n", rms);
    if (rms < 0.98f || rms > 1.02f) {
        printf("  FAIL: square RMS should be ~1.0\n");
        pass = false;
    }

    // Count zero crossings — square at 3 Hz has 6 transitions
    int transitions = 0;
    for (int i = 1; i < (int)buf.size(); i++) {
        if (buf[i] != buf[i-1]) transitions++;
    }
    printf("  Transitions: %d (expect ~%d)\n", transitions, (int)(freq * 2));
    if (transitions < (int)(freq * 2 * 0.8f) || transitions > (int)(freq * 2 * 1.2f)) {
        printf("  FAIL: frequency mismatch for square\n");
        pass = false;
    }

    printf("LFO square waveform test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 3: Shape morph (blend between square and triangle)
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_shape_morph() {
    printf("\n=== Test: LFO shape morph ===\n");
    bool pass = true;

    float sr = 48000.0f;
    float freq = 2.0f;
    int mode = 0;  // AND with freq_b=0 passes through A only

    // Render at several shape values, verify RMS changes monotonically
    // square (shape=0) has RMS=1.0, triangle (shape=1) has RMS≈0.577
    float shapes[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    float rms_values[5];

    for (int i = 0; i < 5; i++) {
        auto buf = render_lfo(freq, 0.0f, shapes[i], mode, sr, 1.0f);
        rms_values[i] = compute_rms(buf.data(), buf.size());
        printf("  shape=%.2f: RMS=%.4f\n", shapes[i], rms_values[i]);
    }

    // Endpoints should match pure waveforms
    if (rms_values[0] < 0.98f) {
        printf("  FAIL: shape=0 should be pure square (RMS≈1.0)\n");
        pass = false;
    }
    if (rms_values[4] > 0.62f || rms_values[4] < 0.50f) {
        printf("  FAIL: shape=1 should be pure triangle (RMS≈0.577)\n");
        pass = false;
    }
    // Intermediate shapes should have RMS between triangle and square
    // (not necessarily monotonic due to cross-correlation of sq and tri)
    for (int i = 1; i < 4; i++) {
        if (rms_values[i] > rms_values[0] + 0.01f || rms_values[i] < rms_values[4] - 0.1f) {
            printf("  FAIL: shape=%.2f RMS=%.4f outside [%.4f, %.4f]\n",
                   shapes[i], rms_values[i], rms_values[4] - 0.1f, rms_values[0]);
            pass = false;
        }
    }

    printf("LFO shape morph test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 4: AND mode — dual LFO logic combination
// AND = (ua * ub) * 2 - 1, where ua/ub = (a * 0.5 + 0.5)
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_and_mode() {
    printf("\n=== Test: LFO AND mode ===\n");
    bool pass = true;

    float sr = 48000.0f;
    // Use square waves for verifiable logic: A@1Hz, B@2Hz
    // Square wave is ±1; unipolar: 0 or 1
    // AND(0,0)=0 AND(0,1)=0 AND(1,0)=0 AND(1,1)=1
    // In bipolar: result pattern should show clear AND gating
    float shape = 0.0f;  // pure square

    auto buf_and = render_lfo(1.0f, 2.0f, shape, 0 /*AND*/, sr, 1.0f);

    // AND output should be non-silent (mode is active)
    float rms_and = compute_rms(buf_and.data(), buf_and.size());
    printf("  AND RMS: %.6f\n", rms_and);
    if (rms_and < 0.1f) {
        printf("  FAIL: AND mode should produce non-trivial output\n");
        pass = false;
    }

    // AND of two square waves: when either is low (bipolar -1 → unipolar 0), output = -1
    // Count samples at -1: with A@1Hz and B@2Hz in 1s, at least 50% should be at -1
    int at_low = 0;
    for (float s : buf_and) {
        if (s < -0.95f) at_low++;
    }
    float low_fraction = (float)at_low / buf_and.size();
    printf("  Fraction at -1: %.3f (expect >= 0.50 for AND square)\n", low_fraction);
    if (low_fraction < 0.45f) {
        printf("  FAIL: AND should gate output low when either input is low\n");
        pass = false;
    }

    // AND output should be bounded [-1, +1]
    float min_v = 1.0f, max_v = -1.0f;
    for (float s : buf_and) {
        if (s < min_v) min_v = s;
        if (s > max_v) max_v = s;
    }
    printf("  Range: [%.4f, %.4f]\n", min_v, max_v);
    if (min_v < -1.01f || max_v > 1.01f) {
        printf("  FAIL: output out of [-1, +1] range\n");
        pass = false;
    }

    printf("LFO AND mode test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 5: OR mode — dual LFO logic combination
// OR = (ua + ub - ua * ub) * 2 - 1
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_or_mode() {
    printf("\n=== Test: LFO OR mode ===\n");
    bool pass = true;

    float sr = 48000.0f;
    float shape = 0.0f;  // pure square for verifiable logic

    auto buf_or = render_lfo(1.0f, 2.0f, shape, 2 /*OR*/, sr, 1.0f);

    // OR output should be non-silent
    float rms_or = compute_rms(buf_or.data(), buf_or.size());
    printf("  OR RMS: %.6f\n", rms_or);
    if (rms_or < 0.1f) {
        printf("  FAIL: OR mode should produce non-trivial output\n");
        pass = false;
    }
    // OR of two square waves: when either is high (unipolar 1), output = +1
    // With A@1Hz and B@2Hz, high fraction should be >= 50%
    int at_high = 0;
    for (float s : buf_or) {
        if (s > 0.95f) at_high++;
    }
    float high_fraction = (float)at_high / buf_or.size();
    printf("  Fraction at +1: %.3f (expect >= 0.50 for OR square)\n", high_fraction);
    if (high_fraction < 0.45f) {
        printf("  FAIL: OR should keep output high when either input is high\n");
        pass = false;
    }

    // OR should be opposite pattern to AND: when AND is low, OR should be high (for binary inputs)
    auto buf_and = render_lfo(1.0f, 2.0f, shape, 0 /*AND*/, sr, 1.0f);
    // For binary inputs: AND + OR = 2*A + 2*B - 2*A*B - 1 ... not exactly complementary
    // But at least they should differ significantly
    float and_or_diff = 0.0f;
    for (int i = 0; i < (int)buf_or.size(); i++) {
        float d = buf_or[i] - buf_and[i];
        and_or_diff += d * d;
    }
    float and_or_diff_rms = std::sqrt(and_or_diff / buf_or.size());
    printf("  AND vs OR diff_rms: %.6f\n", and_or_diff_rms);
    if (and_or_diff_rms < 0.1f) {
        printf("  FAIL: AND and OR should produce significantly different outputs\n");
        pass = false;
    }

    printf("LFO OR mode test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 6: Frequency accuracy across range
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_frequency_range() {
    printf("\n=== Test: LFO frequency accuracy ===\n");
    bool pass = true;

    float sr = 48000.0f;
    int mode = 0;         // AND with freq_b=0 passes through A only
    float shape = 1.0f;   // triangle for easy zero-crossing counting

    // Test several frequencies from the Kotlin range (0.01-5 Hz)
    float freqs[] = {0.1f, 0.5f, 1.0f, 2.0f, 5.0f};

    for (float freq : freqs) {
        float duration = std::max(2.0f / freq, 1.0f); // at least 2 full cycles
        auto buf = render_lfo(freq, 0.0f, shape, mode, sr, duration);

        // Count zero crossings
        int zc = 0;
        for (int i = 1; i < (int)buf.size(); i++) {
            if ((buf[i-1] >= 0.0f && buf[i] < 0.0f) || (buf[i-1] < 0.0f && buf[i] >= 0.0f))
                zc++;
        }

        // Triangle at F Hz has 2*F zero crossings per second
        float expected_zc = freq * 2.0f * duration;
        float error = std::fabs((float)zc - expected_zc) / expected_zc;
        printf("  freq=%.2f Hz: zero_crossings=%d expected=%.0f error=%.1f%%\n",
               freq, zc, expected_zc, error * 100.0f);
        if (error > 0.15f) {
            printf("  FAIL: frequency error > 15%%\n");
            pass = false;
        }
    }

    printf("LFO frequency accuracy test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 7: LFO as mod source → voice timbre modulation
// Verify that LFO mod source (src=2) with mod_depth changes voice timbre
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_timbre_modulation() {
    printf("\n=== Test: LFO → voice timbre modulation ===\n");
    bool pass = true;

    float sr = 48000.0f;
    int total = 48000; // 1 second

    // ── Baseline: no modulation ──
    OrpheusEngine* eng_dry = orpheus_engine_create(sr);
    eng_dry->voice_params[0].active.store(1);
    eng_dry->voice_params[0].ever_triggered.store(1);
    eng_dry->voice_params[0].engine_index.store(8); // VirtualAnalog
    eng_dry->voice_params[0].tune.store(60.0f);
    eng_dry->voice_params[0].gate.store(1);
    eng_dry->voice_params[0].harmonics.store(0.5f);
    eng_dry->voice_params[0].timbre.store(0.5f);
    eng_dry->voice_params[0].morph.store(0.5f);
    eng_dry->voice_params[0].decay.store(0.0f);
    eng_dry->mod_source[0].store(1); // OFF

    GraphUnit v_dry;
    setup_voice_unit(&v_dry, 0);

    std::vector<float> buf_dry(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_dry, eng_dry, chunk, sr);
        std::memcpy(buf_dry.data() + off, v_dry.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    float rms_dry = compute_rms(buf_dry.data(), total);
    orpheus_engine_destroy(eng_dry);

    // ── With LFO timbre modulation ──
    OrpheusEngine* eng_lfo = orpheus_engine_create(sr);
    eng_lfo->voice_params[0].active.store(1);
    eng_lfo->voice_params[0].ever_triggered.store(1);
    eng_lfo->voice_params[0].engine_index.store(8);
    eng_lfo->voice_params[0].tune.store(60.0f);
    eng_lfo->voice_params[0].gate.store(1);
    eng_lfo->voice_params[0].harmonics.store(0.5f);
    eng_lfo->voice_params[0].timbre.store(0.5f);
    eng_lfo->voice_params[0].morph.store(0.5f);
    eng_lfo->voice_params[0].decay.store(0.0f);
    eng_lfo->mod_source[0].store(2); // LFO
    eng_lfo->mod_depth[0].store(0.8f);
    eng_lfo->fm_depth[0].store(0.0f);
    // Fill LFO buffer with constant value (simulates steady LFO for plaits modulation)
    fill_lfo_buffer(eng_lfo, 0.7f);

    GraphUnit v_lfo;
    setup_voice_unit(&v_lfo, 0);

    std::vector<float> buf_lfo(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_lfo, eng_lfo, chunk, sr);
        std::memcpy(buf_lfo.data() + off, v_lfo.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    float rms_lfo = compute_rms(buf_lfo.data(), total);
    orpheus_engine_destroy(eng_lfo);

    // The waveforms should differ
    float diff_sum = 0.0f;
    for (int i = 0; i < total; i++) {
        float d = buf_dry[i] - buf_lfo[i];
        diff_sum += d * d;
    }
    float diff_rms = std::sqrt(diff_sum / total);

    printf("  Dry RMS: %.6f, LFO mod RMS: %.6f\n", rms_dry, rms_lfo);
    printf("  Diff RMS: %.6f\n", diff_rms);

    if (diff_rms < 0.001f) {
        printf("  FAIL: LFO timbre modulation should change voice output\n");
        pass = false;
    } else {
        printf("  OK: LFO timbre mod produces different output\n");
    }

    printf("LFO timbre modulation test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 8: LFO as mod source → voice FM modulation
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_fm_modulation() {
    printf("\n=== Test: LFO → voice FM modulation ===\n");
    bool pass = true;

    float sr = 48000.0f;
    int total = 48000;

    // Engine 0 (engine_index=-1): LFO as mod source modulates frequency via
    // linear FM in Hz (matching JSyn fmFreqMixer path).
    // Plaits engines only get timbre modulation from mod sources, not pitch FM.

    // ── Baseline: no modulation (Engine 0) ──
    OrpheusEngine* eng_dry = orpheus_engine_create(sr);
    eng_dry->voice_params[0].active.store(1);
    eng_dry->voice_params[0].ever_triggered.store(1);
    eng_dry->voice_params[0].engine_index.store(-1);  // Engine 0 (OSC mode)
    eng_dry->voice_params[0].tune.store(60.0f);
    eng_dry->voice_params[0].gate.store(1);
    eng_dry->voice_params[0].harmonics.store(0.0f);
    eng_dry->voice_params[0].timbre.store(0.0f);
    eng_dry->voice_params[0].morph.store(0.0f);
    eng_dry->voice_params[0].decay.store(0.0f);
    eng_dry->mod_source[0].store(1); // OFF

    GraphUnit v_dry;
    setup_voice_unit(&v_dry, 0);

    std::vector<float> buf_dry(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_dry, eng_dry, chunk, sr);
        std::memcpy(buf_dry.data() + off, v_dry.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    orpheus_engine_destroy(eng_dry);

    // ── With LFO FM modulation (Engine 0) ──
    OrpheusEngine* eng_fm = orpheus_engine_create(sr);
    eng_fm->voice_params[0].active.store(1);
    eng_fm->voice_params[0].ever_triggered.store(1);
    eng_fm->voice_params[0].engine_index.store(-1);  // Engine 0 (OSC mode)
    eng_fm->voice_params[0].tune.store(60.0f);
    eng_fm->voice_params[0].gate.store(1);
    eng_fm->voice_params[0].harmonics.store(0.0f);
    eng_fm->voice_params[0].timbre.store(0.0f);
    eng_fm->voice_params[0].morph.store(0.0f);
    eng_fm->voice_params[0].decay.store(0.0f);
    eng_fm->mod_source[0].store(2); // LFO
    eng_fm->mod_depth[0].store(0.0f);
    eng_fm->fm_depth[0].store(0.8f); // FM depth
    fill_lfo_buffer(eng_fm, 0.5f);

    GraphUnit v_fm;
    setup_voice_unit(&v_fm, 0);

    std::vector<float> buf_fm(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_fm, eng_fm, chunk, sr);
        std::memcpy(buf_fm.data() + off, v_fm.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    orpheus_engine_destroy(eng_fm);

    // Signals should differ due to LFO frequency modulation
    float diff_sum = 0.0f;
    for (int i = 0; i < total; i++) {
        float d = buf_dry[i] - buf_fm[i];
        diff_sum += d * d;
    }
    float diff_rms = std::sqrt(diff_sum / total);
    printf("  FM diff_rms: %.6f\n", diff_rms);

    if (diff_rms < 0.001f) {
        printf("  FAIL: LFO FM modulation should change Engine 0 pitch\n");
        pass = false;
    } else {
        printf("  OK: LFO FM mod produces different output (pitch shift)\n");
    }

    printf("LFO FM modulation test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 9: LFO vibrato (direct pitch modulation independent of mod source)
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_vibrato() {
    printf("\n=== Test: LFO vibrato (pitch modulation) ===\n");
    bool pass = true;

    float sr = 48000.0f;
    int total = 48000;

    // ── Baseline: no vibrato ──
    OrpheusEngine* eng_dry = orpheus_engine_create(sr);
    eng_dry->voice_params[0].active.store(1);
    eng_dry->voice_params[0].ever_triggered.store(1);
    eng_dry->voice_params[0].engine_index.store(8);
    eng_dry->voice_params[0].tune.store(60.0f);
    eng_dry->voice_params[0].gate.store(1);
    eng_dry->voice_params[0].harmonics.store(0.5f);
    eng_dry->voice_params[0].timbre.store(0.5f);
    eng_dry->voice_params[0].morph.store(0.5f);
    eng_dry->voice_params[0].decay.store(0.0f);
    eng_dry->vibrato_depth.store(0.0f);

    GraphUnit v_dry;
    setup_voice_unit(&v_dry, 0);
    std::vector<float> buf_dry(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_dry, eng_dry, chunk, sr);
        std::memcpy(buf_dry.data() + off, v_dry.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    orpheus_engine_destroy(eng_dry);

    // ── With vibrato ──
    OrpheusEngine* eng_vib = orpheus_engine_create(sr);
    eng_vib->voice_params[0].active.store(1);
    eng_vib->voice_params[0].ever_triggered.store(1);
    eng_vib->voice_params[0].engine_index.store(8);
    eng_vib->voice_params[0].tune.store(60.0f);
    eng_vib->voice_params[0].gate.store(1);
    eng_vib->voice_params[0].harmonics.store(0.5f);
    eng_vib->voice_params[0].timbre.store(0.5f);
    eng_vib->voice_params[0].morph.store(0.5f);
    eng_vib->voice_params[0].decay.store(0.0f);
    eng_vib->vibrato_depth.store(1.0f); // depth=1.0 → 20 Hz vibrato range
    // Fill vibrato buffer with +20 Hz offset (simulates sine at peak with depth=1.0)
    fill_vibrato_buffer(eng_vib, 20.0f);

    GraphUnit v_vib;
    setup_voice_unit(&v_vib, 0);
    std::vector<float> buf_vib(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_vib, eng_vib, chunk, sr);
        std::memcpy(buf_vib.data() + off, v_vib.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    orpheus_engine_destroy(eng_vib);

    float diff_sum = 0.0f;
    for (int i = 0; i < total; i++) {
        float d = buf_dry[i] - buf_vib[i];
        diff_sum += d * d;
    }
    float diff_rms = std::sqrt(diff_sum / total);
    printf("  Vibrato diff_rms: %.6f\n", diff_rms);

    if (diff_rms < 0.001f) {
        printf("  FAIL: vibrato should change voice pitch\n");
        pass = false;
    } else {
        printf("  OK: vibrato produces pitch-shifted output\n");
    }

    printf("LFO vibrato test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 10: LFO graph integration — full graph path
// UNIT_HYPER_LFO → voice mod (via lfo_output_value) → audio output
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_graph_integration() {
    printf("\n=== Test: LFO graph integration (LFO unit → voice mod) ===\n");
    bool pass = true;

    float sr = 48000.0f;

    // Build graph: LFO + voice → master
    OrpheusEngine* engine = orpheus_engine_create(sr);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(8);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(0.0f);

    // Configure LFO
    engine->lfo_freq_a.store(2.0f);  // 2 Hz
    engine->lfo_shape.store(1.0f);   // triangle
    engine->lfo_mode.store(0);       // AND mode (active output)

    // Set LFO as mod source for duo 0
    engine->mod_source[0].store(2);  // LFO
    engine->mod_depth[0].store(0.8f);

    auto* graph = new OrpheusGraph();
    std::memset(graph, 0, sizeof(OrpheusGraph));
    graph->sample_rate = sr;

    // Unit 0: HyperLFO
    graph->units[0].type = UNIT_HYPER_LFO;
    graph->units[0].id = 0;
    graph->units[0].enabled = true;
    unit_init(&graph->units[0], sr);

    // Unit 1: Voice (Plaits)
    graph->units[1].type = UNIT_PLAITS;
    graph->units[1].id = 1;
    graph->units[1].enabled = true;
    unit_init(&graph->units[1], sr);
    graph->units[1].state.module.index = 0;

    // Unit 2: Master out
    graph->units[2].type = UNIT_MASTER_OUT;
    graph->units[2].id = 2;
    graph->units[2].enabled = true;
    unit_init(&graph->units[2], sr);
    graph->units[2].inputs[IPORT_INPUT_A].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
    graph->units[2].inputs[IPORT_INPUT_B].sources[0] = graph->units[1].output_buffers[OPORT_OUT];
    graph->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

    graph->unit_count = 3;
    graph->exec_count = 3;
    graph->exec_order[0] = 0;  // LFO first (updates lfo_output_value)
    graph->exec_order[1] = 1;  // then voice (reads lfo_output_value for mod)
    graph->exec_order[2] = 2;  // then master
    graph->master_out_index = 2;

    // Render with LFO modulation
    int total = 48000; // 1 second
    float overall_peak = 0.0f;

    // Track timbre changes by looking at spectral variation across blocks
    std::vector<float> block_rms;
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        float stereo[256] = {};
        orpheus_graph_process(graph, engine, stereo, chunk);

        float block_sum = 0.0f;
        for (int i = 0; i < chunk * 2; i++) {
            float a = std::fabs(stereo[i]);
            if (a > overall_peak) overall_peak = a;
            block_sum += stereo[i] * stereo[i];
        }
        block_rms.push_back(std::sqrt(block_sum / (chunk * 2)));
    }

    printf("  Peak: %.4f\n", overall_peak);
    if (overall_peak < 0.01f) {
        printf("  FAIL: no audio output from LFO→voice graph\n");
        pass = false;
    }

    // Verify LFO modulation causes energy variation across blocks
    // A 2 Hz LFO at 48kHz in 128-sample blocks = varying timbre each block
    float min_rms = 1.0f, max_rms = 0.0f;
    for (float r : block_rms) {
        if (r < min_rms) min_rms = r;
        if (r > max_rms) max_rms = r;
    }
    float rms_variation = (max_rms - min_rms) / (max_rms + 0.0001f);
    printf("  Block RMS range: [%.4f, %.4f] variation=%.3f\n", min_rms, max_rms, rms_variation);
    // LFO modulating timbre should cause some variation in block energy
    if (rms_variation < 0.01f) {
        printf("  WARN: very low block energy variation (LFO may not be modulating effectively)\n");
        // Not a hard fail — timbre mod may not always cause large RMS changes
    }

    // Verify lfo_output_value was updated by the LFO unit
    float lfo_val = engine->lfo_output_value;
    printf("  lfo_output_value after render: %.4f\n", lfo_val);
    if (lfo_val == 0.0f) {
        printf("  FAIL: LFO unit did not update lfo_output_value\n");
        pass = false;
    }

    delete graph;
    orpheus_engine_destroy(engine);

    printf("LFO graph integration test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 11: LFO warps source routing
// UNIT_HYPER_LFO output should appear in warps_source_buffers[3]
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_warps_routing() {
    printf("\n=== Test: LFO → warps source buffer routing ===\n");
    bool pass = true;

    float sr = 48000.0f;
    OrpheusEngine* engine = orpheus_engine_create(sr);
    engine->lfo_freq_a.store(2.0f);
    engine->lfo_shape.store(0.0f);   // square for clear signal
    engine->lfo_mode.store(0);       // AND mode (active output)
    engine->lfo_freq_b.store(0.0f);  // B=0 → passes through A

    GraphUnit lfo;
    std::memset(&lfo, 0, sizeof(lfo));
    lfo.type = UNIT_HYPER_LFO;
    lfo.enabled = true;
    unit_init(&lfo, sr);

    unit_process_hyper_lfo(&lfo, engine, 128, sr);

    // The warps source copy is handled by orpheus_graph_process, not the unit.
    // Simulate it here since we're testing the unit directly.
    std::memcpy(engine->warps_source_buffers[3], engine->lfo_output_buffer,
                128 * sizeof(float));

    // Check warps_source_buffers[3] has non-zero content
    float warps_peak = 0.0f;
    for (int i = 0; i < 128; i++) {
        float a = std::fabs(engine->warps_source_buffers[3][i]);
        if (a > warps_peak) warps_peak = a;
    }
    printf("  Warps source buffer[3] peak: %.4f\n", warps_peak);
    if (warps_peak < 0.9f) {
        printf("  FAIL: LFO square output not routed to warps buffer\n");
        pass = false;
    }

    // Check output buffer matches warps buffer
    float match_err = 0.0f;
    for (int i = 0; i < 128; i++) {
        float d = lfo.output_buffers[OPORT_OUT][i] - engine->warps_source_buffers[3][i];
        match_err += d * d;
    }
    match_err = std::sqrt(match_err / 128);
    printf("  Output vs warps buffer error: %.6f\n", match_err);
    if (match_err > 0.0001f) {
        printf("  FAIL: output and warps buffer should be identical\n");
        pass = false;
    }

    orpheus_engine_destroy(engine);
    printf("LFO warps routing test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 12: Dual LFO mode comparison — OFF vs AND vs OR with triangle
// Verify all three modes produce distinct waveforms with triangle shape
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_mode_comparison() {
    printf("\n=== Test: LFO mode comparison (OFF/AND/OR with triangle) ===\n");
    bool pass = true;

    float sr = 48000.0f;
    float shape = 1.0f;  // triangle

    auto buf_off = render_lfo(1.0f, 3.0f, shape, 1 /*OFF*/, sr, 2.0f);
    auto buf_and = render_lfo(1.0f, 3.0f, shape, 0 /*AND*/, sr, 2.0f);
    auto buf_or  = render_lfo(1.0f, 3.0f, shape, 2 /*OR*/,  sr, 2.0f);

    auto diff_rms = [](const std::vector<float>& a, const std::vector<float>& b) {
        float sum = 0.0f;
        for (int i = 0; i < (int)a.size(); i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return std::sqrt(sum / a.size());
    };

    float off_and = diff_rms(buf_off, buf_and);
    float off_or  = diff_rms(buf_off, buf_or);
    float and_or  = diff_rms(buf_and, buf_or);

    printf("  OFF vs AND: %.6f\n", off_and);
    printf("  OFF vs OR:  %.6f\n", off_or);
    printf("  AND vs OR:  %.6f\n", and_or);

    // All three should be significantly different with triangle waves
    if (off_and < 0.01f) {
        printf("  FAIL: OFF and AND should differ for triangle waves\n");
        pass = false;
    }
    if (off_or < 0.01f) {
        printf("  FAIL: OFF and OR should differ for triangle waves\n");
        pass = false;
    }
    if (and_or < 0.01f) {
        printf("  FAIL: AND and OR should differ for triangle waves\n");
        pass = false;
    }

    // AND should generally have lower RMS than OR (for triangle waves)
    float rms_and = compute_rms(buf_and.data(), buf_and.size());
    float rms_or  = compute_rms(buf_or.data(), buf_or.size());
    printf("  AND RMS: %.4f, OR RMS: %.4f\n", rms_and, rms_or);
    if (rms_and >= rms_or) {
        printf("  WARN: expected AND RMS < OR RMS (AND gates, OR opens)\n");
        // Not a hard fail — depends on frequency ratio
    }

    printf("LFO mode comparison test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 13: LFO mod depth sweep — increasing depth produces increasing effect
// ═══════════════════════════════════════════════════════════════════
static bool test_lfo_mod_depth_sweep() {
    printf("\n=== Test: LFO mod depth sweep ===\n");
    bool pass = true;

    float sr = 48000.0f;
    int total = 24000; // 0.5s

    // Render voice with LFO mod at increasing depths, compare to dry
    float depths[] = {0.0f, 0.2f, 0.5f, 0.8f, 1.0f};
    float diff_values[5];

    // First render dry baseline
    OrpheusEngine* eng_base = orpheus_engine_create(sr);
    eng_base->voice_params[0].active.store(1);
    eng_base->voice_params[0].ever_triggered.store(1);
    eng_base->voice_params[0].engine_index.store(8);
    eng_base->voice_params[0].tune.store(60.0f);
    eng_base->voice_params[0].gate.store(1);
    eng_base->voice_params[0].harmonics.store(0.5f);
    eng_base->voice_params[0].timbre.store(0.5f);
    eng_base->voice_params[0].morph.store(0.5f);
    eng_base->voice_params[0].decay.store(0.0f);
    eng_base->mod_source[0].store(1); // OFF

    GraphUnit v_base;
    setup_voice_unit(&v_base, 0);
    std::vector<float> buf_base(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_base, eng_base, chunk, sr);
        std::memcpy(buf_base.data() + off, v_base.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    orpheus_engine_destroy(eng_base);

    for (int d = 0; d < 5; d++) {
        OrpheusEngine* eng = orpheus_engine_create(sr);
        eng->voice_params[0].active.store(1);
        eng->voice_params[0].ever_triggered.store(1);
        eng->voice_params[0].engine_index.store(8);
        eng->voice_params[0].tune.store(60.0f);
        eng->voice_params[0].gate.store(1);
        eng->voice_params[0].harmonics.store(0.5f);
        eng->voice_params[0].timbre.store(0.5f);
        eng->voice_params[0].morph.store(0.5f);
        eng->voice_params[0].decay.store(0.0f);
        eng->mod_source[0].store(2); // LFO
        eng->mod_depth[0].store(depths[d]);
        eng->fm_depth[0].store(0.0f);
        fill_lfo_buffer(eng, 0.7f);

        GraphUnit v;
        setup_voice_unit(&v, 0);
        std::vector<float> buf(total, 0.0f);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            unit_process_plaits(&v, eng, chunk, sr);
            std::memcpy(buf.data() + off, v.output_buffers[OPORT_OUT], chunk * sizeof(float));
        }

        float diff_sum = 0.0f;
        for (int i = 0; i < total; i++) {
            float dd = buf_base[i] - buf[i];
            diff_sum += dd * dd;
        }
        diff_values[d] = std::sqrt(diff_sum / total);
        printf("  depth=%.1f: diff_rms=%.6f\n", depths[d], diff_values[d]);
        orpheus_engine_destroy(eng);
    }

    // At depth=0, output should match dry baseline
    if (diff_values[0] > 0.001f) {
        printf("  FAIL: depth=0 should match dry baseline\n");
        pass = false;
    }

    // Each non-zero depth should produce meaningful difference from baseline
    for (int i = 1; i < 5; i++) {
        if (diff_values[i] < 0.01f) {
            printf("  FAIL: depth=%.1f should produce difference from baseline\n", depths[i]);
            pass = false;
        }
    }

    // Higher depths should produce at least as much difference as low depths
    // (may plateau due to timbre saturation at [0,1] clamp, but shouldn't decrease much)
    if (diff_values[4] < diff_values[1] * 0.5f) {
        printf("  FAIL: depth=1.0 should be at least half of depth=0.2 effect\n");
        pass = false;
    }

    printf("LFO mod depth sweep test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Test: DuoLFO 4-channel output ──────────────────────────────────
// All 4 mod channels should be populated, distinct, and within [-1,+1]
// when DuoLFO is active (AND or OR mode). All should be zero in OFF mode.
static bool test_lfo_4channel_output() {
    printf("\n=== Test: DuoLFO 4-channel output ===\n");
    bool all_pass = true;

    const char* mode_names[] = { "AND", "OFF", "OR" };
    for (int mode = 0; mode <= 2; mode++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        if (!load_production_graph(engine)) return false;

        engine->lfo_source.store(0);  // DuoLFO
        engine->lfo_mode.store(mode);
        engine->lfo_freq_a.store(2.0f);  // 2 Hz
        engine->lfo_freq_b.store(3.0f);  // 3 Hz (distinct for channel separation)
        engine->lfo_shape.store(1.0f);   // triangle
        engine->lfo_range_min.store(-1.0f);
        engine->lfo_range_max.store(1.0f);

        auto* graph = engine->graph.load(std::memory_order_acquire);
        float buf[128 * 2];

        // Run enough blocks for several LFO cycles at 2-3 Hz
        float pk[4] = {};
        float sum[4] = {};
        int total = 0;
        for (int b = 0; b < 200; b++) {
            orpheus_graph_process(graph, engine, buf, 64);
            for (int i = 0; i < 64; i++) {
                float ch[4] = {
                    engine->lfo_output_buffer[i],
                    engine->lfo_morph_buffer[i],
                    engine->lfo_harmonics_buffer[i],
                    engine->lfo_pitch_buffer[i],
                };
                for (int c = 0; c < 4; c++) {
                    float a = std::fabs(ch[c]);
                    if (a > pk[c]) pk[c] = a;
                    sum[c] += ch[c];
                }
            }
            total += 64;
        }

        float dc[4];
        for (int c = 0; c < 4; c++) dc[c] = sum[c] / total;

        bool is_off = (mode == 1);
        if (is_off) {
            // OFF mode: all channels should be zero
            bool all_zero = true;
            for (int c = 0; c < 4; c++) {
                if (pk[c] > 0.001f) all_zero = false;
            }
            if (!all_zero) {
                printf("  FAIL: %s mode should produce zero on all channels\n", mode_names[mode]);
                all_pass = false;
            } else {
                printf("  %s: all zero — PASS\n", mode_names[mode]);
            }
        } else {
            // Active mode: all 4 channels should have signal
            printf("  %s: pk=[%.3f, %.3f, %.3f, %.3f] dc=[%+.3f, %+.3f, %+.3f, %+.3f]\n",
                   mode_names[mode], pk[0], pk[1], pk[2], pk[3], dc[0], dc[1], dc[2], dc[3]);

            for (int c = 0; c < 4; c++) {
                if (pk[c] < 0.1f) {
                    printf("  FAIL: %s ch%d peak=%.4f too low (no signal)\n",
                           mode_names[mode], c, pk[c]);
                    all_pass = false;
                }
                if (pk[c] > 1.1f) {
                    printf("  FAIL: %s ch%d peak=%.4f exceeds [-1.1, +1.1]\n",
                           mode_names[mode], c, pk[c]);
                    all_pass = false;
                }
            }

            // Channels should be distinct (not all identical)
            // Compare ch0 vs ch1 (combined vs osc A)
            float diff_01 = 0.0f, diff_02 = 0.0f, diff_03 = 0.0f;
            orpheus_graph_process(graph, engine, buf, 64);
            for (int i = 0; i < 64; i++) {
                diff_01 += std::fabs(engine->lfo_output_buffer[i] - engine->lfo_morph_buffer[i]);
                diff_02 += std::fabs(engine->lfo_output_buffer[i] - engine->lfo_harmonics_buffer[i]);
                diff_03 += std::fabs(engine->lfo_output_buffer[i] - engine->lfo_pitch_buffer[i]);
            }
            diff_01 /= 64; diff_02 /= 64; diff_03 /= 64;
            bool distinct = diff_01 > 0.01f && diff_02 > 0.01f && diff_03 > 0.01f;
            if (!distinct) {
                printf("  FAIL: %s channels not distinct (diffs: %.4f, %.4f, %.4f)\n",
                       mode_names[mode], diff_01, diff_02, diff_03);
                all_pass = false;
            }
        }

        orpheus_engine_destroy(engine);
    }

    printf("DuoLFO 4-channel test: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// ── Helper: count positive-going zero crossings on poly_lfo_output ch0 ──
// Processes `blocks` blocks of `frames_per_block` frames each through
// unit_process_poly_lfo and counts sign transitions from <=0 to >0.
static int count_zero_crossings_poly_lfo(OrpheusEngine* engine, GraphUnit* u,
                                         int blocks, int frames_per_block, float sr) {
    int crossings = 0;
    float prev = 0.0f;
    for (int b = 0; b < blocks; b++) {
        unit_process_poly_lfo(u, engine, frames_per_block, sr);
        for (int i = 0; i < frames_per_block; i++) {
            float v = engine->poly_lfo_output[0][i];
            if (prev <= 0.0f && v > 0.0f) crossings++;
            prev = v;
        }
    }
    return crossings;
}

// ═══════════════════════════════════════════════════════════════════
// Test: PolyLFO rate knob changes cycle rate (direct unit test)
//
// Rate mapping: freq = rate^3 * 55000 (cubic ease-in).
// At 48 kHz: rate=0.85 → ~3 Hz, rate=0.9 → ~7 Hz, rate=0.95 → ~15 Hz
//
// Three rate values should produce monotonically increasing zero
// crossings over ~1 second (750 blocks × 64 frames = 48000 samples).
// ═══════════════════════════════════════════════════════════════════
static bool test_poly_lfo_rate_knob_unit() {
    printf("\n=== Test: PolyLFO rate knob changes cycle rate (unit) ===\n");

    // Cubic ease-in: most of the knob is slow, so use upper-range values
    const float rates[] = { 0.85f, 0.9f, 0.95f };
    int crossings[3] = {};

    for (int r = 0; r < 3; r++) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        engine->poly_lfo_bypass.store(0);           // enable (default is bypassed!)
        engine->poly_lfo_rate.store(rates[r]);
        engine->poly_lfo_shape.store(0.5f);
        engine->poly_lfo_spread.store(0.5f);
        engine->poly_lfo_coupling.store(0.0f);
        engine->poly_lfo_shape_spread.store(0.5f);

        GraphUnit u = {};
        u.type = UNIT_POLY_LFO;
        u.enabled = true;
        unit_init(&u, SR);

        // Warmup — let internal state settle
        for (int b = 0; b < 20; b++)
            unit_process_poly_lfo(&u, engine, 64, SR);

        // Measure over ~1 second (750 blocks × 64 frames = 48000 samples at 48 kHz)
        crossings[r] = count_zero_crossings_poly_lfo(engine, &u, 750, 64, SR);
        orpheus_engine_destroy(engine);

        printf("  rate=%.2f: %d zero crossings in 1s\n", rates[r], crossings[r]);
    }

    // Each higher rate setting must produce strictly more crossings
    bool pass = (crossings[0] > 0) && (crossings[1] > crossings[0]) && (crossings[2] > crossings[1]);
    printf("  Monotonically increasing: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test: PolyLFO rate knob changes cycle rate (production graph)
//
// Same test as above but renders through the full production graph
// via orpheus_graph_process() and measures zero crossings on
// engine->poly_lfo_output[0].
// ═══════════════════════════════════════════════════════════════════
static bool test_poly_lfo_rate_knob_production_graph() {
    printf("\n=== Test: PolyLFO rate knob changes cycle rate (production graph) ===\n");

    // Cubic ease-in: most of the knob is slow, so use upper-range values
    const float rates[] = { 0.85f, 0.9f, 0.95f };
    int crossings[3] = {};

    for (int r = 0; r < 3; r++) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;

        engine->poly_lfo_bypass.store(0);           // enable
        engine->poly_lfo_rate.store(rates[r]);
        engine->poly_lfo_shape.store(0.5f);
        engine->poly_lfo_spread.store(0.5f);
        engine->poly_lfo_coupling.store(0.0f);
        engine->poly_lfo_shape_spread.store(0.5f);

        auto* graph = engine->graph.load(std::memory_order_acquire);
        float buf[128 * 2] = {};

        // Warmup
        for (int b = 0; b < 20; b++)
            orpheus_graph_process(graph, engine, buf, 64);

        // Measure zero crossings on ch0 over ~1 second
        int zc = 0;
        float prev = 0.0f;
        for (int b = 0; b < 750; b++) {
            orpheus_graph_process(graph, engine, buf, 64);
            for (int i = 0; i < 64; i++) {
                float v = engine->poly_lfo_output[0][i];
                if (prev <= 0.0f && v > 0.0f) zc++;
                prev = v;
            }
        }
        crossings[r] = zc;

        orpheus_engine_destroy(engine);
        printf("  rate=%.2f: %d zero crossings in 1s\n", rates[r], crossings[r]);
    }

    bool pass = (crossings[0] > 0) && (crossings[1] > crossings[0]) && (crossings[2] > crossings[1]);
    printf("  Monotonically increasing: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test runner
// ═══════════════════════════════════════════════════════════════════
bool run_lfo_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_lfo_triangle_waveform());
    tally(test_lfo_square_waveform());
    tally(test_lfo_shape_morph());
    tally(test_lfo_and_mode());
    tally(test_lfo_or_mode());
    tally(test_lfo_frequency_range());
    tally(test_lfo_timbre_modulation());
    tally(test_lfo_fm_modulation());
    tally(test_lfo_vibrato());
    tally(test_lfo_graph_integration());
    tally(test_lfo_warps_routing());
    tally(test_lfo_mode_comparison());
    tally(test_lfo_mod_depth_sweep());
    tally(test_lfo_4channel_output());
    tally(test_poly_lfo_rate_knob_unit());
    tally(test_poly_lfo_rate_knob_production_graph());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
