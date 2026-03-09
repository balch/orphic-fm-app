# Native C++ Clock System Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal**: Move timing generation into the C++ ODWG graph for sample-accurate clock, drum triggers (Grids), random sequences (Marbles/Flux), and beat-quantized looping.

**Architecture**: Four new graph units — UNIT_CLOCK (master tempo), UNIT_GRIDS (drum patterns), UNIT_MARBLES (Flux random sequencer), UNIT_LOOPER (beat-quantized audio looper) — wired together via the existing ODWG binary graph format. Parameters flow through engine atomics (like clouds/rings/warps). All code is shared between Android and Desktop.

**Tech Stack**: C++ (liborpheus_dsp), Kotlin DSL (DefaultWiringGraph.kt), MI Grids + Marbles source ports

---

## 1. UNIT_CLOCK — Master Tempo Generator

**Purpose**: Generate sample-accurate clock pulses from a BPM value. All downstream units receive timing from this single source.

**Interface**:
- **Inputs**:
  - `IPORT_INPUT_A` — BPM (set via port map from Kotlin GlobalTempo, default 120)
  - `IPORT_INPUT_B` — run/stop (1.0 = running, 0.0 = stopped)
- **Outputs**:
  - `OPORT_OUT` — 24 PPQN clock pulse (1.0 on tick frames, 0.0 otherwise)
  - `OPORT_OUT_RIGHT` — beat pulse (1.0 on quarter-note boundaries)
- **State**:
  - `double phase` — fractional sample accumulator (double precision to avoid drift)
  - `int tick_count` — counts 0..23 within each beat for PPQN subdivision
  - `int beat_count` — counts beats within a bar (for future bar-level quantization)

**How it works**: Each frame, advance phase by `(bpm / 60.0) * 24.0 / sample_rate`. When phase crosses 1.0, emit a tick pulse. Every 24 ticks, emit a beat pulse. The pulse lands on the exact sample where the accumulator overflows — no jitter.

**Port map**:
```kotlin
map("org.balch.orpheus.plugins.tempo", "bpm", "clock", IPORT_INPUT_A)
map("org.balch.orpheus.plugins.tempo", "run", "clock", IPORT_INPUT_B)
```

---

## 2. UNIT_GRIDS — Drum Pattern Generator

**Purpose**: Port MI Grids as a graph unit. Generates 3 channels of drum triggers (kick, snare, hat) with density/pattern controls.

**Source**: MI Grids core is a lookup table of drum patterns with interpolation. The pattern ROM is ~3KB (3 instruments x 32 steps x 32 patterns).

**Interface**:
- **Inputs**:
  - `IPORT_INPUT_A` — clock pulse (from UNIT_CLOCK OPORT_OUT)
  - `IPORT_INPUT_B` — beat pulse (from UNIT_CLOCK OPORT_OUT_RIGHT, for step reset)
  - `IPORT_INPUT_C` — fill amount (0..1, global density)
- **Outputs**:
  - `OPORT_OUT` — kick triggers
  - `OPORT_OUT_RIGHT` — snare triggers
  - `OPORT_OUT_AUX` — hat triggers (requires new third output port)
- **Parameters** (via engine atomics):
  - `grids_density_kick`, `grids_density_snare`, `grids_density_hat` (0..1)
  - `grids_x`, `grids_y` — pattern map position (interpolates between archetypes)

**How it works**: On each clock tick, advance internal step counter. Look up pattern ROM at (x, y, step) for each channel, compare against density threshold. If above threshold, emit trigger (1.0 for ~1ms, then 0.0).

**Requires**: `kMaxOutputPorts` bump from 2 to 3 for `OPORT_OUT_AUX`.

**Integration with drums**: Grids trigger pulses gate existing UNIT_PLAITS drum voices directly via a new gate input (`IPORT_INPUT_C` on Plaits). `DrumBeatsViewModel` stops Kotlin-side polling.

---

## 3. UNIT_MARBLES — Flux Random Sequencer

**Purpose**: Full port of MI Marbles C++ source as a graph unit. Replaces Kotlin `FluxProcessor` with sample-accurate random gate/CV generation.

**Source scope** (~15 core files from `eurorack/marbles/`):
- `random/t_generator.h/.cc` — rhythmic/gate generation
- `random/x_y_generator.h/.cc` — CV sequence generation
- `random/quantizer.h/.cc` — scale quantization
- `random/lag_processor.h/.cc` — slew/smoothing
- `random/output_channel.h/.cc` — output conditioning
- `ramp/ramp_extractor.h/.cc` — clock recovery from pulses
- Support: `random/distributions.h/.cc`, `random/discrete_distribution.h/.cc`

UI/hardware layer (`drivers/`, `cv_reader*`, `ui.*`) is **not** needed.

**Interface**:
- **Inputs**:
  - `IPORT_INPUT_A` — clock pulse (from UNIT_CLOCK)
  - `IPORT_INPUT_B` — external CV (optional)
- **Outputs**:
  - `OPORT_OUT` — t1 gate output (rhythmic triggers)
  - `OPORT_OUT_RIGHT` — x1 CV output (random pitch/voltage)
  - `OPORT_OUT_AUX` — x2 CV output (second random channel)
- **Parameters** (via engine atomics):
  - `marbles_t_rate` — clock rate/division
  - `marbles_t_bias` — gate density bias
  - `marbles_t_jitter` — timing randomness
  - `marbles_x_spread` — CV spread/range
  - `marbles_x_bias` — CV center bias
  - `marbles_x_steps` — quantization steps
  - `marbles_deja_vu` — sequence lock (0 = random, 1 = locked loop)
  - `marbles_deja_vu_length` — loop length (2..16 steps)

