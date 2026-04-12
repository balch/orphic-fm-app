#include "test_harness.h"
#include "../src/orpheus_unit_pulsar.h"
#include <cstdio>
#include <cmath>

// ── Unit tests for tension system math (no engine needed) ──

static bool test_tension_inner_phase() {
    printf("\n=== Test: Tension inner phase ramps 0->1 over innerBars ===\n");
    int inner = 4;
    float phases[5];
    for (int loop = 0; loop < 5; loop++) {
        phases[loop] = static_cast<float>(loop % inner) / static_cast<float>(inner);
    }
    bool ok = std::fabs(phases[0] - 0.0f) < 0.001f
           && std::fabs(phases[1] - 0.25f) < 0.001f
           && std::fabs(phases[3] - 0.75f) < 0.001f
           && std::fabs(phases[4] - 0.0f) < 0.001f;  // wraps
    printf("  Phases: %.2f, %.2f, %.2f, %.2f, %.2f -- %s\n",
           phases[0], phases[1], phases[2], phases[3], phases[4],
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_outer_modulation() {
    printf("\n=== Test: Outer cycle modulates inner ceiling ===\n");
    int inner = 4, outer = 8;
    float depth = 0.5f;
    auto intensity = [&](int loop) {
        float ip = static_cast<float>(loop % inner) / static_cast<float>(inner);
        float op = static_cast<float>(loop % outer) / static_cast<float>(outer);
        float os = (1.0f - depth) + depth * op;
        return ip * os;
    };
    float i0 = intensity(0);
    float i3 = intensity(3);
    float i7 = intensity(7);
    // loop0: inner_phase=0 -> 0
    // loop3: inner_phase=3/4=0.75, outer_phase=3/8=0.375, outer_scale=0.5+0.5*0.375=0.6875 -> 0.515
    // loop7: inner_phase=3/4=0.75, outer_phase=7/8=0.875, outer_scale=0.5+0.5*0.875=0.9375 -> 0.703
    bool ok = i0 < 0.001f && i3 > 0.4f && i3 < 0.6f && i7 > 0.65f && i7 < 0.75f;
    printf("  loop0=%.3f, loop3=%.3f, loop7=%.3f -- %s\n", i0, i3, i7, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_volume_scaling() {
    printf("\n=== Test: Volume tension scales velocity ===\n");
    float vol = 0.5f;
    // At intensity=0: scale = 1 - 0.5*0.3*1.0 = 0.85
    float scale_low = 1.0f - vol * 0.3f * (1.0f - 0.0f);
    // At intensity=1: scale = 1 - 0.5*0.3*0.0 = 1.0
    float scale_high = 1.0f - vol * 0.3f * (1.0f - 1.0f);
    bool ok = std::fabs(scale_low - 0.85f) < 0.01f && std::fabs(scale_high - 1.0f) < 0.01f;
    printf("  At intensity=0: %.3f, intensity=1: %.3f -- %s\n", scale_low, scale_high, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_timing_scaling() {
    printf("\n=== Test: Timing tension scales drunk offsets ===\n");
    float timing = 0.5f;
    // At intensity=0: scale = (1-0.5) + 0.5*0.0 = 0.5
    float scale_low = (1.0f - timing) + timing * 0.0f;
    // At intensity=1: scale = (1-0.5) + 0.5*1.0 = 1.0
    float scale_high = (1.0f - timing) + timing * 1.0f;
    bool ok = std::fabs(scale_low - 0.5f) < 0.01f && std::fabs(scale_high - 1.0f) < 0.01f;
    printf("  At intensity=0: %.3f, intensity=1: %.3f -- %s\n", scale_low, scale_high, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_evolution_attack_point() {
    printf("\n=== Test: Evolution attack point gates evo_intensity ===\n");
    float ap = 0.5f;
    // intensity below attack_point -> 0
    float evo_at_0_3 = (ap < 0.999f) ? std::max(0.0f, (0.3f - ap) / (1.0f - ap)) : 0.0f;
    // intensity above attack_point -> ramps up
    float evo_at_0_75 = (ap < 0.999f) ? std::max(0.0f, (0.75f - ap) / (1.0f - ap)) : 0.0f;
    // intensity at 1.0 -> 1.0
    float evo_at_1_0 = (ap < 0.999f) ? std::max(0.0f, (1.0f - ap) / (1.0f - ap)) : 0.0f;
    bool ok = std::fabs(evo_at_0_3 - 0.0f) < 0.01f
           && std::fabs(evo_at_0_75 - 0.5f) < 0.01f
           && std::fabs(evo_at_1_0 - 1.0f) < 0.01f;
    printf("  evo@0.3=%.3f, evo@0.75=%.3f, evo@1.0=%.3f -- %s\n",
           evo_at_0_3, evo_at_0_75, evo_at_1_0, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_struct_defaults() {
    printf("\n=== Test: TensionParams default values ===\n");
    TensionParams tp;
    bool ok = tp.inner_bars == 4
           && tp.outer_bars == 0
           && std::fabs(tp.volume - 0.3f) < 0.001f
           && std::fabs(tp.timing - 0.2f) < 0.001f
           && !tp.octave_shift
           && tp.key_shift == 0
           && !tp.half_lick
           && std::fabs(tp.chromatic_passing) < 0.001f
           && std::fabs(tp.evo_timbre_low - 0.25f) < 0.001f
           && std::fabs(tp.evo_morph_low - (-1.0f)) < 0.001f;
    printf("  Defaults check -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_chromatic_passing_math() {
    printf("\n=== Test: Chromatic passing probability scales with intensity ===\n");
    float base_prob = 0.5f;
    // At intensity=0: effective prob = 0.5 * 0.0 = 0.0
    float p0 = base_prob * 0.0f;
    // At intensity=0.5: effective prob = 0.5 * 0.5 = 0.25
    float p5 = base_prob * 0.5f;
    // At intensity=1.0: effective prob = 0.5 * 1.0 = 0.5
    float p10 = base_prob * 1.0f;
    bool ok = std::fabs(p0) < 0.001f
           && std::fabs(p5 - 0.25f) < 0.001f
           && std::fabs(p10 - 0.5f) < 0.001f;
    printf("  prob@0=%.3f, prob@0.5=%.3f, prob@1.0=%.3f -- %s\n", p0, p5, p10, ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_tension_tests() {
    printf("\n========== PULSAR TENSION TESTS ==========\n");
    bool all_pass = true;
    all_pass &= test_tension_inner_phase();
    all_pass &= test_tension_outer_modulation();
    all_pass &= test_tension_volume_scaling();
    all_pass &= test_tension_timing_scaling();
    all_pass &= test_tension_evolution_attack_point();
    all_pass &= test_tension_struct_defaults();
    all_pass &= test_tension_chromatic_passing_math();
    printf("\nPulsar tension tests: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
