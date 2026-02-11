package org.balch.orpheus.core.audio.dsp

/**
 * Platform-independent audio engine interface.
 */
interface AudioEngine {
    /** Start audio processing */
    fun start()

    /** Stop audio processing */
    fun stop()

    /** Check if engine is running */
    val isRunning: Boolean

    /** Sample rate in Hz */
    val sampleRate: Int

    /** Add a unit to the synthesis graph */
    fun addUnit(unit: AudioUnit)

    /** Enable or disable a unit in the synthesis graph (disabled units output zero) */
    fun setUnitEnabled(unit: AudioUnit, enabled: Boolean)

    /** Master output - connect final audio here */
    val lineOutLeft: AudioInput
    val lineOutRight: AudioInput

    // Monitoring
    /** Get current CPU load (0.0 - 1.0) */
    fun getCpuLoad(): Float

    /** Get current audio time in seconds */
    fun getCurrentTime(): Double
}
