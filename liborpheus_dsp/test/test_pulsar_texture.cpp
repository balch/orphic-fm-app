#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_pattern_gen.h"
#include "../src/pulsar_mod_ranges.h"
#include "../src/orpheus_graph.h"
#include "tides2/poly_slope_generator.h"
#include "frames/poly_lfo.h"
#include <cstdio>
#include <cmath>
#include <cstring>

bool run_pulsar_texture_tests() {
    printf("\n=== Pulsar Texture Tests ===\n\n");
    int pass = 0, fail = 0;

    // ── Test 1: Hold step generation produces hold chains ──
    {
        printf("  Test 1: Hold step generation produces hold chains\n");

        PulsarGenreProfile genre = {};
        for (int i = 0; i < 8; i++) genre.base_density[i] = 0.0f;
        genre.base_density[5] = 0.8f;
        genre.ghost_probability = 0.1f;
        genre.note_range_low  = 48;
        genre.note_range_high = 84;
        genre.rhythm_density  = 0.0f;
        genre.progression_style = 0;
        genre.chords_per_bar  = 2;

        // Minor scale
        PulsarScale scale = kPulsarScales[0];

        PulsarStep steps[kMaxPulsarSteps] = {};
        uint32_t seed = 12345;

        generate_effect_pattern(
            steps, 16, /*track_index=*/5,
            genre, /*root=*/2, scale, /*chord_degree=*/0,
            seed,
            /*hold_probability=*/0.9f,
            /*hold_length_min=*/4,
            /*hold_length_max=*/8);

        bool found_hold = false;
        for (int i = 0; i < 16; i++) {
            if (steps[i].hold) { found_hold = true; break; }
        }

        if (found_hold) {
            printf("    PASS: at least one hold step produced\n");
            pass++;
        } else {
            printf("    FAIL: no hold steps produced with hold_probability=0.9\n");
            fail++;
        }
    }

    // ── Test 2: Zero hold probability produces no holds ──
    {
        printf("  Test 2: Zero hold probability produces no holds\n");

        PulsarGenreProfile genre = {};
        for (int i = 0; i < 8; i++) genre.base_density[i] = 0.0f;
        genre.base_density[5] = 0.8f;
        genre.ghost_probability = 0.1f;
        genre.note_range_low  = 48;
        genre.note_range_high = 84;
        genre.rhythm_density  = 0.0f;
        genre.progression_style = 0;
        genre.chords_per_bar  = 2;

        PulsarScale scale = kPulsarScales[0];

        PulsarStep steps[kMaxPulsarSteps] = {};
        uint32_t seed = 12345;

        generate_effect_pattern(
            steps, 16, /*track_index=*/5,
            genre, /*root=*/2, scale, /*chord_degree=*/0,
            seed,
            /*hold_probability=*/0.0f,
            /*hold_length_min=*/4,
            /*hold_length_max=*/8);

        bool found_hold = false;
        for (int i = 0; i < 16; i++) {
            if (steps[i].hold) { found_hold = true; break; }
        }

        if (!found_hold) {
            printf("    PASS: no hold steps with hold_probability=0.0\n");
            pass++;
        } else {
            printf("    FAIL: unexpected hold steps with hold_probability=0.0\n");
            fail++;
        }
    }

    // ── Test 3: Engine mod ranges are within 0-1 bounds ──
    {
        printf("  Test 3: Engine mod ranges are within [0,1] bounds\n");

        bool ok = true;
        for (int i = 0; i < 24; i++) {
            const EngineModRange& r = kEngineModRanges[i];
            if (r.harmonics_min > r.harmonics_max) {
                printf("    FAIL engine %d: harmonics_min %.2f > harmonics_max %.2f\n",
                       i, r.harmonics_min, r.harmonics_max);
                ok = false;
            }
            if (r.timbre_min > r.timbre_max) {
                printf("    FAIL engine %d: timbre_min %.2f > timbre_max %.2f\n",
                       i, r.timbre_min, r.timbre_max);
                ok = false;
            }
            if (r.morph_min > r.morph_max) {
                printf("    FAIL engine %d: morph_min %.2f > morph_max %.2f\n",
                       i, r.morph_min, r.morph_max);
                ok = false;
            }
            // All parameter values in [0,1]
            auto check01 = [&](const char* name, float v) {
                if (v < 0.0f || v > 1.0f) {
                    printf("    FAIL engine %d: %s=%.3f out of [0,1]\n", i, name, v);
                    ok = false;
                }
            };
            check01("harmonics_min", r.harmonics_min);
            check01("harmonics_max", r.harmonics_max);
            check01("timbre_min",    r.timbre_min);
            check01("timbre_max",    r.timbre_max);
            check01("morph_min",     r.morph_min);
            check01("morph_max",     r.morph_max);
            // pitch_cents >= 0
            if (r.pitch_cents < 0.0f) {
                printf("    FAIL engine %d: pitch_cents=%.1f < 0\n", i, r.pitch_cents);
                ok = false;
            }
        }

        if (ok) {
            printf("    PASS: all 24 engine mod ranges within bounds\n");
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 4: apply_mod clamps to safe range ──
    {
        printf("  Test 4: apply_mod clamps to safe range\n");

        // STR engine (19): harmonics 0.28-0.80
        const EngineModRange& str_range = kEngineModRanges[19];
        float base = 0.5f;
        bool ok = true;

        // LFO at +1.0, full depth → should still be within [0.28, 0.80]
        float result_pos = apply_mod(base, +1.0f, 1.0f,
                                     str_range.harmonics_min, str_range.harmonics_max,
                                     str_range.harmonics_safe);
        if (result_pos < str_range.harmonics_min || result_pos > str_range.harmonics_max) {
            printf("    FAIL: apply_mod(+1.0, depth=1.0) = %.4f, out of [%.2f, %.2f]\n",
                   result_pos, str_range.harmonics_min, str_range.harmonics_max);
            ok = false;
        }

        // LFO at -1.0, full depth → should still be within [0.28, 0.80]
        float result_neg = apply_mod(base, -1.0f, 1.0f,
                                     str_range.harmonics_min, str_range.harmonics_max,
                                     str_range.harmonics_safe);
        if (result_neg < str_range.harmonics_min || result_neg > str_range.harmonics_max) {
            printf("    FAIL: apply_mod(-1.0, depth=1.0) = %.4f, out of [%.2f, %.2f]\n",
                   result_neg, str_range.harmonics_min, str_range.harmonics_max);
            ok = false;
        }

        // safe=false → result equals base unchanged
        float result_unsafe = apply_mod(base, +1.0f, 1.0f,
                                        str_range.harmonics_min, str_range.harmonics_max,
                                        /*safe=*/false);
        if (result_unsafe != base) {
            printf("    FAIL: apply_mod(safe=false) = %.4f, expected base=%.4f\n",
                   result_unsafe, base);
            ok = false;
        }

        if (ok) {
            printf("    PASS: apply_mod clamps to [%.2f, %.2f], unsafe returns base\n",
                   str_range.harmonics_min, str_range.harmonics_max);
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 5: Energy volume curve shape ──
    {
        printf("  Test 5: Energy volume curve shape\n");

        bool ok = true;

        float v0   = texture_energy_curve(0.0f);
        float v03  = texture_energy_curve(0.3f);
        float v05  = texture_energy_curve(0.5f);
        float v08  = texture_energy_curve(0.8f);
        float v10  = texture_energy_curve(1.0f);

        // Full volume at extremes
        if (v0 < 0.99f) {
            printf("    FAIL: curve(0.0) = %.4f, expected ~1.0\n", v0);
            ok = false;
        }
        if (v03 < 0.99f) {
            printf("    FAIL: curve(0.3) = %.4f, expected ~1.0\n", v03);
            ok = false;
        }
        // Deeply ducked in mid-zone
        if (v05 > 0.1f) {
            printf("    FAIL: curve(0.5) = %.4f, expected <= 0.1\n", v05);
            ok = false;
        }
        // Recovered at higher energy
        if (v08 < 0.99f) {
            printf("    FAIL: curve(0.8) = %.4f, expected ~1.0\n", v08);
            ok = false;
        }
        if (v10 < 0.99f) {
            printf("    FAIL: curve(1.0) = %.4f, expected ~1.0\n", v10);
            ok = false;
        }

        if (ok) {
            printf("    PASS: energy curve shapes correct (0.0=%.2f 0.3=%.2f 0.5=%.3f 0.8=%.2f 1.0=%.2f)\n",
                   v0, v03, v05, v08, v10);
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 6: Lick/bass boost in mid zone ──
    {
        printf("  Test 6: Lick/bass energy boost in mid zone\n");

        bool ok = true;

        float v0  = lick_bass_energy_boost(0.0f);
        float v05 = lick_bass_energy_boost(0.5f);
        float v10 = lick_bass_energy_boost(1.0f);

        if (v0 < 0.99f || v0 > 1.01f) {
            printf("    FAIL: boost(0.0) = %.4f, expected ~1.0\n", v0);
            ok = false;
        }
        if (v05 < 1.2f) {
            printf("    FAIL: boost(0.5) = %.4f, expected >= 1.2\n", v05);
            ok = false;
        }
        if (v10 < 0.99f || v10 > 1.01f) {
            printf("    FAIL: boost(1.0) = %.4f, expected ~1.0\n", v10);
            ok = false;
        }

        if (ok) {
            printf("    PASS: lick/bass boost (0.0=%.2f 0.5=%.2f 1.0=%.2f)\n",
                   v0, v05, v10);
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 7: PolyLfo initializes and renders without crash ──
    {
        printf("  Test 7: PolyLfo initializes and renders without crash\n");

        frames::PolyLfo lfo;
        lfo.Init();
        lfo.set_shape(16384);         // mid shape
        lfo.set_spread(32768 + 8192); // slight spread above center
        lfo.set_coupling(32768);      // zero coupling (center)

        bool valid = true;
        // Render 256 times with a moderate frequency
        int32_t freq = 1000; // arbitrary phase increment
        for (int i = 0; i < 256; i++) {
            lfo.Render(freq);
        }

        // All 4 channels should produce valid uint8_t values (0-255)
        for (int ch = 0; ch < 4; ch++) {
            uint8_t lvl = lfo.level((uint8_t)ch);
            // level() returns uint8_t so always 0-255; just verifying no crash occurred
            (void)lvl;
        }

        if (valid) {
            printf("    PASS: PolyLfo rendered 256 blocks without crash, levels: %u %u %u %u\n",
                   lfo.level(0), lfo.level(1), lfo.level(2), lfo.level(3));
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test 8: Full Pulsar render with hold steps and mod LFO ──
    {
        printf("  Test 8: Full Pulsar render with hold steps and mod LFO (ambient energy)\n");

        OrpheusEngine* engine = orpheus_engine_create(48000.0f);

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_cosmic_techno(engine);
        trigger_vibe_load(engine);
        engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

        // Set low energy (ambient mode) to exercise TEXTURE/FX tracks
        engine->pulsar_energy.store(0.2f, std::memory_order_relaxed);

        // Enable hold and mod LFO on texture/FX tracks (5-7)
        for (int t = 5; t < 8; t++) {
            engine->pulsar_track_hold_probability[t].store(0.7f, std::memory_order_relaxed);
            engine->pulsar_track_hold_length_min[t].store(2, std::memory_order_relaxed);
            engine->pulsar_track_hold_length_max[t].store(6, std::memory_order_relaxed);
            engine->pulsar_track_mod_lfo_rate[t].store(0.5f, std::memory_order_relaxed);
            engine->pulsar_track_mod_lfo_depth[t].store(0.6f, std::memory_order_relaxed);
            engine->pulsar_track_mod_lfo_shape[t].store(0.3f, std::memory_order_relaxed);
            engine->pulsar_track_mod_lfo_coupling[t].store(0.5f, std::memory_order_relaxed);
        }

        GraphUnit unit;
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;

        // Process ~3 seconds (300 blocks of 512 samples @ 48kHz)
        for (int i = 0; i < 300; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
        }

        // Check peak amplitude
        float peak = 0.0f;
        for (int i = 0; i < 512; i++) {
            float al = std::fabs(engine->pulsar_out_l[i]);
            float ar = std::fabs(engine->pulsar_out_r[i]);
            if (al > peak) peak = al;
            if (ar > peak) peak = ar;
        }

        if (peak > 0.01f) {
            printf("    PASS: output peak = %.4f\n", peak);
            pass++;
        } else {
            printf("    FAIL: output peak = %.4f (expected > 0.01)\n", peak);
            fail++;
        }

        orpheus_engine_destroy(engine);
    }

    // ── Test: Note range override clamps notes ──
    {
        printf("  Test %d: Note range override clamps notes\n", pass + fail + 1);

        PulsarStep steps[32];
        PulsarGenreProfile genre{};
        for (int i = 0; i < 8; i++) genre.base_density[i] = 0.9f;
        genre.note_range_low = 36;
        genre.note_range_high = 84;

        PulsarScale scale = {7, {0,2,3,5,7,8,10,0,0,0,0,0}};
        uint32_t seed = 123;

        // Override range to 40-55, no holds
        generate_effect_pattern(steps, 16, 5, genre, 60, scale, 0, seed,
                                0.0f, 2, 8, -1.0f, 40, 55);

        bool ok = true;
        int gated = 0;
        for (int i = 0; i < 16; i++) {
            if (steps[i].gate) {
                gated++;
                if (steps[i].note < 40 || steps[i].note > 55) {
                    printf("    FAIL: note %d outside range 40-55\n", steps[i].note);
                    ok = false;
                }
            }
        }
        if (gated == 0) {
            printf("    FAIL: no gated steps generated\n");
            ok = false;
        }
        if (ok) {
            printf("    PASS: %d notes all within 40-55\n", gated);
            pass++;
        } else {
            fail++;
        }
    }

    // ── Test: Density override changes gate count ──
    {
        printf("  Test %d: Density override changes gate count\n", pass + fail + 1);

        PulsarStep steps_low[32], steps_high[32];
        PulsarGenreProfile genre{};
        for (int i = 0; i < 8; i++) genre.base_density[i] = 0.5f;
        genre.note_range_low = 36;
        genre.note_range_high = 72;

        PulsarScale scale = {7, {0,2,3,5,7,8,10,0,0,0,0,0}};
        uint32_t seed1 = 42, seed2 = 42;

        // Low density override
        generate_effect_pattern(steps_low, 32, 5, genre, 60, scale, 0, seed1,
                                0.0f, 2, 8, 0.1f, 0, 0);
        // High density override
        generate_effect_pattern(steps_high, 32, 5, genre, 60, scale, 0, seed2,
                                0.0f, 2, 8, 0.9f, 0, 0);

        int gates_low = 0, gates_high = 0;
        for (int i = 0; i < 32; i++) {
            if (steps_low[i].gate) gates_low++;
            if (steps_high[i].gate) gates_high++;
        }

        if (gates_high > gates_low) {
            printf("    PASS: low=%d high=%d\n", gates_low, gates_high);
            pass++;
        } else {
            printf("    FAIL: low=%d high=%d (expected high > low)\n", gates_low, gates_high);
            fail++;
        }
    }

    // ── Summary ──
    printf("\n  Pulsar Texture: %d passed, %d failed\n", pass, fail);
    TEST_SUITE_RETURN(pass, fail);
}
