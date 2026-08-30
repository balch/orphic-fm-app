# DJ App TV: Glass Gate Moved to Real Hardware, Not Layout (2026-08-29)

## The ask, precisely
On-device measurement on a real Chromecast with Google TV: panel glass ON = ~27fps/100% janky;
glass OFF = ~33fps/86.5% janky (~6ms/frame cost). Desktop runs the identical UI at ~117fps
(median frame 8.3ms, 0.4% of frames over 16.7ms) — glass is free there. Wanted: glass OFF on real
TV hardware, unchanged (glass ON) everywhere else, INCLUDING a wide/fullscreen desktop window.

## Why `TvGlassEnabled = false` alone was the wrong fix
Commit 4592c418a ("feat(tv): turn the panel glass on") gated `TvGlassEnabled` behind
`LocalTvFocusChrome` only (see [[djapp_tv_focus_treatment]]). That compositionLocal is a LAYOUT
signal, not a hardware one: `DjAppScreen.kt` provides it `true` for its whole `isLargeScreen`
branch, and `DjLayoutMode.determineLayoutMode()` (`DjLayoutMode.kt`) enters `LargeScreen` purely
from width/height ≥ 900x500dp + `tvModeAllowed` — no platform check anywhere in that function.
`LocalTvModeAllowed` defaults `true` on every platform and is overridden ONLY by desktop's
`apps/djapp/desktopApp/.../main.kt`, to `(windowState.placement == WindowPlacement.Fullscreen)`.
Net effect: a FULLSCREEN desktop window past the size threshold really does enter `LargeScreen`
today, with `LocalTvFocusChrome = true`, on the same ~117fps hardware that can afford glass for
free. Flipping the bare const would have silently killed glass there too — confirmed this via the
desktop `main.kt` source before touching anything, per CLAUDE.md's "trace root cause before
editing" rule.

## The fix: a second, hardware-only compositionLocal
Added `val LocalTelevisionHardware = compositionLocalOf { false }` next to `LocalTvFocusChrome` in
`RaisedAccentSurface.kt`, with both KDocs rewritten to state the LAYOUT vs HARDWARE distinction
explicitly (each links to the other and names conflating them as "exactly the bug" the hardware
gate fixes — worth keeping that framing if either doc drifts again).

New expect/actual seam, mirroring `PlatformSafeArea.kt`'s exact shape (this module has only
android/jvm/ios source sets, no wasm):
- `apps/djapp/shared/src/commonMain/.../PlatformTelevisionHardware.kt` — `@Composable expect fun isTelevisionHardware(): Boolean`
- `.../androidMain/.../PlatformTelevisionHardware.android.kt` — delegates to the pre-existing `Context.isTelevision()` in `TvMode.android.kt` (UiModeManager + leanback feature, same pattern `tvDensityScale()` already used)
- `.../jvmMain/.../PlatformTelevisionHardware.jvm.kt` — hardcoded `false`
- `.../iosMain/.../PlatformTelevisionHardware.ios.kt` — hardcoded `false`

`DjAppScreen.kt`'s `isLargeScreen` `CompositionLocalProvider` now provides all three locals:
`LocalTvFocusChrome provides true`, `LocalTvFocusRegion provides focusRegion`, and
`LocalTelevisionHardware provides isTelevisionHardware()` (a composable call inlined directly as
an argument — fine since `DjAppScreen` itself is `@Composable`).

`CollapsibleColumnPanel.kt`'s gate: `val glassEnabled = !LocalTelevisionHardware.current || TvGlassEnabled`
(was `!LocalTvFocusChrome.current || TvGlassEnabled` — the unused `tvChrome` local was deleted).
`TvGlassEnabled` flipped back to `false` — this REVERSES 4592c418a's default, on purpose, now that
the gate is hardware-based instead of layout-based. `true` still exists as the on-device A/B
switch; its KDoc keeps the measured frame numbers and now states `false` is the shipping default.

