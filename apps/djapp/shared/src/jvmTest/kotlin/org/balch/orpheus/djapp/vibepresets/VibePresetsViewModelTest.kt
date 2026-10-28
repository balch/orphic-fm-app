package org.balch.orpheus.djapp.vibepresets

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.DjSymbol
import org.balch.orpheus.core.plugin.symbols.HornSymbol
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.core.preferences.VibePreset
import org.balch.orpheus.core.preferences.VibePresetSlot
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeAppliedListener
import org.balch.orpheus.features.pulsar.mixer.TrackGainSink
import org.balch.orpheus.features.pulsar.mixer.TrackTuning
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VibePresetsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: FeatureCoroutineScope

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = FeatureCoroutineScope()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    // Integer ports hold ints in the engine; the controller's 0.5 fallback would round on write-back.
    private val portValues = listOf(
        HornSymbol.BRAKE, DjSymbol.SOURCE_A, DjSymbol.SOURCE_B,
        PulsarSymbol.ROOT_NOTE, PulsarSymbol.SCALE, PulsarSymbol.ENVELOPE_MODE,
    ).associateTo(mutableMapOf<String, PortValue>()) { it.controlId.key to IntValue(0) }

    private val synth = SynthController().apply {
        setDelegates(
            setter = { id, value -> portValues[id.key] = value; true },
            getter = { id -> portValues[id.key] },
        )
    }

    private fun port(id: PluginControlId): Float = synth.controlFlow(id).value.asFloat()

    private fun setPort(id: PluginControlId, v: Float) {
        synth.controlFlow(id).value = FloatValue(v)
    }

    private inner class FakePulsar(
        private val base: PulsarFeature = PulsarViewModel.previewFeature(),
    ) : PulsarFeature by base {
        val listeners = mutableListOf<VibeAppliedListener>()
        var portsBeforeListeners: Map<String, PortValue> = emptyMap()
        var bpm = 120f

        override val vibeFlow = MutableStateFlow(vibe("A"))
        override val vibeLoadCount = MutableStateFlow(0)
        override val baseBpm: Float get() = bpm
        override fun setBaseBpm(bpm: Float) {
            this.bpm = bpm
        }

        override fun addVibeAppliedListener(listener: VibeAppliedListener) {
            listeners += listener
        }

        /** applyVibe's order: the vibe, the EXPLICIT-mode bus reset, the count, then the listeners. */
        fun fireApply(vibe: Vibe, beforeListeners: () -> Unit = {}): Boolean {
            vibeFlow.value = vibe
            for (s in listOf(PulsarSymbol.BASS_GAIN, PulsarSymbol.KEYS_GAIN, PulsarSymbol.FX_GAIN)) {
                setPort(s.controlId, 0.75f)
            }
            vibeLoadCount.value += 1
            beforeListeners()
            portsBeforeListeners = portValues.toMap()
            return listeners.map { it.onVibeApplied(vibe) }.any { it }
        }
    }

    private class Sink : TrackGainSink {
        override val state = MutableStateFlow(TrackTuning())
        override fun toggleMute(t: Int) {}
        override fun toggleSolo(t: Int) {}
        override fun setLevel(t: Int, level: Float) {}
        override fun reset() {}
        override fun load(tuning: TrackTuning) {
            state.value = tuning
        }
    }

    private class MemoryPrefs(
        var prefs: AppPreferences,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : BaseAppPreferencesRepository() {
        private var first = true

        // Only the view model's init load waits; saves read straight through, as a slow first read would.
        override suspend fun load(): AppPreferences {
            if (first) {
                first = false
                gate.await()
            }
            return prefs
        }

        override suspend fun save(preferences: AppPreferences) {
            prefs = preferences
        }
    }

    private object TestDispatchers : DispatcherProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.Main
        override val default = Dispatchers.Main
        override val unconfined = Dispatchers.Main
    }

    private val pulsar = FakePulsar()
    private val sink = Sink()

    private fun vm(prefs: MemoryPrefs, available: Boolean = true) = VibePresetsViewModel(
        pulsar = pulsar,
        synthController = synth,
        trackGainSink = sink,
        appPreferencesRepository = prefs,
        availability = VibePresetsAvailability { available },
        scope = scope,
        dispatcherProvider = TestDispatchers,
        clock = PresetClock { NOW },
    )

    /** Keeps the WhileSubscribed projection running for the test's lifetime. */
    private fun TestScope.watch(vm: VibePresetsViewModel) {
        backgroundScope.launch { vm.stateFlow.collect {} }
    }

    private fun prefsWith(vararg slots: Pair<String, VibePresetSlot>) =
        MemoryPrefs(AppPreferences(vibePresets = mapOf(*slots)))

    private val bassHalf = VibePresetSlot(
        presets = listOf(VibePreset(1, "Half Bass", 10L, mapOf(PresetField.MIX_BASS.key to 0.5f))),
        selectedId = 1,
        nextId = 2,
    )

    private val fullBook = VibePresetSlot(
        presets = (1..5).map { id ->
            // id 3 is the oldest, so the replace default is not just the first row.
            VibePreset(id, "P$id", if (id == 3) 1L else 100L + id, mapOf(PresetField.MIX_KEYS.key to 0.4f))
        },
        selectedId = null,
        nextId = 6,
    )

    @Test
    fun appliesSelectedPresetOnVibeApply() = runTest(dispatcher) {
        vm(prefsWith("A" to bassHalf))

        val claimed = pulsar.fireApply(vibe("A"))

        assertTrue(claimed)
        assertEquals(0.5f, port(PulsarSymbol.BASS_GAIN.controlId))
    }

    @Test
    fun busResetThenPresetKeepsPresetLevels() = runTest(dispatcher) {
        vm(prefsWith("A" to bassHalf))

        pulsar.fireApply(vibe("A"))

        assertEquals(0.5f, port(PulsarSymbol.BASS_GAIN.controlId))
        assertEquals(0.75f, port(PulsarSymbol.KEYS_GAIN.controlId))
        assertEquals(0.75f, port(PulsarSymbol.FX_GAIN.controlId))
    }

    @Test
    fun noPresetReturnsFalseAndLeavesPorts() = runTest(dispatcher) {
        vm(prefsWith("B" to bassHalf))

        val claimed = pulsar.fireApply(vibe("A"))

        assertFalse(claimed)
        assertEquals(pulsar.portsBeforeListeners, portValues)
        assertEquals(120f, pulsar.bpm)
    }

    @Test
    fun appliesSelectedPresetWhenStoreLoadsLate() = runTest(dispatcher) {
        val prefs = MemoryPrefs(AppPreferences(vibePresets = mapOf("A" to bassHalf)), CompletableDeferred())
        vm(prefs)

        assertFalse(pulsar.fireApply(vibe("A")))
        assertEquals(0.75f, port(PulsarSymbol.BASS_GAIN.controlId))

        prefs.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0.5f, port(PulsarSymbol.BASS_GAIN.controlId))
    }

    @Test
    fun lateLoadBeforeFirstApplyWritesNothing() = runTest(dispatcher) {
        val hornFast = VibePresetSlot(
            presets = listOf(VibePreset(1, "Fast Horn", 10L, mapOf(PresetField.HORN_SPEED.key to 0.9f))),
            selectedId = 1,
            nextId = 2,
        )
        pulsar.vibeFlow.value = vibe("B")
        val before = portValues.toMap()
        val vm = vm(prefsWith("B" to hornFast))
        watch(vm)
        advanceUntilIdle()

        assertEquals(before, portValues)

        pulsar.fireApply(vibe("A"))
        vm.actions.openCapture()

        assertEquals(0.5f, port(HornSymbol.SPEED.controlId))
        assertTrue(vm.stateFlow.value.draft!!.chips.isEmpty())
    }

    @Test
    fun lateLoadDuringApplyLeavesBaselineClean() = runTest(dispatcher) {
        val prefs = MemoryPrefs(AppPreferences(vibePresets = mapOf("A" to bassHalf)), CompletableDeferred())
        val vm = vm(prefs)
        watch(vm)

        // The store lands after the count bump but before the listener runs.
        val claimed = pulsar.fireApply(vibe("A")) {
            prefs.gate.complete(Unit)
            testScheduler.advanceUntilIdle()
        }

        assertTrue(claimed)
        assertEquals(0.5f, port(PulsarSymbol.BASS_GAIN.controlId))
        vm.actions.loadOriginal()
        assertEquals(0.75f, port(PulsarSymbol.BASS_GAIN.controlId))
    }

    private val hornPreset = VibePresetSlot(
        presets = listOf(VibePreset(1, "Fast Horn", 10L, mapOf(PresetField.HORN_SPEED.key to 0.6f))),
        selectedId = 1,
        nextId = 2,
    )

    @Test
    fun sameVibeReapplyKeepsBaselineUnderPreset() = runTest(dispatcher) {
        val vm = vm(prefsWith("A" to hornPreset))
        watch(vm)
        setPort(HornSymbol.SPEED.controlId, 0.3f)
        pulsar.fireApply(vibe("A"))
        assertEquals(0.6f, port(HornSymbol.SPEED.controlId))

        // The launch graph-ready re-apply: Horn carries over, so the ports still hold the preset.
        assertTrue(pulsar.fireApply(vibe("A")))
        assertEquals(0.6f, port(HornSymbol.SPEED.controlId))

        vm.actions.loadOriginal()
        assertEquals(0.3f, port(HornSymbol.SPEED.controlId))
    }

    @Test
    fun sameVibeReapplyAfterSelectKeepsBaseline() = runTest(dispatcher) {
        val vm = vm(prefsWith("A" to hornPreset.copy(selectedId = null)))
        watch(vm)
        setPort(HornSymbol.SPEED.controlId, 0.3f)
        pulsar.fireApply(vibe("A"))
        vm.actions.select(1)

        pulsar.fireApply(vibe("A"))
        vm.actions.loadOriginal()

        assertEquals(0.3f, port(HornSymbol.SPEED.controlId))
    }

    @Test
    fun sameVibeReapplyWithoutPresetRetakesBaseline() = runTest(dispatcher) {
        val vm = vm(prefsWith())
        watch(vm)
        setPort(HornSymbol.SPEED.controlId, 0.3f)
        pulsar.fireApply(vibe("A"))
        setPort(HornSymbol.SPEED.controlId, 0.6f)

        pulsar.fireApply(vibe("A"))
        vm.actions.openCapture()

        assertTrue(vm.stateFlow.value.draft!!.chips.isEmpty())
        vm.actions.loadOriginal()
        assertEquals(0.6f, port(HornSymbol.SPEED.controlId))
    }

    @Test
    fun loadBeforeRegistrationAppliesWhenStoreLands() = runTest(dispatcher) {
        // vibeFlow is already "A" and loaded once before the view model exists.
        pulsar.vibeLoadCount.value = 1
        setPort(HornSymbol.SPEED.controlId, 0.3f)
        val prefs = MemoryPrefs(AppPreferences(vibePresets = mapOf("A" to hornPreset)), CompletableDeferred())
        val vm = vm(prefs)
        watch(vm)

        prefs.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0.6f, port(HornSymbol.SPEED.controlId))
        vm.actions.loadOriginal()
        assertEquals(0.3f, port(HornSymbol.SPEED.controlId))
    }

    @Test
    fun baselineIsTakenAtApply() = runTest(dispatcher) {
        val vm = vm(prefsWith())
        watch(vm)
        setPort(HornSymbol.SPEED.controlId, 0.3f)
        pulsar.fireApply(vibe("A"))
        setPort(HornSymbol.SPEED.controlId, 0.6f)

        vm.actions.openCapture()

        val draft = vm.stateFlow.value.draft!!
        assertTrue("HORN: SPEED 30%→60%" in draft.chips, draft.chips.toString())
    }

    @Test
    fun changedFlagTracksSelectedPreset() = runTest(dispatcher) {
        val vm = vm(prefsWith("A" to bassHalf.copy(selectedId = null)))
        watch(vm)
        pulsar.fireApply(vibe("A"))

        vm.actions.select(1)
        assertEquals(1, vm.stateFlow.value.selectedId)
        assertFalse(vm.stateFlow.value.changed)

        setPort(PulsarSymbol.BASS_GAIN.controlId, 0.6f)
        assertTrue(vm.stateFlow.value.changed)
    }

    @Test
    fun loadOriginalRestoresBaselineAndClearsSelection() = runTest(dispatcher) {
        val prefs = prefsWith("A" to bassHalf)
        val vm = vm(prefs)
        watch(vm)
        pulsar.fireApply(vibe("A"))
        assertEquals(0.5f, port(PulsarSymbol.BASS_GAIN.controlId))

        vm.actions.loadOriginal()

        assertEquals(0.75f, port(PulsarSymbol.BASS_GAIN.controlId))
        assertNull(vm.stateFlow.value.selectedId)
        assertFalse(vm.stateFlow.value.changed)
        assertNull(prefs.prefs.vibePresets.getValue("A").selectedId)
    }

    @Test
    fun saveCaptureWhenFullUsesReplaceId() = runTest(dispatcher) {
        val prefs = prefsWith("A" to fullBook)
        val vm = vm(prefs)
        watch(vm)
        pulsar.fireApply(vibe("A"))
        setPort(PulsarSymbol.BASS_GAIN.controlId, 0.2f)

        vm.actions.openCapture()
        val draft = vm.stateFlow.value.draft!!
        assertTrue(draft.full)
        assertEquals(3, draft.replaceDefaultId)

        vm.actions.saveCapture("New", 4)

        val slot = prefs.prefs.vibePresets.getValue("A")
        assertEquals(listOf(1, 2, 3, 6, 5), slot.presets.map { it.id })
        val saved = slot.presets.first { it.id == 6 }
        assertEquals("New", saved.name)
        assertEquals(NOW, saved.capturedAtMs)
        assertEquals(0.2f, saved.values[PresetField.MIX_BASS.key])
        assertEquals(mapOf(PresetField.MIX_BASS.key to 0.75f), saved.originals)
        assertEquals(6, slot.selectedId)
        assertNull(vm.stateFlow.value.draft)
    }

    @Test
    fun staleReplaceIdOnFullBookSavesNothing() = runTest(dispatcher) {
        val prefs = prefsWith("A" to fullBook)
        val vm = vm(prefs)
        watch(vm)
        pulsar.fireApply(vibe("A"))
        setPort(PulsarSymbol.BASS_GAIN.controlId, 0.2f)
        vm.actions.openCapture()

        vm.actions.saveCapture("New", 99)
        vm.actions.saveCapture("New", null)

        assertEquals(fullBook, prefs.prefs.vibePresets.getValue("A"))
        assertEquals(listOf(1, 2, 3, 4, 5), vm.stateFlow.value.presets.map { it.id })
        assertTrue(vm.stateFlow.value.draft != null)
    }

    @Test
    fun captureBeforeStoreLoadKeepsStoredVibes() = runTest(dispatcher) {
        val prefs = MemoryPrefs(AppPreferences(vibePresets = mapOf("Gone Vibe" to bassHalf)), CompletableDeferred())
        val vm = vm(prefs)
        pulsar.fireApply(vibe("A"))
        setPort(PulsarSymbol.BASS_GAIN.controlId, 0.3f)
        vm.actions.openCapture()
        vm.actions.saveCapture("Mine", null)

        prefs.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(setOf("Gone Vibe", "A"), prefs.prefs.vibePresets.keys)
        assertEquals(listOf("Mine"), prefs.prefs.vibePresets.getValue("A").presets.map { it.name })
    }

    @Test
    fun cancelCaptureDropsNothing() = runTest(dispatcher) {
        val prefs = prefsWith("A" to fullBook)
        val vm = vm(prefs)
        watch(vm)
        pulsar.fireApply(vibe("A"))

        vm.actions.openCapture()
        vm.actions.cancelCapture()

        val state = vm.stateFlow.value
        assertNull(state.draft)
        assertEquals(listOf(1, 2, 3, 4, 5), state.presets.map { it.id })
        assertEquals(fullBook, prefs.prefs.vibePresets.getValue("A"))
    }

    @Test
    fun blankNameFallsBackToSuggestion() = runTest(dispatcher) {
        val prefs = prefsWith()
        val vm = vm(prefs)
        watch(vm)
        pulsar.fireApply(vibe("A"))
        setPort(PulsarSymbol.BASS_GAIN.controlId, 0.3f)

        vm.actions.openCapture()
        val suggested = vm.stateFlow.value.draft!!.suggestedName
        vm.actions.saveCapture("   ", null)

        assertEquals("Deep Bass", suggested)
        assertEquals(listOf(suggested), prefs.prefs.vibePresets.getValue("A").presets.map { it.name })
    }

    @Test
    fun deleteSelectedKeepsSoundAndClearsSelection() = runTest(dispatcher) {
        val vm = vm(prefsWith("A" to bassHalf))
        watch(vm)
        pulsar.fireApply(vibe("A"))
        val before = portValues.toMap()

        vm.actions.delete(1)

        assertEquals(before, portValues)
        val state = vm.stateFlow.value
        assertNull(state.selectedId)
        assertTrue(state.presets.isEmpty())
        // The sound still differs from the vibe's original, so the now-dot shows.
        assertTrue(state.changed)
    }

    @Test
    fun presetsForUnknownVibeAreKeptOnSave() = runTest(dispatcher) {
        val prefs = prefsWith("Gone Vibe" to bassHalf)
        val vm = vm(prefs)
        pulsar.fireApply(vibe("A"))
        setPort(PulsarSymbol.BASS_GAIN.controlId, 0.3f)

        vm.actions.openCapture()
        vm.actions.saveCapture("Mine", null)

        assertEquals(setOf("Gone Vibe", "A"), prefs.prefs.vibePresets.keys)
        assertEquals(bassHalf, prefs.prefs.vibePresets.getValue("Gone Vibe"))
    }

    @Test
    fun barShownIsPersisted() = runTest(dispatcher) {
        val prefs = prefsWith()
        val vm = vm(prefs)
        watch(vm)

        vm.actions.setBarShown(true)

        assertTrue(vm.stateFlow.value.barShown)
        assertTrue(prefs.prefs.vibePresetBarShown)
    }

    @Test
    fun unavailableEditionRegistersNoListener() = runTest(dispatcher) {
        val vm = vm(prefsWith("A" to bassHalf), available = false)
        watch(vm)

        assertTrue(pulsar.listeners.isEmpty())
        assertFalse(vm.stateFlow.value.available)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        fun vibe(name: String): Vibe = PulsarViewModel.previewFeature().vibeFlow.value.copy(name = name)
    }
}
