# Distortion & Dynamics Test Plan

## Overview

The distortion system features a tanh-based soft limiter with drive control and a parallel wet/dry mix architecture (Lyra MIX style).

## Architecture

- **Signal Flow:** Voices → Delays → PreMix → [Clean Path | Distorted Path] → PostMix → Master
- **Drive Control:** Increases saturation (1x to 15x gain)
- **Tanh Limiter:** Soft clipping preventing harsh distortion
- **Distortion Mix:** Blends clean and distorted signals in parallel
- **Master Volume:** Final output level control
- **Peak Follower:** Monitors output level for feedback

## Test Cases

### Test 1: Clean Path (No Distortion)

**Objective:** Verify clean signal path with minimal distortion

**Procedure:**
1. Set DRIVE to 0%
2. Set DISTORTION MIX to 0% (fully clean)
3. Set VOLUME to 50%
4. Play various voices

**Expected Results:**
- ✓ Clean, uncolored tone
- ✓ No saturation or clipping
- ✓ Full dynamic range preserved
- ✓ Low harmonic distortion

**Pass/Fail:** ____

---

### Test 2: Drive Control Range

**Objective:** Verify drive increases saturation smoothly

**Setup:**
1. Play sustained tone (voice 1)
2. Set DISTORTION MIX to 100% (fully distorted)

**Procedure:**
1. DRIVE at 0%: Clean
2. DRIVE at 25%: Mild saturation
3. DRIVE at 50%: Moderate distortion
4. DRIVE at 75%: Heavy distortion
5. DRIVE at 100%: Maximum saturation

**Expected Results:**
- ✓ 0%: Clean tone (1x gain)
- ✓ 50%: Noticeable warmth/grit (8x gain)
- ✓ 100%: Heavy distortion (15x gain)
- ✓ Smooth transition between levels
- ✓ No clicks or discontinuities
- ✓ Never harsh or digital-sounding

**Pass/Fail:** ____

---

### Test 3: Tanh Limiter Soft Clipping

**Objective:** Verify limiter prevents hard clipping

**Procedure:**
1. Set DRIVE to 100%
2. Set DISTORTION MIX to 100%
3. Play all 8 voices at once (maximum signal)
4. Monitor output level

**Expected Results:**
- ✓ Output remains controlled
- ✓ No hard clipping audible
- ✓ Soft saturation characteristic
- ✓ No digital harshness
- ✓ Signal compressed smoothly

**Pass/Fail:** ____

---

### Test 4: Distortion Mix Control

**Objective:** Verify parallel mix blends clean and distorted paths

**Setup:**
1. Set DRIVE to 75% (heavy distortion)
2. Play sustained tone

**Procedure:**
1. MIX at 0%: Only clean signal
2. MIX at 25%: Mostly clean, some distortion
3. MIX at 50%: Equal blend
4. MIX at 75%: Mostly distorted
5. MIX at 100%: Only distorted signal

**Expected Results:**
- ✓ Smooth crossfade between paths
- ✓ No level jumps
- ✓ 50% maintains similar perceived loudness
- ✓ Clean attack preserved at low mix values
- ✓ Distortion character controlled without losing clarity

**Pass/Fail:** ____

---

### Test 5: Master Volume Control

**Objective:** Verify master volume affects final output

**Procedure:**
1. Set VOLUME to 0%: Should be silent
2. Set VOLUME to 50%: Medium level
3. Set VOLUME to 100%: Maximum level

**Expected Results:**
- ✓ 0%: Complete silence
- ✓ Linear response across range
- ✓ No distortion from volume control itself
- ✓ Smooth fades
- ✓ Affects both clean and distorted paths equally

**Pass/Fail:** ____

---

### Test 6: Drive + Mix Interaction

**Objective:** Verify drive and mix work together musically

**Procedure:**
Test these combinations:
1. Low Drive (20%), Low Mix (20%)
2. Low Drive (20%), High Mix (80%)
3. High Drive (80%), Low Mix (20%)
4. High Drive (80%), High Mix (80%)

**Expected Results:**

| Drive | Mix | Expected Character |
|-------|-----|-------------------|
| Low | Low | Nearly clean, slight warmth |
| Low | High | Subtle saturation, clean attack |
| High | Low | Distorted body, clean attack |
| High | High | Full aggressive distortion |

- ✓ All combinations produce musical results
- ✓ No unexpected behavior

**Pass/Fail:** ____

---

### Test 7: Dynamic Response

**Objective:** Verify distortion responds to input level

**Setup:**
1. Set DRIVE to 50%
2. Set MIX to 50%

**Procedure:**
1. Play single voice quietly (low hold level)
2. Play single voice loudly (high hold level)
3. Play chord (multiple voices)

