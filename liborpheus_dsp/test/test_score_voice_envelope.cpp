// Score-voice envelope: orchestral ADSR mapping + sustain taper for pooled score voices.
// Most tests drive voice_params[0].score_driven directly and render through
// unit_process_plaits; the flag-lifecycle test drives the real mirror loop instead.
#include "test_harness.h"
#include "orpheus_unit_pulsar.h"
#include "test_pulsar_helpers.h"
#include "stmlib/utils/random.h"

// Engine 19's pluck excitation and the generative band's noise both draw from
// process-global RNGs (stmlib::Random, pulsar_seed's wall-clock re-stir) -- pin
// both so renders are reproducible regardless of what ran before this suite.
static constexpr uint32_t kReproRng = 0xBEA70000u;
static constexpr int64_t kReproSeed = 0xBEA7;

// Renders one voice through unit_process_plaits, gate held for gate_on_s then released,
// for a total of total_s. Mirrors what the pooled-score mirror loop drives per block.
static std::vector<float> render_score_voice(int engine_index, float decay, bool score_driven,
                                              float gate_on_s, float total_s, int sr = 48000) {
    stmlib::Random::Seed(kReproRng);
    OrpheusEngine* engine = orpheus_engine_create((float)sr);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(engine_index);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].decay.store(decay);
    engine->voice_params[0].score_driven.store(score_driven);

    GraphUnit unit;
    setup_voice_unit(&unit, 0);

    int total = (int)(total_s * sr);
    int gate_frames = (int)(gate_on_s * sr);
    std::vector<float> buf;
    buf.reserve(total);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        if (off == 0) engine->voice_params[0].gate.store(1);
        if (off >= gate_frames) engine->voice_params[0].gate.store(0);
        unit_process_plaits(&unit, engine, chunk, (float)sr);
        for (int i = 0; i < chunk; i++) buf.push_back(unit.output_buffers[OPORT_OUT][i]);
    }
    orpheus_engine_destroy(engine);
    return buf;
}

