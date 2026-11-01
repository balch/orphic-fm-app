# UI Surfaces: Panel, Sheet or Pop-out

Every control surface in the apps is one of three things: a panel, a sheet, or an anchored pop-out.
This doc says which to pick, which composable to use, and how each one behaves in every layout. The
last section audits the surfaces that exist today against the rules.

## The rules

1. **Panel: something you play or tweak while the music runs, and keep coming back to.** It has no
   "done" moment. Examples: Pulsar, Mix, Horn, Cowbell, the TV dock. It lives in a layout slot
   (`DjLayout`), never covers the rest with a scrim, and is reached by the nav or the dock.
2. **Sheet: a short task with an end.** You open it, finish, and it goes away: pick a song
   (playlist), read about a vibe, save or manage presets, transition settings, the AI chat. It goes
   through `StageSheet` or `AdaptiveSheet`, which become a bottom panel in portrait and a side sheet
   with landscape chrome (Landscape, LargeScreen). DJ app code doesn't call `OrpheusSlideUpSheet`
   directly.
3. **Anchored pop-out: choosing one value for one control.** For example the engine picker or the
   patch bay. Use `AnchoredPopoverOrSheet`, which is a popover at 600dp or wider and a sheet below
   that.
4. **Nothing you play by gesture goes in a sheet.** A sheet's drag-to-dismiss and scrim tap fight
   drags, tilts and second fingers. This is the hard reason the cowbell is a panel.
5. **Tie-breaker:** if you'd use it twice in a row with the music playing, it's a panel. If it has a
   Done or Close moment, it's a sheet.

## Which composable

| Surface | Use | Where |
|---|---|---|
| Panel | A slot or dock entry in `DjLayout`. On Orpheus, a `PanelId` and a `PanelNavEntry` | `DjAppScreen.kt`, `DjPanelDock.kt`, `PanelNavCatalog.kt` |
| Sheet | `StageSheet` for the stage's own sheets, `AdaptiveSheet` for content that needs a phone bottom sheet | `AdaptiveSheet.kt` |
| Pop-out | `AnchoredPopoverOrSheet` with `sizeModifier` from its callback | `ui/widgets/.../popover/AnchoredPopoverOrSheet.kt` |

The two sheet hosts differ in portrait:

- `StageSheet` portrait is `StagePanel`: a panel inside the stage, 85% of its height, sliding up
  under the bar. The phone bar's raised ring stays visible over it. Use it when the bar should stay
  reachable (playlist).
- `AdaptiveSheet` portrait is `OrpheusSlideUpSheet`, a Material modal bottom sheet in its own window,
  sized by `portraitPeekHeight`. It covers the bar. Use it when the content wants its own height
  (presets, AI chat).

In landscape chrome both become `SideSheet`: a right-edge panel over a scrim, min(60% of the
window, 400dp), or 440dp for `AdaptiveSheet`. The rail and the dock stay visible. Back and Esc
dismiss it.

`OrpheusSlideUpSheet` is the shared widget under `AdaptiveSheet` and `AnchoredPopoverOrSheet`. Only
those two hosts, and shared feature code that has no DJ host to call, should reach for it.

`AnchoredPopoverOrSheet` reads the window width, not the layout. Below `NarrowWindow` (600dp) it
shows a tall bottom sheet (`sheetHeightFraction`, default 0.8 of the window). At 600dp or wider it shows a
focusable popup under the anchor, kept inside the window. The caller gets `sheet: Boolean` in its
content lambda to pick a roomier arrangement.

## Behaviour per layout

`resolveLayout` picks the layout from the window size (`DjLayout.kt`). `usesLandscapeChrome()` is
true for Landscape and LargeScreen only, and that flag is what flips sheets to the side.

