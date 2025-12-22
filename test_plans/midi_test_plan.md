# MIDI Integration Test Plan

## Overview

MIDI integration includes device connectivity, learn mode for mapping, note-to-voice mapping, CC-to-control mapping, and persistence of mappings per device.

## Features

- **Auto-connection:** Automatic MIDI device detection and connection
- **Learn Mode:** Interactive MIDI mapping system
- **Note Mapping:** MIDI notes trigger specific voices
- **CC Mapping:** MIDI continuous controllers map to knobs
- **Button Mapping:** MIDI notes can toggle/cycle controls
- **Persistence:** Mappings saved per-device
- **Visual Feedback:** Learn mode highlights learnable controls

## Test Cases

### Test 1: MIDI Device Detection

**Objective:** Verify automatic device detection

**Procedure:**
1. Launch app with no MIDI device connected
2. Connect MIDI device
3. Wait up to 2 seconds (polling interval)
4. Check MIDI indicator

**Expected Results:**
- ✓ Device detected automatically
- ✓ MIDI indicator shows connected
- ✓ Device name displayed
- ✓ Console logs connection

**Pass/Fail:** ____

---

### Test 2: MIDI Device Disconnection

**Objective:** Verify graceful handling of device removal

**Procedure:**
1. Connect MIDI device
2. Disconnect device while app running
3. Monitor app behavior

**Expected Results:**
- ✓ Disconnect detected within 2 seconds
- ✓ MIDI indicator shows disconnected
- ✓ No crashes or errors
- ✓ App remains functional

**Pass/Fail:** ____

---

### Test 3: MIDI Device Reconnection

**Objective:** Verify device auto-reconnects after removal

**Procedure:**
1. Connect device
2. Disconnect device
3. Reconnect same device
4. Wait for auto-connect

**Expected Results:**
- ✓ Device reconnects automatically
- ✓ Previous mappings restored
- ✓ MIDI functionality resumes
- ✓ Device name matches

**Pass/Fail:** ____

---

### Test 4: Note-to-Voice Mapping (Learn Mode)

**Objective:** Learn MIDI notes for voice triggers

**Procedure:**
1. Click LEARN button (enters learn mode)
2. Click Voice 1 trigger button (starts learning)
3. Press MIDI note C3
4. Verify mapping
5. Test: Press C3, voice 1 should trigger

**Expected Results:**
- ✓ Learn mode activates
- ✓ Voice 1 button highlighted
- ✓ Note C3 assigned to voice 1
- ✓ Console logs assignment
- ✓ Pressing C3 triggers voice 1

**Pass/Fail:** ____

---

### Test 5: CC-to-Control Mapping (Knobs)

**Objective:** Learn MIDI CC for continuous controls

**Procedure:**
1. Enter learn mode
2. Click a knob (e.g., MOD 1 on voice pair)
3. Move CC knob/slider on MIDI controller (e.g., CC 1)
4. Exit learn mode (SAVE)
5. Move CC 1 again

**Expected Results:**
- ✓ Knob highlighted in learn mode
- ✓ CC 1 assigned to MOD 1
- ✓ Console shows assignment
- ✓ Moving CC 1 controls MOD 1
- ✓ Smooth, responsive control

**Pass/Fail:** ____

---

### Test 6: CC Toggle Behavior (Buttons)

**Objective:** Verify CC buttons toggle controls

**Procedure:**
1. Map MIDI note to a toggle control (e.g., voice hold)
2. Press MIDI button once: Hold on
3. Press MIDI button again: Hold off

**Expected Results:**
- ✓ First press: Toggle ON
- ✓ Second press: Toggle OFF
- ✓ Latching behavior (ignores note-off)
- ✓ Visual feedback in UI

**Pass/Fail:** ____

---

### Test 7: CC Cycle Behavior (3-Way Switches)

**Objective:** Verify CC buttons cycle through modes

