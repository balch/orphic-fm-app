package org.balch.orpheus.features.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AgentActivityEventBusTest {

    @Test
    fun `emitted activity is observable in order`() = runTest {
        val bus = AgentActivityEventBus()
        val received = mutableListOf<AgentActivityEvent>()
        // replay = 0, so the collector must subscribe BEFORE emitting.
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent() // let the collector subscribe
        bus.emitToolStarted("pulsar_get_vibe")
        bus.emitAssistant("Created Akron Echoes")
        bus.emitError("something broke")
        runCurrent() // let the emissions deliver
        job.cancel()
        assertEquals(
            listOf(
                AgentActivityEvent.ToolStarted("pulsar_get_vibe"),
                AgentActivityEvent.Assistant("Created Akron Echoes"),
                AgentActivityEvent.Error("something broke"),
            ),
            received,
        )
    }
}
