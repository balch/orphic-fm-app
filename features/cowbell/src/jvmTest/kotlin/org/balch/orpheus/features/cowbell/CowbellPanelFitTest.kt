package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.panels.LocalPanelTopInset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The panel at the 320x330 slot design size and a short 320x260 slot, with "more" open and
 * closed: the knob labels, the mode and fill pills and the morph chip (in "more") lay out whole inside it.
 *
 * ./gradlew :features:cowbell:jvmTest --tests '*CowbellPanelFitTest*'
 */
class CowbellPanelFitTest {

    private val required = listOf("TONE", "RING", "INTENSITY", "TAP", "SHAKE", "HOLD", "MARBLE", "BEADS", "SEEDS", "JINGLES", "Metal")
    private val more = listOf("SHARDS", "OCTAVE", "SNAP", "LEVEL")

    /** [inset] is what the host draws over the body: a slot's 8dp of buttons, the phone's 44dp header. */
    private fun assertFits(
        width: Int,
        height: Int,
        moreOpen: Boolean,
        inset: Dp = 8.dp,
        fontScale: Float = 1f,
    ) {
        val labels = if (moreOpen) required + more else required
        CowbellScene(width, height, fontScale) {
            CompositionLocalProvider(LocalPanelTopInset provides inset) {
                CowbellPanelContent(
                    feature = CowbellViewModel.previewFeature(),
                    modifier = Modifier.fillMaxSize(),
                    isExpanded = true,
                    moreOpenByDefault = moreOpen,
                )
            }
        }.use { s ->
            val what = "${width}x$height" + (if (moreOpen) " more" else "") + (if (fontScale != 1f) " @$fontScale" else "")
            val nodes = s.nodes()
            for (label in labels) {
                val node = nodes.firstOrNull { it.text() == label }
                assertTrue(node != null, "$what: no '$label'")
                val b = node.laidOut
                assertTrue(
                    b.left >= -0.5f && b.top >= -0.5f && b.right <= width + 0.5f && b.bottom <= height + 0.5f,
                    "$what: '$label' $b runs outside the panel",
                )
                assertTrue(b.height > 4f && b.width > 4f, "$what: '$label' is squeezed to $b")
                assertTrue(node.boundsInRoot.width >= b.width - 0.5f, "$what: '$label' is clipped to ${node.boundsInRoot}")
                // Pills and knob labels alike: an ellipsized label keeps its height and passes every check above.
                assertFalse(node.textOverflows(), "$what: '$label' wants ${node.textWidthShortfall()}px more than it gives (${node.size.width}px)")
            }
            // Below whatever the host draws over the top of the body.
            for (label in listOf("TAP", "MARBLE")) {
                val top = nodes.first { it.text() == label }.laidOut.top
                assertTrue(top >= inset.value, "$what: '$label' starts at $top, under the host's ${inset.value}dp of chrome")
            }
            if (!moreOpen) assertTrue(nodes.none { it.text() == "OCTAVE" }, "$what: the more row shows")
            val bell = s.bell()
            // The bell yields width to the controls at a large font, down to 30% of the panel.
            val minBell = if (fontScale > 1f) 0.3f else 0.35f
            assertTrue(bell.width >= width * minBell, "$what: the bell is only ${bell.width} wide")
            assertTrue(bell.height >= height * 0.6f, "$what: the bell is only ${bell.height} tall")
            // The chip rides in the bell's box, under the bell, and shows only with MORE.
            assertTrue(nodes.any { it.text() == "SHARDS" } == moreOpen, "$what: the morph chip and MORE disagree")
        }
    }

    @Test
    fun fitsTheSlotDesignSize() = assertFits(320, 330, moreOpen = true)

    /** Clear of the phone's 44dp prev/next header, at a phone's top section. */
    @Test
    fun fitsBelowThePhoneNavHeader() = assertFits(380, 380, moreOpen = true, inset = 44.dp)

    @Test
    fun fitsAShortSlot() = assertFits(320, 260, moreOpen = true)

    @Test
    fun closedFitsTheSlotDesignSize() = assertFits(320, 330, moreOpen = false)

    @Test
    fun closedFitsAShortSlot() = assertFits(320, 260, moreOpen = false)

    /** The Fold cover at the system's large font: the controls column once ellipsized INTENSITY. */
    @Test
    fun fitsTheFoldCoverAtLargeFont() {
        assertFits(350, 290, moreOpen = false, fontScale = 1.3f)
        assertFits(350, 290, moreOpen = true, fontScale = 1.3f)
    }

    /** Orpheus's slots (320dp and wider, font scale 1) keep the bell's 0.45 share. */
    @Test
    fun orpheusKeepsItsProportionsAtFontScaleOne() {
        for (panel in listOf(320, 340, 380, 412, 700)) {
            assertEquals(0.45f, bellWidthFraction((panel - 12).dp, 1f), 0.0001f, "$panel dp panel")
        }
        // The same slot at 320 dp draws the same bell it drew before the floor existed.
        CowbellScene(320, 330) {
            CowbellPanelContent(feature = CowbellViewModel.previewFeature(), modifier = Modifier.fillMaxSize(), isExpanded = true)
        }.use { s -> assertEquals(136f, s.bell().width, 0.5f) }
    }

    @Test
    fun bellYieldsToTheControlsAtLargeFont() {
        assertTrue(bellWidthFraction(338.dp, 1.3f) < 0.4f, "the Fold cover at 1.3")
        assertEquals(0.45f, bellWidthFraction(400.dp, 1.3f), 0.0001f, "a 412 dp panel at 1.3 keeps 0.45")
        assertEquals(0.3f, bellWidthFraction(100.dp, 2f), 0.0001f, "never under 0.3")
    }

    /** With the strip hidden, the panel names itself. */
    @Test
    fun hiddenHeaderPanelShowsItsTitle() {
        CowbellScene(320, 330) {
            CowbellPanelContent(
                feature = CowbellViewModel.previewFeature(),
                modifier = Modifier.fillMaxSize(),
                isExpanded = true,
                showCollapsedHeader = false,
            )
        }.use { s -> assertTrue(s.nodes().any { it.text() == "BELL" }, "the panel has no BELL title") }
    }

    @Test
    fun moreIsOpenByDefault() {
        CowbellScene(320, 330) {
            CowbellPanel(feature = CowbellViewModel.previewFeature(), modifier = Modifier.fillMaxSize(), isExpanded = true)
        }.use { s ->
            assertTrue(s.nodes().any { it.text() == "LEVEL" }, "LEVEL is not up front")
        }
    }
}
