---
name: djapp-ios-test-compile-broken
description: djapp/shared iOS test compilation fails on clean main (TabMergeTest type mismatch, VibeInfoMapperTest illegal K/Native symbol names) — pre-existing, not caused by commonMain changes.
metadata:
  type: reference
---

## djapp/shared Pre-Existing Broken iOS Test Compile (as of 2026-07-14)
- `:apps:djapp:shared:compileTestKotlinIosArm64` / `compileTestKotlinIosSimulatorArm64` FAIL on unmodified `main` — unrelated to any commonMain change. Two files: `commonTest/.../variant/TabMergeTest.kt` (`List<Any>` vs `List<DjRoute>` argument-type mismatch) and `commonTest/.../vibeinfo/VibeInfoMapperTest.kt` (backtick test names containing `()`/`,` are illegal Kotlin/Native symbol characters — JVM tolerates these, K/Native doesn't). `-x test` does NOT skip these; `check`/`build` still pulls in test *compilation* (just not execution) for every KMP native target.
- Don't waste time re-diagnosing this if `:apps:djapp:shared:build` fails there — confirm via `git stash` + rerun the two specific tasks against clean `main` before assuming your change broke it. For a clean signal on a commonMain-only change, prefer targeted tasks: `compileKotlinJvm` + `compileAndroidMain` (+ `compileKotlinIosArm64`/`compileKotlinIosSimulatorArm64` for main-source-only iOS coverage) over the broad `:build`.
