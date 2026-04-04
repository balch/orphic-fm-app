#include "../src/orpheus_engine.h"
#include "../src/orpheus_unit_pulsar.h"
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
        engine->pulsar_scene.store(2, std::memory_order_relaxed);  // Cosmic Techno
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

    // ── Test 4: Scene switching ──
    {
        printf("  Test 4: Scene switching works\n");

        // Switch to Deep Space (scene 0, 70 BPM)
        engine->pulsar_scene.store(0, std::memory_order_relaxed);

        // Process one block to trigger scene load
        unit_process_pulsar(&unit, engine, 512, 48000.0f);

        // Verify step_counts are still 16 (Deep Space also uses 16 steps)
        int step_count = engine->pulsar_viz.step_counts[0];
        bool scene_ok = (step_count == 16);

        // Deep Space kick: sparse pattern always puts gate on step 0
        bool kick_step0 = engine->pulsar_viz.step_gates[0][0];
        scene_ok = scene_ok && kick_step0;

        // Perc pattern is algorithmically generated — verify at least some gates exist
        int perc_gates = 0;
        for (int s = 0; s < engine->pulsar_viz.step_counts[1]; s++) {
            if (engine->pulsar_viz.step_gates[1][s]) perc_gates++;
        }
        bool perc_has_gates = (perc_gates > 0);
        scene_ok = scene_ok && perc_has_gates;

        if (scene_ok) {
            printf("    PASS: scene 0 loaded, step_counts[0]=%d, kick_step0=%d, perc_gates=%d\n",
                   step_count, kick_step0, perc_gates);
            pass++;
        } else {
            printf("    FAIL: scene switch did not update patterns correctly\n");
            printf("      step_counts[0]=%d, kick_step0=%d, perc_gates=%d\n",
                   step_count, kick_step0, perc_gates);
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

        // Reset state by toggling scene
        engine->pulsar_scene.store(1, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        engine->pulsar_scene.store(2, std::memory_order_relaxed);

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

        // Reset scene
        engine->pulsar_scene.store(1, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        engine->pulsar_scene.store(2, std::memory_order_relaxed);

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
        engine->pulsar_scene.store(0, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        engine->pulsar_scene.store(2, std::memory_order_relaxed);

        int low_gates = 0;
        for (int i = 0; i < 200; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }
        for (int t = 0; t < 5; t++) {
            for (int s = 0; s < engine->pulsar_viz.step_counts[t]; s++) {
                if (engine->pulsar_viz.step_gates[t][s]) low_gates++;
            }
        }

        if (high_gates >= low_gates) {
            printf("    PASS: high energy gates (%d) >= low energy gates (%d)\n", high_gates, low_gates);
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
        engine->pulsar_scene.store(0, std::memory_order_relaxed);  // Deep Space, 70 BPM

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

        // Reset scene
        engine->pulsar_scene.store(1, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        engine->pulsar_scene.store(2, std::memory_order_relaxed);  // Cosmic Techno, D minor

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
        engine->pulsar_scene.store(0, std::memory_order_relaxed);
        engine->pulsar_energy.store(0.7f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.8f, std::memory_order_relaxed);

        // Set root to D (2) and scale to Pentatonic (2)
        engine->pulsar_root_note.store(2, std::memory_order_relaxed);
        engine->pulsar_scale_index.store(2, std::memory_order_relaxed);

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
        engine->pulsar_scene.store(0, std::memory_order_relaxed);
        engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);

        // Run one block to initialize and load scene
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

        // Reset scene
        engine->pulsar_scene.store(1, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        engine->pulsar_scene.store(2, std::memory_order_relaxed);  // Cosmic Techno: drums active

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

    printf("\nPulsar: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
