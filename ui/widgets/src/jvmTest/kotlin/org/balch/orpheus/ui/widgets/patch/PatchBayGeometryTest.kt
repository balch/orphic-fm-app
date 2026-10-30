package org.balch.orpheus.ui.widgets.patch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatchBayGeometryTest {

    @Test
    fun curveStartsAndEndsAtJacks() {
        val from = Offset(10f, 20f)
        val to = Offset(300f, 140f)
        val curve = cableCurve(from, to, samples = 24)
        assertEquals(25, curve.size)
        assertEquals(from, curve.first())
        assertEquals(to, curve.last())
        // The S-curve leaves and arrives level, so its middle sits halfway between the jacks.
        val mid = curve[12]
        assertEquals(155f, mid.x, 0.5f)
        assertEquals(80f, mid.y, 0.5f)
    }

    @Test
    fun hitCableFindsNearestWithinTolerance() {
        val top = cableCurve(Offset(0f, 0f), Offset(200f, 0f))
        val lower = cableCurve(Offset(0f, 20f), Offset(200f, 20f))
        assertEquals(1, hitCable(Offset(100f, 14f), listOf(top, lower), tolerance = 8f))
        assertEquals(0, hitCable(Offset(100f, 5f), listOf(top, lower), tolerance = 8f))
    }

    @Test
    fun hitCableMissesOutsideTolerance() {
        val curve = cableCurve(Offset(0f, 0f), Offset(200f, 100f))
        assertNull(hitCable(Offset(10f, 90f), listOf(curve), tolerance = 8f))
        assertNull(hitCable(Offset(10f, 10f), emptyList(), tolerance = 8f))
    }

    @Test
    fun nearestJackRespectsRadius() {
        val lfo = JackId.Out(PatchSource.LFO_A)
        val morph = JackId.In(PatchDest.MORPH, VoiceSlot.BOTH)
        val jacks = mapOf(lfo to Offset(10f, 10f), morph to Offset(100f, 10f))
        assertEquals(lfo, nearestJack(Offset(14f, 12f), jacks, radius = 10f))
        assertEquals(morph, nearestJack(Offset(92f, 10f), jacks, radius = 10f))
        assertNull(nearestJack(Offset(55f, 10f), jacks, radius = 10f))
    }

    /**
     * The legacy LFO conversion on OSC: A·B to timbre and fm, A to morph, B to harmonics, A 90° to
     * pitch. Every midpoint shares one x, so knobs at the midpoints would stack.
     */
    private fun legacyLfoCurves(outX: Float, inX: Float, outPitch: Float, inPitch: Float): List<List<Offset>> {
        fun out(row: Int) = Offset(outX, 30f + row * outPitch)
        fun input(row: Int) = Offset(inX, 30f + row * inPitch)
        return listOf(
            cableCurve(out(0), input(1)), // A·B -> timbre
            cableCurve(out(1), input(2)), // A -> morph
            cableCurve(out(2), input(0)), // B -> harmonics
            cableCurve(out(3), input(6)), // A 90° -> pitch
            cableCurve(out(0), input(3)), // A·B -> fm
        )
    }

    @Test
    fun legacyLfoKnobsDoNotOverlap() {
        val box = Size(KnobBoxWidth.value, KnobBoxHeight.value)
        val anchor = Offset(KnobBoxWidth.value / 2f, KnobDial.value / 2f)
        // The popover's spacing, then the sheet's taller rows across a narrow phone.
        for (curves in listOf(legacyLfoCurves(70f, 380f, 18f, 24f), legacyLfoCurves(70f, 250f, 30f, 30f))) {
            val knobs = placeKnobs(curves, box, anchor)
            assertEquals(knobs, placeKnobs(curves, box, anchor), "the layout is deterministic")
            knobs.forEachIndexed { i, k -> assertTrue(k in curves[i], "knob $i sits on its own cable") }
            val rects = knobs.map { Rect(it - anchor, box) }
            for (a in rects.indices) for (b in a + 1 until rects.size) {
                assertTrue(!rects[a].overlaps(rects[b]), "knobs $a and $b overlap: ${rects[a]} ${rects[b]}")
            }
        }
    }

    @Test
    fun lonelyKnobSitsAtTheMiddle() {
        val curve = cableCurve(Offset(0f, 0f), Offset(200f, 100f))
        assertEquals(listOf(curve[12]), placeKnobs(listOf(curve), Size(KnobBoxWidth.value, KnobBoxHeight.value), Offset(KnobBoxWidth.value / 2f, KnobDial.value / 2f)))
    }
}
