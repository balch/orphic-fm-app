package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlin.test.Test
import kotlin.test.assertTrue

class VibeCreateEventBusTest {

    @Test
    fun `emitGenerated is observable`() = runTest {
        val bus = VibeCreateEventBus()
        bus.emitGenerated(DogHouseVibe().vibe)
        val event = bus.events.first()
        assertTrue(event is VibeCreateEvent.Generated)
    }
}
