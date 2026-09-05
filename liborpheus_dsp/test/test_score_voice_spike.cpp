// SPIKE: can score_voice_alloc drive the existing OrpheusEngine voice bank to sound a
// chord? The bank was built for fixed-pitch keyboard slots written from ports, not for a
// scheduler allocating notes per event, and that is the one unproven assumption in Route B.
// Answering it decides where the score voice pool lives.
//
// Informational: prints findings, never fails a build.
#include "test_harness.h"
#include "score_voice_alloc.h"
#include <cstdio>
#include <cmath>
#include <vector>

// Goertzel: energy at one frequency. Cheaper than an FFT and all we need — "is this pitch
// present in the mix" — with no dependency on the spectrum unit.
static double bin_energy(const float* buf, int n, double freq, double sr) {
    const double w = 2.0 * M_PI * freq / sr;
    const double coeff = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (int i = 0; i < n; i++) {
        const double s0 = buf[i] + coeff * s1 - s2;
        s2 = s1; s1 = s0;
    }
    return std::sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / n;
}

static double midi_hz(int note) { return 440.0 * std::pow(2.0, (note - 69) / 12.0); }

bool run_score_voice_spike_tests() {
    printf("\n========== SPIKE: score pool driving the voice bank ==========\n");
    const int sr = 48000;
    const int frames = 16384;

    // A C major triad: three notes that must sound AT ONCE from one allocation pass.
    const int chord[3] = {60, 64, 67};   // C4 E4 G4

    OrpheusEngine* engine = orpheus_engine_create(sr);

    // The allocator picks slots; nothing about it knows this bank exists.
    ScoreVoicePool pool{};
    score_voice_pool_reset(pool);

    printf("\n-- allocating a triad through score_voice_alloc --\n");
    int slots[3];
    for (int i = 0; i < 3; i++) {
        slots[i] = score_voice_start(pool, /*note_id=*/i, /*part=*/0,
                                     static_cast<uint8_t>(chord[i]), /*velocity=*/100);
        printf("   note %d (MIDI %d) -> slot %d\n", i, chord[i], slots[i]);
    }
    const bool distinct = slots[0] != slots[1] && slots[1] != slots[2] && slots[0] != slots[2];
    printf("   distinct slots: %s\n", distinct ? "YES" : "NO");

    // Drive the bank from the pool. This is the seam under test: tune + gate per slot,
    // exactly what a scheduler would write per note.
    // ever_triggered must be set explicitly. It gates rendering ("0 = never gated on; skip
    // render until first gate") and the orpheus_engine_set_voice_* port helpers do not set
    // it, so a scheduler driving this bank has to write voice_params directly -- the first
    // real finding of this spike, and silent if missed: the voice simply never sounds.
    for (int i = 0; i < 3; i++) {
        const int v = slots[i];
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(0);
        engine->voice_params[v].tune.store(static_cast<float>(pool.voices[v].pitch));
        engine->voice_params[v].gate.store(1);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(0.5f);
        engine->voice_params[v].decay.store(0.9f);
    }

    // Voices render through GRAPH UNITS, not orpheus_engine_process — the second finding.
    // Each allocated slot gets its own unit, exactly as the wiring graph would build it.
    printf("\n-- rendering each allocated slot through its own voice unit --\n");
    printf("   %-6s %-10s %-10s %s\n", "slot", "MIDI", "Hz", "peak");
    std::vector<std::vector<float>> per_slot;
    double weakest_peak = 1e9;
    for (int i = 0; i < 3; i++) {
        const int v = slots[i];
        GraphUnit unit;
        setup_voice_unit(&unit, v);
        // Same loop as the harness's render_voice, but keeping the samples: it returns only
        // a peak, and the chord has to be analysed for pitch content.
        std::vector<float> buf;
        buf.reserve(frames);
        double pk = 0.0;
        for (int off = 0; off < frames; off += 128) {
            const int chunk = std::min(128, frames - off);
            unit_process_plaits(&unit, engine, chunk, (float)sr);
            for (int k = 0; k < chunk; k++) {
                const float s = unit.output_buffers[OPORT_OUT][k];
                buf.push_back(s);
                pk = std::max(pk, std::fabs((double)s));
            }
        }
        weakest_peak = std::min(weakest_peak, pk);
        printf("   %-6d %-10d %-10.1f %.4f\n", v, chord[i], midi_hz(chord[i]), pk);
        per_slot.push_back(std::move(buf));
    }

    // Sum them: this is the chord, and the pitches must be separable in the mix.
    const size_t n = per_slot[0].size();
    std::vector<float> mix(n, 0.0f);
    for (auto& b : per_slot)
        for (size_t i = 0; i < n && i < b.size(); i++) mix[i] += b[i] * 0.33f;

    printf("\n-- summed mix: is each pitch present? --\n");
    printf("   %-12s %-10s %s\n", "note", "Hz", "energy");
    double weakest = 1e9;
    for (int i = 0; i < 3; i++) {
        const double hz = midi_hz(chord[i]);
        const double e = bin_energy(mix.data(), (int)n, hz, sr);
        weakest = std::min(weakest, e);
        printf("   MIDI %-7d %-10.1f %.6f\n", chord[i], hz, e);
    }
    // A pitch NOT in the chord: if it reads as loud as the chord tones the measurement is
    // picking up broadband noise rather than the notes.
    const double off_hz = midi_hz(73);   // C#5, not in a C major triad
    const double off_e = bin_energy(mix.data(), (int)n, off_hz, sr);
    printf("   %-12s %-10.1f %.6f   (control, should be well below)\n", "MIDI 73", off_hz, off_e);

    const bool all_present = weakest > off_e * 2.0 && weakest_peak > 0.01;
    printf("\n   VERDICT: three simultaneous pitches from one pool: %s\n",
           all_present ? "CONFIRMED" : "NOT CONFIRMED");
    printf("   weakest chord tone / control = %.1fx\n", off_e > 0 ? weakest / off_e : 0.0);

    orpheus_engine_destroy(engine);
    printf("\n  Spike: informational, no pass/fail\n");
    return true;
}
