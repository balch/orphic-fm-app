package org.balch.orpheus.djapp.ai.tools

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortType
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.djapp.di.MixBenchExit
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun vibe(name: String) = Vibe(
    name = name,
    tracks = List(8) { TrackVoice(engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA), engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA)) },
    bpm = 120f,
    rootNote = RootNote.A,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(swingAmount = 0f, ghostProbability = 0f, noteRangeLow = 36, noteRangeHigh = 72, rhythmDensity = RhythmPattern.SPARSE.density),
    arrangement = Arrangement(sections = listOf(Section("intro"), Section("verse"), Section("chorus"))),
)

/** Just enough Pulsar for the controller and tools: knob writes move [stateFlow], as the real knobs do. */
private class FakePulsar(override val vibeList: List<Vibe>) : PulsarFeature {
    override val vibeFlow = MutableStateFlow(vibeList.first())
    override val vibeNames = vibeList.map { it.name }
    override val vibeLoadCount = MutableStateFlow(0)
    val arrangement = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
    override val arrangementStateFlow: StateFlow<PulsarArrangementState> = arrangement
    override val stateFlow = MutableStateFlow(PulsarUiState(vibe = vibeList.first()))
    val macroWrites = mutableListOf<Pair<String, Float>>()
    override val actions = PulsarPanelActions(
        setEnergy = { macroWrites += "energy" to it; stateFlow.value = stateFlow.value.copy(energy = it) },
        setComplexity = { macroWrites += "complexity" to it; stateFlow.value = stateFlow.value.copy(complexity = it) },
        setSpace = { macroWrites += "space" to it; stateFlow.value = stateFlow.value.copy(space = it) },
        setMood = { macroWrites += "mood" to it; stateFlow.value = stateFlow.value.copy(mood = it) },
    )

    override fun applyVibe(vibe: Vibe) {
        vibeFlow.value = vibe
        benchMacros = emptyMap()
        vibeLoadCount.value++
    }

    /** Engine-only section macros, as the controller last set them. */
    var benchMacros: Map<Macro, Float> = emptyMap()
        private set

    override fun setBenchMacros(values: Map<Macro, Float>) {
        benchMacros = values
    }

    override fun applyVibeByName(name: String): Boolean {
        applyVibe(vibeList.firstOrNull { it.name == name } ?: return false)
        return true
    }

    fun section(s: Int) {
        arrangement.value = PulsarArrangementState(s, 0, 0, false, -1, 0)
    }
}

private class FakePlugin(uri: String, override val ports: List<Port>) : DspPlugin {
    override val info = PluginInfo(uri = uri, name = uri, author = "test")
}

private fun port(i: Int, symbol: String, min: Float = 0f, max: Float = 1f, type: PortType = PortType.FLOAT) =
    ControlPort(index = i, symbol = symbol, name = symbol, type = type, default = min, min = min, max = max)

private class OneDispatcher(d: CoroutineDispatcher) : DispatcherProvider {
    override val main = d
    override val io = d
    override val default = d
    override val unconfined = d
}

@OptIn(ExperimentalCoroutinesApi::class)
class MixToolsTest {
    private val ports = ConcurrentHashMap<String, PortValue>()
    private val synth = SynthController().apply {
        setDelegates(setter = { id, v -> ports[id.key] = v; true }, getter = { ports[it.key] })
    }
    private val pulsar = FakePulsar(listOf(vibe("Night Drive"), vibe("Low Tide")))
    private val registry = PortRegistry(
        setOf(
            FakePlugin(
                PULSAR_URI,
                listOf(
                    port(0, "energy"),
                    port(1, "track_3_volume"),
                    port(2, "track_3_harmonics"),
                    port(3, "track_4_reverb_send_space"),
                    port(4, "track_3_pan_glide"),
                ),
            ),
            FakePlugin("org.balch.orpheus.plugins.reverb", listOf(port(0, "mode", 0f, 3f, PortType.INT))),
        ),
    )

    private fun gain(t: Int) = (ports["$PULSAR_URI:track_${t}_audition_gain"] as PortValue.FloatValue?)?.value ?: 1f

    private suspend fun TestScope.controller() = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also {
        it.start()
        runCurrent()
    }

    @Test
    fun mixSetLevelRoutesToController() = runTest {
        pulsar.section(1)
        val c = controller()
        val r = MixSetTool({ pulsar }, { c }).tool.execute(MixSetArgs(field = "level_db", value = -6.0206f, track = 2))
        assertTrue(r.ok, r.message)
        assertTrue(r.captured)
        assertEquals("VIBE", r.scope)
        val track = assertNotNull(r.track)
        assertEquals(-6.021f, track.levelDb, 1e-3f)
        assertTrue(track.owned)
        assertEquals(-6.0206f, c.layers.value.vibe.levelDb[2], 1e-4f)
        assertEquals(0.5f, gain(2), 1e-4f)
    }

