@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.worker

import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.dspSampleRate
import org.balch.orpheus.core.plugin.PortValue

/**
 * Routes incoming postMessage commands from the main thread
 * to the appropriate [SynthEngine] methods.
 *
 * Command protocol:
 * ```
 * worker.postMessage({ cmd: <int>, ...params })
 * ```
 *
 * Command IDs are defined in the companion object and must stay
 * in sync with the main-thread proxy (WorkerSynthProxy).
 */
class CommandDispatcher(private val engine: SynthEngine) {

    fun dispatch(cmd: Int, data: JsAny) {
        when (cmd) {
            CMD_INIT -> handleInit(data)
            CMD_START -> engine.start()
            CMD_STOP -> engine.stop()
            CMD_SET_PORT -> handleSetPort(data)
            CMD_VOICE_GATE -> handleVoiceGate(data)
            CMD_VOICE_TUNE -> handleVoiceTune(data)
            CMD_TRIGGER_DRUM -> handleTriggerDrum(data)
            CMD_SET_MASTER_VOLUME -> handleSetFloat(data) { engine.setMasterVolume(it) }
            CMD_SET_DRIVE -> handleSetFloat(data) { engine.setDrive(it) }
            CMD_SET_DELAY_MIX -> handleSetFloat(data) { engine.setDelayMix(it) }
            CMD_SET_VIBRATO -> handleSetFloat(data) { engine.setVibrato(it) }
            CMD_SET_BEND -> handleSetFloat(data) { engine.setBend(it) }
            else -> { /* unknown command */ }
        }
    }

    private fun handleInit(data: JsAny) {
        val sampleRate = jsGetCmdFloat(data, "sampleRate")
        dspSampleRate = sampleRate
        jsStoreWorkletPort(data)
        jsSetupWorkletPortListener()
    }

    private fun handleSetPort(data: JsAny) {
        val uri = jsGetCmdString(data, "uri")
        val sym = jsGetCmdString(data, "sym")
        val value = jsGetCmdFloat(data, "val")
        engine.setPluginPort(uri, sym, PortValue.FloatValue(value))
    }

    private fun handleVoiceGate(data: JsAny) {
        val index = jsGetCmdInt(data, "idx")
        val gate = jsGetCmdBoolean(data, "gate")
        engine.setVoiceGate(index, gate)
    }

    private fun handleVoiceTune(data: JsAny) {
        val index = jsGetCmdInt(data, "idx")
        val tune = jsGetCmdFloat(data, "val")
        engine.setVoiceTune(index, tune)
    }

    private fun handleTriggerDrum(data: JsAny) {
        val drumIndex = jsGetCmdInt(data, "idx")
        val accent = jsGetCmdFloat(data, "accent")
        engine.triggerDrum(drumIndex, accent)
    }

    private inline fun handleSetFloat(data: JsAny, setter: (Float) -> Unit) {
        val value = jsGetCmdFloat(data, "val")
        setter(value)
    }

    companion object {
        const val CMD_INIT = 0
        const val CMD_START = 1
        const val CMD_STOP = 2
        const val CMD_SET_PORT = 10
        const val CMD_VOICE_GATE = 11
        const val CMD_VOICE_TUNE = 12
        const val CMD_TRIGGER_DRUM = 13
        const val CMD_SET_MASTER_VOLUME = 20
        const val CMD_SET_DRIVE = 21
        const val CMD_SET_DELAY_MIX = 22
        const val CMD_SET_VIBRATO = 23
        const val CMD_SET_BEND = 24
    }
}
