# C++ DSP Engine Design

**Date:** 2026-03-07
**Branch:** `cpp-dsp` (worktree: `.worktrees/cpp-dsp`)
**Status:** Design approved, pending implementation plan

## Problem

The Kotlin DSP engine running on Android's Oboe audio thread causes audio clicks. Root cause: Samsung S24 Ultra's 48-frame burst (1ms) leaves only 0.37ms headroom after the JNI round-trip + Kotlin DSP graph. GC pauses, thermal throttling, and cache pollution from the visualizer's 100% RenderThread regularly exceed that budget.

More broadly, the project maintains three parallel DSP implementations (Oboe/Kotlin, JSyn/Kotlin, WASM/Kotlin) — all ports of original Mutable Instruments C++ code that already exists in `/Users/balch/Source/eurorack/`.

## Solution

Replace all Kotlin DSP with a single C++ library (`liborpheus_dsp`) built from the original MI source, compiled per-platform:

| Platform | Binding | Audio Backend |
|----------|---------|---------------|
| Android | NDK/JNI | Oboe (existing) |
| JVM Desktop | JNI or Panama FFI | PortAudio or JACK |
| WASM | Emscripten | AudioWorklet |
| iOS (future) | Direct C++ | CoreAudio |

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Kotlin (UI / Control Layer)                    │
│  ┌──────────┐ ┌──────────┐ ┌────────────────┐  │
│  │ Compose  │ │ViewModel │ │ SynthController│  │
│  │ Panels   │→│ MVI      │→│ controlFlow()  │  │
│  └──────────┘ └──────────┘ └───────┬────────┘  │
│                                     │           │
│  ┌──────────────────────────────────▼────────┐  │
│  │ OrpheusDspBridge (Kotlin)                 │  │
│  │ - Patch DSL → binary topology descriptor  │  │
│  │ - setPort(uri, symbol, value) → JNI/FFI   │  │
│  │ - pollMonitor() → viz data at 60fps       │  │
│  └──────────────────────────────────┬────────┘  │
├─────────────────────────────────────┼───────────┤
│  C API boundary (extern "C")        │           │
├─────────────────────────────────────┼───────────┤
│  liborpheus_dsp (C++)               ▼           │
│  ┌──────────────────────────────────────────┐   │
│  │ OrpheusEngine                            │   │
│  │ ┌────────────┐ ┌───────────────────────┐ │   │
│  │ │ GraphBuilder│ │ VoiceManager (8+4)   │ │   │
│  │ │ (from DSL) │ │ gate/tune/hold/wobble│ │   │
│  │ └────────────┘ └───────────────────────┘ │   │
│  │ ┌──────────────────────────────────────┐ │   │
│  │ │ GraphScheduler (Tarjan SCC topo-sort)│ │   │
│  │ └──────────────────────────────────────┘ │   │
│  │ ┌──────────────────────────────────────┐ │   │
│  │ │ Plugin Units                         │ │   │
│  │ │ ┌────────┐┌───────┐┌──────┐┌──────┐ │ │   │
│  │ │ │ Plaits ││Clouds ││Rings ││Warps │ │ │   │
│  │ │ │(13 eng)││(gran) ││(reson)││(meta)│ │ │   │
│  │ │ └────────┘└───────┘└──────┘└──────┘ │ │   │
│  │ │ ┌────────┐┌───────┐┌──────┐┌──────┐ │ │   │
│  │ │ │Marbles ││Drums  ││Reverb││Delay │ │ │   │
│  │ │ │(flux)  ││(4 eng)││(datt)││      │ │ │   │
│  │ │ └────────┘└───────┘└──────┘└──────┘ │ │   │
│  │ │ + Distortion, Stereo, LFO, Looper,  │ │   │
│  │ │   Vibrato, Bender, Beats, TTS        │ │   │
│  │ └──────────────────────────────────────┘ │   │
│  │ ┌──────────────────────────────────────┐ │   │
│  │ │ MonitorBuffer (lock-free ring)       │ │   │
│  │ │ peak, CPU load, voice levels, LFO   │ │   │
│  │ └──────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────┘   │
│                                                 │
│  eurorack/ (unmodified MI source)               │
│  ├── stmlib/dsp/  (primitives)                  │
│  ├── plaits/dsp/  (13 voice engines)            │
│  ├── clouds/dsp/  (granular processor)          │
│  ├── rings/dsp/   (modal resonator)             │
│  ├── warps/dsp/   (modulator)                   │
│  ├── marbles/dsp/ (random/flux)                 │
│  └── grids/       (drum patterns)               │
│                                                 │
│  orpheus_compat.h (STM32 → standard C++ shim)   │
└─────────────────────────────────────────────────┘
```

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| C API granularity | Opaque engine handle | Matches existing DspWorkerProxy pattern. Kotlin never touches DSP topology directly. |
| Graph topology | Kotlin DSL → binary descriptor | Sent once at patch load (zero audio-thread overhead). Enables flexible routing without hardcoding in C++. |
| Monitoring data | Polled JNI/FFI at 60fps | Simple, portable, sufficient for visualization. |
| MI source handling | Thin compat shim, source unmodified | Can diff against upstream eurorack repo. Standard practice (Surge, VCV Rack). |

## C API Surface

```c
// ── Lifecycle ────────────────────────────────────
typedef struct OrpheusEngine OrpheusEngine;

