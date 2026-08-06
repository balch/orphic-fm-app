# Orphic FM App

Kotlin Multiplatform synthesizer app with a shared C++ DSP engine (`liborpheus_dsp/`) porting Mutable Instruments firmware. Targets Android, JVM desktop, and WASM.

Key domains: C++ DSP graph, Plaits voice engines, MIDI CC routing, AI agent configs, Compose UI panels/ViewModels.

**Primary platform: JVM desktop.** When the user reports issues or asks to test, assume JVM unless stated otherwise.

## Bug Fixing

When fixing bugs, trace the root cause through the full signal/data path before proposing a fix. Do not guess — read the code from input to output and explain where the issue is before editing anything.

For DSP code specifically: understand whether values are linear vs exponential, what buffer sizes are expected, and how the signal chain transforms data at each stage.

## Dependency Injection

See `docs/di-architecture.md` for the Metro layout: the seven `@DependencyGraph` declarations and
where each is declared, the three scopes (`AppScope`, `FeatureScope`, `HeaderPanelScope`), the
supporting types (`FeatureGraphHolder`, `FeatureCollection`, `SynthFeatureRegistry`,
`InjectedViewModelFactory`), the per-app binding differences, the eager-root list, and the
constraints (`T` vs `T?` type keys, unscopable `@Binds`, `() -> T` for AppScope-to-FeatureScope).

## ViewModel Pattern

See `.claude/skills/panel-viewmodel-feature/` for the full MVI pattern (Symbol -> Plugin -> ViewModel -> Panel -> Registration), DI annotations, viz integration, and new module checklist. Canonical reference: `LfoViewModel.kt` in `features/lfo/`.

## DSP Implementation

See `.claude/skills/dsp-implementation/` for C++ unit creation, engine atomics, normalization, port routing, and graph wiring. Test patterns in `.claude/skills/writing-dsp-tests/`.

## Build

- Run desktop app (JVM): `./gradlew :apps:orpheus:desktopApp:run` (hot reload: `:apps:orpheus:desktopApp:hotRun`)
- Build desktop app: `./gradlew :apps:orpheus:desktopApp:build`
- Build shared library: `./gradlew :apps:orpheus:shared:build`
- Single plugin: `./gradlew :core:plugins:<name>:build`
- Feature module: `./gradlew :features:<name>:build`
- JVM compile check: `./gradlew compileKotlinJvm`
- WASM production build: `./gradlew :apps:orpheus:webApp:wasmJsBrowserDistribution`
- WASM dev server: `./gradlew :apps:orpheus:webApp:wasmJsBrowserDevelopmentRun` (serves on localhost:8080)
- C++ tests: `cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop -DEURORACK_DIR=$EURORACK_DIR -DBUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test`
  - (Release is the CMake default for this project; pass `-DCMAKE_BUILD_TYPE=Debug` to override.)
  - Run specific suites: `liborpheus_dsp/build-desktop/orpheus_dsp_test tides bass warps`
  - List suites: `liborpheus_dsp/build-desktop/orpheus_dsp_test --list`
  - Diagnose graph wiring: `ORPHEUS_DUMP_GRAPH=1 liborpheus_dsp/build-desktop/orpheus_dsp_test graph` — prints full exec order for every graph load. Silent by default to keep test output readable.
  - AddressSanitizer (opt-in, use a separate build dir): add `-DENABLE_ASAN=ON -DCMAKE_BUILD_TYPE=Debug` to the cmake command above, e.g. `cmake -S liborpheus_dsp -B liborpheus_dsp/build-asan -DEURORACK_DIR=$EURORACK_DIR -DBUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Debug -DENABLE_ASAN=ON -DCMAKE_EXPORT_COMPILE_COMMANDS=ON`. Default is OFF with zero effect on normal builds.

## Generated C++ Test Fixtures

`liborpheus_dsp/test/data/preset_*.h`, `default_graph.odwg` and `dj_graph.odwg` are generated from Kotlin and checked in for the C++ harness. **`jvmTest` only verifies them — it never writes to the working tree.** A drifted fixture fails the build with a line-level diff naming the source of truth.

Both `.odwg` files must stay tracked. The C++ harness loads them and cannot generate them, so a missing one fails as `Cannot open DJ graph: ...` printed *above* the test banners, where it scrolls out of sight — the visible symptom is the `djapp` suite reporting `1/17`. A new graph topology (e.g. a Baton one) should be committed as soon as it is generated.

Regenerate deliberately, then commit the result:
- Presets: `./gradlew :features:presets:exportPresets`
- Wiring graph: `./gradlew :core:dsp-engine:exportOdwg`
- Both, alongside a normal run: `./gradlew jvmTest -Pexport-fixtures`

The headers are derived from `features/presets/.../composeResources/files/presets/*.json`. If a generated value looks wrong, fix the JSON — editing the header only hides the drift until the next regeneration.

## C++ Clang Diagnostics

IDE clang errors in `liborpheus_dsp/` (e.g. "orpheus_dsp.h not found", "undeclared identifier stmlib") are **false positives** from the editor's built-in clang not having CMake include paths. The actual cmake build always succeeds. To fix these diagnostics, ensure `compile_commands.json` exists at the project root (symlinked from the cmake build directory). The `-DCMAKE_EXPORT_COMPILE_COMMANDS=ON` flag in the C++ test command above generates it automatically. **Do not treat these IDE diagnostics as build failures.**

## WASM

See `.claude/skills/wasm-dev/` for build, deploy (GitHub Pages + local dev server), Playwright debugging, JS bridge conventions, and known issues (incremental compilation, platform restrictions).
