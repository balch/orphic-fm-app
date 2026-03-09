/**
 * orpheus-dsp-worker.js
 *
 * Web Worker that hosts the Emscripten-compiled C++ DSP engine.
 * Receives commands from the main thread (matching DspWorkerProtocol.kt),
 * renders audio into WASM memory, and posts interleaved L/R buffers
 * to the AudioWorklet via a transferred MessagePort.
 */

/* ── Constants ── */
const RENDER_QUANTUM = 128;
const TARGET_QUEUE   = 16;
const MAX_QUEUE      = 32;
const RENDER_INTERVAL_MS = 10;

/* ── Command IDs (must match DspWorkerProtocol.kt) ── */
const CMD_INIT              = 0;
const CMD_START             = 1;
const CMD_STOP              = 2;
const CMD_SET_PORT          = 10;
const CMD_VOICE_GATE        = 11;
const CMD_VOICE_TUNE        = 12;
const CMD_TRIGGER_DRUM      = 13;
const CMD_SET_MASTER_VOLUME = 20;
const CMD_SET_DRIVE         = 21;
const CMD_SET_DELAY_MIX     = 22;
const CMD_SET_VIBRATO       = 23;
const CMD_SET_BEND          = 24;
const CMD_LOAD_GRAPH        = 30;

/* ── State ── */
let Module       = null;   // Emscripten module instance
let engine       = 0;      // Pointer returned by _wasm_engine_create
let outputPtr    = 0;      // WASM heap pointer for interleaved output
let workletPort  = null;   // MessagePort to AudioWorklet
let renderTimer  = null;   // setInterval handle
let queueDepth   = 0;      // Latest reported queue depth from worklet
let running      = false;

/* ── Load Emscripten module ── */
importScripts('orpheus_dsp.js');

OrpheusDSP().then(mod => {
    Module = mod;
    console.log('[DSP-Worker] WASM module loaded');
    postMessage({ type: 'ready' });
});

/* ── Render loop ── */
function startRenderLoop() {
    if (renderTimer !== null) return;
    running = true;
    renderTimer = setInterval(renderTick, RENDER_INTERVAL_MS);
    console.log('[DSP-Worker] Render loop started');
}

function stopRenderLoop() {
    running = false;
    if (renderTimer !== null) {
        clearInterval(renderTimer);
        renderTimer = null;
    }
    console.log('[DSP-Worker] Render loop stopped');
}

function renderTick() {
    if (!engine || !workletPort || !running) return;

    const buffersNeeded = Math.min(TARGET_QUEUE - queueDepth, MAX_QUEUE);
    if (buffersNeeded <= 0) return;

    const t0 = performance.now();

    for (let i = 0; i < buffersNeeded; i++) {
        // Render one quantum of interleaved stereo audio into WASM heap
        Module._wasm_engine_process(engine, outputPtr, RENDER_QUANTUM);

        // De-interleave from HEAPF32 into separate L/R arrays
        // outputPtr is a byte offset; HEAPF32 is indexed by float (4 bytes)
        const floatOffset = outputPtr >> 2;
        const left  = new Float32Array(RENDER_QUANTUM);
        const right = new Float32Array(RENDER_QUANTUM);

        for (let s = 0; s < RENDER_QUANTUM; s++) {
            left[s]  = Module.HEAPF32[floatOffset + s * 2];
            right[s] = Module.HEAPF32[floatOffset + s * 2 + 1];
        }

        // Transfer to worklet (zero-copy)
        workletPort.postMessage(
            { type: 'buffer', left, right },
            [left.buffer, right.buffer]
        );
    }

    const elapsed = performance.now() - t0;
    // CPU load: time spent rendering vs. real-time equivalent of buffers produced
    const realTimeDuration = (buffersNeeded * RENDER_QUANTUM / 48000) * 1000;
    if (realTimeDuration > 0) {
        postMessage({ type: 'monitor', cpuLoad: elapsed / realTimeDuration });
    }
}

/* ── Message handler ── */
onmessage = function(e) {
    const msg = e.data;

    switch (msg.cmd) {
        case CMD_INIT: {
            workletPort = msg.workletPort;
            const sampleRate = msg.sampleRate || 48000;

            // Listen for queue depth feedback from worklet
            workletPort.onmessage = (evt) => {
                if (evt.data.type === 'queueDepth') {
                    queueDepth = evt.data.depth;
                }
            };

            if (!Module) {
                console.error('[DSP-Worker] CMD_INIT received before module loaded');
                return;
            }

            // Create engine and allocate output buffer
            engine = Module._wasm_engine_create(sampleRate);
            outputPtr = Module._wasm_alloc_output(RENDER_QUANTUM);
            console.log('[DSP-Worker] Engine created, sampleRate=' + sampleRate +
                        ', engine=' + engine + ', outputPtr=' + outputPtr);
            break;
        }

        case CMD_START:
            startRenderLoop();
            break;

        case CMD_STOP:
            stopRenderLoop();
            break;

        case CMD_SET_PORT:
            if (engine && Module) {
                Module.ccall(
                    'wasm_engine_set_port', null,
                    ['number', 'string', 'string', 'number'],
                    [engine, msg.uri, msg.sym, msg.val]
                );
            }
            break;

        case CMD_VOICE_GATE:
            if (engine && Module) {
                Module._wasm_engine_voice_gate(engine, msg.idx, msg.gate ? 1 : 0);
            }
            break;

        case CMD_VOICE_TUNE:
            if (engine && Module) {
                Module._wasm_engine_voice_tune(engine, msg.idx, msg.val);
            }
            break;

        case CMD_TRIGGER_DRUM:
            if (engine && Module) {
                Module._wasm_engine_trigger_drum(engine, msg.idx, msg.accent);
            }
            break;

        case CMD_SET_MASTER_VOLUME:
            if (engine && Module) {
                Module._wasm_engine_set_master_volume(engine, msg.val);
            }
            break;

        case CMD_SET_DRIVE:
            if (engine && Module) {
                Module._wasm_engine_set_drive(engine, msg.val);
            }
            break;

        case CMD_SET_DELAY_MIX:
            if (engine && Module) {
                Module._wasm_engine_set_delay_mix(engine, msg.val);
            }
            break;

        case CMD_SET_VIBRATO:
            if (engine && Module) {
                Module._wasm_engine_set_vibrato(engine, msg.val);
            }
            break;

        case CMD_SET_BEND:
            if (engine && Module) {
                Module._wasm_engine_set_bend(engine, msg.val);
            }
            break;

        case CMD_LOAD_GRAPH: {
            if (!engine || !Module) {
                console.error('[DSP-Worker] CMD_LOAD_GRAPH: engine not ready');
                return;
            }
            const graphData = new Uint8Array(msg.graph);
            const len = graphData.length;
            const ptr = Module._malloc(len);
            if (!ptr) {
                console.error('[DSP-Worker] CMD_LOAD_GRAPH: malloc failed for ' + len + ' bytes');
                return;
            }
            Module.HEAPU8.set(graphData, ptr);
            Module._wasm_engine_load_graph(engine, ptr, len);
            Module._free(ptr);
            console.log('[DSP-Worker] Graph loaded, ' + len + ' bytes');
            break;
        }

        default:
            console.log('[DSP-Worker] Unknown command: ' + msg.cmd);
            break;
    }
};