OrpheusEngine* orpheus_engine_create(float sample_rate);
void           orpheus_engine_destroy(OrpheusEngine* engine);

// ── Topology (called once per preset load) ───────
// Accepts a binary descriptor built by the Kotlin DSL.
// Format: flat buffer of unit IDs, connection pairs, parameter defaults.
int  orpheus_engine_load_patch(OrpheusEngine* engine,
                               const uint8_t* descriptor, size_t length);

// ── Audio render (called from audio thread) ──────
// Writes interleaved stereo into `output_buffer`.
void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames);

// ── Parameter control (called from UI thread) ────
void orpheus_engine_set_port(OrpheusEngine* engine,
                             const char* plugin_uri,
                             const char* symbol,
                             float value);

float orpheus_engine_get_port(OrpheusEngine* engine,
                              const char* plugin_uri,
                              const char* symbol);

// ── Voice control ────────────────────────────────
void orpheus_engine_set_voice_gate(OrpheusEngine* engine,
                                   int index, int active);
void orpheus_engine_set_voice_tune(OrpheusEngine* engine,
                                   int index, float tune);
void orpheus_engine_trigger_drum(OrpheusEngine* engine,
                                 int drum_index, float accent);

// ── Global controls ──────────────────────────────
void orpheus_engine_set_master_volume(OrpheusEngine* engine, float v);
void orpheus_engine_set_drive(OrpheusEngine* engine, float v);
void orpheus_engine_set_delay_mix(OrpheusEngine* engine, float v);
void orpheus_engine_set_vibrato(OrpheusEngine* engine, float v);
void orpheus_engine_set_bend(OrpheusEngine* engine, float v);

// ── Monitoring (polled from UI thread at ~60fps) ─
typedef struct {
    float peak_left, peak_right;
    float cpu_load;
    float voice_levels[12];     // 8 main + 4 REPL
    float lfo_output;
    float master_level;
    float bend_position;
} OrpheusMonitorData;

void orpheus_engine_get_monitor(OrpheusEngine* engine,
                                OrpheusMonitorData* out);

