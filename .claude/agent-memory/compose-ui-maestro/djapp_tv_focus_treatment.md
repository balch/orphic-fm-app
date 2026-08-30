# DJ App TV: Raised-on-Filled D-pad Focus Treatment (2026-08-29)

## The ask, precisely
"Selected" for TV nav items and for RotaryDial both mean **D-pad focus** (the remote's
cursor), NOT the persistent "docked"/toggle state. Before this pass, `DjTvBottomBar`'s items
only showed DOCKED state (cyan wash) — there was no visual at all for where the D-pad cursor
currently sat, which is a real navigation usability gap, not just a polish nit. Confirmed this
reading from the task's own judgement call ("selected nav item is the analogue of the D-pad
focused dial") before implementing — the two states are orthogonal and both need to render:
docked = persistent tint, focused = prominent raised plate, independent of each other.

## LocalTvFocusChrome — cross-module TV gating for a shared widget
`ui/widgets/.../ui/infrastructure/RaisedAccentSurface.kt` defines
`val LocalTvFocusChrome = compositionLocalOf { false }`. `RotaryKnobDial` (ui/widgets, used by
every panel across Orpheus + DJ app) reads it to branch focus rendering. `DjAppScreen.kt` wraps
ONLY its `isLargeScreen` block (`CompositionLocalProvider(LocalTvFocusChrome provides true) { Column { DjTvTopBar; stage(); DjTvBottomBar } }`)
— everywhere else (phone/tablet/desktop layouts, and desktop keyboard focus even on a resized
window) it defaults false and the widget's old behavior is byte-for-byte unchanged. This is the
general pattern for "a shared low-level widget needs a TV-only visual variant, but the flag that
means 'we're on TV' lives in a downstream app module (djapp) the widget module (ui/widgets)
can't depend on" — define the compositionLocal in the shared module, default false, let the
app-level screen provide true around just the TV surface.

**Correction (2026-08-29)**: `LocalTvFocusChrome` does NOT mean "physically on a television" —
it is a LAYOUT signal, also `true` on a sufficiently large/fullscreen desktop window (confirmed via
`apps/djapp/desktopApp/.../main.kt`). A real hardware-only signal, `LocalTelevisionHardware`, was
added alongside it for anything that must be gated on actual TV hardware (e.g. glass effects a
desktop's faster GPU can afford but a TV can't). See [[djapp_tv_glass_hardware_gate]] for the full
story and the render-harness gotcha it caused.

## raisedAccentSurface modifier — generalizing AppTitleTreatment's raised language
Same file. Generalizes `AppTitleTreatment.kt`'s hardcoded `raisedFill`/`raisedBevel` (cosmicPurple
top-lit / deepPurple bottom, neonCyan bevel) into a reusable `Modifier.raisedAccentSurface(accent: Color, shape: Shape, elevation: Dp = 6.dp)`:
`.shadow(elevation, shape, clip=false, ambientColor=accent, spotColor=accent).background(verticalGradient(accent 55%→black 82%), shape).border(1.5.dp, verticalGradient(accent 95%→black 55%), shape)`.
**Gotcha**: never chain an ancestor `.clip(shape)` before this modifier — clip() clips
everything drawn by later modifiers in the chain regardless of the shadow's own `clip=false`,
so the glow gets silently clipped away. Structure as
`.then(if (focused) Modifier.raisedAccentSurface(...) else Modifier.clip(shape).background(...))`
— clip only lives in the non-raised branch. Used in `DjTvBottomBar.kt` (TvBottomBarItem) and
`DjTvTopBar.kt` (TvTopBarButton) for real Row/Column chips; RotaryKnobDial does NOT use this
modifier (see below) since it draws inside a raw `Canvas`/DrawScope, not a Modifier chain.

## RotaryKnobDial: plate drawn in DrawScope, not via Modifier
`RotaryKnobDial` (ui/widgets/.../RotaryKnob.kt) draws everything in one `Canvas`'s drawScope, so
changing the knob's *visual* footprint (a plate bigger than the knob) without changing its
*layout* size just means drawing the plate at a larger radius directly in the same drawScope —
DrawScope draws are never clipped to the composable's own bounds unless something explicitly
clips them, exactly like the pre-existing focus ring already relied on (it drew outside the
knob's own radius). No Modifier.shadow/background needed or even possible here (Canvas has no
useful modifier chain positioning for this). Focused-not-adjusting plate accent = `indicatorColor`
(same color the old ring used for that state); adjusting plate accent = `progressColor` +
`rememberInfiniteTransition` pulsing outer glow ring — this reuses the SAME color convention the
pre-TV ring already had for focused-vs-adjusting, just swaps "thin ring" for "opaque bevel plate
+ drop shadow" so it survives a bright/busy background. Verified in render PNGs: the old thin
ring nearly disappears against a bright sunset-gradient backdrop; the opaque plate does not.

