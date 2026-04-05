#include "orpheus_units.h"
#include "orpheus_engine.h"
#include "pulsar_pattern_gen.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <chrono>

static constexpr float kTidesNorm = 0.125f;

// ═══════════════════════════════════════════════════════════════════════
// Pulsar Beat Machine — Clock, Sequencer, Voice Rendering
// ═══════════════════════════════════════════════════════════════════════
//
// 8-track step sequencer with OrpheusVoice per track, Tides envelopes,
// algorithmic pattern generation, constant-power panning, and macro modulation.
// Runs entirely on the audio thread. One heap allocation on first call
// (PulsarState with 8 voices); all subsequent processing is RT-safe.
// State is owned by OrpheusEngine and freed in orpheus_engine_destroy().

// ── Helpers ──────────────────────────────────────────────────────────

static inline float lerp_macro(float macro, const PulsarMacroTarget& t) {
    return t.min_value + macro * (t.max_value - t.min_value);
}

static inline float clamp01(float x) {
    return x < 0.0f ? 0.0f : (x > 1.0f ? 1.0f : x);
}

// Constant-power pan: left = cos(angle), right = sin(angle)
// pan in [-1, 1], 0 = center
static inline void constant_power_pan(float pan, float& gain_l, float& gain_r) {
    float angle = (clamp01(pan * 0.5f + 0.5f)) * 1.5707963f; // pi/2
    gain_l = std::cos(angle);
    gain_r = std::sin(angle);
}

// ── Scale quantization ──────────────────────────────────────────────

static int quantize_to_scale(int note, uint8_t root, const PulsarScale& scale) {
    if (scale.count >= 12) return note;
    int rel = ((note - root) % 12 + 12) % 12;
    int octave_base = note - rel;

    int best = scale.degrees[0];
    int best_dist = 12;
    for (int i = 0; i < scale.count; i++) {
        int dist = rel - scale.degrees[i];
        if (dist < 0) dist = -dist;
        if (dist > 6) dist = 12 - dist;
        if (dist < best_dist) {
            best_dist = dist;
            best = scale.degrees[i];
        }
    }

    int result = octave_base + best;
    // Ensure result is close to original note (handle octave boundary)
    while (result < note - 6 && result + 12 <= 96) result += 12;
    while (result > note + 6 && result - 12 >= 24) result -= 12;
    if (result < 24) result += 12;
    if (result > 96) result -= 12;
    return result;
}

// ── PRNG for mutation ────────────────────────────────────────────────

static inline uint32_t xorshift32(uint32_t& state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}

// Returns 0.0-1.0
static inline float rand01(uint32_t& state) {
    return static_cast<float>(xorshift32(state) & 0xFFFF) / 65535.0f;
}

// Deterministic hash for consistent per-step values (not random each block)
static inline uint32_t step_hash(int step, int track, int loop) {
    uint32_t h = static_cast<uint32_t>(step * 7919 + track * 104729 + loop * 15485863);
    h ^= h >> 16; h *= 0x45d9f3b; h ^= h >> 16;
    return h;
}

// ── Mutation: evolve patterns each loop ─────────────────────────────

