package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.features.pulsar.playback.VibeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSessionTest {
    private fun session(dispatcher: TestDispatcher) =
        PulsarSession(SongEndingStubSynthEngine(), makeAppCoroutineScope(dispatcher), FixturesDispatchers(dispatcher))

    @Test
    fun reApplyingTheSameVibeStillStartsANewSong() = runTest {
        val s = session(UnconfinedTestDispatcher(testScheduler))
        val vibe = mkMinimalVibe("A")
        s.updateVibe(vibe)
        val first = s.songGenerationFlow.value
        s.updateVibe(vibe)
        assertEquals(first + 1, s.songGenerationFlow.value)
    }

    @Test
    fun requestsReachACollector() = runTest {
        val s = session(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vibeRequests.collect { seen += it } }
        s.requestVibe(VibeRequest.Next)
        s.requestVibe(VibeRequest.Next)
        assertEquals(listOf<VibeRequest>(VibeRequest.Next, VibeRequest.Next), seen)
    }
}
