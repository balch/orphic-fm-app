# Gesture Reference

Hand tracking gesture controls for the Orpheus synthesizer.
Enable gesture control from the Gesture Control panel.

## How It Works

The camera tracks your hands using MediaPipe HandLandmarker. A hybrid
classifier combines a custom ML model (trained via MediaPipe Model Maker
on all 26 ASL letters) with geometric rule-based analysis for robust sign
recognition from the 21-point landmark data. One hand selects targets and
parameters, then pinch gestures control values.

## One-Hand Selection Flow

1. **Sign a number** (1-10) to select a voice target
2. **Sign a parameter letter** (M, S, B, L, W) to select what to control
3. **Pinch + drag up/down** to adjust the selected parameter
4. **Push hand toward/away from camera** (Z-axis) simultaneously adjusts envelope speed

The breadcrumb bar at the bottom of the camera view shows your progress:
**[Target] > [Param] > [Pinch]** — each slot lights up as you complete each step.

## ASL Signs

### Numbers — Voice Selection

| Sign | Hand Shape | Action |
|------|-----------|--------|
| **1** | Index finger up | Select voice 1 |
| **2** | Index + middle (peace sign) | Select voice 2 |
| **3** | Thumb + index + middle | Select voice 3 |
| **4** | Four fingers up, thumb curled | Select voice 4 |
| **5** | All five fingers spread | Select voice 5 |
| **6** | Pinky + thumb touching, 3 middle up | Select voice 6 |
| **7** | Ring + thumb touching, others up | Select voice 7 |
| **8** | Middle + thumb touching, others up | Select voice 8 |
| **9** | Index curled, thumb touches index, others up | Select voice 9 |
| **10** | Fist with thumb flicking up (like thumbs up) | Select voice 10 |

### Letters — Full ASL Alphabet

All 26 ASL letters are recognized by the hybrid ML + rule-based classifier.
Letters with assigned functions are listed first, followed by reserved letters.

#### Active Letters

| Sign | Hand Shape | Action |
|------|-----------|--------|
| **A** | Fist, thumb alongside index finger | Deselect / clear all |
| **B** | Four fingers up together, thumb across palm | Pitch bend mode |
| **C** | Curved open hand forming C shape | System: Coupling |
| **D** | Index up, thumb touches middle finger | Duo prefix — sign D then a number |
| **E** | Fingertips curled down to touch thumb | Toggle AR Keyboard Mode |
| **H** | Index + middle horizontal together | Parameter: Hold level (quad) |
| **L** | Index + thumb at 90 degrees (L shape) | Parameter: Mod source level |
| **M** | Fist, thumb tucked under 3 fingers | Parameter: Morph |
| **Q** | Thumb + index pointing down | Quad prefix — sign Q then a number |
| **R** | Index + middle crossed | Remote adjust (no-gate pinch) |
| **S** | Fist, thumb over fingers | Parameter: Sharpness |
| **V** | Index + middle spread (peace sign) | System: Vibrato |
| **W** | Index + middle + ring spread | Parameter: Volume (quad) |
| **Y** | Thumb + pinky out (hang loose) | System: Chaos/Feedback |

#### Reserved Letters (recognized but not yet assigned)

| Sign | Hand Shape |
|------|-----------|
| **F** | Thumb + index tips touching (circle), middle + ring + pinky up |
| **G** | Index + thumb extended parallel horizontally, others curled |
| **I** | Pinky only extended, others curled |
| **K** | Index + middle spread, thumb up between them |
| **J** | Like I but pinky traces a J curve (motion sign) |
| **N** | Fist, thumb between index and middle fingers |
| **O** | Thumb + index tips meet forming O, others partially bent |
| **P** | Like K but hand points down |
| **T** | Fist, thumb between index and middle (thumb tip visible) |
| **U** | Index + middle extended together pointing up |
| **X** | Index finger hooked/bent, others curled |
| **Z** | Index extended, hand traces Z pattern (motion sign) |

### Control Gestures

