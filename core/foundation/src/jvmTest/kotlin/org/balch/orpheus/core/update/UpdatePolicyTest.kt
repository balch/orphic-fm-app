package org.balch.orpheus.core.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatePolicyTest {

    private fun decide(priority: Int, staleness: Int?, immediate: Boolean = true, flexible: Boolean = true) =
        UpdatePolicy.decide(priority, staleness, immediateAllowed = immediate, flexibleAllowed = flexible)

    @Test fun `priority 5 is immediate regardless of staleness`() {
        assertEquals(UpdateDecision.IMMEDIATE, decide(5, 0))
        assertEquals(UpdateDecision.IMMEDIATE, decide(5, 100))
    }

    @Test fun `priority 4 is flexible until 3 days stale`() {
        assertEquals(UpdateDecision.FLEXIBLE, decide(4, 0))
        assertEquals(UpdateDecision.FLEXIBLE, decide(4, 2))
        assertEquals(UpdateDecision.IMMEDIATE, decide(4, 3))
        assertEquals(UpdateDecision.IMMEDIATE, decide(4, 10))
    }

    @Test fun `priority 1 to 3 is always flexible`() {
        assertEquals(UpdateDecision.FLEXIBLE, decide(1, 0))
        assertEquals(UpdateDecision.FLEXIBLE, decide(2, 100))
        assertEquals(UpdateDecision.FLEXIBLE, decide(3, 0))
    }

    @Test fun `priority 0 is none until 14 days stale`() {
        assertEquals(UpdateDecision.NONE, decide(0, 0))
        assertEquals(UpdateDecision.NONE, decide(0, 13))
        assertEquals(UpdateDecision.FLEXIBLE, decide(0, 14))
        assertEquals(UpdateDecision.FLEXIBLE, decide(0, 30))
    }

    @Test fun `null staleness is treated as zero days`() {
        assertEquals(UpdateDecision.IMMEDIATE, decide(5, null))
        assertEquals(UpdateDecision.FLEXIBLE, decide(4, null))
        assertEquals(UpdateDecision.NONE, decide(0, null))
    }

    @Test fun `desired immediate downgrades to flexible when immediate not allowed`() {
        assertEquals(UpdateDecision.FLEXIBLE, decide(5, 0, immediate = false, flexible = true))
    }

    @Test fun `desired immediate is none when neither type allowed`() {
        assertEquals(UpdateDecision.NONE, decide(5, 0, immediate = false, flexible = false))
    }

    @Test fun `desired flexible never escalates to immediate`() {
        assertEquals(UpdateDecision.NONE, decide(2, 0, immediate = true, flexible = false))
        assertEquals(UpdateDecision.NONE, decide(0, 20, immediate = true, flexible = false))
    }
}
