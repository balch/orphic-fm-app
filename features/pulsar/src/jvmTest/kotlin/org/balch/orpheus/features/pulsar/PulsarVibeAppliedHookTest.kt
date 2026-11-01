package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what `PulsarViewModel.applyVibe()` announces: the song-ending source (only applyVibe clears
 * the sticky C++ outro request, so nothing else would notice if that call were dropped), the
 * [VibeAppliedListener]s, and the DJ app's bus reset on a vibe switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarVibeAppliedHookTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    /** Symbols in write order, so the test can assert relative ordering. */
    private val writeOrder = mutableListOf<String>()

    private val songEnding = StubSongEndingEventSource()

    private lateinit var controller: SynthController

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun makeViewModel(
        vibe: Vibe,
        playbackMode: PulsarPlaybackMode = PulsarPlaybackMode.EXPLICIT,
        prefs: AppPreferences = AppPreferences(),
    ): PulsarViewModel {
        controller = SynthController().apply {
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
            appPreferencesRepository = HookTestPrefs(prefs),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(HookTestVibeProvider(vibe)),
            playbackMode = playbackMode,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = songEnding,
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
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

    private fun port(symbol: PulsarSymbol) = controller.controlFlow(symbol.controlId)

    private fun setPort(symbol: PulsarSymbol, value: Float) {
        port(symbol).value = FloatValue(value)
    }

    private fun float(symbol: PulsarSymbol) = port(symbol).value.asFloat()

    @Test
    fun listenerSeesVibeValuesBeforeLoadTriggers() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Hook").copy(energy = 0.83f)
        val vm = makeViewModel(vibe)
        advanceUntilIdle()
        var energyAtHook = -1f
        var generationAtHook = -1
        vm.addVibeAppliedListener {
            energyAtHook = float(PulsarSymbol.ENERGY)
            generationAtHook = port(PulsarSymbol.VIBE_GENERATION).value.asInt()
            false
        }
        val generationBefore = port(PulsarSymbol.VIBE_GENERATION).value.asInt()

        vm.applyVibe(vibe)

        assertEquals(0.83f, energyAtHook)
        assertEquals(generationBefore, generationAtHook, "the hook must run before the load trigger")
        assertEquals(generationBefore + 1, port(PulsarSymbol.VIBE_GENERATION).value.asInt())
    }

    @Test
    fun listenerRunsOncePerApply() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Hook")
        val vm = makeViewModel(vibe)
        advanceUntilIdle()
        var calls = 0
        vm.addVibeAppliedListener { calls++; false }

        vm.applyVibe(vibe)
        vm.applyVibe(vibe)

        assertEquals(2, calls)
    }

    @Test
    fun setBaseBpmScalesBySectionMultiplier() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Half").copy(
            arrangement = Arrangement(sections = listOf(Section(name = "intro", bpmMultiplier = 0.5f))),
        )
        val vm = makeViewModel(vibe)
        advanceUntilIdle()
        vm.applyVibe(vibe)

        vm.setBaseBpm(100f)

        assertEquals(50f, float(PulsarSymbol.BPM))
        assertEquals(100f, vm.baseBpm)
    }

    @Test
    fun switchingVibeResetsBusGainsToUnity() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Hook")
        val vm = makeViewModel(vibe)
        advanceUntilIdle()
        vm.applyVibe(vibe)
        for (bus in BUSES) setPort(bus, 0.4f)
        setPort(PulsarSymbol.PERC_MIX, 0.3f)

        vm.applyVibe(mkMinimalVibe("Other"))

        for (bus in BUSES) assertEquals(0.75f, float(bus), bus.name)
        assertEquals(0.3f, float(PulsarSymbol.PERC_MIX))
    }

    @Test
    fun reapplyingSameVibeKeepsBusGains() = runTest(testDispatcher) {
        val vibe = mkMinimalVibe("Hook")
        val vm = makeViewModel(vibe)
        advanceUntilIdle()
        vm.applyVibe(vibe)
        setPort(PulsarSymbol.BASS_GAIN, 0.4f)

        vm.applyVibe(vibe)

        assertEquals(0.4f, float(PulsarSymbol.BASS_GAIN))
    }

    @Test
    fun firstApplyKeepsBusGains() = runTest(testDispatcher) {
        val vm = makeViewModel(mkMinimalVibe("Hook"))
        setPort(PulsarSymbol.BASS_GAIN, 0.4f)

        // Before the graph-ready re-apply runs, so this is the VM's first load.
        vm.applyVibe(mkMinimalVibe("Other"))
        advanceUntilIdle()

        assertEquals(0.4f, float(PulsarSymbol.BASS_GAIN))
    }

    @Test
    fun orpheusNeverResetsBusGains() = runTest(testDispatcher) {
        // Orpheus's Distortion panel shares the mixer ports, so only the DJ app resets.
        val vibe = mkMinimalVibe("Hook")
        val vm = makeViewModel(vibe, PulsarPlaybackMode.MIX_GATED)
        advanceUntilIdle()
        vm.applyVibe(vibe)
        setPort(PulsarSymbol.BASS_GAIN, 0.4f)

        vm.applyVibe(mkMinimalVibe("Other"))

        assertEquals(0.4f, float(PulsarSymbol.BASS_GAIN))
    }

    @Test
    fun claimedApplySkipsSavedKnobRestore() = runTest(testDispatcher) {
        // No arrangement, so the graph-ready re-apply stays out and the restore is the last writer.
        val vibe = mkMinimalVibe("Hook").copy(energy = 0.83f, arrangement = null)
        val saved = HookTestJson.encodeToString(
            PulsarUiState.serializer(),
            PulsarUiState(vibe = vibe, vibeName = vibe.name, energy = 0.2f),
        )
        val vm = makeViewModel(vibe, prefs = AppPreferences(lastPulsarJson = saved))
        // Registered before the launch restore runs.
        vm.addVibeAppliedListener {
            setPort(PulsarSymbol.ENERGY, 0.6f)
            true
        }

        advanceUntilIdle()

        assertEquals(0.6f, float(PulsarSymbol.ENERGY), "the claimed knobs win over the saved ones")
    }

    private companion object {
        val BUSES = listOf(PulsarSymbol.BASS_GAIN, PulsarSymbol.KEYS_GAIN, PulsarSymbol.FX_GAIN)
    }
}

private val HookTestJson = Json { encodeDefaults = true }

private class HookTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class HookTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name = VibeName(vibe.name)
}

private class HookTestPrefs(private var prefs: AppPreferences) : AppPreferencesRepository {
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
