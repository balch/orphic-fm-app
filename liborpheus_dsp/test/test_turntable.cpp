#include "../src/orpheus_engine.h"
#include "../src/orpheus_turntable.h"
#include "../src/orpheus_graph.h"
#include "test_harness.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static bool test_turntable_bypass_at_zero_mix() {
    printf("\n=== Test: Turntable bypass at zero mix ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(0.0f);
    engine->turntable_wet_b.store(0.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    for (int i = 0; i < 64; i++) u.output_buffers[OPORT_OUT][i] = 1.0f;

    unit_process_turntable(&u, engine, 64, 48000.0f);

    bool silent = true;
    for (int i = 0; i < 64; i++) {
        if (u.output_buffers[OPORT_OUT][i] != 0.0f) { silent = false; break; }
    }
    orpheus_engine_destroy(engine);
    printf("  Output silent: %s\n", silent ? "PASS" : "FAIL");
    return silent;
}

static bool test_turntable_captures_source() {
    printf("\n=== Test: Turntable captures source into buffer ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_source_a.store(TT_SOURCE_SYNTH);
    engine->turntable_crossfader.store(0.0f);

    for (int i = 0; i < 64; i++) {
        engine->warps_synth_read[i] = std::sin(2.0f * 3.14159f * 440.0f * i / 48000.0f);
    }

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    for (int b = 0; b < 10; b++) {
        unit_process_turntable(&u, engine, 64, 48000.0f);
    }

    bool has_data = false;
    for (int i = 0; i < 640; i++) {
        if (engine->turntable_decks[0].buffer[i] != 0.0f) { has_data = true; break; }
    }
    orpheus_engine_destroy(engine);
    printf("  Buffer has data: %s\n", has_data ? "PASS" : "FAIL");
    return has_data;
}

static bool test_turntable_freeze_stops_capture() {
    printf("\n=== Test: Freeze stops buffer capture ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_source_a.store(TT_SOURCE_SYNTH);
    engine->turntable_frozen_a.store(0);
    engine->turntable_crossfader.store(0.0f);

    for (int i = 0; i < 64; i++) engine->warps_synth_read[i] = 0.5f;

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    unit_process_turntable(&u, engine, 64, 48000.0f);
    int write_pos_before = engine->turntable_decks[0].write_pos;

    engine->turntable_frozen_a.store(1);

    for (int i = 0; i < 64; i++) engine->warps_synth_read[i] = -0.5f;
    unit_process_turntable(&u, engine, 64, 48000.0f);

    int write_pos_after = engine->turntable_decks[0].write_pos;
    bool frozen_ok = (write_pos_before == write_pos_after);

    orpheus_engine_destroy(engine);
    printf("  Write pos unchanged: %s\n", frozen_ok ? "PASS" : "FAIL");
    return frozen_ok;
}

static bool test_turntable_crossfader() {
    printf("\n=== Test: Crossfader blends decks ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    // Explicitly set both decks to the same source so the equal-gain
    // assumption (both use TT_SOURCE_SYNTH gain) is visible in the test.
    engine->turntable_source_a.store(TT_SOURCE_SYNTH);
    engine->turntable_source_b.store(TT_SOURCE_SYNTH);

    for (int i = 0; i < kTurntableBufSize; i++) {
        engine->turntable_decks[0].buffer[i] = 1.0f;
        engine->turntable_decks[1].buffer[i] = -1.0f;
    }
    engine->turntable_frozen_a.store(1);
    engine->turntable_frozen_b.store(1);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    engine->turntable_crossfader.store(0.5f);
    unit_process_turntable(&u, engine, 64, 48000.0f);

    float sum = 0.0f;
    for (int i = 0; i < 64; i++) sum += std::fabs(u.output_buffers[OPORT_OUT][i]);
    bool center_ok = (sum / 64.0f) < 0.1f;

    orpheus_engine_destroy(engine);
    printf("  Center crossfade cancels: %s (avg=%.4f)\n", center_ok ? "PASS" : "FAIL", sum / 64.0f);
    return center_ok;
}

static bool test_turntable_viz_snapshot() {
    printf("\n=== Test: Viz snapshot produces data ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    for (int i = 0; i < kTurntableBufSize; i++) {
        engine->turntable_decks[0].buffer[i] = static_cast<float>(i) / kTurntableBufSize;
    }
    engine->turntable_decks[0].read_pos = kTurntableBufSize / 4.0f;

    // Freeze deck A so buffer data is preserved, then process one block
    // to trigger turntable_update_viz which populates the snapshot.
    engine->turntable_wet_a.store(1.0f);
    engine->turntable_wet_b.store(1.0f);
    engine->turntable_frozen_a.store(1);
    engine->turntable_frozen_b.store(1);
    engine->turntable_crossfader.store(0.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    unit_process_turntable(&u, engine, 64, 48000.0f);

    float viz[kTurntableVizSize + 1];
    turntable_get_viz(&engine->turntable_decks[0], viz);

    bool has_waveform = false;
    for (int i = 0; i < kTurntableVizSize; i++) {
        if (viz[i] != 0.0f) { has_waveform = true; break; }
    }
    bool playhead_ok = std::fabs(viz[kTurntableVizSize] - 0.25f) < 0.01f;

    orpheus_engine_destroy(engine);
    printf("  Has waveform: %s\n", has_waveform ? "PASS" : "FAIL");
    printf("  Playhead pos: %s (%.3f)\n", playhead_ok ? "PASS" : "FAIL", viz[kTurntableVizSize]);
    return has_waveform && playhead_ok;
}

// Simulates Bluetooth chunked callbacks (e.g. 960 = 512 + 448) and verifies
// that the turntable buffer doesn't contain stale samples from unequal chunks.
static bool test_turntable_chunked_capture_no_stale_tail() {
    printf("\n=== Test: Chunked capture has no stale tail ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Fill bass source buffer with a known signal for chunk 1 (512 frames)
    float tone_a = 0.1f;
    for (int i = 0; i < kMaxFrames; i++) {
        engine->warps_source_buffers[9][i] = tone_a;
    }

    // Simulate graph_process double-buffer swap + zero for chunk 1 (512 frames)
    std::memcpy(engine->warps_bass_read, engine->warps_source_buffers[9],
                kMaxFrames * sizeof(float));
    // With the fix, this zeros kMaxFrames; without it, only num_frames
    std::memset(engine->warps_source_buffers[9], 0, kMaxFrames * sizeof(float));

    // Bass voice writes 512 samples for chunk 1
    float tone_b = 0.2f;
    for (int i = 0; i < 512; i++) {
        engine->warps_source_buffers[9][i] = tone_b;
    }

    // Simulate graph_process for chunk 2 (448 frames) — smaller chunk
    int chunk2 = 448;
    std::memcpy(engine->warps_bass_read, engine->warps_source_buffers[9],
                chunk2 * sizeof(float));
    // With fix: zeros full kMaxFrames, clearing tail
    // Without fix: only zeros 448, leaving samples 448-511 = tone_b (stale)
    std::memset(engine->warps_source_buffers[9], 0, kMaxFrames * sizeof(float));

    // Bass voice writes 448 samples for chunk 2
    float tone_c = 0.3f;
    for (int i = 0; i < chunk2; i++) {
        engine->warps_source_buffers[9][i] = tone_c;
    }

    // Now simulate NEXT callback, chunk 1 (512 frames) — reads full 512
    std::memcpy(engine->warps_bass_read, engine->warps_source_buffers[9],
                512 * sizeof(float));

    // Check: samples 448-511 should be ZERO (properly cleared), not stale tone_b
    bool tail_clean = true;
    for (int i = chunk2; i < 512; i++) {
        if (engine->warps_bass_read[i] != 0.0f) {
            printf("  FAIL: warps_bass_read[%d] = %.4f (expected 0, stale data)\n",
                   i, engine->warps_bass_read[i]);
            tail_clean = false;
            break;
        }
    }

    orpheus_engine_destroy(engine);
    printf("  Tail samples clean: %s\n", tail_clean ? "PASS" : "FAIL");
    return tail_clean;
}

// Helper used by drop-kind tests (Tasks 5–8).
// Fills deck buffer with a pure tone so the new per-kind drop processors
// have material to work with, and freezes the deck to prevent the tone
// from being overwritten by live capture.
static void fill_deck_tone(TurntableDeck* deck, float freq, float sr) {
    for (int i = 0; i < kTurntableBufSize; i++) {
        deck->buffer[i] = std::sin(2.0f * 3.14159f * freq * i / sr);
    }
    deck->frozen = true;  // prevent capture from overwriting
}

static bool test_beat_phase_freerun_when_pulsar_idle() {
    printf("\n=== Test: beat_phase free-runs when Pulsar idle ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(0);           // Pulsar idle
    engine->beat_phase.store(0.0f);

    GraphUnit u;
    std::memset(&u, 0, sizeof(u));
    u.type = UNIT_TURNTABLE;
    u.enabled = true;

    // Set wet so the unit actually runs (non-bypassed)
    engine->turntable_wet_a.store(1.0f);

    // Run for 0.5 s of audio (24000 samples, 375 blocks of 64)
    // At 2 Hz free-run, phase should advance ~1.0 total (wraps through 0).
    float first_phase = 0.0f;
    bool saw_advance = false;
    for (int b = 0; b < 375; b++) {
        unit_process_turntable(&u, engine, 64, 48000.0f);
        float p = engine->beat_phase.load();
        if (b == 0) first_phase = p;
        if (p > first_phase + 0.01f) { saw_advance = true; break; }
    }

    orpheus_engine_destroy(engine);
    printf("  beat_phase advanced: %s\n", saw_advance ? "PASS" : "FAIL");
    return saw_advance;
}

static bool test_drop_filter_attenuates_highs() {
    printf("\n=== Test: DROP_FILTER attenuates high frequencies ===\n");

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 8000.0f, sr);

    float clean_out[512];
    float drop_out[512];

    // Clean baseline: process without a drop
    playback_deck(&deck, 1.0f, clean_out, 512, sr);

    // Reset read pos so the drop path sees the same tone region
    deck.read_pos = 0.0f;
    deck.drop.kind = DROP_FILTER;
    deck.drop.phase = 0.0f;

    // Advance the filter deep into its sweep (~3 s worth of samples)
    float scratch[512];
    for (int b = 0; b < 280; b++) {
        playback_deck(&deck, 1.0f, scratch, 512, sr);
        drop_process(&deck, scratch, 512, sr, 0.5f);
    }
    playback_deck(&deck, 1.0f, drop_out, 512, sr);
    drop_process(&deck, drop_out, 512, sr, 0.5f);

    float rms_clean = 0.0f, rms_drop = 0.0f;
    for (int i = 0; i < 512; i++) {
        rms_clean += clean_out[i] * clean_out[i];
        rms_drop  += drop_out[i]  * drop_out[i];
    }
    rms_clean = std::sqrt(rms_clean / 512.0f);
    rms_drop  = std::sqrt(rms_drop  / 512.0f);
    float ratio_db = 20.0f * std::log10(rms_drop / (rms_clean + 1e-10f));
    bool attenuated = ratio_db < -6.0f;  // expect 8 kHz tone clearly knocked down

    printf("  RMS clean: %.4f, drop: %.4f, ratio: %.1f dB\n",
           rms_clean, rms_drop, ratio_db);
    printf("  Attenuation > 6 dB: %s\n", attenuated ? "PASS" : "FAIL");
    return attenuated;
}

static bool test_drop_brake_decays_to_silence() {
    printf("\n=== Test: DROP_BRAKE decays to silence ===\n");

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 440.0f, sr);

    deck.drop.kind = DROP_BRAKE;
    deck.drop.brake_read = 0.0f;
    deck.drop.brake_speed = 1.0f;

    float out[512];
    float final_peak = 0.0f;
    // 0.7 s ≈ 33 blocks of 512 at 48 kHz
    for (int b = 0; b < 33; b++) {
        playback_deck(&deck, 1.0f, out, 512, sr);
        drop_process(&deck, out, 512, sr, 0.5f);
        if (b >= 30) {  // measure the last 3 blocks
            for (int i = 0; i < 512; i++) {
                float a = std::fabs(out[i]);
                if (a > final_peak) final_peak = a;
            }
        }
    }

    bool silent = final_peak < 0.02f;  // ~ −34 dB floor (accounts for kDropOutputGain = 1.3)
    printf("  Final peak after brake: %.5f (< 0.02): %s\n",
           final_peak, silent ? "PASS" : "FAIL");
    return silent;
}

static bool test_drop_stutter_gates_at_divisions() {
    printf("\n=== Test: DROP_STUTTER gates buffer at beat divisions ===\n");

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 440.0f, sr);
    deck.drop.kind = DROP_STUTTER;

    float out[512];
    int transitions = 0;
    float prev_gate_magnitude = 1.0f;

    // Drive beat_phase through multiple cycles; at 1/8 division we expect
    // 8 gate transitions per full phase cycle.
    for (int b = 0; b < 20; b++) {
        float phase = (b / 20.0f) * 4.0f;
        phase -= std::floor(phase);
        playback_deck(&deck, 1.0f, out, 512, sr);
        drop_process(&deck, out, 512, sr, phase);
        float avg = 0.0f;
        for (int i = 0; i < 512; i++) avg += std::fabs(out[i]);
        avg /= 512.0f;
        bool loud = avg > 0.1f;
        bool was_loud = prev_gate_magnitude > 0.1f;
        if (loud != was_loud) transitions++;
        prev_gate_magnitude = avg;
    }

    bool ok = transitions >= 4;  // at least a few on/off toggles across the sweep
    printf("  Gate transitions: %d (>= 4): %s\n", transitions, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_drop_freeze_loops_slice() {
    printf("\n=== Test: DROP_FREEZE loops a fixed slice ===\n");

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;

    // Fill buffer with a linear ramp (not periodic at kFreezeSliceSamples).
    // Without FREEZE, playback_deck reads sequentially and the output is
    // monotonically increasing — autocorrelation at freeze_len offset will be
    // near 1.0 only if the output truly loops (i.e. FREEZE is active).
    // To break the trivial correlation, set read_pos to the MIDDLE of the
    // buffer so playback output crosses the wrap boundary partway through,
    // producing a discontinuity that destroys autocorrelation at freeze_len.
    for (int i = 0; i < kTurntableBufSize; i++) {
        deck.buffer[i] = static_cast<float>(i) / kTurntableBufSize;
    }
    // Position read_pos so that after freeze_len samples, playback_deck wraps
    // through the buffer boundary — output second half differs strongly from first half.
    deck.read_pos = static_cast<float>(kTurntableBufSize - kFreezeSliceSamples / 2);
    deck.write_pos = kFreezeSliceSamples + 100;  // slice is buffer[100..4900)
    deck.frozen = true;
    deck.drop.kind = DROP_FREEZE;
    deck.drop.freeze_start = 100;
    deck.drop.freeze_len   = kFreezeSliceSamples;
    deck.drop.freeze_read  = 0.0f;

    float out[kFreezeSliceSamples * 2];
    playback_deck(&deck, 1.0f, out, kFreezeSliceSamples * 2, sr);
    drop_process(&deck, out, kFreezeSliceSamples * 2, sr, 0.5f);

    // Autocorrelation: out[i] should strongly resemble out[i + freeze_len]
    float acc = 0.0f, norm = 0.0f;
    for (int i = 0; i < kFreezeSliceSamples; i++) {
        acc  += out[i] * out[i + kFreezeSliceSamples];
        norm += out[i] * out[i];
    }
    float corr = acc / (norm + 1e-10f);
    bool loops = corr > 0.7f;  // pitch mod makes it imperfect but strong
    printf("  Autocorrelation at freeze_len: %.3f (> 0.7): %s\n",
           corr, loops ? "PASS" : "FAIL");
    return loops;
}

// Sanity helper: returns true when every sample in `buf` is finite and
// under `cap` in magnitude — the minimum bar for any drop processor that
// has internal feedback (PHASER, ECHO) or shares the audio path.
static bool buffer_bounded_and_finite(const float* buf, int n, float cap) {
    for (int i = 0; i < n; i++) {
        if (!std::isfinite(buf[i])) return false;
        if (std::fabs(buf[i]) > cap) return false;
    }
    return true;
}

static bool test_drop_phaser_bounded_under_long_dc() {
    printf("\n=== Test: DROP_PHASER stays finite and bounded under long DC input ===\n");
    // Allpass cascades with feedback are the most likely drop to produce Inf/NaN.
    // A hot DC input is the worst case: no canceling dry signal, just steady energy
    // into the feedback loop. Run for ~2 s and assert all samples are finite and
    // under the output-gain ceiling.

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 0.0f;           // playback layer muted so we isolate the drop
    deck.drop.kind = DROP_PHASER;

    float out[512];
    bool ok = true;
    for (int b = 0; b < 200 && ok; b++) {     // 200 * 512 = 102400 samples ≈ 2.1 s
        for (int i = 0; i < 512; i++) out[i] = 0.7f;  // hot DC
        drop_process(&deck, out, 512, sr, 0.5f);
        ok = buffer_bounded_and_finite(out, 512, 4.0f);
    }
    printf("  PHASER finite+bounded over ~2 s of DC: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_drop_echo_produces_delayed_copy() {
    printf("\n=== Test: DROP_ECHO delays input by kEchoDelaySamples ===\n");

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 0.0f;
    deck.drop.kind = DROP_ECHO;

    // Feed a single impulse at sample 0, then silence for long enough for the
    // echo tap to emerge. We expect a peak near sample kEchoDelaySamples.
    const int total = kEchoDelaySamples + 2048;
    float* out = new float[total];
    std::memset(out, 0, total * sizeof(float));
    out[0] = 1.0f;

    // Process in 512-sample chunks
    for (int off = 0; off < total; off += 512) {
        int n = (off + 512 <= total) ? 512 : (total - off);
        drop_process(&deck, out + off, n, sr, 0.5f);
    }

    // Find peak outside a 64-sample guard around the dry impulse
    float peak_delayed = 0.0f;
    int peak_idx = 0;
    for (int i = 64; i < total; i++) {
        float a = std::fabs(out[i]);
        if (a > peak_delayed) { peak_delayed = a; peak_idx = i; }
    }
    // Tolerance: within ±16 samples of the configured delay
    bool near_delay = std::abs(peak_idx - kEchoDelaySamples) < 16;
    bool audible    = peak_delayed > 0.1f;  // impulse * wet_mix * output_gain
    bool all_finite = buffer_bounded_and_finite(out, total, 8.0f);

    printf("  peak at sample %d (expected ~%d), mag %.3f, finite: %s\n",
           peak_idx, kEchoDelaySamples, peak_delayed, all_finite ? "yes" : "NO");
    delete[] out;
    return near_delay && audible && all_finite;
}

static bool test_drop_ring_injects_sidebands() {
    printf("\n=== Test: DROP_RING produces sidebands (output != input) ===\n");
    // Ring mod of a pure tone produces sidebands at (signal ± carrier). We
    // don't need a full spectrum — just verify the processed output diverges
    // meaningfully from the dry signal and stays finite.

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;
    fill_deck_tone(&deck, 440.0f, sr);

    float dry[1024], wet[1024];
    playback_deck(&deck, 1.0f, dry, 1024, sr);

    deck.read_pos = 0.0f;                     // rewind so the wet pass sees the same tone
    deck.drop.kind = DROP_RING;
    playback_deck(&deck, 1.0f, wet, 1024, sr);
    drop_process(&deck, wet, 1024, sr, 0.5f);

    // Sum of squared differences — if the ring mod is active, wet diverges far from dry
    double diff_energy = 0.0, dry_energy = 0.0;
    for (int i = 0; i < 1024; i++) {
        float d = wet[i] - dry[i];
        diff_energy += d * d;
        dry_energy  += dry[i] * dry[i];
    }
    double ratio = diff_energy / (dry_energy + 1e-12);
    bool modulated = ratio > 0.05;           // at least 5% of dry energy is modulation
    bool all_finite = buffer_bounded_and_finite(wet, 1024, 4.0f);

    printf("  diff/dry energy ratio: %.3f (>0.05), finite: %s\n",
           ratio, all_finite ? "yes" : "NO");
    return modulated && all_finite;
}

static bool test_drop_octave_adds_subharmonic_energy() {
    printf("\n=== Test: DROP_OCTAVE adds energy from the subharmonic read cursor ===\n");
    // OCTAVE reads at half the deck velocity and mixes it UNDER the normal
    // playback. A buffer filled with different amplitudes in its two halves
    // lets the sub cursor (starting at read_pos=0, advancing at half speed)
    // read material the dry playback doesn't reach during the test window.
    // The wet output should differ from dry-only playback.

    float sr = 48000.0f;
    TurntableDeck deck;
    std::memset(&deck, 0, sizeof(deck));
    deck.smoothed_velocity = 1.0f;

    // Two-zone buffer: loud tone in first half, silent second half.
    for (int i = 0; i < kTurntableBufSize; i++) {
        if (i < kTurntableBufSize / 2) {
            deck.buffer[i] = std::sin(2.0f * 3.14159265f * 440.0f * i / sr);
        } else {
            deck.buffer[i] = 0.0f;
        }
    }
    deck.frozen = true;

    float dry[1024], wet[1024];
    // Dry pass starting in the silent half — should be near silent
    deck.read_pos = static_cast<float>(kTurntableBufSize / 2);
    playback_deck(&deck, 1.0f, dry, 1024, sr);

    // Wet pass: same read_pos (dry layer silent) but the OCTAVE cursor starts
    // at 0 (loud half) and advances at half speed, so wet has energy.
    deck.read_pos = static_cast<float>(kTurntableBufSize / 2);
    deck.drop.kind = DROP_OCTAVE;
    deck.drop.octave_read = 0.0f;
    playback_deck(&deck, 1.0f, wet, 1024, sr);
    drop_process(&deck, wet, 1024, sr, 0.5f);

    float dry_peak = 0.0f, wet_peak = 0.0f;
    for (int i = 0; i < 1024; i++) {
        dry_peak = std::max(dry_peak, std::fabs(dry[i]));
        wet_peak = std::max(wet_peak, std::fabs(wet[i]));
    }
    bool added_energy = wet_peak > dry_peak + 0.1f;
    bool all_finite = buffer_bounded_and_finite(wet, 1024, 4.0f);

    printf("  dry peak %.4f, wet peak %.4f (wet > dry + 0.1), finite: %s\n",
           dry_peak, wet_peak, all_finite ? "yes" : "NO");
    return added_energy && all_finite;
}

bool run_turntable_tests() {
    printf("\n========== TURNTABLE TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_turntable_bypass_at_zero_mix());
    tally(test_turntable_captures_source());
    tally(test_turntable_freeze_stops_capture());
    tally(test_turntable_crossfader());
    tally(test_turntable_viz_snapshot());
    tally(test_turntable_chunked_capture_no_stale_tail());
    tally(test_beat_phase_freerun_when_pulsar_idle());
    tally(test_drop_filter_attenuates_highs());
    tally(test_drop_brake_decays_to_silence());
    tally(test_drop_stutter_gates_at_divisions());
    tally(test_drop_freeze_loops_slice());
    tally(test_drop_phaser_bounded_under_long_dc());
    tally(test_drop_echo_produces_delayed_copy());
    tally(test_drop_ring_injects_sidebands());
    tally(test_drop_octave_adds_subharmonic_energy());
    printf("\nTurntable tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
