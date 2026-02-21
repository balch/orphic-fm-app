# R Sign Remote Adjust — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add ASL sign R as a no-gate pinch modifier, and fix D/Q prefix targeting so duo and quad selections work properly.

**Architecture:** R is detected on the pincher hand and sets a `remoteAdjustArmed` flag that suppresses VoiceGateOn/Off during pinch. D/Q prefixes properly wire through `resolveControlId()` using stored `selectedDuoIndex`/`selectedQuadIndex`. EnvSpeed is extended to apply to voice groups.

**Tech Stack:** Kotlin Multiplatform, Compose UI, rule-based ASL classifier, MediaPipe ML training pipeline (Python)

---

### Task 1: Add LETTER_R to AslSign enum

**Files:**
- Modify: `core/gestures/src/commonMain/kotlin/org/balch/orpheus/core/gestures/AslSign.kt:22-33`

**Step 1: Add enum entry**

In `AslSign.kt`, add between `LETTER_Q` and `LETTER_S`:

```kotlin
LETTER_R("R", AslCategory.COMMAND),    // Remote adjust (no-gate pinch)
```

**Step 2: Add paramDisplayLabel for R**

In the `paramDisplayLabel` extension (line 72-80), no change needed — R is a COMMAND, not a PARAMETER.

**Step 3: Add targetDisplayLabel for R**

In the `targetDisplayLabel` extension (line 61-69), no change needed — R is not a target, it's a modifier.

**Step 4: Run tests**

Run: `./gradlew :core:gestures:jvmTest`
Expected: PASS (R is just a new enum entry, no logic change yet)

**Step 5: Commit**

```
feat(gestures): Add LETTER_R enum entry for remote adjust modifier
```

---

### Task 2: Add R detection to AslSignClassifier

**Files:**
- Modify: `core/gestures/src/commonMain/kotlin/org/balch/orpheus/core/gestures/AslSignClassifier.kt:154-162`
- Test: `core/gestures/src/commonTest/kotlin/org/balch/orpheus/core/gestures/AslSignClassifierTest.kt`

**Step 1: Write failing test**

In `AslSignClassifierTest.kt`, add a test for R sign detection. ASL R = index + middle extended, crossed (index tip X crosses past middle tip X). The test should create landmarks where:
- Index and middle are extended (tipY < pipY)
- Ring and pinky are curled
- Thumb is not extended
- Index tipX is on the "wrong side" of middle tipX (crossed)

For a RIGHT hand: normally index tipX < middle tipX (index is to the left). When crossed: index tipX > middle tipX.

```kotlin
@Test
fun `R sign detected when index and middle crossed`() {
    val landmarks = createDefaultLandmarks()
    // Index + middle extended, crossed: index tipX past middle tipX
    // For right hand, crossing means index tip moves right past middle tip
    landmarks[LandmarkIndex.INDEX_TIP] = HandLandmark(0.55f, 0.2f, 0f)  // right of middle
    landmarks[LandmarkIndex.INDEX_PIP] = HandLandmark(0.5f, 0.4f, 0f)
    landmarks[LandmarkIndex.INDEX_DIP] = HandLandmark(0.52f, 0.3f, 0f)
    landmarks[LandmarkIndex.MIDDLE_TIP] = HandLandmark(0.50f, 0.2f, 0f) // left of index
    landmarks[LandmarkIndex.MIDDLE_PIP] = HandLandmark(0.5f, 0.4f, 0f)
    // Ring + pinky curled
    landmarks[LandmarkIndex.RING_TIP] = HandLandmark(0.5f, 0.6f, 0f)
    landmarks[LandmarkIndex.PINKY_TIP] = HandLandmark(0.5f, 0.65f, 0f)
    // Thumb not extended (curled alongside)
    landmarks[LandmarkIndex.THUMB_TIP] = HandLandmark(0.4f, 0.45f, 0f)
    landmarks[LandmarkIndex.THUMB_IP] = HandLandmark(0.42f, 0.45f, 0f)

    val fingers = buildFingers(landmarks, Handedness.RIGHT)
    val (sign, _) = classifier.classify(landmarks, fingers, Handedness.RIGHT)
    assertEquals(AslSign.LETTER_R, sign)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :core:gestures:jvmTest --tests "*AslSignClassifierTest*R sign*"`
