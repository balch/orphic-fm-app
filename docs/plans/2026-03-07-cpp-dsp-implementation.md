# C++ DSP Engine Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace Kotlin DSP with a shared C++ library (`liborpheus_dsp`) using original Mutable Instruments source, eliminating audio clicks on Android and unifying DSP across all platforms.

**Architecture:** A C library with an opaque `OrpheusEngine` handle wraps the original MI C++ engines (Plaits, Clouds, Rings, Warps, Marbles). A Kotlin DSL builds patch topology descriptors sent once at preset load. Platform backends (Oboe, Emscripten, PortAudio) call `orpheus_engine_process()` directly from their audio callbacks — no JNI in the audio path.

**Tech Stack:** C++17, CMake 3.22+, Oboe 1.9 (Android), Emscripten 3.x (WASM), original eurorack source at `/Users/balch/Source/eurorack/`

**Design doc:** `docs/plans/2026-03-07-cpp-dsp-engine-design.md`

**Working branch:** `cpp-dsp` (worktree: `.worktrees/cpp-dsp`)

---

## Phase 1: Build Infrastructure and Minimal Engine (Tasks 1-4)

Goal: `liborpheus_dsp` compiles, Plaits FM engine renders audio into a float buffer.

---

### Task 1: Create liborpheus_dsp directory structure and CMake build

**Files:**
- Create: `liborpheus_dsp/CMakeLists.txt`
- Create: `liborpheus_dsp/include/orpheus_dsp.h`
- Create: `liborpheus_dsp/src/orpheus_compat.h`

**Step 1: Create directory structure**

```bash
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp
mkdir -p liborpheus_dsp/{include,src,platform/{android,jvm,wasm},test}
```

**Step 2: Create the compatibility shim**

Create `liborpheus_dsp/src/orpheus_compat.h`:

```cpp
#pragma once

// ── STM32 → Standard C++ compatibility shim ─────────────────────
// Allows original eurorack source to compile unmodified on desktop/mobile/WASM.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

// stmlib.h macros
#ifndef DISALLOW_COPY_AND_ASSIGN
#define DISALLOW_COPY_AND_ASSIGN(TypeName) \
    TypeName(const TypeName&) = delete;    \
    TypeName& operator=(const TypeName&) = delete;
#endif

// CONSTRAIN: in-place clamp (used heavily in MI code)
// The original is a macro that modifies `var` in place.
#ifndef CONSTRAIN
#define CONSTRAIN(var, min, max) \
    if ((var) < (min)) (var) = (min); \
    else if ((var) > (max)) (var) = (max);
#endif

// CLIP: 16-bit signed clip
#ifndef CLIP
#define CLIP(x) if ((x) < -32767) (x) = -32767; if ((x) > 32767) (x) = 32767;
#endif

// ARM SSAT intrinsics → standard clamp for non-ARM
#ifndef __arm__
inline int16_t Clip16(int32_t x) {
    return static_cast<int16_t>(std::clamp(x, -32768, 32767));
}
inline uint16_t ClipU16(int32_t x) {
    return static_cast<uint16_t>(std::clamp(x, 0, 65535));
}
#endif

// stmlib BufferAllocator — heap-based replacement for STM32 SRAM allocator.
// Only called during Init(), never on audio thread.
namespace stmlib {
class BufferAllocator {
public:
    BufferAllocator() : buffer_(nullptr), size_(0), offset_(0) {}
    BufferAllocator(uint8_t* buffer, size_t size)
        : buffer_(buffer), size_(size), offset_(0) {}

    void Init(uint8_t* buffer, size_t size) {
        buffer_ = buffer;
        size_ = size;
        offset_ = 0;
    }

    template<typename T>
    T* Allocate(size_t count) {
        size_t bytes = count * sizeof(T);
        // Align to sizeof(T) or 4, whichever is larger
        size_t align = std::max(sizeof(T), size_t(4));
        offset_ = (offset_ + align - 1) & ~(align - 1);
        T* ptr = reinterpret_cast<T*>(buffer_ + offset_);
        offset_ += bytes;
        std::memset(ptr, 0, bytes);
        return ptr;
    }

    size_t used() const { return offset_; }

private:
    uint8_t* buffer_;
    size_t size_;
    size_t offset_;
};
}  // namespace stmlib
```

**Step 3: Create the C API header**

Create `liborpheus_dsp/include/orpheus_dsp.h` — use the exact API from the design doc:

```cpp
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>
#include <stdint.h>

// ── Opaque engine handle ─────────────────────────
typedef struct OrpheusEngine OrpheusEngine;

// ── Lifecycle ────────────────────────────────────
OrpheusEngine* orpheus_engine_create(float sample_rate);
void           orpheus_engine_destroy(OrpheusEngine* engine);

// ── Topology (called once per preset load) ───────
int orpheus_engine_load_patch(OrpheusEngine* engine,
                              const uint8_t* descriptor, size_t length);

// ── Audio render (called from audio thread) ──────
void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames);

// ── Parameter control (called from UI thread) ────
void  orpheus_engine_set_port(OrpheusEngine* engine,
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

// ── Monitoring (polled at ~60fps from UI thread) ─
typedef struct {
    float peak_left;
    float peak_right;
    float cpu_load;
    float voice_levels[12];
    float lfo_output;
    float master_level;
    float bend_position;
} OrpheusMonitorData;

void orpheus_engine_get_monitor(OrpheusEngine* engine,
                                OrpheusMonitorData* out);

void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames);

#ifdef __cplusplus
}
#endif
```

