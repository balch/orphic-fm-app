// Fog horn voice (pulsar_fog.h) and its two hosts: the section fog bed and FogHornAnomaly.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_fog.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static constexpr float kFogSr = 48000.0f;
static constexpr float kRootHz = 69.3f;   // C#2

static std::vector<float> render_fog(fog::FogHornVoice& v, float seconds) {
    std::vector<float> out(static_cast<size_t>(seconds * kFogSr), 0.0f);
    std::vector<float> r(out.size(), 0.0f);
    for (size_t at = 0; at < out.size(); at += 256) {
        const int n = static_cast<int>(std::min<size_t>(256, out.size() - at));
        v.Process(out.data() + at, r.data() + at, n);
    }
    return out;
}

static float window_rms(const std::vector<float>& x, float from_s, float to_s) {
    const size_t a = static_cast<size_t>(from_s * kFogSr), b = std::min(x.size(), static_cast<size_t>(to_s * kFogSr));
    double sum = 0.0;
    for (size_t i = a; i < b; i++) sum += static_cast<double>(x[i]) * x[i];
    return b > a ? static_cast<float>(std::sqrt(sum / (b - a))) : 0.0f;
}

// Goertzel power at one frequency over a window.
static float tone_power(const std::vector<float>& x, float from_s, float to_s, float hz) {
    const size_t a = static_cast<size_t>(from_s * kFogSr), b = std::min(x.size(), static_cast<size_t>(to_s * kFogSr));
    const float w = 2.0f * 3.14159265f * hz / kFogSr, coeff = 2.0f * std::cos(w);
    float s1 = 0.0f, s2 = 0.0f;
    for (size_t i = a; i < b; i++) { const float s0 = x[i] + coeff * s1 - s2; s2 = s1; s1 = s0; }
    return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / static_cast<float>(std::max<size_t>(1, b - a));
}

