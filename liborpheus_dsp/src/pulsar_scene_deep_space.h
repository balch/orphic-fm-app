#pragma once
// Scene: Deep Space — 70 BPM, A minor, sparse ambient

static const PulsarScenePreset kPulsarSceneDeepSpace = {
    70.0f, 9, 0, // A minor
    {
        // Track 0 — Kick: Modal(20)/Modal(20), rhythm
        {
            20, 20,     // engine_edm, engine_space
            16,         // step_count
            0.85f,      // volume
            0.0f,       // pan (center)
            0.3f,       // harmonics
            0.4f,       // timbre
            0.2f,       // morph
            {           // macro_map
                {0.6f, 1.0f},   // energy_volume
                {0.2f, 0.6f},   // energy_density
                {0.0f, 0.15f},  // complexity_swing
                {0.0f, 0.3f},   // complexity_variation
                {0.4f, 0.8f},   // space_decay
                {0.05f, 0.2f},  // space_reverb_send
                {0.2f, 0.5f},   // mood_harmonics
                {0.3f, 0.6f},   // mood_timbre
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 1 — Perc: Noise(17)/Particle(18), rhythm
        {
            17, 18,
            16,
            0.45f,
            0.3f,
            0.5f,
            0.7f,
            0.3f,
            {
                {0.3f, 0.7f},
                {0.1f, 0.5f},
                {0.0f, 0.3f},
                {0.0f, 0.4f},
                {0.2f, 0.6f},
                {0.1f, 0.4f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 2 — HiHat: HH(23)/HH(23), rhythm
        {
            23, 23,
            16,
            0.35f,
            0.25f,
            0.3f,
            0.5f,
            0.2f,
            {
                {0.2f, 0.5f},
                {0.1f, 0.4f},
                {0.0f, 0.2f},
                {0.0f, 0.3f},
                {0.1f, 0.3f},
                {0.1f, 0.35f},
                {0.2f, 0.6f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 3 — Bass: WSH(9)/STR(19), melodic
        {
            9, 19,
            16,
            0.80f,
            0.0f,
            0.2f,
            0.3f,
            0.1f,
            {
                {0.6f, 1.0f},
                {0.1f, 0.4f},
                {0.0f, 0.1f},
                {0.0f, 0.2f},
                {0.5f, 0.9f},
                {0.0f, 0.1f},
                {0.1f, 0.4f},
                {0.2f, 0.5f},
            },
            ENV_PROFILE_MELODIC,
        },
        // Track 4 — Keys: SM(6)/SM(6), melodic
        {
            6, 6,
            16,
            0.55f,
            -0.2f,
            0.5f,
            0.6f,
            0.4f,
            {
                {0.3f, 0.7f},
                {0.1f, 0.3f},
                {0.0f, 0.2f},
                {0.0f, 0.3f},
                {0.6f, 1.0f},
                {0.2f, 0.6f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
            },
            ENV_PROFILE_MELODIC,
        },
        // Track 5 — Pad: CHD(14)/STR(19), effect
        {
            14, 19,
            16,
            0.40f,
            -0.3f,
            0.6f,
            0.5f,
            0.5f,
            {
                {0.2f, 0.5f},
                {0.05f, 0.2f},
                {0.0f, 0.15f},
                {0.0f, 0.25f},
                {0.7f, 1.0f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
                {0.5f, 0.9f},
            },
            ENV_PROFILE_EFFECT,
        },
        // Track 6 — Texture: GRN(11)/WTB(13), effect
        {
            11, 13,
            16,
            0.30f,
            0.4f,
            0.6f,
            0.5f,
            0.5f,
            {
                {0.2f, 0.6f},
                {0.1f, 0.5f},
                {0.0f, 0.4f},
                {0.1f, 0.5f},
                {0.5f, 0.9f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_EFFECT,
        },
        // Track 7 — FX: MOD(20)/STR(19), wild
        {
            20, 19,
            16,
            0.20f,
            0.5f,
            0.7f,
            0.6f,
            0.7f,
            {
                {0.15f, 0.4f},
                {0.05f, 0.3f},
                {0.0f, 0.5f},
                {0.1f, 0.6f},
                {0.6f, 1.0f},
                {0.4f, 0.8f},
                {0.5f, 0.9f},
                {0.4f, 0.8f},
            },
            ENV_PROFILE_WILD,
        },
    },
    // Genre profile
    {
        {0.25f, 0.15f, 0.10f, 0.15f, 0.10f, 0.08f, 0.06f, 0.05f}, // base_density
        0.0f,   // swing_amount
        0.1f,   // ghost_probability
        36,     // note_range_low
        72,     // note_range_high
        0,      // rhythm_pattern (sparse)
    },
};
