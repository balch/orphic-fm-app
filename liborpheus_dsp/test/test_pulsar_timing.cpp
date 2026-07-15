// Pulsar timing tests — drum-grid accuracy over the length of a song.
//
// Regression coverage for the "sloppy drums, worse toward the end" audit
// (docs/audio-timing-audit-belltolls.md):
//   1. Swing pair sum: a swung step pair must total exactly 2*samples_per_step
//      so the real grid tempo matches the nominal BPM that fixed-ms delays,
//      beat-synced LFOs, and the bass/grids/stutter units follow.
//   2. Sub-block onsets: drum hits must land on the true step boundary, not
//      snapped up to a full 512-sample block early.
//   3. Percussive probability floor: low-energy sections must not randomly
//      drop the kit's backbone hits.
//   4. Pattern stability: per-bar mutation must not accumulate ghosts or walk
//      velocities on percussive tracks between deja-vu resets.
//   5. Elastic tempo stays bounded at low energy.

#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "stmlib/utils/random.h"
#include <cstdio>
#include <cmath>
#include <cstring>

namespace {

constexpr float kSampleRate = 48000.0f;
constexpr int kBlock = 512;

GraphUnit make_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

OrpheusEngine* make_engine(void (*setup)(OrpheusEngine*), float bpm) {
    OrpheusEngine* engine = orpheus_engine_create(kSampleRate);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup(engine);
    // Pin both RNGs for byte-reproducible runs (see test_pulsar_signal_quality).
    engine->pulsar_seed.store(0xBEA7, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(bpm, std::memory_order_relaxed);
    return engine;
}

double nominal_samples_per_step(float bpm) {
    return static_cast<double>(kSampleRate) / ((static_cast<double>(bpm) / 60.0) * 4.0);
}

// Count sequencer step advances by watching track 0's playhead per block.
// At test BPMs samples_per_step >> kBlock, so at most one boundary lands in
// any block and every advance is observed.
struct StepCounter {
    int last_playhead = -1;
    long steps = 0;
    void observe(const PulsarState* state) {
        int ph = state->tracks[0].playhead;
        if (last_playhead >= 0 && ph != last_playhead) steps++;
        last_playhead = ph;
    }
};

// Detect percussive onsets in a mono buffer stream: a sample whose amplitude
// crosses the threshold while the recent past was quiet. `abs_pos` is the
// absolute sample index of the buffer's first sample.
struct OnsetDetector {
    float threshold;
    long refractory;  // min samples between onsets
    long last_onset = -1000000;
    long positions[4096];
    int count = 0;

    explicit OnsetDetector(float thresh, long refract)
        : threshold(thresh), refractory(refract) {}

    void feed(const float* buf, int frames, long abs_pos) {
        for (int i = 0; i < frames; i++) {
            float a = std::fabs(buf[i]);
            long pos = abs_pos + i;
            if (a > threshold && pos - last_onset > refractory
                && count < 4096) {
                positions[count++] = pos;
                last_onset = pos;
            }
        }
    }
};

// Silence every track but one so the drums bus carries a single voice.
void solo_pulsar_track(OrpheusEngine* engine, int track) {
    for (int t = 0; t < 8; t++) {
        float vol = (t == track) ? 1.0f : 0.0f;
        engine->pulsar_track_volume[t].store(vol, std::memory_order_relaxed);
    }
}

}  // namespace

// ── Test 1: swung pairs total 2S — real grid tempo == nominal BPM ──────────
static bool test_swing_pair_sum() {
    printf("\n=== Test: swing keeps real tempo at nominal BPM ===\n");
    const float bpm = 120.0f;
    OrpheusEngine* engine = make_engine(setup_cosmic_techno, bpm);
    // Heavy swing via the genre floor (now consumed by the clock), and
    // energy=1 to disable the elastic-tempo wander.
    engine->pulsar_genre_swing.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    GraphUnit unit = make_unit();

    // Warm up so smoothed macros settle and the state exists.
    for (int b = 0; b < 200; b++) unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    StepCounter counter;
    counter.observe(engine->pulsar_state);
    const int blocks = static_cast<int>(60.0f * kSampleRate) / kBlock;  // ~60s
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
        counter.observe(engine->pulsar_state);
    }

