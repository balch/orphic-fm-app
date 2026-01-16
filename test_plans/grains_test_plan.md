# Grains Texture Synthesizer Test Plan

## Overview

The Grains module is a texture synthesizer ported from Mutable Instruments Clouds, specifically focusing on the **Looping Delay Mode**. It processes audio through granular synthesis and delay techniques, adding texture, space, and pitch manipulation to incoming audio.

## Architecture

- **Signal Flow:** Voices/Drums → StereoPan → Resonator → Distortion → **Clouds** → StereoSum → Delay → LineOut
- **GrainsUnit:** Core DSP implementing granular/looping delay processing 
- **DspCloudsPlugin:** Wrapper managing parameter control and audio routing
- **GranularProcessor:** Processes stereo audio with looping delay/granular synthesis

## Test Cases

### Test 1: Stereo Output Verification

**Objective:** Verify Clouds produces proper stereo output on both channels

**Procedure:**

1. Open Clouds panel
2. Set MIX to 100% (fully wet)
3. Set SIZE to 50%
4. Set DENSITY (feedback) to 30%
5. Play sustained chord on voices
6. Monitor left and right output channels
7. Pan voices hard left
8. Monitor right channel for Clouds processing

**Expected Results:**

- ✓ Both left and right channels produce audio
- ✓ Stereo width is preserved through Clouds
- ✓ No mono summing artifacts
- ✓ Right channel is not silent
- ✓ Spatial characteristics are maintained

**Pass/Fail:** ____

---

### Test 2: Mix (Dry/Wet) Control

**Objective:** Verify MIX parameter blends dry and wet signals correctly

**Procedure:**

1. Play sustained note
2. Set all Clouds parameters to neutral (50%)
3. MIX at 0%: Fully dry
4. MIX at 25%: Mostly dry with some texture
5. MIX at 50%: Equal blend
6. MIX at 75%: Mostly wet
7. MIX at 100%: Fully wet

**Expected Results:**

- ✓ 0%: Original signal passes through unaffected
- ✓ 50%: Equal balance of dry and processed
- ✓ 100%: Only Clouds-processed signal audible
- ✓ Smooth crossfade with no level jumps
- ✓ No clicks or pops when adjusting

**Pass/Fail:** ____

---

### Test 3: Position Parameter (Delay Time / Loop Length)

**Objective:** Verify POSITION controls delay time or loop point

**Procedure:**

1. Set MIX to 75%
2. Set DENSITY to 40% (some feedback)
3. Play short percussive note
4. POSITION at 0%: Very short delay
5. POSITION at 50%: Medium delay
6. POSITION at 100%: Longest delay

**Expected Results:**

- ✓ Low values: Short delay/loop time
- ✓ High values: Long delay/loop time  
- ✓ Audible difference in echo spacing
- ✓ Smooth parameter response
- ✓ No artifacts when changing position

**Pass/Fail:** ____

---

### Test 4: Size Parameter (Grain Size - ENHANCED ✨)

**Objective:** Verify SIZE controls grain size in both delay and freeze modes

**✨ NEW FEATURE:** SIZE now implements true granular synthesis in delay mode!

**Procedure:**

1. **Delay Mode Test:**
   - Set FREEZE to OFF
   - Set MIX to 75%
   - Set POSITION to 30% (delay time)
   - Set DENSITY to 50% (moderate grain overlap)
   - Play sustained chord or pad sound
   - SIZE at 0%: Tiny grains (5ms, grainy/AM radio effect)
   - SIZE at 25%: Small grains (130ms, stuttery texture)
   - SIZE at 50%: Medium grains (260ms, balanced granular)
   - SIZE at 75%: Large grains (390ms, smooth clouds)
   - SIZE at 100%: Maximum grains (500ms, ambient smears)

2. **Freeze Mode Test:**
   - Enable FREEZE
   - Play audio to capture into buffer  
   - SIZE at 10%: Very short loop
   - SIZE at 50%: Medium loop
   - SIZE at 90%: Long loop

