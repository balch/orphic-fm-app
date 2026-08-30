---
name: gesture-pad-overlay-design
description: Piano-key/organ-lever visual language for MediaPipe gesture pads (voice + drum) — bevel/gradient/glow spec for idle, pressed, and pinched states, plus file locations.
metadata:
  type: project
---

# Gesture Pad Overlay Design (Piano Key Aesthetic)

### Visual Language
Gesture pads (voice + drum) use a **3D piano key / organ lever** design language:
- Tall elongated rectangles with generous rounded corners (18% of width)
- Full beveled edges with lighting from above-left
- Vertical gradients (light top → dark bottom when idle, compressed when pressed)
- Horizontal bevel highlights (left edge bright, right edge shadowed)
- Top specular highlight strip (glossy material reflection, only when idle)
- Subtle inner radial glow from center (quad color energy)
- Shadow beneath key — offset (2f, 5f) when idle, (1f, 2f) when pressed
- Label positioned at 80% down the key (like embossed piano key text)
- Text has shadow for depth (1px offset, 0.6 alpha black)

### Pressed State Changes
- Shadow compresses and moves closer
- Gradient becomes more uniform (less light variation)
- Left bevel highlight disappears (key no longer catching light)
- Specular top highlight disappears
- Rim outline becomes brighter and thicker
- Alpha increases (0.50 → 0.80)

### Pinched State (Envelope Speed Control)
- All pressed-state changes PLUS:
- Glowing halo ring around key in `warmGlow` color (3.5px stroke, 0.85 alpha)
- Highest alpha (0.90) for maximum visibility
- Ring offset by 3px from key bounds

### Colors
- Voice pads colored by quad: `electricBlue` (0-3), `synthPink` (4-7), `synthGreen` (8-11)
- Drum pads: `warmGlow`

### Files
- Play mode: `features/mediapipe/src/commonMain/kotlin/org/balch/orpheus/features/mediapipe/PadOverlay.kt`
- Edit mode: `features/mediapipe/src/commonMain/kotlin/org/balch/orpheus/features/mediapipe/PadEditOverlay.kt`
- Layout: `core/gestures/src/commonMain/kotlin/org/balch/orpheus/core/gestures/DefaultPadLayouts.kt`

### Edit Mode
- Uses `RoundedCornerShape(18)` to match play mode key shape
- Labels positioned at `Alignment.BottomCenter` with `-8.dp` vertical offset
- Full key dimensions (not centered circle)
