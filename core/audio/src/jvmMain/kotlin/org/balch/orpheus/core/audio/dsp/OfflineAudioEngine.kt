package org.balch.orpheus.core.audio.dsp

import com.jsyn.JSyn
import com.jsyn.ports.UnitInputPort
import com.jsyn.unitgen.LineOut
import com.jsyn.unitgen.PassThrough
import com.jsyn.unitgen.UnitGenerator
import java.util.Arrays

/**
 * Offline-mode JSyn [AudioEngine] for headless WAV snapshot rendering.
 *
 * Uses `setRealTime(false)` so the synth runs as fast as the CPU allows.
 * Stereo output is captured via custom [SampleCapture] units tapping the line-out proxies.
 */
class OfflineAudioEngine(override val sampleRate: Int = 44100) : AudioEngine {

    private val synth = JSyn.createSynthesizer().apply {
        setRealTime(false)
    }
    private val lineOut = LineOut()

    private val lineOutLeftProxy = PassThrough()
    private val lineOutRightProxy = PassThrough()

    /** Accumulates left-channel samples during offline rendering. */
    private val captureL = SampleCapture()
    /** Accumulates right-channel samples during offline rendering. */
    private val captureR = SampleCapture()

    init {
        synth.add(lineOutLeftProxy)
        synth.add(lineOutRightProxy)
        lineOutLeftProxy.output.connect(0, lineOut.input, 0)
        lineOutRightProxy.output.connect(0, lineOut.input, 1)

        // Capture taps: branch from line-out proxies
        synth.add(captureL)
        synth.add(captureR)
        lineOutLeftProxy.output.connect(captureL.jsInput)
        lineOutRightProxy.output.connect(captureR.jsInput)
    }

    override fun start() {
        synth.add(lineOut)
        synth.start(sampleRate)
        lineOut.start()
        captureL.start()
        captureR.start()
    }

    override fun stop() {
        captureL.stop()
        captureR.stop()
        lineOut.stop()
        synth.stop()
    }

    override val isRunning: Boolean get() = synth.isRunning

    override fun addUnit(unit: AudioUnit) {
        when (unit) {
            is JsynEnvelope -> synth.add(unit.jsEnv)
            is JsynDelayLine -> synth.add(unit.jsDelay)
            is JsynPeakFollowerWrapper -> synth.add(unit.jsPeak)
            is JsynLimiter -> synth.add(unit.jsLimiter)
            is JsynMultiplyWrapper -> synth.add(unit.jsUnit)
            is JsynAddWrapper -> synth.add(unit.jsUnit)
            is JsynMultiplyAddWrapper -> synth.add(unit.jsUnit)
            is JsynPassThroughWrapper -> synth.add(unit.jsUnit)
            is JsynSineOscillatorWrapper -> synth.add(unit.jsOsc)
            is JsynTriangleOscillatorWrapper -> synth.add(unit.jsOsc)
            is JsynSquareOscillatorWrapper -> synth.add(unit.jsOsc)
            is JsynSawtoothOscillatorWrapper -> synth.add(unit.jsOsc)
            is JsynMinimumWrapper -> synth.add(unit.jsUnit)
            is JsynMaximumWrapper -> synth.add(unit.jsUnit)
            is JsynLinearRampWrapper -> synth.add(unit.jsRamp)
            is JsynAutomationPlayer -> synth.add(unit.reader)
            is JsynLooperUnit -> {
                synth.add(unit.writerLeft)
                synth.add(unit.writerRight)
                synth.add(unit.readerLeft)
                synth.add(unit.readerRight)
                synth.add(unit.recordGateInput)
                synth.add(unit.playGateInput)
            }
            is JsynClockUnit -> {
                synth.add(unit.jsOsc)
                synth.add(unit.scaler)
            }
            is JsynTtsPlayerUnit -> synth.add(unit)
            is UnitGenerator -> synth.add(unit)
        }
    }

    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {
        val ug: UnitGenerator? = when (unit) {
            is JsynMultiplyWrapper -> unit.jsUnit
            is JsynAddWrapper -> unit.jsUnit
            is JsynMultiplyAddWrapper -> unit.jsUnit
            is JsynPassThroughWrapper -> unit.jsUnit
            is JsynEnvelope -> unit.jsEnv
            is JsynDelayLine -> unit.jsDelay
            is JsynPeakFollowerWrapper -> unit.jsPeak
            is JsynLimiter -> unit.jsLimiter
            is JsynSineOscillatorWrapper -> unit.jsOsc
            is JsynTriangleOscillatorWrapper -> unit.jsOsc
            is JsynSquareOscillatorWrapper -> unit.jsOsc
            is JsynSawtoothOscillatorWrapper -> unit.jsOsc
            is JsynMinimumWrapper -> unit.jsUnit
            is JsynMaximumWrapper -> unit.jsUnit
            is JsynLinearRampWrapper -> unit.jsRamp
            is JsynAutomationPlayer -> unit.reader
            is JsynTtsPlayerUnit -> unit
            is UnitGenerator -> unit
            else -> null
        }
        ug?.let {
            it.isEnabled = enabled
            if (!enabled) {
                for (port in it.ports) {
                    if (port is com.jsyn.ports.UnitOutputPort) {
                        for (i in 0 until port.numParts) {
                            Arrays.fill(port.getValues(i), 0.0)
                        }
                    }
                }
            }
        }
    }

    override val lineOutLeft: AudioInput get() = JsynAudioInput(lineOutLeftProxy.input)
    override val lineOutRight: AudioInput get() = JsynAudioInput(lineOutRightProxy.input)

    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = synth.currentTime

    // ── Offline rendering ──────────────────────────────────

    /**
     * Render [totalFrames] of stereo audio, returning interleaved L/R float samples.
     *
     * @param perChunk callback invoked before each chunk, receives frame offset.
     *        Use it to change gate/parameters mid-render.
     */
    fun renderStereo(
        totalFrames: Int,
        chunkSize: Int = 128,
        perChunk: (frameOffset: Int) -> Unit = {}
    ): FloatArray {
        val result = FloatArray(totalFrames * 2)
        val frameDuration = 1.0 / sampleRate

        var offset = 0
        while (offset < totalFrames) {
            val chunk = minOf(chunkSize, totalFrames - offset)
            perChunk(offset)

            // Clear capture buffers before this chunk
            captureL.captured.clear()
            captureR.captured.clear()

            // Process chunk — all UnitGenerators run their generate() method
            synth.sleepUntil(synth.currentTime + chunk * frameDuration)

            // Copy captured samples to result
            val nL = minOf(captureL.captured.size, chunk)
            val nR = minOf(captureR.captured.size, chunk)
            for (i in 0 until minOf(nL, nR)) {
                result[(offset + i) * 2] = captureL.captured[i].toFloat()
                result[(offset + i) * 2 + 1] = captureR.captured[i].toFloat()
            }
            offset += chunk
        }
        return result
    }

    /**
     * Custom UnitGenerator that accumulates input samples during generate().
     * Used as an offline capture tap on audio signal paths.
     */
    private class SampleCapture : UnitGenerator() {
        val jsInput = UnitInputPort("CaptureInput")
        val captured = mutableListOf<Double>()

        init {
            addPort(jsInput)
        }

        override fun generate(start: Int, end: Int) {
            val values = jsInput.values
            for (i in start until end) {
                captured.add(values[i])
            }
        }
    }
}