**Expected Results:**

- ✓ **DELAY MODE:** SIZE dramatically changes texture character
  - Low SIZE: Grainy, gritty, stuttering texture
  - Medium SIZE: Balanced granular shimmer
  - High SIZE: Smooth, ambient, reverb-like texture
- ✓ **FREEZE MODE:** SIZE controls loop duration (as before)
- ✓ Smooth transitions between sizes
- ✓ No clicks or artifacts
- ✓ DENSITY interaction creates varying grain overlap

**Pass/Fail:** ____

**Notes:** This implements professional granular synthesis with windowedgrains, up to 12 simultaneous grains, and Hann envelope windowing for smooth texture.

---

### Test 5: Pitch Parameter

**Objective:** Verify PITCH controls pitch shifting of processed audio

**Procedure:**

1. Set MIX to 75%
2. Set POSITION to 30%
3. Set SIZE to 50%
4. Play sustained note (e.g., A440)
5. PITCH at 0%: No pitch shift (center)
6. PITCH at -50%: Downward shift
7. PITCH at +50%: Upward shift

**Expected Results:**

- ✓ 0% (center): No pitch change
- ✓ Negative values: Lower pitch
- ✓ Positive values: Higher pitch
- ✓ Pitch shift is audible and musical
- ✓ Original pitch preserved in dry signal (if MIX not 100%)

**Pass/Fail:** ____

---

### Test 6: Density Parameter (Grain Overlap & Feedback)

**Objective:** Verify DENSITY controls grain overlap density in delay mode

**✨ ENHANCED:** DENSITY now controls grain overlap (how many grains play simultaneously)

**Procedure:**

1. Set MIX to 75%
2. Set POSITION to 40%
3. Set SIZE to 50% (medium grains)
4. Play sustained chord
5. DENSITY at 0%: Sparse grains (minimal overlap, 1-2 grains)
6. DENSITY at 30%: Light texture (2-4 grains)
7. DENSITY at 60%: Dense texture (4-8 grains)
8. DENSITY at 100%: Maximum density (8-12 grains, thick cloud)

**Expected Results:**

- ✓ Low DENSITY: Sparse, distinct grains (can hear individual grains)
- ✓ Medium DENSITY: Continuous granular texture
- ✓ High DENSITY: Thick, reverb-like cloud of sound
- ✓ Smooth transitions - no sudden jumps
- ✓ Interacts musically with SIZE parameter

**Pass/Fail:** ____

**Notes:** Higher density = more CPU usage but richer texture. Grain engine limits to 12 simultaneous grains for performance.

---

### Test 7: Texture Parameter (Tonal Color Filter)

**Objective:** Verify TEXTURE controls LP/HP filtering of the granular output

**What TEXTURE does:**
- **0-50%:** Progressive low-pass filtering (darker, removes highs)
- **50%:** Neutral (wide open, no filtering)
- **50-100%:** Progressive high-pass filtering (brighter, removes lows)

**Procedure:**

1. Set MIX to 100% (fully wet to hear filter clearly)
2. Set DENSITY to 50%
3. Set SIZE to 50%
4. Play sustained chord with rich harmonic content
5. **TEXTURE Sweep Test:**
   - TEXTURE at 0%: Maximum low-pass (very dark/muffled)
   - TEXTURE at 25%: Moderate low-pass (warm, rolled-off)
   - TEXTURE at 50%: Neutral (no filtering, full spectrum)
   - TEXTURE at 75%: Moderate high-pass (bright, thin)
   - TEXTURE at 100%: Maximum high-pass (very bright/airy)

**Expected Results:**

- ✓ 0%: Dark, muffled character (bass-heavy)
- ✓ 25%: Warm tone (slight high-frequency rolloff)
- ✓ 50%: Unfiltered, full-spectrum sound
- ✓ 75%: Bright, airy character (reduced bass)
- ✓ 100%: Very bright, thin texture (minimal low frequencies)
- ✓ Smooth transitions - no sudden jumps
- ✓ Filter resonance is present at extreme settings
- ✓ Interacts musically with grain textures

