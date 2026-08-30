---
name: djapp-tv-focus-idle-fade
description: DJ app TV region-focus borders (top bar, bottom bar, docked panels) fade out after 5s of D-pad inactivity and snap back instantly on the next key press — draw-phase-only Animatable on the shared TvFocusRegionHolder, activity observed via a root onPreviewKeyEvent that never consumes.
metadata:
  type: project
---

# DJ App TV: Region-Focus Idle Fade (2026-08-29)

Builds on [[djapp_tv_topbar_viz_theming]]'s `TvFocusRegionHolder`/`tvFocusRegionBorder`. Ask: the
region-level focus borders (NOT per-item focus plates) fade out after 5s of D-pad silence, and
reappear immediately (not animated back in) on the next key press.

## Design: extend the existing single holder, don't add a parallel mechanism
`TvFocusRegionHolder` (`RaisedAccentSurface.kt`) already tracked `current: Any?` (which ONE
container currently holds focus, via an identity check). Added to the SAME class rather than a
second holder/map:
- `val alpha = Animatable(1f)` — 1f shown, 0f faded. A plain property (not `remember`ed inside
  itself — it doesn't need to be, since the whole holder is `remember { TvFocusRegionHolder() }`'d
  once at the DjAppScreen call site, which already gives `alpha` the same one-time-construction
  lifetime a `remember { Animatable(1f) }` would).
- `var activityTick: Int by mutableIntStateOf(0)` (private set) + `fun notifyActivity() { activityTick++ }`.

Only one `alpha` for the whole holder (not per-container) because `current`'s own exclusivity
already guarantees at most one container draws a border at all — one shared fade for whichever one
that is is sufficient, no per-token bookkeeping needed.

## The draw-phase-only trick, extended correctly (order matters for perf)
`tvFocusRegionBorder`'s existing `drawWithContent { if (holder.current === token) { ... } }`
already scoped invalidation to draw-only by reading a `State` (`current`) inside the draw lambda.
Naive extension — reading `holder.alpha.value` UNCONDITIONALLY before the `current === token`
check — would have every OTHER (non-focused) container's draw scope ALSO subscribe to the shared
`alpha` Animatable, since it's one shared object read by every container's own
`tvFocusRegionBorder` call. That means all N containers (both bars + every docked panel) would
repaint on EVERY animation frame of the fade, not just the one actually showing a border. Fix:
nest the `alpha.value` read INSIDE the `current === token` block, relying on Kotlin's short-circuit
`if` (not `&&`, but same effect — the second condition/read simply never executes for a
non-matching token) so only the currently-focused container's draw scope subscribes to the
Animatable at all:
```kotlin
if (holder.current === token) {
    val fade = holder.alpha.value   // read ONLY here, not before the identity check
    if (fade > 0f) drawOutline(..., color = color.copy(alpha = color.alpha * fade), ...)
}
```
General rule: when adding a second animated `State` read to an already-draw-scoped modifier that's
called by MANY sibling instances sharing one source object, always nest the new read behind
whatever cheap identity/equality check already narrows it to "the one instance that matters" —
otherwise the new animation silently fans out its per-frame cost across every sibling.

## The timer: one LaunchedEffect keyed on activityTick, not a ticking clock
```kotlin
@Composable
private fun TvFocusIdleWatcher(holder: TvFocusRegionHolder) {
    LaunchedEffect(holder.activityTick) {
        holder.alpha.snapTo(1f)                 // instant — never animate the RETURN direction
        delay(TvFocusIdleTimeoutMs)              // 5_000L, named const in RaisedAccentSurface.kt
        holder.alpha.animateTo(0f, tween(TvFocusFadeOutMs))  // 500, only the fade-OUT animates
    }
}
```
`LaunchedEffect` cancels its previous coroutine and relaunches whenever its key changes — that
alone gives "every new key event resets the countdown" for free, no manual job-cancellation needed.
`snapTo` (not `animateTo`) for the return direction is deliberate and was an explicit requirement:
getting the border back matters more than how it returns, since a user pressing a direction must
never wonder where focus went — only fading OUT after real silence is worth animating.

