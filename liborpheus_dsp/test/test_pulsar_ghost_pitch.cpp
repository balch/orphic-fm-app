// Pulsar ghost notes take their pitch from the pattern.
//
// Mutation switches empty steps on as quiet ghosts: once per loop-cycle in
// mutate_patterns(), and once per render in bar_strategy_mutate(). Generators clear
// empty steps with make_step(0, ...), so a ghost that kept its step's note played
// MIDI 0, and the render floor folded it up to the engine's note_min octave (C3 on
// OSC) whatever the key. A ghost must sound the pitch of the nearest preceding
// gated, non-ghost step instead, wrapping around the pattern.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_bar_strategy.h"
#include <cstdio>
#include <cstring>

namespace {

constexpr int kLeadTrack = 4;
constexpr int kPadTrack = 5;

// mutate_patterns() ghosts carry duration 0.2 and velocity 0.15-0.30; lick steps render at
// 1.0. Matching on this, not on PulsarStep::ghost, keeps the oracle independent of the flag.
bool has_ghost_signature(const PulsarStep& s) {
    return s.gate && s.duration == 0.2f && s.velocity < 0.301f;
}

int nearest_sounding_step(const PulsarTrackState& ts, int s) {
    for (int k = 1; k < ts.step_count; k++) {
        const int j = (s - k + ts.step_count) % ts.step_count;
        if (ts.steps[j].gate && !has_ghost_signature(ts.steps[j])) return j;
    }
    return -1;
}

GraphUnit make_pulsar_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

OrpheusEngine* make_ghost_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);  // root D, natural minor
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);      // every gated step fires
    engine->pulsar_complexity.store(0.8f, std::memory_order_relaxed);  // under the step-count mutation gate
    engine->pulsar_space.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    pin_pulsar_rngs(engine, 0x00C0FFEE);
    return engine;
}

// Runs whole bars: on_bar(ps) once per new loop-cycle, on_block(ps) after every block.
template <typename BarFn, typename BlockFn>
int run_bars(OrpheusEngine* engine, int bars, BarFn on_bar, BlockFn on_block) {
    GraphUnit unit = make_pulsar_unit();
    int last_loop = -1;
    int seen = 0;
    for (int i = 0; i < 400000 && seen < bars; i++) {
        unit_process_pulsar(&unit, engine, 128, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->loop_count != last_loop) {
            last_loop = ps->loop_count;
            seen++;
            on_bar(ps);
        }
        on_block(ps);
    }
    return seen;
}

}  // namespace

