# Orpheus Record Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A desktop Record panel that saves each take (full output WAV + preset + telemetry) to disk, lists, deletes and plays takes, plus a Claude skill that turns a take into a Pulsar vibe track.

**Architecture:**
- A lock-free ring in C++ copies each rendered block in `DesktopEngine::process`. The JVM drains it over JNI and streams it to a WAV.
- A new `features/record` module has three parts:
  - pure, tested logic (telemetry model, snapshot mapping, event recorder) in `commonMain`
  - file storage, WAV writing and playback in `jvmMain`
  - a `RecordSession` that ties together capture, preset save and telemetry.
- The module is added to the desktop app only.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Metro DI, kotlinx.serialization, kotlinx.coroutines, C++17, JNI, miniaudio, `javax.sound.sampled`.

**Spec:** `docs/plans/2026-10-17-orpheus-record-panel-design.md` (read it first; this plan argues from it).

## Global Constraints

- Base branch `main`. Desktop JVM only; do not touch the Android (Oboe) or iOS render paths.
- Audio thread: no locks, no allocation, no JNI calls from the miniaudio callback.
- Takes live in `~/.config/orpheus/recordings/<take-id>/` with `audio.wav`, `preset.json`, `telemetry.json`.
- Take id = `take-YYYY-MM-DD-HHMMSS`, local time. Preset also saved to the presets folder as `rec-<take-id>`.
- WAV: stereo, engine sample rate (48000), 32-bit float (format tag 3).
- Max take length 5 minutes; recording stops itself at the cap.
- Control events throttled to at most 20 per second per key.
- Telemetry `version` = 1, `app` = `"orpheus-desktop"`.
- Comments 1-3 lines; no em-dashes in prose; commit messages short, ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Follow `.claude/skills/panel-viewmodel-feature/SKILL.md` (all four ViewModel annotations, feature injected into the registration) and `.claude/skills/dsp-implementation/` for the C++/JNI piece.

## Review Focus

1. **Replayed gate on subscribe:** `SynthController.onPulseStart`/`onPulseEnd`/`onControlChange` have `replay = 1`. A recording must not open with a stale gate or control event from before record was pressed. Test in Task 5.
2. **Stop during a held note:** a gate that is on at stop has no `gateOff`. Telemetry must still be valid, and the skill must cope with an open gate. Test in Task 5.
3. **Delete or play while recording, delete while playing:** deleting the take that is playing stops playback first. Starting a recording stops playback. Delete is disabled for the take being written. Tests in Tasks 7 and 9.
4. **Ring overflow when the JVM stalls** (GC pause, slow disk): no audio-thread block, dropped frames counted, and the count lands in `telemetry.droppedFrames` as the difference from the count at start. Tests in Tasks 1 and 7.
5. **Damaged or foreign folders in `recordings/`** (no `telemetry.json`, a half-written take after a crash, a stray file): the list skips or shows them without crashing. Test in Task 6.

---

### Task 1: C++ record ring

**Files:**
- Create: `liborpheus_dsp/src/orpheus_record_ring.h`
- Create: `liborpheus_dsp/test/test_record_ring.cpp`
- Modify: `liborpheus_dsp/CMakeLists.txt` (add the test file to `orpheus_dsp_test`)
- Modify: `liborpheus_dsp/test/test_main.cpp` (register suite `"record"`)

**Interfaces:**
- Produces (header-only, `namespace orpheus`):
  ```cpp
  class RecordRing {
   public:
    explicit RecordRing(int capacity_frames);        // rounds up to a power of two; allocates once
    void arm();                                      // consumer thread
    void disarm();                                   // consumer thread
    bool armed() const;
    void write(const float* interleaved, int frames); // audio thread; no-op when disarmed
    int  read(float* out_interleaved, int max_frames); // consumer; returns frames copied
    void discard();                                  // consumer; read index = write index
    uint64_t dropped_frames() const;                 // monotonic, never reset
    int capacity_frames() const;
  };
  ```
  Stereo interleaved (2 floats per frame). SPSC with `std::atomic<uint32_t>` read/write indices, acquire/release. `write` copies as many frames as fit and adds the rest to `dropped_frames` (it never overwrites unread data). `discard` and `read` touch only the read index, so they are safe while the writer runs. There is no reset of the drop counter: the JVM keeps a baseline.

