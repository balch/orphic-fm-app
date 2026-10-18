# Orpheus Record Panel on Android: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The desktop REC panel works the same on the Orpheus Android app. It records, lists, plays and deletes takes in `filesDir/recordings/<take-id>/` with the same three files, and the recording-to-vibe skill can pull a take off the phone.

**Architecture:** `OboeEngine` owns the existing `orpheus::RecordRing` and writes each rendered block into it right after `orpheus_engine_process`. A new `OboeOutputCapture` drains the ring over JNI, the same way desktop does. The java.io file store and WAV writer move to a source set that JVM and Android share, and each platform provides its own root folder. Android gets an `AudioTrack` take player. The feature joins the Android app graph and the Compact panel set.

**Tech Stack:** C++ (Oboe, JNI), Kotlin Multiplatform, Metro DI, Compose, Android `AudioTrack` (float PCM).

**Spec:** `docs/plans/2026-10-17-orpheus-record-panel-design.md` (desktop design; this plan ports it unchanged). The user asked for "the functionality as is on Android", so there is no new spec.

## Global Constraints

- Same on-disk layout as desktop: `<root>/<take-id>/{audio.wav,preset.json,telemetry.json}`, 32-bit float stereo WAV.
- Android root: `File(context.filesDir, "recordings")`. Desktop root stays `~/.config/orpheus/recordings`.
- The audio callback takes no lock and allocates nothing for recording.
- `OutputCapture.sampleRate` must report the stream's real rate, which may not be 48000, and must stay the same for the whole take.
- Desktop behavior and tests stay unchanged; the desktop app must still build.
- `features:record` stays off the wasm and iOS app graphs.
- Parallel implementers share one worktree: no `./gradlew --stop`, never stash, clean or revert files you did not change, and stage explicit paths only (never `git add -A`).

## Review Focus

1. **The route changes mid-take** (Bluetooth earbuds connect, and the rate goes from 48k to 44.1k or the reverse). Expected: the WAV never mixes rates. The native ring disarms on a rate change, so the take ends where the change happened. Owned by Task 1.
2. **The app is backgrounded while recording** (the stream is parked). Expected: no crash and no garbage audio; the take just has no new frames until resume. Owned by Task 1 (no code needed; the reviewer confirms that `park()` does not touch the ring).
3. **Playing a take that is still being written, or one whose WAV is truncated.** Expected: the player clamps to the data it can read and never throws onto the UI. Owned by Task 2 (`WavHeader.read` test with a short file).
4. **Seeking while playing, then reaching the end.** Expected: position resets to 0 and `playing=false`, like desktop. Owned by Task 2.
5. **The phone panel is too narrow for REC's controls.** Expected: the panel fits on a phone in portrait and landscape without clipping. Owned by Task 3 (device check).

---

### Task 1: Native capture on Android

**Files:**
- Modify: `apps/orpheus/androidApp/src/main/cpp/OboeEngine.h`, `apps/orpheus/androidApp/src/main/cpp/OboeEngine.cpp`
- Modify: `apps/orpheus/androidApp/src/main/cpp/jni_bridge.cpp` (the djapp CMake compiles the same file)
- Modify: `core/audio/src/androidMain/kotlin/org/balch/orpheus/core/audio/dsp/OboeAudioBridge.kt`
- Create: `core/audio/src/androidMain/kotlin/org/balch/orpheus/core/audio/dsp/OboeOutputCapture.kt`
- Modify: `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/OutputCapture.kt` (the KDoc says "Desktop only"; change it to "Desktop and Android")

**Interfaces:**
- Produces: `OutputCapture` bound in `AppScope` on Android by `OboeOutputCapture`.

