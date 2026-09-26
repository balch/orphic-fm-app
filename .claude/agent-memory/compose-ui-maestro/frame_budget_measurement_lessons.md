---
name: frame-budget-measurement-lessons
description: Lessons from the navigator perf task (Task 20, 2026-09-24): what ScopeCounter really counts, which pixel-identical refactors held, and which did not
metadata:
  type: reference
---

From the vibe-navigator perf task (FrameMeter.kt / FrameBudgetTest.kt in apps/djapp/shared/src/jvmTest).

- `CompositionObserver.onScopeEnter` fires at every `startRestartGroup`, including children that then skip,
  and also when a `derivedStateOf` dependency changes but the derived value does not (the "reread" path).
  So "0 scopes/frame" needs no invalidation at all; to gate on a threshold, write a plain int state only
  when it crosses, rather than using derivedStateOf.
- Pixel-identical swaps that held (201/201 renders byte-identical): a cached `ShaderBrush` subclass
  calling `LinearGradientShader`/`SweepGradientShader` with the same from/to/colors/stops as
  `Brush.horizontalGradient(*pairs)`; M3 `Text(color: ColorProducer)` instead of `Text(color = c)`;
  `layout { placeRelative(x.roundToPx(), 0) }` instead of `Modifier.offset(x)`; recording draws into
  an `obtainGraphicsLayer()` layer and replaying with `drawLayer` (skiko RenderNode, no layer paint).
- A swap that did NOT hold: moving a glass modifier (clip + liquid + border) from a NavigationRail onto a
  sibling Box behind it shifted the rail's text AA by up to 6/255. Wrapping the rail in a Box that wears
  the unchanged glass modifier kept pixels identical and still let the rail skip.
- Some scenes are bimodal across full-suite runs (S2a ring 279 vs 313 B, S9 264 vs 280 B): budget
  off the lower mode with room for the higher; a single run's "<1.2% variance" can mislead.
- Gradle's `--rerun` binds to the task right before it on the command line: give each test task its
  own `--rerun`, or the others come back UP-TO-DATE with stale XML.
- "Something still moves" guards (FrameMeter.image() pixel diffs) are mutation-checkable: disabling
  the zip draw, the countdown pulse or the glass accent each fails its scene.
- A dynamic `compositionLocalOf` can only be escaped by one reader scope; a child that writes it into a
  parent-owned `MutableState` during composition (as rememberUpdatedState does) lets draw-phase readers
  follow it with no lag and no parent recomposition. See [[orpheus-theme-notes]].
