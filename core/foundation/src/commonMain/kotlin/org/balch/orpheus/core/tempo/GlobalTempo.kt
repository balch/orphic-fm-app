package org.balch.orpheus.core.tempo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.dsp.AudioEngine

/**
 * Centralized global tempo management for all time-synced modules.
 *
 * Provides BPM as a shared state that TidalScheduler, DrumBeatsViewModel,
 * and FluxViewModel all subscribe to, ensuring synchronized playback.
 *
 * BPM changes are forwarded to the C++ engine via [AudioEngine.setPort]
 * using the tempo plugin URI.
 *
 * Synchronization Strategy:
 * 1. DSP Modules: C++ engine handles audio-rate clock generation internally.
 * 2. CPU Modules (REPL/Drums):
 *    polls [AudioEngine.getCurrentTime] in a tight loop to align with the
 *    audio frame rate. The [bpm] property here drives the logic for both.
 */
@SingleIn(AppScope::class)
@Inject
class GlobalTempo(
    private val audioEngine: AudioEngine,
) {

    private val _bpm = MutableStateFlow(120.0)
    val bpm: StateFlow<Double> = _bpm.asStateFlow()

    /**
     * Set the global tempo in BPM (beats per minute).
     * Valid range: 60-200 BPM
     * Updates both the StateFlow and forwards the BPM to the C++ engine.
     */
    fun setBpm(bpm: Double) {
        val coercedBpm = bpm.coerceIn(60.0, 200.0)
        _bpm.value = coercedBpm
        updateClockFrequency(coercedBpm)
    }

    /**
     * Forward BPM to C++ engine clock.
     */
    private fun updateClockFrequency(bpm: Double) {
        audioEngine.setPort(TEMPO_URI, "bpm", bpm.toFloat())
    }

    /**
     * Get current BPM value.
     */
    fun getBpm(): Double = _bpm.value

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

    companion object {
        const val TEMPO_URI = "org.balch.orpheus.plugins.tempo"
    }
}