**Procedure:**
1. Map MIDI note to HyperLFO mode (3-way switch)
2. Press button repeatedly
3. Should cycle: AND → OFF → OR → AND...

**Expected Results:**
- ✓ Each press cycles to next state
- ✓ 3 states: AND, OFF, OR
- ✓ Wraps around after OR
- ✓ Visual feedback in UI

**Pass/Fail:** ____

---

### Test 8: CC Knob vs Button Detection

**Objective:** Verify jump detection distinguishes knobs from buttons

**Procedure:**
1. Map CC 1 to a continuous control
2. Move CC slider slowly (knob behavior)
   - Control should follow smoothly
3. Press CC button (0→127 jump)
   - Control should toggle, not jump to 100%

**Expected Results:**
- ✓ Slow movements: Direct mapping
- ✓ Quick jumps (0→≥0.9): Toggle/cycle behavior
- ✓ No accidental toggles from knob turns
- ✓ Buttons work reliably

**Pass/Fail:** ____

---

### Test 9: Multiple Control Mapping

**Objective:** Map multiple controls in one learn session

**Procedure:**
1. Enter learn mode
2. Map 5 different controls:
   - Voice 1 note
   - MOD 1 CC
   - Delay Time CC
   - HyperLFO A CC
   - Master Volume CC
3. Save mappings
4. Test all 5 mappings

**Expected Results:**
- ✓ All 5 mappings work independently
- ✓ No conflicts or interference
- ✓ Each control responds correctly
- ✓ Learn mode exits cleanly

**Pass/Fail:** ____

---

### Test 10: Learn Mode Cancellation

**Objective:** Verify CANCEL discards changes

**Procedure:**
1. Note existing mappings (or lack thereof)
2. Enter learn mode
3. Create several new mappings
4. Click CANCEL (not SAVE)
5. Test previous mappings

**Expected Results:**
- ✓ New mappings discarded
- ✓ Previous mappings restored
- ✓ No changes applied
- ✓ Learn mode exits cleanly

**Pass/Fail:** ____

---

### Test 11: Mapping Persistence

**Objective:** Verify mappings save and load per device

**Procedure:**
1. Create mappings for "Controller A"
2. Click SAVE
3. Restart app
4. Reconnect "Controller A"
5. Test mappings

**Expected Results:**
- ✓ Mappings saved to storage
- ✓ Mappings load on reconnect
- ✓ All mappings restored correctly
- ✓ Console confirms loading

**Pass/Fail:** ____

---

### Test 12: Per-Device Mappings

**Objective:** Verify different devices have separate mappings

**Procedure:**
1. Connect "Controller A", create mappings
2. Save mappings
3. Disconnect "Controller A"
4. Connect "Controller B"
5. Create different mappings
6. Save
7. Switch back to "Controller A"

**Expected Results:**
- ✓ Controller A: Original mappings
- ✓ Controller B: Different mappings
- ✓ No cross-contamination
- ✓ Each device has independent mapping state

**Pass/Fail:** ____

---

### Test 13: Legacy Mod Wheel (CC 1)

**Objective:** Verify CC 1 defaults to vibrato if unmapped

**Procedure:**
1. Ensure CC 1 is unmapped
2. Move mod wheel (CC 1)
3. Check vibrato knob

**Expected Results:**
- ✓ Vibrato controlled by mod wheel
- ✓ Fallback behavior works
- ✓ Can be overridden by mapping CC 1

**Pass/Fail:** ____

---

### Test 14: Learn Mode Visual Feedback

**Objective:** Verify UI provides clear learn mode indication

**Procedure:**
1. Enter learn mode
2. Observe UI changes
3. Click various controls
4. Exit learn mode

**Expected Results:**
- ✓ Learn mode indicator visible
- ✓ LEARN button highlighted
- ✓ Selected control highlighted
- ✓ Clear which control is being learned
- ✓ Non-learnable controls disabled/dimmed
- ✓ Exit restores normal UI