- [ ] **Step 1: Write the failing tests** in `test_record_ring.cpp`, `bool run_record_ring_tests()`:
  - `ring_reads_back_in_order`: capacity 8, arm, write 3 frames `{1,-1},{2,-2},{3,-3}`, read(max 8) returns 3 and the same 6 floats.
  - `ring_disarmed_write_is_noop`: never armed, write 4 frames, read returns 0, `dropped_frames()==0`.
  - `ring_wraps_around`: capacity 8; 5 times: write 5 frames, read 5 frames; every read matches what was written.
  - `ring_overflow_counts_drops`: capacity 8, arm, write 12 frames, `dropped_frames()==4`, read returns the first 8 frames unchanged.
  - `ring_discard_skips_unread`: write 5, discard, read returns 0; write 2, read returns those 2.
  - `ring_capacity_rounds_up`: `RecordRing(1000).capacity_frames()==1024`.
- [ ] **Step 2: Run, expect a compile failure** (header missing):
  `cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test record` (configure first with the CLAUDE.md cmake line if the build dir is missing).
- [ ] **Step 3: Implement `orpheus_record_ring.h`** to the interface above.
- [ ] **Step 4: Run** the `record` suite. Expected: all 6 pass. Then run the full suite: no new failures.
- [ ] **Step 5: Commit** `feat(dsp): lock-free stereo record ring`.

### Task 2: Desktop capture over JNI and `OutputCapture`

**Files:**
- Modify: `liborpheus_dsp/desktop/DesktopEngine.h`, `DesktopEngine.cpp`
- Modify: `liborpheus_dsp/desktop/jni_bridge_desktop.cpp`
- Modify: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/DesktopDspBridge.kt`
- Create: `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/OutputCapture.kt`
- Modify: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/NativeDspAudioEngine.kt`, `AudioEngineProvider.kt`

**Interfaces:**
- Consumes: `orpheus::RecordRing` (Task 1).
- Produces:
  - `DesktopEngine` has a member `orpheus::RecordRing record_ring_{1 << 18}` (about 5.5 s at 48 kHz) and these methods:
    - `void startRecording()`: discard, then arm
    - `void stopRecording()`: disarm
    - `int drainRecording(float* out, int maxFrames)`
    - `uint64_t recordDroppedFrames() const`

    `process()` calls `record_ring_.write(outputBuffer, numFrames)` right after `orpheus_engine_process`.
  - JNI / `DesktopDspBridge`:
    - `external fun nativeStartRecording()`
    - `external fun nativeStopRecording()`
    - `external fun nativeDrainRecording(out: FloatArray): Int` (frames; `out.size / 2` max)
    - `external fun nativeRecordDroppedFrames(): Long`
  - Kotlin, `org.balch.orpheus.core.audio.dsp`:
    ```kotlin
    interface OutputCapture {
        val sampleRate: Int
        fun start()
        fun stop()
        /** Copies up to into.size / 2 interleaved stereo frames; returns frames copied. */
        fun drain(into: FloatArray): Int
        fun droppedFrames(): Long
    }
    ```
    `NativeDspAudioEngine` implements it. `AudioEngineModule` adds `@Provides @SingleIn(AppScope::class) fun provideOutputCapture(engine: AudioEngine): OutputCapture = engine as OutputCapture`.

- [ ] **Step 1: Add the C++ members and calls.** The drain JNI copies into a stack chunk of up to 4096 frames per loop and `SetFloatArrayRegion`s, like `nativeGetScope` (no pinning).
- [ ] **Step 2: Add the Kotlin externals, the interface, the implementation and the provider.**
- [ ] **Step 3: Build the native lib and compile:** `./gradlew :apps:orpheus:desktopApp:build` (rebuilds `liborpheus_desktop`). Expected: BUILD SUCCESSFUL. The C++ full suite still passes.
- [ ] **Step 4: Commit** `feat(audio): desktop output capture over JNI`.

(No JVM unit test here: the native library is not loaded in unit tests. Task 7 covers the logic with a fake `OutputCapture`, and Task 10 runs it for real.)

### Task 3: Publish raw MIDI notes