**Step 4: Create CMakeLists.txt**

Create `liborpheus_dsp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(orpheus_dsp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Eurorack source location ──────────────────────
# Set EURORACK_DIR to the path of the eurorack repo.
# Default: sibling directory or environment variable.
if(NOT DEFINED EURORACK_DIR)
    if(DEFINED ENV{EURORACK_DIR})
        set(EURORACK_DIR $ENV{EURORACK_DIR})
    else()
        set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../eurorack")
    endif()
endif()
message(STATUS "Eurorack source: ${EURORACK_DIR}")

# ── Compat shim include (must come BEFORE eurorack includes) ──
# This overrides stmlib.h macros for non-STM32 targets.
set(COMPAT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src")

# ── Plaits engine sources ─────────────────────────
file(GLOB PLAITS_ENGINE_SRC "${EURORACK_DIR}/plaits/dsp/engine/*.cc")
file(GLOB PLAITS_ENGINE2_SRC "${EURORACK_DIR}/plaits/dsp/engine2/*.cc")
set(PLAITS_SRC
    ${PLAITS_ENGINE_SRC}
    ${PLAITS_ENGINE2_SRC}
    "${EURORACK_DIR}/plaits/dsp/voice.cc"
    "${EURORACK_DIR}/plaits/dsp/speech/naive_speech_synth.cc"
    "${EURORACK_DIR}/plaits/dsp/speech/sam_speech_synth.cc"
    "${EURORACK_DIR}/plaits/dsp/physical_modelling/string_voice.cc"
    "${EURORACK_DIR}/plaits/dsp/physical_modelling/resonator.cc"
    "${EURORACK_DIR}/plaits/dsp/chords/chord_bank.cc"
    "${EURORACK_DIR}/plaits/resources.cc"
)

# ── stmlib sources ────────────────────────────────
set(STMLIB_SRC
    "${EURORACK_DIR}/stmlib/dsp/atan.cc"
    "${EURORACK_DIR}/stmlib/dsp/units.cc"
    "${EURORACK_DIR}/stmlib/utils/random.cc"
)

# ── Clouds sources ────────────────────────────────
set(CLOUDS_SRC
    "${EURORACK_DIR}/clouds/dsp/granular_processor.cc"
    "${EURORACK_DIR}/clouds/dsp/mu_law.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/frame_transformation.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/phase_vocoder.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/stft.cc"
    "${EURORACK_DIR}/clouds/resources.cc"
)

# ── Rings sources ─────────────────────────────────
set(RINGS_SRC
    "${EURORACK_DIR}/rings/dsp/fm_voice.cc"
    "${EURORACK_DIR}/rings/dsp/part.cc"
    "${EURORACK_DIR}/rings/dsp/string_synth_part.cc"
    "${EURORACK_DIR}/rings/dsp/resonator.cc"
    "${EURORACK_DIR}/rings/resources.cc"
)

# ── Warps sources ─────────────────────────────────
set(WARPS_SRC
    "${EURORACK_DIR}/warps/dsp/modulator.cc"
    "${EURORACK_DIR}/warps/dsp/vocoder.cc"
    "${EURORACK_DIR}/warps/dsp/filter_bank.cc"
    "${EURORACK_DIR}/warps/resources.cc"
)

# ── Marbles sources ───────────────────────────────
set(MARBLES_SRC
    "${EURORACK_DIR}/marbles/dsp/t_generator.cc"
    "${EURORACK_DIR}/marbles/dsp/x_y_generator.cc"
    "${EURORACK_DIR}/marbles/dsp/scale_recorder.cc"
    "${EURORACK_DIR}/marbles/random/t_generator.cc"
    "${EURORACK_DIR}/marbles/random/x_y_generator.cc"
    "${EURORACK_DIR}/marbles/resources.cc"
)

# ── Orpheus engine sources ────────────────────────
set(ORPHEUS_SRC
    "src/orpheus_engine.cpp"
    "src/orpheus_graph.cpp"
    "src/orpheus_monitor.cpp"
    "src/orpheus_units.cpp"
    "src/orpheus_patch.cpp"
)

# ── Library target ────────────────────────────────
add_library(orpheus_dsp STATIC
    ${ORPHEUS_SRC}
    ${STMLIB_SRC}
    ${PLAITS_SRC}
    ${CLOUDS_SRC}
    ${RINGS_SRC}
    ${WARPS_SRC}
    ${MARBLES_SRC}
)

target_include_directories(orpheus_dsp PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_include_directories(orpheus_dsp PRIVATE
    "${COMPAT_DIR}"
    "${EURORACK_DIR}"
)

# Compile flags: treat warnings loosely for MI code (it targets GCC ARM)
target_compile_options(orpheus_dsp PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-unused-variable
    -Wno-missing-field-initializers
    "$<$<CONFIG:Release>:-O3>"
    "$<$<CONFIG:Release>:-ffast-math>"
)

# Define TEST to use non-ARM code paths in stmlib
target_compile_definitions(orpheus_dsp PRIVATE TEST)

# ── Desktop test executable (optional) ────────────
option(BUILD_TESTS "Build test executable" OFF)
if(BUILD_TESTS)
    add_executable(orpheus_dsp_test test/test_main.cpp)
    target_link_libraries(orpheus_dsp_test orpheus_dsp)
endif()
```

