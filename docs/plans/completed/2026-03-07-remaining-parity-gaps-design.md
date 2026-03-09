# Remaining C++ DSP Parity Gaps — Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close all 6 remaining gaps (#6, #10, #11, #12, #13, #15) between the JSyn and C++ audio engines, achieving full 20/20 parity.

**Architecture:** All DSP logic lives in C++. Kotlin only forwards parameters via `nativeSetPort`. New engine atomics follow the existing pattern (UI thread writes, audio thread reads with `memory_order_relaxed`). Two new graph unit types (UNIT_BENDER, UNIT_PER_STRING_BENDER) plus modifications to existing unit processors and graph wiring.

**Tech Stack:** C++ (orpheus_units.cpp, orpheus_engine.h/cpp), Kotlin DSL (DefaultWiringGraph.kt, WiringGraphDsl.kt)

---

## Phase A: Voice Coupling (Gap #11)

Each voice duo (0-1, 2-3, ..., 10-11) cross-modulates pitch. Voice A's envelope level shifts Voice B's pitch, and vice versa.

### Engine State

```cpp
// In OrpheusEngine:
float voice_envelope[kNumVoices] = {};         // peak follower output per voice
std::atomic<float> coupling_depth{0.0f};       // 0 = off, user control
```

### Processing (in unit_process_plaits)

After Plaits renders, update the peak follower:
```cpp
voice_envelope[idx] = voice_envelope[idx] * 0.999f + (1.0f - 0.999f) * peak;
```

Before computing pitch, add partner's envelope:
```cpp
int partner = (idx % 2 == 0) ? idx + 1 : idx - 1;
if (partner >= 0 && partner < kNumVoices) {
    float coupling = engine->coupling_depth.load(std::memory_order_relaxed);
    float partner_env = engine->voice_envelope[partner];
    actual_note += partner_env * coupling * 24.0f;  // 0..2 octaves at full depth
}
```

### Port Map

```kotlin
map("org.balch.orpheus.plugins.voice", "coupling_depth", "clock", IPORT_INPUT_C)
// Or direct engine atomic routing in orpheus_engine.cpp set_port
```

---

## Phase B: Mod Source Routing + FM Cross-Modulation (Gaps #12, #13)

Each voice duo selects a modulation source (OFF, VOICE_FM, LFO, FLUX) that feeds FM and Plaits timbre modulation.

### Engine State

```cpp
// Per-duo modulation
float voice_last_output[kNumVoices] = {};       // previous block's peak output
float marbles_cv_output[2] = {};                // cached Marbles X1/X2 CV
std::atomic<int> mod_source[6] = {};            // per-duo: 0=OFF, 1=VOICE_FM, 2=LFO, 3=FLUX
std::atomic<float> mod_depth[6] = {};           // per-duo timbre mod depth
std::atomic<float> fm_depth[6] = {};            // per-duo FM depth
std::atomic<int> fm_cross_quad{0};              // 0=normal duo, 1=cross-quad circular
```

### Mod Source Selection (in unit_process_plaits)

```cpp
int duo = idx / 2;
int src = engine->mod_source[duo].load(std::memory_order_relaxed);
float mod_signal = 0.0f;

switch (src) {
    case 1: { // VOICE_FM
        int fm_source;
        if (!engine->fm_cross_quad.load(std::memory_order_relaxed)) {
            fm_source = (idx % 2 == 0) ? idx + 1 : idx - 1;  // partner
        } else {
            fm_source = (idx - 2 + 8) % 8;  // cross-quad circular
        }
        mod_signal = engine->voice_last_output[fm_source];
        break;
    }
    case 2: mod_signal = engine->lfo_output_value; break;          // LFO
    case 3: mod_signal = engine->marbles_cv_output[duo % 2]; break; // FLUX
}

float timbre_mod = mod_signal * engine->mod_depth[duo].load(std::memory_order_relaxed);
float fm_mod = mod_signal * engine->fm_depth[duo].load(std::memory_order_relaxed);
```

Apply `timbre_mod` as offset to Plaits timbre parameter. Apply `fm_mod` as pitch offset (scaled to semitones).

After render, store output peak:
```cpp
engine->voice_last_output[idx] = peak;
```

Cache Marbles CV after UNIT_MARBLES processes:
```cpp
engine->marbles_cv_output[0] = marbles_unit.output_buffers[OPORT_OUT_RIGHT][last_sample];
engine->marbles_cv_output[1] = marbles_unit.output_buffers[OPORT_AUX][last_sample];
```

### Port Map

Per-duo entries for mod_source, mod_depth, fm_depth, plus fm_cross_quad global.

---

## Phase C: Resonator Routing (Gap #6)

Replace the simple mono-mix-to-Rings with full 4-input excitation/bypass architecture.

### Graph Wiring (DefaultWiringGraph.kt)

