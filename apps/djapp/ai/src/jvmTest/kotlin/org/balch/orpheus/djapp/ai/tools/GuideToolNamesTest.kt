package org.balch.orpheus.djapp.ai.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.SerialDescriptor
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.ai.tools.VibeGuideArgs
import org.balch.orpheus.features.ai.tools.VibeGuideTool
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService
import org.balch.orpheus.features.pulsar.bench.UnavailableStemCaptureService
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shared vibe guide teaches the AI edition's mix tools by name. Every tool name and meter
 * field it mentions must be real here, or the agent calls a tool that doesn't exist.
 */
class GuideToolNamesTest {

    private val guide = runBlocking {
        VibeGuideTool { FakePulsar(listOf(vibe("Night Drive"))) }.tool.execute(VibeGuideArgs()).guide
    }

    // Built as MixToolsTest does; descriptors never call the providers.
    private val toolNames: Set<String> = run {
        val synth = SynthController()
        val registry = PortRegistry(emptySet())
        val d = OneDispatcher(Dispatchers.Unconfined)
        // A cancelled scope: the mixer's knob follower never starts.
        val r = CollabRig(FakePulsar(listOf(vibe("Night Drive"))), { error("unused") }, CoroutineScope(Job().apply { cancel() }))
        listOf(
            MixReadTool({ error("unused") }, { error("unused") }), MixSetTool({ error("unused") }, { error("unused") }, TurnBudget(), r.mixer, r.events),
            MixMuteTool({ error("unused") }, TurnBudget(), r.mixer, r.events), MixSoloTool({ error("unused") }, TurnBudget(), r.mixer, r.events),
            MixResetTool(r.mixer, r.events, r.guard, r.collab),
            SectionJumpTool({ error("unused") }, r.mixer, r.events), SectionLoopTool(r.mixer, r.events),
            PortsListTool(registry, synth), PortGetTool(registry, synth), PortSetTool(registry, synth, r.mixer, r.events),
            VibeListTool { error("unused") }, VibeLoadTool({ error("unused") }, d, r.guard), TransportTool({ error("unused") }, d),
            AudioAnalyzeTool { error("unused") }, AudioCaptureTool(UnavailableStemCaptureService()),
            MixCaptureTool(UnavailableMixCaptureService(), r.mixer),
        ).map { it.tool.descriptor.name }.toSet()
    }

    private val SerialDescriptor.fields get() = (0 until elementsCount).map { getElementName(it) }

    @Test
    fun everyMixToolTheGuideNamesExists() {
        val named = Regex("""\b(?:mix|audio|section|ports?)_[a-z_]+\b|\btransport\b""")
            .findAll(guide).map { it.value }.toSet()
        assertTrue(
            named.containsAll(setOf("mix_read", "mix_set", "mix_capture", "audio_analyze", "transport", "section_jump")),
            "the guide no longer teaches the core mix tools: $named",
        )
        val unknown = named - toolNames
        assertTrue(unknown.isEmpty(), "the guide names tools the AI edition lacks: $unknown (tools: $toolNames)")
    }

    @Test
    fun theAppGuideNamesOnlyRealMixTools() {
        val appGuide = runBlocking { AppGuideTool().tool.execute(NoArgs()).guide }
        assertTrue(appGuide.contains("Reset to file") && appGuide.contains("Capture"), "the app guide lost its Mixer buttons")
        assertTrue(appGuide.contains("## History"), "the app guide lost its History section")
        val named = Regex("""\b(?:mix|audio|section|ports?)_[a-z_]+\b""").findAll(appGuide).map { it.value }.toSet()
        assertTrue(named.containsAll(setOf("mix_reset", "mix_capture")), "the app guide no longer names mix_reset and mix_capture: $named")
        val unknown = named - toolNames
        assertTrue(unknown.isEmpty(), "the app guide names tools the AI edition lacks: $unknown")
        // The numbers table maps the Mixer's readout to mix_read fields, which must stay real.
        val fields = MixMeters.serializer().descriptor.fields + MixChannelMeter.serializer().descriptor.fields
        listOf("lufsShort", "lufsIntegrated", "preClipPeakDb", "pastKneePct", "correlation", "relLu", "peakDb", "subPct", "lowPct").forEach {
            assertTrue(Regex("""\b$it\b""").containsMatchIn(appGuide), "precondition: the app guide reads $it")
            assertTrue(it in fields, "mix_read has no meter field $it")
        }
    }

    @Test
    fun everyMeterFieldTheGuideReadsExists() {
        val fields = MixMeters.serializer().descriptor.fields + MixChannelMeter.serializer().descriptor.fields +
            AudioAnalyzeResult.serializer().descriptor.fields
        // Mirrors VibeGuideMixingTest's tool-field list in features/ai.
        listOf("lufsShort", "lufsIntegrated", "pastKneePct", "relLu", "subPct", "lowPct", "trackLabels").forEach {
            assertTrue(guide.contains(it), "precondition: the guide reads $it")
            assertTrue(it in fields, "the mix tools have no result field $it")
        }
    }
}
