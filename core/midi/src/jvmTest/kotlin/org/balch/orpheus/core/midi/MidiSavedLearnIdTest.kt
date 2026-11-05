package org.balch.orpheus.core.midi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.ControlEvent
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.symbols.BassSymbol
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DjSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.plugins.bass.BassPlugin
import org.balch.orpheus.plugins.beats.BeatsPlugin
import org.balch.orpheus.plugins.dj.DjPlugin
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.grains.GrainsPlugin
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.stereo.StereoPlugin
import org.balch.orpheus.plugins.warps.WarpsPlugin
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The strings here are what users' saved mappings hold, so they must never change. */
@OptIn(ExperimentalCoroutinesApi::class)
class MidiSavedLearnIdTest {
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
            BassPlugin(audioEngine), BeatsPlugin(), DjPlugin(audioEngine), DrumPlugin(audioEngine),
            GrainsPlugin(), ResonatorPlugin(audioEngine), StereoPlugin(audioEngine), WarpsPlugin(),
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

    private fun restore(json: String) =
        stateHolder.restoreSaved(Json.decodeFromString<MidiMappingState>(json))

    private fun restoreCc(savedId: String) = restore("""{"ccMappings":{"$CC":"$savedId"}}""")

    private fun read(port: PortSymbol): Float = registry.getPortValue(port.controlId.key)!!.asFloat()