| Sign | Hand Shape | Action |
|------|-----------|--------|
| **Thumbs Up** | Thumb up, all others curled | Swipe panel left |
| **Thumbs Down** | Thumb down, all others curled | Hold selected voice OFF |
| **Double Pinch** | Two quick pinches (< 400ms) | Toggle hold on/off |
| **ILY** | Thumb + index + pinky up (I Love You) | Toggle Maestro Mode |
| **R** (other hand) | Index + middle crossed | Arm remote adjust — next pinch adjusts parameter without gating |

## Pinch Controls

With a target and parameter selected:

- **Pinch** = voice gates ON immediately (sounds while pinching, stops on release)
- **Pinch + drag up/down** = adjust selected parameter while hearing it (Y-axis, ~20% screen = full range)
- **Pinch + push toward/away** = adjust envelope speed (Z-axis, simultaneous with Y)
- **Double-pinch** (two quick pinches within 400ms) = toggle hold on/off

## Remote Adjust (R Sign)

Sign **R** (crossed fingers) with your non-signing hand to arm remote adjust mode.
Your signing hand can then pinch to adjust parameters **without gating the voice** —
useful for tweaking a sound while it's already playing or held.

The breadcrumb bar shows **R** in the control slot when armed. Remote adjust is
consumed after one pinch release and must be re-armed for each adjustment.

## Duo/Quad Prefixes (D and Q Signs)

By default, voice numbers auto-derive their duo and quad:
- V1/V2 = Duo 0, V3/V4 = Duo 1, V5/V6 = Duo 2, V7/V8 = Duo 3
- V1-V4 = Quad 0, V5-V8 = Quad 1

Use **D** or **Q** as a prefix to target a specific duo or quad directly:
- **D + 1** = Duo 0, **D + 2** = Duo 1, **D + 3** = Duo 2, **D + 4** = Duo 3
- **Q + 1** = Quad 0, **Q + 2** = Quad 1

## Parameter Mapping

| Target | M (Morph) | S (Sharpness) | B (Bend) | H (Hold) | W (Volume) | L (Level) |
|--------|-----------|---------------|----------|----------|------------|-----------|
| Voice  | Duo morph | Duo sharpness | Pitch bend | Quad hold | Quad volume | Duo mod level |

System params are direct (no voice selection needed):
- **V** = Vibrato, **C** = Coupling, **Y** = Chaos/Feedback

## Tips

- Hold a sign steady for ~3 frames (~100ms) for it to register
- Close fist (A) to deselect everything and return to idle
- Hand leaving the camera also deselects after a brief hysteresis
- The breadcrumb bar shows **A=X** as a cancel hint when a selection is active

## Maestro Mode

Enter Maestro Mode by signing **ILY** (thumb + index + pinky extended, "I Love You").
Exit with **ILY** again or fist (**A**/**S**).

In Maestro Mode, each finger-to-thumb touch gates an individual voice.
Multiple fingers can touch simultaneously to play chords.

### Voice Gating (All 4 Fingers)

| Hand | Index | Middle | Ring | Pinky |
|------|-------|--------|------|-------|
| **Left** | V1 | V2 | V3 | V4 |
| **Right** | V5 | V6 | V7 | V8 |

- Touch finger to thumb → voice gates ON (sounds while touching)
- Release from thumb → voice gates OFF
- Multiple fingers touching simultaneously = chord

### Pitch Bend (String Pull)

- **Single voice touching**: horizontal fingertip offset from thumb bends that voice
- **Both voices of a duo touching** (index+middle or ring+pinky): they share a
  duo-level string bend with spring-back animation
- Release triggers a spring-back to center bend

### Hold Control (Thumbs Up / Thumbs Down)

- **Thumbs Up** or **Thumbs Down** gesture activates hold control for that hand's quad
  (left hand = Q1, right hand = Q2)
- While showing thumbs gesture, **push/pull toward camera** (Z-axis velocity) steps
  through hold detents (0, 0.4, 0.5, 0.6, 0.75)
- Returning to open hand settles hold to the nearest detent

### Continuous Controls

- **Hand height** (Y) controls dynamics — high hand = loud, low hand = quiet (auto-calibrated)
- **Hand roll** (tilt) controls global pitch bend
- **Open palm swipe** left/right switches panels

No voice selection step needed — fingers directly control individual voices.
