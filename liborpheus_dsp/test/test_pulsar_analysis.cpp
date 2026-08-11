#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "stmlib/utils/random.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cmath>
#include <cstring>
#include <vector>

static float dft_magnitude_at(const float* mono, int num_frames, float freq_hz, float sample_rate) {
    double re = 0.0, im = 0.0;
    double omega = 2.0 * M_PI * freq_hz / sample_rate;
    for (int i = 0; i < num_frames; i++) {
        re += mono[i] * cos(omega * i);
        im += mono[i] * sin(omega * i);
    }
    return static_cast<float>(sqrt(re * re + im * im) / num_frames);
}

static float rms(const float* buf, int n) {
    double sum = 0.0;
    for (int i = 0; i < n; i++) sum += buf[i] * buf[i];
    return static_cast<float>(sqrt(sum / n));
}

// ── Deep Space vibe setup matching the Kotlin definition exactly ──
static void setup_deep_space_v2(OrpheusEngine* engine) {
    // Engines: MOD, STR, PAR, PD, VA, STR, PAR, MOD
    int engines[] = {20, 19, 18, 1, 8, 19, 18, 20};
    float volumes[] = {0.35f, 0.25f, 0.15f, 0.65f, 0.45f, 0.55f, 0.35f, 0.25f};
    float pans[] = {0.0f, -0.3f, 0.35f, 0.0f, -0.15f, 0.2f, -0.35f, 0.3f};
    int role[] = {0, 0, 0, 1, 1, 1, 1, 1};  // TrackRole: 0=PERC, 1=MELODIC
    // EFFECT=2, MELODIC=1, WILD=3
    int envelopes[] = {2, 2, 3, 1, 2, 2, 2, 3};
    float harmonics[] = {0.3f, 0.4f, 0.2f, 0.15f, 0.3f, 0.35f, 0.15f, 0.5f};
    float timbres[] = {0.2f, 0.3f, 0.3f, 0.15f, 0.25f, 0.2f, 0.25f, 0.4f};
    float morphs[] = {0.6f, 0.15f, 0.2f, 0.7f, 0.3f, 0.1f, 0.2f, 0.15f};

    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_engine_edm[t].store(engines[t], std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(engines[t], std::memory_order_relaxed);
        engine->pulsar_track_volume[t].store(volumes[t], std::memory_order_relaxed);
        engine->pulsar_track_pan[t].store(pans[t], std::memory_order_relaxed);
        engine->pulsar_track_harmonics[t].store(harmonics[t], std::memory_order_relaxed);
        engine->pulsar_track_timbre[t].store(timbres[t], std::memory_order_relaxed);
        engine->pulsar_track_morph[t].store(morphs[t], std::memory_order_relaxed);
        engine->pulsar_track_envelope[t].store(envelopes[t], std::memory_order_relaxed);
        engine->pulsar_track_role[t].store(role[t], std::memory_order_relaxed);
    }

    // Per-track note ranges
    int nr_low[]  = {36, 48,  0, 36, 33, 36, 40, 55};
    int nr_high[] = {48, 60,  0, 48, 48, 50, 55, 72};
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_note_range_low[t].store(nr_low[t], std::memory_order_relaxed);
        engine->pulsar_track_note_range_high[t].store(nr_high[t], std::memory_order_relaxed);
    }

    // Per-track reverb brightness
    float brightness[] = {0.25f, 0.5f, 0.7f, 0.15f, 0.4f, 0.55f, 0.65f, 0.8f};
    for (int t = 0; t < 8; t++)
        engine->pulsar_track_reverb_brightness[t].store(brightness[t], std::memory_order_relaxed);

    // Per-track glide
    float glide[] = {0.0f, 0.0f, 0.0f, 0.7f, 0.5f, 0.6f, 0.3f, 0.0f};
    for (int t = 0; t < 8; t++)
        engine->pulsar_track_glide_rate[t].store(glide[t], std::memory_order_relaxed);

    // Per-track holds (tracks 3-6 get holds)
    float hold_prob[] = {0.0f, 0.0f, 0.0f, 0.5f, 0.85f, 0.95f, 0.8f, 0.0f};
    int hold_min[] =    {2,    2,    2,    4,    8,     12,    6,    2};
    int hold_max[] =    {4,    4,    4,    12,   24,    28,    16,   4};
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_hold_probability[t].store(hold_prob[t], std::memory_order_relaxed);
        engine->pulsar_track_hold_length_min[t].store(hold_min[t], std::memory_order_relaxed);
        engine->pulsar_track_hold_length_max[t].store(hold_max[t], std::memory_order_relaxed);
    }

    // Per-track mod LFO (tracks 4-6)
    float lfo_rate[]  = {0.0f, 0.0f, 0.0f, 0.0f, 0.04f, 0.03f, 0.05f, 0.0f};
    float lfo_depth[] = {0.0f, 0.0f, 0.0f, 0.0f, 0.5f,  0.4f,  0.45f, 0.0f};
    float lfo_shape[] = {0.0f, 0.0f, 0.0f, 0.0f, 0.2f,  0.15f, 0.3f,  0.0f};
    float lfo_coup[]  = {0.0f, 0.0f, 0.0f, 0.0f, 0.3f,  0.2f,  0.35f, 0.0f};
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_mod_lfo_rate[t].store(lfo_rate[t], std::memory_order_relaxed);
        engine->pulsar_track_mod_lfo_depth[t].store(lfo_depth[t], std::memory_order_relaxed);
        engine->pulsar_track_mod_lfo_shape[t].store(lfo_shape[t], std::memory_order_relaxed);
        engine->pulsar_track_mod_lfo_coupling[t].store(lfo_coup[t], std::memory_order_relaxed);
    }

    // Per-track sends
    float delay_send[]  = {0.0f, 0.15f, 0.0f, 0.0f, 0.1f, 0.15f, 0.2f, 0.3f};
    float reverb_send[] = {0.0f, 0.0f,  0.2f, 0.0f, 0.2f, 0.3f,  0.35f, 0.4f};
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_delay_send[t].store(delay_send[t], std::memory_order_relaxed);
        engine->pulsar_track_reverb_send[t].store(reverb_send[t], std::memory_order_relaxed);
    }

    // Per-track density overrides
    float density_ovr[] = {-1.0f, -1.0f, -1.0f, -1.0f, 0.18f, 0.30f, 0.20f, 0.05f};
    for (int t = 0; t < 8; t++)
        engine->pulsar_track_density_override[t].store(density_ovr[t], std::memory_order_relaxed);

    // Genre
    //                    KICK  PERC  HH    BASS  KEYS  STR   PAR   CHIME
    float density[] = {0.10f, 0.06f, 0.04f, 0.12f, 0.10f, 0.25f, 0.20f, 0.05f};
    for (int i = 0; i < 8; i++)
        engine->pulsar_genre_density[i].store(density[i], std::memory_order_relaxed);
    engine->pulsar_genre_swing.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_genre_ghost_prob.store(0.08f, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_low.store(36, std::memory_order_relaxed);
    engine->pulsar_genre_note_range_high.store(72, std::memory_order_relaxed);
    engine->pulsar_genre_rhythm_density.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_genre_progression_style.store(4, std::memory_order_relaxed);  // Drone
    engine->pulsar_genre_chords_per_bar.store(1, std::memory_order_relaxed);

    engine->pulsar_root_note.store(9, std::memory_order_relaxed);   // A
    engine->pulsar_scale_index.store(0, std::memory_order_relaxed); // Minor
    engine->pulsar_lick_length.store(0, std::memory_order_release);
    engine->pulsar_seed.store(0, std::memory_order_relaxed);
}

