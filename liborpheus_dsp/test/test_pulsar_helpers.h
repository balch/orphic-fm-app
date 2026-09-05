#pragma once

// Shared Pulsar test helpers.
// These are independent test fixtures with stable values for reproducible tests.
// They are NOT mirrors of the Kotlin PulsarVibes definitions — the Kotlin vibes
// may differ in volumes, engines, and other parameters.

#include "../src/orpheus_engine.h"
#include "test_harness.h"
#include <cstring>

static void setup_fixture_baseline(OrpheusEngine* engine) {
    int edm[] = {21, 22, 23, 9, 14, 14, 17, 20};
    int space[] = {20, 17, 23, 19, 6, 14, 17, 19};
    float volumes[] = {0.90f, 0.60f, 0.65f, 0.75f, 0.55f, 0.40f, 0.30f, 0.25f};
    float pans[] = {0.0f, -0.15f, 0.2f, 0.0f, -0.25f, -0.35f, 0.3f, 0.4f};
    int role[] = {0, 0, 0, 1, 1, 1, 0, 1};  // TrackRole: 0=PERC, 1=MELODIC
    int envelopes[] = {0, 0, 0, 1, 1, 2, 2, 3};

    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_engine_edm[t].store(edm[t], std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(space[t], std::memory_order_relaxed);
        engine->pulsar_track_volume[t].store(volumes[t], std::memory_order_relaxed);
        engine->pulsar_track_pan[t].store(pans[t], std::memory_order_relaxed);
        engine->pulsar_track_harmonics[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_timbre[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_morph[t].store(0.3f, std::memory_order_relaxed);
        engine->pulsar_track_envelope[t].store(envelopes[t], std::memory_order_relaxed);
        engine->pulsar_track_role[t].store(role[t], std::memory_order_relaxed);
    }

    float density[] = {0.50f, 0.35f, 0.80f, 0.40f, 0.30f, 0.20f, 0.15f, 0.08f};
    for (int i = 0; i < 8; i++)
        engine->pulsar_genre_density[i].store(density[i], std::memory_order_relaxed);
    engine->pulsar_genre_swing.store(0.02f, std::memory_order_relaxed);
    engine->pulsar_genre_ghost_prob.store(0.3f, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_low.store(36, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_high.store(72, std::memory_order_relaxed);
    engine->pulsar_genre_rhythm_density.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_genre_progression_style.store(0, std::memory_order_relaxed);  // Pop
    engine->pulsar_genre_chords_per_bar.store(2, std::memory_order_relaxed);

    engine->pulsar_root_note.store(2, std::memory_order_relaxed);   // D
    engine->pulsar_scale_index.store(0, std::memory_order_relaxed); // minor
    engine->pulsar_lick_length.store(0, std::memory_order_release); // no lick
    engine->pulsar_seed.store(0, std::memory_order_relaxed);
}

static void setup_fixture_sparse_slow(OrpheusEngine* engine) {
    int edm[] = {20, 17, 23, 19, 6, 14, 17, 19};
    int space[] = {20, 17, 23, 19, 6, 14, 17, 19};
    float volumes[] = {0.80f, 0.50f, 0.55f, 0.70f, 0.50f, 0.35f, 0.25f, 0.20f};
    float pans[] = {0.0f, -0.1f, 0.15f, 0.0f, -0.2f, -0.3f, 0.25f, 0.35f};
    int role[] = {0, 0, 0, 1, 1, 1, 0, 1};  // TrackRole: 0=PERC, 1=MELODIC
    int envelopes[] = {0, 0, 0, 1, 1, 2, 2, 3};

    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_engine_edm[t].store(edm[t], std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(space[t], std::memory_order_relaxed);
        engine->pulsar_track_volume[t].store(volumes[t], std::memory_order_relaxed);
        engine->pulsar_track_pan[t].store(pans[t], std::memory_order_relaxed);
        engine->pulsar_track_harmonics[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_timbre[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_morph[t].store(0.3f, std::memory_order_relaxed);
        engine->pulsar_track_envelope[t].store(envelopes[t], std::memory_order_relaxed);
        engine->pulsar_track_role[t].store(role[t], std::memory_order_relaxed);
    }

    float density[] = {0.30f, 0.20f, 0.50f, 0.25f, 0.15f, 0.10f, 0.10f, 0.05f};
    for (int i = 0; i < 8; i++)
        engine->pulsar_genre_density[i].store(density[i], std::memory_order_relaxed);
    engine->pulsar_genre_swing.store(0.05f, std::memory_order_relaxed);
    engine->pulsar_genre_ghost_prob.store(0.2f, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_low.store(36, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_high.store(72, std::memory_order_relaxed);
    engine->pulsar_genre_rhythm_density.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_genre_progression_style.store(4, std::memory_order_relaxed);  // Drone
    engine->pulsar_genre_chords_per_bar.store(1, std::memory_order_relaxed);

    engine->pulsar_root_note.store(9, std::memory_order_relaxed);   // A
    engine->pulsar_scale_index.store(0, std::memory_order_relaxed); // minor
    engine->pulsar_lick_length.store(0, std::memory_order_release); // no lick
    engine->pulsar_seed.store(0, std::memory_order_relaxed);
}

// Blues fixture: E Phrygian, 85 BPM, blues progression, 32-step
static void setup_fixture_blues(OrpheusEngine* engine) {
    int edm[] = {21, 22, 23, 9, 6, 19, 11, 20};
    int space[] = {21, 22, 23, 19, 6, 19, 11, 19};
    float volumes[] = {0.85f, 0.60f, 0.55f, 0.75f, 0.50f, 0.40f, 0.30f, 0.20f};
    float pans[] = {0.0f, -0.10f, 0.15f, 0.0f, -0.25f, 0.30f, -0.30f, 0.0f};
    int role[] = {0, 0, 0, 1, 1, 1, 1, 1};  // TrackRole: 0=PERC, 1=MELODIC
    int envelopes[] = {0, 0, 0, 1, 1, 2, 2, 3};

    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_engine_edm[t].store(edm[t], std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(space[t], std::memory_order_relaxed);
        engine->pulsar_track_volume[t].store(volumes[t], std::memory_order_relaxed);
        engine->pulsar_track_pan[t].store(pans[t], std::memory_order_relaxed);
        engine->pulsar_track_harmonics[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_timbre[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_morph[t].store(0.3f, std::memory_order_relaxed);
        engine->pulsar_track_envelope[t].store(envelopes[t], std::memory_order_relaxed);
        engine->pulsar_track_role[t].store(role[t], std::memory_order_relaxed);
    }

    float density[] = {0.45f, 0.35f, 0.55f, 0.40f, 0.30f, 0.25f, 0.15f, 0.08f};
    for (int i = 0; i < 8; i++)
        engine->pulsar_genre_density[i].store(density[i], std::memory_order_relaxed);
    engine->pulsar_genre_swing.store(0.10f, std::memory_order_relaxed);
    engine->pulsar_genre_ghost_prob.store(0.25f, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_low.store(36, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_high.store(72, std::memory_order_relaxed);
    engine->pulsar_genre_rhythm_density.store(0.67f, std::memory_order_relaxed);
    engine->pulsar_genre_progression_style.store(3, std::memory_order_relaxed);  // Blues
    engine->pulsar_genre_chords_per_bar.store(4, std::memory_order_relaxed);

    engine->pulsar_root_note.store(4, std::memory_order_relaxed);   // E
    engine->pulsar_scale_index.store(3, std::memory_order_relaxed); // Phrygian
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(0, std::memory_order_release);
    engine->pulsar_seed.store(0, std::memory_order_relaxed);
}

// Dense/fast fixture: A major, 160 BPM, pop 4-chord, fast energy
static void setup_fixture_dense_fast(OrpheusEngine* engine) {
    int edm[] = {21, 22, 23, 8, 14, 14, 17, 9};
    int space[] = {21, 22, 23, 8, 14, 14, 17, 9};
    float volumes[] = {0.90f, 0.75f, 0.70f, 0.80f, 0.60f, 0.45f, 0.35f, 0.30f};
    float pans[] = {0.0f, -0.10f, 0.20f, 0.0f, -0.20f, -0.30f, 0.25f, 0.35f};
    int role[] = {0, 0, 0, 1, 1, 1, 0, 1};  // TrackRole: 0=PERC, 1=MELODIC
    int envelopes[] = {0, 0, 0, 0, 0, 2, 2, 3};

    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_engine_edm[t].store(edm[t], std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(space[t], std::memory_order_relaxed);
        engine->pulsar_track_volume[t].store(volumes[t], std::memory_order_relaxed);
        engine->pulsar_track_pan[t].store(pans[t], std::memory_order_relaxed);
        engine->pulsar_track_harmonics[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_timbre[t].store(0.5f, std::memory_order_relaxed);
        engine->pulsar_track_morph[t].store(0.3f, std::memory_order_relaxed);
        engine->pulsar_track_envelope[t].store(envelopes[t], std::memory_order_relaxed);
        engine->pulsar_track_role[t].store(role[t], std::memory_order_relaxed);
    }

    float density[] = {0.60f, 0.50f, 0.80f, 0.50f, 0.40f, 0.25f, 0.20f, 0.10f};
    for (int i = 0; i < 8; i++)
        engine->pulsar_genre_density[i].store(density[i], std::memory_order_relaxed);
    engine->pulsar_genre_swing.store(0.01f, std::memory_order_relaxed);
    engine->pulsar_genre_ghost_prob.store(0.35f, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_low.store(36, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_high.store(72, std::memory_order_relaxed);
    engine->pulsar_genre_rhythm_density.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_genre_progression_style.store(0, std::memory_order_relaxed);  // Pop
    engine->pulsar_genre_chords_per_bar.store(4, std::memory_order_relaxed);

    engine->pulsar_root_note.store(9, std::memory_order_relaxed);   // A
    engine->pulsar_scale_index.store(1, std::memory_order_relaxed); // Major
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(0, std::memory_order_release);
    engine->pulsar_seed.store(0, std::memory_order_relaxed);
}

// Solo a single track by zeroing all other volumes
static void solo_track(OrpheusEngine* engine, int track_index) {
    for (int t = 0; t < 8; t++) {
        if (t == track_index) {
            engine->pulsar_track_volume[t].store(1.0f, std::memory_order_relaxed);
        } else {
            engine->pulsar_track_volume[t].store(0.0f, std::memory_order_relaxed);
        }
    }
}

// Engine names for diagnostics
static const char* engine_name(int id) {
    static const char* names[] = {
        "VCF","PD","DX","DX2","DX3","TRN","ENS","NES",
        "VA","WSH","FM","GRN","ADD","WTB","CHD","SPK",
        "SWM","NSE","PAR","STR","MOD","BD","SD","HH"
    };
    if (id >= 0 && id < 24) return names[id];
    return "???";
}

// Load the DJ app wiring graph from test data directory.
static bool load_dj_graph(OrpheusEngine* engine) {
    const char* path = TEST_DATA_DIR "/dj_graph.odwg";
    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "Cannot open DJ graph: %s\n", path); return false; }
    fseek(f, 0, SEEK_END);
    size_t len = (size_t)ftell(f);
    fseek(f, 0, SEEK_SET);
    auto* buf = new uint8_t[len];
    size_t read = fread(buf, 1, len, f);
    fclose(f);
    if (read != len) { delete[] buf; return false; }
    int result = orpheus_engine_load_patch(engine, buf, len);
    delete[] buf;
    return result == 0;
}

static void trigger_vibe_load(OrpheusEngine* engine) {
    int gen = engine->pulsar_vibe_generation.load(std::memory_order_relaxed);
    engine->pulsar_vibe_generation.store(gen + 1, std::memory_order_relaxed);
}

// JAM arrangement: 2-section arrangement with groove and improv + solos
static void setup_jam_arrangement(OrpheusEngine* engine) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    // Section 0: groove (bars 4-8, transitions to 0 and 1)
    // Section 1: improv (bars 4-8, IMPROVISERS solo)
    // kSectionDataFields floats per section (slots 18-20 = comping overrides, -1 = no
    // override; 21-25 = weather, left zeroed here — no fixture exercises it yet;
    // 26 = lick_index+1, 0 = no override).
    constexpr int kSectionStride = kSectionDataFields;
    float section_data[8 * kSectionStride] = {};
    // Default all comping-override slots to -1 across every section
    for (int s = 0; s < 8; s++) {
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
    }
    // Section 0
    section_data[0] = 4;    // bars_min
    section_data[1] = 8;    // bars_max
    section_data[2] = 1;    // bar_step = 1 (any value in range)
    section_data[3] = 0.8f; // recency_decay
    section_data[4] = 2;    // transition_count
    section_data[5] = -1;   // macro energy (no override)
    section_data[6] = -1;   section_data[7] = -1; section_data[8] = -1;
    section_data[9] = 0;    // has_solo = false

    // Section 1
    int s1 = kSectionStride;
    section_data[s1 + 0] = 4;
    section_data[s1 + 1] = 8;
    section_data[s1 + 2] = 0;
    section_data[s1 + 3] = 0.8f;
    section_data[s1 + 4] = 2;
    section_data[s1 + 5] = -1; section_data[s1 + 6] = -1;
    section_data[s1 + 7] = -1; section_data[s1 + 8] = -1;
    section_data[s1 + 9] = 1;    // has_solo
    section_data[s1 + 10] = 2;   // IMPROVISERS
    section_data[s1 + 11] = 0.7f;
    section_data[s1 + 12] = 2;   // bars_per_soloist_min
    section_data[s1 + 13] = 4;
    section_data[s1 + 14] = 2;   // WEIGHTED_RANDOM
    section_data[s1 + 15] = 0.5f;
    section_data[s1 + 16] = 1;   // allow_effects
    section_data[s1 + 17] = 0.7f;

    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // Transitions: s0->{0:0.6, 1:0.4}, s1->{0:0.6, 1:0.4}.
    // Per-edge stride: 3 floats (target, weight, transition_bars). All hard-cut.
    float trans[8 * 8 * 3] = {};
    // s0 edges (base index 0)
    trans[0] = 0; trans[1] = 0.6f; trans[2] = 0;
    trans[3] = 1; trans[4] = 0.4f; trans[5] = 0;
    // s1 edges (base index 8 * 3 = 24)
    trans[24] = 0; trans[25] = 0.6f; trans[26] = 0;
    trans[27] = 1; trans[28] = 0.4f; trans[29] = 0;
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Default solo behavior/ducking/markov (zeros — C++ applies defaults)
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// Band-solo arrangement fixture: a 1-bar NONE intro that transitions into a
// 32-bar self-looping LICK_BUILDER section, with a two-member band --
//   member 0 = drums (always_active, tracks 0,1,2)
//   member 1 = melodic lead (track `lead_track`)
// Section 0 = 1-bar NONE intro that transitions to section 1.
// Section 1 = long (32-bar) LickBuilder section that self-loops; the solo
// starts on ENTRY to section 1 and then advance_band_solo + mutate_live_lick
// run once per bar for the section's duration, evolving the live lick.
static void push_lickbuilder_band_arrangement(OrpheusEngine* engine, int lead_track) {
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
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
    }
    // Section 0: 1-bar NONE intro, transitions to section 1.
    section_data[0] = 1;     // bars_min
    section_data[1] = 1;     // bars_max
    section_data[2] = 1;     // bar_step
    section_data[3] = 0.8f;  // recency_decay
    section_data[4] = 1;     // transition_count
    section_data[9] = 0;     // solo_mode = NONE

    // Section 1: 32-bar LickBuilder, self-loops.
    int s1 = kSectionStride;
    section_data[s1 + 0] = 32;     // bars_min
    section_data[s1 + 1] = 32;     // bars_max
    section_data[s1 + 2] = 1;      // bar_step
    section_data[s1 + 3] = 0.8f;   // recency_decay
    section_data[s1 + 4] = 1;      // transition_count
    section_data[s1 + 9]  = static_cast<float>(static_cast<int>(SoloModeId::LICK_BUILDER));
    section_data[s1 + 10] = 1.0f;  // solo_probability
    section_data[s1 + 11] = 1.0f;  // solo_mutation_rate (max so the live lick clearly evolves)
    section_data[s1 + 12] = 0.5f;  // solo_lick_influence
    section_data[s1 + 13] = 2;     // solo_bars_min
    section_data[s1 + 14] = 4;     // solo_bars_max
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // Transitions: s0 -> {1:1.0}, s1 -> {1:1.0} (self-loop).
    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;  // s0 edge 0 -> section 1
    trans[24] = 1; trans[25] = 1.0f; trans[26] = 0;  // s1 edge 0 -> section 1 (s1 base = 8*3 = 24)
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Band config: member 0 = drums (always_active), member 1 = melodic lead.
    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(2, std::memory_order_relaxed);
    // band_member_data layout per member: base = m*12
    //   [0]=track_count, [1..8]=tracks, [9]=always_active, [10]=loudness, [11]=creativity
    for (int i = 0; i < 96; i++)
        engine->pulsar_band_member_data[i].store(0.0f, std::memory_order_relaxed);
    // member 0: drums tracks 0,1,2 always_active
    engine->pulsar_band_member_data[0 + 0].store(3.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 1].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 2].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 3].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 9].store(1.0f, std::memory_order_relaxed);  // always_active
    engine->pulsar_band_member_data[0 + 10].store(0.7f, std::memory_order_relaxed); // loudness
    engine->pulsar_band_member_data[0 + 11].store(0.2f, std::memory_order_relaxed); // creativity
    // member 1: melodic lead on lead_track
    engine->pulsar_band_member_data[12 + 0].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 1].store(static_cast<float>(lead_track), std::memory_order_relaxed);
    engine->pulsar_band_member_data[12 + 9].store(0.0f, std::memory_order_relaxed);  // not always_active
    engine->pulsar_band_member_data[12 + 10].store(0.8f, std::memory_order_relaxed); // loudness
    engine->pulsar_band_member_data[12 + 11].store(0.9f, std::memory_order_relaxed); // creativity (high)
    // Handoff/pull-in: keep the lead leading (member 1 -> member 1). Stride-N=2.
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    // row 1 (lead) -> col 1 (itself) = 1.0 (stride-N row-major, N=2 → idx 1*2+1=3)
    engine->pulsar_band_handoff_matrix[1 * 2 + 1].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_min.store(64, std::memory_order_relaxed);  // keep leading
    engine->pulsar_band_bars_per_lead_max.store(64, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_min.store(2, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_max.store(4, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    // Clear solo/ducking/markov to defaults
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// ── Shared sequencer-test scaffolding ──────────────────────────────────────
// Only the pieces that were genuinely duplicated logic. The trivial
// make_*_unit() constructor is deliberately NOT here: four suites already
// define their own at file scope, so a shared symbol collides with them for no
// benefit — a memset of a 4-field struct is not duplication worth centralizing.

#include "stmlib/utils/random.h"
#include "../src/orpheus_graph.h"

// Pin BOTH RNGs. pulsar_seed alone is not enough: the voices draw from the
// process-global stmlib::Random, so results stay suite-order dependent without
// it (see test_pulsar_signal_quality). Callers that care about later suites
// should save/restore stmlib::Random::state() around the whole suite.
static void pin_pulsar_rngs(OrpheusEngine* engine) {
    engine->pulsar_seed.store(0xBEA7, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
}

static double pulsar_samples_per_step(float bpm, float sample_rate = 48000.0f) {
    return static_cast<double>(sample_rate) / ((static_cast<double>(bpm) / 60.0) * 4.0);
}
