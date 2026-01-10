# 808 Drum Engines Test Plan

## Overview

The 808 Drum Engines module provides specialized drum synthesis ported from Mutable Instruments Plaits. It includes three distinct engines for classic analog-style drum sounds: Bass Drum (BD), Snare Drum (SD), and Hi-Hat (HH).

## Architecture

- **Bass Drum (BD):** Resonant sine oscillator with pitch envelope and FM
- **Snare Drum (SD):** Dual-mode resonator with filtered noise
- **Hi-Hat (HH):** 6-oscillator metallic cluster through bandpass filter
- **Signal Path:** DrumUnit → DrumPlugin → Stereo Mix → Master Output
- **DSP Foundation:** Zero-Delay-Feedback State Variable Filters (SVF)

## UI Controls

| Control | Function | Range |
|---------|----------|-------|
| BD/SD/HH | Drum type selector | 3 options |
| TUNE | Base frequency | 0-100% |
| TONE | Filter/harmonic content | 0-100% |
| DECAY | Envelope decay time | 0-100% |
| AFM/SNAP/NOISE | Type-specific parameter | 0-100% |
| TRIG | Manual trigger button | Momentary |

---

## Test Cases

### Test 1: Bass Drum Basic Sound

**Objective:** Verify bass drum produces classic 808 kick character

**Procedure:**

1. Select BD in the drum panel
2. Set TUNE to 50%
3. Set TONE to 50%
4. Set DECAY to 50%
5. Set AFM to 0%
6. Press TRIG repeatedly

**Expected Results:**

- ✓ Deep, punchy kick sound
- ✓ Clear pitch "thump"
- ✓ Smooth attack transient
- ✓ Controlled low-frequency content
- ✓ No clicks or pops

**Pass/Fail:** ____

---

### Test 2: Bass Drum Tune Range

**Objective:** Verify TUNE control affects pitch appropriately

**Procedure:**

1. Select BD
2. Set DECAY to 70% (longer tail for pitch assessment)
3. Vary TUNE from 0% to 100%

**Expected Results:**

- ✓ 0%: Very low sub-bass (~40Hz feel)
- ✓ 50%: Standard 808 kick range (~55Hz)
- ✓ 100%: Higher, tom-like pitch
- ✓ Smooth pitch sweep with no stepping

**Pass/Fail:** ____

---

### Test 3: Bass Drum Decay Control

**Objective:** Verify DECAY controls sustain length

**Procedure:**

1. Select BD
2. Set TUNE and TONE to 50%
3. Test DECAY at 0%, 25%, 50%, 75%, 100%

**Expected Results:**

| Decay | Expected Behavior |
|-------|-------------------|
| 0% | Very short, tight thump |
| 25% | Punchy, quick release |
| 50% | Standard 808 decay |
| 75% | Long, sustained boom |
| 100% | Very long, drone-like tail |

- ✓ All decay lengths musically useful
- ✓ Smooth transition between values

**Pass/Fail:** ____

---

### Test 4: Bass Drum Attack FM (AFM)

**Objective:** Verify AFM adds pitch sweep character

**Procedure:**

1. Select BD
2. Set standard settings (50% across)
3. Vary AFM from 0% to 100%

**Expected Results:**

- ✓ 0%: Clean, minimal pitch sweep
- ✓ 50%: Moderate "zap" attack
- ✓ 100%: Aggressive pitch drop, techno character
- ✓ FM adds harmonic complexity to attack

**Pass/Fail:** ____

---

### Test 5: Bass Drum Self-FM

**Objective:** Verify P5 (Self-FM) parameter adds body

**Procedure:**

1. Select BD
2. Set AFM to 30%
3. Trigger with varying SELF-FM values (if exposed)

**Expected Results:**

- ✓ Self-FM adds "punch" to body
- ✓ Increases harmonic richness
- ✓ Does not destabilize pitch

**Pass/Fail:** ____

---

### Test 6: Snare Drum Basic Sound

**Objective:** Verify snare produces classic 808 snare character

**Procedure:**

1. Select SD in drum panel
2. Set all controls to 50%
3. Press TRIG repeatedly

**Expected Results:**

- ✓ Clear snare body (resonant tone)
- ✓ White noise "snap" component
- ✓ Two distinct frequency modes audible
- ✓ Punchy attack

**Pass/Fail:** ____

---

### Test 7: Snare Drum Snappy Control

**Objective:** Verify SNAP controls noise/body balance

**Procedure:**

1. Select SD
2. Set TUNE, TONE, DECAY to 50%
3. Vary SNAP from 0% to 100%

**Expected Results:**

| Snap | Expected Behavior |
|------|-------------------|
| 0% | Almost all body, no snap |
| 25% | Slight noise, full body |
| 50% | Balanced snare character |
| 75% | Emphasised snap, less body |
| 100% | Mostly noise, minimal tone |

- ✓ Full range from body to snap
- ✓ Always sounds like a snare

**Pass/Fail:** ____

---

### Test 8: Snare Drum Tone Control

**Objective:** Verify TONE shapes frequency content

**Procedure:**

1. Select SD
2. Vary TONE from 0% to 100%

**Expected Results:**

- ✓ 0%: Deep, boxy snare
- ✓ 50%: Balanced frequency response
- ✓ 100%: Bright, crackling snare
- ✓ Affects both resonator modes appropriately

**Pass/Fail:** ____

---

### Test 9: Hi-Hat Basic Sound

**Objective:** Verify hi-hat produces metallic character

**Procedure:**

