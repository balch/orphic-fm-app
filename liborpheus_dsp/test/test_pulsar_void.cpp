#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "pulsar_void.h"
#include "stmlib/utils/random.h"  // pin the global metallic-noise RNG for reproducible mix tests
#include <cstdio>
#include <cmath>
#include <cstring>

static bool vclose(float a, float b) { return std::fabs(a - b) < 1e-4f; }

// Ramp down is monotonic non-increasing 1.0 -> floor; ramp up mirrors it.
static bool test_void_ramps_are_gradual() {
    printf("\n=== Test: void ramps are gradual and monotonic ===\n");
    VoidConfig cfg;                 // floor 0.05, ramp_down 1 bar, floor 1-2, ramp_up 1.5, ghost 0.5
    VoidAnomaly v;
    compute_void_schedule(v, cfg, /*floor_bars=*/2.0f);

    bool susp = false;
    float prev = 2.0f;
    bool down_mono = true;
    for (float pos = 0.0f; pos <= v.ramp_down_end; pos += 1.0f) {
        float g = void_arc_gain(v, pos, &susp);
        if (g > prev + 1e-4f) down_mono = false;
        prev = g;
    }
    float g_start = void_arc_gain(v, 0.0f, &susp);
    float g_floor = void_arc_gain(v, v.ramp_down_end + 1.0f, &susp);
    bool ok = down_mono && vclose(g_start, 1.0f) && vclose(g_floor, cfg.floor_level);
    printf("  down monotonic=%d start=%.3f floor=%.3f -- %s\n",
           down_mono, g_start, g_floor, ok ? "PASS" : "FAIL");
    return ok;
}

// The floor halves suppress note-ons; ramps and ghost do not.
static bool test_void_suppression_windows() {
    printf("\n=== Test: void suppresses note-ons only on the floor ===\n");
    VoidConfig cfg;
    VoidAnomaly v;
    compute_void_schedule(v, cfg, 2.0f);
    bool s_ramp = false, s_floor = false, s_ghost = false;
    void_arc_gain(v, v.ramp_down_end * 0.5f, &s_ramp);                    // mid ramp-down
    void_arc_gain(v, (v.ramp_down_end + v.floor1_end) * 0.5f, &s_floor);  // mid floor half 1
    float ghost_mid = (v.floor1_end + v.ghost_end) * 0.5f;
    void_arc_gain(v, ghost_mid, &s_ghost);                               // mid ghost bar
    bool s_floor2 = false;
    float floor2_mid = (v.ghost_end + v.floor2_end) * 0.5f;   // mid of the second floor half
    void_arc_gain(v, floor2_mid, &s_floor2);
    bool ok = (!s_ramp) && s_floor && (!s_ghost) && s_floor2;
    printf("  ramp=%d floor=%d ghost=%d floor2=%d (want 0/1/0/1) -- %s\n",
           s_ramp, s_floor, s_ghost, s_floor2, ok ? "PASS" : "FAIL");
    return ok;
}

// Ghost bar lifts the gain above the floor; with ghost_intensity=0 the floor is flat.
static bool test_void_ghost_bump_and_disable() {
    printf("\n=== Test: void ghost bump present, and absent when zeroed ===\n");
    VoidConfig cfg; cfg.ghost_intensity = 0.6f;
    VoidAnomaly v; compute_void_schedule(v, cfg, 2.0f);
    bool s = false;
    float ghost_mid = (v.floor1_end + v.ghost_end) * 0.5f;
    float g_ghost = void_arc_gain(v, ghost_mid, &s);
    bool bump_ok = g_ghost > cfg.floor_level + 0.01f && vclose(g_ghost, 0.6f);

    VoidConfig cfg0; cfg0.ghost_intensity = 0.0f;
    VoidAnomaly v0; compute_void_schedule(v0, cfg0, 2.0f);
    bool no_ghost_ok = !v0.has_ghost && vclose(v0.ghost_end, v0.floor1_end);
    bool ok = bump_ok && no_ghost_ok;
    printf("  ghost gain=%.3f bump_ok=%d disabled_ok=%d -- %s\n",
           g_ghost, bump_ok, no_ghost_ok, ok ? "PASS" : "FAIL");
    return ok;
}

// Ramp up mirrors ramp down (monotonic non-decreasing floor->1.0), and past the
// arc the gain returns to full with suppression off.
static bool test_void_ramp_up_and_boundary() {
    printf("\n=== Test: void ramps back up and returns to full ===\n");
    VoidConfig cfg;
    VoidAnomaly v; compute_void_schedule(v, cfg, 2.0f);
    bool susp = false;
    float prev = -1.0f;
    bool up_mono = true;
    for (float pos = v.floor2_end; pos <= v.ramp_up_end; pos += 1.0f) {
        float g = void_arc_gain(v, pos, &susp);
        if (g < prev - 1e-4f) up_mono = false;
        prev = g;
    }
    float g_before_end = void_arc_gain(v, v.ramp_up_end - 1.0f, &susp);
    bool s_after = false;
    float g_after = void_arc_gain(v, v.ramp_up_end + 5.0f, &s_after);   // past the arc
    bool ok = up_mono && g_before_end > cfg.floor_level + 0.01f
              && vclose(g_after, 1.0f) && (s_after == false);
    printf("  up monotonic=%d before_end=%.3f after=%.3f suppress_after=%d -- %s\n",
           up_mono, g_before_end, g_after, s_after, ok ? "PASS" : "FAIL");
    return ok;
}

// probability=1.0 with equal floor bounds always fires and is fully deterministic.
static bool test_void_auto_arms_end_aligned() {
    printf("\n=== Test: auto arm is end-aligned within the section ===\n");
    VoidConfig cfg; cfg.probability = 1.0f;
    cfg.floor_bars_min = 1.0f; cfg.floor_bars_max = 1.0f;   // deterministic floor
    VoidAnomaly v; uint32_t rng = 999;
    float section_total = 40.0f * kVoidStepsPerBar;         // 40 musical bars of room
    bool armed = arm_void_auto(v, cfg, section_total, rng);
    float arc = void_arc_total_steps(v);
    bool ok = armed && v.armed && vclose((float)v.start_step, section_total - arc);
    printf("  armed=%d start=%.1f arc=%.1f section=%.1f -- %s\n",
           armed, v.start_step, arc, section_total, ok ? "PASS" : "FAIL");
    return ok;
}