## Gotcha: previews/tests simulating "the TV path" via the OLD signal go silently inert
Two existing call sites reached "the TV glass code path" by providing ONLY
`LocalTvFocusChrome provides true`: `CollapsibleColumnPanelTvGlassPreview` (CollapsibleColumnPanel.kt)
and both `renderGlassSwitchComparison` / `renderFullTvScreen` in `DjLayoutRenderHarness.kt`
(jvmTest). Since JVM's `isTelevisionHardware()` actual is hardcoded `false`, all three would now
ALWAYS render glass ON regardless of `TvGlassEnabled`'s value — the preview/test would still
compile and pass, just silently stop proving anything. Fixed by adding
`LocalTelevisionHardware provides true` alongside `LocalTvFocusChrome provides true` at all three
sites, and rewording labels ("TV path" → "TV hardware path"). Confirmed the fix visually by reading
the rendered PNG (`apps/djapp/shared/build/djapp-render/glass-switch-comparison.png` — Read tool
renders PNGs directly, see [[djapp_tv_focus_treatment]]): left column ("Glass ON — non-TV") shows
the busy sunset/bokeh backdrop bleeding through translucently; right column ("TV hardware —
TvGlassEnabled = false") is flat opaque navy. Before adding `LocalTelevisionHardware`, both columns
rendered identically translucent — the test was passing while demonstrating nothing.

**General lesson**: whenever a compositionLocal-gated behavior splits into a layout-signal +
hardware/cost-signal pair, grep every existing `CompositionLocalProvider(OldSignal provides true)`
call site (previews AND test harnesses, not just production code) that stands in for "simulate the
gated path." Each one needs the new local added too, or it degrades into "always takes the
unconditional branch" — a false-green result, not a caught regression.

## Verified NOT a bug: every other `LocalTvFocusChrome` consumer
Grepped every reader of `LocalTvFocusChrome` repo-wide: `RotaryKnob.kt`, `BenderFaderWidget.kt`,
`TimerPanel.kt`, `MixerFader.kt` (D-pad focus-treatment branch), `EnumDropdown.kt` (doc note only).
All of them correctly use it as a LAYOUT signal (focus chrome / screen-space decisions that a big
desktop window legitimately needs too), never as a stand-in for "on real TV hardware."

**Correction (2026-08-29, later session)**: `PulsarPanel.kt`'s Row 1 gate (VIBE/ROOT/SCALE/ENV)
was NOT actually fine — it used `LocalTvFocusChrome` to skip composing the row, which meant a
tablet or fullscreen desktop window entering `LargeScreen` also lost ROOT/SCALE/ENV with zero
other entry point in the app (VIBE alone is duplicated in the TV top bar). Fixed by switching that
one gate to `LocalTelevisionHardware`, so the row now only disappears on real TV hardware. This is
exactly the bug class this file documents — the earlier "None needed changing" sign-off on
PulsarPanel was wrong; a grep for the *symbol* found every reader, but confirming each one's
*intent* (does this really only need a layout signal, or does it gate the sole path to a control
with no other entry point?) needs the same scrutiny [[djapp_tv_nav_rework]]'s reachability audit
used for VIBE/ROOT/SCALE/ENV in the first place. See [[djapp_large_screen_toggle_fixes]].

## Files
- `ui/widgets/src/commonMain/kotlin/org/balch/orpheus/ui/infrastructure/RaisedAccentSurface.kt`
- `ui/widgets/src/commonMain/kotlin/org/balch/orpheus/ui/panels/CollapsibleColumnPanel.kt`
- `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjAppScreen.kt`
- `apps/djapp/shared/src/{commonMain,androidMain,jvmMain,iosMain}/.../PlatformTelevisionHardware*.kt` (new)
- `apps/djapp/shared/src/jvmTest/kotlin/org/balch/orpheus/djapp/DjLayoutRenderHarness.kt`
