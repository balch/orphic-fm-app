package org.balch.orpheus.core.audio.dsp

import kotlin.math.PI
import kotlin.math.tan

/**
 * Speech-dedicated effects chain: 6-stage phaser → feedback delay → Schroeder reverb.
 * All processing is mono (L+R summed, then duplicated to stereo out).
 * Each effect bypasses when its amount is 0.
 */
class JsynSpeechEffectsUnit : com.jsyn.unitgen.UnitGenerator(), SpeechEffectsUnit {

    private val jsynInputL = com.jsyn.ports.UnitInputPort("InputLeft")
    private val jsynInputR = com.jsyn.ports.UnitInputPort("InputRight")
    private val jsynOutputL = com.jsyn.ports.UnitOutputPort("OutputLeft")
    private val jsynOutputR = com.jsyn.ports.UnitOutputPort("OutputRight")

    override val inputLeft: AudioInput = JsynAudioInput(jsynInputL)
    override val inputRight: AudioInput = JsynAudioInput(jsynInputR)
    override val output: AudioOutput = JsynAudioOutput(jsynOutputL)
    override val outputRight: AudioOutput = JsynAudioOutput(jsynOutputR)

    // --- Parameters ---
    private var phaserIntensity = 0f
    private var feedbackAmount = 0f
    private var reverbAmount = 0f

    // --- Phaser state (6-stage all-pass) ---
    private val PHASER_STAGES = 6
    private val phaserBuf = FloatArray(PHASER_STAGES)
    private var phaserLfoPhase = 0.0

    // --- Feedback delay state (~250ms circular buffer) ---
    private val DELAY_SAMPLES = 11025 // ~250ms at 44100
    private val delayBuffer = FloatArray(DELAY_SAMPLES)
    private var delayWritePos = 0
    private var delayFeedbackSample = 0f

    // --- Schroeder reverb state ---
    // 4 parallel comb filters
    private val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356)
    private val combBuffers = Array(4) { FloatArray(COMB_LENGTHS[it]) }
    private val combPositions = IntArray(4)
    private val COMB_FEEDBACK = 0.84f // ~1.5s decay

    // 2 series all-pass filters
    private val AP_LENGTHS = intArrayOf(225, 556)
    private val apBuffers = Array(2) { FloatArray(AP_LENGTHS[it]) }
    private val apPositions = IntArray(2)
    private val AP_GAIN = 0.5f

    // Low-pass state for reverb damping
    private var reverbLpState = 0f
    private val REVERB_LP_COEFF = 0.7f

    init {
        addPort(jsynInputL)
        addPort(jsynInputR)
        addPort(jsynOutputL)
        addPort(jsynOutputR)
    }

    override fun setPhaserIntensity(intensity: Float) {
        phaserIntensity = intensity.coerceIn(0f, 1f)
    }

    override fun setFeedbackAmount(amount: Float) {
        feedbackAmount = amount.coerceIn(0f, 1f)
    }

    override fun setReverbAmount(amount: Float) {
        reverbAmount = amount.coerceIn(0f, 1f)
    }

    override fun generate(start: Int, end: Int) {
        val inL = jsynInputL.values
        val inR = jsynInputR.values
        val outL = jsynOutputL.values
        val outR = jsynOutputR.values

        for (i in start until end) {
            // Sum to mono
            var sample = ((inL[i] + inR[i]) * 0.5).toFloat()

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
            val out = sample.toDouble()
            outL[i] = out
            outR[i] = out
        }
    }

    private fun processPhaser(input: Float): Float {
        // Triangle LFO: rate scales with intensity (0.2-4 Hz)
        val lfoRate = 0.2 + phaserIntensity * 3.8
        phaserLfoPhase += lfoRate / 44100.0
        if (phaserLfoPhase >= 1.0) phaserLfoPhase -= 1.0

        // Triangle wave 0..1
        val tri = if (phaserLfoPhase < 0.5) (phaserLfoPhase * 2.0).toFloat()
        else (2.0 - phaserLfoPhase * 2.0).toFloat()

        // Sweep center frequency 200-4000 Hz
        val depth = phaserIntensity
        val fc = 200f + tri * depth * 3800f
        val w = tan((PI * fc / 44100.0).toFloat())
        val g = (1f - w) / (1f + w)

        // 6-stage all-pass chain
        var x = input
        for (stage in 0 until PHASER_STAGES) {
            val y = -g * x + phaserBuf[stage]
            phaserBuf[stage] = g * y + x
            x = y
        }

        // Mix: dry + wet (phased signal)
        return input + x * phaserIntensity
    }

    private fun processDelay(input: Float): Float {
        val feedbackGain = (feedbackAmount * 0.6f).coerceAtMost(0.85f)
        val wet = delayBuffer[delayWritePos]

        // Write new sample + feedback
        delayBuffer[delayWritePos] = input + delayFeedbackSample * feedbackGain
        delayFeedbackSample = wet

        delayWritePos++
        if (delayWritePos >= DELAY_SAMPLES) delayWritePos = 0

        // Mix dry + wet
        return input * (1f - feedbackAmount * 0.5f) + wet * feedbackAmount
    }

    private fun processReverb(input: Float): Float {
        // Low-pass the input for damping
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

        // Mix dry + wet
        return input * (1f - reverbAmount * 0.5f) + apOut * reverbAmount
    }
}
