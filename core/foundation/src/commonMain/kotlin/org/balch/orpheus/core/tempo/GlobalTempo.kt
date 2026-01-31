package org.balch.orpheus.core.tempo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.ClockUnit
import org.balch.orpheus.core.audio.dsp.DspFactory

/**
 * Centralized global tempo management for all time-synced modules.
 * 
 * Provides BPM as a shared state that TidalScheduler, DrumBeatsViewModel,
 * and FluxViewModel all subscribe to, ensuring synchronized playback.
 * 
 * Also provides a hardware-accurate ClockUnit that generates audio-rate
 * clock pulses for driving drum sequencers and other timing-critical modules.
 *
 * Synchronization Strategy:
 * 1. DSP Modules: Connect directly to [getClockOutput] for sample-accurate pulsing.
 * 2. CPU Modules (REPL/Drums):
 *    polls [AudioEngine.getCurrentTime] in a tight loop to align with the
 *    audio frame rate. The [bpm] property here drives the logic for both.
 */
@SingleIn(AppScope::class)
class GlobalTempo @Inject constructor(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) {

    private val _bpm = MutableStateFlow(120.0)
    val bpm: StateFlow<Double> = _bpm.asStateFlow()

    // Clock generator for audio-rate timing (24 PPQN standard)
    private val clockUnit: ClockUnit = dspFactory.createClockUnit().also { clock ->
        audioEngine.addUnit(clock)
        // Initialize clock frequency based on default BPM (120)
        // 120 BPM = 2 beats/sec, 24 PPQN = 48 Hz
        // Manually set initial freq to avoid accessing 'clockUnit' property before it is assigned
        val ppqn = 24
        val frequency = (120.0 / 60.0) * ppqn
        clock.frequency.set(frequency)
    }

    /**
     * Set the global tempo in BPM (beats per minute).
     * Valid range: 60-200 BPM
     * Updates both the StateFlow and the ClockUnit frequency.
     */
    fun setBpm(bpm: Double) {
        val coercedBpm = bpm.coerceIn(60.0, 200.0)
        _bpm.value = coercedBpm
        updateClockFrequency(coercedBpm)
    }

    /**
     * Update the ClockUnit frequency based on BPM.
     * Clock runs at 24 PPQN (pulses per quarter note).
     */
    private fun updateClockFrequency(bpm: Double) {
        // Formula: (BPM / 60) * PPQN
        // Example: 120 BPM = 2 beats/sec * 24 PPQN = 48 Hz
        val ppqn = 24
        val frequency = (bpm / 60.0) * ppqn
        clockUnit.frequency.set(frequency)
    }

    /**
     * Get current BPM value.
     */
    fun getBpm(): Double = _bpm.value

    /**
     * Get the master clock unit output.
     * Connect this to drum sequencers, REPL timing, or other modules
     * that need sample-accurate synchronization.
     */
    fun getClockOutput() = clockUnit.output

    /**
     * Set the clock pulse width (duty cycle).
     * @param width 0.01 - 0.99, where 0.5 = 50% duty cycle
     */
    fun setClockPulseWidth(width: Double) {
        clockUnit.pulseWidth.set(width.coerceIn(0.01, 0.99))
    }

    /**
     * Get cycles per second for TidalScheduler.
     * Tidal uses cycles (typically quarter notes at 4/4).
     */
    val cps: Double get() = _bpm.value / 60.0 / 4.0

    /**
     * Get milliseconds per quarter note step.
     */
    val msPerQuarterNote: Long get() = (60_000.0 / _bpm.value).toLong()

    /**
     * Get milliseconds per 16th note (common for sequencers).
     */
    val msPer16thNote: Long get() = (60_000.0 / _bpm.value / 4.0).toLong()

    /**
     * Get milliseconds per clock tick (24 PPQN standard).
     */
    val msPerClockTick: Long get() = (60_000.0 / _bpm.value / 24.0).toLong()

    /**
     * Get clock frequency in Hz for the current BPM.
     * Useful for external synchronization.
     */
    val clockFrequencyHz: Double get() = (_bpm.value / 60.0) * 24.0
}
