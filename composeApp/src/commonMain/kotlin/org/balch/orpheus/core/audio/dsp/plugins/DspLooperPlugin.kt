package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory

/**
 * DSP Plugin for Native Audio Looper.
 * Wraps JsynLooperUnit.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspLooperPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    private val looper = dspFactory.createLooperUnit()
    
    // Internal proxies if needed, otherwise direct exposure
    // Let's use proxies to forward inputs cleanly
    private val inputLeftProxy = dspFactory.createPassThrough()
    private val inputRightProxy = dspFactory.createPassThrough()
    
    // Gate control via automation/manual
    // LooperUnit has recordGate/playGate inputs.

    override val audioUnits: List<AudioUnit> = listOf(
        looper,
        inputLeftProxy, inputRightProxy
    )

    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "inputLeft" to inputLeftProxy.input,
            "inputRight" to inputRightProxy.input,
            "recordGate" to looper.recordGate,
            "playGate" to looper.playGate
        )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "output" to looper.output,      // Left
            "outputRight" to looper.outputRight // Right
        )

    override fun initialize() {
        // Allocate buffer (e.g. 1 minute loop max)
        looper.allocate(60.0)
        
        // Wire stereo input directly
        inputLeftProxy.output.connect(looper.inputLeft)
        inputRightProxy.output.connect(looper.inputRight)
    }
    
    fun allocate(seconds: Double) {
        looper.allocate(seconds)
    }
    
    fun clear() {
        looper.clear()
    }
    
    fun setRecording(active: Boolean) {
        looper.setRecording(active)
    }
    
    fun setPlaying(active: Boolean) {
        looper.setPlaying(active)
    }
    
    fun getLoopDuration(): Double = looper.getLoopDuration()
    
    fun getPosition(): Float = looper.getPosition()
}
