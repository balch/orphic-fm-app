package org.balch.orpheus.core.audio.dsp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps StemLayout in step with the stem capture defines in orpheus_dsp.h. */
class StemLayoutTest {
    private val header = File("../../liborpheus_dsp/include/orpheus_dsp.h")

    private fun define(name: String): Int {
        assertTrue(header.exists(), "missing C header ${header.absolutePath}")
        val m = Regex("""#define\s+$name\s+(\d+)""").find(header.readText())
        requireNotNull(m) { "could not find `#define $name` in ${header.name}" }
        return m.groupValues[1].toInt()
    }

    @Test
    fun matchesNativeHeader() {
        assertEquals(define("ORPHEUS_STEM_CHANNELS"), StemLayout.STEMS)
        assertEquals(define("ORPHEUS_STEM_FLOATS_PER_FRAME"), StemLayout.FLOATS_PER_FRAME)
    }

    @Test
    fun namesAreOrdered() {
        assertEquals(StemLayout.STEMS, StemLayout.NAMES.size)
        assertEquals("track0", StemLayout.NAMES.first())
        assertEquals("out", StemLayout.NAMES.last())
        assertEquals("bus", StemLayout.NAMES[11])
    }
}
