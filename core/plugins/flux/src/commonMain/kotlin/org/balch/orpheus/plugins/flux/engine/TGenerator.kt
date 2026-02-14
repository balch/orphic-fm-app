// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles random/t_generator.cc.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

import kotlin.math.abs

/** Generator for the T (timing/gate) outputs.
 *  Implements all 7 Marbles timing models. */
class TGenerator {

    enum class Model {
        COMPLEMENTARY_BERNOULLI,
        CLUSTERS,
        DRUMS,
        INDEPENDENT_BERNOULLI,
        DIVIDER,
        THREE_STATES,
        MARKOV
    }

    enum class Range {
        RANGE_0_25X,
        RANGE_1X,
        RANGE_4X
    }

    /** Per-tick random vector layout matching C++ union. */
    private class RandomVector {
        val pulseWidth = FloatArray(NUM_T_CHANNELS)
        val u = FloatArray(NUM_T_CHANNELS)
        var p = 0f
        var jitter = 0f

        fun fromArray(arr: FloatArray) {
            pulseWidth[0] = arr[0]
            pulseWidth[1] = arr[1]
            u[0] = arr[2]
            u[1] = arr[3]
            p = arr[4]
            jitter = arr[5]
        }
    }

    // Configuration
    private var oneHertz = 0f
    private var model = Model.COMPLEMENTARY_BERNOULLI
    private var range = Range.RANGE_1X
    private var rate = 0f
    private var bias = 0.5f
    private var jitter = 0f
    private var pulseWidthMean = 0f
    private var pulseWidthStd = 0f

    // Phase tracking
    private var masterPhase = 0f
    private var jitterMultiplier = 1f
    private var phaseDifference = 0f
    private var previousExternalRampValue = 0f
    private var useExternalClock = false

    // Pattern state
    private var dividerPatternLength = 0
    private val streakCounter = IntArray(MARKOV_HISTORY_SIZE)
    private val markovHistory = IntArray(MARKOV_HISTORY_SIZE)
    private var markovHistoryPtr = 0
    private var drumPatternStep = 0
    private var drumPatternIndex = 0

    // Sub-components
    private val sequence = RandomSequence()
    private val rampExtractor = RampExtractor()
    private val rampGenerator = RampGenerator()
    private val slaveRamp = Array(NUM_T_CHANNELS) { SlaveRamp() }
    private val biasQuantizer = HysteresisQuantizer2()
    private val rateQuantizer = HysteresisQuantizer2()

    private val randomStream = RandomStream()
    private val randomVectorArray = FloatArray(2 * NUM_T_CHANNELS + 2)

    fun init(sampleRate: Float) {
        oneHertz = 1f / sampleRate
        model = Model.COMPLEMENTARY_BERNOULLI
        range = Range.RANGE_1X

        rate = 0f
        bias = 0.5f
        jitter = 0f
        pulseWidthMean = 0f
        pulseWidthStd = 0f

        masterPhase = 0f
        jitterMultiplier = 1f
        phaseDifference = 0f
        previousExternalRampValue = 0f

        dividerPatternLength = 0
        streakCounter.fill(0)
        markovHistory.fill(0)
        markovHistoryPtr = 0
        drumPatternStep = 0
        drumPatternIndex = 0

        sequence.init(randomStream)
        rampExtractor.init(1000f / sampleRate)
        rampGenerator.init()
        for (i in 0 until NUM_T_CHANNELS) {
            slaveRamp[i].init()
        }
        biasQuantizer.init(NUM_DIVIDER_PATTERNS, 0.1f, false)
        rateQuantizer.init(NUM_INPUT_DIVIDER_RATIOS, 0.05f, false)
        useExternalClock = false
    }

