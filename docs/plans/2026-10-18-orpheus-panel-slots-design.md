# Orpheus panel slots: rail, recents and search instead of the spine row

Date: 2026-10-18. Base: `main` (`3f57a4694`). Platforms: everything above phone size (JVM desktop,
Android tablets and the open Fold, iPad, WASM). JVM desktop stays the primary platform.

## Why

On wide screens Orpheus shows its feature panels as one horizontal row (`HeaderPanel.kt`). A
closed panel is a 28dp vertical "spine" with its name spelled one letter per line, and an open
panel takes a share of what is left. With 29 panels the spines alone take about 810dp.

On the open Pixel Fold (about 850dp wide) that leaves almost nothing. Open panels are clipped:
the Mix panel shrinks to a sliver, Pulsar is cut off at the right edge, and the user "could not
see when one was open". The current build was checked on the Pixel 9 Pro Fold emulator. On desktop
the row still works at 1900dp, but it only gets more crowded as panels are added.

The user needs every panel reachable on every screen size. Showing one panel at a time is fine
on small screens, but where the width allows it, several panels should be visible side by side.
The user also wants a quicker way to move between panels. They were open to a full redesign,
and they picked a combination of a side list of all panels and a launcher with recents.

## Goals

- No panel is ever clipped or squeezed below a usable width.
- Every panel is reachable in one or two taps (or one shortcut and a few letters on desktop).
- The number of panels showing at once follows the window width: 2 on the open Fold, more on
  tablets and desktop.
- The panels used recently are one tap away.
- Text and knobs scale together with the space a panel actually gets.
- The navigation can be put away, so the panels and voices get the whole screen while playing.

## Non-goals

- Phones, the closed Fold and compact landscape. `CompactPortraitScreen` (swipe pager plus
  Pads/AI/Strings) and `CompactLandscapeScreen` stay as they are. The user said they work.
- The voice section below the panels. It stays as it is today.
- Fold postures (book or tabletop) and hinge-aware splitting. The DJ app has hinge code
  (`apps/djapp/shared/.../Hinge.kt`, `TabletopHinge.android.kt`) for a later pass.
- User-named workspaces or saved layouts. Panel sets stay an AI and code concept for now.
- Fixing the existing focus leak where typing in CODE or AI may trigger synth keys (see Risks).
  The new search field must not have this problem, but the old panels are out of scope.

## Anti-goals (failure even if it works)

- Panels reshuffle under the user's finger while they are playing.
- Typing in search plays notes or drums.
- Any panel becomes unreachable on any screen size.
- The phone layout gets worse.
- Text gets so small on a crowded desktop that it is hard to read, or so large on the Fold that
  a panel no longer fits its height.

## The design (decided with the user)

Above the compact breakpoint (`SynthScreen.kt:47`: width ≥ 600dp and height ≥ 400dp) the screen
becomes, top to bottom:

1. **Recents strip** (top, one line). A 🔍 button, then a chip for each open panel (bright, in
   slot order, 📌 on pinned ones), a divider, then chips for panels closed recently (dim, most
   recent first). Tapping a dim chip opens that panel. The strip collapses to a small handle
   and expands again from it. If the chips overflow, the strip scrolls sideways.
2. **Panel area**, with the **side rail** on its left. The rail lists every panel grouped by
   kind, with each group's header in its color and open panels lit. Tapping a name opens that
   panel. The rail can be shown or hidden. It is hidden by default on the open Fold and shown by
   default on desktop.
3. **Voices**, unchanged.

**Slots.** The panel area holds `slots = floor(panelAreaWidth / 320dp)` panels side by side. The
area's width excludes the rail when it is showing. That gives 2 on the open Fold, 3 on a 10"
tablet in landscape, 4 on a laptop and 5 on a 1900dp desktop. Each open panel has 📌 (pin) and ✕
(close) in its own small header.

**Opening a panel when the slots are full** closes the panel used longest ago that is not pinned.
The new panel takes that panel's place. This was agreed with the user.

**Search.** 🔍 (or Cmd+K / Ctrl+K on desktop) opens a list of every panel. Typing filters it, and
Enter opens the top match.

**Sizing.** Text and knobs scale with the slot, so the user's question "can we resize panel text
by width" is handled by scaling, not by editing font sizes (see below).

## Decisions I made (the user has not reviewed these individually)

### Slot arithmetic

- A unit is 320dp, today's minimum expanded width (`HeaderPanel.kt:66`). There is no upper cap.
  A 2560dp window gets 8.
- Units are split evenly. Panel `weight` and the panel-set `weightOverrides` stop affecting this
  layout. They still exist for the compact screens and for `ApplySet` (see AI below).
