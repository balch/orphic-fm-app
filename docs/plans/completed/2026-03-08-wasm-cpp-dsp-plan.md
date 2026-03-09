# WASM C++ DSP Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Compile the C++ DSP engine to WASM via Emscripten and wire it into the existing Web Worker + AudioWorklet pipeline, replacing the Kotlin DSP worker.

**Architecture:** The existing `DspWorkerProxy` (main thread) sends `postMessage` commands to a new pure-JS worker script (`orpheus-dsp-worker.js`) that loads the Emscripten WASM module. The worker calls the C API (`orpheus_engine_create`, `orpheus_engine_process`, etc.), renders 128-frame buffers, and posts them to the existing `dsp-output-processor.js` AudioWorklet. The Kotlin DSP worker module (`apps/dspWorker/`) is deleted.

**Tech Stack:** Emscripten 3.x, C++17, Web Workers, AudioWorklet, CMake

**Design doc:** `docs/plans/2026-03-08-wasm-cpp-dsp-design.md`

---

## Task 1: Install Emscripten and create WASM build target

**Files:**
- Create: `liborpheus_dsp/platform/wasm/CMakeLists.txt`
- Create: `liborpheus_dsp/platform/wasm/wasm_exports.cpp`

**Context:** Emscripten is not currently installed. The existing `liborpheus_dsp/CMakeLists.txt` builds a static library (`orpheus_dsp`) used by both the test executable and the JVM desktop shared library. We need a new CMake target that compiles the same sources to WASM.

**Step 1: Install Emscripten SDK**

```bash
cd ~
git clone https://github.com/emscripten-core/emsdk.git
cd emsdk
./emsdk install latest
./emsdk activate latest
source ./emsdk_env.sh
emcc --version  # Should show 3.x
```

**Step 2: Create the WASM exports file**

Create `liborpheus_dsp/platform/wasm/wasm_exports.cpp`:

```cpp
#include <emscripten.h>
#include "orpheus_engine.h"
#include "orpheus_graph.h"

// All exported functions use C linkage and EMSCRIPTEN_KEEPALIVE
// to prevent dead-code elimination.

extern "C" {

EMSCRIPTEN_KEEPALIVE
OrpheusEngine* wasm_engine_create(float sample_rate) {
    return orpheus_engine_create(sample_rate);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_destroy(OrpheusEngine* engine) {
    orpheus_engine_destroy(engine);
}

EMSCRIPTEN_KEEPALIVE
int wasm_engine_load_graph(OrpheusEngine* engine, const uint8_t* data, int length) {
    return orpheus_engine_load_patch(engine, data, length);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_process(OrpheusEngine* engine, float* output, int num_frames) {
    orpheus_engine_process(engine, output, num_frames);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_port(OrpheusEngine* engine,
                          const char* plugin_uri,
                          const char* symbol,
                          float value) {
    orpheus_engine_set_port(engine, plugin_uri, symbol, value);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_gate(OrpheusEngine* engine, int voice_index, int gate) {
    orpheus_engine_set_voice_gate(engine, voice_index, gate);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_tune(OrpheusEngine* engine, int voice_index, float note) {
    orpheus_engine_set_voice_tune(engine, voice_index, note);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_engine(OrpheusEngine* engine, int voice_index, int engine_index) {
    orpheus_engine_set_voice_engine(engine, voice_index, engine_index);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_active(OrpheusEngine* engine, int voice_index, int active) {
    orpheus_engine_set_voice_active(engine, voice_index, active);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_hold(OrpheusEngine* engine, int voice_index, float level) {
    orpheus_engine_set_voice_hold(engine, voice_index, level);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_harmonics(OrpheusEngine* engine, int voice_index, float v) {
    orpheus_engine_set_voice_harmonics(engine, voice_index, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_timbre(OrpheusEngine* engine, int voice_index, float v) {
    orpheus_engine_set_voice_timbre(engine, voice_index, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_morph(OrpheusEngine* engine, int voice_index, float v) {
    orpheus_engine_set_voice_morph(engine, voice_index, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_voice_decay(OrpheusEngine* engine, int voice_index, float v) {
    orpheus_engine_set_voice_decay(engine, voice_index, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_trigger_drum(OrpheusEngine* engine, int drum_index, float accent) {
    orpheus_engine_trigger_drum(engine, drum_index, accent);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_master_volume(OrpheusEngine* engine, float v) {
    orpheus_engine_set_master_volume(engine, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_drive(OrpheusEngine* engine, float v) {
    orpheus_engine_set_drive(engine, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_delay_mix(OrpheusEngine* engine, float v) {
    orpheus_engine_set_delay_mix(engine, v);
}

EMSCRIPTEN_KEEPALIVE
void wasm_engine_set_vibrato(OrpheusEngine* engine, float v) {
    orpheus_engine_set_vibrato(engine, v);
}

EMSCRIPTEN_KEEPALIVE
float* wasm_alloc_output(int num_frames) {
    // Allocate interleaved stereo buffer on WASM heap for JS to read
    static float* buf = nullptr;
    static int buf_size = 0;
    int needed = num_frames * 2;
    if (needed > buf_size) {
        delete[] buf;
        buf = new float[needed];
        buf_size = needed;
    }
    return buf;
}

} // extern "C"
```

