package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the production call site of the outro-strand fix: only
 * `PulsarViewModel.applyVibe()` clears the sticky C++ outro request, and the
 * detector tests all drive `PulsarSongEnding` directly, so nothing else would
 * notice if this call were dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarVibeAppliedHookTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    /** Symbols in write order, so the test can assert relative ordering. */
    private val writeOrder = mutableListOf<String>()

    private val songEnding = StubSongEndingEventSource()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun makeViewModel(vibe: Vibe): PulsarViewModel {
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
        val tempo = GlobalTempo(HookTestAudioEngine())
        val engine = SongEndingStubSynthEngine()
        val dispatchers = HookTestDispatchers(testDispatcher)
        val appScope = makeAppCoroutineScope(testDispatcher)
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, appScope, dispatchers),
            globalTempo = tempo,
            appPreferencesRepository = HookTestPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(HookTestVibeProvider(vibe)),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = songEnding,
            engagementTracker = DefaultEngagementTracker(),
        )
    }

    @Test
    fun `applyVibe notifies the song-ending source before the arrangement fence`() =
        runTest(testDispatcher) {
            val vibe = mkMinimalVibe("Hook")
            val vm = makeViewModel(vibe)
            advanceUntilIdle()  // the VM applies a vibe at construction too

            // Snapshot how many ports had been written when the hook fired.
            var writesAtHook = -1
            songEnding.onVibeAppliedObserver = { writesAtHook = writeOrder.size }
            val before = songEnding.vibeAppliedCount
            writeOrder.clear()

            vm.actions.setVibe(vibe)
            advanceUntilIdle()

            assertEquals(
                before + 1, songEnding.vibeAppliedCount,
                "applyVibe must notify SongEndingEventSource; without it the sticky C++ " +
                    "outro request survives into the next song and pins it to its outro",
            )

            val fenceIndex = writeOrder.indexOf("arrangement_generation")
            assertTrue(fenceIndex >= 0, "sanity: applyVibe should write the arrangement fence")
            assertTrue(
                writesAtHook <= fenceIndex,
                "the cleared outro port must land before the arrangement_generation fence, " +
                    "got hook at $writesAtHook and fence at $fenceIndex",
            )
        }

    @Test
    fun `re-applying the playing vibe notifies again`() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Hook")
        val vm = makeViewModel(vibe)
        vm.actions.setVibe(vibe)
        advanceUntilIdle()
        val before = songEnding.vibeAppliedCount

        vm.applyVibe(vibe)
        advanceUntilIdle()

        assertEquals(
            before + 1, songEnding.vibeAppliedCount,
            "a re-apply of the playing vibe is a new song; vibeFlow cannot signal it",
        )
    }
}

private class HookTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class HookTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}

private class HookTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

private class HookTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}