**Port mapping**: These map directly to existing Flux UI knobs. `FluxViewModel` controls reroute from `FluxPlugin` → native engine atomics.

**Build approach**:
1. Copy ~15 core files into `liborpheus_dsp/src/marbles/`
2. Thin adapter: `unit_process_marbles()` holds MI objects, feeds clock to RampExtractor, calls TGenerator/XYGenerator Process(), writes outputs
3. Strip hardware dependencies (stmlib GPIO, ADC) → direct float reads from graph ports

---

## 4. UNIT_LOOPER — Beat-Quantized Audio Looper

**Purpose**: Sample-accurate looper that quantizes record/overdub/play transitions to beat boundaries.

**Interface**:
- **Inputs**:
  - `IPORT_INPUT_A` — audio in L (post-drive)
  - `IPORT_INPUT_B` — audio in R
  - `IPORT_INPUT_C` — beat pulse (from UNIT_CLOCK, for quantization)
- **Outputs**:
  - `OPORT_OUT` — audio out L
  - `OPORT_OUT_RIGHT` — audio out R
- **Parameters** (via engine atomics):
  - `looper_state` — 0=stop, 1=record, 2=play, 3=overdub
  - `looper_level` — playback level (0..1)
  - `looper_feedback` — overdub feedback (0..1)
  - `looper_quantize` — 0=immediate, 1=quantize to beat

**How it works**:
1. **Record**: On beat boundary (when quantize=1) or immediately, write stereo audio into circular buffer. Max ~30s at 48kHz (~5.5MB stereo).
2. **Play**: Loop playback from buffer, mixed with input passthrough.
3. **Overdub**: Read existing loop + new input → write back, scaled by feedback.
4. **Beat quantization**: State transitions deferred until next beat pulse on IPORT_INPUT_C.

**Buffer allocation**: Pre-allocate at graph load via `PARAM_MAX_LOOP` (default ~30s).

---

## 5. Graph Wiring & Integration

### Full clock subgraph (DefaultWiringGraph.kt)

```kotlin
val clock = clock("clock")
val grids = grids("grids")
clock.out to grids.inputA        // 24 PPQN clock
clock.beatOut to grids.inputB    // beat pulse for step reset

val marbles = marbles("marbles")
clock.out to marbles.inputA      // clock for ramp extractor

val looper = looper("looper")
driveL.out to looper.inputA      // audio in L
driveR.out to looper.inputB      // audio in R
clock.beatOut to looper.inputC   // beat sync

looper.out to delay.inputA
looper.outRight to delay.inputB
```

### Grids → Voice triggering

Wire grids outputs directly into Plaits voice gate inputs (graph-native). Add gate detection to `unit_process_plaits()`: when `IPORT_INPUT_C` crosses threshold (0→1), treat as note-on with current tune.

### Marbles → Voice pitch

Marbles CV output routes to voice tune modulation via `IPORT_PITCH_MOD` on target Plaits units.

### Parameter routing

Engine atomics for all Grids/Marbles/Looper parameters (matching clouds/rings/warps pattern). Port map entries only for audio/clock connections and a few key parameters.

### kMaxOutputPorts change

Bump from 2 → 3 to support `OPORT_OUT_AUX` for Grids hat channel and Marbles x2 CV.

---

## 6. Implementation Phases

### Phase A: Foundation (prerequisite for all)
- Bump `kMaxOutputPorts` to 3, add `OPORT_OUT_AUX`
- Implement `UNIT_CLOCK` (~50 LOC)
- Wire clock into graph, verify BPM port map
- Add clock state to `OrpheusEngine`

### Phase B: Grids (depends on A)
- Port MI Grids pattern ROM + interpolation into `liborpheus_dsp/src/grids/`
- Implement `UNIT_GRIDS` processor
- Add gate input to `unit_process_plaits()`
- Wire grids → drum voices
- Migrate `DrumBeatsViewModel` away from Kotlin polling

### Phase C: Marbles (depends on A, parallel with B)
- Copy MI Marbles core sources into `liborpheus_dsp/src/marbles/`
- Strip hardware dependencies
- Implement `UNIT_MARBLES` processor
- Wire marbles → voice pitch modulation
- Migrate `FluxPlugin`/`FluxViewModel` to native engine

### Phase D: Looper (depends on A, parallel with B/C)
- Implement `UNIT_LOOPER` with circular buffer + beat quantization
- Wire into graph
- Migrate `LooperPlugin` controls to native engine

### Dependency graph

```
Phase A (Clock foundation)
  ├── Phase B (Grids)
  ├── Phase C (Marbles) — parallel with B
  └── Phase D (Looper) — parallel with B/C
```

### Parity impact

Closes Gaps #9 (Looper), #14 (Flux CV), #16 (Drum routing) from gap analysis. Partial progress on #10 (Bender — clock enables sync later). Brings parity from 12/20 → 15-16/20.

---

## 7. Testing Strategy

- **Unit tests**: Each C++ unit gets standalone test (render N frames, verify output pulses/CV at correct sample positions)
- **Integration test**: Build graph with clock→grids, verify triggers appear at correct samples
- **A/B comparison**: Same preset, JSyn vs C++, verify drum patterns and flux sequences sound equivalent
- **Drift test**: Run clock for 10 minutes, verify no accumulated timing drift (double-precision phase)