// Room gate: a section shorter than the arc never arms.
static bool test_void_auto_room_gate() {
    printf("\n=== Test: auto arm skips when the section has no room ===\n");
    VoidConfig cfg; cfg.probability = 1.0f;
    cfg.floor_bars_min = 2.0f; cfg.floor_bars_max = 2.0f;
    VoidAnomaly v; uint32_t rng = 7;
    bool armed = arm_void_auto(v, cfg, /*section_total=*/2.0f * kVoidStepsPerBar, rng);
    bool ok = !armed && !v.armed;
    printf("  armed=%d (want 0) -- %s\n", armed, ok ? "PASS" : "FAIL");
    return ok;
}

// probability=0 never auto-arms.
static bool test_void_auto_disabled() {
    printf("\n=== Test: probability 0 never auto-arms ===\n");
    VoidConfig cfg; cfg.probability = 0.0f;
    VoidAnomaly v; uint32_t rng = 1;
    bool armed = arm_void_auto(v, cfg, 100.0f * kVoidStepsPerBar, rng);
    bool ok = !armed;
    printf("  armed=%d (want 0) -- %s\n", armed, ok ? "PASS" : "FAIL");
    return ok;
}

// Manual arm snaps to the next musical-bar boundary from the current cursor.
static bool test_void_manual_next_bar() {
    printf("\n=== Test: manual arm snaps to the next musical bar ===\n");
    VoidConfig cfg;  // schema defaults (floor 0.05, ramp_down 1, floor_bars 1-2, ramp_up 1.5, ghost 0.5)
    cfg.floor_bars_min = 1.0f; cfg.floor_bars_max = 1.0f;
    VoidAnomaly v; v.cursor = 20.0;      // 1.25 bars into the section
    uint32_t rng = 3;
    arm_void_manual(v, cfg, rng);
    bool ok = v.armed && vclose((float)v.start_step, 32.0f);  // next 16-step boundary after 20
    printf("  start=%.1f (want 32) armed=%d -- %s\n", v.start_step, v.armed, ok ? "PASS" : "FAIL");
    return ok;
}

// The floor draw must consume NO RNG when bounds are equal (deterministic
// tests rely on this), and MUST advance the RNG when bounds differ.
static bool test_void_draw_floor_no_rng_when_equal_bounds() {
    printf("\n=== Test: floor draw consumes no RNG when bounds equal ===\n");
    VoidConfig cfg; cfg.floor_bars_min = 2.0f; cfg.floor_bars_max = 2.0f;
    uint32_t rng = 123456u; uint32_t before = rng;
    float fb = void_draw_floor_bars(cfg, rng);
    bool equal_no_rng = (rng == before) && vclose(fb, 2.0f);

    VoidConfig cfg2; cfg2.floor_bars_min = 1.0f; cfg2.floor_bars_max = 3.0f;
    uint32_t rng2 = 123456u; uint32_t before2 = rng2;
    void_draw_floor_bars(cfg2, rng2);
    bool range_advances_rng = (rng2 != before2);

    bool ok = equal_no_rng && range_advances_rng;
    printf("  equal:rng_unchanged=%d fb=%.2f | range:rng_advanced=%d -- %s\n",
           (rng == before), fb, range_advances_rng, ok ? "PASS" : "FAIL");
    return ok;
}

// Bank order [prob, floor, rampDown, floorMin, floorMax, rampUp, ghost] unpacks
// into VoidConfig on load_vibe, exactly like the section-data / band-data banks.
static bool test_void_config_unpacks_from_atomics() {
    printf("\n=== Test: void_data atomics unpack into VoidConfig ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Bank: [0]=prob [1]=floor [2]=rampDown [3]=floorMin [4]=floorMax [5]=rampUp [6]=ghost.
    // DISTINCT values in every slot so a bank-index transposition (e.g. rampDown
    // vs floorMin vs rampUp) can't slip past — this is the exact contract the
    // Kotlin push and the C++ unpack must agree on.
    engine->pulsar_void_data[0].store(0.04f, std::memory_order_relaxed);  // probability
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed);  // floor_level
    engine->pulsar_void_data[2].store(1.25f, std::memory_order_relaxed);  // ramp_down_bars
    engine->pulsar_void_data[3].store(0.75f, std::memory_order_relaxed);  // floor_bars_min
    engine->pulsar_void_data[4].store(2.5f,  std::memory_order_relaxed);  // floor_bars_max
    engine->pulsar_void_data[5].store(1.75f, std::memory_order_relaxed);  // ramp_up_bars
    engine->pulsar_void_data[6].store(0.6f,  std::memory_order_relaxed);  // ghost_intensity

    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);   // first render runs load_vibe

    PulsarState* ps = engine->pulsar_state;
    bool ok = ps
        && vclose(ps->void_config.probability,    0.04f)
        && vclose(ps->void_config.floor_level,    0.05f)
        && vclose(ps->void_config.ramp_down_bars, 1.25f)
        && vclose(ps->void_config.floor_bars_min, 0.75f)
        && vclose(ps->void_config.floor_bars_max, 2.5f)
        && vclose(ps->void_config.ramp_up_bars,   1.75f)
        && vclose(ps->void_config.ghost_intensity, 0.6f);
    printf("  prob=%.3f floor=%.3f rampDn=%.3f floorMin=%.3f floorMax=%.3f rampUp=%.3f ghost=%.3f -- %s\n",
           ps ? ps->void_config.probability    : -1.0f,
           ps ? ps->void_config.floor_level     : -1.0f,
           ps ? ps->void_config.ramp_down_bars  : -1.0f,
           ps ? ps->void_config.floor_bars_min  : -1.0f,
           ps ? ps->void_config.floor_bars_max  : -1.0f,
           ps ? ps->void_config.ramp_up_bars    : -1.0f,
           ps ? ps->void_config.ghost_intensity : -1.0f, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Minimal two-section A/B arrangement, copied from test_pulsar_sections.cpp
// (that copy is static/internal to its TU). Section 0 -> 1 -> 0, each
// bars_per_section arrangement bars long, hard-cut transitions, no solos.
static void push_two_section_ab_arrangement(OrpheusEngine* engine,
                                            int bars_per_section) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = kSectionDataFields;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
        section_data[s * kSectionStride + 9]  = 0;
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
    }
    section_data[0] = (float)bars_per_section;
    section_data[1] = (float)bars_per_section;
    section_data[2] = 1;
    section_data[3] = 0.8f;
    section_data[4] = 1;
    int s1 = kSectionStride;
    section_data[s1 + 0] = (float)bars_per_section;
    section_data[s1 + 1] = (float)bars_per_section;
    section_data[s1 + 2] = 1;
    section_data[s1 + 3] = 0.8f;
    section_data[s1 + 4] = 1;
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;
    trans[24] = 0; trans[25] = 1.0f; trans[26] = 0;
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    for (int s = 0; s < 8; s++) {
        engine->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
        for (int i = 0; i < 12; i++)
            engine->pulsar_section_progression_degrees[s * 12 + i].store(0, std::memory_order_relaxed);
    }

    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 6; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// Integration proof: with probability forced to 1.0 and a deterministic floor,
