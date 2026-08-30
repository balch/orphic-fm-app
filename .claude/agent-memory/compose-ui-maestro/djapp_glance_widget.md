---
name: djapp-glance-widget
description: Jetpack Glance home-screen widget (DjWidget) patterns — full-bleed art layering, GlanceModifier.defaultWeight, timer visibility, R8-kept action classes.
metadata:
  type: project
---

## Jetpack Glance Widget Patterns (DjWidget)
- Full-bleed background art: stack `Image(ContentScale.Crop, fillMaxSize)` + scrim `Box(background(SCRIM))` + content `Column` inside a root `Box(fillMaxSize)`. Do NOT use `background(imageProvider=...)` — the stacked Box approach lets you add a scrim.
- `GlanceModifier.defaultWeight()` is the only weight mechanism (no `Modifier.weight`). Use it inside Row/Column for flexible spacing between buttons.
- Timer/Chronometer visibility: wrap in `if (timerActive)` in the composable body — Glance respects Kotlin control flow. No `Visibility` modifier needed.
- Bump `TARGET_ART_PX` to ~288 for full-bleed art (old 144 was fine for thumbnail, too small for background).
- Widget sizing: `dj_widget_info.xml` `minHeight="80dp"` + `targetCellHeight="2"` for compact two-row widget.
- `ContentScale` import: `androidx.glance.layout.ContentScale` (NOT `androidx.compose.ui.layout.ContentScale`).
- Action class names (`SkipPrevAction`, `SkipNextAction`, `PlayPauseAction`, `TimerStopAction`) are kept by R8 rules — do NOT rename. Leave unused action classes in place.
- No `@Preview` for Glance — verify by compile only.
