---
name: cmp-launch-focus-and-scene-input
description: CMP 1.12 skiko LaunchedEffect requestFocus vs BoxWithConstraints ordering (dock top bar), and ImageComposeScene tricks for focus, keys and drag velocity tests
metadata:
  type: reference
---

# Launch focus on the dock's play/pause (verified 2026-09-24, CMP 1.12)

- The dock's play/pause requests focus from `LaunchedEffect(Unit)`. It is `DockDome` in the bottom bar
  since the Task 23 redesign; before that it sat in `DjTvTopBar`'s `BoxWithConstraints`.
- skiko `BaseComposeScene.setContent` calls `FrameRecomposer.performFrameDispatch()` right after the first
  composition, so effects run BEFORE measure. A target inside a subcomposing layout (`BoxWithConstraints`)
  composed in a scene's first frame misses the request and prints `FocusRelatedWarning: FocusRequester is
  not initialized` (CMP 1.12 prints, it does not throw).
- In the app the dock arrives a frame after launch (`DjLayoutBox` measures first), so the request lands.
  Scene tests must mimic that: compose the bar behind a `mutableStateOf(false)` flipped after one render,
  on `StandardTestDispatcher(scheduler)` with `scheduler.runCurrent()` per frame. See `DockDomeTest`.
- Desktop: `clickable`'s key handler treats Enter, NumPadEnter, DirectionCenter AND Spacebar as click keys;
  skiko `handleFocusKeys` moves focus only on Tab/DirectionCenter/Back, so arrows bubble to the window's
  `onKeyEvent` (`VibeStepKeys`). Desktop clicks request focus (`isRequestFocusOnClickEnabled() = true`).
- Since 2026-09-25 every `VibeTransportItem` (phone bar, rail, dock) wears `DomeIndication` (round
  press/hover light on the ring, no focus layer) and a keyboard-only focus circle in its own layer,
  reading focus, input mode and `LocalTvFocusRegion`'s alpha (1 where absent) only in draw. The dock
  passes its accent as `focusColor`. Tests drive the fade with `region.alpha.snapTo(0f)`.

# ImageComposeScene input gotchas
- `sendPointerEvent` defaults `timeMillis` to wall-clock; rapid test moves read as a fling and commit
  a 24dp drag. Pass `timeMillis = <frame clock ms>` for deterministic velocity.
- Stand-in for the desktop window key hook: an ancestor `Modifier.onKeyEvent` only sees keys while
  something below it is focused.

Related: [[compose-overhang-hit-testing]], [[djapp-tv-nav-rework]]
