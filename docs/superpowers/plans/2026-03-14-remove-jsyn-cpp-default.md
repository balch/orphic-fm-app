# Remove JSyn / Make C++ Default Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove JSyn, all Kotlin DSP engine implementations, and the entire Kotlin audio graph infrastructure, making C++ the sole audio backend on all platforms (JVM desktop, Android, WASM).

**Architecture:** Three layers to remove: (1) JSyn library + jvmMain wrappers, (2) Kotlin DSP engine ports in commonMain (Plaits, Drums, Warps, etc.), (3) the audio graph infrastructure (DspFactory, AudioUnit/Input/Output, DspVoice, DspWiringGraph, DspGraphScheduler). After this, the Kotlin layer is a pure control surface — plugins store state and forward parameters to C++ via NativeDspBridge. C++ owns all audio processing.

**Tech Stack:** Kotlin Multiplatform, C++ (liborpheus_dsp), Metro DI, Gradle KTS

**Key insight:** `DspSynthEngine.setPluginPort()` already forwards ALL port values to C++ via `audioEngine.setPort(uri, symbol, value)` before calling `plugin.setPortValue()`. So plugin port setters only need to cache state — C++ already receives the values.

---

## Chunk 1: Rewire Entry Points

### Task 1: JVM Desktop — Always Use NativeDspAudioEngine

**Files:**
- Modify: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/AudioEngineProvider.kt`

- [ ] **Step 1:** Remove the `orpheus.engine` system property switch. Always return `NativeDspAudioEngine()`.
- [ ] **Step 2:** Verify: `./gradlew compileKotlinJvm` — SUCCESS
- [ ] **Step 3:** Commit: `"refactor: make NativeDspAudioEngine the only JVM desktop engine"`

### Task 2: WASM — Switch to C++ Worker Audio Engine

**Files:**
- Create: `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/core/audio/dsp/WasmNativeAudioEngine.kt`
- Modify: WASM DI wiring to provide `WasmNativeAudioEngine`

- [ ] **Step 1:** Create `WasmNativeAudioEngine` implementing `AudioEngine` + `NativeDspBridge`. Wraps `DspWorkerProxy`. Audio graph methods (`addUnit`, `setUnitEnabled`, `lineOutLeft/Right`) are no-ops. All `NativeDspBridge` methods delegate to worker proxy. Read `DspWorkerProxy.kt` fully for the existing command API.
- [ ] **Step 2:** Wire into WASM DI via `@ContributesTo(AppScope::class)` module providing `WasmNativeAudioEngine` as `AudioEngine`.
- [ ] **Step 3:** Verify: `./gradlew compileKotlinWasmJs` — SUCCESS
- [ ] **Step 4:** Commit: `"feat(wasm): switch to C++ Worker audio engine"`

---

## Chunk 2: Strip DspSynthEngine and DspVoiceManager

### Task 3: Strip DspSynthEngine of Kotlin DSP Graph Code

DspSynthEngine currently manages both the Kotlin DSP graph AND C++ forwarding. Remove all Kotlin graph code. After this, it only manages plugin state and NativeDspBridge.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspSynthEngine.kt`

- [ ] **Step 1: Remove constructor deps** — Remove `dspFactory: DspFactory`, `wiringGraph: DspWiringGraph`, `automationManager: DspAutomationManager` parameters.

- [ ] **Step 2: NativeBridge is always present** — Change `nativeBridge` from nullable cast to required cast: `val nativeBridge = audioEngine as NativeDspBridge`. Change `hasNativeEngine` to always `true`. Remove all `nativeBridge?.` → `nativeBridge.` and delete all `else` branches for "Kotlin DSP path".

- [ ] **Step 3: Remove graph init** — Remove `wiringGraph.initialize(voiceManager)`. Remove entire `setupAutomation()` method.

- [ ] **Step 4: Remove Kotlin DSP monitoring** — In `start()`, keep only the native monitor polling branch. Delete the `else` branch that polls `pluginProvider.stereoPlugin.getPeak()` / `voiceManager.voices[i].getCurrentLevel()`.

