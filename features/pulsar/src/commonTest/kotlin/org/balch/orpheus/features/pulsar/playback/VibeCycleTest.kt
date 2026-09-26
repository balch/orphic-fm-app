package org.balch.orpheus.features.pulsar.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VibeCycleTest {
    private val names = listOf("A", "B", "C")

    @Test fun nextFromTheMiddle() = assertEquals("C", neighborVibe(names, "B", 1))
    @Test fun previousFromTheMiddle() = assertEquals("A", neighborVibe(names, "B", -1))
    @Test fun nextWrapsFromTheLast() = assertEquals("A", neighborVibe(names, "C", 1))
    @Test fun previousWrapsFromTheFirst() = assertEquals("C", neighborVibe(names, "A", -1))
    @Test fun anUnknownVibeGoesToTheFirstForward() = assertEquals("A", neighborVibe(names, "AI Vibe", 1))
    @Test fun anUnknownVibeGoesToTheLastBackward() = assertEquals("C", neighborVibe(names, "AI Vibe", -1))
    @Test fun aSingleVibeIsItsOwnNeighbour() = assertEquals("A", neighborVibe(listOf("A"), "A", 1))
    @Test fun anEmptyListHasNoNeighbour() = assertNull(neighborVibe(emptyList(), "A", 1))
}