**Files:**
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/controller/SynthController.kt`
- Modify: `core/midi/src/commonMain/kotlin/org/balch/orpheus/core/midi/MidiInputHandler.kt`
- Modify: `core/midi/build.gradle.kts` (commonTest deps: `kotlin("test")`, `libs.kotlinx.coroutines.test`)
- Test: `core/midi/src/commonTest/kotlin/org/balch/orpheus/core/midi/MidiInputHandlerNoteEventTest.kt`

**Interfaces:**
- Produces, in `SynthController.kt`:
  ```kotlin
  data class MidiNoteEvent(val note: Int, val velocity: Int, val on: Boolean, val voice: Int?)
  val onMidiNote: Flow<MidiNoteEvent>          // replay = 0, extraBufferCapacity = 256, DROP_OLDEST
  fun emitMidiNote(event: MidiNoteEvent)
  ```
  `MidiInputHandler.onNoteOn` emits `MidiNoteEvent(note, velocity, on = true, voice = stateHolder.getVoiceForNote(note))` after the learn-mode early return. `onNoteOff` emits `velocity = 0, on = false`.

- [ ] **Step 1: Write the failing test** `noteOnAndOffArePublished`:
  - Build `MidiInputHandler(SynthController(), MidiMappingStateHolder())`.
  - Collect `onMidiNote` in `backgroundScope` (UnconfinedTestDispatcher), call `onNoteOn(64, 100)` then `onNoteOff(64)`.
  - Assert two events: `note == 64`, `velocity == 100`, `on == true`, then `on == false`, `velocity == 0`.
  - Assert `voice` equals `stateHolder.getVoiceForNote(64)`.
- [ ] **Step 2: Run** `./gradlew :core:midi:jvmTest --tests '*MidiInputHandlerNoteEventTest*'`. Expected: FAIL (unresolved `onMidiNote`).
- [ ] **Step 3: Implement** the flow, the emitter and the two calls.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(midi): publish raw note events on SynthController`.

### Task 4: Record module, telemetry model and snapshot mapping

**Files:**
- Create: `features/record/build.gradle.kts`. Copy `features/timer/build.gradle.kts`, set namespace `org.balch.orpheus.features.record`, and add:
  - plugin `alias(libs.plugins.kotlinSerialization)`
  - `implementation(libs.kotlinx.serialization.json)`, `implementation(libs.kotlinx.datetime)`
  - `implementation(projects.core.audio)`
- Modify: `settings.gradle.kts` (`include(":features:record")`)
- Create in `features/record/src/commonMain/kotlin/org/balch/orpheus/features/record/`:
  - `telemetry/TakeTelemetry.kt`
  - `telemetry/TakeSnapshot.kt`
  - `telemetry/TakeSnapshotMapper.kt`
  - `TakeId.kt`
- Test: `features/record/src/commonTest/kotlin/org/balch/orpheus/features/record/TakeSnapshotMapperTest.kt`, `TakeTelemetryJsonTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class TakeTelemetry(
      val version: Int = 1, val takeId: String, val app: String = "orpheus-desktop",
      val sampleRate: Int, val durationSec: Float, val droppedFrames: Long,
      val start: TakeSnapshot, val end: TakeSnapshot, val events: List<TakeEvent>,
  )
  @Serializable sealed interface TakeEvent { val t: Float }   // seconds since record start
  @Serializable @SerialName("gateOn")  data class GateOn(override val t: Float, val voice: Int, val midiNote: Float) : TakeEvent
  @Serializable @SerialName("gateOff") data class GateOff(override val t: Float, val voice: Int) : TakeEvent
  @Serializable @SerialName("control") data class Control(override val t: Float, val key: String, val value: Float) : TakeEvent
  @Serializable @SerialName("midiNoteOn")  data class MidiNoteOn(override val t: Float, val note: Int, val velocity: Int, val voice: Int?) : TakeEvent
  @Serializable @SerialName("midiNoteOff") data class MidiNoteOff(override val t: Float, val note: Int, val voice: Int?) : TakeEvent

  @Serializable data class TakeSnapshot(val bpm: Float, val pairs: List<PairSnapshot>, val quads: List<QuadSnapshot>, val effects: EffectsSnapshot)
  @Serializable data class PairSnapshot(val pair: Int, val engineId: Int, val engineName: String,
      val harmonics: Float, val timbre: Float, val morph: Float,
      val modSource: String, val modSourceLevel: Float, val voices: List<VoiceSnapshot>)
  @Serializable data class VoiceSnapshot(val voice: Int, val tune: Float, val midiNote: Float, val envSpeed: Float)
  @Serializable data class QuadSnapshot(val quad: Int, val pitch: Float, val pitchSource: Int, val triggerSource: Int, val hold: Float)
  @Serializable data class EffectsSnapshot(val delayTime1: Float, val delayTime2: Float, val delayFeedback: Float,
      val delayMix: Float, val reverbAmount: Float, val distortionDrive: Float, val vibratoDepth: Float)

  val TelemetryJson: Json   // classDiscriminator = "type", prettyPrint = true, encodeDefaults = true, ignoreUnknownKeys = true

  fun soundingMidiNote(voice: Int, tune: Float, quadPitch: Float): Float
  fun snapshotOf(preset: SynthPreset): TakeSnapshot
  fun modSourceName(stored: Int): String
  fun takeIdFor(time: LocalDateTime): String        // "take-2026-10-17-132431"
  ```
