---
name: djapp-tv-nav-rework
description: DJ app TV top/bottom bar rework — unified picker, exclusive region-focus border via drawWithContent phase-skipping, Ends/Info action buttons, PulsarPanel ENDING TV gating.
metadata:
  type: project
---

# DJ App TV Nav Rework (2026-08-29, follow-up to djapp_tv_mode.md / djapp_tv_focus_treatment.md)

## Scope
Top bar: Play/Pause moved to far left with initial D-pad focus (FocusRequester + `LaunchedEffect(Unit)`,
fires once per composition — never re-request on recompose). Title centred via a 3-slot `Box` with
`Modifier.align(Alignment.Center/Start/End)` on each slot (NOT a flex Row) so the title's position is
independent of the left/right groups' differing widths. Vibe + Viz pickers moved to the right, adjacent,
unified into one new composable.

Bottom bar: gained two action buttons (Info, Ends) after the 5 existing dock toggles, separated by a
thin vertical divider (`Box.width(1.dp).height(fixed dp).background(white 15%)`) so they read as a
distinct group per an explicit user requirement, not more toggles. All 7 items doubled in size
(icon 30→56dp, label 13→24sp) per an explicit "twice the size" follow-up ask.

## TvInlinePicker — the "one composable both pickers use"
New public composable in `ui/widgets/.../EnumDropdown.kt` (same file as `EnumDropdown`, reuses its
private `EnumDropdownMenu` popup so there is still only one dropdown-menu implementation). Renders
"LABEL: value ▼" on one line (bigger fonts, `raisedAccentSurface` on focus) instead of EnumDropdown's
stacked label-above-value layout. `EnumDropdown` itself was NOT changed — zero risk to Pulsar's own
VIBE/ROOT/SCALE row or the Bass panel. DjTvTopBar's Viz picker wraps `VizFeature`'s "Random" flag (not
a real catalog entry) in a local `private sealed interface VizPickerEntry { Random, Item(viz) }` so
both Vibe and Viz can share `TvInlinePicker<T>`'s single generic entries list.

## Exclusive region-focus border — cheap, single-holder, draw-phase-only
User's final requirement (after two earlier asks — full glass fill, then per-container amplified
panelGlassChrome border — were both walked back for performance/simplicity): **at most one of
{top bar, bottom bar, each docked panel} shows a border at a time, border only (no glass fill this
round), and it must add ZERO per-frame cost** — a real-device trace showed the TV's UI thread (not
GPU) already janking at ~66ms/frame with a STATIC Pulsar panel alone.

Implementation, in `ui/widgets/.../ui/infrastructure/RaisedAccentSurface.kt`:
- `class TvFocusRegionHolder { var current: Any? by mutableStateOf(null); fun setFocused(token, focused) }`
  — single nullable field is what makes exclusivity structural, not incidental. `setFocused` only
  clears `current` if the caller's token IS `current` — guards against the OLD container's stale
  focus-lost event racing a NEW container's focus-gained event (order between two onFocusChanged
  callbacks during a focus transition is not guaranteed).
- `val LocalTvFocusRegion = compositionLocalOf<TvFocusRegionHolder?> { null }` — null everywhere except
  DjAppScreen's `isLargeScreen` branch, which does `val focusRegion = remember { TvFocusRegionHolder() }`
  and provides it alongside the existing `LocalTvFocusChrome provides true`.
- `Modifier.tvFocusRegionBorder(holder, token, color, shape, width=2.dp)` — a plain (non-composable)
  `drawWithContent { drawContent(); if (holder.current === token) drawOutline(...) }`. **The key
  performance property**: reading `holder.current` INSIDE the draw lambda (not hoisted to a `val` via
  `by` in the composable body) means the State read happens in the DRAW phase, not composition — a
  focus change invalidates and repaints only the previously- and newly-focused containers' own draw
  scope, never recomposes or relays out anything, and costs nothing on frames where focus hasn't
  moved. This is the general Compose "phase-skipping" pattern for exactly this kind of rare-event,
  many-observers state.