    double total_samples = static_cast<double>(blocks) * kBlock;
    double expected_steps = total_samples / nominal_samples_per_step(bpm);
    double ratio = static_cast<double>(counter.steps) / expected_steps;
    // The old accounting made each swung pair 2S + swing*0.5*S: at swing 0.5
    // the grid ran 11.1% slow (ratio ~0.889). Allow 1% for block rounding.
    bool ok = std::fabs(ratio - 1.0) < 0.01;
    printf("  steps=%ld expected=%.1f ratio=%.4f (swing=0.5) -- %s\n",
           counter.steps, expected_steps, ratio, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// ── Test 2: drum onsets land on the true step boundary, not block starts ───
static bool test_subblock_onset_alignment() {
    printf("\n=== Test: sub-block onset alignment ===\n");
    // 130 BPM -> samples_per_step = 5538.46, deliberately not a multiple of
    // the 512-frame block so block-quantized onsets would show up as inter-
    // onset errors of up to ~500 samples.
    const float bpm = 130.0f;
    OrpheusEngine* engine = make_engine(setup_dog_house, bpm);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    // Straight time and no tempo wander: swing floor 0, complexity 0 (kills
    // the macro swing), energy 1 (kills elastic tempo and probability gating).
    engine->pulsar_genre_swing.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.0f, std::memory_order_relaxed);
    solo_pulsar_track(engine, 0);  // kick only (dog_house: engine 21 both slots)
    GraphUnit unit = make_unit();

    for (int b = 0; b < 400; b++) unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    const double sps = nominal_samples_per_step(bpm);
    OnsetDetector det(0.08f, static_cast<long>(sps * 0.5));
    long pos = 0;
    const int blocks = static_cast<int>(30.0f * kSampleRate) / kBlock;
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
        det.feed(engine->pulsar_bus_drums_l, kBlock, pos);
        pos += kBlock;
    }

