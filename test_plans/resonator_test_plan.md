# Resonator Test Plan

## Overview

The Resonator module is a physical modeling synthesis effect ported from Mutable Instruments.
It provides modal synthesis (resonating filter bank) and Karplus-Strong string simulation, functioning as 
both a post-processor for synth voices and an excitation source for drums.

## Architecture

- **Signal Flow:** Voices/Drums → StereoPan → Resonator → Distortion → StereoSum → LineOut
- **ResonatorUnit:** Core DSP implementing 24 SVF filters (modal) or comb filter (string)
- **DspResonatorPlugin:** Wrapper managing dry/wet mixing and parameter control
- **Strum Trigger:** String release gestures excite the resonator at the string's pitch

## Test Cases

### Test 1: Enable/Disable Toggle

**Objective:** Verify resonator enable state affects signal processing

**Procedure:**

1. Open Rings Resonator panel
2. Set MIX to 50%
3. Play sustained voice
4. Toggle Enable OFF
5. Toggle Enable ON

**Expected Results:**

- ✓ OFF: Signal passes through dry (unprocessed)
- ✓ ON: Resonator processing audible
- ✓ No clicks/pops on toggle
- ✓ UI toggle state reflects audio state

**Pass/Fail:** ____

---

### Test 2: Modal Mode - Basic Excitation

**Objective:** Verify Modal mode creates resonating harmonics

**Setup:**

1. Enable Resonator
2. Set Mode to "Modal"
3. Set MIX to 75%
4. Set STRUCTURE to 50%
5. Set BRIGHTNESS to 50%

**Procedure:**

1. Play short percussive voice hit
2. Listen for resonance tail
3. Play sustained chord
4. Listen for harmonic reinforcement

**Expected Results:**

- ✓ Percussive hits excite ringing overtones
- ✓ Sustained notes add modal character
- ✓ Sound resembles struck metal/glass
- ✓ Harmonics decay naturally

**Pass/Fail:** ____

---

### Test 3: String Mode - Karplus-Strong

**Objective:** Verify String mode creates plucked string sounds

**Setup:**

1. Enable Resonator
2. Set Mode to "String"
3. Set MIX to 75%
4. Set DAMPING to 30%
5. Set BRIGHTNESS to 60%

**Procedure:**

1. Play short percussive voice hit
2. Listen for string-like decay
3. Vary DAMPING
4. Verify decay time changes

**Expected Results:**

- ✓ Percussive input sounds like plucked string
- ✓ Low damping = long sustain
- ✓ High damping = quick decay
- ✓ Character differs from Modal mode

**Pass/Fail:** ____

---

### Test 4: Structure Parameter

**Objective:** Verify STRUCTURE controls harmonic spacing/inharmonicity

**Setup:**

1. Enable Resonator (Modal mode)
2. Set MIX to 75%
3. Play sustained note

**Procedure:**

1. STRUCTURE at 0%: Hear result
2. STRUCTURE at 25%: Hear result
3. STRUCTURE at 50%: Hear result
4. STRUCTURE at 75%: Hear result
5. STRUCTURE at 100%: Hear result

**Expected Results:**

- ✓ 0%: Minimal spread, focused tone
- ✓ 50%: Balanced harmonic spread
- ✓ 100%: Wide, bell-like inharmonic spectrum
- ✓ Smooth transition between values
- ✓ No clicks or artifacts

**Pass/Fail:** ____

---

### Test 5: Brightness Parameter

**Objective:** Verify BRIGHTNESS controls high frequency content

**Setup:**

1. Enable Resonator (Modal mode)
2. Set MIX to 75%
3. Play percussive hit

**Procedure:**

1. BRIGHTNESS at 0%: Hear result
2. BRIGHTNESS at 50%: Hear result
3. BRIGHTNESS at 100%: Hear result

**Expected Results:**

- ✓ 0%: Dark, muffled resonance
- ✓ 50%: Balanced tone
- ✓ 100%: Bright, shimmering resonance
- ✓ Affects high frequency harmonics clearly
- ✓ Smooth parameter response

