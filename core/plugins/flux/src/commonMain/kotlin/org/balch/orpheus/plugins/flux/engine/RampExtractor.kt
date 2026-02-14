// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles ramp/ramp_extractor.cc.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

import kotlin.math.max
import kotlin.math.min

/** Beat-tracking algorithm converting gate pulses to continuous 0-1 ramps.
 *
 *  Uses concurrent prediction strategies:
 *  - Moving averages (slow and fast)
 *  - Trigram hash model
 *  - Periodicity detectors (periods 1-10)
 *
 *  The best-performing predictor is selected based on accuracy scoring. */
class RampExtractor {

    private data class Pulse(
        var onDuration: Int = 0,
        var totalDuration: Int = 0,
        var bucket: Int = 0,
        var pulseWidth: Float = 0f
    )

    private data class Prediction(
        var period: Float = 0f,
        var accuracy: Float = 0f
    )

    private companion object {
        const val HISTORY_SIZE = 16
        const val HASH_TABLE_SIZE = 256
        const val NUM_PREDICTORS = 13 // SLOW_MA, FAST_MA, HASH, PERIOD_1..10
        const val PREDICTOR_SLOW_MA = 0
        const val PREDICTOR_FAST_MA = 1
        const val PREDICTOR_HASH = 2
        const val PREDICTOR_PERIOD_BASE = 3
        const val LOG_ONE_FOURTH = 1.189207115f
        const val PULSE_WIDTH_TOLERANCE = 0.05f
        const val NUM_CONSISTENT_PULSES = 3
    }

    // History ring buffer
    private var currentPulse = 0
    private val history = Array(HISTORY_SIZE) { Pulse() }
    private var nextBucket = 48f

    // Prediction state
    private val predictionHashTable = FloatArray(HASH_TABLE_SIZE)
    private val predictedPeriod = FloatArray(NUM_PREDICTORS)
    private val predictionAccuracy = FloatArray(NUM_PREDICTORS)
    private var averagePulseWidth = 0f

    // Phase tracking
    private var trainPhase = 0f
    private var frequency = 0f
    private var maxOutputPhase = 0f
    private var maxTrainPhase = 0f
    private var resetFrequency = 0f
    private var targetFrequency = 0f
    private var lpCoefficient = 0f

    // Ratio tracking
    private var fRatio = 0f
    private var nextFRatio = 0f
    private var nextMaxTrainPhase = 0f
    private var resetCounter = 0
    private var resetInterval = 0

    // Audio rate detection
    private var audioRate = false
    private var numConsistentAudioRatePulses = 0
    private var maxFrequency = 0f
    private var audioRatePeriod = 0f
    private var audioRatePeriodHysteresis = 0f

    private var resetAtNextPulse = false

    fun init(maxFrequency: Float) {
        this.maxFrequency = maxFrequency
        audioRatePeriod = 1f / (100f / 32000f)
        audioRatePeriodHysteresis = audioRatePeriod
        reset()
    }

    fun reset() {
        audioRate = false
        numConsistentAudioRatePulses = 0
        trainPhase = 0f
        frequency = 0.0001f
        targetFrequency = frequency
        lpCoefficient = 0.5f
        nextMaxTrainPhase = 0.999f
        maxTrainPhase = nextMaxTrainPhase
        nextFRatio = 1f
        fRatio = nextFRatio
        resetCounter = 1
        resetFrequency = 0f
        resetInterval = 32000 * 3
        resetAtNextPulse = false

        val p = Pulse(onDuration = 2000, totalDuration = 4000, bucket = 1, pulseWidth = 0.5f)
        for (i in 0 until HISTORY_SIZE) {
            history[i] = p.copy()
        }

        currentPulse = 0
        nextBucket = 48f
        averagePulseWidth = 0f
        predictedPeriod.fill(4000f)
        predictionAccuracy.fill(0f)
        predictionHashTable.fill(0f)
    }

    private fun isWithinTolerance(x: Float, y: Float, error: Float): Boolean {
        return x >= y * (1f - error) && x <= y * (1f + error)
    }

