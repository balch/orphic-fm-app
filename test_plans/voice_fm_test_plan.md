# Voice & FM Synthesis Test Plan

## Overview

Songe-8 features an 8-voice FM synthesis system organized into 4 Duo pairs within 2 Quads. Each
voice has independent tuning, envelope speed, and modulation depth, with sophisticated routing
options.

## Architecture

- **8 Voices** organized as:
    - **Quad 1-4:** Voices 1,2,3,4 (bass/mid range)
    - **Quad 5-8:** Voices 5,6,7,8 (mid/high range)
- **4 Duo Pairs:** 1-2, 3-4, 5-6, 7-8
- **Per-Voice Controls:** Tune, Envelope Speed, Hold
- **Per-Duo Controls:** FM Depth (Mod), Sharpness, Mod Source
- **Per-Quad Controls:** Pitch offset, Hold level
- **FM Routing:** Within-pair or Cross-quad

## Test Cases

### Test 1: Basic Voice Triggering

**Objective:** Verify all 8 voices can be triggered independently

**Procedure:**

1. Click each voice button (1-8) individually
2. Listen for sound output
3. Verify each voice has distinct pitch

**Expected Results:**

- ✓ All 8 voices produce sound
- ✓ Default F# minor scale audible
- ✓ Clean attack, no clicks
- ✓ Voice stops when released (if hold is off)

**Pass/Fail:** ____

---

### Test 2: Hold Function

**Objective:** Verify voice latch/hold behavior

**Procedure:**

1. Click voice 1 hold button (should light up)
2. Trigger voice 1 (should stay on)
3. Release trigger
4. Voice should continue playing
5. Click hold again to unlatch

**Expected Results:**

- ✓ Hold button lights when active
- ✓ Voice sustains after trigger release
- ✓ Hold toggle turns off voice
- ✓ Works for all 8 voices

**Pass/Fail:** ____

---

### Test 3: Voice Tuning

**Objective:** Verify tune knob controls pitch across full range

**Procedure:**

1. Trigger and hold voice 1
2. Sweep TUNE knob from 0% to 100%
3. Listen to pitch change

**Expected Results:**

- ✓ Pitch sweeps from low (55 Hz) to high (880 Hz)
- ✓ Approximately 4 octaves range
- ✓ Smooth pitch transitions
- ✓ No glitches or dropouts
- ✓ Tuning is stable at all positions

**Pass/Fail:** ____

---

### Test 4: Envelope Speed Control

**Objective:** Verify envelope slider affects attack/release speed

**Setup:**

1. Voice 1 hold OFF

**Procedure:**

1. Set envelope speed to 0% (fast)
    - Trigger voice: should attack/release quickly
2. Set envelope speed to 100% (slow)
    - Trigger voice: should attack/release slowly
3. Test at various positions

**Expected Results:**

- ✓ 0%: Snappy, percussive (fast attack/release)
- ✓ 100%: Slow fade in/out (slow attack/release)
- ✓ Continuous range between extremes
- ✓ No pops or clicks at any speed

**Pass/Fail:** ____

---

### Test 5: Duo FM Depth (Within-Pair)

**Objective:** Verify FM modulation between voices in a pair

**Setup:**

1. Duo 1-2, set mod source to **FM** (bottom position)
2. Tune voice 1 and 2 close together

**Procedure:**

1. Trigger both voices
2. Set MOD knob to 0%: Should hear two pure tones
3. Increase MOD to 50%: Timbre should change
4. Increase MOD to 100%: Strong FM effect

**Expected Results:**

- ✓ 0%: Clean, separate tones
- ✓ 50%: Moderate FM complexity
- ✓ 100%: Heavy FM, rich harmonics
- ✓ Timbre changes smoothly with MOD
- ✓ Voice 1 modulates voice 2, and vice versa

**Pass/Fail:** ____

---

### Test 6: Sharpness Control

**Objective:** Verify sharpness morphs waveform from triangle to square