Expected: FAIL — R not classified yet

**Step 3: Add R classifier rule**

In `AslSignClassifier.kt`, add R detection BEFORE the V/2 check (line 154), since R has the same finger extension pattern (index+middle up, others down) but with crossing:

```kotlin
// === R: Index + middle extended and crossed, ring + pinky curled ===
// Crossed = index tipX past middle tipX (mirrored for left hand).
// Must precede V/2 (which also have index + middle extended).
index.isExtended && middle.isExtended &&
    !ring.isExtended && !pinky.isExtended && !thumb.isExtended &&
    isFingersCrossed(indexTip, middleTip, handedness) ->
    AslSign.LETTER_R to confidenceMedium
```

Add helper method:

```kotlin
/** Check if index finger crosses over middle finger (ASL R). */
private fun isFingersCrossed(
    indexTip: HandLandmark,
    middleTip: HandLandmark,
    handedness: Handedness,
): Boolean {
    // For right hand (camera-mirrored): index is normally left of middle.
    // Crossed = index tipX > middle tipX.
    // For left hand: index is normally right of middle.
    // Crossed = index tipX < middle tipX.
    return if (handedness == Handedness.RIGHT) {
        indexTip.x > middleTip.x
    } else {
        indexTip.x < middleTip.x
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :core:gestures:jvmTest`
Expected: PASS

**Step 5: Commit**

```
feat(gestures): Add R sign classification (crossed index+middle)
```

---

### Task 3: Add remoteAdjustArmed to AslInteractionEngine

**Files:**
- Modify: `core/gestures/src/commonMain/kotlin/org/balch/orpheus/core/gestures/AslInteractionEngine.kt`
- Test: `core/gestures/src/commonTest/kotlin/org/balch/orpheus/core/gestures/AslInteractionEngineTest.kt`

**Step 1: Write failing tests**

```kotlin
@Test
fun `R sign on second hand arms remote adjust — pinch does not gate`() {
    // Select voice 3 and param B on signer hand
    engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
    engine.update(listOf(gestureState(aslSign = AslSign.LETTER_B)))
    // Show R on second hand (not pinching yet)
    engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
        gestureState(aslSign = AslSign.LETTER_R),
    ))
    // R hand transitions to pinch
    val events = engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
        gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
    ))
    assertTrue(events.none { it is AslEvent.VoiceGateOn },
        "R-armed pinch should NOT gate voice on")
}

@Test
fun `R-armed pinch still emits ParameterAdjust`() {
    engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
    engine.update(listOf(gestureState(aslSign = AslSign.LETTER_B)))
    engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
        gestureState(aslSign = AslSign.LETTER_R),
    ))
    // Start pinch
    engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
        gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
    ))
    // Drag
    val events = engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
        gestureState(aslSign = null, isPinching = true, palmY = 0.3f),
    ))
    assertTrue(events.any { it is AslEvent.ParameterAdjust },
        "R-armed pinch should still emit ParameterAdjust")
}

@Test
fun `R-armed pinch release does not gate off or toggle hold`() {
    engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
    engine.update(listOf(gestureState(aslSign = AslSign.LETTER_B)))
    engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
        gestureState(aslSign = AslSign.LETTER_R),
    ))
    engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
        gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
    ), timestampMs = 100L)
    val events = engine.update(listOf(
        gestureState(aslSign = AslSign.LETTER_B),
    ), timestampMs = 200L)
    assertTrue(events.none { it is AslEvent.VoiceGateOff },
        "R-armed release should NOT gate off")
    assertTrue(events.none { it is AslEvent.HoldToggle },
        "R-armed release should NOT toggle hold")
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :core:gestures:jvmTest`
Expected: FAIL — remoteAdjustArmed not implemented

**Step 3: Implement remoteAdjustArmed**

In `AslInteractionEngine.kt`:

