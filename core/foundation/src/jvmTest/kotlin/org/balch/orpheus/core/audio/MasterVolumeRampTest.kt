package org.balch.orpheus.core.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.plugin.PortValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MasterVolumeRampTest {

    @Test
    fun `ramp from 1 to 0 across 1000ms in 100ms steps`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine(initial = 1.0f)
        val ramp = MasterVolumeRamp(engine)

        val job = launch(dispatcher) {
            ramp.rampMasterVolumeTo(target = 0f, durationMs = 1000L, stepMs = 100L)
        }
        advanceTimeBy(1L)
        assertEquals(1.0f, engine.volume, "first write should be initial value")

        advanceTimeBy(500L)
        assertTrue(abs(engine.volume - 0.5f) < 0.06f, "midpoint volume=${engine.volume}")

        advanceTimeBy(600L)
        job.join()
        assertEquals(0f, engine.volume)
    }

    @Test
    fun `ramp respects current value as the starting point`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine(initial = 0.5f)
        val ramp = MasterVolumeRamp(engine)
        val job = launch(dispatcher) {
            ramp.rampMasterVolumeTo(target = 0f, durationMs = 200L, stepMs = 100L)
        }
        advanceTimeBy(1L)
        assertEquals(0.5f, engine.volume)
        advanceTimeBy(250L)
        job.join()
        assertEquals(0f, engine.volume)
    }

    @Test
    fun `EASE_IN holds volume high through the midpoint`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine(initial = 1.0f)
        val ramp = MasterVolumeRamp(engine)
        val job = launch(dispatcher) {
            ramp.rampMasterVolumeTo(
                target = 0f,
                durationMs = 1000L,
                stepMs = 100L,
                curve = FadeCurve.EASE_IN,
            )
        }
        // After 500 ms (halfway): linear would be 0.5; ease-in (t²) is 0.75.
        advanceTimeBy(501L)
        assertTrue(
            abs(engine.volume - 0.75f) < 0.06f,
            "ease-in midpoint volume=${engine.volume} (expected ~0.75)",
        )
        // Past the duration: lands at 0 just like linear.
        advanceTimeBy(550L)
        job.join()
        assertEquals(0f, engine.volume)
    }
}

private class FakeEngine(initial: Float = 1.0f) : SynthEngine {
    @Volatile var volume: Float = initial

    override fun start() = Unit
    override fun stop() = Unit
    override fun setMasterVolume(amount: Float) { volume = amount }
    override fun getMasterVolume(): Float = volume

