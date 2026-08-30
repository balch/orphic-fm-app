---
name: djapp-dj-panel-dpad-audit
description: Full enumeration of DJ panel value controls for D-pad operability (2026-08-29) — what needed fixing (BenderFaderWidget's missing focus visual) vs what was already correct, and how to verify Compose Foundation/Material3 D-pad behavior from cached sources instead of assuming.
metadata:
  type: project
---

# DJ Panel D-pad Audit (2026-08-29)

Task: "every value control in the DJ panel must be D-pad operable," mirroring an earlier Mix-panel
pass. Premise given was that most controls were already done; told explicitly to verify, not
comply. Full enumeration of every value control `DjPanel.kt` renders:

| Control | Mechanism | Status found |
|---|---|---|
| Fader A/B (`BenderFaderWidget`) | adjust-mode toggle + arrow steps | **Behavior correct, visual MISSING — fixed** |
| DLY/RVB knobs (`RotaryKnob`→`RotaryKnobDial`) | adjust-mode toggle + arrow steps | Already correct (TV raised-plate + non-TV ring both present) |
| Turntables A/B (`Modifier.deckDpad`) | up/down nudge/fling, double-select = random drop | Already correct + already tested |
| Source A/B dropdowns (bespoke, not `EnumDropdown`) | `Modifier.clickable` + M3 `DropdownMenu` | Already correct (verified from library source, see below) |
| Crossfader (`actions.setCrossfader` in `DjViewModel.kt`) | none | **No on-screen control exists at all** — did not build one, reported only |
| Deck lock/freeze toggle (`onToggleLock`, long-press platter spindle) | pointer-only `detectTapGestures(onLongPress)` | **No D-pad equivalent** — flagged, not implemented (see reasoning below) |

## The one real gap: BenderFaderWidget had zero focus visual
`ui/widgets/.../BenderFaderWidget.kt` had textbook-correct key handling (adjust-mode toggle,
arrow-gating, Back-only-consumed-if-it-exited, blur-clears-adjusting) — a user memory note
(`reference_dpad_adjust_mode_pattern`) even listed it as "implemented." But `isFocused`/
`isAdjusting` were tracked in state and never once read for rendering — grepped the whole file to
confirm (`grep -n "isFocused\|isAdjusting\|LocalTvFocusChrome"` showed only the declarations/
mutations, zero draw-site reads). The behavioral contract was complete; the "make the mode
visible" half of the contract was simply never finished. This is the kind of gap a memory note
built from an earlier pass can miss — trust the grep over the note.

Fix mirrors `RotaryKnobDial` exactly: derived `isFocused`/`isAdjusting` vals (`previewFocused ?:
liveFocused`, etc.) for rendering, `liveFocused`/`liveAdjusting` vars for the real key-handling
path — same split RotaryKnobDial uses so a `previewFocused` override never leaks into what real
key events are gated on. TV branch reuses the existing `Modifier.raisedAccentSurface` from
`RaisedAccentSurface.kt` (elevation 6dp focused / 11dp adjusting, no animation — see perf note
below); non-TV branch is a plain static `Modifier.border` (width/alpha jump, no animation),
matching RotaryKnobDial's own non-TV branch which is also static. Added matching
`previewFocused`/`previewAdjusting` params + 3 TV/non-TV preview composables, same seam
RotaryKnobDial established.

**Deliberately skipped RotaryKnobDial's pulsing-glow-ring animation for the adjusting state.**
That pulse is cheap in RotaryKnobDial because it's a raw `drawCircle` alpha animation inside an
existing `Canvas` drawScope (draw-phase only). BenderFaderWidget's outer Box has no Canvas at that
level; replicating the pulse would mean animating `Modifier.shadow`'s elevation continuously,
which re-triggers the compositor's shadow/layer machinery every frame — a real cost on the exact
platform (TV) this feature targets, per this project's own measured sensitivity (`TvGlassEnabled`
A/B: ~6ms/frame just for glass fill; a static Pulsar panel alone already showed ~66ms/frame UI
thread cost). Static elevation swap (6dp/11dp) satisfies "visibly heavier while adjusting"
without a continuous animation. If a future pass wants full pixel-parity with the knob's pulse,
do it via a `drawBehind`/Canvas overlay on the fader (draw-phase only), not by animating `.shadow`.

