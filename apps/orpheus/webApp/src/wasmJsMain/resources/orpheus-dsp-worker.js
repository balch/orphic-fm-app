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
const CMD_VOICE_ENGINE      = 14;
const CMD_VOICE_ACTIVE      = 15;
const CMD_VOICE_HOLD        = 16;
const CMD_VOICE_HARMONICS   = 17;
const CMD_VOICE_TIMBRE      = 18;
const CMD_VOICE_MORPH       = 19;
const CMD_SET_MASTER_VOLUME = 20;
const CMD_SET_DRIVE         = 21;
const CMD_SET_DELAY_MIX     = 22;
const CMD_SET_VIBRATO       = 23;
const CMD_SET_BEND          = 24;
const CMD_VOICE_DECAY       = 25;
const CMD_SET_VIBRATO_RATE  = 26;
const CMD_LOAD_GRAPH        = 30;

/* ── State ── */
let Module       = null;   // Emscripten module instance
let engine       = 0;      // Pointer returned by _wasm_engine_create
let workletPort  = null;   // MessagePort to AudioWorklet
let renderTimer  = null;   // setInterval handle
let queueDepth   = 0;      // Latest reported queue depth from worklet
let running      = false;
let pendingMessages = [];   // Commands queued before WASM module loads

/* ── Load Emscripten module ── */
importScripts('orpheus_dsp.js');

OrpheusDSP().then(mod => {
    Module = mod;
    console.log('[DSP-Worker] WASM module loaded');
    postMessage({ type: 'ready' });

    // Replay any commands that arrived before the module was ready
    const pending = pendingMessages;
    pendingMessages = null; // Signal that module is ready
    for (const msg of pending) {
        handleCommand(msg);
    }
});

/** Get a fresh Float32Array view of WASM memory (safe after memory growth) */
function getHeapF32() {
    return new Float32Array(Module.wasmMemory.buffer);
}

/** Get a fresh Uint8Array view of WASM memory (safe after memory growth) */
function getHeapU8() {
    return new Uint8Array(Module.wasmMemory.buffer);
}

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
        // Render one quantum into the internal WASM output buffer
        Module._wasm_engine_process(engine, RENDER_QUANTUM);

        // Get the output pointer (stable across calls, allocated once)
        const outputPtr = Module._wasm_get_output_ptr();

        // Create fresh heap view each iteration (safe after memory growth)
        const heap = getHeapF32();
        const floatOffset = outputPtr >> 2;

        // De-interleave from WASM heap into separate L/R arrays
        const left  = new Float32Array(RENDER_QUANTUM);
        const right = new Float32Array(RENDER_QUANTUM);

        for (let s = 0; s < RENDER_QUANTUM; s++) {
            left[s]  = heap[floatOffset + s * 2];
            right[s] = heap[floatOffset + s * 2 + 1];
        }

        // Transfer to worklet (zero-copy)
        workletPort.postMessage(
            { type: 'buffer', left, right },
            [left.buffer, right.buffer]
        );
    }

    const elapsed = performance.now() - t0;
    // CPU load: time spent rendering vs. real-time equivalent of buffers produced
    const sampleRate = 48000; // C++ engine uses 48kHz
    const realTimeDuration = (buffersNeeded * RENDER_QUANTUM / sampleRate) * 1000;
    if (realTimeDuration > 0) {
        postMessage({ type: 'monitor', cpuLoad: elapsed / realTimeDuration });
    }
}

/* ── Message handler ── */
onmessage = function(e) {
    const msg = e.data;

    // Queue commands that arrive before WASM module is loaded
    if (pendingMessages !== null) {
        console.log('[DSP-Worker] Queuing cmd ' + msg.cmd + ' (module loading)');
        pendingMessages.push(msg);
        return;
    }

    handleCommand(msg);
};

function handleCommand(msg) {
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

            // Create engine
            engine = Module._wasm_engine_create(sampleRate);
            // Pre-allocate output buffer (stays allocated across process calls)
            Module._wasm_alloc_output(RENDER_QUANTUM);
            console.log('[DSP-Worker] Engine created, sampleRate=' + sampleRate +
                        ', engine=' + engine);
            break;
        }

        case CMD_START:
            startRenderLoop();
            break;

        case CMD_STOP:
            stopRenderLoop();
            break;

        case CMD_SET_PORT:
            if (engine) {
                Module.ccall(
                    'wasm_engine_set_port', null,
                    ['number', 'string', 'string', 'number'],
                    [engine, msg.uri, msg.sym, msg.val]
                );
            }
            break;

        case CMD_VOICE_GATE:
            if (engine) {
                Module._wasm_engine_set_voice_gate(engine, msg.idx, msg.gate ? 1 : 0);
            }
            break;

        case CMD_VOICE_TUNE:
            if (engine) {
                Module._wasm_engine_set_voice_tune(engine, msg.idx, msg.val);
            }
            break;

        case CMD_TRIGGER_DRUM:
            if (engine) {
                Module._wasm_engine_trigger_drum(engine, msg.idx, msg.accent);
            }
            break;

        case CMD_VOICE_ENGINE:
            if (engine) {
                Module._wasm_engine_set_voice_engine(engine, msg.idx, msg.val);
            }
            break;

        case CMD_VOICE_ACTIVE:
            if (engine) {
                Module._wasm_engine_set_voice_active(engine, msg.idx, msg.val);
            }
            break;

        case CMD_VOICE_HOLD:
            if (engine) {
                Module._wasm_engine_set_voice_hold(engine, msg.idx, msg.val);
            }
            break;

        case CMD_VOICE_HARMONICS:
            if (engine) {
                Module._wasm_engine_set_voice_harmonics(engine, msg.idx, msg.val);
            }
            break;

        case CMD_VOICE_TIMBRE:
            if (engine) {
                Module._wasm_engine_set_voice_timbre(engine, msg.idx, msg.val);
            }
            break;

        case CMD_VOICE_MORPH:
            if (engine) {
                Module._wasm_engine_set_voice_morph(engine, msg.idx, msg.val);
            }
            break;

        case CMD_VOICE_DECAY:
            if (engine) {
                Module._wasm_engine_set_voice_decay(engine, msg.idx, msg.val);
            }
            break;

        case CMD_SET_MASTER_VOLUME:
            if (engine) {
                Module._wasm_engine_set_master_volume(engine, msg.val);
            }
            break;

        case CMD_SET_DRIVE:
            if (engine) {
                Module._wasm_engine_set_drive(engine, msg.val);
            }
            break;

        case CMD_SET_DELAY_MIX:
            if (engine) {
                Module._wasm_engine_set_delay_mix(engine, msg.val);
            }
            break;

        case CMD_SET_VIBRATO:
            if (engine) {
                Module._wasm_engine_set_vibrato(engine, msg.val);
            }
            break;

        case CMD_SET_VIBRATO_RATE:
            if (engine) {
                Module._wasm_engine_set_vibrato_rate(engine, msg.val);
            }
            break;

        case CMD_SET_BEND:
            if (engine) {
                Module._wasm_engine_set_bend(engine, msg.val);
            }
            break;

        case CMD_LOAD_GRAPH: {
            if (!engine) {
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
            getHeapU8().set(graphData, ptr);
            Module._wasm_engine_load_patch(engine, ptr, len);
            Module._free(ptr);
            console.log('[DSP-Worker] Graph loaded, ' + len + ' bytes');
            break;
        }

        default:
            console.log('[DSP-Worker] Unknown command: ' + msg.cmd);
            break;
    }
}