- [ ] **Step 5: Remove plugin AudioInput/Output accesses** — DspSynthEngine accesses plugin-specific `AudioInput`/`AudioOutput` properties for Kotlin graph wiring (e.g., `pluginProvider.delayPlugin.delay1TimeRampInput`, `pluginProvider.distortionPlugin.limiterLeftDrive`, `wiringGraph.drumDirectLimiterL.drive`, `pluginProvider.stereoPlugin.masterGainLeftInput`). Remove ALL such accesses. Full list of methods with wiringGraph/audio-graph side effects to strip:
  - `setDrumsBypass()`: keep `pluginProvider.drumPlugin.setBypass()` + `setRouting()`, remove wiringGraph references
  - `setDrive()`: keep `nativeBridge.nativeSetDrive()` + `setPort()`, remove `wiringGraph.drumDirectLimiter*`
  - `setTotalFeedback()`: keep `voiceManager` + `pluginProvider.voicePlugin`, remove `wiringGraph.totalFbGain`
  - `setResonatorMode()`: remove `wiringGraph.drumDirectResonator.setMode()`
  - `setResonatorStructure()`: remove `wiringGraph.drumDirectResonator.setStructure()`
  - `setResonatorBrightness()`: remove `wiringGraph.drumDirectResonator.setBrightness()`
  - `setResonatorDamping()`: remove `wiringGraph.drumDirectResonator.setDamping()`
  - `setResonatorPosition()`: remove `wiringGraph.drumDirectResonator.setPosition()`
  - `setResonatorMix()` / `updateDirectResonatorGains()`: remove wiringGraph wet/dry gain refs
  - `strumResonator()`: remove `wiringGraph.drumDirectResonator.strum()`
  - `setWarpsCarrierSource()` / `setWarpsModulatorSource()`: remove `plugin.outputs`, `plugin.modulatorRouteInput`, `plugin.dryInputLeft/Right` audio graph connections. Keep C++ setPort forwarding.
  - `getWarpsSourceOutput()`: remove `wiringGraph.voiceSumLeft/Right`, `wiringGraph.replSumLeft/Right`

- [ ] **Step 6: Remove test tone** — Delete `playTestTone()`/`stopTestTone()` implementations (Kotlin DSP only). Keep the interface methods as no-ops or TODO.

- [ ] **Step 7: Simplify automation** — `setParameterAutomation()`: remove `else` branch that calls `automationManager`. Only keep native path (`scheduleNativeAutomation`). `clearParameterAutomation()`: remove `automationManager.clearParameterAutomation()`.

- [ ] **Step 8:** Verify compiles (expect some errors from missing types — OK for now)
- [ ] **Step 9:** Commit: `"refactor: strip Kotlin DSP graph code from DspSynthEngine"`

### Task 4: Strip DspVoiceManager of DspVoice References