```
Excitation path:
  grains.out × drumExciteGainL  ─┐
  grains.outRight × drumExciteGainR ─┤
  mvL × synthExciteGainL ────────┤──→ excitationSumL/R → ringsHalf → reso.input
  mvR × synthExciteGainR ────────┘

Bypass path (inverse gains):
  grains.out × drumBypassGainL  ─┐
  grains.outRight × drumBypassGainR ─┤──→ bypassSumL/R
  mvL × synthBypassGainL ────────┤
  mvR × synthBypassGainR ────────┘

Output mix:
  reso.out × wetGain + excitationSum × dryGain + bypassSum → driveL
  reso.outRight × wetGain + excitationSum × dryGain + bypassSum → driveR
```

### targetMix Control

Engine atomic `resonator_target_mix` (0..1). Kotlin computes gain values in `DspSynthEngine` and forwards via port map:

- targetMix <= 0.5: drumExcite = 1.0, synthExcite = targetMix * 2
- targetMix > 0.5: drumExcite = (1 - targetMix) * 2, synthExcite = 1.0
- Bypass = 1 - Excite (for each source)

### Engine State

```cpp
std::atomic<float> resonator_target_mix{0.5f};
std::atomic<float> resonator_mix{0.5f};        // wet/dry
```

### Port Map

6 multiply node gains (drumExciteL/R, synthExciteL/R, drumBypassL/R, synthBypassL/R) + wetGain + dryGain.

---

## Phase D: Warps Source Routing (Gap #15)

Runtime selection of carrier and modulator sources from 7 options.

### Source Buffer System

Each unit writes its output to an engine-level source buffer after processing:

```cpp
// In OrpheusEngine:
static constexpr int kNumWarpsSources = 7;
float warps_source_buffers[kNumWarpsSources][kMaxFrames] = {};
// 0=SYNTH, 1=DRUMS(grains), 2=REPL(voices 8-11), 3=LFO, 4=RESONATOR(aux), 5=WARPS(feedback), 6=FLUX
std::atomic<int> warps_carrier_source{0};
std::atomic<int> warps_modulator_source{0};
float warps_feedback_buffer[2][kMaxFrames] = {};  // previous warps output
```

### Processing (in unit_process_warps)

Before calling Warps Process(), copy the selected source:

```cpp
int carrier_src = engine->warps_carrier_source.load(std::memory_order_relaxed);
int mod_src = engine->warps_modulator_source.load(std::memory_order_relaxed);

// Copy selected source into warps input
std::memcpy(carrier_in, engine->warps_source_buffers[carrier_src], num_frames * sizeof(float));
std::memcpy(mod_in, engine->warps_source_buffers[mod_src], num_frames * sizeof(float));
```

### Source Population

Each unit writes to the appropriate source buffer at the end of its process function:
- `unit_process_plaits` (voices 0-7 summed → SYNTH[0], voices 8-11 → REPL[2])
- `unit_process_clouds` → DRUMS[1]
- `unit_process_hyper_lfo` → LFO[3]
- `unit_process_rings` → RESONATOR[4] (aux output)
- `unit_process_warps` → WARPS[5] (feedback, written at end)
- `unit_process_marbles` → FLUX[6]

### Graph Changes

Remove fixed resonator→warps connections. Warps reads from engine source buffers instead.

---

## Phase E: Bender & PerStringBender (Gap #10)

### E1: UNIT_BENDER (Global Bend)

New graph unit. Takes bend amount from engine atomic, produces 3 outputs.

#### Engine State

```cpp
// Global bender
std::atomic<float> bend_amount{0.0f};          // -1..+1
std::atomic<float> bend_max_semitones{24.0f};
std::atomic<float> bend_timbre_mod{0.3f};
std::atomic<float> bend_spring_vol{0.4f};
std::atomic<float> bend_tension_vol{0.015f};

// Internal state (not atomic, audio thread only)
float bend_tension_phase{0.0f};
float bend_tension_env{0.0f};
int   bend_tension_env_stage{0};       // 0=off, 1=attack, 2=decay, 3=sustain, 4=release
float bend_spring_phase{0.0f};
float bend_spring_env{0.0f};
int   bend_spring_env_stage{0};
float bend_wobble_phase{0.0f};
float bend_random_lfo_phase{0.0f};
bool  bend_was_active{false};
```

#### Processing

```cpp
void unit_process_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float amount = engine->bend_amount.load(std::memory_order_relaxed);
    bool active = fabsf(amount) > 0.05f;

    // State transitions
    if (active && !engine->bend_was_active) trigger_tension_envelope();
    if (!active && engine->bend_was_active) trigger_spring_envelope();
    engine->bend_was_active = active;

    for (int i = 0; i < num_frames; i++) {
        // Pitch CV: cubic curve → exponential
        float cubic = amount * amount * amount;  // preserves sign
        float semitones = cubic * max_bend;
        out_pitch[i] = powf(2.0f, semitones / 12.0f) - 1.0f;

        // Timbre CV
        out_timbre[i] = amount * timbre_mod_depth;

        // Audio: tension + spring
        float tension = process_tension_osc(amount, sr);
        float spring = process_spring_osc(sr);
        out_audio[i] = tension + spring;
    }
}
```

#### Outputs
- OPORT_OUT: pitch CV
- OPORT_OUT_RIGHT: timbre CV
- OPORT_AUX: synthesized audio

