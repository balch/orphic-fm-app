---
name: dsp-implementation
description: Use when adding new C++ DSP units, engine parameters, buffer routing, normalization, or graph wiring. Covers the full path from engine atomics through unit processing to visualization and Kotlin port routing.
---

# C++ DSP Implementation

## Overview

All audio processing lives in `liborpheus_dsp/`. The C++ engine owns the audio thread; Kotlin writes parameters via atomics. Each DSP module follows a strict pattern: **engine atomics** (parameter storage) -> **set_port routing** (Kotlin->C++) -> **unit process function** (audio) -> **source buffers** (cross-module routing) -> **viz rings** (UI feedback).

## Engine Atomics (Parameter Storage)

**File**: `liborpheus_dsp/src/orpheus_engine.h`

Parameters are `std::atomic<T>` for lock-free UI -> audio communication:

```cpp
// In OrpheusEngine struct:
std::atomic<float> tides_frequency{0.5f};    // 0-1 knob position
std::atomic<float> tides_slope{0.5f};
std::atomic<float> tides_mix{0.0f};          // default off (mix knob pattern)
std::atomic<int>   tides_ramp_mode{1};       // enum as int
std::atomic<int>   tides_output_mode{0};
```

**Rules**:
- Always initialize with sensible defaults matching Kotlin Plugin defaults
- Use `std::memory_order_relaxed` for reads/writes (no ordering needed for knobs)
- Audio-thread-only state (smoothing, edge detection) is non-atomic in same struct
- MI module instances (e.g., `tides::PolySlopeGenerator`) live in engine, not unit state

## Engine Buffers

```cpp
// Per-channel output (for multi-output modules like Tides)
float tides_output_buffer[4][kMaxFrames] = {};

// Smoothing state (non-atomic, audio-thread only)
float tides_smooth_mix = 0.0f;

// MI module instance
tides::PolySlopeGenerator tides_generator;
```

## Port Routing (Kotlin -> C++)

**File**: `liborpheus_dsp/src/orpheus_engine_routing.cpp`

Hash-based routing from Kotlin `setPort(uri, symbol, value)`:

```cpp
void orpheus_engine_set_port(OrpheusEngine* engine,
                            const char* plugin_uri,
                            const char* symbol,
                            float value) {
    uint16_t uri_hash = engine_hash16(plugin_uri);
    uint16_t sym_hash = engine_hash16(symbol);

    // Route to graph ports (for wired connections)
    OrpheusGraph* g = engine->graph.load(std::memory_order_relaxed);
    if (g) orpheus_graph_set_port(g, uri_hash, sym_hash, value);

    // Direct atomic storage
    static uint16_t h_tides = engine_hash16("org.balch.orpheus.plugins.tides");
    if (uri_hash == h_tides) {
        if (sym_hash == engine_hash16("frequency"))
            engine->tides_frequency.store(value, std::memory_order_relaxed);
        else if (sym_hash == engine_hash16("mix"))
            engine->tides_mix.store(value, std::memory_order_relaxed);
        // ... one branch per parameter
        return;
    }
}
```

**Rules**:
- Hash strings are `static` (computed once)
- Symbol strings must match the Kotlin `PortSymbol.symbol` exactly
- URI must match the Kotlin `*_URI` constant

## Unit Type Registration

### 1. Add to enum (`orpheus_units.h`)

```cpp
enum OrpheusUnitType : uint16_t {
    // ... existing types ...
    UNIT_TIDES = 34,
    UNIT_TYPE_COUNT
};
```

### 2. Declare process function (`orpheus_units.h`)

```cpp
void unit_process_tides(GraphUnit* u, OrpheusEngine* engine,
                        int num_frames, float sample_rate);
```

### 3. Register in dispatch switch (`orpheus_graph.cpp`)

```cpp
case UNIT_TIDES:
    unit_process_tides(u, engine, num_frames, sr);
    break;
```

### 4. Add init case if needed (`orpheus_unit_basic.cpp` in `unit_init`)

```cpp
case UNIT_TIDES:
    // Type-specific initialization (smoothed ports, defaults)
    break;
```

## Process Function Structure

**Canonical example**: `liborpheus_dsp/src/orpheus_unit_tides.cpp`

