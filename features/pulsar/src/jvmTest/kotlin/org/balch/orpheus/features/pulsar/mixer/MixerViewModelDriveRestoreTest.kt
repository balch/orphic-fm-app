package org.balch.orpheus.features.pulsar.mixer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The GAIN fader comes back at its last value on the next launch. */
@OptIn(ExperimentalCoroutinesApi::class)
class MixerViewModelDriveRestoreTest {
    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val engine = OrpheusEngine(engineId = OrpheusEngineId.DX3)
    private val vibe = Vibe(
        name = "Mixer Drive Restore Test",
        bpm = 100f,
        rootNote = RootNote.C,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
            swingAmount = 0f, ghostProbability = 0f,
            noteRangeLow = 36, noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.SPARSE.density,
        ),
        tracks = List(8) {
            TrackVoice(
                engineEdm = engine,
                engineSpace = engine,
                role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
            )
        },
    )

    private val prefs = object : BaseAppPreferencesRepository() {
        var saved = AppPreferences()
        override suspend fun load() = saved
        override suspend fun save(preferences: AppPreferences) { saved = preferences }
    }

    private class Launch(
        val vm: MixerViewModel,
        val controller: SynthController,
        val engine: SongEndingStubSynthEngine,
    ) {
        val drive get() = controller.controlFlow(DistortionSymbol.DRIVE.controlId).value.asFloat()
        val mix get() = controller.controlFlow(DistortionSymbol.MIX.controlId).value.asFloat()
    }

    // Unconfined, so the restore finishes inside bind(): the order a warm prefs cache could give.
    private fun TestScope.launchApp(dispatcher: CoroutineDispatcher): Launch {
        val controller = SynthController().apply {
            setDelegates(setter = { _, _ -> true }, getter = { null })
        }
        val dispatchers = object : DispatcherProvider {
            override val main get() = dispatcher
            override val io get() = dispatcher
            override val default get() = dispatcher
            override val unconfined get() = dispatcher
        }
        val scope = FeatureCoroutineScope()
        val pulsar = FakePulsarFeature(listOf(vibe), vibe)
        val engine = SongEndingStubSynthEngine()
        val vm = MixerViewModel(
            engine = engine,
            synthController = controller,
            pulsarFeature = pulsar,
            persistence = FeatureStatePersistence(prefs, dispatchers, scope),
            engagementTracker = DefaultEngagementTracker(),
            restoreStrategy = RestoreStrategy.USER_PREFERENCES,
            dispatcherProvider = dispatchers,
            scope = scope,
            trackGainSink = DirectTrackGainSink(controller, pulsar, scope),
        )
        backgroundScope.launch { vm.stateFlow.collect {} }
        return Launch(vm, controller, engine)
    }

    @Test
    fun gainStartsAtTheDefaultThenComesBackAtItsLastValue() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        val first = launchApp(dispatcher)
        runCurrent()
        assertEquals(0.3f, first.drive)

        first.vm.actions.setDrive(0.55f)
        advanceTimeBy(3_000L)
        runCurrent()

        val second = launchApp(dispatcher)
        runCurrent()
        assertEquals(0.55f, second.drive)
        assertEquals(0.55f, second.mix)
        assertEquals(0.55f, second.vm.stateFlow.value.drive)
    }

    @Test
    fun gainIsSavedWhileTheMeterIsMoving() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        val first = launchApp(dispatcher)
        // The engine polls the peak meter at 5 Hz while the music plays.
        backgroundScope.launch {
            while (true) {
                delay(200L)
                first.engine.peakFlow.value += 0.001f
            }
        }
        advanceTimeBy(1_000L)

        first.vm.actions.setDrive(0.55f)
        advanceTimeBy(1_000L)
        runCurrent()

        val second = launchApp(dispatcher)
        runCurrent()
        assertEquals(0.55f, second.drive)
    }
}
