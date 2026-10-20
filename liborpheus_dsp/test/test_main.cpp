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
//   liborpheus_dsp/build-desktop/orpheus_dsp_test -j           # one process per suite, ncpu wide
//   liborpheus_dsp/build-desktop/orpheus_dsp_test -j4 tides    # -jN caps the width
//
// Diagnostics:
//   ORPHEUS_DUMP_GRAPH=1 ...                                   # re-enable graph dump
#include "test_harness.h"
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>

extern char** environ;

// ── Per-suite counting globals (declared in test_harness.h) ─────────
bool g_filtered_run = false;
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
    {"scope",           run_scope_tests,           true},
    {"record",          run_record_ring_tests,     true},
    {"engine_slot",     run_engine_slot_tests,     true},
    {"fm-compare",      run_fm_compare_tests,      true},
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
    {"pulsar_score_clock", run_pulsar_score_clock_tests, true},
    {"pulsar_score_sched", run_pulsar_score_sched_tests, true},
    {"score_voice_alloc", run_score_voice_alloc_tests, true},
    {"score_spike",     run_score_voice_spike_tests, false},
    {"score_chord",     run_score_chord_path_tests, true},
    {"score_marshal",   run_score_marshalling_tests, false},  // informational: timing probe
    {"score_envelope",  run_score_voice_envelope_tests, true},
    {"pulsar_section_request", run_pulsar_section_request_tests, true},
    {"tension",         run_pulsar_tension_tests,  true},
    {"pulsar_solos",    run_pulsar_solos_tests,    true},
    {"pulsar_sections", run_pulsar_sections_tests, true},
    {"pulsar_seams",    run_pulsar_section_seams_tests, true},
    {"pulsar_timing",   run_pulsar_timing_tests,   true},
    {"pulsar_start",    run_pulsar_start_tests,    true},
    {"pulsar_kraken",   run_pulsar_kraken_tests,   true},
    {"pulsar_marshalling", run_pulsar_marshalling_tests, true},
    {"pulsar_bass_line", run_pulsar_bass_line_tests, true},
    {"lick_offset",     run_pulsar_lick_offset_tests, true},
    {"ghost_pitch",     run_pulsar_ghost_pitch_tests, true},
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
    {"pulsar_storm",    run_pulsar_storm_tests,    true},
    {"pulsar_trans_fx", run_pulsar_transition_fx_tests, true},
    {"pulsar_breathe",  run_pulsar_breathe_tests,  true},
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
    {"osc",             run_osc_tests,             true},
    {"pulsar_osc",      run_pulsar_osc_tests,      true},
    {"mist",            run_mist_tests,            true},
    {"mist_samples",    run_mist_samples,          false},  // manual: writes WAV files
    {"vibe_record",     run_vibe_record,           false},  // manual: writes WAV files
    {"pulsar_mist",     run_pulsar_mist_tests,     true},
    {"pulsar_vibrato",  run_pulsar_vibrato_tests,  true},
    {"pulsar_pitch_rise", run_pulsar_pitch_rise_tests, true},
    {"pulsar_harmonics_rise", run_pulsar_harmonics_rise_tests, true},
    {"pulsar_slot",     run_pulsar_slot_tests,     true},
    {"pulsar_section_ramp", run_pulsar_section_ramp_tests, true},
    {"pulsar_texture_line", run_pulsar_texture_line_tests, true},
    {"pulsar_pad_restrike", run_pulsar_pad_restrike_tests, true},
    {"pulsar_audition_gain", run_pulsar_audition_gain_tests, true},
    {"pulsar_grain_fx", run_pulsar_grain_fx_tests, true},
    {"pulsar_lick_growth", run_pulsar_lick_growth_tests, true},
    {"pulsar_complexity_calm", run_pulsar_complexity_calm_tests, true},
    {"pulsar_speech",   run_pulsar_speech_tests,   true},
    {"pulsar_street",   run_pulsar_street_tests,   true},
    {"pulsar_str_lick", run_pulsar_str_lick_tests, true},

// Rows for any extra suites CMake added to the target. Absent is the normal case.
#if __has_include("noop_suites.inc")
#include "noop_suites.inc"
#endif
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

static SuiteResult run_in_process(int s) {
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
    if (!suites[s].counts) r.status = "INFO";
    else r.status = result ? "PASS" : "FAIL";
    return r;
}

// ── Parallel mode (-j) ──────────────────────────────────────────────
// Each suite runs in its own child process (`<self> --child <filtered> <name>`), so suites keep
// their globals and statics to themselves. A child's output is buffered and printed whole when it
// exits, and its last line is a machine-readable result the parent parses.
static const char* kResultTag = "@@ORPHEUS_SUITE_RESULT";

