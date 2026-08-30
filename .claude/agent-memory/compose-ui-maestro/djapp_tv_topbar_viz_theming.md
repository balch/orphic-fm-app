---
name: djapp-tv-topbar-viz-theming
description: DJ app TV top+bottom bar chrome follows the selected visualization's titleColor via a shared dark-chrome-plus-wash recipe (orpheusChromeWash); history of two wrong intermediate designs and why each was rejected; EnumDropdownMenu D-pad focus-on-open.
metadata:
  type: project
---

# DJ App TV Bar Chrome: Viz-Following Dark-Chrome-Plus-Wash (through 2026-08-29)

Three rounds on this design, each corrected by the user after seeing a render. Read the "wrong"
sections below before ever touching `orpheusRaisedPlate`/`orpheusChromeWash` again — both dead ends
are easy to reinvent by accident.

## Current (correct) design
`RaisedAccentSurface.kt`:
- `raisedPlateBase` (private, file-level `Brush`) = fixed `cosmicPurple@0.70 -> deepPurple@0.96`
  vertical gradient. NEVER viz-derived — this is fixed "app chrome" identity, independent of
  whatever visualization is on screen.
- `Modifier.orpheusChromeWash(shape, accent, washAlpha = PlateWashAlpha)` — PUBLIC, reusable:
  `.background(raisedPlateBase, shape).background(accent.copy(alpha = washAlpha), shape)`. Two
  stacked `.background()` calls composite the second visibly ON TOP of the first (Compose modifier
  draw order: earlier-in-chain draws first/behind, later draws on top) — this is what "wash
  composited over a base" means in code, not a color-math blend function.
- `Modifier.orpheusRaisedPlate(shape, elevation, accent)` — one param now (`accent`, defaults to
  `VisualizationLiquidEffects.Default.title.titleColor`), built from `orpheusChromeWash` plus
  `.shadow(ambientColor=accent, spotColor=accent)` and a `.border(accent@0.95 -> black@0.55)`.
- Callers (`AppTitleTreatment`'s raised branch, `DjTvTopBar.TvTopBarButton`'s idle branch,
  `EnumDropdown.TvInlinePicker`'s idle branch) all pass `accent = effects.title.titleColor` — ONE
  value drives fill-wash, bevel, AND shadow. `effects.title.borderColor` is NOT used by any of
  these anymore (see "wrong #1" below for why it looks tempting).
- Every top-bar element's TEXT/ICON also reads `effects.title.titleColor` directly (a separate call
  each composable already made) — including Play/Pause and the Vibe picker. **There is no
  "deliberate brand-identity exception" left** — an earlier pass (commit 33a5b6037) left Play/Pause
  and the Vibe picker on a fixed `cosmicPurple`, and a LATER commit (0da32ac06, before this session)
  removed that exception too. If you're reading an old memory or comment claiming those two stay
  fixed, it's stale — `grep -n "cosmicPurple\|titleColor" DjTvTopBar.kt` to check the live state
  before trusting any claim about which elements follow the viz.
- `DjTvBottomBar.kt` mirrors this with its OWN `accent = effects.title.titleColor` local, threaded
  into: the bar's own `tvFocusRegionBorder` color, each item's tint (docked||focused), the focused
  item's `raisedAccentSurface(accent=...)`, and the docked item's fill via `orpheusChromeWash(shape,
  accent, washAlpha = TvDockedWashAlpha)` (own alpha constant, currently 0.5f — see "wrong #3"
  below for why the docked wash specifically needed `orpheusChromeWash`, not a bare
  `background(accent.copy(alpha=X))`). `DjTvTopBar`'s own bar-level `tvFocusRegionBorder` ALSO uses
  `effects.title.titleColor` now (was still hardcoded `neonCyan` even after the button/picker fix
  landed — fixed this session so the two bars' own outer borders agree under a non-default palette,
  not just their internal buttons).
