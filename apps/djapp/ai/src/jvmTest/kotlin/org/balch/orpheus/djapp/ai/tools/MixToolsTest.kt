package org.balch.orpheus.djapp.ai.tools

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortType
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.djapp.ai.collab.CollabEvent
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.mcp.McpToolAdapter
import org.balch.orpheus.djapp.di.MixBenchExit
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.DelayFx
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.StemRecorder
import org.balch.orpheus.features.pulsar.bench.StemSink
import org.balch.orpheus.features.pulsar.bench.capture
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlugin(uri: String, override val ports: List<Port>) : DspPlugin {
    override val info = PluginInfo(uri = uri, name = uri, author = "test")
}

private fun port(i: Int, symbol: String, min: Float = 0f, max: Float = 1f, type: PortType = PortType.FLOAT) =
    ControlPort(index = i, symbol = symbol, name = symbol, type = type, default = min, min = min, max = max)

@OptIn(ExperimentalCoroutinesApi::class)
class MixToolsTest {
    private val ports = ConcurrentHashMap<String, PortValue>()
    private val synth = SynthController().apply {
        setDelegates(setter = { id, v -> ports[id.key] = v; true }, getter = { ports[it.key] })
    }
    private val budget = TurnBudget()
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
                    port(5, "track_3_audition_gain"),
                    port(6, "pulsar_delay_feedback"),
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

    /** Every tool over one controller and one collaboration layer, as the app wires them; the Mixer starts on screen. */
    private inner class Tools(c: () -> MixBenchController, scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
        val rig = CollabRig(pulsar, c, scope)
        val collab = rig.collab
        val read = MixReadTool({ pulsar }, c)
        val set = MixSetTool({ pulsar }, c, budget, rig.mixer, rig.events)
        val mute = MixMuteTool(c, budget, rig.mixer, rig.events)
        val solo = MixSoloTool(c, budget, rig.mixer, rig.events)
        val reset = MixResetTool(rig.mixer, rig.events, rig.guard, rig.collab)
        val jump = SectionJumpTool({ pulsar }, rig.mixer, rig.events)
        val loop = SectionLoopTool(rig.mixer, rig.events)
        val portSet = PortSetTool(registry, synth, rig.mixer, rig.events)
        val load = VibeLoadTool({ pulsar }, OneDispatcher(dispatcher))
    }

    private fun TestScope.tools(c: MixBenchController) = Tools({ c }, backgroundScope, StandardTestDispatcher(testScheduler))

