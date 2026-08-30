---
name: collapsible-column-panel
description: CollapsibleColumnPanel layout pattern — automatic vertical spacing/centering, content-height budget inside the 260dp header row, and the canonical panel-body code shape.
metadata:
  type: project
---

# CollapsibleColumnPanel Layout Pattern

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

## Panel Registration Pattern
Every panel needs a `*PanelRegistration` class with `@Inject @ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())`.
- The registration class provides `panelId`, `description`, `weight`, `label`, `color`, and a `Content` composable.
- Canonical reference: `features/warps/.../WarpsPanelRegistration.kt`
- New panels: create `*PanelRegistration.kt` in the same package as the panel composable.
- `FactoryPanelSets.kt` only needs `PanelId.BASS` entries (already there) — the DI set handles rendering.

## Compose Style Conventions
- Remove unused imports like `Spacer` and `aspectRatio` after edits
- Kotlin import organization: foundation layout first, then material3, runtime, ui