static void mutate_patterns(PulsarState* state, float complexity, OrpheusEngine* engine) {
    state->loop_count++;

    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];
        bool is_melodic = (t >= 3 && t <= 4);  // BASS=3, KEYS=4

        for (int s = 0; s < ts.step_count; s++) {
            PulsarStep& step = ts.steps[s];
            uint32_t h = step_hash(s, t, state->loop_count);
            float roll = static_cast<float>(h & 0xFFFF) / 65535.0f;

            // Ghost notes: activate inactive steps with low velocity
            if (!step.gate) {
                float ghost_prob = complexity * 0.08f;  // up to 8% chance per step
                if (roll < ghost_prob) {
                    step.gate = true;
                    step.velocity = 0.15f + roll * 0.15f / std::max(ghost_prob, 0.001f);
                    step.duration = 0.2f;
                    // Keep existing note (from preset)
                }
                continue;
            }

            // Accent variation: slightly vary existing velocities
            float accent_range = complexity * 0.15f;
            float accent_offset = (static_cast<float>((h >> 8) & 0xFFFF) / 65535.0f - 0.5f) * 2.0f * accent_range;
            step.velocity = clamp01(step.velocity + accent_offset);

            // Note drift for melodic and effect tracks (3-7)
            if (t >= 3) {
                float drift_prob = complexity * 0.1f;
                float drift_roll = static_cast<float>((h >> 16) & 0xFFFF) / 65535.0f;
                if (drift_roll < drift_prob) {
                    // Drift by ±1-2 semitones from raw_note, then quantize
                    int offsets[] = {-2, -1, 1, 2};
                    int idx = static_cast<int>((h >> 24) & 0x3);
                    int new_note = static_cast<int>(step.raw_note) + offsets[idx];
                    if (new_note >= 24 && new_note <= 96) {
                        uint8_t root = static_cast<uint8_t>(
                            engine->pulsar_root_note.load(std::memory_order_relaxed));
                        int si = engine->pulsar_scale_index.load(std::memory_order_relaxed);
                        if (si < 0) si = 0;
                        if (si >= kNumPulsarScales) si = kNumPulsarScales - 1;
                        const PulsarScale& scale = kPulsarScales[si];
                        step.raw_note = static_cast<uint8_t>(new_note);
                        step.note = static_cast<uint8_t>(
                            quantize_to_scale(new_note, root, scale));
                    }
                }
            }
        }
    }

    // Step count mutation (high complexity only, melodic tracks 2-4)
    if (complexity > 0.7f) {
        float step_mut_prob = (complexity - 0.7f) * 0.1f;
        for (int t = 2; t < kNumPulsarTracks; t++) {
            PulsarTrackState& ts = state->tracks[t];
            float roll = rand01(state->mutation_seed);
            if (roll < step_mut_prob) {
                int delta = (rand01(state->mutation_seed) > 0.5f) ? 1 : -1;
                if (rand01(state->mutation_seed) > 0.7f) delta *= 2;
                int new_count = ts.step_count + delta;
                if (new_count >= 12 && new_count <= 20) {
                    ts.step_count = new_count;
                }
            }
        }
    }
}

// ── Tides envelope parameter computation ────────────────────────────

static void compute_tides_params(
    PulsarEnvelopeProfile profile,
    float energy, float complexity, float space, float mood,
    uint32_t& seed,
    float& out_shape, float& out_pw, float& out_smoothness, float& out_freq_mult
) {
    // Each profile responds to knobs differently for musical variety:
    // - SHAPE: morphs envelope curve (low=soft, high=snappy)
    // - PW: attack/decay balance (low=fast attack, high=slow attack)
    // - SMOOTHNESS: <0.5 adds LP filter (rounded), >0.5 adds wavefold (complex)
    // - FREQ_MULT: scales the base envelope frequency (>1 = shorter, <1 = longer)
    switch (profile) {
        case ENV_PROFILE_RHYTHM:
            // Energy → punchier (shorter, snappier). Mood → tonal character.
            out_shape = 0.7f + energy * 0.2f;           // snappier at high energy
            out_pw = 0.15f + (1.0f - energy) * 0.15f;   // faster attack at high energy
            out_smoothness = 0.3f + mood * 0.2f;         // mood rounds the transient
            out_freq_mult = 0.5f + energy * 1.5f;        // shorter at high energy
            break;
        case ENV_PROFILE_MELODIC:
            // Space → longer, dreamier. Mood → warmer shape. Energy → sustain.
            out_shape = 0.3f + mood * 0.4f;              // mood morphs curve
            out_pw = 0.2f + space * 0.5f;                // space extends decay
            out_smoothness = 0.4f - space * 0.2f;        // space adds LP smoothing
            out_freq_mult = 0.3f + (1.0f - space) * 0.7f; // space extends envelope
            break;
        case ENV_PROFILE_EFFECT:
            // Space → ambient swells. Mood → shape character. Complexity → variation.
            out_shape = 0.2f + mood * 0.6f;              // mood: soft → bright
            out_pw = 0.3f + space * 0.5f;                // space: slow attack swell
            out_smoothness = 0.5f - space * 0.35f;       // high space → very smooth
            out_freq_mult = 0.2f + (1.0f - space) * 0.5f; // long at high space
            break;
        case ENV_PROFILE_WILD:
        default:
            // Everything cross-modulates. Complexity adds chaos.
            out_shape = mood * 0.6f + energy * 0.3f + rand01(seed) * 0.1f;
            out_pw = complexity * 0.7f + space * 0.2f;
            out_smoothness = 0.5f - space * 0.3f + complexity * 0.3f + rand01(seed) * 0.15f;
            out_freq_mult = 0.3f + energy * 0.5f + complexity * 0.3f;
            break;
    }
}

// ── FX track probability ────────────────────────────────────────────

static float compute_fx_probability(float energy, float complexity) {
    float low_prob = std::max(0.0f, (1.0f - energy) - 0.6f) * 2.5f;
    float high_prob = std::max(0.0f, (complexity - 0.7f) * 3.3f)
                    * std::max(0.0f, (energy - 0.6f) * 2.5f);
    return std::max(low_prob, high_prob);
}

// ── Scene loading ────────────────────────────────────────────────────

