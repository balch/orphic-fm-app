package org.balch.orpheus.features.timer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.MasterVolumeRamp
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.MuteSink
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private class SingleDispatcher(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class StubMetadata : MetadataProducer {
    override val titleFlow = MutableStateFlow("Vibe")
    override val subtitleFlow = MutableStateFlow("Sub")
}

private class InMemoryPreferences : BaseAppPreferencesRepository() {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
}

/**
 * Sleep-timer expiry has to leave the app restartable: the fade stops playback,
 * and the next play() must reach Playing with master volume back where it was.
 *
 * Wires the real ricochet the expiry sets off — TimerViewModel emits StopAll,
 * SynthOrchestrator answers it with MediaSessionStateManager.clearAll(), and the
 * Pulsar bridge answers it with PlaybackController.pause() while re-asserting
 * Pulsar as an activity source. Those three fought each other on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerExpiryPlaybackTest {

    @BeforeTest fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `play after the timer expires resumes with master volume restored`() = runTest {
        val engine = FakeSynthEngine().also { it.setInitialVolume(0.8f) }
        val appScope = AppCoroutineScope(SingleDispatcher(UnconfinedTestDispatcher(testScheduler)))
        val stateManager = MediaSessionStateManager(appScope)
        val lifecycle = PlaybackLifecycleManager()
        val controller = PlaybackController(
            mediaSessionManager = MediaSessionManager(),
            mediaSessionStateManager = stateManager,
            playbackLifecycleManager = lifecycle,
            muteSink = MuteSink { },
            engagementTracker = DefaultEngagementTracker(),
            metadataProducer = StubMetadata(),
            scope = appScope,
        )
        // Real orchestrator: it is the StopAll -> clearAll() half of the ricochet.
        SynthOrchestrator(engine, lifecycle, stateManager, controller, appScope)
        // Stand-in for PulsarPlaybackBridge; features/pulsar is not on this classpath.
        appScope.launch {
            controller.state.collect { stateManager.setPulsarActive(it != PlaybackState.Stopped) }
        }
        appScope.launch {
            lifecycle.events.collect {
                if (it is PlaybackLifecycleEvent.StopAll) controller.pause()
            }
        }

        val featureScope = FeatureCoroutineScope()
        val timer = TimerViewModel(
            engine,
            MasterVolumeRamp(engine),
            lifecycle,
            stateManager,
            NoOpTimerWidgetNotifier(),
            DefaultEngagementTracker(),
            featureScope,
            FeatureStatePersistence(
                InMemoryPreferences(),
                SingleDispatcher(UnconfinedTestDispatcher(testScheduler)),
                featureScope,
            ),
        )

        controller.play()
        timer.actions.onSetDuration(1.minutes)
        timer.actions.onStart()
        advanceTimeBy(61_000L)   // countdown
        advanceTimeBy(16_000L)   // 15s fade plus the post-fade settle
        advanceUntilIdle()

        assertEquals(TimerStatus.FINISHED, timer.stateFlow.value.status)
        assertEquals(
            0.8f,
            engine.volumeLevel,
            "expiry must hand master volume back, or play() restarts into silence",
        )

        controller.play()
        advanceUntilIdle()

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(0.8f, engine.volumeLevel)

        featureScope.cancel()
        appScope.cancel()
    }

    /** The device log also showed a stop() landing just after the expiry pause. */
    @Test
    fun `play after an expiry that ends in stop resumes`() = runTest {
        val engine = FakeSynthEngine().also { it.setInitialVolume(0.8f) }
        val appScope = AppCoroutineScope(SingleDispatcher(UnconfinedTestDispatcher(testScheduler)))
        val stateManager = MediaSessionStateManager(appScope)
        val lifecycle = PlaybackLifecycleManager()
        val controller = PlaybackController(
            mediaSessionManager = MediaSessionManager(),
            mediaSessionStateManager = stateManager,
            playbackLifecycleManager = lifecycle,
            muteSink = MuteSink { },
            engagementTracker = DefaultEngagementTracker(),
            metadataProducer = StubMetadata(),
            scope = appScope,
        )
        SynthOrchestrator(engine, lifecycle, stateManager, controller, appScope)
        appScope.launch {
            controller.state.collect { stateManager.setPulsarActive(it != PlaybackState.Stopped) }
        }
        appScope.launch {
            lifecycle.events.collect {
                if (it is PlaybackLifecycleEvent.StopAll) controller.pause()
            }
        }

        val featureScope = FeatureCoroutineScope()
        val timer = TimerViewModel(
            engine,
            MasterVolumeRamp(engine),
            lifecycle,
            stateManager,
            NoOpTimerWidgetNotifier(),
            DefaultEngagementTracker(),
            featureScope,
            FeatureStatePersistence(
                InMemoryPreferences(),
                SingleDispatcher(UnconfinedTestDispatcher(testScheduler)),
                featureScope,
            ),
        )

        controller.play()
        timer.actions.onSetDuration(1.minutes)
        timer.actions.onStart()
        advanceTimeBy(77_000L)
        advanceUntilIdle()
        controller.stop()
        advanceUntilIdle()

        controller.play()
        advanceUntilIdle()

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(0.8f, engine.volumeLevel)

        featureScope.cancel()
        appScope.cancel()
    }
}
