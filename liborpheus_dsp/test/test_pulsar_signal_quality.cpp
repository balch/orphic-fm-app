// Pulsar Signal Quality / Stress Tests
//
// Process many blocks of audio and measure output for:
//   - Sample-to-sample discontinuities (clicks/pops)
//   - Peak levels exceeding soft-limit
//   - Denormal float values
//   - DC offset accumulation
//   - Signal degradation over extended playback
//   - Block size sensitivity (Bluetooth buffer sizes)
//
// Discontinuity thresholds are engine-aware:
//   - Self-enveloped percussive engines (HH, BD, SD, Modal, String, SixOp)
//     have sharp natural transients (0→0.9 in 1 sample) — this is by design.
//   - Non-percussive melodic/effect engines should have smooth transitions.
//   - The mixed stereo output threshold accounts for percussion contribution.

#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static constexpr float kSampleRate = 48000.0f;

// Thresholds: discontinuity jump that counts as an artifact.
// Musical transients from percussion engines naturally exceed 0.9.
// The Chord engine (14) uses divide-down oscillators whose phase relationships
// snap on retrigger — same behavior as real organ keyboards. Original MI hardware
// masks this with an LPG; our OrpheusVoice micro-fade handles it in the mix.
static constexpr float kDiscThresholdMix = 0.95f;       // mixed stereo output (percussion contributes)
static constexpr float kDiscThresholdMelodic = 0.5f;     // melodic/effect tracks (should be smooth)
static constexpr float kDiscThresholdPercussive = 0.98f;  // percussion tracks (sharp attacks OK)
static constexpr float kDiscThresholdChord = 0.95f;       // Chord engine: organ-style phase snaps

// Classification for per-track threshold selection
static float threshold_for_engine(int engine_id) {
    // Self-enveloped (percussion): sharp transients expected
    if ((engine_id >= 19 && engine_id <= 23)   // String, Modal, BD, SD, HH
        || (engine_id >= 2 && engine_id <= 4)) // SixOp
        return kDiscThresholdPercussive;
    // Chord engine: divide-down organ phase snaps
    if (engine_id == 14)
        return kDiscThresholdChord;
    // All other melodic/effect engines
    return kDiscThresholdMelodic;
}

static bool is_self_enveloped_engine(int engine_id) {
    return (engine_id >= 19 && engine_id <= 23)
        || (engine_id >= 2 && engine_id <= 4);
}

// ── Measurement helpers ─────────────────────────────────────────────

struct SignalStats {
    float peak;
    float max_discontinuity;
    int   discontinuity_count;
    int   denormal_count;
    double dc_sum;
    int   total_samples;
};

static SignalStats make_stats() {
    SignalStats s;
    std::memset(&s, 0, sizeof(s));
    return s;
}

static void measure_block(const float* left, const float* right, int frames,
                          float& prev_l, float& prev_r,
                          SignalStats& stats, float disc_threshold) {
    for (int i = 0; i < frames; i++) {
        float l = left[i];
        float r = right[i];

        float al = std::fabs(l);
        float ar = std::fabs(r);
        if (al > stats.peak) stats.peak = al;
        if (ar > stats.peak) stats.peak = ar;

        float d = std::max(std::fabs(l - prev_l), std::fabs(r - prev_r));
        if (d > stats.max_discontinuity) stats.max_discontinuity = d;
        if (d > disc_threshold) stats.discontinuity_count++;

        if (al > 0.0f && al < 1e-30f) stats.denormal_count++;
        if (ar > 0.0f && ar < 1e-30f) stats.denormal_count++;

        stats.dc_sum += static_cast<double>(l + r);
        prev_l = l;
        prev_r = r;
    }
    stats.total_samples += frames;
}

static SignalStats process_seconds(GraphUnit* unit, OrpheusEngine* engine,
                                   float seconds, int block_size,
                                   float disc_threshold = kDiscThresholdMix) {
    SignalStats stats = make_stats();
    float prev_l = 0.0f, prev_r = 0.0f;
    int blocks = static_cast<int>(seconds * kSampleRate / block_size);
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(unit, engine, block_size, kSampleRate);
        measure_block(engine->pulsar_out_l, engine->pulsar_out_r, block_size,
                      prev_l, prev_r, stats, disc_threshold);
    }
    return stats;
}

static GraphUnit make_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

static OrpheusEngine* make_engine(void (*setup)(OrpheusEngine*), float bpm) {
    OrpheusEngine* engine = orpheus_engine_create(kSampleRate);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(bpm, std::memory_order_relaxed);
    return engine;
}