- [ ] **Step 1:** In `OboeEngine.h`, include `"../../../../../../liborpheus_dsp/src/orpheus_record_ring.h"` (or whatever relative or include path the CMake already exposes for `orpheus_dsp.h`; check `CMakeLists.txt` include dirs) and add a member `orpheus::RecordRing record_ring_{1 << 18};`. Add the same four methods as `DesktopEngine`: `void startRecording(); void stopRecording(); int drainRecording(float* out, int maxFrames); uint64_t recordDroppedFrames() const;`.
- [ ] **Step 2:** In `onAudioReady`, call `record_ring_.write(static_cast<float*>(audioData), numFrames);` right after `orpheus_engine_process`. Implement the four methods the same way as `liborpheus_dsp/desktop/DesktopEngine.cpp` (`startRecording` = `discard()` then `arm()`).
- [ ] **Step 3:** In `reopenLocked`, inside the `new_sr != old_sr` branch, call `record_ring_.disarm();` with a one-line comment: a take must not mix sample rates, so it ends at the route change.
- [ ] **Step 4:** In `jni_bridge.cpp`, add `nativeStartRecording`, `nativeStopRecording`, `nativeDrainRecording(jfloatArray): jint` and `nativeRecordDroppedFrames(): jlong` for `OboeAudioBridge`. Copy the chunked `SetFloatArrayRegion` drain from `liborpheus_dsp/desktop/jni_bridge_desktop.cpp:266-282`. Add the matching `external fun`s to `OboeAudioBridge.kt`.
- [ ] **Step 5:** Create `OboeOutputCapture(private val bridge: OboeAudioBridge) : OutputCapture` with `@Inject @ContributesBinding(AppScope::class)`. `start()` latches `bridge.nativeGetSampleRate()` into a `@Volatile` field before it calls `nativeStartRecording()`. `sampleRate` returns the latched rate when it is above 0, else the live `nativeGetSampleRate()` when that is above 0, else 48000. `stop()`, `drain()` and `droppedFrames()` delegate to the bridge.
- [ ] **Step 6: Verify:** `./gradlew :apps:orpheus:androidApp:assembleDebug :apps:djapp:androidApp:assembleDebug :core:audio:compileAndroidMain` (use `./gradlew :apps:djapp:androidApp:tasks` to find the right variant name if `assembleDebug` is ambiguous). Expected: BUILD SUCCESSFUL, and native compiles for both apps. Then run the C++ `record_ring` suite with the CLAUDE.md C++ test command and the suite name `record_ring` (confirm the name with `--list`). Expected: all pass.
- [ ] **Step 7: Commit:** `feat(audio): capture the Android output through the record ring`

### Task 2: Shared take storage and an Android player

**Files:**
- Modify: `features/record/build.gradle.kts`: add a `jvmAndroidMain` source set (`dependsOn(commonMain)`, with `jvmMain` and `androidMain` depending on it), following `ui/widgets/build.gradle.kts:27-30`.
- Move (git mv) to `features/record/src/jvmAndroidMain/kotlin/...`: `store/FileTakeStore.kt`, `store/WavFileWriter.kt`, `DefaultPresetSnapshotter.kt`, `SynthEngineMasterLevel.kt`.
- Create: `features/record/src/jvmAndroidMain/kotlin/org/balch/orpheus/features/record/store/WavHeader.kt`
- Create: `features/record/src/jvmMain/kotlin/org/balch/orpheus/features/record/store/DesktopTakeStoreModule.kt`
- Create: `features/record/src/androidMain/kotlin/org/balch/orpheus/features/record/store/AndroidTakeStoreModule.kt`
- Create: `features/record/src/androidMain/kotlin/org/balch/orpheus/features/record/playback/AudioTrackTakePlayer.kt`
- Test: `features/record/src/jvmTest/kotlin/org/balch/orpheus/features/record/store/WavHeaderTest.kt`

**Interfaces:**
- Produces: `TakeStore` bound in `AppScope` on both JVM and Android; `TakePlayer` bound on Android by `AudioTrackTakePlayer`.
- Produces: `data class WavHeader(val sampleRate: Int, val channels: Int, val dataOffset: Long, val frames: Long)` with `companion fun read(file: File): WavHeader?`. It returns null when the file is shorter than 44 bytes or the rate is not above 0. `frames` is clamped to what the file length holds.

