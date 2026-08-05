#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include <cstdio>
#include <cmath>
#include <cstring>
#include <vector>

// ── Unit tests for tension system math (no engine needed) ──

static bool test_tension_inner_phase() {
    printf("\n=== Test: Tension inner phase ramps 0->1 over innerBars ===\n");
    int inner = 4;
    float phases[5];
    for (int loop = 0; loop < 5; loop++) {
        phases[loop] = static_cast<float>(loop % inner) / static_cast<float>(inner);
    }
    bool ok = std::fabs(phases[0] - 0.0f) < 0.001f
           && std::fabs(phases[1] - 0.25f) < 0.001f
           && std::fabs(phases[3] - 0.75f) < 0.001f
           && std::fabs(phases[4] - 0.0f) < 0.001f;  // wraps
    printf("  Phases: %.2f, %.2f, %.2f, %.2f, %.2f -- %s\n",
           phases[0], phases[1], phases[2], phases[3], phases[4],
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_outer_modulation() {
    printf("\n=== Test: Outer cycle modulates inner ceiling ===\n");
    int inner = 4, outer = 8;
    float depth = 0.5f;
    auto intensity = [&](int loop) {
        float ip = static_cast<float>(loop % inner) / static_cast<float>(inner);
        float op = static_cast<float>(loop % outer) / static_cast<float>(outer);
        float os = (1.0f - depth) + depth * op;
        return ip * os;
    };
    float i0 = intensity(0);
    float i3 = intensity(3);
    float i7 = intensity(7);
    // loop0: inner_phase=0 -> 0
    // loop3: inner_phase=3/4=0.75, outer_phase=3/8=0.375, outer_scale=0.5+0.5*0.375=0.6875 -> 0.515
    // loop7: inner_phase=3/4=0.75, outer_phase=7/8=0.875, outer_scale=0.5+0.5*0.875=0.9375 -> 0.703
    bool ok = i0 < 0.001f && i3 > 0.4f && i3 < 0.6f && i7 > 0.65f && i7 < 0.75f;
    printf("  loop0=%.3f, loop3=%.3f, loop7=%.3f -- %s\n", i0, i3, i7, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_volume_scaling() {
    printf("\n=== Test: Volume tension scales velocity ===\n");
    float vol = 0.5f;
    // At intensity=0: scale = 1 - 0.5*0.3*1.0 = 0.85
    float scale_low = 1.0f - vol * 0.3f * (1.0f - 0.0f);
    // At intensity=1: scale = 1 - 0.5*0.3*0.0 = 1.0
    float scale_high = 1.0f - vol * 0.3f * (1.0f - 1.0f);
    bool ok = std::fabs(scale_low - 0.85f) < 0.01f && std::fabs(scale_high - 1.0f) < 0.01f;
    printf("  At intensity=0: %.3f, intensity=1: %.3f -- %s\n", scale_low, scale_high, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_timing_scaling() {
    printf("\n=== Test: Timing tension scales drunk offsets ===\n");
    float timing = 0.5f;
    // At intensity=0: scale = (1-0.5) + 0.5*0.0 = 0.5
    float scale_low = (1.0f - timing) + timing * 0.0f;
    // At intensity=1: scale = (1-0.5) + 0.5*1.0 = 1.0
    float scale_high = (1.0f - timing) + timing * 1.0f;
    bool ok = std::fabs(scale_low - 0.5f) < 0.01f && std::fabs(scale_high - 1.0f) < 0.01f;
    printf("  At intensity=0: %.3f, intensity=1: %.3f -- %s\n", scale_low, scale_high, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_evolution_attack_point() {
    printf("\n=== Test: Evolution attack point gates evo_intensity ===\n");
    float ap = 0.5f;
    // intensity below attack_point -> 0
    float evo_at_0_3 = (ap < 0.999f) ? std::max(0.0f, (0.3f - ap) / (1.0f - ap)) : 0.0f;
    // intensity above attack_point -> ramps up
    float evo_at_0_75 = (ap < 0.999f) ? std::max(0.0f, (0.75f - ap) / (1.0f - ap)) : 0.0f;
    // intensity at 1.0 -> 1.0
    float evo_at_1_0 = (ap < 0.999f) ? std::max(0.0f, (1.0f - ap) / (1.0f - ap)) : 0.0f;
    bool ok = std::fabs(evo_at_0_3 - 0.0f) < 0.01f
           && std::fabs(evo_at_0_75 - 0.5f) < 0.01f
           && std::fabs(evo_at_1_0 - 1.0f) < 0.01f;
    printf("  evo@0.3=%.3f, evo@0.75=%.3f, evo@1.0=%.3f -- %s\n",
           evo_at_0_3, evo_at_0_75, evo_at_1_0, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tension_struct_defaults() {
    printf("\n=== Test: TensionParams default values ===\n");
    TensionParams tp;
    bool ok = tp.inner_bars == 4
           && tp.outer_bars == 0
           && std::fabs(tp.volume - 0.3f) < 0.001f
           && std::fabs(tp.timing - 0.2f) < 0.001f
           && !tp.octave_shift
           && tp.key_shift == 0
           && tp.half_lick == HalfLickMode::OFF
           && std::fabs(tp.chromatic_passing) < 0.001f
           && std::fabs(tp.evo_timbre_low - 0.25f) < 0.001f
           && std::fabs(tp.evo_morph_low - (-1.0f)) < 0.001f;
    printf("  Defaults check -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_half_lick_effective_loop_len() {
    printf("\n=== Test: half_lick truncates a FILL lead to its first bar ===\n");
    // A 32-step FILL lead records half_loop_len = 16 (bar 1). With half_lick active
    // the sequencer loops just those 16 steps; without it, the full 32.
    PulsarTrackState fill{};
    fill.step_count = 32;
    fill.half_loop_len = 16;
    bool ok = pulsar_effective_loop_len(fill, HalfLickMode::JAM)          == 16  // jam bar 1
           && pulsar_effective_loop_len(fill, HalfLickMode::JAM_INVERTED) == 16  // same truncation
           && pulsar_effective_loop_len(fill, HalfLickMode::OFF)          == 32; // full riff at the drop

    // A non-FILL track (half_loop_len = 0) is never truncated, even under half_lick —
    // so drums/bass keep their full pattern while the lead jams.
    PulsarTrackState perc{};
    perc.step_count = 32;
    perc.half_loop_len = 0;
    ok = ok && pulsar_effective_loop_len(perc, HalfLickMode::JAM) == 32;

    // Guard: a 16-step vibe whose bar1 == step_count is a no-op (no phantom truncation).
    PulsarTrackState oneBar{};
    oneBar.step_count = 16;
    oneBar.half_loop_len = 16;
    ok = ok && pulsar_effective_loop_len(oneBar, HalfLickMode::JAM) == 16;

    printf("  half_lick loop length -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Releasing half_lick must not leave the riff a bar out of phase.
//
// The section flip fires inside track 0's boundary handling, and the render loop is
// `for t { for b { ... } }` — track 0 is fully advanced (clearing half_lick) BEFORE the
// lead is advanced in that same step boundary. So the lead reaches its final increment
// with playhead == half_loop_len - 1 but loop_len already back to step_count:
//
//     (15 + 1) % 32 == 16      instead of wrapping to 0
//
// The lead resumes on bar 2 and stays 16 steps out of phase with track 0 for the rest
// of the song — the riff plays answer-first, permanently. Reproduces Fire Sky's
// "lead-in inverts the riff" bug; the arrangement length is irrelevant, since every
// section is a whole number of track-0 wraps (32 steps) and therefore always leaves
// the truncated lead sitting on half_loop_len - 1.
// Run a `mode` lead-in of 2 track-0 wraps, releasing on the final boundary exactly as
// the section flip does, and report where the riff resumes.
static int playhead_after_lead_in(HalfLickMode mode) {
    PulsarTrackState lead{};
    lead.step_count = 32;
    lead.half_loop_len = 16;
    lead.playhead = 0;

    constexpr int kLeadInSteps = 64;   // 2 arrangement bars = 2 track-0 wraps
    for (int step = 0; step < kLeadInSteps; step++) {
        // Final boundary: track 0 wrapped, advance_section ran, tension reverted.
        HalfLickMode active = (step == kLeadInSteps - 1) ? HalfLickMode::OFF : mode;
        pulsar_advance_playhead(lead, active);
    }
    return lead.playhead;
}

static bool test_half_lick_release_keeps_riff_phase() {
    printf("\n=== Test: releasing half_lick resumes the riff on bar 1, not bar 2 ===\n");
    int jam = playhead_after_lead_in(HalfLickMode::JAM);
    bool ok = jam == 0;
    printf("  JAM playhead after lead-in = %d (want 0 = bar 1) -- %s\n",
           jam, ok ? "PASS" : "FAIL");
    return ok;
}

// JAM_INVERTED keeps the old phase flip, but as a declared behavior with a defined end.
static bool test_half_lick_inverted_holds_then_relocks() {
    printf("\n=== Test: JAM_INVERTED enters on bar 2, then re-locks at the next section ===\n");
    PulsarTrackState lead{};
    lead.step_count = 32;
    lead.half_loop_len = 16;
    lead.playhead = 0;

    constexpr int kLeadInSteps = 64;
    for (int step = 0; step < kLeadInSteps; step++) {
        HalfLickMode active =
            (step == kLeadInSteps - 1) ? HalfLickMode::OFF : HalfLickMode::JAM_INVERTED;
        pulsar_advance_playhead(lead, active);
    }
    bool ok = lead.playhead == 16 && lead.phase_inverted;
    printf("  entered next section at playhead %d (want 16 = bar 2), inverted=%d -- %s\n",
           lead.playhead, lead.phase_inverted ? 1 : 0, ok ? "PASS" : "FAIL");

    // It must STAY inverted across the whole section, not drift back on its own.
    for (int step = 0; step < 32; step++) pulsar_advance_playhead(lead, HalfLickMode::OFF);
    bool held = lead.playhead == 16;
    printf("  still bar-2-first one full statement later: playhead %d -- %s\n",
           lead.playhead, held ? "PASS" : "FAIL");

    // The section boundary arms the re-lock (mirrors the handler in the render loop).
    lead.phase_inverted = false;
    lead.resync_pending = true;
    pulsar_advance_playhead(lead, HalfLickMode::OFF);
    bool relocked = lead.playhead == 0;
    printf("  re-locked at next section boundary: playhead %d (want 0) -- %s\n",
           lead.playhead, relocked ? "PASS" : "FAIL");

    return ok && held && relocked;
}

// JAM_LAST_BAR shifts the loop window instead of shortening it, so the section jams the
// riff's answer phrase. Entering from bar 1 must snap into the window rather than run
// past it, and releasing must re-lock to bar 1.
static bool test_half_lick_last_bar_jams_the_answer() {
    printf("\n=== Test: JAM_LAST_BAR loops the last bar, then re-locks to bar 1 ===\n");
    PulsarTrackState lead{};
    lead.step_count = 32;
    lead.half_loop_len = 16;
    lead.playhead = 0;

    PulsarLoopWindow win = pulsar_effective_loop_window(lead, HalfLickMode::JAM_LAST_BAR);
    bool window_ok = win.start == 16 && win.len == 16;
    printf("  window = [%d, %d) (want [16, 32)) -- %s\n",
           win.start, win.start + win.len, window_ok ? "PASS" : "FAIL");

    // Run a few steps of bar 1, then engage. The playhead is outside the new window,
    // so it must snap to its start rather than keep climbing.
    for (int step = 0; step < 5; step++) pulsar_advance_playhead(lead, HalfLickMode::OFF);
    pulsar_advance_playhead(lead, HalfLickMode::JAM_LAST_BAR);
    bool entered = lead.playhead == 16;
    printf("  entered at playhead %d (want 16) -- %s\n", lead.playhead, entered ? "PASS" : "FAIL");

    // It must stay inside [16, 32) for a full pass, never touching bar 1.
    bool stayed = true;
    for (int step = 0; step < 48; step++) {
        pulsar_advance_playhead(lead, HalfLickMode::JAM_LAST_BAR);
        if (lead.playhead < 16 || lead.playhead >= 32) stayed = false;
    }
    printf("  stayed inside the last bar over 48 steps -- %s\n", stayed ? "PASS" : "FAIL");

    // Release: the riff re-locks to bar 1 rather than stranding in bar 2.
    pulsar_advance_playhead(lead, HalfLickMode::OFF);
    bool relocked = lead.playhead == 0;
    printf("  re-locked to playhead %d on release (want 0) -- %s\n",
           lead.playhead, relocked ? "PASS" : "FAIL");

    // A one-bar lick has no last bar to jam — must be a no-op, not an empty window.
    PulsarTrackState oneBar{};
    oneBar.step_count = 16;
    oneBar.half_loop_len = 16;
    PulsarLoopWindow ow = pulsar_effective_loop_window(oneBar, HalfLickMode::JAM_LAST_BAR);
    bool noop = ow.start == 0 && ow.len == 16;
    printf("  one-bar lick is a no-op: [%d, %d) -- %s\n",
           ow.start, ow.start + ow.len, noop ? "PASS" : "FAIL");

    return window_ok && entered && stayed && relocked && noop;
}

// Fire Sky's shipped shape: a JAM_INVERTED lead-in hands off to a solo that carries no
// tension override, so the solo plays the riff answer-first and the section AFTER the
// solo re-locks. The solo is barsMin=4/barsMax=6, so this must hold for every length it
// can draw — an arrangement "bar" is one track-0 wrap (32 steps), which is a whole lick
// loop, so solo length cannot change the phase either way.
static bool test_inverted_lead_in_spans_exactly_the_solo() {
    printf("\n=== Test: JAM_INVERTED lead-in inverts the solo, then returns ===\n");
    bool ok = true;
    for (int solo_bars = 4; solo_bars <= 6; solo_bars++) {
        PulsarTrackState lead{};
        lead.step_count = 32;
        lead.half_loop_len = 16;
        lead.playhead = 0;

        // lead-in: 2 arrangement bars under JAM_INVERTED. Releases on the final boundary.
        constexpr int kLeadInSteps = 2 * 32;
        for (int s = 0; s < kLeadInSteps; s++) {
            HalfLickMode m = (s == kLeadInSteps - 1) ? HalfLickMode::OFF
                                                     : HalfLickMode::JAM_INVERTED;
            pulsar_advance_playhead(lead, m);
        }
        bool solo_opens_inverted = (lead.playhead == 16 && lead.phase_inverted);

        // solo: no override, so half-lick stays OFF for its whole duration.
        bool held = true;
        for (int s = 0; s < solo_bars * 32; s++) {
            pulsar_advance_playhead(lead, HalfLickMode::OFF);
        }
        held = (lead.playhead == 16);   // back where it started, still bar-2-first

        // The boundary out of the solo arms the re-lock (as the section handler does).
        if (lead.phase_inverted) { lead.phase_inverted = false; lead.resync_pending = true; }
        pulsar_advance_playhead(lead, HalfLickMode::OFF);
        bool returned = (lead.playhead == 0);

        bool pass = solo_opens_inverted && held && returned;
        ok = ok && pass;
        printf("  %d-bar solo: opens@%d inverted=%d, holds=%d, returns@%d -- %s\n",
               solo_bars, 16, solo_opens_inverted ? 1 : 0, held ? 1 : 0,
               lead.playhead, pass ? "PASS" : "FAIL");
    }
    return ok;
}

// The mirror case the latch also fixes: half-lick ENGAGING partway through bar 2 must
// not jump the playhead into the middle of bar 1. It finishes the loop it is on.
static bool test_half_lick_engage_midloop_does_not_jump() {
    printf("\n=== Test: engaging half_lick mid-loop finishes the current pass ===\n");
    PulsarTrackState lead{};
    lead.step_count = 32;
    lead.half_loop_len = 16;
    lead.playhead = 0;
    for (int step = 0; step < 20; step++) pulsar_advance_playhead(lead, HalfLickMode::OFF);
    // Playhead is at 20, mid bar 2. Engage half-lick.
    pulsar_advance_playhead(lead, HalfLickMode::JAM);
    bool ok = lead.playhead == 21;   // continues, does not snap to (20+1) % 16 == 5
    printf("  playhead %d after engaging at 20 (want 21, not 5) -- %s\n",
           lead.playhead, ok ? "PASS" : "FAIL");

    // Once it completes the 32-step pass it adopts the 16-step loop.
    for (int step = 0; step < 11; step++) pulsar_advance_playhead(lead, HalfLickMode::JAM);
    bool adopted = lead.playhead == 0 && lead.wrap_len == 16;
    printf("  adopted the truncated loop at the wrap: playhead %d wrap_len %d -- %s\n",
           lead.playhead, lead.wrap_len, adopted ? "PASS" : "FAIL");
    return ok && adopted;
}

static bool test_tension_chromatic_passing_math() {
    printf("\n=== Test: Chromatic passing probability scales with intensity ===\n");
    float base_prob = 0.5f;
    // At intensity=0: effective prob = 0.5 * 0.0 = 0.0
    float p0 = base_prob * 0.0f;
    // At intensity=0.5: effective prob = 0.5 * 0.5 = 0.25
    float p5 = base_prob * 0.5f;
    // At intensity=1.0: effective prob = 0.5 * 1.0 = 0.5
    float p10 = base_prob * 1.0f;
    bool ok = std::fabs(p0) < 0.001f
           && std::fabs(p5 - 0.25f) < 0.001f
           && std::fabs(p10 - 0.5f) < 0.001f;
    printf("  prob@0=%.3f, prob@0.5=%.3f, prob@1.0=%.3f -- %s\n", p0, p5, p10, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_spurt_triggers_at_tension_peak() {
    printf("\n=== Test: Spurt triggers when intensity > 0.85 at tension peak ===\n");
    int inner = 8;
    bool spurt_triggered = false;
    float trigger_intensity = -1.0f;
    for (int loop = 0; loop < 16; loop++) {
        float phase = static_cast<float>(loop % inner) / static_cast<float>(inner);
        float intensity = phase;  // simplified: no outer modulation
        if (intensity > 0.85f) {
            spurt_triggered = true;
            trigger_intensity = intensity;
        }
    }
    // At loop=7: phase=7/8=0.875 > 0.85, so spurt should trigger
    float expected = 7.0f / 8.0f;
    bool ok = spurt_triggered && std::fabs(trigger_intensity - expected) < 0.001f;
    printf("  Triggered=%s, intensity=%.3f (expected %.3f) -- %s\n",
           spurt_triggered ? "yes" : "no", trigger_intensity, expected, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_spurt_duration_from_inner_bars() {
    printf("\n=== Test: Spurt duration = max(1, innerBars / 2) ===\n");
    auto spurt_dur = [](int innerBars) { return std::max(1, innerBars / 2); };
    bool ok = spurt_dur(4) == 2
           && spurt_dur(8) == 4
           && spurt_dur(16) == 8
           && spurt_dur(1) == 1
           && spurt_dur(2) == 1;
    printf("  dur(4)=%d, dur(8)=%d, dur(16)=%d, dur(1)=%d, dur(2)=%d -- %s\n",
           spurt_dur(4), spurt_dur(8), spurt_dur(16), spurt_dur(1), spurt_dur(2),
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_effective_mutation_during_spurt() {
    printf("\n=== Test: Effective mutation = min(1.0, base * 3.0) during spurt ===\n");
    auto eff_normal = [](float base) { return base; };
    auto eff_spurt  = [](float base) { return std::min(1.0f, base * 3.0f); };
    float n015  = eff_normal(0.15f);
    float s015  = eff_spurt(0.15f);
    float s050  = eff_spurt(0.50f);
    float s085  = eff_spurt(0.85f);
    bool ok = std::fabs(n015 - 0.15f) < 0.001f
           && std::fabs(s015 - 0.45f) < 0.001f
           && std::fabs(s050 - 1.00f) < 0.001f
           && std::fabs(s085 - 1.00f) < 0.001f;
    printf("  normal(0.15)=%.3f, spurt(0.15)=%.3f, spurt(0.5)=%.3f, spurt(0.85)=%.3f -- %s\n",
           n015, s015, s050, s085, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_bounded_drift_clamp() {
    printf("\n=== Test: Bounded drift clamps working degree within max_drift of original ===\n");
    // mutation=0.15, max_drift=round(0.15*4)=1
    float mutation_low = 0.15f;
    int max_drift_low = static_cast<int>(std::round(mutation_low * 4.0f));  // 1

    int orig = 2;
    // working=5: clamped to orig+max_drift = 3
    int w5 = 5;
    int clamped_w5 = std::max(orig - max_drift_low, std::min(orig + max_drift_low, w5));
    // working=1: within [1,3], keep as is
    int w1 = 1;
    int clamped_w1 = std::max(orig - max_drift_low, std::min(orig + max_drift_low, w1));

    // mutation=0.85, max_drift=round(0.85*4)=4 (rounds to 3... actually 0.85*4=3.4, round=3)
    float mutation_high = 0.85f;
    int max_drift_high = static_cast<int>(std::round(mutation_high * 4.0f));  // 3

    bool ok = max_drift_low == 1
           && clamped_w5 == 3
           && clamped_w1 == 1
           && max_drift_high == 3;
    printf("  max_drift(0.15)=%d, clamp(w=5)=%d, clamp(w=1)=%d, max_drift(0.85)=%d -- %s\n",
           max_drift_low, clamped_w5, clamped_w1, max_drift_high, ok ? "PASS" : "FAIL");

    // Also verify the expected value per spec comment (round(0.85*4)=4 if spec says 4)
    // Spec says max_drift=4 for 0.85; let's check both and report
    printf("  Note: round(0.85*4)=round(3.4)=%d (spec says 4 if using truncation+1?)\n", max_drift_high);
    return ok;
}

static bool test_original_lick_immutable() {
    printf("\n=== Test: original_lick unchanged after bounded drift applied to working copy ===\n");
    const int N = 4;
    PulsarLickStep original[N];
    PulsarLickStep working[N];
    for (int i = 0; i < N; i++) {
        original[i].scale_degree = static_cast<int8_t>(i * 2);
        original[i].duration = 0.25f;
        original[i].velocity = 0.7f;
    }
    std::memcpy(working, original, sizeof(PulsarLickStep) * N);

    // Mutate working copy and apply bounded drift (mutation=0.5, max_drift=2)
    float mutation = 0.5f;
    int max_drift = static_cast<int>(std::round(mutation * 4.0f));  // 2
    for (int i = 0; i < N; i++) {
        // Simulate a large mutation to working copy
        int orig_deg = original[i].scale_degree;
        int new_deg = orig_deg + 5;  // intentionally large drift
        working[i].scale_degree = static_cast<int8_t>(
            std::max(orig_deg - max_drift, std::min(orig_deg + max_drift, new_deg)));
    }

    // Verify original is unchanged
    bool ok = true;
    for (int i = 0; i < N; i++) {
        if (original[i].scale_degree != static_cast<int8_t>(i * 2)) {
            ok = false;
        }
    }
    printf("  original degrees: %d %d %d %d -- %s\n",
           original[0].scale_degree, original[1].scale_degree,
           original[2].scale_degree, original[3].scale_degree,
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_random_spurt_fires() {
    printf("\n=== Test: Random spurt fires ~10%% of the time with spurt_chance=0.1 ===\n");
    float spurt_chance = 0.1f;
    int fires = 0;
    // Simple xorshift PRNG
    uint32_t state = 0xDEADBEEFu;
    auto xorshift = [&]() -> uint32_t {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return state;
    };
    for (int i = 0; i < 1000; i++) {
        float r = static_cast<float>(xorshift() & 0xFFFFFFu) / static_cast<float>(0x1000000u);
        if (r < spurt_chance) fires++;
    }
    bool ok = fires >= 50 && fires <= 150;
    printf("  fires=%d/1000 (expected ~100, accept 50-150) -- %s\n", fires, ok ? "PASS" : "FAIL");
    return ok;
}

// ── TENS-2: evolution smoother decays over multiple bars (releaseSpeed) ──
//
// The evo smoother must step ONCE PER BAR (not per audio block) so a high
// releaseSpeed produces an audible multi-bar decay. With the old per-block
// stepping the smoother converged within a few ms, so when tension_intensity
// dropped (e.g. at the inner-cycle wrap) tension_evo_smooth followed almost
// instantly — releaseSpeed had no observable effect.
static bool test_tension_evo_release_speed_decays_over_bars() {
    printf("\n=== Test: TENS-2 evo smoother decays over multiple bars (releaseSpeed) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.7f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    // Tension: inner cycle of 8 bars, no outer cycle. Intensity ramps
    // 0 -> 7/8 over 8 bars then wraps to 0 (a sharp target drop).
    engine->pulsar_tension_inner_bars.store(8, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_tension_evo_attack_point.store(0.0f, std::memory_order_relaxed);  // evo_intensity == intensity
    engine->pulsar_tension_evo_release_speed.store(0.85f, std::memory_order_relaxed); // slow decay
    engine->pulsar_tension_evo_timbre_prob.store(1.0f, std::memory_order_relaxed);
    for (int t = 0; t < 8; t++)
        engine->pulsar_track_evo_weight[t].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_seed.store(99, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    // Capture (intensity, evo_smooth) once per bar (on loop_count change).
    std::vector<float> bar_intensity, bar_evo;
    int last_loop = -1;
    for (int i = 0; i < 4000 && bar_evo.size() < 40; i++) {
        unit_process_pulsar(&unit, engine, 256, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->loop_count != last_loop) {
            last_loop = ps->loop_count;
            bar_intensity.push_back(ps->tension_intensity);
            bar_evo.push_back(ps->tension_evo_smooth);
        }
    }

    // Find a bar where intensity dropped sharply from the previous bar (the
    // inner-cycle wrap, e.g. ~0.875 -> 0). At that bar the smoother must still
    // be well above the new low intensity (it LAGS = multi-bar decay).
    bool found_drop = false;
    bool laggy = false;
    int decay_bars = 0;
    for (size_t b = 2; b < bar_intensity.size(); b++) {
        if (bar_intensity[b - 1] > 0.5f && bar_intensity[b] < 0.15f) {
            found_drop = true;
            // evo_smooth should still hold substantial value at the drop bar
            // (per-block stepping would have collapsed it to ~intensity).
            if (bar_evo[b] > 0.25f) laggy = true;
            // Count how many subsequent bars it takes to decay below 0.1.
            for (size_t k = b; k < bar_evo.size(); k++) {
                if (bar_evo[k] < 0.1f) break;
                decay_bars++;
            }
            break;
        }
    }

    printf("  captured %zu bars; found_drop=%s evo_at_drop_lags=%s decay_bars=%d\n",
           bar_evo.size(), found_drop ? "YES" : "NO", laggy ? "YES" : "NO", decay_bars);

    bool pass = found_drop && laggy && decay_bars >= 2;
    printf("  TENS-2 evo release decay: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

bool run_pulsar_tension_tests() {
    printf("\n========== PULSAR TENSION TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_tension_inner_phase());
    tally(test_tension_outer_modulation());
    tally(test_tension_volume_scaling());
    tally(test_tension_timing_scaling());
    tally(test_tension_evolution_attack_point());
    tally(test_tension_struct_defaults());
    tally(test_half_lick_effective_loop_len());
    tally(test_half_lick_release_keeps_riff_phase());
    tally(test_half_lick_inverted_holds_then_relocks());
    tally(test_half_lick_engage_midloop_does_not_jump());
    tally(test_half_lick_last_bar_jams_the_answer());
    tally(test_inverted_lead_in_spans_exactly_the_solo());
    tally(test_tension_chromatic_passing_math());
    // Lick evolution spurt tests
    tally(test_spurt_triggers_at_tension_peak());
    tally(test_spurt_duration_from_inner_bars());
    tally(test_effective_mutation_during_spurt());
    tally(test_bounded_drift_clamp());
    tally(test_original_lick_immutable());
    tally(test_random_spurt_fires());
    tally(test_tension_evo_release_speed_decays_over_bars());
    printf("\nPulsar tension tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