    private fun cc(port: PortSymbol, value: Int): Float {
        handler.onControlChange(CC, value)
        return read(port)
    }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 0.001f, "$what: expected $expected, was $actual")

    /** Mid-range values, so the jump toggle stays out of it. */
    private fun assertKnobsReachPorts(knobs: List<Pair<String, PortSymbol>>) {
        for ((savedId, port) in knobs) {
            restoreCc(savedId)
            assertNear(40 / 127f, cc(port, 40), savedId)
            assertNear(90 / 127f, cc(port, 90), savedId)
        }
    }

    @Test
    fun savedBassKnobsReachTheirPorts() = assertKnobsReachPorts(
        listOf(
            "bass_jitter" to BassSymbol.JITTER,
            "bass_accent_amount" to BassSymbol.ACCENT_AMOUNT,
            "bass_lfo_mix" to BassSymbol.LFO_MIX,
            "bass_mutation" to BassSymbol.MUTATION,
            "bass_cutoff" to BassSymbol.CUTOFF,
            "bass_resonance" to BassSymbol.RESONANCE,
            "bass_envelope" to BassSymbol.ENVELOPE,
            "bass_overdrive" to BassSymbol.OVERDRIVE,
            "bass_compressor" to BassSymbol.COMPRESSOR,
            "bass_fx_send" to BassSymbol.FX_SEND,
            "bass_mix" to BassSymbol.MIX,
        ),
    )

    @Test
    fun savedBassSteppedKnobsReachTheirStates() {
        restoreCc("bass_clock_division")
        assertEquals(4f, cc(BassSymbol.CLOCK_DIV, 127))
        restoreCc("bass_step_count")
        assertEquals(16f, cc(BassSymbol.STEP_COUNT, 127))
        assertEquals(1f, cc(BassSymbol.STEP_COUNT, 0))
    }

    @Test
    fun savedResonatorControlsReachTheirPorts() {
        assertKnobsReachPorts(
            listOf(
                "resonator_structure" to ResonatorSymbol.STRUCTURE,
                "resonator_brightness" to ResonatorSymbol.BRIGHTNESS,
                "resonator_damping" to ResonatorSymbol.DAMPING,
                "resonator_position" to ResonatorSymbol.POSITION,
                "resonator_mix" to ResonatorSymbol.MIX,
                "resonator_target_mix" to ResonatorSymbol.TARGET_MIX,
            ),
        )
        restoreCc("resonator_mode")
        assertEquals(2f, cc(ResonatorSymbol.MODE, 127))
        restoreCc("resonator_snap_back")
        assertEquals(1f, cc(ResonatorSymbol.SNAP_BACK, 127))
    }

    @Test
    fun savedDrumKnobsReachTheirPorts() = assertKnobsReachPorts(
        listOf(
            "drums_bd_tune" to DrumSymbol.BD_FREQ,
            "drums_bd_tone" to DrumSymbol.BD_TONE,
            "drums_bd_decay" to DrumSymbol.BD_DECAY,
            "drums_bd_mod" to DrumSymbol.BD_P4,
            "drums_sd_tune" to DrumSymbol.SD_FREQ,
            "drums_sd_tone" to DrumSymbol.SD_TONE,
            "drums_sd_decay" to DrumSymbol.SD_DECAY,
            "drums_sd_mod" to DrumSymbol.SD_P4,
            "drums_hh_tune" to DrumSymbol.HH_FREQ,
            "drums_hh_tone" to DrumSymbol.HH_TONE,
            "drums_hh_decay" to DrumSymbol.HH_DECAY,
            "drums_hh_mod" to DrumSymbol.HH_P4,
            "drum_mix" to DrumSymbol.MIX,
        ),
    )

    @Test
    fun savedBeatsKnobsReachTheirPorts() = assertKnobsReachPorts(
        listOf(
            "beats_bd_density" to BeatsSymbol.DENSITY_0,
            "beats_sd_density" to BeatsSymbol.DENSITY_1,
            "beats_hh_density" to BeatsSymbol.DENSITY_2,
            "beats_randomness" to BeatsSymbol.RANDOMNESS,
            "beats_swing" to BeatsSymbol.SWING,
            "beats_mix" to DrumSymbol.MIX,
        ),
    )

    @Test
    fun savedBeatsModeTogglesLikeAButton() {
        restoreCc("beats_mode")
        assertEquals(1f, cc(BeatsSymbol.MODE, 127))
    }

    @Test
    fun savedEuclideanLengthsReachEveryLength() {
        val lengths = listOf(BeatsSymbol.EUCLIDEAN_0, BeatsSymbol.EUCLIDEAN_1, BeatsSymbol.EUCLIDEAN_2)
        lengths.forEachIndexed { index, port ->
            restoreCc("beats_euclid_len_$index")
            assertEquals(32f, cc(port, 127))
            assertEquals(17f, cc(port, 64))
            assertEquals(1f, cc(port, 0))
        }
    }

    @Test
    fun savedBeatsBpmSpansThePortsTempoRange() {
        restoreCc("beats_bpm")
        assertEquals(60f, cc(BeatsSymbol.BPM, 0))
        assertEquals(200f, cc(BeatsSymbol.BPM, 127))
    }

    @Test
    fun savedGrainsControlsReachTheirPorts() {
        assertKnobsReachPorts(
            listOf(
                "clouds_position" to GrainsSymbol.POSITION,
                "clouds_size" to GrainsSymbol.SIZE,
                "clouds_density" to GrainsSymbol.DENSITY,
                "clouds_texture" to GrainsSymbol.TEXTURE,
                "clouds_feedback" to GrainsSymbol.FEEDBACK,
                "clouds_reverb" to GrainsSymbol.REVERB,
                "clouds_mix" to GrainsSymbol.DRY_WET,
            ),
        )
        restoreCc("clouds_pitch")
        assertEquals(1f, cc(GrainsSymbol.PITCH, 127))
        restoreCc("clouds_mode")
        assertEquals(0f, cc(GrainsSymbol.MODE, 0))
        assertTrue(cc(GrainsSymbol.MODE, 127) > 1f, "clouds_mode reaches its top mode")
        restoreCc("clouds_freeze")
        assertEquals(1f, cc(GrainsSymbol.FREEZE, 127))
        restoreCc("clouds_trigger")
        assertEquals(1f, cc(GrainsSymbol.TRIGGER, 127))
    }

    @Test
    fun savedWarpsControlsReachTheirPorts() {
        assertKnobsReachPorts(
            listOf(
                "warps_carrier_level" to WarpsSymbol.LEVEL1,
                "warps_modulator_level" to WarpsSymbol.LEVEL2,
                "warps_timbre" to WarpsSymbol.TIMBRE,
                "warps_mix" to WarpsSymbol.MIX,
            ),
        )
        restoreCc("warps_algo")
        assertEquals(0f, cc(WarpsSymbol.ALGORITHM, 0))
        assertEquals(8f, cc(WarpsSymbol.ALGORITHM, 127))
    }

    @Test
    fun savedDjSendsReachTheirPorts() = assertKnobsReachPorts(
        listOf(
            "dj_delay_send" to DjSymbol.DELAY_SEND,
            "dj_reverb_send" to DjSymbol.REVERB_SEND,
        ),
    )

    @Test
    fun aSavedNoteReachesItsPort() {
        restore("""{"noteControlMappings":{"$NOTE":"resonator_mode"}}""")
        handler.onNoteOn(NOTE, 100)
        assertEquals(1f, read(ResonatorSymbol.MODE))
    }

    @Test
    fun relearningAControlReplacesItsSavedId() {
        restoreCc("bass_cutoff")
        stateHolder.updateState { it.assignCCToControl(CC + 1, BassSymbol.CUTOFF.controlId.key) }
        assertNull(stateHolder.getControlForCC(CC))
    }

    @Test
    fun aSavedIdWithNoPortStaysAsItIsAndStillEmits() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<ControlEvent>()
        backgroundScope.launch { controller.onControlChange.toList(events) }
        restoreCc("no_such_control")
        handler.onControlChange(CC, 64)
        assertEquals(listOf("no_such_control" to 64 / 127f), events.map { it.controlId to it.value })
    }

    @Test
    fun aSavedPortKeyIsLeftAlone() {
        val key = BassSymbol.CUTOFF.controlId.key
        restoreCc(key)
        assertEquals(key, stateHolder.getControlForCC(CC))
    }

    @Test
    fun aCcSpansAFloatPortsDeclaredRange() {
        val pan = StereoSymbol.MASTER_PAN
        stateHolder.updateState { it.assignCCToControl(CC, pan.controlId.key) }
        assertEquals(-1f, cc(pan, 0))
        assertEquals(1f, cc(pan, 127))
    }

    private companion object {
        const val CC = 21
        const val NOTE = 72
    }
}
