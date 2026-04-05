---
name: wasm-dev
description: Use when building, deploying, or debugging the WASM target, when working with the orphic.fm site, when debugging JS bridge files, or when troubleshooting browser console output.
---

# WASM Development & Deployment

## Build Commands

- Production build: `./gradlew :apps:orpheus:wasmJsBrowserDistribution`
- Dev server: `./gradlew :apps:orpheus:wasmJsBrowserDevelopmentRun` (serves on localhost:8080)

## Local Dev with orphic.fm Site

- `./scripts/dev-site.sh` — build WASM + copy to `~/Source/orphic-fm/synth/` + serve Jekyll on localhost:4001
- `./scripts/dev-site.sh --skip-build` — use existing build output
- `./scripts/dev-site.sh --copy-only` — build + copy without starting server
- Test at http://localhost:4001/synth/
- Set `ORPHIC_FM_SITE` env var if the site repo is not at `~/Source/orphic-fm`

## Deploy to GitHub Pages

- CI: pushes to `main` auto-deploy via `.github/workflows/deploy-wasm.yml`
- Manual: `./scripts/deploy-gh-pages.sh` (or `--dry-run` to preview)
- Both scripts strip API keys from `local.properties` during build
- Deploys to `balch/orphic-fm` repo via SSH deploy key

## Debugging with Playwright

Use Playwright MCP to debug the running WASM app at `http://localhost:4001/synth/` (or `http://localhost:8080/` for raw dev server):

1. `browser_navigate` to the app URL
2. `browser_console_messages` with `level: "info"` to read all console output
3. `browser_take_screenshot` to see current UI state
4. `browser_snapshot` for accessibility tree (useful for finding interactive elements)

## JS Bridge Conventions

- All JS bridge files live in `apps/orpheus/src/wasmJsMain/resources/`
- Use `console.log`/`console.error` with a prefix tag (e.g., `[MP]`) for filtering
- Kotlin-side logging via KmLogging also appears in the browser console

## Audio Architecture (WASM)

C++ DSP runs via Emscripten in an AudioWorklet worker:
- `WasmNativeAudioEngine` -> `DspWorkerProxy` -> `orpheus-dsp-worker.js`
- Parameters flow: Kotlin -> JS interop -> Worker postMessage -> C++ atomics

## Known Issues

### Incremental Compilation (KT-82395)
Metro compiler plugin generates top-level declarations that crash incremental JS/WASM compilation. **Must disable both** in `gradle.properties`:
```
kotlin.incremental.js=false
kotlin.incremental.js.klib=false
```
`kotlin.incremental.wasmJs` is NOT a real KGP property — WASM uses the JS properties.

### Platform Restrictions
- `@Volatile` is not available in Kotlin/WASM (single-threaded) — remove annotations for wasmJsMain
- WASM contribution hints require Kotlin 2.3.20+ and Metro 0.11.2+
