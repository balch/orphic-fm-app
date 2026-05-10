package org.balch.orpheus.features.timer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.MasterVolumeRamp
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.plugin.PortValue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// ─── Fake SynthEngine ─────────────────────────────────────────────────────────

private class FakeSynthEngine : SynthEngine {
    private var _masterVolume: Float = 0.8f

    // Expose the backing field for assertions in tests
    val volumeLevel: Float get() = _masterVolume
    fun setInitialVolume(v: Float) { _masterVolume = v }

    override fun getMasterVolume(): Float = _masterVolume
    override fun setMasterVolume(amount: Float) { _masterVolume = amount }

    // Stubs — these should never be called in timer tests
    override fun start() = Unit
    override fun stop() = Unit
    override val hasNativeEngine: Boolean get() = false
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

// ─── Test Helpers ─────────────────────────────────────────────────────────────

private object FakeDispatcherProvider : org.balch.orpheus.core.coroutines.DispatcherProvider {
    override val main = kotlinx.coroutines.Dispatchers.Unconfined
    override val io = kotlinx.coroutines.Dispatchers.Unconfined
    override val default = kotlinx.coroutines.Dispatchers.Unconfined
    override val unconfined = kotlinx.coroutines.Dispatchers.Unconfined
}

private class FakeAppPreferencesRepository(
    private var prefs: org.balch.orpheus.core.preferences.AppPreferences =
        org.balch.orpheus.core.preferences.AppPreferences(),
) : org.balch.orpheus.core.preferences.BaseAppPreferencesRepository() {
    override suspend fun load() = prefs
    override suspend fun save(preferences: org.balch.orpheus.core.preferences.AppPreferences) {
        prefs = preferences
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun makeVm(
    engine: FakeSynthEngine = FakeSynthEngine(),
    manager: PlaybackLifecycleManager = PlaybackLifecycleManager(),
): TimerViewModel {
    // FeatureCoroutineScope uses Dispatchers.Main.immediate.
    // Tests set Main to a StandardTestDispatcher via @BeforeTest / setMain so that
    // virtual-time advancement via advanceTimeBy() controls the countdown.
    val scope = FeatureCoroutineScope()
    val mediaSessionStateManager = MediaSessionStateManager(
        org.balch.orpheus.core.coroutines.AppCoroutineScope(FakeDispatcherProvider)
    )
    val persistence = org.balch.orpheus.core.features.FeatureStatePersistence(
        appPreferencesRepository = FakeAppPreferencesRepository(),
        dispatcherProvider = FakeDispatcherProvider,
        scope = scope,
    )
    return TimerViewModel(engine, MasterVolumeRamp(engine), manager, mediaSessionStateManager, NoOpTimerWidgetNotifier(), scope, persistence)
}

// ─── Tests ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `countdown ticks every second`() = runTest {
        val vm = makeVm()

        vm.actions.onSetDuration(2.minutes) // 120 seconds
        vm.actions.onStart()

        advanceTimeBy(5_001L)

        assertEquals(115.seconds, vm.stateFlow.value.remainingTime)
        assertEquals(TimerStatus.RUNNING, vm.stateFlow.value.status)
    }

    @Test
    fun `stop cancels timer without affecting audio`() = runTest {
        val manager = PlaybackLifecycleManager()
        val vm = makeVm(manager = manager)

        var stopAllEmitted = false
        val collectJob = launch {
            manager.events.collect { event ->
                if (event is PlaybackLifecycleEvent.StopAll) stopAllEmitted = true
            }
        }

        vm.actions.onSetDuration(5.minutes) // 300 seconds
        vm.actions.onStart()
        advanceTimeBy(3_001L)
        vm.actions.onStop()

        assertEquals(TimerStatus.IDLE, vm.stateFlow.value.status)
        assertEquals(false, vm.stateFlow.value.showOverlay)
        assertEquals(false, stopAllEmitted)

        collectJob.cancel()
    }

    @Test
    fun `pause freezes countdown`() = runTest {
        val vm = makeVm()

        vm.actions.onSetDuration(5.minutes) // 300 seconds
        vm.actions.onStart()
        advanceTimeBy(3_001L)

        val remainingBeforePause = vm.stateFlow.value.remainingTime
        vm.actions.onPause()

        assertEquals(TimerStatus.PAUSED, vm.stateFlow.value.status)

        advanceTimeBy(10_000L) // time passes but countdown is paused

        assertEquals(remainingBeforePause, vm.stateFlow.value.remainingTime)
    }

    @Test
    fun `reset restores original duration`() = runTest {
        val vm = makeVm()

        vm.actions.onSetDuration(3.minutes) // 180 seconds
        vm.actions.onStart()
        advanceTimeBy(30_001L)

        assertTrue(vm.stateFlow.value.remainingTime < 180.seconds)

        vm.actions.onReset()

        assertEquals(180.seconds, vm.stateFlow.value.remainingTime)
        assertEquals(TimerStatus.IDLE, vm.stateFlow.value.status)
    }

    @Test
    fun `timer reaches zero then fades for 15 seconds`() = runTest {
        val engine = FakeSynthEngine().also { it.setInitialVolume(0.8f) }
        val manager = PlaybackLifecycleManager()
        val vm = makeVm(engine = engine, manager = manager)

        var stopAllEmitted = false
        val collectJob = launch {
            manager.events.collect { event ->
                if (event is PlaybackLifecycleEvent.StopAll) stopAllEmitted = true
            }
        }

        vm.actions.onSetDuration(1.minutes) // 60 seconds
        vm.actions.onStart()

        // Advance past the countdown
        advanceTimeBy(61_000L)

        assertEquals(TimerStatus.FADING, vm.stateFlow.value.status)

        // Advance partway into the 15s fade; progress should be less than 1
        advanceTimeBy(5_000L)
        val progressMidFade = vm.stateFlow.value.fadeProgress
        assertTrue(progressMidFade < 1.0f, "Fade progress should be decreasing")

        // Let the rest of the fade finish
        advanceTimeBy(11_000L)

        assertEquals(TimerStatus.FINISHED, vm.stateFlow.value.status)
        assertEquals(1.0f, vm.stateFlow.value.fadeProgress)
        // Auto-reset remaining back to 1 min
        assertEquals(1.minutes, vm.stateFlow.value.remainingTime)
        assertTrue(stopAllEmitted, "StopAll should have been emitted after fade completes")

        collectJob.cancel()
    }

    @Test
    fun `stopAll stops timer and emits StopAll`() = runTest {
        val manager = PlaybackLifecycleManager()
        val vm = makeVm(manager = manager)

        var stopAllCount = 0
        val collectJob = launch {
            manager.events.collect { event ->
                if (event is PlaybackLifecycleEvent.StopAll) stopAllCount++
            }
        }

        vm.actions.onSetDuration(5.minutes)
        vm.actions.onStart()
        advanceTimeBy(2_001L)
        vm.actions.onStopAll()
        advanceTimeBy(1L) // yield to let the collector coroutine process the event

        assertEquals(TimerStatus.IDLE, vm.stateFlow.value.status)
        assertEquals(1, stopAllCount)

        collectJob.cancel()
    }

    @Test
    fun `setDuration clamps to TimerLimits range`() = runTest {
        val vm = makeVm()

        // 300 min → clamps down to MaxDuration (4h 20m = 260 min).
        vm.actions.onSetDuration(300.minutes)
        assertEquals(TimerLimits.MaxDuration, vm.stateFlow.value.initialTime)
        assertEquals(TimerLimits.MaxDuration, vm.stateFlow.value.remainingTime)

        // Negative → clamps up to MinDuration (zero).
        vm.actions.onSetDuration((-5).minutes)
        assertEquals(TimerLimits.MinDuration, vm.stateFlow.value.initialTime)
        assertEquals(TimerLimits.MinDuration, vm.stateFlow.value.remainingTime)
    }

    // ─── Lifecycle: status transitions ────────────────────────────────────────

    @Test
    fun `start transitions status from IDLE to RUNNING and shows overlay`() = runTest {
        val vm = makeVm()

        assertEquals(TimerStatus.IDLE, vm.stateFlow.value.status)
        assertEquals(false, vm.stateFlow.value.showOverlay)

        vm.actions.onSetDuration(5.minutes)
        vm.actions.onStart()

        assertEquals(TimerStatus.RUNNING, vm.stateFlow.value.status)
        assertEquals(true, vm.stateFlow.value.showOverlay)
    }

    @Test
    fun `start while already RUNNING is a no-op`() = runTest {
        val vm = makeVm()

        vm.actions.onSetDuration(5.minutes)
        vm.actions.onStart()
        advanceTimeBy(2_001L)

        val remainingAfterFirstStart = vm.stateFlow.value.remainingTime
        vm.actions.onStart() // should be ignored
        advanceTimeBy(1L)

        assertEquals(TimerStatus.RUNNING, vm.stateFlow.value.status)
        assertEquals(remainingAfterFirstStart, vm.stateFlow.value.remainingTime)
    }

    @Test
    fun `pause then resume resumes countdown from where it was paused`() = runTest {
        val vm = makeVm()

        vm.actions.onSetDuration(5.minutes) // 300 seconds
        vm.actions.onStart()
        advanceTimeBy(3_001L) // tick 3 seconds

        vm.actions.onPause()
        assertEquals(TimerStatus.PAUSED, vm.stateFlow.value.status)

        val remainingAtPause = vm.stateFlow.value.remainingTime

        // Resume via onStart (resume action)
        vm.actions.onStart()
        assertEquals(TimerStatus.RUNNING, vm.stateFlow.value.status)

        // Countdown should continue from where it was paused
        advanceTimeBy(2_001L)
        assertEquals(remainingAtPause - 2.seconds, vm.stateFlow.value.remainingTime)
    }

    @Test
    fun `stop during FADING restores volume and transitions to IDLE`() = runTest {
        val engine = FakeSynthEngine().also { it.setInitialVolume(0.8f) }
        val vm = makeVm(engine = engine)

        vm.actions.onSetDuration(1.minutes) // 60 seconds
        vm.actions.onStart()

        // Advance past the countdown to reach FADING
        advanceTimeBy(61_000L)
        assertEquals(TimerStatus.FADING, vm.stateFlow.value.status)

        // Advance a few seconds into the fade so volume is reduced
        advanceTimeBy(3_000L)
        assertTrue(engine.volumeLevel < 0.8f, "Volume should be reduced during fade")

        // Stop while fading
        vm.actions.onStop()

        assertEquals(0.8f, engine.volumeLevel, "Volume should be restored to saved value")
        assertEquals(TimerStatus.IDLE, vm.stateFlow.value.status)
    }

    // ─── Widget command bus ──────────────────────────────────────────────────

    @Test
    fun `widget PLAY_PAUSE command starts idle timer`() = runTest {
        val vm = makeVm()
        advanceTimeBy(1L) // let the init collector coroutine start

        vm.actions.onSetDuration(5.minutes)
        TimerWidgetCommandBus.send(TimerWidgetCommand.PLAY_PAUSE)
        advanceTimeBy(1L)

        assertEquals(TimerStatus.RUNNING, vm.stateFlow.value.status)
    }

    @Test
    fun `widget PLAY_PAUSE command pauses running timer`() = runTest {
        val vm = makeVm()

        vm.actions.onSetDuration(5.minutes)
        vm.actions.onStart()
        advanceTimeBy(2_001L)

        TimerWidgetCommandBus.send(TimerWidgetCommand.PLAY_PAUSE)
        advanceTimeBy(1L)

        assertEquals(TimerStatus.PAUSED, vm.stateFlow.value.status)
    }

    @Test
    fun `widget STOP command stops running timer`() = runTest {
        val vm = makeVm()

        vm.actions.onSetDuration(5.minutes)
        vm.actions.onStart()
        advanceTimeBy(2_001L)

        TimerWidgetCommandBus.send(TimerWidgetCommand.STOP)
        advanceTimeBy(1L)

        assertEquals(TimerStatus.IDLE, vm.stateFlow.value.status)
    }

}