    @Test
    fun mixSetSectionScopeEditsThePlayingSection() = runTest {
        pulsar.section(1)
        val c = controller()
        val r = MixSetTool({ pulsar }, { c }).tool.execute(MixSetArgs(field = "level_db", value = -3f, track = 5, scope = "section"))
        assertTrue(r.ok, r.message)
        assertEquals("SECTION", r.scope)
        assertEquals(-3f, c.layers.value.sections.getValue(1).levelDb[5], 1e-5f)
        assertEquals(10f.pow(-3f / 20f), gain(5), 1e-4f)
    }

    @Test
    fun mixSetMacroMovesKnob() = runTest {
        val c = controller()
        val r = MixSetTool({ pulsar }, { c }).tool.execute(MixSetArgs(field = "macro", value = 0.7f, macro = "Energy"))
        assertTrue(r.ok, r.message)
        assertTrue(r.captured)
        assertEquals("energy" to 0.7f, pulsar.macroWrites.last())
        assertEquals(0.7f, r.macroKnob)
        assertNull(r.macroHeard)
    }

    @Test
    fun mixReadReportsTracksMacrosAndSection() = runTest {
        pulsar.section(2)
        val c = controller()
        c.toggleMute(1)
        val r = MixReadTool({ pulsar }, { c }).tool.execute(MixReadArgs(meters = true))
        assertTrue(r.ok, r.message)
        assertEquals("Night Drive", r.vibe)
        assertEquals(MixSectionRef(2, "chorus"), r.section)
        assertEquals(listOf("intro", "verse", "chorus"), r.sections)
        assertEquals(8, r.tracks.size)
        assertEquals("PERC", r.tracks[1].name)
        assertTrue(r.tracks[1].muted)
        assertTrue(r.tracks[1].owned)
        assertFalse(r.tracks[0].owned)
        assertEquals(setOf("energy", "complexity", "mood", "space"), r.macros.keys)
        assertNotNull(r.meters)
    }

    @Test
    fun portSetGoesThroughSynthControllerWithAiOrigin() = runTest {
        val key = "$PULSAR_URI:track_3_harmonics"
        val r = PortSetTool(registry, synth).tool.execute(PortSetArgs(key, 0.4f))
        assertTrue(r.ok, r.message)
        assertFalse(r.captured)
        assertNull(r.hint)
        assertEquals(PortValue.FloatValue(0.4f), ports[key])
        val event = synth.onControlChange.first()
        assertEquals(key, event.controlId)
        assertEquals(ControlEventOrigin.AI, event.origin)
    }

    @Test
    fun portSetClampsToRange() = runTest {
        val tool = PortSetTool(registry, synth).tool
        val high = tool.execute(PortSetArgs("$PULSAR_URI:track_3_harmonics", 5f))
        assertTrue(high.ok)
        assertTrue(high.clamped)
        assertEquals(1f, high.value)
        assertEquals(PortValue.FloatValue(1f), ports["$PULSAR_URI:track_3_harmonics"])
        val int = tool.execute(PortSetArgs("org.balch.orpheus.plugins.reverb:mode", 1.6f))
        assertFalse(int.clamped)
        assertEquals(PortValue.IntValue(2), ports["org.balch.orpheus.plugins.reverb:mode"])
    }

    @Test
    fun portSetOnTrackVolumeHintsMixSet() = runTest {
        val tool = PortSetTool(registry, synth).tool
        val volume = tool.execute(PortSetArgs("$PULSAR_URI:track_3_volume", 0.5f))
        assertTrue(volume.ok)
        assertFalse(volume.captured)
        assertEquals("use mix_set field=level_db track=3 to keep this", volume.hint)
        assertEquals("use mix_set field=reverb track=4 to keep this", tool.execute(PortSetArgs("$PULSAR_URI:track_4_reverb_send_space", 0.2f)).hint)
        assertEquals("use mix_set field=macro macro=energy to keep this", tool.execute(PortSetArgs("$PULSAR_URI:energy", 0.2f)).hint)
        assertNull(tool.execute(PortSetArgs("$PULSAR_URI:track_3_pan_glide", 0.2f)).hint)
    }

    @Test
    fun portsListFilters() = runTest {
        ports["$PULSAR_URI:track_3_volume"] = PortValue.FloatValue(0.8f)
        val tool = PortsListTool(registry, synth).tool
        val r = tool.execute(PortsListArgs(filter = "*pulsar:track_3_*"))
        assertTrue(r.ok)
        assertEquals(
            listOf("track_3_harmonics", "track_3_pan_glide", "track_3_volume").map { "$PULSAR_URI:$it" },
            r.ports.map { it.key },
        )
        assertEquals(0.8f, r.ports.last().value)
        val limited = tool.execute(PortsListArgs(filter = "TRACK_3", limit = 1))
        assertEquals(3, limited.matched)
        assertEquals(1, limited.ports.size)
        assertEquals(6, tool.execute(PortsListArgs()).matched)
    }