**Pass/Fail:** ____

---

### Test 6: Damping Parameter

**Objective:** Verify DAMPING controls decay time

**Setup:**

1. Enable Resonator (String mode)
2. Set MIX to 75%
3. Play short hit

**Procedure:**

1. DAMPING at 10%: Very long sustain
2. DAMPING at 50%: Medium sustain
3. DAMPING at 90%: Very short sustain

**Expected Results:**

- ✓ Low damping = long ringing
- ✓ High damping = quick decay
- ✓ Affects both Modal and String modes
- ✓ No sudden cutoffs (smooth decay)

**Pass/Fail:** ____

---

### Test 7: Position Parameter

**Objective:** Verify POSITION affects excitation position

**Setup:**

1. Enable Resonator (String mode)
2. Set MIX to 75%
3. Play percussive hit

**Procedure:**

1. POSITION at 0%: Excitation near edge
2. POSITION at 50%: Excitation at center
3. POSITION at 100%: Excitation near opposite edge

**Expected Results:**

- ✓ Edge positions: More harmonics
- ✓ Center position: Fundamental emphasis
- ✓ Audible tonal difference
- ✓ Simulates pickup/excitation position

**Pass/Fail:** ____

---

### Test 8: Mix (Dry/Wet) Control

**Objective:** Verify MIX blends dry and wet signals

**Setup:**

1. Enable Resonator
2. Play sustained chord

**Procedure:**

1. MIX at 0%: Fully dry
2. MIX at 25%: Mostly dry
3. MIX at 50%: Equal blend
4. MIX at 75%: Mostly wet
5. MIX at 100%: Fully wet

**Expected Results:**

- ✓ 0%: Original signal, no resonator
- ✓ 50%: Equal blend of both
- ✓ 100%: Only resonated signal
- ✓ Smooth crossfade
- ✓ No level jumps

**Pass/Fail:** ____

---

### Test 9: String Strumming (Per-String Bender)

**Objective:** Verify string pluck gestures trigger resonator

**Setup:**

1. Enable Resonator (String mode)
2. Set MIX to 75%
3. Navigate to Strings panel

**Procedure:**

1. Pull a string horizontally
2. Release quickly (mimicking pluck)
3. Listen for resonator excitation
4. Repeat with different strings

**Expected Results:**

- ✓ Quick release triggers strum
- ✓ Strum pitch matches string tuning
- ✓ Different strings produce different pitches
- ✓ Velocity affects brightness/intensity
- ✓ Smooth integration with voice sound

**Pass/Fail:** ____

---

### Test 10: Drum Excitation

**Objective:** Verify drums route through resonator

**Setup:**

1. Enable Resonator (Modal mode)
2. Set MIX to 50%
3. Open 808 Drums panel

**Procedure:**

1. Trigger kick drum
2. Trigger snare drum
3. Trigger hi-hat
4. Listen for resonance

**Expected Results:**

- ✓ Kick excites low modal resonance
- ✓ Snare excites mid-range modes
- ✓ Hi-hat excites high-frequency shimmer
- ✓ Drums + resonator creates hybrid percussion
- ✓ MIX controls blend

**Pass/Fail:** ____

---

### Test 11: Mode Switching

**Objective:** Verify clean switching between Modal/String modes

**Setup:**

1. Enable Resonator
2. Play sustained chord

**Procedure:**

1. Set Mode to "Modal"
2. Listen to tone
3. Switch to "String"
4. Listen to tone change
5. Switch back to "Modal"

**Expected Results:**

- ✓ Modal: Metallic/bell character
- ✓ String: Plucked string character
- ✓ No clicks on mode switch
- ✓ Different sonic character per mode

**Pass/Fail:** ____

---

### Test 12: Resonator with Delay

**Objective:** Verify resonator integrates with delay

**Setup:**

1. Enable Resonator
2. Set DELAY MIX to 50%
3. Set DELAY FEEDBACK to 60%

**Procedure:**

1. Play short notes
2. Listen to delayed resonance
3. Adjust resonator parameters