**Step 5: Commit**

```bash
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp
git add liborpheus_dsp/
git commit -m "build(dsp): Add liborpheus_dsp directory structure, C API header, compat shim, and CMake build"
```

---

### Task 2: Create stub engine implementation that compiles

**Files:**
- Create: `liborpheus_dsp/src/orpheus_engine.cpp`
- Create: `liborpheus_dsp/src/orpheus_engine.h` (internal header)
- Create: `liborpheus_dsp/src/orpheus_graph.cpp`
- Create: `liborpheus_dsp/src/orpheus_monitor.cpp`
- Create: `liborpheus_dsp/src/orpheus_units.cpp`
- Create: `liborpheus_dsp/src/orpheus_patch.cpp`

**Step 1: Create internal engine header**

Create `liborpheus_dsp/src/orpheus_engine.h`:

```cpp
#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"
#include <atomic>
#include <cstring>
#include <vector>

// Forward-declare MI types we'll use
namespace plaits {
class Voice;
struct Patch;
struct Modulations;
}

struct OrpheusEngine {
    float sample_rate;

    // Interleaved stereo output staging buffer
    std::vector<float> staging_buffer;

    // Monitor data (written by audio thread, read by UI thread)
    std::atomic<float> peak_left{0.0f};
    std::atomic<float> peak_right{0.0f};
    std::atomic<float> cpu_load{0.0f};

    // Master volume
    std::atomic<float> master_volume{0.8f};

    // Voice state
    struct VoiceState {
        std::atomic<float> tune{60.0f};  // MIDI note
        std::atomic<int> gate{0};
    };
    VoiceState voices[12];  // 8 main + 4 REPL

    // Plaits voice instances (allocated in load_patch)
    // Will be added in Task 3
};
```

**Step 2: Create stub source files**

Create `liborpheus_dsp/src/orpheus_engine.cpp`:

```cpp
#include "orpheus_engine.h"
#include <cstring>
#include <chrono>

extern "C" {

OrpheusEngine* orpheus_engine_create(float sample_rate) {
    auto* engine = new OrpheusEngine();
    engine->sample_rate = sample_rate;
    return engine;
}

void orpheus_engine_destroy(OrpheusEngine* engine) {
    delete engine;
}

int orpheus_engine_load_patch(OrpheusEngine* engine,
                              const uint8_t* descriptor, size_t length) {
    // TODO: parse descriptor, build graph
    return 0;
}

void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames) {
    // Silence for now — will be replaced with real DSP in Task 3
    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));
}

void orpheus_engine_set_port(OrpheusEngine* engine,
                             const char* plugin_uri,
                             const char* symbol,
                             float value) {
    // TODO: route to plugin
}

float orpheus_engine_get_port(OrpheusEngine* engine,
                              const char* plugin_uri,
                              const char* symbol) {
    return 0.0f;
}

void orpheus_engine_set_voice_gate(OrpheusEngine* engine,
                                   int index, int active) {
    if (index >= 0 && index < 12) {
        engine->voices[index].gate.store(active);
    }
}

void orpheus_engine_set_voice_tune(OrpheusEngine* engine,
                                   int index, float tune) {
    if (index >= 0 && index < 12) {
        engine->voices[index].tune.store(tune);
    }
}

void orpheus_engine_trigger_drum(OrpheusEngine* engine,
                                 int drum_index, float accent) {
    // TODO
}

void orpheus_engine_set_master_volume(OrpheusEngine* engine, float v) {
    engine->master_volume.store(v);
}

void orpheus_engine_set_drive(OrpheusEngine* engine, float v) { }
void orpheus_engine_set_delay_mix(OrpheusEngine* engine, float v) { }
void orpheus_engine_set_vibrato(OrpheusEngine* engine, float v) { }
void orpheus_engine_set_bend(OrpheusEngine* engine, float v) { }

void orpheus_engine_get_monitor(OrpheusEngine* engine,
                                OrpheusMonitorData* out) {
    std::memset(out, 0, sizeof(OrpheusMonitorData));
    out->peak_left = engine->peak_left.load();
    out->peak_right = engine->peak_right.load();
    out->cpu_load = engine->cpu_load.load();
}

void orpheus_engine_get_waveform(OrpheusEngine* engine,
                                 float* buffer, int max_frames) {
    std::memset(buffer, 0, max_frames * sizeof(float));
}

}  // extern "C"
```

Create empty stubs for remaining source files:

`liborpheus_dsp/src/orpheus_graph.cpp`:
```cpp
// Graph scheduler — Tarjan SCC topological sort
// Ported from DspGraphScheduler.kt in Task 6
#include "orpheus_engine.h"
```

`liborpheus_dsp/src/orpheus_monitor.cpp`:
```cpp
// Lock-free monitor ring buffer for visualization data
#include "orpheus_engine.h"
```

`liborpheus_dsp/src/orpheus_units.cpp`:
```cpp
// Basic DSP units: oscillators, math, dynamics, utility
// These are simple units not from MI (LFO, delay, distortion, etc.)
#include "orpheus_engine.h"
```

`liborpheus_dsp/src/orpheus_patch.cpp`:
```cpp
// Binary patch descriptor parser
#include "orpheus_engine.h"
```

**Step 3: Test that it compiles on desktop (macOS)**

