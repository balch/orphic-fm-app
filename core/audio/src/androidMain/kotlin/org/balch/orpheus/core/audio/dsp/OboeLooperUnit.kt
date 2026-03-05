package org.balch.orpheus.core.audio.dsp

/**
 * Oboe implementation of LooperUnit.
 * Stereo record/play with crossfade using internal FloatArray buffers.
 */
class OboeLooperUnit : LooperUnit, OboeProcessable {
    @Volatile override var enabled = true

    private val CROSSFADE_MS = 20.0
    private val CROSSFADE_SAMPLES = (DSP_SAMPLE_RATE * CROSSFADE_MS / 1000.0).toInt()

    // Ports
    private val oboeInputL = OboeAudioInput("InputLeft")
    private val oboeInputR = OboeAudioInput("InputRight")
    private val oboeOutputL = OboeAudioOutput("OutputLeft")
    private val oboeOutputR = OboeAudioOutput("OutputRight")
    private val oboeRecordGate = OboeAudioInput("RecordGate")
    private val oboePlayGate = OboeAudioInput("PlayGate")

    override val inputLeft: AudioInput = oboeInputL
    override val inputRight: AudioInput = oboeInputR
    override val output: AudioOutput = oboeOutputL
    override val outputRight: AudioOutput = oboeOutputR
    override val recordGate: AudioInput = oboeRecordGate
    override val playGate: AudioInput = oboePlayGate

    // Recording buffers
    private var bufferLeft = FloatArray(0)
    private var bufferRight = FloatArray(0)
    private var maxFrames = 0

    // State
    private var loopSampleCount = 0
    private var writePosition = 0
    private var readPosition = 0
    @Volatile private var isRecording = false
    @Volatile private var isPlaying = false

    override fun allocate(maxSeconds: Double) {
        maxFrames = (DSP_SAMPLE_RATE * maxSeconds).toInt()
        bufferLeft = FloatArray(maxFrames)
        bufferRight = FloatArray(maxFrames)
    }

    override fun setRecording(active: Boolean) {
        if (active == isRecording) return
        isRecording = active

        if (active) {
            if (isPlaying) {
                isPlaying = false
            }
            writePosition = 0
            loopSampleCount = 0
        } else {
            loopSampleCount = writePosition
            if (loopSampleCount > 0) {
                applyCrossfade(bufferLeft, loopSampleCount)
                applyCrossfade(bufferRight, loopSampleCount)
                readPosition = 0
                isPlaying = true
            }
        }
    }

    override fun setPlaying(active: Boolean) {
        if (active == isPlaying) return
        isPlaying = active
        if (active && loopSampleCount > 0) {
            readPosition = 0
        }
    }

    override fun clear() {
        isRecording = false
        isPlaying = false
        loopSampleCount = 0
        writePosition = 0
        readPosition = 0
    }

    override fun getPosition(): Float {
        if (isRecording && maxFrames > 0) {
            return writePosition.toFloat() / maxFrames
        }
        if (isPlaying && loopSampleCount > 0) {
            return readPosition.toFloat() / loopSampleCount
        }
        return 0f
    }

    override fun getLoopDuration(): Double = loopSampleCount / DSP_SAMPLE_RATE.toDouble()

    override fun process(numFrames: Int) {
        val inL = oboeInputL.getBuffer()
        val inR = oboeInputR.getBuffer()
        val outL = oboeOutputL.getBuffer()
        val outR = oboeOutputR.getBuffer()

        for (i in 0 until numFrames) {
            // Recording
            if (isRecording && writePosition < maxFrames) {
                bufferLeft[writePosition] = inL[i]
                bufferRight[writePosition] = inR[i]
                writePosition++
            }

            // Playback
            if (isPlaying && loopSampleCount > 0) {
                outL[i] = bufferLeft[readPosition]
                outR[i] = bufferRight[readPosition]
                readPosition++
                if (readPosition >= loopSampleCount) {
                    readPosition = 0
                }
            } else {
                outL[i] = 0f
                outR[i] = 0f
            }
        }
    }

    private fun applyCrossfade(buffer: FloatArray, sampleCount: Int) {
        if (sampleCount < CROSSFADE_SAMPLES * 2) return

        val fadeLength = CROSSFADE_SAMPLES.coerceAtMost(sampleCount / 4)

        for (i in 0 until fadeLength) {
            val fadeIn = i.toFloat() / fadeLength
            val fadeOut = 1.0f - fadeIn

            val startSample = buffer[i]
            val endSample = buffer[sampleCount - fadeLength + i]

            buffer[i] = startSample * fadeIn + endSample * fadeOut
            buffer[sampleCount - fadeLength + i] = endSample * fadeOut + startSample * fadeIn
        }
    }

    override fun allocateBuffers(maxFrames: Int) {
        oboeInputL.allocate(maxFrames)
        oboeInputR.allocate(maxFrames)
        oboeOutputL.allocate(maxFrames)
        oboeOutputR.allocate(maxFrames)
    }

    // Gate inputs are declared for interface compliance but not consumed in
    // process() — looper responds to control-rate setRecording()/setPlaying().
    // Exclude from input ports to avoid unnecessary prepare() overhead.
    private val inputPorts = listOf(oboeInputL, oboeInputR)
    private val outputPorts = listOf(oboeOutputL, oboeOutputR)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