1. Select HH in drum panel
2. Set all controls to 50%
3. Press TRIG repeatedly

**Expected Results:**

- ✓ Metallic, shimmering character
- ✓ High-frequency content dominant
- ✓ Clear attack transient
- ✓ Inharmonic frequency ratios audible

**Pass/Fail:** ____

---

### Test 10: Hi-Hat Decay (Closed vs Open)

**Objective:** Verify DECAY creates closed and open hat sounds

**Procedure:**

1. Select HH
2. Test DECAY at various settings

**Expected Results:**

| Decay | Expected Sound |
|-------|----------------|
| 0-20% | Tight closed hat |
| 20-50% | Standard closed hat |
| 50-75% | Semi-open hat |
| 75-100% | Open hat with sustain |

- ✓ Full range of hat articulations

**Pass/Fail:** ____

---

### Test 11: Hi-Hat Noisiness Control

**Objective:** Verify NOISE adds texture variety

**Procedure:**

1. Select HH
2. Vary NOISE from 0% to 100%

**Expected Results:**

- ✓ 0%: Pure metallic square cluster
- ✓ 50%: Added noise texture
- ✓ 100%: Heavy noise, almost white noise character
- ✓ Noise is clocked (pitched quality)

**Pass/Fail:** ____

---

### Test 12: Hi-Hat Tone/Cutoff Control

**Objective:** Verify TONE affects brightness

**Procedure:**

1. Select HH
2. Vary TONE from 0% to 100%

**Expected Results:**

- ✓ 0%: Dark, muted hat
- ✓ 50%: Standard brightness
- ✓ 100%: Bright, sizzling hat
- ✓ Affects coloration filter cutoff

**Pass/Fail:** ____

---

### Test 13: Rapid Triggering Stability

**Objective:** Verify stability under rapid triggers

**Procedure:**

1. For each drum type (BD, SD, HH)
2. Trigger rapidly (8+ times per second)
3. Listen for glitches

**Expected Results:**

- ✓ No audio glitches or clicks
- ✓ Each trigger retrigs cleanly
- ✓ Envelope resets properly
- ✓ No accumulating DC offset

**Pass/Fail:** ____

---

### Test 14: Drum Output Level Consistency

**Objective:** Verify all drums have balanced output levels

**Procedure:**

1. Set all parameters to 50%
2. Trigger each drum type
3. Compare perceived loudness

**Expected Results:**

- ✓ BD, SD, HH have similar peak levels
- ✓ No drum significantly louder/quieter
- ✓ Mix well without level adjustment

**Pass/Fail:** ____

---

### Test 15: DrumUnit Integration with Master

**Objective:** Verify drums route correctly through master chain

**Procedure:**

1. Trigger drums
2. Adjust master volume
3. Add distortion
4. Add delay

**Expected Results:**

- ✓ Drums respond to master volume
- ✓ Drums can be distorted
- ✓ Drums can use delay effects
- ✓ Full signal chain functional

**Pass/Fail:** ____

---

### Test 16: Desktop Panel Integration

**Objective:** Verify DRUMS panel works in desktop layout

**Procedure:**

1. Launch desktop app
2. Click DRUMS panel header to expand
3. Test all controls

**Expected Results:**

- ✓ Panel expands/collapses correctly
- ✓ All controls accessible
- ✓ Matches compact panel functionality
- ✓ Title shows "808 Engines" when expanded

**Pass/Fail:** ____

---

### Test 17: Compact Panel Integration

**Objective:** Verify drums work in compact/mobile layout

**Procedure:**

1. Navigate to Drums panel in switcher
2. Test all controls
3. Switch between panels

**Expected Results:**

- ✓ Panel displays correctly
- ✓ Touch/click triggers work
- ✓ Panel switching preserved state

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Drums + Tidal Live Code

**Objective:** Verify drums can be triggered via code

**Procedure:**

1. Run: `d1 $ s "bd808 sn808 hh808 hh808"`
2. Verify pattern plays correctly

**Expected Results:**

- ✓ Each drum sound triggers on schedule
- ✓ Correct drum type for each sample name
- ✓ Syncs with Tidal clock

**Pass/Fail:** ____

---

### INT-2: Drums + Pattern Generator

**Objective:** Verify drums work with topographic sequencer

**Procedure:**

1. Open Pattern panel
2. Set all densities to ~70%
3. Start pattern
4. Verify drums trigger

**Expected Results:**

- ✓ All three drums trigger from pattern
- ✓ Density affects trigger rate
- ✓ X/Y position affects pattern character

**Pass/Fail:** ____

---

## Parameter Reference

| Drum | Tune | Tone | Decay | Param4 |
|------|------|------|-------|--------|
| BD | Base freq (Hz) | Body brightness | Envelope length | Attack FM |
| SD | Body freq (Hz) | Shell brightness | Envelope length | Snappiness |
| HH | Base freq (Hz) | Cutoff frequency | Envelope length | Noisiness |

## Technical Details

- **Sample Rate:** 44100 Hz
- **Filter Type:** Zero-Delay-Feedback SVF (Mutable Instruments stmlib)
- **BD Oscillator:** 6 inharmonic square waves
- **SD Modes:** 2 resonators (1:1 and 1:2 ratio)
- **HH Ratios:** 1.0, 1.304, 1.466, 1.787, 1.932, 2.536

## Known Issues

- UIntArray usage in HH requires @OptIn for ExperimentalUnsignedTypes (warning only)

## Test Summary

**Total Tests:** 19  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
