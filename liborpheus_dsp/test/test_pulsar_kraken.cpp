// Kraken: a performance key shift applied at fire time (pulsar_kraken.h).

#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_kraken.h"
#include "../src/orpheus_graph.h"
#include "../src/orpheus_viz.h"
#include <cstdio>
#include <cstring>

static bool expect_note(const char* what, int got, int want) {
    if (got == want) return true;
    printf("  FAIL %s: got %d, want %d\n", what, got, want);
    return false;
}

static bool test_kraken_math_transposes_and_remaps() {
    printf("\n=== Test: Kraken shift math ===\n");
    bool ok = true;
    // C major (root 0, scale 1). MIDI 60 = C4.
    ok &= expect_note("home leaves notes alone", kraken_apply(64, 0, 1, kKrakenHome), 64);
    ok &= expect_note("IV lifts E to A", kraken_apply(64, 0, 1, KRAKEN_IV), 69);
    ok &= expect_note("V lifts E to B", kraken_apply(64, 0, 1, KRAKEN_V), 71);
    ok &= expect_note("parallel: E to Eb", kraken_apply(64, 0, 1, KRAKEN_PARALLEL), 63);
    ok &= expect_note("parallel: A to Ab", kraken_apply(69, 0, 1, KRAKEN_PARALLEL), 68);
    ok &= expect_note("parallel: G stays", kraken_apply(67, 0, 1, KRAKEN_PARALLEL), 67);
    ok &= expect_note("relative: C to A", kraken_apply(60, 0, 1, KRAKEN_RELATIVE), 57);
    ok &= expect_note("relative: E to C", kraken_apply(64, 0, 1, KRAKEN_RELATIVE), 60);
    ok &= expect_note("D dorian relative: E to D", kraken_apply(64, 2, 6, KRAKEN_RELATIVE), 62);
    ok &= expect_note("passing tone C# under parallel", kraken_apply(61, 0, 1, KRAKEN_PARALLEL), 61);
    ok &= expect_note("passing tone C# under relative", kraken_apply(61, 0, 1, KRAKEN_RELATIVE), 58);
    ok &= expect_note("out-of-range target is home", kraken_apply(64, 0, 1, 9), 64);
    ok &= expect_note("parallel scale of Major", kraken_scale_for(KRAKEN_PARALLEL, 1), 0);
    ok &= expect_note("home scale under IV", kraken_scale_for(KRAKEN_IV, 6), 6);
    ok &= expect_note("home scale at home", kraken_scale_for(kKrakenHome, 6), 6);
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Every relative and parallel row pairs scales of equal size, so degrees map one to one.
static bool test_kraken_tables_pair_equal_sizes() {
    printf("\n=== Test: Kraken relative and parallel rows pair equal-size scales ===\n");
    bool ok = true;
    for (int s = 0; s < kNumPulsarScales; s++) {
        for (int target : {KRAKEN_RELATIVE, KRAKEN_PARALLEL}) {
            int to = kraken_scale_for(target, s);
            if (kPulsarScales[to].count != kPulsarScales[s].count) {
                printf("  FAIL scale %d target %d -> scale %d: %d vs %d tones\n",
                       s, target, to, kPulsarScales[s].count, kPulsarScales[to].count);
                ok = false;
            }
        }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The diatonic relatives keep the pitch set: every in-scale note lands in the home set.
static bool test_kraken_relative_keeps_the_pitch_set() {
    printf("\n=== Test: Kraken relative keeps the diatonic pitch set ===\n");
    bool ok = true;
    for (int s : {0, 1, 3, 6, 7, 8}) {
        const PulsarScale& sc = kPulsarScales[s];
        for (int i = 0; i < sc.count; i++) {
            int out = kraken_apply(60 + sc.degrees[i], 0, s, KRAKEN_RELATIVE);
            int pc = ((out % 12) + 12) % 12;
            bool in_set = false;
            for (int j = 0; j < sc.count; j++) in_set |= sc.degrees[j] == pc;
            if (!in_set) {
                printf("  FAIL scale %d degree %d -> pitch class %d left the set\n", s, i, pc);
                ok = false;
            }
        }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

namespace {
constexpr float kSr = 48000.0f;
constexpr int kBlk = 512;  // under one 6000-sample step at 120 BPM, so at most one boundary per block

GraphUnit kraken_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

OrpheusEngine* kraken_engine() {
    OrpheusEngine* engine = orpheus_engine_create(kSr);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_dense_fast(engine);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    return engine;
}

void press(OrpheusEngine* e) {
    e->pulsar_kraken_presses.store(e->pulsar_kraken_presses.load() + 1, std::memory_order_release);
}

// Runs blocks until the shift in effect differs from `from`; returns blocks run, or -1.
int run_until_shift_changes(OrpheusEngine* e, GraphUnit& u, int from, int max_blocks = 400) {
    for (int i = 1; i <= max_blocks; i++) {
        unit_process_pulsar(&u, e, kBlk, kSr);
        if (e->pulsar_state->kraken_effective != from) return i;
    }
    return -1;
}

void run_until_off_beat(OrpheusEngine* e, GraphUnit& u) {
    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&u, e, kBlk, kSr);
        if (e->pulsar_state->tracks[0].playhead % 4 == 1) return;
    }
}
}  // namespace

static bool test_shift_commits_on_the_next_beat() {
    printf("\n=== Test: a held Kraken commits on the next beat, on every track ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    run_until_off_beat(e, u);
    e->pulsar_kraken_target.store(KRAKEN_IV);
    e->pulsar_kraken_held.store(1);
    press(e);
    int blocks = run_until_shift_changes(e, u, kKrakenHome);
    PulsarState* s = e->pulsar_state;
    bool ok = blocks > 1 && s->kraken_effective == KRAKEN_IV && s->tracks[0].playhead % 4 == 0;
    for (int t = 0; t < kNumPulsarTracks; t++) ok &= s->tracks[t].kraken_shift == KRAKEN_IV;
    printf("  committed after %d blocks at playhead %d -- %s\n", blocks, s->tracks[0].playhead,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok;
}

static bool test_release_returns_home_on_the_next_beat() {
    printf("\n=== Test: releasing returns home on the next beat ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    run_until_off_beat(e, u);
    e->pulsar_kraken_target.store(KRAKEN_V);
    e->pulsar_kraken_held.store(1);
    press(e);
    run_until_shift_changes(e, u, kKrakenHome);
    run_until_off_beat(e, u);
    e->pulsar_kraken_held.store(0);
    int blocks = run_until_shift_changes(e, u, KRAKEN_V);
    PulsarState* s = e->pulsar_state;
    bool ok = blocks > 1 && s->kraken_effective == kKrakenHome && s->tracks[0].playhead % 4 == 0;
    for (int t = 0; t < kNumPulsarTracks; t++) ok &= s->tracks[t].kraken_shift == kKrakenHome;
    printf("  home after %d blocks at playhead %d -- %s\n", blocks, s->tracks[0].playhead,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok;
}

static bool test_tap_inside_one_block_gets_one_beat() {
    printf("\n=== Test: a tap shorter than a block still plays one beat ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    run_until_off_beat(e, u);
    e->pulsar_kraken_target.store(KRAKEN_IV);
    press(e);  // held never goes to 1
    run_until_shift_changes(e, u, kKrakenHome);
    int on_at = e->pulsar_state->tracks[0].playhead;
    run_until_shift_changes(e, u, KRAKEN_IV);
    int off_at = e->pulsar_state->tracks[0].playhead;
    int steps = (off_at - on_at + e->pulsar_state->tracks[0].step_count) % e->pulsar_state->tracks[0].step_count;
    bool ok = steps == 4;
    printf("  shifted from step %d to %d (%d steps, want 4) -- %s\n", on_at, off_at, steps,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok;
}

static bool test_pause_and_load_clear() {
    printf("\n=== Test: a pause and a vibe load end the shift ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    e->pulsar_kraken_target.store(KRAKEN_IV);
    e->pulsar_kraken_held.store(1);
    press(e);
    run_until_shift_changes(e, u, kKrakenHome);
    e->pulsar_playing.store(0);
    unit_process_pulsar(&u, e, kBlk, kSr);
    bool paused_home = e->pulsar_state->kraken_effective == kKrakenHome;
    for (int t = 0; t < kNumPulsarTracks; t++) paused_home &= e->pulsar_state->tracks[t].kraken_shift == kKrakenHome;

    // A stab pending when a vibe loads is dropped, not played on the new vibe's downbeat.
    e->pulsar_kraken_held.store(0);
    e->pulsar_playing.store(1);
    press(e);
    trigger_vibe_load(e);
    unit_process_pulsar(&u, e, kBlk, kSr);
    bool load_home = e->pulsar_state->kraken_effective == kKrakenHome;
    printf("  pause: %s, load drops a pending stab: %s\n", paused_home ? "PASS" : "FAIL",
           load_home ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return paused_home && load_home;
}

static bool test_root_and_scale_are_never_written() {
    printf("\n=== Test: the Kraken never writes the vibe's root or scale ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    unit_process_pulsar(&u, e, kBlk, kSr);
    int root = e->pulsar_root_note.load(), scale = e->pulsar_scale_index.load();
    for (int target = 0; target < kKrakenTargetCount; target++) {
        e->pulsar_kraken_target.store(target);
        e->pulsar_kraken_held.store(1);
        press(e);
        run_until_shift_changes(e, u, e->pulsar_state->kraken_effective);
        e->pulsar_kraken_held.store(0);
        run_until_shift_changes(e, u, e->pulsar_state->kraken_effective);
    }
    bool ok = e->pulsar_root_note.load() == root && e->pulsar_scale_index.load() == scale;
    printf("  root %d scale %d unchanged -- %s\n", root, scale, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok;
}

static bool test_viz_reports_the_shift_in_effect() {
    printf("\n=== Test: the viz ring reports the shift in effect ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    float buf[480];
    int read_pos = 0;
    e->pulsar_kraken_target.store(KRAKEN_PARALLEL);
    e->pulsar_kraken_held.store(1);
    press(e);
    run_until_shift_changes(e, u, kKrakenHome);
    unit_process_pulsar(&u, e, kBlk, kSr);
    int n = orpheus_engine_get_viz(e, VIZ_PULSAR_KRAKEN, buf, 480, &read_pos);
    bool ok = n > 0 && buf[n - 1] == static_cast<float>(KRAKEN_PARALLEL);
    printf("  last ring value %.0f (want %d) -- %s\n", n > 0 ? buf[n - 1] : -99.f, KRAKEN_PARALLEL,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok;
}

// For a settled melodic track the sounding target is always its base pitch under its shift.
static bool kraken_invariant_holds(OrpheusEngine* e, const char* when) {
    const PulsarState* s = e->pulsar_state;
    int root = e->pulsar_root_note.load(), scale = e->pulsar_scale_index.load();
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const PulsarTrackState& ts = s->tracks[t];
        if (ts.role == TrackRole::PERCUSSIVE || !ts.kraken_base_valid || ts.arp_note_count > 0 ||
            !(ts.voice_active || ts.in_hold)) continue;  // released tails keep the pitch they fired with
        int want = kraken_apply(static_cast<int>(ts.kraken_base_pitch), root, scale, ts.kraken_shift);
        if (want < 0) want = 0;
        if (want > 127) want = 127;
        if (static_cast<int>(ts.target_pitch) != want) {
            printf("  FAIL %s t%d: target %.0f, base %.0f under shift %d wants %d\n", when, t,
                   ts.target_pitch, ts.kraken_base_pitch, ts.kraken_shift, want);
            return false;
        }
    }
    return true;
}

static bool test_fired_notes_carry_the_shift() {
    printf("\n=== Test: fired melodic notes carry the Kraken shift ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    e->pulsar_kraken_target.store(KRAKEN_IV);
    e->pulsar_kraken_held.store(1);
    press(e);
    run_until_shift_changes(e, u, kKrakenHome);
    bool ok = true, moved = false;
    for (int i = 0; i < 300 && ok; i++) {
        unit_process_pulsar(&u, e, kBlk, kSr);
        ok = kraken_invariant_holds(e, "held IV");
        for (int t = 0; t < kNumPulsarTracks; t++) {
            const PulsarTrackState& ts = e->pulsar_state->tracks[t];
            if (ts.kraken_base_valid && ts.role != TrackRole::PERCUSSIVE &&
                static_cast<int>(ts.target_pitch) != static_cast<int>(ts.kraken_base_pitch)) moved = true;
        }
    }
    printf("  invariant %s, some note moved: %s\n", ok ? "PASS" : "FAIL", moved ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok && moved;
}

// Track 3 holds one note over every step, so the beat that commits the shift lands mid-note
// with nothing fired on it: the slide is the only thing that can move the pitch.
static void hold_track3_note(OrpheusEngine* e) {
    PulsarTrackState& ts = e->pulsar_state->tracks[3];
    for (int i = 0; i < kMaxPulsarSteps; i++) { ts.steps[i].gate = true; ts.steps[i].hold = true; }
}

static bool test_ringing_note_slides_and_tails_stay() {
    printf("\n=== Test: a sounding note slides to the shift; released tails stay ===\n");
    OrpheusEngine* e = kraken_engine();
    GraphUnit u = kraken_unit();
    run_until_off_beat(e, u);
    // Seat a ringing, held note on track 3 at its natural fired pitch.
    hold_track3_note(e);
    PulsarTrackState& ring = e->pulsar_state->tracks[3];
    bool ok = ring.kraken_base_valid && ring.role != TrackRole::PERCUSSIVE;
    if (!ok) printf("  FAIL track 3 has no fired melodic base to hold\n");
    ring.in_hold = true;
    ring.voice_active = true;
    ring.gate_timer = 1.0e6f;
    ring.target_pitch = ring.current_pitch = ring.kraken_base_pitch;
    ring.glide_rate = 0.0f;
    float before[kNumPulsarTracks];
    bool sounding[kNumPulsarTracks];
    e->pulsar_kraken_target.store(KRAKEN_V);
    e->pulsar_kraken_held.store(1);
    press(e);
    // Snapshot right before each block, so the last snapshot precedes the committing one.
    for (int i = 0; i < 400; i++) {
        hold_track3_note(e);
        for (int t = 0; t < kNumPulsarTracks; t++) {
            before[t] = e->pulsar_state->tracks[t].target_pitch;
            sounding[t] = e->pulsar_state->tracks[t].voice_active || e->pulsar_state->tracks[t].in_hold;
        }
        unit_process_pulsar(&u, e, kBlk, kSr);
        if (e->pulsar_state->kraken_effective == KRAKEN_V) break;
    }
    ok &= kraken_invariant_holds(e, "commit block");
    const PulsarTrackState& ts3 = e->pulsar_state->tracks[3];
    int scale = e->pulsar_scale_index.load();
    int want = kraken_apply(static_cast<int>(ts3.kraken_base_pitch), e->pulsar_root_note.load(), scale, KRAKEN_V);
    want = want < 0 ? 0 : (want > 127 ? 127 : want);
    if (!sounding[3] || !ts3.in_hold || want == static_cast<int>(before[3])) {
        printf("  FAIL track 3 is not a ringing note that the shift would move\n");
        ok = false;
    } else if (static_cast<int>(ts3.target_pitch) != want || ts3.glide_rate != kKrakenGlideRate ||
               ts3.current_pitch == ts3.target_pitch) {
        printf("  FAIL ringing t3: target %.2f (want %d), glide %.5f, current %.2f\n",
               ts3.target_pitch, want, ts3.glide_rate, ts3.current_pitch);
        ok = false;
    }
    // A released tail keeps the pitch it fired with, unless a fresh note fired on the beat.
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const PulsarTrackState& ts = e->pulsar_state->tracks[t];
        if (ts.role != TrackRole::PERCUSSIVE && !sounding[t] && !ts.voice_active && ts.kraken_base_valid &&
            ts.target_pitch != before[t] && ts.arp_note_count == 0) {
            printf("  FAIL released tail t%d was re-pitched\n", t);
            ok = false;
        }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok;
}

static bool test_toggles_ending_home_match_a_clean_run() {
    printf("\n=== Test: toggling and ending home matches a never-pressed run ===\n");
    OrpheusEngine* clean = kraken_engine();
    OrpheusEngine* toggled = kraken_engine();
    GraphUnit uc = kraken_unit(), ut = kraken_unit();
    for (int i = 0; i < 600; i++) {
        if (i % 37 == 5 && i < 400) {
            toggled->pulsar_kraken_target.store((i / 37) % kKrakenTargetCount);
            press(toggled);
            toggled->pulsar_kraken_held.store((i / 37) % 2);
        }
        if (i == 400) toggled->pulsar_kraken_held.store(0);
        unit_process_pulsar(&uc, clean, kBlk, kSr);
        unit_process_pulsar(&ut, toggled, kBlk, kSr);
    }
    // The shift lives only at fire time: stored patterns and pre-shift pitches never diverge.
    bool ok = toggled->pulsar_state->kraken_effective == kKrakenHome;
    for (int t = 0; t < kNumPulsarTracks && ok; t++) {
        const PulsarTrackState& a = clean->pulsar_state->tracks[t];
        const PulsarTrackState& b = toggled->pulsar_state->tracks[t];
        if (a.kraken_base_pitch != b.kraken_base_pitch || a.step_count != b.step_count) {
            printf("  FAIL t%d: base %.1f vs %.1f\n", t, a.kraken_base_pitch, b.kraken_base_pitch);
            ok = false;
        }
        for (int s = 0; s < a.step_count && ok; s++) {
            if (a.steps[s].note != b.steps[s].note || a.steps[s].raw_note != b.steps[s].raw_note) {
                printf("  FAIL t%d s%d: note %d vs %d\n", t, s, a.steps[s].note, b.steps[s].note);
                ok = false;
            }
        }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(clean);
    orpheus_engine_destroy(toggled);
    return ok;
}

static bool test_finished_arp_note_keeps_its_pitch() {
    printf("\n=== Test: a finished arp's held note is not re-pitched ===\n");
    OrpheusEngine* e = orpheus_engine_create(kSr);
    e->pulsar_playing.store(1, std::memory_order_relaxed);
    e->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_dense_fast(e);
    e->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    const int T = 4;
    e->pulsar_track_role[T].store(2, std::memory_order_relaxed);      // CHORDAL
    e->pulsar_track_arp_mode[T].store(1, std::memory_order_relaxed);  // ALWAYS
    pin_pulsar_rngs(e);
    trigger_vibe_load(e);
    e->clock_bpm.store(120.0f, std::memory_order_relaxed);
    GraphUnit u = kraken_unit();
    bool finished = false, was_arping = false;
    for (int i = 0; i < 800 && !finished; i++) {
        unit_process_pulsar(&u, e, kBlk, kSr);
        const PulsarTrackState& ts = e->pulsar_state->tracks[T];
        if (ts.arp_note_count > 0) was_arping = true;
        else if (was_arping && (ts.voice_active || ts.in_hold)) finished = true;
    }
    if (!finished) {
        printf("  FAIL could not reach a finished-but-sounding arp (arped: %d)\n", was_arping);
        orpheus_engine_destroy(e);
        return false;
    }
    float held = e->pulsar_state->tracks[T].target_pitch;
    e->pulsar_kraken_target.store(KRAKEN_V);
    e->pulsar_kraken_held.store(1);
    press(e);
    bool ok = true;
    for (int i = 0; i < 400; i++) {
        const PulsarTrackState& ts = e->pulsar_state->tracks[T];
        bool sounding = ts.voice_active || ts.in_hold;
        bool pre = ts.arp_note_count == 0 && ts.target_pitch == held;
        unit_process_pulsar(&u, e, kBlk, kSr);
        if (e->pulsar_state->kraken_effective == KRAKEN_V) {
            if (sounding && pre && e->pulsar_state->tracks[T].arp_note_count == 0 &&
                e->pulsar_state->tracks[T].target_pitch != held) {
                printf("  FAIL held arp note moved %.0f -> %.0f\n", held,
                       e->pulsar_state->tracks[T].target_pitch);
                ok = false;
            }
            break;
        }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(e);
    return ok;
}

bool run_pulsar_kraken_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { ok ? suite_pass++ : suite_fail++; };
    tally(test_kraken_math_transposes_and_remaps());
    tally(test_kraken_tables_pair_equal_sizes());
    tally(test_kraken_relative_keeps_the_pitch_set());
    tally(test_shift_commits_on_the_next_beat());
    tally(test_release_returns_home_on_the_next_beat());
    tally(test_tap_inside_one_block_gets_one_beat());
    tally(test_pause_and_load_clear());
    tally(test_root_and_scale_are_never_written());
    tally(test_viz_reports_the_shift_in_effect());
    tally(test_fired_notes_carry_the_shift());
    tally(test_ringing_note_slides_and_tails_stay());
    tally(test_toggles_ending_home_match_a_clean_run());
    tally(test_finished_arp_note_keeps_its_pitch());
    printf("\nKraken tests: %s\n", suite_fail == 0 ? "ALL PASS" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
