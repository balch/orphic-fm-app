package org.balch.orpheus.core.audio.dsp

import kotlin.concurrent.Volatile

/**
 * Variable-rate sample playback with linear interpolation.
 * Mono playback duplicated to stereo output.
 */
class DspTtsPlayerUnit : TtsPlayerUnit, DspProcessable {
    @Volatile override var enabled = true

    private val dspOutputL = DspAudioOutput("OutputLeft")
    private val dspOutputR = DspAudioOutput("OutputRight")

    override val output: AudioOutput = dspOutputL
    override val outputRight: AudioOutput = dspOutputR

    private var samples: FloatArray? = null
    private var playbackRate = 1.0f
    private var volume = 0.7f
    private var position = 0.0 // fractional sample position
    @Volatile private var playing = false

    override fun loadAudio(samples: FloatArray, sampleRate: Int) {
        this.samples = samples
        position = 0.0
    }

    override fun play() {
        position = 0.0
        playing = true
    }

    override fun stop() {
        playing = false
    }

    override fun isPlaying(): Boolean = playing

    override fun setRate(rate: Float) {
        playbackRate = rate.coerceIn(0.25f, 2.0f)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceAtLeast(0f)
    }

    override fun process(numFrames: Int) {
        val outL = dspOutputL.getBuffer()
        val outR = dspOutputR.getBuffer()
        val buf = samples

        if (!playing || buf == null) {
            outL.fill(0f, 0, numFrames)
            outR.fill(0f, 0, numFrames)
            return
        }

        val len = buf.size
        for (i in 0 until numFrames) {
            val intPos = position.toInt()
            if (intPos >= len - 1) {
                playing = false
                outL.fill(0f, i, numFrames)
                outR.fill(0f, i, numFrames)
                return
            }
            // Linear interpolation
            val frac = (position - intPos).toFloat()
            val sample = buf[intPos] * (1f - frac) + buf[intPos + 1] * frac
            val scaled = sample * volume
            outL[i] = scaled
            outR[i] = scaled
            position += playbackRate
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspOutputL.allocate(maxFrames)
        dspOutputR.allocate(maxFrames)
    }

    private val outputPorts = listOf(dspOutputL, dspOutputR)
    override fun getInputPorts(): List<DspAudioInput> = emptyList()
    override fun getOutputPorts() = outputPorts
}
