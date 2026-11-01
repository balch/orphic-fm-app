package org.balch.orpheus.features.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentErrorLogTest {
    @Test
    fun keepsTheNewestEntriesUpToCapacity() {
        val log = AgentErrorLog()
        repeat(AgentErrorLog.CAPACITY + 5) { log.record(AgentErrorSource.TOOL, "fail $it", "mix_set") }
        val entries = log.entries.value
        assertEquals(AgentErrorLog.CAPACITY, entries.size)
        assertEquals("fail 5", entries.first().message)
        assertEquals("fail ${AgentErrorLog.CAPACITY + 4}", entries.last().message)
    }

    @Test
    fun clipsLongMessagesAndClears() {
        val log = AgentErrorLog()
        log.record(AgentErrorSource.AGENT, "x".repeat(AgentErrorLog.MAX_MESSAGE_CHARS * 2))
        assertEquals(AgentErrorLog.MAX_MESSAGE_CHARS, log.entries.value.single().message.length)
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }
}