static void load_scene(PulsarState* state, int scene_index, OrpheusEngine* engine) {
    if (scene_index < 0) scene_index = 0;
    if (scene_index >= kNumPulsarScenes) scene_index = kNumPulsarScenes - 1;

    const PulsarScenePreset& scene = kPulsarScenes[scene_index];

    // Mix time entropy so the same kit generates fresh patterns each load
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    uint32_t time_bits = static_cast<uint32_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(now).count());
    state->seed_counter += time_bits;
    uint32_t base_seed = state->seed_counter * 2654435761u;

    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];
        const PulsarTrackPreset& tp = scene.tracks[t];

        // Copy config (NO step data — patterns are generated)
        ts.step_count = tp.step_count;
        ts.volume = tp.volume;
        ts.pan = tp.pan;
        ts.harmonics = tp.harmonics;
        ts.timbre = tp.timbre;
        ts.morph = tp.morph;
        ts.macro_map = tp.macro_map;
        ts.envelope_profile = tp.envelope_profile;

        // Generate pattern algorithmically
        generate_track_pattern(ts, t, scene, base_seed);

        // Write engine defaults to atomics
        engine->pulsar_track_engine_edm[t].store(tp.engine_edm, std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(tp.engine_space, std::memory_order_relaxed);
        ts.engine_index = tp.engine_edm;

        // Reset state
        ts.playhead = 0;
        ts.gate_timer = 0.0f;
        ts.voice_active = false;
        ts.swing_offset = 0.0;
        ts.tides_env.Init();
        ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
        ts.tides_env_level = 0.0f;
        ts.current_pitch = 60.0f;
        ts.target_pitch = 60.0f;
        ts.glide_rate = 0.0f;
        ts.prev_step_gated = false;
    }

    state->current_scene = scene_index;
    state->last_root_note = scene.root_note;
    state->last_scale_index = scene.scale_index;
    state->clock_accumulator = 0.0;
    state->mutation_seed = base_seed;
    state->loop_count = 0;
    state->loops_since_reset = 0;
    std::memset(state->drunk_offsets, 0, sizeof(state->drunk_offsets));
    std::memset(state->drunk_targets, 0, sizeof(state->drunk_targets));
    state->tempo_drift = 0.0f;
    state->tempo_drift_target = 0.0f;
    state->tempo_drift_countdown = 0;
}

// ── Main process function ────────────────────────────────────────────