// per-block RMS dips hard mid-section (the quiet floor) and climbs back by the
// section boundary (the ramp-up). This is the real verification that the void
// state machine is wired into the render loop. Measurements are phase-anchored to
// the void state rather than to a fixed block index, because the per-step note
// envelope makes raw block RMS swing wildly within a single bar.
static bool test_void_dips_and_recovers() {
    printf("\n=== Test: void dips output mid-section and recovers by boundary ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    push_two_section_ab_arrangement(engine, /*bars_per_section=*/6);  // 6 arrangement bars

    // Force the void ON with a deterministic 1-bar floor and 1-bar ramps (arc = 3
    // musical bars) so the whole arc fits inside the 6-bar section with room to spare.
    engine->pulsar_void_data[0].store(1.0f, std::memory_order_relaxed);  // probability
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed); // floor
    engine->pulsar_void_data[2].store(1.0f, std::memory_order_relaxed);  // ramp down
    engine->pulsar_void_data[3].store(1.0f, std::memory_order_relaxed);  // floor min
    engine->pulsar_void_data[4].store(1.0f, std::memory_order_relaxed);  // floor max (== min)
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);  // ramp up
    engine->pulsar_void_data[6].store(0.0f, std::memory_order_relaxed);  // no ghost (flat floor)

    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    // Observe the armed void arcs. Each arc is end-aligned to its section boundary
    // (arm_void_auto pins start_step = section_total - arc), so it runs: full-gain
    // lead-in -> quiet suppressed floor mid-arc -> ramp-up that restores gain to 1.0
    // right at the boundary, immediately followed by the NEXT section's full-gain
    // lead-in. Measurements are phase-anchored to the void state (not a fixed block
    // index) so the per-step note envelope can't alias a decay trough:
    //   baseline - full-gain lead-in of the section-0 arc, before it ducks,
    //   min_rms  - the floor (void gain 0.05 + note-ons suppressed -> ~silence),
    //   end_rms  - the full-gain lead-in AFTER the dip, i.e. output restored to full
    //              by the boundary. Comparing lead-in to lead-in keeps the ratio from
    //              being confounded by whichever bar happens to hold sparse notes.
    // The 0.4 dip / 0.5 recovery thresholds below are the proof the void is audible.
    float baseline = 0.0f, min_rms = 1e9f, end_rms = 0.0f;
    bool saw_floor = false;
    // Phase-anchored readback of VIZ_PULSAR_VOID_GAIN alongside the audio RMS above —
    // proves the exported viz channel (what SynthEngineMonitor.kt folds into
    // PulsarVizData.voidGain to drive the Kotlin VIBE-dropdown glow) tracks the SAME
    // internal arc as the audible duck, not just that the engine keeps running.
    float viz_buf[480];
    int viz_read_pos = 0;
    float viz_baseline = 0.0f, viz_min = 1e9f, viz_end = 0.0f;
    for (int i = 0; i < 6000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        float rms = compute_rms(engine->pulsar_out_l, 512);
        int cur = ps->section_state.current_section;
        bool armed = ps->void_state.armed;
        double vpos = ps->void_state.cursor - ps->void_state.start_step;
        bool lead_in = armed && vpos <= 0.0;  // full-gain, arc not yet ducking
        int vc = orpheus_engine_get_viz(engine, VIZ_PULSAR_VOID_GAIN, viz_buf, 480, &viz_read_pos);
        float viz_gain = vc > 0 ? viz_buf[vc - 1] : -1.0f;  // -1 sentinel: no new sample this block
        // Full-gain lead-in of the section-0 arc.
        if (cur == 0 && lead_in) {
            baseline = std::fmax(baseline, rms);
            if (viz_gain >= 0.0f) viz_baseline = std::fmax(viz_baseline, viz_gain);
        }
        // The quiet floor of the section-0 arc: void gain 0.05, new note-ons muted.
        if (cur == 0 && armed && ps->void_state.suppress_note_ons) {
            min_rms = std::fmin(min_rms, rms);
            saw_floor = true;
            if (viz_gain >= 0.0f) viz_min = std::fmin(viz_min, viz_gain);
        }
        // Recovery: the first full-gain lead-in reached AFTER the dip — the void has
        // released and output is back to full by/at the boundary.
        if (saw_floor && lead_in) {
            end_rms = std::fmax(end_rms, rms);
            if (viz_gain >= 0.0f) viz_end = std::fmax(viz_end, viz_gain);
        }
    }
    bool dipped = min_rms < baseline * 0.4f;          // clearly quieter at the floor
    bool recovered = end_rms > baseline * 0.5f;        // came back up by the boundary
    // The viz channel must pin the exact deterministic constants, not just the shape:
    // exactly 1.0 at lead-in and after recovery (the gain_smoothed slew clamps AT its
    // target and the idle fast-path snaps to exactly 1.0 once within 1e-4), and
    // exactly the configured floor_level (0.05) during suppression (the floor holds
    // the slewed gain at target for ~90 straight blocks, so the min can't miss it).
    // Slack below is only for float noise, not for measurement uncertainty.
    bool viz_baseline_ok = viz_baseline > 0.999f;
    bool viz_dipped = viz_min < 0.08f;
    bool viz_recovered = viz_end > 0.999f;
    bool ok = baseline > 1e-4f && dipped && recovered
              && viz_baseline_ok && viz_dipped && viz_recovered;
    printf("  baseline=%.4f min=%.4f end=%.4f dipped=%d recovered=%d -- %s\n",
           baseline, min_rms, end_rms, dipped, recovered, ok ? "PASS" : "FAIL");
    printf("  viz_baseline=%.4f viz_min=%.4f viz_end=%.4f -- %s\n",
           viz_baseline, viz_min, viz_end,
           (viz_baseline_ok && viz_dipped && viz_recovered) ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Integration proof: the manual trigger (Kotlin UI bumping pulsar_anomaly_request)
// must be able to fire the void even when probability==0 (auto-firing disabled), as
// long as the vibe DECLARES the void (void_data[7]=1). The shape fields (floor/ramp/
// ghost) are this vibe's own config — there is no default fallback for undeclared
// vibes (see test_void_manual_ignored_when_undeclared below). Seeds are pinned per
// the project's anti-flake convention (test_pulsar_signal_quality.cpp's make_engine):
// pulsar_seed pins the pattern RNG (load_vibe re-stirs from the wall clock when
// seed==0), and stmlib::Random::Seed pins the 808 hat/snare noise RNG that the mixed
// RMS measurement is sensitive to.
static bool test_void_manual_trigger_fires_with_zero_probability() {
    printf("\n=== Test: manual anomaly trigger fires even at probability 0 (declared) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    push_two_section_ab_arrangement(engine, 8);

    // Auto disabled, but the manual path fires because the vibe DECLARES the void.
    engine->pulsar_void_data[0].store(0.0f, std::memory_order_relaxed);  // probability 0
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed);
    engine->pulsar_void_data[2].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[3].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[4].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[6].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[7].store(1.0f, std::memory_order_relaxed);  // declared
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    // Warm up and capture baseline.
    float baseline = 0.0f;
    for (int i = 0; i < 60; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        baseline = std::fmax(baseline, compute_rms(engine->pulsar_out_l, 512));
    }
    PulsarState* ps = engine->pulsar_state;   // valid: at least one block already rendered
    // Fire the manual trigger (bump the counter, as the ViewModel would).
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    // Phase-anchor the dip to the void's OWN armed state rather than a raw
    // window min. The baseline fixture's sparse tracks (density as low as 0.08) can
    // produce a near-silent 512-sample block from ordinary pattern gaps —
    // confirmed empirically: with the edge-detect/arm NOT yet wired up (so
    // void_state.armed can never become true), this same seed still produces
    // a min_rms of ~0.0002 at block 244, purely from note sparsity. Requiring
    // the minimum to land while armed==true ties the measurement to the void
    // engine itself, so a naturally-quiet block can't masquerade as the proof
    // in either direction (false PASS pre-implementation, false confidence
    // post-implementation).
    bool saw_armed = false;
    float min_rms = 1e9f;
    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (ps->void_state.armed) {
            saw_armed = true;
            min_rms = std::fmin(min_rms, compute_rms(engine->pulsar_out_l, 512));
        }
    }
    bool ok = baseline > 1e-4f && saw_armed && min_rms < baseline * 0.4f;
    printf("  baseline=%.4f min=%.4f saw_armed=%d -- %s\n",
           baseline, saw_armed ? min_rms : -1.0f, saw_armed, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Regression guard for the declared-only refactor: an UNDECLARED vibe (void_data[7]
// left at 0, the atomic bank's zero-init default) must treat the manual trigger as a
// no-op — no default-shape fallback. `armed` never going true is the precise,
// deterministic proof (arm_void_manual is simply never called); the max-RMS check is
// a looser sanity check that the engine keeps playing normally rather than having
// broken silently. We deliberately do NOT assert a MIN-RMS floor across the window:
// the sibling test above documents that the baseline fixture's sparse tracks (density as low
// as 0.08) already produce near-silent 512-sample blocks from ordinary pattern gaps,
// so a naive min-RMS check would be flaky/wrong regardless of this fix.
static bool test_void_manual_ignored_when_undeclared() {
    printf("\n=== Test: manual anomaly trigger is a no-op when the vibe does not declare it ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    push_two_section_ab_arrangement(engine, 8);

    // Same shape as the sibling "fires" test above, EXCEPT [7] stays 0 (undeclared).
    engine->pulsar_void_data[0].store(0.0f, std::memory_order_relaxed);  // probability 0
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed);
    engine->pulsar_void_data[2].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[3].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[4].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[6].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_void_data[7].store(0.0f, std::memory_order_relaxed);  // NOT declared
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    // The void-gain viz channel (VIZ_PULSAR_VOID_GAIN) must report a flat 1.0 the
    // whole time the anomaly can never arm — this is the "idle" contract the Kotlin
    // VIBE-dropdown glow depends on (SynthEngineMonitor.kt folds this channel's
    // latest sample into PulsarVizData.voidGain; 1.0 means "no tint").
    float viz_buf[480];
    int viz_read_pos = 0;
    bool viz_stayed_at_one = true;
    auto drain_viz = [&]() {
        int vc = orpheus_engine_get_viz(engine, VIZ_PULSAR_VOID_GAIN, viz_buf, 480, &viz_read_pos);
        for (int i = 0; i < vc; i++) {
            if (std::fabs(viz_buf[i] - 1.0f) > 1e-5f) viz_stayed_at_one = false;
        }
    };

    // Warm up and capture baseline (full-gain, void never touched anything yet).
    float baseline = 0.0f;
    for (int i = 0; i < 60; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        baseline = std::fmax(baseline, compute_rms(engine->pulsar_out_l, 512));
        drain_viz();
    }
    PulsarState* ps = engine->pulsar_state;   // valid: at least one block already rendered
    // Fire the manual trigger — must be ignored (undeclared vibe, no fallback).
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    bool ever_armed = false;
    float max_rms_after = 0.0f;
    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (ps->void_state.armed) ever_armed = true;
        max_rms_after = std::fmax(max_rms_after, compute_rms(engine->pulsar_out_l, 512));
        drain_viz();
    }
    bool stayed_healthy = max_rms_after > baseline * 0.6f;  // still playing normally somewhere
    bool ok = baseline > 1e-4f && !ever_armed && stayed_healthy && viz_stayed_at_one;
    printf("  baseline=%.4f max_after=%.4f ever_armed=%d viz_stayed_at_one=%d -- %s\n",
           baseline, max_rms_after, ever_armed, viz_stayed_at_one, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Edge-detect proof for the manual trigger counter, covering the three behaviors
// the ViewModel depends on:
//   1. A value already sitting on the counter when the vibe loads must NOT fire —
//      load_vibe() resets both the atomic and the prev_anomaly_request mirror, so
//      a stale bump from a prior vibe/session can't leak into the new one.
//   2. Two successive DISTINCT bumps fire twice (once each), each producing its
//      own dip. The engine's arm guard (`!state->void_state.armed`) means a second
//      bump is only honored once the first arc has fully released, so the two
//      bumps are separated by enough processing time for arc 1 to complete.
//   3. Writing the SAME value again (no change from the ViewModel's perspective)
//      must NOT fire a third time.
// Verified behaviorally by counting armed-state rising edges (false -> true)
// directly on PulsarState, which is robust to exact timing/RNG and avoids having
// to hand-derive bar-wrap cadence.
static bool test_void_anomaly_request_edge_detect() {
    printf("\n=== Test: anomaly request counter is edge-detected (not level-triggered) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    // Long section so no section boundary (which would void_reset the arc) falls
    // inside the test's processing window.
    push_two_section_ab_arrangement(engine, /*bars_per_section=*/64);

    engine->pulsar_void_data[0].store(0.0f, std::memory_order_relaxed);  // probability 0
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed); // floor
    engine->pulsar_void_data[2].store(1.0f, std::memory_order_relaxed);  // ramp down (1 bar)
    engine->pulsar_void_data[3].store(1.0f, std::memory_order_relaxed);  // floor min
    engine->pulsar_void_data[4].store(1.0f, std::memory_order_relaxed); // floor max (== min)
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);  // ramp up (1 bar)
    engine->pulsar_void_data[6].store(0.0f, std::memory_order_relaxed);  // no ghost
    engine->pulsar_void_data[7].store(1.0f, std::memory_order_relaxed);  // declared

    // 1. A stale nonzero request sitting on the counter BEFORE the vibe loads must
    //    be neutralized by load_vibe()'s reset, not treated as an armable edge.
    engine->pulsar_anomaly_request.store(9, std::memory_order_relaxed);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    // pulsar_state is lazily allocated inside unit_process_pulsar (and load_vibe
    // runs on the first call that observes the bumped generation) — force that
    // before taking the pointer.
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    int arm_count = 0;
    bool prev_armed = ps->void_state.armed;
    auto pump = [&](int blocks) {
        for (int i = 0; i < blocks; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            bool armed = ps->void_state.armed;
            if (armed && !prev_armed) arm_count++;
            prev_armed = armed;
        }
    };

    // Warm up several bars: the stale counter (still 9 on the atomic, since
    // load_vibe reset it to 0 and nothing has re-bumped it) must not arm anything.
    pump(800);
    bool stale_did_not_fire = (arm_count == 0);

    // 2a. First distinct bump: 0 -> 1. Wait out the full arc (ramp-down + floor +
    // ramp-up, ~3 musical bars) so `armed` releases before the next bump.
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);
    pump(1600);
    bool first_bump_fired = (arm_count == 1) && !ps->void_state.armed;

    // 2b. Second distinct bump: 1 -> 2. Must fire again now that arc 1 has released.
    engine->pulsar_anomaly_request.store(2, std::memory_order_release);
    pump(1600);
    bool second_bump_fired = (arm_count == 2) && !ps->void_state.armed;

    // 3. Re-store the SAME value (2 -> 2): no change, so no third arm.
    engine->pulsar_anomaly_request.store(2, std::memory_order_release);
    pump(400);
    bool same_value_did_not_refire = (arm_count == 2);

    bool ok = stale_did_not_fire && first_bump_fired && second_bump_fired
               && same_value_did_not_refire;
    printf("  arm_count=%d stale_ok=%d first_ok=%d second_ok=%d repeat_ok=%d -- %s\n",
           arm_count, stale_did_not_fire, first_bump_fired, second_bump_fired,
           same_value_did_not_refire, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Integration proof of the ghost mechanism itself: with a strong ghost_intensity
// and a 2-bar floor on each side (framing the ghost bar in near-silence), the
// single ghost bar must be audibly LOUD against a baseline, while the floor
// surrounding it stays deeply suppressed against that same baseline. Measurements
// are phase-anchored to the void state's own schedule fields (floor1_end/ghost_end/
// floor2_end), not a fixed block index, for the same reason as
// test_void_dips_and_recovers: the per-step note envelope makes raw RMS noisy
// within a bar.
//
// Both seeds are pinned (pattern RNG via pulsar_seed, and stmlib's global noise
// RNG via Random::Seed) so the measured RMS trace is fully reproducible, matching
// the sibling manual-trigger/edge-detect tests in this file.
//
// The assertion is two-sided on purpose: ghost_max > baseline*0.2 alone could be
// satisfied by a floor that never really suppresses (floor_min close to baseline),
// and floor_min < baseline*0.1 alone says nothing about the ghost lifting back up.
// Together they prove the ghost un-suppresses note-ons AND lifts gain for exactly
// one bar against a floor that is otherwise near-silent. Sanity check: with
// ghost_intensity forced to 0 (no ghost bar at all — verified by temporarily
// editing this test during development), the arc has no ghost window, has_ghost is
// false, and ghost_max stays at its initial 0.0f, so ghost_loud is FALSE and the
// test correctly fails — proving this isn't a trivially-true assertion.
static bool test_void_ghost_bump_in_render() {
    printf("\n=== Test: ghost bar produces an audible bump against a suppressed floor ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    push_two_section_ab_arrangement(engine, /*bars_per_section=*/8);

    // Manual-only (probability 0 disables auto-fire): 2-bar floor on each side of a
    // strong ghost bar, so the ghost is framed by near-silence in both directions.
    engine->pulsar_void_data[0].store(0.0f, std::memory_order_relaxed);  // probability (manual only)
    engine->pulsar_void_data[1].store(0.03f, std::memory_order_relaxed); // floor
    engine->pulsar_void_data[2].store(1.0f, std::memory_order_relaxed);  // ramp down (1 bar)
    engine->pulsar_void_data[3].store(2.0f, std::memory_order_relaxed);  // floor min (2 bars)
    engine->pulsar_void_data[4].store(2.0f, std::memory_order_relaxed);  // floor max (2 bars)
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);  // ramp up (1 bar)
    engine->pulsar_void_data[6].store(0.9f, std::memory_order_relaxed); // strong ghost
    engine->pulsar_void_data[7].store(1.0f, std::memory_order_relaxed);  // declared

    // Pin BOTH RNGs before trigger_vibe_load, per this file's anti-flake convention.
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    // Full-gain BASELINE: max RMS during warm-up, before the void has armed
    // (gain == 1.0 the whole time), same pattern as the manual-trigger test above.
    float baseline = 0.0f;
    for (int i = 0; i < 60; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        baseline = std::fmax(baseline, compute_rms(engine->pulsar_out_l, 512));
    }
    PulsarState* ps = engine->pulsar_state;   // valid: at least one block already rendered
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    // The manual arm is only picked up on track 0's next loop wrap (bar boundary),
    // and cursor/start_step/pos are all in STEPS, advancing ~512/6000 = 0.085
    // steps/block at 120bpm/48kHz. Reaching floor2_end (pos=64, i.e. cursor ~80
    // steps from section entry) needs roughly 80/0.085 ~= 940 blocks; use 1200
    // for margin (tempo drift can perturb samples_per_step slightly).
    float floor_min = 1e9f, ghost_max = 0.0f;
    for (int i = 0; i < 1200; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (!ps || !ps->void_state.armed) continue;
        float rms = compute_rms(engine->pulsar_out_l, 512);
        float pos = static_cast<float>(ps->void_state.cursor - ps->void_state.start_step);
        if (pos <= 0.0f || pos >= ps->void_state.ramp_up_end) continue;
        bool in_ghost = ps->void_state.has_ghost &&
                        pos >= ps->void_state.floor1_end && pos < ps->void_state.ghost_end;
        bool in_floor = (pos >= ps->void_state.ramp_down_end && pos < ps->void_state.floor1_end) ||
                        (pos >= ps->void_state.ghost_end && pos < ps->void_state.floor2_end);
        if (in_ghost) ghost_max = std::fmax(ghost_max, rms);
        if (in_floor) floor_min = std::fmin(floor_min, rms);
    }
    bool ghost_loud = ghost_max > baseline * 0.2f;    // ghost un-suppresses + lifts gain
    bool floor_quiet = floor_min < baseline * 0.1f;   // floor stays deeply suppressed
    bool ok = baseline > 1e-4f && ghost_loud && floor_quiet;
    printf("  baseline=%.4f floor_min=%.4f ghost_max=%.4f ghost_loud=%d floor_quiet=%d -- %s\n",
           baseline, floor_min, ghost_max, ghost_loud, floor_quiet, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Click-free proof: the FINAL applied void gain (VoidAnomaly::gain_smoothed) must
// never move by more than the ~5ms slew's per-sample max-delta, bounded per BLOCK as
// void_gain_max_step_per_sample(sample_rate) * block_frames (with a 5% margin for
// float rounding across accumulated per-sample clamps). Uses the SAME shape as
// test_void_ghost_bump_in_render (strong ghost_intensity=0.9, floor split 2 bars/side
// around the ghost) so the ghost bar's musical step -- the biggest raw discontinuity
// in the schedule -- is exercised. SMALL (64-frame) blocks make the per-block bound
// meaningful: at 512 frames a full 0->1 slew fits inside a single block, so ANY delta
// trivially satisfies the bound; at 64 frames a raw step (floor 0.05 -> ghost 0.9)
// blows past it by >3x, so only a genuine per-sample slew keeps every block's delta
// under the cap. Also phase-anchors to the ghost window itself (not just "while
// armed") and asserts the ghost still visibly RISES there, proving the slew hasn't
// flattened the musical shape into a non-event.
static bool test_void_gain_never_steps() {
    printf("\n=== Test: void gain never steps -- per-block delta bounded by the slew ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    push_two_section_ab_arrangement(engine, /*bars_per_section=*/8);

    // Manual-only (probability forced to 0, matching test_void_ghost_bump_in_render's
    // determinism), strong ghost framed by a 2-bar floor on each side.
    engine->pulsar_void_data[0].store(0.0f, std::memory_order_relaxed);  // probability (manual only)
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed); // floor
    engine->pulsar_void_data[2].store(1.0f, std::memory_order_relaxed);  // ramp down (1 bar)
    engine->pulsar_void_data[3].store(2.0f, std::memory_order_relaxed);  // floor min (2 bars)
    engine->pulsar_void_data[4].store(2.0f, std::memory_order_relaxed);  // floor max (2 bars)
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);  // ramp up (1 bar)
    engine->pulsar_void_data[6].store(0.9f, std::memory_order_relaxed); // strong ghost
    engine->pulsar_void_data[7].store(1.0f, std::memory_order_relaxed);  // declared

    // Pin BOTH RNGs per this file's anti-flake convention.
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    constexpr int kBlockFrames = 64;
    const float kMaxBlockDelta =
        void_gain_max_step_per_sample(48000.0f) * static_cast<float>(kBlockFrames) * 1.05f;

    // Warm up so pulsar_state exists, then fire the manual trigger.
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    float prev_gain = ps->void_state.gain_smoothed;
    bool no_step = true;
    float worst_delta = 0.0f;
    bool saw_armed = false;
    float ghost_max = 0.0f;
    // Arc is ramp_down(16) + floor1(16) + ghost(16) + floor2(16) + ramp_up(16) = 80
    // steps, plus up to 16 steps of arm delay -- ~96 steps minimum. At 120bpm/48kHz
    // (6000 samples/step) that's ~1650 64-frame blocks; 12000 is ~7x margin.
    for (int i = 0; i < 12000; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        float gain = ps->void_state.gain_smoothed;
        float delta = std::fabs(gain - prev_gain);
        if (delta > worst_delta) worst_delta = delta;
        if (delta > kMaxBlockDelta) no_step = false;
        prev_gain = gain;

        if (ps->void_state.armed) {
            saw_armed = true;
            double pos = ps->void_state.cursor - ps->void_state.start_step;
            bool in_ghost = ps->void_state.has_ghost &&
                            pos >= ps->void_state.floor1_end && pos < ps->void_state.ghost_end;
            if (in_ghost && gain > ghost_max) ghost_max = gain;
        }
    }
    bool ghost_rose = ghost_max > 0.5f;   // the ghost bump reached well above the floor
    bool ok = no_step && saw_armed && ghost_rose;
    printf("  worst_delta=%.4f (bound=%.4f) saw_armed=%d ghost_max=%.3f -- %s\n",
           worst_delta, kMaxBlockDelta, saw_armed, ghost_max, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Reset-mid-arc proof: a MANUAL void's arc can cross a section boundary (e.g. a short
// section under a long floor), which calls void_reset() while gain_smoothed is still
// deep in the duck. void_reset() intentionally leaves gain_smoothed untouched (see its
// header comment in pulsar_void.h) so the idle path glides it back to 1.0 instead of
// snapping -- this test proves that glide never violates the same per-block delta
// bound as test_void_gain_never_steps, across the reset transition itself.
//
// Timing is engineered to be robust WITHOUT hand-deriving track-0's exact loop length
// (it varies with the generated pattern -- see reference_pulsar_bar_units.md):
// bars_min==bars_max==3 gives exactly 2 "safe" track-0 loop-wraps (bars_remaining
// 3->2->1, no flip) before the 3rd wrap flips the section, and the manual void is
// armed on the very first of those safe wraps (fired immediately after warmup, before
// section 0 has taken any wraps at all -- arm_void_manual and advance_section share
// the same track-0-wrap gate, so the arm always lands on wrap 1). A very short
// ramp_down (0.25 bar = 4 steps) and a very long floor (100 bars) guarantee the arc
// has cleared its ramp and is sitting deep in the floor well within those 2 safe
// wraps, for any plausible track-0 loop length -- so the 3rd wrap's boundary always
// lands mid-floor. The test polls live state and stops as soon as the scenario has
// been fully exercised, rather than hard-coding a block count, so it self-adjusts to
// whatever the actual loop length turns out to be.
static bool test_void_reset_glides_not_snaps() {
    printf("\n=== Test: void_reset glides gain back to 1.0, never steps ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    push_two_section_ab_arrangement(engine, /*bars_per_section=*/3);

    // Manual-only (probability 0, so arm_void_auto never re-arms after the reset).
    // Very short ramp-down + very long floor: guarantees the arc is deep in the floor
    // by the section's 3rd (flipping) track-0 wrap -- see header comment above.
    engine->pulsar_void_data[0].store(0.0f, std::memory_order_relaxed);   // probability (manual only)
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed);  // floor
    engine->pulsar_void_data[2].store(0.25f, std::memory_order_relaxed);  // ramp down (4 steps)
    engine->pulsar_void_data[3].store(100.0f, std::memory_order_relaxed); // floor min (100 bars)
    engine->pulsar_void_data[4].store(100.0f, std::memory_order_relaxed); // floor max (== min)
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);   // ramp up (unreachable here)
    engine->pulsar_void_data[6].store(0.0f, std::memory_order_relaxed);   // no ghost (isolate the reset)
    engine->pulsar_void_data[7].store(1.0f, std::memory_order_relaxed);   // declared

    // Pin BOTH RNGs per this file's anti-flake convention.
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    constexpr int kBlockFrames = 64;
    const float kMaxBlockDelta =
        void_gain_max_step_per_sample(48000.0f) * static_cast<float>(kBlockFrames) * 1.05f;

    // Warm up so pulsar_state exists, then fire the manual trigger immediately --
    // section 0 has not yet taken any track-0 wrap, so the arm lands on the first of
    // the 2 "safe" wraps (see header comment above).
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    float prev_gain = ps->void_state.gain_smoothed;
    bool no_step = true;
    float worst_delta = 0.0f;
    bool reached_floor = false;
    bool prev_armed = false;
    bool reset_while_armed = false;
    float pre_reset_gain = -1.0f;
    bool recovered = false;
    int blocks_since_reset = -1;

    // Generous safety-net cap: even a large (e.g. 64-step) track-0 loop needs only
    // ~3*64 = 192 steps (~18000 64-frame blocks at 120bpm/48kHz) to reach the 3rd
    // wrap; the loop breaks out early once the scenario is fully exercised.
    for (int i = 0; i < 60000; i++) {
        int prev_section = ps->section_state.current_section;
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);

        float gain = ps->void_state.gain_smoothed;
        float delta = std::fabs(gain - prev_gain);
        if (delta > worst_delta) worst_delta = delta;
        if (delta > kMaxBlockDelta) no_step = false;

        if (ps->void_state.armed && ps->void_state.suppress_note_ons) reached_floor = true;

        bool section_flipped = ps->section_state.current_section != prev_section;
        if (section_flipped && prev_armed && !reset_while_armed) {
            reset_while_armed = true;
            pre_reset_gain = prev_gain;   // gain the block BEFORE the flip -- proves mid-duck
            blocks_since_reset = 0;
        } else if (reset_while_armed && blocks_since_reset >= 0) {
            blocks_since_reset++;
            if (gain > 0.98f) recovered = true;
        }

        prev_armed = ps->void_state.armed;
        prev_gain = gain;

        if (reset_while_armed && recovered) break;   // scenario fully exercised
    }

    bool mid_duck = pre_reset_gain >= 0.0f && pre_reset_gain < 0.5f;
    bool ok = no_step && reached_floor && reset_while_armed && mid_duck && recovered;
    printf("  worst_delta=%.4f (bound=%.4f) reached_floor=%d reset_while_armed=%d "
           "pre_reset_gain=%.4f recovered=%d -- %s\n",
           worst_delta, kMaxBlockDelta, reached_floor, reset_while_armed,
           pre_reset_gain, recovered, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Pause-freeze proof: the !playing / muted early return in unit_process_pulsar sits
// ABOVE the void-gain pre-pass and its viz write, so without a fix a pause mid-duck
// simply stops writing the ring -- the Kotlin glow keeps the ring's last sample,
// freezing the VIBE-dropdown tint at mid-purple for the whole pause. The fix has the
// early return write 1.0 to the ring AND snap gain_smoothed to 1.0: a pause is an
// audio gap, so the snap is inaudible, and on resume the pre-pass slew re-glides
// down to the still-armed arc's target under the same per-block bound as the other
// slew tests -- the snap can't click on either edge. This test drives exactly that:
// manual void deep in its floor, pause one block (ring's latest sample must be 1.0,
// gain_smoothed must be exactly 1.0), then resume and assert the re-glide obeys the
// bound and actually re-descends toward the floor.
static bool test_void_pause_resets_glow_to_idle() {
    printf("\n=== Test: pause resets the void glow to idle; resume re-slews down ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    // Long sections: no boundary (and thus no void_reset) lands inside the window.
    push_two_section_ab_arrangement(engine, /*bars_per_section=*/64);

    // Manual-only, fast ramp-down, very long floor: the pause always lands mid-floor.
    engine->pulsar_void_data[0].store(0.0f, std::memory_order_relaxed);   // probability (manual only)
    engine->pulsar_void_data[1].store(0.05f, std::memory_order_relaxed);  // floor
    engine->pulsar_void_data[2].store(0.25f, std::memory_order_relaxed);  // ramp down (4 steps)
    engine->pulsar_void_data[3].store(100.0f, std::memory_order_relaxed); // floor min (100 bars)
    engine->pulsar_void_data[4].store(100.0f, std::memory_order_relaxed); // floor max (== min)
    engine->pulsar_void_data[5].store(1.0f, std::memory_order_relaxed);   // ramp up (unreachable)
    engine->pulsar_void_data[6].store(0.0f, std::memory_order_relaxed);   // no ghost
    engine->pulsar_void_data[7].store(1.0f, std::memory_order_relaxed);   // declared

    // Pin BOTH RNGs per this file's anti-flake convention.
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    constexpr int kBlockFrames = 64;
    const float kMaxBlockDelta =
        void_gain_max_step_per_sample(48000.0f) * static_cast<float>(kBlockFrames) * 1.05f;

    // Warm up so pulsar_state exists, then fire the manual trigger.
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    // Run deep into the floor (the arm waits for the next musical bar, so allow a
    // generous cap; break as soon as we're clearly mid-duck).
    bool reached_floor = false;
    for (int i = 0; i < 20000; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        if (ps->void_state.armed && ps->void_state.suppress_note_ons
            && ps->void_state.gain_smoothed < 0.1f) { reached_floor = true; break; }
    }

    // Drain the ring so the next read isolates the paused block's write.
    float viz_buf[480];
    int viz_read_pos = 0;
    orpheus_engine_get_viz(engine, VIZ_PULSAR_VOID_GAIN, viz_buf, 480, &viz_read_pos);

    // Pause mid-duck and process one block through the early return.
    engine->pulsar_playing.store(0, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    int vc = orpheus_engine_get_viz(engine, VIZ_PULSAR_VOID_GAIN, viz_buf, 480, &viz_read_pos);
    float paused_viz = vc >= 1 ? viz_buf[vc - 1] : -1.0f;   // -1 sentinel: nothing written
    float paused_gain = ps->void_state.gain_smoothed;
    bool paused_viz_idle = (vc >= 1) && vclose(paused_viz, 1.0f);
    bool paused_gain_idle = (paused_gain == 1.0f);   // exact: the fix assigns the literal

    // Resume: the arc is still armed and still mid-floor (the clock froze with the
    // pause), so the slew must re-glide DOWN under the same per-block bound.
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    float prev_gain = ps->void_state.gain_smoothed;
    bool no_step = true;
    float worst_delta = 0.0f;
    for (int i = 0; i < 40; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        float gain = ps->void_state.gain_smoothed;
        float delta = std::fabs(gain - prev_gain);
        if (delta > worst_delta) worst_delta = delta;
        if (delta > kMaxBlockDelta) no_step = false;
        prev_gain = gain;
    }
    bool redescended = prev_gain < 0.1f;

    bool ok = reached_floor && paused_viz_idle && paused_gain_idle && no_step && redescended;
    printf("  reached_floor=%d paused_viz=%.4f (vc=%d) paused_gain=%.4f "
           "worst_delta=%.4f (bound=%.4f) redescended=%d -- %s\n",
           reached_floor, paused_viz, vc, paused_gain,
           worst_delta, kMaxBlockDelta, redescended, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_void_tests() {
    printf("\n═══ Pulsar Void Anomaly ═══\n");
    int passed = 0, failed = 0;
    auto run = [&](bool (*fn)()) { if (fn()) passed++; else failed++; };
    run(test_void_ramps_are_gradual);
    run(test_void_suppression_windows);
    run(test_void_ghost_bump_and_disable);
    run(test_void_ramp_up_and_boundary);
    run(test_void_auto_arms_end_aligned);
    run(test_void_auto_room_gate);
    run(test_void_auto_disabled);
    run(test_void_manual_next_bar);
    run(test_void_draw_floor_no_rng_when_equal_bounds);
    run(test_void_config_unpacks_from_atomics);
    run(test_void_dips_and_recovers);
    run(test_void_manual_trigger_fires_with_zero_probability);
    run(test_void_manual_ignored_when_undeclared);
    run(test_void_anomaly_request_edge_detect);
    run(test_void_ghost_bump_in_render);
    run(test_void_gain_never_steps);
    run(test_void_reset_glides_not_snaps);
    run(test_void_pause_resets_glow_to_idle);
    printf("\n  Pulsar Void: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
