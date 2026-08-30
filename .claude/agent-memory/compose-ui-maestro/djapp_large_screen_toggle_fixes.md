---
name: djapp-large-screen-toggle-fixes
description: Five code-review findings fixed on the large-screen-layout-toggles branch — opensAsSheet is not a "can this dock" discriminant, conditional padding vs conditional border for a reserved-space ring, double vertical overscan when a dock sits between two insetting bars, and a LaunchedEffect-vs-user-action preference race.
metadata:
  type: project
---

# DJ App Large-Screen Layout Toggle Fixes (2026-08-29)

Five review findings against `DjAppScreen.kt`/`DjTvBottomBar.kt`/`DjPanelDock.kt`/`PulsarPanel.kt`
on the `large-screen-layout-toggles` branch. All five were confirmed real (none were false
positives) — see [[djapp_tv_glass_hardware_gate]] for the PulsarPanel gate fix specifically (same
LocalTvFocusChrome-vs-LocalTelevisionHardware conflation documented there).

## `opensAsSheet` is NOT the same question as "can this route dock"
`DjNavRoutes.kt`'s `VibeInfoTab.opensAsSheet = true` unconditionally, even though on the TV/
LargeScreen layout it behaves as a genuine dock toggle (its own kdoc says so: "`opensAsSheet` only
governs the phone/tablet nav path"). `DjAppNavScaffold`'s existing `onItemClick`/`isSelected`
`when` blocks branch on `route.opensAsSheet` safely — but only because `VibeInfoTab` never actually
flows through those callbacks in phone/tablet mode (it's reached via the header title's `onClick`,
not a nav item at all).

When wiring reachability for the AI edition's `AiTab` (a `DjTabContribution` with
`opensAsSheet = true` and no dock slot) into `DjTvBottomBar` — which iterates ALL panels including
`VibeInfoTab` through one generic per-route callback — copying `DjAppNavScaffold`'s
`route.opensAsSheet` discriminant would have silently flipped `VibeInfoTab` from dock-toggle to
sheet-toggle on TV, breaking already-working behavior. **The correct discriminant is membership in
`dockablePanels` (`largeScreenPanels(tabs)`)**, not the route's own `opensAsSheet` flag:
```kotlin
isDocked = { route -> if (route in dockablePanels) route in dockedPanels.orEmpty() else route == activeSheet }
onToggle = { route -> if (route in dockablePanels) toggleDocked(route) else activeSheet = if (activeSheet == route) null else route }
panels = bottomBarPanels(dockablePanels) + tabs.filter { it.opensAsSheet }  // appends AiTab only — VibeInfoTab/EndsTab aren't in `tabs`
```
Caught this by tracing what `isDocked/onToggle` would actually do for every route already in the
panels list, not just the new one being added — a discriminant that's correct for the route you're
adding can silently break a different route already flowing through the same generic loop.

## Conditional decoration that reserves space must reserve it unconditionally
`DjTvBottomBar.kt`'s "armed" ring (`Ends` item, `PulsarPanelActions.outroArmed`) was
`Modifier.border(2.5.dp, color, shape).padding(3.dp)` applied ONLY when armed. `border()` doesn't
add layout size (draws inside existing bounds); `padding()` does — so the item measured ~6dp wider
only while armed, and the centered `Row` (`Arrangement.spacedBy(gap, CenterHorizontally)`)
re-flowed every sibling under the D-pad cursor when it flipped. Fix: make `.padding(3.dp)`
unconditional and keep only `.border(...)` conditional, in the SAME order (border outside,
padding inside) so the visual gap between ring and plate is unchanged:
```kotlin
modifier
    .then(if (armed) Modifier.border(2.5.dp, color, shape) else Modifier)
    .padding(3.dp)
```
General rule: when a conditional visual needs reserved space (a ring, an outline, an icon that
only sometimes appears), reserve the space unconditionally and make only the non-space-affecting
part (border stroke, color, icon visibility inside an already-sized slot) conditional. Verified via
`DjLayoutRenderHarness`'s `renderEndsBottomBarSignals()` — the "Real 7-item bar... SCRATCH armed"
render shows even spacing across all 7 items post-fix (was visibly asymmetric before, wider gap
before the armed item).