    /** What the tools told the UI, collected from now on. */
    private fun TestScope.events(t: Tools): List<CollabEvent> {
        val seen = mutableListOf<CollabEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.rig.events.events.collect { seen += it } }
        return seen
    }

    @Test
    fun mixSetLevelRoutesToController() = runTest {
        pulsar.section(1)
        val c = controller()
        val r = tools(c).set.tool.execute(MixSetArgs(field = "level_db", value = -6.0206f, track = 2))
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
        val r = tools(c).set.tool.execute(MixSetArgs(field = "level_db", value = -3f, track = 5, scope = "section"))
        assertTrue(r.ok, r.message)
        assertEquals("SECTION", r.scope)
        assertEquals(-3f, c.layers.value.sections.getValue(1).levelDb[5], 1e-5f)
        assertEquals(10f.pow(-3f / 20f), gain(5), 1e-4f)
    }

    @Test
    fun mixSetSectionSendClampsAndSaysSo() = runTest {
        pulsar.section(1)
        val c = controller()
        val set = tools(c).set.tool
        val high = set.execute(MixSetArgs(field = "reverb", value = 1.4f, track = 2, scope = "section"))
        assertTrue(high.ok, high.message)
        assertTrue("clamped" in high.message, high.message)
        assertEquals(1f, c.layers.value.sections.getValue(1).reverbSend[2])
        assertEquals(1f, high.track?.reverb)
        val low = set.execute(MixSetArgs(field = "delay", value = -0.2f, track = 2))
        assertTrue(low.ok, low.message)
        assertTrue("clamped" in low.message, low.message)
        assertEquals(0f, c.layers.value.sections.getValue(1).delaySend[2])
        assertEquals("ok", set.execute(MixSetArgs(field = "delay", value = 0.3f, track = 2)).message)
    }

    @Test
    fun mixSetMacroMovesKnob() = runTest {
        val c = controller()
        val r = tools(c).set.tool.execute(MixSetArgs(field = "macro", value = 0.7f, macro = "Energy"))
        assertTrue(r.ok, r.message)
        assertTrue(r.captured)
        assertEquals("energy" to 0.7f, pulsar.macroWrites.last())
        assertEquals(0.7f, r.macroKnob)
        assertNull(r.macroHeard)
    }

    @Test
    fun editsDuringAnUnfollowedVibeLoadAskForAMixRead() = runTest {
        val c = controller()
        val t = tools(c)
        pulsar.applyVibe(pulsar.vibeFlow.value)
        val set = t.set.tool
        for (args in listOf(
            MixSetArgs(field = "level_db", value = -6f, track = 2),
            MixSetArgs(field = "pan", value = 0.5f, track = 2),
            MixSetArgs(field = "reverb", value = 0.3f, track = 2, scope = "section"),
            MixSetArgs(field = "macro", value = 0.7f, macro = "energy"),
        )) {
            val r = set.execute(args)
            assertFalse(r.ok, args.field)
            assertEquals(VIBE_LOADING, r.message)
        }
        val mute = t.mute.tool.execute(MixTrackArgs(1))
        assertFalse(mute.ok)
        assertEquals(VIBE_LOADING, mute.message)
        assertEquals(BenchLayers(), c.layers.value)
        runCurrent()
        assertTrue(set.execute(MixSetArgs(field = "level_db", value = -6f, track = 2)).ok)
    }

    @Test
    fun mixReadReportsTracksMacrosAndSection() = runTest {
        pulsar.section(2)
        val c = controller()
        c.toggleMute(1)
        val r = tools(c).read.tool.execute(MixReadArgs(meters = true))
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
    fun mixSetDelaySettingsLandInHistoryAndTheCapture() = runTest {
        pulsar.section(1)
        val c = controller()
        val t = tools(c)
        val vibeWide = t.set.tool.execute(MixSetArgs(field = "delay_time_a", value = 0.6f, scope = "vibe"))
        assertTrue(vibeWide.ok, vibeWide.message)
        assertEquals(0.6f, vibeWide.delayHeard)
        val section = t.set.tool.execute(MixSetArgs(field = "delay_feedback", value = 1f, scope = "section"))
        assertTrue(section.ok, section.message)
        assertTrue("clamped" in section.message, section.message)
        assertEquals(0.95f, section.delayHeard)

        val changes = t.rig.mixer.history.value.changes
        assertEquals(
            listOf(MixControl.DelaySetting(DelayFx.TIME_A), MixControl.DelaySetting(DelayFx.FEEDBACK)),
            changes.map { it.control },
        )
        assertEquals(setOf<LayerField>(LayerField.SectionDelayFx(1, DelayFx.FEEDBACK)), changes.last().writes.keys)
        val rows = capture(pulsar.vibeFlow.value, c.layers.value)
        assertEquals(
            listOf(Scope.VIBE to CaptureField.DELAY_TIME_A, Scope.SECTION to CaptureField.DELAY_FEEDBACK),
            rows.map { it.scope to it.field },
        )
        assertEquals(0.95f, rows.last().new)
        val read = t.read.tool.execute(MixReadArgs(meters = false))
        assertEquals(0.6f, read.delay["delay_time_a"])
        assertEquals(0.95f, read.delay["delay_feedback"])
    }

    @Test
    fun portSetRefusesDelaySettings() = runTest {
        val r = tools(controller()).portSet.tool.execute(PortSetArgs("$PULSAR_URI:pulsar_delay_feedback", 0.7f))
        assertFalse(r.ok)
        assertTrue("mix_set field=delay_feedback" in r.message, r.message)
        assertNull(ports["$PULSAR_URI:pulsar_delay_feedback"])
    }

    @Test
    fun portSetGoesThroughSynthControllerWithAiOrigin() = runTest {
        val key = "$PULSAR_URI:track_3_harmonics"
        val r = Tools({ error("an ungated port never reaches the mixer") }, backgroundScope).portSet.tool.execute(PortSetArgs(key, 0.4f))
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
        val tool = Tools({ error("an ungated port never reaches the mixer") }, backgroundScope).portSet.tool
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
        val tool = tools(controller()).portSet.tool
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
            listOf("track_3_audition_gain", "track_3_harmonics", "track_3_pan_glide", "track_3_volume").map { "$PULSAR_URI:$it" },
            r.ports.map { it.key },
        )
        assertEquals(0.8f, r.ports.last().value)
        val limited = tool.execute(PortsListArgs(filter = "TRACK_3", limit = 1))
        assertEquals(4, limited.matched)
        assertEquals(1, limited.ports.size)
        assertEquals(8, tool.execute(PortsListArgs()).matched)
    }

    @Test
    fun badArgsReturnErrors() = runTest {
        val c = controller()
        val t = tools(c)
        val set = t.set.tool
        val results = listOf(
            set.execute(MixSetArgs(field = "level_db", value = -3f, track = 9)).let { it.ok to it.message },
            set.execute(MixSetArgs(field = "volume", value = -3f, track = 1)).let { it.ok to it.message },
            set.execute(MixSetArgs(field = "macro", value = 0.5f, macro = "tempo")).let { it.ok to it.message },
            set.execute(MixSetArgs(field = "pan", value = 0f, track = 1, scope = "song")).let { it.ok to it.message },
            t.mute.tool.execute(MixTrackArgs(9)).let { it.ok to it.message },
            PortGetTool(registry, synth).tool.execute(PortGetArgs("$PULSAR_URI:nope")).let { it.ok to it.message },
            t.portSet.tool.execute(PortSetArgs("nope", 1f)).let { it.ok to it.message },
            t.load.tool.execute(VibeLoadArgs("Nope")).let { it.ok to it.message },
            t.jump.tool.execute(SectionJumpArgs(section = 7)).let { it.ok to it.message },
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
        val tool = tools(controller()).load.tool
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
        val r = tools(c).jump.tool.execute(SectionJumpArgs(name = "Chorus"))
        assertTrue(r.ok, r.message)
        assertEquals(MixSectionRef(2, "chorus"), r.section)
    }

    @Test
    fun everyToolBuildsItsDescriptor() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = MixBenchController(pulsar, synth, NoMeterTap, scope)
            val d = OneDispatcher(Dispatchers.Unconfined)
            val t = Tools({ c }, scope)
            val tools = listOf(
                t.read, t.set, t.mute, t.solo, t.reset, t.jump, t.loop,
                PortsListTool(registry, synth), PortGetTool(registry, synth), t.portSet,
                VibeListTool { pulsar }, t.load, TransportTool({ error("unused") }, d),
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
            val t = Tools({ c }, scope)
            val r = runBlocking {
                c.start()
                t.set.tool.execute(MixSetArgs(field = "macro", value = 0.1f, macro = "space", scope = "section"))
            }
            assertTrue(r.ok, r.message)
            assertEquals(0.65f, r.macroKnob)
            assertEquals(0.1f, r.macroHeard)
            // The engine plays the section's value; the knob, and so the saved state, keeps the whole-vibe one.
            assertEquals(mapOf(Macro.SPACE to 0.1f), pulsar.benchMacros)
            assertEquals(0.65f, pulsar.macroKnob(Macro.SPACE))
            assertTrue(pulsar.macroWrites.isEmpty())
            val read = runBlocking { t.read.tool.execute(MixReadArgs(meters = false)) }
            assertEquals(0.65f, read.macros["space"])
            assertEquals(mapOf("space" to 0.1f), read.sectionMacros)
            // What the desktop window close runs; it closes the recorder first.
            val exit = MixBenchExit.register(c)
            assertEquals(exit, MixBenchExit.current)
            val recorder = StemRecorder(c, { pulsar }, FakeStemTap(), { 48_000 }, OneDispatcher(Dispatchers.Default))
            exit.recorder = recorder
            assertTrue(MixBenchExit.runIfCreated())
            assertFalse(c.isRunning)
            val refused = runBlocking { recorder.record(0.5f, StemSink { _, _ -> error("a capture ran after exit") }) }
            assertFalse(refused.ok)
            assertTrue("closing" in refused.message, refused.message)
            assertTrue(pulsar.benchMacros.isEmpty())
            assertTrue(pulsar.macroWrites.isEmpty())
            // The second caller finds it done.
            assertTrue(exit.run())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mixEditBudgetRefusesThirteenth() = runTest {
        val c = controller()
        val t = tools(c)
        val set = t.set.tool
        val mute = t.mute.tool
        val solo = t.solo.tool
        repeat(4) { i ->
            assertTrue(set.execute(MixSetArgs(field = "level_db", value = -1f - i, track = i)).ok)
            assertTrue(mute.execute(MixTrackArgs(i)).ok)
            assertTrue(solo.execute(MixTrackArgs(i)).ok)
        }
        val layers = c.layers.value
        val refused = listOf(
            set.execute(MixSetArgs(field = "level_db", value = -9f, track = 6)).let { Triple(it.ok, it.message, it.budgetSpent) },
            mute.execute(MixTrackArgs(6)).let { Triple(it.ok, it.message, it.budgetSpent) },
            solo.execute(MixTrackArgs(6)).let { Triple(it.ok, it.message, it.budgetSpent) },
        )
        assertEquals(List(3) { Triple(false, TurnBudget.SPENT, true) }, refused)
        assertEquals(layers, c.layers.value)
        assertFalse(6 in c.muted.value || 6 in c.soloed.value)
        // A bad argument is refused before it spends anything.
        budget.reset()
        repeat(20) { mute.execute(MixTrackArgs(9)).let { assertFalse(it.ok); assertFalse(it.budgetSpent) } }
        assertTrue(mute.execute(MixTrackArgs(6)).ok)
    }

    @Test
    fun mcpCallsAreNotBudgeted() = runTest {
        val mute = tools(controller()).mute.tool
        repeat(12) { budget.tryMixEdit() }
        assertFalse(mute.execute(MixTrackArgs(1)).ok, "an agent call is over budget")
        // Over MCP there are no agent turns, so the budget would never reset: those calls skip it.
        repeat(20) {
            val r = McpToolAdapter.call(mute, buildJsonObject { put("track", JsonPrimitive(1)) })
            assertNotEquals(true, r.isError, r.content.toString())
        }
        assertFalse(mute.execute(MixTrackArgs(1)).ok, "MCP calls leave the agent's budget as it was")
    }

    @Test
    fun mixSetRefusesWhenMixerClosed() = runTest {
        val c = controller()
        val t = tools(c)
        val seen = events(t)
        t.collab.setMixerVisible(false)
        val r = t.set.tool.execute(MixSetArgs(field = "level_db", value = -6f, track = 2))
        assertFalse(r.ok)
        assertEquals("MIXER_CLOSED", r.refusal)
        assertEquals(CollabMixer.MIXER_CLOSED, r.message)
        assertEquals(BenchLayers(), c.layers.value)
        assertEquals(listOf<CollabEvent>(CollabEvent.OpenMixerRequested(CollabMixer.MIXER_CLOSED)), seen)
    }

    @Test
    fun everyMixChangingToolRefusesWhenMixerClosed() = runTest {
        val c = controller()
        val t = tools(c)
        t.collab.setMixerVisible(false)
        val refusals = listOf(
            t.mute.tool.execute(MixTrackArgs(1)).refusal,
            t.solo.tool.execute(MixTrackArgs(1)).refusal,
            t.reset.tool.execute(NoArgs()).refusal,
            t.jump.tool.execute(SectionJumpArgs(section = 2)).refusal,
            t.loop.tool.execute(SectionLoopArgs(on = true)).refusal,
            t.set.tool.execute(MixSetArgs(field = "macro", value = 0.9f, macro = "energy")).refusal,
        )
        assertEquals(List(6) { "MIXER_CLOSED" }, refusals)
        assertTrue(c.muted.value.isEmpty() && c.soloed.value.isEmpty())
        assertNull(c.loopSection.value)
        assertTrue(pulsar.macroWrites.isEmpty())
    }

    @Test
    fun mixSetRefusesPinnedStrip() = runTest {
        val c = controller()
        val t = tools(c)
        t.collab.togglePin(2)
        val pinned = t.set.tool.execute(MixSetArgs(field = "level_db", value = -6f, track = 2))
        assertFalse(pinned.ok)
        assertEquals("PINNED", pinned.refusal)
        assertEquals(CollabMixer.PINNED, pinned.message)
        assertEquals("PINNED", t.mute.tool.execute(MixTrackArgs(2)).refusal)
        assertEquals(0f, c.layers.value.vibe.levelDb[2])
        assertFalse(2 in c.muted.value)
        val other = t.set.tool.execute(MixSetArgs(field = "level_db", value = -6f, track = 3))
        assertTrue(other.ok, other.message)
        assertNull(other.refusal)
    }

    @Test
    fun mixSetSkipsUserMovedControl() = runTest {
        val c = controller()
        val t = tools(c)
        val seen = events(t)
        t.collab.beginTurn()
        t.rig.mixer.edit(EditOrigin.USER, MixControl.Level(2)) { setHeardLevelDb(2, -4f) }
        val r = t.set.tool.execute(MixSetArgs(field = "level_db", value = -9f, track = 2))
        assertFalse(r.ok)
        assertEquals("USER_MOVED", r.refusal)
        assertEquals(-4f, c.layers.value.vibe.levelDb[2])
        assertEquals(listOf<CollabEvent>(CollabEvent.AgentSkipped(MixControl.Level(2), "-4.0 dB")), seen)
        // Another control on the same strip is still the agent's to change.
        assertTrue(t.set.tool.execute(MixSetArgs(field = "pan", value = 0.4f, track = 2)).ok)
    }

    @Test
    fun sectionJumpSkipNamesTheUsersTarget() = runTest {
        pulsar.section(0)
        val c = controller()
        val t = tools(c)
        val seen = events(t)
        t.collab.beginTurn()
        // The user's jump lands at the next bar line, so "intro" is still playing.
        t.rig.mixer.edit(EditOrigin.USER, MixControl.Section, shown = "chorus") { jumpTo(2) }
        val r = t.jump.tool.execute(SectionJumpArgs(section = 1))
        assertEquals("USER_MOVED", r.refusal)
        assertEquals(listOf<CollabEvent>(CollabEvent.AgentSkipped(MixControl.Section, "chorus")), seen)
    }

    @Test
    fun mcpMixSetWorksWithMixerClosed() = runTest {
        val c = controller()
        val t = tools(c)
        val seen = events(t)
        t.collab.setMixerVisible(false)
        val r = withContext(TurnBudget.Exempt) { t.set.tool.execute(MixSetArgs(field = "level_db", value = -6f, track = 2)) }
        assertTrue(r.ok, r.message)
        assertNull(r.refusal)
        assertEquals(-6f, c.layers.value.vibe.levelDb[2])
        assertTrue(seen.isEmpty())
    }

    @Test
    fun mcpMixSetRespectsPins() = runTest {
        val c = controller()
        val t = tools(c)
        t.collab.togglePin(2)
        val r = withContext(TurnBudget.Exempt) { t.set.tool.execute(MixSetArgs(field = "level_db", value = -6f, track = 2)) }
        assertFalse(r.ok)
        assertEquals("PINNED", r.refusal)
        assertEquals(0f, c.layers.value.vibe.levelDb[2])
    }

    @Test
    fun resetSkipsPinnedStrips() = runTest {
        pulsar.section(1)
        val c = controller()
        val t = tools(c)
        val seen = events(t)
        val set = t.set.tool
        t.rig.mixer.edit(EditOrigin.USER, MixControl.Level(2)) { setHeardLevelDb(2, -6.0206f) }
        set.execute(MixSetArgs(field = "level_db", value = -6f, track = 1))
        set.execute(MixSetArgs(field = "pan", value = 0.5f, track = 2))
        set.execute(MixSetArgs(field = "reverb", value = 0.4f, track = 2, scope = "section"))
        t.collab.togglePin(2)
        // The user's only change is on the strip they pinned, which the reset keeps, so nothing needs confirming.
        val r = t.reset.tool.execute(NoArgs())
        assertTrue(r.ok, r.message)
        assertTrue(seen.isEmpty())
        val layers = c.layers.value
        assertEquals(0f, layers.vibe.levelDb[1])
        assertEquals(-6.0206f, layers.vibe.levelDb[2], 1e-4f)
        assertEquals(0.5f, layers.vibe.pan[2])
        assertEquals(0.4f, layers.sections.getValue(1).reverbSend[2])
        assertEquals(1f, gain(1), 1e-5f)
        assertEquals(0.5f, gain(2), 1e-4f)
    }

    @Test
    fun portSetGatesMixPortsOnly() = runTest {
        val c = controller()
        val t = tools(c)
        val tool = t.portSet.tool
        t.collab.setMixerVisible(false)
        val mixPorts = listOf("track_3_volume", "track_4_reverb_send_space", "energy").map { "$PULSAR_URI:$it" }
        for (key in mixPorts) {
            val r = tool.execute(PortSetArgs(key, 0.2f))
            assertFalse(r.ok, key)
            assertEquals("MIXER_CLOSED", r.refusal, key)
            assertNull(ports[key], key)
        }
        for (key in listOf("track_3_pan_glide", "track_3_audition_gain").map { "$PULSAR_URI:$it" }) {
            val r = tool.execute(PortSetArgs(key, 0.2f))
            assertEquals("MIXER_CLOSED", r.refusal, key)
            assertNull(ports[key], key)
        }
        for (key in listOf("track_3_harmonics").map { "$PULSAR_URI:$it" }) {
            val r = tool.execute(PortSetArgs(key, 0.2f))
            assertTrue(r.ok, key)
            assertNull(r.refusal)
        }
        assertTrue(tool.execute(PortSetArgs("org.balch.orpheus.plugins.reverb:mode", 1f)).ok)

        t.collab.setMixerVisible(true)
        t.collab.togglePin(3)
        for (port in listOf("track_3_volume", "track_3_pan_glide", "track_3_audition_gain")) {
            assertEquals("PINNED", tool.execute(PortSetArgs("$PULSAR_URI:$port", 0.2f)).refusal, port)
        }
        t.collab.togglePin(3)
        val written = tool.execute(PortSetArgs("$PULSAR_URI:track_3_volume", 0.2f))
        assertTrue(written.ok, written.message)
        assertEquals(PortValue.FloatValue(0.2f), ports["$PULSAR_URI:track_3_volume"])
    }
}