static int run_child(const char* filtered_flag, const char* name) {
    g_filtered_run = strcmp(filtered_flag, "1") == 0;
    // stdout is a pipe here; line buffering keeps it interleaved with stderr as on a terminal.
    setvbuf(stdout, nullptr, _IOLBF, 0);
    for (int s = 0; s < kNumSuites; s++) {
        if (!match(suites[s].name, name)) continue;
        SuiteResult r = run_in_process(s);
        fflush(stderr);
        printf("\n%s %s %d %d %lld\n", kResultTag, r.status, r.passes, r.fails, r.wall_ms);
        fflush(stdout);
        return 0;
    }
    fprintf(stderr, "Unknown suite: %s\n", name);
    return 1;
}

// Per-suite wall times from earlier -j runs, kept next to the binary as "<name> <ms>" lines.
// Wall time can't beat the slowest suite and only matches it when that suite starts first.
using Timings = std::vector<std::pair<std::string, long long>>;

static std::string timings_path(const char* self) {
    std::string p(self);
    size_t slash = p.rfind('/');
    return (slash == std::string::npos ? std::string(".") : p.substr(0, slash)) +
           "/orpheus_dsp_test.timings";
}

static long long* find_timing(Timings& t, const char* name) {
    for (auto& e : t) if (e.first == name) return &e.second;
    return nullptr;
}

static Timings load_timings(const std::string& path) {
    Timings t;
    FILE* f = fopen(path.c_str(), "r");
    if (!f) return t;
    char name[128];
    long long ms;
    while (fscanf(f, "%127s %lld", name, &ms) == 2) t.emplace_back(name, ms);
    fclose(f);
    return t;
}

// Merges this run into the file, so a filtered run keeps the other suites' times.
static void save_timings(const std::string& path, Timings t, const std::vector<SuiteResult>& results) {
    for (const auto& r : results) {
        if (long long* ms = find_timing(t, r.name)) *ms = r.wall_ms;
        else t.emplace_back(r.name, r.wall_ms);
    }
    std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "w");
    if (!f) return;
    for (const auto& e : t) fprintf(f, "%s %lld\n", e.first.c_str(), e.second);
    fclose(f);
    rename(tmp.c_str(), path.c_str());
}

// Slowest first. A suite with no recorded time goes to the front: it may be the long one.
static void order_for_dispatch(std::vector<int>& picked, Timings& timings) {
    std::stable_sort(picked.begin(), picked.end(), [&](int a, int b) {
        long long* ta = find_timing(timings, suites[a].name);
        long long* tb = find_timing(timings, suites[b].name);
        if (!ta || !tb) return !ta && tb;
        return *ta > *tb;
    });
}

struct Child {
    int suite;
    pid_t pid;
    int fd;
    std::string out;
};

static SuiteResult finish_child(Child& c, int wait_status) {
    SuiteResult r{suites[c.suite].name, "FAIL", 0, 0, 0};
    size_t at = c.out.rfind(kResultTag);
    char status[8] = {0};
    if (at != std::string::npos &&
        sscanf(c.out.c_str() + at + strlen(kResultTag), " %7s %d %d %lld",
               status, &r.passes, &r.fails, &r.wall_ms) == 4) {
        r.status = strcmp(status, "PASS") == 0 ? "PASS" : strcmp(status, "INFO") == 0 ? "INFO" : "FAIL";
        c.out.erase(at);
    } else {
        // Crashed or exited before reporting: count it as a failure and say why.
        if (WIFSIGNALED(wait_status))
            c.out += "\n[suite " + std::string(r.name) + " killed by signal " +
                     std::to_string(WTERMSIG(wait_status)) + "]\n";
        else
            c.out += "\n[suite " + std::string(r.name) + " exited without a result]\n";
    }
    if (!suites[c.suite].counts) r.status = "INFO";
    fwrite(c.out.data(), 1, c.out.size(), stdout);
    fflush(stdout);
    return r;
}

