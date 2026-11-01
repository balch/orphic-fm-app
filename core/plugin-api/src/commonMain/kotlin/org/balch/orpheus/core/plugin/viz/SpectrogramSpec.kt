package org.balch.orpheus.core.plugin.viz

/**
 * The rows `SynthEngine.spectrogramFlow` carries: [ROWS] dB levels of the master mix at
 * log-spaced frequencies from [F_MIN_HZ] (index 0) to [F_MAX_HZ].
 */
object SpectrogramSpec {
    const val ROWS = 256
    const val F_MIN_HZ = 50f
    const val F_MAX_HZ = 10_000f
}