    private fun computeAveragePulseWidth(tolerance: Float): Float {
        var sum = 0f
        for (i in 0 until HISTORY_SIZE) {
            if (!isWithinTolerance(
                    history[i].pulseWidth,
                    history[currentPulse].pulseWidth,
                    tolerance
                )) {
                return 0f
            }
            sum += history[i].pulseWidth
        }
        return sum / HISTORY_SIZE
    }

    private fun predictNextPeriod(): Prediction {
        val lastPeriod = history[currentPulse].totalDuration.toFloat()
        var bestPredictor = PREDICTOR_FAST_MA

        for (i in PREDICTOR_FAST_MA until NUM_PREDICTORS) {
            val error = (predictedPeriod[i] - lastPeriod) / (lastPeriod + 0.01f)
            val accuracy = 1f / (1f + 100f * error * error)
            predictionAccuracy[i] = DspUtil.slope(predictionAccuracy[i], accuracy, 0.1f, 0.5f)

            when (i) {
                PREDICTOR_SLOW_MA ->
                    predictedPeriod[i] = DspUtil.onePole(predictedPeriod[i], lastPeriod, 0.1f)
                PREDICTOR_FAST_MA ->
                    predictedPeriod[i] = DspUtil.onePole(predictedPeriod[i], lastPeriod, 0.5f)
                PREDICTOR_HASH -> {
                    val t2 = (currentPulse - 2 + HISTORY_SIZE) % HISTORY_SIZE
                    val t1 = (currentPulse - 1 + HISTORY_SIZE) % HISTORY_SIZE
                    val t0 = currentPulse

                    var hash = history[t1].bucket + 17 * history[t2].bucket
                    predictionHashTable[hash % HASH_TABLE_SIZE] = DspUtil.onePole(
                        predictionHashTable[hash % HASH_TABLE_SIZE], lastPeriod, 0.5f
                    )

                    hash = history[t0].bucket + 17 * history[t1].bucket
                    predictedPeriod[i] = predictionHashTable[hash % HASH_TABLE_SIZE]
                    if (predictedPeriod[i] == 0f) {
                        predictedPeriod[i] = lastPeriod
                    }
                }
                else -> {
                    // Periodicity detector
                    val candidatePeriod = i - PREDICTOR_PERIOD_BASE + 1
                    val t = currentPulse + 1 + HISTORY_SIZE - candidatePeriod
                    predictedPeriod[i] = history[t % HISTORY_SIZE].totalDuration.toFloat()
                }
            }

            if (predictionAccuracy[i] >= predictionAccuracy[bestPredictor]) {
                bestPredictor = i
            }
        }

        return Prediction(predictedPeriod[bestPredictor], predictionAccuracy[bestPredictor])
    }

    fun process(
        ratio: Ratio,
        alwaysRampToMaximum: Boolean,
        gateFlags: IntArray,
        ramp: FloatArray,
        rampOffset: Int,
        size: Int
    ) {
        var doReset = false
        processInternal(ratio, alwaysRampToMaximum, gateFlags, ramp, rampOffset, size) { doReset }
    }