- **Wide panels** take 2 units: Pulsar and Beats to start with (Beats already has a 600dp content
  cap and weight 1.25). The builder may add others after rendering each panel at 320dp.
  - Nothing is ever closed to make room for a wide panel's second unit. A wide panel takes 2
    units only when the window has at least 3 slots and a second unit is free (empty, not
    occupied). Otherwise it takes 1.
  - So on the open Fold, every panel takes exactly one of the two slots, and Pulsar sits next to
    another panel. The goal of 2 panels on the Fold wins over Pulsar's extra width.
  - A wide panel already at 2 units never shrinks to make room. When the slots are full, the
    usual eviction applies. If the wide panel itself is the one evicted, the new panel takes its
    first unit and the second unit becomes free.
- **Pins never cover every unit.** Pinned panels may use at most `slots - 1` units, so there is
  always an unpinned slot to replace. 📌 is disabled when pinning would break this. On the Fold
  (2 slots) that means one pinned panel at a time.
- "Used" means opened, or touched anywhere inside the panel. Touch is detected by the slot
  container on the initial pointer pass, without consuming the event. Individual controls are
  not involved.
- Opening a panel that is already open does not move it. Its slot pulses briefly.

### When the slot count changes (resize, fold/unfold, rail toggled)

- **Shrinking.** Keep the panels used most recently (pins first) that fit. The rest move to the
  front of recents as dim chips.
- **Growing.** Fill the new empty slots from recents, most recent first. Unfolding the Fold
  therefore restores what was open.
- Slots change only when the count changes, never in the middle of a gesture. The layout
  decision is made from the previous frame's size (`onSizeChanged` into state, as
  `DjLayoutBox` in `apps/djapp/shared/.../DjLayout.kt:143` does), not inside `BoxWithConstraints`.
  The DJ app hit a desktop crash ("RootNodeOwner is already disposed") when a size-driven branch
  inside `BoxWithConstraints` removed a dialog layer mid-measure. `SynthScreen` currently uses
  `BoxWithConstraints` (`SynthScreen.kt:86`) and should move to the same pattern.

### Scaling text and knobs

dp already accounts for pixel density, so a Fold and a desktop at the same dp width lay out the
same. What remains is the slot's actual size. Each slot provides its own `LocalDensity`:
`Density(base.density * s, base.fontScale)`, where

`s = clamp(min(slotWidth / designWidth, slotHeight / designHeight), 0.85, 1.25)`

- `designWidth` is 320dp per unit (640dp for a 2-unit panel).
- `designHeight` is the height today's desktop header gives panels. The builder measures it; it
  is about 260 to 320dp.
- Inside the slot, everything (text, knobs, spacing) grows or shrinks together. The user's own
  font-size setting is kept because `fontScale` passes through.
- The DJ app does the same at screen level (`apps/djapp/desktopApp/.../DesktopCanvasScale.kt:41`).
- `designHeight` is one constant: the panel row's height in a 1280×800 desktop window today,
  about 330dp. The row's height comes from `weight(0.75f)` against the voices' `1f`
  (`DesktopSynthScreen.kt:96`).
- The recents strip's height comes out of the panel area, not out of the voices, which stay
  unchanged.
- If a panel still does not fit its slot's height at `s = 0.85`, the slot scrolls vertically.
  This is the fallback, not the plan. Any panel that needs it gets noted for a later
  layout pass.
- **Knob feel stays constant without extra work.** Knob drag sensitivity is measured in physical
  pixels (`rememberKnobDragState`, 200px for a full sweep). Overriding `LocalDensity` does not
  change pixels, so the same hand movement still turns a knob by the same amount. Do not convert
  it to dp.
- Per-text sizing was rejected. The feature panels hard-code about 179 `fontSize = N.sp`
  values, and editing them one by one would drift.

### Rail groups

The rail groups below are my guess. The user saw them in mockups and did not object. They are
easy to change, since they live in one literal map in the app module, not on each panel.

