package org.balch.orpheus.features.pulsar.mixer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
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

@OptIn(ExperimentalCoroutinesApi::class)
class MixerViewModelTracksViewTest {
    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val dx3 = OrpheusEngine(engineId = OrpheusEngineId.DX3, harmonics = 0.107f)
    private val ens = OrpheusEngine(engineId = OrpheusEngineId.ENS)
    private val vibe = Vibe(
        name = "Mixer Tracks View Test",
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
                engineEdm = dx3,
                engineSpace = ens,
                role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
            )
        },
    )

    private val otherVibe = vibe.copy(name = "Mixer Tracks View Other")

    private val pulsar = FakePulsarFeature(listOf(vibe, otherVibe), vibe)

    private fun makeViewModel(
        playbackMode: PulsarPlaybackMode = PulsarPlaybackMode.EXPLICIT,
    ): MixerViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value -> ports[id.symbol] = value; true },
                getter = { id -> ports[id.symbol] },
            )
        }
        val dispatchers = object : DispatcherProvider {
            override val main get() = testDispatcher
            override val io get() = testDispatcher
            override val default get() = testDispatcher
            override val unconfined get() = testDispatcher
        }
        val scope = FeatureCoroutineScope()
        val prefs = object : BaseAppPreferencesRepository() {
            private var saved = AppPreferences()
            override suspend fun load() = saved
            override suspend fun save(preferences: AppPreferences) { saved = preferences }
        }
        return MixerViewModel(
            engine = SongEndingStubSynthEngine(),
            synthController = controller,
            pulsarFeature = pulsar,
            persistence = FeatureStatePersistence(prefs, dispatchers, scope),
            engagementTracker = DefaultEngagementTracker(),
            restoreStrategy = RestoreStrategy.PRESET,
            playbackMode = playbackMode,
            dispatcherProvider = dispatchers,
            scope = scope,
        )
    }

    @Test
    fun tracksViewStartsOnBandAndStaysForTheSession() = runTest(testDispatcher) {
        val vm = makeViewModel()
        backgroundScope.launch { vm.stateFlow.collect {} }
        advanceUntilIdle()
        assertEquals(false, vm.stateFlow.value.tracksView)
        vm.actions.setTracksView(true)
        advanceUntilIdle()
        // An energy change must not flip the view back.
        pulsar.stateFlow.value = pulsar.stateFlow.value.copy(energy = 0.2f)
        advanceUntilIdle()
        assertEquals(true, vm.stateFlow.value.tracksView)
    }

    private fun bass() = ports[PulsarSymbol.BASS_GAIN.symbol]?.asFloat()
    private fun drive() = ports[DistortionSymbol.DRIVE.symbol]?.asFloat()

    private fun perc() = ports[PulsarSymbol.PERC_MIX.symbol]?.asFloat()

    /** Pulls BASS, PERC and DIST off their defaults and flips to TRACKS. */
    private fun MixerViewModel.dialIn() {
        actions.setGroupGain(MixerGroup.BASS, 0.2f)
        actions.setGroupGain(MixerGroup.PERC, 0.4f)
        actions.setDrive(0.9f)
        actions.setTracksView(true)
    }

    @Test
    fun switchingToADifferentVibeResetsTheFadersButKeepsDistAndPerc() = runTest(testDispatcher) {
        pulsar.applyVibe(vibe) // the startup restore
        val vm = makeViewModel()
        backgroundScope.launch { vm.stateFlow.collect {} }
        advanceUntilIdle()
        vm.dialIn()
        advanceUntilIdle()

        pulsar.applyVibe(otherVibe)
        advanceUntilIdle()

        assertEquals(0.75f, bass())
        assertEquals(0.4f, perc())
        assertEquals(0.9f, drive())
        assertEquals(0.9f, ports[DistortionSymbol.MIX.symbol]?.asFloat())
        // The view is a session choice: only an app start puts it back on BAND.
        assertEquals(true, vm.stateFlow.value.tracksView)
    }

    @Test
    fun reapplyingTheSameVibeKeepsTheMix() = runTest(testDispatcher) {
        pulsar.applyVibe(vibe)
        val vm = makeViewModel()
        backgroundScope.launch { vm.stateFlow.collect {} }
        advanceUntilIdle()
        vm.dialIn()
        advanceUntilIdle()

        // Preset reload, graph-ready re-apply and engine rebuild all land here.
        pulsar.applyVibe(vibe)
        advanceUntilIdle()

        assertEquals(0.2f, bass())
        assertEquals(0.9f, drive())
        assertEquals(true, vm.stateFlow.value.tracksView)
    }

    @Test
    fun startupRestoreAfterConstructionIsNotASwitch() = runTest(testDispatcher) {
        // The mixer is built before the restore runs, and the restored vibe differs from the default.
        val vm = makeViewModel()
        backgroundScope.launch { vm.stateFlow.collect {} }
        advanceUntilIdle()
        vm.actions.setDrive(0.9f)
        advanceUntilIdle()

        pulsar.applyVibe(otherVibe)
        advanceUntilIdle()

        assertEquals(0.9f, drive())
    }

    @Test
    fun orpheusNeverResetsSinceDistortionSharesTheDrivePorts() = runTest(testDispatcher) {
        pulsar.applyVibe(vibe)
        val vm = makeViewModel(PulsarPlaybackMode.MIX_GATED)
        backgroundScope.launch { vm.stateFlow.collect {} }
        advanceUntilIdle()
        vm.dialIn()
        advanceUntilIdle()

        pulsar.applyVibe(otherVibe)
        advanceUntilIdle()

        assertEquals(0.2f, bass())
        assertEquals(0.9f, drive())
    }
}
