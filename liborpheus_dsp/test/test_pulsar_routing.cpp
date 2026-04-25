// Pulsar port routing coverage test
// Verifies that every Pulsar symbol routed through orpheus_engine_set_port()
// actually reaches the corresponding C++ engine atomic.
// Catches gaps where a new Kotlin PulsarSymbol is added but the
// C++ routing in orpheus_engine_routing.cpp is not wired up.
#include "test_harness.h"

static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";

// Helper: set a port and check that an atomic<float> changed
static bool check_float_port(OrpheusEngine* engine, const char* symbol,
                              float test_val, std::atomic<float>& target,
                              bool& pass) {
    float before = target.load(std::memory_order_relaxed);
    // Use a value different from default
    orpheus_engine_set_port(engine, PULSAR_URI, symbol, test_val);
    float after = target.load(std::memory_order_relaxed);
    if (after != test_val) {
        printf("    FAIL: '%s' = %.2f (expected %.2f, was %.2f)\n", symbol, after, test_val, before);
        pass = false;
        return false;
    }
    return true;
}

// Helper: set a port and check that an atomic<int> changed
static bool check_int_port(OrpheusEngine* engine, const char* symbol,
                            int test_val, std::atomic<int>& target,
                            bool& pass) {
    orpheus_engine_set_port(engine, PULSAR_URI, symbol, static_cast<float>(test_val));
    int after = target.load(std::memory_order_relaxed);
    if (after != test_val) {
        printf("    FAIL: '%s' = %d (expected %d)\n", symbol, after, test_val);
        pass = false;
        return false;
    }
    return true;
}

