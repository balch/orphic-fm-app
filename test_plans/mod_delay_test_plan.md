# Mod Delay Test Plan

## Overview

The Mod Delay system features dual independent delay lines with multiple modulation sources (LFO or
self-modulation), providing chorus, flanger, and tape delay effects.

## Architecture

- **2 Independent Delay Lines** with separate time and modulation controls
- **Modulation Sources:**
    - **HyperLFO:** Rhythmic, predictable modulation (triangle or square wave)
    - **Self-Modulation:** Delay output modulates its own time (chaotic, organic)
- **Unipolar LFO Conversion:** Prevents negative delay times (bug fix applied)
- **Dual Feedback Loops:** Each delay has independent feedback
- **Dry/Wet Mix:** Parallel processing with master mix control

## Test Cases

### Test 1: Basic Delay Function

**Objective:** Verify delay lines produce audible echoes at correct timing

**Setup:**

1. Trigger and hold Voice 1
2. Set all MOD knobs to 0
3. Set FB (feedback) to 40%
4. Set MIX to 50%

**Procedure:**

1. Set TIME 1 to 30% (~0.6s)
2. Set TIME 2 to 50% (~1.0s)
3. Play short pulse on voice

**Expected Results:**

- ✓ Hear two distinct echo taps
- ✓ First echo at ~0.6s
- ✓ Second echo at ~1.0s
- ✓ Echoes fade gradually (feedback)
- ✓ No distortion or clicks

**Pass/Fail:** ____

---

### Test 2: LFO Modulation (Triangle Wave)

**Objective:** Verify smooth chorus/flanger effect with triangle LFO

**Setup:**

1. Continue from Test 1
2. Set SELF/LFO toggle to **LFO** (bottom)
3. Set TRI/SQR toggle to **TRI** (top)

**Procedure:**

1. In HyperLFO panel:
    - Set LFO A to 20% (~2 Hz)
    - Set LFO B to 30% (~3 Hz)
    - Set mode to **AND** (left)
2. Slowly increase MOD 1 to 50%
3. Slowly increase MOD 2 to 50%

**Expected Results:**

- ✓ Delay times wobble smoothly
- ✓ Pitch shifts up/down with modulation
- ✓ Chorus/shimmer effect audible
- ✓ No audio dropouts or glitches
- ✓ Modulation affects both delays independently
- ✓ AND mode creates intermittent modulation

**Pass/Fail:** ____

---

### Test 3: Square Wave Modulation

**Objective:** Verify rhythmic stepped modulation with square waves

**Setup:**

1. Continue from Test 2 with modulation active

**Procedure:**

1. Toggle TRI/SQR to **SQR** (square wave)
2. Listen for change in character

**Expected Results:**

- ✓ Modulation becomes stepped/abrupt
- ✓ Two distinct delay times alternate
- ✓ More robotic/electronic character
- ✓ No clicks at transitions (important!)
- ✓ Rhythm matches LFO rates

**Pass/Fail:** ____

---

### Test 4: AND vs OR Mode Comparison

**Objective:** Verify MIN/MAX logic for triangle waves

**Setup:**

1. Set TRI/SQR back to **TRI**
2. Ensure MOD 1/2 are at 50%

**Procedure:**

1. Set HyperLFO to **AND** mode
    - Listen for 30 seconds
2. Set HyperLFO to **OR** mode
    - Listen for 30 seconds
3. Compare character

**Expected Results:**

**AND Mode (MIN):**

- ✓ Sparse, intermittent modulation
- ✓ More "breathing room"
- ✓ Follows lower of two LFOs

**OR Mode (MAX):**

- ✓ Continuous, fluid modulation
- ✓ Fuller presence
- ✓ Follows higher of two LFOs

**Pass/Fail:** ____

---

### Test 5: Self-Modulation

**Objective:** Verify feedback-based modulation creates organic textures

**Setup:**

1. Set SELF/LFO toggle to **SELF** (top)
2. Increase FB to 60%

**Procedure:**

1. Set MOD 1 to 60%
2. Set MOD 2 to 70%
3. Hold a voice and listen
4. Try varying feedback amount

**Expected Results:**

- ✓ Unpredictable, evolving textures
- ✓ Metallic/resonant character
- ✓ More chaotic than LFO mode
- ✓ Can create pitched tones at certain settings
- ✓ No runaway feedback causing clipping
- ✓ Stable at high feedback levels

**Pass/Fail:** ____

---

