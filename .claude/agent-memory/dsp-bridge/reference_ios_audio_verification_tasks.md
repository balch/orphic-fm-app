---
name: ios-audio-verification-tasks
description: Gradle task names and coverage gaps when verifying iOS audio/bridge changes — the plan-doc guess is wrong and djapp needs its own link
metadata:
  type: reference
---

Verifying a change to `core/audio/src/iosMain` (or anything the iOS audio host
touches) needs this set:

```
./gradlew :core:audio:compileKotlinIosArm64
./gradlew compileKotlinJvm :core:foundation:jvmTest --tests "*PlaybackControllerTest*"
./gradlew :apps:orpheus:shared:linkDebugFrameworkIosArm64
./gradlew :apps:djapp:shared:linkDebugFrameworkIosArm64
```

Two traps:

- The task is `linkDebugFrameworkIosArm64`, **not** `linkPodDebugFrameworkIosArm64`.
  This project uses plain `binaries.framework`, not CocoaPods. The iOS watchdog plan
  doc (`docs/superpowers/plans/2026-07-24-ios-bt-audio-watchdog.md`) guesses the Pod
  name and is wrong.
- **Both apps share the iOS audio code.** Linking `:apps:orpheus:shared` alone leaves
  `:apps:djapp:shared` unverified. Link both.

Gradle prints no test counts. Read them from
`core/foundation/build/test-results/jvmTest/TEST-*.xml`.

New AVFAudio cinterop symbols are the usual compile break. Category properties
(`otherAudioPlaying`, `currentRoute`, `sampleRate`, `setActive`) import as lowercase
extensions from `platform.AVFAudio`; members of the main `@interface` (`portType`,
`outputs`) need only the class import. Check which one you have in the SDK header
before guessing.
