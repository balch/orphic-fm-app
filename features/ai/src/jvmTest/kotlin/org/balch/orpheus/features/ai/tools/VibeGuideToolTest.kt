package org.balch.orpheus.features.ai.tools

import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import org.balch.orpheus.features.pulsar.vibes.FireSkyVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibeGuideToolTest {

    private val dogHouse = DogHouseVibe().vibe

    @Test
    fun `oneDecimal renders clean macro values to a single decimal place`() {
        assertEquals("0.0", oneDecimal(0.0f))
        assertEquals("0.3", oneDecimal(0.3f))
        assertEquals("0.5", oneDecimal(0.5f))
        assertEquals("1.0", oneDecimal(1.0f))
    }

    @Test
    fun `fingerprint includes name, bpm, key, progression, swing, macros, engines`() {
        val fp = vibeFingerprint(dogHouse)
        assertTrue(fp.startsWith(dogHouse.name + " — "), "fingerprint should start with the vibe name: $fp")
        assertTrue(fp.contains("85bpm"), "missing bpm: $fp")
        assertTrue(fp.contains("E PHRYGIAN"), "missing root+scale: $fp")
        assertTrue(fp.contains("BLUES"), "missing progression style: $fp")
        assertTrue(fp.contains("swing 10%"), "missing swing: $fp")
        assertTrue(
            Regex("""energy \d\.\d complexity \d\.\d space \d\.\d mood \d\.\d""").containsMatchIn(fp),
            "macros not rendered to one decimal: $fp",
        )
        assertTrue(fp.contains("engines ["), "missing engines segment: $fp")
        // DogHouse opens BD/SD/HH on its first three tracks.
        assertTrue(fp.contains("BD"), "missing a known engine id: $fp")
    }

    @Test
    fun `fingerprint includes sections and band when present, omits lick when absent`() {
        val fp = vibeFingerprint(dogHouse)
        assertTrue(fp.contains("${dogHouse.arrangement!!.sections.size} sections"), "missing sections: $fp")
        assertTrue(fp.contains("band(${dogHouse.band!!.members.size})"), "missing band: $fp")
        assertFalse(fp.contains("· lick"), "DogHouse has no lick but fingerprint shows one: $fp")
    }

    @Test
    fun `fingerprint omits sections and band when absent`() {
        val bare = dogHouse.copy(arrangement = null, band = null)
        val fp = vibeFingerprint(bare)
        assertFalse(fp.contains("sections"), "should omit sections when arrangement is null: $fp")
        assertFalse(fp.contains("band("), "should omit band when band is null: $fp")
    }

    @Test
    fun `fingerprint includes lick segment when a lick is present`() {
        val withLick = dogHouse.copy(lick = FireSkyVibe().vibe.lick)
        assertTrue(FireSkyVibe().vibe.lick != null, "precondition: Fire Sky has a lick to borrow")
        assertTrue(vibeFingerprint(withLick).contains("· lick"), "lick segment missing when lick present")
    }

    @Test
    fun `fingerprint includes lick-rotation and anomalies segments`() {
        val l = FireSkyVibe().vibe.lick!!
        val withRotation = dogHouse.copy(
            lickRotation = LickRotation(pool = listOf(l, l)),
            anomalies = listOf(LickAnomaly(lick = l, chance = 0.2f)),
        )
        val fp = vibeFingerprint(withRotation)
        assertTrue(fp.contains("lick-rotation(2)"), "lick-rotation segment missing/incorrect: $fp")
        assertTrue(fp.contains("anomalies[lick]"), "anomalies segment missing/incorrect: $fp")
    }

    @Test
    fun `catalogSection lists one bullet per vibe with a header`() {
        val section = catalogSection(listOf(dogHouse))
        assertTrue(section.contains("Available vibes"), "missing catalog header: $section")
        assertTrue(section.contains("- " + vibeFingerprint(dogHouse)), "missing bulleted fingerprint line: $section")
    }

    @Test
    fun `static guide carries the stable semantic markers`() {
        // Anchor a few load-bearing phrases so the guide can't silently lose its highest-value content.
        assertTrue(STATIC_GUIDE.contains("Translation recipes"), "guide missing the recipes section")
        assertTrue(STATIC_GUIDE.contains("harmonics"), "guide missing the DX patch-bank gotcha")
        assertTrue(STATIC_GUIDE.contains("exactly 8 tracks"), "guide missing the 8-track invariant")
        assertTrue(STATIC_GUIDE.contains("ROOT_ONLY"), "guide missing the bass chordFollow invariant")
    }

    @Test
    fun `buildGuide concatenates the static guide and the catalog`() {
        val guide = buildGuide(listOf(dogHouse))
        assertTrue(guide.contains("Translation recipes"), "buildGuide dropped the static guide")
        assertTrue(guide.contains("Available vibes"), "buildGuide dropped the catalog header")
        assertTrue(guide.contains("- " + vibeFingerprint(dogHouse)), "buildGuide dropped the catalog lines")
        // Static guide comes first, catalog second.
        assertTrue(
            guide.indexOf("Translation recipes") < guide.indexOf("Available vibes"),
            "static guide should precede the catalog",
        )
    }
}
