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
- C++ tests: `CMAKE_GENERATOR=Ninja cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop -DEURORACK_DIR=$EURORACK_DIR -DBUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test -j$(sysctl -n hw.ncpu) && liborpheus_dsp/build-desktop/orpheus_dsp_test -j`
  - Always pass `-j` to both the build and the runner. Without them Makefiles compile one file at a time and the runner runs suites in sequence (~90s + ~105s, versus ~15s + ~20s).
  - `CMAKE_GENERATOR` only applies to a new build dir; an existing Makefiles dir keeps its generator. Delete the dir once to switch it to Ninja.
  - ccache is picked up automatically when installed, with hits shared across worktrees. Keep absolute paths off target-wide compile definitions (ccache doesn't rewrite `-D` values): put them on one source file, as `test_paths.cpp` does for `TEST_DATA_DIR`.
  - (Release is the CMake default for this project; pass `-DCMAKE_BUILD_TYPE=Debug` to override.)
  - Run specific suites: `liborpheus_dsp/build-desktop/orpheus_dsp_test tides bass warps` (add `-j` for one process per suite)
  - List suites: `liborpheus_dsp/build-desktop/orpheus_dsp_test --list`
  - Diagnose graph wiring: `ORPHEUS_DUMP_GRAPH=1 liborpheus_dsp/build-desktop/orpheus_dsp_test graph` — prints full exec order for every graph load. Silent by default to keep test output readable.
  - AddressSanitizer (opt-in, use a separate build dir): add `-DENABLE_ASAN=ON -DCMAKE_BUILD_TYPE=Debug` to the cmake command above, e.g. `cmake -S liborpheus_dsp -B liborpheus_dsp/build-asan -DEURORACK_DIR=$EURORACK_DIR -DBUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Debug -DENABLE_ASAN=ON -DCMAKE_EXPORT_COMPILE_COMMANDS=ON`. Default is OFF with zero effect on normal builds.

## Which Tests When

| Changed | Run |
|---|---|
| A UI tweak (layout, labels, colours) | only the covering classes: `./gradlew :<module>:jvmTest --tests '*TracksLayoutTest*'` (seconds) |
| Kotlin in one module | `./gradlew :<module>:jvmTest` |
| Kotlin across modules, before committing | `./gradlew jvmTest` (minutes) |
| A screenshot to look at | that harness alone: `--tests '*DockRenderHarness*' -PrenderHarness` |
| C++ DSP | the suites you touched, then `orpheus_dsp_test -j` |
| A C++ header that Kotlin tests parse (`pulsar_limits.h`, `orpheus_unit_pulsar.h`, `orpheus_engine.h`) | the C++ suite plus `./gradlew :features:pulsar:jvmTest` |
| Before merging to main, or code touching iOS/Android/wasm source sets | `./gradlew allTests` |

**The UI tests are expensive.** `:apps:djapp:shared:jvmTest` renders Compose scenes off screen at phone, tablet and TV sizes (`TracksLayoutTest`, `FrameBudgetTest`, ...). Don't rerun it for each tweak: run the covering classes with `--tests`, save the full run for once before landing, and say up front when a run will take minutes.

`*RenderHarness` classes write PNGs to look at and assert nothing. They took ~4.5 of the djapp suite's ~7 minutes, so every `Test` task skips them unless `-PrenderHarness` is set (root `build.gradle.kts`). A render that checks something real belongs in a `*Test` class, so it keeps running (e.g. `FaceMorphRenderTest`).

`allTests` is ~8,400 tasks: it reruns the shared `commonTest` code on every target, including a Kotlin/Native iOS test link per module. Don't use it as the everyday loop.

`orpheus_engine.h` forward-declares `PulsarState` instead of including `orpheus_unit_pulsar.h`, so Pulsar header edits rebuild ~57 TUs instead of ~140. A TU that needs Pulsar types includes `orpheus_unit_pulsar.h` (tests: `test_pulsar_helpers.h`) itself.

## Generated C++ Test Fixtures

`liborpheus_dsp/test/data/preset_*.h`, `default_graph.odwg` and `dj_graph.odwg` are generated from Kotlin and checked in for the C++ harness. **`jvmTest` only verifies them — it never writes to the working tree.** A drifted fixture fails the build with a line-level diff naming the source of truth.

Both `.odwg` files must stay tracked. The C++ harness loads them and cannot generate them, so a missing one fails as `Cannot open DJ graph: ...` printed *above* the test banners, where it scrolls out of sight — the visible symptom is the `djapp` suite reporting `1/17`. Any new graph topology should be committed as soon as it is generated.

Regenerate deliberately, then commit the result:
- Presets: `./gradlew :features:presets:exportPresets`
- Wiring graph: `./gradlew :core:dsp-engine:exportOdwg`
- Both, alongside a normal run: `./gradlew jvmTest -Pexport-fixtures`

The headers are derived from `features/presets/.../composeResources/files/presets/*.json`. If a generated value looks wrong, fix the JSON — editing the header only hides the drift until the next regeneration.

## C++ Clang Diagnostics

IDE clang errors in `liborpheus_dsp/` (e.g. "orpheus_dsp.h not found", "undeclared identifier stmlib") are **false positives** from the editor's built-in clang not having CMake include paths. The actual cmake build always succeeds. To fix these diagnostics, ensure `compile_commands.json` exists at the project root (symlinked from the cmake build directory). The `-DCMAKE_EXPORT_COMPILE_COMMANDS=ON` flag in the C++ test command above generates it automatically. **Do not treat these IDE diagnostics as build failures.**

## WASM

See `.claude/skills/wasm-dev/` for build, deploy (GitHub Pages + local dev server), Playwright debugging, JS bridge conventions, and known issues (incremental compilation, platform restrictions).