    // Each inter-onset interval must sit close to a whole multiple of the
    // step duration. Block-start quantization put every onset up to 511
    // samples early with a precessing error; the split render leaves only
    // the 24-sample voice-chunk remainder plus attack-detection slop.
    long worst = 0;
    int intervals = 0;
    for (int i = 1; i < det.count; i++) {
        double diff = static_cast<double>(det.positions[i] - det.positions[i - 1]);
        double mult = std::floor(diff / sps + 0.5);
        if (mult < 1.0) continue;
        long err = static_cast<long>(std::fabs(diff - mult * sps) + 0.5);
        if (err > worst) worst = err;
        intervals++;
    }
    bool ok = det.count >= 8 && intervals >= 4 && worst <= 96;
    printf("  onsets=%d intervals=%d worst_grid_error=%ld samples (%.2f ms) -- %s\n",
           det.count, intervals, worst,
           1000.0f * static_cast<float>(worst) / kSampleRate, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// ── Test 3: percussive tracks keep their backbone at low energy ────────────
static bool test_percussive_probability_floor() {
    printf("\n=== Test: percussive probability floor at low energy ===\n");
    const float bpm = 120.0f;

    // Count kick triggers straight from sequencer state (voice_active rising
    // edges, observed per block — kick gates are ~0.2 steps so they always
    // fall back between hits). Audio-independent, so quiet low-energy hits
    // still count.
    auto count_triggers = [&](float energy, float& peak_out) -> int {
        OrpheusEngine* engine = make_engine(setup_dog_house, bpm);
        engine->pulsar_step_count.store(16, std::memory_order_relaxed);
        trigger_vibe_load(engine);
        engine->pulsar_genre_swing.store(0.0f, std::memory_order_relaxed);
        engine->pulsar_energy.store(energy, std::memory_order_relaxed);
        engine->pulsar_space.store(0.0f, std::memory_order_relaxed);
        // complexity 0 pushes the déjà-vu reset interval (32*(1-c) loops)
        // past the measurement window so the pattern stays fixed throughout.
        engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
        solo_pulsar_track(engine, 0);  // kick only
        GraphUnit unit = make_unit();

        for (int b = 0; b < 800; b++) unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

        int trigs = 0;
        bool prev_active = engine->pulsar_state->tracks[0].voice_active;
        float peak = 0.0f;
        const int blocks = static_cast<int>(60.0f * kSampleRate) / kBlock;
        for (int b = 0; b < blocks; b++) {
            unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
            bool active = engine->pulsar_state->tracks[0].voice_active;
            if (active && !prev_active) trigs++;
            prev_active = active;
            for (int i = 0; i < kBlock; i++) {
                float a = std::fabs(engine->pulsar_bus_drums_l[i]);
                if (a > peak) peak = a;
            }
        }
        peak_out = peak;
        orpheus_engine_destroy(engine);
        return trigs;
    };

    float peak_high = 0.0f, peak_low = 0.0f;
    int high = count_triggers(1.0f, peak_high);  // energy >= 0.99 always fires
    int low  = count_triggers(0.2f, peak_low);   // outro/dub territory
    float ratio = (high > 0) ? static_cast<float>(low) / high : 0.0f;
    // With the 0.9 floor the kick keeps >=85% of its hits at outro energy.
    // Pre-fix, fire_prob at energy 0.2 was ~0.52-0.74 depending on velocity.
    bool ok = high >= 20 && ratio >= 0.85f;
    printf("  triggers high-energy=%d low-energy=%d ratio=%.3f peaks=[%.3f, %.3f] -- %s\n",
           high, low, ratio, peak_high, peak_low, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Test 4: percussive patterns don't accumulate mutations between resets ──
static bool test_percussive_pattern_stability() {
    printf("\n=== Test: percussive pattern stability across loops ===\n");
    OrpheusEngine* engine = make_engine(setup_cosmic_techno, 160.0f);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    // Moderate complexity: mutation runs, deja-vu reset stays far out
    // (reset_interval = max(8, 32*(1-complexity)) loops).
    engine->pulsar_complexity.store(0.4f, std::memory_order_relaxed);
    GraphUnit unit = make_unit();
    for (int b = 0; b < 40; b++) unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    PulsarState* state = engine->pulsar_state;
    auto run_loops = [&](int target_wraps) {
        int wraps = 0;
        int last = state->tracks[0].playhead;
        // Bound the wait generously; each loop is step_count steps.
        for (long guard = 0; wraps < target_wraps && guard < 200000; guard++) {
            unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
            int ph = state->tracks[0].playhead;
            if (ph < last) wraps++;
            last = ph;
        }
        return wraps == target_wraps;
    };

    if (!run_loops(2)) {
        printf("  FAIL: sequencer did not advance\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    // Snapshot percussive gates + velocities after 2 loops...
    bool gate_snap[kNumPulsarTracks][kMaxPulsarSteps];
    float vel_snap[kNumPulsarTracks][kMaxPulsarSteps];
    for (int t = 0; t < kNumPulsarTracks; t++) {
        for (int s = 0; s < kMaxPulsarSteps; s++) {
            gate_snap[t][s] = state->tracks[t].steps[s].gate;
            vel_snap[t][s] = state->tracks[t].steps[s].velocity;
        }
    }

    // ...then run 6 more loops (well inside the deja-vu window) and compare.
    if (!run_loops(6)) {
        printf("  FAIL: sequencer stalled during comparison run\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    int ghost_adds = 0, vel_changes = 0;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        if (state->tracks[t].role != TrackRole::PERCUSSIVE) continue;
        for (int s = 0; s < kMaxPulsarSteps; s++) {
            const PulsarStep& step = state->tracks[t].steps[s];
            if (step.gate && !gate_snap[t][s]) ghost_adds++;
            if (step.gate && gate_snap[t][s]
                && std::fabs(step.velocity - vel_snap[t][s]) > 1e-6f) {
                vel_changes++;
            }
        }
    }
    bool ok = (ghost_adds == 0) && (vel_changes == 0);
    printf("  ghost_adds=%d velocity_walks=%d over 6 loops -- %s\n",
           ghost_adds, vel_changes, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// ── Test 5: elastic tempo stays bounded at low energy ──────────────────────
static bool test_elastic_tempo_bounded() {
    printf("\n=== Test: elastic tempo bounded at low energy ===\n");
    const float bpm = 120.0f;
    OrpheusEngine* engine = make_engine(setup_cosmic_techno, bpm);
    engine->pulsar_genre_swing.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.2f, std::memory_order_relaxed);  // outro territory
    GraphUnit unit = make_unit();
    for (int b = 0; b < 200; b++) unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    StepCounter counter;
    counter.observe(engine->pulsar_state);
    const int blocks = static_cast<int>(60.0f * kSampleRate) / kBlock;
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
        counter.observe(engine->pulsar_state);
    }

    double total_samples = static_cast<double>(blocks) * kBlock;
    double expected_steps = total_samples / nominal_samples_per_step(bpm);
    double ratio = static_cast<double>(counter.steps) / expected_steps;
    // Drift ceiling at energy 0.2 is (1-0.2)*0.05 = 4%; the mean-reverting
    // walk averages far below it over a minute. The old ceiling (pre-cap,
    // with the slew fixed) allowed 12%.
    bool ok = std::fabs(ratio - 1.0) < 0.05;
    printf("  steps=%ld expected=%.1f ratio=%.4f (energy=0.2) -- %s\n",
           counter.steps, expected_steps, ratio, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_timing_tests() {
    printf("\n╔══════════════════════════════════════╗\n");
    printf("║  Pulsar Timing Tests                 ║\n");
    printf("╚══════════════════════════════════════╝\n");
    bool all = true;
    all &= test_swing_pair_sum();
    all &= test_subblock_onset_alignment();
    all &= test_percussive_probability_floor();
    all &= test_percussive_pattern_stability();
    all &= test_elastic_tempo_bounded();
    return all;
}
