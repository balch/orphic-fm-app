package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.pulsar.VibeNavState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibeStepTileTest {
    private val nav = VibeNavState(currentName = "Rust Belt", previousName = "Dog House", nextName = "Stay Asleep")

    @Test fun aWideSlotWithANameShowsIt() = assertTrue(stepTileShowsName(StepTileNameMinWidth, "Dog House"))
    @Test fun aNarrowSlotIsIconOnly() = assertFalse(stepTileShowsName(StepTileNameMinWidth - 1.dp, "Dog House"))
    @Test fun aWideSlotWithNoNameIsIconOnly() = assertFalse(stepTileShowsName(StepTileNameMinWidth, null))
    @Test fun aWideSlotWithABlankNameIsIconOnly() = assertFalse(stepTileShowsName(StepTileNameMinWidth, ""))

    @Test fun previousNamesThePreviousVibe() = assertEquals("Dog House", previousTileName(nav))
    @Test fun previousNamesTheCurrentVibePastTheThreshold() =
        assertEquals("Rust Belt", previousTileName(nav.copy(previousRestarts = true)))
    @Test fun nextNamesTheNextVibe() = assertEquals("Stay Asleep", nextTileName(nav))

    @Test fun previousDescriptionNamesThePreviousVibe() =
        assertEquals("Previous vibe, Dog House", previousTileDescription(nav))
    @Test fun previousDescriptionSaysRestartPastTheThreshold() =
        assertEquals("Restart Rust Belt", previousTileDescription(nav.copy(previousRestarts = true)))
    @Test fun previousDescriptionSaysRestartWithNoName() =
        assertEquals("Restart", previousTileDescription(nav.copy(currentName = "", previousRestarts = true)))
    @Test fun previousDescriptionFallsBackWithNoNeighbour() =
        assertEquals("Previous vibe", previousTileDescription(nav.copy(previousName = null)))
    @Test fun nextDescriptionNamesTheNextVibe() = assertEquals("Next vibe, Stay Asleep", nextTileDescription(nav))
    @Test fun nextDescriptionFallsBackWithNoNeighbour() =
        assertEquals("Next vibe", nextTileDescription(nav.copy(nextName = null)))
}
