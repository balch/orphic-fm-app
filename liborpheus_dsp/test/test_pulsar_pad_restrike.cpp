// Opt-in restrike_on_chord_change on a CHORDAL PAD track: the held bed re-strikes on the
// first step of each new chord degree (including across a section seam) instead of holding
// its first note forever. Flag off keeps the one-strike hold.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_pattern_gen.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <vector>

namespace {

constexpr int kPadTrack = 6;
constexpr int kFrames = 512;
constexpr float kBpm = 120.0f;   // 6000 samples a step, so at most one boundary a block

struct Strike {
    int block;
    int playhead;
    int chord_index;
    int degree;
    int section;
    float pitch;
};

OrpheusEngine* make_pad_engine(bool restrike, ChordFollowMode follow = ChordFollowMode::FOLLOW) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.6f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    engine->clock_bpm.store(kBpm, std::memory_order_relaxed);
    engine->pulsar_genre_chords_per_bar.store(1, std::memory_order_relaxed);
    engine->pulsar_track_role[kPadTrack].store(static_cast<int>(TrackRole::CHORDAL), std::memory_order_relaxed);
    engine->pulsar_track_comping_style[kPadTrack].store(static_cast<int>(CompingStyleId::PAD), std::memory_order_relaxed);
    engine->pulsar_track_arp_mode[kPadTrack].store(static_cast<int>(ArpModeId::NEVER), std::memory_order_relaxed);
    engine->pulsar_track_restrike_on_chord_change[kPadTrack].store(restrike ? 1 : 0, std::memory_order_relaxed);
    engine->pulsar_track_chord_follow[kPadTrack].store(static_cast<int>(follow), std::memory_order_relaxed);
    return engine;
}

// Every note-on the pad track fires. A fire re-seats head_origin on this block's boundary;
// otherwise it only slides back by a block.
// `poke` runs after each block, so a test can put the track in a state mid-run.
std::vector<Strike> run_pad(OrpheusEngine* engine, int blocks,
                            const std::function<void(int, PulsarTrackState&)>& poke = nullptr) {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    trigger_vibe_load(engine);
    std::vector<Strike> out;
    double prev = 1.0e9;
    for (int i = 0; i < blocks; i++) {
        unit_process_pulsar(&unit, engine, kFrames, 48000.0f);
        const PulsarState* ps = engine->pulsar_state;
        const PulsarTrackState& ts = ps->tracks[kPadTrack];
        if (std::fabs(ts.head_origin - (prev - kFrames)) > 0.5) {
            const PulsarChordState& cs = ps->chord_state;
            out.push_back({i, ts.playhead, cs.chord_index, cs.progression[cs.chord_index],
                           ps->section_state.current_section, ts.target_pitch});
        }
        prev = ts.head_origin;
        if (poke) poke(i, engine->pulsar_state->tracks[kPadTrack]);
    }
    return out;
}

int semis(OrpheusEngine* engine, int degree) {
    return chord_degree_to_semitones(degree, kPulsarScales[engine->pulsar_scale_index.load()]);
}

// The strike's pitch class sits where its chord root does, relative to the first strike.
bool follows_roots(OrpheusEngine* engine, const std::vector<Strike>& s) {
    bool ok = !s.empty();
    for (size_t k = 1; k < s.size(); k++) {
        const int got = ((static_cast<int>(s[k].pitch) - static_cast<int>(s[0].pitch)) % 12 + 12) % 12;
        const int want = ((semis(engine, s[k].degree) - semis(engine, s[0].degree)) % 12 + 12) % 12;
        if (got != want) {
            printf("    strike %zu: degree %d pitch %.0f, pitch class offset %d want %d\n",
                   k, s[k].degree, s[k].pitch, got, want);
            ok = false;
        }
    }
    return ok;
}

void push_vibe_progression(OrpheusEngine* engine, const int (&degrees)[4] = {0, 3, 4, 5}) {
    // Default: D, G, A, Bb in D minor.
    engine->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);
    engine->pulsar_custom_progression_length.store(4, std::memory_order_relaxed);
    for (int i = 0; i < 4; i++) {
        engine->pulsar_custom_progression[i].store(degrees[i], std::memory_order_relaxed);
        engine->pulsar_custom_progression_glide[i].store(0.0f, std::memory_order_relaxed);
    }
}

