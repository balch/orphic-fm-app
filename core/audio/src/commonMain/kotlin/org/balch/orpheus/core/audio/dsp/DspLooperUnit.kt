package org.balch.orpheus.core.audio.dsp

import kotlin.concurrent.Volatile

/**
 * Stereo record/play looper with crossfade using internal FloatArray buffers.
 */
class DspLooperUnit : LooperUnit, DspProcessable {
    @Volatile override var enabled = true

    private val CROSSFADE_MS = 20.0
    private var crossfadeSamples = (dspSampleRate * CROSSFADE_MS / 1000.0).toInt()

    // Ports
    private val dspInputL = DspAudioInput("InputLeft")
    private val dspInputR = DspAudioInput("InputRight")
    private val dspOutputL = DspAudioOutput("OutputLeft")
    private val dspOutputR = DspAudioOutput("OutputRight")
    private val dspRecordGate = DspAudioInput("RecordGate")
    private val dspPlayGate = DspAudioInput("PlayGate")

    override val inputLeft: AudioInput = dspInputL
    override val inputRight: AudioInput = dspInputR
    override val output: AudioOutput = dspOutputL
    override val outputRight: AudioOutput = dspOutputR
    override val recordGate: AudioInput = dspRecordGate
    override val playGate: AudioInput = dspPlayGate

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
        maxFrames = (dspSampleRate * maxSeconds).toInt()
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

    override fun getLoopDuration(): Double = loopSampleCount / dspSampleRate.toDouble()

    override fun process(numFrames: Int) {
        val inL = dspInputL.getBuffer()
        val inR = dspInputR.getBuffer()
        val outL = dspOutputL.getBuffer()
        val outR = dspOutputR.getBuffer()

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
        if (sampleCount < crossfadeSamples * 2) return

        val fadeLength = crossfadeSamples.coerceAtMost(sampleCount / 4)

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
        dspInputL.allocate(maxFrames)
        dspInputR.allocate(maxFrames)
        dspOutputL.allocate(maxFrames)
        dspOutputR.allocate(maxFrames)
    }

    // Gate inputs are declared for interface compliance but not consumed in
    // process() — looper responds to control-rate setRecording()/setPlaying().
    // Exclude from input ports to avoid unnecessary prepare() overhead.
    private val inputPorts = listOf(dspInputL, dspInputR)
    private val outputPorts = listOf(dspOutputL, dspOutputR)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