    // --- Setters ---
    fun setModel(model: Model) { this.model = model }
    fun setRange(range: Range) { this.range = range }
    fun setRate(rate: Float) { this.rate = rate }
    fun setBias(bias: Float) { this.bias = bias }
    fun setJitter(jitter: Float) { this.jitter = jitter }
    fun setDejaVu(dejaVu: Float) { sequence.setDejaVu(dejaVu) }
    fun setLength(length: Int) { sequence.setLength(length) }
    fun setPulseWidthMean(pw: Float) { pulseWidthMean = pw }
    fun setPulseWidthStd(pw: Float) { pulseWidthStd = pw }

    // --- Model generators ---

    private fun generateComplementaryBernoulli(v: RandomVector): Int {
        var bitmask = 0
        for (i in 0 until NUM_T_CHANNELS) {
            if ((v.u[i shr 1] > bias) xor (i and 1 != 0)) {
                bitmask = bitmask or (1 shl i)
            }
        }
        return bitmask
    }

    private fun generateIndependentBernoulli(v: RandomVector): Int {
        var bitmask = 0
        for (i in 0 until NUM_T_CHANNELS) {
            if ((v.u[i] > bias) xor (i and 1 != 0)) {
                bitmask = bitmask or (1 shl i)
            }
        }
        return bitmask
    }

    private fun generateThreeStates(v: RandomVector): Int {
        var bitmask = 0
        val pNone = 0.75f - abs(bias - 0.5f)
        val threshold = pNone + (1f - pNone) * (0.25f + bias * 0.5f)
        for (i in 0 until NUM_T_CHANNELS) {
            val u = v.u[i shr 1]
            if (u > pNone && ((u > threshold) xor (i and 1 != 0))) {
                bitmask = bitmask or (1 shl i)
            }
        }
        return bitmask
    }

    private fun generateDrums(v: RandomVector): Int {
        drumPatternStep++
        if (drumPatternStep >= DRUM_PATTERN_SIZE) {
            drumPatternStep = 0
            val u = v.u[0] * 2f * abs(bias - 0.5f)
            drumPatternIndex = (NUM_DRUM_PATTERNS * u).toInt()
                .coerceIn(0, NUM_DRUM_PATTERNS - 1)
            if (bias <= 0.5f) {
                drumPatternIndex -= drumPatternIndex % 2
            }
        }
        return drumPatterns[drumPatternIndex][drumPatternStep]
    }

    private fun generateMarkov(v: RandomVector): Int {
        var bitmask = 0
        val b = 1.5f * bias - 0.5f
        markovHistory[markovHistoryPtr] = 0
        val p = markovHistoryPtr

        for (i in 0 until NUM_T_CHANNELS) {
            val mask = 1 shl i
            val periodic = markovHistory[(p + 8) % MARKOV_HISTORY_SIZE] and mask != 0
            val simultaneous = markovHistory[(p + 8) % MARKOV_HISTORY_SIZE] and mask.inv() != 0
            val dense = markovHistory[(p + 1) % MARKOV_HISTORY_SIZE] and mask != 0
            val alternate = markovHistory[(p + 4) % MARKOV_HISTORY_SIZE] and mask.inv() != 0

            var logit = -1.5f
            logit += if (streakCounter[i] > 24) 10f else 0f
            logit += 8f * abs(b) * if (periodic) b else -b
            logit -= 2f * if (simultaneous) b else -b
            logit -= 1f * if (dense) b else 0f
            logit += 1f * if (alternate) b else 0f
            logit = logit.coerceIn(-10f, 10f)

            val probability = LookupTables.logit[(logit * 12.8f + 128f).toInt()
                .coerceIn(0, 256)]
            var state = v.u[i] < probability

            if (sequence.getDejaVu() >= v.p) {
                state = markovHistory[(p + sequence.getLength()) % MARKOV_HISTORY_SIZE] and mask != 0
            }
            if (state) {
                bitmask = bitmask or mask
                streakCounter[i] = 0
            } else {
                streakCounter[i]++
            }
        }
        markovHistory[p] = markovHistory[p] or bitmask
        markovHistoryPtr = (p + MARKOV_HISTORY_SIZE - 1) % MARKOV_HISTORY_SIZE
        return bitmask
    }

