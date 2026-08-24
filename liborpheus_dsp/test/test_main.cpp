// Orpheus DSP test runner — dispatches to focused test suites
//
// Build:
//   cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop \
//     -DEURORACK_DIR=/Source/eurorack -DBUILD_TESTS=ON \
//     -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
//   cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test
//
// Run:
//   liborpheus_dsp/build-desktop/orpheus_dsp_test              # all suites
//   liborpheus_dsp/build-desktop/orpheus_dsp_test tides        # single suite
//   liborpheus_dsp/build-desktop/orpheus_dsp_test tides bass   # multiple suites
//   liborpheus_dsp/build-desktop/orpheus_dsp_test --list       # list suites
//
// Diagnostics:
//   ORPHEUS_DUMP_GRAPH=1 ...                                   # re-enable graph dump
#include "test_harness.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <vector>

// ── Per-suite counting globals (declared in test_harness.h) ─────────
int g_suite_passes = 0;
int g_suite_fails  = 0;

struct TestSuite {
    const char* name;
    bool (*run)();
    bool counts;  // false = informational only (no pass/fail)
};

static TestSuite suites[] = {
    {"unit",            run_unit_tests,            true},
    {"voice",           run_voice_tests,           true},
    {"lpg",             run_lpg_tests,             true},
    {"engine",          run_engine_render_tests,   true},
    {"effects",         run_effects_tests,         true},
    {"graph",           run_graph_tests,           true},
    {"output-chain",    run_output_chain_tests,    true},
    {"snapshot",        run_snapshot_tests,        true},
    {"drums",           run_drums_graph_tests,     true},
    {"lfo",             run_lfo_tests,             true},
    {"control-routing", run_control_routing_tests, true},
    {"headroom",        run_headroom_tests,        true},
    {"warps",           run_warps_tests,           true},
    {"bridge",          run_bridge_audit,          true},
    {"preset",          run_preset_tests,          true},
    {"viz",             run_viz_tests,             true},
    {"spectrum",        run_spectrum_tests,        true},
    {"fm-compare",      run_fm_compare_tests,      false},
    {"chain-compare",   run_chain_compare_tests,   false},
    {"bass",            run_bass_voice_tests,      true},
    {"horn",            run_horn_tests,            true},
    {"turntable",       run_turntable_tests,       true},
    {"tides",           run_tides_tests,           true},
    {"preset-voices",   run_preset_voice_tests,    true},
    {"benchmark",       run_benchmark_tests,       true},
    {"pulsar",          run_pulsar_tests,          true},
    {"pulsar_bus",      run_pulsar_bus_tests,      true},
    {"djapp",           run_djapp_graph_tests,     true},
    {"bar_strategy",    run_bar_strategy_tests,    true},
    {"pulsar_routing",  run_pulsar_routing_tests,  true},
    {"pulsar_chords",   run_pulsar_chords_tests,   true},
    {"comping",         run_pulsar_comping_tests,  true},
    {"pulsar_signal",   run_pulsar_signal_tests,   true},
    {"pulsar_void",    run_pulsar_void_tests,     true},
    {"wah_core",        run_wah_core_tests,        true},
    {"pulsar_lick_wah", run_pulsar_lick_wah_tests, true},
    {"pulsar_wah_anomaly", run_pulsar_wah_anomaly_tests, true},
    {"pulsar_anomaly_arm", run_pulsar_anomaly_arm_tests, true},
    {"pulsar_lick_select", run_pulsar_lick_select_tests, true},
    {"tension",         run_pulsar_tension_tests,  true},
    {"pulsar_solos",    run_pulsar_solos_tests,    true},
    {"pulsar_sections", run_pulsar_sections_tests, true},
    {"pulsar_timing",   run_pulsar_timing_tests,   true},
    {"pulsar_start",    run_pulsar_start_tests,    true},
    {"pulsar_marshalling", run_pulsar_marshalling_tests, true},
    {"pulsar_bass_line", run_pulsar_bass_line_tests, true},
    {"lick_offset",     run_pulsar_lick_offset_tests, true},
    {"pulsar_outro_request", run_pulsar_outro_request_tests, true},
    {"oboe_buffer",     run_oboe_buffer_tests,     true},
    {"texture",         run_pulsar_texture_tests,  true},
    {"complexity_cap",  run_pulsar_complexity_cap_tests, true},
    {"pattern_gen",     run_pulsar_pattern_gen_tests, true},
    {"band_solo",       run_pulsar_band_solo_tests, true},
    {"lick_techniques", run_pulsar_lick_techniques_tests, true},
    {"pulsar_glide",    run_pulsar_glide_tests,    true},
    {"markov_solo",     run_markov_solo_tests,     true},
    {"analysis",        run_pulsar_analysis_tests, false},  // manual: writes WAV files
    {"braids",          run_braids_tests,          true},
    {"pulsar_pinning",  run_pulsar_pinning_tests,  true},
    {"chaos",           run_chaos_tests,           true},
    {"chaos_lorenz",    run_chaos_lorenz_tests,    true},
    {"chaos_rossler",   run_chaos_rossler_tests,   true},
    {"chaos_duffing",   run_chaos_duffing_tests,   true},
    {"chaos_henon",     run_chaos_henon_tests,     true},
    {"chaos_chua",      run_chaos_chua_tests,      true},
    {"pulsar_chaos",    run_pulsar_chaos_tests,    true},
    {"master_fader",    run_master_fader_tests,    true},
    {"master_tape_stop", run_master_tape_stop_tests, true},
    {"master_scratch",  run_master_scratch_tests,   true},
    {"master_leslie",   run_master_leslie_tests,    true},
    {"master_fader_pulsar", run_master_fader_pulsar_routing_tests, true},
    {"master_tape_stop_pulsar", run_master_tape_stop_pulsar_routing_tests, true},
    {"master_crossfade", run_master_crossfade_tests, true},
    {"master_swell",    run_master_swell_tests,    true},
    {"master_cut",      run_master_cut_tests,      true},
    {"graph-swap",      run_graph_swap_tests,      true},
};

