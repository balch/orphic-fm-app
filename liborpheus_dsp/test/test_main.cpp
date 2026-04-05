// Orpheus DSP test runner — dispatches to focused test suites
//
// Build:
//   cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop \
//     -DEURORACK_DIR=/Source/eurorack -DBUILD_TESTS=ON \
//     -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
//   cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test
//
// Run:
//   liborpheus_dsp/build-desktop/orpheus_dsp_test              # all suites
//   liborpheus_dsp/build-desktop/orpheus_dsp_test tides        # single suite
//   liborpheus_dsp/build-desktop/orpheus_dsp_test tides bass   # multiple suites
//   liborpheus_dsp/build-desktop/orpheus_dsp_test --list       # list available suites
#include "test_harness.h"
#include <cstring>

struct TestSuite {
    const char* name;
    bool (*run)();
    bool counts;  // false = informational only (no pass/fail)
};

static TestSuite suites[] = {
    {"unit",            run_unit_tests,            true},
    {"voice",           run_voice_tests,           true},
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
    {"fm-compare",      run_fm_compare_tests,      false},
    {"chain-compare",   run_chain_compare_tests,   false},
    {"bass",            run_bass_voice_tests,      true},
    {"horn",            run_horn_tests,             true},
    {"turntable",       run_turntable_tests,       true},
    {"tides",           run_tides_tests,           true},
    {"preset-voices",   run_preset_voice_tests,    true},
    {"benchmark",       run_benchmark_tests,       true},
    {"pulsar",          run_pulsar_tests,          true},
    {"pulsar_bus",      run_pulsar_bus_tests,      true},
    {"djapp",           run_djapp_graph_tests,     true},
};

static constexpr int kNumSuites = sizeof(suites) / sizeof(suites[0]);

static bool match(const char* name, const char* arg) {
    return strcmp(name, arg) == 0;
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
    int ran = 0;

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

        bool result = suites[s].run();
        if (suites[s].counts) all_pass &= result;
        ran++;
    }

    if (ran == 0) {
        fprintf(stderr, "No matching test suites found. Use --list to see available suites.\n");
        return 1;
    }

    if (!all_pass) {
        fprintf(stderr, "\nFAILURE: One or more tests failed!\n");
        return 1;
    }

    printf("\nSUCCESS: %s tests passed.\n", filtered ? "Selected" : "All");
    return 0;
}