**Pass/Fail:** ____

**Creative Use Cases:**
- **Dark Clouds (0-30%):** Warm, vintage delay character
- **Neutral (40-60%):** Transparent granular processing
- **Shimmer (70-100%):** Bright, ethereal textures

---

### Test 8: Freeze Toggle (Capture & Loop Mode)

**Objective:** Verify FREEZE captures buffer and switches between delay/loop modes

**What FREEZE does:**
- **OFF:** Granular delay mode - continuous flow, grains from delayed audio
- **ON:** Loop mode - captures current buffer, stops writing new audio, loops frozen content

**Critical Mode Differences:**

| Parameter | FREEZE OFF (Delay) | FREEZE ON (Loop) |
|-----------|-------------------|------------------|
| SIZE | Grain size (5-500ms) | **Loop duration** |
| POSITION | Delay time | **Loop start point** |
| Audio flow | Continuous | **Frozen/static** |

**Procedure:**

1. **Delay Mode Test (FREEZE OFF):**
   - Set MIX to 75%
   - Set SIZE to 50% (grain size)
   - Set DENSITY to 50%
   - Play chord progression
   - Verify grains are created from continuously updating input

2. **Freeze Mode Test (FREEZE ON):**
   - Continue playing chord progression
   - While notes sustain, toggle **FREEZE ON**
   - Stop playing your instrument
   - Observe: Audio continues looping/sustaining
   - **SIZE Behavior Change:** Now controls loop length
     - SIZE at 10%: Short, rhythmic loop
     - SIZE at 50%: Medium loop
     - SIZE at 90%: Long, evolving loop
   - **POSITION Behavior Change:** Now controls loop start point
     - Sweep POSITION slowly
     - Hear loop point move through frozen buffer

3. **Transition Test:**
   - Play audio
   - Toggle FREEZE ON → OFF → ON rapidly
   - Verify smooth transitions without clicks

4. **Infinite Sustain Test:**
   - Freeze a chord
   - Set PITCH to +50% (octave up)
   - Set SIZE to 20% (medium loop)
   - Result: Endless shimmer pad

**Expected Results:**

- ✓ **FREEZE ON:** Buffer freezes, audio loops indefinitely
- ✓ **FREEZE OFF:** Normal granular delay resumes
- ✓ SIZE changes behavior between modes (grain size vs loop duration)
- ✓ Frozen buffer sustains even when input stops
- ✓ No clicks or pops on freeze/unfreeze
- ✓ PITCH shifting works in both modes
- ✓ Can create infinite sustaining textures
- ✓ Loop crossfading is smooth (no zipper noise)

**Pass/Fail:** ____

**Creative Use Cases:**
- **Freeze Pad:** Capture chord, freeze, pitch shift up for shimmer
- **Granular Sampling:** Freeze drums, adjust SIZE for time-stretching
- **Live Texture:** Freeze voice/instrument for instant ambient sustain

---

### Test 9: Trigger Button (Tap Sync / Loop Reset)

**Objective:** Verify TRIGGER button functions correctly in both delay and freeze modes

**What TRIGGER does:**
- **Delay Mode (FREEZE OFF):** Tap tempo sync - captures timing for synchronized delays
- **Freeze Mode (FREEZE ON):** Loop reset - restarts playback from loop beginning

**Procedure:**

1. **Delay Mode - Tap Tempo Sync:**
   - Set FREEZE to OFF
   - Set MIX to 75%
   - Set SIZE to 40%
   - Set DENSITY to 50%
   - Play continuous audio (pad or sustained note)
   - **Press TRIGGER rhythmically** (like tapping a delay time)
     - Try quarter note timing
     - Try eighth note timing
   - Observe: Delay time syncs to your taps
   - Note: After triggering, POSITION knob is overridden
   - Verify "synchronized" mode is active (delays lock to tap time)
   