**Expected Results:**
- ✓ Quiet signals: Less distortion
- ✓ Loud signals: More distortion
- ✓ Distortion increases with signal level
- ✓ Maintains dynamics (not over-compressed)

**Pass/Fail:** ____

---

### Test 8: Peak Follower Monitoring

**Objective:** Verify peak follower tracks output level

**Procedure:**
1. Play notes with varying intensity
2. Observe peak indicator (if visible)
3. Check Total FB responds to peaks

**Expected Results:**
- ✓ Peak follower responds to output
- ✓ Attack: Fast response
- ✓ Decay: Smooth fall-off (~0.1s half-life)
- ✓ Total FB routing works correctly

**Pass/Fail:** ____

---

### Test 9: Distortion with Delay Feedback

**Objective:** Verify stable behavior with delay feedback

**Setup:**
1. Set delay feedback to 70%
2. Set DRIVE to 60%
3. Set MIX to 50%

**Procedure:**
1. Play short pulse
2. Listen to delay tail
3. Monitor for runaway feedback

**Expected Results:**
- ✓ Delay feedback remains controlled
- ✓ No infinite feedback loops
- ✓ Distortion doesn't accumulate destructively
- ✓ Tail fades smoothly

**Pass/Fail:** ____

---

### Test 10: Extreme Settings Stress Test

**Objective:** Verify system stability at extreme settings

**Procedure:**
1. All controls at 100%:
   - DRIVE: 100%
   - MIX: 100%
   - VOLUME: 100%
   - Delay FB: 95%
   - All 8 voices playing
2. Monitor for issues

**Expected Results:**
- ✓ Output remains controlled
- ✓ No crashes or audio glitches
- ✓ Limiter prevents damage
- ✓ System remains responsive
- ✓ Can recover by lowering levels

**Pass/Fail:** ____

---

### Test 11: Distortion with Modulation

**Objective:** Verify distortion works with modulated delays

**Setup:**
1. Enable delay LFO modulation
2. Set DRIVE to 50%
3. Set MIX to 50%

**Procedure:**
1. Play sustained chord
2. Listen to combined effect

**Expected Results:**
- ✓ Distortion and modulation complement each other
- ✓ No intermodulation artifacts
- ✓ Musical, cohesive sound
- ✓ No zipper noise

**Pass/Fail:** ____

---

### Test 12: Zero Drive Test

**Objective:** Verify 0% drive produces unity gain

**Procedure:**
1. Set DRIVE to 0%
2. Set MIX to 100% (full distorted path)
3. Compare level to clean path

**Expected Results:**
- ✓ No level difference
- ✓ No tonal coloration
- ✓ Essentially bypasses limiter
- ✓ 1x gain verified

**Pass/Fail:** ____

---

### Test 13: Sustain with Distortion

**Objective:** Verify distortion affects sustain character

**Setup:**
1. Enable hold on voice 1
2. Set envelope speed to 100% (slow)

**Procedure:**
1. Trigger voice with low drive
2. Trigger voice with high drive
3. Compare sustain phase

**Expected Results:**
- ✓ High drive: More compressed sustain
- ✓ Low drive: More dynamic sustain
- ✓ Distortion adds character throughout envelope
- ✓ No pops or clicks

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Full Chain Test

**Objective:** Verify complete signal chain

**Signal Flow:**
Voices → Delays → Mix → [Clean | Distorted] → Master → Output

**Procedure:**
1. Play voices
2. Add delays
3. Add distortion
4. Adjust all parameters
5. Verify clean signal flow

**Expected Results:**
- ✓ Each stage functions correctly
- ✓ No unexpected interactions
- ✓ Musical result at all settings

**Pass/Fail:** ____

---

### INT-2: CPU Load Test

**Objective:** Verify distortion doesn't cause excessive CPU usage

**Procedure:**
1. Monitor CPU meter
2. Enable distortion
3. Vary drive amount

**Expected Results:**
- ✓ CPU increase is reasonable (<10%)
- ✓ No audio dropouts
- ✓ Real-time performance maintained

**Pass/Fail:** ____

---

## Parameter Reference

| Parameter | Range | Function |
|-----------|-------|----------|
| DRIVE | 0-100% | Distortion amount (1x - 15x gain) |
| MIX | 0-100% | Clean/Distorted blend |
| VOLUME | 0-100% | Master output level (0.0 - 1.0) |

## Technical Details

- **Limiter Type:** Tanh (soft clipping)
- **Drive Range:** 1.0 + (amount × 14.0)
- **Mix Implementation:** Parallel paths, not series
- **Clean Path Gain:** 1.0 - mixAmount
- **Distorted Path Gain:** mixAmount

## Known Issues

None currently.

## Test Summary

**Total Tests:** 15  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____  

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