**Step 3: Create the WASM CMakeLists.txt**

Create `liborpheus_dsp/platform/wasm/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(orpheus_dsp_wasm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include the main library CMakeLists to get source file lists
set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../eurorack"
    CACHE PATH "Path to eurorack source")

# Re-declare source files (shared with main CMakeLists.txt)
set(COMPAT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../src")

file(GLOB PLAITS_ENGINE_SRC "${EURORACK_DIR}/plaits/dsp/engine/*.cc")
file(GLOB PLAITS_ENGINE2_SRC "${EURORACK_DIR}/plaits/dsp/engine2/*.cc")
file(GLOB PLAITS_SPEECH_SRC "${EURORACK_DIR}/plaits/dsp/speech/*.cc")
file(GLOB PLAITS_PM_SRC "${EURORACK_DIR}/plaits/dsp/physical_modelling/*.cc")
file(GLOB PLAITS_FM_SRC "${EURORACK_DIR}/plaits/dsp/fm/*.cc")
set(PLAITS_SRC
    ${PLAITS_ENGINE_SRC} ${PLAITS_ENGINE2_SRC}
    ${PLAITS_SPEECH_SRC} ${PLAITS_PM_SRC} ${PLAITS_FM_SRC}
    "${EURORACK_DIR}/plaits/dsp/voice.cc"
    "${EURORACK_DIR}/plaits/dsp/chords/chord_bank.cc"
    "${EURORACK_DIR}/plaits/resources.cc"
)

set(STMLIB_SRC
    "${EURORACK_DIR}/stmlib/dsp/atan.cc"
    "${EURORACK_DIR}/stmlib/dsp/units.cc"
    "${EURORACK_DIR}/stmlib/utils/random.cc"
)

set(CLOUDS_SRC
    "${EURORACK_DIR}/clouds/dsp/granular_processor.cc"
    "${EURORACK_DIR}/clouds/dsp/mu_law.cc"
    "${EURORACK_DIR}/clouds/dsp/correlator.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/frame_transformation.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/phase_vocoder.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/stft.cc"
    "${EURORACK_DIR}/clouds/resources.cc"
)

set(RINGS_SRC
    "${EURORACK_DIR}/rings/dsp/fm_voice.cc"
    "${EURORACK_DIR}/rings/dsp/part.cc"
    "${EURORACK_DIR}/rings/dsp/string_synth_part.cc"
    "${EURORACK_DIR}/rings/dsp/string.cc"
    "${EURORACK_DIR}/rings/dsp/resonator.cc"
    "${EURORACK_DIR}/rings/resources.cc"
)

set(WARPS_SRC
    "${EURORACK_DIR}/warps/dsp/modulator.cc"
    "${EURORACK_DIR}/warps/dsp/oscillator.cc"
    "${EURORACK_DIR}/warps/dsp/vocoder.cc"
    "${EURORACK_DIR}/warps/dsp/filter_bank.cc"
    "${EURORACK_DIR}/warps/resources.cc"
)

file(GLOB MARBLES_RANDOM_SRC "${EURORACK_DIR}/marbles/random/*.cc")
set(MARBLES_SRC
    ${MARBLES_RANDOM_SRC}
    "${EURORACK_DIR}/marbles/ramp/ramp_extractor.cc"
    "${EURORACK_DIR}/marbles/resources.cc"
)

set(ORPHEUS_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/../../src/orpheus_engine.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../src/orpheus_graph.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../src/orpheus_monitor.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../src/orpheus_units.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../src/orpheus_patch.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/wasm_exports.cpp"
)

add_executable(orpheus_dsp
    ${ORPHEUS_SRC}
    ${STMLIB_SRC}
    ${PLAITS_SRC}
    ${CLOUDS_SRC}
    ${RINGS_SRC}
    ${WARPS_SRC}
    ${MARBLES_SRC}
)

target_include_directories(orpheus_dsp PRIVATE
    "${COMPAT_DIR}"
    "${EURORACK_DIR}"
)

target_compile_definitions(orpheus_dsp PRIVATE TEST)

target_compile_options(orpheus_dsp PRIVATE
    -Wno-unused-parameter -Wno-sign-compare -Wno-unused-variable
    -Wno-missing-field-initializers -Wno-unused-local-typedef
    -Wno-unused-private-field -Wno-char-subscripts
    -Wno-tautological-compare -Wno-implicit-const-int-float-conversion
    -O3 -ffast-math
)

# Emscripten link flags
target_link_options(orpheus_dsp PRIVATE
    -sEXPORTED_RUNTIME_METHODS=['ccall','cwrap','HEAPF32','_malloc','_free']
    -sALLOW_MEMORY_GROWTH=1
    -sINITIAL_MEMORY=33554432
    -sSTACK_SIZE=1048576
    -sENVIRONMENT=worker
    -sMODULARIZE=1
    -sEXPORT_NAME=OrpheusDSP
    -sNO_EXIT_RUNTIME=1
    --no-entry
    -O3
)
```

