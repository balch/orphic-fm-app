#pragma once
// Scene: Artemis II — 120 BPM, G major, bright shimmer

static const PulsarScenePreset kPulsarSceneArtemis2 = {
    120.0f, 7, 1, // G major
    {
        // Track 0 — Kick: BD(21)/BD(21), rhythm
        {
            21, 21,
            16,
            0.80f,
            0.0f,
            0.4f,
            0.3f,
            0.2f,
            {
                {0.5f, 0.95f},
                {0.3f, 0.7f},
                {0.0f, 0.15f},
                {0.0f, 0.25f},
                {0.3f, 0.6f},
                {0.03f, 0.12f},
                {0.2f, 0.5f},
                {0.2f, 0.45f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 1 — Perc: SD(22)/SD(22), rhythm
        {
            22, 22,
            16,
            0.55f,
            0.1f,
            0.4f,
            0.45f,
            0.25f,
            {
                {0.3f, 0.7f},
                {0.15f, 0.5f},
                {0.0f, 0.2f},
                {0.0f, 0.3f},
                {0.2f, 0.5f},
                {0.1f, 0.3f},
                {0.3f, 0.6f},
                {0.3f, 0.65f},
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
            0.5f,
            0.2f,
            {
                {0.3f, 0.75f},
                {0.4f, 0.85f},
                {0.0f, 0.15f},
                {0.0f, 0.25f},
                {0.1f, 0.3f},
                {0.05f, 0.2f},
                {0.25f, 0.6f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 3 — Bass: FM(10)/FM(10), melodic
        {
            10, 10,
            16,
            0.70f,
            0.0f,
            0.35f,
            0.4f,
            0.3f,
            {
                {0.5f, 0.95f},
                {0.3f, 0.7f},
                {0.0f, 0.1f},
                {0.0f, 0.2f},
                {0.3f, 0.65f},
                {0.0f, 0.08f},
                {0.2f, 0.5f},
                {0.25f, 0.55f},
            },
            ENV_PROFILE_MELODIC,
        },
        // Track 4 — Keys: CHD(14)/CHD(14), melodic
        {
            14, 14,
            16,
            0.55f,
            -0.25f,
            0.5f,
            0.55f,
            0.45f,
            {
                {0.3f, 0.7f},
                {0.15f, 0.4f},
                {0.0f, 0.2f},
                {0.0f, 0.3f},
                {0.4f, 0.8f},
                {0.2f, 0.55f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
            },
            ENV_PROFILE_MELODIC,
        },
        // Track 5 — Pad: SM(6)/SM(6), effect
        {
            6, 6,
            16,
            0.40f,
            -0.35f,
            0.6f,
            0.65f,
            0.5f,
            {
                {0.2f, 0.5f},
                {0.05f, 0.2f},
                {0.0f, 0.15f},
                {0.0f, 0.2f},
                {0.6f, 1.0f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
                {0.5f, 0.85f},
            },
            ENV_PROFILE_EFFECT,
        },
        // Track 6 — Texture: GRN(11)/WTB(13), effect
        {
            11, 13,
            16,
            0.30f,
            0.35f,
            0.55f,
            0.5f,
            0.45f,
            {
                {0.15f, 0.5f},
                {0.1f, 0.4f},
                {0.0f, 0.35f},
                {0.1f, 0.45f},
                {0.45f, 0.85f},
                {0.25f, 0.6f},
                {0.35f, 0.7f},
                {0.35f, 0.75f},
            },
            ENV_PROFILE_EFFECT,
        },
        // Track 7 — FX: MOD(20)/SPK(15), wild
        {
            20, 15,
            16,
            0.20f,
            0.4f,
            0.7f,
            0.6f,
            0.65f,
            {
                {0.1f, 0.4f},
                {0.05f, 0.3f},
                {0.0f, 0.5f},
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
        {0.35f, 0.20f, 0.60f, 0.35f, 0.30f, 0.15f, 0.08f, 0.05f},
        0.0f,   // swing_amount
        0.1f,   // ghost_probability
        43,     // note_range_low
        79,     // note_range_high
        1,      // rhythm_pattern (four-on-floor)
    },
};
