package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mode One pins every vibe to a seed derived from its name, so two builds can be A/B'd on
 * the same roll while two vibes still roll differently. The Pulsar panel toggles it with a
 * long press on the Complexity knob; it lives only for the session, and flipping it restarts
 * the current vibe so the seed takes effect at once.
 *
 * The pinned seeds are the low 24 bits of Kotlin's String.hashCode (the port crosses to C++
 * as a float, which is exact to 24 bits): "Seven" -> 12668909, "Random" -> 7852259.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarOneModeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun makeViewModel(vararg vibes: Vibe): PulsarViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value -> ports["${id.uri}:${id.symbol}"] = value; true },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val tempo = GlobalTempo(OneModeAudioEngine())
        val engine = SongEndingStubSynthEngine()
        val dispatchers = FixturesDispatchers(testDispatcher)
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), dispatchers),
            globalTempo = tempo,
            appPreferencesRepository = OneModePrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = vibes.map { OneModeVibeProvider(it) }.toSet(),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
        )
    }

    private fun intPort(symbol: PulsarSymbol): Int? =
        (ports["${symbol.controlId.uri}:${symbol.controlId.symbol}"] as? IntValue)?.value

    private fun seed(): Int? = intPort(PulsarSymbol.SEED)
    private fun generation(): Int? = intPort(PulsarSymbol.VIBE_GENERATION)

    @Test
    fun `a vibe pushes its own seed while One Mode is off`() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Seven").copy(seed = 7)
        val vm = makeViewModel(vibe)

        vm.actions.setVibe(vibe)
        advanceUntilIdle()

        assertFalse(vm.actions.modeOne.value, "One Mode starts off")
        assertEquals(7, seed(), "the vibe's own seed reaches the engine")
    }

    @Test
    fun `toggling Mode One on pushes the vibe's name-hash seed and restarts it`() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Seven").copy(seed = 7)
        val vm = makeViewModel(vibe)
        vm.actions.setVibe(vibe)
        advanceUntilIdle()
        val generationBefore = assertNotNull(generation())

        vm.actions.onToggleModeOne()
        advanceUntilIdle()

        assertTrue(vm.actions.modeOne.value)
        assertEquals(12668909, seed(), "the seed is the name's hash, not the vibe's own seed")
        assertEquals(generationBefore + 1, generation(), "the current vibe is re-applied so the seed takes effect now")
        assertEquals("Seven", vm.vibeFlow.value.name, "the restart keeps the same vibe")
    }

    @Test
    fun `modeOneSeed is the low 24 bits of the name hash and never zero`() {
        assertEquals(12668909, PulsarViewModel.modeOneSeed(mkMinimalVibe("Seven")))
        assertEquals(7852259, PulsarViewModel.modeOneSeed(mkMinimalVibe("Random")))
        // Same name, different authored seed: Mode One ignores the authored one.
        assertEquals(
            PulsarViewModel.modeOneSeed(mkMinimalVibe("Seven").copy(seed = 7)),
            PulsarViewModel.modeOneSeed(mkMinimalVibe("Seven").copy(seed = 0)),
        )
        for (name in listOf("Dog House", "Rust Belt", "Fire Sky", "")) {
            assertTrue(PulsarViewModel.modeOneSeed(mkMinimalVibe(name)) > 0, "'$name' must not roll seed 0 (random)")
        }
    }

    @Test
    fun `toggling One Mode off restores the vibe's own seed`() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Seven").copy(seed = 7)
        val vm = makeViewModel(vibe)
        vm.actions.setVibe(vibe)
        advanceUntilIdle()
        val generationBefore = assertNotNull(generation())

        vm.actions.onToggleModeOne()
        vm.actions.onToggleModeOne()
        advanceUntilIdle()

        assertFalse(vm.actions.modeOne.value)
        assertEquals(7, seed(), "off again, the vibe rolls its own seed")
        assertEquals(generationBefore + 2, generation(), "each flip restarts the vibe")
    }

    @Test
    fun `a vibe change while Mode One is on pins the new vibe to its own name-hash seed`() = runTest(testDispatcher) {
        val first = mkMinimalVibe("Seven").copy(seed = 7)
        val second = mkMinimalVibe("Random").copy(seed = 0)
        val vm = makeViewModel(first, second)
        vm.actions.setVibe(first)
        advanceUntilIdle()

        vm.actions.onToggleModeOne()
        vm.actions.setVibe(second)
        advanceUntilIdle()

        assertTrue(vm.actions.modeOne.value, "a vibe change does not clear Mode One")
        assertEquals("Random", vm.vibeFlow.value.name)
        assertEquals(7852259, seed(), "the new vibe is pinned to ITS name, so two vibes never share a roll")
    }
}

private class OneModeAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class OneModePrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

private class OneModeVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}