**Step 4: Build the WASM module**

```bash
source ~/emsdk/emsdk_env.sh
cd liborpheus_dsp/platform/wasm
mkdir -p build && cd build
emcmake cmake .. -DEURORACK_DIR=/Users/balch/Source/eurorack
emmake make -j8
# Should produce: orpheus_dsp.js + orpheus_dsp.wasm
ls -la orpheus_dsp.*
```

Expected: `orpheus_dsp.js` (~few KB loader) and `orpheus_dsp.wasm` (~1-2 MB).

**Step 5: Verify the WASM module exports**

```bash
# List exported functions (should show all wasm_engine_* functions)
wasm-objdump -x orpheus_dsp.wasm | grep -i "wasm_engine\|wasm_alloc" || \
    node -e "const m = require('./orpheus_dsp.js'); m().then(i => console.log(Object.keys(i).filter(k => k.startsWith('_wasm'))))"
```

**Step 6: Add build directory to .gitignore and commit**

```bash
echo "liborpheus_dsp/platform/wasm/build/" >> .gitignore
git add liborpheus_dsp/platform/wasm/ .gitignore
git commit -m "feat(wasm): Add Emscripten build target for C++ DSP engine"
```

---

## Task 2: Create the C++ DSP Worker script

**Files:**
- Create: `apps/composeApp/src/wasmJsMain/resources/orpheus-dsp-worker.js`

**Context:** This JS file replaces the entire Kotlin `dspWorker` module. It runs in a Web Worker, loads the Emscripten WASM module, handles `postMessage` commands from `DspWorkerProxy`, and renders audio buffers to the AudioWorklet. The command protocol matches `DspWorkerProtocol.kt` (CMD IDs: 0=INIT, 1=START, 2=STOP, 10=SET_PORT, 11=VOICE_GATE, 12=VOICE_TUNE, 13=TRIGGER_DRUM, 20-24=dedicated setters, 30=LOAD_GRAPH).

**Step 1: Create the worker script**