- Mapping rules:
  - Pairs 0..5, voices `2p` and `2p+1`, quads 0..2. Quad of voice `v` = `v / 4`.
  - Read ports by `PortSymbol.controlId.key` from `preset.portValues`:
    - voice ports: `VoiceSymbol.duoEngine/duoHarmonics/duoSharpness/duoMorph/duoModSource/duoModSourceLevel/tune/envSpeed/quadPitch/quadPitchSource/quadTriggerSource/quadHold`
    - effect ports: `DelaySymbol.TIME_1/TIME_2/FEEDBACK/MIX`, `ReverbSymbol.AMOUNT`, `DistortionSymbol.DRIVE`, `VibratoSymbol.DEPTH`
  - When a key is missing, use the plugin's port default.
  - Pitch math: `33 + tune*48 + offset[voice] + 24*(quadPitch-0.5)`, offsets `[-12,-12,0,0,0,0,12,12,0,0,0,0]` (same as `DspSynthEngine.VOICE_PITCH_MULT_SEMITONES`; copy, do not reach into `core:dsp-engine`).
  - `engineName` = `engineLabel(id)` from `ui/widgets/.../VoiceEnginePickerPopup.kt`.
  - `modSourceName`: stored int is the `ModSource` ordinal; `VOICE_FM` → `"FM"`, others by name, out of range → `"UNKNOWN"`.
  - Tidal state is not recorded, and `gateOn` has no `source` field (both "best effort" in the spec; there is no single flow for either without reworking callers). The skill says so.

- [ ] **Step 1: Write the failing tests:**
  - `soundingMidiNote(1, 0.71f, 0.92f) == 65.16f` (±0.001)
  - `soundingMidiNote(2, 0.72f, 0.5f) == 67.56f`
  - `soundingMidiNote(6, 0f, 0.5f) == 45f`
  - `modSourceName(0) == "FM"`, `modSourceName(2) == "LFO"`, `modSourceName(9) == "UNKNOWN"`
  - `snapshotOf` with a preset holding:
    - `duo_engine_0 = 107`, `duo_harmonics_0 = 0.2`, `duo_morph_0 = 0.45`, `duo_sharpness_0 = 0.43`, `duo_mod_source_0 = 2`
    - `tune_0 = 0.71`, `tune_1 = 0.72`, `quad_pitch_0 = 0.92`, `quad_trigger_source_1 = 3`

    Expect:
    - `pairs[0].engineName == "VOW"`, `modSource == "LFO"`, `voices[1].midiNote` ≈ `33 + 34.56 - 12 + 10.08`
    - `quads[1].triggerSource == 3`
    - `pairs.size == 6`, `quads.size == 3`
  - `snapshotOf(SynthPreset())` does not throw; every pair falls back to the plugin defaults.
  - `takeIdFor(LocalDateTime(2026, 10, 17, 13, 24, 31)) == "take-2026-10-17-132431"`
  - JSON test: a `TakeTelemetry` with one of each event encodes `"type":"gateOn"` (etc.) and decodes back equal.
