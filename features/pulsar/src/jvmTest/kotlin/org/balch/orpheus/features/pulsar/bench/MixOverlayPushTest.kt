package org.balch.orpheus.features.pulsar.bench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.FixturesDispatchers
import org.balch.orpheus.features.pulsar.MusicPulseSource
import org.balch.orpheus.features.pulsar.PulsarSession
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.StubSongEndingEventSource
import org.balch.orpheus.features.pulsar.StubSongEndingPreferences
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.StubTransitionRunner
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.VibeCatalogScan
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MixOverlayPushTest {

    private val testDispatcher = StandardTestDispatcher()
    private val writes = mutableListOf<Pair<String, Float>>()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val lore: Vibe get() = VibeCatalogScan.allProviders().first { it.vibe.name == "Orphic Lore" }.vibe

    /** Loads Orphic Lore, optionally installs [overlay] first, and drives [sections] in order. */
    private fun TestScope.run(overlay: MixOverlay?, installCall: Boolean, beforeLoad: Boolean = true, sections: List<Int> = emptyList()): List<Pair<String, Float>> {
        writes.clear()
        val vibe = lore
        val engine = SongEndingStubSynthEngine()
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(engine, vibe, scope)
        if (installCall && beforeLoad) vm.setMixOverlay(overlay)
        vm.actions.setVibe(vibe)
        advanceUntilIdle()
        if (installCall && !beforeLoad) vm.setMixOverlay(overlay)
        for (s in sections) {
            engine.pulsarArrangementStateFlow.value = PulsarArrangementState(
                sectionIndex = s, barsElapsed = 0, barsTotal = 0,
                soloActive = false, soloTrack = -1, soloMode = 0,
            )
            advanceUntilIdle()
        }
        scope.cancel()
        return writes.toList()
    }

    @Test
    fun noOverlayIsIdentity() = runTest(testDispatcher) {
        val without = run(null, installCall = false, sections = listOf(0, 1))
        val withNull = run(null, installCall = true, beforeLoad = false, sections = listOf(0, 1))
        assertTrue(without.isNotEmpty())
        // The re-push adds writes; the settled value of every port must not move.
        assertEquals(without.toMap(), withNull.toMap())
    }

    @Test
    fun overlayPanApplied() = runTest(testDispatcher) {
        val overlay = object : MixOverlay {
            override fun pan(t: Int, vibePan: Float) = if (t == 5) 0.35f else vibePan
            override fun reverbSend(t: Int, section: Int, base: Float) = base
            override fun delaySend(t: Int, section: Int, base: Float) = base
        }
        val got = run(overlay, installCall = true)
        assertEquals(0.35f, got.last { it.first == "track_5_pan" }.second)
    }

    @Test
    fun sectionFlipKeepsOverlay() = runTest(testDispatcher) {
        val section = 3
        val sec = lore.arrangement!!.sections[section]
        val base = sec.trackOverrides?.get(4)?.reverbSend ?: lore.tracks[4].engineEdm.reverbSend
        assertTrue(base > 0f, "track 4 needs a reverb send in section $section")
        val doubling = object : MixOverlay {
            override fun pan(t: Int, vibePan: Float) = vibePan
            override fun reverbSend(t: Int, section: Int, base: Float) = if (t == 4) base * 2f else base
            override fun delaySend(t: Int, section: Int, base: Float) = base
        }
        val plain = run(null, installCall = false, sections = listOf(section))
            .last { it.first == "track_4_reverb_send" }.second
        val doubled = run(doubling, installCall = true, sections = listOf(section))
            .last { it.first == "track_4_reverb_send" }.second
        assertTrue(plain > 0f)
        assertEquals(plain * 2f, doubled, 1e-6f)
    }

    @Test
    fun sectionPanGoesToTheGlidePortThroughTheOverlay() = runTest(testDispatcher) {
        val sections = lore.arrangement!!.sections
        val introPan = sections[0].trackOverrides?.get(5)?.pan ?: lore.tracks[5].pan
        val s = sections.indices.first { it > 0 && (sections[it].trackOverrides?.get(5)?.pan ?: introPan) != introPan }
        val sectionPan = sections[s].trackOverrides!![5]!!.pan!!

        val plain = run(null, installCall = false, sections = listOf(s))
        // A section change writes only the glide target; the snapping pan port keeps the load value.
        assertEquals(sectionPan, plain.last { it.first == "track_5_pan_glide" }.second)
        assertEquals(introPan, plain.last { it.first == "track_5_pan" }.second)

        val benchPan = object : MixOverlay {
            override fun pan(t: Int, vibePan: Float) = if (t == 5) 0.35f else vibePan
            override fun reverbSend(t: Int, section: Int, base: Float) = base
            override fun delaySend(t: Int, section: Int, base: Float) = base
        }
        val overlaid = run(benchPan, installCall = true, sections = listOf(s))
        assertEquals(0.35f, overlaid.last { it.first == "track_5_pan_glide" }.second)
        assertEquals(0.35f, overlaid.last { it.first == "track_5_pan" }.second)
    }

    private fun TestScope.makeViewModel(engine: SongEndingStubSynthEngine, vibe: Vibe, scope: FeatureCoroutineScope): PulsarViewModel {
        val provider = VibeCatalogScan.allProviders().first { it.vibe.name == vibe.name }
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value ->
                    val f = when (value) {
                        is PortValue.FloatValue -> value.value
                        is PortValue.IntValue -> value.value.toFloat()
                        is PortValue.BoolValue -> if (value.value) 1f else 0f
                        else -> null
                    }
                    if (f != null) writes += id.symbol to f
                    true
                },
                getter = { null },
            )
        }
        val tempo = GlobalTempo(NoopAudioEngine())
        val dispatchers = FixturesDispatchers(testDispatcher)
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), dispatchers),
            globalTempo = tempo,
            appPreferencesRepository = MemoryPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = scope,
            vibeProviders = setOf(provider),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
        )
    }
}

private class NoopAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 48000
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class MemoryPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) { prefs = transform(prefs) }
}
