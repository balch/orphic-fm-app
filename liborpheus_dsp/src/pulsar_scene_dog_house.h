#pragma once
// Scene: Dog House — 85 BPM, E Phrygian, heavy groove

static const PulsarScenePreset kPulsarSceneDogHouse = {
    85.0f, 4, 3, // E Phrygian
    {
        // Track 0 — Kick: BD(21)/BD(21), rhythm
        {
            21, 21,
            16,
            0.90f,
            0.0f,
            0.5f,
            0.35f,
            0.2f,
            {
                {0.7f, 1.0f},
                {0.3f, 0.7f},
                {0.0f, 0.2f},
                {0.0f, 0.25f},
                {0.3f, 0.6f},
                {0.02f, 0.12f},
                {0.3f, 0.6f},
                {0.2f, 0.5f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 1 — Perc: SD(22)/SD(22), rhythm
        {
            22, 22,
            16,
            0.70f,
            0.05f,
            0.45f,
            0.5f,
            0.3f,
            {
                {0.4f, 0.85f},
                {0.2f, 0.6f},
                {0.0f, 0.35f},
                {0.0f, 0.4f},
                {0.2f, 0.5f},
                {0.05f, 0.2f},
                {0.3f, 0.6f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 2 — HiHat: HH(23)/HH(23), rhythm
        {
            23, 23,
            16,
            0.55f,
            0.2f,
            0.35f,
            0.55f,
            0.2f,
            {
                {0.3f, 0.7f},
                {0.3f, 0.8f},
                {0.0f, 0.3f},
                {0.0f, 0.35f},
                {0.1f, 0.35f},
                {0.05f, 0.2f},
                {0.2f, 0.6f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 3 — Bass: WSH(9)/STR(19), melodic
        {
            9, 19,
            16,
            0.85f,
            0.0f,
            0.3f,
            0.4f,
            0.2f,
            {
                {0.6f, 1.0f},
                {0.3f, 0.7f},
                {0.0f, 0.15f},
                {0.0f, 0.2f},
                {0.3f, 0.7f},
                {0.0f, 0.08f},
                {0.2f, 0.5f},
                {0.2f, 0.5f},
            },
            ENV_PROFILE_MELODIC,
        },
        // Track 4 — Keys: SM(6)/SM(6), melodic
        {
            6, 6,
            16,
            0.45f,
            -0.25f,
            0.5f,
            0.55f,
            0.4f,
            {
                {0.3f, 0.65f},
                {0.1f, 0.3f},
                {0.0f, 0.2f},
                {0.0f, 0.3f},
                {0.5f, 0.9f},
                {0.15f, 0.5f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
            },
            ENV_PROFILE_MELODIC,
        },
        // Track 5 — Pad: STR(19)/STR(19), effect
        {
            19, 19,
            16,
            0.35f,
            -0.4f,
            0.55f,
            0.6f,
            0.45f,
            {
                {0.15f, 0.45f},
                {0.05f, 0.15f},
                {0.0f, 0.1f},
                {0.0f, 0.2f},
                {0.7f, 1.0f},
                {0.3f, 0.7f},
                {0.4f, 0.75f},
                {0.45f, 0.85f},
            },
            ENV_PROFILE_EFFECT,
        },
        // Track 6 — Texture: GRN(11)/GRN(11), effect
        {
            11, 11,
            16,
            0.25f,
            0.35f,
            0.55f,
            0.5f,
            0.45f,
            {
                {0.1f, 0.4f},
                {0.05f, 0.3f},
                {0.0f, 0.3f},
                {0.05f, 0.4f},
                {0.4f, 0.8f},
                {0.2f, 0.55f},
                {0.35f, 0.7f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_EFFECT,
        },
        // Track 7 — FX: MOD(20)/STR(19), wild
        {
            20, 19,
            16,
            0.20f,
            0.45f,
            0.65f,
            0.55f,
            0.7f,
            {
                {0.1f, 0.35f},
                {0.05f, 0.25f},
                {0.0f, 0.45f},
                {0.1f, 0.55f},
                {0.5f, 0.9f},
                {0.35f, 0.75f},
                {0.45f, 0.85f},
                {0.4f, 0.8f},
            },
            ENV_PROFILE_WILD,
        },
    },
    // Genre profile
    {
        {0.45f, 0.30f, 0.50f, 0.40f, 0.15f, 0.08f, 0.04f, 0.05f},
        0.15f,  // swing_amount
        0.4f,   // ghost_probability
        33,     // note_range_low
        60,     // note_range_high
        2,      // rhythm_pattern (backbeat-heavy)
    },
};
