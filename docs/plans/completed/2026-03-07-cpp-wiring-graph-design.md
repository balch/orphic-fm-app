# C++ DSP Wiring Graph Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the hardcoded procedural C++ DSP engine with a graph-based scheduler driven by a Kotlin DSL, enabling engine 0 (oscillator mode), quad holds, envelopes, and full effects chain parity with the Kotlin DSP path.

**Architecture:** Kotlin DSL builds a wiring graph description, serializes to a compact binary descriptor, sends to C++ via a single JNI call. C++ parses, allocates units, wires connections, runs Tarjan SCC for topological sort, and executes the graph each audio callback. Graph topology is immutable at runtime; parameters are mutable.

**Tech Stack:** C++ (liborpheus_dsp), Kotlin DSL (core/foundation), JNI bridge (existing)

---

## Terminology

- **WiringGraph** — topology: which DSP units exist and how they're connected. Loaded once at startup or on topology change.
- **Preset** — parameter values applied to an existing wiring graph. Loaded frequently. In the future, a preset may reference a specific wiring graph.

## Binary Descriptor Format

Little-endian. Sent as `ByteArray` via `nativeLoadGraph()`.

```
Header (12 bytes):
  [4] magic: "ODWG"
  [2] version: 1
  [2] unit_count
  [2] connection_count
  [2] param_count (total across all units)

Units section (variable):
  Per unit:
    [2] unit_type (enum)
    [2] unit_id (unique, 0-based)
    [2] param_count (for this unit)
    Per param:
      [2] param_key (enum)
      [4] param_value (float)

Connections section (8 bytes each):
  Per connection:
    [2] src_unit_id
    [2] src_port (enum)
    [2] dst_unit_id
    [2] dst_port (enum)

Port map section (6 bytes each):
  [2] map_entry_count
  Per entry:
    [2] uri_hash (16-bit hash of plugin URI)
    [2] symbol_hash (16-bit hash of symbol string)
    [2] target_unit_id
    [2] target_port
```

Total size: ~4-8KB for the full 12-voice + effects graph.

## Unit Type Enum

```
TRIANGLE_OSC = 0
SQUARE_OSC = 1
MULTIPLY = 2
ADD = 3
MULTIPLY_ADD = 4
ENVELOPE = 5
LINEAR_RAMP = 6
PASS_THROUGH = 7
PEAK_FOLLOWER = 8
HARD_CLIP = 9
LIMITER = 10
PLAITS = 11
CLOUDS = 12
RINGS = 13
WARPS = 14
DELAY_LINE = 15
REVERB = 16
MASTER_OUT = 17
```

## Port Enums

```
// Output ports
OUT = 0, OUT_RIGHT = 1, AUX = 2

// Input ports
INPUT = 0, INPUT_A = 1, INPUT_B = 2, INPUT_C = 3
FREQUENCY = 4, AMPLITUDE = 5, GATE = 6, TIME = 7
DRIVE = 8, TRIGGER = 9
```

## C++ Graph Runtime

### Data Structures

```cpp
struct DspPort {
    float* buffer;       // pre-allocated, size = max_frames
    int num_sources;     // how many outputs feed this input
    float constant;      // used when num_sources == 0
};

struct DspUnit {
    uint16_t type;       // unit type enum
    uint16_t id;         // unique ID from descriptor
    bool enabled;        // toggled at runtime (no graph mutation)
    DspPort inputs[4];   // max 4 inputs per unit
    DspPort outputs[3];  // max 3 outputs (OUT, OUT_RIGHT, AUX)
    void* state;         // unit-specific state (phase, envelope, etc.)
};
```

### Execution Model

1. **`nativeLoadGraph(bytes)`** — parse descriptor, allocate units into flat array, wire input buffers to output buffers via pointer assignment, run Tarjan SCC for topological sort, store execution order.
2. **`orpheus_engine_process(num_frames)`** — iterate units in topological order, call per-type process function, MASTER_OUT writes to the interleaved output buffer.
3. **Parameters** changed at runtime via `nativeSetPort(uri, symbol, value)` — hashes URI+symbol, looks up unit_id+port in the port map table, sets `DspPort.constant` or unit state. No graph mutation.