DspVoiceManager manages 12 `DspVoice` instances (each creating ~37 audio units). With C++ only, it should cache state and forward to `pluginProvider.voicePlugin`.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspVoiceManager.kt`

- [ ] **Step 1: Remove DspVoice/DspFactory deps** — Remove `audioEngine: AudioEngine`, `dspFactory: DspFactory`, `engineFactory: PlaitsEngineFactory` from constructor. Remove the `voices` list entirely.

- [ ] **Step 2: Strip all `voices[i].*` calls** — Every method that calls `voices[i].frequency.set()`, `voices[i].gate.set()`, `voices[i].plaits.setEngine()`, `voices[i].output.connect()`, etc. should keep only:
  - State cache updates (`_voiceTune[index] = tune`)
  - `pluginProvider.voicePlugin.*` calls (forwards to C++)
  - `pluginProvider.perStringBenderPlugin.*` / `pluginProvider.fluxPlugin.*` calls

- [ ] **Step 3: Simplify specific methods:**
  - `initialize()`: remove voice coupling wiring, keep idle state init
  - `setDuoEngine()`: remove Plaits engine creation and voice manipulation, keep cache + voicePlugin forwarding
  - `setDuoModSource()`: remove audio graph connect/disconnect, keep cache + voicePlugin forwarding
  - `setQuadTriggerSource()` / `setQuadPitchSource()`: remove audio port connect/disconnect
  - `setVoiceWobble()` / `fadeQuadVolume()`: make no-ops or remove (Kotlin DSP only)
  - `updateVoiceFrequency()`: remove (frequency calc for Kotlin oscillators, C++ handles this)

- [ ] **Step 4:** Verify compiles
- [ ] **Step 5:** Commit: `"refactor: strip DspVoice graph manipulation from DspVoiceManager"`

---

## Chunk 3: Strip Plugins and GlobalTempo

### Task 5: Refactor All 16 Plugins to Pure State + C++ Forwarding

Each plugin currently creates Kotlin DSP audio units via `DspFactory` and wires an internal audio graph. After refactoring, plugins are pure state containers — they store port values and forward to C++ via `audioEngine.setPort()`.

**Pattern for each plugin:**
1. Remove `dspFactory: DspFactory` constructor parameter
2. Remove all audio unit creation (`dspFactory.create*()`)
3. Set `audioUnits` to `emptyList()`
4. Set `inputs`/`outputs` to `emptyMap()`
5. Empty `initialize()` — no audio units to wire or register
6. Port setter lambdas: keep state update, remove audio unit manipulation (e.g., `delay1FeedbackGain.inputB.set(fb)` → just `_feedback = it`)
7. Remove "bridge methods" that expose `AudioInput`/`AudioOutput` (e.g., `delay1TimeRampInput`, `masterGainLeftInput`)
8. Remove `setPluginEnabled()` / `applyInitialBypassState()` overrides (C++ manages bypass)
9. Keep custom methods that only forward to `audioEngine.setPort()` (e.g., `StereoPlugin.setQuadVolume()`)

**Files to modify (16 plugins — 14 with DspFactory + 2 without):**
- `core/plugins/beats/src/commonMain/.../BeatsPlugin.kt` (no DspFactory, but overrides `audioUnits`/`connectPort`/`run` — update for interface changes)
- `core/plugins/bender/src/commonMain/.../BenderPlugin.kt`
- `core/plugins/delay/src/commonMain/.../DelayPlugin.kt`
- `core/plugins/distortion/src/commonMain/.../DistortionPlugin.kt`
- `core/plugins/drum/src/commonMain/.../DrumPlugin.kt`
- `core/plugins/duolfo/src/commonMain/.../DuoLfoPlugin.kt`
- `core/plugins/flux/src/commonMain/.../FluxPlugin.kt`
- `core/plugins/grains/src/commonMain/.../GrainsPlugin.kt`
- `core/plugins/looper/src/commonMain/.../LooperPlugin.kt`
- `core/plugins/perstringbender/src/commonMain/.../PerStringBenderPlugin.kt`
- `core/plugins/resonator/src/commonMain/.../ResonatorPlugin.kt`
- `core/plugins/reverb/src/commonMain/.../ReverbPlugin.kt`
- `core/plugins/stereo/src/commonMain/.../StereoPlugin.kt`
- `core/plugins/vibrato/src/commonMain/.../VibratoPlugin.kt`
- `core/plugins/voice/src/commonMain/.../VoicePlugin.kt` (no DspFactory, but imports AudioUnit/AudioInput/AudioOutput — update for interface changes)
- `core/plugins/warps/src/commonMain/.../WarpsPlugin.kt`

**Also modify:**
- `core/dsp-engine/src/commonMain/.../TtsPlugin.kt` — Remove `dspFactory`, `ttsPlayer`, `speechEffects` unit creation. Remove `isNative` checks — always use native path. Simplify `loadAudio/play/stopPlayback/isPlaying` to always cast `audioEngine as NativeDspBridge`.

- [ ] **Step 1:** Refactor plugins in batches (group by complexity):
  - **Simple** (state-only ports, no custom methods): Bender, Vibrato, Reverb, Grains, Looper
  - **Medium** (custom methods + routing): Delay, Distortion, Stereo, Resonator, Warps, PerStringBender
  - **Complex** (listeners, routing logic): Drum, DuoLfo, Flux
  - **Special**: TtsPlugin

- [ ] **Step 2:** For each plugin, follow the pattern above. Key attention points:
  - `DelayPlugin`: Port setters like MIX that call `setPluginEnabled` and `delay.clear()` — simplify to just caching state
  - `StereoPlugin`: Remove `getPeak()` (reads from Kotlin DSP peakFollower), keep `setQuadVolume()`/`setVoicePan()` (forward to C++)
  - `DuoLfoPlugin`: Remove `getCurrentValue()`/`getCurrentValueA()`/`getCurrentValueB()` (Kotlin DSP reads), remove `output` AudioOutput. LFO output is now only in C++ monitoring data.
  - `DrumPlugin`: Remove audio unit trigger mechanism, keep `trigger()` method forwarding to `audioEngine.triggerDrum()`
  - `FluxPlugin`: Remove audio output connections, keep `setClockSource()`/`setDrumTriggerSource()` etc. forwarding to C++

- [ ] **Step 3:** After each batch, verify compilation: `./gradlew compileKotlinJvm`
- [ ] **Step 4:** Commit per batch: `"refactor: strip Kotlin DSP from [batch] plugins"`

### Task 6: Refactor GlobalTempo

**Files:**
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/tempo/GlobalTempo.kt`

