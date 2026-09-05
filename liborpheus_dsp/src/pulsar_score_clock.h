#pragma once

// Musical position for notated scores, in ticks at 96 PPQ. Deliberately independent of
// Pulsar's 16-steps-per-bar grid: score tracks do not live on that grid, which is what
// lets a piece in 2/4 work at all.
//
// Position accumulates in a double so a long piece does not drift; consumers compare
// against (int)tick_pos so everything downstream sees integer ticks. At 200 BPM and
// 48kHz the per-sample increment is ~0.0067 ticks, which a float would lose inside a
// minute.

static constexpr int kScorePpq = 96;

struct ScoreClock {
    double tick_pos = 0.0;
};

inline double score_ticks_per_sample(float bpm, float sample_rate) {
    if (bpm <= 0.0f || sample_rate <= 0.0f) return 0.0;
    return (static_cast<double>(bpm) * static_cast<double>(kScorePpq))
         / (60.0 * static_cast<double>(sample_rate));
}

// A non-positive bpm freezes the clock rather than running it backwards; the engine
// substitutes a default before calling, so this is defence in depth.
inline void score_clock_advance(ScoreClock& clock, int frames, float bpm, float sample_rate) {
    if (frames <= 0) return;
    clock.tick_pos += static_cast<double>(frames) * score_ticks_per_sample(bpm, sample_rate);
}

inline void score_clock_reset(ScoreClock& clock) {
    clock.tick_pos = 0.0;
}
