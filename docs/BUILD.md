# Build Guide

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 21 (LTS) | Kotlin/JVM compilation, Gradle |
| Android SDK | 35+ | Android target |
| Android NDK | 27+ | C++ DSP engine on Android (Oboe) |
| CMake | 3.22+ | C++ DSP library build |
| Emscripten | 3.1.64+ | WASM target (C++ DSP compiled to WebAssembly) |
| Xcode | 16+ | iOS/macOS builds |
| XcodeGen | 2.40+ | iOS Xcode project generation (`brew install xcodegen`) |

**Optional:**
- [jenv](https://www.jenv.be/) for managing JDK versions
- [Bazelisk](https://github.com/bazelbuild/bazelisk) for rebuilding MediaPipe JNI (see README)

### Eurorack Source

The C++ DSP engine depends on [Mutable Instruments Eurorack](https://github.com/pichenettes/eurorack) firmware source. Clone it as a sibling directory:

```bash
cd ~/Source
git clone https://github.com/pichenettes/eurorack.git
```

The CMake build expects `eurorack/` at `../../../eurorack` relative to `liborpheus_dsp/`, or set `EURORACK_DIR` explicitly.

### Emscripten (WASM only)

```bash
git clone https://github.com/emscripten-core/emsdk.git ~/emsdk
cd ~/emsdk
./emsdk install latest
./emsdk activate latest
source ~/emsdk/emsdk_env.sh
```

Add `source ~/emsdk/emsdk_env.sh` to your shell profile for convenience.

## Quick Start

```bash
# Desktop (C++ DSP engine via JNI + miniaudio)
./gradlew buildDesktopNative && ./gradlew :apps:composeApp:run

# Android
./gradlew :apps:androidApp:installDebugRelease

# iOS Simulator
./gradlew :apps:composeApp:linkDebugFrameworkIosSimulatorArm64
cd apps/iosApp && xcodegen generate && open OrpheusApp.xcodeproj

# WASM dev server (opens browser at localhost:8080)
./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun

# WASM in orphic.fm site (serves at localhost:4001/synth/)
./scripts/dev-site.sh
```

## Platform Details

### Desktop (JVM)

The audio engine is the C++ DSP library (`liborpheus_desktop.dylib` on macOS) loaded via JNI, with [miniaudio](https://miniaud.io/) for low-latency audio output.

```bash
# Build the native library
./gradlew buildDesktopNative

# Run desktop app
./gradlew :apps:composeApp:run

# Package for distribution (requires full JDK with jpackage)
./gradlew :apps:composeApp:packageReleaseDistributionForCurrentOS
```

The native library is built from `liborpheus_dsp/` using `liborpheus_dsp/platform/jvm/CMakeLists.txt`.

### Android

Uses [Oboe](https://github.com/google/oboe) (Google's C++ low-latency audio library) with the C++ DSP engine compiled via NDK.

```bash
# Debug build
./gradlew :apps:androidApp:installDebug

# Release build
./gradlew :apps:androidApp:installDebugRelease

# Run instrumented tests
./gradlew :apps:androidApp:connectedDebugAndroidTest
```

The Android CMake build is configured in `apps/androidApp/build.gradle.kts` and uses `liborpheus_dsp/platform/android/CMakeLists.txt`.

### iOS

The C++ DSP engine is compiled as a static library for iOS arm64 (device) and simulator, linked via Kotlin/Native cinterop. Audio renders through `AVAudioEngine` with an `AVAudioSourceNode` callback. The Xcode project is generated via XcodeGen.

```bash
# Build the Kotlin framework for simulator
./gradlew :apps:composeApp:linkDebugFrameworkIosSimulatorArm64

# Generate Xcode project and open it
cd apps/iosApp
xcodegen generate
open OrpheusApp.xcodeproj

# Or build and install from command line
xcodebuild -project OrpheusApp.xcodeproj -scheme OrpheusApp \
  -sdk iphonesimulator -configuration Debug build
xcrun simctl install booted \
  ~/Library/Developer/Xcode/DerivedData/OrpheusApp-*/Build/Products/Debug-iphonesimulator/OrpheusApp.app
xcrun simctl launch booted org.balch.orpheus.app

# Build for device (requires signing)
./gradlew :apps:composeApp:linkDebugFrameworkIosArm64
```

The iOS CMake build is configured in `liborpheus_dsp/platform/ios/CMakeLists.txt`. The Gradle build tasks `buildIosDeviceNative` and `buildIosSimNative` handle CMake invocation automatically.

**First-time setup:** After generating the Xcode project, set your development team in Xcode (Signing & Capabilities) or edit `DEVELOPMENT_TEAM` in `project.yml` and regenerate.

**Current status:** Core synth UI and audio engine work. TTS, hand tracking, and MediaPipe are stubbed (returning unavailable). Presets and preferences persist via `NSUserDefaults`.

### WASM

The WASM target compiles the C++ DSP engine to WebAssembly via Emscripten. Audio runs in a Web Worker, rendering 128-frame buffers that feed an AudioWorkletNode for gapless playback.

```bash
# Build WASM module (one-time or after C++ changes)
source ~/emsdk/emsdk_env.sh
cd liborpheus_dsp/platform/wasm
mkdir -p build && cd build
emcmake cmake ../.. -DBUILD_WASM=ON
emmake make -j$(nproc)

# Copy to app resources
cp orpheus_dsp.js orpheus_dsp.wasm \
   ../../../../apps/composeApp/src/wasmJsMain/resources/

# Run dev server
./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun
```

The Gradle build has a `copyWasmDsp` task that copies WASM artifacts from the Emscripten build output to the processed resources directory automatically.

### Local Dev with orphic.fm Site

Build the WASM synth and serve it inside the full Jekyll site at `localhost:4001/synth/`:

```bash
# Full build + copy + serve
./scripts/dev-site.sh

# Use existing build output (faster iteration)
./scripts/dev-site.sh --skip-build

# Build + copy only (start Jekyll yourself)
./scripts/dev-site.sh --copy-only
```

Requires the `orphic-fm` site repo at `~/Source/orphic-fm` (override with `ORPHIC_FM_SITE` env var) and Jekyll/Bundler installed. API keys in `local.properties` are automatically stripped during the build.

### Deploy to GitHub Pages

**CI (automatic):** Pushes to `main` trigger `.github/workflows/deploy-wasm.yml`, which builds the WASM distribution and pushes it to `balch/orphic-fm` via SSH deploy key.

**Manual:**

```bash
# Full build + deploy
./scripts/deploy-gh-pages.sh

# Preview what would be deployed
./scripts/deploy-gh-pages.sh --dry-run

# Deploy existing build output
./scripts/deploy-gh-pages.sh --skip-build
```

Both CI and manual deploy strip API keys from `local.properties` before building. See the script header for deploy key setup instructions.

## C++ DSP Library (`liborpheus_dsp/`)

The C++ DSP engine is shared across Android, Desktop (JNI), and WASM. It ports Plaits voice engines, effects (delay, reverb, distortion), and a graph-based audio routing system.

```
liborpheus_dsp/
  src/              Core engine: orpheus_engine.cpp, orpheus_graph.cpp, orpheus_units.cpp
  include/          Public C API headers
  test/             C++ test suite (Google Test-style)
  platform/
    android/        Android NDK CMake config
    ios/            iOS static library CMake config (arm64 device + simulator)
    jvm/            Desktop JNI CMake config
    wasm/           Emscripten CMake config + WASM export wrappers
```

### Building Tests

```bash
cd liborpheus_dsp
mkdir -p build_test && cd build_test
cmake .. -DCMAKE_BUILD_TYPE=Debug
make -j$(nproc)
./orpheus_dsp_test
```

See [TESTS.md](TESTS.md) for full testing documentation.

## Configuration

### AI API Keys (optional)

Create `local.properties` in the project root:

```properties
GEMINI_API_KEY=your_gemini_api_key_here
ANTHROPIC_API_KEY=your_anthropic_api_key_here
```

Keys are injected at build time via BuildKonfig. The app runs without them.

### JDK for Packaging

Desktop packaging requires a full JDK 17+ with `jpackage`. If you see `'jpackage' is missing`:

```properties
# local.properties
org.gradle.java.home=/path/to/your/full/jdk
```

## Module Structure

```
core/audio/          DSP engine interfaces, plugin system, type-safe port DSL
core/dsp-engine/     Shared DSP graph: voice manager, wiring, automation
core/foundation/     MIDI, presets, SynthController event bus, speech
core/gestures/       ASL sign classifier, gesture interpretation engines
core/mediapipe/      MediaPipe hand tracking abstraction (Android + Desktop)
core/plugin-api/     Shared symbol definitions across all plugins
core/plugins/        14 self-contained DSP plugin modules
features/            20+ UI feature modules (Compose + ViewModel, MVI)
ui/theme, ui/widgets Dark synth theme, knobs, sliders, collapsible panels
apps/composeApp/     App wiring: signal routing, voice management, DI
apps/iosApp/         iOS Xcode project (XcodeGen), Swift AppDelegate, Info.plist
liborpheus_dsp/      C++ DSP engine (shared across Android, Desktop, iOS, WASM)
build-logic/         Convention plugins for consistent KMP module config
```

## Useful Gradle Commands

```bash
# Compile check (no run)
./gradlew compileKotlinJvm
./gradlew :apps:composeApp:compileKotlinWasmJs
./gradlew :apps:composeApp:compileKotlinIosSimulatorArm64

# iOS framework (simulator / device)
./gradlew :apps:composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :apps:composeApp:linkDebugFrameworkIosArm64

# Single plugin build
./gradlew :core:plugins:<name>:build

# Feature module build
./gradlew :features:<name>:build

# Full build (all platforms)
./gradlew build
```
