# Direct Engine Rendering — Bypass plaits::Voice

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace `plaits::Voice::Render()` with direct `plaits::Engine::Render()` calls, eliminating the LPG, AGC limiter, and int16 round-trip to match Kotlin's clean float signal path. Revert all eurorack source modifications.

**Architecture:** Create a lightweight `OrpheusVoice` struct in our own code that holds a `plaits::EngineRegistry`, allocates engine memory, handles trigger edge detection, and calls `Engine::Render()` for clean float output. Apply our own `outGain` + `softLimit()` matching Kotlin. Both `unit_process_plaits` (graph path) and `orpheus_engine_process` (fallback path) switch to this new wrapper.

**Tech Stack:** C++ (liborpheus_dsp), Plaits engine classes (unmodified eurorack source)

---

## Context

### Current Signal Path (C++ via plaits::Voice)

```
Engine::Render(float)
  → LPG vactrol VCA+VCF (ChannelPostProcessor)
  → AGC Limiter (negative-gain engines)
  → int16 conversion (* -32767)
  → [our code] int16 → float (/ 32768)
  → gain correction (kotlinGain / cppGain)
  → softLimit()
```

### Target Signal Path (matching Kotlin)

```
Engine::Render(float)
  → [our code] * outGain
  → softLimit()
```

### Key API — `plaits::Engine::Render()`

```cpp
// In plaits/dsp/engine/engine.h (UNMODIFIED)
struct EngineParameters {
    int trigger;     // TriggerState enum
    float note;      // MIDI note
    float timbre;    // 0..1
    float morph;     // 0..1
    float harmonics; // 0..1
    float accent;    // 0..1
};

enum TriggerState {
    TRIGGER_LOW = 0,
    TRIGGER_RISING_EDGE = 1,
    TRIGGER_UNPATCHED = 2,
    TRIGGER_HIGH = 4,
};

class Engine {
    virtual void Render(const EngineParameters& params,
                       float* out, float* aux, size_t size,
                       bool* already_enveloped) = 0;
};
```

### Kotlin outGain values (from PlaitsEngine subclasses)

| Index | Engine | outGain |
|-------|--------|---------|
| 8 | VirtualAnalog | 0.30 |
| 9 | Waveshaping | 0.25 |
| 10 | FM | 0.30 |
| 11 | Grain | 0.30 |
| 12 | Additive | 0.30 |
| 13 | Wavetable | 0.50 |
| 14 | Chord | 0.30 |
| 15 | Speech | 0.50 |
| 16 | Swarm | 0.30 |
| 17 | Noise | 0.30 |
| 18 | Particle | 0.30 |
| 19 | String | 0.30 |
| 20 | Modal | 0.30 |
| 21-23 | Drums | 0.30 (default) |

### Engine Init Pattern (from plaits Voice::Init)

All 24 engines are registered via `EngineRegistry`. Each engine gets `Init(allocator)` called with a shared `BufferAllocator`. The allocator is freed between engines so they share the same RAM (only one engine is active at a time). This means 32KB is enough for all engines (largest engine allocation fits within `kVoiceAllocBytes`).

---

## Task 1: Create OrpheusVoice struct

**Files:**
- Create: `liborpheus_dsp/src/orpheus_voice.h`

This is the replacement for `plaits::Voice` — a thin wrapper that holds the engine registry, does trigger edge detection, and calls `Engine::Render()` directly.

**Step 1: Write `orpheus_voice.h`**

