// Tides2 unit processor tests
#include "test_harness.h"
#include <cmath>
#include <cstring>

static constexpr float SR = 48000.0f;

// ── Test 1: Init — create engine, verify it doesn't crash, destroy ──────────
static bool test_tides_init() {
    printf("\n=== Test: Tides2 init ===\n");

    OrpheusEngine* engine = orpheus_engine_create(SR);

    GraphUnit u = {};
    u.type = UNIT_TIDES;
    u.enabled = true;
    unit_init(&u, SR);

    orpheus_engine_destroy(engine);
    printf("  Create/init/destroy: PASS\n");
    return true;
}

// ── Test 2: Bypass — mix=0 produces all-zero outputs ───────────────────────
static bool test_tides_bypass() {
    printf("\n=== Test: Tides2 bypass (mix=0) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(SR);
    engine->tides_mix.store(0.0f);
    engine->tides_ramp_mode.store(1); // LOOPING
    engine->tides_frequency.store(0.5f);
    engine->tides_gate_source.store(4); // free-run

    GraphUnit u = {};
    u.type = UNIT_TIDES;
    u.enabled = true;
    unit_init(&u, SR);

    // Pre-fill output buffers with non-zero to confirm they get zeroed
    for (int i = 0; i < 64; i++) {
        u.output_buffers[OPORT_OUT][i]       = 1.0f;
        u.output_buffers[OPORT_OUT_RIGHT][i] = 1.0f;
        u.output_buffers[OPORT_AUX][i]       = 1.0f;
    }

    unit_process_tides(&u, engine, 64, SR);

    bool all_zero = true;
    for (int i = 0; i < 64; i++) {
        if (u.output_buffers[OPORT_OUT][i]       != 0.0f) { all_zero = false; break; }
        if (u.output_buffers[OPORT_OUT_RIGHT][i] != 0.0f) { all_zero = false; break; }
        if (u.output_buffers[OPORT_AUX][i]       != 0.0f) { all_zero = false; break; }
    }

    // Also check engine-level buffers
    for (int ch = 0; ch < 4; ch++) {
        for (int i = 0; i < 64; i++) {
            if (engine->tides_output_buffer[ch][i] != 0.0f) { all_zero = false; break; }
            if (engine->warps_source_buffers[10 + ch][i] != 0.0f) { all_zero = false; break; }
        }
    }

    orpheus_engine_destroy(engine);
    printf("  All outputs zero (ports + engine buffers): %s\n", all_zero ? "PASS" : "FAIL");
    return all_zero;
}

// ── Test 3: Tight output bounds — all settings sweep ──────────────────────
// After kTidesNorm=0.125 normalization, output should be within [-1.1, +1.1]
// (was previously [-5, +5] which caught nothing)
static bool test_tides_tight_output_bounds() {
    printf("\n=== Test: Tides2 tight output bounds [-1.1, +1.1] (all settings) ===\n");

    const char* ramp_names[] = { "AD", "LOOPING", "AR" };
    const char* out_names[] = { "GATES", "AMPLITUDE", "PHASE", "FREQUENCY" };
    const char* range_names[] = { "CTRL", "AUDIO" };
    const float kLimit = 1.1f;
    bool all_pass = true;

    for (int ramp = 0; ramp <= 2; ramp++) {
        for (int outm = 0; outm <= 3; outm++) {
            for (int rng = 0; rng <= 1; rng++) {
                OrpheusEngine* engine = orpheus_engine_create(SR);
                // Set ALL params explicitly
                engine->tides_mix.store(1.0f);
                engine->tides_ramp_mode.store(ramp);
                engine->tides_output_mode.store(outm);
                engine->tides_frequency.store(0.5f);
                engine->tides_slope.store(0.5f);
                engine->tides_shape.store(0.5f);
                engine->tides_smoothness.store(0.5f);
                engine->tides_shift.store(0.5f);
                engine->tides_clock_offset.store(0.0f);
                engine->tides_gate_source.store(4); // free-run
                engine->tides_range.store(rng);
                engine->tides_clock_source.store(0); // internal

                GraphUnit u = {};
                u.type = UNIT_TIDES;
                u.enabled = true;
                unit_init(&u, SR);

                bool in_range = true;
                for (int b = 0; b < 100; b++) {
                    unit_process_tides(&u, engine, 64, SR);
                    for (int ch = 0; ch < 4; ch++) {
                        for (int i = 0; i < 64; i++) {
                            float v = engine->tides_output_buffer[ch][i];
                            if (!std::isfinite(v) || v < -kLimit || v > kLimit) {
                                in_range = false;
                            }
                        }
                    }
                }

                if (!in_range) {
                    printf("  FAIL: %s+%s+%s exceeds [%.1f, +%.1f]\n",
                           ramp_names[ramp], out_names[outm], range_names[rng], -kLimit, kLimit);
                    all_pass = false;
                }

                orpheus_engine_destroy(engine);
            }
        }
    }

    printf("  All settings within [-1.1, +1.1]: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// ── Test 4: Peak levels + modulation impact (all settings sweep) ──────────
// Diagnostic: prints actual peak and modulation destination impact.
// FAILS if any mode combo has pitch impact > 0.5 semitones at full depth.
static bool test_tides_modulation_impact() {
    printf("\n=== Test: Tides2 modulation impact (all settings) ===\n");
    printf("  %-10s %-12s %-5s | ch0_pk   ch3_pk   | pitch(semi) FM(Hz)   dc_ch3\n",
           "Ramp", "Output", "Range");

    const char* ramp_names[] = { "AD", "LOOPING", "AR" };
    const char* out_names[] = { "GATES", "AMPLITUDE", "PHASE", "FREQUENCY" };
    const char* range_names[] = { "CTRL", "AUDIO" };

    bool all_pass = true;
    const float kMaxPitchSemi = 0.5f;  // 0.5 semitone max at full depth

    for (int ramp = 0; ramp <= 2; ramp++) {
        for (int outm = 0; outm <= 3; outm++) {
            for (int rng = 0; rng <= 1; rng++) {
                OrpheusEngine* engine = orpheus_engine_create(SR);
                engine->tides_mix.store(1.0f);
                engine->tides_ramp_mode.store(ramp);
                engine->tides_output_mode.store(outm);
                engine->tides_frequency.store(0.5f);
                engine->tides_slope.store(0.5f);
                engine->tides_shape.store(0.5f);
                engine->tides_smoothness.store(0.5f);
                engine->tides_shift.store(0.5f);
                engine->tides_clock_offset.store(0.0f);
                engine->tides_gate_source.store(4);
                engine->tides_range.store(rng);
                engine->tides_clock_source.store(0);

                GraphUnit u = {};
                u.type = UNIT_TIDES;
                u.enabled = true;
                unit_init(&u, SR);

                float pk[4] = {};
                float sum3 = 0.0f;
                int total_samples = 0;

                for (int b = 0; b < 100; b++) {
                    unit_process_tides(&u, engine, 64, SR);
                    for (int ch = 0; ch < 4; ch++) {
                        for (int i = 0; i < 64; i++) {
                            float a = std::fabs(engine->tides_output_buffer[ch][i]);
                            if (a > pk[ch]) pk[ch] = a;
                        }
                    }
                    for (int i = 0; i < 64; i++) {
                        sum3 += engine->tides_output_buffer[3][i];
                    }
                    total_samples += 64;
                }

                // Modulation impact at full depth (fm_depth=1.0)
                float pitch_semi = pk[3] * 0.5f;    // note += ch3 * depth * 0.5
                float fm_hz = pk[0] * 200.0f;       // freq += ch0 * depth * 200
                float dc3 = sum3 / total_samples;

                bool concern = pitch_semi > kMaxPitchSemi;
                if (concern) all_pass = false;

                printf("  %-10s %-12s %-5s | %7.4f  %7.4f  | %5.2f       %5.0f    %+.4f%s\n",
                       ramp_names[ramp], out_names[outm], range_names[rng],
                       pk[0], pk[3], pitch_semi, fm_hz, dc3,
                       concern ? " FAIL" : "");

                orpheus_engine_destroy(engine);
            }
        }
    }

    printf("  Max pitch impact <= %.1f semi: %s\n", kMaxPitchSemi, all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// ── Test 5: Bass unconditional Tides routing ──────────────────────────────
// Bug: Bass voice applies Tides pitch modulation whenever tides_mix > 0 and
// bass_lfo_mix > 0, WITHOUT requiring the user to select Tides as mod source.
// This test verifies that bass pitch is NOT affected by Tides unless the user
// has explicitly opted in.
//
// Uses production graph to test full signal path.
static bool test_bass_tides_routing_requires_selection() {
    printf("\n=== Test: Bass pitch unaffected by Tides without mod source selection ===\n");

    // ── Render 1: bass note WITHOUT Tides ──
    OrpheusEngine* eng1 = orpheus_engine_create(SR);
    if (!load_production_graph(eng1)) return false;

    // Bass voice on via key override (held note, bypasses sequencer)
    eng1->bass_key_override.store(1);
    eng1->bass_key_pitch.store(48.0f);
    eng1->bass_mix.store(1.0f);
    eng1->bass_bypass.store(0);
    eng1->bass_lfo_mix.store(0.5f);  // LFO depth nonzero

    // Tides OFF
    eng1->tides_mix.store(0.0f);

    auto* graph1 = eng1->graph.load(std::memory_order_acquire);
    float buf1[128 * 2] = {};
    // Warm up + render
    for (int b = 0; b < 20; b++) {
        orpheus_graph_process(graph1, eng1, buf1, 64);
    }
    // Capture bass output energy
    float energy_no_tides = 0.0f;
    for (int b = 0; b < 10; b++) {
        orpheus_graph_process(graph1, eng1, buf1, 64);
        for (int i = 0; i < 128; i++) {
            energy_no_tides += buf1[i] * buf1[i];
        }
    }

    orpheus_engine_destroy(eng1);

    // ── Render 2: bass note WITH Tides active (but no mod source selected) ──
    OrpheusEngine* eng2 = orpheus_engine_create(SR);
    if (!load_production_graph(eng2)) return false;

    eng2->bass_key_override.store(1);
    eng2->bass_key_pitch.store(48.0f);
    eng2->bass_mix.store(1.0f);
    eng2->bass_bypass.store(0);
    eng2->bass_lfo_mix.store(0.5f);

    // Tides ON with non-zero output (looping + phase mode produces continuous signal)
    eng2->tides_mix.store(1.0f);
    eng2->tides_ramp_mode.store(1);     // LOOPING
    eng2->tides_output_mode.store(2);   // SLOPE_PHASE (continuous signal on all channels)
    eng2->tides_frequency.store(0.5f);
    eng2->tides_slope.store(0.5f);
    eng2->tides_shape.store(0.5f);
    eng2->tides_smoothness.store(0.5f);
    eng2->tides_shift.store(0.5f);
    eng2->tides_gate_source.store(4);   // free-run
    eng2->tides_range.store(0);         // CONTROL
    eng2->tides_clock_source.store(0);

    auto* graph2 = eng2->graph.load(std::memory_order_acquire);
    float buf2[128 * 2] = {};
    for (int b = 0; b < 20; b++) {
        orpheus_graph_process(graph2, eng2, buf2, 64);
    }

    // Check: does tides_output_buffer have non-zero pitch channel?
    float tides_ch3_pk = 0.0f;
    float energy_with_tides = 0.0f;
    for (int b = 0; b < 10; b++) {
        orpheus_graph_process(graph2, eng2, buf2, 64);
        for (int i = 0; i < 64; i++) {
            float a = std::fabs(eng2->tides_output_buffer[3][i]);
            if (a > tides_ch3_pk) tides_ch3_pk = a;
        }
        for (int i = 0; i < 128; i++) {
            energy_with_tides += buf2[i] * buf2[i];
        }
    }

    orpheus_engine_destroy(eng2);

    // The Tides ch3 should have signal (confirming Tides is producing output)
    bool tides_has_signal = tides_ch3_pk > 0.01f;

    // KEY ASSERTION: Bass output energy should be identical whether Tides
    // is active or not — because bass should NOT read tides_output_buffer
    // without an explicit mod source selection.
    //
    // Compare the two renders' energy. If bass pitch is being modulated by
    // Tides, the outputs will differ (different pitch = different waveform).
    // Allow small tolerance for floating-point determinism.
    float energy_diff = std::fabs(energy_with_tides - energy_no_tides);
    float energy_max = std::max(energy_with_tides, energy_no_tides);
    float rel_diff = (energy_max > 0.001f) ? energy_diff / energy_max : 0.0f;

    // With the fix, rel_diff should be ~0 (bass unaffected by Tides).
    // Without the fix, Tides modulates bass pitch/timbre, changing output.
    bool bass_unaffected = rel_diff < 0.01f;

    printf("  Tides ch3 has signal: %s (peak=%.4f)\n", tides_has_signal ? "yes" : "no", tides_ch3_pk);
    printf("  Bass energy without Tides: %.4f\n", energy_no_tides);
    printf("  Bass energy with Tides:    %.4f\n", energy_with_tides);
    printf("  Relative difference:       %.6f (threshold: 0.01)\n", rel_diff);
    printf("  Result: %s\n", bass_unaffected ? "PASS" : "FAIL (Tides modulates bass without user consent)");
    return bass_unaffected;
}

// ── Test 6: DC offset on pitch-routed channels ──────────────────────────
// Unipolar modes (AD/AR) produce positive DC offset that causes constant
// pitch shift when used as modulation source.
static bool test_tides_dc_offset_on_pitch_channel() {
    printf("\n=== Test: Tides2 DC offset detection on pitch channel (ch3) ===\n");

    const char* ramp_names[] = { "AD", "LOOPING", "AR" };
    const char* out_names[] = { "GATES", "AMPLITUDE", "PHASE", "FREQUENCY" };

    // Test all ramp × output mode combos at CONTROL range
    for (int ramp = 0; ramp <= 2; ramp++) {
        for (int outm = 0; outm <= 3; outm++) {
            OrpheusEngine* engine = orpheus_engine_create(SR);
            engine->tides_mix.store(1.0f);
            engine->tides_ramp_mode.store(ramp);
            engine->tides_output_mode.store(outm);
            engine->tides_frequency.store(0.5f);
            engine->tides_slope.store(0.5f);
            engine->tides_shape.store(0.5f);
            engine->tides_smoothness.store(0.5f);
            engine->tides_shift.store(0.5f);
            engine->tides_gate_source.store(4); // free-run
            engine->tides_range.store(0);       // CONTROL
            engine->tides_clock_source.store(0);

            GraphUnit u = {};
            u.type = UNIT_TIDES;
            u.enabled = true;
            unit_init(&u, SR);

            float sum3 = 0.0f;
            float pk3 = 0.0f;
            int total_samples = 0;

            for (int b = 0; b < 200; b++) {
                unit_process_tides(&u, engine, 64, SR);
                for (int i = 0; i < 64; i++) {
                    float v = engine->tides_output_buffer[3][i];
                    sum3 += v;
                    float a = std::fabs(v);
                    if (a > pk3) pk3 = a;
                }
                total_samples += 64;
            }

            float dc = (total_samples > 0) ? sum3 / total_samples : 0.0f;
            // Flag if DC is significant relative to peak AND would cause
            // pitch offset > 0.1 semitones (dc * 0.5)
            float pitch_offset = std::fabs(dc) * 0.5f;
            bool has_dc = (pk3 > 0.01f) && (std::fabs(dc) > 0.1f * pk3) && (pitch_offset > 0.1f);

            if (has_dc) {
                printf("  %s+%s: pk=%.4f dc=%+.4f pitch_offset=%.2f semi [DC!]\n",
                       ramp_names[ramp], out_names[outm], pk3, dc, pitch_offset);
            }

            orpheus_engine_destroy(engine);
        }
    }

    // This test is informational — flags DC offsets but doesn't fail.
    // The unconditional routing test (test 5) is the actual assertion.
    printf("  DC offset scan complete (informational)\n");
    return true;
}

// ── Test 7: AMPLITUDE mode produces output when shift is turned ──────────
// In AMPLITUDE mode, channel_index = |shift_internal * 5.1|. At the hardware
// center position (shift=0.5, internal=0.0), channel_index=0 → silent by design
// (same as MI Tides2 hardware). Users must turn the shift knob to direct
// output to channels. This test verifies AMPLITUDE works at typical shift values.
static bool test_tides_amplitude_mode_with_shift() {
    printf("\n=== Test: Tides2 AMPLITUDE mode produces output with shift turned ===\n");

    bool all_pass = true;
    // Test shift values that should produce output on various channels:
    // ch1 peak at shift≈0.6 (internal≈0.2, channel_index≈1.0)
    // ch2 peak at shift≈0.7 (internal≈0.4, channel_index≈2.0)
    float shifts[] = { 0.6f, 0.7f, 0.8f };
    for (int s = 0; s < 3; s++) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        engine->tides_mix.store(1.0f);
        engine->tides_ramp_mode.store(1);     // LOOPING
        engine->tides_output_mode.store(1);   // AMPLITUDE
        engine->tides_frequency.store(0.5f);
        engine->tides_slope.store(0.5f);
        engine->tides_shape.store(0.5f);
        engine->tides_smoothness.store(0.5f);
        engine->tides_shift.store(shifts[s]);
        engine->tides_gate_source.store(4);   // free-run
        engine->tides_range.store(0);         // CONTROL
        engine->tides_clock_source.store(0);

        GraphUnit u = {};
        u.type = UNIT_TIDES;
        u.enabled = true;
        unit_init(&u, SR);

        float pk[4] = {};
        for (int b = 0; b < 100; b++) {
            unit_process_tides(&u, engine, 64, SR);
            for (int ch = 0; ch < 4; ch++) {
                for (int i = 0; i < 64; i++) {
                    float a = std::fabs(engine->tides_output_buffer[ch][i]);
                    if (a > pk[ch]) pk[ch] = a;
                }
            }
        }

        float any_signal = std::max({pk[0], pk[1], pk[2], pk[3]});
        bool has_output = any_signal > 0.05f;
        if (!has_output) all_pass = false;

        orpheus_engine_destroy(engine);
        printf("  shift=%.1f: pk=[%.4f, %.4f, %.4f, %.4f] %s\n",
               shifts[s], pk[0], pk[1], pk[2], pk[3],
               has_output ? "PASS" : "FAIL (silent)");
    }

    return all_pass;
}

// ── Helper: count zero-crossings on ch0 of tides_output_buffer ─────────────
// Returns the number of positive-going zero crossings, which equals the
// number of complete cycles for a bipolar waveform.
static int count_zero_crossings(OrpheusEngine* engine, GraphUnit* u,
                                int blocks, int frames_per_block, float sr) {
    int crossings = 0;
    float prev = 0.0f;
    for (int b = 0; b < blocks; b++) {
        unit_process_tides(u, engine, frames_per_block, sr);
        for (int i = 0; i < frames_per_block; i++) {
            float v = engine->tides_output_buffer[0][i];
            if (prev <= 0.0f && v > 0.0f) crossings++;
            prev = v;
        }
    }
    return crossings;
}

// ── Test 8: Rate knob changes cycle rate (targeted unit test) ──────────────
// Renders Tides at a low and high frequency, counts zero crossings on ch0,
// and asserts the higher frequency produces more cycles.
static bool test_tides_rate_knob_unit() {
    printf("\n=== Test: Tides2 rate knob changes cycle rate (unit) ===\n");

    // CONTROL range: hz = 0.001 * pow(10000, freq)
    //   0.75 → ~1 Hz, 0.85 → ~4 Hz, 0.95 → ~8 Hz
    const float freqs[] = { 0.75f, 0.85f, 0.95f };
    int crossings[3] = {};

    for (int f = 0; f < 3; f++) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        engine->tides_mix.store(1.0f);
        engine->tides_ramp_mode.store(1);     // LOOPING
        engine->tides_output_mode.store(2);   // PHASE (bipolar, clean zero crossings)
        engine->tides_frequency.store(freqs[f]);
        engine->tides_slope.store(0.5f);
        engine->tides_shape.store(0.5f);
        engine->tides_smoothness.store(0.5f);
        engine->tides_shift.store(0.5f);
        engine->tides_gate_source.store(4);   // free-run
        engine->tides_range.store(0);         // CONTROL
        engine->tides_clock_source.store(0);

        GraphUnit u = {};
        u.type = UNIT_TIDES;
        u.enabled = true;
        unit_init(&u, SR);

        // Warmup
        for (int b = 0; b < 20; b++)
            unit_process_tides(&u, engine, 64, SR);

        // Measure over ~1 second (750 blocks × 64 frames = 48000 samples)
        crossings[f] = count_zero_crossings(engine, &u, 750, 64, SR);
        orpheus_engine_destroy(engine);

        printf("  freq=%.2f: %d zero crossings in 1s\n", freqs[f], crossings[f]);
    }

    // Each higher frequency setting must produce strictly more crossings
    bool pass = (crossings[0] > 0) && (crossings[1] > crossings[0]) && (crossings[2] > crossings[1]);
    printf("  Monotonically increasing: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Test 9: Rate knob changes cycle rate (production graph) ────────────────
// Same principle as test 8, but renders through the full production graph
// and measures zero crossings on the engine's tides_output_buffer.
static bool test_tides_rate_knob_production_graph() {
    printf("\n=== Test: Tides2 rate knob changes cycle rate (production graph) ===\n");

    // CONTROL range: hz = 0.001 * pow(10000, freq)
    const float freqs[] = { 0.75f, 0.85f, 0.95f };
    int crossings[3] = {};

    for (int f = 0; f < 3; f++) {
        OrpheusEngine* engine = orpheus_engine_create(SR);
        if (!load_production_graph(engine)) return false;

        engine->tides_mix.store(1.0f);
        engine->tides_ramp_mode.store(1);     // LOOPING
        engine->tides_output_mode.store(2);   // PHASE (bipolar)
        engine->tides_frequency.store(freqs[f]);
        engine->tides_slope.store(0.5f);
        engine->tides_shape.store(0.5f);
        engine->tides_smoothness.store(0.5f);
        engine->tides_shift.store(0.5f);
        engine->tides_gate_source.store(4);   // free-run
        engine->tides_range.store(0);         // CONTROL
        engine->tides_clock_source.store(0);

        auto* graph = engine->graph.load(std::memory_order_acquire);
        float buf[128 * 2];

        // Warmup
        for (int b = 0; b < 20; b++)
            orpheus_graph_process(graph, engine, buf, 64);

        // Measure zero crossings on ch0 over ~1 second
        int zc = 0;
        float prev = 0.0f;
        for (int b = 0; b < 750; b++) {
            orpheus_graph_process(graph, engine, buf, 64);
            for (int i = 0; i < 64; i++) {
                float v = engine->tides_output_buffer[0][i];
                if (prev <= 0.0f && v > 0.0f) zc++;
                prev = v;
            }
        }
        crossings[f] = zc;

        orpheus_engine_destroy(engine);
        printf("  freq=%.2f: %d zero crossings in 1s\n", freqs[f], crossings[f]);
    }

    bool pass = (crossings[0] > 0) && (crossings[1] > crossings[0]) && (crossings[2] > crossings[1]);
    printf("  Monotonically increasing: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ── Test runner ─────────────────────────────────────────────────────────────
bool run_tides_tests() {
    printf("\n========== TIDES2 TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_tides_init());
    tally(test_tides_bypass());
    tally(test_tides_tight_output_bounds());
    tally(test_tides_modulation_impact());
    tally(test_bass_tides_routing_requires_selection());
    tally(test_tides_amplitude_mode_with_shift());
    tally(test_tides_dc_offset_on_pitch_channel());
    tally(test_tides_rate_knob_unit());
    tally(test_tides_rate_knob_production_graph());
    printf("\nTides2 tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