static bool test_fog_voice_idle_until_triggered() {
    printf("\n=== Test: the fog voice is silent and inactive until triggered ===\n");
    fog::FogHornVoice v; v.Init(1u, kFogSr);
    const auto out = render_fog(v, 0.5f);
    float peak = 0.0f;
    for (float s : out) peak = std::max(peak, std::fabs(s));
    const bool ok = !v.active() && peak == 0.0f;
    printf("Fog idle: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fog_single_blast_swells_holds_and_decays() {
    printf("\n=== Test: a single blast swells in, holds on the root, then dies away ===\n");
    fog::FogHornVoice v; v.Init(1u, kFogSr);
    v.Trigger(kRootHz, fog::kSingle, 1.0f, 0.0f);
    const auto out = render_fog(v, 6.0f);
    float peak = 0.0f;
    for (float s : out) peak = std::max(peak, std::fabs(s));
    const float onset = window_rms(out, 0.0f, 0.05f), held = window_rms(out, 1.0f, 2.0f);
    const float tail = window_rms(out, 5.5f, 6.0f);
    const float at_root = tone_power(out, 1.0f, 2.0f, kRootHz);
    const float off_root = tone_power(out, 1.0f, 2.0f, kRootHz * 6.7f);
    printf("  onset=%.4f held=%.4f tail=%.5f peak=%.3f root/off=%.1f\n", onset, held, tail, peak,
           at_root / std::max(1e-12f, off_root));
    bool ok = held > 0.03f && onset < held * 0.35f && tail < held * 0.02f && peak < 1.0f
              && at_root > off_root * 20.0f && !v.active();
    printf("Fog single blast: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fog_dum_duum_is_three_blasts() {
    printf("\n=== Test: DUM_DUUUM sounds short, long, short with silence between ===\n");
    fog::FogHornVoice v; v.Init(1u, kFogSr);
    v.Trigger(kRootHz, fog::kDumDuum, 1.0f, 0.0f);
    const auto out = render_fog(v, 7.0f);
    const float dum = window_rms(out, 0.25f, 0.45f), gap1 = window_rms(out, 1.05f, 1.10f);
    const float duum = window_rms(out, 1.8f, 3.2f), gap2 = window_rms(out, 4.35f, 4.40f);
    const float dumm = window_rms(out, 4.7f, 4.95f);
    printf("  dum=%.4f gap=%.5f duum=%.4f gap=%.5f dumm=%.4f\n", dum, gap1, duum, gap2, dumm);
    bool ok = dum > 0.02f && duum > 0.02f && dumm > 0.02f && gap1 < dum * 0.15f && gap2 < duum * 0.15f;
    printf("Fog DUM_DUUUM: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fog_distance_darkens_and_quiets() {
    printf("\n=== Test: a distant horn is darker and quieter ===\n");
    float rms[2], bright[2];
    for (int k = 0; k < 2; k++) {
        fog::FogHornVoice v; v.Init(1u, kFogSr);
        v.Trigger(kRootHz, fog::kSingle, 1.0f, k == 0 ? 0.0f : 1.0f);
        const auto out = render_fog(v, 3.0f);
        rms[k] = window_rms(out, 1.0f, 2.0f);
        bright[k] = tone_power(out, 1.0f, 2.0f, kRootHz * 8.0f) / std::max(1e-12f, tone_power(out, 1.0f, 2.0f, kRootHz));
    }
    printf("  near rms=%.4f far rms=%.4f; harmonic-8 / root: near=%.5f far=%.5f\n", rms[0], rms[1], bright[0], bright[1]);
    bool ok = rms[1] < rms[0] * 0.8f && bright[1] < bright[0] * 0.7f;
    printf("Fog distance: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fog_retrigger_replaces_the_phrase() {
    printf("\n=== Test: re-triggering mid-phrase restarts it instead of stacking ===\n");
    fog::FogHornVoice a; a.Init(1u, kFogSr);
    a.Trigger(kRootHz, fog::kDumDuum, 1.0f, 0.0f);
    render_fog(a, 2.0f);
    a.Trigger(kRootHz, fog::kSingle, 1.0f, 0.0f);   // replaces the unfinished third blast
    const auto out = render_fog(a, 8.0f);
    const float late = window_rms(out, 6.0f, 8.0f);
    const bool ok = late < 1e-3f;
    printf("  late rms=%.6f\nFog retrigger: %s\n", late, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Hosts ───────────────────────────────────────────────────────────────────
static constexpr int kBlocksPerBar = 188;   // 120 BPM: a bar is 187.5 blocks of 512

static OrpheusEngine* make_muted_fog_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    solo_track(engine, -1);
    pin_pulsar_rngs(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    return engine;
}

static GraphUnit make_fog_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

struct FogTestBed { float level = 0.0f; int interval = 4; int pattern = 0; float pitch = 0.0f; float distance = 0.4f; };

static void push_fog_arrangement(OrpheusEngine* engine, const FogTestBed& f0, const FogTestBed& f1, int bars) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    constexpr int kStride = kSectionDataFields;
    std::vector<float> sd(kMaxSections * kStride, 0.0f);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kStride;
        sd[b + 5] = sd[b + 6] = sd[b + 7] = sd[b + 8] = -1.0f;
        sd[b + 18] = sd[b + 19] = sd[b + 20] = -1.0f;
    }
    const FogTestBed* f[2] = { &f0, &f1 };
    for (int s = 0; s < 2; s++) {
        const int b = s * kStride;
        sd[b + 0] = sd[b + 1] = static_cast<float>(bars);
        sd[b + 2] = 1.0f; sd[b + 3] = 0.8f; sd[b + 4] = 1.0f;
        sd[b + kSectionFogSlot + 0] = f[s]->level;
        sd[b + kSectionFogSlot + 1] = static_cast<float>(f[s]->interval);
        sd[b + kSectionFogSlot + 2] = static_cast<float>(f[s]->pattern);
        sd[b + kSectionFogSlot + 3] = f[s]->pitch;
        sd[b + kSectionFogSlot + 4] = f[s]->distance;
    }
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);
    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[0] = 1.0f; tr[1] = 1.0f; tr[2] = 0.0f;
    const int s1 = kMaxSectionTransitions * 3;
    tr[s1 + 0] = 0.0f; tr[s1 + 1] = 1.0f; tr[s1 + 2] = 0.0f;
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

static void push_fog_anomaly_bank(OrpheusEngine* engine, float probability, bool declared) {
    const float bank[OrpheusEngine::kFogDataFields] = {
        probability, 0.8f, 0.4f, static_cast<float>(fog::kSingle), 0.0f, declared ? 1.0f : 0.0f };
    for (int i = 0; i < OrpheusEngine::kFogDataFields; i++)
        engine->pulsar_fog_data[i].store(bank[i], std::memory_order_relaxed);
}

// Rising edges of the fog voice going active, and the output peak, over `blocks` blocks.
static int count_fog_phrases(OrpheusEngine* engine, GraphUnit* unit, int blocks, float* peak_out = nullptr) {
    int phrases = 0; bool prev = false; float peak = 0.0f;
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(unit, engine, 512, 48000.0f);
        for (int i = 0; i < 512; i++) peak = std::fmax(peak, std::fabs(engine->pulsar_out_l[i]));
        const bool now = engine->pulsar_state->fog_voice.active();
        if (now && !prev) phrases++;
        prev = now;
    }
    if (peak_out) *peak_out = peak;
    return phrases;
}

static bool test_fog_bed_sounds_on_entry_and_every_interval() {
    printf("\n=== Test: a fog section sounds on its first bar and every interval after ===\n");
    OrpheusEngine* engine = make_muted_fog_engine();
    GraphUnit unit = make_fog_unit();
    FogTestBed bed; bed.level = 0.8f; bed.interval = 4; bed.pattern = fog::kSingle;
    push_fog_arrangement(engine, bed, bed, /*bars=*/8);
    trigger_vibe_load(engine);
    float peak = 0.0f;
    // Bars 0 and 4 of section 0, then bar 8 = bar 0 of section 1: three phrases in 10 bars.
    const int phrases = count_fog_phrases(engine, &unit, 10 * kBlocksPerBar, &peak);
    printf("  phrases=%d peak=%.3f\n", phrases, peak);
    bool ok = phrases == 3 && peak > 0.02f && peak < 1.0f;
    printf("Fog bed cadence: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_fog_level_zero_bed_is_silent() {
    printf("\n=== Test: a section with no fog level never sounds the horn ===\n");
    OrpheusEngine* engine = make_muted_fog_engine();
    GraphUnit unit = make_fog_unit();
    FogTestBed none;
    push_fog_arrangement(engine, none, none, /*bars=*/8);
    trigger_vibe_load(engine);
    float peak = 0.0f;
    const int phrases = count_fog_phrases(engine, &unit, 10 * kBlocksPerBar, &peak);
    printf("  phrases=%d peak=%.6f\n", phrases, peak);
    bool ok = phrases == 0 && peak == 0.0f;
    printf("Fog bed off: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_fog_anomaly_manual_trigger_and_undeclared() {
    printf("\n=== Test: the manual trigger sounds a declared FogHornAnomaly, ignores an undeclared one ===\n");
    bool ok = true;
    for (int declared = 0; declared < 2; declared++) {
        OrpheusEngine* engine = make_muted_fog_engine();
        GraphUnit unit = make_fog_unit();
        FogTestBed none;
        push_fog_arrangement(engine, none, none, /*bars=*/8);
        push_fog_anomaly_bank(engine, /*probability=*/0.0f, declared == 1);
        trigger_vibe_load(engine);
        for (int b = 0; b < kBlocksPerBar; b++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
        engine->pulsar_anomaly_request.store(1, std::memory_order_release);
        float peak = 0.0f;
        const int phrases = count_fog_phrases(engine, &unit, 3 * kBlocksPerBar, &peak);
        printf("  declared=%d phrases=%d peak=%.5f\n", declared, phrases, peak);
        if (declared) ok &= (phrases == 1 && peak > 0.02f);
        else ok &= (phrases == 0 && peak == 0.0f);
        orpheus_engine_destroy(engine);
    }
    printf("Fog anomaly manual: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fog_anomaly_auto_roll() {
    printf("\n=== Test: the auto roll fires at probability 1 and never at 0 ===\n");
    bool ok = true;
    for (int p = 0; p < 2; p++) {
        OrpheusEngine* engine = make_muted_fog_engine();
        GraphUnit unit = make_fog_unit();
        FogTestBed none;
        push_fog_arrangement(engine, none, none, /*bars=*/4);
        push_fog_anomaly_bank(engine, p == 0 ? 1.0f : 0.0f, /*declared=*/true);
        trigger_vibe_load(engine);
        const int phrases = count_fog_phrases(engine, &unit, 10 * kBlocksPerBar);
        printf("  probability=%d phrases=%d\n", 1 - p, phrases);
        if (p == 0) ok &= phrases >= 1; else ok &= phrases == 0;
        orpheus_engine_destroy(engine);
    }
    printf("Fog anomaly auto: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fog_pitch_follows_the_vibe_root() {
    printf("\n=== Test: the horn is tuned to the vibe's root note ===\n");
    float rms_hz[2];
    for (int root = 0; root < 2; root++) {
        OrpheusEngine* engine = make_muted_fog_engine();
        GraphUnit unit = make_fog_unit();
        engine->pulsar_root_note.store(root == 0 ? 0 : 7, std::memory_order_relaxed);   // C vs G
        FogTestBed bed; bed.level = 1.0f; bed.interval = 16; bed.pattern = fog::kSingle; bed.distance = 0.0f;
        push_fog_arrangement(engine, bed, bed, /*bars=*/8);
        trigger_vibe_load(engine);
        // Collect ~2 s of the held blast and measure its pitch by upward zero crossings.
        std::vector<float> pcm;
        for (int b = 0; b < 260; b++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            if (b >= 70) pcm.insert(pcm.end(), engine->pulsar_out_l, engine->pulsar_out_l + 512);
        }
        // Power at the C2 and G2 fundamentals (best bin within 2 Hz of each).
        const float len_s = static_cast<float>(pcm.size()) / kFogSr;
        auto near = [&](float hz) {
            float best = 0.0f;
            for (float d = -2.0f; d <= 2.0f; d += 0.5f) best = std::max(best, tone_power(pcm, 0.0f, len_s, hz + d));
            return best;
        };
        rms_hz[root] = near(65.4f) / std::max(1e-12f, near(98.0f));
        orpheus_engine_destroy(engine);
    }
    // The horn carries an open fifth, so G2 is always present; what moves is how much C2 there
    // is against it. With the root on C the C2 bin leads, on G it all but vanishes.
    printf("  C2/G2 power: root C=%.3f, root G=%.4f\n", rms_hz[0], rms_hz[1]);
    bool ok = rms_hz[0] > 0.3f && rms_hz[1] < rms_hz[0] * 0.2f;
    printf("Fog root: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fog_data_port_routes() {
    printf("\n=== Test: fog_data ports reach the engine bank ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int i = 0; i < OrpheusEngine::kFogDataFields; i++) {
        char sym[32]; snprintf(sym, sizeof(sym), "fog_data_%d", i);
        orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.pulsar", sym, 0.1f * (i + 1));
        ok &= std::fabs(engine->pulsar_fog_data[i].load() - 0.1f * (i + 1)) < 1e-6f;
    }
    printf("Fog data routing: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_fog_tests() {
    printf("\n=== Pulsar Fog Horn Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_fog_voice_idle_until_triggered()) suite_pass++; else suite_fail++;
    if (test_fog_single_blast_swells_holds_and_decays()) suite_pass++; else suite_fail++;
    if (test_fog_dum_duum_is_three_blasts()) suite_pass++; else suite_fail++;
    if (test_fog_distance_darkens_and_quiets()) suite_pass++; else suite_fail++;
    if (test_fog_retrigger_replaces_the_phrase()) suite_pass++; else suite_fail++;
    if (test_fog_bed_sounds_on_entry_and_every_interval()) suite_pass++; else suite_fail++;
    if (test_fog_level_zero_bed_is_silent()) suite_pass++; else suite_fail++;
    if (test_fog_anomaly_manual_trigger_and_undeclared()) suite_pass++; else suite_fail++;
    if (test_fog_anomaly_auto_roll()) suite_pass++; else suite_fail++;
    if (test_fog_pitch_follows_the_vibe_root()) suite_pass++; else suite_fail++;
    if (test_fog_data_port_routes()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