- Each container (`DjTvTopBar`, `DjTvBottomBar`, `CollapsibleColumnPanel`) does
  `val focusRegion = LocalTvFocusRegion.current; val focusToken = remember { Any() }`, wraps itself in
  `.focusGroup().onFocusChanged { focusRegion?.setFocused(focusToken, it.hasFocus) }` (focusGroup makes
  onFocusChanged observe the WHOLE subtree, not just the container itself — no manual threading through
  children needed), then chains `.tvFocusRegionBorder(focusRegion, focusToken, color, shape)`.
  `remember { Any() }` gives each CollapsibleColumnPanel call site (one per docked route) its own
  stable per-instance identity for free — no DjRoute or other cross-module type needed, since equality
  is by reference.
- `drawOutline` import gotcha: it's `androidx.compose.ui.graphics.drawOutline`, NOT
  `androidx.compose.ui.graphics.drawscope.drawOutline` (the intuitive guess, which doesn't exist and
  fails as "Unresolved reference" with no better error). Confirmed via foundation's own
  `Background.kt` source (`import androidx.compose.ui.graphics.drawOutline`).

## Deferred / built-then-shelved: glass fill + amplified panel border
An earlier pass in this same session built `Modifier.panelGlassChrome(liquidState, effects, color,
shape, accented, focusedDescendant)` in `LiquidInfrastructure.kt` — factoring
`CollapsibleColumnPanel`'s pre-existing `liquidVizEffects + clip + border` chain into a shared
function, AND adding a `focusedDescendant` tier so a panel's border amplified further when a child
had focus (driven by composition-phase `var hasFocusedDescendant by remember { mutableStateOf(false) }`).
The user then asked for the exclusive-single-border design above instead, citing recomposition cost.
Resolution: `panelGlassChrome` the REFACTOR is kept and still used (CollapsibleColumnPanel calls it
with only `accented = effectiveExpanded`, matching pre-session behavior byte-for-byte) — factoring
out existing behavior into a shared function is not "new glass work." The `focusedDescendant` param
still exists on `panelGlassChrome`'s signature (default `false`) but nothing passes it anymore — kept
per explicit instruction ("keep it out of the default path rather than deleting it"), not wired to
TvFocusRegionHolder. `DjTvTopBar`/`DjTvBottomBar` do NOT call `panelGlassChrome` at all (reverted to
transparent backgrounds, their pre-session look) — only the cheap border was added to bars.

## previewRegionFocused / previewFocused seams
Same established pattern as RotaryKnobDial's `previewFocused` (see djapp_tv_focus_treatment.md):
trailing optional bool, defaults false, ORs into or stands in for the real mechanism for static
preview/render-harness use. `CollapsibleColumnPanel.previewRegionFocused` and
`DjTvTopBar`/`DjTvBottomBar.previewRegionFocused` all draw a plain always-on `Modifier.border(2.dp,
color, shape)` overlay — NOT wired through a fake `TvFocusRegionHolder`, since the internal
`focusToken` is private to each call site and can't be pre-seeded from outside. This is an honest
stand-in for the visual, not a real exercise of the holder's exclusivity logic; exclusivity itself
was verified by code inspection (single nullable field) rather than a screenshot, and flagged for
real-device D-pad verification in the task report.

