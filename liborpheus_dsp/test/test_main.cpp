// Orpheus DSP test runner — dispatches to focused test suites
#include "test_harness.h"

int main() {
    bool all_pass = true;

    all_pass &= run_unit_tests();
    all_pass &= run_voice_tests();
    all_pass &= run_engine_render_tests();
    all_pass &= run_effects_tests();
    all_pass &= run_graph_tests();
    all_pass &= run_output_chain_tests();
    all_pass &= run_snapshot_tests();
    all_pass &= run_drums_graph_tests();
    all_pass &= run_lfo_tests();
    all_pass &= run_control_routing_tests();
    all_pass &= run_headroom_tests();
    all_pass &= run_warps_tests();
    all_pass &= run_bridge_audit();
    all_pass &= run_preset_tests();
    all_pass &= run_viz_tests();
    run_chain_compare_tests(); // comparison WAVs (no pass/fail, just output)
    run_fm_compare_tests();    // FM comparison WAVs
    all_pass &= run_bass_voice_tests();
    all_pass &= run_horn_tests();
    all_pass &= run_benchmark_tests(); // always last — timing

    if (!all_pass) {
        fprintf(stderr, "\nFAILURE: One or more tests failed!\n");
        return 1;
    }

    printf("\nSUCCESS: All tests passed.\n");
    return 0;
}
