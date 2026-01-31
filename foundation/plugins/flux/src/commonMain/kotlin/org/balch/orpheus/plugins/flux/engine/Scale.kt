package org.balch.orpheus.plugins.flux.engine

/**
 * Scale degree with voltage and weight.
 */
data class Degree(
    val voltage: Float,
    val weight: Int  // 0-255, higher = more important note
)

/**
 * Musical scale definition.
 */
data class Scale(
    val baseInterval: Float,  // Usually 1.0 for chromatic octaves
    val degrees: List<Degree>
) {
    /**
     * Get the voltage for a given cell index (can be outside 0..numDegrees-1).
     */
    fun cellVoltage(i: Int): Float {
        val transposition = (i / degrees.size).toFloat() * baseInterval
        return degrees[i % degrees.size].voltage + transposition
    }
    
    companion object {
        const val MAX_DEGREES = 16
        
        /**
         * Chromatic scale (all semitones, 0V = C)
         */
        fun chromatic(): Scale {
            val degrees = (0 until 12).map { i ->
                Degree(
                    voltage = i * (1.0f / 12.0f),
                    weight = 128  // All equal weight
                )
            }
            return Scale(1.0f, degrees)
        }
        
        /**
         * C Major scale with weighted degrees
         * (C=255, G=224, E=192, etc.)
         */
        fun major(): Scale {
            val weights = intArrayOf(255, 16, 128, 16, 192, 64, 8, 224, 16, 96, 32, 160)
            val degrees = (0 until 12).map { i ->
                Degree(
                    voltage = i * (1.0f / 12.0f),
                    weight = weights[i]
                )
            }
            return Scale(1.0f, degrees)
        }
        
        /**
         * C Minor scale
         */
        fun minor(): Scale {
            val weights = intArrayOf(255, 16, 16, 192, 16, 128, 16, 224, 64, 16, 96, 32)
            val degrees = (0 until 12).map { i ->
                Degree(
                    voltage = i * (1.0f / 12.0f),
                    weight = weights[i]
                )
            }
            return Scale(1.0f, degrees)
        }
        
        /**
         * Pentatonic scale (C D E G A)
         */
        fun pentatonic(): Scale {
            val semitones = intArrayOf(0, 2, 4, 7, 9)  // C D E G A
            val degrees = semitones.map { i ->
                Degree(
                    voltage = i * (1.0f / 12.0f),
                    weight = 255
                )
            }
            return Scale(1.0f, degrees)
        }
        
        /**
         * Phrygian scale
         */
        fun phrygian(): Scale {
            val weights = intArrayOf(255, 224, 16, 192, 16, 128, 16, 160, 64, 96, 16, 32)
            val degrees = (0 until 12).map { i ->
                Degree(
                    voltage = i * (1.0f / 12.0f),
                    weight = weights[i]
                )
            }
            return Scale(1.0f, degrees)
        }
        
        /**
         * Whole tone scale
         */
        fun wholeTone(): Scale {
            val semitones = intArrayOf(0, 2, 4, 6, 8, 10)  // C D E F# G# A#
            val degrees = semitones.map { i ->
                Degree(
                    voltage = i * (1.0f / 12.0f),
                    weight = 255
                )
            }
            return Scale(1.0f, degrees)
        }
    }
}
