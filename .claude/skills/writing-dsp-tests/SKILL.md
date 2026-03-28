---
name: writing-dsp-tests
description: Use when writing or reviewing C++ DSP unit tests in liborpheus_dsp/, when adding new DSP modules/parameters, or when a DSP bug was found that existing tests missed. Covers output level verification, modulation routing validation, all-settings sweeps, and DC offset detection.
---

# Writing DSP Tests

## Overview

DSP tests must verify **signal correctness at modulation destinations**, not just crash-freedom. A test that checks `[-5, +5]` on a signal normalized to `[-1, +1]` catches nothing. Tests must exercise every parameter combination and measure levels as they'd be experienced by downstream consumers (voices, effects, mix bus).

## Core Principle

**Test what the listener hears, not what the code computes.** A value of 0.5 in `tides_output_buffer[3]` means nothing until you know it becomes `0.25 semitones of pitch shift` at the modulation destination.

## Production Graph for Tests

**Always use the production graph** (`default_graph.odwg`) rather than hand-building test graphs. This ensures tests exercise the real execution order, wiring, and unit interactions.

### Pipeline
1. **Kotlin exports** the graph: `./gradlew :core:dsp-engine:jvmTest --tests "*ExportOdwgTest*"`
   - Source: `core/dsp-engine/src/jvmTest/kotlin/.../ExportOdwgTest.kt`
   - Calls `buildDefaultWiringGraph()` from `DefaultWiringGraph.kt`
   - Writes binary ODWG to `liborpheus_dsp/test/data/default_graph.odwg`
2. **CMake auto-runs** this via `add_dependencies(orpheus_dsp_test export_graph)` — no manual step needed when building via cmake
3. **C++ tests load** via `load_production_graph(engine)` from `test_harness.h`
4. **Process** via `orpheus_graph_process(graph, engine, buf, num_frames)` where `graph = engine->graph.load()`

### Usage Pattern
```cpp
OrpheusEngine* engine = orpheus_engine_create(48000.0f);
if (!load_production_graph(engine)) return false;

// Set parameters on engine atomics
engine->tides_mix.store(1.0f);
engine->voice_params[0].gate.store(1);
// ...

// Process through full graph (all units in production order)
float buf[128 * 2];  // stereo interleaved
auto* graph = engine->graph.load(std::memory_order_acquire);
orpheus_graph_process(graph, engine, buf, 64);

// Read results from engine buffers (tides_output_buffer, etc.)
orpheus_engine_destroy(engine);  // frees graph too
```

### When to Regenerate
Re-export the graph (`./gradlew :core:dsp-engine:jvmTest --tests "*ExportOdwgTest*"`) after changing:
- `DefaultWiringGraph.kt` (unit wiring, execution order)
- Adding/removing units or connections
- Changing unit type enums in `orpheus_graph.h`

## Test Categories (all required for new DSP modules)

### 1. Init & Crash-Freedom
- Create engine, init unit, process one block, destroy. No crash, no NaN/Inf.
- Minimal — gate for every new unit but not sufficient alone.

### 2. Bypass Behavior
- When bypass condition is met (e.g., `mix <= 0.001`), ALL outputs must be exactly zero.
- Check unit output ports AND engine-level buffers (e.g., `warps_source_buffers`, `tides_output_buffer`).
- Check visualization rings are zeroed.

### 3. Output Level Verification (CRITICAL)
**This is the test category most often missing.**

```
For EVERY combination of:
  - All enum settings (ramp_mode, output_mode, range, gate_source, etc.)
  - Representative knob positions (0.0, 0.25, 0.5, 0.75, 1.0 for continuous params)
  - Multiple block counts (enough to capture full LFO cycles at lowest freq)

Measure:
  - Peak absolute value per channel
  - DC offset (mean value over full cycle)
  - Whether output is unipolar [0, peak] or bipolar [-peak, +peak]
```

**Bounds must be TIGHT.** If normalization targets `[-1, +1]`, assert `[-1.1, +1.1]` (small margin for filter transients), NOT `[-5, +5]`.

### 4. Modulation Destination Impact
For each channel that routes to a modulation destination, compute the **actual effect**:

| Destination | Formula | Acceptable Range |
|---|---|---|
| Pitch (semitones) | `channel_peak * scaling_factor * depth` | < 1.0 semi at full depth |
| FM (Hz offset) | `channel_peak * depth * 200.0` | < 100 Hz for LFO-rate sources |
| Timbre/morph/harmonics | `channel_peak * depth * 0.5` | [0, 1] after clamping |
| Amplitude/mix | `channel_peak` | [0, 1] |

**Print the impact values** so humans can audit whether they sound right.

### 5. DC Offset Detection
Unipolar outputs (envelopes, AD/AR modes) used as modulation sources create constant pitch/timbre offsets instead of oscillation. Test:

```cpp
float sum = 0.0f;
for (int i = 0; i < total_samples; i++) sum += buffer[i];
float dc_offset = sum / total_samples;
// Flag if |dc_offset| > 0.1 * peak AND destination is pitch
```