- [ ] **Step 1: Failing test** `WavHeaderTest`: write a file with `WavFileWriter(file, 44100)` holding 1000 frames, then close it; `WavHeader.read(file)` gives `sampleRate == 44100`, `channels == 2`, `dataOffset == 44L`, `frames == 1000L`. A second case truncates the file to `44 + 8 * 10 + 3` bytes and expects `frames == 10L`. A third case uses a 20-byte file and expects `null`.
- [ ] **Step 2:** Run `./gradlew :features:record:jvmTest --tests '*WavHeaderTest*'`. Expected: FAIL (unresolved `WavHeader`).
- [ ] **Step 3:** Add the source set, do the moves, and implement `WavHeader`. Switch `FileTakeStore.wavDuration` to it (`frames / sampleRate`). Remove `FileTakeStore`'s `@Inject` secondary constructor and its `@ContributesBinding`. `DesktopTakeStoreModule` is a `@ContributesTo(AppScope::class) interface` with `@Provides fun provideTakeStore(): TakeStore = FileTakeStore(File(System.getProperty("user.home"), ".config/orpheus/recordings"))`. `AndroidTakeStoreModule` does the same with `(context: Context)` and `File(context.filesDir, "recordings")`. Keep the current scoping (none).
- [ ] **Step 4:** Implement `AudioTrackTakePlayer` (`@Inject @SingleIn(AppScope::class) @ContributesBinding(AppScope::class)`). Keep `JavaSoundTakePlayer`'s contract and threading: one `Dispatchers.IO.limitedParallelism(1)` scope, the same `PlaybackState` updates, end-of-take resets to `playing=false, positionSec=0`, and errors are logged and reset the state. Stream the WAV with `AudioTrack` in `MODE_STREAM` and `ENCODING_PCM_FLOAT`, stereo, at `WavHeader.sampleRate`, with `USAGE_MEDIA`, reading 4096-frame chunks from `dataOffset` in a writer loop. `seek` repositions the file read and calls `pause()`/`flush()` (and `play()` again if it was playing). The position is the frame the current write segment started at plus `playbackHeadPosition`.
- [ ] **Step 5:** Run `./gradlew :features:record:jvmTest :features:record:compileAndroidMain` (or the module's Android compile task name) and `./gradlew :apps:orpheus:desktopApp:build -x test` (or the matching `compileKotlinJvm`). Expected: all `features:record` JVM tests pass, Android compiles, and desktop builds.
- [ ] **Step 6: Commit:** `feat(record): share the take store with Android and add an AudioTrack player`

### Task 3: Wire REC into the Android app

**Files:**
- Modify: `apps/orpheus/shared/build.gradle.kts`: add `api(project(":features:record"))` to `androidMain.dependencies` and update the jvmMain comment ("Desktop and Android only").
- Modify: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/FactoryPanelSets.kt`: add `expand(PanelId.RECORD)` to `Compact`, right after `expand(PanelId.LOOPER)`.
- Check: `CompactLandscapeScreen.kt` and `CompactLandscapeHeaderPanel.kt`. If landscape builds its panel list some other way than `FactoryPanelSets.Compact`, add RECORD there too.
- Modify: `.claude/skills/orpheus-recording-to-vibe/SKILL.md`: add an "On Android" step. It pulls the newest take with `adb exec-out run-as org.balch.orpheus ls -t files/recordings` (see the `ls -1` gotcha in `.claude/skills/grab-ai-vibes/SKILL.md`) and `adb exec-out run-as org.balch.orpheus tar -cf - -C files/recordings <take-id> | tar -xf - -C ~/.config/orpheus/recordings`. The rest of the skill then reads it from the usual desktop path. Note that `run-as` needs a debug (debuggable) build. Also widen the description to "desktop or Android".

- [ ] **Step 1:** Make the edits above.
- [ ] **Step 2: Verify:** `./gradlew :apps:orpheus:androidApp:assembleDebug :apps:orpheus:desktopApp:build` and `./gradlew :apps:orpheus:shared:jvmTest :features:record:jvmTest`. Expected: BUILD SUCCESSFUL with no Metro missing-binding errors on Android or JVM, and `compileKotlinWasmJs` for `apps:orpheus:shared` still succeeds (features:record is not on that graph).
- [ ] **Step 3: Commit:** `feat(record): REC panel on the Android app`

### Task 4: Device verification (controller and user)

- [ ] Install the debug APK on a connected device. On the REC panel: record about 10 seconds, stop, then confirm the take is listed, plays, seeks and deletes.
- [ ] Pull the take with the skill's Android step. Check that `audio.wav` reports the device's real rate (`afinfo` or a header read) and that the three files are present.
- [ ] Check the panel layout in portrait and landscape.
- [ ] The user eye-tests before anything is pushed or merged.
