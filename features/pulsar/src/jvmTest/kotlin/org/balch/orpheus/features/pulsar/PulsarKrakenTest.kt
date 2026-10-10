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
import org.balch.orpheus.core.plugin.symbols.AppSymbol
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PulsarKrakenTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private var lastController: SynthController? = null

    private fun vibe(name: String) = mkMinimalVibe(name)

    private fun makeViewModel(vararg vibes: Vibe): PulsarViewModel {
        val controller = SynthController().apply {
            lastController = this
            setDelegates(
                setter = { id, value -> ports["${id.uri}:${id.symbol}"] = value; true },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val tempo = GlobalTempo(KrakenAudioEngine())
        val engine = SongEndingStubSynthEngine()
        val dispatchers = FixturesDispatchers(testDispatcher)
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), dispatchers),
            globalTempo = tempo,
            appPreferencesRepository = KrakenPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = vibes.map { KrakenVibeProvider(it) }.toSet(),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
        )
    }

    private fun port(symbol: PulsarSymbol) =
        (ports["${symbol.controlId.uri}:${symbol.controlId.symbol}"] as? IntValue)?.value

    @Test
    fun pressesAndHoldsReachThePorts() = runTest(testDispatcher) {
        val vm = makeViewModel(vibe("Seven"))
        advanceUntilIdle()
        vm.actions.onKrakenPress()
        assertEquals(1, port(PulsarSymbol.KRAKEN_PRESSES))
        assertEquals(1, port(PulsarSymbol.KRAKEN_HELD))
        vm.actions.onKrakenRelease()
        assertEquals(0, port(PulsarSymbol.KRAKEN_HELD))
        vm.actions.onKrakenCycleTarget()
        assertEquals(1, port(PulsarSymbol.KRAKEN_TARGET))
        assertEquals(1, vm.actions.krakenTarget.value)
    }

    @Test
    fun aVibeChangeLetsGoOfALatch() = runTest(testDispatcher) {
        val seven = vibe("Seven")
        val vm = makeViewModel(seven, vibe("Random"))
        advanceUntilIdle()
        vm.actions.onKrakenPress(); vm.actions.onKrakenRelease()
        vm.actions.onKrakenPress(); vm.actions.onKrakenRelease()
        assertTrue(vm.actions.krakenLatched.value)
        vm.actions.setVibe(seven)
        advanceUntilIdle()
        assertFalse(vm.actions.krakenLatched.value)
        assertEquals(0, port(PulsarSymbol.KRAKEN_HELD))
    }

    @Test
    fun aPauseLetsGoOfALatch() = runTest(testDispatcher) {
        val vm = makeViewModel(vibe("Seven"))
        advanceUntilIdle()
        vm.actions.onKrakenPress(); vm.actions.onKrakenRelease()
        vm.actions.onKrakenPress(); vm.actions.onKrakenRelease()
        assertTrue(vm.actions.krakenLatched.value)
        // Production pauses by raising the app's muted control.
        lastController!!.setPluginControl(AppSymbol.MUTED.controlId, IntValue(1))
        advanceUntilIdle()
        assertFalse(vm.actions.krakenLatched.value)
        assertEquals(0, port(PulsarSymbol.KRAKEN_HELD))
    }
}

private class KrakenAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class KrakenPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

private class KrakenVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name = VibeName(vibe.name)
}
