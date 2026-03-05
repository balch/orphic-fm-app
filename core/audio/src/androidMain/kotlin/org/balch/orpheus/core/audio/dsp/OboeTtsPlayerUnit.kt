package org.balch.orpheus.core.audio.dsp

/**
 * Oboe implementation of TtsPlayerUnit.
 * Variable-rate sample playback with linear interpolation.
 * Mono playback duplicated to stereo output.
 */
class OboeTtsPlayerUnit : TtsPlayerUnit, OboeProcessable {
    @Volatile override var enabled = true

    private val oboeOutputL = OboeAudioOutput("OutputLeft")
    private val oboeOutputR = OboeAudioOutput("OutputRight")

    override val output: AudioOutput = oboeOutputL
    override val outputRight: AudioOutput = oboeOutputR

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
        val outL = oboeOutputL.getBuffer()
        val outR = oboeOutputR.getBuffer()
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
        oboeOutputL.allocate(maxFrames)
        oboeOutputR.allocate(maxFrames)
    }

    private val outputPorts = listOf(oboeOutputL, oboeOutputR)
    override fun getInputPorts(): List<OboeAudioInput> = emptyList()
    override fun getOutputPorts() = outputPorts
}
