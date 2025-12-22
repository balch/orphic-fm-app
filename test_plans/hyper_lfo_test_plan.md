# HyperLFO Test Plan

## Overview

The HyperLFO is a dual-oscillator LFO system with AND/OR logic combination, supporting both triangle and square waveforms. It serves as a modulation source for delays and voice FM.

## Architecture

- **Dual Oscillators:** LFO A and LFO B with independent frequency control
- **Waveform Types:** Triangle (smooth) and Square (stepped)
- **Combination Modes:**
  - **AND:** Output only when both LFOs are high
  - **OFF:** No output
  - **OR:** Output when either LFO is high
- **Logic Implementation:**
  - Square waves: Boolean logic on unipolar (0-1) signals
  - Triangle waves: MIN (AND) and MAX (OR) operations
- **Link Mode:** LFO A frequency modulates LFO B
- **Feedback Input:** System output can modulate LFO frequencies

## Test Cases

### Test 1: Basic Oscillation (Triangle Wave)

**Objective:** Verify both LFOs generate smooth triangle waves

**Setup:**
1. Set delay MOD 1 to 50% (to visualize LFO)
2. Set delay to LFO mode, triangle wave
3. Set HyperLFO mode to **OR**

**Procedure:**
1. Set LFO A to 30% (~3 Hz)
2. Set LFO B to 0%
3. Listen to delay modulation
4. Set LFO A to 0%, LFO B to 30%
5. Listen to delay modulation

**Expected Results:**
- ✓ LFO A alone: Smooth periodic wobble
- ✓ LFO B alone: Same smooth wobble
- ✓ Rate matches approximately 3 Hz
- ✓ Modulation is continuous and smooth
- ✓ No discontinuities or clicks

**Pass/Fail:** ____

---

### Test 2: Frequency Range Test

**Objective:** Verify LFO frequency range (0.01 - 10 Hz)

**Procedure:**
1. Set LFO A to 0% (minimum)
   - Observe very slow modulation
2. Set LFO A to 100% (maximum)
   - Observe fast modulation
3. Set LFO A to 50% (midpoint)
   - Should be moderate rate (~5 Hz)

**Expected Results:**
- ✓ 0%: Very slow (0.01 Hz, ~100s period)
- ✓ 50%: Medium (5 Hz, 0.2s period)
- ✓ 100%: Fast (10 Hz, 0.1s period)
- ✓ Smooth range with no dead zones

**Pass/Fail:** ____

---

### Test 3: Triangle AND Mode

**Objective:** Verify MIN operation for triangle AND

**Setup:**
1. Set both LFOs active
2. Triangle waveform
3. AND mode

**Procedure:**
1. Set LFO A to 20% (slow)
2. Set LFO B to 40% (faster)
3. Listen to modulation pattern

**Expected Results:**
- ✓ Modulation follows the **lower** of the two LFOs
- ✓ Creates a "gated" effect where B is truncated by A
- ✓ More sparse/intermittent than OR mode
- ✓ No audio glitches at crossover points

**Visual:** Output should look like the minimum envelope of both waves

**Pass/Fail:** ____

---

### Test 4: Triangle OR Mode

**Objective:** Verify MAX operation for triangle OR

**Setup:**
1. Continue from Test 3
2. Switch to OR mode

**Procedure:**
1. Keep LFO A at 20%, LFO B at 40%
2. Compare to AND mode

**Expected Results:**
- ✓ Modulation follows the **higher** of the two LFOs
- ✓ More continuous modulation than AND
- ✓ Output "fills in" the gaps from AND mode
- ✓ Smooth transitions at crossover points

**Pass/Fail:** ____

---

### Test 5: Square Wave AND Logic

**Objective:** Verify proper boolean AND for square waves

**Setup:**
1. Set waveform to **SQR** (square)
2. Set mode to **AND**

**Procedure:**
1. Set LFO A to 25% (~2.5 Hz)
2. Set LFO B to 33% (~3.3 Hz)
3. Listen for modulation pattern

**Expected Results:**
- ✓ Output is HIGH only when both A AND B are HIGH
- ✓ Creates a rhythmic pulsing pattern
- ✓ Pulse timing matches overlaps of A and B
- ✓ Clean square edges (no rounding)
- ✓ No clicks at transitions

**Pass/Fail:** ____

---

### Test 6: Square Wave OR Logic

**Objective:** Verify proper boolean OR for square waves

**Setup:**
1. Continue from Test 5
2. Switch to OR mode

**Procedure:**
1. Keep same LFO rates
2. Compare pattern to AND mode

**Expected Results:**
- ✓ Output is HIGH when either A OR B is HIGH
- ✓ More dense pulse pattern than AND
- ✓ Output is LOW only when both are LOW
- ✓ Logical OR truth table verified

**Pass/Fail:** ____

---

### Test 7: OFF Mode

**Objective:** Verify OFF mode disables output

**Procedure:**
1. Set mode to **OFF** (center position)
2. Both LFOs can be at any value
3. Check delay modulation

**Expected Results:**
- ✓ No modulation occurs
- ✓ Delay times remain static
- ✓ Clean silence from LFO (no DC offset)

**Pass/Fail:** ____

---

### Test 8: Link Mode