```bash
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp
mkdir -p build && cd build
EURORACK_DIR=/Users/balch/Source/eurorack cmake .. -DBUILD_TESTS=OFF
cmake --build . 2>&1 | tail -20
```

Expected: Compiles with warnings (MI code) but no errors. If there are errors, they'll be missing includes or macro issues — fix in `orpheus_compat.h`.

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/
git commit -m "feat(dsp): Add stub OrpheusEngine implementation that compiles with MI source"
```

---

### Task 3: Integrate Plaits Voice rendering

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Context:**
- Plaits Voice class: `/Users/balch/Source/eurorack/plaits/dsp/voice.h`
- Voice renders in blocks of `kBlockSize=12` samples at 48kHz
- Each voice produces `Frame { int16_t out, aux }` — we need float conversion
- Voice needs `Patch` (engine select + 6 params) and `Modulations` (CV + trigger)
- Voice::Init requires a `BufferAllocator` with ~16KB per voice

**Step 1: Add Plaits voice instances to OrpheusEngine**

Update `orpheus_engine.h` to include Plaits headers and allocate voices:

```cpp
#pragma once

#include "orpheus_dsp.h"
#include "orpheus_compat.h"

// Include MI headers — compat shim must be included first
#include "plaits/dsp/voice.h"

#include <atomic>
#include <cstring>
#include <memory>
#include <vector>

static constexpr int kNumMainVoices = 8;
static constexpr int kNumReplVoices = 4;
static constexpr int kNumVoices = kNumMainVoices + kNumReplVoices;
static constexpr int kVoiceAllocBytes = 32768;  // 32KB per voice (generous)
static constexpr int kPlaitsBlockSize = 12;      // plaits::kBlockSize

struct OrpheusEngine {
    float sample_rate;

    // Plaits voices
    plaits::Voice voices_dsp[kNumVoices];
    uint8_t voice_alloc_buffers[kNumVoices][kVoiceAllocBytes];

    // Per-voice parameter state (written from UI, read from audio)
    struct VoiceParams {
        std::atomic<float> tune{60.0f};
        std::atomic<int> gate{0};
        std::atomic<float> harmonics{0.5f};
        std::atomic<float> timbre{0.5f};
        std::atomic<float> morph{0.5f};
        std::atomic<int> engine_index{0};
        int prev_gate{0};  // audio-thread only, for edge detection
    };
    VoiceParams voice_params[kNumVoices];

    // Master controls
    std::atomic<float> master_volume{0.8f};

    // Monitor
    std::atomic<float> peak_left{0.0f};
    std::atomic<float> peak_right{0.0f};
    std::atomic<float> cpu_load{0.0f};
    float voice_levels[kNumVoices] = {};

    // Staging buffers for block processing
    float out_buffer[kPlaitsBlockSize] = {};
    float aux_buffer[kPlaitsBlockSize] = {};
};
```

**Step 2: Implement create with voice initialization**

Update `orpheus_engine_create` in `orpheus_engine.cpp`:

```cpp
OrpheusEngine* orpheus_engine_create(float sample_rate) {
    auto* engine = new OrpheusEngine();
    engine->sample_rate = sample_rate;

    // Initialize all Plaits voices
    for (int i = 0; i < kNumVoices; i++) {
        stmlib::BufferAllocator allocator(
            engine->voice_alloc_buffers[i], kVoiceAllocBytes);
        engine->voices_dsp[i].Init(&allocator);
    }

    return engine;
}
```

**Step 3: Implement process with Plaits rendering**

Replace the `orpheus_engine_process` stub:

```cpp
void orpheus_engine_process(OrpheusEngine* engine,
                            float* output_buffer, int num_frames) {
    // Zero the output
    std::memset(output_buffer, 0, num_frames * 2 * sizeof(float));

    const float volume = engine->master_volume.load();
    const float inv_32768 = 1.0f / 32768.0f;

    // Process each voice
    for (int v = 0; v < kNumMainVoices; v++) {
        auto& vp = engine->voice_params[v];
        auto& voice = engine->voices_dsp[v];

        // Build Plaits Patch from atomic params
        plaits::Patch patch;
        patch.engine = vp.engine_index.load();
        patch.note = vp.tune.load();
        patch.harmonics = vp.harmonics.load();
        patch.timbre = vp.timbre.load();
        patch.morph = vp.morph.load();
        patch.frequency_modulation_amount = 0.0f;
        patch.timbre_modulation_amount = 0.0f;
        patch.morph_modulation_amount = 0.0f;
        patch.decay = 0.5f;
        patch.lpg_colour = 0.5f;

        // Build Modulations
        plaits::Modulations mod;
        std::memset(&mod, 0, sizeof(mod));
        int current_gate = vp.gate.load();
        if (current_gate && !vp.prev_gate) {
            mod.trigger = plaits::TRIGGER_RISING_EDGE;
        } else if (current_gate) {
            mod.trigger = plaits::TRIGGER_HIGH;
        } else {
            mod.trigger = plaits::TRIGGER_LOW;
        }
        mod.trigger_patched = true;
        mod.level_patched = false;
        vp.prev_gate = current_gate;

        // Render in kPlaitsBlockSize chunks
        int frames_done = 0;
        float voice_peak = 0.0f;

        while (frames_done < num_frames) {
            int block = std::min(kPlaitsBlockSize, num_frames - frames_done);

            plaits::Voice::Frame frames[kPlaitsBlockSize];
            voice.Render(patch, mod, frames, block);

            // After first block, clear trigger (only edge on first block)
            mod.trigger = current_gate ? plaits::TRIGGER_HIGH : plaits::TRIGGER_LOW;

            // Mix into interleaved stereo output with float conversion
            for (int i = 0; i < block; i++) {
                float sample_out = frames[i].out * inv_32768 * volume;
                float sample_aux = frames[i].aux * inv_32768 * volume;

                int idx = (frames_done + i) * 2;
                output_buffer[idx]     += sample_out;  // Left
                output_buffer[idx + 1] += sample_aux;  // Right

                float abs_out = std::fabs(sample_out);
                if (abs_out > voice_peak) voice_peak = abs_out;
            }

            frames_done += block;
        }

        engine->voice_levels[v] = voice_peak;
    }

    // Compute peak for monitoring
    float pk_l = 0.0f, pk_r = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float l = std::fabs(output_buffer[i * 2]);
        float r = std::fabs(output_buffer[i * 2 + 1]);
        if (l > pk_l) pk_l = l;
        if (r > pk_r) pk_r = r;
    }
    engine->peak_left.store(pk_l);
    engine->peak_right.store(pk_r);
}
```

**Step 4: Rebuild and verify compilation**

```bash
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp/build
cmake --build . 2>&1 | tail -20
```

Expected: Compiles. Likely issues: missing Plaits includes or resources. Fix by adjusting include paths or adding missing `.cc` files to CMakeLists.

**Step 5: Commit**

```bash
git add liborpheus_dsp/
git commit -m "feat(dsp): Integrate Plaits Voice rendering into OrpheusEngine"
```

---

### Task 4: Desktop smoke test — render audio to WAV

**Files:**
- Create: `liborpheus_dsp/test/test_main.cpp`

**Step 1: Write a minimal test that renders Plaits to a WAV file**

Create `liborpheus_dsp/test/test_main.cpp`:

```cpp
#include "orpheus_dsp.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

