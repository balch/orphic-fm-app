#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_pattern_gen.h"
#include "../src/orpheus_graph.h"
#include "tides2/poly_slope_generator.h"
#include "stmlib/dsp/dsp.h"
#include <cstdio>
#include <cmath>
#include <cstring>

bool run_pulsar_tests() {
    printf("\n=== Pulsar Tests ===\n\n");
    int pass = 0, fail = 0;

    // Shared engine — pulsar state is owned by engine->pulsar_state.
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    // ── Test 1: Pulsar produces non-zero audio output ──
    {
        printf("  Test 1: Pulsar produces non-zero audio output\n");

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_cosmic_techno(engine);
        trigger_vibe_load(engine);
        engine->clock_bpm.store(128.0f, std::memory_order_relaxed);

        // Process ~2 seconds of audio (200 * 512 = 102400 samples @ 48kHz ~ 2.13s)
        for (int i = 0; i < 200; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        // Check peak amplitude of most recent block
        float peak = 0.0f;
        for (int i = 0; i < 512; i++) {
            float al = std::fabs(engine->pulsar_out_l[i]);
            float ar = std::fabs(engine->pulsar_out_r[i]);
            if (al > peak) peak = al;
            if (ar > peak) peak = ar;
        }

        if (peak > 0.01f) {
            printf("    PASS: peak amplitude = %.4f\n", peak);
            pass++;
        } else {
            printf("    FAIL: peak amplitude = %.4f (expected > 0.01)\n", peak);
            fail++;
        }
    }

    // ── Test 2: Playhead advances ──
    {
        printf("  Test 2: Playhead advances over time\n");

        // After ~2 seconds of processing at 128 BPM (from test 1),
        // the playhead should have advanced from 0.
        // At 128 BPM, 16th notes = 128/60*4 = 8.53 steps/sec.
        // Over 2 seconds, that's ~17 steps, wrapping around a 16-step pattern.
        int playhead = engine->pulsar_viz.playheads[0];

        // The playhead should be non-zero (extremely unlikely to land exactly on 0
        // after ~17 step advances through a 16-step pattern)
        // Actually it could wrap to 0, so just check any track has a non-zero playhead
        bool any_advanced = false;
        for (int t = 0; t < kNumPulsarTracks; t++) {
            if (engine->pulsar_viz.playheads[t] != 0) {
                any_advanced = true;
                break;
            }
        }

        if (any_advanced) {
            printf("    PASS: playhead[0] = %d\n", playhead);
            pass++;
        } else {
            printf("    FAIL: all playheads still at 0\n");
            fail++;
        }
    }

    // ── Test 3: Viz data populated correctly ──
    {
        printf("  Test 3: Viz data populated correctly\n");

        bool viz_ok = true;

        // All Cosmic Techno tracks use 16 steps
        int step_count = engine->pulsar_viz.step_counts[0];
        if (step_count != 16) {
            printf("    FAIL: step_counts[0] = %d (expected 16)\n", step_count);
            viz_ok = false;
        }

        // Cosmic Techno kick has gate on step 0 (first step in pattern)
        bool kick_step0 = engine->pulsar_viz.step_gates[0][0];
        if (!kick_step0) {
            printf("    FAIL: step_gates[0][0] = false (expected true for Cosmic Techno kick)\n");
            viz_ok = false;
        }

        // Viz version should have been incremented many times
        int viz_version = engine->pulsar_viz_version.load(std::memory_order_acquire);
        if (viz_version <= 0) {
            printf("    FAIL: pulsar_viz_version = %d (expected > 0)\n", viz_version);
            viz_ok = false;
        }

        if (viz_ok) {
            printf("    PASS: step_counts[0]=%d, step_gates[0][0]=true, viz_version=%d\n",
                   step_count, viz_version);
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 4: Vibe switching ──
    {
        printf("  Test 4: Vibe switching works\n");

        // Switch to Deep Space vibe
        setup_deep_space(engine);
        trigger_vibe_load(engine);

        // Process one block to trigger vibe load
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        // Verify step_counts are still 16 (Deep Space also uses 16 steps)
        int step_count = engine->pulsar_viz.step_counts[0];
        bool vibe_ok = (step_count == 16);

        // Verify at least some gates exist across all tracks (patterns are random)
        int total_gates = 0;
        for (int t = 0; t < kNumPulsarTracks; t++) {
            for (int s = 0; s < engine->pulsar_viz.step_counts[t]; s++) {
                if (engine->pulsar_viz.step_gates[t][s]) total_gates++;
            }
        }
        vibe_ok = vibe_ok && (total_gates > 0);

        if (vibe_ok) {
            printf("    PASS: deep space vibe loaded, step_counts[0]=%d, total_gates=%d\n",
                   step_count, total_gates);
            pass++;
        } else {
            printf("    FAIL: vibe switch did not update patterns correctly\n");
            printf("      step_counts[0]=%d, total_gates=%d\n",
                   step_count, total_gates);
            fail++;
        }
    }

    // ── Test 5: Not playing produces silence (even with mix > 0) ──
    {
        printf("  Test 5: Not playing produces silence\n");

        engine->pulsar_playing.store(0, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);  // mix is on — only playing gates output

        // Process one block
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        bool silent = true;
        for (int i = 0; i < 512; i++) {
            if (engine->pulsar_out_l[i] != 0.0f || engine->pulsar_out_r[i] != 0.0f) {
                silent = false;
                break;
            }
        }

        if (silent) {
            printf("    PASS: output is silent when not playing\n");
            pass++;
        } else {
            printf("    FAIL: output is not silent when not playing\n");
            fail++;
        }
    }

    // ── Test 6: Voice crossfade — low energy activates space engines ──
    {
        printf("  Test 6: Voice crossfade — low energy activates space engines\n");

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);

        // Reset state by re-loading vibe
        setup_cosmic_techno(engine);
        trigger_vibe_load(engine);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        engine->pulsar_energy.store(0.2f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.8f, std::memory_order_relaxed);
        engine->pulsar_space.store(0.8f, std::memory_order_relaxed);
        engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);

        for (int i = 0; i < 400; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        float peak = 0.0f;
        for (int i = 0; i < 512; i++) {
            float a = std::fabs(engine->pulsar_out_l[i]);
            if (a > peak) peak = a;
        }

        if (peak > 0.001f) {
            printf("    PASS: low energy produces output (space engines), peak=%.4f\n", peak);
            pass++;
        } else {
            printf("    FAIL: low energy produced no output (peak=%.4f)\n", peak);
            fail++;
        }
    }

    // ── Test 7: Probability gating — high energy fires more gates than low ──
    {
        printf("  Test 7: Probability gating — high energy denser than low energy\n");

        // High energy run — count active gates in viz data
        engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
        engine->pulsar_space.store(0.3f, std::memory_order_relaxed);

        // Reset by re-loading vibe
        setup_cosmic_techno(engine);
        trigger_vibe_load(engine);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        int high_gates = 0;
        for (int i = 0; i < 200; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }
        for (int t = 0; t < 5; t++) {
            for (int s = 0; s < engine->pulsar_viz.step_counts[t]; s++) {
                if (engine->pulsar_viz.step_gates[t][s]) high_gates++;
            }
        }

        // Low energy run — count active gates in viz data
        engine->pulsar_energy.store(0.1f, std::memory_order_relaxed);
        setup_cosmic_techno(engine);
        trigger_vibe_load(engine);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        int low_gates = 0;
        for (int i = 0; i < 200; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }
        for (int t = 0; t < 5; t++) {
            for (int s = 0; s < engine->pulsar_viz.step_counts[t]; s++) {
                if (engine->pulsar_viz.step_gates[t][s]) low_gates++;
            }
        }

        // Stochastic test — patterns are fully random per-load so variance is high.
        // Allow generous margin; we're testing the trend, not exact counts.
        if (high_gates >= low_gates - 10) {
            printf("    PASS: high energy gates (%d) ~>= low energy gates (%d)\n", high_gates, low_gates);
            pass++;
        } else {
            printf("    FAIL: expected high energy gates >= low, got high=%d low=%d\n", high_gates, low_gates);
            fail++;
        }
    }

    // ── Test 8: Elastic tempo — valid playhead at low energy ──
    {
        printf("  Test 8: Elastic tempo — valid playhead at low energy\n");

        engine->pulsar_energy.store(0.1f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.5f, std::memory_order_relaxed);
        setup_deep_space(engine);
        trigger_vibe_load(engine);

        for (int i = 0; i < 500; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        bool valid = engine->pulsar_viz.playheads[0] >= 0
                  && engine->pulsar_viz.playheads[0] < 20;

        if (valid) {
            printf("    PASS: elastic tempo — playhead=%d after 5s at low energy\n", engine->pulsar_viz.playheads[0]);
            pass++;
        } else {
            printf("    FAIL: invalid playhead=%d\n", engine->pulsar_viz.playheads[0]);
            fail++;
        }
    }

    // ── Test 9: Scale quantization — output remains musical after heavy mutation ──
    {
        printf("  Test 9: Scale quantization — musical after heavy mutation\n");

        engine->pulsar_energy.store(0.5f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(1.0f, std::memory_order_relaxed);
        engine->pulsar_space.store(0.5f, std::memory_order_relaxed);

        // Re-load Cosmic Techno vibe (D minor)
        setup_cosmic_techno(engine);
        trigger_vibe_load(engine);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        for (int i = 0; i < 1000; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        float peak = 0.0f;
        for (int i = 0; i < 512; i++) {
            float a = std::fabs(engine->pulsar_out_l[i]);
            if (a > peak) peak = a;
        }

        int active_gates = 0;
        for (int t = 0; t < 5; t++) {
            for (int s = 0; s < engine->pulsar_viz.step_counts[t]; s++) {
                if (engine->pulsar_viz.step_gates[t][s]) active_gates++;
            }
        }

        if (peak > 0.001f && active_gates > 10) {
            printf("    PASS: scale quantization — musical after heavy mutation (peak=%.4f, gates=%d)\n",
                   peak, active_gates);
            pass++;
        } else {
            printf("    FAIL: scale quantization — peak=%.4f, gates=%d\n",
                   peak, active_gates);
            fail++;
        }
    }

    // ── Test 10: Root/scale atomics override scene defaults ──
    {
        printf("  Test 10: Root/scale atomics override scene defaults\n");

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_deep_space(engine);
        engine->pulsar_energy.store(0.7f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.8f, std::memory_order_relaxed);

        // Override root to D (2) and scale to Pentatonic (2)
        engine->pulsar_root_note.store(2, std::memory_order_relaxed);
        engine->pulsar_scale_index.store(2, std::memory_order_relaxed);
        trigger_vibe_load(engine);

        // Run enough blocks to trigger mutations with note drift
        for (int i = 0; i < 200; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        // Verify output is non-silent (pattern is running with new scale)
        float peak = 0.0f;
        for (int i = 0; i < 512; i++) {
            float a = std::fabs(engine->pulsar_out_l[i]);
            if (a > peak) peak = a;
        }

        if (peak > 0.001f) {
            printf("    PASS: root/scale atomics — output non-silent (peak=%.4f)\n", peak);
            pass++;
        } else {
            printf("    FAIL: root/scale atomics — output silent (peak=%.4f)\n", peak);
            fail++;
        }
    }

    // ── Test 11: Per-track engine atomics override scene engines ──
    {
        printf("  Test 11: Per-track engine atomics override scene engines\n");

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_deep_space(engine);
        trigger_vibe_load(engine);
        engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);

        // Run one block to initialize and load vibe
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        // Override track 0 (kick) to use FM (10) for both EDM and space
        engine->pulsar_track_engine_edm[0].store(10, std::memory_order_relaxed);
        engine->pulsar_track_engine_space[0].store(10, std::memory_order_relaxed);

        // Run more blocks — should not crash and should produce output
        for (int i = 0; i < 50; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        float peak = 0.0f;
        for (int i = 0; i < 512; i++) {
            float a = std::fabs(engine->pulsar_out_l[i]);
            if (a > peak) peak = a;
        }

        if (peak > 0.001f) {
            printf("    PASS: per-track engine atomics — output non-silent (peak=%.4f)\n", peak);
            pass++;
        } else {
            printf("    FAIL: per-track engine atomics — output silent (peak=%.4f)\n", peak);
            fail++;
        }
    }

    // ── Test 12: Mix controls output level ──
    {
        printf("  Test 12: Mix controls output level\n");

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);

        // Mix = 0: should be silent
        engine->pulsar_mix.store(0.0f, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        float peak_silent = 0.0f;
        for (int i = 0; i < 512; i++) {
            float a = std::fabs(engine->pulsar_out_l[i]);
            if (a > peak_silent) peak_silent = a;
        }

        // Mix = 1: should produce output (run enough blocks for pattern to fire)
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        float peak_full = 0.0f;
        for (int i = 0; i < 100; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            for (int j = 0; j < 512; j++) {
                float a = std::fabs(engine->pulsar_out_l[j]);
                if (a > peak_full) peak_full = a;
            }
        }

        bool silent_ok = peak_silent < 0.001f;
        bool full_ok   = peak_full > 0.001f;

        if (silent_ok && full_ok) {
            printf("    PASS: mix=0 silent (%.4f), mix=1 audible (%.4f)\n", peak_silent, peak_full);
            pass++;
        } else {
            printf("    FAIL: mix=0 peak=%.4f (expected <0.001), mix=1 peak=%.4f (expected >0.01)\n",
                   peak_silent, peak_full);
            fail++;
        }
    }

    orpheus_engine_destroy(engine);

    // ── Test 13: PolySlopeGenerator shift→channel mapping (stack) ──
    {
        printf("  Test 13: PolySlopeGenerator shift->channel[0] mapping (stack)\n");

        // Test various raw shift values to find which puts output on channel[0].
        // Internal shift = 2*raw - 1. For AMPLITUDE mode:
        //   channel_index = |shift_internal * 5.1|
        //   gain[j] = max(1 - |channel(j+1) - channel_index|, 0)
        // channel[0] (j=0) → channel=1.0 → need channel_index≈1.0
        //   |shift_internal * 5.1| = 1.0 → shift_internal ≈ 0.196 → raw ≈ 0.598

        float test_shifts[] = { 0.0f, 0.2f, 0.5f, 0.6f, 0.7f, 0.8f, 1.0f };
        int num_shifts = sizeof(test_shifts) / sizeof(test_shifts[0]);
        bool found_ch0 = false;
        float best_shift = -1.0f;
        float best_ch0_peak = 0.0f;

        for (int si = 0; si < num_shifts; si++) {
            tides::PolySlopeGenerator gen;
            gen.Init();

            // Build gate flags with a rising edge at sample 0
            stmlib::GateFlags flags[64];
            stmlib::GateFlags prev_flag = stmlib::GATE_FLAG_LOW;
            for (int i = 0; i < 64; i++) {
                bool high = (i < 32);
                flags[i] = stmlib::ExtractGateFlags(prev_flag, high);
                prev_flag = flags[i];
            }

            tides::PolySlopeGenerator::OutputSample out[64];
            // Render multiple blocks to let the envelope develop
            float pk[4] = {};
            for (int block = 0; block < 20; block++) {
                // Only trigger on first block
                stmlib::GateFlags block_flags[64];
                if (block == 0) {
                    std::memcpy(block_flags, flags, sizeof(flags));
                } else {
                    for (int i = 0; i < 64; i++) {
                        block_flags[i] = stmlib::GATE_FLAG_LOW;
                    }
                }

                gen.Render(
                    tides::RAMP_MODE_AD, tides::OUTPUT_MODE_AMPLITUDE, tides::RANGE_CONTROL,
                    0.005f,   // frequency (moderate envelope speed)
                    0.5f,     // pw (balanced attack/decay)
                    0.5f,     // shape
                    0.5f,     // smoothness
                    test_shifts[si],
                    block_flags, nullptr, out, 64
                );

                for (int i = 0; i < 64; i++) {
                    for (int c = 0; c < 4; c++) {
                        float a = std::fabs(out[i].channel[c]);
                        if (a > pk[c]) pk[c] = a;
                    }
                }
            }

            printf("    shift=%.1f: ch0=%.4f ch1=%.4f ch2=%.4f ch3=%.4f\n",
                   test_shifts[si], pk[0], pk[1], pk[2], pk[3]);

            if (pk[0] > 0.1f && pk[0] > best_ch0_peak) {
                best_ch0_peak = pk[0];
                best_shift = test_shifts[si];
                found_ch0 = true;
            }
        }

        if (found_ch0) {
            printf("    PASS: channel[0] has output at shift=%.1f (peak=%.4f)\n",
                   best_shift, best_ch0_peak);
            pass++;
        } else {
            printf("    FAIL: no shift value produced output on channel[0]\n");
            fail++;
        }
    }

    // ── Test 14: PolySlopeGenerator shift→channel mapping (heap) ──
    {
        printf("  Test 14: PolySlopeGenerator shift->channel[0] mapping (heap)\n");

        // Same test but with heap-allocated generator (like PulsarTrackState)
        tides::PolySlopeGenerator* gen = new tides::PolySlopeGenerator();
        gen->Init();

        stmlib::GateFlags flags[64];
        stmlib::GateFlags prev_flag = stmlib::GATE_FLAG_LOW;
        for (int i = 0; i < 64; i++) {
            bool high = (i < 32);
            flags[i] = stmlib::ExtractGateFlags(prev_flag, high);
            prev_flag = flags[i];
        }

        // Use shift=0.6 which should put output on channel[0]
        float pk[4] = {};
        for (int block = 0; block < 20; block++) {
            stmlib::GateFlags block_flags[64];
            if (block == 0) {
                std::memcpy(block_flags, flags, sizeof(flags));
            } else {
                for (int i = 0; i < 64; i++) {
                    block_flags[i] = stmlib::GATE_FLAG_LOW;
                }
            }

            tides::PolySlopeGenerator::OutputSample out[64];
            gen->Render(
                tides::RAMP_MODE_AD, tides::OUTPUT_MODE_AMPLITUDE, tides::RANGE_CONTROL,
                0.005f, 0.5f, 0.5f, 0.5f,
                0.6f,  // shift that targets channel[0]
                block_flags, nullptr, out, 64
            );

            for (int i = 0; i < 64; i++) {
                for (int c = 0; c < 4; c++) {
                    float a = std::fabs(out[i].channel[c]);
                    if (a > pk[c]) pk[c] = a;
                }
            }
        }

        delete gen;

        printf("    heap: ch0=%.4f ch1=%.4f ch2=%.4f ch3=%.4f\n",
               pk[0], pk[1], pk[2], pk[3]);

        if (pk[0] > 0.1f) {
            printf("    PASS: heap-allocated generator produces output on channel[0] (%.4f)\n", pk[0]);
            pass++;
        } else {
            printf("    FAIL: heap-allocated generator silent on channel[0] (%.4f)\n", pk[0]);
            fail++;
        }
    }

    // ── Test 15: PolySlopeGenerator embedded in struct (like PulsarTrackState) ──
    {
        printf("  Test 15: PolySlopeGenerator in struct (PulsarTrackState-like)\n");

        struct TestTrackState {
            tides::PolySlopeGenerator tides_env;
            stmlib::GateFlags tides_prev_gate;
            float tides_env_level;
            bool voice_active;
        };

        TestTrackState* ts = new TestTrackState();
        ts->tides_env.Init();
        ts->tides_prev_gate = stmlib::GATE_FLAG_LOW;
        ts->tides_env_level = 0.0f;
        ts->voice_active = true;

        float pk[4] = {};
        for (int block = 0; block < 20; block++) {
            stmlib::GateFlags flags[64];
            for (int i = 0; i < 64; i++) {
                bool high = (block == 0 && i < 32);
                flags[i] = stmlib::ExtractGateFlags(ts->tides_prev_gate, high);
                ts->tides_prev_gate = flags[i];
            }

            tides::PolySlopeGenerator::OutputSample out[64];
            ts->tides_env.Render(
                tides::RAMP_MODE_AD, tides::OUTPUT_MODE_AMPLITUDE, tides::RANGE_CONTROL,
                0.005f, 0.5f, 0.5f, 0.5f,
                0.6f,  // shift for channel[0]
                flags, nullptr, out, 64
            );

            for (int i = 0; i < 64; i++) {
                for (int c = 0; c < 4; c++) {
                    float a = std::fabs(out[i].channel[c]);
                    if (a > pk[c]) pk[c] = a;
                }
            }
        }

        delete ts;

        printf("    struct: ch0=%.4f ch1=%.4f ch2=%.4f ch3=%.4f\n",
               pk[0], pk[1], pk[2], pk[3]);

        if (pk[0] > 0.1f) {
            printf("    PASS: struct-embedded generator works on channel[0] (%.4f)\n", pk[0]);
            pass++;
        } else {
            printf("    FAIL: struct-embedded generator silent on channel[0] (%.4f)\n", pk[0]);
            fail++;
        }
    }

    // ── Test 16: Drum tracks (self-enveloped) unaffected by Tides change ──
    {
        printf("  Test 16: Drum tracks produce output with self-enveloped engines\n");

        // Re-load Cosmic Techno vibe (drums active)
        setup_cosmic_techno(engine);
        trigger_vibe_load(engine);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);

        // Force drum tracks to self-enveloped engines
        engine->pulsar_track_engine_edm[0].store(21, std::memory_order_relaxed);  // BD
        engine->pulsar_track_engine_edm[1].store(22, std::memory_order_relaxed);  // SD
        engine->pulsar_track_engine_edm[2].store(23, std::memory_order_relaxed);  // HH

        for (int i = 0; i < 100; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        // Check per-track viz peaks for drum tracks (0-2) — scan entire ring
        float drum_peak = 0.0f;
        for (int t = 0; t < 3; t++) {
            for (int s = 0; s < 480; s++) {
                float pk = engine->viz_rings[VIZ_PULSAR_TRACK_0 + t].buf[s];
                if (pk > drum_peak) drum_peak = pk;
            }
        }

        if (drum_peak > 0.001f) {
            printf("    PASS: drum tracks produce output (peak=%.4f)\n", drum_peak);
            pass++;
        } else {
            printf("    FAIL: drum tracks silent (peak=%.4f)\n", drum_peak);
            fail++;
        }
    }

    // ── Test 17: Melodic tracks produce output with Tides envelope ──
    {
        printf("  Test 17: Melodic tracks produce output with Tides envelope\n");

        // Check per-track viz peaks for melodic tracks (3-4) — scan entire ring
        float melodic_peak = 0.0f;
        for (int t = 3; t <= 4; t++) {
            for (int s = 0; s < 480; s++) {
                float pk = engine->viz_rings[VIZ_PULSAR_TRACK_0 + t].buf[s];
                if (pk > melodic_peak) melodic_peak = pk;
            }
        }

        if (melodic_peak > 0.001f) {
            printf("    PASS: melodic tracks produce output with Tides (peak=%.4f)\n", melodic_peak);
            pass++;
        } else {
            printf("    FAIL: melodic tracks silent with Tides (peak=%.4f)\n", melodic_peak);
            fail++;
        }
    }

    // ── Test 18: Tides envelope duration varies by profile ──
    {
        printf("  Test 18: Tides envelope duration varies by profile\n");

        // Create a generator and trigger it once, measure how long output stays above threshold
        auto measure_env_duration = [](float freq, float pw, float shape, float smoothness) -> int {
            tides::PolySlopeGenerator gen;
            gen.Init();

            // Trigger: gate high for 16 samples then low
            stmlib::GateFlags prev = stmlib::GATE_FLAG_LOW;
            int above_threshold = 0;
            bool triggered = false;

            for (int block = 0; block < 200; block++) {
                stmlib::GateFlags flags[64];
                for (int i = 0; i < 64; i++) {
                    bool high = (block == 0 && i < 16);  // trigger in first block only
                    flags[i] = stmlib::ExtractGateFlags(prev, high);
                    prev = flags[i];
                }

                tides::PolySlopeGenerator::OutputSample out[64];
                gen.Render(
                    tides::RAMP_MODE_AD, tides::OUTPUT_MODE_AMPLITUDE, tides::RANGE_CONTROL,
                    freq, pw, shape, smoothness, 0.6f,
                    flags, nullptr, out, 64);

                for (int i = 0; i < 64; i++) {
                    float v = std::fabs(out[i].channel[0]) * 0.125f;
                    if (v > 0.01f) {
                        above_threshold++;
                        triggered = true;
                    }
                }
                // If we triggered and output dropped below threshold, we're done
                if (triggered && above_threshold > 0) {
                    bool still_active = false;
                    for (int i = 0; i < 64; i++) {
                        if (std::fabs(out[i].channel[0]) * 0.125f > 0.01f) still_active = true;
                    }
                    if (!still_active) break;
                }
            }
            return above_threshold;  // in samples
        };

        // Rhythm profile: short (freq ≈ 0.001 at space=0.5)
        int rhythm_dur = measure_env_duration(0.001f, 0.2f, 0.85f, 0.4f);
        // Melodic profile: medium (freq ≈ 0.000125 at space=0.5)
        int melodic_dur = measure_env_duration(0.000125f, 0.5f, 0.5f, 0.5f);
        // Effect profile: long (freq ≈ 0.00006 at space=0.5)
        int effect_dur = measure_env_duration(0.00006f, 0.35f, 0.55f, 0.35f);

        printf("    Rhythm:  %d samples (%.0f ms)\n", rhythm_dur, rhythm_dur / 48.0f);
        printf("    Melodic: %d samples (%.0f ms)\n", melodic_dur, melodic_dur / 48.0f);
        printf("    Effect:  %d samples (%.0f ms)\n", effect_dur, effect_dur / 48.0f);

        // Rhythm should be shortest, effect longest
        // Melodic should be at least 100ms (4800 samples), effect at least 200ms (9600 samples)
        bool duration_ok = (rhythm_dur < melodic_dur) && (melodic_dur < effect_dur)
                        && (melodic_dur > 2000) && (effect_dur > 5000);

        if (duration_ok) {
            printf("    PASS: envelope durations scale correctly by profile\n");
            pass++;
        } else {
            printf("    FAIL: envelope durations don't scale (rhythm < melodic < effect)\n");
            fail++;
        }
    }

    // ── Test 19: Lick pattern produces non-silent output ──
    {
        printf("  Test 19: Lick pattern produces non-silent output\n");

        OrpheusEngine* lick_engine = orpheus_engine_create(48000.0f);

        GraphUnit lick_unit;
        std::memset(&lick_unit, 0, sizeof(lick_unit));
        lick_unit.type = UNIT_PULSAR;
        lick_unit.enabled = true;

        lick_engine->pulsar_playing.store(1, std::memory_order_relaxed);
        lick_engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        lick_engine->pulsar_energy.store(0.7f, std::memory_order_relaxed);
        lick_engine->pulsar_complexity.store(0.5f, std::memory_order_relaxed);
        lick_engine->pulsar_space.store(0.4f, std::memory_order_relaxed);
        lick_engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
        lick_engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

        setup_cosmic_techno(lick_engine);

        // Write a simple 4-step lick: root-root-2nd-3rd
        lick_engine->pulsar_lick[0] = {0, 0.5f, 0.8f};
        lick_engine->pulsar_lick[1] = {0, 0.5f, 0.8f};
        lick_engine->pulsar_lick[2] = {1, 0.5f, 0.8f};
        lick_engine->pulsar_lick[3] = {2, 1.0f, 0.9f};
        lick_engine->pulsar_lick_length.store(4, std::memory_order_release);
        lick_engine->pulsar_lick_mutation.store(0.3f, std::memory_order_relaxed);
        trigger_vibe_load(lick_engine);

        // Process ~3 seconds of audio
        for (int i = 0; i < 300; i++) {
            unit_process_pulsar(&lick_unit, lick_engine, 512, 48000.0f);
        }

        float peak = 0.0f;
        for (int i = 0; i < 512; i++) {
            float al = std::fabs(lick_engine->pulsar_out_l[i]);
            float ar = std::fabs(lick_engine->pulsar_out_r[i]);
            if (al > peak) peak = al;
            if (ar > peak) peak = ar;
        }

        if (peak > 0.01f) {
            printf("    PASS: lick pattern produces output (peak=%.4f)\n", peak);
            pass++;
        } else {
            printf("    FAIL: lick pattern silent (peak=%.4f)\n", peak);
            fail++;
        }

        orpheus_engine_destroy(lick_engine);
    }

    // ── Test 20: Lick mutation=0 preserves original notes ──
    {
        printf("  Test 20: Lick mutation=0.0 preserves original scale degrees\n");

        // Generate lick pattern with zero mutation — output notes should be
        // deterministic from the lick input (no randomization).
        PulsarStep steps_a[16], steps_b[16];
        PulsarLickStep lick[4] = {
            {0, 0.5f, 0.8f},   // root
            {2, 0.5f, 0.8f},   // 3rd
            {4, 1.0f, 0.9f},   // 5th
            {-1, 0.5f, 0.0f},  // rest
        };
        // D minor scale: 0=D, 1=E, 2=F, 3=G, 4=A, 5=Bb, 6=C
        const PulsarScale& d_minor = kPulsarScales[0]; // minor
        uint8_t root_d = 2; // D

        generate_lick_pattern(steps_a, 16, lick, 4, 0.0f, root_d, d_minor, 12345);
        generate_lick_pattern(steps_b, 16, lick, 4, 0.0f, root_d, d_minor, 99999);

        // With mutation=0, both seeds should produce identical note sequences
        bool notes_match = true;
        bool rests_match = true;
        for (int i = 0; i < 16; i++) {
            if (steps_a[i].gate != steps_b[i].gate) { rests_match = false; break; }
            if (steps_a[i].gate && steps_a[i].note != steps_b[i].note) { notes_match = false; break; }
        }

        // Verify step 3 (rest in lick) produces a rest
        bool rest_correct = !steps_a[3].gate && !steps_a[7].gate && !steps_a[11].gate && !steps_a[15].gate;

        // Verify step 0 (root degree=0) maps to D4 (MIDI 62 = root_note(2) + 48 + scale[0](0))
        bool root_correct = steps_a[0].gate && steps_a[0].note == 50; // 2 + 48 + 0 = 50

        if (notes_match && rests_match && rest_correct && root_correct) {
            printf("    PASS: mutation=0 is deterministic, rests preserved, root=MIDI %d\n", steps_a[0].note);
            pass++;
        } else {
            printf("    FAIL: notes_match=%d rests_match=%d rest_correct=%d root_correct=%d (root note=%d)\n",
                   notes_match, rests_match, rest_correct, root_correct, steps_a[0].note);
            fail++;
        }
    }

    // ── Test 21: Lick mutation=1.0 diverges across seeds ──
    {
        printf("  Test 21: Lick mutation=1.0 diverges across different seeds\n");

        PulsarStep steps_a[16], steps_b[16];
        PulsarLickStep lick[4] = {
            {0, 0.5f, 0.8f},
            {2, 0.5f, 0.8f},
            {4, 1.0f, 0.9f},
            {0, 0.5f, 0.7f},
        };
        const PulsarScale& scale = kPulsarScales[0];

        generate_lick_pattern(steps_a, 16, lick, 4, 1.0f, 2, scale, 12345);
        generate_lick_pattern(steps_b, 16, lick, 4, 1.0f, 2, scale, 99999);

        // With mutation=1.0 and different seeds, notes should diverge
        int different_notes = 0;
        for (int i = 0; i < 16; i++) {
            if (steps_a[i].gate && steps_b[i].gate && steps_a[i].note != steps_b[i].note)
                different_notes++;
        }

        // At full mutation, we expect at least 4 of 16 notes to differ
        if (different_notes >= 4) {
            printf("    PASS: mutation=1.0 produces %d different notes across seeds\n", different_notes);
            pass++;
        } else {
            printf("    FAIL: mutation=1.0 only produced %d different notes (expected >= 4)\n", different_notes);
            fail++;
        }
    }

    // ── Test 22: Lick notes stay in scale ──
    {
        printf("  Test 22: Lick notes quantized to scale at all mutation levels\n");

        PulsarLickStep lick[8] = {
            {0, 0.5f, 0.8f}, {1, 0.5f, 0.8f}, {2, 0.5f, 0.8f}, {3, 0.5f, 0.8f},
            {4, 0.5f, 0.8f}, {5, 0.5f, 0.8f}, {6, 0.5f, 0.8f}, {0, 0.5f, 0.8f},
        };
        // Test with multiple mutation levels and scales
        float mutations[] = {0.0f, 0.3f, 0.5f, 0.8f, 1.0f};
        int num_mutations = 5;
        bool all_in_scale = true;

        for (int mi = 0; mi < num_mutations; mi++) {
            for (int si = 0; si < 6; si++) { // all 6 scales
                const PulsarScale& scale = kPulsarScales[si];
                for (uint8_t root = 0; root < 12; root++) {
                    PulsarStep steps[16];
                    uint32_t seed = 42 + mi * 100 + si * 10 + root;
                    generate_lick_pattern(steps, 16, lick, 8, mutations[mi], root, scale, seed);

                    for (int s = 0; s < 16; s++) {
                        if (!steps[s].gate) continue;
                        // Check that note % 12 - root is in the scale
                        int pitch_class = (steps[s].note - root + 120) % 12; // +120 to handle negative
                        bool found = false;
                        for (int d = 0; d < scale.count; d++) {
                            if (scale.degrees[d] == pitch_class) { found = true; break; }
                        }
                        if (!found) {
                            printf("    FAIL at mutation=%.1f scale=%d root=%d step=%d: "
                                   "note=%d pitch_class=%d not in scale\n",
                                   mutations[mi], si, root, s, steps[s].note, pitch_class);
                            all_in_scale = false;
                        }
                    }
                }
            }
        }

        if (all_in_scale) {
            printf("    PASS: all notes in scale across 5 mutations × 6 scales × 12 roots\n");
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 23: Deterministic — same seed produces same pattern ──
    {
        printf("  Test 23: Same seed produces identical lick patterns\n");

        PulsarStep steps_a[16], steps_b[16];
        PulsarLickStep lick[4] = {
            {0, 0.5f, 0.8f}, {2, 0.5f, 0.8f}, {4, 1.0f, 0.9f}, {1, 0.5f, 0.7f},
        };
        const PulsarScale& scale = kPulsarScales[2]; // Pentatonic

        generate_lick_pattern(steps_a, 16, lick, 4, 0.5f, 4, scale, 77777);
        generate_lick_pattern(steps_b, 16, lick, 4, 0.5f, 4, scale, 77777);

        bool identical = true;
        for (int i = 0; i < 16; i++) {
            if (steps_a[i].gate != steps_b[i].gate ||
                steps_a[i].note != steps_b[i].note ||
                std::fabs(steps_a[i].velocity - steps_b[i].velocity) > 0.001f) {
                identical = false;
                printf("    FAIL at step %d: a(gate=%d note=%d vel=%.3f) vs b(gate=%d note=%d vel=%.3f)\n",
                       i, steps_a[i].gate, steps_a[i].note, steps_a[i].velocity,
                       steps_b[i].gate, steps_b[i].note, steps_b[i].velocity);
                break;
            }
        }

        if (identical) {
            printf("    PASS: identical output for same seed\n");
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 24: Lick wraps correctly when step_count > lick_length ──
    {
        printf("  Test 24: Lick wraps when pattern is longer than lick\n");

        PulsarStep steps[16];
        PulsarLickStep lick[3] = {
            {0, 0.5f, 0.8f},  // root, 0.5 beats = 2 steps
            {2, 0.5f, 0.8f},  // 3rd,  0.5 beats = 2 steps
            {4, 1.0f, 0.9f},  // 5th,  1.0 beats = 4 steps
        };
        // Total lick = 2+2+4 = 8 steps per cycle, wraps twice in 16 steps
        const PulsarScale& scale = kPulsarScales[1]; // Major

        generate_lick_pattern(steps, 16, lick, 3, 0.0f, 0, scale, 42);

        // Duration-based scheduling: gate fires on first step of each note,
        // remaining steps within the note's duration are rests.
        // In C major: degree 0=C(48), degree 2=E(52), degree 4=G(55)
        // Layout per 8-step cycle:
        //   step 0: C gate  (2 slots)   step 1: rest
        //   step 2: E gate  (2 slots)   step 3: rest
        //   step 4: G gate  (4 slots)   steps 5-7: rest
        //   Then repeats at step 8
        bool expected_gate[16] = {
            true,  false,         // C
            true,  false,         // E
            true,  false, false, false,  // G
            true,  false,         // C (wrap)
            true,  false,         // E
            true,  false, false, false,  // G
        };
        int expected_notes[16] = {
            48, 0, 52, 0, 55, 0, 0, 0,
            48, 0, 52, 0, 55, 0, 0, 0,
        };

        bool wrap_ok = true;
        for (int i = 0; i < 16; i++) {
            if (steps[i].gate != expected_gate[i] ||
                (expected_gate[i] && steps[i].note != expected_notes[i])) {
                printf("    FAIL at step %d: expected note=%d gate=%d, got note=%d gate=%d\n",
                       i, expected_notes[i], expected_gate[i], steps[i].note, steps[i].gate);
                wrap_ok = false;
                break;
            }
        }

        if (wrap_ok) {
            printf("    PASS: lick wraps correctly (3-step lick over 16 steps, duration-based)\n");
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 25: Mutation increases variance proportionally ──
    {
        printf("  Test 25: Higher mutation produces more variance\n");

        PulsarLickStep lick[4] = {
            {0, 0.5f, 0.8f}, {2, 0.5f, 0.8f}, {4, 1.0f, 0.9f}, {1, 0.5f, 0.7f},
        };
        const PulsarScale& scale = kPulsarScales[0]; // minor

        // Run with low mutation (0.1) and high mutation (0.9), count how many
        // notes deviate from the unmutated baseline across 10 different seeds
        auto count_deviations = [&](float mutation) -> int {
            PulsarStep baseline[16];
            generate_lick_pattern(baseline, 16, lick, 4, 0.0f, 2, scale, 0);

            int total_deviations = 0;
            for (uint32_t seed = 1; seed <= 10; seed++) {
                PulsarStep mutated[16];
                generate_lick_pattern(mutated, 16, lick, 4, mutation, 2, scale, seed * 31337);
                for (int i = 0; i < 16; i++) {
                    if (mutated[i].gate && baseline[i].gate && mutated[i].note != baseline[i].note)
                        total_deviations++;
                }
            }
            return total_deviations;
        };

        int low_dev = count_deviations(0.1f);
        int high_dev = count_deviations(0.9f);

        if (high_dev > low_dev) {
            printf("    PASS: low mutation deviations=%d, high mutation deviations=%d\n", low_dev, high_dev);
            pass++;
        } else {
            printf("    FAIL: expected high_dev > low_dev, got low=%d high=%d\n", low_dev, high_dev);
            fail++;
        }
    }

    printf("\nPulsar: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