| Layout | Panel | Sheet | Pop-out |
|---|---|---|---|
| **Portrait** | One nav-selected panel under Pulsar. | `StagePanel` or modal bottom sheet. | Bottom sheet under 600dp, popover above. A phone is usually under 600dp. |
| **PortraitPair** | Up to two panels side by side (the pair). The pair list evicts the oldest. | Same as Portrait: portrait chrome. | Popover (the pair needs 700dp). |
| **Landscape** | Pulsar left, one nav-selected panel right. | `SideSheet`. | Popover when the window is 600dp wide, which a landscape phone usually is. |
| **LargeScreen** (TV, desktop fullscreen, big windows) | Docked around Pulsar, toggled from the top and bottom bars. The dock never evicts. | `SideSheet`. | Popover. |
| **Tabletop** (half-folded) | Below the hinge: one panel, or the pair when wide enough. | Portrait chrome, so `StagePanel` or modal bottom sheet. | By width, as above. |
| **Orpheus** (desktop app) | Slots in the panel row, opened from the rail (`PanelNavCatalog`). | No `AdaptiveSheet` or `StageSheet`. It uses dialogs and draggable dialogs (not covered by these rules), and the shared Pulsar panel brings in `TransitionSettingsSheet`. | `AnchoredPopoverOrSheet`: popover, or sheet in a window under 600dp. |

Notes that follow from the code:

- On LargeScreen, Vibe Info and Ends dock as panels (`largeScreenPanels`). In the pair layouts Vibe
  Info takes a pair slot. Elsewhere Vibe Info is a sheet. Same content, three hosts. Docking is the
  one allowed way for a sheet's content to become a panel, because the dock has room and a sheet
  would hide the dock.
- A route with `opensAsSheet` is never a dock or pair panel, except Vibe Info. A sheet-only tab (AI)
  is appended to the dock's bottom bar so it still has an entry point there.
- The dock panel's controls take D-pad focus. A sheet opened from the dock is a `SideSheet`, and Back
  closes it.
- `OrpheusSlideUpSheet` and `AnchoredPopoverOrSheet` open their own window, so they call
  `KeepPanelsAwake` to stop the panel underneath fading out while they are open. `SideSheet` and
  `StagePanel` draw inside the stage and do not. A new host that opens its own window needs the call.
- Do not decide a layout with `BoxWithConstraints` around a sheet or popup. Adding or removing a
  dialog layer during measure crashes desktop Compose. Use `DjLayoutBox` or the window size.

## Choosing, in order

1. Do you hold it, drag it, tilt it, or play it? Panel (rule 4).
2. Would you use it twice in a row with the music going? Panel (rule 5).
3. Is it one value for one control? Pop-out (rule 3).
4. Does it end with Done, Close or a pick? Sheet (rule 2).

If the answers disagree, the earlier line wins.

## Audit of current surfaces

Snapshot of 2026-10-31. Found with
`grep -rn 'OrpheusSlideUpSheet(\|AdaptiveSheet(\|StageSheet(\|AnchoredPopoverOrSheet(\|SideSheet(' --include='*.kt' apps features ui`,
plus `MixerSheet`, which draws its own scrim and so is not in that grep. Tests are left out. The
table names functions, not lines, so it ages slower than the code. Nothing here has been moved.