// Minimal WAV writer
void write_wav(const char* path, const float* data, int num_frames, int sample_rate) {
    FILE* f = fopen(path, "wb");
    if (!f) { fprintf(stderr, "Cannot open %s\n", path); return; }

    int channels = 2;
    int bytes_per_sample = 2;  // 16-bit
    int data_size = num_frames * channels * bytes_per_sample;
    int file_size = 44 + data_size;

    // WAV header
    fwrite("RIFF", 1, 4, f);
    int32_t chunk_size = file_size - 8; fwrite(&chunk_size, 4, 1, f);
    fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f);
    int32_t fmt_size = 16; fwrite(&fmt_size, 4, 1, f);
    int16_t audio_fmt = 1; fwrite(&audio_fmt, 2, 1, f);
    int16_t nch = channels; fwrite(&nch, 2, 1, f);
    int32_t sr = sample_rate; fwrite(&sr, 4, 1, f);
    int32_t byte_rate = sr * channels * bytes_per_sample; fwrite(&byte_rate, 4, 1, f);
    int16_t block_align = channels * bytes_per_sample; fwrite(&block_align, 2, 1, f);
    int16_t bps = 16; fwrite(&bps, 2, 1, f);
    fwrite("data", 1, 4, f);
    fwrite(&data_size, 4, 1, f);

    // Convert float → int16
    for (int i = 0; i < num_frames * channels; i++) {
        float s = data[i];
        if (s > 1.0f) s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        int16_t sample = static_cast<int16_t>(s * 32767.0f);
        fwrite(&sample, 2, 1, f);
    }

    fclose(f);
    printf("Wrote %s (%d frames, %d Hz)\n", path, num_frames, sample_rate);
}

