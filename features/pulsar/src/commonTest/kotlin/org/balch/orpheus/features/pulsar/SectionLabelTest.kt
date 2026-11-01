package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.audio.TransitionStyle
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

    @Test
    fun readoutShowsSectionBarAndSoloist() {
        assertEquals(
            SectionReadout("Ithaca 3/8 ▸ KEYS"),
            sectionReadout("Ithaca", state(solo = true, track = 4), null, -1, TransitionStyle.FADE),
        )
    }

    @Test
    fun finalSectionNamesThePendingTransition() {
        assertEquals(
            SectionReadout("Outro 3/8", " — FADE"),
            sectionReadout("Outro", state(section = 2), null, finalSectionIndex = 2, TransitionStyle.FADE),
        )
    }

    @Test
    fun firingTransitionReplacesTheSection() {
        assertEquals(
            SectionReadout("", "▸ FADE"),
            sectionReadout("Outro", state(), TransitionStyle.FADE, -1, null),
        )
    }

    @Test
    fun noSectionNoReadout() {
        assertNull(sectionReadout(null, state(), null, -1, null))
    }
}
