# Gesture Reference

Hand tracking gesture controls for the Orpheus synthesizer.
Enable gesture control from the Gesture Control panel.

## How It Works

The camera tracks your hands using MediaPipe HandLandmarker. A pure-Kotlin
classifier recognizes ASL (American Sign Language) hand signs from the
21-point landmark data. One hand selects targets and parameters, then
pinch gestures control values.

## One-Hand Selection Flow

1. **Sign a number** (1-8) to select a voice target
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

### Letters — Parameter & Mode Selection

| Sign | Hand Shape | Action |
|------|-----------|--------|
| **A** | Fist (thumb alongside) | Deselect / clear all |
| **B** | Flat hand, fingers together, thumb across | Pitch bend mode |
| **C** | Curved open hand | System: Coupling |
| **D** | Index up, thumb touches middle finger | Duo prefix — sign D then a number to target a specific duo |
| **H** | Index + middle horizontal, thumb across | Parameter: Hold level (quad) |
| **L** | Index + thumb in L-shape | Parameter: Mod source level |
| **M** | Fist, thumb under 3 fingers | Parameter: Morph |
| **Q** | Thumb + index pointing down | Quad prefix — sign Q then a number to target a specific quad |
| **R** | Index + middle crossed | Remote adjust (other hand pinch adjusts without gating) |
| **S** | Fist, thumb over fingers | Parameter: Sharpness |
| **V** | Index + middle spread (peace) | System: Vibrato |
| **W** | Index + middle + ring spread | Parameter: Volume (quad) |
| **Y** | Thumb + pinky out (hang loose) | System: Chaos/Feedback |

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

In Maestro Mode, each finger has a distinct role. Voices are grouped into
four "strings" of two voices each (String 0 = V1/V2, String 1 = V3/V4, etc.).

### String Gating (Index + Middle Fingers)

- **Index finger** touches thumb → gates strings 0-1 (voices 1-4)
- **Middle finger** touches thumb → gates strings 2-3 (voices 5-8)
- Horizontal fingertip offset from the thumb drives **per-string pitch bend**
- Release triggers a spring-back to center bend

### Continuous Controls

- **Hand height** (Y) controls dynamics — high hand = loud, low hand = quiet (auto-calibrated)
- **Hand openness** controls timbre — open palm = soft/legato, fist = sharp/staccato
- **Hand roll** (tilt) controls pitch bend (when no modifier is active)

### Ring Finger — Mod Source

- **Ring touches thumb**: hand roll routes to **mod source level** instead of bend
- **Ring double-tap** (< 300ms): cycles mod source (OFF → LFO → FM → FLUX)

### Pinky Finger — Hold

- **Pinky touches thumb**: Z-axis velocity (push/pull) steps through hold detents (0, 0.4, 0.5, 0.6, 0.75)
- **Pinky double-tap** (< 300ms): resets hold to 0

No voice selection needed — the conductor controls the whole orchestra.
