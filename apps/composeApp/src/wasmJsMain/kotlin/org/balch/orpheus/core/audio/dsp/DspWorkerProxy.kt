@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_BEND
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_DELAY_MIX
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_DRIVE
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_MASTER_VOLUME
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_VIBRATO
import org.balch.orpheus.core.audio.DspWorkerProtocol.CMD_SET_VIBRATO_RATE
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

    /** Send a SET_VIBRATO_RATE command to the Worker */
    fun setVibratoRate(hz: Float) {
        jsSendFloatCmd(CMD_SET_VIBRATO_RATE, hz)
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

/** Send VOICE_ENGINE command */
fun jsSendVoiceEngineCmd(index: Int, engineIndex: Int): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 14, idx: index, val: engineIndex })")

/** Send VOICE_ACTIVE command */
fun jsSendVoiceActiveCmd(index: Int, active: Boolean): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 15, idx: index, val: active ? 1 : 0 })")

/** Send VOICE_HOLD command */
fun jsSendVoiceHoldCmd(index: Int, level: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 16, idx: index, val: level })")

/** Send VOICE_HARMONICS command */
fun jsSendVoiceHarmonicsCmd(index: Int, value: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 17, idx: index, val: value })")

/** Send VOICE_TIMBRE command */
fun jsSendVoiceTimbreCmd(index: Int, value: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 18, idx: index, val: value })")

/** Send VOICE_MORPH command */
fun jsSendVoiceMorphCmd(index: Int, value: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 19, idx: index, val: value })")

/** Send VOICE_DECAY command */
fun jsSendVoiceDecayCmd(index: Int, value: Float): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 25, idx: index, val: value })")

/** Allocate a JS Uint8Array of given size */
private fun jsNewUint8Array(size: Int): JsAny =
    js("new Uint8Array(size)")

/** Set a byte in a Uint8Array */
private fun jsSetByte(arr: JsAny, index: Int, value: Int): Unit =
    js("arr[index] = value")

/** Post the Uint8Array's buffer as CMD_LOAD_GRAPH */
private fun jsPostGraphCmd(arr: JsAny): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 30, graph: arr.buffer }, [arr.buffer])")

/** Send LOAD_GRAPH command with ODWG binary as ArrayBuffer Transferable */
fun jsSendLoadGraphCmd(bytes: ByteArray) {
    val arr = jsNewUint8Array(bytes.size)
    for (i in bytes.indices) {
        jsSetByte(arr, i, bytes[i].toInt() and 0xFF)
    }
    jsPostGraphCmd(arr)
}

/** Read Worker's CPU load (set by listener) */
fun jsGetProxyCpuLoad(): Float =
    js("globalThis.__workerCpuLoad || 0")

// ─── Automation ──────────────────────────────────────────────────────────────

/** Allocate a JS Float32Array of given size */
private fun jsNewFloat32ArraySized(size: Int): JsAny =
    js("new Float32Array(size)")

/** Set a float in a Float32Array */
private fun jsSetFloat(arr: JsAny, index: Int, value: Float): Unit =
    js("arr[index] = value")

/** Post CMD_SET_AUTOMATION with times/values as Transferable Float32Arrays */
private fun jsPostAutomationCmd(target: Int, voiceIndex: Int, timesArr: JsAny, valuesArr: JsAny, count: Int): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 31, target: target, voiceIdx: voiceIndex, times: timesArr.buffer, values: valuesArr.buffer, count: count }, [timesArr.buffer, valuesArr.buffer])")

/** Send SET_AUTOMATION command with times/values arrays as Transferable buffers */
fun jsSendSetAutomationCmd(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int) {
    val timesArr = jsNewFloat32ArraySized(count)
    val valuesArr = jsNewFloat32ArraySized(count)
    for (i in 0 until count) {
        jsSetFloat(timesArr, i, times[i])
        jsSetFloat(valuesArr, i, values[i])
    }
    jsPostAutomationCmd(target, voiceIndex, timesArr, valuesArr, count)
}

/** Send CLEAR_AUTOMATION command */
fun jsSendClearAutomationCmd(target: Int, voiceIndex: Int): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 32, target: target, voiceIdx: voiceIndex })")

// ─── TTS ─────────────────────────────────────────────────────────────────────

/** Post CMD_LOAD_TTS_AUDIO with samples as Transferable Float32Array */
private fun jsPostLoadTtsCmd(samplesArr: JsAny, sampleRate: Int): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 40, samples: samplesArr.buffer, sampleRate: sampleRate }, [samplesArr.buffer])")

/** Send LOAD_TTS_AUDIO command with audio samples as Transferable buffer */
fun jsSendLoadTtsAudioCmd(samples: FloatArray, sampleRate: Int) {
    val arr = jsNewFloat32ArraySized(samples.size)
    for (i in samples.indices) {
        jsSetFloat(arr, i, samples[i])
    }
    jsPostLoadTtsCmd(arr, sampleRate)
}

/** Send PLAY_TTS command */
fun jsSendPlayTtsCmd(): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 41 })")

/** Send STOP_TTS command */
fun jsSendStopTtsCmd(): Unit =
    js("globalThis.__dspWorker.postMessage({ cmd: 42 })")

/** Check TTS playing state (polled from Worker via globalThis) */
fun jsGetTtsPlaying(): Int =
    js("globalThis.__ttsPlaying || 0")
