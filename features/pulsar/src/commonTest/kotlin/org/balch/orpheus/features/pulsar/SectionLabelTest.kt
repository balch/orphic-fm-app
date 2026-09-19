package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SectionLabelTest {
    private fun state(
        section: Int = 0,
        solo: Boolean = false,
        track: Int = -1,
        band: List<String> = emptyList(),
    ) = PulsarArrangementState(
        sectionIndex = section,
        barsElapsed = 2,
        barsTotal = 8,
        soloActive = solo,
        soloTrack = track,
        soloMode = 0,
        bandSolo = band.isNotEmpty(),
        bandMemberNames = band,
    )

    @Test
    fun noSoloHasNoSoloist() {
        assertNull(state().soloistName())
    }

    @Test
    fun trackSoloistUsesTheTrackName() {
        // Track 4 is KEYS in PULSAR_TRACK_NAMES.
        assertEquals("KEYS", state(solo = true, track = 4).soloistName())
    }

    @Test
    fun bandSoloistUsesTheMemberName() {
        assertEquals("Melodica", state(solo = true, track = 1, band = listOf("Kit", "Melodica")).soloistName())
    }
}
