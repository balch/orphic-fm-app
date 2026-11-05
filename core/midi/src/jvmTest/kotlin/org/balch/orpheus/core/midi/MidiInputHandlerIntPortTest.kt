package org.balch.orpheus.core.midi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.ControlEvent
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BassSymbol
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import org.balch.orpheus.core.plugin.symbols.DjDrop
import org.balch.orpheus.core.plugin.symbols.DjSource
import org.balch.orpheus.core.plugin.symbols.DjSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.TidesSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.plugins.bass.BassPlugin
import org.balch.orpheus.plugins.beats.BeatsPlugin
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellPlugin
import org.balch.orpheus.plugins.dj.DjPlugin
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin
import org.balch.orpheus.plugins.duolfo.VoicePlugin
import org.balch.orpheus.plugins.flux.FluxPlugin
import org.balch.orpheus.plugins.grains.GrainsPlugin
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.tides.TidesPlugin
import org.balch.orpheus.plugins.warps.WarpsPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real plugins, so each port's own declaration and setter decide what a learned CC reaches. */
@OptIn(ExperimentalCoroutinesApi::class)
class MidiInputHandlerIntPortTest {
    private val audioEngine = object : AudioEngine {
        override fun start() {}
        override fun stop() {}
        override val isRunning = false
        override val sampleRate = 48000
        override fun getCpuLoad() = 0f
        override fun getCurrentTime() = 0.0
    }
    private val registry = PortRegistry(
        setOf(
            BassPlugin(audioEngine), BeatsPlugin(), CowbellPlugin(), DjPlugin(audioEngine),
            DrumPlugin(audioEngine), DuoLfoPlugin(), FluxPlugin(audioEngine), GrainsPlugin(),
            ResonatorPlugin(audioEngine), TidesPlugin(audioEngine), VoicePlugin(audioEngine), WarpsPlugin(),
        ),
    )
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, v -> registry.setPortValue(id.key, v) },
            getter = { registry.getPortValue(it.key) },
        )
    }
    private val stateHolder = MidiMappingStateHolder()
    private val handler = MidiInputHandler(controller, stateHolder, registry)

    private fun read(port: PortSymbol): Int = registry.getPortValue(port.controlId.key)!!.asInt()

    private fun setFromPanel(port: PortSymbol, value: Int) {
        registry.setPortValue(port.controlId.key, PortValue.IntValue(value))
    }

    private fun cc(port: PortSymbol, value: Int): Int {
        handler.onControlChange(CC, value)
        return read(port)
    }

    private fun learnCc(port: PortSymbol) =
        stateHolder.updateState { it.assignCCToControl(CC, port.controlId.key) }

    private fun pressMappedNote(port: PortSymbol): Int {
        handler.onNoteOn(NOTE, 100)
        handler.onNoteOff(NOTE)
        return read(port)
    }

    @Test
    fun aSweepReachesEveryStateOfEveryPortInOrder() {
        val stranded = sweepable.mapNotNull { (port, states) ->
            learnCc(port)
            val seen = (0..127).map { cc(port, it) }.distinct()
            if (seen == states.toList()) null else "${port.symbol}: wanted $states, saw $seen"
        }
        assertEquals(emptyList(), stranded)
    }

    @Test
    fun aMappedNoteVisitsEveryStateOfEveryPort() {
        val stranded = sweepable.mapNotNull { (port, states) ->
            stateHolder.updateState { it.assignNoteToControl(NOTE, port.controlId.key) }
            val seen = states.map { pressMappedNote(port) }.toSet()
            if (seen == states.toSet()) null else "${port.symbol}: wanted $states, saw $seen"
        }
        assertEquals(emptyList(), stranded)
    }

    @Test
    fun aJumpOnAManyStatePortLandsOnTheStateNotAToggle() {
        learnCc(CowbellSymbol.OCTAVE)
        repeat(3) {
            assertEquals(-2, cc(CowbellSymbol.OCTAVE, 0))
            assertEquals(2, cc(CowbellSymbol.OCTAVE, 127))
        }
    }

    @Test
    fun aMappedNoteCyclesOctaveAcrossZero() {
        stateHolder.updateState { it.assignNoteToControl(NOTE, CowbellSymbol.OCTAVE.controlId.key) }
        val seen = (0 until 6).map { pressMappedNote(CowbellSymbol.OCTAVE) }
        assertEquals(listOf(1, 2, -2, -1, 0, 1), seen)
    }

    @Test
    fun aMappedNoteCyclesDuoLfoModeToItsTopState() {
        stateHolder.updateState { it.assignNoteToControl(NOTE, DuoLfoSymbol.MODE.controlId.key) }
        val seen = (0 until 4).map { pressMappedNote(DuoLfoSymbol.MODE) }
        assertEquals(listOf(2, 0, 1, 2), seen)
    }

    @Test
    fun aTwoStatePortStillTogglesLikeAButton() {
        learnCc(TidesSymbol.RANGE)
        assertEquals(1, cc(TidesSymbol.RANGE, 127))
        assertEquals(1, cc(TidesSymbol.RANGE, 0))
        assertEquals(0, cc(TidesSymbol.RANGE, 127))
    }

    @Test
    fun aMappedNoteStillTogglesATwoStatePort() {
        stateHolder.updateState { it.assignNoteToControl(NOTE, TidesSymbol.RANGE.controlId.key) }
        val seen = (0 until 3).map { pressMappedNote(TidesSymbol.RANGE) }
        assertEquals(listOf(1, 0, 1), seen)
    }

    @Test
    fun aFirstPressCyclesOnFromTheShippedDefault() {
        stateHolder.updateState { it.assignNoteToControl(NOTE, CowbellSymbol.BODY.controlId.key) }
        assertEquals(2, read(CowbellSymbol.BODY))
        assertEquals(3, pressMappedNote(CowbellSymbol.BODY))
    }

    @Test
    fun aMappedNoteCyclesOnFromWhatThePanelSet() {
        stateHolder.updateState { it.assignNoteToControl(NOTE, CowbellSymbol.OCTAVE.controlId.key) }
        assertEquals(1, pressMappedNote(CowbellSymbol.OCTAVE))
        setFromPanel(CowbellSymbol.OCTAVE, -2)
        assertEquals(-1, pressMappedNote(CowbellSymbol.OCTAVE))
    }

    @Test
    fun aFirstPressTogglesATwoStatePortThePanelChanged() {
        stateHolder.updateState { it.assignNoteToControl(NOTE, BeatsSymbol.MODE.controlId.key) }
        setFromPanel(BeatsSymbol.MODE, 1)
        assertEquals(0, pressMappedNote(BeatsSymbol.MODE))
    }

    @Test
    fun aFirstCcJumpTogglesATwoStatePortThePanelChanged() {
        learnCc(TidesSymbol.RANGE)
        setFromPanel(TidesSymbol.RANGE, 1)
        assertEquals(0, cc(TidesSymbol.RANGE, 127))
    }

    @Test
    fun aFaderCrossesATwoStatePortAtItsMiddle() {
        learnCc(TidesSymbol.RANGE)
        assertEquals(listOf(0, 1, 0), listOf(63, 64, 63).map { cc(TidesSymbol.RANGE, it) })
    }

    @Test
    fun aPortThatDeclaresNoRangeIsNotSpreadAcrossADefaultRange() {
        // No min, max or labels: 0..100 is only the builder's default, not a list of states.
        learnCc(DrumSymbol.BD_TRIGGER_SRC)
        assertEquals(0, cc(DrumSymbol.BD_TRIGGER_SRC, 64))
    }

    @Test
    fun aSparseEnginePortNeverLandsOnAnIdThatIsNotAnEngine() {
        val port = VoiceSymbol.duoEngine(0)
        learnCc(port)
        val valid = OrpheusEngineId.entries.map { it.id }.toSet()
        val invalid = (0..127).map { cc(port, it) }.filterNot { it in valid }
        assertEquals(emptyList(), invalid)
    }

    @Test
    fun aFloatPortStillSweepsSmoothly() {
        learnCc(CowbellSymbol.TONE)
        handler.onControlChange(CC, 64)
        assertEquals(PortValue.FloatValue(64 / 127f), registry.getPortValue(CowbellSymbol.TONE.controlId.key))
    }

    @Test
    fun aSavedMappingReachesTheTopOfAnIntPort() {
        val key = CowbellSymbol.OCTAVE.controlId.key
        val saved = """{"ccMappings":{"$CC":"$key"}}"""
        stateHolder.updateState(Json.decodeFromString<MidiMappingState>(saved))
        assertEquals(2, cc(CowbellSymbol.OCTAVE, 127))
    }

    @Test
    fun aSavedKeyWithNoPortStillEmitsItsControlEvent() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<ControlEvent>()
        backgroundScope.launch { controller.onControlChange.toList(events) }
        val keys = listOf("no_such_control", "${CowbellPlugin.URI}:retired_port")
        for (key in keys) {
            stateHolder.updateState { it.assignCCToControl(CC, key) }
            handler.onControlChange(CC, 64)
        }
        // A key with a uri reports twice: once on the way to the setter, once when it finds no port.
        assertEquals(keys.map { it to 64 / 127f }, events.map { it.controlId to it.value }.distinct())
    }

    private companion object {
        const val CC = 21
        const val NOTE = 72

        val drumEngines = 0 until OrpheusEngineId.entries.count { !it.isPulsarOnly }
        val djSources = 0..DjSource.entries.lastIndex
        val djDrops = 0..DjDrop.entries.lastIndex

        val sweepable: List<Pair<PortSymbol, IntRange>> = listOf(
            CowbellSymbol.MODE to 0..2,
            CowbellSymbol.OCTAVE to -2..2,
            CowbellSymbol.FILL to 0..CowbellFill.entries.lastIndex,
            DuoLfoSymbol.MODE to 0..2,
            DuoLfoSymbol.SOURCE to 0..2,
            BassSymbol.ENGINE to 0..3,
            BassSymbol.ROOT_NOTE to 0..127,
            BassSymbol.SCALE to 0..5,
            BassSymbol.CLOCK_DIV to 0..4,
            BassSymbol.STEP_COUNT to 1..16,
            BassSymbol.TRIGGER_SOURCE to 0..3,
            BassSymbol.PITCH_SOURCE to 0..3,
            DjSymbol.SOURCE_A to djSources,
            DjSymbol.SOURCE_B to djSources,
            DjSymbol.DROP_A to djDrops,
            DjSymbol.DROP_B to djDrops,
            DrumSymbol.BD_ENGINE to drumEngines,
            DrumSymbol.SD_ENGINE to drumEngines,
            DrumSymbol.HH_ENGINE to drumEngines,
            FluxSymbol.LENGTH to 1..16,
            FluxSymbol.SCALE to 0..5,
            FluxSymbol.T_MODEL to 0..6,
            FluxSymbol.T_RANGE to 0..2,
            FluxSymbol.DEJAVU_MODE to 0..2,
            FluxSymbol.CONTROL_MODE to 0..2,
            FluxSymbol.VOLTAGE_RANGE to 0..2,
            GrainsSymbol.MODE to 0..3,
            ResonatorSymbol.MODE to 0..2,
            TidesSymbol.RAMP_MODE to 0..2,
            TidesSymbol.OUTPUT_MODE to 0..3,
            TidesSymbol.GATE_SOURCE to 0..4,
            WarpsSymbol.CARRIER_SOURCE to 0..9,
            WarpsSymbol.MODULATOR_SOURCE to 0..9,
        )
    }
}
