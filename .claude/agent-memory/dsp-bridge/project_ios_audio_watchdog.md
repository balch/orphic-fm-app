---
name: ios-audio-watchdog
description: iOS closed-loop audio host watchdog on fix/ios-bt-audio-watchdog — its governing constraint, and what still needs a device trace
metadata:
  type: project
---

`IosAudioEngine` recovery is a closed loop: a 1s watchdog reconciles `shouldBeRunning`
against the C++ `blocks_rendered` counter and repairs whenever they disagree.

**Governing rule for any change to this file: there must be no state in which
`shouldBeRunning` is true and nothing is trying to repair.** "Stop polling" is never an
acceptable fix to anything found here. A guard may decline to *repair*; it may not stop
the tick chain.

**Why:** three prior fixes (69112e88, d4c604c0, 28bc659e) tuned an open-loop retry
budget and none of them fixed the bug. `avEngine.isRunning` reports YES while producing
no audio, so every recovery path keyed on it latched shut and audio stayed dead until
relaunch. The render counter is the only signal that does not lie.

**How to apply:** when reviewing or fixing this file, trace the proposed change for a
terminal state before anything else. Prefer adding a condition over removing a loop. The
same rule is why the interruption stand-down re-arms rather than being lengthened.

**Status as of 2026-07-25:** squashed onto `main` as a single commit. Branch
`fix/ios-bt-audio-watchdog` retains the granular history (12 commits, tip `e7b9854d`)
and the plan doc, neither of which came across. All builds green, both app frameworks
link. **Nothing is device-verified.** The whole feature is reasoning plus compiles,
which is why the code deliberately logs repair attempts, recovery, and route landing at
`log.info` (they survive release log levels) — a device trace is the only verification
this can ever get.

A second review pass landed with the squash, after `e7b9854d`:
- An explicit play tap (`ensureRunning`, `userInitiated = true`) now bypasses BOTH
  stand-down gates. Previously it consulted `otherAudioOwnsTheRoute()` like any
  reactive path, and because that function *re-arms* the 30s suspension as a side
  effect, a suppressed tap extended the very stand-down that suppressed it — a user
  tapping play while a podcast ran got silence for as long as the podcast lasted. The
  bypass has to be "don't call it", not "ignore the result".
- `repairHost`'s doc claimed non-stalled callers stay at step 1. They do not; `stalled`
  only decides whether a success rc is trustworthy, and the rc-gated ladder still
  reaches the step-3 rebuild on failure.
- `loadGraphAndSync()` is serialized on a `SynchronizedObject`. Registering the
  engine-recreated callback before `audioEngine.start()` made concurrent entry real.
- The three notification observers are guarded by `engineLock`, and
  `registerNotifications()` is skip-if-registered rather than
  tear-down-and-rebuild-every-call.

Two things a device trace should settle:
- whether `AVAudioSession.isOtherAudioPlaying` actually reads true during a CallKit
  call (the C-1 stand-down guard depends on it; if it reads false, the churn loop
  returns after 30s). Note the play-tap bypass above does not help here — it is the
  *tick* chain that would churn.
- whether the deliberate absence of a rate-change branch holds on a device whose
  wireless output runs 44.1k and whose built-in speaker runs 48k

See [[ios-audio-verification-tasks]] for how to build it.
