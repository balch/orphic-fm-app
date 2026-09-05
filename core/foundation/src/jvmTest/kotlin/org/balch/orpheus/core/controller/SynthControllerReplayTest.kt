package org.balch.orpheus.core.controller

import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue.IntValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A port write that happens before DspSynthEngine wires the delegates must reach the
 * engine once it does. `pluginPortSetter?.invoke` used to drop it silently, and a
 * startup-composition write (the SCORE_FREE_RUN mirror) never changes value again,
 * so nothing ever retried it — the flag simply never arrived on a real device.
 */
class SynthControllerReplayTest {

    private val freeRun = PluginControlId("org.balch.orpheus.plugins.pulsar", "score_free_run")
    private val bpm = PluginControlId("org.balch.orpheus.plugins.pulsar", "bpm")

    @Test
    fun `a write before the engine arrives replays onto it when delegates are set`() {
        val controller = SynthController()
        controller.controlFlow(freeRun).value = IntValue(1)

        val received = mutableListOf<Pair<PluginControlId, Int>>()
        controller.setDelegates(
            setter = { id, v -> received.add(id to v.asInt()); true },
            getter = { null },
        )

        assertEquals(listOf(freeRun to 1), received, "The pre-wiring write must land, exactly once")
    }

    @Test
    fun `only the latest pre-wiring write replays`() {
        val controller = SynthController()
        controller.controlFlow(freeRun).value = IntValue(0)
        controller.controlFlow(freeRun).value = IntValue(1)

        val received = mutableListOf<Int>()
        controller.setDelegates(
            setter = { _, v -> received.add(v.asInt()); true },
            getter = { null },
        )

        assertEquals(listOf(1), received, "A level port wants its final value, not its history")
    }

    @Test
    fun `a flow that was only read does not push its seed onto the engine`() {
        val controller = SynthController()
        // Read-only access: seeds the flow (0.5f fallback, no getter yet) but writes nothing.
        controller.controlFlow(bpm)

        val received = mutableListOf<PluginControlId>()
        controller.setDelegates(
            setter = { id, _ -> received.add(id); true },
            getter = { null },
        )

        assertTrue(received.isEmpty(), "Reading a port must never overwrite the engine's value with the seed")
    }

    @Test
    fun `a write after wiring is not replayed twice`() {
        val controller = SynthController()
        val received = mutableListOf<Int>()
        controller.setDelegates(
            setter = { _, v -> received.add(v.asInt()); true },
            getter = { null },
        )
        controller.controlFlow(freeRun).value = IntValue(1)

        assertEquals(listOf(1), received, "The live path already forwarded it; replay owes nothing")
    }
}