// Engine 19 (STR) is normally already_enveloped (raw, no ADSR). A score-driven voice
// must get the envelope anyway: quiet at onset, then risen by the time it's mid-note.
static bool test_score_engine19_gets_envelope() {
    printf("\n=== Test: score-driven engine 19 gets an envelope ===\n");
    // decay=0.5 -> eased=0.25 -> attack_s=0.005+0.25*0.145=0.04125s, ramp-to-sustain ~0.18s.
    auto buf = render_score_voice(/*engine=*/19, /*decay=*/0.5f, /*score_driven=*/true,
                                   /*gate_on_s=*/0.6f, /*total_s=*/0.6f);
    float early_peak = compute_peak(buf.data(), 128);  // first ~2.7ms
    float mid_peak = compute_peak(buf.data() + (int)(0.30f * 48000), (int)(0.10f * 48000));

    bool ramps_up = early_peak < mid_peak * 0.5f;
    bool nonzero_mid = mid_peak > 0.02f;
    printf("  engine19 score: early_peak=%.5f mid_peak=%.5f ramps_up=%s nonzero_mid=%s\n",
           early_peak, mid_peak, ramps_up ? "yes" : "NO", nonzero_mid ? "yes" : "NO");

    bool pass = ramps_up && nonzero_mid;
    printf("Score engine19 envelope test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// Wind part (engine 8), decay=0.5: old compute_adsr_from_speed gives a 0.754s attack,
// longer than an eighth note. The score mapping must reach half peak within 150ms.
static bool test_score_wind_fast_attack() {
    printf("\n=== Test: score-driven wind voice reaches half peak within 150ms ===\n");
    auto buf = render_score_voice(/*engine=*/8, /*decay=*/0.5f, /*score_driven=*/true,
                                   /*gate_on_s=*/1.0f, /*total_s=*/1.0f);
    float peak = compute_peak(buf.data(), (int)buf.size());
    float half = peak * 0.5f;
    int half_idx = -1;
    for (size_t i = 0; i < buf.size(); i++) {
        if (std::fabs(buf[i]) >= half) { half_idx = (int)i; break; }
    }
    float half_ms = (half_idx >= 0) ? (half_idx / 48000.0f * 1000.0f) : -1.0f;
    printf("  engine8 score decay=0.5: peak=%.4f half_peak_reached_at=%.1fms\n", peak, half_ms);

    bool pass = half_idx >= 0 && half_ms < 150.0f;
    printf("Score wind fast-attack test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// A long held note (>=3s of sustain) must taper: late-sustain RMS below early-sustain
// RMS, drifting toward the 0.6 floor (tau=3.5s) but not collapsing past it.
static bool test_score_sustain_taper() {
    printf("\n=== Test: score sustain tapers toward the floor on long notes ===\n");
    // decay=0.5 -> sustain=0.85, ramp-to-sustain ~0.18s (well before the 0.3s early window,
    // so it measures stable sustain). Floor=sustain*0.6, tau=3.5s: at t=3s into sustain,
    // remaining fraction = exp(-3/3.5) = 0.42, so late/early ~= 0.79.
    auto buf = render_score_voice(/*engine=*/8, /*decay=*/0.5f, /*score_driven=*/true,
                                   /*gate_on_s=*/3.3f, /*total_s=*/3.3f);
    int sr = 48000;
    float early_rms = compute_rms(buf.data() + (int)(0.3f * sr), (int)(0.2f * sr));
    float late_rms  = compute_rms(buf.data() + (int)(3.0f * sr), (int)(0.2f * sr));
    float ratio = (early_rms > 0.0001f) ? late_rms / early_rms : 0.0f;
    printf("  early_rms(0.3-0.5s)=%.5f late_rms(3.0-3.2s)=%.5f ratio=%.3f\n",
           early_rms, late_rms, ratio);

    bool tapers = ratio < 0.98f;             // measurably below early-sustain
    bool not_over_tapered = ratio > 0.55f;   // stays near the 0.6 floor, doesn't collapse
    bool pass = tapers && not_over_tapered;
    printf("Score sustain taper test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// After the written note ends (end_tick expiry -> gate drops), the voice must release
// fast: below -60dB (relative to its pre-release peak) within release_s plus margin.
static bool test_score_release_below_60db() {
    printf("\n=== Test: score voice release reaches -60dB within release_s + margin ===\n");
    // decay=0.5 -> release_s=0.15+0.25*0.45=0.2625s. Measure a tail window ~1.3x release_s
    // after gate-off (0.4s gate-on, tail sampled at 0.70-0.75s).
    auto buf = render_score_voice(/*engine=*/8, /*decay=*/0.5f, /*score_driven=*/true,
                                   /*gate_on_s=*/0.4f, /*total_s=*/0.75f);
    int sr = 48000;
    float peak_note = compute_peak(buf.data(), (int)(0.4f * sr));
    float peak_tail = compute_peak(buf.data() + (int)(0.7f * sr), (int)(0.05f * sr));
    float ratio = (peak_note > 0.0001f) ? peak_tail / peak_note : 0.0f;
    printf("  peak_note=%.4f peak_tail(0.70-0.75s)=%.6f ratio=%.5f (want < 0.001, i.e. below -60dB)\n",
           peak_note, peak_tail, ratio);

    bool pass = ratio < 0.001f;  // -60dB per the brief; measured 0.00029 leaves headroom
    printf("Score release test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// Regression: voices the mirror loop never touches (score_driven stays false, the
// default) must render exactly as before -- no attack ramp on engine 19, and engine 8
// keeps the old (slow) compute_adsr_from_speed mapping.
static bool test_non_score_regression() {
    printf("\n=== Test: non-score voices are unchanged ===\n");
    bool all_pass = true;

    // Golden values pinned from this exact (seeded, reproducible) render -- a tight
    // epsilon makes "renders exactly as before" a durable assertion, not a structural proxy.
    {
        auto buf = render_score_voice(/*engine=*/19, /*decay=*/0.5f, /*score_driven=*/false,
                                       /*gate_on_s=*/0.6f, /*total_s=*/0.6f);
        float early_peak = compute_peak(buf.data(), 128);
        float mid_peak = compute_peak(buf.data() + (int)(0.30f * 48000), (int)(0.10f * 48000));
        const float kGoldenEarlyPeak = 0.115262017f;  // already_enveloped: raw, no ramp
        const float kGoldenMidPeak = 0.029819062f;
        bool matches = std::fabs(early_peak - kGoldenEarlyPeak) < 1e-5f &&
                       std::fabs(mid_peak - kGoldenMidPeak) < 1e-5f;
        printf("  engine19 non-score: early_peak=%.9f (golden %.9f) mid_peak=%.9f (golden %.9f) %s\n",
               early_peak, kGoldenEarlyPeak, mid_peak, kGoldenMidPeak,
               matches ? "MATCH" : "DRIFT (regression!)");
        all_pass &= matches;
    }
    {
        auto buf = render_score_voice(/*engine=*/8, /*decay=*/0.5f, /*score_driven=*/false,
                                       /*gate_on_s=*/1.0f, /*total_s=*/1.0f);
        float peak = compute_peak(buf.data(), (int)buf.size());
        float half = peak * 0.5f;
        int half_idx = -1;
        for (size_t i = 0; i < buf.size(); i++) {
            if (std::fabs(buf[i]) >= half) { half_idx = (int)i; break; }
        }
        const int kGoldenHalfIdx = 18104;  // old compute_adsr_from_speed attack, exact sample
        bool matches = half_idx == kGoldenHalfIdx;
        printf("  engine8 non-score decay=0.5: half_idx=%d (golden %d) %s\n",
               half_idx, kGoldenHalfIdx, matches ? "MATCH" : "DRIFT (regression!)");
        all_pass &= matches;
    }

    printf("Non-score regression test: %s\n", all_pass ? "PASS" : "FAIL");
    return all_pass;
}

// True while some bank voice is gated at `pitch` (matches the pattern in
// test_pulsar_score_sched.cpp's bank_sounds).
static bool score_bank_sounds(OrpheusEngine* engine, int pitch) {
    for (int v = 0; v < kNumMainVoices; v++) {
        if (engine->voice_params[v].gate.load(std::memory_order_relaxed) != 0 &&
            static_cast<int>(engine->voice_params[v].tune.load(std::memory_order_relaxed)) == pitch)
            return true;
    }
    return false;
}

// Drives the REAL mirror loop (unit_process_pulsar), not unit_process_plaits directly:
// fires a score note, waits for its slot to expire and free, then confirms
// score_driven cleared and the slot renders non-score (raw, no ramp) again.
static bool test_score_flag_clears_when_slot_leaves_score_duty() {
    printf("\n=== Test: score_driven clears when a slot returns to the pool ===\n");
    stmlib::Random::Seed(kReproRng);
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_seed.store(kReproSeed, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    // PulsarState allocates lazily on first process -- warm up before touching it.
    for (int i = 0; i < 20; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    const int pitch = 64;
    engine->pulsar_score_part[t].engine_index.store(19, std::memory_order_relaxed);  // STR
    engine->pulsar_score_events[t][0] = {0, /*duration=*/24, (uint8_t)pitch, 100, 0};
    engine->pulsar_score_event_count[t] = 1;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    int slot = -1;
    for (int i = 0; i < 2000 && slot < 0; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (score_bank_sounds(engine, pitch)) {
            for (int v = 0; v < kNumMainVoices; v++) {
                if (engine->voice_params[v].gate.load(std::memory_order_relaxed) != 0 &&
                    (int)engine->voice_params[v].tune.load(std::memory_order_relaxed) == pitch) {
                    slot = v;
                    break;
                }
            }
        }
    }
    bool sounded = slot >= 0;
    bool was_score_driven = sounded &&
        engine->voice_params[slot].score_driven.load(std::memory_order_relaxed);
    printf("  slot=%d sounded=%s was_score_driven=%s\n", slot, sounded ? "yes" : "NO",
           was_score_driven ? "yes" : "NO");

    // The short note (24 ticks at 120bpm, PPQ=96) expires well within this budget --
    // keep processing until its pool slot frees and the flag clears.
    bool cleared = false;
    for (int i = 0; i < 3000 && sounded && !cleared; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (score_voice_is_free(ps->score_pool.voices[slot]) &&
            !engine->voice_params[slot].score_driven.load(std::memory_order_relaxed)) {
            cleared = true;
        }
    }
    printf("  cleared_after_expiry=%s\n", cleared ? "yes" : "NO");

    // Rendered-behavior check: reuse the freed slot for a fresh non-score engine-19
    // voice and confirm already_enveloped's raw/no-ramp behavior is back.
    bool no_ramp = false;
    if (sounded && cleared) {
        // Re-pin: the band ran for thousands of blocks above and its own noise
        // draws share stmlib::Random with String's excitation.
        stmlib::Random::Seed(kReproRng);
        engine->voice_params[slot].active.store(1, std::memory_order_relaxed);
        engine->voice_params[slot].ever_triggered.store(1, std::memory_order_relaxed);
        engine->voice_params[slot].engine_index.store(19, std::memory_order_relaxed);
        engine->voice_params[slot].tune.store(60.0f, std::memory_order_relaxed);
        engine->voice_params[slot].harmonics.store(0.5f, std::memory_order_relaxed);
        engine->voice_params[slot].timbre.store(0.5f, std::memory_order_relaxed);
        engine->voice_params[slot].morph.store(0.5f, std::memory_order_relaxed);
        engine->voice_params[slot].decay.store(0.5f, std::memory_order_relaxed);
        // engine_changed is still set from the mirror loop's engine_index write (it
        // force-zeroes plaits_gate for one block on a real swap). unit_process_plaits
        // would consume it normally; clear it since this test never called that above.
        engine->voice_params[slot].engine_changed.store(0, std::memory_order_relaxed);
        engine->voice_params[slot].gate.store(1, std::memory_order_relaxed);

        GraphUnit voice_unit;
        setup_voice_unit(&voice_unit, slot);
        int total = (int)(0.6f * 48000);
        std::vector<float> buf;
        buf.reserve(total);
        for (int off = 0; off < total; off += 128) {
            int chunk = std::min(128, total - off);
            unit_process_plaits(&voice_unit, engine, chunk, 48000.0f);
            for (int i = 0; i < chunk; i++) buf.push_back(voice_unit.output_buffers[OPORT_OUT][i]);
        }
        float early_peak = compute_peak(buf.data(), 128);
        float mid_peak = compute_peak(buf.data() + (int)(0.30f * 48000), (int)(0.10f * 48000));
        no_ramp = early_peak > mid_peak * 0.3f;
        printf("  after reuse: early_peak=%.5f mid_peak=%.5f no_ramp=%s\n",
               early_peak, mid_peak, no_ramp ? "yes (restored)" : "NO (still score-enveloped!)");
    }

    bool pass = sounded && was_score_driven && cleared && no_ramp;
    printf("Score flag lifecycle test: %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

bool run_score_voice_envelope_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_score_engine19_gets_envelope());
    tally(test_score_wind_fast_attack());
    tally(test_score_sustain_taper());
    tally(test_score_release_below_60db());
    tally(test_non_score_regression());
    tally(test_score_flag_clears_when_slot_leaves_score_duty());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
