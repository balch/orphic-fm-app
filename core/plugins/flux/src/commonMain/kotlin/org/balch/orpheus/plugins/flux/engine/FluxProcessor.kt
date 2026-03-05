// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

import kotlin.math.pow

/** Flux processor: block-based Marbles engine.
 *
 *  Integrates TGenerator (timing/gates), XYGenerator-style output channels
 *  (voltage generation with beta distribution, quantization, lag processing),
 *  and ramp infrastructure for per-sample phase tracking.
 *
 *  All buffer parameters are Float-native for zero-copy on Android (Oboe).
 *
 *  Call [process] for block-based audio-rate processing (preferred),
 *  or [tick]/[tickClockOff] for backwards-compatible event-driven usage. */
class FluxProcessor(private val sampleRate: Float) {
    private val randomStream = RandomStream()

    // X-section: 3 output channels with independent random sequences
    private val randomSequences = Array(NUM_X_CHANNELS) { RandomSequence() }
    private val outputChannels = Array(NUM_X_CHANNELS) { OutputChannel() }

    // T-section
    private val tGenerator = TGenerator()

    // Ramp infrastructure for X clock source
    private val xRampExtractor = RampExtractor()
    private val xRampDivider = RampDivider()

    // Scales (matches Marbles preset order)
    private val scales = arrayOf(
        Scale.major(),
        Scale.minor(),
        Scale.pentatonic(),
        Scale.phrygian(),
        Scale.wholeTone(),
        Scale.chromatic()
    )

    // Parameters
    private var spread = 0.5f
    private var bias = 0.5f
    private var steps = 0.5f
    private var dejaVu = 0.0f
    private var length = 8
    private var scaleIndex = 0
    private var rate = 0.5f
    private var jitter = 0.0f
    private var probability = 0.5f
    private var tModel = TGenerator.Model.COMPLEMENTARY_BERNOULLI
    private var tRange = TGenerator.Range.RANGE_1X
    private var pulseWidth = 0.5f
    private var pulseWidthStd = 0.0f
    private var controlMode = ControlMode.IDENTICAL
    private var voltageRange = VoltageRange.FULL
    private var mix = 0.0f

    // Output state (for tick() compatibility and getters)
    private var outX1 = 0.5f
    private var outX2 = 0.5f
    private var outX3 = 0.5f
    private var outT1 = 0.0f
    private var outT2 = 0.0f
    private var outT3 = 0.0f

    // Clock edge detection for tick() compatibility
    private var previousGateFlags = GateFlags.LOW

    // Lazy-allocated buffers for block processing
    private var blockSize = 0
    private var tRamps: TGenerator.Ramps? = null
    private var tGate: BooleanArray? = null
    private var xRamps: Array<FloatArray>? = null
    private var xOutput: FloatArray? = null
    private var clockFlags: IntArray? = null

    // Correlated sequence hashes (from x_y_generator.cc)
    private val xHashes = intArrayOf(0, 0xbeca55e5.toInt(), 0xf0cacc1a.toInt())

    // Pre-allocated scratch buffers for tick()/tickClockOff() compat API
    private val tickClock = FloatArray(1)
    private val tickOx1 = FloatArray(1)
    private val tickOx2 = FloatArray(1)
    private val tickOx3 = FloatArray(1)
    private val tickOt1 = FloatArray(1)
    private val tickOt2 = FloatArray(1)
    private val tickOt3 = FloatArray(1)

    init {
        for (i in 0 until NUM_X_CHANNELS) {
            randomSequences[i].init(randomStream)
            outputChannels[i].init()
            for (s in scales.indices) {
                outputChannels[i].loadScale(s, scales[s])
            }
        }
        tGenerator.init(sampleRate)
        xRampExtractor.init(8000f / sampleRate)
        xRampDivider.init()
    }

    /** Ensure internal buffers are at least [size] samples. */
    private fun ensureBuffers(size: Int) {
        if (size > blockSize) {
            blockSize = size
            tRamps = TGenerator.Ramps(size)
            tGate = BooleanArray(size * TGenerator.NUM_T_CHANNELS)
            xRamps = Array(NUM_X_CHANNELS) { FloatArray(size) }
            xOutput = FloatArray(size * NUM_X_CHANNELS)
            clockFlags = IntArray(size)
        }
    }

