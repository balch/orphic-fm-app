package org.balch.orpheus.core.audio.dsp

/**
 * Desktop [OutputCapture] over the native record ring. A separate class because
 * [AudioEngine.start] and [AudioEngine.stop] would clash with the capture start and stop.
 * The bridge is a stateless wrapper around the one native engine, so a second instance is safe.
 */
class NativeOutputCapture(private val bridge: DesktopDspBridge = DesktopDspBridge()) : OutputCapture {
    override val sampleRate: Int = 48000

    override fun start() = bridge.nativeStartRecording()
    override fun stop() = bridge.nativeStopRecording()
    override fun drain(into: FloatArray): Int = bridge.nativeDrainRecording(into)
    override fun droppedFrames(): Long = bridge.nativeRecordDroppedFrames()
}