- [ ] **Step 2: Run** `./gradlew :features:record:jvmTest`. Expected: FAIL (unresolved symbols).
- [ ] **Step 3: Implement** the files to the interfaces above.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(record): take telemetry model and snapshot mapping`.

### Task 5: Event recorder

**Files:**
- Create: `features/record/src/commonMain/kotlin/org/balch/orpheus/features/record/telemetry/TakeEventRecorder.kt`
- Test: `features/record/src/commonTest/.../TakeEventRecorderTest.kt`

**Interfaces:**
- Consumes: `TakeEvent` types (Task 4), `MidiNoteEvent` (Task 3).
- Produces:
  ```kotlin
  class TakeEventRecorder(private val nowMs: () -> Long) {
      fun start()                                   // clears, records start time, active = true
      val isActive: Boolean
      fun gateOn(voice: Int, midiNote: Float)
      fun gateOff(voice: Int)
      fun control(key: String, value: Float)        // keeps only RECORDED_URIS, throttled
      fun midiNote(event: MidiNoteEvent)
      fun stop(): List<TakeEvent>                   // flushes held-back controls, active = false, sorted by t
      companion object { val RECORDED_URIS: Set<String>; const val CONTROL_MIN_INTERVAL_MS = 50L }
  }
  ```
  `RECORDED_URIS` = `VOICE_URI`, `DELAY_URI`, `REVERB_URI`, `DISTORTION_URI`, `VIBRATO_URI`. Calls made while inactive are ignored. That is how replayed events from `replay = 1` flows are dropped: the session subscribes once at construction, and the recorder only listens between `start` and `stop`. A control for a key arriving within 50 ms of that key's last kept event is held back. Only the newest held value is kept, and it is emitted at stop, or when the next allowed event for that key arrives.

- [ ] **Step 1: Write the failing tests** (fake clock):
  - `ignoresEventsBeforeStart`: `gateOn(1, 60f)` before `start()`, then start, stop → empty.
  - `timesAreSecondsFromStart`: start at 1000 ms, `gateOn` at 1840 → `GateOn(t = 0.84f, ...)`.
  - `throttlesControlsPerKey`: 10 `control(tune_1, i/10f)` calls 10 ms apart → ≤ 2 events for that key, the last one with value 0.9.
  - `dropsUnrelatedUris`: `control("org.balch.orpheus.plugins.pulsar:foo", 1f)` → not recorded.
  - `openGateAtStopIsKept`: `gateOn` without `gateOff`, stop → the list has the `GateOn` and no synthesized `GateOff`.
  - `midiNotesRecorded`: `midiNote(MidiNoteEvent(64, 100, true, 1))` → `MidiNoteOn(t, 64, 100, 1)`.
- [ ] **Step 2: Run** `./gradlew :features:record:jvmTest --tests '*TakeEventRecorderTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(record): event recorder with per-key throttle`.

### Task 6: Take storage and WAV writer

**Files:**
- Create: `features/record/src/commonMain/.../store/TakeStore.kt`
- Create: `features/record/src/jvmMain/kotlin/org/balch/orpheus/features/record/store/FileTakeStore.kt`, `WavFileWriter.kt`
- Test: `features/record/src/jvmTest/kotlin/org/balch/orpheus/features/record/store/FileTakeStoreTest.kt`, `WavFileWriterTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class TakeInfo(val takeId: String, val durationSec: Float, val createdAtMs: Long, val audioPath: String)

  interface TakeWriter {                       // one open take
      fun writeAudio(interleaved: FloatArray, frames: Int)
      fun finish(preset: SynthPreset, telemetry: TakeTelemetry)   // patches WAV sizes, writes both JSON files
      fun abort()                              // deletes the folder
  }
  interface TakeStore {
      suspend fun list(): List<TakeInfo>       // newest first
      suspend fun delete(takeId: String)
      fun open(takeId: String, sampleRate: Int): TakeWriter
  }
  class FileTakeStore(private val root: File) : TakeStore
  class WavFileWriter(file: File, sampleRate: Int)   // stereo float32; write(FloatArray, frames); close() patches RIFF and data sizes
  ```
  - `FileTakeStore` default root: `File(System.getProperty("user.home"), ".config/orpheus/recordings")`. Bind it in `jvmMain` with `@ContributesBinding(AppScope::class)` through a no-arg `@Inject` constructor that delegates to the default root.
  - `list()` reads `durationSec` from `telemetry.json` when present. Otherwise it computes it from the WAV data size. A folder with no `audio.wav` is skipped. Unreadable JSON falls back to the WAV.
  - `createdAtMs` = the folder's last-modified time.
  - Preset JSON uses the same `Json` config as `JvmSynthPresetRepository` (`apps/orpheus/shared/src/jvmMain/.../SynthPresetRepository.jvm.kt:25-35`). Move that config to a shared spot only if `features/record` cannot reach it, and put it in `core/foundation` `presets`.

