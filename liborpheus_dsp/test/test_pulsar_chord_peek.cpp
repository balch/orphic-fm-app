// Next-chord hint (Lick Lab): the engine names the next chord and the beats until it on
// VIZ_PULSAR_NEXT_CHORD / VIZ_PULSAR_CHORD_BEATS_LEFT, worked out on a copy of the chord
// state so nothing is drawn from the live seed, and frozen in the last beat while a track is
// live-armed and the landing chord hangs on the roll.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_chord_progression.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

namespace {

constexpr int kFrames = 256;
constexpr int kBarBlocks = 375;  // 120 BPM: 96000 samples a bar
constexpr int kLiveTrack = 4;

float ring_last(OrpheusEngine* e, int ch) {
    const uint32_t wc = e->viz_rings[ch].write_count.load(std::memory_order_acquire);
    return wc == 0 ? NAN : e->viz_rings[ch].buf[(wc - 1) % VizRing::kVizBufSize];
}

// Chromatic Markov rows: a mutation lands on a different degree most of the time.
void store_chromatic_matrix(OrpheusEngine* e) {
    for (int r = 0; r < 7; r++)
        for (int c = 0; c < 7; c++)
            e->pulsar_chord_matrix[r * 7 + c].store(r == c ? 0.10f : 0.15f, std::memory_order_relaxed);
    e->pulsar_chord_matrix_active.store(1, std::memory_order_relaxed);
}

struct Rig {
    OrpheusEngine* e = nullptr;
    GraphUnit unit{};

    // Loads the vibe on a paused block, so the state exists before the first played block.
    explicit Rig(void (*configure)(OrpheusEngine*)) {
        e = orpheus_engine_create(48000.0f);
        e->pulsar_playing.store(0, std::memory_order_relaxed);
        e->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_fixture_baseline(e);
        pin_pulsar_rngs(e);
        e->clock_bpm.store(120.0f, std::memory_order_relaxed);
        if (configure) configure(e);
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;
        trigger_vibe_load(e);
        block();
    }
    ~Rig() { orpheus_engine_destroy(e); }
    Rig(const Rig&) = delete;
    Rig& operator=(const Rig&) = delete;

    void block() { unit_process_pulsar(&unit, e, kFrames, 48000.0f); }
    void play() { e->pulsar_playing.store(1, std::memory_order_relaxed); }
    PulsarState* ps() const { return e->pulsar_state; }
    float next() const { return ring_last(e, VIZ_PULSAR_NEXT_CHORD); }
    float beats_left() const { return ring_last(e, VIZ_PULSAR_CHORD_BEATS_LEFT); }
    int sounding() const { return e->pulsar_chord_root_degree.load(std::memory_order_relaxed); }
};

// A progression that mutates at most wraps: full complexity, mood and drift, chromatic rows.
void configure_mutating(OrpheusEngine* e) {
    store_chromatic_matrix(e);
    e->pulsar_genre_chords_per_bar.store(4, std::memory_order_relaxed);
    e->pulsar_progression_drift_range.store(1.0f, std::memory_order_relaxed);
    e->pulsar_progression_anchor.store(0, std::memory_order_relaxed);
    e->pulsar_complexity.store(1.0f, std::memory_order_relaxed);
    e->pulsar_mood.store(1.0f, std::memory_order_relaxed);
}

// One chord long: every change is a wrap whose roll can move the very chord it lands on.
void configure_single_chord(OrpheusEngine* e) {
    configure_mutating(e);
    e->pulsar_genre_chords_per_bar.store(2, std::memory_order_relaxed);  // 2 beats a chord
    e->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);
    e->pulsar_custom_progression_length.store(1, std::memory_order_relaxed);
    e->pulsar_custom_progression[0].store(2, std::memory_order_relaxed);
    e->pulsar_track_live_active[kLiveTrack].store(1, std::memory_order_relaxed);
}