    private fun randomPulseWidth(u: Float): Float {
        return if (pulseWidthStd == 0f) {
            0.05f + 0.9f * pulseWidthMean
        } else {
            0.05f + 0.9f * Distributions.betaDistributionSample(u, pulseWidthStd, pulseWidthMean)
        }
    }

    private fun scheduleOutputPulses(v: RandomVector, bitmask: Int) {
        var mask = bitmask
        for (i in 0 until NUM_T_CHANNELS) {
            slaveRamp[i].init(
                mask and 1 != 0,
                randomPulseWidth(v.pulseWidth[i]),
                0.5f
            )
            mask = mask shr 1
        }
    }

    private fun configureSlaveRamps(v: RandomVector) {
        when (model) {
            Model.COMPLEMENTARY_BERNOULLI ->
                scheduleOutputPulses(v, generateComplementaryBernoulli(v))
            Model.INDEPENDENT_BERNOULLI ->
                scheduleOutputPulses(v, generateIndependentBernoulli(v))
            Model.THREE_STATES ->
                scheduleOutputPulses(v, generateThreeStates(v))
            Model.DRUMS ->
                scheduleOutputPulses(v, generateDrums(v))
            Model.MARKOV ->
                scheduleOutputPulses(v, generateMarkov(v))
            Model.CLUSTERS, Model.DIVIDER -> {
                dividerPatternLength--
                if (dividerPatternLength <= 0) {
                    val pattern: DividerPattern
                    if (model == Model.DIVIDER) {
                        val idx = biasQuantizer.process(bias)
                        pattern = fixedDividerPatterns[idx]
                    } else {
                        val strength = abs(bias - 0.5f) * 2f
                        var u = v.u[0]
                        u *= (u + strength * strength * (1f - u))
                        u *= strength
                        val patIdx = (u * NUM_DIVIDER_PATTERNS).toInt()
                            .coerceIn(0, NUM_DIVIDER_PATTERNS - 1)
                        pattern = dividerPatterns[patIdx].let { p ->
                            if (bias < 0.5f) {
                                DividerPattern(
                                    ratios = arrayOf(p.ratios[1].copy(), p.ratios[0].copy()),
                                    length = p.length
                                )
                            } else p
                        }
                    }
                    for (i in 0 until NUM_T_CHANNELS) {
                        slaveRamp[i].init(
                            pattern.length,
                            pattern.ratios[i],
                            randomPulseWidth(v.pulseWidth[i])
                        )
                    }
                    dividerPatternLength = pattern.length
                }
            }
        }
    }

    /** Process a block of samples.
     *  @param useExternalClock whether to derive timing from external clock
     *  @param externalClock gate flags per sample
     *  @param ramps output ramp arrays (external, master, slave[0], slave[1])
     *  @param gate output gate arrays (interleaved: [gate_ch0, gate_ch1] per sample)
     *  @param size number of samples */
    fun process(
        useExternalClock: Boolean,
        externalClock: IntArray,
        ramps: Ramps,
        gate: BooleanArray,
        size: Int
    ) {
        var reset = false
        process(useExternalClock, externalClock, ramps, gate, size, reset = { reset })
    }

