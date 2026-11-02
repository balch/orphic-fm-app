// Live track input (Lick Lab): an armed track drops its sequenced notes and plays the
// live note, gate, strike, velocity and glide atomics through its own engine.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_band_solo.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>

namespace {

constexpr int kTrack = 4;
constexpr int kOther = 3;
constexpr int kFrames = 256;
constexpr int kBarBlocks = 375;  // 120 BPM: 96000 samples a bar
constexpr int kSettle = 40;

struct Rig {
    OrpheusEngine* e = nullptr;
    GraphUnit unit{};
    std::unique_ptr<OrpheusEngine::PulsarMeasureTaps> taps;

    explicit Rig(bool armed, int engine = 9) {
        e = orpheus_engine_create(48000.0f);
        e->pulsar_playing.store(1, std::memory_order_relaxed);
        e->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_fixture_baseline(e);
        // The keys track (melodic) plays on the bass engine: a plain voice, not CHD's chords.
        e->pulsar_track_engine_edm[kTrack].store(engine, std::memory_order_relaxed);
        e->pulsar_track_engine_space[kTrack].store(engine, std::memory_order_relaxed);
        // Without an override a C++ fixture's generated melodic pattern is empty.
        e->pulsar_track_density_override[kTrack].store(0.8f, std::memory_order_relaxed);
        pin_pulsar_rngs(e);
        e->clock_bpm.store(120.0f, std::memory_order_relaxed);
        e->pulsar_energy.store(0.6f, std::memory_order_relaxed);
        if (armed) e->pulsar_track_live_active[kTrack].store(1, std::memory_order_relaxed);
        taps = std::make_unique<OrpheusEngine::PulsarMeasureTaps>();
        e->pulsar_measure_taps.store(taps.get(), std::memory_order_release);
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;
        trigger_vibe_load(e);
    }
    ~Rig() {
        e->pulsar_measure_taps.store(nullptr, std::memory_order_release);
        orpheus_engine_destroy(e);
    }

    // Runs one block; returns the peak of track t's dry output in it.
    float block(int t = kTrack) {
        unit_process_pulsar(&unit, e, kFrames, 48000.0f);
        float p = 0.0f;
        for (int i = 0; i < kFrames; i++) p = std::max(p, std::fabs(taps->track_l[t][i]));
        return p;
    }
    void run(int n) { for (int i = 0; i < n; i++) block(); }

    int note_ons() const { return e->pulsar_track_note_on_debug[kTrack].load(); }
    float note() const { return e->pulsar_track_note_debug[kTrack].load(); }
    const PulsarTrackState& ts() const { return e->pulsar_state->tracks[kTrack]; }
    void strike(float note, int count) {
        e->pulsar_track_live_note[kTrack].store(note, std::memory_order_relaxed);
        e->pulsar_track_live_gate[kTrack].store(1, std::memory_order_relaxed);
        e->pulsar_track_live_strike[kTrack].store(count, std::memory_order_relaxed);
    }
};

}  // namespace

