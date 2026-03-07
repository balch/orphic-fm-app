# Dynamic Voice Pan + Reverb: C++ DSP Parity Design

**Goal:** Add dynamic per-voice stereo panning and Dattorro plate reverb to the C++ graph engine, matching JSyn behavior for A/B comparison.

**Architecture:** Port existing Kotlin `DattorroReverb` algorithm to C++ as `UNIT_REVERB` graph unit. Extend port map for per-voice pan gains. Both changes are in shared code (commonMain + liborpheus_dsp) so Android and desktop both benefit.

---

## 1. Dynamic Voice Pan

### Current State
- Pan gains baked at graph build time in `DefaultWiringGraph.kt` (constant-power cos/sin)
- No port map entries for voice pan nodes — can't update at runtime
- JSyn `StereoPlugin` updates pan dynamically via `VOICE_PAN_0..11` ports

### Design
Each voice has two multiply nodes: `v*_pL` (left gain) and `v*_pR` (right gain). Pan value (-1..+1) maps to gains via constant-power law:
```
angle = ((pan + 1) / 2) * (PI / 2)
leftGain  = cos(angle)
rightGain = sin(angle)
```

**Port map entries** (24 total, in `DefaultWiringGraph.kt`):
```
For each voice v in 0..11:
  map("stereo", "voice_pan_L_v", "v{v}_pL", IPORT_INPUT_B)
  map("stereo", "voice_pan_R_v", "v{v}_pR", IPORT_INPUT_B)
```

**Forwarding** (in `DspSynthEngine.kt`):
When `setPluginPort("stereo", "voice_pan_N", value)` is called:
1. Forward to JSyn as normal
2. Compute cos/sin gains from the pan value
3. Call `nativeBridge.nativeSetPort("stereo", "voice_pan_L_N", leftGain)`
4. Call `nativeBridge.nativeSetPort("stereo", "voice_pan_R_N", rightGain)`

This keeps the cos/sin computation in Kotlin (matching JSyn exactly) and sends pre-computed gains to the C++ graph.

### Files Changed
- `DefaultWiringGraph.kt` — Add 24 port map entries
- `DspSynthEngine.kt` — Override pan forwarding to compute and send L/R gains

---

## 2. Reverb (Dattorro Plate)

### Algorithm
Dattorro plate reverb (Griesinger topology), matching the Kotlin `DattorroReverb.kt`:

```
Input → inputGain(0.5) → 4 cascaded allpass diffusers →
  ┌─ Path 1: AP1A → AP1B → DEL1(+LFO1) → LP → feedback ──┐
  │                                                          │
  └─ Path 2: AP2A → AP2B → DEL2(+LFO2) → LP → feedback ──┘
                                              (cross-coupled)
Output: wet-only stereo, scaled by amount parameter
```

**Delay line sizes** (at 48kHz, scaled by `sampleRate / 48000`):
- Input APs: 150, 214, 319, 527 samples
- Path 1: AP1A=2182, AP1B=2690, DEL1=4501, LFO tap offset=4460
- Path 2: AP2A=2525, AP2B=2197, DEL2=6312, LFO tap offset=6261
- Total buffer: 32768 floats (power-of-2 ring buffer)

**LFO modulation**: Two cosine oscillators (0.5Hz, 0.3Hz) updated every 32 samples, amplitude ~40-50 samples at 48kHz. Linear interpolation on modulated reads.

**Parameters** (via engine atomics):
| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| amount | 0-1 | 0.0 | Wet level (0=off, self-bypass at <=0.001) |
| time | 0-1 | 0.5 | Feedback/decay length |
| damping | 0-1 | 0.7 | LP coefficient (0=bright, 1=dark) |
| diffusion | 0-1 | 0.625 | Allpass coefficient |

### Signal Chain Wiring
**JSyn**: Distortion → Reverb (parallel send) → Stereo Sum

**C++ graph** (`DefaultWiringGraph.kt`):
```kotlin
val reverb = reverb("reverb")
driveL.out to reverb.inputA    // parallel send from drive
driveR.out to reverb.inputB
// Reverb wet output sums into clip inputs alongside delay output
reverb.out to clipL.input
reverb.outRight to clipR.input
```

Delay output already feeds clipL/clipR. Reverb output adds to the same inputs (auto-summed by port_prepare).

### C++ Implementation

**New file**: `orpheus_reverb.h` / `orpheus_reverb.cpp` (or inline in `orpheus_units.cpp`)

**State** (in `OrpheusEngine`):
```cpp
// Reverb state
float reverb_buffer[32768] = {};
int   reverb_write_pos = 0;
float reverb_lfo1_phase = 0.0f;
float reverb_lfo2_phase = 0.0f;
float reverb_lp1 = 0.0f;  // LP filter state path 1
float reverb_lp2 = 0.0f;  // LP filter state path 2
float reverb_tail1 = 0.0f; // feedback accumulator path 1
float reverb_tail2 = 0.0f; // feedback accumulator path 2

// Reverb parameters (atomics)
std::atomic<float> reverb_amount{0.0f};
std::atomic<float> reverb_time{0.5f};
std::atomic<float> reverb_damping{0.7f};
std::atomic<float> reverb_diffusion{0.625f};
std::atomic<int>   reverb_bypass{1};
```

**Processor**: `unit_process_reverb(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr)`
- Reads stereo input from `IPORT_INPUT_A` / `IPORT_INPUT_B`
- Writes stereo wet output to `OPORT_OUT` / `OPORT_OUT_RIGHT`
- Self-bypasses when amount <= 0.001

**Port routing** (in `orpheus_engine_set_port`):
```cpp
if (strcmp(plugin_uri, "org.balch.orpheus.plugins.reverb") == 0) {
    if (strcmp(symbol, "amount") == 0) {
        engine->reverb_amount.store(value);
        engine->reverb_bypass.store(value < 0.001f ? 1 : 0);
    }
    else if (strcmp(symbol, "time") == 0) engine->reverb_time.store(value);
    else if (strcmp(symbol, "damping") == 0) engine->reverb_damping.store(value);
    else if (strcmp(symbol, "diffusion") == 0) engine->reverb_diffusion.store(value);
}
```

### Files Changed
- `orpheus_engine.h` — Add reverb state and parameter atomics
- `orpheus_engine.cpp` — Add port routing for reverb params
- `orpheus_units.cpp` — Add `unit_process_reverb()` (Dattorro plate algorithm)
- `orpheus_units.h` — Declare `unit_process_reverb()`
- `orpheus_graph.cpp` — Add `case UNIT_REVERB` to process switch
- `DefaultWiringGraph.kt` — Add reverb unit + wiring
- `WiringGraphDsl.kt` — Add `reverb()` builder function (if not already present)

---

## 3. Testing

- Build C++ library, verify compilation
- Build Kotlin, verify graph descriptor generation
- Run with `-Dorpheus.engine=cpp`:
  - Dynamic pan: change voice pan knobs, verify stereo image shifts
  - Reverb: enable reverb (amount > 0), verify audible reverb tail
  - A/B with JSyn: compare reverb character and pan positions

---

## 4. Implementation Order

1. **Dynamic pan** (simpler, fewer files) — port map entries + Kotlin forwarding
2. **Reverb unit** (C++ algorithm port) — engine state + processor + graph wiring
3. **Integration test** — verify both in running app