    fun process(
        useExternalClock: Boolean,
        externalClock: IntArray,
        ramps: Ramps,
        gate: BooleanArray,
        size: Int,
        reset: () -> Boolean
    ) {
        val internalFrequency: Float

        if (useExternalClock) {
            if (!this.useExternalClock) {
                rampExtractor.reset()
            }
            val ratio = rateQuantizer.let { rq ->
                val idx = rq.process((1.05f * rate / 96f + 0.5f).coerceIn(0f, 1f))
                inputDividerRatios[idx].copy()
            }
            when (range) {
                Range.RANGE_0_25X -> ratio.q *= 4
                Range.RANGE_4X -> ratio.p *= 4
                Range.RANGE_1X -> {}
            }
            ratio.simplify(2)
            rampExtractor.process(ratio, true, externalClock, ramps.external, 0, size)
            internalFrequency = 0f
        } else {
            val rateMul = when (range) {
                Range.RANGE_4X -> 8f
                Range.RANGE_0_25X -> 0.5f
                Range.RANGE_1X -> 2f
            }
            internalFrequency = rateMul * oneHertz * DspUtil.semitonesToRatio(rate)
        }

        this.useExternalClock = useExternalClock

        if (reset()) {
            for (i in 0 until NUM_T_CHANNELS) {
                slaveRamp[i].reset()
            }
            sequence.reset()
            dividerPatternLength = 0
            drumPatternStep = DRUM_PATTERN_SIZE
            if (model != Model.DIVIDER) {
                val rv = RandomVector()
                sequence.nextVector(randomVectorArray, randomVectorArray.size)
                rv.fromArray(randomVectorArray)
                configureSlaveRamps(rv)
            }
        }

        var gateIdx = 0
        for (s in 0 until size) {
            val frequency = if (useExternalClock) {
                var f = ramps.external[s] - previousExternalRampValue
                if (f < 0f) f += 1f
                f
            } else {
                internalFrequency
            }

            val jitteryFrequency = frequency * jitterMultiplier
            masterPhase += jitteryFrequency
            phaseDifference += frequency - jitteryFrequency

            if (masterPhase > 1f) {
                masterPhase -= 1f
                val rv = RandomVector()
                sequence.nextVector(randomVectorArray, randomVectorArray.size)
                rv.fromArray(randomVectorArray)

                val jitterAmount = jitter * jitter * jitter * jitter * 36f
                val x = Distributions.fastBetaDistributionSample(rv.jitter)
                var multiplier = DspUtil.semitonesToRatio((x * 2f - 1f) * jitterAmount)

                // Self-correcting jitter: catch up with the straight clock
                multiplier *= if (phaseDifference > 0f) {
                    1f + phaseDifference
                } else {
                    1f / (1f - phaseDifference)
                }

                jitterMultiplier = multiplier
                configureSlaveRamps(rv)
            }

            if (internalFrequency != 0f) {
                ramps.external[s] = masterPhase
            }

            previousExternalRampValue = ramps.external[s]
            ramps.master[s] = masterPhase

            for (j in 0 until NUM_T_CHANNELS) {
                slaveRamp[j].process(frequency * jitterMultiplier)
                ramps.slave[j][s] = slaveRamp[j].outputPhase
                gate[gateIdx++] = slaveRamp[j].outputGate
            }
        }
    }

    /** Ramp buffer container for T-generator output. */
    class Ramps(size: Int) {
        val external = FloatArray(size)
        val master = FloatArray(size)
        val slave = Array(NUM_T_CHANNELS) { FloatArray(size) }
    }

    /** Divider pattern: ratios for each T channel + pattern length. */
    data class DividerPattern(
        val ratios: Array<Ratio>,
        val length: Int
    )

