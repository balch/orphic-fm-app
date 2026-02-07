# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Orpheus-FM is an 8-oscillator organismic synthesizer built with Kotlin Multiplatform (Kotlin 2.3.0), targeting Desktop (JVM) and Android. It uses Compose Multiplatform for UI, JSyn for audio synthesis, and Metro for compile-time dependency injection.

## Build & Run Commands

```bash
# Run desktop app
./gradlew :apps:composeApp:run

# Run all tests (common tests on JVM)
./gradlew :apps:composeApp:jvmTest

# Run a single test class
./gradlew :apps:composeApp:jvmTest --tests "org.balch.orpheus.core.audio.dsp.SynthDspTest"

# Full build (all modules, all targets)
./gradlew build

# Android
./gradlew :apps:composeApp:installDebug

# Desktop release (dmg/msi/deb)
./gradlew :apps:composeApp:packageReleaseDistributionForCurrentOS
```

Tests live in `apps/composeApp/src/commonTest/`. There is no CI pipeline; tests are run locally.

## Architecture

### Module Layout

The project has ~30 Gradle modules organized into four layers:

- **`core/audio`** — Platform-agnostic audio engine interfaces (`DspPlugin`, `DspFactory`, `AudioEngine`, `Port`, `PortsDsl`)
- **`core/foundation`** — Infrastructure: MIDI, presets, state management, `SynthController` event bus
- **`core/plugins/*`** — 14 self-contained DSP plugin modules (delay, distortion, duolfo, voice, drum, grains, warps, flux, etc.)
- **`features/*`** — Feature modules combining UI (Compose) + ViewModel (MVI pattern). Each feature is independently scoped.
- **`ui/theme`, `ui/widgets`** — Shared Compose theme and reusable controls
- **`apps/composeApp`** — Main app wiring: `DspSynthEngine` (signal routing), `DspWiringGraph` (topology), `DspVoiceManager`, DI modules

Convention plugins in `build-logic/convention/` provide `orpheus.kmp.library` and `orpheus.kmp.compose` to standardize module configuration (KMP + KSP + Metro + serialization).

### Plugin System (DSP Layer)

Each DSP module implements `DspPlugin` (in `core/audio/src/commonMain/.../dsp/DspPlugin.kt`):

- Exposes `ports: List<Port>` (audio + control ports) via a type-safe DSL (`PortsDsl.kt`)
- Provides `audioUnits` for engine registration, `inputs`/`outputs` maps for inter-plugin wiring
- Lifecycle: `initialize()` → `activate()` → `onStart()` → `run(nFrames)` → `onStop()`
- Registered via Metro DI: `@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())`

Port definitions use a nested DSL with `PortSymbol` enums for compile-time safety:
```kotlin
val portDefs = ports(startIndex = 6) {
    controlPort(MySymbol.FREQ) {
        floatType { default = 0.5f; min = 0f; max = 1f; get { _freq }; set { _freq = it } }
    }
}
```

### Signal Path

8 voices (in 4 duo pairs) → per-voice stereo panning → dry bus → parallel clean/distortion paths → dual modulating delays (with LFO + feedback) → master gain/pan → stereo output. Full documentation in `docs/audio/AUDIO_PATH.md`.

Key routing rule: `AudioInput` connections override `.set()` calls, so automation dynamically connects/disconnects to preserve manual control.

### Control Event Routing

`SynthController` (`core/foundation/.../controller/SynthController.kt`) is the central event bus. Events carry an `origin` field (`MIDI`, `UI`, `SEQUENCER`, `TIDAL`, `AI`, `EVO`) to prevent double-driving parameters. ViewModels listen via `onControlChange` flow and skip engine calls for `SEQUENCER` origin (DSP-driven parameters).

### Dependency Injection (Metro)

- `AppScope` singleton scope with `@SingleIn(AppScope::class)`
- `@ContributesBinding` for interface→implementation bindings
- `@ContributesIntoSet` for automatic plugin discovery
- All DI is compile-time (no runtime reflection)

### Feature Module Pattern

Each feature module (e.g., `features/delay/`) follows MVI:
- `*ViewModel` with `StateFlow<*State>` and `*Actions` interface
- `*Panel` Composable observes state and emits actions
- ViewModels receive `SynthController` events and delegate to the engine
- No direct `PresetLoader` dependency in ViewModels (see `docs/ViewModelRefactoring.md`)

## Platform Support

| Platform | Audio | Status |
|----------|-------|--------|
| Desktop (JVM) | JSyn | Primary development target |
| Android | JSyn | Full support |
| wasmJs | Stub | UI only, audio disabled |
| iOS | None | Skeleton only |

## Key Files

| File | What it does |
|------|-------------|
| `core/audio/.../dsp/DspPlugin.kt` | Base plugin interface |
| `core/audio/.../dsp/PortsDsl.kt` | Type-safe port definition DSL |
| `core/foundation/.../controller/SynthController.kt` | Control event bus |
| `apps/composeApp/.../dsp/DspSynthEngine.kt` | Main synth wiring and signal routing |
| `apps/composeApp/.../dsp/DspWiringGraph.kt` | Static graph topology |
| `apps/composeApp/.../dsp/DspVoiceManager.kt` | Voice state and envelopes |
| `docs/audio/AUDIO_PATH.md` | Complete signal path documentation |

## Conventions

- Kotlin 2.3.0 with JVM 17 target
- `libremidi-panama` is excluded from test configurations (requires JVM 22+)
- Compose hot reload is enabled in `gradle.properties`
- Gradle configuration cache and build cache are enabled; JVM heap is set to 6GB
