#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_bar_strategy.h"
#include "../src/pulsar_lick_calm.h"
#include "../src/pulsar_solo.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>
#include <vector>

// Section-seam defects in the Pulsar engine: what a section entry, a solo handoff and the
// deja-vu reset leave behind on the tracks. Each test drives the whole unit at 240 BPM and
// inspects PulsarState once per bar.

namespace {

constexpr int kStride = kSectionDataFields;

struct SeamSection {
    int bars;
    SoloModeId mode;
    float solo_probability;
    int lick_slot;   // -1 = no pin
    int next;        // the single outgoing edge
};

OrpheusEngine* make_seam_engine(uint32_t seed, float complexity) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.9f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(complexity, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    pin_pulsar_rngs(engine, seed);
    return engine;
}

void push_seam_sections(OrpheusEngine* engine, const SeamSection* secs, int n) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(n, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kMaxSections * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(0.0f, std::memory_order_relaxed);
    // The per-track section atomics default to 0, which reads as an override; -1 is unset.
    for (int i = 0; i < kMaxSections * kNumPulsarTracks; i++) {
        engine->pulsar_section_track_comping_style[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_inversion[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_arp_mode[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_chord_follow[i].store(-1, std::memory_order_relaxed);
    }
    for (int s = 0; s < n; s++) {
        const int b = s * kStride;
        const SeamSection& sec = secs[s];
        auto put = [&](int f, float v) { engine->pulsar_section_data[b + f].store(v, std::memory_order_relaxed); };
        put(0, static_cast<float>(sec.bars));
        put(1, static_cast<float>(sec.bars));
        put(2, 1);
        put(3, 0.8f);
        put(4, 1);
        for (int f = 5; f <= 8; f++) put(f, -1);
        put(9, static_cast<float>(static_cast<int>(sec.mode)));
        put(10, sec.solo_probability);
        put(11, 1.0f);
        put(12, 0.5f);
        put(13, 2);
        put(14, 4);
        for (int f = 18; f <= 20; f++) put(f, -1);
        put(26, static_cast<float>(sec.lick_slot + 1));
        engine->pulsar_section_transitions[s * 24 + 0].store(static_cast<float>(sec.next), std::memory_order_relaxed);
        engine->pulsar_section_transitions[s * 24 + 1].store(1.0f, std::memory_order_relaxed);
    }
    for (int i = 0; i < 8 * 15; i++) {
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);
    }
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.fetch_add(1, std::memory_order_release);
}

// Drums (tracks 0-2, always active) plus one or two melodic members. Two melodic members
// trade the lead every `bars_per_lead` bars; one keeps it.
void push_seam_band(OrpheusEngine* engine, const std::vector<int>& member_a,
                    const std::vector<int>& member_b, int bars_per_lead) {
    const int members = member_b.empty() ? 2 : 3;
    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(members, std::memory_order_relaxed);
    for (int i = 0; i < 96; i++) engine->pulsar_band_member_data[i].store(0.0f, std::memory_order_relaxed);
    auto put_member = [&](int m, const std::vector<int>& tracks, bool always, float loud, float creat) {
        const int b = m * 12;
        engine->pulsar_band_member_data[b + 0].store(static_cast<float>(tracks.size()), std::memory_order_relaxed);
        for (size_t i = 0; i < tracks.size(); i++)
            engine->pulsar_band_member_data[b + 1 + static_cast<int>(i)].store(
                static_cast<float>(tracks[i]), std::memory_order_relaxed);
        engine->pulsar_band_member_data[b + 9].store(always ? 1.0f : 0.0f, std::memory_order_relaxed);
        engine->pulsar_band_member_data[b + 10].store(loud, std::memory_order_relaxed);
        engine->pulsar_band_member_data[b + 11].store(creat, std::memory_order_relaxed);
    };
    put_member(0, {0, 1, 2}, true, 0.7f, 0.2f);
    put_member(1, member_a, false, 0.8f, 0.9f);
    if (members == 3) put_member(2, member_b, false, 0.8f, 0.9f);
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    if (members == 3) {
        engine->pulsar_band_handoff_matrix[1 * 3 + 2].store(1.0f, std::memory_order_relaxed);
        engine->pulsar_band_handoff_matrix[2 * 3 + 1].store(1.0f, std::memory_order_relaxed);
    } else {
        engine->pulsar_band_handoff_matrix[1 * 2 + 1].store(1.0f, std::memory_order_relaxed);
    }
    engine->pulsar_band_bars_per_lead_min.store(bars_per_lead, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_max.store(bars_per_lead, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_min.store(1, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_max.store(1, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.fetch_add(1, std::memory_order_release);
}

// A gappy hook (gated on 0,1,4,5,8,9,12,13), so a Jam has rests to ornament.
const int8_t kSeamHook[8] = {0, -1, 3, -1, 5, -1, 4, -1};

bool is_seam_hook_step(int s) {
    const int r = s % 4;
    return r == 0 || r == 1;
}

void arm_seam_hook(OrpheusEngine* engine, const std::vector<int>& tracks) {
    for (int i = 0; i < 8; i++)
        engine->pulsar_lick[i] = {kSeamHook[i], 0.5f, 0.8f, -1.0f};
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(8, std::memory_order_release);
    for (int t : tracks) {
        engine->pulsar_track_lick_mode[t].store(2, std::memory_order_relaxed);     // FILL
        engine->pulsar_track_bar_strategy[t].store(0, std::memory_order_relaxed);  // REPEAT
    }
}

void give_jam_density(OrpheusEngine* engine, const std::vector<int>& tracks) {
    for (int t : tracks) {
        engine->pulsar_track_solo_behavior[t * 15 + 12].store(0.6f, std::memory_order_relaxed);
        engine->pulsar_track_solo_behavior[t * 15 + 13].store(0.9f, std::memory_order_relaxed);
    }
}

// Process blocks, calling on_bar once after every bar boundary, until on_bar returns false
// or `max_blocks` run out.
void run_bars(OrpheusEngine* engine, int max_blocks, const std::function<bool(PulsarState*)>& on_bar) {
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    int last_loop = -1;
    for (int i = 0; i < max_blocks; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps || ps->loop_count == last_loop) continue;
        last_loop = ps->loop_count;
        if (!on_bar(ps)) break;
    }
}

struct StepSnap {
    int count = 0;
    PulsarStep steps[kMaxPulsarSteps] = {};
};

StepSnap snap(const PulsarTrackState& ts) {
    StepSnap out;
    out.count = ts.step_count;
    for (int s = 0; s < ts.step_count && s < kMaxPulsarSteps; s++) out.steps[s] = ts.steps[s];
    return out;
}

// A fresh render of track t from its current lick channel, the recipe a pool swap uses.
StepSnap fresh_lick_render(OrpheusEngine* engine, PulsarState* ps, int t) {
    static PulsarTrackState scratch;
    const bool bass = engine->pulsar_track_lick_source[t].load(std::memory_order_relaxed) == 1;
    const PulsarLickStep* lick = bass ? ps->bass_line : ps->lick;
    const int len = bass ? ps->bass_line_length : ps->lick_length;
    const int loop = bass ? ps->bass_line_loop_length : ps->lick_loop_length;
    const int oct = bass ? ps->bass_line_octave : ps->lick_octave;
    const float mut = lick_calm::effective_mutation(bass ? ps->bass_line_mutation : ps->lick_mutation,
                                                    ps->in_spurt, ps->lick_variation,
                                                    static_cast<lick_calm::Zone>(ps->lick_zone));
    int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
    render_lick_into_track(scratch, t, lick, len, mut,
                           static_cast<uint8_t>(engine->pulsar_root_note.load(std::memory_order_relaxed)),
                           kPulsarScales[si], ps->seed_counter * 2654435761u,
                           ps->tracks[t].bar_strategy, engine->pulsar_step_count.load(std::memory_order_relaxed), oct,
                           static_cast<uint8_t>(engine->pulsar_genre_note_range_low.load(std::memory_order_relaxed)),
                           static_cast<uint8_t>(engine->pulsar_genre_note_range_high.load(std::memory_order_relaxed)),
                           loop, engine->pulsar_track_lick_degree_offset[t].load(std::memory_order_relaxed));
    return snap(scratch);
}

bool same_steps(const StepSnap& a, const StepSnap& b) {
    if (a.count != b.count) return false;
    for (int s = 0; s < a.count; s++) {
        if (a.steps[s].gate != b.steps[s].gate) return false;
        if (!a.steps[s].gate) continue;
        if (a.steps[s].note != b.steps[s].note || a.steps[s].velocity != b.steps[s].velocity) return false;
    }
    return true;
}

}  // namespace

// 1. A LickBuilder solo opening a section seeds its live lick from THAT section's pool slot,
// not from the slot the outgoing section was playing.
static bool test_solo_seeds_from_incoming_slot() {
    printf("\n=== Test: a section's solo seeds from the incoming section's lick slot ===\n");
    const int8_t kSlot0[4] = {0, 1, 2, 3};
    const int8_t kSlot1[4] = {7, 9, 8, 10};
    OrpheusEngine* engine = make_seam_engine(4242, 0.0f);
    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    for (int i = 0; i < 4; i++) {
        float* a = &engine->pulsar_lick_pool_data[0 * (S * F) + i * F];
        float* b = &engine->pulsar_lick_pool_data[1 * (S * F) + i * F];
        a[0] = kSlot0[i]; a[1] = 0.5f; a[2] = 0.8f; a[3] = -1.0f;
        b[0] = kSlot1[i]; b[1] = 0.5f; b[2] = 0.8f; b[3] = -1.0f;
    }
    engine->pulsar_lick_pool_len[0] = 4; engine->pulsar_lick_pool_loop[0] = 16;
    engine->pulsar_lick_pool_len[1] = 4; engine->pulsar_lick_pool_loop[1] = 16;
    engine->pulsar_lick_anomaly_index = -1;
    engine->pulsar_lick_anomaly_chance = 0.0f;
    engine->pulsar_lick_pool_count.store(2, std::memory_order_release);
    engine->pulsar_track_lick_mode[4].store(2, std::memory_order_relaxed);
    const SeamSection secs[2] = {
        {1, SoloModeId::NONE, 0.0f, 0, 1},
        {32, SoloModeId::LICK_BUILDER, 1.0f, 1, 1},
    };
    push_seam_sections(engine, secs, 2);
    push_seam_band(engine, {4}, {}, 64);

    bool seen = false, ok = false;
    int got[4] = {-99, -99, -99, -99}, slot = -1, len = -1;
    run_bars(engine, 4000, [&](PulsarState* ps) {
        if (ps->section_state.current_section != 1 || !ps->band_solo_state.active) return true;
        seen = true;
        slot = ps->current_lick_index;
        len = ps->live_lick_length;
        ok = slot == 1 && len == 4;
        for (int i = 0; i < 4; i++) {
            got[i] = ps->live_lick_base_degrees[i];
            if (got[i] != kSlot1[i]) ok = false;
        }
        return false;
    });
    orpheus_engine_destroy(engine);
    const bool pass = seen && ok;
    printf("  slot=%d live_len=%d seed=[%d %d %d %d] (want [7 9 8 10]) -- %s\n",
           slot, len, got[0], got[1], got[2], got[3], pass ? "PASS" : "FAIL");
    return pass;
}

// 2. On a Jam handoff the outgoing soloist's lick track goes back to the bare figure, rather
// than looping its last ornamented bar.
static bool test_handoff_rerenders_outgoing_lead() {
    printf("\n=== Test: a handoff re-renders the outgoing soloist from the current slot ===\n");
    const int kLeadA = 4, kLeadB = 5;
    OrpheusEngine* engine = make_seam_engine(31337, 0.0f);
    arm_seam_hook(engine, {kLeadA, kLeadB});
    const SeamSection secs[2] = {
        {1, SoloModeId::NONE, 0.0f, -1, 1},
        {32, SoloModeId::JAM, 1.0f, -1, 1},
    };
    push_seam_sections(engine, secs, 2);
    give_jam_density(engine, {kLeadA, kLeadB});
    push_seam_band(engine, {kLeadA}, {kLeadB}, 2);

    int handoffs = 0, mismatches = 0, ornamented_leads = 0;
    run_bars(engine, 8000, [&](PulsarState* ps) {
        if (!ps->band_solo_state.active) return true;
        const int lead_track = ps->band_solo_state.lead_member == 1 ? kLeadA : kLeadB;
        const int out_track = lead_track == kLeadA ? kLeadB : kLeadA;
        for (int s = 0; s < ps->tracks[lead_track].step_count; s++)
            if (!is_seam_hook_step(s) && ps->tracks[lead_track].steps[s].gate) { ornamented_leads++; break; }
        if (!ps->band_solo_state.just_handed_off) return true;
        handoffs++;
        if (!same_steps(fresh_lick_render(engine, ps, out_track), snap(ps->tracks[out_track]))) mismatches++;
        return handoffs < 12;
    });
    orpheus_engine_destroy(engine);
    const bool pass = handoffs >= 8 && ornamented_leads > 0 && mismatches == 0;
    printf("  handoffs=%d ornamented lead bars=%d outgoing tracks off the fresh render=%d -- %s\n",
           handoffs, ornamented_leads, mismatches, pass ? "PASS" : "FAIL");
    return pass;
}

// 3. The deja-vu reset leaves a CHORDAL track's comping alone.
static bool test_dejavu_reset_skips_chordal() {
    printf("\n=== Test: the deja-vu reset does not rebuild a CHORDAL track ===\n");
    const int kChordal = 5;
    OrpheusEngine* engine = make_seam_engine(777, 0.0f);
    engine->pulsar_track_role[kChordal].store(static_cast<int>(TrackRole::CHORDAL), std::memory_order_relaxed);
    engine->pulsar_arrangement_active.store(0, std::memory_order_relaxed);

    StepSnap before;
    int bar = 0;
    bool steady = false, reset_fired = false, unchanged = false;
    run_bars(engine, 4000, [&](PulsarState* ps) {
        bar++;
        if (bar == 3) { before = snap(ps->tracks[kChordal]); return true; }
        if (bar == 4) {
            steady = same_steps(before, snap(ps->tracks[kChordal]));
            before = snap(ps->tracks[kChordal]);
            ps->loops_since_reset = 1000;  // the next bar boundary resets
            return true;
        }
        if (bar == 5) {
            reset_fired = ps->loops_since_reset == 0;
            unchanged = same_steps(before, snap(ps->tracks[kChordal]));
            return false;
        }
        return true;
    });
    orpheus_engine_destroy(engine);
    const bool pass = steady && reset_fired && unchanged;
    printf("  steady without a reset=%s reset fired=%s chordal unchanged=%s -- %s\n",
           steady ? "YES" : "NO", reset_fired ? "YES" : "NO", unchanged ? "YES" : "NO",
           pass ? "PASS" : "FAIL");
    return pass;
}

// 4. The deja-vu count keeps its global cadence across seams, but a section entry clamps it
// so no reset lands in a section's first kGuard bars. Short sections after a long one still
// get their resets, so drift never goes unreset for long.
static bool test_dejavu_count_restarts_at_section_entry() {
    printf("\n=== Test: deja-vu resets keep their cadence but skip a section's first bars ===\n");
    const int kGuard = 2;
    const float kComplexity = 0.5f;
    const int interval = std::max(8, static_cast<int>(32.0f * (1.0f - kComplexity)));
    OrpheusEngine* engine = make_seam_engine(99, kComplexity);
    const SeamSection secs[3] = {
        {21, SoloModeId::NONE, 0.0f, -1, 1},
        {6, SoloModeId::NONE, 0.0f, -1, 2},
        {7, SoloModeId::NONE, 0.0f, -1, 0},
    };
    push_seam_sections(engine, secs, 3);

    int last_section = -1, pos = 0, bars = 0, resets = 0, early = 0, entries = 0;
    int short_resets = 0, last_reset_bar = 0, max_gap = 0;
    run_bars(engine, 40000, [&](PulsarState* ps) {
        const int sec = ps->section_state.current_section;
        if (sec != last_section) { pos = 0; entries++; } else pos++;
        last_section = sec;
        // loop_count 0 is the vibe load, which zeroes the count without a reset.
        if (ps->loop_count > 0 && ps->loops_since_reset == 0) {
            resets++;
            if (pos < kGuard) early++;
            if (sec != 0) short_resets++;
            max_gap = std::max(max_gap, bars - last_reset_bar);
            last_reset_bar = bars;
        }
        return ++bars < 200;
    });
    orpheus_engine_destroy(engine);
    const bool pass = entries >= 15 && resets > 0 && early == 0 && short_resets > 0 &&
                      max_gap <= interval + kGuard;
    printf("  interval=%d bars=%d entries=%d resets=%d in short sections=%d early=%d "
           "max gap=%d (<= %d) -- %s\n", interval, bars, entries, resets, short_resets, early,
           max_gap, interval + kGuard, pass ? "PASS" : "FAIL");
    return pass;
}

// 5. Leaving a solo for a section with none drops the smoothed ducks and density offsets at
// once, instead of easing them 0.15 a bar into the new section. A positive volume lift keeps
// easing: it feeds the per-block texture lift, so a snap would drop a sustaining soloist.
static bool test_sololess_section_snaps_solo_offsets() {
    printf("\n=== Test: a solo-less section starts unducked; a soloist's lift still eases ===\n");
    const int kLead = 4;
    OrpheusEngine* engine = make_seam_engine(2024, 0.0f);
    const SeamSection secs[2] = {
        {4, SoloModeId::JAM, 1.0f, -1, 1},
        {4, SoloModeId::NONE, 0.0f, -1, 0},
    };
    push_seam_sections(engine, secs, 2);
    give_jam_density(engine, {kLead});
    push_seam_band(engine, {kLead}, {}, 64);

    int last_section = -1, entries = 0, ducks = 0, leaky = 0, lifts_kept = 0, lifts_wrong = 0;
    float prev_vol[kNumPulsarTracks] = {};
    run_bars(engine, 8000, [&](PulsarState* ps) {
        const int sec = ps->section_state.current_section;
        if (sec == 1 && last_section == 0) {
            entries++;
            for (int t = 0; t < kNumPulsarTracks; t++) {
                const float vol = ps->tracks[t].solo_volume_mod_current;
                if (ps->tracks[t].solo_density_mod_current != 0.0f) leaky++;
                if (prev_vol[t] <= 0.0f) {
                    if (prev_vol[t] < 0.0f) ducks++;
                    if (vol != 0.0f) leaky++;
                } else {
                    const float eased = std::max(0.0f, prev_vol[t] - kSoloModSlew);
                    if (std::fabs(vol - eased) > 1e-6f) lifts_wrong++;
                    else if (vol > 0.0f) lifts_kept++;
                }
            }
        }
        for (int t = 0; t < kNumPulsarTracks; t++) prev_vol[t] = ps->tracks[t].solo_volume_mod_current;
        last_section = sec;
        return entries < 4;
    });
    orpheus_engine_destroy(engine);
    const bool pass = entries >= 3 && ducks > 0 && leaky == 0 && lifts_kept > 0 && lifts_wrong == 0;
    printf("  entries=%d ducks seen=%d offsets left=%d lifts easing=%d lifts snapped or off=%d -- %s\n",
           entries, ducks, leaky, lifts_kept, lifts_wrong, pass ? "PASS" : "FAIL");
    return pass;
}

// 6. A Jam ornaments every lick track of the lead member, each a lickDegreeOffset apart, so
// a two-guitar member keeps its harmony line.
static bool test_jam_ornaments_every_lead_lick_track() {
    printf("\n=== Test: Jam ornaments both lick tracks of a two-track lead ===\n");
    const int kLow = 4, kHigh = 6, kOffset = 2;
    OrpheusEngine* engine = make_seam_engine(4242, 0.0f);
    engine->pulsar_track_role[kHigh].store(static_cast<int>(TrackRole::MELODIC), std::memory_order_relaxed);
    arm_seam_hook(engine, {kLow, kHigh});
    engine->pulsar_track_lick_degree_offset[kHigh].store(kOffset, std::memory_order_relaxed);
    const SeamSection secs[2] = {
        {1, SoloModeId::NONE, 0.0f, -1, 1},
        {32, SoloModeId::JAM, 1.0f, -1, 1},
    };
    push_seam_sections(engine, secs, 2);
    give_jam_density(engine, {kLow, kHigh});
    push_seam_band(engine, {kLow, kHigh}, {}, 64);

    const PulsarScale& sc = kPulsarScales[0];
    const int root = 2;
    int bars = 0, ornaments = 0, missing = 0, wrong_interval = 0;
    run_bars(engine, 20000, [&](PulsarState* ps) {
        if (!ps->band_solo_state.active || ps->section_state.current_section != 1) return true;
        const PulsarTrackState& lo = ps->tracks[kLow];
        const PulsarTrackState& hi = ps->tracks[kHigh];
        for (int s = 0; s < lo.step_count && s < hi.step_count; s++) {
            if (is_seam_hook_step(s) || !lo.steps[s].gate) continue;
            ornaments++;
            if (!hi.steps[s].gate) { missing++; continue; }
            int d = midi_to_lick_degree(hi.steps[s].note, root, sc)
                  - midi_to_lick_degree(lo.steps[s].note, root, sc);
            if (((d - kOffset) % sc.count + sc.count) % sc.count != 0) wrong_interval++;
        }
        return ++bars < 24;
    });
    orpheus_engine_destroy(engine);
    const bool pass = bars >= 16 && ornaments > 0 && missing == 0 && wrong_interval == 0;
    printf("  bars=%d lead ornaments=%d missing on the upper track=%d wrong interval=%d -- %s\n",
           bars, ornaments, missing, wrong_interval, pass ? "PASS" : "FAIL");
    return pass;
}

// 7. A seam that ends a solo also puts the outgoing soloist back on its figure, when no
// slot swap has re-rendered it already.
static bool test_seam_rerenders_outgoing_lead() {
    printf("\n=== Test: a seam that ends a Jam re-renders the soloist from the current slot ===\n");
    const int kLead = 4;
    OrpheusEngine* engine = make_seam_engine(5150, 0.0f);
    arm_seam_hook(engine, {kLead});
    const SeamSection secs[2] = {
        {8, SoloModeId::JAM, 1.0f, -1, 1},
        {4, SoloModeId::NONE, 0.0f, -1, 0},
    };
    push_seam_sections(engine, secs, 2);
    give_jam_density(engine, {kLead});
    push_seam_band(engine, {kLead}, {}, 64);

    int last_section = -1, entries = 0, ornamented_exits = 0, mismatches = 0;
    bool last_bar_ornamented = false;
    run_bars(engine, 8000, [&](PulsarState* ps) {
        const int sec = ps->section_state.current_section;
        if (sec == 1 && last_section == 0) {
            entries++;
            if (last_bar_ornamented) ornamented_exits++;
            if (!same_steps(fresh_lick_render(engine, ps, kLead), snap(ps->tracks[kLead]))) mismatches++;
        }
        last_bar_ornamented = false;
        if (sec == 0 && ps->band_solo_state.active)
            for (int s = 0; s < ps->tracks[kLead].step_count; s++)
                if (!is_seam_hook_step(s) && ps->tracks[kLead].steps[s].gate) last_bar_ornamented = true;
        last_section = sec;
        return entries < 4;
    });
    orpheus_engine_destroy(engine);
    const bool pass = entries >= 3 && ornamented_exits > 0 && mismatches == 0;
    printf("  entries=%d entered from an ornamented bar=%d soloist off the fresh render=%d -- %s\n",
           entries, ornamented_exits, mismatches, pass ? "PASS" : "FAIL");
    return pass;
}

// 8. A generative CALL_RESPONSE track renders the vibe's lick with its lickDegreeOffset at
// load, at a section's density regen and at the deja-vu reset. Two runs, offset 0 and +2,
// must differ by exactly two scale degrees on every sounding step.
enum class CrPath { LOAD, SECTION_DENSITY, DEJAVU };

static StepSnap call_response_render(int offset, CrPath path) {
    const int kTrack = 5;
    OrpheusEngine* engine = make_seam_engine(2718, 0.0f);
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    const int8_t degrees[4] = {0, 1, 2, 4};
    for (int i = 0; i < 4; i++) engine->pulsar_lick[i] = {degrees[i], 1.0f, 0.8f, -1.0f};
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    engine->pulsar_track_lick_mode[kTrack].store(0, std::memory_order_relaxed);  // NONE: generative
    engine->pulsar_track_bar_strategy[kTrack].store(static_cast<int>(BarStrategy::CALL_RESPONSE), std::memory_order_relaxed);
    engine->pulsar_track_lick_degree_offset[kTrack].store(offset, std::memory_order_relaxed);
    if (path == CrPath::SECTION_DENSITY) {
        const SeamSection secs[2] = {{2, SoloModeId::NONE, 0.0f, -1, 1}, {4, SoloModeId::NONE, 0.0f, -1, 0}};
        push_seam_sections(engine, secs, 2);
        for (int i = 0; i < kMaxSections * kNumPulsarTracks; i++)
            engine->pulsar_section_track_density[i].store(-1.0f, std::memory_order_relaxed);
        engine->pulsar_section_track_density[1 * kNumPulsarTracks + kTrack].store(0.33f, std::memory_order_relaxed);
    } else {
        engine->pulsar_arrangement_active.store(0, std::memory_order_relaxed);
    }

    StepSnap out;
    int bar = 0;
    run_bars(engine, 8000, [&](PulsarState* ps) {
        bar++;
        switch (path) {
            case CrPath::LOAD:
                out = snap(ps->tracks[kTrack]);
                return false;
            case CrPath::SECTION_DENSITY:
                if (ps->section_state.current_section != 1) return true;
                if (ps->tracks[kTrack].generated_density == 0.33f) out = snap(ps->tracks[kTrack]);
                return false;
            case CrPath::DEJAVU:
                if (bar == 2) { ps->loops_since_reset = 1000; return true; }
                if (bar == 3 && ps->loops_since_reset == 0) out = snap(ps->tracks[kTrack]);
                return bar < 3;
        }
        return false;
    });
    orpheus_engine_destroy(engine);
    return out;
}

static bool test_call_response_keeps_degree_offset() {
    printf("\n=== Test: a generative CALL_RESPONSE track keeps its lickDegreeOffset on every render ===\n");
    const PulsarScale& sc = kPulsarScales[0];  // the fixture's minor scale on D
    const uint8_t root = 2;
    bool pass = true;
    const CrPath paths[3] = {CrPath::LOAD, CrPath::SECTION_DENSITY, CrPath::DEJAVU};
    const char* names[3] = {"load", "section density", "deja-vu reset"};
    for (int p = 0; p < 3; p++) {
        const StepSnap a = call_response_render(0, paths[p]);
        const StepSnap b = call_response_render(2, paths[p]);
        int sounding = 0, wrong = 0;
        bool shape = a.count > 16 && a.count == b.count;
        for (int s = 0; shape && s < a.count; s++) {
            if (a.steps[s].gate != b.steps[s].gate) { shape = false; break; }
            if (!a.steps[s].gate) continue;
            sounding++;
            const int d = midi_to_lick_degree(b.steps[s].note, root, sc) - midi_to_lick_degree(a.steps[s].note, root, sc);
            if (((d - 2) % sc.count + sc.count) % sc.count != 0) wrong++;
        }
        const bool ok = shape && sounding >= 8 && wrong == 0;
        printf("  %s: steps=%d sounding=%d off by other than 2 degrees=%d -- %s\n",
               names[p], a.count, sounding, wrong, ok ? "OK" : "FAIL");
        pass = pass && ok;
    }
    printf("  Overall -- %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_pulsar_section_seams_tests() {
    printf("\n=== Pulsar Section Seams ===\n");
    int passed = 0, failed = 0;
    auto run = [&](bool (*fn)()) { if (fn()) passed++; else failed++; };
    run(test_solo_seeds_from_incoming_slot);
    run(test_handoff_rerenders_outgoing_lead);
    run(test_dejavu_reset_skips_chordal);
    run(test_dejavu_count_restarts_at_section_entry);
    run(test_sololess_section_snaps_solo_offsets);
    run(test_jam_ornaments_every_lead_lick_track);
    run(test_seam_rerenders_outgoing_lead);
    run(test_call_response_keeps_degree_offset);
    printf("\n  Pulsar Section Seams: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