Create `apps/composeApp/src/wasmJsMain/resources/orpheus-dsp-worker.js`:

```javascript
/**
 * C++ DSP Worker — loads Emscripten WASM module and renders audio.
 *
 * Receives commands via postMessage from DspWorkerProxy (main thread).
 * Sends pre-rendered audio buffers to AudioWorklet via transferred MessagePort.
 *
 * Command protocol matches DspWorkerProtocol.kt:
 *   0=INIT, 1=START, 2=STOP, 10=SET_PORT, 11=VOICE_GATE, 12=VOICE_TUNE,
 *   13=TRIGGER_DRUM, 20=MASTER_VOL, 21=DRIVE, 22=DELAY_MIX, 23=VIBRATO,
 *   24=BEND, 30=LOAD_GRAPH
 */

let Module = null;       // Emscripten module instance
let engine = null;       // OrpheusEngine* pointer
let outputPtr = null;    // WASM heap pointer for interleaved output buffer
let workletPort = null;  // MessagePort to AudioWorklet
let sampleRate = 48000;
let renderInterval = null;
let queueDepth = 0;

const RENDER_QUANTUM = 128;
const TARGET_QUEUE = 16;
const MAX_QUEUE = 32;

// ── Load Emscripten WASM module ──────────────────────────────────
importScripts('orpheus_dsp.js');

OrpheusDSP().then(function(mod) {
    Module = mod;
    console.log('[DSP-Worker] WASM module loaded');
    postMessage({ type: 'ready' });
});

// ── Command dispatcher ──────────────────────────────────────────
onmessage = function(e) {
    const data = e.data;
    const cmd = data.cmd;

    switch (cmd) {
        case 0: // CMD_INIT
            workletPort = data.workletPort;
            sampleRate = data.sampleRate || 48000;
            workletPort.onmessage = function(ev) {
                if (ev.data.type === 'queueDepth') {
                    queueDepth = ev.data.depth;
                }
            };
            // Create engine
            if (Module && !engine) {
                engine = Module._wasm_engine_create(sampleRate);
                outputPtr = Module._wasm_alloc_output(RENDER_QUANTUM);
                console.log('[DSP-Worker] Engine created, sr=' + sampleRate);
            }
            break;

        case 1: // CMD_START
            if (!renderInterval && engine) {
                renderInterval = setInterval(renderTick, 10);
                console.log('[DSP-Worker] Render loop started');
            }
            break;

        case 2: // CMD_STOP
            if (renderInterval) {
                clearInterval(renderInterval);
                renderInterval = null;
            }
            break;

        case 10: // CMD_SET_PORT
            if (engine) {
                Module.ccall('wasm_engine_set_port', null,
                    ['number', 'string', 'string', 'number'],
                    [engine, data.uri, data.sym, data.val]);
            }
            break;

        case 11: // CMD_VOICE_GATE
            if (engine) {
                Module._wasm_engine_set_voice_gate(engine, data.idx, data.gate ? 1 : 0);
            }
            break;

        case 12: // CMD_VOICE_TUNE
            if (engine) {
                Module._wasm_engine_set_voice_tune(engine, data.idx, data.val);
            }
            break;

        case 13: // CMD_TRIGGER_DRUM
            if (engine) {
                Module._wasm_engine_trigger_drum(engine, data.idx, data.accent);
            }
            break;

        case 20: // CMD_SET_MASTER_VOLUME
            if (engine) Module._wasm_engine_set_master_volume(engine, data.val);
            break;

        case 21: // CMD_SET_DRIVE
            if (engine) Module._wasm_engine_set_drive(engine, data.val);
            break;

        case 22: // CMD_SET_DELAY_MIX
            if (engine) Module._wasm_engine_set_delay_mix(engine, data.val);
            break;

        case 23: // CMD_SET_VIBRATO
            if (engine) Module._wasm_engine_set_vibrato(engine, data.val);
            break;

        case 24: // CMD_SET_BEND
            // bend is not yet implemented in C++
            break;

        case 30: // CMD_LOAD_GRAPH
            if (engine && data.graph) {
                var bytes = new Uint8Array(data.graph);
                var ptr = Module._malloc(bytes.length);
                Module.HEAPU8.set(bytes, ptr);
                var result = Module._wasm_engine_load_graph(engine, ptr, bytes.length);
                Module._free(ptr);
                console.log('[DSP-Worker] Graph loaded, result=' + result);
            }
            break;
    }
};

// ── Render loop ─────────────────────────────────────────────────
function renderTick() {
    if (!engine || !workletPort) return;

    var buffersNeeded = Math.min(TARGET_QUEUE - queueDepth, MAX_QUEUE);
    if (buffersNeeded <= 0) return;

    var t0 = performance.now();
    for (var i = 0; i < buffersNeeded; i++) {
        Module._wasm_engine_process(engine, outputPtr, RENDER_QUANTUM);

        // Read interleaved output from WASM heap and de-interleave
        var heapOffset = outputPtr / 4;  // float32 index
        var left = new Float32Array(RENDER_QUANTUM);
        var right = new Float32Array(RENDER_QUANTUM);
        for (var j = 0; j < RENDER_QUANTUM; j++) {
            left[j] = Module.HEAPF32[heapOffset + j * 2];
            right[j] = Module.HEAPF32[heapOffset + j * 2 + 1];
        }
        workletPort.postMessage(
            { type: 'buffer', left: left, right: right },
            [left.buffer, right.buffer]
        );
    }

    // Report CPU load
    var tickMs = performance.now() - t0;
    var budgetMs = (buffersNeeded * RENDER_QUANTUM / sampleRate) * 1000;
    if (budgetMs > 0) {
        postMessage({ type: 'monitor', cpuLoad: tickMs / budgetMs });
    }
}
```