// Section 0 plays the vibe's Pop progression, section 1 its own [5, 1]; 2 bars each, a
// one-bar pre-roll on both edges. Section 0 opens.
void configure_two_sections(OrpheusEngine* e) {
    e->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    e->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    e->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    e->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    constexpr int kStride = kSectionDataFields;
    float data[8 * kStride] = {};
    for (int s = 0; s < 8; s++) {
        for (int f = 5; f <= 8; f++) data[s * kStride + f] = -1;    // no macro overrides
        for (int f = 18; f <= 20; f++) data[s * kStride + f] = -1;  // no comping overrides
    }
    for (int s = 0; s < 2; s++) {
        data[s * kStride + 0] = 2;     // bars_min
        data[s * kStride + 1] = 2;     // bars_max
        data[s * kStride + 2] = 1;     // bar_step
        data[s * kStride + 3] = 0.8f;  // recency_decay
        data[s * kStride + 4] = 1;     // transition_count
    }
    for (int i = 0; i < 8 * kStride; i++)
        e->pulsar_section_data[i].store(data[i], std::memory_order_relaxed);
    float trans[8 * 8 * 3] = {};
    trans[0] = 1;  trans[1] = 1.0f;  trans[2] = 1;   // s0 -> s1, one bar of pre-roll
    trans[24] = 0; trans[25] = 1.0f; trans[26] = 1;  // s1 -> s0
    for (int i = 0; i < 8 * 8 * 3; i++)
        e->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);
    for (int s = 0; s < 8; s++) {
        e->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        e->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        e->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
    }
    e->pulsar_section_progression_length[1].store(2, std::memory_order_relaxed);
    e->pulsar_section_progression_degrees[1 * 12 + 0].store(5, std::memory_order_relaxed);
    e->pulsar_section_progression_degrees[1 * 12 + 1].store(1, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++) {
        e->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
        e->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);
    }
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        e->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    e->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

}  // namespace

