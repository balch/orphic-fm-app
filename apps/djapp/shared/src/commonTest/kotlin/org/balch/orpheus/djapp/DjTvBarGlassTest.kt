package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DjTvBarGlassTest {

    @Test
    fun barGlassShowsOnFullscreenDesktop() {
        assertTrue(
            shouldShowTvBarGlass(DjLayout.LargeScreen, isTelevisionHardware = false),
            "a fullscreen desktop window is the case this feature exists for",
        )
    }

    @Test
    fun barGlassStaysOffTelevisionHardware() {
        assertFalse(
            shouldShowTvBarGlass(DjLayout.LargeScreen, isTelevisionHardware = true),
            "the deferral comments in both bars cite a real-device TELEVISION trace; the " +
                "television keeps its stroke-only bars",
        )
    }

    @Test
    fun barGlassStaysOffSmallerLayouts() {
        // Phone and tablet never render these bars, but the gate must not depend on that: if
        // either bar is ever reused outside the TV chrome it has to stay unglazed there.
        assertFalse(shouldShowTvBarGlass(DjLayout.Portrait, isTelevisionHardware = false))
        assertFalse(shouldShowTvBarGlass(DjLayout.Landscape, isTelevisionHardware = false))
        assertFalse(shouldShowTvBarGlass(DjLayout.PortraitPair, isTelevisionHardware = false))
        assertFalse(shouldShowTvBarGlass(DjLayout.Tabletop(Hinge(1128, 1128), pair = true), isTelevisionHardware = false))
    }
}
