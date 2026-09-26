---
name: dome-cues-and-shared-worktree-testing
description: How the DJ app's play/pause dome gets navigator moves and the wiggle without call-site params, and test lessons from Task 22 (2026-09-24): S7 warmup flakiness, same-frame move arrival hides double rolls, private scratch worktree for A/B
metadata:
  type: reference
---

- Every `VibeTransportItem` reads `LocalVibeDomeCues` (static local, provided once in `DjApp.kt`),
  so a dome anywhere (phone bar, rail, dock) rolls and wiggles with no new call-site parameter.
  Unprovided in previews and most tests, so existing scenes see no wiggle. The wiggle flag lives in
  `AppPreferences.domeSwiped`; "spent this launch" on `DomeWiggleLaunch`, an AppScope singleton
  (`graph.domeWiggleLaunch`), so an Android activity recreation doesn't re-arm it. TalkBack's
  Next/Previous actions call `swipeLearned()` too.
- Test sensitivity: with the navigator on an `UnconfinedTestDispatcher`, a swipe's own move lands in
  the same frame as its release, so a double roll is pixel-identical to one. To prove the dome
  ignores its own move, run the navigator on `StandardTestDispatcher(navScheduler)` and
  `runCurrent()` it a frame or more after release. Mutation-check such tests.
- `FrameBudgetTest` S7 (swipe bytes/move): a scene's FIRST swipe costs 17-44 KB/move depending on
  what ran before; fixed in Task 25 (2026-09-25) with two warm-up swipes of its own, after which cold
  and in-suite agree at ~18.3 KB. The RigVibeNames middle vibe is "Drift" so still-dome scenes, whose
  names now scroll paused if too long, stay frame-free.
- When sibling agents' WIP breaks the shared worktree's compile, a private
  `git worktree add --detach <scratchpad>/wtNN <sha>` plus copied files gives isolated, fast runs
  (the Gradle build cache makes it ~20 s) and clean A/B against a base commit. Remove it after.

Related: [[frame-budget-measurement-lessons]]