int main() {
    printf("Creating OrpheusEngine...\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Set voice 0 to FM engine (index 10 in Plaits), note C4
    orpheus_engine_set_voice_tune(engine, 0, 60.0f);

    // Gate on
    orpheus_engine_set_voice_gate(engine, 0, 1);

    // Render 2 seconds
    const int num_frames = 48000 * 2;
    std::vector<float> buffer(num_frames * 2, 0.0f);

    // Process in 128-frame chunks (like Oboe)
    for (int offset = 0; offset < num_frames; offset += 128) {
        int chunk = std::min(128, num_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    // Gate off at 1 second
    orpheus_engine_set_voice_gate(engine, 0, 0);
    for (int offset = 48000; offset < num_frames; offset += 128) {
        int chunk = std::min(128, num_frames - offset);
        orpheus_engine_process(engine, buffer.data() + offset * 2, chunk);
    }

    write_wav("test_output.wav", buffer.data(), num_frames, 48000);

    OrpheusMonitorData mon;
    orpheus_engine_get_monitor(engine, &mon);
    printf("Peak L=%.3f R=%.3f CPU=%.1f%%\n",
           mon.peak_left, mon.peak_right, mon.cpu_load);

    orpheus_engine_destroy(engine);
    printf("Done.\n");
    return 0;
}
```

**Step 2: Build and run the test**

```bash
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp/build
cmake .. -DBUILD_TESTS=ON -DEURORACK_DIR=/Users/balch/Source/eurorack
cmake --build .
./orpheus_dsp_test
```

Expected: Prints "Wrote test_output.wav", peak values > 0. Open the WAV in any audio player — you should hear a Plaits FM tone.

**Step 3: Verify audio is not silence**

```bash
# Check peak is non-zero
./orpheus_dsp_test 2>&1 | grep "Peak"
```

Expected: `Peak L=0.xxx R=0.xxx` where xxx > 0.

**Step 4: Commit**

```bash
git add liborpheus_dsp/test/
git commit -m "test(dsp): Add desktop smoke test that renders Plaits to WAV"
```

---

## Phase 2: Android Integration (Tasks 5-6)

Goal: Replace JNI→Kotlin audio path with direct C++ processing on Android. Verify clicks are gone.

---

### Task 5: Wire liborpheus_dsp into Android NDK build

**Files:**
- Modify: `apps/androidApp/src/main/cpp/CMakeLists.txt`
- Modify: `apps/androidApp/src/main/cpp/OboeEngine.cpp`
- Modify: `apps/androidApp/src/main/cpp/OboeEngine.h`
- Modify: `apps/androidApp/src/main/cpp/jni_bridge.cpp`

**Step 1: Update Android CMakeLists to link liborpheus_dsp**

Modify `apps/androidApp/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(orpheus_oboe)

# ── Oboe ──────────────────────────────────────────
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

# ── liborpheus_dsp ────────────────────────────────
# EURORACK_DIR should be set by the Gradle externalNativeBuild config
# or default to a sibling directory of the project root.
if(NOT DEFINED EURORACK_DIR)
    set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../eurorack"
        CACHE PATH "Path to eurorack source repo")
endif()
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../liborpheus_dsp"
    "${CMAKE_CURRENT_BINARY_DIR}/liborpheus_dsp"
)

# ── Android bridge ────────────────────────────────
add_library(orpheus_oboe SHARED
    jni_bridge.cpp
    OboeEngine.cpp
)

target_include_directories(orpheus_oboe PRIVATE ${oboe_SOURCE_DIR}/include)
target_link_libraries(orpheus_oboe android log oboe orpheus_dsp)
target_compile_options(orpheus_oboe PRIVATE -Wall -Werror "$<$<CONFIG:RELEASE>:-O3 -ffast-math>")
target_link_options(orpheus_oboe PRIVATE "-Wl,-z,max-page-size=16384")
```

**Step 2: Update OboeEngine to own an OrpheusEngine and process directly**

Modify `OboeEngine.h` — add `#include "orpheus_dsp.h"` and replace the JNI callback with direct C++ processing:

```cpp
#pragma once
#include <oboe/Oboe.h>
#include "orpheus_dsp.h"
#include <atomic>
#include <chrono>

class OboeEngine : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback {
public:
    OboeEngine();
    ~OboeEngine();

    oboe::Result openStream();
    oboe::Result open(JNIEnv* env, jobject kotlinBridge);
    oboe::Result requestStart();
    oboe::Result stop();

    bool isRunning() const;
    int32_t getSampleRate() const;
    int32_t getFramesPerBuffer() const;
    double getCpuLoad() const;

    // Direct C++ DSP engine access (no JNI for audio)
    OrpheusEngine* getDspEngine() { return dsp_engine_; }

    // C API pass-through (called from JNI bridge for parameter control)
    void setPort(const char* uri, const char* sym, float value);
    void setVoiceGate(int index, int active);
    void setVoiceTune(int index, float tune);
    void triggerDrum(int drumIndex, float accent);
    void setMasterVolume(float v);
    void setDrive(float v);
    void setDelayMix(float v);
    void setVibrato(float v);
    void setBend(float v);
    void getMonitor(OrpheusMonitorData* out);

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(
        oboe::AudioStream* stream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    OrpheusEngine* dsp_engine_ = nullptr;
    std::atomic<bool> mIsRunning{false};
    std::atomic<double> mCpuLoad{0.0};
};
```

Modify `OboeEngine.cpp` — the `onAudioReady` becomes trivial:

```cpp
oboe::DataCallbackResult OboeEngine::onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    if (!mIsRunning.load() || !dsp_engine_) {
        memset(audioData, 0, numFrames * 2 * sizeof(float));
        return oboe::DataCallbackResult::Stop;
    }

    auto start = std::chrono::steady_clock::now();

    // Direct C++ DSP — no JNI, no Kotlin, no GC
    orpheus_engine_process(dsp_engine_, (float*)audioData, numFrames);

    auto end = std::chrono::steady_clock::now();
    double us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    double budget = (double)numFrames / stream->getSampleRate() * 1e6;
    mCpuLoad.store(us / budget);

    return oboe::DataCallbackResult::Continue;
}
```

The `open()` method creates the `OrpheusEngine` instead of setting up JNI rendering:

```cpp
oboe::Result OboeEngine::open(JNIEnv* env, jobject kotlinBridge) {
    oboe::Result result = openStream();
    if (result != oboe::Result::OK) return result;

    float sr = static_cast<float>(mStream->getSampleRate());
    dsp_engine_ = orpheus_engine_create(sr);

    LOGI("Stream opened: sampleRate=%d, framesPerBurst=%d, dsp_engine=%p",
         mStream->getSampleRate(), mStream->getFramesPerBurst(), dsp_engine_);
    return oboe::Result::OK;
}
```

**Step 3: Update JNI bridge to expose C API methods**

Update `jni_bridge.cpp` to add JNI methods for `setPort`, `setVoiceGate`, `setVoiceTune`, etc. that call through to the `OrpheusEngine` C API. The existing `nativeOpen`, `nativeRequestStart`, `nativeStop` stay but the `renderAudio` JNI callback is no longer needed.

Add new JNI exports:

```cpp
JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetPort(
    JNIEnv* env, jobject thiz, jstring uri, jstring sym, jfloat value) {
    const char* c_uri = env->GetStringUTFChars(uri, nullptr);
    const char* c_sym = env->GetStringUTFChars(sym, nullptr);
    engine.setPort(c_uri, c_sym, value);
    env->ReleaseStringUTFChars(uri, c_uri);
    env->ReleaseStringUTFChars(sym, c_sym);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceGate(
    JNIEnv* env, jobject thiz, jint index, jboolean active) {
    engine.setVoiceGate(index, active ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceTune(
    JNIEnv* env, jobject thiz, jint index, jfloat tune) {
    engine.setVoiceTune(index, tune);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetMasterVolume(
    JNIEnv* env, jobject thiz, jfloat value) {
    engine.setMasterVolume(value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetMonitor(
    JNIEnv* env, jobject thiz, jfloatArray out) {
    OrpheusMonitorData mon;
    engine.getMonitor(&mon);
    env->SetFloatArrayRegion(out, 0, sizeof(mon) / sizeof(float),
                             reinterpret_cast<float*>(&mon));
}
```

**Step 4: Update Kotlin OboeAudioBridge to add new native methods**

Add corresponding `external fun` declarations to `OboeAudioBridge.kt`:

```kotlin
external fun nativeSetPort(uri: String, symbol: String, value: Float)
external fun nativeSetVoiceGate(index: Int, active: Boolean)
external fun nativeSetVoiceTune(index: Int, tune: Float)
external fun nativeSetMasterVolume(value: Float)
external fun nativeGetMonitor(out: FloatArray)
```

**Step 5: Update OboeAudioEngine to use bridge methods instead of Kotlin DSP**

The `OboeAudioEngine.start()` no longer needs to sort the Kotlin graph or allocate Kotlin buffers — the C++ engine handles all of this internally.

**Step 6: Build on device and test for clicks**

```bash
cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp
./gradlew :apps:androidApp:assembleDebug
adb install -r apps/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Test: Play notes. Verify audio plays and no clicks. Check CPU:

```bash
adb shell "top -n 1 -b -H -p $(adb shell pidof org.balch.orpheus)" 2>&1 | grep -E "Oboe|Render|orpheus"
```

Expected: OboeAudioThread CPU drops from ~63% to ~15-25%.

**Step 7: Commit**

```bash
git add apps/androidApp/ core/audio/src/androidMain/ liborpheus_dsp/
git commit -m "feat(android): Wire liborpheus_dsp into Oboe audio path — no JNI in audio callback"
```

---

### Task 6: Update Kotlin SynthEngine to delegate to C++ bridge on Android

**Files:**
- Modify: `core/audio/src/androidMain/kotlin/org/balch/orpheus/core/audio/dsp/OboeAudioEngine.kt`
- Modify: `core/audio/src/androidMain/kotlin/org/balch/orpheus/core/audio/dsp/OboeAudioBridge.kt`

**Step 1:** Replace the `OboeGraphScheduler` usage in `OboeAudioEngine` with calls to the native bridge methods. The `SynthController.overrideDelegates()` mechanism (already on the branch) is the hook — override the `pluginPortSetter` to call `nativeSetPort()` instead of routing through the Kotlin DSP graph.

**Step 2:** The `SynthEngine` interface methods (`setVoiceGate`, `setVoiceTune`, `setMasterVolume`, etc.) delegate to `OboeAudioBridge.nativeSetVoiceGate()`, etc.

**Step 3:** The 60fps monitor polling calls `nativeGetMonitor()` and unpacks the `OrpheusMonitorData` struct into the existing `StateFlow` properties.

**Step 4: Commit**

```bash
git commit -am "feat(android): Delegate SynthEngine to native C++ bridge"
```

---

## Phase 3: Remaining MI Engines (Tasks 7-9)

Goal: Add Clouds, Rings, Warps, Marbles, Drums, Reverb to the C++ engine.

---

### Task 7: Add Clouds (Granular) to liborpheus_dsp

**Context:** Clouds uses `ShortFrame` (int16) I/O and its own internal sample rate (32kHz hi-fi or 16kHz lo-fi). Needs sample rate conversion wrappers.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h` — add GranularProcessor instance
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp` — wire clouds into signal chain
- Modify: `liborpheus_dsp/CMakeLists.txt` — verify CLOUDS_SRC compiles

**Key:** Clouds `GranularProcessor` needs a large audio buffer (~65KB for 16-bit stereo). Allocate in `orpheus_engine_create()`. Process chain: voice sum → float-to-short → Clouds → short-to-float.

---

### Task 8: Add Rings (Resonator), Warps (Modulator), Reverb (Dattorro)

**Context:**
- Rings `Resonator::Process()` takes float I/O — simplest to integrate
- Warps `Modulator::Process()` uses `ShortFrame` I/O like Clouds
- Reverb: The Dattorro reverb is already ported to Kotlin (`DattorroReverb.kt`). Since it's not from MI source, either port the Kotlin version to C++ or use Rings' `fx/reverb.h` directly.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

---

### Task 9: Add Marbles (Flux) and Drums

**Context:**
- Marbles `TGenerator` + `XYGenerator` — clock-driven random sequence generator
- Drums: Plaits already has `BassDrumEngine`, `SnareDrumEngine`, `HiHatEngine` registered as engines 21-23. Wire `trigger_drum()` to trigger those engine indices.

---

## Phase 4: Non-MI Units and Graph Builder (Tasks 10-12)

Goal: Add LFO, delay, distortion, stereo processing. Implement the patch descriptor parser and Kotlin DSL.

---

### Task 10: Implement basic DSP units in C++ (LFO, delay, distortion, limiter)

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.cpp`
- Create: `liborpheus_dsp/src/orpheus_units.h`

**These are simple units** not from MI source:
- **HyperLFO**: Sine/triangle/square with frequency modulation (port from `DspOscillators.kt`)
- **Delay**: Circular buffer with feedback and modulation (port from `DspUtilityUnits.kt`)
- **Distortion/Drive**: Tanh saturation (port from existing `TanhLimiter.kt`)
- **Stereo**: Pan law, width control
- **Hard limiter**: `std::clamp` on master output

---

### Task 11: Implement graph scheduler in C++ (Tarjan SCC)

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`
- Create: `liborpheus_dsp/src/orpheus_graph.h`

**Port from:** `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspGraphScheduler.kt` (~200 lines). The algorithm is identical — Tarjan's SCC for topological sort of DSP units with feedback cycle handling.

---

### Task 12: Implement patch descriptor parser and Kotlin DSL

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_patch.cpp`
- Create: `liborpheus_dsp/src/orpheus_patch.h`
- Create: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/OrpheusPatchDsl.kt`

**Binary descriptor format:** Compact flat buffer:
```
[header: magic + version + unit_count + connection_count]
[units: type_enum(u16) + id(u16) + param_count(u16) + params(float[])]
[connections: src_unit(u16) + src_port(u16) + dst_unit(u16) + dst_port(u16)]
```

The Kotlin DSL builds this in-memory and serializes to a `ByteArray` sent via JNI.

---

## Phase 5: WASM via Emscripten (Tasks 13-14)

Goal: Same C++ DSP compiled to WASM, running in AudioWorklet.

---

### Task 13: Emscripten build target

**Files:**
- Create: `liborpheus_dsp/platform/wasm/CMakeLists.txt`
- Create: `liborpheus_dsp/platform/wasm/wasm_exports.cpp`
- Create: `liborpheus_dsp/platform/wasm/orpheus_worklet.js`

**Build:** `emcmake cmake .. && emmake make` → produces `orpheus_dsp.wasm` + `orpheus_dsp.js`.

Export the C API functions via `EMSCRIPTEN_KEEPALIVE` or `-s EXPORTED_FUNCTIONS`.

The AudioWorklet processor loads the WASM module and calls `_orpheus_engine_process()` in its `process()` method.

---

### Task 14: Wire WASM DSP into Compose WASM app

**Files:**
- Modify: `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/core/audio/dsp/DspWorkerProxy.kt`
- Modify: `apps/composeApp/src/wasmJsMain/resources/index.html`

Replace the Kotlin WASM DSP worker with the Emscripten WASM module. The `DspWorkerProxy` command protocol stays the same — it sends `setPort`/`setVoiceGate`/etc. commands to the AudioWorklet, which routes them to the C API.

---

## Phase 6: JVM Desktop and Cleanup (Tasks 15-16)

---

### Task 15: JVM desktop integration via JNI

**Files:**
- Create: `liborpheus_dsp/platform/jvm/CMakeLists.txt`
- Create: `liborpheus_dsp/platform/jvm/jni_bridge.cpp`
- Create: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/NativeAudioEngine.kt`

Replace JSyn with PortAudio or Java Sound API for audio output, calling through to `orpheus_engine_process()` via JNI. Same pattern as Android but with a different audio backend.

---

### Task 16: Delete Kotlin DSP code

**Files to delete:**
- `core/audio/src/commonMain/kotlin/**/Dsp*.kt` (~15 files)
- `core/audio/src/androidMain/kotlin/**/Oboe{Oscillators,MathUnits,DspUnits,DynamicsUnits,...}.kt` (~12 files)
- `core/audio/src/jvmMain/kotlin/**/Jsyn*.kt` (~13 files)
- `core/dsp-engine/src/commonMain/` — DspSynthEngine, DspVoice, DspVoiceManager, DspWiringGraph (~9 files)
- `core/plugins/*/src/commonMain/**/Dsp*Unit.kt` (~8 files)
- `core/plugins/*/src/commonMain/**/engine/` — ported MI engines (~64 files)
- `apps/dspWorker/` — entire module
- JSyn dependency from `libs.versions.toml`

**Only delete after** all platforms are verified working with the C++ engine.

---

## Weekend Plan (Recommended Priority)

| Day | Tasks | Milestone |
|-----|-------|-----------|
| **Saturday AM** | 1-2 | liborpheus_dsp compiles with MI source |
| **Saturday PM** | 3-4 | Plaits renders audio (desktop WAV test) |
| **Sunday AM** | 5-6 | Android plays Plaits via C++ (no clicks!) |
| **Sunday PM** | 7-9 | Remaining MI engines integrated |
| **Stretch** | 10-12 | Non-MI units + patch DSL |
| **Later** | 13-16 | WASM, JVM, cleanup |

The critical validation point is **Task 5** — if Android plays audio without clicks, the architecture is proven and the rest is incremental.