    // Stubs — never called by MasterVolumeRamp
    override fun setVoiceTune(index: Int, tune: Float) = Unit
    override fun setVoiceGate(index: Int, active: Boolean) = Unit
    override fun setVoiceFeedback(index: Int, amount: Float) = Unit
    override fun setVoiceFmDepth(index: Int, amount: Float) = Unit
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) = Unit
    override fun setDuoSharpness(duoIndex: Int, sharpness: Float) = Unit
    override fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) = Unit
    override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) = Unit
    override fun triggerDrum(type: Int, accent: Float) = Unit
    override fun setQuadPitch(quadIndex: Int, pitch: Float) = Unit
    override fun setQuadHold(quadIndex: Int, amount: Float) = Unit
    override fun setQuadVolume(quadIndex: Int, volume: Float) = Unit
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) = Unit
    override fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) = Unit
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) = Unit
    override fun getQuadPitch(quadIndex: Int): Float = 0f
    override fun getQuadHold(quadIndex: Int): Float = 0f
    override fun getQuadVolume(quadIndex: Int): Float = 0f
    override fun getQuadTriggerSource(quadIndex: Int): Int = 0
    override fun getQuadPitchSource(quadIndex: Int): Int = 0
    override fun getQuadEnvelopeTriggerMode(quadIndex: Int): Boolean = false
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) = Unit
    override fun setVoiceHold(index: Int, amount: Float) = Unit
    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) = Unit
    override fun setDrive(amount: Float) = Unit
    override fun setDistortionMix(amount: Float) = Unit
    override fun setDelayTime(index: Int, time: Float) = Unit
    override fun setDelayFeedback(amount: Float) = Unit
    override fun setDelayMix(amount: Float) = Unit
    override fun setDelayModDepth(index: Int, amount: Float) = Unit
    override fun setHyperLfoFreq(index: Int, frequency: Float) = Unit
    override fun setHyperLfoMode(mode: Int) = Unit
    override fun setHyperLfoLink(active: Boolean) = Unit
    override fun getHyperLfoFreq(index: Int): Float = 0f
    override fun getHyperLfoMode(): Int = 0
    override fun getHyperLfoLink(): Boolean = false
    override fun setDuoModSource(duoIndex: Int, source: ModSource) = Unit
    override fun setFmStructure(crossQuad: Boolean) = Unit
    override fun setTotalFeedback(amount: Float) = Unit
    override fun setVibrato(amount: Float) = Unit
    override fun setVoiceCoupling(amount: Float) = Unit
    override fun setBend(amount: Float) = Unit
    override fun getBend(): Float = 0f
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) = Unit
    override fun releaseStringBend(stringIndex: Int): Int = 0
    override fun setSlideBar(yPosition: Float, xPosition: Float) = Unit
    override fun releaseSlideBar() = Unit
    override fun resetStringBenders() = Unit
    override fun playTestTone(frequency: Float) = Unit
    override fun stopTestTone() = Unit
    override fun getPeak(): Float = 0f
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
    override val peakFlow: StateFlow<Float> get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val cpuLoadFlow: StateFlow<Float> get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val voiceLevelsFlow: StateFlow<FloatArray> get() = kotlinx.coroutines.flow.MutableStateFlow(FloatArray(8))
    override val lfoOutputFlow: StateFlow<Float> get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val lfoAOutputFlow: StateFlow<Float> get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val lfoBOutputFlow: StateFlow<Float> get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val masterLevelFlow: StateFlow<Float> get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val bendFlow: StateFlow<Float> get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean = false
    override fun getPluginPort(pluginUri: String, symbol: String): PortValue? = null
    override fun getVoiceTune(index: Int): Float = 0f
    override fun getVoiceFmDepth(index: Int): Float = 0f
    override fun getVoiceEnvelopeSpeed(index: Int): Float = 0f
    override fun getDuoSharpness(duoIndex: Int): Float = 0f
    override fun getDuoModSource(duoIndex: Int): ModSource = ModSource.OFF
    override fun getFmStructureCrossQuad(): Boolean = false
    override fun getTotalFeedback(): Float = 0f
    override fun getVibrato(): Float = 0f
    override fun getVoiceCoupling(): Float = 0f
    override fun getDelayTime(index: Int): Float = 0f
    override fun getDelayFeedback(): Float = 0f
    override fun getDelayMix(): Float = 0f
    override fun getDelayModDepth(index: Int): Float = 0f
    override fun getDrive(): Float = 0f
    override fun getDistortionMix(): Float = 0f
    override fun setVoicePan(index: Int, pan: Float) = Unit
    override fun getVoicePan(index: Int): Float = 0f
    override fun setMasterPan(pan: Float) = Unit
    override fun getMasterPan(): Float = 0f
    override fun setStereoMode(mode: StereoMode) = Unit
    override fun getStereoMode(): StereoMode = StereoMode.VOICE_PAN
    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) = Unit
    override fun clearParameterAutomation(controlId: String) = Unit
    override fun getDrumFrequency(type: Int): Float = 0f
    override fun getDrumTone(type: Int): Float = 0f
    override fun getDrumDecay(type: Int): Float = 0f
    override fun getDrumP4(type: Int): Float = 0f
    override fun getDrumP5(type: Int): Float = 0f
    override fun loadTtsAudio(samples: FloatArray, sampleRate: Int) = Unit
    override fun playTts() = Unit
    override fun stopTts() = Unit
    override fun isTtsPlaying(): Boolean = false
    override fun setLooperRecord(recording: Boolean) = Unit
    override fun setLooperPlay(playing: Boolean) = Unit
    override fun setLooperOverdub(overdub: Boolean) = Unit
    override fun setLooperQuantize(enabled: Boolean) = Unit
    override fun setLooperLevel(level: Float) = Unit
    override fun clearLooper() = Unit
    override fun getLooperPosition(): Float = 0f
    override fun getLooperDuration(): Double = 0.0
}