    companion object {
        const val NUM_T_CHANNELS = 2
        const val MARKOV_HISTORY_SIZE = 16
        const val NUM_DRUM_PATTERNS = 18
        const val DRUM_PATTERN_SIZE = 8
        const val NUM_DIVIDER_PATTERNS = 17
        const val NUM_INPUT_DIVIDER_RATIOS = 9

        val dividerPatterns = arrayOf(
            DividerPattern(arrayOf(Ratio(1,1), Ratio(1,1)), 1),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(2,1)), 1),
            DividerPattern(arrayOf(Ratio(1,2), Ratio(1,1)), 2),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(4,1)), 1),
            DividerPattern(arrayOf(Ratio(1,2), Ratio(2,1)), 2),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(3,2)), 2),
            DividerPattern(arrayOf(Ratio(1,4), Ratio(4,1)), 4),
            DividerPattern(arrayOf(Ratio(1,4), Ratio(2,1)), 4),
            DividerPattern(arrayOf(Ratio(1,2), Ratio(3,2)), 2),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(8,1)), 1),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(3,1)), 1),
            DividerPattern(arrayOf(Ratio(1,3), Ratio(1,1)), 3),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(5,4)), 4),
            DividerPattern(arrayOf(Ratio(1,2), Ratio(5,4)), 4),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(6,1)), 1),
            DividerPattern(arrayOf(Ratio(1,3), Ratio(2,1)), 3),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(16,1)), 1),
        )

        val fixedDividerPatterns = arrayOf(
            DividerPattern(arrayOf(Ratio(8,1), Ratio(1,8)), 8),
            DividerPattern(arrayOf(Ratio(6,1), Ratio(1,6)), 6),
            DividerPattern(arrayOf(Ratio(4,1), Ratio(1,4)), 4),
            DividerPattern(arrayOf(Ratio(3,1), Ratio(1,3)), 3),
            DividerPattern(arrayOf(Ratio(2,1), Ratio(1,2)), 2),
            DividerPattern(arrayOf(Ratio(3,2), Ratio(2,3)), 6),
            DividerPattern(arrayOf(Ratio(4,3), Ratio(3,4)), 12),
            DividerPattern(arrayOf(Ratio(5,4), Ratio(4,5)), 20),
            DividerPattern(arrayOf(Ratio(1,1), Ratio(1,1)), 1),
            DividerPattern(arrayOf(Ratio(4,5), Ratio(5,4)), 20),
            DividerPattern(arrayOf(Ratio(3,4), Ratio(4,3)), 12),
            DividerPattern(arrayOf(Ratio(2,2), Ratio(3,2)), 6),
            DividerPattern(arrayOf(Ratio(1,2), Ratio(2,1)), 2),
            DividerPattern(arrayOf(Ratio(1,3), Ratio(3,1)), 3),
            DividerPattern(arrayOf(Ratio(1,4), Ratio(4,1)), 4),
            DividerPattern(arrayOf(Ratio(1,6), Ratio(6,1)), 6),
            DividerPattern(arrayOf(Ratio(1,8), Ratio(8,1)), 8),
        )

        val inputDividerRatios = arrayOf(
            Ratio(1,4), Ratio(1,3), Ratio(1,2), Ratio(2,3),
            Ratio(1,1), Ratio(3,2), Ratio(2,1), Ratio(3,1), Ratio(4,1),
        )

        val drumPatterns: Array<IntArray> = arrayOf(
            intArrayOf(1,0,0,0, 2,0,0,0),
            intArrayOf(0,0,1,0, 2,0,0,0),
            intArrayOf(1,0,1,0, 2,0,0,0),
            intArrayOf(0,0,1,0, 2,0,0,2),
            intArrayOf(1,0,1,0, 2,0,1,0),
            intArrayOf(0,2,1,0, 2,0,0,2),
            intArrayOf(1,0,0,0, 2,0,1,0),
            intArrayOf(0,2,1,0, 2,0,1,2),
            intArrayOf(1,0,0,1, 2,0,0,0),
            intArrayOf(0,2,1,1, 2,0,1,2),
            intArrayOf(1,0,0,1, 2,0,1,0),
            intArrayOf(0,2,1,1, 2,2,1,2),
            intArrayOf(1,0,0,1, 2,0,1,2),
            intArrayOf(0,2,0,1, 2,0,1,2),
            intArrayOf(1,0,1,1, 2,0,1,2),
            intArrayOf(2,0,1,2, 0,1,2,0),
            intArrayOf(1,2,1,1, 2,0,1,2),
            intArrayOf(2,0,1,2, 0,1,2,2),
        )
    }
}