```cpp
void unit_process_tides(GraphUnit* u, OrpheusEngine* engine,
                        int num_frames, float sample_rate) {
    // 1. Get output buffer pointers
    float* out0 = u->output_buffers[OPORT_OUT];
    float* out1 = u->output_buffers[OPORT_OUT_RIGHT];

    // 2. Self-bypass (zero ALL outputs + source buffers + viz)
    float mix = engine->tides_mix.load(std::memory_order_relaxed);
    if (mix <= 0.001f) {
        std::memset(out0, 0, num_frames * sizeof(float));
        std::memset(out1, 0, num_frames * sizeof(float));
        // Zero engine buffers too
        for (int ch = 0; ch < 4; ch++) {
            std::memset(engine->tides_output_buffer[ch], 0, num_frames * sizeof(float));
            std::memset(engine->warps_source_buffers[10+ch], 0, num_frames * sizeof(float));
        }
        // Zero viz
        for (int ch = 0; ch < 4; ch++)
            engine->viz_rings[VIZ_TIDES_CH0 + ch].write(0.0f);
        return;
    }

    // 3. Read ALL atomics into locals (one read per frame block)
    float frequency = engine->tides_frequency.load(std::memory_order_relaxed);
    int ramp_mode_i = engine->tides_ramp_mode.load(std::memory_order_relaxed);

    // 4. Clamp enums to valid range
    ramp_mode_i = std::clamp(ramp_mode_i, 0, 2);

    // 5. Frequency mapping (range-dependent)
    float hz = 0.001f * std::pow(10000.0f, frequency);  // control rate
    float norm_freq = hz / sample_rate;

    // 6. Process via MI module
    engine->tides_generator.Render(/* ... */);

    // 7. Deinterleave from MI render buffer to per-channel buffers
    for (int i = 0; i < num_frames; i++) {
        engine->tides_output_buffer[0][i] = render_buf[i].channel[0];
        // ...
    }

    // 8. Normalize + mix smooth
    constexpr float kTidesNorm = 0.125f;  // +/-8V -> +/-1V
    float coeff = smooth_coeff(sample_rate);
    for (int i = 0; i < num_frames; i++) {
        engine->tides_smooth_mix += coeff * (mix - engine->tides_smooth_mix);
        for (int ch = 0; ch < 4; ch++)
            engine->tides_output_buffer[ch][i] *= kTidesNorm * engine->tides_smooth_mix;
    }

    // 9. Copy to unit output ports
    std::memcpy(out0, engine->tides_output_buffer[0], num_frames * sizeof(float));

    // 10. Copy to source buffers (for Warps/Clouds routing)
    for (int ch = 0; ch < 4; ch++)
        std::memcpy(engine->warps_source_buffers[10+ch],
                    engine->tides_output_buffer[ch], num_frames * sizeof(float));

    // 11. Write visualization (peak per block per channel)
    for (int ch = 0; ch < 4; ch++) {
        float pk = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            float a = std::fabs(engine->tides_output_buffer[ch][i]);
            if (a > pk) pk = a;
        }
        engine->viz_rings[VIZ_TIDES_CH0 + ch].write(pk);
    }
}
```

## Normalization Constants

Each MI module outputs different voltage ranges. Normalize to +/-1V:

```cpp
constexpr float kTidesNorm  = 0.125f;   // +/-8V -> +/-1V
constexpr float kSynthNorm  = 1.0f/8.0f; // 8 voices averaged
constexpr float kDrumNorm   = 1.0f/3.0f; // 3 drum voices
```

**Rule**: After normalization, output should stay within [-1.1, +1.1]. Test with tight bounds (see `writing-dsp-tests` skill).

## Parameter Smoothing

All discontinuous parameter changes (knobs, MIDI CC) need click-free smoothing:

```cpp
// ~5ms exponential ramp (from orpheus_units_common.h)
inline float smooth_coeff(float sample_rate) {
    return 1.0f - std::exp(-1.0f / (0.005f * sample_rate));
}

// Applied per-sample in process function:
engine->tides_smooth_mix += coeff * (target - engine->tides_smooth_mix);
```

## Source Buffer Registration (Warps Routing)

**File**: `orpheus_engine.h`

```cpp
static constexpr int kNumWarpsSources = 14;
// 0=SYNTH, 1=DRUMS, 2=REPL, 3=LFO, 4=RESONATOR, 5=WARPS(fb),
// 6=FLUX, 7=BENDER, 8=STRINGS, 9=BASS, 10-13=TIDES ch0-3
float warps_source_buffers[kNumWarpsSources][kMaxFrames] = {};
```