// ── The peek names the chord advance_chord then lands on ─────────────────
static bool test_peek_matches_advance() {
    printf("\n=== Test: peek_next_chord matches the chord advance_chord lands on ===\n");
    static const int kCustom[] = {3};
    int runs = 0, changes = 0, moved_landing = 0, failures = 0;
    for (int style = 0; style < kNumProgressionStyles + 1; style++) {   // last = one-chord custom
        for (int cpb : {1, 2, 3, 4}) {
            for (int steps : {16, 32}) {
                for (int anchor : {0, 1, 3}) {
                    for (float complexity : {0.2f, 0.7f, 1.0f}) {
                        for (float mood : {0.3f, 1.0f}) {
                            for (uint32_t seed = 1; seed <= 6; seed++) {
                                PulsarChordState cs{};
                                cs.use_custom_matrix = (seed % 2) == 0;
                                for (int r = 0; r < 7; r++)
                                    for (int c = 0; c < 7; c++) cs.custom_matrix[r][c] = r == c ? 0.10f : 0.15f;
                                const bool one = style == kNumProgressionStyles;
                                init_chord_progression(cs, one ? 0 : style, cpb, steps, seed * 2654435761u,
                                                       one ? kCustom : nullptr, one ? 1 : 0);
                                cs.anchor_bars = anchor;
                                cs.drift_range = 1.0f;
                                runs++;
                                for (int n = 0; n < 24 && failures < 5; n++) {
                                    PulsarChordState before;
                                    std::memcpy(&before, &cs, sizeof(cs));
                                    int left = -1;
                                    const int want = peek_next_chord(cs, complexity, mood, &left);
                                    if (std::memcmp(&before, &cs, sizeof(cs)) != 0) {
                                        printf("  FAIL: peek changed the chord state\n");
                                        failures++;
                                        break;
                                    }
                                    if (want < 0 || want > 6 || left < 1) {
                                        printf("  FAIL style %d cpb %d: peek %d, steps %d\n", style, cpb, want, left);
                                        failures++;
                                        break;
                                    }
                                    const int prev = cs.progression[cs.chord_index];
                                    for (int k = 1; k <= left; k++) {
                                        advance_chord(cs, complexity, mood);
                                        const bool changed = cs.chord_step_counter == 0;
                                        if (changed != (k == left)) {
                                            printf("  FAIL style %d cpb %d: change at step %d, peek said %d\n",
                                                   style, cpb, k, left);
                                            failures++;
                                            break;
                                        }
                                        if (!changed) {
                                            // Mid-chord the answer holds and counts down.
                                            int left2 = -1;
                                            const int again = peek_next_chord(cs, complexity, mood, &left2);
                                            if (again != want || left2 != left - k) {
                                                printf("  FAIL: mid-chord peek %d/%d, expected %d/%d\n",
                                                       again, left2, want, left - k);
                                                failures++;
                                                break;
                                            }
                                        }
                                    }
                                    const int got = cs.progression[cs.chord_index];
                                    if (got != want) {
                                        printf("  FAIL style %d cpb %d seed %u: landed on %d, peek said %d\n",
                                               style, cpb, seed, got, want);
                                        failures++;
                                    }
                                    changes++;
                                    if (one && got != prev) moved_landing++;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // A one-chord progression rolls the chord it lands on, so some landings must move.
    const bool ok = failures == 0 && moved_landing > 0;
    printf("  %d runs, %d changes, %d one-chord landings moved by the roll\n", runs, changes, moved_landing);
    printf("Peek matches advance: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── Publishing draws nothing: an unarmed render is bit-identical with it off ──
struct RenderTrace {
    std::vector<float> audio;
    std::vector<int> chords;
    int mutated_blocks = 0;
    int published = 0;
};

static RenderTrace render_unarmed(bool publish_off, int bars) {
    RenderTrace tr;
    Rig r(configure_mutating);
    r.ps()->next_chord_publish_off = publish_off;
    r.play();
    for (int b = 0; b < bars * kBarBlocks; b++) {
        r.block();
        tr.audio.insert(tr.audio.end(), r.e->pulsar_out_l, r.e->pulsar_out_l + kFrames);
        tr.audio.insert(tr.audio.end(), r.e->pulsar_out_r, r.e->pulsar_out_r + kFrames);
        tr.chords.push_back(r.sounding());
        const PulsarChordState& cs = r.ps()->chord_state;
        if (std::memcmp(cs.progression, cs.original_progression, cs.progression_length) != 0)
            tr.mutated_blocks++;
        if (r.next() >= 0.0f) tr.published++;
    }
    return tr;
}

static bool test_unarmed_render_bit_identical() {
    printf("\n=== Test: an unarmed render is bit-identical with the hint switched off ===\n");
    constexpr int kBars = 16;
    const RenderTrace on = render_unarmed(false, kBars);
    const RenderTrace off = render_unarmed(true, kBars);
    size_t first_diff = on.audio.size();
    for (size_t i = 0; i < on.audio.size() && i < off.audio.size(); i++) {
        if (std::memcmp(&on.audio[i], &off.audio[i], sizeof(float)) != 0) { first_diff = i; break; }
    }
    const bool same_audio = on.audio.size() == off.audio.size() && first_diff == on.audio.size();
    const bool same_chords = on.chords == off.chords;
    // Not vacuous: the progression really mutated, and the hint really published.
    const bool ok = same_audio && same_chords && on.mutated_blocks > 0 && on.published > 0
                 && off.published == 0;
    printf("  audio %s (first diff at sample %zu), chords %s, mutated blocks %d, published %d/%d\n",
           same_audio ? "same" : "DIFFERENT", first_diff, same_chords ? "same" : "DIFFERENT",
           on.mutated_blocks, on.published, off.published);
    printf("Unarmed bit-identical: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── Armed: the published chord is the one that plays, even when a ramp moves the roll ──
static bool test_armed_freeze_holds_under_ramp() {
    printf("\n=== Test: armed, the published next chord plays though complexity moves ===\n");
    Rig r(configure_single_chord);
    r.play();
    float target = 1.0f;
    r.e->pulsar_complexity.store(target, std::memory_order_relaxed);
    bool ok = true;
    int checked = 0, rolled_differently = 0, frozen_moves = 0;
    int published = -2;     // -2 = not yet in this chord's last beat
    int counterfactual = -1;
    int prev_degree = r.sounding();
    float prev_left = 1e9f;
    for (int b = 0; b < 48 * kBarBlocks && ok; b++) {
        r.block();
        const float left = r.beats_left();
        const int next = static_cast<int>(r.next());
        if (left > prev_left + 0.5f) {
            // A change landed in this block.
            if (published != -2) {
                const int sounding = r.sounding();
                if (sounding != published) {
                    printf("  FAIL block %d: published %d, sounding %d\n", b, published, sounding);
                    ok = false;
                }
                checked++;
                if (counterfactual != published) rolled_differently++;
                if (published != prev_degree) frozen_moves++;
            }
            prev_degree = r.sounding();
            published = -2;
        } else if (left <= 1.0f) {
            if (published == -2) {
                published = next;
                if (next < 0) {
                    printf("  FAIL block %d: armed hint is unknown (%d)\n", b, next);
                    ok = false;
                }
                // Swing the roll's input for the rest of this chord.
                target = target > 0.5f ? 0.0f : 1.0f;
                r.e->pulsar_complexity.store(target, std::memory_order_relaxed);
            } else if (next != published) {
                printf("  FAIL block %d: hint moved %d -> %d inside the last beat\n", b, published, next);
                ok = false;
            }
            // What a fresh roll with the inputs of this moment would land on.
            int steps = 0;
            counterfactual = peek_next_chord(r.ps()->chord_state,
                                             std::clamp(r.ps()->smooth_complexity, 0.0f, 1.0f),
                                             std::clamp(r.ps()->smooth_mood, 0.0f, 1.0f), &steps);
        }
        prev_left = left;
    }
    // Not vacuous: a live re-roll would have landed elsewhere at least once.
    ok &= checked >= 20 && rolled_differently > 0;
    printf("  %d changes checked, %d a re-roll would have changed, %d moved by the frozen roll\n",
           checked, rolled_differently, frozen_moves);
    printf("Armed freeze: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── Armed with several chords: the wrap lands on the first chord whatever the roll, so
// nothing is held and the chords match an unarmed render ──
void configure_two_beat(OrpheusEngine* e) {
    configure_mutating(e);
    e->pulsar_genre_chords_per_bar.store(2, std::memory_order_relaxed);
}

void configure_two_beat_armed(OrpheusEngine* e) {
    configure_two_beat(e);
    e->pulsar_track_live_active[kLiveTrack].store(1, std::memory_order_relaxed);
}

// Complexity steps once per chord (high every third), so the inputs at a chord's start
// differ from those at its change and a roll held from the start would differ from a live one.
static RenderTrace render_ramped(void (*configure)(OrpheusEngine*), int bars) {
    RenderTrace tr;
    Rig r(configure);
    r.play();
    int changes = 0;
    float prev_left = 1e9f;
    for (int b = 0; b < bars * kBarBlocks; b++) {
        const float left = r.beats_left();
        if (left > prev_left + 0.5f) changes++;
        prev_left = left;
        r.e->pulsar_complexity.store(changes % 3 == 0 ? 1.0f : 0.0f, std::memory_order_relaxed);
        r.block();
        tr.chords.push_back(r.sounding());
        const PulsarChordState& cs = r.ps()->chord_state;
        if (std::memcmp(cs.progression, cs.original_progression, cs.progression_length) != 0)
            tr.mutated_blocks++;
    }
    return tr;
}

static bool test_armed_multi_chord_matches_unarmed() {
    printf("\n=== Test: armed with several chords, the chords match an unarmed render ===\n");
    constexpr int kBars = 64;
    const RenderTrace unarmed = render_ramped(configure_two_beat, kBars);
    const RenderTrace armed = render_ramped(configure_two_beat_armed, kBars);
    size_t first_diff = unarmed.chords.size();
    for (size_t i = 0; i < unarmed.chords.size() && i < armed.chords.size(); i++) {
        if (unarmed.chords[i] != armed.chords[i]) { first_diff = i; break; }
    }
    const bool same = unarmed.chords == armed.chords;
    // Not vacuous: the progression really mutated under the ramp.
    const bool ok = same && unarmed.mutated_blocks > 0;
    printf("  chords %s (first diff at block %zu), mutated blocks %d\n",
           same ? "same" : "DIFFERENT", first_diff, unarmed.mutated_blocks);
    printf("Armed multi-chord: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── A section's first chord is published during the last beat of the one before ──
static bool test_section_first_chord_published() {
    printf("\n=== Test: the next section's first chord shows in the last beat ===\n");
    Rig r(configure_two_sections);
    r.play();
    bool ok = true;
    int flips = 0;
    int prev_section = r.ps()->section_state.current_section;
    std::vector<float> tail_next, tail_left;  // the current section's blocks in a last beat
    for (int b = 0; b < 13 * kBarBlocks && ok; b++) {
        r.block();
        const int section = r.ps()->section_state.current_section;
        if (section != prev_section) {
            // Section 1 opens on 5 (its own progression), section 0 on 0 (the vibe's Pop).
            const int want = section == 1 ? 5 : 0;
            bool falling = true;
            for (size_t i = 1; i < tail_left.size(); i++) falling &= tail_left[i] < tail_left[i - 1];
            bool all_want = !tail_next.empty();
            for (float v : tail_next) all_want &= static_cast<int>(v) == want;
            const float last = tail_left.empty() ? 1e9f : tail_left.back();
            const bool flip_ok = all_want && falling && last < 0.05f && r.sounding() == want;
            printf("  flip %d -> %d: %zu last-beat blocks, hint %s, beats left %s to %.3f, sounding %d -- %s\n",
                   prev_section, section, tail_next.size(), all_want ? "steady" : "WRONG",
                   falling ? "falling" : "NOT FALLING", last, r.sounding(), flip_ok ? "OK" : "FAIL");
            ok &= flip_ok;
            flips++;
            prev_section = section;
        }
        if (r.beats_left() <= 1.0f) {
            tail_next.push_back(r.next());
            tail_left.push_back(r.beats_left());
        } else {
            tail_next.clear();
            tail_left.clear();
        }
    }
    ok &= flips >= 4;
    printf("Section first chord: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── No progression: -1 ───────────────────────────────────────────────────
static bool test_no_progression_publishes_minus_one() {
    printf("\n=== Test: no progression publishes -1 ===\n");
    PulsarChordState empty{};
    int steps = 7;
    const int peeked = peek_next_chord(empty, 1.0f, 1.0f, &steps);

    // Paused, nothing sounds and nothing is coming; the same after playing, then pausing.
    Rig r(nullptr);
    const float paused = r.next();
    r.play();
    for (int b = 0; b < kBarBlocks; b++) r.block();
    const float playing = r.next();
    r.e->pulsar_playing.store(0, std::memory_order_relaxed);
    r.block();
    const float stopped = r.next();
    const bool ok = peeked == -1 && paused == -1.0f && playing >= 0.0f && stopped == -1.0f;
    printf("  peek on empty %d, paused %.0f, playing %.0f, paused again %.0f\n",
           peeked, paused, playing, stopped);
    printf("No progression: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_chord_peek_tests() {
    printf("\n========== PULSAR CHORD PEEK TESTS ==========\n");
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };
    tally(test_peek_matches_advance());
    tally(test_unarmed_render_bit_identical());
    tally(test_armed_freeze_holds_under_ramp());
    tally(test_armed_multi_chord_matches_unarmed());
    tally(test_section_first_chord_published());
    tally(test_no_progression_publishes_minus_one());
    printf("\nPulsar chord peek tests: %s\n", fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(pass, fail);
}
