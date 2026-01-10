# Topographic Pattern Generator Test Plan

## Overview

The Topographic Pattern Generator is based on Mutable Instruments Grids. It provides an algorithmic drum pattern generator using a 2D "map" of pre-computed patterns that can be navigated with X/Y controls, producing varying rhythmic patterns for three drum parts (BD, SD, HH).

## Architecture

- **Pattern Grid:** 5x5 grid of 25 pre-computed pattern nodes
- **Interpolation:** Nearest-neighbor node selection (future: bilinear)
- **Parts:** 3 independent drum channels (BD, SD, HH)
- **Steps:** 32 steps per pattern
- **Density Control:** Per-part threshold for trigger probability
- **Clock:** Internal or external clock source (6 ticks per 16th note)

## UI Controls

| Control | Function | Range |
|---------|----------|-------|
| X/Y Pad | Navigate pattern map | 0-100% each axis |
| BD Density | Bass drum trigger threshold | 0-100% |
| SD Density | Snare drum trigger threshold | 0-100% |
| HH Density | Hi-hat trigger threshold | 0-100% |
| PLAY/STOP | Start/stop pattern playback | Toggle |

---

## Test Cases

### Test 1: X/Y Pad Navigation

**Objective:** Verify X/Y pad responds to touch/click

**Procedure:**

1. Open Pattern panel
2. Click/tap on X/Y pad at various positions
3. Drag across the pad

**Expected Results:**

- ✓ Crosshair follows touch/click position
- ✓ X and Y values update independently
- ✓ Smooth dragging response
- ✓ Values stay within 0-100% bounds
- ✓ Grid lines visible for reference

**Pass/Fail:** ____

---

### Test 2: X/Y Position Affects Pattern

**Objective:** Verify moving X/Y changes the rhythmic pattern

**Setup:**

1. Set all densities to 70%
2. Start pattern

**Procedure:**

1. Position X/Y at (0, 0) - listen for 8 bars
2. Move to (0.5, 0.5) - listen for 8 bars
3. Move to (1.0, 1.0) - listen for 8 bars
4. Drag slowly across diagonal

**Expected Results:**

- ✓ Distinctly different patterns at corners
- ✓ Pattern changes as X/Y moves
- ✓ Smooth transition between patterns
- ✓ No sudden jumps or glitches

**Pass/Fail:** ____

---

### Test 3: Density Control - BD

**Objective:** Verify BD density affects bass drum trigger rate

**Setup:**

1. Set X/Y to center (0.5, 0.5)
2. Set SD and HH density to 0% (mute)
3. Start pattern

**Procedure:**

1. BD density at 0%: Listen for 8 bars
2. BD density at 25%: Listen for 8 bars
3. BD density at 50%: Listen for 8 bars
4. BD density at 75%: Listen for 8 bars
5. BD density at 100%: Listen for 8 bars

**Expected Results:**

| Density | Expected Behavior |
|---------|-------------------|
| 0% | No BD triggers |
| 25% | Sparse BD, only strong accents |
| 50% | Moderate BD density |
| 75% | Dense BD pattern |
| 100% | All possible BD steps trigger |

- ✓ Smooth density transition

**Pass/Fail:** ____

---

### Test 4: Density Control - SD

**Objective:** Verify SD density affects snare trigger rate

**Setup:**

1. Set X/Y to center
2. Mute BD and HH (density 0%)
3. Start pattern

**Procedure:**

1. Vary SD density from 0% to 100%

**Expected Results:**

- ✓ Same behavior as BD test
- ✓ 0%: No triggers
- ✓ 100%: Maximum triggers
- ✓ Affects only snare channel

**Pass/Fail:** ____

---

### Test 5: Density Control - HH

**Objective:** Verify HH density affects hi-hat trigger rate

**Setup:**

1. Set X/Y to center
2. Mute BD and SD (density 0%)
3. Start pattern

**Procedure:**

1. Vary HH density from 0% to 100%

**Expected Results:**

- ✓ Same behavior as BD test
- ✓ Hi-hats typically have more frequent triggers at high density
- ✓ Characteristic hi-hat rhythms preserved

