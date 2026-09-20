package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FaceKeyframesTest {
    private fun frames(count: Int, decoded: MutableList<Int>) = FaceKeyframes(
        exists = { it < count },
    ) { i -> decoded += i; ImageBitmap(2, 2) }

    @Test fun `count comes from the resources present`() = runTest {
        assertEquals(7, frames(7, mutableListOf()).count())
    }

    @Test fun `only the window around the stage stays decoded`() = runTest {
        val k = frames(7, mutableListOf())
        k.count()
        k.prepare(0)
        assertNotNull(k.pair(0)); assertNotNull(k[2])
        k.prepare(4)
        assertNull(k[0]); assertNull(k[1])
        assertNotNull(k.pair(4)); assertNotNull(k[6])
    }

    @Test fun `a frame is decoded once while it stays in the window`() = runTest {
        val decoded = mutableListOf<Int>()
        val k = frames(7, decoded)
        k.count()
        assertEquals(emptyList(), decoded, "count() must not decode")
        decoded.clear()
        k.prepare(2); k.prepare(2); k.prepare(3)
        assertEquals(listOf(2, 3, 4, 5), decoded)
    }

    @Test fun `pair is null until both frames are in`() = runTest {
        val k = frames(7, mutableListOf())
        k.count()
        assertNull(k.pair(3))
    }

    @Test fun `clear during prepare does not resurrect frames`() = runTest {
        lateinit var k: FaceKeyframes
        k = FaceKeyframes(exists = { it < 7 }) { i ->
            // Simulates onDeactivate firing while prepare() is mid-decode on another dispatcher.
            if (i == 1) k.clear()
            ImageBitmap(2, 2)
        }
        k.count()
        k.prepare(0)
        assertNull(k.pair(0))
        assertNull(k[0]); assertNull(k[1]); assertNull(k[2])
    }

    @Test fun `two overlapping prepares both end with their pairs available`() = runTest {
        lateinit var k: FaceKeyframes
        var triggered = false
        k = FaceKeyframes(exists = { it < 7 }) { i ->
            // A fast multi-stage jump starts a second prepare() before the first has published.
            if (i == 0 && !triggered) {
                triggered = true
                k.prepare(4)
            }
            ImageBitmap(2, 2)
        }
        k.count()
        k.prepare(0)
        assertNotNull(k.pair(0), "the first prepare's window should not be discarded")
        assertNotNull(k.pair(4), "the second prepare's window should not be discarded")
    }
}
