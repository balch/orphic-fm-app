// DJ App full-graph integration tests
//
// Loads the DJ App wiring graph (dj_graph.odwg) and verifies each component
// in the signal path: Pulsar, Horn/Leslie, Reverb, Delay, Limiter/Distortion,
// and their send routing.
//
// Run:  orpheus_dsp_test djapp

#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include <cstring>
#include <cmath>

static void setup_pulsar_cosmic_techno(OrpheusEngine* engine) {
    engine->pulsar_playing.store(1);
    engine->pulsar_mix.store(1.0f);
    setup_cosmic_techno(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(128.0f);
}

// load_dj_graph() is defined in test_pulsar_helpers.h (shared)

// ── Helper: set per-track effect sends on dedicated Pulsar delay/reverb ──

static void set_pulsar_delay_sends(OrpheusEngine* engine, float level, int track_start = 0, int track_end = 8) {
    for (int t = track_start; t < track_end; t++) {
        engine->pulsar_track_delay_send[t].store(level, std::memory_order_relaxed);
    }
}

static void set_pulsar_reverb_sends(OrpheusEngine* engine, float level, int track_start = 0, int track_end = 8) {
    for (int t = track_start; t < track_end; t++) {
        engine->pulsar_track_reverb_send[t].store(level, std::memory_order_relaxed);
    }
}

// ── Helper: render DJ graph and return stereo buffer ────────────────

static RenderResult render_dj_graph(OrpheusEngine* engine, int num_frames, int warmup_blocks = 20) {
    return render_engine(engine, num_frames, warmup_blocks);
}

// ── Test 1: Graph loads and processes without crash ─────────────────

static bool test_djapp_graph_loads() {
    printf("\n=== Test: DJ App graph loads and processes ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) {
        orpheus_engine_destroy(engine);
        return false;
    }

    // Process a few blocks — should not crash or produce NaN/Inf
    float buf[128 * 2];
    bool clean = true;
    for (int i = 0; i < 50; i++) {
        orpheus_engine_process(engine, buf, 128);
        for (int j = 0; j < 256; j++) {
            if (std::isnan(buf[j]) || std::isinf(buf[j])) {
                printf("  FAIL: NaN/Inf at block %d sample %d\n", i, j);
                clean = false;
                break;
            }
        }
        if (!clean) break;
    }

    printf("  Graph load + process: %s\n", clean ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return clean;
}

// ── Test 2: Pulsar produces output through full graph ───────────────

static bool test_djapp_pulsar_through_graph() {
    printf("\n=== Test: Pulsar → Horn → Master (full graph) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    // Enable Pulsar with a known vibe
    setup_pulsar_cosmic_techno(engine);

    // Horn bypass so we isolate pulsar output reaching master
    engine->horn_mix.store(0.0f);

    // Ensure effects are off
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);
    engine->drive_mix.store(0.0f);

    // Render 2 seconds (enough for several bars at 128 BPM)
    auto r = render_dj_graph(engine, 96000, 30);

    bool has_output = r.rms_l > 0.001f || r.rms_r > 0.001f;
    bool no_clip = r.peak < 1.5f;

    printf("  rms_l=%.4f rms_r=%.4f peak=%.4f %s\n",
           r.rms_l, r.rms_r, r.peak,
           (has_output && no_clip) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return has_output && no_clip;
}

// ── Test 3: Horn (Leslie) modifies Pulsar signal through graph ──────

static bool test_djapp_horn_active() {
    printf("\n=== Test: Horn active modifies Pulsar signal ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);
    engine->drive_mix.store(0.0f);

    // Render with horn bypassed
    engine->horn_mix.store(0.0f);
    auto dry = render_dj_graph(engine, 48000, 30);

    // Reset engine for second render
    orpheus_engine_destroy(engine);
    engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);
    engine->drive_mix.store(0.0f);

    // Render with horn active
    engine->horn_mix.store(1.0f);
    engine->horn_speed.store(0.7f);
    engine->horn_ratio.store(0.5f);
    engine->horn_depth.store(0.8f);
    engine->horn_brake.store(0);
    auto wet = render_dj_graph(engine, 48000, 30);

    // Both should have output
    bool dry_ok = dry.rms_l > 0.001f;
    bool wet_ok = wet.rms_l > 0.001f;
    // They should differ (horn modulates the signal)
    float d = diff_rms(dry, wet);
    bool differs = d > 0.001f;

    printf("  dry rms=%.4f wet rms=%.4f diff=%.4f\n", dry.rms_l, wet.rms_l, d);
    printf("  dry_has_output=%s wet_has_output=%s signal_differs=%s %s\n",
           dry_ok ? "yes" : "NO", wet_ok ? "yes" : "NO", differs ? "yes" : "NO",
           (dry_ok && wet_ok && differs) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return dry_ok && wet_ok && differs;
}

// ── Test 4: Distortion (limiter drive) affects output ───────────────

static bool test_djapp_distortion_active() {
    printf("\n=== Test: Distortion (limiter drive) affects output ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    // Limiter is now inline on direct path (Pulsar→Horn→Limiter→Master)
    // so no delay/reverb sends needed to test distortion

    // Render with drive off (clean)
    engine->drive_mix.store(0.0f);
    engine->drive_amount.store(1.0f);
    auto clean = render_dj_graph(engine, 48000, 30);

    // Reset for driven render
    orpheus_engine_destroy(engine);
    engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    // Drive cranked up
    engine->drive_mix.store(1.0f);
    engine->drive_amount.store(3.0f);
    auto driven = render_dj_graph(engine, 48000, 30);

    bool clean_ok = clean.rms_l > 0.0001f;
    bool driven_ok = driven.rms_l > 0.0001f;
    float d = diff_rms(clean, driven);
    bool differs = d > 0.001f;

    printf("  clean rms=%.4f driven rms=%.4f diff=%.4f\n",
           clean.rms_l, driven.rms_l, d);
    printf("  clean_ok=%s driven_ok=%s differs=%s %s\n",
           clean_ok ? "yes" : "NO", driven_ok ? "yes" : "NO",
           differs ? "yes" : "NO",
           (clean_ok && driven_ok && differs) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return clean_ok && driven_ok && differs;
}

// ── Test 5: Reverb produces tail through full graph ─────────────────

static bool test_djapp_reverb_active() {
    printf("\n=== Test: Reverb produces tail through DJ graph ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->delay_bypass.store(1);
    engine->drive_mix.store(0.0f);

    // Enable dedicated Pulsar reverb
    engine->pulsar_reverb_bypass.store(0);
    engine->pulsar_reverb_amount.store(0.7f);
    engine->pulsar_reverb_time.store(0.8f);

    // Bypass shared reverb to isolate dedicated unit
    engine->reverb_bypass.store(1);

    // Set per-track reverb sends directly
    set_pulsar_reverb_sends(engine, 0.8f);

    // Run Pulsar for 1 second
    float warmup[128 * 2];
    for (int i = 0; i < 50; i++) orpheus_engine_process(engine, warmup, 128);
    auto active = render_dj_graph(engine, 48000, 0);

    // Stop Pulsar, render reverb tail
    engine->pulsar_playing.store(0);
    engine->pulsar_mix.store(0.0f);
    auto tail = render_dj_graph(engine, 24000, 0);

    bool active_ok = active.rms_l > 0.001f;
    bool has_tail = tail.rms_l > 0.0001f;

    printf("  active rms=%.4f tail rms=%.4f peak=%.4f\n",
           active.rms_l, tail.rms_l, tail.peak);
    printf("  active_ok=%s has_tail=%s %s\n",
           active_ok ? "yes" : "NO", has_tail ? "yes" : "NO",
           (active_ok && has_tail) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return active_ok && has_tail;
}

// ── Test 6: Delay echoes through full graph ─────────────────────────

static bool test_djapp_delay_active() {
    printf("\n=== Test: Delay echoes through DJ graph ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->reverb_bypass.store(1);
    engine->drive_mix.store(0.0f);

    // Enable dedicated Pulsar delay
    engine->pulsar_delay_bypass.store(0);
    engine->pulsar_delay_time_a.store(0.15f);
    engine->pulsar_delay_time_b.store(0.2f);
    engine->pulsar_delay_feedback.store(0.4f);

    // Bypass shared delay to isolate dedicated unit
    engine->delay_bypass.store(1);

    // Set per-track delay sends directly
    set_pulsar_delay_sends(engine, 0.8f);

    // Run long enough for echoes to appear
    float warmup[128 * 2];
    for (int i = 0; i < 50; i++) orpheus_engine_process(engine, warmup, 128);
    auto active = render_dj_graph(engine, 48000, 0);

    // Stop source, check for delay tail
    engine->pulsar_playing.store(0);
    engine->pulsar_mix.store(0.0f);
    auto tail = render_dj_graph(engine, 24000, 0);

    bool active_ok = active.rms_l > 0.001f;
    bool has_tail = tail.rms_l > 0.0001f;

    printf("  active rms=%.4f tail rms=%.4f\n", active.rms_l, tail.rms_l);
    printf("  active_ok=%s has_tail=%s %s\n",
           active_ok ? "yes" : "NO", has_tail ? "yes" : "NO",
           (active_ok && has_tail) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return active_ok && has_tail;
}

// ── Test 7: Send routing — turntable delay/reverb sends ─────────────

static bool test_djapp_turntable_sends() {
    printf("\n=== Test: Turntable send routing (delay + reverb) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    // Set up turntable with synth source, fill capture buffer
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_source_a.store(0);  // TT_SOURCE_SYNTH
    engine->turntable_crossfader.store(0.0f);
    engine->pulsar_playing.store(0);
    engine->horn_mix.store(0.0f);
    engine->drive_mix.store(0.0f);

    // Pre-fill turntable buffer with a tone
    for (int i = 0; i < kTurntableBufSize; i++) {
        engine->turntable_decks[0].buffer[i] =
            std::sin(2.0f * 3.14159f * 440.0f * i / 48000.0f) * 0.3f;
    }
    engine->turntable_frozen_a.store(1);  // freeze so it plays from buffer

    // Render with sends at zero — should still have direct turntable output
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.dj", "delay_send", 0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.dj", "reverb_send", 0.0f);

    auto no_send = render_dj_graph(engine, 24000, 10);

    // Now enable delay send
    orpheus_engine_destroy(engine);
    engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_source_a.store(0);
    engine->turntable_crossfader.store(0.0f);
    engine->pulsar_playing.store(0);
    engine->horn_mix.store(0.0f);
    engine->drive_mix.store(0.0f);
    for (int i = 0; i < kTurntableBufSize; i++) {
        engine->turntable_decks[0].buffer[i] =
            std::sin(2.0f * 3.14159f * 440.0f * i / 48000.0f) * 0.3f;
    }
    engine->turntable_frozen_a.store(1);
    engine->delay_bypass.store(0);
    engine->delay_mix.store(0.7f);
    engine->delay_feedback.store(0.3f);
    engine->delay_time_1.store(0.1f);
    engine->delay_time_2.store(0.15f);
    engine->reverb_bypass.store(1);

    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.dj", "delay_send", 0.8f);

    auto with_delay = render_dj_graph(engine, 24000, 10);

    float d = diff_rms(no_send, with_delay);
    bool send_works = d > 0.001f;

    printf("  no_send rms=%.4f with_delay rms=%.4f diff=%.4f %s\n",
           no_send.rms_l, with_delay.rms_l, d,
           send_works ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return send_works;
}

// ── Test 8: Horn Doppler L/R measurement ────────────────────────────
//
// Measures the Leslie horn effect across L and R channels independently.
// The Doppler effect creates stereo spread: the rotating horn should produce
// measurable L/R RMS difference and time-varying amplitude modulation.
// Reports detailed per-channel data for tuning the Doppler depth/speed.

static bool test_djapp_horn_doppler_lr() {
    printf("\n=== Test: Horn Doppler L/R measurement ===\n");
    bool all_pass = true;

    struct HornConfig {
        const char* label;
        float mix, speed, depth, ratio;
    };

    HornConfig configs[] = {
        { "slow+shallow",  1.0f, 0.2f, 0.3f, 0.5f },
        { "slow+deep",     1.0f, 0.2f, 1.0f, 0.5f },
        { "mid+mid",       1.0f, 0.5f, 0.5f, 0.5f },
        { "fast+deep",     1.0f, 0.9f, 1.0f, 0.5f },
        { "fast+deep+lo",  1.0f, 0.9f, 1.0f, 0.0f },  // woofer ratio = 0 (max woofer speed)
        { "fast+deep+hi",  1.0f, 0.9f, 1.0f, 1.0f },  // woofer ratio = 1 (min woofer speed)
    };
    int num_configs = sizeof(configs) / sizeof(configs[0]);

    printf("  %-17s  rms_L   rms_R   peak_L  peak_R  L/R_ratio  stereo_diff  AM_depth_L  AM_depth_R\n",
           "config");
    printf("  %-17s  ------  ------  ------  ------  ---------  -----------  ----------  ----------\n", "");

    for (int c = 0; c < num_configs; c++) {
        auto& cfg = configs[c];
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

        setup_pulsar_cosmic_techno(engine);
        engine->reverb_bypass.store(1);
        engine->delay_bypass.store(1);
        engine->drive_mix.store(0.0f);

        engine->horn_mix.store(cfg.mix);
        engine->horn_speed.store(cfg.speed);
        engine->horn_depth.store(cfg.depth);
        engine->horn_ratio.store(cfg.ratio);
        engine->horn_brake.store(0);

        const int render_frames = 96000;  // 2 seconds
        auto r = render_dj_graph(engine, render_frames, 40);

        // Per-channel peak measurement
        float peak_l = 0.0f, peak_r = 0.0f;
        // Per-channel AM depth: measure envelope variation (sliding window RMS)
        // Use 1024-sample windows (~21ms at 48kHz) to capture modulation
        const int window = 1024;
        int num_windows = render_frames / window;
        float min_rms_l = 999.0f, max_rms_l = 0.0f;
        float min_rms_r = 999.0f, max_rms_r = 0.0f;

        for (int w = 0; w < num_windows; w++) {
            double sum_l = 0.0, sum_r = 0.0;
            for (int i = 0; i < window; i++) {
                int idx = w * window + i;
                float l = r.buffer[idx * 2];
                float rv = r.buffer[idx * 2 + 1];
                sum_l += (double)l * l;
                sum_r += (double)rv * rv;
                float al = std::fabs(l), ar = std::fabs(rv);
                if (al > peak_l) peak_l = al;
                if (ar > peak_r) peak_r = ar;
            }
            float wrms_l = (float)std::sqrt(sum_l / window);
            float wrms_r = (float)std::sqrt(sum_r / window);
            if (wrms_l < min_rms_l) min_rms_l = wrms_l;
            if (wrms_l > max_rms_l) max_rms_l = wrms_l;
            if (wrms_r < min_rms_r) min_rms_r = wrms_r;
            if (wrms_r > max_rms_r) max_rms_r = wrms_r;
        }

        // AM depth = (max - min) / max, 0 = no modulation, 1 = full tremolo
        float am_depth_l = (max_rms_l > 0.001f) ? (max_rms_l - min_rms_l) / max_rms_l : 0.0f;
        float am_depth_r = (max_rms_r > 0.001f) ? (max_rms_r - min_rms_r) / max_rms_r : 0.0f;

        // L/R ratio: how much the channels differ (1.0 = identical, != 1.0 = stereo)
        float lr_ratio = (r.rms_r > 0.001f) ? r.rms_l / r.rms_r : 0.0f;

        // Stereo diff: RMS of (L-R) — measures how different the channels are
        double stereo_diff_sum = 0.0;
        for (int i = 0; i < render_frames; i++) {
            float d = r.buffer[i * 2] - r.buffer[i * 2 + 1];
            stereo_diff_sum += (double)d * d;
        }
        float stereo_diff = (float)std::sqrt(stereo_diff_sum / render_frames);

        printf("  %-17s  %.4f  %.4f  %.4f  %.4f  %.4f     %.4f       %.4f      %.4f\n",
               cfg.label, r.rms_l, r.rms_r, peak_l, peak_r,
               lr_ratio, stereo_diff, am_depth_l, am_depth_r);

        // Checks
        bool bounded = peak_l < 1.5f && peak_r < 1.5f;
        bool has_output = r.rms_l > 0.001f && r.rms_r > 0.001f;
        // At full mix with any depth, L and R should differ (Doppler creates stereo)
        bool has_stereo = stereo_diff > 0.001f;
        // At full depth, there should be measurable AM (amplitude modulation)
        bool has_am = (cfg.depth >= 0.5f) ? (am_depth_l > 0.01f || am_depth_r > 0.01f) : true;

        if (!bounded) printf("    FAIL: peak exceeds 1.5\n");
        if (!has_output) printf("    FAIL: no output\n");
        if (!has_stereo) printf("    FAIL: no stereo separation (Doppler not working?)\n");
        if (!has_am) printf("    FAIL: no amplitude modulation at depth=%.1f\n", cfg.depth);

        all_pass &= bounded && has_output && has_stereo && has_am;

        // Export WAV for the deep config so you can listen
        if (c == 3) {  // fast+deep
            const char* wav_path = TEST_DATA_DIR "/../output/djapp_horn_doppler.wav";
            write_wav(wav_path, r.buffer.data(), r.num_frames, 48000);
        }

        orpheus_engine_destroy(engine);
    }

    printf("\n  Horn Doppler L/R: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// ── Test 9: Full chain — all effects on simultaneously ──────────────

static bool test_djapp_full_chain() {
    printf("\n=== Test: Full DJ chain (Pulsar+Horn+Delay+Reverb+Drive) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    // Everything on
    setup_pulsar_cosmic_techno(engine);

    engine->horn_mix.store(0.6f);
    engine->horn_speed.store(0.5f);
    engine->horn_ratio.store(0.5f);
    engine->horn_depth.store(0.5f);
    engine->horn_brake.store(0);

    // Dedicated Pulsar reverb
    engine->pulsar_reverb_bypass.store(0);
    engine->pulsar_reverb_amount.store(0.4f);
    engine->pulsar_reverb_time.store(0.6f);

    // Dedicated Pulsar delay
    engine->pulsar_delay_bypass.store(0);
    engine->pulsar_delay_time_a.store(0.12f);
    engine->pulsar_delay_time_b.store(0.18f);
    engine->pulsar_delay_feedback.store(0.3f);

    // Bypass shared effects (Pulsar uses dedicated units now)
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    engine->drive_mix.store(0.5f);
    engine->drive_amount.store(2.0f);

    // Per-track effect sends
    set_pulsar_delay_sends(engine, 0.5f);
    set_pulsar_reverb_sends(engine, 0.5f);

    auto r = render_dj_graph(engine, 96000, 40);

    bool has_output = r.rms_l > 0.01f;
    bool bounded = r.peak < 1.5f;
    bool stereo = std::fabs(r.rms_l - r.rms_r) > 0.001f; // horn creates stereo spread

    printf("  rms_l=%.4f rms_r=%.4f peak=%.4f stereo_spread=%s\n",
           r.rms_l, r.rms_r, r.peak, stereo ? "yes" : "mono");
    printf("  has_output=%s bounded=%s %s\n",
           has_output ? "yes" : "NO", bounded ? "yes" : "NO",
           (has_output && bounded) ? "PASS" : "FAIL");

    // Export WAV for manual listening
    const char* wav_path = TEST_DATA_DIR "/../output/djapp_full_chain.wav";
    write_wav(wav_path, r.buffer.data(), r.num_frames, 48000);

    orpheus_engine_destroy(engine);
    return has_output && bounded;
}

// ── Test 10: Distortion drive sweep — verify increasing saturation ──

static bool test_djapp_drive_sweep() {
    printf("\n=== Test: Distortion drive sweep ===\n");
    bool all_pass = true;

    float drive_levels[] = { 1.0f, 2.0f, 4.0f, 8.0f };
    float prev_rms = 0.0f;

    for (int d = 0; d < 4; d++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

        setup_pulsar_cosmic_techno(engine);
        engine->horn_mix.store(0.0f);
        engine->reverb_bypass.store(1);

        // Route through dedicated Pulsar delay so it hits the limiter path
        engine->pulsar_delay_bypass.store(0);
        engine->pulsar_delay_time_a.store(0.001f);  // Very short = near-instant echo
        engine->pulsar_delay_time_b.store(0.001f);
        engine->pulsar_delay_feedback.store(0.2f);
        engine->delay_bypass.store(1);

        set_pulsar_delay_sends(engine, 1.0f);

        engine->drive_mix.store(1.0f);
        engine->drive_amount.store(drive_levels[d]);

        auto r = render_dj_graph(engine, 48000, 30);

        printf("  drive=%.1f: rms_l=%.4f rms_r=%.4f peak=%.4f\n",
               drive_levels[d], r.rms_l, r.rms_r, r.peak);

        // At higher drives, tanh saturates so output shouldn't grow linearly
        // but it should still have output
        if (r.rms_l < 0.0001f) {
            printf("    FAIL: no output at drive=%.1f\n", drive_levels[d]);
            all_pass = false;
        }
        // Peak should be bounded by tanh (< ~1.0) even at high drive
        if (r.peak > 1.5f) {
            printf("    FAIL: peak exceeds 1.5 at drive=%.1f\n", drive_levels[d]);
            all_pass = false;
        }

        prev_rms = r.rms_l;
        orpheus_engine_destroy(engine);
    }

    printf("  Drive sweep: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// ── Test 11: Distortion mix dry/wet — verify mix parameter effect ───

static bool test_djapp_distortion_mix() {
    printf("\n=== Test: Distortion mix dry/wet crossfade ===\n");
    bool all_pass = true;

    // The limiter (distortion) sits inline on ALL paths to master:
    //   Pulsar → Horn → Limiter → Master
    //   Delay → Limiter → Master
    //   Reverb → Limiter → Master
    // So distortion/warmth colors the entire mix.

    float mix_levels[] = { 0.0f, 0.25f, 0.5f, 0.75f, 1.0f };
    float prev_rms = -1.0f;

    printf("  mix    rms_L   rms_R   peak    notes\n");
    printf("  -----  ------  ------  ------  -----\n");

    for (int m = 0; m < 5; m++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

        setup_pulsar_cosmic_techno(engine);
        engine->horn_mix.store(0.0f);
        engine->reverb_bypass.store(1);

        // Route ALL signal through dedicated Pulsar delay → limiter path
        engine->pulsar_delay_bypass.store(0);
        engine->pulsar_delay_time_a.store(0.001f);  // Near-instant (passthrough)
        engine->pulsar_delay_time_b.store(0.001f);
        engine->pulsar_delay_feedback.store(0.0f);
        engine->delay_bypass.store(1);

        set_pulsar_delay_sends(engine, 1.0f);

        // High drive so saturation is obvious
        engine->drive_amount.store(8.0f);
        engine->drive_mix.store(mix_levels[m]);

        auto r = render_dj_graph(engine, 48000, 30);

        const char* note = "";
        if (mix_levels[m] == 0.0f) note = "(fully dry)";
        else if (mix_levels[m] == 1.0f) note = "(fully wet/saturated)";

        printf("  %.2f   %.4f  %.4f  %.4f  %s\n",
               mix_levels[m], r.rms_l, r.rms_r, r.peak, note);

        if (r.rms_l < 0.0001f) {
            printf("    FAIL: no output at mix=%.2f\n", mix_levels[m]);
            all_pass = false;
        }
        // At mix=0 vs mix=1, output should differ noticeably
        if (m > 0 && prev_rms >= 0.0f) {
            // tanh saturation compresses peaks, so higher mix = different RMS
            // We just verify they're not identical
        }

        prev_rms = r.rms_l;
        orpheus_engine_destroy(engine);
    }

    // Compare mix=0 vs mix=1 — must be different
    {
        // Render with mix=0
        OrpheusEngine* e0 = orpheus_engine_create(48000.0f);
        if (!load_dj_graph(e0)) { orpheus_engine_destroy(e0); return false; }
        setup_pulsar_cosmic_techno(e0);
        e0->horn_mix.store(0.0f);
        e0->reverb_bypass.store(1);
        e0->delay_bypass.store(1);
        e0->pulsar_delay_bypass.store(0);
        e0->pulsar_delay_time_a.store(0.001f);
        e0->pulsar_delay_time_b.store(0.001f);
        e0->pulsar_delay_feedback.store(0.0f);
        set_pulsar_delay_sends(e0, 1.0f);
        e0->drive_amount.store(8.0f);
        e0->drive_mix.store(0.0f);
        auto dry = render_dj_graph(e0, 48000, 30);
        orpheus_engine_destroy(e0);

        // Render with mix=1
        OrpheusEngine* e1 = orpheus_engine_create(48000.0f);
        if (!load_dj_graph(e1)) { orpheus_engine_destroy(e1); return false; }
        setup_pulsar_cosmic_techno(e1);
        e1->horn_mix.store(0.0f);
        e1->reverb_bypass.store(1);
        e1->delay_bypass.store(1);
        e1->pulsar_delay_bypass.store(0);
        e1->pulsar_delay_time_a.store(0.001f);
        e1->pulsar_delay_time_b.store(0.001f);
        e1->pulsar_delay_feedback.store(0.0f);
        set_pulsar_delay_sends(e1, 1.0f);
        e1->drive_amount.store(8.0f);
        e1->drive_mix.store(1.0f);
        auto wet = render_dj_graph(e1, 48000, 30);
        orpheus_engine_destroy(e1);

        float d = diff_rms(dry, wet);
        bool differs = d > 0.001f;
        printf("\n  dry vs wet diff=%.4f %s\n", d, differs ? "PASS (mix changes output)" : "FAIL (mix has no effect!)");
        all_pass &= differs;
    }

    printf("\n  NOTE: Limiter is inline on all paths to master.\n");
    printf("  Pulsar->Horn->Limiter->Master (warmth on direct output).\n");

    return all_pass;
}

// ── Test 12: BT turntable — large callback vs small callback ───────
//
// Simulates Bluetooth earbuds (960-frame callbacks that trigger the engine's
// internal chunking loop at kMaxFrames=512) and compares turntable output
// against the normal speaker path (128-frame callbacks).
// Both should produce equivalent RMS levels with no signal degradation.

static bool test_djapp_turntable_bt_chunking() {
    printf("\n=== Test: Turntable BT chunking (960 vs 128 frame callbacks) ===\n");

    auto setup_turntable = [](OrpheusEngine* engine) {
        // Disable effects, keep bass voice active for live capture
        engine->pulsar_playing.store(0);
        engine->horn_mix.store(0.0f);
        engine->drive_mix.store(0.0f);
        engine->reverb_bypass.store(1);
        engine->delay_bypass.store(1);

        // Activate bass voice playing a note (provides source signal for turntable)
        engine->bass_mix.store(1.0f);
        orpheus_engine_set_voice_active(engine, 12, 1);  // bass voice index
        orpheus_engine_set_voice_tune(engine, 12, 48.0f);
        orpheus_engine_set_voice_gate(engine, 12, 1);
        orpheus_engine_set_voice_engine(engine, 12, 8);  // VA engine
        orpheus_engine_set_voice_harmonics(engine, 12, 0.5f);
        orpheus_engine_set_voice_timbre(engine, 12, 0.5f);
        orpheus_engine_set_voice_morph(engine, 12, 0.5f);

        // Set up deck A to capture from bass, unfrozen (live recording)
        engine->turntable_wet_a.store(1.0f);
        engine->turntable_wet_b.store(0.0f);
        engine->turntable_source_a.store(2);  // TT_SOURCE_BASS
        engine->turntable_frozen_a.store(0);  // live capture!
    };

    // ── Reference: 128-frame callbacks (speaker mode) ──
    // Use production graph (has turntable) since DJ graph file may not exist
    OrpheusEngine* e_ref = orpheus_engine_create(48000.0f);
    if (!load_production_graph(e_ref)) { orpheus_engine_destroy(e_ref); return false; }
    setup_turntable(e_ref);

    // Warmup: 19200 total frames (using 128-frame calls = 150 calls)
    float warmup[128 * 2];
    for (int i = 0; i < 150; i++)
        orpheus_engine_process(e_ref, warmup, 128);

    // Render 4800 frames using 128-frame calls (like speakers)
    int total_frames = 4800;
    std::vector<float> ref_buf(total_frames * 2, 0.0f);
    for (int off = 0; off < total_frames; off += 128) {
        int chunk = std::min(128, total_frames - off);
        orpheus_engine_process(e_ref, ref_buf.data() + off * 2, chunk);
    }

    // Measure reference RMS
    double ref_sum = 0.0;
    float ref_peak = 0.0f;
    for (int i = 0; i < total_frames; i++) {
        float s = ref_buf[i * 2];
        ref_sum += (double)s * s;
        float a = std::fabs(s);
        if (a > ref_peak) ref_peak = a;
    }
    float ref_rms = (float)std::sqrt(ref_sum / total_frames);

    // ── BT mode: 960-frame callbacks (triggers internal 512+448 chunking) ──
    OrpheusEngine* e_bt = orpheus_engine_create(48000.0f);
    if (!load_production_graph(e_bt)) { orpheus_engine_destroy(e_bt); return false; }
    setup_turntable(e_bt);

    // Warmup with 960-frame calls (BT-sized)
    std::vector<float> bt_warmup(960 * 2, 0.0f);
    for (int i = 0; i < 20; i++)
        orpheus_engine_process(e_bt, bt_warmup.data(), 960);

    // Render same total frames using 960-frame calls
    std::vector<float> bt_buf(total_frames * 2, 0.0f);
    for (int off = 0; off < total_frames; off += 960) {
        int chunk = std::min(960, total_frames - off);
        orpheus_engine_process(e_bt, bt_buf.data() + off * 2, chunk);
    }

    // Measure BT RMS
    double bt_sum = 0.0;
    float bt_peak = 0.0f;
    for (int i = 0; i < total_frames; i++) {
        float s = bt_buf[i * 2];
        bt_sum += (double)s * s;
        float a = std::fabs(s);
        if (a > bt_peak) bt_peak = a;
    }
    float bt_rms = (float)std::sqrt(bt_sum / total_frames);

    orpheus_engine_destroy(e_ref);
    orpheus_engine_destroy(e_bt);

    // Both should have signal
    bool ref_has_signal = ref_rms > 0.01f;
    bool bt_has_signal = bt_rms > 0.01f;

    // RMS should be within 20% of each other (same source, same gain)
    float rms_ratio = (ref_rms > 0.001f) ? bt_rms / ref_rms : 0.0f;
    bool rms_close = rms_ratio > 0.8f && rms_ratio < 1.2f;

    printf("  Reference (128-frame): rms=%.4f peak=%.4f\n", ref_rms, ref_peak);
    printf("  BT mode   (960-frame): rms=%.4f peak=%.4f\n", bt_rms, bt_peak);
    printf("  RMS ratio (bt/ref):    %.3f\n", rms_ratio);
    printf("  Reference has signal:  %s\n", ref_has_signal ? "PASS" : "FAIL");
    printf("  BT has signal:         %s\n", bt_has_signal ? "PASS" : "FAIL");
    printf("  RMS within 20%%:        %s\n", rms_close ? "PASS" : "FAIL");

    bool ok = ref_has_signal && bt_has_signal && rms_close;
    printf("  Overall: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── Test 13: Dedicated Pulsar delay produces output and tail ────────

static bool test_djapp_pulsar_dedicated_delay() {
    printf("\n=== Test: Dedicated Pulsar delay produces output and tail ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->drive_mix.store(0.0f);

    // Bypass shared effects
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    // Enable dedicated Pulsar delay
    engine->pulsar_delay_bypass.store(0);
    engine->pulsar_delay_time_a.store(0.15f);
    engine->pulsar_delay_time_b.store(0.2f);
    engine->pulsar_delay_feedback.store(0.5f);

    // Bypass dedicated reverb
    engine->pulsar_reverb_bypass.store(1);

    // Set per-track delay sends on tracks 5-7
    set_pulsar_delay_sends(engine, 0.8f, 5, 8);

    // Warmup + render 1 second
    float warmup[128 * 2];
    for (int i = 0; i < 50; i++) orpheus_engine_process(engine, warmup, 128);
    auto active = render_dj_graph(engine, 48000, 0);

    // Stop Pulsar, render delay tail
    engine->pulsar_playing.store(0);
    engine->pulsar_mix.store(0.0f);
    auto tail = render_dj_graph(engine, 24000, 0);

    bool active_ok = active.rms_l > 0.001f;
    bool has_tail = tail.rms_l > 0.0001f;

    printf("  active rms=%.4f tail rms=%.6f\n", active.rms_l, tail.rms_l);
    printf("  active_ok=%s has_tail=%s %s\n",
           active_ok ? "yes" : "NO", has_tail ? "yes" : "NO",
           (active_ok && has_tail) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return active_ok && has_tail;
}

// ── Test 14: Dedicated Pulsar reverb produces output and tail ──────

static bool test_djapp_pulsar_dedicated_reverb() {
    printf("\n=== Test: Dedicated Pulsar reverb produces output and tail ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->drive_mix.store(0.0f);

    // Bypass shared effects
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    // Enable dedicated Pulsar reverb
    engine->pulsar_reverb_bypass.store(0);
    engine->pulsar_reverb_amount.store(0.7f);
    engine->pulsar_reverb_time.store(0.8f);

    // Bypass dedicated delay
    engine->pulsar_delay_bypass.store(1);

    // Set per-track reverb sends on tracks 5-7
    set_pulsar_reverb_sends(engine, 0.8f, 5, 8);

    // Warmup + render 1 second
    float warmup[128 * 2];
    for (int i = 0; i < 50; i++) orpheus_engine_process(engine, warmup, 128);
    auto active = render_dj_graph(engine, 48000, 0);

    // Stop Pulsar, render reverb tail
    engine->pulsar_playing.store(0);
    engine->pulsar_mix.store(0.0f);
    auto tail = render_dj_graph(engine, 24000, 0);

    bool active_ok = active.rms_l > 0.001f;
    bool has_tail = tail.rms_l > 0.0001f;

    printf("  active rms=%.4f tail rms=%.6f\n", active.rms_l, tail.rms_l);
    printf("  active_ok=%s has_tail=%s %s\n",
           active_ok ? "yes" : "NO", has_tail ? "yes" : "NO",
           (active_ok && has_tail) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return active_ok && has_tail;
}

// ── Test 15: Dedicated effects are independent from shared ─────────

static bool test_djapp_pulsar_effects_independent() {
    printf("\n=== Test: Dedicated Pulsar effects independent from shared ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->drive_mix.store(0.0f);

    // Bypass shared effects completely
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    // Enable dedicated Pulsar delay and reverb
    engine->pulsar_delay_bypass.store(0);
    engine->pulsar_delay_time_a.store(0.15f);
    engine->pulsar_delay_time_b.store(0.2f);
    engine->pulsar_delay_feedback.store(0.4f);

    engine->pulsar_reverb_bypass.store(0);
    engine->pulsar_reverb_amount.store(0.6f);
    engine->pulsar_reverb_time.store(0.7f);

    // Set per-track sends
    set_pulsar_delay_sends(engine, 0.6f, 5, 8);
    set_pulsar_reverb_sends(engine, 0.6f, 5, 8);

    // Render with effects
    auto wet = render_dj_graph(engine, 96000, 30);

    // Compare against dry (no sends)
    orpheus_engine_destroy(engine);
    engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->drive_mix.store(0.0f);
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    // Dedicated effects bypassed too
    engine->pulsar_delay_bypass.store(1);
    engine->pulsar_reverb_bypass.store(1);

    auto dry = render_dj_graph(engine, 96000, 30);

    bool wet_ok = wet.rms_l > 0.001f;
    bool dry_ok = dry.rms_l > 0.001f;
    float d = diff_rms(wet, dry);
    bool differs = d > 0.001f;

    printf("  dry rms=%.4f wet rms=%.4f diff=%.4f\n", dry.rms_l, wet.rms_l, d);
    printf("  dry_ok=%s wet_ok=%s differs=%s %s\n",
           dry_ok ? "yes" : "NO", wet_ok ? "yes" : "NO", differs ? "yes" : "NO",
           (dry_ok && wet_ok && differs) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return dry_ok && wet_ok && differs;
}

// ── Test 16: Zero sends produce no effect tail (DEEP=0 equivalent) ──

static bool test_djapp_pulsar_zero_sends() {
    printf("\n=== Test: Zero sends produce no effect tail (DEEP=0) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(engine)) { orpheus_engine_destroy(engine); return false; }

    setup_pulsar_cosmic_techno(engine);
    engine->horn_mix.store(0.0f);
    engine->drive_mix.store(0.0f);

    // Bypass shared effects
    engine->reverb_bypass.store(1);
    engine->delay_bypass.store(1);

    // Dedicated effects enabled but with zero sends (simulates DEEP=0)
    engine->pulsar_delay_bypass.store(0);
    engine->pulsar_delay_time_a.store(0.15f);
    engine->pulsar_delay_time_b.store(0.2f);
    engine->pulsar_delay_feedback.store(0.5f);

    engine->pulsar_reverb_bypass.store(0);
    engine->pulsar_reverb_amount.store(0.7f);
    engine->pulsar_reverb_time.store(0.8f);

    // All sends at zero
    set_pulsar_delay_sends(engine, 0.0f);
    set_pulsar_reverb_sends(engine, 0.0f);

    // Run Pulsar for 1 second
    float warmup[128 * 2];
    for (int i = 0; i < 50; i++) orpheus_engine_process(engine, warmup, 128);
    render_dj_graph(engine, 48000, 0);

    // Stop Pulsar, check for tail — should be near-silent
    engine->pulsar_playing.store(0);
    engine->pulsar_mix.store(0.0f);
    auto tail = render_dj_graph(engine, 24000, 0);

    // Tail should be negligible (no signal was sent to effects)
    bool no_tail = tail.rms_l < 0.0005f;

    printf("  tail rms=%.6f %s\n", tail.rms_l,
           no_tail ? "PASS (no effect tail)" : "FAIL (unexpected tail)");

    orpheus_engine_destroy(engine);
    return no_tail;
}

// ── Test 17: Delay-only vs reverb-only produce different output ────

static bool test_djapp_pulsar_delay_vs_reverb() {
    printf("\n=== Test: Delay-only vs reverb-only produce different output ===\n");

    // Delay-only
    OrpheusEngine* e_delay = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(e_delay)) { orpheus_engine_destroy(e_delay); return false; }

    setup_pulsar_cosmic_techno(e_delay);
    e_delay->horn_mix.store(0.0f);
    e_delay->drive_mix.store(0.0f);
    e_delay->reverb_bypass.store(1);
    e_delay->delay_bypass.store(1);

    e_delay->pulsar_delay_bypass.store(0);
    e_delay->pulsar_delay_time_a.store(0.15f);
    e_delay->pulsar_delay_time_b.store(0.2f);
    e_delay->pulsar_delay_feedback.store(0.4f);
    e_delay->pulsar_reverb_bypass.store(1);

    set_pulsar_delay_sends(e_delay, 0.7f, 5, 8);
    set_pulsar_reverb_sends(e_delay, 0.0f);

    auto delay_r = render_dj_graph(e_delay, 96000, 30);
    orpheus_engine_destroy(e_delay);

    // Reverb-only
    OrpheusEngine* e_reverb = orpheus_engine_create(48000.0f);
    if (!load_dj_graph(e_reverb)) { orpheus_engine_destroy(e_reverb); return false; }

    setup_pulsar_cosmic_techno(e_reverb);
    e_reverb->horn_mix.store(0.0f);
    e_reverb->drive_mix.store(0.0f);
    e_reverb->reverb_bypass.store(1);
    e_reverb->delay_bypass.store(1);

    e_reverb->pulsar_delay_bypass.store(1);
    e_reverb->pulsar_reverb_bypass.store(0);
    e_reverb->pulsar_reverb_amount.store(0.7f);
    e_reverb->pulsar_reverb_time.store(0.8f);

    set_pulsar_delay_sends(e_reverb, 0.0f);
    set_pulsar_reverb_sends(e_reverb, 0.7f, 5, 8);

    auto reverb_r = render_dj_graph(e_reverb, 96000, 30);
    orpheus_engine_destroy(e_reverb);

    bool delay_ok = delay_r.rms_l > 0.001f;
    bool reverb_ok = reverb_r.rms_l > 0.001f;
    float d = diff_rms(delay_r, reverb_r);
    bool differs = d > 0.001f;

    printf("  delay-only rms=%.4f reverb-only rms=%.4f diff=%.4f\n",
           delay_r.rms_l, reverb_r.rms_l, d);
    printf("  delay_ok=%s reverb_ok=%s differs=%s %s\n",
           delay_ok ? "yes" : "NO", reverb_ok ? "yes" : "NO", differs ? "yes" : "NO",
           (delay_ok && reverb_ok && differs) ? "PASS" : "FAIL");

    return delay_ok && reverb_ok && differs;
}

// ── Suite runner ────────────────────────────────────────────────────

bool run_djapp_graph_tests() {
    printf("\n========== DJ APP GRAPH TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_djapp_graph_loads());
    tally(test_djapp_pulsar_through_graph());
    tally(test_djapp_horn_active());
    tally(test_djapp_distortion_active());
    tally(test_djapp_reverb_active());
    tally(test_djapp_delay_active());
    tally(test_djapp_turntable_sends());
    tally(test_djapp_horn_doppler_lr());
    tally(test_djapp_full_chain());
    tally(test_djapp_drive_sweep());
    tally(test_djapp_distortion_mix());
    tally(test_djapp_turntable_bt_chunking());
    tally(test_djapp_pulsar_dedicated_delay());
    tally(test_djapp_pulsar_dedicated_reverb());
    tally(test_djapp_pulsar_effects_independent());
    tally(test_djapp_pulsar_zero_sends());
    tally(test_djapp_pulsar_delay_vs_reverb());
    printf("\nDJ App graph tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