## Isolating the watcher so key-driven recomposition can't reach Pulsar
`TvFocusIdleWatcher(holder)` is called as its OWN composable (a sibling of the `Column` holding the
top bar / dock / bottom bar in `DjAppScreen.kt`'s `isLargeScreen` branch), not inlined into
`DjAppScreen`'s own body. Reading `holder.activityTick` to key the `LaunchedEffect` DOES recompose
on every key event — but because that read happens inside `TvFocusIdleWatcher`'s own tiny
composition scope (which renders nothing else), recomposing it never re-invokes `DjAppScreen`'s own
body, the `Column`, the dock, or Pulsar. Compose recompose-scopes are per state-READ-site, not
per-file or per-parent-function — a state read inside a leaf composable with no other content only
invalidates that leaf. This is the same reasoning as "why previewFocused/previewRegionFocused seams
never touch a panel's actual content composition" elsewhere in this codebase, applied to a REAL
(non-preview) state source this time.

## Activity source: root onPreviewKeyEvent, verified (not assumed) to be non-interfering
`Modifier.onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown)
focusRegion.notifyActivity(); false }` on the `Column` wrapping the whole TV layout in
`DjAppScreen.kt`. Compose's key dispatch is two-phase: `onPreviewKeyEvent` tunnels root-to-focused-
leaf FIRST, then `onKeyEvent` bubbles leaf-to-root; either phase stops entirely the moment any
handler returns `true`. Before adding this, grepped every existing key handler in
`ui/widgets/` (`RotaryKnob.kt`, `SegmentedAlgoKnob.kt`, `BenderFaderWidget.kt` — the D-pad
adjust-mode implementations) and confirmed ALL of them use `onKeyEvent` (bubbling), never
`onPreviewKeyEvent` — so there is no pre-existing preview-phase handler this new one could race or
shadow. Always returning `false` means it never consumes, so the tunneling phase continues
unimpeded to the focused leaf and the normal bubbling phase (where adjust-mode logic lives) is
completely untouched. Confirmed this by tracing the actual two-phase dispatch order and grepping
for competing handlers — not by assuming "false = harmless" without checking what else was already
in the tree, per this project's "trace root cause before editing" rule applied to a UI event-dispatch
question instead of a DSP one.

**Known, confirmed (not just theoretical) gap**: `EnumDropdown.kt`'s `EnumDropdownMenu` renders via
a raw `androidx.compose.ui.window.Popup` (verified via its own import list), which is backed by a
SEPARATE composition root for focus/input purposes. Key events while a VIBE/VIZ dropdown is open
and being D-pad-navigated do NOT tunnel through the main screen's root `Column`, so
`notifyActivity()` is never called for them — the idle countdown keeps running (and could fire)
while a user is actively browsing an open dropdown. Not fixed this session (would need the same
`onPreviewKeyEvent` observer added inside the Popup's own content) — flagged as a scoped, easy
follow-up rather than silently left undiscovered. General lesson: `Popup` in Compose is not part of
the same event-dispatch tree as its logical parent for input purposes — any "observe all input at
the root" mechanism needs a second copy inside every `Popup` it should also cover.

## Verifying a real Animatable's mid-fade appearance in a headless jvmTest
`ImageComposeScene.render()` takes one static snapshot — there's no running frame clock to let a
real `Animatable.animateTo()` progress through intermediate values inside a `@Test`. Rather than
trying to drive the real holder+coroutine (this codebase's established pattern, per
`renderTvRegionFocusBorder`'s own doc comment, is "real Compose focus can't be driven across a
jvmTest's module boundary" — same applies to real animation timing), added a `previewRegionFocusAlpha:
Float = 1f` parameter alongside the existing `previewRegionFocused: Boolean` seam on all three
callers (`CollapsibleColumnPanel`, `DjTvTopBar`, `DjTvBottomBar`) — scales the SAME preview-only
`Modifier.border(...)` fallback's alpha, defaulting to 1f so every real call site is unaffected.
`DjLayoutRenderHarness.renderTvFocusFadeStates()` renders alpha ∈ {1.0, 0.5, 0.15, 0.0} for all
three container kinds, each ALSO with an item forced focused at full strength simultaneously
(`previewFocusedButton`/`previewFocusedRoute`), to visualize the actual "mixed state" question the
task asked about (does it look broken to have the region border gone but the item's own plate still
lit?). Confirmed by rendering: no, it reads fine — the item-level focus plate remains the primary
"where is my cursor" signal, the region border is legitimately secondary/decorative and safe to
dim. Needed a much taller `ImageComposeScene` than the default guess (1280x720 clipped the last of
12 stacked rows silently — no error, just missing content off the bottom of a FIXED-size scene;
bumped to 1280x1500). **Any harness test stacking many real-sized bottom-bar rows needs generous
height — `TvBottomBarMinHeight` alone is 148dp per row, with no complaint if the canvas is too
short.**

## Files
- `ui/widgets/src/commonMain/kotlin/org/balch/orpheus/ui/infrastructure/RaisedAccentSurface.kt`
  (`TvFocusRegionHolder.alpha`/`activityTick`/`notifyActivity()`, `TvFocusIdleTimeoutMs` = 5_000L,
  `TvFocusFadeOutMs` = 500, `tvFocusRegionBorder`'s nested-read restructure)
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjAppScreen.kt`
  (`TvFocusIdleWatcher`, the root `onPreviewKeyEvent`) — NOT in this task's original file list, but
  explicitly authorized by the user mid-task since the activity observer has to live at the TV
  layout root and no one else was editing it.
- `.../djapp/DjTvTopBar.kt`, `.../djapp/DjTvBottomBar.kt`,
  `ui/widgets/.../ui/panels/CollapsibleColumnPanel.kt` (`previewRegionFocusAlpha` seam each)
- `apps/djapp/shared/src/jvmTest/.../DjLayoutRenderHarness.kt` (`renderTvFocusFadeStates`)

## Verification
`./gradlew compileKotlinJvm :apps:djapp:androidApp:compileOgDebugReleaseKotlin
:apps:djapp:shared:jvmTest` green. Fade-state PNG read visually per container kind and confirmed
clean (no stray line/banding artifacts at any alpha, item plates correctly independent of the
region border's fade). Real 5s timing and on-device feel explicitly NOT verifiable from a headless
render — left for the user's own device pass, per their own framing of this task.