2. **Freeze Mode - Loop Reset:**
   - Freeze a long pad/chord (FREEZE ON)
   - Set SIZE to 40% (medium loop)
   - Let loop play naturally
   - **Press TRIGGER** - loop should restart from beginning
   - Press TRIGGER multiple times rapidly
   - Verify: Creates rhythmic chopping/stuttering effect
   
3. **Edge Case Tests:**
   - Press TRIGGER with no audio (shouldn't crash)
   - Toggle FREEZE while trigger is synchronized
   - Press TRIGGER at various delay times
   - Verify smooth transitions

**Expected Results:**

- ✓ **Delay mode:** TRIGGER captures tap tempo
  - Subsequent delays sync to tap timing
  - Minimum ~128 samples for valid sync
  - Overrides POSITION knob temporarily
  - Multiple taps update timing
- ✓ **Freeze mode:** TRIGGER resets loop phase
  - Loop restarts from beginning
  - No clicks on reset
  - Rapid triggering creates stutter effect
- ✓ Visual feedback (button flashes or indicates trigger)
- ✓ Works reliably with repeated presses
- ✓ No crashes with edge cases
- ✓ Momentary action (doesn't stay "on")

**Pass/Fail:** ____

**Creative Use Cases:**
- **Tap Delay:** Tap TRIGGER to musical tempo for synced echoes
- **Rhythmic Chopping:** Freeze long sample, trigger rhythmically for glitch
- **Re-sync:** Use TRIGGER to realign grains to playing rhythm

**Technical Notes:**
- Tap tempo requires >128 samples (~3ms) between triggers to register
- In delay mode, creates "synchronized" state until timeout
- Edge detection prevents continuous retriggering
- Loop reset includes crossfade for smooth transition

---

### Test 10: Signal Chain Integration - Pre-Clouds

**Objective:** Verify audio routing from Distortion to Clouds

**Signal Path:** Voices → Resonator → Distortion → **Clouds**

**Procedure:**

1. Enable Resonator (MIX 50%)
2. Set Distortion DRIVE to 40%, MIX to 50%
3. Set Clouds MIX to 50%
4. Play notes through full chain
5. Disable Resonator
6. Disable Distortion
7. Verify Clouds still receives voice audio

**Expected Results:**

- ✓ Clouds processes resonated audio
- ✓ Clouds processes distorted audio
- ✓ Clouds works with clean voice audio
- ✓ Signal chain flows correctly
- ✓ Enabling/disabling upstream effects works

**Pass/Fail:** ____

---

### Test 11: Signal Chain Integration - Post-Clouds

**Objective:** Verify audio routing from Clouds to Delay

**Signal Path:** **Clouds** → StereoSum → Delay → LineOut

**Procedure:**

1. Set Clouds MIX to 75%
2. Set Delay MIX to 0% (off)
3. Play notes, hear Clouds output
4. Set Delay MIX to 50%
5. Set Delay FEEDBACK to 40%
6. Listen for Clouds output feeding into Delay

**Expected Results:**

- ✓ Dry Clouds output is audible with Delay off
- ✓ Delay echoes the Clouds-processed signal
- ✓ Delay + Clouds create layered texture
- ✓ No feedback runaway
- ✓ Both effects complement each other

**Pass/Fail:** ____

---

### Test 12: Drum Excitation

**Objective:** Verify drums route through Clouds

**Procedure:**

1. Set Clouds MIX to 50%
2. Set POSITION to 30%
3. Set DENSITY to 40%
4. Open 808 Drums panel
5. Trigger kick drum
6. Trigger snare drum
7. Trigger hi-hat
8. Listen for Clouds processing

**Expected Results:**

- ✓ Kick creates low frequency texture
- ✓ Snare creates mid-range granulation
- ✓ Hi-hat creates high frequency shimmer
- ✓ Drums + Clouds creates hybrid rhythmic texture
- ✓ MIX controls blend correctly

**Pass/Fail:** ____

---

### Test 13: Stereo Imaging with Panned Voices

**Objective:** Verify Clouds preserves stereo image from panned input

**Procedure:**

1. Pan Voice 1 hard left
2. Pan Voice 2 hard right
3. Set Clouds MIX to 75%
4. Play both voices simultaneously
5. Listen to stereo image
6. Adjust SIZE and POSITION
7. Verify stereo separation maintained

**Expected Results:**

- ✓ Panned voices maintain left/right separation
- ✓ Clouds processes each channel independently
- ✓ Stereo width is preserved
- ✓ Granular texture respects pan positions
- ✓ No unwanted stereo collapse

**Pass/Fail:** ____

---

### Test 14: Extreme Parameter Settings

**Objective:** Verify stability at extreme settings

**Procedure:**

1. Set all Clouds parameters to 100%
2. Set MIX to 100%
3. Enable all voices
4. Set FREEZE to ON
5. Trigger drums rapidly
6. Monitor CPU and output levels

**Expected Results:**

- ✓ No crashes or freezes
- ✓ Output remains controlled (no runaway levels)
- ✓ CPU usage is reasonable (<20% increase)
- ✓ No audio dropouts
- ✓ System remains responsive

**Pass/Fail:** ____

---

### Test 15: Parameter Automation

**Objective:** Verify parameters can be modulated/automated (future feature)

**Procedure:**

1. (When automation available) Automate MIX 0→100%
2. Automate POSITION over time
3. Automate PITCH for shimmer effects
4. Play sustained audio through automation

**Expected Results:**

- ✓ Automated parameters respond smoothly
- ✓ No zipper noise or clicks
- ✓ Creates musical movement
- ✓ Automation timing is accurate

**Pass/Fail:** ____ (N/A if automation not implemented)

---

### Test 16: MIDI Learn Integration

**Objective:** Verify all Clouds controls are MIDI learnable

**Procedure:**

1. Enter MIDI Learn mode
2. Click each rotary knob (POS, SIZE, PITCH, DENS, TEX, MIX)
3. Assign MIDI CC to each
4. Test FREEZE toggle learn
5. Test TRIGGER button learn
6. Send MIDI CC values
7. Verify parameters respond

**Expected Results:**

- ✓ All 6 knobs are learnable
- ✓ FREEZE toggle is learnable
- ✓ TRIGGER button is learnable  
- ✓ MIDI control is smooth and responsive
- ✓ Control IDs are unique (clouds_position, clouds_size, clouds_pitch, clouds_density, clouds_texture, clouds_mix, clouds_freeze, clouds_trigger)

**Pass/Fail:** ____

---

### Test 17: Preset Persistence

**Objective:** Verify Clouds settings persist across sessions

**Procedure:**

1. Configure Clouds with specific values:
   - Position: 35%
   - Size: 60%
   - Pitch: -20%
   - Density: 45%
   - Texture: 70%
   - Mix: 65%
   - Freeze: OFF
2. Save preset
3. Load different preset
4. Reload saved preset
5. Verify all values restored

**Expected Results:**

- ✓ All parameter values restored correctly
- ✓ Freeze state restored
- ✓ Sound matches original configuration
- ✓ No default value overwrites

**Pass/Fail:** ____

---

### Test 18: UI Responsiveness

**Objective:** Verify UI controls respond smoothly

**Procedure:**

1. Rapidly adjust all 6 knobs
2. Toggle FREEZE rapidly
3. Press TRIGGER multiple times quickly
4. Monitor for UI lag or audio artifacts

**Expected Results:**

- ✓ Knobs respond immediately without lag
- ✓ No UI freezing
- ✓ Audio follows UI changes smoothly
- ✓ No parameter response delays
- ✓ Smooth interaction feel

**Pass/Fail:** ____

---

## Parameter Reference

| Parameter | Range | Function | Control ID |
|-----------|-------|----------|------------|
| POSITION (POS) | 0-100% | Delay time / Loop length | clouds_position |
| SIZE | 0-100% | Grain size / Diffusion amount | clouds_size |
| PITCH | -100% to +100% | Pitch shifting of processed audio | clouds_pitch |
| DENSITY (DENS) | 0-100% | Feedback / Decay amount | clouds_density |
| TEXTURE (TEX) | 0-100% | Filter / Tonal color | clouds_texture |
| MIX | 0-100% | Dry/Wet blend (0=dry, 100=wet) | clouds_mix |
| FREEZE | On/Off | Capture and loop current buffer | clouds_freeze |
| TRIGGER (TRIG) | Momentary | Reset loop / Manual grain trigger | clouds_trigger |

## Technical Details

- **Sample Rate:** 44100 Hz (matches audio engine)
- **Buffer Size:** Variable based on granular processor implementation
- **Channels:** Stereo (L/R independent processing)
- **Signal Path:** Post-distortion, pre-final delay
- **Mode:** Looping Delay (Mode 2) - hardcoded in initialization

## Known Issues

### Fixed
- ✅ **Right channel not connected** - Fixed by adding `"outputRight"` to DspCloudsPlugin outputs map
- ✅ **SIZE parameter limitation** - Implemented true granular synthesis! SIZE now controls grain size (5-500ms) in delay mode

### Potential Issues to Investigate
- **Pitch parameter mapping:** Verify pitch range is appropriate (±2 octaves currently)
- **Trigger edge detection:** Confirm trigger works reliably for manual granulation
- **Buffer length:** Verify maximum delay/loop time is sufficient
- **Mode parameter:** Currently hardcoded to mode 2 (Looping Delay) - other modes not accessible
- **CPU load:** Granular synthesis with 12 grains may be intensive - monitor on target hardware

## Integration Tests

### INT-1: Full Signal Chain Test

**Objective:** Verify complete signal path with all effects

**Signal Flow:**
Voices → StereoPan → Resonator → Distortion → Clouds → StereoSum → Delay → LineOut

**Procedure:**

1. Enable all effects with moderate settings
2. Play voices
3. Trigger drums
4. Verify all stages process correctly
5. Adjust each effect independently
6. Listen for cumulative processing

**Expected Results:**

- ✓ Each stage functions correctly
- ✓ Signal flows through complete chain
- ✓ Musical result at all combinations
- ✓ No signal dropouts

**Pass/Fail:** ____

---

### INT-2: CPU Load Test

**Objective:** Verify Clouds doesn't cause excessive CPU usage

**Procedure:**

1. Monitor CPU meter
2. Enable Clouds with MIX at 100%
3. Set complex settings (high DENSITY, granular SIZE)
4. Play multiple voices
5. Enable FREEZE
6. Monitor CPU increase

**Expected Results:**

- ✓ CPU increase is reasonable (<15%)
- ✓ No audio dropouts
- ✓ Real-time performance maintained
- ✓ No buffer underruns

**Pass/Fail:** ____

---

## Test Summary

**Total Tests:** 20  
**Passed:** ____  
**Failed:** ____  
**Blocked:** ____  
**N/A:** ____

**Critical Bug Fixed:** Right channel connection restored ✅

**Tester:** ________________  
**Date:** ________________  
**Platform:** ________________  
**Build:** ________________

## Recommendations

1. **Test stereo output first** (Test 1) - this was the critical bug
2. **Verify basic parameters** (Tests 2-7) before complex interactions
3. **Test signal chain integration** (Tests 10-11) to ensure proper routing
4. **Stress test with extreme settings** (Test 14) to verify stability
5. **Document any unexpected behavior** for future refinement

## Notes

- The Grains implementation is a simplified port focusing on Looping Delay mode
- Full Mutable Instruments Clouds has 4 modes; this port uses mode 2 exclusively
- The GranularProcessor is the core DSP engine - check its implementation for low-level behavior
- Trigger edge detection logic should be reviewed in `JsynCloudsUnit.generate()` method
