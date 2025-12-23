# Preset System Test Plan

## Overview

The preset system manages complete synth state snapshots including voice settings, global
parameters, effects, and modulation routing. Presets are stored as JSON files with timestamps.

## Features

- **Save New:** Create named presets from current state
- **Override:** Update existing preset with current state
- **Load:** Restore complete synth state
- **Delete:** Remove presets
- **Factory Presets:** Built-in starting points
- **Persistence:** JSON file storage
- **Metadata:** Creation timestamp tracking

## Test Cases

### Test 1: Preset List Display

**Objective:** Verify preset list loads and displays

**Procedure:**

1. Launch app
2. Open settings panel
3. Check preset dropdown/list

**Expected Results:**

- ✓ Factory presets visible
- ✓ Presets sorted (newest first or alphabetical)
- ✓ Preset names clear and readable
- ✓ No duplicate entries

**Pass/Fail:** ____

---

### Test 2: Load Factory Preset

**Objective:** Verify factory presets load correctly

**Procedure:**

1. Note current synth state
2. Select factory preset (e.g., "Dark_Ambient")
3. Verify parameters change
4. Listen to sound

**Expected Results:**

- ✓ All parameters updated
- ✓ Voice tunings match preset
- ✓ Effect settings match preset
- ✓ Sound character matches preset name
- ✓ Console confirms preset application

**Pass/Fail:** ____

---

### Test 3: Save New Preset

**Objective:** Create and save a new preset

**Procedure:**

1. Create custom sound:
    - Adjust voice tunings
    - Set delay times
    - Configure LFO
    - Set distortion
2. Click "New Preset"
3. Enter name: "Test_Patch_1"
4. Save

**Expected Results:**

- ✓ Preset appears in list
- ✓ Preset is selected/highlighted
- ✓ Console confirms save
- ✓ File created in preset directory

**Pass/Fail:** ____

---

### Test 4: Load Custom Preset

**Objective:** Verify custom preset recalls correctly

**Procedure:**

1. Continue from Test 3
2. Change all parameters significantly
3. Load "Test_Patch_1"
4. Verify parameters restored

**Expected Results:**

- ✓ All parameters return to saved values
- ✓ Sound matches original
- ✓ No parameters missed
- ✓ Console confirms load

**Pass/Fail:** ____

---

### Test 5: Override Preset

**Objective:** Update existing preset with current state

**Procedure:**

1. Load "Test_Patch_1"
2. Make modifications:
    - Change delay feedback
    - Adjust LFO rates
3. Click "Override"
4. Confirm override
5. Reload preset to verify

**Expected Results:**

- ✓ Override confirmation dialog
- ✓ Preset updated with new values
- ✓ Creation timestamp preserved
- ✓ Modified timestamp updated (if stored)
- ✓ Reloading shows new values

**Pass/Fail:** ____

---

### Test 6: Delete Preset

**Objective:** Remove custom preset

**Procedure:**

1. Select "Test_Patch_1"
2. Click "Delete"
3. Confirm deletion
4. Check preset list

**Expected Results:**

- ✓ Deletion confirmation dialog
- ✓ Preset removed from list
- ✓ File deleted from storage
- ✓ Selection cleared
- ✓ Console confirms deletion

**Pass/Fail:** ____

---

### Test 7: Preset Name Validation

**Objective:** Verify preset name restrictions

**Procedure:**

1. Try to save presets with problematic names:
    - Empty name
    - Very long name (>100 chars)
    - Special characters: `<>:"/\|?*`
    - Duplicate name

**Expected Results:**

- ✓ Empty name rejected
- ✓ Long names handled or truncated
- ✓ Special chars sanitized or rejected
- ✓ Duplicate names handled (prompt or auto-rename)

**Pass/Fail:** ____

---

### Test 8: Complete State Capture

**Objective:** Verify all parameters saved

**Parameters to verify:**

- Voice tunings (8)
- Voice mod depths (8)
- Voice envelope speeds (8)
- Pair sharpness (4)
- Duo mod sources (4)
- HyperLFO A & B rates
- HyperLFO mode
- HyperLFO link state
- Delay times (2)
- Delay mod depths (2)
- Delay feedback
- Delay mix
- Delay mod source
- Delay LFO waveform
- Master volume
- Drive
- Distortion mix
- FM structure
- Total feedback

**Procedure:**

1. Set all parameters to known values
2. Save preset
3. Randomize all parameters
4. Load preset
5. Verify each parameter

**Expected Results:**

- ✓ All parameters restored correctly
- ✓ No missing values
- ✓ No incorrect values

**Pass/Fail:** ____

---

### Test 9: Preset with Extreme Values

**Objective:** Verify extreme parameter values save/load

**Procedure:**

1. Set all parameters to maximum (100%)
2. Save as "Max_Preset"
3. Set all parameters to minimum (0%)
4. Save as "Min_Preset"
5. Load each and verify

**Expected Results:**

- ✓ Max preset: All values at 100%
- ✓ Min preset: All values at 0%
- ✓ No clamping or rounding errors
- ✓ Full range preserved

**Pass/Fail:** ____

---

### Test 10: Rapid Preset Switching

**Objective:** Verify stable operation when switching presets quickly

**Procedure:**

1. Load preset A
2. Immediately load preset B
3. Immediately load preset C
4. Repeat cycle rapidly 5 times

**Expected Results:**

- ✓ No crashes or hangs
- ✓ Each preset loads correctly
- ✓ No audio glitches
- ✓ UI remains responsive
- ✓ No parameter conflicts

**Pass/Fail:** ____

---

