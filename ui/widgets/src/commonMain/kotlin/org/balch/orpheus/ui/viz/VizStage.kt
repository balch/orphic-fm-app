package org.balch.orpheus.ui.viz

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * The window area left for the visualization's picture: between the header and the nav. Root
 * coordinates, pixels.
 *
 * The host reports it, a visualization reads it. [bounds] is written from the layout phase and
 * read from wherever the picture is sized, so every read should be scoped as tightly as the
 * caller can manage.
 */
@Stable
class VizStage {
    /** Null until a host reports one, which means the picture may use the whole window. */
    var bounds: Rect? by mutableStateOf(null)
        private set

    /**
     * A band of chrome drawn inside [bounds] that never fades with the panels. Only the tabletop
     * layout has one: its header sits between the folded halves, so the picture spans it while
     * taps on it still have to land.
     */
    var chromeBand: Rect? by mutableStateOf(null)
        private set

    /**
     * Chrome from outside [bounds] that reaches into it and never fades: the phone bar's raised
     * dome. Independent of [chromeBand], since the tabletop layout has both.
     */
    var chromeOverhang: Rect? by mutableStateOf(null)
        private set

    /** onGloballyPositioned fires on every layout pass, so only a real move is published. */
    fun report(rect: Rect) {
        if (rect != bounds) bounds = rect
    }

    /** Null in every layout whose chrome already sits outside [bounds]. */
    fun reportChromeBand(rect: Rect?) {
        if (rect != chromeBand) chromeBand = rect
    }

    /** Null wherever no chrome reaches into [bounds]. */
    fun reportChromeOverhang(rect: Rect?) {
        if (rect != chromeOverhang) chromeOverhang = rect
    }
}

/**
 * Null wherever nothing reports a stage (the Orpheus app), where the picture keeps the whole
 * window it has always had.
 */
val LocalVizStage = compositionLocalOf<VizStage?> { null }

/** Reports this node's root bounds as the stage. */
fun Modifier.vizStage(stage: VizStage?): Modifier =
    if (stage == null) this else onGloballyPositioned { stage.report(it.boundsInRoot()) }

/** Reports this node as the strip of never-fading chrome sitting inside the stage. */
fun Modifier.vizStageChrome(stage: VizStage?): Modifier =
    if (stage == null) this else onGloballyPositioned { stage.reportChromeBand(it.boundsInRoot()) }

/**
 * Reports this node as chrome reaching into the stage from outside it. Its whole rect, unclipped,
 * since a parent may lay out smaller than the node draws; cleared when the node leaves.
 */
fun Modifier.vizStageOverhang(stage: VizStage?): Modifier =
    if (stage == null) this else this then StageOverhangElement(stage)

private data class StageOverhangElement(val stage: VizStage) : ModifierNodeElement<StageOverhangNode>() {
    override fun create() = StageOverhangNode(stage)

    override fun update(node: StageOverhangNode) = node.retarget(stage)
}

private class StageOverhangNode(private var stage: VizStage) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var reported: Rect? = null

    fun retarget(to: VizStage) {
        if (to === stage) return
        val last = reported
        clear()
        stage = to
        last?.let(::publish)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) =
        publish(coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false))

    override fun onDetach() = clear()

    private fun publish(rect: Rect) {
        reported = rect
        stage.reportChromeOverhang(rect)
    }

    /** Clears only its own rect, so a newer reporter's survives this one leaving. */
    private fun clear() {
        if (reported != null && stage.chromeOverhang == reported) stage.reportChromeOverhang(null)
        reported = null
    }
}