**Pass/Fail:** ____

---

### Test 6: Combined Density Test

**Objective:** Verify all three parts work together

**Procedure:**

1. Set all densities to 50%
2. Start pattern
3. Listen for full drum pattern

**Expected Results:**

- ✓ All three drums trigger independently
- ✓ Produces coherent drum pattern
- ✓ BD on downbeats (1, 3 typically)
- ✓ SD on backbeats (2, 4 typically)
- ✓ HH on 8ths or 16ths

**Pass/Fail:** ____

---

### Test 7: Play/Stop Toggle

**Objective:** Verify play/stop control works

**Procedure:**

1. Press PLAY - pattern starts
2. Press STOP - pattern stops
3. Press PLAY - pattern resumes (from step 0)

**Expected Results:**

- ✓ PLAY starts pattern immediately
- ✓ STOP silences all output
- ✓ UI updates to show state
- ✓ Button text changes (PLAY ↔ STOP)
- ✓ Pattern resets on restart

**Pass/Fail:** ____

---

### Test 8: Pattern Loop Continuity

**Objective:** Verify pattern loops seamlessly

**Procedure:**

1. Start pattern
2. Listen for at least 4 full cycles (128 steps)
3. Focus on loop point

**Expected Results:**

- ✓ Pattern loops smoothly
- ✓ No gap or stutter at loop point
- ✓ Step counter wraps from 31 to 0
- ✓ Consistent rhythm across loops

**Pass/Fail:** ____

---

### Test 9: Real-time X/Y Modulation

**Objective:** Verify X/Y can be modulated while playing

**Setup:**

1. Set densities to 60%
2. Start pattern

**Procedure:**

1. Slowly drag X from 0% to 100% over 8 bars
2. Slowly drag Y from 0% to 100% over 8 bars
3. Make circular motions

**Expected Results:**

- ✓ Pattern evolves smoothly
- ✓ No audio glitches during movement
- ✓ Musically interesting variations
- ✓ No sudden pattern jumps

**Pass/Fail:** ____

---

### Test 10: Density at Extremes

**Objective:** Verify behavior at 0% and 100% density

**Procedure:**

1. All densities at 0%: Start pattern
2. All densities at 100%: Start pattern

**Expected Results:**

- ✓ 0%: Complete silence (no triggers)
- ✓ 100%: Maximum density pattern
- ✓ 100% still has rhythmic structure (not every step)
- ✓ No crashes at extremes

**Pass/Fail:** ____

---

### Test 11: Clock Accuracy

**Objective:** Verify pattern tempo is stable

**Setup:**

1. Use metronome or tap tempo reference
2. Note the expected BPM

**Procedure:**

1. Start pattern
2. Listen for timing drift over 2 minutes

**Expected Results:**

- ✓ Pattern stays in sync with reference
- ✓ No drift or rushing/dragging
- ✓ Consistent step duration

**Pass/Fail:** ____

---

### Test 12: Drum Sound Triggering

**Objective:** Verify pattern triggers correct drum sounds

**Procedure:**

1. Start pattern with all densities at 50%
2. Listen for correct drum assignments

**Expected Results:**

- ✓ Part 0 triggers bass drum sound
- ✓ Part 1 triggers snare drum sound
- ✓ Part 2 triggers hi-hat sound
- ✓ Sounds match 808 engine outputs

**Pass/Fail:** ____

---

### Test 13: Pattern Characteristics by Region

**Objective:** Verify X/Y regions produce distinct patterns

**Procedure:**

Test pattern at these X/Y positions:

| Position | Expected Character |
|----------|-------------------|
| (0, 0) | Simple, sparse |
| (1, 0) | Different feel |
| (0, 1) | More complex |
| (1, 1) | Dense or syncopated |
| (0.5, 0.5) | Balanced/common |

**Expected Results:**

- ✓ Each region has distinct character
- ✓ Patterns are musically coherent
- ✓ Navigation feels like exploring a "terrain"

**Pass/Fail:** ____

---

### Test 14: Desktop Panel Integration

**Objective:** Verify PATT panel works in desktop layout

