// Marshalling probe for the notated-score wire path. Covers the C++ half only:
// orpheus_engine_set_port's strcmp dispatch chain, which "score_ev_" sits 55 comparisons
// into. Does NOT cover the JNI crossing or the Kotlin side, which likely dominate on
// Android — a fast result here moves the risk rather than retiring it.
// Informational: prints, never fails. A threshold would encode this machine's speed.
#include "test_harness.h"
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

static constexpr const char* kPulsarUri = "org.balch.orpheus.plugins.pulsar";

// Beethoven 5 mvt 1: 8,295 notes, per :features:pulsar:inspectScore. Phase A writes 4
// fields per event; polyphony adds a voice lane for 5.
static constexpr int kRealisticNotes = 8295;

// Built before the clock starts. Formatting inside the timed loop charges snprintf to
// dispatch, and only the indexed symbols need it — that skewed the first cut to 2.24x.
static std::vector<std::string> build_symbols(const char* prefix, int count, bool indexed) {
    std::vector<std::string> out;
    out.reserve(count);
    char buf[64];
    for (int i = 0; i < count; i++) {
        if (indexed) snprintf(buf, sizeof(buf), "%s%d", prefix, i);
        else         snprintf(buf, sizeof(buf), "%s", prefix);
        out.emplace_back(buf);
    }
    return out;
}

static double time_dispatch(const char* label, const std::vector<std::string>& symbols) {
    OrpheusEngine* engine = orpheus_engine_create(48000);

    // Warm: the first call faults in the engine's pages, which would otherwise be charged
    // entirely to whichever configuration ran first.
    orpheus_engine_set_port(engine, kPulsarUri, symbols[0].c_str(), 1.0f);

    const auto t0 = std::chrono::steady_clock::now();
    for (size_t i = 0; i < symbols.size(); i++) {
        orpheus_engine_set_port(engine, kPulsarUri, symbols[i].c_str(),
                                static_cast<float>(i & 0x7F));
    }
    const auto t1 = std::chrono::steady_clock::now();

    const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    printf("  %-34s %8zu writes  %8.2f ms  %7.1f ns/write\n",
           label, symbols.size(), ms, (ms * 1e6) / symbols.size());
    orpheus_engine_destroy(engine);
    return ms;
}

bool run_score_marshalling_tests() {
    printf("\n========== Score Marshalling Probe ==========\n");
    printf("\nHow long does pushing a whole notated score through the string-keyed\n");
    printf("port path take? C++ side only -- no JNI, no Kotlin.\n\n");

    const int mono_writes = kRealisticNotes * 4;
    const int poly_writes = kRealisticNotes * 5;

    const std::vector<std::string> poly_symbols = build_symbols("score_ev_", poly_writes, true);
    const std::vector<std::string> mono_symbols = build_symbols("score_ev_", mono_writes, true);
    const std::vector<std::string> shallow_symbols = build_symbols("playing", poly_writes, false);

    printf("Realistic load (Beethoven 5 mvt 1, %d notes):\n", kRealisticNotes);
    const double poly_ms = time_dispatch("score_ev_ (poly, 5 fields/note)", poly_symbols);
    time_dispatch("score_ev_ (phase A, 4 fields/note)", mono_symbols);

    // Depth-1 symbol at the same call count: the gap is the 55-comparison walk, separated
    // from fixed per-call overhead.
    printf("\nDispatch-depth comparison at the same call count:\n");
    const double shallow_ms = time_dispatch("\"playing\" (chain depth 1)", shallow_symbols);

    printf("\n  score_ev_ is 55 string comparisons deep in the pulsar block;\n");
    printf("  \"playing\" is the first. Ratio: %.2fx\n", poly_ms / (shallow_ms > 0 ? shallow_ms : 1));

    printf("\nRead: a score uploads once per piece selection, not per block. Judge\n");
    printf("%.1f ms against how long a user waits after tapping a piece, then add\n", poly_ms);
    printf("the JNI and Kotlin halves before concluding anything about Android.\n");

    printf("\n  Score Marshalling Probe: informational, no pass/fail\n");
    return true;
}