// Optional: waveform snapshot for visualizer
void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames);
```

## Kotlin Patch DSL

```kotlin
// Called at preset load — compiles to binary descriptor
val patch = orpheusPatch {
    val voice0 = plaitsVoice("voice0") {
        engine = PlaitsEngine.FM
        harmonics = 0.5f
        timbre = 0.3f
        morph = 0.7f
    }
    val voice1 = plaitsVoice("voice1") {
        engine = PlaitsEngine.MODAL
    }
    // ... voices 2-7

    val grains = grainsEffect("grains")
    val warps = warpsEffect("warps")
    val resonator = resonatorEffect("resonator")
    val reverb = reverbEffect("reverb")
    val delay = delayEffect("delay")
    val distortion = distortionEffect("distortion")
    val stereo = stereoEffect("stereo")
    val lfo = hyperLfo("lfo")
    val flux = fluxGenerator("flux")
    val drums = drumKit("drums")

    // Signal routing
    routing {
        voices(0..7).sumTo(grains.input)
        grains.output connectTo resonator.input
        resonator.output connectTo warps.inputLeft
        warps.output connectTo distortion.input
        distortion.output connectTo reverb.input
        reverb.output connectTo delay.input
        delay.output connectTo stereo.input
        stereo.output connectTo masterOut

        // Parallel drum path
        drums.output connectTo resonator.input  // parallel feed
        drums.output connectTo masterOut        // direct out

        // Feedback: peak → LFO
        stereo.peak connectTo lfo.feedbackInput

        // Flux clock from tempo
        tempo connectTo flux.clockInput
    }
}

// Serialize and send to C++ engine
bridge.loadPatch(patch.toDescriptor())
```

The DSL builds an in-memory graph description that serializes to a compact binary format (unit type enum + connection pairs + initial parameter values). Sent once via JNI — not on the audio thread.

## Compatibility Shim (`orpheus_compat.h`)

```cpp
#pragma once

// Replace STM32 macros with standard C++
#include <algorithm>
#include <cmath>
#include <cstring>

#define CONSTRAIN(x, lo, hi)  std::clamp((x), (lo), (hi))
#define DISALLOW_COPY_AND_ASSIGN(TypeName) \
    TypeName(const TypeName&) = delete;    \
    TypeName& operator=(const TypeName&) = delete;

// stmlib buffer allocator — replaced with simple heap allocation
// (only called during Init, never on audio thread)
namespace stmlib {
class BufferAllocator {
public:
    BufferAllocator(uint8_t* buffer, size_t size)
        : buffer_(buffer), size_(size), offset_(0) {}

    template<typename T>
    T* Allocate(size_t count) {
        size_t bytes = count * sizeof(T);
        // Align to 4 bytes
        offset_ = (offset_ + 3) & ~3;
        T* ptr = reinterpret_cast<T*>(buffer_ + offset_);
        offset_ += bytes;
        return ptr;
    }

private:
    uint8_t* buffer_;
    size_t size_;
    size_t offset_;
};
}  // namespace stmlib

// Sample rate: configurable instead of hardcoded 48000
// Each engine receives sample_rate in its Init or as a member.
```

## Platform Build Strategy

```
liborpheus_dsp/
├── CMakeLists.txt              ← top-level: defines library target
├── include/
│   └── orpheus_dsp.h           ← C API header (extern "C")
├── src/
│   ├── orpheus_engine.cpp      ← Engine impl, graph builder, voice manager
│   ├── orpheus_graph.cpp       ← Tarjan SCC scheduler (port of DspGraphScheduler)
│   ├── orpheus_monitor.cpp     ← Lock-free monitor ring buffer
│   ├── orpheus_compat.h        ← STM32 shim
│   ├── orpheus_units.cpp       ← Basic DSP units (osc, math, dynamics, utility)
│   └── orpheus_patch.cpp       ← Binary patch descriptor parser
├── eurorack/                   ← git submodule or symlink
│   ├── stmlib/
│   ├── plaits/
│   ├── clouds/
│   ├── rings/
│   ├── warps/
│   ├── marbles/
│   └── grids/
└── platform/
    ├── android/
    │   ├── CMakeLists.txt      ← Android .so, links Oboe + liborpheus_dsp
    │   ├── oboe_backend.cpp    ← Oboe stream → orpheus_engine_process()
    │   └── jni_bridge.cpp      ← JNI for setPort/loadPatch/getMonitor
    ├── jvm/
    │   ├── CMakeLists.txt      ← Desktop .dylib/.so
    │   ├── portaudio_backend.cpp
    │   └── jni_bridge.cpp
    └── wasm/
        ├── CMakeLists.txt      ← Emscripten .wasm
        ├── wasm_exports.cpp    ← Exported C functions for JS
        └── worklet_glue.js     ← AudioWorklet → wasm process()