**Expected Results:**

- ✓ Resonated signal feeds into delay
- ✓ Delay echoes include resonance
- ✓ No feedback runaway
- ✓ Musical combination

**Pass/Fail:** ____

---

### Test 13: Resonator with Distortion

**Objective:** Verify resonator + distortion chain

**Setup:**

1. Enable Resonator
2. Set DISTORTION DRIVE to 50%
3. Set DISTORTION MIX to 50%

**Procedure:**

1. Play notes
2. Listen to distorted resonance
3. Adjust drive amount

**Expected Results:**

- ✓ Resonated signal feeds into distortion
- ✓ Distortion adds warmth/grit to modes
- ✓ No harsh artifacts
- ✓ Signal chain order: Resonator → Distortion

**Pass/Fail:** ____

---

### Test 14: Extreme Settings Stress Test

**Objective:** Verify stability at extreme settings

**Procedure:**

1. Set all resonator parameters to 100%
2. Enable all voices
3. Trigger drums rapidly
4. Monitor for issues

**Expected Results:**

- ✓ Output remains controlled
- ✓ No crashes or glitches
- ✓ No runaway feedback
- ✓ System remains responsive

**Pass/Fail:** ____

---

### Test 15: Parameter Persistence

**Objective:** Verify settings persist across sessions

**Procedure:**

1. Configure resonator with specific values:
   - Mode: String
   - Structure: 35%
   - Brightness: 65%
   - Damping: 25%
   - Position: 55%
   - Mix: 70%
2. Save preset
3. Load different preset
4. Reload saved preset

**Expected Results:**

- ✓ All values restored correctly
- ✓ Enable state restored
- ✓ Mode restored
- ✓ Sound matches original configuration

**Pass/Fail:** ____

---

### Test 16: UI Responsiveness

**Objective:** Verify UI controls respond smoothly

**Procedure:**

1. Rapidly adjust all knobs
2. Toggle enable rapidly
3. Switch modes rapidly

**Expected Results:**

- ✓ Knobs respond immediately
- ✓ No UI lag
- ✓ Audio follows UI changes
- ✓ No zipper noise on parameter changes

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Full Signal Chain Test

**Objective:** Verify complete signal path

**Signal Flow:**
Voices → StereoPan → Resonator → Distortion → StereoSum → Delay → LineOut

**Procedure:**

1. Play voices
2. Enable resonator
3. Enable distortion
4. Enable delay
5. Verify all effects interact

**Expected Results:**

- ✓ Each stage functions correctly
- ✓ Signal flows through complete chain
- ✓ Musical result at all settings

**Pass/Fail:** ____

---

### INT-2: CPU Load Test

**Objective:** Verify resonator doesn't cause excessive CPU usage

**Procedure:**

1. Monitor CPU meter
2. Enable resonator
3. Set to Modal mode (most intensive)
4. Play multiple voices

**Expected Results:**

- ✓ CPU increase is reasonable (<15%)
- ✓ No audio dropouts
- ✓ Real-time performance maintained

**Pass/Fail:** ____

---

## Parameter Reference

| Parameter   | Range   | Function                                    |
|-------------|---------|---------------------------------------------|
| ENABLED     | On/Off  | Bypass resonator processing                 |
| MODE        | Modal/String | Physical modeling algorithm              |
| STRUCTURE   | 0-100%  | Harmonic spread / inharmonicity             |
| BRIGHTNESS  | 0-100%  | High frequency content                      |
| DAMPING     | 0-100%  | Decay time (inverse)                        |
| POSITION    | 0-100%  | Excitation position                         |
| MIX         | 0-100%  | Dry/Wet blend                               |

## Technical Details

- **Modal Engine:** 24 parallel SVF filters with configurable frequencies
- **String Engine:** Karplus-Strong with damping filter and delay line
- **Sample Rate:** 44100 Hz (matches audio engine)
- **Mix Implementation:** Parallel dry/wet paths with linear crossfade
- **Strum Trigger:** Velocity > 1.5 units/second, pull distance > 15%

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
