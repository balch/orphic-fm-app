package org.balch.orpheus.features.pulsar.bench

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.dsp.BenchSummaryLayout
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.Section
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hands out queued summaries like the native ring. */
private class FakeTap : MixBenchTap {
    @Volatile var on = false
    var enables = 0
    val queue = ArrayDeque<FloatArray>()
    override fun setEnabled(on: Boolean) {
        this.on = on
        if (on) enables++
    }
    override fun drain(out: FloatArray): Int {
        var n = 0
        while (n < out.size / BenchSummaryLayout.FLOATS && queue.isNotEmpty()) {
            queue.removeFirst().copyInto(out, n * BenchSummaryLayout.FLOATS)
            n++
        }
        return n
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MixBenchControllerTest {
    private val ports = ConcurrentHashMap<String, PortValue>()
    /** Every audition gain write, as (port symbol, value). */
    private val gainWrites = mutableListOf<Pair<String, Float>>()
    private val synth = SynthController().apply {
        setDelegates(
            setter = { id, v ->
                ports[id.symbol] = v
                if (id.symbol.endsWith("_audition_gain")) gainWrites += id.symbol to (v as PortValue.FloatValue).value
                true
            },
            getter = { ports[it.symbol] },
        )
    }
    private val vibe = mkMinimalVibe("bench").copy(
        arrangement = Arrangement(sections = listOf(Section("a"), Section("b"), Section("c"), Section("d"))),
    )
    private val feature = FakePulsarFeature(listOf(vibe), vibe)
    private val tap = FakeTap()

    /** The track's audition gain; an unwritten port plays the engine default, 1. */
    private fun gain(t: Int) = (ports["track_${t}_audition_gain"] as PortValue.FloatValue?)?.value ?: 1f

    private fun summary(lufs: Double = -20.0) = FloatArray(BenchSummaryLayout.FLOATS).also { a ->
        a[BenchSummaryLayout.FRAMES] = 512f
        a[BenchSummaryLayout.channel(BenchSummaryLayout.OUT) + BenchSummaryLayout.K_ENERGY] =
            (10.0.pow((lufs + 0.691) / 10.0) * 512).toFloat()
    }

    private fun section(s: Int) {
        feature.arrangement.value = PulsarArrangementState(s, 0, 0, false, -1, 0)
    }

    private suspend fun TestScope.started(meterIdleMs: Long = 10_000): MixBenchController =
        MixBenchController(feature, synth, tap, backgroundScope, meterIdleMs = meterIdleMs).also {
            it.start()
            runCurrent()
        }

    @Test
    fun idleControllerWritesNothing() = runTest {
        section(1)
        started()
        advanceTimeBy(1_000)
        section(2); runCurrent()
        feature.applyVibe(vibe); runCurrent()
        assertTrue(gainWrites.isEmpty(), "audition gain writes: $gainWrites")
        assertFalse(tap.on)
        assertEquals(0, tap.enables)
        assertNull(feature.overlay)
        assertTrue(feature.macroWrites.isEmpty())
        assertTrue(feature.benchMacroWrites.isEmpty())
    }

    @Test
    fun readMetersEnablesThenIdlesOff() = runTest {
        val c = started(meterIdleMs = 500)
        c.readMeters()
        assertTrue(tap.on)
        c.setLevelDb(2, -6f)
        c.toggleMute(5)
        advanceTimeBy(300); runCurrent()
        // A read pushes the deadline: off 500 ms after the last read, not the first.
        c.readMeters()
        advanceTimeBy(450); runCurrent()
        assertTrue(tap.on)
        advanceTimeBy(100); runCurrent()
        assertFalse(tap.on)
        // The poll stopped with the tap.
        tap.queue += summary()
        advanceTimeBy(100); runCurrent()
        assertEquals(1, tap.queue.size)
        // Edits outlive metering.
        assertEquals(-6f, c.layers.value.vibe.levelDb[2])
        assertEquals(setOf(5), c.muted.value)
        assertEquals(0f, gain(5))
        // The next read starts it again.
        c.readMeters()
        runCurrent()
        assertTrue(tap.on)
        assertEquals(2, tap.enables)
        assertTrue(tap.queue.isEmpty())
    }

    @Test
    fun editOwnsOnlyThatTrack() = runTest {
        section(1)
        val c = started()
        c.setHeardLevelDb(2, -6f)
        assertEquals(setOf("track_2_audition_gain"), gainWrites.map { it.first }.toSet())
        section(2); runCurrent()
        assertEquals(setOf("track_2_audition_gain"), gainWrites.map { it.first }.toSet())
        c.setPan(4, 0.5f)
        assertEquals(setOf("track_2_audition_gain"), gainWrites.map { it.first }.toSet())
    }

    @Test
    fun soloOwnsAll() = runTest {
        val c = started()
        c.toggleSolo(3)
        assertEquals((0 until 8).map { "track_${it}_audition_gain" }.toSet(), gainWrites.map { it.first }.toSet())
        for (t in 0 until 8) assertEquals(if (t == 3) 1f else 0f, gain(t), "track $t")
        c.toggleSolo(3)
        for (t in 0 until 8) assertEquals(1f, gain(t), "track $t")
    }

    @Test
    fun resetReleasesOwnership() = runTest {
        section(1)
        val c = started()
        c.setLevelDb(2, -6f)
        c.setPan(3, 0.5f)
        c.toggleMute(5)
        c.resetToFile()
        assertEquals(1f, gain(2))
        assertEquals(BenchLayers(), c.layers.value)
        assertNull(feature.overlay)
        // Mutes stay, so the muted track stays owned; the released one is never written again.
        assertEquals(0f, gain(5))
        gainWrites.clear()
        section(2); runCurrent()
        assertEquals(setOf("track_5_audition_gain"), gainWrites.map { it.first }.toSet())
    }

    @Test
    fun vibeMacroMovesRealKnob() = runTest {
        val c = started()
        c.setMacro(Macro.ENERGY, 0.7f)
        assertEquals(listOf("energy" to 0.7f), feature.macroWrites)
        assertEquals(0.7f, feature.macroKnob(Macro.ENERGY))
        assertEquals(0.7f, feature.stateFlow.value.energy)
        assertEquals(0.7f, c.layers.value.vibe.macros[Macro.ENERGY])
    }

    @Test
    fun sectionMacroWritesThePortOnEntryAndTheKnobBackOnExit() = runTest {
        // Section 2 plays energy x 0.5, so a heard 0.2 needs a port value of 0.4 there.
        val halved = vibe.copy(
            arrangement = Arrangement(
                sections = vibe.arrangement!!.sections.mapIndexed { i, s ->
                    if (i == 2) s.copy(macroOverrides = MacroOverrides(energy = 0.5f)) else s
                },
            ),
        )
        feature.applyVibe(halved)
        feature.stateFlow.value = feature.stateFlow.value.copy(energy = 0.8f)
        section(2)
        val c = started()
        c.setScope(Scope.SECTION)
        c.setMacro(Macro.ENERGY, 0.2f)
        assertEquals(0.2f, c.layers.value.sections.getValue(2).macrosHeard[Macro.ENERGY])
        assertEquals(mapOf(Macro.ENERGY to 0.4f), feature.benchMacroWrites.last())
        assertEquals(0.4f, feature.engineMacro(Macro.ENERGY))
        section(1); runCurrent()
        // Leaving the section plays the knob again.
        assertEquals(emptyMap(), feature.benchMacroWrites.last())
        assertEquals(0.8f, feature.engineMacro(Macro.ENERGY))
        section(2); runCurrent()
        assertEquals(0.4f, feature.engineMacro(Macro.ENERGY))
        section(3); runCurrent()
        assertEquals(0.8f, feature.engineMacro(Macro.ENERGY))
        // The knob and the state the app saves never carried a section value.
        assertTrue(feature.macroWrites.isEmpty())
        assertEquals(0.8f, feature.stateFlow.value.energy)
        assertEquals(listOf(0.4f, null, 0.4f, null), feature.benchMacroWrites.map { it[Macro.ENERGY] })
    }

    @Test
    fun knobMoveWhileASectionHoldsTheMacroRewritesThePort() = runTest {
        section(2)
        val c = started()
        c.setScope(Scope.SECTION)
        c.setMacro(Macro.MOOD, 0.2f)
        val before = feature.benchMacroWrites.size
        // A hand on the knob writes its port; the section's value goes back over it.
        feature.actions.setMood(0.9f)
        runCurrent()
        assertEquals(before + 1, feature.benchMacroWrites.size)
        assertEquals(mapOf(Macro.MOOD to 0.2f), feature.benchMacroWrites.last())
        // A whole-vibe move is a real knob move, and the section keeps its heard value.
        c.setScope(Scope.VIBE)
        c.setMacro(Macro.MOOD, 0.6f)
        runCurrent()
        assertEquals(0.6f, feature.macroKnob(Macro.MOOD))
        assertEquals(mapOf(Macro.MOOD to 0.2f), feature.benchMacroWrites.last())
        section(1); runCurrent()
        assertEquals(0.6f, feature.engineMacro(Macro.MOOD))
    }

    @Test
    fun levelGoesToAuditionGain() = runTest {
        val c = started()
        c.setLevelDb(2, -6.0206f)
        assertEquals(0.5f, gain(2), 1e-4f)
        assertEquals(1f, gain(1))
    }

    @Test
    fun soloSilencesOthers() = runTest {
        val c = started()
        c.toggleSolo(0)
        assertEquals(1f, gain(0))
        for (t in 1 until 8) assertEquals(0f, gain(t), "track $t")
        assertEquals(setOf(0), c.soloed.value)
    }

    @Test
    fun sectionFlipKeepsOverlay() = runTest {
        section(2)
        val c = started()
        c.setScope(Scope.SECTION)
        c.setLevelDb(4, 3f)
        assertEquals(10f.pow(3f / 20f), gain(4), 1e-4f)
        section(0); runCurrent()
        assertEquals(1f, gain(4))
        section(2); runCurrent()
        assertEquals(1.4125f, gain(4), 1e-4f)
        section(3); runCurrent()
        assertEquals(1f, gain(4))
    }

    @Test
    fun sectionEditWithoutSectionsGoesToVibe() = runTest {
        val flat = mkMinimalVibe("flat")
        feature.applyVibe(flat)
        section(0)
        val c = started()
        c.setScope(Scope.SECTION)
        c.setLevelDb(1, -6.0206f)
        assertEquals(-6.0206f, c.layers.value.vibe.levelDb[1])
        assertTrue(c.layers.value.sections.isEmpty())
    }

    @Test
    fun sectionSendDividesOutVibeRatio() = runTest {
        section(1)
        val c = started()
        c.setReverb(3, 0.5f)
        c.setScope(Scope.SECTION)
        c.setReverb(3, 0.4f)
        assertEquals(0.8f, c.layers.value.sections.getValue(1).reverbSend[3]!!, 1e-5f)
        assertEquals(0.4f, feature.overlay!!.reverbSend(3, 1, 0.9f), 1e-5f)
    }

    @Test
    fun macroBaselineIsTheLiveKnob() = runTest {
        // The app restored 0.8 over the file's 0.3: an untouched section plays the 0.8.
        feature.applyVibe(vibe.copy(energy = 0.3f))
        feature.stateFlow.value = feature.stateFlow.value.copy(energy = 0.8f)
        section(2)
        val c = started()
        c.setScope(Scope.SECTION)
        c.setMacro(Macro.ENERGY, 0.2f)
        assertEquals(mapOf(Macro.ENERGY to 0.2f), feature.benchMacroWrites.last())
        section(1); runCurrent()
        assertEquals(0.8f, feature.engineMacro(Macro.ENERGY))
        assertTrue(feature.macroWrites.isEmpty())
        // Capture still writes file values: the section multiplier is over the file's 0.3.
        val row = capture(feature.vibeFlow.value, c.layers.value).single { it.field == CaptureField.MACRO }
        assertEquals(1f, row.old, 1e-6f)
        assertEquals(0.2f / 0.3f, row.new, 1e-5f)
    }

    @Test
    fun untouchedMacroCapturesNothingWhenTheKnobDiffersFromTheFile() = runTest {
        feature.stateFlow.value = feature.stateFlow.value.copy(mood = 0.9f)
        val c = started()
        c.setLevelDb(0, -3f)
        assertTrue(capture(vibe, c.layers.value).none { it.field == CaptureField.MACRO })
        assertTrue(feature.macroWrites.isEmpty())
    }

    @Test
    fun stopRestoresSectionMacrosAndKeepsVibeKnobMoves() = runTest {
        feature.stateFlow.value = feature.stateFlow.value.copy(space = 0.65f)
        section(1)
        val c = started()
        c.setMacro(Macro.MOOD, 0.3f)
        c.setScope(Scope.SECTION)
        c.setMacro(Macro.SPACE, 0.1f)
        assertEquals(0.1f, feature.engineMacro(Macro.SPACE))
        assertEquals(0.65f, feature.macroKnob(Macro.SPACE))
        c.stop()
        // The engine plays the knob again; a whole-vibe move is the app's knob now.
        assertEquals(0.65f, feature.engineMacro(Macro.SPACE))
        assertTrue(feature.benchMacros.isEmpty())
        assertEquals(0.3f, feature.macroKnob(Macro.MOOD))
        assertEquals(listOf("mood" to 0.3f), feature.macroWrites)
    }

    @Test
    fun sectionFlipBeforeTheLoadFollowerLeavesTheNewVibesMacros() = runTest {
        section(1)
        val c = started()
        c.setScope(Scope.SECTION)
        c.setMacro(Macro.ENERGY, 0.2f)
        section(2); runCurrent()
        c.setMacro(Macro.ENERGY, 0.3f)
        // The flip reaches the section follower before the load follower clears the old layers.
        section(1)
        feature.applyVibe(vibe)
        feature.stateFlow.value = feature.stateFlow.value.copy(energy = 0.9f)
        runCurrent()
        assertEquals(0.9f, feature.engineMacro(Macro.ENERGY))
        assertTrue(feature.benchMacros.isEmpty())
        assertEquals(BenchLayers(), c.layers.value)
    }

    @Test
    fun vibeLoadReleasesHeldMacrosAndRefusesMacroEditsUntilFollowed() = runTest {
        section(1)
        val c = started()
        c.setScope(Scope.SECTION)
        assertTrue(c.setMacro(Macro.SPACE, 0.1f))
        assertEquals(listOf(mapOf(Macro.SPACE to 0.1f)), feature.benchMacroWrites)
        // The follower has not seen this load, so the layers still belong to the old vibe.
        feature.applyVibe(vibe)
        assertNull(c.captureSnapshot())
        assertFalse(c.setMacro(Macro.SPACE, 0.2f))
        c.setScope(Scope.VIBE)
        assertFalse(c.setMacro(Macro.ENERGY, 0.9f))
        assertTrue(feature.macroWrites.none { it.first == "energy" }, "${feature.macroWrites}")
        assertEquals(listOf(mapOf(Macro.SPACE to 0.1f)), feature.benchMacroWrites)
        runCurrent()
        // The load follower tells the feature, so a held SPACE can't keep scaling the sends.
        assertEquals(listOf(mapOf(Macro.SPACE to 0.1f), emptyMap()), feature.benchMacroWrites)
        assertEquals(BenchLayers(), c.layers.value)
        assertEquals(1, c.captureSnapshot()?.loadCount)
        assertTrue(c.setMacro(Macro.ENERGY, 0.9f))
        assertEquals("energy" to 0.9f, feature.macroWrites.last())
    }

    @Test
    fun vibeChangeClearsLayers() = runTest {
        section(1)
        val c = started()
        c.setLevelDb(2, -6f)
        c.setPan(3, 0.5f)
        c.toggleMute(5)
        c.toggleSolo(6)
        c.setScope(Scope.SECTION)
        c.setLevelDb(4, 2f)
        feature.applyVibe(vibe)
        runCurrent()
        assertEquals(BenchLayers(), c.layers.value)
        assertTrue(c.muted.value.isEmpty())
        assertTrue(c.soloed.value.isEmpty())
        for (t in 0 until 8) assertEquals(1f, gain(t), "track $t")
    }

    @Test
    fun loopRequeues() = runTest {
        section(1)
        val c = started()
        c.setLoop(true)
        section(2); runCurrent()
        // The real feature writes index + 1, so arrangement_section_jump = 2.
        assertEquals(listOf(1), feature.jumps)
    }

    @Test
    fun pollFeedsMetersAndStopReleases() = runTest {
        val c = started()
        c.readMeters()
        assertTrue(tap.on)
        repeat(20) { tap.queue += summary() }
        advanceTimeBy(40)
        runCurrent()
        assertTrue(tap.queue.isEmpty())
        assertEquals(-20.0, c.readMeters().lufsShort, 0.2)
        assertEquals(-20.0, c.meters.value.lufsShort, 0.2)
        c.setLevelDb(0, -6f)
        c.stop()
        assertFalse(tap.on)
        assertEquals(1f, gain(0))
        assertEquals(null, feature.overlay)
    }

    @Test
    fun stopWaitsForAnInFlightEdit() {
        // Parks the edit's track 2 write, past any running check, until stop has had its chance.
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = SynthController().apply {
            setDelegates(
                setter = { id, v ->
                    if (id.symbol == "track_2_audition_gain" && v == PortValue.FloatValue(0.5f) && parked.count > 0) {
                        parked.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    ports[id.symbol] = v
                    true
                },
                getter = { ports[it.symbol] },
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = MixBenchController(feature, gated, tap, scope)
            runBlocking { c.start() }
            val editor = thread { runBlocking { c.setLevelDb(2, -6.0206f) } }
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            val stopper = thread { runBlocking { c.stop() } }
            // A racy stop finishes here and the parked 0.5 lands after its release.
            stopper.join(300)
            release.countDown()
            editor.join(5_000)
            stopper.join(5_000)
            assertFalse(stopper.isAlive)
            assertEquals(1f, gain(2))
            assertNull(feature.overlay)
            assertFalse(c.isRunning)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadBeforeStopStillClears() = runTest {
        val c = started()
        c.setLevelDb(2, -6f)
        feature.applyVibe(vibe)
        // No runCurrent: the load follower never sees this count before stop cancels it.
        c.stop()
        c.start()
        runCurrent()
        assertEquals(BenchLayers(), c.layers.value)
        for (t in 0 until 8) assertEquals(1f, gain(t), "track $t")
    }

    @Test
    fun stopDropsTheLoop() = runTest {
        section(1)
        val c = started()
        c.setLoop(true)
        c.stop()
        assertNull(c.loopSection.value)
        section(2)
        c.start()
        runCurrent()
        assertTrue(feature.jumps.isEmpty())
    }

    @Test
    fun vibeLoadReenablesTapWhileMetering() = runTest {
        val c = started()
        c.readMeters()
        // A rebuilt engine starts with the bench off; the feature re-applies the vibe on it.
        tap.on = false
        feature.applyVibe(vibe)
        runCurrent()
        assertTrue(tap.on)
        assertEquals(2, tap.enables)
    }

    @Test
    fun heardLevelInVibeScopeKeepsSectionOffset() = runTest {
        section(1)
        val c = started()
        c.setScope(Scope.SECTION)
        c.setLevelDb(2, -3f)
        c.setScope(Scope.VIBE)
        c.setHeardLevelDb(2, -9f)
        assertEquals(-6f, c.layers.value.vibe.levelDb[2], 1e-5f)
        assertEquals(-3f, c.layers.value.sections.getValue(1).levelDb[2], 1e-5f)
        assertEquals(10f.pow(-9f / 20f), gain(2), 1e-4f)
    }

    @Test
    fun heardLevelInSectionScopeKeepsVibeOffset() = runTest {
        section(1)
        val c = started()
        c.setLevelDb(2, -2f)
        c.setScope(Scope.SECTION)
        c.setHeardLevelDb(2, -5f)
        assertEquals(-2f, c.layers.value.vibe.levelDb[2], 1e-5f)
        assertEquals(-3f, c.layers.value.sections.getValue(1).levelDb[2], 1e-5f)
        assertEquals(10f.pow(-5f / 20f), gain(2), 1e-4f)
    }

    @Test
    fun heardSendInVibeScopeIsARatio() = runTest {
        feature.applyVibe(sendVibe)
        section(1)
        val c = started()
        assertTrue(c.setHeardSend(3, 0.25f, reverb = true))
        assertEquals(0.5f, c.layers.value.vibe.reverbRatio[3], 1e-5f)
        assertEquals(0.25f, c.layers.value.effective(sendVibe, 1, 3).reverbSend, 1e-5f)
    }

    @Test
    fun heardSendRefusesAZeroSendInVibeScope() = runTest {
        feature.applyVibe(sendVibe)
        section(1)
        val c = started()
        assertFalse(c.setHeardSend(0, 0.3f, reverb = false))
        assertEquals(BenchLayers(), c.layers.value)
    }

    @Test
    fun heardSectionSendMatchesOverlayAndCapture() = runTest {
        feature.applyVibe(sendVibe)
        section(1)
        val c = started()
        c.setHeardSend(3, 0.25f, reverb = true)
        c.setScope(Scope.SECTION)
        assertTrue(c.setHeardSend(3, 0.4f, reverb = true))
        assertEquals(0.4f, feature.overlay!!.reverbSend(3, 1, 0.5f), 1e-5f)
        val row = capture(sendVibe, c.layers.value)
            .single { it.scope == Scope.SECTION && it.field == CaptureField.REVERB_SEND }
        assertEquals(0.4f, row.new, 1e-5f)
    }

    private val sendVibe = vibe.copy(
        tracks = vibe.tracks.mapIndexed { t, tr ->
            if (t != 3) tr
            else tr.copy(engineEdm = tr.engineEdm.copy(reverbSend = 0.5f), engineSpace = tr.engineSpace.copy(reverbSend = 0.5f))
        },
    )
}