## Preview/render-harness test seam: previewFocused / previewAdjusting
Compose's real focus system can't be driven from a different Gradle module without either (a)
simulating native KeyEvents into `ImageComposeScene.sendKeyEvent()` (real API, exists, but
finicky — requires the composable to already hold focus / tab order to land where you want) or
(b) plumbing the interactionSource out as a public param and emitting into it from a
LaunchedEffect (works, but two-frame async settle). Chose instead: trailing optional
`previewFocused: Boolean? = null, previewAdjusting: Boolean = false` on `RotaryKnobDial`
(internal), forwarded through the public `RotaryKnob`/`HorizontalRotaryKnob`. `null` (every real
call site) means "use live focus state" unchanged. Confirmed **zero blast radius**: whole-repo
`./gradlew compileKotlinJvm` (452 tasks) succeeds untouched — trailing optional params never
break existing positional or named call sites. Same pattern applied to
`DjTvBottomBar`/`DjTvTopBar`: real `MutableInteractionSource` + `collectIsFocusedAsState()` per
item (so real D-pad/keyboard focus works in production), PLUS a top-level
`previewFocusedRoute: DjRoute? = null` / `previewFocusedButton: TvTopBarButtonId? = null` param
on the bar composable so a render-harness test can force one specific item's focus visual
deterministically without fighting the async interaction source.

## Render harness: a synthetic busy/bright backdrop caught a real pre-existing bug
`DjLayoutRenderHarness.kt` gained `renderTvNavFocusStates()` and `renderRotaryKnobFocusStates()`,
each rendering against both flat dark AND a new `busyVizBackdrop()` (procedural vertical
sunset gradient + 5 soft radial-gradient "bokeh" circles drawn in a plain `Canvas`) to match the
task's explicit "bright and busy... sunset scene with bokeh" requirement — no real viz asset
needed, a few `drawCircle(brush = Brush.radialGradient(...))` calls are enough to prove the
point. **Reading the rendered PNGs directly with the Read tool works** — `ImageComposeScene`'s
skiko natives load fine in this environment (no fallback/skip triggered), and Read renders PNGs
visually for a multimodal model, so this is genuine visual verification, not code-reading
guesswork. Finding worth flagging separately: against the busy backdrop, the *existing*
16%-alpha `NavIndicatorColor` "docked" wash (unchanged by this task) nearly disappears — DJ/Mix
tabs are barely visible when docked-but-unfocused over a bright background, while Horn's new
raised-focus plate stays crisp. The docked indicator itself might deserve a similar opacity/plate
bump in a future pass; out of scope here since the task was specifically about focus, not the
docked-state affordance.

## Files
- `ui/widgets/src/commonMain/kotlin/org/balch/orpheus/ui/infrastructure/RaisedAccentSurface.kt` (new)
- `ui/widgets/src/commonMain/kotlin/org/balch/orpheus/ui/widgets/RotaryKnob.kt`
- `ui/widgets/src/commonMain/kotlin/org/balch/orpheus/ui/widgets/HorizontalRotaryKnob.kt`
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjTvBottomBar.kt`
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjTvTopBar.kt`
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjAppScreen.kt` (just the
  `CompositionLocalProvider` wrap around the `isLargeScreen` block)
- `apps/djapp/shared/src/jvmTest/kotlin/org/balch/orpheus/djapp/DjLayoutRenderHarness.kt`

## Not touched (flagged, not fixed)
`EnumDropdown`/`VizDropdown` (Pulsar's own VIBE/ROOT/SCALE row, the phone header's Viz selector,
`DjTvTopBar`'s duplicate VIBE dropdown) have no focus-ring concept at all today and are shared
across far more call sites than the knob; giving them the same TV-gated treatment is a
same-shaped follow-up but was out of scope for this pass (higher blast radius, no existing
focus-state hook to extend). `AppTitleTreatment`'s title button in the TV top bar is *always*
rendered raised (not focus-conditional) — a separate, older convention, left as-is.
