package org.balch.orpheus.ui.viz

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

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

    /** onGloballyPositioned fires on every layout pass, so only a real move is published. */
    fun report(rect: Rect) {
        if (rect != bounds) bounds = rect
    }

    /** Null in every layout whose chrome already sits outside [bounds]. */
    fun reportChromeBand(rect: Rect?) {
        if (rect != chromeBand) chromeBand = rect
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