### Test 11: Preset Load During Playback

**Objective:** Verify smooth preset changes while playing

**Procedure:**

1. Play sustained chord (multiple voices)
2. While playing, load different preset
3. Listen for artifacts

**Expected Results:**

- ✓ Preset loads smoothly
- ✓ No audio dropouts
- ✓ No clicks or pops
- ✓ Parameters transition cleanly
- ✓ Voices remain stable

**Pass/Fail:** ____

---

### Test 12: Factory Preset Integrity

**Objective:** Verify factory presets are well-balanced

**Procedure:**
Test each factory preset:

1. Load preset
2. Check levels (not clipping)
3. Verify musical character
4. Test with various voice triggers

**Expected Results:**

**Each Factory Preset:**

- ✓ No clipping or distortion (unless intended)
- ✓ Clear sonic identity
- ✓ Musical and usable
- ✓ Good starting point for tweaking

**Pass/Fail:** ____

---

### Test 13: Preset Persistence After Restart

**Objective:** Verify presets survive app restart

**Procedure:**

1. Create and save "Restart_Test"
2. Note preset count
3. Close app completely
4. Relaunch app
5. Check preset list

**Expected Results:**

- ✓ "Restart_Test" still in list
- ✓ Preset count unchanged
- ✓ Preset loads correctly
- ✓ No data loss

**Pass/Fail:** ____

---

### Test 14: JSON File Validation

**Objective:** Verify preset files are valid JSON

**Procedure:**

1. Save a preset
2. Locate JSON file in filesystem
3. Open in text editor
4. Validate JSON structure

**Expected Results:**

- ✓ Valid JSON syntax
- ✓ Human-readable
- ✓ All parameters present
- ✓ Proper data types
- ✓ Includes metadata (name, timestamp)

**Pass/Fail:** ____

---

### Test 15: Corrupted Preset Handling

**Objective:** Verify graceful handling of invalid preset files

**Procedure:**

1. Manually corrupt a preset JSON file:
    - Invalid JSON syntax
    - Missing required fields
    - Invalid data types
2. Launch app
3. Attempt to load corrupted preset

**Expected Results:**

- ✓ App doesn't crash
- ✓ Error logged to console
- ✓ Corrupted preset skipped or flagged
- ✓ Other presets unaffected
- ✓ User notified (optional)

**Pass/Fail:** ____

---

### Test 16: Preset Sorting

**Objective:** Verify preset list order

**Procedure:**

1. Create multiple presets with different names
2. Observe list order

**Expected Results:**

- ✓ Consistent sort order
- ✓ Either alphabetical or by date
- ✓ Factory presets positioned appropriately
- ✓ Easy to find presets

**Pass/Fail:** ____

---

### Test 17: Currently Selected Preset Indicator

**Objective:** Verify UI shows which preset is active

**Procedure:**

1. Load various presets
2. Check visual indication

**Expected Results:**

- ✓ Active preset highlighted in list
- ✓ Preset name displayed
- ✓ Clear which preset is loaded
- ✓ Updates when switching

**Pass/Fail:** ____

---

### Test 18: Unsaved Changes Indication

**Objective:** Verify user knows when changes haven't been saved

**Procedure:**

1. Load preset
2. Modify a parameter
3. Check for unsaved indicator

**Expected Results:**

- ✓ Indicator shows unsaved changes (if implemented)
- ✓ User aware state diverged from preset
- ✓ Can choose to override or save new

**Pass/Fail:** ____

---

## Integration Tests

### INT-1: Preset Workflow

**Objective:** Complete preset creation workflow

**Procedure:**

1. Start with factory preset
2. Tweak to taste
3. Save as new preset
4. Further modifications
5. Override preset
6. Create variant
7. Save as second preset
8. Switch between both

**Expected Results:**

- ✓ Smooth workflow
- ✓ All operations work
- ✓ Both presets distinct and recallable

**Pass/Fail:** ____

---

### INT-2: Preset + MIDI Integration

**Objective:** Verify presets don't affect MIDI mappings

**Procedure:**

1. Create MIDI mappings
2. Load different presets
3. Verify MIDI still works

**Expected Results:**

- ✓ MIDI mappings independent of presets
- ✓ Presets don't overwrite MIDI config
- ✓ Both systems coexist

**Pass/Fail:** ____

---

## Factory Presets to Test

- [ ] Dark_Ambient
- [ ] Warm_Pad
- [ ] F_Minor_Drift
- [ ] (Others as available)

## Known Issues

None currently.

## Technical Details

**Storage Format:** JSON  
**Storage Location:** `composeResources/files/presets/`  
**Filename Pattern:** `PresetName.json`

**Preset Structure:**

```json
{
  "name": "String",
  "voiceTunes": [8 floats],
  "voiceModDepths": [8 floats],
  "voiceEnvelopeSpeeds": [8 floats],
  "pairSharpness": [4 floats],
  "duoModSources": [4 strings],
  "hyperLfoA": float,
  "hyperLfoB": float,
  "hyperLfoMode": string,
  "hyperLfoLink": boolean,
  "delayTime1": float,
  "delayTime2": float,
  "delayMod1": float,
  "delayMod2": float,
  "delayFeedback": float,
  "delayMix": float,
  "delayModSourceIsLfo": boolean,
  "delayLfoWaveformIsTriangle": boolean,
  "masterVolume": float,
  "drive": float,
  "distortionMix": float,
  "fmStructureCrossQuad": boolean,
  "totalFeedback": float,
  "createdAt": long,
  "modifiedAt": long
}
```

## Test Summary

**Total Tests:** 20  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________
