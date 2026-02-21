# Compose UI Maestro Memory

## CollapsibleColumnPanel Layout Pattern

### Vertical Spacing
- `CollapsibleColumnPanel` provides its own vertical spacing via `Arrangement.spacedBy(12.dp)` (line 134 in CollapsibleColumnPanel.kt)
- NEVER add manual `Spacer` elements between content items — the parent handles all spacing
- Panel content is automatically vertically centered via `Spacer(Modifier.weight(1f))` before and after content lambda (lines 147-149)

### Content Sizing in Panels
- Available content height in the 260dp header row: ~180-190dp after accounting for:
  - 16dp vertical padding (line 131)
  - "Expanded Title" text height
  - 12dp gaps between items
- Use fixed heights (e.g., `height(140.dp)`) instead of `aspectRatio()` modifiers that might overflow
- See VizPanel: uses fixed `height(32.dp)` for dropdown box (line 66 in VizPanel.kt)
- See ReverbPanel: uses fixed `size = 40.dp` for knobs, arranged in rows

### Pattern to Follow
```kotlin
CollapsibleColumnPanel(...) {
    // Control row 1 (no manual spacing needed)
    Row(...) { /* controls */ }

    // Control row 2 (spacing is automatic from parent)
    Box(modifier = Modifier.height(140.dp)) { /* fixed height content */ }

    // Status text (spacing is automatic)
    Text(...)
}
```

## Panel Accent Colors
- `OrpheusColors.synthGreen` - MediaPipe gesture panel
- `OrpheusColors.echoLavender` - Reverb panel
- `OrpheusColors.vizGreen` - VizPanel

## Gesture Pad Overlay Design (Piano Key Aesthetic)

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

## Import Cleanup
- Remove unused imports like `Spacer` and `aspectRatio` after edits
- Kotlin import organization: foundation layout first, then material3, runtime, ui