**Objective:** Verify LFO A modulates LFO B frequency

**Setup:**
1. Set triangle waveform
2. Set OR mode
3. Set Link toggle ON

**Procedure:**
1. Set LFO A to 10% (slow)
2. Set LFO B to 50% (medium)
3. Listen for frequency modulation effect

**Expected Results:**
- ✓ LFO B frequency wobbles in time with LFO A
- ✓ Creates a "vibrato on the vibrato" effect
- ✓ More complex, organic modulation pattern
- ✓ System remains stable

**Pass/Fail:** ____

---

### Test 9: Extreme Rate Differences

**Objective:** Verify stable operation with widely different rates

**Procedure:**
1. Set LFO A to 0% (very slow, 0.01 Hz)
2. Set LFO B to 100% (very fast, 10 Hz)
3. Test both AND and OR modes
4. Test both triangle and square

**Expected Results:**
- ✓ Both oscillators maintain their rates
- ✓ No interference between oscillators
- ✓ Logic operations work correctly
- ✓ No aliasing or artifacts at fast rate

**Pass/Fail:** ____

---

### Test 10: Waveform Switching

**Objective:** Verify smooth transitions between waveforms

**Procedure:**
1. Set moderate LFO rates (30-40%)
2. Active modulation on delays
3. Toggle TRI/SQR repeatedly while playing

**Expected Results:**
- ✓ Immediate waveform change
- ✓ No audio glitches during switch
- ✓ No DC offset shifts
- ✓ Smooth crossfade or instant switch (either is acceptable)

**Pass/Fail:** ____

---

### Test 11: Mode Switching

**Objective:** Verify smooth transitions between AND/OFF/OR

**Procedure:**
1. Active LFOs with modulation
2. Cycle through: AND → OFF → OR → AND
3. Do this rapidly several times

**Expected Results:**
- ✓ Immediate mode change
- ✓ No clicks or pops
- ✓ OFF truly silences output
- ✓ AND and OR produce different patterns

**Pass/Fail:** ____

---

### Test 12: Total Feedback Integration

**Objective:** Verify system output can modulate LFO frequencies

**Setup:**
1. Set Total FB knob (in center panel) to 50%
2. Set up audible output (voices + delays)

**Procedure:**
1. Play notes and create audio output
2. Observe if LFO rates change with output level

**Expected Results:**
- ✓ LFO frequencies increase with output amplitude
- ✓ Creates dynamic, responsive modulation
- ✓ System remains stable (no runaway)
- ✓ Effect is subtle but noticeable

**Pass/Fail:** ____

---

### Test 13: Bipolar to Unipolar Conversion (Square)

**Objective:** Verify correct signal range conversion for square waves

**Technical Details:**
- Square LFOs output -1 to +1 (bipolar)
- Must convert to 0 to 1 (unipolar) for logic
- Formula: `unipolar = (bipolar * 0.5) + 0.5`

**Procedure:**
1. Set square waveform, AND mode
2. Use oscilloscope or meter if available
3. Verify logic gates work correctly

**Expected Results:**
- ✓ AND: 1 only when both inputs > 0.5 unipolar
- ✓ OR: 1 when either input > 0.5 unipolar  
- ✓ Clean boolean logic behavior
- ✓ No intermediate values (except during transitions)

**Pass/Fail:** ____

---

### Test 14: MIN/MAX Operations (Triangle)

**Objective:** Verify correct MIN/MAX math for triangle waves

**Technical Details:**
- Triangle waves remain bipolar (-1 to +1)
- AND uses MIN(A, B)
- OR uses MAX(A, B)

**Procedure:**
1. Set triangle waveform
2. Set LFO A and B to different rates
3. Compare AND vs OR visually (via delay modulation)

**Expected Results:**

**AND (MIN):**
- ✓ Output never exceeds either input
- ✓ Follows lower envelope

**OR (MAX):**
- ✓ Output never falls below either input
- ✓ Follows upper envelope

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Delay Modulation

**Procedure:**
1. Set delay modulation source to LFO
2. Verify HyperLFO controls affect delay wobble
3. Test all modes and waveforms

**Expected:** HyperLFO fully controls delay character

**Pass/Fail:** ____

---

### INT-2: Voice FM Modulation

**Objective:** Verify HyperLFO can modulate voice FM depth

**Procedure:**
1. Set a Duo pair mod source to **LFO** (top position)
2. Set voices with FM depth
3. Modulate LFO rates and observe voice timbre changes

**Expected Results:**
- ✓ Voice timbre changes rhythmically
- ✓ Follows HyperLFO rate
- ✓ AND/OR modes affect modulation pattern

**Pass/Fail:** ____

---

## Parameter Reference

| Parameter | Range | Function |
|-----------|-------|----------|
| LFO A | 0-100% | Oscillator A frequency (0.01-10 Hz) |
| LFO B | 0-100% | Oscillator B frequency (0.01-10 Hz) |
| Mode | 3-way | AND / OFF / OR |
| Link | Toggle | A modulates B frequency |
| Waveform | Toggle | Triangle / Square |

## Known Issues

None currently. MIN/MAX bug was fixed for triangle waves.

## Test Summary

**Total Tests:** 16  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____  

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
