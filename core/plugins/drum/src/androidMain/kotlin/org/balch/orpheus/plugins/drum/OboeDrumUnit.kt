package org.balch.orpheus.plugins.drum

import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.DrumUnit
import org.balch.orpheus.core.audio.dsp.OboeAudioInput
import org.balch.orpheus.core.audio.dsp.OboeAudioOutput
import org.balch.orpheus.core.audio.dsp.OboeProcessable
import org.balch.orpheus.plugins.drum.engine.AnalogBassDrum
import org.balch.orpheus.plugins.drum.engine.AnalogSnareDrum
import org.balch.orpheus.plugins.drum.engine.FmDrum
import org.balch.orpheus.plugins.drum.engine.MetallicHiHat
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Oboe implementation of DrumUnit.
 *
 * Renders ALL drums simultaneously (not just the last triggered type).
 * This prevents clicks when rapidly triggering different drums.
 */
class OboeDrumUnit : DrumUnit, OboeProcessable {
    @Volatile override var enabled = true

    private val bd = AnalogBassDrum()
    private val sd = AnalogSnareDrum()
    private val hh = MetallicHiHat()
    private val fm = FmDrum(org.balch.orpheus.core.audio.dsp.DSP_SAMPLE_RATE)

    // Output port
    private val oboeOutput = OboeAudioOutput("Output")

    // Trigger input ports
    private val oboeTriggerBd = OboeAudioInput("TriggerBD")
    private val oboeTriggerSd = OboeAudioInput("TriggerSD")
    private val oboeTriggerHh = OboeAudioInput("TriggerHH")

    override val output: AudioOutput = oboeOutput
    override val triggerInputBd: AudioInput = oboeTriggerBd
    override val triggerInputSd: AudioInput = oboeTriggerSd
    override val triggerInputHh: AudioInput = oboeTriggerHh

    // Mode: 0 = 808, 1 = FM
    private var drumMode = 0

    // Per-drum trigger flags
    private var bdTrigger = false
    private var sdTrigger = false
    private var hhTrigger = false
    private var fmTrigger = false

    // Per-drum parameters
    // Bass Drum
    private var bdAccent = 0.5f
    private var bdF0 = 55.0f / org.balch.orpheus.core.audio.dsp.DSP_SAMPLE_RATE
    private var bdTone = 0.5f
    private var bdDecay = 0.5f
    private var bdP4 = 0.5f
    private var bdP5 = 0.5f

    // Snare Drum
    private var sdAccent = 0.5f
    private var sdF0 = 180.0f / org.balch.orpheus.core.audio.dsp.DSP_SAMPLE_RATE
    private var sdTone = 0.5f
    private var sdDecay = 0.5f
    private var sdP4 = 0.5f

    // Hi-Hat
    private var hhAccent = 0.5f
    private var hhF0 = 400.0f / org.balch.orpheus.core.audio.dsp.DSP_SAMPLE_RATE
    private var hhTone = 0.5f
    private var hhDecay = 0.5f
    private var hhP4 = 0.5f

    init {
        bd.init()
        sd.init()
        hh.init()
        fm.init()
    }

    override fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    ) {
        setParameters(type, frequency, tone, decay, param4, param5)
        trigger(type, accent)
    }

    override fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    ) {
        val f0 = frequency / org.balch.orpheus.core.audio.dsp.DSP_SAMPLE_RATE
        when (type) {
            0 -> { bdF0 = f0; bdTone = tone; bdDecay = decay; bdP4 = param4; bdP5 = param5 }
            1 -> { sdF0 = f0; sdTone = tone; sdDecay = decay; sdP4 = param4 }
            2 -> { hhF0 = f0; hhTone = tone; hhDecay = decay; hhP4 = param4 }
        }
    }

    override fun trigger(type: Int, accent: Float) {
        if (type >= 10 || drumMode == 1) {
            bdAccent = accent
            fmTrigger = true
            return
        }
        when (type) {
            0 -> { bdAccent = accent; bdTrigger = true }
            1 -> { sdAccent = accent; sdTrigger = true }
            2 -> { hhAccent = accent; hhTrigger = true }
        }
    }

    // Edge detection state
    private var lastBdTrig = 0f
    private var lastSdTrig = 0f
    private var lastHhTrig = 0f

    override fun process(numFrames: Int) {
        val outputBuf = oboeOutput.getBuffer()
        val bdTrigs = oboeTriggerBd.getBuffer()
        val sdTrigs = oboeTriggerSd.getBuffer()
        val hhTrigs = oboeTriggerHh.getBuffer()

        for (i in 0 until numFrames) {
            // Manual triggers (control rate, processed at buffer start)
            val bdManual = if (i == 0 && bdTrigger) { bdTrigger = false; true } else false
            val sdManual = if (i == 0 && sdTrigger) { sdTrigger = false; true } else false
            val hhManual = if (i == 0 && hhTrigger) { hhTrigger = false; true } else false
            val fmManual = if (i == 0 && fmTrigger) { fmTrigger = false; true } else false

            // Audio rate triggers (edge detection)
            val bdIn = bdTrigs[i]
            val sdIn = sdTrigs[i]
            val hhIn = hhTrigs[i]

            val bdAudio = bdIn > 0.1f && lastBdTrig <= 0.1f
            val sdAudio = sdIn > 0.1f && lastSdTrig <= 0.1f
            val hhAudio = hhIn > 0.1f && lastHhTrig <= 0.1f

            lastBdTrig = bdIn
            lastSdTrig = sdIn
            lastHhTrig = hhIn

            // Combine triggers
            val doBd = bdManual || bdAudio
            val doSd = sdManual || sdAudio
            val doHh = hhManual || hhAudio
            val doFm = fmManual

            // Process ALL drums every sample
            val bdSample = bd.process(doBd, bdAccent, bdF0, bdTone, bdDecay, bdP4, bdP5)
            val sdSample = sd.process(doSd, sdAccent, sdF0, sdTone, sdDecay, sdP4)
            val hhSample = hh.process(doHh, hhAccent, hhF0, hhTone, hhDecay, hhP4)
            val fmSample = fm.process(doFm, bdAccent, bdF0, bdTone, bdDecay, bdP4, bdP5)

            // Mix all drums with gain staging
            var mix = bdSample * 1.2f + sdSample * 0.6f + hhSample * 0.5f
            if (drumMode == 1) {
                mix = fmSample * 0.8f
            } else {
                mix += fmSample * 0.2f
            }

            outputBuf[i] = softLimit(mix)
        }
    }

    private fun softLimit(x: Float): Float {
        return if (x.absoluteValue < 0.5f) {
            x
        } else {
            sign(x) * (0.5f + 0.5f * tanh((x.absoluteValue - 0.5f) * 2f))
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        oboeOutput.allocate(maxFrames)
        oboeTriggerBd.allocate(maxFrames)
        oboeTriggerSd.allocate(maxFrames)
        oboeTriggerHh.allocate(maxFrames)
    }

    private val inputPorts = listOf(oboeTriggerBd, oboeTriggerSd, oboeTriggerHh)
    private val outputPorts = listOf(oboeOutput)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