```cpp
#pragma once

#include "stmlib/utils/buffer_allocator.h"
#include "plaits/dsp/engine/engine.h"

// Engine includes (same set as plaits/dsp/voice.h)
#include "plaits/dsp/engine/additive_engine.h"
#include "plaits/dsp/engine/bass_drum_engine.h"
#include "plaits/dsp/engine/chord_engine.h"
#include "plaits/dsp/engine/fm_engine.h"
#include "plaits/dsp/engine/grain_engine.h"
#include "plaits/dsp/engine/hi_hat_engine.h"
#include "plaits/dsp/engine/modal_engine.h"
#include "plaits/dsp/engine/noise_engine.h"
#include "plaits/dsp/engine/particle_engine.h"
#include "plaits/dsp/engine/snare_drum_engine.h"
#include "plaits/dsp/engine/speech_engine.h"
#include "plaits/dsp/engine/string_engine.h"
#include "plaits/dsp/engine/swarm_engine.h"
#include "plaits/dsp/engine/virtual_analog_engine.h"
#include "plaits/dsp/engine/waveshaping_engine.h"
#include "plaits/dsp/engine/wavetable_engine.h"
#include "plaits/dsp/engine2/chiptune_engine.h"
#include "plaits/dsp/engine2/phase_distortion_engine.h"
#include "plaits/dsp/engine2/six_op_engine.h"
#include "plaits/dsp/engine2/string_machine_engine.h"
#include "plaits/dsp/engine2/virtual_analog_vcf_engine.h"
#include "plaits/dsp/engine2/wave_terrain_engine.h"

#include <cmath>
#include <cstring>

static constexpr int kOrpheusMaxEngines = 24;
static constexpr int kOrpheusBlockSize = 24;  // matches Kotlin PLAITS_BLOCK_SIZE

// Per-engine Kotlin outGain values, indexed by engine index 0-23
static const float kOutGain[kOrpheusMaxEngines] = {
    0.3f,  // 0: VirtualAnalogVCF
    0.3f,  // 1: PhaseDistortion
    0.3f,  // 2: SixOp FM1
    0.3f,  // 3: SixOp FM2
    0.3f,  // 4: SixOp FM3
    0.3f,  // 5: WaveTerrain
    0.3f,  // 6: StringMachine
    0.3f,  // 7: Chiptune
    0.3f,  // 8: VirtualAnalog
    0.25f, // 9: Waveshaping
    0.3f,  // 10: FM
    0.3f,  // 11: Grain
    0.3f,  // 12: Additive
    0.5f,  // 13: Wavetable
    0.3f,  // 14: Chord
    0.5f,  // 15: Speech
    0.3f,  // 16: Swarm
    0.3f,  // 17: Noise
    0.3f,  // 18: Particle
    0.3f,  // 19: String
    0.3f,  // 20: Modal
    0.3f,  // 21: BassDrum
    0.3f,  // 22: SnareDrum
    0.3f,  // 23: HiHat
};

// Soft saturation matching Kotlin DspPlaitsUnit.softLimit():
// Linear below 0.5, tanh saturation above.
static inline float soft_limit(float x) {
    float ax = std::fabs(x);
    if (ax < 0.5f) return x;
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    return sign * (0.5f + 0.5f * std::tanh((ax - 0.5f) * 2.0f));
}

// Lightweight voice wrapper that calls Engine::Render() directly.
// No LPG. No limiter. No int16. Clean float output like Kotlin.
struct OrpheusVoice {
    plaits::EngineRegistry<kOrpheusMaxEngines> engines;
    int previous_engine_index = -1;
    bool trigger_state = false;

    // Individual engine instances (same as plaits::Voice members)
    plaits::VirtualAnalogVCFEngine virtual_analog_vcf_engine;
    plaits::PhaseDistortionEngine phase_distortion_engine;
    plaits::SixOpEngine six_op_engine;
    plaits::WaveTerrainEngine wave_terrain_engine;
    plaits::StringMachineEngine string_machine_engine;
    plaits::ChiptuneEngine chiptune_engine;
    plaits::VirtualAnalogEngine virtual_analog_engine;
    plaits::WaveshapingEngine waveshaping_engine;
    plaits::FMEngine fm_engine;
    plaits::GrainEngine grain_engine;
    plaits::AdditiveEngine additive_engine;
    plaits::WavetableEngine wavetable_engine;
    plaits::ChordEngine chord_engine;
    plaits::SpeechEngine speech_engine;
    plaits::SwarmEngine swarm_engine;
    plaits::NoiseEngine noise_engine;
    plaits::ParticleEngine particle_engine;
    plaits::StringEngine string_engine;
    plaits::ModalEngine modal_engine;
    plaits::BassDrumEngine bass_drum_engine;
    plaits::SnareDrumEngine snare_drum_engine;
    plaits::HiHatEngine hi_hat_engine;

    // Working buffers for Engine::Render output
    float out_buffer[kOrpheusBlockSize];
    float aux_buffer[kOrpheusBlockSize];

    void Init(stmlib::BufferAllocator* allocator) {
        engines.Init();

        // Register in same order as plaits::Voice (indices 0-23)
        // Bank 2 engines (indices 0-7)
        engines.RegisterInstance(&virtual_analog_vcf_engine, false, 1.0f, 1.0f);
        engines.RegisterInstance(&phase_distortion_engine, false, 0.7f, 0.7f);
        engines.RegisterInstance(&six_op_engine, true, 1.0f, 1.0f);
        engines.RegisterInstance(&six_op_engine, true, 1.0f, 1.0f);
        engines.RegisterInstance(&six_op_engine, true, 1.0f, 1.0f);
        engines.RegisterInstance(&wave_terrain_engine, false, 0.7f, 0.7f);
        engines.RegisterInstance(&string_machine_engine, false, 0.8f, 0.8f);
        engines.RegisterInstance(&chiptune_engine, false, 0.5f, 0.5f);

        // Bank 1 engines (indices 8-23) — these match Kotlin
        engines.RegisterInstance(&virtual_analog_engine, false, 0.8f, 0.8f);
        engines.RegisterInstance(&waveshaping_engine, false, 0.7f, 0.6f);
        engines.RegisterInstance(&fm_engine, false, 0.6f, 0.6f);
        engines.RegisterInstance(&grain_engine, false, 0.7f, 0.6f);
        engines.RegisterInstance(&additive_engine, false, 0.8f, 0.8f);
        engines.RegisterInstance(&wavetable_engine, false, 0.6f, 0.6f);
        engines.RegisterInstance(&chord_engine, false, 0.8f, 0.8f);
        engines.RegisterInstance(&speech_engine, false, -0.7f, 0.8f);
        engines.RegisterInstance(&swarm_engine, false, -3.0f, 1.0f);
        engines.RegisterInstance(&noise_engine, false, -1.0f, -1.0f);
        engines.RegisterInstance(&particle_engine, false, -2.0f, 1.0f);
        engines.RegisterInstance(&string_engine, true, -1.0f, 0.8f);
        engines.RegisterInstance(&modal_engine, true, -1.0f, 0.8f);
        engines.RegisterInstance(&bass_drum_engine, true, 0.8f, 0.8f);
        engines.RegisterInstance(&snare_drum_engine, true, 0.8f, 0.8f);
        engines.RegisterInstance(&hi_hat_engine, true, 0.8f, 0.8f);

        // Initialize all engines sharing the same allocator memory
        for (int i = 0; i < engines.size(); ++i) {
            allocator->Free();
            engines.get(i)->Init(allocator);
        }

        previous_engine_index = -1;
        trigger_state = false;
    }

    // Render a block of audio using direct Engine::Render().
    // Output: mono float buffer with Kotlin-matched gain + soft limiting.
    //
    // engine_index: 0-23 (Plaits engine index)
    // gate: true if gate is on
    // note: MIDI note (float)
    // harmonics/timbre/morph: 0..1
    // out: output buffer (mono float, num_frames samples)
    // num_frames: number of samples to render
    void Render(int engine_index, bool gate,
                float note, float harmonics, float timbre, float morph,
                float accent,
                float* out, int num_frames) {
        if (engine_index < 0 || engine_index >= engines.size()) {
            std::memset(out, 0, num_frames * sizeof(float));
            return;
        }

        plaits::Engine* e = engines.get(engine_index);

        // Handle engine switch: reset the new engine
        if (engine_index != previous_engine_index) {
            e->Reset();
            previous_engine_index = engine_index;
            // Force trigger state low so next gate-on sees a rising edge
            trigger_state = false;
        }

        float out_gain = kOutGain[engine_index];

        int frames_done = 0;
        while (frames_done < num_frames) {
            int block = std::min(kOrpheusBlockSize, num_frames - frames_done);

            // Trigger edge detection (Schmitt trigger matching Kotlin)
            bool rising_edge = gate && !trigger_state;
            if (gate && !trigger_state) trigger_state = true;
            if (!gate && trigger_state) trigger_state = false;

            plaits::EngineParameters params;
            params.trigger = (rising_edge ? plaits::TRIGGER_RISING_EDGE : plaits::TRIGGER_LOW)
                           | (gate ? plaits::TRIGGER_HIGH : plaits::TRIGGER_LOW);
            params.note = note;
            params.harmonics = harmonics;
            params.timbre = timbre;
            params.morph = morph;
            params.accent = accent;

            bool already_enveloped = e->post_processing_settings.already_enveloped;
            e->Render(params, out_buffer, aux_buffer, block, &already_enveloped);

            // Apply Kotlin-matched gain + soft limiting
            for (int i = 0; i < block; i++) {
                out[frames_done + i] = soft_limit(out_buffer[i] * out_gain);
            }

            frames_done += block;
            // After first block, no more rising edges until gate cycles
        }
    }
};
```