    internal fun processInternal(
        ratio: Ratio,
        alwaysRampToMaximum: Boolean,
        gateFlags: IntArray,
        ramp: FloatArray,
        rampOffset: Int,
        size: Int,
        resetFlag: () -> Boolean
    ) {
        if (resetFlag()) {
            resetAtNextPulse = true
        }

        for (s in 0 until size) {
            val flags = gateFlags[s]

            // Rising edge: we are done with the previous pulse
            if (flags and GateFlags.RISING != 0) {
                val p = history[currentPulse]
                val recordPulse = p.totalDuration < resetInterval

                if (!recordPulse) {
                    // Long pause - clock probably stopped and restarted
                    resetFrequency = 0f
                    trainPhase = 0f
                    resetCounter = ratio.q
                    resetInterval = 4 * p.totalDuration
                } else {
                    if (resetAtNextPulse) {
                        resetCounter = 1
                        resetAtNextPulse = false
                    }

                    val period = p.totalDuration.toFloat()
                    if (period <= audioRatePeriodHysteresis) {
                        numConsistentAudioRatePulses = min(
                            numConsistentAudioRatePulses + 1, NUM_CONSISTENT_PULSES
                        )
                        audioRatePeriodHysteresis = audioRatePeriod * 1.1f
                    } else {
                        numConsistentAudioRatePulses = 0
                        audioRatePeriodHysteresis = audioRatePeriod
                    }

                    audioRate = numConsistentAudioRatePulses == NUM_CONSISTENT_PULSES
                    if (audioRate) {
                        averagePulseWidth = 0f
                        val noGlide = fRatio != ratio.toFloat()
                        fRatio = ratio.toFloat()
                        val freq = 1f / period
                        targetFrequency = min(fRatio * freq, maxFrequency)
                        val upTolerance = (1.02f + 2f * freq) * frequency
                        val downTolerance = (0.98f - 2f * freq) * frequency
                        lpCoefficient = if (noGlide || targetFrequency > upTolerance ||
                            targetFrequency < downTolerance
                        ) 1f else min(period * 0.00001f, 0.1f)
                    } else {
                        p.pulseWidth = p.onDuration.toFloat() / period
                        averagePulseWidth = computeAveragePulseWidth(PULSE_WIDTH_TOLERANCE)
                        if (p.onDuration < 32) {
                            averagePulseWidth = 0f
                        }

                        val prediction = predictNextPeriod()
                        frequency = 1f / prediction.period

                        resetCounter--
                        if (resetCounter == 0) {
                            nextFRatio = ratio.toFloat() * SlaveRamp.MAX_RAMP_VALUE
                            nextMaxTrainPhase = ratio.q.toFloat()
                            if (alwaysRampToMaximum && trainPhase < maxTrainPhase) {
                                resetFrequency =
                                    (0.01f + maxTrainPhase - trainPhase) * 0.0625f
                            } else {
                                resetFrequency = 0f
                                trainPhase = 0f
                                fRatio = nextFRatio
                                maxTrainPhase = nextMaxTrainPhase
                            }
                            resetCounter = ratio.q
                        } else {
                            val expected = maxTrainPhase - resetCounter.toFloat()
                            val warp = expected - trainPhase + 1f
                            frequency *= max(warp, 0.01f)
                        }
                    }
                    resetInterval = max(4f / targetFrequency, 32000f * 3f).toInt()
                    currentPulse = (currentPulse + 1) % HISTORY_SIZE
                }
                history[currentPulse] = Pulse()
                nextBucket = 48f
            }

            // Update history buffer
            history[currentPulse].totalDuration++
            if (flags and GateFlags.HIGH != 0) {
                history[currentPulse].onDuration++
            }
            if (history[currentPulse].totalDuration.toFloat() >= nextBucket) {
                history[currentPulse].bucket++
                nextBucket *= LOG_ONE_FOURTH
            }

            // Pulse width correction on falling edge
            if (flags and GateFlags.FALLING != 0 && averagePulseWidth > 0f) {
                val tOn = history[currentPulse].onDuration.toFloat()
                val next = maxTrainPhase - resetCounter.toFloat() + 1f
                val pw = averagePulseWidth
                frequency = max((next - trainPhase), 0f) * pw / ((1f - pw) * tOn)
            }

            // Phase accumulation
            if (audioRate) {
                frequency = DspUtil.onePole(frequency, targetFrequency, lpCoefficient)
                trainPhase += frequency
                if (trainPhase >= 1f) {
                    trainPhase -= 1f
                }
                ramp[rampOffset + s] = trainPhase
            } else {
                if (resetFrequency != 0f) {
                    trainPhase += resetFrequency
                    if (trainPhase >= maxTrainPhase) {
                        trainPhase = 0f
                        resetFrequency = 0f
                        fRatio = nextFRatio
                        maxTrainPhase = nextMaxTrainPhase
                    }
                } else {
                    trainPhase += frequency
                    if (trainPhase >= maxTrainPhase) {
                        trainPhase = if (frequency == maxFrequency) {
                            trainPhase - maxTrainPhase
                        } else {
                            maxTrainPhase
                        }
                    }
                }

                var outputPhase = trainPhase * fRatio
                outputPhase -= outputPhase.toInt().toFloat()
                ramp[rampOffset + s] = outputPhase
            }
        }
    }
}
