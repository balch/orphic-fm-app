# Global Controls Test Plan

## Overview

Global controls provide advanced modulation and interaction features including cross-modulation
structure, total feedback, vibrato, and voice coupling.

## Features

- **FM Structure:** Within-pair vs Cross-quad routing
- **Total Feedback:** Output level modulates LFO frequencies
- **Vibrato:** Global pitch wobble (5 Hz sine wave)
- **Voice Coupling:** Voice envelopes modulate partner frequencies

## Test Cases

### Test 1: FM Structure - Within-Pair (Default)

**Objective:** Verify default within-pair FM routing

**Setup:**

1. Cross-quad toggle OFF (default)
2. Set duo pairs to FM mod source
3. Set FM depth to 50%

**Procedure:**

1. Trigger duo 1-2
    - Voice 1 should modulate voice 2
    - Voice 2 should modulate voice 1
2. Repeat for other duos

**Expected Results:**

- ✓ Each voice modulates its partner
- ✓ Duo 1-2: Self-contained feedback loop
- ✓ Duo 3-4: Self-contained feedback loop
- ✓ Duo 5-6: Self-contained feedback loop
- ✓ Duo 7-8: Self-contained feedback loop
- ✓ No cross-talk between pairs

**Pass/Fail:** ____

---

### Test 2: FM Structure - Cross-Quad

**Objective:** Verify cross-quad routing creates complex interactions

**Setup:**

1. Enable cross-quad toggle (34→56, 78→12)
2. All duo pairs set to FM mod source
3. FM depth 50%

**Procedure:**

1. Trigger all 8 voices
2. Listen for increased complexity

**Expected Results:**

**Routing:**

- ✓ Duo 1 (1-2) receives modulation from Duo 4 (7-8)
- ✓ Duo 2 (3-4) modulates within pair
- ✓ Duo 3 (5-6) receives modulation from Duo 2 (3-4)
- ✓ Duo 4 (7-8) modulates within pair

**Sound:**

- ✓ More complex timbres than within-pair
- ✓ Longer modulation chains
- ✓ Less predictable but musical

**Pass/Fail:** ____

---

### Test 3: Total Feedback - Basic Function

**Objective:** Verify output level modulates LFO frequencies

**Setup:**

1. Set Total FB to 0% initially
2. Set HyperLFO rates to 30% (moderate)
3. Enable delay LFO modulation to visualize effect

**Procedure:**

1. Play quiet tone
    - Note LFO rate
2. Play loud tone (multiple voices)
    - Note LFO rate increases
3. Set Total FB to 50%
    - Repeat and compare

**Expected Results:**

- ✓ 0% Total FB: LFO rates unaffected by output
- ✓ 50% Total FB: LFO speeds up with loud signals
- ✓ Scaling factor ~20x applied
- ✓ Effect is audible in delay modulation
- ✓ System remains stable

**Pass/Fail:** ____

---

### Test 4: Total Feedback - Stability

**Objective:** Verify no runaway feedback at extreme settings

**Procedure:**

1. Set Total FB to 100%
2. Play all 8 voices
3. Maximum output level
4. Monitor for runaway

**Expected Results:**

- ✓ LFO rates increase but stabilize
- ✓ No infinite feedback loop
- ✓ System remains playable
- ✓ Output level doesn't explode
- ✓ Can recover by lowering Total FB

**Pass/Fail:** ____

---

### Test 5: Vibrato - Basic Function

**Objective:** Verify vibrato creates global pitch wobble

**Setup:**

1. Set Vibrato to 0%
2. Play sustained chord (3-4 voices)

**Procedure:**

1. Increase Vibrato to 50%
2. Listen for pitch modulation
3. Increase to 100%

**Expected Results:**

- ✓ All voices wobble together in pitch
- ✓ ~5 Hz sine wave rate (fixed)
- ✓ Depth increases with control (0-20 Hz range)
- ✓ Musical, chorus-like effect
- ✓ No detuning between voices

**Pass/Fail:** ____

---

### Test 6: Vibrato Range Test

**Objective:** Verify vibrato depth range (0-20 Hz)

**Procedure:**

1. Set Vibrato to 0%: No wobble
2. Set Vibrato to 50%: ±10 Hz wobble
3. Set Vibrato to 100%: ±20 Hz wobble

**Expected Results:**

- ✓ 0%: Stable pitch
- ✓ 50%: Subtle wobble
- ✓ 100%: Pronounced wobble
- ✓ Smooth, musical at all levels

**Pass/Fail:** ____

---

### Test 7: Voice Coupling - Basic Function

**Objective:** Verify partner envelopes modulate frequency

**Setup:**

1. Set Coupling to 0%
2. Trigger voice 1 alone (with hold)

**Procedure:**

1. With Coupling at 50%, trigger voice 2
2. Listen to voice 1 pitch bend
3. Release voice 2
4. Voice 1 should bend back

