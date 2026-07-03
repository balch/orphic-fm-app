package org.balch.orpheus.features.ai

import org.balch.orpheus.features.pulsar.VibeCreateEvent
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeCreateViewModelTest {

    // --- reduceVibeEvent (the dedicated vibe bus) ---

    @Test
    fun `Generating event enters GENERATING and clears any prior error`() {
        val state = reduceVibeEvent(
            VibeCreateEvent.Generating,
            VibeCreateUiState(phase = VibePhase.IDLE, error = "old"),
        )
        assertEquals(VibePhase.GENERATING, state.phase)
        assertEquals(null, state.error)
    }

    @Test
    fun `Generated event moves to RESULT and stores the vibe`() {
        val vibe = DogHouseVibe().vibe
        val state = reduceVibeEvent(VibeCreateEvent.Generated(vibe), VibeCreateUiState(phase = VibePhase.GENERATING))
        assertEquals(VibePhase.RESULT, state.phase)
        assertEquals(vibe, state.vibe)
        assertEquals(null, state.error)
    }

    @Test
    fun `Failed event sets the error and leaves GENERATING for IDLE`() {
        val state = reduceVibeEvent(VibeCreateEvent.Failed("bad json"), VibeCreateUiState(phase = VibePhase.GENERATING))
        assertEquals("bad json", state.error)
        assertEquals(VibePhase.IDLE, state.phase)
    }

    // --- reduceActivity (the shared agent activity feed) ---

    @Test
    fun `a vibe tool starting while idle begins a generating run (chat-initiated path)`() {
        val state = reduceActivity(AgentActivityEvent.ToolStarted("pulsar_get_vibe"), VibeCreateUiState(phase = VibePhase.IDLE))
        assertEquals(VibePhase.GENERATING, state.phase)
        assertEquals(1, state.feed.size)
    }

    @Test
    fun `a vibe tool starting while showing a result starts a fresh run and clears the old vibe`() {
        val prev = VibeCreateUiState(phase = VibePhase.RESULT, vibe = DogHouseVibe().vibe)
        val state = reduceActivity(AgentActivityEvent.ToolStarted("pulsar_apply_vibe"), prev)
        assertEquals(VibePhase.GENERATING, state.phase)
        assertEquals(null, state.vibe)
        assertEquals(1, state.feed.size)
    }

    @Test
    fun `a non-vibe tool while idle is ignored (shared agent, focused panel)`() {
        val state = reduceActivity(AgentActivityEvent.ToolStarted("synth_control"), VibeCreateUiState(phase = VibePhase.IDLE))
        assertEquals(VibePhase.IDLE, state.phase)
        assertTrue(state.feed.isEmpty())
    }

    @Test
    fun `activity appends to the feed while generating`() {
        val gen = reduceActivity(AgentActivityEvent.ToolCompleted("pulsar_get_vibe"), VibeCreateUiState(phase = VibePhase.GENERATING))
        assertEquals(1, gen.feed.size)
    }

    @Test
    fun `reasoning appends a thought line to the feed while generating`() {
        val gen = reduceActivity(AgentActivityEvent.Reasoning("mapping to E minor"), VibeCreateUiState(phase = VibePhase.GENERATING))
        assertEquals(1, gen.feed.size)
        assertTrue(gen.feed[0].startsWith("💭"))
        val idle = reduceActivity(AgentActivityEvent.Reasoning("mapping to E minor"), VibeCreateUiState(phase = VibePhase.IDLE))
        assertTrue(idle.feed.isEmpty())
    }

    @Test
    fun `an assistant message while generating is shown in the feed and stays GENERATING (no spurious cancel)`() {
        val state = reduceActivity(AgentActivityEvent.Assistant("customizing the bass"), VibeCreateUiState(phase = VibePhase.GENERATING))
        assertEquals(VibePhase.GENERATING, state.phase)
        assertEquals(1, state.feed.size)
        assertEquals("customizing the bass", state.feed[0])
    }

    @Test
    fun `an assistant message while showing a result is ignored`() {
        val prev = VibeCreateUiState(phase = VibePhase.RESULT, vibe = DogHouseVibe().vibe)
        val state = reduceActivity(AgentActivityEvent.Assistant("here is your vibe"), prev)
        assertEquals(VibePhase.RESULT, state.phase)
        assertEquals(prev.vibe, state.vibe)
    }

    @Test
    fun `instrument lines list all 8 tracks`() {
        assertEquals(8, instrumentLines(DogHouseVibe().vibe).size)
    }
}
