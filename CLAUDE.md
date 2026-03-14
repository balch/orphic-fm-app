# Orphic FM App

Kotlin Multiplatform synthesizer app porting Mutable Instruments DSP engines to Kotlin/JSyn. Targets Android, JVM desktop, and WASM.

Key domains: JSyn audio graph, Plaits voice engines, MIDI CC routing, AI agent configs, Compose UI panels/ViewModels.

## Bug Fixing

When fixing bugs, trace the root cause through the full signal/data path before proposing a fix. Do not guess — read the code from input to output and explain where the issue is before editing anything.

For DSP code specifically: understand whether values are linear vs exponential, what buffer sizes are expected, and how the signal chain transforms data at each stage.

## ViewModel Pattern

The canonical ViewModel reference is `LfoViewModel.kt`:
`features/lfo/src/commonMain/kotlin/org/balch/orpheus/features/lfo/LfoViewModel.kt`

All feature ViewModels follow this MVI pattern:
1. `@Immutable data class *UiState` — UI state with defaults
2. `@Immutable data class *PanelActions` — action lambdas with `EMPTY` companion
3. `private sealed interface *Intent` — one variant per control
4. `interface *Feature : SynthFeature<UiState, Actions>` — with `SynthControlDescriptor` (panelId, title, markdown docs, portControlKeys)
5. `@Inject
class *ViewModel` — takes `SynthController`, `DispatcherProvider`, `FeatureCoroutineScope`
   - `controlFlow()` per port symbol
   - `actions` wired to setters (floatSetter, enumSetter, boolSetter, or custom)
   - `merge()` control flows → `scan()` reducer → `stateIn()`
6. `companion object` with `previewFeature()` and `@Composable feature()`

## Build

- Full app: `./gradlew :apps:composeApp:build`
- Single plugin: `./gradlew :core:plugins:<name>:build`
- Feature module: `./gradlew :features:<name>:build`
- JVM compile check: `./gradlew compileKotlinJvm`
- WASM production build: `./gradlew :apps:composeApp:wasmJsBrowserDistribution`
- WASM dev server: `./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun` (serves on localhost:8080)

## WASM Deploy & Dev

### Local dev with orphic.fm site
- `./scripts/dev-site.sh` — build WASM + copy to `~/Source/orphic-fm/synth/` + serve Jekyll on localhost:4001
- `./scripts/dev-site.sh --skip-build` — use existing build output
- `./scripts/dev-site.sh --copy-only` — build + copy without starting server
- Test at http://localhost:4001/synth/
- Set `ORPHIC_FM_SITE` env var if the site repo is not at `~/Source/orphic-fm`

### Deploy to GitHub Pages
- CI: pushes to `main` auto-deploy via `.github/workflows/deploy-wasm.yml`
- Manual: `./scripts/deploy-gh-pages.sh` (or `--dry-run` to preview)
- Both scripts strip API keys from `local.properties` during build
- Deploys to `balch/orphic-fm` repo via SSH deploy key

## Debugging WASM

Use Playwright MCP to debug the running WASM app at `http://localhost:4001/synth/` (or `http://localhost:8080/` if using the raw dev server):
1. `browser_navigate` to the app URL
2. `browser_console_messages` with `level: "info"` to read all console output
3. `browser_take_screenshot` to see current UI state
4. `browser_snapshot` for accessibility tree (useful for finding interactive elements)

All JS bridge files in `apps/composeApp/src/wasmJsMain/resources/` should use `console.log`/`console.error` with a prefix tag (e.g., `[MP]`) for filtering. Kotlin-side logging via KmLogging also appears in the browser console.