**Blast radius**: `BenderFaderWidget` is shared — also used by the main Orpheus app's pitch-bend
UI (`apps/orpheus/shared/.../CompactPortraitVoicePads.kt`, `DesktopBenderStringsSection.kt`), not
just the DJ panel. `LocalTvFocusChrome` defaults false there (Orpheus has no TV mode), so only the
non-TV static-border branch reaches those call sites — a previously-absent focus ring now appears
there too on keyboard Tab. Treated this as a genuine fix rather than DJ-only scope creep: the
"visually invisible adjust mode" is a real usability gap independent of TV, and RotaryKnobDial's
own non-TV ring predates the TV work entirely per its own comment ("unchanged from before this TV
pass") — i.e. the baseline (non-TV) ring was always considered required, not a TV-only add-on.

## Verifying Compose/Material3 D-pad behavior from source instead of assuming
Two claims worth having verified-not-assumed, since both determined "no change needed" calls:
- `Modifier.clickable` (Compose Foundation) already treats `Key.DirectionCenter`/`Key.Enter`/
  `Key.NumPadEnter` as a click (press-on-down, fire-on-up) with **zero extra code** — confirmed
  via `KeyEvent.isClick` in `Clickable.kt` (extract any `foundation-desktop-<ver>-sources.jar`
  from the Gradle cache, `grep -n "isClick" Clickable.kt`). It does NOT touch arrow keys at all,
  so there's no focus-trap risk for a plain clickable — the "adjust-mode" hazard is specific to
  controls that give arrow keys a second meaning, not to clickables in general.
- M3's `DropdownMenu` on desktop/skiko uses `DefaultMenuProperties = PopupProperties(focusable =
  true)` (`Menu.skiko.kt`) and `DropdownMenuItemContent` is itself a `.clickable` (`Menu.kt`) — so
  a bespoke dropdown built from vanilla `DropdownMenu`/`DropdownMenuItem` (like `DjPanel.kt`'s
  private `SourceDropdown`, which does NOT use the shared `EnumDropdown`) is already fully D-pad
  operable with no extra work, end to end: anchor opens via Enter/DirectionCenter, popup is
  focusable, items select via Enter/DirectionCenter.
- General technique: `find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.<module> -iname "*sources.jar" | grep desktop`, unzip to scratchpad, grep. Works for any Compose
  Multiplatform artifact — see also the NavigationSuiteScaffold memory entry above for the same
  technique applied to material3-adaptive-navigation-suite.

## Boundary call: deck lock/freeze toggle left unfixed, flagged only
`toggleLock` (long-press the platter's center spindle → freezes that deck) sets real state
(`DjUiState.lockedA/B`) but has no keyboard/D-pad path at all — distinct from the fader/knob
"roach motel" hazard since it isn't arrow-key-driven (no focus-trap risk either way). Did not
invent a select-press mapping for it (e.g. repurposing deckDpad's currently-inert single-select
press) without being asked — same restraint the task explicitly required for the crossfader ("no
on-screen control → report, don't build one"). This one already HAS an on-screen affordance (the
spindle), just not a D-pad-reachable one, so it's a real gap worth flagging, but inventing new
key semantics for `deckDpad` felt like the same category of overreach the crossfader instruction
was warning against. Left for the user to decide intent.

## Working in a shared worktree with other agents mid-edit
Instructed not to open/edit/revert `RaisedAccentSurface.kt` (among others) because another agent
was actively changing it in the same worktree. My fix calls its already-public
`Modifier.raisedAccentSurface(accent, shape, elevation)` — using a concurrently-edited file's
existing public API (without reading the file) is different from editing it, and was the right
call for visual consistency, but it's a real integration-risk seam: if that agent changes the
function's signature, this call site breaks and I have no way to check from inside this session.
Flagged explicitly in the task report rather than silently hoping — this is what "verification
deferred to a single pass at the end" is for in a multi-agent worktree.