- [ ] **Step 1: Write the failing tests** (temp dir):
  - `wavHeaderIsFloatStereo`: write 480 frames at 48000, close. Bytes 0-3 `RIFF`, 8-11 `WAVE`, format tag (offset 20) = 3, channels (22) = 2, sample rate (24) = 48000, bits (34) = 32, data size (40) = 480*8, RIFF size (4) = file length - 8.
  - `listNewestFirstAndSkipsBrokenFolders`: create two finished takes plus an empty folder `junk/` and a stray file. `list()` returns the two takes, newest first.
  - `listFallsBackToWavLengthWithoutTelemetry`: a take with only `audio.wav` of 48000 frames → `durationSec == 1f`.
  - `deleteRemovesFolder`: after `delete(id)`, the folder is gone and `list()` doesn't have it.
  - `abortRemovesHalfWrittenTake`.
- [ ] **Step 2: Run** `./gradlew :features:record:jvmTest --tests '*store*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(record): file take store and float WAV writer`.

### Task 7: Record session

**Files:**
- Create: `features/record/src/commonMain/.../RecordSession.kt`
- Test: `features/record/src/commonTest/.../RecordSessionTest.kt`

**Interfaces:**
- Consumes:
  - `OutputCapture` (Task 2), `SynthController.onPulseStart/onPulseEnd/onControlChange/onMidiNote` (Task 3)
  - `snapshotOf`, `soundingMidiNote`, `takeIdFor`, `TakeEventRecorder`, `TakeStore`
  - `PresetLoader.currentStateAsPreset(name)`, `PresetsRepository.save(preset)` (wrapped by `DefaultPresetSnapshotter`)
- Produces:
  ```kotlin
  sealed interface RecordState {
      data object Idle : RecordState
      data class Recording(val takeId: String, val elapsedSec: Float) : RecordState
  }
  interface PresetSnapshotter {               // seam over PresetLoader + PresetsRepository
      fun capture(name: String): SynthPreset
      suspend fun save(preset: SynthPreset)
  }
  // jvmMain or commonMain, @ContributesBinding(AppScope::class):
  @Inject class DefaultPresetSnapshotter(presetLoader: PresetLoader, presetsRepository: PresetsRepository) : PresetSnapshotter

  @SingleIn(AppScope::class)
  class RecordSession internal constructor(
      capture: OutputCapture, store: TakeStore, presets: PresetSnapshotter,
      synthController: SynthController, clock: kotlin.time.Clock, scope: CoroutineScope,
  ) {
      @Inject constructor(capture: OutputCapture, store: TakeStore, presets: PresetSnapshotter,
                          synthController: SynthController, dispatcherProvider: DispatcherProvider)
          : this(capture, store, presets, synthController, Clock.System,
                 CoroutineScope(SupervisorJob() + dispatcherProvider.io))
      val state: StateFlow<RecordState>
      val takesChanged: SharedFlow<String>   // takeId of each finished take
      suspend fun start()
      suspend fun stop()
      companion object { const val MAX_SECONDS = 300; const val DRAIN_INTERVAL_MS = 40L }
  }
  ```
  Tests use the internal constructor with a test clock and the test scope.
- Behavior:
  1. `start()`, when Idle:
     - take id from the clock (local time zone)
     - `presets.capture("rec-$id")`, then `presets.save`
     - start snapshot from that preset
     - store `open`, note the dropped-frames baseline, `capture.start()`, `recorder.start()`
     - launch the drain loop: every 40 ms drain into a reusable `FloatArray(4096 * 2)` until empty, then `writeAudio`
  2. Gate-on pitch = `soundingMidiNote(voice, tune_v, quadPitch_q)`, using the live values from `synthController.getPluginControl`.
  3. At 300 s the loop calls `stop()` itself.
  4. `stop()`:
     - `capture.stop()`, then a final drain
     - end snapshot from `presets.capture`
     - telemetry with `droppedFrames = now - baseline` and `durationSec = framesWritten / sampleRate`
     - `finish`, emit `takesChanged`, back to Idle
  5. Any exception between open and finish: `abort()`, `capture.stop()`, Idle, log it.
  6. The flows are collected once in `init` on the session's `scope`, feeding the recorder (inactive outside a take).

