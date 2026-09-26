---
name: imagecomposescene-shared-lifecycle-registry
description: Why LifecycleStartEffect (any observer added from composition) races in ImageComposeScene tests (CMP 1.12), the HoldWhileStarted alternative, and how to drive a hand-moved lifecycle in a scene test (2026-09-25)
metadata:
  type: reference
---

- ImageComposeScene gives EVERY scene one JVM-global `LifecycleRegistry` (`EmptyArchitectureComponentsOwner`
  in ui-desktop's PlatformContext.skiko.kt: `createUnsafe`, RESUMED, no thread checks).
- `collectAsStateWithLifecycle` -> `repeatOnLifecycle` adds/removes observers from `Dispatchers.Main`
  (the Swing EDT in jvmTest). `LifecycleStartEffect`/`LifecycleEventEffect` add theirs from the
  composition thread (the test worker). Together they race: `IndexOutOfBoundsException` inside
  `LifecycleRegistry.addObserver` (parentStates), 1 in 4 MarqueeLabelTest runs, and a corrupted
  global observer map can break later tests. Production is safe (composition thread == main).
- DJ app fix: `HoldWhileStarted(key) { hold(): DisposableHandle }` in ProgressWave.kt collects
  `lifecycle.currentStateFlow` in a coroutine launched UNDISPATCHED inside a DisposableEffect: no
  observer registered, and a stop releases without a frame. Don't gate on `collectAsState` of the
  state instead: a backgrounded app renders no frames, so a recomposition-gated release never runs.
- Test recipe (`TransportLifecycleTest`): provide `LocalLifecycleOwner` with
  `LifecycleRegistry.createUnsafe(owner)`; after the first frames run `runBlocking(Dispatchers.Main) {}`
  so the collectors' EDT registrations finish before the test thread moves the state.
- Desktop: minimise -> CREATED (ON_STOP), focus loss -> STARTED (ComposeContainer.updateLifecycleState).

Related: [[frame-budget-measurement-lessons]], [[dome-cues-and-shared-worktree-testing]]