Add state variable:
```kotlin
// Remote adjust mode — R sign on pincher hand suppresses gate events
var remoteAdjustArmed: Boolean = false
    private set
```

In `update()`, after picking signerHand and pincherHand (line 66-70), add R detection:
```kotlin
// Check for R sign on any non-signer hand (remote adjust modifier)
val remoteHand = gestures.firstOrNull {
    it != signerHand && it.aslSign == AslSign.LETTER_R
        && it.aslConfidence >= confidenceThreshold
}
if (remoteHand != null) remoteAdjustArmed = true
```

In `processPinch()` (line 219), wrap the VoiceGateOn in a guard:
```kotlin
if (!wasPinching) {
    pinchAnchorY = hand.palmY
    lastPinchY = hand.palmY
    lastApparentSize = hand.apparentSize
    if (!remoteAdjustArmed) {
        target.voiceIndex()?.let { vi ->
            events += AslEvent.VoiceGateOn(vi)
        }
    }
}
```

In `processPinchRelease()` (line 280), wrap gate-off/hold-toggle:
```kotlin
if (remoteAdjustArmed) {
    remoteAdjustArmed = false  // consumed
} else {
    target?.voiceIndex()?.let { vi ->
        val isDoublePinch = timestampMs - lastPinchReleaseTimeMs < doublePinchWindowMs
        if (isDoublePinch) {
            events += AslEvent.HoldToggle(vi)
        } else {
            events += AslEvent.VoiceGateOff(vi)
        }
        lastPinchReleaseTimeMs = timestampMs
    }
}
```

Add to `reset()`:
```kotlin
remoteAdjustArmed = false
```

**Step 4: Run tests**

Run: `./gradlew :core:gestures:jvmTest`
Expected: PASS

**Step 5: Commit**

```
feat(gestures): Add remoteAdjustArmed flag for R-sign no-gate pinch
```

---

### Task 4: Fix D/Q targeting in resolveControlId

**Files:**
- Modify: `features/mediapipe/src/commonMain/kotlin/org/balch/orpheus/features/mediapipe/MediaPipeViewModel.kt:593-624`

**Step 1: Fix resolveControlId to use selectedDuoIndex/selectedQuadIndex**

Replace the `di` and `qi` derivation in the NUMBER branch:

```kotlin
target.category == AslCategory.NUMBER -> {
    val vi = target.voiceIndex() ?: return null
    val di = selectedDuoIndex ?: (vi / 2)
    val qi = selectedQuadIndex ?: (vi / 4)
    when (param) {
        AslSign.LETTER_M -> VoiceSymbol.duoMorph(di).controlId
        AslSign.LETTER_S -> VoiceSymbol.duoSharpness(di).controlId
        AslSign.LETTER_L -> VoiceSymbol.duoModSourceLevel(di).controlId
        AslSign.LETTER_W -> VoiceSymbol.quadVolume(qi).controlId
        AslSign.LETTER_B -> BenderSymbol.BEND.controlId
        else -> null
    }
}
```

**Step 2: Fix adjustEnvSpeed for group targets**

Replace the single-voice `adjustEnvSpeed()` with group-aware version:

```kotlin
private fun adjustEnvSpeed(deltaZ: Float) {
    val target = _selectedTarget.value ?: return
    val voiceIndices = resolveVoiceIndices(target)
    if (voiceIndices.isEmpty()) return
    for (vi in voiceIndices) {
        val controlId = VoiceSymbol.envSpeed(vi).controlId
        val current = synthController.getPluginControl(controlId)?.asFloat() ?: 0.5f
        val newValue = (current + deltaZ * ENV_SPEED_Z_SCALE).coerceIn(0f, 1f)
        synthController.setPluginControl(controlId, PortValue.FloatValue(newValue), ControlEventOrigin.MEDIAPIPE)
    }
}
```

Add helper:

```kotlin
/** Resolve voice indices for a target, respecting duo/quad selection. */
private fun resolveVoiceIndices(target: AslSign): List<Int> {
    return when {
        selectedDuoIndex != null -> {
            val di = selectedDuoIndex!!
            listOf(di * 2, di * 2 + 1)
        }
        selectedQuadIndex != null -> {
            val qi = selectedQuadIndex!!
            (qi * 4 until qi * 4 + 4).toList()
        }
        target.category == AslCategory.NUMBER -> {
            val vi = target.voiceIndex() ?: return emptyList()
            listOf(vi)
        }
        else -> emptyList()
    }
}
```