- [ ] **Step 1: Write the failing tests** with fakes: `FakeOutputCapture` (queue of chunks, settable drop count), in-memory `FakeTakeStore`, `FakePresetSnapshotter`, a real `SynthController`, a test clock and the `runTest` scope with `StandardTestDispatcher`.
  - `startSavesPresetNamedAfterTake`: the saved preset's name is `rec-take-2026-10-17-132431`.
  - `audioChunksReachTheWriter`: queue 3 chunks of 100 frames, advance 200 ms → the writer saw 300 frames. Stop → `durationSec == 300/48000f`.
  - `replayedGateBeforeStartIsNotRecorded`: `emitPulseStart(1)` before `start()`, then start, stop → no `GateOn`.
  - `gateDuringTakeCarriesPitch`: set `tune_1 = 0.72`, `quad_pitch_0 = 0.5`, emit pulse start 1 → `GateOn(voice = 1, midiNote ≈ 55.56)`.
  - `droppedFramesAreRelativeToStart`: baseline 50, 70 at stop → telemetry `droppedFrames == 20`.
  - `stopsItselfAtFiveMinutes`: advance 300 s + 1 drain → state Idle, one take finished.
  - `failureAbortsTake`: the writer throws on `writeAudio` → `abort` called, state Idle.
- [ ] **Step 2: Run** `./gradlew :features:record:jvmTest --tests '*RecordSessionTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(record): record session ties capture, preset and telemetry`.

### Task 8: Record panel, ViewModel and desktop wiring

**Files:**
- Modify: `core/features/src/commonMain/kotlin/org/balch/orpheus/core/features/FeaturePanel.kt` (`val RECORD = PanelId("record")`)
- Create: `features/record/src/commonMain/.../RecordViewModel.kt`, `RecordPanel.kt`, `RecordPanelRegistration.kt`
- Modify: `apps/orpheus/shared/build.gradle.kts`: `api(project(":features:record"))` under **`jvmMain.dependencies`** only (desktop-only panel; `OutputCapture` and `FileTakeStore` are bound only in JVM)
- Modify: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/FactoryPanelSets.kt`: `collapse(PanelId.RECORD)` after `PRESETS` in `All`. Confirm that a set naming an unregistered panel is ignored on Android/WASM. If it isn't, add it to `DesktopScreen` only.
- Test: `features/record/src/commonTest/.../RecordViewModelTest.kt`

**Interfaces:**
- Consumes: `RecordSession`, `TakeStore` (Tasks 6-7). `TakePlayer` arrives in Task 9: leave the play slots as no-ops here.
- Produces:
  ```kotlin
  @Immutable data class RecordUiState(
      val recording: Boolean = false, val elapsedSec: Float = 0f, val level: Float = 0f,
      val takes: List<TakeInfo> = emptyList(), val selectedTakeId: String? = null,
      val pendingDeleteId: String? = null,
  )
  @Immutable data class RecordPanelActions(
      val toggleRecord: () -> Unit, val select: (String) -> Unit,
      val requestDelete: (String) -> Unit, val confirmDelete: () -> Unit, val cancelDelete: () -> Unit,
      val play: (String) -> Unit, val pause: () -> Unit, val seek: (Float) -> Unit,
  ) { companion object { val EMPTY: RecordPanelActions } }
  interface RecordFeature : SynthFeature<RecordUiState, RecordPanelActions>  // panelId RECORD, title "Record", empty portControlKeys
  ```
  The ViewModel has all four annotations from the skill. Level comes from `SynthEngine.masterLevelFlow`. The list is loaded on init and after each `takesChanged`. The newest take is selected after a recording.
- Panel (layout A in the spec): `CollapsibleColumnPanel(title = "REC", color = <red accent from OrpheusColors>)`.
  - Left column:
    - a round record button (filled ring idle, pulsing red while recording)
    - timer `mm:ss.t`
    - a level meter bar
  - Right column:
    - label "TAKES"
    - one row per take: play icon, take id, `m:ss · MM-dd HH:mm`, delete icon
    - delete asks inline "delete? yes / no", and is disabled while that take is recording
    - a spacer, then the listen bar slot (Task 9)
  - Registration: label `"REC"`, weight 0.6, description `"Record the output with a preset and telemetry for AI"`. Include `preview()` / `previewFeature()`.

- [ ] **Step 1: Write the failing tests** (fake session state, fake store):
  - `toggleStartsAndStops`
  - `finishedTakeIsSelectedAndListed`
  - `deleteNeedsConfirm`: `requestDelete` sets `pendingDeleteId`; `cancelDelete` clears it and the store is untouched; `confirmDelete` calls `store.delete` and refreshes.
  - `cannotDeleteRecordingTake`: `requestDelete(currentTakeId)` while recording is ignored.
- [ ] **Step 2: Run** `./gradlew :features:record:jvmTest --tests '*RecordViewModelTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the ViewModel, Panel, Registration, PanelId and wiring.
- [ ] **Step 4: Run** the tests, then `./gradlew :apps:orpheus:desktopApp:build` and `./gradlew compileKotlinJvm`. Expected: PASS / BUILD SUCCESSFUL. Run `FeatureScopeGuardTest` if it lives elsewhere (`./gradlew jvmTest --tests '*FeatureScopeGuardTest*'`).
- [ ] **Step 5: Commit** `feat(record): Record panel with take list and delete`.

