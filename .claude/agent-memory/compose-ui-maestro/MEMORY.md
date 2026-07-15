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
- `OrpheusColors.bassAmber` - Bass Voice panel (`bassDarkAmber` for knob track, `bassKnobCap` for knob cap)
- `OrpheusColors.warpsGreen` - Warps cross-modulator panel

## Panel Registration Pattern
Every panel needs a `*PanelRegistration` class with `@Inject @ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())`.
- The registration class provides `panelId`, `description`, `weight`, `label`, `color`, and a `Content` composable.
- Canonical reference: `features/warps/.../WarpsPanelRegistration.kt`
- New panels: create `*PanelRegistration.kt` in the same package as the panel composable.
- `FactoryPanelSets.kt` only needs `PanelId.BASS` entries (already there) — the DI set handles rendering.

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

## ModalBottomSheet Styling Patterns
- Use `containerColor` and `contentColor` params on `ModalBottomSheet` to override the M3 surface defaults (e.g., `containerColor = OrpheusColors.deepPurple`)
- Custom `dragHandle` lambda gives a cosmically-styled pill instead of the default grey bar
- `HorizontalDivider` with `color = accent.copy(alpha = 0.25f)` creates subtle cosmic section separators
- Vertical gradient header (using `Brush.verticalGradient`) gives depth without extra composables

## Jetpack Glance Widget Patterns (DjWidget)
- Full-bleed background art: stack `Image(ContentScale.Crop, fillMaxSize)` + scrim `Box(background(SCRIM))` + content `Column` inside a root `Box(fillMaxSize)`. Do NOT use `background(imageProvider=...)` — the stacked Box approach lets you add a scrim.
- `GlanceModifier.defaultWeight()` is the only weight mechanism (no `Modifier.weight`). Use it inside Row/Column for flexible spacing between buttons.
- Timer/Chronometer visibility: wrap in `if (timerActive)` in the composable body — Glance respects Kotlin control flow. No `Visibility` modifier needed.
- Bump `TARGET_ART_PX` to ~288 for full-bleed art (old 144 was fine for thumbnail, too small for background).
- Widget sizing: `dj_widget_info.xml` `minHeight="80dp"` + `targetCellHeight="2"` for compact two-row widget.
- `ContentScale` import: `androidx.glance.layout.ContentScale` (NOT `androidx.compose.ui.layout.ContentScale`).
- Action class names (`SkipPrevAction`, `SkipNextAction`, `PlayPauseAction`, `TimerStopAction`) are kept by R8 rules — do NOT rename. Leave unused action classes in place.
- No `@Preview` for Glance — verify by compile only.