    /** Block-based processing: the preferred entry point.
     *
     *  @param clockIn per-sample clock input (analog signal, >0.1 = high)
     *  @param outputX1 output buffer for X1 voltage
     *  @param outputX2 output buffer for X2 voltage
     *  @param outputX3 output buffer for X3 voltage
     *  @param outputT1 output buffer for T1 gate
     *  @param outputT2 output buffer for T2 gate
     *  @param outputT3 output buffer for T3 gate
     *  @param start start index in buffers
     *  @param size number of samples to process */
    fun process(
        clockIn: FloatArray,
        outputX1: FloatArray,
        outputX2: FloatArray,
        outputX3: FloatArray,
        outputT1: FloatArray,
        outputT2: FloatArray,
        outputT3: FloatArray,
        start: Int,
        size: Int
    ) {
        ensureBuffers(size)

        val flags = clockFlags!!
        val tR = tRamps!!
        val tG = tGate!!
        val xR = xRamps!!
        val xOut = xOutput!!

        // 1. Convert analog clock to GateFlags per sample
        for (i in 0 until size) {
            val high = clockIn[start + i] > 0.1f
            previousGateFlags = GateFlags.extract(previousGateFlags, high)
            flags[i] = previousGateFlags
        }

        // 2. Configure and run TGenerator
        tGenerator.setModel(tModel)
        tGenerator.setRange(tRange)
        // Map 0-1 UI rate to engine range: 0→slowest division, 0.5→1:1, 1→fastest multiply
        tGenerator.setRate((rate - 0.5f) * 96f)
        tGenerator.setBias(probability)
        tGenerator.setJitter(jitter)
        tGenerator.setDejaVu(dejaVu)
        tGenerator.setLength(length)
        tGenerator.setPulseWidthMean(pulseWidth)
        tGenerator.setPulseWidthStd(pulseWidthStd)

        tGenerator.process(
            useExternalClock = true,
            externalClock = flags,
            ramps = tR,
            gate = tG,
            size = size
        )

        // 3. Map T ramps to X channels based on clock source
        // Default: T1->X1, T2(master)->X2, T3->X3
        xR[0] = tR.slave[0]
        xR[1] = tR.master
        xR[2] = tR.slave[1]

        // 4. Configure and run output channels
        // Voltage range controls octave span (no negative offset — keeps output positive)
        val so = when (voltageRange) {
            VoltageRange.NARROW -> ScaleOffset(1f, 0f)    // 1 octave
            VoltageRange.POSITIVE -> ScaleOffset(2f, 0f)  // 2 octaves
            VoltageRange.FULL -> ScaleOffset(3f, 0f)      // 3 octaves
        }

        for (ch in 0 until NUM_X_CHANNELS) {
            val channel = outputChannels[ch]
            val amount = controlModeAmount(ch)

            channel.setSpread(0.5f + (spread - 0.5f) * amount)
            channel.setBias(0.5f + (bias - 0.5f) * amount)
            channel.setSteps(0.5f + (steps - 0.5f) * amount)
            channel.setScaleIndex(scaleIndex)
            channel.setScaleOffset(so)

            // Correlated sequence replay for channels 1,2
            val sequence: RandomSequence
            if (ch > 0) {
                sequence = randomSequences[0]
                sequence.replayPseudoRandom(xHashes[ch].toUInt())
            } else {
                sequence = randomSequences[0]
                sequence.record()
                sequence.setDejaVu(dejaVu)
                sequence.setLength(length)
            }

            channel.process(
                randomSequence = sequence,
                phase = xR[ch],
                phaseOffset = 0,
                output = xOut,
                outputOffset = ch,
                size = size,
                stride = NUM_X_CHANNELS
            )
        }

        // 5. Copy outputs to buffers
        // X outputs: convert V/octave to frequency ratio for multiplicative CV path.
        // Voice computes: baseFreq + baseFreq * cvInput = baseFreq * (1 + cvInput)
        // So cvInput = 2^(voltage) - 1 gives: baseFreq * 2^(voltage) = correct exponential pitch
        // Mix scales the raw voltage before exp conversion for perceptually linear control:
        //   2^(V * mix) - 1  →  at mix=0.5, a 3-octave range becomes 1.5 octaves
        // Clamp voltage to [-1, 4] octaves before exp to prevent blowout (2^4 = 16x max)
        val m = mix
        for (i in 0 until size) {
            val idx = start + i
            val v1 = (xOut[i * NUM_X_CHANNELS] * m).coerceIn(-1f, MAX_OCTAVES)
            val v2 = (xOut[i * NUM_X_CHANNELS + 1] * m).coerceIn(-1f, MAX_OCTAVES)
            val v3 = (xOut[i * NUM_X_CHANNELS + 2] * m).coerceIn(-1f, MAX_OCTAVES)
            outputX1[idx] = 2f.pow(v1) - 1f
            outputX2[idx] = 2f.pow(v2) - 1f
            outputX3[idx] = 2f.pow(v3) - 1f

            // T gates from slave ramps
            val gateBase = i * TGenerator.NUM_T_CHANNELS
            outputT1[idx] = if (tG[gateBase]) 1f else 0f
            outputT2[idx] = if (tR.master[i] < pulseWidth) 1f else 0f
            outputT3[idx] = if (tG[gateBase + 1]) 1f else 0f
        }

        // Update cached output state for getters (raw V/octave for internal use)
        val lastIdx = size - 1
        outX1 = xOut[lastIdx * NUM_X_CHANNELS]
        outX2 = xOut[lastIdx * NUM_X_CHANNELS + 1]
        outX3 = xOut[lastIdx * NUM_X_CHANNELS + 2]
        outT1 = if (tG[lastIdx * TGenerator.NUM_T_CHANNELS]) 1f else 0f
        outT2 = if (tR.master[lastIdx] < pulseWidth) 1f else 0f
        outT3 = if (tG[lastIdx * TGenerator.NUM_T_CHANNELS + 1]) 1f else 0f
    }