| Surface | File | What it is | Rule | Conforms |
|---|---|---|---|---|
| `AiSheet`, chat mode | `apps/djapp/ai/.../AiTabContribution.kt` | The AI chat. `AdaptiveSheet` in portrait and landscape. | 2 | Yes |
| `AiSheet`, mixer mode (`MixerSheet`, `MixerSheetContent`, `AiMixerPanel`) | `apps/djapp/ai/.../AiTabContribution.kt`, `mixer/MixerSheet.kt` | The AI edition's mixer, shown over a scrim, with the chat beside or below it. You tweak it while music plays, and in portrait its strips sit in a draggable bottom sheet. | 1, 4, 5 | **No** |
| `VibeInfoSheet` | `apps/djapp/shared/.../vibeinfo/VibeInfoSheet.kt` | Read about the playing vibe. Calls `OrpheusSlideUpSheet` directly, so it is a bottom sheet in Landscape too. | 2 | **No** |
| `TransitionSettingsSheet` | `features/pulsar/.../TransitionSettingsSheet.kt` | Pick the vibe-ending style. Opened from Pulsar's ENDING pill. Calls `OrpheusSlideUpSheet` directly, so it is a bottom sheet in Landscape. Lives in a feature module Orpheus shares. | 2 | **No** |
| `CapturePresetSheet` | `apps/djapp/shared/.../vibepresets/PresetSheets.kt` | Name and save a preset. `AdaptiveSheet`. | 2 | Yes |
| `ManagePresetsSheet` | `apps/djapp/shared/.../vibepresets/PresetSheets.kt` | Rename, delete, pick a preset. `AdaptiveSheet`. | 2 | Yes |
| `PlaylistSheet` | `apps/djapp/shared/.../playlist/PlaylistSheet.kt` | Pick a song. `StageSheet`. | 2 | Yes |
| `EnginePicker` | `ui/widgets/.../engine/EnginePicker.kt` | Pick an engine for one voice. `AnchoredPopoverOrSheet`. | 3 | Yes |
| `PatchBayButton` | `ui/widgets/.../patch/PatchBayButton.kt` | Pick which output drives one duo knob. `AnchoredPopoverOrSheet`. | 3 | Yes |
| `AdaptiveSheet` (portrait branch) | `apps/djapp/shared/.../AdaptiveSheet.kt` | Host: calls `OrpheusSlideUpSheet`. | 2 | Yes, a host |
| `AnchoredPopoverOrSheet` (narrow branch) | `ui/widgets/.../popover/AnchoredPopoverOrSheet.kt` | Host: calls `OrpheusSlideUpSheet`. | 3 | Yes, a host |

### Non-conforming rows, for you to decide

- **AI mixer mode.** The mixer is a set of strips and meters you adjust while the music plays, and it
  sits behind a scrim that a stray tap closes. In portrait it is inside a bottom sheet you can drag
  away, which rule 4 rules out for anything you play by gesture. Rules 1, 4 and 5 make it a panel.
  It also writes its own
  scrim and Back handling in `MixerSheet` rather than using `StageSheet`. The chat beside it is a
  fine sheet. A move would make the mixer a panel and leave the chat as the sheet.
- **`VibeInfoSheet`.** The task is right for a sheet, but the host is wrong: it skips `AdaptiveSheet`
  and `StageSheet`, so Landscape gets a bottom sheet where rule 2 says a side sheet. Switching it to
  `AdaptiveSheet` fixes that. (On LargeScreen and in the pair it is already a panel, which is the
  allowed dock form.)
- **`TransitionSettingsSheet`.** The task is right for a sheet, but it is a bottom sheet in Landscape
  for the same reason, and it auto-dismisses on inactivity. It lives in `features/pulsar`, which
  Orpheus uses too and which cannot see the DJ app's `AdaptiveSheet`. A move needs a sheet host that
  the DJ app injects, or `AdaptiveSheet` moving down into `ui/widgets`.

### Checked and fine

- Rows marked "a host" are the wrappers rule 2 and 3 allow to call the slide-up widget.
- Orpheus has no `AdaptiveSheet` or `StageSheet`. Its pop-outs are the two above. Not audited: its
  dialogs and draggable dialogs (the AI chat, the timer, the expanded sequencer, the preset name and
  confirm dialogs). They are outside these rules and outside the grep.
- The dock forms of Vibe Info and Ends are panels by the dock exception above, not violations.

## Adding a surface

- New panel: `.claude/skills/panel-viewmodel-feature/`. In the DJ app, add the route to `djTabs` or
  the dock list, and give it a slot in each layout. On Orpheus, add a `PanelId` and a
  `PanelNavEntry`.
- New sheet: `StageSheet` or `AdaptiveSheet`, with `isLandscape` from `layout.usesLandscapeChrome()`.
  Host it in `DjAppOverlaySheets` so it shares the single `activeSheet` state.
- New pop-out: `AnchoredPopoverOrSheet`. Test it at under 600dp and at 600dp or more.
- Add a row to the audit above.
