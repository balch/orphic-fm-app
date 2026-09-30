package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.AppSymbol
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.VibePlaylistStore
import org.balch.orpheus.features.pulsar.playback.VibeRequest
import org.balch.orpheus.features.pulsar.playback.stepInRotation
import org.balch.orpheus.features.pulsar.vibes.AlbumCatalog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Vibe pickers must ask [PulsarSession]'s navigator rather than applying directly, and
 * [PulsarViewModel.vibeNavFlow] must track the session's progress. See `VibeNavigator`
 * (Task 3) for the consumer side of `vibeRequests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarVibeNavTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.makeViewModelAndSession(
        vibes: List<Vibe> = listOf(mkMinimalVibe("Nav")),
        store: VibePlaylistStore = VibePlaylistStore.InMemory(),
        controller: SynthController = navTestController(),
        albums: AlbumCatalog = AlbumCatalog(emptyList()),
        providers: Set<VibeProvider> = vibes.mapTo(mutableSetOf()) { NavTestVibeProvider(it) },
    ): Pair<PulsarViewModel, PulsarSession> {
        val tempo = GlobalTempo(NavTestAudioEngine())
        val engine = SongEndingStubSynthEngine()
        val dispatchers = NavTestDispatchers(testDispatcher)
        val appScope = makeAppCoroutineScope(testDispatcher)
        val session = PulsarSession(engine, appScope, dispatchers)
        val vm = PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = session,
            globalTempo = tempo,
            appPreferencesRepository = NavTestPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = providers,
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
            vibePlaylistStore = store,
            albumCatalog = albums,
        )
        advanceUntilIdle()
        return vm to session
    }

    @Test
    fun pickVibeAsksTheSessionInsteadOfApplying() = runTest {
        val (vm, session) = makeViewModelAndSession()
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        val before = vm.vibeFlow.value
        val other = mkMinimalVibe("Other")
        vm.actions.pickVibe(other)
        vm.actions.nextVibe()
        vm.actions.previousVibe()
        assertEquals(listOf(VibeRequest.Pick(other), VibeRequest.Next, VibeRequest.Previous), seen)
        assertEquals(before, vm.vibeFlow.value, "a pick waits for the navigator; it must not apply directly")
    }

    // The dome's swipe asks as the dome, so the move it causes is recognisably its own.
    @Test
    fun aDomeSwipeAsksTheSessionAsTheDome() = runTest {
        val (vm, session) = makeViewModelAndSession()
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        vm.actions.swipeVibe(true)
        vm.actions.swipeVibe(false)
        assertEquals(listOf<VibeRequest>(VibeRequest.DomeSwipe(next = true), VibeRequest.DomeSwipe(next = false)), seen)
    }

    // An AI's vibe applies at once with no transition: it asks the navigator nothing, so nothing rolls.
    @Test
    fun setVibeAsksTheNavigatorNothing() = runTest {
        val (vm, session) = makeViewModelAndSession()
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        val other = mkMinimalVibe("Other")
        vm.actions.setVibe(other)
        advanceUntilIdle()
        assertEquals("Other", vm.vibeFlow.value.name, "sanity: the vibe applied")
        assertEquals(emptyList<VibeRequest>(), seen)
    }

    @Test
    fun theFeatureRelaysTheSessionsMoves() = runTest {
        val (vm, session) = makeViewModelAndSession()
        assertSame(session.vibeMoves, vm.vibeMoves)
    }

    @Test
    fun navStateFollowsSessionProgress() = runTest {
        val (vm, session) = makeViewModelAndSession()
        session.updateProgress(PlaybackProgress(50_000, 200_000))
        advanceUntilIdle()
        assertEquals(0.25f, vm.vibeNavFlow.value.progress)
        assertEquals(vm.vibeFlow.value.name, vm.vibeNavFlow.value.currentName)
    }

    // Previews and fakes read the default on every composition; it must not allocate a flow per read.
    @Test
    fun theStubNavFlowIsShared() {
        val vibe = mkMinimalVibe("A")
        val stub = FakePulsarFeature(listOf(vibe), vibe)
        assertSame(stub.vibeNavFlow, stub.vibeNavFlow)
        assertEquals(VibeNavState.EMPTY, stub.vibeNavFlow.value)
    }

    private val abc = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"), mkMinimalVibe("C"))

    // The sheet's tap-to-play names a vibe: the session hears a Pick of it, and no other body is built.
    @Test
    fun aPickByNameBuildsOnlyThatVibe() = runTest {
        val providers = abc.map { CountingVibeProvider(it) }
        val (vm, session) = makeViewModelAndSession(providers = providers.toSet())
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        val before = providers.map { it.builds }
        vm.pickVibeByName("C")
        vm.pickVibeByName("Nowhere")
        assertEquals(listOf<VibeRequest>(VibeRequest.Pick(abc[2])), seen)
        assertEquals(listOf(0, 0, 1), providers.map { it.builds }.zip(before) { after, was -> after - was })
    }

    @Test
    fun theRotationComesFromTheStore() = runTest {
        val (vm, _) = makeViewModelAndSession(abc, VibePlaylistStore.InMemory(VibePlaylistPrefs(listOf("A", "C", "B"))))
        advanceUntilIdle()
        assertEquals(listOf("A", "C", "B"), vm.rotationFlow.value.order)
    }

    @Test
    fun anEditChangesTheRotationAtOnce() = runTest {
        val (vm, _) = makeViewModelAndSession(abc)
        assertEquals(listOf("A", "B", "C"), vm.rotationFlow.value.order, "sanity: catalog order")
        vm.editPlaylist(PlaylistEdit.PlaceAfter("C", "A"))
        advanceUntilIdle()
        assertEquals(listOf("A", "C", "B"), vm.rotationFlow.value.order)
    }

    // Album order and RIF's track order both run against enum and catalog order; nothing collects first.
    @Test
    fun theAlbumsComeFromTheAlbumCatalog() = runTest {
        val albums = AlbumCatalog(
            listOf(
                Album.RIF to listOf(VibeName("C"), VibeName("Gone"), VibeName("A")),
                Album.ANOMALIES to listOf(VibeName("Gone Too")),
                Album.STEALTH to listOf(VibeName("B")),
            ),
        )
        val (vm, _) = makeViewModelAndSession(abc, albums = albums)
        assertEquals(mapOf(Album.RIF to listOf("C", "A"), Album.STEALTH to listOf("B")), vm.playlistFlow.value.albums)
        assertEquals(listOf(Album.RIF, Album.STEALTH), vm.playlistFlow.value.albums.keys.toList())
    }

    // The saved playlist loads after the feature is built; the sheet's first frame must already show it.
    @Test
    fun theFirstPlaylistACollectorSeesIsTheSavedOne() = runTest {
        val store = VibePlaylistStore.InMemory()
        val (vm, _) = makeViewModelAndSession(abc, store)
        store.update { VibePlaylistPrefs(listOf("A", "C", "B"), removed = setOf("C")) }
        advanceUntilIdle()
        val first = vm.playlistFlow.first()
        assertEquals(listOf("A", "C", "B"), first.rotation.order)
        assertEquals(listOf("A", "B"), first.rotation.playing)
    }

    @Test
    fun theStubRotationIsCatalogOrder() {
        val vibe = mkMinimalVibe("A")
        val stub = FakePulsarFeature(listOf(vibe, mkMinimalVibe("B")), vibe)
        assertEquals(listOf("A", "B"), stub.rotationFlow.value.playing)
    }

    @Test
    fun theNavStateNamesTheSavedRotationsNeighbours() = runTest {
        val (vm, _) = makeViewModelAndSession(abc, VibePlaylistStore.InMemory(VibePlaylistPrefs(listOf("A", "C", "B"))))
        advanceUntilIdle()
        assertEquals("C", vm.vibeNavFlow.value.nextName, "catalog order would say B")
        assertEquals("B", vm.vibeNavFlow.value.previousName)
    }

    @Test
    fun anEditMovesUpNextAtOnce() = runTest {
        val (vm, _) = makeViewModelAndSession(abc)
        assertEquals("B", vm.vibeNavFlow.value.nextName, "sanity: catalog order")
        vm.editPlaylist(PlaylistEdit.PlaceAfter("C", "A"))
        advanceUntilIdle()
        assertEquals("C", vm.vibeNavFlow.value.nextName)
    }

    // ==================== QueueAlbum ====================

    // A plays first; RIF's track order, D then B, runs against catalog order.
    private val abcd = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"), mkMinimalVibe("C"), mkMinimalVibe("D"))
    private val abcdAlbums = AlbumCatalog(
        listOf(
            Album.RIF to listOf(VibeName("D"), VibeName("B")),
            Album.STEALTH to listOf(VibeName("A"), VibeName("C")),
        ),
    )

    /** A paused or playing app, with the session's requests recorded. The sheet need not be open. */
    private fun TestScope.queueSetup(paused: Boolean): Pair<PulsarViewModel, MutableList<VibeRequest>> {
        val controller = navTestController()
        val (vm, session) = makeViewModelAndSession(abcd, controller = controller, albums = abcdAlbums)
        controller.setPluginControl(AppSymbol.MUTED.controlId, PortValue.IntValue(if (paused) 1 else 0))
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(paused, vm.stateFlow.value.globalPaused, "sanity")
        return vm to seen
    }

    /** Up next as the sheet lists it: the rotation from the playing vibe round to it. */
    private fun upNext(vm: PulsarViewModel): List<String> {
        val now = vm.vibeFlow.value.name
        val rotation = vm.rotationFlow.value
        return generateSequence(stepInRotation(rotation, now, 1)) { stepInRotation(rotation, it, 1) }
            .takeWhile { it != now }.take(rotation.order.size).toList()
    }

    @Test
    fun playingAQueuedAlbumPlaysNext() = runTest {
        val (vm, seen) = queueSetup(paused = false)
        assertEquals(listOf("B", "C", "D"), upNext(vm), "sanity: catalog order")
        vm.editPlaylist(PlaylistEdit.QueueAlbum(Album.RIF, now = "A"))
        advanceUntilIdle()
        assertEquals(emptyList(), seen, "the playing song finishes first")
        assertEquals(listOf("D", "B", "C"), upNext(vm))
        assertEquals("D", vm.vibeNavFlow.value.nextName)
    }

    @Test
    fun pausedAQueuedAlbumsFirstVibeReplacesNow() = runTest {
        val (vm, seen) = queueSetup(paused = true)
        vm.editPlaylist(PlaylistEdit.QueueAlbum(Album.RIF, now = "A"))
        advanceUntilIdle()
        val pick = assertIs<VibeRequest.Pick>(seen.single())
        assertEquals("D", pick.vibe.name)
        vm.applyVibe(pick.vibe) // what the navigator does with it
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "A"), upNext(vm))
    }

    // Paused under the album's own first vibe, it already plays: no restart from a second pick.
    @Test
    fun pausedOnTheAlbumsFirstVibeNothingIsPicked() = runTest {
        val (vm, seen) = queueSetup(paused = true)
        vm.editPlaylist(PlaylistEdit.QueueAlbum(Album.STEALTH, now = "A"))
        advanceUntilIdle()
        assertEquals(emptyList(), seen)
        assertEquals(listOf("C", "B", "D"), upNext(vm))
    }

    // ==================== Shuffle ====================

    @Test
    fun playingAShuffleLetsTheSongFinish() = runTest {
        val (vm, seen) = queueSetup(paused = false)
        vm.editPlaylist(PlaylistEdit.Shuffle)
        advanceUntilIdle()
        assertEquals(emptyList(), seen)
    }

    // The order is random, so the pick is checked against the shuffled rotation itself.
    @Test
    fun pausedAShufflesFirstUpNextReplacesNow() = runTest {
        val (vm, seen) = queueSetup(paused = true)
        vm.editPlaylist(PlaylistEdit.Shuffle)
        advanceUntilIdle()
        val first = upNext(vm).first()
        val pick = assertIs<VibeRequest.Pick>(seen.single())
        assertEquals(first, pick.vibe.name)
    }
}

private fun navTestController() = SynthController().apply {
    setDelegates(
        setter = { _, _ -> true },
        getter = { null },
    )
}

private class NavTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class NavTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name = VibeName(vibe.name)
}

/** Counts each read of its body, where a real provider would build it. */
private class CountingVibeProvider(private val body: Vibe) : VibeProvider {
    var builds = 0
    override val name = VibeName(body.name)
    override val vibe: Vibe get() = body.also { builds++ }
}

private class NavTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

private class NavTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}