constexpr int kBarBlocks = static_cast<int>(16 * 6000 / kFrames);   // 187 blocks a bar

}  // namespace

static bool test_unpushed_default_off() {
    printf("\n=== Test: an unpushed restrike_on_chord_change is off on every track ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) ok &= engine->pulsar_track_restrike_on_chord_change[t].load() == 0;
    orpheus_engine_destroy(engine);
    printf("Default off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_flag_on_follows_each_chord() {
    printf("\n=== Test: flag on, the PAD bed re-strikes once per chord on its root ===\n");
    OrpheusEngine* engine = make_pad_engine(true);
    push_vibe_progression(engine);
    const auto s = run_pad(engine, kBarBlocks * 9);
    bool ok = s.size() >= 9;
    for (size_t k = 0; k < s.size(); k++) {
        // One strike per chord, on the chord's first step.
        ok &= s[k].playhead == 0;
        ok &= s[k].chord_index == static_cast<int>(k % 4);
    }
    ok &= follows_roots(engine, s);
    printf("  strikes=%zu (want >= 9, one per bar)\n", s.size());
    for (const Strike& x : s)
        printf("    block %4d step %2d chord %d deg %d pitch %.0f\n",
               x.block, x.playhead, x.chord_index, x.degree, x.pitch);
    orpheus_engine_destroy(engine);
    printf("Follows chords: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_flag_off_holds_first_root() {
    printf("\n=== Test: flag off, the PAD bed strikes once and holds (unchanged behaviour) ===\n");
    OrpheusEngine* engine = make_pad_engine(false);
    push_vibe_progression(engine);
    const auto s = run_pad(engine, kBarBlocks * 9);
    const bool ok = s.size() == 1 && s[0].playhead == 0 && s[0].chord_index == 0;
    printf("  strikes=%zu (want 1)\n", s.size());
    orpheus_engine_destroy(engine);
    printf("Holds: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Two 2-bar sections whose progressions are one chord each, so chord_index never moves:
// only the seam's change of degree can re-strike.
static void push_two_sections(OrpheusEngine* engine) {
    constexpr int kStride = kSectionDataFields;
    const int degrees[2] = {0, 3};
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kMaxSections * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kMaxSections * kNumPulsarTracks; i++) {
        engine->pulsar_section_track_comping_style[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_inversion[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_arp_mode[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_chord_follow[i].store(-1, std::memory_order_relaxed);
    }
    for (int s = 0; s < 2; s++) {
        auto put = [&](int f, float v) { engine->pulsar_section_data[s * kStride + f].store(v, std::memory_order_relaxed); };
        put(0, 2);
        put(1, 2);
        put(2, 1);
        put(3, 0.8f);
        put(4, 1);
        for (int f = 5; f <= 8; f++) put(f, -1);
        put(9, static_cast<float>(static_cast<int>(SoloModeId::NONE)));
        put(10, 0.0f);
        for (int f = 18; f <= 20; f++) put(f, -1);
        engine->pulsar_section_transitions[s * 24 + 0].store(static_cast<float>(1 - s), std::memory_order_relaxed);
        engine->pulsar_section_transitions[s * 24 + 1].store(1.0f, std::memory_order_relaxed);
        engine->pulsar_section_progression_length[s].store(1, std::memory_order_relaxed);
        engine->pulsar_section_progression_degrees[s * kMaxProgressionLength].store(degrees[s], std::memory_order_relaxed);
        engine->pulsar_section_progression_glides[s * kMaxProgressionLength].store(0.0f, std::memory_order_relaxed);
    }
    for (int i = 0; i < 8 * 15; i++) {
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);
    }
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.fetch_add(1, std::memory_order_release);
}

static bool test_section_restart_restrikes() {
    printf("\n=== Test: flag on, a section's progression restart re-strikes at the seam ===\n");
    OrpheusEngine* on = make_pad_engine(true);
    push_two_sections(on);
    const auto s = run_pad(on, kBarBlocks * 9);
    bool ok = s.size() >= 4;
    for (size_t k = 1; k < s.size(); k++) {
        ok &= s[k].playhead == 0;
        ok &= s[k].section != s[k - 1].section;   // only a seam re-strikes
    }
    ok &= follows_roots(on, s);
    printf("  flag on: strikes=%zu (want >= 4, one per section entry)\n", s.size());
    for (const Strike& x : s)
        printf("    block %4d step %2d section %d deg %d pitch %.0f\n",
               x.block, x.playhead, x.section, x.degree, x.pitch);
    orpheus_engine_destroy(on);

    OrpheusEngine* off = make_pad_engine(false);
    push_two_sections(off);
    const auto f = run_pad(off, kBarBlocks * 9);
    printf("  flag off: strikes=%zu (want 1)\n", f.size());
    ok &= f.size() == 1;
    orpheus_engine_destroy(off);
    printf("Section restart: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_repeated_degree_holds() {
    printf("\n=== Test: flag on, a progression of one repeated degree never re-strikes ===\n");
    OrpheusEngine* engine = make_pad_engine(true);
    push_vibe_progression(engine, {0, 0, 0, 0});
    const auto s = run_pad(engine, kBarBlocks * 9);
    const bool ok = s.size() == 1;
    printf("  strikes=%zu (want 1)\n", s.size());
    orpheus_engine_destroy(engine);
    printf("Repeated degree: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_fixed_follow_holds() {
    printf("\n=== Test: flag on, a FIXED chord-follow track holds (its pitch cannot move) ===\n");
    OrpheusEngine* engine = make_pad_engine(true, ChordFollowMode::FIXED);
    push_vibe_progression(engine);
    const auto s = run_pad(engine, kBarBlocks * 9);
    const bool ok = s.size() == 1;
    printf("  strikes=%zu (want 1)\n", s.size());
    orpheus_engine_destroy(engine);
    printf("FIXED holds: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Mid-chord, put the track where a lost PAD head leaves it: in_hold off, the tail latch on
// (every PAD step is hold=true, so the latch never clears by itself).
static void lose_head(int block, PulsarTrackState& ts) {
    if (block != kBarBlocks / 2) return;
    ts.in_hold = false;
    ts.suppress_hold_tail = true;
    ts.prev_step_gated = false;
}

static bool test_restrike_clears_lost_head_latch() {
    printf("\n=== Test: a lost PAD head stays silent flag off; flag on, the next chord sounds ===\n");
    OrpheusEngine* off = make_pad_engine(false);
    push_vibe_progression(off);
    const auto f = run_pad(off, kBarBlocks * 3, lose_head);
    const bool off_silent = f.size() == 1 && off->pulsar_state->tracks[kPadTrack].suppress_hold_tail;
    orpheus_engine_destroy(off);

    OrpheusEngine* on = make_pad_engine(true);
    push_vibe_progression(on);
    const auto s = run_pad(on, kBarBlocks * 3, lose_head);
    const bool on_back = s.size() >= 3 && s[1].chord_index == 1 && s[1].block > kBarBlocks / 2
        && !on->pulsar_state->tracks[kPadTrack].suppress_hold_tail;
    orpheus_engine_destroy(on);
    printf("  flag off strikes=%zu latched=%s; flag on strikes=%zu\n",
           f.size(), off_silent ? "YES" : "NO", s.size());
    const bool ok = off_silent && on_back;
    printf("Latch cleared: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_pad_restrike_tests() {
    printf("\n=== Pulsar PAD Restrike Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_unpushed_default_off()) suite_pass++; else suite_fail++;
    if (test_flag_on_follows_each_chord()) suite_pass++; else suite_fail++;
    if (test_flag_off_holds_first_root()) suite_pass++; else suite_fail++;
    if (test_section_restart_restrikes()) suite_pass++; else suite_fail++;
    if (test_repeated_degree_holds()) suite_pass++; else suite_fail++;
    if (test_fixed_follow_holds()) suite_pass++; else suite_fail++;
    if (test_restrike_clears_lost_head_latch()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