**Setup:**

1. Trigger voice 2 (even numbered voice)
2. Listen to basic tone

**Procedure:**

1. Set SHARP to 0%: Triangle wave
2. Set SHARP to 50%: Mixed waveform
3. Set SHARP to 100%: Square wave

**Expected Results:**

- ✓ 0%: Warm, smooth triangle tone
- ✓ 50%: Brighter, intermediate
- ✓ 100%: Buzzy, bright square wave
- ✓ Continuous morphing
- ✓ No discontinuities

**Pass/Fail:** ____

---

### Test 7: Duo Mod Source - OFF

**Objective:** Verify OFF disables modulation

**Procedure:**

1. Set duo mod source to **OFF** (center)
2. Set MOD depth to 100% (should have no effect)
3. Trigger voices

**Expected Results:**

- ✓ Clean, unmodulated tones
- ✓ MOD knob has no effect
- ✓ Voices remain independent

**Pass/Fail:** ____

---

### Test 8: Duo Mod Source - LFO

**Objective:** Verify LFO modulates FM depth

**Setup:**

1. Set duo mod source to **LFO** (top)
2. Set HyperLFO to moderate rate (30%)
3. Set MOD depth to 50%

**Procedure:**

1. Trigger duo pair
2. Listen for rhythmic timbre changes

**Expected Results:**

- ✓ Timbre modulates rhythmically
- ✓ Follows HyperLFO rate
- ✓ Effect is musical and controlled
- ✓ No audio glitches

**Pass/Fail:** ____

---

### Test 9: Quad Pitch Control

**Objective:** Verify quad pitch offset affects all 4 voices

**Setup:**

1. Trigger all voices in Quad 1-4 (voices 1,2,3,4)
2. Listen to chord

**Procedure:**

1. Set Quad 1-4 PITCH to 25% (down 1 octave)
2. Set Quad 1-4 PITCH to 75% (up 1 octave)
3. Set back to 50% (unity)

**Expected Results:**

- ✓ 25%: Whole chord shifts down
- ✓ 75%: Whole chord shifts up
- ✓ 50%: Original tuning
- ✓ Relative pitches between voices maintained
- ✓ Smooth pitch transitions

**Pass/Fail:** ____

---

### Test 10: Quad Hold Level

**Objective:** Verify quad hold affects envelope sustain

**Setup:**

1. Set all voices in quad with hold ON
2. Trigger all 4 voices

**Procedure:**

1. Set Quad HOLD to 0%: Voices silent
2. Set Quad HOLD to 50%: Medium level
3. Set Quad HOLD to 100%: Full level

**Expected Results:**

- ✓ Controls overall sustain level
- ✓ 0%: Silent even with hold on
- ✓ 100%: Full sustain level
- ✓ Affects all 4 voices in quad

**Pass/Fail:** ____

---

### Test 11: Cross-Quad FM Routing

**Objective:** Verify cross-quad modulation structure

**Setup:**

1. Enable cross-quad toggle (in center panel)
2. Set duo mod sources to **FM**

**Procedure:**

1. Trigger duo 1-2 and duo 3-4
2. Listen for complex FM interactions

**Expected Results:**

**Cross-Quad Routing:**

- ✓ Duo 1 (voices 1-2) receives from Duo 4 (voices 7-8)
- ✓ Duo 2 (voices 3-4) modulates within pair
- ✓ Duo 3 (voices 5-6) receives from Duo 2 (voices 3-4)
- ✓ Duo 4 (voices 7-8) modulates within pair
- ✓ Creates more complex timbres than within-pair

**Pass/Fail:** ____

---

### Test 12: Polyphony Test

**Objective:** Verify all 8 voices can play simultaneously

**Procedure:**

1. Enable hold on all 8 voices
2. Trigger all 8 voices rapidly
3. Listen to full 8-voice chord

**Expected Results:**

- ✓ All 8 voices audible
- ✓ No voice stealing
- ✓ Clean mix, no distortion
- ✓ Stable CPU load
- ✓ All controls remain responsive