To add a new source:
1. Increment `kNumWarpsSources`
2. Add slot comment
3. Copy output to slot in process function
4. Zero slot in bypass path
5. Add to Kotlin `WarpSourceType` enum

## Visualization Ring Buffers

**File**: `liborpheus_dsp/src/orpheus_viz.h`

```cpp
struct VizRing {
    static constexpr int kVizBufSize = 480;  // ~5sec at 94 writes/sec
    float buf[kVizBufSize] = {};
    std::atomic<uint32_t> write_count{0};

    inline void write(float value) {
        uint32_t wc = write_count.load(std::memory_order_relaxed);
        buf[wc % kVizBufSize] = value;
        write_count.store(wc + 1, std::memory_order_release);
    }
};

enum VizChannel {
    // ... existing channels ...
    VIZ_TIDES_CH0 = 25,
    VIZ_TIDES_CH1 = 26,
    VIZ_CHANNEL_COUNT  // must be last
};
```

To add a new viz channel:
1. Add enum value before `VIZ_CHANNEL_COUNT`
2. Write peak-per-block in process function
3. Zero in bypass path
4. Add `StateFlow<FloatArray>` to `SynthEngine` interface
5. Add to `SignalMonitorViz` channels + allData lists (see `panel-viewmodel-feature` skill)

## Graph Wiring (DefaultWiringGraph.kt)

**File**: `core/dsp-engine/src/commonMain/kotlin/.../DefaultWiringGraph.kt`

```kotlin
fun buildDefaultWiringGraph(): ByteArray = wiringGraph {
    val tidesUnit = tides("tides")

    // Wire connections
    clock.outRight to tidesUnit.inputA  // beat pulse

    // Port map (Kotlin symbol -> graph port)
    portMap {
        map("org.balch.orpheus.plugins.tides", "frequency", "tides", IPORT_FREQUENCY)
    }
}
```

After changing wiring, regenerate the binary graph:
```bash
./gradlew :core:dsp-engine:jvmTest --tests "*ExportOdwgTest*"
```
CMake auto-runs this when building tests.

## New DSP Module Checklist

- [ ] Engine atomics in `orpheus_engine.h` (with matching Kotlin defaults)
- [ ] Engine buffers (output, smoothing state, MI module instance)
- [ ] Unit type in `OrpheusUnitType` enum (`orpheus_units.h`)
- [ ] Process function declared (`orpheus_units.h`) and implemented
- [ ] Dispatch case in `orpheus_graph.cpp` switch
- [ ] Init case in `unit_init()` if needed
- [ ] `set_port` routing in `orpheus_engine_routing.cpp`
- [ ] Normalization constant calibrated and documented
- [ ] Self-bypass zeros ALL outputs (ports + engine buffers + source buffers + viz)
- [ ] Parameter smoothing on mix and any discontinuous controls
- [ ] Source buffer slots registered (if routable to Warps/Clouds)
- [ ] Viz channels added to enum + written in process function
- [ ] Graph wiring in `DefaultWiringGraph.kt` + ODWG re-exported
- [ ] Tests per `writing-dsp-tests` skill (bounds, modulation impact, bypass, DC offset)

## Common Mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Atomic default doesn't match Kotlin Plugin default | Startup glitch, wrong initial value | Sync defaults across C++ atomic, Kotlin Plugin, and ViewModel UiState |
| Missing bypass zeroing of source buffers | Ghost signal in Warps when module is off | Zero `warps_source_buffers[slot]` in bypass path |
| Missing bypass zeroing of viz rings | Oscilloscope shows stale signal | `viz_rings[ch].write(0.0f)` in bypass |
| No parameter smoothing on mix | Click/pop when toggling module | Use `smooth_coeff()` per-sample ramp |
| Normalization too loose (e.g., [-5,+5]) | Destroys downstream pitch/timbre | Calibrate to [-1.1, +1.1] post-norm |
| Reading atomics inside sample loop | Unnecessary overhead | Read once per block into local vars |
| Unconditional modulation routing | Module affects others without user consent | Gate by explicit mod source selector |
| Hash string mismatch with Kotlin symbol | `set_port` silently drops parameter | Match `engine_hash16("symbol")` to `PortSymbol.symbol` exactly |
| Forgot to re-export ODWG after wiring change | Tests use stale graph | Run `./gradlew :core:dsp-engine:jvmTest --tests "*ExportOdwgTest*"` |