**Step 2: Verify it compiles**

Add `orpheus_voice.h` to the build and test:

```bash
cd liborpheus_dsp && cmake --build build 2>&1 | tail -5
```

Expected: Build success (header-only, no new .cpp file needed).

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_voice.h
git commit -m "feat(dsp): Add OrpheusVoice wrapper for direct Engine::Render() calls"
```

---

## Task 2: Replace plaits::Voice with OrpheusVoice in OrpheusEngine

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Step 1: Update `orpheus_engine.h`**

Replace:
```cpp
#include "plaits/dsp/voice.h"
```
With:
```cpp
#include "orpheus_voice.h"
```

Replace:
```cpp
plaits::Voice voices_dsp[kNumVoices];
```
With:
```cpp
OrpheusVoice voices_dsp[kNumVoices];
```

Remove `#include "plaits/dsp/voice.h"` — `orpheus_voice.h` includes everything needed.

**Step 2: Update `orpheus_engine_create()` in `orpheus_engine.cpp`**

Replace the voice initialization block:
```cpp
for (int i = 0; i < kNumVoices; i++) {
    stmlib::BufferAllocator allocator(
        engine->voice_alloc_buffers[i], kVoiceAllocBytes);
    engine->voices_dsp[i].Init(&allocator);
    engine->voices_dsp[i].set_force_lpg_bypass(true);
}
```
With:
```cpp
for (int i = 0; i < kNumVoices; i++) {
    stmlib::BufferAllocator allocator(
        engine->voice_alloc_buffers[i], kVoiceAllocBytes);
    engine->voices_dsp[i].Init(&allocator);
}
```