    @Test
    fun badArgsReturnErrors() = runTest {
        val c = controller()
        val set = MixSetTool({ pulsar }, { c }).tool
        val results = listOf(
            set.execute(MixSetArgs(field = "level_db", value = -3f, track = 9)).let { it.ok to it.message },
            set.execute(MixSetArgs(field = "volume", value = -3f, track = 1)).let { it.ok to it.message },
            set.execute(MixSetArgs(field = "macro", value = 0.5f, macro = "tempo")).let { it.ok to it.message },
            set.execute(MixSetArgs(field = "pan", value = 0f, track = 1, scope = "song")).let { it.ok to it.message },
            MixMuteTool { c }.tool.execute(MixTrackArgs(9)).let { it.ok to it.message },
            PortGetTool(registry, synth).tool.execute(PortGetArgs("$PULSAR_URI:nope")).let { it.ok to it.message },
            PortSetTool(registry, synth).tool.execute(PortSetArgs("nope", 1f)).let { it.ok to it.message },
            VibeLoadTool({ pulsar }, OneDispatcher(Dispatchers.Unconfined)).tool.execute(VibeLoadArgs("Nope")).let { it.ok to it.message },
            SectionJumpTool({ pulsar }, { c }).tool.execute(SectionJumpArgs(section = 7)).let { it.ok to it.message },
            TransportTool({ error("unused") }, OneDispatcher(Dispatchers.Unconfined)).tool.execute(TransportArgs("rewind")).let { it.ok to it.message },
        )
        for ((ok, message) in results) {
            assertFalse(ok, message)
            assertTrue(message.isNotBlank())
        }
        assertEquals(BenchLayers(), c.layers.value)
    }

    @Test
    fun vibeLoadUnknownFails() = runTest {
        val tool = VibeLoadTool({ pulsar }, OneDispatcher(StandardTestDispatcher(testScheduler))).tool
        val bad = tool.execute(VibeLoadArgs("Nope"))
        assertFalse(bad.ok)
        assertTrue(bad.message.contains("vibe_list"))
        assertEquals(0, pulsar.vibeLoadCount.value)
        val good = tool.execute(VibeLoadArgs("low tide"))
        assertTrue(good.ok, good.message)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
    }

    @Test
    fun sectionJumpByName() = runTest {
        val c = controller()
        val r = SectionJumpTool({ pulsar }, { c }).tool.execute(SectionJumpArgs(name = "Chorus"))
        assertTrue(r.ok, r.message)
        assertEquals(MixSectionRef(2, "chorus"), r.section)
    }

    @Test
    fun everyToolBuildsItsDescriptor() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = MixBenchController(pulsar, synth, NoMeterTap, scope)
            val d = OneDispatcher(Dispatchers.Unconfined)
            val tools = listOf(
                MixReadTool({ pulsar }, { c }), MixSetTool({ pulsar }, { c }), MixMuteTool { c }, MixSoloTool { c },
                MixResetTool { c }, SectionJumpTool({ pulsar }, { c }), SectionLoopTool { c },
                PortsListTool(registry, synth), PortGetTool(registry, synth), PortSetTool(registry, synth),
                VibeListTool { pulsar }, VibeLoadTool({ pulsar }, d), TransportTool({ error("unused") }, d),
            ).map { it.tool }
            assertEquals(
                listOf(
                    "mix_read", "mix_set", "mix_mute", "mix_solo", "mix_reset", "section_jump", "section_loop",
                    "ports_list", "port_get", "port_set", "vibe_list", "vibe_load", "transport",
                ),
                tools.map { it.descriptor.name },
            )
            assertEquals(listOf("field", "value"), tools[1].descriptor.requiredParameters.map { it.name })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sectionMacroReachesOnlyTheEngineAndWindowCloseStopsTheController() {
        pulsar.stateFlow.value = pulsar.stateFlow.value.copy(space = 0.65f)
        pulsar.section(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = MixBenchController(pulsar, synth, NoMeterTap, scope)
            val r = runBlocking {
                c.start()
                MixSetTool({ pulsar }, { c }).tool.execute(MixSetArgs(field = "macro", value = 0.1f, macro = "space", scope = "section"))
            }
            assertTrue(r.ok, r.message)
            assertEquals(0.65f, r.macroKnob)
            assertEquals(0.1f, r.macroHeard)
            // The engine plays the section's value; the knob, and so the saved state, keeps the whole-vibe one.
            assertEquals(mapOf(Macro.SPACE to 0.1f), pulsar.benchMacros)
            assertEquals(0.65f, pulsar.macroKnob(Macro.SPACE))
            assertTrue(pulsar.macroWrites.isEmpty())
            val read = runBlocking { MixReadTool({ pulsar }, { c }).tool.execute(MixReadArgs(meters = false)) }
            assertEquals(0.65f, read.macros["space"])
            assertEquals(mapOf("space" to 0.1f), read.sectionMacros)
            // What the desktop window close runs.
            val exit = MixBenchExit.register(c)
            assertEquals(exit, MixBenchExit.current)
            assertTrue(MixBenchExit.runIfCreated())
            assertFalse(c.isRunning)
            assertTrue(pulsar.benchMacros.isEmpty())
            assertTrue(pulsar.macroWrites.isEmpty())
            // The second caller finds it done.
            assertTrue(exit.run())
        } finally {
            scope.cancel()
        }
    }
}
