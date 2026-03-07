package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.drum.engine.AnalogBassDrum
import org.balch.orpheus.plugins.drum.engine.AnalogSnareDrum
import org.balch.orpheus.plugins.drum.engine.FmDrum
import org.balch.orpheus.plugins.drum.engine.MetallicHiHat
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlin.math.tanh

/**
 * DSP DrumUnit — delegates to real 808/FM drum engines.
 *
 * All four drum voices (BD, SD, HH, FM) run simultaneously every sample so
 * that decaying tails are never cut short when a different drum type triggers.
 * Audio-rate trigger inputs use rising-edge detection; control-rate triggers
 * set flags consumed at the start of the next process block.
 */
class DspDrumUnit : DrumUnit, DspProcessable {
    override var enabled = true

    // --- Drum engines ---
    private val bd = AnalogBassDrum()
    private val sd = AnalogSnareDrum()
    private val hh = MetallicHiHat()
    private val fm = FmDrum(SynthDsp.SAMPLE_RATE)

    // --- Audio ports ---
    private val dspTriggerBd = DspAudioInput("Drum.trigBd")
    private val dspTriggerSd = DspAudioInput("Drum.trigSd")
    private val dspTriggerHh = DspAudioInput("Drum.trigHh")
    private val dspOutput = DspAudioOutput("Drum.out")

    override val triggerInputBd: AudioInput = dspTriggerBd
    override val triggerInputSd: AudioInput = dspTriggerSd
    override val triggerInputHh: AudioInput = dspTriggerHh
    override val output: AudioOutput = dspOutput

    // --- Mode: 0 = 808, 1 = FM ---
    private var drumMode = 0

    // --- Per-drum trigger flags (set by control-rate trigger(), consumed in process()) ---
    private var bdTrigger = false
    private var sdTrigger = false
    private var hhTrigger = false
    private var fmTrigger = false

    // --- Per-drum stored parameters ---
    // Bass Drum
    private var bdAccent = 0.5f
    private var bdF0 = 55.0f / SynthDsp.SAMPLE_RATE
    private var bdTone = 0.5f
    private var bdDecay = 0.5f
    private var bdP4 = 0.5f  // Attack FM
    private var bdP5 = 0.5f  // Self FM

    // Snare Drum
    private var sdAccent = 0.5f
    private var sdF0 = 180.0f / SynthDsp.SAMPLE_RATE
    private var sdTone = 0.5f
    private var sdDecay = 0.5f
    private var sdP4 = 0.5f  // Snappiness

    // Hi-Hat
    private var hhAccent = 0.5f
    private var hhF0 = 400.0f / SynthDsp.SAMPLE_RATE
    private var hhTone = 0.5f
    private var hhDecay = 0.5f
    private var hhP4 = 0.5f  // Noisiness

    // --- Edge-detection state for audio-rate triggers ---
    private var lastBdTrig = 0f
    private var lastSdTrig = 0f
    private var lastHhTrig = 0f

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
        val f0 = frequency / SynthDsp.SAMPLE_RATE
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

    override fun process(numFrames: Int) {
        val out = dspOutput.getBuffer()
        val bdTrigs = dspTriggerBd.getBuffer()
        val sdTrigs = dspTriggerSd.getBuffer()
        val hhTrigs = dspTriggerHh.getBuffer()

        for (i in 0 until numFrames) {
            // --- Control-rate triggers (consumed at first sample) ---
            val bdManual = if (i == 0 && bdTrigger) { bdTrigger = false; true } else false
            val sdManual = if (i == 0 && sdTrigger) { sdTrigger = false; true } else false
            val hhManual = if (i == 0 && hhTrigger) { hhTrigger = false; true } else false
            val fmManual = if (i == 0 && fmTrigger) { fmTrigger = false; true } else false

            // --- Audio-rate trigger edge detection ---
            val bdIn = bdTrigs[i]
            val sdIn = sdTrigs[i]
            val hhIn = hhTrigs[i]

            val bdAudio = bdIn > 0.1f && lastBdTrig <= 0.1f
            val sdAudio = sdIn > 0.1f && lastSdTrig <= 0.1f
            val hhAudio = hhIn > 0.1f && lastHhTrig <= 0.1f

            lastBdTrig = bdIn
            lastSdTrig = sdIn
            lastHhTrig = hhIn

            // --- Combine triggers ---
            val doBd = bdManual || bdAudio
            val doSd = sdManual || sdAudio
            val doHh = hhManual || hhAudio
            val doFm = fmManual

            // --- Process ALL drums every sample (they naturally decay to 0) ---
            val bdSample = bd.process(doBd, bdAccent, bdF0, bdTone, bdDecay, bdP4, bdP5)
            val sdSample = sd.process(doSd, sdAccent, sdF0, sdTone, sdDecay, sdP4)
            val hhSample = hh.process(doHh, hhAccent, hhF0, hhTone, hhDecay, hhP4)
            val fmSample = fm.process(doFm, bdAccent, bdF0, bdTone, bdDecay, bdP4, bdP5)

            // --- Mix with gain staging ---
            var mix: Float
            if (drumMode == 1) {
                mix = fmSample * 0.8f
            } else {
                mix = bdSample * 1.2f + sdSample * 0.6f + hhSample * 0.5f + fmSample * 0.2f
            }

            // --- Soft limiter (tanh saturation) ---
            out[i] = softLimit(mix)
        }
    }

    /**
     * Soft saturation curve to prevent hard clipping.
     * Linear below 0.5, tanh saturation above.
     */
    private fun softLimit(x: Float): Float {
        return if (x.absoluteValue < 0.5f) {
            x
        } else {
            sign(x) * (0.5f + 0.5f * tanh((x.absoluteValue - 0.5f) * 2f))
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspTriggerBd.allocate(maxFrames)
        dspTriggerSd.allocate(maxFrames)
        dspTriggerHh.allocate(maxFrames)
        dspOutput.allocate(maxFrames)
    }

    private val inputPorts = listOf(dspTriggerBd, dspTriggerSd, dspTriggerHh)
    private val outputPorts = listOf(dspOutput)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
