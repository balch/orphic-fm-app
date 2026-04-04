#pragma once
// Scene: Chillwave — 100 BPM, C major, laid-back groove

static const PulsarScenePreset kPulsarSceneChillwave = {
    100.0f, 0, 1, // C major
    {
        // Track 0 — Kick: BD(21)/Modal(20), rhythm
        {
            21, 20,
            16,
            0.80f,
            0.0f,
            0.4f,
            0.3f,
            0.2f,
            {
                {0.5f, 0.95f},
                {0.3f, 0.7f},
                {0.0f, 0.2f},
                {0.0f, 0.3f},
                {0.3f, 0.7f},
                {0.05f, 0.15f},
                {0.2f, 0.5f},
                {0.2f, 0.5f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 1 — Perc: SD(22)/Noise(17), rhythm
        {
            22, 17,
            16,
            0.55f,
            -0.1f,
            0.4f,
            0.5f,
            0.3f,
            {
                {0.3f, 0.7f},
                {0.2f, 0.6f},
                {0.0f, 0.3f},
                {0.0f, 0.35f},
                {0.2f, 0.5f},
                {0.1f, 0.3f},
                {0.3f, 0.6f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 2 — HiHat: HH(23)/HH(23), rhythm
        {
            23, 23,
            16,
            0.50f,
            0.25f,
            0.3f,
            0.5f,
            0.2f,
            {
                {0.3f, 0.7f},
                {0.2f, 0.7f},
                {0.0f, 0.4f},
                {0.0f, 0.4f},
                {0.1f, 0.4f},
                {0.1f, 0.35f},
                {0.2f, 0.6f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_RHYTHM,
        },
        // Track 3 — Bass: VA(8)/STR(19), melodic
        {
            8, 19,
            16,
            0.75f,
            -0.05f,
            0.3f,
            0.4f,
            0.25f,
            {
                {0.5f, 0.95f},
                {0.3f, 0.7f},
                {0.0f, 0.15f},
                {0.0f, 0.25f},
                {0.3f, 0.7f},
                {0.0f, 0.1f},
                {0.2f, 0.5f},
                {0.3f, 0.6f},
            },
            ENV_PROFILE_MELODIC,
        },
        // Track 4 — Keys: CHD(14)/SM(6), melodic
        {
            14, 6,
            16,
            0.55f,
            -0.3f,
            0.5f,
            0.6f,
            0.5f,
            {
                {0.3f, 0.7f},
                {0.1f, 0.3f},
                {0.0f, 0.25f},
                {0.0f, 0.3f},
                {0.5f, 0.9f},
                {0.25f, 0.6f},
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
            -0.4f,
            0.6f,
            0.7f,
            0.5f,
            {
                {0.2f, 0.5f},
                {0.05f, 0.2f},
                {0.0f, 0.15f},
                {0.0f, 0.2f},
                {0.7f, 1.0f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
                {0.5f, 0.9f},
            },
            ENV_PROFILE_EFFECT,
        },
        // Track 6 — Texture: WTB(13)/WTB(13), effect
        {
            13, 13,
            16,
            0.30f,
            0.35f,
            0.5f,
            0.6f,
            0.4f,
            {
                {0.15f, 0.5f},
                {0.1f, 0.4f},
                {0.0f, 0.35f},
                {0.1f, 0.4f},
                {0.5f, 0.85f},
                {0.25f, 0.6f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
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
            0.5f,
            0.6f,
            {
                {0.1f, 0.35f},
                {0.05f, 0.25f},
                {0.0f, 0.4f},
                {0.1f, 0.5f},
                {0.5f, 0.9f},
                {0.3f, 0.7f},
                {0.4f, 0.8f},
                {0.3f, 0.7f},
            },
            ENV_PROFILE_WILD,
        },
    },
    // Genre profile
    {
        {0.35f, 0.25f, 0.30f, 0.30f, 0.20f, 0.15f, 0.10f, 0.05f},
        0.05f,  // swing_amount
        0.2f,   // ghost_probability
        36,     // note_range_low
        72,     // note_range_high
        1,      // rhythm_pattern (four-on-floor)
    },
};
