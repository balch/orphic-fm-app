package org.balch.orpheus.core.audio.dsp

/**
 * Minimal test stub for AudioEngine.
 * Just enough to construct GlobalTempo in tests.
 */
class TestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}