static bool test_ports_route() {
    printf("\n=== Test: track_N_live_* ports reach the atomics ===\n");
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    bool ok = e->pulsar_track_live_active[0].load() == 0 && e->pulsar_track_live_gate[0].load() == 0;
    const char* kPulsar = "org.balch.orpheus.plugins.pulsar";
    for (int t = 0; t < 8; t++) {
        char sym[48];
        auto set = [&](const char* p, float v) {
            std::snprintf(sym, sizeof(sym), "track_%d_%s", t, p);
            orpheus_engine_set_port(e, kPulsar, sym, v);
        };
        set("live_active", 1.0f);
        set("live_note", 60.5f + t);
        set("live_gate", 1.0f);
        set("live_strike", 7.0f + t);
        set("live_velocity", 0.25f);
        set("live_glide", 0.4f);
        set("live_boost", 2.5f);
        ok &= e->pulsar_track_live_active[t].load() == 1
           && e->pulsar_track_live_note[t].load() == 60.5f + t
           && e->pulsar_track_live_gate[t].load() == 1
           && e->pulsar_track_live_strike[t].load() == 7 + t
           && e->pulsar_track_live_velocity[t].load() == 0.25f
           && e->pulsar_track_live_glide[t].load() == 0.4f
           && e->pulsar_track_live_boost[t].load() == 2.5f;
    }
    // The boost is clamped to 1..4.
    orpheus_engine_set_port(e, kPulsar, "track_0_live_boost", 0.2f);
    ok &= e->pulsar_track_live_boost[0].load() == 1.0f;
    orpheus_engine_set_port(e, kPulsar, "track_0_live_boost", 9.0f);
    ok &= e->pulsar_track_live_boost[0].load() == 4.0f;
    orpheus_engine_destroy(e);
    printf("Routing: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_armed_track_drops_sequenced_notes() {
    printf("\n=== Test: an armed track with its gate down is silent; the others play ===\n");
    float ref_peak = 0.0f, armed_peak = 0.0f, other_peak = 0.0f;
    {
        Rig ref(false);
        for (int b = 0; b < 4 * kBarBlocks; b++) {
            const float p = ref.block();
            if (b >= kSettle) ref_peak = std::max(ref_peak, p);
        }
    }
    Rig armed(true);
    for (int b = 0; b < 4 * kBarBlocks; b++) {
        unit_process_pulsar(&armed.unit, armed.e, kFrames, 48000.0f);
        for (int i = 0; i < kFrames; i++) {
            armed_peak = std::max(armed_peak, std::fabs(armed.taps->track_l[kTrack][i]));
            if (b >= kSettle) other_peak = std::max(other_peak, std::fabs(armed.taps->track_l[kOther][i]));
        }
    }
    const bool ok = ref_peak > 0.01f && armed_peak < 1e-4f && other_peak > 0.01f
                 && armed.note_ons() == 0;
    printf("  unarmed peak %.4f, armed peak %.6f, track %d peak %.4f, note-ons %d\n",
           ref_peak, armed_peak, kOther, other_peak, armed.note_ons());
    printf("Armed silent: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_live_strike_plays_note() {
    printf("\n=== Test: gate 1 + strike plays the note within one block ===\n");
    Rig r(true);
    r.run(kSettle);
    const int before = r.note_ons();
    r.strike(71.0f, 1);
    r.block();
    const bool fired = r.note_ons() == before + 1 && r.note() == 71.0f && r.ts().voice_active;
    float peak = 0.0f;
    for (int b = 0; b < 20; b++) peak = std::max(peak, r.block());
    const bool ok = fired && peak > 0.01f && r.ts().voice_active && r.note_ons() == before + 1;
    printf("  note-ons %d -> %d, note %.2f, peak %.4f, held %d\n",
           before, r.note_ons(), r.note(), peak, r.ts().voice_active);
    printf("Strike plays: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_restrike_same_note_retriggers() {
    printf("\n=== Test: a new strike on the same held note retriggers ===\n");
    Rig r(true);
    r.run(kSettle);
    r.strike(71.0f, 1);
    r.run(30);
    const int held = r.note_ons();
    r.strike(71.0f, 2);
    r.block();
    const int after = r.note_ons();
    r.run(10);
    const bool ok = after == held + 1 && r.note_ons() == after && r.ts().voice_active;
    printf("  note-ons held %d, after restrike %d, 10 blocks later %d\n", held, after, r.note_ons());
    printf("Restrike: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_legato_note_change_no_retrigger() {
    printf("\n=== Test: a note change without a strike is legato, glide per live_glide ===\n");
    Rig r(true);
    r.run(kSettle);
    r.strike(71.0f, 1);
    r.run(10);
    const int ons = r.note_ons();
    // Glide 0: a hammer-on lands at once.
    r.e->pulsar_track_live_note[kTrack].store(74.0f, std::memory_order_relaxed);
    r.block();
    const bool instant = r.note() == 74.0f && r.note_ons() == ons;
    // Glide 0.5: the pitch travels over blocks and arrives.
    r.e->pulsar_track_live_glide[kTrack].store(0.5f, std::memory_order_relaxed);
    r.e->pulsar_track_live_note[kTrack].store(77.0f, std::memory_order_relaxed);
    r.block();
    const float mid = r.note();
    r.run(200);
    const float end = r.note();
    const bool glided = mid > 74.0f && mid < 77.0f && end == 77.0f;
    const bool ok = instant && glided && r.note_ons() == ons && r.ts().voice_active;
    printf("  hammer-on note %.2f, glide first block %.3f, settled %.3f, note-ons %d -> %d\n",
           r.note(), mid, end, ons, r.note_ons());
    printf("Legato: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_sub_block_tap_still_strikes() {
    printf("\n=== Test: a press and release inside one block still sounds for a block ===\n");
    Rig r(true);
    r.run(kSettle);
    const int before = r.note_ons();
    // The UI wrote strike, gate 1, then gate 0 before the audio thread read any of it.
    r.strike(71.0f, 1);
    r.e->pulsar_track_live_gate[kTrack].store(0, std::memory_order_relaxed);
    const float peak = r.block();
    const bool fired = r.note_ons() == before + 1 && r.note() == 71.0f;
    r.block();
    const bool released = !r.ts().voice_active && r.note_ons() == before + 1;
    // Re-arming with that count still in the port is not a new strike.
    r.e->pulsar_track_live_active[kTrack].store(0, std::memory_order_relaxed);
    r.run(5);
    r.e->pulsar_track_live_strike[kTrack].store(2, std::memory_order_relaxed);
    const int seq_ons = r.note_ons();
    r.e->pulsar_track_live_active[kTrack].store(1, std::memory_order_relaxed);
    r.block();
    const bool no_phantom = r.note_ons() == seq_ons && !r.ts().voice_active;
    const bool ok = fired && peak > 0.0f && released && no_phantom;
    printf("  note-ons %d -> %d, note %.2f, peak %.4f, released %d, no phantom on re-arm %d\n",
           before, before + (fired ? 1 : 0), r.note(), peak, released, no_phantom);
    printf("Sub-block tap: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_strike_a_block_before_gate_strikes_once() {
    printf("\n=== Test: a count stored a block before its gate is one note-on ===\n");
    Rig r(true);
    r.run(kSettle);
    const int before = r.note_ons();
    // A block boundary fell between the strike and gate stores.
    r.e->pulsar_track_live_note[kTrack].store(71.0f, std::memory_order_relaxed);
    r.e->pulsar_track_live_strike[kTrack].store(1, std::memory_order_relaxed);
    r.block();
    const int first = r.note_ons();
    r.e->pulsar_track_live_gate[kTrack].store(1, std::memory_order_relaxed);
    r.block();
    const int second = r.note_ons();
    r.run(10);
    const bool held = r.ts().voice_active;
    // The next press is a new note-on again.
    r.e->pulsar_track_live_gate[kTrack].store(0, std::memory_order_relaxed);
    r.block();
    r.strike(72.0f, 2);
    r.block();
    const bool ok = first == before + 1 && second == first && held
                 && r.note_ons() == first + 1 && r.note() == 72.0f;
    printf("  note-ons %d -> %d (count) -> %d (gate) -> %d (next press), held %d\n",
           before, first, second, r.note_ons(), held);
    printf("Count before gate: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_engine_swap_waits_for_live_strike() {
    printf("\n=== Test: an engine change under a held live note waits for the next strike ===\n");
    Rig r(true);
    r.run(kSettle);
    r.strike(71.0f, 1);
    r.run(10);
    const int ons = r.note_ons();
    r.e->pulsar_track_engine_edm[kTrack].store(8, std::memory_order_relaxed);
    r.e->pulsar_track_engine_space[kTrack].store(8, std::memory_order_relaxed);
    r.run(3 * kBarBlocks);
    const int held_engine = r.e->pulsar_track_active_engine[kTrack].load();
    const bool no_retrig = r.note_ons() == ons && r.ts().voice_active;
    r.strike(71.0f, 2);
    r.block();
    const int struck_engine = r.e->pulsar_track_active_engine[kTrack].load();
    const bool ok = no_retrig && held_engine == 9 && struck_engine == 8 && r.note_ons() == ons + 1;
    printf("  note-ons over 3 held bars %d -> %d, engine held %d, after strike %d\n",
           ons, r.note_ons() - 1, held_engine, struck_engine);
    printf("Swap waits for strike: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_disarm_returns_to_sequencer() {
    printf("\n=== Test: disarm releases the held note and the sequencer plays on ===\n");
    Rig r(true);
    r.run(kSettle);
    r.strike(71.0f, 1);
    r.run(30);
    const bool held = r.ts().voice_active;
    // The UI's gate can still read 1 at disarm; the voice must not stay on it.
    r.e->pulsar_track_live_active[kTrack].store(0, std::memory_order_relaxed);
    const int ons = r.note_ons();
    bool stuck = false;
    int first_seq = -1;
    for (int b = 0; b < kBarBlocks; b++) {
        r.block();
        const PulsarTrackState& ts = r.ts();
        if (ts.voice_active && ts.gate_timer <= 0.0f && !ts.in_hold) stuck = true;
        if (first_seq < 0 && r.note_ons() > ons) first_seq = b;
    }
    const bool ok = held && !stuck && first_seq >= 0;
    printf("  held before %d, stuck %d, first sequenced note-on %d blocks after disarm\n",
           held, stuck, first_seq);
    printf("Disarm: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_boost_scales_armed_track_only() {
    printf("\n=== Test: boost lifts the armed track about 4 dB; an unarmed track is untouched ===\n");
    auto armed_peak = [](float boost) {
        Rig r(true);
        r.e->pulsar_track_live_boost[kTrack].store(boost, std::memory_order_relaxed);
        r.run(kSettle);
        r.strike(71.0f, 1);
        float peak = 0.0f;
        for (int b = 0; b < 20; b++) peak = std::max(peak, r.block());
        return peak;
    };
    auto seq_peak = [](float boost) {
        Rig r(false);
        r.e->pulsar_track_live_boost[kTrack].store(boost, std::memory_order_relaxed);
        float peak = 0.0f;
        for (int b = 0; b < 4 * kBarBlocks; b++) {
            const float p = r.block();
            if (b >= kSettle) peak = std::max(peak, p);
        }
        return peak;
    };
    const float unity = armed_peak(1.0f);
    const float boosted = armed_peak(1.585f);
    const float db = 20.0f * std::log10(boosted / std::max(unity, 1e-9f));
    const float seq_ref = seq_peak(1.0f);
    const float seq_boosted = seq_peak(1.585f);
    const bool ok = unity > 0.01f && std::fabs(db - 4.0f) < 0.5f
                 && seq_ref > 0.01f && seq_boosted == seq_ref;
    printf("  armed %.4f -> %.4f (%.2f dB), unarmed %.4f -> %.4f\n",
           unity, boosted, db, seq_ref, seq_boosted);
    printf("Boost: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_paused_armed_track_still_plays() {
    printf("\n=== Test: paused, the armed track plays, the rest is silent and the clock holds ===\n");
    Rig r(true);
    r.run(kSettle);
    r.e->pulsar_playing.store(0, std::memory_order_relaxed);
    r.run(4);
    const int playhead = r.e->pulsar_state->tracks[0].playhead;
    const double acc = r.e->pulsar_state->clock_accumulator;
    r.strike(71.0f, 1);
    float live = 0.0f, other = 0.0f;
    for (int b = 0; b < kBarBlocks; b++) {
        live = std::max(live, r.block());
        for (int i = 0; i < kFrames; i++) other = std::max(other, std::fabs(r.taps->track_l[kOther][i]));
    }
    const bool held = r.e->pulsar_state->tracks[0].playhead == playhead
                   && r.e->pulsar_state->clock_accumulator == acc;
    // Disarmed while paused, the whole unit goes quiet again.
    r.e->pulsar_track_live_active[kTrack].store(0, std::memory_order_relaxed);
    float quiet = 0.0f;
    for (int b = 0; b < 4; b++) {
        unit_process_pulsar(&r.unit, r.e, kFrames, 48000.0f);
        for (int i = 0; i < kFrames; i++) quiet = std::max(quiet, std::fabs(r.unit.output_buffers[0][i]));
    }
    const bool ok = live > 0.01f && other < 1e-4f && held && quiet == 0.0f;
    printf("  live peak %.4f, track %d peak %.6f, clock held %d, disarmed peak %.6f\n",
           live, kOther, other, held, quiet);
    printf("Paused noodle: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_density_out_mutes_only_unarmed_tracks() {
    printf("\n=== Test: a section's density 0 mutes the sequencer, never the live player ===\n");
    Rig r(true);
    r.run(kSettle);
    // The flag a `density = 0` section sets on entry, on the armed track and an unarmed one.
    r.e->pulsar_state->tracks[kTrack].section_density_out = true;
    r.e->pulsar_state->tracks[kOther].section_density_out = true;
    r.strike(71.0f, 1);
    float live = 0.0f, other = 0.0f;
    for (int b = 0; b < kBarBlocks; b++) {
        live = std::max(live, r.block());
        for (int i = 0; i < kFrames; i++) other = std::max(other, std::fabs(r.taps->track_l[kOther][i]));
    }
    const bool ok = live > 0.01f && other < 1e-4f;
    printf("  live peak %.4f, unarmed track %d peak %.6f\n", live, kOther, other);
    printf("Density out: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_handoff_skips_armed_track() {
    printf("\n=== Test: a solo handoff never lands on an armed track ===\n");
    BandSoloConfigParam config{};
    config.probability = 1.0f;
    config.member_count = 3;
    config.bars_per_lead_min = 1;
    config.bars_per_lead_max = 2;
    config.members[0].tracks[0] = 0;  // drums
    config.members[0].track_count = 1;
    config.members[0].always_active = true;
    config.members[1].tracks[0] = 3;
    config.members[1].track_count = 1;
    config.members[2].tracks[0] = 4;
    config.members[2].track_count = 1;
    for (int a = 0; a < 3; a++)
        for (int b = 0; b < 3; b++)
            config.handoff_matrix[a * kMaxBandMembers + b] = (a == b) ? 0.0f : 1.0f;

    bool ok = true;
    int bars = 0, drum_leads = 0;
    for (SoloModeId mode : {SoloModeId::JAM, SoloModeId::LICK_BUILDER}) {
        SectionParam section{};
        section.solo_mode = mode;
        section.solo_probability = 1.0f;
        for (uint32_t s = 1; s <= 40; s++) {
            PulsarTrackState tracks[kNumPulsarTracks]{};
            tracks[3].role = TrackRole::MELODIC;
            tracks[4].role = TrackRole::MELODIC;
            tracks[4].live_armed = true;   // member 2's only track
            BandSoloState state{};
            uint32_t seed = s * 7919u;
            start_band_solo(state, config, section, tracks, seed);
            ok &= state.lead_member != 2;
            for (int bar = 0; bar < 32; bar++, bars++) {
                advance_band_solo(state, config, section, tracks, seed);
                ok &= state.lead_member != 2;
                if (state.lead_member == 0) drum_leads++;
            }
            // Arming the drums takes the kit out of drum leads too.
            tracks[0].live_armed = true;
            for (int bar = 0; bar < 32; bar++) {
                const int prev = state.lead_member;
                advance_band_solo(state, config, section, tracks, seed);
                if (state.lead_member != prev) ok &= state.lead_member == 1;
            }
        }
    }
    ok &= drum_leads > 0;
    printf("  %d bars, drum leads before arming the kit %d\n", bars, drum_leads);
    printf("Handoff skips armed: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_track_note_readback_follows_live_note() {
    printf("\n=== Test: the track note readback follows the live note and gate ===\n");
    Rig r(true);
    r.run(kSettle);
    float notes[8];
    orpheus_engine_get_pulsar_track_notes(r.e, notes);
    const bool idle = notes[kTrack] == -1.0f;
    r.strike(74.0f, 1);
    r.block();
    orpheus_engine_get_pulsar_track_notes(r.e, notes);
    const float held = notes[kTrack];
    r.e->pulsar_track_live_gate[kTrack].store(0, std::memory_order_relaxed);
    r.block();
    orpheus_engine_get_pulsar_track_notes(r.e, notes);
    const float released = notes[kTrack];
    // Mid-glide the readback is the travelling pitch, not either endpoint.
    r.strike(71.0f, 2);
    r.run(10);
    r.e->pulsar_track_live_glide[kTrack].store(0.5f, std::memory_order_relaxed);
    r.e->pulsar_track_live_note[kTrack].store(77.0f, std::memory_order_relaxed);
    r.block();
    orpheus_engine_get_pulsar_track_notes(r.e, notes);
    const float gliding = notes[kTrack];
    const bool ok = idle && held == 74.0f && released == -1.0f && gliding > 71.0f && gliding < 77.0f;
    printf("  idle %d, held %.2f, released %.2f, mid-glide %.3f\n", idle, held, released, gliding);
    printf("Track note readback: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_track_note_readback_includes_floor_fold() {
    printf("\n=== Test: a live note below the engine's note_min reads back folded up ===\n");
    Rig r(true, 8);  // VA: note_min 40
    r.run(kSettle);
    r.strike(30.0f, 1);
    r.block();
    float notes[8];
    orpheus_engine_get_pulsar_track_notes(r.e, notes);
    const bool ok = notes[kTrack] == 42.0f && r.note() == 42.0f;
    printf("  played 30, readback %.2f, rendered %.2f\n", notes[kTrack], r.note());
    printf("Floor fold readback: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_chord_root_readback_tracks_progression() {
    printf("\n=== Test: the chord root readback walks a 2-chord progression bar by bar ===\n");
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    const bool unset = orpheus_engine_get_pulsar_chord_root_degree(e) == -1;
    e->pulsar_playing.store(1, std::memory_order_relaxed);
    e->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(e);
    pin_pulsar_rngs(e);
    e->clock_bpm.store(120.0f, std::memory_order_relaxed);
    e->pulsar_complexity.store(0.0f, std::memory_order_relaxed);  // no Markov mutation
    e->pulsar_genre_chords_per_bar.store(1, std::memory_order_relaxed);
    e->pulsar_custom_progression[0].store(2, std::memory_order_relaxed);
    e->pulsar_custom_progression[1].store(5, std::memory_order_relaxed);
    e->pulsar_custom_progression_length.store(2, std::memory_order_relaxed);
    e->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);
    GraphUnit unit{};
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    trigger_vibe_load(e);

    // Runs of equal readbacks: each should be one bar long and alternate 2, 5, 2, 5.
    int runs[8] = {}, lens[8] = {}, n_runs = 0;
    bool matches_state = true;
    for (int b = 0; b < 4 * kBarBlocks + kBarBlocks / 2; b++) {
        unit_process_pulsar(&unit, e, kFrames, 48000.0f);
        const int d = orpheus_engine_get_pulsar_chord_root_degree(e);
        const PulsarChordState& cs = e->pulsar_state->chord_state;
        matches_state &= d == cs.progression[cs.chord_index];
        if (n_runs == 0 || runs[n_runs - 1] != d) {
            if (n_runs == 8) break;
            runs[n_runs++] = d;
        }
        lens[n_runs - 1]++;
    }
    bool ok = unset && matches_state && n_runs >= 4;
    for (int i = 0; i < n_runs; i++) {
        ok &= runs[i] == (i % 2 == 0 ? 2 : 5);
        // Within 2%: swing and the elastic clock move the bar line a few blocks.
        if (i > 0 && i < n_runs - 1) ok &= std::abs(lens[i] - kBarBlocks) <= kBarBlocks / 50;
        printf("  run %d: degree %d for %d blocks\n", i, runs[i], lens[i]);
    }
    orpheus_engine_destroy(e);
    printf("  unset before play %d, matches chord state %d\n", unset, matches_state);
    printf("Chord root readback: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static float track_note(OrpheusEngine* e) {
    float notes[8];
    orpheus_engine_get_pulsar_track_notes(e, notes);
    return notes[kTrack];
}

static bool test_ghost_port_routes() {
    printf("\n=== Test: track_N_live_ghost reaches its atomic ===\n");
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    const bool off = e->pulsar_track_live_ghost[5].load() == 0;
    orpheus_engine_set_port(e, "org.balch.orpheus.plugins.pulsar", "track_5_live_ghost", 1.0f);
    const bool ok = off && e->pulsar_track_live_ghost[5].load() == 1 && e->pulsar_track_live_ghost[4].load() == 0;
    orpheus_engine_destroy(e);
    printf("Ghost port: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_ghost_reads_back_the_pattern_it_would_play() {
    printf("\n=== Test: an armed ghost reads back the notes the unarmed track really plays ===\n");
    Rig played(false), ghost(true);
    ghost.e->pulsar_track_live_ghost[kTrack].store(1, std::memory_order_relaxed);
    int blocks = 0, sounding = 0, differ = 0;
    for (int b = 0; b < kBarBlocks * 8; b++) {
        played.block();
        ghost.block();
        const float want = track_note(played.e), got = track_note(ghost.e);
        blocks++;
        if (want >= 0.0f) sounding++;
        if (want != got) differ++;
    }
    const bool ok = sounding > kBarBlocks && differ == 0;
    printf("  %d blocks, %d sounding, %d differ\n", blocks, sounding, differ);
    printf("Ghost matches pattern: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_ghost_is_unheard() {
    printf("\n=== Test: a ghost makes no note-ons and no sound while the player is silent ===\n");
    Rig r(true);
    r.e->pulsar_track_live_ghost[kTrack].store(1, std::memory_order_relaxed);
    r.run(kSettle);
    const int before = r.note_ons();
    float peak = 0.0f;
    int sounding = 0;
    for (int b = 0; b < kBarBlocks * 4; b++) {
        peak = std::max(peak, r.block());
        if (track_note(r.e) >= 0.0f) sounding++;
    }
    const bool ok = sounding > 0 && r.note_ons() == before && peak < 1e-4f && !r.ts().voice_active;
    printf("  %d blocks with a ghost note, note-ons +%d, peak %.6f\n", sounding, r.note_ons() - before, peak);
    printf("Ghost unheard: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_ghost_leaves_the_live_voice_untouched() {
    printf("\n=== Test: the live voice renders bit for bit the same with a ghost running ===\n");
    Rig plain(true), ghost(true);
    ghost.e->pulsar_track_live_ghost[kTrack].store(1, std::memory_order_relaxed);
    int differ = 0, count = 0;
    float peak = 0.0f;
    for (int b = 0; b < kBarBlocks * 4; b++) {
        // A press every half bar, held for a quarter of a bar, with a legato on the way.
        const int phase = b % (kBarBlocks / 2);
        if (phase == 0) {
            count++;
            const float note = 60.0f + static_cast<float>(count % 7);
            plain.strike(note, count);
            ghost.strike(note, count);
        } else if (phase == kBarBlocks / 8) {
            plain.e->pulsar_track_live_note[kTrack].store(72.0f, std::memory_order_relaxed);
            ghost.e->pulsar_track_live_note[kTrack].store(72.0f, std::memory_order_relaxed);
        } else if (phase == kBarBlocks / 4) {
            plain.e->pulsar_track_live_gate[kTrack].store(0, std::memory_order_relaxed);
            ghost.e->pulsar_track_live_gate[kTrack].store(0, std::memory_order_relaxed);
        }
        peak = std::max(peak, plain.block());
        ghost.block();
        if (std::memcmp(plain.taps->track_l[kTrack], ghost.taps->track_l[kTrack], kFrames * sizeof(float)) != 0) differ++;
    }
    const bool ok = peak > 0.01f && differ == 0 && plain.note_ons() == ghost.note_ons();
    printf("  peak %.4f, %d blocks differ, note-ons %d vs %d\n", peak, differ, plain.note_ons(), ghost.note_ons());
    printf("Live voice untouched: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_ghost_off_reads_the_live_note_again() {
    printf("\n=== Test: turning the ghost off hands the readback back to the live note ===\n");
    Rig r(true);
    r.e->pulsar_track_live_ghost[kTrack].store(1, std::memory_order_relaxed);
    r.run(kSettle);
    r.strike(74.0f, 1);
    r.run(2);
    r.e->pulsar_track_live_ghost[kTrack].store(0, std::memory_order_relaxed);
    r.block();
    const bool ok = track_note(r.e) == 74.0f;
    printf("  readback %.2f\n", track_note(r.e));
    printf("Ghost off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_ghost_silent_in_density_out_and_pause() {
    printf("\n=== Test: a density 0 section and a pause show no ghost notes ===\n");
    auto sounding_blocks = [](Rig& r) {
        int n = 0;
        for (int b = 0; b < kBarBlocks * 2; b++) {
            r.block();
            if (track_note(r.e) >= 0.0f) n++;
        }
        return n;
    };
    Rig out(true), paused(true);
    out.e->pulsar_track_live_ghost[kTrack].store(1, std::memory_order_relaxed);
    paused.e->pulsar_track_live_ghost[kTrack].store(1, std::memory_order_relaxed);
    out.run(kSettle);
    paused.run(kBarBlocks);
    out.e->pulsar_state->tracks[kTrack].section_density_out = true;
    const int in_out = sounding_blocks(out);
    paused.e->pulsar_playing.store(0, std::memory_order_relaxed);
    const int in_pause = sounding_blocks(paused);
    const bool ok = in_out == 0 && in_pause == 0;
    printf("  sounding blocks: density out %d, paused %d\n", in_out, in_pause);
    printf("Ghost silent sections: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_live_input_tests() {
    printf("\n========== PULSAR LIVE INPUT TESTS ==========\n");
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };
    tally(test_ports_route());
    tally(test_armed_track_drops_sequenced_notes());
    tally(test_live_strike_plays_note());
    tally(test_restrike_same_note_retriggers());
    tally(test_legato_note_change_no_retrigger());
    tally(test_sub_block_tap_still_strikes());
    tally(test_strike_a_block_before_gate_strikes_once());
    tally(test_engine_swap_waits_for_live_strike());
    tally(test_disarm_returns_to_sequencer());
    tally(test_boost_scales_armed_track_only());
    tally(test_paused_armed_track_still_plays());
    tally(test_density_out_mutes_only_unarmed_tracks());
    tally(test_handoff_skips_armed_track());
    tally(test_track_note_readback_follows_live_note());
    tally(test_track_note_readback_includes_floor_fold());
    tally(test_chord_root_readback_tracks_progression());
    tally(test_ghost_port_routes());
    tally(test_ghost_reads_back_the_pattern_it_would_play());
    tally(test_ghost_is_unheard());
    tally(test_ghost_leaves_the_live_voice_untouched());
    tally(test_ghost_off_reads_the_live_note_again());
    tally(test_ghost_silent_in_density_out_and_pause());
    printf("\nPulsar live input tests: %s\n", fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(pass, fail);
}
