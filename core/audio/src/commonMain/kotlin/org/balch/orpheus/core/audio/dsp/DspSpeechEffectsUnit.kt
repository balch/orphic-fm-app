package org.balch.orpheus.core.audio.dsp

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.tan

/**
 * Speech-dedicated effects chain: 6-stage phaser -> feedback delay -> Schroeder reverb.
 * All processing is mono (L+R summed, then duplicated to stereo out).
 * Each effect bypasses when its amount is 0.
 */
class DspSpeechEffectsUnit : SpeechEffectsUnit, DspProcessable {
    @Volatile override var enabled = true

    private val dspInputL = DspAudioInput("InputLeft")
    private val dspInputR = DspAudioInput("InputRight")
    private val dspOutputL = DspAudioOutput("OutputLeft")
    private val dspOutputR = DspAudioOutput("OutputRight")

    override val inputLeft: AudioInput = dspInputL
    override val inputRight: AudioInput = dspInputR
    override val output: AudioOutput = dspOutputL
    override val outputRight: AudioOutput = dspOutputR

    // --- Parameters (written from UI thread, read from audio thread) ---
    @Volatile private var phaserIntensity = 0f
    @Volatile private var feedbackAmount = 0f
    @Volatile private var reverbAmount = 0f

    // --- Phaser state (6-stage all-pass) ---
    private val PHASER_STAGES = 6
    private val phaserBuf = FloatArray(PHASER_STAGES)
    private var phaserLfoPhase = 0.0

    // --- Feedback delay state (~250ms circular buffer, allocated at runtime) ---
    private var delaySamples = (dspSampleRate * 0.25f).toInt()
    private var delayBuffer = FloatArray(delaySamples)
    private var delayWritePos = 0
    private var delayFeedbackSample = 0f

    // --- Schroeder reverb state ---
    private val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356)
    private val combBuffers = Array(4) { FloatArray(COMB_LENGTHS[it]) }
    private val combPositions = IntArray(4)
    private val COMB_FEEDBACK = 0.84f

    private val AP_LENGTHS = intArrayOf(225, 556)
    private val apBuffers = Array(2) { FloatArray(AP_LENGTHS[it]) }
    private val apPositions = IntArray(2)
    private val AP_GAIN = 0.5f

    private var reverbLpState = 0f
    private val REVERB_LP_COEFF = 0.7f

    override fun setPhaserIntensity(intensity: Float) {
        phaserIntensity = intensity.coerceIn(0f, 1f)
    }

    override fun setFeedbackAmount(amount: Float) {
        feedbackAmount = amount.coerceIn(0f, 1f)
    }

    override fun setReverbAmount(amount: Float) {
        reverbAmount = amount.coerceIn(0f, 1f)
    }

    override fun process(numFrames: Int) {
        val inL = dspInputL.getBuffer()
        val inR = dspInputR.getBuffer()
        val outL = dspOutputL.getBuffer()
        val outR = dspOutputR.getBuffer()

        for (i in 0 until numFrames) {
            // Sum to mono
            var sample = (inL[i] + inR[i]) * 0.5f

            // 1. Phaser
            if (phaserIntensity > 0.001f) {
                sample = processPhaser(sample)
            }

            // 2. Feedback delay
            if (feedbackAmount > 0.001f) {
                sample = processDelay(sample)
            }

            // 3. Reverb
            if (reverbAmount > 0.001f) {
                sample = processReverb(sample)
            }

            // Duplicate mono to stereo
            outL[i] = sample
            outR[i] = sample
        }
    }

    private fun processPhaser(input: Float): Float {
        // Triangle LFO: rate scales with intensity (0.2-4 Hz)
        val lfoRate = 0.2 + phaserIntensity * 3.8
        phaserLfoPhase += lfoRate / dspSampleRate
        if (phaserLfoPhase >= 1.0) phaserLfoPhase -= 1.0

        // Triangle wave 0..1
        val tri = if (phaserLfoPhase < 0.5) (phaserLfoPhase * 2.0).toFloat()
        else (2.0 - phaserLfoPhase * 2.0).toFloat()

        // Sweep center frequency 200-4000 Hz
        val depth = phaserIntensity
        val fc = 200f + tri * depth * 3800f
        val w = tan((PI * fc / dspSampleRate).toFloat())
        val g = (1f - w) / (1f + w)

        // 6-stage all-pass chain
        var x = input
        for (stage in 0 until PHASER_STAGES) {
            val y = -g * x + phaserBuf[stage]
            phaserBuf[stage] = g * y + x
            x = y
        }

        return input + x * phaserIntensity
    }

    private fun processDelay(input: Float): Float {
        val feedbackGain = (feedbackAmount * 0.6f).coerceAtMost(0.85f)
        val wet = delayBuffer[delayWritePos]

        delayBuffer[delayWritePos] = input + delayFeedbackSample * feedbackGain
        delayFeedbackSample = wet

        delayWritePos++
        if (delayWritePos >= delaySamples) delayWritePos = 0

        return input * (1f - feedbackAmount * 0.5f) + wet * feedbackAmount
    }

    private fun processReverb(input: Float): Float {
        reverbLpState += REVERB_LP_COEFF * (input - reverbLpState)
        val dampedInput = reverbLpState

        // 4 parallel comb filters
        var combSum = 0f
        for (c in 0 until 4) {
            val buf = combBuffers[c]
            val pos = combPositions[c]
            val delayed = buf[pos]
            buf[pos] = dampedInput + delayed * COMB_FEEDBACK
            combPositions[c] = (pos + 1) % COMB_LENGTHS[c]
            combSum += delayed
        }
        combSum *= 0.25f

        // 2 series all-pass filters
        var apOut = combSum
        for (a in 0 until 2) {
            val buf = apBuffers[a]
            val pos = apPositions[a]
            val delayed = buf[pos]
            val y = -AP_GAIN * apOut + delayed
            buf[pos] = apOut + AP_GAIN * y
            apPositions[a] = (pos + 1) % AP_LENGTHS[a]
            apOut = y
        }

        return input * (1f - reverbAmount * 0.5f) + apOut * reverbAmount
    }

    override fun allocateBuffers(maxFrames: Int) {
        dspInputL.allocate(maxFrames)
        dspInputR.allocate(maxFrames)
        dspOutputL.allocate(maxFrames)
        dspOutputR.allocate(maxFrames)
    }

    private val inputPorts = listOf(dspInputL, dspInputR)
    private val outputPorts = listOf(dspOutputL, dspOutputR)
    override fun getInputPorts() = inputPorts
    override fun getOutputPorts() = outputPorts
}