| Group | Panels (today's spine names) |
|---|---|
| Rhythm | PULSE, BEATS, 808, BASS, DJ, TALK |
| Mod | LFO, Waves, EVO, FLUX, TRIGG |
| FX | VOL, DELAY, VERB, HORN, REZO, GRAIN, BLEND, LOOP |
| Tools | PATCH, MIDI, VIZ, CODE, AI, VIBE, ASL, Timer, REC |

TWEAKS and MIXER are left out, as they are today: `DesktopScreen` excludes TWEAKS, and no factory
set includes MIXER. REC exists on desktop only (`:features:record` is a jvmMain dependency).
Group order and panel order within a group follow the table.

### Names shown in rail, strip and search

Users know panels by their spine text (VERB, 808, BLEND). That text currently lives inside each
panel's composable, passed as `title` to `CollapsibleColumnPanel`
(`ui/widgets/.../CollapsibleColumnPanel.kt:74`), so nothing outside the panel can read it.

- The short label goes in the same literal catalog as the groups, in the app module.
- No Orpheus layout shows spines after this change, so the two copies cannot visibly disagree.
  That leaves the 30 panel registrations untouched.
- Search matches on the short label, `FeaturePanel.label`, `description` and `panelId`.

### Panel chrome in a slot

In a slot the 28dp spine strip is hidden; `LocalCompactMode` already does this
(`CollapsibleColumnPanel.kt:47`). The slot draws a thin header row with the short label, 📌 and ✕.
The panel's own expanded title and body render as they do today. If `LocalCompactMode` turns off
other things a slot still needs, the builder may add a separate local instead of reusing it.

### Open in a slot means expanded

Panels keep receiving `isExpanded`, and some act on it. For example, `DjPanelRegistration.kt:37`
turns turntable viz polling on and off with `LaunchedEffect(isExpanded)`. So:

- An open slot passes `isExpanded = true`. `HeaderPanelUiState.isExpanded(id)` reads the slots.
- Only one panel keys an effect on `isExpanded`. Change its `LaunchedEffect` to a
  `DisposableEffect` that turns the viz off in `onDispose`, so closing the slot (which removes the
  panel from composition) switches it off.
- No other registration changes.

### Defaults on first run, and where state lives

- New fields on `AppPreferences` (`core/foundation/.../AppPreferences.kt:8`), each with a default:
  - open panels in slot order
  - pinned panels
  - recents, capped at 12
  - `railVisible`, nullable: null means use the default for the width
  - `recentsStripVisible`
- The DJ app's `largeScreenPanels` (`AppPreferences.kt:35-42`, written in `DjAppScreen.kt:185`)
  is the precedent.
- **Migration.** If the new open-panels list is empty, seed it from the panels marked expanded
  in `lastExpandedPanelsJson`. Failing that, use the `DesktopScreen` set's expanded panels in
  order (Distortion, Pulsar), filling until full.
- **Rail default.** Shown when the window is at least 1200dp wide, hidden below that. After the
  user toggles it, their choice sticks on that device.
- Cmd+K / Ctrl+K opens search. Tab is not used, because it does focus traversal. While search is
  open it raises `isDialogActive` (`SynthKeyboardHandler.kt:30`), so typing never reaches synth
  keys. Escape closes it.
- On touch screens, 🔍 opens the full grouped list with the search field present but not
  focused, so the soft keyboard does not cover half the Fold until the user taps the field.

### AI agent

The AI drives panels through `PanelExpansionEventBus`
(`features/ai-orpheus/.../PanelExpansionEvent.kt:36`), which `HeaderViewModel` consumes
(`HeaderViewModel.kt:128-138`). Those events now map like this:

- `expand(id)`: open the panel (same rules as a tap).
- `collapse(id)`: close it. It goes to recents.
- `expandAll`: nothing calls it today, and it is only a series of `expand` events, so it needs
  no special handling. The panels it opens follow the normal eviction rule.
- `applyPanelSet(set)`: close all unpinned panels, then open the set's expanded panels in order
  until full.

Other code also expands panels: SpeechTool, ReplExecuteTool, VibeCreateViewModel and
AiOptionsViewModel, which expands CODE and collapses LFO and DELAY. It keeps working through the
same mapping. `PanelExpandTool`'s description lists stale panel ids; refreshing it is a small
side fix the builder may make.

## What is left to the builder

- Exact sizes of the strip, rail, handle and chips, and the look of the chips. Follow
  `OrpheusTheme` and each panel's existing color. No glass on reading surfaces.
- Whether slot state lives in `HeaderViewModel` (extended) or a new feature next to it, as long
  as compact screens keep using `resolvePanels` unchanged.
- The final list of wide panels, after rendering each at one unit.
- Animations for opening, closing and the pulse. Keep them short; this is a live instrument.

## Testing

- **Pure slot logic** (open, close, pin, the pin cap, eviction order, wide panels, shrink and
  grow) as plain unit tests on a function with no Compose.
- **Render harness** (Compose to PNG) at 850×880dp (open Fold, rail hidden and shown) and
  1920×1080dp, checking that no panel is clipped and that text scales.
- **Pixel 9 Pro Fold emulator:** install `org.balch.orpheus.debug` (the debug build's application
  id; plain `org.balch.orpheus` on the emulator is an old March build). Fold and unfold, check
  that the open panels come back, and screenshot the inner display with
  `screencap -d <display id>`.
- **JVM desktop run:** resize the window across 600, 960, 1280 and 1920dp. Use Cmd+K to search,
  and type letters bound to synth keys (A, S, D, Q, W, Space) to confirm nothing plays.

## Risks

- **Height on the Fold.** The scale clamp keeps tall panels (Pulsar, Beats) from overflowing,
  but at 0.85 they may still be tight. Render first.
- **Existing focus leak.** `DesktopSynthScreen.kt:78` puts `onPreviewKeyEvent` on the parent, and
  CODE and AI do not raise `isDialogActive`, so typing there may play notes. This is out of scope,
  but it is the same trap the search field must avoid.
- Moving `SynthScreen` off `BoxWithConstraints` touches the phone path too, so check that the
  compact screens still switch correctly when rotating.