- The "armed" ring (`DjTvBottomBar`'s Ends item, `TvBottomBarItem.armedColor`) stays FIXED at
  `OrpheusColors.cosmicPurple` — deliberately NOT viz-derived. Reasoning: cosmicPurple is the exact
  same hue `raisedPlateBase` uses as its own fixed chrome-identity color, so keeping armed pinned to
  it reads as "a piece of app chrome punching through" regardless of viz, immune to ever coinciding
  with whatever hue the docked/focused accent currently is. A viz-derived armed ring COULD collide
  with the docked/focused accent (nothing stops a viz's `titleColor` from being violet/purple),
  destroying the "armed never looks like docked or focused" guarantee the whole bar depends on.

## Wrong #1 (shipped, then reported by the user as "TV chrome looks off"): viz color as the WHOLE fill
`fillAccent` used to be sourced from `effects.title.borderColor` and painted as the entire plate
background (`fillAccent.copy(alpha=0.70) -> black.copy(alpha=0.96)`). Since `.copy(alpha=X)`
REPLACES alpha rather than multiplying it, this ignored whatever alpha the viz's own `borderColor`
had and produced a saturated block of viz color — the plate stopped reading as chrome and read as
"a colored rectangle." `borderColor`'s actual semantic role (a THIN TRANSLUCENT STROKE, per its own
`CenterPanelStyle` default `Color.White.copy(alpha=0.3f)` and per how the phone/desktop
`AppTitleTreatment` non-raised branch actually uses it — a `BorderStroke`, never a fill) was being
stretched across an opaque surface it was never designed for.

## Wrong #2 (proposed by the assistant, rejected by the user before shipping): dark chrome + thin trim only
The first fix attempt swung too far the other way: keep `raisedPlateBase` as the ENTIRE fill, and
let `accent` drive ONLY the 1.5dp border + shadow glow — no wash on the fill at all. The user
rejected this specifically: the visualization's identity needs to stay "clearly visible, not
reduced to a thin accent." A 1.5dp colored edge around an otherwise-fixed-purple plate doesn't say
"this is Heartbeat" the way a genuinely tinted plate does.

## Wrong #3 (found by rendering, not requested): a flat docked-wash `background(accent.copy(alpha=X))` alone
Applied the SAME lesson to `DjTvBottomBar`'s docked-item fill first as a plain single-alpha
background with no dark base underneath (mirroring the OLD `NavIndicatorColor` shape — a flat
`neonCyan.copy(alpha=0.16)` wash with nothing else). Rendering it under `renderTvBottomBarVizPalettes`
(a green palette, `busyVizBackdrop()`'s warm orange/magenta gradient behind it) showed the backdrop's
own hue visibly bleeding through and muddying the intended green into an olive/brown — and a pale
`sterlingSilver` palette was nearly invisible against the busy backdrop at the same alpha. **A flat
translucent wash with no opaque-ish base under it inherits whatever's behind it, hue and all** —
this is the general failure mode to watch for any time a "docked/active" indicator is asked to be
"obvious at a glance" over an unpredictable backdrop. Fix: route the docked wash through the SAME
`orpheusChromeWash` the idle plate uses (dark base first, then the accent wash on top) — no shadow
added (kept the bottom bar's per-item fills cheap, since up to ~5 items can be docked
simultaneously vs. the top bar's ~4 persistently-shadowed elements), just the two flat
`.background()` calls, which was enough: the dark base blocks most of the backdrop before the wash
color has anything to muddy against. Needed its OWN (much higher) wash alpha too — 0.5f vs.
`PlateWashAlpha`'s 0.42f for the shadowed/bordered idle plate — since without a border+shadow to
help it read as "a deliberate surface," the flat-under-a-dark-base fill needs more of its own
opacity to still look intentional rather than washed out.

**General lesson**: when a color-wash design gets "tune the alpha by eye" instructions, always
render it against BOTH a flat dark ground AND the busiest/brightest available backdrop, AND against
at least one PALE/desaturated test palette in addition to vivid ones — the busy-backdrop-bleed
failure mode and the pale-palette-contrast failure mode are different risks that vivid-color-on-dark
renders alone won't surface. `DjLayoutRenderHarness`'s `VizTestPalettes` (pink=HeartbeatViz's real
`synthPink`, green=LavaLampViz's real `synthGreen`, orange=a vivid non-pink/green control, pale=
`sterlingSilver` for the contrast stress test) plus `busyVizBackdrop()` is the harness combination
that caught both issues in this session — reuse that list rather than inventing new stand-in colors
each time this chrome is touched again.

## Deliberate exception (STILL current, unlike the cosmicPurple one above): panel focus border ≠ panel accent
`CollapsibleColumnPanel`'s thick TV region-focus border (`TvPanelFocusBorderWidth = 5.dp`) uses
`effects.title.titleColor.lighten(0.2f)`, NOT the panel's own `color` param (each docked panel has
its own fixed brand accent, e.g. Pulsar=cosmicPurple, Mix=neonCyan, unrelated to the visualization).
Rendered both ways: the panel's own accent doesn't reliably "stand out" against its own chrome
(same hue driving both the fill and the border), and disagrees with the bar-level viz language.
`lighten(0.2f)` was kept after rendering the pale palette too — it didn't look over-brightened even
for an already-light `sterlingSilver` accent, and gave vivid/dark accents a visible boost against a
busy backdrop.

## Blast radius check before touching a shared modifier's signature
Grep every call site before changing `orpheusRaisedPlate`'s or `orpheusChromeWash`'s params — as of
this session that's `AppTitleTreatment.kt`, `DjTvTopBar.kt` (`TvTopBarButton`), `EnumDropdown.kt`
(`TvInlinePicker`), and `DjTvBottomBar.kt` (`TvBottomBarItem`'s docked branch, via
`orpheusChromeWash` directly, not `orpheusRaisedPlate`). All four now derive their accent from
`effects.title.titleColor` — if a future change wants a DIFFERENT accent for just one of them,
that's a deliberate divergence to call out explicitly, not something to do quietly, since the
whole point of this design is "the bars read as one consistent piece of chrome."

## EnumDropdownMenu: D-pad focus now lands on the selected row, not row 0
`FocusRequester` on the row where `index == selectedIndex`, `requestFocus()` called in the SAME
`LaunchedEffect(selectedIndex, entries.size)` right after `listState.scrollToItem(...)` (same
coroutine, after the suspend point — `scrollToItem` synchronously force-remeasures before
returning, so the target row's focus node already exists by then). `if (selectedIndex >= 0)` guards
both calls. Each row also got its own focus-wash background (`color.copy(alpha=0.18f)`) since a
plain `clickable` row has no persistent focus visual otherwise in this codebase — "focus lands in
the right place" is only useful if a Up/Down-browsing user can then see it move.

## Verification gotcha: @Preview is IDE-only in this repo, not exercised by jvmTest
`./gradlew :apps:djapp:shared:jvmTest` never renders `@Preview` functions — only the hand-rolled
`ImageComposeScene`-based `@Test` functions in `DjLayoutRenderHarness.kt` produce PNGs. Any
"does this actually look right" claim needs a harness test + Read-the-PNG, not just a passing
compile plus a `@Preview` that nobody's IDE has opened.

## Related
[[djapp_tv_focus_idle_fade]] — the 5s-idle-fade feature built on top of this bar chrome (same
session, same `TvFocusRegionHolder`/`tvFocusRegionBorder`, separate concern).

## Verification
`./gradlew compileKotlinJvm :apps:djapp:androidApp:compileOgDebugReleaseKotlin
:apps:djapp:shared:jvmTest` — green after every round described above, most re-run with `--rerun`
after touching the render harness itself. PNGs read via the Read tool (it renders PNG bytes
directly) after every wash-alpha change — never trusted a "should look right" claim without an
actual render, since two of the three design rounds in this session were rejected/fixed specifically
because a render revealed something a description alone didn't.
