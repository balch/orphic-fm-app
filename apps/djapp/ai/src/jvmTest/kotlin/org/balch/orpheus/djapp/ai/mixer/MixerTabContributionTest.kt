package org.balch.orpheus.djapp.ai.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.balch.orpheus.djapp.AiTab
import org.balch.orpheus.djapp.DjTab
import org.balch.orpheus.djapp.EndsTab
import org.balch.orpheus.djapp.HornTab
import org.balch.orpheus.djapp.MixTab
import org.balch.orpheus.djapp.MixerTab
import org.balch.orpheus.djapp.PlaylistRoute
import org.balch.orpheus.djapp.PulsarTab
import org.balch.orpheus.djapp.TimerTab
import org.balch.orpheus.djapp.VibeInfoTab
import org.balch.orpheus.djapp.ai.AiTabContribution
import org.balch.orpheus.djapp.djTabs
import org.balch.orpheus.djapp.variant.mergeTabContributions

class MixerTabContributionTest {
    @Test
    fun mixerTabAppends() {
        val tabs = mergeTabContributions(djTabs, listOf(AiTabContribution(), MixerTabContribution()))
        assertEquals(MixerTab, tabs.last())
        assertEquals(listOf(DjTab, MixTab, AiTab, TimerTab, MixerTab), tabs)
        assertFalse(HornTab in tabs)
    }

    @Test
    fun coreTabsUnchanged() {
        assertEquals(listOf(DjTab, MixTab, HornTab, TimerTab), djTabs)
        assertFalse(MixerTab in djTabs)
    }

    @Test
    fun mixerLabelIsUnique() {
        val others = djTabs + listOf(AiTab, VibeInfoTab, PlaylistRoute, PulsarTab, EndsTab)
        assertTrue(others.none { it.label == "Mixer" })
        assertTrue(MixerTab.opensAsSheet)
    }
}