### 6. Cross-Source Level Matching
When a module can be selected as a mod source alongside others (LFO, Flux, etc.), verify that switching between sources at the same depth produces **comparable** modulation amounts. A 10x level difference between LFO and Tides as pitch mod sources is a bug.

### 7. Unconditional Routing Audit
Check for modulation paths that activate **without user selection**. Pattern to flag:

```cpp
// BAD: activates whenever tides_mix > 0, regardless of mod source setting
if (tides_mix > 0.001f && lfo_depth > 0.001f) {
    mod_pitch += tides_output_buffer[3][mid] * lfo_depth * 0.5f;
}

// GOOD: only activates when user selects Tides as mod source
if (fm_mod_source == 4) {
    // ... use tides buffers
}
```

## Test Template

```cpp
static bool test_MODULE_peak_levels() {
    printf("\n=== Test: MODULE peak levels (all settings) ===\n");

    const char* setting_a_names[] = { "OPT0", "OPT1", "OPT2" };
    const char* setting_b_names[] = { "OPTX", "OPTY" };
    bool all_pass = true;

    for (int a = 0; a < NUM_A; a++) {
        for (int b = 0; b < NUM_B; b++) {
            OrpheusEngine* engine = orpheus_engine_create(48000.0f);
            // Set ALL parameters explicitly (never rely on defaults)
            engine->module_mix.store(1.0f);
            engine->module_setting_a.store(a);
            engine->module_setting_b.store(b);
            // ... set ALL continuous params to known values

            GraphUnit u = {};
            u.type = UNIT_MODULE;
            u.enabled = true;
            unit_init(&u, 48000.0f);

            float pk[NUM_CHANNELS] = {};
            float sum[NUM_CHANNELS] = {};
            int total_samples = 0;

            // Run enough blocks to capture full cycle
            for (int blk = 0; blk < 100; blk++) {
                unit_process_MODULE(&u, engine, 64, 48000.0f);
                for (int ch = 0; ch < NUM_CHANNELS; ch++) {
                    for (int i = 0; i < 64; i++) {
                        float v = engine->module_output_buffer[ch][i];
                        float a = std::fabs(v);
                        if (a > pk[ch]) pk[ch] = a;
                        sum[ch] += v;
                    }
                }
                total_samples += 64;
            }

            // Check bounds (tight!)
            const float kMaxExpected = 1.1f;  // normalized + small margin
            for (int ch = 0; ch < NUM_CHANNELS; ch++) {
                if (pk[ch] > kMaxExpected) {
                    printf("  FAIL: %s+%s ch%d peak=%.4f exceeds %.1f\n",
                           setting_a_names[a], setting_b_names[b], ch, pk[ch], kMaxExpected);
                    all_pass = false;
                }
            }

            // Report modulation impact
            float pitch_semi = pk[PITCH_CH] * 0.5f;  // fm_depth=1, scaling=0.5
            float dc = sum[PITCH_CH] / total_samples;
            printf("  %s+%s: pk=[%.3f,%.3f,%.3f,%.3f] pitch=%.2f semi dc=%.4f%s\n",
                   setting_a_names[a], setting_b_names[b],
                   pk[0], pk[1], pk[2], pk[3],
                   pitch_semi, dc,
                   (std::fabs(dc) > 0.1f * pk[PITCH_CH]) ? " [DC!]" : "");

            orpheus_engine_destroy(engine);
        }
    }
    return all_pass;
}
```

## Common Mistakes

| Mistake | Why It's Wrong | Fix |
|---|---|---|
| Bounds check of `[-5, +5]` on normalized output | 5x too generous, catches nothing | Use `[-1.1, +1.1]` or tighter |
| Only testing default settings | Bugs hide in non-default mode combos | Sweep ALL enum combinations |
| Checking finiteness only | `3.0` is finite but will destroy pitch | Check magnitude AND destination impact |
| Not printing diagnostic values | Can't audit if levels "sound right" | Always print peak, DC, and destination effect |
| Testing output in isolation | Buffer is fine but routing multiplies by 200 | Test the downstream formula too |
| Ignoring unipolar vs bipolar | Unipolar envelope as pitch mod = constant detune | Flag DC offset on pitch-routed channels |
| Relying on engine defaults | Stale or uninitialized state masks bugs | Set EVERY parameter explicitly in each test |

## Checklist for New DSP Module Tests

- [ ] Init/destroy without crash
- [ ] Bypass zeros ALL outputs (ports + engine buffers + viz)
- [ ] Peak levels checked with TIGHT bounds for ALL setting combos
- [ ] Modulation destination impact computed and printed
- [ ] DC offset flagged for pitch-routed channels
- [ ] Level-matched against other mod sources (LFO, Flux)
- [ ] No unconditional modulation routing without user selection
- [ ] Enough blocks processed to capture full cycle at lowest frequency
- [ ] All continuous params tested at multiple positions (not just default)