### Feedback Handling (Tarjan SCC)

- Self-feedback (osc -> feedbackScaler -> FM mixer -> osc) creates cycles.
- Tarjan groups cycles into SCCs, processes units in insertion order within each SCC.
- One-sample-delay semantics for feedback paths — same as Kotlin.

### Input Port Behavior

- **Zero sources:** Fill from `constant` value, with optional one-pole smoothing (~5ms) for control-rate ports.
- **One source:** Direct buffer pointer (zero-copy).
- **Multiple sources:** Sum source buffers during `prepare()`.
- **Smoothing:** Determined by unit type + port index (hardcoded table). Gates/triggers jump immediately.

### Memory Model

- All unit state + buffers allocated once in a contiguous arena at `loadGraph()` time.
- Zero allocation during `process()` — fully real-time safe.
- Old graph freed after new graph is live (double-buffer swap).

## Kotlin DSL

```kotlin
val graph = wiringGraph {
    repeat(12) { v ->
        val triOsc = triangleOsc("voice_${v}_tri")
        val sqOsc = squareOsc("voice_${v}_sq")
        val sharpInv = multiplyAdd("voice_${v}_sharpInv") {
            inputB set -1f; inputC set 1f
        }
        val triGain = multiply("voice_${v}_triGain")
        val sqGain = multiply("voice_${v}_sqGain")
        val oscMix = add("voice_${v}_oscMix")
        val env = envelope("voice_${v}_env") {
            attack set 0.01f; release set 0.3f
        }
        val vca = multiply("voice_${v}_vca")
        val plaits = plaits("voice_${v}_plaits")
        val oscGain = multiply("voice_${v}_oscGain") { inputB set 1f }
        val plaitsGain = multiply("voice_${v}_plaitsGain") { inputB set 0f }
        val source = add("voice_${v}_source")
        val holdRamp = linearRamp("voice_${v}_holdRamp") { time set 0.02f }
        val vcaControl = add("voice_${v}_vcaControl")
        val wobbleGain = multiply("voice_${v}_wobble") { inputB set 1f }
        val volumeGain = multiply("voice_${v}_volume") { inputB set 1f }

        // Wiring (abbreviated — full voice graph)
        triOsc.out to triGain.inputA
        sharpInv.out to triGain.inputB
        sqOsc.out to sqGain.inputA
        oscMix.out to oscGain.inputA
        plaits.out to plaitsGain.inputA
        oscGain.out to source.inputA
        plaitsGain.out to source.inputB
        source.out to vca.inputA
        env.out to vcaControl.inputA
        holdRamp.out to vcaControl.inputB
        vcaControl.out to vca.inputB
        vca.out to wobbleGain.inputA
        wobbleGain.out to volumeGain.inputA
    }

    // Effects chain
    val drive = limiter("drive")
    val clouds = clouds("grains")
    val rings = rings("resonator")
    val warps = warps("warps")
    val delay = delayLine("delay")
    val reverb = reverb("reverb")
    val masterClipL = hardClip("master_clip_l")
    val masterClipR = hardClip("master_clip_r")
    val master = masterOut("master")

    // Voice outputs -> pan -> stereo sum -> effects -> master
    // (full wiring in implementation)

    // Port map: maps (uri, symbol) -> (unit_id, port)
    portMap {
        map("org.balch.orpheus.plugins.voice", "tune_0",
            "voice_0_tri", FREQUENCY)
        map("org.balch.orpheus.plugins.distortion", "drive",
            "drive", DRIVE)
        // ... generated by DSL from unit names + conventions
    }
}

nativeBridge.nativeLoadGraph(graph.serialize())
```

### DSL Design Points