### Test 6: Extreme Settings - Flanger Effect

**Objective:** Verify system handles short delays with heavy modulation

**Procedure:**

1. Set TIME 1 to 5% (~0.1s)
2. Set TIME 2 to 10% (~0.2s)
3. Set MOD 1 to 80%
4. Set MOD 2 to 80%
5. Set LFO mode, rates at 50-70%
6. Set FB to 40%

**Expected Results:**

- ✓ Classic jet plane flanger sweep
- ✓ No audio glitches or dropouts
- ✓ No negative delay artifacts (critical!)
- ✓ Smooth swooshing sound
- ✓ Remains stable

**Pass/Fail:** ____

---

### Test 7: Extreme Settings - Long Delays

**Objective:** Verify maximum delay time functionality

**Procedure:**

1. Set TIME 1 to 100% (2.0s)
2. Set TIME 2 to 100% (2.0s)
3. Set FB to 80%
4. Play short pulses

**Expected Results:**

- ✓ Delays reach 2 seconds
- ✓ Long feedback tail (10+ seconds)
- ✓ No buffer overflow artifacts
- ✓ Audio quality maintained
- ✓ Memory remains stable

**Pass/Fail:** ____

---

### Test 8: Mix Control

**Objective:** Verify dry/wet balance

**Procedure:**

1. Set moderate delay settings
2. Sweep MIX from 0% to 100%

**Expected Results:**

- ✓ 0%: Only dry signal (no delay)
- ✓ 50%: Equal dry/wet balance
- ✓ 100%: Only wet signal (no dry)
- ✓ Smooth transition, no clicks
- ✓ Total level remains consistent

**Pass/Fail:** ____

---

### Test 9: Independent Delay Character

**Objective:** Verify delays can have different characters simultaneously

**Procedure:**

1. Set TIME 1 to 20% (short)
2. Set TIME 2 to 70% (long)
3. Set MOD 1 to 70% (heavy)
4. Set MOD 2 to 20% (subtle)
5. Set FB to 50%

**Expected Results:**

- ✓ Early reflections heavily chorused
- ✓ Later echoes more stable
- ✓ Complex stereo field
- ✓ Two distinct characters audible

**Pass/Fail:** ____

---

### Test 10: Zero Modulation Depth

**Objective:** Verify system behaves correctly with no modulation

**Procedure:**

1. Set MOD 1 to 0%
2. Set MOD 2 to 0%
3. Test both SELF and LFO modes

**Expected Results:**

- ✓ Static delay time (no wobble)
- ✓ Clean repeats
- ✓ No artifacts from modulation system
- ✓ Same behavior in both modes

**Pass/Fail:** ____

---

### Test 11: Negative Delay Time Bug (Regression Test)

**Objective:** Verify the negative delay time bug fix is working

**Procedure:**

1. Set TIME 1 to minimum (0%, ~0.01s)
2. Set MOD 1 to maximum (100%, 0.5s depth)
3. Set LFO mode with slow triangle wave
4. Listen carefully for glitches

**Expected Results:**

- ✓ No audio dropouts
- ✓ No buffer underruns
- ✓ Delay time modulates upward from base
- ✓ Smooth operation even at minimum time
- ✓ System remains stable

**Notes:**
Before fix, this would cause negative delay times (-0.49s) resulting in glitches.
After fix, LFO is converted to unipolar (0-1) so delay only increases from base.

**Pass/Fail:** ____

---

## Parameter Reference

| Parameter | Range  | Function                       |
|-----------|--------|--------------------------------|
| TIME 1/2  | 0-100% | Base delay time (0.01s - 2.0s) |
| MOD 1/2   | 0-100% | Modulation depth (0 - 0.5s)    |
| FB        | 0-100% | Feedback amount (0 - 95%)      |
| MIX       | 0-100% | Dry/wet balance                |
| SELF/LFO  | Toggle | Modulation source              |
| TRI/SQR   | Toggle | LFO waveform shape             |

## Known Issues

None currently. Negative delay time bug was fixed with unipolar LFO conversion.

## Performance Notes

- CPU usage increases with modulation enabled
- Self-modulation is more CPU intensive than LFO mode
- Long delays with high feedback create longer DSP chains

## Cross-Platform Testing

Test on both platforms:

- [ ] JVM (desktop) - Uses JSyn InterpolatingDelay
- [ ] Android - Uses stub implementation (TODO: Oboe)

## Test Summary

**Total Tests:** 11  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