```

### Android

The existing `OboeEngine.cpp` simplifies dramatically — the `onAudioReady` callback calls `orpheus_engine_process()` directly. No JNI, no Kotlin, no GC.

```cpp
DataCallbackResult onAudioReady(AudioStream*, void* data, int32_t frames) {
    orpheus_engine_process(engine_, (float*)data, frames);
    return DataCallbackResult::Continue;
}
```

### WASM (Emscripten)

The C++ compiles to a `.wasm` module that runs inside the existing AudioWorklet. Replaces the entire `dspWorker` Kotlin module.

```js
// AudioWorklet processor
class OrpheusProcessor extends AudioWorkletProcessor {
    process(inputs, outputs, parameters) {
        Module._orpheus_engine_process(enginePtr, outputPtr, 128);
        // copy outputPtr → outputs[0]
        return true;
    }
}
```

### JVM Desktop

JNI bridge identical to Android (same C API). Replaces JSyn entirely. Audio backend via PortAudio or JACK.

## What Gets Deleted from Kotlin

Once the C++ engine is working, these Kotlin DSP files become deletable:

- `core/audio/src/commonMain/kotlin/**/Dsp*.kt` — all common DSP units (~15 files)
- `core/audio/src/androidMain/kotlin/**/Oboe*.kt` — Oboe wrappers (~17 files)
- `core/audio/src/jvmMain/kotlin/**/Jsyn*.kt` — JSyn wrappers (~13 files)
- `core/dsp-engine/src/commonMain/` — DspSynthEngine, DspVoice, DspVoiceManager, DspWiringGraph (~9 files)
- `core/plugins/*/src/commonMain/**/Dsp*Unit.kt` — plugin DSP units (~8 files)
- `core/plugins/*/src/commonMain/**/engine/` — ported MI engines (~64 files, ~50K lines)
- `apps/dspWorker/` — entire WASM worker module
- JSyn dependency from `libs.versions.toml`

**What stays in Kotlin:** UI panels, ViewModels (MVI pattern), SynthController, preset repository, DI wiring, MediaPipe, AI agents — everything above the C API boundary.

## Migration Path

Incremental, platform-by-platform:

1. **Build `liborpheus_dsp` with one engine (Plaits)** — verify it compiles and renders audio
2. **Android integration** — replace JNI callback with direct C++ process. Verify clicks are gone.
3. **Add remaining MI engines** — Clouds, Rings, Warps, Marbles, Drums, Reverb
4. **Add non-MI units** — LFO, delay, distortion, stereo, looper (new C++ or port from Kotlin)
5. **Graph builder + patch DSL** — dynamic topology from Kotlin descriptor
6. **WASM via Emscripten** — compile same C++ to AudioWorklet
7. **JVM Desktop** — JNI bridge, PortAudio backend
8. **Delete Kotlin DSP** — remove ~120 files, JSyn dependency

## Performance Expectations

| Metric | Current (Kotlin) | Expected (C++) |
|--------|-----------------|----------------|
| Audio thread CPU (Android) | 63% @ 48-frame burst | 15-25% |
| GC impact on audio | HeapTaskDaemon 15% | 0% (no JVM heap) |
| Audio headroom (1ms burst) | 0.37ms | 0.75-0.85ms |
| JNI calls per audio frame | 2 (CallVoidMethod + GetFloatArrayRegion) | 0 |
| WASM DSP overhead | Kotlin→WASM + JS interop | Near-native Emscripten |
| Codebase DSP lines | ~70K Kotlin across 3 platforms | ~5K new C++ + original MI source |
