// Chord-tone lick mutation (Vibe.lickMutationMode = CHORD_TONES).
//
// RANDOM (0) is the original mutation: a rest can fill and a note can jump to any
// degree. CHORD_TONES (1) keeps every rest, moves a mutated note at most one scale
// step at render time and flags it `mutated`; at note-on a flagged lick step snaps
// to the nearest tone of the chord sounding under it.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_bar_strategy.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>

static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";

namespace {

// 4 beats = 16 steps with rests at steps 4-7 and 10-11.
const PulsarLickStep kLick[] = {
    {0, 1.0f, 0.8f, -1.0f, 1.0f},
    {-1, 1.0f, 0.0f, -1.0f, 1.0f},
    {2, 0.5f, 0.8f, -1.0f, 1.0f},
    {-1, 0.5f, 0.0f, -1.0f, 1.0f},
    {4, 0.5f, 0.7f, -1.0f, 1.0f},
    {1, 0.5f, 0.7f, -1.0f, 1.0f},
};
constexpr int kLickLen = 6;
constexpr uint8_t kRoot = 2;
constexpr int kRangeLo = 36, kRangeHi = 72;

int slots_of(const PulsarLickStep& ls) {
    return std::max(1, static_cast<int>(ls.duration * 4.0f + 0.5f));
}

// Rendered degree relative to the lick's degree 0, so octave folds compare linearly.
int rendered_degree(uint8_t note, const PulsarScale& sc) {
    return midi_to_lick_degree(note, kRoot, sc)
         - midi_to_lick_degree(lick_degree_to_midi(0, kRoot, sc, -1, kRangeLo, kRangeHi), kRoot, sc);
}

struct WalkResult { int notes = 0; int mutated = 0; bool ok = true; };

// Walks one pass of the lick from step_pos to end, checking each written position:
// rests stay silent, a note sits within 1 of T(written), mutated marks a difference.
// transform mirrors bar_strategy_call_response's response phase (-1 = none).
void check_walk(const PulsarStep* steps, int start, int end, int transform,
                const PulsarScale& sc, uint32_t seed, const char* label, WalkResult& r) {
    int pos = start;
    int lick_idx = 0;
    while (pos < end) {
        const PulsarLickStep& ls = kLick[lick_idx % kLickLen];
        lick_idx++;
        const int slots = slots_of(ls);
        if (transform == 3 && lick_idx > kLickLen / 2) break;
        if (ls.scale_degree < 0) {
            for (int s = pos; s < pos + slots && s < end; s++) {
                if (steps[s].gate) {
                    if (r.ok) printf("  FAIL %s seed %u: step %d inside a rest is gated\n", label, seed, s);
                    r.ok = false;
                }
            }
            pos += slots;
            continue;
        }
        int written = ls.scale_degree;
        switch (transform) {
            case 0: written += 2; break;
            case 1: written = -written; break;
            case 2: written += sc.count; break;
            default: break;
        }
        const PulsarStep& st = steps[pos];
        if (!st.gate || !st.from_lick) {
            if (r.ok) printf("  FAIL %s seed %u: note head at step %d missing\n", label, seed, pos);
            r.ok = false;
        } else {
            const int want = rendered_degree(
                static_cast<uint8_t>(lick_degree_to_midi(written, kRoot, sc, -1, kRangeLo, kRangeHi)), sc);
            const int got = rendered_degree(st.note, sc);
            const int diff = std::abs(got - want);
            r.notes++;
            if (st.mutated) r.mutated++;
            if (diff > 1 || st.mutated != (diff != 0)) {
                if (r.ok) printf("  FAIL %s seed %u step %d: written %d rendered %d mutated=%d\n",
                                 label, seed, pos, want, got, st.mutated ? 1 : 0);
                r.ok = false;
            }
        }
        pos += slots;
    }
    // Truncated response: nothing may sound after the break.
    if (transform == 3) {
        for (int s = pos; s < end; s++) {
            if (steps[s].gate && r.ok) {
                printf("  FAIL %s seed %u: step %d sounds after the truncation\n", label, seed, s);
                r.ok = false;
            }
        }
    }
}

}  // namespace