void unit_process_pulsar(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    if (num_frames > kMaxFrames) num_frames = kMaxFrames;

    float* out_l = engine->pulsar_out_l;
    float* out_r = engine->pulsar_out_r;

    // ── Lazy-init persistent state (owned by engine, freed in destroy) ──
    PulsarState* state = engine->pulsar_state;
    if (!state) {
        state = new PulsarState();
        // Zero POD fields only — don't memset the whole struct, which
        // contains OrpheusVoice instances with vtable pointers.
        state->clock_accumulator = 0.0;
        state->current_scene = -1;  // force scene load
        state->initialized = false;
        state->smooth_energy = 0.5f;
        state->smooth_complexity = 0.3f;
        state->smooth_space = 0.4f;
        state->smooth_mood = 0.5f;
        // Seed from high-resolution clock so patterns differ across launches
        auto now = std::chrono::steady_clock::now().time_since_epoch();
        state->seed_counter = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(now).count());
        state->mutation_seed = state->seed_counter ^ 0xDEADBEEF;
        state->loop_count = 0;
        state->loops_since_reset = 0;
        std::memset(state->voice_alloc_buffers, 0, sizeof(state->voice_alloc_buffers));
        std::memset(state->drunk_offsets, 0, sizeof(state->drunk_offsets));
        std::memset(state->drunk_targets, 0, sizeof(state->drunk_targets));
        state->tempo_drift = 0.0f;
        state->tempo_drift_target = 0.0f;
        state->tempo_drift_countdown = 0;
        state->last_root_note = -1;
        state->last_scale_index = -1;
        for (int t = 0; t < kNumPulsarTracks; t++) {
            PulsarTrackState& ts = state->tracks[t];
            ts.step_count = 0;
            ts.playhead = 0;
            ts.engine_index = 0;
            ts.volume = 0.0f;
            ts.pan = 0.0f;
            ts.harmonics = 0.0f;
            ts.timbre = 0.0f;
            ts.morph = 0.0f;
            ts.gate_timer = 0.0f;
            ts.voice_active = false;
            ts.swing_offset = 0.0;
            ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
            ts.tides_env_level = 0.0f;
            ts.envelope_profile = ENV_PROFILE_RHYTHM;
            ts.current_pitch = 60.0f;
            ts.target_pitch = 60.0f;
            ts.glide_rate = 0.0f;
            ts.prev_step_gated = false;
        }
        engine->pulsar_state = state;
    }

    if (!state->initialized) {
        for (int t = 0; t < kNumPulsarTracks; t++) {
            stmlib::BufferAllocator allocator(
                state->voice_alloc_buffers[t], kVoiceAllocBytes_Pulsar);
            state->tracks[t].voice.Init(&allocator);
            state->tracks[t].tides_env.Init();
        }
        load_scene(state, 0, engine);
        state->initialized = true;
    }

    // ── Check playing state ──
    bool playing = engine->pulsar_playing.load(std::memory_order_relaxed) != 0;
    float mix = engine->pulsar_mix.load(std::memory_order_relaxed);
    if (!playing || mix <= 0.001f) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        std::memset(u->output_buffers[OPORT_OUT_RIGHT], 0, num_frames * sizeof(float));
        return;
    }

    // ── Handle scene change ──
    int scene = engine->pulsar_scene.load(std::memory_order_relaxed);
    if (scene != state->current_scene) {
        load_scene(state, scene, engine);
    }

    // ── Re-quantize melodic notes when root/scale changes live ──
    int live_root = engine->pulsar_root_note.load(std::memory_order_relaxed);
    int live_scale = engine->pulsar_scale_index.load(std::memory_order_relaxed);
    if (live_scale < 0) live_scale = 0;
    if (live_scale >= kNumPulsarScales) live_scale = kNumPulsarScales - 1;

    if (live_root != state->last_root_note || live_scale != state->last_scale_index) {
        uint8_t root = static_cast<uint8_t>(live_root);
        const PulsarScale& scale = kPulsarScales[live_scale];
        for (int t = 3; t < kNumPulsarTracks; t++) {
            PulsarTrackState& ts = state->tracks[t];
            for (int s = 0; s < ts.step_count; s++) {
                if (ts.steps[s].gate) {
                    // Re-quantize from raw_note (original intent) to avoid drift
                    ts.steps[s].note = static_cast<uint8_t>(
                        quantize_to_scale(ts.steps[s].raw_note, root, scale));
                }
            }
        }
        state->last_root_note = live_root;
        state->last_scale_index = live_scale;
    }

    // ── Read and smooth macros (~10ms coefficient) ──
    float smooth_coeff = 1.0f - std::exp(-1.0f / (0.01f * sample_rate));

    float target_energy     = engine->pulsar_energy.load(std::memory_order_relaxed);
    float target_complexity = engine->pulsar_complexity.load(std::memory_order_relaxed);
    float target_space      = engine->pulsar_space.load(std::memory_order_relaxed);
    float target_mood       = engine->pulsar_mood.load(std::memory_order_relaxed);

    state->smooth_energy     += smooth_coeff * (target_energy     - state->smooth_energy);
    state->smooth_complexity += smooth_coeff * (target_complexity - state->smooth_complexity);
    state->smooth_space      += smooth_coeff * (target_space      - state->smooth_space);
    state->smooth_mood       += smooth_coeff * (target_mood       - state->smooth_mood);

    float energy     = clamp01(state->smooth_energy);
    float complexity = clamp01(state->smooth_complexity);
    float space      = clamp01(state->smooth_space);
    float mood       = clamp01(state->smooth_mood);

    // Complexity and space are used below for swing, variation, morph, etc.

    // ── Determine BPM ──
    float bpm_override = engine->pulsar_bpm_override.load(std::memory_order_relaxed);
    float bpm = (bpm_override > 0.0f)
        ? bpm_override
        : engine->clock_bpm.load(std::memory_order_relaxed);
    if (bpm <= 0.0f) bpm = 120.0f;

    // 16th-note grid: 4 steps per beat
    double steps_per_second = (static_cast<double>(bpm) / 60.0) * 4.0;
    double samples_per_step = static_cast<double>(sample_rate) / steps_per_second;

    // ── Elastic tempo: slow random walk scaled by (1 - energy) ──
    float max_drift = (1.0f - energy) * 0.15f;

    state->tempo_drift_countdown -= num_frames;
    if (state->tempo_drift_countdown <= 0) {
        state->tempo_drift_target = (rand01(state->mutation_seed) - 0.5f) * 2.0f * max_drift;
        int bars = 4 + static_cast<int>(rand01(state->mutation_seed) * 4.0f);
        state->tempo_drift_countdown = static_cast<int>(samples_per_step * 16.0 * bars);
    }

    float drift_coeff = 1.0f - std::exp(-1.0f / std::max(static_cast<float>(samples_per_step * 32.0f), 1.0f));
    state->tempo_drift += drift_coeff * (state->tempo_drift_target - state->tempo_drift);
    state->tempo_drift = std::max(-max_drift, std::min(max_drift, state->tempo_drift));

    samples_per_step *= (1.0 + static_cast<double>(state->tempo_drift));

    // ── Zero output buffers ──
    std::memset(out_l, 0, num_frames * sizeof(float));
    std::memset(out_r, 0, num_frames * sizeof(float));

    // Zero per-bus accumulation buffers
    std::memset(engine->pulsar_bus_keys_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_keys_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_drums_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_drums_r, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_bass_l, 0, num_frames * sizeof(float));
    std::memset(engine->pulsar_bus_bass_r, 0, num_frames * sizeof(float));

    // ── Clock: find step boundaries within this block ──
    // Swing: odd steps are delayed by swing_amount * 0.5 * samples_per_step.
    // We track a global step parity to alternate even/odd thresholds.
    static constexpr int kMaxStepBoundaries = 32;
    int step_boundary_samples[kMaxStepBoundaries];
    int num_boundaries = 0;

    // Use track 0's playhead parity to determine global swing phase
    // (all tracks advance together on the same clock)
    bool step_is_odd = (state->tracks[0].playhead % 2) != 0;

    for (int i = 0; i < num_frames; i++) {
        state->clock_accumulator += 1.0;

        // Swing: alternate threshold between straight and delayed
        // swing_amount from complexity macro (use track 0's macro as global ref)
        float swing_amount = lerp_macro(complexity, state->tracks[0].macro_map.complexity_swing);
        double threshold = samples_per_step;
        if (step_is_odd) {
            threshold += static_cast<double>(swing_amount) * 0.5 * samples_per_step;
        }

        if (state->clock_accumulator >= threshold) {
            state->clock_accumulator -= threshold;
            step_is_odd = !step_is_odd;
            if (num_boundaries < kMaxStepBoundaries) {
                step_boundary_samples[num_boundaries++] = i;
            }
        }
    }

    // ── Per-track: advance sequencer + render voice ──
    float track_buffer[kMaxFrames];

    for (int t = 0; t < kNumPulsarTracks; t++) {
        PulsarTrackState& ts = state->tracks[t];
        const PulsarTrackMacroMap& mm = ts.macro_map;

        // ── Apply engine selection from atomics (immediate UI response) ──
        {
            int edm = engine->pulsar_track_engine_edm[t].load(std::memory_order_relaxed);
            int spa = engine->pulsar_track_engine_space[t].load(std::memory_order_relaxed);
            if (edm == spa) {
                ts.engine_index = edm;
            } else if (energy > 0.6f) {
                ts.engine_index = edm;
            } else if (energy < 0.4f) {
                ts.engine_index = spa;
            }
            // In crossfade zone (0.4-0.6): keep current — probabilistic pick at loop boundary
        }

        // ── Apply macro modulation ──
        float mod_volume    = lerp_macro(energy, mm.energy_volume);
        float mod_harmonics = lerp_macro(mood, mm.mood_harmonics);
        float mod_timbre    = lerp_macro(mood, mm.mood_timbre);
        float mod_morph     = lerp_macro(space, mm.space_decay);
        float variation_amt = lerp_macro(complexity, mm.complexity_variation);

        float track_volume = ts.volume * clamp01(mod_volume);
        // Percussion group mix — scales tracks 0-2 (KICK, PERC, HIHAT)
        float perc_mix = engine->pulsar_perc_mix.load(std::memory_order_relaxed);
        if (t <= 2) track_volume *= perc_mix;

        // ── Pan gains ──
        float pan_l, pan_r;
        constant_power_pan(ts.pan, pan_l, pan_r);

        // ── Process step boundaries for this track ──
        // Advance playhead at each step boundary.
        // Determine gate state for voice rendering.
        for (int b = 0; b < num_boundaries; b++) {
            int prev_playhead = ts.playhead;
            ts.playhead = (ts.playhead + 1) % ts.step_count;

            // Detect loop wrap (playhead wrapped to 0) — trigger mutation
            if (ts.playhead == 0 && prev_playhead > 0 && t == 0) {
                mutate_patterns(state, complexity, engine);

                // Voice crossfade: select EDM or space engine per track based on energy
                for (int vt = 0; vt < kNumPulsarTracks; vt++) {
                    int edm = engine->pulsar_track_engine_edm[vt].load(std::memory_order_relaxed);
                    int spa = engine->pulsar_track_engine_space[vt].load(std::memory_order_relaxed);
                    if (edm == spa) continue;

                    if (energy > 0.6f) {
                        state->tracks[vt].engine_index = edm;
                    } else if (energy < 0.4f) {
                        state->tracks[vt].engine_index = spa;
                    } else {
                        float p_edm = (energy - 0.4f) / 0.2f;
                        float roll = rand01(state->mutation_seed);
                        state->tracks[vt].engine_index = (roll < p_edm) ? edm : spa;
                    }
                }

                // Update drunk timing targets
                float max_drunk = (1.0f - energy) * complexity * 0.3f;
                for (int dt = 0; dt < kNumPulsarTracks; dt++) {
                    for (int ds = 0; ds < state->tracks[dt].step_count; ds++) {
                        float target = (rand01(state->mutation_seed) - 0.5f) * 2.0f * max_drunk
                                       * static_cast<float>(samples_per_step);
                        state->drunk_targets[dt][ds] = target;
                        state->drunk_offsets[dt][ds] += 0.5f * (target - state->drunk_offsets[dt][ds]);
                    }
                }

                // Déjà vu reset: regenerate patterns from original seed periodically
                state->loops_since_reset++;
                int reset_interval = std::max(4, static_cast<int>(32.0f * (1.0f - complexity)));
                if (state->loops_since_reset >= reset_interval) {
                    state->loops_since_reset = 0;
                    const PulsarScenePreset& sp = kPulsarScenes[state->current_scene];
                    uint32_t reset_seed = state->seed_counter * 2654435761u;
                    for (int rt = 0; rt < kNumPulsarTracks; rt++) {
                        PulsarTrackState& rts = state->tracks[rt];
                        const PulsarTrackPreset& rtp = sp.tracks[rt];
                        rts.step_count = rtp.step_count;
                        generate_track_pattern(rts, rt, sp, reset_seed);
                    }
                }
            }

            const PulsarStep& step = ts.steps[ts.playhead];

            if (step.gate) {
                // FX track (7): only fire at energy extremes
                bool fx_skip = false;
                if (t == 7) {
                    float fx_prob = compute_fx_probability(energy, complexity);
                    if (fx_prob <= 0.001f) {
                        ts.prev_step_gated = false;
                        fx_skip = true;
                    }
                }

                if (!fx_skip) {
                    // Probability gating: energy controls base fire probability
                    float base_prob = energy * 0.6f + 0.4f;  // 40% at energy=0, 100% at energy=1
                    float vel_boost = step.velocity * (1.0f - base_prob) * 0.5f;
                    float fire_prob = base_prob + vel_boost;

                    uint32_t prob_hash = step_hash(ts.playhead, t, state->loop_count);
                    float prob_roll = static_cast<float>(prob_hash & 0xFFFF) / 65535.0f;
                    bool fires = prob_roll < fire_prob || energy >= 0.99f;

                    if (fires) {
                        // Apply velocity variation from complexity
                        float vel = step.velocity;
                        if (variation_amt > 0.001f) {
                            uint32_t vh = step_hash(ts.playhead, t, state->loop_count);
                            float var_offset = (static_cast<float>(vh & 0xFFFF) / 65535.0f - 0.5f)
                                              * 2.0f * variation_amt * 0.2f;
                            vel = clamp01(vel + var_offset);
                        }

                        // Force retrigger: reset gate so Tides sees a rising edge
                        ts.tides_prev_gate = stmlib::GATE_FLAG_LOW;
                        ts.voice_active = true;
                        float drunk = state->drunk_offsets[t][ts.playhead];
                        float base_gate = static_cast<float>(step.duration * samples_per_step);
                        ts.gate_timer = std::max(base_gate + drunk, base_gate * 0.25f);

                        // Pitch glide
                        float new_note = static_cast<float>(step.note);
                        ts.target_pitch = new_note;

                        if (ts.prev_step_gated && space > 0.01f) {
                            float glide_samples = space * 0.5f * static_cast<float>(samples_per_step);
                            if (glide_samples > 1.0f) {
                                ts.glide_rate = (ts.target_pitch - ts.current_pitch) / glide_samples;
                            } else {
                                ts.current_pitch = ts.target_pitch;
                                ts.glide_rate = 0.0f;
                            }
                        } else {
                            ts.current_pitch = ts.target_pitch;
                            ts.glide_rate = 0.0f;
                        }
                        ts.prev_step_gated = true;
                    } else {
                        ts.prev_step_gated = false;
                    }
                }
            }
        }

        // Decrement gate timer by num_frames (block-rate approximation).
        if (ts.gate_timer > 0.0f) {
            ts.gate_timer -= static_cast<float>(num_frames);
            if (ts.gate_timer <= 0.0f) {
                ts.gate_timer = 0.0f;
                ts.voice_active = false;
            }
        }

        // Apply pitch glide
        if (ts.glide_rate != 0.0f) {
            ts.current_pitch += ts.glide_rate * static_cast<float>(num_frames);
            if ((ts.glide_rate > 0 && ts.current_pitch > ts.target_pitch) ||
                (ts.glide_rate < 0 && ts.current_pitch < ts.target_pitch)) {
                ts.current_pitch = ts.target_pitch;
                ts.glide_rate = 0.0f;
            }
        }

        // Determine note and accent from current step
        float note_for_render = ts.current_pitch;
        float accent_for_render = 0.8f;
        if (ts.playhead >= 0 && ts.playhead < ts.step_count) {
            const PulsarStep& step = ts.steps[ts.playhead];
            if (step.gate) {
                accent_for_render = step.velocity;
            }
        }

        // Accent boost: high-velocity steps push harmonics/timbre
        if (accent_for_render > 0.7f) {
            float accent_boost = (accent_for_render - 0.7f) * 0.3f;
            mod_harmonics = clamp01(mod_harmonics + accent_boost);
            mod_timbre = clamp01(mod_timbre + accent_boost);
        }

        int gate_for_render = ts.voice_active ? 1 : 0;

        // ── Render voice ──
        ts.voice.Render(
            ts.engine_index,
            gate_for_render,
            note_for_render,
            clamp01(mod_harmonics),
            clamp01(mod_timbre),
            clamp01(mod_morph),
            accent_for_render,
            track_buffer,
            num_frames
        );

        // ── Apply Tides envelope ──
        // Self-enveloped engines bypass: 19-23 (String, Modal, BD, SD, HH), 2-4 (SixOp)
        bool self_enveloped = (ts.engine_index >= 19 && ts.engine_index <= 23)
                           || (ts.engine_index >= 2 && ts.engine_index <= 4);

        if (!self_enveloped) {
            int envelope_mode = engine->pulsar_envelope_mode.load(std::memory_order_relaxed);

            // Blend mode (2): AD at high energy (EDM), Tides at low energy (Space)
            if (envelope_mode == 2) {
                envelope_mode = (energy > 0.5f) ? 0 : 1;
            }

            if (envelope_mode == 1) {
                // === TIDES ENVELOPE ===
                // Tides PolySlopeGenerator AD envelope on channel[0].
                // shift=0.6 → internal≈0.2 → channel_index≈1.0 → channel[0] output.

                // Compute envelope params from profile + macros
                float env_shape, env_pw, env_smoothness, env_freq_mult;
                compute_tides_params(ts.envelope_profile, energy, complexity, space, mood,
                                     state->mutation_seed, env_shape, env_pw, env_smoothness, env_freq_mult);

                // Build per-sample gate flags from voice_active state
                stmlib::GateFlags env_flags[kMaxFrames];
                for (int i = 0; i < num_frames; i++) {
                    env_flags[i] = stmlib::ExtractGateFlags(
                        ts.tides_prev_gate, ts.voice_active);
                    ts.tides_prev_gate = env_flags[i];
                }

                // Base envelope frequency per profile, then scaled by macro-driven freq_mult
                float base_freq;
                switch (ts.envelope_profile) {
                    case ENV_PROFILE_RHYTHM:  base_freq = 0.0005f; break;   // ~40ms base
                    case ENV_PROFILE_MELODIC: base_freq = 0.00008f; break;  // ~260ms base
                    case ENV_PROFILE_EFFECT:  base_freq = 0.00003f; break;  // ~700ms base
                    case ENV_PROFILE_WILD:
                    default:                  base_freq = 0.00006f; break;  // ~350ms base
                }
                float env_freq = base_freq * env_freq_mult;

                tides::PolySlopeGenerator::OutputSample env_out[kMaxFrames];
                ts.tides_env.Render(
                    tides::RAMP_MODE_AD,
                    tides::OUTPUT_MODE_AMPLITUDE,
                    tides::RANGE_CONTROL,
                    env_freq,
                    env_pw,
                    env_shape,
                    env_smoothness,
                    0.6f,     // shift: output on channel[0]
                    env_flags, nullptr, env_out, static_cast<size_t>(num_frames)
                );

                for (int i = 0; i < num_frames; i++) {
                    float env = env_out[i].channel[0] * kTidesNorm;
                    if (env < 0.0f) env = 0.0f;
                    if (env > 1.0f) env = 1.0f;
                    ts.tides_env_level = env;
                    track_buffer[i] *= env;
                }
            } else {
                // === SIMPLE AD ENVELOPE ===
                float attack_samples = 200.0f;
                float decay_coeff = 1.0f - (4.0f / (0.01f * sample_rate + space * sample_rate * 0.5f));
                if (decay_coeff < 0.99f) decay_coeff = 0.99f;
                if (decay_coeff > 0.99999f) decay_coeff = 0.99999f;

                for (int i = 0; i < num_frames; i++) {
                    if (ts.voice_active && ts.tides_env_level < 1.0f) {
                        ts.tides_env_level += 1.0f / attack_samples;
                        if (ts.tides_env_level > 1.0f) ts.tides_env_level = 1.0f;
                    } else if (!ts.voice_active) {
                        ts.tides_env_level *= decay_coeff;
                        if (ts.tides_env_level < 0.001f) ts.tides_env_level = 0.0f;
                    }
                    track_buffer[i] *= ts.tides_env_level;
                }
            }
        }

        // ── Mix to stereo with constant-power pan ──
        float vol = track_volume;
        float track_peak = 0.0f;

        // Classify track into bus: engine-type for drums, track role for bass.
        // Track 3 is always the bass track across all scenes, regardless of engine.
        PulsarBusType bus;
        if (t == 3) {
            bus = PULSAR_BUS_BASS;
        } else {
            int engine_id = ts.engine_index;
            if (engine_id < 0) engine_id = 0;
            if (engine_id >= 24) engine_id = 0;
            bus = kEngineBusType[engine_id];
        }

        // Select bus output pointers
        float* bus_l;
        float* bus_r;
        switch (bus) {
            case PULSAR_BUS_DRUMS:
                bus_l = engine->pulsar_bus_drums_l;
                bus_r = engine->pulsar_bus_drums_r;
                break;
            case PULSAR_BUS_BASS:
                bus_l = engine->pulsar_bus_bass_l;
                bus_r = engine->pulsar_bus_bass_r;
                break;
            default: // PULSAR_BUS_KEYS
                bus_l = engine->pulsar_bus_keys_l;
                bus_r = engine->pulsar_bus_keys_r;
                break;
        }

        for (int i = 0; i < num_frames; i++) {
            float s = track_buffer[i] * vol;
            out_l[i] += s * pan_l;
            out_r[i] += s * pan_r;
            // Also accumulate into the per-bus buffer
            bus_l[i] += s * pan_l;
            bus_r[i] += s * pan_r;
            float a = std::fabs(s);
            if (a > track_peak) track_peak = a;
        }
        // Write per-track peak to viz ring
        engine->viz_rings[VIZ_PULSAR_TRACK_0 + t].write(track_peak);
    }

    // Copy bus buffers to warps_source_buffers for turntable capture.
    // warps_source_buffers were zeroed at frame start; += accumulates with other sources.
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[0][i] +=
            (engine->pulsar_bus_keys_l[i] + engine->pulsar_bus_keys_r[i]) * 0.5f;
        engine->warps_source_buffers[1][i] +=
            (engine->pulsar_bus_drums_l[i] + engine->pulsar_bus_drums_r[i]) * 0.5f;
        engine->warps_source_buffers[9][i] +=
            (engine->pulsar_bus_bass_l[i] + engine->pulsar_bus_bass_r[i]) * 0.5f;
    }

    // Apply mix gain BEFORE bus limiter so soft_limit catches the boosted peaks.
    // Previously gain was applied after limiting, sending 1.5-1.7 peaks into the
    // master limiter which caused audible pumping on dense bass patterns.
    static constexpr float kPulsarOutputGain = 3.0f;
    float output_gain = mix * kPulsarOutputGain;
    for (int i = 0; i < num_frames; i++) {
        out_l[i] *= output_gain;
        out_r[i] *= output_gain;
    }

    // Bus limiter: soft-clip the boosted 8-track sum to prevent digital overs.
    // soft_limit is linear below 0.5, tanh saturation above — natural bus compression.
    for (int i = 0; i < num_frames; i++) {
        out_l[i] = soft_limit(out_l[i]);
        out_r[i] = soft_limit(out_r[i]);
    }

    // ── Copy to graph output buffers for effects routing ──
    std::memcpy(u->output_buffers[OPORT_OUT], out_l, num_frames * sizeof(float));
    std::memcpy(u->output_buffers[OPORT_OUT_RIGHT], out_r, num_frames * sizeof(float));

    // ── Write visualization data ──
    auto& viz = engine->pulsar_viz;
    for (int t = 0; t < kNumPulsarTracks; t++) {
        const PulsarTrackState& ts = state->tracks[t];
        viz.playheads[t] = ts.playhead;
        viz.step_counts[t] = ts.step_count;
        for (int s = 0; s < ts.step_count; s++) {
            viz.step_gates[t][s] = ts.steps[s].gate;
            viz.step_velocities[t][s] = ts.steps[s].velocity;
        }
    }
    engine->pulsar_viz_version.fetch_add(1, std::memory_order_release);
}
