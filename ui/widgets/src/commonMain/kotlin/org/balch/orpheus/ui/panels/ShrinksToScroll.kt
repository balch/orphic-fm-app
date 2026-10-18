package org.balch.orpheus.ui.panels

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Marks a region that scrolls its own content: its min intrinsic height is capped at [minHeight],
 * so a slot's fit probe lets it shrink and scroll inside instead of scrolling the whole panel.
 */
fun Modifier.shrinksToScroll(minHeight: Dp): Modifier = this then ShrinksToScrollElement(minHeight)

private data class ShrinksToScrollElement(val minHeight: Dp) : ModifierNodeElement<ShrinksToScrollNode>() {
    override fun create() = ShrinksToScrollNode(minHeight)
    override fun update(node: ShrinksToScrollNode) {
        node.minHeight = minHeight
    }
}

private class ShrinksToScrollNode(var minHeight: Dp) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        minOf(measurable.minIntrinsicHeight(width), minHeight.roundToPx())
}
