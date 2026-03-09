# JVM Desktop C++ DSP Engine — Design

**Date**: 2026-03-07
**Goal**: Run liborpheus_dsp on JVM desktop for A/B testing against existing JSyn engine.

## Context

Android already runs the C++ DSP engine via JNI/Oboe (7% CPU on Galaxy S24 Ultra).
JVM desktop currently uses JSyn (pure Java). We need both engines available on desktop
so the user can toggle between them and compare sound output preset-by-preset.

## Architecture

```
┌─ JVM Desktop (existing) ──────┐   ┌─ JVM Desktop (new C++ path) ─────┐
│ OrpheusAudioEngine (JSyn)     │   │ NativeDspAudioEngine              │
│   AudioEngine binding         │   │   AudioEngine + NativeDspBridge   │
│         ↓                     │   │         ↓ JNI                     │
│ JSyn synthesizer              │   │ DesktopDspBridge (external fns)   │
│ → pure Java audio graph       │   │         ↓                         │
│                               │   │ jni_bridge_desktop.cpp            │
│                               │   │         ↓                         │
│                               │   │ DesktopEngine.cpp (no audio I/O)  │
│                               │   │         ↓                         │
│                               │   │ liborpheus_dsp (static)           │
│                               │   │ → orpheus_desktop.dylib           │
└───────────────────────────────┘   └───────────────────────────────────┘

Toggle: -Dorpheus.engine=cpp  (default: jsyn)
```

## Components

### 1. C++ Desktop Engine (no Oboe)

**Files**: `liborpheus_dsp/desktop/DesktopEngine.h`, `DesktopEngine.cpp`

Thin wrapper around `orpheus_engine_*` C API:
- `open(float sampleRate)` → `orpheus_engine_create(sr)`
- `process(float* buffer, int numFrames)` → `orpheus_engine_process()`
- Same parameter pass-throughs as Android's `OboeEngine.cpp`
- **No audio thread** — Kotlin drives the pull-model via `nativeProcess()`

### 2. JNI Bridge

**File**: `liborpheus_dsp/desktop/jni_bridge_desktop.cpp`

Nearly identical to `apps/androidApp/src/main/cpp/jni_bridge.cpp` but:
- JNI class: `org.balch.orpheus.core.audio.dsp.DesktopDspBridge`
- Adds `nativeProcess(float[])` — Kotlin calls this to fill audio buffers
- No Oboe dependencies, no Android log

### 3. CMake Build

**File**: `liborpheus_dsp/desktop/CMakeLists.txt`

Builds `liborpheus_desktop.dylib` (macOS) / `.so` (Linux) / `.dll` (Windows):
- Links liborpheus_dsp (static) + JNI headers
- Gradle task `buildDesktopNative` invokes CMake
- Output placed in resources for JAR bundling

### 4. Kotlin Bridge

**File**: `core/audio/src/jvmMain/.../DesktopDspBridge.kt`

- `System.load()` the native library from extracted JAR resource
- `external fun` declarations matching JNI bridge
- Same shape as `OboeAudioBridge.kt` plus `nativeProcess(buffer: FloatArray)`

### 5. Audio Engine

**File**: `core/audio/src/jvmMain/.../NativeDspAudioEngine.kt`

Implements `AudioEngine` + `NativeDspBridge`:
- `start()`: Opens `javax.sound.sampled.SourceDataLine` (48kHz stereo float32)
- Spawns daemon audio thread:
  ```
  loop {
    bridge.nativeProcess(floatBuffer)  // C++ fills buffer
    convert float[] → byte[]
    sourceLine.write(bytes)
  }
  ```
- Buffer: 512 frames (~10ms at 48kHz)
- `addUnit()`, `lineOutLeft/Right` → no-ops (C++ manages graph)

### 6. DI Toggle

**File**: `core/audio/src/jvmMain/.../AudioEngineProvider.kt`

Provider checks `System.getProperty("orpheus.engine")`:
- `"cpp"` → `NativeDspAudioEngine`
- default → `OrpheusAudioEngine` (JSyn)

Both engines are available in the same binary. Toggle at launch:
```
./gradlew :apps:composeApp:run -PjvmArgs="-Dorpheus.engine=cpp"
```

## Build Flow

```
./gradlew buildDesktopNative
  → cmake --build liborpheus_dsp/desktop/
  → produces orpheus_desktop.dylib
  → copies to src/jvmMain/resources/native/darwin-aarch64/

./gradlew :apps:composeApp:run
  → JSyn engine (default)

./gradlew :apps:composeApp:run -Dorpheus.engine=cpp
  → C++ engine via javax.sound.sampled
```

## A/B Testing Workflow

1. Launch desktop app (default JSyn)
2. Load a preset (e.g., "Orpheus")
3. Play notes, listen to each voice duo
4. Quit, relaunch with `-Dorpheus.engine=cpp`
5. Load same preset, play same notes
6. Compare by ear

## Future: Automated Comparison (Phase 2)

Not in scope now, but the architecture supports:
- Offline WAV render: headless mode that renders N seconds per preset
- Comparison tooling: RMS diff, spectral analysis between JSyn and C++ WAVs
- Per-effect parity checklist with pass/fail metrics

## Files to Create

| File | Purpose |
|------|---------|
| `liborpheus_dsp/desktop/CMakeLists.txt` | Desktop dylib build |
| `liborpheus_dsp/desktop/DesktopEngine.h` | C++ engine wrapper (no audio I/O) |
| `liborpheus_dsp/desktop/DesktopEngine.cpp` | C++ engine implementation |
| `liborpheus_dsp/desktop/jni_bridge_desktop.cpp` | JNI function implementations |
| `core/audio/src/jvmMain/.../DesktopDspBridge.kt` | Kotlin JNI external declarations |
| `core/audio/src/jvmMain/.../NativeDspAudioEngine.kt` | AudioEngine + NativeDspBridge impl |
| `core/audio/src/jvmMain/.../AudioEngineProvider.kt` | DI toggle provider |

## Files to Modify

| File | Change |
|------|--------|
| `liborpheus_dsp/CMakeLists.txt` | Add desktop subdirectory option |
| `apps/composeApp/build.gradle.kts` | Add `buildDesktopNative` Gradle task |
| `core/audio/src/jvmMain/.../OrpheusAudioEngine.kt` | Remove `@ContributesBinding`, let provider decide |