static bool test_pulsar_global_port_routing() {
    printf("\n  Test: Pulsar global ports reach engine atomics\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000);

    check_float_port(engine, "energy", 0.77f, engine->pulsar_energy, pass);
    check_float_port(engine, "complexity", 0.66f, engine->pulsar_complexity, pass);
    check_float_port(engine, "space", 0.55f, engine->pulsar_space, pass);
    check_float_port(engine, "mood", 0.44f, engine->pulsar_mood, pass);
    check_float_port(engine, "bpm", 140.0f, engine->pulsar_bpm_override, pass);
    check_int_port(engine, "root_note", 7, engine->pulsar_root_note, pass);
    check_int_port(engine, "scale", 3, engine->pulsar_scale_index, pass);
    check_float_port(engine, "mix", 0.8f, engine->pulsar_mix, pass);
    check_float_port(engine, "perc_mix", 0.6f, engine->pulsar_perc_mix, pass);
    check_int_port(engine, "envelope_mode", 2, engine->pulsar_envelope_mode, pass);
    check_float_port(engine, "lick_mutation", 0.33f, engine->pulsar_lick_mutation, pass);
    check_int_port(engine, "step_count", 32, engine->pulsar_step_count, pass);
    check_int_port(engine, "playing", 1, engine->pulsar_playing, pass);

    int routed = 13;
    printf("    %s: %d global ports verified\n", pass ? "PASS" : "FAIL", routed);

    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_pulsar_per_track_port_routing() {
    printf("\n  Test: Pulsar per-track ports reach engine atomics\n");
    bool pass = true;
    int routed = 0;

    OrpheusEngine* engine = orpheus_engine_create(48000);

    for (int t = 0; t < 8; t++) {
        char sym[64];

        snprintf(sym, sizeof(sym), "track_%d_engine_edm", t);
        check_int_port(engine, sym, 10 + t, engine->pulsar_track_engine_edm[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_engine_space", t);
        check_int_port(engine, sym, 20 + t, engine->pulsar_track_engine_space[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_volume", t);
        check_float_port(engine, sym, 0.5f + t * 0.01f, engine->pulsar_track_volume[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_pan", t);
        check_float_port(engine, sym, -0.1f * t, engine->pulsar_track_pan[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_harmonics", t);
        check_float_port(engine, sym, 0.3f + t * 0.01f, engine->pulsar_track_harmonics[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_timbre", t);
        check_float_port(engine, sym, 0.4f + t * 0.01f, engine->pulsar_track_timbre[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_morph", t);
        check_float_port(engine, sym, 0.2f + t * 0.01f, engine->pulsar_track_morph[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_envelope", t);
        check_int_port(engine, sym, t % 4, engine->pulsar_track_envelope[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_role", t);
        check_int_port(engine, sym, t < 3 ? 0 : 1, engine->pulsar_track_role[t], pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_bar_strategy", t);
        check_int_port(engine, sym, t % 5, engine->pulsar_track_bar_strategy[t], pass);
        routed++;

        // Spot-check one macro pair per track
        snprintf(sym, sizeof(sym), "track_%d_macro_energy_vol_min", t);
        check_float_port(engine, sym, 0.1f * t, engine->pulsar_track_macros[t].energy_vol_min, pass);
        routed++;

        snprintf(sym, sizeof(sym), "track_%d_macro_mood_timbre_max", t);
        check_float_port(engine, sym, 0.9f - 0.05f * t, engine->pulsar_track_macros[t].mood_timbre_max, pass);
        routed++;
    }

    printf("    %s: %d per-track ports verified (8 tracks × %d params)\n",
           pass ? "PASS" : "FAIL", routed, routed / 8);

    orpheus_engine_destroy(engine);
    return pass;
}

static bool test_pulsar_genre_port_routing() {
    printf("\n  Test: Pulsar genre profile ports reach engine atomics\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000);

    for (int i = 0; i < 8; i++) {
        char sym[32];
        snprintf(sym, sizeof(sym), "genre_density_%d", i);
        check_float_port(engine, sym, 0.1f * (i + 1), engine->pulsar_genre_density[i], pass);
    }
    check_float_port(engine, "genre_swing", 0.08f, engine->pulsar_genre_swing, pass);
    check_float_port(engine, "genre_ghost_prob", 0.25f, engine->pulsar_genre_ghost_prob, pass);
    check_int_port(engine, "genre_note_range_low", 40, engine->pulsar_genre_note_range_low, pass);
    check_int_port(engine, "genre_note_range_high", 80, engine->pulsar_genre_note_range_high, pass);
    check_float_port(engine, "genre_rhythm_density", 0.67f, engine->pulsar_genre_rhythm_density, pass);

    printf("    %s: 13 genre ports verified\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// Regression: the routing parser checks the more-specific
// "custom_progression_glide_" prefix BEFORE the broader "custom_progression_"
// prefix. If that ordering is ever flipped, atoi("glide_3") would silently
// route the glide value into pulsar_custom_progression[0]. Same shape applies
// to "section_progression_glide_" vs "section_progression_degree_".
static bool test_pulsar_progression_glide_routing() {
    printf("\n  Test: Pulsar progression glide ports route to glide arrays (not progression arrays)\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000);

    // Distinct sentinel values so a misroute is obvious from the failure message.
    for (int i = 0; i < 8; i++) {
        char sym[64];
        snprintf(sym, sizeof(sym), "custom_progression_glide_%d", i);
        check_float_port(engine, sym, 0.10f + 0.05f * i,
                         engine->pulsar_custom_progression_glide[i], pass);

        snprintf(sym, sizeof(sym), "custom_progression_%d", i);
        check_int_port(engine, sym, i + 1, engine->pulsar_custom_progression[i], pass);
    }
    // Confirm the glide route did not corrupt pulsar_custom_progression[0].
    if (engine->pulsar_custom_progression[0].load(std::memory_order_relaxed) != 1) {
        printf("    FAIL: custom_progression[0] corrupted by glide-routing collision (got %d)\n",
               engine->pulsar_custom_progression[0].load(std::memory_order_relaxed));
        pass = false;
    }

    for (int s = 0; s < 8; s++) {
        for (int i = 0; i < 8; i++) {
            char sym[64];
            int idx = s * 8 + i;
            snprintf(sym, sizeof(sym), "section_progression_glide_%d", idx);
            check_float_port(engine, sym, 0.20f + 0.001f * idx,
                             engine->pulsar_section_progression_glides[idx], pass);

            snprintf(sym, sizeof(sym), "section_progression_degree_%d", idx);
            check_int_port(engine, sym, (idx % 7),
                           engine->pulsar_section_progression_degrees[idx], pass);
        }
    }
    if (engine->pulsar_section_progression_degrees[0].load(std::memory_order_relaxed) != 0) {
        printf("    FAIL: section_progression_degrees[0] corrupted by glide-routing collision (got %d)\n",
               engine->pulsar_section_progression_degrees[0].load(std::memory_order_relaxed));
        pass = false;
    }

    printf("    %s: progression glide routing isolated from degree/index routing\n",
           pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

bool run_pulsar_routing_tests() {
    printf("\n═══ Pulsar Port Routing Coverage ═══\n");
    int passed = 0, failed = 0;

    auto run = [&](bool (*fn)()) {
        if (fn()) passed++; else failed++;
    };

    run(test_pulsar_global_port_routing);
    run(test_pulsar_per_track_port_routing);
    run(test_pulsar_genre_port_routing);
    run(test_pulsar_progression_glide_routing);

    printf("\n  Pulsar Routing: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