**Step 2: Commit**

```bash
git add apps/composeApp/src/wasmJsMain/resources/orpheus-dsp-worker.js
git commit -m "feat(wasm): Add C++ DSP worker script with command dispatch and render loop"
```

---

## Task 3: Wire the main thread to the C++ DSP Worker

**Files:**
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/DspWorkerProtocol.kt`
- Modify: `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/core/audio/dsp/DspWorkerProxy.kt`
- Modify: `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/main.wasmJs.kt`

**Context:** The main thread needs to: (a) create the Worker pointing at the new JS file instead of `dsp-worker-entry.js`, (b) add a `CMD_LOAD_GRAPH` command, (c) send the ODWG graph bytes after init.

**Step 1: Add CMD_LOAD_GRAPH to the protocol**

In `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/DspWorkerProtocol.kt`, add:

```kotlin
const val CMD_LOAD_GRAPH = 30
```

**Step 2: Update DspWorkerProxy to use new worker URL**

In `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/core/audio/dsp/DspWorkerProxy.kt`:

Change the worker URL reference. Add a JS bridge function for sending the graph:

```kotlin
/** Send LOAD_GRAPH command with ODWG binary */
fun jsSendLoadGraphCmd(bytes: ByteArray): Unit =
    js("(function(){ var a = new Uint8Array(bytes); globalThis.__dspWorker.postMessage({ cmd: 30, graph: a.buffer }, [a.buffer]) })()")
```

**Step 3: Update main.wasmJs.kt**

Change `jsCreateDspWorker("dsp-worker-entry.js")` to `jsCreateDspWorker("orpheus-dsp-worker.js")`.

After `workerProxy = DspWorkerProxy()`, add graph sending after worker is ready. The ODWG bytes come from `buildDefaultWiringGraph()`:

```kotlin
// Send ODWG graph to the C++ worker after init
// The worker will call orpheus_engine_load_graph() with these bytes
val graphBytes = buildDefaultWiringGraph()
```

Then in the `start()` flow (after `jsTransferWorkletPortToWorker`), send the graph before starting the render loop. The simplest approach: add the graph send to `DspWorkerProxy.start()` or do it right after `jsSendWorkerCmd(CMD_START)`.

**Step 4: Build and test**

```bash
./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun
```

Open `http://localhost:8080/` in browser. Check console for:
- `[DSP-Worker] WASM module loaded`
- `[DSP-Worker] Engine created, sr=48000`
- `[DSP-Worker] Graph loaded, result=N` (where N is the number of units)

