package org.balch.orpheus.djapp.ai.mcp

import kotlinx.coroutines.runBlocking
import org.balch.orpheus.features.ai.AgentErrorLog
import org.balch.orpheus.features.ai.AgentErrorSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentErrorsToolTest {
    @Test
    fun returnsTheNewestUpToLimitAndClearsOnRequest() = runBlocking {
        val log = AgentErrorLog()
        log.record(AgentErrorSource.AGENT, "HTTP 529 overloaded")
        log.record(AgentErrorSource.VIBE, "Section 2 pins hits on track 3", "pulsar_apply_vibe")
        val tool = AgentErrorsTool(log).tool

        val peek = tool.execute(AgentErrorsArgs(limit = 1))
        assertEquals(2, peek.total)
        assertEquals("pulsar_apply_vibe", peek.errors.single().tool)

        val drained = tool.execute(AgentErrorsArgs(clear = true))
        assertEquals(2, drained.errors.size)
        assertTrue(log.entries.value.isEmpty())
    }
}
