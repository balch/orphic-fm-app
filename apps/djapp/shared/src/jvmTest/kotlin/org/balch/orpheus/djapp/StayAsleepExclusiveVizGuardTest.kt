package org.balch.orpheus.djapp

import org.balch.orpheus.features.pulsar.vibes.StayAsleepVibe
import org.balch.orpheus.features.visualizations.viz.FaceMorphViz
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FaceMorphViz locks itself to a vibe by exact name match (Visualization.exclusiveToSong).
 * This guards the two names from drifting apart silently — a rename of either one should
 * fail loudly here instead of quietly breaking the lock.
 *
 * Lives here (not in features:visualizations or features:pulsar) because this app module is the
 * first place both the vibe and the viz are visible to the same test source set.
 */
class StayAsleepExclusiveVizGuardTest {

    @Test
    fun `Stay Asleep vibe name matches FaceMorphViz's exclusive song`() {
        assertEquals(FaceMorphViz.EXCLUSIVE_SONG, StayAsleepVibe().name)
    }
}
