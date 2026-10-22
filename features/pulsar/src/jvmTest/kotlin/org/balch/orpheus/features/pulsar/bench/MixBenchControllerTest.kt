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
    private val synth = SynthController().apply {
        setDelegates(setter = { id, v -> ports[id.symbol] = v; true }, getter = { ports[it.symbol] })
    }
    private val vibe = mkMinimalVibe("bench").copy(
        arrangement = Arrangement(sections = listOf(Section("a"), Section("b"), Section("c"), Section("d"))),
    )
    private val feature = FakePulsarFeature(listOf(vibe), vibe)
    private val tap = FakeTap()

    private fun gain(t: Int) = (ports["track_${t}_audition_gain"] as PortValue.FloatValue).value

    private fun section(s: Int) {
        feature.arrangement.value = PulsarArrangementState(s, 0, 0, false, -1, 0)
    }

    private suspend fun TestScope.started(): MixBenchController =
        MixBenchController(feature, synth, tap, backgroundScope).also {
            it.start()
            runCurrent()
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
    fun macroGoesThroughActions() = runTest {
        val c = started()
        c.setMacro(Macro.ENERGY, 0.7f)
        assertEquals("energy" to 0.7f, feature.macroWrites.last())
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
        assertTrue(tap.on)
        val p = 10.0.pow((-20.0 + 0.691) / 10.0)
        repeat(20) {
            tap.queue += FloatArray(BenchSummaryLayout.FLOATS).also { a ->
                a[BenchSummaryLayout.FRAMES] = 512f
                a[BenchSummaryLayout.channel(BenchSummaryLayout.OUT) + BenchSummaryLayout.K_ENERGY] = (p * 512).toFloat()
            }
        }
        advanceTimeBy(40)
        runCurrent()
        assertTrue(tap.queue.isEmpty())
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
    fun vibeLoadReenablesTap() = runTest {
        started()
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