**Step 5: Commit**

```bash
git add core/foundation/ apps/composeApp/
git commit -m "feat(wasm): Wire main thread to C++ DSP worker, send ODWG graph on init"
```

---

## Task 4: Copy WASM build artifacts to app resources

**Files:**
- Modify: `apps/composeApp/build.gradle.kts`

**Context:** The Emscripten build produces `orpheus_dsp.js` and `orpheus_dsp.wasm` in `liborpheus_dsp/platform/wasm/build/`. These need to be served alongside the app. Replace the `copyDspWorker` task that copied the Kotlin worker output.

**Step 1: Replace the copyDspWorker Gradle task**

In `apps/composeApp/build.gradle.kts`, replace lines 207-219:

```kotlin
// Copy Emscripten WASM DSP module to app resources for serving alongside the app.
// The worker script (orpheus-dsp-worker.js) loads orpheus_dsp.js which loads orpheus_dsp.wasm.
val copyWasmDsp by tasks.registering(Copy::class) {
    from("${rootProject.projectDir}/liborpheus_dsp/platform/wasm/build") {
        include("orpheus_dsp.js", "orpheus_dsp.wasm")
    }
    into(layout.buildDirectory.dir("processedResources/wasmJs/main"))
}

tasks.named("wasmJsProcessResources") {
    dependsOn(copyWasmDsp)
}
```

**Step 2: Build and verify files are served**

```bash
./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun
```

Check browser network tab: `orpheus_dsp.js` and `orpheus_dsp.wasm` should load.

**Step 3: Commit**

```bash
git add apps/composeApp/build.gradle.kts
git commit -m "build(wasm): Replace Kotlin worker copy task with Emscripten WASM copy"
```

---

## Task 5: Delete the Kotlin DSP Worker module

**Files:**
- Delete: `apps/dspWorker/` (entire directory)
- Modify: `settings.gradle.kts` (remove `include(":apps:dspWorker")`)

**Context:** The Kotlin DSP worker module is no longer needed. The `orpheus-dsp-worker.js` script replaces all of its functionality. Remove the module and its Gradle include.

**Step 1: Remove from settings.gradle.kts**

Remove the line: `include(":apps:dspWorker")`

**Step 2: Delete the module directory**

```bash
rm -rf apps/dspWorker
```

**Step 3: Build to verify no compilation errors**

```bash
./gradlew :apps:composeApp:compileKotlinWasmJs
```

Expected: BUILD SUCCESSFUL (no references to dspWorker remain)

**Step 4: Commit**

```bash
git add -A
git commit -m "chore(wasm): Remove Kotlin DSP worker module (replaced by C++ WASM)"
```

---

## Task 6: Forward voice lifecycle commands from DspSynthEngine

**Files:**
- Modify: `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/main.wasmJs.kt`
- Modify: `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/core/audio/dsp/DspWorkerProxy.kt`

**Context:** Currently only `setPluginPort` is forwarded to the worker via the `overrideDelegates` setter. But the C++ engine also needs voice lifecycle commands: `setVoiceGate`, `setVoiceTune`, `triggerDrum`, `setMasterVolume`, `setDrive`, `setDelayMix`, `setVibrato`. These were previously handled by the Kotlin worker's `CommandDispatcher`. Now we need to intercept them in `main.wasmJs.kt` and forward via `DspWorkerProxy`.

The key methods to intercept on `SynthEngine` (which is `DspSynthEngine`):
- `setVoiceGate(index, active)` — voice on/off
- `setVoiceTune(index, tune)` — voice pitch (MIDI note)
- `triggerDrum(type, accent)` — drum trigger
- `setMasterVolume(v)` — master volume
- `setDrive(v)` — distortion drive
- `setDelayMix(v)` — delay wet/dry
- `setVibrato(v)` — vibrato depth

Look at how `DspSynthEngine` calls `nativeBridge?.nativeSetVoiceGate()` etc. in its methods. In the WASM path, these native bridge calls are null (no JNI on WASM), so we need the proxy forwarding.

**Step 1: Add JS bridge functions for missing commands**

