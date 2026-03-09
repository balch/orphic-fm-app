@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_BEND
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_DELAY_MIX
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_DRIVE
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_MASTER_VOLUME
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_VIBRATO
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_START
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_STOP

/**
 * Main-thread bridge to the DSP Web Worker.
 *
 * Creates AudioContext and AudioWorkletNode (browser requirement: must be on main thread),
 * then transfers the worklet's MessagePort to the Worker so it can send audio buffers
 * directly to the AudioWorklet without main-thread involvement.
 *
 * This is a helper class for the Worker path — not a full AudioEngine replacement
 * (that requires Task 9). It provides methods to send commands to the Worker.
 */
class DspWorkerProxy {
    private var audioContext: AudioContext? = null
    private var workletNode: AudioWorkletNode? = null
    private var _isReady = false

    val isReady: Boolean get() = _isReady

    /**
     * Initialize the AudioContext, AudioWorklet, and transfer the worklet port
     * to the Worker. Must be called from a user gesture handler (click/keydown/touchstart)
     * to satisfy browser autoplay restrictions.
     */
    fun start(graphBytes: ByteArray? = null) {
        if (audioContext != null) return // Already started

        val ctx = createAudioContext()
        audioContext = ctx
        ctx.resume() // Must explicitly resume — may be suspended even after user gesture
        dspSampleRate = ctx.sampleRate

        val promise = jsAddWorkletModule(ctx, "dsp-output-processor.js")
        jsPromiseThen(promise) {
            val node = jsCreateWorkletNode(ctx, "dsp-output-processor")
            node.connect(ctx.destination)
            workletNode = node

            // Transfer the worklet's MessagePort to the Worker via CMD_INIT.
            // After this, the Worker can send audio buffers directly to the worklet.
            jsTransferWorkletPortToWorker(node, ctx.sampleRate)

            // Tell the Worker to start its render loop
            jsSendWorkerCmd(CMD_START)

            // Send graph after engine is created (CMD_INIT creates it)
            if (graphBytes != null) {
                jsSendLoadGraphCmd(graphBytes)
            }

            _isReady = true
        }
    }

    fun stop() {
        jsSendWorkerCmd(CMD_STOP)
        workletNode?.disconnect()
        workletNode = null
        audioContext?.close()
        audioContext = null
        _isReady = false
    }

    /** Send a SET_PORT command to the Worker */
    fun setPort(uri: String, symbol: String, value: Float) {
        jsSendSetPortCmd(uri, symbol, value)
    }

    /** Send a VOICE_GATE command to the Worker */
    fun setVoiceGate(index: Int, active: Boolean) {
        jsSendVoiceGateCmd(index, active)
    }

    /** Send a VOICE_TUNE command to the Worker */
    fun setVoiceTune(index: Int, tune: Float) {
        jsSendVoiceTuneCmd(index, tune)
    }

    /** Send a TRIGGER_DRUM command to the Worker */
    fun triggerDrum(drumIndex: Int, accent: Float) {
        jsSendTriggerDrumCmd(drumIndex, accent)
    }

    /** Send a SET_MASTER_VOLUME command to the Worker */
    fun setMasterVolume(value: Float) {
        jsSendFloatCmd(CMD_SET_MASTER_VOLUME, value)
    }

    /** Send a SET_DRIVE command to the Worker */
    fun setDrive(value: Float) {
        jsSendFloatCmd(CMD_SET_DRIVE, value)
    }

    /** Send a SET_DELAY_MIX command to the Worker */
    fun setDelayMix(value: Float) {
        jsSendFloatCmd(CMD_SET_DELAY_MIX, value)
    }

    /** Send a SET_VIBRATO command to the Worker */
    fun setVibrato(value: Float) {
        jsSendFloatCmd(CMD_SET_VIBRATO, value)
    }

    /** Send a SET_BEND command to the Worker */
    fun setBend(value: Float) {
        jsSendFloatCmd(CMD_SET_BEND, value)
    }

    /** Read the Worker's reported CPU load */
    fun getCpuLoad(): Float = jsGetProxyCpuLoad()

    val sampleRate: Int get() = audioContext?.sampleRate?.toInt() ?: 48000
    val currentTime: Double get() = audioContext?.currentTime ?: 0.0

    // Command IDs sourced from DspWorkerProtocol in core:foundation
}

// ─── JS Bridge Functions for Worker Communication ────────────────────────────

/** Create the Web Worker from the entry JS file */
fun jsCreateDspWorker(workerUrl: String): Unit =
    js("globalThis.__dspWorker = new Worker(workerUrl)")

/** Set up listener for Worker messages (ready, monitor data) */
fun jsSetupWorkerListener(): Unit =
    js("globalThis.__dspWorker.onmessage = function(e) { if (e.data.type === 'ready') globalThis.__dspWorkerReady = true; if (e.data.type === 'monitor') globalThis.__workerCpuLoad = e.data.cpuLoad || 0 }")

/** Check if Worker has posted 'ready' */
fun jsIsWorkerReady(): Boolean =
    js("globalThis.__dspWorkerReady === true")

/** Transfer AudioWorklet's MessagePort to the Worker with CMD_INIT */
fun jsTransferWorkletPortToWorker(node: AudioWorkletNode, sampleRate: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 0, workletPort: node.port, sampleRate: sampleRate }, [node.port])")

/** Send a simple command (no extra data) */
fun jsSendWorkerCmd(cmd: Int): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: cmd })")

/** Send a command with a single float value */
fun jsSendFloatCmd(cmd: Int, value: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: cmd, val: value })")

/** Send SET_PORT command */
fun jsSendSetPortCmd(uri: String, sym: String, value: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 10, uri: uri, sym: sym, val: value })")

/** Send VOICE_GATE command */
fun jsSendVoiceGateCmd(index: Int, gate: Boolean): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 11, idx: index, gate: gate })")

/** Send VOICE_TUNE command */
fun jsSendVoiceTuneCmd(index: Int, tune: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 12, idx: index, val: tune })")

/** Send TRIGGER_DRUM command */
fun jsSendTriggerDrumCmd(drumIndex: Int, accent: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 13, idx: drumIndex, accent: accent })")

/** Send LOAD_GRAPH command with ODWG binary as ArrayBuffer Transferable */
fun jsSendLoadGraphCmd(bytes: ByteArray): Unit =
    js("(function(){ var a = new Uint8Array(bytes); globalThis.__dspWorker.postMessage({ cmd: 30, graph: a.buffer }, [a.buffer]) })()")

/** Read Worker's CPU load (set by listener) */
fun jsGetProxyCpuLoad(): Float =
    js("globalThis.__workerCpuLoad || 0")