- [ ] **Step 1:** Remove `dspFactory: DspFactory` constructor parameter. Remove `ClockUnit` creation and `audioEngine.addUnit()` calls. Remove `getClockOutput()`/`getBeatClockOutput()` methods (Kotlin DSP clock outputs). Keep BPM state management and `setBpm()` — forward clock frequency to C++ via `audioEngine.setPort()` instead of setting `clockUnit.frequency`.

- [ ] **Step 2:** Update callers of `getClockOutput()`/`getBeatClockOutput()` — likely in `DspWiringGraph` (being deleted) and `FluxPlugin` (already stripped). Also update test files that construct `GlobalTempo` with `DspFactory`: `apps/composeApp/src/commonTest/.../TidalReplTuneTest.kt` and `TidalReplParsingTest.kt`.

- [ ] **Step 3:** Verify: `./gradlew :core:foundation:compileKotlinJvm`
- [ ] **Step 4:** Commit: `"refactor: remove ClockUnit from GlobalTempo"`

---

## Chunk 4: Simplify Interfaces

### Task 7: Simplify DspPlugin and AudioEngine Interfaces

Now that no plugin creates audio units, simplify the interfaces.

**Files:**
- Modify: `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspPlugin.kt`
- Modify: `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/AudioEngine.kt`

- [ ] **Step 1: Simplify DspPlugin** — Remove `audioUnits`, `inputs`, `outputs` properties. Remove `setPluginEnabled()`, `applyInitialBypassState()`. Remove `connectPort()`, `run()`, `activate()`. Keep: `info`, `ports`, `initialize()`, `onStart()`, `onStop()`, `setPortValue()`, `getPortValue()`.

- [ ] **Step 2: Simplify AudioEngine** — Remove `addUnit(unit: AudioUnit)`, `setUnitEnabled(unit: AudioUnit, enabled: Boolean)`, `lineOutLeft`, `lineOutRight`. These are Kotlin DSP graph methods. Keep: `start()`, `stop()`, `isRunning`, `sampleRate`, `getCpuLoad()`, `getCurrentTime()`, `setPort()`, `getPort()`, `triggerDrum()`.

- [ ] **Step 3: Fix compilation** — Update all implementors of these interfaces:
  - `NativeDspAudioEngine` (jvmMain) — remove `lineOutLeft/Right`, `addUnit`, `setUnitEnabled`
  - `OboeAudioEngine` (androidMain) — same
  - `WasmNativeAudioEngine` (wasmJsMain, created in Task 2) — already no-ops
  - All 16 plugins implementing `DspPlugin` — remove `audioUnits`, `connectPort()`, `run()` overrides
  - Update `DspSynthOrchestrator` or any code calling `plugin.applyInitialBypassState()` / `audioEngine.addUnit()`

- [ ] **Step 4: Move `dspSampleRate`** — Currently in `DspAudioPorts.kt` (being deleted). Move to `AudioEngine.kt` companion or a new minimal file in commonMain.

- [ ] **Step 5: Update test stubs** — `apps/composeApp/src/commonTest/kotlin/.../TestDspInfra.kt` — update `TestAudioEngine` and `TestDspFactory` to match simplified interfaces (or delete `TestDspFactory` entirely).

- [ ] **Step 6:** Verify: `./gradlew compileKotlinJvm compileKotlinWasmJs`
- [ ] **Step 7:** Commit: `"refactor: simplify DspPlugin and AudioEngine interfaces"`

---

## Chunk 5: Delete Dead Code

### Task 8: Delete Core DSP Infrastructure

**Files to delete from `core/audio/src/commonMain/`:**
- `DspUnits.kt` (SineOscillator, Envelope, DelayLine, PeakFollower, Limiter, Multiply, Add, etc. — all unit type interfaces)
- `DspFactory.kt` (factory interface)
- `DspAudioPorts.kt` (DspAudioInput, DspAudioOutput implementations — after moving `dspSampleRate`)
- `AudioPorts.kt` (AudioUnit, AudioInput, AudioOutput interfaces — IF fully removed from DspPlugin/AudioEngine in Task 7. Otherwise keep minimal stubs.)
- `DspProcessable.kt`
- `DspGraphScheduler.kt`
- `DspOscillators.kt` / `DspOscillatorFactories.kt`
- `DspMathUnits.kt` / `DspMathFactories.kt`
- `DspDynamicsUnits.kt` / `DspDynamicsFactories.kt`
- `DspUtilityUnits.kt` / `DspUtilityFactories.kt`
- `DspEffectFactories.kt`
- `DspLooperUnit.kt` / `DspTtsPlayerUnit.kt` / `DspSpeechEffectsUnit.kt`

