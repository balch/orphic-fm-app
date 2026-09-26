package org.balch.orpheus.ui.viz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The overhang report: chrome that lays out smaller than it draws (the phone bar's raised dome)
 * reports its whole rect, and takes it back when it leaves. One density unit per pixel.
 *
 * ./gradlew :ui:widgets:jvmTest --tests '*VizStageOverhangTest*' --rerun
 */
class VizStageOverhangTest {
    /** The 60x70 node, placed 30px above its 40px slot at y 100. */
    private val overhang = Rect(20f, 70f, 80f, 140f)

    /**
     * A clipped 40px slot whose child lays out 40px tall but places a 70px node 30px above it,
     * like the dome. [onBounds] gets that node's boundsInRoot(), which the clip cuts.
     */
    private fun scene(stage: VizStage, show: () -> Boolean = { true }, onBounds: (Rect) -> Unit = {}) =
        ImageComposeScene(200, 200, Density(1f)) {
            Box(Modifier.offset(0.dp, 100.dp).size(100.dp, 40.dp).clipToBounds()) {
                if (show()) {
                    Box(
                        Modifier
                            .layout { measurable, _ ->
                                val placeable = measurable.measure(Constraints.fixed(60, 70))
                                layout(60, 40) { placeable.place(20, -30) }
                            }
                            .vizStageOverhang(stage)
                            .onGloballyPositioned { onBounds(it.boundsInRoot()) },
                    )
                }
            }
        }

    @Test
    fun anOverhangReportsItsWholeRectUnclipped() {
        val stage = VizStage()
        var clipped: Rect? = null
        val scene = scene(stage, onBounds = { clipped = it })
        try {
            scene.render()
            assertEquals(Rect(20f, 100f, 80f, 140f), clipped, "sanity: boundsInRoot() loses the part above the clip")
            assertEquals(overhang, stage.chromeOverhang)
        } finally {
            scene.close()
        }
    }

    @Test
    fun anOverhangThatLeavesTakesItsReportWithIt() {
        val stage = VizStage()
        var show by mutableStateOf(true)
        val scene = scene(stage, show = { show })
        try {
            scene.render()
            assertEquals(overhang, stage.chromeOverhang)
            show = false
            Snapshot.sendApplyNotifications()
            scene.render()
            assertNull(stage.chromeOverhang, "a stale overhang would keep a hole in the wake overlay")
        } finally {
            scene.close()
        }
    }
}
