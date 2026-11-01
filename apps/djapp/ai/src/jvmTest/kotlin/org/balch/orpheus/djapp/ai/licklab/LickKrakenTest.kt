package org.balch.orpheus.djapp.ai.licklab

import org.balch.orpheus.djapp.ai.songref.intervals
import org.balch.orpheus.features.pulsar.models.ScaleType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Cases worked by hand from `kraken_apply` and `quantize_to_scale` in pulsar_kraken.h / pulsar_pattern_gen.h. */
class LickKrakenTest {
    private val b = 11

    @Test
    fun `shifts in B minor`() {
        val cases = listOf(
            // note, target, expected
            Triple(71, -1, 71), // home
            Triple(71, 0, 76), // IV
            Triple(71, 1, 78), // V
            Triple(71, 2, 74), // relative major: degree 0 lands on D
            Triple(73, 2, 76), // C# (degree 1) -> E
            Triple(72, 2, 75), // C is a passing tone: keeps its distance, +3
            Triple(74, 3, 75), // parallel major: D (minor third) -> D#
            Triple(78, 3, 78), // F# (fifth) stays
        )
        for ((note, target, want) in cases) {
            assertEquals(want, LickKraken.apply(note, b, ScaleType.MINOR, target), "note $note target $target")
        }
    }

    @Test
    fun `quantize to scale`() {
        val major = ScaleType.MAJOR.intervals()
        assertEquals(60, LickKraken.quantize(61, 0, major)) // tie goes to the first degree
        assertEquals(67, LickKraken.quantize(66, 0, ScaleType.PENTATONIC.intervals()))
        assertEquals(31, LickKraken.quantize(20, 0, major)) // lifted above 24
        assertEquals(88, LickKraken.quantize(100, 0, major)) // dropped below 96
        assertEquals(61, LickKraken.quantize(61, 0, ScaleType.CHROMATIC.intervals()))
    }
}