**Pass/Fail:** ____

---

### Test 13: Keyboard Octave Shift

**Objective:** Verify keyboard octave controls (Z/X keys)

**Procedure:**

1. Press Z key repeatedly (octave down)
2. Trigger voices with keyboard (A-K keys)
3. Press X key repeatedly (octave up)
4. Trigger voices again

**Expected Results:**

- ✓ Z lowers pitch range
- ✓ X raises pitch range
- ✓ Keyboard triggers remain consistent
- ✓ Multiple octaves available

**Pass/Fail:** ____

---

### Test 14: Keyboard Tune Adjustment

**Objective:** Verify number keys (1-8) adjust voice tuning

**Procedure:**

1. Press 1 key repeatedly (adjust voice 1 tune)
2. Hold Shift+1 for fine adjustment
3. Test with other number keys

**Expected Results:**

- ✓ Number keys adjust corresponding voice
- ✓ Shift provides fine adjustment
- ✓ Works for all 8 voices (keys 1-8)
- ✓ Visual feedback in UI

**Pass/Fail:** ____

---

### Test 15: Vibrato (Global Pitch Wobble)

**Objective:** Verify vibrato affects all voices

**Setup:**

1. Trigger multiple voices

**Procedure:**

1. Set VIBRATO knob (center panel) to 50%
2. Listen for pitch wobble

**Expected Results:**

- ✓ All voices wobble together
- ✓ ~5 Hz sine wave modulation
- ✓ Affects all 8 voices equally
- ✓ Smooth, musical vibrato

**Pass/Fail:** ____

---

### Test 16: Voice Coupling

**Objective:** Verify voice coupling (partner envelope → frequency)

**Setup:**

1. Set COUPLE knob to 50%
2. Trigger voice 1 alone

**Procedure:**

1. Trigger voice 2 (partner in pair)
2. Listen to voice 1 pitch modulation

**Expected Results:**

- ✓ Voice 1 pitch bends with voice 2 envelope
- ✓ Vice versa (2 modulates 1)
- ✓ Coupling depth controlled by COUPLE knob
- ✓ Creates organic pitch interactions

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Complex FM Patch

**Objective:** Create a complex multilayered FM sound

**Procedure:**

1. All 8 voices active with holds
2. Various tunings creating chords
3. Mix of mod sources (OFF, LFO, FM)
4. Cross-quad enabled
5. Sharpness varied per pair
6. Different envelope speeds

**Expected Results:**

- ✓ Rich, evolving timbre
- ✓ System remains stable
- ✓ CPU load acceptable
- ✓ All controls remain responsive

**Pass/Fail:** ____

---

### INT-2: Preset Recall

**Objective:** Verify voices load correctly from presets

**Procedure:**

1. Load various factory presets
2. Verify voice states match preset

**Expected Results:**

- ✓ All voice parameters restored
- ✓ Tunings correct
- ✓ Mod sources correct
- ✓ Sounds match preset intent

**Pass/Fail:** ____

---

## Parameter Reference

| Parameter      | Scope     | Range  | Function                         |
|----------------|-----------|--------|----------------------------------|
| Tune           | Per-Voice | 0-100% | Frequency (55-880 Hz, 4 octaves) |
| Envelope Speed | Per-Voice | 0-100% | Attack/Release time              |
| Hold           | Per-Voice | Toggle | Latch voice on                   |
| MOD            | Per-Duo   | 0-100% | FM depth                         |
| Sharpness      | Per-Duo   | 0-100% | Triangle→Square morph            |
| Mod Source     | Per-Duo   | 3-way  | OFF / LFO / FM                   |
| Pitch          | Per-Quad  | 0-100% | Pitch offset (-1 to +1 octave)   |
| Hold Level     | Per-Quad  | 0-100% | Sustain amplitude                |

## Known Issues

None currently.

## Test Summary

**Total Tests:** 18  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
