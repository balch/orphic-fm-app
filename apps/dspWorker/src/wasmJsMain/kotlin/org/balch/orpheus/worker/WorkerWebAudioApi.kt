@file:Suppress("NOTHING_TO_INLINE")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.worker

/**
 * Typed Array for WASM interop — Worker-local definition.
 * (The composeApp module has its own; dspWorker cannot depend on it.)
 */
external class Float32Array(length: Int) : JsAny {
    val length: Int
    @JsName("get")
    operator fun get(index: Int): Float
    @JsName("set")
    operator fun set(index: Int, value: Float)
}

// ─── Timer functions (available in Web Worker global scope) ────────────

fun jsWorkerSetInterval(ms: Int, callback: () -> Unit): Int =
    js("setInterval(callback, ms)")

fun jsWorkerClearInterval(id: Int): Unit =
    js("clearInterval(id)")

fun jsWorkerPerformanceNow(): Double =
    js("performance.now()")

// ─── Float32Array creation ─────────────────────────────────────────────

fun jsNewWorkerFloat32Array(length: Int): Float32Array =
    js("new Float32Array(length)")

// ─── Post audio to worklet via transferred MessagePort ─────────────────

/**
 * De-interleave a staging Float32Array [L0,R0,L1,R1,...] into separate
 * L/R buffers and post to the worklet port with Transferable (zero-copy).
 * The split loop runs in pure JS — no per-element WASM↔JS interop.
 */
fun jsSplitAndPostToWorklet(interleaved: Float32Array, frames: Int): Unit =
    js("(function(){var p=globalThis.__workletPort;if(!p)return;var l=new Float32Array(frames),r=new Float32Array(frames);for(var i=0;i<frames;i++){l[i]=interleaved[i*2];r[i]=interleaved[i*2+1]}p.postMessage({type:'buffer',left:l,right:r},[l.buffer,r.buffer])})()")

// ─── Queue depth from worklet port messages ────────────────────────────

fun jsGetWorkerQueueDepth(): Int =
    js("globalThis.__workerQueueDepth || 0")

fun jsGetWorkerUnderrunCount(): Int =
    js("globalThis.__workerUnderruns || 0")

// ─── Set up listener on the transferred worklet port ───────────────────

fun jsSetupWorkletPortListener(): Unit =
    js("globalThis.__workletPort.onmessage = function(e) { if (e.data.type === 'queueDepth') { globalThis.__workerQueueDepth = e.data.depth; globalThis.__workerUnderruns = e.data.underruns || 0 } }")

// ─── Store worklet port from init message ──────────────────────────────

fun jsStoreWorkletPort(data: JsAny): Unit =
    js("globalThis.__workletPort = data.workletPort")

// ─── Post messages to main thread ──────────────────────────────────────

fun jsPostReady(): Unit =
    js("self.postMessage({ type: 'ready' })")

fun jsPostMonitorData(cpuLoad: Float): Unit =
    js("self.postMessage({ type: 'monitor', cpuLoad: cpuLoad })")

// ─── Command field readers ─────────────────────────────────────────────

fun jsGetCmdInt(data: JsAny, field: String): Int =
    js("data[field] | 0")

fun jsGetCmdFloat(data: JsAny, field: String): Float =
    js("+(data[field] || 0)")

fun jsGetCmdString(data: JsAny, field: String): String =
    js("'' + (data[field] || '')")

fun jsGetCmdBoolean(data: JsAny, field: String): Boolean =
    js("!!data[field]")