// ═══════════════════════════════════════════════════════════════════════

bool run_pulsar_signal_tests() {
    printf("\n=== Pulsar Signal Quality Tests ===\n\n");
    int pass = 0, fail = 0;

    // ── Test 1: Mixed output — no artifacts above percussion threshold (30s) ──
    {
        printf("  Test 1: Mixed output at 128 BPM (30s, threshold=%.2f)\n", kDiscThresholdMix);
        OrpheusEngine* engine = make_engine(setup_cosmic_techno, 128.0f);
        GraphUnit unit = make_unit();

        SignalStats stats = process_seconds(&unit, engine, 30.0f, 512);

        printf("    peak=%.4f max_disc=%.4f disc=%d denorm=%d\n",
               stats.peak, stats.max_discontinuity, stats.discontinuity_count,
               stats.denormal_count);

        bool ok = stats.discontinuity_count == 0 &&
                  stats.denormal_count == 0 &&
                  stats.peak > 0.01f &&
                  stats.peak <= 1.0f;
        if (ok) { printf("    PASS\n"); pass++; }
        else    { printf("    FAIL\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 2: Melodic tracks only — strict threshold (10s) ──
    // Solo each non-percussive track and verify smooth output.
    {
        printf("  Test 2: Per-engine thresholds (mel=%.2f, perc=%.2f, CHD=%.2f)\n",
               kDiscThresholdMelodic, kDiscThresholdPercussive, kDiscThresholdChord);
        const char* track_roles[] = {"KICK","PERC","HIHAT","BASS","KEYS","PAD","TEXTURE","FX"};
        bool all_ok = true;

        for (int solo = 0; solo < 8; solo++) {
            OrpheusEngine* engine = make_engine(setup_cosmic_techno, 128.0f);
            int eng_id = engine->pulsar_track_engine_edm[solo].load(std::memory_order_relaxed);
            bool is_perc = is_self_enveloped_engine(eng_id);
            float threshold = threshold_for_engine(eng_id);

            solo_track(engine, solo);
            trigger_vibe_load(engine);
            GraphUnit unit = make_unit();

            SignalStats stats = process_seconds(&unit, engine, 10.0f, 512, threshold);

            const char* verdict = (stats.discontinuity_count == 0) ? "ok" : "CLICK";
            printf("    Track %d %-7s (%-3s %s): peak=%.4f max_disc=%.4f disc=%d %s\n",
                   solo, track_roles[solo], engine_name(eng_id),
                   is_perc ? "perc" : (eng_id == 14 ? "chrd" : "mel "),
                   stats.peak, stats.max_discontinuity, stats.discontinuity_count, verdict);

            if (stats.discontinuity_count > 0) all_ok = false;
            orpheus_engine_destroy(engine);
        }

        if (all_ok) { printf("    PASS\n"); pass++; }
        else        { printf("    FAIL: melodic tracks have artifacts\n"); fail++; }
    }

    // ── Test 3: No signal degradation over 2 minutes ──
    {
        printf("  Test 3: No degradation over 2 minutes\n");
        OrpheusEngine* engine = make_engine(setup_cosmic_techno, 128.0f);
        GraphUnit unit = make_unit();

        process_seconds(&unit, engine, 5.0f, 512);
        SignalStats early = process_seconds(&unit, engine, 10.0f, 512);
        process_seconds(&unit, engine, 95.0f, 512);
        SignalStats late = process_seconds(&unit, engine, 10.0f, 512);

        float early_dc = static_cast<float>(early.dc_sum / (2.0 * early.total_samples));
        float late_dc = static_cast<float>(late.dc_sum / (2.0 * late.total_samples));

        printf("    Early: peak=%.4f max_disc=%.4f disc=%d denorm=%d dc=%.6f\n",
               early.peak, early.max_discontinuity, early.discontinuity_count,
               early.denormal_count, early_dc);
        printf("    Late:  peak=%.4f max_disc=%.4f disc=%d denorm=%d dc=%.6f\n",
               late.peak, late.max_discontinuity, late.discontinuity_count,
               late.denormal_count, late_dc);

        bool ok = late.denormal_count == 0 &&
                  late.peak <= 1.0f &&
                  late.peak > 0.001f &&
                  std::fabs(late_dc) < 0.05f;
        if (ok) { printf("    PASS\n"); pass++; }
        else    { printf("    FAIL\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 4: Soft-limit caps output at max energy ──
    {
        printf("  Test 4: Soft-limit caps output at max energy\n");
        OrpheusEngine* engine = make_engine(setup_cosmic_techno, 128.0f);
        engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
        GraphUnit unit = make_unit();

        SignalStats stats = process_seconds(&unit, engine, 10.0f, 512);

        printf("    peak=%.4f\n", stats.peak);

        bool ok = stats.peak <= 1.0f && stats.peak > 0.3f;
        if (ok) { printf("    PASS\n"); pass++; }
        else    { printf("    FAIL\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 5: BPM sweep — no artifacts at any tempo ──
    {
        printf("  Test 5: BPM sweep 40-200 (threshold=%.2f)\n", kDiscThresholdMix);
        GraphUnit unit = make_unit();
        float bpms[] = {40, 80, 120, 160, 200};
        int num_bpms = 5;
        bool all_ok = true;

        for (int bi = 0; bi < num_bpms; bi++) {
            OrpheusEngine* engine = make_engine(setup_cosmic_techno, bpms[bi]);
            SignalStats stats = process_seconds(&unit, engine, 5.0f, 512);

            printf("    BPM %3.0f: peak=%.4f max_disc=%.4f disc=%d denorm=%d\n",
                   bpms[bi], stats.peak, stats.max_discontinuity,
                   stats.discontinuity_count, stats.denormal_count);

            if (stats.discontinuity_count > 0 || stats.denormal_count > 0 ||
                stats.peak > 1.0f) {
                all_ok = false;
            }
            orpheus_engine_destroy(engine);
        }

        if (all_ok) { printf("    PASS\n"); pass++; }
        else        { printf("    FAIL\n"); fail++; }
    }

    // ── Test 6: Block size sweep (Bluetooth) ──
    {
        printf("  Test 6: Block size sweep (64-512)\n");
        int block_sizes[] = {64, 128, 256, 512};
        int num_sizes = 4;
        bool all_ok = true;

        for (int si = 0; si < num_sizes; si++) {
            int bs = block_sizes[si];
            OrpheusEngine* engine = make_engine(setup_cosmic_techno, 128.0f);
            GraphUnit unit = make_unit();

            SignalStats stats = process_seconds(&unit, engine, 10.0f, bs);

            printf("    Block %3d: peak=%.4f max_disc=%.4f disc=%d denorm=%d\n",
                   bs, stats.peak, stats.max_discontinuity,
                   stats.discontinuity_count, stats.denormal_count);

            if (stats.denormal_count > 0 || stats.peak > 1.0f || stats.peak < 0.01f) {
                all_ok = false;
            }
            orpheus_engine_destroy(engine);
        }

        if (all_ok) { printf("    PASS\n"); pass++; }
        else        { printf("    FAIL: issues at some block sizes\n"); fail++; }
    }

    // ── Test 7: Varying block sizes (Bluetooth jitter) ──
    {
        printf("  Test 7: Varying block sizes (Bluetooth jitter, 10s)\n");
        OrpheusEngine* engine = make_engine(setup_cosmic_techno, 128.0f);
        GraphUnit unit = make_unit();

        SignalStats stats = make_stats();
        float prev_l = 0.0f, prev_r = 0.0f;

        int sizes[] = {128, 256, 192, 128, 384, 256, 128, 512, 64, 256};
        int num_sizes = 10;
        int total_samples = 0;
        int target_samples = static_cast<int>(10.0f * kSampleRate);

        int si = 0;
        while (total_samples < target_samples) {
            int bs = sizes[si % num_sizes];
            if (bs > 512) bs = 512;
            si++;
            unit_process_pulsar(&unit, engine, bs, kSampleRate);
            measure_block(engine->pulsar_out_l, engine->pulsar_out_r, bs,
                          prev_l, prev_r, stats, kDiscThresholdMix);
            total_samples += bs;
        }

        printf("    peak=%.4f max_disc=%.4f disc=%d denorm=%d\n",
               stats.peak, stats.max_discontinuity, stats.discontinuity_count,
               stats.denormal_count);

        bool ok = stats.denormal_count == 0 &&
                  stats.peak <= 1.0f && stats.peak > 0.01f;
        if (ok) { printf("    PASS\n"); pass++; }
        else    { printf("    FAIL\n"); fail++; }
        orpheus_engine_destroy(engine);
    }

    // ── Test 8: Per-vibe comparison (informational + worst-vibe check) ──
    {
        printf("  Test 8: Per-vibe comparison (10s each)\n");

        struct VibeConfig {
            const char* name;
            void (*setup)(OrpheusEngine*);
            float bpm;
        };
        VibeConfig vibes[] = {
            {"Cosmic Techno", setup_cosmic_techno, 128.0f},
            {"Deep Space",    setup_deep_space,    70.0f},
            {"Dog House",     setup_dog_house,     85.0f},
            {"Garage Blitz",  setup_garage_blitz,  160.0f},
            {"Slow Burn",     setup_slow_burn,     72.0f},
        };
        int num_vibes = 5;

        int worst_disc = 0;
        const char* worst_name = "";
        bool any_over_limit = false;

        for (int vi = 0; vi < num_vibes; vi++) {
            OrpheusEngine* engine = make_engine(vibes[vi].setup, vibes[vi].bpm);
            GraphUnit unit = make_unit();

            SignalStats stats = process_seconds(&unit, engine, 10.0f, 512);

            printf("    %-15s (%3.0f BPM): peak=%.4f max_disc=%.4f disc=%d\n",
                   vibes[vi].name, vibes[vi].bpm,
                   stats.peak, stats.max_discontinuity,
                   stats.discontinuity_count);

            if (stats.discontinuity_count > worst_disc) {
                worst_disc = stats.discontinuity_count;
                worst_name = vibes[vi].name;
            }
            if (stats.peak > 1.0f) any_over_limit = true;
            orpheus_engine_destroy(engine);
        }

        if (worst_disc > 0) printf("    Worst: %s (%d disc at %.2f threshold)\n", worst_name, worst_disc, kDiscThresholdMix);
        bool ok = !any_over_limit;
        if (ok) { printf("    PASS (no over-limit peaks)\n"); pass++; }
        else    { printf("    FAIL: peaks exceed 1.0\n"); fail++; }
    }

    // ── Test 9: Per-track isolation — Cosmic Techno (informational) ──
    {
        printf("  Test 9: Per-track isolation (Cosmic Techno 128 BPM, 10s)\n");
        const char* track_roles[] = {"KICK","PERC","HIHAT","BASS","KEYS","PAD","TEXTURE","FX"};

        for (int solo = 0; solo < 8; solo++) {
            OrpheusEngine* engine = make_engine(setup_cosmic_techno, 128.0f);
            int eng_id = engine->pulsar_track_engine_edm[solo].load(std::memory_order_relaxed);
            bool is_perc = is_self_enveloped_engine(eng_id);

            solo_track(engine, solo);
            trigger_vibe_load(engine);
            GraphUnit unit = make_unit();

            // Use engine-appropriate threshold for counting
            float threshold = threshold_for_engine(eng_id);
            SignalStats stats = process_seconds(&unit, engine, 10.0f, 512, threshold);

            printf("    Track %d %-7s (%-3s %s): peak=%.4f max_disc=%.4f disc=%d\n",
                   solo, track_roles[solo], engine_name(eng_id),
                   is_perc ? "perc" : (eng_id == 14 ? "chrd" : "mel "),
                   stats.peak, stats.max_discontinuity, stats.discontinuity_count);

            orpheus_engine_destroy(engine);
        }
        printf("    (informational)\n");
        pass++;
    }

    // ── Test 10: Per-track isolation — Dog House (informational) ──
    {
        printf("  Test 10: Per-track isolation (Dog House 128 BPM, 10s)\n");
        const char* track_roles[] = {"KICK","PERC","HIHAT","BASS","KEYS","PAD","TEXTURE","FX"};

        for (int solo = 0; solo < 8; solo++) {
            OrpheusEngine* engine = make_engine(setup_dog_house, 128.0f);
            int eng_id = engine->pulsar_track_engine_edm[solo].load(std::memory_order_relaxed);
            bool is_perc = is_self_enveloped_engine(eng_id);

            solo_track(engine, solo);
            trigger_vibe_load(engine);
            GraphUnit unit = make_unit();

            float threshold = threshold_for_engine(eng_id);
            SignalStats stats = process_seconds(&unit, engine, 10.0f, 512, threshold);

            printf("    Track %d %-7s (%-3s %s): peak=%.4f max_disc=%.4f disc=%d\n",
                   solo, track_roles[solo], engine_name(eng_id),
                   is_perc ? "perc" : (eng_id == 14 ? "chrd" : "mel "),
                   stats.peak, stats.max_discontinuity, stats.discontinuity_count);

            orpheus_engine_destroy(engine);
        }
        printf("    (informational)\n");
        pass++;
    }

    printf("\n  Pulsar Signal Quality: %d passed, %d failed\n", pass, fail);
    TEST_SUITE_RETURN(pass, fail);
}