static const char* track_names[] = {
    "KICK(MOD)", "PERC(STR)", "HH(PAR)", "BASS(PD)",
    "KEYS(VA)", "PAD(STR)", "TEX(PAR)", "FX(MOD)"
};

static void print_spectrum(const float* mono, int num_frames, int sr) {
    float bands[] = {40, 80, 160, 250, 500, 1000, 2000, 4000};
    const char* names[] = {"40Hz", "80Hz", "160Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz"};
    for (int b = 0; b < 8; b++) {
        float mag = dft_magnitude_at(mono, num_frames, bands[b], sr);
        float db = (mag > 0.0001f) ? 20.0f * log10f(mag) : -80.0f;
        int bar_len = static_cast<int>((db + 80.0f) * 0.6f);
        if (bar_len < 0) bar_len = 0;
        if (bar_len > 40) bar_len = 40;
        char bar[41];
        for (int i = 0; i < bar_len; i++) bar[i] = '#';
        bar[bar_len] = '\0';
        printf("      %5s: %6.1f dB  %s\n", names[b], db, bar);
    }
}

bool run_pulsar_analysis_tests() {
    printf("\n=== Deep Space Vibe — Per-Track Analysis ===\n");
    system("mkdir -p liborpheus_dsp/test/output");

    // This suite pins the process-global stmlib RNG below and renders nine 10-second passes.
    // Restore it on the way out: later suites inherit whatever state they are handed, and this
    // one runs before master_fader_pulsar.
    const uint32_t saved_rng = stmlib::Random::state();

    static constexpr int SR = 48000;
    static constexpr int BLOCK = 512;
    static constexpr int DURATION = 10;  // seconds
    int total_frames = SR * DURATION;
    int num_blocks = total_frames / BLOCK;

    // ── 1. Render each track solo'd ──
    printf("\n  ── Per-Track Solo Renders ──\n");
    for (int solo_track = 0; solo_track < 8; solo_track++) {
        OrpheusEngine* engine = orpheus_engine_create(static_cast<float>(SR));
        GraphUnit unit;
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_deep_space_v2(engine);
        engine->pulsar_energy.store(0.15f, std::memory_order_relaxed);
        engine->pulsar_space.store(0.9f, std::memory_order_relaxed);
        engine->pulsar_mood.store(0.65f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.1f, std::memory_order_relaxed);
        engine->clock_bpm.store(55.0f, std::memory_order_relaxed);

        // Mute all tracks except solo_track
        for (int t = 0; t < 8; t++) {
            engine->pulsar_track_mute[t].store(t == solo_track ? 0 : 1, std::memory_order_relaxed);
        }

        // Pin BOTH RNGs: setup_deep_space_v2 leaves pulsar_seed at 0, and load_vibe
        // re-stirs from the wall clock when the seed is 0 — so two identical runs
        // render different patterns and any A/B off this suite is noise.
        engine->pulsar_seed.store(0xBEA7, std::memory_order_relaxed);
        stmlib::Random::Seed(0xBEA70000u);
        trigger_vibe_load(engine);

        std::vector<float> stereo(total_frames * 2, 0.0f);
        std::vector<float> mono(total_frames, 0.0f);

        for (int b = 0; b < num_blocks; b++) {
            unit_process_pulsar(&unit, engine, BLOCK, static_cast<float>(SR));
            int off = b * BLOCK;
            for (int i = 0; i < BLOCK; i++) {
                stereo[(off + i) * 2]     = engine->pulsar_out_l[i];
                stereo[(off + i) * 2 + 1] = engine->pulsar_out_r[i];
                mono[off + i] = (engine->pulsar_out_l[i] + engine->pulsar_out_r[i]) * 0.5f;
            }
        }

        char wav_path[128];
        snprintf(wav_path, sizeof(wav_path),
                 "liborpheus_dsp/test/output/deep_space_track%d_%s.wav",
                 solo_track, track_names[solo_track]);
        // Sanitize filename: replace () with _
        for (char* p = wav_path; *p; p++) { if (*p == '(' || *p == ')') *p = '_'; }
        write_wav(wav_path, stereo.data(), total_frames, SR);

        float peak = 0.0f;
        for (int i = 0; i < total_frames; i++) {
            float a = std::fabs(mono[i]);
            if (a > peak) peak = a;
        }
        float total_rms = rms(mono.data(), total_frames);

        printf("\n    Track %d: %s\n", solo_track, track_names[solo_track]);
        printf("      Peak: %.4f  RMS: %.4f  %s\n", peak, total_rms,
               peak < 0.005f ? "** SILENT **" : "");
        if (peak > 0.005f) {
            print_spectrum(mono.data(), total_frames, SR);
        }

        orpheus_engine_destroy(engine);
    }

    // ── 2. Full mix render ──
    {
        printf("\n  ── Full Mix ──\n");
        OrpheusEngine* engine = orpheus_engine_create(static_cast<float>(SR));
        GraphUnit unit;
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;

        engine->pulsar_playing.store(1, std::memory_order_relaxed);
        engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
        setup_deep_space_v2(engine);
        engine->pulsar_energy.store(0.15f, std::memory_order_relaxed);
        engine->pulsar_space.store(0.9f, std::memory_order_relaxed);
        engine->pulsar_mood.store(0.65f, std::memory_order_relaxed);
        engine->pulsar_complexity.store(0.1f, std::memory_order_relaxed);
        engine->clock_bpm.store(55.0f, std::memory_order_relaxed);

        // Pin BOTH RNGs: setup_deep_space_v2 leaves pulsar_seed at 0, and load_vibe
        // re-stirs from the wall clock when the seed is 0 — so two identical runs
        // render different patterns and any A/B off this suite is noise.
        engine->pulsar_seed.store(0xBEA7, std::memory_order_relaxed);
        stmlib::Random::Seed(0xBEA70000u);
        trigger_vibe_load(engine);

        std::vector<float> stereo(total_frames * 2, 0.0f);
        std::vector<float> mono(total_frames, 0.0f);

        for (int b = 0; b < num_blocks; b++) {
            unit_process_pulsar(&unit, engine, BLOCK, static_cast<float>(SR));
            int off = b * BLOCK;
            for (int i = 0; i < BLOCK; i++) {
                stereo[(off + i) * 2]     = engine->pulsar_out_l[i];
                stereo[(off + i) * 2 + 1] = engine->pulsar_out_r[i];
                mono[off + i] = (engine->pulsar_out_l[i] + engine->pulsar_out_r[i]) * 0.5f;
            }
        }

        write_wav("liborpheus_dsp/test/output/deep_space_full_mix.wav",
                  stereo.data(), total_frames, SR);

        float peak = 0.0f;
        for (int i = 0; i < total_frames; i++) {
            float a = std::fabs(mono[i]);
            if (a > peak) peak = a;
        }
        printf("\n    Peak: %.4f  RMS: %.4f\n", peak, rms(mono.data(), total_frames));
        print_spectrum(mono.data(), total_frames, SR);

        orpheus_engine_destroy(engine);
    }

    printf("\n  WAV files: liborpheus_dsp/test/output/deep_space_*.wav\n");
    printf("  Listen to each track solo and the full mix.\n\n");
    stmlib::Random::Seed(saved_rng);
    return true;
}