static constexpr int kNumSuites = sizeof(suites) / sizeof(suites[0]);

struct SuiteResult {
    const char* name;
    const char* status;  // "PASS", "FAIL", "INFO"
    int passes;
    int fails;
    long long wall_ms;
};

static bool match(const char* name, const char* arg) {
    return strcmp(name, arg) == 0;
}

static void print_summary(const std::vector<SuiteResult>& results, long long total_ms) {
    int suites_passed = 0;
    int suites_counted = 0;
    for (const auto& r : results) {
        if (strcmp(r.status, "INFO") == 0) continue;
        suites_counted++;
        if (strcmp(r.status, "PASS") == 0) suites_passed++;
    }

    printf("\n==================================================\n");
    printf("SUMMARY (%d/%d suites passed, wall time %.1fs)\n",
           suites_passed, suites_counted, total_ms / 1000.0);
    printf("==================================================\n");
    for (const auto& r : results) {
        if (r.passes == 0 && r.fails == 0) {
            printf("  %-18s %-4s    -          %5.1fs\n",
                   r.name, r.status, r.wall_ms / 1000.0);
        } else {
            char counts[32];
            snprintf(counts, sizeof(counts), "%d/%d tests",
                     r.passes, r.passes + r.fails);
            printf("  %-18s %-4s  %-12s %5.1fs\n",
                   r.name, r.status, counts, r.wall_ms / 1000.0);
        }
    }
    printf("==================================================\n");

    // Top 5 slowest suites
    std::vector<SuiteResult> sorted = results;
    std::sort(sorted.begin(), sorted.end(),
              [](const SuiteResult& a, const SuiteResult& b) {
                  return a.wall_ms > b.wall_ms;
              });
    printf("Slowest suites:\n");
    int n = std::min((int)sorted.size(), 5);
    for (int i = 0; i < n; i++) {
        printf("  %d. %-18s %5.1fs\n",
               i + 1, sorted[i].name, sorted[i].wall_ms / 1000.0);
    }
    printf("==================================================\n");
}

int main(int argc, char* argv[]) {
    // --list: print available suites and exit
    for (int i = 1; i < argc; i++) {
        if (match(argv[i], "--list") || match(argv[i], "-l")) {
            printf("Available test suites:\n");
            for (int s = 0; s < kNumSuites; s++)
                printf("  %s%s\n", suites[s].name, suites[s].counts ? "" : " (informational)");
            return 0;
        }
    }

    bool filtered = argc > 1;
    bool all_pass = true;
    std::vector<SuiteResult> results;

    auto total_start = std::chrono::steady_clock::now();

    for (int s = 0; s < kNumSuites; s++) {
        bool should_run = !filtered;
        if (filtered) {
            for (int i = 1; i < argc; i++) {
                if (match(suites[s].name, argv[i])) {
                    should_run = true;
                    break;
                }
            }
        }
        if (!should_run) continue;

        g_suite_passes = 0;
        g_suite_fails  = 0;

        auto t0 = std::chrono::steady_clock::now();
        bool result = suites[s].run();
        auto t1 = std::chrono::steady_clock::now();

        SuiteResult r;
        r.name    = suites[s].name;
        r.passes  = g_suite_passes;
        r.fails   = g_suite_fails;
        r.wall_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        if (!suites[s].counts) {
            r.status = "INFO";
        } else if (result) {
            r.status = "PASS";
        } else {
            r.status = "FAIL";
            all_pass = false;
        }
        results.push_back(r);
    }

    auto total_end = std::chrono::steady_clock::now();
    long long total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        total_end - total_start).count();

    if (results.empty()) {
        fprintf(stderr, "No matching test suites found. Use --list to see available suites.\n");
        return 1;
    }

    print_summary(results, total_ms);

    if (!all_pass) {
        fprintf(stderr, "\nFAILURE: One or more test suites failed.\n");
        return 1;
    }

    printf("\nSUCCESS: %s tests passed.\n", filtered ? "Selected" : "All");
    return 0;
}