**Step 3: Run build**

Run: `./gradlew :features:mediapipe:build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
fix(gestures): Wire D/Q prefix targeting through resolveControlId and adjustEnvSpeed
```

---

### Task 5: Expose remoteAdjustArmed in UI

**Files:**
- Modify: `features/mediapipe/src/commonMain/kotlin/org/balch/orpheus/features/mediapipe/MediaPipeViewModel.kt`
- Modify: `features/mediapipe/src/commonMain/kotlin/org/balch/orpheus/features/mediapipe/AslSelectionBar.kt`

**Step 1: Add state flow for R-armed in ViewModel**

In `MediaPipeViewModel.kt`, add a state flow:
```kotlin
private val _remoteAdjustArmed = MutableStateFlow(false)
```

In the gesture processing section (where `_interactionPhase` is updated), add:
```kotlin
_remoteAdjustArmed.value = aslEngine.remoteAdjustArmed
```

Include in the `MediaPipeUiState`:
```kotlin
val remoteAdjustArmed: Boolean = false,
```

Wire into the combine that produces the state flow.

**Step 2: Update AslSelectionBar**

Add `remoteAdjustArmed: Boolean` parameter. When armed, show "R" badge on the control (pinch) slot:

```kotlin
val controlLabel = when {
    isControlling -> "\u25CF"  // filled circle
    remoteAdjustArmed -> "R"   // remote adjust armed
    else -> "Pinch"
}
```

Also update `targetLabel` to show D/Q labels when duo/quad is selected:
```kotlin
val targetLabel = when {
    selectedTarget != null && selectedDuoIndex != null ->
        AslSign.duoDisplayLabel(selectedDuoIndex)
    selectedTarget != null && selectedQuadIndex != null ->
        AslSign.quadDisplayLabel(selectedQuadIndex)
    selectedTarget != null -> selectedTarget.targetDisplayLabel
    hasModePrefix -> if (modePrefix == AslSign.LETTER_D) "DUO ?" else "QUAD ?"
    else -> "Target"
}
```

Note: This requires passing `selectedDuoIndex` and `selectedQuadIndex` through the UI state.

**Step 3: Run build**

Run: `./gradlew :features:mediapipe:build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
feat(ui): Show R indicator and D/Q labels in AslSelectionBar
```

---

### Task 6: Add R to ML training pipeline

**Files:**
- Modify: `tools/train-asl-model/prepare_data.py:22`
- Modify: `tools/train-asl-model/README.md:42-45`

**Step 1: Add R to LETTER_CLASSES**

In `prepare_data.py` line 22:
```python
LETTER_CLASSES = ["A", "B", "C", "D", "L", "M", "Q", "R", "S", "V", "W", "Y"]
```

**Step 2: Update README**

Update the gesture classes table to include R (20 → 21 total), and update total images calculation.

**Step 3: Commit**

```
feat(ml): Add R to ASL training pipeline class list
```

---

### Task 7: Final verification

**Step 1: Run all gesture tests**

Run: `./gradlew :core:gestures:jvmTest`
Expected: ALL PASS

**Step 2: Run mediapipe feature build**

Run: `./gradlew :features:mediapipe:build`
Expected: BUILD SUCCESSFUL

**Step 3: Manual testing checklist**

- [ ] Show R on second hand → "R" appears in control slot of selection bar
- [ ] R + pinch-drag → param adjusts, NO voice gates on
- [ ] R + pinch release → NO voice gates off, NO hold toggle
- [ ] D + 2 → target shows "D2"
- [ ] Q + 1 → target shows "Q1"
- [ ] D + 2 + M + R-pinch → morph adjusts on duo 2
- [ ] Q + 1 + R-pinch + Z-push → envSpeed adjusts on all quad 1 voices
- [ ] Regular pinch without R → still gates normally