## TransitionSettingsSheet Design (Pulsar / cosmicPurple)
- File: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/TransitionSettingsSheet.kt`
- Accent: `OrpheusColors.cosmicPurple` throughout
- Header: "MORPH" (all-caps, letter-spacing 3.sp, FontWeight.ExtraBold, 14.sp) + mid-dot + one-line per-style description on same row; vertical padding 8.dp
- Style chips: INLINE Row layout (glyph 13sp + " label" 9sp), ~30dp tall (padding vertical=6.dp), 4dp gap between chips and rows
- Handoff: `HorizontalRotaryKnob` (32dp, cosmicPurple, labelSide=END, label="${handoffMs}ms", valueFormatter=null); dimmed with `Modifier.alpha(0.35f)` on wrapping Column for CUT/RANDOM
- Section gap between STYLE and HANDOFF: `HorizontalDivider` with `Modifier.padding(top = 8.dp)` (no manual Spacer)
- Random pool picker REMOVED — RANDOM implicitly uses runner's SAFE_POOL=[CUT,GAP,FADE,SCRATCH]
- SectionLabel top padding: 10.dp
- Sheet content wrapped in `verticalScroll(rememberScrollState())`
- Public contract: 5 lambdas (spec, onDismiss, onStyleChange, onHandoffMsChange, onPoolChange) — `onPoolChange` retained for API compat, not wired
- HIDDEN_STYLES = setOf(CROSSFADE) — excluded from sheet and SAFE_POOL

## OrpheusTheme Dark Posture (2026-07-14)
- `OrpheusTheme(darkTheme: Boolean = true, ...)` in `ui/theme/.../OrpheusTheme.kt` — NO LONGER follows `isSystemInDarkTheme()`. Changed because a light system theme leaked M3 light defaults into the synth UI. Parameter + `LightColorScheme` still exist for explicit opt-out.
- Every call site in the repo (~104, both app roots `App.kt`/`DjApp.kt` + all `@Preview` composables) uses bare `OrpheusTheme { ... }` — none ever passed `darkTheme` explicitly, so this default flip is safe/uniform. No other file called `isSystemInDarkTheme()`.
- If adding a new `@Preview` or app entry point: just use `OrpheusTheme { ... }`, dark is now automatic. Only pass `darkTheme = false` if you deliberately want to preview/ship the light scheme.

## NavigationSuiteScaffold Item Colors (material3-adaptive-navigation-suite 1.9.0)
- Version catalog's `material3 = "1.5.0-alpha23"` version.ref does NOT describe what's actually resolved for KMP commonMain — JB's Compose Multiplatform plugin substitutes `org.jetbrains.compose.material3:*` with its own versioning (resolved to 1.9.0 as of 2026-07; check `~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material3/` for the real cached version before trusting the catalog string).
- `NavigationSuiteScaffold(navigationSuiteItems: NavigationSuiteScope.() -> Unit, ...)` has **no scaffold-level default-item-colors parameter**. `navigationSuiteColors` only sets container/content colors, not per-item indicator/icon/text colors.
- The only per-item color hook is `NavigationSuiteScope.item(..., colors: NavigationSuiteItemColors? = null)`. Build with `NavigationSuiteDefaults.itemColors(navigationBarItemColors = NavigationBarItemDefaults.colors(indicatorColor = X), navigationRailItemColors = NavigationRailItemDefaults.colors(indicatorColor = X))` and pass `colors = ...` to **every** `item()` call (there's no way to set it once for all items).
- `item()` itself is NOT `@Composable` (it's a plain builder fn that records items into a list for later rendering) — so the `NavigationSuiteItemColors` must be computed as a `val` in the enclosing `@Composable` body *before* the `navigationSuiteItems = { }` lambda, then captured by closure. Calling `NavigationSuiteDefaults.itemColors()` (which IS `@Composable`) directly inside the lambda is a compile error.
- `NavigationBarItemDefaults.colors(indicatorColor = X)` alone (all other params omitted) safely overrides ONLY the indicator — other params default to `Color.Unspecified` and `.copy()` uses `takeOrElse` to fall back to theme-derived colors. `NavigationRailItemDefaults.colors(indicatorColor = X)` takes a different internal path (concrete token defaults, not Unspecified) but resolves to the same observable behavior.
- Icon/label content color IS independent of `colors.iconColor()`/`textColor()` **as long as** the `icon`/`label` composable lambdas pass an explicit `tint`/`color` themselves (`Icon.tint` defaults to `LocalContentColor.current`, which is what `colors` would otherwise drive via `CompositionLocalProvider` — an explicit tint always wins). Verified via `NavigationBar.kt`/`NavigationRail.kt`/`Icon.kt` source, not assumed.
- To read this library's actual source when docs/autocomplete aren't enough: `find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material3 -iname "*sources.jar"`, `unzip -q <jar> -d <scratchpad-dir>`, then `Read`/`grep` the extracted `commonMain/androidx/compose/material3/**/*.kt`. Works the same for any Compose Multiplatform artifact.
- Applied in `apps/djapp/shared/.../DjAppBottomNav.kt`: `NavIndicatorColor = OrpheusColors.neonCyan.copy(alpha = NavIndicatorAlpha)`, `NavIndicatorAlpha = 0.16f` (top-level private const, tunable).

## djapp/shared Pre-Existing Broken iOS Test Compile (as of 2026-07-14)
- `:apps:djapp:shared:compileTestKotlinIosArm64` / `compileTestKotlinIosSimulatorArm64` FAIL on unmodified `main` — unrelated to any commonMain change. Two files: `commonTest/.../variant/TabMergeTest.kt` (`List<Any>` vs `List<DjRoute>` argument-type mismatch) and `commonTest/.../vibeinfo/VibeInfoMapperTest.kt` (backtick test names containing `()`/`,` are illegal Kotlin/Native symbol characters — JVM tolerates these, K/Native doesn't). `-x test` does NOT skip these; `check`/`build` still pulls in test *compilation* (just not execution) for every KMP native target.
- Don't waste time re-diagnosing this if `:apps:djapp:shared:build` fails there — confirm via `git stash` + rerun the two specific tasks against clean `main` before assuming your change broke it. For a clean signal on a commonMain-only change, prefer targeted tasks: `compileKotlinJvm` + `compileAndroidMain` (+ `compileKotlinIosArm64`/`compileKotlinIosSimulatorArm64` for main-source-only iOS coverage) over the broad `:build`.