### E2: UNIT_PER_STRING_BENDER (4 strings)

New graph unit managing 4 strings with full audio synthesis.

#### Engine State

```cpp
// Per-string state (4 strings, audio thread only)
struct StringState {
    float bend_amount{0.0f};
    float voice_mix{0.0f};
    bool  is_active{false};
    bool  was_active{false};
    // Oscillators
    float tension_phase{0.0f};
    float tension_env{0.0f};
    int   tension_env_stage{0};
    float spring_phase{0.0f};
    float spring_env{0.0f};
    int   spring_env_stage{0};
    float wobble_phase{0.0f};
    float pluck_phase{0.0f};
    float pluck_env{0.0f};
    int   pluck_env_stage{0};
    float slide_phase{0.0f};
    float slide_lfo_phase{0.0f};
    float slide_ramp{0.0f};
};
StringState string_state[4];

// Per-string atomics (from UI)
std::atomic<float> string_bend[4] = {};
std::atomic<float> string_mix[4] = {};
std::atomic<int>   string_active[4] = {};
std::atomic<float> string_base_freq[4] = {400.f, 550.f, 700.f, 850.f};

// Slide bar
std::atomic<float> slide_bar_y{0.0f};
std::atomic<float> slide_bar_x{0.0f};

// Output arrays (read by unit_process_plaits)
float voice_bend_cv[8] = {};       // pitch bend per voice
float voice_mix_cv[8] = {};        // voice A/B crossfade per voice
```

#### Processing

```cpp
void unit_process_per_string_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float audio_sum = 0.0f;

    for (int s = 0; s < 4; s++) {
        auto& st = engine->string_state[s];
        float bend = engine->string_bend[s].load(std::memory_order_relaxed);
        float mix = engine->string_mix[s].load(std::memory_order_relaxed);
        bool active = engine->string_active[s].load(std::memory_order_relaxed) != 0;

        // Direction: strings 2,3 are inverted (right hand)
        float direction = (s < 2) ? 1.0f : -1.0f;
        float directed_bend = bend * direction;

        // State transitions
        if (active && !st.was_active) trigger_tension(st);
        if (!active && st.was_active) { trigger_spring(st); trigger_pluck(st); }
        st.was_active = active;
        st.bend_amount = directed_bend;
        st.voice_mix = mix;

        // Compute voice CVs (2 voices per string)
        int v0 = s * 2, v1 = s * 2 + 1;
        float cubic = directed_bend * directed_bend * directed_bend;
        float semi = cubic * 12.0f;
        engine->voice_bend_cv[v0] = semi;
        engine->voice_bend_cv[v1] = semi;

        // Voice mix: non-linear crossfade
        float volA, volB;
        if (mix <= 0.25f) { volA = 1.0f; volB = mix / 0.25f; }
        else if (mix >= 0.75f) { volA = (1.0f - mix) / 0.25f; volB = 1.0f; }
        else { volA = 1.0f; volB = 1.0f; }
        engine->voice_mix_cv[v0] = volA;
        engine->voice_mix_cv[v1] = volB;

        // Audio synthesis (per-string tension + spring + pluck + slide)
        for (int i = 0; i < num_frames; i++) {
            float tension = process_string_tension(st, sr, s);
            float spring = process_string_spring(st, sr, s);
            float pluck = process_string_pluck(st, sr, s);
            float slide = process_string_slide(st, engine, sr, s);
            out[i] += tension + spring + pluck + slide;
        }
    }
}
```

### E3: Integration

In `unit_process_plaits`, apply bend CV and mix CV:
```cpp
float bend_offset = engine->voice_bend_cv[idx];
actual_note += bend_offset;

// Voice mix CV scales volume
float mix_vol = engine->voice_mix_cv[idx];
// Apply as additional volume multiplier (default 1.0 when bender inactive)
```

Strum trigger: When pluck envelope triggers, set `engine->rings_strum.store(1)`.

Graph wiring:
```kotlin
val bender = bender("bender")
val perStringBender = perStringBender("psb")
perStringBender.out to clipL.input      // per-string audio → output
perStringBender.outRight to clipR.input
bender.aux to delay.inputA              // bender audio → delay send
bender.aux to delay.inputB
```

---

## Phase F: Gap Analysis Update + Testing

- Update gap analysis doc: mark all 6 gaps FIXED (20/20)
- Add unit tests for coupling, FM, bender CV output
- Integration test: full graph with all units active

---

## Execution Order

| Phase | Gap(s) | New Units | Effort |
|-------|--------|-----------|--------|
| A | #11 Voice coupling | None (modify Plaits) | ~1 day |
| B | #12, #13 FM + mod source | None (modify Plaits) | ~3 days |
| C | #6 Resonator routing | None (graph rewire) | ~3 days |
| D | #15 Warps source routing | None (modify Warps) | ~2 days |
| E1 | #10 Global bender | UNIT_BENDER | ~3 days |
| E2 | #10 Per-string bender | UNIT_PER_STRING_BENDER | ~4 days |
| E3 | #10 Bender integration | Wire CVs + strum | ~2 days |
| F | All | Tests + docs | ~1 day |