- **`ref("name")`** refers to a unit created earlier by string ID. Resolved to integer unit_id at serialize time.
- **`infix fun Port.to(other: Port)`** records connections. Validated at build time.
- **String IDs are Kotlin-only.** Binary descriptor uses integer unit_ids. C++ never sees strings except through the hash-based port map.
- **Port map** built automatically from unit naming conventions + explicit overrides. Enables existing `nativeSetPort(uri, symbol, value)` to keep working.

## Parameter Routing

`nativeSetPort(uri, symbol, value)` on the C++ side:
1. Hash uri and symbol (16-bit each)
2. Look up in port map table (built at loadGraph time)
3. Set the target unit's port constant or state field
4. O(1) average, linear scan on collision

Existing direct bridge methods (`nativeSetVoiceGate`, `nativeSetVoiceTune`, `nativeSetMasterVolume`, etc.) continue to work by mapping to unit_id + port internally.

## What Changes, What Stays

### Replaces

- `orpheus_engine_process()` — procedural render becomes graph scheduler
- `orpheus_engine_set_port()` — strcmp chain becomes hash table lookup
- All hardcoded effect processing (drive, clouds, rings, warps, delay, LFO, master clip)

### Stays the Same

- `OrpheusEngine` struct — MI processor instances owned by graph unit wrappers
- `VoiceParams` atomics — Plaits graph units read from these
- JNI bridge layer — `OboeAudioBridge`, `jni_bridge.cpp`, `OboeEngine` unchanged
- `NativeDspBridge` interface — adds one method: `nativeLoadGraph(ByteArray)`
- All ViewModels, plugins, UI — completely untouched
- `DspSynthEngine` — adds one call to `nativeLoadGraph()` at init

### New Files

| File | Purpose |
|------|---------|
| `liborpheus_dsp/src/orpheus_graph.h` | DspUnit, DspPort structs, type/port enums |
| `liborpheus_dsp/src/orpheus_graph.cpp` | Descriptor parser, Tarjan SCC, graph scheduler |
| `liborpheus_dsp/src/orpheus_units.h` | Unit state structs, process declarations |
| `liborpheus_dsp/src/orpheus_units.cpp` | Per-type process functions |
| `core/foundation/.../OrpheusWiringGraphDsl.kt` | Kotlin DSL builder + binary serializer |

### New JNI Surface

One method: `nativeLoadGraph(ByteArray)`.

## Unit Implementation Priority

### Day 1 (voice + effects parity)

**11 new primitives:**
- Triangle, Square (engine 0 waveforms)
- Multiply, Add, MultiplyAdd (gain, mix, FM)
- Envelope (ADSR), LinearRamp (antizipper), PassThrough
- PeakFollower (coupling, monitoring), HardClip (master clip), Limiter (drive)

**6 wrappers around existing C++ code:**
- Plaits (wraps existing voice render), Clouds (wraps GranularProcessor)
- Rings (wraps Part), Warps (wraps Modulator)
- DelayLine (wraps existing circular buffer), Reverb (Dattorro, MI source)

**1 special unit:**
- MasterOut (writes interleaved stereo to output buffer)

### Deferred

- Sine, Sawtooth oscillators (unused in current graphs)
- ClockUnit, AutomationPlayer (sequencing)
- Looper, Flux, Drum, TTS, SpeechEffects (standalone modules)
- Min, Max (unused in current wiring)

These can be added as new unit types later without graph system changes.

## Voice Instancing

No template concept in C++. The Kotlin DSL uses `repeat(12) { v -> ... }` to generate 12 voice sub-graphs. C++ sees a flat list of ~500 units with unique IDs. Simple, debuggable, one code path.

## Graph Lifecycle

1. App starts -> DspSynthEngine builds default wiring graph via Kotlin DSL
2. Serializes to ByteArray, calls `nativeLoadGraph(bytes)`
3. C++ parses, allocates arena, wires, sorts, swaps in new graph
4. Audio callback runs graph scheduler each frame
5. UI/MIDI/AI changes parameters via existing `nativeSetPort`/`nativeSetVoiceGate`/etc.
6. Preset load changes parameter values, not topology
7. Future: preset could trigger a new `nativeLoadGraph()` for alternate topologies
