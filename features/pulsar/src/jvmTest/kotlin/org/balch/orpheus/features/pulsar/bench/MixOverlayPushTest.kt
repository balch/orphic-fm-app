package org.balch.orpheus.features.pulsar.bench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.OrpheusEngineId
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
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.StubSongEndingEventSource
import org.balch.orpheus.features.pulsar.StubSongEndingPreferences
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.StubTransitionRunner
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.models.SlotLock
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.VibeCatalogScan
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // Bench delay: the vibe ports take the whole-vibe value, the bench ports the section's; empty maps hand both back.
    @Test
    fun benchDelayWritesTheVibeAndBenchPorts() = runTest(testDispatcher) {
        writes.clear()
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), lore, scope, MemoryPrefs())
        vm.actions.setVibe(lore)
        advanceUntilIdle()
        fun last(symbol: String) = writes.last { it.first == symbol }.second

        vm.setBenchDelay(mapOf(DelayFx.TIME_A to 0.6f), mapOf(DelayFx.FEEDBACK to 0.8f))
        advanceUntilIdle()
        assertEquals(0.6f, last("pulsar_delay_time_a"))
        assertEquals(lore.effects.delayTimeB, last("pulsar_delay_time_b"))
        assertEquals(0.8f, last("pulsar_bench_delay_feedback"))
        assertEquals(-1f, last("pulsar_bench_delay_time_a"))

        vm.setBenchDelay(emptyMap(), emptyMap())
        advanceUntilIdle()
        assertEquals(lore.effects.delayTimeA, last("pulsar_delay_time_a"))
        assertEquals(-1f, last("pulsar_bench_delay_feedback"))
        scope.cancel()
    }

    // A voice knob retunes the playing vibe: its ports move, the load count and the song do not.
    @Test
    fun retuneWritesTheKnobPortsWithoutALoad() = runTest(testDispatcher) {
        writes.clear()
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), lore, scope)
        vm.actions.setVibe(lore)
        advanceUntilIdle()
        val loads = vm.vibeLoadCount.value
        writes.clear()

        val tuned = lore.withEngineKnob(2, EngineSlot.EDM, EngineKnob.HARMONICS, 0.2f)
            .withEngineKnob(2, EngineSlot.SPACE, EngineKnob.MORPH, 0.8f)
        assertTrue(vm.retuneVibe(tuned))
        advanceUntilIdle()

        assertEquals(loads, vm.vibeLoadCount.value)
        assertEquals(tuned, vm.vibeFlow.value)
        val last = writes.toMap()
        assertEquals(0.2f, last["track_2_harmonics"])
        assertEquals(1f, last["track_2_pin_harmonics"])
        assertEquals(0.8f, last["track_2_morph_space"])
        assertEquals(1f, last["track_2_pin_morph_space"])
        assertTrue(writes.none { it.first.startsWith("track_3_") }, "an unchanged track is not re-pushed")
        assertTrue(writes.none { it.first == "vibe_generation" }, "no reload")

        // Anything but a knob field is a reload's job.
        assertFalse(vm.retuneVibe(tuned.copy(bpm = tuned.bpm + 1f)))
        assertEquals(tuned, vm.vibeFlow.value)
        scope.cancel()
    }

    // An engine switch retunes too: only the switched slot's engine port takes the new id, with no load.
    @Test
    fun retuneWritesTheSwitchedEnginePortWithoutALoad() = runTest(testDispatcher) {
        writes.clear()
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), lore, scope)
        vm.actions.setVibe(lore)
        advanceUntilIdle()
        val loads = vm.vibeLoadCount.value
        writes.clear()

        val track = lore.tracks[4]
        assertTrue(track.engineEdm.engineId != OrpheusEngineId.VOWEL_FOF)
        val switched = lore.copy(
            tracks = lore.tracks.toMutableList().also {
                it[4] = track.copy(engineEdm = track.engineEdm.copy(engineId = OrpheusEngineId.VOWEL_FOF))
            },
        )
        assertTrue(vm.retuneVibe(switched))
        advanceUntilIdle()

        assertEquals(loads, vm.vibeLoadCount.value)
        assertEquals(switched, vm.vibeFlow.value)
        val last = writes.toMap()
        assertEquals(107f, last["track_4_engine_edm"])
        assertEquals(track.engineSpace.engineId.id.toFloat(), last["track_4_engine_space"])
        assertTrue(writes.none { it.first.startsWith("track_3_") }, "an unchanged track is not re-pushed")
        assertTrue(writes.none { it.first == "vibe_generation" }, "no reload")
        scope.cancel()
    }

    // A lock edit retunes: only the lock port moves (0 Auto, 1 EDM, 2 SPACE), with no load.
    @Test
    fun retuneWritesTheSlotLockPort() = runTest(testDispatcher) {
        writes.clear()
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), lore, scope)
        vm.actions.setVibe(lore)
        advanceUntilIdle()
        val loads = vm.vibeLoadCount.value
        writes.clear()

        val locked = lore.copy(
            tracks = lore.tracks.toMutableList().also { it[4] = it[4].copy(slotLock = SlotLock.SPACE) },
        )
        assertTrue(vm.retuneVibe(locked))
        advanceUntilIdle()

        assertEquals(loads, vm.vibeLoadCount.value)
        assertEquals(locked, vm.vibeFlow.value)
        assertEquals(2f, writes.last { it.first == "track_4_slot_lock" }.second)
        assertTrue(writes.none { it.first.startsWith("track_3_") }, "an unchanged track is not re-pushed")
        assertTrue(writes.none { it.first == "vibe_generation" }, "no reload")

        assertTrue(vm.retuneVibe(lore))
        advanceUntilIdle()
        assertEquals(0f, writes.last { it.first == "track_4_slot_lock" }.second, "back to auto writes 0")
        scope.cancel()
    }

    // C++ never resets the lock, so every load writes it: EDM as 1, and AUTO as 0.
    @Test
    fun applyVibeWritesTheSlotLockPort() = runTest(testDispatcher) {
        writes.clear()
        val locked = lore.copy(
            tracks = lore.tracks.toMutableList().also { it[2] = it[2].copy(slotLock = SlotLock.EDM) },
        )
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), locked, scope)
        vm.actions.setVibe(locked)
        advanceUntilIdle()
        assertEquals(1f, writes.last { it.first == "track_2_slot_lock" }.second)
        assertEquals(0f, writes.last { it.first == "track_3_slot_lock" }.second)

        vm.actions.setVibe(lore)
        advanceUntilIdle()
        assertEquals(0f, writes.last { it.first == "track_2_slot_lock" }.second, "a vibe with AUTO writes 0")
        scope.cancel()
    }

    // A VA -> DX retune writes the vibe's raw pin: C++ forces the DX pin once the new engine sounds.
    @Test
    fun retuneToDxWritesTheRawHarmonicsPin() = runTest(testDispatcher) {
        writes.clear()
        val track = lore.tracks[4]
        val onVa = lore.copy(
            tracks = lore.tracks.toMutableList().also {
                it[4] = track.copy(engineEdm = track.engineEdm.copy(engineId = OrpheusEngineId.VA, pinHarmonics = false))
            },
        )
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), onVa, scope)
        vm.actions.setVibe(onVa)
        advanceUntilIdle()

        val toDx = onVa.copy(
            tracks = onVa.tracks.toMutableList().also {
                it[4] = it[4].copy(engineEdm = it[4].engineEdm.copy(engineId = OrpheusEngineId.DX))
            },
        )
        assertTrue(vm.retuneVibe(toDx))
        advanceUntilIdle()

        assertEquals(2f, writes.last { it.first == "track_4_engine_edm" }.second)
        assertEquals(0f, writes.last { it.first == "track_4_pin_harmonics" }.second)
        scope.cancel()
    }

    // Section-held macros: the engine and the sends play them; the knob and the save never see them.
    @Test
    fun benchMacrosReachTheEngineButNeverTheSavedState() = runTest(testDispatcher) {
        writes.clear()
        val prefs = MemoryPrefs()
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), lore, scope, prefs)
        vm.actions.setVibe(lore)
        advanceUntilIdle()           // the graph-ready re-apply resets the knobs to the vibe's
        vm.actions.setDeep(0.5f)
        vm.actions.setEnergy(0.8f)
        advanceUntilIdle()
        val track = (0 until 8).first { t -> writes.last { it.first == "track_${t}_reverb_send" }.second > 0f }
        fun send() = writes.last { it.first == "track_${track}_reverb_send" }.second
        val sendAtKnob = send()

        vm.setBenchMacros(mapOf(Macro.ENERGY to 0.2f, Macro.SPACE to 0f))
        advanceUntilIdle()
        assertEquals(0.2f, writes.last { it.first == "energy" }.second)
        assertEquals(0f, writes.last { it.first == "space" }.second)
        // SPACE scales the sends in Kotlin, so the bench value has to reach them too.
        val floor = lore.effects.deepFloor
        assertEquals(sendAtKnob * floor / (floor + lore.space * (1f - floor)), send(), 1e-6f)
        // The knob, the UI state and the saved blob all keep the app's values.
        assertEquals(0.8f, vm.macroKnob(Macro.ENERGY))
        assertEquals(0.8f, vm.stateFlow.value.energy)
        val saved = Json { ignoreUnknownKeys = true }
            .decodeFromString(PulsarUiState.serializer(), prefs.load().lastPulsarJson!!)
        assertEquals(0.8f, saved.energy)
        assertEquals(lore.space, saved.space)

        vm.setBenchMacros(emptyMap())
        advanceUntilIdle()
        assertEquals(0.8f, writes.last { it.first == "energy" }.second)
        assertEquals(lore.space, writes.last { it.first == "space" }.second)
        assertEquals(sendAtKnob, send(), 1e-6f)
        scope.cancel()
    }

    // The bench moves macros through these actions, so its moves must play, rescale sends and save like a hand.
    @Test
    fun macroActionsReachTheEngineTheSendsAndTheSavedState() = runTest(testDispatcher) {
        writes.clear()
        val prefs = MemoryPrefs()
        val scope = FeatureCoroutineScope()
        val vm = makeViewModel(SongEndingStubSynthEngine(), lore, scope, prefs)
        vm.actions.setVibe(lore)
        advanceUntilIdle()           // the graph-ready re-apply resets the knobs to the vibe's
        vm.actions.setDeep(0.5f)
        advanceUntilIdle()
        val track = (0 until 8).first { t -> writes.last { it.first == "track_${t}_reverb_send" }.second > 0f }
        fun send() = writes.last { it.first == "track_${track}_reverb_send" }.second
        val sendAtKnob = send()

        vm.actions.setEnergy(0.2f)
        vm.actions.setSpace(0f)
        advanceUntilIdle()
        assertEquals(0.2f, writes.last { it.first == "energy" }.second)
        assertEquals(0f, writes.last { it.first == "space" }.second)
        // SPACE scales the sends in Kotlin, so the knob move has to reach them too.
        val floor = lore.effects.deepFloor
        assertEquals(sendAtKnob * floor / (floor + lore.space * (1f - floor)), send(), 1e-6f)
        assertEquals(0.2f, vm.macroKnob(Macro.ENERGY))
        assertEquals(0f, vm.macroKnob(Macro.SPACE))
        assertEquals(0.2f, vm.stateFlow.value.energy)
        val saved = Json { ignoreUnknownKeys = true }
            .decodeFromString(PulsarUiState.serializer(), prefs.load().lastPulsarJson!!)
        assertEquals(0.2f, saved.energy)
        assertEquals(0f, saved.space)
        scope.cancel()
    }

    private fun TestScope.makeViewModel(
        engine: SongEndingStubSynthEngine,
        vibe: Vibe,
        scope: FeatureCoroutineScope,
        prefs: AppPreferencesRepository = MemoryPrefs(),
    ): PulsarViewModel {
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
            appPreferencesRepository = prefs,
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
