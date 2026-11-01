package org.balch.orpheus.djapp.ai.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.tools.CollabRig
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.MemoryChangeStore
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.MixBenchController

@OptIn(ExperimentalCoroutinesApi::class)
class MixClearChangesToolTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }

    @Test
    fun defaultsToThePlayingVibe() = runTest {
        val pulsar = FakePulsar(listOf(vibe("Night Drive"), vibe("Low Tide")))
        val controller = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also { it.start() }
        val store = MemoryChangeStore()
        val rig = CollabRig(pulsar, { controller }, backgroundScope, changeStore = store)
        runCurrent()
        rig.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        rig.collab.togglePin(1)
        assertTrue("Night Drive" in store.sets)

        val result = MixClearChangesTool({ rig.mixer }).tool.execute(MixClearChangesArgs())

        assertTrue(result.ok, result.message)
        assertTrue(rig.mixer.history.value.items.isEmpty())
        assertTrue("Night Drive" !in store.sets)
        assertEquals(0f, controller.layers.value.vibe.levelDb[1])
    }

    @Test
    fun namedOtherVibeIsClearedIgnoringCase() = runTest {
        val pulsar = FakePulsar(listOf(vibe("Night Drive"), vibe("Low Tide")))
        val controller = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also { it.start() }
        val store = MemoryChangeStore()
        val rig = CollabRig(pulsar, { controller }, backgroundScope, changeStore = store)
        runCurrent()
        rig.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        pulsar.applyVibeByName("Low Tide")
        runCurrent()

        val result = MixClearChangesTool({ rig.mixer }).tool.execute(MixClearChangesArgs("night drive"))

        assertTrue(result.ok, result.message)
        assertTrue("Night Drive" !in store.sets)
    }
}
