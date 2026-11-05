package org.balch.orpheus.features.cowbell

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.AudioHostHold
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.COWBELL_URI
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.plugins.cowbell.CowbellBody
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.plugins.cowbell.cowbellStrikeValue
import org.balch.orpheus.plugins.cowbell.strikeKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CowbellViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scope = FeatureCoroutineScope() // reads Dispatchers.Main, so after setMain
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** Engine ports the controller reads and writes, seeded with the plugin defaults. */
    private val knobPorts = mutableMapOf<String, PortValue>(
        "level" to FloatValue(0.7f),
        "more" to FloatValue(0f),
        "body" to IntValue(2),
        "tone" to FloatValue(0f),
        "ring" to FloatValue(0.48f),
        "intensity" to FloatValue(0.85f),
        "octave" to IntValue(0),
        "grid" to BoolValue(true),
        "grid_strength" to FloatValue(0.7f),
        "mode" to IntValue(0),
        "fill" to IntValue(0),
    )
    private val gridWrites = mutableListOf<PortValue>()
    private val engine = RecordingSynthEngine()
    private val prefs = MemoryPrefs()
    private val hostHold = AudioHostHold()
    private lateinit var scope: FeatureCoroutineScope
    private lateinit var controller: SynthController

    private fun makeViewModel(
        restoreStrategy: RestoreStrategy = RestoreStrategy.USER_PREFERENCES,
    ): CowbellViewModel {
        controller = SynthController().apply {
            setDelegates(
                setter = { id, value ->
                    if (id.symbol == "grid") gridWrites += value
                    knobPorts[id.symbol] = value
                    true
                },
                getter = { id -> knobPorts[id.symbol] },
            )
        }
        return CowbellViewModel(
            synthEngine = engine,
            synthController = controller,
            dispatcherProvider = UnconfinedDispatchers,
            scope = scope,
            appPreferences = prefs,
            hostHold = hostHold,
            // The test dispatcher, so the 2 s save debounce runs on virtual time.
            persistence = FeatureStatePersistence(prefs, TestIoDispatchers(testDispatcher), scope),
            restoreStrategy = restoreStrategy,
        )
    }

    /** Collects the state so the WhileSubscribed projection runs. */
    private fun TestScope.observe(vm: CowbellViewModel) {
        backgroundScope.launch { vm.stateFlow.collect { } }
        runCurrent() // advanceUntilIdle skips background work, so start the collector first
        advanceUntilIdle()
    }

    /** Writes since [from], as (symbol, value). Every one must be addressed to the cowbell. */
    private fun writes(from: Int = 0): List<Pair<String, PortValue>> =
        engine.writes.drop(from).map { (uri, symbol, value) ->
            assertEquals(COWBELL_URI, uri)
            symbol to value
        }

    private fun Pair<String, PortValue>.floatValue(): Float = second.asFloat()

    @Test
    fun strikeWritesOffsetsThenPackedStrike() = runTest(testDispatcher) {
        val vm = makeViewModel()

        vm.actions.onStrike(0.8f, 1f, 0f)

        val written = writes()
        assertEquals(
            listOf("position_offset", "structure_offset", "strike"),
            written.map { it.first },
            "offsets land before the strike so the hit uses them",
        )
        assertEquals(0.45f, written[0].floatValue(), 1e-5f)
        assertEquals(0.4f, written[1].floatValue(), 1e-5f)
        assertEquals(FloatValue(cowbellStrikeValue(0.8f)), written[2].second)
    }

    @Test
    fun strikeCentreOfBellLeavesOffsetsAtZero() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onStrike(1f, 0.5f, 0.5f)
        val written = writes()
        assertEquals(0f, written[0].floatValue(), 1e-6f)
        assertEquals(0f, written[1].floatValue(), 1e-6f)
    }

    @Test
    fun twoIdenticalStrikesAreBothSent() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onStrike(0.8f, 0.5f, 0.5f)
        vm.actions.onStrike(0.8f, 0.5f, 0.5f)
        assertEquals(2, writes().count { it.first == "strike" })
    }

    @Test
    fun gesturePortsNeverGoThroughTheController() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMode(CowbellMode.SYNTH)
        vm.actions.onStrike(0.8f, 0.2f, 0.9f)
        vm.actions.onHold(0.3f, 0.3f)
        vm.actions.onBend(0.5f)
        vm.actions.onRelease()
        val gesture = setOf("position_offset", "structure_offset", "strike", "gate", "bend")
        assertTrue(knobPorts.keys.none { it in gesture }, "controller saw ${knobPorts.keys}")
    }

    @Test
    fun synthHoldGatesAndReleaseUngates() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)
        vm.actions.onMode(CowbellMode.SYNTH)
        advanceUntilIdle()

        vm.actions.onHold(0.5f, 0.5f)
        assertEquals(
            listOf("position_offset", "structure_offset", "gate"),
            writes().map { it.first },
        )
        assertEquals(BoolValue(true), writes().last().second)
        assertTrue(vm.stateFlow.value.holding)

        val before = engine.writes.size
        vm.actions.onRelease()
        assertEquals(listOf("gate" to BoolValue(false)), writes(before))
        advanceUntilIdle()
        assertTrue(!vm.stateFlow.value.holding)
    }

    @Test
    fun tapHoldStrikesAndNeverGates() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMode(CowbellMode.TAP)
        vm.actions.onHold(0.5f, 0.5f)
        vm.actions.onRelease()
        val symbols = writes().map { it.first }
        assertEquals(listOf("position_offset", "structure_offset", "strike"), symbols)
        assertEquals(FloatValue(cowbellStrikeValue(0.8f)), writes().last().second)
    }

    @Test
    fun shakeHoldBehavesLikeTap() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMode(CowbellMode.SHAKE)
        vm.actions.onHold(0.5f, 0.5f)
        vm.actions.onRelease()
        assertEquals(listOf("position_offset", "structure_offset", "strike"), writes().map { it.first })
        assertEquals(FloatValue(cowbellStrikeValue(0.8f)), writes().last().second)
    }

    @Test
    fun tiltOwnsStructureWhenMotionAvailable() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMotionAvailable(true)
        vm.actions.onMotion(true)

        vm.actions.onStrike(0.8f, 1f, 0f)
        val struck = writes()
        assertEquals(listOf("position_offset", "strike"), struck.map { it.first })
        assertEquals(0.45f, struck[0].floatValue(), 1e-5f)

        val before = engine.writes.size
        vm.actions.onTilt(0.3f)
        assertEquals(listOf("structure_offset"), writes(before).map { it.first })
        assertEquals(0.3f, writes(before)[0].floatValue(), 1e-6f)

        val afterTilt = engine.writes.size
        vm.actions.onTilt(0.301f)
        assertEquals(afterTilt, engine.writes.size, "a move under 0.002 writes nothing")

        vm.actions.onTilt(0.303f)
        assertEquals(0.303f, writes(afterTilt).single().floatValue(), 1e-6f)
    }

    @Test
    fun tiltOwnsStructureForHoldAndMoveToo() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMotionAvailable(true)
        vm.actions.onMotion(true)
        vm.actions.onMode(CowbellMode.SYNTH)
        vm.actions.onHold(0.2f, 0.9f)
        vm.actions.onMove(0.3f, 0.1f)
        assertEquals(
            listOf("position_offset", "gate", "position_offset"),
            writes().map { it.first },
        )
    }

    @Test
    fun fingerOwnsStructureWithoutMotion() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMotionAvailable(false)
        vm.actions.onStrike(0.8f, 1f, 0f)
        vm.actions.onMove(0f, 1f)
        assertEquals(
            listOf("position_offset", "structure_offset", "strike", "position_offset", "structure_offset"),
            writes().map { it.first },
        )
        assertEquals(0.4f, writes()[1].floatValue(), 1e-5f)
    }

    @Test
    fun tiltResendsAfterTheFingerTookStructureBack() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMotionAvailable(true)
        vm.actions.onTilt(0.3f)
        vm.actions.onMotionAvailable(false)
        vm.actions.onMove(0.5f, 0f)
        vm.actions.onMotionAvailable(true)
        val before = engine.writes.size
        vm.actions.onTilt(0.3f)
        assertEquals(listOf("structure_offset" to FloatValue(0.3f)), writes(before))
    }

    @Test
    fun withMotionOffTheFingerOwnsStructureOnAPhone() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMotionAvailable(true)
        vm.actions.onStrike(0.8f, 1f, 0f)
        assertEquals(listOf("position_offset", "structure_offset", "strike"), writes().map { it.first })
    }

    @Test
    fun shakeFollowsTheMotionSettingToo() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMotionAvailable(true)
        vm.actions.onMode(CowbellMode.SHAKE)
        vm.actions.onHold(0.2f, 0.9f)
        assertEquals(listOf("position_offset", "structure_offset", "strike"), writes().map { it.first })
        vm.actions.onMotion(true)
        val before = engine.writes.size
        vm.actions.onHold(0.2f, 0.9f)
        assertEquals(listOf("position_offset", "strike"), writes(before).map { it.first })
    }

    @Test
    fun turningMotionOffLetsGoOfTheTilt() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMotionAvailable(true)
        vm.actions.onMotion(true)
        vm.actions.onTilt(0.3f)
        val before = engine.writes.size
        vm.actions.onMotion(false)
        assertEquals(listOf("structure_offset" to FloatValue(0f)), writes(before))
    }

    @Test
    fun tiltClampsAndIgnoresNaN() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onTilt(Float.NaN)
        assertTrue(engine.writes.isEmpty(), "a bad reading writes nothing")
        vm.actions.onTilt(5f)
        assertEquals(0.4f, writes().single().floatValue(), 1e-6f)
    }

    @Test
    fun motionAvailabilityShowsInTheState() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)
        assertTrue(!vm.stateFlow.value.motionAvailable)
        vm.actions.onMotionAvailable(true)
        advanceUntilIdle()
        assertTrue(vm.stateFlow.value.motionAvailable)
    }

    @Test
    fun fillStrikeForwardsPacked() = runTest(testDispatcher) {
        val vm = makeViewModel()
        val packed = cowbellStrikeValue(0.6f, CowbellFill.SEEDS.strikeKind, 3)
        vm.actions.onFillStrike(packed)
        assertEquals(listOf("strike" to FloatValue(packed)), writes())
    }

    @Test
    fun releaseUngatesEvenIfModeChangedMidHold() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMode(CowbellMode.SYNTH)
        vm.actions.onHold(0.5f, 0.5f)
        vm.actions.onMode(CowbellMode.TAP)
        vm.actions.onRelease()
        assertEquals("gate" to BoolValue(false), writes().last())
    }

    @Test
    fun moveWritesOffsetsOnly() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMove(0f, 1f)
        val written = writes()
        assertEquals(listOf("position_offset", "structure_offset"), written.map { it.first })
        assertEquals(-0.45f, written[0].floatValue(), 1e-5f)
        assertEquals(-0.4f, written[1].floatValue(), 1e-5f)
    }

    @Test
    fun outOfRangePositionsAreClampedToTheBell() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMove(5f, -5f)
        val written = writes()
        assertEquals(0.45f, written[0].floatValue(), 1e-5f)
        assertEquals(0.4f, written[1].floatValue(), 1e-5f)
    }

    @Test
    fun releaseWithNothingHeldSendsNothing() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onRelease()
        assertTrue(engine.writes.isEmpty())
    }

    @Test
    fun releaseAllClearsGateAndBend() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)
        vm.actions.onMode(CowbellMode.SYNTH)
        advanceUntilIdle()
        vm.actions.onHold(0.5f, 0.5f)
        vm.actions.onBend(0.7f)
        assertEquals("bend" to FloatValue(0.7f), writes().last())

        val before = engine.writes.size
        vm.actions.releaseAll()

        assertEquals(
            setOf("gate" to BoolValue(false), "bend" to FloatValue(0f)),
            writes(before).toSet(),
        )
        advanceUntilIdle()
        assertEquals(0f, vm.stateFlow.value.bend)
        assertTrue(!vm.stateFlow.value.holding)
    }

    @Test
    fun releaseAllIsSafeTwiceAndWithNothingHeld() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.releaseAll()
        assertTrue(engine.writes.isEmpty(), "nothing held, nothing to send")

        vm.actions.onMode(CowbellMode.SYNTH)
        vm.actions.onHold(0.5f, 0.5f)
        vm.actions.releaseAll()
        val after = engine.writes.size
        vm.actions.releaseAll()
        assertEquals(after, engine.writes.size, "the second call finds nothing held")
    }

    @Test
    fun clearingTheScopeReleasesAHeldNote() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onMode(CowbellMode.SYNTH)
        vm.actions.onHold(0.5f, 0.5f)
        val before = engine.writes.size

        scope.cancel()
        // The scope completes once its save job has wound down on the test dispatcher.
        advanceUntilIdle()

        assertEquals(listOf("gate" to BoolValue(false)), writes(before))
    }

    @Test
    fun bendDoneReturnsBendToRest() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)
        vm.actions.onBend(-0.6f)
        advanceUntilIdle()
        assertEquals(-0.6f, vm.stateFlow.value.bend)

        vm.actions.onBendDone()
        advanceUntilIdle()

        assertEquals("bend" to FloatValue(0f), writes().last())
        assertEquals(0f, vm.stateFlow.value.bend)
    }

    @Test
    fun bendIsClamped() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.actions.onBend(4f)
        vm.actions.onBend(Float.NaN)
        assertEquals(listOf(1f, 0f), writes().map { it.floatValue() })
    }

    @Test
    fun knobsWritePorts() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)

        vm.actions.onLevel(0.31f)
        vm.actions.onTone(0.32f)
        vm.actions.onRing(0.33f)
        vm.actions.onIntensity(0.34f)
        vm.actions.onOctave(-1)
        vm.actions.onGrid(true)
        vm.actions.onGridStrength(0.35f)
        vm.actions.onMode(CowbellMode.SYNTH)
        vm.actions.onFill(CowbellFill.SEEDS)
        advanceUntilIdle()

        assertEquals(FloatValue(0.31f), knobPorts["level"])
        assertEquals(FloatValue(0.32f), knobPorts["tone"])
        assertEquals(FloatValue(0.33f), knobPorts["ring"])
        assertEquals(FloatValue(0.34f), knobPorts["intensity"])
        assertEquals(IntValue(-1), knobPorts["octave"])
        assertEquals(BoolValue(true), knobPorts["grid"])
        assertEquals(FloatValue(0.35f), knobPorts["grid_strength"])
        assertEquals(IntValue(CowbellMode.SYNTH.ordinal), knobPorts["mode"])
        assertEquals(IntValue(CowbellFill.SEEDS.ordinal), knobPorts["fill"])

        assertEquals(
            CowbellUiState(
                level = 0.31f, tone = 0.32f, ring = 0.33f, intensity = 0.34f,
                octave = -1, grid = true, gridStrength = 0.35f,
                mode = CowbellMode.SYNTH, fill = CowbellFill.SEEDS,
            ),
            vm.stateFlow.value,
        )
        assertTrue(engine.writes.isEmpty(), "knobs go through the controller, not setPluginPort")
    }

    @Test
    fun onBodyWritesTheOrdinalAndStateFollows() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)

        vm.actions.onBody(CowbellBody.SYNTH)
        advanceUntilIdle()

        assertEquals(IntValue(CowbellBody.SYNTH.ordinal), knobPorts["body"])
        assertEquals(CowbellBody.SYNTH, vm.stateFlow.value.body)
        assertTrue(engine.writes.isEmpty(), "BODY goes through the controller, not setPluginPort")
    }

    @Test
    fun onToneWritesTheTonePort() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)

        vm.actions.onTone(0.62f)
        advanceUntilIdle()

        assertEquals(FloatValue(0.62f), knobPorts["tone"])
        assertEquals(0.62f, vm.stateFlow.value.tone)
    }

    @Test
    fun anOutOfRangeBodyClampsIntoTheEnum() = runTest(testDispatcher) {
        knobPorts["body"] = IntValue(9)
        val vm = makeViewModel()
        observe(vm)
        assertEquals(CowbellBody.SYNTH, vm.stateFlow.value.body)

        knobPorts["body"] = IntValue(-3)
        val low = makeViewModel()
        observe(low)
        assertEquals(CowbellBody.STRING, low.stateFlow.value.body)
    }

    @Test
    fun aBodySetByThePortDirectlyUpdatesTheBody() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)

        // What a preset or MIDI does: write the controller's flow, not the action.
        controller.controlFlow(CowbellSymbol.BODY.controlId).value = IntValue(CowbellBody.SITAR.ordinal)
        advanceUntilIdle()

        assertEquals(CowbellBody.SITAR, vm.stateFlow.value.body)
    }

    @Test
    fun onMoreWritesTheMorePort() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)
        assertEquals(0f, vm.stateFlow.value.more)

        vm.actions.onMore(0.6f)
        advanceUntilIdle()

        assertEquals(FloatValue(0.6f), knobPorts["more"])
        assertEquals(0.6f, vm.stateFlow.value.more)
        assertEquals(0.7f, vm.stateFlow.value.level, "MORE leaves LEVEL alone")
        assertTrue(engine.writes.isEmpty(), "MORE goes through the controller, not setPluginPort")
    }

    @Test
    fun morphStyleStartsAsShards() = runTest(testDispatcher) {
        assertEquals(BellMorphStyle.SHARDS, makeViewModel().morphStyle.value)
    }

    @Test
    fun morphStyleLoadsAndSaves() = runTest(testDispatcher) {
        prefs.saved = AppPreferences(bellMorph = "LIQUID")
        val vm = makeViewModel()
        assertEquals(BellMorphStyle.LIQUID, vm.morphStyle.value)

        vm.setMorphStyle(BellMorphStyle.SHARDS)
        assertEquals(BellMorphStyle.SHARDS, vm.morphStyle.value)
        assertEquals("SHARDS", prefs.saved.bellMorph)
        assertTrue(engine.writes.isEmpty(), "the morph is a look, not a port")
    }

    @Test
    fun anUnknownSavedMorphFallsBackToShards() = runTest(testDispatcher) {
        prefs.saved = AppPreferences(bellMorph = "MELT")
        assertEquals(BellMorphStyle.SHARDS, makeViewModel().morphStyle.value)
    }

    @Test
    fun onLockWritesGridAndStrength() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)

        vm.actions.onLock(0.5f)
        advanceUntilIdle()
        assertEquals(BoolValue(true), knobPorts["grid"])
        assertEquals(0.575f, knobPorts["grid_strength"]!!.asFloat(), 1e-4f)
        assertEquals(0.5f, vm.stateFlow.value.lock, 1e-4f)

        vm.actions.onLock(0f)
        advanceUntilIdle()
        assertEquals(BoolValue(false), knobPorts["grid"])
        assertEquals(0.575f, knobPorts["grid_strength"]!!.asFloat(), 1e-4f, "LOCK 0 leaves the strength alone")
        assertEquals(0f, vm.stateFlow.value.lock, 1e-6f)
    }

    @Test
    fun aLockDragWritesGridOnce() = runTest(testDispatcher) {
        // The drag starts from LOCK off, so the first value above 0 turns GRID on.
        knobPorts["grid"] = BoolValue(false)
        val vm = makeViewModel()
        observe(vm)

        for (lock in listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)) {
            vm.actions.onLock(lock)
            advanceUntilIdle()
        }
        assertEquals(listOf<PortValue>(BoolValue(true)), gridWrites, "GRID is written on the first value above 0 only")
        assertEquals(0.575f, knobPorts["grid_strength"]!!.asFloat(), 1e-4f, "the strength still follows every value")

        vm.actions.onLock(0f)
        advanceUntilIdle()
        assertEquals(listOf<PortValue>(BoolValue(true), BoolValue(false)), gridWrites)
    }

    @Test
    fun lockFollowsGridPortsSetElsewhere() = runTest(testDispatcher) {
        knobPorts["grid"] = BoolValue(true)
        knobPorts["grid_strength"] = FloatValue(0.575f)
        val vm = makeViewModel()
        observe(vm)
        assertEquals(0.5f, vm.stateFlow.value.lock, 1e-4f)
    }

    @Test
    fun stateStartsAtThePluginDefaults() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)
        assertEquals(CowbellUiState(), vm.stateFlow.value)
    }

    @Test
    fun badPortValuesStayInRange() = runTest(testDispatcher) {
        knobPorts["mode"] = IntValue(9)
        knobPorts["fill"] = IntValue(-4)
        knobPorts["body"] = IntValue(99)
        val vm = makeViewModel()
        observe(vm)
        val state = vm.stateFlow.value
        assertEquals(CowbellMode.SYNTH, state.mode)
        assertEquals(CowbellFill.MARBLE, state.fill)
        assertEquals(CowbellBody.SYNTH, state.body)
    }

    @Test
    fun liveArmedWhilePanelShown() = runTest(testDispatcher) {
        val vm = makeViewModel()

        vm.armLive()
        assertEquals(listOf("live" to BoolValue(true)), writes())
        assertTrue(hostHold.held.value, "a paused app must keep its audio host running for the bell")

        vm.disarmLive()
        assertEquals(listOf("live" to BoolValue(true), "live" to BoolValue(false)), writes())
        assertFalse(hostHold.held.value)
    }

    @Test
    fun liveStaysArmedUntilLastPanelLeaves() = runTest(testDispatcher) {
        val vm = makeViewModel()

        // BELL and BELL BEND shown together.
        vm.armLive()
        vm.armLive()
        vm.disarmLive()
        assertEquals(listOf("live" to BoolValue(true)), writes(), "one panel still shown, LIVE must stay armed")
        assertTrue(hostHold.held.value)

        vm.disarmLive()
        assertEquals(BoolValue(false), writes().last().second)
        assertEquals(2, writes().size)

        // A stray extra disarm neither writes nor goes negative.
        vm.disarmLive()
        assertEquals(2, writes().size)
        vm.armLive()
        assertEquals(BoolValue(true), writes().last().second, "a stray disarm left the count negative")
    }

    @Test
    fun savedSettingsComeBackAtLaunch() = runTest(testDispatcher) {
        prefs.saved = AppPreferences(lastCowbellJson = Json.encodeToString(
            CowbellUiState.serializer(),
            CowbellUiState(
                level = 0.4f, more = 0.3f, body = CowbellBody.SITAR, tone = 0.65f, ring = 0.2f, intensity = 0.9f,
                octave = -1, grid = false, gridStrength = 0.4f, mode = CowbellMode.SHAKE, fill = CowbellFill.BEADS,
            ),
        ))
        val vm = makeViewModel()
        observe(vm)

        assertEquals(IntValue(CowbellBody.SITAR.ordinal), knobPorts["body"], "the picked body must survive a relaunch")
        assertEquals(FloatValue(0.65f), knobPorts["tone"])
        assertEquals(FloatValue(0.2f), knobPorts["ring"])
        assertEquals(FloatValue(0.9f), knobPorts["intensity"])
        assertEquals(FloatValue(0.4f), knobPorts["level"])
        assertEquals(FloatValue(0.3f), knobPorts["more"])
        assertEquals(IntValue(-1), knobPorts["octave"])
        assertEquals(BoolValue(false), knobPorts["grid"])
        assertEquals(FloatValue(0.4f), knobPorts["grid_strength"])
        assertEquals(CowbellMode.SHAKE, vm.stateFlow.value.mode)
        assertEquals(CowbellFill.BEADS, vm.stateFlow.value.fill)
    }

    @Test
    fun anOldSaveWithMotionOnStillStartsOff() = runTest(testDispatcher) {
        prefs.saved = AppPreferences(lastCowbellJson = """{"level":0.4,"motion":true}""")
        val vm = makeViewModel()
        observe(vm)
        assertEquals(FloatValue(0.4f), knobPorts["level"])
        assertFalse(vm.stateFlow.value.motion)
    }

    @Test
    fun savedBodyAndToneComeBack() = runTest(testDispatcher) {
        prefs.saved = AppPreferences(lastCowbellJson = Json.encodeToString(
            CowbellUiState.serializer(),
            CowbellUiState(body = CowbellBody.METAL, tone = 0.6f),
        ))
        val vm = makeViewModel()
        observe(vm)

        assertEquals(IntValue(CowbellBody.METAL.ordinal), knobPorts["body"])
        assertEquals(FloatValue(0.6f), knobPorts["tone"])
        assertEquals(CowbellBody.METAL, vm.stateFlow.value.body)
        assertEquals(0.6f, vm.stateFlow.value.tone)
    }

    @Test
    fun oldSavedJsonRestoresToMetalAtTheDefaultTone() = runTest(testDispatcher) {
        prefs.saved = AppPreferences(lastCowbellJson = """{"metal":0.2,"engine":5,"ring":0.4}""")
        val vm = makeViewModel()
        observe(vm)

        assertEquals(IntValue(CowbellBody.METAL.ordinal), knobPorts["body"])
        assertEquals(FloatValue(0f), knobPorts["tone"])
        assertEquals(FloatValue(0.4f), knobPorts["ring"])
        assertEquals(CowbellBody.METAL, vm.stateFlow.value.body)
        assertEquals(0f, vm.stateFlow.value.tone)
        assertEquals(0.4f, vm.stateFlow.value.ring)
    }

    @Test
    fun aPickedBodyIsSavedForTheNextLaunch() = runTest(testDispatcher) {
        val vm = makeViewModel()
        observe(vm)
        assertFalse(vm.stateFlow.value.motion, "motion starts off: the bender bends")
        vm.actions.onBody(CowbellBody.SYNTH)
        vm.actions.onTone(0.62f)
        vm.actions.onMotion(true)
        advanceUntilIdle()

        val saved = Json.decodeFromString(CowbellUiState.serializer(), assertNotNull(prefs.saved.lastCowbellJson))
        assertEquals(CowbellBody.SYNTH, saved.body)
        assertEquals(0.62f, saved.tone)
        assertFalse(prefs.saved.lastCowbellJson!!.contains("motion"), "motion starts off every launch")
        // Gestures and the bend never persist: a relaunch must not wake holding a note.
        assertFalse(prefs.saved.lastCowbellJson!!.contains("holding"))
        assertFalse(prefs.saved.lastCowbellJson!!.contains("\"bend\""))
    }

    @Test
    fun orpheusSavesButLeavesTheRestoreToItsPresets() = runTest(testDispatcher) {
        prefs.saved = AppPreferences(lastCowbellJson = Json.encodeToString(CowbellUiState.serializer(), CowbellUiState(body = CowbellBody.SITAR)))
        val vm = makeViewModel(restoreStrategy = RestoreStrategy.PRESET)
        advanceUntilIdle()
        assertEquals(IntValue(CowbellBody.METAL.ordinal), knobPorts["body"])
        assertFalse(vm.stateRestored.value)
    }

    @Test
    fun stateRestoredTurnsTrueOnlyAfterSavedKnobsLand() = runTest(testDispatcher) {
        val untouched = makeViewModel()
        advanceUntilIdle()
        assertFalse(untouched.stateRestored.value)

        prefs.saved = AppPreferences(lastCowbellJson = Json.encodeToString(CowbellUiState.serializer(), CowbellUiState(body = CowbellBody.SITAR)))
        val vm = makeViewModel()
        advanceUntilIdle()
        assertEquals(IntValue(CowbellBody.SITAR.ordinal), knobPorts["body"])
        assertTrue(vm.stateRestored.value)
    }
}

private class TestIoDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val io = dispatcher
    override val default = dispatcher
    override val unconfined = dispatcher
}

private object UnconfinedDispatchers : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val unconfined = Dispatchers.Unconfined
}

private class MemoryPrefs(var saved: AppPreferences = AppPreferences()) : BaseAppPreferencesRepository() {
    override suspend fun load(): AppPreferences = saved
    override suspend fun save(preferences: AppPreferences) { saved = preferences }
}
