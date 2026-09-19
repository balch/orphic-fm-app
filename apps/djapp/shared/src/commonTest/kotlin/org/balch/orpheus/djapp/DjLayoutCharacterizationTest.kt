package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** One screen size and the layout today's code picks for it. */
internal data class LayoutCase(
    val label: String,
    val width: Float,
    val height: Float,
    val tvModeAllowed: Boolean,
    val expected: String,
)

/** Every boundary and every real target device. The DjLayout refactor must reproduce it exactly. */
internal val LayoutCharacterization: List<LayoutCase> = listOf(
    LayoutCase("phone portrait", 412f, 915f, true, "Portrait"),
    LayoutCase("phone landscape", 892f, 412f, true, "Landscape"),
    LayoutCase("fold cover", 360f, 840f, true, "Portrait"),
    LayoutCase("fold inner upright", 752f, 834.667f, true, "PortraitPair"),
    LayoutCase("fold inner sideways, unscaled", 834.667f, 752f, true, "Landscape"),
    LayoutCase("fold inner sideways, scaled", 1280f, 1153f, true, "LargeScreen"),
    LayoutCase("fold flex-panel top half", 835f, 376f, true, "Landscape"),
    LayoutCase("tv 1080p", 960f, 540f, true, "LargeScreen"),
    LayoutCase("tv widened", 1280f, 720f, true, "LargeScreen"),
    LayoutCase("pixel tablet landscape", 1280f, 800f, true, "LargeScreen"),
    LayoutCase("pixel tablet portrait", 800f, 1280f, true, "PortraitPair"),
    LayoutCase("ipad 13 portrait", 1032f, 1376f, true, "LargeScreen"),
    LayoutCase("desktop window", 1200f, 800f, false, "Landscape"),
    LayoutCase("desktop fullscreen", 1512f, 982f, true, "LargeScreen"),
    LayoutCase("dock width just under", 899f, 600f, true, "Landscape"),
    LayoutCase("dock width exact", 900f, 600f, true, "LargeScreen"),
    LayoutCase("dock height just under", 1000f, 499f, true, "Landscape"),
    LayoutCase("dock height exact", 1000f, 500f, true, "LargeScreen"),
    LayoutCase("pair width just under", 699f, 900f, false, "Portrait"),
    LayoutCase("pair width exact", 700f, 900f, false, "PortraitPair"),
    LayoutCase("square, wide enough for a pair", 800f, 800f, true, "PortraitPair"),
    LayoutCase("square, phone", 400f, 400f, true, "Portrait"),
)

class DjLayoutCharacterizationTest {
    @Test
    fun resolveLayoutReproducesTheTableWithNoHinge() {
        LayoutCharacterization.forEach { case ->
            val layout = resolveLayout(case.width.dp, case.height.dp, case.tvModeAllowed, hinge = null)
            assertEquals(case.expected, layout.label(), case.label)
        }
    }

    private fun DjLayout.label(): String = when (this) {
        DjLayout.Portrait -> "Portrait"
        DjLayout.PortraitPair -> "PortraitPair"
        DjLayout.Landscape -> "Landscape"
        DjLayout.LargeScreen -> "LargeScreen"
        is DjLayout.Tabletop -> "Tabletop"
    }
}