**Step 3: Update fallback rendering in `orpheus_engine_process()`**

Replace the Plaits rendering block in the fallback path (the `for (int v = 0; ...)` loop) that currently builds `plaits::Patch` + `plaits::Modulations` and calls `voice.Render(patch, mod, frames, block)`. Replace it to call `OrpheusVoice::Render()` instead:

The entire voice rendering loop in the fallback path should change from building `plaits::Patch`/`plaits::Modulations` and calling `Voice::Render(patch, mod, frames, block)` then doing `frames[i].out * inv_32768 * gain_corr` to:

```cpp
// Build parameters from atomics
float note = vp.tune.load(std::memory_order_relaxed);
float harmonics = vp.harmonics.load(std::memory_order_relaxed);
float timbre = vp.timbre.load(std::memory_order_relaxed);
float morph = vp.morph.load(std::memory_order_relaxed);
int engine_index = vp.engine_index.load(std::memory_order_relaxed);
bool gate = vp.gate.load(std::memory_order_relaxed) != 0;

float mono_buf[kMaxFrames];
voice.Render(engine_index, gate, note, harmonics, timbre, morph, 0.8f,
             mono_buf, num_frames);

// Mix into stereo output with pan and volume
for (int i = 0; i < num_frames; i++) {
    float mono = mono_buf[i] * volume;
    output_buffer[i * 2]     += mono * pan_l;
    output_buffer[i * 2 + 1] += mono * pan_r;
}
```

Remove `kotlin_gain_correction()` function and `soft_limit()` duplicate from `orpheus_engine.cpp` — both are now in `orpheus_voice.h`.

**Step 4: Build and run tests**

```bash
cd liborpheus_dsp && cmake --build build && build/orpheus_dsp_test
```

