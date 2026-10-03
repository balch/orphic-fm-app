// Horn (Leslie) DSP unit tests
#include "test_harness.h"
#include <vector>

// ── Test 1: Self-bypass — mix=0 passes input through unchanged ──────────────
static bool test_horn_self_bypass() {
    printf("\n=== Test: Horn self-bypass (passthrough) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->horn_mix.store(0.0f);

    GraphUnit u = {};
    u.type = UNIT_HORN;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    const int num_frames = 128;
    // Fill input with non-zero sine
    for (int i = 0; i < num_frames; i++) {
        float t = (float)i / 48000.0f;
        float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
        u.inputs[IPORT_INPUT_A].buffer[i] = val;
        u.inputs[IPORT_INPUT_B].buffer[i] = val;
    }

    unit_process_horn(&u, engine, num_frames, 48000.0f);

    // Bypass should copy input to output (passthrough, not silence)
    // because horn is wired inline in the signal chain
    float max_diff = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float diff_l = std::fabs(u.output_buffers[OPORT_OUT][i] - u.inputs[IPORT_INPUT_A].buffer[i]);
        float diff_r = std::fabs(u.output_buffers[OPORT_OUT_RIGHT][i] - u.inputs[IPORT_INPUT_B].buffer[i]);
        if (diff_l > max_diff) max_diff = diff_l;
        if (diff_r > max_diff) max_diff = diff_r;
    }
    bool pass = max_diff < 0.0001f;

    printf("  mix=0 passthrough: max_diff=%.6f %s\n",
           max_diff, pass ? "OK (passthrough)" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 2: Non-zero output — effect modifies the signal ───────────────────
static bool test_horn_active_processing() {
    printf("\n=== Test: Horn active processing ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->horn_mix.store(0.5f);

    engine->horn_speed.store(0.5f);
    engine->horn_ratio.store(0.5f);
    engine->horn_depth.store(0.5f);
    engine->horn_brake.store(0);

    GraphUnit u = {};
    u.type = UNIT_HORN;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    const int test_frames = 4800;  // 100ms at 48kHz — enough to build delay history
    float out_rms = 0.0f;
    float in_rms  = 0.0f;
    float diff_rms = 0.0f;

    for (int offset = 0; offset < test_frames; offset += 128) {
        int chunk = std::min(128, test_frames - offset);
        for (int i = 0; i < chunk; i++) {
            float t = (float)(offset + i) / 48000.0f;
            float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
            u.inputs[IPORT_INPUT_A].buffer[i] = val;
            u.inputs[IPORT_INPUT_B].buffer[i] = val;
            in_rms += val * val;
        }

        unit_process_horn(&u, engine, chunk, 48000.0f);

        for (int i = 0; i < chunk; i++) {
            float ol = u.output_buffers[OPORT_OUT][i];
            float or_ = u.output_buffers[OPORT_OUT_RIGHT][i];
            out_rms += ol * ol + or_ * or_;
            // Compare output to input to verify the effect actually modifies the signal
            float dl = ol - u.inputs[IPORT_INPUT_A].buffer[i];
            float dr = or_ - u.inputs[IPORT_INPUT_B].buffer[i];
            diff_rms += dl * dl + dr * dr;
        }
    }

    in_rms   = std::sqrt(in_rms   / test_frames);
    out_rms  = std::sqrt(out_rms  / (test_frames * 2));
    diff_rms = std::sqrt(diff_rms / (test_frames * 2));

    // Effect must produce non-trivial output
    bool has_output = out_rms > 0.001f;
    // Output must differ from input (not just a passthrough)
    bool modifies_signal = diff_rms > 0.001f;
    bool pass = has_output && modifies_signal;

    printf("  mix=0.5 in_rms=%.4f out_rms=%.4f diff_rms=%.4f %s\n",
           in_rms, out_rms, diff_rms, pass ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 3: Phase export — viz rings written with non-zero phases ───────────
static bool test_horn_phase_export() {
    printf("\n=== Test: Horn phase export to viz rings ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->horn_mix.store(1.0f);

    engine->horn_speed.store(0.5f);
    engine->horn_ratio.store(0.5f);
    engine->horn_depth.store(0.5f);
    engine->horn_brake.store(0);

    GraphUnit u = {};
    u.type = UNIT_HORN;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    // Record write counts before processing
    uint32_t horn_wc_before   = engine->viz_rings[VIZ_HORN_PHASE].write_count.load(std::memory_order_relaxed);
    uint32_t woofer_wc_before = engine->viz_rings[VIZ_WOOFER_PHASE].write_count.load(std::memory_order_relaxed);

    // Process multiple blocks — rotor phases need time to ramp up from 0
    const int test_frames = 12000;  // 250ms — enough for speed inertia to build
    for (int offset = 0; offset < test_frames; offset += 128) {
        int chunk = std::min(128, test_frames - offset);
        for (int i = 0; i < chunk; i++) {
            float t = (float)(offset + i) / 48000.0f;
            float val = std::sin(t * 440.0f * 6.283185f) * 0.3f;
            u.inputs[IPORT_INPUT_A].buffer[i] = val;
            u.inputs[IPORT_INPUT_B].buffer[i] = val;
        }
        unit_process_horn(&u, engine, chunk, 48000.0f);
    }

    // Check write counts advanced (writes happened)
    uint32_t horn_wc_after   = engine->viz_rings[VIZ_HORN_PHASE].write_count.load(std::memory_order_relaxed);
    uint32_t woofer_wc_after = engine->viz_rings[VIZ_WOOFER_PHASE].write_count.load(std::memory_order_relaxed);

    bool writes_advanced = (horn_wc_after > horn_wc_before) && (woofer_wc_after > woofer_wc_before);

    // Read the most recently written phase values
    uint32_t horn_last_idx   = (horn_wc_after - 1) % VizRing::kVizBufSize;
    uint32_t woofer_last_idx = (woofer_wc_after - 1) % VizRing::kVizBufSize;
    float horn_phase_val   = engine->viz_rings[VIZ_HORN_PHASE].buf[horn_last_idx];
    float woofer_phase_val = engine->viz_rings[VIZ_WOOFER_PHASE].buf[woofer_last_idx];

    // After 250ms at ~0.75 Hz horn speed, horn phase should have advanced from 0
    bool horn_phase_nonzero   = horn_phase_val > 0.0f && horn_phase_val <= 1.0f;
    bool woofer_phase_nonzero = woofer_phase_val > 0.0f && woofer_phase_val <= 1.0f;

    bool pass = writes_advanced && horn_phase_nonzero && woofer_phase_nonzero;

    printf("  writes: horn %u->%u, woofer %u->%u  writes_ok=%s\n",
           horn_wc_before, horn_wc_after,
           woofer_wc_before, woofer_wc_after,
           writes_advanced ? "yes" : "no");
    printf("  horn_phase=%.4f woofer_phase=%.4f %s\n",
           horn_phase_val, woofer_phase_val, pass ? "OK" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Helpers for the bypass / alignment tests ────────────────────────────────

static OrpheusEngine* make_horn(GraphUnit& u, float mix, float depth, int brake) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->horn_mix.store(mix);
    engine->horn_speed.store(0.5f);
    engine->horn_ratio.store(0.5f);
    engine->horn_depth.store(depth);
    engine->horn_brake.store(brake);
    u = {};
    u.type = UNIT_HORN;
    u.enabled = true;
    unit_init(&u, 48000.0f);
    return engine;
}

// Runs `frames` of input from `sig(t)` in 128-frame blocks. Returns the largest
// |out - in| seen and the largest |out| seen, over both channels.
template <typename Sig>
static void run_horn(GraphUnit& u, OrpheusEngine* engine, int& t, int frames, Sig sig,
                     float* max_wet_diff = nullptr, float* max_out = nullptr) {
    if (max_wet_diff) *max_wet_diff = 0.0f;
    if (max_out) *max_out = 0.0f;
    for (int done = 0; done < frames; done += 128) {
        int n = std::min(128, frames - done);
        for (int i = 0; i < n; i++) {
            float v = sig(t + i);
            u.inputs[IPORT_INPUT_A].buffer[i] = v;
            u.inputs[IPORT_INPUT_B].buffer[i] = v;
        }
        unit_process_horn(&u, engine, n, 48000.0f);
        for (int i = 0; i < n; i++) {
            for (int ch = 0; ch < 2; ch++) {
                float out = u.output_buffers[ch == 0 ? OPORT_OUT : OPORT_OUT_RIGHT][i];
                float in  = u.inputs[ch == 0 ? IPORT_INPUT_A : IPORT_INPUT_B].buffer[i];
                if (max_wet_diff) *max_wet_diff = std::fmax(*max_wet_diff, std::fabs(out - in));
                if (max_out) *max_out = std::fmax(*max_out, std::fabs(out));
            }
        }
        t += n;
    }
}

static float sine440(int t) { return 0.4f * std::sin(t / 48000.0f * 440.0f * 6.283185f); }
static float silence(int) { return 0.0f; }

// ── Test 4: snapping mix to 0 fades out instead of cutting to dry ───────────
static bool test_horn_bypass_fades_out() {
    printf("\n=== Test: Horn mix snapped to 0 fades out (no hard cut) ===\n");
    GraphUnit u;
    OrpheusEngine* engine = make_horn(u, 1.0f, 0.5f, 0);
    int t = 0;
    run_horn(u, engine, t, 9600, sine440);  // 200 ms fully wet

    engine->horn_mix.store(0.0f);
    float first_block_wet, settled_wet;
    run_horn(u, engine, t, 128, sine440, &first_block_wet);
    run_horn(u, engine, t, 9600, sine440);
    run_horn(u, engine, t, 128, sine440, &settled_wet);

    bool fades = first_block_wet > 0.01f;   // still carries wet signal right after the snap
    bool settles = settled_wet < 1e-6f;     // then reaches exact passthrough
    printf("  first block after snap |out-in| max=%.4f %s, after 200 ms=%.2e %s\n",
           first_block_wet, fades ? "fading" : "FAIL (hard cut)",
           settled_wet, settles ? "passthrough" : "FAIL");
    orpheus_engine_destroy(engine);
    return fades && settles;
}

// ── Test 5: re-enabling after bypass must not replay stale audio ────────────
static bool test_horn_reenable_starts_clean() {
    printf("\n=== Test: Horn re-enable after bypass starts from silence ===\n");
    GraphUnit u;
    OrpheusEngine* engine = make_horn(u, 1.0f, 0.5f, 0);
    int t = 0;
    run_horn(u, engine, t, 9600, sine440);   // fill the delay lines with audio
    engine->horn_mix.store(0.0f);
    run_horn(u, engine, t, 9600, silence);   // bypassed, input silent
    engine->horn_mix.store(1.0f);
    float peak;
    run_horn(u, engine, t, 2400, silence, nullptr, &peak);

    bool pass = peak < 1e-6f;
    printf("  output peak with silent input after re-enable=%.2e %s\n",
           peak, pass ? "OK" : "FAIL (stale audio)");
    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 6: at depth 0 with the rotor parked, the wet L channel is a pure delay ──
// The bass band must be delayed as far as the treble, or the bands recombine as a comb.
static bool test_horn_depth0_bands_aligned() {
    printf("\n=== Test: Horn depth 0, rotor parked: wet L is a pure delay ===\n");
    GraphUnit u;
    OrpheusEngine* engine = make_horn(u, 1.0f, 0.0f, 1);  // brake: rotor stays at phase 0
    int t = 0;
    run_horn(u, engine, t, 9600, silence);  // let the smoothed mix reach 1

    std::vector<float> resp;
    for (int blk = 0; blk < 16; blk++) {
        for (int i = 0; i < 128; i++) {
            float v = (blk == 0 && i == 0) ? 1.0f : 0.0f;
            u.inputs[IPORT_INPUT_A].buffer[i] = v;
            u.inputs[IPORT_INPUT_B].buffer[i] = v;
        }
        unit_process_horn(&u, engine, 128, 48000.0f);
        for (int i = 0; i < 128; i++) resp.push_back(u.output_buffers[OPORT_OUT][i]);
    }
    double total = 0.0, peak = 0.0;
    int lag = 0;
    for (int i = 0; i < (int)resp.size(); i++) {
        double e = (double)resp[i] * resp[i];
        total += e;
        if (e > peak) { peak = e; lag = i; }
    }
    double frac = total > 0.0 ? peak / total : 0.0;
    bool pass = frac > 0.999;
    printf("  impulse response: peak at lag %d holds %.4f of the energy %s\n",
           lag, frac, pass ? "OK" : "FAIL (bands misaligned)");
    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test 7: AM never inverts the far side, at any depth ─────────────────────
static bool test_horn_am_never_inverts() {
    printf("\n=== Test: Horn AM gains stay in [0, 1] at every depth ===\n");
    float min_gain = 1.0f, max_depth = 0.0f;
    for (int k = 0; k <= 1000; k++) {
        HornAmDepths am = horn_am_depths(k / 1000.0f);
        min_gain = std::fmin(min_gain, std::fmin(1.0f - am.horn, 1.0f - am.woofer));
        max_depth = std::fmax(max_depth, std::fmax(am.horn, am.woofer));
    }
    bool pass = min_gain >= 0.0f && max_depth <= 1.0f;
    printf("  min far-side gain=%.3f max AM depth=%.3f %s\n", min_gain, max_depth, pass ? "OK" : "FAIL");
    return pass;
}

// ── Test 8: Doppler never hits the delay clamp; mid depth ≈ a real Leslie ───
static bool test_horn_doppler_range() {
    printf("\n=== Test: Horn Doppler fits the delay line; depth 0.5 ≈ real Leslie ===\n");
    bool pass = true;
    for (float sr : {44100.0f, 48000.0f, 96000.0f}) {
        float center = static_cast<float>(horn_center_samples(sr));
        float max_amp = horn_doppler_seconds(1.0f) * sr;
        bool fits = center - max_amp >= 1.0f && center + max_amp <= OrpheusHorn::kBufSize - 2;
        // Peak pitch ratio of a delay swinging ±A seconds at f Hz is 2*pi*f*A.
        float cents = 1200.0f * std::log2(1.0f + 6.283185f * 6.7f * horn_doppler_seconds(0.5f));
        bool real = cents > 25.0f && cents < 40.0f;
        printf("  sr=%.0f center=%.0f max swing=%.1f samples fits=%s, depth 0.5 at 6.7 Hz=±%.1f cents %s\n",
               sr, center, max_amp, fits ? "yes" : "NO", cents, real ? "OK" : "FAIL");
        pass &= fits && real;
    }
    return pass;
}

// ── Test 9: the heavier drum spins up more slowly than the horn ─────────────
static bool test_horn_drum_lags_horn() {
    printf("\n=== Test: Horn drum rotor lags the horn on spin-up ===\n");
    GraphUnit u;
    OrpheusEngine* engine = make_horn(u, 1.0f, 0.5f, 0);
    int t = 0;
    run_horn(u, engine, t, 48000, silence);  // 1 s from rest
    float horn_target = 0.2f * std::pow(40.0f, 0.5f);
    float horn_frac = engine->horn.horn_speed_hz / horn_target;
    float drum_frac = engine->horn.woofer_speed_hz / (horn_target / 9.0f);
    bool pass = horn_frac > 0.55f && drum_frac < 0.35f;
    printf("  after 1 s: horn at %.0f%% of target, drum at %.0f%% %s\n",
           horn_frac * 100.0f, drum_frac * 100.0f, pass ? "OK" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool run_horn_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_horn_self_bypass());
    tally(test_horn_active_processing());
    tally(test_horn_phase_export());
    tally(test_horn_bypass_fades_out());
    tally(test_horn_reenable_starts_clean());
    tally(test_horn_depth0_bands_aligned());
    tally(test_horn_am_never_inverts());
    tally(test_horn_doppler_range());
    tally(test_horn_drum_lags_horn());
    printf("\nHorn tests: %s\n", suite_fail == 0 ? "PASS" : "FAIL");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
