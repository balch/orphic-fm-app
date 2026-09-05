package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.NotatedPart
import org.balch.orpheus.features.pulsar.models.NotatedScore
import org.balch.orpheus.features.pulsar.models.NotatedScoreProvider
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.PartTimbre
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.ScoreEvent
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [PulsarViewModel.pushNotatedScore]: a pushed score flags its track as
 * score-driven, writes its event count, and packs each event's fields (tick,
 * duration, pitch|velocity, flags) at the track's reserved slot. A null score
 * clears every track's driven flag so a previous score can't linger.
 *
 * Also pins [PulsarFeature.applyNotatedScore] -- the id-resolving entry point
 * an app actually calls -- separately from the lower-level method above:
 * a registered id resolves through the injected `Set<NotatedScoreProvider>` and
 * pushes, while a null or unmatched id clears, which is what lets a later score
 * with no written part of its own stop a previous score's notes from lingering.
 *
 * The ordering and re-apply tests at the bottom cover two review findings that neither
 * side could see alone: the audio thread reads the score arrays on EVERY block, so write
 * order IS the publish contract; and the vibe is re-applied from three paths that used to
 * drop the score, handing its track back to the pattern generator with no log at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarScorePushTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    /** Symbols in write order, so a test can assert relative ordering. */
    private val writeOrder = mutableListOf<String>()

    private val recreatedEngine = RecreatableStubSynthEngine()

    // Lazy: FeatureCoroutineScope's init reads Dispatchers.Main.immediate, which is only
    // valid after setUp()'s Dispatchers.setMain() — construction must not happen eagerly
    // in a property initializer, which JUnit runs before @BeforeTest.
    private val viewModel: PulsarViewModel by lazy { makeViewModel() }

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun makeViewModel(
        notatedScoreProviders: Set<NotatedScoreProvider> = emptySet(),
        prefs: AppPreferencesRepository = ScorePushTestPrefs(),
        vibe: Vibe = scorePushTestVibe(),
    ): PulsarViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value ->
                    ports["${id.uri}:${id.symbol}"] = value
                    writeOrder += id.symbol
                    true
                },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val tempo = GlobalTempo(ScorePushTestAudioEngine())
        val engine = recreatedEngine
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), ScorePushTestDispatchers(testDispatcher)),
            globalTempo = tempo,
            appPreferencesRepository = prefs,
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = ScorePushTestDispatchers(testDispatcher),
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(ScorePushTestVibeProvider(vibe)),
            notatedScoreProviders = notatedScoreProviders,
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
        )
    }

    // Score ports are always written as IntValue (see pushNotatedScore); asFloat()
    // converts so assertions can use a single float-typed helper either way.
    private fun floatPort(symbol: String): Float? =
        ports["$PULSAR_URI:$symbol"]?.asFloat()

    @Test
    fun `a pushed score writes flags, counts and packed pitch-velocity`() {
        val part = NotatedPart(
            trackIndex = 4,
            name = "Lead",
            events = listOf(
                ScoreEvent(tick = 0, durationTicks = 48, pitch = 60, velocity = 100),
                ScoreEvent(tick = 96, durationTicks = 24, pitch = 64, velocity = 80),
            ),
        )
        viewModel.pushNotatedScore(NotatedScore(name = "T", parts = listOf(part)))

        assertEquals(1f, floatPort("score_driven_4"))
        assertEquals(0f, floatPort("score_driven_3"), "untouched tracks must stay pattern-driven")
        assertEquals(2f, floatPort("score_count_4"))

        val base = 4 * NotatedScore.MAX_SCORE_EVENTS * 4
        assertEquals(0f, floatPort("score_ev_${base + 0}"))     // tick
        assertEquals(48f, floatPort("score_ev_${base + 1}"))    // duration
        // pitch in the low 7 bits, velocity in the next 7: 60 | (100 shl 7) = 12860
        assertEquals(12860f, floatPort("score_ev_${base + 2}"))
        assertEquals(0f, floatPort("score_ev_${base + 3}"))     // flags
    }

    @Test
    fun `a pushed score marshals part timbre and both flag bits`() {
        val part = NotatedPart(
            trackIndex = 3,
            name = "Color",
            events = listOf(
                ScoreEvent(tick = 0, durationTicks = 48, pitch = 60, velocity = 100, hold = true, bandRelease = true),
            ),
            timbre = PartTimbre(engineIndex = 2, timbre = 0.8f, level = 0.6f),
        )
        viewModel.pushNotatedScore(NotatedScore(name = "T", parts = listOf(part)))

        assertEquals(2f, floatPort("score_part_3_engine"))
        assertEquals(0.8f, floatPort("score_part_3_timbre"))
        assertEquals(0.6f, floatPort("score_part_3_level"))

        val base = 3 * NotatedScore.MAX_SCORE_EVENTS * 4
        assertEquals(3f, floatPort("score_ev_${base + 3}"), "hold (bit 0) and bandRelease (bit 1) both set")
    }

    @Test
    fun `a null score clears every score_driven flag`() {
        viewModel.pushNotatedScore(null)
        for (t in 0..7) assertEquals(0f, floatPort("score_driven_$t"))
    }

    @Test
    fun `applyNotatedScore resolves a registered id through the injected provider set and pushes it`() =
        runTest(testDispatcher) {
            val part = NotatedPart(
                trackIndex = 4,
                name = "Lead",
                events = listOf(ScoreEvent(tick = 0, durationTicks = 48, pitch = 60, velocity = 100)),
            )
            val provider = FakeNotatedScoreProvider("test-score", NotatedScore(name = "T", parts = listOf(part)))
            val vm = makeViewModel(notatedScoreProviders = setOf(provider))
            advanceUntilIdle()  // the VM applies a vibe at construction too

            vm.applyNotatedScore("test-score")
            advanceUntilIdle()

            assertEquals(1f, floatPort("score_driven_4"))
            assertEquals(1f, floatPort("score_count_4"), "sanity: the resolved provider's own score, not an empty one, was pushed")
            assertEquals(1, provider.loadCount, "resolution must go through the injected provider, not a fresh instance")
        }

    @Test
    fun `applyNotatedScore clears a previously-pushed score on a null id`() = runTest(testDispatcher) {
        val part = NotatedPart(
            trackIndex = 4,
            name = "Lead",
            events = listOf(ScoreEvent(tick = 0, durationTicks = 48, pitch = 60, velocity = 100)),
        )
        val provider = FakeNotatedScoreProvider("test-score", NotatedScore(name = "T", parts = listOf(part)))
        val vm = makeViewModel(notatedScoreProviders = setOf(provider))
        advanceUntilIdle()
        vm.applyNotatedScore("test-score")
        advanceUntilIdle()
        assertEquals(1f, floatPort("score_driven_4"), "sanity: a score was actually pushed first")

        vm.applyNotatedScore(null)
        advanceUntilIdle()

        for (t in 0..7) assertEquals(0f, floatPort("score_driven_$t"))
    }

    @Test
    fun `applyNotatedScore clears rather than leaving a stale score on an unmatched id`() = runTest(testDispatcher) {
        val part = NotatedPart(
            trackIndex = 4,
            name = "Lead",
            events = listOf(ScoreEvent(tick = 0, durationTicks = 48, pitch = 60, velocity = 100)),
        )
        val provider = FakeNotatedScoreProvider("test-score", NotatedScore(name = "T", parts = listOf(part)))
        val vm = makeViewModel(notatedScoreProviders = setOf(provider))
        advanceUntilIdle()
        vm.applyNotatedScore("test-score")
        advanceUntilIdle()
        assertEquals(1f, floatPort("score_driven_4"), "sanity: a score was actually pushed first")

        // Simulates picking a second piece whose Score names no NotatedScoreProvider --
        // Finding #1's exact failure scenario before this fix: track 4 must stop being
        // score-driven rather than keep playing the previous piece's motif.
        vm.applyNotatedScore("no-such-id")
        advanceUntilIdle()

        for (t in 0..7) assertEquals(0f, floatPort("score_driven_$t"))
    }

    // ─── Publish order ────────────────────────────────────────────────────────
    // The audio thread reads pulsar_score_events[t] and pulsar_score_event_count[t] on
    // EVERY block, not only on a generation change, so write order is the entire publish
    // contract. Announcing a track and its count first told the engine "track 4, N notes,
    // go" while the array was still zeroed -- a held MIDI note 0 with the gate pinned
    // open until the fence finally landed. Mirrors how PulsarVibeAppliedHookTest pins
    // arrangement_generation.

    @Test
    fun `a pushed score writes its events before the flags that announce them`() {
        val part = NotatedPart(
            trackIndex = 4,
            name = "Lead",
            events = listOf(
                ScoreEvent(tick = 0, durationTicks = 48, pitch = 60, velocity = 100),
                ScoreEvent(tick = 96, durationTicks = 24, pitch = 64, velocity = 80),
            ),
        )
        writeOrder.clear()
        viewModel.pushNotatedScore(NotatedScore(name = "T", parts = listOf(part)))

        val firstDriven = writeOrder.indexOfFirst { it == "score_driven_4" }
        val firstEvent = writeOrder.indexOfFirst { it.startsWith("score_ev_") }
        val lastEvent = writeOrder.indexOfLast { it.startsWith("score_ev_") }
        val count = writeOrder.indexOfLast { it == "score_count_4" }
        val driven = writeOrder.indexOfLast { it == "score_driven_4" }
        val fence = writeOrder.indexOfLast { it == "score_generation" }
        assertTrue(firstEvent >= 0, "sanity: the push should write score_ev_ ports")

        assertTrue(
            firstDriven in 0..<firstEvent,
            "the flag must be CLEARED before this track's array is rewritten, or a second " +
                "push tears under a reader that is mid-block " +
                "(first driven $firstDriven, first event $firstEvent)",
        )
        assertTrue(
            lastEvent < count,
            "every event must land before the count that bounds it: score_collect_due " +
                "trusts that count as its loop bound, so an early count reads zeroed events " +
                "(last event $lastEvent, count $count)",
        )
        assertTrue(
            count < driven,
            "score_driven_4 is the per-track publish flag -- the audio thread reads the " +
                "array whenever it is set, so it must follow the data (count $count, driven $driven)",
        )
        assertTrue(
            driven < fence,
            "score_generation goes last: it is what arms the read side and resets the score " +
                "clock (driven $driven, fence $fence)",
        )
    }

    // ─── Surviving a vibe re-apply ────────────────────────────────────────────
    // applyVibe pushes the vibe's own generative lick over the score-driven track, and it
    // runs from three paths that never mention the score: restore (init + every preset
    // load), the graph-ready re-apply, and the engine-recreated re-push.

    @Test
    fun `the score survives an engine-recreated vibe re-apply`() = runTest(testDispatcher) {
        val vm = makeViewModel(notatedScoreProviders = setOf(oneNoteProvider()))
        advanceUntilIdle()
        vm.applyNotatedScore("test-score")
        advanceUntilIdle()
        writeOrder.clear()

        recreatedEngine.engineRecreated.emit(Unit)
        advanceUntilIdle()

        assertScoreOutlivesTheVibeReApply(
            "the engine-recreated path re-pushes everything that is not in the port map; " +
                "the notated score is in exactly that set"
        )
    }

    @Test
    fun `a saved score is restored alongside its vibe on relaunch`() = runTest(testDispatcher) {
        // Second launch: preferences already carry a Pulsar blob naming the score. Without
        // the restore hook the vibe comes back and its track quietly plays the generative
        // lick instead of the written part. Construction also runs the graph-ready
        // re-apply (graphReady defaults to already-complete and this vibe has an
        // arrangement), so the ordering assertion covers that path too.
        val vibe = mkMinimalVibe("Scored")
        val saved = PulsarUiState(vibe = vibe, vibeName = vibe.name, notatedScoreId = "test-score")
        val prefs = ScorePushTestPrefs()
        prefs.save(
            AppPreferences(
                lastPulsarJson = scorePushPersistJson.encodeToString(PulsarUiState.serializer(), saved)
            )
        )

        val vm = makeViewModel(
            notatedScoreProviders = setOf(oneNoteProvider()),
            prefs = prefs,
            vibe = vibe,
        )
        advanceUntilIdle()

        assertEquals(
            1f, floatPort("score_driven_4"),
            "restoreSavedState must re-apply the saved notated score; the feature otherwise " +
                "works on the first pick and silently stops working after a relaunch",
        )
        assertEquals("test-score", vm.stateFlow.value.notatedScoreId, "id must round-trip into state")
        assertScoreOutlivesTheVibeReApply(
            "restore is followed by the graph-ready re-apply, which pushes the vibe's own " +
                "lick over the score-driven track"
        )
    }

    /** The last score push must land AFTER the last vibe push, or the vibe overwrote it. */
    private fun assertScoreOutlivesTheVibeReApply(why: String) {
        val vibeGen = writeOrder.indexOfLast { it == "vibe_generation" }
        val driven = writeOrder.indexOfLast { it == "score_driven_4" }
        assertTrue(vibeGen >= 0, "sanity: the re-apply should write the vibe generation")
        assertTrue(driven > vibeGen, "$why (vibe_generation $vibeGen, score_driven $driven)")
        assertEquals(1f, floatPort("score_driven_4"))
    }

    private fun oneNoteProvider() = FakeNotatedScoreProvider(
        "test-score",
        NotatedScore(
            name = "T",
            parts = listOf(
                NotatedPart(
                    trackIndex = 4,
                    name = "Lead",
                    events = listOf(ScoreEvent(tick = 0, durationTicks = 48, pitch = 67, velocity = 100)),
                )
            ),
        ),
    )
}

/** Adds an emittable engine-recreated signal to the shared stub, which has none. */
private class RecreatableStubSynthEngine : SongEndingStubSynthEngine() {
    val engineRecreated = MutableSharedFlow<Unit>()
    override val engineRecreatedFlow: SharedFlow<Unit> get() = engineRecreated
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

/** Matches PulsarViewModel's own `persistJson`, so a seeded blob decodes the same way. */
private val scorePushPersistJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private fun scorePushTestVibe(): Vibe = Vibe(
    name = "Score Push Test",
    bpm = 120f,
    rootNote = RootNote.C,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f, ghostProbability = 0f,
        noteRangeLow = 36, noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    tracks = List(8) {
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = TrackRole.Percussive,
        )
    },
)

private class ScorePushTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}

/** Counts loads so a test can assert resolution reused the injected instance, not a fresh one. */
private class FakeNotatedScoreProvider(
    override val name: String,
    private val score: NotatedScore,
) : NotatedScoreProvider {
    var loadCount: Int = 0
        private set

    override suspend fun score(): NotatedScore {
        loadCount++
        return score
    }
}

private class ScorePushTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class ScorePushTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class ScorePushTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}