    /** Compute per-channel modulation amount based on control mode. */
    private fun controlModeAmount(channelIndex: Int): Float = when (controlMode) {
        ControlMode.IDENTICAL -> 1f
        ControlMode.BUMP -> if (channelIndex == NUM_X_CHANNELS / 2) 1f else -1f
        ControlMode.TILT -> 2f * channelIndex.toFloat() / (NUM_X_CHANNELS - 1).toFloat() - 1f
    }

    // --- Backwards-compatible event-driven API ---

    /** Generate the next voltage(s) when triggered by a gate/clock.
     *  Kept for non-JSyn platforms. For JSyn, use [process] instead. */
    fun tick() {
        tickClock[0] = 1f
        tickOx1[0] = 0f; tickOx2[0] = 0f; tickOx3[0] = 0f
        tickOt1[0] = 0f; tickOt2[0] = 0f; tickOt3[0] = 0f
        process(tickClock, tickOx1, tickOx2, tickOx3, tickOt1, tickOt2, tickOt3, 0, 1)
        outX1 = tickOx1[0]
        outX2 = tickOx2[0]
        outX3 = tickOx3[0]
        outT1 = tickOt1[0]
        outT2 = tickOt2[0]
        outT3 = tickOt3[0]
    }

    /** Called when clock is low/off to reset gates. */
    fun tickClockOff() {
        tickClock[0] = 0f
        tickOx1[0] = 0f; tickOx2[0] = 0f; tickOx3[0] = 0f
        tickOt1[0] = 0f; tickOt2[0] = 0f; tickOt3[0] = 0f
        process(tickClock, tickOx1, tickOx2, tickOx3, tickOt1, tickOt2, tickOt3, 0, 1)
    }

    // --- Parameter setters ---

    fun setSpread(spread: Float) { this.spread = spread.coerceIn(0f, 1f) }
    fun setBias(bias: Float) { this.bias = bias.coerceIn(0f, 1f) }
    fun setSteps(steps: Float) { this.steps = steps.coerceIn(0f, 1f) }

    fun setDejaVu(dejaVu: Float) {
        this.dejaVu = dejaVu.coerceIn(0f, 1f)
        for (seq in randomSequences) seq.setDejaVu(dejaVu)
    }

    fun setLength(length: Int) {
        this.length = length.coerceIn(1, RandomSequence.DEJA_VU_BUFFER_SIZE)
        for (seq in randomSequences) seq.setLength(this.length)
    }

    fun setScale(index: Int) {
        scaleIndex = index.coerceIn(0, scales.size - 1)
    }

    fun setRate(rate: Float) { this.rate = rate.coerceIn(0f, 1f) }
    fun setJitter(jitter: Float) { this.jitter = jitter.coerceIn(0f, 1f) }
    fun setGateProbability(p: Float) { this.probability = p.coerceIn(0f, 1f) }
    fun setTModel(model: Int) { tModel = TGenerator.Model.entries[model.coerceIn(0, 6)] }
    fun setTRange(range: Int) { tRange = TGenerator.Range.entries[range.coerceIn(0, 2)] }
    fun setPulseWidth(pw: Float) { pulseWidth = pw.coerceIn(0f, 1f) }
    fun setPulseWidthStd(pw: Float) { pulseWidthStd = pw.coerceIn(0f, 1f) }
    fun setControlMode(mode: Int) { controlMode = ControlMode.entries[mode.coerceIn(0, 2)] }
    fun setVoltageRange(range: Int) { voltageRange = VoltageRange.entries[range.coerceIn(0, 2)] }
    fun setMix(mix: Float) { this.mix = mix.coerceIn(0f, 1f) }

    fun reset() {
        for (seq in randomSequences) seq.reset()
        outX1 = 0.5f
        outX2 = 0.5f
        outX3 = 0.5f
    }

    companion object {
        const val NUM_X_CHANNELS = 3
        private const val MAX_OCTAVES = 4f // Safety clamp: max 4 octaves above base (16x freq)
    }
}

enum class VoltageRange {
    NARROW,    // 1 octave
    POSITIVE,  // 2 octaves
    FULL       // 3 octaves
}

enum class ControlMode {
    IDENTICAL,
    BUMP,
    TILT
}