// ── Test 1: a rest never fills ────────────────────────────────────────────
static bool test_chord_tone_mutation_keeps_rests() {
    printf("\n=== Test: CHORD_TONES keeps every rest of the lick ===\n");
    const PulsarScale& sc = kPulsarScales[0];
    bool ok = true;
    int rest_fills = 0;
    for (uint32_t seed = 1; seed <= 200; seed++) {
        PulsarStep steps[kMaxPulsarSteps];
        generate_lick_pattern(steps, 16, kLick, kLickLen, 1.0f, kRoot, sc, seed * 2654435761u,
                              0, -1, kRangeLo, kRangeHi, 0, 0, LickMutationMode::CHORD_TONES);
        for (int s : {4, 5, 6, 7, 10, 11}) {
            if (steps[s].gate) rest_fills++;
        }
    }
    if (rest_fills != 0) {
        printf("  FAIL: %d rest steps gated over 200 seeds\n", rest_fills);
        ok = false;
    }
    printf("  rest steps gated: %d -- %s\n", rest_fills, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Test 2: a mutated note moves at most one scale step ───────────────────
static bool test_chord_tone_mutation_moves_one_step() {
    printf("\n=== Test: CHORD_TONES moves a mutated note one scale step at most ===\n");
    const PulsarScale& sc = kPulsarScales[0];
    WalkResult r;
    for (uint32_t seed = 1; seed <= 200; seed++) {
        PulsarStep steps[kMaxPulsarSteps];
        generate_lick_pattern(steps, 16, kLick, kLickLen, 1.0f, kRoot, sc, seed * 2654435761u,
                              0, -1, kRangeLo, kRangeHi, 0, 0, LickMutationMode::CHORD_TONES);
        check_walk(steps, 0, 16, -1, sc, seed, "lick", r);
    }
    // Mutation 1.0 moves roughly half the notes; none would make the check vacuous.
    if (r.mutated < r.notes / 4) {
        printf("  FAIL: only %d of %d notes mutated\n", r.mutated, r.notes);
        r.ok = false;
    }
    printf("  %d notes, %d mutated -- %s\n", r.notes, r.mutated, r.ok ? "PASS" : "FAIL");
    return r.ok;
}

// ── Test 3: CALL_RESPONSE renders both halves the same way ─────────────────
static bool test_chord_tone_mutation_call_response_too() {
    printf("\n=== Test: CHORD_TONES holds through bar_strategy_call_response ===\n");
    const PulsarScale& sc = kPulsarScales[0];
    WalkResult r;
    for (uint32_t seed = 1; seed <= 200; seed++) {
        PulsarStep steps[kMaxPulsarSteps];
        bar_strategy_call_response(steps, 32, kLick, kLickLen, 1.0f, kRoot, sc, seed,
                                   -1, kRangeLo, kRangeHi, 0, 0, LickMutationMode::CHORD_TONES);
        check_walk(steps, 0, 16, -1, sc, seed, "call", r);
        check_walk(steps, 16, 32, static_cast<int>(seed % 4), sc, seed, "response", r);
    }
    if (r.mutated < r.notes / 4) {
        printf("  FAIL: only %d of %d notes mutated\n", r.mutated, r.notes);
        r.ok = false;
    }
    printf("  %d notes, %d mutated -- %s\n", r.notes, r.mutated, r.ok ? "PASS" : "FAIL");
    return r.ok;
}

// ── Test 4: a FIXED track's mutated notes land on the sounding chord ───────
static bool test_chord_tone_mutation_fixed_track_lands_on_chord() {
    printf("\n=== Test: CHORD_TONES snaps a FIXED track's mutated notes to the chord ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);   // root D, natural minor
    pin_pulsar_rngs(engine, 0xC0DE);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    // Complexity scales lickMutation (pulsar_lick_calm.h); 0 would play the lick as written.
    engine->pulsar_complexity.store(0.6f, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(0, std::memory_order_relaxed);       // AD leaves pending_retrig readable
    engine->pulsar_tension_inner_bars.store(1, std::memory_order_relaxed);  // tension 0: no key shift
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_genre_chords_per_bar.store(1, std::memory_order_relaxed);
    engine->pulsar_custom_progression[0].store(0, std::memory_order_relaxed);
    engine->pulsar_custom_progression[1].store(3, std::memory_order_relaxed);
    engine->pulsar_custom_progression_length.store(2, std::memory_order_relaxed);
    engine->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);

    constexpr int T = 4;
    engine->pulsar_track_lick_mode[T].store(2, std::memory_order_relaxed);        // FILL
    engine->pulsar_track_chord_follow[T].store(2, std::memory_order_relaxed);     // FIXED
    engine->pulsar_track_lick_degree_offset[T].store(23, std::memory_order_relaxed);
    const int8_t degrees[] = {0, 1, 2, 3, 4, 5, 6, 1};
    for (int i = 0; i < 8; i++) {
        engine->pulsar_lick[i].scale_degree = degrees[i];
        engine->pulsar_lick[i].duration = 0.5f;
        engine->pulsar_lick[i].velocity = 0.8f;
        engine->pulsar_lick[i].glide_rate = -1.0f;
        engine->pulsar_lick[i].hit_probability = 1.0f;
    }
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);
    orpheus_engine_set_port(engine, PULSAR_URI, "lick_mutation_mode", 1.0f);
    engine->pulsar_lick_length.store(8, std::memory_order_release);

    trigger_vibe_load(engine);
    const PulsarScale& sc = kPulsarScales[0];
    int checked = 0, off_chord = 0, plain = 0;
    bool saw_chord[7] = {};
    for (int i = 0; i < 200000; i++) {
        unit_process_pulsar(&unit, engine, 64, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->loop_count >= 16) break;
        const PulsarTrackState& ts = ps->tracks[T];
        if (!ts.pending_retrig || ts.playhead < 0 || ts.playhead >= ts.step_count) continue;
        const PulsarStep& st = ts.steps[ts.playhead];
        if (!st.from_lick) continue;
        if (!st.mutated) { plain++; continue; }
        const int cd = ps->chord_state.progression[ps->chord_state.chord_index];
        const int deg = midi_to_lick_degree(static_cast<int>(ts.target_pitch), kRoot, sc);
        const int pc = ((deg % sc.count) + sc.count) % sc.count;
        const bool on_chord = pc == cd % sc.count || pc == (cd + 2) % sc.count
                           || pc == (cd + 4) % sc.count;
        checked++;
        if (cd >= 0 && cd < 7) saw_chord[cd] = true;
        if (!on_chord) {
            if (off_chord < 5)
                printf("  FAIL: chord %d, note %d is degree %d\n",
                       cd, static_cast<int>(ts.target_pitch), pc);
            off_chord++;
        }
    }
    orpheus_engine_destroy(engine);

    bool ok = off_chord == 0;
    if (checked < 16) {
        printf("  FAIL: only %d mutated note-ons observed\n", checked);
        ok = false;
    }
    if (!saw_chord[0] || !saw_chord[3]) {
        printf("  FAIL: mutated notes did not meet both chords (i=%d iv=%d)\n",
               saw_chord[0] ? 1 : 0, saw_chord[3] ? 1 : 0);
        ok = false;
    }
    printf("  %d mutated note-ons (%d plain), %d off the chord -- %s\n",
           checked, plain, off_chord, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Test 5: mode 0 is the original render ─────────────────────────────────
namespace {

OrpheusEngine* make_render_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    // Past the steady zone, so mode 1 moves degrees (and flags them), not just velocities.
    engine->pulsar_complexity.store(0.6f, std::memory_order_relaxed);
    engine->pulsar_track_lick_mode[4].store(2, std::memory_order_relaxed);    // FILL
    engine->pulsar_track_bar_strategy[4].store(3, std::memory_order_relaxed); // CALL_RESPONSE
    engine->pulsar_track_lick_mode[5].store(2, std::memory_order_relaxed);    // FILL, plain loop
    engine->pulsar_track_lick_mode[3].store(2, std::memory_order_relaxed);    // FILL from the bass line
    engine->pulsar_track_lick_source[3].store(1, std::memory_order_relaxed);
    for (int i = 0; i < kLickLen; i++) {
        engine->pulsar_lick[i].scale_degree = kLick[i].scale_degree;
        engine->pulsar_lick[i].duration = kLick[i].duration;
        engine->pulsar_lick[i].velocity = kLick[i].velocity;
        engine->pulsar_lick[i].glide_rate = -1.0f;
        engine->pulsar_lick[i].hit_probability = 1.0f;
        engine->pulsar_bass_line[i].scale_degree = kLick[i].scale_degree;
        engine->pulsar_bass_line[i].duration = kLick[i].duration;
        engine->pulsar_bass_line[i].velocity = kLick[i].velocity;
        engine->pulsar_bass_line[i].glide_rate = -1.0f;
        engine->pulsar_bass_line[i].hit_probability = 1.0f;
    }
    engine->pulsar_lick_mutation.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_bass_line_mutation.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_bass_line_loop.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(kLickLen, std::memory_order_release);
    engine->pulsar_bass_line_length.store(kLickLen, std::memory_order_release);
    return engine;
}

bool same_step(const PulsarStep& a, const PulsarStep& b) {
    return a.note == b.note && a.raw_note == b.raw_note && a.velocity == b.velocity
        && a.gate == b.gate && a.duration == b.duration && a.hold == b.hold
        && a.ghost == b.ghost && a.glide_rate == b.glide_rate
        && a.hit_probability == b.hit_probability && a.from_lick == b.from_lick
        && a.mutated == b.mutated;
}

int mutated_steps(const PulsarState* ps) {
    int n = 0;
    for (int t = 0; t < kNumPulsarTracks; t++)
        for (int s = 0; s < ps->tracks[t].step_count; s++)
            if (ps->tracks[t].steps[s].mutated) n++;
    return n;
}

bool same_tracks(const PulsarState* a, const PulsarState* b) {
    for (int t = 0; t < kNumPulsarTracks; t++) {
        if (a->tracks[t].step_count != b->tracks[t].step_count) return false;
        for (int s = 0; s < a->tracks[t].step_count; s++)
            if (!same_step(a->tracks[t].steps[s], b->tracks[t].steps[s])) return false;
    }
    return true;
}

void load_seed(OrpheusEngine* engine, GraphUnit& unit, uint32_t seed) {
    pin_pulsar_rngs(engine, seed);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);
}

}  // namespace

static bool test_random_mutation_unchanged() {
    printf("\n=== Test: lick_mutation_mode 0 renders as if never written ===\n");
    OrpheusEngine* never = make_render_engine();
    OrpheusEngine* zero = make_render_engine();
    OrpheusEngine* chord = make_render_engine();
    orpheus_engine_set_port(zero, PULSAR_URI, "lick_mutation_mode", 0.0f);
    orpheus_engine_set_port(chord, PULSAR_URI, "lick_mutation_mode", 1.0f);
    GraphUnit un, uz, uc;
    for (GraphUnit* u : {&un, &uz, &uc}) {
        std::memset(u, 0, sizeof(*u));
        u->type = UNIT_PULSAR;
        u->enabled = true;
    }

    int mismatched = 0, chord_differs = 0, bass_fills_random = 0, bass_fills_chord = 0;
    int flagged_random = 0, flagged_chord = 0;
    for (uint32_t seed = 1; seed <= 200; seed++) {
        load_seed(never, un, seed);
        load_seed(zero, uz, seed);
        load_seed(chord, uc, seed);
        if (!same_tracks(never->pulsar_state, zero->pulsar_state)) {
            if (mismatched < 3) printf("  FAIL seed %u: mode 0 differs from the unwritten port\n", seed);
            mismatched++;
        }
        if (!same_tracks(never->pulsar_state, chord->pulsar_state)) chord_differs++;
        // RANDOM never flags a step `mutated`, so its note-on never snaps to the chord.
        flagged_random += mutated_steps(never->pulsar_state) + mutated_steps(zero->pulsar_state);
        flagged_chord += mutated_steps(chord->pulsar_state);
        // Bass-line channel (track 3): rests sit at steps 4-7 and 10-11 of each 16.
        for (int base : {0, 16}) {
            for (int s : {4, 5, 6, 7, 10, 11}) {
                if (never->pulsar_state->tracks[3].steps[base + s].gate) bass_fills_random++;
                if (chord->pulsar_state->tracks[3].steps[base + s].gate) bass_fills_chord++;
            }
        }
    }
    orpheus_engine_destroy(never);
    orpheus_engine_destroy(zero);
    orpheus_engine_destroy(chord);

    bool ok = mismatched == 0;
    // The comparison has teeth only if mode 1 reaches the render.
    if (chord_differs == 0) {
        printf("  FAIL: mode 1 never changed the render, so the port is not reaching it\n");
        ok = false;
    }
    // RANDOM fills the bass line's rests at mutation 1.0; CHORD_TONES keeps them.
    if (bass_fills_random == 0 || bass_fills_chord != 0) {
        printf("  FAIL: bass-line rest fills random=%d chord=%d\n", bass_fills_random, bass_fills_chord);
        ok = false;
    }
    // Mode 1 flags its moved notes, so a zero count under mode 0 is not a flag that never sets.
    if (flagged_random != 0 || flagged_chord == 0) {
        printf("  FAIL: steps flagged mutated random=%d chord=%d\n", flagged_random, flagged_chord);
        ok = false;
    }
    printf("  mode0 mismatches %d, mode1 differs on %d seeds, bass rest fills %d/%d, "
           "mutated flags %d/%d -- %s\n",
           mismatched, chord_differs, bass_fills_random, bass_fills_chord,
           flagged_random, flagged_chord, ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_chord_mutation_tests() {
    printf("\n========== PULSAR CHORD-TONE MUTATION TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_chord_tone_mutation_keeps_rests());
    tally(test_chord_tone_mutation_moves_one_step());
    tally(test_chord_tone_mutation_call_response_too());
    tally(test_chord_tone_mutation_fixed_track_lands_on_chord());
    tally(test_random_mutation_unchanged());
    printf("\nPulsar chord-tone mutation tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
