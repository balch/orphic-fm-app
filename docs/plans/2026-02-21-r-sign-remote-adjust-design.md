# R Sign "Remote Adjust" — Design

## Problem

In ASL mode, pinch-dragging to adjust a parameter always triggers `VoiceGateOn` /
`VoiceGateOff`, activating the target voice. When voices are held and the performer
wants to tweak bend, envSpeed, or other params without re-gating, there's no way
to do it. The D (duo) and Q (quad) prefix system is also broken — `selectedDuoIndex`
and `selectedQuadIndex` are stored but never used in `resolveControlId()`.

## Solution

Add ASL sign **R** as a **no-gate pinch modifier** shown on the pincher hand.
R means: "adjust the selected parameter without triggering voice gate events."
Also fix D/Q targeting so duo and quad prefixes actually work.

## Interaction Flow

### Basic R usage (single voice)
```
Left hand (signer):  3 → B              (select voice 3, select bend)
Right hand (pincher): R → pinch-drag     (adjust bend on voice 3, NO gate)
```

### R with envSpeed (Z-axis)
```
Left hand:  3                            (select voice 3)
Right hand: R → pinch + Z-push          (adjust envSpeed, NO gate)
```

### R with duo target
```
Left hand:  D → 2 → M                   (select duo 2, select morph)
Right hand: R → pinch-drag              (adjust morph on duo 2, NO gate)
```

### R with quad target + envSpeed
```
Left hand:  Q → 1                        (select quad 1)
Right hand: R → pinch + Z-push          (set envSpeed on all quad 1 voices)
```

## Engine Changes

### 1. Add `LETTER_R` to `AslSign` (category: `COMMAND`)

New enum entry. Add `fromLabel("R")` mapping for future ML support.

### 2. R detection in `AslInteractionEngine.update()`

After picking `signerHand` and `pincherHand`, check for R on any non-signer hand:

```kotlin
val remoteHand = gestures.firstOrNull {
    it != signerHand && it.aslSign == AslSign.LETTER_R
        && it.aslConfidence >= confidenceThreshold
}
if (remoteHand != null) remoteAdjustArmed = true
```

`remoteAdjustArmed` persists across frames until consumed.

### 3. Gate suppression in `processPinch()` / `processPinchRelease()`

- **Pinch start**: if `remoteAdjustArmed`, skip `VoiceGateOn`. `ParameterAdjust` and
  `EnvSpeedAdjust` still fire.
- **Pinch release**: if `remoteAdjustArmed`, skip `VoiceGateOff` and `HoldToggle`.
  Clear `remoteAdjustArmed`.

### 4. R sign classifier rule (`AslSignClassifier`)

ASL R = index + middle extended and crossed. Detection:
- Index and middle extended, ring + pinky curled (extCount == 2, same as V/2)
- Index tipX crosses past middle tipX (for right hand: index tipX < middle tipX;
  for left hand: index tipX > middle tipX)
- Differentiated from V (spread, not crossed) and NUM_2 (together, not crossed)

Confidence: medium (0.75) — crossing detection from 2D landmarks may be noisy.

### 5. ML training pipeline update

The ML gesture recognizer is already running on every frame in VIDEO (synchronous)
mode. It was switched from LIVE_STREAM (async) to work around an Eigen memory bug
in the native dylib — `Holder<Eigen::Matrix>::~Holder()` was calling mismatched
`delete` on aligned pointers when symbols were hidden. The Eigen patch
(`EIGEN_MAX_ALIGN_BYTES=0`) and `Send(Matrix&&)` value-semantics fix are already
applied in `mediapipe.patch`. VIDEO mode has zero throughput impact since the
capture loop is sequential.

The `GestureInterpreter` already fuses native GR results (when >0.7 confidence)
with the rule-based classifier (for signs the native model doesn't know, like ILY).
So adding R to both the ML model and the classifier gives the best coverage.

To add R to the ML model:
1. Add `"R"` to the class list in `prepare_data.py`
2. Re-run `prepare_data.py` + `train.py` (~2 min on M4)
3. New `gesture_recognizer.task` auto-deploys to resources

The Kaggle ASL Alphabet dataset already has R training images (3000 samples).

## D/Q Targeting Fix

### Fix `resolveControlId()` in MediaPipeViewModel

When `selectedDuoIndex` is set (via D+number), use it directly instead of deriving
`di = vi / 2`:

```kotlin
val di = selectedDuoIndex ?: (vi / 2)
val qi = selectedQuadIndex ?: (vi / 4)
```

### Fix `adjustEnvSpeed()` for group targets

Currently uses `target.voiceIndex()` for a single voice. When duo/quad is selected,
apply to all voices in the group:

- Duo N → voices `[N*2, N*2+1]`
- Quad N → voices `[N*4, N*4+1, N*4+2, N*4+3]`

## UI Changes

### Selection bar feedback

- Show "R" indicator when `remoteAdjustArmed` is true (via new state flow)
- D/Q selection: show "D2" or "Q1" in the target slot instead of just the voice number

## Files to Modify

| File | Change |
|------|--------|
| `core/gestures/.../AslSign.kt` | Add `LETTER_R` enum, `fromLabel("R")` |
| `core/gestures/.../AslSignClassifier.kt` | Add R detection rule (crossed index+middle) |
| `core/gestures/.../AslInteractionEngine.kt` | `remoteAdjustArmed` flag, R detection, gate suppression |
| `features/mediapipe/.../MediaPipeViewModel.kt` | Fix `resolveControlId()` D/Q, fix `adjustEnvSpeed()` for groups, expose `remoteAdjustArmed` state |
| `features/mediapipe/.../AslSelectionBar.kt` | Show R indicator and D/Q labels |
| `tools/train-asl-model/prepare_data.py` | Add `"R"` to class list |
| Tests: `AslInteractionEngineTest.kt`, `AslSignClassifierTest.kt` | New tests for R behavior |

## Verification

1. `./gradlew :core:gestures:jvmTest` — all gesture tests pass
2. Manual test: R + pinch adjusts params without gating
3. Manual test: D2 + M + R-pinch adjusts morph on duo 2
4. Manual test: Q1 + R-pinch + Z adjusts envSpeed on quad 1 voices
5. Selection bar shows correct labels (R indicator, D2/Q1)