### Task 9: Listen bar

**Files:**
- Create: `features/record/src/commonMain/.../playback/TakePlayer.kt`
- Create: `features/record/src/jvmMain/.../playback/JavaSoundTakePlayer.kt`
- Modify: `RecordViewModel.kt`, `RecordPanel.kt`, `RecordSession.kt` (stop playback on start)
- Test: extend `RecordViewModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class PlaybackState(val takeId: String? = null, val playing: Boolean = false, val positionSec: Float = 0f, val durationSec: Float = 0f)
  interface TakePlayer {
      val state: StateFlow<PlaybackState>
      fun play(take: TakeInfo)       // loads if different, resumes otherwise
      fun pause()
      fun seek(fraction: Float)      // 0..1
      fun stop()
  }
  ```
  `JavaSoundTakePlayer` (`@ContributesBinding(AppScope::class)`, `@SingleIn(AppScope::class)`): `AudioSystem.getAudioInputStream` on the float WAV, then convert to PCM_SIGNED 16-bit if the mixer refuses float, played on a `Clip`. Position is polled every 50 ms. It never goes through the synth engine. `RecordUiState` gains `playback: PlaybackState`.
- Bar (under the list): play/pause, a progress line you click to seek, `m:ss / m:ss`. A plain progress line (spec left waveform vs line to the builder; the line is the smaller job).

- [ ] **Step 1: Write the failing tests** (fake player):
  - `startingRecordingStopsPlayback`
  - `deletingPlayingTakeStopsPlaybackFirst`: `stop()` is called before `store.delete`.
  - `playSelectsTake`
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the tests and `./gradlew :apps:orpheus:desktopApp:build`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(record): listen bar for takes`.

### Task 10: Recording-to-vibe skill and desktop run

**Files:**
- Create: `.claude/skills/orpheus-recording-to-vibe/SKILL.md`

**Content (from the spec's skill section):**
- Frontmatter: name `orpheus-recording-to-vibe`. The description should trigger on "use my recording", "grab my last take", "turn this take into a vibe", "capture from Orpheus".
- Steps: find, explain, write the track, name the gaps, hand off (spec "The skill" 1-5). Include:
  - the telemetry field reference, with the pitch formula
  - the warning rule when `quads[*].triggerSource != 0` or `pitchSource != 0` (the timeline and pitches are incomplete for those voices; Tidal state is not recorded)
  - an ffmpeg recipe: `ffmpeg -i audio.wav -af astats -f null -` for levels, and `showspectrumpic` for a spectrogram to Read
- Move in the knowledge from memory `reference_orpheus_panel_to_pulsar_engine_mapping.md`:
  - glyph map, stored mod-source ints (FM=0, OFF=1, LFO=2, FLUX=3, TIDES=4)
  - OSC (-1) → VA stand-in values
  - Braids 100-108: harmonics → TIMBRE, morph → COLOR, timbre unused
  - delay times in seconds `0.01 + v*1.99`, `delayDamping` no-op
  - main delay only on the Orpheus graph
  - `modLfoDepth` blocked by `pinTimbre`
- Point to the vibe-creator skill for everything after the track is written.

- [ ] **Step 1: Write the skill file.**
- [ ] **Step 2: Run the desktop app** (`nohup ./gradlew :apps:orpheus:desktopApp:run > /tmp/orpheus-run.log 2>&1 &`) and walk the spec's manual checks:
  1. Record about 15 s with a voice pair singing and the quad pitch swept.
  2. The take appears, selected.
  3. `audio.wav` plays in the listen bar and with `afplay`.
  4. `rec-<take-id>` reloads the same sound from Presets.
  5. `telemetry.json` shows gate events and the `quad_pitch_0` sweep.
  6. Delete asks first and removes the folder.

  The panel is UI, so ask the user to eye-test it. Report what you checked yourself and what you could not.
- [ ] **Step 3: Dry-run the skill** on that take: produce the plain-words explanation and a proposed `OrpheusEngine(...)` block. Do not edit any vibe.
- [ ] **Step 4: Commit** `docs(skill): recording-to-vibe skill`.
