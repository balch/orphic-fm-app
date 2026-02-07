package org.balch.orpheus.plugins.drum.engine

/**
 * FM Drum synthesizer ported from Mutable Instruments Peaks.
 * Features sine-wave FM with decaying envelopes, noise mixing, and overdrive.
 */
class FmDrum(private val sampleRate: Float = 44100f) {
    
    // Internal state
    private var phase: UInt = 0u
    private var fmEnvelopePhase: UInt = 0xFFFFFFFFu
    private var amEnvelopePhase: UInt = 0xFFFFFFFFu
    private var auxEnvelopePhase: UInt = 0xFFFFFFFFu
    
    private var previousSample: Int = 0
    
    // Parameters
    private var auxEnvelopeStrength: Int = 0
    private var frequency: Int = 0         // MIDI pitch << 7
    private var fmAmount: Int = 0          // FM modulation depth
    private var amDecay: Int = 0           // AM envelope decay rate index
    private var fmDecay: Int = 0           // FM envelope decay rate index
    private var noiseAmount: Int = 0       // Noise mix amount
    private var overdriveAmount: Int = 0   // Overdrive amount
    
    private var sdRange: Boolean = false

    fun init() {
        phase = 0u
        fmEnvelopePhase = 0xFFFFFFFFu
        amEnvelopePhase = 0xFFFFFFFFu
        auxEnvelopePhase = 0xFFFFFFFFu
        previousSample = 0
    }

    fun trigger() {
        fmEnvelopePhase = 0u
        amEnvelopePhase = 0u
        auxEnvelopePhase = 0u
    }

    /**
     * Process a single sample. Returns value in range [-1, 1].
     */
    fun process(
        trig: Boolean,
        accent: Float,
        f0: Float, // 0-1 normalized frequency
        tone: Float, // 0-1 normalized noise/overdrive
        decay: Float, // 0-1 normalized decay
        p4: Float, // 0-1 normalized FM amount
        p5: Float // unused in base FM drum but can be morph
    ): Float {
        if (trig) trigger()

        // If envelope is finished, return silence to avoid DC offset/drone bleeding into mix
        if (amEnvelopePhase == 0xFFFFFFFFu && !trig) {
            return 0f
        }
        
        // Map parameters
        setFrequency(f0)
        setFmAmount(p4)
        setDecay(decay)
        setNoise(tone)

        // 1. Update envelopes
        val amIncrement = computeEnvelopeIncrement(amDecay)
        val fmIncrement = computeEnvelopeIncrement(fmDecay)
        val auxIncrement = computeEnvelopeIncrement(16384) // Fixed fast decay for punch
        
        if (amEnvelopePhase < 0xFFFFFFFFu) {
            val next = amEnvelopePhase.toLong() + amIncrement
            amEnvelopePhase = if (next > 0xFFFFFFFFL) 0xFFFFFFFFu else next.toUInt()
        }
        if (fmEnvelopePhase < 0xFFFFFFFFu) {
            val next = fmEnvelopePhase.toLong() + fmIncrement
            fmEnvelopePhase = if (next > 0xFFFFFFFFL) 0xFFFFFFFFu else next.toUInt()
        }
        if (auxEnvelopePhase < 0xFFFFFFFFu) {
            val next = auxEnvelopePhase.toLong() + auxIncrement
            auxEnvelopePhase = if (next > 0xFFFFFFFFL) 0xFFFFFFFFu else next.toUInt()
        }

        // 2. Read envelope values from LUT
        val amValue = 65535 - readLut(FmDrumTables.ENV_EXPO, amEnvelopePhase)
        val fmValue = 65535 - readLut(FmDrumTables.ENV_EXPO, fmEnvelopePhase)
        val auxValue = 65535 - readLut(FmDrumTables.ENV_EXPO, auxEnvelopePhase)

        // If AM Envelope is effectively zero, return 0 to be safe
        if (amValue <= 0) {
            return 0f
        }

        // 3. Compute pitch and phase increment
        var pitch = frequency + (fmAmount * fmValue shr 16)
        // Reduced shift (was shr 10) to tame the attack pitch sweep into bass-thump territory
        pitch += (auxEnvelopeStrength * auxValue shr 13)
        
        val phaseIncrement = computePhaseIncrement(pitch)
        phase += phaseIncrement.toUInt()

        // 4. Generate sine wave with FM
        // Modulate phase with previous sample for simple self-FM/feedback if desired, 
        // but base Peaks FM uses a simpler approach.
        val sineIndex = (phase shr 22).toInt() and 1023
        var sample = FmDrumTables.SINE[sineIndex].toInt()

        // 5. Apply AM envelope
        sample = (sample * amValue) shr 16

        // 6. Apply Overdrive
        if (overdriveAmount > 0) {
            val overdriveIndex = ((sample + 32768) shr 6).coerceIn(0, 1023)
            val overdriven = FmDrumTables.OVERDRIVE[overdriveIndex].toInt()
            sample = sample + ((overdriven - sample) * overdriveAmount shr 15)
        }

        // 7. Add Noise
        if (noiseAmount > 0) {
            val noise = (kotlin.random.Random.nextInt(65536) - 32768)
            sample = sample + ((noise - sample) * noiseAmount shr 15)
        }

        previousSample = sample
        return sample / 32768f
    }

    private fun setFrequency(value: Float) {
        val f = (value * 65535).toInt()
        if (f <= 16384) {
            auxEnvelopeStrength = 1024
        } else if (f <= 32768) {
            auxEnvelopeStrength = 2048 - (f shr 4)
        } else {
            auxEnvelopeStrength = 0
        }
        frequency = (24 shl 7) + ((72 shl 7) * f shr 16)
    }

    private fun setFmAmount(value: Float) {
        fmAmount = (value * 65535).toInt() shr 2
    }

    private fun setDecay(value: Float) {
        val d = (value * 65535).toInt()
        amDecay = 16384 + (d shr 1)
        fmDecay = 8192 + (d shr 2)
    }

    private fun setNoise(value: Float) {
        val n = (value * 65535).toInt()
        noiseAmount = if (n >= 32768) ((n - 32768) * (n - 32768) shr 15) else 0
        noiseAmount = (noiseAmount shr 2) * 5
        overdriveAmount = if (n <= 32767) ((32767 - n) * (32767 - n) shr 14) else 0
    }

    private fun computePhaseIncrement(pitch: Int): Int {
        val index = (pitch shr 7).coerceIn(0, 95)
        val a = FmDrumTables.OSCILLATOR_INCREMENTS[index]
        val b = FmDrumTables.OSCILLATOR_INCREMENTS[index + 1]
        val frac = pitch and 0x7F
        return a + ((b - a) * frac shr 7)
    }

    private fun computeEnvelopeIncrement(time: Int): Int {
        val index = (time shr 8).coerceIn(0, 255)
        val a = FmDrumTables.ENV_INCREMENTS[index]
        val b = FmDrumTables.ENV_INCREMENTS[index + 1]
        val frac = time and 0xFF
        return a + (((b - a).toLong() * frac shr 8)).toInt()
    }

    private fun readLut(lut: ShortArray, phase: UInt): Int {
        val index = (phase shr 24).toInt()
        val a = lut[index].toInt() and 0xFFFF
        val b = lut[index + 1].toInt() and 0xFFFF
        val frac = (phase shr 16).toInt() and 0xFF
        return a + ((b - a) * frac shr 8)
    }
}