**Expected Results:**

- ✓ Voice 1 pitch follows voice 2 envelope
- ✓ Attack: pitch bends up
- ✓ Release: pitch bends down
- ✓ Depth controlled by Coupling knob
- ✓ Affects all duo pairs

**Pass/Fail:** ____

---

### Test 8: Voice Coupling Range

**Objective:** Verify coupling depth range (0-30 Hz)

**Procedure:**

1. Coupling at 0%: No pitch bend
2. Coupling at 50%: ±15 Hz bend
3. Coupling at 100%: ±30 Hz bend

**Expected Results:**

- ✓ 0%: Voices independent
- ✓ 50%: Subtle interaction
- ✓ 100%: Strong pitch tracking
- ✓ Creates organic, breathing sounds

**Pass/Fail:** ____

---

### Test 9: Coupling with Fast Envelopes

**Objective:** Verify coupling with percussive envelopes

**Setup:**

1. Set envelope speed to 0% (fast)
2. Set Coupling to 75%

**Procedure:**

1. Trigger voice 1 (hold)
2. Rapidly trigger voice 2 multiple times
3. Listen to voice 1 pitch "hits"

**Expected Results:**

- ✓ Voice 1 pitch jumps with each voice 2 trigger
- ✓ Creates percussive pitch bends
- ✓ Tracks fast envelopes accurately
- ✓ No audio glitches

**Pass/Fail:** ____

---

### Test 10: Coupling with Slow Envelopes

**Objective:** Verify coupling with slow envelopes

**Setup:**

1. Set envelope speed to 100% (slow)
2. Set Coupling to 75%

**Procedure:**

1. Trigger voice 1 (hold)
2. Trigger voice 2
3. Listen to slow pitch bend

**Expected Results:**

- ✓ Voice 1 pitch bends slowly
- ✓ Smooth, whale-like pitch glides
- ✓ Tracks envelope contour accurately
- ✓ Musical portamento effect

**Pass/Fail:** ____

---

### Test 11: Combined Effects Test

**Objective:** Verify all global controls work together

**Setup:**

1. Total FB: 40%
2. Vibrato: 30%
3. Coupling: 50%
4. Cross-quad: ON

**Procedure:**

1. Play complex patch with multiple voices
2. Vary parameters individually
3. Listen for interactions

**Expected Results:**

- ✓ All effects audible simultaneously
- ✓ No unexpected interactions
- ✓ System remains stable
- ✓ Musical result
- ✓ Each control maintains independent effect

**Pass/Fail:** ____

---

### Test 12: Global Controls with Presets

**Objective:** Verify global controls save/load correctly

**Procedure:**

1. Set specific values for all global controls
2. Save as preset
3. Change all values
4. Load preset
5. Verify all controls restored

**Expected Results:**

- ✓ Total FB value restored
- ✓ Vibrato value restored
- ✓ Coupling value restored
- ✓ FM structure state restored
- ✓ Sound matches saved preset

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Complex Modulation Matrix

**Objective:** Create a patch using all modulation features

**Setup:**

- Cross-quad FM: ON
- Total FB: 50%
- Vibrato: 40%
- Coupling: 60%
- HyperLFO active
- All 8 voices playing

**Expected Results:**

- ✓ Rich, complex sound
- ✓ Stable operation
- ✓ CPU load acceptable
- ✓ All controls remain effective

**Pass/Fail:** ____

---

### INT-2: Performance Test

**Objective:** Verify global controls don't impact performance

**Procedure:**

1. Monitor CPU with all controls at 0%
2. Enable all controls at 100%
3. Compare CPU usage

**Expected Results:**

- ✓ Minimal CPU increase
- ✓ No audio dropouts
- ✓ UI remains responsive

**Pass/Fail:** ____

---

## Parameter Reference

| Parameter    | Range  | Function                          |
|--------------|--------|-----------------------------------|
| FM Structure | Toggle | Within-pair / Cross-quad          |
| Total FB     | 0-100% | Output → LFO freq (×20 scaling)   |
| Vibrato      | 0-100% | Global pitch wobble (0-20 Hz)     |
| Coupling     | 0-100% | Envelope → partner freq (0-30 Hz) |

## Technical Details

**Vibrato:**

- Fixed 5 Hz sine wave LFO
- Depth range: 0-20 Hz
- Applied to all voices equally

**Coupling:**

- Each voice's envelope → partner's frequency
- Depth range: 0-30 Hz
- Pairs: 1↔2, 3↔4, 5↔6, 7↔8

**Total Feedback:**

- Peak follower with 0.1s half-life
- Scaling: amount × 20
- Adds to LFO base frequencies

**Cross-Quad Routing:**

- Default: Each duo self-contained
- Cross: 1-2←7-8, 3-4↔3-4, 5-6←3-4, 7-8↔7-8

## Known Issues

None currently.

## Test Summary

**Total Tests:** 14  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