static std::vector<SuiteResult> run_parallel(const char* self, std::vector<int> picked, bool filtered,
                                             int jobs) {
    const std::string tpath = timings_path(self);
    Timings timings = load_timings(tpath);
    order_for_dispatch(picked, timings);
    std::vector<SuiteResult> results;
    std::vector<Child> running;
    size_t next = 0;
    const char* filtered_flag = filtered ? "1" : "0";

    while (next < picked.size() || !running.empty()) {
        while (next < picked.size() && (int)running.size() < jobs) {
            int s = picked[next++];
            int fds[2];
            if (pipe(fds) != 0) { perror("pipe"); exit(2); }
            posix_spawn_file_actions_t fa;
            posix_spawn_file_actions_init(&fa);
            posix_spawn_file_actions_adddup2(&fa, fds[1], STDOUT_FILENO);
            posix_spawn_file_actions_adddup2(&fa, fds[1], STDERR_FILENO);
            posix_spawn_file_actions_addclose(&fa, fds[0]);
            posix_spawn_file_actions_addclose(&fa, fds[1]);
            char* child_argv[] = {const_cast<char*>(self), const_cast<char*>("--child"),
                                  const_cast<char*>(filtered_flag),
                                  const_cast<char*>(suites[s].name), nullptr};
            pid_t pid;
            int rc = posix_spawn(&pid, self, &fa, nullptr, child_argv, environ);
            posix_spawn_file_actions_destroy(&fa);
            close(fds[1]);
            if (rc != 0) { fprintf(stderr, "posix_spawn %s: %s\n", self, strerror(rc)); exit(2); }
            running.push_back({s, pid, fds[0], {}});
        }

        std::vector<pollfd> pfds;
        for (auto& c : running) pfds.push_back({c.fd, POLLIN, 0});
        if (poll(pfds.data(), pfds.size(), -1) < 0) { perror("poll"); exit(2); }

        for (size_t i = running.size(); i-- > 0;) {
            if (!(pfds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            char buf[16384];
            ssize_t n = read(running[i].fd, buf, sizeof(buf));
            if (n > 0) { running[i].out.append(buf, (size_t)n); continue; }
            // EOF: the child closed its output, so it is exiting.
            close(running[i].fd);
            int wait_status = 0;
            waitpid(running[i].pid, &wait_status, 0);
            results.push_back(finish_child(running[i], wait_status));
            running.erase(running.begin() + (long)i);
        }
    }

    save_timings(tpath, timings, results);

    // Summary rows in table order, matching the sequential run.
    std::sort(results.begin(), results.end(), [](const SuiteResult& a, const SuiteResult& b) {
        auto index = [](const char* name) {
            for (int s = 0; s < kNumSuites; s++) if (match(suites[s].name, name)) return s;
            return kNumSuites;
        };
        return index(a.name) < index(b.name);
    });
    return results;
}

// "-j" means one job per core, "-j8" or "-j 8" an explicit width. Returns 0 when absent.
static int parse_jobs(int& i, int argc, char* argv[]) {
    const char* a = argv[i];
    if (strncmp(a, "-j", 2) != 0 && strcmp(a, "--jobs") != 0) return 0;
    const char* n = strncmp(a, "-j", 2) == 0 && a[2] ? a + 2 : nullptr;
    if (!n && i + 1 < argc && argv[i + 1][0] >= '0' && argv[i + 1][0] <= '9') n = argv[++i];
    int jobs = n ? atoi(n) : (int)sysconf(_SC_NPROCESSORS_ONLN);
    return jobs > 0 ? jobs : 1;
}

int main(int argc, char* argv[]) {
    if (argc == 4 && match(argv[1], "--child")) return run_child(argv[2], argv[3]);

    // --list: print available suites and exit
    for (int i = 1; i < argc; i++) {
        if (match(argv[i], "--list") || match(argv[i], "-l")) {
            printf("Available test suites:\n");
            for (int s = 0; s < kNumSuites; s++)
                printf("  %s%s\n", suites[s].name, suites[s].counts ? "" : " (informational)");
            return 0;
        }
    }

    int jobs = 0;
    std::vector<const char*> names;
    for (int i = 1; i < argc; i++) {
        int j = parse_jobs(i, argc, argv);
        if (j > 0) jobs = j;
        else names.push_back(argv[i]);
    }

    bool filtered = !names.empty();
    g_filtered_run = filtered;
    std::vector<int> picked;
    for (int s = 0; s < kNumSuites; s++) {
        bool should_run = !filtered;
        for (const char* n : names) {
            if (match(suites[s].name, n)) { should_run = true; break; }
        }
        if (should_run) picked.push_back(s);
    }

    auto total_start = std::chrono::steady_clock::now();

    std::vector<SuiteResult> results;
    if (jobs > 0 && !picked.empty()) {
        results = run_parallel(argv[0], picked, filtered, jobs);
    } else {
        for (int s : picked) results.push_back(run_in_process(s));
    }
    bool all_pass = true;
    for (const auto& r : results) {
        if (strcmp(r.status, "FAIL") == 0) all_pass = false;
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
