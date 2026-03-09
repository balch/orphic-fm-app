# Separate Drum Voices + Quad Hold Tests Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Separate drum voices from REPL/Quad 2 voices to match Kotlin architecture, add quad volume/hold tests.

**Architecture:** Grow C++ engine from 12 to 15 voices (12 main + 3 drums). Add 3 dedicated drum plaits units to ODWG graph with per-drum volume/pan. REPL keeps fixed voices 8-11 (Quad 2).

## Voice Layout

```
Current:  voices[0-7] main (Quad 0+1) | voices[8-11] REPL/drums shared (Quad 2)
Proposed: voices[0-11] main (Quad 0+1+2) | voices[12-14] drums (separate)
```

C++ constants:
- `kNumMainVoices = 12` (was 8)
- `kNumDrumVoices = 3` (new)
- `kNumVoices = 15` (was 12)
- `kDrumVoiceStart = 12` (new)
- Remove `kNumReplVoices`

## ODWG Graph Changes (`DefaultWiringGraph.kt`)

Add 3 drum plaits units with their own volume/pan chain:

```
d0_p (plaits, engine=21) -> d0_vol (multiply, inputB=1.2) -> d0_pL/d0_pR -> summing tree
d1_p (plaits, engine=22) -> d1_vol (multiply, inputB=0.6) -> d1_pL/d1_pR -> summing tree
d2_p (plaits, engine=23) -> d2_vol (multiply, inputB=0.5) -> d2_pL/d2_pR -> summing tree
```

- `inputB` defaults match Kotlin `SLOT_GAINS = [1.2, 0.6, 0.5]`
- Default pan: all center (0.0)
- GRIDS rewired to d0/d1/d2 gates (instead of v8/v9/v10)
- Drum voice outputs feed into existing summing tree
- Port map entries for per-drum volume and pan

## C++ Engine Changes

**`orpheus_engine.h`:** Update constants, all arrays grow to 15 automatically.

**`orpheus_engine.cpp`:**
- `orpheus_engine_create`: Pan defaults for 0-11 (existing), 12-14 center
- `orpheus_engine_trigger_drum`: Use `kDrumVoiceStart + drum_index`, clamp to `kNumDrumVoices`

**`orpheus_units.cpp`:**
- Drum plaits units use `moduleIndex = 12/13/14` mapping to new voice slots
- Graph gate override logic unchanged (already generic per-voice)

## Tests

**Quad volume test:**
- `quad_vol_0 = 0.5` via set_port, verify voices 0-3 ~half level of voices 4-7
- `quad_vol_2 = 0.0`, verify voices 8-11 silent
- Verify no cross-quad bleed

**Quad hold test:**
- Set voice_hold on all 4 voices in a quad, verify signal without gate
- Hold level scales output (0.5 vs 1.0)
- Hold=0 + no gate = silence

**Drum voice isolation:**
- Trigger drums on voices 12-14
- Verify relative levels match SLOT_GAINS (kick > snare > hat)
- Verify main voices 8-11 unaffected by drum triggers

**Full-chain headroom:**
- 15-voice scenario: 12 main + 3 drums
- Verify no clipping, all voice_levels active
