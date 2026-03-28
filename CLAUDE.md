# Orphic FM App

Kotlin Multiplatform synthesizer app with a shared C++ DSP engine (`liborpheus_dsp/`) porting Mutable Instruments firmware. Targets Android, JVM desktop, and WASM.

Key domains: C++ DSP graph, Plaits voice engines, MIDI CC routing, AI agent configs, Compose UI panels/ViewModels.

**Primary platform: JVM desktop.** When the user reports issues or asks to test, assume JVM unless stated otherwise.

## Bug Fixing

When fixing bugs, trace the root cause through the full signal/data path before proposing a fix. Do not guess — read the code from input to output and explain where the issue is before editing anything.

For DSP code specifically: understand whether values are linear vs exponential, what buffer sizes are expected, and how the signal chain transforms data at each stage.

## ViewModel Pattern

See `.claude/skills/panel-viewmodel-feature/` for the full MVI pattern (Symbol -> Plugin -> ViewModel -> Panel -> Registration), DI annotations, viz integration, and new module checklist. Canonical reference: `LfoViewModel.kt` in `features/lfo/`.

## DSP Implementation

See `.claude/skills/dsp-implementation/` for C++ unit creation, engine atomics, normalization, port routing, and graph wiring. Test patterns in `.claude/skills/writing-dsp-tests/`.

## Build

- Full app: `./gradlew :apps:composeApp:build`
- Single plugin: `./gradlew :core:plugins:<name>:build`
- Feature module: `./gradlew :features:<name>:build`
- JVM compile check: `./gradlew compileKotlinJvm`
- WASM production build: `./gradlew :apps:composeApp:wasmJsBrowserDistribution`
- WASM dev server: `./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun` (serves on localhost:8080)
- C++ tests: `cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop -DEURORACK_DIR=/Users/balch/Source/eurorack -DBUILD_TESTS=ON -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test`

## C++ Clang Diagnostics

IDE clang errors in `liborpheus_dsp/` (e.g. "orpheus_dsp.h not found", "undeclared identifier stmlib") are **false positives** from the editor's built-in clang not having CMake include paths. The actual cmake build always succeeds. To fix these diagnostics, ensure `compile_commands.json` exists at the project root (symlinked from the cmake build directory). The `-DCMAKE_EXPORT_COMPILE_COMMANDS=ON` flag in the C++ test command above generates it automatically. **Do not treat these IDE diagnostics as build failures.**

## WASM

See `.claude/skills/wasm-dev/` for build, deploy (GitHub Pages + local dev server), Playwright debugging, JS bridge conventions, and known issues (incremental compilation, platform restrictions).
