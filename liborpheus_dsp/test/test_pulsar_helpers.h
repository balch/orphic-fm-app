#pragma once

// Shared Pulsar test helpers.
// These are independent test fixtures with stable values for reproducible tests.
// They are NOT mirrors of the Kotlin PulsarVibes definitions — the Kotlin vibes
// may differ in volumes, engines, and other parameters.

#include "../src/orpheus_engine.h"
#include <cstring>

static void setup_cosmic_techno(OrpheusEngine* engine) {
    int edm[] = {21, 22, 23, 9, 14, 14, 17, 20};
    int space[] = {20, 17, 23, 19, 6, 14, 17, 19};
    float volumes[] = {0.90f, 0.60f, 0.65f, 0.75f, 0.55f, 0.40f, 0.30f, 0.25f};
    float pans[] = {0.0f, -0.15f, 0.2f, 0.0f, -0.25f, -0.35f, 0.3f, 0.4f};
    int percussive[] = {1, 1, 1, 0, 0, 0, 1, 0};
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
        engine->pulsar_track_percussive[t].store(percussive[t], std::memory_order_relaxed);
    }

    float density[] = {0.50f, 0.35f, 0.80f, 0.40f, 0.30f, 0.20f, 0.15f, 0.08f};
    for (int i = 0; i < 8; i++)
        engine->pulsar_genre_density[i].store(density[i], std::memory_order_relaxed);
    engine->pulsar_genre_swing.store(0.02f, std::memory_order_relaxed);
    engine->pulsar_genre_ghost_prob.store(0.3f, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_low.store(36, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_high.store(72, std::memory_order_relaxed);
    engine->pulsar_genre_rhythm_pattern.store(3, std::memory_order_relaxed);

    engine->pulsar_root_note.store(2, std::memory_order_relaxed);   // D
    engine->pulsar_scale_index.store(0, std::memory_order_relaxed); // minor
    engine->pulsar_lick_length.store(0, std::memory_order_release); // no lick
    engine->pulsar_seed.store(0, std::memory_order_relaxed);
}

static void setup_deep_space(OrpheusEngine* engine) {
    int edm[] = {20, 17, 23, 19, 6, 14, 17, 19};
    int space[] = {20, 17, 23, 19, 6, 14, 17, 19};
    float volumes[] = {0.80f, 0.50f, 0.55f, 0.70f, 0.50f, 0.35f, 0.25f, 0.20f};
    float pans[] = {0.0f, -0.1f, 0.15f, 0.0f, -0.2f, -0.3f, 0.25f, 0.35f};
    int percussive[] = {1, 1, 1, 0, 0, 0, 1, 0};
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
        engine->pulsar_track_percussive[t].store(percussive[t], std::memory_order_relaxed);
    }

    float density[] = {0.30f, 0.20f, 0.50f, 0.25f, 0.15f, 0.10f, 0.10f, 0.05f};
    for (int i = 0; i < 8; i++)
        engine->pulsar_genre_density[i].store(density[i], std::memory_order_relaxed);
    engine->pulsar_genre_swing.store(0.05f, std::memory_order_relaxed);
    engine->pulsar_genre_ghost_prob.store(0.2f, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_low.store(36, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_high.store(72, std::memory_order_relaxed);
    engine->pulsar_genre_rhythm_pattern.store(0, std::memory_order_relaxed);

    engine->pulsar_root_note.store(9, std::memory_order_relaxed);   // A
    engine->pulsar_scale_index.store(0, std::memory_order_relaxed); // minor
    engine->pulsar_lick_length.store(0, std::memory_order_release); // no lick
    engine->pulsar_seed.store(0, std::memory_order_relaxed);
}

static void trigger_vibe_load(OrpheusEngine* engine) {
    int gen = engine->pulsar_vibe_generation.load(std::memory_order_relaxed);
    engine->pulsar_vibe_generation.store(gen + 1, std::memory_order_relaxed);
}
