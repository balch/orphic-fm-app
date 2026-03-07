# C++ DSP Parity Gap Analysis: JSyn vs C++ Engine

**Date**: 2026-03-07
**Goal**: Identify every wiring/parameter gap between the JSyn audio engine and the C++ graph engine so A/B comparisons are apples-to-apples.

## Critical Bug: Voice Tune Format Mismatch

**This is why pitch sounds wrong.**

| Path | Tune Format | Example: A3 (220Hz) |
|------|------------|---------------------|
| JSyn (DspVoiceManager) | 0..1 range: `55 * 2^(tune*4)` | tune = 0.5 |
| C++ (orpheus_units.cpp) | MIDI note: `440 * 2^((note-69)/12)` | note = 57 |

`DspSynthEngine.setVoiceTune()` passes the raw 0..1 value to `nativeSetVoiceTune()`. C++ interprets 0.5 as MIDI note 0.5 (~8Hz) instead of 220Hz.

**Fix**: Convert in `setVoiceTune()` before calling native bridge:
```kotlin
// tune 0..1 → MIDI note: 55Hz * 2^(tune*4) = 440 * 2^((note-69)/12)
// Solving: note = 12 * log2(55 * 2^(tune*4) / 440) + 69
//        = 12 * (log2(55/440) + tune*4) + 69
//        = 12 * log2(0.125) + 48*tune + 69
//        = -36 + 48*tune + 69 = 33 + 48*tune
val midiNote = 33f + tune * 48f
nativeBridge?.nativeSetVoiceTune(index, midiNote)
```
Note: quadPitch is NOT forwarded to C++ yet (see Gap #2).

---

## Gap Summary

| # | Gap | JSyn Handles | C++ Handles | Impact | Fix Complexity |
|---|-----|-------------|-------------|--------|----------------|
| 1 | **Tune format** | 0..1 range | MIDI note | Wrong pitch on all voices | Convert in bridge call |
| 2 | **Quad pitch** | Per-quad pitch offset | Not forwarded | Quad pitch offsets ignored | Forward converted note |
| 3 | **Pitch multiplier** | Per-voice (0.5x bass, 1x mid) | Not applied | Bass voices at wrong octave | Apply in conversion or C++ |
| 4 | **Per-voice volume** | Per-quad volume scaling | Graph `v*_vol` inputB=1.0 | All voices same volume | Add port map entries |
| 5 | **Reverb** | ReverbPlugin in chain | UNIT_REVERB not implemented | No reverb in C++ | Implement or skip |
| 6 | **Resonator routing** | Complex 4-input routing | Rings gets mono mix post-grains | Different excitation | Match routing |
| 7 | **Distortion chain** | Distortion after resonator | Drive before clouds | Different effect order | Reorder graph |
| 8 | **Delay routing** | Multiple sends (grains+dist+bender+warps) | Only warps→delay | Missing delay sends | Add connections |
| 9 | **Looper** | LooperPlugin in chain | Not in graph | No looper | Add or skip |
| 10 | **Bender/PerStringBender** | Audio synthesis + pitch bend | Bend is stub (no-op) | No pitch bend | Implement |
| 11 | **Voice coupling** | Partner envelope → pitch mod | Not implemented | Missing coupling effect | Low priority |
| 12 | **FM modulation** | Voice-to-voice cross-mod, LFO→FM | Not routed in graph | No FM between voices | Major feature gap |
| 13 | **Mod source routing** | LFO/FM/Flux → timbre/morph mod | Not implemented | Missing modulation | Major feature gap |
| 14 | **Flux CV** | Pitch CV + trigger to quads | Not forwarded | No Flux integration | Forward ports |
| 15 | **Warps source routing** | 7 carrier/modulator options | Fixed: resonator→warps | Static routing only | Add source switching |
| 16 | **Drum routing** | Bypass/chain mode, direct resonator | Not in graph | Drums go through full chain | Add bypass path |
| 17 | **Drive scaling** | 1.0 + amount*14 (1..15x) | 1.0 + v*4 (1..5x) | Drive range mismatch | Align scaling |
| 18 | **Master volume** | port map sets mvL/mvR inputB | Graph default 0.4 | Correct (0.8 * 0.5) | Verify values match |
| 19 | **Per-voice pan** | Dynamic via setPort | Baked into graph at build time | Can't change pan live | Add port map entries |
| 20 | **LFO→delay mod** | HyperLfo.output→Delay.lfoInput | No audio connection | No delay modulation | Wire in graph |

---

## Phase 1: Make A/B Comparable (voice pitch + basic effects)

These fixes will make the C++ engine produce comparable pitch and basic effects for preset comparison.

### 1.1 Fix Tune Conversion (Critical)

**File**: `DspSynthEngine.kt` line 671

The raw tune (0..1) must be converted to MIDI note AND include pitch multiplier + quad pitch.

JSyn frequency formula:
```
freq = 55 * 2^(tune*4) * pitchMultiplier * 2^((quadPitch-0.5)*2)
```

Where pitchMultiplier per voice:
- Voices 0,1: 0.5 (bass, -1 octave)
- Voices 2-11: 1.0

Equivalent MIDI note:
```
midiNote = 12 * log2(freq / 440) + 69
```

Combined: `midiNote = 33 + tune*48 + pitchMultiplierSemitones + quadPitchSemitones`

Where:
- pitchMultiplier 0.5 → -12 semitones, 1.0 → 0
- quadPitch offset → `(quadPitch - 0.5) * 24` semitones

**Implementation**:
```kotlin
override fun setVoiceTune(index: Int, tune: Float) {
    voiceManager.setVoiceTune(index, tune)
    nativeBridge?.nativeSetVoiceTune(index, computeMidiNote(index, tune))
}

private fun computeMidiNote(index: Int, tune: Float): Float {
    val baseMidi = 33f + tune * 48f  // 0..1 → A1..A5
    val quadIndex = index / 4
    val quadOffset = (voiceManager.getQuadPitch(quadIndex) - 0.5f) * 24f
    val pitchMult = voiceManager.getPitchMultiplier(index)
    val multSemitones = if (pitchMult < 1f) -12f else 0f  // 0.5x = -12 semitones
    return baseMidi + quadOffset + multSemitones
}
```

Also fix `setQuadPitch()` to re-sync all voices in that quad to C++.

### 1.2 Fix Drive Scaling

**JSyn**: `drive = 1.0 + amount * 14` (range 1..15)
**C++**: `drive = 1.0 + v * 4` (range 1..5)

Fix in `orpheus_engine.cpp` `orpheus_engine_set_drive()`:
```cpp
float scaled = 1.0f + v * 14.0f;  // Match JSyn range
engine->drive_amount.store(scaled, std::memory_order_relaxed);
```

### 1.3 Fix Effect Chain Order

**JSyn chain**: Voices → Grains → Resonator → Distortion → Delay → Reverb → Stereo
**C++ graph**: Voices → Drive → Clouds → Rings → Warps → Delay → Clip → Out

Fix `DefaultWiringGraph.kt` to match JSyn order:
```
Voices → summing → Clouds(grains) → Rings(resonator) → Drive(distortion) → Warps → Delay → Clip → Out
```
Move drive AFTER rings (currently before clouds).

### 1.4 Add Per-Voice Volume Port Map

Currently all `v*_vol` multiply nodes have inputB=1.0 (fixed). Need port map entries so `setPort("stereo", "voice_pan_N")` can adjust them, or add volume port map entries.

For basic comparison, per-quad volume can be wired:
```kotlin
portMap {
    // Quad 0 voices (0-3)
    for (v in 0..3) map("org.balch.orpheus.plugins.stereo", "quad_vol_0", "v${v}_vol", IPORT_INPUT_B)
    // Quad 1 voices (4-7)
    for (v in 4..7) map("org.balch.orpheus.plugins.stereo", "quad_vol_1", "v${v}_vol", IPORT_INPUT_B)
    // Quad 2 voices (8-11)
    for (v in 8..11) map("org.balch.orpheus.plugins.stereo", "quad_vol_2", "v${v}_vol", IPORT_INPUT_B)
}
```

---

## Phase 2: Effect Parity (match JSyn effect wiring)

### 2.1 Reverb
- UNIT_REVERB exists in enum but has no processor in `orpheus_units.cpp`
- Options: (a) Port a reverb algorithm (FreeVerb, Dattorro), (b) Skip for now
- JSyn uses a custom reverb plugin; for parity we need something comparable

### 2.2 Delay Sends
- JSyn sends grains+distortion+bender+warps all to delay
- C++ graph only sends warps→delay
- Fix: Add connections in DefaultWiringGraph from clouds and drive outputs to delay

### 2.3 Dynamic Pan
- Pan is baked at graph build time via constant-power gains
- Need runtime pan updates via port map entries for each voice's pL/pR multiply nodes
- Complex: pan changes require updating BOTH left and right gain nodes with cos/sin

### 2.4 LFO→Delay Modulation
- JSyn wires HyperLfo output to delay mod input
- C++ HyperLFO has no audio connections in graph (standalone monitoring only)
- Need: Connect LFO output to delay time modulation (requires adding mod input to UNIT_DUAL_DELAY)

---

## Phase 3: Modulation Parity (voice-to-voice FM, mod sources)

### 3.1 FM Cross-Modulation
- JSyn wires voice outputs back to other voices' FM inputs
- C++ has no inter-voice audio routing in graph
- Would require adding feedback connections or a dedicated FM bus unit

### 3.2 LFO/Flux Mod Sources
- JSyn routes LFO/Flux to voice FM depth, Plaits timbre, and Plaits morph
- C++ only has vibrato (LFO→pitch via engine atomic)
- Need: timbre_mod and morph_mod inputs on UNIT_PLAITS, routed from LFO/Flux

### 3.3 Voice Coupling
- JSyn routes partner envelope to pitch modulation
- Not in C++ at all
- Low priority for A/B comparison

---

## Phase 4: Full Feature Parity

- Looper (record/playback loop)
- Bender/PerStringBender (pitch bend audio synthesis)
- Warps source selection (7 carrier/modulator options)
- Drum bypass/chain mode switching
- Flux CV→voice pitch/trigger routing
- Speech engine prosody/speed parameters

---

## Recommended Execution Order

1. **Fix tune conversion** (Phase 1.1) — immediate, fixes the #1 audible issue
2. **Fix effect chain order** (Phase 1.3) — reorder graph nodes
3. **Fix drive scaling** (Phase 1.2) — one-line fix
4. **Add volume port map** (Phase 1.4) — enables quad volume control
5. **Reverb stub or impl** (Phase 2.1) — needed for any preset with reverb
6. **Document remaining gaps** — maintain this doc as migration checklist

After Phase 1, presets should sound recognizably similar between JSyn and C++, enabling meaningful A/B comparison even if effects aren't 100% identical yet.

---

## Test Methodology

For each preset:
1. Launch with `-Dorpheus.engine=jsyn` (default), load preset, play voices, note sound
2. Relaunch with `-Dorpheus.engine=cpp`, load same preset, play same voices
3. Compare: pitch (correct octave?), timbre (similar character?), effects (delay/reverb present?)
4. Log discrepancies per preset

Future (Phase 2 of design doc): Automated WAV snapshot comparison with RMS diff + spectral analysis.
