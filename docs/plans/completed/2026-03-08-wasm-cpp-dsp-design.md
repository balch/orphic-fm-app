# C++ DSP via Emscripten WASM — Design

## Goal

Replace the Kotlin DSP Web Worker with the C++ engine compiled to WASM via Emscripten, reusing the existing AudioWorklet output pipeline.

## Architecture

The existing Web Worker + AudioWorklet buffering pipeline stays intact. The worker's internals change from Kotlin `DspSynthEngine` + `DspGraphScheduler` to an Emscripten-compiled C++ WASM module called via the C API.

```
Main Thread (Compose UI)                Worker Thread
┌──────────────────────┐    postMessage  ┌──────────────────────┐
│ DspWorkerProxy       │ ──────────────→ │ orpheus-dsp-worker.js│
│ (unchanged Kotlin)   │                 │  ┌──────────────────┐│
│ sends CMD_SET_PORT,  │                 │  │ orpheus_dsp.wasm ││
│ CMD_VOICE_GATE, etc. │                 │  │ (C++ engine)     ││
│                      │                 │  └──────────────────┘│
│                      │                 │  renders 128-frame   │
│                      │                 │  buffers, posts to   │
│                      │                 │  AudioWorklet port   │
└──────────────────────┘                 └──────────────────────┘
                                                    │
                                         ┌──────────▼───────────┐
                                         │ dsp-output-processor  │
                                         │ (unchanged JS)        │
                                         └───────────────────────┘
```

## Components

### New files

- `liborpheus_dsp/platform/wasm/CMakeLists.txt` — Emscripten build config, exports C API functions
- `liborpheus_dsp/platform/wasm/wasm_exports.cpp` — `EMSCRIPTEN_KEEPALIVE` wrappers for the C API
- `apps/composeApp/src/wasmJsMain/resources/orpheus-dsp-worker.js` — Worker script: loads WASM module, dispatches postMessage commands to C API, runs render-ahead loop

### Modified files

- `apps/composeApp/src/wasmJsMain/kotlin/.../DspWorkerProxy.kt` — Point Worker URL at new JS file
- `apps/composeApp/src/wasmJsMain/kotlin/.../main.wasmJs.kt` — Send ODWG bytes on init, remove Kotlin worker creation
- `core/foundation/src/commonMain/kotlin/.../DspWorkerProtocol.kt` — Add `CMD_LOAD_GRAPH` command ID

### Deleted

- `apps/dspWorker/` — Entire Kotlin DSP worker module (was WIP, did not work well)

## Command Protocol

Same `postMessage` JSON format as current. New command for graph loading:

| Command | ID | Fields | Purpose |
|---------|----|--------|---------|
| `CMD_LOAD_GRAPH` | 30 | `graph: ArrayBuffer` | Send ODWG binary to worker |

Worker dispatches existing commands to the C API:

- `CMD_SET_PORT` → `orpheus_engine_set_port(engine, uri, symbol, value)`
- `CMD_VOICE_GATE` → `orpheus_engine_set_voice_gate(engine, idx, gate)`
- `CMD_VOICE_TUNE` → `orpheus_engine_set_voice_tune(engine, idx, note)`
- `CMD_SET_MASTER_VOLUME` → `orpheus_engine_set_port(engine, "stereo", "master_vol", value)`
- etc.

## Graph Loading

The main thread sends the ODWG binary via `CMD_LOAD_GRAPH` as an `ArrayBuffer` Transferable. The worker copies it into the WASM heap and calls `orpheus_engine_load_graph()`. This allows swapping graph definitions at runtime in the future.

## Render Loop

The worker uses `setInterval(10ms)`, checks AudioWorklet queue depth, and renders enough 128-frame buffers to maintain the target depth:

```js
function renderTick() {
    const needed = targetDepth - queueDepth;
    for (let i = 0; i < needed; i++) {
        _orpheus_engine_process(engine, outputPtr, 128);
        // Copy from WASM heap, de-interleave L/R, post to worklet port
    }
}
```

## Build

```bash
cd liborpheus_dsp/platform/wasm
emcmake cmake ../.. -DBUILD_WASM=ON
emmake make
# Produces: orpheus_dsp.js + orpheus_dsp.wasm
```

Output files are copied to `apps/composeApp/src/wasmJsMain/resources/`.

## Testing

- Build WASM module, verify it loads in a browser
- Launch dev server (`wasmJsBrowserDevelopmentRun`), play notes, verify audio
- Compare with desktop C++ engine — should sound identical

## Decisions

- **Worker architecture preserved** — lowest risk, reuses existing buffering and AudioWorklet
- **Kotlin worker replaced (not kept)** — it was WIP and unreliable
- **ODWG sent via postMessage** — allows future graph swapping at runtime