**Files to delete from `core/dsp-engine/`:**
- `DspVoice.kt`, `DspWiringGraph.kt`, `DefaultWiringGraph.kt`
- `DspAutomationManager.kt`, `DspFactoryImpl.kt`
- `PlaitsEngineFactoryImpl.kt`
- `ExportDefaultGraph.kt` (jvmMain)
- `WasmPluginDspFactories.kt` (wasmJsMain)
- `jvmTest/` — entire test directory

- [ ] **Step 1:** Delete all files listed above
- [ ] **Step 2:** Remove `exportDefaultGraph` task from `core/dsp-engine/build.gradle.kts`
- [ ] **Step 3:** Fix compilation errors
- [ ] **Step 4:** Verify: `./gradlew compileKotlinJvm`
- [ ] **Step 5:** Commit: `"refactor: delete Kotlin DSP graph infrastructure"`

### Task 9: Delete All jvmMain JSyn Files

**Files to delete from `core/audio/src/jvmMain/` (keep NativeDspAudioEngine, DesktopDspBridge, AudioEngineProvider):**
- `OrpheusAudioEngine.kt` (JSyn engine)
- `JsynAudioPorts.kt`, `JsynDspUnits.kt`
- `JsynOscillatorFactories.kt`, `JsynMathFactories.kt`, `JsynMathUnits.kt`
- `JsynDynamicsFactories.kt`, `JsynEffectFactories.kt`, `JsynUtilityFactories.kt`
- `JsynSawtoothOscillatorWrapper.kt`, `JsynSpeechEffectsUnit.kt`
- `OfflineAudioEngine.kt`, `HardClipGenerator.kt`, `TanhLimiter.kt`

**Plugin jvmMain directories to delete entirely:**
- `core/plugins/plaits/src/jvmMain/`
- `core/plugins/drum/src/jvmMain/`
- `core/plugins/warps/src/jvmMain/`
- `core/plugins/resonator/src/jvmMain/`
- `core/plugins/grains/src/jvmMain/`
- `core/plugins/reverb/src/jvmMain/`
- `core/plugins/flux/src/jvmMain/`

- [ ] **Step 1:** Delete all files and directories
- [ ] **Step 2:** Verify: `./gradlew compileKotlinJvm`
- [ ] **Step 3:** Commit: `"refactor: delete all JSyn implementations"`

### Task 10: Delete Android Oboe DSP Files

Android has Oboe-based implementations of DspFactory/AudioUnit that are now dead code (C++ handles all audio via JNI).

**Files to delete from `core/audio/src/androidMain/` (keep OboeAudioEngine, OboeAudioBridge):**
- `OboeAudioPorts.kt`, `OboeDspUnits.kt`
- `OboeGraphScheduler.kt`
- `OboeOscillatorFactories.kt`, `OboeMathFactories.kt`, `OboeEffectFactories.kt`
- `OboeUtilityFactories.kt`, `OboeDynamicsFactories.kt`
- Any remaining Oboe unit implementations

**Plugin androidMain directories to delete entirely:**
- `core/plugins/plaits/src/androidMain/`
- `core/plugins/drum/src/androidMain/`
- `core/plugins/resonator/src/androidMain/`
- `core/plugins/grains/src/androidMain/`
- `core/plugins/warps/src/androidMain/`
- `core/plugins/reverb/src/androidMain/`
- `core/plugins/flux/src/androidMain/`

- [ ] **Step 1:** Delete all files and directories (keep OboeAudioEngine.kt, OboeAudioBridge.kt)
- [ ] **Step 2:** Verify: `./gradlew compileKotlinJvm compileKotlinAndroid` (check both platforms)
- [ ] **Step 3:** Commit: `"refactor: delete Android Oboe DSP unit implementations"`

### Task 11: Delete WASM Kotlin DSP Audio Engine

**Files to delete:**
- `apps/composeApp/src/wasmJsMain/kotlin/.../OrpheusAudioEngine.kt` (old main-thread Kotlin engine)

- [ ] **Step 1:** Delete file
- [ ] **Step 2:** Verify: `./gradlew compileKotlinWasmJs`
- [ ] **Step 3:** Commit: `"refactor: delete WASM Kotlin DSP audio engine"`

### Task 12: Delete Kotlin DSP Engine Implementations