**Procedure:**

1. Launch desktop app
2. Click PATT panel header to expand
3. Test X/Y pad and density controls

**Expected Results:**

- ✓ Panel expands/collapses correctly
- ✓ X/Y pad is usable at panel size
- ✓ Title shows "Topographic" when expanded
- ✓ All controls function

**Pass/Fail:** ____

---

### Test 15: Compact Panel Integration

**Objective:** Verify pattern panel works in compact layout

**Procedure:**

1. Navigate to Sequencer panel in bottom switcher
2. Test all controls
3. Switch panels and return

**Expected Results:**

- ✓ Panel displays correctly on small screen
- ✓ Touch/drag on X/Y pad works
- ✓ State preserved when switching

**Pass/Fail:** ____

---

### Test 16: Persistence of Settings

**Objective:** Verify X/Y and density settings persist

**Procedure:**

1. Set X/Y to (0.3, 0.7)
2. Set densities to (40%, 60%, 80%)
3. Switch to another panel
4. Return to Pattern panel

**Expected Results:**

- ✓ X/Y position restored
- ✓ Density values restored
- ✓ Play/Stop state restored

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Pattern + 808 Drums

**Objective:** Verify pattern controls 808 drum engines

**Procedure:**

1. Open Pattern panel
2. Ensure 808 engines are active
3. Start pattern
4. Adjust 808 TUNE, TONE, DECAY while pattern plays

**Expected Results:**

- ✓ Pattern triggers 808 engines correctly
- ✓ Sound changes affect pattern playback
- ✓ Real-time parameter changes work

**Pass/Fail:** ____

---

### INT-2: Pattern + Effects Chain

**Objective:** Verify pattern output goes through effects

**Procedure:**

1. Start pattern
2. Add delay (high mix, long time)
3. Add distortion

**Expected Results:**

- ✓ Drums have delay echoes
- ✓ Distortion affects drum sound
- ✓ Full effects chain functional

**Pass/Fail:** ____

---

### INT-3: Pattern + Voice Playing

**Objective:** Verify pattern and FM voices work together

**Procedure:**

1. Start pattern at 50% densities
2. Play chords on keyboard/MIDI

**Expected Results:**

- ✓ Both drums and voices audible
- ✓ No audio glitches
- ✓ CPU remains reasonable
- ✓ Musical combination possible

**Pass/Fail:** ____

---

## Future Tests (When Implemented)

### FUT-1: External Clock Sync

**Objective:** Verify pattern syncs to MIDI clock

_(Blocked until MIDI clock sync is implemented)_

---

### FUT-2: Euclidean Mode

**Objective:** Verify Euclidean rhythm generation

_(Blocked until Euclidean mode is implemented)_

---

### FUT-3: Bilinear Interpolation

**Objective:** Verify smooth interpolation between nodes

_(Blocked until bilinear interpolation is implemented)_

---

## Parameter Reference

| Parameter | Range | Default | Function |
|-----------|-------|---------|----------|
| X | 0.0-1.0 | 0.5 | Horizontal map position |
| Y | 0.0-1.0 | 0.5 | Vertical map position |
| BD Density | 0.0-1.0 | 0.5 | BD trigger threshold |
| SD Density | 0.0-1.0 | 0.5 | SD trigger threshold |
| HH Density | 0.0-1.0 | 0.5 | HH trigger threshold |
| Running | Boolean | false | Pattern playback state |

## Technical Details

- **Pattern Resolution:** 32 steps
- **Clock Resolution:** 6 ticks per step (24 PPQN)
- **Node Grid:** 5x5 = 25 pattern nodes
- **Node Data:** 96 bytes each (32 steps × 3 parts)
- **Interpolation:** Nearest-neighbor (current)
- **Trigger Logic:** `patternValue > (1.0 - density)`

## Known Limitations

1. Only Node 0 implemented (demonstration); full 25-node grid pending
2. Nearest-neighbor selection only (no bilinear interpolation yet)
3. Internal clock only (no external MIDI sync yet)
4. No Euclidean mode yet

## Test Summary

**Total Tests:** 19  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