## A docked-between-two-bars stage doesn't need its own vertical overscan margin
`DjPanelDock.kt` padded itself by `OverscanFraction` (5%) on both axes. But in `DjAppScreen.kt`'s
TV layout, the dock is the middle `Box(weight(1f))` of `Column(DjTvTopBar, Box, DjTvBottomBar)` —
and `DjTvTopBar`/`DjTvBottomBar` each already consume `platformSafeAreaInsets()` (which, on real TV
hardware, IS the same `OverscanFraction` margin, per `PlatformSafeArea.android.kt`'s own doc
comment: "the same margin DjPanelDock already keeps clear") on their own Top/Bottom edges via
`windowInsetsPadding` — this makes each bar genuinely TALLER, so the dock's top/bottom edges are
never physical screen edges at all; they're bounded by the (already-inset) bars. Left/right ARE
still physical edges for the dock (neither bar reserves horizontal space on the dock's behalf), so
horizontal padding stays. Fix: drop `vertical = maxHeight * OverscanFraction` entirely, keep only
`horizontal = maxWidth * OverscanFraction`. General pattern: before adding an inset/margin to a
container, check whether a SIBLING already structurally guarantees that edge isn't physical
(grew via its own inset padding) — two independently-reasoned "compensate for the screen edge"
mechanisms stacked on the same edge is the double-count, not a single copy-paste.

## `LaunchedEffect` seeding state must not clobber a user action that raced it
`DjAppScreen.kt`'s `LaunchedEffect(dockablePanels) { ...; dockedPanels = saved ?: default }` reads
prefs via a suspending `load()`. A `toggleDocked()` call that lands while `load()` is still
suspended already sets a concrete (non-null) `dockedPanels` value AND persists it — but the
`LaunchedEffect` would then unconditionally overwrite it with the (stale) loaded value once
`load()` resolves, desyncing the visible dock from what's already on disk for the rest of the
session. Fix: guard the assignment with `if (dockedPanels == null)` — whichever writer reaches
`dockedPanels` first (the toggle's synchronous click handler, or the effect's post-suspend
resumption) "claims" it; since both run on the same Compose-associated dispatcher (no true thread
parallelism), a plain null-check is sufficient, no Mutex/generation-counter needed. General pattern
for "seed from an async load, but a user action might beat the load to the punch": use the
state's own null/unset sentinel as the claim flag, checked at the point the async result is about
to be applied, not at the point the load was kicked off.

## Files
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjAppScreen.kt`
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjTvBottomBar.kt`
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjPanelDock.kt`
- `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/PulsarPanel.kt`

## Verification
`./gradlew compileKotlinJvm :apps:djapp:androidApp:compileOgDebugReleaseKotlin
:apps:djapp:shared:jvmTest :features:pulsar:jvmTest` — all green. Visually confirmed the armed-ring
fix by reading `apps/djapp/shared/build/djapp-render/ends-bottombar-signals.png` (written by the
existing `renderEndsBottomBarSignals()` harness test, asserts nothing — see [[djapp_tv_mode]]).
Did not add a render-harness case for AI-tab reachability specifically (no existing harness
scenario constructs a `tabContributions` list) — verified that fix by tracing the discriminant
logic against every route in `dockablePanels` instead.

## Left unfixed, flagged only
`DjAppScreen.kt`'s `else { DjAppNavScaffold(...) }` branch (non-large-screen path) still has three
`isLargeScreen ->` conditionals (`isSelected`, `onItemClick`, the `tabs =` ternary) that are dead
code — that whole call only executes when `isLargeScreen` is false, so those branches can never
fire. Left over from before the TV top/bottom-bar split existed as its own top-level branch (see
[[djapp_tv_nav_rework]] — `DjAppNavScaffold`/its rail predecessor used to render LargeScreen too).
Harmless (always falls through correctly) but misleading to a future reader; out of scope for this
review pass (not one of the five findings), flagged via spawn_task instead of fixed inline.