These already exist in `DspWorkerProxy.kt`: `jsSendVoiceGateCmd`, `jsSendVoiceTuneCmd`, `jsSendTriggerDrumCmd`, `jsSendFloatCmd`. They just need to be called from the right places.

**Step 2: Wire forwarding in main.wasmJs.kt**

After the `overrideDelegates` call, also wrap the engine methods that need forwarding. The approach depends on how `DspSynthEngine` is structured — if it has overridable callbacks or if we need to use a different interception mechanism.

Examine `DspSynthEngine.setVoiceGate()`, `setMasterVolume()`, etc. to determine the best interception point. The `nativeBridge` is already null on WASM, so the simplest approach may be to implement a `NativeDspBridge` for WASM that sends postMessage commands instead of JNI calls.

**Step 3: Build and test**

```bash
./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun
```

Open browser, play notes. Voices should trigger and produce sound.

**Step 4: Commit**

```bash
git add apps/composeApp/
git commit -m "feat(wasm): Forward voice lifecycle and parameter commands to C++ worker"
```

---

## Task 7: Sync startup state to C++ Worker

**Files:**
- Modify: `apps/composeApp/src/wasmJsMain/kotlin/org/balch/orpheus/main.wasmJs.kt`

**Context:** When the app starts, `DspSynthEngine.syncNativeBridgeState()` pushes engine indices, voice tunes, mod sources, and quad volumes to the C++ engine. On JVM desktop, this happens via `nativeBridge`. On WASM, we need to replicate this by sending the same SET_PORT, VOICE_TUNE, etc. commands to the worker after the graph is loaded.

The `syncNativeBridgeState()` method already exists and calls `nativeBridge` methods. On WASM, we need to either:
1. Implement a WASM-specific `NativeDspBridge` that sends postMessage commands, or
2. Call the proxy methods directly after start.

**Step 1: Examine the NativeDspBridge interface**

Check how `NativeDspBridge` is defined and used. If it's an interface, implement it for WASM.

**Step 2: Implement and test**

After the worker starts and graph is loaded, the startup state sync should happen automatically via the same code path as desktop.

**Step 3: Play notes in browser, verify sound**

- Click to start audio
- Press keyboard keys to play notes
- Verify voices produce sound with correct pitch
- Verify mod source and volume settings are applied

**Step 4: Commit**

```bash
git add apps/composeApp/ core/
git commit -m "feat(wasm): Sync startup state to C++ DSP worker"
```

---

## Task 8: End-to-end verification

**Files:** None (testing only)

**Context:** Full manual test of the WASM C++ DSP path.

**Step 1: Build everything**

```bash
# Build WASM module
source ~/emsdk/emsdk_env.sh
cd liborpheus_dsp/platform/wasm/build
emcmake cmake .. -DEURORACK_DIR=/Users/balch/Source/eurorack
emmake make -j8

# Build and run app
cd /path/to/project
./gradlew :apps:composeApp:wasmJsBrowserDevelopmentRun
```

**Step 2: Test checklist**

Open `http://localhost:8080/` in Chrome/Firefox:

- [ ] Console shows `[DSP-Worker] WASM module loaded`
- [ ] Click to start → console shows `Engine created` and `Graph loaded`
- [ ] Press keyboard keys → hear synth voices
- [ ] Change engine type → different timbres
- [ ] Adjust timbre/morph knobs → timbral changes
- [ ] Toggle mod source to LFO → hear modulation
- [ ] Adjust mod source level → smooth change (no crackling)
- [ ] LFO OFF mode → silence (no modulation)
- [ ] Trigger drums → hear drum sounds
- [ ] Adjust delay/reverb → hear effects
- [ ] CPU load indicator shows reasonable value
- [ ] No audio underruns during normal use

**Step 3: Compare with desktop C++ engine**

Launch desktop app with `./gradlew :apps:composeApp:run -Dorpheus.engine=cpp` and compare:
- Same timbres at same engine/timbre/morph settings
- Same modulation behavior
- Same effects processing

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat(wasm): C++ DSP engine running in browser via Emscripten"
```