Expected: All tests pass. WAV snapshot values will change (this is expected — we're changing the signal path).

**Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "refactor(dsp): Switch fallback renderer to direct Engine::Render via OrpheusVoice"
```

---

## Task 3: Update unit_process_plaits (graph path)

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`

The graph-based Plaits rendering in `unit_process_plaits()` also needs to use `OrpheusVoice::Render()`.

**Step 1: Replace Voice::Render call in `unit_process_plaits()`**

In the Plaits engine branch (`else` block starting at ~line 589), replace the code that builds `plaits::Patch` + `plaits::Modulations`, calls `voice.Render(patch, mod, frames, block)`, and does `frames[i].out * inv_32768 * gain_correction`:

```cpp
auto& voice = engine->voices_dsp[idx];

// Compute final note with all modulations
float note = vp.tune.load(std::memory_order_relaxed)
    + vibrato_semitones + coupling_offset + fm_mod_semitones + bend_offset;
float harmonics = vp.harmonics.load(std::memory_order_relaxed);
float timbre = std::max(0.0f, std::min(1.0f,
    vp.timbre.load(std::memory_order_relaxed) + timbre_mod_offset));
float morph = vp.morph.load(std::memory_order_relaxed);
bool gate = (plaits_gate != 0);

// Render via direct Engine::Render (no LPG, no limiter, no int16)
float render_buf[kMaxFrames];
voice.Render(engine_index, gate, note, harmonics, timbre, morph, 0.8f,
             render_buf, num_frames);

// Apply hold VCA and collect peak
float voice_peak = 0.0f;
for (int i = 0; i < num_frames; i++) {
    float sample = render_buf[i];

    // Hold ramp
    osc.hold_smoothed += hold_coeff * (scaled_hold - osc.hold_smoothed);
    if (raw_hold > 0.001f && actual_gate == 0) {
        sample *= osc.hold_smoothed;
    }

    out[i] = sample;
    float abs_s = std::fabs(sample);
    if (abs_s > voice_peak) voice_peak = abs_s;
}
```

Remove `soft_limit()` and the `kKotlinOutGain`/`kCppOutGain` tables from `orpheus_units.cpp` — they're now in `orpheus_voice.h`.

**Step 2: Add `#include "orpheus_voice.h"` to `orpheus_units.cpp`**

This may already be included transitively via `orpheus_engine.h`. Verify.

**Step 3: Build and run tests**

```bash
cd liborpheus_dsp && cmake --build build && build/orpheus_dsp_test
```

Expected: All tests pass.

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.cpp
git commit -m "refactor(dsp): Switch graph Plaits renderer to direct Engine::Render"
```

---

## Task 4: Update raw engine snapshots in test_snapshots.cpp

**Files:**
- Modify: `liborpheus_dsp/test/test_snapshots.cpp`

The raw engine snapshot tests currently access `engine->voices_dsp[0]` as a `plaits::Voice` and call `Voice::Render()`. These need to use `OrpheusVoice::Render()` or call `Engine::Render()` directly.

**Step 1: Update raw engine render in test_snapshots.cpp**

Replace the raw engine render block that builds `plaits::Patch`, calls `eng->voices_dsp[0].Render(patch, mod, frames, block)`, and does `(frames[i].out + frames[i].aux) * 0.5f * inv_32768`. Instead:

```cpp
// Raw Plaits output via direct Engine::Render
{
    for (auto& e : engines) {
        char label[64];
        snprintf(label, sizeof(label), "cpp_raw_%s", e.name);

        OrpheusEngine* eng = orpheus_engine_create(sr);
        auto& voice = eng->voices_dsp[0];

        int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        float mono[kOrpheusBlockSize];

        for (int off = 0; off < total; off += kOrpheusBlockSize) {
            int block = std::min(kOrpheusBlockSize, total - off);
            bool gate = true;  // gate on for full duration

            voice.Render(e.cpp_index, gate, 60.0f, 0.5f, 0.5f, 0.5f, 0.8f,
                        mono, block);

            for (int i = 0; i < block; i++) {
                buf[(off + i) * 2]     = mono[i];
                buf[(off + i) * 2 + 1] = mono[i];
            }
        }

        printf("  Raw %s: RMS=%.4f Peak=%.4f\n", e.name,
               compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check(label, buf.data(), total, sr, dir);
        orpheus_engine_destroy(eng);
    }
}
```

**Step 2: Delete `.ref.wav` baselines**

The signal path has changed, so old baselines are invalid. Delete them so snapshot_check creates new ones:

```bash
rm -f liborpheus_dsp/test/output/*.ref.wav
```

**Step 3: Build and run**

```bash
cd liborpheus_dsp && cmake --build build && build/orpheus_dsp_test
```

Expected: All tests pass. New `.ref.wav` baselines created.

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/test_snapshots.cpp
git commit -m "refactor(dsp): Update raw snapshot tests to use OrpheusVoice direct rendering"
```

---

## Task 5: Revert eurorack modifications

**Files:**
- Revert: `/Users/balch/Source/eurorack/plaits/dsp/voice.h`
- Revert: `/Users/balch/Source/eurorack/plaits/dsp/voice.cc`

**Step 1: Revert voice.h changes**

Remove these additions:
- Constructor initialization: `Voice() : force_lpg_bypass_(false) { }` → `Voice() { }`
- The `set_force_lpg_bypass()` method
- The comment block above it
- The `bool force_lpg_bypass_;` member

**Step 2: Revert voice.cc changes**

Change line 230 from:
```cpp
bool lpg_bypass = force_lpg_bypass_ || already_enveloped || \
```
Back to:
```cpp
bool lpg_bypass = already_enveloped || \
```

**Step 3: Build to verify nothing depends on the reverted code**

```bash
cd liborpheus_dsp && cmake --build build 2>&1 | tail -5
```

Expected: Clean build. No references to `set_force_lpg_bypass` or `force_lpg_bypass_` remain.

**Step 4: Run tests**

```bash
build/orpheus_dsp_test
```

Expected: All tests pass (OrpheusVoice doesn't use plaits::Voice at all).

**Step 5: Commit (in eurorack repo)**

```bash
cd /Users/balch/Source/eurorack
git checkout -- plaits/dsp/voice.h plaits/dsp/voice.cc
```

---

## Task 6: Run cross-engine comparison and validate

**Step 1: Generate fresh C++ snapshots**

```bash
cd liborpheus_dsp && cmake --build build && build/orpheus_dsp_test
```

**Step 2: Generate JSyn snapshots and comparison**

```bash
cd /path/to/orphic-fm-app/.worktrees/cpp-dsp
./gradlew :core:dsp-engine:cleanJvmTest
./gradlew :core:dsp-engine:jvmTest --tests "org.balch.orpheus.core.audio.dsp.JsynSnapshotTest" --no-build-cache --rerun
```

**Step 3: Check comparison results**

Look at the test report HTML for `crossEngineComparison` and `normalizedComparison`. Key metrics to verify:

- **Ratio** (JSyn RMS / C++ RMS): Should be closer to 1.0 than before
- **Crest factor**: JSyn and C++ should be within ~1-2 dB for most engines
- **ZCR**: Should be similar for same-frequency engines

**Step 4: Commit**

```bash
git add -A
git commit -m "test(dsp): Validate direct engine rendering matches Kotlin output"
```

---

## Summary of Changes

| File | Action | Description |
|------|--------|-------------|
| `orpheus_voice.h` | Create | OrpheusVoice struct with EngineRegistry + direct Render |
| `orpheus_engine.h` | Modify | Replace `plaits::Voice` with `OrpheusVoice` |
| `orpheus_engine.cpp` | Modify | Update create() and fallback render path |
| `orpheus_units.cpp` | Modify | Update unit_process_plaits graph render path |
| `test_snapshots.cpp` | Modify | Update raw engine snapshot tests |
| `eurorack/voice.h` | Revert | Remove force_lpg_bypass_ (git checkout) |
| `eurorack/voice.cc` | Revert | Remove force_lpg_bypass_ (git checkout) |

**What's removed:** `kotlin_gain_correction()`, duplicate `soft_limit()`, `kKotlinOutGain`/`kCppOutGain` tables from `orpheus_engine.cpp` and `orpheus_units.cpp`. All replaced by the single `kOutGain[]` table and `soft_limit()` in `orpheus_voice.h`.

**What's preserved:** All modulation routing (vibrato, coupling, FM, bender, hold VCA), graph-based rendering, fallback rendering, warps source buffer population, voice level tracking, engine change retrigger logic.