// ghost_pitch_source picks the nearest written step before a ghost, skips other ghosts,
// wraps past step 0, and finds nothing in a pattern where only ghosts sound.
static bool test_ghost_pitch_source_rule() {
    printf("\n=== Test: ghost_pitch_source picks the nearest preceding written step ===\n");

    PulsarStep steps[8];
    for (int i = 0; i < 8; i++) steps[i] = make_step(0, 0.0f, false, 0.0f);
    steps[2] = make_step(50, 0.8f, true, 1.0f);
    steps[5] = make_step(53, 0.8f, true, 1.0f);
    steps[6] = make_step(57, 0.2f, true, 0.2f);
    steps[6].ghost = true;

    bool ok = true;
    auto expect = [&](int s, int want, const char* what) {
        const int got = ghost_pitch_source(steps, 8, s);
        if (got != want) {
            printf("  FAIL %s: step %d -> %d, want %d\n", what, s, got, want);
            ok = false;
        }
    };
    expect(4, 2, "nearest preceding");
    expect(3, 2, "adjacent");
    expect(7, 5, "skips a ghost");
    expect(1, 5, "wraps past step 0");

    PulsarStep only_ghosts[8];
    for (int i = 0; i < 8; i++) only_ghosts[i] = make_step(0, 0.0f, false, 0.0f);
    only_ghosts[3] = make_step(50, 0.2f, true, 0.2f);
    only_ghosts[3].ghost = true;
    const int none = ghost_pitch_source(only_ghosts, 8, 5);
    if (none != -1) {
        printf("  FAIL only ghosts sound: got %d, want -1\n", none);
        ok = false;
    }

    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A Fill lick with a quarter rest after every note: steps 0-3, 8-11, 16-19 and 24-27
// sound D3, F3, A3 and Bb3 on OSC, and the rests between them collect ghosts.
static bool test_fill_lick_ghost_borrows_preceding_pitch() {
    printf("\n=== Test: Fill lick ghosts sound the nearest preceding written pitch ===\n");

    OrpheusEngine* engine = make_ghost_engine();
    const int8_t degrees[] = {0, -1, 2, -1, 4, -1, 5, -1};
    for (int i = 0; i < 8; i++) {
        engine->pulsar_lick[i].scale_degree = degrees[i];
        engine->pulsar_lick[i].duration = 1.0f;  // 4 sequencer slots
        engine->pulsar_lick[i].velocity = 0.8f;
        engine->pulsar_lick[i].glide_rate = -1.0f;
        engine->pulsar_lick[i].hit_probability = 1.0f;
    }
    engine->pulsar_lick_octave.store(4, std::memory_order_relaxed);       // D3 = 50
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);  // rests stay rests
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(8, std::memory_order_release);
    engine->pulsar_track_lick_mode[kLeadTrack].store(2, std::memory_order_relaxed);  // FILL
    // OSC in both slots, FIXED so the rendered note is the step's note untransposed.
    engine->pulsar_track_engine_edm[kLeadTrack].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kLeadTrack].store(-1, std::memory_order_relaxed);
    engine->pulsar_track_chord_follow[kLeadTrack].store(2, std::memory_order_relaxed);
    engine->pulsar_track_macros[kLeadTrack].complexity_var_min.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[kLeadTrack].complexity_var_max.store(1.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    bool was_ghost[kMaxPulsarSteps] = {};
    int new_ghosts = 0, wrong_pitch = 0, unflagged = 0, heard = 0, refolded = 0;
    run_bars(engine, 48,
        [&](PulsarState* ps) {
            const PulsarTrackState& lead = ps->tracks[kLeadTrack];
            for (int s = 0; s < lead.step_count; s++) {
                const bool ghost = has_ghost_signature(lead.steps[s]);
                if (ghost && !lead.steps[s].ghost) unflagged++;
                if (ghost && !was_ghost[s]) {
                    new_ghosts++;
                    const int src = nearest_sounding_step(lead, s);
                    if (src < 0 || lead.steps[s].note != lead.steps[src].note) {
                        if (wrong_pitch < 4)
                            printf("  loop %d step %d: ghost note %d, nearest written step %d holds %d\n",
                                   ps->loop_count, s, lead.steps[s].note, src,
                                   src < 0 ? -1 : lead.steps[src].note);
                        wrong_pitch++;
                    }
                }
                was_ghost[s] = ghost;
            }
        },
        [&](PulsarState* ps) {
            // What the voice renders while a fired ghost is under the playhead.
            const PulsarTrackState& lead = ps->tracks[kLeadTrack];
            const int ph = lead.playhead;
            if (ph < 0 || ph >= lead.step_count || !has_ghost_signature(lead.steps[ph])) return;
            if (lead.current_pitch != static_cast<float>(lead.steps[ph].note)) return;
            const float rendered =
                engine->pulsar_track_note_debug[kLeadTrack].load(std::memory_order_relaxed);
            heard++;
            if (rendered != lead.current_pitch) {
                if (refolded < 2)
                    printf("  step %d: ghost note %d renders at %.0f\n",
                           ph, lead.steps[ph].note, rendered);
                refolded++;
            }
        });

    printf("  new ghosts %d, wrong pitch %d, unflagged %d; ghost blocks heard %d, re-folded %d\n",
           new_ghosts, wrong_pitch, unflagged, heard, refolded);
    const bool pass = new_ghosts > 0 && wrong_pitch == 0 && unflagged == 0
                   && heard > 0 && refolded == 0;
    printf("  %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// Track 5 carries no lick in the baseline fixture, so its pattern comes from the effect
// generator, which clears empty steps to note 0 as well.
static bool test_generative_track_ghosts_never_sound_note_zero() {
    printf("\n=== Test: generative track ghosts never sound MIDI note 0 ===\n");

    OrpheusEngine* engine = make_ghost_engine();
    // The override atomic defaults to 0, which generates an empty pattern.
    engine->pulsar_track_density_override[kPadTrack].store(0.25f, std::memory_order_relaxed);
    engine->pulsar_track_macros[kPadTrack].complexity_var_min.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[kPadTrack].complexity_var_max.store(1.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    int ghosts = 0, zero_notes = 0;
    run_bars(engine, 48,
        [&](PulsarState* ps) {
            const PulsarTrackState& pad = ps->tracks[kPadTrack];
            for (int s = 0; s < pad.step_count; s++) {
                if (!pad.steps[s].gate) continue;
                if (has_ghost_signature(pad.steps[s])) ghosts++;
                if (pad.steps[s].note == 0) zero_notes++;
            }
        },
        [](PulsarState*) {});

    printf("  ghost steps seen %d, gated steps at note 0: %d\n", ghosts, zero_notes);
    const bool pass = ghosts > 0 && zero_notes == 0;
    printf("  %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// A track whose generator wrote nothing has no pitch to lend. Before ghosts borrowed
// pitches, this silent track filled up with MIDI 0 ghosts between deja-vu resets.
static bool test_silent_track_grows_no_ghosts() {
    printf("\n=== Test: a track with nothing written grows no ghosts ===\n");

    OrpheusEngine* engine = make_ghost_engine();
    engine->pulsar_track_density_override[kPadTrack].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[kPadTrack].complexity_var_min.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[kPadTrack].complexity_var_max.store(1.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    int gates = 0;
    const int bars = run_bars(engine, 48,
        [&](PulsarState* ps) {
            const PulsarTrackState& pad = ps->tracks[kPadTrack];
            for (int s = 0; s < pad.step_count; s++)
                if (pad.steps[s].gate) gates++;
        },
        [](PulsarState*) {});

    printf("  bars %d, gated steps seen %d\n", bars, gates);
    const bool pass = bars >= 48 && gates == 0;
    printf("  %s\n", pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return pass;
}

// bar_strategy_mutate copies bar 1 into bar 2, then ghosts some of bar 2's empty steps
// at render time. Bar 1 here sounds one note on each beat and nothing else.
static bool test_bar_strategy_mutate_ghosts_borrow_preceding_pitch() {
    printf("\n=== Test: bar_strategy_mutate ghosts sound the nearest preceding written pitch ===\n");

    const PulsarScale& minor = kPulsarScales[0];
    const uint8_t beat_notes[] = {50, 53, 57, 60};  // D3 F3 A3 C4
    int ghosts = 0, wrong_pitch = 0, unflagged = 0;
    for (uint32_t trial = 1; trial <= 64; trial++) {
        PulsarStep steps[32];
        for (int i = 0; i < 32; i++) steps[i] = make_step(0, 0.0f, false, 0.0f);
        for (int b = 0; b < 4; b++) steps[b * 4] = make_step(beat_notes[b], 0.8f, true, 0.5f);
        bar_strategy_mutate(steps, 16, kLeadTrack, 1.0f, 2, minor, trial * 2654435761u);
        for (int i = 17; i < 32; i++) {
            if (i % 4 == 0 || !steps[i].gate) continue;  // an off-beat gate in bar 2 is a ghost
            ghosts++;
            if (!steps[i].ghost) unflagged++;
            const int src = (i / 4) * 4;  // this beat's note, drifted or not
            if (steps[i].note != steps[src].note) {
                if (wrong_pitch < 4)
                    printf("  trial %u step %d: ghost note %d, beat step %d holds %d\n",
                           trial, i, steps[i].note, src, steps[src].note);
                wrong_pitch++;
            }
        }
    }

    printf("  ghosts %d, wrong pitch %d, unflagged %d\n", ghosts, wrong_pitch, unflagged);
    const bool pass = ghosts > 0 && wrong_pitch == 0 && unflagged == 0;
    printf("  %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_pulsar_ghost_pitch_tests() {
    printf("\n========== PULSAR GHOST PITCH TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_ghost_pitch_source_rule());
    tally(test_fill_lick_ghost_borrows_preceding_pitch());
    tally(test_generative_track_ghosts_never_sound_note_zero());
    tally(test_silent_track_grows_no_ghosts());
    tally(test_bar_strategy_mutate_ghosts_borrow_preceding_pitch());
    printf("\nPulsar ghost pitch tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
