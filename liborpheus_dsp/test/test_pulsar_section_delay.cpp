// SectionDelay: a section's own Pulsar delay times and feedback (slots 55-57, pulsar_section_delay.h).
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_section_delay.h"
#include "../src/orpheus_graph.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static constexpr int kDelayBlocksPerBar = 188;   // 120 BPM: a bar is 187.5 blocks of 512

static bool near(float a, float b, float tol = 1e-4f) { return std::fabs(a - b) <= tol; }

static bool test_section_delay_blend() {
    printf("\n=== Test: SectionDelay blend falls back to the vibe and lerps on the pre-roll ===\n");
    using namespace section_delay;
    bool ok = true;
    ok &= blend(kUnset, kUnset, false, 0.3f, 0.0f) == kUnset;   // nothing authored: untouched
    ok &= blend(kUnset, kUnset, true, 0.3f, 0.5f) == kUnset;
    ok &= near(blend(0.8f, kUnset, false, 0.3f, 0.7f), 0.8f);   // no transition: progress ignored
    ok &= near(blend(0.8f, kUnset, true, 0.3f, 0.5f), 0.55f);   // drains back to the vibe
    ok &= near(blend(kUnset, 0.8f, true, 0.3f, 0.25f), 0.425f); // swells in from the vibe
    ok &= near(blend(0.2f, 0.6f, true, 0.3f, 1.0f), 0.6f);
    ok &= decode(0.0f, 1.0f) == kUnset;
    ok &= near(decode(1.5f, 1.0f), 0.5f);
    ok &= near(decode(1.99f, kMaxFeedback), kMaxFeedback);
    printf("SectionDelay blend: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static OrpheusEngine* make_delay_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    solo_track(engine, -1);
    pin_pulsar_rngs(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    return engine;
}

struct DelayBed { float time_a = -1.0f, time_b = -1.0f, feedback = -1.0f; };

// Two sections of `bars` bars each, 0 -> 1 -> 0, with a `trans_bars` pre-roll on each edge.
static void push_delay_arrangement(OrpheusEngine* engine, const DelayBed& d0, const DelayBed& d1,
                                   int bars, int trans_bars) {
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
    const DelayBed* d[2] = { &d0, &d1 };
    auto wire = [](float v) { return v >= 0.0f ? v + 1.0f : 0.0f; };
    for (int s = 0; s < 2; s++) {
        const int b = s * kStride;
        sd[b + 0] = sd[b + 1] = static_cast<float>(bars);
        sd[b + 2] = 1.0f; sd[b + 3] = 0.8f; sd[b + 4] = 1.0f;
        sd[b + kSectionDelaySlot + 0] = wire(d[s]->time_a);
        sd[b + kSectionDelaySlot + 1] = wire(d[s]->time_b);
        sd[b + kSectionDelaySlot + 2] = wire(d[s]->feedback);
    }
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);
    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[0] = 1.0f; tr[1] = 1.0f; tr[2] = static_cast<float>(trans_bars);
    const int s1 = kMaxSectionTransitions * 3;
    tr[s1 + 0] = 0.0f; tr[s1 + 1] = 1.0f; tr[s1 + 2] = static_cast<float>(trans_bars);
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

static GraphUnit make_pulsar_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

static bool test_section_delay_publishes_and_clears() {
    printf("\n=== Test: a section's delay reaches the engine, an unauthored one clears it ===\n");
    OrpheusEngine* engine = make_delay_engine();
    GraphUnit unit = make_pulsar_unit();
    engine->pulsar_delay_time_a.store(0.2f, std::memory_order_relaxed);
    DelayBed wet; wet.time_a = 0.8f; wet.feedback = 0.6f;
    push_delay_arrangement(engine, wet, DelayBed{}, /*bars=*/4, /*trans_bars=*/0);
    trigger_vibe_load(engine);
    for (int b = 0; b < kDelayBlocksPerBar; b++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const float a0 = engine->pulsar_section_delay_time_a.load();
    const float b0 = engine->pulsar_section_delay_time_b.load();
    const float f0 = engine->pulsar_section_delay_feedback.load();
    for (int b = 0; b < 4 * kDelayBlocksPerBar; b++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const float a1 = engine->pulsar_section_delay_time_a.load();
    const float f1 = engine->pulsar_section_delay_feedback.load();
    printf("  section 0: a=%.3f b=%.3f fb=%.3f  section 1: a=%.3f fb=%.3f\n", a0, b0, f0, a1, f1);
    const bool ok = near(a0, 0.8f) && b0 < 0.0f && near(f0, 0.6f) && a1 < 0.0f && f1 < 0.0f;
    printf("SectionDelay publish: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_delay_glides_on_pre_roll() {
    printf("\n=== Test: the delay time glides toward the next section over the pre-roll ===\n");
    OrpheusEngine* engine = make_delay_engine();
    GraphUnit unit = make_pulsar_unit();
    engine->pulsar_delay_time_a.store(0.2f, std::memory_order_relaxed);
    DelayBed near_tap; near_tap.time_a = 0.2f;
    DelayBed far_tap; far_tap.time_a = 0.6f;
    push_delay_arrangement(engine, near_tap, far_tap, /*bars=*/4, /*trans_bars=*/2);
    trigger_vibe_load(engine);
    // Bar 2 is the pre-roll's first bar; halfway through bar 3 the ramp is about 3/4 done.
    float before = 0.0f, mid = 0.0f, after = 0.0f;
    for (int b = 0; b < 6 * kDelayBlocksPerBar; b++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (b == kDelayBlocksPerBar + 20) before = engine->pulsar_section_delay_time_a.load();
        if (b == 3 * kDelayBlocksPerBar) mid = engine->pulsar_section_delay_time_a.load();
        if (b == 5 * kDelayBlocksPerBar) after = engine->pulsar_section_delay_time_a.load();
    }
    printf("  before=%.3f mid=%.3f after=%.3f\n", before, mid, after);
    const bool ok = near(before, 0.2f) && mid > 0.3f && mid < 0.55f && near(after, 0.6f);
    printf("SectionDelay glide: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The first echo of an impulse, in seconds, from the pulsar delay unit alone.
static float first_echo_seconds(OrpheusEngine* engine) {
    static GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    const float* l = unit.output_buffers[OPORT_OUT];
    std::memset(engine->pulsar_delay_send_l, 0, sizeof(engine->pulsar_delay_send_l));
    std::memset(engine->pulsar_delay_send_r, 0, sizeof(engine->pulsar_delay_send_r));
    for (int b = 0; b < 40; b++) unit_process_pulsar_delay(&unit, engine, 512, 48000.0f);   // settle smoothing
    float peak = 0.0f; int at = -1;
    for (int b = 0; b < 300; b++) {
        engine->pulsar_delay_send_l[0] = engine->pulsar_delay_send_r[0] = (b == 0) ? 1.0f : 0.0f;
        unit_process_pulsar_delay(&unit, engine, 512, 48000.0f);
        for (int i = 0; i < 512; i++)
            if (std::fabs(l[i]) > peak) { peak = std::fabs(l[i]); at = b * 512 + i; }
    }
    return at / 48000.0f;
}

static bool test_section_delay_moves_the_echo() {
    printf("\n=== Test: the delay unit follows the section's time, else the vibe's ===\n");
    OrpheusEngine* engine = make_delay_engine();
    engine->pulsar_delay_time_a.store(0.1f, std::memory_order_relaxed);
    engine->pulsar_delay_time_b.store(0.1f, std::memory_order_relaxed);
    const float vibe_echo = first_echo_seconds(engine);
    engine->pulsar_section_delay_time_a.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_section_delay_time_b.store(0.5f, std::memory_order_relaxed);
    const float section_echo = first_echo_seconds(engine);
    printf("  vibe echo=%.3fs section echo=%.3fs\n", vibe_echo, section_echo);
    const bool ok = std::fabs(vibe_echo - 0.209f) < 0.01f && std::fabs(section_echo - 1.005f) < 0.01f;
    printf("SectionDelay echo: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_bench_delay_wins_over_the_section() {
    printf("\n=== Test: a Mix Bench delay time wins over the section's, -1 hands it back ===\n");
    OrpheusEngine* engine = make_delay_engine();
    engine->pulsar_section_delay_time_a.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_section_delay_time_b.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_bench_delay_time_a.store(0.1f, std::memory_order_relaxed);
    engine->pulsar_bench_delay_time_b.store(0.1f, std::memory_order_relaxed);
    const float bench_echo = first_echo_seconds(engine);
    engine->pulsar_bench_delay_time_a.store(-1.0f, std::memory_order_relaxed);
    engine->pulsar_bench_delay_time_b.store(-1.0f, std::memory_order_relaxed);
    const float section_echo = first_echo_seconds(engine);
    printf("  bench echo=%.3fs section echo=%.3fs\n", bench_echo, section_echo);
    const bool ok = std::fabs(bench_echo - 0.209f) < 0.01f && std::fabs(section_echo - 1.005f) < 0.01f;
    printf("Bench delay echo: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_section_delay_tests() {
    printf("\n========== Pulsar Section Delay Tests ==========\n");
    bool ok = true;
    ok &= test_section_delay_blend();
    ok &= test_section_delay_publishes_and_clears();
    ok &= test_section_delay_glides_on_pre_roll();
    ok &= test_section_delay_moves_the_echo();
    ok &= test_bench_delay_wins_over_the_section();
    return ok;
}