## PulsarPanel: showEndingControl (TV-only removal of the ENDING pill)
`PulsarPanel(..., showEndingControl: Boolean = true)` — wraps the entire ENDING pill block (the
`songEndingEnabled`/`transitionSpec`/`outroArmed` collectAsState calls, the pill Column, and the
`TransitionSettingsSheet` trigger) in `if (showEndingControl) { ... }`. Row reflows automatically
(it's a plain `Row(spacedBy(16.dp))` with 4 children, not a fixed grid) — removing one child leaves
no gap. DjAppScreen's `routePanel` lambda passes `showEndingControl = !docked` for `PulsarTab` —
`docked` is ONLY ever `true` when called from the TV dock (`DjPanelDock`'s `panelContent` callback),
never from the phone/tablet/desktop `PulsarPanel` call sites, so `!docked` is exactly "TV only."

## EndsTab route + wiring
New `DjRoute` in `DjNavRoutes.kt`, same shape as `VibeInfoTab` (`opensAsSheet = true`, never in
`djTabs`, so `largeScreenPanels()`'s `filterNot { it.opensAsSheet }` naturally excludes it from
docking). `DjTvBottomBar`'s "Ends" button label is computed inline
(`transitionSpec.style.name.lowercase().replaceFirstChar(Char::titlecase)`, or `"Plays"` when
`songEndingEnabled` is false) — mirrors `PulsarPanel`'s own pill logic exactly, duplicated rather than
extracted since it's two lines and lives in different modules (pulsar feature vs djapp shared).
Tapping Ends toggles `activeSheet` to `EndsTab`; DjAppScreen renders the EXISTING
`TransitionSettingsSheet` (unchanged, already public) when `activeSheet == EndsTab && isLargeScreen` —
zero new sheet UI, just a new trigger point reusing the Pulsar panel's own picker.

## AppTitleTreatment.forceRaised
New trailing optional param (`false` default). `val raised = onClick != null || forceRaised`. Needed
because the TV title had to become non-clickable/non-focusable ("no focusable or click on the title")
but ALSO needed the high-contrast opaque `raisedFill`/`raisedBevel` look (previously only reachable
via `onClick != null`) to survive a bright/busy visualization background — the non-raised branch is a
translucent `liquidVizEffects` tint that washes out badly over anything but flat dark chrome. The
phone header's title (`DjAppHeaderRow`) already used the raised look via a real `onClick`, so
`forceRaised` just decouples that visual from requiring a click handler.

## Files touched this session
- `apps/djapp/shared/src/commonMain/.../DjTvTopBar.kt` (near-total rewrite)
- `apps/djapp/shared/src/commonMain/.../DjTvBottomBar.kt` (Info/Ends added, sizing doubled, border)
- `apps/djapp/shared/src/commonMain/.../DjAppScreen.kt` (TV branch wiring, TvFocusRegionHolder,
  Ends sheet render, `showEndingControl = !docked` on PulsarTab)
- `apps/djapp/shared/src/commonMain/.../DjNavRoutes.kt` (new `EndsTab`)
- `features/pulsar/src/commonMain/.../PulsarPanel.kt` (`showEndingControl` param)
- `ui/widgets/src/commonMain/.../ui/infrastructure/LiquidInfrastructure.kt` (`panelGlassChrome`,
  unused-but-kept `focusedDescendant` param)
- `ui/widgets/src/commonMain/.../ui/infrastructure/RaisedAccentSurface.kt` (`TvFocusRegionHolder`,
  `LocalTvFocusRegion`, `tvFocusRegionBorder`)
- `ui/widgets/src/commonMain/.../ui/panels/CollapsibleColumnPanel.kt` (region-focus wiring,
  `previewRegionFocused`)
- `ui/widgets/src/commonMain/.../ui/widgets/AppTitleTreatment.kt` (`forceRaised`)
- `ui/widgets/src/commonMain/.../ui/widgets/EnumDropdown.kt` (`TvInlinePicker`)
- `apps/djapp/shared/src/jvmTest/.../DjLayoutRenderHarness.kt` (`renderTvRegionFocusBorder()` new test)

## Verification
`:apps:djapp:shared:jvmTest` (render harness — PNGs actually read via the Read tool, confirms bar
sizing/spacing/contrast/border visually, not just compile), `:apps:djapp:androidApp:compileOgDebugReleaseKotlin`,
whole-repo `compileKotlinJvm` (452 tasks, proves the ui/widgets changes didn't ripple into the Orpheus
app). All three run together in one Gradle invocation cleanly. Real-device check still needed:
D-pad-driven region border exclusivity (the render harness can only preview-force one border at a
time per composable, not exercise real overlapping focus transitions), and general TV frame-rate
impact of the two new `onFocusChanged` + `drawWithContent` modifiers added per docked panel (expected
negligible given they're pure draw-phase reads, but not measured on-device by this pass).

## Follow-up round (2026-08-29, same day): Ends-as-panel, TvGlassEnabled, bigger top bar, Timer focus gap

### Ends became a real dockable panel, not a sheet
`EndsTab.opensAsSheet` flipped to `false` (default). `largeScreenPanels()` now appends BOTH
`VibeInfoTab` and `EndsTab` explicitly (`listOf(PulsarTab) + tabs.filterNot{it.opensAsSheet} + VibeInfoTab + EndsTab`)
— a mid-task clarification revealed `VibeInfoTab` was ALREADY being docked in practice (the
bottom bar's old "Info" button called `toggleDocked(VibeInfoTab)`, which never checked
`dockablePanels` membership before mutating `dockedPanels`), just with its bar item hardcoded to
`docked = false` so the visual never reflected it and it wouldn't survive an app-restart
preference reload (`byLabel = dockablePanels.associateBy{label}` couldn't find it). Fixed both by
making Info a genuine member of the same list, not a special-cased action.

`EndsPanel` (new public composable, `features/pulsar/.../TransitionSettingsSheet.kt`, same file as
`TransitionSettingsSheet`) wraps `CollapsibleColumnPanel(title="ENDS", color=cosmicPurple)` around
the EXACT SAME private `StyleChips`/`HandoffSection` composables the sheet uses — zero duplicated
control logic, only the host container changed. `DjTvBottomBar`'s `BottomBarOrder` is now
`[DjTab, MixTab, PulsarTab, HornTab, TimerTab, VibeInfoTab, EndsTab]` — ALL SEVEN are plain dock
toggles rendered by one `panels.forEach` loop, no divider, no special "action" group left (an
earlier framing had Info/Ends as sheet-triggering actions after a divider; a mid-task correction
from the user explicitly said "Info is ALREADY a panel... ignore any earlier framing about it
being the only non-dock action" — ripped out the divider and `TvBottomBarDividerHeight` entirely
once BOTH items became real toggles).

Ends' bar label must read as the exact bare enum name Pulsar's own ENDING pill uses — literally
copy the expression, not a derived transform: `if (songEndingEnabled) transitionSpec.style.name else "PLAYS"`
(NOT `.lowercase().replaceFirstChar(titlecase)` — an earlier pass in this same round did that
title-case transform and had to be walked back when the user said "match that labelling exactly
so the two never disagree"). Reusing the identical source expression, not just the same *shape* of
expression, is what actually guarantees they can't drift apart.

### Three-way independent signal separation — a recurring pattern in this codebase
The Ends bottom-bar item ended up needing THREE simultaneous, mutually-legible signals: docked
(persistent cyan wash/tint), D-pad-focused (raised plate), and "song ending in progress" (armed —
`PulsarPanelActions.outroArmed`, the SAME StateFlow Pulsar's own ENDING pill already reads, so the
two indicators share one source of truth and can't disagree). The fix: armed gets its OWN outer
visual channel that touches neither tint nor plate/wash — an outer `Box` that, only when armed,
draws `.border(2.5.dp, accent.copy(alpha=0.9f), shape).padding(3.dp)` around the existing
docked/focused Column, mirroring the "pinched state" glowing-halo-ring convention already
documented for the gesture pad overlays (`PadOverlay.kt`/`PadEditOverlay.kt`) elsewhere in this
memory file's Gesture Pad section. **General lesson, confirmed working via a dedicated 7-way
combinatorial render (`ends-bottombar-signals.png`)**: when N independent boolean signals must all
read simultaneously without collapsing into each other, give each one its own layer/property
(tint vs background-fill-vs-plate vs outer-ring), never try to encode two states in one channel
(e.g. don't try to make "armed" a tint change — it would compete with docked/focused tint).

### TvGlassEnabled — single switch, gates ONLY panelGlassChrome
`const val TvGlassEnabled = false` lives in `ui/widgets/.../ui/infrastructure/RaisedAccentSurface.kt`,
right next to `LocalTvFocusChrome` (same file already hosted the TV-gating compositionLocal, so
this is "the one obvious place"). `panelGlassChrome` (`LiquidInfrastructure.kt`) gained a
`glassEnabled: Boolean = true` param — every non-TV caller is unaffected by the default; when
`false` it skips `liquidVizEffects` (the actual GPU-cost blur/frost via the `liquid` library) and
substitutes `Modifier.clip(shape).background(OrpheusColors.panelSurface)`, an opaque flat fill at
the identical clip/shape so layout metrics never move. `CollapsibleColumnPanel` computes
`glassEnabled = !LocalTvFocusChrome.current || TvGlassEnabled` and passes it through — this is the
ONLY call site that needed wiring, because `panelGlassChrome` is ONLY used by
`CollapsibleColumnPanel` in the whole repo (verified by grep), and `CollapsibleColumnPanel` is what
EVERY docked TV panel (DJ/Mix/Pulsar/Horn/Timer/VibeInfo/Ends) goes through.
**Confirmed scope by grep + reading**: `DjTvTopBar`/`DjTvBottomBar` never call `panelGlassChrome` at
all (an earlier pass in this same nav-rework reverted them to transparent backgrounds) and
`DjTvTopBar`'s one `LocalLiquidEffects.current` read only feeds text-styling metadata
(`titleSize`/`titleColor`) into `AppTitleTreatment`, which the TV top bar always calls with
`forceRaised=true` — so it never takes the translucent `liquidVizEffects` branch regardless of the
switch. **Net: `panelGlassChrome` on docked panels is the ONLY glass/translucency effect reachable
from the TV path**, full stop — nothing else needed gating. Verified visually with a dedicated
side-by-side render (`glass-switch-comparison.png`): identical panel content over a busy backdrop,
translucent-bleed-through on the left (non-TV / glass forced on) vs fully opaque block on the
right (TV path, switch at its current `false`).

### tvIdlePlate — opaque-even-when-idle, for top-bar contrast
User's complaint: Play/Pause and the Vibe/Viz pickers were "dimmer/lower-contrast than the title."
Root cause: the title ALREADY got an always-opaque treatment via `AppTitleTreatment.forceRaised`
(from the prior nav-rework round), but Play/Pause and `TvInlinePicker` only went opaque WHEN
FOCUSED (`raisedAccentSurface`) — idle state had no background at all (Play/Pause) or a translucent
`darkVoid.copy(alpha=0.6f)` fill (`TvInlinePicker`), both of which wash out over a bright/busy viz.
Fix: new `Modifier.tvIdlePlate(accent, shape)` in `RaisedAccentSurface.kt` — `clip + background(OrpheusColors.panelSurface) + border(1.dp, accent.copy(alpha=0.4f))`,
a flat OPAQUE (not glass, not toggled by TvGlassEnabled — there's no blur here to toggle) fill used
for the NOT-focused state of `TvTopBarButton` (DjTvTopBar.kt) and `TvInlinePicker`
(EnumDropdown.kt), replacing their old "nothing" / translucent-tint idle looks. Focus still upgrades
to the brighter `raisedAccentSurface` plate — idle and focused are now both opaque, same shape, so
nothing changes size when focus arrives, just brightness/glow. New shared `TvTopBarControlHeight = 52.dp`
constant in `DjTvTopBar.kt` — title, Play/Pause, and both pickers all reference (or already matched)
this exact value so the row reads as one weight class, not "big title, small everything else."
`TvInlinePicker`'s fonts bumped (label 15→16sp, value 17→19sp, icon 22→24dp) for the same
readability ask.

### Timer panel: same TV-focus gap RotaryKnobDial already had, applied to IconButtons
`TimerPanel`'s Start/Stop and Reset were plain M3 `IconButton`s with zero focus visual — a
same-shaped gap to what RotaryKnobDial had before the earlier focus-treatment round. Fixed with a
new private `TimerTransportButton` (same file) that wraps a real `IconButton` in an outer `Box`:
off TV (`LocalTvFocusChrome.current == false`) the Box carries NO modifier at all — literally
pixel-identical to the old bare `IconButton` call, not just "looks the same." On TV: focused →
`raisedAccentSurface(accent, CircleShape)`; not-focused-but-`active` (Start/Stop only, when
RUNNING or PAUSED) → a static `clip(CircleShape).background(tint.copy(alpha=0.18f))` wash; neither
→ plain `Modifier`. `active` and focus are DELIBERATELY two separate booleans feeding two
non-overlapping branches (same three-way-signal-separation principle as Ends above, minus the
outer-ring trick since there are only two signals here, not three) — Reset never receives `active`
at all (momentary action, focus is its only signal). New `TimerTransportButtonId {START_STOP, RESET}`
enum + `previewFocusedButton` param on `TimerPanel`, mirroring the established
`TvTopBarButtonId`/`previewFocusedButton` seam pattern exactly.

### Audit: other focusable controls inside docked panels with the SAME missing-TV-treatment gap
Explicitly surveyed but NOT fixed this round (reported to the user as a punch list):
- `EnumDropdown` (`ui/widgets/.../EnumDropdown.kt`) — Pulsar's own VIBE/ROOT/SCALE row uses this
  directly (TV does NOT use `TvInlinePicker` there, only the top bar's duplicate Vibe picker does).
  A `combinedClickable` chip with zero focus-ring concept; same gap `TvInlinePicker` used to have.
  Higher blast radius to fix (shared across Bass panel too) — flagged, not touched.
- Pulsar's inline ENV mode toggle (`Modifier.clickable{}` Box cycling AD/WAVES/BLEND) — no focus
  visual at all.
- `EnginePickerButton` (`ui/widgets/.../VoiceEnginePickerPopup.kt`, Pulsar's HI/LO track engine
  picker) — a custom gesture-driven ring/popup control; unclear it's even D-pad-focusable at all
  (looked gesture/pointerInput-driven, not a standard focusable target) — a *reachability* gap,
  not just a visual one.
- `PulsarStepGrid`'s track-selection tap targets — same reachability question as above; the grid
  looks pointer/tap-driven with no visible focusable node per cell.
- `HorizontalFader` (used by `EndsPanel`'s own new HANDOFF row via `HandoffSection`, and by
  `MixerFader`) — drag-based, no focus-ring concept checked/found.
- `DjPanel`'s per-deck source `DropdownMenu` (stock M3 `DropdownMenu`/`DropdownMenuItem`) — default
  M3 look, no raised-plate treatment.
Pattern is clearly "any focusable control living INSIDE a docked panel's content never got the
TV pass" — only the panel CONTAINER (via `tvFocusRegionBorder`) and top-level nav bars/rotary
knobs got it. Worth a dedicated future pass rather than fixing ad hoc.

### Render harness additions this round (`DjLayoutRenderHarness.kt`)
`renderGlassSwitchComparison()`, `renderEndsPanel()`, `renderEndsBottomBarSignals()` (7-way
docked×focused×armed combinatorial grid at FIXED 200dp swatch width — `DjTvBottomBar` fillMaxWidth()s
internally so an unweighted Column in a Row will fight its siblings for the whole row's width
unless given an explicit `.width()`; the real bar itself needs no such constraint since nothing
forces it wider than its natural content), `renderTimerTransportFocusStates()`. `renderFullTvScreen`
now wraps its content in `CompositionLocalProvider(LocalTvFocusChrome provides true)` (it didn't
before — that omission would have silently rendered the ALWAYS-glass non-TV panel look, defeating
the entire point of a "verify TvGlassEnabled" render pass) and bumped its docked-count fixture from
`[0,2,5]` to `[0,3,7]` to reach the new 7-panel total including Ends.

## PulsarPanel Row 1 (VIBE/ROOT/SCALE/ENV): dropped entirely on TV (2026-08-29, same day)

Row 1 (the block comment `// Row 1: Selectors only`, ~line 118) is now wrapped in
`if (!LocalTvFocusChrome.current) { Row(...) { ... } }` — read DIRECTLY inside `PulsarPanel.kt`'s
body, not threaded in as a parameter. This is a DIFFERENT gating idiom than `showEndingControl`
(a boolean param the caller passes, driven by DjAppScreen's `docked` flag) used for the ENDING pill
in the same file — the user's explicit instruction for this pass was to read the compositionLocal
directly, since Row 1 has no existing external plumbing point and skipping *composition* (not just
visibility) was the point: a real-device trace put this panel at ~66ms/frame on TV even with a
static grid and stopped transport, so not composing four dropdowns beats hiding them. Both idioms
now coexist in `PulsarPanel.kt` — worth knowing before assuming there's one house style for
TV-conditional content in this file.

Reachability audit done as part of this change (grepped the whole repo, not just reasoned about
it):
- VIBE selection: fully preserved — `DjTvTopBar.kt`'s `TvVibePicker`/`TvInlinePicker` duplicates it.
- ROOT, SCALE, ENV: `setRootNote`/`setScale`/`setEnvelopeMode` are called from NOWHERE else in the
  repo. Once Row 1 stops composing on TV, a TV/remote user has no way to change root note, scale,
  or envelope mode at all. `VibeInfoMapper.kt` shows `rootNote`/`scaleType` as read-only text in the
  Info panel, but that's the *Vibe preset's* nominal key/scale, not the live overridable state —
  it doesn't restore any control.
- The manual anomaly trigger (VIBE dropdown's `onLongPress = actions.onTriggerAnomaly`) is used
  at exactly ONE call site in the entire repo (`PulsarPanel.kt`'s own EnumDropdown). `TvInlinePicker`
  (`ui/widgets/.../EnumDropdown.kt`) uses a plain `.clickable{}`, not `combinedClickable`, and has no
  long-press parameter in its signature at all — so this isn't just "unreachable via D-pad," the
  capability has literally no wiring anywhere on the TV path, confirmed by grep rather than assumed.

Preview seam: `PulsarPanelNoEndingPreview` (renamed to "TV — no top row or ENDING") now wraps its
`PulsarPanel(...)` call in `CompositionLocalProvider(LocalTvFocusChrome provides true)` IN ADDITION
to passing `showEndingControl = false` — before this change the preview didn't need the
compositionLocal at all (only `showEndingControl` mattered), but once Row 1 also depends on
`LocalTvFocusChrome`, leaving it unprovided would make the preview show Row 1 present with ENDING
missing, a combination that never actually occurs in production (both are always driven by the same
TV/docked condition together). Providing it makes the preview byte-for-byte what DjAppScreen
actually renders when this panel is docked on TV.

## Third round (2026-08-29, same day): orpheusRaisedPlate unification + TvGlassEnabled=true

### tvIdlePlate was short-lived — unified into orpheusRaisedPlate within the same day
The prior round (above) added `tvIdlePlate(accent, shape)` — an opaque idle fill TINTED per
element — to fix top-bar idle contrast. This round's user ask ("make all the items in the top bar
have the same theme based treatment as the title bar") replaced it: ONE shared idle plate for
Play/Pause, both pickers, AND the title, not three same-shaped but differently-tinted lookalikes.

`Modifier.orpheusRaisedPlate(shape, elevation: Dp = 6.dp)` (`RaisedAccentSurface.kt`) is
`AppTitleTreatment`'s `forceRaised` plate extracted verbatim: file-level `raisedPlateFill`
(cosmicPurple 0.70→deepPurple 0.96 vertical gradient), `raisedPlateBevel` (neonCyan 0.95→black
0.55 vertical gradient, 1.5dp border), 6dp neonCyan-tinted shadow. Unlike `tvIdlePlate`, it takes
NO accent param — idle is now byte-for-byte identical everywhere; only the FOCUSED state
(`raisedAccentSurface(accent = ...)`, unchanged) still varies per element. `tvIdlePlate` is
DELETED (confirmed zero remaining references repo-wide before deleting — grep for the exact
identifier first when retiring a shared modifier like this, don't trust a task's "BOTH call
sites" framing without checking, since a THIRD call site elsewhere would have silently broken).
Corner radius unified to 8.dp across title/button/picker (button was 12dp, picker was 10dp) — the
shape family, not just the fill, is now identical.

**Card `border:` param -> `Modifier.border()` in the chain is visually equivalent, reasoned not
rendered**: `AppTitleTreatment`'s raised branch used to draw its bevel via `Card`'s own
`border: BorderStroke?` parameter, separate from `.shadow()+.background()` on the passed-in
`modifier`. Moving the border INTO the shared modifier (chained after `.background()`) and setting
`Card(border = null)` in that branch is equivalent because `border()`'s implementation is
`drawWithContent { drawContent(); drawStroke() }` — the stroke always paints AFTER (on top of)
whatever is nested inside it, regardless of whether that border modifier sits on Card's externally
-passed `modifier` or is threaded in via Card's own internal `border` param; both resolve to the
same node type with the same stroke geometry (inset half-width from the shape outline). This
matters because `AppTitleTreatment`'s raised branch isn't TV-exclusive — a real `onClick` (phone
header) also triggers it — so a mechanism change here has non-TV blast radius. Verification was
explicitly deferred to the user's own pass this round; this is chain-order reasoning, not a
rendered diff.

### TvGlassEnabled flipped false -> true, with the measured cost recorded in its own doc
User measured on-device, back to back: glass OFF 326 frames/10s (~33fps, 86.5% janky) vs glass ON
271 frames/10s (~27fps, 100% janky) — ~6ms/frame cost — and chose ON anyway, a deliberate look
call. The const's doc comment now records both measurements and says so explicitly; the switch
itself stays (not deleted) for future re-measurement.

**The "single read site" claim needs a footnote when reporting it back verbatim**: grepping the
whole repo for the identifier turns up more than just `CollapsibleColumnPanel.kt`'s
`val glassEnabled = !tvChrome || TvGlassEnabled` (the only place that actually BRANCHES on it) —
also a KDoc `[link]` in `LiquidInfrastructure.kt` (doc only; `panelGlassChrome` takes a plain
`glassEnabled: Boolean` param and never reads the const itself), and
`DjLayoutRenderHarness.kt` (`apps/djapp/shared/src/jvmTest/...`), which interpolates
`$TvGlassEnabled` into a label `Text` in `renderGlassSwitchComparison()` purely for display — same
pattern as `CollapsibleColumnPanel`'s own `@Preview`. None of those is a second BEHAVIORAL gate,
so "single functional read site, not flavor-conditional" holds — but "read from one place" is only
literally true if "read" means "branches on." Worth spelling out the distinction rather than just
saying "confirmed" when a task asks you to verify a claim like this.

Also caught by that same repo-wide grep: `CollapsibleColumnPanelTvGlassPreview`'s own KDoc said
"with the switch at its current (`false`) value..." — a value baked into prose, which goes stale
the instant the const flips, unlike the `Text("TvGlassEnabled = $TvGlassEnabled")` one line below
it, which interpolates live and can never go stale. **General lesson**: when flipping a boolean
switch, grep for the literal word describing its OLD value (here, `` `false` ``) inside nearby doc
comments, not just for the const's name — a name-only grep finds every reader/writer of the
constant but misses prose that describes a specific value in words.

### Files touched this round
`ui/widgets/.../ui/infrastructure/RaisedAccentSurface.kt` (new `orpheusRaisedPlate` + file-level
brushes, `TvGlassEnabled` doc+value, `tvIdlePlate` deleted, `clip` import dropped), 
`ui/widgets/.../ui/widgets/AppTitleTreatment.kt` (raised branch now calls `orpheusRaisedPlate`,
`raisedFill`/`raisedBevel` deleted, `Card border` -> `null` in that branch, `Brush`/`background`/
`shadow` imports dropped — `BorderStroke` STAYS, still used by the non-raised `showSizeEffects`
branch), `ui/widgets/.../ui/widgets/EnumDropdown.kt` (`TvInlinePicker`: 10dp->8dp, swap+KDoc),
`apps/djapp/shared/.../djapp/DjTvTopBar.kt` (`TvTopBarButton`: 12dp->8dp, swap+KDoc),
`ui/widgets/.../ui/panels/CollapsibleColumnPanel.kt` (doc-only: fixed the stale "(`false`)"
preview KDoc found above). Did NOT touch `DjTvBottomBar.kt` or `PulsarPanel.kt` — another agent was
concurrently editing those in the same worktree this round; confirmed via `git status` (both files
showed as separately modified, untouched by this pass) and via grep (`tvIdlePlate` had zero
references in either file before deletion, so the concurrent edit wasn't put at risk).