**Pass/Fail:** ____

---

### Test 15: MIDI Note Range

**Objective:** Verify full MIDI note range supported

**Procedure:**
1. Map voices to extreme notes:
   - Voice 1: Note 0 (C-2)
   - Voice 8: Note 127 (G8)
2. Test playback

**Expected Results:**
- ✓ Full 0-127 range supported
- ✓ No range restrictions
- ✓ All notes trigger correctly

**Pass/Fail:** ____

---

### Test 16: MIDI CC Range

**Objective:** Verify full CC range (0-127) maps correctly

**Procedure:**
1. Map CC to a knob
2. Send CC value 0: Knob at 0%
3. Send CC value 63: Knob at ~50%
4. Send CC value 127: Knob at 100%

**Expected Results:**
- ✓ 0 → 0% (minimum)
- ✓ 63 → 49.6% (midpoint)
- ✓ 127 → 100% (maximum)
- ✓ Linear mapping
- ✓ Full resolution

**Pass/Fail:** ____

---

### Test 17: MIDI Velocity

**Objective:** Verify velocity is received (even if not used)

**Procedure:**
1. Map note to voice
2. Send note with velocity 64
3. Send note with velocity 127
4. Check console logs

**Expected Results:**
- ✓ Velocity value logged
- ✓ Note triggers regardless of velocity
- ✓ System ready for future velocity mapping

**Pass/Fail:** ____

---

### Test 18: Polyphonic MIDI

**Objective:** Verify multiple simultaneous notes

**Procedure:**
1. Map 4 notes to 4 voices
2. Play all 4 notes at once (chord)
3. Hold chord

**Expected Results:**
- ✓ All 4 voices trigger
- ✓ No voice stealing
- ✓ Chord sustains
- ✓ All notes release correctly

**Pass/Fail:** ____

---

### Test 19: Learn Mode with Preset Loading

**Objective:** Verify learn mode doesn't interfere with presets

**Procedure:**
1. Create mappings in learn mode
2. While in learn mode, load a preset
3. Exit learn mode

**Expected Results:**
- ✓ Preset loads correctly
- ✓ MIDI mappings unaffected by preset
- ✓ Both systems independent

**Pass/Fail:** ____

---

### Test 20: Rapid MIDI Input

**Objective:** Verify system handles fast MIDI data

**Procedure:**
1. Map multiple CCs
2. Rapidly move multiple knobs on controller
3. Play rapid note sequences

**Expected Results:**
- ✓ No missed events
- ✓ No audio glitches
- ✓ Smooth parameter changes
- ✓ UI remains responsive

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Complete Mapping Session

**Objective:** Create full performance setup

**Procedure:**
1. Map all 8 voices to keyboard
2. Map key parameters to CCs:
   - Delay times
   - Feedback
   - Mix
   - Drive
   - Volume
   - LFO rates
3. Map mode switches to buttons
4. Save and test performance

**Expected Results:**
- ✓ Complete control from MIDI
- ✓ No computer interaction needed
- ✓ Expressive performance possible

**Pass/Fail:** ____

---

### INT-2: Mapping Backup/Restore

**Objective:** Verify mapping data integrity

**Procedure:**
1. Create complex mapping
2. Close app
3. Reopen app
4. Connect device
5. Verify mappings

**Expected Results:**
- ✓ All mappings restored
- ✓ Data persists correctly
- ✓ No corruption

**Pass/Fail:** ____

---

## Known Issues

None currently.

## Technical Details

**Polling Interval:** 2 seconds  
**Jump Threshold:** 0.9 (for button detection)  
**CC Resolution:** 128 steps (0-127)  
**Note Range:** 0-127 (C-2 to G8)  
**Storage:** Per-device JSON files  

## Test Summary

**Total Tests:** 22  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____  
**Requires MIDI Device:** Yes  

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**MIDI Device:** ________________  
**Build:** ________________