**Plugin engine directories to delete:**
- `core/plugins/plaits/src/commonMain/.../engine/` — ALL files EXCEPT `NativeOnlyEngine.kt` (keep: C++ engine enum stub)
- `core/plugins/drum/src/commonMain/.../engine/` — entire directory
- `core/plugins/warps/src/commonMain/.../engine/` — entire directory
- `core/plugins/resonator/src/commonMain/.../engine/` — entire directory
- `core/plugins/grains/src/commonMain/.../engine/` — entire directory
- `core/plugins/flux/src/commonMain/.../engine/` — entire directory

**Plugin Dsp*Unit files to delete:**
- `core/plugins/plaits/src/commonMain/.../DspPlaitsUnit.kt`
- `core/plugins/drum/src/commonMain/.../DspDrumUnit.kt`
- `core/plugins/warps/src/commonMain/.../DspWarpsUnit.kt`
- `core/plugins/resonator/src/commonMain/.../DspResonatorUnit.kt`
- `core/plugins/grains/src/commonMain/.../DspGrainsUnit.kt`
- `core/plugins/reverb/src/commonMain/.../DspReverbUnit.kt`
- `core/plugins/flux/src/commonMain/.../DspFluxUnit.kt`

- [ ] **Step 1:** Delete all engine directories and Dsp*Unit files (keep NativeOnlyEngine.kt)
- [ ] **Step 2:** Fix compilation — plugin files may import deleted engine types
- [ ] **Step 3:** Verify: `./gradlew compileKotlinJvm`
- [ ] **Step 4:** Commit: `"refactor: delete all Kotlin DSP engine implementations"`

---

## Chunk 6: Build Cleanup and Verification

### Task 13: Remove JSyn from Build Configuration

**Files:**
- `gradle/libs.versions.toml` — remove `jsyn` version + library entries
- `core/audio/build.gradle.kts` — remove `implementation(libs.jsyn)` from jvmMain
- All plugin `build.gradle.kts` — remove any `libs.jsyn` references
- Clean empty jvmMain/androidMain source set blocks in build files

- [ ] **Step 1:** Remove JSyn from version catalog
- [ ] **Step 2:** Clean all build.gradle.kts files
- [ ] **Step 3:** Verify: `./gradlew compileKotlinJvm compileKotlinWasmJs`
- [ ] **Step 4:** Commit: `"build: remove JSyn dependency"`

### Task 14: Final Verification and Cleanup

- [ ] **Step 1:** Full build: `./gradlew build`
- [ ] **Step 2:** Grep for dead references: `jsyn`, `JSyn`, `DspVoice`, `DspWiringGraph`, `DspFactory`, `DspGraphScheduler`, `OfflineAudioEngine`
- [ ] **Step 3:** Desktop launch: `./gradlew :apps:composeApp:run`
- [ ] **Step 4:** WASM build: `./gradlew :apps:composeApp:wasmJsBrowserDistribution`
- [ ] **Step 5:** Update CLAUDE.md — remove JSyn references, `-Dorpheus.engine=jsyn`, update build docs
- [ ] **Step 6:** Update memory — update project memory to reflect JSyn removal
- [ ] **Step 7:** Commit: `"chore: final cleanup after JSyn removal"`

---

## Dependency Graph

```
Task 1 (JVM entry)  ──┐
Task 2 (WASM entry) ──┤
                       ▼
                 Task 3 (DspSynthEngine)
                       │
                       ▼
                 Task 4 (DspVoiceManager)
                       │
                       ▼
                 Task 5 (14 plugins + TtsPlugin)
                       │
                       ▼
                 Task 6 (GlobalTempo)
                       │
                       ▼
                 Task 7 (DspPlugin + AudioEngine interfaces)
                       │
                       ▼
              ┌────────┼────────┬──────────┐
              ▼        ▼        ▼          ▼
          Task 8    Task 9   Task 10    Task 11
         (infra)    (JSyn)  (Android)   (WASM)
              │        │        │          │
              └────────┼────────┴──────────┘
                       ▼
                 Task 12 (engine impls)
                       │
                       ▼
                 Task 13 (build files)
                       │
                       ▼
                 Task 14 (verify)
```

Tasks 1 & 2 are independent (parallel).
Tasks 3 → 4 → 5 → 6 → 7 are strictly sequential (each removes references the next needs gone).
Tasks 8-11 can run in parallel after Task 7 (all deletions, no interdependencies).
Tasks 12-14 are sequential after all deletions.
